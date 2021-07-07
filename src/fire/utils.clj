(ns fire.utils
  (:require [cheshire.core :as json])
  (:gen-class))

(def firebase-root "firebaseio.com")

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
