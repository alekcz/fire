(ns fire.admin-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clj-uuid :as uuid]
            [fire.admin :as admin]
            [fire.auth :as fire-auth]
            [fire.utils :as utils]))

;; ---------------------------------------------------------------------------
;; Two tiers here.
;;
;; The first tier is offline: the pure translation between fire's shapes and
;; the wire's, and the argument checks that short-circuit before anything
;; touches the network. Firebase's own Admin SDK validated phone numbers,
;; photo urls, custom claim names and empty ids client-side, so charmander
;; callers saw errors for those without a round trip — fire keeps that, and
;; these tests pin it down without needing credentials.
;;
;; The second tier talks to real Firebase Auth, the way every other test in
;; this repo talks to real Firebase. It covers charmander's admin_test.clj
;; case for case, so a port can be checked against the behaviour it replaces,
;; and then the features charmander never had.
;; ---------------------------------------------------------------------------

(def ^:private full-account
  {:localId "uid-123"
   :email "person@example.com"
   :emailVerified true
   :photoUrl "https://example.com/pic.jpg"
   :phoneNumber "+27123456789"
   :displayName "Charmander"
   :customAttributes "{\"role\":\"admin\"}"
   :providerUserInfo [{:providerId "password" :rawId "person@example.com" :email "person@example.com"}]
   :mfaInfo [{:mfaEnrollmentId "e1" :totpInfo {}}]
   :createdAt "1700000000000"
   :lastLoginAt "1700000001000"
   :validSince "1700000000"
   :tenantId "tenant-1"})

(deftest ^:offline convert-user-record-test
  (testing "an identity toolkit account maps onto fire's user map"
    (is (= {:email "person@example.com"
            :email-verified true
            :uid "uid-123"
            :provider-id "firebase"
            :photo-url "https://example.com/pic.jpg"
            :phone-number "+27123456789"
            :display-name "Charmander"
            :disabled false
            :custom-claims {:role "admin"}
            :provider-data [{:provider-id "password"
                             :uid "person@example.com"
                             :email "person@example.com"
                             :display-name nil
                             :photo-url nil
                             :phone-number nil}]
            :mfa-info [{:id "e1" :type :totp :display-name nil :phone-number nil :enrolled-at nil}]
            :created-at 1700000000000
            :last-login-at 1700000001000
            :valid-since 1700000000
            :tenant-id "tenant-1"}
           (#'admin/convert-user-record full-account))))

  (testing "charmander's eight keys are present, unrenamed — that's the port contract"
    (let [record (#'admin/convert-user-record full-account)]
      (is (every? #(contains? record %)
                  [:email :email-verified :uid :provider-id :photo-url
                   :phone-number :display-name :disabled]))))

  (testing "emailVerified and disabled are absent rather than false in the json,
            so they come back as booleans and not as nil"
    (let [record (#'admin/convert-user-record {:localId "uid-123" :email "person@example.com"})]
      (is (false? (:email-verified record)))
      (is (false? (:disabled record)))
      (is (nil? (:custom-claims record)))
      (is (= [] (:provider-data record)))
      (is (= [] (:mfa-info record)))
      (is (nil? (:created-at record)))
      (is (nil? (:valid-since record)))))

  (testing "nil in, nil out"
    (is (nil? (#'admin/convert-user-record nil)))))

(deftest ^:offline factor-test
  (testing "the factor type is inferred from which info block is present"
    (is (= :totp (:type (#'admin/->factor {:mfaEnrollmentId "e1" :totpInfo {}}))))
    (is (= :phone (:type (#'admin/->factor {:mfaEnrollmentId "e2" :phoneInfo "+27123456789"}))))
    (is (= :unknown (:type (#'admin/->factor {:mfaEnrollmentId "e3"})))))
  (testing "a phone factor carries its number through"
    (is (= "+27123456789" (:phone-number (#'admin/->factor {:mfaEnrollmentId "e2" :phoneInfo "+27123456789"}))))))

(deftest ^:offline endpoint-test
  (testing "urls are project scoped"
    (is (= "https://identitytoolkit.googleapis.com/v1/projects/p/accounts"
           (#'admin/endpoint "/accounts" {:project-id "p"} nil))))
  (testing "and tenant scoped on top of that when a tenant is named"
    (is (= "https://identitytoolkit.googleapis.com/v1/projects/p/tenants/t/accounts:lookup"
           (#'admin/endpoint "/accounts:lookup" {:project-id "p"} {:tenant-id "t"}))))
  (testing "the session cookie endpoint hangs off the project, not off accounts"
    (is (= "https://identitytoolkit.googleapis.com/v1/projects/p:createSessionCookie"
           (#'admin/endpoint ":createSessionCookie" {:project-id "p"} nil))))
  (testing "options override the project the credentials name"
    (is (= "https://identitytoolkit.googleapis.com/v1/projects/other/accounts"
           (#'admin/endpoint "/accounts" {:project-id "p"} {:project-id "other"})))))

(deftest ^:offline update-body-test
  (testing "fields translate to the api's camelCase names"
    (is (= {:email "a@b.com" :password "secret"}
           (#'admin/->update-body {:email "a@b.com" :password "secret"})))
    (is (= {:disableUser true} (#'admin/->update-body {:disabled true})))
    (is (= {:emailVerified false} (#'admin/->update-body {:email-verified false})))
    (is (= {:validSince "1700000000"} (#'admin/->update-body {:valid-since 1700000000}))))

  (testing "custom claims travel as a json string, not an object"
    (is (= {:customAttributes "{\"role\":\"admin\"}"}
           (#'admin/->update-body {:custom-claims {:role "admin"}})))
    (is (= {:customAttributes "{}"} (#'admin/->update-body {:custom-claims nil}))))

  (testing "a key present with a nil value clears the field"
    (is (= {:deleteAttribute ["DISPLAY_NAME"]} (#'admin/->update-body {:display-name nil})))
    (is (= {:deleteAttribute ["DISPLAY_NAME" "PHOTO_URL"]}
           (#'admin/->update-body {:display-name nil :photo-url nil})))
    (is (= {:deleteProvider ["phone"]} (#'admin/->update-body {:phone-number nil}))))

  (testing "a key left out entirely changes nothing"
    (is (= {} (#'admin/->update-body {})))
    (is (= {:displayName "Charmander"} (#'admin/->update-body {:display-name "Charmander"}))))

  (testing "providers and second factors"
    (is (= {:deleteProvider ["google.com"]} (#'admin/->update-body {:unlink-providers ["google.com"]})))
    (is (= {:mfa {:enrollments []}} (#'admin/->update-body {:mfa-enrollments []})))))

(deftest ^:offline claims-problem-test
  (testing "ordinary claims are fine"
    (is (nil? (#'admin/claims-problem nil)))
    (is (nil? (#'admin/claims-problem {})))
    (is (nil? (#'admin/claims-problem {:role "admin" :tier 3}))))
  (testing "names firebase already owns are refused, whether keyword or string"
    (is (str/starts-with? (#'admin/claims-problem {:sub "x"}) "RESERVED_CLAIM"))
    (is (str/starts-with? (#'admin/claims-problem {"firebase" 1}) "RESERVED_CLAIM"))
    (is (str/starts-with? (#'admin/claims-problem {:user_id "x"}) "RESERVED_CLAIM")))
  (testing "and so is anything past firebase's 1000 byte limit"
    (is (= "CLAIMS_TOO_LARGE" (#'admin/claims-problem {:blob (apply str (repeat 1200 "x"))}))))
  (testing "claims have to be a map at all"
    (is (= "INVALID_CLAIMS" (#'admin/claims-problem "not a map")))))

(deftest ^:offline e164-test
  (testing "phone numbers must be E.164, as firebase requires"
    (is (#'admin/e164? "+27123456789"))
    (is (#'admin/e164? "+15555550100"))
    (is (not (#'admin/e164? "")))
    (is (not (#'admin/e164? "27123456789")))
    (is (not (#'admin/e164? "+0123456789")))
    (is (not (#'admin/e164? "not a phone number")))
    (is (not (#'admin/e164? nil)))))

(deftest ^:offline valid-url-test
  (testing "photo urls must be absolute, scheme and all"
    (is (#'admin/valid-url? "https://www.domain.com/pic.jpg"))
    (is (not (#'admin/valid-url? "domain.com/pic.jpg")))
    (is (not (#'admin/valid-url? "")))
    (is (not (#'admin/valid-url? nil)))))

(deftest ^:offline error-message-test
  (testing "the message is dug out of an identity toolkit error body"
    (is (= "EMAIL_EXISTS" (#'admin/error-message {:error {:code 400 :message "EMAIL_EXISTS"}})))
    (is (= "boom" (#'admin/error-message {:error "boom"})))
    (is (= "Unknown error" (#'admin/error-message nil)))))

(deftest ^:offline local-guards-test
  (testing "bad arguments are refused without a round trip, in charmander's error shape"
    ;; nil auth proves the point: if any of these reached the network they would
    ;; fail differently (and slowly) rather than returning these exact messages
    (is (= {:error true :error-data "INVALID_PHONE_NUMBER"} (admin/set-user-phone-number "uid" "" nil)))
    (is (= {:error true :error-data "INVALID_PHOTO_URL"} (admin/set-user-photo-url "uid" "domain.com/pic.jpg" nil)))
    (is (= {:error true :error-data "MISSING_PASSWORD"} (admin/set-user-password "uid" "" nil)))
    (is (= {:error true :error-data "WEAK_PASSWORD"} (admin/set-user-password "uid" "12345" nil)))
    (is (= {:error true :error-data "WEAK_PASSWORD"} (admin/update-user "uid" {:password "123"} nil)))
    (is (= {:error true :error-data "WEAK_PASSWORD"} (admin/create-user "a@b.com" "123" nil)))
    (is (= {:error true :error-data "INVALID_LOCAL_ID"} (admin/set-user-display-name "" "Charmander" nil)))
    (is (= {:error true :error-data "INVALID_LOCAL_ID"} (admin/delete-user "" nil)))
    (is (= {:error true :error-data "INVALID_LOCAL_ID"} (admin/delete-user nil nil)))
    (is (= {:error true :error-data "MISSING_PROVIDER_ID"} (admin/unlink-provider "uid" "" nil)))
    (is (= {:error true :error-data "MISSING_LOCAL_ID"} (admin/delete-users [] nil)))
    (is (= {:error true :error-data "TOO_MANY_LOCAL_IDS"} (admin/delete-users (repeat 1001 "uid") nil)))
    (is (= {:error true :error-data "MISSING_CONTINUE_URI"} (admin/generate-sign-in-with-email-link "a@b.com" "" nil)))
    (is (= {:error true :error-data "MISSING_NEW_EMAIL"} (admin/generate-verify-and-change-email-link "a@b.com" "" nil))))

  (testing "reserved custom claims are caught before the call, not after"
    (is (str/starts-with? (:error-data (admin/set-custom-user-claims "uid" {:sub "x"} nil)) "RESERVED_CLAIM"))
    (is (str/starts-with? (:error-data (admin/create-user "a@b.com" "secret1" nil {:custom-claims {:iss "x"}}))
                          "RESERVED_CLAIM")))

  (testing "custom token arguments"
    (is (= {:error true :error-data "INVALID_LOCAL_ID"} (admin/create-custom-token "" nil)))
    (is (= {:error true :error-data "INVALID_LOCAL_ID"} (admin/create-custom-token (apply str (repeat 129 "x")) nil)))
    (is (str/starts-with? (:error-data (admin/create-custom-token "uid" nil {:claims {:aud "x"}})) "RESERVED_CLAIM"))
    ;; no credentials to sign with, so it can't produce a token either way
    (is (:error (admin/create-custom-token "uid" nil))))

  (testing "session cookie durations are bounded by firebase's 5 minutes to 14 days"
    (is (= {:error true :error-data "MISSING_ID_TOKEN"} (admin/create-session-cookie "" nil)))
    (is (= {:error true :error-data "INVALID_DURATION"} (admin/create-session-cookie "token" nil {:valid-duration 60})))
    (is (= {:error true :error-data "INVALID_DURATION"} (admin/create-session-cookie "token" nil {:valid-duration 2000000})))))

(def ^:private fake-pages
  {nil  {:users [1 2] :next-page-token "t1"}
   "t1" {:users [3 4] :next-page-token "t2"}
   "t2" {:users [5]   :next-page-token nil}})

(defn- counting-fetch
  "A fake paginated endpoint that records which page tokens it was asked for."
  [calls]
  (fn [token] (swap! calls conj token) (get fake-pages token)))

(deftest ^:offline page-seq-test
  (let [calls (atom [])
        fetch (counting-fetch calls)]

    (testing "every page is walked, in order, and the tokens chain"
      (reset! calls [])
      (is (= [1 2 3 4 5] (vec (#'admin/page-seq fetch nil))))
      (is (= [nil "t1" "t2"] @calls)))

    (testing "nothing is fetched until the sequence is consumed"
      (reset! calls [])
      (let [s (#'admin/page-seq fetch nil)]
        (is (= [] @calls))
        (is (= 1 (first s)))
        (is (= [nil] @calls))))

    (testing "only the pages actually needed are fetched"
      (reset! calls [])
      (is (= [1 2 3] (take 3 (#'admin/page-seq fetch nil))))
      ;; the third element lives on page two, so page three is never requested
      (is (= [nil "t1"] @calls)))

    (testing "a transducer that stops early stops the paging with it"
      (reset! calls [])
      (is (= [1 2] (into [] (take 2) (#'admin/page-seq fetch nil))))
      (is (= [nil] @calls)))

    (testing "the last page ends the sequence without another request"
      (reset! calls [])
      (is (= 5 (count (#'admin/page-seq fetch nil))))
      (is (= 3 (count @calls))))))

(deftest ^:offline page-seq-failure-test
  (testing "a failing page throws rather than truncating silently"
    ;; the whole point: a short list must not be mistakable for a complete one
    (let [boom (fn [_] {:error true :error-data "PERMISSION_DENIED"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PERMISSION_DENIED"
            (doall (#'admin/page-seq boom nil))))
      (is (:error (ex-data (try (doall (#'admin/page-seq boom nil))
                                (catch clojure.lang.ExceptionInfo e e)))))))

  (testing "a page that fails midway still throws, after yielding what came before"
    (let [pages {nil {:users [1 2] :next-page-token "t1"}
                 "t1" {:error true :error-data "PERMISSION_DENIED"}}
          fetch #(get pages %)
          s (#'admin/page-seq fetch nil)]
      (is (= [1 2] (take 2 s)))
      (is (thrown? clojure.lang.ExceptionInfo (doall s))))))

(deftest ^:offline long-parse-test
  (testing "identity toolkit int64s arrive as strings, and junk degrades to nil"
    (is (= 1700000000000 (#'admin/->long "1700000000000")))
    (is (= 42 (#'admin/->long 42)))
    (is (nil? (#'admin/->long nil)))
    (is (nil? (#'admin/->long "not-a-number")))))

(deftest ^:offline missing-credentials-test
  (testing "signing a custom token without a private key to sign it with"
    ;; an env var that resolves to nothing, rather than one that throws
    (is (= {:error true :error-data "MISSING_CREDENTIALS"}
           (admin/create-custom-token "uid-123" {:env :non-existent-key})))))

(deftest ^:offline validate-token-fails-closed-test
  (testing "a token that doesn't survive fire.auth never reaches the account lookup"
    ;; no network and no credentials involved: the signature check rejects first
    (is (nil? (admin/validate-token "test-project" "not.a.jwt" nil)))
    (is (nil? (admin/validate-token "test-project" "" nil)))
    (is (nil? (admin/validate-session-cookie "test-project" "not.a.cookie" nil)))))

;; ---------------------------------------------------------------------------
;; Live Firebase Auth from here down. Needs GOOGLE_APPLICATION_CREDENTIALS and
;; the Email/Password sign-in provider enabled on the project.
;; ---------------------------------------------------------------------------

(def ^:private auth (delay (fire-auth/create-token)))

(defn- unique-email [] (str (uuid/v1) "@domain.com"))

(defn- fresh-user [] (admin/create-user (unique-email) "superDuperSecure" @auth))

(deftest create-user-test
  (testing "creating a user, and that a second one on the same email is refused"
    (let [email (unique-email)
          response (admin/create-user email "superDuperSecure" @auth)
          duplicate (admin/create-user email "superDuperSecure" @auth)]
      (try
        (is (= email (:email response)))
        (is (not (:error response)))
        (is (:error duplicate))
        (is (not= response duplicate))
        (finally (admin/delete-user (:uid response) @auth))))))

(deftest create-user-with-uid-test
  (testing "creating a user with a chosen uid, and that the uid can't be reused"
    (let [uid (str (uuid/v1) "-" (uuid/v1))
          email (unique-email)
          response (admin/create-user email "superDuperSecure" @auth {:uid uid})
          duplicate (admin/create-user (unique-email) "superDuperSecure" @auth {:uid uid})]
      (try
        (is (= email (:email response)))
        (is (= uid (:uid response)))
        (is (:error duplicate))
        (finally (admin/delete-user uid @auth))))))

(deftest create-user-with-profile-test
  (testing "the whole profile can be set at creation, custom claims included"
    (let [response (admin/create-user (unique-email) "superDuperSecure" @auth
                                      {:display-name "Charmander"
                                       :photo-url "https://www.domain.com/pic.jpg"
                                       :email-verified true
                                       :custom-claims {:role "staff"}})]
      (try
        (is (= "Charmander" (:display-name response)))
        (is (= "https://www.domain.com/pic.jpg" (:photo-url response)))
        (is (true? (:email-verified response)))
        (is (= {:role "staff"} (:custom-claims response)))
        (finally (admin/delete-user (:uid response) @auth))))))

(deftest get-user-test
  (testing "the three lookups all resolve to the same record"
    (let [prep (fresh-user)
          phone "+27123456789"
          response (admin/set-user-phone-number (:uid prep) phone @auth)]
      (try
        (is (= response (admin/get-user (:uid response) @auth)))
        (is (= response (admin/get-user-by-email (:email response) @auth)))
        (is (= response (admin/get-user-by-phone-number phone @auth)))
        (finally (admin/delete-user (:uid prep) @auth)))))

  (testing "a lookup that matches nobody is an error, not an empty result"
    (is (:error (admin/get-user (str (uuid/v1)) @auth)))
    (is (:error (admin/get-user "" @auth)))
    (is (:error (admin/get-user-by-email (unique-email) @auth)))
    (is (:error (admin/get-user-by-phone-number "" @auth)))))

(deftest get-users-test
  (testing "a batch lookup returns what it finds and stays quiet about the rest"
    (let [a (fresh-user)
          b (fresh-user)]
      (try
        (let [found (admin/get-users {:uids [(:uid a) (:uid b) (str (uuid/v1))]} @auth)]
          (is (= 2 (count found)))
          (is (= #{(:uid a) (:uid b)} (set (map :uid found)))))
        (is (= 2 (count (admin/get-users {:emails [(:email a) (:email b)]} @auth))))
        ;; nothing asked for, nothing to ask the api
        (is (= [] (admin/get-users {} @auth)))
        (finally
          (admin/delete-user (:uid a) @auth)
          (admin/delete-user (:uid b) @auth))))))

(deftest list-users-test
  (testing "listing pages, and that a page size is honoured"
    (let [a (fresh-user)
          b (fresh-user)]
      (try
        (let [page (admin/list-users @auth {:page-size 1})]
          (is (= 1 (count (:users page))))
          (is (not (str/blank? (:next-page-token page))))
          ;; the token walks forward rather than repeating the same page
          (is (not= (map :uid (:users page))
                    (map :uid (:users (admin/list-users @auth {:page-size 1 :page-token (:next-page-token page)}))))))
        (let [everyone (admin/list-all-users @auth {:page-size 2})]
          (is (seq everyone))
          (is (contains? (set (map :uid everyone)) (:uid a)))
          (is (contains? (set (map :uid everyone)) (:uid b))))
        (testing "the lazy enumeration only pages as far as it is consumed"
          ;; a page size of one means taking two users is two requests, not the
          ;; whole project — the assertion is that this returns at all, quickly
          (is (= 2 (count (take 2 (admin/list-all-users @auth {:page-size 1}))))))
        (testing "search-users filters over that same enumeration"
          (let [found (into [] (admin/search-users
                                 (comp (filter #(= (:uid a) (:uid %))) (take 1))
                                 @auth
                                 {:page-size 100}))]
            (is (= [(:uid a)] (map :uid found))))
          ;; a transducer that matches nothing still terminates
          (is (= [] (into [] (admin/search-users (filter (constantly false)) @auth
                                                 {:page-size 1000})))))
        (finally
          (admin/delete-user (:uid a) @auth)
          (admin/delete-user (:uid b) @auth))))))

(deftest set-user-display-name-test
  (testing "setting a display name, and clearing it again"
    (let [prep (fresh-user)
          response (admin/set-user-display-name (:uid prep) "Charmander" @auth)]
      (try
        (is (= (:uid prep) (:uid response)))
        (is (= "Charmander" (:display-name response)))
        (is (not= (:display-name prep) (:display-name response)))
        (is (nil? (:display-name (admin/set-user-display-name (:uid prep) nil @auth))))
        (finally (admin/delete-user (:uid prep) @auth))))))

(deftest set-user-phone-number-test
  (testing "setting a phone number, and unlinking it again"
    (let [prep (fresh-user)
          response (admin/set-user-phone-number (:uid prep) "+27123456789" @auth)]
      (try
        (is (= (:uid prep) (:uid response)))
        (is (= "+27123456789" (:phone-number response)))
        (is (not= (:phone-number prep) (:phone-number response)))
        (is (nil? (:phone-number (admin/set-user-phone-number (:uid prep) nil @auth))))
        (finally (admin/delete-user (:uid prep) @auth))))))

(deftest set-user-photo-url-test
  (testing "setting a photo url"
    (let [prep (fresh-user)
          response (admin/set-user-photo-url (:uid prep) "https://www.domain.com/pic.jpg" @auth)]
      (try
        (is (= (:uid prep) (:uid response)))
        (is (= "https://www.domain.com/pic.jpg" (:photo-url response)))
        (finally (admin/delete-user (:uid prep) @auth))))))

(deftest set-user-email-test
  (testing "setting an email address"
    (let [prep (fresh-user)
          email (unique-email)
          response (admin/set-user-email (:uid prep) email @auth)]
      (try
        (is (= (:uid prep) (:uid response)))
        (is (= email (:email response)))
        (is (not= (:email prep) (:email response)))
        (finally (admin/delete-user (:uid prep) @auth)))))

  (testing "an email already taken by someone else is refused"
    (let [taken (unique-email)
          prep (fresh-user)
          other (admin/create-user taken "superDuperSecure" @auth)]
      (try
        (is (:error (admin/set-user-email (:uid prep) taken @auth)))
        (finally
          (admin/delete-user (:uid prep) @auth)
          (admin/delete-user (:uid other) @auth)))))

  (testing "a malformed email is refused"
    (let [prep (fresh-user)]
      (try
        (is (:error (admin/set-user-email (:uid prep) (str (uuid/v1)) @auth)))
        (finally (admin/delete-user (:uid prep) @auth))))))

(deftest set-user-password-test
  (testing "setting a password, and that firebase's minimum length still applies"
    (let [prep (fresh-user)]
      (try
        (is (= (:uid prep) (:uid (admin/set-user-password (:uid prep) "Charizard" @auth))))
        (is (= "MISSING_PASSWORD" (:error-data (admin/set-user-password (:uid prep) "" @auth))))
        ;; firebase's REST update endpoint does not enforce its own six character
        ;; minimum — the admin SDK did it client-side, and so does fire
        (is (= "WEAK_PASSWORD" (:error-data (admin/set-user-password (:uid prep) "123" @auth))))
        (finally (admin/delete-user (:uid prep) @auth))))))

(deftest email-verified-and-disabled-test
  (testing "verifying, disabling and re-enabling an account"
    (let [prep (fresh-user)
          uid (:uid prep)]
      (try
        (is (false? (:email-verified prep)))
        (is (true? (:email-verified (admin/set-user-email-verified uid true @auth))))
        (is (false? (:email-verified (admin/set-user-email-verified uid false @auth))))
        (is (true? (:disabled (admin/disable-user uid @auth))))
        (is (false? (:disabled (admin/enable-user uid @auth))))
        (finally (admin/delete-user uid @auth))))))

(deftest custom-claims-test
  (testing "custom claims round trip, and clear"
    (let [prep (fresh-user)
          uid (:uid prep)]
      (try
        (is (nil? (:custom-claims prep)))
        (is (= {:role "staff" :tier 3} (:custom-claims (admin/set-custom-user-claims uid {:role "staff" :tier 3} @auth))))
        (is (= {:role "admin"} (:custom-claims (admin/set-custom-user-claims uid {:role "admin"} @auth))))
        ;; clearing leaves nothing behind rather than an empty map
        (is (empty? (or (:custom-claims (admin/set-custom-user-claims uid nil @auth)) {})))
        (finally (admin/delete-user uid @auth)))))

  (testing "reserved names are refused by the library before firebase sees them"
    (is (str/starts-with? (:error-data (admin/set-custom-user-claims "uid" {:exp 1} @auth)) "RESERVED_CLAIM"))))

(deftest update-user-test
  (testing "several fields in one call"
    (let [prep (fresh-user)
          uid (:uid prep)
          response (admin/update-user uid {:display-name "Charmander"
                                           :photo-url "https://www.domain.com/pic.jpg"
                                           :email-verified true
                                           :custom-claims {:role "staff"}}
                                      @auth)]
      (try
        (is (= "Charmander" (:display-name response)))
        (is (= "https://www.domain.com/pic.jpg" (:photo-url response)))
        (is (true? (:email-verified response)))
        (is (= {:role "staff"} (:custom-claims response)))
        ;; a nil clears, a missing key leaves alone
        (let [cleared (admin/update-user uid {:display-name nil} @auth)]
          (is (nil? (:display-name cleared)))
          (is (= "https://www.domain.com/pic.jpg" (:photo-url cleared))))
        (finally (admin/delete-user uid @auth))))))

(deftest revoke-refresh-tokens-test
  (testing "revoking stamps valid-since forward, which is what token checks read"
    (let [prep (fresh-user)
          uid (:uid prep)
          before (utils/now)
          response (admin/revoke-refresh-tokens uid @auth)]
      (try
        (is (not (:error response)))
        (is (>= (:valid-since response) (- before 5)))
        (finally (admin/delete-user uid @auth))))))

(deftest second-factor-test
  (testing "a user with no enrolled factors has an empty list, not an error"
    (let [prep (fresh-user)]
      (try
        (is (= [] (admin/list-user-factors (:uid prep) @auth)))
        (is (= [] (:mfa-info (admin/get-user (:uid prep) @auth))))
        ;; unenrolling something they don't have is refused rather than silently ok
        (is (:error (admin/unenroll-user-factor (:uid prep) "no-such-enrollment" @auth)))
        (is (:error (admin/unenroll-user-factor (:uid prep) "" @auth)))
        ;; clearing an already-empty set is harmless
        (is (not (:error (admin/unenroll-all-user-factors (:uid prep) @auth))))
        (finally (admin/delete-user (:uid prep) @auth)))))

  (testing "factors for somebody who doesn't exist is an error"
    (is (:error (admin/list-user-factors (str (uuid/v1)) @auth)))))

(deftest email-action-link-test
  (testing "generating verification, reset and sign-in links"
    (let [email (unique-email)
          prep (admin/create-user email "superDuperSecure" @auth)]
      (try
        (is (str/includes? (admin/generate-email-verification-link email @auth) "https://"))
        (is (str/includes? (admin/generate-password-reset-link email @auth) "https://"))
        ;; passwordless email-link sign-in is a provider that has to be switched on
        ;; per project. where it isn't, firebase answers OPERATION_NOT_ALLOWED —
        ;; which still proves the request fire built was well formed and understood.
        (let [link (admin/generate-sign-in-with-email-link email "https://domain.com/done" @auth)]
          (is (or (and (string? link) (str/includes? link "https://"))
                  (= "OPERATION_NOT_ALLOWED" (:error-data link)))))
        (is (str/includes? (admin/generate-verify-and-change-email-link email (unique-email) @auth) "https://"))
        (finally (admin/delete-user (:uid prep) @auth)))))

  (testing "links for something that isn't an email address are refused"
    (is (:error (admin/generate-email-verification-link (str (uuid/v1)) @auth)))
    (is (:error (admin/generate-password-reset-link (str (uuid/v1)) @auth)))))

(deftest create-custom-token-test
  (testing "a custom token is a real, well-formed jwt for the given uid"
    (let [token (admin/create-custom-token "uid-123" @auth {:claims {:role "staff"}})
          [_ payload _] (str/split (str token) #"\." 3)
          claims (utils/decode (String. (.decode (java.util.Base64/getUrlDecoder) ^String payload) "UTF-8"))]
      (is (string? token))
      (is (= 3 (count (str/split (str token) #"\."))))
      (is (= "uid-123" (:uid claims)))
      (is (= {:role "staff"} (:claims claims)))
      (is (= "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit" (:aud claims)))
      (is (= (:iss claims) (:sub claims)))
      ;; firebase caps these at an hour
      (is (<= (- (:exp claims) (:iat claims)) 3600)))))

(deftest create-session-cookie-test
  (testing "a session cookie can't be minted from a token that isn't one"
    (is (:error (admin/create-session-cookie "not.a.real.token" @auth)))))

(deftest unauthorized-project-test
  (testing "reads against a project the credentials can't touch come back as errors"
    ;; every read path has an error branch that the happy-path tests never reach
    (let [nowhere {:project-id "fire-no-such-project-000"}]
      (is (:error (admin/get-user "uid" @auth nowhere)))
      (is (:error (admin/get-users {:uids ["uid"]} @auth nowhere)))
      (is (:error (admin/list-users @auth nowhere)))
      ;; list-all-users is lazy, so merely calling it does nothing at all —
      ;; the failure only surfaces when something consumes it, and then as a
      ;; throw rather than an error map
      (is (some? (admin/list-all-users @auth nowhere)))
      (is (thrown? clojure.lang.ExceptionInfo (doall (admin/list-all-users @auth nowhere))))
      (is (thrown? clojure.lang.ExceptionInfo
            (into [] (admin/search-users (map identity) @auth nowhere))))
      (is (:error (admin/list-user-factors "uid" @auth nowhere)))
      (is (:error (admin/delete-users ["uid"] @auth nowhere)))
      (is (:error (admin/unenroll-user-factor "uid" "enrollment" @auth nowhere)))
      (is (:error (admin/create-session-cookie "token" @auth nowhere))))))

(deftest unlink-provider-test
  (testing "unlinking a provider drops that identity but keeps the account"
    (let [prep (fresh-user)
          uid (:uid prep)]
      (try
        (let [with-phone (admin/set-user-phone-number uid "+27123456789" @auth)]
          (is (= "+27123456789" (:phone-number with-phone)))
          (is (contains? (set (map :provider-id (:provider-data with-phone))) "phone")))
        (let [unlinked (admin/unlink-provider uid "phone" @auth)]
          (is (= uid (:uid unlinked)))
          (is (nil? (:phone-number unlinked)))
          (is (not (contains? (set (map :provider-id (:provider-data unlinked))) "phone"))))
        (finally (admin/delete-user uid @auth))))))

(deftest revocation-test
  (testing "the checks behind admin/validate-token that fire.auth can't make"
    ;; a real firebase ID token needs a web api key to obtain, which this repo
    ;; doesn't wire into CI — so this drives the check with synthetic claims
    ;; against real accounts. That's the half fire.auth structurally cannot do,
    ;; and the half worth being sure about.
    (let [prep (fresh-user)
          uid (:uid prep)]
      (try
        (testing "an untouched account passes"
          (is (true? (boolean (#'admin/still-good? {:uid uid :iat (utils/now)} @auth nil)))))

        (testing "a token issued before a revocation stops passing"
          (admin/revoke-refresh-tokens uid @auth)
          (is (false? (boolean (#'admin/still-good? {:uid uid :iat (- (utils/now) 3600)} @auth nil)))))

        (testing "a disabled account fails however new the token is"
          (admin/disable-user uid @auth)
          (is (false? (boolean (#'admin/still-good? {:uid uid :iat (+ (utils/now) 3600)} @auth nil)))))

        (finally (admin/delete-user uid @auth))))

    (testing "and a token for somebody who no longer exists fails closed"
      (is (false? (boolean (#'admin/still-good? {:uid (str (uuid/v1)) :iat (utils/now)} @auth nil)))))))

(deftest delete-user-test
  (testing "deleting a user returns nil, and deleting a stranger is an error"
    (let [prep (fresh-user)]
      (is (nil? (admin/delete-user (:uid prep) @auth)))
      (is (:error (admin/get-user (:uid prep) @auth))))
    (is (:error (admin/delete-user "123abc123abcNotThere" @auth))))

  (testing "deleting a batch returns nil when they all go"
    (let [a (fresh-user)
          b (fresh-user)]
      (is (nil? (admin/delete-users [(:uid a) (:uid b)] @auth)))
      (is (:error (admin/get-user (:uid a) @auth)))
      (is (:error (admin/get-user (:uid b) @auth))))))
