# Seeker IDE

**A Solana-first, native, open-source IDE for Android — write, build, test
and deploy on-chain programs entirely from the phone.**

> ⚠️ Early development. The editor foundations are inherited and working;
> the Solana layer is being built. Not a daily driver yet.

## What this is

Solana Playground, but the compiler is in your pocket rather than in a
datacentre. Seeker IDE carries a real Debian and the SBF toolchain on the
device, so `anchor build`, `cargo build-sbf` and the test run all happen
locally — and a deploy is signed by the phone's own wallet.

- **The toolchain is on the device.** Debian through `proot`, rustup, the
  Solana platform-tools (LLVM + a Rust that targets SBF), Anchor and
  rust-analyzer. One tap to install, then it builds offline. No cloud
  build server, no account, no upload of your program.
- **Solana-first, not Solana-flavoured.** New project means Anchor,
  Native or Seahorse. Build, test, deploy and the cluster selector are
  first-class, not tasks you wire up by hand.
- **The wallet is already here.** The Seeker has Seed Vault, so deploying
  is signed on-device through Mobile Wallet Adapter instead of a keypair
  file sitting in the project.
- **Zed's engine, not a lookalike.** The core reuses Zed's actual Rust
  crates (rope/CRDT text engine, tree-sitter, grammars) compiled for
  Android with the NDK.
- **Spettro, in full.** The agent panel speaks
  [ACP](https://agentclientprotocol.com), and Spettro's ACP is a superset of
  it — workflows with live phases, Ultra mode, the Mode/Model/Permission/
  Thinking selectors, question forms, a live context gauge, steering. The
  phone renders all of it natively rather than reducing Spettro to the
  common denominator. Any other ACP agent still works as a settings entry.
- **No telemetry. No analytics. Ever.** In the tradition of
  [VSCodium](https://vscodium.com): the user's code and behavior are
  nobody's business.

See [docs/SOLANA.md](docs/SOLANA.md) for the design of the Solana layer.

## Architecture in one paragraph

A Rust engine (`core/`) owns everything that isn't pixels: buffers
(Zed's rope/CRDT), syntax (tree-sitter), language intelligence (LSP),
project state, git, and ACP agent sessions. A Kotlin/Jetpack Compose app
(`app/`) owns everything visual and platform-specific: rendering, input,
window/fold awareness, storage access. The two meet at one deliberately
narrow, coarse-grained JNI boundary. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the long version,
[docs/BUILDING.md](docs/BUILDING.md) to build it yourself, and
[docs/SOLANA.md](docs/SOLANA.md) for the Solana layer.

The shell is being rebuilt **portrait-first for the Seeker itself** —
400 × 890 dp, one hand, touch, no keyboard. The inherited foldable/tablet
workspace (docks, split panes, chord-driven commands) is not the right
product for that screen and is being replaced rather than adapted.

## Status

| Area | State |
|---|---|
| Rust core ↔ Kotlin JNI pipeline | ✅ working end-to-end |
| Portrait-first phone shell (Seeker) | 🚧 replacing the inherited foldable/tablet workspace |
| On-device SBF compile (verified: 71 s to a real `elf64-sbf`) | ✅ proven on hardware |
| Solana toolchain installer, templates, build/deploy/test | 🚧 in progress |
| Rope/CRDT text engine (from Zed) | ✅ vendored & wired through JNI |
| Tree-sitter syntax highlighting | ✅ in-engine, 31 languages (30 grammars; JavaScript parses with the TSX grammar, as in Zed) — Zed's own set plus HTML, Java, Kotlin, TOML, Dockerfile, Make, SQL, XML, SCSS and Svelte, which Zed reaches through extensions |
| Injected languages | ✅ Zed's syntax layers: a Markdown fence highlights as the language its info string names, an HTML `<script>`/`<style>` as JavaScript/CSS, a Rust `sql!` body as SQL — nested three deep, deeper layers painting over shallower ones |
| Custom high-performance editor surface | ✅ v1: virtualized canvas, IME editing, selection & clipboard, Zed themes |
| Project tree (Zed worktree in-engine) | ✅ lazy, gitignore-aware; open files from the panel |
| Tabs, save & external change detection | ✅ dirty dots, atomic save, live reload |
| Line endings & encodings | ✅ CRLF/LF and UTF-8 BOM, UTF-16, Windows-1252 read and written back as found; status-bar items with Zed's pickers |
| Tab parity with Zed | ✅ Ctrl+Tab MRU switcher, preview tabs, drag to reorder, `tabs` settings and `max_tabs` |
| Status bar | ✅ Zed's items, and its buttons: the caret position opens go-to-line, the language name opens the language selector (`Ctrl+K` then `M`), the diagnostics summary opens the Diagnostics tab, and the line ending and encoding open their pickers |
| Projects: create, switch, import & export | ✅ app-private storage, SAF folder import/export |
| Opening files from other apps | ✅ **Open with** or a share from any app — `VIEW`/`EDIT` for a text file, `SEND`/`SEND_MULTIPLE` for files or a piece of text, which becomes `Shared text.txt` — lands in a project you pick. The engine works on real paths, so an incoming file is copied into the project rather than edited in place. The back gesture closes whatever is drawn over the work area first — a modal, the find bar, a focused terminal, a dock on a phone — and leaves the app only when there is nothing left to close |
| Fuzzy file finder | ✅ Ctrl+P, match highlighting |
| Command palette | ✅ Ctrl+Shift+P, every command with its chord — read from every context the command is bound in, so an agent-panel chord prints too — Zed's action names, and `command_aliases`: type `W` for `workspace::Save` and the alias shows beside the row. A command this build cannot perform is hidden; one that cannot run right now is greyed, never hidden |
| Find in file | ✅ Ctrl+F / Ctrl+H, case/word/regex, every match highlighted, replace next/all with `$1` groups, select all matches |
| Search across the project | ✅ engine-side, cancellable, gitignore-aware; results open as an editable multibuffer; replace all across files (open buffers undoable, closed files rewritten) |
| Multiple cursors & line operations | ✅ Ctrl+D, cursors above/below, move/duplicate/delete line, select line (Ctrl+L), toggle comment, sort/reverse/shuffle/unique lines, transpose, the eight `ConvertTo*` case commands, and `editor::Rewrap` (Ctrl+K Ctrl+Q) reflowing a paragraph or comment block to `preferred_line_length` |
| Selecting by syntax | ✅ Zed's `Alt+Shift+→` / `Alt+Shift+←` grow and shrink a selection through the file's syntax tree, with a stack so shrinking retraces; the bracket pair around the caret is highlighted and `Ctrl+M` jumps between its ends |
| Editor display settings | ✅ `show_whitespaces` (all five modes, Zed's `·` and `→`), `show_wrap_guides` and `wrap_guides`, `relative_line_numbers`, `gutter.line_numbers`, `current_line_highlight`, `cursor_shape` and `cursor_blink`, `remove_trailing_whitespace_on_save` and `ensure_final_newline_on_save` (both on by default, as in Zed) |
| Scrollbar marks & minimap | ✅ git hunks, search hits, problems and cursors marked on the scrollbar track under the `scrollbar` keys; `minimap` draws the file as colour blocks down the right edge with a draggable viewport thumb (off by default, as in Zed) |
| Inline diagnostics | ✅ `diagnostics.inline` writes the worst problem of a row at the end of it in the severity's colour — Zed's error lens — with `editor::ToggleInlineDiagnostics` |
| Snippets | ✅ LSP snippet bodies with real tabstops — `$1`, `${2:placeholder}`, `${1|a,b|}` choices, `$0` — driven with Tab and Shift+Tab, a cursor on every mirror of a stop so they change together; your own snippets in `snippets/<language>.json` in Zed's format, offered beside the server's |
| Autoclose, auto-indent, indent guides | ✅ from the language's own brackets |
| Project panel as a file manager | ✅ new/rename/delete/duplicate, context menu, full keyboard |
| Project panel parity with Zed | ✅ multi-select, drag entries between folders, trash with undo, Open in Terminal / Search in Directory, `sort_mode`/`auto_fold_dirs`/`show_diagnostics` |
| Several folders in one project | ✅ Zed's multi-root worktrees — `workspace::AddFolderToProject` from the palette, the panel's `+` or its menu, and `workspace::RemoveWorktreeFromProject` to drop one; a root header per folder with its own expand state, and the file finder, project search and `.zed/settings.json` spanning them all. A folder from the device is copied into app storage first, as opening one is |
| Docks | ✅ each panel left or right by setting, one per dock, both resizable |
| Themes | ✅ eleven of Zed's own with a live-preview picker, plus your own in a `themes` folder (watched, validated, bad files named in the picker) and **Import theme…**; Zed's `theme` object (`mode`/`light`/`dark`) and `theme_overrides` |
| Fonts | ✅ `buffer_font_family`, `_fallbacks`, `_features` (ligatures), `_weight`, `buffer_line_height`, `ui_font_family`, `ui_font_size` — bundled faces, a `fonts` folder, or the device's own; settings rows with a live preview, and the editor and terminal share the face |
| Icon themes | ✅ the bundled Zed set as a named theme, `icon_theme`, an icon-theme picker, and user themes in an `icon_themes` folder (raster art, falling back to the bundled icons per key) |
| Settings | ✅ JSONC file that keeps your comments, settings screen with a filter, `zed::OpenDefaultSettings` |
| Keymap | ✅ Zed's `keymap.json` — Zed's keystroke syntax, contexts, chords (`ctrl-k ctrl-s`), `null` to unbind, `base_keymap` (VS Code, JetBrains, Sublime Text, Atom, Emacs, None), `zed::OpenKeymap` / `zed::OpenDefaultKeymap`; the palette prints your chords |
| Vim mode | ✅ `vim_mode` — normal/insert/visual/visual-line/visual-block/replace, counts, motions, operators with text objects, registers and the clipboard policy, dot-repeat, marks, `/` search on the engine's search, `:` commands (`:w` `:q` `:e` `:s` `:noh`…), `gd` and `gc`; `-- NORMAL --` in the status bar, block/bar/underline caret |
| Settings parity | ✅ per-language `languages`, project-local `.zed/settings.json`, `hard_tabs`, `preferred_line_length` and wrap guides, `format_on_save` / `formatter` (language server or an external command in the userland) / `code_actions_on_format`, `lsp.<server>` binary, initialization options and `workspace/configuration`, `enable_language_server`, `autosave`, `file_types` (globs mapped onto a language, asked before the built-in suffix table) |
| Integrated terminal | ✅ shells in the project directory, tabs, theme colours, keyboard/mouse/touch, clickable paths and URLs (`src/main.rs:12:5` opens at the spot), search in the scrollback, `terminal` settings (working directory, env, scrollback) |
| Tasks & runnables | ✅ Zed's `tasks.json` (user and `.zed/`), `$ZED_*` variables, `task::Spawn` picker with oneshots and history, `task::Rerun`, `reveal` / `hide` / `save` / `use_new_terminal` / `allow_concurrent_runs`; built-in cargo, python/pytest, go, npm-script and shell tasks; ▶ play buttons in the gutter from each grammar's `runnables.scm` — see [docs/TASKS.md](docs/TASKS.md) |
| Terminal sessions survive backgrounding | ✅ foreground service, notification with **Stop all**, survives a swipe from Recents |
| Debian userland (`apt`) | ✅ in the `full` edition — installs on demand, ~30 MB |
| Clone a repository into a project | ✅ progress, cancel, credential prompts (`full` edition) |
| Git status colours in the project panel | ✅ engine-side, from the theme's own colours |
| Git panel | ✅ `Ctrl+Shift+G`, stage/unstage/discard/commit, keyboard and touch |
| Diff bars in the gutter, inline blame | ✅ Zed's own widths and colours; blame while the file is clean |
| Hunks in the editor | ✅ `Alt+.`/`Alt+,` walk them, `Ctrl+'` expands them into Zed's deleted/added blocks; stage, unstage or restore one hunk from the block header or the chords (`git apply --cached` of a one-hunk patch) |
| Blame column | ✅ `Alt+G B`, Zed's gutter blame with a popover per row and **View on GitHub** |
| Stash | ✅ Stash All / Tracked / Staged with a message, pop, apply, and Zed's stash picker (`git: view stash`) |
| Project diff staging | ✅ Stage/Unstage per file and Stage/Unstage/Restore per hunk in the diff tab |
| Merge conflicts | ✅ Zed's tinted regions and Use HEAD / Use branch / Use Both, next/previous, stage when resolved |
| Diff view, history, push | ✅ unified diffs as tabs, Changes/History tabs, push and publish — HTTPS tokens, SSH passphrases and host keys asked in Zed's askpass dialog |
| Commit graph | ✅ lanes, refs, paging, per-commit files |
| Markdown, SVG and table preview | ✅ the toolbar's 👁, split beside the editor or full screen; `.csv`/`.tsv` as a table with the row and column count |
| Markdown scroll sync | ✅ the preview follows the editor's top line, a tap in it moves the caret; `⇅` in the title bar and `markdown_preview.scroll_sync` |
| Markdown images, mermaid and math | ✅ project images and inline SVG drawn (never fetched); ```mermaid flowcharts and sequence diagrams drawn in Compose, anything else named; `$…$` kept verbatim rather than mangled |
| Images, audio and video | ✅ never a text buffer: pictures zoom with Zed's image viewer chords and buttons, and show dimensions, size and format; sound and video play (ExoPlayer) with play/pause, seek bar and Space/←/→/Home; Android's decoders decide which formats — `.psd`, `.jxl`, `.tiff` still open as text |
| Soft wrap | ✅ `soft_wrap`, off by default as in Zed |
| Go to line | ✅ `Ctrl+G`, `42:8`, Escape puts everything back — or tap the caret position in the status bar |
| Code folding | ✅ from the language server's folding ranges, else the language's own syntax tree, else indentation; gutter chevrons and chords |
| Breadcrumbs & outline | ✅ the symbol path at the caret, `Ctrl+Shift+O` for the picker |
| Outline panel | ✅ `Ctrl+Shift+B` — the file's symbol tree as a dock panel, expandable, following the caret (`auto_reveal_entries`), with the picker's filter; `outline_panel.dock` like every other panel |
| Notifications | ✅ Zed's toast stack — severity, an action button (**Show log**, **Show output**, **Open keymap.json**), auto-dismissing info, errors until dismissed, four visible and **+N more**, `workspace::ClearAllNotifications`; save, git, language-server, task, agent and settings/keymap parse failures all report here |
| Activity indicator | ✅ a spinner and one sentence in the status bar for the worktree scan, project search, git fetch, a running task and language-server progress — tap it to reveal where the work is |
| Split panes | ✅ Zed's pane tree — split right/left/up/down (`Ctrl+K` then an arrow), focus and swap across a split, join, zoom (`Shift+Esc`), per-pane tabs and history, drag a tab between panes or to an edge, resizable dividers; two panes at most on a phone |
| Sticky headers in the project panel | ✅ ancestors pin above the list, as in Zed |
| Navigation history | ✅ back and forward across the places you jumped from |
| Editable multibuffers | ✅ Zed's own surface — excerpts of many files in one document, edits routed to each file, `Ctrl+S` saves them all |
| Project diagnostics | ✅ a tab of every problem, grouped by file, and the same problems as an editable multibuffer |
| Workspace persistence | ✅ one JSON document per project in the engine (Zed keeps the same shape in sqlite): the pane tree, every tab with its carets, scroll, pinned and preview state, the jump list, the docks and their widths; `restore_on_startup` (`last_session` / `last_workspace` / `none`), `close_on_file_delete`, `projects::OpenRecent` (`Ctrl+Alt+O`) with Remove from Recent Projects, and `workspace::CloseWindow`. Terminal tabs come back as fresh shells in the same directories — a shell dies with the app; multibuffers are not restored, being snapshots of a search that has moved on |
| Language servers on-device | ✅ diagnostics, completions with `completionItem/resolve` documentation, hover, signature help, inlay hints (`inlay_hints`, off by default as in Zed), go to definition / type definition / implementation / declaration, find references (into a multibuffer), rename, code actions (edits and commands), formatting, project symbols (`Ctrl+T`); restart, stop and a log tab per server from the status bar; servers installed with `apt` (`full` edition) |
| Toolchain selector | ✅ Zed's `toolchain::Select` — the project's virtualenvs (`pyvenv.cfg` in the root or one directory down), `poetry env info`, `rustup toolchain list` and the guest's own `python3`/`cargo`; the choice is per project and per language, exports `VIRTUAL_ENV` and leads `PATH` for the language servers and for tasks, and shows in the status bar |
| Interface size | ✅ `Ctrl+=` / `Ctrl+-` / `Ctrl+0`, and a settings row — all the chrome scales |
| Which-key hint | ✅ press `Ctrl+K` and the ways to finish the chord are listed above the status bar — Zed's `which_key`, on the pending state the keymap already kept |
| Chrome visibility | ✅ Zed's `tab_bar` (`show`, `show_nav_history_buttons`, `show_tab_bar_buttons`), `toolbar` (`breadcrumbs`, `quick_actions`, `selections_menu` — with Zed's selection-controls menu behind it) and `status_bar` (`active_language_button`, `cursor_position_button`), each hiding the thing it names and leaving its commands in the palette |
| Accessibility | ✅ the editor canvas announces the file, the caret and the line it is on, with the diagnostic under the caret as its own live region; project-panel rows say their kind, git status, problems and selection in words; every icon-only button is named and carries a 48dp touch target; `reduce_motion` (`on`/`off`/`auto`, following Android's own **Remove animations**) stills the scroll animations and the spinners; UI text follows the system font scale |
| Translations | ✅ the shell's strings — settings, the project picker, the dialogs, the ☰ menu, the welcome screen, About — are in `res/values/strings.xml`; adding a language is one file (see [CONTRIBUTING.md](CONTRIBUTING.md)) |
| Onboarding | ✅ a first-run welcome with the three things to try and the theme choice — Zed's `zed::OpenOnboarding`, reachable again from ☰ and the palette |
| About | ✅ `zed::About` — app version and edition, engine version, the Zed commit the vendored crates come from, device, Android version, ABI and page size, with **Copy** for a bug report |
| ACP agent panel | ✅ conversations with any ACP agent — permission-gated edits, plans, `@` mentions (files, directories, symbols, threads, fetched pages, rules files, diagnostics, the selection), per-message checkpoints with **Restore checkpoint**, a **Review changes** tab with Keep/Reject per file, MCP `context_servers` forwarded to the agent, notifications when it is waiting while the panel is hidden; agents are configured in `agent_servers` (settings.json or the Settings screen), none are bundled or named in code (`full` edition) |

## Editions

Two builds come out of this repository, and the difference is worth
knowing before you download one:

- **`full`** — includes the Debian userland, so `apt` works. Android only
  permits that at an older target SDK, which Google Play does not accept,
  so this edition comes from F-Droid or a direct APK.
- **`play`** — Play-compatible. Everything else is identical; the terminal
  runs Android's own shell and there is no `apt`.

See [docs/BUILDING.md](docs/BUILDING.md) for the details and
[docs/USERLAND.md](docs/USERLAND.md) for what the userland can do.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Testing on a real Seeker is
especially valuable — that is the device this is designed around.

## Licence & credits

**Copyright (C) 2026 Eyed** (Carlo Esposito, carlo@aploi.de).

Seeker IDE is free software. Eyed's own code — the Kotlin app and the Rust
engine — is licensed **GPL-3.0-or-later**; see [LICENSE](LICENSE) for the
text and [NOTICE](NOTICE) for the copyright statement.

The application **as distributed is GPL-3.0-only**. It links Termux's
`terminal-emulator` and `terminal-view`, which are GPL-3.0-*only*, so no
recipient of the APK may take the "or later" option over that code. Take
Eyed's code on its own and the "or later" is yours again. If you
redistribute a build of this repository, say GPL-3.0.
[docs/LICENSING.md](docs/LICENSING.md) explains why, with the full
compatibility matrix.

This program comes with ABSOLUTELY NO WARRANTY. It is free software, and you
are welcome to redistribute it under the terms of the GNU General Public
License.

### Lineage

Seeker IDE is a fork of **Conquest Code** (GPL-3.0-or-later), which supplies
the editor, the Debian userland, the terminal, the LSP client, git and the
ACP agent panel. Conquest Code in turn reuses Zed's engine crates and
vendors Termux's terminal libraries. Seeker IDE adds the Solana layer and
rebuilds the shell for the Seeker's screen.

Those notices are kept deliberately. A fork that quietly drops the notices
of the work it stands on is the most common GPL violation there is.

It stands on the shoulders of:

- **[Zed](https://github.com/zed-industries/zed)** (GPL-3.0-or-later and
  Apache-2.0, © Zed Industries, Inc.) — the engine crates this project
  reuses, and the design north star.
- **[Termux](https://github.com/termux/termux-app)** (GPL-3.0-only, ©
  Fredrik Fornwall and the Termux contributors, with an Apache-2.0 heritage
  from [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator),
  © Jack Palevich) — its `terminal-emulator` and `terminal-view` libraries
  are vendored here under `vendor/`, and its work is the reference for
  running a real userland on Android.
- **[proot](https://github.com/termux/proot)** (GPL-2.0-or-later) — the
  userspace chroot that lets a Linux distribution run without root — and
  **[talloc](https://download.samba.org/pub/talloc/)** (LGPL-3.0-or-later),
  linked into it.
- **[Debian](https://www.debian.org)** — the userland itself, and the
  package archive behind it.
- **[IBM Plex Sans](https://github.com/IBM/plex)** and
  **[Lilex](https://github.com/mishamyrt/Lilex)** (SIL OFL 1.1) — the two
  faces the app draws in.
- **[Lucide](https://lucide.dev)** (ISC) and **Feather** (MIT, © Cole
  Bemis) — the heritage of Zed's interface icons.
- **[VSCodium](https://github.com/VSCodium/vscodium)** (MIT) — proof that a
  community can keep an IDE honest.

Full provenance is in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md); the
obligations, the compatibility matrix and the shipping checklist are in
[docs/LICENSING.md](docs/LICENSING.md).

### Source offer

For a period of three years from the date you received this software, and
for as long as Eyed offers spare parts or customer support for the product
model it came on, Eyed will give any third party who possesses the object
code access to copy the complete corresponding source for every GPL- and
LGPL-licensed component in it, from a network server, at no charge — the
second of the two forms GPLv3 §6(b) permits. Write to carlo@aploi.de if you
need help obtaining it.

You do not need the offer to get the source: it is in this repository, and
a complete `corresponding-source` archive is attached to every release. The
offer exists because someone who received the app preinstalled on a phone
never visited this repository, and GPLv3 s6(d) does not reach them.

### Trademarks

The GPL grants no trademark rights. Product names and logos shown beside
file types in the interface are the trademarks of their respective owners
and are used only to identify a file's type. A GPL fork may take this code;
it may not take the Seeker IDE name or Eyed's marks. See
[docs/TRADEMARKS.md](docs/TRADEMARKS.md).

This project is not affiliated with or endorsed by Zed Industries, Termux,
Debian, GitHub, Google, Solana Labs, Solana Mobile, or VSCodium.

### Security

Found a vulnerability? Do not open a public issue —
[SECURITY.md](SECURITY.md) has the private routes and the timelines.
