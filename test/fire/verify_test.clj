(ns fire.verify-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [charmander.admin :as charm-admin]
            [fire.verify :as verify]
            [environ.core :refer [env]]
            [malli.generator :as mg])            
  (:gen-class))


(def ancient-firebase-token "eyJhbGciOiJSUzI1NiIsImtpZCI6IjU3ZGQ5ZGNmYmIxZDkzZWY2MWE1Y2Y5N2QxMjYxZjk5YTIxNWQ4YTAifQ.eyJpc3MiOiJodHRwczovL3NlY3VyZXRva2VuLmdvb2dsZS5jb20vbmVlZHR5cmVzemEiLCJhdWQiOiJuZWVkdHlyZXN6YSIsImF1dGhfdGltZSI6MTQ4OTgzMDQ5MSwidXNlcl9pZCI6Ikg1eHpTQW9nZkVOUlk4ampHbTFVS2hRVHZ5QTMiLCJzdWIiOiJINXh6U0FvZ2ZFTlJZOGpqR20xVUtoUVR2eUEzIiwiaWF0IjoxNDk3Mzk3MjA2LCJleHAiOjE0OTc0MDA4MDYsImVtYWlsIjoiYWxla2N6QGdtYWlsLmNvbSIsImVtYWlsX3ZlcmlmaWVkIjpmYWxzZSwiZmlyZWJhc2UiOnsiaWRlbnRpdGllcyI6eyJlbWFpbCI6WyJhbGVrY3pAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoicGFzc3dvcmQifX0.mEQHljuKO5v2c_A38zH5KqzqYU_Nq8Q3hCEiQjFag1VL32voJndece8fjfCo0dKxFCkKNoTIgMidLiMUet2aTTk89JaCfIBlKzGs3i8o5FEzDbdb1VU5KsrKbeFkCnMu7v9B8K6d5xkAnIW6JI-1wLgTVYov8RlxHhRBYjn-iNd_CKMIUvwDMaPo4kYr70IqKmK8kgCha9x9FViBCdMncc9nPvZWN-OE22Lwmk3qjHhMfuLSYBWZa_KotvHiQFEc06Mdc0vj-JtOTKSGzl4ESrnnX4QQR6lKGUqsbwqk0h61_NQd0-tlQxelMb6td8U6ISvlzufIYTj5Lx9N1bhcgw")

(deftest test-validate-token
	(testing "Testing validate-token"
		(let [token ancient-firebase-token]
			(is (nil? (verify/validate-token  "(.*)" token))))))
				
(deftest test-verify-token	
	(testing "Testing the verifaction of tokens"
    (charm-admin/init)
    (let [api-key (:firebase-api env) 
          email (mg/generate [:re #"[a-z]{5,10}@[a-z]{5,7}\.com$"])
          password "superDuperSecure"
          endpoint (str "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" api-key)
          response  (charm-admin/create-user email password)
          payload (json/encode {:email (:email response) :password password :returnSecureToken true})
          {:keys [_ _ body error] :as resp} 
              @(http/request {:url endpoint 
                              :method :post 
                              :body payload})]
      (if error
        (println error)
        (let [data (json/decode body true)
              validated (verify/validate-token "(.*)" (:idToken data))]
            (is (= (:email validated) email))
            (is (= (:email data) email))))
      (charm-admin/delete-user (:uid response)))))