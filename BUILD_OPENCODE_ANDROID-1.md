# Anthracite Android — OpenCode CLI Build Specification

Build a working Android application that installs or connects to **OpenCode CLI on a user-owned Ubuntu Linux machine** and displays **OpenCode's original WebUI**. Execute this specification; deliver source code and a buildable APK, not a new proposal.

## Product

```text
Android APK                         User-owned Ubuntu 24.04 (x86_64 / arm64)
Kotlin setup + WebView              OpenCode CLI + original WebUI
SSH authentication and SFTP  ─SSH─> systemd user service, 127.0.0.1:4096
127.0.0.1:<free port>        <───── user's projects, tools and provider credentials
```

The same workflow supports a VPS and a local Linux computer. The user supplies a reachable SSH server and credentials. Android provisions OpenCode or connects to an existing instance, creates a **loopback-only SSH port forward**, then loads the WebUI. OpenCode, its configuration, repositories, and model credentials stay on Linux. There is no Anthracite backend.

**Scope:** One Kotlin Android app; one Linux installer/manager shell script bundled as an APK asset; one OpenCode service. Do not build an Android-local OpenCode engine, native toolchain, Termux integration, custom chat UI, other agent CLIs, Docker environment, server proxy, account system or unnecessary abstraction.

## Source code to reuse

Use working upstream implementations rather than rebuilding standard capabilities. Inspect their current source and dependencies before copying.

| Repository | Take exactly this | Usage and license |
|---|---|---|
| [anthracite-labs/OpenCode-Android](https://github.com/anthracite-labs/OpenCode-Android) | [`MainActivity.kt`](https://github.com/anthracite-labs/OpenCode-Android/blob/main/app/src/main/java/dev/example/opencode/MainActivity.kt): WebView, file chooser, Android insets, renderer recovery; [`PickerPolicy.kt`](https://github.com/anthracite-labs/OpenCode-Android/blob/main/app/src/main/java/dev/example/opencode/PickerPolicy.kt) and [tests](https://github.com/anthracite-labs/OpenCode-Android/blob/main/app/src/test/java/dev/example/opencode/PickerPolicyTest.kt). Retain only code independent of its local runtime. | MIT; copy/adapt selected files. Remove all `ServerEngine`, `ServerService`, `jniLibs`, native builds, local auth-token URLs, and obsolete permissions. |
| [mwiede/jsch](https://github.com/mwiede/jsch) | Use the **released library dependency** for SSH login, command execution, SFTP and local TCP forwarding. Adapt examples; do not vendor the library. | JSch BSD-style license; retain its notices. Use a pinned tested release. Its newer SSH algorithms may need a cryptography provider on Android; test before adding one. |
| [connectbot/connectbot](https://github.com/connectbot/connectbot) and [ConnectBot forwarding guide](https://connectbot.org/guides/port-forwarding/) | Reference Android connection/host-key/port-forward lifecycle and failure handling; reuse specific code only if simpler than using JSch. | Apache-2.0; preserve copyright/NOTICE obligations if copying. **Do not fork the full terminal app.** |
| [connectbot/cbssh](https://github.com/connectbot/cbssh) | Alternative maintained SSH library with exec, SFTP and forwarding **only if JSch fails required Android/device tests**. | Apache-2.0; do not install both libraries. |
| [coder/code-server](https://github.com/coder/code-server) | Adapt the small **standalone download → install under user home → versioned release → symlink** pattern in [`install.sh`](https://github.com/coder/code-server/blob/main/install.sh); consult its [installation guide](https://github.com/coder/code-server/blob/main/docs/install.md). | MIT; do not copy its unrelated package-manager support or run its installer. |
| [anomalyco/opencode](https://github.com/anomalyco/opencode) | Download only pinned **official Linux CLI** release archives; follow [CLI](https://docs.opencode.ai/docs/cli/) and [server/auth documentation](https://docs.opencode.ai/docs/server/). | Keep the upstream program and licenses intact; it is installed on the user's Linux host, not included in the APK. |

## Pinned initial OpenCode release

Use **OpenCode v1.19.3** until explicitly upgraded. GitHub release metadata published on **16 October 2026** lists these CLI artifacts and SHA-256 digests (not the similarly named desktop packages):

| Linux CPU | Artifact in `https://github.com/anomalyco/opencode/releases/download/v1.19.3/` | SHA-256 |
|---|---|---|
| `x86_64` | `opencode-linux-x64-baseline.tar.gz` | `531cff2441129c30f0edd5878af494108ad07f5feb46d7ee29a2a5b830a5765d` |
| `aarch64` | `opencode-linux-arm64.tar.gz` | `7900818a6a98881edbbadab4b7525940eff3b9750040c200481f9f56cacee405` |

Verify downloaded bytes against these pinned hashes before extraction. Check the resulting executable's `--version`, supported `web` flags, binary layout, and behavior **by running it**; release metadata alone does not establish runtime compatibility. The documented v1 command is `opencode web --hostname 127.0.0.1 --port 4096`; `OPENCODE_SERVER_PASSWORD` and optional `OPENCODE_SERVER_USERNAME` provide HTTP Basic authentication. Do not substitute `opencode serve` or v2 `pair/service` behavior without testing the pinned release.

## Implementation

### 1. Android application

- Create `Anthracite-Remote-Android/` as a fresh, single-module Android project. Treat `anthracite-labs/OpenCode-Android` as read-only reference; do not overwrite its code. Kotlin + Android Views/XML + system WebView. Reuse the source files above selectively. Set `minSdk 28` if dependency/device validation passes; target an SDK accepted for publication at release time. Keep Gradle/dependencies pinned.
- Build three screens: **Server setup**, **Deploy / Manage**, **OpenCode WebUI**. Setup requests host, SSH port, Linux username and SSH key or password. Save nonsecrets in preferences; protect saved secrets with Android Keystore-backed encryption. Do not sync or back up raw secrets.
- Implement SSH with one library: verify the actual server host key **before authenticating**; display its SHA-256 fingerprint for first-use approval; remember the approved key; refuse later unexpected changes. Support exec, SFTP, SSH disconnects, bounded timeouts and a persistent local port forward. Never use `StrictHostKeyChecking=no` or unconditional trust.
- **Connect existing:** ask for remote OpenCode port (default `4096`), establish an SSH tunnel to remote `127.0.0.1:<port>`, check HTTP readiness and render the original WebUI. Do not change or claim ownership of the existing server.
- **Install:** run remote preflight, upload the bundled script over SFTP to a safely created user-owned temporary directory, execute its fixed commands through SSH, display real step names and sanitized failures; once healthy, connect through the same tunnel.
- Bind the Android forwarder to `127.0.0.1` on an available port. Keep the SSH session alive while WebView uses it; close on explicit disconnect and reconnect after network loss. Never bind the OpenCode server or Android forwarder to `0.0.0.0`.
- Load `http://127.0.0.1:<allocated-port>` in WebView. Permit cleartext HTTP **only for the Android loopback origin** using an appropriate network-security configuration; never globally allow insecure network traffic. Use JavaScript and DOM storage only as needed by OpenCode. Handle HTTP Basic auth for the trusted forwarded origin, browser cookies, WebSockets/streaming, Back, Refresh, renderer crashes, Android keyboard/insets, document picker and file downloads. Refuse unsafe schemes/navigation, file access and unrestricted JS bridges; open external links in the system browser.
- Provide **Reconnect**, **Restart**, **Update**, **Uninstall managed OpenCode**, and **Forget connection**. Uninstall and Forget are separate; never remove workspace or provider credentials. Show clear states for SSH failure, host-key change, WebUI authentication, service unavailable and provisioning failure.

### 2. One bundled Linux script

Create `app/src/main/assets/install-opencode.sh`. It executes on Ubuntu, **not Android**. Implement these script operations (they are not OpenCode CLI flags):

```sh
install-opencode.sh preflight
install-opencode.sh install
install-opencode.sh status
install-opencode.sh restart
install-opencode.sh update
install-opencode.sh uninstall
```

- Preflight: Ubuntu 24.04, `x86_64`/`aarch64`, non-root SSH user, `systemd --user` availability, disk space, `curl` or `wget`, `tar`, `sha256sum`, and remote port collisions. Reject unmanaged-service conflicts rather than overwriting them.
- Install: create `~/.local/share/anthracite/opencode/releases/v1.19.3/`; download only the matching pinned artifact into a temporary file; verify SHA-256; safely unpack it; verify the real binary; atomically update `~/.local/share/anthracite/opencode/current` to the managed version.
- Create a **user-owned systemd service** at `~/.config/systemd/user/anthracite-opencode.service`. Launch the checked `opencode web` executable bound to `127.0.0.1:4096`, run under the SSH user's identity, and supervise restarts. Ask the user to choose a strong WebUI password during deployment; send it to the script via the encrypted SSH channel **on stdin**, not in command arguments or logs. Store it only on Linux in a mode-`0600` user-owned service environment file (for example, `~/.config/anthracite/opencode.env`), never in source control or Android persistent storage. Prompt for the WebUI password when WebView encounters HTTP Basic auth; never put credentials in a URL.
- Confirm service readiness against the **supervised instance**; differentiate expected HTTP `401` (auth required) from service failure. Do not claim that a browser session is authenticated merely because the TCP port responds.
- Keep operations safe on repeat. For update, stage and hash-check a separately pinned new release before switching; retain the previous version and restore it if the new service fails. **Do not implement an unpinned “latest” download.** If no newer tested version is configured, report “No approved update” instead of fabricating one.
- `uninstall` removes only this script's managed binaries and service; preserve projects, OpenCode provider configuration and sessions. Track owned resources to avoid deleting unrelated paths. Never elevate automatically. If persistence after logout or reboot requires administrator-enabled lingering, show the exact requested action and treat the lack of persistence as an explicit limitation.
- Use exit codes and readable step-based output. Quote all paths, validate inputs, keep state on the Linux host so interrupted installs can be inspected/retried, and never execute remotely fetched shell scripts.

### 3. Build and verify in this order

1. **Inspect and reuse:** read the relevant source files and license notices; create the Android project and compile a minimal WebView screen.
2. **Connect first:** implement trust-on-first-use SSH, encrypted credential storage, local forwarding and WebView. Prove it against a **separately installed** OpenCode instance before building provisioning.
3. **Provision:** implement and test the single Linux script; connect Android SFTP → remote execution → verified service → WebView.
4. **Finish:** add remote management, reconnect/error states and uploads/downloads; simplify redundant code.
5. **Release checks:** pin dependencies, retain necessary third-party notices, audit permissions and APK contents, build APK. Evaluate current Google Play rules against the actual shipping app; do not assume approval.

Run, at minimum:

```sh
bash -n app/src/main/assets/install-opencode.sh
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Prove with tests wherever hardware/hosts are available: valid SSH host-key acceptance and changed-key rejection; password and key login; local-port forwarding; OpenCode WebUI login, streaming and session continuity; repeat install; corrupted archive rejection; interruption/retry; failed update restoring previous version; reboot persistence or documented limitation; non-destructive uninstall; WebView crashes and file picker behavior. Test Ubuntu `x86_64` and `aarch64`, and at least one actual Android device. **Never mark a test passed unless it ran.**

## Completion

Success means: **a user can supply a Linux SSH connection, confirm host identity, install OpenCode or connect to an existing installation, authenticate, and use the genuine OpenCode WebUI on Android through SSH; a failed install or update does not lose user data.**

Write code and run checks rather than producing planning files. Prefer reused code and native APIs over new frameworks; do not introduce interfaces with one implementation, extra Gradle modules, generic installers, or unused features. Never deploy to a real host, delete user resources, or publish without authorization. If hardware, network, signing credentials, or a privileged Linux action is unavailable, complete all independent code/tests and report the **specific unverified gate** rather than claiming success. Final report: build location, APK path, test results, and genuine blockers only.
