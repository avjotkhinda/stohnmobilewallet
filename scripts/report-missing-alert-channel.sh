#!/usr/bin/env bash
#
# Prints the standing "no alert channel" configuration gap for the scheduled workflows.
#
# The text lives here because it otherwise exists twice - schedule-watchdog and pin-liveness both
# have to say the same thing - and the two copies drifted three times before this script: different
# settings routes, then a whole paragraph present in one and missing from the other. Each workflow
# passes only the clause that is its own, which failure still turns the run red, so the part a
# reader acts on cannot disagree.
#
# It warns and exits 0 on purpose. A missing channel is a setting, and the run that would report it
# is the run that reports the outage; a red every morning for a setting would hide the failure it
# sits next to. The workflows decide when to run this - always, but in pin-liveness not on a dry run.
#
# Usage: report-missing-alert-channel.sh "<what still fails this run>"
set -euo pipefail

if [[ -n "${PIN_ALERT_WEBHOOK_URL:-}" || -n "${PIN_ALERT_WEBHOOK_URL_FALLBACK:-}" ||
      -n "${PIN_ALERT_WEBHOOK_URL_VAR:-}" || -n "${PIN_ALERT_WEBHOOK_URL_FALLBACK_VAR:-}" ]]; then
  echo "alert channel: configured"
  exit 0
fi

still_fails=${1:-}
if [[ -z $still_fails ]]; then
  echo "usage: $0 \"<what still fails this run>\"" >&2
  exit 2
fi
: "${GH_REPO:?GH_REPO must name the repository, owner/name}"

origin=${GITHUB_SERVER_URL:-https://github.com}
new_secret_url="https://github.com/${GH_REPO}/settings/secrets/actions/new"
new_variable_url="https://github.com/${GH_REPO}/settings/variables/actions/new"

# The summary is the run page without opening a log; the annotation is what surfaces in the checks
# list. Both must stand alone, so each names the setting and where to set it.
{
  echo "## No alert channel configured"
  echo
  echo "The missing channel is a gap, not a failure - this step runs even when a check above already failed, so its green says nothing about those checks: read them for their result, and fix this gap on its own."
  echo
  echo 'Set **one** of these two, then re-run this workflow. Both are repository-level, and the name has to match exactly - a near-miss is not read:'
  echo
  echo "1. **Repository secret** \`PIN_ALERT_WEBHOOK_URL\` - ${new_secret_url}"
  echo '   Settings -> Secrets and variables -> Actions -> **Secrets** -> **New repository secret**, name `PIN_ALERT_WEBHOOK_URL`, value = the Slack incoming webhook URL.'
  echo "2. **Repository variable** \`PIN_ALERT_WEBHOOK_URL\` - ${new_variable_url}"
  echo '   Settings -> Secrets and variables -> Actions -> **Variables** -> **New repository variable**, same name and value. A variable delivers too, but anyone with read access to this repository can read it, so the secret is the better home.'
  echo
  echo 'It must not be an *environment* secret or variable: this workflow declares no `environment:`, so one scoped to an environment is invisible to it.'
  echo
  echo 'Optional second route: `PIN_ALERT_WEBHOOK_URL_FALLBACK` - any webhook that accepts `{"text": ...}`, in either tab.'
  echo
  echo 'To prove delivery first: Actions -> **alert-test** -> **Run workflow** posts one labelled test message and goes red if nothing arrives.'
  echo "Full setup and troubleshooting: [docs/ALERTS.md](${origin}/${GH_REPO}/blob/main/docs/ALERTS.md)"
  echo 'Once either name exists this step logs `alert channel: configured`; until then the checks above are still reported normally.'
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"

echo "::warning title=No alert channel configured::Set the repository secret PIN_ALERT_WEBHOOK_URL (${new_secret_url}) or a repository variable of the same name (${new_variable_url}); optional second route PIN_ALERT_WEBHOOK_URL_FALLBACK, in either tab. Run the alert-test workflow to prove delivery; setup and troubleshooting: docs/ALERTS.md. ${still_fails}; only the missing channel is a warning."
exit 0
