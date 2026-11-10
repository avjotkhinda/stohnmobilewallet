# Architecture

## High-level layout

The Stohn Wallet is a self-contained Android application with no third-party telemetry or remote signer. Its three logical layers are:

| Layer | Responsibility |
|---|---|
| **UI** | Compose-based screens: lock screen, send/receive, settings, transaction detail. All state is owned locally; RPC calls go through the embedded node. |
| **Runtime** | An isolated execution environment for the Bitcoin full node and wallet tools. Currently provided by **PRoot** (user-space emulation of arm64 Linux) or a future **native backend** (`libstohncoind.so`). The runtime is selected via `RuntimeBackendFactory`, which verifies hash pins from `RuntimeManifest` before switching. |
| **Node** | A loopback RPC-enabled `stohncoind` instance managed by `NodeController`. Foreground service, single-instance, cookie auth, ephemeral port. Start/stop controls live in the node screen. |

## Runtime backend interface

`RuntimeBackend` (interface in `runtime/RuntimeBackend.kt`) defines the contract the app needs from a node environment:

- `rpcConnection()` → connection config (host, port, cookie path)
- `walletToolArgs()` → arguments for `stohnd` wallet commands
- `backupSnapshot()` → path to a backup snapshot archive
- `inspectWallet(path)` → open a wallet via the core tool

Two implementations exist:

- **`EmbeddedLinuxRuntime`** — wraps PRoot + a Debian rootfs. The PRoot binary and rootfs `deb` are downloaded, hash-verified, and extracted by `scripts/build-proot-android-bundle.sh` + `scripts/integrate-proot-bundle.sh`. Pin-gated through `RuntimeManifest.PROOT_SHA256`, `PROOT_LOADER_SHA256`, `LIBTALLOC_SHA256`, `LIBANDROID_SHMEM_SHA256`.
- **`NativeRuntime`** — ships as `arm64-v8a/libstohncoind.so`. When both native binaries match their pins in `RuntimeManifest` the factory selects it; otherwise PRoot is used. The native backend is the long-term goal to eliminate glibc/PRoot overhead and enable store distribution.

## Hash pinning discipline

Every runtime artifact is hash-pinned in `app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt`:

- `PROOT_SHA256` / `PROOT_LOADER_SHA256` — the PRoot `.deb` and loader `.so`
- `LIBTALLOC_SHA256` / `LIBANDROID_SHMEM_SHA256` — the supporting `.so` files

The release pipeline (`build-proot-android-bundle.sh` → `integrate-proot-bundle.sh`) downloads the exact `.deb` versions pinned in `runtime/proot/TERMUX-PACKAGE-LOCK.json`, extracts the `.so` files, re-patches `libproot.so` with `patchelf --replace-needed libproot.so \`$ORIGIN\``, and verifies `readelf -d` DT_NEEDED for `$ORIGIN`. A hash mismatch is a hard failure — the pipeline will not proceed.

New artifacts require a **new pin**; there is no silent swap path. The runtime-update path (M3 #4) adds signed manifest verification on top of this discipline.

## Node lifecycle

- `NodeController` foreground service owns the `stohncoind` process.
- UI `MainActivity` binds to the service and displays sync progress (`syncPercent`).
- User controls (Start/Stop) go through `LockGate` — a PIN is required to start unless `startUnlessStopped` is set.
- Stopping the node clears `blocks`, `chainstate`, and `indexes` (never `wallets` or config) via the tested `NodeStorage` allowlist.