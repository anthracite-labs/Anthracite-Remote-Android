#!/bin/bash
# Managed OpenCode installer for a user-owned Ubuntu 24.04 host.
# Commands: preflight | install | status | restart | update | uninstall
# install reads the WebUI password from stdin. It is not accepted as an argument.
#
# Update does not fetch "latest". A newer release is used only when
# ~/.config/anthracite/opencode-update.pin exists and is newer than the
# installed release. Pin format:
#   version=vX.Y.Z
#   x86_64_sha256=<64 hex of opencode-linux-x64-baseline.tar.gz>
#   aarch64_sha256=<64 hex of opencode-linux-arm64.tar.gz>
# If that pin is absent or not newer, update prints "No approved update".
set -euo pipefail

PIN_VERSION=v1.19.3
PIN_X64_FILE=opencode-linux-x64-baseline.tar.gz
PIN_X64_SHA=531cff2441129c30f0edd5878af494108ad07f5feb46d7ee29a2a5b830a5765d
PIN_ARM_FILE=opencode-linux-arm64.tar.gz
PIN_ARM_SHA=7900818a6a98881edbbadab4b7525940eff3b9750040c200481f9f56cacee405
PORT=4096
MARKER=anthracite-managed-opencode
MIN_FREE_KB=409600
WEB_USER=opencode
READY_TRIES=30
DOWNLOAD_BASE=https://github.com/anomalyco/opencode/releases/download

say() { printf '%s\n' "$*"; }
step() { say "step: $1"; }
ok() { say "ok: $*"; }
die() {
  local code=$1
  shift
  say "error: $*" >&2
  exit "$code"
}

root_dir() { printf '%s/.local/share/anthracite/opencode' "$HOME"; }
env_file() { printf '%s/.config/anthracite/opencode.env' "$HOME"; }
unit_file() { printf '%s/.config/systemd/user/anthracite-opencode.service' "$HOME"; }
pin_file() { printf '%s/.config/anthracite/opencode-update.pin' "$HOME"; }
os_release_path() { printf '%s' /etc/os-release; }
cpu_arch() { uname -m; }

assert_under() {
  local path=$1 prefix=$2
  case "$path" in
    "$prefix"|"$prefix"/*) ;;
    *) die 5 "refusing to touch path outside ${prefix}: ${path}" ;;
  esac
  case "$path" in
    *..*) die 5 "refusing to touch unsafe path: ${path}" ;;
  esac
}

is_ubuntu_2404() {
  local file=$1 id="" version=""
  [ -f "$file" ] || return 1
  id=$(. "$file" && printf '%s' "${ID:-}")
  version=$(. "$file" && printf '%s' "${VERSION_ID:-}")
  [ "$id" = "ubuntu" ] && [ "$version" = "24.04" ]
}

arch_pin() {
  case "$1" in
    x86_64) printf '%s %s\n' "$PIN_X64_FILE" "$PIN_X64_SHA" ;;
    aarch64) printf '%s %s\n' "$PIN_ARM_FILE" "$PIN_ARM_SHA" ;;
    *) return 1 ;;
  esac
}

official_file() {
  case "$1" in
    x86_64) printf '%s\n' "$PIN_X64_FILE" ;;
    aarch64) printf '%s\n' "$PIN_ARM_FILE" ;;
    *) return 1 ;;
  esac
}

version_ok() {
  [[ "$1" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

sha_ok_text() {
  [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

release_url() {
  local version=$1 file=$2
  version_ok "$version" || return 1
  case "$file" in
    "$PIN_X64_FILE"|"$PIN_ARM_FILE") ;;
    *) return 1 ;;
  esac
  printf '%s/%s/%s\n' "$DOWNLOAD_BASE" "$version" "$file"
}

version_newer() {
  local new=${1#v} old=${2#v} top
  [ -n "$new" ] && [ -n "$old" ] || return 1
  [ "$new" = "$old" ] && return 1
  top=$(printf '%s\n%s\n' "$new" "$old" | sort -V | tail -n 1)
  [ "$top" = "$new" ]
}

password_accepted() {
  local p=$1 i c letter=0 digit=0
  [ "${#p}" -ge 12 ] || return 1
  for ((i = 0; i < ${#p}; i++)); do
    c=${p:i:1}
    case "$c" in
      [[:alpha:]]) letter=1 ;;
      [[:digit:]]) digit=1 ;;
      [[:cntrl:]]) return 1 ;;
    esac
  done
  [ "$letter" -eq 1 ] && [ "$digit" -eq 1 ]
}

escape_env_value() {
  local v=$1
  v=${v//\\/\\\\}
  v=${v//\"/\\\"}
  printf '"%s"' "$v"
}

read_password() {
  local password
  IFS= read -r password || {
    say "error: WebUI password missing on stdin" >&2
    return 2
  }
  password_accepted "$password" || {
    say "error: WebUI password must be at least 12 characters and include a letter and a digit" >&2
    return 2
  }
  printf '%s' "$password"
}

tar_listing_safe() {
  local entry
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    case "$entry" in
      /*|..|../*|*/..|*/../*|*..*) return 1 ;;
    esac
  done
  return 0
}

extract_safe() {
  local archive=$1 dest=$2 listing
  listing=$(tar -tzf "$archive") || return 1
  printf '%s\n' "$listing" | tar_listing_safe || return 1
  tar -xzf "$archive" -C "$dest"
}

find_unpacked_binary() {
  local dir=$1
  local found=()
  local f
  if [ -f "$dir/opencode" ] && [ ! -L "$dir/opencode" ]; then
    printf '%s\n' "$dir/opencode"
    return 0
  fi
  if [ -f "$dir/bin/opencode" ] && [ ! -L "$dir/bin/opencode" ]; then
    printf '%s\n' "$dir/bin/opencode"
    return 0
  fi
  while IFS= read -r f; do
    found+=("$f")
  done < <(find "$dir" -maxdepth 3 -type f -name opencode)
  [ "${#found[@]}" -eq 1 ] || return 1
  printf '%s\n' "${found[0]}"
}

sha_matches() {
  local file=$1 expected=$2 got
  got=$(sha256sum -- "$file" | awk '{print $1}')
  [ "$got" = "$expected" ]
}

download_to() {
  local url=$1 dest=$2
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL --retry 3 --retry-delay 2 -o "$dest" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$dest" "$url"
  else
    die 2 "curl or wget is required"
  fi
}

http_code() {
  local url=$1 code
  if command -v curl >/dev/null 2>&1; then
    code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 "$url" || true)
  else
    code=$(wget -S -O /dev/null --timeout=3 "$url" 2>&1 | awk '/^  HTTP/{c=$2} END{print c}') || true
  fi
  [ -n "${code:-}" ] || code=000
  printf '%s' "$code"
}

ensure_runtime_dir() {
  if [ -z "${XDG_RUNTIME_DIR:-}" ]; then
    export XDG_RUNTIME_DIR="/run/user/$(id -u)"
  fi
}

user_bus_ok() {
  ensure_runtime_dir
  systemctl --user show-environment >/dev/null 2>&1
}

port_in_use() {
  if command -v ss >/dev/null 2>&1; then
    ss -H -ltn "sport = :${PORT}" 2>/dev/null | grep -q .
    return
  fi
  (echo >/dev/tcp/127.0.0.1/"$PORT") >/dev/null 2>&1
}

service_active() {
  systemctl --user is-active --quiet anthracite-opencode.service
}

unit_is_ours() {
  local file
  file=$(unit_file)
  [ -f "$file" ] && grep -q "$MARKER" "$file"
}

installed_version() {
  local root link
  root=$(root_dir)
  [ -L "$root/current" ] || return 1
  link=$(readlink "$root/current")
  printf '%s' "${link##*/}"
}

write_env() {
  local password=$1 dir file
  dir=$(dirname "$(env_file)")
  mkdir -p "$dir"
  file=$(env_file)
  assert_under "$file" "$HOME/.config/anthracite"
  {
    printf 'OPENCODE_SERVER_USERNAME=%s\n' "$WEB_USER"
    printf 'OPENCODE_SERVER_PASSWORD=%s\n' "$(escape_env_value "$password")"
  } >"${file}.tmp"
  chmod 600 "${file}.tmp"
  mv -f "${file}.tmp" "$file"
}

write_unit() {
  local dir file
  dir=$(dirname "$(unit_file)")
  mkdir -p "$dir"
  file=$(unit_file)
  assert_under "$file" "$HOME/.config/systemd/user"
  cat >"${file}.tmp" <<EOF
# ${MARKER}
[Unit]
Description=Anthracite managed OpenCode WebUI
After=default.target

[Service]
Type=simple
WorkingDirectory=%h
EnvironmentFile=%h/.config/anthracite/opencode.env
ExecStart=%h/.local/share/anthracite/opencode/current/opencode web --hostname 127.0.0.1 --port ${PORT}
Restart=on-failure
RestartSec=2
StartLimitIntervalSec=60
StartLimitBurst=5
NoNewPrivileges=true

[Install]
WantedBy=default.target
EOF
  mv -f "${file}.tmp" "$file"
}

write_state() {
  local version=$1 root
  root=$(root_dir)
  assert_under "$root" "$HOME/.local/share/anthracite"
  cat >"$root/state.tmp" <<EOF
managed=1
version=${version}
unit=1
env=1
EOF
  chmod 600 "$root/state.tmp"
  mv -f "$root/state.tmp" "$root/state"
  printf 'release:%s\nunit\nenv\n' "$version" >>"$root/owned"
  sort -u "$root/owned" -o "$root/owned"
  chmod 600 "$root/owned"
}

switch_current() {
  local version=$1 root
  root=$(root_dir)
  version_ok "$version" || die 3 "refusing to activate unsafe version ${version}"
  [ -x "$root/releases/$version/opencode" ] || die 3 "missing verified binary for ${version}"
  rm -f "$root/current.next"
  ln -s "releases/${version}" "$root/current.next"
  mv -Tf "$root/current.next" "$root/current"
}

verify_binary() {
  local bin=$1 version=$2 out help
  [ -f "$bin" ] || die 3 "unpacked archive has no opencode binary"
  chmod 755 "$bin"
  out=$(timeout 20 "$bin" --version 2>&1) || die 3 "opencode --version failed: ${out}"
  case "$out" in
    *"${version#v}"*) ok "binary reports ${out}" ;;
    *) die 3 "binary version '${out}' does not match ${version}" ;;
  esac
  help=$(timeout 20 "$bin" web --help 2>&1) || die 3 "opencode web --help failed: ${help}"
  case "$help" in
    *--hostname*) ;;
    *) die 3 "opencode web does not advertise --hostname" ;;
  esac
  case "$help" in
    *--port*) ;;
    *) die 3 "opencode web does not advertise --port" ;;
  esac
  ok "opencode web supports --hostname and --port"
}

wait_ready() {
  local i code=""
  step ready
  for ((i = 1; i <= READY_TRIES; i++)); do
    code=$(http_code "http://127.0.0.1:${PORT}/")
    case "$code" in
      401)
        ok "supervised service requires authentication (HTTP 401). A browser session is not authenticated."
        return 0
        ;;
      200)
        ok "supervised service responded HTTP 200. Authentication was not enforced. A browser session is not authenticated."
        return 0
        ;;
    esac
    sleep 1
  done
  return 1
}

start_service() {
  step service
  systemctl --user daemon-reload
  systemctl --user enable --now anthracite-opencode.service
}

restart_service() {
  systemctl --user daemon-reload
  systemctl --user restart anthracite-opencode.service
}

linger_note() {
  local linger=unknown user
  user=$(id -un)
  step linger
  if command -v loginctl >/dev/null 2>&1; then
    linger=$(loginctl show-user "$user" -p Linger --value 2>/dev/null || true)
    [ -n "$linger" ] || linger=unknown
  fi
  if [ "$linger" = "yes" ]; then
    ok "lingering is enabled; the user service can survive logout"
  else
    say "limitation: without lingering, this user service stops when the last login session ends, including after logout or reboot."
    say "An administrator must run:"
    say "  sudo loginctl enable-linger ${user}"
  fi
}

preflight_checks() {
  local osfile=$1 arch=$2 uid=$3 root file free_kb
  step preflight-os
  is_ubuntu_2404 "$osfile" || die 2 "Ubuntu 24.04 is required"
  ok "Ubuntu 24.04"
  step preflight-arch
  arch_pin "$arch" >/dev/null || die 2 "unsupported CPU ${arch}; need x86_64 or aarch64"
  ok "$arch"
  step preflight-user
  [ "$uid" -ne 0 ] || die 2 "refusing to run as root; use the SSH user"
  ok "non-root uid ${uid}"
  step preflight-tools
  command -v tar >/dev/null 2>&1 || die 2 "tar is required"
  command -v sha256sum >/dev/null 2>&1 || die 2 "sha256sum is required"
  if ! command -v curl >/dev/null 2>&1 && ! command -v wget >/dev/null 2>&1; then
    die 2 "curl or wget is required"
  fi
  command -v systemctl >/dev/null 2>&1 || die 2 "systemd is required"
  ok "tar, sha256sum, downloader, and systemctl are present"
  step preflight-disk
  free_kb=$(df -Pk "$HOME" | awk 'NR==2 {print $4}')
  [ "${free_kb:-0}" -ge "$MIN_FREE_KB" ] || die 2 "need at least $((MIN_FREE_KB / 1024)) MiB free under HOME"
  ok "disk space ${free_kb} KiB free"
  step preflight-systemd
  if ! user_bus_ok; then
    die 2 "systemd --user is not available. Start a login session, or ask an administrator to run: sudo loginctl enable-linger $(id -un)"
  fi
  ok "systemd --user is available"
  step preflight-conflict
  file=$(unit_file)
  if [ -f "$file" ] && ! unit_is_ours; then
    die 5 "unmanaged unit exists at ${file}; refusing to overwrite it"
  fi
  root=$(root_dir)
  if [ -d "$root" ] && [ ! -f "$root/state" ] && [ -n "$(ls -A "$root" 2>/dev/null || true)" ]; then
    die 5 "unmanaged directory exists at ${root}; refusing to overwrite it"
  fi
  step preflight-port
  if port_in_use; then
    if service_active && unit_is_ours; then
      ok "port ${PORT} is held by the managed service"
    else
      die 5 "port ${PORT} is in use by an unmanaged process; refusing to take it"
    fi
  else
    ok "port ${PORT} is free"
  fi
}

cmd_preflight() {
  preflight_checks "$(os_release_path)" "$(cpu_arch)" "$(id -u)"
}

stage_release() {
  local version=$1 expected=$2 file url root stage partial archive extract bin
  version_ok "$version" || die 3 "refusing unpinned version ${version}"
  sha_ok_text "$expected" || die 3 "refusing hash that is not 64 hex characters"
  file=$(official_file "$(cpu_arch)") || die 2 "no official artifact for this CPU"
  url=$(release_url "$version" "$file") || die 3 "refusing unofficial download"
  root=$(root_dir)
  assert_under "$root" "$HOME/.local/share/anthracite"
  mkdir -p "$root/releases"
  stage="$root/releases/$version"
  assert_under "$stage" "$root/releases"
  # Mark the tree before download so an interrupted install can be retried
  # and is not mistaken for an unmanaged directory.
  if [ ! -f "$root/state" ]; then
    printf 'managed=1\nphase=staging\nversion=%s\n' "$version" >"$root/state"
    chmod 600 "$root/state"
  fi
  if [ ! -f "$root/owned" ] || ! grep -q "^release:${version}$" "$root/owned"; then
    printf 'release:%s\n' "$version" >>"$root/owned"
    chmod 600 "$root/owned"
  fi
  if [ -L "$root/current" ] && [ "$(readlink "$root/current")" = "releases/${version}" ]; then
    die 3 "refusing to restage the running release ${version}"
  fi
  mkdir -p "$stage"
  partial="$stage/download.partial"
  archive="$stage/archive.tar.gz"
  step download
  say "downloading ${url}"
  if ! download_to "$url" "$partial"; then
    die 3 "download failed; partial file kept at ${partial}"
  fi
  step verify-hash
  if ! sha_matches "$partial" "$expected"; then
    mv -f "$partial" "$stage/download.rejected"
    die 3 "SHA-256 mismatch for ${version}; rejected archive kept at ${stage}/download.rejected"
  fi
  mv -f "$partial" "$archive"
  printf '%s\n' "$expected" >"$stage/SHA256"
  chmod 600 "$stage/SHA256"
  step unpack
  extract="$stage/unpack"
  rm -rf "$extract"
  mkdir -p "$extract"
  extract_safe "$archive" "$extract" || die 3 "archive paths are unsafe or tar failed; archive kept at ${archive}"
  bin=$(find_unpacked_binary "$extract") || die 3 "archive does not contain exactly one opencode binary"
  mv -f "$bin" "$stage/opencode"
  chmod 755 "$stage/opencode"
  rm -rf "$extract"
  step verify-binary
  verify_binary "$stage/opencode" "$version"
}

cmd_install() {
  local password arch file expected root
  umask 077
  cmd_preflight
  if ! password=$(read_password); then
    exit 2
  fi
  arch=$(cpu_arch)
  read -r file expected < <(arch_pin "$arch")
  root=$(root_dir)
  if [ -x "$root/releases/${PIN_VERSION}/opencode" ] && [ -f "$root/releases/${PIN_VERSION}/SHA256" ] \
    && [ "$(cat "$root/releases/${PIN_VERSION}/SHA256")" = "$expected" ] \
    && [ "$(installed_version 2>/dev/null || true)" = "$PIN_VERSION" ]; then
    step download
    ok "verified ${PIN_VERSION} already staged; not downloading again"
    step verify-binary
    verify_binary "$root/releases/${PIN_VERSION}/opencode" "$PIN_VERSION"
  else
    stage_release "$PIN_VERSION" "$expected"
  fi
  step activate
  switch_current "$PIN_VERSION"
  write_env "$password"
  unset password
  write_unit
  write_state "$PIN_VERSION"
  start_service
  if ! wait_ready; then
    die 4 "managed service did not become ready on 127.0.0.1:${PORT}. Previous files were kept for inspection."
  fi
  linger_note
  ok "installed ${PIN_VERSION}. WebUI username is ${WEB_USER}. The password is only in the mode-0600 env file on this host."
}

cmd_status() {
  local root version code active=inactive
  root=$(root_dir)
  if unit_is_ours && service_active; then
    active=active
  elif unit_is_ours; then
    active=$(systemctl --user is-active anthracite-opencode.service 2>/dev/null || true)
  fi
  version=$(installed_version 2>/dev/null || true)
  say "step: status"
  say "version: ${version:-none}"
  say "service: ${active:-unknown}"
  if port_in_use; then
    code=$(http_code "http://127.0.0.1:${PORT}/")
    say "http: ${code}"
  else
    code=000
    say "http: not-listening"
  fi
  linger_note
  case "$code" in
    401)
      ok "service is up and requires authentication. A browser session is not authenticated."
      ;;
    200)
      ok "service is up. Authentication was not enforced. A browser session is not authenticated."
      ;;
    *)
      die 4 "managed OpenCode is not ready (service ${active:-unknown}, http ${code})"
      ;;
  esac
}

cmd_restart() {
  umask 077
  unit_is_ours || die 5 "refusing to restart a unit this installer does not own"
  step restart
  restart_service
  if ! wait_ready; then
    die 4 "restarted service did not become ready on 127.0.0.1:${PORT}"
  fi
  ok "restarted"
}

read_pin() {
  local file=$1 key value
  PIN_READ_VERSION=""
  PIN_READ_X64=""
  PIN_READ_ARM=""
  [ -f "$file" ] || return 1
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      ""|\#*) continue ;;
    esac
    key=${line%%=*}
    value=${line#*=}
    case "$key" in
      version) PIN_READ_VERSION=$value ;;
      x86_64_sha256) PIN_READ_X64=$value ;;
      aarch64_sha256) PIN_READ_ARM=$value ;;
      *) die 2 "unknown pin field ${key}" ;;
    esac
  done <"$file"
  version_ok "$PIN_READ_VERSION" || die 2 "pin version is not an approved vX.Y.Z value"
  [ -n "$PIN_READ_X64" ] && sha_ok_text "$PIN_READ_X64" || [ -z "$PIN_READ_X64" ] || die 2 "pin x86_64 hash is not 64 hex characters"
  [ -n "$PIN_READ_ARM" ] && sha_ok_text "$PIN_READ_ARM" || [ -z "$PIN_READ_ARM" ] || die 2 "pin aarch64 hash is not 64 hex characters"
  return 0
}

cmd_update() {
  local current pin arch expected previous
  umask 077
  pin=$(pin_file)
  current=$(installed_version 2>/dev/null || true)
  if [ ! -f "$pin" ]; then
    say "No approved update"
    exit 0
  fi
  read_pin "$pin"
  if [ -z "$current" ]; then
    die 2 "nothing managed is installed to update"
  fi
  if ! version_newer "$PIN_READ_VERSION" "$current"; then
    say "No approved update"
    exit 0
  fi
  arch=$(cpu_arch)
  case "$arch" in
    x86_64) expected=$PIN_READ_X64 ;;
    aarch64) expected=$PIN_READ_ARM ;;
    *) die 2 "unsupported CPU ${arch}" ;;
  esac
  [ -n "$expected" ] || die 2 "pin has no hash for ${arch}"
  cmd_preflight
  previous=$current
  stage_release "$PIN_READ_VERSION" "$expected"
  step activate
  switch_current "$PIN_READ_VERSION"
  write_state "$PIN_READ_VERSION"
  if ! restart_service || ! wait_ready; then
    say "error: new release failed readiness; restoring ${previous}" >&2
    switch_current "$previous"
    write_state "$previous"
    if restart_service && wait_ready; then
      die 4 "update to ${PIN_READ_VERSION} failed and ${previous} was restored. The failed release was kept for inspection."
    fi
    die 4 "update to ${PIN_READ_VERSION} failed and restoring ${previous} also failed. Both releases were kept."
  fi
  ok "updated to ${PIN_READ_VERSION}; previous release ${previous} was kept"
}

remove_path() {
  local path=$1 prefix=$2
  [ -e "$path" ] || [ -L "$path" ] || return 0
  assert_under "$path" "$prefix"
  rm -rf -- "$path"
}

cmd_uninstall() {
  local root file version
  umask 077
  step uninstall
  root=$(root_dir)
  if unit_is_ours; then
    systemctl --user disable --now anthracite-opencode.service >/dev/null 2>&1 || true
    file=$(unit_file)
    remove_path "$file" "$HOME/.config/systemd/user"
    systemctl --user daemon-reload >/dev/null 2>&1 || true
    ok "removed managed user service"
  elif [ -f "$(unit_file)" ]; then
    die 5 "unit is not managed by this installer; refusing to remove it"
  else
    ok "no managed unit to remove"
  fi
  if [ -f "$root/state" ] && grep -q '^managed=1$' "$root/state"; then
    if [ -f "$root/owned" ]; then
      while IFS= read -r line; do
        case "$line" in
          release:*)
            version=${line#release:}
            version_ok "$version" || die 5 "owned release version is unsafe: ${version}"
            remove_path "$root/releases/$version" "$root/releases"
            ;;
          unit|env) ;;
          "") ;;
          *) die 5 "unknown owned entry: ${line}" ;;
        esac
      done <"$root/owned"
    fi
    file=$(env_file)
    if [ -f "$file" ]; then
      remove_path "$file" "$HOME/.config/anthracite"
      ok "removed managed env file"
    fi
    remove_path "$root/current" "$root"
    remove_path "$root/state" "$root"
    remove_path "$root/owned" "$root"
    rmdir "$root/releases" 2>/dev/null || true
    rmdir "$root" 2>/dev/null || true
    ok "removed managed binaries"
  else
    say "limitation: no managed state file; binaries were left in place"
  fi
  say "preserved: OpenCode provider configuration, sessions, and project files were not removed"
  ok "uninstall finished"
}

main() {
  [ $# -eq 1 ] || die 64 "usage: install-opencode.sh preflight|install|status|restart|update|uninstall"
  case "$1" in
    preflight) cmd_preflight ;;
    install) cmd_install ;;
    status) cmd_status ;;
    restart) cmd_restart ;;
    update) cmd_update ;;
    uninstall) cmd_uninstall ;;
    *) die 64 "unknown command: $1" ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
