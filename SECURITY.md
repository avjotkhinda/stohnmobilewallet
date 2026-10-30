# Security status

Stohn Wallet is an experimental, pre-release Android full-node wallet prototype. It has not received an independent security audit and has not been validated on its target Android device. **Do not use it to store or transfer real funds or production wallet keys.**

## Controls present in the source

- Android app-data backup is disabled in the manifest.
- PRoot, its loader, native dependencies, and downloaded runtime artifacts have pinned SHA-256 values and are checked by the installer/runtime before use.
- Core RPC binds to `127.0.0.1` and uses Core's per-instance `.cookie` authentication. The app reads that cookie from its private data directory.
- Wallet encryption and locking are available through Stohn Core.

These controls address specific risks; they do not establish that the app or its build process is secure. In particular, app sandbox protections do not protect wallet files from a compromised or rooted device, and wallet encryption depends on the user choosing and protecting a strong passphrase.

## Plaintext key export

The app never asks Stohn Core for an unencrypted key, seed, or `dumpwallet` file:

- The wallet tool is invoked only through an allowlist (`info`, `dump`, `createfromdump`) in
  `runtime/KeyExportGuard`, so `dumpwallet` cannot be reached from app code. The record `dump`
  used for migration carries encrypted key records, and its workspace is deleted afterwards.
- Every Core RPC call passes the same guard, which rejects `dumpwallet`, `dumpprivkey`,
  `dumphdinfo`, `dumpmasterprivkey` and `dumpmasterkey` before a request is sent.
- `scripts/verify-wallet-migration.sh` uses the same record `dump` and keeps its output in a
  temporary directory that the script removes on exit.

The rule is exercised by `KeyExportGuardTest`: a backup or migration copies only the encrypted
`wallet.dat`, so no code path can write plaintext key material into app storage.

## Release blockers

Before a release is recommended for real funds, the project needs an independent security review, a successful Android build and testing on supported physical devices, and verified repository and release-signing access controls. Source code and CI checks alone cannot enforce GitHub branch protection, require independent reviewers, or protect an offline signing key; those controls must be configured and verified by repository maintainers.

## Reporting

Please do not publish exploitable wallet vulnerabilities as public issues. Contact the project maintainers privately with a concise description, affected version or commit, and reproduction steps. No formal security response SLA is currently established.
