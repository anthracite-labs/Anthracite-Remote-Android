# Real-host integration plan

Mocks, in-process servers, simulators and disposable containers prove the
command grammar and the parsers. They cannot prove behaviour on a complete
real deployment environment. This plan is the checklist for that pass; a row
counts only with the host, OS, date and run that produced it.

Status: **not run**. The automated subset that can run without a real host is
`app/src/test/shell/service-smoke-test.sh` (isolated tmux round-trip plus
client versions, wired as `service_integration` in `android-ci.yml`).

## 1. OpenSSH + SFTP (real `sshd` / `sftp-server`)

On a disposable VM or container with `openssh-server`:

| # | Step | Expected |
| --- | --- | --- |
| 1 | `ssh -p <port> <user>@<host> true; echo $?` | exit 0; host key shown for first-use approval |
| 2 | Upload a 1 MiB file with overwrite disabled, twice | first succeeds, second refused, original intact |
| 3 | Upload the same path from two clients at once | survivor is one complete file, no `*.anthracite.tmp` left |
| 4 | Recursive delete of a tree containing a symlink to `/etc` | link removed, target intact |
| 5 | `tmux new -d -s e2e`, detach, `tmux attach`, kill | presence reports attach/detach through the app |
| 6 | Logout while a tmux session runs, log back in | session still attached-listed (tmux), PTY tab re-attaches |
| 7 | Reboot the host, reconnect | host unreachable DURING is reported, not mistaken for empty; after boot the managed record reconciles as GONE or MATCH, never as another object |

## 2. Docker / Podman (real daemon)

| # | Step | Expected |
| --- | --- | --- |
| 1 | Create the app's container, read back id + label | id recorded, label `anthracite.managed=1` |
| 2 | Delete the container by name after `docker rename` to a new id | refused (id mismatch); the replacement is untouched |
| 3 | `docker logs --tail`, start/stop/restart | outputs bounded, actions target the listed id |
| 4 | Podman: same create/remove cycle under the Podman runtime | same guarantees |

## 3. Kubernetes (real cluster, disposable namespace)

| # | Step | Expected |
| --- | --- | --- |
| 1 | Apply the generated manifest, read back UID + label | UID recorded; `AlreadyExists` on re-apply leaves the object untouched |
| 2 | `rollout undo` with the object replaced mid-flight | reported as replaced (exit 66), never as success |
| 3 | Delete with the object replaced mid-flight | API server refuses on the UID precondition; replacement survives |
| 4 | RBAC denial (`can-i` = no) | refused before any mutation |

## 4. Logout, reboot, reconnect behaviour (app level)

| # | Step | Expected |
| --- | --- | --- |
| 1 | Kill the SSH session server-side while browsing files | error surfaces, no stale listing presented as current |
| 2 | Rotate the host key, reconnect | change refused until explicitly re-approved |
| 3 | Process death with managed deployments recorded | each record reconciled by its own identity on next launch |

## Results

| Date | Host / image | OS | Prover | Outcome |
| --- | --- | --- | --- | --- |
| — | — | — | — | not run yet |
