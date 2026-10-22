#!/usr/bin/env python3
"""Turn the Android lint XML report into annotations.

Lint failures otherwise arrive as "task FAILED" with no location, because the
console output only says that lint found errors and points at an HTML report the
reader has to download. This prints the id, message and file:line of each
error-severity finding instead, capped so one pathological run cannot bury the
job summary.

Warnings are counted but not annotated: the project treats warnings as visible
in the report, not as build failures.
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

MAX_REPORTED = 40


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: lint-report.py <lint-results-debug.xml>")
        return 2
    try:
        root = ET.parse(argv[1]).getroot()
    except FileNotFoundError:
        print(f"::error::lint produced no XML report at {argv[1]}")
        return 0
    except ET.ParseError as error:
        print(f"::error::lint XML report is not well formed: {error}")
        return 0

    errors = 0
    warnings = 0
    for issue in root.iter("issue"):
        severity = (issue.attrib.get("severity") or "Warning").lower()
        if severity not in {"error", "fatal"}:
            warnings += 1
            continue
        location = issue.find("location")
        path = location.attrib.get("file", "?") if location is not None else "?"
        line = location.attrib.get("line", "1") if location is not None else "1"
        identifier = issue.attrib.get("id", "lint")
        message = " ".join((issue.attrib.get("message") or "").split())
        if errors < MAX_REPORTED:
            print(f"::error file={path},line={line}::[{identifier}] {message}")
        elif errors == MAX_REPORTED:
            print("::notice::Further lint errors were suppressed; the full report is an artifact.")
        errors += 1

    print(f"lint: {errors} error-severity finding(s), {warnings} warning(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
