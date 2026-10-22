# Anthracite Remote

Android client for OpenCode on a user-owned Ubuntu machine. The phone stores an SSH connection, installs or attaches to OpenCode, and shows OpenCode's own WebUI through a loopback SSH tunnel. OpenCode, projects, and provider credentials stay on Linux.

## Use

1. On Ubuntu 24.04, sign in over SSH as a non-root user with `systemd --user` available.
2. In the app, enter the host, SSH port, username, and a password or private key.
3. Approve the SHA-256 host-key fingerprint before authentication. A later change is refused until you approve the new fingerprint out of band.
4. Connect to an existing OpenCode WebUI, or install the pinned release. The install password is sent on the SSH stdin channel and stored only in `~/.config/anthracite/opencode.env` (`0600`) on Linux.
5. The WebUI username is `opencode`. The phone asks for that password when the page returns HTTP Basic auth and does not save it.

Reconnect, Restart, Update, Uninstall, and Forget connection are separate. Uninstall removes only the managed binary and `anthracite-opencode.service`. Forget removes the phone's saved SSH connection. Neither deletes projects, provider credentials, or sessions.

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
