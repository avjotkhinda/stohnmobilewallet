# Threat model

## Trust boundaries

| Boundary | Trusted | Reason |
|---|---|---|
| **App storage** (shared prefs, files dir) | Partial | On a compromised or rooted device, an attacker with file-system access can read stored PIN verifiers, backup containers, and wallet data. This is why the app is classified "not for real funds." |
| **Keystore PIN verifier** | High | Salted PBKDF2-HMAC-SHA256 with ≥200k iterations; brute-force is infeasible on unmodified hardware. The verifier never leaves Keystore; only the verification result is returned. |
| **Runtime backend (PRoot / native)** | Medium | The PRoot binary and all supporting `.so` files are hash-pinned in `RuntimeManifest`. A hash mismatch is a hard failure. However, PRoot runs with the same UID as the app; a root device can intercept its I/O. |
| **Network/RPC** | Low | RPC traffic travels over loopback only. The cookie-based auth scheme prevents unauthorized access, but a kernel-level network attacker on the same host could potentially intercept. |
| **Backup container** | Medium | AES-GCM (or Argon2id/scrypt for v2) encrypted; the encryption key derives from the user's PIN via PBKDF2/SKLearn. Backup container format is versioned (`STOHN-BACKUP-1` / `STOHN-BACKUP-2`). Truncation and trailing-data authentication are enforced in `BackupVerifier`. |
| **Signing key** | Offline | Release signing keys are never stored on the device. They exist only in the CI environment and are never checked into version control. |

## Attack surface summary

- **Rooted device**: an attacker can read `shared_prefs`, unzip backup containers, and intercept PRoot I/O. Pin verification provides defense-in-depth but cannot be relied upon.
- **Malicious `.so` swap**: prevented by SHA-256 pinning in `RuntimeManifest`. The update path (M3 #4) adds ECDSA P-256 signature verification on the staged manifest before pins are resolved.
- **Backup restoration**: the `Test Backup` flow decrypts and unpacks into app cache (never the wallet directory) and, when the node is running, invokes `CoreWalletMigration.inspect` to confirm Core can read the wallet.
- **Notification content**: notification visibility is gated by `VISIBILITY_SECRET`; sensitive node sync details are redacted.

## Security goals (why the app is "not for real funds")

- PIN storage is KDF-hardened but can be extracted from a rooted device's `/data/data/<pkg>/shared_prefs`.
- Runtime artifacts are hash-pinned but not signed end-to-end until the M3 #4 update path is activated with a published manifest + public key.
- Backup containers are encrypted but the key is derivable from the user's PIN; on a compromised device, the attacker can re-derive the key.
- No remote signer or cloud sync; all state is local. This is a local-full-node prototype, not a production custody product.