(ns fire.oauth2
  (:require [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [fire.utils :as utils]
            [clojure.string :as str]
            [environ.core :refer [env]])
  (:import  [java.net URLEncoder]
            [java.security KeyFactory Signature] 
            [java.security.spec PKCS8EncodedKeySpec]
            [java.util Base64 Base64$Decoder Base64$Encoder])
  (:gen-class))

(set! *warn-on-reflection* 1)

(defn str->private-key [keystr']
  (let [^Base64$Decoder b64decoder (. Base64 getDecoder)
        ^KeyFactory kf (KeyFactory/getInstance "RSA")
        ^String keystr (-> keystr' (str/replace "\n" "") (str/replace "-----BEGIN PRIVATE KEY-----" "") (str/replace "-----END PRIVATE KEY-----" ""))]
         (->> keystr
          (.decode b64decoder)
          (PKCS8EncodedKeySpec.)
          (.generatePrivate kf))))

(defn sign [claims' priv-key]
  (let [^Base64$Encoder b64encoder (. Base64 	getUrlEncoder)
        ^Signature sig (Signature/getInstance "SHA256withRSA")
        strip (fn [s] (str/replace s "=" ""))
        encode (fn [b] (strip (.encodeToString b64encoder (.getBytes ^String b "UTF-8"))))
        rencode (fn [b] (strip (.encodeToString b64encoder ^"[B" b)))
        header "{\"alg\":\"RS256\"}"
        claims (utils/encode claims')
        jwtbody (str (encode header) "." (encode claims))]
        (.initSign sig priv-key)
        (.update sig (.getBytes ^String jwtbody "UTF-8"))
        (str jwtbody "." (rencode (.sign sig)))))

(defn get-token [env-var]
  (let [auth (-> env-var utils/clean-env-var env utils/decode)]
    (if-not (:private_key auth)
      nil
      (binding [org.httpkit.client/*default-client* sni-client/default-client]
        (let [scopes "https://www.googleapis.com/auth/firebase.database 
                      https://www.googleapis.com/auth/userinfo.email 
                      https://www.googleapis.com/auth/devstorage.full_control"
              aud "https://oauth2.googleapis.com/token"
              t (utils/now)
              private-key (-> auth :private_key str->private-key)
              claims {:iss (:client_email auth) :scope scopes :aud aud :iat t :exp (+ t 3599)}
              token (sign claims private-key)
              body (str "grant_type=" (URLEncoder/encode "urn:ietf:params:oauth:grant-type:jwt-bearer") "&assertion=" token "&access_type=offline")
              res' @(client/request {:url aud
                                    :headers {"Content-Type" "application/x-www-form-urlencoded"}
                                    :body body
                                    :method :post })
              res (-> res' :body utils/decode)]
              (when (= (:status res') 200)
                {:token (:access_token res)
                  :expiry (+ (utils/now) (:expires_in res) -5)
                  :project-id (:project_id auth)
                  :type (:type auth)}))
                  ))))             
