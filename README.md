# fire

A lightweight clojure client for Firebase based on the REST API. Basically [Charmander](https://github.com/alekcz/charmander) 2.0  

# Status

![master](https://github.com/alekcz/fire/workflows/master/badge.svg) [![codecov](https://codecov.io/gh/alekcz/fire/branch/master/graph/badge.svg?token=ahELyNhNVg)](https://codecov.io/gh/alekcz/fire) [![Dependencies Status](https://versions.deps.co/alekcz/fire/status.svg)](https://versions.deps.co/alekcz/fire) [![Clojars Project](https://img.shields.io/clojars/v/alekcz/fire.svg)](https://clojars.org/alekcz/fire)      

## Prerequisites

For fire you will need to create a Realtime Database on Firebase and retrieve the service account credentials.

1. Get the json file containing your service account creditials by following the instruction here https://cloud.google.com/docs/authentication/getting-started
2. Copy the contents of your .json into the `GOOGLE_APPLICATION_CREDENTIALS` environment variable. In your `~/.bash_profile` and in Travis CI you should escape your credentials using singe quotes (').

## Usage

`[alekcz/fire "0.7.0"]`

### Interacting with Realtime Database

Creating your auth token

```clojure
(require  '[fire.core :as fire]
          '[fire.auth :as auth])
(def auth (auth/create-token "GOOGLE_APPLICATION_CREDENTIALS"))
```

Write to the specified location (will overwrite any existing data):

```clojure
    (fire/write! "protected-db-name" "/path" {:map "with data"} auth)
    (fire/write! "public-db-name" "/path" {:map "with data"} nil)
    ; => {:map "with data"}
```

Read data from the specified location:

```clojure
    (fire/read "protected-db-name" "/path" auth)
    (fire/read "public-db-name" "/path" nil)
    ; => {:map "with data"}
```
 
 Update data at the specified location (only updates the specified fields):
 
```clojure
     (fire/update! "protected-db-name" "/path" {:more "data"} auth)
     (fire/update! "public-db-name" "/path" {:more "data"} nil)
     ; => {:map "with data" :more "data"}
```
 
Add data at the specified location with an automatically generated key:

```clojure
     (fire/push! "protected-db-name" "/path" {:map "with data"} auth)
     (fire/push! "public-db-name" "/path" {:map "with data"} nil)
     ; => {"name" "-IoZ3DZlTTQIkR0c7iVK"}
```
      
Delete at the specified locations:

```clojure
    (fire/delete! "protected-db-name" "/path" auth)
    (fire/delete! "public-db-name" "/path" nil)
    ; => nil
```

Query data at the specified locations:
Note that if the child key is not indexed firebase will respond with error 400. Also `:orderBy` is required for all queries. 
See the Firebase [query docs](https://firebase.google.com/docs/database/rest/retrieve-data#section-rest-filtering) for more info.
```clojure
    (fire/read "protected-db-name" "/path" auth {:query {:orderBy "child-key" :startAt 10 :endAt 50}})
    (fire/read "protected-db-name" "/path" auth {:query {:orderBy "child-key" :equalTo 10}})
    (fire/read "public-db-name" "/path" nil {:query {:orderBy "child-key" :limitToFirst 10}})
    (fire/read "public-db-name" "/path" nil {:query {:orderBy "child-key" :limitToLast 3}})
    
    ; => nil
```

### Interacting with Firebase Storage

Creating your auth token

```clojure
(require  '[fire.storage :as storage]
          '[fire.auth :as auth])
(def auth (auth/create-token "GOOGLE_APPLICATION_CREDENTIALS"))
```

Upload data or a file to Firebase Storage

```clojure
(spit "path/on/firebase.txt" "this is fire")
(storage/upload! "path/on/firebase.txt" "path/on/disk/storage.txt" "text/plain" auth)
(storage/upload! "path/on/firebase.txt" non-string-data-in-memory  "text/plain" auth)
```
 
Download data to memory or a file from Firebase Storage
 
```clojure
(store/download "path/on/firebase.txt" auth) ;=> "this is fire"
(store/download-to-file "path/on/firebase.txt" "downloads/storage.txt" auth)
(slurp "downloads/storage.txt") ;=> "this is fire"

```
 
Add data at the specified location with an automatically generated key:

```clojure
(store/delete! "path/on/firebase.txt" auth) 
```


### Verifying Firebase ID tokens

The other direction to `auth/create-token`: this checks a token somebody
*else* already holds — a signed-in user's Firebase ID token — against Google's
public certs. No service account and no Admin SDK needed.

```clojure
(require '[fire.auth :as auth])

(auth/validate-token "your-project-id" id-token)
```

It returns the token's verified claims, or `nil` on any failure at all — bad
signature, expired, wrong project, malformed input. It fails closed and never
throws, because every failure here means the same thing to a caller: reject.

The sign-in metadata Firebase nests inside the token's `:firebase` block is
lifted to the top level of the result, alongside the raw claims:

| Key | Meaning |
| --- | --- |
| `:projectid` | the project the token was issued for (`aud`) |
| `:uid` | the user's id (`user_id`, falling back to `sub`) |
| `:email` | the user's email, if any |
| `:email_verified` | whether that email has been verified |
| `:sign_in_provider` | how the user signed in — `"password"`, `"google.com"`, ... |
| `:sign_in_second_factor` | the second factor presented at sign-in — `"totp"`, `"phone"`, or `nil` |
| `:second_factor_identifier` | the enrollment id of that factor, for audit trails |
| `:exp` | expiry |
| `:auth_time` | when the user actually authenticated |

The two second factor keys require Identity Platform (Firebase Auth's upgraded
tier) to ever be non-nil — on the legacy tier Firebase never emits them.

`:sign_in_second_factor` is the whole reason to look at any of this: it is the
only server-side proof that a second factor was actually presented. A valid
signature is not. Fire reports, it doesn't decide — `nil` means "no second
factor proven", and an enforcing consumer must treat that as a deny:

```clojure
(defn auth-staff [token]
  (when-let [res (auth/validate-token "your-project-id" token)]
    (assoc res :second-factor (:sign_in_second_factor res))))

;; your login handler then rejects staff tokens whose :second-factor is nil
```

Nothing is dropped or renamed, so the raw claims are all still on the result
too — `(-> res :firebase :sign_in_second_factor)` works just as well, and
anything already destructuring the return value keeps working.

**Session cookies** verify the same way. They're the long-lived, httpOnly-cookie
alternative to keeping a refreshing ID token in reach of javascript — mint one
with `admin/create-session-cookie`, then:

```clojure
(auth/validate-session-cookie "your-project-id" cookie)
```

Same return value, different key set and issuer under the hood. The two kinds
don't verify as each other.

Neither of these can see a *revoked* session: an ID token stays
cryptographically valid for up to an hour after you revoke the refresh tokens
behind it. Where that hour matters, use `fire.admin`'s versions, which cost a
lookup and check revocation and disabled accounts too:

```clojure
(require '[fire.admin :as admin])
(admin/validate-token "your-project-id" id-token auth)
(admin/validate-session-cookie "your-project-id" cookie auth)
```

### Admin user management

The user management half of [charmander](https://github.com/alekcz/charmander)
and then some, without charmander's Admin SDK dependency or its `init`
singleton — fire talks to the Identity Toolkit REST API the SDK itself wraps,
so you pass the same `auth` map you pass everywhere else in fire.

```clojure
(require '[fire.admin :as admin]
         '[fire.auth :as auth])
(def auth (auth/create-token "GOOGLE_APPLICATION_CREDENTIALS"))
```

Every function returns `{:error true :error-data "MESSAGE"}` on failure —
charmander's contract, kept deliberately so a port is close to a namespace
rename. Every function also takes an optional trailing options map, which
understands `:project-id` and `:tenant-id`.

#### Creating and reading

```clojure
(admin/create-user "person@domain.com" "superDuperSecure" auth)
; => {:uid "MjM0NTY3..." :email "person@domain.com" :email-verified false
;     :provider-id "firebase" :photo-url nil :phone-number nil
;     :display-name nil :disabled false :custom-claims nil :provider-data [...]
;     :mfa-info [] :created-at 1755000000000 :last-login-at nil
;     :valid-since nil :tenant-id nil}

;; the whole profile can go in at creation
(admin/create-user "person@domain.com" "superDuperSecure" auth
                   {:uid "my-own-id" :display-name "Charmander"
                    :email-verified true :custom-claims {:role "staff"}})
```

```clojure
(admin/get-user "MjM0NTY3..." auth)
(admin/get-user-by-email "person@domain.com" auth)
(admin/get-user-by-phone-number "+27123456789" auth)

;; many at once — identifiers that match nobody are simply absent
(admin/get-users {:uids ["a" "b"] :emails ["person@domain.com"]} auth)

;; and everybody, a page at a time or all in one go
(admin/list-users auth {:page-size 100})
; => {:users [...] :next-page-token "..."}
(admin/list-all-users auth)
; => [...]
```

#### Updating

`update-user` takes any combination of fields in one call. A key present with a
`nil` value **clears** that field; a key left out entirely leaves it alone.

```clojure
(admin/update-user "MjM0NTY3..."
                   {:display-name "Charmander"
                    :photo-url nil                ; clears it
                    :email-verified true
                    :custom-claims {:role "staff"}}
                   auth)
```

The single-field setters are thin wrappers over it, and each returns the full,
current record:

```clojure
(admin/set-user-email "MjM0NTY3..." "new@domain.com" auth)  ; also resets email-verified
(admin/set-user-email-verified "MjM0NTY3..." true auth)
(admin/set-user-password "MjM0NTY3..." "evenMoreSecure" auth)
(admin/set-user-phone-number "MjM0NTY3..." "+27123456789" auth)   ; nil unlinks it
(admin/set-user-display-name "MjM0NTY3..." "Charmander" auth)     ; nil clears it
(admin/set-user-photo-url "MjM0NTY3..." "https://domain.com/pic.jpg" auth)
(admin/disable-user "MjM0NTY3..." auth)
(admin/enable-user "MjM0NTY3..." auth)
(admin/unlink-provider "MjM0NTY3..." "google.com" auth)
```

#### Custom claims

Custom claims ride along inside every ID token the user is subsequently issued,
which is what makes them worth having: a consumer can authorize off a verified
token without a database round trip.

```clojure
(admin/set-custom-user-claims "MjM0NTY3..." {:role "staff" :tier 3} auth)
(admin/set-custom-user-claims "MjM0NTY3..." nil auth)   ; clear
```

Firebase refuses names that collide with a reserved claim (`sub`, `iss`, `exp`,
`firebase`, ...) and caps the serialized map at 1000 bytes; fire checks both
before the call so the error says which rule was broken.

Claims only reach a token when one is minted, so a user holding a live token
keeps their old claims until it refreshes. If a change needs to bite
immediately, revoke as well.

#### Revoking

```clojure
(admin/revoke-refresh-tokens "MjM0NTY3..." auth)
```

This stamps `:valid-since` forward, so the user's sessions can't be renewed.
Already-issued ID tokens stay cryptographically valid until they expire — which
is why `admin/validate-token` exists, and why `auth/validate-token` alone can't
tell you about a revoked session.

#### Second factors

Enrollment is the client's job against Firebase — fire can't enroll a factor and
doesn't try. What a server needs is the support-side half: seeing what a locked
out user has enrolled, and taking it off them so they can start over.

```clojure
(admin/list-user-factors "MjM0NTY3..." auth)
; => [{:id "enrollment-1" :type :totp :display-name "Authenticator"
;      :phone-number nil :enrolled-at "2026-08-20T09:00:00Z"}]

(admin/unenroll-user-factor "MjM0NTY3..." "enrollment-1" auth)
(admin/unenroll-all-user-factors "MjM0NTY3..." auth)
```

Unenrollment is security-sensitive and worth audit logging on the way past —
fire doesn't log it for you. All of this needs Identity Platform; on the legacy
Firebase Auth tier `:mfa-info` is simply always empty.

#### Custom tokens and session cookies

A custom token is how you let your own system decide who somebody is — an SSO
bridge, an internal user table, an impersonation tool — and still hand them a
genuine Firebase session, which they get by passing it to
`signInWithCustomToken`. It's signed locally with your service account key, so
it costs no API call.

```clojure
(admin/create-custom-token "MjM0NTY3..." auth)
(admin/create-custom-token "MjM0NTY3..." auth {:claims {:role "staff"}})
; => "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."
```

A session cookie goes the other way: exchange a fresh ID token for a long-lived
credential you can put in an httpOnly cookie.

```clojure
(admin/create-session-cookie id-token auth)
(admin/create-session-cookie id-token auth {:valid-duration 604800})  ; seconds, 5min–14d
```

#### Email action links

Generated rather than sent, so you can deliver them yourself.

```clojure
(admin/generate-password-reset-link "person@domain.com" auth)
(admin/generate-email-verification-link "person@domain.com" auth)
(admin/generate-sign-in-with-email-link "person@domain.com" "https://domain.com/done" auth)
(admin/generate-verify-and-change-email-link "person@domain.com" "new@domain.com" auth)
; => "https://your-project.firebaseapp.com/__/auth/action?mode=..."

;; the first two take :continue-url in options to land the user somewhere specific
(admin/generate-password-reset-link "person@domain.com" auth {:continue-url "https://domain.com/done"})
```

#### Deleting

```clojure
(admin/delete-user "MjM0NTY3..." auth)
; => nil

;; up to 1000 at a time. a batch can partly succeed, so failures come back listed
(admin/delete-users ["a" "b" "c"] auth)
; => nil, or {:error true :error-data "PARTIAL_FAILURE" :failures [...]}
```

## Thanks 
Special thanks to: 
- [@sgrove](https://github.com/sgrove)

## License

Copyright © 2020 Alexander Oloo

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
