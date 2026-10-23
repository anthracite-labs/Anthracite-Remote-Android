#!/usr/bin/env bash
# Regression suite for the host-kit shared lib and install_packages error path.
#
# Covers the audit verdict:
#   * process_gone() has one source of truth (anthracite-provision-lib.sh),
#     sourced by cancel and status with a byte-identical fallback.
#   * install_packages / verify_package refuse unknown managers loudly.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
KIT_DIR="$HERE/../../main/assets/host"
. "$HERE/kit_test_lib.sh"
SHELL_UNDER_TEST=${SHELL_UNDER_TEST:-sh}

LIB="$KIT_DIR/anthracite-provision-lib.sh"
CANCEL="$KIT_DIR/anthracite-provision-cancel.sh"
STATUS="$KIT_DIR/anthracite-provision-status.sh"
RUNNER="$KIT_DIR/anthracite-provision-run.sh"

# 1. lib exists and is the single definition -------------------------------
check "lib file exists" "yes" "$([[ -f "$LIB" ]] && echo yes || echo no)"
check_contains "lib defines process_gone" "process_gone()" "$(cat "$LIB")"
check "cancel sources lib" "sourced" "$([[ -n "$(grep -F 'anthracite-provision-lib.sh' "$CANCEL")" ]] && echo sourced || echo missing)"
check "status sources lib" "sourced" "$([[ -n "$(grep -F 'anthracite-provision-lib.sh' "$STATUS")" ]] && echo sourced || echo missing)"

# 2. process_gone behaviour via the lib ------------------------------------
# Missing /proc PID is gone (exit 0).
"$SHELL_UNDER_TEST" -c '. "$1"; if process_gone 999999999; then exit 0; else exit 1; fi' _ "$LIB"
check "missing pid is gone" "0" "$?"
# Current shell is alive (exit 1 from process_gone).
"$SHELL_UNDER_TEST" -c '. "$1"; if process_gone "$$"; then exit 0; else exit 1; fi' _ "$LIB"
check "live pid is alive" "1" "$?"

# 3. install_packages / verify_package loud refusal -------------------------
# Extract the functions from the runner without executing its top-level gate.
eval "$(sed -n '/^install_packages()/,/^}/p' "$RUNNER")"
eval "$(sed -n '/^verify_package()/,/^}/p' "$RUNNER")"
run_privileged() { echo "UNEXPECTED_PRIVILEGED_CALL $*"; return 0; }
MANAGER="definitely-not-a-manager"
INSTALL_BIN="apt-get"
VERIFY_BIN="dpkg-query"

err=$(MANAGER="$MANAGER" install_packages foo 2>&1); rc=$?
check "install_packages rejects unknown manager" "1" "$rc"
check_contains "install_packages names the manager" "definitely-not-a-manager" "$err"

err=$(MANAGER="$MANAGER" verify_package foo 2>&1); rc=$?
check "verify_package rejects unknown manager" "1" "$rc"
check_contains "verify_package names the manager" "definitely-not-a-manager" "$err"

summary
