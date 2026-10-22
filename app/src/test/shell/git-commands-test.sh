#!/bin/bash
# Real-git regression suite for the command grammar in gitx/HostGit.kt.
#
# This exists because a unit test can only prove what the Kotlin builder emits; it
# cannot prove that the emitted flags are accepted by the git binary on the host.
# The suite therefore pairs a source assertion with actual git invocations in
# throwaway repositories, including a real remote for fetch/pull/push.
#
# Runs on plain Linux with git >= 2.28. No network, no credentials, no Android.
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
GIT_SOURCE="$REPO/app/src/main/java/dev/anthracite/remote/gitx/HostGit.kt"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

passed=0
failed=0
failures=()

ok() { passed=$((passed + 1)); }
bad() {
  failed=$((failed + 1))
  failures+=("$1")
  printf 'FAIL: %s\n' "$1" >&2
}
check() { # check <description> <command...>
  local description=$1
  shift
  if "$@" >/dev/null 2>&1; then ok; else bad "$description"; fi
}

export GIT_AUTHOR_NAME=Anthracite GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=Anthracite GIT_COMMITTER_EMAIL=test@example.invalid
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
export GIT_TERMINAL_PROMPT=0

git --version

# ---------------------------------------------------------------------------
# 1. Platform facts the command builder depends on.
# ---------------------------------------------------------------------------
plain="$WORK/plain"
mkdir -p "$plain"
git -C "$plain" init --quiet --initial-branch=main
printf 'one\n' > "$plain/file.txt"
git -C "$plain" add file.txt
git -C "$plain" commit --quiet -m one

# fetch/pull/push need a remote before the option itself can be judged.
plain_remote="$WORK/plain-remote.git"
git init --quiet --bare --initial-branch=main "$plain_remote"
git -C "$plain" remote add origin "$plain_remote"
git -C "$plain" -c color.ui=false push --quiet --set-upstream origin main

for subcommand in status fetch pull push; do
  args=()
  case "$subcommand" in
    status) args=(status --no-color --porcelain=v2 --branch) ;;
    fetch) args=(fetch --no-color) ;;
    pull) args=(pull --no-color --ff-only) ;;
    push) args=(push --no-color) ;;
  esac
  if git -C "$plain" "${args[@]}" >/dev/null 2>&1; then
    bad "git $subcommand unexpectedly accepted --no-color; the builder's workaround is now unnecessary"
  else
    ok
  fi
done

# The global option must be accepted by the same subcommands.
for subcommand in status fetch pull push log branch diff; do
  args=()
  case "$subcommand" in
    status) args=(status --porcelain=v2 --branch) ;;
    fetch) args=(fetch --prune) ;;
    pull) args=(pull --ff-only) ;;
    push) args=(push --dry-run) ;;
    log) args=(log -n 1 --pretty=format:%H) ;;
    branch) args=(branch --format='%(refname:short)') ;;
    diff) args=(diff --no-ext-diff --unified=3) ;;
  esac
  if git -c color.ui=false -C "$plain" "${args[@]}" >/dev/null 2>&1; then ok; else
    bad "git -c color.ui=false $subcommand is not accepted"
  fi
done

# ---------------------------------------------------------------------------
# 2. The builder must use the global option and must not pass --no-color to a
#    subcommand that rejects it.
# ---------------------------------------------------------------------------
check "GitCommands prefixes every command with -c color.ui=false" \
  grep -q 'git -c color.ui=false -C' "$GIT_SOURCE"

for helper in fetch pull status; do
  pattern="fun $helper(directory: String): String = \"\\\${base(directory)} .*--no-color"
  if grep -Eq "$pattern" "$GIT_SOURCE"; then
    bad "GitCommands.$helper still passes --no-color"
  else
    ok
  fi
done

if grep -Eq 'push \(\?|--force-with-lease --no-color' "$GIT_SOURCE"; then
  bad "GitCommands.push still passes --no-color"
else
  ok
fi

# ---------------------------------------------------------------------------
# 3. End to end against a real remote: fetch, ff-only pull, push,
#    force-with-lease.
# ---------------------------------------------------------------------------
remote="$WORK/remote.git"
git init --quiet --bare --initial-branch=main "$remote"

clone="$WORK/clone"
git clone --quiet "$remote" "$clone" 2>/dev/null || {
  # A bare repository with no commits cannot be cloned on older git; seed it.
  git -C "$plain" remote add origin "$remote"
  git -C "$plain" push --quiet origin main
  git clone --quiet "$remote" "$clone"
}
cd "$clone" || exit 1
git checkout --quiet main 2>/dev/null || git checkout --quiet -b main
printf 'from clone\n' > clone.txt
git add clone.txt
git commit --quiet -m "from clone"

check "push to a real remote" git -c color.ui=false -C "$clone" push origin main
check "fetch --prune from a real remote" git -c color.ui=false -C "$clone" fetch --prune
check "pull --ff-only on a fast-forwardable branch" git -c color.ui=false -C "$clone" pull --ff-only

# Diverge the remote so --ff-only must refuse, not merge.
second="$WORK/second"
git clone --quiet "$remote" "$second"
printf 'remote change\n' > "$second/remote.txt"
git -C "$second" add remote.txt
git -C "$second" commit --quiet -m "remote change"
git -C "$second" -c color.ui=false push origin main >/dev/null 2>&1

printf 'local change\n' > "$clone/local.txt"
git -C "$clone" add local.txt
git -C "$clone" commit --quiet -m "local change"
if git -c color.ui=false -C "$clone" pull --ff-only >/dev/null 2>&1; then
  bad "pull --ff-only merged a diverged branch instead of refusing"
else
  ok
fi

# force-with-lease must succeed when the remote is where we last fetched it.
printf 'rewritten\n' > "$clone/local.txt"
git -C "$clone" add local.txt
git -C "$clone" commit --quiet --amend -m "rewritten history"
git -c color.ui=false -C "$clone" fetch --quiet origin
if git -c color.ui=false -C "$clone" push --force-with-lease origin main >/dev/null 2>&1; then
  ok
else
  bad "push --force-with-lease failed against an unchanged remote"
fi

# ---------------------------------------------------------------------------
# 4. switch/restore forms used by checkout and unstage.
# ---------------------------------------------------------------------------
printf 'dirty\n' >> "$clone/local.txt"
git -C "$clone" add local.txt
if git -c color.ui=false -C "$clone" restore --staged -- local.txt >/dev/null 2>&1; then
  ok
else
  bad "restore --staged -- <path> failed"
fi
if git -c color.ui=false -C "$clone" switch -- main >/dev/null 2>&1; then
  ok
else
  bad "switch -- main failed"
fi

# ---------------------------------------------------------------------------
# 5. Paths that need quoting stay one argument.
# ---------------------------------------------------------------------------
mkdir -p "$clone/dir with space"
printf 'x\n' > "$clone/dir with space/a'b.txt"
git -C "$clone" add "dir with space/a'b.txt"
if git -c color.ui=false -C "$clone" status --porcelain=v2 --branch -z | grep -q "a'b.txt"; then
  ok
else
  bad "a quoted path with a space and a quote did not survive the round trip"
fi

printf '\n%d passed, %d failed\n' "$passed" "$failed"
if [[ "$failed" -ne 0 ]]; then
  printf 'failures:\n' >&2
  for failure in "${failures[@]}"; do printf ' - %s\n' "$failure" >&2; done
  exit 1
fi
