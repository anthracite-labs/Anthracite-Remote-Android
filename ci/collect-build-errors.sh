#!/bin/bash
# Collects every independent compiler/resource error from a Gradle log so that a
# single CI run reports the whole inventory instead of stopping at the first
# failure. GitHub caps annotations at ten per step, so the caller asks for
# successive slices; the full list also goes to the job summary and an artifact.
set -euo pipefail

log=${1:?usage: collect-build-errors.sh <log> [out-dir]}
out_dir=${2:-ci-diagnostics}

[ -f "$log" ] || exit 0
mkdir -p "$out_dir"

filter() {
  grep -E '^(e|w): file:|^[A-Za-z0-9_./-]+\.(kt|java|xml):[0-9]+: (error|warning):|^error: |What went wrong|Execution failed for task|> Task .* FAILED|Could not resolve all files|Dependency .* requires|requires libraries' "$1" || true
}

# Kotlin repeats a chain of follow-on errors for one unresolved symbol, and every
# Gradle invocation repeats the same task banner; keep the first occurrence of
# each distinct line so the ten-annotation budget is spent on distinct faults.
filter "$log" | sed 's/[[:space:]]*$//' | awk '!seen[$0]++' > "$out_dir/build-errors.txt"

count=$(wc -l < "$out_dir/build-errors.txt")
echo "distinct build errors: $count"

{
  echo "### Build diagnostics"
  echo
  echo "Distinct compiler/resource errors: **$count**"
  echo
  if [ "$count" -eq 0 ]; then
    echo "No compiler or resource errors were parsed from the log. The failure is elsewhere; see the failing step."
  else
    echo '```text'
    head -n 200 "$out_dir/build-errors.txt"
    echo '```'
  fi
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"

# Exit status is deliberately zero: this is a reporting step. The caller's gate
# step decides whether the job fails.
exit 0
