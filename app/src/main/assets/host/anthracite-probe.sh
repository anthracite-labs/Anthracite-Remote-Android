#!/bin/sh
# Anthracite host probe v1.
#
# Read-only. Nothing here writes, installs, escalates or downloads: the probe
# answers "what is this machine and what is already usable". Every line is
# `key=value`; missing facts are omitted rather than invented.
#
# `sudo -n -l` only *lists* authorization non-interactively. It never runs a
# command as root and never prompts, so a probe cannot hang on a password.

emit() {
    printf '%s=%s\n' "$1" "$2"
}

emit probe_version 1

if [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release 2>/dev/null || true
fi
emit os_id "${ID:-}"
emit os_version_id "${VERSION_ID:-}"
emit os_id_like "${ID_LIKE:-}"
emit os_pretty "${PRETTY_NAME:-}"

emit kernel "$(uname -s 2>/dev/null)"
emit arch "$(uname -m 2>/dev/null)"
emit kernel_release "$(uname -r 2>/dev/null)"

emit uid "$(id -u 2>/dev/null)"
emit user "$(id -un 2>/dev/null)"
emit group "$(id -gn 2>/dev/null)"
emit groups "$(id -Gn 2>/dev/null)"
emit home "${HOME:-}"
emit shell "${SHELL:-}"
emit pwd "$(pwd 2>/dev/null)"

emit cpu_count "$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo 0)"
if [ -r /proc/meminfo ]; then
    emit mem_total_kb "$(sed -n 's/^MemTotal:[[:space:]]*\([0-9]*\).*/\1/p' /proc/meminfo 2>/dev/null | head -n 1)"
fi
emit disk_avail_kb "$(df -Pk "${HOME:-/}" 2>/dev/null | awk 'NR==2 {print $4}')"
emit filesystem "$(df -PT "${HOME:-/}" 2>/dev/null | awk 'NR==2 {print $2}')"

emit sudo_present "$(command -v sudo >/dev/null 2>&1 && echo yes || echo no)"
emit sudo_noninteractive "$(sudo -n -l </dev/null >/dev/null 2>&1 && echo yes || echo no)"
emit systemctl_present "$(command -v systemctl >/dev/null 2>&1 && echo yes || echo no)"
emit loginctl_present "$(command -v loginctl >/dev/null 2>&1 && echo yes || echo no)"
if command -v loginctl >/dev/null 2>&1; then
    emit linger "$(loginctl show-user "$(id -un)" -p Linger --value 2>/dev/null)"
fi

emit container_docker "$([ -e /.dockerenv ] && echo yes || echo no)"
emit container_hint "$(sed -n 's/.*\(docker\|podman\|containerd\|lxc\|kubepods\).*/\1/p' /proc/1/cgroup 2>/dev/null | head -n 1)"
emit pid1 "$(sed -n 's/^Name:[[:space:]]*//p' /proc/1/status 2>/dev/null)"

for tool in \
    git tmux curl wget tar sha256sum ssh rsync \
    docker podman kubectl helm \
    systemctl loginctl \
    apt-get dnf pacman zypper apk \
    python3 node npm go cargo make gcc vim htop rg jq unzip
do
    tool_path=$(command -v "$tool" 2>/dev/null) || tool_path=""
    if [ -n "$tool_path" ]; then
        emit "tool.$tool" "$tool_path"
    fi
done

if command -v kubectl >/dev/null 2>&1; then
    emit kube_current "$(kubectl config current-context 2>/dev/null)"
    emit kube_contexts "$(kubectl config get-contexts -o name 2>/dev/null | head -n 20 | tr '\n' ',')"
fi

emit done 1
