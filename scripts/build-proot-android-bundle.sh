#!/usr/bin/env bash
# Build the pinned PRoot Android bundle from the Termux archive.
#
#   scripts/build-proot-android-bundle.sh /path/to/bundle
#
# The bundle directory is populated with proot-bin.so, libproot-loader.so and
# libs/{libtalloc,libandroid-shmem}.so, then handed to integrate-proot-bundle.sh
# with --write-only so the caller decides when the pins land in RuntimeManifest.kt.
#
# Every downloaded package is checked against the sha256 recorded in
# runtime/proot/TERMUX-PACKAGE-LOCK.json, so a mirror serving a different build
# fails here instead of shipping a binary that disagrees with the app's pins.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="${1:?usage: $0 /path/to/bundle}"
LOCK="$ROOT/runtime/proot/TERMUX-PACKAGE-LOCK.json"

REPO="https://packages.termux.dev/apt/termux-main"
MIRRORS=(
  "https://packages.termux.dev/apt/termux-main"
  "https://packages-cf.termux.dev/apt/termux-main"
)

DL_DIR="$ROOT/tmp"
rm -rf "$DL_DIR"
mkdir -p "$DL_DIR"

[[ -f "$LOCK" ]] || { echo "FAIL: package lock missing at $LOCK" >&2; exit 1; }

# name|path|sha256, straight from the lock file so the script cannot drift.
PACKAGES="$(python3 - "$LOCK" <<'PY'
import json, sys
lock = json.load(open(sys.argv[1]))
if lock.get("architecture") != "aarch64":
    sys.exit("FAIL: lock architecture is %r, expected aarch64" % lock.get("architecture"))
for pkg in lock["packages"]:
    print("%s|%s|%s" % (pkg["name"], pkg["path"], pkg["sha256"]))
PY
)"

fetch() {
  local name="$1" path="$2" want="$3"
  local deb="$DL_DIR/${name}.deb" url="" mirror
  for mirror in "${MIRRORS[@]}"; do
    url="$mirror/$path"
    if curl --silent --fail --location --max-time 120 --output "$deb" "$url" 2>/dev/null; then
      break
    fi
    url=""
  done
  if [[ -z "$url" || ! -s "$deb" ]]; then
    {
      echo "=== ✗  Termux package download failed ==="
      echo "    package:  ${name}"
      echo "    expected: ${REPO}/${path}"
      echo "    tried mirrors:"
      printf '      - %s\n' "${MIRRORS[@]}"
      echo "    The pinned version is no longer served. Re-pin to a published"
      echo "    version in ${LOCK#"$ROOT"/} and in RuntimeManifest.kt, then update"
      echo "    the expected sha256 below to match the new artifact."
      echo "=== ✗  Termux package download failed ==="
    } >&2
    exit 1
  fi
  local got
  got="$(sha256sum "$deb" | cut -d' ' -f1)"
  if [[ "$got" != "$want" ]]; then
    {
      echo "=== ✗  Termux package hash mismatch ==="
      echo "    package:  ${name}"
      echo "    expected: ${want}"
      echo "    actual:   ${got}"
      echo "    The mirror served a different build than the one this repo pins."
      echo "=== ✗  Termux package hash mismatch ==="
    } >&2
    exit 1
  fi
  echo "    ${name}: sha256 ok"
}

echo "==> Fetching pinned Termux packages"
while IFS='|' read -r name path sha; do
  [[ -n "$name" ]] || continue
  fetch "$name" "$path" "$sha"
done <<< "$PACKAGES"

echo "==> Extracting packages"
for name in proot libtalloc libandroid-shmem; do
  dpkg -x "$DL_DIR/${name}.deb" "$DL_DIR/$name"
done

PROOT_PREFIX="$DL_DIR/proot/data/data/com.termux/files/usr"
BIN="$PROOT_PREFIX/bin/proot"
LOADER="$PROOT_PREFIX/libexec/proot/loader"

[[ -x "$BIN" && -s "$BIN" ]] || { echo "FAIL: proot binary missing or empty" >&2; exit 1; }
[[ -f "$LOADER" && -s "$LOADER" ]] || { echo "FAIL: proot loader missing or empty" >&2; exit 1; }

BUN="$BUNDLE"
rm -rf "$BUN"
mkdir -p "$BUN/libs"

echo "==> Copying proot binary and loader into bundle"
cp "$BIN" "$BUN/proot-bin.so"
cp "$LOADER" "$BUN/libproot-loader.so"

for dep in libtalloc libandroid-shmem; do
  LIBDIR="$DL_DIR/$dep/data/data/com.termux/files/usr/lib"
  SRC="$(find "$LIBDIR" -maxdepth 1 -name "${dep}.so*" -print -quit)"
  [[ -n "$SRC" ]] || { echo "FAIL: ${dep} shared library missing from archive" >&2; exit 1; }
  cp "$SRC" "$BUN/libs/${dep}.so"
  echo "    copied ${dep} as libs/${dep}.so"
done

# Normalize SONAMEs and RPATH with patchelf so the APK is self-contained.
echo "==> Normalizing shared library references with patchelf"
for lib in libtalloc libandroid-shmem; do
  SONAME="$(readelf -d "$BUN/proot-bin.so" | grep "Shared library:" | grep "$lib" | sed 's/.*Shared library: \[\(.*\)\]/\1/')"
  if [[ -n "$SONAME" && "$SONAME" != "${lib}.so" ]]; then
    patchelf --replace-needed "$SONAME" "${lib}.so" "$BUN/proot-bin.so"
    echo "    replaced NEEDED $SONAME -> ${lib}.so"
  fi
done
patchelf --set-rpath '$ORIGIN' "$BUN/proot-bin.so"

# Verify the dependency graph is exactly what we expect.
echo "==> Verifying dependency graph"
NEEDED="$(readelf -d "$BUN/proot-bin.so" | grep 'Shared library:')"
for dep in libtalloc.so libandroid-shmem.so; do
  if ! echo "$NEEDED" | grep -q "$dep"; then
    echo "FAIL: $dep not present in final PRoot DT_NEEDED" >&2
    exit 1
  fi
done

echo "==> Bundle ready at ${BUN}"
ls -la "$BUN/" "$BUN/libs/"

# Forward to the integration step. --write-only means "don't copy to jniLibs
# here; just leave the bundle prepared for the next step".
"$ROOT/scripts/integrate-proot-bundle.sh" "$BUN" --write-only
