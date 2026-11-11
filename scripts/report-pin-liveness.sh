#!/usr/bin/env bash
set -euo pipefail
# A failing scheduled job is invisible unless somebody opens the Actions tab, and this check exists
# precisely to notice something that would otherwise reach a user as "Download failed: HTTP 404" on
# their phone. So its outcome is written where a person will see it: an issue that is opened (or
# updated) when a pin is dead, and closed again once the pins serve their bytes.
#
#   scripts/report-pin-liveness.sh <failure|success> <log-file> [run-url]
#
# Needs GH_TOKEN and GH_REPO in the environment for the GitHub calls; REPORT_DRY_RUN=1 renders the
# report and the action it would take without contacting GitHub.
OUTCOME="${1:-}"
LOG="${2:-}"
RUN_URL="${3:-}"
TITLE="[pin-liveness] A pinned runtime URL no longer serves its bytes"
# A tail of the log, not the whole thing: the failure line is what matters, and an unbounded issue
# body grows with every run.
MAX_LINES="${REPORT_MAX_LINES:-60}"
DRY_RUN="${REPORT_DRY_RUN:-0}"

case "$OUTCOME" in
  failure | success) ;;
  *)
    echo "usage: $0 <failure|success> <log-file> [run-url]" >&2
    exit 2
    ;;
esac
[[ -s "$LOG" ]] || {
  echo "report: no check log at '${LOG}' to report from" >&2
  exit 2
}

BODY="$(mktemp)"
trap 'rm -f "$BODY"' EXIT

render() {
  local headline="$1"
  printf '%s\n\n' "$headline"
  echo 'The `pin-liveness` check downloads each runtime artifact pinned in `RuntimeManifest.kt` and compares it with its pin. Its output:'
  echo
  echo '```'
  # Colour escapes from a terminal run would otherwise land in the issue body.
  sed -e 's/\x1b\[[0-9;]*m//g' "$LOG" | tail -n "$MAX_LINES"
  echo '```'
  echo
  if [[ -n "$RUN_URL" ]]; then
    printf 'Run: %s\n\n' "$RUN_URL"
  fi
  if [[ "$OUTCOME" == failure ]]; then
    echo 'To repair: mirror the snapshot with `mirror-runtime-rootfs.yml`, repin `DEBIAN_URL`/`DEBIAN_SHA256` (or `CORE_URL`/`CORE_SHA256`) in `RuntimeManifest.kt`, and let this check pass again.'
  else
    echo 'Both pinned runtime URLs serve their pinned bytes again.'
  fi
  echo
  echo 'This issue is maintained by `.github/workflows/pin-liveness.yml`: it is updated on every failing run and closed when the check passes again.'
}

# The one open issue this workflow owns, matched on its title prefix so no label has to exist first.
# jq's `// ""` keeps a missing issue from printing the string "null".
open_issue_number() {
  gh issue list --state open --limit 100 --json number,title \
    --jq '[.[] | select(.title | startswith("[pin-liveness]"))][0].number // ""'
}

# The issue number is handed to later steps so an alert can link to it without searching again.
emit_number() {
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "issue_number=$1" >> "$GITHUB_OUTPUT"
  fi
}

if [[ "$DRY_RUN" == "1" ]]; then
  render "Dry run: the check reported $OUTCOME." > "$BODY"
  echo "report: dry run, no GitHub call made"
  cat "$BODY"
  exit 0
fi

command -v gh >/dev/null || {
  echo "report: the gh CLI is not installed" >&2
  exit 1
}
[[ -n "${GH_TOKEN:-}" ]] || {
  echo "report: GH_TOKEN is not set" >&2
  exit 1
}
[[ -n "${GH_REPO:-}" ]] || {
  echo "report: GH_REPO is not set" >&2
  exit 1
}

number="$(open_issue_number)"

if [[ "$OUTCOME" == "failure" ]]; then
  if [[ -n "$number" ]]; then
    render 'The check failed again: a pinned runtime URL still does not serve its bytes.' > "$BODY"
    gh issue comment "$number" --body-file "$BODY"
    echo "report: updated issue #$number"
    emit_number "$number"
  else
    render 'A runtime artifact pinned in `RuntimeManifest.kt` no longer serves its bytes.' > "$BODY"
    created="$(gh issue create --title "$TITLE" --body-file "$BODY")"
    echo "report: opened an issue for the dead pin: $created"
    emit_number "${created##*/}"
  fi
else
  if [[ -z "$number" ]]; then
    echo "report: the check passes and no issue is open, nothing to do"
    exit 0
  fi
  render 'The check passes again.' > "$BODY"
  gh issue comment "$number" --body-file "$BODY"
  gh issue close "$number" --reason completed
  echo "report: closed issue #$number"
  emit_number "$number"
fi
