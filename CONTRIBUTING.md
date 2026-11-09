# Contributing

This is an experimental Android full-node wallet. Read [SECURITY.md](SECURITY.md) first: the project
is not ready for real funds, and that shapes what a useful contribution looks like. Bug reports,
threat-model corrections and test coverage are more valuable right now than new screens.

## Before you start

- Build the app: [docs/BUILD.md](docs/BUILD.md) covers the JDK, Android SDK/NDK, `JAVA_HOME` and the
  Gradle wrapper.
- Understand the runtime: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains the PRoot and
  native-backend split, and [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) explains what the app
  claims to protect.
- Read [docs/ROADMAP.md](docs/ROADMAP.md) for what is done, what is open, and what is deliberately
  out of scope.

## The checks that must pass

Every change is expected to pass, in this order:

```bash
./gradlew :app:testDebugUnitTest --console=plain
./gradlew detekt --console=plain
./gradlew :app:lintDebug --console=plain
./tools/security-audit.sh
./tools/verify-files-list.sh
./scripts/verify-restore-path.sh
bash scripts/release-preflight.sh
```

`detekt` is the static analysis gate and `lintDebug` the Android Lint gate; both run in CI. The
detekt configuration (`config/detekt/detekt.yml`) deliberately switches off a set of style and
complexity rules — `MagicNumber`, `WildcardImport`, the complexity group, and the exception rules —
because the findings they raise are debt this project has not paid down yet. Each entry records how
many findings it was hiding when the gate went in. Turning one back on is a welcome change; adding a
new suppression is not.

`verify-files-list.sh` compares `FILES.txt` against `git ls-files`. If you add, rename or delete a
file, regenerate the inventory rather than hand-editing it:

```bash
git ls-files | sed 's|^|./|' | LC_ALL=C sort > FILES.txt
```

## Ground rules

- **Do not weaken a check to make it pass.** The security audit, the file inventory, the restore-path
  check and the pin validation gates exist because the code drifted past them before. Fix the cause;
  do not add a suppression, a blanket `allow` rule or a skipped test.
- **Pins are load-bearing.** `RuntimeManifest` holds SHA-256 values for PRoot, its loader and its
  shared libraries, and for the rootfs and Stohn Core. Changing a version means re-pinning **and**
  proving the new artifact reproduces the hash — see
  `runtime/proot/proot-version-runbook.json` for the shape of that record. Never accept a
  "close enough" binary.
- **No plaintext key material.** Key export is restricted by `KeyExportGuard` to `info`, `dump` and
  `createfromdump`. A change that reaches `dumpwallet`, `dumpprivkey`, `dumphdinfo` or their
  relatives is rejected regardless of the reason.
- **Wallet amounts stay integral.** Amounts travel as satoshi longs end to end; do not reintroduce
  floating point at any boundary.
- **Commit to `main`, do not force-push.** Keep one concern per commit and write the reason in the
  message, not just the change.

## Where new code goes

| Change | Place |
| --- | --- |
| Wallet behaviour, amounts, address book | `app/src/main/java/org/stohncoin/wallet/wallet/` |
| App lock, PIN, throttling | `app/src/main/java/org/stohncoin/wallet/security/` |
| Runtime, PRoot, native backend, pins | `app/src/main/java/org/stohncoin/wallet/runtime/` |
| Node lifecycle, RPC, foreground service | `app/src/main/java/org/stohncoin/wallet/core/` |
| Compose UI | `app/src/main/java/org/stohncoin/wallet/ui/` |
| Tests | `app/src/test/java/org/stohncoin/wallet/` |

## Tests

Unit tests are the current bar, and they are expected to cover the failure path as well as the happy
one — a guard that has never been observed refusing something is not yet known to work. When you add a
guard, add the test that watches it say no:

```bash
./gradlew :app:testDebugUnitTest --console=plain
```

Property-style tests (`RuntimeInstallerPathFuzzTest`, `BackupContainerFuzzTest`) drive their subjects
with seeded adversarial input; extend them rather than writing one-off cases for each new attack.

## Reporting a vulnerability

Do not open a public issue for an exploitable wallet vulnerability. Contact the maintainers privately
with a description, the affected version or commit, and reproduction steps — see the Reporting
section of [SECURITY.md](SECURITY.md).
