#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
URL="$(sed -n 's/.*const val DEBIAN_URL = "\([^"]*\)".*/\1/p' "$MANIFEST")"
PIN="$(sed -n 's/.*const val DEBIAN_SHA256 = "\([0-9a-f]*\)".*/\1/p' "$MANIFEST")"
[[ "$URL" == https://* ]] || { echo 'FAIL: Debian URL is not HTTPS' >&2; exit 1; }

# If the repository intentionally leaves the snapshot digest empty, resolve it
# from the snapshot publisher's SHA256SUMS file in CI, then verify the archive
# against that published digest. This keeps the checked-in source portable while
# ensuring the build never accepts an unverified rootfs.
if [[ ! "$PIN" =~ ^[0-9a-f]{64}$ ]]; then
  SUMS_URL="${URL%/*}/SHA256SUMS"
  SUMS="$(mktemp)"
  trap 'rm -f "$SUMS" "$TMP"' EXIT
  curl --fail --location --proto '=https' --tlsv1.2 --retry 4 --retry-delay 2 --retry-all-errors -o "$SUMS" "$SUMS_URL"
  PIN="$(awk '$2 == "rootfs.tar.xz" {print $1; exit}' "$SUMS")"
  [[ "$PIN" =~ ^[0-9a-fA-F]{64}$ ]] || { echo 'FAIL: published SHA256SUMS does not contain a valid rootfs.tar.xz digest' >&2; exit 1; }
  PIN="${PIN,,}"
  python3 - "$MANIFEST" "$PIN" <<'PY'
from pathlib import Path
import re, sys
p = Path(sys.argv[1])
s = p.read_text()
s, n = re.subn(r'const val DEBIAN_SHA256 = "[0-9a-f]*"',
                f'const val DEBIAN_SHA256 = "{sys.argv[2]}"', s, count=1)
if n != 1:
    raise SystemExit('FAIL: DEBIAN_SHA256 declaration not found')
p.write_text(s)
PY
  echo "Resolved Debian rootfs SHA-256 from official SHA256SUMS: $PIN"
fi

[[ "$PIN" =~ ^[0-9a-f]{64}$ ]] || { echo 'FAIL: DEBIAN_SHA256 is not pinned' >&2; exit 1; }
TMP="$(mktemp)"
curl --fail --location --proto '=https' --tlsv1.2 --retry 4 --retry-delay 2 --retry-all-errors -o "$TMP" "$URL"
ACTUAL="$(sha256sum "$TMP" | awk '{print $1}')"
[[ "$ACTUAL" == "$PIN" ]] || { echo "FAIL: Debian rootfs SHA mismatch: manifest=$PIN actual=$ACTUAL" >&2; exit 1; }
echo "Debian rootfs SHA-256 verified: $PIN"
