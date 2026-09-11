#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="${1:?usage: $0 /path/to/proot-bundle [--write]}"
MODE="${2:-}"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
[[ -f "$BUNDLE/proot-bin.so" && -f "$BUNDLE/libproot-loader.so" ]] || { echo 'FAIL: incomplete bundle' >&2; exit 1; }
mkdir -p "$DEST"
cp "$BUNDLE/proot-bin.so" "$DEST/libproot.so"
cp "$BUNDLE/libproot-loader.so" "$DEST/libproot-loader.so"
for dep in libtalloc.so libandroid-shmem.so; do
  [[ -f "$BUNDLE/libs/$dep" ]] || { echo "FAIL: missing dependency $dep" >&2; exit 1; }
  cp "$BUNDLE/libs/$dep" "$DEST/$dep"
done
extra=$(find "$BUNDLE/libs" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | grep -Ev '^(libtalloc|libandroid-shmem)\.so$' || true)
[[ -z "$extra" ]] || { echo "FAIL: unexpected native dependency files: $extra" >&2; exit 1; }
P=$(sha256sum "$DEST/libproot.so" | awk '{print $1}')
L=$(sha256sum "$DEST/libproot-loader.so" | awk '{print $1}')
T=$(sha256sum "$DEST/libtalloc.so" | awk '{print $1}')
S=$(sha256sum "$DEST/libandroid-shmem.so" | awk '{print $1}')
if [[ "$MODE" == "--write" ]]; then
  python3 - "$MANIFEST" "$P" "$L" "$T" "$S" <<'INNERPY'
from pathlib import Path
import re,sys
p=Path(sys.argv[1]); s=p.read_text()
for key,val in zip(("PROOT_SHA256","PROOT_LOADER_SHA256","LIBTALLOC_SHA256","LIBANDROID_SHMEM_SHA256"),sys.argv[2:]):
    s,n=re.subn(rf'const val {key} = "[0-9a-f]*"', f'const val {key} = "{val}"', s, count=1)
    if n != 1: raise SystemExit(f'FAIL: {key} declaration not found')
p.write_text(s)
INNERPY
else
  for pair in "PROOT_SHA256=$P" "PROOT_LOADER_SHA256=$L" "LIBTALLOC_SHA256=$T" "LIBANDROID_SHMEM_SHA256=$S"; do
    key=${pair%%=*}; actual=${pair#*=}
    expected=$(sed -n "s/.*const val $key = \"\([0-9a-f]*\)\".*/\1/p" "$MANIFEST")
    [[ "$expected" == "$actual" ]] || { echo "FAIL: $key mismatch: manifest=$expected bundle=$actual" >&2; exit 1; }
  done
fi
bash "$ROOT/scripts/verify-proot-bundle.sh" "$DEST"
printf 'PRoot SHA-256: %s\nloader SHA-256: %s\nlibtalloc SHA-256: %s\nlibandroid-shmem SHA-256: %s\n' "$P" "$L" "$T" "$S"
