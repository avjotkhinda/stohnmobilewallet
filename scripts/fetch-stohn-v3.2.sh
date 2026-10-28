#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/third_party"
cd "$ROOT/third_party"
if [ ! -d StohnCoin ]; then
  git clone --depth 1 --branch v3.2 https://github.com/StohnCoin-Projects/StohnCoin.git StohnCoin
fi
echo "Source ready at $ROOT/third_party/StohnCoin"
