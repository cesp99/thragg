# Vendored code

Crates copied from the Zed repository, per the vendoring decision in the
project docs (vendor, not git-deps). Licenses: each crate directory
carries its upstream LICENSE file (GPL-3.0-or-later or Apache-2.0);
upstream copyright headers are preserved.

Copyright in these crates is Zed Industries, Inc.'s. This table is the
per-crate record; `docs/THIRD_PARTY.md` is the project-wide register and
`docs/LICENSING.md` is the obligation analysis.

Two exceptions to "each directory carries its LICENSE file", recorded here
so a per-directory scan has an answer:

- `assets/` has no LICENSE file. It holds Zed's repo-root
  `assets/settings/default.json` and is **GPL-3.0-or-later with Zed**, like
  the rest of Zed's assets. Do not fix this by dropping a `LICENSE-GPL` into
  the directory: the `settings` crate embeds it whole with
  `#[folder = "../assets"]`, so the licence text would be compiled into
  `libthraggcore.so`. This paragraph is the record.
- `gpui_shared_string`, `gpui_util`, `grammars` and `language_core` have
  their LICENSE files but declare no `license` field in `Cargo.toml`, so an
  SPDX scanner reports them as unlicensed packages inside the shipped
  binary. Upstream Zed has the same gap. The values are
  `Apache-2.0`, `Apache-2.0`, `GPL-3.0-or-later`, `GPL-3.0-or-later`
  respectively; adding them is on the release checklist in
  `docs/LICENSING.md` and each should be marked `THRAGG PATCH` so the next
  sync does not revert it.

## Upstream

- Source: https://github.com/zed-industries/zed
- Commit: `bc538def45` (local checkout, 2026-08-15)
- Toolchain upstream targets: rustc 1.97.1, edition 2024 (matches this
  workspace).

## Crates

Two tiers, vendored in two passes.

**Tier 0 — the UI-free text stack** (phase 1). These have no gpui
dependency outside their tests.

| Crate | License | Notes |
|---|---|---|
| `sum_tree` | Apache-2.0 | patched (see below) |
| `rope` | GPL-3.0-or-later | patched (see below) |
| `text` | GPL-3.0-or-later | unpatched |
| `clock` | GPL-3.0-or-later | patched (see below) |
| `collections` | Apache-2.0 | unpatched |
| `util` | Apache-2.0 | patched (see below) |
| `util_macros` | Apache-2.0 | unpatched |
| `path` | Apache-2.0 | unpatched |
| `gpui_shared_string` | Apache-2.0 | unpatched |
| `gpui_util` | Apache-2.0 | unpatched |
| `grammars` | GPL-3.0-or-later | patched — ten grammars added (see below); feature `load-grammars` compiles the embedded tree-sitter C grammars (works for Android via cargo-ndk) |
| `language_core` | GPL-3.0-or-later | unpatched |
| `zlog` | GPL-3.0-or-later | unpatched |
| `ztracing` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `ztracing_macro` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `perf` | Apache-2.0 | from `zed/tooling/perf`; patched (see below) |

**Tier 1 — the gpui-coupled runtime** (phase 3, P3-2). The dependency
closure of `worktree`, which is what the project layer needs.
`gpui` here is Zed's real framework, used purely as a reactive runtime:
the engine supplies a headless `Platform` that cannot draw (see
`core/crates/engine/src/platform.rs`).

| Crate | License | Notes |
|---|---|---|
| `gpui` | Apache-2.0 | patched (see below) |
| `gpui_macros` | Apache-2.0 | unpatched |
| `fs` | GPL-3.0-or-later | patched (see below) |
| `worktree` | GPL-3.0-or-later | unpatched — no OS-specific cfgs at all |
| `language` | GPL-3.0-or-later | patched (see below) |
| `lsp` | GPL-3.0-or-later | patched (see below) |
| `git` | GPL-3.0-or-later | unpatched |
| `settings` | GPL-3.0-or-later | patched (see below) |
| `settings_content`, `settings_json`, `settings_macros` | GPL-3.0-or-later | `settings_json` patched (see below), the others unpatched |
| `release_channel` | GPL-3.0-or-later | patched (see below) |
| `theme`, `syntax_theme` | GPL-3.0-or-later | unpatched |
| `task`, `migrator`, `zed_actions`, `zeta_prompt` | GPL-3.0-or-later | `migrator` patched (see below), the others unpatched |
| `paths`, `fuzzy`, `fuzzy_nucleo`, `watch`, `net`, `askpass` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `proto`, `rpc`, `http_client`, `scheduler` | GPL-3.0-or-later / Apache-2.0 | unpatched |
| `telemetry`, `telemetry_events` | GPL-3.0-or-later | unpatched; nothing calls them — the engine sends nothing anywhere |
| `cloud_llm_client`, `language_model_core` | GPL-3.0-or-later / Apache-2.0 | unpatched; pulled in by `settings_content`'s schema |
| `media` | Apache-2.0 | patched (see below); macOS-only content, inert here |
| `refineable`, `refineable/derive_refineable` | Apache-2.0 | unpatched |

`assets/settings/` is vendored from Zed's repo-root `assets/`: `settings`
embeds `default.json` with rust-embed and `SettingsStore` needs it.
Zed's `keymaps/` are deliberately *not* vendored (340 KB of bindings for
a desktop UI we don't have).

Not vendored, but living next to the vendor tree:
`core/crates/trash-android` is a Thragg-written `trash` crate — Zed's
`trash-rs` fork has no Android backend — wired in through a
`[patch."https://github.com/zed-industries/trash-rs"]` entry, and
`core/crates/tree-sitter-dockerfile` is a Thragg-written binding over
camdencheek's Dockerfile grammar (see "Grammars beyond Zed's set" below).

## Grammars beyond Zed's set

Zed's `grammars` crate carries twenty-two parsers and reaches the rest
through extensions, which this app has no runtime for. Ten more are
compiled in here, each added the way the existing ones are: a pin in
`core/Cargo.toml` `[workspace.dependencies]`, an optional dependency and a
`load-grammars` feature entry in `vendor/grammars/Cargo.toml`, a row in
`native_grammars()` (marked `THRAGG PATCH`), and a directory of query
files under `vendor/grammars/src/<name>/`.

| Grammar | Crate | Directory |
|---|---|---|
| HTML | `tree-sitter-html` 0.23 | `src/html` |
| Java | `tree-sitter-java` 0.23.5 | `src/java` |
| Kotlin | `tree-sitter-kotlin-ng` 1.1.0 | `src/kotlin` |
| TOML | `tree-sitter-toml-ng` 0.7.0 | `src/toml` |
| Dockerfile | `tree-sitter-dockerfile` (path, `core/crates/`) | `src/dockerfile` |
| Make | `tree-sitter-make` 1.1.1 | `src/make` |
| SQL | `tree-sitter-sequel` 0.3.11 | `src/sql` |
| XML | `tree-sitter-xml` 0.7.0 (`LANGUAGE_XML`; the DTD grammar is not compiled in) | `src/xml` |
| SCSS | `tree-sitter-scss` 1.0.0 | `src/scss` |
| Svelte | `tree-sitter-svelte-ng` 1.0.2 | `src/svelte` |

Only HTML's queries are Zed's own (copied from
`zed/extensions/html/languages/html/`). The other nine have no Zed source
in the checkout, so their `highlights.scm` is adapted from the grammar's own
query — the Neovim capture vocabulary renamed onto Zed's syntax key set —
and their `config.toml`, `indents.scm`, `outline.scm`, `overrides.scm` and
`brackets.scm` are written to Zed's conventions. Every file carries its
provenance in a comment at the top, and `docs/THIRD_PARTY.md` has the
table with licences. `engine`'s `highlight::grammar_tests` refuse a query
that does not compile against its grammar, a grammar with no `config.toml`,
and a grammar that cannot parse a sample of its own syntax.

**Dockerfile is the exception.** The published `tree-sitter-dockerfile`
still declares `tree-sitter = "0.20"`, whose `Language` is a different Rust
type from the 0.26 this workspace parses with, so `language()` would not
typecheck — and pulling a second `tree-sitter` in would link two copies of
the C runtime. `core/crates/tree-sitter-dockerfile` vendors the upstream
generated `parser.c`/`scanner.c` (MIT, licence beside them) and exposes the
entry point as a `LanguageFn`, in the spirit of `crates/trash-android`.

**Size.** Stripped object sizes from a release build on the host
(`cargo build --release -p grammars --features load-grammars`, then
`ar x` + `strip` per archive):

| Grammar | Bytes |
|---|---|
| Kotlin | 3,474,016 |
| SQL | 2,511,536 |
| Java | 444,208 |
| Make | 195,568 |
| SCSS | 156,040 |
| XML | 126,616 |
| Dockerfile | 84,640 |
| Svelte | 71,184 |
| TOML | 43,008 |
| HTML | 38,096 |
| **added total** | **≈ 6.8 MiB** |

The twenty-two that were already here come to ≈ 12.4 MiB by the same
measure, so this is roughly a 55% increase in the grammar share of
`libthraggcore.so`, uncompressed, per ABI. Two grammars are two thirds of
it: Kotlin's and SQL's parse tables are simply enormous, and both are worth
it here — Kotlin because this is an Android IDE, SQL because it is the
grammar Zed's own SQL extension uses. The APK splits per ABI
(`app/build.gradle.kts`, `splits.abi`), and parse tables compress well, so
the download grows by rather less than this.

All `X.workspace = true` references resolve against
`core/Cargo.toml`, whose `[workspace.dependencies]` external pins
mirror Zed's workspace `Cargo.toml` at the commit above. Keep them in
sync when syncing vendor/.

Exception to "no git dependencies" (see also "Grammars beyond Zed's set"
below): several tree-sitter grammar crates
(`cpp`, `gitcommit`, `go-mod`, `gowork`, `md`, `typescript`, `yaml`)
are rev-pinned git dependencies because that is how Zed itself pins
them (forks/unreleased fixes), as are `async-task`, `notify` and
`notify-types` (Zed's `[patch.crates-io]` forks, kept because gpui and
`fs` depend on the fork behaviour). They are small repositories, cached
by cargo after first fetch — nothing like the full-Zed-clone problem the
vendoring decision avoids.

## Local patches

All patches are marked with `THRAGG PATCH` comments in the touched
files.

Tier 0:

- `sum_tree`: removed `src/property_test.rs` and its module
  declaration + the optional `proptest` dependency (upstream pins a
  git fork of proptest; not worth a git dependency for property tests).
  `test-support` feature is now empty.
- `rope`: dropped `benches/` and the `criterion` dev-dependency.
- `clock`: added `parking_lot` as a dev-dependency so
  `cargo test -p clock` builds standalone (upstream only gets it via
  feature unification from other crates).
- `util`: removed macOS/Windows target-dependency sections (`mach2`,
  `tendril`, `windows`) — we build only for Linux hosts and Android.
- `perf`: removed `src/main.rs` (the profiler CLI binary; we only need
  the library that `util_macros` consumes).

Tier 1 — Android support, from the P3-1 spike
(`agent-docs/archive/research/p3-1-spike-artifacts/android-cfg-patches.diff`):

- `gpui/src/gpui.rs`: two cfg lists gain `target_os = "android"` so the
  `queue` module and its `PriorityQueueSender/Receiver` exports exist on
  Android. Any `PlatformDispatcher` needs them.
- `fs/src/fs.rs`: seven cfg sites gain `target_os = "android"`, so
  Android behaves as Linux. Without the first, `FileHandle::current_path`
  is missing and the `Fs` impl fails to compile; `/proc/self/fd/N` and
  `renameat2` both work verbatim on Android.
- `settings/src/vscode_import.rs`: same rule for the terminal-env
  platform key.

Tier 1 — de-Zed-ing:

- `settings/src/settings.rs`: the `SettingsAssets` rust-embed folder
  points at `../assets` (ours) instead of Zed's repo root.
- `release_channel/src/lib.rs`: the channel name is the literal `"dev"`
  instead of `include_str!` of `crates/zed/RELEASE_CHANNEL`, which lives
  in the Zed app crate we don't vendor.
- `gpui`: the `windows-manifest` feature and its `embed-resource`
  build-dependency are dropped, and `build.rs` with them. We never build
  for Windows.
- Every Tier-1 crate lost its `[dev-dependencies]`, `[[test]]`,
  `[[example]]`, `[[bench]]` and (except `proto`'s prost codegen)
  `[build-dependencies]` sections, plus the matching `tests/`,
  `examples/` and `benches/` directories, and gained
  `[lib] test = false, doctest = false` so their remaining inline
  `#[cfg(test)]` modules aren't built either. Their harnesses need
  `gpui/test-support` and crates outside this closure
  (`gpui_platform`, `reqwest_client`, `theme_settings`). Tier-0 crates
  keep their tests, which is where the vendoring confidence comes from.

Tier 1 — binary size (perf/optimizations):

- `settings_json`, `migrator`, `language`: tree-sitter's `wasm` feature is
  dropped from all three manifests — it pulls the whole Cranelift/Wasmtime
  stack into libthraggcore.so for a .wasm-grammar-loading path this app
  never takes (all grammars are statically linked via `grammars`'s
  `load-grammars` feature). `language` additionally loses the code that used
  the wasm API: `with_parser` no longer attaches a `WasmStore`
  (`src/language.rs`), and `LanguageRegistry`'s `AvailableGrammar` loses its
  `Loaded`/`Loading` variants — a registered `.wasm` grammar now fails to
  load with an error naming the unsupported path
  (`src/language_registry.rs`).
- `gpui`: image decoding and SVG rasterization sit behind a new off-by-default
  `images` cargo feature. `resvg`, `usvg` and `ttf-parser` become optional
  dependencies of that feature; `image` stays required but codec-free (the
  workspace pin's codec features move into the feature list), because
  `RenderImage` and the paint API use its core types. Gated sites are marked
  in `Cargo.toml`, `src/svg_renderer.rs`, `src/app.rs`, `src/window.rs`,
  `src/platform.rs`, `src/elements/mod.rs` (the `svg` element module) and
  `src/elements/img.rs`, where the two asset loaders return an error instead
  of decoding when the feature is off. No build of this app enables it — the
  engine drives gpui headless.

Tier 1 — the fake language server:

- `lsp/src/lsp.rs`: `FakeLanguageServer::set_request_handler` no longer
  awaits `simulate_random_delay` — that is gpui's TestDispatcher fuzzing
  knob, behind gpui's `test-support` feature, and it unwraps a test
  dispatcher the engine's headless runtime does not have. The engine's
  `executeCommand` and `completionItem/resolve` tests drive the real
  client through this fake (`lsp` gains the `test-support` feature as an
  engine dev-dependency), on the runtime the app itself uses.

Also relevant, though not a source patch: `rust-embed` gains the
`debug-embed` feature in `core/Cargo.toml`. Without it, debug builds
read assets from the host path baked in at compile time, which doesn't
exist on a device — `settings::init` then panics on
`settings/default.json`.

## Sync procedure

1. Update the Zed checkout, note the new commit.
2. Re-copy crate directories (`cp -rL` to dereference LICENSE
   symlinks), re-apply the patches above (search for `THRAGG PATCH`
   in the old tree first).
3. Diff Zed's workspace `Cargo.toml` pins against
   `core/Cargo.toml` `[workspace.dependencies]` and update.
4. `cd core && cargo test` must be green; update this file's commit
   pin.
