#!/usr/bin/env bash
# Enable TOTP MFA on a Firebase project via the Identity Platform admin API.
#
# The project must already be upgraded to Firebase Authentication with
# Identity Platform (Firebase console -> Authentication -> Settings ->
# Upgrade), which needs Blaze billing. This call then turns the TOTP
# provider on — the piece the Firebase console UI does not always expose.
#
# Usage:
#   ./totp.sh status <project-id>          # read-only; what is actually set
#   ./totp.sh enable <project-id> [n]      # n = adjacent intervals, default 5
#
# Requires gcloud authenticated as an owner/editor of the project.
set -euo pipefail

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 1; }

cmd="${1:-}"; project_id="${2:-}"
[[ -z "$cmd" || -z "$project_id" ]] && usage
[[ "$cmd" != "status" && "$cmd" != "enable" ]] && usage

# ±intervals of 30s the verifier accepts, absorbing device clock skew.
adjacent_intervals="${3:-5}"
if [[ "$cmd" == "enable" && ! "$adjacent_intervals" =~ ^[0-9]+$ ]]; then
  echo "adjacent-intervals must be a number, got: ${adjacent_intervals}" >&2
  exit 1
fi

# Without a quota project, Google attributes the call to gcloud's own shared
# client project (32555940559) — where identitytoolkit is not enabled — and
# answers 403 SERVICE_DISABLED, which reads like a permissions problem on YOUR
# project but is not. x-goog-user-project below pins attribution to this
# project instead. (Global alternative: gcloud auth application-default
# set-quota-project <project-id>.)
token="$(gcloud auth print-access-token)"
config_url="https://identitytoolkit.googleapis.com/admin/v2/projects/${project_id}/config"

# Every call goes through here: curl -sS exits 0 on HTTP 400/403, so the status
# code must be checked explicitly or a failed call reads as a success. That is
# exactly how this script once reported "TOTP MFA enabled" on a project where
# nothing had been enabled at all.
call() { # method [data] -> body on stdout, non-zero exit on API error
  local method="$1" data="${2:-}" body code
  body="$(mktemp)"
  if [[ -n "$data" ]]; then
    code="$(curl -sS -o "$body" -w '%{http_code}' -X "$method" "${config_url}?updateMask=mfa" \
      -H "Authorization: Bearer ${token}" -H "x-goog-user-project: ${project_id}" \
      -H "Content-Type: application/json" -d "$data")"
  else
    code="$(curl -sS -o "$body" -w '%{http_code}' "$config_url" \
      -H "Authorization: Bearer ${token}" -H "x-goog-user-project: ${project_id}")"
  fi
  if [[ "$code" -ge 400 ]]; then
    echo "API returned HTTP ${code}:" >&2
    # Print the parsed error when it parses, and ALWAYS fall back to the raw
    # body — a 403 from a disabled API carries its reason only in the text.
    python3 - "$body" >&2 <<'ERR' || true
import json, sys
raw = open(sys.argv[1]).read()
try:
    e = json.loads(raw).get("error", {})
    print("   status :", e.get("status", "?"))
    print("   message:", e.get("message", "?"))
    for d in e.get("details", []):
        if d.get("reason"): print("   reason :", d["reason"])
        if d.get("metadata"): print("   meta   :", json.dumps(d["metadata"]))
except Exception:
    print("   raw:", raw[:600] or "(empty body)")
ERR
    rm -f "$body"; return 1
  fi
  cat "$body"; rm -f "$body"
}

show() { # prints the real state, and exits non-zero when TOTP is NOT on
  python3 - "$@" <<'PY'
import json, sys
mfa = json.loads(sys.stdin.read() or "{}").get("mfa", {})
state = mfa.get("state", "<unset>")
print("mfa.state:", state)
on = False
for p in mfa.get("providerConfigs", []) or []:
    totp = p.get("totpProviderConfig")
    print("  provider:", p.get("state"), "totp:", json.dumps(totp) if totp else "none")
    if p.get("state") == "ENABLED" and totp is not None:
        on = True
print("TOTP enrolment available:", "YES" if on else "NO")
sys.exit(0 if on else 2)
PY
}

if [[ "$cmd" == "status" ]]; then
  # Read first, THEN interpret: piping straight into show made an unreadable
  # config print a confident "TOTP enrolment available: NO", which is a claim
  # about state we never actually saw.
  body="$(call GET)" || { echo "Could not read the config — state unknown." >&2; exit 1; }
  printf '%s' "$body" | show
  exit $?
fi

call PATCH "{\"mfa\":{\"state\":\"ENABLED\",\"providerConfigs\":[{\"state\":\"ENABLED\",\"totpProviderConfig\":{\"adjacentIntervals\":${adjacent_intervals}}}]}}" > /dev/null

# Never claim success from the write call — re-read and prove it.
echo "Verifying against the live config…"
verify="$(call GET)" || { echo "Could not re-read the config — state unknown." >&2; exit 1; }
if printf '%s' "$verify" | show; then
  echo "TOTP MFA is enabled on ${project_id} (adjacentIntervals=${adjacent_intervals})."
else
  echo "PATCH was accepted but TOTP is still not enabled on ${project_id}." >&2
  echo "Most likely the project is not upgraded to Identity Platform yet." >&2
  exit 1
fi
