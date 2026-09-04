#!/usr/bin/env python3
"""Generate app/src/main/assets/licenses/components.json — the notices bundle.

docs/LICENSING.md §4: "Generate it. Do not hand-maintain it. A hand-curated
list of 471 crates is a list that is wrong within one sprint." This is the
generator, and the file it writes is what the in-app licences screen (§5)
reads. Nothing about that screen is hard-coded in Kotlin.

    tools/gen-licenses.py            # write components.json
    tools/gen-licenses.py --check    # exit 1 if the committed copy is stale

Three inputs, exactly as §4 specifies:

 1. THE RUST CLOSURE, from cargo, run against the release target and feature
    set — `--target aarch64-linux-android`, `-p jni-bridge` — and not over
    core/Cargo.lock. That distinction is the whole reason this script exists
    rather than a lock-file parser: Cargo.lock resolves 834 packages and
    includes resvg, usvg and freetype-sys, none of which link, because gpui is
    taken with default-features = false and those three sit behind its
    off-by-default `images` feature. Running against the target PROVES their
    absence instead of arguing it. 471 packages link today.

    `cargo tree` gives the closure and `cargo metadata` gives each package's
    licence, repository and manifest path; both are run --offline, because a
    notices build that needs the network is one that cannot be reproduced from
    the corresponding-source archive three years from now.

 2. THE MAVEN CLOSURE, from Gradle, via `./gradlew :app:dumpMavenLicences`,
    which writes tools/licenses/maven-runtime.json — the RELEASE RUNTIME
    classpath of both flavours, so the Android Gradle Plugin, JAXB and Bouncy
    Castle stay out. That file is committed, so this script runs without
    Gradle; refresh it when a dependency changes.

 3. THE CHECKED-IN MANIFEST, tools/licenses/manifest.jsonc, for what neither
    tool can see: the vendored Termux modules, proot, talloc, the fonts, the
    themes, the icon sets, the tree-sitter query files, the components Setup
    downloads, and Eyed's own entry.

WHAT IT REFUSES TO DO. The script fails, loudly, rather than emit a row it
cannot stand behind:

  * a crate with no `license` field and no entry in `rustLicenceOverrides`;
  * an SPDX identifier with no verbatim text in assets/licenses/;
  * a Maven group with no entry in `mavenLicences`;
  * a `licenseFiles` path in the manifest that does not exist in assets.

Adding a dependency without a notice therefore breaks the build (that is what
--check is for in CI) instead of quietly producing an incomplete screen.

WHAT IT WILL NOT INVENT. `copyright` is a line copied verbatim out of the
component's own LICENSE, COPYING or NOTICE file. Where upstream ships no such
file inside the package — 46 of the 471 crates, mostly the tree-sitter
grammars, whose licence lives in their git repository and not in their
.crate — the field is null and `authors` carries the Cargo.toml authors
instead, which the screen labels as authors rather than as a copyright notice.
Manufacturing "Copyright (c) 2026 The X Authors" out of a name would be
inventing the one string MIT actually requires us to reproduce.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import subprocess
import sys
from typing import Any

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE = os.path.join(ROOT, "core")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
LICENSE_DIR = os.path.join(ASSETS, "licenses")
OUT = os.path.join(LICENSE_DIR, "components.json")
MANIFEST = os.path.join(ROOT, "tools", "licenses", "manifest.jsonc")
MAVEN_DUMP = os.path.join(ROOT, "tools", "licenses", "maven-runtime.json")

TARGET = "aarch64-linux-android"
PACKAGE = "jni-bridge"

# SPDX identifier -> the verbatim texts a reader has to be given for it.
#
# Two rows are not one-to-one and both are deliberate. LGPL-3.0 is a set of
# additional permissions ON TOP OF GPLv3, so an LGPL row without the GPL text
# beside it is incomplete. LLVM-exception is an exception to Apache-2.0, not a
# licence, so it never appears alone.
#
# The `-or-later` / `-only` suffixes collapse onto the same file because there
# is one GPLv3 document; which version a component may be used under is the
# row's `spdx` string, which keeps the suffix.
TEXTS: dict[str, list[str]] = {
    "GPL-3.0": ["GPL-3.0.txt"],
    "GPL-2.0": ["GPL-2.0.txt"],
    "LGPL-3.0": ["LGPL-3.0.txt", "GPL-3.0.txt"],
    "Apache-2.0": ["Apache-2.0.txt"],
    "MIT": ["MIT.txt"],
    "MIT-0": ["MIT-0.txt"],
    "ISC": ["ISC.txt"],
    "BSD-2-Clause": ["BSD-2-Clause.txt"],
    "BSD-3-Clause": ["BSD-3-Clause.txt"],
    "0BSD": ["0BSD.txt"],
    "MPL-2.0": ["MPL-2.0.txt"],
    "Zlib": ["Zlib.txt"],
    "BSL-1.0": ["BSL-1.0.txt"],
    "Unicode-3.0": ["Unicode-3.0.txt"],
    "Unlicense": ["Unlicense.txt"],
    "CC0-1.0": ["CC0-1.0.txt"],
    "bzip2-1.0.6": ["bzip2-1.0.6.txt"],
    "LLVM-exception": ["Apache-2.0.txt", "LLVM-exception.txt"],
    "OFL-1.1": ["OFL-1.1.txt"],
}

# The words an SPDX expression is built out of, which are not identifiers.
SPDX_OPERATORS = {"AND", "OR", "WITH"}

# MIT, ISC, BSD and Zlib all ship as templates with the holder left blank.
# A "copyright" line that is still a template names nobody and satisfies
# nothing, so it is not accepted as one.
TEMPLATE = re.compile(
    r"<year>|<YEAR>|\[year\]|\[yyyy\]|<copyright holders?>|<COPYRIGHT HOLDER>"
    r"|\[name of copyright owner\]|<name of author>|<owner>|\{\{|xxxx",
    re.IGNORECASE,
)
# `\b` deliberately only on the word forms: "© 2016 Ike Ku" and "(c) 2013 …"
# both start with a non-word character, and a word boundary after one of those
# never matches — which silently dropped 76 real copyright lines the first time
# this was written.
COPYRIGHT_LINE = re.compile(r"^(Copyright\b|COPYRIGHT\b|\(c\)|\(C\)|©)")
LICENSE_GLOBS = ("LICENSE*", "LICENCE*", "COPYING*", "NOTICE*", "UNLICENSE*")


def die(message: str) -> None:
    sys.exit(f"gen-licenses: {message}")


# ── JSONC ────────────────────────────────────────────────────────────────────


def strip_jsonc(text: str) -> str:
    """Drop `//` comments, leaving string literals alone.

    Written as a scanner rather than a regex because the manifest is full of
    `"https://…"`, and a regex that gets that wrong corrupts a URL in a
    compliance document without telling anyone.
    """
    out: list[str] = []
    in_string = False
    escaped = False
    i = 0
    while i < len(text):
        ch = text[i]
        if in_string:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if ch == '"':
            in_string = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and text[i : i + 2] == "//":
            while i < len(text) and text[i] != "\n":
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


# ── Licence texts ────────────────────────────────────────────────────────────


def normalise_spdx(raw: str) -> str:
    """cargo's licence strings, in the four shapes crates.io actually holds.

    Old crates wrote `MIT/Apache-2.0` before SPDX expressions were required,
    and a few write `Apache-2.0 / MIT` with spaces. Both mean OR.
    """
    return re.sub(r"\s*/\s*", " OR ", raw.strip())


def texts_for(spdx: str, where: str) -> list[str]:
    """Every licence text a row's SPDX expression obliges us to carry.

    Every identifier in the expression, not just the first: a reader offered
    "MIT OR Apache-2.0" has to be able to read both to know which one they
    want, and an `AND` row needs both because both apply. Order follows the
    expression so the primary licence is first.
    """
    files: list[str] = []
    for token in re.split(r"[()\s]+", spdx):
        token = token.strip()
        if not token or token.upper() in SPDX_OPERATORS:
            continue
        base = re.sub(r"-(or-later|only|\+)$", "", token)
        mapped = TEXTS.get(base)
        if mapped is None:
            die(f"{where}: no verbatim text is shipped for SPDX id {token!r}. "
                f"Add it to app/src/main/assets/licenses/ and to TEXTS.")
        for name in mapped:
            path = f"licenses/{name}"
            if path not in files:
                files.append(path)
    if not files:
        die(f"{where}: licence expression {spdx!r} named no identifier")
    return files


def check_asset(path: str, where: str) -> None:
    if not os.path.isfile(os.path.join(ASSETS, path)):
        die(f"{where}: licenseFiles names {path!r}, which is not in assets/")


# ── The Rust closure ─────────────────────────────────────────────────────────


def cargo(args: list[str]) -> str:
    result = subprocess.run(
        ["cargo", *args], cwd=CORE, capture_output=True, text=True
    )
    if result.returncode != 0:
        die(f"cargo {' '.join(args)} failed:\n{result.stderr.strip()}")
    return result.stdout


def path_copyright(manifest_path: str, mapping: dict[str, str]) -> str | None:
    """The recorded holder for a package vendored or written inside this tree."""
    relative = os.path.relpath(os.path.dirname(manifest_path), ROOT)
    for prefix, holder in mapping.items():
        if relative == prefix or relative.startswith(prefix + os.sep):
            return holder
    return None


def rust_closure() -> set[tuple[str, str]]:
    """(name, version) for every package that actually links.

    `-e normal,build` because a build-dependency's code runs on the build
    machine and does not ship — but its licence still governs the generated
    code some of them emit, and leaving them out would be the more surprising
    of the two mistakes. `--no-dedupe` because a deduped tree hides a package
    that appears only under an already-printed subtree.
    """
    out = cargo([
        "tree", "--offline", "-p", PACKAGE, "--target", TARGET,
        "-e", "normal,build", "--prefix", "none", "--no-dedupe",
    ])
    closure: set[tuple[str, str]] = set()
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1].startswith("v") and parts[1][1:2].isdigit():
            closure.add((parts[0], parts[1][1:]))
    if not closure:
        die("cargo tree produced no packages")
    return closure


def rust_metadata() -> dict[tuple[str, str], dict[str, Any]]:
    out = cargo([
        "metadata", "--offline", "--format-version", "1",
        "--filter-platform", TARGET,
    ])
    data = json.loads(out)
    return {(p["name"], p["version"]): p for p in data["packages"]}


def licence_text_copyrights() -> set[str]:
    """Copyright lines that belong to a LICENCE DOCUMENT, not to a component.

    The trap this exists for: a crate that ships the bare GPL as its LICENSE
    file has, as the first copyright line in it, "Copyright (C) 2007 Free
    Software Foundation, Inc." — which is the FSF's copyright on the licence
    text and says nothing about who wrote the crate. Thirty of the vendored
    Zed crates were attributed to the FSF the first time this ran.

    Rather than hand-listing the licence stewards, every copyright line in the
    verbatim texts we ship is collected and refused. Those files are the
    licence documents; a line that appears in one of them is the steward's.
    """
    lines: set[str] = set()
    for path in sorted(glob.glob(os.path.join(LICENSE_DIR, "*.txt"))):
        with open(path, encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if COPYRIGHT_LINE.match(line):
                    lines.add(steward_key(line))
    return lines


def steward_key(line: str) -> str:
    """A copyright line reduced to what it says, not how it was typed.

    The GNU texts on gnu.org write "Copyright (C)"; the copy of the GPL that
    ships inside a vendored Zed crate writes "Copyright ©". Same claim, and a
    byte comparison would have let the FSF's line through as thirty crates'
    copyright holder.
    """
    return re.sub(r"\s+", " ", line).replace("©", "(c)").lower()


STEWARDS = licence_text_copyrights()


def copyright_of(directory: str) -> str | None:
    """The first real copyright line in the package's own licence files.

    First, not all: a crate dual-licensed MIT/Apache carries two files with
    the same holder, and printing the same line twice reads as a bug. Where
    they genuinely differ the repository URL is the row's next stop.
    """
    files: list[str] = []
    for pattern in LICENSE_GLOBS:
        files += sorted(glob.glob(os.path.join(directory, pattern)))
    for path in files:
        if not os.path.isfile(path):
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as handle:
                text = handle.read()
        except OSError:
            continue
        for line in text.splitlines():
            line = line.strip()
            if (
                COPYRIGHT_LINE.match(line)
                and not TEMPLATE.search(line)
                and steward_key(line) not in STEWARDS
                and len(line) < 200
            ):
                return line
    return None


def rust_rows(
    overrides: dict[str, str], path_copyrights: dict[str, str]
) -> list[dict[str, Any]]:
    closure = rust_closure()
    metadata = rust_metadata()
    rows: list[dict[str, Any]] = []
    for name, version in sorted(closure, key=lambda p: (p[0].lower(), p[1])):
        package = metadata.get((name, version))
        if package is None:
            die(f"{name} {version} is in the tree but not in cargo metadata")
        directory = os.path.dirname(package["manifest_path"])
        vendored = directory.startswith(os.path.join(CORE, "vendor"))
        own = directory.startswith(os.path.join(CORE, "crates"))

        raw = package.get("license")
        if not raw:
            raw = overrides.get(name)
            if not raw:
                die(f"{name} {version} declares no licence and has no entry in "
                    f"manifest.jsonc rustLicenceOverrides")
        spdx = normalise_spdx(raw)

        url = package.get("repository") or package.get("homepage")
        if not url and not (vendored or own):
            url = f"https://crates.io/crates/{name}"

        row: dict[str, Any] = {
            "id": f"rust/{name}@{version}",
            "group": "rust",
            "name": name,
            "version": version,
            "spdx": spdx,
            # The crate's own notice where it has one. Where it does not —
            # a Zed crate whose only licence file is the bare GPL — the
            # holder is still a recorded fact (core/vendor/VENDOR.md), and
            # `rustPathCopyrights` in the manifest is where it is recorded.
            # It is a FALLBACK: a line in the package always wins.
            "copyright": copyright_of(directory) or path_copyright(
                package["manifest_path"], path_copyrights
            ),
            "url": url or "",
            "licenseFiles": texts_for(spdx, f"crate {name} {version}"),
        }
        authors = [a for a in package.get("authors") or [] if a.strip()]
        if row["copyright"] is None and authors:
            row["authors"] = authors
        if vendored:
            row["origin"] = "Vendored from Zed at bc538def45, in core/vendor/."
        elif own:
            row["origin"] = "Written for this project, in core/crates/."
        rows.append(row)
    return rows


# ── The Maven closure ────────────────────────────────────────────────────────


def maven_rows(licences: dict[str, dict[str, str]]) -> list[dict[str, Any]]:
    if not os.path.isfile(MAVEN_DUMP):
        die(f"{os.path.relpath(MAVEN_DUMP, ROOT)} is missing. "
            f"Run ./gradlew :app:dumpMavenLicences")
    with open(MAVEN_DUMP, encoding="utf-8") as handle:
        dump = json.load(handle)
    rows: list[dict[str, Any]] = []
    for entry in sorted(dump["modules"], key=lambda m: m["id"]):
        group, name, version = entry["id"].split(":")
        # Longest matching prefix, so androidx.compose.ui resolves through
        # "androidx" and a future "androidx.wear" needs no new entry, while a
        # publisher who needs their own row can have one.
        match = max(
            (key for key in licences if group == key or group.startswith(key + ".")),
            key=len,
            default=None,
        )
        if match is None:
            die(f"Maven group {group!r} ({entry['id']}) has no entry in "
                f"manifest.jsonc mavenLicences. Add one, with the licence its "
                f"POM declares.")
        licence = licences[match]
        spdx = licence["spdx"]
        rows.append({
            "id": f"android/{group}:{name}",
            "group": "android",
            "name": f"{group}:{name}",
            "version": version,
            "spdx": spdx,
            "copyright": licence.get("copyright"),
            "url": licence.get("url", ""),
            "licenseFiles": texts_for(spdx, f"maven {entry['id']}"),
        })
    return rows


# ── The manifest ─────────────────────────────────────────────────────────────


def build_facts() -> dict[str, str]:
    """The two facts that live in the build and must not be typed twice.

    The release name is in app/build.gradle.kts and the repository URL is in
    core/Cargo.toml, and docs/LICENSING.md §3 requires everything — the offer,
    this screen, README.md, the release archive — to name the SAME URL byte for
    byte. So the manifest writes `@versionName` and `@sourceUrl` and this
    resolves them, rather than a person keeping three copies in step.
    """
    gradle = os.path.join(ROOT, "app", "build.gradle.kts")
    cargo_toml = os.path.join(ROOT, "core", "Cargo.toml")
    with open(gradle, encoding="utf-8") as handle:
        version = re.search(r'versionName\s*=\s*"([^"]+)"', handle.read())
    with open(cargo_toml, encoding="utf-8") as handle:
        url = re.search(r'^repository\s*=\s*"([^"]+)"', handle.read(), re.MULTILINE)
    if not version:
        die("app/build.gradle.kts has no versionName for the app row to carry")
    if not url:
        die("core/Cargo.toml has no repository URL for the app row to carry")
    return {"@versionName": version.group(1), "@sourceUrl": url.group(1)}


# The row the licences screen's "View the GPL v3" link opens by id. The screen
# names it as an identifier and nothing else; if the manifest renames it, the
# link would land on a missing component and the screen would say so politely
# instead of showing the GPL. Fail here rather than there.
APP_ROW_ID = "app/thragg"


def manifest_rows(components: list[dict[str, Any]]) -> list[dict[str, Any]]:
    facts = build_facts()
    rows: list[dict[str, Any]] = []
    for row in components:
        where = f"manifest row {row['id']}"
        for path in row.get("licenseFiles", []):
            check_asset(path, where)
        resolved = {
            key: facts.get(value, value) if isinstance(value, str) else value
            for key, value in row.items()
        }
        rows.append(resolved)
    if not any(row["id"] == APP_ROW_ID for row in rows):
        die(f"the manifest has no {APP_ROW_ID!r} row; the licences screen links "
            f"to it by that id")
    return rows


# ── Assembly ─────────────────────────────────────────────────────────────────


def build() -> dict[str, Any]:
    with open(MANIFEST, encoding="utf-8") as handle:
        manifest = json.loads(strip_jsonc(handle.read()))

    rows = (
        manifest_rows(manifest["components"])
        + rust_rows(manifest["rustLicenceOverrides"], manifest["rustPathCopyrights"])
        + maven_rows(manifest["mavenLicences"])
    )

    seen: set[str] = set()
    for row in rows:
        if row["id"] in seen:
            die(f"two components share the id {row['id']!r}")
        seen.add(row["id"])

    groups = []
    for group_id, meta in manifest["groups"].items():
        members = [row for row in rows if row["group"] == group_id]
        if not members:
            die(f"group {group_id!r} has no components")
        groups.append({
            "id": group_id,
            "title": meta["title"],
            "note": meta.get("note", ""),
            "components": [
                {key: value for key, value in row.items() if key != "group"}
                for row in members
            ],
        })

    return {
        "schema": 1,
        "note": [
            "GENERATED by tools/gen-licenses.py. Do not edit by hand.",
            "The bill of materials for this package: every component in it,",
            "its version, its SPDX identifier, its copyright holder and the",
            "verbatim licence text that governs it. Read by the in-app",
            "Open source licences screen; see docs/LICENSING.md §4 and §5.",
        ],
        "target": TARGET,
        "rustPackage": PACKAGE,
        "componentCount": len(rows),
        "groups": groups,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if the committed components.json is not what this run produces",
    )
    args = parser.parse_args()

    # Two-space indent and sort_keys off: the file is read by a human in a
    # diff far more often than by the parser, and a stable key order keeps a
    # dependency bump to the lines that changed.
    text = json.dumps(build(), indent=2, ensure_ascii=False) + "\n"

    if args.check:
        if not os.path.isfile(OUT):
            print(f"{os.path.relpath(OUT, ROOT)} is missing", file=sys.stderr)
            return 1
        with open(OUT, encoding="utf-8") as handle:
            if handle.read() != text:
                print(
                    f"{os.path.relpath(OUT, ROOT)} is stale — a dependency changed "
                    f"without its notice. Run tools/gen-licenses.py and commit "
                    f"the result.",
                    file=sys.stderr,
                )
                return 1
        print(f"{os.path.relpath(OUT, ROOT)} is up to date")
        return 0

    os.makedirs(LICENSE_DIR, exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as handle:
        handle.write(text)
    data = json.loads(text)
    print(f"wrote {os.path.relpath(OUT, ROOT)}: {data['componentCount']} components in "
          f"{len(data['groups'])} groups")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
