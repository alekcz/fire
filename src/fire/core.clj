(ns fire.core
  (:require [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [fire.auth :as fire-auth]
            [fire.utils :as utils])            
  (:refer-clojure :exclude [read])
  (:gen-class))

(set! *warn-on-reflection* true)

(def sni-client (delay (client/make-client {:ssl-configurer sni-client/ssl-configurer})))
(def http-type {:get    "GET"
                :post   "POST"
                :put    "PUT"
                :patch  "PATCH"
                :delete "DELETE"})
(defn thrower [res]
  (when (instance? Throwable res) (throw res))
  res)

(defn db-base-url 
  "Returns a proper Firebase base url given a database name"
  [db-name]
  (str "https://" db-name "." utils/firebase-root))

(defn db-url 
  "Returns a proper Firebase url given a database name and path"
  [db-name path]
  (let [url (try (str (io/as-url db-name)) (catch Exception _ nil))]
    (if (nil? url)
      (str (db-base-url db-name) path ".json")
      (str url path ".json"))))

(defn request 
  "Request method used by other functions."
  [method db-name path data & [auth options]]
  (let [res-ch (async/chan 1)]
    (try
      (let [token (when (:expiry auth) 
                    (if (< (utils/now) (:expiry auth))
                      (:token auth) 
                      (-> auth :env fire-auth/create-token :token)))
            request-options (reduce 
                              utils/recursive-merge [{:query-params {:pretty-print true}}
                                                     {:headers {"X-HTTP-Method-Override" (method http-type)
                                                                "Connection" "keep-alive"}}
                                                     {:keepalive 600000}
                                                     (when auth {:headers {"Authorization" (str "Bearer " token)}})
                                                     (when-not (nil? data) {:body (utils/encode data)})
                                                     (dissoc options :async)])
            url (db-url db-name path)
            c sni-client]
        (binding [org.httpkit.client/*default-client* c]
          (client/post url request-options 
            (fn [response] 
              (let [res (-> response :body utils/decode)
                    error (:error response)]
                (if error 
                  (async/put! res-ch error)
                  (when-not (nil? res) (async/put! res-ch res)))
                (async/close! res-ch))))))
      (catch Exception e 
        (async/put! res-ch e)
        (async/close! res-ch)))
      res-ch))  

(defn write! 
  "Creates or destructively replaces data in a Firebase database at a given path"
  [db-name path data auth & [options]]
  (let [res (request :put db-name path data auth options)]
    (if (:async (merge {} options auth))
      res
      (-> res async/<!! thrower))))

(defn update!
  "Updates data in a Firebase database at a given path via destructively merging."
  [db-name path data auth & [options]]
  (let [res (request :patch db-name path data auth options)]
    (if (:async (merge {} options auth))
      res
      (-> res async/<!! thrower))))

(defn push!
  "Appends data to a list in a Firebase db at a given path."
  [db-name path data auth & [options]]
  (let [res (request :post db-name path data auth options)]
    (if (:async (merge {} options auth))
      res
      (-> res async/<!! thrower))))

(defn delete! 
  "Deletes data from Firebase database at a given path"
  [db-name path auth & [options]]
  (let [res (request :delete db-name path nil auth options)]
    (if (:async (merge {} options auth))
      res
      (-> res async/<!! thrower))))

(defn read
  "Retrieves data from Firebase database at a given path"
  [db-name path auth & [options]]
  (let [res (request :get db-name path nil auth (merge {:query-params (or (utils/escape (:query options)) {})} 
                                                       (dissoc options :query)))]
    (if (:async (merge {} options auth))
      res
      (-> res async/<!! thrower))))
