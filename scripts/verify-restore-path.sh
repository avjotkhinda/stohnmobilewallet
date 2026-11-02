#!/usr/bin/env bash
set -euo pipefail
# Cross-checks the app's restore path against scripts/verify-wallet-migration.sh.
# The official wallet tool is arm64-only, so CI cannot run Core itself. What it can prove is that
# restore still uses the exact sequence and containment the shell script establishes on a real
# wallet.dat: info, then the encrypted record dump, then createfromdump into a SQLite wallet,
# with every intermediate file inside a directory that is deleted afterwards.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIGRATE="$ROOT/scripts/verify-wallet-migration.sh"
CORE="$ROOT/app/src/main/java/org/stohncoin/wallet/wallet/CoreWalletMigration.kt"
BACKUP="$ROOT/app/src/main/java/org/stohncoin/wallet/wallet/WalletBackupManager.kt"
fail(){ echo "RESTORE CROSS-CHECK: FAIL: $*" >&2; exit 1; }
pass(){ echo "RESTORE CROSS-CHECK: PASS: $*"; }
[[ -f "$MIGRATE" && -f "$CORE" && -f "$BACKUP" ]] || fail "restore or migration source file missing"

# Subcommands are the last token on their line in the script, and quoted arguments in the app.
script_line(){ grep -nE -- "-wallet=[^ ]+ .*\b$1\s*$" "$MIGRATE" | head -1 | cut -d: -f1 || true; }
app_line(){ grep -nF -- "\"$1\"" "$CORE" | head -1 | cut -d: -f1 || true; }

prev=0
for sub in info dump createfromdump; do
  pos=$(app_line "$sub")
  [[ -n "$pos" ]] || fail "CoreWalletMigration no longer runs '$sub'"
  (( pos > prev )) || fail "CoreWalletMigration runs '$sub' out of order"
  prev=$pos
done
prev=0
for sub in info dump createfromdump; do
  pos=$(script_line "$sub")
  [[ -n "$pos" ]] || fail "verify-wallet-migration.sh no longer runs '$sub'"
  (( pos > prev )) || fail "verify-wallet-migration.sh runs '$sub' out of order"
  prev=$pos
done

# Restore must land on the same target format the script verifies.
grep -qF -- '-format=sqlite' "$MIGRATE" || fail "script no longer creates a SQLite wallet"
grep -qF -- 'walletFormat = "sqlite"' "$CORE" || fail "restore no longer creates a SQLite wallet"

# The encrypted record dump stays inside the throwaway directory and is removed after use.
grep -qF -- '-dumpfile="$TMP/wallet.dump"' "$MIGRATE" || fail "script dump escapes its temp directory"
grep -qF -- "trap 'rm -rf \"\$TMP\"' EXIT" "$MIGRATE" || fail "script temp directory is not cleaned up"
grep -qF -- 'File(workspace, "wallet.dump")' "$CORE" || fail "restore dump escapes its workspace"
[[ $(grep -cF -- 'workspace.deleteRecursively()' "$CORE") -ge 2 ]] || fail "restore workspace is not cleaned up"

# Restore hands Core only what it decrypted into app-private cache, and deletes it afterwards.
grep -qF -- 'File.createTempFile("stohn-restore-", ".snapshot", context.cacheDir)' "$BACKUP" \
  || fail "decrypted snapshot is not staged in app cache"
grep -qF -- 'File.createTempFile("stohn-restore-wallet-", ".dat", context.cacheDir)' "$BACKUP" \
  || fail "restored wallet is not staged in app cache"
grep -qF -- 'stagedWallet.delete()' "$BACKUP" || fail "staged wallet is not deleted"
grep -qF -- 'CoreWalletMigration(runtime).import(contents.wallet, destination)' "$BACKUP" \
  || fail "restore no longer imports through CoreWalletMigration"

# Backup writes exactly the entries restore accepts, through the reader's own constants.
grep -qF -- 'ZipEntry(ENTRY_WALLET)' "$BACKUP" || fail "backup wallet entry is not the restore constant"
grep -qF -- 'ZipEntry(ENTRY_MANIFEST)' "$BACKUP" || fail "backup manifest entry is not the restore constant"

# Neither path may reach for the plaintext export that KeyExportGuard refuses.
! grep -qF -- 'dumpwallet' "$MIGRATE" "$CORE" "$BACKUP" || fail "plaintext key export reappeared"
pass "restore path matches verify-wallet-migration.sh"
