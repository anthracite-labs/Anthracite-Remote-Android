# Support matrix

What has been *observed*, and what is only *expected*. A row is added here only
with the CI run, container image or host that produced it. `ProvisioningSupport`
reads this list; an entry without evidence is not allowed.

## Guided provisioning

| Distribution | Package manager | Tier | Evidence |
| --- | --- | --- | --- |
| Debian 12 | APT | verified in a container | `debian:12` image run, plan digest + install + verify steps |
| Ubuntu 24.04 | APT | verified in a container | `ubuntu:24.04` image run |
| Fedora 42 | DNF | verified in a container | `fedora:42` image run |
| Arch Linux | pacman | verified in a container | `archlinux:latest` image run |
| openSUSE Tumbleweed | zypper | verified in a container | `opensuse/tumbleweed:latest` image run |
| Alpine 3.21 | apk | verified in a container | `alpine:3.21` image run |
| anything else | detected | unverified | needs an explicit acknowledgement before launch |

A container proves that package names resolve and that the runner's grammar,
locking, verification and cancellation behave. It does **not** prove:

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
