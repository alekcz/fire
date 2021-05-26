(ns fire.utils
  (:require [jsonista.core :as j])
  (:gen-class))

(def firebase-root "firebaseio.com")

(def mapper
  (j/object-mapper
    {:encode-key-fn name
     :decode-key-fn keyword}))

(defn now []
  (quot (inst-ms (java.util.Date.)) 1000))

(defn encode [m]
  (j/write-value-as-string m))

(defn decode [json]
  (j/read-value json mapper))

(defn escape 
  "Surround all strings in query with quotes"
  [query]
  (apply merge (for [[k v] query]  {k (if (string? v) (str "\"" v "\"") v)})))
