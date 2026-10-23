#!/bin/sh
# Anthracite provisioning job cancel (host side) — compare-and-signal.
#
# Usage: sh anthracite-provision-cancel.sh <job-directory>
#
# The phone can ask for a stop, but it neither chooses the PID nor sends the
# signal. Everything is re-derived here, immediately before signalling:
#
#   * the job directory is exactly "$HOME/.anthracite/jobs/<16 lowercase hex>",
#     is a real directory rather than a symlink, and is owned by this user;
#   * pid / start_ticks / state are regular files owned by this user, and the
#     recorded pid and start time are well formed (never 0, never 1);
#   * the live process exists, is owned by this user, still has the recorded
#     start time (field 22 of /proc/<pid>/stat), and its command line names both
#     the reviewed runner and this job id.
#
# Any missing or inconsistent fact is refused and nothing is signalled. That is
# the whole point of the script: a PID recorded on the phone an hour ago proves
# nothing about the process that owns that number now. The remaining window
# between the checks and the kill is microseconds and cannot be closed without
# kernel support; it is not widened by trusting an old observation.
#
# Exactly one SIGTERM is sent, never SIGKILL: the runner traps TERM and stops at
# a command boundary, so a package transaction finishes and no package-manager
# lock is removed or broken.
#
# Output is `key=value` lines, always on stdout, always with exit 0 once the job
# argument is present:
#   cancel_result = refused_invalid_job | refused_unknown_identity |
#                   refused_not_owner | already_finished | signal_sent |
#                   still_running | confirmed_stopped | signal_failed
#   state, pid, process_alive, verification, reason
#
#   signal_sent      — the verified runner was signalled and is still running
#                      (it is probably inside a package transaction).
#   still_running    — a stop had already been requested and the same verified
#                      process is still running; this request confirmed that.
#   confirmed_stopped— the process is gone. A kill syscall returning success is
#                      never reported as this; only an observed exit is.

set -u
umask 077

emit() {
    printf '%s=%s\n' "$1" "$2"
}

PID=""
STATE=""
ALIVE="unknown"

finish() { # result, reason
    emit cancel_result "$1"
    emit reason "$2"
    emit state "${STATE:-unknown}"
    emit pid "${PID:-none}"
    emit process_alive "$ALIVE"
    exit 0
}

refuse() { # result, reason
    emit verification none
    finish "$1" "$2"
}

JOB_ARG=${1:-}
if [ -z "$JOB_ARG" ]; then
    emit cancel_result refused_invalid_job
    emit reason missing_job_argument
    emit pid none
    emit process_alive unknown
    emit verification none
    exit 64
fi

JOBS_ROOT="${HOME:-/nonexistent}/.anthracite/jobs"
[ -n "${HOME:-}" ] && [ -d "$JOBS_ROOT" ] || refuse refused_invalid_job jobs_root_missing

case "$JOB_ARG" in
    /*) JOB=$JOB_ARG ;;
    *) JOB="$(pwd)/$JOB_ARG" ;;
esac

[ -L "$JOB" ] && refuse refused_invalid_job job_dir_symlink
[ -d "$JOB" ] || refuse refused_invalid_job job_dir_missing

CANONICAL=$(cd "$JOB" 2>/dev/null && pwd -P) || refuse refused_invalid_job job_dir_unresolvable
ROOT_CANONICAL=$(cd "$JOBS_ROOT" 2>/dev/null && pwd -P) || refuse refused_invalid_job jobs_root_unresolvable
JOB_ID=$(basename "$CANONICAL")
case "$JOB_ID" in
    *[!a-f0-9]*) refuse refused_invalid_job job_id_grammar ;;
esac
[ "${#JOB_ID}" -eq 16 ] || refuse refused_invalid_job job_id_grammar
# The same directory, reached the same way: no symlinked jobs root, no traversal.
[ "$CANONICAL" = "$ROOT_CANONICAL/$JOB_ID" ] || refuse refused_invalid_job job_dir_outside_root

uid_of() {
    ls -nd "$1" 2>/dev/null | tr -s ' ' | cut -d' ' -f3
}

ME=$(id -u)
[ "$(uid_of "$JOB")" = "$ME" ] || refuse refused_not_owner job_dir_owner

# A file the runner writes must be ours, regular, and not a link.
# A zombie has already exited; its parent simply has not reaped it yet. Treating
# it as alive would report a signal that no process can act on.
process_gone() {
    pid=$1
    [ -e "/proc/$pid" ] || return 0
    line=$(cat "/proc/$pid/stat" 2>/dev/null) || return 0
    rest=${line##*) }
    # shellcheck disable=SC2086
    set -- $rest
    [ "${1:-}" = Z ] && return 0
    return 1
}

owned_file() {
    [ -f "$1" ] || return 1
    [ -L "$1" ] && return 1
    [ "$(uid_of "$1")" = "$ME" ] || return 1
    return 0
}

if owned_file "$JOB/state"; then
    STATE=$(sed -n 's/^status=//p' "$JOB/state" 2>/dev/null | head -n 1)
fi

owned_file "$JOB/pid" || refuse refused_invalid_job pid_file_missing_or_tampered
PID=$(sed -n '1p' "$JOB/pid" 2>/dev/null | tr -d '[:space:]')
case "$PID" in
    ''|*[!0-9]*) refuse refused_invalid_job pid_unreadable ;;
esac
# A tampered or abbreviated pid file must not become "signal the whole system".
[ "${#PID}" -le 10 ] || refuse refused_invalid_job pid_out_of_range
[ "$PID" -gt 1 ] || refuse refused_invalid_job pid_out_of_range

if process_gone "$PID"; then
    ALIVE=no
    # The runner is gone. That is an observation, not a successful signal.
    finish already_finished process_not_running
fi

# A job whose state is already terminal is not signalled, even if a process still
# holds the number: the state the user sees must not be contradicted by a signal
# that could reach an unrelated process.
if [ "$STATE" = SUCCEEDED ] || [ "$STATE" = FAILED ] || [ "$STATE" = CANCELLED ] ||
    [ "$STATE" = INTERRUPTED ] || [ "$STATE" = LOCKED ]; then
    ALIVE=yes
    finish already_finished state_terminal
fi

# From here the PID exists and must be proven to be *our* runner before any
# signal. Everything is re-read from the live process, never from the phone.
PROC_UID=$(sed -n 's/^Uid:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "/proc/$PID/status" 2>/dev/null | head -n 1)
[ -n "$PROC_UID" ] || refuse refused_unknown_identity proc_uid_unreadable
[ "$PROC_UID" = "$ME" ] || refuse refused_unknown_identity process_owner_mismatch

owned_file "$JOB/start_ticks" || refuse refused_unknown_identity start_ticks_missing
RECORDED=$(sed -n '1p' "$JOB/start_ticks" 2>/dev/null | tr -d '[:space:]')
case "$RECORDED" in
    ''|*[!0-9]*) refuse refused_unknown_identity start_ticks_unavailable ;;
esac

STAT_LINE=$(cat "/proc/$PID/stat" 2>/dev/null) || refuse refused_unknown_identity proc_stat_unreadable
# Field 22 (starttime) with the comm field skipped: field 3 starts after ") ".
REST=${STAT_LINE##*) }
# shellcheck disable=SC2086
set -- $REST
ACTUAL=${20:-}
case "$ACTUAL" in
    ''|*[!0-9]*) refuse refused_unknown_identity proc_stat_unreadable ;;
esac
[ "$ACTUAL" = "$RECORDED" ] || refuse refused_unknown_identity start_ticks_mismatch

CMDLINE=$(tr '\0' ' ' <"/proc/$PID/cmdline" 2>/dev/null) || refuse refused_unknown_identity cmdline_unreadable
case "$CMDLINE" in
    *anthracite-provision-run.sh*) : ;;
    *) refuse refused_unknown_identity cmdline_not_the_runner ;;
esac
case "$CMDLINE" in
    *"$JOB_ID"*) : ;;
    *) refuse refused_unknown_identity cmdline_other_job ;;
esac

ALIVE=yes
PRIOR_REQUEST=no
[ -e "$JOB/cancel_requested" ] && PRIOR_REQUEST=yes

if ! kill -TERM "$PID" 2>/dev/null; then
    refuse signal_failed kill_failed
fi

emit verification pid+ticks+uid+cmdline
# Record the request so a second request can be distinguished. Ours, regular,
# and never a link; a failure here does not undo the signal already delivered.
if [ ! -L "$JOB/cancel_requested" ]; then
    printf '%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown)" >>"$JOB/cancel_requested" 2>/dev/null
fi

# Observe, don't assume: a signal is not a termination. Bounded to a few seconds
# so the SSH call returns and the phone can show the honest intermediate state.
DEADLINE=$(( $(date +%s 2>/dev/null || echo 0) + 5 ))
while [ "$(date +%s 2>/dev/null || echo 0)" -lt "$DEADLINE" ]; do
    if process_gone "$PID"; then
        ALIVE=no
        if owned_file "$JOB/state"; then
            STATE=$(sed -n 's/^status=//p' "$JOB/state" 2>/dev/null | head -n 1)
        fi
        finish confirmed_stopped process_exited
    fi
    sleep 1
done

ALIVE=yes
if [ "$PRIOR_REQUEST" = yes ]; then
    finish still_running stop_requested_process_alive
fi
finish signal_sent term_delivered_process_alive
