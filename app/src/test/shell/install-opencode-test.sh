#!/bin/bash
# Exercises install-opencode.sh without a real OpenCode release or Ubuntu host.
set -euo pipefail

ASSET=$(cd "$(dirname "$0")/../../main/assets" && pwd)/install-opencode.sh
bash -n "$ASSET"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'ok: %s\n' "$*"; }

# shellcheck disable=SC1090
source "$ASSET"

# Operating-system checks use fixtures only. The runner's own /etc/os-release is never consulted.
os=$(mktemp)
printf 'ID=ubuntu\nVERSION_ID="24.04"\n' >"$os"
is_ubuntu_2404 "$os" || fail "ubuntu 24.04 os-release rejected"
printf 'ID=ubuntu\nVERSION_ID=24.04\n' >"$os"
is_ubuntu_2404 "$os" || fail "unquoted ubuntu 24.04 os-release rejected"
printf 'ID=ubuntu\nVERSION_ID="22.04"\n' >"$os"
is_ubuntu_2404 "$os" && fail "ubuntu 22.04 accepted"
printf 'ID=ubuntu\nVERSION_ID="24.10"\n' >"$os"
is_ubuntu_2404 "$os" && fail "ubuntu 24.10 accepted"
printf 'ID=debian\nVERSION_ID="12"\n' >"$os"
is_ubuntu_2404 "$os" && fail "debian accepted"
rm -f "$os"
pass "os-release fixtures"

arch_pin x86_64 >/dev/null || fail "x86_64 pin"
arch_pin aarch64 >/dev/null || fail "arm pin"
arch_pin i686 >/dev/null && fail "i686 accepted"
pass "arch pin"

password_accepted "CorrectHorse1" || fail "strong password rejected"
password_accepted "short1A" && fail "short password accepted"
password_accepted "correcthorsebattery" && fail "password without digit accepted"
password_accepted $'CorrectHorse1\nextra' && fail "newline password accepted"
pass "password policy"

escaped=$(escape_env_value 'abc"def\ghi12')
[ "$escaped" = '"abc\"def\\ghi12"' ] || fail "env escape got $escaped"
pass "env escape"

printf '%s\n' 'opencode' '../evil' | tar_listing_safe && fail "traversal accepted"
printf '%s\n' 'opencode' | tar_listing_safe || fail "plain binary rejected"
pass "tar path policy"

version_newer v1.19.4 v1.19.3 || fail "newer version not detected"
version_newer v1.19.3 v1.19.3 && fail "same version treated as newer"
version_newer v1.19.3 v1.19.4 && fail "older version treated as newer"
pass "version compare"

grep -q "$PIN_X64_SHA" "$ASSET" || fail "x64 hash pin missing"
grep -q "$PIN_ARM_SHA" "$ASSET" || fail "arm hash pin missing"
grep -q 'opencode-linux-x64-baseline.tar.gz' "$ASSET" || fail "x64 artifact missing"
grep -q 'opencode-linux-arm64.tar.gz' "$ASSET" || fail "arm artifact missing"
grep -q '0.0.0.0' "$ASSET" && fail "script binds a wildcard address"
grep -q 'curl |' "$ASSET" && fail "script pipes a download into a shell"
grep -Fq -- "--proto '=https'" "$ASSET" || fail "curl download does not restrict the initial protocol to HTTPS"
grep -Fq -- "--proto-redir '=https'" "$ASSET" || fail "curl download redirects can downgrade from HTTPS"
grep -Fq -- '--https-only' "$ASSET" || fail "wget download does not restrict redirects to HTTPS"
pass "pinned release constants"

url=$(release_url v1.19.3 opencode-linux-x64-baseline.tar.gz)
[ "$url" = "https://github.com/anomalyco/opencode/releases/download/v1.19.3/opencode-linux-x64-baseline.tar.gz" ] \
  || fail "unexpected url $url"
release_url latest opencode-linux-x64-baseline.tar.gz >/dev/null && fail "unpinned version accepted"
release_url v1.19.3 evil.tar.gz >/dev/null && fail "unofficial filename accepted"
pass "download url is pinned"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fake=$work/bin
mkdir -p "$fake" "$work/payload" "$work/payload-new"

cat >"$work/payload/opencode" <<'EOF'
#!/bin/sh
if [ "$1" = "--version" ]; then echo 1.19.3; exit 0; fi
if [ "$1" = "web" ] && [ "$2" = "--help" ]; then printf '%s\n' "--hostname" "--port"; exit 0; fi
exit 0
EOF
chmod 755 "$work/payload/opencode"
tar -czf "$work/good.tar.gz" -C "$work/payload" opencode

cat >"$work/payload-new/opencode" <<'EOF'
#!/bin/sh
if [ "$1" = "--version" ]; then echo 1.19.4; exit 0; fi
if [ "$1" = "web" ] && [ "$2" = "--help" ]; then printf '%s\n' "--hostname" "--port"; exit 0; fi
exit 0
EOF
chmod 755 "$work/payload-new/opencode"
tar -czf "$work/new.tar.gz" -C "$work/payload-new" opencode

mkdir -p "$work/badpayload"
printf 'not-opencode\n' >"$work/badpayload/readme.txt"
tar -czf "$work/bad.tar.gz" -C "$work/badpayload" readme.txt
python3 - <<PY
import tarfile
with tarfile.open("$work/escape.tar.gz", "w:gz") as tar:
    tar.add("$work/payload/opencode", arcname="../opencode")
PY

good_sha=$(sha256sum "$work/good.tar.gz" | awk '{print $1}')
new_sha=$(sha256sum "$work/new.tar.gz" | awk '{print $1}')
sed "s/${PIN_X64_SHA}/${good_sha}/" "$ASSET" >"$work/install.sh"
chmod 700 "$work/install.sh"
grep -q "$good_sha" "$work/install.sh" || fail "test hash was not substituted"
grep -q "$PIN_ARM_SHA" "$work/install.sh" || fail "arm pin was rewritten"

cat >"$fake/curl" <<'EOF'
#!/usr/bin/env python3
import os, sys, shutil
args = sys.argv[1:]
open(os.environ["CURL_LOG"], "a").write(" ".join(args) + "\n")
url = args[-1]
if url.startswith("http://127.0.0.1:4096"):
    count_file = os.environ.get("HTTP_COUNT_FILE")
    n = 0
    if count_file:
        if os.path.exists(count_file):
            n = int(open(count_file).read() or "0")
        n += 1
        open(count_file, "w").write(str(n))
    fail_calls = int(os.environ.get("HTTP_FAIL_CALLS", "0"))
    code = "500" if n and n <= fail_calls else os.environ.get("FAKE_HTTP_CODE", "401")
    if "%{http_code}" in args:
        sys.stdout.write(code)
    sys.exit(0)
if "releases/download/" not in url:
    sys.exit(22)
if os.environ.get("CURL_FAIL") == "1":
    dest = args[args.index("-o") + 1]
    open(dest, "wb").write(b"partial")
    sys.exit(1)
dest = args[args.index("-o") + 1]
if os.environ.get("CURL_GARBAGE") == "1":
    open(dest, "wb").write(b"this is not the pinned archive")
    sys.exit(0)
src = os.environ["FAKE_ARCHIVE"]
if "v1.19.4/" in url:
    src = os.environ["FAKE_ARCHIVE_NEW"]
shutil.copy(src, dest)
sys.exit(0)
EOF
cat >"$fake/systemctl" <<'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "$SYSTEMCTL_LOG"
[ "$1" = "--user" ] && shift
cmd=$1
shift || true
case "$cmd" in
  show-environment|daemon-reload|status) exit 0 ;;
  enable|restart)
    printf '%s\n' active > "$SYSTEMCTL_STATE"
    exit 0
    ;;
  disable)
    printf '%s\n' inactive > "$SYSTEMCTL_STATE"
    exit 0
    ;;
  is-active)
    state=$(cat "$SYSTEMCTL_STATE" 2>/dev/null || echo inactive)
    if [ "${1:-}" = "--quiet" ]; then
      [ "$state" = "active" ]
      exit $?
    fi
    printf '%s\n' "$state"
    [ "$state" = "active" ]
    ;;
  *) exit 0 ;;
esac
EOF
cat >"$fake/ss" <<'EOF'
#!/bin/bash
# A per-test listener file overrides the default. An empty file means nothing is listening.
if [ -n "${SS_LISTEN_FILE:-}" ] && [ -f "$SS_LISTEN_FILE" ]; then
  cat "$SS_LISTEN_FILE"
  exit 0
fi
if [ -f "${SYSTEMCTL_STATE:-}" ] && [ "$(cat "$SYSTEMCTL_STATE")" = "active" ]; then
  printf '%s\n' "LISTEN 0 128 127.0.0.1:4096"
fi
exit 0
EOF
cat >"$fake/loginctl" <<'EOF'
#!/bin/sh
if [ "$1" = "show-user" ]; then
  printf '%s\n' "no"
  exit 0
fi
exit 0
EOF
chmod 755 "$fake/curl" "$fake/systemctl" "$fake/ss" "$fake/loginctl"

osfile=$work/os-release
printf 'ID=ubuntu\nVERSION_ID="24.04"\n' >"$osfile"

run() {
  local home=$1
  shift
  mkdir -p "$home"
  : >"$home/curl.log"
  : >"$home/systemctl.log"
  if [ ! -f "$home/systemctl.state" ]; then
    printf '%s\n' inactive >"$home/systemctl.state"
  fi
  env \
    HOME="$home" \
    PATH="$fake:/usr/bin:/bin" \
    CURL_LOG="$home/curl.log" \
    SYSTEMCTL_LOG="$home/systemctl.log" \
    SYSTEMCTL_STATE="$home/systemctl.state" \
    SS_LISTEN_FILE="$home/ss.listen" \
    FAKE_ARCHIVE="$work/good.tar.gz" \
    FAKE_ARCHIVE_NEW="$work/new.tar.gz" \
    FAKE_HTTP_CODE="${FAKE_HTTP_CODE:-401}" \
    HTTP_FAIL_CALLS="${HTTP_FAIL_CALLS:-0}" \
    HTTP_COUNT_FILE="${HTTP_COUNT_FILE:-}" \
    CURL_FAIL="${CURL_FAIL:-0}" \
    CURL_GARBAGE="${CURL_GARBAGE:-0}" \
    READY_TRIES_OVERRIDE="${READY_TRIES_OVERRIDE:-}" \
    bash -c 'source "$1"; osfile=$2; os_release_path() { printf "%s" "$osfile"; }; if [ -n "${READY_TRIES_OVERRIDE:-}" ]; then READY_TRIES=$READY_TRIES_OVERRIDE; fi; shift 2; main "$@"' \
      bash "$work/install.sh" "$osfile" "$@" \
      >"$home/out" 2>"$home/err"
}

# Fixture preflight never reads the runner's os-release and never calls the real systemctl.
saved_os=$(cat "$osfile")
printf 'ID=debian\nVERSION_ID="12"\n' >"$osfile"
home=$work/home-os-debian
set +e
run "$home" preflight
code=$?
set -e
[ "$code" -eq 2 ] || fail "debian fixture preflight exit $code, expected 2; err=$(cat "$home/err")"
grep -q "Ubuntu 24.04 is required" "$home/err" || fail "debian fixture error did not name Ubuntu 24.04"
[ ! -s "$home/curl.log" ] || fail "debian preflight contacted the network"
printf '%s\n' "$saved_os" >"$osfile"

printf 'ID=ubuntu\nVERSION_ID="22.04"\n' >"$osfile"
home=$work/home-os-2204
set +e
run "$home" preflight
code=$?
set -e
[ "$code" -eq 2 ] || fail "ubuntu 22.04 fixture preflight exit $code"
grep -q "Ubuntu 24.04 is required" "$home/err" || fail "22.04 fixture was accepted"
printf '%s\n' "$saved_os" >"$osfile"

home=$work/home-os-2404
run "$home" preflight
grep -q "Ubuntu 24.04" "$home/out" || fail "24.04 fixture was not accepted: $(cat "$home/out" "$home/err")"
pass "os fixtures do not depend on the runner"

home=$work/home-corrupt
mkdir -p "$home"
set +e
printf '%s\n' 'CorrectHorse1' | CURL_GARBAGE=1 run "$home" install
code=$?
set -e
[ "$code" -eq 3 ] || fail "corrupt archive exit $code, expected 3"
grep -q "SHA-256 mismatch" "$home/err" || fail "corrupt archive was not rejected"
[ ! -e "$home/.local/share/anthracite/opencode/current" ] || fail "corrupt archive activated current"
[ -f "$home/.local/share/anthracite/opencode/releases/v1.19.3/download.rejected" ] || fail "rejected archive was not kept"
pass "corrupted archive rejected"

home=$work/home-interrupt
mkdir -p "$home"
set +e
printf '%s\n' 'CorrectHorse1' | CURL_FAIL=1 run "$home" install
code=$?
set -e
[ "$code" -eq 3 ] || fail "interrupted download exit $code"
[ -f "$home/.local/share/anthracite/opencode/releases/v1.19.3/download.partial" ] || fail "partial download was not kept"
[ ! -e "$home/.local/share/anthracite/opencode/current" ] || fail "interrupted install activated current"
unset CURL_FAIL
printf '%s\n' 'CorrectHorse1' | run "$home" install
grep -q "installed v1.19.3" "$home/out" || fail "retry after interruption did not install: $(cat "$home/out" "$home/err")"
pass "interrupted install can be retried"

install_home() {
  local home=$1
  mkdir -p "$home/.config/opencode" "$home/.local/share/opencode" "$home/projects/demo"
  printf '%s\n' '{"provider":"kept"}' >"$home/.config/opencode/config.json"
  printf '%s\n' session >"$home/.local/share/opencode/session"
  printf '%s\n' project >"$home/projects/demo/README"
  printf '%s\n' 'CorrectHorse1' | run "$home" install
}

home=$work/home-ok
install_home "$home"
grep -q "HTTP 401" "$home/out" || fail "install did not report auth-required readiness"
grep -q "not authenticated" "$home/out" || fail "install claimed a browser session"
grep -q "sudo loginctl enable-linger" "$home/out" || fail "missing linger instruction"
grep -q "CorrectHorse1" "$home/out" "$home/err" && fail "password leaked into output"
mode=$(stat -c %a "$home/.config/anthracite/opencode.env")
[ "$mode" = "600" ] || fail "env file mode $mode"
grep -q 'OPENCODE_SERVER_USERNAME=opencode' "$home/.config/anthracite/opencode.env" || fail "username not recorded"
grep -q '127.0.0.1' "$home/.config/systemd/user/anthracite-opencode.service" || fail "unit is not loopback"
grep -q -- '--hostname 127.0.0.1 --port 4096' "$home/.config/systemd/user/anthracite-opencode.service" || fail "unit command"
grep -q "$MARKER" "$home/.config/systemd/user/anthracite-opencode.service" || fail "unit marker missing"
[ -x "$home/.local/share/anthracite/opencode/current/opencode" ] || fail "current binary missing"
"$home/.local/share/anthracite/opencode/current/opencode" --version | grep -q 1.19.3 || fail "activated binary"
pass "install"

printf '%s\n' 'CorrectHorse1' | run "$home" install
grep -q "not downloading again" "$home/out" || fail "repeat install downloaded again: $(cat "$home/out" "$home/err")"
[ "$(readlink "$home/.local/share/anthracite/opencode/current")" = "releases/v1.19.3" ] || fail "repeat install moved current"
pass "repeat install"

run "$home" status || fail "status failed: $(cat "$home/out" "$home/err")"
grep -q "http: 401" "$home/out" || fail "status did not see 401"
grep -q "not authenticated" "$home/out" || fail "status claimed a browser session"
pass "status"

run "$home" restart
grep -q "restarted" "$home/out" || fail "restart failed: $(cat "$home/out" "$home/err")"
pass "restart"

run "$home" update
grep -q "No approved update" "$home/out" || fail "missing pin should not invent an update"
pass "no approved update"

same_pin=$home/.config/anthracite/opencode-update.pin
printf 'version=v1.19.3\nx86_64_sha256=%s\n' "$good_sha" >"$same_pin"
run "$home" update
grep -q "No approved update" "$home/out" || fail "same version pin should not update"
pass "pin that is not newer"

bad_home=$work/home-badbin
install_home "$bad_home"
# Replace the staged download with an archive whose binary fails version check by
# pointing the pin at a hash of an archive that has no opencode binary.
bad_sha=$(sha256sum "$work/bad.tar.gz" | awk '{print $1}')
printf 'version=v1.19.4\nx86_64_sha256=%s\n' "$bad_sha" >"$bad_home/.config/anthracite/opencode-update.pin"
# Serve the bad archive for the new version.
cat >"$fake/curl" <<EOF
#!/usr/bin/env python3
import os, sys, shutil
args = sys.argv[1:]
open(os.environ["CURL_LOG"], "a").write(" ".join(args) + "\\n")
url = args[-1]
if url.startswith("http://127.0.0.1:4096"):
    if "%{http_code}" in args:
        sys.stdout.write("401")
    sys.exit(0)
dest = args[args.index("-o") + 1]
src = os.environ["FAKE_ARCHIVE"]
if "v1.19.4/" in url:
    src = "$work/bad.tar.gz"
shutil.copy(src, dest)
EOF
chmod 755 "$fake/curl"
set +e
run "$bad_home" update
code=$?
set -e
[ "$code" -ne 0 ] || fail "bad archive update succeeded"
[ "$(readlink "$bad_home/.local/share/anthracite/opencode/current")" = "releases/v1.19.3" ] || fail "bad update moved current"
pass "bad update did not switch current"

# Restore the health-aware curl for rollback.
cat >"$fake/curl" <<'EOF'
#!/usr/bin/env python3
import os, sys, shutil
args = sys.argv[1:]
open(os.environ["CURL_LOG"], "a").write(" ".join(args) + "\n")
url = args[-1]
if url.startswith("http://127.0.0.1:4096"):
    count_file = os.environ.get("HTTP_COUNT_FILE")
    n = 0
    if count_file:
        if os.path.exists(count_file):
            n = int(open(count_file).read() or "0")
        n += 1
        open(count_file, "w").write(str(n))
    fail_calls = int(os.environ.get("HTTP_FAIL_CALLS", "0"))
    code = "500" if count_file and n <= fail_calls else os.environ.get("FAKE_HTTP_CODE", "401")
    if "%{http_code}" in args:
        sys.stdout.write(code)
    sys.exit(0)
if "releases/download/" not in url:
    sys.exit(22)
dest = args[args.index("-o") + 1]
src = os.environ["FAKE_ARCHIVE"]
if "v1.19.4/" in url:
    src = os.environ["FAKE_ARCHIVE_NEW"]
shutil.copy(src, dest)
EOF
chmod 755 "$fake/curl"

home=$work/home-rollback
install_home "$home"
printf 'version=v1.19.4\nx86_64_sha256=%s\naarch64_sha256=%s\n' "$new_sha" "$new_sha" \
  >"$home/.config/anthracite/opencode-update.pin"
HTTP_COUNT_FILE=$home/http.count
HTTP_FAIL_CALLS=30
: >"$HTTP_COUNT_FILE"
set +e
run "$home" update
code=$?
set -e
[ "$code" -eq 4 ] || fail "failed update exit $code, expected 4; out=$(cat "$home/out") err=$(cat "$home/err")"
[ "$(readlink "$home/.local/share/anthracite/opencode/current")" = "releases/v1.19.3" ] || fail "rollback did not restore current"
grep -q "was restored" "$home/err" || fail "rollback was not reported"
[ -x "$home/.local/share/anthracite/opencode/releases/v1.19.3/opencode" ] || fail "previous release deleted"
[ -d "$home/.local/share/anthracite/opencode/releases/v1.19.4" ] || fail "failed release was not kept for inspection"
pass "failed update restored previous version"
unset HTTP_COUNT_FILE HTTP_FAIL_CALLS

home=$work/home-keep
install_home "$home"
mkdir -p "$home/.opencode"
printf '%s\n' provider >"$home/.opencode/auth.json"
run "$home" uninstall
[ ! -e "$home/.config/systemd/user/anthracite-opencode.service" ] || fail "unit left behind"
[ ! -e "$home/.config/anthracite/opencode.env" ] || fail "env file left behind"
[ ! -e "$home/.local/share/anthracite/opencode/current" ] || fail "current left behind"
[ -f "$home/.config/opencode/config.json" ] || fail "provider config removed"
[ -f "$home/.local/share/opencode/session" ] || fail "session removed"
[ -f "$home/projects/demo/README" ] || fail "project removed"
[ -f "$home/.opencode/auth.json" ] || fail "auth file removed"
grep -q "preserved" "$home/out" || fail "uninstall did not say what it preserved"
pass "uninstall preserves user data"

# Traversal archive is rejected by the function even if its hash were trusted.
mkdir -p "$work/extract"
set +e
extract_safe "$work/escape.tar.gz" "$work/extract"
code=$?
set -e
[ "$code" -ne 0 ] || fail "path traversal archive extracted"
[ ! -e "$work/opencode" ] || fail "traversal wrote outside the destination"
pass "path traversal archive rejected"

# Unmanaged unit and an unmanaged listener must be left untouched.
home=$work/home-unmanaged-unit
mkdir -p "$home/.config/systemd/user" "$home/projects/demo"
printf '%s\n' 'not-ours' >"$home/.config/systemd/user/anthracite-opencode.service"
printf '%s\n' 'keep-project' >"$home/projects/demo/README"
set +e
run "$home" preflight
code=$?
set -e
[ "$code" -eq 5 ] || fail "unmanaged unit preflight exit $code; err=$(cat "$home/err")"
grep -q "unmanaged unit" "$home/err" || fail "unmanaged unit was not named"
grep -q "not-ours" "$home/.config/systemd/user/anthracite-opencode.service" || fail "unmanaged unit was rewritten"
[ -f "$home/projects/demo/README" ] || fail "project removed during unmanaged-unit preflight"
[ ! -s "$home/curl.log" ] || fail "unmanaged-unit preflight downloaded a release"
pass "unmanaged unit is not overwritten"

home=$work/home-unmanaged-port
mkdir -p "$home/projects/demo"
printf '%s\n' 'keep-project' >"$home/projects/demo/README"
printf '%s\n' 'LISTEN 0 128 127.0.0.1:4096' >"$home/ss.listen"
set +e
run "$home" preflight
code=$?
set -e
[ "$code" -eq 5 ] || fail "unmanaged port preflight exit $code; err=$(cat "$home/err")"
grep -q "unmanaged process" "$home/err" || fail "unmanaged listener was not refused"
[ -f "$home/projects/demo/README" ] || fail "project removed during unmanaged-port preflight"
[ ! -e "$home/.config/systemd/user/anthracite-opencode.service" ] || fail "unmanaged port created a unit"
pass "unmanaged listener is not taken over"

home=$work/home-open-auth
mkdir -p "$home/projects/demo" "$home/.config/opencode"
printf '%s\n' 'keep-project' >"$home/projects/demo/README"
printf '%s\n' '{"provider":"kept"}' >"$home/.config/opencode/config.json"
set +e
printf '%s\n' 'CorrectHorse1' | FAKE_HTTP_CODE=200 run "$home" install
code=$?
set -e
[ "$code" -eq 4 ] || fail "unauthenticated HTTP 200 install exit $code; out=$(cat "$home/out") err=$(cat "$home/err")"
grep -q "HTTP 200" "$home/err" || fail "HTTP 200 was not rejected"
grep -q "not authenticated" "$home/err" || fail "HTTP 200 rejection did not say the browser is unauthenticated"
grep -q "installed v1.19.3" "$home/out" && fail "HTTP 200 was reported as a successful install"
[ -f "$home/projects/demo/README" ] || fail "project removed when authentication was not enforced"
[ -f "$home/.config/opencode/config.json" ] || fail "provider config removed when authentication was not enforced"
pass "password-protected install rejects HTTP 200"

home=$work/home-not-ready
mkdir -p "$home/projects/demo"
printf '%s\n' 'keep-project' >"$home/projects/demo/README"
set +e
printf '%s\n' 'CorrectHorse1' | FAKE_HTTP_CODE=500 READY_TRIES_OVERRIDE=2 run "$home" install
code=$?
set -e
[ "$code" -eq 4 ] || fail "failed readiness exit $code; out=$(cat "$home/out") err=$(cat "$home/err")"
grep -q "did not become ready" "$home/err" || fail "failed readiness was not reported"
[ -f "$home/projects/demo/README" ] || fail "project removed after failed readiness"
pass "failed readiness does not destroy user data"

home=$work/home-wildcard
install_home "$home"
printf '%s\n' 'LISTEN 0 128 0.0.0.0:4096' >"$home/ss.listen"
set +e
run "$home" status
code=$?
set -e
[ "$code" -ne 0 ] || fail "wildcard bind was accepted as the managed service"
grep -q "127.0.0.1" "$home/err" || fail "wildcard bind error did not name loopback"
[ -f "$home/.config/systemd/user/anthracite-opencode.service" ] || fail "status removed the managed unit"
grep -q "$MARKER" "$home/.config/systemd/user/anthracite-opencode.service" || fail "status rewrote the managed unit"
pass "non-loopback listener is not the managed service"

root=$(cd "$(dirname "$0")/../../../.." && pwd)
cmp "$root/THIRD-PARTY-NOTICES.md" "$root/app/src/main/assets/THIRD-PARTY-NOTICES.md" \
  || fail "APK notice asset does not match THIRD-PARTY-NOTICES.md"
cmp "$root/LICENSE" "$root/app/src/main/assets/LICENSE" \
  || fail "APK license asset does not match LICENSE"
pass "license notices match the APK assets"

printf '%s\n' 'all script checks passed'
