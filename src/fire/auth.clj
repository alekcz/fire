(ns fire.auth
  (:require [googlecredentials.core :as g-cred])
  (:import 	[com.google.auth.oauth2 ServiceAccountCredentials AccessToken]
            [java.util Vector])
  (:gen-class))

(set! *warn-on-reflection* 1)

(def year (* 365 24 60 60 1000))

(defn create-token 
  ([]
    (create-token nil))
  ([env-var]
    (let [env-var (if (nil? env-var) "GOOGLE_APPLICATION_CREDENTIALS" env-var)
          ^ServiceAccountCredentials cred (g-cred/load-service-credentials env-var)
          ^ServiceAccountCredentials scoped (.createScoped cred ^Vector (into [] ["https://www.googleapis.com/auth/firebase.database"
                                                                                  "https://www.googleapis.com/auth/userinfo.email"]))]
      {:new-token (fn [] (-> scoped ^AccessToken .refreshAccessToken .getTokenValue))
       :token (-> scoped ^AccessToken .refreshAccessToken .getTokenValue)
       :project-id (.getProjectId scoped)
       :expiry (+ year (inst-ms (java.util.Date.)))})))
