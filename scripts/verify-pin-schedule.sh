#!/usr/bin/env bash
set -euo pipefail
# A scheduled workflow has one failure mode that leaves no run to look at: the schedule itself stops
# firing. GitHub only schedules workflows from the default branch, and disables the schedule on a
# repository with no activity for 60 days, so "nothing ran on Monday" is a real and silent outage of
# the check. This is the dry-run half of it: it proves the schedule is still wired up, without
# downloading 190 MB of runtime artifacts and without touching any issue.
#
#   scripts/verify-pin-schedule.sh [workflow-file] [workflow-name] [subject]
#
# Defaults are the weekly pin-liveness check. The arguments exist because more than one schedule has
# to be provable now: schedule-watchdog.yml guards the weekly check, so the same questions are asked
# of its own daily cron, with "daily" rather than "weekly" in the verdict.
#
# Needs GH_TOKEN and GH_REPO in the environment for the default-branch and Actions-state checks;
# PIN_LIVENESS_WORKFLOW / PIN_LIVENESS_WORKFLOW_NAME exist so the checks themselves can be exercised.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKFLOW="${1:-${PIN_LIVENESS_WORKFLOW:-$ROOT/.github/workflows/pin-liveness.yml}}"
WORKFLOW_NAME="${2:-${PIN_LIVENESS_WORKFLOW_NAME:-pin-liveness.yml}}"
SUBJECT="${3:-weekly}"

fail() { echo "PIN SCHEDULE: FAIL: $*" >&2; exit 1; }
note() { echo "PIN SCHEDULE: $*"; }

[[ -f "$WORKFLOW" ]] || fail "no workflow file at $WORKFLOW"

# A scheduled workflow that lost its cron trigger still parses, still dispatches by hand, and never
# runs on its own - which is why this is checked rather than assumed.
cron_lines="$(sed -n "s/^[[:space:]]*-[[:space:]]*cron:[[:space:]]*['\"]\(.*\)['\"][[:space:]]*$/\1/p" "$WORKFLOW")"
cron="${cron_lines%%$'\n'*}"
[[ -n "$cron" ]] || fail "$(basename "$WORKFLOW") declares no schedule cron; a scheduled run would never happen"

[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is not set, so the schedule cannot be checked against GitHub"
[[ -n "${GH_REPO:-}" ]] || fail "GH_REPO is not set, so the schedule cannot be checked against GitHub"

# Schedules only fire from the default branch: a workflow committed to another branch is invisible to
# the scheduler, so a dry run on such a branch saying "everything is fine" would be a lie.
default_branch="$(gh api "repos/$GH_REPO" --jq '.default_branch')"
[[ -n "$default_branch" ]] || fail "could not read the default branch of $GH_REPO"
checked_out="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
if [[ "$checked_out" != "$default_branch" ]]; then
  note "note: this checkout is '$checked_out', which is not the default branch '$default_branch'"
  note "note: the workflow that is scheduled is the one on '$default_branch'"
fi

# GitHub reports a schedule disabled for inactivity as a workflow state rather than a failed run.
state="$(gh api "repos/$GH_REPO/actions/workflows/$WORKFLOW_NAME" --jq '.state')"
case "$state" in
  active) note "workflow state: active" ;;
  disabled_inactivity)
    fail "GitHub disabled this workflow after 60 days without repository activity; re-enable it from the Actions tab, or push any commit to restore the schedule"
    ;;
  disabled_manually) fail "this workflow is disabled manually; re-enable it from the Actions tab" ;;
  *) fail "unexpected workflow state '$state' for $WORKFLOW_NAME" ;;
esac

# Printing when the next run is due is the part a human actually wants from a dry run. The evaluator
# deliberately refuses cron syntax it does not understand rather than guessing at a time.
next_run="$(python3 - "$cron" <<'PY'
import datetime as dt
import sys

fields = sys.argv[1].split()
if len(fields) != 5:
    print("unknown")
    raise SystemExit(0)

def parse(field):
    if field == "*":
        return "*"
    if field.isdigit():
        return int(field)
    return None

minute, hour, dom, month, dow = (parse(f) for f in fields)
if any(f is None for f in (minute, hour, dom, month, dow)):
    print("unknown")
    raise SystemExit(0)

def matches(value, field):
    return field == "*" or value == field

now = dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0) + dt.timedelta(minutes=1)
# Two weeks is enough for any weekly expression to come round again.
for _ in range(60 * 24 * 15):
    cron_dow = (now.weekday() + 1) % 7  # cron counts Sunday as 0
    if (
        matches(now.minute, minute)
        and matches(now.hour, hour)
        and matches(now.day, dom)
        and matches(now.month, month)
        and matches(cron_dow, dow)
    ):
        print(now.strftime("%Y-%m-%d %H:%M UTC"))
        break
    now += dt.timedelta(minutes=1)
else:
    print("unknown")
PY
)"

if [[ "$next_run" == "unknown" ]]; then
  note "cron '$cron' uses syntax this check does not evaluate; next run not calculated"
else
  note "cron '$cron' next fires: $next_run"
fi

echo "PIN SCHEDULE: PASS: the ${SUBJECT} schedule is intact ($cron, state $state, default branch $default_branch)"
