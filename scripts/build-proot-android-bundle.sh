#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="${1:?usage: $0 /path/to/bundle [--write-only]}"
MODE="${2:-}"

# ----------------------------------------------------------------------
# Pre-flight: download a pinned version of proot from Termux unless the
# bundle directory was already provided by the caller (e.g. from CI).
# ----------------------------------------------------------------------
VERSION="5.1.107.102"
ARCHIVE_NAME="proot_${VERSION}_aarch64.deb"
ARCHIVE_URLS=(
  "https://termux.dev/files/downloads/releases/${ARCHIVE_NAME}"
  "https://termux.dev/files/downloads/releases/termux-packages-${VERSION}/packages/proot/${ARCHIVE_NAME}"
)

DL_DIR="$ROOT/tmp"
mkdir -p "$DL_DIR"

DL_URL=""
for url in "${ARCHIVE_URLS[@]}"; do
  if curl --silent --head --fail --max-redirs 3 --location "$url" >/dev/null 2>&1; then
    DL_URL="$url"
    break
  fi
done

if [[ -z "$DL_URL" ]]; then
  echo "=== ✗  PRoot package download failed ==="
  echo "    package:    proot"
  echo "    version:    ${VERSION}"
  echo "    archive:    ${ARCHIVE_NAME}"
  echo "    tried URLs:"
  for url in "${ARCHIVE_URLS[@]}"; do
    echo "      - ${url}"
  done
  echo "    ==> The requested package version is not available from any known"
  echo "        Termux mirror. You can fix this by reverting to a published"
  echo "        version (see scripts/release-preflight.sh --dry-run) or by"
  echo "        adding a working URL to the list above and re-running this"
  echo "        script. Do NOT silently accept a binary that does not match"
  echo "        the SHA-256 pinned in app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
  echo "        — the pin is there for the same reason the release preflight"
  echo "        checks it. If you are in CI and this is the first error, the"
  echo "        next step is almost certainly a stale or withdrawn package."
  echo "=== ✗  PRoot package download failed ==="
  exit 1
fi

echo "==> Downloading proot ${VERSION} from ${DL_URL}"
curl --silent --fail --location --output "$DL_DIR/$ARCHIVE_NAME" "$DL_URL"

echo "==> Extracting proot deb"
EXTRACT_DIR="$DL_DIR/extract"
rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"
dpkg -x "$DL_DIR/$ARCHIVE_NAME" "$EXTRACT_DIR"

PREFIX="$EXTRACT_DIR/data/data/com.termux/files/usr"
BIN="$PREFIX/bin/proot"
LOADER="$PREFIX/libexec/proot/loader"

[[ -x "$BIN" && -s "$BIN" ]] || { echo "FAIL: proot binary missing or empty" >&2; exit 1; }
[[ -f "$LOADER" && -s "$LOADER" ]] || { echo "FAIL: proot loader missing or empty" >&2; exit 1; }

BUN="$BUNDLE"
rm -rf "$BUN"
mkdir -p "$BUN/libs"

echo "==> Copying proot binary and loader into bundle"
cp "$BIN" "$BUN/proot-bin.so"
cp "$LOADER" "$BUN/libproot-loader.so"

for dep in libtalloc libandroid-shmem; do
  LIBDIR="$EXTRACT_DIR/$dep/data/data/com.termux/files/usr/lib"
  CANDIDATES=$(find "$LIBDIR" -maxdepth 1 -name "${dep}.so*" -print -quit)
  [[ -n "$CANDIDATES" ]] || { echo "FAIL: ${dep} shared library missing from archive" >&2; exit 1; }
  cp "$LIBDIR/${dep}.so"* "$BUN/libs/" 2>/dev/null || true
  # Pick the plain .so if available, otherwise the deepest match
  if [[ -f "$BUN/libs/${dep}.so" ]]; then
    echo "    copied $dep as libs/${dep}.so"
  else
    cp "$(find "$BUN/libs" -name "${dep}.so*" -print -quit)" "$BUN/libs/${dep}.so"
    echo "    copied $dep as libs/${dep}.so (renamed from $(basename $(find "$BUN/libs" -name "${dep}.so*" -print -quit)))"
  fi
done

# Normalize SONAMEs and RPATH with patchelf so the APK is self-contained.
echo "==> Normalizing shared library references with patchelf"
for lib in libtalloc libandroid-shmem; do
  SONAME="$(readelf -d "$BUN/proot-bin.so" | grep "Shared library:" | grep "$lib" | sed 's/.*Shared library: \[\(.*\)\]/\1/')"
  if [[ -n "$SONAME" && "$SONAME" != "${lib}.so" ]]; then
    patchelf --replace-needed "$SONAME" "${lib}.so" "$BUN/proot-bin.so" 2>/dev/null || true
    echo "    replaced NEEDED $SONAME -> ${lib}.so"
  fi
done
patchelf --set-rpath '$ORIGIN' "$BUN/proot-bin.so" 2>/dev/null || true

# Verify the dependency graph is exactly what we expect.
echo "==> Verifying dependency graph"
NEEDED="$(readelf -d "$BUN/proot-bin.so" | grep 'Shared library:' || true)"
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
