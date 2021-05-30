(ns fire.socket
  (:require [fire.utils :as u]
            [fire.auth :as auth]
            [gniazdo.core :as ws]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-uuid :as uuid])       
  (:refer-clojure :exclude [read])           
  (:gen-class))

; Inferred from https://github.com/firebase/firebase-js-sdk/blob/master/packages/database/src/core/PersistentConnection.ts#L176

(def version 5) ;aligned to firebase-js-sdk
(def timeout (* 1 60 1000))

(defn extract [data']
  (let [data (u/decode data')]
    (if (-> data :t (= "d"))
      [(-> data :d :r) (-> data :d :b :d)] ;data
      [nil (-> data :d :d)]))) ; control

(defn to-key [num]
  (when num (keyword (str "f" num))))

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

(defn result [chan]
  (let [res (async/alts!! [(async/timeout timeout) chan])]
    (if (= (second res) chan)
      (first res)
      (throw (ex-info "Timeout" {:error "Timeout trying to reach web server"})))))

(defn send! [conn a msg & [read?]]
  (let [sock (-> @conn :socket)
        message-count (-> @conn :count inc)
        k (to-key message-count)
        payload (u/encode {:t "d" :d {:r message-count :a a  :b msg}})
        submessages (vec (re-seq #".{1,65000}" payload))
        chunks (count submessages)]
        
      (if (= chunks 1)
        (ws/send-msg sock payload)
        (do 
          (ws/send-msg sock (str chunks))
          (doseq [s submessages]
            (ws/send-msg sock s))))
      (swap! conn assoc :count message-count)
      (when read? (swap! conn assoc k (async/chan 1)))
    message-count))

; Does not work with  localhost
(defn connect [db-name auth & {:keys [on-close connection]
                               :or {on-close (fn[]) connection nil}}]
  (let [url (socket-url db-name)
        conn (or connection (atom {:socket nil :count 0 :auth auth :db db-name :chunks 0 :temp ""}))
        socket (ws/connect (str url "/.ws?v=" version) 
                :on-close (fn [_ _] 
                            (swap! conn assoc :socket nil)
                            (on-close))
                :on-receive (fn [d']
                              (let [len (count d')
                                    header (when (< len 12) (Integer/parseInt d'))]
                                (if (some? header)
                                  (swap! conn assoc :chunks (Integer/parseInt d')) 
                                  (if (-> @conn :chunks (> 1))
                                    (do 
                                      (swap! conn update :temp str d')
                                      (swap! conn update :chunks dec))
                                    (let [d'' (str (-> @conn :temp) d')
                                          d (extract d'')
                                          k (-> d first to-key)
                                          c (when k (k @conn))]
                                      (swap! conn assoc :temp "")
                                      (when-not (or (nil? k) (nil? c))
                                        (if (second d)
                                          (async/put! c (second d))
                                          (async/close! c)))))))))]
    (swap! conn assoc :socket socket)                            
    (when auth 
      (let [count (send! conn "gauth" {:cred (:token auth)} true)
            k (to-key count)
            data (result (-> @conn k))]
            (swap! conn dissoc k)
            (swap! conn assoc :expiry  (:expires data))))
    conn))

(defn refresh [conn]
  (let [expiry (-> @conn :expiry)]
    (cond 
      (-> @conn :socket nil?)   (let [new-auth (-> @conn :auth :env auth/create-token) new-db (-> @conn :db)]
                                  (connect new-db new-auth :connection conn))

      (>= (u/now) expiry)       (let [new-auth (-> @conn :auth :env auth/create-token)
                                      count (send! conn "gauth" {:cred (:token new-auth)} true)
                                      k (to-key count)
                                      data (result (-> @conn k))]
                                  (swap! conn dissoc k)
                                  (swap! conn assoc :auth new-auth)
                                  (swap! conn assoc :expiry (:expires data))))))

(defn read [conn path & [query]]
  (refresh conn)
  (let [count (send! conn "g" {:p path :q query} true)
        k (to-key count)
        data (result (-> @conn k))]
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
  (let [n (str "fire_" (uuid/get-timestamp (uuid/v1)))]
    (send! conn "m" {:p path :d {(keyword n) data}})
    {:name n}))

(defn delete! [conn path]
  (refresh conn)
  (send! conn "p" {:p path :d {}}))

(defn disconnect [conn]
  (let [sock (:socket @conn)]
    (when (some? sock) 
      ;(send! conn "unauth" {})
      (ws/close sock))))

(defn -main []
  (let [auth (auth/create-token :fire)
        db (connect (:project-id auth) auth)
        root "/fire-graalvm-test-socket"]
    (push! db root {:originalname "graalvm"})
    (write! db root {:name "graal-socket"})
    (let [res (read db root)]
      (delete! db root)
      (println res)
      res)))