# P1 repairs — evidence

Two safety defects and one fail-open integrity condition were repaired first, as
the contract requires. Nothing in this document is a claim about a real host.

## P1-A — cancellation must verify the host process identity

**Defect.** `ProvisionClient.cancel()` read a status snapshot on the phone and then
sent `kill -TERM <recorded PID>` over SSH. `JobStatus.startTicksMatch` is `null`
when the host cannot read `/proc` or the saved start ticks, and `null` was allowed
while `processAlive` was true, so a reused PID could be signalled. The identity was
also checked at one point in time and the signal sent later.

**Repair.** The phone no longer chooses a PID and never sends a signal. A new
reviewed host-kit script (`app/src/main/assets/host/anthracite-provision-cancel.sh`)
re-derives everything immediately before signalling:

1. the job directory is exactly `$HOME/.anthracite/jobs/<16 lowercase hex>`, a real
   directory (not a symlink), owned by this user, and resolves to that same path;
2. `pid`, `start_ticks` and `state` are regular files owned by this user; the PID is
   numeric, at most ten digits and greater than 1;
3. the live process exists, is not a zombie, is owned by this user, still has the
   recorded `/proc/<pid>/stat` field 22 start time, and its command line names both
   `anthracite-provision-run.sh` and *this* job id;
4. a job whose state is already terminal is not signalled at all.

Outcomes are explicit and parseable: `refused_invalid_job`,
`refused_unknown_identity`, `refused_not_owner`, `already_finished`,
`signal_sent`, `still_running`, `confirmed_stopped`, `signal_failed`. Exactly one
`SIGTERM` is sent, never `SIGKILL`; a `kill` syscall returning success is never
reported as termination — only an observed exit is (`confirmed_stopped`).

The status script now also reports `identity=verified|unverified|absent`, and the
phone maps a `RUNNING` record with an unverified live process to `UNKNOWN` rather
than "running".

**Tests.** `app/src/test/host/provision-cancel-test.sh` — 32 cases, run under both
`dash` and `bash`:
missing/unknown start ticks, reused PID, mismatched ticks, terminal state, symlinked
`pid` file, symlinked job directory, job outside the jobs root, malformed job id,
foreign-owned directory, PID 1 and PID 0, vanished process, a genuinely running
runner, a second request on a stopped job, cancellation during a (slow) package
transaction, concurrent stop requests, and "no signal was sent" assertions on every
unproven case.

**Before/after.** Before the script existed the suite could not even run (the
script was absent: `FAIL cancel script is missing`). After the repair: 32/32 under
both interpreters, in CI, plus JVM parsing tests (`ProvisionJobsTest`) for every
outcome including "a signal that was only requested is not a confirmed stop".

## P1-B — read-only discovery must not certify provisioning

**Defect.** `ProvisioningSupport.tierFor()` returned `VERIFIED_CONTAINER` for six
distribution names on the strength of a read-only probe, and `decide()` treated that
tier as `ALLOWED`, bypassing the unverified-provisioning acknowledgement even though
no package had ever been installed.

**Repair.** Capabilities are now separate values (`ProvisionCapability`: SSH/PTY, OS
discovery, plan generation, package installation, verification, cancellation,
cleanup, session durability, reboot durability, container runtime, Kubernetes). A
`DistributionEvidence` record names the exact image digest, date, run and the
capabilities that run actually exercised. Promotion is impossible: `installTested`
requires *install and verify* evidence, and:

* a real host of a tested distribution is `UNVERIFIED` — a container test cannot
  certify a host's package availability, supervisor, logout or reboot behaviour;
* a container matching a tested image is `TESTED_IN_IMAGE`, and **still requires
  the acknowledgement** (`ALLOWED_UNVERIFIED`);
* only `VERIFIED_HOST`, keyed to the host's own `/etc/machine-id` and a completed
  host run, skips the acknowledgement. No host has been run, so no host can claim
  it.

**Evidence shipped.** Six records, from the container matrix run
[39881616212](https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212),
with the resolved digest of each image and the capabilities that run exercised
(discovery, plan, install, verify, cancellation, cleanup — not SSH, not session
durability, not reboot). See `docs/evidence/container-matrix.md`.

**Tests.** `ProvisionPlannerTest`: discovery-only evidence stays `UNVERIFIED`; image
evidence stays `ALLOWED_UNVERIFIED` even for a matching container; evidence for
another release or architecture does not transfer; host evidence for another
machine-id does not transfer; a host without a machine identity can never match;
the shipped records contain only exercised capabilities.

## Integrity gate — a plan that cannot be verified must not run

**Defect.** `anthracite-provision-run.sh` verified the steps digest only
`if command -v sha256sum && [ -n "$EXPECTED_HASH" ]`, and otherwise wrote
`plan_hash_verified=unavailable` and **continued into package mutations**.

**Repair.** Before the lock and before any package-manager call the runner now:

* requires `plan`/`steps` to be regular files owned by the current user (no
  symlinks) and the job directory to be owned and not a symlink;
* requires the job id to be 16 hex characters and to match the directory name;
* requires `plan_hash` to be exactly 64 lowercase hex characters;
* requires a working SHA-256 implementation (`sha256sum`, else `shasum -a 256`,
  else `openssl dgst -sha256 -r`); if none is usable it fails with
  `no_checksum_tool` and records that as the reason;
* validates the whole steps grammar (trailing newline, three tab-separated fields,
  strictly increasing indices, `install` kind, package-name character set) before
  executing anything;
* refuses a distribution/manager mismatch (e.g. `dnf` on Debian).

Package-manager locks remain untouched; the job's own per-user lock is unchanged.

**Tests.** `app/src/test/host/provision-run-test.sh` — 55 cases under `dash` and
`bash`, including: blank/absent/short `plan_hash` (each asserted to mutate nothing),
symlinked `plan` and `steps`, unterminated steps file, manager/OS mismatch, a
restricted `PATH` with no SHA-256 tool at all (refused, nothing installed) and the
same restricted `PATH` plus a reviewed `shasum` alternative (plan verified, job
succeeds, `plan_hash_verified=shasum` recorded).

**Before/after.** Before the repair the new cases produced 17 failures against the
old runner, including `an unavailable checksum tool mutates nothing` → the old
runner *installed packages* with no checksum available. After the repair: 55/55
under both interpreters.

## Verification runs

| Purpose | Run |
| --- | --- |
| P1 host kit + Android compile/unit tests (temporary workflow) | see `docs/evidence/verification-runs.md` |
| Six-distribution install matrix (temporary workflow) | [39881616212](https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212) |
| Permanent CI on the cleaned commit | see `docs/evidence/verification-runs.md` |

## Residual risk

* The compare-and-signal window between the identity checks and `kill` is
  microseconds but not zero; closing it would need kernel support. Everything that
  can be verified without that is verified.
* A package transaction that has already started is allowed to finish. Cancel is a
  request that the runner honours at a command boundary, which is why
  `signal_sent`/`still_running` are separate states from `confirmed_stopped`.
* Host evidence (`VERIFIED_HOST`) requires a real host run that has not happened;
  the code path exists and is unit tested with injected evidence.
