(ns fire.core
  (:require [clj-http.lite.client :as client]
            [fire.auth :as auth]
            [cheshire.core :as json])            
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
  (str (db-base-url db-name) path ".json"))

(defn request 
  "Request method used by other functions."
  [method db-name path data & [auth options]]
  (let [token (:token auth)
        request-options (reduce recursive-merge [{:query-params {:pretty-print true}}
                                                 {:headers {"X-HTTP-Method-Override" (method http-type)}}
                                                 (when auth {:headers {"Authorization" (str "Bearer " (token))}})
                                                 (when (not (nil? data)) {:body (json/generate-string data)})
                                                 options])
        url (db-url db-name path)]
    (-> (client/post url request-options {:as :json})
        :body
        (json/decode true))))

(defn write! 
  "Creates or destructively replaces data in a Firebase database at a given path"
  [db-name path data & [auth options]]
  (request :put db-name path data auth options))

(defn update!
  "Updates data in a Firebase database at a given path via destructively merging."
  [db-name path data & [auth options]]
  (request :patch db-name path data auth options))

(defn push!
  "Appends data to a list in a Firebase db at a given path."
  [db-name path data & [auth options]]
  (request :post db-name path data auth options))

(defn delete! 
  "Deletes data from Firebase database at a given path"
  [db-name path & [auth options]]
  (request :delete db-name path nil auth options))

(defn escape 
  "Surround all string with quotes"
  [query]
  (apply merge (for [[k v] query]  {k (if (string? v) (str "\"" v "\"") v)})))

(defn read
  "Retrieves data from Firebase database at a given path"
  [db-name path & [auth query options]]
  (request :get db-name path nil auth (merge {:query-params (or (escape query) {})} options)))

;to test graalvm compatibility
(defn -main []
  (let [auth (auth/create-token :fire)
        db (:project-id auth)
        root "/fire-graalvm-test"]
    (push! db root {:name "graal"} auth)
    (write! db root {:name "graal"} auth)
    (println (read db root auth))
    (delete! db root auth)))
