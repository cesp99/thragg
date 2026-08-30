# Third-party code and binaries

Seeker IDE is GPL-3.0-or-later. This file records everything that comes
from somewhere else, where it came from, and under what licence — including
the components shipped as **compiled binaries**, which carry a source
obligation we intend to meet properly.

## Source vendored into this repository

| Component | Upstream | Version / commit | Licence | Where |
|---|---|---|---|---|
| Zed engine crates | [zed-industries/zed](https://github.com/zed-industries/zed) | `bc538de` | GPL-3.0-or-later, some Apache-2.0 | `core/vendor/` — see `core/vendor/VENDOR.md` |
| Termux `terminal-emulator`, `terminal-view` | [termux/termux-app](https://github.com/termux/termux-app) | `3df69d1` (v0.118.0) | GPL-3.0-only, with an Apache-2.0 heritage from [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator) | `vendor/` — see `vendor/VENDOR.md` |
| Zed themes (One, Ayu, Gruvbox — 11 themes in 3 family files) | zed-industries/zed | `bc538de` | The theme *files* are GPL-3.0-or-later with Zed; the palettes they carry are the upstream authors' — Ayu is MIT (© Ike Ku), Gruvbox is MIT (© Pavel Pertsev). Both licence texts travel with them. | `app/src/main/assets/themes/`, licences at `app/src/main/assets/themes/LICENSES.txt` |
| IBM Plex Sans (Zed's UI face) | [IBM/plex](https://github.com/IBM/plex), via Zed's `assets/fonts/` | `bc538de` | SIL Open Font License 1.1 | `app/src/main/res/font/ibm_plex_sans_*.ttf`; licence at `app/src/main/assets/fonts/IBMPlexSans-LICENSE.txt` |
| Zed's file-type icons (79, converted to Android vector drawables by `tools/import-zed-icons.py`) | zed-industries/zed | `bc538de` | GPL-3.0-or-later with Zed; the set derives from [Lucide](https://lucide.dev) (ISC, © Lucide Contributors) and Feather (MIT, © Cole Bemis), whose licence text is at `app/src/main/assets/icons/LICENSES.txt` | `app/src/main/res/drawable/ic_file_*.xml` |
| Lilex (Zed's editor face) | [mishamyrt/Lilex](https://github.com/mishamyrt/Lilex), via Zed's `assets/fonts/` | `bc538de` | SIL Open Font License 1.1 | `app/src/main/res/font/lilex_*.ttf`; licence at `app/src/main/assets/fonts/Lilex-LICENSE.txt` |
| Dockerfile tree-sitter grammar (generated `parser.c` / `scanner.c`) | [camdencheek/tree-sitter-dockerfile](https://github.com/camdencheek/tree-sitter-dockerfile) | v0.2.0 | MIT (© Camden Cheek) | `core/crates/tree-sitter-dockerfile/grammar/`; licence beside it at `core/crates/tree-sitter-dockerfile/LICENSE` |

### Tree-sitter queries

Zed's own grammars carry Zed's `highlights.scm`, `indents.scm`,
`outline.scm`, `brackets.scm`, `overrides.scm` and `injections.scm`, which
are GPL-3.0-or-later with Zed and vendored at the commit above. The grammars
this project added on top of Zed's built-in set — Zed reaches them through
extensions, which this app has no runtime for — have no Zed source in the
checkout, so their queries come from elsewhere. Every file names its own
provenance in a comment at the top; this is the summary.

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
`core/Cargo.toml`; they are compiled into `libseekercore.so` and their
licences (all MIT) travel in the crate sources cargo fetches.

## Binaries shipped in the APK

These are **compiled by us** from published sources, by
`tools/build-proot.sh`, which fetches each tarball and verifies its SHA-256
before building. They appear in the `full` edition only.

| Binary | Source | Version | Licence |
|---|---|---|---|
| `libproot_exec.so` | [termux/proot](https://github.com/termux/proot) | v5.1.107.91 | GPL-2.0-or-later |
| (linked into the above) talloc | [samba.org](https://download.samba.org/pub/talloc/) | 2.4.2 | LGPL-3.0-or-later |

We use Termux's fork of proot rather than
[proot-me/proot](https://github.com/proot-me/proot) because upstream's
guests are killed with `SIGSYS` on current Android; the fork carries the
fixes.

**Local modifications**, applied by the build script and marked in the
source with `SEEKER PATCH`:

- `src/extension/ashmem_memfd/ashmem_memfd.c` — add `#include <string.h>`.
  It uses `strcmp` and `memset` without declaring them, which clang 18 and
  later reject.

talloc is built from a hand-written `config.h` rather than its own `waf`
build system, which does not cross-compile comfortably; the values are in
`tools/build-proot.sh` and every one of them is true on Android's bionic.

### Source offer

The GPL requires that anyone receiving these binaries can get their exact
source. Running `tools/build-proot.sh` reproduces them from the upstream
tarballs named above, at the pinned versions, with the single patch listed
here. Anyone who wants the corresponding source can therefore obtain it
from those upstreams plus this repository; if you would rather receive a
tarball, open an issue and we will provide one.

## Downloaded at runtime

The `full` edition downloads a Debian base filesystem the first time you
ask for the Linux userland. It is **not** part of this project and is not
redistributed by us:

- **Debian** `stable-slim`, pulled from Debian's official container image
  and verified against the digest the registry publishes. Debian is a
  collection of works under many licences; see
  `/usr/share/doc/*/copyright` inside the installed rootfs.
- Anything you then install with `apt` comes from Debian's own
  repositories, under its own terms.

## Not used

- `termux-shared` — MIT with GPL subtrees, 26.7k lines, and unnecessary:
  the two terminal modules are self-contained.
