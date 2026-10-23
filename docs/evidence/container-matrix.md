# Guided-provisioning container matrix — evidence

**Run:** [39881616212](https://github.com/anthracite-labs/Anthracite-Remote-Android/actions/runs/39881616212)
**Date:** 2026-10-23
**Commit under test:** `751e7e81f1c4754a31d863c76fec1a761340dd9d`
**Workflow:** `.github/workflows/_temp-anthracite-matrix.yml` (temporary; removed
before merge)
**Runner:** GitHub-hosted `ubuntu-24.04`, six parallel jobs, `contents: read`,
`timeout-minutes: 30`, image digests resolved inside each job.

Each job pulled one public image, recorded its resolved digest, then ran
`ci/container-install-test.sh` inside `docker run --rm`. The script performs, in
that order: distribution/manager discovery with the shipped probe, a real package
install with the shipped runner, native package verification, a deliberate
install of a nonexistent package, cancellation with a proven process identity,
a refusal case with an unproven identity, and removal of the installed package.

All six jobs concluded **success**; the per-job artifacts
(`matrix-<distro>-39881616212`) contain the full log.

| os id | image | resolved digest | install | verify | failure | cancel | refuse unproven | cleanup |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| debian | `debian:12` | `sha256:854dd8ab51d62866dbd8f5431d15ce77b12983a2a4a1e1fe43a2395dc7ef9f61` | yes | yes | yes | yes | yes | yes |
| ubuntu | `ubuntu:24.04` | `sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac` | yes | yes | yes | yes | yes | yes |
| fedora | `fedora:42` | `sha256:99e203b80b1c3d8f7e161ec10a68fd02b081ef83a3963553e513c82846b97814` | yes | yes | yes | yes | yes | yes |
| arch | `archlinux:latest` | `sha256:52dfbd5afb23a220d5ee92dafcb4c788d96c026ed04179890c23adef4c897fa0` | yes | yes | yes | yes | yes | yes |
| opensuse-tumbleweed | `opensuse/tumbleweed:latest` | `sha256:0c6f00841281acf55a8ef29e777fc2db40d0923289ba933d566542cff2cddc00` | yes | yes | yes | yes | yes | yes |
| alpine | `alpine:3.21` | `sha256:bbfcd601d7cd20d35bb0b8b866ad1a8a0d107ca3e1c20870c75c5ce2c1186d30` | yes | yes | yes | yes | yes | yes |

## Defects this run found (and that are now fixed)

1. **openSUSE Tumbleweed does not ship `awk`.** The probe aborted at disk
   discovery and the identity checks would have degraded to `unknown` (which the
   cancel path treats as a refusal). All host-kit field extraction now uses
   POSIX positional splitting, `sed`, `tr` and `cut`.
2. **The matrix fixture stripped the steps file's trailing newline** through
   command substitution. The runner's new grammar check refused the job, which is
   the intended behaviour: this was a fixture bug, not a runner bug.
3. **The cancellation fixture placed jobs outside `$HOME/.anthracite/jobs`**, so
   the reviewed cancel script refused the job path. The fixture now mirrors the
   real layout, and the refusal itself is a tested guard.

## What this evidence is *not*

* It is not a real-host run: no SSH, supervisor, logout or reboot behaviour was
  exercised, so none is claimed. The app still requires the acknowledgement for
  every host, including these distributions.
* It is not a permanently pinned input. Images were pulled by mutable tag and the
  digest recorded from that pull; the digests above identify exactly which image
  was tested.
* The temporary workflow was deleted before the branch was proposed for merge, as
  required. The run history is kept (never deleted).
