# Anthracite Remote

Android client for OpenCode on a user-owned Ubuntu machine. The phone stores an SSH connection, installs or attaches to OpenCode, and shows OpenCode's own WebUI through a loopback SSH tunnel. OpenCode, projects, and provider credentials stay on Linux.

## Use

1. On Ubuntu 24.04, sign in over SSH as a non-root user with `systemd --user` available.
2. First launch shows a one-time intro, then the server form: host, username, and a password or private key. Advanced options keep sensible defaults (SSH port 22, existing OpenCode port 4096). Continue saves the profile and verifies the server identity.
3. A full-screen Verify step presents the server's SHA-256 host-key fingerprint before authentication. A later change is refused until you approve the new fingerprint out of band; the changed-key screen shows the saved and the presented fingerprint side by side.
4. Home shows the connection status and one adaptive primary action: **Install & Launch OpenCode** until a managed install exists, then **Open OpenCode**, reopening the remembered journey (managed install or existing service). A quiet secondary action always offers the other journey. Connecting to an existing **HTTP Basic-authenticated** OpenCode WebUI never uploads or runs the installer.
5. A new managed install passes through Prepare install, where the WebUI password is created with live requirement checks. The password is sent on the SSH stdin channel and stored only in `~/.config/anthracite/opencode.env` (`0600`) on Linux; the phone does not save it.
6. Every remote operation reports honest phases on a Progress screen, driven only by the installer's own `step:`/`ok:`/`error:`/`limitation:` markers, with an expandable technical log. The client probes the remote loopback service through SSH before it creates a device-loopback forward; it refuses anything other than a Basic `401`, so it never intentionally exposes an unauthenticated control interface to other local apps.
7. The WebUI username is `opencode`. The phone asks for that password when the page returns HTTP Basic auth and does not save it.
8. Manage groups Service (Check status, Restart, Update), Connection (Reconnect, Edit server, Disconnect), and a Danger zone (Uninstall, Forget connection) with a session log. Uninstall removes only the managed binary and `anthracite-opencode.service`. Forget removes the phone's saved SSH connection. Neither deletes projects, provider credentials, or sessions.

The interface is a single-activity Android Views app: system-following light and dark themes, a capped content column on large screens, TalkBack-readable states, touch targets of at least 48 dp, and motion that honors the system "Remove animations" setting. The design contract lives in `docs/design/UIUX_REDESIGN_SPEC.md`.

## SSH workspace (terminal, files, Git, GitHub, deployments)

The OpenCode journey is unchanged. A second, independent SSH connection powers a
workspace for the same Linux machine, reached from **Manage → Host workspace**.

* **Hosts** — several profiles, each with its own pinned SSH key. Credentials are
  sealed with the Android Keystore. Forgetting a host removes only this phone's
  profile, key and credential; nothing on the host is touched.
* **Terminal** — a real PTY and a full VT emulator (cursor addressing, scroll
  regions, alternate screen, 256 colours, wide glyphs, mouse modes). Tabs are
  independent channels. Closing a tab ends a plain shell; for a `tmux` attachment
  it is a *detach*, and the session keeps running. **Stop session** is separate
  and verifies with `tmux has-session` before claiming success. `Ctrl-C` is a
  byte, not a promise.
* **Files** — SFTP browsing with absolute paths, no traversal outside the chosen
  directory, no symlink following on delete, a 5000-entry cap, an 8 MB transfer
  cap, and temporary-name-then-rename uploads so a final name is never partial.
* **Host Git** — status (porcelain v2), diff, stage/unstage, commit, fetch,
  fast-forward-only pull, branch switch, and push. Force push is limited to
  `--force-with-lease` and is never offered by a single tap.
* **GitHub** — a fine-grained personal access token (or GitHub's device flow when
  a public client id is configured). Repositories, **issues** (list, detail,
  comments, open/close, create), pull requests, checks, diff, comment, review and
  merge. A merge carries the head SHA you reviewed; an issue mutation names its
  repository and number before it runs.
* **Deployments** — list, logs, start/stop/restart a host-reported container,
  delete one named pod, **and create**: a container from an existing image
  (ports, volumes, environment; labelled `anthracite.managed=1`), a reviewed
  Kubernetes Deployment/StatefulSet in one confirmed context and namespace, or a
  native program as a `systemd --user` unit. Creation shows the exact commands
  first; removal only ever targets the resource this app created, and a container
  is removed only while the host still reports its label. Docker socket access is
  root-equivalent and the screen says so.
* **Set up tools** — guided provisioning from a shipped catalogue of native
  packages, with presets and per-tool selection. The phone uploads a reviewed host
  script plus a plan digest; the host re-verifies both before running anything. No
  `curl | sh`, no downloaded artifacts, no stored `sudo` password. Coding-agent
  CLIs (OpenCode, Claude Code, Codex, Gemini) are listed as **manual**
  instructions, because their vendors publish no pin-verifiable artifact for every
  distribution; nothing is executed for them.

### What the terminal does not promise

A dropped connection never stops a host process. A `tmux` session survives the
channel; whether it survives *logout* depends on the host's lingers and
`KillUserProcesses`, and nothing survives a reboot. Anthracite reports those
three events separately instead of claiming "your session is safe".

Terminal tabs do not survive Android killing the app. That is deliberate: the
host owns durable work, and reattaching to a `tmux` session is the honest
equivalent of session restore.

The full decision record is in `docs/adr/0001-ssh-workspace.md`; verified and
unverified behaviour is listed in `docs/support-matrix.md`. Guided provisioning
was exercised end to end (install, verify, failure, cancel, cleanup) in six
disposable distribution containers — see
`docs/evidence/container-matrix.md`. No real host, no live container daemon and no
Kubernetes cluster has been used yet, so those paths stay unverified and the app
says so.

A native **iOS client** lives in `ios/` (SwiftUI plus the tested `AnthraciteKit`
package): the same host-key pinning, PTY terminal with tabs, SFTP browsing, host
Git, GitHub issues and pull requests, deployments and guided provisioning. It is
built and exercised on a macOS simulator runner; see
`docs/evidence/ios.md` for exactly what that does and does not prove. Android is
still the more complete client.

## iOS build

```sh
brew install xcodegen
sh ci/ios-build.sh     # swift test, simulator build, simulator UI tests
```

## Persistence limitation

The service is a systemd user unit. It stops when the last login session ends unless lingering is enabled. The installer does not elevate. An administrator must run:

```sh
sudo loginctl enable-linger USER
```

Replace `USER` with the SSH username. Until that is done, logout or reboot stops the WebUI. The installer prints this limitation and the exact command.

## Update pin

Update does not download "latest". Without `~/.config/anthracite/opencode-update.pin` it reports `No approved update`. A pin is used only when its version is newer than the installed release:

```text
version=vX.Y.Z
x86_64_sha256=<64 hex of opencode-linux-x64-baseline.tar.gz>
aarch64_sha256=<64 hex of opencode-linux-arm64.tar.gz>
```

The initial install is pinned to OpenCode v1.19.3. A failed update restores the previous managed version. A managed install that answers HTTP 200 without authentication is rejected. HTTP 401 means the service is up and the browser is not signed in yet.

## Build

```sh
bash -n app/src/main/assets/install-opencode.sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. This checkout does not vendor a JDK or Android SDK. `compileSdk` is 37 because `androidx.core` 1.19.1 requires it. `targetSdk` stays 36, which is the Play requirement for phone and tablet updates.
