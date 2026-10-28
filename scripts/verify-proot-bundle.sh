#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$ROOT/app/src/main/jniLibs/arm64-v8a}"
# Accept both the final APK payload layout and the raw builder bundle layout.
# The builder emits proot-bin.so + libs/*.so; integration renames those files.
if [[ -f "$DIR/libproot.so" ]]; then
  PROOT="$DIR/libproot.so"
  LOADER="$DIR/libproot-loader.so"
  TALLOC="$DIR/libtalloc.so"
  SHMEM="$DIR/libandroid-shmem.so"
else
  PROOT="$DIR/proot-bin.so"
  LOADER="$DIR/libproot-loader.so"
  TALLOC="$DIR/libs/libtalloc.so"
  SHMEM="$DIR/libs/libandroid-shmem.so"
fi

for f in "$PROOT" "$LOADER" "$TALLOC" "$SHMEM"; do
  [[ -f "$f" ]] || { echo "FAIL: missing $f" >&2; exit 1; }
  python3 - "$f" <<'PY'
import struct,sys
p=sys.argv[1]
h=open(p,'rb').read(20)
if len(h)!=20 or h[:4]!=b'\x7fELF' or h[4]!=2 or h[5]!=1 or struct.unpack_from('<H',h,18)[0]!=183:
    raise SystemExit('FAIL: not AArch64 ELF64 '+p)
PY
done

readelf -d "$PROOT" | grep -q 'libtalloc.so' || { echo 'FAIL: libtalloc dependency missing from PRoot' >&2; exit 1; }
readelf -d "$PROOT" | grep -q 'libandroid-shmem.so' || { echo 'FAIL: libandroid-shmem dependency missing from PRoot' >&2; exit 1; }
readelf -d "$PROOT" | grep -q '\$ORIGIN' || { echo 'FAIL: PRoot has no $ORIGIN runtime path' >&2; exit 1; }
declare -A expected=(
  [libproot.so]=""
  [libproot-loader.so]=""
  [libtalloc.so]=""
  [libandroid-shmem.so]=""
)
manifest="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
if [[ -f "$manifest" ]]; then
  expected[libproot.so]="$(sed -n 's/.*const val PROOT_SHA256 = "\([0-9a-f]*\)".*/\1/p' "$manifest")"
  expected[libproot-loader.so]="$(sed -n 's/.*const val PROOT_LOADER_SHA256 = "\([0-9a-f]*\)".*/\1/p' "$manifest")"
  expected[libtalloc.so]="$(sed -n 's/.*const val LIBTALLOC_SHA256 = "\([0-9a-f]*\)".*/\1/p' "$manifest")"
  expected[libandroid-shmem.so]="$(sed -n 's/.*const val LIBANDROID_SHMEM_SHA256 = "\([0-9a-f]*\)".*/\1/p' "$manifest")"
fi
declare -A actual=(
  [libproot.so]="$PROOT"
  [libproot-loader.so]="$LOADER"
  [libtalloc.so]="$TALLOC"
  [libandroid-shmem.so]="$SHMEM"
)

for name in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
  got="$(sha256sum "${actual[$name]}" | awk '{print $1}')"
  echo "$got  $name"
  if [[ -n "${expected[$name]}" ]]; then
    [[ "${expected[$name]}" == "$got" ]] || { echo "FAIL: $name hash does not match RuntimeManifest" >&2; exit 1; }
  fi
done
printf 'PASS: PRoot Android bundle validated\n'
