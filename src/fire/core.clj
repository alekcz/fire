(ns fire.core
  (:require [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [fire.auth :as fire-auth])            
  (:refer-clojure :exclude [read])
  (:gen-class))

(set! *warn-on-reflection* 1)

(def firebase-root "firebaseio.com")

(def http-type {:get    "GET"
                :post   "POST"
                :put    "PUT"
                :patch  "PATCH"
                :delete "DELETE"})

(defn recursive-merge
  "Recursively merge hash maps."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with recursive-merge a b)
    (if (map? a) a b)))

(defn db-base-url 
  "Returns a proper Firebase base url given a database name"
  [db-name]
  (str "https://" db-name "." firebase-root))

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
      (let [now (inst-ms (java.util.Date.))
            token (when auth 
                    (if (< now (:expiry auth))
                      (:token auth) 
                      (-> auth :env fire-auth/create-token :token)))
            request-options (reduce 
                              recursive-merge [{:query-params {:pretty-print true}}
                                              {:headers {"X-HTTP-Method-Override" (method http-type)}}
                                              {:keepalive 600000}
                                              (when auth {:headers {"Authorization" (str "Bearer " token)}})
                                              (when (not (nil? data)) {:body (json/generate-string data)})
                                              (dissoc options :async)])
            url (db-url db-name path)]
        (binding [org.httpkit.client/*default-client* sni-client/default-client]
          (client/post url request-options 
            (fn [response] 
              (let [res (-> response :body (json/decode true))]
                (if (nil? res)
                  (async/close! res-ch)
                  (async/put! res-ch res)))))))
      (catch Exception e 
        (async/close! res-ch)
        (throw e)))
      res-ch))  

(defn write! 
  "Creates or destructively replaces data in a Firebase database at a given path"
  [db-name path data auth & [options]]
  (let [res (request :put db-name path data auth options)]
    (if (:async (merge {} options auth))
      res
      (async/<!! res))))

(defn update!
  "Updates data in a Firebase database at a given path via destructively merging."
  [db-name path data auth & [options]]
  (let [res (request :patch db-name path data auth options)]
    (if (:async (merge {} options auth))
      res
      (async/<!! res))))

(defn push!
  "Appends data to a list in a Firebase db at a given path."
  [db-name path data auth & [options]]
  (let [res (request :post db-name path data auth options)]
    (if (:async (merge {} options auth))
      res
      (async/<!! res))))

(defn delete! 
  "Deletes data from Firebase database at a given path"
  [db-name path auth & [options]]
  (let [res (request :delete db-name path nil auth options)]
    (if (:async (merge {} options auth))
      res
      (async/<!! res))))

(defn escape 
  "Surround all strings in query with quotes"
  [query]
  (apply merge (for [[k v] query]  {k (if (string? v) (str "\"" v "\"") v)})))

(defn read
  "Retrieves data from Firebase database at a given path"
  [db-name path auth & [options]]
  (let [res (request :get db-name path nil auth (merge {:query-params (or (escape (:query options)) {})} 
                                                       (dissoc options :query)))]
    (if (:async (merge {} options auth))
      res
      (async/<!! res))))

(defn -main []
  (let [auth (fire-auth/create-token :fire)
        db (:project-id auth)
        root "/fire-graalvm-test"]
    (push! db root {:originalname "graalvm"} auth)
    (write! db root {:name "graal"} auth)
    (let [res (read db root auth)]
      (delete! db root auth)
      (println res)
      res)))