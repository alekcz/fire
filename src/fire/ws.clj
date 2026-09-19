(ns fire.ws
  "The websocket client under fire.socket, over the JDK's own java.net.http.

   This used to be gniazdo, which brought seven Jetty 9.4 jars onto every
   consumer's classpath — Jetty 9.4 has been end of life since 2022 — for a
   client the JDK has shipped since 11. What fire.socket needs from a
   websocket is three things: open one with callbacks, send a text message,
   close it. That is all this is.

   Two things the JDK client does that Jetty did for you, both handled here:
   a text message can arrive in several frames, so they are gathered until the
   last one and delivered whole; and it refuses a second sendText while the
   first is in flight, so sends are serialised and each waits to complete.

   Requires Java 11."
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def ^:private connect-timeout-secs 30)

(defn- listener
  "A WebSocket$Listener that reassembles fragmented text and hands each whole
   message to on-receive. Every handler asks for the next frame itself,
   because overriding the interface's default methods takes that with it."
  ^WebSocket$Listener [on-receive on-close on-error]
  (let [partial (StringBuilder.)]
    (reify WebSocket$Listener
      (onOpen [_ ws]
        (.request ^WebSocket ws 1))
      (onText [_ ws data last?]
        (.append partial ^CharSequence data)
        (when last?
          (let [message (str partial)]
            (.setLength partial 0)
            (on-receive message)))
        (.request ^WebSocket ws 1)
        nil)
      (onClose [_ _ code reason]
        (on-close code reason)
        nil)
      (onError [_ _ error]
        (on-error error)))))

(defn connect
  "Open a websocket to `url` (ws:// or wss://) and return a connection.
   `on-receive` gets each whole text message as a string; `on-close` gets the
   status code and reason when the peer closes; `on-error` gets a Throwable
   when the connection fails after it opened. Throws if the connection cannot
   be made within thirty seconds."
  [^String url & {:keys [on-receive on-close on-error]
                  :or {on-receive (fn [_]) on-close (fn [_ _]) on-error (fn [_])}}]
  (let [socket (-> (HttpClient/newHttpClient)
                   (.newWebSocketBuilder)
                   (.buildAsync (URI/create url) (listener on-receive on-close on-error))
                   (.get connect-timeout-secs TimeUnit/SECONDS))]
    {:socket socket
     ;; the JDK client refuses overlapping sends, so they queue on this
     :send-lock (Object.)}))

(defn send!
  "Send one text message, whole, and wait until it has gone."
  [{:keys [^WebSocket socket send-lock]} ^String message]
  (locking send-lock
    (.get ^java.util.concurrent.CompletableFuture (.sendText socket message true)))
  nil)

(defn close!
  "Start a normal close. The peer's answering close arrives at on-close."
  [{:keys [^WebSocket socket send-lock]}]
  (locking send-lock
    (when-not (.isOutputClosed socket)
      (.get ^java.util.concurrent.CompletableFuture (.sendClose socket WebSocket/NORMAL_CLOSURE ""))))
  nil)
