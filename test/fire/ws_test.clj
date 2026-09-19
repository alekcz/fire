(ns fire.ws-test
  "fire.ws two ways. Against a real websocket server with no network —
   http-kit's server side is already a dependency, so an echo server on a
   loopback port is all that takes — for the upgrade, a round trip, large
   messages, concurrent senders and the close handshake. And at the frame
   level, on hand-built bytes, for what an echo server never sends: a message
   split across continuation frames, a ping, the three length encodings."
  (:require [clojure.test :refer [deftest is testing]]
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
    (doseq [^String text ["" "hello" (apply str (repeat 70000 "x"))]]
      (let [payload (utf8 text)
            frame (ws/read-frame (stream (ws/encode-frame :text payload)))]
        (is (:fin? frame))
        (is (= :text (:opcode frame)))
        (is (= text (String. ^bytes (:payload frame) StandardCharsets/UTF_8))))))

  (testing "the mask bit is set on every client frame, as the spec demands"
    (is (bit-test (aget ^bytes (ws/encode-frame :text (utf8 "x")) 1) 7))
    (is (bit-test (aget ^bytes (ws/encode-frame :close (byte-array 2)) 1) 7))))

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
