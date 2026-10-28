#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:?usage: $0 APK}"
[[ -s "$APK" ]] || { echo "FAIL: APK missing or empty" >&2; exit 1; }
command -v unzip >/dev/null || { echo "FAIL: unzip unavailable" >&2; exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$APK" -d "$TMP"
EXPECTED='lib/arm64-v8a/libproot.so lib/arm64-v8a/libproot-loader.so lib/arm64-v8a/libtalloc.so lib/arm64-v8a/libandroid-shmem.so'
for rel in $EXPECTED; do
  [[ -s "$TMP/$rel" ]] || { echo "FAIL: missing APK native runtime artifact $rel" >&2; exit 1; }
done
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"

get_manifest_hash() {
    local key="$1"
    grep -F "const val $key =" "$MANIFEST" |
        sed -E 's/.*= "([0-9a-f]{64})".*/\1/'
}

check_hash() {
    local key="$1"
    local file="$2"

    local expected
    expected="$(get_manifest_hash "$key")"

    [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || {
        echo "FAIL: $key is missing or invalid in RuntimeManifest.kt" >&2
        exit 1
    }

    local actual
    actual="$(sha256sum "$TMP/$file" | awk '{print $1}')"

    if [[ "$actual" != "$expected" ]]; then
        echo "FAIL: $file SHA-256 mismatch" >&2
        echo "Expected: $expected" >&2
        echo "Actual:   $actual" >&2
        exit 1
    fi
}

check_hash PROOT_SHA256 lib/arm64-v8a/libproot.so
check_hash PROOT_LOADER_SHA256 lib/arm64-v8a/libproot-loader.so
check_hash LIBTALLOC_SHA256 lib/arm64-v8a/libtalloc.so
check_hash LIBANDROID_SHMEM_SHA256 lib/arm64-v8a/libandroid-shmem.so

echo "APK native runtime hash verification: PASS"
# Verify that critical Full Mode runtime libraries are present, and allow additional app dependencies.
find "$TMP/lib/arm64-v8a" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | sort > "$TMP/native-files.txt"
printf '%s\n' libandroid-shmem.so libandroidx.graphics.path.so libproot-loader.so libproot.so libtalloc.so | sort > "$TMP/native-expected.txt"
diff -u "$TMP/native-expected.txt" "$TMP/native-files.txt" || {
  echo "WARNING: APK contains native libraries that differ from expected set" >&2
  echo "Expected:" >&2
  cat "$TMP/native-expected.txt" >&2
  echo "Found:" >&2
  cat "$TMP/native-files.txt" >&2
}
# Ensure the APK contains no other ABI payloads.
if [[ -d "$TMP/lib" ]]; then
  unexpected_abis="$(find "$TMP/lib" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | grep -v '^arm64-v8a$' || true)"
  if [[ -n "$unexpected_abis" ]]; then
    echo "FAIL: unexpected non-ARM64 ABI payload: $unexpected_abis" >&2
    exit 1
  fi
fi
sha256sum "$APK"
echo 'APK ARTIFACT CHECK: PASS'
