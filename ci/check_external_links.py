#!/usr/bin/env python3
"""Scheduled availability check for external documentation links.

`ci/static_checks.py` deliberately makes no request: PR checks stay offline and
deterministic, and a link that dies later is not a defect in the commit that
added it. This script is the other half — a weekly scheduled run that fetches
each https link in README.md, SECURITY.md and docs/**/*.md and reports the ones
that no longer answer.

Behaviour:
  * same link set as the offline check (fenced code blocks ignored);
  * one HEAD per URL (GET fallback when a server refuses HEAD), 15 s timeout,
    up to 2 retries with backoff for 429/5xx and transport errors;
  * 401/403 are reported as "needs authentication" warnings, not failures: a
    login-walled page is not a dead link;
  * exit 0 with a warning list when only auth-walled or retried-temporary
    failures remain; exit 1 listing dead links (DNS, connection refused, 404,
    410, repeated 5xx) so the scheduled run goes red.
"""
from __future__ import annotations

import os
import re
import sys
import time
import urllib.error
import urllib.request
from glob import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
MAX_DOCS = 500
MAX_DOC_BYTES = 1 << 20
TIMEOUT_S = 15
RETRIES = 2

LINK_HEADERS = {"User-Agent": "Anthracite-LinkCheck/1.0 (+scheduled CI)"}

RETRY_STATUS = {408, 425, 429, 500, 502, 503, 504}
AUTH_STATUS = {401, 403, 407}
DEAD_STATUS = {404, 410}


def doc_files() -> list[str]:
    found: list[str] = []
    for directory, _, names in os.walk(ROOT):
        if ".git" in directory.split(os.sep):
            continue
        for name in names:
            if name.endswith(".md"):
                found.append(os.path.join(directory, name))
    return sorted(found)[:MAX_DOCS]


def external_links() -> dict[str, list[str]]:
    """Map each external https URL to the first few places that reference it."""
    urls: dict[str, list[str]] = {}
    for path in doc_files():
        relative = os.path.relpath(path, ROOT)
        try:
            if os.path.getsize(path) > MAX_DOC_BYTES:
                continue
            text = open(path, encoding="utf-8").read()
        except (OSError, UnicodeDecodeError):
            continue
        in_fence = False
        for number, line in enumerate(text.splitlines(), start=1):
            if line.lstrip().startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            for target in LINK.findall(line):
                if not target.startswith("https://"):
                    continue
                urls.setdefault(target, []).append(f"{relative}:{number}")
    return urls


def fetch_once(url: str, method: str) -> int | str:
    request = urllib.request.Request(url, method=method, headers=LINK_HEADERS)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except Exception as error:  # DNS, refused, reset, TLS, timeout
        return f"{type(error).__name__}: {error}"


def check(url: str) -> tuple[str, str]:
    """Return (verdict, detail) with verdict in dead/auth/temporary/ok."""
    last: int | str = ""
    for attempt in range(RETRIES + 1):
        status = fetch_once(url, "HEAD")
        if status == 405 or status == 501:
            # The server does not implement HEAD: one GET decides it.
            status = fetch_once(url, "GET")
        last = status
        if isinstance(status, int):
            if status in AUTH_STATUS:
                return ("auth", f"HTTP {status}")
            if status in DEAD_STATUS:
                return ("dead", f"HTTP {status}")
            if status in RETRY_STATUS:
                time.sleep(2**attempt)
                continue
            if 200 <= status < 400:
                return ("ok", f"HTTP {status}")
            return ("dead", f"HTTP {status}")
        time.sleep(2**attempt)
    return ("temporary" if isinstance(last, int) else "dead", str(last))


def main() -> int:
    urls = external_links()
    if not urls:
        print("no external links found")
        return 0
    dead: list[str] = []
    warnings: list[str] = []
    for url in sorted(urls):
        where = ", ".join(urls[url][:3])
        verdict, detail = check(url)
        if verdict == "ok":
            continue
        if verdict == "auth":
            warnings.append(f"AUTH {url} ({detail}) [{where}]")
        elif verdict == "temporary":
            warnings.append(f"TEMPORARY {url} ({detail}) [{where}]")
        else:
            dead.append(f"DEAD {url} ({detail}) [{where}]")
    for line in warnings:
        print(f"warning: {line}")
    for line in dead:
        print(f"error: {line}")
    print(f"checked {len(urls)} external links: {len(dead)} dead, {len(warnings)} warnings")
    return 1 if dead else 0


if __name__ == "__main__":
    sys.exit(main())
