#!/bin/bash
# Emits one slice of the collected build errors as check annotations.
#
# GitHub shows at most ten error annotations per step, so the workflow calls this
# with slice=1..6 on separate steps. That gives up to sixty distinct compiler
# errors in one run, which is what makes a single diagnostic pass sufficient.
set -euo pipefail

out_dir=${1:?usage: annotate-slice.sh <dir> <slice> [size]}
slice=${2:?slice index, starting at 1}
size=${3:-10}

file="$out_dir/build-errors.txt"
[ -f "$file" ] || exit 0

start=$(( (slice - 1) * size ))
total=$(wc -l < "$file")
[ "$total" -eq 0 ] && exit 0

sed -n "$((start + 1)),$((start + size))p" "$file" | while IFS= read -r line; do
  [ -n "$line" ] || continue
  line=${line//$'\r'/}
  line=${line//%/%25}
  printf '::error::%s\n' "$line"
done
exit 0
