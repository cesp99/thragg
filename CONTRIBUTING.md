# Contributing to Seeker IDE

Thanks for your interest! Seeker IDE is an open-source (GPL-3.0)
attempt to bring a Zed-class IDE to Android. Contributions of all kinds
are welcome: code, docs, testing on real foldables/tablets, theme and
grammar work.

## Ground rules

- **License**: all contributions are accepted under GPL-3.0-or-later
  (see `LICENSE`). Code copied or adapted from other projects must be
  GPL-3.0-compatible and attributed in the commit message and in
  `README.md`'s credits section.
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

## Workflow

1. Fork, branch from `master`.
2. `./gradlew assembleDebug` must pass (see `docs/BUILDING.md`).
3. `cd core && cargo test && cargo clippy` must pass.
4. Open a PR with a clear description of what and why.

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
