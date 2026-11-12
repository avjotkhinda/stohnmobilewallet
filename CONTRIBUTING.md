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
./gradlew :app:jacocoDebugUnitTestCoverage --console=plain
./gradlew detekt --console=plain
./gradlew :app:lintDebug --console=plain
./tools/security-audit.sh
./tools/verify-files-list.sh
./scripts/verify-restore-path.sh
bash scripts/release-preflight.sh
```

Gradle verifies every downloaded artifact against `gradle/verification-metadata.xml`, so a build
fails rather than trusting an unfamiliar jar. Bumping a dependency therefore needs the new hashes
recorded; do it deliberately and read what changed:

```bash
./gradlew --write-verification-metadata sha256 help assembleDebug :app:testDebugUnitTest detekt :app:lintDebug
git diff gradle/verification-metadata.xml
```

Commit the resulting change alongside the version bump. Never delete entries or add
`--dependency-verification=lenient` to get a build through — that is the check doing its job.

`detekt` is the static analysis gate and `lintDebug` the Android Lint gate; both run in CI. The
detekt configuration (`config/detekt/detekt.yml`) deliberately switches off a set of style and
complexity rules — `MagicNumber`, `WildcardImport`, the complexity group, and the exception rules —
because the findings they raise are debt this project has not paid down yet. Each entry records how
many findings it was hiding when the gate went in. Turning one back on is a welcome change; adding a
new suppression is not.

Unit tests and lint never run R8 or the release signing configuration, so a pull request also gets a
`release-smoke` job: it generates a throwaway key, runs `assembleRelease`, verifies the APK payload
against `RuntimeManifest` and generates the SBOM. That is the cheap way to catch a missing shrinker
rule — the failure mode that broke the release build once without any red test. Run it locally the
same way when you touch `app/proguard-rules.pro`, the signing config or the runtime pins:

```sh
keytool -genkeypair -keystore /tmp/smoke.jks -storetype PKCS12 -storepass smoke123 \
  -keypass smoke123 -alias smoke -keyalg RSA -keysize 2048 -validity 2 -dname "CN=smoke"
STOHN_RELEASE_STORE_FILE=/tmp/smoke.jks STOHN_RELEASE_STORE_PASSWORD=smoke123 \
STOHN_RELEASE_KEY_ALIAS=smoke STOHN_RELEASE_KEY_PASSWORD=smoke123 \
  ./gradlew assembleRelease
bash scripts/verify-apk-artifact.sh app/build/outputs/apk/release/app-release.apk
```

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

## Fetching and pushing

`git fetch` only updates `origin/main` when the clone has a fetch refspec. Without one it downloads
objects but never writes `refs/remotes/origin/*`, so `git status`, `git log origin/main` and the
fast-forward hint from `git remote show origin` keep describing an older commit while GitHub has
moved on. That is a local configuration gap, not a stale remote, and it is repaired once per clone:

```bash
git config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'
git fetch origin
git rev-parse --verify origin/main
```

`git ls-remote origin refs/heads/main` asks GitHub directly and is the authority whenever the
remote-tracking ref disagrees with it. Linked worktrees share one `.git`, so the refspec is set once
for the whole checkout rather than per worktree.

Plain `git push` has no credentials in an agent or CI workspace and fails with `fatal: could not read
Username for 'https://github.com'`. Push through the authenticated git path — the workspace's git
integration, or a credential helper locally — and confirm the result against
`git ls-remote origin refs/heads/main` rather than a tracking ref that may never have been updated.

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

Code that needs an Android `Context` runs under Robolectric in the same unit-test task
(`AppAuthManagerTest`, `LockScreenTest`), so a lock policy or a screen's state does not have to wait
for a device. Two limits are worth knowing before reaching for it:

- Robolectric has no Android keystore, so `EncryptedSharedPreferences` cannot be built in a test.
  `AppAuthManager` therefore makes its decisions in `store`-taking overloads against a plain
  `SharedPreferences`; test those, and do not add a plaintext fallback to the Context path.
- Tests are pinned to API 35 in `app/src/test/resources/robolectric.properties` because CI runs
  JDK 17 and Robolectric needs JDK 21 to simulate API 36. Robolectric downloads its own runtime
  jars on the first run, which Gradle's dependency verification does not cover — expect that first
  run to be slow and to need network access.

## Release key custody and rotation

The release job signs with four repository secrets — `STOHN_RELEASE_STORE_FILE_B64`,
`STOHN_RELEASE_STORE_PASSWORD`, `STOHN_RELEASE_KEY_ALIAS` and `STOHN_RELEASE_KEY_PASSWORD` — and
skips the release build when they are absent, so a fork can never produce an APK that looks
official. The keystore itself never enters the repository, a build log or an issue.

**Custody.** Keep the single `release.jks` on two offline media in two physical locations, plus one
sealed paper copy of the passwords. Nothing else — not a laptop, not a cloud drive, not this
repository's secrets page alone — may be the only copy. The repository secrets are a copy for CI,
not the backup: losing them costs one push, losing the keystore costs every future upgrade, because
Android only installs an update signed by the same key.

**Access.** Two maintainers hold the passwords, and both must be present (or their sealed copies
retrieved) for a rotation. No individual should be able to sign alone. Record who holds what, and
where the media are, in the private key log, not in this repository.

**Routine use.** The keystore is decoded into `RUNNER_TEMP` for the length of one job and removed
with the runner. Never commit a decoded keystore, never pass it as an artifact, and never re-use a
runner that has held one for anything else.

**Rotation is not available to an app-signing key.** Android refuses an update whose signature
changed, so "rotating" the signing key means publishing a new application id or asking every user
to uninstall and lose their node data. Treat the key as permanent. Rotate the *passwords* only if a
copy is compromised, and then: take the keystore offline, change the store and key passwords with
`keytool -storepasswd` and `keytool -keypasswd`, re-upload all four secrets, and sign a release to
confirm it installs over the previous one. If the keystore itself is lost or leaked, the decision is
whether to ship a differently-named app or to stop shipping — write that decision down before the
moment arrives, because it cannot be made well under time pressure.

## Release checksum signing key

Published downloads are checkable because `SHA256SUMS` carries a detached Ed25519 signature: the
release job signs it and then verifies it exactly as a downloader would before anything is uploaded.
That key is **not** the APK keystore and must never be the same file. The keystore stays offline in
custody and signs the APK; this key exists only to authenticate the small files published beside it,
lives in CI as `STOHN_RELEASE_SIGNING_KEY_B64` (base64 of the private key PEM), and is pinned by the
repository variable `STOHN_RELEASE_SIGNING_KEY_FINGERPRINT` (SHA-256 of the public key's DER form).
Because the fingerprint is checked before signing and the signature is verified against the
published public key afterwards, a key swapped in the secret store fails the release instead of
signing quietly. A release whose APK is signed but whose checksums cannot be fails the job: an
unverifiable download is not published.

Create the key once, offline:

```sh
openssl genpkey -algorithm ed25519 -out stohn-release-signing-key.pem
chmod 600 stohn-release-signing-key.pem
openssl pkey -in stohn-release-signing-key.pem -pubout -out stohn-release-signing-key.pem.pub
openssl pkey -pubin -in stohn-release-signing-key.pem.pub -outform DER | openssl dgst -sha256 -r
```

The last command prints the fingerprint to put in the repository variable; `base64 -w0
stohn-release-signing-key.pem` is what goes into the secret. Back the key up with the keystore — with
the same two-media, two-location discipline, though losing it only costs re-signing future releases
rather than the app identity. Rotating it is allowed and announces itself: publish the new public
key, update the fingerprint variable, and sign every later release with the new key, since there is
no chain from old signatures to new ones.

Verify a download with what the release ships:

```sh
openssl pkeyutl -verify -pubin -inkey stohn-release-signing-key.pem -rawin \
  -in SHA256SUMS -sigfile SHA256SUMS.sig
sha256sum -c SHA256SUMS
```

## Upgrade compatibility

`runtime/release-baseline.json` records the last release that actually went out —
`package`, `versionCode`, `versionName` and the SHA-256 of the signing certificate. The release job
runs `scripts/verify-upgrade-compatibility.sh` against the APK it built and fails on each of the
three ways an update silently stops installing:

- a different package, which is a different app rather than an update;
- a `versionCode` that is not higher than the released one — the most common way a release is
  published and then reaches nobody, because the device and the store both refuse it;
- a different signing certificate, which Android will not install at all, so every user would have
  to uninstall and lose their node data. This is the check that catches "the CI secret now holds a
  different keystore".

`runtime/release-history.json` lists every release that went out, oldest first, and the baseline is
its newest entry: `--emit` appends both in one run, so the two files cannot describe different
releases. `tools/check-release-history.py` enforces that agreement, that versionCodes only increase
and that a version is never reused; the preflight runs it on every pull request and the release job
before releasing. The history is not bookkeeping — the app-version guard rejects any version literal
found there, so a copy of a release from three versions ago is caught, not just a copy of the current
one. A release that only reaches the baseline leaves those copies undetected.

The same rule runs earlier, in `scripts/release-preflight.sh`, which reads `applicationId`,
`versionCode` and `versionName` straight out of `app/build.gradle.kts` and is part of the `build` job.
Forgetting to bump `versionCode` therefore fails on the pull request, not after a release build.

`versionName` is also the single source of the app version: `RuntimeManifest.APP_VERSION` reads
`BuildConfig.VERSION_NAME` (which is why `buildConfig` is enabled in `app/build.gradle.kts`), so the
config file, the user agent and backup manifests all follow a one-line bump. Both preflight and
`tools/verify-fullmode-repair.sh` refuse a literal version in `RuntimeManifest`, because a second
copy is exactly what goes stale.

The release step also writes `release-baseline.json` for the APK being released, appends the same
release to `runtime/release-history.json`, and uploads both with the artifact. Forgetting to commit
them is caught: before building anything, `scripts/verify-baseline-committed.sh` compares the
committed baseline with the one inside the previous release's artifact, and fails when the tree still
records an older release. That comparison is why the baseline must be committed after publishing —
the artifact expires, and the record of the version goes with it. After publishing,
replace the tracked files with those, otherwise the next release is only checked for its package
name. The history already records the first release, `0.1` (`versionCode` 1), with an empty signer:
fill it from the key that APK was signed with — `--emit` on the published APK does it — because until
it is set a release signed by a different key is not caught.

## Reproducible releases

Two clean runners build every release: the `release` job publishes the APK, and the
`reproducibility` job builds the same commit again and compares the result with
`scripts/verify-reproducible-build.sh`. A mismatch fails the workflow.

The comparison is over what ships, not over the signed file, and that is a deliberate limit worth
knowing. apksigner randomises the nonce on every signature (RSA uses PSS, EC uses ECDSA), so the
APK Signing Block differs between two builds of identical source and the whole-file sha256 can never
match. Comparing the file would make the job red forever and prove nothing. Instead the script
compares every entry the build produced — by name and content, with `META-INF` signature files
excluded, since those are exactly what a signature rewrites — plus the SBOM's component list. A
difference there is real: a timestamp inside a dex, a resource ordering that depends on the host, a
path baked into a native library. The signed file's own sha256 is still what downloaders check, and
`SHA256SUMS` stays the record of it; it simply is not the thing reproducibility can be measured by.

Run it yourself on two builds:

```sh
bash scripts/verify-reproducible-build.sh build-a/app-release.apk build-b/app-release.apk \
  build-a/stohn-wallet-0.28.2-fullmode.cdx.json build-b/stohn-wallet-0.28.2-fullmode.cdx.json
```

Release builds run R8, so a rule missing from `app/proguard-rules.pro` breaks the release path while
debug builds and unit tests stay green. `assembleRelease` locally, with the four `STOHN_RELEASE_*`
variables pointing at any keystore, is the cheap way to catch that before pushing.

## Reporting a vulnerability

Do not open a public issue for an exploitable wallet vulnerability. Contact the maintainers privately
with a description, the affected version or commit, and reproduction steps — see the Reporting
section of [SECURITY.md](SECURITY.md).
