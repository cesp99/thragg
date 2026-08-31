# Trademarks

The GPL grants copyright permissions. It grants **no trademark rights** —
GPLv3 s7(e) says so explicitly — so "it is in a GPL repository" answers
nothing about a logo. This file is the project's position on the marks that
appear in Seeker IDE: which ones, why they are there, and what would have to
change if an owner objected.

It is written to be handed to a lawyer, so it separates what we are
confident about from what is a judgement call.

## The short version

- **Roughly 64 third-party product logos** appear as file-type icons in the
  project panel. They are Zed Industries' redrawings, GPL-3.0-or-later, and
  they are used **nominatively** — to say "this file is a Rust file", never
  to identify Seeker IDE. Every IDE and file manager does this. We think the
  position is strong; it is still a judgement call and counsel should sign
  it off.
- **Three marks could not stay as they were. All three are now dealt with.**
  GitHub's Octocat is now Lucide's `cloud` and Zed's AI mark is now the
  hand-drawn sparkle, both under their original drawable names; and
  **Google's Android robot is no longer the launcher icon** — it was
  replaced with an original Eyed mark (a prompt chevron and a block cursor)
  in three purpose-drawn layers. No trademark blocker remains on the release
  checklist.
- **"Seeker" and "Solana" are somebody else's marks**, and the app is called
  Seeker IDE. That needs written permission in the OEM agreement, not an
  assumption.

## Marks used nominatively: the file-type icons

`app/src/main/res/drawable/ic_file_*.xml` — 79 icons, of which about 15 are
neutral pictograms (file, folder, code, terminal, database, image, audio,
video, font, book, info, lock, settings, diff) and the rest are product,
language and project marks: Docker's whale, GitLab's tanuki, the Rust gear,
Python's interlocked snakes, Java's cup, Swift's bird, Ruby's gem, React's
atom, PHP's elephant, OCaml's camel, Elixir's drop, and Go, Kotlin, Vue,
Astro, Bun, Nix, Zig, Gleam, Terraform, Helm, Heroku, Prisma, GraphQL,
ESLint, Prettier, Jupyter, Phoenix, Sass, Scala, Haskell, Erlang and more.

**Copyright is not the issue.** The artwork is Zed's own, licensed
GPL-3.0-or-later with the rest of Zed's assets, so redistributing it is
lawful and is covered in `docs/THIRD_PARTY.md`.

**The trademark position** is nominative fair use. The three conditions
usually asked of it are all met here:

1. The thing is not readily identifiable without the mark. A file-type icon
   whose job is to say "Dockerfile" at 16dp in a list has no non-mark
   alternative that a user reads as fast.
2. Only as much of the mark is used as needed. A monochrome 16dp glyph on a
   tinted grid, never the owner's wordmark, never their colours, never their
   full lockup.
3. Nothing suggests sponsorship or endorsement. The marks appear only in the
   file tree, never in the launcher icon, never in a store listing, never in
   the app's own identity, and `README.md`, `NOTICE` and the in-app licences
   screen all carry a non-affiliation disclaimer.

**Two things worth saying out loud rather than burying:**

- Several of these owners publish brand guidelines forbidding modification
  of their marks, and Zed's versions are stylised redraws. That is the same
  structural issue as the GitHub icon below, with far lower enforcement
  risk, because a 16dp file-type glyph is the canonical nominative use and
  the GitHub one is not.
- **Re-sourcing cannot fix this.** Lucide's published Brand Logos Statement
  says it does not accept brand logos and does not plan to, citing exactly
  this trademark risk, and points at Simple Icons instead. So there is no
  permissively-licensed generic set to swap to. Commissioning 64 original
  language logos produces either recognisable marks — the same position,
  now with Eyed's name on the drawing — or unrecognisable ones, which is a
  worse IDE.

**If counsel wants the exposure lower**, the lever is per-icon, not
wholesale. Replace the handful belonging to the most actively-enforcing
owners — Java (Oracle), Swift and Metal (Apple), Docker, Terraform and HCL
(HashiCorp), GitLab, Heroku (Salesforce) — with the generic
`ic_file_code` sheet. That is about seven entries in the `ZED_ICON_DRAWABLE`
table in `ZedFileIcons.kt` and no code change. Do **not** migrate all 64 to
Simple Icons: it is CC0, but it is a solid-fill 24px set drawn in each
brand's own style, it will not sit on the monochrome tinted 16dp grid, and
the icon mapping is generated from Zed so it would drift on the next sync.

## Marks that could not stay

Three, in descending order of how bad they would be to ship. All three are
now resolved. What each one *was* is kept below rather than deleted: a
compliance document that only records the current state cannot be audited,
and "was this ever shipped?" is the question a reviewer actually asks.


### 1. Google's Android robot — the launcher icon

**What was wrong.** `ic_launcher_background.xml`,
`ic_launcher_foreground.xml`, `mipmap-anydpi/ic_launcher*.xml` and all ten
`mipmap-*dpi/*.webp` were the unmodified Android Studio new-project
template: `#3DDC84` (Android brand green) with the guide grid, and the
bugdroid head as foreground *and* monochrome layer.

Two independent problems. The artwork is offered under CC BY 3.0 and we ship
no attribution, so even the permissive reading fails. And the robot is
Google's trademark; Google's brand guidelines do not permit it as or within
a third-party product's own logo. On a Solana Seeker it would read as Google
endorsement of a Solana device's preinstalled IDE.

**Resolved.** The launcher is now an original Eyed mark: a prompt chevron
and a block cursor — `>` and the cell a caret sits in — in bone (`#F4EFE3`)
and amber (`#F2A63C`) on flat deep ink slate (`#1E2434`). Both glyphs are
among the most generic in computing and carry nobody's trademark; nothing is
traced from or evokes the Android robot, Google green, Solana's logo or
gradient, or Zed's marks, and the amber is deliberate distance from all
three palettes.

It ships as **three** layers, not two: `ic_launcher_background.xml`,
`ic_launcher_foreground.xml` and a purpose-drawn
`ic_launcher_monochrome.xml` — the template's mistake was pointing
`<monochrome>` at the foreground, which flattens away the colour that
separates prompt from cursor. All ten `mipmap-*dpi/*.webp` are derived from
those same vectors by `tools/render-launcher-icon.py`, whose `--check` fails
if a bitmap drifts, and `LauncherIconTest` asserts the safe zone, the three
distinct layers, and that no attribute paints `#3DDC84`. Attribution is in
docs/THIRD_PARTY.md: *Original work, Copyright (C) 2026 Eyed,
GPL-3.0-or-later.*

The only remaining occurrence of the string `#3DDC84` anywhere under
`app/src/main/res/` is the line in `ic_launcher_background.xml`'s header
recording what was removed.

### 2. GitHub's Octocat — `ic_ui_github.xml`

Was imported from Zed's `github.svg`, which is the Octocat silhouette
redrawn as 1.2px strokes. GitHub's brand toolkit permits the *unmodified* logo in a
narrow set of uses — linking to GitHub, indicating an integration — and
states that no adaptation of its registered marks is allowed without written
permission. A stroke-outline redraw is that adaptation.

**Resolved.** The artwork is now Lucide's `cloud` (ISC), imported by
`tools/import-lucide-icons.py` under the same drawable name, so no Kotlin
moved. Both call sites — `BranchPicker.kt` (the remote host is github.com)
and `GitGraphPane.kt` — already print the remote's name in text, so the word
"GitHub" carries the meaning nominatively and no mark is needed. The
official Invertocat was deliberately *not* substituted: that would trade a
modification problem for a permission-scope problem on an OEM preinstall.
Renaming the resource to `ic_ui_remote` remains a tidy follow-up.

### 3. Zed's AI mark — `ic_ui_ai_zed.xml`

Zed Industries' own brand mark. Redistribution was lawful — it is
GPL-3.0-or-later with the rest of Zed's assets — but another company's
visual identity inside our own navigation is not something an OEM
preinstall should ship.

**Resolved, with one follow-up.** The mark is gone: `ic_ui_ai_zed.xml` now
draws the same hand-drawn sparkle as `ic_ui_agent.xml`, and its header
records what it used to hold and why. The file itself survives only because
`ui/workspace/Docks.kt:37` still names `R.drawable.ic_ui_ai_zed`, and
deleting a drawable ahead of its last caller breaks the build — so delete it
when `Docks.kt` goes and leave the Kotlin pointing at `ic_ui_agent`.
`ai_zed.svg` is out of `tools/import-zed-icons.py`, which no longer imports
chrome glyphs at all, and `tools/check-icon-provenance.py` runs on every
pull request (`.github/workflows/icons.yml`) to keep both marks out.

## The app's own name

The app is called **Seeker IDE** and its Solana layer is a selling point.
"Solana" and "Seeker" are Solana Labs / Solana Mobile marks. A preinstall
agreement does not implicitly convey a trademark licence, and GPL-3.0
certainly does not.

**Action:** get written trademark permission for the name in the OEM
agreement, naming the permitted form and what happens to it if the
arrangement ends. And say in `README.md` that a GPL fork may take the code
but not the name — that is normal, it is how Firefox and Chromium and
countless others work, and being upfront about it prevents a downstream from
getting it wrong.

## Non-affiliation

Seeker IDE is not affiliated with, sponsored by or endorsed by: Zed
Industries, Termux, Debian, GitHub, GitLab, Google, Docker, Oracle, Apple,
HashiCorp, Salesforce, the Rust Foundation, the Python Software Foundation,
Solana Labs, Solana Mobile, Anza, Anchor, VSCodium, or any other owner of a
mark that appears in this application. All trademarks are the property of
their respective owners.

This paragraph, or the shorter version of it, belongs in `README.md`, in
`NOTICE`, and on the in-app licences screen — see `docs/LICENSING.md` §5.
