#!/bin/bash
# Print matching log lines as GitHub annotations so they can be read from the
# Checks API when the Actions log blob is not reachable.
set -euo pipefail
file=${1:?log file}
[ -f "$file" ] || exit 0
grep -E 'e: |FAILED|What went wrong|Execution failed|error:|Exception|AssertionError|Caused by:' "$file" \
  | head -n 60 \
  | while IFS= read -r line; do
      line=${line//$'\r'/}
      line=${line//::/:}
      line=${line//%/%25}
      printf '::error::%s\n' "$line"
    done
tail -n 40 "$file" | while IFS= read -r line; do
  line=${line//$'\r'/}
  line=${line//::/:}
  line=${line//%/%25}
  printf '::error::%s\n' "$line"
done
