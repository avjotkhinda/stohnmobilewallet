#!/usr/bin/env bash
#
# Fails when a scheduled workflow's alert-channel guidance drifts from docs/ALERTS.md.
#
# The guidance exists twice: as text a user reads on a red or warning-only run, and as the
# reference page those runs link to. Editing one and not the other is silent - the doc keeps
# describing a cadence or a missing-channel outcome the workflow no longer has, and the person
# following it either looks in the wrong place or expects the wrong colour. Nothing here is
# generated, so the agreement has to be asserted.
#
# What has to agree, for every workflow that runs on a schedule and mentions an alert channel:
#   * the guidance it shows links docs/ALERTS.md, so the short text on the run page reaches the
#     full one - either in the workflow itself (the watchdog builds its summary inline) or in a
#     script it invokes (the strict annotations come from scripts/notify-pin-liveness.sh).
#     Which of the two is accepted depends on where the workflow's guidance is written: a
#     workflow that writes it inline - a step summary or a warning/error annotation - has to
#     carry the link in its own text, because that is the text the reader sees. Otherwise a
#     summary that silently loses its link still passes on the link in a helper script the user
#     never opened. A workflow that writes no such text delegates the whole message to the
#     script, so the link there is the one that reaches the run page;
#   * every alert variable it names is documented (the *_VAR plumbing is skipped: those are the
#     copies the script reads from repository variables, not names a user sets);
#   * docs/ALERTS.md has a table row for it, and the cadence in that row matches its crons;
#   * the row's "Missing channel" outcome matches whether the workflow runs the notifier strictly.
# The reverse is checked too: a documented workflow must still exist and mention the channel, so
# the table cannot keep a row for a workflow that was renamed or lost its delivery step.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

docs=docs/ALERTS.md
[[ -f $docs ]] || { echo "ALERT GUIDANCE: FAIL: $docs is missing" >&2; exit 1; }

python3 - "$docs" <<'PY'
import glob
import os
import re
import sys

docs_path = sys.argv[1]
docs = open(docs_path, encoding="utf-8").read()

failures = []

def fail(message):
    failures.append(message)

# The table is the doc's claim about each workflow: cadence, what it sends, and what a missing
# channel costs. Parsed rather than trusted because that claim is exactly what drifts.
rows = {}
for line in docs.splitlines():
    match = re.match(r"^\|\s*`([^`]+)`\s*\|([^|]*)\|([^|]*)\|([^|]*)\|\s*$", line)
    if match:
        rows[match.group(1).strip()] = {
            "cadence": match.group(2).strip().lower(),
            "sends": match.group(3).strip(),
            "missing": match.group(4).strip().lower(),
        }

if not rows:
    fail(f"{docs_path} has no workflow table; the guidance checker cannot verify anything")

workflows = {}
for path in sorted(glob.glob(".github/workflows/*.yml")):
    name = os.path.basename(path)[:-len(".yml")]
    workflows[name] = (path, open(path, encoding="utf-8").read())

def cadence_of(cron):
    """The coarse cadence the doc table can describe, or None when the cron needs a new word."""
    fields = cron.split()
    if len(fields) != 5:
        return None
    _minute, _hour, day_of_month, month, day_of_week = fields
    if day_of_week not in ("*", "?"):
        return "weekly"
    if day_of_month == "*" and month == "*":
        return "daily"
    return None

for name, (path, text) in workflows.items():
    crons = re.findall(r"cron:\s*['\"]([^'\"]+)['\"]", text)
    names = {n for n in re.findall(r"\bPIN_ALERT_[A-Z0-9_]+\b", text) if not n.endswith("_VAR")}
    if not names:
        continue

    row = rows.get(name)
    if row is None:
        fail(f"{path} mentions {', '.join(sorted(names))} but {docs_path} has no table row for it")
        continue

    # Comments do not reach the reader, so they cannot satisfy the link requirement either.
    def without_comments(source):
        return "\n".join(
            line for line in source.splitlines() if not line.lstrip().startswith("#")
        )

    own_text = without_comments(text)
    writes_inline = "$GITHUB_STEP_SUMMARY" in own_text or bool(
        re.search(r"::(?:warning|error|notice)\b", own_text)
    )
    invoked = re.findall(r"\b(?:scripts|tools)/[A-Za-z0-9_./-]+\.sh\b", text)
    surfaces = [path] + list(dict.fromkeys(script for script in invoked if os.path.isfile(script)))
    if writes_inline:
        # The workflow writes the guidance itself, so the link has to be in that same text: a link
        # in a script it calls is invisible to whoever is reading the summary or the annotation.
        if docs_path not in own_text:
            fail(
                f"{path} writes its alert guidance inline without linking {docs_path} "
                f"in the workflow's own text; a link in the script it runs does not reach the "
                f"reader (checked: {', '.join(surfaces[1:]) or 'no scripts'})"
            )
    elif not any(docs_path in open(surface, encoding="utf-8").read() for surface in surfaces):
        fail(
            f"{path} gives alert guidance without linking {docs_path}, "
            f"in the workflow or in the scripts it runs ({', '.join(surfaces[1:]) or 'none'})"
        )

    for variable in sorted(names):
        if variable not in docs:
            fail(f"{path} names {variable}, which {docs_path} never documents")

    if not crons:
        if "hand-run" not in row["cadence"]:
            fail(
                f"{docs_path} describes {name} as {row['cadence']!r} but the workflow has no schedule"
            )
        continue

    cadences = {cadence_of(cron) for cron in crons}
    if None in cadences:
        fail(
            f"{path} has a cron {docs_path} has no word for ({', '.join(crons)}); "
            f"add its cadence to the table before relying on the guidance"
        )
        continue
    for cadence in sorted(cadences):
        if cadence not in row["cadence"]:
            fail(f"{path} runs {cadence} but {docs_path} describes {name} as {row['cadence']!r}")

    # CRON IS NOT THE WHOLE CLAIM: whether a missing channel is red or amber decides whether a
    # scheduled run that is merely unconfigured looks like an outage, so it has to match too.
    strict = bool(re.search(r"PIN_ALERT_STRICT\s*:\s*['\"]1['\"]", text))
    if strict and "fails the run" not in row["missing"]:
        fail(f"{path} runs the notifier strictly but {docs_path} says a missing channel is {row['missing']!r}")
    if not strict and "warning only" not in row["missing"]:
        fail(f"{path} warns when the channel is missing but {docs_path} says {row['missing']!r}")

for name, row in rows.items():
    entry = workflows.get(name)
    if entry is None:
        fail(f"{docs_path} documents {name}, which is not a workflow file")
        continue
    path, text = entry
    if "PIN_ALERT_" not in text:
        fail(f"{docs_path} documents {name} as an alert sender, but {path} never mentions one")

if failures:
    print(f"ALERT GUIDANCE: FAIL: {len(failures)} disagreement(s) with {docs_path}:", file=sys.stderr)
    for failure in failures:
        print(f"  {failure}", file=sys.stderr)
    sys.exit(1)

print(f"ALERT GUIDANCE: PASS: {len(rows)} documented workflow(s) match their guidance")
PY
