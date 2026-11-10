# Backup and recovery guide

## Backup container format

The wallet backup is an encrypted container stored at `filesDir/backup/`. Two versions exist:

### Version 1 (`STOHN-BACKUP-1`)

- KDF: PBKDF2-HMAC-SHA256, 600k iterations, 16-byte random salt
- Symmetric cipher: AES-256-GCM
- Payload: zip archive containing:
  - `wallet.dat` (Core output, encrypted)
  - `labels.json` (address‑book labels, included since M2)
  - `MANIFEST.sha256` (first line: `app_version wallet_name creation_height checksum`)

### Version 2 (`STOHN-BACKUP-2`) — current

- KDF: scrypt N=2^15/r=8 (RFC 7914 vectors), 1 MiB AES‑256‑GCM chunks
- AAD per chunk: header || chunk-index || chunk-length
- Terminal chunk authenticates trailing data (truncation‑resistant)
- Payload structure:
  - Header: `magic` + `version` (2) + `salt_len` + `kdf_id` + `params`
  - Chunks: `length||ciphertext||tag` (AAD = header||index||length)
  - `MANIFEST.sha256` (same format as v1)

## Creating a backup

### From the UI

1. Open **Settings → Backup**.
2. Tap **Export** (or **Create Backup**).
3. The `BackupReminder` records the timestamp and increments the send counter.
4. A prompt appears after N days (default 30) or M transactions (default 20).

### From the command line (dev)

```bash
# Via the BackupVerifier/KT helper; the exact command depends on the KT API.
# Typically: invoke the BackupManager which writes to filesDir/backup/
```

## Testing a backup before reliance

### From the UI

1. Open **Settings → Backup**.
2. Tap **Test Backup** next to the exported container.
3. `BackupVerifier` decrypts and unpacks into the app cache (never the wallet directory).
4. If the node is running, `CoreWalletMigration.inspect` opens the wallet via Core itself — "valid" means Core can read it.
5. Mismatches between the manifest (app version, wallet name, creation height, checksum) and the current app state are displayed; the user must resolve them before the backup is considered trustworthy.

### From the command line (dev)

```bash
# The test flow is exercised by the instrumented test
# BackupRestoreRoundTripTest — it creates a backup, tests it, and asserts validity.
```

## Restoring from a backup

1. Place the backup `.zip` (v1) or `.enc` (v2) file in `filesDir/backup/` (or select it via the UI).
2. The `BackupVerifier` attempts decryption using the user's PIN.
3. If decryption succeeds, the contents are unpacked into `app cache/` (v1) or restored through the `BackupRestoreManager` (v2).
4. **Never** restore directly into the wallet directory from untrusted sources — the verified path always uses the cache or the node‑assisted migration.
5. After restoration, the wallet service restarts with the recovered wallet. The `NodeController` may need to be restarted to pick up the new chain state.

## Known limitations

- Pre‑v2 backups (v1) still restore, but they lack the chunked‑AEAD authenticity of `STOHN-BACKUP-2`.
- The PIN is required to both create and verify a backup. If the PIN is forgotten, there is no backdoor — the backup is unrecoverable without the PIN.
- Restore does NOT require the node to be running; it works offline. If the node IS running, `CoreWalletMigration.inspect` provides an extra validity check.