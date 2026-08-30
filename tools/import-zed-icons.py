#!/usr/bin/env python3
"""Import Zed's file icons, and the table that maps a filename to one.

Zed draws a per-language icon beside every row in its project panel, and the
icons and the mapping both live in its repository: 16x16 SVGs under
`assets/icons/file_icons/`, and three tables in `crates/theme/src/icon_theme.rs`
(suffix -> icon key, stem -> icon key, icon key -> file). Android cannot render
SVG, so this converts each one to a VectorDrawable and generates the Kotlin
table from the same source.

    tools/import-zed-icons.py [--zed /path/to/zed]

Both outputs are generated, never hand-edited: re-run this after a Zed sync and
the icons and the mapping move together. The SVGs it handles are exactly the
shape Zed's icon set uses — paths with `fill`, `stroke`, `stroke-width`,
`stroke-linecap`, `stroke-linejoin`, `fill-rule` and `opacity`; anything else
it refuses loudly rather than silently dropping.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SVG_NS = "{http://www.w3.org/2000/svg}"

# Everything is tinted at draw time, so the colour in the file is irrelevant —
# what matters is *whether* a path is filled or stroked. White is the identity
# for a tint.
TINT = "#FFFFFFFF"

CAPS = {"round": "round", "square": "square", "butt": "butt"}
JOINS = {"round": "round", "bevel": "bevel", "miter": "miter"}


def attr(node: ET.Element, name: str, default: str | None = None) -> str | None:
    return node.attrib.get(name, default)


def convert(svg_path: Path) -> str:
    """One SVG to one VectorDrawable, or raise with the reason it cannot."""
    tree = ET.parse(svg_path)
    root = tree.getroot()
    view_box = attr(root, "viewBox") or "0 0 16 16"
    _, _, vb_w, vb_h = (float(n) for n in view_box.replace(",", " ").split())

    paths: list[str] = []
    # `root.iter()` walks *everything*, including what is inside a <defs>, a
    # <mask> or a <clipPath> — so skipping those containers skipped only the
    # container. Their children came out as ordinary opaque shapes, drawn last:
    # the C, C++, Odin and Helm icons were solid white blocks, because a clip
    # rectangle is a full-viewport rectangle. Walk the tree by hand instead,
    # and do not descend into a container whose children are definitions.
    def visit(parent: ET.Element) -> None:
        for node in parent:
            tag = node.tag.replace(SVG_NS, "")
            if tag in ("defs", "mask", "clipPath", "title", "desc", "style"):
                # Definitions, not drawings: something has to reference them.
                continue
            if tag in ("svg", "g"):
                # A `g` carrying a transform would move its children, and none
                # of Zed's file icons use one; refuse rather than draw it wrong.
                if tag == "g" and "transform" in node.attrib:
                    raise ValueError(f"{svg_path.name}: <g transform> is not handled")
                visit(node)
                continue
            emit(node, tag)

    def emit(node: ET.Element, tag: str) -> None:
        nonlocal paths
        # VectorDrawable can carry a transform only on a <group>, so a
        # transformed shape becomes its own group. Four of Zed's icons need
        # this — two translate a rectangle, two place a scaled glyph.
        transform = attr(node, "transform")
        translate, scale = parse_transform(transform, svg_path.name)

        # A few icons draw with primitives rather than a path. VectorDrawable
        # has only paths, so they become the same curve the renderer would
        # have produced — a circle is four arcs, a rectangle four lines.
        if tag == "circle":
            cx, cy, r = (float(attr(node, k, "0")) for k in ("cx", "cy", "r"))
            node.set(
                "d",
                f"M{cx - r},{cy} a{r},{r} 0 1,0 {r * 2},0 a{r},{r} 0 1,0 {-r * 2},0 Z",
            )
        elif tag == "ellipse":
            cx, cy = (float(attr(node, k, "0")) for k in ("cx", "cy"))
            rx, ry = (float(attr(node, k, "0")) for k in ("rx", "ry"))
            node.set(
                "d",
                f"M{cx - rx},{cy} a{rx},{ry} 0 1,0 {rx * 2},0 a{rx},{ry} 0 1,0 {-rx * 2},0 Z",
            )
        elif tag == "rect":
            x, y = (float(attr(node, k, "0")) for k in ("x", "y"))
            w, h = (float(attr(node, k, "0")) for k in ("width", "height"))
            if attr(node, "rx") or attr(node, "ry"):
                rx = float(attr(node, "rx") or attr(node, "ry") or 0)
                ry = float(attr(node, "ry") or attr(node, "rx") or 0)
                node.set(
                    "d",
                    f"M{x + rx},{y} h{w - 2 * rx} a{rx},{ry} 0 0 1 {rx},{ry} "
                    f"v{h - 2 * ry} a{rx},{ry} 0 0 1 {-rx},{ry} h{-(w - 2 * rx)} "
                    f"a{rx},{ry} 0 0 1 {-rx},{-ry} v{-(h - 2 * ry)} "
                    f"a{rx},{ry} 0 0 1 {rx},{-ry} Z",
                )
            else:
                node.set("d", f"M{x},{y} h{w} v{h} h{-w} Z")
        elif tag != "path":
            raise ValueError(f"{svg_path.name}: <{tag}> is not handled")

        data = attr(node, "d")
        if not data:
            return
        fill = attr(node, "fill", "none")
        stroke = attr(node, "stroke", "none")
        opacity = attr(node, "opacity")

        lines = [f'    <path\n        android:pathData="{data.strip()}"']
        if fill and fill != "none":
            lines.append(f'        android:fillColor="{TINT}"')
            if attr(node, "fill-rule") == "evenodd":
                lines.append('        android:fillType="evenOdd"')
            alpha = attr(node, "fill-opacity") or opacity
            if alpha:
                lines.append(f'        android:fillAlpha="{alpha}"')
        if stroke and stroke != "none":
            lines.append(f'        android:strokeColor="{TINT}"')
            lines.append(f'        android:strokeWidth="{attr(node, "stroke-width", "1")}"')
            cap = CAPS.get(attr(node, "stroke-linecap", ""), None)
            if cap:
                lines.append(f'        android:strokeLineCap="{cap}"')
            join = JOINS.get(attr(node, "stroke-linejoin", ""), None)
            if join:
                lines.append(f'        android:strokeLineJoin="{join}"')
            alpha = attr(node, "stroke-opacity") or opacity
            if alpha:
                lines.append(f'        android:strokeAlpha="{alpha}"')
        drawn = "\n".join(lines) + " />"
        if translate != (0.0, 0.0) or scale != (1.0, 1.0):
            drawn = (
                f'    <group\n        android:translateX="{translate[0]:g}"\n'
                f'        android:translateY="{translate[1]:g}"\n'
                f'        android:scaleX="{scale[0]:g}"\n'
                f'        android:scaleY="{scale[1]:g}">\n'
                + "\n".join("    " + line for line in drawn.split("\n"))
                + "\n    </group>"
            )
        paths.append(drawn)

    visit(root)

    if not paths:
        raise ValueError(f"{svg_path.name}: no drawable paths")

    body = "\n".join(paths)
    origin = svg_path.parent.name
    origin = f"file_icons/{svg_path.name}" if origin == "file_icons" else svg_path.name
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by tools/import-zed-icons.py from Zed's "
        f"assets/icons/{origin} — do not edit. -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="16dp"\n'
        '    android:height="16dp"\n'
        f'    android:viewportWidth="{vb_w:g}"\n'
        # No `android:tint` here: the caller tints, and the only attribute
        # that would fit is AppCompat's, which this app does not use.
        f'    android:viewportHeight="{vb_h:g}">\n{body}\n</vector>\n'
    )


def parse_transform(value: str | None, name: str) -> tuple[tuple[float, float], tuple[float, float]]:
    """The `translate(...)` and `scale(...)` an icon uses, or the identity."""
    if not value:
        return (0.0, 0.0), (1.0, 1.0)
    translate = (0.0, 0.0)
    scale = (1.0, 1.0)
    for kind, args in re.findall(r"(translate|scale)\(([^)]*)\)", value):
        numbers = [float(n) for n in re.split(r"[ ,]+", args.strip()) if n]
        if kind == "translate":
            translate = (numbers[0], numbers[1] if len(numbers) > 1 else 0.0)
        else:
            scale = (numbers[0], numbers[1] if len(numbers) > 1 else numbers[0])
    leftover = re.sub(r"(translate|scale)\([^)]*\)", "", value).strip()
    if leftover:
        raise ValueError(f"{name}: transform {leftover!r} is not handled")
    return translate, scale


def ui_drawable_name(svg_path: Path) -> str:
    """`eye.svg` -> `ic_ui_eye`, for the icons the chrome uses directly."""
    return "ic_ui_" + re.sub(r"[^a-z0-9_]", "_", svg_path.stem.lower())


def drawable_name(icon_file: str) -> str:
    """`file_icons/c++.svg` -> `ic_file_cpp`. Resource names are picky."""
    stem = Path(icon_file).stem
    cleaned = re.sub(r"[^a-z0-9_]", "_", stem.lower())
    return f"ic_file_{cleaned}"


def parse_tables(source: str) -> tuple[dict[str, str], dict[str, str], dict[str, str]]:
    """The three tables out of `icon_theme.rs`, as plain dicts."""

    def table(name: str) -> str:
        start = source.index(f"const {name}")
        # Past the type annotation — which is itself `&[(&str, &[&str])]` — to
        # the value.
        start = source.index("= &[", start) + len("= ")
        depth = 0
        for i in range(start, len(source)):
            if source[i] == "[":
                depth += 1
            elif source[i] == "]":
                depth -= 1
                if depth == 0:
                    return source[start : i + 1]
        raise ValueError(f"{name}: unterminated")

    def associations(name: str) -> dict[str, str]:
        found: dict[str, str] = {}
        body = table(name)
        # `(\s*"key"` — rustfmt breaks a long list across lines, putting a
        # newline between the paren and the key, and a regex that assumed they
        # were adjacent silently dropped every icon with many suffixes. Images
        # were among them.
        for key, values in re.findall(r'\(\s*"([^"]+)"\s*,\s*&\[([^\]]*)\]', body, re.S):
            for value in re.findall(r'"([^"]+)"', values):
                found[value] = key
        return found

    icons: dict[str, str] = {}
    for key, path in re.findall(r'\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', table("FILE_ICONS")):
        icons[key] = path
    return associations("FILE_SUFFIXES_BY_ICON_KEY"), associations("FILE_STEMS_BY_ICON_KEY"), icons


def kotlin(suffixes: dict[str, str], stems: dict[str, str], icons: dict[str, str]) -> str:
    def entries(mapping: dict[str, str]) -> str:
        return "\n".join(
            f'        "{key}" to "{value}",' for key, value in sorted(mapping.items())
        )

    drawables = {key: drawable_name(path) for key, path in icons.items()}
    return f'''package to.eyed.seeker.code.ui.workspace

// Generated by tools/import-zed-icons.py from Zed's
// crates/theme/src/icon_theme.rs — do not edit by hand. Re-run the script
// after a Zed sync so the icons and this table move together.

/** Filename suffix to Zed icon key. */
internal val ZED_ICON_BY_SUFFIX: Map<String, String> = mapOf(
{entries(suffixes)}
)

/** Whole filename to Zed icon key, for the files that have no extension. */
internal val ZED_ICON_BY_STEM: Map<String, String> = mapOf(
{entries(stems)}
)

/** Zed icon key to the drawable this app ships for it. */
internal val ZED_ICON_DRAWABLE: Map<String, String> = mapOf(
{entries(drawables)}
)
'''


UI_ICONS = (
    "ai_zed.svg",
    "arrow_circle.svg",
    "arrow_down.svg",
    "arrow_up.svg",
    "check.svg",
    "chevron_down.svg",
    "chevron_up.svg",
    "close.svg",
    "cursor_i_beam.svg",
    "envelope.svg",
    "expand_up.svg",
    "eye.svg",
    "eye_off.svg",
    "file_tree.svg",
    "filter.svg",
    "git_branch.svg",
    "git_branch_plus.svg",
    "git_commit.svg",
    "git_graph.svg",
    "github.svg",
    "hash.svg",
    "load_circle.svg",
    "magnifying_glass.svg",
    "plus.svg",
    "server.svg",
    "terminal.svg",
    "trash.svg",
    "undo.svg",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    # No default: the path to a Zed checkout is a property of whoever is
    # running this, not of the repository.
    parser.add_argument("--zed", required=True, help="path to a Zed checkout")
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()

    zed = Path(args.zed)
    repo = Path(args.repo)
    icons_dir = zed / "assets/icons/file_icons"
    out_drawables = repo / "app/src/main/res/drawable"
    out_kotlin = (
        repo / "app/src/main/java/to/eyed/seeker/code/ui/workspace/ZedFileIcons.kt"
    )

    source = (zed / "crates/theme/src/icon_theme.rs").read_text()
    suffixes, stems, icon_files = parse_tables(source)

    # Only what the tables actually reference, plus the two the panel needs for
    # directories. Zed's icon directory also holds chevrons and toolbar glyphs
    # that no file maps to.
    wanted = set(icon_files.values()) | {
        "icons/file_icons/folder.svg",
        "icons/file_icons/folder_open.svg",
        "icons/file_icons/file.svg",
    }

    written = 0
    for reference in sorted(wanted):
        svg = zed / "assets" / Path(reference).relative_to("icons").parent / Path(reference).name
        svg = icons_dir / Path(reference).name
        if not svg.is_file():
            print(f"missing: {svg}", file=sys.stderr)
            return 1
        xml = convert(svg)
        (out_drawables / f"{drawable_name(reference)}.xml").write_text(xml)
        written += 1

    # The handful of Zed glyphs the chrome draws itself. Not in any table —
    # they belong to a toolbar, not to a file type — so they are named here.
    for name in sorted(UI_ICONS):
        svg = zed / "assets/icons" / name
        if not svg.is_file():
            print(f"missing: {svg}", file=sys.stderr)
            return 1
        (out_drawables / f"{ui_drawable_name(svg)}.xml").write_text(convert(svg))
        written += 1

    out_kotlin.write_text(kotlin(suffixes, stems, icon_files))
    print(f"{written} icons -> {out_drawables}")
    print(f"{len(suffixes)} suffixes, {len(stems)} stems -> {out_kotlin.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
