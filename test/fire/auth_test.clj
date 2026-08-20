(ns fire.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fire.auth :as fire-auth]
            [fire.oauth2 :as oauth2]
            [fire.utils :as utils])
  (:import [java.security Signature]
           [java.util Base64]))

(deftest default-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token)
          token (:token auth)]
      (is (not (str/blank? token))))))

(deftest nil-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token nil)
          token (:token auth)]
      (is (not (str/blank? token))))))

(deftest keyword-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token :fire)
          token (:token auth)]
      (is (not (str/blank? token))))))

(deftest string-token-test
  (testing "Tests if a token is returned"
    (let [auth (fire-auth/create-token "FIRE")
          token (:token auth)]
      (is (not (str/blank? token))))))

(deftest non-existent-token-test
  (testing "Tests non-existent token"
    (let [auth (fire-auth/create-token :non-existent-key)]
      (is (= {:env :non-existent-key} auth)))))

;; ---------------------------------------------------------------------------
;; validate-token — verifying an incoming Firebase ID token (the inverse
;; of create-token above, which is about fire's own outbound calls).
;;
;; project.clj sets -Dclojure.compiler.direct-linking=true, which breaks
;; with-redefs on defn'd functions (compiled call sites bypass the Var
;; entirely) — confirmed directly while writing these. So instead of
;; redefining current-certs, these tests reach into cert-cache's atom
;; state via the Var and mutate it with plain reset! — that's normal
;; atom mutation, not function redefinition, so direct-linking doesn't
;; affect it.
;;
;; The fixture cert/key below is a real self-signed pair (openssl req
;; -x509 -newkey rsa:2048 -days 3650 -nodes), used to prove
;; validate-token actually SUCCEEDS on a well-formed token, not just
;; that it fails closed on garbage. That distinction matters: an early
;; version of this code had a bug (kid lookup missing a keywordize,
;; silently rejecting every real token) that only a real signed-token
;; test would ever catch — the fail-closed tests passed either way.
;; ---------------------------------------------------------------------------

(def ^:private fixture-cert
  "-----BEGIN CERTIFICATE-----
MIIDEzCCAfugAwIBAgIUY5Jxb/n492Lm8rdpgyetzBAuxgowDQYJKoZIhvcNAQEL
BQAwGTEXMBUGA1UEAwwOY29ja3JvYWNoLXRlc3QwHhcNMjYwNzI4MjI0NDE5WhcN
MzYwNzI1MjI0NDE5WjAZMRcwFQYDVQQDDA5jb2Nrcm9hY2gtdGVzdDCCASIwDQYJ
KoZIhvcNAQEBBQADggEPADCCAQoCggEBALJNPhRWOji9Bx+ukkim/wW0IrjbZiL1
A8e3MzeTJTgAj1o0VAqL2mMBHgo+c6SkFvnAc1EqyGPHlQRDaf6Cw/L4WDpuXVkm
Nf9kOiy4nMkj71VIbzz75N0XfYg37cpi6y5XlKiEqRYoVubO4QE9lHlq+RkJnBlQ
U+H+FmZtxITbu83vCRQp4+ljSyVw2bVabQ8I1W0Dp1sIQHT0v3p/o/uqSXf3ZFrp
AVVEeFGeb5V1Q0GT07A6EYOPm/yTTURADR1IiIn4WyplGsv3yLfsJXQt5E0VB+Cg
uG98ch+LoRK/K/9Z+buyPhCCI5bjxrphRfxA86WSqBZ3prApnuWwKZ0CAwEAAaNT
MFEwHQYDVR0OBBYEFFvXMimlrPaDwyVLBBY7We9JF0NJMB8GA1UdIwQYMBaAFFvX
MimlrPaDwyVLBBY7We9JF0NJMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQEL
BQADggEBAKl3hNcrfeX7YIsAeyt+6R+EzfoXoG8lfdeIPflvEaYtte3QXwZ08HLS
NSE2P+8lYzTSiuQjy82knFDDzHGjZD2Uz1xhMysmw7J+QCdoXENBoZDet6O6Z6cl
bDLy4q/KZIw4LyCbi9CGeBuoAb3DBdwSYhpa7+YWxtIs3x96dtylxB5yHN5sksVZ
vopJeHADwsPhKTtxG5Z8wu+SKYy7ZH7KTc/5RIhc5N5LKxZSdh9drnWWVTyDX8ye
GpbLiRQ68/21mZg4Zz3L7PyIpLhRsd/TEXzBJBENrk5sc9FhUfBPdPGSmoIxRZrI
TqBrY/enJOkvW1B+dZQdWfOwxVTog+g=
-----END CERTIFICATE-----")

(def ^:private fixture-key
  "-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCyTT4UVjo4vQcf
rpJIpv8FtCK422Yi9QPHtzM3kyU4AI9aNFQKi9pjAR4KPnOkpBb5wHNRKshjx5UE
Q2n+gsPy+Fg6bl1ZJjX/ZDosuJzJI+9VSG88++TdF32IN+3KYusuV5SohKkWKFbm
zuEBPZR5avkZCZwZUFPh/hZmbcSE27vN7wkUKePpY0slcNm1Wm0PCNVtA6dbCEB0
9L96f6P7qkl392Ra6QFVRHhRnm+VdUNBk9OwOhGDj5v8k01EQA0dSIiJ+FsqZRrL
98i37CV0LeRNFQfgoLhvfHIfi6ESvyv/Wfm7sj4QgiOW48a6YUX8QPOlkqgWd6aw
KZ7lsCmdAgMBAAECggEAAkbicI/YILDCEW40Xje+HcLUbXtLw7PqSPFQQ59RUQ5D
LMbQ5FOV33OWGeswC3dKKp7oOhSerq62FFhbpA1KcEtdYRDXIbpo44QfPajCQ8uw
+wW4rSHJ8eYI/qWfZHK8uxpEnGqy3ngRNNOJsKJ3LdFaZGH1iHM4pwDZWC4cENal
MRrZC5idGCIsa+OiEyZ6zmiWWKdSVL9CiMbKvKBh8unDgx8cEuJu2z2ODnrVGkUA
xmYNqxNuOMkNZn8sRvik0yvZp7Ee0RMJAY0bAEl3Jr268NHmL059uQreTINSFDZn
WU+WnZRCLKD2LItzLjs4v2FvCGuA/Y4afwxNZs3IdwKBgQD1o9OwNO1S0jkOtyyZ
eRw0zk5vWq1nOG/cnl6C2s98WCfm7zSdgkuLeJREmT+cP4+J0kYjD2ir8HIx+Gke
qTKirq7A6KlmKOXsLbMcKBJ1n/VjhOJl4YQxa0FPu9KDdkSqkOWBGUmXKbGko4Vs
JvxBKegwN1sswe++b0JF7ZRvBwKBgQC50l1++Pl84ySPbih8rzXxgP9r5tGHenr/
5H8y+4qJrZG7nTffiIfh8P74RmYccV1YOOB3auucwKOsiFFYEijqoFDJROAw/h27
NbYVFkywpyNXSbMi1gHQa4hzbPxShSzKYceasKQCTkF15PQr3zlnvv1UGbfsEEIG
rIQ5RukVOwKBgHDKujBmDTeDelmseJk8SFxjAxcUrxz/iDXoroMtkCqLnD7pReKx
apjvVD2vlMrdUL67RCNjNvAEp5sUcsh2bt7OkUXZT7euPe1WCrF6IQfL3HTHFuIr
THUYx9Oh7gcZbmxXvlqqTuVPaterkl9YA2q0oH5LXN1PbBOoqOjNL+RPAoGAUogw
ppiGlwV704ilyts3JlCZIZ+fKIEp6EXgiRBX89Z3h9DIZCwLzjpvxG3gJHnlb62z
ZNbEVxbom1TgbDGEotEZkIta1+fF5MRyXuNMpJlXhQli2vIaVCuuqzWYzD4CKtFL
ClWyQqPnRymtmV7H7GBTu+rAWcTOzpAJGjd4hskCgYBrn4MUGUjrelW81bXOLcvZ
qPquk1rt2B3fB9fIsYVK9EUzR3xZjc9RzeW+OrJH09M6MyLOqYmjxAJgGdxGGU0z
GM2GjIv+Xwv6I8XEyt8Bhtd5k4hX1eV/H7ht1P25r63M9GcN1DI+eEV6Lacav8RH
qwEFwqRUFo+nrwDhrCmruQ==
-----END PRIVATE KEY-----")

(defn- b64url [^bytes bs]
  (-> (Base64/getUrlEncoder) (.withoutPadding) (.encodeToString bs)))

(defn- sign-fixture-token
  "Hand-build and sign a JWT with the fixture private key — the same
   SHA256withRSA primitives fire.auth itself uses to verify."
  [claims]
  (let [priv (oauth2/str->private-key fixture-key)
        header-b64  (b64url (.getBytes ^String (utils/encode {:alg "RS256" :kid "test-kid" :typ "JWT"}) "UTF-8"))
        payload-b64 (b64url (.getBytes ^String (utils/encode claims) "UTF-8"))
        signing-input (str header-b64 "." payload-b64)
        sig (doto (Signature/getInstance "SHA256withRSA")
              (.initSign priv)
              (.update (.getBytes signing-input "UTF-8")))]
    (str signing-input "." (b64url (.sign sig)))))

(defn- with-fixture-cert
  "Install the fixture cert under both key sets fire.auth verifies against —
   id tokens and session cookies are signed by different keys — so either
   kind of token resolves to it."
  [f]
  (let [cache @#'fire-auth/cert-cache
        prior @cache
        entry {:certs {:test-kid fixture-cert} :fetched-at (utils/now)}]
    (reset! cache {@#'fire-auth/id-token-certs-url entry
                   @#'fire-auth/session-cookie-certs-url entry})
    (try (f) (finally (reset! cache prior)))))

(defn- fixture-claims [now]
  {:iss "https://securetoken.google.com/test-project" :aud "test-project"
   :sub "uid-123" :email "person@example.com" :email_verified true
   :auth_time now :iat now :exp (+ now 3600)})

(deftest validate-token-round-trip-test
  (with-fixture-cert
    (fn []
      (let [now    (utils/now)
            claims (fixture-claims now)
            token  (sign-fixture-token claims)]

        (testing "a genuinely valid, correctly-signed token succeeds"
          (let [result (fire-auth/validate-token "test-project" token)]
            (is (some? result))
            (is (= "uid-123" (:sub result)))
            (is (= "person@example.com" (:email result)))))

        (testing "wrong project id is rejected even with a valid signature"
          (is (nil? (fire-auth/validate-token "some-other-project" token))))

        (testing "a tampered payload (re-encoded claims, original signature) is rejected"
          (let [[header-b64 _ sig-b64] (str/split token #"\." 3)
                forged-b64 (b64url (.getBytes ^String (utils/encode (assoc claims :sub "attacker")) "UTF-8"))]
            (is (nil? (fire-auth/validate-token "test-project"
                        (str header-b64 "." forged-b64 "." sig-b64))))))

        (testing "an expired token is rejected"
          (is (nil? (fire-auth/validate-token "test-project"
                      (sign-fixture-token (assoc claims :exp (- now 10)))))))))))

(deftest validate-token-fails-closed-on-garbage-test
  (testing "malformed input never throws — always nil"
    (is (nil? (fire-auth/validate-token "test-project" "not.a.jwt")))
    (is (nil? (fire-auth/validate-token "test-project" "")))
    (is (nil? (fire-auth/validate-token "test-project" "a.b")))))

(deftest cert->public-key-test
  (testing "parses a real PEM X.509 certificate into an RSA PublicKey"
    (let [pubkey (fire-auth/cert->public-key fixture-cert)]
      (is (= "RSA" (.getAlgorithm pubkey))))))
;; ---------------------------------------------------------------------------
;; Second factor claims. Firebase puts sign-in metadata inside the token's
;; :firebase block; on Identity Platform that block also carries
;; sign_in_second_factor, which is the ONLY server-side proof that a second
;; factor was actually presented at sign-in. A valid signature is not.
;;
;; These run entirely offline against the fixture cert above — the token is
;; hand-built and signed here, so the :firebase block can be set to exactly
;; the three shapes a real project produces: totp, sms, and legacy.
;; ---------------------------------------------------------------------------

(defn- claims-with
  "fixture-claims whose :firebase block carries the given extra claims."
  [now extra]
  (assoc (fixture-claims now)
         :user_id "uid-123"
         :firebase (merge {:identities {:email ["person@example.com"]}
                           :sign_in_provider "password"}
                          extra)))

(deftest second-factor-claims-test
  (with-fixture-cert
    (fn []
      (let [now (utils/now)
            totp   (claims-with now {:sign_in_second_factor "totp" :second_factor_identifier "enrollment-1"})
            sms    (claims-with now {:sign_in_second_factor "phone" :second_factor_identifier "enrollment-2"})
            legacy (claims-with now {})
            verify #(fire-auth/validate-token "test-project" (sign-fixture-token %))]

        (testing "a totp second factor is surfaced at the top level"
          (let [res (verify totp)]
            (is (= "totp" (:sign_in_second_factor res)))
            (is (= "enrollment-1" (:second_factor_identifier res)))
            (is (= "password" (:sign_in_provider res)))))

        (testing "an sms second factor is surfaced the same way"
          (let [res (verify sms)]
            (is (= "phone" (:sign_in_second_factor res)))
            (is (= "enrollment-2" (:second_factor_identifier res)))))

        (testing "no mfa at sign-in (or legacy firebase auth) means nil, not absent"
          (let [res (verify legacy)]
            ;; nil is the fail-closed signal — an enforcing consumer denies on it
            (is (nil? (:sign_in_second_factor res)))
            (is (nil? (:second_factor_identifier res)))
            ;; but the keys are there either way, so destructuring is safe
            (is (contains? res :sign_in_second_factor))
            (is (contains? res :second_factor_identifier))))

        (testing "an invalid token is still nil outright, not a nil second factor"
          (is (nil? (verify (assoc totp :exp (- now 10)))))
          (is (nil? (fire-auth/validate-token "test-project" "not.a.jwt"))))

        (testing "the raw claims are untouched — this is purely additive"
          (let [res (verify totp)]
            (is (= "totp" (-> res :firebase :sign_in_second_factor)))
            (is (= "test-project" (:aud res)))
            (is (= "uid-123" (:sub res)))
            (is (= "uid-123" (:uid res)))
            (is (= "test-project" (:projectid res)))
            ;; every raw claim still resolves to what it did before the merge
            (is (= (select-keys totp (keys totp)) (select-keys res (keys totp))))))))))

(deftest format-result-test
  (testing "format-result flattens the :firebase block and nothing else"
    (let [now (utils/now)
          claims (claims-with now {:sign_in_second_factor "totp" :second_factor_identifier "enrollment-1"})
          res (fire-auth/format-result claims)]
      (is (= {:projectid "test-project"
              :uid "uid-123"
              :email "person@example.com"
              :email_verified true
              :sign_in_provider "password"
              :sign_in_second_factor "totp"
              :second_factor_identifier "enrollment-1"
              :exp (+ now 3600)
              :auth_time now}
             res))))
  (testing "uid falls back to :sub when a token carries no :user_id"
    (is (= "uid-123" (:uid (fire-auth/format-result (fixture-claims (utils/now)))))))
  (testing "nil in, nil out"
    (is (nil? (fire-auth/format-result nil)))))

;; ---------------------------------------------------------------------------
;; Session cookies. Same RS256 verification as an ID token, but signed by a
;; different key set and carrying a different issuer — which is exactly the
;; thing worth testing, since getting the issuer wrong would happily verify
;; one kind of token as the other.
;; ---------------------------------------------------------------------------

(defn- session-claims [now]
  (assoc (fixture-claims now)
         :iss "https://session.firebase.google.com/test-project"
         :user_id "uid-123"
         :firebase {:sign_in_provider "password" :sign_in_second_factor "totp"}))

(deftest validate-session-cookie-test
  (with-fixture-cert
    (fn []
      (let [now (utils/now)
            cookie (sign-fixture-token (session-claims now))
            id-token (sign-fixture-token (assoc (fixture-claims now) :user_id "uid-123"))]

        (testing "a well-formed session cookie verifies, and flattens like a token does"
          (let [res (fire-auth/validate-session-cookie "test-project" cookie)]
            (is (some? res))
            (is (= "uid-123" (:uid res)))
            (is (= "totp" (:sign_in_second_factor res)))))

        (testing "the two kinds don't verify as each other — the issuers differ"
          (is (nil? (fire-auth/validate-token "test-project" cookie)))
          (is (nil? (fire-auth/validate-session-cookie "test-project" id-token))))

        (testing "wrong project, expiry and garbage are refused the same way"
          (is (nil? (fire-auth/validate-session-cookie "other-project" cookie)))
          (is (nil? (fire-auth/validate-session-cookie "test-project"
                      (sign-fixture-token (assoc (session-claims now) :exp (- now 10))))))
          (is (nil? (fire-auth/validate-session-cookie "test-project" "not.a.cookie"))))))))
