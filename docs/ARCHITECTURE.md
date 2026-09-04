# Architecture

Thragg is a two-language project with one strict boundary.

```
┌─────────────────────────────────────────────────────────┐
│  app/  — Kotlin + Jetpack Compose                       │
│  workspace shell · editor surface · terminal view       │
│  fold/tablet adaptive layouts · IME · SAF storage       │
├────────────────── JNI (CoreBridge) ─────────────────────┤
│  core/ — Rust (cargo workspace, built via cargo-ndk)    │
│  crates/jni-bridge  → libseekercore.so (cdylib)       │
│  crates/engine      → buffers, syntax, LSP, ACP, git    │
└─────────────────────────────────────────────────────────┘
```

## Why Rust below, Kotlin above

The "Zed feel" — instant startup, zero dropped frames while typing on a
50k-line file — requires predictable latency in the hot paths. Kotlin
runs on ART with a garbage collector; Rust compiles to machine code with
none. More importantly, the hardest components of an editor (the
rope/CRDT text engine, incremental tree-sitter parsing, LSP plumbing)
already exist as battle-tested Rust crates in
[Zed](https://github.com/zed-industries/zed), and this project reuses
them rather than re-deriving years of correctness work.

Kotlin/Compose does what it is genuinely best at: rendering, input,
window size classes and fold postures, the soft keyboard, and Android
storage — things no Rust crate does well on Android.

## The JNI boundary

The entire contract lives in two files that must change together:

- `core/crates/jni-bridge/src/lib.rs` (Rust exports)
- `app/src/main/java/to/eyed/thragg/core/CoreBridge.kt`
  (Kotlin `external` declarations)

Rules:

1. **Coarse-grained calls only.** A JNI crossing costs real time; the
   UI never loops over per-character calls. Batches, snapshots and
   diffs cross the boundary, not keystrokes.
2. **The engine owns truth.** Buffers, project state, agent sessions
   all live in Rust. Kotlin holds view state only.
3. **No Android types in Rust, no editor logic in Kotlin.**

## Reusing Zed

Zed's crates fall into tiers (verified against the Zed source):

- **UI-free, reusable as-is**: `sum_tree` (Apache-2.0), `rope`, `text`
  (the CRDT buffer), `clock`, `language_core`, `grammars` (tree-sitter
  queries + configs for 20+ languages), plus utility crates. This is
  the text engine. These are vendored into `core/vendor/` at a pinned
  upstream commit (see `core/vendor/VENDOR.md` for the crate list and
  local patches); `engine` buffers are `text::Buffer` instances —
  single-replica CRDTs whose collaboration machinery simply lies
  dormant.
- **Runtime-coupled**: `fs`, `worktree`, `language`, `lsp` and friends
  depend on GPUI — but on its *reactive runtime* (entities, tasks,
  executors), not its renderer. GPUI itself is Apache-2.0 and pure Rust
  at its core, with a pluggable `Platform` trait. This tier is now
  vendored too, and the engine supplies its own headless platform: a
  thread pool, a timer thread and a foreground queue, with every
  window, display and menu method left `unimplemented!()`. GPUI runs on
  a thread of the engine's own and **never draws a pixel** — if a
  vendored crate ever reached for a window it would panic rather than
  silently misbehave.
- **UI crates** (`editor`, `workspace`, `multi_buffer`, GPUI rendering):
  not reused. Their responsibilities are reimplemented natively in
  Compose — with one exception that reaches back into Rust. Zed's
  multibuffer is not a view: excerpts of several files compose into one
  editable document, and an edit in it has to land in the right file's
  CRDT. So `engine/src/multibuffer.rs` owns the composition, and it
  composes into an ordinary `text::Buffer` — the *mirror* — which the
  Compose editor then renders with no idea it is one. `Engine::edit` on
  a mirror maps the offset back to (buffer, offset) and edits the file
  through the normal path, so undo, `didChange` and dirty state stay per
  file. The excerpt spans are `text::Anchor`s, so they ride edits made
  anywhere, including in the file's own tab.

Zed's own `remote_server` crate — a headless Zed engine driven over an
RPC protocol — is the in-tree proof that the engine runs without a UI.

Because the worktree scans asynchronously on that runtime, its state
reaches the UI by being *mirrored* into an ordinary snapshot the JNI
layer reads directly. Kotlin polls a version counter to know when to
re-read; no JNI call ever waits on the Rust runtime, and the Android
main thread is never blocked by it.

## Where projects live

Projects are directories in the app's private storage, and content
brought in from elsewhere on the device is **copied in** rather than
opened where it sits.

That is the engine's constraint rather than a preference. The worktree is
Zed's: it walks a real filesystem path and watches it with inotify. A
Storage Access Framework tree is a stream of content URIs with no path
behind it, so a project left on shared storage could not be scanned,
watched or opened by the engine at all. Import and export therefore copy,
which costs real time on a large tree and is the honest trade for the
engine working.

`MANAGE_EXTERNAL_STORAGE` would give real paths anywhere and so genuine
open-in-place, but Google Play restricts that permission to a short list
of app categories an IDE is not in, and shared storage is FUSE-backed and
slower to scan. It stays off the table for now.

### What a project remembers

Zed persists a workspace in sqlite: `workspace/src/persistence.rs` keys a
workspace by its root paths and stores the pane group as a tree of axes,
flexes and panes, the items of each pane with their kind and active flag,
and per-item editor state (scroll anchor and selections); the docks store
which panel is open on each side and how wide it is; `recent_projects`
reads the same database.

Nothing under `core/vendor` carries `sqlez` or `rusqlite`, and a database
for one document per project would be a dependency bought for no new
capability, so the same shape is written as one JSON document per project
under `<filesDir>/sessions/`, named by a hash of the root path (a project
name is user input; a file name should not be). `core/crates/engine/src/session.rs`
owns the format and, more importantly, the *rules*: the app hands it the
document built from its view state and gets back one that is true of the
disk as it is now — files that have gone dropped, carets clamped, emptied
panes collapsed, flexes renormalised, and a corrupt or foreign document
discarded with a log line. That keeps every one of those rules testable on
the host, where the UI is not.

What is deliberately *not* persisted is anything live. A terminal is a
process tree that Android ends with the app, so only each tab's title and
working directory are kept and the restore opens fresh shells there; an
agent thread is a running program; and the tabs that are views rather than
files — a diff, the commit graph, the problems tab, a language-server log,
a multibuffer — would come back stale, which is worse than not coming back.
`OpenFile.isReopenable` is the single answer to that question: it is what
`Ctrl+Shift+T` asks and what the session capture asks, so a view added
later is excluded from both by construction rather than by remembering to.

What *is* persisted beyond Zed's list is the tab's `preview` flag: the pane
keeps one provisional slot (`preview_tabs`), and a workspace restored
without it would turn a browsed project into thirty permanent tabs.

### Opening files from other apps

The same constraint decides what "open with Thragg" means. The app
is a target for `ACTION_VIEW`/`ACTION_EDIT` on text-like types and for
`ACTION_SEND`/`ACTION_SEND_MULTIPLE` (manifest), but a content URI cannot
be opened in place, so every one of them is an **import**: the bytes are
staged into cache (`core/IncomingIntent.kt`, with the intent → request
mapping as a pure, tested function), then placed in a project — the open
one, after an "Add *name* to *project*?" dialog with a destination path
(a clash gets Zed's ` copy` suffix rather than overwriting), or a
**Scratch** project in app-private storage, created on demand. Text
shared without a file becomes `Shared text.txt`. `MainActivity` is
`singleTask`, so a share reaches the running workspace through
`onNewIntent` rather than stacking a second one.

### Several folders in one project

A project is Zed's list of worktrees, not a single directory: `project.rs`
keeps an ordered `Vec<WorktreeState>` with the folder it was opened with
first, `workspace::AddFolderToProject` appends and
`workspace::RemoveWorktreeFromProject` drops one. The file finder, project
search and the project panel all span every folder, and `.zed/settings.json`
is read per folder with the project's own winning — Zed's precedence.

Because the engine needs a real path, "add a folder" carries the same
constraint as opening one: a folder already in app-private storage (another
project) is added where it is, and anything else is **copied in** first, with
the dialog saying so.

Outside the engine a file is named by its *project path*: worktree-relative
in the project's own folder, and `<folder name>/<relative>` in any other —
the prefix Zed's file finder shows once there is more than one worktree. That
keeps one string addressing a file through tabs, search results and the
editor. The engine resolves it primary-first, so a folder added later can
never take a path away from the project's own folder; two added folders with
the same name are the one ambiguity left, and the first wins.

Two things stay single-root for now. Git status is read for the repository
the project's own folder is in, so rows in another folder show no status
mark, and the panel's sticky headers stand down while more than one folder is
open (the anchor arithmetic assumes a tree whose root sits above the list).

The other direction goes through a `FileProvider` exporting only the
projects directory (`res/xml/file_paths.xml`): **Open with…** is Zed's
`workspace::OpenWithSystem` as an `ACTION_VIEW` chooser, and **Share…**
(`project_panel::Share`, Android-only) is the share sheet — both in the
project panel's context menu, the tab menu and the media pane
(`core/ShareOut.kt`).

## On-device execution (terminals, LSP servers, agents)

Android only executes programs that arrived through the package installer.
On a modern target SDK that rules out any package manager: a downloaded
binary cannot run, whatever permissions it is given. This shapes the whole
tooling story, and the project answers it by targeting an old SDK (see
[BUILDING.md](BUILDING.md)).

The app targets SDK 28, where the restriction does not
apply, and runs a real **Debian** through
[proot](https://github.com/termux/proot): the rootfs is downloaded on first
use into app-private storage, proot fakes the filesystem layout its
binaries expect, and `apt install` works against Debian's own servers. The
project directory is bound into the namespace, so the shell and the editor
see the same files. Nothing about the packages is maintained by this
project — that is the point of borrowing a distribution. See
[USERLAND.md](USERLAND.md).

Before the rootfs is installed the terminal falls back to Android's own
shell (mksh, with toybox's ~210 commands), and anything shipped rather than
downloaded goes into the APK as a `lib<name>_exec.so` in the native library
directory, which stays executable at any target SDK — that is how proot
itself gets to run. A Play-compatible edition that stopped at that fallback
used to ship beside this one; it is gone, because a modern target SDK
forecloses every toolchain the app exists to drive.

Terminal emulation builds on Termux's cleanly decoupled
`terminal-emulator` / `terminal-view` libraries (GPL-compatible),
embedded in Compose via `AndroidView`. They are vendored as Gradle
modules under `vendor/` at a pinned upstream commit — source, tests and
all — for the same reasons the Zed crates are: see `vendor/VENDOR.md`.
The emulator carries a small C shim (`libtermux.so`) that opens
`/dev/ptmx` and forks the child process; everything above it, including
the VT/xterm state machine, is plain Java.

## ACP (Agent Client Protocol)

Agents are external processes speaking newline-delimited JSON-RPC over
stdio — the same model Zed uses, via the same
[`agent-client-protocol`](https://crates.io/crates/agent-client-protocol)
crate. The engine spawns and supervises agent processes and maintains
thread state (messages, tool calls, plans, permission requests); the
Compose agent panel renders that state. Node-based agents (Claude Code,
Gemini CLI) need Node, which comes from the Linux userland's own package
manager like any other tool — so the agent panel is one more thing that
depends on the userland being real, and one more reason there is no
edition without it.

## Privacy

No telemetry, no analytics, no phoning home. Network access happens
only for features the user explicitly invokes (cloning a repo,
downloading a language server or agent, an agent calling its own API).
