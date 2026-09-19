(ns fire.oauth2
  (:require [org.httpkit.sni-client :as sni-client]
            [fire.utils :as utils]
            [clojure.string :as str]
            [environ.core :refer [env]])
  (:import  [java.net URLEncoder]
            [java.security KeyFactory Signature PrivateKey] 
            [java.security.spec PKCS8EncodedKeySpec]
            [java.util Base64 Base64$Decoder Base64$Encoder])
  (:gen-class))

(set! *warn-on-reflection* true)

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

(defn decode-credentials
  "The service account json as a map; nil for nothing at all, and
   {:error true :error-data \"INVALID_CREDENTIALS\"} for something that is
   not the json of a service account."
  [raw]
  (when-not (str/blank? (str raw))
    (try
      (let [decoded (utils/decode raw)]
        (if (map? decoded)
          decoded
          {:error true :error-data "INVALID_CREDENTIALS"}))
      (catch Exception _ {:error true :error-data "INVALID_CREDENTIALS"}))))

(defn credentials
  "Decode the service account credentials held in the named environment
   variable. fire.admin needs the private key and client email out of these
   to mint custom tokens, which is signing rather than an api call.

   nil when the variable is unset or empty. A variable that is set but does
   not hold json is reported as {:error true :error-data \"INVALID_CREDENTIALS\"}
   rather than thrown, so every caller sees the same shape of failure."
  [env-var]
  (decode-credentials (-> env-var utils/clean-env-var env)))

;; the private key is derived from the credentials once per env var, not once
;; per signature. environ reads the environment at load time, so the value
;; behind an env var cannot change underneath this cache.
(defonce ^:private key-cache (atom {}))

(defn signing-key
  "The service account behind `env-var`, ready to sign with:
   {:client-email ... :private-key java.security.PrivateKey}. Cached per env
   var. nil when there are no credentials to derive it from, or an error map
   when what is there cannot be read."
  [env-var]
  (or (get @key-cache env-var)
      (let [creds (credentials env-var)]
        (cond
          (nil? creds) nil
          (:error creds) creds
          (str/blank? (:private_key creds)) nil
          :else
          (let [derived {:client-email (:client_email creds)
                         :private-key (str->private-key (:private_key creds))}]
            (swap! key-cache assoc env-var derived)
            derived)))))

(def ^:private scopes
  (str/join " "
    ["https://www.googleapis.com/auth/firebase.database"
     "https://www.googleapis.com/auth/userinfo.email"
     "https://www.googleapis.com/auth/devstorage.full_control"
     ;; user management in fire.admin goes through the identity
     ;; toolkit api, which none of the scopes above cover — without
     ;; this one every admin call comes back 403.
     "https://www.googleapis.com/auth/identitytoolkit"
     "https://www.googleapis.com/auth/firebase"]))

(defn exchange-token
  "Trade a service account's signed assertion for an OAuth2 access token:
   {:token ... :expiry ... :project-id ... :type ...}, or an error map saying
   why not. `creds` is the decoded service account json; `private-key` its
   key, already derived. The network half of get-token, on its own so it can
   be driven through fire.utils/*http-fn*."
  [creds ^PrivateKey private-key]
  (let [aud "https://oauth2.googleapis.com/token"
        t (utils/now)
        claims {:iss (:client_email creds) :scope scopes :aud aud :iat t :exp (+ t 3599)}
        token (sign claims private-key)
        body (str "grant_type=" (URLEncoder/encode "urn:ietf:params:oauth:grant-type:jwt-bearer") "&assertion=" token "&access_type=offline")
        res' (try
               (utils/http! sni-client/default-client
                            {:url aud
                             :headers {"Content-Type" "application/x-www-form-urlencoded"}
                             :body body
                             :method :post})
               (catch Exception e {:error e}))
        res (try (some-> res' :body utils/decode) (catch Exception _ nil))]
    (cond
      (:error res')
      {:error true :error-data (str "TOKEN_EXCHANGE_FAILED: " (ex-message (:error res')))}

      (not= (:status res') 200)
      {:error true :error-data (str "TOKEN_EXCHANGE_FAILED: "
                                    (or (:error_description res) (:error res) (:status res')))}

      :else
      {:token (:access_token res)
       :expiry (+ (utils/now) (:expires_in res) -5)
       :project-id (:project_id creds)
       :type (:type creds)})))

(defn get-token
  "Exchange the service account in `env-var` for an OAuth2 access token:
   {:token ... :expiry ... :project-id ... :type ...}.

   nil when the env var holds no credentials. When it does and the exchange
   still fails — Google refused the assertion, or could not be reached — an
   error map says so, because those two are different problems from having no
   credentials and used to look identical."
  [env-var]
  (let [creds (credentials env-var)]
    (cond
      (nil? creds) nil
      (:error creds) creds
      (str/blank? (:private_key creds)) nil
      :else (exchange-token creds (:private-key (signing-key env-var))))))
