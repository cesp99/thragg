#!/usr/bin/env python3
"""Regenerate the launcher `.webp` bitmaps from the adaptive icon's vectors.

    tools/render-launcher-icon.py              # rewrite the ten mipmap webps
    tools/render-launcher-icon.py --check      # fail if any is stale
    tools/render-launcher-icon.py --sheet /tmp/launcher.png

`mipmap-anydpi/ic_launcher.xml` is what every device this app supports actually
draws — minSdk is 31 and adaptive icons landed in 26. The ten
`mipmap-*dpi/ic_launcher*.webp` are the pre-O fallback that the packaging tools
and Play still expect, and they are the files most likely to rot: nothing in a
build fails when a bitmap stops matching the vector beside it, which is exactly
how a project ends up shipping one icon in the launcher and a different one in
a store listing.

So they are not drawn. They are *derived*, here, from `ic_launcher_background`
and `ic_launcher_foreground` — the same two files the launcher inflates — using
the VectorDrawable-to-SVG translation in `render-icon-sheet.py` so there is one
converter in this repository and not two.

The geometry is the adaptive-icon contract, not an approximation of it: the
layers are a 108x108 canvas, the launcher shows the middle 72x72, and a mask
is applied to that. Rendering at `size * 108/72` and centre-cropping to `size`
reproduces it exactly, which is why the bitmaps and the adaptive icon agree at
every density instead of being two drawings of the same idea.

`ic_launcher.webp` gets a squircle mask and `ic_launcher_round.webp` a circle,
matching what `android:icon` and `android:roundIcon` promise the launcher.
"""

from __future__ import annotations

import argparse
import importlib.util
import math
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DRAWABLE = REPO / "app/src/main/res/drawable"
RES = REPO / "app/src/main/res"

# The five density buckets, and the launcher icon size each one wants. These
# are the sizes Android Studio's asset pipeline emits and the sizes already on
# disk; regenerating at anything else would silently change the packaged icon.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# 108 units of canvas, 72 of which the launcher shows. Everything else here is
# a consequence of these two numbers.
CANVAS = 108
VIEWPORT = 72

# A superellipse rather than a rounded rectangle. The rounded rect has four
# arcs joined to four straight edges, and at 48px the curvature discontinuity
# at each join is visible as a flat spot; the superellipse has none, which is
# why launchers use one.
SQUIRCLE_N = 4.0


def load_converter():
    """`to_svg` out of render-icon-sheet.py, whose filename is not importable.

    `im`, the ImageMagick 6/7 command-line shim, comes along with it so this
    script runs on the CI runner's ImageMagick 6 as well as a desktop's 7.
    """
    global im
    path = REPO / "tools/render-icon-sheet.py"
    spec = importlib.util.spec_from_file_location("render_icon_sheet", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    im = module.im
    return module.to_svg


# Bound by load_converter(); see there.
im = None

# `--check` compares pixels, not bytes. The bytes of a lossless WebP depend on
# the libwebp that encoded it, and the pixels a hair on which librsvg and which
# ImageMagick resampled them — so a byte comparison only ever passes on the
# machine that last ran the generator, and the CI runner is not that machine.
# Normalised RMSE over the decoded image: a vector edit moves this by whole
# percentage points, a different encoder by a fraction of one.
DRIFT_TOLERANCE = 0.01


def drift(fresh: Path, committed: Path) -> float:
    """Normalised root-mean-square difference between two bitmaps, 0..1."""
    result = subprocess.run(
        im("compare", "-metric", "RMSE", str(fresh), str(committed), "null:"),
        capture_output=True,
        text=True,
    )
    # `compare` exits 1 when the images differ at all and prints the metric on
    # stderr as `123 (0.0019)`; the parenthesised number is the normalised one.
    if result.returncode > 1:
        raise SystemExit(f"compare failed on {committed.name}: {result.stderr.strip()}")
    text = result.stderr.strip()
    return float(text[text.rindex("(") + 1:text.rindex(")")])


def superellipse(size: float, n: float = SQUIRCLE_N, points: int = 512) -> str:
    """|x/r|^n + |y/r|^n = 1, as an SVG path, inscribed in `size`."""
    r = size / 2.0
    out = []
    for i in range(points + 1):
        t = 2 * math.pi * i / points
        ct, st = math.cos(t), math.sin(t)
        x = r + r * math.copysign(abs(ct) ** (2.0 / n), ct)
        y = r + r * math.copysign(abs(st) ** (2.0 / n), st)
        out.append(("M" if i == 0 else "L") + f"{x:.4f},{y:.4f}")
    return " ".join(out) + " Z"


def rsvg(svg: str, px: int, out: Path) -> None:
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as handle:
        handle.write(svg)
        src = handle.name
    try:
        subprocess.run(
            ["rsvg-convert", "-w", str(px), "-h", str(px), "-o", str(out), src],
            check=True,
        )
    finally:
        Path(src).unlink(missing_ok=True)


def mask_svg(size: int, shape: str) -> str:
    body = (
        f'<circle cx="{size / 2}" cy="{size / 2}" r="{size / 2}" fill="#fff"/>'
        if shape == "circle"
        else f'<path d="{superellipse(size)}" fill="#fff"/>'
    )
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" '
        f'height="{size}">{body}</svg>'
    )


def render(to_svg, size: int, shape: str, out: Path, fmt: str = "webp") -> None:
    """One masked bitmap, composed the way the launcher composes the icon."""
    # Oversample so the 108 canvas lands on whole pixels before the crop; the
    # mask edge is antialiased by rsvg at this size and downsampled after.
    scale = 4
    full = int(round(size * CANVAS / VIEWPORT)) * scale
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        for layer in ("background", "foreground"):
            svg, painted = to_svg(DRAWABLE / f"ic_launcher_{layer}.xml")
            if not painted:
                raise SystemExit(f"ic_launcher_{layer}.xml paints nothing")
            rsvg(svg, full, tmp / f"{layer}.png")
        rsvg(mask_svg(size * scale, shape), size * scale, tmp / "mask.png")
        subprocess.run(
            im(
                str(tmp / "background.png"), str(tmp / "foreground.png"),
                "-composite",
                "-gravity", "center",
                "-crop", f"{size * scale}x{size * scale}+0+0", "+repage",
                str(tmp / "mask.png"), "-alpha", "off",
                "-compose", "copy_opacity", "-composite",
                "-filter", "Lanczos", "-resize", f"{size}x{size}",
                *(["-define", "webp:lossless=true"] if fmt == "webp" else []),
                str(out),
            ),
            check=True,
        )


def targets() -> list[tuple[Path, int, str]]:
    out = []
    for density, size in DENSITIES.items():
        out.append((RES / f"mipmap-{density}/ic_launcher.webp", size, "squircle"))
        out.append((RES / f"mipmap-{density}/ic_launcher_round.webp", size, "circle"))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="do not write; fail if a bitmap is stale")
    parser.add_argument("--sheet", type=Path,
                        help="also write a review sheet: both masks, both "
                             "backdrops, 48/96/192, plus the themed layer")
    args = parser.parse_args()
    to_svg = load_converter()

    stale = []
    for out, size, shape in targets():
        out.parent.mkdir(parents=True, exist_ok=True)
        if args.check:
            with tempfile.TemporaryDirectory() as tmp:
                fresh = Path(tmp) / out.name
                render(to_svg, size, shape, fresh)
                if not out.exists():
                    stale.append(f"{out.relative_to(REPO)} (missing)")
                elif (d := drift(fresh, out)) > DRIFT_TOLERANCE:
                    stale.append(f"{out.relative_to(REPO)} (RMSE {d:.4f})")
        else:
            render(to_svg, size, shape, out)
            print(f"  {out.relative_to(REPO)}  {size}x{size} {shape}")

    if args.sheet:
        review_sheet(to_svg, args.sheet)
        print(f"  {args.sheet}")

    if stale:
        print("\nlauncher bitmaps do not match the vectors:\n", file=sys.stderr)
        for path in stale:
            print(f"  {path}", file=sys.stderr)
        print("\nRun tools/render-launcher-icon.py.", file=sys.stderr)
        return 1
    return 0


def review_sheet(to_svg, out: Path) -> None:
    """The thing to actually look at before believing any of this.

    A launcher icon is a 48dp decision, so 48 comes first and the large sizes
    are there to check craft, not legibility. Both masks, because a mark that
    survives the squircle can still have a cap clipped by the circle, and both
    backdrops, because a tile that holds its edge on white can vanish on black.
    """
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        tiles = {}
        for size in (48, 96, 192):
            for shape in ("squircle", "circle"):
                tile = tmp / f"{size}_{shape}.png"
                render(to_svg, size, shape, tile, fmt="png")
                tiles[(size, shape)] = tile

        # The themed icon: system plate, monochrome layer tinted onto it. Two
        # plates, because a themed launcher follows the system light/dark.
        themed = []
        for plate, tint, name in (("#E6E0D4", "#3B3226", "light"),
                                  ("#3B3226", "#E6E0D4", "dark")):
            svg, _ = to_svg(DRAWABLE / "ic_launcher_monochrome.xml")
            for size in (48, 96, 192):
                mono = tmp / f"mono_{name}_{size}.png"
                scale = 4
                full = int(round(size * CANVAS / VIEWPORT)) * scale
                rsvg(svg, full, tmp / "m.png")
                rsvg(mask_svg(size * scale, "squircle"), size * scale, tmp / "mk.png")
                subprocess.run(
                    im("-size", f"{full}x{full}", f"xc:{plate}",
                       "(", str(tmp / "m.png"), "-fill", tint, "-colorize", "100", ")",
                       "-composite", "-gravity", "center",
                       "-crop", f"{size * scale}x{size * scale}+0+0", "+repage",
                       str(tmp / "mk.png"), "-alpha", "off",
                       "-compose", "copy_opacity", "-composite",
                       "-filter", "Lanczos", "-resize", f"{size}x{size}", str(mono)),
                    check=True)
                themed.append(mono)

        rows = []
        for backdrop, name in (("#EFEFF2", "light"), ("#0D0D0F", "dark")):
            row = [str(tiles[(s, k)]) for s in (48, 96, 192)
                   for k in ("squircle", "circle")]
            path = tmp / f"row_{name}.png"
            subprocess.run(im("-background", backdrop, "-gravity", "center",
                              *row, "+append", "-bordercolor", backdrop,
                              "-border", "16x16", str(path)), check=True)
            rows.append(str(path))
        path = tmp / "row_themed.png"
        subprocess.run(im("-background", "#7A7A80", "-gravity", "center",
                          *[str(p) for p in themed], "+append",
                          "-bordercolor", "#7A7A80", "-border", "16x16", str(path)),
                       check=True)
        rows.append(str(path))
        subprocess.run(im("-background", "#7A7A80", "-gravity", "west",
                          *rows, "-append", str(out)), check=True)


if __name__ == "__main__":
    sys.exit(main())
