# Anthracite — ranked repository skills and engineering-tooling backlog

Baseline inspected: main at d8a8065495ebf0ae7eee81eadc7f79eee3e2651c (PR #8 merged, 2026-10-23).
This file is the repo-controlled plan. Items marked PROPOSED are not installed by this document-only change.
Ranks are implementation order, not a command to import all upstream repositories.

## Repo-local layout (required)

- AGENTS.md: short mandatory engineering instructions and pointers to project skills.
- .agents/skills/<skill-name>/SKILL.md: committed, reviewed agent skills. Add references/ and scripts/ only when needed.
- .agents/skills/PROVENANCE.md: origin repository, exact upstream commit, license, local changes, last review for each copied third-party skill.
- .github/workflows/: pinned, least-privilege CI jobs (PR critical checks; heavy tests in scheduled/manual jobs).
- ci/: reproducible runners, fixtures, helper scripts.
- app/src/test/ and ios/Tests/: deterministic behavioral regression tests.
- docs/engineering/: security model, acceptance matrix, tool baselines, reports.

Agent Skills format: https://github.com/agentskills/agentskills

## P0 — fix correctness and verification blind spots first

| Rank | Type and item | Source | Add to repo / expected acceptance | Status |
| ---: | --- | --- | --- | --- |
| 1 | Project-wide agent engineering contract | https://github.com/agentskills/agentskills | AGENTS.md: describe SSH trust, stdin-only secrets, exact process/resource identity, cancellation, no agent backend, red-green tests, final-head CI, and citation/evidence rules. | PROPOSED |
| 2 | Anthracite regression fixer skill | Local custom | .agents/skills/anthracite-regression-fixer/SKILL.md: reproduce, failing test, minimal fix, rerun affected/full suites, prove cleanup. | PROPOSED |
| 3 | Anthracite concurrency and cancellation auditor skill | Local custom | .agents/skills/anthracite-concurrency-audit/SKILL.md: Swift continuations/actors/NIO callbacks and Kotlin coroutine cancellation; test failure/close/timeout/cancel interleavings. | PROPOSED |
| 4 | Extend CodeQL to Swift | https://github.com/github/codeql-action | .github/workflows/codeql-swift.yml: dedicated macos-15 Swift manual build, resolved dependencies, security-extended, SARIF; keep existing Java/Kotlin + Actions analysis. | GAP: existing CodeQL excludes Swift |
| 5 | Anthracite security/threat-model skill | Local custom | .agents/skills/anthracite-security-review/SKILL.md: trust boundaries for SSH/SFTP, shell quoting, secret handling, tmux, host PID, Docker IDs, Kubernetes UID/resourceVersion; fail-closed review. | PROPOSED |
| 6 | Live disposable OpenSSH/SFTP fixtures | https://github.com/openssh/openssh-portable | ci/integration/ssh/: ephemeral private host-key/auth fixture; test real scan cancellation, pinning, authentication, PTY, SFTP, tmux and disconnects; no user's hosts. | GAP: scripted peers only |
| 7 | Testcontainers-backed service tests | https://github.com/testcontainers/testcontainers-java | app/src/test integration classes and ci/integration/: disposable SSH/SFTP services with JUnit and fixed images/digests; not an app runtime dependency. | PROPOSED |
| 8 | Real disposable Kubernetes API tests (kind) | https://github.com/kubernetes-sigs/kind | ci/integration/kubernetes/: create-only, server-side delete UID precondition, replaced UID, rollback race and permission refusal. | GAP: mocked kubectl only |
| 9 | Network fault injection (Toxiproxy) | https://github.com/Shopify/toxiproxy | ci/integration/network/: deterministic drop/delay/half-close during SSH/SFTP operations and cancellation. | PROPOSED |
| 10 | Differential security PR review skill | https://github.com/trailofbits/skills | Review and pin upstream differential-review into .agents/skills/differential-review; require pinned merge base, precise lines and regression evidence. | PROPOSED |
| 11 | Whole-codebase audit context skill | https://github.com/trailofbits/skills | Review and pin audit-context-building into .agents/skills/audit-context-building; map trust boundaries and failure states before auditing. | PROPOSED |
| 12 | Bug variant analysis skill | https://github.com/trailofbits/skills | Review and pin variant-analysis into .agents/skills/variant-analysis; search sibling paths for new findings. | PROPOSED |
| 13 | Semgrep customized safety rules | https://github.com/semgrep/semgrep | .semgrep/anthracite.yml and CI: targeted Kotlin/Swift/shell rules for identity, insecure defaults, secret leaks, unsafe shell patterns. Test and suppress false positives explicitly. | PROPOSED |
| 14 | Kotlin correctness/complexity checks (detekt) | https://github.com/detekt/detekt | config/detekt.yml plus pinned compatible Gradle integration; baseline preexisting findings and fail on new ones. Check Kotlin/AGP compatibility first. | PROPOSED |
| 15 | Swift correctness/quality lint (SwiftLint) | https://github.com/realm/SwiftLint | .swiftlint.yml and pinned macOS CI job; baseline existing debt, focus on correctness/high complexity, do not block on styling alone. | PROPOSED |

## P1 — review, mobile security, quality gates and release evidence

| Rank | Type and item | Source | Add to repo / expected acceptance | Status |
| ---: | --- | --- | --- | --- |
| 16 | PR bug-finding skill | https://github.com/getsentry/skills | Review and pin find-bugs into .agents/skills/find-bugs; distinguish reproduced defects from hypotheses. | PROPOSED |
| 17 | CI-to-green PR iteration skill | https://github.com/getsentry/skills | Review and pin iterate-pr into .agents/skills/iterate-pr; require final SHA, actual job results, no force push. | PROPOSED |
| 18 | Security code review skill | https://github.com/getsentry/skills | Review and pin security-review into .agents/skills/security-review; enforce authorization and data handling review. | PROPOSED |
| 19 | Secret detection (Gitleaks) | https://github.com/gitleaks/gitleaks | .gitleaks.toml and pinned PR workflow; scan committed history/changed lines without printing found secrets; complement existing regex checks. | PROPOSED |
| 20 | Mobile security acceptance standards | https://github.com/OWASP/masvs and https://github.com/OWASP/mastg | docs/security/mobile-acceptance.md mapping Android/iOS checks to MASVS and MASTG; record evidence and untested status. | PROPOSED |
| 21 | Cross-platform mobile E2E (Maestro) | https://github.com/mobile-dev-inc/maestro | ci/mobile-flows/: Android emulator + iOS simulator flows for onboarding, reconnect, error UI, keyboard, host key changes; physical hardware later. | PROPOSED |
| 22 | Secret serialization review skill | https://github.com/getsentry/skills | Review and pin secret-serialization into .agents/skills/secret-serialization; inspect logs, error strings, preferences, JSON, debug output. | PROPOSED |
| 23 | Insecure defaults / dangerous APIs skill | https://github.com/trailofbits/skills | Review and pin insecure-defaults and sharp-edges into .agents/skills/; inspect settings, permissions, fallback trust decisions. | PROPOSED |
| 24 | Skill supply-chain scanner | https://github.com/getsentry/skills | Review and pin skill-scanner; audit SKILL.md, scripts, tool permissions and remote hooks before copying external skills. | PROPOSED |
| 25 | Container/IaC/dependency analysis (Trivy) | https://github.com/aquasecurity/trivy | .github/workflows/trivy.yml scheduled/manual: SBOM/lockfiles, pinned disposable container images, Kubernetes YAML; avoid redundant PR blockers. | PROPOSED |
| 26 | Improve existing MobSF APK baseline | https://github.com/MobSF/Mobile-Security-Framework-MobSF | Change existing Android CI MobSF job from latest/nonblocking to version/digest pinned, report machine-readable findings; only gate reviewed high-confidence rules. | PRESENT: improve existing job |
| 27 | Dependency takeover/reputation audit skill | https://github.com/trailofbits/skills | Review and pin supply-chain-risk-auditor into .agents/skills/; periodic review of direct/transitive Gradle and Swift dependencies. | PROPOSED |
| 28 | CI workflow security review skill | https://github.com/getsentry/skills | Review and pin gha-security-review; augment, do not duplicate, current actionlint + zizmor + CodeQL Actions checks. | PROPOSED |

## P2 — test depth, memory, refactoring and developer ergonomics

| Rank | Type and item | Source | Add to repo / expected acceptance | Status |
| ---: | --- | --- | --- | --- |
| 29 | Kotlin test coverage (Kover) | https://github.com/Kotlin/kotlinx-kover | Gradle coverage report and PR diff evidence; prioritize security/ownership branches over overall percentage. | PROPOSED |
| 30 | JVM input fuzzing (Jazzer) | https://github.com/CodeIntelligenceTesting/jazzer | ci/fuzz/: fuzz SFTP/terminal/Git/json parsers; stand-alone harness if JUnit 5 migration is unwarranted; persist minimized crash inputs. | PROPOSED |
| 31 | Property-based testing skill | https://github.com/trailofbits/skills | Review and pin property-based-testing; invariants for identity, shell escaping, parser rejection and state machines. | PROPOSED |
| 32 | Android leak detection (LeakCanary) | https://github.com/square/leakcanary | app/build.gradle.kts debugImplementation ONLY; exercises Activity/terminal/SSH lifecycle leaks; never include in release artifact. | PROPOSED |
| 33 | Refactor and simplify skill | https://github.com/getsentry/skills | Review and pin code-simplifier; require behavior-preserving regression coverage, no removal of trust checks or safety logging. | PROPOSED |
| 34 | Anthracite architecture/refactor skill | Local custom | .agents/skills/anthracite-refactor-review/SKILL.md: deep seams, duplication, dead abstractions, Kotlin/Swift drift; proposals before rewrites. | PROPOSED |
| 35 | Kotlin/Swift shared golden contract fixtures | Local custom | ci/contracts/: common trusted JSON fixtures for host identity, deployment identity, parser behavior, command escaping and refusal states; run both platforms. | PROPOSED |
| 36 | Kotlin formatting (ktlint) | https://github.com/ktlint/ktlint | .editorconfig and pinned format/check command; use formatting rules distinct from detekt; no mass-change PR. | PROPOSED |
| 37 | Swift formatting (SwiftFormat) | https://github.com/nicklockwood/SwiftFormat | .swiftformat with pinned binary; ensure compatible with SwiftLint; keep format-only changes isolated. | PROPOSED |
| 38 | Shell formatting (shfmt) | https://github.com/mvdan/sh | ci format check for installer and provisioning scripts; verify portability under dash, bash, busybox ash. | PROPOSED |
| 39 | Python checks (Ruff) | https://github.com/astral-sh/ruff | pyproject.toml and CI checks for ci/*.py; preserve special runner output/annotations. | PROPOSED |

## P3 — optional or overlapping tooling, install only if justified

| Rank | Type and item | Source | Add to repo / expected acceptance | Status |
| ---: | --- | --- | --- | --- |
| 40 | Scheduled live external-link checking | https://github.com/lycheeverse/lychee | .github/workflows/doc-links.yml scheduled/manual; keep current offline link validation in PR jobs. | OPTIONAL |
| 41 | Shell test framework (Bats) | https://github.com/bats-core/bats-core | Use only if existing executable shell tests are becoming unmaintainable; no gratuitous rewrite. | OPTIONAL |
| 42 | PR inline analyzer annotation (reviewdog) | https://github.com/reviewdog/reviewdog | Use only after analyzer ownership is clear; preserve minimum token permissions and no write tokens on untrusted PRs. | OPTIONAL |
| 43 | Specialized mobile source rules (mobsfscan) | https://github.com/MobSF/mobsfscan | Add only if it finds meaningful Kotlin/Swift issues beyond Semgrep + Android Lint + SwiftLint + CodeQL + MobSF APK. | OPTIONAL/OVERLAPS |
| 44 | OSV-Scanner for dependency corroboration | https://github.com/google/osv-scanner | Scheduled/manual only if Trivy + Dependabot + GitHub dependency review miss coverage; do not run three equivalent PR scans. | OPTIONAL/OVERLAPS |
| 45 | Static-analysis false-positive triage skill | https://github.com/trailofbits/skills | Review and pin fp-check; require independent exploit or false-positive evidence before suppressing reported issues. | OPTIONAL |
| 46 | Spec-to-code compliance skill | https://github.com/trailofbits/skills | Review and pin spec-to-code-compliance; enforce no forced cloud/agent architecture and unchanged Linux CLI semantics. | OPTIONAL |

## Existing checks: preserve, do not install a second copy

- CodeQL (java-kotlin and actions; Swift is missing).
- Android Lint, Gradle unit/instrumentation tests, Android emulator security smoke.
- ShellCheck, actionlint, zizmor, dependency review, Gradle dependency submission.
- Dependabot and OpenSSF Scorecard.
- CycloneDX SBOM emission and non-blocking Android MobSF APK baseline.
- Swift Package.resolved, checksum-verified XcodeGen, iOS Swift tests and simulator UI tests.
- Internal docs-link existence checks.

## Adoption policy

1. Use isolated PRs for P0, P1, P2 and P3 batches. Never vendor dozens of skills in one unreviewed commit.
2. Copy only skills needed for this codebase under .agents/skills; record upstream exact SHA, license and modifications in PROVENANCE.md. If a license does not permit the proposed use, link the skill instead.
3. Inspect SKILL.md and every bundled script before enabling tools, Bash, hooks, or network access. No curl-pipe-shell installer.
4. Pin downloaded tools with version + checksum or immutable action SHA; pin Docker images with digest for security jobs.
5. Do not put external scanner libraries into Android/iOS release dependencies. Test tools stay test-only, debug-only or CI-only.
6. PR gate: Kotlin/Swift compile, existing tests, critical static security checks, and deterministic regression suites. Scheduled/manual: real cluster, Toxiproxy, MobSF deep scan, fuzz, live URLs and physical devices.
7. No mandatory Anthracite daemon, cloud service, agent SDK, protocol adapter, hosted account, or production secrets.
8. Never mark tests green without actual final-commit job evidence; document unverified OpenSSH, live API, runtime and hardware behaviors.
9. Tracked tools are not accepted merely for a fashionable name. For each rank, add one negative test or actionable detection proving it addresses a real gap.
10. Re-run the full permanent CI matrix after each critical change and keep the docs/support-matrix.md truth claims up to date.

## Immediate fix order for open findings

A. Repair cancellation of the iOS SSH host-key scanner, with a silent-peer and race regression.
B. Verify and repair TerminalOutput pause/resume callback interleavings.
C. Enforce explicit refusal or true server-supported no-overwrite for SFTP upload; preserve disclosure if best effort only.
D. Define strict handling of SFTP recursive delete and Kubernetes rollback when identity-atomic operations are unavailable.
E. Run physical device and real OpenSSH, SFTP, tmux, Docker, Podman and Kubernetes acceptance tests.
F. Address managed deployment history, GitHub Load More and minor repository cleanup after safety work.
