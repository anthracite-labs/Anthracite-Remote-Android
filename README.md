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
