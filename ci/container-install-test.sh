#!/bin/sh
# Anthracite distribution matrix: real package installation, failure handling,
# cancellation and cleanup inside one disposable container.
#
# Usage: sh ci/container-install-test.sh <expected-os-id>
#
# This script proves the *guided provisioning runner* against a real native
# package manager. It is deliberately not run against a user's host: the CI job
# starts a disposable container from a public image and this script runs inside
# it as root, with no access to the repository's credentials or to any runner
# outside the container.
#
# What a green run means:
#   * the read-only probe reports this distribution and manager;
#   * a plan digest is verified and a real package is installed and verified;
#   * a package that does not exist fails the job without claiming success;
#   * cancellation proves process identity before signalling (using a fake
#     manager so the test is deterministic) and never sends SIGKILL;
#   * the installed package and the job directory are removed again.
#
# What it does not mean: anything about SSH, tmux, systemd, logout, reboot or a
# real host of the same name. Those are separate capabilities with separate
# evidence.

set -eu

EXPECTED_OS=${1:-}
KIT_DIR=${KIT_DIR:-app/src/main/assets/host}
RUNNER="$KIT_DIR/anthracite-provision-run.sh"
STATUS="$KIT_DIR/anthracite-provision-status.sh"
CANCEL="$KIT_DIR/anthracite-provision-cancel.sh"
WORK=${WORK:-/tmp/anthracite-matrix}
PKG=${PKG:-less}

fail() {
    echo "MATRIX-FAIL: $*" >&2
    for file in "${WORK:-/tmp}/install.run.log" "${JOB:-/nonexistent}/result" "${JOB:-/nonexistent}/job.log"; do
        if [ -f "$file" ]; then
            echo "--- $file (tail)"
            tail -n 15 "$file" 2>/dev/null || true
        fi
    done
    exit 1
}

step() {
    echo "== $*"
}

[ -n "$EXPECTED_OS" ] || fail "usage: $0 <expected-os-id>"
[ -r "$RUNNER" ] || fail "runner not found at $RUNNER"
[ -r "$STATUS" ] || fail "status script not found at $STATUS"
[ -r "$CANCEL" ] || fail "cancel script not found at $CANCEL"

# ---------------------------------------------------------------- observation

OS_ID=$(sed -n 's/^ID=//p' /etc/os-release | head -n 1 | tr -d '"')
OS_VERSION=$(sed -n 's/^VERSION_ID=//p' /etc/os-release | head -n 1 | tr -d '"')
ARCH=$(uname -m)
[ "$OS_ID" = "$EXPECTED_OS" ] || fail "image reported os id '$OS_ID', expected '$EXPECTED_OS'"
step "distribution: $OS_ID $OS_VERSION $ARCH"

PROBE=$(sh "$KIT_DIR/anthracite-probe.sh")
printf '%s\n' "$PROBE" | grep -q '^done=1$' || fail "probe did not finish"
PROBE_OS=$(printf '%s\n' "$PROBE" | sed -n 's/^os_id=//p' | head -n 1)
[ "$PROBE_OS" = "$OS_ID" ] || fail "probe reported a different distribution: $PROBE_OS"

MANAGER=""
for candidate in apt-get dnf zypper pacman apk; do
    if printf '%s\n' "$PROBE" | grep -qx "tool.$candidate=.*"; then
        MANAGER=$candidate
        break
    fi
done
[ -n "$MANAGER" ] || fail "no supported package manager was detected"
step "package manager: $MANAGER"

case "$MANAGER" in
    apt-get) PLAN_MANAGER=apt; VERIFY_BIN=dpkg-query ;;
    dnf) PLAN_MANAGER=dnf; VERIFY_BIN=rpm ;;
    zypper) PLAN_MANAGER=zypper; VERIFY_BIN=rpm ;;
    pacman) PLAN_MANAGER=pacman; VERIFY_BIN=pacman ;;
    apk) PLAN_MANAGER=apk; VERIFY_BIN=apk ;;
    *) fail "unmapped manager $MANAGER" ;;
esac

# A real host normally has package metadata already; a fresh container does not.
# Refreshing it here is part of preparing the fixture, not part of the runner.
step "priming package metadata"
case "$MANAGER" in
    apt-get) apt-get update -qq ;;
    dnf) dnf -q -y makecache ;;
    zypper) zypper --non-interactive --quiet --gpg-auto-import-keys refresh ;;
    pacman) pacman -Sy --noconfirm --quiet >/dev/null ;;
    apk) apk update --quiet ;;
esac

package_present() {
    case "$MANAGER" in
        apt-get) "$VERIFY_BIN" -s "$1" >/dev/null 2>&1 ;;
        dnf|zypper) "$VERIFY_BIN" -q "$1" >/dev/null 2>&1 ;;
        pacman) "$VERIFY_BIN" -Q "$1" >/dev/null 2>&1 ;;
        apk) "$VERIFY_BIN" info -e "$1" >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
}

write_plan() { # $1 job dir, $2 manager id, $3 steps content
    job=$1
    mkdir -p "$job"
    # The caller's command substitution strips the trailing newline; the runner
    # requires the file to end with one, exactly like the phone writes it.
    printf '%s\n' "$3" >"$job/steps"
    if command -v sha256sum >/dev/null 2>&1; then
        hash=$(sha256sum "$job/steps" | awk '{print $1}')
    else
        hash=$(shasum -a 256 "$job/steps" | awk '{print $1}')
    fi
    {
        printf 'job_id=%s\n' "$(basename "$job")"
        printf 'manager=%s\n' "$2"
        printf 'admin=0\n'
        printf 'os_id=%s\n' "$OS_ID"
        printf 'catalog_version=matrix\n'
        printf 'plan_hash=%s\n' "$hash"
    } >"$job/plan"
}

job_status() { # $1 job dir
    sh "$STATUS" "$1" 2>/dev/null | sed -n 's/^status=//p' | head -n 1
}

wait_for_status() { # $1 job dir, $2 expected, $3 attempts
    i=0
    while [ "$i" -lt "$3" ]; do
        current=$(job_status "$1")
        [ "$current" = "$2" ] && return 0
        i=$((i + 1))
        sleep 1
    done
    echo "last status: $current" >&2
    return 1
}

# ------------------------------------------------------- real install + verify

step "real install, verification and job isolation"
rm -rf "$WORK"
mkdir -p "$WORK"
JOB="$WORK/install-0123456789abcdef"
# The job directory name must be the job id; create it accordingly.
JOB="$WORK/jobs/0123456789abcdef"
mkdir -p "$WORK/jobs"
steps_install=$(printf '1\tinstall\t%s\n' "$PKG")
write_plan "$JOB" "$PLAN_MANAGER" "$steps_install"
set +e
HOME="$WORK/env-install" mkdir -p "$WORK/env-install"
HOME="$WORK/env-install" sh "$RUNNER" "$JOB" >"$WORK/install.run.log" 2>&1
install_rc=$?
set -e
install_status=$(job_status "$JOB")
grep -q 'status=installed' "$JOB/result" 2>/dev/null || fail "the runner did not record a verified install"
package_present "$PKG" || fail "package $PKG is not installed after a successful job"
INSTALL=ok
[ "$install_status" = "SUCCEEDED" ] || fail "install job ended as $install_status (exit $install_rc)"
step "install verified: $PKG"

# A package that cannot exist must fail, never succeed silently.
step "failure handling"
BAD_JOB="$WORK/jobs/fedcba9876543210"
write_plan "$BAD_JOB" "$PLAN_MANAGER" "$(printf '1\tinstall\t%s\n' 'anthracite-nonexistent-package-9999')"
set +e
HOME="$WORK/env-install" sh "$RUNNER" "$BAD_JOB" >"$WORK/fail.run.log" 2>&1
fail_rc=$?
set -e
fail_status=$(job_status "$BAD_JOB")
[ "$fail_status" = "FAILED" ] || fail "a nonexistent package ended as $fail_status"
[ "$fail_rc" -ne 0 ] || fail "a failed install reported exit 0"
grep -q 'status=install_failed' "$BAD_JOB/result" 2>/dev/null || fail "failure was not recorded as install_failed"
FAILURE=ok
step "failure handling verified"

# ---------------------------------------------------- cancellation and identity
# A fake manager makes the timing deterministic and keeps the real package
# database untouched. The identity checks themselves are the code under test.
step "cancellation with a verified process identity"
FAKE="$WORK/fakebin"
mkdir -p "$FAKE"
cat >"$FAKE/$MANAGER" <<'FAKE_MANAGER'
#!/bin/sh
sleep 6
exit 0
FAKE_MANAGER
cat >"$FAKE/$VERIFY_BIN" <<'FAKE_VERIFY'
#!/bin/sh
exit 0
FAKE_VERIFY
chmod +x "$FAKE/$MANAGER" "$FAKE/$VERIFY_BIN"
CANCEL_JOB="$WORK/jobs/0011223344556677"
write_plan "$CANCEL_JOB" "$PLAN_MANAGER" "$(printf '1\tinstall\t%s\n' "$PKG")"
(
    export HOME="$WORK/env-cancel"
    mkdir -p "$HOME"
    export PATH="$FAKE:$PATH"
    sh "$RUNNER" "$CANCEL_JOB" >"$WORK/cancel.run.log" 2>&1
) &
runner_pid=$!
wait_for_status "$CANCEL_JOB" RUNNING 20 || fail "the job never reached RUNNING"
cancel_first=$(HOME="$WORK/env-cancel" sh "$CANCEL" "$CANCEL_JOB" 2>&1 || true)
printf '%s\n' "$cancel_first" | grep -q '^cancel_result=signal_sent$' ||
    fail "a verified runner was not signalled: $cancel_first"
printf '%s\n' "$cancel_first" | grep -q '^verification=pid+ticks+uid+cmdline$' ||
    fail "the cancel result did not report a proven identity"
wait_for_status "$CANCEL_JOB" CANCELLED 30 || fail "the cancelled job did not reach CANCELLED"
wait "$runner_pid" 2>/dev/null || true
grep -qi 'SIGKILL' "$CANCEL_JOB/job.log" 2>/dev/null && fail "the runner used SIGKILL"
CANCEL=ok
step "cancellation verified"

# An unproven identity must never be signalled: point the pid file at a live
# process that is not the runner and confirm the refusal.
step "unproven identity is refused"
REUSE_JOB="$WORK/jobs/aaaabbbbccccdddd"
write_plan "$REUSE_JOB" "$PLAN_MANAGER" "$(printf '1\tinstall\t%s\n' "$PKG")"
sleep 300 >/dev/null 2>&1 &
canary=$!
printf '%s\n' "$canary" >"$REUSE_JOB/pid"
printf '1\n' >"$REUSE_JOB/start_ticks"
printf 'status=RUNNING\n' >"$REUSE_JOB/state"
refused=$(HOME="$WORK/env-cancel" sh "$CANCEL" "$REUSE_JOB" 2>&1 || true)
kill "$canary" 2>/dev/null || true
printf '%s\n' "$refused" | grep -q '^cancel_result=refused_unknown_identity$' ||
    fail "an unproven identity was not refused: $refused"
IDENTITY=ok
step "identity refusal verified"

# ---------------------------------------------------------------------- cleanup

step "cleanup"
set +e
case "$MANAGER" in
    apt-get) apt-get remove -y -qq "$PKG" >/dev/null 2>&1 ;;
    dnf) dnf -q -y remove "$PKG" >/dev/null 2>&1 ;;
    zypper) zypper --non-interactive --quiet remove "$PKG" >/dev/null 2>&1 ;;
    pacman) pacman -R --noconfirm "$PKG" >/dev/null 2>&1 ;;
    apk) apk del "$PKG" >/dev/null 2>&1 ;;
esac
set -e
if package_present "$PKG"; then
    fail "cleanup did not remove $PKG"
fi
rm -rf "$WORK"
CLEANUP=ok
step "cleanup verified"

printf '\nANTHRACITE-MATRIX os=%s version=%s arch=%s manager=%s probe=ok install=%s verify=%s failure=%s cancel=%s identity=%s cleanup=%s\n' \
    "$OS_ID" "${OS_VERSION:-unknown}" "$ARCH" "$MANAGER" "$INSTALL" "$INSTALL" "$FAILURE" "$CANCEL" "$IDENTITY" "$CLEANUP"
printf 'ANTHRACITE-MATRIX-END\n'
