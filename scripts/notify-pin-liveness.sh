#!/usr/bin/env bash
set -euo pipefail
# A red scheduled run is only visible to somebody who opens the Actions tab, and an issue is only
# visible to somebody who looks at their GitHub notifications. This posts the failure where people
# actually are: a Slack incoming webhook, which needs no SDK, no token and no service account - just
# a URL held in a repository secret.
#
#   scripts/notify-pin-liveness.sh <failure|success> <log-file> [run-url] [issue-url]
#
# A success is the all-clear for an alert that already went out, so it is sent only when that run
# also closed the tracking issue: a routine weekly pass says nothing rather than pinging every Monday.
#
#   scripts/notify-pin-liveness.sh test "" [run-url]
#
# `test` goes through exactly this delivery path with a payload that says it was run by hand, so the
# channel can be confirmed without waiting for, or faking, a dead pin. PIN_ALERT_STRICT=1 makes an
# unset channel or a refused delivery fail the step instead of warning: a deliberate test that passes
# without sending anything would only hide the problem.
#
# The pin check and the alert test both run strict, so a channel that cannot receive is a red run. The
# default stays lenient for a script run by hand, where a chat problem should not look like a code
# problem; scripts/watch-pin-schedule.sh relies on that while it tracks its own outage in an issue.
#
#   scripts/notify-pin-liveness.sh <schedule|schedule-ok> <log-file> [run-url] [issue-url]
#
# The schedule outcomes belong to the watchdog: `schedule` says the weekly check has stopped running,
# which is a different failure from a dead pin and must not read like one, and `schedule-ok` closes
# it out. scripts/watch-pin-schedule.sh sends each of them at most once per outage.
#
# PIN_ALERT_WEBHOOK_URL_FALLBACK is the second route: any webhook that accepts {"text": ...} - another
# Slack channel or workspace, Discord, Teams, Google Chat. It is tried only when the primary is missing
# or refuses the message, so one broken channel cannot silence the alarm on its own.
#
# PIN_ALERT_WEBHOOK_URL is the secret. Unset, the script reports the missing channel and exits 0,
# which is the lenient default; strict callers get an error and exit 1 instead.
#
# Delivery problems follow the same split: a warning and exit 0 by default, an error and exit 1 under
# PIN_ALERT_STRICT=1. Neither changes what the step already reported about the pins themselves - it
# only decides whether a chat outage is allowed to pass as a healthy run.
OUTCOME="${1:-}"
LOG="${2:-}"
RUN_URL="${3:-}"
ISSUE_URL="${4:-}"
WEBHOOK="${PIN_ALERT_WEBHOOK_URL:-}"
FALLBACK="${PIN_ALERT_WEBHOOK_URL_FALLBACK:-}"
MAX_LINES="${PIN_ALERT_MAX_LINES:-6}"
STRICT="${PIN_ALERT_STRICT:-0}"

case "$OUTCOME" in
  failure | success | test | schedule | schedule-ok) ;;
  *)
    echo "usage: $0 <failure|success|test|schedule|schedule-ok> <log-file> [run-url] [issue-url]" >&2
    exit 2
    ;;
esac
# Whoever opens the run reads the summary, not the log, so the delivery result is written there too:
# "did the alert arrive?" is the question this step exists to answer, and a notice buried in a log is
# not an answer anybody reads.
summarize() {
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '%s\n' "$1" >> "$GITHUB_STEP_SUMMARY"
  fi
}

case "$OUTCOME" in
  failure) KIND="failure alert" ;;
  success) KIND="recovery notice" ;;
  test) KIND="test message" ;;
  schedule) KIND="schedule warning" ;;
  schedule-ok) KIND="schedule all-clear" ;;
  *) KIND="alert" ;;

esac

# A test is run by hand against no check at all, so it is the one outcome without a log behind it.
if [[ "$OUTCOME" != "test" ]]; then
  [[ -s "$LOG" ]] || {
    echo "notify: no check log at '${LOG}' to alert about" >&2
    exit 2
  }
fi

# A passing run is only news when it closed an incident. The workflow passes the issue it closed,
# so an empty issue URL here means the channel was never told about a failure and has nothing to
# recover from.
if [[ "$OUTCOME" == "success" && -z "$ISSUE_URL" ]]; then
  echo "notify: the check passes and no incident was closed, nothing to announce"
  exit 0
fi

# Every route that could carry this message, primary first. A missing route is not an error on its
# own: the fallback exists precisely so one unconfigured channel does not silence the alarm.
ROUTE_NAMES=()
ROUTE_URLS=()
if [[ -n "$WEBHOOK" ]]; then
  ROUTE_NAMES+=("primary")
  ROUTE_URLS+=("$WEBHOOK")
fi
if [[ -n "$FALLBACK" ]]; then
  ROUTE_NAMES+=("fallback")
  ROUTE_URLS+=("$FALLBACK")
fi

if [[ ${#ROUTE_NAMES[@]} -eq 0 ]]; then
  summarize "- Alert channel: **not configured** (\`PIN_ALERT_WEBHOOK_URL\` and \`PIN_ALERT_WEBHOOK_URL_FALLBACK\` are both empty), so the ${KIND} could not be sent."
  if [[ "$STRICT" == "1" ]]; then
    echo "::error title=No alert channel configured::Neither PIN_ALERT_WEBHOOK_URL nor PIN_ALERT_WEBHOOK_URL_FALLBACK is set for this repository, so no alert could be delivered. Add either under Settings - Secrets and variables - Actions - New repository secret."
    exit 1
  fi
  echo "::notice title=No alert channel configured::Set the PIN_ALERT_WEBHOOK_URL secret, and optionally PIN_ALERT_WEBHOOK_URL_FALLBACK as a second route, to be told here instead of only in the Actions tab."
  exit 0
fi

# Plain HTTPS, or loopback so the payload can be verified locally against a capture server. Every route
# is validated up front: discovering an unusable fallback only after the primary refused would waste the
# single chance the alert had.
for i in "${!ROUTE_NAMES[@]}"; do
  url="${ROUTE_URLS[$i]}"
  if [[ "$url" != https://* && ! "$url" =~ ^http://(127\.0\.0\.1|localhost)(:|/) ]]; then
    echo "notify: refusing to send a runtime pin alert to a non-HTTPS ${ROUTE_NAMES[$i]} webhook" >&2
    exit 1
  fi
done

PAYLOAD="$(mktemp)"
trap 'rm -f "$PAYLOAD"' EXIT

# The payload is assembled by python3 so the check's own output cannot break the JSON: a quote or a
# backslash in a URL is normal, and hand-rolled quoting here would corrupt the message exactly when
# it matters.
python3 - "$OUTCOME" "$LOG" "$RUN_URL" "$ISSUE_URL" "$MAX_LINES" > "$PAYLOAD" <<'PY'
import json
import re
import sys

outcome, log_path, run_url, issue_url = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
max_lines = int(sys.argv[5])

# A hand-run test has no check output behind it; every other outcome does.
lines = []
if outcome != "test":
    with open(log_path, encoding="utf-8", errors="replace") as handle:
        lines = [re.sub(r"\x1b\[[0-9;]*m", "", line.rstrip("\n")) for line in handle]

if outcome == "failure":
    # The specific failure lines, which name the URL and the digest that disagreed. Falling back to
    # the tail of the log keeps the message useful even when the failure looks like a network error.
    detail_lines = [line for line in lines if "FAIL" in line] or lines[-(max_lines + 4):]
    detail = "\n".join(detail_lines[-max_lines:])
    text = [
        ":rotating_light: *stohnmobilewallet: a pinned runtime URL is dead*",
        "The weekly pin-liveness check could not download a runtime artifact, or got different bytes than the pin. Users hit this as \"Download failed: HTTP 404\" when they start the node.",
        "```" + detail + "```" if detail else "",
    ]
    if run_url:
        text.append(f"<{run_url}|Failing run>")
    if issue_url:
        text.append(f"<{issue_url}|Tracking issue>")
    text.append("Repair: mirror the snapshot with `mirror-runtime-rootfs.yml`, repin `DEBIAN_URL`/`DEBIAN_SHA256` (or `CORE_URL`/`CORE_SHA256`), and let the check pass again.")
elif outcome == "success":
    # The one or two per-artifact "ok" lines and the PASS line: an all-clear is read at a glance to
    # confirm which artifacts came back, not inspected line by line.
    detail = "\n".join([line for line in lines if "PIN LIVENESS:" in line][-max_lines:])
    text = [
        ":white_check_mark: *stohnmobilewallet: the pinned runtime URLs serve their bytes again*",
        "The weekly pin-liveness check downloads both runtime artifacts and matches their pins again, so Full Mode downloads work for users.",
        "```" + detail + "```" if detail else "",
    ]
    if run_url:
        text.append(f"<{run_url}|Passing run>")
    if issue_url:
        text.append(f"<{issue_url}|Closed issue>")
    text.append("Nothing to do - this closes out the earlier alert.")
elif outcome in ("schedule", "schedule-ok"):
    # The schedule check writes its own lines under its own prefix: a stopped schedule is not a dead
    # pin, and both land in the same channel, so they have to be distinguishable at a glance.
    detail = "\n".join([line for line in lines if "PIN SCHEDULE:" in line][-max_lines:])
    if outcome == "schedule":
        text = [
            ":warning: *stohnmobilewallet: the weekly pin check has stopped running*",
            "GitHub only runs a schedule from the default branch, and disables it after 60 days without repository activity. Until this is fixed nobody is watching the runtime pins, so a dead URL would be found by a user instead of here.",
            "```" + detail + "```" if detail else "",
        ]
        if run_url:
            text.append(f"<{run_url}|Watchdog run>")
        if issue_url:
            text.append(f"<{issue_url}|Tracking issue>")
        text.append("Repair: re-enable the workflow from the Actions tab, or push any commit to restore a schedule GitHub disabled for inactivity.")
    else:
        text = [
            ":white_check_mark: *stohnmobilewallet: the weekly pin check is running on schedule again*",
            "The `pin-liveness` schedule is enabled and its last scheduled run is recent, so the runtime pins are being watched weekly again.",
            "```" + detail + "```" if detail else "",
        ]
        if run_url:
            text.append(f"<{run_url}|Watchdog run>")
        if issue_url:
            text.append(f"<{issue_url}|Closed issue>")
        text.append("Nothing to do - this closes out the earlier warning.")
else:
    # A test message has to be unmistakable: somebody glancing at the channel must not read it as a
    # real dead pin, and must not go looking for a failing run that does not exist.
    text = [
        ":test_tube: *stohnmobilewallet: pin-liveness alert test*",
        "This message was requested by hand to confirm the alert channel works. It is not a pin failure - a real alert here has a red headline and names the URL that stopped serving its bytes.",
        "If you can read this, a dead pin will reach this channel too.",
    ]
    if run_url:
        text.append(f"<{run_url}|Triggering run>")
    text.append("No action needed.")

print(json.dumps({"text": "\n".join(part for part in text if part)}))
PY

# Routes are tried in order and the first 2xx wins. A refusal is recorded per route: "the fallback saved
# it" is worth knowing, because it means the primary is broken and needs looking at.
delivered_route=""
delivered_code=""
attempts=""
primary_note=""
for i in "${!ROUTE_NAMES[@]}"; do
  route="${ROUTE_NAMES[$i]}"
  url="${ROUTE_URLS[$i]}"
  response="$(curl --silent --show-error --max-time 30 --request POST \
    --header 'Content-Type: application/json' \
    --data @"$PAYLOAD" \
    --write-out '\n%{http_code}' \
    "$url" 2>&1 || true)"
  code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [[ "$code" == 2* ]]; then
    delivered_route="$route"
    delivered_code="$code"
    break
  fi
  echo "notify: the ${route} webhook answered HTTP ${code:-000}: ${body}" >&2
  attempts="${attempts:+$attempts; }${route} HTTP ${code:-000}"
  if [[ "$route" == "primary" ]]; then
    primary_note="HTTP ${code:-000}"
  fi
done

if [[ -n "$delivered_route" ]]; then
  if [[ "$delivered_route" == "fallback" ]]; then
    summarize "- Alert channel: ${KIND} delivered via the **fallback** webhook (HTTP ${delivered_code}); the primary did not take it (${primary_note:-not configured})."
    echo "notify: the primary route did not deliver it; the fallback did"
  else
    summarize "- Alert channel: ${KIND} delivered (HTTP ${delivered_code})."
  fi
  echo "notify: alert delivered to the configured webhook (HTTP $delivered_code)"
  exit 0
fi

summarize "- Alert channel: **delivery failed** (${attempts:-no route answered}); the ${KIND} was rejected by every configured webhook."
if [[ "$STRICT" == "1" ]]; then
  echo "::error title=Alert not delivered::No configured webhook accepted the message (${attempts:-no response}), so the channel did not receive it."
  exit 1
fi
echo "::warning title=Alert not delivered::No configured webhook accepted the message (${attempts:-no response}). This only means the chat message did not arrive; the run's result is decided by the check itself."
exit 0
