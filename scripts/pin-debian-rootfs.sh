#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
URL="$(sed -n 's/.*const val DEBIAN_URL = "\([^"]*\)".*/\1/p' "$MANIFEST")"
WORK="${1:-${RUNNER_TEMP:-/tmp}/stohn-debian-pin}"
MODE="${2:-}"
[[ "$URL" == https://* ]] || { echo 'FAIL: Debian URL must be HTTPS' >&2; exit 1; }
mkdir -p "$WORK"
SUMS="$WORK/SHA256SUMS"
ARCHIVE="$WORK/rootfs.tar.xz"
curl --fail --location --proto '=https' --tlsv1.2 --retry 4 --retry-delay 2 --retry-all-errors -o "$SUMS" "${URL%/*}/SHA256SUMS"
EXPECTED="$(awk '$2 == "rootfs.tar.xz" {print $1; exit}' "$SUMS")"
[[ "$EXPECTED" =~ ^[0-9a-fA-F]{64}$ ]] || { echo 'FAIL: official SHA256SUMS has no valid rootfs.tar.xz digest' >&2; exit 1; }
curl --fail --location --proto '=https' --tlsv1.2 --retry 4 --retry-delay 2 --retry-all-errors "$URL" -o "$ARCHIVE"
tar -tJf "$ARCHIVE" | grep -Eq '(^|/)bin/sh$' || { echo 'FAIL: rootfs archive lacks bin/sh' >&2; exit 1; }
SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
[[ "${SHA,,}" == "${EXPECTED,,}" ]] || { echo "FAIL: downloaded rootfs does not match official SHA256SUMS: expected=$EXPECTED actual=$SHA" >&2; exit 1; }
echo "Debian rootfs SHA-256 verified against official SHA256SUMS: ${EXPECTED,,}"
if [[ "$MODE" == "--write" ]]; then
  python3 - "$MANIFEST" "${EXPECTED,,}" <<'PY'
from pathlib import Path
import re,sys
p=Path(sys.argv[1]); s=p.read_text()
s,n=re.subn(r'const val DEBIAN_SHA256 = "[0-9a-f]*"', f'const val DEBIAN_SHA256 = "{sys.argv[2]}"', s, count=1)
if n != 1: raise SystemExit('FAIL: DEBIAN_SHA256 declaration not found')
p.write_text(s)
PY
  echo 'Updated RuntimeManifest.kt with the publisher-verified digest.'
else
  echo 'Digest not written. Use --write to persist the verified digest.'
fi
