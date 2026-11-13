#!/usr/bin/env bash
set -euo pipefail
# Every APK in artifacts/ travels with a <apk>.json sidecar that records its sha256, its size, the
# commit it was built from and when. Git does not track that directory, so nothing else notices a
# sidecar that was hand-edited, copied from another build or left behind by a rebuild: the record
# would simply be believed. This check reads each pair and fails when the record disagrees with the
# file beside it, when builtFrom is not a commit in this repository, or when buildTime does not
# parse as an ISO-8601 timestamp with a timezone.
#
# artifacts/ is deliberately untracked, so a checkout without it (CI, a fresh clone) passes: there is
# nothing to check, which is not the same as a problem.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[[ -d artifacts ]] || { echo 'APK SIDECAR: PASS: no artifacts/ directory; nothing to check'; exit 0; }
git rev-parse --git-dir >/dev/null 2>&1 || {
  echo 'APK SIDECAR: FAIL: not a git working tree, so builtFrom cannot be resolved' >&2
  exit 1
}

python3 - "$ROOT" <<'PY'
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

root = Path(sys.argv[1])
artifacts = root / "artifacts"
apks = sorted(artifacts.glob("*.apk"))
if not apks:
    print("APK SIDECAR: PASS: no APKs in artifacts/; nothing to check")
    raise SystemExit(0)

errors = []


def bad(apk_name, message):
    errors.append(f"{apk_name}: {message}")


def commit_exists(rev):
    result = subprocess.run(
        ["git", "-C", str(root), "cat-file", "-e", f"{rev}^{{commit}}"],
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


for apk in apks:
    sidecar = apk.with_name(apk.name + ".json")
    if not sidecar.is_file():
        bad(apk.name, "no sidecar beside it (an APK without one is unattributable)")
        continue
    try:
        meta = json.loads(sidecar.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        bad(apk.name, f"sidecar is not valid JSON: {error}")
        continue
    if not isinstance(meta, dict):
        bad(apk.name, "sidecar is not a JSON object")
        continue

    for key in ("sha256", "sizeBytes", "builtFrom", "buildTime"):
        if key not in meta:
            bad(apk.name, f"sidecar is missing {key}")

    data = apk.read_bytes()

    if "sha256" in meta:
        recorded = meta["sha256"]
        if not isinstance(recorded, str) or not re.fullmatch(r"[0-9a-f]{64}", recorded):
            bad(apk.name, f"sha256 is not a lowercase SHA-256: {recorded!r}")
        else:
            actual = hashlib.sha256(data).hexdigest()
            if recorded != actual:
                bad(apk.name, f"sha256 {recorded} does not match the file ({actual}); the sidecar is stale")

    if "sizeBytes" in meta:
        recorded = meta["sizeBytes"]
        if isinstance(recorded, bool) or not isinstance(recorded, int) or recorded <= 0:
            bad(apk.name, f"sizeBytes is not a positive integer: {recorded!r}")
        elif recorded != len(data):
            bad(apk.name, f"sizeBytes {recorded} does not match the file ({len(data)})")

    if "builtFrom" in meta:
        recorded = meta["builtFrom"]
        if not isinstance(recorded, str) or not re.fullmatch(r"[0-9a-f]{7,40}", recorded):
            bad(apk.name, f"builtFrom is not a commit id: {recorded!r}")
        elif not commit_exists(recorded):
            bad(apk.name, f"builtFrom {recorded} is not a commit in this repository")

    if "buildTime" in meta:
        recorded = meta["buildTime"]
        if not isinstance(recorded, str) or not recorded.strip():
            bad(apk.name, f"buildTime is not a timestamp: {recorded!r}")
        else:
            try:
                parsed = datetime.fromisoformat(recorded.strip().replace("Z", "+00:00"))
            except ValueError:
                bad(apk.name, f"buildTime is not an ISO-8601 timestamp: {recorded!r}")
            else:
                if parsed.tzinfo is None:
                    bad(apk.name, f"buildTime has no timezone, so it names no instant: {recorded!r}")

# A sidecar whose APK is gone describes a file nobody has: stale, and it would be believed.
for sidecar in sorted(artifacts.glob("*.apk.json")):
    if not sidecar.with_name(sidecar.name[: -len(".json")]).is_file():
        errors.append(f"{sidecar.name}: sidecar with no APK beside it")

if errors:
    for error in errors:
        print(f"APK SIDECAR: FAIL: {error}", file=sys.stderr)
    print(
        f"APK SIDECAR: FAIL: {len(errors)} problem(s) across {len(apks)} APK(s); "
        "rewrite a sidecar from the file it describes rather than editing it by hand",
        file=sys.stderr,
    )
    raise SystemExit(1)

print(f"APK SIDECAR: PASS: {len(apks)} APK sidecar(s) match their files, commits and timestamps")
PY
