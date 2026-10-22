# Support matrix

What has been *observed*, and what is only *expected*. A row is added here only
with the CI run, container image or host that produced it, linked by run URL.

**Current state:** six distribution images passed a **read-only discovery probe**
under an isolated, unprivileged GitHub-hosted Docker matrix. [Run 39859014222](https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39859014222)
executed six jobs with zero skips/failures; each job artifact
`distro-probe-<distribution>-39859014222` contains the observed OS fields,
package-manager path and resolved image digest. Artifact bytes are not accessible
from this sandbox because GitHub redirects the download to a restricted host;
no digest values are asserted here. Images were fetched by mutable public tag and
then executed by the resolved digest **within the same job**. These are not
reproducibly pinned input images for a later run.

**Guided provisioning remains unverified on all rows below.** No package install,
job recovery, host supervisor, SSH connection, login/logout or device interaction
was exercised by this container probe. The latest review is
[PR7_REVIEW.md](engineering/PR7_REVIEW.md).

## Container-only read-only discovery (not provisioning)

| Image | Observed ID and package-manager command | Evidence |
| --- | --- | --- |
| `debian:12` | `debian`, `apt-get` | [run 39859014222](https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39859014222), artifact `distro-probe-debian-12-39859014222` |
| `ubuntu:24.04` | `ubuntu`, `apt-get` | same run, artifact `distro-probe-ubuntu-24.04-39859014222` |
| `fedora:42` | `fedora`, `dnf` | same run, artifact `distro-probe-fedora-42-39859014222` |
| `archlinux:latest` | `arch`, `pacman` | same run, artifact `distro-probe-archlinux-39859014222` |
| `opensuse/tumbleweed:latest` | `opensuse-tumbleweed`, `zypper` | same run, artifact `distro-probe-opensuse-tumbleweed-39859014222` |
| `alpine:3.21` | `alpine`, `apk` | same run, artifact `distro-probe-alpine-3.21-39859014222` |

## Guided provisioning

| Distribution | Package manager | Tier | Evidence |
| --- | --- | --- | --- |
| Debian 12 | APT | unverified | none yet; intended check: `debian:12`, plan digest + install + verify |
| Ubuntu 24.04 | APT | unverified | none yet; intended check: `ubuntu:24.04` |
| Fedora 42 | DNF | unverified | none yet; intended check: `fedora:42` |
| Arch Linux | pacman | unverified | none yet; intended check: `archlinux:latest` |
| openSUSE Tumbleweed | zypper | unverified | none yet; intended check: `opensuse/tumbleweed:latest` |
| Alpine 3.21 | apk | unverified | none yet; intended check: `alpine:3.21` |
| anything else | detected | unverified | needs an explicit acknowledgement before launch |

When the matrix runs, a container will prove that package names resolve and that
the runner's grammar, locking, verification and cancellation behave. It does
**not** prove:

* that a real host of the same distribution has the same package availability;
* supervisor behaviour (`systemd --user`, `loginctl`, linger);
* whether a logout kills detached processes (`KillUserProcesses`).

For those, a real host or VM run is required before any row may claim
`VERIFIED_HOST`. No row claims it today.

## Terminal

| Behaviour | Status | Evidence |
| --- | --- | --- |
| PTY open, raw bytes, exit status/signal | tested | `SshSessionTest` against MINA SSHD |
| Window resize → `window-change` | tested | `SshSessionTest` observes `COLS`/`LINES` change |
| VT parsing: CSI/SGR/scroll regions/alt screen | unit tested | `TerminalEmulatorTest` |
| Key encoding incl. modifiers, function keys, paste, mouse | unit tested | `TerminalKeysTest` |
| Clipboard write blocked (OSC 52), hyperlinks never opened | unit tested | `TerminalEmulatorTest` |
| IME behaviour, glyph rendering, touch scrolling on hardware | **untested** | needs a device pass |
| Terminal tabs across process death | not supported by design | `tmux` is the durable option |

## Files, Git, GitHub, deployments

| Behaviour | Status |
| --- | --- |
| SFTP list/stat/mkdir/rename/delete/upload/download, bounded and temporary-named | implemented, unit tested for path policy |
| Git status v2 / refs / log parsing and command quoting | unit tested (`GitParserTest`) |
| GitHub device flow + PAT, rate-limit and 401 handling | unit tested against a mock transport; live API untested |
| Docker/Podman listing and actions, kubectl pods/logs/delete | unit tested parsers; live daemons untested |

## Deliberately out of scope

* Local or on-device language models (see ADR 0001, decision 14).
* Docker socket forwarding, `--privileged` containers, container creation.
* Any Anthracite-hosted service, account or relay.
* Opening links or writing the clipboard from terminal output.
* Elevating privileges, prompting for a `sudo` password, or storing one.
