#!/usr/bin/env bash
set -euo pipefail
# FILES.txt is the project's inventory of its own files. It drifts silently whenever a file is
# added, renamed or deleted without updating it, so this check regenerates the inventory in the
# same format ("./"-prefixed, one path per line, byte-sorted) and fails on any difference.
#
# The inventory lists tracked files: regenerating it is
#   git ls-files | sed 's|^|./|' | LC_ALL=C sort > FILES.txt
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="$ROOT/FILES.txt"
fail(){ echo "FILES DRIFT: FAIL: $*" >&2; exit 1; }
pass(){ echo "FILES DRIFT: PASS: $*"; }

[[ -f "$LIST" ]] || fail "FILES.txt is missing"
cd "$ROOT"
git ls-files >/dev/null 2>&1 || fail "not a git working tree; there is nothing to compare against"

EXPECTED="$(mktemp)"
ACTUAL="$(mktemp)"
trap 'rm -f "$EXPECTED" "$ACTUAL"' EXIT

git ls-files -z | tr '\0' '\n' | sed 's|^|./|' | LC_ALL=C sort > "$EXPECTED"

if grep -q $'\r' "$LIST"; then
  fail "FILES.txt has CRLF line endings; the inventory is LF-only"
fi
if grep -q '^$' "$LIST"; then
  fail "FILES.txt has blank lines"
fi
if ! LC_ALL=C sort -c "$LIST" >/dev/null 2>&1; then
  fail "FILES.txt is not in byte-sorted order"
fi
tr -d '\r' < "$LIST" | LC_ALL=C sort > "$ACTUAL"

missing="$(comm -23 "$EXPECTED" "$ACTUAL")"
extra="$(comm -13 "$EXPECTED" "$ACTUAL")"

status=0
if [[ -n "$missing" ]]; then
  echo "FILES DRIFT: tracked but missing from FILES.txt:" >&2
  echo "$missing" >&2
  status=1
fi
if [[ -n "$extra" ]]; then
  echo "FILES DRIFT: listed in FILES.txt but not tracked by git:" >&2
  echo "$extra" >&2
  status=1
fi
if (( status != 0 )); then
  fail "FILES.txt does not match the tracked files; regenerate it with: git ls-files | sed 's|^|./|' | LC_ALL=C sort > FILES.txt"
fi

pass "FILES.txt matches the $(wc -l < "$EXPECTED" | tr -d ' ') tracked files"
