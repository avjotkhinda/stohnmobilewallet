#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
BUILD="$ROOT/app/build.gradle.kts"
ANDROID_MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"

grep -F 'versionName = "0.28.2-fullmode"' "$BUILD" >/dev/null
grep -F 'const val APP_VERSION = "0.28.2-fullmode"' "$MANIFEST" >/dev/null
grep -F 'android:foregroundServiceType="specialUse"' "$ANDROID_MANIFEST" >/dev/null
grep -F 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$ANDROID_MANIFEST" >/dev/null
grep -F 'android.permission.ACCESS_NETWORK_STATE' "$ANDROID_MANIFEST" >/dev/null
! grep -F 'android.permission.WAKE_LOCK' "$ANDROID_MANIFEST" >/dev/null

grep -F 'const val DEBIAN_VERSION = "bookworm-arm64-default-20260910_05:24"' "$MANIFEST" >/dev/null
grep -F 'const val DEBIAN_URL = "https://images.linuxcontainers.org/images/debian/bookworm/arm64/default/20260910_05:24/rootfs.tar.xz"' "$MANIFEST" >/dev/null
for key in DEBIAN_SHA256 PROOT_SHA256 PROOT_LOADER_SHA256 LIBTALLOC_SHA256 LIBANDROID_SHMEM_SHA256; do
  value="$(sed -n "s/.*const val $key = \"\([0-9a-f]*\)\".*/\1/p" "$MANIFEST")"
  [[ "$value" =~ ^[0-9a-f]{64}$ ]] || { echo "FAIL: $key is not pinned" >&2; exit 1; }
done

LOCK="$ROOT/runtime/proot/TERMUX-PACKAGE-LOCK.json"
[[ -s "$LOCK" ]] || { echo 'FAIL: native runtime package lock missing' >&2; exit 1; }
for sha in 1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6 ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da; do
  grep -F "$sha" "$LOCK" >/dev/null || { echo "FAIL: expected native package hash missing: $sha" >&2; exit 1; }
done

for f in "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/libproot-loader.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/libtalloc.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so"; do
  [[ -s "$f" ]] || { echo "FAIL: missing native runtime artifact $f" >&2; exit 1; }
done

bash "$ROOT/tools/verify-fullmode-repair.sh"
echo 'RELEASE PREFLIGHT: PASS'
