# iOS client — evidence

**Build and test run:** [39886906949](https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39886906949)
**Commit:** `c3548b4456f617edc7f0bc12bd0e75532876d129`
**Runner:** GitHub-hosted `macos-15`, `permissions: contents: read`, pinned action
SHAs, `persist-credentials: false`, 40-minute timeout.
**Workflow:** `_temp ios` (temporary; deleted before merge; `ios-ci.yml` is the
permanent equivalent).

What the run did, in order:

1. `swift test` for the `AnthraciteKit` package:
   * `TerminalTests` — cursor movement, erase, SGR and true colour, alternate
     screen, wide glyphs and resize, bracketed-paste tracking, OSC 52 blocked,
     OSC 8 restricted to `https`, title sanitising, unknown/overlong sequences
     consumed, DCS/APC discarded, device-status replies, scroll regions, resize.
   * `KeyEncodingTests` — Ctrl characters, Alt prefixing rules, application cursor
     mode, function keys, bracketed paste, mouse encoding bounds.
   * `HostKeyTests` — a fingerprint that matches `ssh-keygen -lf`, blob parsing
     that refuses RSA and truncation, the pin lifecycle (first use, approval,
     change, per-port scope, forgetting) and OpenSSH Ed25519 parsing.
   * `TmuxTests`, `GitTests`, `DeployTests`, `SFTPWireTests`, `StorageTests` — see
     the test names for the exact invariants.
   * `ProvisioningTests` — the reviewed digest of the steps file, the plan-file
     grammar, that cancellation names a job directory and never a PID, that a
     requested signal is not a confirmed stop, that an unproven live process is
     reported `UNKNOWN`, and that only this machine's own completed evidence can
     skip the unverified-plan acknowledgement.
   * `GitHubTests` — issue listing/detail/comments, create/comment/close, checks,
     that a merge always carries the reviewed head SHA, that a blocked merge is an
     error, and the 401/403/404/rate-limit mapping (against a stubbed transport).
2. `xcodegen generate` from `ios/project.yml`.
3. `xcodebuild build` for `platform=iOS Simulator,name=iPhone 16`.
4. `xcodebuild test` running `LaunchUITests` on the simulator with
   `-useMemoryStore`: first launch shows the connection list, **Add a server**
   opens the form, the form states that the secret is stored in the Keychain, an
   incomplete server is refused with an explanation, and Cancel returns.

Result annotations read back from the check run: `AnthraciteKit unit tests
passed`, `the app built for iOS Simulator`, `simulator UI smoke tests passed`.
The `.xcresult` bundle was uploaded as an artifact.

## What this evidence does not cover

* **No live SSH server.** The transport, PTY, SFTP and tmux paths compile and
  their pure logic is tested, but the first real connection has not been made from
  this repository's CI. Against a real host, expect to validate: PTY echo and
  window-change, `sftp` subsystem availability, and host-key algorithms (this
  client accepts Ed25519 and ECDSA P-256/384/521; RSA host keys are refused, which
  the UI reports as an unsupported key rather than silently trusting).
* **No physical device.** IME, VoiceOver, hardware-keyboard behaviours and
  re-creation after process death are unverified.
* **Encrypted private keys are unsupported.** The parser accepts unencrypted
  `openssh-key-v1` Ed25519 keys only, and says so in the form.
* **GitHub is stubbed.** No live issue or pull request was created or merged.
* **Deployments are not live-tested.** Container and Kubernetes commands are unit
  tested; no daemon or cluster was used.

## Reproducing

```sh
brew install xcodegen
sh ci/ios-build.sh          # swift test + simulator build + UI tests
```

The app target bundles the reviewed host scripts from
`app/src/main/assets/host`, so the Android and iOS clients cannot drift apart on
what they upload.
