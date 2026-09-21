(ns fire.ws-test
  "fire.ws two ways. Against a real websocket server with no network —
   http-kit's server side is already a dependency, so an echo server on a
   loopback port is all that takes — for the upgrade, a round trip, large
   messages, concurrent senders and the close handshake. And at the frame
   level, on hand-built bytes, for what an echo server never sends: a message
   split across continuation frames, a ping, the three length encodings."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [org.httpkit.server :as server]
            [fire.ws :as ws])
  (:import [java.io DataInputStream ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; frames
;; ---------------------------------------------------------------------------

(defn- server-frame
  "An unmasked frame as a server would send it."
  ^bytes [fin? opcode ^bytes payload]
  (let [out (ByteArrayOutputStream.)
        len (alength payload)]
    (.write out (int (bit-or (if fin? 0x80 0) opcode)))
    (cond
      (< len 126) (.write out len)
      (< len 65536) (do (.write out 126) (.write out (bit-shift-right len 8)) (.write out (bit-and len 0xFF)))
      :else (do (.write out 127) (dotimes [i 8] (.write out (int (bit-and (bit-shift-right len (* 8 (- 7 i))) 0xFF))))))
    (.write out payload)
    (.toByteArray out)))

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- stream ^DataInputStream [& frames]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes f frames] (.write out f))
    (DataInputStream. (ByteArrayInputStream. (.toByteArray out)))))

(deftest ^:offline no-random-in-the-image-heap-test
  (testing "the SecureRandom is behind a delay, so nothing constructs one at class-init"
    ;; native-image runs class initializers at BUILD time and then rejects an
    ;; image whose heap holds a Random: its seed would be baked into the
    ;; binary and every copy of it would draw the same websocket masking keys.
    ;; Holding the instance in the var root failed the graal build outright.
    ;; Asserting on the root rather than on realized? keeps this independent
    ;; of whether an earlier test in the run has already opened a socket.
    (is (instance? clojure.lang.Delay @#'ws/random))
    (is (not (instance? java.util.Random @#'ws/random)))))

(deftest ^:offline read-frame-test
  (testing "the three payload length encodings"
    (doseq [n [0 125 126 65535 65536 200000]]
      (let [payload (byte-array n (byte 7))
            frame (ws/read-frame (stream (server-frame true 0x1 payload)))]
        (is (= n (alength ^bytes (:payload frame))) (str n " bytes"))
        (is (:fin? frame))
        (is (= :text (:opcode frame))))))

  (testing "a message in three frames reads as text, continuation, continuation"
    (let [in (stream (server-frame false 0x1 (utf8 "hel"))
                     (server-frame false 0x0 (utf8 "lo, "))
                     (server-frame true 0x0 (utf8 "world")))
          frames (repeatedly 3 #(ws/read-frame in))]
      (is (= [:text :continuation :continuation] (map :opcode frames)))
      (is (= [false false true] (map :fin? frames)))))

  (testing "control frames carry their opcode"
    (is (= :ping (:opcode (ws/read-frame (stream (server-frame true 0x9 (utf8 "hi")))))))
    (is (= :close (:opcode (ws/read-frame (stream (server-frame true 0x8 (byte-array [3 (unchecked-byte 232)])))))))))

(deftest ^:offline encode-frame-test
  (testing "a client frame is masked, and unmasks back to what went in"
    ;; one payload per length encoding: 7-bit, 16-bit, 64-bit
    (doseq [^String text ["" "hello" (apply str (repeat 1000 "y")) (apply str (repeat 70000 "x"))]]
      (let [payload (utf8 text)
            frame (ws/read-frame (stream (ws/encode-frame :text payload)))]
        (is (:fin? frame))
        (is (= :text (:opcode frame)))
        (is (= text (String. ^bytes (:payload frame) StandardCharsets/UTF_8))))))

  (testing "the mask bit is set on every client frame, as the spec demands"
    (is (bit-test (aget ^bytes (ws/encode-frame :text (utf8 "x")) 1) 7))
    (is (bit-test (aget ^bytes (ws/encode-frame :close (byte-array 2)) 1) 7))))

;; ---------------------------------------------------------------------------
;; the read loop, fed frames a server would send and an echo server never does
;; ---------------------------------------------------------------------------

(defn- fake-conn
  "A connection whose input is the given frames and whose output is captured.
   The socket is a real, unconnected one, so finish! has something to close."
  [& frames]
  (let [out (ByteArrayOutputStream.)
        received (atom [])
        closed (promise)
        errors (atom [])]
    {:conn {:socket (java.net.Socket.) :in (apply stream frames) :out out
            :send-lock (Object.) :closing (atom false) :closed (atom false)
            :on-receive #(swap! received conj %)
            :on-close (fn [code reason] (deliver closed [code reason]))
            :on-error #(swap! errors conj %)}
     :sent (fn [] (let [in (DataInputStream. (ByteArrayInputStream. (.toByteArray out)))]
                    (loop [acc []] (let [f (try (ws/read-frame in) (catch java.io.EOFException _ nil))]
                                     (if f (recur (conj acc f)) acc)))))
     :received received :closed closed :errors errors}))

(deftest ^:offline read-loop-test
  (testing "a message split across frames is delivered once, whole, and decoded once"
    ;; the euro sign is three bytes; the split lands inside it
    (let [bs (utf8 "price: €5")
          {:keys [conn received closed]} (fake-conn (server-frame false 0x1 (java.util.Arrays/copyOfRange bs 0 8))
                                                    (server-frame true 0x0 (java.util.Arrays/copyOfRange bs 8 (alength bs)))
                                                    (server-frame true 0x8 (byte-array [3 (unchecked-byte 232)])))]
      (#'ws/read-loop conn)
      (is (= ["price: €5"] @received))
      (is (= [1000 ""] @closed))))

  (testing "a ping is answered with a pong carrying the same payload"
    (let [{:keys [conn sent closed]} (fake-conn (server-frame true 0x9 (utf8 "keepalive"))
                                                (server-frame true 0x8 (byte-array 0)))]
      (#'ws/read-loop conn)
      (let [[pong close] (sent)]
        (is (= :pong (:opcode pong)))
        (is (= "keepalive" (String. ^bytes (:payload pong) StandardCharsets/UTF_8)))
        (is (= :close (:opcode close)) "and the peer's close was answered"))
      ;; a close frame with no body has no code: 1005 is the reserved 'none given'
      (is (= [1005 ""] @closed))))

  (testing "a pong and an opcode this client does not speak are skipped"
    (let [{:keys [conn received closed]} (fake-conn (server-frame true 0xA (utf8 "unsolicited"))
                                                    (server-frame true 0x3 (utf8 "reserved"))
                                                    (server-frame true 0x1 (utf8 "real"))
                                                    (server-frame true 0x8 (byte-array 0)))]
      (#'ws/read-loop conn)
      (is (= ["real"] @received))
      (is (realized? closed))))

  (testing "a close with a reason passes the reason through"
    (let [reason (utf8 "going away")
          payload (byte-array (concat [3 (unchecked-byte 233)] reason))
          {:keys [conn closed]} (fake-conn (server-frame true 0x8 payload))]
      (#'ws/read-loop conn)
      (is (= [1001 "going away"] @closed))))

  (testing "the stream ending with no close frame is an abnormal close, reported once"
    (let [{:keys [conn closed errors]} (fake-conn (server-frame true 0x1 (utf8 "then silence")))]
      (#'ws/read-loop conn)
      (is (= 1006 (first @closed)))
      (is (empty? @errors) "eof is a close, not an error")
      ;; a second finish! does nothing: the owner hears about it once
      (#'ws/finish! conn 1000 "again")
      (is (= 1006 (first @closed)))))

  (testing "a server frame that is masked, against the spec, is read rather than refused"
    (let [{:keys [conn received]} (fake-conn (ws/encode-frame :text (utf8 "masked by a server"))
                                             (server-frame true 0x8 (byte-array 0)))]
      (#'ws/read-loop conn)
      (is (= ["masked by a server"] @received))))

  (testing "a close we started is not answered again when the peer's close arrives"
    (let [{:keys [conn sent closed]} (fake-conn (server-frame true 0x8 (byte-array [3 (unchecked-byte 232)])))]
      (reset! (:closing conn) true)
      (#'ws/read-loop conn)
      (is (empty? (sent)))
      (is (= [1000 ""] @closed)))))

(deftest ^:offline read-loop-failure-test
  (testing "a socket that dies mid-read is an error and an abnormal close, in that order"
    (let [{:keys [conn closed errors]} (fake-conn)
          dying (proxy [java.io.InputStream] []
                  (read ([] (throw (java.net.SocketException. "Connection reset")))
                        ([^bytes b off len] (throw (java.net.SocketException. "Connection reset")))))
          conn (assoc conn :in (DataInputStream. dying))]
      (#'ws/read-loop conn)
      (is (= 1 (count @errors)))
      (is (instance? java.net.SocketException (first @errors)))
      (is (= [1006 "Connection reset"] @closed))))

  (testing "an output that fails does not stop the reader answering, or closing"
    ;; the pong to a ping and the answer to a close both go out through the
    ;; failing stream; neither failure may kill the loop or reach the owner
    (let [{:keys [conn received closed errors]} (fake-conn (server-frame true 0x9 (utf8 "ping"))
                                                           (server-frame true 0x1 (utf8 "still here"))
                                                           (server-frame true 0x8 (byte-array 0)))
          broken (proxy [java.io.OutputStream] []
                   (write ([b] (throw (java.io.IOException. "Broken pipe")))
                          ([^bytes b off len] (throw (java.io.IOException. "Broken pipe")))))
          conn (assoc conn :out broken)]
      (#'ws/read-loop conn)
      (is (= ["still here"] @received))
      (is (empty? @errors))
      (is (= 1005 (first @closed)))))

  (testing "close! on a connection whose output has gone still ends it, once"
    (let [{:keys [conn closed]} (fake-conn)
          broken (proxy [java.io.OutputStream] []
                   (write ([b] (throw (java.io.IOException. "Broken pipe")))
                          ([^bytes b off len] (throw (java.io.IOException. "Broken pipe")))))
          conn (assoc conn :out broken)]
      (is (nil? (ws/close! conn)))
      (is (= 1006 (first @closed)))
      (is (nil? (ws/close! conn)) "and again is nothing"))))

;; ---------------------------------------------------------------------------
;; the upgrade, against servers that answer it wrongly
;; ---------------------------------------------------------------------------

(defn- with-raw-server
  "One connection, answered with `response` after the request is read, then
   closed. For the upgrade answers a websocket server never gives."
  [^String response f]
  (let [server (java.net.ServerSocket. 0)
        thread (Thread. (fn []
                          (try
                            (with-open [sock (.accept server)]
                              (let [in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))]
                                (loop [] (let [line (.readLine in)] (when-not (or (nil? line) (str/blank? line)) (recur)))))
                              (.write (.getOutputStream sock) (.getBytes response StandardCharsets/US_ASCII))
                              (.flush (.getOutputStream sock)))
                            (catch Exception _))))]
    (.start thread)
    (try (f (str "ws://127.0.0.1:" (.getLocalPort server)))
         (finally (.close server)))))

(deftest ^:offline bad-upgrade-test
  (testing "a 101 with the wrong Sec-WebSocket-Accept is refused: the server did not prove it read our key"
    (with-raw-server (str "HTTP/1.1 101 Switching Protocols\r\n"
                          "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                          "Sec-WebSocket-Accept: bm90IHRoZSBhbnN3ZXI=\r\n\r\n")
      (fn [url]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"wrong Sec-WebSocket-Accept" (ws/connect url))))))

  (testing "a server that hangs up mid-handshake is an eof, not a hang"
    (with-raw-server "HTTP/1.1 101 Switching"
      (fn [url]
        (is (thrown? java.io.EOFException (ws/connect url))))))

  (testing "a server that accepts and then says nothing gives up, rather than hanging forever"
    ;; the tcp connect succeeds, so only a socket read timeout ends this —
    ;; without one the upgrade read blocks for as long as the peer cares to
    ;; stay silent, which is what jetty's idle timeout used to prevent
    (let [server (java.net.ServerSocket. 0)
          accepted (promise)
          thread (Thread. (fn [] (try (deliver accepted (.accept server)) (catch Exception _))))]
      (.start thread)
      (try
        (with-redefs-fn {#'ws/handshake-timeout-ms 300}
          (fn []
            (let [start (System/currentTimeMillis)]
              (is (thrown? java.net.SocketTimeoutException
                    (ws/connect (str "ws://127.0.0.1:" (.getLocalPort server)))))
              (is (< (- (System/currentTimeMillis) start) 10000)
                  "gave up on the silent peer rather than blocking"))))
        (finally
          (when (realized? accepted) (try (.close ^java.net.Socket @accepted) (catch Exception _)))
          (.close server))))))

;; ---------------------------------------------------------------------------
;; against a server
;; ---------------------------------------------------------------------------

(defn- echo-handler
  "Echoes every message back with a prefix, and records closes."
  [closed]
  (fn [request]
    (server/as-channel request
      {:on-receive (fn [ch message] (server/send! ch (str "echo:" message)))
       :on-close (fn [_ status] (deliver closed status))})))

(defn- with-echo-server
  "Run f with the ws:// url of a fresh echo server and a promise of its close."
  [f]
  (let [closed (promise)
        srv (server/run-server (echo-handler closed) {:port 0 :legacy-return-value? false})]
    (try
      (f (str "ws://127.0.0.1:" (server/server-port srv)) closed)
      (finally (server/server-stop! srv)))))

(defn- take-message
  "The next message, or ::none after a bounded wait."
  [received]
  (let [p (promise)]
    (add-watch received p (fn [_ _ _ v] (when (seq v) (deliver p (first v)))))
    (try
      (when (seq @received) (deliver p (first @received)))
      (let [v (deref p 5000 ::none)]
        (when-not (= ::none v) (swap! received subvec 1))
        v)
      (finally (remove-watch received p)))))

(deftest ^:offline round-trip-test
  (with-echo-server
    (fn [url closed]
      (let [received (atom [])
            closed-with (promise)
            conn (ws/connect url
                             :on-receive #(swap! received conj %)
                             :on-close (fn [code reason] (deliver closed-with [code reason])))]
        (testing "a message goes out and its echo comes back whole"
          (ws/send! conn "hello")
          (is (= "echo:hello" (take-message received))))

        (testing "a message needing the 64-bit length encoding arrives as one string"
          ;; fire.socket chunks at 65000 chars itself, but the transport must not
          ;; be what forces that
          (let [big (apply str (repeat 200000 "x"))]
            (ws/send! conn big)
            (is (= (str "echo:" big) (take-message received)))))

        (testing "sends from several threads are serialised, so frames never interleave"
          ;; two frames written at once onto one stream is a corrupt stream and a
          ;; dropped connection; fire.socket's chunked sends rely on this
          (let [n 20
                futures (doall (for [i (range n)] (future (ws/send! conn (str "m" i)))))]
            (doseq [f futures] @f)
            (is (= (set (map #(str "echo:m" %) (range n)))
                   (set (repeatedly n #(take-message received)))))))

        (testing "a multi-byte character round-trips intact"
          (ws/send! conn "naïve — 日本語 🔥")
          (is (= "echo:naïve — 日本語 🔥" (take-message received))))

        (testing "closing reaches both ends"
          (ws/close! conn)
          (is (not= ::none (deref closed 5000 ::none)) "server saw the close")
          (let [[code _] (deref closed-with 5000 [::none])]
            (is (= 1000 code) "client got the peer's answering close"))
          ;; a second close is a no-op, not an error
          (is (nil? (ws/close! conn))))))))

(deftest ^:offline connect-failure-test
  (testing "a port nobody listens on is a throw from connect, not a socket that never speaks"
    (is (thrown? Exception (ws/connect "ws://127.0.0.1:1"))))

  (testing "a server that answers the upgrade with anything but 101 is a throw too"
    (let [srv (server/run-server (fn [_] {:status 404 :body "no websockets here"})
                                 {:port 0 :legacy-return-value? false})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"upgrade refused"
              (ws/connect (str "ws://127.0.0.1:" (server/server-port srv)))))
        (finally (server/server-stop! srv))))))
