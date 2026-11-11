#!/usr/bin/env bash
set -euo pipefail
# The weekly pin-liveness check has one failure mode that leaves no red run to look at: GitHub stops
# scheduling it. A schedule only runs from the default branch, and GitHub disables it after 60 days
# without repository activity, so "nothing ran on Monday" is silent - the repository looks quiet
# rather than broken, and the first symptom is a user meeting "Download failed: HTTP 404" months
# later. A workflow that never fires cannot report that it never fired, so this watches it from the
# outside, on its own daily schedule.
#
#   scripts/watch-pin-schedule.sh
#
# Needs GH_TOKEN and GH_REPO. PIN_SCHEDULE_MAX_AGE_DAYS (default 9) is how long a scheduled run may
# be missing before that counts as an outage: one week, plus room for a slow Monday. A stopped
# schedule is tracked in a single issue and announced to the channel once, so a long outage costs one
# message rather than one per day; the run itself still fails, so the repository shows it.
#
# WATCH_DRY_RUN=1 runs every check and prints the verdict without touching GitHub, any issue, or the
# channel. WATCH_MAX_LINES (default 40) bounds how much of the check output goes into the issue.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TITLE="[pin-schedule] The weekly pin check has stopped running"
WORKFLOW_NAME="${PIN_LIVENESS_WORKFLOW_NAME:-pin-liveness.yml}"
MAX_AGE_DAYS="${PIN_SCHEDULE_MAX_AGE_DAYS:-9}"
DRY_RUN="${WATCH_DRY_RUN:-0}"
RUN_URL="${RUN_URL:-}"

LOG="$(mktemp)"
BODY="$(mktemp)"
trap 'rm -f "$LOG" "$BODY"' EXIT

problem=""

# Is the schedule still wired up at all: cron present, workflow enabled, default branch? The dry run's
# own check is reused rather than reimplemented, so the two can never disagree about "is it wired".
if bash "$ROOT/scripts/verify-pin-schedule.sh" > "$LOG" 2>&1; then
  echo "WATCH: schedule wiring is intact"
else
  problem="disabled"
fi
cat "$LOG"

[[ -n "${GH_TOKEN:-}" ]] || {
  echo "WATCH: GH_TOKEN is not set, so the schedule cannot be checked against GitHub" >&2
  exit 2
}
[[ -n "${GH_REPO:-}" ]] || {
  echo "WATCH: GH_REPO is not set, so the schedule cannot be checked against GitHub" >&2
  exit 2
}

# Has it actually fired? An enabled workflow that never runs looks identical from the inside, and
# leaves no log anywhere - which is why the age of the last scheduled run is checked, not assumed.
last_run="$(gh api "repos/$GH_REPO/actions/workflows/$WORKFLOW_NAME/runs?event=schedule&per_page=1" \
  --jq '.workflow_runs[0].created_at // ""' 2>/dev/null || true)"
created="$(gh api "repos/$GH_REPO/actions/workflows/$WORKFLOW_NAME" --jq '.created_at // ""' 2>/dev/null || true)"

# A workflow that has never run on schedule is only late once it is older than the limit: a check
# added yesterday has not missed anything yet, and crying wolf on day one teaches people to ignore it.
age_check="$(python3 - "$last_run" "$created" "$MAX_AGE_DAYS" <<'PY'
import datetime as dt
import sys

last_run, created, max_days = sys.argv[1], sys.argv[2], int(sys.argv[3])


def parse(value):
    # GitHub answers with RFC 3339 including milliseconds ("2026-11-11T06:25:13.000Z"), which a fixed
    # strptime format rejects. fromisoformat takes the fractional part and the offset, so a timestamp
    # shape this check has not seen yet cannot crash the watchdog instead of reporting on it.
    if not value:
        return None
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


now = dt.datetime.now(dt.timezone.utc)
recent = parse(last_run)
began = parse(created)

if recent is not None:
    age = (now - recent).days
    if age > max_days:
        print(f"stale\tno scheduled run for {age} days (limit {max_days}); the last was {last_run}")
    else:
        print(f"ok\tthe last scheduled run was {age} day(s) ago ({last_run})")
elif began is not None and (now - began).days > max_days:
    print(f"stale\tno scheduled run has ever happened; the workflow was created {(now - began).days} days ago")
else:
    print("ok\tno scheduled run yet, but the workflow is younger than the limit")
PY
)"

verdict="${age_check%%$'\t'*}"
note="${age_check#*$'\t'}"
echo "WATCH: $note"

if [[ "$verdict" == "stale" ]]; then
  problem="${problem:+$problem, }stale"
  # The line the chat payload quotes, in the same shape the schedule check already uses.
  echo "PIN SCHEDULE: FAIL: $note" >> "$LOG"
fi

if [[ "$DRY_RUN" == "1" ]]; then
  echo "WATCH: dry run - verdict is '${problem:-ok}'; no issue and no message sent"
  exit 0
fi

command -v gh >/dev/null || {
  echo "WATCH: the gh CLI is not installed" >&2
  exit 2
}

# The one open issue this watchdog owns, matched on a title prefix of its own: the pin failures use
# "[pin-liveness]", and matching on that here would let a stopped schedule steal their incident.
open_issue() {
  gh issue list --state open --limit 100 --json number,title \
    --jq '[.[] | select(.title | startswith("[pin-schedule]"))][0].number // ""'
}

render() {
  local headline="$1"
  printf '%s\n\n' "$headline"
  echo 'The `pin-liveness` workflow is the only thing checking that the two pinned runtime URLs still serve their bytes. GitHub schedules it from the default branch and disables it after 60 days without repository activity, so a schedule that stops leaves nobody watching the pins.'
  echo
  echo '```'
  # Colour escapes from a terminal run would otherwise land in the issue body.
  sed -e 's/\x1b\[[0-9;]*m//g' "$LOG" | tail -n "${WATCH_MAX_LINES:-40}"
  echo '```'
  echo
  if [[ -n "$RUN_URL" ]]; then
    printf 'Watchdog run: %s\n\n' "$RUN_URL"
  fi
  if [[ -n "$problem" ]]; then
    echo 'To repair: re-enable the workflow from the Actions tab, or push any commit to restore a schedule that GitHub disabled for inactivity.'
  else
    echo 'The weekly check is enabled and firing again.'
  fi
  echo
  echo 'This issue is maintained by `.github/workflows/schedule-watchdog.yml`: it is opened when the schedule stops and closed when it is running again.'
}

number="$(open_issue)"

if [[ -n "$problem" ]]; then
  if [[ -n "$number" ]]; then
    # Already announced once. Repeating it every morning is how a real alert gets filtered out.
    echo "WATCH: the outage is already tracked in issue #$number; the channel was told once already"
    exit 1
  fi
  render "The weekly \`pin-liveness\` check is not running: ${problem}." > "$BODY"
  created_url="$(gh issue create --title "$TITLE" --body-file "$BODY")"
  echo "WATCH: opened $created_url"
  bash "$ROOT/scripts/notify-pin-liveness.sh" schedule "$LOG" "$RUN_URL" "$created_url"
  exit 1
fi

if [[ -z "$number" ]]; then
  echo "WATCH: the weekly check is enabled and firing, nothing to report"
  exit 0
fi

issue_url=""
if [[ -n "${GITHUB_REPOSITORY:-}" ]]; then
  issue_url="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY}/issues/${number}"
fi
render 'The weekly `pin-liveness` check is firing again.' > "$BODY"
gh issue comment "$number" --body-file "$BODY"
gh issue close "$number" --reason completed
echo "WATCH: closed issue #$number"
bash "$ROOT/scripts/notify-pin-liveness.sh" schedule-ok "$LOG" "$RUN_URL" "$issue_url"
