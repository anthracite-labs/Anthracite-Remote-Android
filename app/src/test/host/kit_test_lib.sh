#!/bin/sh
# Shared assertions for the host-kit suites.
#
# Sourced by provision-run-test.sh and provision-cancel-test.sh so the two suites
# cannot drift on what "passed" means. The caller owns `set -u`, the temporary
# directory and its own trap; this file only counts and prints.

pass=0
fail=0

check() { # $1 label, $2 expected, $3 actual
    if [ "$2" = "$3" ]; then
        pass=$((pass + 1))
        printf 'ok   %s\n' "$1"
    else
        fail=$((fail + 1))
        printf 'FAIL %s\n     expected: %s\n     actual:   %s\n' "$1" "$2" "$3"
    fi
}

check_contains() { # $1 label, $2 needle, $3 haystack
    case "$3" in
        *"$2"*)
            pass=$((pass + 1))
            printf 'ok   %s\n' "$1"
            ;;
        *)
            fail=$((fail + 1))
            printf 'FAIL %s\n     missing: %s\n     in: %s\n' "$1" "$2" "$3"
            ;;
    esac
}

summary() {
    printf '\n%d passed, %d failed\n' "$pass" "$fail"
    [ "$fail" -eq 0 ]
}
