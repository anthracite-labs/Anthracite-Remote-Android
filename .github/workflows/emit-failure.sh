#!/bin/bash
# Print a short failure excerpt as GitHub annotations. The Actions log blob is
# not readable from the development environment, and the Checks API keeps only
# a small number of annotations.
set -euo pipefail
file=${1:?log file}
[ -f "$file" ] || exit 0
{
  grep -E 'Dependency |requires |requires libraries|minCompileSdk|AAR metadata|e: |FAILED|What went wrong|Execution failed' "$file" || true
  echo '--- tail ---'
  tail -n 25 "$file"
} | head -n 12 | while IFS= read -r line; do
  line=${line//$'\r'/}
  line=${line//::/:}
  line=${line//%/%25}
  printf '::error::%s\n' "$line"
done
