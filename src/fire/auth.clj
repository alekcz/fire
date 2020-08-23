(ns fire.auth
  (:require [fire.oauth2 :as oauth2])
  (:gen-class))

(set! *warn-on-reflection* 1)

(defn create-token 
  ([]
    (create-token nil))
  ([env-var]
    (let [env-var (if (nil? env-var) "GOOGLE_APPLICATION_CREDENTIALS" env-var)
          auth (oauth2/get-token env-var)]
      (merge auth {:env env-var}))))
