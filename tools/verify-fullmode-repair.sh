#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"

[[ -f "$MANIFEST" ]] || { echo "ERROR: RuntimeManifest.kt missing" >&2; exit 1; }

require_const() {
  local name="$1"
  local expected="$2"
  local value
  value="$(grep -E "^[[:space:]]*const val ${name}[[:space:]]*=" "$MANIFEST" | head -n1 | sed -E 's/.*=[[:space:]]*"([^"]*)".*/\1/')"
  [[ -n "$value" ]] || { echo "ERROR: missing RuntimeManifest.${name}" >&2; exit 1; }
  [[ "$value" == "$expected" ]] || {
    echo "ERROR: RuntimeManifest.${name} mismatch: expected ${expected}, got ${value}" >&2
    exit 1
  }
}

require_sha_const() {
  local name="$1"
  local value
  value="$(grep -E "^[[:space:]]*const val ${name}[[:space:]]*=" "$MANIFEST" | head -n1 | sed -E 's/.*=[[:space:]]*"([^"]*)".*/\1/')"
  [[ "$value" =~ ^[0-9a-fA-F]{64}$ ]] || {
    echo "ERROR: RuntimeManifest.${name} must be a 64-character SHA-256" >&2
    exit 1
  }
}

require_const APP_VERSION "0.28.2-fullmode"
require_const CORE_VERSION "3.2"
require_const CORE_SHA256 "1c7fed5e4e5ef9e753146f9ada932abb20994134caaec33d1933d7f5c65531bf"
require_sha_const CORE_SHA256
require_sha_const PROOT_SHA256
require_sha_const PROOT_LOADER_SHA256
require_sha_const LIBTALLOC_SHA256
require_sha_const LIBANDROID_SHMEM_SHA256

[[ -x "$ROOT/scripts/integrate-runtime-manifest.sh" ]] || {
  echo "ERROR: scripts/integrate-runtime-manifest.sh missing or not executable" >&2
  exit 1
}
[[ -x "$ROOT/scripts/pin-debian-rootfs.sh" ]] || {
  echo "ERROR: scripts/pin-debian-rootfs.sh missing or not executable" >&2
  exit 1
}

echo "FULLMODE REPAIR VERIFICATION: PASS (Debian digest is resolved and verified by integrate-runtime-manifest.sh before release preflight)"
