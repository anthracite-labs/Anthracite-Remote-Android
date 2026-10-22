#!/bin/bash
# Print a short, bounded failure excerpt as GitHub annotations.
#
# The Actions log blob is not always reachable outside the runner and the Checks
# API keeps only a small number of annotations, so the excerpt is assembled here:
# JUnit failure messages when a report exists, then compiler/resource errors, then
# the tail of the log (which is where Gradle states the actual reason a task
# failed, such as "No tests found for given includes").
#
# No `set -e`: a reporter must never be the thing that fails the job, and a
# pipeline into `head` would otherwise raise "Broken pipe" under `pipefail`.
set -uo pipefail

file=${1:?usage: emit-failure.sh <log file> [max lines]}
limit=${2:-12}

[ -f "$file" ] || exit 0

details=$(mktemp)
trap 'rm -f "$details"' EXIT

if [ -d app/build/test-results/testDebugUnitTest ]; then
  python3 - >>"$details" <<'PY' || true
import glob
import xml.etree.ElementTree as ET

for filename in sorted(glob.glob("app/build/test-results/testDebugUnitTest/**/*.xml", recursive=True)):
    try:
        root = ET.parse(filename).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        failure = next((child for child in case if child.tag in {"failure", "error"}), None)
        if failure is None:
            continue
        message = (failure.attrib.get("message") or "").replace("\n", " ").strip()
        detail = (failure.text or "").strip().splitlines()
        source = next((line.strip() for line in detail if ".kt:" in line or ".java:" in line), None)
        suffix = f" — {source or detail[0]}" if detail else ""
        print(f"JUnit {case.attrib.get('classname', '?')}.{case.attrib.get('name', '?')}: {message}{suffix}")
PY
fi

# Resource linking and javac report differently from Kotlin; surface those first
# so a resource error is not hidden behind a wall of `e:` lines.
grep -E '^ERROR:|^e: |: error: ' "$file" >>"$details" 2>/dev/null || true
grep -E 'No tests found|tests completed|FAILED|What went wrong|Execution failed|Could not resolve|requires libraries|Dependency ' \
  "$file" >>"$details" 2>/dev/null || true

count=0
while IFS= read -r line; do
  [ -n "$line" ] || continue
  case "$line" in
    "--- log tail ---") continue ;;
  esac
  line=${line//$'\r'/}
  line=${line//::/:}
  line=${line//%/%25}
  printf '::error::%s\n' "$line"
  count=$((count + 1))
  if [ "$count" -ge "$limit" ]; then
    printf '::notice::Further distinct errors were suppressed; the full log is in the job artifact.\n'
    break
  fi
done <"$details"

# The tail always follows: without it a task-level failure is reported only as
# "task FAILED", with no reason attached.
while IFS= read -r line; do
  [ -n "$line" ] || continue
  line=${line//$'\r'/}
  line=${line//::/:}
  line=${line//%/%25}
  printf '::error::%s\n' "$line"
done < <(tail -n 8 "$file" 2>/dev/null)

exit 0
