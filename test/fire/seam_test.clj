(ns fire.seam-test
  "Full round trips through every request path with no network at all.

   fire.utils/*http-fn* is the one place fire touches http. Bound, it answers
   every request fire would have sent, so these tests exercise request
   building, the auth header, response decoding and the error branches end to
   end — the parts the offline tests in each namespace stop short of, and the
   live tests only reach against a real project.

   It is also the model for a consumer's own tests: bind a responder per test
   thread, and a suite that used to share one live Firebase project can run in
   parallel with nothing to collide on."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fire.admin :as admin]
            [fire.auth :as fire-auth]
            [fire.core :as fire]
            [fire.storage :as storage]
            [fire.utils :as utils]))

(def ^:private auth
  "A live token, so token-for never reaches for create-token."
  {:token "test-token" :expiry (+ (utils/now) 3600) :project-id "test-project" :env "TEST"})

(defn- responder
  "A fake http that records every request it is asked for and answers each
   from `answer`, a response map or a function of the request."
  [calls answer]
  (fn [request]
    (swap! calls conj request)
    (if (fn? answer) (answer request) answer)))

(defn- json-response [status body]
  {:status status :body (utils/encode body)})

(def ^:private account
  {:localId "uid-123" :email "person@example.com" :emailVerified true
   :displayName "Charmander" :createdAt "1700000000000"})

(deftest ^:offline admin-round-trip-test
  (let [calls (atom [])]
    (binding [utils/*http-fn* (responder calls (json-response 200 {:users [account]}))]
      (testing "a user lookup builds the identity toolkit request and decodes the account"
        (let [user (admin/get-user "uid-123" auth)]
          (is (= "uid-123" (:uid user)))
          (is (= "Charmander" (:display-name user)))
          (is (true? (:email-verified user)))
          (is (= 1700000000000 (:created-at user)))))

      (testing "and what it sent is what identity toolkit expects"
        (let [[request] @calls]
          (is (= 1 (count @calls)))
          (is (= :post (:method request)))
          (is (= "https://identitytoolkit.googleapis.com/v1/projects/test-project/accounts:lookup" (:url request)))
          (is (= "Bearer test-token" (get-in request [:headers "Authorization"])))
          (is (= {:localId ["uid-123"]} (utils/decode (:body request)))))))

    (testing "a tenant scopes the url"
      (reset! calls [])
      (binding [utils/*http-fn* (responder calls (json-response 200 {:users [account]}))]
        (admin/get-user "uid-123" auth {:tenant-id "t1"})
        (is (str/ends-with? (:url (first @calls)) "/projects/test-project/tenants/t1/accounts:lookup"))))))

(deftest ^:offline admin-error-branches-test
  (testing "identity toolkit's error message becomes charmander's error map"
    (binding [utils/*http-fn* (constantly (json-response 400 {:error {:message "USER_NOT_FOUND"}}))]
      (is (= {:error true :error-data "USER_NOT_FOUND"} (admin/get-user "nobody" auth)))))

  (testing "a lookup that matches nobody is an error, as charmander's callers expect"
    (binding [utils/*http-fn* (constantly (json-response 200 {}))]
      (is (= {:error true :error-data "USER_NOT_FOUND"} (admin/get-user-by-email "nobody@example.com" auth)))))

  (testing "a connection failure is reported, not thrown"
    (binding [utils/*http-fn* (constantly {:error (java.net.ConnectException. "refused")})]
      (is (= {:error true :error-data "refused"} (admin/get-user "uid" auth)))))

  (testing "delete answers nil on success, an error map otherwise"
    (binding [utils/*http-fn* (constantly (json-response 200 {:kind "identitytoolkit#DeleteAccountResponse"}))]
      (is (nil? (admin/delete-user "uid-123" auth))))
    (binding [utils/*http-fn* (constantly (json-response 403 {:error {:message "PERMISSION_DENIED"}}))]
      (is (= {:error true :error-data "PERMISSION_DENIED"} (admin/delete-user "uid-123" auth))))))

(deftest ^:offline admin-refuses-without-credentials-test
  (testing "an auth map with no token to offer is refused before any request is built"
    (let [calls (atom [])]
      (binding [utils/*http-fn* (responder calls (json-response 200 {:users [account]}))]
        (doseq [no-token [nil
                          {:project-id "test-project"}
                          ;; what create-token hands back when the env var is empty
                          {:error true :error-data "MISSING_CREDENTIALS" :env :non-existent-key :project-id "p"}
                          ;; expired, and nothing to mint a replacement from
                          {:token "old" :expiry 0 :project-id "p"}]]
          (is (= {:error true :error-data "MISSING_CREDENTIALS"} (admin/get-user "uid-123" no-token)) (pr-str no-token))
          (is (= {:error true :error-data "MISSING_CREDENTIALS"} (admin/create-user "a@b.com" "secret1" no-token)) (pr-str no-token)))
        ;; the point: nothing was sent to come back as a 401
        (is (= [] @calls))))))

(deftest ^:offline admin-multi-step-test
  (testing "create is create-then-read, both through the same auth"
    (let [calls (atom [])
          answer (fn [request]
                   (if (str/ends-with? (:url request) "/accounts")
                     (json-response 200 {:localId "uid-new"})
                     (json-response 200 {:users [(assoc account :localId "uid-new")]})))]
      (binding [utils/*http-fn* (responder calls answer)]
        (let [user (admin/create-user "person@example.com" "secret1" auth {:display-name "Charmander"})]
          (is (= "uid-new" (:uid user)))
          (is (= 2 (count @calls)))
          (is (= {:email "person@example.com" :password "secret1" :emailVerified false :disabled false :displayName "Charmander"}
                 (utils/decode (:body (first @calls)))))
          (is (= {:localId ["uid-new"]} (utils/decode (:body (second @calls)))))))))

  (testing "a failed create does not go on to the read"
    (let [calls (atom [])]
      (binding [utils/*http-fn* (responder calls (json-response 400 {:error {:message "EMAIL_EXISTS"}}))]
        (is (= {:error true :error-data "EMAIL_EXISTS"} (admin/create-user "person@example.com" "secret1" auth)))
        (is (= 1 (count @calls))))))

  (testing "enumeration pages through the fake exactly as through the real thing"
    (let [pages {nil {:users [(assoc account :localId "a")] :nextPageToken "p2"}
                 "p2" {:users [(assoc account :localId "b")]}}
          answer (fn [request] (json-response 200 (get pages (get-in request [:query-params :nextPageToken]))))]
      (binding [utils/*http-fn* (responder (atom []) answer)]
        (is (= ["a" "b"] (map :uid (admin/list-all-users auth))))))))

(deftest ^:offline admin-mfa-config-test
  (testing "enable-totp-mfa patches only the mfa fields it names"
    (let [calls (atom [])
          config {:mfa {:state "ENABLED" :providerConfigs [{:state "ENABLED" :totpProviderConfig {:adjacentIntervals 3}}]}
                  :signIn {:email {:enabled true} :hashConfig {:signerKey "SECRET"}}
                  :authorizedDomains ["localhost"]}]
      (binding [utils/*http-fn* (responder calls (json-response 200 config))]
        (let [result (admin/enable-totp-mfa auth {:adjacent-intervals 3})
              [request] @calls]
          (is (= :patch (:method request)))
          (is (= "https://identitytoolkit.googleapis.com/admin/v2/projects/test-project/config" (:url request)))
          (is (= "mfa.state,mfa.providerConfigs" (get-in request [:query-params :updateMask])))
          (is (= {:mfa {:state "ENABLED" :providerConfigs [{:state "ENABLED" :totpProviderConfig {:adjacentIntervals 3}}]}}
                 (utils/decode (:body request))))
          (is (= {:state :enabled :totp {:state :enabled :adjacent-intervals 3} :sms {:state :disabled}} (:mfa result)))
          ;; the hashing secret came back in the response and went no further
          (is (not (str/includes? (pr-str result) "SECRET"))))))))

(deftest ^:offline core-round-trip-test
  (testing "a database read goes out as a POST with the method override, and decodes"
    (let [calls (atom [])]
      (binding [utils/*http-fn* (responder calls (json-response 200 {:name "fire"}))]
        (is (= {:name "fire"} (fire/read "test-db" "/path" auth)))
        (let [[request] @calls]
          (is (= "https://test-db.firebaseio.com/path.json" (:url request)))
          (is (= "GET" (get-in request [:headers "X-HTTP-Method-Override"])))
          (is (= "Bearer test-token" (get-in request [:headers "Authorization"])))))))

  (testing "a nil auth is a public database: the request goes out without a bearer"
    (let [calls (atom [])]
      (binding [utils/*http-fn* (responder calls (json-response 200 {:public true}))]
        (is (= {:public true} (fire/read "test-db" "/path" nil)))
        (is (nil? (get-in (first @calls) [:headers "Authorization"]))))))

  (testing "a write carries its body and answers with what firebase echoed"
    (let [calls (atom [])]
      (binding [utils/*http-fn* (responder calls (json-response 200 {:name "written"}))]
        (is (= {:name "written"} (fire/write! "test-db" "/path" {:name "written"} auth)))
        (is (= "PUT" (get-in (first @calls) [:headers "X-HTTP-Method-Override"])))
        (is (= {:name "written"} (utils/decode (:body (first @calls))))))))

  (testing "a failed connection surfaces as the throw the sync arities always made of it"
    (binding [utils/*http-fn* (constantly {:error (java.net.ConnectException. "refused")})]
      (is (thrown? java.net.ConnectException (fire/read "test-db" "/path" auth)))))

  (testing "the async arity still hands back a channel"
    (binding [utils/*http-fn* (constantly (json-response 200 {:a 1}))]
      (let [ch (fire/read "test-db" "/path" auth {:async true})]
        (is (= {:a 1} (clojure.core.async/<!! ch)))))))

(deftest ^:offline storage-round-trip-test
  (testing "a download names the bucket it was given and returns the body"
    (let [calls (atom [])]
      (binding [utils/*http-fn* (responder calls {:status 200 :body "this is fire"})]
        (is (= "this is fire" (storage/download "path/on/firebase.txt" auth {:bucket "my-bucket"})))
        (let [[request] @calls]
          (is (= :get (:method request)))
          (is (= "https://storage.googleapis.com/storage/v1/b/my-bucket/o/path%2Fon%2Ffirebase.txt?alt=media" (:url request)))
          (is (= "Bearer test-token" (get-in request [:headers "Authorization"])))))))

  (testing "a public bucket takes a nil auth and sends no bearer"
    (let [calls (atom [])]
      (binding [utils/*http-fn* (responder calls {:status 200 :body "public"})]
        (is (= "public" (storage/download "file.txt" nil {:bucket "public-bucket"})))
        (is (nil? (get-in (first @calls) [:headers "Authorization"])))))))

(deftest ^:offline certs-through-the-seam-test
  (testing "the public cert fetch is a request like any other, so a bound seam sees it"
    (let [cache @#'fire-auth/cert-cache
          prior @cache
          calls (atom [])]
      (reset! cache {})
      (try
        (binding [utils/*http-fn* (responder calls (json-response 200 {:some-kid "not-a-cert"}))]
          ;; a kid the fixture doesn't hold is a nil; what matters is that the
          ;; fetch went through the seam and was cached from it
          (is (nil? (fire-auth/validate-token "test-project"
                      "eyJhbGciOiJSUzI1NiIsImtpZCI6Im90aGVyIn0.eyJhdWQiOiJ0ZXN0LXByb2plY3QifQ.c2ln")))
          (is (= 1 (count @calls)))
          (is (str/starts-with? (:url (first @calls)) "https://www.googleapis.com/robot/v1/metadata/x509/"))
          (is (= {:some-kid "not-a-cert"} (get-in @cache [@#'fire-auth/id-token-certs-url :certs]))))
        (finally (reset! cache prior))))))

(deftest ^:offline seam-is-thread-local-test
  (binding [utils/*http-fn* (constantly (json-response 200 {:users [account]}))]
    (testing "a bound responder answers on this thread"
      (is (= "uid-123" (:uid (admin/get-user "uid-123" auth)))))

    (testing "and on the futures this thread starts — clojure conveys bindings to them"
      ;; which is what a test wants: its own pmap or future still hits the fake
      (is (= "uid-123" (:uid @(future (admin/get-user "uid-123" auth))))))

    (testing "but not on a thread that was not started under the binding"
      (let [seen (promise)]
        (doto (Thread. (fn [] (deliver seen utils/*http-fn*))) .start)
        (is (nil? @seen))))))
