#!/bin/sh
# Anthracite provisioning job status (host side).
#
# Usage: sh anthracite-provision-status.sh <job-directory>
#
# Prints `key=value` lines only. The phone never trusts its own last request:
# the state file, the recorded process identity and the log tail are read back
# from the host, and a vanished process overrides a stale "RUNNING" state.

set -u

JOB=${1:-}
if [ -z "$JOB" ] || [ ! -d "$JOB" ]; then
    echo "status=UNKNOWN"
    echo "reason=job_dir_missing"
    exit 0
fi

emit() {
    printf '%s=%s\n' "$1" "$2"
}

STATE="$JOB/state"
if [ -r "$STATE" ]; then
    # Only the keys this script understands, whatever a tampered file contains.
    for key in status step exit_code updated; do
        value=$(sed -n "s/^$key=//p" "$STATE" 2>/dev/null | head -n 1)
        [ -n "$value" ] && emit "$key" "$value"
    done
    [ -n "$(sed -n 's/^status=//p' "$STATE" 2>/dev/null)" ] || emit status UNKNOWN
else
    emit status UNKNOWN
    emit reason=no_state
fi

PID=$(cat "$JOB/pid" 2>/dev/null || echo "")
RECORDED_TICKS=$(cat "$JOB/start_ticks" 2>/dev/null || echo "")
emit pid "${PID:-none}"

PROCESS_ALIVE=no
TICKS_MATCH=unknown
if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    PROCESS_ALIVE=yes
    if [ -r "/proc/$PID/stat" ]; then
        line=$(cat "/proc/$PID/stat" 2>/dev/null || echo "")
        rest=${line##*) }
        ACTUAL_TICKS=$(printf '%s\n' "$rest" | awk '{print $20}')
        if [ -n "$RECORDED_TICKS" ] && [ "$RECORDED_TICKS" != "unknown" ]; then
            if [ "$ACTUAL_TICKS" = "$RECORDED_TICKS" ]; then TICKS_MATCH=yes; else TICKS_MATCH=no; fi
        fi
    fi
fi
emit process_alive "$PROCESS_ALIVE"
emit start_ticks_match "$TICKS_MATCH"

RESULT="$JOB/result"
if [ -r "$RESULT" ]; then
    emit result_present yes
    # Bounded: the phone already keeps its own log copy if the user asked for one.
    emit result_lines "$(wc -l <"$RESULT" 2>/dev/null | tr -d ' ')"
else
    emit result_present no
fi

LOG="$JOB/job.log"
if [ -r "$LOG" ]; then
    emit log_bytes "$(wc -c <"$LOG" 2>/dev/null | tr -d ' ')"
    emit log_tail_b64 "$(tail -c 3000 "$LOG" 2>/dev/null | base64 | tr -d '\n')"
else
    emit log_bytes 0
    emit log_tail_b64 ""
fi

exit 0
