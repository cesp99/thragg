# Seeker IDE vs Zed — Gap Report

## Executive summary

1. The local editing core is genuinely there: Zed's own engine crates (text CRDT, worktree, fuzzy, settings_json, 21 native grammars) run on-device, with LSP (5 apt-installed servers), diagnostics, hover/definition/references/rename/code actions/format, multi-cursor, folding, buffer + project search, a full git panel (commit/amend/fetch/pull/push/branches/graph/history/diffs), a Termux-based terminal in a Debian userland, and an ACP agent panel with permissions, modes, elicitation, queueing and session resume.
2. Biggest editing holes: no find-and-replace anywhere (buffer or project), no multibuffers (search/diagnostics/references are read-only lists), no vim mode, no split panes, no format-on-save, no inlay hints / signature help / go-to-type-def-or-impl, Tab does not indent selections and Shift+Tab does nothing, CRLF files are silently rewritten as LF on save.
3. Biggest git holes: HTTPS remotes cannot authenticate (no askpass, vendored crate unused), no stash, no merge-conflict resolution UI, no hunk-level stage/revert/expand, no full-file blame, Project Diff is read-only.
4. Biggest workspace/platform holes: nothing but the last project survives a relaunch (tabs/carets/docks lost), no keymap.json / base_keymap, settings.json recognises ~9 keys (no per-language, per-project, format/autosave/appearance knobs), no tasks/runnables, no extensions, and on Android specifically: no open-with/share intents, no in-place editing of external folders, no back-gesture handling.
5. Totals across 442 checked items (incl. 91 verifier-added): 131 present, 88 partial, 194 missing, 29 intentionally N/A (collab, telemetry, auto-update, native-agent-only features).

## Coverage table

| Area | present | partial | missing | n/a | total |
|---|---|---|---|---|---|
| editor | 13 | 14 | 26 | 0 | 53 |
| language | 13 | 9 | 28 | 1 | 51 |
| workspace | 18 | 13 | 18 | 1 | 50 |
| git | 21 | 9 | 18 | 1 | 49 |
| terminal-tasks | 16 | 10 | 23 | 1 | 50 |
| agent-ai | 18 | 9 | 18 | 4 | 49 |
| search-project | 16 | 11 | 20 | 0 | 47 |
| appearance-settings-ext | 13 | 9 | 23 | 2 | 47 |
| collab-remote-debug | 3 | 4 | 20 | 19 | 46 |
| **total** | **131** | **88** | **194** | **29** | **442** |

Counts include verifier-added confirmations that overlap with finder items (e.g. rename/multi-cursor/folding appear twice in editor), so "present" is somewhat inflated relative to distinct features.

## Per area

Format: feature — what's missing — importance.

### editor
present: rename (F2), code actions (Ctrl+.), find references, format (LSP), multi-cursor (Ctrl+D/Ctrl+Shift+L/Alt+Up-Down/Alt+click), line ops (move/dup/delete/join/comment), hover, folding, outline picker, go to line.

MISSING
- Multibuffers — search/references/diagnostics are read-only lists (`ui/search/ProjectSearchPanel.kt:190`, `ui/editor/LspActions.kt:67`) — important
- Vim/Helix mode — no modal editing, no `vim_mode` key — important
- Find and replace in buffer — `ui/search/BufferSearchBar.kt` has no replace field — important
- Go to type definition / implementation / declaration — engine `lsp.rs` RequestKind lacks them — important
- Inlay hints — no request, no rendering — important
- Signature help — no `textDocument/signatureHelp` — important
- Format on save / external formatters / `code_actions_on_format` — `file.rs save_buffer` writes with no format step — important
- Select larger/smaller syntax node — Alt+Up/Down move lines instead — important
- Ctrl+Backspace / Ctrl+Delete word deletion, forward Delete key — `EditorPane.kt:2057` has no ctrl branch — important
- Edit prediction (Zeta/Copilot/Supermaven) — none — important
- User keymap (keymap.json) — `docs/SHORTCUTS.md:8` "hard-coded for now" — important
- User snippets (snippets dir, completions from snippets) — nice-to-have
- Select line (Ctrl+L) / triple-click — nice-to-have
- Transpose — nice-to-have
- Sort/unique/reverse/shuffle lines — nice-to-have
- Case conversion commands — nice-to-have
- Minimap — theme keys only — nice-to-have
- Relative / hidden line numbers — nice-to-have
- Encoding selection / non-UTF-8 files — `file.rs:106` `read_to_string` fails on Latin-1 — nice-to-have
- Auto-indent on paste — nice-to-have
- Inline diagnostics (error-lens) — nice-to-have
- Drag-and-drop selected text — nice-to-have
- Whitespace rendering / trailing-whitespace / final-newline on save — nice-to-have
- Autosave — `UnsavedChangesDialog.kt:24` — nice-to-have
- Document highlight of symbol under caret — nice-to-have
- Newline above/below (Ctrl+Enter) — nice-to-have

PARTIAL
- Snippet tabstops — bodies flattened, caret on `$1`, no Tab-to-next (`Completions.kt:599`) — important
- Bracket matching — autoclose/step-over only; no matching-bracket highlight or Ctrl+M jump — important
- Indent/outdent — Tab inserts one indent even with selection; no Shift+Tab (`EditorPane.kt:2065`) — important
- Git hunk navigation/actions in editor — gutter bars + blame only; no next/prev hunk, no expand/revert — important
- Line endings — CRLF normalised on load, saved as LF, no indicator (`file.rs:180`) — important
- Indent detection / `.editorconfig` — file+language detection works; no per-language `tab_size`/`hard_tabs`, no editorconfig — important
- Completion docs — detail inline only; no `completionItem/resolve`, no docs panel — important
- Columnar mouse selection — keyboard column carets only — nice-to-have
- Wrap guides / bounded soft wrap — `DisplayMap.kt:9` only none/editor_width — nice-to-have
- Scrollbar marks — plain thumb, no diagnostic/search/hunk marks, no `scrollbar.show` — nice-to-have
- Cursor shape/blink setting — blink exists (`EditorPane.kt:111`), no shape or blink toggle — nice-to-have
- Current line highlight — always-on, not configurable — nice-to-have
- Buffer font zoom — `buffer_font_size` key only; no Ctrl+± chords, no pinch — nice-to-have
- Copy path / permalink from editor — only project-panel context menu (`ProjectPanel.kt:639`) — nice-to-have

### language
present: on-device LSP via proot (didOpen/Change/Save, watchers), diagnostics + F8, completion, hover, definition, references, rename, tree-sitter outline/breadcrumbs/brackets, Zed's 21 native grammars, status-bar server note + install prompt, toggle comments from language config.

MISSING
- Go to type definition / implementation — important; go to declaration — nice-to-have
- Format on save / on-save hooks — important
- Per-language settings (`languages`, `file_types`) — `AppSettings.kt` has none — important
- LSP server configuration (`lsp.<server>.initialization_options/binary`) — argv table hard-coded, `workspace/configuration` answered empty (`lsp.rs:2787`) — important
- Multiple servers per language (eslint, tailwind, json/yaml) — `server_for` returns one — important
- Restart / stop language server — important
- Workspace symbol search (Ctrl+T) — important
- Inlay hints — important
- Signature help — important
- Extensions system (add languages/servers) — important
- LSP logs viewer — nice-to-have
- Document highlight — nice-to-have
- Code lens / runnables gutter — `runnables.scm` vendored, unused — nice-to-have
- Semantic tokens — nice-to-have
- On-type formatting — nice-to-have
- Linked editing ranges — nice-to-have
- Document colours — nice-to-have
- Syntax-node selection / syntax tree view — nice-to-have
- Language selector for buffer — `bufferSetLanguage` exists, no picker — nice-to-have
- Toolchain (venv) selection — nice-to-have
- Call hierarchy — nice-to-have
- Inline diagnostics — nice-to-have
- Diagnostics on project-panel rows / tabs — nice-to-have
- Outline panel dock — nice-to-have
- LSP `$/progress` in status bar — nice-to-have (see inconsistency note below)
- Rewrap (Alt+Q) — nice-to-have

PARTIAL
- Project diagnostics panel — read-only tab, no refresh toggle — core
- Code actions — Command-type actions refused (`lsp.rs:3141`), no lightbulb — core
- Language server install — user-confirmed apt for 5 servers; no node_runtime, so npm servers unobtainable — core
- Completions — hard-coded trigger chars (`Completions.kt:476`), no resolve — core
- Document formatting — whole-document only, no `FormatSelections`, no `formatter` setting — important
- Tree-sitter injections — one grammar per buffer; fences only highlighted in Markdown preview — important
- Snippets — flattened, no tabstops, no user snippets — important
- Language breadth — 21 grammars; no HTML/Java/Kotlin/TOML/etc. and no way to add — important
- Folding — indent-based only, no LSP `foldingRange`; README overstates "syntax tree" — nice-to-have

Inconsistency in input: item "Language servers run on-device" cites WorkDoneProgress handling (`lsp.rs ~L2860`, `StatusBar.kt L168-246`) as present, while the verifier-added "LSP progress" item reports zero hits for `progress|WorkDoneProgress`. Treat progress display as unverified.

### workspace
present: tab management (close others/right/all, pin, reopen, middle-click, Ctrl+1..9), outline picker, project panel core ops, file finder, command palette with recency, left/right docks with drag resize and per-panel dock settings, status bar, title bar, go to line, nav history, diagnostics tab, theme selector, unsaved-changes prompt, auto-reveal.

MISSING
- Split panes — one editor pane (`EditorTabs.kt:308`, `WorkspaceScreen.kt:1309`) — core
- Pane/panel zoom (`ToggleZoom`) — important
- Drag to reorder tabs — important
- Tab switcher (MRU Ctrl+Tab) — positional cycling only — important
- Preview tabs — every open is permanent — important
- Multiple worktrees per window — one `ProjectSession` — important
- Outline panel dock — important
- Project panel multi-select / bulk ops — `ProjectTreeState.kt:90` single path — important
- Keymap editor / keymap.json — important
- Multibuffer pane items — important
- Tasks / runnables (`task::Spawn`, tasks.json) — important
- Project panel drag & drop — nice-to-have
- Open-with-system / search-in-directory / trash / undo file ops / sort settings — nice-to-have
- Project panel diagnostics markers — nice-to-have
- Centered layout — nice-to-have
- Which-key pending-chord popup — nice-to-have
- Workspace/tab settings (autosave, `max_tabs`, `tabs.*`, `close_on_file_delete`) — nice-to-have
- Toolchain selector — nice-to-have

PARTIAL
- Session restore — only last project name; tabs/caret/scroll/docks/terminals lost (`ProjectsRoot.kt:113`) — core
- Settings UI — ~9 rows, no search, no per-language/project scope (`SettingsScreen.kt:169-273`) — important
- Recent projects — picker lists all projects; no fuzzy OpenRecent, no remove — important
- File finder extras — no history rows, no `path:line:col`, no ignored toggle — important
- Notification system — only the LSP-install prompt; no toast stack for save/git/LSP errors — important
- Bottom dock — terminal only; no `ToggleBottomDock`/other panels bottom — important
- Status bar interactivity — cursor/language are static labels; no line-ending/encoding items — important
- Tab actions — no close-left / close-clean / keyboard reorder — nice-to-have
- Tab bar toolbar — `+` and nav arrows only — nice-to-have
- Palette hidden actions / aliases — no `command_aliases`; enum-only (~45 commands) — nice-to-have
- Save All etc. — menu only; no Save As, no copy-path for active tab, no focus cycling — nice-to-have
- Welcome state — project picker doubles as welcome — nice-to-have
- Title bar project-name click — non-interactive (`TitleBar.kt:271`) — nice-to-have

### git
present: git panel (stage/unstage/discard, Space/Delete, Ctrl G leader), commit with Amend/Signoff/Skip Hooks + drafts, uncommit, fetch/pull/push/force/remote pickers, PR-link after push, branch picker, conflict colouring, gutter hunks, history, commit view, git graph, branch diff, init, clone, status colours, gitignore handling.

MISSING
- Credentials / askpass — HTTPS remotes fail; `git_remotes.rs:17`, `GitClone.kt:368` — core
- Merge conflict resolution UI — no marker parsing, no ours/theirs/both — core
- Stash (push, any variant) — important
- Stash picker/apply/pop/drop — important
- Expand hunks inline in editor — important
- Stage/revert single hunk, GoToHunk — whole-file only — important
- Full-file blame gutter + commit popover — engine has per-line blame already — important
- Permalinks (line/file copy/open) — only "View on GitHub" for a commit — important
- Split diff view style — nice-to-have
- Word-level diff highlighting — nice-to-have
- File history per file — nice-to-have
- AI commit message — nice-to-have
- Expanded commit editor / line-length guide — nice-to-have
- Tree view / group by / sort in git panel — nice-to-have
- Git worktrees — nice-to-have
- Copy branch name / open modified files — nice-to-have
- Hide gutter indicators / `hunk_style` — nice-to-have
- Repository selector / add to .gitignore — nice-to-have

PARTIAL
- Project Diff — read-only unified view (`ui/git/DiffPane.kt`); no per-hunk/file staging — core
- Branch picker — no `RenameBranch` — core
- Inline blame — clean buffers only; no toggle command; delay/min_column ignored — important
- Hosting providers — github.com only (`ui/git/GitHosting.kt:11`) — important
- Commit view context menu — no Copy Ref/Tag, custom commands — important
- Submodules — dirty submodule shows as one modified path — nice-to-have
- Commit signing — passes through; passphrase-protected keys fail (no pinentry) — nice-to-have
- Restore file — panel only; no checkout at arbitrary revision — nice-to-have
- Bulk discard (restore tracked / trash untracked) — nice-to-have

### terminal-tasks
present: bottom-dock terminal Ctrl+`, tabs with rename/close/cycle, clear, copy/paste + touch handles, scroll to top/bottom/page, bell, exit bar with restart, theme colours, extra-key row, pinch zoom, foreground service, Debian userland via proot, ACP agent terminals.

MISSING
- Terminal splits — flat list in `TerminalPanelState.kt:26` — important
- Search in scrollback — important
- Path/URL hyperlinks (Ctrl+click `file:line`) — compiler errors not clickable — important
- tasks.json templates — `core/vendor/task` unused — important
- task::Spawn picker — important
- task::Rerun — important
- Task variables (`$ZED_FILE` etc.) — important
- Runnables gutter play button — `runnables.scm` shipped, never run — important
- Language-provided tasks (cargo/pytest/go/npm) — important
- Center-pane terminal — nice-to-have
- venv auto-activation — nice-to-have
- Cursor shape/blink setting — nice-to-have
- `copy_on_select` / `keep_selection_on_copy` — nice-to-have
- Vi mode — nice-to-have
- Terminal restore across restarts — nice-to-have
- Open in terminal from project panel — nice-to-have
- `SendText`/`SendKeystroke` — nice-to-have
- Drop paths onto terminal — nice-to-have
- Oneshot tasks — nice-to-have
- Tasks as code actions — nice-to-have (note: this item's claim that LSP code actions don't exist contradicts editor findings; code actions are present at `EditorPane.kt:1984`)
- VS Code tasks.json / hooks — nice-to-have
- Debugger launch — nice-to-have
- REPL / Jupyter — nice-to-have

PARTIAL
- Working directory — always project root; no setting, no current-file-dir — important
- Terminal env — fixed env; no `terminal.env`, no TERM_PROGRAM marker — important
- Custom shell — auto-chosen, no setting — nice-to-have
- Font settings — follows `buffer_font_size`; no family/line_height — nice-to-have
- Scrollback size — hard-coded 4000 (`TerminalSessionHost.kt:226`) — nice-to-have
- Alternate scroll — emulator supports, no toggle — nice-to-have
- Panel settings — height not persisted, bottom only — nice-to-have
- Bell — behaviour present, no `terminal.bell` setting — nice-to-have
- Line scroll (Shift+Up/Down) — nice-to-have
- Full-screen / ToggleZoom — narrow screens only, no explicit command — nice-to-have

### agent-ai
present: ACP panel, permission cards with inline diff, modes/config options, cancel + queue, terminal tool, auth incl. terminal login/logout, elicitation forms, session list/load/resume/delete, slash commands, @file mentions, usage/cost, External Agents settings form.

MISSING
- @directory/@symbol/@thread/@fetch/@rules/@diagnostics/@branch mentions — files only (`AgentPanel.kt:2815`) — important
- Selection as context (`AddSelectionToThread`) — important
- Checkpoints / restore — important
- Notifications when agent finishes/waits while backgrounded — in-panel strip only — important
- MCP context servers config + forwarding in `session/new` (`acp.rs:1607`) — important
- ACP Registry one-click agent install — `SHORTCUTS.md:370` "installs nothing" — important
- Native Zed Agent / LLM providers / model selector — ACP-only by design — important
- Inline assistant (editor/terminal) — important
- Edit predictions — important
- Follow the agent — nice-to-have
- Edit and resubmit previous message — nice-to-have
- Copy response / open thread as Markdown — nice-to-have
- Text threads — nice-to-have
- Commit message generation — nice-to-have
- Terminal threads — nice-to-have
- Worktree isolation for parallel threads — nice-to-have
- ACP logs viewer — logcat only — nice-to-have
- Per-agent `default_mode`/`default_model` — nice-to-have

PARTIAL
- Thread history — no on-device store/archive; agent-kept sessions do reopen (`AgentSessions.kt:226`) — important
- Multiple threads / agent switching — no thread-switcher chord, no archive, one agent process at a time — important
- Reviewing agent changes — per-tool-call diffs only; no aggregate review tab or post-hoc keep/reject — important
- Permission chords (`agent::AllowOnce` etc.) — buttons only — core
- Thread titles — no manual rename/regenerate — nice-to-have
- Images — picker only, no paste — nice-to-have
- Scroll chords in thread — "Show" jump only — nice-to-have
- Token usage — shown; no compaction — nice-to-have
- `agent::NewThread` in palette/keymap — `+ New` button only — nice-to-have

### search-project
present: buffer search bar with case/word/regex, next/prev + counter, invalid-regex feedback, project search with globs/progress/cancel/caps, result highlighting and reveal, worktree scan, file watching, symlink guards, project panel file ops, auto-reveal, file icons, fuzzy file finder.

MISSING
- Buffer replace (ReplaceNext/All, `$1`) — no engine replacement API — core
- Project-wide replace — deferred (`SHORTCUTS.md:543`) — core
- Seed query from selection / smartcase — `BufferSearchBar.kt:121` starts empty — important
- Results as editable multibuffer — important
- Multi-root worktrees — `project.rs:66` one per project — important
- Per-project `.zed/settings.json` — important
- Per-language setting overrides — important
- External file open intents (ACTION_VIEW/EDIT/SEND) — manifest has MAIN only — important
- Search in selection — nice-to-have
- Search open buffers only (unsaved buffers not searched) — nice-to-have
- Search in directory from panel — nice-to-have
- Default search options from settings — nice-to-have
- `private_files` redaction — nice-to-have
- Project panel multi-select — nice-to-have
- Project panel undo/redo — nice-to-have
- Drag & drop (panel / external) — nice-to-have
- Preview tabs — nice-to-have
- Open with external app / share (needs FileProvider, `MediaPane.kt:189`) — nice-to-have
- Text Finder modal — nice-to-have
- Search status-bar button — nice-to-have

PARTIAL
- Select all matches from search bar — editor Ctrl+Shift+L only — important
- Large/binary file handling — `file.rs:106` no size/NUL check; media diverted by extension only — important
- Open files outside project — SAF copy-in only; no loose file — important
- Query history — single last project query — nice-to-have
- `search_wrap` — hard-wired — nice-to-have
- Include ignored — only already-expanded ignored dirs — nice-to-have
- `file_scan_exclusions` — Zed defaults, not user-settable — nice-to-have
- Trash vs delete — permanent only (trash crate wired for git discard, not panel) — nice-to-have
- Panel filter/sort settings — gitignored only — nice-to-have
- Per-file collapse — no fold-all — nice-to-have
- Field cycling in project search — nice-to-have

### appearance-settings-ext
present: 11 bundled themes + live theme selector, UI font size chords, `buffer_font_size`/`tab_size`, settings.json with comment-preserving edits, per-panel dock settings, documented shortcuts + palette, Markdown preview, SVG preview with zoom.

MISSING
- Custom/local theme import — assets only (`ZedThemes.kt:15`) — important
- Font family/weight/ligatures/line height (`Fonts.kt` hard-coded) — important
- `OpenDefaultSettings` / settings JSON schema — important
- Per-project settings — important
- keymap.json — important
- `base_keymap` presets / vim_mode — important
- Extensions & marketplace — important
- Markdown preview scroll/caret sync (`MarkdownPreview.kt:222`) — important
- Editor-appearance settings (cursor, whitespace, line numbers, wrap guides) — important
- Workspace/session settings (autosave, restore, preview_tabs, format_on_save) — important
- `theme::ToggleMode` — nice-to-have
- `theme_overrides` — nice-to-have
- Settings migrator — nice-to-have
- Keymap editor — nice-to-have
- Mermaid — nice-to-have
- CSV/TSV preview — nice-to-have
- `reduce_motion` — nice-to-have
- Journal — nice-to-have
- Feedback/help commands — nice-to-have
- Minimap — nice-to-have
- Scrollbar settings — nice-to-have
- Tab bar / toolbar / title bar visibility settings — nice-to-have
- `active_pane_modifiers`, scroll settings — nice-to-have

PARTIAL
- Light/dark auto — bare string only; Zed `{mode,light,dark}` object rejected (`ThemeStore.kt:14`) — important
- Settings UI — ~9 settings, no search — core
- Buffer font zoom chords/pinch — important
- Icon theme — one baked in, no selector — nice-to-have
- Markdown images/anchors — alt text only, `#` links inert — nice-to-have
- Image viewer — gesture zoom only, dimensions only — nice-to-have
- Audio/video — named, not played (README overstates) — nice-to-have
- Onboarding — sample project instead — nice-to-have
- TalkBack — some labels; editor canvas has no semantics — nice-to-have

### collab-remote-debug
present: project picker with last-opened, mouse hover/pointer icons, terminal-side ssh via userland.

MISSING
- SSH remote development — flagged feasible/valuable (thin client sidesteps SDK-28 exec limits) — important
- Debugger (start/launch configs) — feasible via debugpy/delve/lldb-dap under proot — important
- Git askpass (duplicate of git area) — important
- LSP logs viewer — important
- Restart/stop LS — important
- Open-with / share-sheet intents into app — important
- User-editable keymap — important
- Breakpoints, stepping, variables/console, DAP logs — nice-to-have
- About / version / system specs — nice-to-have
- Open log file — logcat only — nice-to-have
- CLI / `$EDITOR` helper on userland PATH — feasible — nice-to-have
- Share file out / FileProvider — nice-to-have
- Stylus awareness — nice-to-have
- Drag & drop from other apps — nice-to-have
- Syntax tree / key context views — nice-to-have
- HTTP proxy setting — nice-to-have
- Debug adapter extensions — nice-to-have

PARTIAL
- SAF in-place editing — copy in/out only, no persisted grant (`core/SafTransfer.kt`) — core
- Back gesture — no `BackHandler`; back backgrounds the app with a panel open — important
- Multi-window/DeX — resizing works; no fold posture, no multi-instance — important
- Keyboard shortcut coverage — rename/code actions/references bound but undocumented in `docs/SHORTCUTS.md` — important

## Top 15 gaps by impact

1. No find-and-replace, in buffer or across the project (`ui/search/BufferSearchBar.kt`, `engine/src/search.rs` no replacement API).
2. HTTPS git remotes cannot authenticate — clone/push/pull fail; vendored askpass unused (`engine/src/git_remotes.rs:17`, `terminal/GitClone.kt:368`).
3. No split panes; one editor pane only (`EditorTabs.kt:308`).
4. Relaunch loses all open tabs, carets, scroll, docks and terminals (`ProjectsRoot.kt:113`).
5. No merge-conflict resolution UI (conflicts only coloured).
6. External folders cannot be edited in place and the app is not an open-with/share target (`AndroidManifest.xml` MAIN only, `SafTransfer.kt` copy-only).
7. No format-on-save, per-language, per-project or LSP configuration; settings.json recognises ~9 keys (`config.rs:283-346`).
8. No multibuffers — search results, references and diagnostics are read-only lists.
9. No vim mode / `base_keymap` / keymap.json.
10. Missing LSP features: go to type definition/implementation, inlay hints, signature help, workspace symbols, restart server.
11. CRLF files silently rewritten as LF on save (`engine/src/file.rs:180`).
12. Tab does not indent a selection and Shift+Tab does nothing; no Ctrl+Backspace word delete (`EditorPane.kt:2057-2070`).
13. Git hunks: no inline expansion, no hunk-level stage/revert, no full-file blame, Project Diff read-only, no stash.
14. Terminal output is inert — no clickable `file:line` paths, no scrollback search; no tasks/runnables at all.
15. No back-gesture handling on phones (a panel/palette open + back backgrounds the app) and no notification when a backgrounded agent finishes.

## Verifier corrections

Status changes (`corrected: true`):
- editor / Cursor shape and blink: **missing → partial** — blinking exists (`EditorPane.kt:111` CURSOR_BLINK_MILLIS, `:1391` restart on caret move); only shape choice and blink setting are absent.
- agent-ai / Thread history persisted: **status now partial** — threads survive restarts whenever the agent keeps sessions (`AgentSessions.kt:226` resumeThread, `acp.rs:304-313` load/resume/list/delete caps, `AgentPanel.kt:814-846` "Kept by the agent"); only Seeker-side storage/archive is missing.

Evidence corrected without status change:
- collab / Keyboard shortcut coverage: finder said F2 rename was absent from code and docs; F2 IS bound (`EditorPane.kt:2045`, `RenameSymbol.kt:62`). Docs gap stands (`docs/SHORTCUTS.md` has no rename/Ctrl+./Shift+F12 rows).
- appearance / Localisation: `res/values/strings.xml` exists but holds only `app_name`; still N/A.
- search / Trash vs delete: trash crate IS wired for git discard of untracked files (`CoreBridge.kt:285`), just not for the panel's Delete.
- git / File history: a commit diff can be narrowed to a path from an open commit (`DiffPane.kt:65`, `GitGraphPane.kt:1197`), but no per-file log entry point.
- language / Code folding: `docs/SHORTCUTS.md:576` says indent-based; `README.md:87` "from the language's own syntax tree" overstates.

Verifier-added items: 91 (10 per area, 11 in language), mostly confirming present features with line citations; new negatives include buffer find/replace, Ctrl+Backspace word delete, newline above/below, tasks/runnables in workspace, git askpass, LS logs/restart, HTTP proxy, `RenameBranch`, `default_mode`/`default_model`.

Input inconsistencies to note: language area both claims `$/progress` handling present (`lsp.rs ~L2860`) and, in a verifier-added item, zero hits for `WorkDoneProgress`; terminal-tasks "Tasks as code actions" claims LSP code actions do not exist, contradicting editor/language findings (`EditorPane.kt:1984`, `LspActions.kt:171`).