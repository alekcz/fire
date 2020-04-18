(ns fire.auth
  (:require [google-credentials.core :as g-cred])
  (:import 	[com.google.auth.oauth2 ServiceAccountCredentials])
  (:gen-class))

(set! *warn-on-reflection* 1)

(defn create-token 
  ([]
    (create-token "GOOGLE_APPLICATON_CREDENTIALS"))
  ([env-var]
    (let [^ServiceAccountCredentials cred (g-cred/load-service-credentials env-var)
          ^ServiceAccountCredentials scoped (.createScoped cred ["https://www.googleapis.com/auth/firebase.database"
                                                                 "https://www.googleapis.com/auth/userinfo.email"])]
      {:token (-> scoped ^AccessToken .refreshAccessToken .getTokenValue)
       :project-id (.getProjectId scoped)})))
