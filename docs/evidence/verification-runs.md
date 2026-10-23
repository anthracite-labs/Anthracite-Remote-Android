# Verification runs

Every claim in this repository points at a run. Workflow logs are not always
retrievable from the API, so each temporary workflow publishes its result as a
check-run annotation (`::notice`/`::error`) as well; those annotations were read
back with `gh api .../check-runs/<id>/annotations`.

## Run index

| What | Workflow | Run | Commit | Result |
| --- | --- | --- | --- | --- |
| Android compile, P1 unit tests, host kit (cancel identity + plan integrity) | `_temp-anthracite-p1` | 39886906955 | `c3548b4` | success |
| Six-distribution provisioning matrix | `_temp-anthracite-matrix` | 39886906954 | `c3548b4` | success (6/6) |
| iOS: Swift package tests, simulator build, UI smoke tests | `_temp ios` | 39886906949 | `c3548b4` | success |
| Six-distribution matrix with resolved image digests | `_temp-anthracite-matrix` | 39881616212 | `751e7e8` | success (6/6) |

All three workflows were temporary, feature-branch-only, `permissions: contents:
read`, pinned action SHAs, `persist-credentials: false` on checkout, bounded
timeouts, hosted runners only, and no production credentials or hosts. They were
deleted before the branch was proposed for merge, as required. Run history is
permanent.

## What each run proves

**P1 workflow.** The Android app compiles, the P1 unit tests pass (cancellation
contract, planner/tier rules, GitHub merge pinning), and the host-kit scripts pass
their shell suites under both `dash` and `bash`:

* `provision-run-test.sh` — 55 cases including a blank, absent and malformed plan
  digest, symlinked `plan`/`steps`, a manager/OS mismatch, no SHA-256 tool at all
  (refused before any mutation, nothing installed) and the `shasum` alternative.
* `provision-cancel-test.sh` — 32 cases including a reused PID, mismatched start
  ticks, missing ticks, a terminal job, a symlinked job directory, a foreign
  owner, a vanished process, a real runner, and "no signal was sent" on every
  unproven case.

**Matrix.** Six disposable containers each completed: distribution/manager
discovery with the shipped probe, a real package install with the shipped runner,
native package verification, a deliberate install failure, cancellation with a
proven process identity, a refusal for an unproven identity, and removal of the
installed package. The resolved digest of each image is recorded in
`docs/evidence/container-matrix.md` and in `ProvisioningSupport.evidence`.

This found three real defects, all fixed: `awk` is absent on openSUSE
Tumbleweed minimal; the fixture stripped the steps file's trailing newline; and the
fixture put jobs outside `$HOME/.anthracite/jobs`, which the reviewer correctly
refused.

**iOS.** `swift test` passed the `AnthraciteKit` suites (terminal emulation and key
encoding, host-key fingerprints and pinning, OpenSSH key parsing, tmux identity,
Git parsing and path policy, deployment command construction, SFTP wire format,
provisioning plan/cancel semantics, storage, and the GitHub client against a stubbed
transport). The app built for the iPhone 16 simulator and a XCUITest launched it,
walked to the server form, checked that the form says where the secret is stored,
and checked that an incomplete server is refused with an explanation.

## What no run proves

* No real Linux host was used: SSH against a real OpenSSH server, tmux, SFTP,
  Docker/Podman, Kubernetes and systemd were not exercised end to end.
* No physical iPhone or Android device was used: IME behaviour, TalkBack/VoiceOver,
  glyph rendering and re-creation after process death are unverified.
* The GitHub client was tested against a stubbed transport, never the live API:
  no issue or pull request was created or merged by these runs.

These are listed as unverified in `docs/support-matrix.md`; they are not silently
counted as passing.
