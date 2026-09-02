# The Seeker shell

Seeker IDE is a phone app. The editor, terminal, LSP client and ACP session
layer are inherited from Conquest Code, but the *shell* around them is not:
Conquest was built for foldables, tablets and DeX, and this device is a
**400 × 890 dp** phone held in one hand. This page is the specification for the
shell that replaces it.

It was produced by putting three independent designs — ruthless-minimal,
Solana-Playground-shaped, and agent-first — through four judges each
(portrait usability, Solana workflow fit, honest simplicity, feasibility
against the real code) and synthesising the winner.

## The design chosen

Agent-first "Thread" (avg 7.0, and the only design to score 8 on simplicity) is the base. Its thesis is kept whole: the ACP conversation is a peer of the editor, not a dock panel; every error carries a one-tap "Fix with agent"; "New program" ends in a pre-seeded thread; Changes fuses agent Keep/Reject with git staging. Three things are grafted in and one is overruled. Grafted: (a) from "Three Doors" — Shell is a mode of Build reached by a header chip rather than a fourth destination, one shared Diff surface serves both agent review and git, everything that was a dock becomes a modal bottom sheet, and soft wrap defaults on; (b) from "Playground Portrait" — the toolchain download is deferred until the first Build press rather than gating first launch, Wallet/cluster/deploy state is a real surface with airdrop and "deployed from this phone", and the Problems list survives as its own route; (c) from the pruning inventory — DiagnosticsPane.kt, DiffPane.kt, FileFinder.kt, TaskRunner.kt and EditorActionRow are re-hosted rather than rebuilt. Overruled: Thread's four stops (Chat/Files/Build/Changes) become three (Code/Agent/Build). Files is a sheet because you spend hours in a buffer and seconds picking a file; Changes is a route because the brief says "occasionally git" and a permanent stop that is empty on a fresh project is 133dp of nothing. Start destination is Code, not Chat, because Code is the only destination that degrades honestly with no agent, no network and no toolchain.

### Why

Three reasons this base wins for this device. First, the brief's own user description — "often with an AI agent doing the typing" — makes the agent an input method, and an input method cannot be a dock panel on a 400dp screen. Thread is the only design that treats it as such and then follows through: the [Fix with agent] button on a build failure, the pre-seeded composer on project creation, and Changes-as-trust-surface all fall out of that one commitment. Second, Thread has the cleanest structural claim — four inherited navigation models (pane tree, two docks, tab strip, palette+keymap) die together and are replaced by one bar plus per-destination back stacks — and its file-level citations check out against the tree (MinEditorWidth = 360.dp at WorkspaceScreen.kt:234, AgentBar at AgentPanel.kt:515, media3 at build.gradle.kts:186). Third, it names the two couplings that would otherwise break the build (MarkdownText inside the deleted preview package; the WorkspaceCommand audit), which is the difference between a plan and a wish.

The three changes to it are all judge-confirmed defects, and each has a concrete fix in this spec. (1) "The build trigger is never where the work is" — fixed by putting ▶ Build in the fixed, non-scrolling head of the editor's IME action row and a build-state dot on the ▶ nav item, so a rebuild from the editor is one tap and its result is visible from every destination. (2) "Nothing saves dirty buffers before a build" — fixed by making save-all-then-build a single atomic action in BuildRunner, and by flipping autosave to on-leaving-a-file. (3) "Reachability inversion" — fixed by moving every high-frequency control into the bottom third: the file bar and its ⌕ sit directly above the nav bar, Build/Test/Deploy sit directly above the nav bar, Keep/Reject sit at the bottom of Diff, Commit sits at the bottom of Changes. The top bar carries only identity (project, file) and rare actions (⋮).

Two engine facts from this session's reading shape the spec and contradicted all three designs. First, the diagnostics store is read-only across JNI — CoreBridge.lspDiagnostics/lspDiagnosticRows/bufferDiagnostics are `external fun` reads with no ingest path — so "feed cargo's JSON into the same store" is a Kotlin-side merge layer (solana/build/BuildDiagnostics.kt), not a free keep. Second, go-to-definition already has a working touch route: EditorPane.kt:1026-1046 long-presses into selectWordAt + hover.longPressAt, and Hover.kt:462-465 renders "Go to definition / type definition / implementation / declaration" as tappable rows on the card. So this spec does not invent a long-press sheet that would collide with range selection — it keeps the inherited gesture and adds keys to the action row.

## Navigation

SHELL. One file, app/src/main/java/to/eyed/seeker/code/ui/shell/SeekerShell.kt, replacing WorkspaceScreen.kt. Structure: `Column { destination(weight = 1f); BottomNav }`. No title bar, no status bar, no tab strip, no dock, no pane tree. There is no `isWide`, no `WideLayoutMinWidth`, no `DockMinWidth`, no `MinEditorWidth`, and no window-size-class branch anywhere in the shell.

THREE DESTINATIONS, and nothing else is a destination. Bottom navigation bar, 56dp + gesture inset, 3 items at 133dp each — comfortably thumb-reachable across the full width including left-handed.
  ‹› Code   — the editor, full-bleed. Start destination.
  ✦ Agent   — one ACP conversation on the current project.
  ▶ Build   — cluster, wallet, program id, streamed build/test/deploy output, three buttons; toggles in place to Shell.

BADGES. ✦ shows a dot when the agent finished or is blocked on a permission while you are elsewhere. ▶ shows an animated ring while a build/test/deploy runs, a red dot when the last run failed, and a green tick for 10 s after a success. This is the single most important cross-destination signal in the app: a 71-second build is a build you walk away from.

THE BAR HIDES WHEN THE IME IS UP. In Code the 44dp action row takes its place (docked on the keyboard). In Agent the composer takes its place. Nowhere do the nav bar, the action row and the IME coexist. The rule is exact and unconditional: `if (WindowInsets.isImeVisible) hide nav`.

SWITCHING is a tap only. No swipe between destinations — a horizontal swipe belongs to the editor's selection and the terminal's touch selection. Re-tapping the current destination scrolls it to top / to the newest message / to the end of the log.

PER-DESTINATION BACK STACKS. Opening a file from Agent pushes Code's editor onto Agent's stack, so back returns to the conversation you were reading. Opening the same file from the Files sheet or from a Build error row pushes it onto Code's / Build's stack. Each destination keeps its own scroll, caret and console state alive across switches.

FULL-SCREEN ROUTES (push over the current destination, keep the nav bar, own a ← in their own top row): Changes, Diff, Problems, Settings, New program, Clone, Setup. Setup is the one route that hides the nav bar.

MODAL BOTTOM SHEETS (never docks, never side-by-side): Files & Find, Projects & tools, Cluster, Wallet, Deploy confirm, Agent picker / install, @-mention picker, Commit, Code overflow (⋮), file long-press menu, code-actions (quick fix), unsaved-changes confirm. Sheets open at ~65% height, drag to expand, drag down or back to dismiss. Any sheet with a text field pins that field at the *bottom* of the sheet so the IME lands under it and results scroll above.

BACK GESTURE — one ordered handler in ui/shell/ShellBackHandler.kt, replacing the eight-branch chain at WorkspaceScreen.kt:3608-3650:
  1. A completion menu, hover card, selection toolbar or code-action popup is showing → dismiss it.
  2. A sheet or dialog is up → close the topmost one.
  3. The IME is up → dismiss it (and, in Code, leave the action row's state alone).
  4. The find bar is deployed → close it and clear match highlights.
  5. A full-screen route is on the current destination's stack → pop it.
  6. In Code, the jump stack is non-empty (you followed a go-to-definition) → pop one jump, restoring file, caret and scroll. This is OpenFiles.kt's NavHistory, kept.
  7. The current destination is not Code → go to Code.
  8. In Code at the root → leave the app. No confirm: autosave-on-leaving-a-file is on by default, so leaving is never destructive.
Notably absent: back does not close a dock (there are none), does not unfocus the terminal (Shell is a mode of Build; back from Shell goes to Code, and the mode is remembered), and does not dismiss a tab switcher (there is none).

NO KEYBOARD PATH AT ALL. There is no command palette, no chord dispatcher, no which-key, no keymap.json, no base_keymap. Every surviving capability has a touch target on a screen, in a sheet, in the editor action row, or on the selection/hover card. A capability with no touch target is a capability that was cut, and it is named in "removed". A paired Bluetooth keyboard still types and still gets Enter/Tab/arrows/Ctrl+S/Ctrl+Z/Ctrl+Y/Ctrl+C/V/X/Ctrl+F/Escape — nine bindings hard-coded in EditorInput.kt, not configurable, not documented as a feature.

LAUNCH. Cold start restores the last project, the open-file MRU with carets and scroll, and the destination you left. No splash, no picker. If there is no project: the Projects sheet opens over an empty Code. If there is no agent installed: Agent shows a single card offering Setup and the composer is disabled — the other two destinations are complete without it. If there is no toolchain: the Setup takeover is on top of Code and stays there until the required components are in — it is the gate, see "First run".

## Orientation

PORTRAIT-FIRST, ONE LAYOUT, NO ADAPTIVITY. There is no `isWide`, no WideLayoutMinWidth, no DockMinWidth, no MinEditorWidth, no planDocks argument, no window-size-class branch and no two-panes rule anywhere in the shell. The shell is a single column with a bottom bar, and it is that at every width.

The manifest does NOT lock orientation. `android:screenOrientation` stays unspecified: locking it fights the system rotation lock, breaks users who mount the phone, and is hostile to accessibility. What we do instead is refuse to grow a second layout.

LANDSCAPE (890 x 400dp) gets the identical shell with three mechanical adjustments, all of which are height rules rather than width rules:
1. The bottom nav collapses from icon+label (56dp) to icon-only (44dp). Three targets at ~296dp each; still the easiest thing on the screen.
2. In Code, the 44dp file bar is hidden and its contents move behind the header's file chip (which already opens the Files sheet, whose top block is the same MRU list). At 400dp of height, the file bar and the editor cannot both be worth having.
3. With the IME up in landscape the arithmetic is brutal and is faced rather than papered over: 400 − 24 status − 44 header − ~250 landscape Gboard − 44 action row = ~38dp of buffer, which is two lines. So in landscape with the IME visible, the header auto-hides on scroll (the editor scrolls under it), yielding ~82dp — four lines — and the caret is always scrolled into view. This is bad and we say so: landscape is a supported orientation, not a recommended one, and the app never encourages it.

SPLIT-SCREEN AND FREEFORM get exactly the same rules; there is no separate compact mode. Below 300dp of content height the destination content scrolls and the bottom bar is the last thing to go. Below 280dp of width nothing changes structurally — the file bar and the log rows already ellipsize with a middle ellipsis on paths, which is the correct truncation for `programs/escrow/src/instructions/initialize.rs`.

TEXT SCALE. The system font scale is honoured through ui/theme/Rem.kt's existing accessibility floor. Every row in every sheet and list is specified to wrap to a second line rather than truncate at 1.3x scale; the only elements permitted to truncate are file paths (middle ellipsis) and base58 addresses (head…tail).

STATE ACROSS ROTATION. Rotation must not lose the caret, the scroll, the log, the terminal session or the sheet that is open. The shell's state lives in ui/shell/ShellState.kt as a retained holder outside composition, in the same way UserlandInstaller.kt already lives outside composition and for the same reason.

What is deliberately abandoned: DeX, foldable inner/outer transitions, external displays, tablet two-column layouts, and the README's foldable/DeX framing. This is a one-device product.

## First run

"Fresh install, no toolchain, no project, no agent." Exactly what happens, in order:

1. THE APP OPENS ON THE GATE. It does not open a splash, a theme picker or a project wizard. It opens the Setup takeover (Screen 13) over Code, because on a fresh install there is nothing else honest to show — a Solana IDE that cannot compile is not the product, and without the toolchain there is no Build, no deploy and an agent that can only read. The gate is mandatory: there is no Skip, the back gesture leaves the app rather than popping it (ShellBackHandler, `gated`), and it goes back up on the next launch until the *required* components are in. On a fresh phone the takeover is a three-page onboarding — why the phone needs a compiler, what to expect while it installs (minutes, Wi-Fi, a charger, that it survives locking the phone, that a failed row retries alone), and the parts with their sizes — with Next on the first two pages and Start on the third. Setup states the cost in THREE numbers that are not the same number: ~680 MB over the network, ~2.1 GB on disk, and about N minutes, where N is measured (the manifest's `estimatedSeconds` from the reference Seeker, or this phone's own recorded timings once it has a full set) and rounded up, never down. It lists eight components, all as sized downloads: `cargo-build-sbf` and `anchor` have no arm64 binary upstream and come prebuilt from our own public workflow (cesp99/solana-tools-arm64, docs/SOLANA.md); a manifest that compiled them on the phone instead would show those two rows with elapsed timers, and the screen still knows how.

2. THE USER TAPS START (or, on a metered connection, "Download over mobile data (~680 MB)", which is not the default focus). The install runs under the existing TerminalService foreground notification, so locking the phone or backgrounding the app is safe. It runs as two lanes — one fetching and unpacking, one working inside the guest — so the 505 MB is off the critical path (docs/SOLANA.md, "How long it takes"). Each component verifies against a pinned SHA-256 from solana/toolchain/manifest.json and resumes a partial download rather than restarting. A failed component gets a Retry on its own row; it never restarts the gigabyte. Leaving the screen keeps it running; coming back re-attaches. The headline shows elapsed against the estimate.

3. THE GATE OPENS ON THE REQUIRED ROWS. Anchor is optional in the manifest; the moment everything Build needs is in, the button reads "Continue — Anchor keeps installing" and the user is let through while it finishes under the notification. Setup returns any time from Projects → Toolchain and Settings → Toolchain, where the same screen is the repair and free-the-disk page and has a Close.

4. WHEN THE GATE OPENS, the Projects sheet opens over an empty Code destination. Empty Code is a single line of body text and one button that opens that same sheet — it is never a blank screen with nothing to press.

5. THE USER PICKS ONE OF THREE. "New program" pushes the New program route: name (with the crate/module/type preview live under the field from SolanaNames.kt), framework (Anchor default), cluster (devnet default), and a checkbox — on by default — "Open a thread and describe it to the agent afterwards". "Clone from GitHub" reuses GitClone.kt with its progress and credential prompts. "Import a folder" is SAF.

6. CREATE SCAFFOLDS AND LANDS. With the checkbox on, the app lands on Agent with the composer pre-seeded "This is a new Anchor program called escrow. " and the keyboard up. With it off, it lands on Code at SolanaFramework.entryPath — lib.rs, not Cargo.toml. Either way the toolchain is not required to get here.

7. THE AGENT. Spettro is bundled and installed by Setup, so the Agent destination works immediately. If Setup was skipped, Agent shows one card — "No agent yet" → Setup — and the composer is disabled. Claude Code and Codex are one-tap installs from the agent picker that first pull Node into the guest and say so before starting.

8. THE FIRST BUILD. Tapping Build with no toolchain pushes Setup instead of failing. Tapping Build with a toolchain saves every dirty buffer, syncs the Anchor program id if declare_id! and the keypair disagree, and runs. Roughly 71 seconds for a cold SBF build, under the foreground service, with the ▶ nav item spinning wherever the user goes and a system notification when it lands.

Deleted from the first-run path entirely: ui/workspace/Onboarding.kt (305), its "three things to try", its theme choice, its command-palette tour, and the sample project written by ProjectsRoot.writeSampleProject. The first run has exactly one job.

## Screens

### Code — the editor (start destination)

Where the file lives. One buffer, full-bleed. No tab strip, no breadcrumbs, no status bar, no minimap, no gutter play buttons. The 44dp header carries identity and the rare exits; the 44dp file bar at the bottom — in the thumb zone — carries the frequent one, which is switching files.

```
┌──────────────────────────────────────────┐
│ escrow ▾    lib.rs ●        ⌕   ✕1   ⋮   │
├──────────────────────────────────────────┤
│ 14│ ) -> Result<()> {                    │
│ 15│     let e = &mut ctx.accounts.escrow;│
│ 16│     e.amount = amount;               │
│ 17│     e.bump = ctx.bumps.esrow;        │
│ ✕ │ no field `esrow` on `EscrowBumps`    │
│   │ E0609                     [ Fix ▸ ]  │
│ 18│     Ok(())                           │
│ 19│ }                                    │
│ 20│                                      │
│ 21│ #[derive(Accounts)]                  │
│ 22│ pub struct Initialize<'info> {       │
│ 23│     #[account(init, payer = signer)] │
│ 24│     pub escrow: Account<'info,       │
│   │ Escrow>,                             │
├──────────────────────────────────────────┤
│ lib.rs● │Anchor.toml│escrow.ts│   ⌕    ☰│
├──────────────────────────────────────────┤
│   ‹›           ✦            ▶            │
│  Code        Agent        Build          │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/editor/EditorPane.kt — the whole virtualized canvas, IME path, selection handles, pinch-zoom, gutter, scrollbar-as-handle. Called unchanged apart from the action row and the vim excision.
- app/src/main/java/to/eyed/seeker/code/ui/editor/EditorState.kt, DisplayMap.kt, EditorDisplay.kt, EditorInput.kt, EditorBuffer.kt, EditorSemantics.kt — untouched.
- app/src/main/java/to/eyed/seeker/code/ui/editor/Diagnostics.kt — inline diagnostics, rendered as a wrapped block row under the offending line via DisplayMap.setBlocks, not at end-of-line.
- app/src/main/java/to/eyed/seeker/code/ui/editor/Hover.kt — the hover card and its 'Go to definition / type definition / implementation / declaration' rows (Hover.kt:462-465). This is the touch route to LSP navigation and it already exists.
- app/src/main/java/to/eyed/seeker/code/ui/editor/Completions.kt — including placeMenuAtCaret (Completions.kt:756-783), which already flips the menu above the caret and already accounts for the IME and the action row.
- app/src/main/java/to/eyed/seeker/code/ui/editor/LspActions.kt, SignatureHelp.kt, SyntaxFolds.kt, Snippets.kt (insertion only), LineTransforms.kt, EditorCommands.kt (pruned, not deleted).
- app/src/main/java/to/eyed/seeker/code/ui/workspace/OpenFiles.kt — the open-buffer model: dirty state, disk-change and deletion detection, per-file encoding and line ending, reopen history, MediaKind routing at :206, and NavHistory. Load-bearing; survives the tab strip's deletion whole.
- app/src/main/java/to/eyed/seeker/code/ui/workspace/AutosaveTracker.kt — the debounce, now driven by leave-a-file rather than a settings toggle nobody finds.
- app/src/main/java/to/eyed/seeker/code/ui/search/BufferSearchBar.kt — find/replace, re-hosted at the bottom of the screen above the IME instead of at the top.
- app/src/main/java/to/eyed/seeker/code/ui/theme/Rem.kt, TouchTarget.kt, Motion.kt, ZedTheme.kt — the layout, hit-target and paint system. Untouched.

**New:**

- ui/shell/code/CodeScreen.kt — the destination host: header, editor, file bar, find bar, route stack. Owns the save-on-leave and external-change resync that currently live inline in WorkspaceScreen.kt (openFileInto, save, formatBeforeSave, resyncBuffers, noteExternalEdit) — these must be carried across, not re-derived.
- ui/shell/code/FileBar.kt — NEW and load-bearing: a 44dp horizontally scrollable chip row pinned directly above the nav bar showing the MRU of open files (● = dirty), with a fixed ⌕ (Files sheet in 'in files' mode) and ☰ (Files sheet) at its right end. This is the answer to 'where did my tabs go', and it is in the thumb zone rather than the top bar. Tap a chip to switch; long-press a chip to close (raising the re-homed UnsavedChangesDialog). It hides when the IME is up.
- ui/shell/code/CodeOverflowSheet.kt — the ⋮ sheet, capped at seven rows: Save, Go to symbol (OutlinePicker), Go to line (GoToLine), Problems, Changes, Share file, Settings.
- Rewrite of EditorActionRow inside ui/editor/EditorPane.kt (currently at :2775, ACTION_ROW_HEIGHT :155). Height goes 38dp → 44dp and every key gets .touchTarget() (48dp). The first eight slots are FIXED and never scroll off: esc · ⇥ · ← · → · undo · redo · save · ▶ Build. Everything else lives behind a ⌄ at the right end which expands the row into a two-row grid: the Rust punctuation set { } ( ) [ ] ; : ' " < > / _ = ! # & | and the LSP set suggest · fix · refs · rename · format · def · prob↑ · prob↓ · fold · // — plus conflict↑/conflict↓ only while the file has conflicts, as today. ▶ Build in the fixed head is the fix for 'the build trigger is never where the work is'.
- The ✕ gutter marker becomes a 48dp target that opens the code-actions sheet (quick fixes) — LspActions already computes them; only the sheet presentation is new.
- A build-state dot painted on the ▶ nav item, fed by solana/build/BuildRunner.kt.

### Code with the soft keyboard up — the typing posture

The posture the device is actually in for most of a session, drawn explicitly because it is where the vertical budget is decided. This is the screen the whole 'no keyboard' criterion is answered on.

```
┌──────────────────────────────────────────┐
│ escrow ▾    lib.rs ●        ⌕   ✕1   ⋮   │
├──────────────────────────────────────────┤
│ 21│ #[derive(Accounts)]                  │
│ 22│ pub struct Initialize<'info> {       │
│ 23│     #[account(init, payer = signer)] │
│ 24│     pub escrow: Account<'info,       │
│   │ Escrow>,                             │
│ 25│     #[account(mut)]                  │
│ 26│     pub signer: Signer<'info>,       │
│ 27│ }▏                                   │
├──────────────────────────────────────────┤
│ esc  ⇥   ←   →   ↶   ↷  save  ▶      ⌄   │
├──────────────────────────────────────────┤
│  q  w  e  r  t  y  u  i  o  p            │
│   a  s  d  f  g  h  j  k  l              │
│  ⇧   z  x  c  v  b  n  m    ⌫            │
│ ?123  ,          space         .    ↵    │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/editor/EditorPane.kt — imeOverlapPx() already lifts the row onto the keyboard; WindowInsets.isImeVisible already gates it; the `act {}` wrapper already keeps focus on the canvas so a key tap does not end the IME session and drop the keyboard under the finger. All three behaviours are inherited, not invented.
- app/src/main/java/to/eyed/seeker/code/ui/editor/Completions.kt — placeMenuAtCaret flips the completion popup above the caret when the caret is in the lower half, so the IME never covers it.

**New:**

- The eight-fixed-key head described above, and the ⌄ expansion. Explicit budget, portrait, 890dp: 24 status + 44 header + ~300 Gboard + 44 action row + 24 gesture inset = 436, leaving ~454dp of buffer, ~24 wrapped lines at buffer_font_size 15. The nav bar's 56dp is reclaimed precisely because it is hidden here; the file bar's 44dp is reclaimed for the same reason. Nothing else may be added to this stack.
- A rule the implementation must hold: the action row and the inline diagnostic block row may both be present. If the caret's line has a diagnostic and the remaining buffer height falls below 200dp, the inline block collapses to a one-line summary with a ▸ that opens the code-actions sheet.

### Files & Find (sheet over Code)

The only file-navigation surface in the app. It replaces the project-panel dock, the Ctrl+P fuzzy finder, the Ctrl+Tab switcher, the project-search panel and the outline picker's file half with one sheet, reached from the bottom-zone ☰ or ⌕.

```
┌──────────────────────────────────────────┐
│▒▒▒▒▒ editor, dimmed and inert ▒▒▒▒▒▒▒▒▒▒▒│
│╭────────────────────────────────────────╮│
││                 ────                   ││
││  OPEN                                  ││
││   ● lib.rs        programs/escrow/src ✕││
││   ○ Anchor.toml   ./                  ✕││
││   ○ escrow.ts     tests/              ✕││
││  ────────────────────────────────────  ││
││  ▾ programs/escrow/src                 ││
││      lib.rs                         M  ││
││      state.rs                          ││
││  ▸ tests                               ││
││    Anchor.toml                      M  ││
││    Cargo.toml                          ││
││  ────────────────────────────────────  ││
││ ┌────────────────────────────────────┐ ││
││ │ ⌕ vault           names │ in files │ ││
││ └────────────────────────────────────┘ ││
││  ＋ New file        ⑂ Changes          ││
│╰────────────────────────────────────────╯│
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/workspace/ProjectTreeState.kt — the lazy, gitignore-aware worktree and expand model.
- app/src/main/java/to/eyed/seeker/code/ui/workspace/ProjectPanel.kt — the tree rendering, git status colours (GitStatusColours.kt), file icons (ZedFileIcons.kt, FileIcons.kt, ProjectEntryIcons.kt). Kept and re-hosted in the sheet; row height raised from its current density to 44dp.
- app/src/main/java/to/eyed/seeker/code/ui/workspace/ProjectPanelMenu.kt and ProjectFileOps.kt — rename, duplicate, delete-to-trash-with-undo, new file/folder here, open in Shell, search in this folder. Reached by long-press, presented as a sheet.
- app/src/main/java/to/eyed/seeker/code/ui/workspace/FileFinder.kt — the engine-side fuzzy matcher and its match highlighting (ProjectSession.findFiles, core/ProjectSession.kt:160). Becomes the 'names' mode of the one field.
- app/src/main/java/to/eyed/seeker/code/core/SearchSession.kt and ui/search/ProjectSearchRows.kt — the cancellable engine-side project search. Becomes the 'in files' mode; results are a flat tappable list of path:line + matched text.

**New:**

- ui/shell/code/FilesSheet.kt — the sheet host, the OPEN/tree/filter composition, and the names ⇄ in-files toggle. The filter field is pinned at the BOTTOM of the sheet, immediately above where the IME appears.
- ProjectPanel.kt is called with a single root and no per-root header, no multi-select, no drag-between-folders, no sticky ancestors. Those code paths are deleted, not hidden.
- ui/search/ProjectSearchPanel.kt is called with onOpenMultibuffer = null and multibufferIsDefault = false — the phone path already exists in that signature, so dropping the multibuffer costs a null at the call site.

### Agent

The ACP conversation for the open project, first-class. On this device the agent is the primary text-input method for code, so it is a destination with equal billing, not a dock panel.

```
┌──────────────────────────────────────────┐
│ escrow ▾   Spettro · coding ▾    ＋   ⋮  │
├──────────────────────────────────────────┤
│ you ──────────────────────────────────── │
│ add a bump seed to the escrow account    │
│ and use it in initialize                 │
│                                          │
│ Spettro ──────────────────────────────── │
│ I'll add the field and the constraint,   │
│ then build.                              │
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │ ✎ programs/escrow/src/state.rs       │ │
│ │   +3 −1                     view  →  │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ ⚠ run `anchor build`?                │ │
│ │ [  Allow  ] [ Always ] [  Deny  ]    │ │
│ └──────────────────────────────────────┘ │
│ ── plan 2/5 · editing state.rs ───────── │
├──────────────────────────────────────────┤
│  2 files changed          Review  →      │
├──────────────────────────────────────────┤
│ @ ┌────────────────────────────────┐  ➤  │
│   │ tell it what to build…         │     │
├──────────────────────────────────────────┤
│   ‹›           ✦            ▶            │
│  Code        Agent        Build          │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/agent/AgentPanel.kt (3658 lines) — Conversation, ToolCallCard, TerminalCard, DiffCard, ElicitationCard, PlanStrip, CompletedPlanCard, ActivityStrip, QueuedRow, attachment and mention chips, the pendingCount banner. Signature (AgentPanel.kt:231) already takes only (project, focusToken, onOpenPath, onOpenSettings, onOpenReview, workspace, onFocusChanged) — no dock, no pane, no command. Re-hosting is a call-site change.
- app/src/main/java/to/eyed/seeker/code/core/AgentSession.kt, AgentSessions.kt, AgentMentions.kt, AgentNotifier.kt, Agents.kt — the ACP layer and the background notification.
- app/src/main/java/to/eyed/seeker/code/ui/agent/ContextPicker.kt — the @ mention picker, re-presented as a searchable bottom sheet.
- app/src/main/java/to/eyed/seeker/code/solana/agents/AgentCatalog.kt — the three one-tap installs (Spettro native, Claude Code and Codex via npm). Currently orphaned; this screen is what reaches it.
- ui/common/MarkdownText.kt — extracted in chunk P0 from ui/preview/MarkdownPreview.kt:869 and the inline half of MarkdownDocument.kt. AgentPanel calls it at five sites; the extraction is a precondition for deleting ui/preview.

**New:**

- ui/shell/agent/AgentScreen.kt — dissolves AgentBar (AgentPanel.kt:515) into the destination's own top row: project chip left, agent+mode chip centre, ＋ new thread, ⋮ (Threads, Checkpoints, Agent settings). The horizontally scrolling ComposerChrome selector strip (AgentPanel.kt:1050) is kept but collapsed into the `Spettro · coding ▾` chip, which opens a sheet carrying Spettro's Mode / Model / Permission / Thinking / Ultra selectors and the live context gauge, per docs/SOLANA.md. It is a sheet, not 30dp of permanent chips.
- ui/shell/agent/AgentPickerSheet.kt — switch or install an agent. Spettro is the bundled default; the other two are one-tap installs that first install Node in the guest and say so. Any other ACP agent is an agent_servers entry via Settings → Edit settings.json.
- The sticky '2 files changed · Review →' bar above the composer, pushing the Changes route. This is the trust surface; it must not scroll away.
- Degradation: with no agent installed, the transcript is replaced by one card — 'No agent yet' → Setup — and the composer is disabled rather than the screen being an empty dead end.

### Build — the payoff loop

The screen the app exists for. Which cluster, which wallet, how much SOL, which program, what the compiler said, and three buttons — all on one 890dp column, with the primary action at the bottom right where the thumb rests.

```
┌──────────────────────────────────────────┐
│ escrow ▾   Anchor          ⌗ Shell   ⋮   │
├──────────────────────────────────────────┤
│ ◈ devnet ▾   7NJd…4kQz   2.41 ◎     ⭳    │
├──────────────────────────────────────────┤
│ Fg6PaFpo…zPFsLnS  ⧉      not deployed    │
├──────────────────────────────────────────┤
│ 14:22  anchor build                      │
│    Compiling escrow v0.1.0               │
│    Compiling anchor-lang v0.31.1         │
│ ✕ error[E0609]: no field `esrow` on      │
│   type `EscrowBumps`                     │
│      programs/escrow/src/lib.rs:17:26 →  │
│ ! warning: unused import `std::mem`      │
│      programs/escrow/src/lib.rs:2:5   →  │
│                                          │
│ ── failed · 1 error, 1 warning · 1m11s ─ │
│                                          │
│ [ Fix with agent ]      [ Problems 2 → ] │
│                                          │
├──────────────────────────────────────────┤
│ ┌────────┐ ┌────────┐ ┌────────────────┐ │
│ │  Test  │ │ Deploy │ │   ▶  Build     │ │
│ └────────┘ └────────┘ └────────────────┘ │
├──────────────────────────────────────────┤
│   ‹›           ✦            ▶            │
│  Code        Agent        Build          │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/tasks/TaskRunner.kt — runTask() already builds the shell line through ShellEnvironment.taskCommand, spawns it into a terminal session, registers Activities.begin for progress, and posts a failure notification with a 'Show output' action. Build/Test/Deploy become three fixed TaskSpecs through this existing runner. The picker, tasks.json, the $ZED_* variables and the gutter runnables are deleted; the runner is not.
- app/src/main/java/to/eyed/seeker/code/terminal/TerminalPanelState.kt, TerminalSessionHost.kt, TerminalService.kt, ShellEnvironment.kt, Userland.kt, DebianUserland.kt — the proot session layer and the foreground service that lets a 1m11s build survive the screen turning off.
- app/src/main/java/to/eyed/seeker/code/ui/terminal/TerminalLinks.kt and TerminalPathTarget.kt — PathWithPosition(path, row, column) detection, which is what makes the `path:line:col →` row a one-tap jump into Code at the caret.
- app/src/main/java/to/eyed/seeker/code/ui/workspace/ActivityIndicator.kt, Notifications.kt, NotificationHost.kt — progress and the error channel.
- app/src/main/java/to/eyed/seeker/code/solana/templates/SolanaTemplates.kt — SolanaFramework decides which command each button runs.

**New:**

- solana/build/BuildTasks.kt — the framework → command table from docs/SOLANA.md, as three TaskSpecs. Anchor: `anchor build` / `anchor test` / deploy. Native: `cargo build-sbf` / `cargo test` / deploy. Seahorse: `seahorse build` / `anchor test` / deploy.
- solana/build/BuildRunner.kt — the one entry point. It does, atomically and in this order: (1) save every dirty buffer in the project and wait for the writes; (2) for Anchor, run `anchor keys sync` if declare_id! and the program keypair disagree; (3) spawn the task through TaskRunner; (4) stream to the log; (5) parse; (6) publish. A second press while running is Stop, not a second build. Save-before-build is not optional and not a setting — the judges' 'edit, tap Build, read the error you already fixed' failure is designed out.
- solana/build/CargoDiagnostics.kt — a parser for `cargo --message-format=json-diagnostic-rendered-ansi` plus a line-regex fallback for everything that is not a rustc diagnostic: proc-macro panics, IDL generation errors, `Failed to execute rustup`, lld/linker errors, `Error: Unable to read keypair file`, mocha failures. Anything unparsed still renders as a monospace row with a long-press 'Copy' and 'Ask the agent about this'. The design does not depend on the parser being perfect.
- solana/build/BuildDiagnostics.kt — the merge layer. The engine's diagnostics store is READ-ONLY across JNI (CoreBridge.lspDiagnostics / lspDiagnosticRows / bufferDiagnostics are external reads with no ingest), so cargo diagnostics are held here and merged with the engine's at every consumer: the editor's block rows, the ✕ gutter marks, the Problems route and the header counts. Each row is tagged with its producer ('cargo · anchor build' or 'rust-analyzer') so a stale build error is distinguishable from a live one, and the cargo set is cleared at the start of each run.
- ui/shell/build/BuildScreen.kt — the destination host, the virtualized log, and the three buttons. Order left→right is Test, Deploy, Build; Build is the widest and rightmost (thumb rest); Deploy is separated from Build by Test rather than adjacent to it, because Deploy spends SOL and Build gets hammered. While a run is going, the three buttons collapse to one `■ Stop · Building 0:38` row and a determinate progress line sits above them.
- The result card: `[ Fix with agent ]` pushes the failing diagnostics (message, code, path:line:col, the rendered snippet) into the Agent composer and switches to Agent. `[ Problems N → ]` pushes the Problems route.
- Freshness: the program row reads `not deployed` / `deployed · 3 min ago` / `stale — edited since the last build`. Deploy with a stale or missing artifact offers 'Build and deploy' rather than silently shipping an old .so.
- Test honesty. Anchor's scaffolded `[scripts] test` is `yarn run ts-mocha …` and the manifest ships no Node. So: for Native, Test runs `cargo test` and works. For Anchor, the first Test press opens a sheet — 'Anchor tests need Node and yarn (~90 MB). Install now?' — and offers `cargo test` for the program's own Rust tests as the alternative. Test also passes `--skip-local-validator` and targets the selected cluster, because there is no local validator on this phone (Agave has no arm64 build). None of this is hidden behind a button that just fails.

### Build → Shell (terminal mode)

The escape hatch. Everything the three buttons do not cover — `solana config`, `anchor keys list`, `apt install`, `solana logs`, `git log` — in the same Debian proot the build uses, in the same working directory.

```
┌──────────────────────────────────────────┐
│ escrow ▾   Shell           ⌗ Build   ⋮   │
├──────────────────────────────────────────┤
│ root@seeker:~/escrow# solana balance     │
│ 2.41 SOL                                 │
│ root@seeker:~/escrow# anchor keys list   │
│ escrow: Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEf…  │
│ root@seeker:~/escrow# ▊                  │
│                                          │
│                                          │
│                                          │
│                                          │
│                                          │
│                                          │
├──────────────────────────────────────────┤
│ esc ctl alt ⇥  ←  ↓  ↑  →  |  ~  /  ⌨    │
├──────────────────────────────────────────┤
│   ‹›           ✦            ▶            │
│  Code        Agent        Build          │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/terminal/TerminalPane.kt — the Termux emulator/view host and ExtraKeysRow at :912, which already scrolls horizontally and already applies .touchTarget() to every key with a comment saying why. Kept as the accessory row, including the ⌨ show/hide-keyboard key, which is the only way to get the keyboard back after dismissing it to read scrollback.
- vendor/ — terminal-emulator and terminal-view, untouched.
- app/src/main/java/to/eyed/seeker/code/terminal/TerminalSessionHost.kt, TerminalService.kt, ShellCwd.kt.
- app/src/main/java/to/eyed/seeker/code/ui/terminal/TerminalLinks.kt — clickable path:line:col in output, still opening Code.

**New:**

- ui/shell/build/ShellMode.kt — a boolean on the Build destination, toggled by the `⌗ Shell` / `⌗ Build` header chip and remembered per project. Two sessions exist at most: one interactive shell, born in the project root and reused forever, and one transient build session owned by BuildRunner. There are no terminal tabs, no new/close/next/previous session, and no terminal search bar.
- Back from Shell goes to Code, not to Build: Shell and Build are one destination in two modes.

### Deploy (sheet over Build)

The one screen in the app that spends money. Everything that decides whether the deploy will work or will cost the wrong amount, on one sheet, with the confirm at the bottom.

```
┌──────────────────────────────────────────┐
│▒▒▒▒▒▒▒ Build, dimmed and inert ▒▒▒▒▒▒▒▒▒▒│
│╭────────────────────────────────────────╮│
││                ────                    ││
││  DEPLOY                                ││
││  Cluster           devnet              ││
││  Program           Fg6PaFpo…zPFsLnS    ││
││  Artifact          escrow.so · 214 KB  ││
││                    built 30 s ago      ││
││  Signer            Seed Vault          ││
││                    7NJd…4kQz           ││
││  Rent + fees       ~1.49 ◎             ││
││  Balance           2.41 ◎              ││
││  ────────────────────────────────────  ││
││  ⚠ declare_id! does not match the      ││
││    program keypair.                    ││
││        [ Sync ids and rebuild ]        ││
││  ────────────────────────────────────  ││
││ ┌──────────┐  ┌──────────────────────┐ ││
││ │  Cancel  │  │       Deploy         │ ││
││ └──────────┘  └──────────────────────┘ ││
│╰────────────────────────────────────────╯│
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/workspace/Notifications.kt — deploy failures and successes report here.
- app/src/main/java/to/eyed/seeker/code/terminal/ShellEnvironment.kt — for the keypair-signer path, which runs `solana program deploy` in the guest.

**New:**

- solana/chain/ProgramDeploy.kt — and this is the honest part. `solana program deploy` is an Agave CLI process inside proot; it cannot call out to an Android Mobile Wallet Adapter activity for a signature, and a program deploy is not one transaction but hundreds of buffer-write chunks plus a final upgrade. So there are two implementations and the sheet says which is in use: (a) KEYPAIR SIGNER — the guest CLI does the whole deploy with a filesystem keypair; this is the devnet default and is the path that works on day one. (b) SEED VAULT / MWA SIGNER — a Kotlin-side deploy that builds, signs and submits the chunk transactions itself through the MWA session, with the CLI used only for `program show`. (b) is the differentiator and is scheduled after (a); until it lands, the Signer row offers Seed Vault only for the final upgrade authority and says so. No wireframe pretends (b) is free.
- solana/chain/AnchorToml.kt — reads and writes `[provider] cluster` and `[programs.<cluster>]`. The cluster chip is not decorative: changing it rewrites Anchor.toml, so `anchor build/test/deploy` and the UI can never disagree.
- Program-id reconciliation. Before any Anchor deploy, compare `declare_id!` in lib.rs, `target/deploy/<name>-keypair.json` and Anchor.toml. On mismatch the sheet shows the ⚠ row above and `[ Sync ids and rebuild ]` runs `anchor keys sync` + rebuild. This is the number-one first-deploy failure (DeclaredProgramIdMismatch) and the scaffold ships a placeholder id (SolanaTemplates.kt:48), so it is on the happy path, not an edge case.
- Buffer recovery. A deploy interrupted over mobile data leaves a buffer account holding real rent. On failure the sheet becomes a recovery sheet: the buffer address, its SOL, `[ Resume deploy ]` (--buffer) and `[ Close buffer and reclaim ]`. This is the failure mode most specific to deploying from a phone.
- Mainnet-beta gets a second, red confirm naming the program, the cluster and the SOL cost. Airdrop is not offered off devnet/testnet/localnet.
- On success: the program row flips to `deployed · just now`, the transaction signature is shown with a copy button and an explorer link, and the program is recorded in DeployedPrograms.

### Wallet (sheet over Build)

The thing a laptop cannot do. Which key signs, what it holds, how to top it up on devnet, and what this phone has already put on chain.

```
┌──────────────────────────────────────────┐
│▒▒▒▒▒▒▒ Build, dimmed and inert ▒▒▒▒▒▒▒▒▒▒│
│╭────────────────────────────────────────╮│
││                ────                    ││
││  WALLET                                ││
││  ◉ Seed Vault                          ││
││    7NJdQ2xk…W8kM4kQz            ⧉  ⊞   ││
││    2.41 ◎ on devnet                    ││
││  ○ Keypair file      devnet only       ││
││    ~/.config/solana/id.json            ││
││    0.00 ◎                              ││
││  ────────────────────────────────────  ││
││  [  Airdrop 2 SOL  ]  [   Refresh   ]  ││
││  ────────────────────────────────────  ││
││  DEPLOYED FROM THIS PHONE              ││
││   escrow        Fg6PaFpo…zPFsLnS    ⋮  ││
││   devnet · upgradeable · 1.42 ◎        ││
││   hello-solana  9wQtN4bR…hK4Lm2     ⋮  ││
││   devnet · 0.81 ◎                      ││
│╰────────────────────────────────────────╯│
└──────────────────────────────────────────┘
```

**Reuses:**

- Nothing. There is no solana/wallet package today and no MWA or Seed Vault dependency in app/build.gradle.kts or gradle/libs.versions.toml. This screen is greenfield and the plan says so.

**New:**

- solana/chain/Wallet.kt, SeedVaultSigner.kt, KeypairSigner.kt — the two signers behind one interface.
- solana/chain/Rpc.kt — getBalance, requestAirdrop, getAccountInfo for program accounts, getSignatureStatuses. Kotlin-side JSON-RPC over OkHttp; the only outbound network call the shell makes besides the toolchain download and git.
- solana/chain/DeployedPrograms.kt — a local record of what this device deployed (name, id, cluster, authority, rent). Per-program ⋮: copy id, view on explorer, set as this project's program id, close program and reclaim rent (with a confirm).
- Cluster and wallet are one state per project, shared by the Build header, this sheet, Anchor.toml and Settings. There is not one cluster per screen.
- Address: tap to copy, ⊞ for a QR. Airdrop is disabled with an explanatory caption on mainnet-beta rather than hidden, so nobody hunts for it.

### Problems

Every diagnostic in the project from both producers — rust-analyzer and the last build — in one grouped list. 'Read errors' is a named step in the user's loop and it gets a real screen.

```
┌──────────────────────────────────────────┐
│ ←  Problems              all ▾    ✕1 !3  │
├──────────────────────────────────────────┤
│ programs/escrow/src/lib.rs            2  │
│  ✕ 17:26  no field `esrow` on type       │
│           `EscrowBumps`          E0609   │
│           cargo · anchor build           │
│  ! 2:5    unused import: `std::mem`      │
│                                          │
│ programs/escrow/src/state.rs          1  │
│  ! 8:5    field is never read: `bump`    │
│           rust-analyzer                  │
│                                          │
│ tests/escrow.ts                       1  │
│  ! 8:1    'anchor' is declared but       │
│           never read             ts6133  │
│                                          │
├──────────────────────────────────────────┤
│ [ Fix with agent ]                       │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/diagnostics/DiagnosticsPane.kt (715) and DiagnosticsRows.kt — already a grouped, per-file, tappable list with sticky headers, and its onOpenMultibuffer parameter is already nullable. Passing null is the whole change. All three competing designs either deleted this or rebuilt it; re-hosting it is the cheapest path and it is better than what they proposed.
- app/src/main/java/to/eyed/seeker/code/ui/editor/Diagnostics.kt — the row model.

**New:**

- ui/shell/changes/ProblemsScreen.kt — a thin route host: the ← , the all/errors/warnings filter, the merged source from solana/build/BuildDiagnostics.kt, and the producer tag under each row.
- Rows wrap to two or three lines rather than truncate — an E0609 clipped at 40 columns tells you nothing.
- A tap opens Code at the exact row and column and pops the route, so build → tap error → fix → build is two taps back.
- [ Fix with agent ] sends the whole filtered set into the Agent composer.

### Changes — agent review and git, one screen

The two questions a phone dev asks about a diff — 'what did the agent just do to my files' and 'what am I about to commit' — are the same bytes, so they are the same screen. This is the trust surface of an agent-first product.

```
┌──────────────────────────────────────────┐
│ ←  Changes            main ▾   ↑2 ↓0   ⋮ │
├──────────────────────────────────────────┤
│ AGENT EDITS (2)             [ Keep all ] │
│  ~ programs/escrow/src/state.rs  +3 −1 → │
│  ~ programs/escrow/src/lib.rs   +12 −2 → │
├──────────────────────────────────────────┤
│ YOUR CHANGES (2)             [ Stage all]│
│  ☑ M tests/escrow.ts             +4 −0 → │
│  ☐ ? target/deploy/escrow.so             │
├──────────────────────────────────────────┤
│ ⚠ 1 conflict — Anchor.toml            →  │
├──────────────────────────────────────────┤
│                                          │
│                                          │
├──────────────────────────────────────────┤
│ ┌──────────────────────────────────────┐ │
│ │ add bump seed to escrow account      │ │
│ └──────────────────────────────────────┘ │
│ ┌───────────────┐  ┌───────────────────┐ │
│ │    Commit     │  │  Commit & Push    │ │
│ └───────────────┘  └───────────────────┘ │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/core/AgentSessions.kt — keepEdits(paths) / rejectEdits(paths) at :530 and :538, over CoreBridge.acpEditedFiles. Note these are per-FILE, not per-hunk; the design uses file granularity for agent edits and does not pretend otherwise.
- app/src/main/java/to/eyed/seeker/code/ui/agent/AgentReviewPane.kt — the Keep/Reject row model, re-hosted as the top block.
- app/src/main/java/to/eyed/seeker/code/core/GitSession.kt (1035) — status, stage, unstage, commit, push, pull, branch switch, discard, stageHunk (:315), restoreHunk (:998).
- app/src/main/java/to/eyed/seeker/code/ui/git/GitOps.kt, GitDrafts.kt, GitBranchState.kt, DiffStaging.kt, AskpassDialog.kt (252) and core/GitAskpass.kt — the askpass dialog is the only thing that makes `git push` over HTTPS or SSH work at all; it is kept.
- app/src/main/java/to/eyed/seeker/code/ui/git/BranchPickerRows.kt — the row model behind the `main ▾` chip.
- app/src/main/java/to/eyed/seeker/code/ui/editor/Conflicts.kt, ConflictView.kt and ui/git/MergeResolvedBar.kt — conflicts resolve in the editor with the inherited tinted regions and Use HEAD / Use branch / Use both. A pull on a phone will eventually produce one, and 'go use the terminal' is not an answer for a product whose thesis is that the phone is enough.

**New:**

- ui/shell/changes/ChangesScreen.kt — the route host and the three-block composition. GitPanel.kt (3565) is NOT re-hosted: its keyboard navigation, amend, co-author editor and menu depth are the reason it is that size. The engine underneath it (GitSession) is what survives.
- ui/shell/changes/CommitSheet.kt — message field, staged count, an 'Ask the agent for a message' button, Commit / Commit & Push.
- `main ▾` is a short branch list plus 'New branch…'. `↑2 ↓0` taps into pull/push. Long-press a row → Discard changes, with a confirm. That is the only destructive git operation offered.
- The branch picker is a sheet of ~40 lines over BranchPickerRows, not BranchPicker.kt's 889-line create/checkout/track/remote matrix.

### Diff — the one diff surface

Shared by the agent's file-edit cards and by Changes. There is no second diff, no split diff, no diff-as-a-tab, and no editable multibuffer.

```
┌──────────────────────────────────────────┐
│ ←  state.rs                    +3 −1     │
├──────────────────────────────────────────┤
│  6   #[account]                          │
│  7   pub struct Escrow {                 │
│  8       pub amount: u64,                │
│ +9       pub bump: u8,                   │
│ 10   }                                   │
│  ⋯  expand 12 lines                      │
│ 22   #[derive(Accounts)]                 │
│ −23      pub escrow: Account<'info,      │
│          Escrow>,                        │
│ +23      #[account(init, payer =         │
│          signer, space = 8 + 17)]        │
│ +24      pub escrow: Account<'info,      │
│          Escrow>,                        │
│                                          │
├──────────────────────────────────────────┤
│ ┌───────────────┐  ┌───────────────────┐ │
│ │   ✕ Reject    │  │      ✓ Keep       │ │
│ └───────────────┘  └───────────────────┘ │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/ui/git/DiffPane.kt (578) — the unified diff renderer and its collapsed-context expansion.
- app/src/main/java/to/eyed/seeker/code/ui/git/HunkControls.kt and DiffStaging.kt — per-hunk stage/discard against the git index, offered on long-press of a hunk. Available for git rows only; agent rows are file-granular because AgentSessions only offers file granularity.
- app/src/main/java/to/eyed/seeker/code/ui/theme/ZedTheme.kt — the +/- colours.

**New:**

- ui/shell/changes/DiffScreen.kt — the route host and the bottom action pair. Header actions are contextual: from Agent it is ✕ Reject / ✓ Keep; from Changes it is Discard / Stage. They are at the BOTTOM, not next to the back arrow, because on this device accepting an agent's edit is a high-frequency consequential one-handed action.
- DiffPane.kt:543 currently hard-sets softWrap = false. This must change: diffs word-wrap, never scroll horizontally. Because a wrapped continuation of a `−` line is otherwise indistinguishable from a `+` line, every visual row of a changed line gets a tinted background from the theme, not just a leading sign on the first row.
- Read-only. Editing happens in Code.

### Setup — first run and the toolchain page thereafter

The one full-screen takeover, and on a fresh install the gate. It is the honest cost of a phone that compiles Solana programs, said plainly, once, before the button — three onboarding pages, then this list. There is no Skip: everything that makes this an IDE rather than an editor is behind it.

```
┌──────────────────────────────────────────┐
│                                          │
│                    ◎                     │
│                Seeker IDE                │
│                                          │
│    Build and ship Solana programs        │
│          from your phone.                │
│                                          │
│ ──────────────────────────────────────── │
│ One setup, once. ~600 MB down, ~1.4 GB   │
│ on disk, then the phone builds offline.  │
│                                          │
│  ✓  Debian userland          30 MB       │
│  ✓  rustup (manager only)    15 MB       │
│  ▶  SBF platform-tools  312 / 505 MB     │
│     ████████████░░░░░░░░  62 % · 4 MB/s  │
│  ·  rust-analyzer            40 MB       │
│  ·  Spettro (agent)          15 MB       │
│  ·  cargo-build-sbf     builds on device │
│  ·  Anchor              builds on device │
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │               Pause                  │ │
│ └──────────────────────────────────────┘ │
│                                          │
└──────────────────────────────────────────┘
```

The gate has no text link under the button while the install runs. Once the required rows are in the button reads Continue; reached from Settings afterwards the same screen has Close and "Remove the toolchain — frees 2.1 GB".

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/terminal/UserlandInstaller.kt — already owns an install outside the composition, already reports (step, fraction), already survives the panel going away, already resumes and cancels cleanly, and already guarantees the completion callback lands on the main thread. The Solana installer is modelled on it and reuses its scope discipline.
- app/src/main/java/to/eyed/seeker/code/terminal/Userland.kt and terminal/DebianUserland.kt — the first row of the list.
- app/src/main/java/to/eyed/seeker/code/terminal/TerminalService.kt — the foreground service that keeps a download and an on-device compile alive when the screen locks.
- app/src/main/java/to/eyed/seeker/code/solana/agents/AgentCatalog.kt — Spettro's ReleaseTarball install.

**New:**

- solana/toolchain/manifest.json — the component list as data: URL, SHA-256, unpacked size, install path. A toolchain bump is a manifest edit, not a release. This file does not exist yet.
- solana/toolchain/ToolchainManifest.kt, ToolchainInstaller.kt, ToolchainState.kt — the fetch → verify → unpack loop with per-component resume across a dropped connection AND across app restarts, per-component Retry that never restarts the gigabyte, and the two proot rules from docs/SOLANA.md that cost real time to find: `--link2symlink` is used for apt and nothing else (it breaks `cargo install`'s hard-link-into-place, leaving a dangling symlink and a silent success), and large archives are unpacked with the host's tar directly into the rootfs rather than through proot, with the one hard-linked entry in Debian's rootfs handled specially.
- ui/shell/setup/SetupScreen.kt — the takeover, and later the Settings → Toolchain page, where it doubles as the repair / uninstall / free-1.4-GB page.
- The progress model has TWO kinds of row and says so. Downloads show bytes and a rate. A `cargo-install` row — none in the shipped manifest since the two drivers went prebuilt, but the method is kept — shows an elapsed timer and a spinner, never a MB bar, and before it starts the minutes it took on the reference phone. A row whose bytes are in but whose turn on the guest lane has not come is "downloaded · waiting for its turn" with a dashed circle, not a spinner. The headline separates transfer (~680 MB) from disk (~2.1 GB) and adds minutes as a third figure, because conflating them is the exact dishonesty a first-run screen cannot afford.
- ShellBackHandler's `gated` step: Setup on top with no toolchain is never popped by back. What is on top of it — a sheet, an overlay, the keyboard — still closes first.
- Metered connections: the Start button reads 'Download over mobile data (~600 MB)' and is not the default focus.

### Projects & tools (sheet from the project chip)

Switch project, make one, and reach the four things that are configuration rather than work. A sheet rather than a fourth destination because it is visited once a session, not once a minute.

```
┌──────────────────────────────────────────┐
│╭────────────────────────────────────────╮│
││                ────                    ││
││  PROJECTS                              ││
││  ● escrow          Anchor · devnet     ││
││    deployed · 2 min ago                ││
││    token-vault     Anchor              ││
││    yesterday                           ││
││    hello-solana    Native              ││
││    3 days ago                          ││
││  ────────────────────────────────────  ││
││  ＋  New program                       ││
││  ⤓  Clone from GitHub                  ││
││  ⤒  Import a folder                    ││
││  ────────────────────────────────────  ││
││  Wallet        Seed Vault · 2.41 ◎   > ││
││  Toolchain     installed · 1.4 GB    > ││
││  Settings                            > ││
│╰────────────────────────────────────────╯│
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/core/ProjectsRoot.kt — list, create, delete, nameError, uniqueName, lastOpened. ProjectSummary is the row model.
- app/src/main/java/to/eyed/seeker/code/core/ProjectSession.kt — open/close.
- app/src/main/java/to/eyed/seeker/code/terminal/GitClone.kt (688) — clone with progress and credential prompts.
- app/src/main/java/to/eyed/seeker/code/core/SafTransfer.kt and ui/workspace/ImportDialog.kt — SAF folder import and export.
- app/src/main/java/to/eyed/seeker/code/core/ShareOut.kt — share a file or a project out.

**New:**

- ui/shell/projects/ProjectsSheet.kt — replaces ProjectPicker.kt's modal and RecentProjectsPicker.kt's overlay with one list. Long-press a row: Rename / Export / Delete. Switching project switches the agent thread with it (AgentSessions is already keyed by project) and drops the terminal sessions, which is the existing rule.
- One project is one folder. AddFolderDialog and multi-root worktrees are gone.
- The last three rows are pinned; they are the only route to Wallet, Toolchain and Settings, none of which is a destination.

### New program

Scaffold an Anchor, Native or Seahorse program and hand it straight to the agent. This is the 'create' step of the loop and it is three taps plus a name.

```
┌──────────────────────────────────────────┐
│ ←  New program                           │
├──────────────────────────────────────────┤
│  Name                                    │
│  ┌────────────────────────────────────┐  │
│  │ escrow                             │  │
│  └────────────────────────────────────┘  │
│  crate escrow · mod escrow · type Escrow │
│                                          │
│  Framework                               │
│  ┌────────────────────────────────────┐  │
│  │ ◉ Anchor    Rust program + tests   │  │
│  │ ○ Native    solana-program only    │  │
│  │ ○ Seahorse  Python → Anchor        │  │
│  └────────────────────────────────────┘  │
│                                          │
│  Cluster              [ devnet     ▾ ]   │
│                                          │
│  ☑ Open a thread and describe it to      │
│    the agent afterwards                  │
│                                          │
├──────────────────────────────────────────┤
│ ┌───────────────┐  ┌───────────────────┐ │
│ │    Cancel     │  │      Create       │ │
│ └───────────────┘  └───────────────────┘ │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/solana/templates/SolanaTemplates.kt — SolanaFramework, SolanaProgram.of(), files(program), entryPath(program). Already written, currently referenced from nowhere outside the solana package; this screen is what reaches it. Note R8 is on for release builds, so this reference matters.
- app/src/main/java/to/eyed/seeker/code/solana/templates/SolanaNames.kt — crateName / moduleName / typeName / error, driving the live preview line under the field.
- app/src/main/java/to/eyed/seeker/code/core/ProjectsRoot.kt — nameError and create.

**New:**

- ui/shell/projects/NewProgramScreen.kt — the route. Create scaffolds, sets the cluster into Anchor.toml, opens the project, and with the checkbox on (the default) lands on Agent with the composer pre-seeded 'This is a new Anchor program called escrow. ' rather than on a file tree. That default is this design's thesis expressed as one checkbox.
- The toolchain gate comes before Create: on a fresh phone the takeover is up until the required components are in, and Anchor may still be downloading when the user gets here. Scaffolding and editing do not need it; the first `anchor build` does, and the Build tab says so while it is still going.
- Seahorse honesty: selecting it shows a one-line caption that Seahorse builds through `anchor build` and needs Python in the guest, and the Build button installs it on first press. A framework you can create but cannot build is worse than one never offered.

### Settings

One scrolling list. Everything a phone user will ever change, plus one door to the JSON for everything else. No filter field, no category tree, no per-language matrix, no keymap.

```
┌──────────────────────────────────────────┐
│ ←  Settings                              │
├──────────────────────────────────────────┤
│  SOLANA                                  │
│   Toolchain        installed · 1.4 GB  > │
│   Cluster                      devnet  > │
│   Wallet      Seed Vault · 7NJd…4kQz   > │
│                                          │
│  AGENT                                   │
│   Coding agent                Spettro  > │
│   Install an agent                     > │
│                                          │
│  EDITOR                                  │
│   Theme                      One Dark  > │
│   Font size        ───────●────────  15  │
│   Wrap long lines                 ( on ) │
│   Format on save                  ( on ) │
│   Autosave on leaving a file      ( on ) │
│                                          │
│  ADVANCED                                │
│   Edit settings.json                   > │
│   About this device                    > │
└──────────────────────────────────────────┘
```

**Reuses:**

- app/src/main/java/to/eyed/seeker/code/core/AppSettings.kt (1092) — the JSONC parser with comment preservation, project-local .zed/settings.json, per-language overrides and lsp.<server> configuration that rust-analyzer needs. Rows and keys are deleted; the file is not.
- app/src/main/java/to/eyed/seeker/code/core/AppearanceSettings.kt, ui/theme/ThemeStore.kt, ZedThemes.kt — the theme engine. Untouched.
- app/src/main/java/to/eyed/seeker/code/ui/workspace/AboutDialog.kt (112) and core/SystemSpecs.kt (114) — 226 lines producing a copyable bug report with engine version, ABI and page size. For a product whose whole premise rests on the page size being 4 KB and the ABI being arm64, that is cheap insurance.

**New:**

- ui/shell/settings/SettingsScreen.kt — fourteen rows, replacing ui/workspace/SettingsScreen.kt (1842). Theme is a plain list of the bundled Zed themes plus Follow system: no live-preview carousel, no user themes folder, no Import theme, no icon themes, no font family picker.
- Two defaults are flipped from Zed's, deliberately: soft_wrap goes from None to EditorWidth (SoftWrapMode.EditorWidth already exists in DisplayMap.kt:485-535 with its own Fenwick tree and its own test suite, so this is a default change, not a feature), and autosave goes from Off to on-leaving-a-file. Horizontal scrolling in a 400dp viewport is unusable, and a build that compiles a stale file is a 71-second lie.
- 'Edit settings.json' opens the real JSONC file in Code, so every engine key stays reachable for the person who wants inlay_hints or lsp.rust-analyzer.initialization_options. It just has no row.

## What is removed

Deleted outright, not hidden. Line counts are from the fork as it stands.

- Split panes and the pane tree. DELETE ui/workspace/PaneGroup.kt (713), PaneGroupView.kt (284), TabDrag.kt (114), and the ~225 `panes.` call sites in WorkspaceScreen.kt. Also test/PaneGroupTest.kt (431). A 400dp column cannot hold two editors; MinEditorWidth is already 360.dp. Every Split/ActivatePane/SwapPane/JoinIntoNext/JoinAll/ToggleZoom command goes with it. `panes.active.files` becomes a single OpenFilesState owned by the shell.
- The dock system. DELETE ui/workspace/Docks.kt (284: WorkspacePanel, DockLayout, planDocks), the Dock / DockHandle / DockPanel composables at WorkspaceScreen.kt:5782-5961, DockDivider at :383-398, dockTookWorkArea, drawnDocks, the ToggleLeftDock / ToggleRightDock commands, the six `*_panel.dock` and `default_width` settings keys, and test/DockPlanTest.kt (178). Every panel becomes a destination or a sheet. There is no side of a 400dp screen to dock to.
- The tab strip. DELETE ui/workspace/EditorTabs.kt (1141) and TabSwitcher.kt (251) — preview tabs, pinning, drag-to-reorder, max_tabs, the split menu, the nav buttons, the MRU overlay, CloseOthers/ToTheRight/ToTheLeft/CleanTabs/AllTabs/TogglePin/ReopenClosedTab/OpenTabSwitcher. Replaced by the bottom file bar and the Files sheet. PRECONDITION: UnsavedChangesDialog is hosted inside EditorTabs.kt (:285, :457) and every route into closing a file goes through it — it must be re-homed to ui/common/ in chunk P0 or unsaved edits are lost silently.
- The status bar. DELETE ui/workspace/StatusBar.kt (998) and ui/workspace/TitleBar.kt with its menu groups — ~80dp of permanent chrome carrying caret position, language name, line ending, encoding, toolchain, vim mode, pending chord keys, an LSP server menu, six panel buttons and a terminal toggle. Diagnostics counts move to the Build header and the Code header; the branch moves to Changes; activity moves to the destination that owns it. ActivityIndicator.kt (152) is KEPT — build progress lands there.
- The command palette and the command table. DELETE ui/workspace/CommandPalette.kt (309), Commands.kt (403), and the 164-case WorkspaceCommand enum in Keybindings.kt (1687) together with runCommand (WorkspaceScreen.kt:2484-3095, ~610 lines). PRECONDITION: every case must be walked and marked 'has a touch surface' or 'deliberately gone' BEFORE deletion, and the ones this spec knows fall through the cracks are named here as deliberately gone: SelectLanguage, SelectEncoding, SelectLineEnding, SelectToolchain, RestartLanguageServer, StopLanguageServer, OpenLanguageServerLogs (moves to a long-press on the Settings → Toolchain row), ClearAllNotifications, all task commands, all pane commands, all dock commands, all image-zoom commands, all stash commands, GitBlame, OpenGitGraph, ToggleMinimap, ToggleVimMode.
- The keymap subsystem. DELETE ui/workspace/Keymap.kt (578), DefaultKeymap and the ~650 lines of bindings in Keybindings.kt, WhichKey.kt (135), ChordDispatcher, PreImeKey, command_aliases, base_keymap, keymap.json, zed::OpenKeymap / OpenDefaultKeymap, core/crates/engine/src/keymap.rs (818), core/vendor/settings/src/keymap_file.rs (2802), and test/KeymapTest.kt (295). HARD EDGE: CoreBridge.loadKeymap(DefaultKeymap.text()) is called at WorkspaceScreen.kt:639 and the engine layers user keymaps on top of that document — the JNI call and the engine crate must be removed together or engine init breaks. Keymap.kt also owns onPreInterceptKeyBeforeSoftKeyboard; verify nothing in the editor's IME path depends on it before ripping.
- Vim mode. DELETE the whole ui/editor/vim/ package (11 files, 3145 lines) plus test/VimStateTest.kt (732), the VimSettings block in AppSettings.kt (485-520, 562-565, 775-782), the SettingsScreen rows (668-700), the status-bar mode item, and the onVimSave / onVimOpenPath / onCloseTab / onSaveAndClose callbacks threaded into EditorPane purely to answer `:q`, `:wq` and `ctrl-o`. CARE: EditorInput.kt routes soft-keyboard composition through `state.vim?.wantsRawInput` at :75-90 and :207-225, and EditorDisplay.kt:73-90 draws the caret through VimCursorShape even when vim is off. Both seams must be unpicked deliberately or ordinary typing and the caret break.
- The preview subsystem. DELETE ui/preview/ (12 files, 5766 lines: MarkdownDocument 1414, MarkdownPreview 1319, SvgDocument 634, MermaidDiagram 361, MermaidView 333, MermaidLayout 312, TablePreview 295, plus MarkdownImage, CodeFenceHighlighter, TableDocument, PreviewKind), the 11 test files under test/ui/preview/, PreviewPanel at WorkspaceScreen.kt:5962-5998, the preview eye in EditorToolbar.kt:42, and the markdown_preview settings. The single largest cut. PRECONDITION: MarkdownText (MarkdownPreview.kt:869) is called by AgentPanel.kt at five sites and must be extracted to ui/common/MarkdownText.kt first. README.md opens as wrapped text, which is all anyone needs from it here.
- Audio and video playback. DELETE the player half of ui/media/MediaPane.kt (621), ImageZoom.kt (139), the five image-zoom commands, and the androidx.media3-exoplayer dependency (app/build.gradle.kts:186 and libs.versions.toml:25,48). KEEP MediaKind.kt (63) and MediaInfo.kt (97): MediaKind at OpenFiles.kt:206 is the routing that keeps a 1.4 MB target/deploy/*.so out of the text rope. A non-text file gets a one-line 'binary · 214 KB' placeholder with Share, and a PNG gets a plain fit-to-view image.
- The outline panel and breadcrumbs. DELETE ui/workspace/OutlinePanel.kt (490), EditorToolbar.kt (339), BreadcrumbToolbar (WorkspaceScreen.kt:5679-5727), parseOutlinePath (:352-379), BREADCRUMB_SETTLE_MS and the toolbar.breadcrumbs / quick_actions settings. A 400dp breadcrumb shows one crumb, and it re-queries the engine on every caret settle. KEEP OutlinePicker.kt (301) as a 'Go to symbol' sheet from Code's ⋮.
- Multibuffers. DELETE ui/editor/MultiBufferPane.kt (151), core/MultiBufferSession.kt (259), core/crates/engine/src/multibuffer.rs (1229), openSearchMultibuffer / openReferencesMultibuffer (WorkspaceScreen.kt:879-1065) and test/MultiBufferInfoTest.kt. Excerpts of six files stacked in one editable document is a wide-screen idea. PRECONDITION: project search, find-references and project diagnostics all deploy into it today; the flat tappable lists must exist first. Cross-file replace-all goes with it — LSP rename covers the symbol case, which is the case that occurs, and plain-text mass replace is a `sed` in Shell.
- Tasks and runnables as a user surface. DELETE ui/tasks/TaskPicker.kt (309), core/Tasks.kt's user-facing half, tasks.json (user and project), the $ZED_* variables, reveal/hide/use_new_terminal/allow_concurrent_runs as user options, the built-in cargo/pytest/go/npm task table, the ▶ gutter runnables from runnables.scm (EditorPane.kt:519-530, 905-920, 2070 — this also removes a per-buffer engine query fired on every open), task::Spawn / Rerun / SpawnNearestTask / OpenTasks / OpenProjectTasks, and docs/TASKS.md. KEEP ui/tasks/TaskRunner.kt, TaskSpec, SpawnInTerminal and TerminalPanelState.runTask as the plumbing Build/Test/Deploy run through.
- Git's heavy views. DELETE ui/git/GitPanel.kt (3565), GitGraphPane.kt (1296), CommitGraph.kt (100), CommitFileTree.kt (103), GitAvatars.kt (168 — also the only outbound network call in the editor), StashPicker.kt (420), GitAnnotations.kt (150), BlameText.kt (52), GitHosting.kt (78), BranchPicker.kt (889), RemoteButton.kt (437), GitChords.kt (155), core/crates/engine/src/git_history.rs (345) and git_stash.rs (322), plus test/CommitGraphTest.kt. Gone as capabilities: the lane-rendered commit DAG with avatars and paging, stash all/tracked/staged + pop + apply, blame gutter and inline blame and View-on-GitHub, force-push and rebase-pull as first-class buttons, branch-diff tabs. CARE: state.showBlameGutter and state.blameColumnPx are baked into EditorPane's tap hit-testing at :897 and :912 — the offsets must be removed, not just the column.
- The six modal selectors. DELETE ui/workspace/EncodingSelector.kt (295), LineEndingSelector.kt (169), LanguageSelector.kt (202), ToolchainSelector.kt (237), IconThemeSelector.kt (261), ProjectSymbolsPicker.kt (252), core/Toolchains.kt (61) and core/crates/engine/src/toolchain.rs (554). CARE: the toolchain selection exports VIRTUAL_ENV and leads PATH for language servers and tasks — that PATH-leading behaviour must move into ShellEnvironment.kt / the Solana toolchain installer before toolchain.rs is deleted, or rust-analyzer loses its rustup. Encoding and line-ending round-tripping stay in the engine; only the pickers go.
- Theme, icon-theme and font extensibility. DELETE ui/theme/UserThemes.kt (182), the watched `themes` folder, Import theme…, theme_overrides, ThemeSelector.kt's 540-line live-preview grid (reduced to a plain list), ui/theme/IconThemes.kt (248), IconTheme.kt (87), ui/theme/FontCatalog.kt (217), FontNames.kt (123), the `fonts` and `icon_themes` folders, device-font enumeration, buffer_font_fallbacks / _features / _weight and ui_font_family. KEEP ZedTheme.kt, ZedThemes.kt, ThemeStore.kt, Fonts.kt, ZedFileIcons.kt, FileIcons.kt and the bundled theme JSON — every colour in the app is read through LocalZedTheme.color(...), and Rem.kt is the layout system, not a zoom preference.
- The settings mirror. DELETE ui/workspace/SettingsScreen.kt (1842) and its rows for vim, base keymap, dock sides, chrome visibility (:946-1007), minimap, inlay hints, project-panel sort/fold/spacing, preview tabs and icon theme. Replaced by fourteen rows plus 'Edit settings.json'. core/AppSettings.kt is KEPT whole.
- Inlay hints. DELETE ui/editor/InlayHints.kt (281), its four settings rows and test/InlayHintsTest.kt. Type and parameter hints inserted mid-line destroy a 40-column line, and they are off by default anyway.
- The minimap and scrollbar marks. DELETE ui/editor/Minimap.kt (141), its draw and drag blocks in EditorPane.kt (:470-490, 746-772, 2211-2262), the minimap settings block, and the marks on the scrollbar track (:2026-2035, 2213-2270) with the ScrollbarSettings block. KEEP the scrollbar thumb itself — it is a real handle and the only fast way through a long lib.rs by touch.
- Multi-root worktrees. DELETE ui/workspace/AddFolderDialog.kt (143), the RemoveFolderDialog, workspace::AddFolderToProject / RemoveFolderFromProject, the per-root headers in ProjectPanel.kt and ProjectTreeState.kt, the multi-root paths through the finder and search, and the 500 ms FOLDERS_POLL. One program is one project is one folder.
- Editor power-editing driven by chords. Prune ui/editor/EditorCommands.kt (1880) command-by-command — DELETE multiple cursors as a first-class surface, sort/reverse/shuffle/unique lines, transpose, the eight ConvertTo* case commands, Rewrap, and syntax-tree selection grow/shrink. KEEP undo/redo, cut/copy/paste, select line, duplicate line, toggle comment, indent/outdent, move line up/down, join lines. Snippets: KEEP LSP snippet insertion, DELETE the Tab/Shift+Tab tabstop driving and the user snippets/<language>.json folder.
- Onboarding. DELETE ui/workspace/Onboarding.kt (305), zed::OpenOnboarding and its preference flag. It teaches the sample project, the command palette and a theme choice; two of those no longer exist. Setup is the onboarding.
- Terminal extras. DELETE ui/terminal/TerminalSearchBar.kt (286) and TerminalSearch.kt (123), the terminal tab list in TerminalPanelState.kt, ui/terminal/TerminalActions.kt (82), NewTerminal / CloseTerminal / NextTerminal / PreviousTerminal, and the resizable terminal dock with its drag handle. One interactive session per project plus one transient build session.
- Share-arbitrary-text-in. DELETE the path in core/IncomingIntent.kt (243) that turns shared text into 'Shared text.txt' plus its manifest intent filters. KEEP SAF folder import/export, share-a-file-out, and git clone.
- The `play` product flavour. DELETE app/build.gradle.kts:51-68's play flavour, app/src/play/, and the canClone / canInstallLanguageServer / canUseAgent capability fields with the isOffered greying machinery in Commands.kt. A build with no Debian cannot install platform-tools, cannot run cargo-build-sbf, cannot run rust-analyzer and cannot run any ACP agent — it cannot do the one thing this product exists to do. NOTE FOR THE OWNER: this forecloses Google Play distribution and is a distribution decision, not an engineering one.
- Keyboard-first documentation. DELETE docs/SHORTCUTS.md (2396), docs/TASKS.md (152) and docs/ZED_GAP_REPORT.md (406). Zed parity is the wrong objective function for this device and leaving the scoreboard invites re-adding everything above.
- WorkspaceScreen.kt itself (6042 lines). It is the adaptive shell. With the pane tree, docks, tabs, status bar, palette, chord dispatcher, forty overlays and multi-root handling removed there is no useful residue to keep as a file — but there IS behaviour inside it that must be carried across by hand, not re-derived: openFileInto/openFile (:700, 742), save / formatBeforeSave / cleanBeforeSave / saveNow, the autosave debounce (:1853-1865), external-edit detection and resync (:1676-1780), resyncAfterWorkspaceEdit, reportLocalSettings, SAF import/export (:1507-1558), reload / reopenWithEncoding, the session-restore write gate, and the pre-IME key pass that stops Gboard eating editing keys. Budget for re-finding those bugs; the tests that cover them are the ones NOT to delete.
- Explicitly said out loud rather than discovered: this fork abandons DeX, foldables, tablets, external-monitor use and every Bluetooth-keyboard power user. Nine hard-coded bindings remain as a courtesy. That should be a line in the README, not a surprise.

## What is kept but not surfaced

- ui/workspace/OutlinePicker.kt (301) — kept as a 'Go to symbol' sheet behind Code's ⋮, not a panel and not a chord. Scrolling a 900-line lib.rs by finger is exactly the problem it solves.
- ui/workspace/GoToLine.kt (325) — kept behind Code's ⋮. A compiler error says lib.rs:212:9 and typing a number beats scrolling, but nobody needs it on a permanent surface now that error rows are tappable.
- ui/workspace/RenameSymbol.kt (283) — kept, reached only from the expanded half of the editor action row. LSP rename is the one thing that replaces cross-file replace-all, so it must not be lost.
- ui/workspace/LspLogsPane.kt (148) and LanguageServerPrompt.kt (493) — kept, reached only by a long-press on Settings → Toolchain. For bug reports, not for daily use.
- core/MultiBufferSession.kt's engine side (core/crates/engine/src/multibuffer.rs) — may stay compiled until last; nothing in the shell opens one. Delete in the final pass once the three result lists are proven.
- ui/git/HunkControls.kt (256) and DiffStaging.kt (72) — kept but surfaced only on long-press of a hunk in the Diff route, and only for git rows. Per-hunk staging is a code-review workflow; the gutter diff bars that cost nothing stay visible.
- ui/editor/Conflicts.kt, ConflictView.kt, ui/git/MergeResolvedBar.kt — kept, surfaced only when a file actually has conflict markers. No menu, no command, no discoverability surface.
- core/BufferEncoding, core/crates/engine/src/encoding.rs, line-ending detection — kept whole so a Windows-1252 or CRLF file still opens and round-trips correctly. The pickers are gone; the engine behaviour is not.
- core/Tasks.kt's TaskSpec, TaskReveal/TaskHide/TaskSave, and ui/tasks/TaskRunner.kt — kept entirely as internal plumbing for Build/Test/Deploy. No picker, no history UI, no tasks.json, no user-visible concept of a 'task'.
- ui/media/MediaKind.kt and MediaInfo.kt — kept purely as routing so binaries never reach the text rope. No viewer chrome beyond a placeholder row and a fit-to-view image.
- core/AppSettings.kt's full key surface — every engine key survives and is reachable through Settings → Edit settings.json. Only fourteen get a row.
- ui/workspace/AboutDialog.kt and core/SystemSpecs.kt — kept, one row deep in Settings → About this device.
- OpenFiles.kt's NavHistory — kept and wired to back-gesture step 6. The back/forward buttons in the tab strip are gone; the two stacks are not.
- ui/theme/Rem.kt, LocalUiFontSize, glyphHeight() — kept and never surfaced. It looks like an interface-zoom preference and it is actually the layout system: every chrome dimension is a rem multiple with an accessibility floor, asserted by test/ChromeMetricsTest.kt. The Increase/Decrease/ResetUiFontSize commands go; the machinery stays.
- ui/workspace/ContextMenu.kt (281) — a generic primitive used by the file tree, the sheets and the selection menu. Deleting any one caller must not take it.

## Implementation plan

### P0 — Decouple: extract what the demolition would break

*Owns:* `app/src/main/java/to/eyed/seeker/code/ui/common/MarkdownText.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/common/UnsavedChangesDialog.kt (moved from ui/workspace/)`, `app/src/main/java/to/eyed/seeker/code/ui/common/BinaryPlaceholder.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/agent/AgentPanel.kt (imports only)`, `app/src/test/java/to/eyed/seeker/code/ui/common/MarkdownTextTest.kt (new)`

*Depends on:* nothing

Nothing else may start deleting until this lands. Three verified couplings: (1) MarkdownText is internal to ui/preview/MarkdownPreview.kt:869 and AgentPanel.kt calls it at five sites (1605, 1842, 1950, 1955, 2084) — extract the INLINE renderer only (spans, code, links, lists) into ui/common/, carrying the pieces of MarkdownDocument.kt it needs, and leave the block/preview/mermaid/svg machinery behind to be deleted. Port the subset of test/ui/preview/MarkdownDocumentTest.kt that covers inline rendering. (2) UnsavedChangesDialog(files) is hosted inside EditorTabs.kt at :285 and :457 and every route into closing a file goes through it; move the composable to ui/common/ so the shell can host it. (3) MediaKind at OpenFiles.kt:206 is what keeps a 1.4 MB .so out of the text rope; write BinaryPlaceholder.kt as the one-line 'binary · 214 KB · Share' surface plus a fit-to-view image, so ui/media/MediaPane.kt can be deleted without losing the routing. Exit criterion: the app still builds and runs with ui/preview and ui/media still present but unreferenced by AgentPanel and OpenFiles.

### P1 — Shell skeleton: nav, back, state, sheets

*Owns:* `app/src/main/java/to/eyed/seeker/code/ui/shell/SeekerShell.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/ShellState.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/ShellBackHandler.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/ShellNavBar.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/SheetScaffold.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/RouteStack.kt (new)`, `app/src/main/java/to/eyed/seeker/code/MainActivity.kt`, `app/src/test/java/to/eyed/seeker/code/ui/shell/ShellBackHandlerTest.kt (new)`, `app/src/test/java/to/eyed/seeker/code/ui/shell/RouteStackTest.kt (new)`

*Depends on:* P0

Three destinations, three back stacks, one ordered back handler, one bottom bar with badge slots, one sheet scaffold with the bottom-pinned-field rule, one retained state holder outside composition (modelled on UserlandInstaller.kt). Ship it with three placeholder destinations so P2/P3/P4 can land in parallel against a stable host. There is no navigation dependency in app/build.gradle.kts today — hand-roll RouteStack rather than adding androidx.navigation; three stacks of a sealed Route type is ~120 lines and avoids a library that assumes a NavHost. Carry across from WorkspaceScreen.kt, by hand and with tests: the pre-IME key pass, the focus-restoration-after-overlay-dismiss pattern, the notification host placement, the askpass host, and the incoming-intent handling. Do NOT re-derive them. Exit criterion: the app launches into an empty three-destination shell, back behaves per the eight-step order, and ShellBackHandlerTest asserts all eight.

### P2 — Code destination, file bar, Files sheet, action row

*Owns:* `app/src/main/java/to/eyed/seeker/code/ui/shell/code/CodeScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/code/FileBar.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/code/FilesSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/code/CodeOverflowSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/editor/EditorPane.kt`, `app/src/main/java/to/eyed/seeker/code/ui/editor/EditorInput.kt`, `app/src/main/java/to/eyed/seeker/code/ui/editor/EditorDisplay.kt`, `app/src/main/java/to/eyed/seeker/code/ui/editor/EditorCommands.kt`, `app/src/main/java/to/eyed/seeker/code/ui/workspace/OpenFiles.kt`, `app/src/main/java/to/eyed/seeker/code/ui/search/BufferSearchBar.kt`, `app/src/main/java/to/eyed/seeker/code/ui/workspace/ProjectPanel.kt`, `app/src/main/java/to/eyed/seeker/code/ui/workspace/ProjectTreeState.kt`

*Depends on:* P1

This chunk is the SOLE owner of ui/editor/ — no other chunk may edit EditorPane.kt. Four jobs. (1) Rewrite EditorActionRow (EditorPane.kt:2775): 44dp tall, .touchTarget() on every key, eight fixed non-scrolling keys (esc ⇥ ← → undo redo save ▶Build) plus a ⌄ that expands to a two-row grid with the Rust punctuation set and the LSP set. The ▶ key calls into a lambda the shell supplies, so P4 can wire BuildRunner without touching this file. (2) Excise vim from the three seams — EditorInput.kt:75-90 and :207-225 (wantsRawInput in front of the IME path), EditorDisplay.kt:73-90 (VimCursorShape), and EditorPane.kt:119-124/617-641/1873-1892 — carefully, with the existing editor tests green; this is where ordinary typing breaks if it is done fast. (3) Remove the minimap draw/drag and the scrollbar marks while KEEPING the thumb drag they share an awaitEachGesture with (:742-782), and remove the blame column offsets from the tap hit-testing at :897 and :912. (4) Build CodeScreen, FileBar, FilesSheet. Prune ProjectPanel to one root, 44dp rows, no multi-select, no drag, no sticky ancestors. Do NOT touch long-press: EditorPane.kt:1026-1046 already gives selection + hover card + go-to-definition rows, and that is the design's answer to LSP navigation.

### P3 — Agent destination

*Owns:* `app/src/main/java/to/eyed/seeker/code/ui/shell/agent/AgentScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/agent/AgentPickerSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/agent/AgentConfigSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/agent/MentionSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/agent/AgentPanel.kt`, `app/src/main/java/to/eyed/seeker/code/ui/agent/ContextPicker.kt`, `app/src/main/java/to/eyed/seeker/code/ui/agent/AgentReviewPane.kt`, `app/src/main/java/to/eyed/seeker/code/solana/agents/AgentCatalog.kt`

*Depends on:* P1

Sole owner of ui/agent/. Dissolve AgentBar (AgentPanel.kt:515) into AgentScreen's top row and collapse ComposerChrome's scrolling selector strip (AgentPanel.kt:1050) into one chip that opens AgentConfigSheet carrying Spettro's Mode/Model/Permission/Thinking/Ultra selectors and the live context gauge per docs/SOLANA.md — the strip is not deleted, it is moved off the 890dp column. Re-present ContextPicker as a searchable sheet rather than an inline popup. Wire AgentCatalog's three installs (this is also what keeps R8 from stripping the solana package). Ship the degraded state first: no agent → one card → Setup, composer disabled. Ship the sticky 'N files changed · Review →' bar with a stub route target so P7 can land behind it. Do NOT touch ui/shell/ beyond your own subdirectory.

### P4 — Build runner, diagnostics parser, Build destination, Shell mode

*Owns:* `app/src/main/java/to/eyed/seeker/code/solana/build/BuildTasks.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/build/BuildRunner.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/build/CargoDiagnostics.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/build/BuildDiagnostics.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/build/BuildLog.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/build/BuildScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/build/BuildLogView.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/build/ShellMode.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/tasks/TaskRunner.kt`, `app/src/main/java/to/eyed/seeker/code/terminal/TerminalPanelState.kt`, `app/src/test/java/to/eyed/seeker/code/solana/build/CargoDiagnosticsTest.kt (new)`, `app/src/test/java/to/eyed/seeker/code/solana/build/BuildDiagnosticsTest.kt (new)`

*Depends on:* P1

The centrepiece, and the chunk with the most genuinely new code. Ship CargoDiagnostics.kt FIRST, with its test suite, against captured real output from the device: a rustc E0609, an anchor IDL error, a proc-macro panic, a `Failed to execute rustup`, an lld failure, and a mocha failure. The rest of the design depends on this parser and the fallback path (unparsed lines still render, still copy, still route to the agent) must be tested, not assumed. BuildRunner is the single entry point and its ordering is non-negotiable: save-all-and-wait → anchor keys sync if mismatched → spawn through TaskRunner.runTask → stream → parse → publish. BuildDiagnostics is the merge layer that exists because CoreBridge's diagnostics are read-only across JNI; every consumer reads the merge, never the engine directly. Prune TerminalPanelState's tab list to two sessions. Expose a `BuildState` in ShellState so the ▶ nav badge and the editor action row's ▶ key both read one source. Do NOT touch ui/editor/ (P2 owns it) or solana/chain/ (P6 owns it) — call into a `Deployer` interface that P6 implements.

### P5 — Toolchain manifest, installer, Setup screen

*Owns:* `app/src/main/java/to/eyed/seeker/code/solana/toolchain/manifest.json (new)`, `app/src/main/java/to/eyed/seeker/code/solana/toolchain/ToolchainManifest.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/toolchain/ToolchainInstaller.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/toolchain/ToolchainState.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/setup/SetupScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/terminal/ShellEnvironment.kt`, `app/src/test/java/to/eyed/seeker/code/solana/toolchain/ToolchainManifestTest.kt (new)`

*Depends on:* P1

Parallel with P4; they meet only at the 'Build with no toolchain pushes Setup' edge, which is one boolean in ShellState. Model ToolchainInstaller on UserlandInstaller.kt: a scope outside composition, (step, fraction) progress, main-thread completion callback, cancel that cleans up. Two proot rules from docs/SOLANA.md are correctness requirements, not tips: `--link2symlink` is used for apt and for nothing else (under it `cargo install` hard-links its finished binary into a scratch directory that is then deleted, reporting success and leaving a dangling symlink), and large archives are unpacked with the host's tar directly into the rootfs, with the one hard-linked entry in Debian's rootfs handled specially. The progress model has two row kinds — sized download and on-device compile — and the compile rows must never show a MB bar. ShellEnvironment.kt is edited here to take over the PATH/VIRTUAL_ENV export that core/Toolchains.kt currently does, BEFORE P10 deletes toolchain.rs, or rust-analyzer loses its rustup.

### P6 — Cluster, wallet, deploy

*Owns:* `app/src/main/java/to/eyed/seeker/code/solana/chain/Cluster.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/ClusterStore.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/AnchorToml.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/Rpc.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/Wallet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/KeypairSigner.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/SeedVaultSigner.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/ProgramDeploy.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/ProgramIds.kt (new)`, `app/src/main/java/to/eyed/seeker/code/solana/chain/DeployedPrograms.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/build/DeploySheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/build/WalletSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/build/ClusterSheet.kt (new)`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/src/test/java/to/eyed/seeker/code/solana/chain/AnchorTomlTest.kt (new)`, `app/src/test/java/to/eyed/seeker/code/solana/chain/ProgramIdsTest.kt (new)`

*Depends on:* P4

Entirely greenfield: there is no solana/wallet or solana/chain package and no MWA or Seed Vault dependency in the build files today. Sequence within the chunk matters. (1) Cluster + AnchorToml + ProgramIds first — these are pure logic, host-testable, and they fix the two failure modes that break the loop before any wallet exists: a cluster chip that lies because Anchor.toml says Localnet, and DeclaredProgramIdMismatch from the scaffold's PLACEHOLDER_ID (SolanaTemplates.kt:48). (2) Rpc + KeypairSigner + the CLI deploy path — this is the version that works on day one and the one the Deploy sheet defaults to on devnet. (3) SeedVaultSigner and the Kotlin-side chunked ProgramDeploy — this is the differentiator and the hard part: `solana program deploy` inside proot cannot call an Android MWA activity, and a deploy is hundreds of buffer-write transactions, so this is a reimplementation of the deploy flow in Kotlin, not a shell-out. It ships after (2) and the Deploy sheet names which signer is in use rather than pretending. (4) Buffer recovery (resume/close) — the failure mode most specific to a phone. Do NOT touch solana/build/ (P4 owns it); implement P4's `Deployer` interface.

### P7 — Changes, Diff, Problems

*Owns:* `app/src/main/java/to/eyed/seeker/code/ui/shell/changes/ChangesScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/changes/DiffScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/changes/ProblemsScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/changes/CommitSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/changes/BranchSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/git/DiffPane.kt`, `app/src/main/java/to/eyed/seeker/code/ui/git/GitOps.kt`, `app/src/main/java/to/eyed/seeker/code/ui/git/GitDrafts.kt`, `app/src/main/java/to/eyed/seeker/code/ui/diagnostics/DiagnosticsPane.kt`, `app/src/main/java/to/eyed/seeker/code/ui/search/ProjectSearchPanel.kt`

*Depends on:* P1

Three routes, all cheap because the panes exist. DiffPane already renders unified diffs with collapsed-context expansion; two changes are needed — DiffPane.kt:543 hard-sets softWrap = false and must wrap, and because a wrapped continuation of a '−' line is indistinguishable from a '+' line, every visual row of a changed line needs a tinted background rather than a leading sign on the first row only. DiagnosticsPane and ProjectSearchPanel both already take a nullable multibuffer callback, so dropping the multibuffer is passing null at the call site — do that here so P10 can delete MultiBufferSession safely. ChangesScreen composes AgentSessions.keepEdits/rejectEdits (file granularity, per core/AgentSessions.kt:530/538 — do not promise per-hunk for agent edits) above GitSession's status list (where per-hunk IS available via stageHunk/restoreHunk). Actions go at the BOTTOM of both Changes and Diff. Do NOT re-host GitPanel.kt — it is deleted in P10 and its 3565 lines are the reason.

### P8 — Projects, New program, Settings

*Owns:* `app/src/main/java/to/eyed/seeker/code/ui/shell/projects/ProjectsSheet.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/projects/NewProgramScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/projects/CloneScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/settings/SettingsScreen.kt (new)`, `app/src/main/java/to/eyed/seeker/code/ui/shell/settings/ThemeList.kt (new)`, `app/src/main/java/to/eyed/seeker/code/core/AppSettings.kt`, `app/src/main/java/to/eyed/seeker/code/core/ProjectsRoot.kt`, `app/src/main/java/to/eyed/seeker/code/solana/templates/SolanaTemplates.kt`, `app/src/main/java/to/eyed/seeker/code/solana/templates/SolanaNames.kt`

*Depends on:* P1

Sole owner of core/AppSettings.kt. Flip two defaults with tests: soft_wrap None → EditorWidth (the mode and its Fenwick-tree implementation already exist in DisplayMap.kt:485-535 with SoftWrapEditorTest.kt covering arrow motion, paging, viewport and edit fidelity, so this is a default change), and autosave Off → on-leaving-a-file. Delete the settings KEYS for vim, base_keymap, docks, chrome visibility, minimap, inlay hints, project-panel sort/fold/spacing, preview tabs and icon theme — but not the file, which is the JSONC parser carrying project-local .zed/settings.json and the lsp.<server> config rust-analyzer needs. Fourteen rows plus 'Edit settings.json' which opens the real file in Code. Remove writeSampleProject from ProjectsRoot. NewProgramScreen must reference SolanaTemplates and SolanaNames — R8 is on for release and those files are currently referenced from nowhere.

### P9 — Session persistence, shrunk

*Owns:* `app/src/main/java/to/eyed/seeker/code/core/WorkspaceSession.kt`, `app/src/main/java/to/eyed/seeker/code/ui/shell/SessionRestore.kt (new, replacing ui/workspace/SessionRestore.kt)`, `core/crates/engine/src/session.rs`, `app/src/test/java/to/eyed/seeker/code/core/WorkspaceSessionTest.kt`

*Depends on:* P1

The one chunk that crosses the JNI boundary, so it is isolated. The session document loses the pane tree, dock sides and widths, per-tab pinned/preview state and the jump list; it keeps project, open-file MRU with caret and scroll, active destination, Build/Shell mode, agent thread and cluster. WorkspaceSession.kt:51 has `const val VERSION = 1` with a comment that it must match engine::SESSION_VERSION at core/crates/engine/src/session.rs:44 — bump both together in one commit (docs/ARCHITECTURE.md requires paired changes) and let the mismatch path discard the old document, which it already does by returning null. KEEP the engine-side validation that drops vanished files, clamps carets past EOF and discards corrupt documents: Android kills a backgrounded process holding a 1.4 GB toolchain aggressively, and losing your place every time would be the worst bug in the product. Restore-where-you-left-off is the ten-minute-bus-session feature and it is the reason this chunk is not deferred.

### P10 — Demolition

*Owns:* `Deletion of every file named in `removed``, `app/src/main/java/to/eyed/seeker/code/ui/workspace/WorkspaceScreen.kt`, `app/src/main/java/to/eyed/seeker/code/ui/preview/ (whole package)`, `app/src/main/java/to/eyed/seeker/code/ui/editor/vim/ (whole package)`, `app/src/main/java/to/eyed/seeker/code/ui/media/MediaPane.kt, ImageZoom.kt`, `app/src/main/java/to/eyed/seeker/code/ui/git/ (the deleted subset)`, `app/src/main/java/to/eyed/seeker/code/ui/workspace/ (the deleted subset)`, `app/src/main/java/to/eyed/seeker/code/core/MultiBufferSession.kt, Tasks.kt (pruned), Toolchains.kt`, `core/crates/engine/src/keymap.rs, multibuffer.rs, toolchain.rs, git_history.rs, git_stash.rs`, `core/vendor/settings/src/keymap_file.rs`, `app/build.gradle.kts (play flavour, media3), gradle/libs.versions.toml`, `app/src/play/ (whole source set)`, `app/src/test/java/... (the ~36 test files covering deleted subsystems)`, `docs/SHORTCUTS.md, docs/TASKS.md, docs/ZED_GAP_REPORT.md`, `README.md`

*Depends on:* P2, P3, P4, P5, P6, P7, P8, P9

Last, in one branch, in this order so the tree never stops compiling: (1) the leaves with no callers left — ui/preview, ui/media's player half, vim, the six selectors, the git heavy views, the tasks picker, Onboarding, AddFolderDialog, TerminalSearchBar; (2) the shell — WorkspaceScreen.kt, PaneGroup, Docks, EditorTabs, TabSwitcher, StatusBar, TitleBar, CommandPalette, Commands, Keymap, Keybindings, WhichKey; (3) the paired Rust removals — keymap.rs must go together with the CoreBridge.loadKeymap call, and toolchain.rs only after P5 has moved the PATH export into ShellEnvironment; (4) the build files — play flavour and media3; (5) the tests for deleted subsystems (~36 files, including PaneGroupTest 431, VimStateTest 732, MarkdownDocumentTest 489, DockPlanTest 178, KeymapTest 295, CommitGraphTest 179). DO NOT delete the tests that cover behaviour the new shell inherits — SessionRestoreTest, AutosaveTrackerTest, NavHistoryTest, ChromeMetricsTest, SoftWrapEditorTest — port them to the new call sites instead; they are the only regression net for the bugs the WorkspaceScreen rewrite will otherwise re-find. Finally, rewrite README.md: one edition (`full`), one device, one orientation, and an explicit goodbye to DeX, foldables, tablets and keyboard users.
