# Build guide

## Prerequisites

- **JDK 17** (or JDK 21 as used in CI) — `export JAVA_HOME=/path/to/jdk`
- **Gradle 9.6.0** wrapper (`gradlew`) — AGP 9.4.0
- **Android SDK** — `ANDROID_SDK_ROOT` must point to a valid SDK installation
- **Termux repo access** (for PRoot bundle builds) — the release job pulls `proot`, `libtalloc`, `libandroid-shmem`, and `libtalloc` from `https://packages.termux.dev/apt/termux-main`; pinned versions must still be published there

## Daily development

```bash
# Compile debug APK (including unit tests)
./gradlew :app:assembleDebug --console=plain

# Run unit tests
./gradlew :app:testDebugUnitTest --console=plain

# Full debug build + tests
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

## Release pipeline (CI)

The `android-build` GitHub Actions workflow has two jobs:

1. **`build`** — green when all steps pass:
   - Audit (`tools/security-audit.sh`)
   - Full-mode repair verification (`tools/verify-fullmode-repair.sh`)
   - Restore path check (`scripts/verify-restore-path.sh`)
   - `assembleDebug`
   - APK artifact verification (`scripts/verify-apk-artifact.sh`)
   - Files-drift gate (`tools/verify-files-list.sh`)
   - APK sidecar check (`tools/verify-apk-sidecars.sh`)

2. **`release`** — currently fails at "Build pinned PRoot Android bundle" until the PRoot package pin is updated (see `runtime/proot/TERMUX-PACKAGE-LOCK.json`). When the bundle step succeeds, the remaining steps are:
   - `apt install patchelf binutils dpkg-dev`
   - `scripts/build-proot-android-bundle.sh` (downloads pinned packages, extracts, patchelf‑patches, emits `SHA256SUMS`)
   - `scripts/integrate-proot-bundle.sh --write` (derives `PROOT_SHA256` etc. and writes new `RuntimeManifest` pins)
   - `scripts/release-preflight.sh` (version-strings, hash checks, JNI libs presence)
   - Keystore decode (skipped when release secrets are unset)
   - `assembleRelease`
   - `scripts/verify-apk-artifact.sh` (native hash checks against `RuntimeManifest`)
   - Upload release APK

## Adding a new runtime artifact pin

When a new PRoot version (or any of the four pinned `.so` packages) must be updated:

1. **Update `runtime/proot/TERMUX-PACKAGE-LOCK.json`** — change the `version`, `path`, and `sha256` for the affected package.
2. **Rebuild the bundle**:
   ```bash
   T=$(mktemp -d)
   bash scripts/build-proot-android-bundle.sh "$T/proot-build" "$T/proot-bundle"
   ```
3. **Re-integrate pins**:
   ```bash
   bash scripts/integrate-proot-bundle.sh "$T/proot-bundle" --write
   ```
   This rewrites `RuntimeManifest.PROOT_SHA256`, `PROOT_LOADER_SHA256`, `LIBTALLOC_SHA256`, `LIBANDROID_SHMEM_SHA256` and copies the new `.so` files into `app/src/main/jniLibs/arm64-v8a/`.
4. **Update provenance and notices**:
   - `scripts/build-proot-android-bundle.sh` — update the "PRoot upstream source:" line
   - `app/src/main/assets/legal/third_party_runtime_notices.md` — update the version tag
   - `THIRD_PARTY_NOTICES/README.md` — same
5. **Commit and push** — the two required footers are `🤖 Generated with Codebuff` and `Co-Authored-By: Codebuff <noreply@codebuff.com>`.

## Local verification checklist (run after any pin change)

```bash
# 1. Bundle + integrate
bash scripts/build-proot-android-bundle.sh "$T/proot-build" "$T/proot-bundle"
bash scripts/integrate-proot-bundle.sh "$T/proot-bundle" --write

# 2. All checks pass
bash tools/verify-fullmode-repair.sh    # PRESUMED PASS
bash scripts/release-preflight.sh       # should exit 0
bash tools/security-audit.sh            # should exit 0
bash scripts/verify-restore-path.sh     # should exit 0
./gradlew :app:testDebugUnitTest --console=plain  # BUILD SUCCESSFUL, 0 failures
bash scripts/verify-apk-artifact.sh app/build/outputs/apk/debug/app-debug.apk  # PASS
bash tools/verify-files-list.sh         # should exit 0
bash tools/verify-apk-sidecars.sh       # should exit 0
```