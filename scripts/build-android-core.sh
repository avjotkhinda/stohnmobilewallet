#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
"$ROOT/scripts/fetch-stohn-v3.2.sh"
SRC="$ROOT/third_party/StohnCoin"
BUILD="$ROOT/out/android-core"
mkdir -p "$BUILD"
echo "Stohn v3.2 source acquired."
echo "Next build stage: port the Core dependency graph to Android bionic and produce arm64-v8a/stohncoind."
echo "This script deliberately stops before claiming a successful Core port; dependency patches are source-version-specific."
