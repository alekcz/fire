(ns fire.utils
  (:require [jsonista.core :as j])
  (:gen-class))

(defn now []
  (quot (inst-ms (java.util.Date.)) 1000))

(defn encode [m]
  (j/write-value-as-string m))

(defn decode [json]
  (j/read-value json j/keyword-keys-object-mapper))