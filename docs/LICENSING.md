# Licensing

This is the page an OEM reviewer or a lawyer asks for. It answers four
questions: what licence the thing you are holding is under, whether anything
in it conflicts, what source Eyed owes you and how you get it, and what has
to be true before this app is preinstalled on a phone.

[THIRD_PARTY.md](THIRD_PARTY.md) is the component register — where each file
came from. This file is about obligations. Where they disagree, they should
not; if they do, THIRD_PARTY.md is the one to correct, because it is
generated from the tree and this one is reasoned from it.

---

## 1. Who owns it and under what licence

**Copyright (C) 2026 Eyed** (Carlo Esposito, carlo@aploi.de).

**Eyed's own code is GPL-3.0-or-later.** That is what `core/Cargo.toml`
declares for the Rust workspace, what `CONTRIBUTING.md` accepts
contributions under, and what a per-file SPDX header should say.

**The application as built and distributed is GPL-3.0-only.** Termux's
`terminal-emulator` and `terminal-view` are GPL-3.0-*only*, they are Gradle
modules linked into the app, and their `libtermux.so` ships in the APK.
GPL-3.0-only into a GPL-3.0-or-later project is a fine *inbound*
combination; what it does is cap the *outbound* version, because no
recipient of the combined work may exercise the "or later" option over
Termux's code. So:

- Redistributing the APK, or a build of this repository: **GPL-3.0**.
- Taking Eyed's Kotlin or the Rust engine on their own, without the
  vendored Termux modules: **GPL-3.0-or-later**, as marked.

Say it that way in release notes and in the in-app notices. A downstream who
takes the APK at its word and redistributes under some future GPL would be
infringing Termux's copyright, and that is the sort of mistake a careless
notice causes.

`LICENSE` is the unmodified GNU GPL version 3 text and must stay unmodified
— it is a licence, not a template to fill in. The copyright statement lives
in [NOTICE](../NOTICE), and the same statement should appear in `README.md`,
in the About screen, and as a per-file SPDX header on Eyed-authored sources:

```
// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Eyed
```

The GPL does not require per-file headers — its "How to Apply These Terms"
appendix only recommends them, and s5(a) requires only that *modified* files
carry a notice of the change, which the `SEEKER PATCH` convention already
handles well for vendored code. The reason to add them here is practical:
this tree mixes Eyed's Kotlin, Zed crates under two different licences,
GPL-3.0-only Termux modules and generated icons, and today nothing in an
Eyed-authored file says which bucket it is in. Leave vendored files' own
headers alone.

---

## 2. The compatibility matrix

Shipped material only — anything that ends up inside the APK. Outbound is
GPL-3.0 (see above); every row below is compatible with it.

| Inbound licence | What it covers here | Into GPL-3.0? | Obligation the package must carry |
|---|---|---|---|
| **GPL-3.0-or-later** | Zed engine crates, Eyed's own code, `jni-bridge`, `trash-android` | Same licence | Licence text, source, change notices |
| **GPL-3.0-only** | Termux `terminal-emulator`, `terminal-view`, `libtermux.so` | Yes — **and it caps the outbound at v3** | Licence text, source, warranty disclaimer |
| **GPL-2.0-or-later** | `proot` (`libproot_exec.so`) | Yes, via the "or later" | Licence text, source or a written offer (s3(b)); it is a standalone executable, so mere aggregation as to the rest of the APK |
| **LGPL-3.0-or-later** | talloc 2.4.2, statically linked into `libproot_exec.so` only | Yes | LGPLv3 additional terms **and** the GPLv3 text they sit on; relinking information |
| **Apache-2.0** | 27 Zed crates, the whole Android runtime (Compose, AndroidX, Kotlin, media3, graphics-path), many crates.io deps | Yes — **not** into GPL-2, which is why targeting v3 matters | Licence text (s4(a)), attribution and any NOTICE (s4(d)) |
| **MPL-2.0** | `nucleo`, `nucleo-matcher`, `option-ext` | Yes, under s3.3 — Exhibit B checked and not asserted | Licence notice (s3.2) and access to the Source Code Form of those files |
| **MIT / MIT-OR-Apache-2.0 / Apache-2.0-OR-MIT** | the bulk of the crates.io closure, all tree-sitter grammars, `checker-qual`, the Ayu / Gruvbox / One palettes, Feather's icon heritage | Yes | Copyright notice and permission text must accompany binary distribution |
| **ISC** | Lucide, and two crates | Yes | Copyright notice and permission text |
| **BSD-2-Clause, BSD-3-Clause** | a handful of crates | Yes | Copyright notice, conditions, disclaimer |
| **Zlib, BSL-1.0, 0BSD, CC0-1.0, Unlicense-OR-MIT, Unicode-3.0, bzip2-1.0.6, Apache-2.0-WITH-LLVM-exception** | the permissive tail of the crate closure | Yes | Notice, where the licence asks for one |
| **SIL OFL 1.1** | IBM Plex Sans, Lilex | Yes | Licence text must be **bundled with the fonts** — already satisfied: both texts are in the APK at `assets/fonts/` |

**Nothing conflicts.** There is no CC-BY-NC, no "free for personal use", no
proprietary crate, and no crate in the linked closure whose licence could
not be determined. The compliance work here is attribution, notices and two
trademarks — not a copyleft conflict, and not a dependency that has to be
removed.

Two rows deserve their reasoning written down, so nobody has to redo it:

- **The three MPL-2.0 crates are safe.** MPL-2.0 s3.3 permits combining MPL
  files into a larger GPL work unless a file is marked "Incompatible With
  Secondary Licenses". All three `LICENSE` files were read: the string
  occurs only in the MPL's own Exhibit B boilerplate and is never applied to
  a source file.
- **proot is or-later, not only.** `tools/build-proot.sh` fetches
  termux/proot v5.1.107.91 with a pinned SHA-256; the Termux fork inherits
  proot-me/proot's "version 2 or (at your option) any later version". This
  is load-bearing: talloc is LGPL-3.0-or-later, and LGPL-3 into GPL-2-only
  would not have worked. With the "or later" the combination resolves at
  GPL-3.0-or-later.

**Not shipped, therefore not an obligation:** the Android Gradle Plugin,
JAXB, Bouncy Castle, and the EPL-2.0 / GPL-2.0-with-Classpath-Exception /
LGPL-2.1 artifacts that sit in the Gradle module cache. They are build
tooling and do not enter the APK. `org.json:json` is test-only and its POM
declares Public Domain, not the old GPL-incompatible "JSON License".

---

## 3. The source obligation, and how Eyed meets it

### What is owed

The APK conveys object code for GPL-3.0 code (Eyed's, Zed's, Termux's),
GPL-2.0-or-later code (proot) and LGPL-3.0-or-later code (talloc). Each
carries a source obligation, and each is discharged the same way.

GPLv3 s6 gives four routes for object code conveyed **in or with a physical
product** — which is exactly what a preinstall is:

- **s6(a)** — accompany it with the complete corresponding source on a
  durable medium.
- **s6(b)** — a written offer, valid **at least three years** and for as
  long as spare parts or customer support are offered for that product
  model, to give any third party the source at no more than the cost of
  physically performing the distribution.
- **s6(c)** — pass along someone else's s6(b) offer. Noncommercial only, so
  **not available to Eyed here**.
- **s6(d)** — network-server access. This is the "it's on GitHub" answer,
  and it discharges the obligation *only for object code conveyed from that
  same place*. A person who received the app preinstalled on a Seeker never
  visited GitHub, so **s6(d) does not reach them**.

GPLv2 s3 imposes the same three-year written-offer requirement on proot.

So the preinstall needs s6(a) or s6(b). Eyed does both.

### What "corresponding source" actually means

Not "the repository". GPLv3 defines Corresponding Source to include "the
scripts used to control compilation and installation", and the binaries in
this package are built from tarballs fetched at build time. A clone of this
repository is therefore **not** sufficient for `libproot_exec.so`, and
neither is "go get it from termux/proot", because their release assets can
move and their tarball is not the thing we built. The obligation runs
against Eyed.

The per-release archive, `seeker-ide-<version>-corresponding-source.tar.xz`,
contains:

1. This repository at the exact release tag.
2. The verbatim upstream archives, at the SHA-256s already pinned in
   `tools/build-proot.sh`: proot v5.1.107.91 and talloc 2.4.2.
3. `tools/proot-thread-execve.patch`, the `ashmem_memfd.c` change, and the
   hand-written talloc `config.h`.
4. `tools/build-proot.sh` itself, plus the recorded NDK version (from
   `gradle.properties`) and API level.
5. A `cargo vendor` of the Rust workspace's dependency closure — because
   crates.io versions can be yanked, and "cargo will fetch it" is not a
   guarantee three years out.
6. The Termux modules as vendored (already in the repository, but named here
   so the archive is self-describing).
7. This file, `NOTICE`, `LICENSE` and `THIRD_PARTY.md`.

GPLv3 s6's closing paragraph requires the source be in a publicly documented
format needing no password or key. Do not gate the archive behind an account
and do not encrypt it.

### The written offer

Published in [NOTICE](../NOTICE), in `README.md`, in the in-app licences
screen, and in whatever legal-notices surface the OEM ships:

> For a period of three years from the date you received this software, and
> for as long as Eyed offers spare parts or customer support for the product
> model it came on, Eyed will give any third party who possesses this object
> code access to copy the complete corresponding source for every GPL- and
> LGPL-licensed component in it, from a network server, at no charge.
>
> Eyed — carlo@aploi.de — https://github.com/cesp99/seeker-code

**There is deliberately no postal address, and its absence is not a gap.**
§6(b) offers a choice of two forms, and this offer takes the second:
"access to copy the Corresponding Source from a network server at no
charge". The first form — source on a durable physical medium — is what
needs an address to receive requests at, and we are not using it.

This is worth stating plainly because a reviewer skimming for an address
will not find one, and the reflex is to call that a defect. The distinction
that matters is §6(b) versus §6(d): **§6(d) alone would not be enough
here.** It covers source offered from the same place the object code was
downloaded from, and someone who received this app preinstalled on a phone
downloaded it from nowhere. §6(b)(2) is what reaches them, and it is an
offer that travels with the product rather than a URL they never visited.

Keep the archive online and the offer honoured for **three years after the
last Seeker unit ships**, not three years after the release.

### One URL, kept correct

The offer, the notices screen, `README.md`, `THIRD_PARTY.md` and the release
archive must all name the **same** public repository URL, byte for byte, and
Eyed must control it for the life of the offer. Today the tree disagrees
with itself: `core/Cargo.toml` says `github.com/cesp99/SeekerCodebyEyed` and
`.github/ISSUE_TEMPLATE/config.yml` says `github.com/cesp99/seeker-code`.
Neither is obviously the final home. A URL that moves after the phones ship
is not a broken link, it is a compliance failure.

### Installation Information (GPLv3 s6, User Products)

A Seeker is a User Product, and this app is conveyed in a transaction that
transfers possession of it, so s6 also requires **Installation Information**
— what a user needs to install and run a modified version built from
modified corresponding source on that device.

What it does **not** require, stated plainly because this is where the topic
usually goes wrong: it does not require unlocking the bootloader, does not
require publishing Eyed's signing key, does not require supporting modified
builds, and does not make the rest of the phone Eyed's problem.

Where it bites is the *shape* of the preinstall. If the app lands in
`/system/priv-app`, is platform-signed, or holds signature-level
permissions, then a user cannot install a modified build that works the
same way — and that is precisely the case s6 addresses.

**The good news is that this repository is already in the easy
configuration**, and the work is to keep it there. `AndroidManifest.xml`
declares two permissions, `FOREGROUND_SERVICE` and `POST_NOTIFICATIONS`,
both normal/runtime and neither signature-level, and there is no signature
check, attestation gate or "unofficial build" refusal anywhere in the app.
So the compliant answer is available; it just has to be chosen in the OEM
agreement and then written down. See
[INSTALLATION_INFORMATION.md](INSTALLATION_INFORMATION.md).

---

## 4. The notices bundle

Today the APK ships five licence-ish files: two font licences, the icon
licences, the theme licences, and five stray `META-INF/androidx/*/LICENSE.txt`
that AAR packaging happened to carry in. That is not enough for a package
that statically links 471 Rust crates and ships two GPL binaries. MIT,
BSD-2/3, ISC and Zlib all require their notice to accompany a **binary**
distribution; Apache-2.0 s4(a) and s4(d) require the licence text and
attribution; GPLv3 s4 requires a copy of the GPL with the Program; GPLv2 s1
requires the same for proot. THIRD_PARTY.md's old answer — that the licences
"travel in the crate sources cargo fetches" — is true of a developer's
`~/.cargo` and false of the artifact a user holds.

**Generate it. Do not hand-maintain it.** A hand-curated list of 471 crates
is a list that is wrong within one sprint.

### Shape

A Gradle task produces `app/src/main/assets/licenses/components.json` plus
the verbatim licence texts beside it, and **fails the build** if the
generated file differs from the committed copy. Run it in CI on every PR, so
adding a dependency without a notice cannot merge.

Three inputs:

1. **The Rust closure.** `cargo about generate` (or `cargo-license`), run
   with the *release target and feature set* —
   `--target aarch64-linux-android`, `-p jni-bridge` — not over
   `core/Cargo.lock`. This distinction is not pedantry: `Cargo.lock` has 834
   entries and resolves `resvg`, `usvg` and `freetype-sys`, none of which
   link, because `gpui` is taken with `default-features = false` and those
   three sit behind its off-by-default `images` feature. Running against the
   target proves their absence instead of arguing it.
2. **The Maven closure.** Cash App's `licensee`, or
   `com.jaredsburrows.license`, over the **release runtime** classpath — so
   the AGP, JAXB and Bouncy Castle build-tooling entries stay out.
3. **A checked-in manifest** for what neither tool can see: the vendored
   Termux modules, proot, talloc, the two fonts, the themes and their
   palettes, the icon sets, the tree-sitter query files, and Eyed's own
   entry.

### Texts to ship verbatim

`app/src/main/assets/licenses/` — taken from spdx.org/licenses or gnu.org,
never from a blog or a paste:

```
GPL-3.0.txt          GPL-2.0.txt         LGPL-3.0.txt
Apache-2.0.txt       MIT.txt             ISC.txt
BSD-2-Clause.txt     BSD-3-Clause.txt    MPL-2.0.txt
Zlib.txt             BSL-1.0.txt         Unicode-3.0.txt
OFL-1.1.txt
```

LGPL-3.0 needs the GPL-3.0 text beside it — LGPLv3 is a set of additional
permissions on top of GPLv3, not a standalone licence.

### Notices that are wrong today

- `app/src/main/assets/themes/LICENSES.txt` carried
  `Copyright (c) <YEAR> <COPYRIGHT HOLDER>` for all six Gruvbox entries —
  the unfilled MIT template, inherited from Zed, which inherited it from
  morhetz/gruvbox. MIT's single condition is that the copyright notice be
  included, and a notice naming nobody does not satisfy it. Now filled with
  the holder. **The year is still missing** and should be added once
  confirmed against morhetz/gruvbox's own `LICENSE.md`; a guessed year in a
  shipped notice is worse than none.
- `app/src/main/assets/icons/LICENSES.txt` was Lucide's ISC text only,
  copied from Zed. Lucide's notice *references* Feather but does not
  reproduce Feather's MIT permission text, and THIRD_PARTY.md cited this
  file as where Feather's licence lives. **Fixed.** The chrome icons are now
  imported from Lucide 1.37.0 directly by `tools/import-lucide-icons.py`,
  which writes `lucide-LICENSE.txt` (upstream's LICENSE verbatim, including
  the Feather-derived icon list and Feather's MIT text in full) and
  regenerates `LICENSES.txt` as the aggregate notice for both icon sets.
  Still outstanding: nothing in the app *reads* either file — see §5.
- `vendor/terminal-view/LICENSE.md` said "Everything under `src/` is
  upstream code, unmodified." It is byte-identical to
  `terminal-emulator`'s, where the statement is true; in `terminal-view` it
  is false, because `TerminalView.java` carries three `SEEKER PATCH` hunks
  for the search highlight. `vendor/VENDOR.md` documents them properly and
  the code is marked in place, so GPLv3 s5(a) is met in substance — but a
  false statement in the file whose whole job is to be true is not something
  to leave in a shipping package. Corrected.

---

## 5. The in-app "Open source licences" screen

**Built.** `ui/shell/licences/` — `LicenceCatalog.kt` (the model, the parser
and the filter, all pure and unit-tested), `LicencesScreen.kt` (the list and
the legal header) and `LicenceDetailScreen.kt` (one component and its full
text). `docs/UI.md` owns the shell; this section owns what the screen must
contain and where its content comes from, and it is still the specification
that section is checked against.

An OEM preinstall is normally expected to expose every component and its
full licence text from inside the app, **offline**, within a couple of taps,
without a project open. Nothing of the kind exists today: `AboutDialog.kt`
shows a version string and copyable system specs, and grepping the Kotlin
sources for "licen" finds nothing that renders a notice. The four asset
files that *are* in the APK are unreachable, which means that in the sense
that matters we have not shipped them.

### Where it lives

A new `Route` in `ui/shell/RouteStack.kt` — `data object Licences : Route`
— pushed over the current destination, keeping the nav bar, owning a `←` in
its own top row, exactly like `Settings`. It does not hide the nav bar.

Two entry points, both existing surfaces:

- **Settings → ADVANCED → "Open source licences"**, a `LinkRow` between
  "Edit settings.json" and "About this device". Detail line: *"what this app
  is built from, and the source offer"*.
- **A row in `AboutDialog`**, so someone who went looking for the version
  finds the notices too.

### Structure

List → detail, both scrolling, no search field (the list is long but it is
grouped, and a filter field on a compliance screen is chrome).

**As built there is one deviation from the paragraph above**, recorded here
rather than argued twice: the list carries a filter field. "No search field"
was written before the inventory was generated and turned out to be **615
rows**, of which 471 are crates; scrolling to `unicode-ident` past all of
them is hunting, not reading. The field filters the rows the screen already
shows, over name, version, SPDX id, copyright holder and group, and the
screen with an empty field is exactly the screen this section describes —
header, groups and every row — so the concern behind the rule, that a
compliance screen hide its contents behind a search box, does not arise.

**The list screen** opens with a header block carrying, verbatim, the four
things GPLv3 s0 calls Appropriate Legal Notices — s5(d) makes displaying
them a condition for an interactive program, and today none of the four
appears anywhere in the app. As string resources, so they translate:

```
Seeker IDE — Copyright (C) 2026 Eyed
This program comes with ABSOLUTELY NO WARRANTY.
This is free software, and you are welcome to redistribute it
under the terms of the GNU General Public License, version 3.
                                        [ View the GPL v3 ]  >
```

Then, still in the header: the source URL with the release tag and commit,
and the full written source offer from §3.
Both selectable text — a reviewer will want to copy them.

Then the component groups, in this order:

1. **This application** — Seeker IDE, GPL-3.0-or-later (as distributed:
   GPL-3.0), Copyright (C) 2026 Eyed. With the fork lineage in one sentence:
   a fork of Conquest Code, which reuses Zed and vendors Termux.
2. **Engine and terminal** — Conquest Code; Zed crates split into their
   GPL-3.0-or-later and Apache-2.0 sets at commit `bc538de`; Termux
   `terminal-emulator` and `terminal-view` (GPL-3.0-only) with the Android
   Terminal Emulator Apache-2.0 heritage note.
3. **Native binaries** — proot v5.1.107.91 (GPL-2.0-or-later); talloc 2.4.2
   (LGPL-3.0-or-later, with both LGPLv3 and GPLv3 reachable).
4. **Rust crates** — one row per crate in the linked closure, name, version,
   SPDX id; tapping opens that crate's licence text.
5. **Android libraries** — AndroidX, Compose, Material 3, Kotlin, media3,
   graphics-path, jspecify, checker-qual.
6. **Fonts, themes and icons** — IBM Plex Sans and Lilex (OFL-1.1); the Ayu,
   Gruvbox and One palettes with their MIT holders; Lucide 1.37.0 (ISC) and
   the Feather subset within it (MIT, © Cole Bemis) for the chrome icons;
   Zed's file-type icons (GPL-3.0-or-later), with the trademark note that
   goes with them. The two Eyed-drawn originals, `ic_ui_agent` and
   `ic_stat_terminal`, get their own line so the boundary between Eyed's
   work and everyone else's is legible.
7. **Syntax grammars** — every tree-sitter grammar and query set with its
   holder.

Each row: **name · version-or-commit · SPDX id**. Each detail screen:
copyright holder, upstream URL, and the **full** licence text — not a
summary, not a link. The screen must work with the radio off.

The list closes with the trademark line:

> Product names and logos shown beside file types are trademarks of their
> respective owners and are used only to identify file types. Seeker IDE is
> not affiliated with or endorsed by them.

### Where the content comes from

Everything is read from `assets/licenses/`, which is produced by the
generator in §4:

- `assets/licenses/components.json` — the rows. Shape:
  `{ name, version, spdx, copyright, url, licenseFile }`, grouped.
- `assets/licenses/*.txt` — the verbatim licence texts, referenced by
  `licenseFile`.
- The existing `assets/fonts/*-LICENSE.txt`, `assets/icons/LICENSES.txt` and
  `assets/themes/LICENSES.txt` are folded in by the generator's manifest
  rather than special-cased in Kotlin.

No network, no string constants in Kotlin, nothing hard-coded. A dependency
added without a notice must break the build (§4), never quietly produce an
incomplete screen. The same `components.json` generates the root `NOTICE`
body, so the two cannot drift.

---

## 6. Shipping preinstalled: the checklist

Ordered. Everything in the first block is a hard stop for a public GPL
release or an OEM submission.

### Blockers

- [x] **Replace the launcher icon.** Was the unmodified Android Studio
      template — Google's Android robot on Android brand green, in both
      adaptive vectors, both `mipmap-anydpi` XMLs and all ten `.webp`. The
      robot artwork is CC BY 3.0 and we shipped no attribution; the robot is
      also Google's trademark and its brand guidelines do not permit it as a
      third-party product's identity. Now an original Eyed mark — a prompt
      chevron and a block cursor — in **three** purpose-drawn layers
      (`ic_launcher_background`, `_foreground`, `_monochrome`), with the ten
      bitmaps derived from those vectors by `tools/render-launcher-icon.py`
      and guarded by its `--check` and by `LauncherIconTest`. See
      docs/TRADEMARKS.md §1 and docs/THIRD_PARTY.md.
- [x] **Replace `ic_ui_github.xml`.** Was a stroke redraw of GitHub's mark,
      which GitHub's brand terms do not permit. Now Lucide's `cloud`, under
      the same drawable name; both call sites already show the remote's name
      in text, so nothing was lost. Renaming the resource to `ic_ui_remote`
      is a tidy-up, not a blocker.
- [x] **Remove Zed's AI brand mark from `ic_ui_ai_zed.xml`.** The artwork is
      gone — the file now draws the same hand-drawn sparkle as
      `ic_ui_agent.xml`. The *file* could not be deleted yet because
      `ui/workspace/Docks.kt:37` still names it and deleting a drawable
      ahead of its last caller breaks the build. `ai_zed.svg` is out of
      `tools/import-zed-icons.py`, which no longer imports chrome glyphs at
      all, and `tools/check-icon-provenance.py` runs in CI
      (`.github/workflows/icons.yml`) so neither mark can return.
- [ ] **Delete `ic_ui_ai_zed.xml` with `Docks.kt`.** Follow-up to the above:
      when the panel switcher goes, delete the file and point the Kotlin at
      `ic_ui_agent`; `grep -rn ic_ui_ai_zed app/src` must then be empty.
      `ic_ui_file_tree` loses its last caller in the same change.
- [x] **The written offer** is settled: §6(b)(2) network-server access,
      contact `carlo@aploi.de`, source at `github.com/cesp99/seeker-code`.
      No postal address is required in this form — see §3.
- [x] **Ship the notices bundle** (§4) and the licence texts, and surface
      them from the licences screen (§5). Done: `tools/gen-licenses.py`
      writes `app/src/main/assets/licenses/components.json` (615 components —
      471 crates, 110 Maven modules, 34 hand-maintained rows) from cargo,
      Gradle and `tools/licenses/manifest.jsonc`; the nineteen verbatim texts
      sit beside it with their provenance and SHA-256s in `SOURCES.txt`; and
      `ui/shell/licences/` reads both, offline.
- [x] **Add the four Appropriate Legal Notices** to About / the licences
      screen. Done: they are the header of the licences screen, as string
      resources (`licences_notice_*`), with the source URL, the release, the
      Zed commit and the full written offer under them.
- [ ] **Build and publish the corresponding-source archive** (§3) for the
      first release, and confirm it builds `libproot_exec.so` byte-for-byte
      on a clean machine.
- [ ] **Settle the repository URL** and make it identical everywhere.
- [ ] **Settle Spettro's licence.** It is Eyed's own binary, auto-installed
      by default, and its licence is recorded nowhere in this tree.

### Before the OEM agreement is signed

- [ ] **Install location and signing**: preinstalled as an ordinary
      user-updatable app (`/system/app` or `/product/app`), **not**
      platform-signed, **no** signature-level permissions, and a user-signed
      APK of the same package can be installed. This is what makes the
      GPLv3 s6 Installation Information a one-page document instead of a
      hard problem.
- [ ] **Trademark permission for the name.** "Solana" and "Seeker" are
      Solana Labs / Solana Mobile marks and GPL-3.0 grants no trademark
      rights, so a preinstall deal does not implicitly convey the name. Get
      the permitted form of the name, and what happens to it if the
      arrangement ends, in writing.
- [ ] **Ask Solana Mobile, in writing, whether the preinstall is a dApp
      Store release or a system-image inclusion.** They are different
      conveying paths with different s6 consequences, and the answer decides
      the two items above.
- [ ] **A trademark read on the ~64 third-party logos** in
      `ic_file_*.xml`. Nominative use is the defence and every IDE does
      this; the call belongs to counsel. See [TRADEMARKS.md](TRADEMARKS.md)
      for the position and the per-icon fallback.

### Documentation and hygiene, roughly an hour each

- [ ] Add `license = "Apache-2.0"` to `core/vendor/gpui_shared_string` and
      `gpui_util`, and `license = "GPL-3.0-or-later"` to
      `core/vendor/grammars` and `language_core`, each marked
      `SEEKER PATCH`. *(Deliberately not done in the paperwork pass: editing
      a vendored `Cargo.toml` invalidates cargo fingerprints and would
      collide with a build in flight.)*
- [ ] Record `core/vendor/assets/`'s licence in `core/vendor/VENDOR.md`
      rather than dropping a `LICENSE-GPL` into it — the `settings` crate
      embeds that directory with `#[folder = "../assets"]`, so a licence
      file there would be compiled into `libseekercore.so`.
- [ ] Add provenance headers to `core/vendor/grammars/src/sql/highlights.scm`
      (DerekStride v0.3.11, MIT) and the `src/html/*` set (Zed,
      GPL-3.0-or-later).
- [ ] Add the Gruvbox copyright **year** to
      `app/src/main/assets/themes/LICENSES.txt` once confirmed upstream.
- [x] Refresh `app/src/main/assets/icons/LICENSES.txt` from a pinned Lucide
      tag and add Feather's MIT text. Done, and now generated rather than
      hand-maintained: `tools/import-lucide-icons.py` writes it from the
      snapshot vendored at `tools/lucide/`.
- [ ] Confirm the Zed commit `bc538def45` and the Termux commit
      `3df69d1d…` both resolve in the public upstream repositories, and drop
      "(local checkout)" from `core/vendor/VENDOR.md`.
- [ ] Add SPDX headers to Eyed-authored sources. Consider the
      [REUSE](https://reuse.software) specification with a `reuse lint` CI
      check — it turns an OEM provenance question into a command someone can
      run.
- [x] Add a CI job that runs the notices generator and fails on drift (§4).
      `.github/workflows/licences.yml`, plus
      `./gradlew :app:verifyLicenceAssets` for the same check locally.
- [ ] Make `tools/build-proot.sh` assert that every patch it applies has an
      entry in `THIRD_PARTY.md`.

### Deliverables an OEM will ask for, and where each comes from

| Asked for | Where it comes from |
|---|---|
| Bill of materials (component, version, licence, source URL) | `assets/licenses/components.json`, §4 |
| Licence texts as shippable files | `assets/licenses/*.txt`, §4 |
| Written source offer | [NOTICE](../NOTICE), §3 |
| Corresponding-source archive | Per-release, §3 |
| Statement that no GPL-incompatible code is present | §2, the matrix |
| In-app notices screen | §5 |
| Installation Information | [INSTALLATION_INFORMATION.md](INSTALLATION_INFORMATION.md) |
| Trademark position | [TRADEMARKS.md](TRADEMARKS.md) |
| Named security contact and disclosure process | [SECURITY.md](../SECURITY.md) |
| Inbound licensing provenance for contributions | [CONTRIBUTING.md](../CONTRIBUTING.md), the DCO |
