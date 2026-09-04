# Contributing to Thragg

Thanks for your interest! Thragg is an open-source (GPL-3.0)
attempt to bring a Zed-class IDE to Android. Contributions of all kinds
are welcome: code, docs, testing on real foldables/tablets, theme and
grammar work.

## Ground rules

- **Licence**: all contributions are accepted under GPL-3.0-or-later
  (see `LICENSE`), and every commit must carry a `Signed-off-by:` line.
  See **Inbound licensing** below — this is the one ground rule with a
  mechanical check behind it. Code copied or adapted from other projects
  must be GPL-3.0-compatible and attributed in the commit message, in
  `docs/THIRD_PARTY.md` and in `README.md`'s credits section.
- **No self-integrity checks.** Nothing in this app may compare its own
  signature, call Play Integrity or SafetyNet, or refuse to run because
  it is an "unofficial build". That is not a style preference: it is
  what keeps the GPLv3 s6 Installation Information obligation a
  one-page document when the app ships preinstalled
  (`docs/INSTALLATION_INFORMATION.md`).
- **Architecture**: the split is strict — everything UI-free lives in
  the Rust engine (`core/`), everything visual/platform lives in Kotlin
  (`app/`). Don't put editor logic in Kotlin or Android types in Rust.
  The JNI boundary (`core/crates/jni-bridge` ↔ `CoreBridge.kt`) stays
  coarse-grained; both files must change together.
- **Performance is a feature.** No blocking calls on the main thread,
  no per-keystroke JNI chatter, no dropped frames while typing. When in
  doubt, measure on a mid-range device.
- **Privacy**: no telemetry, no analytics, no network calls the user
  didn't ask for. Ever.

## Inbound licensing

Thragg is going to ship preinstalled on a phone. That means someone
will eventually ask us to prove that every line in it was contributed by
someone who had the right to contribute it. So contributions are
governed by the **Developer Certificate of Origin** — the same mechanism
the Linux kernel and Git use.

The DCO is deliberately *not* a CLA. You assign nothing to Eyed, you
keep your copyright, and you are not signing a contract. You are stating
that you wrote the patch, or that you got it from somewhere that permits
this, and that you are happy for it to be public under GPL-3.0-or-later.

### How to sign off

Add `-s` to your commit:

```
git commit -s -m "editor: keep the caret visible while wrapping"
```

which appends one line:

```
Signed-off-by: Your Name <your.email@example.com>
```

Use your real name and a real address. `git config user.name` and
`user.email` set it once. Every commit in a PR needs the line; if you
forget, `git rebase --signoff <base>` fixes a branch in one command.

### What you are certifying

By signing off you certify version 1.1 of the Developer Certificate of
Origin, reproduced here in full so it is in this repository rather than
behind a link:

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

### Bringing in someone else's code

Clause (b) is the one that matters most here, because this project
already vendors a lot. If your patch contains code you did not write:

1. It must be **GPL-3.0-compatible**. `docs/LICENSING.md` §2 has the
   matrix; if the licence is not on it, ask before you write the code
   rather than after.
2. **Keep the upstream notices.** Copyright headers, `LICENSE` files,
   attribution comments — all of it travels with the code. Dropping the
   original notices is the single most common GPL violation there is,
   and it is not one this project intends to commit.
3. **Mark your changes.** Vendored files carry `// THRAGG PATCH:` on
   every hunk we added, and the change is listed in the relevant
   `VENDOR.md`. Follow that convention; it is what GPLv3 s5(a) asks for
   and it is how the next person knows what is ours.
4. **Add a row to `docs/THIRD_PARTY.md`** — component, upstream,
   version or commit, licence, where the licence text lives. A PR that
   adds a dependency and not its register entry is incomplete.

Generated artefacts count too: an icon converted from someone's SVG, a
tree-sitter query adapted from a grammar's `queries/`, a theme palette.
Each gets a provenance comment at the top of the file and a line in the
register.

### Why the DCO and not a CLA

A CLA would let Eyed relicense the project later — for a proprietary
OEM variant, say. We are choosing not to have that option, because
asking contributors to sign a contract is a real cost and the option is
one we do not want to exercise. The consequence is worth stating: with
no CLA, **nobody can relicense Thragg without every contributor's
agreement**, Eyed included. That is the point.

Retrofitting a CLA across an existing contributor set is close to
impossible, so this decision is effectively permanent from the first
external commit.

### Anything with an unclear licence

Do not send it. Open an issue describing what you want to bring in and
where it came from, and we will work out whether it can be used before
anyone writes code against it. A patch that has to be reverted for
provenance reasons is worse for everyone than a question.

## Workflow

1. Fork, branch from `master`.
2. `./gradlew assembleStandardDebug` must pass (see `docs/BUILDING.md`).
3. `cd core && cargo test && cargo clippy` must pass.
4. Every commit is signed off (`git commit -s`) — CI checks it.
5. Open a PR with a clear description of what and why.

## Translations

The shell's user-visible strings — the settings screen, the project
picker, the dialogs, the ☰ menu, the welcome screen, About — live in
`app/src/main/res/values/strings.xml` and are read through
`stringResource(R.string.…)`. Adding a language is one file:

1. Create `app/src/main/res/values-<code>/strings.xml`, where `<code>` is
   the BCP-47 language tag Android expects — `values-de`, `values-fr`,
   `values-pt-rBR`.
2. Copy the `<string>` elements you want to translate from
   `values/strings.xml` and translate the text, keeping the `name`
   attributes exactly as they are. Anything you leave out falls back to
   English automatically, so a partial translation is a useful one.
3. Keep the format placeholders (`%1$s`) and their order. Escape an
   apostrophe as `\'` and an `&` as `&amp;`, which is Android's rule,
   not ours.

Three things are deliberately **not** in `strings.xml`:

- **Editor content**: file names, buffer text, diagnostics from a
  language server, terminal output, git output. Those are data, and a
  translated file path is a broken one.
- **Zed's action names.** The command palette lists `workspace::Save` as
  "workspace: save" because that is the name in `keymap.json` and in
  Zed's own documentation. Translating it would break the one string a
  user has to type to bind a key.
- **Example commands** in a form's placeholder (`--acp`,
  `/usr/local/bin/agent`). Those are code.

Some strings that interpolate a value are still Kotlin literals; move
one into `strings.xml` with a `%1$s` placeholder when you touch it, the
way `unsaved_title` was done.

## Code style

- Rust: `cargo fmt` defaults, clippy-clean.
- Kotlin: official Kotlin style, Compose idioms (state hoisting,
  unidirectional data flow).
