# ADR 0001 — An SSH workspace beside the OpenCode journey

Status: accepted (2026-10-22)
Scope: `dev.anthracite.remote` — terminal, files, host Git, GitHub, deployments, provisioning

## Context

The app was a single-activity OpenCode client: it connects over SSH, installs or
attaches to OpenCode, and opens a tunnel to the WebUI. That journey works and is
the reason people install the app.

Users repeatedly asked for the things an SSH client is for: a real terminal,
detaching a long job with `tmux`, editing files over SFTP, checking `git status`,
and installing a missing tool. The tempting shortcut is to bolt a "server
management" screen onto the existing state machine. That would put the working
OpenCode journey at risk for every unrelated bug, and it would encourage the
worst habit in this product category: pretending the phone can do more than it
can.

## Decision

1. **The OpenCode journey is untouched.** `MainActivity`, `ConnectionStore`,
   `SshClient`'s existing surface and the installer stay behaviour-compatible.
   `ConnectionStore` is only ever *read* by the new code, for a one-time import.
2. **New surfaces are separate activities**, registered in the manifest with
   `exported="false"` and reachable from *Manage → Host workspace*. Each owns one
   job and one screen: `HostsActivity`, `WorkspaceActivity`, `FilesActivity`,
   `GitActivity`, `GitHubActivity`, `DeployActivity`, `BootstrapActivity`, and
   `TerminalActivity`.
3. **A second, process-wide SSH connection** (`Workspace.ssh`) serves the new
   surfaces, so a terminal tab cannot disturb an OpenCode tunnel. It uses the
   same host-key rules: first-use approval, refusal of a changed key, no
   automatic trust.
4. **Multiple hosts, fail-closed storage.** `HostStore` keeps up to 50 profiles,
   seals credentials with an Android Keystore key, writes the credential blob
   *before* committing the profile list, and restores the previous blob if the
   commit throws. An unreadable list throws; it is never silently dropped.
5. **The terminal is a full VT emulator, not a text view.** JSch opens a real PTY
   (`pty-req` + `shell`, or `exec` for `tmux attach-session`), the emulator parses
   bytes off the UI thread, and the renderer draws a cell grid on a `Canvas`.
   Window changes send an RFC 4254 `window-change`, so full-screen programs
   re-layout instead of tearing.
6. **Nothing opens by itself.** OSC 52 clipboard writes are refused and reported;
   OSC 8 hyperlinks are accepted only as `https` and are never opened — the UI
   names the link instead. DCS/PM/APC strings are consumed and discarded.
7. **Terminal input is encoded in pure Kotlin** (`TerminalKeys`) so arrow keys,
   function keys, modifiers, bracketed paste and mouse reports are unit-tested on
   the JVM. Paste strips C0/C1 controls so terminal output cannot be replayed as
   commands.
8. **Detach is not stop.** Closing a tab closes the SSH channel. For a `tmux`
   attachment the host session keeps running; "Stop session" is a separate,
   confirmed action that runs `tmux kill-session` and then *verifies* with
   `has-session`, reporting failure rather than assuming success.
9. **Commands are built from validated tokens.** `PosixShell` is the single
   quoting/validation gate. Free-form values are single-quoted with the POSIX
   `'\''` idiom after control characters are rejected; structured values (refs,
   commit-ishes, container ids, Kubernetes names, package names) are validated
   before use. No `eval`, no interpolated user text.
10. **Host provisioning runs shipped, reviewed scripts.** The phone uploads
    `assets/host/anthracite-provision-run.sh`, a plan header and a list of
    native package names. The runner re-verifies the plan digest and the
    distribution, refuses to prompt for `sudo`, holds a per-user lock it can
    reclaim only when the owning process is gone, and writes state the phone
    re-reads. A recorded `RUNNING` with no matching process is reported as
    `INTERRUPTED`, never as success.
11. **GitHub is separate from host Git.** Host Git uses the server's own
    credentials. GitHub actions use a token the user authorizes on the phone,
    sealed with the Keystore, validated before storage, dropped when GitHub
    answers 401. Merges send the head SHA the user reviewed, so a force-push
    after loading fails the merge. Revocation is documented, not faked: GitHub's
    revocation endpoint needs a client secret a mobile binary must not contain.
12. **Deployments are read-mostly and say what they cannot do.** The screens
    list containers and pods, show logs, and can start/stop/restart a
    host-reported container or delete one named pod. They cannot create
    containers, forward the Docker socket, run privileged work, or elevate. The
    Docker-socket warning is shown next to the data, not buried.
13. **Durability is reported per event.** A dropped channel, a logout and a
    reboot are three different questions. `tmux` answers the first; linger and
    `KillUserProcesses` decide the second; nothing survives the third. No "your
    session is safe" claim is made anywhere in the UI.
14. **No local model and no cloud.** There is no bundled LLM and no Anthracite
    service. A phone-sized model would either be worse than the agent the user
    already runs on their host, or cost the privacy that is the product's point.

## Consequences

* Two SSH connections can exist at once. That is deliberate: terminal traffic
  cannot starve the WebUI tunnel, and either surface can be closed without
  touching the other.
* Terminal tabs do not survive process death. A `tmux` session on the host does,
  and the UI names that difference in the tab title, the detach dialog and the
  status line.
* Guidance that needs evidence is marked unverified rather than guessed:
  `SupportTier` never promotes an unverified distribution, and a container can
  only ever reach `VERIFIED_CONTAINER`.
* The new code is only as good as the tests. The JVM suite covers parsers,
  command grammars, storage and the emulator; the host-kit suite covers the
  runner against a fake package manager; `SshSessionTest` covers JSch against an
  in-process MINA SSHD server. Device-only behaviour (IME, rendering, touch) is
  documented as untested on hardware and needs a device pass.
