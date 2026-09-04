#!/usr/bin/env python3
"""Import the app's chrome icons from Lucide, at a pinned version.

The UI glyphs used to come in through `tools/import-zed-icons.py`, out of Zed's
`assets/icons/`. Zed's own README says those are "sourced from Lucide, then
modified, adjusted, cleaned up and simplified", with the occasional icon from
Phosphor or drawn from scratch — so the honest provenance of an imported Zed
glyph was "Lucide, probably, filtered through Zed's design team". That is a
fine answer for a sideload and a poor one for an app that ships preinstalled,
where the notice file is the compliance artefact and a reviewer has to be able
to check it. Coming straight from Lucide the answer is instead "Lucide 1.37.0,
commit 796dad29, ISC, this drawable is that icon", which anyone can verify.

    tools/import-lucide-icons.py                  # convert the vendored snapshot
    tools/import-lucide-icons.py --fetch          # refresh it first, then convert
    tools/import-lucide-icons.py --archive x.zip  # refresh it from a local copy

The snapshot under `tools/lucide/` is the source of truth: the SVGs this app
actually uses, plus Lucide's LICENSE, plus a SHA256SUMS the converter checks on
every run. `--fetch` is the only step that touches the network, and it verifies
the release archive and the licence against the digests pinned below before it
writes anything. So the import is reproducible offline, and a silent upstream
edit cannot slip in.

Drawable names are deliberately unchanged from the Zed import, so no Kotlin
moves. Two icons are *not* re-sourced because they are original work — see
`ic_ui_agent.xml` and `ic_stat_terminal.xml`, whose headers say so.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

SVG_NS = "{http://www.w3.org/2000/svg}"

# ---------------------------------------------------------------------------
# The pin. Change these four together, never one of them.
# ---------------------------------------------------------------------------

VERSION = "1.37.0"
COMMIT = "796dad298f8d78c5da204c3e62a5ed93c2bfcd1e"
ZIP_URL = f"https://github.com/lucide-icons/lucide/releases/download/{VERSION}/lucide-icons-{VERSION}.zip"
ZIP_SHA256 = "d001ff465039224ad2936b5da7876d0549949bf81e648eb3cee50505f35822d8"
LICENSE_URL = f"https://raw.githubusercontent.com/lucide-icons/lucide/{VERSION}/LICENSE"
LICENSE_SHA256 = "b495047bd93a9b06913511076f504daba17d5bbeb3e0650f3bb53a4220329c57"

# Everything is tinted at draw time, so the colour in the file is irrelevant —
# what matters is *whether* a path is filled or stroked. White is the identity
# for a tint.
TINT = "#FFFFFFFF"

# Lucide draws at stroke-width 2 on a 24 viewport. The git chrome draws icons
# at 16dp, where that would come out at 2/24*16 = 1.33dp — visibly heavier than
# the 1.2dp the file icons and the rest of that chrome already use. 1.8/24*16 is
# exactly 1.2dp, so the set lands at the weight the app was designed around and
# the swap is invisible. A uniform weight change is the one modification worth
# making here; nothing else about the geometry is touched.
#
# The stroke is in *viewport units*, so it scales with whatever size the caller
# draws at rather than staying 1.2dp forever: ui/theme/Icons.kt's 24dp nav slot
# gets 1.8dp of stroke and its 22dp action slot 1.65dp, which is the weight a
# 480dpi phone needs and the whole reason the glyph-as-text controls looked
# thin next to these. Nothing here has to change for that to happen.
STROKE_WIDTH = "1.8"

CAPS = {"round": "round", "square": "square", "butt": "butt"}
JOINS = {"round": "round", "bevel": "bevel", "miter": "miter"}

# Presentation attributes that an SVG element inherits from its ancestors.
# Lucide puts *all* of these on the root <svg> and lets the children inherit,
# which is precisely what the Zed importer could not do: it read them off the
# node, found nothing, and emitted a path with neither a fill colour nor a
# stroke colour. That drawable compiles, inflates, and draws nothing at all.
INHERITED = (
    "fill",
    "stroke",
    "stroke-width",
    "stroke-linecap",
    "stroke-linejoin",
    "fill-rule",
    "fill-opacity",
    "stroke-opacity",
    "opacity",
)


class Icon:
    """One drawable: which Lucide icon it is, and why."""

    def __init__(self, lucide: str, note: str, fill: bool = False) -> None:
        self.lucide = lucide
        self.note = note
        # `fill` turns a stroked outline into a solid shape. Used for the marks
        # that have to read as a *block* at 12-14dp rather than as an empty
        # outline: stop, the unsaved dot, a favourited star.
        self.fill = fill


# Drawable name -> Lucide icon. The names are the ones already in the Kotlin,
# so this table is the whole migration: nothing above `R.drawable.` changes.
ICONS: dict[str, Icon] = {
    "ic_ui_arrow_circle": Icon("refresh-cw", "the reload/refresh control"),
    "ic_ui_arrow_down": Icon("arrow-down", "next match, scroll down"),
    "ic_ui_arrow_up": Icon("arrow-up", "previous match, scroll up"),
    "ic_ui_check": Icon("check", "the branch you are on, a confirmed action"),
    "ic_ui_chevron_down": Icon("chevron-down", "expand a section"),
    "ic_ui_chevron_up": Icon("chevron-up", "collapse a section"),
    "ic_ui_close": Icon("x", "dismiss a panel, clear a field"),
    "ic_ui_cursor_i_beam": Icon("text-cursor", "the editor's caret affordance"),
    "ic_ui_envelope": Icon("mail", "a commit message"),
    "ic_ui_expand_up": Icon("arrow-up-from-line", "raise a sheet to full height"),
    "ic_ui_eye": Icon("eye", "reveal a secret in an askpass field"),
    "ic_ui_eye_off": Icon("eye-off", "hide it again"),
    # Zed's `filter.svg` is a sliders glyph, not a funnel; matching it keeps
    # the control looking like the same control.
    "ic_ui_filter": Icon("sliders-horizontal", "filter a list"),
    "ic_ui_git_branch": Icon("git-branch", "a local branch"),
    "ic_ui_git_branch_plus": Icon("git-branch-plus", "create a branch"),
    # Lucide's plain `git-commit` is the horizontal variant; Zed's, and the
    # graph this sits beside, are vertical.
    "ic_ui_git_commit": Icon("git-commit-vertical", "a commit"),
    "ic_ui_git_graph": Icon("git-graph", "the commit graph"),
    "ic_ui_hash": Icon("hash", "a commit sha, a line number"),
    "ic_ui_braces": Icon("braces", "go to a symbol in the buffer"),
    "ic_ui_load_circle": Icon("loader-circle", "work in flight; rotated by the caller"),
    "ic_ui_magnifying_glass": Icon("search", "search"),
    "ic_ui_plus": Icon("plus", "create"),
    "ic_ui_server": Icon("server", "a git remote with no known provider"),
    # Zed's terminal glyph is the boxed one, and boxed reads better at 16dp in
    # a status bar than a bare prompt does.
    "ic_ui_terminal": Icon("square-terminal", "toggle the terminal"),
    "ic_ui_trash": Icon("trash-2", "discard"),
    # `undo-2` is the arrow that turns back on itself; `undo` is the dial.
    "ic_ui_undo": Icon("undo-2", "revert a change"),
    "ic_ui_warning": Icon("triangle-alert", "a warning row"),
    # Docks.kt's panel switcher. Kept because it is still referenced today; if
    # the switcher goes, so does this — see the header of the drawable.
    "ic_ui_file_tree": Icon("folder-tree", "the project panel's dock button"),
    # **Replaces a GitHub Octocat.** The old drawable came from Zed's
    # github.svg, which is GitHub's mark redrawn as an outline — and GitHub's
    # brand terms permit no adaptation of it at all, which no GPL grant cures.
    # Both call sites already print the host's name in text, so the mark was
    # carrying no meaning the words were not. A cloud is the neutral "hosted
    # remote" glyph, and stays distinct from `server` beside it in the picker.
    "ic_ui_github": Icon("cloud", "a hosted git remote (was GitHub's mark)"),
    # The composer row. `attach` was Zed's plus glyph, which said "add
    # something" for a button labelled "Attach an image"; a paperclip says the
    # thing the label says.
    "ic_agent_attach": Icon("paperclip", "attach an image to a message"),
    "ic_agent_send": Icon("send-horizontal", "send a message"),
    "ic_agent_stop": Icon("square", "stop a running turn", fill=True),
    "ic_agent_think": Icon("lightbulb", "the agent's thinking block"),
    # Zed's queue_message glyph is lines with a return arrow, which Lucide has
    # no equivalent of; `list-end` is the closest — a list, with the arrow at
    # the point new work joins it.
    "ic_agent_queue": Icon("list-end", "queue a message behind a running turn"),

    # -----------------------------------------------------------------------
    # The glyph-as-text migration.
    #
    # Until now roughly seventy controls across the shell and the editor drew
    # their icon as a *Unicode character in a Text composable* — `⌕` for
    # search, `☰` for the file tree, `⋮` for an overflow menu. Three things are
    # wrong with that and all three showed up on a Seeker at 480dpi. A glyph
    # renders at the font's optical size and stroke weight rather than at an
    # icon metric, so it comes out thin and small beside a real drawable; the
    # size it lands at is whatever the type scale says, so `labelSmall` and
    # `titleMedium` call sites drew the same mark at different sizes; and the
    # codepoint has to exist in the font, so a device whose UI face lacks
    # `⛨` or `⌂` draws tofu where a control should be.
    #
    # These are the icons those call sites needed. Every one is a Lucide glyph
    # picked to mean what the character it replaces meant, so nothing on screen
    # changes its vocabulary — only its metrics. See ui/theme/Icons.kt for the
    # one place their size is now decided.
    # -----------------------------------------------------------------------
    "ic_ui_arrow_left": Icon("arrow-left", "back, out of a pushed route"),
    "ic_ui_arrow_right": Icon("arrow-right", "forward; a move tool call"),
    "ic_ui_at": Icon("at-sign", "add context to a message (was a literal @)"),
    "ic_ui_brain": Icon("brain", "the agent's reasoning block"),
    "ic_ui_checkbox": Icon("square", "an unticked multi-select option"),
    "ic_ui_checkbox_checked": Icon("square-check", "a ticked one"),
    "ic_ui_chevron_right": Icon("chevron-right", "a row that opens something"),
    "ic_ui_circle": Icon("circle", "queued, pending, not yet open"),
    "ic_ui_circle_dashed": Icon("circle-dashed", "the agent planning"),
    "ic_ui_circle_dot": Icon("circle-dot", "in progress; a chosen radio option"),
    # Zed's clone affordance was `⤓`, which is an arrow into a bar and reads as
    # "download" only if you already knew. A cloud with an arrow says where the
    # bytes come from, and matches ic_ui_github beside it in the same sheet.
    "ic_ui_clone": Icon("cloud-download", "clone a repository from a host"),
    "ic_ui_compact": Icon("fold-vertical", "compact the conversation's context"),
    "ic_ui_compass": Icon("compass", "the agent's mode — plan, coding, ask"),
    "ic_ui_diamond": Icon("diamond", "a read tool call; an unclassified option"),
    # Filled, not stroked: this is the dirty-buffer mark and the "open right
    # now" mark, and both have to read as a solid dot at 12dp.
    "ic_ui_dot": Icon("circle", "unsaved changes; a session already open", fill=True),
    "ic_ui_download": Icon("download", "a fetch tool call"),
    "ic_ui_folder_import": Icon("folder-input", "import a folder already on the device"),
    # `⑂` is U+2442 OCR FORK, which is not a git glyph and not in most UI
    # faces. Lucide's fork is the one every git client draws.
    "ic_ui_git_fork": Icon("git-fork", "a swarm member running in its own worktree"),
    "ic_ui_hexagon": Icon("hexagon", "which model the agent is using"),
    "ic_ui_house": Icon("house", "a model served from this device"),
    "ic_ui_key": Icon("key", "sign in with your own API key"),
    "ic_ui_lock": Icon("lock", "a capability this plan does not include"),
    "ic_ui_menu": Icon("menu", "the file tree"),
    "ic_ui_more_vertical": Icon("ellipsis-vertical", "an overflow menu"),
    "ic_ui_pause": Icon("pause", "suspended, waiting"),
    "ic_ui_pencil": Icon("pencil", "an edit tool call; add a note"),
    "ic_ui_play": Icon("play", "build, run, execute"),
    "ic_ui_redo": Icon("redo-2", "redo — the mirror of ic_ui_undo"),
    "ic_ui_return": Icon("corner-down-left", "insert a line"),
    # `rotate-ccw` rather than `ic_ui_arrow_circle`'s `refresh-cw`: restoring a
    # checkpoint goes backwards in time, and the two must not look the same.
    "ic_ui_rotate_ccw": Icon("rotate-ccw", "restore a checkpoint"),
    "ic_ui_save": Icon("save", "write the buffer to disk"),
    "ic_ui_share": Icon("share-2", "hand a file to another app"),
    "ic_ui_shield": Icon("shield", "a permission request"),
    "ic_ui_slash": Icon("slash", "the slash-command palette (was a literal /)"),
    "ic_ui_sparkles": Icon("sparkles", "sign in to Spettro; the thinking level"),
    "ic_ui_star": Icon("star", "not a favourite"),
    "ic_ui_star_filled": Icon("star", "a favourite; the recommended answer", fill=True),
    # A second filled square, name and all, so that a Build screen does not
    # have to reach into the agent's namespace for its stop button.
    "ic_ui_stop": Icon("square", "stop a running build", fill=True),
    "ic_ui_swap": Icon("arrow-right-left", "the agent switching mode"),
    "ic_ui_tab": Icon("arrow-right-to-line", "the Tab key, which a soft keyboard has not got"),
    "ic_ui_target": Icon("target", "the Setup masthead; a hidden secret"),
    "ic_ui_zap": Icon("zap", "Ultra — the swarm, armed"),
}


# ---------------------------------------------------------------------------
# SVG -> VectorDrawable
# ---------------------------------------------------------------------------


def convert(svg_path: Path, icon: Icon, feather: set[str]) -> str:
    """One Lucide SVG to one VectorDrawable, or raise with the reason it cannot."""
    root = ET.parse(svg_path).getroot()
    view_box = root.attrib.get("viewBox") or "0 0 24 24"
    _, _, vb_w, vb_h = (float(n) for n in view_box.replace(",", " ").split())

    paths: list[str] = []

    def inherit(node: ET.Element, outer: dict[str, str]) -> dict[str, str]:
        merged = dict(outer)
        for name in INHERITED:
            if name in node.attrib:
                merged[name] = node.attrib[name]
        return merged

    def visit(parent: ET.Element, outer: dict[str, str]) -> None:
        for node in parent:
            tag = node.tag.replace(SVG_NS, "")
            if tag in ("defs", "mask", "clipPath", "title", "desc", "style", "metadata"):
                # Definitions, not drawings: something has to reference them.
                # Drawing them anyway is how you get a solid white block where
                # a clip rectangle was.
                continue
            attrs = inherit(node, outer)
            if tag == "g":
                if "transform" in node.attrib:
                    raise ValueError(f"{svg_path.name}: <g transform> is not handled")
                visit(node, attrs)
                continue
            if tag == "svg":
                visit(node, attrs)
                continue
            emit(node, tag, attrs)

    def emit(node: ET.Element, tag: str, attrs: dict[str, str]) -> None:
        # VectorDrawable has only paths, so every primitive becomes the curve
        # the renderer would have produced: a circle is two arcs, a rectangle
        # four lines.
        def num(name: str, default: str = "0") -> float:
            return float(node.attrib.get(name, default))

        if tag == "path":
            data = node.attrib.get("d")
        elif tag == "circle":
            cx, cy, r = num("cx"), num("cy"), num("r")
            data = f"M{cx - r},{cy} a{r},{r} 0 1,0 {r * 2},0 a{r},{r} 0 1,0 {-r * 2},0 Z"
        elif tag == "ellipse":
            cx, cy, rx, ry = num("cx"), num("cy"), num("rx"), num("ry")
            data = f"M{cx - rx},{cy} a{rx},{ry} 0 1,0 {rx * 2},0 a{rx},{ry} 0 1,0 {-rx * 2},0 Z"
        elif tag == "rect":
            x, y, w, h = num("x"), num("y"), num("width"), num("height")
            rx = float(node.attrib.get("rx", node.attrib.get("ry", 0)))
            ry = float(node.attrib.get("ry", node.attrib.get("rx", 0)))
            if rx or ry:
                data = (
                    f"M{x + rx},{y} h{w - 2 * rx} a{rx},{ry} 0 0 1 {rx},{ry} "
                    f"v{h - 2 * ry} a{rx},{ry} 0 0 1 {-rx},{ry} h{-(w - 2 * rx)} "
                    f"a{rx},{ry} 0 0 1 {-rx},{-ry} v{-(h - 2 * ry)} "
                    f"a{rx},{ry} 0 0 1 {rx},{-ry} Z"
                )
            else:
                data = f"M{x},{y} h{w} v{h} h{-w} Z"
        elif tag == "line":
            # Lucide uses <line> for six of the icons this app imports. The Zed
            # importer refused it, which is why this converter exists.
            data = f"M{num('x1')},{num('y1')} L{num('x2')},{num('y2')}"
        elif tag in ("polyline", "polygon"):
            points = [n for n in re.split(r"[\s,]+", node.attrib.get("points", "").strip()) if n]
            if len(points) < 4 or len(points) % 2:
                raise ValueError(f"{svg_path.name}: <{tag}> has malformed points")
            pairs = [f"{points[i]},{points[i + 1]}" for i in range(0, len(points), 2)]
            data = "M" + " L".join(pairs) + (" Z" if tag == "polygon" else "")
        else:
            raise ValueError(f"{svg_path.name}: <{tag}> is not handled")

        if not data:
            return
        data = data.strip()

        # `fill` is a colour word, not a boolean: Lucide sets fill="none" on
        # the root and every child inherits it, so a shape is filled only if
        # something further in overrode that.
        fill = attrs.get("fill", "none")
        stroke = attrs.get("stroke", "none")
        # A stop button drawn as an empty box does not read as stop, and a
        # 12dp hollow circle is not a dot. This is the only place the import
        # deviates from what Lucide draws, and it is a fill, not a redraw.
        if icon.fill:
            fill, stroke = TINT, "none"
        opacity = attrs.get("opacity")

        lines = [f'    <path\n        android:pathData="{data}"']
        if fill != "none":
            lines.append(f'        android:fillColor="{TINT}"')
            if attrs.get("fill-rule") == "evenodd":
                lines.append('        android:fillType="evenOdd"')
            alpha = attrs.get("fill-opacity") or opacity
            if alpha:
                lines.append(f'        android:fillAlpha="{alpha}"')
        if stroke != "none":
            lines.append(f'        android:strokeColor="{TINT}"')
            lines.append(f'        android:strokeWidth="{STROKE_WIDTH}"')
            cap = CAPS.get(attrs.get("stroke-linecap", ""))
            if cap:
                lines.append(f'        android:strokeLineCap="{cap}"')
            join = JOINS.get(attrs.get("stroke-linejoin", ""))
            if join:
                lines.append(f'        android:strokeLineJoin="{join}"')
            alpha = attrs.get("stroke-opacity") or opacity
            if alpha:
                lines.append(f'        android:strokeAlpha="{alpha}"')
        if fill == "none" and stroke == "none":
            # The failure this whole converter exists to prevent: a path with
            # no paint. It compiles, it inflates, and the icon is invisible.
            raise ValueError(f"{svg_path.name}: <{tag}> would draw nothing (no fill, no stroke)")
        paths.append("\n".join(lines) + " />")

    # The root <svg> carries the paint for the whole file — fill, stroke,
    # stroke-width, both line joins — so it has to seed the inherited set.
    visit(root, inherit(root, {}))
    if not paths:
        raise ValueError(f"{svg_path.name}: no drawable paths")

    feather_note = (
        "\n     Derived by Lucide from Feather (MIT, (c) 2013-present Cole Bemis)."
        if icon.lucide in feather
        else ""
    )
    fill_note = (
        "\n     Filled rather than stroked: this mark has to read as a solid"
        "\n     block at 12-14dp, where an outline reads as an empty box."
        if icon.fill
        else ""
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- Generated by tools/import-lucide-icons.py from Lucide {VERSION}\n"
        f"     (commit {COMMIT[:10]}), icons/{icon.lucide}.svg — do not edit.\n"
        "     ISC, Copyright (c) 2026 Lucide Icons and Contributors."
        f"{feather_note}\n"
        "     Licence text ships at app/src/main/assets/icons/lucide-LICENSE.txt.\n"
        f"     Used for: {icon.note}.\n"
        f"     Stroke width {STROKE_WIDTH} on a 24 viewport = 1.2dp at 16dp and\n"
        "     1.8dp at 24dp; the caller picks the size, ui/theme/Icons.kt says\n"
        f"     which sizes exist.{fill_note} -->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="16dp"\n'
        '    android:height="16dp"\n'
        f'    android:viewportWidth="{vb_w:g}"\n'
        # No `android:tint` here: the caller tints, and the only attribute that
        # would fit is AppCompat's, which this app does not use.
        f'    android:viewportHeight="{vb_h:g}">\n' + "\n".join(paths) + "\n</vector>\n"
    )


# ---------------------------------------------------------------------------
# The vendored snapshot
# ---------------------------------------------------------------------------


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch(vendor: Path, archive_path: Path | None = None) -> None:
    """Refresh `tools/lucide/` from upstream, verifying both pins.

    `archive_path` swaps the download for a release archive already on disk.
    It changes nothing else: the bytes are checked against the same
    [ZIP_SHA256] before anything is written, so a snapshot seeded from a local
    copy is byte-identical to one seeded over the wire, and a wrong or tampered
    file is rejected exactly as a wrong download would be. It exists because
    fetching 1.3 MB from a release CDN is the one step of this import that can
    fail for reasons that have nothing to do with the icons.
    """
    if archive_path is not None:
        print(f"reading {archive_path}")
        archive = archive_path.read_bytes()
    else:
        print(f"fetching {ZIP_URL}")
        with urllib.request.urlopen(ZIP_URL, timeout=120) as response:
            archive = response.read()
    if digest(archive) != ZIP_SHA256:
        raise SystemExit(f"archive sha256 {digest(archive)} != pinned {ZIP_SHA256}")

    # The licence is only re-downloaded when the vendored copy is not already
    # the pinned one. Re-fetching a file whose digest we have just confirmed
    # proves nothing, and it is the second thing that can fail offline.
    vendored = vendor / "LICENSE"
    if vendored.is_file() and digest(vendored.read_bytes()) == LICENSE_SHA256:
        print(f"{vendored} already matches the pin")
        licence = vendored.read_bytes()
    else:
        print(f"fetching {LICENSE_URL}")
        with urllib.request.urlopen(LICENSE_URL, timeout=60) as response:
            licence = response.read()
        if digest(licence) != LICENSE_SHA256:
            raise SystemExit(f"LICENSE sha256 {digest(licence)} != pinned {LICENSE_SHA256}")

    icons = vendor / "icons"
    icons.mkdir(parents=True, exist_ok=True)
    for stale in icons.glob("*.svg"):
        stale.unlink()
    sums: list[str] = []
    with zipfile.ZipFile(io.BytesIO(archive)) as zf:
        for name in sorted({icon.lucide for icon in ICONS.values()}):
            svg = zf.read(f"icons/{name}.svg")
            (icons / f"{name}.svg").write_bytes(svg)
            sums.append(f"{digest(svg)}  icons/{name}.svg")
    (vendor / "LICENSE").write_bytes(licence)
    sums.append(f"{digest(licence)}  LICENSE")
    (vendor / "SHA256SUMS").write_text("\n".join(sorted(sums)) + "\n")
    print(f"vendored {len(ICONS)} sources -> {vendor}")


def verify(vendor: Path) -> None:
    """Every vendored byte still matches what `--fetch` recorded."""
    sums = vendor / "SHA256SUMS"
    if not sums.is_file():
        raise SystemExit(f"{sums} is missing — run with --fetch once, with a network")
    for line in sums.read_text().splitlines():
        want, _, name = line.partition("  ")
        path = vendor / name
        if not path.is_file():
            raise SystemExit(f"{path} is missing")
        if digest(path.read_bytes()) != want:
            raise SystemExit(f"{path} does not match SHA256SUMS — the snapshot was edited")


def feather_icons(licence: str) -> set[str]:
    """The icon names Lucide's own LICENSE attributes to Feather.

    Parsed rather than transcribed: which icons carry Cole Bemis's MIT as well
    as the ISC is a fact about the pinned licence file, and most of what this
    app uses is on the list.
    """
    match = re.search(
        r"derived from the Feather project:\s*\n\s*\n(.+?)\n\s*\n", licence, re.S
    )
    if not match:
        raise SystemExit("could not find the Feather icon list in Lucide's LICENSE")
    return {name.strip() for name in match.group(1).replace("\n", " ").split(",")}


def notices(licence: str) -> str:
    """The aggregate notice that ships beside the icons in the APK."""
    return f"""Thragg bundles two sets of icons. Both are listed here; the full
licence texts follow.


1. The chrome icons — app/src/main/res/drawable/ic_ui_*.xml and ic_agent_*.xml

   Lucide {VERSION} (commit {COMMIT}),
   https://github.com/lucide-icons/lucide, converted to Android vector
   drawables by tools/import-lucide-icons.py. Some of them are in turn
   derived by Lucide from Feather; each drawable's header says which.

   ISC, Copyright (c) 2026 Lucide Icons and Contributors, and for the
   Feather-derived subset MIT, Copyright (c) 2013-present Cole Bemis.
   Both texts are reproduced in lucide-LICENSE.txt beside this file, and
   the icons are redistributed here under those terms.

   Two chrome icons are not Lucide's: ic_ui_agent.xml and
   ic_stat_terminal.xml are original work, Copyright (C) 2026 Eyed,
   GPL-3.0-or-later.


2. The file-type icons — app/src/main/res/drawable/ic_file_*.xml

   Zed Industries' own artwork, from zed-industries/zed
   assets/icons/file_icons/, converted by tools/import-zed-icons.py and
   redistributed under GPL-3.0-or-later, the licence Zed publishes them
   under.

   Many of them depict third-party logos — Docker, GitLab, Python, Rust,
   Swift, Go, React and others. Those logos are trademarks of their
   respective owners. They appear here only to identify the type of a
   file, which is a nominative use; their presence implies no affiliation
   with, sponsorship by, or endorsement from their owners, and no
   trademark rights are granted by the licence this app ships under.


-------------------------------------------------------------------------------
Lucide, and Feather within it — verbatim from
https://github.com/lucide-icons/lucide/blob/{VERSION}/LICENSE
-------------------------------------------------------------------------------

{licence.strip()}
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--fetch",
        action="store_true",
        help="re-download the pinned release into tools/lucide/ before converting",
    )
    parser.add_argument(
        "--archive",
        help="re-vendor from a copy of the pinned release already on disk "
        "(implies --fetch; the sha256 is checked either way)",
    )
    parser.add_argument("--repo", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()

    repo = Path(args.repo)
    vendor = Path(__file__).resolve().parent / "lucide"
    if args.archive:
        fetch(vendor, Path(args.archive))
    elif args.fetch:
        fetch(vendor)
    verify(vendor)

    licence = (vendor / "LICENSE").read_text()
    feather = feather_icons(licence)

    out = repo / "app/src/main/res/drawable"
    for name, icon in sorted(ICONS.items()):
        svg = vendor / "icons" / f"{icon.lucide}.svg"
        if not svg.is_file():
            print(f"missing: {svg}", file=sys.stderr)
            return 1
        (out / f"{name}.xml").write_text(convert(svg, icon, feather))

    assets = repo / "app/src/main/assets/icons"
    assets.mkdir(parents=True, exist_ok=True)
    # ISC and MIT both require the notice to travel with every copy, so the
    # text is an APK asset, not a repository file. Reaching it from the UI is a
    # separate job — see the licences screen in the OEM audit.
    (assets / "lucide-LICENSE.txt").write_text(licence)
    (assets / "LICENSES.txt").write_text(notices(licence))

    print(f"{len(ICONS)} icons -> {out}")
    print(f"Lucide {VERSION} ISC + Feather MIT -> {assets}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
