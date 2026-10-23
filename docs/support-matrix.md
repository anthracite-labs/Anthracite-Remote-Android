# Support matrix

What has been **observed**, by which run, and what is still unknown. A row is
added only with the run, image digest or host that produced it.

**Current state (2026-10-23):** the guided-provisioning runner completed a real
install → verify → failure handling → cancellation → cleanup cycle inside six
disposable distribution containers. Every claim below is tied to
[run 39881616212](https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212)
and the resolved image digest recorded in that run.

Read-only discovery, a container-only install run, and real-host behaviour are
**separate capabilities** and are never merged into one "supported" badge.

## Capability facts per distribution

Legend: **yes** = exercised in the linked container run; **no** = not exercised;
**n/a** = cannot be observed from a container at all.

| Distribution | Image (resolved digest) | SSH + PTY | Discovery | Plan generation | Actual install | Verify | Cancel | Cleanup | Logout durability | Reboot | Docker/Podman/k8s |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Debian 12 | `debian@sha256:854dd8ab…9f61` | no | yes | yes | **yes** | yes | yes | yes | no | no | no |
| Ubuntu 24.04 | `ubuntu@sha256:68ee2200…33ac` | no | yes | yes | **yes** | yes | yes | yes | no | no | no |
| Fedora 42 | `fedora@sha256:99e203b8…7814` | no | yes | yes | **yes** | yes | yes | yes | no | no | no |
| Arch Linux | `archlinux@sha256:52dfbd5a…7fa0` | no | yes | yes | **yes** | yes | yes | yes | no | no | no |
| openSUSE Tumbleweed | `opensuse/tumbleweed@sha256:0c6f0084…dc00` | no | yes | yes | **yes** | yes | yes | yes | no | no | no |
| Alpine 3.21 | `alpine@sha256:bbfcd601…6d30` | no | yes | yes | **yes** | yes | yes | yes | no | no | no |
| any other distribution | — | — | depends on the host | yes | no | no | yes | yes | no | no | no |

Full digests are in
[`docs/evidence/container-matrix.md`](evidence/container-matrix.md) and in
`ProvisioningSupport.evidence` in the app, so the phone can show exactly which
image a claim came from, on which date, from which run.

### What the container run proves

* The probe reports the real distribution and package manager.
* A plan digest is verified before any mutation.
* A real package is installed and then verified with the native verifier.
* A package that does not exist fails the job and records `install_failed`
  instead of reporting success.
* Cancellation proves PID, start time, uid and command line before signalling,
  sends exactly one `SIGTERM`, and never `SIGKILL`s a running transaction.
* An unproven identity is refused and nothing is signalled.
* The installed package and the job directory are removed again.

### What it does **not** prove — and therefore is not claimed

* That SSH, PTY or tmux behaviour is identical on a real host. Only the Android
  JVM/PTY fixtures cover those (`SshSessionTest`, `TmuxParsingTest`).
* Supervisor behaviour (`systemd --user`, `loginctl`, linger) on a real host.
* Logout durability (`KillUserProcesses`) or reboot recovery. No row claims them.
* That a real host of the same name has the same package availability. A host is
  therefore always `UNVERIFIED`, and the acknowledgement stays in the path;
  `VERIFIED_HOST` requires evidence keyed to that machine's own identity.
* Docker/Podman/Kubernetes access. Those paths are unit tested against parsers
  and re-checked on the host before each mutation, but no live daemon or cluster
  was used.

## Terminal

| Behaviour | Status | Evidence |
| --- | --- | --- |
| PTY open, raw bytes, exit status/signal | tested | `SshSessionTest` against MINA SSHD |
| Window resize → `window-change` | tested | `SshSessionTest` observes `COLS`/`LINES` change |
| VT parsing: CSI/SGR/scroll regions/alt screen | unit tested | `TerminalEmulatorTest` |
| Key encoding incl. modifiers, function keys, paste, mouse | unit tested | `TerminalKeysTest` |
| Clipboard write blocked (OSC 52), hyperlinks never opened | unit tested | `TerminalEmulatorTest` |
| Host-owned sessions (tmux identity, detach/reattach) | unit tested parsers + shell | `TmuxParsingTest`, `provision-run-test.sh` |
| IME behaviour, glyph rendering, touch scrolling on hardware | **untested** | needs a physical device pass |
| Logout and reboot survival | **unknown, never claimed** | `HostFacts.durability()` reports UNKNOWN |
| Terminal tabs across process death | not supported by design | `tmux` is the durable option |

## Files, Git, GitHub, deployments

| Behaviour | Status |
| --- | --- |
| SFTP list/stat/mkdir/rename/delete/upload/download, bounded and temporary-named | implemented, unit tested for path policy; no live SFTP server in CI |
| Git status v2 / refs / log parsing and command quoting | unit tested (`GitParserTest`) + real `git` command suite on Actions |
| GitHub device flow + PAT, rate-limit and 401 handling | unit tested against a mock transport; live API untested |
| GitHub issues: list, detail, comments, open/close, create | mocked-transport tests only; no live API write test |
| GitHub PR: list, diff, reviews, checks, SHA-pinned merge | mocked-transport tests; live merge untested |
| Container/CPU deployment creation, logs, ownership-checked removal | plan/command unit tests; no live daemon in CI |
| Kubernetes workload apply/rollout/rollback/delete | plan/command unit tests; no live cluster in CI, RBAC re-checked at run time |

## iOS

No iOS client exists in this repository yet. It is therefore **not implemented**,
not "in progress": Android capabilities above apply to Android only.

## Deliberately out of scope

* Local or on-device language models (ADR 0001, decision 14).
* Docker socket forwarding, `--privileged` containers.
* Any Anthracite-hosted service, account or relay.
* Opening links or writing the clipboard from terminal output.
* Storing a `sudo` password or elevating privileges from the app.
* Executing vendor `curl | sh` installers. The agent CLIs are documented as
  manual steps instead.
