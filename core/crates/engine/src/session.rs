//! Workspace persistence: what a project looks like when you come back to it.
//!
//! Zed keeps this in sqlite (`workspace/src/persistence.rs`): a workspace per
//! set of root paths, its pane tree, the items of each pane and, per item,
//! the editor's `scroll_anchor` and `selections`. This fork carries a much
//! smaller document, because the shell it restores is a 400 dp column with
//! one editor, three destinations and no docks (docs/UI.md, P9): the project
//! root, the open files in most-recently-used order with a caret and a scroll
//! each, which of the three destinations was showing, and whether Build was
//! in its Shell mode. There is no pane tree, no dock, no jump list and no tab
//! state left to write down.
//!
//! There is no sqlite here — nothing under `core/vendor` carries `sqlez` or
//! `rusqlite` — so the document is one JSON file per project under
//! `<files_dir>/sessions/`. The *rules* are Zed's:
//!
//! - **restoring is best-effort** — a file that no longer exists is dropped,
//!   a caret past the end of a file is clamped against the file as it is
//!   *now*, and a document that will not parse is discarded with a log line
//!   rather than taking the launch down with it;
//! - **nothing live is kept** — a shell is a process tree and dies with the
//!   app; the mode that showed it is remembered and the restore opens a fresh
//!   one. Same for an agent thread, which the agent itself keeps.
//!
//! Restoring where you left off is the ten-minute-bus-session feature: Android
//! kills a backgrounded process holding a 1.4 GB toolchain aggressively, and
//! losing your place every time would be the worst bug in the product. That
//! is why the validation here is kept rather than the document being trusted.
//!
//! The module is UI-free on purpose. The app hands it the document it built
//! from its own view state and gets back a validated one; every rule above
//! is tested on the host.

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use serde::{Deserialize, Serialize};

/// The document format. A file written by another version is discarded
/// rather than guessed at — the place it describes is a minute to find
/// again, and a half-understood one is worse than none. Version 1 was the
/// pane tree of the inherited desktop shell; 2 is the phone's document.
/// `WorkspaceSession.VERSION` on the app side must match.
pub const SESSION_VERSION: u32 = 2;

/// Ceiling on the files one document may name, so a corrupt — or
/// hand-written — file cannot make the restore open ten thousand buffers.
const MAX_FILES: usize = 64;

/// The three destinations of the shell (`ui/shell/RouteStack.kt`,
/// `Destination`), spelled as the document writes them. Anything else comes
/// from another build or a corrupt file and reads as Code, the start
/// destination.
const DESTINATIONS: &[&str] = &["code", "agent", "build"];

/// One caret: Zed's `selections` column, an anchor/head pair
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

/// What a persisted file is. Zed writes the item's *kind* beside its path
/// (the `item_kind` column) so the right view is rebuilt; the two kinds that
/// survive a relaunch here are a text buffer and a media file, and an
/// unknown kind reads as text — which is what opening the path does anyway.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ItemKind {
    #[default]
    Text,
    /// A picture — shown fit-to-view, never by the buffer store.
    Media,
}

/// One open file.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SessionItem {
    /// Project-relative, `/`-separated — the key the file bar uses.
    pub path: String,
    pub kind: ItemKind,
    /// The vertical scroll in pixels, as the editor reports it. Zed keeps a
    /// `scroll_anchor` (an anchor plus a row offset) because a collaborator
    /// can edit its buffers between sessions; ours cannot.
    pub scroll: f32,
    /// Every caret, in document order — `EditorState.caretsInOrder()`.
    pub selections: Vec<SessionSelection>,
}

impl Default for SessionItem {
    fn default() -> Self {
        Self {
            path: String::new(),
            kind: ItemKind::Text,
            scroll: 0.0,
            selections: Vec::new(),
        }
    }
}

/// The whole document: one per project.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SessionDocument {
    pub version: u32,
    /// The project root this describes. Kept so a document that has been
    /// copied — or a file name whose hash collided — is recognised and
    /// refused rather than applied to the wrong project.
    pub root: String,
    /// The open files, **least recently used first**: the last one is the
    /// file that was showing. Opening them in this order rebuilds the
    /// most-recently-used order the file bar keeps.
    pub files: Vec<SessionItem>,
    /// Which of the three destinations was showing: `code`, `agent` or
    /// `build`.
    pub destination: String,
    /// Whether Build was showing its terminal rather than the build controls
    /// (`ShellModes`, ui/shell/build/ShellMode.kt).
    pub shell_mode: bool,
}

impl Default for SessionDocument {
    fn default() -> Self {
        Self {
            version: SESSION_VERSION,
            root: String::new(),
            files: Vec::new(),
            destination: "code".to_owned(),
            shell_mode: false,
        }
    }
}

impl SessionDocument {
    /// The document as it can actually be applied to `root` *now*.
    ///
    /// Every rule in the module doc lives here: files that have gone are
    /// dropped, carets are clamped against the file as it is, the list is
    /// bounded, a path is named once, and a destination this shell does not
    /// have reads as Code.
    pub fn restored(mut self, root: &Path) -> Self {
        self.root = root.to_string_lossy().into_owned();
        let mut seen = std::collections::HashSet::new();
        // A path named twice keeps its *last* mention, which is its place in
        // the recency order; iterate from the end so that is the one kept.
        let mut kept: Vec<SessionItem> = Vec::with_capacity(self.files.len());
        for mut item in self.files.into_iter().rev() {
            if !is_file(root, &item.path) || !seen.insert(item.path.clone()) {
                continue;
            }
            clamp_item(root, &mut item);
            kept.push(item);
        }
        kept.reverse();
        // Newest last, so when the list is over the cap it is the *oldest*
        // that go — after the sweep, so a dropped duplicate cannot cost a
        // place a real file should have kept.
        if kept.len() > MAX_FILES {
            let excess = kept.len() - MAX_FILES;
            kept.drain(0..excess);
        }
        self.files = kept;
        if !DESTINATIONS.contains(&self.destination.as_str()) {
            self.destination = "code".to_owned();
        }
        self.version = SESSION_VERSION;
        self
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
    item.selections.truncate(MAX_FILES);
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

    fn item(path: &str) -> SessionItem {
        SessionItem {
            path: path.to_owned(),
            ..SessionItem::default()
        }
    }

    fn paths(document: &SessionDocument) -> Vec<String> {
        document.files.iter().map(|item| item.path.clone()).collect()
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
        write(&root, "logo.png", "png");

        let document = SessionDocument {
            version: SESSION_VERSION,
            root: root.to_string_lossy().into_owned(),
            files: vec![
                item("README.md"),
                SessionItem {
                    path: "logo.png".to_owned(),
                    kind: ItemKind::Media,
                    ..SessionItem::default()
                },
                SessionItem {
                    path: "src/main.rs".to_owned(),
                    kind: ItemKind::Text,
                    scroll: 12.5,
                    selections: vec![SessionSelection {
                        anchor_row: 1,
                        anchor_col: 0,
                        head_row: 1,
                        head_col: 3,
                    }],
                },
            ],
            destination: "build".to_owned(),
            shell_mode: true,
        };

        let engine = Engine::new();
        let json = serde_json::to_string(&document).unwrap();
        assert!(engine.save_session(&document.root, &json));
        let loaded = engine.load_session(&document.root).unwrap();
        let back: SessionDocument = serde_json::from_str(&loaded).unwrap();
        assert_eq!(back, document.clone().restored(&root));
        // Nothing was lost on the way: the order, the kind, the caret, the
        // scroll, the destination and the mode all came back.
        assert_eq!(back, document);
    }

    #[test]
    fn a_file_that_is_gone_is_dropped_and_the_rest_keep_their_order() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "a.rs", "a\n");
        write(root, "c.rs", "c\n");
        let document = SessionDocument {
            files: vec![item("a.rs"), item("b.rs"), item("c.rs"), item("../etc/passwd"), item("/abs")],
            ..SessionDocument::default()
        }
        .restored(root);
        assert_eq!(paths(&document), vec!["a.rs", "c.rs"]);
    }

    #[test]
    fn a_caret_past_the_end_of_a_file_is_clamped() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        write(root, "short.rs", "ab\ncd\n");
        let document = SessionDocument {
            files: vec![SessionItem {
                path: "short.rs".to_owned(),
                scroll: -4.0,
                selections: vec![
                    SessionSelection {
                        anchor_row: 40,
                        anchor_col: 9,
                        head_row: 40,
                        head_col: 9,
                    },
                    SessionSelection {
                        anchor_row: 1,
                        anchor_col: 7,
                        head_row: 0,
                        head_col: 1,
                    },
                ],
                ..SessionItem::default()
            }],
            ..SessionDocument::default()
        }
        .restored(root);
        let file = &document.files[0];
        assert_eq!(file.scroll, 0.0);
        // Row 40 of a three-line file (the empty line after the final newline
        // counts) is the last line; row 1's seven columns become its two.
        assert_eq!(
            file.selections,
            vec![
                SessionSelection {
                    anchor_row: 2,
                    anchor_col: 0,
                    head_row: 2,
                    head_col: 0,
                },
                SessionSelection {
                    anchor_row: 1,
                    anchor_col: 2,
                    head_row: 0,
                    head_col: 1,
                },
            ]
        );
    }

    #[test]
    fn a_path_named_twice_keeps_its_latest_place_and_the_list_is_bounded() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path();
        for index in 0..(MAX_FILES + 10) {
            write(root, &format!("f{index}.rs"), "x\n");
        }
        let mut files: Vec<SessionItem> = (0..(MAX_FILES + 10)).map(|i| item(&format!("f{i}.rs"))).collect();
        files.push(item("f20.rs"));
        let document = SessionDocument {
            files,
            ..SessionDocument::default()
        }
        .restored(root);
        assert_eq!(document.files.len(), MAX_FILES);
        // The oldest were dropped, the newest — the active file — kept, and
        // f20 appears once, at the end where it was last opened.
        assert_eq!(document.files.last().unwrap().path, "f20.rs");
        assert_eq!(document.files.iter().filter(|f| f.path == "f20.rs").count(), 1);
        assert!(document.files.iter().all(|f| f.path != "f0.rs"));
    }

    #[test]
    fn an_unknown_destination_reads_as_code() {
        let directory = tempfile::tempdir().unwrap();
        let document = SessionDocument {
            destination: "settings".to_owned(),
            shell_mode: true,
            ..SessionDocument::default()
        }
        .restored(directory.path());
        assert_eq!(document.destination, "code");
        assert!(document.shell_mode);
        for name in DESTINATIONS {
            let document = SessionDocument {
                destination: (*name).to_owned(),
                ..SessionDocument::default()
            }
            .restored(directory.path());
            assert_eq!(document.destination, *name);
        }
    }

    #[test]
    fn a_corrupt_or_older_session_file_is_discarded_rather_than_applied() {
        let directory = tempfile::tempdir().unwrap();
        let _guard = session_lock();
        set_directory(directory.path().to_path_buf());
        let root = directory.path().join("project");
        std::fs::create_dir_all(&root).unwrap();
        let root_text = root.to_string_lossy().into_owned();
        let engine = Engine::new();

        // Garbage is refused at write time…
        assert!(!engine.save_session(&root_text, "{ not json"));
        // …and a garbage file that got there some other way is deleted on read.
        let file = session_file(&root).unwrap();
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, "{ not json").unwrap();
        assert!(engine.load_session(&root_text).is_none());
        assert!(!file.exists());

        // The desktop shell's version-1 document is not guessed at.
        std::fs::write(&file, format!(r#"{{"version":1,"root":"{root_text}","panes":{{"kind":"leaf"}}}}"#)).unwrap();
        assert!(engine.load_session(&root_text).is_none());
        assert!(!file.exists());

        // A document for another project is ignored, not applied.
        let other = SessionDocument {
            root: "/somewhere/else".to_owned(),
            ..SessionDocument::default()
        };
        std::fs::write(&file, serde_json::to_string(&other).unwrap()).unwrap();
        assert!(engine.load_session(&root_text).is_none());

        engine.clear_session(&root_text);
        assert!(!file.exists());
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
