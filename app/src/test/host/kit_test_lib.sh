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

# The fake package manager both suites use. It records its argv, can be made to
# fail or to block, and "installs" a marker file so the verifier has something to
# find. Nothing here touches the real system.
write_fake_apt_get() { # $1 directory to install the stub into
    cat >"$1/apt-get" <<'EOF'
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
}

write_fake_dpkg_query() { # $1 directory, $2 "strict" to fail unless installed
    if [ "${2:-}" = strict ]; then
        cat >"$1/dpkg-query" <<'EOF'
#!/bin/sh
[ "$1" = "-s" ] || exit 2
[ -f "$FAKE_INSTALLED/$2" ] || exit 1
echo "Status: install ok installed"
exit 0
EOF
    else
        cat >"$1/dpkg-query" <<'EOF'
#!/bin/sh
exit 0
EOF
    fi
}

run_kit_script() { # $1 home, $2 script name, $3 job argument
    HOME="$1" "${SHELL_UNDER_TEST:-sh}" "$KIT_DIR/$2" "$3" 2>/dev/null
}

wait_status() { # $1 home, $2 job, $3 expected, $4 seconds
    local home=$1 job=$2 want=$3 limit=$4 i=0 st=""
    while [ "$i" -lt "$limit" ]; do
        st=$(run_kit_script "$home" anthracite-provision-status.sh "$job" | sed -n 's/^status=//p' | head -n1)
        [ "$st" = "$want" ] && break
        sleep 1
        i=$((i + 1))
    done
    printf '%s\n' "$st"
}

summary() {
    printf '\n%d passed, %d failed\n' "$pass" "$fail"
    [ "$fail" -eq 0 ]
}
