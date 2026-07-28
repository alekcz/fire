(ns fire.auth
  (:require [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [clojure.string :as str]
            [fire.oauth2 :as oauth2]
            [fire.utils :as utils])
  (:import  [java.security KeyFactory Signature]
            [java.security.cert CertificateFactory X509Certificate]
            [java.io ByteArrayInputStream]
            [java.util Base64 Base64$Decoder])
  (:gen-class))

(set! *warn-on-reflection* true)

(defn create-token
  ([]
    (create-token nil))
  ([env-var]
    (let [env-var (if (nil? env-var) "GOOGLE_APPLICATION_CREDENTIALS" env-var)
          auth (oauth2/get-token env-var)]
      (merge auth {:env env-var}))))

;; ---------------------------------------------------------------------------
;; ID token verification — the inverse of create-token/oauth2/sign above.
;; No service account needed: this only checks a token someone ELSE already
;; has (a signed-in user's Firebase ID token), by verifying it against
;; Google's public certs. Mirrors oauth2/sign's crypto exactly (same
;; SHA256withRSA, same pure-JDK approach, zero extra dependencies) just
;; running it in reverse: initVerify instead of initSign.
;; ---------------------------------------------------------------------------

(def ^:private certs-url
  "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com")

(def ^:private cache-ttl-secs 3600) ;; 1h — Google rotates these on a much longer cycle

(defonce ^:private cert-cache (atom {:certs nil :fetched-at 0}))

(defn- fetch-certs
  "GET the current {kid -> PEM certificate string} map from Google."
  []
  (binding [org.httpkit.client/*default-client* sni-client/default-client]
    (let [res @(client/request {:url certs-url :method :get})]
      (if (= 200 (:status res))
        (utils/decode (:body res))
        (throw (ex-info "Failed to fetch Firebase public certs" {:status (:status res)}))))))

(defn- current-certs
  "Cached certs map, refreshed once the TTL expires. A refresh failure
   falls back to the stale cache rather than breaking every verification
   until Google is reachable again."
  []
  (let [{:keys [certs fetched-at]} @cert-cache]
    (if (and certs (< (- (utils/now) fetched-at) cache-ttl-secs))
      certs
      (try
        (let [fresh (fetch-certs)]
          (reset! cert-cache {:certs fresh :fetched-at (utils/now)})
          fresh)
        (catch Exception e
          (if certs certs (throw e)))))))

(defn cert->public-key
  "Parse a PEM-encoded X.509 certificate string (as returned by Google's
   public-certs endpoint) into its java.security.PublicKey. Unlike
   str->private-key in fire.oauth2, CertificateFactory handles the
   PEM BEGIN/END wrapper itself — no manual header-stripping needed."
  ^java.security.PublicKey [^String pem]
  (let [^CertificateFactory cf (CertificateFactory/getInstance "X.509")
        ^X509Certificate cert  (.generateCertificate cf (ByteArrayInputStream. (.getBytes pem "UTF-8")))]
    (.getPublicKey cert)))

(defn- pad-base64url
  "JWT segments are unpadded base64url; java.util.Base64's decoder wants
   correct padding (or none at all — only a length already a multiple
   of 4 satisfies that)."
  ^String [^String s]
  (let [rem (int (mod (count s) 4))]
    (case rem
      2 (str s "==")
      3 (str s "=")
      s)))

(defn- base64url-decode
  ^String [^String s]
  (let [^Base64$Decoder decoder (. Base64 getUrlDecoder)]
    (String. (.decode decoder (pad-base64url s)) "UTF-8")))

(defn- token-parts
  "Split a JWT into its three segments (header, payload, signature),
   throws if the shape is wrong."
  [^String token]
  (let [parts (str/split token #"\." 3)]
    (when-not (= 3 (count parts)) (throw (ex-info "Malformed JWT" {})))
    parts))

(defn valid-signature?
  "Verify an RS256 JWT's signature against a public key. signing-input is
   \"header.payload\" (the two segments the signature actually covers)."
  [^String signing-input ^String signature-b64url ^java.security.PublicKey pubkey]
  (let [^Signature sig (Signature/getInstance "SHA256withRSA")
        ^Base64$Decoder decoder (. Base64 getUrlDecoder)
        sig-bytes (.decode decoder (pad-base64url signature-b64url))]
    (.initVerify sig pubkey)
    (.update sig (.getBytes signing-input "UTF-8"))
    (.verify sig sig-bytes)))

(defn- valid-claims?
  "Standard Firebase ID token claim checks — see
   https://firebase.google.com/docs/auth/admin/verify-id-tokens"
  [{:keys [iss aud exp iat sub auth_time]}]
  (let [now (utils/now)]
    (and (= (str "https://securetoken.google.com/" aud) iss)
         exp (> exp now)
         iat (<= iat now)
         (not (str/blank? sub))
         auth_time (<= auth_time now))))

(defn validate-token
  "Verify a Firebase ID token's signature and standard claims, and that
   it was issued for `project-id`. Returns the decoded claims map on
   success, nil on any failure (bad signature, expired, wrong project,
   malformed input) — deliberately fails closed and swallows exceptions,
   since every failure mode here means the same thing to a caller: reject.

   No service account or Admin SDK needed — this only reads Google's
   PUBLIC certs. For verifying a signed-in user's ID token, not for
   fire's own outbound calls (see create-token for that)."
  [project-id token]
  (try
    (let [[header-b64 payload-b64 sig-b64] (token-parts token)
          header  (utils/decode (base64url-decode header-b64))
          payload (utils/decode (base64url-decode payload-b64))
          ;; current-certs is keyword-keyed (utils/decode keywordizes map
          ;; keys), but a JWT header's :kid VALUE stays a plain string
          ;; (decode only keywordizes keys, never values) — keywordize it
          ;; to actually hit the cache instead of silently missing every time.
          kid     (some-> (:kid header) keyword)
          pem     (get (current-certs) kid)]
      (when pem
        (let [pubkey (cert->public-key pem)]
          (when (valid-signature? (str header-b64 "." payload-b64) sig-b64 pubkey)
            (when (and (valid-claims? payload) (= project-id (:aud payload)))
              payload)))))
    (catch Exception _ nil)))
