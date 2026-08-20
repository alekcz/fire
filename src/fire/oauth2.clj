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

(defn sign
  "Sign claims into an RS256 JWT. The header defaults to the bare {:alg \"RS256\"}
   this has always emitted; firebase custom tokens want :typ in there too, so it
   can be passed in."
  ([claims' priv-key] (sign claims' priv-key {:alg "RS256"}))
  ([claims' priv-key header']
    (let [^Base64$Encoder b64encoder (. Base64 	getUrlEncoder)
          ^Signature sig (Signature/getInstance "SHA256withRSA")
          strip (fn [s] (str/replace s "=" ""))
          encode (fn [b] (strip (.encodeToString b64encoder (.getBytes ^String b "UTF-8"))))
          rencode (fn [b] (strip (.encodeToString b64encoder ^"[B" b)))
          header (utils/encode header')
          claims (utils/encode claims')
          jwtbody (str (encode header) "." (encode claims))]
          (.initSign sig priv-key)
          (.update sig (.getBytes ^String jwtbody "UTF-8"))
          (str jwtbody "." (rencode (.sign sig))))))

(defn credentials
  "Decode the service account credentials held in the named environment
   variable. fire.admin needs the private key and client email out of these
   to mint custom tokens, which is signing rather than an api call."
  [env-var]
  (-> env-var utils/clean-env-var env utils/decode))

(defn get-token [env-var]
  (let [auth (credentials env-var)]
    (if-not (:private_key auth)
      nil
      (binding [org.httpkit.client/*default-client* sni-client/default-client]
        (let [scopes (str/join " "
                       ["https://www.googleapis.com/auth/firebase.database"
                        "https://www.googleapis.com/auth/userinfo.email"
                        "https://www.googleapis.com/auth/devstorage.full_control"
                        ;; user management in fire.admin goes through the identity
                        ;; toolkit api, which none of the scopes above cover — without
                        ;; this one every admin call comes back 403.
                        "https://www.googleapis.com/auth/identitytoolkit"
                        "https://www.googleapis.com/auth/firebase"])
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
