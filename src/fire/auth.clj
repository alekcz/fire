(ns fire.auth
  (:require [googlecredentials.core :as g-cred])
  (:import 	[com.google.auth.oauth2 ServiceAccountCredentials AccessToken]
            [java.util Vector])
  (:gen-class))

(set! *warn-on-reflection* 1)

(def half-an-hour (* 30 60 1000))

(defn create-scope [env-var]
  (let [^ServiceAccountCredentials cred (g-cred/load-service-credentials env-var)]
        (.createScoped cred ^Vector (into [] ["https://www.googleapis.com/auth/firebase.database" 
                                              "https://www.googleapis.com/auth/userinfo.email"]))))

(defn create-token 
  ([]
    (create-token nil))
  ([env-var]
    (let [env-var (if (nil? env-var) "GOOGLE_APPLICATION_CREDENTIALS" env-var)
          ^ServiceAccountCredentials scoped (create-scope env-var)]
      {:token (-> scoped ^AccessToken .refreshAccessToken .getTokenValue)
       :project-id (.getProjectId scoped)
       :expiry (+ half-an-hour (inst-ms (java.util.Date.)))
       :new-token (fn [] (-> env-var
                             ^ServiceAccountCredentials create-scope
                             ^AccessToken .refreshAccessToken 
                             .getTokenValue))})))
