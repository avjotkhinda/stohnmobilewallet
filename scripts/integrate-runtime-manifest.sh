#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
URL="$(sed -n 's/.*const val DEBIAN_URL = "\([^"]*\)".*/\1/p' "$MANIFEST")"
PIN="$(sed -n 's/.*const val DEBIAN_SHA256 = "\([0-9a-f]*\)".*/\1/p' "$MANIFEST")"
[[ "$URL" == https://* ]] || { echo 'FAIL: Debian URL is not HTTPS' >&2; exit 1; }

[[ "$PIN" =~ ^[0-9a-f]{64}$ ]] || { echo 'FAIL: DEBIAN_SHA256 must be pinned in RuntimeManifest.kt' >&2; exit 1; }
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
curl --fail --location --proto '=https' --tlsv1.2 --retry 4 --retry-delay 2 --retry-all-errors -o "$TMP" "$URL"
ACTUAL="$(sha256sum "$TMP" | awk '{print $1}')"
[[ "$ACTUAL" == "$PIN" ]] || { echo "FAIL: Debian rootfs SHA mismatch: manifest=$PIN actual=$ACTUAL" >&2; exit 1; }
echo "Debian rootfs SHA-256 verified: $PIN"
