# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

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

## [Unreleased]
### Changed
- Add a new arity to `make-widget-async` to provide a different widget shape.

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
