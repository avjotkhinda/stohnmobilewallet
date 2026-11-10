#!/usr/bin/env bash
# Fails when the release APK could not install over the last published one.
#
# usage: verify-upgrade-compatibility.sh <apk>
#        verify-upgrade-compatibility.sh <apk> --emit <file>
#
# Android installs an update only when three things hold: the package name is the same, the signing
# certificate is the same, and versionCode is higher than the installed one. None of them is caught
# by a unit test, lint or a successful build — the first sign of trouble is a user who cannot update.
# This compares the built APK against `runtime/release-baseline.json`, which records the last
# release that actually went out.
#
# `--emit <file>` writes that record for the APK being released, so the baseline can be committed
# after publishing. It is a deliberate manual step: the baseline must describe a release that exists.
set -euo pipefail

apk=${1:?usage: verify-upgrade-compatibility.sh <apk> [--emit <file>]}
shift
emit=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --emit)
            emit=${2:?--emit needs a path}
            shift 2
            ;;
        *)
            echo "unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

[ -f "$apk" ] || { echo "no such APK: $apk" >&2; exit 1; }

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
baseline="$root/runtime/release-baseline.json"

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
[ -n "$sdk" ] || { echo "ANDROID_SDK_ROOT or ANDROID_HOME must be set" >&2; exit 1; }
find_tool() {
    local name=$1
    local candidate
    candidate=$(find "$sdk/build-tools" -maxdepth 2 -type f -name "$name" 2>/dev/null | sort -V | tail -1)
    [ -n "$candidate" ] || { echo "no $name in $sdk/build-tools" >&2; exit 1; }
    printf '%s' "$candidate"
}
apksigner=$(find_tool apksigner)
aapt2=$(find_tool aapt2)

# An APK that does not verify is not an update anyone can install, so this failure comes first.
certs=$(mktemp)
trap 'rm -f "$certs"' EXIT
"$apksigner" verify --print-certs "$apk" > "$certs" 2>&1 || {
    echo "APK signature does not verify; it could not be installed at all" >&2
    cat "$certs"
    exit 1
}
signer=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' "$certs" | head -1 | tr 'A-F' 'a-f')
[ -n "$signer" ] || { echo "no signer certificate found in $apk" >&2; exit 1; }

badging=$("$aapt2" dump badging "$apk")
package=$(printf '%s\n' "$badging" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)
version_code=$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p" | head -1)
version_name=$(printf '%s\n' "$badging" | sed -n "s/^package: .*versionName='\([^']*\)'.*/\1/p" | head -1)
[ -n "$package" ] && [ -n "$version_code" ] || { echo "cannot read package/versionCode from $apk" >&2; exit 1; }

if [ -n "$emit" ]; then
    mkdir -p "$(dirname "$emit")"
    cat > "$emit" <<JSON
{
  "package": "$package",
  "versionCode": $version_code,
  "versionName": "$version_name",
  "signerSha256": "$signer",
  "note": "The last release that went out. Android installs an update only with this package, this signing certificate and a higher versionCode; scripts/verify-upgrade-compatibility.sh enforces all three in the release job."
}
JSON
    echo "wrote $emit for $package $version_name ($version_code)"
    exit 0
fi

[ -f "$baseline" ] || {
    echo "no baseline at $baseline: record the last published release with --emit before releasing" >&2
    exit 1
}

base_package=$(sed -n 's/.*"package": "\([^"]*\)".*/\1/p' "$baseline" | head -1)
base_code=$(sed -n 's/.*"versionCode": \([0-9]*\).*/\1/p' "$baseline" | head -1)
base_name=$(sed -n 's/.*"versionName": "\([^"]*\)".*/\1/p' "$baseline" | head -1)
base_signer=$(sed -n 's/.*"signerSha256": "\([^"]*\)".*/\1/p' "$baseline" | head -1)

[ -n "$base_package" ] && [ -n "$base_code" ] || { echo "cannot read package/versionCode from $baseline" >&2; exit 1; }

failed=0
if [ "$package" != "$base_package" ]; then
    echo "package ${package} differs from the released ${base_package}: this is a different app, not an update" >&2
    failed=1
fi
if [ "$version_code" -le "$base_code" ]; then
    echo "versionCode ${version_code} is not greater than the released ${base_code} (${base_name}): the store and the device both refuse this update. Raise versionCode in app/build.gradle.kts." >&2
    failed=1
fi
if [ -n "$base_signer" ] && [ "$signer" != "$base_signer" ]; then
    echo "signing certificate ${signer} differs from the released ${base_signer}: Android refuses an update signed by a different key, so every user would have to uninstall and lose their node data" >&2
    failed=1
fi
if [ -z "$base_signer" ]; then
    echo "note: the baseline pins no signing certificate yet; publish this release and commit the emitted baseline so the next one is checked against it"
fi

[ "$failed" -eq 0 ] || exit 1

echo "package: $package"
echo "version: $version_name ($version_code), up from $base_name ($base_code)"
echo "signer:  $signer"
echo "UPGRADE CHECK: PASS: this APK installs over ${base_name:-the baseline}"
