(ns fire.ws-test
  "fire.ws against a real websocket server, with no network: http-kit's
   server side is already a dependency, so an echo server on a loopback port
   is all the test needs. What is being proven is the part the JDK client
   leaves to its caller — whole messages out of fragments, serialised sends,
   and the close handshake reaching on-close."
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as server]
            [fire.ws :as ws]))

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

        (testing "a message larger than any single frame still arrives as one string"
          ;; fire.socket chunks at 65000 chars itself, but the transport must not
          ;; be what forces that: the JDK client may split a large text message
          ;; into several frames and the listener has to gather them again
          (let [big (apply str (repeat 200000 "x"))]
            (ws/send! conn big)
            (is (= (str "echo:" big) (take-message received)))))

        (testing "sends from several threads are serialised rather than refused"
          ;; the JDK client throws IllegalStateException on an overlapping sendText;
          ;; fire.socket's chunked sends and firebase's keepalives both rely on
          ;; that never reaching the caller
          (let [n 20
                futures (doall (for [i (range n)] (future (ws/send! conn (str "m" i)))))]
            (doseq [f futures] @f)
            (is (= (set (map #(str "echo:m" %) (range n)))
                   (set (repeatedly n #(take-message received)))))))

        (testing "closing reaches both ends"
          (ws/close! conn)
          (is (not= ::none (deref closed 5000 ::none)) "server saw the close")
          (let [[code _] (deref closed-with 5000 [::none])]
            (is (= 1000 code) "client got the peer's answering close"))
          ;; a second close is a no-op, not an error
          (is (nil? (ws/close! conn))))))))

(deftest ^:offline connect-failure-test
  (testing "a port nobody listens on is a throw from connect, not a socket that never speaks"
    (is (thrown? Exception (ws/connect "ws://127.0.0.1:1")))))
