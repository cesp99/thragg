#!/usr/bin/env python3
"""Fail if a third-party brand mark has come back into the icon set.

Two icons were removed from this app because they are other companies'
trademarks, and no licence in this repository grants a trademark — GPLv3 s7(e)
says so in as many words:

  * Zed Industries' `ai_zed.svg`, their AI brand mark, which used to sit in the
    navigation of a fork of their editor;
  * GitHub's Octocat, which arrived as Zed's `github.svg` — an outline redraw,
    and GitHub's brand terms permit no adaptation of their marks at all.

Both came in through an importer, so both can come back through one: a Zed
sync, a re-run with an old argument list, a revert that looked harmless. This
is the guard. It checks two things a reviewer would not notice:

  * no script under `tools/` names either SVG as a *string literal* — checked
    through the AST rather than by grepping, so the scripts stay free to
    explain in a comment why the marks are gone;
  * no drawable carries a generated header saying it came from one.

    tools/check-icon-provenance.py
"""

from __future__ import annotations

import ast
import sys
from pathlib import Path

# The upstream filenames, as an importer would have to spell them to fetch one.
BANNED_SOURCES = {"ai_zed.svg", "github.svg"}

# The generated-header form: "from Zed's assets/icons/github.svg".
BANNED_HEADERS = ("icons/ai_zed.svg", "icons/github.svg")


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    problems: list[str] = []

    for script in sorted((repo / "tools").glob("*.py")):
        if script.name == Path(__file__).name:
            continue
        for node in ast.walk(ast.parse(script.read_text())):
            if isinstance(node, ast.Constant) and node.value in BANNED_SOURCES:
                problems.append(
                    f"{script.relative_to(repo)}:{node.lineno}: names {node.value!r}, "
                    "which is a third-party brand mark"
                )

    for drawable in sorted((repo / "app/src/main/res/drawable").glob("*.xml")):
        text = drawable.read_text()
        for header in BANNED_HEADERS:
            if header in text:
                problems.append(
                    f"{drawable.relative_to(repo)}: generated from {header}, "
                    "which is a third-party brand mark"
                )

    if problems:
        print("a brand mark has come back:\n", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        print(
            "\nSee the icon section of docs/THIRD_PARTY.md. Neither mark may ship;\n"
            "the replacements are ic_ui_agent.xml and Lucide's cloud glyph.",
            file=sys.stderr,
        )
        return 1

    print("no third-party brand mark in the icon set")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
