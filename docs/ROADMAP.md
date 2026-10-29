# Stohn Wallet — Roadmap

Current status, remaining work, and the order to do it in.

| Field | Value |
| --- | --- |
| App version | `0.28.2-fullmode` |
| Platform | Android, `arm64-v8a` only, minSdk 26 / targetSdk 36 |
| Toolchain | AGP 9.4.0, Gradle 9.6.0 (wrapper), JDK 17 |
| Maturity | Pre-release prototype. **Not for real funds.** |

## Guiding constraints

1. The wallet must stay self-contained: local full node, local RPC on loopback, no third-party telemetry or remote signer.
2. Every runtime artifact is hash-pinned and verified before execution. New artifacts require a new pin, never a bypass.
3. Distribution is sideload-first. Bundled PRoot and glibc binaries make store distribution a separate decision, not an assumption.
4. Security work outranks features. A feature that weakens the runtime boundary does not ship.

## What works today

| Area | State |
| --- | --- |
| Embedded runtime | PRoot + loader + libtalloc + libandroid-shmem verified against `RuntimeManifest`; Debian rootfs and Stohn Core 3.2 downloaded, hash-checked, extracted with traversal/symlink/size guards |
| Node lifecycle | Foreground service, single-instance `NodeController`, ephemeral loopback RPC port, cookie auth, restart-after-encrypt |
| Wallet | Create/load wallet, send with fee estimate, transactions list, encrypt passkey, lock wallet |
| Backup | AES-GCM container, PBKDF2-SHA256 600k, zip payload with SHA-256 manifest, atomic writes, restore path validation |
| Import/migration | `wallet.dat` inspect + migrate through the official Core tool inside PRoot |
| QR | Receive QR generation, QR scanning on send (`stohn:`/URI or bare address, optional amount), address share sheet |
| App lock | PIN gate on cold start, biometric prompt with device-credential fallback |
| CI | Build job (audit, fullmode verify, unit tests, debug APK) and release job (bundle build, integrate, preflight, sign, verify, upload) |

## Known gaps

- `docs/` is absent from the repository even though `FILES.txt` lists 14 files under it. `FILES.txt` is stale on both sides.
- `security/WalletKeystore.kt` and `wallet/WalletManager.kt` are unused. Dead security code is a liability.
- PIN is stored as an unsalted SHA-256 hash. A 4–6 digit PIN vs unsalted SHA-256 is milliseconds to brute force if prefs are ever exposed.
- Biometric unlock can be enabled without a PIN set, so the fallback path can be empty.
- The lock only gates cold start; returning from background does not re-lock.
- No `FLAG_SECURE`: the wallet is visible in the recents thumbnail and screen recordings.
- No attempt throttling on PIN entry.
- No transaction detail view; amounts flow through `Double`.
- Unit tests cover backup crypto and container parsing only. No instrumented tests.
- Release signing secrets are still unset, so the release job builds but skips.

## M0 — App-lock correctness (do first)

Small, self-contained, and the current weak point.

- [ ] Replace the unsalted PIN hash with a salted, slow KDF (PBKDF2-HMAC-SHA256, ≥200k iterations, 16-byte random salt) or a Keystore-bound verifier. Store only the verifier.
- [ ] Add attempt throttling: exponential backoff after 5 failures, wipe-after-N option documented.
- [ ] Require a PIN before biometric unlock can be enabled; disable biometric whenever the PIN is cleared.
- [ ] Re-lock on background: lock in `onStop`/lifecycle observer after a short grace period (configurable, default immediately on background).
- [ ] Add `WindowManager.LayoutParams.FLAG_SECURE` to `MainActivity` (and the biometric prompt host) so screenshots and recents previews are blocked.
- [ ] Redact notification content for the node service.
- [ ] Delete or wire up `WalletKeystore` and `WalletManager`; do not leave unused security code.
- [ ] Tests: PIN verify/clear, throttling state machine, lock state transitions (JVM where possible).

**Done when:** cold start and resume both gate on authenticator, a stolen prefs file does not yield a PIN offline in seconds, and no unused security classes remain.

## M1 — Wallet UX and amount correctness

- [ ] Transaction detail screen: tap a row to see txid, direction, amount, fee, confirmations, block height, copy-txid and copy-address.
- [ ] Replace `Double` amounts end to end with `Long` satoshis (or `BigDecimal`) parsed at the UI edge; format for display only.
- [ ] Fee presets (economy / normal / priority) from `estimatesmartfee`, with the current auto path as the default.
- [ ] Confirmation step before broadcast: recipient, amount, fee, total, and a truncated address to compare against.
- [ ] Address book with labels, saved locally and backed up inside the encrypted container.
- [ ] `stohn:` URI support outbound: generate QR with `stohn:<addr>?amount=<amt>&label=<label>`, and parse `label` on scan.
- [ ] Share the receive QR as an image (FileProvider + `ACTION_SEND`), not just the address string.
- [ ] Split `StohnWalletScreen.kt` (≈900 lines) into per-screen files; the single-file screen is the main source of merge pain.

**Done when:** no currency value is ever a `Double`, and every screen has a reachable detail/confirm step.

## M2 — Backup and recovery

- [ ] Backup reminder: record last-successful-backup time and prompt after N days or M transactions.
- [ ] Test-restore flow: verify a chosen backup decrypts and unpacks to a valid wallet before the user relies on it.
- [ ] Container v2: Argon2id or scrypt KDF, chunked AEAD so large payloads stay bounded, version field for migration.
- [ ] Include wallet name, creation height, and app version in the manifest; verify on restore.
- [ ] Document and enforce that `dumpwallet` output (plaintext keys) never reaches app storage.
- [ ] Cross-check restore against `verify-wallet-migration.sh` in CI.

**Done when:** a user can prove a backup restores before they need it, and the container format is versioned.

## M3 — Node and runtime

- [ ] Start/stop controls and sync progress percentage in the node screen (currently status-only).
- [ ] Surface disk usage and pruned-mode option.
- [ ] NDK-native backend to replace PRoot, behind the existing `RuntimeBackend` interface. This is the long-term fix for the glibc/PRoot overhead and the store-policy problem.
- [ ] Runtime update path: new manifest pins shipped together with a signature check, no silent swaps.
- [ ] `PROOT_TMP_DIR` / `TMPDIR` cleanup on failure and on app start.

## M4 — Testing and CI

- [ ] Instrumented tests (Compose UI): lock screen unlock, PIN failure path, send flow with a mocked RPC.
- [ ] Property/fuzz tests for `RuntimeInstaller` path handling and `BackupContainer` entry handling.
- [ ] `lint` and a static analysis gate (detekt or ktlint) in the build job.
- [ ] Test coverage threshold for `wallet/` and `security/` packages.
- [ ] Device matrix: at least two physical arm64 devices across Android 8 → 16, plus one emulator run.
- [ ] Pin GitHub Actions to commit SHAs and enable Gradle dependency verification metadata.

## M5 — Release engineering

- [ ] Configure `STOHN_RELEASE_STORE_FILE_B64`, `STOHN_RELEASE_STORE_PASSWORD`, `STOHN_RELEASE_KEY_ALIAS`, `STOHN_RELEASE_KEY_PASSWORD` so the release job emits a signed APK.
- [ ] Keep the signing key offline; document key custody and rotation.
- [ ] Reproducible-build check across two clean runners (the bundle already builds deterministically).
- [ ] SBOM and a signed checksum file published next to each APK.
- [ ] Decide distribution: sideload-only first; store submission only after the runtime policy question is settled.
- [ ] Independent security review before any real-funds claim.

## M6 — Documentation and housekeeping

- [ ] Restore the `docs/` tree: architecture, runtime execution, threat model, build and recovery guides.
- [ ] Regenerate `FILES.txt` from the working tree; add a check that fails when it drifts.
- [ ] Refresh `SECURITY.md` to describe the app lock, backup container, and known gaps above.
- [ ] Add `CONTRIBUTING.md` and issue templates.

## Milestones

| Milestone | Contents | Exit criterion |
| --- | --- | --- |
| **M0** | App-lock correctness | Lock is enforced on start and resume; PIN storage is KDF-hardened |
| **M1** | Wallet UX + amount correctness | No `Double` amounts; confirm/detail screens exist |
| **M2** | Backup v2 + test restore | User can verify a backup restores |
| **M3** | Node UX + native backend | Node controllable from UI; PRoot optional |
| **M4** | Test + CI depth | Instrumented tests and static analysis gate in CI |
| **M5** | Signed, reproducible release | Signed APK from CI with published checksums |
| **M6** | Docs complete | `docs/` restored and `FILES.txt` accurate |

## Explicitly out of scope for now

- Multi-chain support, token/asset layer, or smart contracts.
- Cloud backup, cloud sync, or any remote key custody.
- Desktop or iOS clients.
- On-chain analytics or price feeds.

## Notes on sequencing

M0 is deliberately first: it is days of work, it closes a real weakness, and it does not touch the runtime boundary. M1 and M2 can proceed in parallel once M0 lands. M3's native backend is the largest single item and should not block M1/M2. M5 stays blocked until the M0–M2 items are done and a device matrix has passed, because shipping a signed APK of a prototype invites exactly the misuse `SECURITY.md` warns against.
