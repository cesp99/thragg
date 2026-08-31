//! Seeker IDE engine.
//!
//! This crate is the UI-free heart of the IDE: buffers, files, syntax,
//! language intelligence and agent (ACP) integration all live here.
//! The Android app talks to it exclusively through the `jni-bridge` crate.
//!
//! Buffers are backed by Zed's `text::Buffer` (a CRDT over a rope), vendored
//! in `core/vendor/`. Single-replica for now — collaboration features simply
//! lie dormant. Edits are grouped into undo transactions by `text`'s history
//! (time-based grouping), and every content mutation bumps a per-buffer
//! version counter so the UI can cheaply detect staleness.
//!
//! Projects are backed by Zed's `Worktree`, which needs GPUI's reactive
//! runtime. That runtime lives on a thread of its own (`runtime.rs`) behind a
//! headless `Platform` (`platform.rs`) that cannot draw; see `project.rs` for
//! how its state reaches the UI without blocking anything.
//!
//! Search comes in two shapes for the same reason Zed's does: over one open
//! buffer it is a synchronous scan fast enough to run on every keystroke of
//! the query (`search.rs`), and over a whole worktree it is background work
//! published behind a generation counter (`project_search.rs`).
//!
//! Git is not computed here either: it comes from the `git` binary inside the
//! Debian userland, reached through proot (`git.rs`), cached behind a
//! generation counter of the same shape, and silently empty in builds that
//! have no userland. What is ours is the *diff* (`git_diff.rs`): git supplies
//! the file's text at HEAD, and the hunks are computed against the live buffer
//! here, so the gutter tracks typing rather than the last save. Merge
//! conflicts are ours in the same way (`git_conflict.rs`): git leaves its
//! markers in the file, and the regions between them are read out of the live
//! buffer and resolved by one edit to it. Reaching *into* that userland at all is `guest.rs`, which
//! owns the proot command line for everything the engine runs there — git
//! today, language servers next.
//!
//! Tasks (`tasks.rs`) are Zed's: templates from `tasks.json` files and the
//! languages' built-ins, resolved against the caret's context, and the
//! grammars' `runnables.scm` marking the rows a play button belongs on. The
//! engine resolves; the terminal dock, which owns the pty, runs.

use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use rope::Point;
use sum_tree::Bias;

mod acp;
mod acp_elicit;
mod acp_question;
mod acp_terminal;
mod acp_thread;
mod appearance;
mod askpass;
mod config;
mod encoding;
mod file;
mod file_types;
mod find;
mod format;
mod git;
mod git_branches;
mod git_conflict;
mod git_diff;
mod git_history;
mod git_hunks;
mod git_patch;
mod git_remotes;
mod git_stash;
mod guest;
mod highlight;
mod highlight_worker;
mod keymap;
mod language_config;
mod lsp;
mod multibuffer;
mod platform;
mod project;
mod project_search;
mod runtime;
mod search;
mod session;
mod tasks;
mod toolchain;

pub use encoding::{LineEnding, available_encodings, encoding_named};
pub use appearance::{
    BufferLineHeight, DEFAULT_DARK_THEME, DEFAULT_ICON_THEME, DEFAULT_LIGHT_THEME, FontFeatures,
    IconThemeSelection, ResolvedFont, ThemeMode, ThemeSelection,
};
pub use config::{
    Autosave, BaseKeymap, BinarySettings, ContextServer, CustomAgent, FormatOnSave, Formatter,
    GitignoredFiles, LanguageSettings, LanguageSettingsContent, LspSettings,
    NotifyWhenAgentWaiting, ProjectPanelSettings, ProjectSettingsContent, RestoreOnStartup,
    Settings, SoftWrap,
};
pub use format::FormatOutcome;
pub use keymap::{KeybindSource, KeymapContext, KeymapLoad, Keystroke, ResolvedBinding};
pub use project::LOCAL_SETTINGS_PATH;
pub use tasks::{
    RunnableContext, RunnableRow, TaskEditorContext, TaskSource, TaskSpec, parse_tasks_json,
};
pub use task::TaskTemplate;
pub use find::FileMatch;
pub use git::{BranchInfo, ChangedFile, GitChanges, GitStatus};
pub use askpass::{PendingPrompt, PromptKind};
pub use git_remotes::{RemoteEntry, RemoteOutput};
pub use git_branches::{BranchEntry, BranchList};
pub use git_conflict::{ConflictRegion, Keep as ConflictKeep};
pub use git_diff::{BlameEntry, Hunk, HunkKind};
pub use git_hunks::HunkState;
pub use git_stash::{StashEntry, StashKind};
pub use highlight::{
    HighlightSpan, LanguageInfo, OutlineItem, STYLE_NAMES, TextRange, available_languages,
    language_for_path,
};
pub use language_config::config_json as language_config_json;
// `crate::` spelled out: the module and the vendored crate it wraps share a
// name, and an unqualified `lsp::` here would be ambiguous.
pub use crate::lsp::{
    BufferDiagnostics, Counts, DiagnosticRow, ProjectDiagnostics, RequestKind, RequestResult,
    RequestState, ServerState, ServerStatus, Severity,
};
pub use multibuffer::{
    ExcerptInfo, ExcerptSpec, MultiBufferId, MultiBufferInfo, MultiBufferLocation, SaveAllReport,
    parse_specs as parse_excerpt_specs,
};
pub use project::{ProjectId, TrashedEntry, TreeEntry};
pub use project_search::{
    FileMatches, LineMatch, ProjectReplaceSummary, SearchId, SearchResults, SearchState,
};
pub use search::{BufferMatch, BufferSearch, ReplaceOutcome, SearchOptions};
pub use session::{RecentProject, SESSION_VERSION, SessionDocument};
pub use toolchain::{Toolchain, ToolchainEnv};

pub const ENGINE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// A (row, UTF-16 column) position as a byte offset into `rope`, clipped to
/// the text — the pane's coordinates turned into the tree's.
fn utf16_offset(rope: &rope::Rope, row: u32, col_utf16: u32) -> usize {
    let point = rope.clip_point_utf16(
        rope::Unclipped(rope::PointUtf16::new(row, col_utf16)),
        text::Bias::Left,
    );
    rope.point_utf16_to_offset(point)
}

/// The inverse of [`utf16_offset`] over a whole byte range: what the pane can
/// place a caret or a highlight at.
fn text_range(rope: &rope::Rope, range: std::ops::Range<usize>) -> highlight::TextRange {
    let start = rope.offset_to_point_utf16(range.start.min(rope.len()));
    let end = rope.offset_to_point_utf16(range.end.min(rope.len()));
    highlight::TextRange {
        start_row: start.row,
        start_col_utf16: start.column,
        end_row: end.row,
        end_col_utf16: end.column,
    }
}

/// Tell the engine where the app's private storage is. Call this once, from
/// the platform layer, before anything else touches the engine.
///
/// Android runs apps without `$HOME`, but the vendored Zed crates assume a
/// home directory exists — `util::paths::home_dir()` panics outright without
/// one, taking a worktree scan down with it, and `dirs`' config/data lookups
/// derive from it too. An app does have a home; the OS simply doesn't export
/// it, so we do. The same directory anchors the trash, which must sit on the
/// same filesystem as the projects it swallows.
pub fn initialize(files_dir: &Path) {
    if std::env::var_os("HOME").is_none() {
        // SAFETY: `set_var` is unsound only against concurrent environment
        // access. This runs from the platform layer at startup, before the
        // engine has spawned a thread of its own.
        unsafe { std::env::set_var("HOME", files_dir) };
    }
    trash::set_root(files_dir.join(".trash"));
    config::set_directory(files_dir.to_path_buf());
    keymap::set_directory(files_dir.to_path_buf());
    session::set_directory(files_dir.to_path_buf());
    toolchain::set_directory(files_dir.to_path_buf());
}

pub type BufferId = u64;

/// The open buffers, keyed by id and shared with the worker threads.
///
/// The map is write-locked only to open or close a buffer; everything else
/// takes the read lock, clones the one buffer's `Arc` out, and locks just
/// that buffer. So the UI reading one buffer's highlights never waits on the
/// worker installing another buffer's parse — under one map-wide mutex they
/// serialized, which showed up as stutter during fast typing. Lock order: a
/// buffer's mutex may be taken while the map's read lock is held, never the
/// reverse.
pub(crate) type Buffers = Arc<RwLock<HashMap<BufferId, Arc<Mutex<BufferState>>>>>;

static NEXT_BUFFER_ID: AtomicU64 = AtomicU64::new(1);

#[derive(Default)]
pub struct Engine {
    /// Shared so the worktree watcher can reach open buffers from the
    /// runtime thread without a handle on the whole engine (see file.rs).
    buffers: Buffers,
    projects: Mutex<HashMap<ProjectId, Arc<Mutex<project::ProjectState>>>>,
    next_project_id: AtomicU64,
    /// Started on the first `open_project`, so buffer-only use (and most
    /// tests) never pays for a gpui App. The platform layer may start it
    /// earlier with [`Engine::start_runtime`]; either way the boot happens on
    /// the runtime's own thread and nobody blocks on it (see runtime.rs).
    runtime: OnceLock<runtime::Runtime>,
    /// Started when a buffer first gets a language. Reparsing happens here,
    /// never on the caller's thread — see highlight_worker.rs.
    highlight_worker: OnceLock<highlight_worker::HighlightWorker>,
    /// Cached `git status` per project. Empty and quiet until there is a
    /// userland to run git in — see git.rs.
    git: git::GitStatuses,
    /// Cached diff hunks per file-backed buffer: the base text from HEAD, and
    /// the difference between it and the live buffer — see git_diff.rs.
    diffs: git_diff::BufferDiffs,
    /// Where proot and the Debian rootfs are. It sits here rather than on one
    /// feature's state because it belongs to no feature: git status runs
    /// through it today and language servers will, and there is only ever one
    /// guest — see guest.rs.
    userland: Mutex<Option<Arc<guest::Userland>>>,
    /// The credential helper the network-facing git commands run with, and
    /// the prompts it is holding for the UI — started with the userland,
    /// because its pipe lives in the rootfs's `/tmp`. See askpass.rs.
    askpass: Mutex<Option<Arc<askpass::AskpassServer>>>,
    /// The project search running for each project, at most one apiece — see
    /// project_search.rs.
    searches: project_search::ProjectSearches,
    /// Language servers, the documents they have been told about and the
    /// diagnostics they have published — see lsp.rs. Inert, and costing one
    /// relaxed atomic load per edit, until a server actually starts.
    lsp: lsp::LspState,
    /// The ACP agent connection and its sessions — see acp.rs. Inert until
    /// the panel starts one; the `play` flavour never does.
    acp: acp::AcpState,
    /// Bumped whenever settings.json is written through the engine — a
    /// settings-screen edit, a whole-file write, a save of the file's own
    /// tab. What lets a cache keyed on "the settings as they were" notice
    /// they are not any more; see `lsp::project_tree_servers`.
    settings_generation: AtomicU64,
    /// Open multibuffers, and the index the edit path consults to route an
    /// edit made in one to the file it belongs to — see multibuffer.rs.
    multibuffers: multibuffer::MultiBuffers,
}

pub(crate) struct BufferState {
    pub(crate) buffer: text::Buffer,
    /// Monotonic content version: bumped by edit/undo/redo. Not a CRDT
    /// vector clock — just a cheap staleness check for the UI layer.
    pub(crate) version: u64,
    /// Present once a language has been assigned (interim tree-sitter
    /// highlighting; see highlight.rs).
    pub(crate) highlight: Option<highlight::HighlightState>,
    /// Present for buffers backed by a file on disk (see file.rs).
    file: Option<file::FileState>,
}

impl BufferState {
    fn line_count(&self) -> u32 {
        self.buffer.max_point().row + 1
    }

    /// What `Engine::buffer_is_dirty` answers, for a caller already holding
    /// the lock. A buffer with no file is never dirty.
    pub(crate) fn is_dirty(&self) -> bool {
        self.file
            .as_ref()
            .is_some_and(|file| self.version != file.saved_version || file.shape_changed)
    }

    /// Undo or redo just stepped over `transaction`; `version` has been
    /// bumped for it. A reload that changed the encoding takes the
    /// encoding — and the dirty state of the side it lands on — across with it
    /// (see `FileState::restore_encoding_for_transaction`).
    fn crossed_transaction(&mut self, transaction: text::TransactionId, was_dirty: bool) {
        let version = self.version;
        if let Some(file) = &mut self.file {
            file.restore_encoding_for_transaction(transaction, was_dirty, version);
        }
    }

    /// The canonical path of the file behind this buffer, when there is one —
    /// for callers holding the shared buffer map without an `Engine` (the
    /// worktree watcher, the ACP fs handlers).
    pub(crate) fn file_path(&self) -> Option<&Path> {
        self.file.as_ref().map(|file| file.path.as_path())
    }

    /// Mark the tree stale after a history operation, where the edit shape
    /// isn't readily available for an incremental tree edit. The reparse
    /// itself is the worker's job.
    fn reset_highlighter(&mut self) -> bool {
        if let Some(highlighter) = &mut self.highlight {
            highlighter.invalidate();
            true
        } else {
            false
        }
    }
}

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    /// See [`Engine::settings_generation`] on the struct.
    pub(crate) fn settings_generation(&self) -> u64 {
        self.settings_generation.load(Ordering::Relaxed)
    }

    pub(crate) fn note_settings_written(&self) {
        self.settings_generation.fetch_add(1, Ordering::Relaxed);
    }

    /// The gpui runtime, started on first use.
    fn runtime(&self) -> &runtime::Runtime {
        self.runtime
            .get_or_init(|| runtime::Runtime::new(project::init_globals))
    }

    /// Start the gpui runtime now rather than on the first project open.
    ///
    /// Returns at once — it only spawns the runtime thread, on which the
    /// `Application` then boots by itself — so the platform layer can warm the
    /// runtime during startup without keeping anything waiting. Work handed
    /// over before the boot finishes queues on the runtime's job channel
    /// (see runtime.rs); nothing needs to check readiness.
    pub fn start_runtime(&self) {
        self.runtime();
    }

    /// Ask the highlight worker to bring a buffer's tree up to date.
    fn request_highlight(&self, id: BufferId) {
        self.highlight_worker
            .get_or_init(|| highlight_worker::HighlightWorker::new(self.buffers.clone()))
            .request(id);
    }

    fn next_project_id(&self) -> ProjectId {
        self.next_project_id.fetch_add(1, Ordering::Relaxed) + 1
    }

    pub fn create_buffer(&self, initial_text: &str) -> BufferId {
        let id = NEXT_BUFFER_ID.fetch_add(1, Ordering::Relaxed);
        let remote_id = text::BufferId::new(id).expect("buffer ids start at 1");
        let buffer = text::Buffer::new(clock::ReplicaId::LOCAL, remote_id, initial_text);
        self.buffers.write().unwrap().insert(
            id,
            Arc::new(Mutex::new(BufferState {
                buffer,
                version: 0,
                highlight: None,
                file: None,
            })),
        );
        id
    }

    /// Assign a tree-sitter language (by grammar name, e.g. "rust") to the
    /// buffer and parse it. Returns false for unknown language names.
    pub fn set_language(&self, id: BufferId, language: &str) -> Result<bool, EngineError> {
        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        let rope = state.buffer.as_rope().clone();
        state.highlight = highlight::HighlightState::new(language, &rope);
        Ok(state.highlight.is_some())
    }

    pub fn close_buffer(&self, id: BufferId) -> bool {
        // Before the buffer goes: the LSP side reads its path out of the
        // registry it keeps, but a request still in flight against this buffer
        // has to be retired here rather than left holding a slot forever.
        self.lsp_did_close(id);
        self.buffers.write().unwrap().remove(&id).is_some()
    }

    /// Replace the byte range `start..end` with `text`, returning the new
    /// buffer version. Offsets are in bytes and must lie on UTF-8 character
    /// boundaries.
    ///
    /// An edit on a multibuffer's composed buffer is *routed* to the file the
    /// offset belongs to and replayed into the composition, so every caller —
    /// typing, paste, a code action — edits a multibuffer without knowing it
    /// is one (see multibuffer.rs). With no multibuffer open the check is one
    /// relaxed atomic load.
    pub fn edit(
        &self,
        id: BufferId,
        start: usize,
        end: usize,
        text: &str,
    ) -> Result<u64, EngineError> {
        match self.multibuffer_for_mirror(id) {
            Some(multibuffer) => self.multibuffer_edit(multibuffer, start, end, text),
            None => self.edit_buffer(id, start, end, text),
        }
    }

    /// The edit itself, against one buffer and nothing else.
    pub(crate) fn edit_buffer(
        &self,
        id: BufferId,
        start: usize,
        end: usize,
        text: &str,
    ) -> Result<u64, EngineError> {
        let buffer = self.buffer(id)?;
        let mut guard = buffer.lock().unwrap();
        let state = &mut *guard;
        let snapshot = state.buffer.snapshot();
        if start > end
            || end > snapshot.len()
            || snapshot.clip_offset(start, Bias::Left) != start
            || snapshot.clip_offset(end, Bias::Left) != end
        {
            return Err(EngineError::InvalidRange { start, end });
        }
        let start_point = snapshot.offset_to_point(start);
        let old_end_point = snapshot.offset_to_point(end);
        // A `didChange` carries the range in the *old* text, which only this
        // snapshot still knows. Taken before the edit and only when a server is
        // actually running — the `play` flavour pays one relaxed load for it.
        let lsp_range = self.lsp_is_live().then(|| {
            (
                snapshot.offset_to_point_utf16(start),
                snapshot.offset_to_point_utf16(end),
            )
        });
        state.buffer.edit([(start..end, text)]);
        state.version += 1;
        let needs_highlight = if let Some(highlighter) = &mut state.highlight {
            let new_end = start + text.len();
            let rope = state.buffer.as_rope().clone();
            let new_end_point = rope.offset_to_point(new_end);
            highlighter.edited(
                &rope,
                start,
                end,
                new_end,
                start_point,
                old_end_point,
                new_end_point,
            );
            true
        } else {
            false
        };
        let version = state.version;
        let lsp_change = lsp_range.map(|(start, old_end)| lsp::TextChange {
            start,
            old_end,
            text: text.to_owned(),
            // Only for a server that negotiated whole-document sync, because
            // this is O(file) and sits on the keystroke path. None of the
            // servers we start does; the flag is here so that one which did
            // would work rather than silently drift.
            whole: self.lsp_wants_full_text().then(|| state.buffer.text()),
            buffer_version: version,
        });
        // Release the lock before touching the worker.
        drop(guard);
        if needs_highlight {
            self.request_highlight(id);
        }
        if let Some(change) = lsp_change {
            self.lsp_did_change(id, change);
        }
        Ok(version)
    }

    /// Undo the most recent transaction. Returns the new version, or `None`
    /// if there was nothing to undo.
    ///
    /// On a multibuffer this undoes in the file the last edit went to, which
    /// is what Zed's multibuffer undo does.
    pub fn undo(&self, id: BufferId) -> Result<Option<u64>, EngineError> {
        match self.multibuffer_for_mirror(id) {
            Some(multibuffer) => self.multibuffer_undo(multibuffer),
            None => self.undo_buffer(id),
        }
    }

    pub(crate) fn undo_buffer(&self, id: BufferId) -> Result<Option<u64>, EngineError> {
        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        let old_end = self
            .lsp_is_live()
            .then(|| state.buffer.snapshot().max_point_utf16());
        let was_dirty = state.is_dirty();
        let result = state.buffer.undo().map(|(transaction, _)| {
            state.version += 1;
            state.crossed_transaction(transaction, was_dirty);
            let needs_highlight = state.reset_highlighter();
            (state.version, needs_highlight)
        });
        let lsp_change = result
            .is_some()
            .then(|| self.history_change(&state, old_end))
            .flatten();
        drop(state);
        if let Some(change) = lsp_change {
            self.lsp_did_change(id, change);
        }
        Ok(result.map(|(version, needs_highlight)| {
            if needs_highlight {
                self.request_highlight(id);
            }
            version
        }))
    }

    /// Undo, redo and reload replace text wholesale: there is no edit shape to
    /// hand a server. What goes out is one change covering the whole of the old
    /// document, which is a perfectly ordinary incremental `didChange` — so the
    /// LSP side keeps a single code path instead of a special case.
    fn history_change(
        &self,
        state: &BufferState,
        old_end: Option<rope::PointUtf16>,
    ) -> Option<lsp::TextChange> {
        let old_end = old_end?;
        let text = state.buffer.text();
        Some(lsp::TextChange {
            start: rope::PointUtf16::new(0, 0),
            old_end,
            whole: self.lsp_wants_full_text().then(|| text.clone()),
            text,
            buffer_version: state.version,
        })
    }

    /// Redo the most recently undone transaction. Returns the new version,
    /// or `None` if there was nothing to redo.
    pub fn redo(&self, id: BufferId) -> Result<Option<u64>, EngineError> {
        match self.multibuffer_for_mirror(id) {
            Some(multibuffer) => self.multibuffer_redo(multibuffer),
            None => self.redo_buffer(id),
        }
    }

    pub(crate) fn redo_buffer(&self, id: BufferId) -> Result<Option<u64>, EngineError> {
        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        let old_end = self
            .lsp_is_live()
            .then(|| state.buffer.snapshot().max_point_utf16());
        let was_dirty = state.is_dirty();
        let result = state.buffer.redo().map(|(transaction, _)| {
            state.version += 1;
            state.crossed_transaction(transaction, was_dirty);
            let needs_highlight = state.reset_highlighter();
            (state.version, needs_highlight)
        });
        let lsp_change = result
            .is_some()
            .then(|| self.history_change(&state, old_end))
            .flatten();
        drop(state);
        if let Some(change) = lsp_change {
            self.lsp_did_change(id, change);
        }
        Ok(result.map(|(version, needs_highlight)| {
            if needs_highlight {
                self.request_highlight(id);
            }
            version
        }))
    }

    /// Highlight spans for rows `first_row..last_row` (end-exclusive,
    /// clipped). Empty when the buffer has no language assigned.
    pub fn highlights(
        &self,
        id: BufferId,
        first_row: u32,
        last_row: u32,
    ) -> Result<Vec<HighlightSpan>, EngineError> {
        self.with_buffer(id, |state| {
            let Some(highlighter) = &state.highlight else {
                return Vec::new();
            };
            let rope = state.buffer.as_rope();
            let line_count = state.line_count();
            let first = first_row.min(line_count);
            let last = last_row.min(line_count);
            if first >= last {
                return Vec::new();
            }
            let start = rope.point_to_offset(Point::new(first, 0));
            let end = rope.point_to_offset(Point::new(last - 1, rope.line_len(last - 1)));
            highlighter.highlights(rope, start..end)
        })
    }

    /// The symbol path containing the caret, outermost first — Zed's
    /// breadcrumbs after the file name (editor.rs `breadcrumbs` →
    /// `buffer.symbols_containing`). Reads the last parsed tree only; a
    /// caret move never pays for a parse, so right after an edit the answer
    /// can be one worker round-trip stale, exactly like the highlights.
    pub fn outline_path(
        &self,
        id: BufferId,
        row: u32,
        col_utf16: u32,
    ) -> Result<Vec<String>, EngineError> {
        self.with_buffer(id, |state| {
            let Some(highlighter) = &state.highlight else {
                return Vec::new();
            };
            let rope = state.buffer.as_rope();
            let point = rope.clip_point_utf16(
                rope::Unclipped(rope::PointUtf16::new(row, col_utf16)),
                text::Bias::Left,
            );
            let offset = rope.point_utf16_to_offset(point);
            highlighter.outline_path(rope, offset)
        })
    }

    /// Every outline item in the buffer, in source order with nesting depths
    /// — the rows of Zed's outline picker. Same staleness contract as
    /// [`Self::outline_path`]: reads the last parsed tree, never parses.
    pub fn outline(&self, id: BufferId) -> Result<Vec<highlight::OutlineItem>, EngineError> {
        self.with_buffer(id, |state| {
            let Some(highlighter) = &state.highlight else {
                return Vec::new();
            };
            highlighter.outline_items(state.buffer.as_rope())
        })
    }

    /// Every foldable block in the buffer, from the syntax tree — the rows
    /// a gutter chevron collapses, computed the way the README promises:
    /// from the language's own grammar rather than from indentation. Same
    /// staleness contract as [`Self::outline`]: reads the last parsed tree,
    /// never parses. Empty for a buffer with no language or a grammar with
    /// no `indents.scm`, where the UI keeps its indent walk.
    pub fn fold_ranges(&self, id: BufferId) -> Result<Vec<highlight::FoldRange>, EngineError> {
        self.with_buffer(id, |state| {
            let Some(highlighter) = &state.highlight else {
                return Vec::new();
            };
            highlighter.fold_ranges(state.buffer.as_rope())
        })
    }

    /// The smallest syntax node that strictly contains the given range —
    /// what `editor::SelectLargerSyntaxNode` grows a selection to, and the
    /// only thing the pane needs from the tree to do it. None when the range
    /// already covers the file, the buffer has no language, or the tree has
    /// not been parsed yet.
    ///
    /// Same staleness contract as [`Self::outline_path`]: reads the last
    /// parsed tree, never parses. A selection grown against a tree one
    /// reparse behind is a selection the next press corrects.
    pub fn syntax_node_range(
        &self,
        id: BufferId,
        start_row: u32,
        start_col_utf16: u32,
        end_row: u32,
        end_col_utf16: u32,
    ) -> Result<Option<highlight::TextRange>, EngineError> {
        self.with_buffer(id, |state| {
            let highlighter = state.highlight.as_ref()?;
            let rope = state.buffer.as_rope();
            let start = utf16_offset(rope, start_row, start_col_utf16);
            let end = utf16_offset(rope, end_row, end_col_utf16);
            let grown = highlighter.syntax_ancestor(start.min(end)..end.max(start))?;
            Some(text_range(rope, grown))
        })
    }

    /// The innermost bracket pair around the caret, as `(open, close)` — what
    /// the pane highlights and what `editor::MoveToEnclosingBracket` jumps
    /// between. From the grammar's `brackets.scm` where there is one, from a
    /// delimiter count where there is not, so a plain-text buffer still
    /// matches its braces. None when nothing encloses the caret.
    pub fn enclosing_brackets(
        &self,
        id: BufferId,
        start_row: u32,
        start_col_utf16: u32,
        end_row: u32,
        end_col_utf16: u32,
    ) -> Result<Option<(highlight::TextRange, highlight::TextRange)>, EngineError> {
        self.with_buffer(id, |state| {
            let rope = state.buffer.as_rope();
            let start = utf16_offset(rope, start_row, start_col_utf16);
            let end = utf16_offset(rope, end_row, end_col_utf16);
            let range = start.min(end)..end.max(start);
            let (open, close) = match state.highlight.as_ref() {
                Some(highlighter) => highlighter.enclosing_brackets(rope, range)?,
                None => highlight::counted_brackets(rope, range)?,
            };
            Some((text_range(rope, open), text_range(rope, close)))
        })
    }

    /// The grammar the buffer is highlighted with ("rust", "markdown"), or
    /// None if it has no language.
    /// Bumped when a reparse lands. The content version doesn't move then,
    /// so the UI watches this to know its spans are stale.
    pub fn buffer_highlight_version(&self, id: BufferId) -> u64 {
        self.with_buffer(id, |state| {
            state
                .highlight
                .as_ref()
                .map(|highlight| highlight.version())
                .unwrap_or(0)
        })
        .unwrap_or(0)
    }

    /// For each byte offset, a bitmask of the bracket pairs of the buffer's
    /// language that are live there — bit *i* for pair *i* of the `brackets`
    /// list in `language_config_json`.
    ///
    /// This is the half of the language config that is not data: a pair
    /// carrying `not_in = ["string", "comment"]` is live or not depending on
    /// where the caret sits in the syntax tree, and only the engine has the
    /// tree. Everything is live for a buffer with no language, and for a
    /// language whose pairs are unconditional — the ordinary brackets — so the
    /// UI never needs to ask about `(` or `{`.
    ///
    /// It costs an incremental reparse when the tree is stale, which is why it
    /// takes every caret's offset at once and why the UI must only call it
    /// when a pair character is actually typed.
    pub fn bracket_scopes(&self, id: BufferId, offsets: &[usize]) -> Result<Vec<u64>, EngineError> {
        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        let rope = state.buffer.as_rope().clone();
        let Some(highlighter) = &mut state.highlight else {
            return Ok(vec![u64::MAX; offsets.len()]);
        };
        // Never a *full* parse on this path. An incremental one is a
        // millisecond and buys a correct answer for the character just typed;
        // a full one is 30 ms on a quarter-megabyte buffer — measured — and
        // this call holds the buffer lock, so the renderer and the highlight
        // worker would stall with it. Undo sets `needs_full_parse`, so a quote
        // typed straight after an undo would have paid it.
        //
        // When that is the state, answer "everything is live": autoclose then
        // behaves as it did before scopes existed, which is a pair inserted
        // inside a string rather than a dropped frame.
        if highlighter.needs_full_reparse() {
            return Ok(vec![u64::MAX; offsets.len()]);
        }
        highlighter.ensure_parsed(&rope);
        let name = highlighter.name();
        let Some(tree) = highlighter.tree() else {
            return Ok(vec![u64::MAX; offsets.len()]);
        };
        Ok(offsets
            .iter()
            .map(|&offset| {
                let offset = offset.min(rope.len());
                // The innermost injected layer, so a quote typed inside a
                // Markdown fence is judged by the fenced language's rules.
                let layer = highlighter.scope_layer_at(offset);
                language_config::enabled_brackets(name, tree, layer, &rope, offset)
            })
            .collect())
    }

    pub fn buffer_language(&self, id: BufferId) -> Option<&'static str> {
        self.with_buffer(id, |state| {
            state.highlight.as_ref().map(|highlight| highlight.name())
        })
        .ok()
        .flatten()
    }

    pub fn version(&self, id: BufferId) -> Result<u64, EngineError> {
        self.with_buffer(id, |state| state.version)
    }

    pub fn text(&self, id: BufferId) -> Result<String, EngineError> {
        self.with_buffer(id, |state| state.buffer.text())
    }

    pub fn len(&self, id: BufferId) -> Result<usize, EngineError> {
        self.with_buffer(id, |state| state.buffer.len())
    }

    pub fn line_count(&self, id: BufferId) -> Result<u32, EngineError> {
        self.with_buffer(id, |state| state.line_count())
    }

    /// The text of rows `first_row..last_row` (end-exclusive, clipped to the
    /// buffer), joined with `\n` and without a trailing newline.
    pub fn lines(
        &self,
        id: BufferId,
        first_row: u32,
        last_row: u32,
    ) -> Result<String, EngineError> {
        self.with_buffer(id, |state| {
            let snapshot = state.buffer.snapshot();
            let line_count = state.line_count();
            let first = first_row.min(line_count);
            let last = last_row.min(line_count);
            if first >= last {
                return String::new();
            }
            let start = Point::new(first, 0);
            let end = Point::new(last - 1, snapshot.line_len(last - 1));
            snapshot.text_for_range(start..end).collect()
        })
    }

    /// Convert a (row, column) position to a byte offset, clipping to the
    /// buffer contents.
    pub fn point_to_offset(
        &self,
        id: BufferId,
        row: u32,
        column: u32,
    ) -> Result<usize, EngineError> {
        self.with_buffer(id, |state| {
            let snapshot = state.buffer.snapshot();
            let point = snapshot.clip_point(Point::new(row, column), Bias::Left);
            snapshot.point_to_offset(point)
        })
    }

    /// Convert a byte offset to a (row, column) position, clipping to the
    /// buffer contents.
    pub fn offset_to_point(&self, id: BufferId, offset: usize) -> Result<(u32, u32), EngineError> {
        self.with_buffer(id, |state| {
            let snapshot = state.buffer.snapshot();
            let offset = snapshot.clip_offset(offset, Bias::Left);
            let point = snapshot.offset_to_point(offset);
            (point.row, point.column)
        })
    }

    /// The shared handle of one buffer. The `Arc` is cloned out from under
    /// the map's read lock, so whatever the caller then does under the
    /// buffer's own lock holds up neither the map nor any other buffer.
    pub(crate) fn buffer(&self, id: BufferId) -> Result<Arc<Mutex<BufferState>>, EngineError> {
        self.buffers
            .read()
            .unwrap()
            .get(&id)
            .cloned()
            .ok_or(EngineError::UnknownBuffer(id))
    }

    fn with_buffer<T>(
        &self,
        id: BufferId,
        f: impl FnOnce(&BufferState) -> T,
    ) -> Result<T, EngineError> {
        let state = self.buffer(id)?;
        let state = state.lock().unwrap();
        Ok(f(&state))
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum EngineError {
    UnknownBuffer(BufferId),
    InvalidRange {
        start: usize,
        end: usize,
    },
    /// The operation needs a file behind the buffer, and there isn't one.
    NoFile(BufferId),
    UnknownProject(ProjectId),
    UnknownMultiBuffer(MultiBufferId),
    /// Every file a multibuffer was asked for was unreadable, so there is
    /// nothing to show.
    EmptyMultiBuffer,
    /// An edit in a multibuffer that lands on a header row, or spans two
    /// excerpts: there is no single file it could go to.
    NotInAnExcerpt,
    /// A search query that does not compile; carries the regex engine's
    /// complaint, which is worth showing the user verbatim.
    InvalidQuery(String),
    /// The engine was never told where settings live (see `initialize`).
    NoSettingsFile,
    /// Settings text that doesn't parse; carries the parser's complaint.
    InvalidSettings(String),
    Io {
        path: String,
        message: String,
    },
}

impl std::fmt::Display for EngineError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            EngineError::UnknownBuffer(id) => write!(f, "unknown buffer {id}"),
            EngineError::InvalidRange { start, end } => {
                write!(f, "invalid range {start}..{end}")
            }
            EngineError::NoFile(id) => write!(f, "buffer {id} is not backed by a file"),
            EngineError::UnknownProject(id) => write!(f, "unknown project {id}"),
            EngineError::UnknownMultiBuffer(id) => write!(f, "unknown multibuffer {id}"),
            EngineError::EmptyMultiBuffer => write!(f, "no readable file to excerpt"),
            EngineError::NotInAnExcerpt => {
                write!(f, "that position is not inside a single excerpt")
            }
            EngineError::InvalidQuery(message) => write!(f, "invalid search query: {message}"),
            EngineError::NoSettingsFile => write!(f, "no settings directory configured"),
            EngineError::InvalidSettings(message) => write!(f, "invalid settings: {message}"),
            EngineError::Io { path, message } => write!(f, "{path}: {message}"),
        }
    }
}

impl std::error::Error for EngineError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn buffer_roundtrip() {
        let engine = Engine::new();
        let id = engine.create_buffer("hello world");
        engine.edit(id, 6, 11, "seeker").unwrap();
        assert_eq!(engine.text(id).unwrap(), "hello seeker");
        assert!(engine.close_buffer(id));
        assert_eq!(engine.text(id), Err(EngineError::UnknownBuffer(id)));
    }

    #[test]
    fn rejects_bad_ranges() {
        let engine = Engine::new();
        let id = engine.create_buffer("héllo");
        assert_eq!(
            engine.edit(id, 2, 3, "x"),
            Err(EngineError::InvalidRange { start: 2, end: 3 })
        );
        assert_eq!(
            engine.edit(id, 4, 3, "x"),
            Err(EngineError::InvalidRange { start: 4, end: 3 })
        );
        assert_eq!(
            engine.edit(id, 0, 100, "x"),
            Err(EngineError::InvalidRange { start: 0, end: 100 })
        );
    }

    #[test]
    fn versions_are_monotonic() {
        let engine = Engine::new();
        let id = engine.create_buffer("abc");
        assert_eq!(engine.version(id).unwrap(), 0);
        let v1 = engine.edit(id, 3, 3, "d").unwrap();
        let v2 = engine.edit(id, 4, 4, "e").unwrap();
        assert!(v2 > v1);
        assert_eq!(engine.version(id).unwrap(), v2);
    }

    #[test]
    fn undo_redo() {
        let engine = Engine::new();
        let id = engine.create_buffer("hello");
        engine.edit(id, 5, 5, " world").unwrap();
        assert_eq!(engine.text(id).unwrap(), "hello world");

        let undo_version = engine.undo(id).unwrap();
        assert!(undo_version.is_some());
        assert_eq!(engine.text(id).unwrap(), "hello");

        let redo_version = engine.redo(id).unwrap();
        assert!(redo_version.is_some());
        assert_eq!(engine.text(id).unwrap(), "hello world");

        // Nothing left to redo.
        assert_eq!(engine.redo(id).unwrap(), None);
    }

    /// The editor indents a multi-row selection as one batch of edits,
    /// applied back to front so no offset is moved by an earlier one. The
    /// text history groups them into one transaction, and undoing it must
    /// hide exactly the inserted fragments — every row back where it was,
    /// nothing after an insertion point touched.
    #[test]
    fn a_back_to_front_batch_undoes_as_one_step() {
        let engine = Engine::new();
        let before = "fn main() {\n    println!(\"Hello, world!\");\n}\n";
        let id = engine.create_buffer(before);
        for row_start in [43, 12, 0] {
            engine.edit(id, row_start, row_start, "    ").unwrap();
        }
        assert_eq!(
            engine.text(id).unwrap(),
            "    fn main() {\n        println!(\"Hello, world!\");\n    }\n"
        );

        assert!(engine.undo(id).unwrap().is_some());
        assert_eq!(engine.text(id).unwrap(), before);
        assert_eq!(engine.undo(id).unwrap(), None);

        assert!(engine.redo(id).unwrap().is_some());
        assert_eq!(
            engine.text(id).unwrap(),
            "    fn main() {\n        println!(\"Hello, world!\");\n    }\n"
        );
    }

    #[test]
    fn line_windows() {
        let engine = Engine::new();
        let id = engine.create_buffer("one\ntwo\nthree\nfour");
        assert_eq!(engine.line_count(id).unwrap(), 4);
        assert_eq!(engine.lines(id, 0, 4).unwrap(), "one\ntwo\nthree\nfour");
        assert_eq!(engine.lines(id, 1, 3).unwrap(), "two\nthree");
        assert_eq!(engine.lines(id, 3, 4).unwrap(), "four");
        // Ranges are clipped, not rejected.
        assert_eq!(engine.lines(id, 2, 100).unwrap(), "three\nfour");
        assert_eq!(engine.lines(id, 100, 200).unwrap(), "");
        assert_eq!(engine.lines(id, 3, 3).unwrap(), "");
    }

    #[test]
    fn point_offset_conversions() {
        let engine = Engine::new();
        let id = engine.create_buffer("ab\ncd");
        assert_eq!(engine.point_to_offset(id, 1, 0).unwrap(), 3);
        assert_eq!(engine.offset_to_point(id, 4).unwrap(), (1, 1));
        // Clipped past the end of a line / buffer.
        assert_eq!(engine.point_to_offset(id, 0, 99).unwrap(), 2);
        assert_eq!(engine.offset_to_point(id, 99).unwrap(), (1, 2));
    }

    #[test]
    fn highlights_rust() {
        let engine = Engine::new();
        let id = engine.create_buffer("fn main() {\n    let x = 42; // answer\n}\n");
        // No language yet: no spans.
        assert!(engine.highlights(id, 0, 3).unwrap().is_empty());
        assert!(!engine.set_language(id, "not-a-language").unwrap());
        assert!(engine.set_language(id, "rust").unwrap());

        let spans = engine.highlights(id, 0, 3).unwrap();
        let style_at = |row: u32, col: u32| {
            spans
                .iter()
                .filter(|s| s.row == row && s.start_col_utf16 <= col && col < s.end_col_utf16)
                .map(|s| STYLE_NAMES[s.style as usize])
                .last()
        };
        // "fn" and "let" are keywords, "42" a number, the comment a comment.
        assert_eq!(style_at(0, 0), Some("keyword"));
        assert_eq!(style_at(1, 4), Some("keyword"));
        assert_eq!(style_at(1, 12), Some("number"));
        assert_eq!(style_at(1, 17), Some("comment"));

        // Window clipping: row 1 only.
        assert!(
            engine
                .highlights(id, 1, 2)
                .unwrap()
                .iter()
                .all(|s| s.row == 1)
        );

        // Reparsing is asynchronous — it costs milliseconds on a large file
        // and must not sit on the keystroke path — so spans catch up shortly
        // after an edit rather than during it. Callers watch
        // `buffer_highlight_version` for that; tests do the same.
        let has_style = |engine: &Engine, style: &str| {
            engine
                .highlights(id, 1, 2)
                .unwrap()
                .iter()
                .any(|s| STYLE_NAMES[s.style as usize] == style)
        };

        // "42" -> "\"hi\"" becomes a string.
        let offset = engine.point_to_offset(id, 1, 12).unwrap();
        engine.edit(id, offset, offset + 2, "\"hi\"").unwrap();
        wait_for_highlights(&engine, id, |engine| has_style(engine, "string"));

        // Undo invalidates the tree; the number comes back.
        engine.undo(id).unwrap();
        wait_for_highlights(&engine, id, |engine| has_style(engine, "number"));
    }

    /// Spin until the highlight worker has caught up and `ready` holds.
    #[track_caller]
    fn wait_for_highlights(engine: &Engine, id: BufferId, ready: impl Fn(&Engine) -> bool) {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while std::time::Instant::now() < deadline {
            if ready(engine) {
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(5));
        }
        let _ = id;
        panic!("the highlight worker never produced the expected spans");
    }

    #[test]
    fn highlight_columns_are_utf16() {
        let engine = Engine::new();
        // '€' is 3 UTF-8 bytes but 1 UTF-16 unit; the string after it must
        // report UTF-16 columns.
        let id = engine.create_buffer("let e = \"€\"; let n = 7;");
        assert!(engine.set_language(id, "rust").unwrap());
        let spans = engine.highlights(id, 0, 1).unwrap();
        let number = spans
            .iter()
            .find(|s| STYLE_NAMES[s.style as usize] == "number")
            .expect("number span");
        // "let e = \"€\"; let n = " is 21 UTF-16 units; the 7 sits at col 21.
        assert_eq!(number.start_col_utf16, 21);
        assert_eq!(number.end_col_utf16, 22);
    }

    #[test]
    fn languages_come_from_file_names() {
        assert_eq!(language_for_path("src/main.rs"), Some("rust"));
        assert_eq!(language_for_path("Cargo.toml"), Some("toml"));
        assert_eq!(language_for_path("README.md"), Some("markdown"));
        assert_eq!(language_for_path("script.PY"), Some("python"));
        // JavaScript is parsed with the tsx grammar, as in Zed.
        assert_eq!(language_for_path("app.jsx"), Some("tsx"));
        // Whole-file-name suffixes beat bare extensions.
        assert_eq!(language_for_path("/p/tsconfig.json"), Some("jsonc"));
        assert_eq!(language_for_path("/p/data.json"), Some("json"));
        assert_eq!(language_for_path("Makefile"), Some("make"));
        assert_eq!(language_for_path("Dockerfile"), Some("dockerfile"));
        assert_eq!(language_for_path("app/AndroidManifest.xml"), Some("xml"));
        assert_eq!(language_for_path("MainActivity.kt"), Some("kotlin"));
    }

    #[test]
    fn open_file_reads_and_detects_language() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("main.rs");
        std::fs::write(&file, "fn main() {}\n").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(engine.text(id).unwrap(), "fn main() {}\n");
        // The language stuck, so highlighting is live without a second call.
        assert!(!engine.highlights(id, 0, 1).unwrap().is_empty());

        assert!(matches!(
            engine.open_file(&dir.path().join("absent.rs")),
            Err(EngineError::Io { .. })
        ));
    }

    /// The question a Kotlin table could never answer: Go's `"` pair carries
    /// `not_in = ["comment", "string"]`, so a quote typed inside either must
    /// not bring a closer with it.
    #[test]
    fn outline_lists_every_item_with_depth() {
        let engine = Engine::new();
        let text = "struct Foo;\n\nimpl Foo {\n    fn bar(&self) {\n        let x = 1;\n    }\n}\n\nfn main() {\n    println!(\"hi\");\n}\n";
        let id = engine.create_buffer(text);
        assert!(engine.set_language(id, "rust").unwrap());

        let items = engine.outline(id).unwrap();
        let flat: Vec<(String, u32, u32)> = items
            .into_iter()
            .map(|item| (item.label, item.depth, item.row))
            .collect();
        assert_eq!(
            flat,
            vec![
                ("struct Foo".to_owned(), 0, 0),
                ("impl Foo".to_owned(), 0, 2),
                ("fn bar".to_owned(), 1, 3),
                ("fn main".to_owned(), 0, 8),
            ]
        );
    }

    #[test]
    fn outline_path_names_the_symbols_containing_the_caret() {
        let engine = Engine::new();
        let text = "struct Foo;\n\nimpl Foo {\n    fn bar(&self) {\n        let x = 1;\n    }\n}\n";
        let id = engine.create_buffer(text);
        assert!(engine.set_language(id, "rust").unwrap());

        // Inside `bar`'s body: impl first, fn second — outermost first.
        let path = engine.outline_path(id, 4, 9).unwrap();
        assert_eq!(path, vec!["impl Foo".to_owned(), "fn bar".to_owned()]);

        // On the struct line.
        let path = engine.outline_path(id, 0, 8).unwrap();
        assert_eq!(path, vec!["struct Foo".to_owned()]);

        // On the blank line between them: no symbol contains the caret.
        assert!(engine.outline_path(id, 1, 0).unwrap().is_empty());

        // A buffer with no language answers with nothing, not an error.
        let plain = engine.create_buffer("just text\n");
        assert!(engine.outline_path(plain, 0, 2).unwrap().is_empty());

        // The boundary byte between two siblings belongs to one symbol, not
        // both — Zed keeps only strictly nesting items (buffer.rs:4475-4482).
        let touching = engine.create_buffer("fn a() {}fn b() {}\n");
        assert!(engine.set_language(touching, "rust").unwrap());
        assert_eq!(
            engine.outline_path(touching, 0, 9).unwrap(),
            vec!["fn a".to_owned()]
        );
    }

    #[test]
    fn bracket_scopes_follow_the_syntax_tree() {
        let engine = Engine::new();
        let text = "func main() {\n\t// a comment\n\ts := \"text\"\n}\n";
        let id = engine.create_buffer(text);
        assert!(engine.set_language(id, "go").unwrap());

        let quote = quote_pair_index(&engine, id);
        let live = |row: u32, column: u32| {
            let offset = engine.point_to_offset(id, row, column).unwrap();
            engine.bracket_scopes(id, &[offset]).unwrap()[0] >> quote & 1 == 1
        };

        // Ordinary code: the pair is live.
        assert!(live(2, 1));
        // Inside the line comment, and at its very end — `overrides.scm`
        // marks comments `.inclusive`, so the end points count as inside too.
        assert!(!live(1, 5));
        assert!(!live(1, 13));
        // Inside the string literal.
        assert!(!live(2, 8));

        // A brace has no `not_in` at all, so it stays live in the comment —
        // which is why the UI never has to ask about one.
        let brace = 0;
        let in_comment = engine.point_to_offset(id, 1, 5).unwrap();
        assert_eq!(
            engine.bracket_scopes(id, &[in_comment]).unwrap()[0] >> brace & 1,
            1
        );
    }

    /// The tree is brought up to date before the question is answered:
    /// otherwise the text typed a keystroke ago would not be in it, and a
    /// caret inside a comment that has only just been opened would read as
    /// ordinary code.
    #[test]
    fn bracket_scopes_see_the_text_just_typed() {
        let engine = Engine::new();
        let id = engine.create_buffer("func main() {\n\ts := 1\n}\n");
        assert!(engine.set_language(id, "go").unwrap());
        let quote = quote_pair_index(&engine, id);

        let at = engine.point_to_offset(id, 1, 1).unwrap();
        assert_eq!(
            engine.bracket_scopes(id, &[at + 3]).unwrap()[0] >> quote & 1,
            1
        );

        // Comment the line out; the same offset is now inside a comment.
        engine.edit(id, at, at, "// ").unwrap();
        assert_eq!(
            engine.bracket_scopes(id, &[at + 3]).unwrap()[0] >> quote & 1,
            0
        );
    }

    /// A buffer with no language keeps every pair: there is nothing to say it
    /// otherwise, and refusing to auto-close in a scratch file would be worse.
    #[test]
    fn bracket_scopes_without_a_language_are_all_live() {
        let engine = Engine::new();
        let id = engine.create_buffer("anything at all");
        assert_eq!(engine.bracket_scopes(id, &[0, 5]).unwrap(), [u64::MAX; 2]);
        assert_eq!(
            engine.bracket_scopes(999, &[0]),
            Err(EngineError::UnknownBuffer(999))
        );
    }

    fn quote_pair_index(engine: &Engine, id: BufferId) -> u32 {
        let name = engine.buffer_language(id).expect("a language");
        let config: serde_json::Value =
            serde_json::from_str(language_config_json(name).expect("a config")).unwrap();
        config["brackets"]
            .as_array()
            .unwrap()
            .iter()
            .position(|pair| pair["start"] == "\"")
            .expect("a quote pair") as u32
    }

    #[test]
    fn normalizes_crlf() {
        let engine = Engine::new();
        let id = engine.create_buffer("a\r\nb");
        assert_eq!(engine.text(id).unwrap(), "a\nb");
        assert_eq!(engine.line_count(id).unwrap(), 2);
    }
}
