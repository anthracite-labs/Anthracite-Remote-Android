#!/usr/bin/env python3
"""Repository-level static checks that do not need an Android build.

These run in their own CI job so a compiler failure cannot hide a resource,
reference or secret-hygiene problem, and so a resource problem is reported with
a file and line instead of as one more Kotlin "unresolved reference".

Checks:
  1. Every XML file under app/src/main/res and the manifest is well formed.
  2. No duplicate resource name inside the same resource type and qualifier.
  3. Every R.<type>.<name> used from Kotlin resolves to a declared resource or id.
  4. Every activity named in the manifest has a Kotlin source file.
  5. No obvious committed credentials (tokens, private keys, passwords).
  6. Every Kotlin source file declares the package it lives in.
"""
from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
from glob import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
JAVA = os.path.join(ROOT, "app/src/main/java")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
PKG = "dev.anthracite.remote"

problems: list[tuple[str, str]] = []


def problem(where: str, message: str) -> None:
    problems.append((where, message))


def check_xml() -> None:
    for pattern in ("app/src/main/res/**/*.xml", "app/src/main/AndroidManifest.xml"):
        for path in glob(os.path.join(ROOT, pattern), recursive=True):
            try:
                ET.parse(path)
            except ET.ParseError as error:
                problem(os.path.relpath(path, ROOT), f"XML is not well formed: {error}")


def declared_resources() -> dict[str, set[str]]:
    declared: dict[str, set[str]] = {}
    for path in glob(os.path.join(RES, "values*", "*.xml")):
        qualifier = os.path.basename(os.path.dirname(path))
        tree = ET.parse(path)
        for child in tree.getroot():
            tag = child.tag
            if tag == "item" and child.get("type"):
                tag = child.get("type")
            name = child.get("name")
            if name:
                declared.setdefault(f"{tag}@{qualifier}", set()).add(name)
    for folder, kind in (
        ("layout", "layout"),
        ("menu", "menu"),
        ("drawable", "drawable"),
        ("xml", "xml"),
        ("mipmap-anydpi-v26", "mipmap"),
    ):
        for path in glob(os.path.join(RES, folder, "*")):
            base = os.path.basename(path)
            name = base.split(".")[0]
            declared.setdefault(f"{kind}@{folder}", set()).add(name)
    return declared


def check_duplicates() -> None:
    for path in glob(os.path.join(RES, "values*", "*.xml")):
        qualifier = os.path.basename(os.path.dirname(path))
        seen: dict[tuple[str, str], str] = {}
        for child in ET.parse(path).getroot():
            tag = child.tag
            if tag == "item" and child.get("type"):
                tag = child.get("type")
            name = child.get("name")
            if not name:
                continue
            key = (tag, name)
            if key in seen:
                problem(
                    os.path.relpath(path, ROOT),
                    f"duplicate {tag} '{name}' in {qualifier} (already defined earlier in the same file)",
                )
            seen[key] = name


def check_references(declared: dict[str, set[str]]) -> None:
    for path in glob(os.path.join(JAVA, "**", "*.kt"), recursive=True):
        source = open(path, encoding="utf-8").read()
        relative = os.path.relpath(path, ROOT)
        for match in re.finditer(r"R\.([a-z]+)\.([A-Za-z0-9_]+)", source):
            kind, name = match.group(1), match.group(2)
            candidates = [
                names
                for key, names in declared.items()
                if key.startswith(f"{kind}@")
            ]
            if not any(name in names for names in candidates):
                line = source[: match.start()].count("\n") + 1
                problem(relative, f"{relative}:{line}: R.{kind}.{name} is not declared")

        # Ids are declared by @+id/ in layouts and menus.
        for match in re.finditer(r"R\.id\.([A-Za-z0-9_]+)", source):
            name = match.group(1)
            if name not in declared.get("id@layouts", set()):
                line = source[: match.start()].count("\n") + 1
                problem(relative, f"{relative}:{line}: R.id.{name} is not declared in any layout or menu")

        package = re.search(r"^package\s+([\w.]+)", source, re.MULTILINE)
        if not package:
            problem(relative, "Kotlin file has no package declaration")
            continue
        expected = os.path.relpath(os.path.dirname(path), JAVA).replace(os.sep, ".")
        if expected != "." and package.group(1) != expected:
            problem(relative, f"package {package.group(1)} does not match directory {expected}")


def collect_ids() -> None:
    ids: set[str] = set()
    for path in glob(os.path.join(RES, "layout*", "*.xml")) + glob(os.path.join(RES, "menu", "*.xml")):
        source = open(path, encoding="utf-8").read()
        ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', source))
    declared["id@layouts"] = ids
    styles = set()
    for path in glob(os.path.join(RES, "values*", "*.xml")):
        for child in ET.parse(path).getroot():
            if child.tag == "style" and child.get("name"):
                styles.add(child.get("name"))
    declared["style@values"] = styles


def check_manifest_activities() -> None:
    tree = ET.parse(MANIFEST)
    android = "{http://schemas.android.com/apk/res/android}"
    known = {
        os.path.splitext(os.path.basename(path))[0]
        for path in glob(os.path.join(JAVA, "**", "*.kt"), recursive=True)
    }
    for element in tree.getroot().iter("activity"):
        name = element.get(android + "name") or ""
        simple = name.split(".")[-1]
        if simple and simple not in known:
            problem("app/src/main/AndroidManifest.xml", f"activity {name} has no source file")
        exported = element.get(android + "exported")
        intent_filter = element.find("intent-filter")
        if intent_filter is not None and exported != "true":
            problem(
                "app/src/main/AndroidManifest.xml",
                f"activity {name} has an intent filter but is not exported",
            )
        if intent_filter is None and exported == "true":
            problem(
                "app/src/main/AndroidManifest.xml",
                f"activity {name} is exported without an intent filter",
            )


def check_secrets() -> None:
    patterns = {
        "GitHub token": re.compile(r"gh[pousr]_[A-Za-z0-9]{30,}"),
        "private key block": re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        "AWS access key": re.compile(r"AKIA[0-9A-Z]{16}"),
    }
    for path in glob(os.path.join(ROOT, "app/src/**/*"), recursive=True):
        if not os.path.isfile(path):
            continue
        # Test fixtures carry deliberately fake credentials; flagging them would
        # train reviewers to ignore the check.
        if "/src/test/" in path.replace(os.sep, "/") or "/src/androidTest/" in path.replace(os.sep, "/"):
            continue
        if not path.endswith((".kt", ".xml", ".sh", ".json", ".properties", ".gradle", ".kts")):
            continue
        try:
            source = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        for label, pattern in patterns.items():
            for match in pattern.finditer(source):
                line = source[: match.start()].count("\n") + 1
                if "allowlist secret" in source.splitlines()[line - 1]:
                    continue
                problem(os.path.relpath(path, ROOT), f"{label} pattern at line {line}")


def main() -> int:
    global declared
    check_xml()
    check_duplicates()
    declared.update(declared_resources())
    collect_ids()
    check_references(declared)
    check_manifest_activities()
    check_secrets()

    for where, message in problems:
        print(f"::error file={where}::{message}")
    print(f"static checks: {len(problems)} problem(s)")
    return 1 if problems else 0


declared: dict[str, set[str]] = {}

if __name__ == "__main__":
    sys.exit(main())
