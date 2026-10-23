#!/usr/bin/env bash
# Host-kit regression suite for anthracite-provision-cancel.sh.
#
# The cancellation path is the only place where the phone can cause a signal on
# the host, so every case here is a negative case first: when the recorded
# process identity cannot be proven, *no* signal may be sent. A canary process
# stands in for "some other process that happens to own the recorded PID".
#
# Runs with no root and no network. Fake package managers record argv; the real
# runner and status scripts are the ones under test.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
KIT_DIR="$HERE/../../main/assets/host"
. "$HERE/kit_test_lib.sh"
CANCEL="$KIT_DIR/anthracite-provision-cancel.sh"
RUNNER="$KIT_DIR/anthracite-provision-run.sh"
SHELL_UNDER_TEST=${SHELL_UNDER_TEST:-sh}

work=$(mktemp -d)
canaries=()
trap 'rm -rf "$work"; for pid in "${canaries[@]:-}"; do kill "$pid" 2>/dev/null; done' EXIT

# --------------------------------------------------------------- fake tooling

new_home() {
    local dir="$work/home-$1"
    mkdir -p "$dir/fakebin" "$dir/.anthracite/jobs"
    write_fake_apt_get "$dir/fakebin"
    write_fake_dpkg_query "$dir/fakebin"
    chmod +x "$dir/fakebin/"*
    echo "$dir"
}

job_id="0123456789abcdef"

new_job() { # $1 home, $2 optional id
    local home=$1 id=${2:-$job_id}
    local job="$home/.anthracite/jobs/$id"
    mkdir -p "$job"
    printf '1\tinstall\tgit\n' >"$job/steps"
    local hash
    hash=$(sha256sum "$job/steps" | awk '{print $1}')
    {
        echo "job_id=$id"
        echo "manager=apt"
        echo "admin=0"
        echo "os_id=$(sed -n 's/^ID=//p' /etc/os-release | head -n1)"
        echo "catalog_version=2026.10"
        echo "plan_hash=$hash"
    } >"$job/plan"
    echo "$job"
}

write_state() { # $1 job, $2 status
    printf 'status=%s\nstep=1\nexit_code=-1\nupdated=2026-10-23T00:00:00Z\n' "$2" >"$1/state"
}

# Starts a process that is *not* the runner, for PID-reuse and identity tests.
canary() {
    # Detached from the command-substitution pipe, or $() would wait for it.
    sleep 300 >/dev/null 2>&1 &
    local pid=$!
    canaries+=("$pid")
    echo "$pid"
}

start_runner() { # $1 home, $2 job, $3 seconds
    (
        export HOME="$1"
        export PATH="$1/fakebin:$PATH"
        export FAKE_LOG="$1/apt.log"
        export FAKE_SLOW=1
        export FAKE_SLOW_SECONDS="$3"
        exec "$SHELL_UNDER_TEST" "$RUNNER" "$2"
    ) >/dev/null 2>&1 &
    echo "$!"
}

# Records a live canary as if it were the job process.
record_pid() { # $1 job, $2 pid, $3 ticks|unknown|missing
    echo "$2" >"$1/pid"
    case "$3" in
        missing) : ;;
        *) echo "$3" >"$1/start_ticks" ;;
    esac
}

actual_ticks() { # $1 pid
    sed -n 's/^[0-9]* (.*) .*//p' "/proc/$1/stat" 2>/dev/null >/dev/null
    local line rest
    line=$(cat "/proc/$1/stat" 2>/dev/null) || return 1
    rest=${line##*) }
    printf '%s\n' "$rest" | awk '{print $20}'
}

run_cancel() { # $1 home, $2 job argument, $3 extra PATH prefix (optional)
    local home=$1 job=$2 shim=${3:-}
    (
        export HOME="$home"
        if [[ -n "$shim" ]]; then export PATH="$shim:$home/fakebin:$PATH"; else export PATH="$home/fakebin:$PATH"; fi
        "$SHELL_UNDER_TEST" "$CANCEL" "$job" 2>/dev/null
    )
}

field() { printf '%s\n' "$1" | sed -n "s/^$2=//p" | head -n1; }

alive() { kill -0 "$1" 2>/dev/null && echo yes || echo no; }

# ------------------------------------------------------------------- scenarios

if [[ ! -f "$CANCEL" ]]; then
    echo "FAIL cancel script is missing at $CANCEL"
    exit 1
fi

# 1. usage / malformed identifiers ------------------------------------------
home=$(new_home usage)
out=$(run_cancel "$home" "")
check "no job argument is refused" "refused_invalid_job" "$(field "$out" cancel_result)"

job=$(new_job "$home")
mkdir -p "$home/.anthracite/jobs/NOTHEX"
out=$(run_cancel "$home" "$home/.anthracite/jobs/NOTHEX")
check "job id outside the grammar is refused" "refused_invalid_job" "$(field "$out" cancel_result)"

out=$(run_cancel "$home" "$home/../home-usage/elsewhere")
check "job outside the jobs root is refused" "refused_invalid_job" "$(field "$out" cancel_result)"

ln -s "$job" "$home/.anthracite/jobs/abcdefabcdefabcd"
out=$(run_cancel "$home" "$home/.anthracite/jobs/abcdefabcdefabcd")
check "symlinked job directory is refused" "refused_invalid_job" "$(field "$out" cancel_result)"

# 2. unknown identity must never signal -------------------------------------
home=$(new_home unknown_ticks)
job=$(new_job "$home")
pid=$(canary)
record_pid "$job" "$pid" unknown
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "unknown start ticks are refused" "refused_unknown_identity" "$(field "$out" cancel_result)"
check "unknown start ticks sent no signal" "yes" "$(alive "$pid")"

home=$(new_home missing_ticks)
job=$(new_job "$home")
pid=$(canary)
record_pid "$job" "$pid" missing
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "missing start ticks are refused" "refused_unknown_identity" "$(field "$out" cancel_result)"
check "missing start ticks sent no signal" "yes" "$(alive "$pid")"

home=$(new_home reused_pid)
job=$(new_job "$home")
pid=$(canary)
record_pid "$job" "$pid" "$(actual_ticks "$pid")"
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "a reused pid is refused" "refused_unknown_identity" "$(field "$out" cancel_result)"
check "a reused pid sent no signal" "yes" "$(alive "$pid")"

home=$(new_home stale_ticks)
job=$(new_job "$home")
pid=$(canary)
record_pid "$job" "$pid" "1"
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "mismatched start ticks are refused" "refused_unknown_identity" "$(field "$out" cancel_result)"
check "mismatched start ticks sent no signal" "yes" "$(alive "$pid")"

home=$(new_home stale_status)
job=$(new_job "$home")
pid=$(canary)
record_pid "$job" "$pid" "$(actual_ticks "$pid")"
write_state "$job" SUCCEEDED
out=$(run_cancel "$home" "$job")
check "a terminal state is not signalled" "already_finished" "$(field "$out" cancel_result)"
check "a terminal state sent no signal" "yes" "$(alive "$pid")"

# A tampered pid file must be refused even when the pid itself is running.
home=$(new_home symlink_pid)
job=$(new_job "$home")
pid=$(canary)
echo "$pid" >"$home/elsewhere.pid"
ln -s "$home/elsewhere.pid" "$job/pid"
echo "$(actual_ticks "$pid")" >"$job/start_ticks"
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "symlinked pid file is refused" "refused_invalid_job" "$(field "$out" cancel_result)"
check "symlinked pid file sent no signal" "yes" "$(alive "$pid")"

# Never signal PID 1 or 0, whatever a tampered file claims.
home=$(new_home init)
job=$(new_job "$home")
record_pid "$job" 1 "1"
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "pid 1 is refused" "refused_invalid_job" "$(field "$out" cancel_result)"

home=$(new_home zero)
job=$(new_job "$home")
record_pid "$job" 0 "1"
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "pid 0 is refused" "refused_invalid_job" "$(field "$out" cancel_result)"

# 3. ownership --------------------------------------------------------------
home=$(new_home owner)
job=$(new_job "$home")
pid=$(canary)
record_pid "$job" "$pid" "$(actual_ticks "$pid")"
write_state "$job" RUNNING
mkdir -p "$home/shim"
cat >"$home/shim/ls" <<'EOF'
#!/bin/sh
for arg in "$@"; do
    case "$arg" in
        */0123456789abcdef|*/0123456789abcdef/*) echo "drwx------ 2 4242 4242 4096 Jan 1 00:00 $arg"; exit 0 ;;
    esac
done
exec /bin/ls "$@"
EOF
chmod +x "$home/shim/ls"
out=$(run_cancel "$home" "$job" "$home/shim")
check "a foreign-owned job directory is refused" "refused_not_owner" "$(field "$out" cancel_result)"
check "a foreign-owned job sent no signal" "yes" "$(alive "$pid")"

# 4. finished and interrupted jobs ------------------------------------------
home=$(new_home finished)
job=$(new_job "$home")
record_pid "$job" 999999 "1"
write_state "$job" SUCCEEDED
out=$(run_cancel "$home" "$job")
check "finished job reports already_finished" "already_finished" "$(field "$out" cancel_result)"

home=$(new_home gone)
job=$(new_job "$home")
record_pid "$job" 999999 "1"
write_state "$job" RUNNING
out=$(run_cancel "$home" "$job")
check "vanished process reports already_finished" "already_finished" "$(field "$out" cancel_result)"
check "vanished process was never signalled" "no" "$(field "$out" process_alive)"

# 5. a genuinely running job ------------------------------------------------
home=$(new_home real)
job=$(new_job "$home")
runner=$(start_runner "$home" "$job" 6)
sleep 1
out=$(run_cancel "$home" "$job")
check "verified runner reports a result" "signal_sent" "$(field "$out" cancel_result)"
check "verified runner identity" "pid+ticks+uid+cmdline" "$(field "$out" verification)"
check "cancelled runner records a terminal state" "CANCELLED" "$(wait_status "$home" "$job" CANCELLED 20)"
out=$(run_cancel "$home" "$job")
check "a second request on a stopped job is not signalled" "already_finished" "$(field "$out" cancel_result)"

# 6. cancellation during a package transaction ------------------------------
home=$(new_home transaction)
job=$(new_job "$home")
runner=$(start_runner "$home" "$job" 12)
sleep 1
first=$(run_cancel "$home" "$job")
second=$(run_cancel "$home" "$job")
check "first request during a transaction is a signal, not a kill" "signal_sent" "$(field "$first" cancel_result)"
check "concurrent request is reported as still running" "still_running" "$(field "$second" cancel_result)"
check "the runner survives until the transaction boundary" "yes" "$(alive "$runner")"
check "the transaction finished before the job stopped" "CANCELLED" "$(wait_status "$home" "$job" CANCELLED 25)"
check "the job log shows no SIGKILL" "absent" "$(grep -q 'SIGKILL' "$job/job.log" 2>/dev/null && echo present || echo absent)"

summary
