#!/usr/bin/env bash
set -euo pipefail
# Verifies a real wallet.dat with the official Stohn wallet-tool. No wallet records are parsed here.
# Usage: verify-wallet-migration.sh /path/to/wallet.dat /path/to/stohncoin-wallet
WALLET_DAT=${1:?wallet.dat path required}
WALLET_TOOL=${2:?stohncoin-wallet path required}
[[ -f "$WALLET_DAT" ]] || { echo "wallet.dat missing" >&2; exit 2; }
[[ -x "$WALLET_TOOL" ]] || { echo "wallet tool is not executable" >&2; exit 2; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir "$TMP/out"
cp --reflink=auto -- "$WALLET_DAT" "$TMP/out/wallet.dat"
"$WALLET_TOOL" -datadir="$TMP/out" -wallet=wallet.dat info
"$WALLET_TOOL" -datadir="$TMP/out" -wallet=wallet.dat -dumpfile="$TMP/wallet.dump" dump
"$WALLET_TOOL" -datadir="$TMP/out" -wallet=migrated -dumpfile="$TMP/wallet.dump" createfromdump
[[ -f "$TMP/out/migrated/wallet.dat" || -f "$TMP/out/migrated" ]] || { echo "createfromdump did not produce a migrated wallet" >&2; exit 1; }
"$WALLET_TOOL" -datadir="$TMP/out" -wallet=migrated info
printf '%s\n' 'REAL WALLET.DAT MIGRATION TEST PASSED'
