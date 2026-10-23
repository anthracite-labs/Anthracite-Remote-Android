# Anthracite engineering backlog — Ponytail pass

**Scope:** repository-local skills, CI and tests; not ChatGPT plugins or mobile runtime dependencies.  
**Reviewed:** 2026-10-23. Source backlog: 46 proposed items in PR #9. This revision replaces the shopping list with work that actually closes observed gaps.  
**Latest checked main:** \`2f2d6a194f962297b67a68c598f6eac186d922e9\`. PR #9 is documentation-only; **none of the proposed installations below is done by this file**.

## Rule

1. **Fix the bug before installing a tool.** One reproducing regression beats a dozen scanners that cannot detect the fault.
2. **Already covered? Don't install it.** First inspect existing CodeQL (Kotlin and Actions), Android Lint, JUnit, Swift XCTest/UI tests, ShellCheck, actionlint, zizmor, Dependabot, dependency review, Scorecard, CycloneDX, MobSF APK baseline, and the security/CI scripts.
3. Prefer OS commands, native XCTest/JUnit, Docker on disposable runners, Swift/Gradle tooling, and existing CI before another framework.
4. Add **one** repository-local engineering skill, not three competing generic agent frameworks. Pin and review any third-party skills before copying; none is required now.
5. Every new tool must prove a distinct bug or coverage gap with a deterministic test or actionable finding. Pin its version, action SHA or image digest. Never introduce production credentials or new app-runtime dependencies.

## Do now — 8 items in priority order

| Rank | Work | Repo path / minimal implementation | Done when |
|---:|---|---|---|
| **1** | Fix iOS SSH host-key scan cancellation | \`ios/Sources/AnthraciteKit/SSHTransport.swift\` + existing iOS tests. Make a silent peer's scan cancellable: key received, timeout, disconnect and caller cancellation resolve once. **No new dependency.** | A red-then-green deterministic test proves prompt cancellation; existing host-key pinning checks pass. |
| **2** | Fix iOS terminal flow-control event ordering | \`ios/Sources/AnthraciteKit/TerminalOutput.swift\` and \`TerminalOutputTests.swift\`. Serialize pause/resume state with its NIO event-loop side effect; no extra stream library. | Forced producer/consumer interleaving cannot leave autoRead stale; byte ordering and no-loss tests pass. |
| **3** | Set one repository engineering contract and one focused skill | Add concise \`AGENTS.md\` and **only** \`.agents/skills/anthracite-engineering/SKILL.md\`. Modes: review/audit/fix/simplify. Require fixed-base diff, file:line findings, trust boundaries, minimal red-green patch, no blind CI claims. The agent's global rules stay in AGENTS; procedure goes in SKILL; **no duplication**. | One runnable example review/fix demonstrably follows the contract. No Trail of Bits/Sentry/Matt/Superpowers mass import. |
| **4** | Close the **Swift CodeQL gap** | Extend existing \`.github/workflows/codeql.yml\` with a macOS Swift job using the repo's pinned Xcode/Swift dependencies. Do not create a parallel security workflow if a matrix/job here suffices. Source: https://github.com/github/codeql-action | CI actually analyzes iOS Swift code and uploads SARIF. Preserve existing Java/Kotlin and Actions checks. |
| **5** | Run a real, disposable OpenSSH/SFTP end-to-end smoke | Add \`ci/integration/ssh/\` scripts and a bounded manual/scheduled runner. Use native \`sshd\` / \`sftp-server\` + existing clients/tests; Docker only if needed for isolation. **Skip Testcontainers until shell orchestration becomes painful.** Source: https://github.com/openssh/openssh-portable | Exercise key pinning, cancellation, PTY, tmux attach/detach, SFTP transfer, refusal paths, reconnect. No real user host. |
| **6** | Verify dangerous Kubernetes operations against an actual API server | \`ci/integration/kubernetes/\` with one disposable **kind** cluster and existing \`kubectl\` command generation. Source: https://github.com/kubernetes-sigs/kind | Test create-only, replaced UID, server-side UID-delete precondition, RBAC refusal and rollback's documented non-atomicity. Refuse strict-identity rollback if the guarantee is unavailable; do not fake an atomic result. |
| **7** | Harden the MobSF job that **already exists** | Modify the existing Android CI MobSF step: replace \`opensecurity/mobile-security-framework-mobsf:latest\` with reviewed digest, keep report artifacts and decide explicitly which findings block release. Source: https://github.com/MobSF/Mobile-Security-Framework-MobSF | Reproducible scan and an inspectable report. No second MobSF install or mobile source scanner by default. |
| **8** | Release acceptance on actual devices/hosts | Add \`docs/evidence/release-acceptance.md\`; record physical Android+iOS, OpenSSH, actual container daemon, Kubernetes, logout/reboot/tmux durability, accessibility/keyboard and process death. Use existing device/unit tests as prerequisites. | Every capability is dated **PASS / FAIL / NOT TESTED** with environment and run/evidence. Never call simulator tests a physical-device pass. |

### Code-safety decisions required alongside 5–8 (not tool purchases)

- Android SFTP no-overwrite: the existing transport cannot promise an atomic no-replace rename. Fail closed or label as explicitly best-effort; do not advertise a guarantee.
- Recursive SFTP deletion: \`lstat\` cannot eliminate path-replacement races. Restrict unsafe behavior, or provide a proven stronger server-side operation; document what remains.
- Kubernetes rollback: a post-write UID check detects an identity change but does not prevent a wrong-object mutation. Keep the limitation and strict-mode refusal when needed.
- Persistent deployments: one record per host discards older creation history. Expand storage only if managing multiple simultaneous resources is a requested user workflow.

## Add **only after a measured failure** — 4 gates, not standing commitments

| Order | Candidate | Trigger / simplest acceptable integration |
|---:|---|---|
| **9** | [Semgrep](https://github.com/semgrep/semgrep) | Add **one** targeted local rule when it reproducibly catches a bug variant missing from Swift/Kotlin CodeQL + existing lint; no giant generic ruleset. |
| **10** | [Toxiproxy](https://github.com/Shopify/toxiproxy) | Only when the existing scripted peer/NIO tests and native socket-close tests cannot reliably reproduce a transport/network fault. |
| **11** | [Gitleaks](https://github.com/gitleaks/gitleaks) | Only when a seeded secret fixture proves the current custom secret checks / existing GitHub security coverage misses a relevant credential pattern. |
| **12** | [detekt](https://github.com/detekt/detekt) **or** [SwiftLint](https://github.com/realm/SwiftLint) | Only if code review finds repeated, actionable complexity defects missing from Android Lint, compiler warnings and Swift's native diagnostics. Adopt **one** first; baseline existing findings. |

## Explicitly cut from the original 46-item plan

| Ponytail verdict | Items removed from automatic adoption | Reason |
|---|---|---|
| **delete:** | Mass-vendored Trail of Bits, Sentry, Matt, Superpowers skills; duplicate review, secret, architecture, bug-fix and skill-scanning skills | Multiple overlapping instruction sets cost maintenance and can conflict. Keep AGENTS.md + one repo skill; link to upstream knowledge when needed. |
| **native:** | Testcontainers (initially), Maestro, custom mobile test framework, extra Python helper stack | Existing shell, JUnit, XCTest, simulator and native tools cover the first working test. Add frameworks only after a concrete limitation. |
| **yagni:** | Trivy + OSV-Scanner simultaneously, mobsfscan on top of Semgrep/MobSF, new dependency-security scanners | Existing dependency review, Dependabot, SBOM, CodeQL and MobSF cover baseline. Require evidence of a blind spot before new scanners. |
| **native:** | ktlint + SwiftFormat + shfmt + Ruff as a mandatory rollout | Existing Android Lint, IDE/Swift tooling, shell tests and \`python -m py_compile\` cover current baseline. Formatting-only changes do not reduce production risk. |
| **yagni:** | Jazzer, Kover, LeakCanary, whole-repo golden fixture framework | Add only if real crash input, coverage blind spot, memory leak or demonstrated Android/iOS rule drift justifies the new apparatus. |
| **delete:** | lychee/reviewdog/Bats/additional CI wrappers by default | Current offline link check, GitHub annotations, and executable shell suites are adequate until a specific recurring failure occurs. |

**Ponytail accounting:** 46 auto-install candidates → **8 ordered work items + 4 evidence-gated options**; **34 entries removed from the rollout**. The remaining candidates are not vendor/import instructions.

## Delivery guardrails

- Work in small PRs. Priority #1 and #2 are bug fixes; #3 is agent instructions; #4–#7 are CI/integration changes. Do not combine them into one mass tooling PR.
- Copy no third-party skill into \`.agents/skills/\` unless its license, exact upstream revision and bundled scripts have been reviewed and documented. Local SKILL.md is original project guidance.
- CI uses pinning, minimal tokens, finite timeouts, disposable services and no production host or GitHub write calls.
- Run affected red-green tests and permanent final-head CI before claiming any item done. \`docs/support-matrix.md\` records real evidence and unknowns.
- Never replace an actual security guarantee with a warning; when the protocol cannot enforce safety, refuse strict operations.
