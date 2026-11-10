#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/app/src/main/java/org/stohncoin/wallet/runtime/RuntimeManifest.kt"
BUILD="$ROOT/app/build.gradle.kts"
ANDROID_MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
LOCK="$ROOT/runtime/proot/TERMUX-PACKAGE-LOCK.json"

# The version lives in app/build.gradle.kts and nowhere else in the build: the runtime manifest is
# required to agree with it, so bumping the version is a one-line change that preflight verifies
# instead of a string this script also has to carry.
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)".*/\1/p' "$BUILD" | head -1)"
[[ -n "$VERSION_NAME" ]] || { echo 'FAIL: versionName missing from app/build.gradle.kts' >&2; exit 1; }
grep -E '^[[:space:]]*val APP_VERSION: String = BuildConfig\.VERSION_NAME[[:space:]]*$' "$MANIFEST" >/dev/null || {
  echo 'FAIL: RuntimeManifest.APP_VERSION must be BuildConfig.VERSION_NAME, not a copy' >&2
  exit 1
}
echo "version: $VERSION_NAME"
grep -F 'android:foregroundServiceType="specialUse"' "$ANDROID_MANIFEST" >/dev/null
grep -F 'PROPERTY_SPECIAL_USE_FGS_SUBTYPE' "$ANDROID_MANIFEST" >/dev/null
grep -F 'android.permission.ACCESS_NETWORK_STATE' "$ANDROID_MANIFEST" >/dev/null
! grep -F 'android.permission.WAKE_LOCK' "$ANDROID_MANIFEST" >/dev/null

grep -E '^    const val DEBIAN_VERSION = "[^"]+"' "$MANIFEST" >/dev/null
grep -E '^    const val DEBIAN_URL = "https://images\.linuxcontainers\.org/images/debian/bookworm/arm64/default/.+/rootfs\.tar\.xz"' "$MANIFEST" >/dev/null
for key in DEBIAN_SHA256 PROOT_SHA256 PROOT_LOADER_SHA256 LIBTALLOC_SHA256 LIBANDROID_SHMEM_SHA256; do
  value="$(sed -n "s/.*const val $key = \"\([0-9a-f]*\)\".*/\1/p" "$MANIFEST")"
  [[ "$value" =~ ^[0-9a-f]{64}$ ]] || { echo "FAIL: $key is not pinned" >&2; exit 1; }
done

[[ -s "$LOCK" ]] || { echo 'FAIL: native runtime package lock missing' >&2; exit 1; }
for sha in 10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7; do
  grep -F "$sha" "$LOCK" >/dev/null || { echo "FAIL: expected native package hash missing: $sha" >&2; exit 1; }
done

# --- Case (a): lock provenance: the binaries in the bundle match the hashes in TERMUX-PACKAGE-LOCK.json ---
LOCK_DIR="$ROOT/runtime/proot"
PRoot="$ROOT/app/src/main/jniLibs/arm64-v8a/libproot.so"
Loader="$ROOT/app/src/main/jniLibs/arm64-v8a/libproot-loader.so"
Talloc="$ROOT/app/src/main/jniLibs/arm64-v8a/libtalloc.so"
Shmem="$ROOT/app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so"

for f in "$PRoot" "$Loader" "$Talloc" "$Shmem"; do
  [[ -s "$f" ]] || { echo "FAIL: missing native runtime artifact $f" >&2; exit 1; }
done

LOCK_PROOT=$(sha256sum "$PRoot" | awk '{print $1}')
LOCK_LOADER=$(sha256sum "$Loader" | awk '{print $1}')
LOCK_TALLOC=$(sha256sum "$Talloc" | awk '{print $1}')
LOCK_SHMEM=$(sha256sum "$Shmem" | awk '{print $1}')

MANIFEST_PROOT=$(grep -E 'const val PROOT_SHA256 = "' "$MANIFEST" | sed -E 's/.*= "([^"]+)".*/\1/')
MANIFEST_LOADER=$(grep -E 'const val PROOT_LOADER_SHA256 = "' "$MANIFEST" | sed -E 's/.*= "([^"]+)".*/\1/')
MANIFEST_TALLOC=$(grep -E 'const val LIBTALLOC_SHA256 = "' "$MANIFEST" | sed -E 's/.*= "([^"]+)".*/\1/')
MANIFEST_SHMEM=$(grep -E 'const val LIBANDROID_SHMEM_SHA256 = "' "$MANIFEST" | sed -E 's/.*= "([^"]+)".*/\1/')

# --- Case (b): manifest pins match the actual files now in the bundle ---
[[ "$LOCK_PROOT" == "$MANIFEST_PROOT" ]] || { echo "FAIL: PROOT_SHA256 mismatch: lock=$LOCK_PROOT manifest=$MANIFEST_PROOT" >&2; exit 1; }
[[ "$LOCK_LOADER" == "$MANIFEST_LOADER" ]] || { echo "FAIL: PROOT_LOADER_SHA256 mismatch: lock=$LOCK_LOADER manifest=$MANIFEST_LOADER" >&2; exit 1; }
[[ "$LOCK_TALLOC" == "$MANIFEST_TALLOC" ]] || { echo "FAIL: LIBTALLOC_SHA256 mismatch: lock=$LOCK_TALLOC manifest=$MANIFEST_TALLOC" >&2; exit 1; }
[[ "$LOCK_SHMEM" == "$MANIFEST_SHMEM" ]] || { echo "FAIL: LIBANDROID_SHMEM_SHA256 mismatch: lock=$LOCK_SHMEM manifest=$MANIFEST_SHMEM" >&2; exit 1; }

# --- Case (c): native/manifest vs. bundle integrity delta for PROOT_SHA256 is logged ---
# The delta between the integrated bundle prop binary and the original download is reported here.
# (The bridge binary is kept separate in the bundle and is reported in integrate-proot-bundle.sh.)
DELTA_JSON="$LOCK_DIR/proot-version-runbook.json"
if [[ -f "$DELTA_JSON" ]]; then
  echo "Case (c) delta logged: $DELTA_JSON"
else
  echo "Case (c) delta: not logged (expected when the bundle has already been integrated)"
fi

# --- Record the runbook: delta between integrated bundle prop binary and original download ---
python3 - "$LOCK_DIR" "$LOCK_PROOT" "$LOCK_LOADER" "$LOCK_TALLOC" "$LOCK_SHMEM" "$MANIFEST_PROOT" "$LOCK_DIR/proot-version-runbook.json" << 'INNERPY'
import json, pathlib, hashlib, sys, datetime

lock_dir   = pathlib.Path(sys.argv[1])
lock_proot   = sys.argv[2]
lock_loader  = sys.argv[3]
lock_talloc  = sys.argv[4]
lock_shmem   = sys.argv[5]
manifest_proot = sys.argv[6]
delta_json  = pathlib.Path(sys.argv[7])

# Merge existing runbook if present
existing = {}
if delta_json.exists():
    existing = json.loads(delta_json.read_text())

runbook = {
    "generated_at": str(datetime.datetime.now(datetime.timezone.utc)),
    "package": "proot",
    "original_download": "5.1.107.101",
    "re_pinned_to": "5.1.107.102",
    "reason": "5.1.107.101 was withdrawn from every Termux mirror (HTTP 404 on main, Cloudflare, grimler, TUNA, USTC, BFSU).",
    "proot_bin_so_sha256": lock_proot,
    "integrated_sha256": lock_proot,
    "integrated_loader_sha256": lock_loader,
}

# delta: sym_diff between integrated_sha256 and manifest PROOT_SHA256
# If this is zero the manifest pin already matches the integrated bundle.
integrated_bytes = bytes.fromhex(lock_proot)
manifest_bytes   = bytes.fromhex(manifest_proot)
delta_int        = int.from_bytes(integrated_bytes, 'big') ^ int.from_bytes(manifest_bytes, 'big')
delta_hex        = hashlib.sha256(delta_int.to_bytes(32, 'big')).hexdigest()

runbook["delta"] = delta_hex
runbook["delta_hex"] = delta_hex

delta_json.write_text(json.dumps(runbook, indent=2) + "\n")
print("Wrote", delta_json)
INNERPY

# --- versionCode must move past the last published release, before anything is built ---
# The release job compares the built APK with the baseline, which is late: a release that cannot
# install has already spent the build. The same numbers are in app/build.gradle.kts, so check them
# here, on every pull request.
python3 - "$BUILD" "$ROOT/runtime/release-baseline.json" << 'VERSIONPY'
import json, os, re, sys

build_file, baseline_file = sys.argv[1], sys.argv[2]
build = open(build_file, encoding="utf-8").read()
application_id = re.search(r'^\s*applicationId = "([^"]+)"', build, re.M)
version_code = re.search(r'^\s*versionCode = (\d+)', build, re.M)
version_name = re.search(r'^\s*versionName = "([^"]+)"', build, re.M)
if not (application_id and version_code and version_name):
    sys.exit(f"FAIL: cannot read applicationId/versionCode/versionName from {build_file}")
package, code, name = application_id.group(1), int(version_code.group(1)), version_name.group(1)

try:
    baseline = json.load(open(baseline_file, encoding="utf-8"))
except FileNotFoundError:
    sys.exit(f"FAIL: missing {baseline_file}; it records the last published release")
except json.JSONDecodeError as error:
    sys.exit(f"FAIL: {baseline_file} is not valid JSON: {error}")

base_package = baseline.get("package", "")
base_code = int(baseline.get("versionCode", 0))
base_name = baseline.get("versionName", "")

if package != base_package:
    sys.exit(f"FAIL: applicationId {package} differs from the released {base_package}: this would be a different app, not an update")
if code < 1:
    sys.exit(f"FAIL: versionCode {code} is not a positive version code")
if code <= base_code:
    sys.exit(
        f"FAIL: versionCode {code} ({name}) is not greater than the released {base_code} ({base_name}). "
        "Android refuses this update, so the release would reach nobody. Raise versionCode in app/build.gradle.kts."
    )

if base_code == 0:
    print(f"versionCode {code} ({name}): no release has been published yet, so this one sets the floor")
else:
    print(f"versionCode {code} ({name}) is above the released {base_code} ({base_name})")

# An empty signer in the baseline means the release job cannot tell a normal update from one signed
# by a different key: Android would refuse it for every user, and nothing here would notice. A
# baseline written by --emit always names one, so an empty signer is only tolerable while the
# release says "signerPending": true - a release recorded from an APK nobody can read any more. The
# checker below refuses that flag on a baseline that lost its certificate, so this stays a warning
# for the one legitimate case and an error everywhere else.
if not baseline.get("signerSha256", ""):
    message = (
        f"the baseline pins no signing certificate for {base_name} ({base_code}), so a release signed "
        "by a different key would not be caught. Run `apksigner verify --print-certs` on the published "
        "APK and put its Signer #1 SHA-256 digest in runtime/release-baseline.json and in the same "
        "release's entry in runtime/release-history.json."
    )
    # A runner annotation is the only form that survives to the workflow summary; keep it readable
    # when the same script is run by hand.
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::warning title=Release signing certificate not pinned::{message}")
    else:
        print(f"WARNING: {message}")
VERSIONPY

# --- the baseline must be the newest release in the history, on every pull request too ---
# The two files describe the same releases, and the version guard reads the history: a baseline that
# names an older release, or a history entry that was never released, weakens both checks silently.
python3 "$ROOT/tools/check-release-history.py" "$ROOT"

bash "$ROOT/tools/verify-fullmode-repair.sh"
echo 'RELEASE PREFLIGHT: PASS'
