# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.7.0] - 2026-09-21

Released as 0.7.0-RC4 and run in production before being cut. This section
covers what changed since RC3; 0.7.0 also carries everything under RC1, RC2
and RC3 below, which is where `fire.admin`, the second-factor claims and the
project MFA configuration arrived.

### Added
- `fire.utils/*http-fn*`, a dynamic var that is the one seam between fire and
  the network. Every outbound request — the OAuth exchange, Google's public
  certs, the realtime database, storage, vision and the identity toolkit —
  goes through `fire.utils/http!`, which calls the bound function instead of
  http-kit when there is one. Bind a responder and nothing leaves the process,
  so a consumer's Firebase-touching tests can run in parallel with no shared
  project to collide on. A dynamic var rather than something to `with-redefs`
  because fire compiles with direct linking, under which a redefined `defn` is
  never seen by its callers; `binding` is also thread-local, so one test's
  responder reaches no other test's requests, and is conveyed to the futures
  that test starts, so its own still do. `test/fire/seam_test.clj` drives
  every request path through it and is the model for a consumer's own tests.
- `fire.auth/token-for`, the access token cache. An auth map is immutable, so
  a request that found its token expired had nowhere to keep the replacement
  it minted: once an hour had passed, EVERY call re-minted — an RSA signature
  and a round trip to Google's token endpoint — and threw the result away. The
  request paths in `fire.admin`, `fire.core` and `fire.storage` now go through
  `token-for`, which keeps the replacement keyed by the env var the credentials
  came from. `fire.auth/forget-token!` drops an entry, for credentials rotated
  while the process runs.
- `fire.oauth2/signing-key`: the service account's private key is derived from
  the credentials once per env var, where `create-custom-token` used to
  re-read and re-parse the json and re-derive the RSA key on every call.
- `fire.admin/get-mfa-config` reports the SMS second factor's state under
  `:sms`, read-only. SMS is configured through `mfa.enabledProviders`, a
  different field from the `mfa.providerConfigs` a TOTP write names, which is
  why a TOTP write cannot touch it — now visible rather than a thing to know.
- CI runs the offline tier on Java 8, 11, 17, 21 and 25 against Clojure 1.11
  and 1.12. Two release candidates loaded on the one JDK and Clojure CI had
  and on nothing newer, because CI only ever had the one; Java 8 is on the
  list so that the floor stays where the README says it is.
- `bb.edn` holds the build workflows — `bb test`, `bb test:matrix`,
  `bb test:all`, `bb native`, `bb jar`, `bb sign-check`, `bb release` — so
  that what a contributor runs and what a release runs cannot drift apart.
  `bb release` reads the built jar back and refuses to deploy one carrying
  `.class` files, checks the clojars credentials and proves gpg can sign
  before spending time on a build, and refuses a dirty tree. `lein publish`
  is a shim onto it.
- `bb graal-check` (and `bb check`, which runs the release gates together):
  scans every var root reachable from `fire.graal` for a `Random`, which
  native-image bakes into the image heap and then refuses. It is reached
  through the namespace graph, so one in any dependency counts — and one in
  a dependency is how it was found. Two release candidates loaded on the one JDK and Clojure CI had and on
  nothing newer, because CI only ever had the one.

### Changed
- Dependencies brought to their latest stable releases: cheshire 5.13.0 → 6.2.0
  (Jackson 2.17.0 → 2.21.1; its one breaking change is Windows line endings
  in pretty-printing, which fire does not use), core.async 1.6.681 → 1.8.741,
  and the lein-cloverage and lein-eftest plugins. clj-uuid stays at 0.1.9:
  0.2.5 holds its SecureRandom in a bare defonce, which fails the native
  image build and would fail it for consumers building one too. All still run on
  Java 8. nippy moves to 3.9.0 in the dev profile. What a consumer gets carries no
  version conflicts; the one left in the tree is dev-only (malli 0.8.0 and
  eftest disagreeing on fipp) and stays, as do malli, claypoole and
  criterium themselves: they exist for the live tests and nothing about them
  is a consumer's concern.
- http-kit 2.7.0 → 2.8.1, the latest stable. Client-side that is 2.8.0's fix
  for the handling of some bad SSL certificates (#535) and 2.8.1's backport of
  a performance regression fix (#568); minimum Java moves from 7 to 8, which
  fire already required. The `sni-client` namespace fire uses and the
  `ClientSslEngineFactory$SSLHolder` class the native image names are both
  unchanged. 2.9 is still in beta and rewrites the client's TLS and body
  handling; it is left for a release of its own.
- `fire.auth/create-token` says why when it cannot mint a token. It used to
  answer a bare `{:env ...}` — no token, no error — and the first sign
  anything was wrong was a 401 several calls later, wearing the same error
  shape as a user who does not exist. It now carries `{:error true
  :error-data ...}` beside `:env`, one of `MISSING_CREDENTIALS` (the env var
  is unset or empty), `INVALID_CREDENTIALS` (set, but not a service account's
  json — previously a throw from the json parser) or
  `TOKEN_EXCHANGE_FAILED: ...` (Google refused the assertion or could not be
  reached). Purely additive for anyone reading `:token` or `:project-id`
  off the result, which are nil in both the old and the new shape.
- `fire.admin` refuses an auth map that yields no token with
  `MISSING_CREDENTIALS` before building a request, instead of sending one with
  no `Authorization` header. Nothing on the identity toolkit is callable
  anonymously, so that request could only ever have been a 401. `fire.core`
  and `fire.storage` still send a nil auth bare, because a public database or
  bucket is a real thing there.
- `set-mfa-config`'s docstring now says that a `:totp` write replaces
  `mfa.providerConfigs` wholesale rather than merging into it — safe today,
  because TOTP is the only provider Identity Platform configures through that
  field, but a replace and not the merge the old wording implied.

### Removed
- gniazdo, and with it the seven Jetty 9.4 jars it put on every consumer's
  classpath — Jetty 9.4 has been end of life since 2022, and nothing but
  `fire.socket` ever opened a websocket. `fire.socket` now runs on
  `fire.ws`, a small RFC 6455 client on Java 8's own sockets — a TLS socket,
  the HTTP upgrade, and the frame format — and behaves as before. Fire still
  runs on Java 8, and CI now runs the offline tier there so that it stays so.
- `totp.sh` and a stray `totp.sh.bak` from the repository root. The script
  lives on as `scripts/enable-totp-mfa.sh`, the gcloud-only fallback for a
  project with no service account yet; `fire.admin/enable-totp-mfa` does the
  same job from Clojure.

## [0.7.0-RC3] - 2026-08-21
### Fixed
- The published jar is source-only again, 3.3MB down to 40KB. `lein jar`
  packages whatever is in `target/classes`, so a build run after an `uberjar`
  or native-image build swept AOT-compiled copies of Clojure itself, cheshire,
  gniazdo, environ and clj_uuid into the artifact — 2758 classes, where the
  library is ten files of source. Those classes also carried direct-linking,
  which would have stopped consumers redefining or mocking any fire function.
  `:main` is now `^:skip-aot`, AOT is confined to the `:uberjar` profile the
  native image needs, and a `lein publish` alias cleans first so a stale
  `target/` cannot leak into a release again.

  **Both 0.7.0-RC1 and 0.7.0-RC2 on Clojars carry this problem — use RC3.**

## [0.7.0-RC2] - 2026-08-21
### Added
- Project configuration in `fire.admin`: `get-project-config`,
  `get-mfa-config`, `set-mfa-config`, `enable-totp-mfa` and `disable-mfa`.
  Turning MFA on is two nested switches — a project-level state and a
  per-provider one underneath — and setting only the inner one is a silent
  no-op, so `enable-totp-mfa` sets both. Writes use an update mask built from
  the keys passed, so they never round-trip the rest of the project config.
  Served from the admin/v2 path on `identitytoolkit.googleapis.com`, which
  needs no additional API enabled and is already covered by fire's scopes.
  `get-project-config` omits `signIn.hashConfig.signerKey`.

## [0.7.0-RC1] - 2026-08-20
### Added
- `fire.admin` — user management against the Identity Toolkit REST API. No
  Admin SDK dependency and no `init` singleton: the `auth` map is passed in
  like everywhere else in fire, and every function takes an optional trailing
  options map understanding `:project-id` and `:tenant-id`. Charmander's
  `{:error true :error-data ...}` contract is kept as-is.
  - Charmander parity: `create-user`, `get-user`, `get-user-by-email`,
    `get-user-by-phone-number`, `set-user-email`, `set-user-password`,
    `set-user-phone-number`, `set-user-display-name`, `set-user-photo-url`,
    `generate-password-reset-link`, `generate-email-verification-link`,
    `delete-user`.
  - Beyond it: `update-user` (many fields at once, nil clears a field),
    `get-users`, `list-users`, `list-all-users` (lazy — pages are fetched as
    the sequence is consumed), `search-users` (a transducer over that
    enumeration), `set-user-email-verified`,
    `disable-user`, `enable-user`, `set-custom-user-claims`,
    `unlink-provider`, `revoke-refresh-tokens`, `delete-users`,
    `generate-sign-in-with-email-link`,
    `generate-verify-and-change-email-link`, `create-custom-token`,
    `create-session-cookie`, and `validate-token` /
    `validate-session-cookie` with revocation and disabled-account checks.
  - Second factors: `list-user-factors`, `unenroll-user-factor`,
    `unenroll-all-user-factors` — the support-side half of MFA. Enrollment
    stays the client's job against Firebase.
- `fire.auth/validate-token` now lifts the sign-in metadata out of the token's
  nested `:firebase` block and onto the top level of its result, including
  `:sign_in_second_factor` and `:second_factor_identifier` — the only
  server-side proof that a second factor was presented at sign-in. Both are
  nil unless the project is on Identity Platform and MFA was used. Purely
  additive: the raw claims are all still there, unrenamed.
- `fire.auth/validate-session-cookie`, verifying the session cookies
  `fire.admin/create-session-cookie` mints — same RS256 verification and the
  same return value, against Firebase's other key set and issuer.
- `fire.auth/format-result`, the claim flattening on its own, for callers
  holding claims they verified some other way.
- `fire.oauth2/credentials`, decoding the service account credentials in a
  named env var. `fire.admin` needs the private key out of these to sign
  custom tokens.
- `fire.oauth2/sign` gained a third-arity taking an explicit JWT header;
  Firebase custom tokens want `:typ` in theirs. The existing two-arity call
  is untouched and produces byte-identical output.

### Changed
- The OAuth2 token now also requests the `identitytoolkit` and `firebase`
  scopes. Without the first, every `fire.admin` call comes back 403.

Nothing was removed or renamed. Every change above is additive: existing
`fire.core`, `fire.storage`, `fire.socket`, `fire.vision`, `fire.auth` and
`fire.oauth2` calls behave exactly as they did in 0.6.0.

## [0.1.1] - 2020-04-18
### Changed
- Documentation on how to make the widgets.

### Removed
- `make-widget-sync` - we're all async, all the time.

### Fixed
- Fixed widget maker to keep working when daylight savings switches over.

## 0.1.0 - 2020-04-18
### Added
- Files from the new template.
- Widget maker public API - `make-widget-sync`.

[Unreleased]: https://github.com/your-name/fire/compare/0.1.1...HEAD
[0.1.1]: https://github.com/your-name/fire/compare/0.1.0...0.1.1
