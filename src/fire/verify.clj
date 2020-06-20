(ns fire.verify
	(:require 
            [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [base64-clj.core :as base64]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as keys]
            [overtone.at-at :as at])
	(:gen-class))

(def public-keys (atom nil))
(def threadpool (at/mk-pool)) ;make threadpool for public key updates
(def public-key-url  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com")

; Fetching and updating public keys

(defn- update-public-keys 
	"Update the public key store with the desired data"
  [pubkey-atom data]
	(reset! pubkey-atom (json/decode data true)))

(defn- load-public-keys 
	"Loads the public keys from https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
  [threadpool callback]
	(let [{:keys [_ headers body error] :as _} @(http/get public-key-url)]
	    (if error
	       (throw (Exception. "Could not retrieve public key"))
	       (do
	       		(update-public-keys public-keys body)
	       		(callback threadpool headers)))))

(defn- schedule-public-key-update 
	"Schedules the next update of the public key based on response header cache-control info (see https://firebase.google.com/docs/auth/admin/verify-id-tokens)"
  [thread-pool response-header]
	(let [cache-control-header (:cache-control response-header)
			 seconds-to-next-update (Integer. (str/replace (re-find #"max-age=\d+" (str cache-control-header)) "max-age=" ""))]
			(at/after 
				(* 3600 seconds-to-next-update) 
				#(load-public-keys thread-pool (fn [_])) 
				thread-pool :desc "Refresh public keys")))

; Allow specific domains using regex

(defn- verify-domain 
	"Test the domain using regex. If valid returns the unsigned token data"
  [projectid-regex data]
	(let [project-matches (re-matches (re-pattern (str projectid-regex)) (:projectid data))] 
		(println data)
		(if (nil? project-matches) nil data)))	

; Formatting data for return

(defn- format-result 
	"Format result for easy use. Removes nesting from map"
  [data]
	(when (not (nil? data))
		{ :projectid (:aud data)
      :uid (:user_id data)
      :email (:email data)
      :email_verified (:email_verified data)
      :sign_in_provider (-> data :firebase :sign_in_provider)
      :exp (:exp data)
      :auth_time (:auth_time data)
      :raw data}))	

; Dealing with JWT tokens

(defn- pad-token 
	"Pads token to so that length is a multiple of 4 as required by base64"
	[token]
	(let [len (count token) 
				remainder (mod len 4)]
		(if (zero? remainder)
			  token
			  ;a base64 string must have a length that is multiple of 4
			  (let [padding (- 4 remainder)] 
			  		;"="" is the padding character for base64 encoded strings
			      	(str token (apply str (repeat padding "=")))))))

(defn- get-token-header 
	"Retrieves header from token. Header is used to find appropriate public key (see https://firebase.google.com/docs/auth/admin/verify-id-tokens)"
	[token]
	(when token
		(let [token-array (str/split token #"\.")]
			(json/decode (base64/decode (pad-token (first token-array)))))))	

(defn- authenticate 
	"Core library method. Validates token using public key and returns formatted data"
	[projectid-regex token]
	(let [header  (get-token-header token)]
		(when (contains? @public-keys (:kid header))
				(let [cert (keys/str->public-key ((keyword (:kid header)) @public-keys))
							unsigned-data (when (keys/public-key? cert) (jwt/unsign token cert {:alg :rs256}))]
		(verify-domain projectid-regex (format-result unsigned-data))))))

; public methods

(defn validate-token 
	"Public method that validates token and makes sure the issuing domain is also valid"
	[projectid-regex token]
	(if (nil? @public-keys) 
		(do	
			(load-public-keys threadpool schedule-public-key-update) 
			(authenticate projectid-regex token))
		(authenticate projectid-regex token)))