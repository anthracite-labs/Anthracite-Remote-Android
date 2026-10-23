# 0002 — iOS client: one kit, one host kit, no second protocol

Status: accepted (2026-10-23)

## Context

Decision 10B requires a native iOS client with the same essentials as Android:
verified SSH, a real PTY terminal with tabs, SFTP, host Git, GitHub, deployments
and guided provisioning. Two failure modes had to be avoided: an iOS app that
re-implements the host side and drifts from the reviewed scripts, and an iOS app
that quietly weakens host-key verification because the library makes pinning
awkward.

## Decision

* **One Swift package for logic** (`ios/`, target `AnthraciteKit`), with the
  SwiftUI app as a thin target. Everything with a security consequence — key
  pinning, path validation, command construction, plan digests, cancel outcome
  parsing — is in the package and unit tested without a simulator.
* **SwiftNIO SSH for transport.** It is the maintained Apple library, gives raw
  channel control (PTY, window-change, subsystems) and does not hide the host key.
* **Fingerprint via a `ssh-keyscan`-style probe.** `NIOSSHPublicKey` exposes no
  serialisation, so the client performs the version exchange and an ephemeral
  Curve25519 key-exchange initiation purely to read the server's key blob, computes
  the same `SHA256:…` fingerprint `ssh-keygen -lf` prints, and asks the user to
  compare it. Pinning then compares the key presented during the *real* handshake
  for equality with the approved key, so the approved key is the only one accepted.
* **One copy of the host kit.** The app target bundles
  `app/src/main/assets/host` directly. There is no second installer, no second
  runner, and no second cancel script.
* **Cancellation stays host-side.** The iOS client sends only a job directory; the
  reviewed cancel script re-derives and proves the process identity before
  signalling, exactly as on Android.
* **Refuse rather than guess.** RSA host keys, encrypted private keys and unknown
  SFTP versions are refused with an explanation. Non-ed25519 private keys are not
  silently downgraded to password auth.

## Consequences

* The realistic first-run limitation is authentication breadth: unencrypted
  Ed25519 keys and passwords only. Encrypted keys need a passphrase flow and a
  KDF implementation; that is a separate, testable change rather than a shortcut.
* The keyscan probe adds one TCP round trip before each connection. It is the
  price of showing a fingerprint the user can actually verify.
* `xcodebuild` and a macOS runner are required for app-level evidence; the package
  itself stays testable with `swift test`.

## Alternatives rejected

* A third-party SSH wrapper that does not expose host keys — pinning would be
  theatre.
* Copying the host scripts into the iOS bundle at build time with a script — two
  copies, two ways to drift.
* Porting the Kotlin emulator line by line — the Swift emulator is smaller by
  design, and its behaviour is pinned by tests rather than by resembling another
  language.
