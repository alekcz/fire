(ns fire.utils
  (:require [cheshire.core :as json])
  (:gen-class))

(set! *warn-on-reflection* true)

(def firebase-root "firebaseio.com")
(def storage-upload-root "https://storage.googleapis.com/upload/storage/v1/b")
(def storage-download-root "https://storage.googleapis.com/storage/v1/b")

(defn now []
  (quot (inst-ms (java.util.Date.)) 1000))

(defn encode [m]
  (json/encode m))

(defn decode [json-string]
  (json/decode json-string true))

(defn escape 
  "Surround all strings in query with quotes"
  [query]
  (apply merge (for [[k v] query]  {k (if (string? v) (str "\"" v "\"") v)})))

(defn recursive-merge
  "Recursively merge hash maps."
  [a b]
  (if (and (map? a) (map? b))
    (merge-with recursive-merge a b)
    (if (map? a) a b)))