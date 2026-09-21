(ns fire.ws
  "The websocket client under fire.socket, on nothing but Java 8.

   This used to be gniazdo, which brought seven Jetty 9.4 jars onto every
   consumer's classpath — Jetty 9.4 has been end of life since 2022 — for the
   one websocket fire.socket opens. The JDK's own client would do, but it
   arrived in Java 11 and fire supports 8. So this is RFC 6455 directly: a
   TLS socket, an HTTP upgrade, and a frame format small enough to fit in a
   screen. What fire.socket needs from it is three things — open one with
   callbacks, send a text message, close it — and that is all this is.

   Text is delivered whole: a message that arrives in several frames is
   gathered as bytes and decoded once, at the end, so a multi-byte character
   split across a frame boundary survives. Pings are answered. Sends are
   serialised on one lock, which the reader's pongs share."
  (:require [clojure.string :as str])
  (:import [java.io DataInputStream DataOutputStream ByteArrayOutputStream
            BufferedInputStream BufferedOutputStream InputStream OutputStream EOFException]
           [java.net Socket InetSocketAddress URI]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.net.ssl SSLSocket SSLSocketFactory SSLParameters]))

(set! *warn-on-reflection* true)

(def ^:private connect-timeout-ms 30000)

;; The TCP connect above is bounded, but nothing after it is unless the socket
;; is told to be: a peer that accepts the connection and then says nothing
;; would hang the TLS handshake, or the read of the upgrade response, for as
;; long as it cared to. Jetty had an idle timeout doing this job. The read
;; loop gets the timeout taken off again once the handshake is through, since
;; a websocket that is merely quiet is not a websocket in trouble.
(def ^:private handshake-timeout-ms 30000)
(def ^:private websocket-guid "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
;; a delay, not the instance: native-image runs class initializers at BUILD
;; time and then refuses an image whose heap holds a Random — its seed would
;; be baked into the binary and every copy would draw the same masking keys.
;; Unrealized, the delay is just a function, which the image is happy to hold.
;; Same reason the sni-client delays elsewhere in fire are delays.
(def ^:private random (delay (SecureRandom.)))

(def ^:private opcodes {:continuation 0x0 :text 0x1 :binary 0x2 :close 0x8 :ping 0x9 :pong 0xA})
(def ^:private opcode-names (into {} (map (fn [[k v]] [v k]) opcodes)))

;; ---------------------------------------------------------------------------
;; frames
;; ---------------------------------------------------------------------------

(defn- mask!
  "XOR `payload` in place with the four-byte `key`, as the client side of
   RFC 6455 §5.3 requires of everything it sends. Its own inverse."
  ^bytes [^bytes payload ^bytes key]
  (dotimes [i (alength payload)]
    (aset-byte payload i (unchecked-byte (bit-xor (aget payload i) (aget key (rem i 4))))))
  payload)

(defn encode-frame
  "One masked client frame: a final frame of `opcode` carrying `payload`."
  ^bytes [opcode ^bytes payload]
  (let [out (ByteArrayOutputStream. (+ 14 (alength payload)))
        data (DataOutputStream. out)
        len (alength payload)
        key (byte-array 4)]
    (.nextBytes ^SecureRandom @random key)
    (.writeByte data (bit-or 0x80 (int (opcodes opcode))))
    (cond
      (< len 126) (.writeByte data (bit-or 0x80 len))
      (< len 65536) (do (.writeByte data (bit-or 0x80 126)) (.writeShort data len))
      :else (do (.writeByte data (bit-or 0x80 127)) (.writeLong data len)))
    (.write data key)
    (.write data ^bytes (mask! (aclone payload) key))
    (.toByteArray out)))

(defn read-frame
  "The next frame off `in`, as {:fin? :opcode :payload}. Blocks until one is
   there; throws EOFException when the stream ends first. A masked frame from
   the server is against the spec, but is unmasked rather than refused."
  [^DataInputStream in]
  (let [b0 (.readUnsignedByte in)
        b1 (.readUnsignedByte in)
        fin? (bit-test b0 7)
        opcode (bit-and b0 0x0F)
        masked? (bit-test b1 7)
        len7 (bit-and b1 0x7F)
        len (case len7
              126 (.readUnsignedShort in)
              127 (.readLong in)
              len7)
        key (when masked? (let [k (byte-array 4)] (.readFully in k) k))
        payload (byte-array len)]
    (.readFully in payload)
    {:fin? fin?
     :opcode (opcode-names opcode opcode)
     :payload (if key (mask! payload key) payload)}))

;; ---------------------------------------------------------------------------
;; the upgrade
;; ---------------------------------------------------------------------------

(defn- accept-for
  "What the server must answer to our Sec-WebSocket-Key."
  [^String key]
  (let [sha1 (MessageDigest/getInstance "SHA-1")]
    (.encodeToString (Base64/getEncoder)
                     (.digest sha1 (.getBytes (str key websocket-guid) StandardCharsets/US_ASCII)))))

(defn- read-line*
  "One CRLF-terminated line of the upgrade response, without the CRLF."
  ^String [^InputStream in]
  (let [out (ByteArrayOutputStream.)]
    (loop [prev -1]
      (let [b (.read in)]
        (cond
          (neg? b) (throw (EOFException. "connection closed during websocket handshake"))
          (and (= 13 prev) (= 10 b)) (let [bs (.toByteArray out)]
                                      (String. bs 0 (dec (alength bs)) StandardCharsets/ISO_8859_1))
          :else (do (.write out b) (recur b)))))))

(defn- handshake!
  "Send the upgrade request for `uri` and check the answer is a 101 that
   accepts our key. Throws with the status line otherwise."
  [^URI uri ^OutputStream out ^InputStream in]
  (let [key-bytes (byte-array 16)
        _ (.nextBytes ^SecureRandom @random key-bytes)
        key (.encodeToString (Base64/getEncoder) key-bytes)
        path (str (if (str/blank? (.getRawPath uri)) "/" (.getRawPath uri))
                  (when (.getRawQuery uri) (str "?" (.getRawQuery uri))))
        host (str (.getHost uri) (when (pos? (.getPort uri)) (str ":" (.getPort uri))))
        request (str "GET " path " HTTP/1.1\r\n"
                     "Host: " host "\r\n"
                     "Upgrade: websocket\r\n"
                     "Connection: Upgrade\r\n"
                     "Sec-WebSocket-Key: " key "\r\n"
                     "Sec-WebSocket-Version: 13\r\n"
                     "\r\n")]
    (.write out (.getBytes request StandardCharsets/US_ASCII))
    (.flush out)
    (let [status (read-line* in)
          headers (loop [acc {}]
                    (let [line (read-line* in)]
                      (if (str/blank? line)
                        acc
                        (let [[k v] (str/split line #":" 2)]
                          (recur (assoc acc (str/lower-case (str/trim k)) (str/trim (str v))))))))]
      (when-not (str/starts-with? status "HTTP/1.1 101")
        (throw (ex-info (str "websocket upgrade refused: " status) {:status status :headers headers})))
      (when-not (= (accept-for key) (get headers "sec-websocket-accept"))
        (throw (ex-info "websocket upgrade answered with the wrong Sec-WebSocket-Accept" {:headers headers}))))))

(defn- open-socket
  "A connected socket for `uri`: TLS for wss, with hostname verification on,
   plain for ws."
  ^Socket [^URI uri]
  (let [secure? (= "wss" (str/lower-case (str (.getScheme uri))))
        port (if (pos? (.getPort uri)) (.getPort uri) (if secure? 443 80))
        address (InetSocketAddress. (.getHost uri) (int port))]
    (if secure?
      (let [^SSLSocket socket (.createSocket ^SSLSocketFactory (SSLSocketFactory/getDefault))]
        (.connect socket address connect-timeout-ms)
        (.setSoTimeout socket handshake-timeout-ms)
        ;; createSocket() with no host leaves SNI and hostname verification
        ;; off. the host has to be put back for both, or a certificate for any
        ;; name at all would do
        (let [^SSLParameters params (doto ^SSLParameters (.getSSLParameters socket)
                                      (.setEndpointIdentificationAlgorithm "HTTPS")
                                      (.setServerNames [(javax.net.ssl.SNIHostName. (.getHost uri))]))]
          (.setSSLParameters socket params))
        (.startHandshake socket)
        socket)
      (doto (Socket.)
        (.connect address connect-timeout-ms)
        (.setSoTimeout handshake-timeout-ms)))))

;; ---------------------------------------------------------------------------
;; a connection
;; ---------------------------------------------------------------------------

(defn- send-frame! [{:keys [^OutputStream out send-lock]} opcode ^bytes payload]
  (locking send-lock
    (.write out ^bytes (encode-frame opcode payload))
    (.flush out)))

(defn- close-payload ^bytes [code]
  (byte-array [(unchecked-byte (bit-shift-right code 8)) (unchecked-byte code)]))

(defn- finish!
  "Tear the socket down and tell the owner once, whichever side ended it."
  [{:keys [^Socket socket closed on-close]} code reason]
  (when (compare-and-set! closed false true)
    (try (.close socket) (catch Exception _))
    (on-close code reason)))

(defn- read-loop
  "Read frames until the peer closes or the socket dies. Text is gathered
   across continuation frames and decoded once; pings are answered on the
   spot; a close frame is answered and ends the loop."
  [{:keys [^DataInputStream in on-receive on-error] :as conn}]
  (let [message (ByteArrayOutputStream.)]
    (try
      (loop []
        (let [{:keys [fin? opcode ^bytes payload]} (read-frame in)]
          (case opcode
            (:text :binary :continuation)
            (do (.write message payload)
                (when fin?
                  (let [whole (String. (.toByteArray message) StandardCharsets/UTF_8)]
                    (.reset message)
                    (on-receive whole)))
                (recur))

            :ping (do (try (send-frame! conn :pong payload) (catch Exception _)) (recur))
            :pong (recur)

            :close
            (let [code (if (>= (alength payload) 2)
                         (bit-or (bit-shift-left (bit-and (aget payload 0) 0xFF) 8) (bit-and (aget payload 1) 0xFF))
                         1005)
                  reason (if (> (alength payload) 2)
                           (String. payload 2 (- (alength payload) 2) StandardCharsets/UTF_8)
                           "")]
              ;; answer unless we started this, in which case ours is already sent
              (when-not @(:closing conn)
                (try (send-frame! conn :close (close-payload code)) (catch Exception _)))
              (finish! conn code reason))

            ;; an opcode this client doesn't speak: nothing to do with it
            (recur))))
      (catch EOFException _
        (finish! conn 1006 "connection closed"))
      (catch Exception e
        (when-not @(:closed conn) (on-error e))
        (finish! conn 1006 (str (.getMessage e)))))))

(defn connect
  "Open a websocket to `url` (ws:// or wss://) and return a connection.
   `on-receive` gets each whole text message as a string; `on-close` gets the
   status code and reason once, however the connection ended; `on-error` gets
   a Throwable when the connection fails after it opened. Throws if the
   connection cannot be made or the upgrade is refused."
  [^String url & {:keys [on-receive on-close on-error]
                  :or {on-receive (fn [_]) on-close (fn [_ _]) on-error (fn [_])}}]
  (let [uri (URI/create url)
        socket (open-socket uri)
        in (DataInputStream. (BufferedInputStream. (.getInputStream socket)))
        out (BufferedOutputStream. (.getOutputStream socket))]
    (try
      (handshake! uri out in)
      ;; the handshake is through, so the read loop may now block as long as
      ;; it likes: an idle websocket is normal, and firebase's own keepalives
      ;; arrive on their own schedule
      (.setSoTimeout socket 0)
      (catch Exception e
        (try (.close socket) (catch Exception _))
        (throw e)))
    (let [conn {:socket socket :in in :out out
                :send-lock (Object.)
                :closing (atom false)
                :closed (atom false)
                :on-receive on-receive :on-close on-close :on-error on-error}]
      (doto (Thread. ^Runnable #(read-loop conn) (str "fire-ws " (.getHost uri)))
        (.setDaemon true)
        (.start))
      conn)))

(defn send!
  "Send one text message, whole."
  [conn ^String message]
  (send-frame! conn :text (.getBytes message StandardCharsets/UTF_8))
  nil)

(defn close!
  "Start a normal close. The peer's answering close arrives at on-close and
   ends the connection. Closing twice is a no-op."
  [{:keys [closing closed] :as conn}]
  (when (and (not @closed) (compare-and-set! closing false true))
    (try (send-frame! conn :close (close-payload 1000))
         (catch Exception _ (finish! conn 1006 "connection closed"))))
  nil)
