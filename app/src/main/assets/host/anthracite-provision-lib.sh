#!/bin/sh
# Anthracite host-kit shared helpers (sourced, never executed).
#
# Single source of truth for process-liveness checks shared by
# anthracite-provision-cancel.sh and anthracite-provision-status.sh.
# Previously duplicated in both files: a fix applied to one copy would not
# reach the other, letting cancel and status disagree on whether a PID is
# alive (e.g. zombie handling) and risking a signal to a reused PID.
#
# POSIX sh only. No bashisms. Callers source this file and keep an inline
# fallback so copies installed without the lib still behave identically:
#
#   _LIB_DIR=$(dirname "$0" 2>/dev/null || echo .)
#   if [ -f "$_LIB_DIR/anthracite-provision-lib.sh" ]; then
#       . "$_LIB_DIR/anthracite-provision-lib.sh"
#   else
#       process_gone() { ... }
#   fi
#   unset _LIB_DIR

# A zombie has already exited; its parent simply has not reaped it yet.
# Treating it as alive would report a signal that no process can act on.
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
