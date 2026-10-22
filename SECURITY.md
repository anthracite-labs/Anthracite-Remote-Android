# Security policy

## Supported versions

Only the latest version on `main` is actively maintained. Do not assume an old APK is supported.

## Reporting a vulnerability

Please do **not** open a public issue containing an exploit, credential, private key,
or undisclosed vulnerability. Use GitHub's private vulnerability reporting option
under the repository Security tab when it is enabled. If unavailable, contact
a repository maintainer privately before disclosing details.

Provide affected versions, impact, steps to reproduce, and a suggested mitigation
when possible. Do not test against another person's machine or OpenCode instance
without explicit authorization.

## Scope

The Android app holds SSH connection material, authenticates remote host keys,
proxies the OpenCode WebUI through a device-loopback SSH forward, and runs a
bundled Linux installer on user-owned machines. Report issues in these boundaries
and in the GitHub build and release pipeline.

Security scanners provide findings, not a certification of OWASP MASVS or NIST SSDF
compliance. Android instrumented tests in CI are currently smoke tests; dedicated
SSH, WebView, and remote-host security cases remain separate acceptance criteria.
