#!/bin/sh
# Anthracite provisioning runner (host side).
#
# Usage: sh anthracite-provision-run.sh <job-directory>
#
# This script is a fixed, reviewed host-kit asset. The phone never uploads a
# generated shell program: it uploads a plan header and one line per step whose
# payload is a list of validated native package names. Every command that runs
# is constructed here from that constrained grammar.
#
# Guarantees this script makes, and does not make:
#   * No `curl | sh`, no downloaded artifacts, no invented package manager.
#   * No sudo password is ever accepted, stored, or passed in argv. When admin
#     rights are needed the runner only tries `sudo -n`; a password requirement
#     fails the step with an explicit message instead of prompting.
#   * An interrupted run stops at a command boundary; the package manager is
#     never killed mid-transaction and native lock files are never removed.
#   * State is written for the phone, but the host remains authoritative: a
#     process that disappears is reported as INTERRUPTED, never as success.
#   * The job's own lock is exclusive per user. A lock whose owner process is
#     gone is set aside with a log line; package-manager locks are never touched.

set -u
umask 077

JOB=${1:-}
if [ -z "$JOB" ] || [ ! -d "$JOB" ]; then
    echo "usage: $0 <job-directory>" >&2
    exit 64
fi

STATE="$JOB/state"
LOG="$JOB/job.log"
RESULT="$JOB/result"
PLAN="$JOB/plan"
STEPS="$JOB/steps"
PIDFILE="$JOB/pid"
TICKSFILE="$JOB/start_ticks"
LOCKROOT="${HOME:-/tmp}/.anthracite/locks"
LOCKDIR="$LOCKROOT/provision.lock"

now() { date -u +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo unknown; }

write_state() {
    # status, current step, exit code
    tmp="$STATE.tmp.$$"
    {
        printf 'status=%s\n' "$1"
        printf 'step=%s\n' "${2:-}"
        printf 'exit_code=%s\n' "${3:--1}"
        printf 'updated=%s\n' "$(now)"
    } >"$tmp" 2>/dev/null && mv "$tmp" "$STATE" 2>/dev/null
}

log() {
    printf '%s %s\n' "$(now)" "$*" >>"$LOG" 2>/dev/null
}

release_lock() {
    if [ -d "$LOCKDIR" ] && [ -f "$LOCKDIR/owner" ] && [ "$(cat "$LOCKDIR/owner" 2>/dev/null)" = "$$" ]; then
        rm -rf "$LOCKDIR" 2>/dev/null
    fi
}

fail() {
    log "FAILED: $*"
    write_state FAILED "${CURRENT_STEP:-}" 1
    printf 'reason=%s\n' "$*" >>"$RESULT" 2>/dev/null
    release_lock
    exit 1
}

on_signal() {
    log "cancel requested; stopping at the next command boundary"
    write_state CANCELLED "${CURRENT_STEP:-}" 130
    release_lock
    exit 130
}

# Field 22 (starttime) of /proc/<pid>/stat, robust against a comm containing
# spaces: everything after the closing parenthesis is field 3 onwards.
process_start_ticks() {
    pid=$1
    [ -r "/proc/$pid/stat" ] || return 1
    line=$(cat "/proc/$pid/stat" 2>/dev/null) || return 1
    rest=${line##*) }
    printf '%s\n' "$rest" | awk '{print $20}'
}

plan_value() {
    key=$1
    sed -n "s/^$key=//p" "$PLAN" 2>/dev/null | head -n 1
}

# ---------------------------------------------------------------- preconditions

[ -r "$PLAN" ] || fail "plan file is missing"
[ -r "$STEPS" ] || fail "steps file is missing"

JOB_ID=$(plan_value job_id)
MANAGER=$(plan_value manager)
ADMIN=$(plan_value admin)
EXPECTED_OS=$(plan_value os_id)
EXPECTED_HASH=$(plan_value plan_hash)
CATALOG=$(plan_value catalog_version)

case "$JOB_ID" in
    ''|*[!a-f0-9]*) fail "job id is invalid" ;;
esac
case "$ADMIN" in
    0|1) : ;;
    *) fail "admin flag is invalid" ;;
esac

# Facts must still match the approved plan.
if [ -r /etc/os-release ] && [ -n "$EXPECTED_OS" ]; then
    # shellcheck disable=SC1091
    . /etc/os-release 2>/dev/null || true
    if [ "${ID:-}" != "$EXPECTED_OS" ]; then
        fail "distribution changed since the plan was approved (${ID:-unknown} != $EXPECTED_OS)"
    fi
fi

case "$MANAGER" in
    apt) INSTALL_BIN=apt-get; VERIFY_BIN=dpkg-query ;;
    dnf) INSTALL_BIN=dnf; VERIFY_BIN=rpm ;;
    zypper) INSTALL_BIN=zypper; VERIFY_BIN=rpm ;;
    pacman) INSTALL_BIN=pacman; VERIFY_BIN=pacman ;;
    apk) INSTALL_BIN=apk; VERIFY_BIN=apk ;;
    *) fail "unsupported package manager: ${MANAGER:-none}" ;;
esac

command -v "$INSTALL_BIN" >/dev/null 2>&1 || fail "package manager $INSTALL_BIN is not installed"
command -v "$VERIFY_BIN" >/dev/null 2>&1 || fail "verification tool $VERIFY_BIN is not installed"
if [ "$ADMIN" = 1 ] && ! command -v sudo >/dev/null 2>&1; then
    fail "administrator authorization is required but sudo is unavailable"
fi

# Plan integrity: the runner recomputes the digest of the steps it is about to run.
if command -v sha256sum >/dev/null 2>&1 && [ -n "$EXPECTED_HASH" ]; then
    ACTUAL_HASH=$(sha256sum "$STEPS" 2>/dev/null | awk '{print $1}')
    if [ "$ACTUAL_HASH" != "$EXPECTED_HASH" ]; then
        fail "steps do not match the approved plan digest"
    fi
    printf 'plan_hash_verified=1\n' >>"$RESULT" 2>/dev/null
else
    printf 'plan_hash_verified=unavailable\n' >>"$RESULT" 2>/dev/null
fi

# Exclusive per-user lock, never a package-manager lock.
mkdir -p "$LOCKROOT" 2>/dev/null
if ! mkdir "$LOCKDIR" 2>/dev/null; then
    LOCK_OWNER=$(cat "$LOCKDIR/owner" 2>/dev/null || echo "")
    if [ -n "$LOCK_OWNER" ] && kill -0 "$LOCK_OWNER" 2>/dev/null; then
        write_state LOCKED "" 75
        log "another provisioning job ($LOCK_OWNER) holds $LOCKDIR"
        exit 75
    fi
    # The owning job process is gone, so this lock belongs to an interrupted job.
    mv "$LOCKDIR" "$LOCKDIR.stale.$(date +%s 2>/dev/null || echo 0)" 2>/dev/null
    if ! mkdir "$LOCKDIR" 2>/dev/null; then
        write_state LOCKED "" 75
        log "could not reclaim a stale lock at $LOCKDIR"
        exit 75
    fi
    log "reclaimed a stale job lock"
fi
printf '%s\n' "$$" >"$LOCKDIR/owner" 2>/dev/null

# A dropped connection must not stop a running job; an explicit cancel may.
trap '' HUP
trap 'on_signal' INT TERM

printf '%s\n' "$$" >"$PIDFILE" 2>/dev/null
TICKS=$(process_start_ticks "$$" 2>/dev/null || echo unknown)
printf '%s\n' "$TICKS" >"$TICKSFILE" 2>/dev/null

write_state RUNNING "" -1
printf 'job_id=%s\n' "$JOB_ID" >>"$RESULT" 2>/dev/null
printf 'catalog_version=%s\n' "$CATALOG" >>"$RESULT" 2>/dev/null
printf 'manager=%s\n' "$MANAGER" >>"$RESULT" 2>/dev/null
printf 'started=%s\n' "$(now)" >>"$RESULT" 2>/dev/null
log "job $JOB_ID starting with $MANAGER (admin=$ADMIN)"

# ---------------------------------------------------------------- execution

run_privileged() {
    if [ "$ADMIN" = 1 ]; then
        DEBIAN_FRONTEND=noninteractive sudo -n "$@"
    else
        DEBIAN_FRONTEND=noninteractive "$@"
    fi
}

install_packages() {
    case "$MANAGER" in
        apt) run_privileged "$INSTALL_BIN" install -y --no-install-recommends "$@" ;;
        dnf) run_privileged "$INSTALL_BIN" install -y "$@" ;;
        zypper) run_privileged "$INSTALL_BIN" --non-interactive install --no-recommends "$@" ;;
        pacman) run_privileged "$INSTALL_BIN" -S --noconfirm --needed "$@" ;;
        apk) run_privileged "$INSTALL_BIN" add --no-progress "$@" ;;
        *) return 1 ;;
    esac
}

verify_package() {
    case "$MANAGER" in
        apt) "$VERIFY_BIN" -s "$1" >/dev/null 2>&1 ;;
        dnf|zypper) "$VERIFY_BIN" -q "$1" >/dev/null 2>&1 ;;
        pacman) "$VERIFY_BIN" -Q "$1" >/dev/null 2>&1 ;;
        apk) "$VERIFY_BIN" info -e "$1" >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
}

CURRENT_STEP=""
STEP_INDEX=0
FAILED_STEPS=0

while IFS='	' read -r step_id kind payload; do
    [ -n "${step_id:-}" ] || continue
    case "$step_id" in
        ''|*[!0-9]*) fail "step id is invalid" ;;
    esac
    CURRENT_STEP=$step_id
    STEP_INDEX=$((STEP_INDEX + 1))
    write_state RUNNING "$step_id" -1
    log "step $step_id: ${kind:-unknown}"

    case "${kind:-}" in
        install)
            [ -n "${payload:-}" ] || fail "step $step_id has no packages"
            # Build the argument list from validated tokens only.
            set --
            for package in $payload; do
                case "$package" in
                    ''|*[!A-Za-z0-9+._-]*) fail "invalid package name in step $step_id" ;;
                esac
                set -- "$@" "$package"
            done
            [ "$#" -gt 0 ] || fail "step $step_id has no valid packages"
            install_packages "$@" </dev/null >>"$LOG" 2>&1
            rc=$?
            if [ "$rc" -eq 0 ]; then
                verify_failed=""
                for package in "$@"; do
                    if ! verify_package "$package"; then
                        verify_failed="$verify_failed $package"
                    fi
                done
                if [ -n "$verify_failed" ]; then
                    printf 'step=%s status=verify_failed packages=%s\n' "$step_id" "${verify_failed# }" >>"$RESULT" 2>/dev/null
                    log "step $step_id verification failed for:${verify_failed}"
                    FAILED_STEPS=$((FAILED_STEPS + 1))
                else
                    printf 'step=%s status=installed packages=%s\n' "$step_id" "$*" >>"$RESULT" 2>/dev/null
                    log "step $step_id verified"
                fi
            else
                printf 'step=%s status=install_failed exit=%s\n' "$step_id" "$rc" >>"$RESULT" 2>/dev/null
                log "step $step_id install failed with exit $rc"
                if grep -qi "password is required\|a terminal is required\|no tty present" "$LOG" 2>/dev/null; then
                    log "administrator authorization is not available non-interactively"
                    printf 'reason=administrator_authorization_required\n' >>"$RESULT" 2>/dev/null
                fi
                FAILED_STEPS=$((FAILED_STEPS + 1))
            fi
            ;;
        *)
            fail "unsupported step kind in step $step_id"
            ;;
    esac
done <"$STEPS"

CURRENT_STEP=""
if [ "$FAILED_STEPS" -eq 0 ]; then
    write_state SUCCEEDED "" 0
    printf 'status=SUCCEEDED\n' >>"$RESULT" 2>/dev/null
    log "job $JOB_ID succeeded after $STEP_INDEX step(s)"
    release_lock
    exit 0
fi

write_state FAILED "" 1
printf 'status=FAILED failed_steps=%s\n' "$FAILED_STEPS" >>"$RESULT" 2>/dev/null
log "job $JOB_ID finished with $FAILED_STEPS failed step(s)"
release_lock
exit 1
