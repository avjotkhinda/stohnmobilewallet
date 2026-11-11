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
# PIN_ALERT_WEBHOOK_URL is the secret. With it unset the step says so and exits 0: a repository that
# has not configured an alert channel should not fail a run for it.
#
# Delivery problems are reported as a warning and do not change the job's result - the run is
# already failing because a pin is dead, and a chat outage must not disguise that as something else.
OUTCOME="${1:-}"
LOG="${2:-}"
RUN_URL="${3:-}"
ISSUE_URL="${4:-}"
WEBHOOK="${PIN_ALERT_WEBHOOK_URL:-}"
MAX_LINES="${PIN_ALERT_MAX_LINES:-6}"
STRICT="${PIN_ALERT_STRICT:-0}"

case "$OUTCOME" in
  failure | success | test) ;;
  *)
    echo "usage: $0 <failure|success|test> <log-file> [run-url] [issue-url]" >&2
    exit 2
    ;;
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

if [[ -z "$WEBHOOK" ]]; then
  if [[ "$STRICT" == "1" ]]; then
    echo "::error title=No alert channel configured::PIN_ALERT_WEBHOOK_URL is not set for this repository, so no alert could be delivered. Add it under Settings - Secrets and variables - Actions - New repository secret."
    exit 1
  fi
  echo "::notice title=No alert channel configured::Set the PIN_ALERT_WEBHOOK_URL secret to a Slack incoming webhook to be told here instead of only in the Actions tab."
  exit 0
fi

# Plain HTTPS, or loopback so the payload can be verified locally against a capture server.
if [[ "$WEBHOOK" != https://* && ! "$WEBHOOK" =~ ^http://(127\.0\.0\.1|localhost)(:|/) ]]; then
  echo "notify: refusing to send a runtime pin alert to a non-HTTPS webhook" >&2
  exit 1
fi

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

response="$(curl --silent --show-error --max-time 30 --request POST \
  --header 'Content-Type: application/json' \
  --data @"$PAYLOAD" \
  --write-out '\n%{http_code}' \
  "$WEBHOOK" 2>&1 || true)"
code="${response##*$'\n'}"
body="${response%$'\n'*}"

if [[ "$code" != 2* ]]; then
  if [[ "$STRICT" == "1" ]]; then
    echo "::error title=Alert not delivered::The webhook answered HTTP ${code:-000}, so the channel did not receive the message. Response: ${body}"
    exit 1
  fi
  echo "::warning title=Alert not delivered::The webhook answered HTTP ${code:-000}. The run still fails for the pin itself; this only means the chat message did not arrive. Response: ${body}"
  exit 0
fi

echo "notify: alert delivered to the configured webhook (HTTP $code)"
