#!/usr/bin/env python3
"""Validate the release history and its agreement with the release baseline.

usage: check-release-history.py [root]

`runtime/release-history.json` lists every release that went out, oldest first, and
`runtime/release-baseline.json` records the newest of them. Two files describing the same releases
can drift — the baseline hand-edited without the history, or the other way round — and a baseline
that is not the newest release silently weakens the upgrade check to "higher than some older one".
This refuses that state, and the shape errors that make the two scripts unreadable: a repeated
versionCode, a versionName that is not a version, a signing certificate that is not a SHA-256.

Run from scripts/release-preflight.sh (no APK needed) and from
scripts/verify-upgrade-compatibility.sh, so the rules are one file rather than two.
"""

import json
import re
import sys
from pathlib import Path

NAME = re.compile(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?")
SIGNER = re.compile(r"[0-9a-f]{64}")


def fail(message):
    sys.exit(f"FAIL: {message}")


def load(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        fail(f"missing {path}; it is tracked, so this is a checkout problem")
    except json.JSONDecodeError as error:
        fail(f"{path} is not valid JSON: {error}")


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent)
    history_path = root / "runtime/release-history.json"
    baseline_path = root / "runtime/release-baseline.json"

    history = load(history_path)
    releases = history.get("releases")
    if not isinstance(releases, list):
        fail(f'{history_path}: "releases" must be a list')

    seen_codes = set()
    seen_names = set()
    previous_code = 0
    for index, release in enumerate(releases):
        where = f"{history_path}: releases[{index}]"
        if not isinstance(release, dict):
            fail(f"{where} must be an object")
        code = release.get("versionCode")
        name = release.get("versionName")
        signer = release.get("signerSha256", "")
        if not isinstance(code, int) or isinstance(code, bool) or code < 1:
            fail(f"{where}.versionCode must be a positive integer, was {code!r}")
        if not isinstance(name, str) or not NAME.fullmatch(name):
            fail(f"{where}.versionName must look like a version, was {name!r}")
        if not isinstance(signer, str) or (signer and not SIGNER.fullmatch(signer)):
            fail(f"{where}.signerSha256 must be a lowercase SHA-256 or empty, was {signer!r}")
        if code in seen_codes:
            fail(f"{where}.versionCode {code} is used twice; a release is identified by it")
        if code <= previous_code:
            fail(f"{where}.versionCode {code} is not above the previous {previous_code}; releases are oldest first")
        if name in seen_names:
            fail(f"{where}.versionName {name} is used twice; two releases cannot share a version")
        seen_codes.add(code)
        seen_names.add(name)
        previous_code = code

    baseline = load(baseline_path)
    base_code = baseline.get("versionCode", 0)
    base_name = baseline.get("versionName", "")
    if not isinstance(base_code, int) or not isinstance(base_name, str):
        fail(f"{baseline_path}: versionCode/versionName must be an integer and a string")

    if not releases:
        if base_code != 0 or base_name:
            fail(
                f"{baseline_path} records {base_name} ({base_code}) but {history_path} lists no release. "
                "Add the matching entry to the history, or reset the baseline to 0 and an empty versionName."
            )
        print(f"HISTORY: PASS: no release recorded yet, baseline at {base_code}")
        return

    newest = releases[-1]
    if (base_code, base_name) != (newest["versionCode"], newest["versionName"]):
        fail(
            f"{baseline_path} records {base_name} ({base_code}) but the newest release in "
            f"{history_path} is {newest['versionName']} ({newest['versionCode']}). The upgrade check "
            "would compare against a release that is not the last one; regenerate both with "
            "scripts/verify-upgrade-compatibility.sh <apk> --emit <baseline>."
        )

    print(f"HISTORY: PASS: {len(releases)} release(s) recorded, baseline is the newest ({base_name})")


if __name__ == "__main__":
    main()
