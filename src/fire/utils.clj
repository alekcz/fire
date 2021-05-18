(ns fire.utils
  (:require [jsonista.core :as j])
  (:gen-class))

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