(ns fire.socket
  (:require [fire.utils :as u]
            [fire.auth :as auth]
            [gniazdo.core :as ws]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-uuid :as uuid]) 
  (:import [org.eclipse.jetty.websocket.client WebSocketClient]
           [org.eclipse.jetty.websocket.common WebSocketSession])            
  (:refer-clojure :exclude [read])           
  (:gen-class))

;Inferred from https://github.com/firebase/firebase-js-sdk/blob/8bece487710aa6315c7dd98bcb086cd18fc9a943/packages/database/src/core/PersistentConnection.ts#L176

(def version 5) ;aligned to firebase-js-sdk

(defn prep-ex 
  [^String message ^Exception e]
  (ex-info message {:error (.getMessage e) :cause (.getCause e) :trace (.getStackTrace e)}))

(defn thrower [res]
  (when (instance? Throwable res) (throw res))
  res)

(defn extract [data']
  (let [data (u/decode data')
        t (-> data :t)]
    (case t
      "d" [(-> data :d :r) (-> data :d :b :d)]
      "c" [nil (-> data :d :d)]
      [nil data])))

(defn to-key [num]
  (when num (keyword (str "f" num))))

(defn send! [conn a msg & [read?]]
  (let [sock (-> @conn :socket)
        count (-> @conn :count inc)
        k (to-key count)]
      (ws/send-msg sock (u/encode {:t "d" :d {:r count :a a  :b msg}}))
      (swap! conn assoc :count count)
      (when read? (swap! conn assoc k (async/chan 1)))
    count))

 (defn base-url 
  "Returns a proper Firebase base url given a database name"
  [db-name]
  (str "wss://" db-name "." u/firebase-root))

(defn socket-url 
  "Returns a proper Firebase url given a database name and path"
  [db-name]
  (let [db-name' (-> db-name (str/replace "ws://" "http://") (str/replace "wss://" "https://"))
        url (try (str (io/as-url db-name')) (catch Exception _ nil))]
    (if (nil? url) (base-url db-name) db-name)))

(defn connect [db-name auth]
  (let [url (socket-url db-name)
        conn (atom {:socket nil :count 0 :auth auth :db db-name})
        client (let [^WebSocketClient ws (ws/client)]
                  ;; (.setMaxTextMessageBufferSize ws 256000)
                  ;; (.setMaxBinaryMessageBufferSize ws 256000)
                  (.setStopAtShutdown ws true)
                  (.start ws)
                  ws)
        socket (ws/connect (str url "/.ws?v=" version) 
                :client client
                :on-close (fn [a b] 
                            (println a b)
                            (.stop client))
                :on-receive (fn [d'] 
                              (Thread/sleep 3000)
                              (let [d (extract d')
                                    k (-> d first to-key)
                                    c (when k (k @conn))]
                                (when-not (or (nil? k) (nil? c))
                                  (if (second d)
                                    (async/put! c (second d))
                                    (async/close! c))))
                                nil)
                :on-error (fn [t] (thrower t)))]
    (swap! conn assoc :socket socket)                            
    (when auth (send! conn "gauth" {:cred (:token auth)}))
    conn))

(defn refresh [conn]
  (let [auth (-> @conn :auth)]
    (when (>= (u/now) (:expiry auth))
      (let [new-auth (-> auth :env auth/create-token)
            new-db (-> @conn :db)]
        (reset! conn (connect new-db new-auth))))))

(defn read [conn path]
  (refresh conn)
  (let [count (send! conn "g" {:p path} true)
        k (to-key count)
        data (async/<!! (-> @conn k))]
    (swap! conn dissoc k)
    data))

(defn write! [conn path data]
  (refresh conn)
  (send! conn "p" {:p path :d data}))

(defn update! [conn path data]
  (refresh conn)
  (send! conn "m" {:p path :d data}))

(defn push! [conn path data]
  (refresh conn)
  (let [k (str "fire_" (uuid/get-timestamp (uuid/v1)))]
    (send! conn "m" {:p path :d {(keyword k) data}})
    {:name k}))

(defn delete! [conn path]
  (refresh conn)
  (send! conn "p" {:p path :d {}}))

(defn disconnect [conn]
  (send! conn "unauth" {}))

