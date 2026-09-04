//! Language servers, run inside the Debian userland.
//!
//! There is no LSP client here. Zed's `lsp` crate is vendored (`core/vendor/lsp`,
//! in the workspace since P3-2) and it owns the whole protocol: framing,
//! request ids, the response and notification tables, capability negotiation,
//! the shutdown handshake. What this module owns is everything *around* it —
//! which server to start for a language, how to reach it through proot, when a
//! document is opened, changed, saved and closed, and how the answers get to a
//! UI that must never block.
//!
//! Five things shape it.
//!
//! **The server is started by Zed's own code.** `LanguageServer::new`
//! (vendor/lsp/src/lsp.rs:429) spawns `binary.path` with `binary.arguments`,
//! and the only executable Android will run for us is proot — so *proot is the
//! binary*, and the server's own argv is the tail of proot's. That command line
//! is `guest.rs`'s, exposed as [`guest::Invocation`] rather than copied, so a
//! language server enters the guest through the exact flags, binds and
//! environment `git status` does. Nothing in the vendored crate is patched.
//!
//! **It lives on the gpui runtime.** `LanguageServer` wants an `AsyncApp`: its
//! reader, writer and timeout tasks are gpui tasks. We already run a headless
//! `App` on a thread of its own (`runtime.rs`), which is where every other
//! vendored crate's work happens, so that is where the client goes too.
//!
//! **Nothing the UI calls waits for a server.** Diagnostics are pushed by the
//! server, cached here, and published behind a generation counter the UI polls
//! — deliberately the same shape as `git.rs` and `project_search.rs`, so a
//! third feature does not mean a third mechanism. The three request wrappers
//! ([`Engine::lsp_request_completion`] and friends) return an id immediately
//! and fill their answer in later, and a newer request of the same kind
//! supersedes the older one exactly the way a newer project search does —
//! which, because Zed's request future sends `$/cancelRequest` when it is
//! dropped (lsp.rs:1518), also tells the server to stop working on it.
//!
//! **Silence when there is nothing to run.** No userland (the `play` flavour,
//! or a `full` build before the user installs one), no server for the language,
//! a server that is not installed, a server that dies on startup: all of them
//! are "this buffer has no language intelligence", never an error and never a
//! panic. It is the contract `git.rs` already has, and it is the normal state
//! of a fresh Debian.
//!
//! **Processes are rationed, and this is what rations them** (P5-4). A desktop
//! editor may start a server per language and forget about it; a phone may not.
//! Android caps an app's background processes at 32 where it enforces the cap,
//! one guest run is proot *plus* its tracee, and that one budget is shared with
//! the terminal's shells, `git`, and `apt`. So this module is the only thing in
//! the engine that starts an unbounded number of long-lived children, and it
//! carries three rules the rest of the file exists around: a **cap** on how
//! many servers run at once ([`MAX_RUNNING_SERVERS`], arithmetic below), an
//! **idle sweep** that stops the ones not earning their processes ([`retire`]),
//! and a **refusal that is visible** — a server the cap turns away is reported
//! `unavailable` with a sentence, exactly like one that is not installed, so
//! there is nothing new for the UI to render and nothing silently missing.

use std::collections::{HashMap, HashSet};
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use gpui::{AsyncApp, Task};
use lsp::{
    CompletionParams, CompletionResponse, CompletionTextEdit, DidChangeTextDocumentParams,
    DidSaveTextDocumentParams, Documentation, GotoDefinitionParams, GotoDefinitionResponse,
    HoverContents, HoverParams, LanguageServer, LanguageServerBinary, LanguageServerId,
    LanguageServerName, MarkedString, PartialResultParams, Position, PublishDiagnosticsParams,
    Range, TextDocumentContentChangeEvent, TextDocumentIdentifier, TextDocumentPositionParams,
    TextDocumentSyncCapability, TextDocumentSyncKind, TextDocumentSyncSaveOptions, Uri,
    VersionedTextDocumentIdentifier, WorkDoneProgressParams,
};
use path::PathStyle;
use rope::PointUtf16;
use util::paths::PathMatcher;

use crate::guest::{self, GuestCommand};
use crate::project::ProjectId;
use crate::{BufferId, Buffers};

/// How long `initialize` gets. rust-analyzer on a cold page cache, inside
/// proot, on a phone, is slow enough that Zed's own 120 s default is not
/// obviously too generous — but a server that has not answered in a minute is
/// not going to, and the UI has been saying "starting" all that time.
const INITIALIZE_TIMEOUT: Duration = Duration::from_secs(60);

/// Per-request deadlines. A completion the user has already typed past is
/// worthless, so it gets the shortest; a definition may need an index built.
const COMPLETION_TIMEOUT: Duration = Duration::from_secs(4);
const HOVER_TIMEOUT: Duration = Duration::from_secs(4);
const DEFINITION_TIMEOUT: Duration = Duration::from_secs(8);
const REFERENCES_TIMEOUT: Duration = Duration::from_secs(8);
const CODE_ACTION_TIMEOUT: Duration = Duration::from_secs(8);
/// Rename walks the same index references does, then writes; formatting can
/// spawn a formatter binary inside the guest. Both are asked for and waited
/// on, never raced against typing, so they get the longest deadline.
const RENAME_TIMEOUT: Duration = Duration::from_secs(15);
const FORMATTING_TIMEOUT: Duration = Duration::from_secs(15);
/// Inlay hints and signature help are asked as the user types and scrolls;
/// an answer that arrives after the next keystroke is discarded anyway.
const INLAY_HINT_TIMEOUT: Duration = Duration::from_secs(4);
const SIGNATURE_HELP_TIMEOUT: Duration = Duration::from_secs(4);
const FOLDING_RANGE_TIMEOUT: Duration = Duration::from_secs(4);
/// A workspace symbol query walks the whole index, like references.
const WORKSPACE_SYMBOL_TIMEOUT: Duration = Duration::from_secs(8);
/// A server command may run a formatter or a build step behind it.
const EXECUTE_COMMAND_TIMEOUT: Duration = Duration::from_secs(15);

/// How many lines each server's log keeps — stderr, `window/logMessage`, and
/// the RPC trace. Zed's log view keeps its own bounded store
/// (language_tools/src/lsp_log_view.rs); two thousand lines is a few screens
/// of rust-analyzer chatter and a few kilobytes per server.
const LOG_LINES: usize = 2000;

/// How much of one RPC message the trace keeps. A `didChange` carrying a
/// whole file, or a completion list, would otherwise be the whole log.
const LOG_MESSAGE_CHARS: usize = 400;

// ---------------------------------------------------------------------------
// The process budget (P5-4)
// ---------------------------------------------------------------------------

/// What one running server costs, in processes Android counts against the app.
///
/// A guest run is two — proot and the program it traces
/// ([`guest::PROCESSES_PER_RUN`]) — and a language server is not a leaf: it
/// forks for the work that produces most of its diagnostics. rust-analyzer runs
/// `cargo check` on save (cargo, then a rustc per crate); gopls shells out to
/// `go list`; pylsp forks pyflakes; clangd forks nothing at all. The `+ 1` is
/// that burst averaged over the servers we ship a mapping for — it is not a
/// limit on it, which is what the spare in the arithmetic below is for.
const PROCESSES_PER_SERVER: usize = guest::PROCESSES_PER_RUN + 1;

/// What the rest of the app must be able to take from the same budget, at a
/// peak, without the language servers having spent it first.
///
/// - **8, the terminal.** A session is proot + bash = 2, and a session is
///   opened to *run* something, which is at least one more; two sessions with a
///   build in one of them is 8. The terminal is the feature the user is looking
///   at when it dies, so it is reserved generously.
/// - **6, git.** `git status` is debounced to one run in flight per project (2),
///   and a clone is proot + git + git-remote-https + index-pack = 4. They
///   overlap: the panel refreshes while a clone runs.
/// - **6, apt.** P5-2's install is proot + apt + dpkg + the maintainer scripts
///   dpkg runs, and it is the one thing here the user explicitly waits for.
///
/// Android counts *processes*, not threads, so the JVM, the engine's gpui
/// runtime and every worker thread in this crate cost nothing here.
const RESERVED_PROCESSES: usize = 8 + 6 + 6;

/// How many language servers may run at once, **across every open project** —
/// because the budget is one app's, not one project's.
///
/// `(32 − 20) / 3 = 4`. Four servers hold 8 processes between them and can burst
/// to 12 without touching what the terminal, git and apt have reserved; a fifth
/// would put the peak over the cap, and going over the cap does not mean a
/// failed spawn — it means Android killing *something of ours*, most likely the
/// shell the user is watching, because the phantom-process killer picks its
/// victim and we do not.
///
/// Four is also more languages than a phone screen holds tabs for: a project
/// mixing Rust, C++, Go, Python and TypeScript *at once* is real but rare, and
/// what it gets is the fifth language without intelligence and a sentence
/// saying so — not a killed terminal.
///
/// Deliberately not a setting. A number the user can raise is a number the user
/// can use to break the terminal, and the honest fix for wanting a fifth server
/// is closing the tabs that hold the fourth.
const MAX_RUNNING_SERVERS: usize =
    (guest::PROCESS_BUDGET - RESERVED_PROCESSES) / PROCESSES_PER_SERVER;

/// The cap as it stands *right now*: [`MAX_RUNNING_SERVERS`] when nothing
/// else has moved into the budget, less while the ACP agent holds its share
/// ([`guest::RESERVED_FOR_AGENT`], set by acp.rs) — the revisit the P5-4
/// decision scheduled. With the agent's 6 that is (32 − 20 − 6) / 3 = 2
/// servers, and the sweep's idle rules are what free the difference; a server
/// already past the reduced cap is not killed, because the burst spare
/// absorbs the overlap the same way it absorbs a pressure sweep's.
fn max_running_servers() -> usize {
    let reserved = RESERVED_PROCESSES + guest::RESERVED_FOR_AGENT.load(Ordering::Relaxed);
    // A reservation can only shrink the cap, never raise it past the
    // configured ceiling.
    MAX_RUNNING_SERVERS.min(guest::PROCESS_BUDGET.saturating_sub(reserved) / PROCESSES_PER_SERVER)
}

/// What a server refused by the cap reports as its `error`.
///
/// It rides the contract that already exists — `lspServers[].state =
/// unavailable` with an `error` sentence — so the status bar says it with no UI
/// change (the Kotlin side renders "<name> could not start: <error>";
/// ui/editor/Diagnostics.kt:401-411). Never a crash, and never a silent
/// nothing: a language that quietly had no intelligence would look exactly like
/// a language whose server is not installed, and the two want different
/// answers from the user.
const CAP_REACHED: &str = "too many language servers running";

/// What a server the user stopped reports as its `error` — the same
/// contract as [`CAP_REACHED`], and read by the status bar's menu to offer
/// "Restart" instead of "Install".
const STOPPED_BY_USER: &str = "stopped";

/// How long a server with no open documents is kept before it is stopped.
///
/// Zed stops nothing here: closing the last buffer drops the *registration*
/// (`unregister_buffer_from_language_servers`, lsp_store.rs:3156-3173, reached
/// when the refcount hits zero at lsp_store.rs:4964-4986) while the server
/// itself runs until the project closes or a setting changes
/// (`stop_local_language_server`, lsp_store.rs:11696). We send the same
/// `didClose` — and then, unlike Zed, we stop the process, because on a phone
/// the two processes it holds are the ones the next language needs.
///
/// The grace is what makes it bearable: closing a tab and reopening it, or
/// closing the last `.rs` tab on the way to another one, must not cost a
/// rust-analyzer restart. Half a minute covers that and nothing longer.
const IDLE_WITHOUT_DOCUMENTS: Duration = Duration::from_secs(30);

/// The backstop Zed does not need and we do: a server that has not exchanged a
/// byte with us in ten minutes is stopped even though its documents are open.
///
/// A desktop can afford a rust-analyzer sitting on an open tab all afternoon.
/// A phone cannot: the app is backgrounded far more than it is used, and it is
/// *while backgrounded* that Android counts our children and kills them by its
/// own rules. Ten minutes is chosen to be far longer than any pause in editing
/// (a server is touched by every keystroke, save, completion, hover and
/// diagnostic publish) and far shorter than "the user has gone".
///
/// A stopped server is not gone for good: its documents are marked dormant, and
/// the next edit, save or request in one of them starts it again — see
/// [`Engine::wake_server`].
const IDLE_WITHOUT_TRAFFIC: Duration = Duration::from_secs(10 * 60);

/// How often the sweep runs when nothing is polling us.
///
/// The foreground sweep rides `lsp_version`, which is the poll the UI already
/// makes — but a backgrounded app polls nothing, and background is exactly when
/// the budget is enforced. So one timer on the runtime's background executor
/// (not a thread of its own) ticks this, from the moment the first server
/// starts.
const SWEEP_INTERVAL: Duration = Duration::from_secs(30);

// ---------------------------------------------------------------------------
// Which server serves which language
// ---------------------------------------------------------------------------

/// A language server we know how to start.
///
/// Keyed by [`Server::name`] rather than by language, so one clangd serves both
/// `c` and `cpp` in a project and one typescript-language-server serves
/// `typescript` and `tsx` — which is what those servers expect, and what a
/// phone with a cap on background processes needs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct Server {
    /// The program name, also the key in the per-project registry and the name
    /// shown in the UI.
    pub name: &'static str,
    /// The guest argv, program included. `--stdio` and friends belong here.
    pub argv: &'static [&'static str],
    /// Environment the server needs and nothing else in the guest does.
    /// Under the toolchain's and the user's `binary.env`, which both win.
    pub env: &'static [(&'static str, &'static str)],
}

/// The rustup toolchain rust-analyzer runs on, and runs cargo with.
///
/// The phone *builds* with platform-tools, a Rust of Solana's own, and that
/// compiler ships no proc-macro server — the sysroot's
/// `libexec/rust-analyzer-proc-macro-srv`, which has to have been built by
/// the very compiler that built the macros, so no standalone one can stand
/// in. With no server every Anchor attribute stays unexpanded and a fresh
/// scaffold opens with thirteen false errors (Seeker, 2026-09-04). So the
/// editor *reads* with a stock toolchain that has one: the toolchain
/// manifest installs a pinned version and links it under this name
/// (`app/src/main/assets/solana/toolchain/manifest.json`, `rust-editor`),
/// and the rustup shims on the guest `PATH` route `rust-analyzer`, `cargo`
/// and `rustc` there for the server alone — every terminal, task and agent
/// keeps the default toolchain. A phone without the component gets rustup's
/// own "toolchain 'thragg-editor' is not installed" as the server's
/// unavailable sentence, which is the truth and names the fix.
pub(crate) const EDITOR_TOOLCHAIN: &str = "thragg-editor";

/// Grammar name (what `highlight::language_for_path` answers, and what
/// `Engine::buffer_language` reports) → the server for it and the `languageId`
/// the LSP spec wants in `didOpen`.
///
/// Deliberately short and deliberately explicit. Every entry is a Debian
/// package the user has to install themselves — P5-2 is what installs them, and
/// until it exists every one of these is simply absent, which this module
/// treats as a normal state rather than a failure. A grammar missing from this
/// table has no server, which is also normal: we highlight far more languages
/// than Debian packages a server for.
fn server_for(grammar: &str) -> Option<(Server, &'static str)> {
    const CLANGD: Server = Server {
        name: "clangd",
        argv: &["clangd", "--background-index"],
        env: &[],
    };
    const TYPESCRIPT: Server = Server {
        name: "typescript-language-server",
        argv: &["typescript-language-server", "--stdio"],
        env: &[],
    };
    Some(match grammar {
        // Debian: rust-analyzer
        "rust" => (
            Server {
                name: "rust-analyzer",
                argv: &["rust-analyzer"],
                env: &[("RUSTUP_TOOLCHAIN", EDITOR_TOOLCHAIN)],
            },
            "rust",
        ),
        // Debian: clangd
        "c" => (CLANGD, "c"),
        "cpp" => (CLANGD, "cpp"),
        // Debian: gopls
        "go" => (
            Server {
                name: "gopls",
                argv: &["gopls", "serve"],
                env: &[],
            },
            "go",
        ),
        // Debian: python3-pylsp
        "python" => (
            Server {
                name: "pylsp",
                argv: &["pylsp"],
                env: &[],
            },
            "python",
        ),
        // Debian: node-typescript-language-server
        "typescript" => (TYPESCRIPT, "typescript"),
        "tsx" => (TYPESCRIPT, "typescriptreact"),
        _ => return None,
    })
}

/// proot's command line for a server, as Zed's `LanguageServerBinary`.
///
/// This is the whole of route (1) in agent-docs/research/lsp-approach.md: the
/// binary is proot, its arguments are proot's flags followed by the server's
/// own argv, and `LanguageServer::new` spawns it without knowing any of that.
/// Split out from [`start_server`] because it is the one part of starting a
/// server that can be tested on a host with no rootfs — and losing a flag here
/// fails exactly as quietly as losing one in `guest.rs` does.
pub(crate) fn server_binary(
    userland: &guest::Userland,
    server: &Server,
    root: &Path,
    binary: Option<&crate::config::BinarySettings>,
    toolchain: &crate::ToolchainEnv,
) -> LanguageServerBinary {
    let argv = server_argv(server, binary);
    // `workdir` both binds the project and starts the server inside it: unlike
    // git, which carries `-C`, a language server finds its own manifest by
    // looking around the directory it was started in.
    let mut command = GuestCommand::new(server.name.to_owned(), argv).workdir(root);
    // The server's own needs first ([`EDITOR_TOOLCHAIN`] for rust-analyzer),
    // then the active toolchain, then the user's `lsp.<server>.binary.env`
    // over both: a server started outside the project's virtualenv cannot
    // resolve a single one of its imports, and a `binary.env` written by
    // hand is the most specific instruction of the three.
    for (key, value) in server.env {
        command = command.env(key, value);
    }
    for (key, value) in toolchain.iter() {
        command = command.env(key, value);
    }
    if let Some(env) = binary.and_then(|binary| binary.env.as_ref()) {
        for (key, value) in env {
            command = command.env(key, value);
        }
    }
    let invocation = guest::invocation(userland, &command);
    LanguageServerBinary {
        path: invocation.program,
        arguments: invocation.args,
        env: Some(
            invocation
                .env
                .into_iter()
                .map(|(key, value)| {
                    (
                        key.to_string_lossy().into_owned(),
                        value.to_string_lossy().into_owned(),
                    )
                })
                .collect(),
        ),
    }
}

/// The guest argv for a server: the built-in table's, unless the user's
/// `lsp.<server>.binary` says otherwise — Zed's rule (project/src/lsp_store.rs
/// `LanguageServerBinary` from `BinarySettings`): a `path` replaces the
/// program and takes only the `arguments` given with it, none by default,
/// because the table's flags belong to the table's program; `arguments`
/// alone keep the program and replace its flags.
fn server_argv(server: &Server, binary: Option<&crate::config::BinarySettings>) -> Vec<OsString> {
    let program = binary
        .and_then(|binary| binary.path.as_deref())
        .filter(|path| !path.trim().is_empty());
    let arguments = binary.and_then(|binary| binary.arguments.as_deref());
    match (program, arguments) {
        (Some(path), arguments) => std::iter::once(path)
            .chain(arguments.unwrap_or_default().iter().map(String::as_str))
            .map(OsString::from)
            .collect(),
        (None, Some(arguments)) => std::iter::once(server.argv[0])
            .chain(arguments.iter().map(String::as_str))
            .map(OsString::from)
            .collect(),
        (None, None) => server.argv.iter().map(OsString::from).collect(),
    }
}

// ---------------------------------------------------------------------------
// What the UI reads
// ---------------------------------------------------------------------------

/// LSP's four severities, under the names the UI paints with. `severity` is
/// never absent in what we hand out: a diagnostic without one is a warning,
/// which is what every editor assumes and is the safer of the two guesses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Severity {
    Error,
    Warning,
    Info,
    Hint,
}

/// One diagnostic, in the coordinates the editor draws in.
///
/// Columns are UTF-16 code units, like `HighlightSpan` and `outline_path` — and
/// unlike them this costs nothing to arrange, because UTF-16 is the position
/// encoding we negotiate with the server in the first place (`initialize`'s
/// `general.positionEncodings`, vendor/lsp/src/lsp.rs:808).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct DiagnosticRow {
    pub row: u32,
    pub col_utf16: u32,
    pub end_row: u32,
    pub end_col_utf16: u32,
    pub severity: Severity,
    pub message: String,
    /// Which analysis produced it ("rustc", "clippy", "clangd"), when the
    /// server says.
    pub source: Option<String>,
    /// The server's own code for it ("E0308", "unused-variable"), as a string
    /// whichever of LSP's two forms it arrived in.
    pub code: Option<String>,
}

/// Everything the editor needs to underline one buffer.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct BufferDiagnostics {
    /// The value [`Engine::buffer_diagnostics_version`] returns; 0 means no
    /// server has ever published for this file.
    pub version: u64,
    /// The buffer version the rows describe. `null` when the server dated its
    /// publish against a document version we no longer recognise — it is
    /// describing text that has already been replaced. Note that 0 is a real
    /// buffer version (a file just opened), which is why this is nullable
    /// rather than sentinelled.
    pub buffer_version: Option<u64>,
    /// The buffer has moved since the server saw it, so the rows are in the
    /// right shape but possibly the wrong place. Zed dims them; so should we.
    pub stale: bool,
    pub rows: Vec<DiagnosticRow>,
}

/// How many of each severity a file holds.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, serde::Serialize)]
pub struct Counts {
    pub errors: usize,
    pub warnings: usize,
    pub infos: usize,
    pub hints: usize,
}

impl Counts {
    fn add(&mut self, severity: Severity) {
        match severity {
            Severity::Error => self.errors += 1,
            Severity::Warning => self.warnings += 1,
            Severity::Info => self.infos += 1,
            Severity::Hint => self.hints += 1,
        }
    }

    fn merge(&mut self, other: Counts) {
        self.errors += other.errors;
        self.warnings += other.warnings;
        self.infos += other.infos;
        self.hints += other.hints;
    }

    fn is_empty(&self) -> bool {
        *self == Counts::default()
    }
}

/// Everything the status bar and a diagnostics panel need for a project.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct ProjectDiagnostics {
    /// The value [`Engine::lsp_version`] returns.
    pub version: u64,
    #[serde(flatten)]
    pub totals: Counts,
    /// Files with at least one diagnostic, sorted by path.
    pub files: Vec<FileDiagnosticCounts>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct FileDiagnosticCounts {
    /// Project-relative, `/`-separated — the spelling `TreeEntry::path` and
    /// `ChangedFile::path` use, so a panel row can be cross-referenced.
    pub path: String,
    #[serde(flatten)]
    pub counts: Counts,
}

/// Every diagnostic a project's servers have published, messages and all —
/// what a diagnostics panel lists, where [`ProjectDiagnostics`] only counts.
///
/// A separate read rather than rows on [`ProjectDiagnostics`], because that
/// one is polled twice a second by a status bar that wants four integers; this
/// one serializes every message in the project and is read only while a panel
/// is showing them.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct ProjectDiagnosticRows {
    /// The value [`Engine::lsp_version`] returns.
    pub version: u64,
    /// Files with at least one diagnostic, sorted by path.
    pub files: Vec<FileDiagnosticRows>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct FileDiagnosticRows {
    /// Spelled exactly as [`FileDiagnosticCounts::path`] is.
    pub path: String,
    /// Sorted by position, like [`BufferDiagnostics::rows`].
    pub rows: Vec<DiagnosticRow>,
}

/// What one server in a project is doing.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ServerState {
    /// Spawned, waiting for the `initialize` response.
    Starting,
    Running,
    /// It could not be started, or it stopped answering. `error` says why, at
    /// the level of detail worth showing: usually "not installed".
    Unavailable,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct ServerStatus {
    pub name: String,
    pub state: ServerState,
    pub error: Option<String>,
    /// The grammars this instance is serving, sorted.
    pub languages: Vec<String>,
    /// What the server says it is doing right now — rust-analyzer's
    /// "indexing (45%)" — or null when it is quiet. One line, first token
    /// wins: the bar has one line of room.
    pub progress: Option<String>,
    /// The user stopped it (`editor::StopLanguageServer`); the status bar's
    /// menu offers to start it again rather than to install it.
    pub stopped: bool,
}

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RequestKind {
    Completion,
    Hover,
    Definition,
    References,
    /// The list of actions at a position. Holds the parsed actions back for
    /// [`RequestKind::CodeActionApply`] to pick from.
    CodeAction,
    /// Resolving one chosen action and readying its edit. Its own kind, not a
    /// re-use of [`RequestKind::CodeAction`]: applying must not supersede the
    /// list it is picking from.
    CodeActionApply,
    Rename,
    Formatting,
    /// The `code_actions_on_format` pass that precedes a format: every
    /// configured kind asked for over the whole document, their edits held.
    CodeActionsOnFormat,
    /// Zed's `editor::GoToTypeDefinition`, `GoToImplementation` and
    /// `GoToDeclaration` — the three siblings of [`RequestKind::Definition`],
    /// answered in its payload shape.
    TypeDefinition,
    Implementation,
    Declaration,
    /// `textDocument/inlayHint` over a row range.
    InlayHint,
    /// `textDocument/signatureHelp` at the caret.
    SignatureHelp,
    /// `workspace/symbol`, fanned out to every running server of a project.
    WorkspaceSymbol,
    /// `textDocument/foldingRange` for a whole buffer.
    FoldingRange,
    /// `completionItem/resolve` for one item of a settled
    /// [`RequestKind::Completion`] list — its own kind, for the same reason
    /// [`RequestKind::CodeActionApply`] is: resolving must not supersede the
    /// list it is picking from.
    CompletionResolve,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RequestState {
    /// In flight.
    Pending,
    /// Answered. `payload` holds the answer, which may legitimately be empty.
    Done,
    /// The server did not answer inside its deadline, and has been told to
    /// stop. Distinct from `done` with nothing in it, because the UI should
    /// not cache "no completions here" from a timeout.
    Timeout,
    /// There is no server for this buffer — no userland, no server for the
    /// language, or one that failed to start. Not an error; the normal state
    /// of the `play` flavour.
    Unavailable,
    /// Superseded by a newer request of the same kind, cancelled outright, or
    /// an id the engine has forgotten.
    Cancelled,
}

/// One request's answer, whatever kind it is.
#[derive(Debug, Clone, serde::Serialize)]
pub struct RequestResult {
    pub id: u64,
    pub kind: RequestKind,
    pub state: RequestState,
    /// Bumped once, when the answer lands. 1 while pending, 2 once settled, 0
    /// for an id the engine has forgotten — the same liveness signal
    /// `projectSearchVersion` gives.
    pub version: u64,
    pub buffer_id: BufferId,
    /// Where it was asked, echoed back so a late answer can be discarded by a
    /// UI whose caret has moved.
    pub row: u32,
    pub col_utf16: u32,
    /// The buffer version it was asked at, for the same reason.
    pub buffer_version: u64,
    /// The answer. Shape depends on `kind`; see [`Engine::lsp_request_result`].
    /// `null` until the request settles.
    pub payload: serde_json::Value,
}

impl RequestResult {
    /// The answer for an id the engine no longer holds. Every field but `id`
    /// and `state` is a placeholder — there is nothing left to report — and
    /// `kind` in particular is not to be believed: the caller knows what it
    /// asked for, and this is the one shape that cannot.
    fn forgotten(id: u64) -> Self {
        Self {
            id,
            kind: RequestKind::Completion,
            state: RequestState::Cancelled,
            version: 0,
            buffer_id: 0,
            row: 0,
            col_utf16: 0,
            buffer_version: 0,
            payload: serde_json::Value::Null,
        }
    }
}

/// What a settled request holds back from its JSON payload: the parsed
/// artefacts the *next* step needs. The payload the UI reads is a summary;
/// a `WorkspaceEdit` crossing the JNI boundary and back would only be
/// re-parsed into exactly this.
enum StoredWork {
    None,
    /// The actions a [`RequestKind::CodeAction`] answered with, in payload
    /// order, waiting for [`Engine::lsp_request_code_action_apply`] to pick.
    Actions(Vec<lsp::CodeActionOrCommand>),
    /// The edit a rename, a formatting or a resolved code action produced,
    /// waiting for [`Engine::lsp_apply_pending_edit`].
    Edit(lsp::WorkspaceEdit),
    /// The edits of every action `code_actions_on_format` produced, in the
    /// server's order, applied one after the other by the same call.
    Edits(Vec<lsp::WorkspaceEdit>),
    /// The items a [`RequestKind::Completion`] answered with, in payload
    /// order, waiting for [`Engine::lsp_request_completion_resolve`] to pick
    /// — the wire item carries the `data` a server wants echoed back, and
    /// that is exactly what must not cross the bridge and return.
    Completions(Vec<lsp::CompletionItem>),
}

/// What one server has been told to apply through `workspace/applyEdit`,
/// waiting for the request that caused it. Zed keeps the same inbox per
/// server (`last_workspace_edits_by_language_server`, lsp_store.rs:2287)
/// so a command's edits can be attributed to the code action that ran it.
type AppliedEdits = Arc<Mutex<Vec<lsp::WorkspaceEdit>>>;

/// What a request needs beyond its position — gathered on the caller's
/// thread, carried into [`perform`] by value so the runtime job owns no
/// borrows.
enum RequestArgs {
    None,
    /// The diagnostics under the position, which is the context
    /// `textDocument/codeAction` wants — a server offers "fix this" only for
    /// problems the client says it is looking at.
    CodeActions { diagnostics: Vec<lsp::Diagnostic> },
    /// The action [`Engine::lsp_request_code_action_apply`] picked out of the
    /// list request's [`StoredWork::Actions`], and the server's applyEdit
    /// inbox, drained after a command runs.
    CodeActionApply {
        action: Box<lsp::CodeActionOrCommand>,
        applied: AppliedEdits,
    },
    Rename { new_name: String },
    Formatting { tab_size: u32, insert_spaces: bool },
    /// The kinds to ask for, and the end of the document the request spans.
    CodeActionsOnFormat { kinds: Vec<String>, end: Position },
    /// The last row of the range hints are wanted for; the first is the
    /// request's own `row`.
    InlayHints { end_row: u32 },
    /// The item [`Engine::lsp_request_completion_resolve`] picked out of the
    /// list request's [`StoredWork::Completions`].
    CompletionResolve { item: Box<lsp::CompletionItem> },
}

struct Pending {
    id: u64,
    kind: RequestKind,
    buffer: BufferId,
    row: u32,
    col_utf16: u32,
    buffer_version: u64,
    answer: Mutex<(RequestState, u64, serde_json::Value)>,
    /// See [`StoredWork`]. Written before the answer settles, so a reader
    /// that has seen the settle can trust what it finds here.
    stored: Mutex<StoredWork>,
}

impl Pending {
    fn settle(&self, state: RequestState, payload: serde_json::Value) {
        let mut answer = self.answer.lock().unwrap();
        // A superseded request must not overwrite its successor's slot — it no
        // longer owns one — and must not un-cancel itself either.
        if answer.0 != RequestState::Pending {
            return;
        }
        *answer = (state, 2, payload);
    }

    fn result(&self) -> RequestResult {
        let answer = self.answer.lock().unwrap();
        RequestResult {
            id: self.id,
            kind: self.kind,
            state: answer.0,
            version: answer.1,
            buffer_id: self.buffer,
            row: self.row,
            col_utf16: self.col_utf16,
            buffer_version: self.buffer_version,
            payload: answer.2.clone(),
        }
    }
}

#[derive(Default)]
struct Requests {
    live: HashMap<u64, Arc<Pending>>,
    /// At most one live request per kind, so a completion popup that re-asks on
    /// every keystroke cannot accumulate work — the same rule
    /// `ProjectSearches` applies per project.
    latest: HashMap<RequestKind, u64>,
    /// Held only to cancel: dropping a gpui `Task` drops Zed's request future,
    /// which sends `$/cancelRequest` on the way out.
    tasks: HashMap<u64, Task<()>>,
}

impl Requests {
    /// Retire whatever was running for `kind` and make `id` the live one.
    fn supersede(&mut self, kind: RequestKind, id: u64) {
        if let Some(previous) = self.latest.insert(kind, id) {
            self.retire(previous);
        }
    }

    fn retire(&mut self, id: u64) -> bool {
        self.tasks.remove(&id);
        match self.live.remove(&id) {
            Some(pending) => {
                pending.settle(RequestState::Cancelled, serde_json::Value::Null);
                true
            }
            None => false,
        }
    }
}

// ---------------------------------------------------------------------------
// Cached state
// ---------------------------------------------------------------------------

/// Diagnostics as they arrive, and the counters that publish them.
///
/// Separate from [`LspState`] and behind an `Arc` because the
/// `publishDiagnostics` handler outlives every borrow of the engine: it is
/// owned by the `LanguageServer`, called on the runtime thread, and must keep
/// working while the caller that started the server is long gone.
#[derive(Default)]
pub(crate) struct DiagnosticStore {
    files: Mutex<HashMap<PathBuf, FileDiagnostics>>,
    /// Per project, bumped whenever anything above — or any server's state —
    /// moves.
    versions: Mutex<HashMap<ProjectId, u64>>,
    /// The last version pair we sent for a document: the LSP document version,
    /// and the engine buffer version it corresponded to. A publish carrying a
    /// different LSP version describes text we have already replaced.
    sent: Mutex<HashMap<PathBuf, (i32, u64)>>,
}

struct FileDiagnostics {
    project: ProjectId,
    version: u64,
    /// The engine buffer version the rows describe; `None` when the publish
    /// could not be dated against anything we sent.
    buffer_version: Option<u64>,
    counts: Counts,
    rows: Arc<Vec<DiagnosticRow>>,
}

impl DiagnosticStore {
    fn bump(&self, project: ProjectId) -> u64 {
        let mut versions = self.versions.lock().unwrap();
        let version = versions.entry(project).or_insert(0);
        *version += 1;
        *version
    }

    fn version(&self, project: ProjectId) -> u64 {
        self.versions
            .lock()
            .unwrap()
            .get(&project)
            .copied()
            .unwrap_or(0)
    }

    /// Remember what we last told the server, so a publish can be dated.
    fn note_sent(&self, path: &Path, lsp_version: i32, buffer_version: u64) {
        self.sent
            .lock()
            .unwrap()
            .insert(path.to_path_buf(), (lsp_version, buffer_version));
    }

    /// A document has been closed: we can no longer date what the server said
    /// about it, but what it said still stands.
    ///
    /// Deliberately *not* a removal. Servers whose analysis is workspace-wide —
    /// rust-analyzer's `cargo check` is the one that matters — go on reporting
    /// a file after `didClose`, and Zed's own diagnostics are project-wide for
    /// exactly that reason: closing a tab must not empty a diagnostics panel.
    /// A server that does drop a closed file publishes an empty list for it,
    /// and *that* is what clears these rows.
    ///
    /// The version mapping does go, so the rows read as stale until the server
    /// republishes — which is honest, because after a close and a reopen there
    /// is no buffer version they can be said to describe.
    fn undate(&self, path: &Path) {
        self.sent.lock().unwrap().remove(path);
        if let Some(file) = self.files.lock().unwrap().get_mut(path) {
            file.buffer_version = None;
        }
    }

    fn forget_project(&self, project: ProjectId) {
        let mut files = self.files.lock().unwrap();
        let mut sent = self.sent.lock().unwrap();
        files.retain(|path, file| {
            let ours = file.project == project;
            if ours {
                sent.remove(path);
            }
            !ours
        });
        drop(files);
        drop(sent);
        self.versions.lock().unwrap().remove(&project);
    }

    /// Install what a server just published for one file.
    fn publish(&self, project: ProjectId, path: PathBuf, params: PublishDiagnosticsParams) {
        let buffer_version = match (params.version, self.sent.lock().unwrap().get(&path)) {
            // The server dated its publish and it matches what we last sent:
            // the rows describe exactly that buffer version.
            (Some(published), Some(&(sent, buffer_version))) if published == sent => {
                Some(buffer_version)
            }
            // It dated it and it does not match — the text has moved on since.
            (Some(_), _) => None,
            // Many servers do not date publishes at all. Then the newest thing
            // we sent is the best answer available, and `stale` below still
            // catches the buffer moving afterwards.
            (None, Some(&(_, buffer_version))) => Some(buffer_version),
            (None, None) => None,
        };

        let mut rows: Vec<DiagnosticRow> = params
            .diagnostics
            .into_iter()
            .map(|diagnostic| DiagnosticRow {
                row: diagnostic.range.start.line,
                col_utf16: diagnostic.range.start.character,
                end_row: diagnostic.range.end.line,
                end_col_utf16: diagnostic.range.end.character,
                severity: severity_of(diagnostic.severity),
                message: diagnostic.message,
                source: diagnostic.source,
                code: diagnostic.code.map(|code| match code {
                    lsp::NumberOrString::Number(number) => number.to_string(),
                    lsp::NumberOrString::String(string) => string,
                }),
            })
            .collect();
        // Sorted so the editor can walk the rows it is painting in one pass,
        // and so "the next diagnostic" (Zed's F8) is a scan rather than a sort.
        rows.sort_by_key(|row| (row.row, row.col_utf16, row.end_row, row.end_col_utf16));

        let mut counts = Counts::default();
        for row in &rows {
            counts.add(row.severity);
        }

        let version = self.bump(project);
        let mut files = self.files.lock().unwrap();
        if rows.is_empty() {
            // An empty publish is how a server retracts everything it said
            // about a file. Dropping the entry is what keeps the summary from
            // listing files with nothing wrong with them.
            files.remove(&path);
        } else {
            files.insert(
                path,
                FileDiagnostics {
                    project,
                    version,
                    buffer_version,
                    counts,
                    rows: Arc::new(rows),
                },
            );
        }
    }
}

fn severity_of(severity: Option<lsp::DiagnosticSeverity>) -> Severity {
    match severity {
        Some(lsp::DiagnosticSeverity::ERROR) => Severity::Error,
        Some(lsp::DiagnosticSeverity::INFORMATION) => Severity::Info,
        Some(lsp::DiagnosticSeverity::HINT) => Severity::Hint,
        // WARNING, and anything the server left out or invented.
        _ => Severity::Warning,
    }
}

/// One document we have told a server about.
struct OpenDoc {
    project: ProjectId,
    server: &'static str,
    path: PathBuf,
    uri: Uri,
    grammar: &'static str,
    language_id: &'static str,
    /// LSP document version. First `didOpen` is 1 and every `didChange`
    /// increments, which is the whole of the spec's requirement.
    lsp_version: i32,
    /// `didOpen` has actually been sent. False while the server is still
    /// initializing: edits before that only move `lsp_version` forward, and the
    /// `didOpen` that eventually goes out carries the current text.
    opened: bool,
    /// Its server was stopped by the idle sweep while this document was still
    /// open, so the document is still ours but nothing is watching it.
    ///
    /// It exists to break a loop: polling is what starts servers
    /// ([`Engine::start_pending_servers`]), so a server stopped for silence
    /// would be restarted by the very next poll and the sweep would be a
    /// stutter rather than a budget. Dormant documents are skipped there, and
    /// woken by *activity* instead — an edit, a save, a completion — which is
    /// the only evidence that the user is back.
    dormant: bool,
}

/// One server instance for one project.
pub(crate) struct Slot {
    project: ProjectId,
    server: Server,
    state: ServerState,
    error: Option<String>,
    /// Live once `initialize` has answered.
    handle: Option<Arc<LanguageServer>>,
    sync: TextDocumentSyncKind,
    /// The server asked to be told about saves.
    wants_save: bool,
    /// Kept so the `publishDiagnostics` handler is removed with the server
    /// rather than left dangling on a shared map.
    subscriptions: Vec<lsp::Subscription>,
    /// The last time anything crossed this server's pipes, in either
    /// direction: a `didOpen`/`didChange`/`didSave` or a request going out, a
    /// `publishDiagnostics` coming back. What [`retire`] measures silence
    /// against.
    ///
    /// Also, for a slot with no documents left, the moment the last one
    /// closed — which is the same thing, because closing sends `didClose`.
    last_activity: Instant,
    /// What the server says it is doing, per `$/progress` token — "indexing",
    /// "cargo check" — while it is doing it. Emptied token by token as the
    /// `End` reports arrive; the status bar shows whatever is left.
    progress: HashMap<String, ServerProgress>,
    /// This slot holds no process: it is the *record* of a server the cap
    /// refused, kept so the status bar can say so and so the next poll does
    /// not try again while the budget is still spent.
    ///
    /// Distinct from every other `Unavailable` — "not installed", above all —
    /// because those must never be retried on a timer (a retry is a proot
    /// spawn per poll for a package that will still not be there), and this one
    /// must be retried the moment a slot frees up.
    capped: bool,
    /// What the server said it can do, kept whole: the trigger characters
    /// the completion menu and signature help open on come from here, and
    /// so does whether `textDocument/foldingRange` is worth asking for.
    capabilities: Option<Box<lsp::ServerCapabilities>>,
    /// Edits the server pushed through `workspace/applyEdit` and nobody has
    /// claimed yet — see [`AppliedEdits`].
    applied_edits: AppliedEdits,
    /// The user stopped this server by hand (`editor::StopLanguageServer`).
    /// Kept as a record so the poll does not start it again on the next
    /// frame — only an explicit restart, or the project reopening, ends it.
    /// The status bar reads it as "stopped".
    stopped: bool,
}

impl Slot {
    fn starting(project: ProjectId, server: Server) -> Self {
        Self {
            project,
            server,
            state: ServerState::Starting,
            error: None,
            handle: None,
            sync: TextDocumentSyncKind::INCREMENTAL,
            wants_save: false,
            subscriptions: Vec::new(),
            last_activity: Instant::now(),
            progress: HashMap::new(),
            capped: false,
            capabilities: None,
            applied_edits: Arc::default(),
            stopped: false,
        }
    }

    /// The record of a server the user stopped.
    fn stopped(project: ProjectId, server: Server) -> Self {
        Self {
            state: ServerState::Unavailable,
            error: Some(STOPPED_BY_USER.to_owned()),
            stopped: true,
            ..Self::starting(project, server)
        }
    }

    /// The record of a server the cap turned away.
    fn refused(project: ProjectId, server: Server) -> Self {
        Self {
            state: ServerState::Unavailable,
            error: Some(CAP_REACHED.to_owned()),
            capped: true,
            ..Self::starting(project, server)
        }
    }

    /// Whether this slot is spending processes — which is what the cap counts,
    /// and what the sweep can free. A `Starting` slot has a proot and a tracee,
    /// or is a moment away from having them, which for a budget is the same
    /// thing; an `Unavailable` one has neither and never will.
    fn holds_processes(&self) -> bool {
        matches!(self.state, ServerState::Starting | ServerState::Running)
    }
}

/// One `$/progress` token's state, as the server last reported it. Kept as
/// parts rather than a rendered line, because a `Report` may carry only a
/// percentage and the title it belongs to arrived in the `Begin`.
#[derive(Clone)]
pub(crate) struct ServerProgress {
    title: String,
    message: Option<String>,
    percentage: Option<u32>,
}

impl ServerProgress {
    /// "indexing: 3/10 crates (30%)" — the whole of it in one line, which is
    /// all a status bar has room to say.
    fn line(&self) -> String {
        let mut line = self.title.clone();
        if let Some(message) = &self.message {
            line.push_str(": ");
            line.push_str(message);
        }
        if let Some(percentage) = self.percentage {
            line.push_str(&format!(" ({percentage}%)"));
        }
        line
    }
}

pub(crate) type SlotMap = HashMap<(ProjectId, &'static str), Slot>;

/// The server registry, shared so a runtime job can install the server it
/// finished starting without holding a borrow on the engine.
pub(crate) type Slots = Arc<Mutex<SlotMap>>;

type DocMap = HashMap<BufferId, OpenDoc>;

/// The open-document table, shared for the same reason: the `didOpen` for a
/// document registered while its server was still initializing is sent by that
/// server's own start job.
type Docs = Arc<Mutex<DocMap>>;

/// The file watchers each server registered through `client/registerCapability`
/// — rust-analyzer watches `**/Cargo.toml`, gopls watches `**/*.go` — keyed by
/// the server's own registration id so `client/unregisterCapability` can take
/// exactly what it names. Shared for the slots' reason: registrations arrive on
/// the runtime, changes arrive from the worktree's watcher.
pub(crate) type Watchers =
    Arc<Mutex<HashMap<(ProjectId, &'static str), HashMap<String, PathMatcher>>>>;

/// Which servers each open project's *tree* calls for — a folder of `.rs`
/// files wants rust-analyzer whether or not a tab is open — cached against
/// the project's mirror version so the walk runs when the tree changes, not
/// on every poll. Shared because the sweep reads it from the background.
pub(crate) type ProjectWants = Arc<Mutex<HashMap<ProjectId, ((u64, u64), Vec<Server>)>>>;

/// The tree-wanted servers the sweep has put to rest. Without this the poll
/// would restart a swept server on the next frame and the sweep would be a
/// stutter rather than a budget — the same loop [`OpenDoc::dormant`] breaks
/// for documents. Cleared by *activity*: a buffer of the language opening,
/// a save waking the server, the project closing.
pub(crate) type Rested = Arc<Mutex<HashSet<(ProjectId, &'static str)>>>;

/// One server's log: the last [`LOG_LINES`] of what it wrote to stderr,
/// what it said through `window/logMessage` and `window/showMessage`, the
/// RPC trace, and this module's own lifecycle notes — Zed's
/// `dev::OpenLanguageServerLogs` view, as a ring rather than a store.
#[derive(Default)]
pub(crate) struct ServerLog {
    lines: std::collections::VecDeque<String>,
    /// Bumped per line, so the log tab polls a counter rather than the text.
    version: u64,
}

impl ServerLog {
    fn push(&mut self, line: String) {
        if self.lines.len() >= LOG_LINES {
            self.lines.pop_front();
        }
        self.lines.push_back(line);
        self.version += 1;
    }
}

/// The logs, keyed like the slots. Shared for the slots' reason: lines
/// arrive on the runtime from the server's own pipes.
pub(crate) type Logs = Arc<Mutex<HashMap<(ProjectId, &'static str), ServerLog>>>;

/// Append one line to a server's log. A message longer than
/// [`LOG_MESSAGE_CHARS`] is cut with an ellipsis, on a character boundary.
fn log_line(logs: &Logs, key: (ProjectId, &'static str), prefix: &str, message: &str) {
    let message = message.trim_end();
    let mut line = String::with_capacity(prefix.len() + message.len().min(LOG_MESSAGE_CHARS) + 2);
    line.push_str(prefix);
    match message.char_indices().nth(LOG_MESSAGE_CHARS) {
        Some((cut, _)) => {
            line.push_str(&message[..cut]);
            line.push('…');
        }
        None => line.push_str(message),
    }
    logs.lock().unwrap().entry(key).or_default().push(line);
}

/// What the log tab reads: the lines, and the counter to poll instead.
#[derive(Debug, Clone, serde::Serialize)]
pub struct ServerLogSnapshot {
    pub version: u64,
    pub lines: Vec<String>,
}

/// The characters a buffer's server opens its menus on, from the
/// capabilities it declared at `initialize` — Zed's
/// `completion_triggers` and the signature-help `trigger_characters` /
/// `retrigger_characters` (lsp_store.rs:2789, 3084).
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize)]
pub struct BufferTriggers {
    pub completion: Vec<String>,
    pub signature_help: Vec<String>,
    pub signature_help_retrigger: Vec<String>,
    /// The server answers `textDocument/foldingRange`, so a fold request is
    /// worth making; otherwise the syntax tree is the only source.
    pub folding_ranges: bool,
    /// The server answers `textDocument/inlayHint` at all.
    pub inlay_hints: bool,
}

/// Install what one `client/registerCapability` said about watched files.
///
/// Anything that is not a watched-files registration is acknowledged and
/// ignored — dynamic registration of *providers* changes nothing for a client
/// that consults server capabilities per request. A glob that will not compile
/// is logged and dropped rather than failing the registration: the server is
/// entitled to `Ok`, and a watcher we cannot honour is a change notification
/// the server does not get, which is the state it was in anyway.
fn register_watchers(
    watchers: &Watchers,
    project: ProjectId,
    server: &'static str,
    params: &lsp::RegistrationParams,
) {
    for registration in &params.registrations {
        if registration.method != "workspace/didChangeWatchedFiles" {
            continue;
        }
        let Some(options) = registration.register_options.clone() else {
            continue;
        };
        let Ok(options) =
            serde_json::from_value::<lsp::DidChangeWatchedFilesRegistrationOptions>(options)
        else {
            log::warn!("lsp: {server} sent watchers this client cannot read");
            continue;
        };
        let globs: Vec<String> = options
            .watchers
            .iter()
            .map(|watcher| match &watcher.glob_pattern {
                lsp::GlobPattern::String(pattern) => pattern.clone(),
                // A relative pattern is a base URI and a glob under it; spelled
                // out it is the same thing as an absolute string pattern.
                lsp::GlobPattern::Relative(relative) => {
                    let base = match &relative.base_uri {
                        lsp::OneOf::Left(folder) => folder.uri.clone(),
                        lsp::OneOf::Right(uri) => uri.clone(),
                    };
                    match base.to_file_path() {
                        Ok(base) => format!(
                            "{}/{}",
                            base.to_string_lossy().trim_end_matches('/'),
                            relative.pattern
                        ),
                        Err(()) => relative.pattern.clone(),
                    }
                }
            })
            .collect();
        match PathMatcher::new(&globs, PathStyle::Unix) {
            Ok(matcher) => {
                watchers
                    .lock()
                    .unwrap()
                    .entry((project, server))
                    .or_default()
                    .insert(registration.id.clone(), matcher);
            }
            Err(err) => log::warn!("lsp: {server} registered an unreadable glob: {err}"),
        }
    }
}

/// The unregister half: remove exactly the registrations the server names.
fn unregister_watchers(
    watchers: &Watchers,
    project: ProjectId,
    server: &'static str,
    params: &lsp::UnregistrationParams,
) {
    let mut watchers = watchers.lock().unwrap();
    let Some(registered) = watchers.get_mut(&(project, server)) else {
        return;
    };
    for unregistration in &params.unregisterations {
        if unregistration.method == "workspace/didChangeWatchedFiles" {
            registered.remove(&unregistration.id);
        }
    }
}

/// worktree's `PathChange`, in the words LSP's `FileChangeType` has for it —
/// and `None` for the one that must never be forwarded: `Loaded` is the
/// initial scan finding a file that was always there, and forwarding it would
/// hand every server one event per file in the tree at startup.
pub(crate) fn watched_change_type(change: &worktree::PathChange) -> Option<lsp::FileChangeType> {
    match change {
        worktree::PathChange::Added => Some(lsp::FileChangeType::CREATED),
        worktree::PathChange::Removed => Some(lsp::FileChangeType::DELETED),
        worktree::PathChange::Updated => Some(lsp::FileChangeType::CHANGED),
        // The watcher could not tell which; `CHANGED` is the answer a server
        // treats as "go look", which is the safe reading of not knowing.
        worktree::PathChange::AddedOrUpdated => Some(lsp::FileChangeType::CHANGED),
        worktree::PathChange::Loaded => None,
    }
}

/// The events each of a project's servers asked to hear about, out of one
/// batch of disk changes. Pure over the watcher table, which is what makes it
/// testable without a server on the other end.
fn collect_watched(
    watchers: &HashMap<(ProjectId, &'static str), HashMap<String, PathMatcher>>,
    project: ProjectId,
    root: &Path,
    changes: &[(PathBuf, lsp::FileChangeType)],
) -> Vec<(&'static str, Vec<lsp::FileEvent>)> {
    watchers
        .iter()
        .filter(|((id, _), registered)| *id == project && !registered.is_empty())
        .filter_map(|((_, server), registered)| {
            let events: Vec<lsp::FileEvent> = changes
                .iter()
                .filter(|(path, _)| {
                    // Servers spell their globs both ways — gopls's
                    // `**/*.go` speaks in suffixes, a `RelativePattern` in
                    // absolute paths — so a change matches on either its
                    // absolute spelling or its root-relative one.
                    registered.values().any(|matcher| {
                        matcher.is_match_std_path(path)
                            || path
                                .strip_prefix(root)
                                .is_ok_and(|relative| matcher.is_match_std_path(relative))
                    })
                })
                .filter_map(|(path, kind)| {
                    Some(lsp::FileEvent::new(Uri::from_file_path(path).ok()?, *kind))
                })
                .collect();
            (!events.is_empty()).then_some((*server, events))
        })
        .collect()
}

/// Tell every server that registered a matching watcher about a batch of disk
/// changes — the worktree's subscription calls this, already on the runtime.
///
/// The one comparable traffic a server gets without asking, and like
/// `publishDiagnostics` it is proof of life: the activity clock moves so the
/// idle sweep does not stop a server mid-reindex.
pub(crate) fn notify_watched_files(
    slots: &Slots,
    watchers: &Watchers,
    project: ProjectId,
    root: &Path,
    changes: &[(PathBuf, lsp::FileChangeType)],
) {
    if changes.is_empty() {
        return;
    }
    let interested = collect_watched(&watchers.lock().unwrap(), project, root, changes);
    for (server, events) in interested {
        let handle = {
            let mut slots = slots.lock().unwrap();
            let Some(slot) = slots.get_mut(&(project, server)) else {
                continue;
            };
            slot.last_activity = Instant::now();
            slot.handle.clone()
        };
        if let Some(handle) = handle {
            handle
                .notify::<lsp::notification::DidChangeWatchedFiles>(
                    lsp::DidChangeWatchedFilesParams { changes: events },
                )
                .ok();
        }
    }
}

// ---------------------------------------------------------------------------
// The two decisions the budget comes down to, as functions of nothing but
// numbers. Everything above them is policy and everything below them is
// plumbing; these are the part worth a test that cannot spawn a process.
// ---------------------------------------------------------------------------

/// What asking for a server gets you.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Claim {
    /// There is already a slot for it — running, starting, or a refusal from
    /// earlier. Nothing to do.
    Taken,
    /// Room in the budget: start it.
    Room,
    /// The budget is spent. Say so; do not start it, and above all do not stop
    /// somebody else's server to make room (see [`sweep`]).
    Full,
}

/// Ask the registry for a slot, and take it when the budget allows.
///
/// Counting and inserting under one lock, deliberately: a count that is already
/// stale by the time it is acted on is not a cap. `cap` is passed in —
/// [`max_running_servers`] in production — so the decision stays a function of
/// numbers a test can name.
fn claim(servers: &mut SlotMap, project: ProjectId, server: Server, cap: usize) -> Claim {
    if servers.contains_key(&(project, server.name)) {
        return Claim::Taken;
    }
    if running_servers(servers) >= cap {
        return Claim::Full;
    }
    servers.insert((project, server.name), Slot::starting(project, server));
    Claim::Room
}

fn running_servers(servers: &SlotMap) -> usize {
    servers
        .values()
        .filter(|slot| slot.holds_processes())
        .count()
}

/// Why a server should stop, or that it should not.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Retire {
    Keep,
    /// Every document it was serving has closed. Zed's rule, one step further:
    /// Zed drops the registration, we drop the process too.
    NoDocuments,
    /// Nothing has crossed its pipes in [`IDLE_WITHOUT_TRAFFIC`], though
    /// documents are still open. Ours alone; a phone's budget is real.
    Silent,
}

/// The whole of the idle policy, as a function of four values.
///
/// `under_pressure` is a start being refused right now. It only shortens the
/// no-documents grace, and it shortens it to nothing: that grace exists because
/// processes were free at the time, and the moment they are not, a server whose
/// last tab closed has no claim on them at all. It deliberately does **not**
/// shorten the silence limit — a server with a document open is one the user
/// may still be reading, and evicting it to start another would mean two
/// servers each paying a cold index to serve one file apiece.
///
/// `tree_wants` is a server the project's own files call for — started at
/// folder-open, tabs or no tabs. It is judged by the *silence* rule rather
/// than the no-documents one, because "no tab open" is its normal working
/// state — except under pressure, where a docless server loses to one
/// actually serving a tab whatever the tree says: the budget is real and the
/// tab is the user's stated interest.
fn retire(documents: usize, tree_wants: bool, idle_for: Duration, under_pressure: bool) -> Retire {
    if documents == 0 && (!tree_wants || under_pressure) {
        if under_pressure || idle_for >= IDLE_WITHOUT_DOCUMENTS {
            return Retire::NoDocuments;
        }
        return Retire::Keep;
    }
    if idle_for >= IDLE_WITHOUT_TRAFFIC {
        return Retire::Silent;
    }
    Retire::Keep
}

/// Stop every server the policy retires, and hand the caller their slots to
/// drop where a shutdown handshake can run.
///
/// A free function over the shared maps rather than a method, because the
/// background timer that runs it while the app is not being polled holds those
/// `Arc`s and nothing else — no `Engine`, no borrow, no lifetime.
///
/// Locks in the declared order (servers, then docs, then the store) and holds
/// none of them across the drop: dropping a [`Slot`] runs Zed's `shutdown`/
/// `exit` handshake (vendor/lsp/src/lsp.rs:1750-1755), which is work, and work
/// under our locks is how a UI thread ends up waiting for a language server.
fn sweep(
    slots: &Slots,
    docs: &Docs,
    store: &DiagnosticStore,
    wants: &ProjectWants,
    rested: &Rested,
    now: Instant,
    under_pressure: bool,
) -> Vec<Slot> {
    let mut stopped: Vec<(ProjectId, Slot)> = Vec::new();
    let mut orphaned: Vec<PathBuf> = Vec::new();
    {
        let tree_wants: HashSet<(ProjectId, &'static str)> = wants
            .lock()
            .unwrap()
            .iter()
            .flat_map(|(project, (_, servers))| {
                servers.iter().map(|server| (*project, server.name))
            })
            .collect();
        let mut servers = slots.lock().unwrap();
        let mut docs = docs.lock().unwrap();

        let mut documents: HashMap<(ProjectId, &'static str), usize> = HashMap::new();
        for doc in docs.values() {
            *documents.entry((doc.project, doc.server)).or_insert(0) += 1;
        }

        let doomed: Vec<((ProjectId, &'static str), Retire)> = servers
            .iter()
            .filter(|(_, slot)| slot.holds_processes())
            .filter_map(|(key, slot)| {
                let verdict = retire(
                    documents.get(key).copied().unwrap_or(0),
                    tree_wants.contains(key),
                    // Saturating: `now` is passed in so tests can name it, and
                    // a caller naming one before a slot was created should get
                    // "no time has passed", not a panic.
                    now.saturating_duration_since(slot.last_activity),
                    under_pressure,
                );
                (verdict != Retire::Keep).then_some((*key, verdict))
            })
            .collect();

        for (key, verdict) in doomed {
            let Some(slot) = servers.remove(&key) else {
                continue;
            };
            log::info!(
                "lsp: stopping {} for project {} ({verdict:?})",
                key.1,
                key.0
            );
            // Its documents stay ours — the user still has them on screen — but
            // nothing is watching them until something wakes the server.
            for doc in docs
                .values_mut()
                .filter(|doc| (doc.project, doc.server) == key)
            {
                doc.opened = false;
                doc.dormant = true;
                orphaned.push(doc.path.clone());
            }
            // The tree still wants it; only activity may bring it back, or
            // the poll would restart it before this sweep's locks are cold.
            rested.lock().unwrap().insert(key);
            stopped.push((key.0, slot));
        }
    }

    // What the server said stands — the same reasoning as `lsp_did_close` —
    // but it can no longer be dated against a buffer version, so the rows read
    // as stale until a restarted server publishes again. Which is honest:
    // nothing is watching that file at this moment.
    for path in orphaned {
        store.undate(&path);
    }
    for (project, _) in &stopped {
        // A server leaving the list changes what `lsp_servers` answers, and the
        // status bar only re-reads when this counter moves.
        store.bump(*project);
    }
    stopped.into_iter().map(|(_, slot)| slot).collect()
}

/// Lock order, wherever two of these are wanted at once: **servers, then docs,
/// then the store**. Nothing here takes them in the other order, and nothing
/// holds one across an `await`.
#[derive(Default)]
pub(crate) struct LspState {
    servers: Slots,
    docs: Docs,
    store: Arc<DiagnosticStore>,
    /// What each server asked to be told about the disk — see [`Watchers`].
    watchers: Watchers,
    /// What each project's tree calls for — see [`ProjectWants`].
    wants: ProjectWants,
    /// Tree-wanted servers the sweep stopped — see [`Rested`].
    rested: Rested,
    /// Each server's last two thousand lines — see [`Logs`].
    logs: Logs,
    requests: Arc<Mutex<Requests>>,
    next_request_id: AtomicU64,
    next_server_id: AtomicU64,
    /// The idle sweep's timer has been started. Set once, when the first server
    /// starts; there is nothing to sweep before that and the `play` flavour
    /// never gets here at all.
    sweeping: AtomicBool,
    /// True once a server has actually been started for something. Read on the
    /// keystroke path by [`Engine::edit`], where even a hash lookup per edit is
    /// a cost the `play` flavour has no reason to pay — and it never sets this,
    /// because it never has a userland to start anything in.
    live: AtomicBool,
    /// A live server negotiated whole-document `didChange`. Almost never true —
    /// the servers we start all take incremental sync — and checked before
    /// [`Engine::edit`] pays `Buffer::text()`, which is O(file). Shared,
    /// because the server that sets it is installed by a runtime job.
    wants_full_text: Arc<AtomicBool>,
    /// The last change [`Engine::edit`] and friends produced. Test-only: it is
    /// the only way to see what a server *would* have been told on a host that
    /// has no server to tell.
    #[cfg(test)]
    last_change: Mutex<Option<TextChange>>,
}

// ---------------------------------------------------------------------------
// The edit that has to reach the server
// ---------------------------------------------------------------------------

/// One change to a document, in the coordinates LSP speaks.
///
/// Built by [`Engine::edit`] from the snapshot it already took *before*
/// applying the edit, because the range a `didChange` carries is a range in the
/// old text and there is no way to recover it afterwards.
#[derive(Clone)]
pub(crate) struct TextChange {
    pub start: PointUtf16,
    pub old_end: PointUtf16,
    pub text: String,
    /// The whole new document, for a server that negotiated full sync.
    /// Filled in only when [`LspState::wants_full_text`] says somebody needs
    /// it.
    pub whole: Option<String>,
    /// The engine buffer version after the change.
    pub buffer_version: u64,
}

fn position(point: PointUtf16) -> Position {
    Position::new(point.row, point.column)
}

/// A change, as the `didChange` the negotiated sync kind asks for.
///
/// `None` means "send nothing": a server that took `NONE` does not want
/// changes at all, and one that took `FULL` cannot be told anything useful
/// without the whole document — only reachable in the instant between such a
/// server initializing and [`LspState::wants_full_text`] being observed by the
/// next edit, which then carries it.
fn content_changes(
    change: TextChange,
    sync: TextDocumentSyncKind,
) -> Option<Vec<TextDocumentContentChangeEvent>> {
    if sync == TextDocumentSyncKind::NONE {
        return None;
    }
    if sync == TextDocumentSyncKind::FULL {
        return Some(vec![TextDocumentContentChangeEvent {
            range: None,
            range_length: None,
            text: change.whole?,
        }]);
    }
    Some(vec![TextDocumentContentChangeEvent {
        // The range is in the *old* text, and in UTF-16 code units because
        // that is the encoding `initialize` negotiated. Getting either wrong
        // desynchronizes the server silently: it keeps answering, about a
        // document that is no longer the one on screen.
        range: Some(Range::new(position(change.start), position(change.old_end))),
        range_length: None,
        text: change.text,
    }])
}

// ---------------------------------------------------------------------------
// Engine surface
// ---------------------------------------------------------------------------

impl crate::Engine {
    /// Generation counter for everything LSP knows about a project:
    /// diagnostics for any of its files, and the state of its servers. Poll it
    /// exactly like `git_status_version`.
    ///
    /// Polling is also what *starts* servers, for the same reason it is what
    /// refreshes git: the userland can appear while files are already open —
    /// the user installs Debian, or `apt install clangd`, with the editor
    /// running — and a client that only ever started servers on `open_file`
    /// would stay silent until the file was closed and reopened. It never
    /// waits for one.
    pub fn lsp_version(&self, project: ProjectId) -> u64 {
        self.start_pending_servers(project);
        self.lsp.store.version(project)
    }

    /// What each server for this project is doing, for a status-bar item.
    /// Reads a cache; never blocks.
    pub fn lsp_servers(&self, project: ProjectId) -> Vec<ServerStatus> {
        let mut languages: HashMap<&'static str, Vec<String>> = HashMap::new();
        for doc in self.lsp.docs.lock().unwrap().values() {
            if doc.project == project {
                languages
                    .entry(doc.server)
                    .or_default()
                    .push(doc.grammar.to_owned());
            }
        }
        let mut statuses: Vec<ServerStatus> = self
            .lsp
            .servers
            .lock()
            .unwrap()
            .values()
            .filter(|slot| slot.project == project)
            .map(|slot| {
                let mut languages = languages.remove(slot.server.name).unwrap_or_default();
                languages.sort();
                languages.dedup();
                let mut tokens: Vec<&String> = slot.progress.keys().collect();
                tokens.sort();
                ServerStatus {
                    name: slot.server.name.to_owned(),
                    state: slot.state,
                    error: slot.error.clone(),
                    languages,
                    progress: tokens
                        .first()
                        .and_then(|token| slot.progress.get(*token))
                        .map(ServerProgress::line),
                    stopped: slot.stopped,
                }
            })
            .collect();
        statuses.sort_by(|a, b| a.name.cmp(&b.name));
        statuses
    }

    /// Every file in the project with a diagnostic, and the totals. Reads a
    /// cache; never blocks; empty when no server has ever published.
    pub fn lsp_diagnostics(&self, project: ProjectId) -> ProjectDiagnostics {
        let root = self.project_root(project);
        let files = self.lsp.store.files.lock().unwrap();
        let mut totals = Counts::default();
        let mut rows: Vec<FileDiagnosticCounts> = files
            .iter()
            .filter(|(_, file)| file.project == project && !file.counts.is_empty())
            .map(|(path, file)| {
                totals.merge(file.counts);
                FileDiagnosticCounts {
                    path: relative_path(root.as_deref(), path),
                    counts: file.counts,
                }
            })
            .collect();
        rows.sort_by(|a, b| a.path.cmp(&b.path));
        ProjectDiagnostics {
            version: self.lsp.store.version(project),
            totals,
            files: rows,
        }
    }

    /// Every diagnostic in the project, messages included — the panel's read,
    /// where [`Engine::lsp_diagnostics`] is the status bar's. Reads a cache;
    /// never blocks; empty when no server has ever published.
    pub fn lsp_diagnostic_rows(&self, project: ProjectId) -> ProjectDiagnosticRows {
        let root = self.project_root(project);
        let files = self.lsp.store.files.lock().unwrap();
        let mut rows: Vec<FileDiagnosticRows> = files
            .iter()
            .filter(|(_, file)| file.project == project && !file.rows.is_empty())
            .map(|(path, file)| FileDiagnosticRows {
                path: relative_path(root.as_deref(), path),
                rows: (*file.rows).clone(),
            })
            .collect();
        rows.sort_by(|a, b| a.path.cmp(&b.path));
        ProjectDiagnosticRows {
            version: self.lsp.store.version(project),
            files: rows,
        }
    }

    /// Generation counter for one buffer's diagnostics; 0 until a server has
    /// published for its file. Cheaper than [`Engine::buffer_diagnostics`] by
    /// enough that an editor with ten tabs open should poll this per tab and
    /// only read the rows that moved.
    pub fn buffer_diagnostics_version(&self, buffer: BufferId) -> u64 {
        let Some(path) = self.buffer_path(buffer) else {
            return 0;
        };
        self.lsp
            .store
            .files
            .lock()
            .unwrap()
            .get(&path)
            .map(|file| file.version)
            .unwrap_or(0)
    }

    /// Everything a server has said about this buffer's file, in UTF-16
    /// columns. Reads a cache; never blocks; empty for a buffer with no file,
    /// no server, or nothing wrong with it.
    pub fn buffer_diagnostics(&self, buffer: BufferId) -> BufferDiagnostics {
        let Some(path) = self.buffer_path(buffer) else {
            return BufferDiagnostics::default();
        };
        let files = self.lsp.store.files.lock().unwrap();
        let Some(file) = files.get(&path) else {
            return BufferDiagnostics::default();
        };
        let current = self.version(buffer).unwrap_or(0);
        BufferDiagnostics {
            version: file.version,
            buffer_version: file.buffer_version,
            // Unknown counts as stale: the rows may describe text nobody has
            // any more, and a wrong underline is worse than a dimmed one.
            stale: file.buffer_version != Some(current),
            rows: (*file.rows).clone(),
        }
    }

    /// Ask for completions at a caret. Returns a request id to poll with —
    /// never blocks, never fails: a buffer with no server gets an id that
    /// reports `unavailable` straight away, so the UI has one code path.
    ///
    /// Supersedes whatever completion request was already in flight, and tells
    /// the server to stop working on it.
    pub fn lsp_request_completion(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Completion, buffer, row, col_utf16)
    }

    /// Hover documentation at a caret. See [`Engine::lsp_request_completion`]
    /// for the contract.
    pub fn lsp_request_hover(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Hover, buffer, row, col_utf16)
    }

    /// Where the symbol under the caret is defined. See
    /// [`Engine::lsp_request_completion`] for the contract.
    pub fn lsp_request_definition(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Definition, buffer, row, col_utf16)
    }

    /// Everywhere the symbol under the caret is used, declaration included.
    /// See [`Engine::lsp_request_completion`] for the contract.
    pub fn lsp_request_references(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::References, buffer, row, col_utf16)
    }

    /// The code actions available at a caret — quick fixes for the
    /// diagnostics under it, refactorings otherwise. The answer is a list of
    /// titles; the actions themselves stay here, keyed by index, for
    /// [`Engine::lsp_request_code_action_apply`]. See
    /// [`Engine::lsp_request_completion`] for the polling contract.
    pub fn lsp_request_code_actions(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        let diagnostics = self.diagnostics_under(buffer, row);
        self.start_request_with(
            RequestKind::CodeAction,
            buffer,
            row,
            col_utf16,
            RequestArgs::CodeActions { diagnostics },
        )
    }

    /// Pick one action out of a settled [`Engine::lsp_request_code_actions`]
    /// answer and ready its edit — resolving through `codeAction/resolve`
    /// when the server sent it lazy. Settles `Done` with an edit summary;
    /// [`Engine::lsp_apply_pending_edit`] then lands it. A stale list id or a
    /// bad index settles `Unavailable` immediately, on the same one path.
    pub fn lsp_request_code_action_apply(&self, list: u64, index: usize) -> u64 {
        let picked = {
            let requests = self.lsp.requests.lock().unwrap();
            requests.live.get(&list).and_then(|pending| {
                let stored = pending.stored.lock().unwrap();
                match &*stored {
                    StoredWork::Actions(actions) => actions
                        .get(index)
                        .cloned()
                        .map(|action| (pending.buffer, pending.row, pending.col_utf16, action)),
                    _ => None,
                }
            })
        };
        match picked {
            Some((buffer, row, col_utf16, action)) => {
                // The server's applyEdit inbox, for an action that runs a
                // command: what the command pushes back is this action's
                // edit.
                let applied = self
                    .lsp
                    .docs
                    .lock()
                    .unwrap()
                    .get(&buffer)
                    .map(|doc| (doc.project, doc.server))
                    .and_then(|key| {
                        self.lsp
                            .servers
                            .lock()
                            .unwrap()
                            .get(&key)
                            .map(|slot| slot.applied_edits.clone())
                    })
                    .unwrap_or_default();
                self.start_request_with(
                    RequestKind::CodeActionApply,
                    buffer,
                    row,
                    col_utf16,
                    RequestArgs::CodeActionApply {
                        action: Box::new(action),
                        applied,
                    },
                )
            }
            // Buffer 0 is never a registered document, so this settles
            // `Unavailable` on the ordinary path rather than inventing one.
            None => self.start_request(RequestKind::CodeActionApply, 0, 0, 0),
        }
    }

    /// Rename the symbol under the caret to [`new_name`], everywhere.
    /// Settles `Done` with an edit summary and holds the edit for
    /// [`Engine::lsp_apply_pending_edit`] — nothing is changed until then.
    pub fn lsp_request_rename(
        &self,
        buffer: BufferId,
        row: u32,
        col_utf16: u32,
        new_name: &str,
    ) -> u64 {
        self.start_request_with(
            RequestKind::Rename,
            buffer,
            row,
            col_utf16,
            RequestArgs::Rename {
                new_name: new_name.to_owned(),
            },
        )
    }

    /// Format the whole document, with the workspace's own tab size. Settles
    /// `Done` with an edit summary — zero edits is a well-formatted file —
    /// and holds the edit for [`Engine::lsp_apply_pending_edit`].
    pub fn lsp_request_formatting(&self, buffer: BufferId) -> u64 {
        // The buffer's own resolved settings, so a project's `.zed` or a
        // `languages.Go` entry is what the formatter is told, not the global.
        let settings = self.buffer_language_settings(buffer);
        self.start_request_with(
            RequestKind::Formatting,
            buffer,
            0,
            0,
            RequestArgs::Formatting {
                tab_size: settings.tab_size,
                insert_spaces: !settings.hard_tabs,
            },
        )
    }

    /// Run the buffer's `code_actions_on_format` — the kinds the settings
    /// mark `true`, asked of the server over the whole document with
    /// `only` set to them, as Zed does before every format
    /// (project/src/lsp_store.rs `execute_code_actions_on_servers`). Each
    /// action the server offers is resolved and its edit held; settles
    /// `Done` with the edits' summary and holds them for
    /// [`Engine::lsp_apply_pending_edit`], which lands them in order. With no
    /// kinds configured it settles `Done` with nothing to apply, so the save
    /// path has one shape.
    pub fn lsp_request_code_actions_on_format(&self, buffer: BufferId) -> u64 {
        let kinds: Vec<String> = self
            .buffer_language_settings(buffer)
            .code_actions_on_format
            .into_iter()
            .filter_map(|(kind, enabled)| enabled.then_some(kind))
            .collect();
        let end = self
            .with_buffer(buffer, |state| state.buffer.snapshot().max_point_utf16())
            .unwrap_or_default();
        self.start_request_with(
            RequestKind::CodeActionsOnFormat,
            buffer,
            0,
            0,
            RequestArgs::CodeActionsOnFormat {
                kinds,
                end: Position::new(end.row, end.column),
            },
        )
    }

    /// Where the *type* of the symbol under the caret is defined — Zed's
    /// `editor::GoToTypeDefinition`. Definition's payload shape and polling
    /// contract.
    pub fn lsp_request_type_definition(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::TypeDefinition, buffer, row, col_utf16)
    }

    /// The implementations of the trait or interface under the caret —
    /// Zed's `editor::GoToImplementation`. Definition's shape.
    pub fn lsp_request_implementation(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Implementation, buffer, row, col_utf16)
    }

    /// The declaration of the symbol under the caret — Zed's
    /// `editor::GoToDeclaration`, which C and C++ tell apart from the
    /// definition. Definition's shape.
    pub fn lsp_request_declaration(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::Declaration, buffer, row, col_utf16)
    }

    /// The inlay hints for rows `first_row..=last_row` — the visible range,
    /// which is what Zed asks for per excerpt (inlay_hint_cache.rs). Settles
    /// `Done` with `{hints: [{row, col_utf16, label, kind, padding_left,
    /// padding_right}]}`; `row` and `buffer_version` echo the ask so a late
    /// answer for text that has moved can be dropped. Supersedes the
    /// previous hint request, as a scroll should.
    pub fn lsp_request_inlay_hints(&self, buffer: BufferId, first_row: u32, last_row: u32) -> u64 {
        self.start_request_with(
            RequestKind::InlayHint,
            buffer,
            first_row,
            0,
            RequestArgs::InlayHints {
                end_row: last_row.max(first_row),
            },
        )
    }

    /// The signature of the call the caret sits in — Zed's
    /// `editor::ShowSignatureHelp`. Settles `Done` with `{signatures:
    /// [{label, documentation, parameters: [{start, end, documentation}],
    /// active_parameter}], active_signature}`, where a parameter's `start`
    /// and `end` are UTF-16 offsets into its signature's label.
    pub fn lsp_request_signature_help(&self, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request(RequestKind::SignatureHelp, buffer, row, col_utf16)
    }

    /// The server's own folding ranges for a buffer. Settles `Done` with
    /// `{ranges: [{start_row, end_row}]}` in the shape of
    /// [`Engine::fold_ranges`] — `end_row` is the last hidden row — or
    /// `unavailable` from a server that does not fold, which is the cue to
    /// use the syntax tree instead.
    pub fn lsp_request_folding_ranges(&self, buffer: BufferId) -> u64 {
        if !self.lsp_buffer_triggers(buffer).folding_ranges {
            // Not worth a round trip: the answer would be "method not
            // found", and the UI has the syntax tree already.
            return self.start_request(RequestKind::FoldingRange, 0, 0, 0);
        }
        self.start_request(RequestKind::FoldingRange, buffer, 0, 0)
    }

    /// Resolve one item of a settled [`Engine::lsp_request_completion`]
    /// list — documentation and `additionalTextEdits` the server left out
    /// of the list, exactly as Zed resolves the selected row
    /// (`resolve_completions`, completions.rs). Settles `Done` with
    /// `{documentation, detail, additional_edits}` and, when there are
    /// additional edits (an import to add), holds them for
    /// [`Engine::lsp_apply_pending_edit`], to be landed after the completion
    /// itself is inserted. A stale list id or bad index settles
    /// `unavailable`.
    pub fn lsp_request_completion_resolve(&self, list: u64, index: usize) -> u64 {
        let picked = {
            let requests = self.lsp.requests.lock().unwrap();
            requests.live.get(&list).and_then(|pending| {
                let stored = pending.stored.lock().unwrap();
                match &*stored {
                    StoredWork::Completions(items) => items
                        .get(index)
                        .cloned()
                        .map(|item| (pending.buffer, pending.row, pending.col_utf16, item)),
                    _ => None,
                }
            })
        };
        match picked {
            Some((buffer, row, col_utf16, item)) => self.start_request_with(
                RequestKind::CompletionResolve,
                buffer,
                row,
                col_utf16,
                RequestArgs::CompletionResolve {
                    item: Box::new(item),
                },
            ),
            None => self.start_request(RequestKind::CompletionResolve, 0, 0, 0),
        }
    }

    /// `workspace/symbol` across every running server of a project — Zed's
    /// `project_symbols::Toggle` picker asks every server the same way
    /// (project_symbols.rs → `Project::symbols`). Settles `Done` with
    /// `{symbols: [{name, kind, container, path, row, col_utf16, end_row,
    /// end_col_utf16, server}]}`, `path` project-relative where it can be;
    /// `unavailable` when no server is running. Supersedes the previous
    /// query, as a picker retyping should. Never blocks.
    pub fn lsp_request_workspace_symbols(&self, project: ProjectId, query: &str) -> u64 {
        let id = self.lsp.next_request_id.fetch_add(1, Ordering::Relaxed) + 1;
        let pending = Arc::new(Pending {
            id,
            kind: RequestKind::WorkspaceSymbol,
            buffer: 0,
            row: 0,
            col_utf16: 0,
            buffer_version: 0,
            answer: Mutex::new((RequestState::Pending, 1, serde_json::Value::Null)),
            stored: Mutex::new(StoredWork::None),
        });
        {
            let mut requests = self.lsp.requests.lock().unwrap();
            requests.supersede(RequestKind::WorkspaceSymbol, id);
            requests.live.insert(id, pending.clone());
        }
        let handles: Vec<(&'static str, Arc<LanguageServer>)> = {
            let mut servers = self.lsp.servers.lock().unwrap();
            servers
                .iter_mut()
                .filter(|((id, _), slot)| *id == project && slot.state == ServerState::Running)
                .filter(|(_, slot)| {
                    slot.capabilities
                        .as_ref()
                        .is_some_and(|caps| caps.workspace_symbol_provider.is_some())
                })
                .filter_map(|(key, slot)| {
                    // Asking is activity, as it is for every other request.
                    slot.last_activity = Instant::now();
                    slot.handle.clone().map(|handle| (key.1, handle))
                })
                .collect()
        };
        if handles.is_empty() {
            pending.settle(RequestState::Unavailable, serde_json::Value::Null);
            return id;
        }
        let root = self.project_root(project);
        let query = query.to_owned();
        let requests = self.lsp.requests.clone();
        self.runtime().spawn(move |cx| {
            let task = cx.spawn(async move |_| {
                let (state, payload) = perform_workspace_symbols(handles, query, root).await;
                pending.settle(state, payload);
            });
            let mut requests = requests.lock().unwrap();
            if requests.live.contains_key(&id) {
                requests.tasks.insert(id, task);
            }
        });
        id
    }

    /// The trigger characters and optional features a buffer's server
    /// declared — empty for a buffer with no running server, which leaves
    /// the UI on its defaults. Reads a cache; never blocks.
    pub fn lsp_buffer_triggers(&self, buffer: BufferId) -> BufferTriggers {
        let Some((project, server)) = self
            .lsp
            .docs
            .lock()
            .unwrap()
            .get(&buffer)
            .map(|doc| (doc.project, doc.server))
        else {
            return BufferTriggers::default();
        };
        let servers = self.lsp.servers.lock().unwrap();
        let Some(capabilities) = servers
            .get(&(project, server))
            .and_then(|slot| slot.capabilities.as_deref())
        else {
            return BufferTriggers::default();
        };
        triggers_of(capabilities)
    }

    /// Stop a server and start it again — Zed's
    /// `editor::RestartLanguageServer` (`restart_language_servers_for_buffers`,
    /// lsp_store.rs). The documents it had are re-announced by the new
    /// instance's `didOpen`; its diagnostics stay until it republishes, as
    /// they do for Zed. False when the project has no such server. Never
    /// blocks: the old process's shutdown handshake runs on the runtime.
    pub fn lsp_restart_server(&self, project: ProjectId, name: &str) -> bool {
        let Some((key, dropped)) = self.take_slot(project, name) else {
            return false;
        };
        let server = dropped.server;
        self.forget_instance(key, dropped, /* rest */ false);
        log_line(&self.lsp.logs, key, "", "restarting");
        self.ensure_server(project, server);
        true
    }

    /// Restart every server this project has running.
    ///
    /// What a toolchain change needs: a server already started has the old
    /// interpreter's `PATH` and `VIRTUAL_ENV` baked into its process, and no
    /// notification tells it otherwise — Zed restarts them for the same
    /// reason when a toolchain is activated (`toolchain_store.rs`). Servers
    /// the user stopped by hand stay stopped, because their slots are not
    /// running slots.
    pub fn restart_all_language_servers(&self, project: ProjectId) {
        let names: Vec<String> = self
            .lsp_servers(project)
            .into_iter()
            .map(|status| status.name)
            .collect();
        for name in names {
            self.lsp_restart_server(project, &name);
        }
    }

    /// Stop a server and keep it stopped — Zed's `editor::StopLanguageServer`.
    /// The poll will not start it again: a stopped record stays in its slot,
    /// reporting `unavailable` with "stopped", until
    /// [`Engine::lsp_restart_server`] or the project closes. False when the
    /// project has no such server.
    pub fn lsp_stop_server(&self, project: ProjectId, name: &str) -> bool {
        let Some((key, dropped)) = self.take_slot(project, name) else {
            return false;
        };
        let server = dropped.server;
        self.forget_instance(key, dropped, /* rest */ true);
        self.lsp
            .servers
            .lock()
            .unwrap()
            .insert(key, Slot::stopped(project, server));
        log_line(&self.lsp.logs, key, "", "stopped by the user");
        self.lsp.store.bump(project);
        true
    }

    /// A server's log — see [`ServerLog`]. Reads a cache; never blocks;
    /// empty for a server that never started.
    pub fn lsp_server_logs(&self, project: ProjectId, name: &str) -> ServerLogSnapshot {
        let logs = self.lsp.logs.lock().unwrap();
        logs.iter()
            .find(|((id, server), _)| *id == project && *server == name)
            .map(|(_, log)| ServerLogSnapshot {
                version: log.version,
                lines: log.lines.iter().cloned().collect(),
            })
            .unwrap_or(ServerLogSnapshot {
                version: 0,
                lines: Vec::new(),
            })
    }

    /// Remove a project's slot for `name`, whatever state it is in.
    /// The log's counter alone — one integer, for a tab that polls it and
    /// reads the lines only when it moves. Zero for a server that never
    /// started.
    pub fn lsp_server_logs_version(&self, project: ProjectId, name: &str) -> u64 {
        let logs = self.lsp.logs.lock().unwrap();
        logs.iter()
            .find(|((id, server), _)| *id == project && *server == name)
            .map(|(_, log)| log.version)
            .unwrap_or(0)
    }

    fn take_slot(&self, project: ProjectId, name: &str) -> Option<((ProjectId, &'static str), Slot)> {
        let mut servers = self.lsp.servers.lock().unwrap();
        let key = servers
            .keys()
            .find(|(id, server)| *id == project && *server == name)
            .copied()?;
        let slot = servers.remove(&key)?;
        Some((key, slot))
    }

    /// Everything that must forget an instance the user took down: the
    /// documents go back to "not yet opened" so the next instance sends
    /// `didOpen`, its watchers die with it, and the handshake runs on the
    /// runtime. With `rest` the documents are also marked dormant and the
    /// tree-want rested, so nothing but a restart brings it back.
    fn forget_instance(&self, key: (ProjectId, &'static str), slot: Slot, rest: bool) {
        {
            let mut docs = self.lsp.docs.lock().unwrap();
            for doc in docs
                .values_mut()
                .filter(|doc| doc.project == key.0 && doc.server == key.1)
            {
                doc.opened = false;
                doc.dormant = rest;
            }
        }
        {
            let mut rested = self.lsp.rested.lock().unwrap();
            if rest {
                rested.insert(key);
            } else {
                rested.remove(&key);
            }
        }
        self.lsp.watchers.lock().unwrap().remove(&key);
        self.lsp.store.bump(key.0);
        if slot.holds_processes() {
            self.runtime().spawn(move |_| drop(slot));
        }
    }

    /// The diagnostics on a row, in the wire shape `textDocument/codeAction`
    /// wants back as context. Row-level rather than caret-exact, because a
    /// finger is not a caret: asking anywhere on the line means the line's
    /// problems.
    fn diagnostics_under(&self, buffer: BufferId, row: u32) -> Vec<lsp::Diagnostic> {
        let Some(path) = self.buffer_path(buffer) else {
            return Vec::new();
        };
        let files = self.lsp.store.files.lock().unwrap();
        let Some(file) = files.get(&path) else {
            return Vec::new();
        };
        file.rows
            .iter()
            .filter(|entry| entry.row <= row && row <= entry.end_row)
            .map(|entry| lsp::Diagnostic {
                range: Range::new(
                    Position::new(entry.row, entry.col_utf16),
                    Position::new(entry.end_row, entry.end_col_utf16),
                ),
                severity: Some(match entry.severity {
                    Severity::Error => lsp::DiagnosticSeverity::ERROR,
                    Severity::Warning => lsp::DiagnosticSeverity::WARNING,
                    Severity::Info => lsp::DiagnosticSeverity::INFORMATION,
                    Severity::Hint => lsp::DiagnosticSeverity::HINT,
                }),
                code: entry.code.clone().map(lsp::NumberOrString::String),
                source: entry.source.clone(),
                message: entry.message.clone(),
                ..Default::default()
            })
            .collect()
    }

    /// Land the edit a settled rename, formatting or code-action request is
    /// holding. Synchronous: open buffers take the edits through the normal
    /// edit path — `didChange`, undo history, version bumps and all — and
    /// files nobody has open are rewritten atomically on disk.
    ///
    /// The edit is *taken*: a second call for the same id reports "nothing to
    /// apply", which is what stops a double-tap applying a rename twice.
    pub fn lsp_apply_pending_edit(&self, request: u64) -> ApplyReceipt {
        let stored = {
            let requests = self.lsp.requests.lock().unwrap();
            let Some(pending) = requests.live.get(&request) else {
                return ApplyReceipt::refused("the request is gone".to_owned());
            };
            std::mem::replace(&mut *pending.stored.lock().unwrap(), StoredWork::None)
        };
        let edits = match stored {
            StoredWork::Edit(edit) => vec![edit],
            StoredWork::Edits(edits) => edits,
            _ => return ApplyReceipt::refused("nothing to apply".to_owned()),
        };
        // Several edits — one per code action — land one after the other,
        // each against the buffers as the one before left them. The
        // receipts are folded into one so the caller sees every file once.
        let mut receipt = ApplyReceipt {
            applied: true,
            files: Vec::new(),
            error: None,
        };
        for edit in edits {
            let files = match normalize_workspace_edit(edit) {
                Ok(files) => files,
                Err(error) => return ApplyReceipt::refused(error),
            };
            let applied = self.apply_file_edits(files);
            if applied.error.is_some() {
                return applied;
            }
            for file in applied.files {
                match receipt.files.iter_mut().find(|known| known.path == file.path) {
                    Some(known) => known.edits += file.edits,
                    None => receipt.files.push(file),
                }
            }
        }
        receipt
    }

    /// Land one normalized workspace edit; see [`Engine::lsp_apply_pending_edit`].
    fn apply_file_edits(&self, files: Vec<FileEdits>) -> ApplyReceipt {

        // Validate everything before touching anything: a rename applied to
        // three files and refused on the fourth is worse than one refused
        // whole.
        for file in &files {
            if let Some(buffer) = self.buffer_for_path(&file.path) {
                if let (Some(sent), Some(current)) = (
                    file.version,
                    self.lsp
                        .docs
                        .lock()
                        .unwrap()
                        .get(&buffer)
                        .map(|doc| doc.lsp_version),
                ) {
                    if sent != current {
                        return ApplyReceipt::refused(format!(
                            "{} changed while the server was thinking — try again",
                            file.path.display()
                        ));
                    }
                }
            } else if !file.path.exists() {
                return ApplyReceipt::refused(format!(
                    "{} is not there to edit",
                    file.path.display()
                ));
            }
        }

        let mut receipt = ApplyReceipt {
            applied: true,
            error: None,
            files: Vec::new(),
        };
        for file in files {
            let applied = match self.buffer_for_path(&file.path) {
                Some(buffer) => self
                    .apply_edits_to_buffer(buffer, &file.edits)
                    .map(|()| AppliedFile {
                        path: file.path.to_string_lossy().into_owned(),
                        buffer_id: Some(buffer),
                        edits: file.edits.len(),
                    }),
                None => std::fs::read_to_string(&file.path)
                    .map_err(|err| format!("{}: {err}", file.path.display()))
                    .and_then(|text| {
                        let text = apply_edits_to_text(&text, &file.edits);
                        crate::file::write_atomically_io(&file.path, &text)
                            .map_err(|err| format!("{}: {err}", file.path.display()))
                    })
                    .map(|()| AppliedFile {
                        path: file.path.to_string_lossy().into_owned(),
                        buffer_id: None,
                        edits: file.edits.len(),
                    }),
            };
            match applied {
                Ok(file) => receipt.files.push(file),
                Err(error) => {
                    // Stop rather than go on: the receipt says what landed,
                    // and the error says why the rest did not.
                    receipt.error = Some(error);
                    break;
                }
            }
        }
        receipt
    }

    /// The open-buffer half of the applier: offsets resolved against one
    /// snapshot, applied back to front through [`Engine::edit`], bracketed
    /// into one undo transaction the way a reload is.
    fn apply_edits_to_buffer(&self, buffer: BufferId, edits: &[lsp::TextEdit]) -> Result<(), String> {
        let mut resolved: Vec<(usize, usize, String)> = self
            .with_buffer(buffer, |state| {
                let rope = state.buffer.as_rope();
                let offset_of = |position: Position| {
                    let point = rope.clip_point_utf16(
                        rope::Unclipped(PointUtf16::new(position.line, position.character)),
                        text::Bias::Left,
                    );
                    rope.point_utf16_to_offset(point)
                };
                edits
                    .iter()
                    .map(|edit| {
                        let start = offset_of(edit.range.start);
                        let end = offset_of(edit.range.end).max(start);
                        (start, end, edit.new_text.clone())
                    })
                    .collect()
            })
            .map_err(|err| format!("{err:?}"))?;
        resolved.sort_by_key(|(start, end, _)| (*start, *end));

        self.finalize_buffer_history(buffer);
        for (start, end, text) in resolved.into_iter().rev() {
            self.edit(buffer, start, end, &text)
                .map_err(|err| format!("{err:?}"))?;
        }
        self.finalize_buffer_history(buffer);
        Ok(())
    }

    /// Close whatever undo transaction is open on a buffer — the bracketing
    /// a reload uses, for the same reason: an applied edit is a discrete
    /// event, and must not merge into the typing before or after it.
    fn finalize_buffer_history(&self, buffer: BufferId) {
        if let Ok(state) = self.buffer(buffer) {
            state.lock().unwrap().buffer.finalize_last_transaction();
        }
    }

    /// Generation counter for a request: 1 while it is in flight, 2 once it has
    /// settled, 0 for an id the engine has forgotten. Poll it like
    /// `project_search_version`.
    pub fn lsp_request_version(&self, id: u64) -> u64 {
        self.lsp
            .requests
            .lock()
            .unwrap()
            .live
            .get(&id)
            .map(|pending| pending.answer.lock().unwrap().1)
            .unwrap_or(0)
    }

    /// A request's answer. Never fails: a forgotten id reports itself
    /// cancelled with nothing in it.
    pub fn lsp_request_result(&self, id: u64) -> RequestResult {
        self.lsp
            .requests
            .lock()
            .unwrap()
            .live
            .get(&id)
            .map(|pending| pending.result())
            .unwrap_or_else(|| RequestResult::forgotten(id))
    }

    /// Stop a request and forget it — how a closed completion popup frees its
    /// slot, and how the server is told to stop indexing for an answer nobody
    /// will read. False if the id was already gone.
    pub fn lsp_cancel_request(&self, id: u64) -> bool {
        let mut requests = self.lsp.requests.lock().unwrap();
        requests.latest.retain(|_, live| *live != id);
        requests.retire(id)
    }

    // -----------------------------------------------------------------------
    // Hooks the rest of the engine calls
    // -----------------------------------------------------------------------

    /// A buffer with a file and a language has been opened. Starts the server
    /// for its language if this is the first such buffer in the project, and
    /// registers the document either way.
    ///
    /// Everything about it is best-effort: no project, no language, no
    /// userland, no server for the language — all of them return quietly.
    pub(crate) fn lsp_did_open(&self, buffer: BufferId) {
        let Some(path) = self.buffer_path(buffer) else {
            return;
        };
        let Some(grammar) = self.buffer_language(buffer) else {
            return;
        };
        let Some((server, language_id)) = server_for(grammar) else {
            return;
        };
        let Some(project) = self.project_for_path(&path) else {
            return;
        };
        // `enable_language_server: false`, globally or for this language,
        // globally or in the project: the document is never registered, so
        // nothing below — not even the tree's want — can start a server
        // for it (Zed: language_settings `enable_language_server`).
        if !self.language_server_enabled(project, grammar) {
            return;
        }
        let Ok(uri) = Uri::from_file_path(&path) else {
            log::debug!("lsp: {} is not a file URI", path.display());
            return;
        };
        // Opening a buffer of the language is exactly the activity that ends
        // a tree-want's rest — see [`Rested`].
        self.lsp.rested.lock().unwrap().remove(&(project, server.name));

        self.lsp.docs.lock().unwrap().insert(
            buffer,
            OpenDoc {
                project,
                server: server.name,
                path,
                uri,
                grammar,
                language_id,
                lsp_version: 1,
                opened: false,
                dormant: false,
            },
        );
        self.ensure_server(project, server);
        // A server that is already running gets the `didOpen` now; one still
        // starting gets it from its own start job, which flushes the same way
        // the moment `initialize` answers.
        if let Some(handle) = self.server_handle(project, server.name) {
            let docs = self.lsp.docs.clone();
            let buffers = self.buffers.clone();
            let store = self.lsp.store.clone();
            self.runtime().spawn(move |_| {
                open_pending_docs(&handle, project, server.name, &docs, &buffers, &store);
            });
        }
    }

    /// A buffer has changed. Costs one relaxed atomic load when no server has
    /// ever run, which is the case this must not slow down.
    pub(crate) fn lsp_did_change(&self, buffer: BufferId, change: TextChange) {
        if !self.lsp.live.load(Ordering::Relaxed) {
            return;
        }
        #[cfg(test)]
        {
            // The one seam the tests need in the real path: a host with no
            // server can still prove that `Engine::edit` measured the range in
            // UTF-16 units on the text as it was *before* the edit, which is
            // the only place that information exists.
            *self.lsp.last_change.lock().unwrap() = Some(change.clone());
        }
        let (project, server, uri, path, version) = {
            let mut docs = self.lsp.docs.lock().unwrap();
            let Some(doc) = docs.get_mut(&buffer) else {
                return;
            };
            doc.lsp_version += 1;
            if !doc.opened {
                // The server has not been told the document exists yet. The
                // `didOpen` that eventually goes out carries the current text,
                // so the change is already in it — only the version has to keep
                // moving, and it just did.
                let dormant = doc.dormant.then_some((doc.project, doc.grammar));
                drop(docs);
                // Typing in a file whose server we stopped is the clearest
                // evidence there is that the user wants it back.
                if let Some((project, grammar)) = dormant {
                    self.wake_server(project, grammar);
                }
                return;
            }
            (
                doc.project,
                doc.server,
                doc.uri.clone(),
                doc.path.clone(),
                doc.lsp_version,
            )
        };
        let sync = self
            .sync_kind(project, server)
            .unwrap_or(TextDocumentSyncKind::INCREMENTAL);
        self.lsp
            .store
            .note_sent(&path, version, change.buffer_version);

        let Some(content_changes) = content_changes(change, sync) else {
            return;
        };

        let Some(handle) = self.server_handle(project, server) else {
            return;
        };
        self.runtime().spawn(move |_| {
            handle
                .notify::<lsp::notification::DidChangeTextDocument>(DidChangeTextDocumentParams {
                    text_document: VersionedTextDocumentIdentifier::new(uri, version),
                    content_changes,
                })
                .ok();
        });
    }

    /// A buffer has been written to disk. Servers that asked to hear about it
    /// use it to re-run the slow checks — rust-analyzer's `cargo check` runs
    /// here and nowhere else, which is where most of its diagnostics come from.
    pub(crate) fn lsp_did_save(&self, buffer: BufferId) {
        if !self.lsp.live.load(Ordering::Relaxed) {
            return;
        }
        let Some((project, server, uri)) = self
            .lsp
            .docs
            .lock()
            .unwrap()
            .get(&buffer)
            .filter(|doc| doc.opened)
            .map(|doc| (doc.project, doc.server, doc.uri.clone()))
        else {
            // A save is the one event a stopped server most wants: it is where
            // rust-analyzer's `cargo check` — most of its diagnostics — runs.
            if let Some((project, grammar)) = self.dormant_server(buffer) {
                self.wake_server(project, grammar);
            }
            return;
        };
        if !self.wants_save(project, server) {
            return;
        }
        let Some(handle) = self.server_handle(project, server) else {
            return;
        };
        self.runtime().spawn(move |_| {
            handle
                .notify::<lsp::notification::DidSaveTextDocument>(DidSaveTextDocumentParams {
                    text_document: TextDocumentIdentifier::new(uri),
                    text: None,
                })
                .ok();
        });
    }

    /// A buffer has been closed. The server is told; what it has already said
    /// about the file stays, because for a workspace-wide analysis it is still
    /// true — see [`DiagnosticStore::undate`].
    pub(crate) fn lsp_did_close(&self, buffer: BufferId) {
        // Deliberately not gated on `live`: a document registered before any
        // server started still has to be forgotten, or a `play` build would
        // accumulate one entry per file the user ever opened.
        let Some(doc) = self.lsp.docs.lock().unwrap().remove(&buffer) else {
            return;
        };
        self.lsp.store.undate(&doc.path);
        // Requests against a buffer that is gone can never be read again.
        let mut requests = self.lsp.requests.lock().unwrap();
        let stale: Vec<u64> = requests
            .live
            .iter()
            .filter(|(_, pending)| pending.buffer == buffer)
            .map(|(id, _)| *id)
            .collect();
        for id in stale {
            requests.latest.retain(|_, live| *live != id);
            requests.retire(id);
        }
        drop(requests);

        if doc.opened {
            let handle = self.server_handle(doc.project, doc.server);
            if let Some(server) = handle {
                let uri = doc.uri.clone();
                self.runtime().spawn(move |_| server.unregister_buffer(uri));
            }
        }
    }

    /// A project is closing: stop its servers and forget everything they said.
    ///
    /// Dropping the `Arc<LanguageServer>` is the shutdown — Zed's `Drop`
    /// (lsp.rs:1755) spawns the `shutdown`/`exit` handshake and only then kills
    /// the process, so the server exits politely and proot follows it out.
    pub(crate) fn lsp_close_project(&self, project: ProjectId) {
        let dropped: Vec<Slot> = {
            let mut servers = self.lsp.servers.lock().unwrap();
            let keys: Vec<(ProjectId, &'static str)> = servers
                .keys()
                .filter(|(id, _)| *id == project)
                .copied()
                .collect();
            keys.into_iter()
                .filter_map(|key| servers.remove(&key))
                .collect()
        };
        self.lsp
            .docs
            .lock()
            .unwrap()
            .retain(|_, doc| doc.project != project);
        self.lsp.store.forget_project(project);
        self.lsp
            .watchers
            .lock()
            .unwrap()
            .retain(|(id, _), _| *id != project);
        self.lsp.wants.lock().unwrap().remove(&project);
        self.lsp
            .rested
            .lock()
            .unwrap()
            .retain(|(id, _)| *id != project);
        self.lsp
            .logs
            .lock()
            .unwrap()
            .retain(|(id, _), _| *id != project);
        if dropped.is_empty() {
            return;
        }
        // The handshake needs the runtime's executor, so the last references
        // are released there rather than on the caller's thread.
        self.runtime().spawn(move |_| drop(dropped));
    }

    /// Whether anything at all should be told about edits. Lets
    /// [`Engine::edit`] skip building a [`TextChange`] entirely.
    pub(crate) fn lsp_is_live(&self) -> bool {
        self.lsp.live.load(Ordering::Relaxed)
    }

    /// The pieces the worktree's subscription needs to forward disk changes
    /// to servers — cloned, so the subscription owns no borrow on the engine.
    pub(crate) fn lsp_watch_handles(&self) -> (Slots, Watchers) {
        (self.lsp.servers.clone(), self.lsp.watchers.clone())
    }

    /// Whether some live server negotiated whole-document sync, and therefore
    /// whether [`Engine::edit`] has to pay for `Buffer::text()`.
    pub(crate) fn lsp_wants_full_text(&self) -> bool {
        self.lsp.wants_full_text.load(Ordering::Relaxed)
    }

    // -----------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------

    /// Whether the settings let a server start for `grammar` in `project` —
    /// the resolved `enable_language_server`, which the user's file, its
    /// `languages` entry, and the project's `.zed/settings.json` all have a
    /// say in.
    fn language_server_enabled(&self, project: ProjectId, grammar: &str) -> bool {
        self.language_settings(Some(project), Some(grammar))
            .enable_language_server
    }

    /// The project one of whose folders contains `path`, deepest root first —
    /// a project opened inside another one owns its files, and so does a
    /// folder added inside another folder of the same project.
    pub(crate) fn project_for_path(&self, path: &Path) -> Option<ProjectId> {
        let projects = self.projects.lock().unwrap();
        let mut best: Option<(ProjectId, usize)> = None;
        for (id, state) in projects.iter() {
            let deepest = state
                .lock()
                .unwrap()
                .worktree_for_path(path)
                .map(|tree| tree.root.components().count());
            if let Some(depth) = deepest
                && best.is_none_or(|(_, best)| depth > best)
            {
                best = Some((*id, depth));
            }
        }
        best.map(|(id, _)| id)
    }

    fn sync_kind(&self, project: ProjectId, server: &'static str) -> Option<TextDocumentSyncKind> {
        self.lsp
            .servers
            .lock()
            .unwrap()
            .get(&(project, server))
            .map(|slot| slot.sync)
    }

    fn wants_save(&self, project: ProjectId, server: &'static str) -> bool {
        self.lsp
            .servers
            .lock()
            .unwrap()
            .get(&(project, server))
            .is_some_and(|slot| slot.wants_save)
    }

    /// The live server for a document, **and the note that we are about to talk
    /// to it**.
    ///
    /// Every caller of this is a caller that is about to send something —
    /// `didOpen`, `didChange`, `didSave`, `didClose`, a request — so taking a
    /// handle is exactly the event [`Slot::last_activity`] means to record, and
    /// recording it here rather than at four call sites is what keeps the fifth
    /// one from forgetting. It costs one mutex the caller was taking anyway.
    fn server_handle(
        &self,
        project: ProjectId,
        server: &'static str,
    ) -> Option<Arc<LanguageServer>> {
        let mut servers = self.lsp.servers.lock().unwrap();
        let slot = servers.get_mut(&(project, server))?;
        slot.last_activity = Instant::now();
        slot.handle.clone()
    }

    /// Start the server for `project` unless it is already there — or say why
    /// it cannot be started, which on a phone is a thing that happens.
    fn ensure_server(&self, project: ProjectId, server: Server) {
        let Some(userland) = self.userland() else {
            return;
        };
        if !userland.is_installed() {
            return;
        }
        let Some(root) = self.project_root(project) else {
            return;
        };

        // The budget, in three steps: ask; if the answer is no, stop what is
        // not earning its processes and ask once more; if it is still no,
        // record the refusal where the user can read it.
        let cap = max_running_servers();
        let mut claimed = claim(&mut self.lsp.servers.lock().unwrap(), project, server, cap);
        if claimed == Claim::Full && self.sweep_idle_servers(true) > 0 {
            claimed = claim(&mut self.lsp.servers.lock().unwrap(), project, server, cap);
        }
        match claimed {
            Claim::Taken => return,
            Claim::Full => {
                self.refuse_server(project, server);
                return;
            }
            // The slot is ours, and it is `starting` from this moment — so a
            // second caller arriving during the spawn below finds it taken
            // rather than starting a second rust-analyzer.
            Claim::Room => {}
        }
        // Only now is anything actually going to talk to a server, so only now
        // does `Engine::edit` need to do any work at all. The `play` flavour
        // never reaches this line.
        self.lsp.live.store(true, Ordering::Relaxed);
        self.ensure_sweeper();
        self.lsp.store.bump(project);

        let id = LanguageServerId(self.lsp.next_server_id.fetch_add(1, Ordering::Relaxed) as usize);
        // Read once, here, and carried into the start job: the user's
        // `lsp.<server>` entry with the project's on top. Changing it means
        // restarting the server, as it does in Zed.
        let lsp_settings = self.lsp_settings(Some(project), server.name);
        let toolchain = self.toolchain_env(project);
        let binary = server_binary(
            &userland,
            &server,
            &root,
            lsp_settings.binary.as_ref(),
            &toolchain,
        );
        log::info!(
            "lsp: starting {} for project {project} in {}",
            server.name,
            root.display()
        );

        // A previous incarnation's watcher registrations died with it; the new
        // server registers its own, under ids of its own choosing.
        self.lsp.watchers.lock().unwrap().remove(&(project, server.name));

        log_line(
            &self.lsp.logs,
            (project, server.name),
            "",
            &format!("starting in {}", root.display()),
        );
        let started = StartRequest {
            project,
            server,
            id,
            binary,
            root,
            store: self.lsp.store.clone(),
            buffers: self.buffers.clone(),
            slots: self.lsp.servers.clone(),
            docs: self.lsp.docs.clone(),
            watchers: self.lsp.watchers.clone(),
            wants_full_text: self.lsp.wants_full_text.clone(),
            initialization_options: lsp_settings.initialization_options,
            configuration: lsp_settings.settings,
            logs: self.lsp.logs.clone(),
        };
        self.runtime().spawn(move |cx| {
            cx.spawn(async move |cx| start_server(started, cx).await)
                .detach();
        });
    }

    // -----------------------------------------------------------------------
    // The process budget: refusing, sweeping, waking
    // -----------------------------------------------------------------------

    /// Record that the cap turned a server away, so the status bar says so.
    ///
    /// The slot holds no process and never will; it is a message. It also stops
    /// the next poll from asking again — [`Engine::retry_capped_servers`] is
    /// what removes it, and only once there is somewhere for the server to go.
    fn refuse_server(&self, project: ProjectId, server: Server) {
        {
            let mut servers = self.lsp.servers.lock().unwrap();
            if servers.contains_key(&(project, server.name)) {
                return;
            }
            log::info!(
                "lsp: not starting {} for project {project}: {} already running (cap {})",
                server.name,
                running_servers(&servers),
                max_running_servers(),
            );
            servers.insert((project, server.name), Slot::refused(project, server));
        }
        self.lsp.store.bump(project);
    }

    /// Give the servers this project had refused another go, now that the sweep
    /// may have freed something.
    ///
    /// Only as many as there is room for, and only from the polled project.
    /// Freeing more than that would refuse them again on the same pass, and
    /// each refusal bumps the version counter the UI polls — a status bar that
    /// redraws twice a second is how a budget becomes a flicker.
    fn retry_capped_servers(&self, project: ProjectId) {
        let retried: Vec<(ProjectId, &'static str)> = {
            let mut servers = self.lsp.servers.lock().unwrap();
            let room = max_running_servers().saturating_sub(running_servers(&servers));
            if room == 0 {
                return;
            }
            let keys: Vec<(ProjectId, &'static str)> = servers
                .iter()
                .filter(|((id, _), slot)| *id == project && slot.capped)
                .map(|(key, _)| *key)
                .take(room)
                .collect();
            for key in &keys {
                servers.remove(key);
            }
            keys
        };
        if !retried.is_empty() {
            // Removing the refusal is itself news: the status bar's sentence
            // goes away here, and the server appears as `starting` a moment
            // later when the caller gets to it.
            self.lsp.store.bump(project);
        }
    }

    /// Stop the servers that are no longer earning their processes; answer how
    /// many were stopped.
    ///
    /// The handshake is handed to the runtime rather than run here, both
    /// because that is where `lsp_close_project` already sends it and because
    /// this is called from `lsp_version` — a poll on the UI's thread, which
    /// must not wait for a server to say goodbye. The consequence, stated
    /// plainly: for a second or two after a pressure sweep the stopped server's
    /// processes and the started one's overlap. That is what the spare in
    /// [`RESERVED_PROCESSES`] absorbs.
    fn sweep_idle_servers(&self, under_pressure: bool) -> usize {
        let stopped = sweep(
            &self.lsp.servers,
            &self.lsp.docs,
            &self.lsp.store,
            &self.lsp.wants,
            &self.lsp.rested,
            Instant::now(),
            under_pressure,
        );
        let count = stopped.len();
        if count > 0 {
            self.runtime().spawn(move |_| drop(stopped));
        }
        count
    }

    /// Start the timer that sweeps while nothing is polling us.
    ///
    /// One task on the runtime's background executor, not a thread: the
    /// foreground sweep (`lsp_version` → [`Engine::start_pending_servers`])
    /// covers the app the user is looking at, and this covers the app they have
    /// switched away from — which is the case that matters, because a
    /// backgrounded app is exactly what Android's phantom-process killer goes
    /// looking through. Started once, from the first server that starts, and
    /// runs for the life of the process.
    fn ensure_sweeper(&self) {
        if self.lsp.sweeping.swap(true, Ordering::Relaxed) {
            return;
        }
        let slots = self.lsp.servers.clone();
        let docs = self.lsp.docs.clone();
        let store = self.lsp.store.clone();
        let wants = self.lsp.wants.clone();
        let rested = self.lsp.rested.clone();
        self.runtime().spawn(move |cx| {
            let executor = cx.background_executor().clone();
            let ticker = executor.clone();
            executor
                .spawn(async move {
                    loop {
                        ticker.timer(SWEEP_INTERVAL).await;
                        // Dropped here, on a background thread: the handshake
                        // wants an executor, and this *is* one.
                        drop(sweep(
                            &slots,
                            &docs,
                            &store,
                            &wants,
                            &rested,
                            Instant::now(),
                            false,
                        ));
                    }
                })
                .detach();
        });
    }

    /// The user came back to a document whose server the sweep stopped.
    ///
    /// Clearing dormancy is what lets the poll consider it again; starting it
    /// here is what makes the return immediate rather than a poll away. If the
    /// cap refuses it now, that refusal is what the status bar shows — the
    /// document is awake either way, and asking again is free.
    fn wake_server(&self, project: ProjectId, grammar: &'static str) {
        let Some((server, _)) = server_for(grammar) else {
            return;
        };
        {
            let mut docs = self.lsp.docs.lock().unwrap();
            for doc in docs
                .values_mut()
                .filter(|doc| doc.project == project && doc.server == server.name)
            {
                doc.dormant = false;
            }
        }
        // A wake ends the tree-want's rest too, for the same reason it ends
        // the documents' dormancy: this is the activity the sweep waits for.
        self.lsp.rested.lock().unwrap().remove(&(project, server.name));
        log::info!("lsp: waking {} for project {project}", server.name);
        self.ensure_server(project, server);
    }

    /// The `(project, server)` of a buffer's document when its server has been
    /// stopped for idleness — and nothing when it has not, which is the normal
    /// case and costs one field read.
    fn dormant_server(&self, buffer: BufferId) -> Option<(ProjectId, &'static str)> {
        let docs = self.lsp.docs.lock().unwrap();
        let doc = docs.get(&buffer)?;
        doc.dormant.then_some((doc.project, doc.grammar))
    }

    /// Servers the project calls for and does not have yet: the languages of
    /// its open documents, and the languages of its *tree* — opening a folder
    /// of `.rs` files is what starts rust-analyzer, tabs or no tabs. Also
    /// how the `apt install` that happened while the editor was running takes
    /// effect: the poll keeps asking, and one day the binary is there.
    fn start_pending_servers(&self, project: ProjectId) {
        if self.userland().is_none() {
            return;
        }
        self.adopt_open_buffers(project);
        // Free before spending: a server whose tabs all closed is stopped here,
        // and a server that was refused when the budget was full is given the
        // room that just appeared.
        self.sweep_idle_servers(false);
        self.retry_capped_servers(project);
        let known: Vec<&'static str> = self
            .lsp
            .servers
            .lock()
            .unwrap()
            .keys()
            .filter(|(id, _)| *id == project)
            .map(|(_, name)| *name)
            .collect();
        let mut wanted: Vec<Server> = self
            .lsp
            .docs
            .lock()
            .unwrap()
            .values()
            // Dormant is deliberate, not pending: its server was stopped by the
            // sweep *while this document was open*, so starting it again from
            // the poll would undo the sweep on the next frame. Only activity
            // wakes it — see `Engine::wake_server`.
            .filter(|doc| doc.project == project && !doc.dormant && !known.contains(&doc.server))
            .filter_map(|doc| server_for(doc.grammar).map(|(server, _)| server))
            .collect();
        // The tree's wants, minus what the sweep put to rest — rested is the
        // tree's own dormancy, and only activity clears it. An open document
        // outranks rested: it is on the docs list above, and `lsp_did_open`
        // already cleared the flag when it arrived.
        {
            let rested = self.lsp.rested.lock().unwrap();
            for server in self.project_tree_servers(project) {
                if known.contains(&server.name) || rested.contains(&(project, server.name)) {
                    continue;
                }
                if !wanted.iter().any(|wanted| wanted.name == server.name) {
                    wanted.push(server);
                }
            }
        }
        for server in wanted {
            self.ensure_server(project, server);
        }
    }

    /// The servers this project's tree calls for, from the mirrored worktree:
    /// one walk over the file names when the mirror has moved, the cached
    /// answer otherwise.
    ///
    /// While the initial scan is still running the walk is capped — the
    /// mirror grows and bumps its version batch by batch, and rewalking an
    /// unbounded prefix on every bump would make opening a big repository
    /// O(files²). The cap is plenty to catch a repository's languages early;
    /// the complete walk happens once, when the scan finishes, and is what
    /// gets cached.
    fn project_tree_servers(&self, project: ProjectId) -> Vec<Server> {
        const EARLY_SCAN_FILES: usize = 2000;
        let tree_version = self.project_version(project);
        if tree_version == 0 {
            return Vec::new();
        }
        // The answer depends on the settings as much as on the tree: a
        // `languages.Go.enable_language_server: false` written after the
        // scan must retire gopls from the want list, so the cache is keyed
        // by both the tree's version and the settings' generation.
        let version = (
            tree_version,
            self.settings_generation() + self.project_settings_version(project),
        );
        if let Some((cached, servers)) = self.lsp.wants.lock().unwrap().get(&project) {
            if *cached == version {
                return servers.clone();
            }
        }
        let Some(snapshot) = self.project_snapshot(project) else {
            return Vec::new();
        };
        let complete = self.project_scan_complete(project);
        // Compiled once for the whole walk: `file_types` reads settings.json
        // and builds a glob set, and a tree has thousands of files.
        let file_types = self.file_types(Some(project));
        let mut servers: Vec<Server> = Vec::new();
        for (walked, entry) in snapshot.files(false, 0).enumerate() {
            if !complete && walked >= EARLY_SCAN_FILES {
                break;
            }
            let Some(grammar) = file_types.language_for_path(entry.path.as_unix_str()) else {
                continue;
            };
            let Some((server, _)) = server_for(grammar) else {
                continue;
            };
            if servers.iter().any(|known| known.name == server.name) {
                continue;
            }
            // Asked once per grammar with a server, not per file: the
            // resolution reads settings.json, and a tree has many files.
            if !self.language_server_enabled(project, grammar) {
                continue;
            }
            servers.push(server);
        }
        if complete {
            self.lsp
                .wants
                .lock()
                .unwrap()
                .insert(project, (version, servers.clone()));
        }
        servers
    }

    /// Register file-backed buffers that this project did not have when they
    /// were opened.
    ///
    /// [`Engine::lsp_did_open`] runs at `open_file`, and a file opened *before*
    /// its enclosing project — a recent-files entry restored at launch, a
    /// project opened around a file already on screen — has no project to
    /// belong to at that moment and is skipped. This is the other half of
    /// "polling is what drives it": one `starts_with` per open tab per poll,
    /// against a list that is the number of tabs long.
    fn adopt_open_buffers(&self, project: ProjectId) {
        let Some(root) = self.project_root(project) else {
            return;
        };
        let known: Vec<BufferId> = self.lsp.docs.lock().unwrap().keys().copied().collect();
        let candidates: Vec<BufferId> = self
            .buffers
            .read()
            .unwrap()
            .keys()
            .copied()
            .filter(|buffer| !known.contains(buffer))
            .collect();
        for buffer in candidates {
            if self
                .buffer_path(buffer)
                .is_some_and(|path| path.starts_with(&root))
            {
                self.lsp_did_open(buffer);
            }
        }
    }

    fn start_request(&self, kind: RequestKind, buffer: BufferId, row: u32, col_utf16: u32) -> u64 {
        self.start_request_with(kind, buffer, row, col_utf16, RequestArgs::None)
    }

    fn start_request_with(
        &self,
        kind: RequestKind,
        buffer: BufferId,
        row: u32,
        col_utf16: u32,
        args: RequestArgs,
    ) -> u64 {
        let id = self.lsp.next_request_id.fetch_add(1, Ordering::Relaxed) + 1;
        let buffer_version = self.version(buffer).unwrap_or(0);
        let pending = Arc::new(Pending {
            id,
            kind,
            buffer,
            row,
            col_utf16,
            buffer_version,
            answer: Mutex::new((RequestState::Pending, 1, serde_json::Value::Null)),
            stored: Mutex::new(StoredWork::None),
        });
        {
            let mut requests = self.lsp.requests.lock().unwrap();
            requests.supersede(kind, id);
            requests.live.insert(id, pending.clone());
        }

        let doc = self
            .lsp
            .docs
            .lock()
            .unwrap()
            .get(&buffer)
            .filter(|doc| doc.opened)
            .map(|doc| (doc.project, doc.server, doc.uri.clone()));
        let Some((project, server, uri)) = doc else {
            // Nothing can answer *this* request — but if the reason is a server
            // the sweep stopped, asking is exactly the evidence that starts it
            // again, and the next request will have somewhere to go.
            if let Some((project, grammar)) = self.dormant_server(buffer) {
                self.wake_server(project, grammar);
            }
            pending.settle(RequestState::Unavailable, serde_json::Value::Null);
            return id;
        };
        let Some(handle) = self.server_handle(project, server) else {
            pending.settle(RequestState::Unavailable, serde_json::Value::Null);
            return id;
        };

        let requests = self.lsp.requests.clone();
        self.runtime().spawn(move |cx| {
            let task = cx.spawn(async move |_| {
                let (state, payload, stored) =
                    perform(&handle, kind, uri, row, col_utf16, args).await;
                // Stored before settled, so a reader that has observed the
                // settle can trust what it finds behind it.
                *pending.stored.lock().unwrap() = stored;
                pending.settle(state, payload);
            });
            // Superseded before the runtime got to it: dropping the task here
            // is what tells the server to forget the request.
            let mut requests = requests.lock().unwrap();
            if requests.live.contains_key(&id) {
                requests.tasks.insert(id, task);
            }
        });
        id
    }
}

/// A path relative to a project root, `/`-separated, or the absolute path when
/// it lies outside — which a header file pulled in from `/usr/include` will.
fn relative_path(root: Option<&Path>, path: &Path) -> String {
    match root.and_then(|root| path.strip_prefix(root).ok()) {
        Some(relative) => relative
            .components()
            .map(|component| component.as_os_str().to_string_lossy())
            .collect::<Vec<_>>()
            .join("/"),
        // Outside the project — a header from /usr/include, a dependency's
        // source — keeps its absolute name. A relative one would point nowhere.
        None => path.to_string_lossy().into_owned(),
    }
}

/// Everything `start_server` needs, gathered on the caller's thread so the
/// runtime job owns no borrows.
struct StartRequest {
    project: ProjectId,
    server: Server,
    id: LanguageServerId,
    binary: LanguageServerBinary,
    root: PathBuf,
    store: Arc<DiagnosticStore>,
    buffers: Buffers,
    slots: Slots,
    docs: Docs,
    watchers: Watchers,
    wants_full_text: Arc<AtomicBool>,
    /// `lsp.<server>.initialization_options`, sent once in `initialize`.
    initialization_options: Option<serde_json::Value>,
    /// `lsp.<server>.settings`, the answer to every `workspace/configuration`.
    configuration: Option<serde_json::Value>,
    /// The server's log ring, shared with the engine's readers.
    logs: Logs,
}

/// The answer to one `workspace/configuration` item out of the user's
/// `lsp.<server>.settings` — Zed's handler (project/src/lsp_store.rs, the
/// `WorkspaceConfiguration` request): the whole object when the server
/// names no section, the section's value when it names one. A dotted
/// section (`rust-analyzer.check`) is walked key by key when no key of that
/// exact name exists, which is how a server that asks for sub-sections gets
/// them out of the nested object Zed's docs tell people to write.
fn configuration_section(settings: &serde_json::Value, section: Option<&str>) -> serde_json::Value {
    let Some(section) = section.filter(|section| !section.is_empty()) else {
        return settings.clone();
    };
    if let Some(value) = settings.get(section) {
        return value.clone();
    }
    let mut current = settings;
    for key in section.split('.') {
        match current.get(key) {
            Some(value) => current = value,
            None => return serde_json::Value::Null,
        }
    }
    current.clone()
}

async fn start_server(request: StartRequest, cx: &mut AsyncApp) {
    let StartRequest {
        project,
        server,
        id,
        binary,
        root,
        store,
        buffers,
        slots,
        docs,
        watchers,
        wants_full_text,
        initialization_options,
        configuration,
        logs,
    } = request;
    let key = (project, server.name);

    let fail = |message: String| {
        log::info!("lsp: {} unavailable: {message}", server.name);
        log_line(&logs, key, "", &format!("unavailable: {message}"));
        if let Some(slot) = slots.lock().unwrap().get_mut(&(project, server.name)) {
            slot.state = ServerState::Unavailable;
            slot.error = Some(message);
        }
        store.bump(project);
    };

    // Zed keeps the last of stderr for its language-server log; we keep it for
    // exactly one purpose, which is to be able to say *why* a server is
    // unavailable rather than only that it is.
    let stderr = Arc::new(parking_lot::Mutex::new(Some(String::new())));
    let server_handle = match LanguageServer::new(
        stderr.clone(),
        id,
        LanguageServerName(server.name.into()),
        binary,
        &root,
        None,
        None,
        cx,
    ) {
        Ok(handle) => handle,
        Err(err) => {
            // proot itself is missing, or the rootfs went away. Not the same
            // as the server not being installed, which shows up as an exit
            // below, but the user cannot act differently on either.
            fail(format!("{err:#}"));
            return;
        }
    };

    // Registered before `initialize` consumes the server: a server that
    // publishes diagnostics the instant it is initialized — clangd does — would
    // otherwise have them logged as unhandled and thrown away.
    let subscription = {
        let store = store.clone();
        let slots = slots.clone();
        server_handle.on_notification::<lsp::notification::PublishDiagnostics, _>(
            move |params: PublishDiagnosticsParams, _cx: &mut AsyncApp| {
                // The only traffic that arrives *from* a server without us
                // asking, and therefore the only proof of life the idle sweep
                // would otherwise miss: a rust-analyzer chewing through a
                // workspace check is working, not idle. Taken before the store,
                // which is the module's lock order (servers, docs, store).
                if let Some(slot) = slots.lock().unwrap().get_mut(&(project, server.name)) {
                    slot.last_activity = Instant::now();
                }
                let Ok(path) = params.uri.to_file_path() else {
                    return;
                };
                // Buffers hold canonical paths (see `Engine::open_file`), and
                // on Android /data/user/0/<pkg> is a symlink to
                // /data/data/<pkg> — so a server's spelling of a path and ours
                // would otherwise never match.
                let path = std::fs::canonicalize(&path).unwrap_or(path);
                store.publish(project, path, params);
            },
        )
    };

    // Servers ask things back, and a request with no handler is answered with
    // a "method not found" *error* — which degrades exactly the
    // workspace-wide behaviour the server is for. `client/registerCapability`
    // is honoured for what matters: watched-files registrations land in the
    // watcher table and the worktree's subscription feeds them (see
    // [`notify_watched_files`]); everything else a server dynamically
    // registers changes nothing for a client that consults capabilities per
    // request. `workspace/configuration` is answered out of the user's
    // `lsp.<server>.settings`, section by section — and, with none written,
    // with the emptiest legal reply, which a server reads as "use your
    // defaults". Registered before `initialize` for the diagnostics
    // handler's reason.
    let acknowledgements = vec![
        {
            let watchers = watchers.clone();
            server_handle.on_request::<lsp::request::RegisterCapability, _, _>(
                move |params: lsp::RegistrationParams, _cx: &mut AsyncApp| {
                    register_watchers(&watchers, project, server.name, &params);
                    async move { anyhow::Ok(()) }
                },
            )
        },
        {
            let watchers = watchers.clone();
            server_handle.on_request::<lsp::request::UnregisterCapability, _, _>(
                move |params: lsp::UnregistrationParams, _cx: &mut AsyncApp| {
                    unregister_watchers(&watchers, project, server.name, &params);
                    async move { anyhow::Ok(()) }
                },
            )
        },
        {
            let configuration = Arc::new(configuration.unwrap_or(serde_json::Value::Null));
            server_handle.on_request::<lsp::request::WorkspaceConfiguration, _, _>(
                move |params: lsp::ConfigurationParams, _cx: &mut AsyncApp| {
                    let configuration = configuration.clone();
                    async move {
                        anyhow::Ok(
                            params
                                .items
                                .iter()
                                .map(|item| {
                                    configuration_section(&configuration, item.section.as_deref())
                                })
                                .collect(),
                        )
                    }
                },
            )
        },
        server_handle.on_request::<lsp::request::WorkDoneProgressCreate, _, _>(
            |_params, _cx: &mut AsyncApp| async move { anyhow::Ok(()) },
        ),
        // What the server *says* — its own errors above all. gopls explaining
        // why `go list` failed arrives here and nowhere else, and dropping it
        // is how a broken toolchain becomes an unexplained silence. Into the
        // log, where `adb logcat` can read it back.
        {
            let logs = logs.clone();
            server_handle.on_notification::<lsp::notification::ShowMessage, _>(
                move |params: lsp::ShowMessageParams, _cx: &mut AsyncApp| {
                    log::warn!("lsp: {} says: {}", server.name, params.message);
                    log_line(&logs, key, "[message] ", &params.message);
                },
            )
        },
        {
            let logs = logs.clone();
            server_handle.on_notification::<lsp::notification::LogMessage, _>(
                move |params: lsp::LogMessageParams, _cx: &mut AsyncApp| {
                    if params.typ == lsp::MessageType::ERROR {
                        log::warn!("lsp: {} logged: {}", server.name, params.message);
                    } else {
                        log::debug!("lsp: {} logged: {}", server.name, params.message);
                    }
                    log_line(&logs, key, "[log] ", &params.message);
                },
            )
        },
        // The server's own edits, pushed rather than answered: the result
        // of a command a code action ran (`workspace/executeCommand`), or
        // of something the server decided on its own. Zed applies them on
        // arrival and remembers them per server so the action that caused
        // them can be told what landed (lsp_store.rs:1084, 2287); here
        // they wait in the slot's inbox for that action's request to drain,
        // and are applied through the one applier every other edit takes —
        // so the UI's receipt names every buffer it must resync. Answered
        // `applied: true`: the edit is accepted, and what could go wrong
        // (a version mismatch) is reported to the user, not to the server.
        {
            let slots = slots.clone();
            let logs = logs.clone();
            server_handle.on_request::<lsp::request::ApplyWorkspaceEdit, _, _>(
                move |params: lsp::ApplyWorkspaceEditParams, _cx: &mut AsyncApp| {
                    let inbox = slots
                        .lock()
                        .unwrap()
                        .get(&key)
                        .map(|slot| slot.applied_edits.clone());
                    if let Some(inbox) = inbox {
                        inbox.lock().unwrap().push(params.edit);
                    }
                    log_line(
                        &logs,
                        key,
                        "",
                        &format!(
                            "workspace/applyEdit{}",
                            params
                                .label
                                .map(|label| format!(" ({label})"))
                                .unwrap_or_default()
                        ),
                    );
                    async move {
                        anyhow::Ok(lsp::ApplyWorkspaceEditResponse {
                            applied: true,
                            failure_reason: None,
                            failed_change: None,
                        })
                    }
                },
            )
        },
        // The RPC trace and stderr, into the ring — Zed's log view's
        // "RPC messages" and "Server logs" tabs, in one list.
        {
            let logs = logs.clone();
            server_handle.on_io(move |kind, message| {
                let prefix = match kind {
                    lsp::IoKind::StdIn => "→ ",
                    lsp::IoKind::StdOut => "← ",
                    lsp::IoKind::StdErr => "[stderr] ",
                };
                log_line(&logs, key, prefix, message);
            })
        },
        // …and the reports on the tokens the request above created: what the
        // server is doing, kept per token on the slot so the status bar can
        // say "indexing (45%)" instead of an unexplained silence.
        {
            let slots = slots.clone();
            let store = store.clone();
            server_handle.on_notification::<lsp::notification::Progress, _>(
                move |params: lsp::ProgressParams, _cx: &mut AsyncApp| {
                    let lsp::ProgressParamsValue::WorkDone(progress) = params.value else {
                        return;
                    };
                    let token = match params.token {
                        lsp::NumberOrString::Number(number) => number.to_string(),
                        lsp::NumberOrString::String(string) => string,
                    };
                    {
                        let mut slots = slots.lock().unwrap();
                        let Some(slot) = slots.get_mut(&(project, server.name)) else {
                            return;
                        };
                        // Progress is proof of life, exactly as a publish is:
                        // a rust-analyzer chewing through a workspace is
                        // working, not idle.
                        slot.last_activity = Instant::now();
                        match progress {
                            lsp::WorkDoneProgress::Begin(begin) => {
                                slot.progress.insert(
                                    token,
                                    ServerProgress {
                                        title: begin.title,
                                        message: begin.message,
                                        percentage: begin.percentage,
                                    },
                                );
                            }
                            // A report may carry only the part that moved;
                            // the rest keeps what the begin said.
                            lsp::WorkDoneProgress::Report(report) => {
                                if let Some(entry) = slot.progress.get_mut(&token) {
                                    if report.message.is_some() {
                                        entry.message = report.message;
                                    }
                                    if report.percentage.is_some() {
                                        entry.percentage = report.percentage;
                                    }
                                }
                            }
                            lsp::WorkDoneProgress::End(_) => {
                                slot.progress.remove(&token);
                            }
                        }
                    }
                    store.bump(project);
                },
            )
        },
    ];

    // `initialize` with the capabilities Zed advertises, which is where UTF-16
    // positions are agreed (vendor/lsp/src/lsp.rs:808) — the reason every
    // column this module hands the UI is already in the units the UI wants.
    // Pull diagnostics are off: we take the pushed ones, which is what every
    // server we start does anyway, and it keeps the UI to one path.
    let mut params = cx.update(|cx| server_handle.default_initialize_params(false, false, cx));
    // The user's `initialization_options`, verbatim — rust-analyzer and
    // clangd take their configuration this way and no other.
    params.initialization_options = initialization_options;
    let initialize = cx.update(move |cx| {
        server_handle.initialize(
            params,
            Arc::new(lsp::DidChangeConfigurationParams {
                settings: serde_json::Value::Null,
            }),
            INITIALIZE_TIMEOUT,
            cx,
        )
    });
    let handle = match initialize.await {
        Ok(handle) => handle,
        Err(err) => {
            // The overwhelmingly common cause: the package is not installed,
            // so proot exited with "command not found" and the request never
            // got an answer. Exactly the state a fresh Debian is in, and the
            // user's cue to install it.
            let captured = stderr.lock().clone().unwrap_or_default();
            let detail = captured.lines().next_back().unwrap_or_default().trim();
            fail(if detail.is_empty() {
                format!("{err:#}")
            } else {
                detail.to_owned()
            });
            return;
        }
    };

    let capabilities = handle.capabilities();
    let (sync, wants_save) = sync_of(&capabilities);
    if sync == TextDocumentSyncKind::FULL {
        // Latching rather than reference-counted: it stays set for the life of
        // the process once any server has ever wanted it. The cost of being
        // wrong is one `Buffer::text()` per edit, and getting the bookkeeping
        // wrong the other way is a server told nothing at all.
        wants_full_text.store(true, Ordering::Relaxed);
    }
    log::info!(
        "lsp: {} initialized for project {project} (sync {sync:?}, save {wants_save})",
        server.name
    );
    log_line(
        &logs,
        key,
        "",
        &format!("initialized (sync {sync:?}, save {wants_save})"),
    );
    {
        let mut servers = slots.lock().unwrap();
        let Some(slot) = servers.get_mut(&(project, server.name)) else {
            // The project closed while we were starting. Dropping `handle`
            // here runs the shutdown handshake, which is what we want.
            return;
        };
        slot.state = ServerState::Running;
        slot.handle = Some(handle.clone());
        slot.sync = sync;
        slot.wants_save = wants_save;
        slot.capabilities = Some(Box::new(capabilities));
        slot.subscriptions.push(subscription);
        slot.subscriptions.extend(acknowledgements);
        // Initializing can take a minute on a cold rust-analyzer, and it is the
        // opposite of idle: the clock starts here, not when we spawned it.
        slot.last_activity = Instant::now();
    }
    // Every buffer of this language that was opened while we were starting.
    open_pending_docs(&handle, project, server.name, &docs, &buffers, &store);
    store.bump(project);
}

/// Send `didOpen` for every document of this server that has not had one.
///
/// The text is read *here* rather than when the document was registered,
/// because a server that takes ten seconds to initialize is a server the user
/// has been typing at for ten seconds: `didOpen` has to describe the buffer as
/// it is now, and the LSP version it carries is the one every later
/// `didChange` counts on from.
fn open_pending_docs(
    server: &LanguageServer,
    project: ProjectId,
    name: &'static str,
    docs: &Docs,
    buffers: &Buffers,
    store: &DiagnosticStore,
) {
    let pending: Vec<(BufferId, Uri, PathBuf, &'static str, i32)> = {
        let mut docs = docs.lock().unwrap();
        docs.iter_mut()
            .filter(|(_, doc)| doc.project == project && doc.server == name && !doc.opened)
            .map(|(buffer, doc)| {
                doc.opened = true;
                // A document whose server the sweep stopped is being watched
                // again, by this one: dormancy ends where `didOpen` begins.
                doc.dormant = false;
                (
                    *buffer,
                    doc.uri.clone(),
                    doc.path.clone(),
                    doc.language_id,
                    doc.lsp_version,
                )
            })
            .collect()
    };
    for (buffer, uri, path, language_id, version) in pending {
        let Some((text, buffer_version)) = buffers.read().unwrap().get(&buffer).map(|state| {
            let state = state.lock().unwrap();
            (state.buffer.text(), state.version)
        }) else {
            continue;
        };
        store.note_sent(&path, version, buffer_version);
        server.register_buffer(uri, language_id.to_owned(), version, text);
    }
}

/// What the server said about document sync, defaulted the way the spec says:
/// absent means no sync at all, and a server that says nothing gets told
/// nothing.
fn sync_of(capabilities: &lsp::ServerCapabilities) -> (TextDocumentSyncKind, bool) {
    match &capabilities.text_document_sync {
        Some(TextDocumentSyncCapability::Kind(kind)) => (*kind, false),
        Some(TextDocumentSyncCapability::Options(options)) => (
            options.change.unwrap_or(TextDocumentSyncKind::NONE),
            match &options.save {
                Some(TextDocumentSyncSaveOptions::Supported(supported)) => *supported,
                Some(TextDocumentSyncSaveOptions::SaveOptions(_)) => true,
                None => false,
            },
        ),
        None => (TextDocumentSyncKind::NONE, false),
    }
}

/// Send one request and turn its answer into the JSON the UI reads — and,
/// for the kinds that produce work rather than words, the [`StoredWork`] the
/// next step runs on.
async fn perform(
    server: &LanguageServer,
    kind: RequestKind,
    uri: Uri,
    row: u32,
    col_utf16: u32,
    args: RequestArgs,
) -> (RequestState, serde_json::Value, StoredWork) {
    let position = TextDocumentPositionParams {
        text_document: TextDocumentIdentifier::new(uri),
        position: Position::new(row, col_utf16),
    };
    let plain = |(state, payload)| (state, payload, StoredWork::None);
    match (kind, args) {
        (RequestKind::Completion, _) => {
            let response = server
                .request::<lsp::request::Completion>(
                    CompletionParams {
                        text_document_position: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                        context: None,
                    },
                    COMPLETION_TIMEOUT,
                )
                .await;
            match response {
                util::ConnectionResult::Result(Ok(response)) => {
                    let (is_incomplete, items) = match response {
                        Some(CompletionResponse::Array(items)) => (false, items),
                        Some(CompletionResponse::List(list)) => (list.is_incomplete, list.items),
                        None => (false, Vec::new()),
                    };
                    // The wire items stay here for `completionItem/resolve`
                    // — see [`StoredWork::Completions`].
                    (
                        RequestState::Done,
                        completion_json(is_incomplete, &items),
                        StoredWork::Completions(items),
                    )
                }
                other => plain(settle(other, |_: Option<CompletionResponse>| {
                    serde_json::Value::Null
                })),
            }
        }
        (RequestKind::CompletionResolve, RequestArgs::CompletionResolve { item }) => {
            resolve_completion(server, *item, position.text_document.uri).await
        }
        (RequestKind::TypeDefinition, _) => {
            let response = server
                .request::<lsp::request::GotoTypeDefinition>(
                    GotoDefinitionParams {
                        text_document_position_params: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    DEFINITION_TIMEOUT,
                )
                .await;
            plain(settle(response, definition_json))
        }
        (RequestKind::Implementation, _) => {
            let response = server
                .request::<lsp::request::GotoImplementation>(
                    GotoDefinitionParams {
                        text_document_position_params: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    DEFINITION_TIMEOUT,
                )
                .await;
            plain(settle(response, definition_json))
        }
        (RequestKind::Declaration, _) => {
            let response = server
                .request::<lsp::request::GotoDeclaration>(
                    GotoDefinitionParams {
                        text_document_position_params: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    DEFINITION_TIMEOUT,
                )
                .await;
            plain(settle(response, definition_json))
        }
        (RequestKind::InlayHint, RequestArgs::InlayHints { end_row }) => {
            let response = server
                .request::<lsp::request::InlayHintRequest>(
                    lsp::InlayHintParams {
                        text_document: position.text_document,
                        // To the start of the row after the last: a hint
                        // anchored at the end of `end_row` is inside it.
                        range: Range::new(Position::new(row, 0), Position::new(end_row + 1, 0)),
                        work_done_progress_params: WorkDoneProgressParams::default(),
                    },
                    INLAY_HINT_TIMEOUT,
                )
                .await;
            plain(settle(response, inlay_hints_json))
        }
        (RequestKind::SignatureHelp, _) => {
            let response = server
                .request::<lsp::request::SignatureHelpRequest>(
                    lsp::SignatureHelpParams {
                        text_document_position_params: position,
                        context: None,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                    },
                    SIGNATURE_HELP_TIMEOUT,
                )
                .await;
            plain(settle(response, signature_help_json))
        }
        (RequestKind::FoldingRange, _) => {
            let response = server
                .request::<lsp::request::FoldingRangeRequest>(
                    lsp::FoldingRangeParams {
                        text_document: position.text_document,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    FOLDING_RANGE_TIMEOUT,
                )
                .await;
            plain(settle(response, folding_ranges_json))
        }
        (RequestKind::Hover, _) => {
            let response = server
                .request::<lsp::request::HoverRequest>(
                    HoverParams {
                        text_document_position_params: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                    },
                    HOVER_TIMEOUT,
                )
                .await;
            plain(settle(response, hover_json))
        }
        (RequestKind::Definition, _) => {
            let response = server
                .request::<lsp::request::GotoDefinition>(
                    GotoDefinitionParams {
                        text_document_position_params: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    DEFINITION_TIMEOUT,
                )
                .await;
            plain(settle(response, definition_json))
        }
        (RequestKind::References, _) => {
            let response = server
                .request::<lsp::request::References>(
                    lsp::ReferenceParams {
                        text_document_position: position,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                        context: lsp::ReferenceContext {
                            // The declaration belongs in the list: "who uses
                            // this" includes where it came from, and Zed
                            // includes it too.
                            include_declaration: true,
                        },
                    },
                    REFERENCES_TIMEOUT,
                )
                .await;
            plain(settle(response, references_json))
        }
        (RequestKind::CodeAction, RequestArgs::CodeActions { diagnostics }) => {
            // The range the actions are for: the diagnostics under the caret
            // when there are any — a fix should cover the whole problem, not
            // the one character the caret is on — and the caret itself
            // otherwise, which is how refactorings with nothing wrong appear.
            let range = diagnostics
                .iter()
                .map(|diagnostic| diagnostic.range)
                .reduce(|a, b| Range::new(a.start.min(b.start), a.end.max(b.end)))
                .unwrap_or_else(|| Range::new(position.position, position.position));
            let response = server
                .request::<lsp::request::CodeActionRequest>(
                    lsp::CodeActionParams {
                        text_document: position.text_document,
                        range,
                        context: lsp::CodeActionContext {
                            diagnostics,
                            only: None,
                            trigger_kind: Some(lsp::CodeActionTriggerKind::INVOKED),
                        },
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    CODE_ACTION_TIMEOUT,
                )
                .await;
            match response {
                util::ConnectionResult::Result(Ok(actions)) => {
                    let actions = actions.unwrap_or_default();
                    (
                        RequestState::Done,
                        code_actions_json(&actions),
                        StoredWork::Actions(actions),
                    )
                }
                other => plain(settle(other, |_: Option<lsp::CodeActionResponse>| {
                    serde_json::Value::Null
                })),
            }
        }
        (RequestKind::CodeActionApply, RequestArgs::CodeActionApply { action, applied }) => {
            let action = match *action {
                lsp::CodeActionOrCommand::CodeAction(action) => action,
                // A bare command: nothing to resolve, nothing to apply
                // ourselves. The server runs it and pushes what it changes
                // through `workspace/applyEdit`.
                lsp::CodeActionOrCommand::Command(command) => {
                    return execute_command(server, command, &applied).await;
                }
            };
            // An action may arrive lazy: no edit yet, a `data` field the
            // server wants echoed back through `codeAction/resolve`. That is
            // rust-analyzer's normal shape.
            let resolved = if action.edit.is_none() && action.data.is_some() {
                match server
                    .request::<lsp::request::CodeActionResolveRequest>(
                        action.clone(),
                        CODE_ACTION_TIMEOUT,
                    )
                    .await
                {
                    util::ConnectionResult::Result(Ok(resolved)) => resolved,
                    util::ConnectionResult::Result(Err(err)) => {
                        log::debug!("lsp: codeAction/resolve failed: {err:#}");
                        action
                    }
                    util::ConnectionResult::Timeout => {
                        return (RequestState::Timeout, serde_json::Value::Null, StoredWork::None);
                    }
                    util::ConnectionResult::ConnectionReset => {
                        return (
                            RequestState::Unavailable,
                            serde_json::Value::Null,
                            StoredWork::None,
                        );
                    }
                }
            } else {
                action
            };
            match (resolved.edit, resolved.command) {
                (Some(edit), _) => (
                    RequestState::Done,
                    edit_summary_json(&edit),
                    StoredWork::Edit(edit),
                ),
                // No edit even after resolving, but a command: the action
                // *is* the command (rust-analyzer's "run", clangd's
                // "switch header"), so run it and take what it pushes back.
                (None, Some(command)) => execute_command(server, command, &applied).await,
                (None, None) => (
                    RequestState::Done,
                    error_payload("the server offered no edit for this action"),
                    StoredWork::None,
                ),
            }
        }
        (RequestKind::Rename, RequestArgs::Rename { new_name }) => {
            let response = server
                .request::<lsp::request::Rename>(
                    lsp::RenameParams {
                        text_document_position: position,
                        new_name,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                    },
                    RENAME_TIMEOUT,
                )
                .await;
            match response {
                util::ConnectionResult::Result(Ok(Some(edit))) => (
                    RequestState::Done,
                    edit_summary_json(&edit),
                    StoredWork::Edit(edit),
                ),
                util::ConnectionResult::Result(Ok(None)) => (
                    RequestState::Done,
                    error_payload("the server had nothing to rename here"),
                    StoredWork::None,
                ),
                other => plain(settle(other, |_: Option<lsp::WorkspaceEdit>| {
                    serde_json::Value::Null
                })),
            }
        }
        (
            RequestKind::CodeActionsOnFormat,
            RequestArgs::CodeActionsOnFormat { kinds, end },
        ) => {
            if kinds.is_empty() {
                return (
                    RequestState::Done,
                    edit_list_summary_json(&[]),
                    StoredWork::Edits(Vec::new()),
                );
            }
            let response = server
                .request::<lsp::request::CodeActionRequest>(
                    lsp::CodeActionParams {
                        text_document: position.text_document,
                        range: Range::new(Position::new(0, 0), end),
                        context: lsp::CodeActionContext {
                            diagnostics: Vec::new(),
                            only: Some(
                                kinds
                                    .iter()
                                    .map(|kind| lsp::CodeActionKind::from(kind.clone()))
                                    .collect(),
                            ),
                            trigger_kind: Some(lsp::CodeActionTriggerKind::AUTOMATIC),
                        },
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    CODE_ACTION_TIMEOUT,
                )
                .await;
            let actions = match response {
                util::ConnectionResult::Result(Ok(actions)) => actions.unwrap_or_default(),
                other => {
                    return plain(settle(other, |_: Option<lsp::CodeActionResponse>| {
                        serde_json::Value::Null
                    }));
                }
            };
            let mut edits = Vec::new();
            for action in actions {
                let lsp::CodeActionOrCommand::CodeAction(action) = action else {
                    continue;
                };
                // Servers answer `only` loosely; keep to what was asked, as
                // Zed filters by kind before running (lsp_store.rs).
                let wanted = action.kind.as_ref().is_some_and(|kind| {
                    kinds.iter().any(|k| kind.as_str().starts_with(k.as_str()))
                });
                if !wanted || action.disabled.is_some() {
                    continue;
                }
                let resolved = if action.edit.is_none() && action.data.is_some() {
                    match server
                        .request::<lsp::request::CodeActionResolveRequest>(
                            action.clone(),
                            CODE_ACTION_TIMEOUT,
                        )
                        .await
                    {
                        util::ConnectionResult::Result(Ok(resolved)) => resolved,
                        _ => action,
                    }
                } else {
                    action
                };
                if let Some(edit) = resolved.edit {
                    edits.push(edit);
                }
            }
            (
                RequestState::Done,
                edit_list_summary_json(&edits),
                StoredWork::Edits(edits),
            )
        }
        (
            RequestKind::Formatting,
            RequestArgs::Formatting {
                tab_size,
                insert_spaces,
            },
        ) => {
            let uri = position.text_document.uri.clone();
            let response = server
                .request::<lsp::request::Formatting>(
                    lsp::DocumentFormattingParams {
                        text_document: position.text_document,
                        options: lsp::FormattingOptions {
                            tab_size,
                            insert_spaces,
                            ..Default::default()
                        },
                        work_done_progress_params: WorkDoneProgressParams::default(),
                    },
                    FORMATTING_TIMEOUT,
                )
                .await;
            match response {
                util::ConnectionResult::Result(Ok(edits)) => {
                    let edits = edits.unwrap_or_default();
                    // A formatter's answer is a WorkspaceEdit over one file,
                    // so the one applier serves all three edit producers.
                    let edit = lsp::WorkspaceEdit {
                        changes: Some(HashMap::from([(uri, edits)])),
                        document_changes: None,
                        change_annotations: None,
                    };
                    (
                        RequestState::Done,
                        edit_summary_json(&edit),
                        StoredWork::Edit(edit),
                    )
                }
                other => plain(settle(other, |_: Option<Vec<lsp::TextEdit>>| {
                    serde_json::Value::Null
                })),
            }
        }
        // A kind reached without the arguments it needs — unreachable from
        // the public entry points, answered rather than paniced all the same.
        (_, _) => (
            RequestState::Unavailable,
            serde_json::Value::Null,
            StoredWork::None,
        ),
    }
}

fn settle<T>(
    response: util::ConnectionResult<T>,
    render: impl FnOnce(T) -> serde_json::Value,
) -> (RequestState, serde_json::Value) {
    match response {
        util::ConnectionResult::Result(Ok(value)) => (RequestState::Done, render(value)),
        util::ConnectionResult::Result(Err(err)) => {
            log::debug!("lsp request failed: {err:#}");
            (RequestState::Unavailable, serde_json::Value::Null)
        }
        util::ConnectionResult::Timeout => (RequestState::Timeout, serde_json::Value::Null),
        // The server died mid-request. Reported as unavailable rather than as
        // a timeout, because retrying will not help until it is restarted.
        util::ConnectionResult::ConnectionReset => {
            (RequestState::Unavailable, serde_json::Value::Null)
        }
    }
}

/// `completionItem/resolve` for one item the list left half-filled — Zed's
/// `resolve_completions` (crates/project/src/lsp_store.rs, the
/// `ResolveCompletionItem` request it sends while the menu is open): the
/// documentation goes to the UI as text, and the `additionalTextEdits` — the
/// `use` line an import-completing server wants to add — are held as an
/// edit for `lsp_apply_pending_edit`, to land after the completion itself.
async fn resolve_completion(
    server: &LanguageServer,
    item: lsp::CompletionItem,
    uri: Uri,
) -> (RequestState, serde_json::Value, StoredWork) {
    let response = server
        .request::<lsp::request::ResolveCompletionItem>(item, COMPLETION_TIMEOUT)
        .await;
    match response {
        util::ConnectionResult::Result(Ok(resolved)) => {
            let additional = resolved.additional_text_edits.clone().unwrap_or_default();
            let stored = if additional.is_empty() {
                StoredWork::None
            } else {
                StoredWork::Edit(lsp::WorkspaceEdit {
                    changes: Some(HashMap::from([(uri, additional)])),
                    document_changes: None,
                    change_annotations: None,
                })
            };
            (RequestState::Done, completion_resolve_json(&resolved), stored)
        }
        other => {
            let (state, payload) = settle(other, |_: lsp::CompletionItem| serde_json::Value::Null);
            (state, payload, StoredWork::None)
        }
    }
}

/// Run a server command and collect what it pushed back — Zed's
/// `execute_code_actions` for the command half of an action
/// (lsp_store.rs:2295-2360): `workspace/executeCommand`, then whatever
/// `workspace/applyEdit` requests arrived meanwhile are this action's edit.
///
/// Several pushed edits merge into one `changes` map — a command that edits
/// two files in two requests lands as one apply, one receipt, one undo per
/// buffer. `document_changes` from a pushed edit are kept only from the
/// first that has them; every server we start uses `changes`.
async fn execute_command(
    server: &LanguageServer,
    command: lsp::Command,
    applied: &AppliedEdits,
) -> (RequestState, serde_json::Value, StoredWork) {
    // Whatever was pushed before this command is not this command's.
    applied.lock().unwrap().clear();
    let response = server
        .request::<lsp::request::ExecuteCommand>(
            lsp::ExecuteCommandParams {
                command: command.command.clone(),
                arguments: command.arguments.unwrap_or_default(),
                work_done_progress_params: WorkDoneProgressParams::default(),
            },
            EXECUTE_COMMAND_TIMEOUT,
        )
        .await;
    match response {
        util::ConnectionResult::Result(Ok(_)) => {}
        util::ConnectionResult::Result(Err(err)) => {
            log::debug!("lsp: command {} failed: {err:#}", command.command);
            return (
                RequestState::Done,
                error_payload(&format!("the server could not run {}", command.command)),
                StoredWork::None,
            );
        }
        util::ConnectionResult::Timeout => {
            return (RequestState::Timeout, serde_json::Value::Null, StoredWork::None);
        }
        util::ConnectionResult::ConnectionReset => {
            return (
                RequestState::Unavailable,
                serde_json::Value::Null,
                StoredWork::None,
            );
        }
    }
    let pushed: Vec<lsp::WorkspaceEdit> = std::mem::take(&mut *applied.lock().unwrap());
    match merge_workspace_edits(pushed) {
        Some(edit) => (
            RequestState::Done,
            edit_summary_json(&edit),
            StoredWork::Edit(edit),
        ),
        // A command that changed nothing is a command that ran: "cargo
        // check" style actions answer this way, and the UI says so.
        None => (
            RequestState::Done,
            serde_json::json!({ "files": 0, "edits": 0, "resource_ops": false, "ran": command.title }),
            StoredWork::None,
        ),
    }
}

/// Several `workspace/applyEdit`s as one — see [`execute_command`].
fn merge_workspace_edits(edits: Vec<lsp::WorkspaceEdit>) -> Option<lsp::WorkspaceEdit> {
    let mut merged: Option<lsp::WorkspaceEdit> = None;
    for edit in edits {
        match &mut merged {
            None => merged = Some(edit),
            Some(into) => {
                if let Some(changes) = edit.changes {
                    let target = into.changes.get_or_insert_with(HashMap::new);
                    for (uri, edits) in changes {
                        target.entry(uri).or_default().extend(edits);
                    }
                }
                if into.document_changes.is_none() {
                    into.document_changes = edit.document_changes;
                }
            }
        }
    }
    merged
}

/// What [`Engine::lsp_buffer_triggers`] reads out of a server's capabilities.
fn triggers_of(capabilities: &lsp::ServerCapabilities) -> BufferTriggers {
    let completion = capabilities
        .completion_provider
        .as_ref()
        .and_then(|provider| provider.trigger_characters.clone())
        .unwrap_or_default();
    let (signature_help, signature_help_retrigger) = capabilities
        .signature_help_provider
        .as_ref()
        .map(|provider| {
            (
                provider.trigger_characters.clone().unwrap_or_default(),
                provider.retrigger_characters.clone().unwrap_or_default(),
            )
        })
        .unwrap_or_default();
    let folding_ranges = match &capabilities.folding_range_provider {
        Some(lsp::FoldingRangeProviderCapability::Simple(enabled)) => *enabled,
        Some(_) => true,
        None => false,
    };
    let inlay_hints = match &capabilities.inlay_hint_provider {
        Some(lsp::OneOf::Left(enabled)) => *enabled,
        Some(lsp::OneOf::Right(_)) => true,
        None => false,
    };
    BufferTriggers {
        completion,
        signature_help,
        signature_help_retrigger,
        folding_ranges,
        inlay_hints,
    }
}

/// `workspace/symbol` to every server at once, merged — see
/// [`Engine::lsp_request_workspace_symbols`]. A server that times out or
/// errors contributes nothing; the answer is `Done` as long as one server
/// answered, `Timeout` when none did.
async fn perform_workspace_symbols(
    handles: Vec<(&'static str, Arc<LanguageServer>)>,
    query: String,
    root: Option<PathBuf>,
) -> (RequestState, serde_json::Value) {
    let asks = handles.into_iter().map(|(name, handle)| {
        let query = query.clone();
        async move {
            let response = handle
                .request::<lsp::request::WorkspaceSymbolRequest>(
                    lsp::WorkspaceSymbolParams {
                        query,
                        work_done_progress_params: WorkDoneProgressParams::default(),
                        partial_result_params: PartialResultParams::default(),
                    },
                    WORKSPACE_SYMBOL_TIMEOUT,
                )
                .await;
            (name, response)
        }
    });
    let answers = futures::future::join_all(asks).await;
    let mut symbols: Vec<WorkspaceSymbolJson> = Vec::new();
    let mut answered = false;
    for (name, response) in answers {
        match response {
            util::ConnectionResult::Result(Ok(response)) => {
                answered = true;
                symbols.extend(workspace_symbols(name, response, root.as_deref()));
            }
            util::ConnectionResult::Result(Err(err)) => {
                log::debug!("lsp: {name} workspace/symbol failed: {err:#}");
            }
            util::ConnectionResult::Timeout | util::ConnectionResult::ConnectionReset => {}
        }
    }
    if !answered {
        return (RequestState::Timeout, serde_json::Value::Null);
    }
    (
        RequestState::Done,
        serde_json::to_value(WorkspaceSymbolsPayload { symbols }).unwrap_or(serde_json::Value::Null),
    )
}

// ---------------------------------------------------------------------------
// The JSON the UI wave consumes. Frozen after this change.
// ---------------------------------------------------------------------------

#[derive(serde::Serialize)]
struct CompletionPayload {
    /// The list is not the whole truth: re-ask after the next character.
    is_incomplete: bool,
    items: Vec<CompletionItemJson>,
}

#[derive(serde::Serialize)]
struct CompletionItemJson {
    /// Position in the stored list — what `lspRequestCompletionResolve` takes.
    index: usize,
    /// What the popup shows.
    label: String,
    /// The signature or type the server puts to the right of the label.
    detail: Option<String>,
    /// LSP's `CompletionItemKind` in snake_case ("function", "struct",
    /// "type_parameter"), or null.
    kind: Option<String>,
    /// The text to put in the buffer. Never null: falls back to `label`, which
    /// is what the spec says.
    insert_text: String,
    /// `insert_text` is a snippet (`${1:name}` placeholders), not literal text.
    is_snippet: bool,
    /// What to match the user's typing against; falls back to `label`.
    filter_text: String,
    /// What to sort by; falls back to `label`.
    sort_text: String,
    /// Documentation, flattened to markdown.
    documentation: Option<String>,
    deprecated: bool,
    preselect: bool,
    /// The range `insert_text` replaces, in the buffer's UTF-16 coordinates,
    /// when the server named one. Null means the UI decides — the word around
    /// the caret, as Zed does.
    edit: Option<RangeJson>,
}

#[derive(serde::Serialize)]
struct RangeJson {
    row: u32,
    col_utf16: u32,
    end_row: u32,
    end_col_utf16: u32,
}

impl From<Range> for RangeJson {
    fn from(range: Range) -> Self {
        Self {
            row: range.start.line,
            col_utf16: range.start.character,
            end_row: range.end.line,
            end_col_utf16: range.end.character,
        }
    }
}

#[derive(serde::Serialize)]
struct HoverPayload {
    /// Everything the server said, as one markdown string. Empty when it had
    /// nothing to say, which is a perfectly ordinary answer.
    contents: String,
    /// What the hover applies to, when the server said.
    range: Option<RangeJson>,
}

#[derive(serde::Serialize)]
struct DefinitionPayload {
    targets: Vec<TargetJson>,
}

#[derive(serde::Serialize)]
struct TargetJson {
    /// Absolute host path — which is also the guest path, because the binds are
    /// identities. Pass it straight to `openFile`.
    path: String,
    /// Where to put the caret.
    row: u32,
    col_utf16: u32,
    /// The whole symbol, for selecting it on arrival.
    end_row: u32,
    end_col_utf16: u32,
    /// The line the target sits on, trimmed — filled in for references, where
    /// a list of bare positions would be unreadable, and absent elsewhere.
    /// Read from disk, so it can trail an unsaved buffer by one save.
    #[serde(skip_serializing_if = "Option::is_none")]
    line_text: Option<String>,
}

fn completion_json(is_incomplete: bool, items: &[lsp::CompletionItem]) -> serde_json::Value {
    let items = items
        .iter()
        .cloned()
        .enumerate()
        .map(|(index, item)| {
            let (text_edit, edit) = match item.text_edit {
                Some(CompletionTextEdit::Edit(edit)) => (Some(edit.new_text), Some(edit.range)),
                Some(CompletionTextEdit::InsertAndReplace(edit)) => {
                    // The *replace* range is the one Zed applies, because
                    // accepting a completion over an existing identifier should
                    // replace it rather than double it.
                    (Some(edit.new_text), Some(edit.replace))
                }
                None => (None, None),
            };
            let insert_text = text_edit
                .or(item.insert_text)
                .unwrap_or_else(|| item.label.clone());
            CompletionItemJson {
                index,
                kind: item.kind.map(completion_kind_name),
                detail: item.detail,
                is_snippet: item.insert_text_format == Some(lsp::InsertTextFormat::SNIPPET),
                filter_text: item.filter_text.unwrap_or_else(|| item.label.clone()),
                sort_text: item.sort_text.unwrap_or_else(|| item.label.clone()),
                documentation: item.documentation.map(|documentation| match documentation {
                    Documentation::String(text) => text,
                    Documentation::MarkupContent(markup) => markup.value,
                }),
                deprecated: item.deprecated.unwrap_or(false)
                    || item
                        .tags
                        .as_ref()
                        .is_some_and(|tags| tags.contains(&lsp::CompletionItemTag::DEPRECATED)),
                preselect: item.preselect.unwrap_or(false),
                edit: edit.map(RangeJson::from),
                insert_text,
                label: item.label,
            }
        })
        .collect();
    serde_json::to_value(CompletionPayload {
        is_incomplete,
        items,
    })
    .unwrap_or(serde_json::Value::Null)
}

/// LSP's numbered kinds, spelled the way the rest of our JSON spells enums.
fn completion_kind_name(kind: lsp::CompletionItemKind) -> String {
    use lsp::CompletionItemKind as K;
    let name = match kind {
        K::TEXT => "text",
        K::METHOD => "method",
        K::FUNCTION => "function",
        K::CONSTRUCTOR => "constructor",
        K::FIELD => "field",
        K::VARIABLE => "variable",
        K::CLASS => "class",
        K::INTERFACE => "interface",
        K::MODULE => "module",
        K::PROPERTY => "property",
        K::UNIT => "unit",
        K::VALUE => "value",
        K::ENUM => "enum",
        K::KEYWORD => "keyword",
        K::SNIPPET => "snippet",
        K::COLOR => "color",
        K::FILE => "file",
        K::REFERENCE => "reference",
        K::FOLDER => "folder",
        K::ENUM_MEMBER => "enum_member",
        K::CONSTANT => "constant",
        K::STRUCT => "struct",
        K::EVENT => "event",
        K::OPERATOR => "operator",
        K::TYPE_PARAMETER => "type_parameter",
        _ => "text",
    };
    name.to_owned()
}

fn hover_json(response: Option<lsp::Hover>) -> serde_json::Value {
    let Some(hover) = response else {
        return serde_json::to_value(HoverPayload {
            contents: String::new(),
            range: None,
        })
        .unwrap_or(serde_json::Value::Null);
    };
    let contents = match hover.contents {
        HoverContents::Scalar(marked) => marked_string(marked),
        HoverContents::Array(marked) => marked
            .into_iter()
            .map(marked_string)
            .collect::<Vec<_>>()
            .join("\n\n"),
        HoverContents::Markup(markup) => markup.value,
    };
    serde_json::to_value(HoverPayload {
        contents: contents.trim().to_owned(),
        range: hover.range.map(RangeJson::from),
    })
    .unwrap_or(serde_json::Value::Null)
}

/// LSP's pre-markup hover form, rendered as markdown so the UI has one thing
/// to draw rather than three.
fn marked_string(marked: MarkedString) -> String {
    match marked {
        MarkedString::String(text) => text,
        MarkedString::LanguageString(string) => {
            format!("```{}\n{}\n```", string.language, string.value)
        }
    }
}

fn definition_json(response: Option<GotoDefinitionResponse>) -> serde_json::Value {
    let targets = match response {
        Some(GotoDefinitionResponse::Scalar(location)) => {
            vec![target(location.uri, location.range)]
        }
        Some(GotoDefinitionResponse::Array(locations)) => locations
            .into_iter()
            .map(|location| target(location.uri, location.range))
            .collect(),
        Some(GotoDefinitionResponse::Link(links)) => links
            .into_iter()
            .map(|link| target(link.target_uri, link.target_selection_range))
            .collect(),
        None => Vec::new(),
    };
    serde_json::to_value(DefinitionPayload {
        targets: targets.into_iter().flatten().collect(),
    })
    .unwrap_or(serde_json::Value::Null)
}

fn target(uri: Uri, range: Range) -> Option<TargetJson> {
    // A definition in a URI that is not a file — a server's synthetic
    // `zipfile:` or `jdt:` document — is one we cannot open, so it is dropped
    // rather than handed over as a path that does not exist.
    let path = uri.to_file_path().ok()?;
    Some(TargetJson {
        path: path.to_string_lossy().into_owned(),
        row: range.start.line,
        col_utf16: range.start.character,
        end_row: range.end.line,
        end_col_utf16: range.end.character,
        line_text: None,
    })
}

#[derive(serde::Serialize)]
struct ReferencesPayload {
    targets: Vec<TargetJson>,
}

fn references_json(locations: Option<Vec<lsp::Location>>) -> serde_json::Value {
    // One read per file, however many references it holds — a symbol used two
    // hundred times in one module costs one file, not two hundred.
    let mut lines_by_file: HashMap<PathBuf, Option<Vec<String>>> = HashMap::new();
    let targets: Vec<TargetJson> = locations
        .unwrap_or_default()
        .into_iter()
        .filter_map(|location| {
            let mut target = target(location.uri, location.range)?;
            let path = PathBuf::from(&target.path);
            let lines = lines_by_file.entry(path.clone()).or_insert_with(|| {
                std::fs::read_to_string(&path)
                    .ok()
                    .map(|text| text.lines().map(|line| line.trim().to_owned()).collect())
            });
            target.line_text = lines
                .as_ref()
                .and_then(|lines| lines.get(target.row as usize).cloned());
            Some(target)
        })
        .collect();
    serde_json::to_value(ReferencesPayload { targets }).unwrap_or(serde_json::Value::Null)
}

/// `{"error": …}` — the shape a settled-but-empty answer takes, so the UI can
/// put a sentence where a list or a receipt was hoped for.
fn error_payload(message: &str) -> serde_json::Value {
    serde_json::json!({ "error": message })
}

#[derive(serde::Serialize)]
struct InlayHintsPayload {
    hints: Vec<InlayHintJson>,
}

#[derive(serde::Serialize)]
struct InlayHintJson {
    /// Where the hint hangs: before the character at this position.
    row: u32,
    col_utf16: u32,
    /// The text to draw, label parts joined — a hint is one run of dimmed
    /// text in Zed too (`InlayHintLabel::LabelParts` is flattened for
    /// display, inlay_hint_cache.rs).
    label: String,
    /// `type`, `parameter`, or null — Zed's `InlayHintKind`, which is what
    /// the `show_type_hints` / `show_parameter_hints` / `show_other_hints`
    /// settings filter on.
    kind: Option<&'static str>,
    /// The server asked for a space on this side — Zed honours both
    /// (`padding_left` / `padding_right`, lsp_command.rs:3953-3962).
    padding_left: bool,
    padding_right: bool,
}

fn inlay_hints_json(response: Option<Vec<lsp::InlayHint>>) -> serde_json::Value {
    let hints = response
        .unwrap_or_default()
        .into_iter()
        .map(|hint| InlayHintJson {
            row: hint.position.line,
            col_utf16: hint.position.character,
            label: match hint.label {
                lsp::InlayHintLabel::String(text) => text,
                lsp::InlayHintLabel::LabelParts(parts) => {
                    parts.into_iter().map(|part| part.value).collect()
                }
            },
            kind: hint.kind.and_then(|kind| match kind {
                lsp::InlayHintKind::TYPE => Some("type"),
                lsp::InlayHintKind::PARAMETER => Some("parameter"),
                _ => None,
            }),
            padding_left: hint.padding_left.unwrap_or(false),
            padding_right: hint.padding_right.unwrap_or(false),
        })
        .collect();
    serde_json::to_value(InlayHintsPayload { hints }).unwrap_or(serde_json::Value::Null)
}

#[derive(serde::Serialize)]
struct SignatureHelpPayload {
    signatures: Vec<SignatureJson>,
    /// Index into `signatures`, clamped the way Zed clamps it
    /// (signature_help.rs:290-292).
    active_signature: usize,
}

#[derive(serde::Serialize)]
struct SignatureJson {
    label: String,
    /// Markdown, flattened like a hover's.
    documentation: Option<String>,
    parameters: Vec<ParameterJson>,
    /// Per-signature override of the active parameter, or null for the
    /// help's own.
    active_parameter: Option<usize>,
}

#[derive(serde::Serialize)]
struct ParameterJson {
    /// UTF-16 offsets into the signature's label, the range to embolden.
    start: u32,
    end: u32,
    documentation: Option<String>,
}

fn documentation_text(documentation: Option<Documentation>) -> Option<String> {
    documentation
        .map(|documentation| match documentation {
            Documentation::String(text) => text,
            Documentation::MarkupContent(markup) => markup.value,
        })
        .map(|text| text.trim().to_owned())
        .filter(|text| !text.is_empty())
}

fn signature_help_json(response: Option<lsp::SignatureHelp>) -> serde_json::Value {
    let Some(help) = response else {
        return serde_json::to_value(SignatureHelpPayload {
            signatures: Vec::new(),
            active_signature: 0,
        })
        .unwrap_or(serde_json::Value::Null);
    };
    let help_active_parameter = help.active_parameter;
    let signatures: Vec<SignatureJson> = help
        .signatures
        .into_iter()
        .map(|signature| {
            let label = signature.label;
            let parameters = signature
                .parameters
                .unwrap_or_default()
                .into_iter()
                .filter_map(|parameter| {
                    let (start, end) = match parameter.label {
                        // Offsets are already UTF-16, per the spec.
                        lsp::ParameterLabel::LabelOffsets([start, end]) => (start, end),
                        // A named parameter is found in the label, as Zed
                        // finds it (signature_help.rs `create_signature_help`
                        // searches the label for the parameter text).
                        lsp::ParameterLabel::Simple(name) => {
                            let byte = label.find(&name)?;
                            let start = label[..byte].encode_utf16().count() as u32;
                            (start, start + name.encode_utf16().count() as u32)
                        }
                    };
                    Some(ParameterJson {
                        start,
                        end,
                        documentation: documentation_text(parameter.documentation),
                    })
                })
                .collect();
            SignatureJson {
                label,
                documentation: documentation_text(signature.documentation),
                parameters,
                active_parameter: signature
                    .active_parameter
                    .map(|index| index as usize)
                    .or(help_active_parameter.map(|index| index as usize)),
            }
        })
        .collect();
    let active_signature = (help.active_signature.unwrap_or(0) as usize)
        .min(signatures.len().saturating_sub(1));
    serde_json::to_value(SignatureHelpPayload {
        signatures,
        active_signature,
    })
    .unwrap_or(serde_json::Value::Null)
}

#[derive(serde::Serialize)]
struct FoldingRangesPayload {
    ranges: Vec<crate::highlight::FoldRange>,
}

/// LSP folding ranges in the engine's own fold shape: `end_line` is the
/// last folded line, which is `end_row`. Single-row and inverted ranges
/// are dropped.
fn folding_ranges_json(response: Option<Vec<lsp::FoldingRange>>) -> serde_json::Value {
    let mut ranges: Vec<crate::highlight::FoldRange> = response
        .unwrap_or_default()
        .into_iter()
        .filter(|range| range.end_line > range.start_line)
        .map(|range| crate::highlight::FoldRange {
            start_row: range.start_line,
            end_row: range.end_line,
        })
        .collect();
    ranges.sort_by_key(|range| (range.start_row, range.end_row));
    ranges.dedup_by_key(|range| range.start_row);
    serde_json::to_value(FoldingRangesPayload { ranges }).unwrap_or(serde_json::Value::Null)
}

/// What resolving a completion adds: the parts a server leaves out of the
/// list to keep it light.
fn completion_resolve_json(item: &lsp::CompletionItem) -> serde_json::Value {
    serde_json::json!({
        "documentation": documentation_text(item.documentation.clone()),
        "detail": item.detail.clone(),
        "additional_edits": item.additional_text_edits.as_ref().map(Vec::len).unwrap_or(0),
    })
}

#[derive(serde::Serialize)]
struct WorkspaceSymbolsPayload {
    symbols: Vec<WorkspaceSymbolJson>,
}

#[derive(serde::Serialize)]
struct WorkspaceSymbolJson {
    name: String,
    /// LSP's `SymbolKind`, in the completion-kind spelling ("function",
    /// "struct", "method"…).
    kind: String,
    /// The enclosing symbol, when the server said ("impl Foo").
    container: Option<String>,
    /// Project-relative where it can be, else absolute — as references.
    path: String,
    /// Absolute host path, for opening.
    absolute_path: String,
    row: u32,
    col_utf16: u32,
    end_row: u32,
    end_col_utf16: u32,
    server: &'static str,
}

fn symbol_kind_name(kind: lsp::SymbolKind) -> &'static str {
    use lsp::SymbolKind as K;
    match kind {
        K::FILE => "file",
        K::MODULE => "module",
        K::NAMESPACE => "namespace",
        K::PACKAGE => "package",
        K::CLASS => "class",
        K::METHOD => "method",
        K::PROPERTY => "property",
        K::FIELD => "field",
        K::CONSTRUCTOR => "constructor",
        K::ENUM => "enum",
        K::INTERFACE => "interface",
        K::FUNCTION => "function",
        K::VARIABLE => "variable",
        K::CONSTANT => "constant",
        K::STRING => "string",
        K::NUMBER => "number",
        K::BOOLEAN => "boolean",
        K::ARRAY => "array",
        K::OBJECT => "object",
        K::KEY => "key",
        K::NULL => "null",
        K::ENUM_MEMBER => "enum_member",
        K::STRUCT => "struct",
        K::EVENT => "event",
        K::OPERATOR => "operator",
        K::TYPE_PARAMETER => "type_parameter",
        _ => "symbol",
    }
}

/// One server's `workspace/symbol` answer as rows. Both response shapes are
/// taken; a symbol whose location is only a URI (the nested shape allows
/// it) lands at the top of its file.
fn workspace_symbols(
    server: &'static str,
    response: Option<lsp::WorkspaceSymbolResponse>,
    root: Option<&Path>,
) -> Vec<WorkspaceSymbolJson> {
    let mut rows = Vec::new();
    let mut push = |name: String,
                    kind: lsp::SymbolKind,
                    container: Option<String>,
                    uri: Uri,
                    range: Range| {
        let Some(target) = target(uri, range) else {
            return;
        };
        let absolute = PathBuf::from(&target.path);
        rows.push(WorkspaceSymbolJson {
            name,
            kind: symbol_kind_name(kind).to_owned(),
            container,
            path: relative_path(root, &absolute),
            absolute_path: target.path,
            row: target.row,
            col_utf16: target.col_utf16,
            end_row: target.end_row,
            end_col_utf16: target.end_col_utf16,
            server,
        });
    };
    match response {
        Some(lsp::WorkspaceSymbolResponse::Flat(symbols)) => {
            for symbol in symbols {
                push(
                    symbol.name,
                    symbol.kind,
                    symbol.container_name,
                    symbol.location.uri,
                    symbol.location.range,
                );
            }
        }
        Some(lsp::WorkspaceSymbolResponse::Nested(symbols)) => {
            for symbol in symbols {
                let (uri, range) = match symbol.location {
                    lsp::OneOf::Left(location) => (location.uri, location.range),
                    lsp::OneOf::Right(workspace) => (workspace.uri, Range::default()),
                };
                push(symbol.name, symbol.kind, symbol.container_name, uri, range);
            }
        }
        None => {}
    }
    rows
}

#[derive(serde::Serialize)]
struct CodeActionsPayload {
    actions: Vec<CodeActionJson>,
}

#[derive(serde::Serialize)]
struct CodeActionJson {
    /// Position in the stored list — what `lspRequestCodeActionApply` takes.
    index: usize,
    title: String,
    /// LSP's kind string ("quickfix", "refactor.extract"), or null.
    kind: Option<String>,
    /// The server marked it the best answer; the UI puts it first.
    is_preferred: bool,
    /// Why it cannot run, when it cannot — the server's own reason. Null for
    /// an action that can, commands included.
    disabled: Option<String>,
}

/// Why an action in the list cannot be applied here, if it cannot — only
/// the server's own reason. A bare command, or an action that is only a
/// command, runs through `workspace/executeCommand` and takes its edits back
/// through `workspace/applyEdit` (see [`execute_command`]), so neither is
/// disabled any more.
fn action_disabled_reason(action: &lsp::CodeActionOrCommand) -> Option<String> {
    match action {
        lsp::CodeActionOrCommand::Command(_) => None,
        lsp::CodeActionOrCommand::CodeAction(action) => {
            action.disabled.as_ref().map(|disabled| disabled.reason.clone())
        }
    }
}

fn code_actions_json(actions: &[lsp::CodeActionOrCommand]) -> serde_json::Value {
    let actions: Vec<CodeActionJson> = actions
        .iter()
        .enumerate()
        .map(|(index, action)| CodeActionJson {
            index,
            title: match action {
                lsp::CodeActionOrCommand::Command(command) => command.title.clone(),
                lsp::CodeActionOrCommand::CodeAction(action) => action.title.clone(),
            },
            kind: match action {
                lsp::CodeActionOrCommand::Command(_) => None,
                lsp::CodeActionOrCommand::CodeAction(action) => {
                    action.kind.as_ref().map(|kind| kind.as_str().to_owned())
                }
            },
            is_preferred: matches!(
                action,
                lsp::CodeActionOrCommand::CodeAction(action) if action.is_preferred == Some(true)
            ),
            disabled: action_disabled_reason(action),
        })
        .collect();
    serde_json::to_value(CodeActionsPayload { actions }).unwrap_or(serde_json::Value::Null)
}

/// What an edit will touch, said before it is applied — the payload a rename,
/// a formatting or a resolved action settles with, so the UI can say
/// "3 files, 14 edits" and then ask for the apply.
fn edit_summary_json(edit: &lsp::WorkspaceEdit) -> serde_json::Value {
    let mut files = 0usize;
    let mut edits = 0usize;
    let mut resource_ops = false;
    if let Some(changes) = &edit.changes {
        files += changes.len();
        edits += changes.values().map(Vec::len).sum::<usize>();
    }
    match &edit.document_changes {
        Some(lsp::DocumentChanges::Edits(documents)) => {
            files += documents.len();
            edits += documents.iter().map(|doc| doc.edits.len()).sum::<usize>();
        }
        Some(lsp::DocumentChanges::Operations(operations)) => {
            for operation in operations {
                match operation {
                    lsp::DocumentChangeOperation::Edit(doc) => {
                        files += 1;
                        edits += doc.edits.len();
                    }
                    lsp::DocumentChangeOperation::Op(_) => resource_ops = true,
                }
            }
        }
        None => {}
    }
    serde_json::json!({ "files": files, "edits": edits, "resource_ops": resource_ops })
}

/// [`edit_summary_json`] over several edits at once — the shape is the same,
/// so the UI reads a code-actions-on-format answer as it reads a formatting.
fn edit_list_summary_json(edits: &[lsp::WorkspaceEdit]) -> serde_json::Value {
    let mut files = 0u64;
    let mut count = 0u64;
    let mut resource_ops = false;
    for edit in edits {
        let summary = edit_summary_json(edit);
        files += summary["files"].as_u64().unwrap_or(0);
        count += summary["edits"].as_u64().unwrap_or(0);
        resource_ops |= summary["resource_ops"].as_bool().unwrap_or(false);
    }
    serde_json::json!({ "files": files, "edits": count, "resource_ops": resource_ops })
}

// ---------------------------------------------------------------------------
// Applying a WorkspaceEdit
// ---------------------------------------------------------------------------

/// One file's slice of a normalized [`lsp::WorkspaceEdit`].
struct FileEdits {
    /// Canonicalized, for the same reason every path in this module is.
    path: PathBuf,
    /// The LSP document version the server dated its edits against, when it
    /// did. Checked against the open document before anything is applied.
    version: Option<i32>,
    edits: Vec<lsp::TextEdit>,
}

/// Flatten the three shapes a [`lsp::WorkspaceEdit`] comes in into one list of
/// per-file edit sets, refusing what this client cannot do yet.
fn normalize_workspace_edit(edit: lsp::WorkspaceEdit) -> Result<Vec<FileEdits>, String> {
    let mut files: Vec<FileEdits> = Vec::new();
    let mut push = |uri: Uri, version: Option<i32>, edits: Vec<lsp::TextEdit>| -> Result<(), String> {
        let path = uri
            .to_file_path()
            .map_err(|()| format!("the edit names a URI that is not a file: {uri}"))?;
        let path = std::fs::canonicalize(&path).unwrap_or(path);
        files.push(FileEdits { path, version, edits });
        Ok(())
    };
    if let Some(changes) = edit.changes {
        for (uri, edits) in changes {
            push(uri, None, edits)?;
        }
    }
    match edit.document_changes {
        Some(lsp::DocumentChanges::Edits(documents)) => {
            for document in documents {
                let edits = plain_edits(document.edits);
                push(document.text_document.uri, document.text_document.version, edits)?;
            }
        }
        Some(lsp::DocumentChanges::Operations(operations)) => {
            for operation in operations {
                match operation {
                    lsp::DocumentChangeOperation::Edit(document) => {
                        let edits = plain_edits(document.edits);
                        push(
                            document.text_document.uri,
                            document.text_document.version,
                            edits,
                        )?;
                    }
                    // Creating, renaming and deleting files is real work this
                    // client has not built yet — refusing the whole edit is
                    // honest where applying half of it would corrupt.
                    lsp::DocumentChangeOperation::Op(_) => {
                        return Err(
                            "the edit creates, renames or deletes files, \
                             which is not supported yet"
                                .to_owned(),
                        );
                    }
                }
            }
        }
        None => {}
    }
    // Sorted so a multi-file receipt reads in one order every time.
    files.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(files)
}

/// A document's edits, whatever mix of plain, annotated and snippet forms
/// they arrived in, as plain text edits.
fn plain_edits(edits: Vec<lsp::Edit>) -> Vec<lsp::TextEdit> {
    edits
        .into_iter()
        .map(|edit| match edit {
            lsp::Edit::Plain(edit) => edit,
            lsp::Edit::Annotated(edit) => edit.text_edit,
            // rust-analyzer sends snippet edits because the capabilities say
            // we take them (workspace_edit.snippet_edit_support, vendored) —
            // there is no caret to place from here, so the placeholders
            // collapse to their text.
            lsp::Edit::Snippet(edit) => lsp::TextEdit {
                range: edit.range,
                new_text: strip_snippet(&edit.snippet.value),
            },
        })
        .collect()
}

/// LSP snippet syntax, reduced to the text it would leave behind: `$0` and
/// `$1` vanish, `${1:body}` keeps its body, `${1|a,b|}` keeps its first
/// choice, `\$` keeps its dollar. Nested placeholders unwrap recursively.
fn strip_snippet(snippet: &str) -> String {
    let mut out = String::with_capacity(snippet.len());
    let mut chars = snippet.chars().peekable();
    while let Some(ch) = chars.next() {
        match ch {
            '\\' => {
                // An escaped character stands for itself; a trailing
                // backslash is kept as one.
                match chars.next() {
                    Some(escaped) => out.push(escaped),
                    None => out.push('\\'),
                }
            }
            '$' => match chars.peek() {
                // `$1` — a bare tabstop: nothing is left behind.
                Some(digit) if digit.is_ascii_digit() => {
                    while chars.peek().is_some_and(char::is_ascii_digit) {
                        chars.next();
                    }
                }
                Some('{') => {
                    chars.next();
                    // `${1:body}` / `${1|a,b|}` / `${1}` — collect to the
                    // matching brace, then keep what the placeholder keeps.
                    let mut depth = 1;
                    let mut inner = String::new();
                    for ch in chars.by_ref() {
                        match ch {
                            '{' => depth += 1,
                            '}' => {
                                depth -= 1;
                                if depth == 0 {
                                    break;
                                }
                            }
                            _ => {}
                        }
                        inner.push(ch);
                    }
                    if let Some(body) = inner.split_once(':').map(|(_, body)| body) {
                        out.push_str(&strip_snippet(body));
                    } else if let Some(choices) = inner.split_once('|').map(|(_, rest)| rest) {
                        if let Some(first) = choices.trim_end_matches('|').split(',').next() {
                            out.push_str(first);
                        }
                    }
                    // `${1}` keeps nothing.
                }
                _ => out.push('$'),
            },
            _ => out.push(ch),
        }
    }
    out
}

/// Apply [`lsp::TextEdit`]s to a document held as text — the closed-file
/// half of the applier, and pure so it is testable on the host.
///
/// Positions are UTF-16 (line, character), clamped the way the spec says: a
/// line past the end means the end of the document, a character past a line's
/// end means the end of that line. Edits are applied back to front against
/// one snapshot, which LSP's no-overlap rule makes safe.
fn apply_edits_to_text(text: &str, edits: &[lsp::TextEdit]) -> String {
    // Byte offset of each line start, plus one payload-end sentinel.
    let mut line_starts: Vec<usize> = vec![0];
    for (index, byte) in text.bytes().enumerate() {
        if byte == b'\n' {
            line_starts.push(index + 1);
        }
    }
    let offset_of = |position: Position| -> usize {
        let Some(&line_start) = line_starts.get(position.line as usize) else {
            return text.len();
        };
        let line_end = line_starts
            .get(position.line as usize + 1)
            .map(|next| next - 1)
            .unwrap_or(text.len());
        let line = &text[line_start..line_end];
        let mut utf16 = 0u32;
        for (byte_index, ch) in line.char_indices() {
            if utf16 >= position.character {
                return line_start + byte_index;
            }
            utf16 += ch.len_utf16() as u32;
        }
        line_end
    };
    let mut resolved: Vec<(usize, usize, &str)> = edits
        .iter()
        .map(|edit| {
            let start = offset_of(edit.range.start);
            let end = offset_of(edit.range.end).max(start);
            (start, end, edit.new_text.as_str())
        })
        .collect();
    resolved.sort_by_key(|(start, end, _)| (*start, *end));
    let mut out = text.to_owned();
    for (start, end, new_text) in resolved.into_iter().rev() {
        out.replace_range(start..end, new_text);
    }
    out
}

/// One applied file, in the receipt [`Engine::lsp_apply_pending_edit`] hands
/// back so the UI knows which of its editors to resync.
#[derive(serde::Serialize)]
pub struct AppliedFile {
    /// Absolute host path, canonical — match it against open tabs.
    pub path: String,
    /// The open buffer it landed in, or null for a file edited on disk.
    pub buffer_id: Option<BufferId>,
    pub edits: usize,
}

/// What applying a stored edit did.
#[derive(serde::Serialize)]
pub struct ApplyReceipt {
    pub applied: bool,
    /// Why nothing (or not everything) was applied. Null on success.
    pub error: Option<String>,
    pub files: Vec<AppliedFile>,
}

impl ApplyReceipt {
    fn refused(error: String) -> Self {
        Self {
            applied: false,
            error: Some(error),
            files: Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    // -----------------------------------------------------------------------
    // Starting a server
    // -----------------------------------------------------------------------

    /// An engine with a userland whose files actually exist, because
    /// `Userland::is_installed` looks.
    fn engine_with_userland(dir: &Path) -> Engine {
        let proot = dir.join("libproot_exec.so");
        let rootfs = dir.join("debian");
        std::fs::write(&proot, "").unwrap();
        std::fs::create_dir_all(&rootfs).unwrap();
        std::fs::create_dir_all(dir.join("projects")).unwrap();
        let engine = Engine::new();
        engine.set_userland(&proot, &rootfs, dir, &dir.join("projects"), "");
        engine
    }

    /// The command line a language server is started with, spelled out.
    ///
    /// This is the whole of route (1): Zed's `LanguageServer::new` execs
    /// `path` with `arguments`, so if proot's flags are not in this list they
    /// are nowhere, and the failure is a server that cannot see the project or
    /// cannot find its own libraries — quiet, on a device, at the far end of a
    /// pipe.
    #[test]
    fn a_server_is_started_as_proot_with_the_server_as_its_tail() {
        let dir = tempfile::tempdir().unwrap();
        let engine = engine_with_userland(dir.path());
        let userland = engine.userland().expect("a userland");
        let root = dir.path().join("projects").join("thing");
        std::fs::create_dir_all(&root).unwrap();

        let (server, language_id) = server_for("rust").expect("rust has a server");
        assert_eq!(language_id, "rust");
        let binary = server_binary(&userland, &server, &root, None, &Default::default());

        assert_eq!(binary.path, dir.path().join("libproot_exec.so"));
        let args: Vec<String> = binary
            .arguments
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        // proot's own flags, in guest.rs's order...
        assert_eq!(
            args[..6],
            [
                "-0",
                "--kill-on-exit",
                "--link2symlink",
                "-k",
                "6.2.1",
                "-r"
            ]
        );
        // ...the rootfs, the three system binds, the projects bind...
        assert!(args.contains(&"/proc".to_owned()));
        // ...the project as the working directory, and the server argv last,
        // which is the whole contract: everything after `-w <dir>` is the
        // guest's command line.
        let w = args.iter().position(|arg| arg == "-w").expect("a -w");
        assert_eq!(args[w + 1], root.to_string_lossy());
        assert_eq!(args[w + 2..], ["rust-analyzer".to_owned()]);

        // The guest environment travels with it: without a guest PATH, every
        // program inside the fake root is "command not found".
        let env = binary.env.expect("an environment");
        assert_eq!(env.get("HOME").map(String::as_str), Some("/root"));
        assert!(
            env.get("PATH")
                .is_some_and(|path| path.contains("/usr/bin"))
        );
        assert!(env.contains_key("PROOT_TMP_DIR"));
        // And rust-analyzer alone is pointed at the editor's toolchain: the
        // shim resolves it there, and cargo with it, so the proc-macro server
        // it finds was built by the compiler that builds the macros.
        assert_eq!(
            env.get("RUSTUP_TOOLCHAIN").map(String::as_str),
            Some(EDITOR_TOOLCHAIN)
        );
    }

    /// `lsp.<server>.binary`, Zed's rule: a `path` replaces the program and
    /// brings only the arguments written beside it; `arguments` alone keep
    /// the program and replace the table's flags; an `env` rides along.
    #[test]
    fn the_binary_setting_overrides_the_built_in_argv() {
        use crate::config::BinarySettings;
        use std::collections::BTreeMap;
        let (server, _) = server_for("c").unwrap();
        let strings = |argv: Vec<OsString>| -> Vec<String> {
            argv.into_iter()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect()
        };
        assert_eq!(
            strings(server_argv(&server, None)),
            ["clangd", "--background-index"]
        );
        let path_only = BinarySettings {
            path: Some("/opt/llvm/bin/clangd".to_owned()),
            ..Default::default()
        };
        // The table's `--background-index` belongs to the table's program.
        assert_eq!(
            strings(server_argv(&server, Some(&path_only))),
            ["/opt/llvm/bin/clangd"]
        );
        let with_args = BinarySettings {
            path: Some("/opt/llvm/bin/clangd".to_owned()),
            arguments: Some(vec!["--log=verbose".to_owned()]),
            ..Default::default()
        };
        assert_eq!(
            strings(server_argv(&server, Some(&with_args))),
            ["/opt/llvm/bin/clangd", "--log=verbose"]
        );
        let args_only = BinarySettings {
            arguments: Some(vec!["-j=2".to_owned()]),
            ..Default::default()
        };
        assert_eq!(strings(server_argv(&server, Some(&args_only))), ["clangd", "-j=2"]);
        // An empty path is no path.
        let blank = BinarySettings {
            path: Some("  ".to_owned()),
            ..Default::default()
        };
        assert_eq!(
            strings(server_argv(&server, Some(&blank))),
            ["clangd", "--background-index"]
        );

        // The whole proot line, with the env in it.
        let dir = tempfile::tempdir().unwrap();
        let engine = engine_with_userland(dir.path());
        let userland = engine.userland().expect("a userland");
        let root = dir.path().join("projects").join("thing");
        std::fs::create_dir_all(&root).unwrap();
        let with_env = BinarySettings {
            path: Some("/root/.cargo/bin/rust-analyzer".to_owned()),
            env: Some(BTreeMap::from([("RA_LOG".to_owned(), "info".to_owned())])),
            ..Default::default()
        };
        let (rust, _) = server_for("rust").unwrap();
        let binary = server_binary(&userland, &rust, &root, Some(&with_env), &Default::default());
        let args: Vec<String> = binary
            .arguments
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        let w = args.iter().position(|arg| arg == "-w").expect("a -w");
        assert_eq!(args[w + 2..], ["/root/.cargo/bin/rust-analyzer".to_owned()]);
        let env = binary.env.expect("an environment");
        assert_eq!(env.get("RA_LOG").map(String::as_str), Some("info"));
        // The guest environment is still underneath it.
        assert_eq!(env.get("HOME").map(String::as_str), Some("/root"));
    }

    /// `workspace/configuration`, answered out of `lsp.<server>.settings`:
    /// the whole object without a section, the section's value with one, a
    /// dotted section walked into the nested object Zed's docs prescribe,
    /// and null — "use your defaults" — for what is not there.
    #[test]
    fn workspace_configuration_is_answered_by_section() {
        let settings = serde_json::json!({
            "tailwindCSS": { "emmetCompletions": true },
            "rust-analyzer": { "check": { "command": "clippy" } },
            "gopls": { "staticcheck": true }
        });
        assert_eq!(configuration_section(&settings, None), settings);
        assert_eq!(configuration_section(&settings, Some("")), settings);
        assert_eq!(
            configuration_section(&settings, Some("gopls")),
            serde_json::json!({ "staticcheck": true })
        );
        assert_eq!(
            configuration_section(&settings, Some("rust-analyzer.check")),
            serde_json::json!({ "command": "clippy" })
        );
        assert_eq!(
            configuration_section(&settings, Some("rust-analyzer.check.command")),
            serde_json::json!("clippy")
        );
        assert_eq!(
            configuration_section(&settings, Some("pylsp")),
            serde_json::Value::Null
        );
        // Nothing configured: the emptiest legal reply, as before.
        assert_eq!(
            configuration_section(&serde_json::Value::Null, Some("gopls")),
            serde_json::Value::Null
        );
    }

    /// One server per *server*, not per language: clangd is started once for a
    /// project holding both C and C++, and typescript-language-server once for
    /// both TypeScript and TSX.
    #[test]
    fn languages_that_share_a_server_share_its_key() {
        let (c, c_id) = server_for("c").unwrap();
        let (cpp, cpp_id) = server_for("cpp").unwrap();
        assert_eq!(c.name, cpp.name);
        assert_eq!(c.argv, cpp.argv);
        // ...but each keeps its own `languageId`, which is what `didOpen`
        // carries and what a server switches dialect on.
        assert_eq!((c_id, cpp_id), ("c", "cpp"));

        let (ts, ts_id) = server_for("typescript").unwrap();
        let (tsx, tsx_id) = server_for("tsx").unwrap();
        assert_eq!(ts.name, tsx.name);
        assert_eq!((ts_id, tsx_id), ("typescript", "typescriptreact"));

        // A grammar we highlight but Debian packages no server for has none,
        // which is a normal state and not a hole.
        assert!(server_for("markdown").is_none());
        assert!(server_for("yaml").is_none());
    }

    // -----------------------------------------------------------------------
    // The process budget (P5-4)
    // -----------------------------------------------------------------------

    /// The arithmetic, pinned. Everything about the cap is a judgement call
    /// about a number, so the number is the test: a later change to what the
    /// terminal or apt reserve should be a deliberate edit here and a red test
    /// if it is not.
    #[test]
    fn the_budget_leaves_room_for_four_servers() {
        // Two processes per guest run — proot and its tracee — plus one for the
        // checker a server forks (cargo, go list, pyflakes).
        assert_eq!(guest::PROCESSES_PER_RUN, 2);
        assert_eq!(PROCESSES_PER_SERVER, 3);
        // 8 for the terminal, 6 for git, 6 for apt.
        assert_eq!(RESERVED_PROCESSES, 20);
        // (32 - 20) / 3.
        assert_eq!(guest::PROCESS_BUDGET, 32);
        assert_eq!(MAX_RUNNING_SERVERS, 4);
        // And four servers at their peak still fit inside what is left, which
        // is the property the division is standing in for. Checked at compile
        // time, because a reservation edited to something that does not fit
        // should not build at all.
        const {
            assert!(
                MAX_RUNNING_SERVERS * PROCESSES_PER_SERVER + RESERVED_PROCESSES
                    <= guest::PROCESS_BUDGET
            )
        };
        // And with the ACP agent's share carved out (P6), the live cap drops
        // to two rather than going negative or staying four.
        const {
            assert!(
                (guest::PROCESS_BUDGET - RESERVED_PROCESSES - crate::acp::PROCESSES_PER_AGENT)
                    / PROCESSES_PER_SERVER
                    == 2
            )
        };
    }

    fn fake_server(name: &'static str) -> Server {
        Server { name, argv: &[], env: &[] }
    }

    /// The cap counts *processes*, not entries — and it counts them across
    /// every project, because the budget belongs to the app.
    #[test]
    fn the_cap_counts_running_servers_across_every_project() {
        // The cap is named explicitly so a concurrently running ACP test —
        // whose agent reservation legitimately shrinks the live cap — cannot
        // make this arithmetic flaky.
        let cap = MAX_RUNNING_SERVERS;
        let mut servers = SlotMap::new();
        for name in ["one", "two", "three", "four"] {
            assert_eq!(claim(&mut servers, 1, fake_server(name), cap), Claim::Room);
        }
        assert_eq!(
            claim(&mut servers, 1, fake_server("five"), cap),
            Claim::Full
        );
        // A second project does not get a budget of its own.
        assert_eq!(
            claim(&mut servers, 2, fake_server("five"), cap),
            Claim::Full
        );

        // A refusal is a record, not a process: it does not count against the
        // cap, and it is not claimed twice.
        servers.insert((1, "five"), Slot::refused(1, fake_server("five")));
        assert_eq!(
            claim(&mut servers, 1, fake_server("five"), cap),
            Claim::Taken
        );
        assert_eq!(running_servers(&servers), MAX_RUNNING_SERVERS);

        // One stops; the room it frees is anybody's.
        servers.remove(&(1, "one"));
        assert_eq!(claim(&mut servers, 2, fake_server("six"), cap), Claim::Room);
    }

    fn running_slot(project: ProjectId, server: Server, last_activity: Instant) -> Slot {
        Slot {
            state: ServerState::Running,
            last_activity,
            ..Slot::starting(project, server)
        }
    }

    fn open_doc(project: ProjectId, server: &'static str, path: &Path) -> OpenDoc {
        OpenDoc {
            project,
            server,
            path: path.to_path_buf(),
            uri: Uri::from_file_path(path).unwrap(),
            grammar: "rust",
            language_id: "rust",
            lsp_version: 1,
            opened: true,
            dormant: false,
        }
    }

    /// The cap, and what it costs the user: a sentence, never a crash and never
    /// a silently missing feature.
    #[test]
    fn a_server_past_the_cap_is_refused_with_a_sentence_and_retried_when_there_is_room() {
        let dir = tempfile::tempdir().unwrap();
        let engine = engine_with_userland(dir.path());
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let project = engine.open_project(file.parent().unwrap());

        // The budget is already spent, and every server holding it has a
        // document open — so the pressure sweep has nothing it is allowed to
        // take. (Fake servers: what is under test is the accounting, and a real
        // one would need a rootfs.)
        let names = ["one", "two", "three", "four"];
        assert_eq!(names.len(), MAX_RUNNING_SERVERS);
        for (index, name) in names.iter().enumerate() {
            let path = dir.path().join(format!("{name}.rs"));
            engine.lsp.servers.lock().unwrap().insert(
                (project, name),
                running_slot(project, fake_server(name), Instant::now()),
            );
            engine
                .lsp
                .docs
                .lock()
                .unwrap()
                .insert(1000 + index as u64, open_doc(project, name, &path));
        }

        // Opening a Rust file now asks for a fifth server.
        let buffer = engine.open_file(&file).unwrap();
        assert!(engine.lsp.docs.lock().unwrap().contains_key(&buffer));

        let status = engine
            .lsp_servers(project)
            .into_iter()
            .find(|status| status.name == "rust-analyzer")
            .expect("the refusal is reported, not swallowed");
        assert_eq!(status.state, ServerState::Unavailable);
        assert_eq!(status.error.as_deref(), Some(CAP_REACHED));
        // Nothing was started and — the part that matters on a phone — nothing
        // that was already running was killed to make room.
        assert_eq!(
            running_servers(&engine.lsp.servers.lock().unwrap()),
            MAX_RUNNING_SERVERS
        );

        // Their tabs close. Inside the grace nothing happens: closing the last
        // `.rs` tab on the way to opening another one must not cost a restart.
        engine.lsp.docs.lock().unwrap().retain(|id, _| *id < 1000);
        assert_eq!(engine.sweep_idle_servers(false), 0);

        // Past it, they go, and the refusal is withdrawn. (`retry_capped_servers`
        // rather than a poll, because a poll would go on to start a real
        // rust-analyzer against a rootfs this test does not have.)
        let aged = Instant::now()
            .checked_sub(IDLE_WITHOUT_DOCUMENTS + Duration::from_secs(1))
            .expect("the machine has been up for a minute");
        for slot in engine.lsp.servers.lock().unwrap().values_mut() {
            slot.last_activity = aged;
        }
        assert_eq!(engine.sweep_idle_servers(false), MAX_RUNNING_SERVERS);
        engine.retry_capped_servers(project);
        assert!(
            !engine
                .lsp
                .servers
                .lock()
                .unwrap()
                .contains_key(&(project, "rust-analyzer")),
            "with room in the budget the refusal is dropped, so the next poll starts it"
        );
    }

    /// The whole idle policy, as a table.
    #[test]
    fn retire_stops_the_docless_and_the_silent_and_nothing_else() {
        let moment = Duration::from_secs(1);

        // Documents open and traffic recently: the normal state of a server
        // being used, and it must survive every sweep.
        assert_eq!(retire(1, false, moment, false), Retire::Keep);
        assert_eq!(retire(1, false, IDLE_WITHOUT_DOCUMENTS, false), Retire::Keep);

        // The last document closed and the tree does not call for the
        // language. Zed drops the registration here; we drop the process
        // too, but only after the grace.
        assert_eq!(retire(0, false, moment, false), Retire::Keep);
        assert_eq!(
            retire(0, false, IDLE_WITHOUT_DOCUMENTS, false),
            Retire::NoDocuments
        );

        // Under pressure — somebody is being refused right now — the grace is
        // over at once.
        assert_eq!(retire(0, false, Duration::ZERO, true), Retire::NoDocuments);

        // The backstop Zed does not have: documents open, but nothing has
        // crossed the pipes in ten minutes.
        assert_eq!(retire(2, false, IDLE_WITHOUT_TRAFFIC, false), Retire::Silent);
        assert_eq!(
            retire(2, false, IDLE_WITHOUT_TRAFFIC - moment, false),
            Retire::Keep
        );
        // And pressure does not shorten *that* one: a document on screen is a
        // server the user may still be reading, and evicting it to start
        // another would leave two cold indexes serving one file each.
        assert_eq!(retire(2, false, moment, true), Retire::Keep);

        // A server the project's tree calls for is docless by *design*: no
        // tab open is its working state, so it is judged by the silence rule.
        assert_eq!(retire(0, true, IDLE_WITHOUT_DOCUMENTS, false), Retire::Keep);
        assert_eq!(retire(0, true, IDLE_WITHOUT_TRAFFIC, false), Retire::Silent);
        // …until the budget is contested: then a docless server loses to one
        // actually serving a tab, whatever the tree says.
        assert_eq!(retire(0, true, Duration::ZERO, true), Retire::NoDocuments);
        assert_eq!(retire(1, true, moment, true), Retire::Keep);
    }

    /// The sweep itself: which slots go, what happens to their documents, and
    /// what the UI is told.
    #[test]
    fn the_sweep_stops_the_idle_and_leaves_their_documents_dormant() {
        let slots: Slots = Slots::default();
        let docs: Docs = Docs::default();
        let store = DiagnosticStore::default();

        // Times are built forwards from a base and handed to `sweep`, so the
        // test names every duration and no clock is consulted twice.
        let base = Instant::now();
        let now = base + IDLE_WITHOUT_TRAFFIC + Duration::from_secs(60);
        {
            let mut servers = slots.lock().unwrap();
            servers.insert((7, "busy"), running_slot(7, fake_server("busy"), now));
            servers.insert((7, "quiet"), running_slot(7, fake_server("quiet"), base));
            servers.insert(
                (7, "empty"),
                running_slot(7, fake_server("empty"), now - IDLE_WITHOUT_DOCUMENTS),
            );
            // A slot holding no process is not something the sweep can free.
            let mut refused = Slot::refused(7, fake_server("refused"));
            refused.last_activity = base;
            servers.insert((7, "refused"), refused);

            let mut open = docs.lock().unwrap();
            open.insert(1, open_doc(7, "busy", Path::new("/p/a.rs")));
            open.insert(2, open_doc(7, "quiet", Path::new("/p/b.rs")));
        }

        let wants = ProjectWants::default();
        let rested = Rested::default();
        let stopped = sweep(&slots, &docs, &store, &wants, &rested, now, false);
        assert_eq!(stopped.len(), 2, "the silent one and the docless one");

        let servers = slots.lock().unwrap();
        assert!(servers.contains_key(&(7, "busy")), "in use, so untouched");
        assert!(servers.contains_key(&(7, "refused")), "nothing to stop");
        assert!(!servers.contains_key(&(7, "quiet")));
        assert!(!servers.contains_key(&(7, "empty")));
        drop(servers);

        // The silent server's document is still the user's — it is on screen —
        // but nothing is watching it, and it must not be resurrected by the
        // next poll.
        let open = docs.lock().unwrap();
        assert!(open[&2].dormant);
        assert!(!open[&2].opened);
        assert!(
            !open[&1].dormant,
            "a server still running keeps its documents"
        );
        drop(open);

        // And the status bar is told, because a server leaving the list changes
        // what `lsp_servers` answers.
        assert!(store.version(7) > 0);
    }

    /// Pressure shortens the grace for a server with nothing open, and does not
    /// touch one that is in use — the rule that keeps the cap from turning into
    /// two servers taking turns to index.
    #[test]
    fn pressure_takes_the_docless_and_never_the_busy() {
        let slots: Slots = Slots::default();
        let docs: Docs = Docs::default();
        let store = DiagnosticStore::default();
        let wants = ProjectWants::default();
        let rested = Rested::default();
        let now = Instant::now();
        {
            slots
                .lock()
                .unwrap()
                .insert((1, "idle"), running_slot(1, fake_server("idle"), now));
            slots
                .lock()
                .unwrap()
                .insert((1, "busy"), running_slot(1, fake_server("busy"), now));
            docs.lock()
                .unwrap()
                .insert(1, open_doc(1, "busy", Path::new("/p/a.rs")));
        }

        assert!(
            sweep(&slots, &docs, &store, &wants, &rested, now, false).is_empty(),
            "inside the grace, an unpressed sweep takes nothing"
        );
        let stopped = sweep(&slots, &docs, &store, &wants, &rested, now, true);
        assert_eq!(stopped.len(), 1);
        let servers = slots.lock().unwrap();
        assert!(servers.contains_key(&(1, "busy")));
        assert!(!servers.contains_key(&(1, "idle")));
    }

    /// Opening a folder is what starts its tooling: a project of `.rs` files
    /// wants rust-analyzer with no tab open at all. The mirror fills
    /// asynchronously and the poll is what starts servers, so the test polls
    /// exactly the way the UI does.
    #[test]
    fn opening_a_folder_starts_its_languages_servers() {
        let dir = tempfile::tempdir().unwrap();
        let engine = engine_with_userland(dir.path());
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let project = engine.open_project(file.parent().unwrap());

        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            engine.lsp_version(project);
            if engine
                .lsp
                .servers
                .lock()
                .unwrap()
                .contains_key(&(project, "rust-analyzer"))
            {
                break;
            }
            assert!(
                Instant::now() < deadline,
                "the scanned tree never produced a server want"
            );
            std::thread::sleep(Duration::from_millis(25));
        }
        // …and no document was ever opened: the folder alone was the reason.
        assert!(engine.lsp.docs.lock().unwrap().is_empty());
    }

    /// A stopped server comes back when the user comes back — and *only* then.
    /// Polling starting it again would undo the sweep on the very next frame,
    /// which is the loop `dormant` exists to break.
    #[test]
    fn a_dormant_document_is_woken_by_typing_rather_than_by_polling() {
        let dir = tempfile::tempdir().unwrap();
        let engine = engine_with_userland(dir.path());
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let project = engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        engine.lsp.live.store(true, Ordering::Relaxed);

        // As the sweep leaves things: the slot gone, the document still open
        // on screen and marked dormant, and the tree's want at rest — the
        // sweep sets all three together, and the poll honours each.
        engine.lsp.servers.lock().unwrap().clear();
        {
            let mut docs = engine.lsp.docs.lock().unwrap();
            let doc = docs.get_mut(&buffer).expect("the document is registered");
            doc.opened = false;
            doc.dormant = true;
        }
        engine
            .lsp
            .rested
            .lock()
            .unwrap()
            .insert((project, "rust-analyzer"));

        engine.lsp_version(project);
        assert!(
            engine.lsp.docs.lock().unwrap()[&buffer].dormant,
            "a poll must not restart a server the sweep just stopped"
        );
        assert!(engine.lsp.servers.lock().unwrap().is_empty());

        engine.edit(buffer, 0, 0, "// ").unwrap();
        assert!(
            !engine.lsp.docs.lock().unwrap()[&buffer].dormant,
            "typing in the file is the evidence that the user wants it back"
        );
        assert!(
            !engine
                .lsp
                .rested
                .lock()
                .unwrap()
                .contains(&(project, "rust-analyzer")),
            "the wake ends the tree-want's rest along with the dormancy"
        );
    }

    // -----------------------------------------------------------------------
    // The inert paths
    // -----------------------------------------------------------------------

    fn project_with_file(dir: &Path, name: &str, text: &str) -> PathBuf {
        let root = dir.join("projects").join("thing");
        std::fs::create_dir_all(&root).unwrap();
        let file = root.join(name);
        std::fs::write(&file, text).unwrap();
        file
    }

    /// The `play` flavour, and every `full` build before the user installs
    /// Debian: opening a Rust file starts nothing, reports nothing, and — the
    /// part that matters on the keystroke path — leaves the engine's edit path
    /// exactly as it was.
    #[test]
    fn without_a_userland_nothing_starts_and_nothing_complains() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");

        let engine = Engine::new();
        let project = engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();

        // The document *is* registered, so that a userland appearing later can
        // start a server for a file that is already open...
        assert_eq!(engine.lsp.docs.lock().unwrap().len(), 1);
        // ...but nothing is running, so the edit path stays free.
        assert!(!engine.lsp_is_live());
        assert!(engine.lsp.servers.lock().unwrap().is_empty());

        assert_eq!(engine.lsp_version(project), 0);
        assert!(engine.lsp_servers(project).is_empty());
        assert_eq!(engine.buffer_diagnostics_version(buffer), 0);
        assert!(engine.buffer_diagnostics(buffer).rows.is_empty());
        assert_eq!(engine.lsp_diagnostics(project).totals, Counts::default());

        // A request is answered rather than refused: the UI has one code path
        // whether or not there is a server behind it.
        let id = engine.lsp_request_completion(buffer, 0, 3);
        let result = engine.lsp_request_result(id);
        assert_eq!(result.state, RequestState::Unavailable);
        assert_eq!(result.kind, RequestKind::Completion);
        assert_eq!(result.buffer_id, buffer);

        // Editing it is still just an edit.
        engine.edit(buffer, 0, 0, "// ").unwrap();
        assert!(engine.lsp.last_change.lock().unwrap().is_none());

        // And closing takes the registration with it rather than leaking one
        // entry per file the user ever opened.
        assert!(engine.close_buffer(buffer));
        assert!(engine.lsp.docs.lock().unwrap().is_empty());
    }

    /// A file opened before its project — a recent-files entry restored at
    /// launch — has no project to belong to when `open_file` runs, and would
    /// otherwise never get a server no matter how long it stayed open.
    #[test]
    fn a_file_opened_before_its_project_is_adopted() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");

        let engine = Engine::new();
        let buffer = engine.open_file(&file).unwrap();
        assert!(
            engine.lsp.docs.lock().unwrap().is_empty(),
            "nothing to belong to yet"
        );

        let project = engine.open_project(file.parent().unwrap());
        engine.adopt_open_buffers(project);
        let docs = engine.lsp.docs.lock().unwrap();
        assert_eq!(docs.len(), 1);
        assert_eq!(docs[&buffer].project, project);
        assert_eq!(docs[&buffer].server, "rust-analyzer");
        assert_eq!(docs[&buffer].language_id, "rust");
    }

    /// A language with no server is inert for a different reason, and just as
    /// quietly: nothing is registered at all.
    #[test]
    fn a_language_with_no_server_registers_nothing() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "README.md", "# hi\n");

        let engine = Engine::new();
        engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        assert_eq!(engine.buffer_language(buffer), Some("markdown"));
        assert!(engine.lsp.docs.lock().unwrap().is_empty());
    }

    // -----------------------------------------------------------------------
    // didChange: UTF-16, and the old text
    // -----------------------------------------------------------------------

    /// A `didChange` range is measured in UTF-16 code units, on the text as it
    /// was *before* the edit. Both halves are load-bearing and neither is
    /// visible from outside: a client that sends byte columns, or that measures
    /// the new text, desynchronizes the server silently — it goes on answering,
    /// about a document nobody has.
    #[test]
    fn a_change_is_a_utf16_range_in_the_old_text() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        // '€' is three UTF-8 bytes and one UTF-16 unit; '𝄞' is four bytes and
        // *two* UTF-16 units, which is the case a naive "chars" count gets
        // wrong as well.
        let file = project_with_file(dir.path(), "main.rs", "let a = \"€𝄞\"; let b = 1;\n");
        let engine = Engine::new();
        engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        // Stand in for a server having started, which is all `lsp_did_change`
        // needs to run far enough to record the change.
        engine.lsp.live.store(true, Ordering::Relaxed);

        // Replace the `1` with `2`. `let a = "€𝄞"; let b = ` is 23 UTF-16 units
        // (9 + 1 + 2 + 11) but 27 bytes.
        let text = engine.text(buffer).unwrap();
        let byte = text.find('1').unwrap();
        assert_eq!(byte, 27, "27 bytes of prefix...");
        engine.edit(buffer, byte, byte + 1, "2").unwrap();

        let change = engine.lsp.last_change.lock().unwrap().take().unwrap();
        assert_eq!(
            change.start,
            PointUtf16::new(0, 23),
            "...but 23 UTF-16 units"
        );
        assert_eq!(change.old_end, PointUtf16::new(0, 24));
        assert_eq!(change.text, "2");
        assert_eq!(change.buffer_version, engine.version(buffer).unwrap());
        // Nobody asked for whole-document sync, so the O(file) copy was not
        // made.
        assert!(change.whole.is_none());

        // ...and that is what goes on the wire.
        let event = content_changes(change, TextDocumentSyncKind::INCREMENTAL).unwrap();
        assert_eq!(
            event[0].range,
            Some(Range::new(Position::new(0, 23), Position::new(0, 24)))
        );
        assert_eq!(event[0].text, "2");
    }

    /// Undo has no edit shape to report, so it reports one range covering the
    /// whole old document — an ordinary incremental change, which is what keeps
    /// this to a single code path.
    #[test]
    fn undo_replaces_the_whole_document() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn a() {}\nfn b() {}\n");
        let engine = Engine::new();
        engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        engine.lsp.live.store(true, Ordering::Relaxed);

        engine.edit(buffer, 0, 0, "// ").unwrap();
        engine.lsp.last_change.lock().unwrap().take();
        engine.undo(buffer).unwrap();

        let change = engine.lsp.last_change.lock().unwrap().take().unwrap();
        assert_eq!(change.start, PointUtf16::new(0, 0));
        // The end of the document *before* the undo: three characters longer
        // on its first line, and a trailing empty line after the final "\n".
        assert_eq!(change.old_end, PointUtf16::new(2, 0));
        assert_eq!(change.text, "fn a() {}\nfn b() {}\n");
    }

    /// A server that negotiated whole-document sync gets the whole document,
    /// and one that negotiated nothing gets nothing at all.
    #[test]
    fn the_sync_kind_decides_what_a_change_looks_like() {
        let change = || TextChange {
            start: PointUtf16::new(1, 0),
            old_end: PointUtf16::new(1, 4),
            text: "x".to_owned(),
            whole: Some("everything".to_owned()),
            buffer_version: 7,
        };
        let full = content_changes(change(), TextDocumentSyncKind::FULL).unwrap();
        assert_eq!(full[0].range, None);
        assert_eq!(full[0].text, "everything");

        assert!(content_changes(change(), TextDocumentSyncKind::NONE).is_none());

        // Full sync with nothing to send at all is silence rather than a lie.
        let mut without = change();
        without.whole = None;
        assert!(content_changes(without, TextDocumentSyncKind::FULL).is_none());
    }

    // -----------------------------------------------------------------------
    // The diagnostics cache
    // -----------------------------------------------------------------------

    fn diagnostic(line: u32, character: u32, severity: lsp::DiagnosticSeverity) -> lsp::Diagnostic {
        lsp::Diagnostic {
            range: Range::new(
                Position::new(line, character),
                Position::new(line, character + 3),
            ),
            severity: Some(severity),
            source: Some("rustc".to_owned()),
            code: Some(lsp::NumberOrString::String("E0308".to_owned())),
            message: "mismatched types".to_owned(),
            ..Default::default()
        }
    }

    fn publish(
        path: &Path,
        version: Option<i32>,
        diagnostics: Vec<lsp::Diagnostic>,
    ) -> PublishDiagnosticsParams {
        PublishDiagnosticsParams {
            uri: Uri::from_file_path(path).unwrap(),
            diagnostics,
            version,
        }
    }

    /// The generation counter, which is the whole of the UI's contract: it
    /// starts at 0, moves on every publish, and never moves backwards.
    #[test]
    fn diagnostics_publish_behind_a_generation_counter() {
        let store = DiagnosticStore::default();
        let path = PathBuf::from("/p/main.rs");
        assert_eq!(store.version(1), 0);

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                None,
                vec![diagnostic(3, 4, lsp::DiagnosticSeverity::ERROR)],
            ),
        );
        assert_eq!(store.version(1), 1);
        // Another project's counter is its own.
        assert_eq!(store.version(2), 0);

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                None,
                vec![
                    diagnostic(3, 4, lsp::DiagnosticSeverity::ERROR),
                    diagnostic(9, 0, lsp::DiagnosticSeverity::WARNING),
                ],
            ),
        );
        assert_eq!(store.version(1), 2);

        let files = store.files.lock().unwrap();
        let file = files.get(&path).expect("an entry");
        assert_eq!(file.version, 2);
        assert_eq!(
            file.counts,
            Counts {
                errors: 1,
                warnings: 1,
                ..Counts::default()
            }
        );
        // Sorted by position: "next diagnostic" is a scan, not a sort.
        assert_eq!(file.rows[0].row, 3);
        assert_eq!(file.rows[1].row, 9);
        assert_eq!(file.rows[0].source.as_deref(), Some("rustc"));
        assert_eq!(file.rows[0].code.as_deref(), Some("E0308"));
        assert_eq!(file.rows[0].severity, Severity::Error);
        // UTF-16 columns, taken as the server gave them — which is what
        // `initialize` negotiated, so there is nothing to convert.
        assert_eq!((file.rows[0].col_utf16, file.rows[0].end_col_utf16), (4, 7));
        drop(files);

        // An empty publish is a retraction, and still a generation.
        store.publish(1, path.clone(), publish(&path, None, Vec::new()));
        assert_eq!(store.version(1), 3);
        assert!(store.files.lock().unwrap().is_empty());
    }

    /// A diagnostic with no severity is a warning: it is the assumption every
    /// editor makes, and the safer of the two available guesses.
    #[test]
    fn a_diagnostic_without_a_severity_is_a_warning() {
        assert_eq!(severity_of(None), Severity::Warning);
        assert_eq!(
            severity_of(Some(lsp::DiagnosticSeverity::HINT)),
            Severity::Hint
        );
        assert_eq!(
            severity_of(Some(lsp::DiagnosticSeverity::INFORMATION)),
            Severity::Info
        );
    }

    /// A publish dated with a document version we never sent describes text
    /// that no longer exists, and the UI has to be able to tell.
    #[test]
    fn a_publish_is_dated_against_what_we_last_sent() {
        let store = DiagnosticStore::default();
        let path = PathBuf::from("/p/main.rs");
        store.note_sent(&path, 5, 40);

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                Some(5),
                vec![diagnostic(0, 0, lsp::DiagnosticSeverity::ERROR)],
            ),
        );
        assert_eq!(
            store.files.lock().unwrap()[&path].buffer_version,
            Some(40),
            "a publish for the version we sent describes that buffer version"
        );

        store.publish(
            1,
            path.clone(),
            publish(
                &path,
                Some(4),
                vec![diagnostic(0, 0, lsp::DiagnosticSeverity::ERROR)],
            ),
        );
        assert_eq!(
            store.files.lock().unwrap()[&path].buffer_version,
            None,
            "an older document version dates to nothing we can name"
        );
    }

    /// What the editor actually reads: rows for one buffer, and whether they
    /// still describe it.
    #[test]
    fn a_buffer_reads_its_own_diagnostics_and_knows_when_they_are_stale() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let engine = Engine::new();
        let project = engine.open_project(file.parent().unwrap());
        let buffer = engine.open_file(&file).unwrap();
        let path = engine.buffer_path(buffer).unwrap();

        assert_eq!(engine.buffer_diagnostics_version(buffer), 0);
        engine.lsp.store.note_sent(&path, 1, 0);
        engine.lsp.store.publish(
            project,
            path.clone(),
            publish(
                &path,
                Some(1),
                vec![diagnostic(0, 3, lsp::DiagnosticSeverity::ERROR)],
            ),
        );

        assert_eq!(engine.buffer_diagnostics_version(buffer), 1);
        let diagnostics = engine.buffer_diagnostics(buffer);
        assert_eq!(diagnostics.version, 1);
        assert_eq!(diagnostics.rows.len(), 1);
        assert_eq!(diagnostics.buffer_version, Some(0));
        assert!(
            !diagnostics.stale,
            "the buffer has not moved since the server saw it"
        );

        // Type; the rows now describe a document that has changed.
        engine.edit(buffer, 0, 0, "// ").unwrap();
        assert!(engine.buffer_diagnostics(buffer).stale);
        // The counter has *not* moved: nothing new was published, and a UI
        // polling it must not be woken by its own typing.
        assert_eq!(engine.buffer_diagnostics_version(buffer), 1);

        // The project summary sees the same publish, with a project-relative
        // path spelled the way the panels spell paths.
        let summary = engine.lsp_diagnostics(project);
        assert_eq!(summary.totals.errors, 1);
        assert_eq!(summary.files.len(), 1);
        assert_eq!(summary.files[0].path, "main.rs");

        // Closing the buffer does *not* retract them: a workspace-wide
        // analysis is still true about a file nobody has on screen, and a
        // diagnostics panel that emptied itself when a tab closed would be
        // worse than useless. Only an empty publish from the server clears
        // them.
        assert!(engine.close_buffer(buffer));
        assert_eq!(engine.lsp_diagnostics(project).files.len(), 1);
        assert_eq!(engine.lsp_diagnostics(project).totals.errors, 1);

        // Reopening cannot date them against the new buffer, so they read as
        // stale until the server publishes again.
        let reopened = engine.open_file(&file).unwrap();
        let diagnostics = engine.buffer_diagnostics(reopened);
        assert_eq!(diagnostics.rows.len(), 1);
        assert_eq!(diagnostics.buffer_version, None);
        assert!(diagnostics.stale);

        // The project closing is what does clear everything: the paths are
        // meaningless without the project they were relative to.
        assert!(engine.close_project(project));
        assert_eq!(engine.lsp_diagnostics(project).files.len(), 0);
        assert_eq!(engine.lsp_version(project), 0);
    }

    /// What the diagnostics panel reads: every row in the project, messages
    /// and all, grouped by file and sorted the way the counts are.
    #[test]
    fn the_panel_reads_every_row_in_the_project() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let main = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let lib = project_with_file(dir.path(), "lib.rs", "fn lib() {}\n");
        let engine = Engine::new();
        let project = engine.open_project(main.parent().unwrap());

        assert_eq!(engine.lsp_diagnostic_rows(project).files.len(), 0);

        let main = std::fs::canonicalize(&main).unwrap();
        let lib = std::fs::canonicalize(&lib).unwrap();
        engine.lsp.store.publish(
            project,
            main.clone(),
            publish(
                &main,
                None,
                vec![
                    diagnostic(3, 4, lsp::DiagnosticSeverity::ERROR),
                    diagnostic(9, 0, lsp::DiagnosticSeverity::WARNING),
                ],
            ),
        );
        engine.lsp.store.publish(
            project,
            lib.clone(),
            publish(&lib, None, vec![diagnostic(1, 2, lsp::DiagnosticSeverity::HINT)]),
        );

        let rows = engine.lsp_diagnostic_rows(project);
        assert_eq!(rows.version, engine.lsp_version(project));
        // Sorted by path, spelled project-relative like the counts.
        assert_eq!(rows.files.len(), 2);
        assert_eq!(rows.files[0].path, "lib.rs");
        assert_eq!(rows.files[1].path, "main.rs");
        assert_eq!(rows.files[0].rows.len(), 1);
        assert_eq!(rows.files[1].rows.len(), 2);
        assert_eq!(rows.files[1].rows[0].row, 3);
        assert_eq!(rows.files[1].rows[0].message, "mismatched types");
        assert_eq!(rows.files[1].rows[0].severity, Severity::Error);

        // The shape the Kotlin side parses, exactly.
        let json = serde_json::to_value(&rows).unwrap();
        let file = &json["files"][0];
        assert_eq!(file["path"], "lib.rs");
        let row = &file["rows"][0];
        assert_eq!(row["row"], 1);
        assert_eq!(row["col_utf16"], 2);
        assert_eq!(row["severity"], "hint");
        assert_eq!(row["message"], "mismatched types");
        assert_eq!(row["source"], "rustc");
        assert_eq!(row["code"], "E0308");
    }

    /// A project opened through a symlinked spelling of its root — which is
    /// every project on Android, where `filesDir` is /data/user/0/<pkg>, a
    /// link to /data/data/<pkg> — still owns its files.
    ///
    /// Buffers and published diagnostics hold canonical paths, so a root kept
    /// in the caller's spelling would fail every `starts_with` against them:
    /// `lsp_did_open` would file the buffer under no project (no server, no
    /// diagnostics, no error), and a server that did start would be rooted at
    /// a URI none of its documents are under — single-file mode, in a
    /// workspace.
    #[cfg(unix)]
    #[test]
    fn a_project_opened_through_a_symlink_still_owns_its_files() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let real_root = file.parent().unwrap();
        let linked_root = dir.path().join("linked");
        std::os::unix::fs::symlink(real_root, &linked_root).unwrap();

        let engine = Engine::new();
        let project = engine.open_project(&linked_root);
        let buffer = engine.open_file(&file).unwrap();
        let path = engine.buffer_path(buffer).unwrap();

        assert_eq!(
            engine.project_for_path(&path),
            Some(project),
            "the canonical buffer path resolves to the project opened by link"
        );

        // A publish under the canonical spelling lands in the project, and its
        // path reads project-relative rather than absolute.
        engine.lsp.store.publish(
            project,
            path.clone(),
            publish(&path, None, vec![diagnostic(0, 0, lsp::DiagnosticSeverity::ERROR)]),
        );
        let summary = engine.lsp_diagnostics(project);
        assert_eq!(summary.files.len(), 1);
        assert_eq!(summary.files[0].path, "main.rs");
    }

    // -----------------------------------------------------------------------
    // Watched files
    // -----------------------------------------------------------------------

    fn registration(id: &str, globs: &[&str]) -> lsp::RegistrationParams {
        lsp::RegistrationParams {
            registrations: vec![lsp::Registration {
                id: id.to_owned(),
                method: "workspace/didChangeWatchedFiles".to_owned(),
                register_options: Some(
                    serde_json::to_value(lsp::DidChangeWatchedFilesRegistrationOptions {
                        watchers: globs
                            .iter()
                            .map(|glob| lsp::FileSystemWatcher {
                                glob_pattern: lsp::GlobPattern::String((*glob).to_owned()),
                                kind: None,
                            })
                            .collect(),
                    })
                    .unwrap(),
                ),
            }],
        }
    }

    /// The round trip a rust-analyzer makes: register `**/Cargo.toml`, and be
    /// told when a Cargo.toml changes — and only a Cargo.toml, and only the
    /// server that asked.
    #[test]
    fn a_registered_watcher_hears_its_globs_and_nothing_else() {
        let watchers: Watchers = Default::default();
        register_watchers(&watchers, 1, "rust-analyzer", &registration("w", &["**/Cargo.toml"]));
        register_watchers(&watchers, 1, "gopls", &registration("g", &["**/*.go"]));
        // Another project's server must not hear project 1's changes.
        register_watchers(&watchers, 2, "gopls", &registration("g", &["**/*.go"]));

        let root = Path::new("/p/thing");
        let changes = vec![
            (PathBuf::from("/p/thing/Cargo.toml"), lsp::FileChangeType::CHANGED),
            (PathBuf::from("/p/thing/src/main.go"), lsp::FileChangeType::CREATED),
            (PathBuf::from("/p/thing/notes.md"), lsp::FileChangeType::CHANGED),
        ];
        let mut heard = collect_watched(&watchers.lock().unwrap(), 1, root, &changes);
        heard.sort_by_key(|(server, _)| *server);

        assert_eq!(heard.len(), 2);
        let (server, events) = &heard[0];
        assert_eq!(*server, "gopls");
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].typ, lsp::FileChangeType::CREATED);
        let (server, events) = &heard[1];
        assert_eq!(*server, "rust-analyzer");
        assert_eq!(events.len(), 1);
        assert!(events[0].uri.to_string().ends_with("Cargo.toml"));

        // Unregistering by the server's own id silences it.
        unregister_watchers(
            &watchers,
            1,
            "rust-analyzer",
            &lsp::UnregistrationParams {
                unregisterations: vec![lsp::Unregistration {
                    id: "w".to_owned(),
                    method: "workspace/didChangeWatchedFiles".to_owned(),
                }],
            },
        );
        let heard = collect_watched(&watchers.lock().unwrap(), 1, root, &changes);
        assert_eq!(heard.len(), 1, "only gopls is still listening");
    }

    /// The initial scan is not change: forwarding `Loaded` would hand a server
    /// one event per file in the tree the moment it starts.
    #[test]
    fn the_initial_scan_is_not_a_change() {
        assert_eq!(watched_change_type(&worktree::PathChange::Loaded), None);
        assert_eq!(
            watched_change_type(&worktree::PathChange::Added),
            Some(lsp::FileChangeType::CREATED)
        );
        assert_eq!(
            watched_change_type(&worktree::PathChange::Removed),
            Some(lsp::FileChangeType::DELETED)
        );
        assert_eq!(
            watched_change_type(&worktree::PathChange::Updated),
            Some(lsp::FileChangeType::CHANGED)
        );
        assert_eq!(
            watched_change_type(&worktree::PathChange::AddedOrUpdated),
            Some(lsp::FileChangeType::CHANGED)
        );
    }

    // -----------------------------------------------------------------------
    // Workspace edits
    // -----------------------------------------------------------------------

    fn text_edit(
        (row, col): (u32, u32),
        (end_row, end_col): (u32, u32),
        text: &str,
    ) -> lsp::TextEdit {
        lsp::TextEdit {
            range: Range::new(Position::new(row, col), Position::new(end_row, end_col)),
            new_text: text.to_owned(),
        }
    }

    /// The closed-file applier: UTF-16 positions, back-to-front application,
    /// and the spec's clamping for positions past the end.
    #[test]
    fn edits_land_in_text_by_utf16_position() {
        let text = "fn main() {\n    let naïve = 1;\n}\n";
        // Two edits on one line, in *ascending* order as servers send them:
        // rename `naïve` and change its value. The ï is one UTF-16 unit but
        // two bytes, which is exactly what this conversion is for.
        let edited = apply_edits_to_text(
            text,
            &[
                text_edit((1, 8), (1, 13), "clever"),
                text_edit((1, 16), (1, 17), "2"),
            ],
        );
        assert_eq!(edited, "fn main() {\n    let clever = 2;\n}\n");

        // A position past the last line means the end of the document —
        // how a formatter appends a missing trailing newline.
        let appended = apply_edits_to_text("no newline", &[text_edit((5, 0), (5, 0), "\n")]);
        assert_eq!(appended, "no newline\n");

        // A character past a line's end means the end of that line.
        let clamped = apply_edits_to_text("ab\ncd\n", &[text_edit((0, 99), (1, 0), "-")]);
        assert_eq!(clamped, "ab-cd\n");
    }

    /// Snippet syntax reduced to the text it leaves behind — what
    /// rust-analyzer's snippet edits collapse to when there is no caret to
    /// place.
    #[test]
    fn snippets_collapse_to_their_text() {
        assert_eq!(strip_snippet("let $0name = ${1:value};"), "let name = value;");
        assert_eq!(strip_snippet("${1|first,second|}"), "first");
        assert_eq!(strip_snippet("${1}"), "");
        assert_eq!(strip_snippet("plain"), "plain");
        assert_eq!(strip_snippet(r"\$literal"), "$literal");
        assert_eq!(strip_snippet("${1:outer ${2:inner}}"), "outer inner");
    }

    /// Both shapes a `WorkspaceEdit` arrives in flatten to the same list, and
    /// file operations are refused whole rather than half-applied.
    #[test]
    fn workspace_edits_normalize_and_resource_ops_are_refused() {
        let uri = Uri::from_file_path(Path::new("/p/main.rs")).unwrap();
        let simple = lsp::WorkspaceEdit {
            changes: Some(HashMap::from([(
                uri.clone(),
                vec![text_edit((0, 0), (0, 1), "x")],
            )])),
            document_changes: None,
            change_annotations: None,
        };
        let files = normalize_workspace_edit(simple).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].path, PathBuf::from("/p/main.rs"));
        assert_eq!(files[0].version, None);
        assert_eq!(files[0].edits.len(), 1);

        let versioned = lsp::WorkspaceEdit {
            changes: None,
            document_changes: Some(lsp::DocumentChanges::Edits(vec![lsp::TextDocumentEdit {
                text_document: lsp::OptionalVersionedTextDocumentIdentifier {
                    uri: uri.clone(),
                    version: Some(7),
                },
                edits: vec![lsp::Edit::Plain(text_edit((0, 0), (0, 1), "y"))],
            }])),
            change_annotations: None,
        };
        let files = normalize_workspace_edit(versioned).unwrap();
        assert_eq!(files[0].version, Some(7));

        let with_ops = lsp::WorkspaceEdit {
            changes: None,
            document_changes: Some(lsp::DocumentChanges::Operations(vec![
                lsp::DocumentChangeOperation::Op(lsp::ResourceOp::Delete(lsp::DeleteFile {
                    uri,
                    options: None,
                })),
            ])),
            change_annotations: None,
        };
        assert!(normalize_workspace_edit(with_ops).is_err());
    }

    /// The whole apply path against a real buffer: one undo step, a receipt
    /// naming the buffer, and the closed-file half writing through to disk.
    #[test]
    fn a_pending_edit_lands_in_buffers_and_files() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("projects")).unwrap();
        let open_file = project_with_file(dir.path(), "main.rs", "fn main() {}\n");
        let closed_file = project_with_file(dir.path(), "lib.rs", "fn lib() {}\n");
        let engine = Engine::new();
        let _project = engine.open_project(open_file.parent().unwrap());
        let buffer = engine.open_file(&open_file).unwrap();
        let open_canonical = engine.buffer_path(buffer).unwrap();
        let closed_canonical = std::fs::canonicalize(&closed_file).unwrap();

        // Stage the edit the way a settled rename would have.
        let edit = lsp::WorkspaceEdit {
            changes: Some(HashMap::from([
                (
                    Uri::from_file_path(&open_canonical).unwrap(),
                    vec![text_edit((0, 3), (0, 7), "start")],
                ),
                (
                    Uri::from_file_path(&closed_canonical).unwrap(),
                    vec![text_edit((0, 3), (0, 6), "start")],
                ),
            ])),
            document_changes: None,
            change_annotations: None,
        };
        let id = engine.start_request(RequestKind::Rename, buffer, 0, 3);
        {
            let requests = engine.lsp.requests.lock().unwrap();
            let pending = requests.live.get(&id).unwrap();
            *pending.stored.lock().unwrap() = StoredWork::Edit(edit);
        }

        let receipt = engine.lsp_apply_pending_edit(id);
        assert!(receipt.applied, "refused: {:?}", receipt.error);
        assert_eq!(receipt.error, None);
        assert_eq!(receipt.files.len(), 2);
        // Sorted by path: lib.rs before main.rs.
        assert_eq!(receipt.files[0].buffer_id, None, "lib.rs is not open");
        assert_eq!(receipt.files[1].buffer_id, Some(buffer));

        assert_eq!(engine.text(buffer).unwrap(), "fn start() {}\n");
        assert_eq!(
            std::fs::read_to_string(&closed_canonical).unwrap(),
            "fn start() {}\n"
        );

        // One undo step takes the whole edit back out of the open buffer.
        engine.undo(buffer).unwrap();
        assert_eq!(engine.text(buffer).unwrap(), "fn main() {}\n");

        // The edit was taken: applying again has nothing to do.
        let again = engine.lsp_apply_pending_edit(id);
        assert!(!again.applied);
    }

    // -----------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------

    /// A newer request of the same kind retires the older one — the rule that
    /// keeps a completion popup re-asking on every keystroke from accumulating
    /// work, and the reason the server is told to stop.
    #[test]
    fn a_newer_request_supersedes_the_older_one_of_its_kind() {
        let engine = Engine::new();
        let first = engine.lsp_request_completion(1, 0, 0);
        let second = engine.lsp_request_completion(1, 0, 1);
        assert_ne!(first, second);

        assert_eq!(engine.lsp_request_version(first), 0, "forgotten");
        assert_eq!(
            engine.lsp_request_result(first).state,
            RequestState::Cancelled
        );
        assert!(engine.lsp_request_version(second) > 0);

        // A different kind has its own slot.
        let hover = engine.lsp_request_hover(1, 0, 1);
        assert!(engine.lsp_request_version(second) > 0);
        assert!(engine.lsp_request_version(hover) > 0);

        assert!(engine.lsp_cancel_request(hover));
        assert!(!engine.lsp_cancel_request(hover));
        assert_eq!(engine.lsp_request_version(hover), 0);
        assert_eq!(
            engine.lsp_request_result(hover).state,
            RequestState::Cancelled
        );
    }

    // -----------------------------------------------------------------------
    // The JSON the UI wave is going to build against
    // -----------------------------------------------------------------------

    #[test]
    fn completion_json_is_this_shape() {
        let response = CompletionResponse::List(lsp::CompletionList {
            is_incomplete: true,
            item_defaults: None,
            items: vec![
                lsp::CompletionItem {
                    label: "push".to_owned(),
                    kind: Some(lsp::CompletionItemKind::METHOD),
                    detail: Some("fn(&mut self, value: T)".to_owned()),
                    documentation: Some(Documentation::MarkupContent(lsp::MarkupContent {
                        kind: lsp::MarkupKind::Markdown,
                        value: "Appends an element.".to_owned(),
                    })),
                    text_edit: Some(CompletionTextEdit::Edit(lsp::TextEdit {
                        range: Range::new(Position::new(4, 8), Position::new(4, 10)),
                        new_text: "push(${1:value})".to_owned(),
                    })),
                    insert_text_format: Some(lsp::InsertTextFormat::SNIPPET),
                    preselect: Some(true),
                    sort_text: Some("0001push".to_owned()),
                    ..Default::default()
                },
                // Nothing but a label, which is legal and common.
                lsp::CompletionItem {
                    label: "pop".to_owned(),
                    ..Default::default()
                },
            ],
        });
        let json = completion_json_of(Some(response));
        assert_eq!(json["is_incomplete"], true);

        let first = &json["items"][0];
        assert_eq!(first["index"], 0);
        assert_eq!(first["label"], "push");
        assert_eq!(first["kind"], "method");
        assert_eq!(first["detail"], "fn(&mut self, value: T)");
        assert_eq!(first["insert_text"], "push(${1:value})");
        assert_eq!(first["is_snippet"], true);
        assert_eq!(first["documentation"], "Appends an element.");
        assert_eq!(first["preselect"], true);
        assert_eq!(first["deprecated"], false);
        assert_eq!(first["sort_text"], "0001push");
        // Filter text falls back to the label rather than to the insert text,
        // which for a snippet would be `push(${1:value})` and match nothing.
        assert_eq!(first["filter_text"], "push");
        assert_eq!(first["edit"]["row"], 4);
        assert_eq!(first["edit"]["col_utf16"], 8);
        assert_eq!(first["edit"]["end_row"], 4);
        assert_eq!(first["edit"]["end_col_utf16"], 10);

        let second = &json["items"][1];
        assert_eq!(second["insert_text"], "pop");
        assert_eq!(second["kind"], serde_json::Value::Null);
        assert_eq!(second["edit"], serde_json::Value::Null);
        assert_eq!(second["is_snippet"], false);

        // Nothing at all is an empty list, not a null.
        let empty = completion_json_of(None);
        assert_eq!(empty["is_incomplete"], false);
        assert_eq!(empty["items"].as_array().map(Vec::len), Some(0));
    }

    /// A completion carrying an insert-and-replace edit applies over the
    /// identifier it replaces, not beside it.
    #[test]
    fn an_insert_and_replace_completion_uses_the_replace_range() {
        let response = CompletionResponse::Array(vec![lsp::CompletionItem {
            label: "collect".to_owned(),
            text_edit: Some(CompletionTextEdit::InsertAndReplace(
                lsp::InsertReplaceEdit {
                    new_text: "collect".to_owned(),
                    insert: Range::new(Position::new(1, 4), Position::new(1, 6)),
                    replace: Range::new(Position::new(1, 4), Position::new(1, 11)),
                },
            )),
            ..Default::default()
        }]);
        let json = completion_json_of(Some(response));
        assert_eq!(json["items"][0]["edit"]["end_col_utf16"], 11);
    }

    /// The wire response as the request arm splits it, for the tests above.
    fn completion_json_of(response: Option<CompletionResponse>) -> serde_json::Value {
        let (is_incomplete, items) = match response {
            Some(CompletionResponse::Array(items)) => (false, items),
            Some(CompletionResponse::List(list)) => (list.is_incomplete, list.items),
            None => (false, Vec::new()),
        };
        completion_json(is_incomplete, &items)
    }

    #[test]
    fn hover_json_is_one_markdown_string() {
        let json = hover_json(Some(lsp::Hover {
            contents: HoverContents::Array(vec![
                MarkedString::LanguageString(lsp::LanguageString {
                    language: "rust".to_owned(),
                    value: "fn main()".to_owned(),
                }),
                MarkedString::String("The entry point.".to_owned()),
            ]),
            range: Some(Range::new(Position::new(0, 3), Position::new(0, 7))),
        }));
        assert_eq!(
            json["contents"],
            "```rust\nfn main()\n```\n\nThe entry point."
        );
        assert_eq!(json["range"]["col_utf16"], 3);
        assert_eq!(json["range"]["end_col_utf16"], 7);

        // "nothing to say" is an empty string, never a null: the UI shows no
        // popup either way and should not have to tell them apart.
        let empty = hover_json(None);
        assert_eq!(empty["contents"], "");
        assert_eq!(empty["range"], serde_json::Value::Null);
    }

    #[test]
    fn definition_json_is_paths_and_positions() {
        let uri = Uri::from_file_path("/p/src/lib.rs").unwrap();
        let json = definition_json(Some(GotoDefinitionResponse::Scalar(lsp::Location {
            uri: uri.clone(),
            range: Range::new(Position::new(12, 3), Position::new(12, 9)),
        })));
        assert_eq!(json["targets"][0]["path"], "/p/src/lib.rs");
        assert_eq!(json["targets"][0]["row"], 12);
        assert_eq!(json["targets"][0]["col_utf16"], 3);
        assert_eq!(json["targets"][0]["end_col_utf16"], 9);

        // A link answer uses the *selection* range, which is the name rather
        // than the whole definition — where the caret should land.
        let json = definition_json(Some(GotoDefinitionResponse::Link(vec![
            lsp::LocationLink {
                origin_selection_range: None,
                target_uri: uri,
                target_range: Range::new(Position::new(12, 0), Position::new(20, 1)),
                target_selection_range: Range::new(Position::new(12, 3), Position::new(12, 9)),
            },
        ])));
        assert_eq!(json["targets"][0]["row"], 12);
        assert_eq!(json["targets"][0]["end_row"], 12);

        assert_eq!(
            definition_json(None)["targets"].as_array().map(Vec::len),
            Some(0)
        );
    }

    /// Paths in the summary are project-relative and `/`-separated, matching
    /// `TreeEntry::path`; one outside the project — a header from
    /// `/usr/include` — keeps its absolute name rather than being mangled into
    /// a relative one that points nowhere.
    #[test]
    fn summary_paths_are_project_relative_where_they_can_be() {
        let root = Path::new("/p/thing");
        assert_eq!(
            relative_path(Some(root), Path::new("/p/thing/src/main.rs")),
            "src/main.rs"
        );
        assert_eq!(
            relative_path(Some(root), Path::new("/usr/include/stdio.h")),
            "/usr/include/stdio.h"
        );
        assert_eq!(relative_path(None, Path::new("/p/x.rs")), "/p/x.rs");
    }

    // -----------------------------------------------------------------------
    // Commands, resolves and the other request shapes
    // -----------------------------------------------------------------------

    /// A client `LanguageServer` wired to an in-process fake, on the engine's
    /// runtime — Zed's `FakeLanguageServer`, driven the way its lsp_store
    /// tests drive it. `run` gets both ends and answers with whatever the
    /// host thread wants to assert on.
    fn with_fake_server<T: Send + 'static>(
        capabilities: lsp::ServerCapabilities,
        run: impl FnOnce(Arc<LanguageServer>, lsp::FakeLanguageServer) -> futures::future::LocalBoxFuture<'static, T>
            + Send
            + 'static,
    ) -> T {
        let engine = Engine::new();
        let (tx, rx) = std::sync::mpsc::channel();
        engine.runtime().spawn(move |cx| {
            cx.spawn(async move |cx| {
                let binary = LanguageServerBinary {
                    path: PathBuf::from("fake-server"),
                    arguments: Vec::new(),
                    env: None,
                };
                let (client, fake) = lsp::FakeLanguageServer::new(
                    LanguageServerId(1),
                    binary,
                    "fake".to_owned(),
                    capabilities,
                    cx,
                );
                let answer = run(Arc::new(client), fake).await;
                let _ = tx.send(answer);
            })
            .detach();
        });
        rx.recv_timeout(Duration::from_secs(20))
            .expect("the fake server answered inside its deadline")
    }

    fn edit_at(row: u32, text: &str) -> lsp::TextEdit {
        lsp::TextEdit {
            range: Range::new(Position::new(row, 0), Position::new(row, 0)),
            new_text: text.to_owned(),
        }
    }

    fn a_command(name: &str) -> lsp::Command {
        lsp::Command {
            title: "Do the thing".to_owned(),
            command: name.to_owned(),
            arguments: Some(vec![serde_json::json!({"file": "main.rs"})]),
        }
    }

    /// The command half of a code action, end to end: `workspace/executeCommand`
    /// goes out, the server answers with `workspace/applyEdit` requests of its
    /// own, and what it pushed is the action's edit — held for
    /// `lsp_apply_pending_edit` exactly as a resolved action's edit is.
    #[test]
    fn a_command_action_runs_through_execute_command_and_takes_the_pushed_edit() {
        let uri = Uri::from_file_path("/tmp/main.rs").unwrap();
        let file = uri.clone();
        let (state, payload, stored, seen) = with_fake_server(
            lsp::ServerCapabilities::default(),
            move |client, fake| {
                Box::pin(async move {
                    // The client's inbox: what `start_server` registers for a
                    // real server, here on the fake's client end.
                    let applied: AppliedEdits = Arc::default();
                    let inbox = applied.clone();
                    let _subscription = client.on_request::<lsp::request::ApplyWorkspaceEdit, _, _>(
                        move |params: lsp::ApplyWorkspaceEditParams, _cx: &mut AsyncApp| {
                            inbox.lock().unwrap().push(params.edit);
                            async move {
                                anyhow::Ok(lsp::ApplyWorkspaceEditResponse {
                                    applied: true,
                                    failure_reason: None,
                                    failed_change: None,
                                })
                            }
                        },
                    );
                    // The server: the command pushes two edits before answering,
                    // as rust-analyzer's `applySourceChange` does.
                    let seen: Arc<Mutex<Vec<String>>> = Arc::default();
                    let seen_by_server = seen.clone();
                    let pusher = fake.server.clone();
                    let _handled = fake.set_request_handler::<lsp::request::ExecuteCommand, _, _>(
                        move |params: lsp::ExecuteCommandParams, _cx| {
                            seen_by_server.lock().unwrap().push(params.command.clone());
                            let pusher = pusher.clone();
                            let file = file.clone();
                            async move {
                                for (row, text) in [(0, "use std::fmt;\n"), (2, "// added\n")] {
                                    pusher
                                        .request::<lsp::request::ApplyWorkspaceEdit>(
                                            lsp::ApplyWorkspaceEditParams {
                                                label: None,
                                                edit: lsp::WorkspaceEdit {
                                                    changes: Some(HashMap::from([(
                                                        file.clone(),
                                                        vec![edit_at(row, text)],
                                                    )])),
                                                    document_changes: None,
                                                    change_annotations: None,
                                                },
                                            },
                                            Duration::from_secs(5),
                                        )
                                        .await;
                                }
                                anyhow::Ok(None)
                            }
                        },
                    );
                    let (state, payload, stored) =
                        execute_command(&client, a_command("rust-analyzer.applySourceChange"), &applied)
                            .await;
                    let seen = seen.lock().unwrap().clone();
                    (state, payload, stored, seen)
                })
            },
        );
        assert_eq!(seen, vec!["rust-analyzer.applySourceChange".to_owned()]);
        assert_eq!(state, RequestState::Done);
        // Two pushes, one file: one apply, summarised as such.
        assert_eq!(payload["files"], 1);
        assert_eq!(payload["edits"], 2);
        match stored {
            StoredWork::Edit(edit) => {
                let changes = edit.changes.expect("the pushed edits are held as changes");
                assert_eq!(changes[&uri].len(), 2);
            }
            _ => panic!("expected the merged edit to be held"),
        }
    }

    /// A command that changes nothing — "cargo check", "run this test" — is
    /// still a command that ran, and the UI is told so rather than "the server
    /// had nothing to offer".
    #[test]
    fn a_command_that_pushes_nothing_reports_that_it_ran() {
        let (state, payload, stored) = with_fake_server(
            lsp::ServerCapabilities::default(),
            |client, fake| {
                Box::pin(async move {
                    let _handled = fake.set_request_handler::<lsp::request::ExecuteCommand, _, _>(
                        |_params: lsp::ExecuteCommandParams, _cx| async move { anyhow::Ok(None) },
                    );
                    let applied: AppliedEdits = Arc::default();
                    // Something pushed *before* the command is not the command's.
                    applied.lock().unwrap().push(lsp::WorkspaceEdit::default());
                    execute_command(&client, a_command("cargo.check"), &applied).await
                })
            },
        );
        assert_eq!(state, RequestState::Done);
        assert_eq!(payload["edits"], 0);
        assert_eq!(payload["ran"], "Do the thing");
        assert!(matches!(stored, StoredWork::None));
    }

    /// A command the server refuses is a sentence for the user, and `done`:
    /// there is nothing to retry.
    #[test]
    fn a_refused_command_is_a_sentence() {
        let (state, payload, _) = with_fake_server(
            lsp::ServerCapabilities::default(),
            |client, fake| {
                Box::pin(async move {
                    let _handled = fake.set_request_handler::<lsp::request::ExecuteCommand, _, _>(
                        |_params: lsp::ExecuteCommandParams, _cx| async move {
                            Err(anyhow::anyhow!("unknown command"))
                        },
                    );
                    let applied: AppliedEdits = Arc::default();
                    execute_command(&client, a_command("nope"), &applied).await
                })
            },
        );
        assert_eq!(state, RequestState::Done);
        assert_eq!(payload["error"], "the server could not run nope");
    }

    /// `completionItem/resolve`: the documentation comes back as text for the
    /// menu's aside, and the `additionalTextEdits` — the import — wait as an
    /// edit against the buffer's own URI.
    #[test]
    fn resolving_a_completion_renders_its_documentation_and_holds_its_extra_edits() {
        let uri = Uri::from_file_path("/tmp/main.rs").unwrap();
        let (state, payload, stored) = with_fake_server(
            lsp::ServerCapabilities::default(),
            {
                let uri = uri.clone();
                move |client, fake| {
                    Box::pin(async move {
                        let _handled = fake.set_request_handler::<lsp::request::ResolveCompletionItem, _, _>(
                            |mut item: lsp::CompletionItem, _cx| async move {
                                // The server fills in what the list left out, and
                                // keeps the `data` it was handed.
                                assert_eq!(item.data, Some(serde_json::json!({"id": 7})));
                                item.documentation = Some(Documentation::MarkupContent(lsp::MarkupContent {
                                    kind: lsp::MarkupKind::Markdown,
                                    value: "  Formats things.\n".to_owned(),
                                }));
                                item.detail = Some("fn fmt()".to_owned());
                                item.additional_text_edits = Some(vec![edit_at(0, "use std::fmt;\n")]);
                                anyhow::Ok(item)
                            },
                        );
                        let item = lsp::CompletionItem {
                            label: "fmt".to_owned(),
                            data: Some(serde_json::json!({"id": 7})),
                            ..Default::default()
                        };
                        resolve_completion(&client, item, uri).await
                    })
                }
            },
        );
        assert_eq!(state, RequestState::Done);
        assert_eq!(payload["documentation"], "Formats things.");
        assert_eq!(payload["detail"], "fn fmt()");
        assert_eq!(payload["additional_edits"], 1);
        match stored {
            StoredWork::Edit(edit) => {
                let changes = edit.changes.expect("changes");
                assert_eq!(changes[&uri][0].new_text, "use std::fmt;\n");
            }
            _ => panic!("expected the import to be held"),
        }
    }

    /// The list request keeps its wire items so a resolve can echo the
    /// server's `data` back; a resolve of a list that is gone starts no
    /// request at all.
    #[test]
    fn a_completion_resolve_needs_a_live_list() {
        let engine = Engine::new();
        let id = engine.lsp_request_completion_resolve(99, 0);
        assert_eq!(engine.lsp_request_version(id), 2);
        assert_eq!(engine.lsp_request_result(id).state, RequestState::Unavailable);
    }

    #[test]
    fn pushed_edits_merge_into_one_apply() {
        let a = Uri::from_file_path("/tmp/a.rs").unwrap();
        let b = Uri::from_file_path("/tmp/b.rs").unwrap();
        let edit = |uri: &Uri, row: u32| lsp::WorkspaceEdit {
            changes: Some(HashMap::from([(uri.clone(), vec![edit_at(row, "x")])])),
            document_changes: None,
            change_annotations: None,
        };
        assert!(merge_workspace_edits(Vec::new()).is_none());
        let merged = merge_workspace_edits(vec![edit(&a, 0), edit(&b, 1), edit(&a, 2)]).unwrap();
        let changes = merged.changes.unwrap();
        assert_eq!(changes[&a].len(), 2, "two pushes to one file are one list");
        assert_eq!(changes[&b].len(), 1);
    }

    #[test]
    fn the_triggers_come_from_the_capabilities() {
        let capabilities = lsp::ServerCapabilities {
            completion_provider: Some(lsp::CompletionOptions {
                trigger_characters: Some(vec![".".to_owned(), "::".to_owned()]),
                ..Default::default()
            }),
            signature_help_provider: Some(lsp::SignatureHelpOptions {
                trigger_characters: Some(vec!["(".to_owned(), ",".to_owned()]),
                retrigger_characters: Some(vec![")".to_owned()]),
                work_done_progress_options: Default::default(),
            }),
            folding_range_provider: Some(lsp::FoldingRangeProviderCapability::Simple(true)),
            inlay_hint_provider: Some(lsp::OneOf::Left(false)),
            ..Default::default()
        };
        let triggers = triggers_of(&capabilities);
        assert_eq!(triggers.completion, vec![".", "::"]);
        assert_eq!(triggers.signature_help, vec!["(", ","]);
        assert_eq!(triggers.signature_help_retrigger, vec![")"]);
        assert!(triggers.folding_ranges);
        assert!(!triggers.inlay_hints, "declared off is off");
        assert_eq!(triggers_of(&lsp::ServerCapabilities::default()), BufferTriggers::default());
    }

    /// Zed finds a named parameter by searching the label
    /// (signature_help.rs `create_signature_help`); offsets are already
    /// UTF-16 on the wire.
    #[test]
    fn signature_help_json_locates_the_parameters_in_the_label() {
        let help = lsp::SignatureHelp {
            signatures: vec![lsp::SignatureInformation {
                label: "fn add(a: i32, b: i32) -> i32".to_owned(),
                documentation: Some(Documentation::String("Adds.".to_owned())),
                parameters: Some(vec![
                    lsp::ParameterInformation {
                        label: lsp::ParameterLabel::Simple("a: i32".to_owned()),
                        documentation: None,
                    },
                    lsp::ParameterInformation {
                        label: lsp::ParameterLabel::LabelOffsets([15, 21]),
                        documentation: Some(Documentation::String("the other".to_owned())),
                    },
                ]),
                active_parameter: None,
            }],
            active_signature: Some(4),
            active_parameter: Some(1),
        };
        let json = signature_help_json(Some(help));
        assert_eq!(json["active_signature"], 0, "clamped to the list");
        let signature = &json["signatures"][0];
        assert_eq!(signature["documentation"], "Adds.");
        assert_eq!(signature["active_parameter"], 1, "the help's, when the signature has none");
        assert_eq!(signature["parameters"][0]["start"], 7);
        assert_eq!(signature["parameters"][0]["end"], 13);
        assert_eq!(signature["parameters"][1]["start"], 15);
        assert_eq!(signature["parameters"][1]["documentation"], "the other");
        assert_eq!(signature_help_json(None)["signatures"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn folding_ranges_json_keeps_one_range_per_start_row_and_drops_single_rows() {
        let range = |start: u32, end: u32| lsp::FoldingRange {
            start_line: start,
            start_character: None,
            end_line: end,
            end_character: None,
            kind: None,
            collapsed_text: None,
        };
        let json = folding_ranges_json(Some(vec![range(5, 9), range(0, 3), range(0, 1), range(4, 4)]));
        let ranges = json["ranges"].as_array().unwrap();
        assert_eq!(ranges.len(), 2);
        assert_eq!(ranges[0]["start_row"], 0);
        assert_eq!(ranges[0]["end_row"], 1, "sorted by end, the first per row wins");
        assert_eq!(ranges[1]["start_row"], 5);
    }

    #[test]
    fn inlay_hints_json_flattens_label_parts_and_names_the_kind() {
        let hint = |label: lsp::InlayHintLabel, kind: Option<lsp::InlayHintKind>| lsp::InlayHint {
            position: Position::new(3, 7),
            label,
            kind,
            text_edits: None,
            tooltip: None,
            padding_left: Some(true),
            padding_right: None,
            data: None,
        };
        let json = inlay_hints_json(Some(vec![
            hint(lsp::InlayHintLabel::String(": i32".to_owned()), Some(lsp::InlayHintKind::TYPE)),
            hint(
                lsp::InlayHintLabel::LabelParts(vec![
                    lsp::InlayHintLabelPart { value: "x".to_owned(), ..Default::default() },
                    lsp::InlayHintLabelPart { value: ":".to_owned(), ..Default::default() },
                ]),
                Some(lsp::InlayHintKind::PARAMETER),
            ),
            hint(lsp::InlayHintLabel::String("'a".to_owned()), None),
        ]));
        let hints = json["hints"].as_array().unwrap();
        assert_eq!(hints[0]["label"], ": i32");
        assert_eq!(hints[0]["kind"], "type");
        assert_eq!(hints[0]["row"], 3);
        assert_eq!(hints[0]["col_utf16"], 7);
        assert_eq!(hints[0]["padding_left"], true);
        assert_eq!(hints[0]["padding_right"], false);
        assert_eq!(hints[1]["label"], "x:");
        assert_eq!(hints[1]["kind"], "parameter");
        assert!(hints[2]["kind"].is_null());
        assert_eq!(inlay_hints_json(None)["hints"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn workspace_symbols_are_project_relative_where_they_can_be() {
        let root = PathBuf::from("/tmp/proj");
        let inside = Uri::from_file_path("/tmp/proj/src/lib.rs").unwrap();
        let outside = Uri::from_file_path("/usr/include/x.h").unwrap();
        #[allow(deprecated)]
        let symbol = |name: &str, uri: Uri| lsp::SymbolInformation {
            name: name.to_owned(),
            kind: lsp::SymbolKind::FUNCTION,
            tags: None,
            deprecated: None,
            location: lsp::Location {
                uri,
                range: Range::new(Position::new(2, 4), Position::new(2, 9)),
            },
            container_name: Some("mod a".to_owned()),
        };
        let rows = workspace_symbols(
            "fake",
            Some(lsp::WorkspaceSymbolResponse::Flat(vec![
                symbol("go", inside),
                symbol("far", outside),
            ])),
            Some(&root),
        );
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].path, "src/lib.rs");
        assert_eq!(rows[0].absolute_path, "/tmp/proj/src/lib.rs");
        assert_eq!(rows[0].kind, "function");
        assert_eq!(rows[0].container.as_deref(), Some("mod a"));
        assert_eq!(rows[0].row, 2);
        assert_eq!(rows[0].col_utf16, 4);
        assert_eq!(rows[0].server, "fake");
        assert_eq!(rows[1].path, "/usr/include/x.h", "outside the project keeps its absolute name");
        assert!(workspace_symbols("fake", None, Some(&root)).is_empty());
    }

    #[test]
    fn a_server_log_is_a_bounded_ring_of_clipped_lines() {
        let logs: Logs = Arc::default();
        let key = (1, "fake");
        let long = "x".repeat(LOG_MESSAGE_CHARS + 50);
        log_line(&logs, key, "[stderr] ", &long);
        for i in 0..LOG_LINES + 5 {
            log_line(&logs, key, "← ", &format!("line {i}\n"));
        }
        let log = logs.lock().unwrap();
        let ring = log.get(&key).unwrap();
        assert_eq!(ring.lines.len(), LOG_LINES);
        assert_eq!(ring.version as usize, LOG_LINES + 6);
        // The oldest lines — the long one among them — fell off the front.
        assert_eq!(ring.lines.front().unwrap(), "← line 5");
        assert_eq!(ring.lines.back().unwrap(), &format!("← line {}", LOG_LINES + 4));
        drop(log);
        let clipped: Logs = Arc::default();
        log_line(&clipped, key, "[stderr] ", &long);
        let line = clipped.lock().unwrap()[&key].lines[0].clone();
        assert!(line.ends_with('…'));
        assert_eq!(line.chars().count(), "[stderr] ".len() + LOG_MESSAGE_CHARS + 1);
    }
}
