#!/bin/bash
# Print a short failure excerpt as GitHub annotations. The Actions log blob is
# not readable from the development environment, and the Checks API keeps only
# a small number of annotations. Prefer the JUnit failure message when it exists:
# it identifies a failed assertion rather than only Gradle's task wrapper.
set -euo pipefail
file=${1:?log file}
[ -f "$file" ] || exit 0
{
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
  # aapt2 and javac report differently from Kotlin; surface those first so a
  # resource error is not hidden behind a wall of `e:` lines.
  grep -E 'ERROR:|: error:|error: ' "$file" | head -n 10 || true
  grep -E 'Dependency |requires |requires libraries|minCompileSdk|AAR metadata|e: |FAILED|What went wrong|Execution failed' "$file" || true
  echo '--- tail ---'
  tail -n 25 "$file"
} | head -n 24 | while IFS= read -r line; do
  line=${line//$'\r'/}
  line=${line//::/:}
  line=${line//%/%25}
  printf '::error::%s\n' "$line"
done
