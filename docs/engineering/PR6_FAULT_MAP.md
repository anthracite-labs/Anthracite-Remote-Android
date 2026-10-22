# PR #6 fault map

Living register for the SSH-workspace PR. Every row is either backed by a CI run,
a local reproduction, or a primary source; suspicion is labelled as such and is
never promoted to fact without evidence.

Column meanings: **ID** stable reference; **Sev** P0 blocks the build or risks
user data, P1 breaks a promised capability or secret handling, P2 is a real but
bounded defect, P3 is polish/robustness. **Status** is one of CONFIRMED,
REPRODUCED, FIXED (awaiting CI), FIXED + VERIFIED (green CI or local run),
PLAUSIBLE / NEEDS TEST, or NOT APPLICABLE.

Baseline: PR head `1ed50d66fcfca6daa6df642aa6fff04af85bb071`, main `84bc457`.
Reviewed failing run: <https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39813806057>
(re-run jobs at `119946851788`), then the first run of the new `compile` job at
<https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39814295783>,
which reported the complete set of eleven errors in one pass.

## Register

| ID | Sev | Area | File:line | Symptom | Evidence | Root cause | Blast radius | Correction | Regression test | Status |
|----|-----|------|-----------|---------|----------|------------|--------------|------------|-----------------|--------|
| B1 | P0 | GitHub API parsing | `github/GitHubApi.kt:454,477,494,507` | `Unresolved reference 'obj'/'string'/'bool'`, inference failures | CI annotations for `1ed50d6` (run 39813806057) | `request { json, _ -> }` hands back a `JsonValue`, but four lambdas indexed it as `JsonValue.Obj` | Every GitHub screen (repos, checks, comment, review, merge) | Narrow with `json.asObject()` and fail with an explicit message when the shape is wrong | `GitHubApiTest` (mock transport) | FIXED (pending CI) |
| B2 | P0 | Terminal | `terminal/TerminalActivity.kt:156` | `Argument type mismatch: String but ByteArray expected` | same run | `TerminalSession.write` takes raw PTY bytes; the start-directory `cd` passed a Kotlin `String` | Terminal could not compile, so no terminal test could run | Encode the single quoted `cd` line explicitly as UTF-8 bytes | covered by compile job + device pass | FIXED (pending CI) |
| B3 | P0 | Deploy | `deploy/DeployTargets.kt:49` | `Unresolved reference 't'/'p'` | same run | Kotlin string template interpolated shell `$t`/`$p` | Deployment screen could not compile | Escape the shell dollars and build the command by concatenation | compile job | FIXED (pending CI) |
| B4 | P0 | Deploy | `deploy/DeployTargets.kt:205` | `Unresolved reference 'obj'` | same run | `MiniJson.parse` returns `JsonValue`; kube pod parsing indexed it as an object | Kubernetes view | `root.asObject()?.obj("items")` | `DeployParserTest` | FIXED (pending CI) |
| B5 | P1 | JSch API | `SshClient.kt` (PTY paths) | `Cannot access 'class ChannelSession': it is package-private in file` | earlier run at `dad1da7` | JSch declares `setPty*` on package-private `ChannelSession`; the code named that type in signatures | Terminal could not compile; fixing it wrongly (reflection/suppression) would hide a real API constraint | Type the channels as the public `ChannelShell`/`ChannelExec`, capture the resize call in a lambda, keep `SshPty` on the public `Channel` | compile job; PTY behaviour in `SshSessionTest` | FIXED + VERIFIED (compile run 119945178387 cleared it) |
| S1 | P1 | Secret storage | `github/GitHubSession.kt:55`, `host/HostStore.kt:394` | A GitHub token is validated against GitHub, then `write()` throws because the store only accepted `[a-f0-9]{16}.bin` | Code inspection; `KeystoreSecretStore.write` pattern vs `GitHubTokenStore.DEFAULT_NAME = "github_token.v1"` | Two independent name rules; the token path was never exercised | GitHub sign-in never persisted: every restart asked for the token again, and the exception escaped into `onSuccess` | One validated namespace (`SecretNames`), a JVM-testable `FileSecretStore`, and explicit error propagation | `SecretStoreTest`, `GitHubTokenStoreTest` | FIXED (pending CI) |
| S2 | P1 | Secret storage | `host/HostStore.kt:415` | A blob that fails to decrypt is reported as "no token stored" | Code inspection | `read()` swallowed every failure with `runCatching { … }.getOrNull()` | A rotated Keystore key or tampering looked like a normal signed-out state | Distinguish absent from unreadable; clear and inform on failure | `GitHubTokenStoreTest` (foreign key, tampered blob) | FIXED (pending CI) |
| S3 | P1 | Secret input | `ui/Rows.kt`, `GitHubActivity.promptToken` | Personal access token typed into an ordinary `EditText` | Code inspection | The generic `promptText` helper was reused for a credential | IME learning/autofill could retain a token; screenshots expose it | `promptSecret`: masked, no suggestions, no personalized learning, not in saved state | manual/device check; lint | FIXED (pending CI) |
| G1 | P1 | Host Git | `gitx/HostGit.kt:228-290` | `git fetch|pull|push|status --no-color` are rejected by git itself | Local repro, git 2.39.5: `error: unknown option 'no-color'`; `git help fetch` lists no such option. Tool: `git -c color.ui=false …` accepted by all subcommands | Colour was disabled per subcommand, but only porcelain/diff/log accept it | Fetch, pull, push and status failed on every host | Disable colour with the global `-c color.ui=false` and drop the subcommand flag | `GitParserTest`, `app/src/test/shell/git-commands-test.sh` (real git, real remote) | FIXED + VERIFIED locally (24/24) |
| F1 | P1 | SFTP | `files/RemoteFiles.kt:229` | Download checked `stat` once, then read the whole stream into an unbounded buffer | Code inspection; new `readBounded` test | Cap enforced after allocation instead of during the read | A host that grows a file, or lies about its size, could exhaust phone memory | Stream through a bounding reader that aborts before the buffer grows past the limit | `SftpBrowserTest`, `readBounded` cases | FIXED (pending CI) |
| F2 | P2 | SFTP | `files/RemoteFiles.kt:247` | Temporary upload name was predictable and shared (`.<name>.anthracite.tmp`) | Code inspection | Fixed suffix, no collision resistance | Two uploads to the same directory could corrupt each other's partial file | Unique hidden temporary name, cleaned up on failure, second existence check before rename | `SftpBrowserTest` (no leftovers, no silent overwrite) | FIXED (pending CI) |
| F3 | P3 | SFTP | `files/RemoteFiles.kt:196` | Recursive delete opens a fresh SFTP channel per entry | Code inspection | `deleteTree` calls `list`/`rm` helpers that each open a channel | Slow deletes on deep trees; no correctness impact | Fold the walk into one channel (tracked, not yet scheduled) | — | PLAUSIBLE (accepted for now) |
| C1 | P1 | CI | `.github/workflows/android-ci.yml` | 24 JUnit jobs failed as one build blocker; only 10 annotations were visible | Run 39813806057 | One compile prerequisite, no batched reporting | Diagnosis required one push per error | `compile` job with `--continue`, de-duplicated error list, six annotation batches, artifact + step summary; `git_integration` and `static_checks` jobs that run without Android | the workflow itself | FIXED (pending CI) |
| C2 | P2 | CI | `.github/workflows/emit-failure.sh` | Reporter could fail with "Broken pipe" and hide the real excerpt | Code inspection (`grep … \| head` under `pipefail`) | `head` closes the pipe early | Misleading annotations | Read into a variable, cap with a counter, always exit 0 | shellcheck (`security-checks`) | FIXED (pending CI) |
| C3 | P2 | CI | `.github/workflows/android-ci.yml` | No job covered resource linking, R-id completeness, manifest sanity or secret hygiene independently of Kotlin | Review | Diagnostics were all Gradle-shaped | A resource problem hid behind Kotlin errors | `ci/static_checks.py` in its own job | self-test in this session (injected a bogus `R.string`, got exactly one finding) | FIXED (pending CI) |
| E1 | P1 | Claims | `docs/support-matrix.md` | Six distributions are listed as "verified in a container" with no linked run | Document review | Rows were written from intent, not evidence | Users are told a distribution is supported on evidence that does not exist | Mark every row unverified until a matrix run exists; link the run when it does | distro matrix job (tracked, not yet added) | CONFIRMED (document corrected) |
| E2 | P2 | Coverage | `app/src/test` | No test for `GitHubApi` against a mock `HttpTransport`, none for `parseTmuxSessions` or `CredentialCrypto` reopen | Review | Those areas were only exercised through the UI | Regressions in HTTP error mapping and tmux parsing would be invisible | Add mock-transport and tmux-parser tests | tracked below | PLAUSIBLE / NEEDS TEST |
| W1 | P0 | Workspace | `workspace/Workspace.kt:141,154` | Cannot access `profile`: it is private | `compile` job annotations, run <https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39814295783> | `profile` has a `private set`, and `private` on an object member is object-scoped, not file-scoped, so the top-level `connectWorkspace` cannot assign it | Every workspace screen: nothing compiled | `Workspace.adopt(...)` writes the profile inside the object, keeping the setter private so only a completed host-key decision can change it | compile job (was previously listed here as "not applicable" — that judgement was wrong and is corrected) | FIXED (pending CI) |
| K1 | P0 | Keys | `terminal/TerminalKeys.kt:86-89` | Argument type mismatch: `String` but `Char` expected | same annotations | F1–F4 passed `"P"`…`"S"` to `function(modifiers, final: Char)` | Terminal could not compile | Pass `Char` literals `'P'`–`'S'`; SS3/CSI output unchanged | `TerminalKeysTest` covers F1–F4 with and without modifiers | FIXED (pending CI) |
| K2 | P0 | Emulator | `terminal/TerminalEmulator.kt:778,783,792,799` | No value passed for parameter `next` | same annotations | Four `ExtendedColor` constructions omitted the sixth constructor argument | Terminal could not compile | Supply the `next` index each branch means (palette: `start + 2`, insufficient RGB: `paramCount`, otherwise `start + 1`, out of parameters: `start`) | `TerminalEmulatorTest` (SGR 38/48 parsing) | FIXED (pending CI) |

## Security and architecture checklist

| Area | Question | State |
|------|----------|-------|
| SSH trust | Changed host key refused, first use needs approval in every new surface | code: `connectWorkspace` mirrors `SshClient` outcomes; tests: `SshSessionTest` |
| Credentials | Never logged, never on the host, sealed with a Keystore key, one namespace | S1/S2 above |
| Terminal input | Paste cannot replay C0/C1 controls; OSC 52 blocked; OSC 8 never opened | `TerminalKeysTest`, `TerminalEmulatorTest` |
| Terminal trust | DCS/PM/APC consumed, title bounded, links bounded | `TerminalEmulatorTest` |
| Command grammar | No `eval`, single quoting gate, structured tokens validated | `PosixShellTest`, `GitParserTest`, `DeployParserTest` |
| Job state | Plan digest re-checked on the host, PID start-time match before cancel, interrupted reported as unknown | `ProvisionJobsTest`, host-kit suite |
| Privilege | No stored sudo password, no implicit root, Docker socket access stated | provisioning runner + deploy subtitle |
| GitHub | Token never in a host or log; 401 clears it; merge pinned to the reviewed SHA | `GitHubApi` merge guard, `GitHubTokenStoreTest` |
| CI supply chain | Pinned actions, `contents: read`, no secrets, no `pull_request_target` | workflow review |
| Device claims | Emulator-only smoke; IME/rendering/real daemons untested | documented in `docs/support-matrix.md` |

## Next actions

1. Read the next `compile` result; clear whatever it reports beyond W1/K1/K2.
2. Add `GitHubApi` mock-transport coverage and `parseTmuxSessions` cases (E2).
3. Add a real distribution matrix job and link its run in `docs/support-matrix.md` (E1).
4. Device pass for IME, rendering and touch once an emulator run exists.
