#!/bin/bash
# Print a short failure excerpt as GitHub annotations.
#
# The Actions log blob is not always readable from the development environment and
# the Checks API keeps only a small number of annotations, so this prints the JUnit
# failure message when one exists: it identifies a failed assertion rather than
# only Gradle's task wrapper. Output is capped without piping into `head` inside a
# `pipefail` pipeline, which used to surface as a spurious "Broken pipe".
set -euo pipefail

file=${1:?usage: emit-failure.sh <log file> [max lines]}
limit=${2:-12}

[ -f "$file" ] || exit 0

details=$(
  if [ -d app/build/test-results/testDebugUnitTest ]; then
    python3 - <<'PY' || true
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
        message = failure.attrib.get("message", "").replace("\n", " ").strip()
        detail = (failure.text or "").strip().splitlines()
        source = next((line.strip() for line in detail if ".kt:" in line or ".java:" in line), None)
        suffix = f" — {source or detail[0]}" if detail else ""
        print(f"JUnit {case.attrib.get('classname', '?')}.{case.attrib.get('name', '?')}: {message}{suffix}")
PY
  fi
  # Resource linking and javac report differently from Kotlin; surface those
  # first so a resource error is not hidden behind a wall of `e:` lines.
  grep -E 'ERROR:|: error:|error: ' "$file" || true
  grep -E 'Dependency |requires |requires libraries|minCompileSdk|AAR metadata|e: |FAILED|What went wrong|Execution failed' "$file" || true
  if ! grep -qE 'ERROR:|e: |FAILED|What went wrong|Execution failed' "$file"; then
    printf -- '--- log tail ---\n'
    tail -n 20 "$file" || true
  fi
)

count=0
while IFS= read -r line; do
  [ -n "$line" ] || continue
  line=${line//$'\r'/}
  line=${line//::/:}
  line=${line//%/%25}
  printf '::error::%s\n' "$line"
  count=$((count + 1))
  if [ "$count" -ge "$limit" ]; then
    printf '::notice::Further distinct errors were suppressed; the full log is in the job artifact.\n'
    break
  fi
done <<< "$details"
exit 0
