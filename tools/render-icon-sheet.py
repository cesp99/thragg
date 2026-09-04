#!/usr/bin/env python3
"""Render VectorDrawables to a contact sheet, and fail on any that draw nothing.

An SVG-to-VectorDrawable conversion has one failure mode that review cannot
catch: a `<path>` with correct-looking `pathData` and no paint. It compiles, it
inflates, it costs nothing at runtime, and it is invisible. The only way to know
an icon survived the import is to rasterise it and look at the pixels.

    tools/render-icon-sheet.py 'ic_ui_*' 'ic_agent_*' -o /tmp/sheet.png
    tools/render-icon-sheet.py 'ic_ui_*' --dp 14 -o /tmp/marks.png

Each drawable is translated back into an SVG that rsvg-convert can rasterise —
the same paths, the same paint — then tiled into one labelled PNG. Any tile
with no ink is reported and the exit status is non-zero, so this works as a
check as well as something to look at.

`--dp` is the second question, and the one a 96px tile cannot answer: **does
this icon still read at the size it is actually drawn?** It rasterises at
`dp x density` pixels — 480dpi, the Seeker's panel, by default — so `--dp 14`
is literally the pixels a status mark occupies on the device, and `--dp 24` is
the nav bar. An icon whose interior detail closes up into a blob at 14dp is a
drawable that passed the ink check and still fails on the phone; ui/theme/
Icons.kt's four sizes were chosen against these sheets.
"""

from __future__ import annotations

import argparse
import fnmatch
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"

CELL = 96  # px per tile; big enough that a 1.2dp stroke is unmistakable

# The Seeker: 400 x 890dp at 480dpi, which is 3x. `--dp N` therefore rasterises
# at 3N pixels, the exact pixel count the device puts on the glass.
SEEKER_DENSITY = 3.0


def im(*args: str) -> list[str]:
    """An ImageMagick command line that runs on whichever major version is here.

    ImageMagick 7 is one `magick` binary and every tool is a subcommand of it.
    ImageMagick 6 -- still what Ubuntu's `imagemagick` package installs, so
    still what the CI runner has -- has no `magick` at all, only the classic
    `convert`, `montage`, `compare`, `identify` binaries. The two accept the
    same operators, so the whole difference is the name at the front.
    """
    if shutil.which("magick"):
        return ["magick", *args]
    if args and args[0] in ("montage", "compare", "identify"):
        return [args[0], *args[1:]]
    return ["convert", *args]


def a(node: ET.Element, name: str, default: str | None = None) -> str | None:
    return node.attrib.get(ANDROID + name, default)


def colour(value: str | None) -> tuple[str, float] | None:
    """`#80FF0000` (ARGB) -> (`#FF0000`, 0.5). Android puts alpha in the colour.

    The launcher template writes `#00000000` for "no fill" on a path it then
    strokes, so dropping the alpha byte would turn a transparent fill into
    opaque black.
    """
    if not value or value.startswith("@") or value.startswith("?"):
        return None
    if len(value) == 9:
        return "#" + value[3:], int(value[1:3], 16) / 255
    return value, 1.0


def to_svg(path: Path, cell: int = CELL) -> tuple[str, int]:
    """One VectorDrawable to one SVG string, plus the number of painted paths."""
    root = ET.parse(path).getroot()
    vw = a(root, "viewportWidth", "24")
    vh = a(root, "viewportHeight", "24")

    body: list[str] = []
    painted = 0

    def emit(node: ET.Element, transform: str) -> None:
        nonlocal painted
        data = a(node, "pathData")
        if not data:
            return
        parts = [f'd="{data}"']
        fill = colour(a(node, "fillColor"))
        stroke = colour(a(node, "strokeColor"))
        fill_alpha = (fill[1] if fill else 0) * float(a(node, "fillAlpha", "1"))
        stroke_alpha = (stroke[1] if stroke else 0) * float(a(node, "strokeAlpha", "1"))
        parts.append(f'fill="{fill[0]}"' if fill else 'fill="none"')
        if a(node, "fillType") == "evenOdd":
            parts.append('fill-rule="evenodd"')
        if fill:
            parts.append(f'fill-opacity="{fill_alpha:g}"')
        if stroke:
            parts.append(f'stroke="{stroke[0]}"')
            parts.append(f'stroke-opacity="{stroke_alpha:g}"')
            parts.append(f'stroke-width="{a(node, "strokeWidth", "1")}"')
            if a(node, "strokeLineCap"):
                parts.append(f'stroke-linecap="{a(node, "strokeLineCap")}"')
            if a(node, "strokeLineJoin"):
                parts.append(f'stroke-linejoin="{a(node, "strokeLineJoin")}"')
        # "Painted" means paint that is actually visible: a fill of
        # `#00000000` is a path the renderer will not put a pixel down for.
        if fill_alpha > 0 or stroke_alpha > 0:
            painted += 1
        if transform:
            parts.append(f'transform="{transform}"')
        body.append("  <path " + " ".join(parts) + " />")

    def walk(parent: ET.Element, transform: str) -> None:
        for node in parent:
            tag = node.tag.split("}")[-1]
            if tag == "group":
                tx = float(a(node, "translateX", "0"))
                ty = float(a(node, "translateY", "0"))
                sx = float(a(node, "scaleX", "1"))
                sy = float(a(node, "scaleY", "1"))
                walk(node, f"{transform} translate({tx},{ty}) scale({sx},{sy})".strip())
            elif tag == "path":
                emit(node, transform)

    walk(root, "")
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{cell}" height="{cell}" '
        f'viewBox="0 0 {vw} {vh}">\n' + "\n".join(body) + "\n</svg>\n"
    )
    return svg, painted


def ink(png: Path) -> float:
    """Mean *alpha* of a freshly rasterised icon, 0..1.

    Alpha, not luminance: rsvg-convert renders onto transparency, so a drawable
    that painted nothing comes back fully transparent and this is exactly 0.
    Measuring luminance instead would compare a blank tile against whatever
    background it was flattened onto, which is a number that looks plausible
    and means nothing — the first version of this script did that, and scored
    an icon that draws nothing at 0.12.
    """
    out = subprocess.run(
        # `-alpha on` first: a PNG rsvg wrote with no alpha channel at all
        # reports mean.a as 0, which is indistinguishable from fully blank.
        im(str(png), "-alpha", "on", "-format", "%[fx:mean.a]", "info:"),
        capture_output=True,
        text=True,
        check=True,
    )
    return float(out.stdout.strip())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("patterns", nargs="*", default=["*"], help="drawable name globs")
    parser.add_argument("-o", "--out", default="/tmp/icon-sheet.png")
    parser.add_argument("--columns", type=int, default=8)
    parser.add_argument(
        "--dp",
        type=float,
        help="rasterise at this many dp instead of one big tile — "
        "the size the icon is actually drawn (see ui/theme/Icons.kt)",
    )
    parser.add_argument(
        "--density",
        type=float,
        default=SEEKER_DENSITY,
        help=f"screen density behind --dp (default {SEEKER_DENSITY:g}x, the Seeker)",
    )
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()

    # At most one pixel per dp per density step, and never zero.
    cell = max(8, round(args.dp * args.density)) if args.dp else CELL

    drawables = Path(args.repo) / "app/src/main/res/drawable"
    files = sorted(
        f
        for f in drawables.glob("*.xml")
        if any(fnmatch.fnmatch(f.stem, p) for p in args.patterns)
    )
    if not files:
        print("no drawables matched", file=sys.stderr)
        return 1

    blank: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        tiles: list[str] = []
        for source in files:
            svg, painted = to_svg(source, cell)
            svg_path = Path(tmp) / f"{source.stem}.svg"
            svg_path.write_text(svg)
            png = Path(tmp) / f"{source.stem}.png"
            # White on transparent, then flattened onto the app's dark chrome:
            # the icons are tinted at draw time, so white is what they become.
            subprocess.run(
                ["rsvg-convert", "-w", str(cell), "-h", str(cell), "-o", str(png), str(svg_path)],
                check=True,
                env={"PATH": "/usr/bin:/bin"},
            )
            # Measure before flattening, while transparency still means
            # "nothing was drawn here".
            level = ink(png)
            if painted == 0 or level < 0.001:
                blank.append(f"{source.stem} (painted paths: {painted}, mean alpha: {level:.5f})")
            subprocess.run(
                im(str(png), "-fill", "white", "-colorize", "100", "-background",
                   "#1e1e22", "-alpha", "remove", "-alpha", "off", str(png)),
                check=True,
            )
            labelled = Path(tmp) / f"{source.stem}-label.png"
            subprocess.run(
                im(str(png), "-background", "#1e1e22", "-fill", "#9aa0a6",
                   "-pointsize", "11", "-gravity", "center",
                   "label:" + source.stem.replace("ic_", ""), "-append", str(labelled)),
                check=True,
            )
            tiles.append(str(labelled))

        subprocess.run(
            im("montage", *tiles, "-tile", f"{args.columns}x", "-geometry", "+6+6",
               "-background", "#141417", args.out),
            check=True,
        )

    size = f"{args.dp:g}dp at {args.density:g}x = {cell}px" if args.dp else f"{CELL}px"
    print(f"{len(files)} drawables at {size} -> {args.out}")
    if blank:
        print("\nDREW NOTHING:", file=sys.stderr)
        for name in blank:
            print(f"  {name}", file=sys.stderr)
        return 1
    print("every drawable put ink on the canvas")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
