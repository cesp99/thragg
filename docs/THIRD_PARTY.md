# Third-party code and binaries

This file is the register: everything in Thragg that came from somewhere
else, where it came from, and under what licence — including the components
shipped as **compiled binaries**, which carry a source obligation we intend
to meet properly.

It is written for two readers who want different things. Someone auditing
the repository wants to know where each file came from; someone auditing the
**package** — an OEM reviewer, a lawyer — wants to know what obligations
travel with the APK a user holds. This file answers the first question.
[docs/LICENSING.md](LICENSING.md) answers the second: the compatibility
matrix, the source offer, the shipping checklist, and the spec for the
in-app licences screen. Read both.

## The outbound licence, precisely

Eyed's own code is **GPL-3.0-or-later**. So is the Rust workspace
(`core/Cargo.toml` says so, and it is right about the workspace).

The application **as built and distributed is GPL-3.0-only**, because it
links Termux's `terminal-emulator` and `terminal-view`, which are
GPL-3.0-*only*. That is a perfectly ordinary inbound combination — GPL-3.0
into GPL-3.0-or-later is fine — but it caps the outbound version: no
recipient of the APK may exercise the "or later" option over Termux's code.
Anyone redistributing the app must say GPL-3.0. Anyone taking Eyed's code
on its own gets the "or later" back.

Copyright (C) 2026 Eyed. See [NOTICE](../NOTICE) for the full statement and
[LICENSE](../LICENSE) for the licence text.

## Lineage

Thragg is a fork of **Conquest Code** (GPL-3.0-or-later), which reuses
**Zed**'s engine crates and vendors **Termux**'s terminal libraries, which
themselves derive from **Android Terminal Emulator** (Apache-2.0). Every one
of those notices is preserved here, in `core/vendor/VENDOR.md`, in
`vendor/VENDOR.md`, in the module `LICENSE.md` files and in `README.md`.

## Source vendored into this repository

| Component | Upstream | Version / commit | Licence | Where |
|---|---|---|---|---|
| Zed engine crates (51 directories) | [zed-industries/zed](https://github.com/zed-industries/zed) | `bc538def45` — see the caveat below | GPL-3.0-or-later, some Apache-2.0; per-crate in `core/vendor/VENDOR.md` | `core/vendor/`, each directory carrying its own `LICENSE-GPL` or `LICENSE-APACHE` |
| Termux `terminal-emulator`, `terminal-view` | [termux/termux-app](https://github.com/termux/termux-app) | `3df69d1d` (v0.118.0) | GPL-3.0-only, with an Apache-2.0 heritage from [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator) | `vendor/` — see `vendor/VENDOR.md` |
| Zed themes (One, Ayu, Gruvbox — 11 themes in 3 family files) | zed-industries/zed | `bc538def45` | The theme *files* are GPL-3.0-or-later with Zed; the palettes are the upstream authors' — Ayu MIT (© 2016 Ike Ku), Gruvbox MIT (© Pavel Pertsev), One Dark and One Light MIT (© 2014 GitHub Inc., via [atom/atom](https://github.com/atom/atom)). All four licence texts travel with them. | `app/src/main/assets/themes/`, licences at `app/src/main/assets/themes/LICENSES.txt` |
| IBM Plex Sans (Zed's UI face) | [IBM/plex](https://github.com/IBM/plex), via Zed's `assets/fonts/` | `bc538def45` | SIL Open Font License 1.1 | `app/src/main/res/font/ibm_plex_sans_*.ttf`; licence at `app/src/main/assets/fonts/IBMPlexSans-LICENSE.txt` |
| Lilex (Zed's editor face) | [mishamyrt/Lilex](https://github.com/mishamyrt/Lilex), via Zed's `assets/fonts/` | `bc538def45` | SIL Open Font License 1.1 | `app/src/main/res/font/lilex_*.ttf`; licence at `app/src/main/assets/fonts/Lilex-LICENSE.txt` |
| Dockerfile tree-sitter grammar (generated `parser.c` / `scanner.c`) | [camdencheek/tree-sitter-dockerfile](https://github.com/camdencheek/tree-sitter-dockerfile) | v0.2.0 | MIT (© Camden Cheek) | `core/crates/tree-sitter-dockerfile/grammar/`; licence beside it at `core/crates/tree-sitter-dockerfile/LICENSE` |
| Gradle wrapper | [gradle/gradle](https://github.com/gradle/gradle) | matches `gradle/wrapper/gradle-wrapper.properties` | Apache-2.0 | `gradle/wrapper/gradle-wrapper.jar` — build tooling, **not** in the APK. Listed because it is the one committed binary that is not build output, and source scanners flag committed jars by default. |

**Two gaps in the vendored tree**, both recorded rather than quietly left:

- `core/vendor/gpui_shared_string`, `gpui_util`, `grammars` and
  `language_core` declare no `license` field in their `Cargo.toml`, so an
  SPDX scanner reports four unlicensed packages inside `libthraggcore.so`.
  The information is on disk — the first two carry `LICENSE-APACHE`, the
  last two `LICENSE-GPL`, and `core/vendor/VENDOR.md` is correct about all
  four. Upstream Zed has the same gap; it does not bite there because Zed
  ships the repository and we ship a binary. Fix is four one-line additions,
  each marked `THRAGG PATCH` so the next vendor sync does not revert it.
- `core/vendor/assets/` is the only one of the 51 vendored directories with
  no licence file. It holds Zed's repo-root `assets/settings/default.json`,
  GPL-3.0-or-later with Zed. Note before "fixing" it the obvious way: the
  `settings` crate embeds that directory with `#[folder = "../assets"]`, so
  dropping a `LICENSE-GPL` in there would compile the whole GPLv3 text into
  `libthraggcore.so`. Record it in `core/vendor/VENDOR.md` instead, or put
  the file somewhere rust-embed does not walk.

**On the Zed commit.** `core/vendor/VENDOR.md` records `bc538def45 (local
checkout, 2026-08-15)`, and `app/build.gradle.kts` parses that line at
configuration time so the About screen cannot drift from it. But "local
checkout" means the hash was taken from a working copy: every GPL claim
about the vendored Rust rests on that identifier being resolvable by a third
party, and it has not been confirmed against the public repository. Same
caveat for Termux's `3df69d1da197dd9bd71a3bafd902dffd720576b4`. Both are on
the release checklist in [LICENSING.md](LICENSING.md).

### Tree-sitter queries

Zed's own grammars carry Zed's `highlights.scm`, `indents.scm`,
`outline.scm`, `brackets.scm`, `overrides.scm` and `injections.scm`, which
are GPL-3.0-or-later with Zed and vendored at the commit above. The grammars
this project added on top of Zed's built-in set — Zed reaches them through
extensions, which this app has no runtime for — have no Zed source in the
checkout, so their queries come from elsewhere.

Most files with third-party content name their provenance in a comment at
the top. **Two do not, and both need one:** `src/sql/highlights.scm`, which
came from DerekStride's grammar, and the whole `src/html/*` set, copied
verbatim from Zed. The other header-less files (`brackets`, `indents`,
`outline`, `overrides`, `injections` across several languages) were written
here against the grammar's node names. This table is the summary either way.

| Language | Query sources | Licence |
|---|---|---|
| HTML | Zed's own `extensions/html/languages/html/*`, copied verbatim | GPL-3.0-or-later with Zed |
| Java | `highlights.scm` from [tree-sitter/tree-sitter-java](https://github.com/tree-sitter/tree-sitter-java) v0.23.5 `queries/`, with punctuation and operator captures appended; the rest written here | MIT (© Ayman Nadeem, Max Brunsfeld) |
| Kotlin | written here against [tree-sitter-grammars/tree-sitter-kotlin](https://github.com/tree-sitter-grammars/tree-sitter-kotlin) v1.1.0 (`tree-sitter-kotlin-ng`), which ships no queries | — |
| TOML | adapted from [tree-sitter-grammars/tree-sitter-toml](https://github.com/tree-sitter-grammars/tree-sitter-toml) v0.7.0 `queries/highlights.scm`; the rest written here | MIT (© Amaan Qureshi) |
| Dockerfile | `queries/highlights.scm` from camdencheek/tree-sitter-dockerfile v0.2.0, `@none` dropped; the rest written here | MIT (© Camden Cheek) |
| Make | trimmed from [tree-sitter-grammars/tree-sitter-make](https://github.com/tree-sitter-grammars/tree-sitter-make) v1.1.1 `queries/highlights.scm` onto Zed's key set | MIT |
| SQL | [DerekStride/tree-sitter-sql](https://github.com/DerekStride/tree-sitter-sql) v0.3.11 `queries/highlights.scm`, Neovim capture names renamed | MIT (© Derek Stride) |
| XML | [tree-sitter-grammars/tree-sitter-xml](https://github.com/tree-sitter-grammars/tree-sitter-xml) v0.7.0 `queries/xml/highlights.scm`, `markup.*` and `@error` renamed | MIT (© Amaan Qureshi) |
| SCSS | [tree-sitter-grammars/tree-sitter-scss](https://github.com/tree-sitter-grammars/tree-sitter-scss) v1.0.0 `queries/highlights.scm`, filled in from Zed's `css/highlights.scm` for what SCSS shares with CSS | MIT (© Amaan Qureshi) / GPL-3.0-or-later for the CSS part |
| Svelte | Zed's `html/highlights.scm` plus [tree-sitter-grammars/tree-sitter-svelte](https://github.com/tree-sitter-grammars/tree-sitter-svelte) v1.0.2 block-tag keywords and `queries/injections.scm` | GPL-3.0-or-later / MIT (© Amaan Qureshi) |

The grammars themselves are ordinary crates.io dependencies, pinned in
`core/Cargo.toml`; they are compiled into `libthraggcore.so` and are covered
by the crate section below.

## Icons

The app has 158 drawables in `res/drawable`, plus ten `mipmap-*dpi`
bitmaps. They come from three different places under three different
arrangements, and an earlier version of this file collapsed them into one
row that attributed the wrong 79 files to Lucide. Split properly:

| Set | Count | Where from | Licence and attribution |
|---|---|---|---|
| `ic_file_*.xml` | 79 | Zed's `assets/icons/file_icons/`, converted by `tools/import-zed-icons.py` | **Zed Industries' own artwork**, GPL-3.0-or-later with Zed. Roughly 15 are neutral pictograms (file, folder, code, terminal, database, lock…); the rest are language and tool **brand marks** — Docker's whale, GitLab's tanuki, the Rust gear, Python's snakes, Java's cup, Swift's bird, React's atom, and so on — redrawn by Zed's design team. Those marks belong to their owners; see [TRADEMARKS.md](TRADEMARKS.md). |
| `ic_ui_*.xml`, `ic_agent_*.xml` | 75 | [Lucide](https://lucide.dev) **1.37.0** (tag `1.37.0`, commit `796dad298f8d78c5da204c3e62a5ed93c2bfcd1e`), converted by `tools/import-lucide-icons.py` | **ISC**, © 2026 Lucide Icons and Contributors. Lucide's own LICENSE names the subset it derives from **Feather** (MIT, © 2013-present Cole Bemis) — most of what this app uses — and each generated drawable's header says whether it is on that list. Both texts ship verbatim at `app/src/main/assets/icons/lucide-LICENSE.txt`, with the aggregate notice at `app/src/main/assets/icons/LICENSES.txt`. The exact source SVGs are vendored at `tools/lucide/` with a `SHA256SUMS` the importer checks on every run, so the conversion is reproducible offline and a silent upstream edit cannot slip in. |
| `ic_ui_agent.xml`, `ic_stat_terminal.xml` | 2 | drawn for this project | Original work, Copyright (C) 2026 Eyed, GPL-3.0-or-later. |
| `ic_ui_ai_zed.xml` | 1 | a stub | Original work, Copyright (C) 2026 Eyed, GPL-3.0-or-later — it draws the same sparkle `ic_ui_agent.xml` does. The name is all that is left of Zed's AI brand mark, which this file used to hold; it survives only until `ui/workspace/Docks.kt` is removed. See below. |
| `ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `mipmap-*/ic_launcher*` | 3 + 10 | **drawn for this project** — a prompt chevron and a block cursor, on ink, with an amber caret | **Original work, Copyright (C) 2026 Eyed**, GPL-3.0-or-later, under the project licence like the rest of Eyed's code. Nothing in it is traced from or derived from another party's mark: not the Android robot, not Google's brand green, not Solana's logo or its violet-to-green gradient, not Zed's. The ten `.webp` bitmaps are not drawn at all — `tools/render-launcher-icon.py` derives them from the two vectors the launcher itself inflates, and its `--check` mode fails if they drift. See below. |

**Why the old attribution was wrong**, since it will be asked: Lucide and
Feather are generic UI glyph sets. Lucide's published Brand Logos Statement
says it does not accept brand logos and does not plan to, and points people
at Simple Icons instead. So Lucide contains no Docker whale and never will,
and "the file-icon set derives from Lucide and Feather" cannot be true of
the 79 `ic_file_*`. It *was* true, loosely, of the `ic_ui_*` /
`ic_agent_*` set, which the old text did not mention at all — the
attribution was pointed at exactly the wrong files.

**Those 75 no longer go through Zed at all.** Zed's own
`crates/icons/README.md` says its icons are "mostly" sourced from Lucide,
"sometimes" from Phosphor, and that many are drawn from scratch, so the most
honest thing that could be said of an imported Zed glyph was "Lucide,
probably". For an app whose notice file is the compliance artefact,
"probably" is not an answer. They are now imported straight from Lucide at a
pinned version, so the claim a reviewer has to check is "Lucide 1.37.0,
commit 796dad29, `icons/check.svg`" — verifiable in ten seconds against the
vendored SVG and its digest. Drawable names were kept byte-identical through
the move, so no Kotlin changed.

`app/src/main/assets/icons/LICENSES.txt` used to be a stale copy of Zed's
`assets/icons/LICENSES` — Lucide's ISC text only, which *references* Feather
without reproducing Feather's MIT permission text, while this file cited it
as the home of both. It is now generated by `tools/import-lucide-icons.py`:
an aggregate notice naming both icon sets, the trademark position on the
file icons, and Lucide's current LICENSE verbatim beside it in
`lucide-LICENSE.txt`, Feather list and MIT text included.

One thing this does **not** fix: nothing in the app reads either file. ISC
and MIT both require the notice to appear in all copies, and bytes sitting
in an APK that no screen can reach satisfy that only technically. The
licences screen is where these become notices we have actually shipped.

### Checking the icons, rather than reading them

An SVG-to-VectorDrawable conversion has one defect that review cannot catch:
a `<path>` with correct-looking `pathData` and no paint. It compiles, it
inflates, and it is invisible. `tools/render-icon-sheet.py` rasterises every
drawable, tiles it into one labelled PNG, and fails if any of them came back
fully transparent — so the set is verified by looking at pixels rather than
at XML. `tools/check-icon-provenance.py` is the companion guard against a
removed brand mark returning through an importer. Both run on every pull
request that touches the icons, via `.github/workflows/icons.yml`, which
also re-runs the Lucide import and fails if the drawables do not come back
byte-identical.

That check earned its place immediately. It found **`ic_file_vyper` drawing
nothing at all** in the shipped set, and `ic_file_helm` silently missing
four of its strokes. Both were the same bug in `tools/import-zed-icons.py`:
those two SVGs carry their paint in a `style="fill:#000"` declaration and on
an enclosing `<g>`, and the converter read `fill` off the node alone, found
nothing, and emitted an unpainted path. The converter now resolves inherited
and `style`-declared paint, and refuses outright to emit a path that would
draw nothing. Re-running it changes exactly those two files and leaves the
other 77 byte-identical.

### The launcher icon, replaced

This was the last trademark blocker, and it is closed.

What shipped until now was the Android Studio new-project template, untouched:
`ic_launcher_background.xml` was a `#3DDC84` fill — Google's Android brand
green — under the template's guide grid, `ic_launcher_foreground.xml` was the
Android robot head, `mipmap-anydpi/ic_launcher.xml` wired that foreground in as
both the foreground *and* the monochrome layer, and the stock robot `.webp` sat
in all five density buckets. Two problems at once: the robot artwork is offered
under CC BY 3.0 and we shipped no attribution for it, and the robot is Google's
trademark, which Google's brand guidelines do not permit as a third-party
product's own identity. On a phone that ships this app preinstalled it read as
Google endorsement. It was also a plain product defect — the flagship IDE with
a placeholder for a face.

**The mark now is a slab "T" whose crossbar is a horseshoe moustache**, bone
on deep ink slate. Thragg is the app's namesake and the moustache is the one
thing everybody remembers about him, so the glyph is the name said once. It is
original work owned by Eyed, under the project licence: a bar, two hooked
ends, a stem and a notch, drawn from geometry and not from any panel of the
comic, so it depicts nobody and carries nobody's trademark. (It replaced the
prompt-chevron-and-cursor mark that came in with the fork's own icon, which
said "an editor" and nothing about this one.) The colour is deliberate
distance as much as taste — Android's green, Solana's violet-to-green gradient
and the blues every other developer tool reaches for are all somewhere else on
the wheel.

Three files, and the third is the point:

| File | Layer |
|---|---|
| `drawable/ic_launcher_background.xml` | Flat `#1E2434`, full 108x108 bleed. |
| `drawable/ic_launcher_foreground.xml` | The mark, one tone, inside the 66-unit safe circle. |
| `drawable/ic_launcher_monochrome.xml` | A **separate drawing** for themed icons. |

The monochrome layer is not the foreground referenced a second time, which is
what the template did and what makes most themed icons look broken. A themed
launcher flattens every tone to one and sits the glyph on a bare system plate
with no tile of its own, so a mark tuned to fill a coloured tile looks
oversized there. The mark is one tone already; what the monochrome drawing
changes is only its size, inset 5% about the centre, every coordinate the
foreground's times 0.95 and nothing else. The same drawable is the in-app
brand glyph on the setup and empty-state screens, tinted by the screen.

`app/src/test/java/to/eyed/thragg/ui/theme/LauncherIconTest.kt` holds
those decisions in place. It parses the shipped vectors and asserts what a
redraw could plausibly break and no build would notice: that `<monochrome>`
still resolves to a different drawable than `<foreground>`; that the
monochrome outline is the colour one uniformly inset, and not a second
drawing; that both layers stay inside the 66-unit safe circle, stay centred,
still fill at least 55% of the visible viewport, and keep every limb — bar,
hook and stem — at 6 units or more (4dp at a 48dp render), with the bar
heavier than the stem so the moustache leads. Every one of those assertions was checked by breaking the icon on a
copy and watching the right test fail — a guard that cannot fail is worse than
no guard, because it reads like one.

The ten `mipmap-*dpi/ic_launcher*.webp` are the pre-O fallback. They are
generated, not drawn: `tools/render-launcher-icon.py` reads the same two
vectors the launcher inflates, reuses the VectorDrawable-to-SVG translation in
`tools/render-icon-sheet.py` so there is one converter and not two, reproduces
the adaptive-icon geometry exactly (108-unit canvas, the middle 72 shown, then
a mask — squircle for `android:icon`, circle for `android:roundIcon`), and
`--check` fails if any bitmap no longer matches. That closes the one failure
mode nobody would catch by review: the vector and the bitmap quietly becoming
two different icons.

`--sheet` writes the review sheet: 48, 96 and 192 px, both masks, on a light
and a dark backdrop, plus the monochrome layer tinted onto a light and a dark
system plate. It is worth looking at before changing anything here, because a
launcher icon is a 48dp decision and every alternative this replaced looked
fine at 512.

### Two brand marks, now dealt with

**`ic_ui_github.xml` was a modified GitHub mark.** It came from Zed's
`github.svg`, the Octocat silhouette redrawn as strokes. GitHub's brand
toolkit permits the *unmodified* logo in a narrow set of uses and states
that no adaptation of its marks is allowed without written permission; a
stroke-outline redraw is exactly the modification that is prohibited, and
GPLv3 s7(e) confirms the licence conveys no trademark rights. Substituting
the official Invertocat would not have been a fix — it trades a modification
problem for a permission-scope problem on a preinstalled app.

It is now Lucide's `cloud`, under the same drawable name so no Kotlin moved.
Both call sites (`BranchPicker.kt`, `GitGraphPane.kt`) already print the
remote's name in text, so "GitHub" carries the meaning nominatively and no
mark is needed. The stale filename is worth renaming to `ic_ui_remote` when
something else is touching those two files anyway; it is a two-line change
and not urgent.

**`ic_ui_ai_zed.xml` held Zed's AI brand mark.** The artwork is gone: the
file now draws the same hand-drawn sparkle as `ic_ui_agent.xml`, and its
header explains why the name outlived the mark. It could not simply be
deleted because `ui/workspace/Docks.kt:37` still names
`R.drawable.ic_ui_ai_zed`, and deleting a drawable ahead of its last caller
breaks the build. `Docks.kt` belongs to the panel switcher being removed;
when it goes, delete `ic_ui_ai_zed.xml` with it and leave the Kotlin
pointing at `ic_ui_agent`. Two drawables lose their last caller in that
change and nothing else does:

- `ic_ui_ai_zed` — delete it.
- `ic_ui_file_tree` — the project panel's dock button. Re-sourced from
  Lucide `folder-tree` because it is still referenced today; delete it too
  unless a panel switcher returns.

All 158 but one are reachable, and the arithmetic now closes: **75** of the
76 `ic_ui_*` / `ic_agent_*` / `ic_stat_*` drawables are named directly from
Kotlin, **79** `ic_file_*` are resolved at runtime by name through
`ZedFileIcons.kt` and `IconThemes.bundled`, and the **3** launcher layers go
through `mipmap-anydpi/ic_launcher.xml`. 75 + 79 + 3 = 157.

The one that is not reachable is **`ic_ui_return.xml`**, orphaned when the
editor's action row replaced its `↵` keycap with the word. `ic_ui_ai_zed`
and `ic_ui_file_tree` are reachable only from `ui/workspace/Docks.kt` and
fall with it. Reproduce the count with:

    grep -rhoE 'R\.drawable\.ic_(ui|agent|stat)_[a-z0-9_]*' app/src/main/java \
      | sed 's/R\.drawable\.//' | sort -u | wc -l

The pruning that a smaller UI suggests is worth a handful of files, not
eighty, because the project panel survived and it drives the whole file-icon
table.

`ai_zed.svg` has also come out of `tools/import-zed-icons.py`, which no
longer imports chrome glyphs at all — its `UI_ICONS` tuple is gone and it is
now scoped to `file_icons/` alone. The two provenances no longer share a
tool, and no re-import can resurrect a Zed logo.

## Rust crates compiled into `libthraggcore.so`

`core/Cargo.lock` has 834 entries. The number that matters is the closure
that actually links, which is smaller and which is what the notices bundle
must cover:

```
cd core && cargo tree --offline -p jni-bridge \
    --target aarch64-linux-android -e normal,build \
    --prefix none --no-dedupe | awk '{print $1" "$2}' | sort -u | wc -l
```

**471** packages at the time of writing (`jni-bridge` is the only package
`cargo-ndk` builds, per `app/build.gradle.kts`). By licence family: MIT and
MIT-OR-Apache-2.0 dominate, then Apache-2.0, then the GPL-3.0-or-later and
Apache-2.0 Zed crates, then a permissive tail of BSD-2/3-Clause, ISC, Zlib,
BSL-1.0, Unicode-3.0, CC0-1.0, 0BSD and Apache-2.0-WITH-LLVM-exception.
Every one is GPL-3.0 compatible. There is no CC-BY-NC, no "free for personal
use", no proprietary crate and no crate whose licence could not be
determined.

**Do not maintain that list by hand.** A hand-curated list of 471 crates is
a list that is wrong within one sprint. It is generated; see
[LICENSING.md](LICENSING.md), "The notices bundle".

### Weak copyleft: three MPL-2.0 crates

| Crate | Version | Reached through |
|---|---|---|
| `nucleo` | 0.5.0 | `core/vendor/fuzzy_nucleo` |
| `nucleo-matcher` | 0.3.1 | `nucleo` |
| `option-ext` | 0.2.0 | `dirs-sys` |

These are the only weak-copyleft crates in the linked closure and they were
undeclared. MPL-2.0 s3.3 permits combining MPL files into a GPL-3.0 work
*unless* a file is marked "Incompatible With Secondary Licenses". All three
`LICENSE` files were opened and read: that string appears only inside the
MPL's own Exhibit B boilerplate and is never applied to a source file, so
the combination is permitted. Recorded so the next reviewer does not have to
repeat the check. MPL-2.0 s3.2 still requires that recipients be told and be
able to get the Source Code Form of those files, which is what the notices
bundle and the crates.io links are for.

### Three crates that look alarming in the lock file and are not linked

`resvg` 0.46.0 and `usvg` 0.46.0 are MPL-2.0, and `freetype-sys` 0.20.1
carries the FreeType Licence / GPL-2.0 dual whose FTL arm requires a
specific credit line. All three resolve in `core/Cargo.lock`, which is why a
lock-file scanner will report them. None is in the linked closure: `gpui` is
depended on with `default-features = false` and `resvg`/`usvg`/`ttf-parser`
sit behind its off-by-default `images` feature, which no build of this app
enables. Verified, not assumed —

```
cd core && cargo tree --offline -p jni-bridge --target aarch64-linux-android \
    -e normal | grep -E 'resvg|usvg|freetype|ttf-parser'
```

returns nothing. This is the difference between a lock file and a bill of
materials, and it is why the generator must be run against the release
target and feature set rather than over `Cargo.lock`.

## Android and Java dependencies

Every one of these ships in the APK. Apache-2.0 flows into GPL-3 cleanly —
which is precisely why this app targets GPL-3 and not GPL-2 — but all of
them require attribution, which the package now carries: the in-app
licences screen (docs/LICENSING.md §5) is generated from
`tools/licenses/maven-runtime.json`, the release runtime classpath as
dumped by `:app:dumpMavenLicences`.

That dump is the authority for this table, and reconciling the two moved
four rows. `org.checkerframework:checker-qual` is **not** on the release
runtime classpath and has been removed; Guava and its two companions
**are**, and were missing.

| Component | Coordinate | Licence |
|---|---|---|
| Jetpack Compose (ui, ui-graphics, foundation, animation, runtime, material3) | `androidx.compose.*`, BOM `2026.02.01` | Apache-2.0, © The Android Open Source Project |
| Activity Compose | `androidx.activity:activity-compose` | Apache-2.0 |
| Core KTX, annotation, collection | `androidx.core`, `androidx.annotation`, `androidx.collection` | Apache-2.0 |
| Lifecycle runtime KTX / Compose | `androidx.lifecycle:*` | Apache-2.0 |
| ProfileInstaller | `androidx.profileinstaller:profileinstaller` | Apache-2.0 |
| media3 ExoPlayer | `androidx.media3:media3-exoplayer` 1.9.0 | Apache-2.0 |
| Graphics Path (contributes `lib/*/libandroidx.graphics.path.so`) | `androidx.graphics:graphics-path`, transitive | Apache-2.0 |
| Kotlin stdlib, kotlinx-coroutines | `org.jetbrains.kotlin:*` | Apache-2.0, © JetBrains s.r.o. |
| JSpecify | `org.jspecify:jspecify`, transitive | Apache-2.0 |
| Guava | `com.google.guava:guava`, transitive | Apache-2.0 |
| Guava `failureaccess` | `com.google.guava:failureaccess`, transitive | Apache-2.0 |
| Guava `listenablefuture` (empty marker artifact) | `com.google.guava:listenablefuture`, transitive | Apache-2.0 |

**Not in the APK, and therefore not a distribution obligation:** the Android
Gradle Plugin, JAXB, Bouncy Castle, and the EPL-2.0 / GPL-2.0-with-Classpath
/ LGPL-2.1 artifacts that appear in the Gradle module cache. They are build
tooling. `org.json:json` 20250107 is `testImplementation`-only and its POM
declares Public Domain, **not** the old GPL-incompatible "JSON License" —
checked specifically, because that one is a classic trap.

Also generated rather than hand-maintained. See [LICENSING.md](LICENSING.md).

## Binaries shipped in the APK

`app/src/main/jniLibs/*/libproot_exec.so` is **committed to this repository**
(`.gitignore` covers the rest of `/app/src/main/jniLibs/`, which is the
cargo-ndk output, and names these two files as its exception). It is
compiled by `tools/build-proot.sh`, which fetches each tarball and verifies
its SHA-256 before building.

| Binary | Source | Version | Licence |
|---|---|---|---|
| `libproot_exec.so` | [termux/proot](https://github.com/termux/proot) | v5.1.107.91 | GPL-2.0-or-later |
| (statically linked into the above) talloc | [samba.org](https://download.samba.org/pub/talloc/) | 2.4.2 | LGPL-3.0-or-later |

proot is GPL-2.0-**or-later**, not GPL-2.0-only: the Termux fork inherits
proot-me/proot's "version 2 or (at your option) any later version". That
matters, because talloc's LGPL-3.0-or-later is compatible with GPL-2 only by
way of the "or later". The combination resolves at GPL-3.0-or-later. proot
is exec'd as a standalone program rather than linked into the app, so as far
as the rest of the APK is concerned it is mere aggregation and keeps its own
terms.

We use Termux's fork rather than
[proot-me/proot](https://github.com/proot-me/proot) because upstream's
guests are killed with `SIGSYS` on current Android; the fork carries the
fixes.

`lib/*/libtermux.so` is built from the vendored Termux `terminal-emulator`
module's JNI sources and is GPL-3.0-only with it.

**Local modifications to proot**, applied by the build script and marked in
the source with `THRAGG PATCH`. There are **two**, and an earlier version of
this file listed one:

1. `src/extension/ashmem_memfd/ashmem_memfd.c` — add `#include <string.h>`.
   It uses `strcmp` and `memset` without declaring them, which clang 18 and
   later reject. Applied inline by `sed` in `tools/build-proot.sh`.
2. `src/tracee/event.c` — `tools/proot-thread-execve.patch`, ~90 lines
   adding `adopt_thread_exec_state()`. `execve(2)` from a non-leader thread
   makes the kernel replace the thread group leader; without this proot
   loses track of the tracee and the guest dies. Applied with `patch -p1`.

`tools/build-proot.sh` should assert that every patch it applies has an
entry here, so the two cannot drift again.

talloc is built from a hand-written `config.h` rather than its own `waf`
build system, which does not cross-compile comfortably; the values are in
`tools/build-proot.sh` and every one of them is true on Android's bionic.
That `config.h` is part of the corresponding source and is in the release
archive.

### Source offer

**Running `tools/build-proot.sh` is not, on its own, a GPL source offer.**
It reproduces the binaries from the upstream tarballs at the pinned versions
with both patches above, which is the *recipe*; the obligation is to hand a
recipient the ingredients. GPLv2 s3(b) and GPLv3 s6(b) both require a
written offer, valid for at least three years, made to any third party, at
no more than the cost of physically performing the distribution. "Open an
issue and we will provide one" is not that.

The real offer is in [NOTICE](../NOTICE) and in
[LICENSING.md](LICENSING.md), "The source obligation", which also describes
the per-release `corresponding-source` archive that satisfies GPLv3 s6(a)
so the offer is belt-and-braces rather than load-bearing.

## Downloaded at runtime

`app/src/main/assets/solana/toolchain/manifest.json` ships in the APK and
drives Setup. It fetches **eight** components, not one. None of them is
redistributed by Eyed: each is fetched by the user's own device from that
component's own upstream, and we host nothing and mirror nothing — so no
source obligation attaches to Eyed for any of them. That reasoning is worth
stating explicitly, because an OEM reviewer looking at a 505 MB download
will ask.

| Component | Upstream | Version | Licence |
|---|---|---|---|
| Debian userland | Debian's official `stable-slim` container image, verified against the registry digest | rolling | A collection of works under many licences; see `/usr/share/doc/*/copyright` inside the installed rootfs |
| rustup | `static.rust-lang.org` | 1.29.0 | MIT OR Apache-2.0 |
| SBF platform-tools (an LLVM with the SBF backend, and a Rust) | [anza-xyz/platform-tools](https://github.com/anza-xyz/platform-tools) | v1.57 | Apache-2.0; the bundled LLVM is Apache-2.0-WITH-LLVM-exception |
| Rust for the editor (rustc, rust-std, cargo, rust-analyzer and rust-src, `--profile minimal`) | `static.rust-lang.org`, via `rustup toolchain install` | 1.98.1 | MIT OR Apache-2.0 |
| Spettro (ACP agent) | [aploide/spettro](https://github.com/aploide/spettro) | v2.7.3 | GPL-3.0-or-later, © Eyed |
| Build tools | Debian's own archives, via `apt` | — | Each package under its own terms |
| cargo-build-sbf | crates.io, built on device | 4.2.0 | Apache-2.0 |
| anchor-cli | crates.io, built on device | 1.1.2 | Apache-2.0 |

**Spettro is GPL-3.0**, confirmed by Eyed, who holds the copyright. That
settles what was the one open question here: it is Eyed's own release
artifact, fetched by default as part of a setup flow in an app Eyed also
ships, so a proprietary agent auto-installed by a GPL app would have read as
a bait-and-switch in an open-source preinstall. It is free software on the
same terms as the app that installs it.

The arrangement itself needs no more than that: Spettro is a separate
program, downloaded on the user's initiative and executed as a subprocess
over a protocol, so it is not linked into the app and does not combine with
it. No source obligation attaches to us for a binary the *device* fetches
from the upstream project.

One loose end, and it belongs upstream rather than here: `aploide/spettro`
carries no `LICENSE` file, so the licence is stated by its author rather
than shipped with the artifact. Adding one closes the gap for anyone who
finds the binary without finding this page.

## Written here, but shaped like something else

- **Solana project scaffolds.** `solana/templates/SolanaTemplates.kt`
  generates `Anchor.toml`, the crate manifest, `lib.rs` and a test in the
  shape `anchor init` produces, and uses Anchor's own placeholder program
  id. The code is written here, and scaffold files of this kind are largely
  factual, but the resemblance is deliberate and the file's own comments say
  so. [Anchor](https://github.com/coral-xyz/anchor) is Apache-2.0.

## Not used

- `termux-shared` — MIT with GPL subtrees, 26.7k lines, and unnecessary: the
  two terminal modules are self-contained.
