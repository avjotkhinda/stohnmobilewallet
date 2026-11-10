#!/usr/bin/env bash
# Fails a release when the previous release's emitted baseline was never committed.
#
# usage: verify-baseline-committed.sh [root] [--previous-baseline <file>]
#
# The release job writes the baseline for the APK it just built and uploads it with the artifact. That
# file is the only record of the release: if it is not committed, the next release compares against an
# older one, `runtime/release-history.json` never learns the version, and nothing can reconstruct it
# afterwards — the artifact expires and the version code is gone from the record.
#
# So before building anything, compare the committed baseline with the baseline inside the artifact of
# the previously successful release run. A committed baseline behind the released one means someone
# skipped the commit. The comparison needs no keystore, so it runs on every push, but it also needs no
# network in tests: --previous-baseline takes the emitted file directly.
set -euo pipefail

root="."
previous=""
while [ "$#" -gt 0 ]; do
    case "$1" in
        --previous-baseline)
            previous=${2:?--previous-baseline needs a path}
            shift 2
            ;;
        *)
            root=$1
            shift
            ;;
    esac
done

tracked="$root/runtime/release-baseline.json"
[ -f "$tracked" ] || { echo "no baseline at $tracked" >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

if [ -z "$previous" ]; then
    : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY must be set to find the previous release}"
    command -v gh >/dev/null || { echo "gh is required to read the previous release artifact" >&2; exit 1; }
    artifact=${ARTIFACT_NAME:-stohn-wallet-release-apk}

    # Newest artifact of this workflow other than this run: the previous signed release.
    if ! gh api "repos/$GITHUB_REPOSITORY/actions/artifacts?name=$artifact&per_page=100" \
            --jq '.artifacts[] | [.id, .workflow_run.id, .expired] | @tsv' > "$work/artifacts" 2> "$work/error"; then
        echo "cannot list release artifacts (the token needs actions: read):" >&2
        cat "$work/error" >&2
        exit 1
    fi

    artifact_id=""
    expired=0
    while IFS=$'\t' read -r candidate run expired_flag; do
        [ -n "${candidate:-}" ] || continue
        [ "$run" = "${GITHUB_RUN_ID:-}" ] && continue
        if [ "$expired_flag" = "true" ]; then
            expired=$((expired + 1))
            continue
        fi
        artifact_id=$candidate
        break
    done < "$work/artifacts" || true

    if [ -z "$artifact_id" ]; then
        if [ "$expired" -gt 0 ]; then
            echo "note: the previous release's artifact has expired, so the committed baseline cannot be compared with it"
        else
            echo "BASELINE COMMIT: PASS: no earlier release artifact, so there is nothing to have committed"
        fi
        exit 0
    fi

    if ! gh api "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id/zip" > "$work/artifact.zip"; then
        echo "cannot download release artifact $artifact_id" >&2
        exit 1
    fi
    if ! unzip -p "$work/artifact.zip" release-baseline.json > "$work/previous.json" 2> "$work/error"; then
        echo "artifact $artifact_id has no release-baseline.json:" >&2
        cat "$work/error" >&2
        exit 1
    fi
    previous="$work/previous.json"
fi

python3 - "$previous" "$tracked" <<'PY'
import json
import sys


def load(path, role):
    try:
        with open(path, encoding="utf-8") as source:
            return json.load(source)
    except FileNotFoundError:
        sys.exit(f"FAIL: no {role} at {path}")
    except json.JSONDecodeError as error:
        sys.exit(f"FAIL: {role} {path} is not valid JSON: {error}")


released = load(sys.argv[1], "previously emitted baseline")
committed = load(sys.argv[2], "committed baseline")

released_code = int(released.get("versionCode", 0))
released_name = released.get("versionName", "")
released_signer = released.get("signerSha256", "")
committed_code = int(committed.get("versionCode", 0))
committed_name = committed.get("versionName", "")
committed_signer = committed.get("signerSha256", "")

if committed_code < released_code:
    sys.exit(
        f"FAIL: the last release built {released_name} ({released_code}) but runtime/release-baseline.json "
        f"still records {committed_name} ({committed_code}), so that release was never committed back. "
        "Take release-baseline.json from that run's artifact, put it in runtime/release-baseline.json, append "
        "the same release to runtime/release-history.json, and commit both before releasing again — otherwise "
        "the record of it is lost with the artifact and the upgrade check compares against the wrong release."
    )
if committed_code == released_code and committed_signer and committed_signer != released_signer:
    sys.exit(
        f"FAIL: {committed_name} ({committed_code}) is committed with signing certificate {committed_signer} "
        f"but the release that built it was signed with {released_signer}. One of the two is wrong, and the "
        "upgrade check would trust the wrong key."
    )
if committed_code > released_code:
    print(
        f"note: runtime/release-baseline.json is ahead of the last release artifact "
        f"({committed_name} ({committed_code}) against {released_name} ({released_code})); "
        "it was recorded outside this pipeline"
    )

print(
    f"BASELINE COMMIT: PASS: the committed baseline is {committed_name} ({committed_code}), "
    f"the last release artifact was {released_name} ({released_code})"
)
PY
