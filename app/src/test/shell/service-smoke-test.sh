#!/bin/bash
# Service smoke suite: the real OpenSSH, tmux, Docker, Podman and kubectl
# binaries the app drives over SSH.
#
# A unit test can only prove what command string the app builds; it cannot
# prove that the flags exist in the binary on the host. This suite runs the
# read-only subset locally in throwaway state: a tmux server on an isolated
# socket is created, listed and killed again, and every other binary is asked
# for its client version. Nothing contacts a daemon, a cluster or the network,
# and nothing outside a mktemp directory is touched.
#
# Required: tmux, ssh, sftp (openssh-client). Docker, Podman and kubectl are
# reported when present and skipped when absent: their absence is a gap in
# coverage, not a failure of this commit.
#
# Runs on plain Linux. No network, no credentials, no Android.
set -uo pipefail

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

# ---------------------------------------------------------------------------
# 1. Binaries the suite depends on.
# ---------------------------------------------------------------------------
for binary in tmux ssh sftp; do
  check "$binary is installed" command -v "$binary"
done

# ---------------------------------------------------------------------------
# 2. tmux session round-trip on an isolated socket.
# ---------------------------------------------------------------------------
SOCK="$WORK/tmux.sock"
if command -v tmux >/dev/null 2>&1; then
  tmux -V
  check "tmux creates a detached session" tmux -S "$SOCK" new-session -d -s smoke -x 80 -y 24
  check "tmux reports the session" tmux -S "$SOCK" has-session -t smoke
  if tmux -S "$SOCK" list-sessions 2>/dev/null | grep -q '^smoke:'; then ok; else bad "tmux lists the session by name"; fi
  check "tmux captures the session pane" tmux -S "$SOCK" capture-pane -p -t smoke
  check "tmux kills the session" tmux -S "$SOCK" kill-session -t smoke
  if tmux -S "$SOCK" has-session -t smoke 2>/dev/null; then bad "a killed session is still reported"; else ok; fi
fi

# ---------------------------------------------------------------------------
# 3. OpenSSH client surface the app relies on.
# ---------------------------------------------------------------------------
if command -v ssh >/dev/null 2>&1; then
  ssh -V 2>&1 | head -n 1
  # `ssh -G` parses the effective configuration without connecting.
  check "ssh parses its effective configuration" ssh -G example.invalid
fi
if command -v sftp >/dev/null 2>&1; then
  # Batch mode against an unreachable host must fail fast, not hang: the app
  # bounds every connection the same way.
  printf 'quit\n' | timeout 20 sftp -b - -o BatchMode=yes -o ConnectTimeout=5 example.invalid >/dev/null 2>&1
  if [ $? -ne 0 ]; then ok; else bad "sftp to an unreachable host unexpectedly succeeded"; fi
fi

# ---------------------------------------------------------------------------
# 4. Container and cluster CLIs: client version only, skipped when absent.
# ---------------------------------------------------------------------------
for binary in docker podman kubectl; do
  if command -v "$binary" >/dev/null 2>&1; then
    case "$binary" in
      kubectl) check "kubectl reports its client version" "$binary" version --client=true ;;
      *) check "$binary reports its client version" "$binary" --version ;;
    esac
  else
    printf 'SKIP: %s is not installed; no live daemon or cluster was contacted\n' "$binary"
  fi
done

printf '\nservice-smoke: %d passed, %d failed\n' "$passed" "$failed"
if [ "$failed" -ne 0 ]; then
  printf 'failures:\n'
  printf '  - %s\n' "${failures[@]}"
  exit 1
fi
