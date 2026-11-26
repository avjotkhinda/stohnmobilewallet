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
#     carry the link inside that same summary or annotation block, because that block is the
#     text the reader sees. A link in another step, or in a different annotation of the same
#     step, does not reach that reader; a summary that silently lost its link would otherwise
#     still pass on a link the workflow happens to print elsewhere. A workflow that writes no
#     such text delegates the whole message to the script, so the link there is the one that
#     reaches the run page;
#   * every alert variable it names is documented (the *_VAR plumbing is skipped: those are the
#     copies the script reads from repository variables, not names a user sets);
#   * docs/ALERTS.md has a table row for it, and the cadence in that row matches its crons;
#   * the row's "Missing channel" outcome matches whether the workflow runs the notifier strictly.
# The reverse is checked too: a documented workflow must still exist and mention the channel, so
# the table cannot keep a row for a workflow that was renamed or lost its delivery step. The step
# names listed under "Which steps read it" are checked the same way against the workflow that runs
# them, because a step rename is otherwise invisible to this page.
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

STEP_START = re.compile(r"^\s*-\s+\S")
RUN_BLOCK = re.compile(r"^(\s*)run:\s*([|>][-+]?)?\s*$")
ANNOTATION = re.compile(r"::(?:warning|error|notice)\b")


def without_comments(source):
    """Comments do not reach the reader, so they cannot satisfy the link requirement either."""
    return "\n".join(
        line for line in source.splitlines() if not line.lstrip().startswith("#")
    )


def alert_steps(text):
    """(label, lines) for each workflow step that names an alert variable.

    The step is the scope the blocks are read from: its env decides whether the summary or
    annotation it writes is about the channel at all, and a step is far cheaper to delimit
    reliably than the shell around a redirection.
    """
    steps = []
    lines = text.splitlines()
    starts = [index for index, line in enumerate(lines) if STEP_START.match(line)]
    for position, start in enumerate(starts):
        end = starts[position + 1] if position + 1 < len(starts) else len(lines)
        body = lines[start:end]
        joined = "\n".join(body)
        if not re.search(r"\bPIN_ALERT_[A-Z0-9_]+\b", joined):
            continue
        name = re.search(r"^\s*-\s+name:\s*(.+?)\s*$", joined, re.M)
        steps.append((name.group(1) if name else f"the step at line {start + 1}", body))
    return steps


def run_scripts(body):
    """[[lines]] for each run: scalar in one step."""
    scripts = []
    index = 0
    while index < len(body):
        match = RUN_BLOCK.match(body[index])
        if match and match.group(2):
            indent = len(match.group(1))
            script = []
            index += 1
            while index < len(body):
                line = body[index]
                if line.strip() and len(line) - len(line.lstrip()) <= indent:
                    break
                script.append(line)
                index += 1
            scripts.append(script)
            continue
        single = re.match(r"^\s*run:\s*(\S.*)$", body[index])
        if single:
            scripts.append([single.group(1)])
        index += 1
    return scripts


def grouping_characters(line):
    """(index, character) for the braces that group commands, skipping ${...} and $(...)."""
    index = 0
    while index < len(line):
        opener = line[index:index + 2]
        if opener in ("${", "$("):
            depth = 1
            closer = "}" if opener == "${" else ")"
            index += 2
            while index < len(line) and depth:
                if line[index] == opener[1]:
                    depth += 1
                elif line[index] == closer:
                    depth -= 1
                index += 1
            continue
        yield index, line[index]
        index += 1


def enclosing_group(lines, index):
    """(start, end) of the { ... } group that the redirection on lines[index] closes."""
    depth = 0
    start = None
    for current in range(index, -1, -1):
        for _, character in reversed(list(grouping_characters(lines[current]))):
            if character == "}":
                depth += 1
            elif character == "{":
                depth -= 1
                if depth == 0:
                    start = current
                    break
        if start is not None:
            break
    if start is None:
        return index, index
    depth = 1
    for current in range(start + 1, len(lines)):
        for _, character in grouping_characters(lines[current]):
            if character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth == 0:
                    return start, current
    return start, len(lines) - 1


def guidance_blocks(label, scripts):
    """(where, text) for the summary and annotation blocks in one step.

    The text is the block itself, not the step: the group redirected into $GITHUB_STEP_SUMMARY,
    or the single annotation statement. That block is the text the run page shows, so that is
    what has to carry the link.
    """
    blocks = []
    for script in scripts:
        lines = without_comments("\n".join(script)).splitlines()
        for index, line in enumerate(lines):
            if re.search(r"\$\{?GITHUB_STEP_SUMMARY\b", line):
                start, end = enclosing_group(lines, index)
                blocks.append(
                    (
                        f"the job-summary block of step {label!r}",
                        "\n".join(lines[start:end + 1]),
                    )
                )
            if ANNOTATION.search(line):
                blocks.append((f"the annotation in step {label!r}", line))
    return blocks


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

    invoked = re.findall(r"\b(?:scripts|tools)/[A-Za-z0-9_./-]+\.sh\b", text)
    surfaces = [path] + list(dict.fromkeys(script for script in invoked if os.path.isfile(script)))
    inline_blocks = [
        block
        for label, step in alert_steps(text)
        for block in guidance_blocks(label, run_scripts(step))
    ]
    if inline_blocks:
        # The workflow writes the guidance itself, so the link has to be inside the very block
        # that carries it. A link printed by another step - or by another annotation in the same
        # step - never appears next to this summary or annotation, so it cannot stand in for it.
        for where, block in inline_blocks:
            if docs_path not in block:
                fail(
                    f"{path} writes alert guidance in {where} without linking {docs_path} "
                    f"inside that same block; a link elsewhere in the workflow does not reach "
                    f"the reader of this summary or annotation"
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

# The doc also names the steps that read the channel, by their display name. Renaming a step in a
# workflow leaves that list pointing at names nothing runs, and no other check reads it: the run
# page shows a step the reference page does not describe, and a reader looking for the exact step
# name finds nothing. So each listed name has to still be the name of a step in that workflow.
section = re.search(r"^### Which steps read it[^\n]*\n(.*?)(?=^#{1,3} |\Z)", docs, re.M | re.S)
if section is None:
    fail(f"{docs_path} has no 'Which steps read it' section, so the step list cannot be checked")
else:
    listed = {}
    current = None
    for line in section.group(1).splitlines():
        head = re.match(r"^-\s*`([^`]+)`\s*:?\s*$", line)
        if head:
            current = head.group(1).strip()
            listed.setdefault(current, [])
            continue
        step = re.match(r"^\s+-\s*\*\*(.+?)\*\*\s*$", line)
        if step and current is not None:
            listed[current].append(step.group(1).strip())
            continue
        # An entry this parser cannot read is a step the check would silently stop covering, so it
        # is a failure rather than a line to skip.
        if re.match(r"^\s+-\s+\S", line):
            fail(
                f"{docs_path} lists {line.strip()!r} under 'Which steps read it' in a form this "
                f"check cannot read; write it as '- **Step name**'"
            )

    for name, steps in listed.items():
        entry = workflows.get(name)
        if entry is None:
            fail(f"{docs_path} lists alert steps for {name}, which is not a workflow file")
            continue
        path, text = entry
        # Step names only: job names sit on ``name:`` without the list dash, so they cannot be
        # mistaken for a step, and comments are not renamed by a reader.
        known = {
            match.group(1).strip()
            for match in re.finditer(r"^\s*-\s+name:\s*(.+?)\s*$", text, re.M)
        }
        for step in steps:
            if step not in known:
                fail(
                    f"{docs_path} lists the step {step!r} in {name}, but {path} has no step with "
                    f"that name; a rename has to update the list"
                )

if failures:
    print(f"ALERT GUIDANCE: FAIL: {len(failures)} disagreement(s) with {docs_path}:", file=sys.stderr)
    for failure in failures:
        print(f"  {failure}", file=sys.stderr)
    sys.exit(1)

print(f"ALERT GUIDANCE: PASS: {len(rows)} documented workflow(s) match their guidance")
PY
