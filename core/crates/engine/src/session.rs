//! Workspace persistence: what a project looks like when you come back to it.
//!
//! Zed keeps this in sqlite (`workspace/src/persistence.rs` and its
//! `model.rs`): a `workspace_id` per set of root paths, the centre group
//! serialized as a `SerializedPaneGroup` of axes, flexes and panes, the
//! items of each pane with their kind and active flag, and per-item editor
//! state — `scroll_anchor` and `selections` — written by
//! `editor/src/persistence.rs`. The docks store which panel is open on each
//! side, its width and whether it is zoomed; `crates/recent_projects` reads
//! the same database to list the projects you have opened, newest first.
//!
//! There is no sqlite here — nothing under `core/vendor` carries `sqlez` or
//! `rusqlite`, and a database for one document per project would be a new
//! dependency for no new capability — so the shape above is written as one
//! JSON document per project under `<files_dir>/sessions/`. The *rules* are
//! what matter and they are Zed's:
//!
//! - **the tree is data, not objects** — a pane is named by its position in
//!   the axis tree, exactly as `SerializedPaneGroup` names it;
//! - **restoring is best-effort** — Zed drops items whose file has gone
//!   (`SerializedEditor` resolves a path and gives up quietly) and resolves
//!   stale anchors against the buffer as it is *now*. So does this: a file
//!   that no longer exists is dropped, a caret past the end of a file is
//!   clamped, and a document that will not parse is discarded with a log
//!   line rather than taking the launch down with it.
//!
//! What is *not* here is anything live: a terminal is a process tree and
//! dies with the app, so only the tab's title and working directory are kept
//! and the restore opens a fresh shell there. Same for an agent thread.
//!
//! The module is UI-free on purpose. The app hands it the document it built
//! from its own view state and gets back a validated one; every rule above
//! is tested on the host.

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use serde::{Deserialize, Serialize};

/// The document format. A file written by another version is discarded
/// rather than guessed at — the workspace it describes is a minute to
/// rebuild, and a half-understood layout is worse than none.
pub const SESSION_VERSION: u32 = 1;

/// How many navigation entries survive per pane, per direction. Zed's cap on
/// the live list is `MAX_NAVIGATION_HISTORY_LEN` = 1024
/// (workspace/src/pane.rs:322); what is *written down* is bounded far
/// tighter, because a jump list from three days ago is archaeology and every
/// entry costs a `stat` at startup.
const MAX_SAVED_NAVIGATION: usize = 64;

/// Ceilings on what one document may describe, so a corrupt — or
/// hand-written — file cannot make the restore open ten thousand buffers.
const MAX_ITEMS_PER_PANE: usize = 256;
const MAX_PANES: usize = 32;
const MAX_TERMINALS: usize = 16;

/// The panels that may be recorded as a dock's occupant: the settings keys
/// of [`crate::config::Settings`]. A name outside this list comes from
/// another build, or from a corrupt file, and the dock comes back empty.
const KNOWN_PANELS: &[&str] = &[
    "project_panel",
    "git_panel",
    "project_search",
    "preview",
    "agent_panel",
];

/// One caret: Zed's `selections` column, a list of anchor/head pairs
/// (editor/src/persistence.rs, `SerializedSelection`). Rows are 0-based
/// buffer rows; columns are **UTF-16 code units**, because that is what the
/// editor's `line(row).length` counts and what a Kotlin caret is measured
/// in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct SessionSelection {
    pub anchor_row: u32,
    pub anchor_col: u32,
    pub head_row: u32,
    pub head_col: u32,
}

/// What a persisted tab is. Zed writes the item's *kind* beside its path
/// (the `item_kind` column) so the right view is rebuilt; the two kinds that
/// survive a relaunch here are a text buffer and a media file, and an
/// unknown kind reads as text — which is what opening the path does anyway.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ItemKind {
    #[default]
    Text,
    /// A picture, sound or video — opened by the media pane, never by the
    /// buffer store.
    Media,
}

/// One tab of one pane.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SessionItem {
    /// Project-relative, `/`-separated — the key the tab strip uses.
    pub path: String,
    pub kind: ItemKind,
    /// A pinned tab, which lives at the head of the strip.
    pub pinned: bool,
    /// A *preview* tab — Zed's `preview_tabs`, at most one per pane. Zed
    /// serializes `preview` on its items too (`SerializedItem::preview`), so a
    /// project browsed but not committed to comes back the way it was left
    /// rather than with thirty permanent tabs.
    pub preview: bool,
    /// The vertical scroll in pixels, as the editor reports it. Zed keeps a
    /// `scroll_anchor` (an anchor plus a row offset) because a collaborator
    /// can edit its buffers between sessions; ours cannot, and the jump list
    /// already records a departure this way (`NavEntry.scroll`).
    pub scroll: f32,
    /// Every caret, in document order — `EditorState.caretsInOrder()`.
    pub selections: Vec<SessionSelection>,
}

impl Default for SessionItem {
    fn default() -> Self {
        Self {
            path: String::new(),
            kind: ItemKind::Text,
            pinned: false,
            preview: false,
            scroll: 0.0,
            selections: Vec::new(),
        }
    }
}

/// One entry of a pane's jump list — `NavEntry` on the app side.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct SessionNavEntry {
    pub path: String,
    pub row: u32,
    pub col: u32,
    pub scroll: f32,
}

/// A pane's GoBack/GoForward stacks, oldest first.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct SessionNavHistory {
    pub back: Vec<SessionNavEntry>,
    pub forward: Vec<SessionNavEntry>,
}

/// Which way an axis lays its members out — gpui's `Axis`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum SessionAxis {
    #[default]
    Horizontal,
    Vertical,
}

/// One node of the pane tree — Zed's `SerializedPaneGroup`
/// (workspace/src/persistence/model.rs): a pane, or an axis of members with
/// their flexes.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum SessionPane {
    Leaf {
        #[serde(default)]
        items: Vec<SessionItem>,
        /// Index into `items`, or -1 for a pane with nothing in it.
        #[serde(default = "minus_one")]
        active_index: i32,
        /// Whether this is the pane the workspace's commands act on. Exactly
        /// one leaf carries it after [`SessionDocument::restored`].
        #[serde(default)]
        active: bool,
        #[serde(default)]
        history: SessionNavHistory,
    },
    Split {
        #[serde(default)]
        axis: SessionAxis,
        #[serde(default)]
        children: Vec<SessionPane>,
        /// One per child, summing to the child count — Zed's invariant
        /// (`flex_values_in_bounds`, pane_group.rs:1615).
        #[serde(default)]
        flexes: Vec<f32>,
    },
}

fn minus_one() -> i32 {
    -1
}

impl Default for SessionPane {
    fn default() -> Self {
        Self::empty_leaf()
    }
}

impl SessionPane {
    fn empty_leaf() -> Self {
        Self::Leaf {
            items: Vec::new(),
            active_index: -1,
            active: true,
            history: SessionNavHistory::default(),
        }
    }
}

/// One dock's occupant: which panel is showing, and how wide the dock is.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct SessionDock {
    /// A settings key from [`KNOWN_PANELS`], or empty for "nothing open".
    pub panel: String,
    /// Dock width in dp. Zero means "whatever the panel asks for".
    pub width: f32,
}

/// The terminal dock, which is a dock of its own here rather than a panel.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct SessionTerminalDock {
    pub open: bool,
    /// Height in dp.
    pub height: f32,
}

/// One terminal tab. The process is gone; this is what it takes to start
/// another one where the last one stood.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct SessionTerminal {
    pub title: String,
    /// Absolute path. A directory that has since gone drops the tab.
    pub cwd: String,
}

/// Everything the docks remember.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct SessionDocks {
    pub left: SessionDock,
    pub right: SessionDock,
    /// Which side was opened most recently — the tie-break when a narrow
    /// screen can only draw one (`DockLayout.lastOpened`).
    pub last_opened_right: bool,
    pub terminal: SessionTerminalDock,
}

/// One project's workspace, as it was when the app last looked at it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SessionDocument {
    pub version: u32,
    /// The project root this describes. Kept so a document that has been
    /// copied — or a file name whose hash collided — is recognised and
    /// refused rather than applied to the wrong project.
    pub root: String,
    pub panes: SessionPane,
    pub docks: SessionDocks,
    pub terminals: Vec<SessionTerminal>,
    /// Whether the active pane filled the work area — Zed's `Pane::zoomed`.
    pub zoomed: bool,
}

impl Default for SessionDocument {
    fn default() -> Self {
        Self {
            version: SESSION_VERSION,
            root: String::new(),
            panes: SessionPane::empty_leaf(),
            docks: SessionDocks::default(),
            terminals: Vec::new(),
            zoomed: false,
        }
    }
}

impl SessionDocument {
    /// The document as it can actually be applied to `root` *now*.
    ///
    /// Every rule in the module doc lives here: items whose file has gone are
    /// dropped, carets are clamped against the file as it is, empty panes
    /// collapse, flexes that do not fit are reset to equal shares, exactly
    /// one pane ends up active, and anything a hand-edited or corrupt file
    /// could overstate — item counts, dock names, terminal directories — is
    /// bounded or refused.
    pub fn restored(mut self, root: &Path) -> Self {
        self.root = root.to_string_lossy().into_owned();
        self.panes = restore_pane(self.panes, root);
        self.panes = prune(self.panes).unwrap_or_else(SessionPane::empty_leaf);
        let mut found_active = false;
        ensure_one_active(&mut self.panes, &mut found_active);
        if !found_active {
            // Every pane said "not me", which a hand edit can write. The
            // first in tree order takes it, as `PaneGroupState.active` falls
            // back to `panes.first()`.
            ensure_first_active(&mut self.panes, &mut false);
        }
        // Nothing to zoom into when the workspace came back as one pane.
        if leaf_count(&self.panes) < 2 {
            self.zoomed = false;
        }
        self.docks.left = restore_dock(std::mem::take(&mut self.docks.left));
        self.docks.right = restore_dock(std::mem::take(&mut self.docks.right));
        if !self.docks.terminal.height.is_finite() || self.docks.terminal.height < 0.0 {
            self.docks.terminal.height = 0.0;
        }
        self.terminals.truncate(MAX_TERMINALS);
        self.terminals
            .retain(|terminal| Path::new(&terminal.cwd).is_dir());
        self.version = SESSION_VERSION;
        self
    }
}

/// A leaf's items and history, made honest about the disk.
fn restore_pane(pane: SessionPane, root: &Path) -> SessionPane {
    match pane {
        SessionPane::Leaf {
            mut items,
            active_index,
            active,
            mut history,
        } => {
            items.truncate(MAX_ITEMS_PER_PANE);
            // The active item is named by index, so which items survive has
            // to be settled before the index can be moved.
            let active_path = usize::try_from(active_index)
                .ok()
                .and_then(|index| items.get(index))
                .map(|item| item.path.clone());
            items.retain(|item| is_file(root, &item.path));
            // One tab per path per pane: `OpenFilesState.open` selects the
            // existing tab rather than adding a twin (pane.rs:1500-1530), so
            // a document with a duplicate would silently lose a tab — and
            // the active index would then name the wrong file.
            let mut seen = std::collections::HashSet::new();
            items.retain(|item| seen.insert(item.path.clone()));
            for item in &mut items {
                clamp_item(root, item);
            }
            let active_index = active_path
                .and_then(|path| items.iter().position(|item| item.path == path))
                .map(|index| index as i32)
                .unwrap_or(if items.is_empty() { -1 } else { 0 });
            history.back.retain(|entry| is_file(root, &entry.path));
            history.forward.retain(|entry| is_file(root, &entry.path));
            // Oldest first, so the cap drops the oldest.
            trim_front(&mut history.back, MAX_SAVED_NAVIGATION);
            trim_front(&mut history.forward, MAX_SAVED_NAVIGATION);
            SessionPane::Leaf {
                items,
                active_index,
                active,
                history,
            }
        }
        SessionPane::Split {
            axis,
            children,
            flexes,
        } => {
            let mut children: Vec<SessionPane> = children
                .into_iter()
                .take(MAX_PANES)
                .map(|child| restore_pane(child, root))
                .collect();
            children.truncate(MAX_PANES);
            let flexes = valid_flexes(flexes, children.len());
            SessionPane::Split {
                axis,
                children,
                flexes,
            }
        }
    }
}

/// Zed's `PaneAxis::load` (pane_group.rs:667-683): flexes that do not fit
/// the members, or do not sum to their count, are reset to equal shares.
fn valid_flexes(flexes: Vec<f32>, members: usize) -> Vec<f32> {
    let sum: f32 = flexes.iter().sum();
    let fits = flexes.len() == members
        && flexes.iter().all(|flex| flex.is_finite() && *flex > 0.0)
        && (sum - members as f32).abs() < 0.001;
    if fits { flexes } else { vec![1.0; members] }
}

/// Empty panes go, and an axis left with one member collapses into it —
/// Zed's `PaneAxis::remove` (pane_group.rs:737-777). `None` means the whole
/// subtree was empty.
fn prune(pane: SessionPane) -> Option<SessionPane> {
    match pane {
        SessionPane::Leaf { ref items, .. } => {
            if items.is_empty() {
                None
            } else {
                Some(pane)
            }
        }
        SessionPane::Split {
            axis,
            children,
            flexes,
        } => {
            let kept: Vec<(usize, SessionPane)> = children
                .into_iter()
                .enumerate()
                .filter_map(|(index, child)| prune(child).map(|child| (index, child)))
                .collect();
            match kept.len() {
                0 => None,
                1 => kept.into_iter().next().map(|(_, child)| child),
                members => {
                    // A flex belongs to the slot it was written for, so the
                    // survivors keep theirs and are renormalised to sum to
                    // their own count — the invariant `valid_flexes` checks.
                    let mut taken: Vec<f32> = kept
                        .iter()
                        .map(|(index, _)| flexes.get(*index).copied().unwrap_or(1.0))
                        .collect();
                    let sum: f32 = taken.iter().sum();
                    if sum > 0.0 && sum.is_finite() {
                        let scale = members as f32 / sum;
                        for flex in &mut taken {
                            *flex *= scale;
                        }
                    }
                    Some(SessionPane::Split {
                        axis,
                        children: kept.into_iter().map(|(_, child)| child).collect(),
                        flexes: valid_flexes(taken, members),
                    })
                }
            }
        }
    }
}

/// Exactly one leaf is the active one. A document with two — which a hand
/// edit can write — must not leave the workspace guessing.
fn ensure_one_active(pane: &mut SessionPane, found: &mut bool) {
    match pane {
        SessionPane::Leaf { active, .. } => {
            if *active && !*found {
                *found = true;
            } else {
                *active = false;
            }
        }
        SessionPane::Split { children, .. } => {
            for child in children.iter_mut() {
                ensure_one_active(child, found);
            }
        }
    }
}

/// The first leaf in tree order becomes the active one.
fn ensure_first_active(pane: &mut SessionPane, done: &mut bool) {
    match pane {
        SessionPane::Leaf { active, .. } => {
            if !*done {
                *active = true;
                *done = true;
            }
        }
        SessionPane::Split { children, .. } => {
            for child in children.iter_mut() {
                ensure_first_active(child, done);
            }
        }
    }
}

fn leaf_count(pane: &SessionPane) -> usize {
    match pane {
        SessionPane::Leaf { .. } => 1,
        SessionPane::Split { children, .. } => children.iter().map(leaf_count).sum(),
    }
}

fn restore_dock(mut dock: SessionDock) -> SessionDock {
    if !KNOWN_PANELS.contains(&dock.panel.as_str()) {
        dock.panel.clear();
    }
    if !dock.width.is_finite() || dock.width < 0.0 {
        dock.width = 0.0;
    }
    dock
}

fn trim_front<T>(entries: &mut Vec<T>, cap: usize) {
    if entries.len() > cap {
        entries.drain(0..entries.len() - cap);
    }
}

/// Whether `path` still names a file inside `root`. A path that climbs out
/// of the project with `..`, or an absolute one, is refused: the document
/// sits in writable storage, and a tab is a file the app will then open.
fn is_file(root: &Path, path: &str) -> bool {
    resolve(root, path)
        .map(|full| full.is_file())
        .unwrap_or(false)
}

fn resolve(root: &Path, path: &str) -> Option<PathBuf> {
    if path.is_empty() || path.starts_with('/') {
        return None;
    }
    let mut full = root.to_path_buf();
    for part in path.split('/') {
        if part.is_empty() || part == "." || part == ".." {
            return None;
        }
        full.push(part);
    }
    Some(full)
}

/// Put every caret back inside the file as it is now. Zed resolves its
/// anchors against the buffer it has just loaded and takes what it gets;
/// with plain row/column pairs the same idea is a clamp.
fn clamp_item(root: &Path, item: &mut SessionItem) {
    if !item.scroll.is_finite() || item.scroll < 0.0 {
        item.scroll = 0.0;
    }
    // A picture has no caret to restore.
    if item.kind == ItemKind::Media {
        item.selections.clear();
        return;
    }
    item.selections.truncate(MAX_ITEMS_PER_PANE);
    if item.selections.is_empty() {
        return;
    }
    let Some(path) = resolve(root, &item.path) else {
        item.selections.clear();
        return;
    };
    for selection in &mut item.selections {
        let (anchor_row, anchor_col) =
            clamp_position(&path, selection.anchor_row, selection.anchor_col);
        let (head_row, head_col) = clamp_position(&path, selection.head_row, selection.head_col);
        selection.anchor_row = anchor_row;
        selection.anchor_col = anchor_col;
        selection.head_row = head_row;
        selection.head_col = head_col;
    }
}

/// `(row, column)` clamped into `path`. An unreadable file answers `(0, 0)`;
/// a file that is *gone* drops the item entirely, before this is reached.
fn clamp_position(path: &Path, row: u32, col: u32) -> (u32, u32) {
    match line_shape(path, row) {
        Some((row, columns)) => (row, col.min(columns)),
        None => (0, 0),
    }
}

/// The row `path` can actually offer for `row`, and how many columns that
/// row has.
///
/// Streams the file and stops at `row`, so clamping a caret near the top of
/// a 200 MB log costs the first few lines. Lines are counted the way the
/// editor counts them — `split('\n')`, so a file ending in a newline has one
/// more, empty, line — and columns in UTF-16 code units, which is what a
/// Kotlin caret is measured in.
fn line_shape(path: &Path, row: u32) -> Option<(u32, u32)> {
    let file = std::fs::File::open(path).ok()?;
    let mut reader = BufReader::new(file);
    let mut buffer = Vec::new();
    let mut index: u32 = 0;
    let mut last_columns: u32 = 0;
    let mut ended_with_newline = false;
    loop {
        buffer.clear();
        let read = reader.read_until(b'\n', &mut buffer).ok()?;
        if read == 0 {
            break;
        }
        ended_with_newline = buffer.last() == Some(&b'\n');
        if ended_with_newline {
            buffer.pop();
        }
        if buffer.last() == Some(&b'\r') {
            buffer.pop();
        }
        let columns = String::from_utf8_lossy(&buffer).encode_utf16().count() as u32;
        if index == row {
            return Some((row, columns));
        }
        last_columns = columns;
        index = index.saturating_add(1);
    }
    if ended_with_newline {
        // The empty line after the final newline is a line the editor can
        // put a caret on.
        if index == row {
            return Some((row, 0));
        }
        index = index.saturating_add(1);
        last_columns = 0;
    }
    Some((index.saturating_sub(1), last_columns))
}

// ---------------------------------------------------------------------------
// Recent projects — Zed's `crates/recent_projects`, which reads the same
// database the workspaces live in and lists them newest first.
// ---------------------------------------------------------------------------

/// One row of the Open Recent picker.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(default)]
pub struct RecentProject {
    /// Absolute path to the project root.
    pub path: String,
    /// Final path component — what the picker shows first.
    pub name: String,
    /// Unix milliseconds. Ordering only; nothing displays it.
    pub last_opened: u64,
}

/// How many projects the list remembers. A phone has tens of projects at
/// most; the cap guards against a file that grows without bound.
const MAX_RECENT: usize = 64;

fn sessions_dir_slot() -> &'static Mutex<Option<PathBuf>> {
    static DIR: OnceLock<Mutex<Option<PathBuf>>> = OnceLock::new();
    DIR.get_or_init(|| Mutex::new(None))
}

/// Point session storage at a directory — the same one settings live in.
/// Called from [`crate::initialize`].
pub(crate) fn set_directory(directory: PathBuf) {
    *sessions_dir_slot().lock().unwrap() = Some(directory.join("sessions"));
}

fn sessions_dir() -> Option<PathBuf> {
    sessions_dir_slot().lock().unwrap().clone()
}

/// The file a project's session lives in.
///
/// Named by a hash of the root path rather than by the path itself, because
/// a project name is user input and a file name is not: a project called
/// `../settings` must not be able to choose the file it is written to. The
/// readable half is kept as a prefix so the directory means something to a
/// human. Zed's `workspace_id` is the same idea with a database's autonumber
/// in place of the hash.
fn session_file(root: &Path) -> Option<PathBuf> {
    let directory = sessions_dir()?;
    let key = root.to_string_lossy();
    let name: String = root
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default()
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
        .take(32)
        .collect();
    Some(directory.join(format!("{name}-{:016x}.json", fnv1a(key.as_bytes()))))
}

/// FNV-1a, 64-bit. A hash, not a digest: it names a file, and the only
/// property needed is that two roots almost never collide — and the document
/// records its own root, so even a collision is caught rather than applied.
fn fnv1a(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in bytes {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

fn recent_file() -> Option<PathBuf> {
    Some(sessions_dir()?.join("recent.json"))
}

/// Write `text` to `path` by way of a temporary file in the same directory,
/// so a crash mid-write leaves the previous document rather than half of a
/// new one.
fn write_atomically(path: &Path, text: &str) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("tmp");
    std::fs::write(&temporary, text)?;
    std::fs::rename(&temporary, path)
}

fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_millis() as u64)
        .unwrap_or(0)
}

impl crate::Engine {
    /// Write `document` — the app's JSON — as `root`'s session.
    ///
    /// The JSON is parsed before it is written, so a document the engine
    /// could not read back never reaches the disk, and the write is atomic,
    /// so the file that is there is always whole. Returns false when the
    /// engine has no directory yet, when the JSON is not a session document,
    /// or when the write failed.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn save_session(&self, root: &str, document: &str) -> bool {
        let Some(path) = session_file(Path::new(root)) else {
            return false;
        };
        let mut parsed: SessionDocument = match serde_json::from_str(document) {
            Ok(parsed) => parsed,
            Err(err) => {
                log::warn!("session: refusing to write a document that will not parse: {err}");
                return false;
            }
        };
        parsed.version = SESSION_VERSION;
        parsed.root = root.to_owned();
        let text = match serde_json::to_string(&parsed) {
            Ok(text) => text,
            Err(err) => {
                log::warn!("session: could not serialize: {err}");
                return false;
            }
        };
        match write_atomically(&path, &text) {
            Ok(()) => true,
            Err(err) => {
                log::warn!("session: could not write {}: {err}", path.display());
                false
            }
        }
    }

    /// `root`'s saved session, made honest about the disk — see
    /// [`SessionDocument::restored`]. `None` when there is none, when the
    /// engine has no directory, or when the file is corrupt or was written
    /// by another version, in which case it is deleted so the next launch
    /// starts clean rather than failing the same way for ever.
    ///
    /// **Blocking**: reads the session file and the head of every file it
    /// names. Call it off the Android main thread.
    pub fn load_session(&self, root: &str) -> Option<String> {
        let path = session_file(Path::new(root))?;
        let text = std::fs::read_to_string(&path).ok()?;
        let document: SessionDocument = match serde_json::from_str(&text) {
            Ok(document) => document,
            Err(err) => {
                log::warn!(
                    "session: {} is not a session document ({err}); discarded",
                    path.display()
                );
                let _ = std::fs::remove_file(&path);
                return None;
            }
        };
        if document.version != SESSION_VERSION {
            log::info!(
                "session: {} was written by format {} rather than {SESSION_VERSION}; discarded",
                path.display(),
                document.version
            );
            let _ = std::fs::remove_file(&path);
            return None;
        }
        // A hash collision, or a document copied between projects: its
        // layout would name another project's files.
        if !document.root.is_empty() && document.root != root {
            log::warn!(
                "session: {} describes {} rather than {root}; ignored",
                path.display(),
                document.root
            );
            return None;
        }
        let restored = document.restored(Path::new(root));
        serde_json::to_string(&restored).ok()
    }

    /// Forget a project's session — what deleting the project does.
    pub fn clear_session(&self, root: &str) {
        if let Some(path) = session_file(Path::new(root)) {
            let _ = std::fs::remove_file(path);
        }
    }

    /// Note that `root` has just been opened, and return the recent list as
    /// it now stands, newest first.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn note_project_opened(&self, root: &str) -> Vec<RecentProject> {
        let mut recent = self.read_recent();
        recent.retain(|project| project.path != root);
        let path = Path::new(root);
        recent.insert(
            0,
            RecentProject {
                path: root.to_owned(),
                name: path
                    .file_name()
                    .map(|name| name.to_string_lossy().into_owned())
                    .unwrap_or_else(|| root.to_owned()),
                last_opened: now_millis(),
            },
        );
        recent.truncate(MAX_RECENT);
        self.write_recent(&recent);
        recent
    }

    /// Every project opened before, newest first, minus the ones no longer
    /// on disk — Zed's recent projects picker, whose rows are the workspaces
    /// its database knows.
    ///
    /// **Blocking**: stats each project directory. Call it off the main
    /// thread.
    pub fn recent_projects(&self) -> Vec<RecentProject> {
        let mut recent = self.read_recent();
        let before = recent.len();
        recent.retain(|project| Path::new(&project.path).is_dir());
        if recent.len() != before {
            self.write_recent(&recent);
        }
        recent
    }

    /// Zed's "Remove from Recent Projects" row action
    /// (recent_projects/src/recent_projects.rs): the project stays on disk,
    /// it just stops being offered. Its session goes with it, since nothing
    /// will ask for it again.
    pub fn remove_recent_project(&self, root: &str) -> Vec<RecentProject> {
        let mut recent = self.read_recent();
        recent.retain(|project| project.path != root);
        self.write_recent(&recent);
        self.clear_session(root);
        recent
    }

    fn read_recent(&self) -> Vec<RecentProject> {
        let Some(path) = recent_file() else {
            return Vec::new();
        };
        let Ok(text) = std::fs::read_to_string(&path) else {
            return Vec::new();
        };
        match serde_json::from_str::<Vec<RecentProject>>(&text) {
            Ok(mut recent) => {
                recent.sort_by(|a, b| b.last_opened.cmp(&a.last_opened));
                recent.truncate(MAX_RECENT);
                recent
            }
            Err(err) => {
                log::warn!("session: recent.json is not a project list ({err}); discarded");
                let _ = std::fs::remove_file(&path);
                Vec::new()
            }
        }
    }

    fn write_recent(&self, recent: &[RecentProject]) {
        let Some(path) = recent_file() else {
            return;
        };
        let Ok(text) = serde_json::to_string(recent) else {
            return;
        };
        if let Err(err) = write_atomically(&path, &text) {
            log::warn!("session: could not write {}: {err}", path.display());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    /// Session storage is a process-wide slot, like the settings path; the
    /// tests that point it somewhere take this lock so they cannot race.
    fn session_lock() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: Mutex<()> = Mutex::new(());
        LOCK.lock().unwrap_or_else(|err| err.into_inner())
    }

    fn write(root: &Path, path: &str, text: &str) {
        let full = root.join(path);
        if let Some(parent) = full.parent() {
            std::fs::create_dir_all(parent).unwrap();
        }
        std::fs::write(full, text).unwrap();
    }

    fn leaf(paths: &[&str], active_index: i32) -> SessionPane {
        SessionPane::Leaf {
            items: paths
                .iter()
                .map(|path| SessionItem {
                    path: (*path).to_owned(),
                    ..SessionItem::default()
                })
                .collect(),
            active_index,
            active: true,
            history: SessionNavHistory::default(),
        }
    }

    fn items_of(pane: &SessionPane) -> Vec<String> {
        match pane {
            SessionPane::Leaf { items, .. } => items.iter().map(|item| item.path.clone()).collect(),
            SessionPane::Split { .. } => panic!("expected a leaf"),
        }
    }

    #[test]
    fn a_session_document_survives_a_round_trip() {
        let directory = tempfile::tempdir().unwrap();
        let _guard = session_lock();
        set_directory(directory.path().to_path_buf());
        let root = directory.path().join("project");
        std::fs::create_dir_all(&root).unwrap();
        write(&root, "src/main.rs", "fn main() {}\nlet x = 1;\n");
        write(&root, "README.md", "# hi\n");
        std::fs::create_dir_all(root.join("scripts")).unwrap();

        let document = SessionDocument {
            version: SESSION_VERSION,
            root: root.to_string_lossy().into_owned(),
            panes: SessionPane::Split {
                axis: SessionAxis::Horizontal,
                children: vec![
                    SessionPane::Leaf {
                        items: vec![SessionItem {
                            path: "src/main.rs".to_owned(),
                            kind: ItemKind::Text,
                            pinned: true,
                            preview: false,
                            scroll: 12.5,
                            selections: vec![SessionSelection {
                                anchor_row: 1,
                                anchor_col: 0,
                                head_row: 1,
                                head_col: 3,
                            }],
                        }],
                        active_index: 0,
                        active: false,
                        history: SessionNavHistory {
                            back: vec![SessionNavEntry {
                                path: "README.md".to_owned(),
                                row: 0,
                                col: 1,
                                scroll: 0.0,
                            }],
                            forward: Vec::new(),
                        },
                    },
                    leaf(&["README.md"], 0),
                ],
                flexes: vec![1.5, 0.5],
            },
            docks: SessionDocks {
                left: SessionDock {
                    panel: "project_panel".to_owned(),
                    width: 240.0,
                },
                right: SessionDock {
                    panel: "git_panel".to_owned(),
                    width: 360.0,
                },
                last_opened_right: true,
                terminal: SessionTerminalDock {
                    open: true,
                    height: 260.0,
                },
            },
            terminals: vec![SessionTerminal {
                title: "shell 1".to_owned(),
                cwd: root.join("scripts").to_string_lossy().into_owned(),
            }],
            zoomed: false,
        };

        let engine = Engine::new();
        let json = serde_json::to_string(&document).unwrap();
        assert!(engine.save_session(&document.root, &json));
        let loaded = engine.load_session(&document.root).unwrap();
        let back: SessionDocument = serde_json::from_str(&loaded).unwrap();
        assert_eq!(back, document.clone().restored(&root));
        // Nothing was lost on the way: the tree, the flexes, the pinned
        // flag, the caret, the docks and the terminal all came back.
        assert_eq!(back, document);
    }

    /// A tab's provisional state is Zed's `preview_tabs`, and it has to
    /// survive the write: a pane restored without it turns a project that was
    /// browsed into a strip of permanent tabs. A document written before the
    /// key existed reads as "permanent", which is `#[serde(default)]`'s job
    /// and is asserted here so a future field cannot quietly break it.
    #[test]
    fn a_preview_tab_survives_the_write_and_an_older_document_has_none() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "src/main.rs", "fn main() {}\n");

        let mut document = SessionDocument {
            panes: leaf(&["src/main.rs"], 0),
            ..SessionDocument::default()
        };
        match &mut document.panes {
            SessionPane::Leaf { items, .. } => items[0].preview = true,
            SessionPane::Split { .. } => panic!("expected a leaf"),
        }
        let json = serde_json::to_string(&document).unwrap();
        let read: SessionDocument = serde_json::from_str(&json).unwrap();
        match &read.restored(root).panes {
            SessionPane::Leaf { items, .. } => assert!(items[0].preview),
            SessionPane::Split { .. } => panic!("expected a leaf"),
        }

        // The same document as an older writer would have left it.
        let older = json.replace(",\"preview\":true", "");
        assert!(!older.contains("preview"));
        let read: SessionDocument = serde_json::from_str(&older).unwrap();
        match &read.panes {
            SessionPane::Leaf { items, .. } => assert!(!items[0].preview),
            SessionPane::Split { .. } => panic!("expected a leaf"),
        }
    }

    #[test]
    fn a_file_that_is_gone_is_dropped() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "src/main.rs", "fn main() {}\n");

        let document = SessionDocument {
            panes: leaf(&["src/main.rs", "src/gone.rs", "../escape.rs", "/etc/passwd"], 1),
            ..SessionDocument::default()
        }
        .restored(root);

        assert_eq!(items_of(&document.panes), vec!["src/main.rs".to_owned()]);
        // The active item was the one that vanished; the survivor takes it.
        match &document.panes {
            SessionPane::Leaf { active_index, .. } => assert_eq!(*active_index, 0),
            SessionPane::Split { .. } => panic!("expected a leaf"),
        }
    }

    #[test]
    fn an_empty_pane_collapses_and_its_axis_with_it() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "kept.rs", "fn main() {}\n");

        let document = SessionDocument {
            panes: SessionPane::Split {
                axis: SessionAxis::Vertical,
                children: vec![leaf(&["gone.rs"], 0), leaf(&["kept.rs"], 0)],
                flexes: vec![1.0, 1.0],
            },
            ..SessionDocument::default()
        }
        .restored(root);

        // One survivor, so the axis is gone and the leaf is the root — and
        // it is the active pane, because something has to be.
        match &document.panes {
            SessionPane::Leaf { items, active, .. } => {
                assert_eq!(items.len(), 1);
                assert!(active);
            }
            SessionPane::Split { .. } => panic!("the axis should have collapsed"),
        }
    }

    #[test]
    fn a_caret_past_the_end_of_a_file_is_clamped() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        // Three lines by the editor's count: "one", "two", and the empty
        // line after the final newline.
        write(root, "short.txt", "one\ntwo\n");

        let document = SessionDocument {
            panes: SessionPane::Leaf {
                items: vec![SessionItem {
                    path: "short.txt".to_owned(),
                    selections: vec![
                        SessionSelection {
                            anchor_row: 900,
                            anchor_col: 900,
                            head_row: 900,
                            head_col: 900,
                        },
                        SessionSelection {
                            anchor_row: 0,
                            anchor_col: 0,
                            head_row: 1,
                            head_col: 99,
                        },
                    ],
                    ..SessionItem::default()
                }],
                active_index: 0,
                active: true,
                history: SessionNavHistory::default(),
            },
            ..SessionDocument::default()
        }
        .restored(root);

        let selections = match &document.panes {
            SessionPane::Leaf { items, .. } => items[0].selections.clone(),
            SessionPane::Split { .. } => panic!("expected a leaf"),
        };
        // Past the end lands on the last line, which is the empty one.
        assert_eq!(
            selections[0],
            SessionSelection {
                anchor_row: 2,
                anchor_col: 0,
                head_row: 2,
                head_col: 0,
            }
        );
        // A column past the end of a line that exists is clamped to it.
        assert_eq!(
            selections[1],
            SessionSelection {
                anchor_row: 0,
                anchor_col: 0,
                head_row: 1,
                head_col: 3,
            }
        );
    }

    #[test]
    fn a_corrupt_session_file_is_discarded_rather_than_applied() {
        let directory = tempfile::tempdir().unwrap();
        let _guard = session_lock();
        set_directory(directory.path().to_path_buf());
        let root = directory.path().join("project");
        std::fs::create_dir_all(&root).unwrap();
        let root_text = root.to_string_lossy().into_owned();

        let engine = Engine::new();
        assert!(engine.save_session(&root_text, r#"{"version":1,"panes":{"kind":"leaf"}}"#));
        let file = session_file(&root).unwrap();
        std::fs::write(&file, "{ this is not json").unwrap();

        assert!(engine.load_session(&root_text).is_none());
        // And it is gone, so the next launch does not fail the same way.
        assert!(!file.exists());

        // A document from another format version goes the same way.
        std::fs::write(&file, r#"{"version":99,"panes":{"kind":"leaf"}}"#).unwrap();
        assert!(engine.load_session(&root_text).is_none());
        assert!(!file.exists());

        // And a document written for another project is refused, but left
        // alone: it is that project's, not ours to delete.
        let other = serde_json::to_string(&SessionDocument {
            root: "/somewhere/else".to_owned(),
            ..SessionDocument::default()
        })
        .unwrap();
        std::fs::write(&file, other).unwrap();
        assert!(engine.load_session(&root_text).is_none());
        assert!(file.exists());
    }

    #[test]
    fn flexes_that_do_not_fit_their_members_are_reset() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "a.rs", "a\n");
        write(root, "b.rs", "b\n");

        let document = SessionDocument {
            panes: SessionPane::Split {
                axis: SessionAxis::Horizontal,
                children: vec![leaf(&["a.rs"], 0), leaf(&["b.rs"], 0)],
                // Three flexes for two members, summing to nothing sensible.
                flexes: vec![9.0, 9.0, 9.0],
            },
            ..SessionDocument::default()
        }
        .restored(root);

        match &document.panes {
            SessionPane::Split { flexes, .. } => assert_eq!(flexes, &vec![1.0, 1.0]),
            SessionPane::Leaf { .. } => panic!("expected a split"),
        }
    }

    #[test]
    fn exactly_one_pane_comes_back_active() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "a.rs", "a\n");
        write(root, "b.rs", "b\n");

        // Two panes both claiming to be active — the second loses.
        let document = SessionDocument {
            panes: SessionPane::Split {
                axis: SessionAxis::Horizontal,
                children: vec![leaf(&["a.rs"], 0), leaf(&["b.rs"], 0)],
                flexes: vec![1.0, 1.0],
            },
            ..SessionDocument::default()
        }
        .restored(root);
        let actives = match &document.panes {
            SessionPane::Split { children, .. } => children
                .iter()
                .filter(|child| matches!(child, SessionPane::Leaf { active: true, .. }))
                .count(),
            SessionPane::Leaf { .. } => panic!("expected a split"),
        };
        assert_eq!(actives, 1);

        // And with none claiming it, the first in tree order takes it.
        let mut none_active = SessionPane::Split {
            axis: SessionAxis::Horizontal,
            children: vec![leaf(&["a.rs"], 0), leaf(&["b.rs"], 0)],
            flexes: vec![1.0, 1.0],
        };
        if let SessionPane::Split { children, .. } = &mut none_active {
            for child in children {
                if let SessionPane::Leaf { active, .. } = child {
                    *active = false;
                }
            }
        }
        let document = SessionDocument {
            panes: none_active,
            ..SessionDocument::default()
        }
        .restored(root);
        match &document.panes {
            SessionPane::Split { children, .. } => {
                assert!(matches!(children[0], SessionPane::Leaf { active: true, .. }));
                assert!(matches!(
                    children[1],
                    SessionPane::Leaf { active: false, .. }
                ));
            }
            SessionPane::Leaf { .. } => panic!("expected a split"),
        }
    }

    #[test]
    fn a_dead_terminal_directory_and_an_unknown_panel_are_dropped() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "a.rs", "a\n");

        let document = SessionDocument {
            panes: leaf(&["a.rs"], 0),
            docks: SessionDocks {
                left: SessionDock {
                    panel: "not_a_panel".to_owned(),
                    width: -4.0,
                },
                right: SessionDock {
                    panel: "agent_panel".to_owned(),
                    width: 400.0,
                },
                last_opened_right: true,
                terminal: SessionTerminalDock {
                    open: true,
                    height: 260.0,
                },
            },
            terminals: vec![
                SessionTerminal {
                    title: "here".to_owned(),
                    cwd: root.to_string_lossy().into_owned(),
                },
                SessionTerminal {
                    title: "gone".to_owned(),
                    cwd: root.join("vanished").to_string_lossy().into_owned(),
                },
            ],
            ..SessionDocument::default()
        }
        .restored(root);

        assert_eq!(document.docks.left.panel, "");
        assert_eq!(document.docks.left.width, 0.0);
        assert_eq!(document.docks.right.panel, "agent_panel");
        assert_eq!(document.terminals.len(), 1);
        assert_eq!(document.terminals[0].title, "here");
    }

    #[test]
    fn the_navigation_history_is_bounded_and_swept() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "kept.rs", "a\n");

        let mut back: Vec<SessionNavEntry> = (0..MAX_SAVED_NAVIGATION + 20)
            .map(|row| SessionNavEntry {
                path: "kept.rs".to_owned(),
                row: row as u32,
                col: 0,
                scroll: 0.0,
            })
            .collect();
        back.insert(
            0,
            SessionNavEntry {
                path: "gone.rs".to_owned(),
                ..SessionNavEntry::default()
            },
        );

        let document = SessionDocument {
            panes: SessionPane::Leaf {
                items: vec![SessionItem {
                    path: "kept.rs".to_owned(),
                    ..SessionItem::default()
                }],
                active_index: 0,
                active: true,
                history: SessionNavHistory {
                    back,
                    forward: Vec::new(),
                },
            },
            ..SessionDocument::default()
        }
        .restored(root);

        match &document.panes {
            SessionPane::Leaf { history, .. } => {
                assert_eq!(history.back.len(), MAX_SAVED_NAVIGATION);
                // The oldest go, so the newest entry is still the last one.
                assert_eq!(
                    history.back.last().unwrap().row,
                    (MAX_SAVED_NAVIGATION + 19) as u32
                );
                assert!(history.back.iter().all(|entry| entry.path == "kept.rs"));
            }
            SessionPane::Split { .. } => panic!("expected a leaf"),
        }
    }

    #[test]
    fn recent_projects_are_newest_first_and_removable() {
        let directory = tempfile::tempdir().unwrap();
        let _guard = session_lock();
        set_directory(directory.path().to_path_buf());
        let engine = Engine::new();

        let one = directory.path().join("one");
        let two = directory.path().join("two");
        std::fs::create_dir_all(&one).unwrap();
        std::fs::create_dir_all(&two).unwrap();
        let one = one.to_string_lossy().into_owned();
        let two = two.to_string_lossy().into_owned();

        engine.note_project_opened(&one);
        engine.note_project_opened(&two);
        let recent = engine.recent_projects();
        assert_eq!(
            recent.iter().map(|p| p.path.clone()).collect::<Vec<_>>(),
            vec![two.clone(), one.clone()]
        );
        assert_eq!(recent[0].name, "two");

        // Opening the older one again brings it to the front rather than
        // listing it twice.
        engine.note_project_opened(&one);
        let recent = engine.recent_projects();
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0].path, one);

        // Remove-from-list takes it off, and a project deleted from disk
        // stops being offered without anyone asking.
        let recent = engine.remove_recent_project(&one);
        assert_eq!(recent.len(), 1);
        std::fs::remove_dir_all(&two).unwrap();
        assert!(engine.recent_projects().is_empty());
    }
}
