(ns fire.admin
  (:require [org.httpkit.client :as client]
            [org.httpkit.sni-client :as sni-client]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [fire.auth :as fire-auth]
            [fire.oauth2 :as oauth2]
            [fire.utils :as utils])
  (:gen-class))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Admin user management — charmander.admin's feature set and then some.
;;
;; Charmander drove this through the Java Firebase Admin SDK and a global
;; FirebaseApp singleton you had to (init) first. Fire talks to the same
;; service — the Identity Toolkit REST API the SDK itself wraps — over
;; http-kit, so there is no singleton, no init, and no new dependency:
;; the auth map you already pass to fire.core and fire.storage is passed
;; here too. That also sidesteps the Admin SDK's lagging TOTP factor
;; support, since REST exposes whatever Identity Platform exposes.
;;
;; The return contract is charmander's, deliberately, so a port is mostly
;; a namespace rename: a user map on success, {:error true :error-data msg}
;; on any failure. Every public function takes an optional trailing options
;; map, which understands :project-id and :tenant-id.
;; ---------------------------------------------------------------------------

(def sni-client (delay (client/make-client {:ssl-configurer sni-client/ssl-configurer})))

(def ^:private projects-root "https://identitytoolkit.googleapis.com/v1/projects")

;; project configuration lives on a different version of the same api. the
;; identityplatform.googleapis.com host serves it too, but has to be enabled on
;; the project separately — this host is already enabled wherever fire works at
;; all, and the identitytoolkit scope already covers it.
(def ^:private admin-root "https://identitytoolkit.googleapis.com/admin/v2/projects")

(def ^:private mfa-states #{:disabled :enabled :mandatory})

(def ^:private custom-token-audience
  "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit")

;; firebase refuses custom claims that would collide with a claim it (or the
;; jwt spec) already owns, because a consumer reading :sub off a token has no
;; way to tell a forged one from the real thing.
(def ^:private reserved-claims
  #{"acr" "amr" "at_hash" "aud" "auth_time" "azp" "cnf" "c_hash" "exp" "firebase"
    "iat" "iss" "jti" "nbf" "nonce" "sub" "user_id"})

(defn- err
  "Charmander's error shape. Every failure — network, non-2xx, or a
   locally-caught bad argument — comes back looking like this."
  [message]
  {:error true :error-data (str message)})

(defn- error-message
  "Pull the human-readable message out of an Identity Toolkit error body."
  [body]
  (or (-> body :error :message) (:error body) "Unknown error"))

(defn- ->long
  "Identity toolkit hands back int64s as strings, as proto3 json does."
  [x]
  (when x (try (Long/parseLong (str x)) (catch Exception _ nil))))

(defn- e164?
  "Firebase only accepts E.164 phone numbers, and rejects anything else
   before it ever reaches the wire. Same check, same place."
  [phone-number]
  (boolean (and (string? phone-number) (re-matches #"\+[1-9]\d{1,14}" phone-number))))

(defn- short-password?
  "Firebase's own six character minimum. The Admin SDK checked this client-side,
   so charmander callers got an error without a round trip — the REST update
   endpoint doesn't check it at all, which would silently let a one character
   password through. Checked here so the rule survives the port."
  [password]
  (< (count (str password)) 6))

(defn- valid-url? [url]
  (boolean (and (string? url)
                (not (str/blank? url))
                (try (io/as-url url) true (catch Exception _ false)))))

(defn- claims-problem
  "Why firebase would refuse these custom claims, or nil if it wouldn't."
  [claims]
  (let [names (when (map? claims) (map name (keys claims)))
        clashes (filter reserved-claims names)]
    (cond
      (nil? claims) nil
      (not (map? claims)) "INVALID_CLAIMS"
      (seq clashes) (str "RESERVED_CLAIM: " (str/join ", " clashes))
      ;; firebase's own limit, checked here so the message says which limit
      (> (count (utils/encode claims)) 1000) "CLAIMS_TOO_LARGE"
      :else nil)))

(defn- endpoint
  "Identity toolkit urls are project scoped, and tenant scoped on top of that
   when a tenant is named."
  [root path auth options]
  (str root "/" (or (:project-id options) (:project-id auth))
       (when (:tenant-id options) (str "/tenants/" (:tenant-id options)))
       path))

(defn- request
  "Call an Identity Toolkit admin endpoint for the project behind `auth`.
   Returns the decoded body on success and an error map on any failure."
  [{:keys [method path body query-params root]} auth options]
  (try
    (let [token (when (:expiry auth)
                  (if (< (utils/now) (:expiry auth))
                    (:token auth)
                    (-> auth :env fire-auth/create-token :token)))
          request-options (reduce utils/recursive-merge
                            [{:method (or method :post)}
                             {:url (endpoint (or root projects-root) path auth options)}
                             {:headers {"Content-Type" "application/json"
                                        "Connection" "keep-alive"}}
                             {:keepalive 600000}
                             (when body {:body (utils/encode body)})
                             (when query-params {:query-params query-params})
                             (when token {:headers {"Authorization" (str "Bearer " token)}})])
          c sni-client]
      (binding [org.httpkit.client/*default-client* c]
        (let [response @(client/request request-options)
              status (or (:status response) 0)
              decoded (some-> response :body utils/decode)]
          (cond
            (:error response) (err (ex-message (:error response)))
            (<= 200 status 299) (or decoded {})
            :else (err (error-message decoded))))))
    (catch Exception e (err (.getMessage e)))))

; converting what the api returns

(defn- ->factor
  "One enrolled second factor, in fire's shape rather than the wire's."
  [entry]
  {:id (:mfaEnrollmentId entry)
   :type (cond (:totpInfo entry) :totp
               (:phoneInfo entry) :phone
               :else :unknown)
   :display-name (:displayName entry)
   :phone-number (:phoneInfo entry)
   :enrolled-at (:enrolledAt entry)})

(defn- ->provider [entry]
  {:provider-id (:providerId entry)
   :uid (:rawId entry)
   :email (:email entry)
   :display-name (:displayName entry)
   :photo-url (:photoUrl entry)
   :phone-number (:phoneNumber entry)})

(defn- convert-user-record
  "Map an Identity Toolkit account onto charmander's user map, key for key,
   plus the fields charmander's SDK-backed version never exposed.
   emailVerified and disabled are absent rather than false in the json, so
   they're coerced instead of passed straight through."
  [account]
  (when account
    {:email (:email account)
     :email-verified (boolean (:emailVerified account))
     :uid (:localId account)
     ;; the SDK's UserRecord.getProviderId() is the constant "firebase" on the
     ;; top-level record (the per-provider ids live under providerUserInfo), so
     ;; charmander only ever saw "firebase" here. kept as-is for parity, with
     ;; the real per-provider detail under :provider-data below.
     :provider-id "firebase"
     :photo-url (:photoUrl account)
     :phone-number (:phoneNumber account)
     :display-name (:displayName account)
     :disabled (boolean (:disabled account))
     :custom-claims (when-not (str/blank? (:customAttributes account))
                      (try (utils/decode (:customAttributes account)) (catch Exception _ nil)))
     :provider-data (mapv ->provider (:providerUserInfo account))
     ;; enrolled second factors. read-only pass-through: fire reports what
     ;; identity platform said and leaves the policy to the consumer.
     :mfa-info (mapv ->factor (:mfaInfo account))
     :created-at (->long (:createdAt account))
     :last-login-at (->long (:lastLoginAt account))
     ;; tokens issued before this are revoked. seconds, unlike the two above
     :valid-since (->long (:validSince account))
     :tenant-id (:tenantId account)}))

; reading users

(defn- raw-account
  "The account exactly as identity toolkit returned it. Round-tripping the raw
   shape matters for mfa: enrollments go back to the api in the form they
   arrived in, so nothing is lost in translation on the way through."
  [identifier-key identifier auth options]
  (let [res (request {:path "/accounts:lookup" :body {identifier-key [identifier]}} auth options)]
    (cond
      (:error res) res
      (first (:users res)) (first (:users res))
      ;; a well-formed lookup that simply matches nobody is an error here, not
      ;; an empty result, because that's what charmander's callers handle
      :else (err "USER_NOT_FOUND"))))

(defn- lookup [identifier-key identifier auth options]
  (let [account (raw-account identifier-key identifier auth options)]
    (if (:error account) account (convert-user-record account))))

(defn get-user
  "Retrieve a user by uid."
  ([uid auth] (get-user uid auth nil))
  ([uid auth options] (lookup :localId uid auth options)))

(defn get-user-by-email
  "Retrieve a user by email address."
  ([email auth] (get-user-by-email email auth nil))
  ([email auth options] (lookup :email email auth options)))

(defn get-user-by-phone-number
  "Retrieve a user by E.164 phone number."
  ([phone-number auth] (get-user-by-phone-number phone-number auth nil))
  ([phone-number auth options] (lookup :phoneNumber phone-number auth options)))

(defn get-users
  "Look up many users in one round trip. `identifiers` is a map with any of
   :uids, :emails and :phone-numbers. Returns a vector of the users that
   exist — unlike the single lookups, identifiers that match nobody are
   simply absent rather than an error."
  ([identifiers auth] (get-users identifiers auth nil))
  ([identifiers auth options]
   (let [body (cond-> {}
                (seq (:uids identifiers)) (assoc :localId (vec (:uids identifiers)))
                (seq (:emails identifiers)) (assoc :email (vec (:emails identifiers)))
                (seq (:phone-numbers identifiers)) (assoc :phoneNumber (vec (:phone-numbers identifiers))))]
     (if (empty? body)
       []
       (let [res (request {:path "/accounts:lookup" :body body} auth options)]
         (if (:error res) res (mapv convert-user-record (:users res))))))))

(defn list-users
  "One page of users, newest api-side ordering. Returns
   {:users [...] :next-page-token \"...\"}, where the token is nil on the last
   page. `options` takes :page-size (default and maximum 1000) and :page-token."
  ([auth] (list-users auth nil))
  ([auth options]
   (let [res (request {:method :get
                       :path "/accounts:batchGet"
                       :query-params (cond-> {:maxResults (min (or (:page-size options) 1000) 1000)}
                                       (:page-token options) (assoc :nextPageToken (:page-token options)))}
                      auth options)]
     (if (:error res)
       res
       {:users (mapv convert-user-record (:users res))
        :next-page-token (:nextPageToken res)}))))

(defn- page-seq
  "Lazily walk a token-paginated endpoint. `fetch` is called with a page token
   (nil for the first page) and returns a {:users [...] :next-page-token ...}
   map, or an error map. Pages are fetched only as the sequence is consumed,
   so (take 5 ...) costs one request rather than the whole project.

   A failing page throws. That's the deliberate exception to this namespace's
   never-throw rule: a lazy sequence has nowhere to put an error map, and the
   alternative — stopping quietly — makes a truncated list indistinguishable
   from a complete one. Enumeration is exactly where that gets someone hurt."
  [fetch token]
  (lazy-seq
    (let [page (fetch token)]
      (if (:error page)
        (throw (ex-info (str "Failed to list users: " (:error-data page)) page))
        (let [next-token (:next-page-token page)]
          (concat (:users page)
                  (when-not (str/blank? next-token)
                    (page-seq fetch next-token))))))))

(defn list-all-users
  "Every user in the project, as a lazy sequence. Pages are pulled in as you
   consume it, so this is safe on a project too big to hold in memory and
   cheap to walk only part of:

     (first (list-all-users auth))          ; one request
     (take 10 (list-all-users auth))        ; still one request

   Tune :page-size down if you expect to stop early and want smaller requests.
   Throws if a page fails — see page-seq for why that isn't an error map."
  ([auth] (list-all-users auth nil))
  ([auth options]
   (page-seq #(list-users auth (assoc options :page-token %)) nil)))

(defn search-users
  "Users matching a transducer. Composes over the lazy enumeration above, so a
   transducer that stops early stops the paging with it:

     ;; the first 5 staff, however many users the project has
     (into [] (search-users (comp (filter (comp :role :custom-claims))
                                  (take 5))
                            auth))

     ;; everyone who never signed in
     (into [] (search-users (remove :last-login-at) auth))

   Identity Toolkit has no query api, so this is a client-side scan: (take n)
   stops early, but a filter matching nothing walks every user in the project.
   Returns an eduction — reduce it, seq it, or pour it into a collection."
  ([xform auth] (search-users xform auth nil))
  ([xform auth options] (eduction xform (list-all-users auth options))))

; writing users

(defn- ->update-body
  "Translate a kebab-case field map into an accounts:update body. A key present
   with a nil value means clear it, which the api expresses as a
   deleteAttribute or deleteProvider entry rather than a null."
  [fields]
  (let [present? #(contains? fields %)
        v #(get fields %)]
    (cond-> {}
      (and (present? :email) (v :email)) (assoc :email (v :email))
      (and (present? :password) (v :password)) (assoc :password (v :password))
      (present? :email-verified) (assoc :emailVerified (boolean (v :email-verified)))
      (present? :disabled) (assoc :disableUser (boolean (v :disabled)))
      (and (present? :display-name) (v :display-name)) (assoc :displayName (v :display-name))
      (and (present? :photo-url) (v :photo-url)) (assoc :photoUrl (v :photo-url))
      (and (present? :phone-number) (v :phone-number)) (assoc :phoneNumber (v :phone-number))
      ;; custom claims travel as a json string, not as an object
      (present? :custom-claims) (assoc :customAttributes (utils/encode (or (v :custom-claims) {})))
      (present? :valid-since) (assoc :validSince (str (v :valid-since)))
      (present? :mfa-enrollments) (assoc :mfa {:enrollments (vec (v :mfa-enrollments))})
      (and (present? :display-name) (nil? (v :display-name))) (update :deleteAttribute (fnil conj []) "DISPLAY_NAME")
      (and (present? :photo-url) (nil? (v :photo-url))) (update :deleteAttribute (fnil conj []) "PHOTO_URL")
      (and (present? :phone-number) (nil? (v :phone-number))) (update :deleteProvider (fnil conj []) "phone")
      (seq (v :unlink-providers)) (update :deleteProvider (fnil into []) (v :unlink-providers)))))

(defn update-user
  "Update any combination of a user's fields in one call, returning the full,
   current record. `fields` is a kebab-case map understanding :email,
   :password, :email-verified, :disabled, :display-name, :photo-url,
   :phone-number, :custom-claims and :unlink-providers.

   A key present with a nil value clears that field — (update-user uid
   {:display-name nil} auth) removes the display name, where leaving the key
   out entirely would have left it alone.

   Every setter below is a thin wrapper over this."
  ([uid fields auth] (update-user uid fields auth nil))
  ([uid fields auth options]
   (let [problem (when (contains? fields :custom-claims) (claims-problem (:custom-claims fields)))]
     (cond
       (str/blank? uid) (err "INVALID_LOCAL_ID")
       problem (err problem)
       (and (:password fields) (short-password? (:password fields))) (err "WEAK_PASSWORD")
       :else
       (let [res (request {:path "/accounts:update"
                           :body (assoc (->update-body fields) :localId uid)}
                          auth options)]
         (if (:error res)
           res
           (get-user uid auth options)))))))

(defn create-user
  "Create a user with the given email and password, returning the new user's
   record. `options` may carry :uid to choose the user's id rather than let
   Firebase generate one, plus :display-name, :photo-url, :phone-number,
   :email-verified, :disabled and :custom-claims.

   Creating and then re-reading is the same two-step the Admin SDK does: the
   create endpoint answers with a stub, so the record you get back here is a
   real, complete one."
  ([email password auth] (create-user email password auth nil))
  ([email password auth options]
   (let [problem (or (when (contains? options :custom-claims) (claims-problem (:custom-claims options)))
                     (when (short-password? password) "WEAK_PASSWORD"))
         payload (cond-> {:email email
                          :password password
                          :emailVerified (boolean (:email-verified options))
                          :disabled (boolean (:disabled options))}
                   (:uid options) (assoc :localId (:uid options))
                   (:display-name options) (assoc :displayName (:display-name options))
                   (:photo-url options) (assoc :photoUrl (:photo-url options))
                   (:phone-number options) (assoc :phoneNumber (:phone-number options)))]
     (if problem
       (err problem)
       (let [res (request {:path "/accounts" :body payload} auth options)]
         (cond
           (:error res) res
           ;; custom claims aren't settable at signup, so they go on straight after
           (seq (:custom-claims options))
           (update-user (:localId res) {:custom-claims (:custom-claims options)} auth options)
           :else (get-user (:localId res) auth options)))))))

(defn set-user-email
  "Set a user's email address. As in charmander, this also resets
   email-verified to false — the new address hasn't been proven yet."
  ([uid email auth] (set-user-email uid email auth nil))
  ([uid email auth options]
   (update-user uid {:email email :email-verified false} auth options)))

(defn set-user-email-verified
  "Mark a user's email as verified, or unverified."
  ([uid verified? auth] (set-user-email-verified uid verified? auth nil))
  ([uid verified? auth options]
   (update-user uid {:email-verified (boolean verified?)} auth options)))

(defn set-user-password
  "Set a user's password."
  ([uid password auth] (set-user-password uid password auth nil))
  ([uid password auth options]
   (cond
     (str/blank? password) (err "MISSING_PASSWORD")
     (short-password? password) (err "WEAK_PASSWORD")
     :else (update-user uid {:password password} auth options))))

(defn set-user-phone-number
  "Set a user's phone number, which must be E.164 (a leading + and up to 15
   digits). Pass nil to unlink the phone number entirely."
  ([uid phone-number auth] (set-user-phone-number uid phone-number auth nil))
  ([uid phone-number auth options]
   (if (and (some? phone-number) (not (e164? phone-number)))
     (err "INVALID_PHONE_NUMBER")
     (update-user uid {:phone-number phone-number} auth options))))

(defn set-user-display-name
  "Set a user's display name. Pass nil to clear it."
  ([uid display-name auth] (set-user-display-name uid display-name auth nil))
  ([uid display-name auth options]
   (update-user uid {:display-name display-name} auth options)))

(defn set-user-photo-url
  "Set a user's photo url, which must be absolute, scheme and all. Pass nil to
   clear it."
  ([uid photo-url auth] (set-user-photo-url uid photo-url auth nil))
  ([uid photo-url auth options]
   (if (and (some? photo-url) (not (valid-url? photo-url)))
     (err "INVALID_PHOTO_URL")
     (update-user uid {:photo-url photo-url} auth options))))

(defn disable-user
  "Disable a user. They stay in the project but can no longer sign in, and
   their existing sessions stop verifying via this namespace's validate-token."
  ([uid auth] (disable-user uid auth nil))
  ([uid auth options] (update-user uid {:disabled true} auth options)))

(defn enable-user
  "Re-enable a disabled user."
  ([uid auth] (enable-user uid auth nil))
  ([uid auth options] (update-user uid {:disabled false} auth options)))

(defn set-custom-user-claims
  "Attach custom claims to a user. They ride along inside every ID token that
   user is subsequently issued, which is what makes them worth having: a
   consumer can authorize off a verified token without a database round trip.

   Pass nil or {} to clear them. Firebase refuses names that collide with a
   reserved claim, and the whole map must serialise to under 1000 bytes;
   both are checked here so the error says which rule was broken.

   Claims only reach a token when one is minted, so a user with a live token
   keeps their old claims until it refreshes — call revoke-refresh-tokens
   too if a change needs to bite immediately."
  ([uid claims auth] (set-custom-user-claims uid claims auth nil))
  ([uid claims auth options] (update-user uid {:custom-claims claims} auth options)))

(defn unlink-provider
  "Unlink a sign-in provider from a user — \"google.com\", \"password\",
   \"phone\", and so on. The account itself survives."
  ([uid provider-id auth] (unlink-provider uid provider-id auth nil))
  ([uid provider-id auth options]
   (if (str/blank? provider-id)
     (err "MISSING_PROVIDER_ID")
     (update-user uid {:unlink-providers [provider-id]} auth options))))

(defn revoke-refresh-tokens
  "Revoke every refresh token the user holds, so their sessions can't be
   renewed. Already-issued ID tokens stay cryptographically valid until they
   expire — up to an hour — which is why this namespace's validate-token
   exists: it checks revocation, where fire.auth's cannot."
  ([uid auth] (revoke-refresh-tokens uid auth nil))
  ([uid auth options] (update-user uid {:valid-since (utils/now)} auth options)))

; second factors
;
; enrollment is the client's job against firebase — fire can't enroll a factor
; and shouldn't try. What a server needs is the support-side half: seeing what
; a locked-out user has enrolled, and taking it off them so they can start over.

(defn list-user-factors
  "The second factors a user has enrolled, each as
   {:id ... :type :totp|:phone ... :display-name ... :phone-number ...
    :enrolled-at ...}. Empty when they have none, which is the common case
   outside Identity Platform."
  ([uid auth] (list-user-factors uid auth nil))
  ([uid auth options]
   (let [account (raw-account :localId uid auth options)]
     (if (:error account) account (mapv ->factor (:mfaInfo account))))))

(defn unenroll-user-factor
  "Take one enrolled second factor off a user, by the :id from
   list-user-factors. This is the locked-out-staffer path, and it is worth
   audit logging on the way past — fire doesn't log it for you."
  ([uid factor-id auth] (unenroll-user-factor uid factor-id auth nil))
  ([uid factor-id auth options]
   (let [account (raw-account :localId uid auth options)]
     (cond
       (:error account) account
       (str/blank? factor-id) (err "MISSING_MFA_ENROLLMENT_ID")
       (not-any? #(= factor-id (:mfaEnrollmentId %)) (:mfaInfo account)) (err "MFA_ENROLLMENT_NOT_FOUND")
       :else (update-user uid
                          {:mfa-enrollments (vec (remove #(= factor-id (:mfaEnrollmentId %))
                                                         (:mfaInfo account)))}
                          auth options)))))

(defn unenroll-all-user-factors
  "Take every second factor off a user at once."
  ([uid auth] (unenroll-all-user-factors uid auth nil))
  ([uid auth options] (update-user uid {:mfa-enrollments []} auth options)))

; deleting users

(defn delete-user
  "Delete a user by uid. Returns nil on success, matching charmander, whose
   delete-user returned the SDK's void."
  ([uid auth] (delete-user uid auth nil))
  ([uid auth options]
   (if (str/blank? uid)
     (err "INVALID_LOCAL_ID")
     (let [res (request {:path "/accounts:delete" :body {:localId uid}} auth options)]
       (when (:error res) res)))))

(defn delete-users
  "Delete up to 1000 users in one call. Returns nil when they all went, or an
   error map whose :failures lists the ones that didn't and why — a batch
   delete can partly succeed, so this can't just be nil or an error."
  ([uids auth] (delete-users uids auth nil))
  ([uids auth options]
   (let [uids (vec (remove str/blank? uids))]
     (cond
       (empty? uids) (err "MISSING_LOCAL_ID")
       (> (count uids) 1000) (err "TOO_MANY_LOCAL_IDS")
       :else
       (let [res (request {:path "/accounts:batchDelete" :body {:localIds uids :force true}} auth options)]
         (cond
           (:error res) res
           (seq (:errors res)) (assoc (err "PARTIAL_FAILURE") :failures (vec (:errors res)))
           :else nil))))))

; email action links
;
; :returnOobLink is what makes these the admin flavour — the link comes back
; to you instead of firebase emailing it, so you can send it yourself.

(defn- oob-link [request-type email auth options extra]
  (let [res (request {:path "/accounts:sendOobCode"
                      :body (cond-> (merge {:requestType request-type :email email :returnOobLink true}
                                           extra)
                              (:continue-url options) (assoc :continueUrl (:continue-url options)))}
                     auth options)]
    (if (:error res)
      res
      (or (:oobLink res) (err "MISSING_OOB_LINK")))))

(defn generate-password-reset-link
  "Generate a password reset link for an email address. `options` may carry
   :continue-url to send the user somewhere specific once they're done."
  ([email auth] (generate-password-reset-link email auth nil))
  ([email auth options] (oob-link "PASSWORD_RESET" email auth options nil)))

(defn generate-email-verification-link
  "Generate an email verification link for an email address. `options` may
   carry :continue-url."
  ([email auth] (generate-email-verification-link email auth nil))
  ([email auth options] (oob-link "VERIFY_EMAIL" email auth options nil)))

(defn generate-sign-in-with-email-link
  "Generate a passwordless sign-in link. Unlike the other two, the
   continue-url is required — it's where the link lands, and firebase refuses
   the request without it."
  ([email continue-url auth] (generate-sign-in-with-email-link email continue-url auth nil))
  ([email continue-url auth options]
   (if (str/blank? continue-url)
     (err "MISSING_CONTINUE_URI")
     (oob-link "EMAIL_SIGNIN" email auth (assoc options :continue-url continue-url) nil))))

(defn generate-verify-and-change-email-link
  "Generate a link that verifies `new-email` and moves the account onto it in
   one step, rather than changing the address first and verifying after."
  ([email new-email auth] (generate-verify-and-change-email-link email new-email auth nil))
  ([email new-email auth options]
   (if (str/blank? new-email)
     (err "MISSING_NEW_EMAIL")
     (oob-link "VERIFY_AND_CHANGE_EMAIL" email auth options {:newEmail new-email}))))

; project configuration
;
; Turning MFA on is two nested switches, which is the part that catches people
; out: a project-level state, and a per-provider state underneath it. TOTP can
; sit there ENABLED while the parent is DISABLED, in which case nothing works
; and nothing says why.

(defn- ->mfa-config
  "The MFA half of a project config, in fire's shape."
  [mfa]
  (let [totp (first (filter :totpProviderConfig (:providerConfigs mfa)))]
    {:state (some-> (:state mfa) str/lower-case keyword)
     :totp {:state (some-> (:state totp) str/lower-case keyword)
            ;; how many 30s windows either side of now a code is accepted.
            ;; google defaults to 5, which is a forgiving +/- 2.5 minutes
            :adjacent-intervals (-> totp :totpProviderConfig :adjacentIntervals)}}))

(defn- ->project-config [config]
  (let [sign-in (:signIn config)]
    {:mfa (->mfa-config (:mfa config))
     :authorized-domains (vec (:authorizedDomains config))
     ;; deliberately partial: the raw config also carries
     ;; signIn.hashConfig.signerKey, the password hashing secret. fire has no
     ;; use for it and it should not be sitting in a log or a repl history.
     :sign-in {:email (boolean (-> sign-in :email :enabled))
               :phone-number (boolean (-> sign-in :phoneNumber :enabled))
               :anonymous (boolean (-> sign-in :anonymous :enabled))
               :allow-duplicate-emails (boolean (:allowDuplicateEmails sign-in))}}))

(defn get-project-config
  "The project's authentication configuration — which sign-in methods are on,
   which domains are authorised, and the MFA settings.

   The password hashing secret in the raw response is left out; everything
   else is passed through."
  ([auth] (get-project-config auth nil))
  ([auth options]
   (let [res (request {:method :get :root admin-root :path "/config"} auth options)]
     (if (:error res) res (->project-config res)))))

(defn get-mfa-config
  "Just the MFA half of get-project-config:
   {:state :disabled|:enabled|:mandatory
    :totp {:state ... :adjacent-intervals n}}"
  ([auth] (get-mfa-config auth nil))
  ([auth options]
   (let [res (get-project-config auth options)]
     (if (:error res) res (:mfa res)))))

(defn- ->mfa-update
  "The request body and its update mask, built from only the keys given. The
   mask is what keeps a write from naming — and so from clobbering — a field
   the caller never mentioned, which matters here because the surrounding
   config carries the password hashing secret."
  [{:keys [state totp]}]
  {:body (cond-> {}
           state (assoc :state (-> state name str/upper-case))
           totp (assoc :providerConfigs
                       [(cond-> {:state (-> totp :state name str/upper-case)}
                          (:adjacent-intervals totp)
                          (assoc :totpProviderConfig
                                 {:adjacentIntervals (:adjacent-intervals totp)}))]))
   :mask (str/join "," (cond-> []
                         state (conj "mfa.state")
                         totp (conj "mfa.providerConfigs")))})

(defn set-mfa-config
  "Set the project's MFA configuration. `config` takes :state and :totp, and
   whichever you leave out is left alone — the update mask is built from the
   keys you actually pass, so this never round-trips the rest of the project
   config and can't clobber a setting you didn't mention.

     (set-mfa-config {:state :enabled} auth)
     (set-mfa-config {:totp {:state :enabled :adjacent-intervals 3}} auth)

   :state is the parent switch and the one people miss:
     :disabled  — nobody can enrol; second factor claims are always nil
     :enabled   — users MAY enrol; anyone who hasn't signs in exactly as before
     :mandatory — users MUST enrol; everyone without a factor is locked out

   :mandatory locks out every user who has not already enrolled, so reach for
   :enabled and let your own gate decide who has to have it. Enrolment has to
   lead enforcement, not follow it."
  ([config auth] (set-mfa-config config auth nil))
  ([config auth options]
   (let [{:keys [state totp]} config
         totp-state (:state totp)]
     (cond
       (and (nil? state) (nil? totp)) (err "NOTHING_TO_UPDATE")
       (and state (not (mfa-states state))) (err (str "INVALID_MFA_STATE: " state))
       ;; :mandatory is a project-level notion; a provider is simply on or off
       (and totp (not (#{:enabled :disabled} totp-state))) (err (str "INVALID_PROVIDER_STATE: " totp-state))
       :else
       (let [{:keys [body mask]} (->mfa-update config)
             res (request {:method :patch
                           :root admin-root
                           :path "/config"
                           :query-params {:updateMask mask}
                           :body {:mfa body}}
                          auth options)]
         (if (:error res) res (->project-config res)))))))

(defn enable-totp-mfa
  "Turn on TOTP second factors in one call: the project-level switch and the
   TOTP provider together, which is the pair that has to be set for anything
   to work.

   Uses :enabled rather than :mandatory, so nobody's sign-in changes until
   they choose to enrol. `options` may carry :adjacent-intervals."
  ([auth] (enable-totp-mfa auth nil))
  ([auth options]
   (set-mfa-config {:state :enabled
                    :totp (cond-> {:state :enabled}
                            (:adjacent-intervals options)
                            (assoc :adjacent-intervals (:adjacent-intervals options)))}
                   auth options)))

(defn disable-mfa
  "Turn multi-factor authentication off at the project level. Enrolled factors
   are not deleted — flipping it back to :enabled restores them."
  ([auth] (disable-mfa auth nil))
  ([auth options] (set-mfa-config {:state :disabled} auth options)))

; minting and checking credentials

(defn create-custom-token
  "Mint a custom token for `uid` — a short-lived JWT your client exchanges for
   a real session via firebase's signInWithCustomToken. This is how you let
   your own system decide who someone is (an SSO bridge, an internal user
   table, an impersonation tool) and still hand them a genuine firebase
   session.

   Signed locally with the service account's private key, so it costs no api
   call. `options` may carry :claims, which land in the resulting ID token's
   custom claims, and :tenant-id.

   Returns the token string, or an error map."
  ([uid auth] (create-custom-token uid auth nil))
  ([uid auth options]
   (let [problem (when (seq (:claims options)) (claims-problem (:claims options)))]
     (cond
       (str/blank? uid) (err "INVALID_LOCAL_ID")
       (> (count uid) 128) (err "INVALID_LOCAL_ID")
       problem (err problem)
       :else
       (try
         (let [creds (oauth2/credentials (:env auth))
               now (utils/now)]
           (if-not (:private_key creds)
             (err "MISSING_CREDENTIALS")
             (let [claims (cond-> {:iss (:client_email creds)
                                   :sub (:client_email creds)
                                   :aud custom-token-audience
                                   :iat now
                                   ;; firebase caps custom tokens at an hour
                                   :exp (+ now (min (or (:expires-in options) 3600) 3600))
                                   :uid uid}
                            (seq (:claims options)) (assoc :claims (:claims options))
                            (:tenant-id options) (assoc :tenant_id (:tenant-id options)))]
               (oauth2/sign claims
                            (oauth2/str->private-key (:private_key creds))
                            {:alg "RS256" :typ "JWT"}))))
         (catch Exception e (err (.getMessage e))))))))

(defn create-session-cookie
  "Exchange a freshly-minted ID token for a session cookie — a long-lived
   credential you can put in an httpOnly cookie, so a server-rendered app
   doesn't have to keep a refreshing ID token in reach of javascript.

   :valid-duration is in seconds, between 5 minutes and 14 days, defaulting
   to 5 days. Verify what comes back with fire.auth/validate-session-cookie,
   or this namespace's validate-session-cookie to also check revocation.

   Returns the cookie string, or an error map."
  ([id-token auth] (create-session-cookie id-token auth nil))
  ([id-token auth options]
   (let [duration (or (:valid-duration options) 432000)]
     (cond
       (str/blank? id-token) (err "MISSING_ID_TOKEN")
       (not (<= 300 duration 1209600)) (err "INVALID_DURATION")
       :else
       (let [res (request {:path ":createSessionCookie"
                           :body {:idToken id-token :validDuration (str duration)}}
                          auth options)]
         (if (:error res)
           res
           (or (:sessionCookie res) (err "MISSING_SESSION_COOKIE"))))))))

(defn- still-good?
  "Whether a verified token's subject is still allowed to use it: the account
   exists, isn't disabled, and hasn't had its refresh tokens revoked since the
   token was issued."
  [claims auth options]
  (let [account (raw-account :localId (:uid claims) auth options)]
    (and (not (:error account))
         (not (:disabled account))
         (>= (or (:iat claims) 0) (or (->long (:validSince account)) 0)))))

(defn validate-token
  "fire.auth/validate-token, plus the two checks that need admin credentials:
   that the account isn't disabled, and that its refresh tokens haven't been
   revoked since this token was issued. Returns the same claims map, or nil.

   Signature and expiry alone can't tell you either of those — an ID token
   stays cryptographically valid for up to an hour after you revoke the
   session behind it. Use this where that hour matters, and fire.auth's
   cheaper, credential-free version where it doesn't.

   Fails closed like everything else here: a lookup that errors is a nil, not
   a pass."
  ([project-id token auth] (validate-token project-id token auth nil))
  ([project-id token auth options]
   (let [claims (fire-auth/validate-token project-id token)]
     (when (and claims (still-good? claims auth options))
       claims))))

(defn validate-session-cookie
  "fire.auth/validate-session-cookie with the same revocation and
   disabled-account checks validate-token adds."
  ([project-id cookie auth] (validate-session-cookie project-id cookie auth nil))
  ([project-id cookie auth options]
   (let [claims (fire-auth/validate-session-cookie project-id cookie)]
     (when (and claims (still-good? claims auth options))
       claims))))
