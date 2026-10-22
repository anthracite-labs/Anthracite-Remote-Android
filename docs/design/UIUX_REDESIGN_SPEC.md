# Anthracite Remote — UI/UX Redesign Specification

Status: approved by the product owner on 2026-10-22 through a structured decision
questionnaire. This document is the implementation contract for the redesign. It
covers the user-facing Android experience only.

## 1. Approved design decisions (verbatim owner selections)

| # | Topic | Decision |
|---|---|---|
| Q1 | Visual direction | **B — Developer Dark**: compact, technical, information-rich; terminal-inflected details (monospace for identity, fingerprints, logs, ports). |
| Q2 | Theme behavior | **A — System-aware light and dark**: both palettes fully crafted; follows the Android system setting. Dark is the signature rendering of the design language. |
| Q3 | Accent color & identity | **B — Coal Green**: keep the existing deep green `#3E7C59` family as the brand accent on graphite/anthracite neutrals. Launcher icon unchanged (continuity). |
| Q4 | Navigation structure | **A — Focused Home + Management screen**: Home carries identity, status, and the one primary action; everything else behind a quiet Manage affordance. Single activity, predictable back stack. |
| Q5 | First-time onboarding | **C — Hybrid**: Welcome intro → one focused setup form (advanced options collapsed) → dedicated full-screen "Verify server identity" moment on first connect (fingerprint large, monospace, copyable) → then the Install-vs-Connect choice on Home. |
| Q6 | Primary action / returning user | **A — Adaptive single CTA**: Home shows exactly one dominant action inferred from state (Set up → Install & Launch OpenCode → Open OpenCode). Quiet secondary for the alternate journey. Last-used mode remembered. "Connect existing" never triggers installation. |
| Q7 | Connection status | **B — Status card on Home**: server identity, current phase, expandable technical detail (fingerprint, tunnel ports, mode). WebUI toolbar keeps only a minimal status dot. |
| Q8 | Deployment progress & logs | **B — Live honest phases**: phase checklist driven by real events, streaming the installer's own `step:` markers through a small additive output listener on `SshClient.exec` (existing return path, redaction, and security untouched); expandable technical log. No invented percentages; indeterminate progress only. |
| Q9 | WebView toolbar | **A — Compact contextual**: slim toolbar with status dot + "OpenCode" title, back (web history) / close, Refresh, overflow (Reconnect, Manage, Disconnect). System Back walks web history, then exits to Home. |
| Q10 | Advanced management | **A — Dedicated Management screen**: grouped Service (Check status, Restart, Update), Connection (Disconnect, Edit server), Danger zone (Uninstall managed OpenCode, Forget connection) with confirmations. Session log available here. |
| Q11 | Motion | **A — Restrained native motion**: 200–300 ms cross-fades, subtle state pulses; fully honors the system "Remove animations" setting (animator duration scale 0 → instant swaps). |
| Q12 | Large screens & density | **A — Responsive single column**: content column capped (~600 dp) and centered on tablets/foldables/landscape via width-qualified padding; the OpenCode WebUI is always full-bleed. |

Composition note: Q1=B (Developer Dark aesthetic) + Q2=A (system light/dark)
means a technical, information-honest design language rendered in **both** a
signature dark theme and an equally crafted light theme. Q3=B keeps the existing
launcher icon and `#3E7C59` green identity.

## 2. Design tokens and theme behavior

- Base theme: `Theme.Material3.DayNight.NoActionBar` via the official
  `com.google.android.material` library (Google's native Android widget set —
  not Compose, not a navigation framework, not a decorative third-party kit).
  `MainActivity` becomes an `AppCompatActivity` (the supported host for
  Material 3 widgets and dialogs). All existing `ComponentActivity` APIs
  (`enableEdgeToEdge`, back dispatcher, activity results, `lifecycleScope`)
  are preserved because `AppCompatActivity` extends `ComponentActivity`.
- `uiMode` is removed from `android:configChanges` so a system theme switch
  recreates the activity and re-resolves DayNight resources. Transient session
  state (tunnel, WebView) does not survive that recreation; Home shows an
  honest disconnected status and one tap restores the tunnel. Rotation and
  density changes keep the existing non-recreating behavior.
- Color roles (semantic names in `values/colors.xml`, dark overrides in
  `values-night/colors.xml`):
  - Dark (signature): background `#101413`, surface `#171C1A`, elevated
    surface `#1E2422`, outline `#2E3634`, on-surface `#E4E9E5`, secondary
    text `#A7B2AC`, primary (brightened coal green) `#7DC89B`.
  - Light: background `#F4F6F5` (existing), surface `#FFFFFF`, elevated
    `#EDF0EE`, outline `#C6CFCA`, on-surface `#1F2933` (existing slate),
    secondary text `#52606D` (existing), primary `#3E7C59` (existing brand).
  - Semantic status: success/connected = brand green family; warning and
    connecting = amber (`#B26A00` light / `#F2B84C` dark); error = M3 red
    family (`#B3261E` light / `#F2B8B5` dark); idle/unknown = neutral grey.
  - Every text/background pair targets WCAG AA (≥ 4.5:1 body text, ≥ 3:1
    large text and UI components).
- Typography: system sans for prose; **monospace** for technical identity
  (`user@host:port`), fingerprints, ports, tunnel mappings, and logs — the
  Developer Dark signature. Scale (sp, font-scale friendly): display 28,
  headline 22, title 18, body 15, label 13, mono 13, mono-small 12.
- Spacing scale (dimens): 4 / 8 / 12 / 16 / 24 / 32 / 48. Corner radii:
  buttons 8 dp (technical, not pill), cards 12 dp, dialogs 16 dp.
- Touch targets ≥ 48 dp everywhere (explicit `minHeight` on buttons;
  icon buttons 48×48 dp).
- No hard-coded colors or sizes in layouts: all references go through
  `@color/`, `@dimen/`, `@style/`.

## 3. Screen inventory (single activity, view-swap; no navigation framework)

| Screen | Purpose |
|---|---|
| Welcome | First launch only. Explains the product honestly; "Get started" → Setup. |
| Setup | Focused form: host, username, auth method toggle, contextual credential fields; collapsed Advanced (SSH port, existing OpenCode port, key passphrase); Continue saves and verifies identity. |
| Verify | Full-screen host-key trust moment. First-use: neutral/brand treatment. Changed key: danger treatment with saved vs presented fingerprints. Approve / refuse. |
| Home | Returning-user hub: identity header, status card (Q7=B), adaptive primary CTA (Q6=A), quiet secondary for the alternate journey, error banner region. |
| Prepare install | WebUI password creation with live requirement checks + truthful deployment summary. New installations only. |
| Progress | Phase checklist for every remote operation (install, open, restart, update, uninstall, status), live technical details expander, failure state with recovery actions, Stop-confirmation on back. |
| Web | The unmodified OpenCode WebUI under a compact toolbar (Q9=A) with status dot and warning banner. |
| Manage | Grouped operations (Q10=A): Service, Connection, Danger zone; session log card (collapsed by default). |

Dialogs that remain dialogs (contextual to a running flow): HTTP Basic sign-in
(restyled with text inputs), destructive-action confirmations, stop-operation
confirmation.

## 4. Navigation map and back behavior

```
(first launch)  Welcome → Setup → [job: verify identity] → Verify → Home
(returning)     Home ⇄ Manage        Home ⇄ Setup (edit)
Home CTA ─────→ [job] → (Verify?) → Prepare install? → Progress → Web
Web: Back → web history → Home.  Manage/Setup/Verify/Prepare: Back → Home
(Setup with no saved connection: Back → task back).
Progress: Back → "Stop operation?" confirm → cancel job, disconnect, Home
with an interrupted-operation banner.
Home/Welcome: Back → task back.
```

The pure decision logic (`firstScreen`, `backFrom`, `animationsEnabled`) lives
in `NavigationPolicy` (JVM-testable). The activity applies it.

## 5. Flows

### 5.1 First-run (Journey A)

Welcome → Setup (Continue ⇒ save via the existing `saveForm()` path, then a
job runs `ensureSsh()`; unknown host key suspends into the Verify screen;
approval keeps the exact existing security order: invalidate tunnel
authentication context, persist host key, reconnect) → Home with CTA
**Install & Launch OpenCode** and quiet secondary **Connect to an existing
installation instead** → install job: Prepare install (WebUI password, sent on
SSH stdin, never stored on the device) → Progress streams the real installer
phases → probe (Basic-401 requirement preserved) → tunnel → Web.

### 5.2 Existing installation

Home secondary (or Manage) → job: `ensureSsh()` → Progress
(Connect → Probe → Tunnel → Launch) → Web. This path never uploads or runs
the installer and never mutates the remote service. The existing-install port
comes from the saved profile (Advanced option in Setup, default 4096).

### 5.3 Returning user (Journey B)

Cold start with a saved connection lands on Home. CTA **Open OpenCode**
reconnects using the remembered mode: managed install → port 4096; existing →
saved remote port. Mode (`managed`/`existing`) is persisted as non-secret UI
state alongside the connection profile and is erased by Forget. If a tunnel is
already live, the CTA opens the Web screen immediately. Automatic reconnect
after network loss keeps the existing `ReconnectGate` semantics; Home and the
Web toolbar show a reconnecting state meanwhile.

## 6. Deployment progress UX (Q8=B)

Truthfulness rules:

- Phase checklists come only from real events: activity-side boundaries
  (Connect SSH, Upload installer, Probe service, Open tunnel, Launch WebUI)
  and the installer's own output lines (`step: …`, `ok: …`, `error: …`,
  `limitation: …`). No percentages, no invented events.
- `SshClient.exec` gains an optional `onOutput: ((ByteArray) -> Unit)?`
  parameter (default null ⇒ byte-for-byte identical behavior). The listener is
  observational, invoked on the IO thread, and exceptions from it cannot break
  the channel loop. Redaction stays a UI-layer responsibility: the activity
  redacts the install secret from every streamed line before display or log,
  line-wise, in addition to the existing final-text redaction semantics.
- `LineStream` converts byte chunks into complete lines (LF split, CR
  trimmed, UTF-8 decode per line, remainder flushed at end).
- `DeployProgress` maps lines to phases per operation:
  - INSTALL: Connect SSH → Upload installer → Preflight → Download → Verify
    checksum → Unpack → Verify binary → Activate → Service ready → Probe
    service → Open tunnel → Launch WebUI.
    (`step: preflight-*` all fold into Preflight with the raw marker as
    monospace detail; `download`, `verify-hash`, `unpack`, `verify-binary`,
    `activate`, `ready` map 1:1.)
  - OPEN (managed or existing): Connect SSH → Probe service → Open tunnel →
    Launch WebUI.
  - RESTART / UPDATE / UNINSTALL / STATUS: Connect SSH → Upload installer →
    (Update also: Preflight, Download, Verify binary, Activate, Service
    ready) → Run command; Restart reopens the tunnel when one was live.
- Unknown lines never create phases; they go to the technical log.
- The technical log expander shows the sanitized session log live (monospace,
  auto-scrolled, polite live region). Failure marks the active phase red with
  the installer's own error text and offers: View technical details, Try
  again (safe to re-run — the installer is idempotent by design), Back to
  Home. `limitation:` lines mentioning `enable-linger` surface a persistence
  note on completion (the exact administrator command comes from the
  installer output, unmodified).

## 7. WebView container UX (Q9=A)

Compact `MaterialToolbar`: navigation icon = back arrow while
`webView.canGoBack()`, otherwise close (→ Home); status dot (connected /
reconnecting / error); title "OpenCode"; actions: Refresh; overflow menu
(Reconnect, Manage, Disconnect). The existing warning strip is restyled as an
inline banner and keeps its polite live region (network loss, renderer
crashes, tunnel refusals). **All WebView security plumbing is preserved
verbatim**: loopback-only origin policy, Basic-auth challenge handling and
release rules, cookie/credential invalidation at trust boundaries, SAF file
picker and download validation, renderer-crash recovery, font-scale
`textZoom`, external `https:` links to the system browser. The OpenCode WebUI
content itself is untouched.

## 8. Management UX (Q10=A)

- **Service**: Check status (runs the existing read-only `status` command),
  Restart, Update. Each runs through the Progress screen with streamed
  output; results (including "No approved update" and the update rollback
  message) are presented verbatim in the log with a human headline.
- **Connection**: Disconnect (existing semantics: cancels jobs, stops
  auto-reconnect, clears the accepted identity), Edit server (Setup
  pre-filled; profile-change invalidation preserved).
- **Danger zone** (visually separated, error-tinted): Uninstall managed
  OpenCode and Forget connection, each behind the existing confirmation
  dialogs with their preserved copy (what is removed, what is kept).
- **Session log** card (collapsed by default) keeps diagnostics available
  after operations complete; monospace, selectable, capped by the existing
  60k-char rolling buffer.

## 9. Empty, loading, error, and recovery states

| Situation | Presentation | Recovery |
|---|---|---|
| No connection saved | Home is never shown; Welcome/Setup instead. | — |
| Invalid configuration | Inline field errors in Setup (no toast-only feedback). | Fix field, Continue. |
| SSH authentication failed | Error banner (Home or Setup): check username/password/key. | Edit credentials / Try again. |
| Unknown host key | Verify screen (flow, not error). | Approve or refuse. |
| Changed host key | Verify screen, danger treatment: saved vs presented fingerprints; refusal is the default path. | Out-of-band check → Approve new fingerprint, or refuse. |
| Unreachable server | Error banner with the technical reason (expandable). | Try again / Edit server. |
| Unsupported server (preflight fail) | Progress failure at Preflight with the installer's own human-readable error. | Fix server side → Try again; details in log. |
| Installation failure | Progress failure at the real failing phase + log. | Try again (idempotent), Back to Home. |
| Service unavailable / not Basic-401 | Progress failure at Probe with the preserved message ("must challenge with HTTP Basic", received status). | Check the service → Try again / Manage. |
| Tunnel disconnected / network loss | Web banner + toolbar dot = reconnecting; existing debounced auto-reconnect. | Automatic; manual Reconnect in overflow. |
| WebUI authentication failure | Basic-auth dialog re-prompts (existing one-shot saved-password semantics); dialog explains credentials are not stored. | Re-enter password. |
| Interrupted operation | Stop-confirmation on back; afterwards an interrupted banner on Home ("nothing was undone; safe to retry"). | Try again. |
| Renderer crash loop | Existing guard: repeated crashes land on Home with the preserved status message. | Reopen / Reconnect. |
| Keystore/credential read failure | Preserved message + requirement to re-enter the credential. | Setup with re-entered secret. |

Loading states: buttons disable during their job (existing single-job mutex
preserved); Progress carries an indeterminate bar; Home status chip shows
Connecting/Reconnecting with an amber dot (pulse animation only when system
animations are enabled).

## 10. Accessibility requirements

- TalkBack: every icon-only control has a content description; status chip
  and phase rows expose state in their labels ("Done", "In progress",
  "Failed"); polite live regions on status/log/banner updates.
- Contrast: WCAG AA in both themes (tokens chosen per §2).
- Touch targets ≥ 48 dp; visible focus handling from Material 3 defaults.
- Android font scaling: all type in sp; WebView `textZoom` follows
  `fontScale` (preserved); layouts scroll rather than clip at large scales.
- Keyboard/switch access: logical focus order (XML order), no focus traps;
  dialogs focus their first field.
- Reduced motion: all custom animation gated on animator duration scale > 0
  (`NavigationPolicy.animationsEnabled`, unit-tested); indeterminate progress
  indicators exempt (they convey state).

## 11. Responsive layout requirements

- Phones (portrait/landscape): single column, 20 dp side padding.
- `w840dp` and up: side padding grows so the content column caps around
  600 dp and centers (`values-w840dp` / `values-w1200dp` dimens).
- Web screen: always full-bleed, toolbar compact.
- Edge-to-edge insets preserved on the root container (system bars, cutout,
  IME), exactly as today.

## 12. Testing and acceptance criteria

Verification environment: this workstation has no Android SDK/JDK access
(sandbox network allowlist), so the authoritative build/test gate is the
repository's GitHub Actions CI on the pull request (unit-test shards, Android
lint, dependency resolution, `assembleDebug` + APK inspection, shell installer
suite, CodeQL). No emulator/device is available: **no visual/on-device testing
is claimed**; the PR lists this as an unverified gate.

Local pre-push verification: XML well-formedness, resource cross-reference
audit (every `@string/@color/@dimen/@drawable/@style` and `R.*` reference
resolves), `bash -n` + the shell installer suite (installer untouched), and
diff review against the preservation checklist.

New JVM unit tests (pure Kotlin, no Android imports; added to the CI matrix):

- `NavigationPolicyTest` — first-screen resolution, back targets for every
  screen, animation-enable policy.
- `HomeStateTest` — adaptive CTA and secondary per (connection, mode, tunnel,
  job) state; status-chip resolution.
- `DeployProgressTest` — per-operation phase lists, installer line mapping
  (step/ok/error/limitation), unknown-line passthrough, failure marking,
  linger-note detection, `LineStream` chunk/partial/CRLF/flush behavior.
- `SshSessionTest` gains an integration test proving the additive `exec`
  output listener streams all lines against the in-process MINA server while
  the returned `ExecResult` stays identical.

Acceptance criteria (definition of done):

1. First run is guided: Welcome → focused Setup → full-screen Verify →
   explicit Install-vs-Connect choice.
2. Home shows exactly one dominant CTA per state; returning users get
   **Open OpenCode** and reach the unmodified WebUI in one tap when a tunnel
   is live.
3. Existing-installation journey never uploads or executes the installer.
4. Progress shows only real phases; the technical log remains available;
   secrets are redacted from every displayed line.
5. Management is grouped with a distinct danger zone; destructive actions
   keep their confirmations and copy semantics.
6. All preserved behaviors (host-key TOFU, probe-before-forward, credential
   handling/zeroing, reconnect gating, WebView policies, downloads, pickers,
   uninstall/forget semantics) are unchanged — verified by diff review and
   the existing test suite.
7. Light/dark themes both render all screens; system theme switch works via
   recreation.
8. CI green: existing + new unit tests, lint, APK build/inspection, shell
   suite, CodeQL.

## 13. Out of scope (hard boundaries)

- `install-opencode.sh`, pinned artifacts, checksums, rollback, uninstall
  preservation: untouched.
- SSH authentication behavior, host-key policy, trust-on-first-use rules,
  `StrictHostKeyChecking=yes`, tunnel isolation, HTTP-Basic-401 requirement,
  credential encryption/storage/zeroing, redaction: untouched except the
  explicitly additive `exec` output listener (Q8=B), which observes only.
- The OpenCode WebUI content: untouched; no custom chat UI.
- No Compose, no navigation framework, no web frameworks, no decorative UI
  dependencies. The single new dependency is
  `com.google.android.material:material:1.13.0` (+ its AppCompat lineage),
  announced during the decision stage and not vetoed.
- No backend, no analytics, no new permissions, no unrelated features.
- Launcher icon unchanged (Q3=B continuity).

## 14. Implementation tickets (ordered, with dependencies)

1. **T1 Design system** — dependency + theme/color/dimen/style/string
   resources, drawables, manifest `uiMode` fix. (Blocks: all)
2. **T2 Pure state logic + tests** — `NavigationPolicy`, `HomeState`,
   `DeployProgress`, `LineStream`; CI matrix update. (Blocked by: T1 naming;
   blocks: T4–T7)
3. **T3 Exec streaming listener + integration test** — additive `onOutput`.
   (Blocked by: none; blocks: T5)
4. **T4 Onboarding screens** — Welcome, Setup, Verify + wiring. (Blocked by:
   T1, T2)
5. **T5 Deployment screens** — Prepare install, Progress + install/open/
   managed wiring. (Blocked by: T2, T3, T4)
6. **T6 Home + returning user** — status card, adaptive CTA, mode memory in
   `ConnectionStore`. (Blocked by: T2, T4)
7. **T7 Web container + Management** — toolbar, banner, auth dialog restyle;
   Manage groups, danger zone, session log. (Blocked by: T4–T6)
8. **T8 Accessibility & responsive pass** — descriptions, live regions,
   focus, width qualifiers, target sizes. (Blocked by: T7)
9. **T9 Validation & delivery** — static audits, shell suite, push, PR, CI
   iteration, two-axis self-review, notices/README updates. (Blocked by: T8)
