#!/usr/bin/env bash
set -euo pipefail
# A red scheduled run is only visible to somebody who opens the Actions tab, and an issue is only
# visible to somebody who looks at their GitHub notifications. This posts the failure where people
# actually are: a Slack incoming webhook, which needs no SDK, no token and no service account - just
# a URL held in a repository secret.
#
#   scripts/notify-pin-liveness.sh <failure|success> <log-file> [run-url] [issue-url]
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

case "$OUTCOME" in
  failure | success) ;;
  *)
    echo "usage: $0 <failure|success> <log-file> [run-url] [issue-url]" >&2
    exit 2
    ;;
esac
[[ -s "$LOG" ]] || {
  echo "notify: no check log at '${LOG}' to alert about" >&2
  exit 2
}

# Only a failure is worth a ping: a passing run is not news, and the report step already closes the
# issue on recovery.
if [[ "$OUTCOME" != "failure" ]]; then
  echo "notify: outcome is '$OUTCOME', nothing to alert on"
  exit 0
fi

if [[ -z "$WEBHOOK" ]]; then
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
python3 - "$LOG" "$RUN_URL" "$ISSUE_URL" "$MAX_LINES" > "$PAYLOAD" <<'PY'
import json
import re
import sys

log_path, run_url, issue_url, max_lines = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])

with open(log_path, encoding="utf-8", errors="replace") as handle:
    lines = [re.sub(r"\x1b\[[0-9;]*m", "", line.rstrip("\n")) for line in handle]

# The specific failure lines, which name the URL and the digest that disagreed. Falling back to the
# tail of the log keeps the message useful even when the failure looks like a network error.
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
  echo "::warning title=Alert not delivered::The webhook answered HTTP ${code:-000}. The run still fails for the pin itself; this only means the chat message did not arrive. Response: ${body}"
  exit 0
fi

echo "notify: alert delivered to the configured webhook (HTTP $code)"
