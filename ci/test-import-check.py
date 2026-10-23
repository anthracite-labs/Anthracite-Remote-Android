#!/usr/bin/env python3
"""Catch test-source references to symbols that are not imported.

This is **not** a dead-code detector. It was called `unused-check.py`, which
promised reachability analysis it never did: it only proves that a name a test
uses is imported. `ci/static_checks.py` and the compiler cover the rest, and no
deletion should ever be justified by this script.


A missing `import org.junit.Assert.assertEquals` or a bare `CredentialCrypto`
reference compiles to a single "Unresolved reference", which costs a whole CI
round trip per symbol. This reports every such reference in one pass.

It is deliberately conservative: only identifiers the project defines (or the
JUnit assertion helpers) are checked, and names that are declared in the same
file are left alone.
"""
from __future__ import annotations

import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app/src/main/java")
TESTS = os.path.join(ROOT, "app/src/test/java")

ASSERTIONS = {
    "assertEquals", "assertArrayEquals", "assertTrue", "assertFalse", "assertNull",
    "assertNotNull", "assertSame", "assertNotSame", "assertThrows", "fail",
}


def kotlin_files(base: str) -> list[str]:
    return sorted(glob.glob(os.path.join(base, "**/*.kt"), recursive=True))


def declarations() -> dict[str, set[str]]:
    """Top-level names in main sources, mapped to the packages that declare them."""
    found: dict[str, set[str]] = {}
    for path in kotlin_files(MAIN):
        source = open(path, encoding="utf-8").read()
        package = re.search(r"^package\s+([\w.]+)", source, re.MULTILINE)
        if not package:
            continue
        for name in re.findall(
            r"^(?:internal |private |public )?(?:abstract |open |sealed |data |enum |annotation )*"
            r"(?:class|interface|object|fun|val|var|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)",
            source,
            re.MULTILINE,
        ):
            found.setdefault(name, set()).add(package.group(1))
    return found


def main() -> int:
    declared = declarations()
    problems = 0
    for path in kotlin_files(TESTS):
        source = open(path, encoding="utf-8").read()
        relative = os.path.relpath(path, ROOT)
        package_match = re.search(r"^package\s+([\w.]+)", source, re.MULTILINE)
        package = package_match.group(1) if package_match else ""
        imported = set(re.findall(r"^import [\w.]+\.([\w*]+)$", source, re.MULTILINE))
        local = set(re.findall(
            r"^(?:internal |private |public )?(?:abstract |open |sealed |data |enum )*"
            r"(?:class|object|fun|val|var)\s+([A-Za-z_][A-Za-z0-9_]*)",
            source,
            re.MULTILINE,
        ))

        for name in sorted(ASSERTIONS):
            match = re.search(rf"\b{name}\s*(<[^>]*>)?\(", source)
            if match and name not in imported and name not in local:
                line = source[: match.start()].count("\n") + 1
                print(f"::error file={relative},line={line}::{name} is used but not imported")
                problems += 1

        # A declaration in the same package needs no import, which is why the
        # package is part of the lookup.
        for name, packages in sorted(declared.items()):
            if name in imported or name in local or package in packages:
                continue
            # A type can be referenced as `Name(` or as `Name.member`; both need
            # the import (or the same package, handled above).
            match = re.search(rf"(?<![\w.]){re.escape(name)}\s*[.(<]", source)
            if match:
                line = source[: match.start()].count("\n") + 1
                print(f"::error file={relative},line={line}::{name} is used but not imported")
                problems += 1

    print(f"unused/import check: {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
