(ns fire.oauth2-test
  "The token exchange, driven through the http seam with a key made on the
   spot — so every outcome Google can hand back is reached without Google."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fire.oauth2 :as oauth2]
            [fire.utils :as utils])
  (:import [java.security KeyPair KeyPairGenerator Signature]
           [java.util Base64]))

(def ^:private ^KeyPair key-pair
  (let [g (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))]
    (.generateKeyPair g)))

(def ^:private pem
  "The private key as a service account json carries it."
  (str "-----BEGIN PRIVATE KEY-----\n"
       (.encodeToString (Base64/getMimeEncoder 64 (.getBytes "\n")) (.getEncoded (.getPrivate key-pair)))
       "\n-----END PRIVATE KEY-----\n"))

(def ^:private creds
  {:type "service_account" :project_id "test-project"
   :client_email "fire@test-project.iam.gserviceaccount.com" :private_key pem})

(defn- exchange [response]
  (let [calls (atom [])]
    (binding [utils/*http-fn* (fn [request] (swap! calls conj request) response)]
      [(oauth2/exchange-token creds (.getPrivate key-pair)) @calls])))

(deftest ^:offline exchange-token-test
  (testing "a 200 is a token, with the expiry a little ahead of when google said"
    (let [[auth [request]] (exchange {:status 200 :body (utils/encode {:access_token "ya29.token" :expires_in 3600})})]
      (is (= "ya29.token" (:token auth)))
      (is (= "test-project" (:project-id auth)))
      (is (= "service_account" (:type auth)))
      (is (<= (+ (utils/now) 3590) (:expiry auth) (+ (utils/now) 3595)))
      (testing "and what went out was a signed jwt-bearer assertion"
        (is (= "https://oauth2.googleapis.com/token" (:url request)))
        (is (str/includes? (:body request) "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"))
        (let [assertion (second (re-find #"assertion=([^&]+)" (:body request)))
              [_ payload] (str/split assertion #"\.")
              claims (utils/decode (String. (.decode (Base64/getUrlDecoder) ^String payload) "UTF-8"))]
          (is (= (:client_email creds) (:iss claims)))
          (is (str/includes? (:scope claims) "identitytoolkit"))))))

  (testing "google refusing the assertion names the refusal"
    (let [[auth _] (exchange {:status 400 :body (utils/encode {:error "invalid_grant" :error_description "Invalid JWT Signature."})})]
      (is (= {:error true :error-data "TOKEN_EXCHANGE_FAILED: Invalid JWT Signature."} auth))))

  (testing "a refusal with no description falls back to the error, then the status"
    (is (= "TOKEN_EXCHANGE_FAILED: invalid_client"
           (:error-data (first (exchange {:status 401 :body (utils/encode {:error "invalid_client"})})))))
    (is (= "TOKEN_EXCHANGE_FAILED: 503"
           (:error-data (first (exchange {:status 503 :body "<html>upstream</html>"}))))))

  (testing "google unreachable is the exception's message, not a throw"
    (let [[auth _] (exchange {:error (java.net.ConnectException. "Connection refused")})]
      (is (= {:error true :error-data "TOKEN_EXCHANGE_FAILED: Connection refused"} auth)))))

(deftest ^:offline signing-key-test
  (testing "no credentials is nil, and stays nil rather than being cached as something"
    (is (nil? (oauth2/signing-key :non-existent-key)))
    (is (nil? (oauth2/signing-key :non-existent-key))))

  (testing "a key derived from a pem signs, and the signature verifies against its public half"
    (let [derived (oauth2/str->private-key pem)
          jwt (oauth2/sign {:sub "x"} derived)
          [h p sig] (str/split jwt #"\.")
          verifier (doto (Signature/getInstance "SHA256withRSA")
                     (.initVerify (.getPublic key-pair))
                     (.update (.getBytes (str h "." p) "UTF-8")))]
      (is (.verify verifier (.decode (Base64/getUrlDecoder) ^String sig))))))
