#!/usr/bin/env bash
# Host-kit regression suite for the provisioning runner and status scripts.
#
# Runs on a disposable Linux host with no root and no network: the package
# manager is a fake that records its argv, so the suite proves the *grammar and
# lifecycle* of the runner (plan integrity, locking, cancellation, verification,
# hostile package names) without touching a real system. Real package-manager
# behaviour is exercised separately in the distribution matrix workflow.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
KIT_DIR="$HERE/../../main/assets/host"
RUNNER="$KIT_DIR/anthracite-provision-run.sh"
STATUS="$KIT_DIR/anthracite-provision-status.sh"
SHELL_UNDER_TEST=${SHELL_UNDER_TEST:-sh}

pass=0
fail=0
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

check() {
    local label=$1 expected=$2 actual=$3
    if [[ "$expected" == "$actual" ]]; then
        pass=$((pass + 1))
        printf 'ok   %s\n' "$label"
    else
        fail=$((fail + 1))
        printf 'FAIL %s\n     expected: %s\n     actual:   %s\n' "$label" "$expected" "$actual"
    fi
}

check_contains() {
    local label=$1 needle=$2 haystack=$3
    if [[ "$haystack" == *"$needle"* ]]; then
        pass=$((pass + 1))
        printf 'ok   %s\n' "$label"
    else
        fail=$((fail + 1))
        printf 'FAIL %s\n     missing: %s\n     in: %s\n' "$label" "$needle" "$haystack"
    fi
}

# --------------------------------------------------------------- fake tooling

new_home() {
    local dir="$work/home-$1"
    mkdir -p "$dir/fakebin"
    cat >"$dir/fakebin/apt-get" <<'EOF'
#!/bin/sh
printf '%s\n' "$*" >>"$FAKE_LOG"
if [ "${FAKE_FAIL:-0}" = 1 ]; then
    echo "E: fake install failure" >&2
    exit 100
fi
if [ "${FAKE_SLOW:-0}" = 1 ]; then
    sleep "${FAKE_SLOW_SECONDS:-5}"
fi
for arg in "$@"; do
    case "$arg" in
        -*) : ;;
        install|remove|add) : ;;
        /*) : ;;
        *) : >"$FAKE_INSTALLED/$arg" ;;
    esac
done
exit 0
EOF
    cat >"$dir/fakebin/dpkg-query" <<'EOF'
#!/bin/sh
[ "$1" = "-s" ] || exit 2
[ -f "$FAKE_INSTALLED/$2" ] || exit 1
echo "Status: install ok installed"
exit 0
EOF
    cat >"$dir/fakebin/sudo" <<'EOF'
#!/bin/sh
if [ "${FAKE_SUDO_OK:-0}" = 1 ]; then
    if [ "${1:-}" = "-n" ]; then
        shift
    fi
    exec "$@"
fi
echo "sudo: a terminal is required to read the password; either use the -S option" >&2
exit 1
EOF
    chmod +x "$dir/fakebin/"*
    mkdir -p "$dir/installed"
    echo "$dir"
}

make_job() {
    # $1 home, $2 manager, $3 admin, $4 steps contents
    local home=$1 manager=$2 admin=$3 steps=$4
    local job="$home/job"
    mkdir -p "$job"
    printf '%s' "$steps" >"$job/steps"
    local hash
    hash=$(sha256sum "$job/steps" | awk '{print $1}')
    {
        echo "job_id=0123456789abcdef"
        echo "manager=$manager"
        echo "admin=$admin"
        echo "os_id=$(sed -n 's/^ID=//p' /etc/os-release | head -n1)"
        echo "catalog_version=2026.10"
        echo "plan_hash=$hash"
    } >"$job/plan"
    echo "$job"
}

run_job() {
    # $1 home, $2 job dir; remaining env from caller
    local home=$1 job=$2
    (
        export HOME="$home"
        export PATH="$home/fakebin:$PATH"
        export FAKE_LOG="$home/apt.log"
        export FAKE_INSTALLED="$home/installed"
        "$SHELL_UNDER_TEST" "$RUNNER" "$job" >/dev/null 2>&1
        echo $?
    )
}

job_status() {
    local home=$1 job=$2
    (
        export HOME="$home"
        "$SHELL_UNDER_TEST" "$STATUS" "$job" 2>/dev/null | sed -n 's/^status=//p' | head -n1
    )
}

# ------------------------------------------------------------------- scenarios

home=$(new_home happy)
job=$(make_job "$home" apt 0 "1	install	git tmux
2	install	curl
")
rc=$(run_job "$home" "$job")
check "happy path exit code" "0" "$rc"
check "happy path status" "SUCCEEDED" "$(job_status "$home" "$job")"
check "packages recorded" "curl git tmux" "$(ls "$home/installed" | tr '\n' ' ' | sed 's/ $//')"
check_contains "install used no-recommends" "--no-install-recommends" "$(cat "$home/apt.log")"
check "job lock removed" "no" "$([[ -d "$home/.anthracite/locks/provision.lock" ]] && echo yes || echo no)"

home=$(new_home failure)
job=$(make_job "$home" apt 0 "1	install	git
")
export FAKE_FAIL=1
rc=$(run_job "$home" "$job")
export FAKE_FAIL=0
check "install failure status" "FAILED" "$(job_status "$home" "$job")"
check_contains "failure reason recorded" "install_failed" "$(cat "$job/result")"
check "lock released after failure" "no" "$([[ -d "$home/.anthracite/locks/provision.lock" ]] && echo yes || echo no)"

home=$(new_home verify)
job=$(make_job "$home" apt 0 "1	install	ghostpkg
")
# Simulate a package manager that claims success without installing anything.
sed -i 's|: >"$FAKE_INSTALLED/$arg"|: >/dev/null|' "$home/fakebin/apt-get"
rc=$(run_job "$home" "$job")
check "verification failure status" "FAILED" "$(job_status "$home" "$job")"
check_contains "verification failure recorded" "verify_failed" "$(cat "$job/result")"

home=$(new_home digest)
job=$(make_job "$home" apt 0 "1	install	git
")
printf '2\tinstall\ttmux\n' >>"$job/steps"
rc=$(run_job "$home" "$job")
check "tampered plan rejected" "FAILED" "$(job_status "$home" "$job")"
check_contains "digest mismatch reason" "digest" "$(cat "$job/result")"

home=$(new_home os)
job=$(make_job "$home" apt 0 "1	install	git
")
sed -i 's/^os_id=.*/os_id=not-this-distribution/' "$job/plan"
rc=$(run_job "$home" "$job")
check "changed distribution rejected" "FAILED" "$(job_status "$home" "$job")"

home=$(new_home admin)
job=$(make_job "$home" apt 1 "1	install	git
")
rc=$(run_job "$home" "$job")
check "admin without passwordless sudo fails" "FAILED" "$(job_status "$home" "$job")"
check_contains "authorization reason" "administrator_authorization_required" "$(cat "$job/result")"

home=$(new_home admin_ok)
job=$(make_job "$home" apt 1 "1	install	git
")
export FAKE_SUDO_OK=1
rc=$(run_job "$home" "$job")
export FAKE_SUDO_OK=0
check "passwordless sudo path" "SUCCEEDED" "$(job_status "$home" "$job")"
check "sudo -n used" "install -y --no-install-recommends git" "$(cat "$home/apt.log" | tr -d '\n')"

home=$(new_home lock)
job=$(make_job "$home" apt 0 "1	install	git
")
mkdir -p "$home/.anthracite/locks"
mkdir -p "$home/.anthracite/locks/provision.lock"
sleep 30 &
live_pid=$!
echo "$live_pid" >"$home/.anthracite/locks/provision.lock/owner"
rc=$(run_job "$home" "$job")
check "live lock refuses second job" "LOCKED" "$(job_status "$home" "$job")"
check "live lock exit code" "75" "$rc"
kill "$live_pid" 2>/dev/null
wait "$live_pid" 2>/dev/null

home=$(new_home stale)
job=$(make_job "$home" apt 0 "1	install	git
")
mkdir -p "$home/.anthracite/locks/provision.lock"
echo "999999" >"$home/.anthracite/locks/provision.lock/owner"
rc=$(run_job "$home" "$job")
check "stale lock reclaimed" "SUCCEEDED" "$(job_status "$home" "$job")"

home=$(new_home injection)
job=$(make_job "$home" apt 0 "1	install	git;id
")
rc=$(run_job "$home" "$job")
check "semicolon package rejected" "FAILED" "$(job_status "$home" "$job")"
home=$(new_home injection2)
job=$(make_job "$home" apt 0 "1	install	\$(touch /tmp/anthracite-pwned)
")
rm -f /tmp/anthracite-pwned
rc=$(run_job "$home" "$job")
check "substitution package rejected" "FAILED" "$(job_status "$home" "$job")"
check "no command substitution ran" "absent" "$([[ -e /tmp/anthracite-pwned ]] && echo present || echo absent)"
home=$(new_home injection3)
job=$(make_job "$home" apt 0 "1	install	../etc/passwd
")
rc=$(run_job "$home" "$job")
check "path package rejected" "FAILED" "$(job_status "$home" "$job")"

home=$(new_home unknown_manager)
job=$(make_job "$home" yum 0 "1	install	git
")
rc=$(run_job "$home" "$job")
check "unknown manager rejected" "FAILED" "$(job_status "$home" "$job")"

home=$(new_home cancel)
job=$(make_job "$home" apt 0 "1	install	git
")
(
    export HOME="$home"
    export PATH="$home/fakebin:$PATH"
    export FAKE_LOG="$home/apt.log"
    export FAKE_INSTALLED="$home/installed"
    export FAKE_SLOW=1
    export FAKE_SLOW_SECONDS=10
    "$SHELL_UNDER_TEST" "$RUNNER" "$job" >/dev/null 2>&1 &
    runner_pid=$!
    sleep 1
    status_while_running=$("$SHELL_UNDER_TEST" "$STATUS" "$job" 2>/dev/null | sed -n 's/^status=//p' | head -n1)
    alive_while_running=$("$SHELL_UNDER_TEST" "$STATUS" "$job" 2>/dev/null | sed -n 's/^process_alive=//p' | head -n1)
    kill -TERM "$runner_pid"
    wait "$runner_pid"
    rc=$?
    echo "$status_while_running|$alive_while_running|$rc" >"$home/cancel-observation"
)
observation=$(cat "$home/cancel-observation")
check "job reports RUNNING while installing" "RUNNING|yes" "$(echo "$observation" | cut -d'|' -f1,2)"
final=$(job_status "$home" "$job")
check "cancel writes a terminal state" "CANCELLED" "$final"
check "cancel releases the lock" "no" "$([[ -d "$home/.anthracite/locks/provision.lock" ]] && echo yes || echo no)"

home=$(new_home missing)
check "missing job dir is unknown" "UNKNOWN" "$(job_status "$home" "$home/none")"

home=$(new_home process_identity)
job=$(make_job "$home" apt 0 "1	install	git
")
(
    export HOME="$home"
    export PATH="$home/fakebin:$PATH"
    export FAKE_LOG="$home/apt.log"
    export FAKE_INSTALLED="$home/installed"
    export FAKE_SLOW=1
    export FAKE_SLOW_SECONDS=3
    "$SHELL_UNDER_TEST" "$RUNNER" "$job" >/dev/null 2>&1 &
    runner_pid=$!
    sleep 1
    "$SHELL_UNDER_TEST" "$STATUS" "$job" >"$home/status.txt" 2>/dev/null
    wait "$runner_pid"
    grep -q 'start_ticks_match=yes' "$home/status.txt" || echo 'start_ticks_match=no' >"$home/status.txt.bad"
)
check "process start ticks match" "absent" "$([[ -e "$home/status.txt.bad" ]] && echo present || echo absent)"
check_contains "status exposes a bounded log" "log_bytes=" "$(cat "$home/status.txt")"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
