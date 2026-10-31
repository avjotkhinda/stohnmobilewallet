# Security status

Stohn Wallet is an experimental, pre-release Android full-node wallet prototype. It has not received an independent security audit and has not been validated on its target Android device. **Do not use it to store or transfer real funds or production wallet keys.**

## Controls present in the source

- Android app-data backup is disabled in the manifest.
- PRoot, its loader, native dependencies, and downloaded runtime artifacts have pinned SHA-256 values and are checked by the installer/runtime before use.
- Core RPC binds to `127.0.0.1` and uses Core's per-instance `.cookie` authentication. The app reads that cookie from its private data directory.
- Address-book labels live only in app-private storage (`filesDir/addressbook/labels.json`) and travel
  inside the same password-encrypted backup container as `wallet.dat`, so a backup carries them
  without writing them anywhere plaintext.
- App lock: the PIN is stored only as a salted PBKDF2-HMAC-SHA256 verifier (210k iterations, 16-byte
  random salt) inside `EncryptedSharedPreferences`; the old unsalted digest is still accepted but is
  rewritten on the first correct entry. Failed attempts are throttled with an exponential backoff
  after five failures, persisted across restarts, and biometric unlock cannot be enabled unless a
  PIN backs it (clearing the PIN clears biometric too).
- The lock gates cold start and every return from background after a configurable grace period
  (immediately / 30 seconds by default / 2 minutes), so a pocketed phone re-locks while a short trip
  into the biometric prompt, scanner or file picker does not bounce the user back to the lock.
  `FLAG_SECURE` is set on the activity, and the node-service notification is `VISIBILITY_SECRET`
  with a coarse public version, so neither wallet content nor node state leaks into screenshots,
  the recents thumbnail or the lock-screen shade.
- Wallet encryption and locking are available through Stohn Core.

These controls address specific risks; they do not establish that the app or its build process is secure. In particular, app sandbox protections do not protect wallet files from a compromised or rooted device, and wallet encryption depends on the user choosing and protecting a strong passphrase.

## Lost device, forgotten PIN, wipe policy

There is deliberately no remote wipe and no in-app "wipe after N failed attempts" button: anything
reachable over the network is also reachable by an attacker, and the wallet has no server to phone
home to. The supported responses are local:

- **Forgot the PIN:** clear the app's data (Android Settings → Apps → Stohn Wallet → Storage →
  Clear storage) or uninstall the app, then restore the encrypted backup with the backup
  passphrase. The PIN protects local access only; it is not a wallet passphrase and it cannot
  recover funds.
- **Lost or stolen device:** the throttle allows five free attempts and then caps an attacker at
  roughly four attempts per hour, so a 4-digit PIN is months of continuous effort and a 6-digit one
  is out of reach. The verifier itself lives in `EncryptedSharedPreferences` under an Android
  Keystore key, so copying the preference file off a non-rooted device yields nothing usable.
  Re-importing the backup from a fresh install is the recovery path; consider the device's own
  remote-wipe facility for the hardware.
- **Selling or recycling the device:** clear the app's data first. This removes the PIN verifier,
  the address book and the wallet files together, and nothing about the on-chain funds changes —
  they remain recoverable from the backup.

Documented here as the M0 decision: without a remote channel there is no code path to implement a
wipe-after-N, and adding one would weaken the offline threat model above.

## Release blockers

Before a release is recommended for real funds, the project needs an independent security review, a successful Android build and testing on supported physical devices, and verified repository and release-signing access controls. Source code and CI checks alone cannot enforce GitHub branch protection, require independent reviewers, or protect an offline signing key; those controls must be configured and verified by repository maintainers.

## Reporting

Please do not publish exploitable wallet vulnerabilities as public issues. Contact the project maintainers privately with a concise description, affected version or commit, and reproduction steps. No formal security response SLA is currently established.
