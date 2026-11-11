#!/usr/bin/env bash
set -euo pipefail
# Both artifacts the Full Mode runtime downloads are pinned by URL and SHA-256 in RuntimeManifest.kt.
# The pins are only as good as the URLs behind them: a release asset can be deleted, an upstream tag
# can be rewritten, and a mirror can prune old snapshots. None of those fail a build - they fail on a
# phone, as "Download failed: HTTP 404", after a user has already installed the app.
#
# So this downloads each pinned artifact, checks the digest against the pin, and checks the archive
# still has the layout the installer expects. It is what .github/workflows/pin-liveness.yml runs
# weekly, and it can be run by hand from the repository root.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"

fail() { echo "PIN LIVENESS: FAIL: $*" >&2; exit 1; }

[[ -f "$MANIFEST" ]] || fail "RuntimeManifest.kt is missing"

# Reads one `const val` string from the manifest, so this check always evaluates the pins that ship
# rather than a copy of them.
pin() {
  local name="$1"
  local value
  value="$(sed -n "s/^[[:space:]]*const val ${name} = \"\([^\"]*\)\".*/\1/p" "$MANIFEST" | head -n1)"
  printf '%s' "$value"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# check <label> <url> <pinned sha256> <archive listing style> <entry the layout must still contain>
check() {
  local label="$1" url="$2" expected="$3" style="$4" entry="$5"
  [[ "$url" == https://* ]] || fail "$label URL is not HTTPS: '${url}'"
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || fail "$label SHA-256 is not pinned in RuntimeManifest.kt: '${expected}'"

  local archive="$WORK/$label.archive" code actual
  # --location because a GitHub release asset answers with a redirect to its object store, which is
  # exactly the hop the app's installer follows. No --fail: the HTTP status is reported, so a 404
  # names itself instead of surfacing as a curl failure.
  code="$(curl --silent --show-error --location --proto '=https' --tlsv1.2 \
    --retry 4 --retry-delay 2 --retry-all-errors \
    --output "$archive" --write-out '%{http_code}' --max-time 600 "$url" || true)"
  [[ "$code" == 200 ]] || fail "$label returned HTTP ${code:-000} for $url"

  actual="$(sha256sum "$archive" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] ||
    fail "$label serves different bytes than its pin: pinned=$expected served=$actual ($url)"

  case "$style" in
    xz) tar -tJf "$archive" > "$WORK/$label.entries" ;;
    gz) tar -tzf "$archive" > "$WORK/$label.entries" ;;
    *) fail "unknown archive style '$style'" ;;
  esac
  # An archive can match its digest and still be unusable - the wrong snapshot, the wrong
  # architecture, an asset uploaded from the wrong file. The installer's own checks are the ones
  # worth repeating: /bin/sh for the rootfs, the daemon for Stohn Core.
  grep -Eq "$entry" "$WORK/$label.entries" ||
    fail "$label archive layout changed: no entry matching '$entry' ($url)"

  local size
  size="$(du -h "$archive" | awk '{print $1}')"
  echo "PIN LIVENESS: $label ok ($size, sha256 $actual)"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "- $label: $(basename "$url") serves its pinned bytes ($size, sha256 \`$actual\`)" >> "$GITHUB_STEP_SUMMARY"
  fi
}

check "debian-rootfs" "$(pin DEBIAN_URL)" "$(pin DEBIAN_SHA256)" xz '(^|/)bin/sh$'
check "stohn-core" "$(pin CORE_URL)" "$(pin CORE_SHA256)" gz '(^|/)stohncoind$'

echo "PIN LIVENESS: PASS: both pinned runtime URLs serve the bytes RuntimeManifest.kt pins"
