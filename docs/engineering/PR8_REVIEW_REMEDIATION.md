# PR #8 external review — remediation record

The review of PR #8 (head `1fc1a24`, base `0aaf63e`) requested changes. This
record maps each finding to the code and the test that now covers it, and states
what is still unproven. Nothing here is claimed as verified on a real host.

## A1 — destructive Kubernetes actions depended on the plan, not on the cluster

**Was.** `workloadRollback` and `workloadDelete` sent `kubectl rollout undo` /
`kubectl delete` for whatever the plan named. `workloadApply` re-read the context
and RBAC, but nothing re-read the object, and no identity was recorded, so a
Deployment deleted and re-created by someone else under the same name looked
exactly like the one this app had made.

**Now.**

* `DeployPlan.Workload` carries `appliedUid`: the immutable UID the cluster
  reported after the apply (`workloadApply` reads `kubectl get … -o json` and keeps
  the UID only when the object also carries `anthracite.managed=1`).
* `DeployCreator.workloadPreflight(plan, verb)` re-reads, in order: the active
  context (`kubectl config current-context`), authority for the exact verb
  (`kubectl auth can-i <get|patch|delete> <resource>`), and the live object's UID
  and ownership label. Any unreadable answer, any answer other than `yes`, and any
  UID or label mismatch refuses with exit 64 and a stated reason.
* `workloadRollback` and `workloadDelete` run only through that guard, and a plan
  with no recorded UID refuses *before any command is sent at all*.
* The identity is parsed with the project's strict JSON reader, not a jsonpath:
  the label key contains dots, and a structural parse cannot be fooled by a
  document that does not carry what we expect.

**Tests.** `DeployGuardTest` (fake `RemoteShell`, real command sequence): replaced
resource (different UID), missing label, denied delete, unparseable `can-i` answer,
changed context, object gone, no recorded UID, rollback under the same guard, and
one positive case asserting the exact four commands and that the delete is the
only mutation. `DeployPlanTest` covers the UID grammar and the command shapes.

## A2 — the created name could never match the reviewed manifest

**Was.** The wizard asked for a free-form manifest and then generated a random
`DeployPlan.Workload(name = newName())`, so `require(metadata.name == name)` could
only be satisfied by a user typing the name the app had not told them yet.

**Now.** `DeployWizard` owns the name: it is fixed when creation starts, before the
first prompt, and every hint, the review screen and the applied object use it. A
workload is described by typed fields — image, replicas, container port — and the
manifest is rendered by `DeployPlan.manifest()`. The Activity only passes typed
strings into the wizard, so the tested path is the user's path.

**Tests.** `DeployWizardTest` drives the wizard exactly as the Activity does
(name → kind → image → replicas → port), asserts the plan carries the wizard name,
the manifest names it, and that defaults and out-of-range values behave as
documented.

**UI-to-host integration** (the review's B4 note) was **not** performed: there is
no Android instrumentation test in this repository that drives the wizard against
a host. That gap is stated in `docs/support-matrix.md` and is not claimed.

## A3 — environment values were in the review screen and in argv

**Was.** `Container.runCommand()` embedded `--env 'KEY=value'`; the value appeared
in the confirmation dialog, in the process list of the host, and in any log of the
command.

**Now.** Values are written through stdin to `$HOME/.anthracite/deploy/<name>.env`
under `umask 077`, passed with `--env-file`, and removed immediately after the
container starts. The review screen shows `KEY=***` only. The plan's warnings state
the remaining exposure independently: anyone who can reach the container runtime's
socket can read the values with `inspect`.

**Tests.** `DeployPlanTest` asserts the exact secret appears in no command, no
`DeployPlanReview.detail(...)` output and no removal command, that the file bytes
are the exact `KEY=value\n` pairs, and that the file is created under a private
umask. `DeployWizardTest` repeats the assertion through the wizard's own plan.

## A4 — iOS treated any nonzero `has-session`/listing exit as "gone"

**Was.** `AppModel.stop()` read a nonzero `has-session` exit as stopped, and
`tmuxSessions()` returned `[]` for any failure whose stderr merely contained
`no server running` — and for every other failure too.

**Now.** `Tmux.Presence` (present/absent/unknown) is derived from exit status plus
one of tmux's own "there is nothing to list/stop" messages, matched exactly:
`no sessions`, `no server running on /<socket path>`, and `can't find session:
<this id>`. Everything else — permission denied, connection refused, lost server,
a mangled listing, a replacement under the same name or id — is `unknown`.
`stop()` reports unknown as unknown and never as success. `tmuxSessions()` throws
rather than returning an empty list, and the Sessions button shows that error.

**Tests.** `ios/Tests/AnthraciteKitTests/TmuxPresenceTests.swift`: four positive
cases plus nine negative ones for the listing and for `has-session` each.

## A5 — raw YAML was validated by regex

**Was.** A pasted manifest was checked for kind, `metadata.name` and the absence of
`---`; duplicate keys, a foreign `metadata.namespace` and forged labels passed.

**Now.** No raw YAML is accepted anywhere. The manifest is generated from
validated typed fields, so there is exactly one document, one resource, the
generated name, the reviewed namespace and the ownership label. Cluster-scoped
kinds and arbitrary kinds are not representable: `WorkloadKind` is a closed enum
of `Deployment` and `StatefulSet`.

**Tests.** `DeployPlanTest` asserts one `kind:` line, zero `---` separators, the
generated name/namespace/label, and refuses hostile image strings, out-of-range
replicas and ports, and forged UIDs.

## B3 — vendor agent CLIs: decision

One-click tool selection covers the shipped native package catalogue. Vendor agent
CLIs (OpenCode, Claude Code, Codex, Gemini) are **deliberately manual
instructions, outside the bootstrapping contract**: their vendors publish no
pin-verifiable artifact for every supported distribution, so the app executes
nothing for them. This is stated in `README.md` as a scope decision; no one-click
installer for those CLIs is claimed or planned in this PR.

## Evidence

Permanent CI on `48690bd`: Android CI 39894828697, Android instrumented
39894828696, Security checks 39894828731, CodeQL 39894828702, iOS CI 39894828736 —
all successful. Details and the failed first attempt (39894393722) are in
`docs/evidence/verification-runs.md`.

## Not proven

* No live Kubernetes cluster, container runtime, tmux server or SSH host was used.
  The guard's *logic* is unit tested against a fake shell; its behaviour against a
  real API server, a real RBAC authorizer and a real UID is not.
* No physical device and no Android instrumentation test of the wizard.
* The iOS listing parser is unit tested; no live tmux server was contacted.
