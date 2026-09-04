//! Projects: one or more directories on disk, each scanned by Zed's
//! `Worktree`.
//!
//! A project is a *list* of worktrees, in the order they were added, exactly
//! as Zed's `Project` holds `Vec<WorktreeHandle>` (project/src/project.rs).
//! The first one is the primary: it is what `open_project` was given, what
//! the title bar and the terminal call the project, and — following Zed's
//! local-settings precedence — the one whose `.zed/settings.json` wins.
//! Everything else (`AddFolderToProject`, `RemoveWorktreeFromProject`) grows
//! and shrinks the tail.
//!
//! The worktree entities themselves live on the runtime thread (see
//! `runtime.rs`). Everything the UI needs is *mirrored* out of them into
//! [`ProjectState`], an ordinary mutex-guarded struct holding the latest
//! `worktree::Snapshot` per worktree. Queries (children of a directory, entry
//! metadata) run against those snapshots, so they are pure in-memory sum-tree
//! lookups: no locking against the runtime, no risk of blocking the Android
//! main thread.
//!
//! Scanning is asynchronous. [`Engine::open_project`] returns an id
//! immediately; [`Engine::project_version`] bumps every time any mirrored
//! snapshot changes, which is the UI's cue to re-read. That polling shape
//! (rather than a JNI callback) keeps the bridge one-directional.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicUsize;
use std::sync::{Arc, Mutex};

use fs::{Fs, RealFs};
use gpui::{App, Entity, Global, Subscription};
use path::rel_path::RelPath;
use settings::WorktreeId;
use worktree::{Snapshot, Worktree};

use crate::config::ProjectPanelSortMode;
use crate::runtime::next_worktree_handle;

pub type ProjectId = u64;

/// Identifies one worktree within a project. Unique for the life of the
/// process (it is the same counter `Worktree::local` is given), so a stale id
/// held by the UI can never name somebody else's folder.
pub type WorktreeHandle = u64;

/// A directory entry as the UI sees it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct TreeEntry {
    /// Path relative to the worktree root, `/`-separated. Empty for the root.
    pub path: String,
    /// Final path component.
    pub name: String,
    pub is_dir: bool,
    /// Ignored by git. Zed scans ignored directories only once expanded.
    pub is_ignored: bool,
    /// Dot-file, or inside a dot-directory.
    pub is_hidden: bool,
    /// True for a directory whose children have not been scanned yet; the UI
    /// must call [`Engine::expand_directory`] before it can show them.
    pub is_unloaded: bool,
    /// Size in bytes; 0 for directories.
    pub size: u64,
}

/// One entry moved to the trash, and everything needed to put it back.
///
/// Mirrors `trash::TrashItem` in the fields that survive a round trip through
/// JSON: the panel holds these for the life of its Undo affordance and hands
/// them straight back to [`Engine::restore_trashed`].
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct TrashedEntry {
    /// Project-relative path it had, for the message that names it.
    pub path: String,
    /// Full path it now occupies inside the trash.
    pub id: String,
    /// File name at the time of trashing.
    pub name: String,
    /// Absolute path of the directory it came out of.
    pub original_parent: String,
}

/// What the UI can read about a project without touching the runtime.
/// One folder of a project, mirrored out of its `Worktree` entity.
#[derive(Default)]
pub struct WorktreeState {
    pub handle: WorktreeHandle,
    /// Canonical absolute path of this folder.
    pub root: PathBuf,
    pub snapshot: Option<Snapshot>,
    /// This folder's initial scan has finished. Entries are readable before
    /// this, they are just still arriving.
    pub scan_complete: bool,
    /// Set when this folder could not be opened at all.
    pub error: Option<String>,
    /// This folder's own `.zed/settings.json`, parsed — Zed's local settings
    /// (settings/src/settings_store.rs:1074-1112). `None` when there is no
    /// such file, or it does not parse; `local_settings_error` says which.
    pub local_settings: Option<crate::config::ProjectSettingsContent>,
    /// Why `.zed/settings.json` was not taken, when it exists and was not.
    pub local_settings_error: Option<String>,
}

impl WorktreeState {
    /// Display name: the worktree's own root name once scanned, else the
    /// final component of the path we were given.
    pub fn name(&self) -> String {
        self.snapshot
            .as_ref()
            .map(|snapshot| snapshot.root_name_str().to_owned())
            .or_else(|| {
                self.root
                    .file_name()
                    .map(|name| name.to_string_lossy().into_owned())
            })
            .unwrap_or_default()
    }
}

/// What the UI can read about one folder of a project without touching the
/// runtime — the row the project panel draws a root header from.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct WorktreeInfo {
    pub id: WorktreeHandle,
    pub name: String,
    /// Absolute path, canonicalized.
    pub path: String,
    pub scan_complete: bool,
    pub error: Option<String>,
    /// The folder the project was opened with. Zed will not let you remove
    /// the last worktree of a project either.
    pub is_primary: bool,
}

/// What the UI can read about a project without touching the runtime.
#[derive(Default)]
pub struct ProjectState {
    /// In the order they were added; `worktrees[0]` is the primary. Never
    /// empty for a live project.
    pub worktrees: Vec<WorktreeState>,
    /// Bumped on every mirrored change, in any worktree, including the first.
    pub version: u64,
    /// Bumped whenever any worktree's `.zed/settings.json` is re-read — on
    /// open, when a folder is added or removed, and when the watcher sees the
    /// file move. The UI's cue to re-ask the engine for every open buffer's
    /// resolved settings.
    pub settings_version: u64,
}

impl ProjectState {
    pub fn primary(&self) -> Option<&WorktreeState> {
        self.worktrees.first()
    }

    pub fn worktree(&self, handle: WorktreeHandle) -> Option<&WorktreeState> {
        self.worktrees.iter().find(|tree| tree.handle == handle)
    }

    fn worktree_mut(&mut self, handle: WorktreeHandle) -> Option<&mut WorktreeState> {
        self.worktrees.iter_mut().find(|tree| tree.handle == handle)
    }

    /// The primary worktree's root. Empty only for a project whose worktrees
    /// have all been removed, which the API does not allow.
    pub fn root(&self) -> PathBuf {
        self.primary()
            .map(|tree| tree.root.clone())
            .unwrap_or_default()
    }

    /// The primary worktree's snapshot — what a single-root consumer means by
    /// "the project's tree".
    pub fn snapshot(&self) -> Option<Snapshot> {
        self.primary().and_then(|tree| tree.snapshot.clone())
    }

    /// Every worktree has finished its initial scan.
    pub fn scan_complete(&self) -> bool {
        !self.worktrees.is_empty() && self.worktrees.iter().all(|tree| tree.scan_complete)
    }

    /// The first failure among the worktrees, primary first — a project whose
    /// added folder vanished still has something to say.
    pub fn error(&self) -> Option<String> {
        self.worktrees.iter().find_map(|tree| tree.error.clone())
    }

    /// The worktree `path` lives in, deepest root first so a folder added
    /// from inside another one still wins for its own files.
    pub fn worktree_for_path(&self, path: &Path) -> Option<&WorktreeState> {
        self.worktrees
            .iter()
            .filter(|tree| path.starts_with(&tree.root))
            .max_by_key(|tree| tree.root.as_os_str().len())
    }

    /// [`WorktreeInfo`] for the worktree at `index`.
    fn info(&self, index: usize) -> Option<WorktreeInfo> {
        let tree = self.worktrees.get(index)?;
        Some(WorktreeInfo {
            id: tree.handle,
            name: tree.name(),
            path: tree.root.to_string_lossy().into_owned(),
            scan_complete: tree.scan_complete,
            error: tree.error.clone(),
            is_primary: index == 0,
        })
    }
}

/// Where a project keeps its own settings, relative to its root — Zed's
/// `local_settings_file_relative_path()` (paths/src/paths.rs).
pub const LOCAL_SETTINGS_PATH: &str = ".zed/settings.json";

/// Re-read one worktree's `.zed/settings.json` into the mirrored state and
/// bump the project's settings version. A missing file is the ordinary case
/// and clears whatever was there; a broken one is refused whole, and its parse
/// error is kept so the UI can say so rather than silently running on the last
/// good text — Zed reports these as `InvalidSettingsError::LocalSettings`.
fn load_local_settings(state: &Arc<Mutex<ProjectState>>, handle: WorktreeHandle) {
    let Some(path) = state
        .lock()
        .unwrap()
        .worktree(handle)
        .map(|tree| tree.root.join(LOCAL_SETTINGS_PATH))
    else {
        return;
    };
    let (settings, error) = match std::fs::read_to_string(&path) {
        Ok(text) => match crate::config::ProjectSettingsContent::parse(&text) {
            Ok(settings) => (Some(settings), None),
            Err(message) => {
                log::warn!("{}: not taken: {message}", path.display());
                (None, Some(message))
            }
        },
        Err(_) => (None, None),
    };
    let mut state = state.lock().unwrap();
    if let Some(tree) = state.worktree_mut(handle) {
        tree.local_settings = settings;
        tree.local_settings_error = error;
    }
    state.settings_version += 1;
}

/// Worktree entities, held on the runtime thread only.
#[derive(Default)]
struct WorktreeRegistry {
    worktrees: HashMap<(ProjectId, WorktreeHandle), Entity<Worktree>>,
    /// Event subscriptions, kept alive by holding them.
    subscriptions: HashMap<(ProjectId, WorktreeHandle), Subscription>,
}

impl Global for WorktreeRegistry {}

/// The `Fs` implementation and shared entry-id counter the worktrees use.
struct FsGlobal {
    fs: Arc<dyn Fs>,
    next_entry_id: Arc<AtomicUsize>,
}

impl Global for FsGlobal {}

/// Runtime-thread setup: called once from `Runtime::new`.
pub(crate) fn init_globals(cx: &mut App) {
    settings::init(cx);
    let fs = Arc::new(RealFs::new(None, cx.background_executor().clone()));
    cx.set_global(FsGlobal {
        fs,
        next_entry_id: Arc::new(AtomicUsize::new(0)),
    });
    cx.set_global(WorktreeRegistry::default());
}

/// Copy a worktree's current snapshot into the mirrored state.
fn mirror(
    worktree: &Entity<Worktree>,
    state: &Arc<Mutex<ProjectState>>,
    handle: WorktreeHandle,
    cx: &App,
) {
    let snapshot = worktree.read(cx).snapshot();
    let mut state = state.lock().unwrap();
    if let Some(tree) = state.worktree_mut(handle) {
        tree.snapshot = Some(snapshot);
    }
    state.version += 1;
}

/// Sibling order, by Zed's `project_panel.sort_mode`
/// (settings_content/src/workspace.rs:938-946): directories first by default,
/// files first, or one mixed list. The tie-break is always case-insensitive
/// name then the raw name, which is Zed's `SortOrder::Default` minus its
/// natural-number comparison.
///
/// Applied in the engine rather than in the panel so that every caller — the
/// tree, the finder, anything later — agrees on one order.
fn sort_entries(entries: &mut [TreeEntry], mode: ProjectPanelSortMode) {
    entries.sort_by(|a, b| {
        let grouping = match mode {
            ProjectPanelSortMode::DirectoriesFirst => b.is_dir.cmp(&a.is_dir),
            ProjectPanelSortMode::FilesFirst => a.is_dir.cmp(&b.is_dir),
            ProjectPanelSortMode::Mixed => std::cmp::Ordering::Equal,
        };
        grouping
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.name.cmp(&b.name))
    });
}

impl crate::Engine {
    /// Start scanning `path` as a project, with `path` as its first (primary)
    /// worktree. Returns its id immediately; watch [`Engine::project_version`]
    /// for the scan filling in.
    pub fn open_project(&self, path: &Path) -> ProjectId {
        let id = self.next_project_id();
        self.projects
            .lock()
            .unwrap()
            .insert(id, Arc::new(Mutex::new(ProjectState::default())));
        self.start_worktree(id, path);
        id
    }

    /// Add another folder to an open project — Zed's
    /// `workspace::AddFolderToProject`, which routes through
    /// `Project::find_or_create_worktree` (project/src/project.rs): a path
    /// already covered by one of the project's worktrees is *not* added twice,
    /// the existing one is returned.
    ///
    /// The engine needs a real path, so importing a SAF tree is the caller's
    /// job (see docs/ARCHITECTURE.md, "Where projects live"); by the time we
    /// are called the folder exists on disk.
    ///
    /// Returns the worktree's handle, or an error for an unknown project.
    pub fn add_worktree(&self, id: ProjectId, path: &Path) -> Result<WorktreeHandle, String> {
        let canonical = std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
        let existing = self
            .with_project(id, |state| {
                state.worktree_for_path(&canonical).map(|tree| tree.handle)
            })
            .ok_or_else(|| format!("no project {id}"))?;
        if let Some(handle) = existing {
            return Ok(handle);
        }
        Ok(self.start_worktree(id, path))
    }

    /// Drop a folder from a project — Zed's
    /// `workspace::RemoveWorktreeFromProject`.
    ///
    /// The primary worktree is refused: a project with no root has no name, no
    /// terminal directory and no repository, and every caller here assumes one
    /// exists. Closing the project is the way to let go of it.
    pub fn remove_worktree(&self, id: ProjectId, handle: WorktreeHandle) -> Result<(), String> {
        let state = self
            .projects
            .lock()
            .unwrap()
            .get(&id)
            .cloned()
            .ok_or_else(|| format!("no project {id}"))?;
        {
            let mut state = state.lock().unwrap();
            let index = state
                .worktrees
                .iter()
                .position(|tree| tree.handle == handle)
                .ok_or_else(|| "that folder is not part of this project".to_owned())?;
            if index == 0 {
                return Err("the project's own folder cannot be removed".to_owned());
            }
            state.worktrees.remove(index);
            state.version += 1;
            state.settings_version += 1;
        }
        self.runtime().spawn(move |cx| {
            let registry = cx.global_mut::<WorktreeRegistry>();
            registry.worktrees.remove(&(id, handle));
            registry.subscriptions.remove(&(id, handle));
        });
        Ok(())
    }

    /// Every folder of a project, in order, primary first.
    pub fn project_worktrees(&self, id: ProjectId) -> Vec<WorktreeInfo> {
        self.with_project(id, |state| {
            (0..state.worktrees.len())
                .filter_map(|index| state.info(index))
                .collect()
        })
        .unwrap_or_default()
    }

    /// Mirror one folder into [`ProjectState::worktrees`] and start scanning
    /// it. The handle is allocated synchronously so the caller can name the
    /// folder before a single entry has arrived.
    fn start_worktree(&self, id: ProjectId, path: &Path) -> WorktreeHandle {
        let handle = next_worktree_handle();
        let worktree_id = WorktreeId::from_usize(handle);
        let handle = handle as WorktreeHandle;
        let Some(state) = self.projects.lock().unwrap().get(&id).cloned() else {
            return handle;
        };
        // Canonicalized, because everything this root is compared against or
        // handed to already is: buffer paths (`Engine::open_file`), published
        // diagnostic paths (lsp.rs), the watcher root below. On Android the
        // caller's spelling is /data/user/0/<pkg>/…, a symlink to
        // /data/data/<pkg> — kept as-is, `project_for_path` would say every
        // buffer lies outside its own project, and a language server would be
        // rooted at a URI none of its documents are under.
        state.lock().unwrap().worktrees.push(WorktreeState {
            handle,
            root: std::fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf()),
            ..Default::default()
        });

        let path: Arc<Path> = Arc::from(path);
        let buffers = self.buffers.clone();
        let (lsp_slots, lsp_watchers) = self.lsp_watch_handles();
        self.runtime().spawn(move |cx| {
            let global = cx.global::<FsGlobal>();
            let fs = global.fs.clone();
            let next_entry_id = global.next_entry_id.clone();

            cx.spawn(async move |cx| {
                let fail = |message: String| {
                    log::warn!("project {id} worktree {handle} failed to open: {message}");
                    let mut state = state.lock().unwrap();
                    if let Some(tree) = state.worktree_mut(handle) {
                        tree.error = Some(message);
                        // A folder that will never scan must not hold up
                        // everybody waiting on the project's scan.
                        tree.scan_complete = true;
                    }
                    state.version += 1;
                };
                log::info!("project {id}: opening {}", path.display());

                // `Worktree::local` happily accepts a path that isn't there
                // (Zed opens worktrees for paths yet to be created), which
                // would leave the UI staring at an empty tree with no
                // explanation. Say so instead.
                match fs.metadata(&path).await {
                    Ok(Some(metadata)) if metadata.is_dir => {}
                    Ok(Some(_)) => {
                        fail(format!("{} is not a directory", path.display()));
                        return;
                    }
                    Ok(None) => {
                        fail(format!("{} does not exist", path.display()));
                        return;
                    }
                    Err(err) => {
                        fail(format!("{err:#}"));
                        return;
                    }
                }

                let worktree =
                    match Worktree::local(path, true, fs, next_entry_id, true, worktree_id, cx)
                        .await
                    {
                        Ok(worktree) => worktree,
                        Err(err) => {
                            fail(format!("{err:#}"));
                            return;
                        }
                    };

                log::info!("project {id}: worktree {handle} created");
                // Before the first mirror, so a buffer opened the moment the
                // tree appears already resolves against the project's file.
                load_local_settings(&state, handle);
                let scan_complete = cx.update(|cx| {
                    mirror(&worktree, &state, handle, cx);
                    let mirrored = state.clone();
                    // The worktree reports changes relative to its root, but
                    // open buffers hold canonical paths. Resolve the root once
                    // here rather than canonicalizing every changed path: on
                    // Android in particular, `filesDir` is
                    // /data/user/0/<pkg>/files, a symlink to /data/data/<pkg>,
                    // so the two spellings would never match.
                    let root = worktree.read(cx).abs_path();
                    let root = std::fs::canonicalize(&root).unwrap_or_else(|_| root.to_path_buf());
                    let subscription =
                        cx.subscribe(&worktree, move |worktree, event: &worktree::Event, cx| {
                            if let worktree::Event::UpdatedEntries(changes) = event {
                                let paths: Vec<PathBuf> = changes
                                    .iter()
                                    .map(|(path, _, _)| root.join(path.as_std_path()))
                                    .collect();
                                crate::file::note_disk_changes(&buffers, &paths);
                                // This folder's own settings file moved:
                                // re-read it, which is Zed's watcher-driven
                                // `set_local_settings` (project/src/project.rs
                                // `UpdatedEntries` → `update_local_settings`).
                                if paths.iter().any(|path| path.ends_with(LOCAL_SETTINGS_PATH)) {
                                    load_local_settings(&mirrored, handle);
                                }
                                // The same batch, for the servers that
                                // registered watchers — minus what must not
                                // travel: the initial scan (see
                                // `watched_change_type`) and the save path's
                                // own `.thragg-tmp` staging files, which
                                // appear and vanish on every save.
                                let watched: Vec<_> = changes
                                    .iter()
                                    .filter_map(|(path, _, change)| {
                                        let kind = crate::lsp::watched_change_type(change)?;
                                        let path = root.join(path.as_std_path());
                                        let temp = path
                                            .file_name()
                                            .and_then(|name| name.to_str())
                                            .is_some_and(|name| name.ends_with(".thragg-tmp"));
                                        (!temp).then_some((path, kind))
                                    })
                                    .collect();
                                crate::lsp::notify_watched_files(
                                    &lsp_slots,
                                    &lsp_watchers,
                                    id,
                                    &root,
                                    &watched,
                                );
                            }
                            mirror(&worktree, &mirrored, handle, cx);
                        });
                    let registry = cx.global_mut::<WorktreeRegistry>();
                    registry.worktrees.insert((id, handle), worktree.clone());
                    registry.subscriptions.insert((id, handle), subscription);
                    worktree
                        .read(cx)
                        .as_local()
                        .map(|local| local.scan_complete())
                });

                if let Some(scan_complete) = scan_complete {
                    scan_complete.await;
                }
                cx.update(|cx| {
                    if let Some(worktree) =
                        cx.global::<WorktreeRegistry>().worktrees.get(&(id, handle))
                    {
                        let worktree = worktree.read(cx);
                        log::info!(
                            "project {id}: worktree {handle} scan complete, {} files, {} dirs",
                            worktree.file_count(),
                            worktree.dir_count()
                        );
                        let snapshot = worktree.snapshot();
                        let mut state = state.lock().unwrap();
                        if let Some(tree) = state.worktree_mut(handle) {
                            tree.snapshot = Some(snapshot);
                            tree.scan_complete = true;
                        }
                        state.version += 1;
                    }
                });
            })
            .detach();
        });

        handle
    }

    /// Stop scanning a project and forget its mirrored state.
    pub fn close_project(&self, id: ProjectId) -> bool {
        self.searches.cancel_project(id);
        // Before the root goes: shutting a server down needs to know where it
        // was started, and its diagnostics are meaningless without the project
        // they are relative to.
        self.lsp_close_project(id);
        let existed = self.projects.lock().unwrap().remove(&id).is_some();
        if existed {
            self.runtime().spawn(move |cx| {
                let registry = cx.global_mut::<WorktreeRegistry>();
                registry.worktrees.retain(|(project, _), _| *project != id);
                registry
                    .subscriptions
                    .retain(|(project, _), _| *project != id);
            });
        }
        existed
    }

    /// Monotonic counter, bumped whenever any mirrored snapshot changes.
    /// Returns 0 for an unknown project — which is also the value before the
    /// first mirror, so "unknown" and "not scanned yet" are deliberately not
    /// distinguished: both mean "nothing to show".
    pub fn project_version(&self, id: ProjectId) -> u64 {
        self.with_project(id, |state| state.version).unwrap_or(0)
    }

    /// Every folder has finished its initial scan.
    pub fn project_scan_complete(&self, id: ProjectId) -> bool {
        self.with_project(id, |state| state.scan_complete())
            .unwrap_or(false)
    }

    /// The mirrored snapshot of the primary worktree, for a reader outside
    /// this module — the LSP module walks it to learn which languages the
    /// tree holds.
    pub(crate) fn project_snapshot(&self, id: ProjectId) -> Option<Snapshot> {
        self.with_project(id, |state| state.snapshot()).flatten()
    }

    /// Every folder's mirrored snapshot, with its handle, display name and
    /// absolute root — what the file finder and project search walk.
    pub(crate) fn project_snapshots(
        &self,
        id: ProjectId,
    ) -> Vec<(WorktreeHandle, String, PathBuf, Snapshot)> {
        self.with_project(id, |state| {
            state
                .worktrees
                .iter()
                .filter_map(|tree| {
                    let snapshot = tree.snapshot.clone()?;
                    Some((tree.handle, tree.name(), tree.root.clone(), snapshot))
                })
                .collect()
        })
        .unwrap_or_default()
    }

    /// Monotonic counter for the project's `.zed/settings.json` files; see
    /// [`ProjectState::settings_version`]. 0 for an unknown project.
    pub fn project_settings_version(&self, id: ProjectId) -> u64 {
        self.with_project(id, |state| state.settings_version)
            .unwrap_or(0)
    }

    /// Why a `.zed/settings.json` is not in effect — its parse error — or
    /// `None` when they all are, or there are none. Primary folder first, the
    /// same order [`Engine::project_local_settings`] resolves in.
    pub fn project_settings_error(&self, id: ProjectId) -> Option<String> {
        self.with_project(id, |state| {
            state
                .worktrees
                .iter()
                .find_map(|tree| tree.local_settings_error.clone())
        })
        .flatten()
    }

    /// The project's local settings, for the resolvers in config.rs.
    ///
    /// With several folders open the first one that has a `.zed/settings.json`
    /// wins, primary first — Zed resolves local settings per worktree, and the
    /// folder the project was opened with is the one it consults for anything
    /// that is not specific to a file in another root.
    pub(crate) fn project_local_settings(
        &self,
        id: ProjectId,
    ) -> Option<crate::config::ProjectSettingsContent> {
        self.with_project(id, |state| {
            state
                .worktrees
                .iter()
                .find_map(|tree| tree.local_settings.clone())
        })
        .flatten()
    }

    /// Re-read every folder's `.zed/settings.json` now rather than when the
    /// watcher gets to it — what the editor calls after writing the file
    /// itself, so the save and its effect land in the same breath.
    pub fn reload_project_settings(&self, id: ProjectId) -> bool {
        let Some(state) = self.projects.lock().unwrap().get(&id).cloned() else {
            return false;
        };
        let handles: Vec<WorktreeHandle> = state
            .lock()
            .unwrap()
            .worktrees
            .iter()
            .map(|tree| tree.handle)
            .collect();
        for handle in handles {
            load_local_settings(&state, handle);
        }
        true
    }

    /// The error that stopped the project — or one of its folders — from
    /// opening, if any.
    pub fn project_error(&self, id: ProjectId) -> Option<String> {
        self.with_project(id, |state| state.error()).flatten()
    }

    /// Absolute path of the primary worktree's root.
    pub fn project_root(&self, id: ProjectId) -> Option<PathBuf> {
        self.with_project(id, |state| state.root())
    }

    /// Display name of the primary worktree (its final path component).
    pub fn project_root_name(&self, id: ProjectId) -> Option<String> {
        self.with_project(id, |state| {
            state.primary().map(|tree| tree.name()).unwrap_or_default()
        })
    }

    /// The folder of `id` that holds `path`, if any — how a consumer working
    /// in absolute paths (a buffer, a diagnostic) finds which root to make
    /// them relative to.
    pub fn worktree_for_path(&self, id: ProjectId, path: &Path) -> Option<WorktreeInfo> {
        self.with_project(id, |state| {
            let handle = state.worktree_for_path(path)?.handle;
            let index = state
                .worktrees
                .iter()
                .position(|tree| tree.handle == handle)?;
            state.info(index)
        })
        .flatten()
    }

    /// Direct children of `dir` in the primary worktree — the single-root
    /// spelling every existing caller uses. See [`Engine::worktree_entries`]
    /// for the rest.
    pub fn project_entries(&self, id: ProjectId, dir: &str) -> Vec<TreeEntry> {
        let Some(Some(snapshot)) = self.with_project(id, |state| state.snapshot()) else {
            return Vec::new();
        };
        entries_of(&snapshot, dir, self.settings().project_panel.sort_mode)
    }

    /// Direct children of `dir` (relative to that worktree's root, `""` for
    /// the root itself), sorted directories-first. Empty for unknown
    /// projects, unknown worktrees, unknown directories, and directories that
    /// have not been scanned yet.
    pub fn worktree_entries(
        &self,
        id: ProjectId,
        worktree: WorktreeHandle,
        dir: &str,
    ) -> Vec<TreeEntry> {
        let Some(Some(snapshot)) = self.with_project(id, |state| {
            state
                .worktree(worktree)
                .and_then(|tree| tree.snapshot.clone())
        }) else {
            return Vec::new();
        };
        entries_of(&snapshot, dir, self.settings().project_panel.sort_mode)
    }

    /// Scan a directory Zed deferred, in the primary worktree.
    pub fn expand_directory(&self, id: ProjectId, dir: &str) -> bool {
        let Some(Some(handle)) =
            self.with_project(id, |state| state.primary().map(|tree| tree.handle))
        else {
            return false;
        };
        self.expand_worktree_directory(id, handle, dir)
    }

    /// Scan a directory Zed deferred — an ignored or hidden one, or one past
    /// `file_scan_depth`. Asynchronous: the results show up as a version bump.
    /// Returns false if the project, folder or path is unknown.
    pub fn expand_worktree_directory(
        &self,
        id: ProjectId,
        worktree: WorktreeHandle,
        dir: &str,
    ) -> bool {
        let Some(Some(snapshot)) = self.with_project(id, |state| {
            state
                .worktree(worktree)
                .and_then(|tree| tree.snapshot.clone())
        }) else {
            return false;
        };
        let Ok(path) = RelPath::from_unix_str(dir) else {
            return false;
        };
        let Some(entry_id) = snapshot.entry_for_path(&path).map(|entry| entry.id) else {
            return false;
        };
        self.runtime().spawn(move |cx| {
            let Some(tree) = cx
                .global::<WorktreeRegistry>()
                .worktrees
                .get(&(id, worktree))
                .cloned()
            else {
                return;
            };
            let task = tree.update(cx, |tree, cx| tree.expand_entry(entry_id, cx));
            if let Some(task) = task {
                task.detach();
            }
        });
        true
    }

    /// Absolute path of a *project* path.
    ///
    /// A project path is worktree-relative inside the folder the project was
    /// opened with, and `<folder name>/<relative>` inside any other — the
    /// spelling Zed puts in front of a path once a project has more than one
    /// worktree (its `path_prefix`, file_finder/src/file_finder.rs). A
    /// single-folder project therefore spells everything exactly as it did
    /// before, which is what keeps tabs, git status and the editor agreeing.
    ///
    /// Resolution is deliberately ordered so a folder added later can never
    /// take a path away from the project's own folder: the primary is tried
    /// first and only a path it does not hold is offered to the others.
    pub fn project_entry_abs_path(&self, id: ProjectId, path: &str) -> Option<PathBuf> {
        let roots: Vec<(bool, String, PathBuf, bool)> = self.with_project(id, |state| {
            state
                .worktrees
                .iter()
                .enumerate()
                .map(|(index, tree)| {
                    let holds = index == 0
                        && RelPath::from_unix_str(path).is_ok_and(|relative| {
                            tree.snapshot
                                .as_ref()
                                .is_some_and(|snapshot| snapshot.entry_for_path(&relative).is_some())
                        });
                    (index == 0, tree.name(), tree.root.clone(), holds)
                })
                .collect()
        })?;
        let (_, _, primary_root, primary_holds) = roots.first()?.clone();
        if roots.len() == 1 || primary_holds || path.is_empty() {
            return join_entry(primary_root, path);
        }
        let (head, rest) = match path.split_once('/') {
            Some((head, rest)) => (head, rest),
            None => (path, ""),
        };
        for (is_primary, name, root, _) in &roots {
            if !is_primary && name == head {
                return join_entry(root.clone(), rest);
            }
        }
        join_entry(primary_root, path)
    }

    /// The project path of `relative` inside `worktree` — the inverse of
    /// [`Engine::project_entry_abs_path`]. Unprefixed for the project's own
    /// folder, `<folder name>/<relative>` for any other.
    pub fn project_display_path(
        &self,
        id: ProjectId,
        worktree: WorktreeHandle,
        relative: &str,
    ) -> String {
        let prefix = self
            .with_project(id, |state| {
                let index = state
                    .worktrees
                    .iter()
                    .position(|tree| tree.handle == worktree)?;
                (index > 0).then(|| state.worktrees[index].name())
            })
            .flatten();
        match prefix {
            Some(name) if relative.is_empty() => name,
            Some(name) => format!("{name}/{relative}"),
            None => relative.to_owned(),
        }
    }

    /// Absolute path of an entry relative to one folder's root.
    pub fn worktree_entry_abs_path(
        &self,
        id: ProjectId,
        worktree: WorktreeHandle,
        path: &str,
    ) -> Option<PathBuf> {
        let root = self.with_project(id, |state| {
            state.worktree(worktree).map(|tree| tree.root.clone())
        })??;
        join_entry(root, path)
    }

    /// Move project entries to the app's trash — Zed's `project_panel::Trash`
    /// (project_panel.rs, `trash` / `Fs::trash_path`), which is what its
    /// Delete key does by default.
    ///
    /// Android has no system trash, so this goes through the vendored
    /// `trash-android` crate the same way git discard does (git.rs:1644): one
    /// directory per entry under the app's private files directory, which the
    /// engine points at during [`crate::initialize`]. Returns one
    /// [`TrashedEntry`] per path, in the order they were trashed, so the panel
    /// can offer an Undo that puts them back.
    ///
    /// Stops at the first failure and reports it, keeping what it has already
    /// moved: a half-done bulk delete the user can undo is better than one
    /// that silently skips entries. **Blocking** — it moves files.
    pub fn trash_project_entries(
        &self,
        id: ProjectId,
        paths: &[String],
    ) -> Result<Vec<TrashedEntry>, String> {
        let mut trashed = Vec::with_capacity(paths.len());
        for path in paths {
            if path.is_empty() {
                return Err("The project itself cannot be moved to the trash".into());
            }
            let absolute = self
                .project_entry_abs_path(id, path)
                .ok_or_else(|| format!("{path} is not in this project"))?;
            match trash::delete_with_info(&absolute) {
                Ok(item) => trashed.push(TrashedEntry {
                    path: path.clone(),
                    id: item.id.to_string_lossy().into_owned(),
                    name: item.name.to_string_lossy().into_owned(),
                    original_parent: item.original_parent.to_string_lossy().into_owned(),
                }),
                Err(err) => return Err(format!("Could not move {path} to the trash: {err}")),
            }
        }
        Ok(trashed)
    }

    /// Put trashed entries back where they came from — the Undo behind
    /// [`Engine::trash_project_entries`], and Zed's `project_panel::Undo`.
    ///
    /// Refuses the whole restore if any destination is occupied again rather
    /// than overwriting: the file that took the name may be the reason the
    /// original was deleted. **Blocking**.
    pub fn restore_trashed(&self, entries: &[TrashedEntry]) -> Result<(), String> {
        let items: Vec<trash::TrashItem> = entries
            .iter()
            .map(|entry| trash::TrashItem {
                id: std::ffi::OsString::from(&entry.id),
                name: std::ffi::OsString::from(&entry.name),
                original_parent: PathBuf::from(&entry.original_parent),
                time_deleted: 0,
            })
            .collect();
        trash::restore_all(items).map_err(|err| format!("Could not restore: {err}"))
    }

    pub(crate) fn with_project<T>(
        &self,
        id: ProjectId,
        f: impl FnOnce(&ProjectState) -> T,
    ) -> Option<T> {
        let state = self.projects.lock().unwrap().get(&id).cloned()?;
        let state = state.lock().unwrap();
        Some(f(&state))
    }
}

/// Children of `dir` in one snapshot, in the panel's order.
fn entries_of(snapshot: &Snapshot, dir: &str, sort_mode: ProjectPanelSortMode) -> Vec<TreeEntry> {
    let Ok(dir) = RelPath::from_unix_str(dir) else {
        return Vec::new();
    };
    let mut entries: Vec<TreeEntry> = snapshot
        .child_entries(&dir)
        .map(|entry| TreeEntry {
            path: entry.path.as_unix_str().to_owned(),
            name: entry
                .path
                .file_name()
                .unwrap_or(snapshot.root_name_str())
                .to_owned(),
            is_dir: entry.is_dir(),
            is_ignored: entry.is_ignored,
            is_hidden: entry.is_hidden,
            is_unloaded: entry.kind.is_unloaded(),
            size: if entry.is_dir() { 0 } else { entry.size },
        })
        .collect();
    sort_entries(&mut entries, sort_mode);
    entries
}

/// `root` joined with a worktree-relative entry path, refusing anything that
/// could escape the root. The UI only ever passes paths it got from
/// [`Engine::worktree_entries`], so this is a guard, not a feature.
fn join_entry(root: PathBuf, path: &str) -> Option<PathBuf> {
    if path.is_empty() {
        return Some(root);
    }
    if path.split('/').any(|part| part == ".." || part.is_empty()) {
        return None;
    }
    Some(root.join(path))
}

#[cfg(test)]
mod tests {
    use super::{ProjectPanelSortMode, TreeEntry, sort_entries};
    use crate::Engine;
    use std::time::{Duration, Instant};

    /// Block until the scan finishes. The runtime is genuinely concurrent, so
    /// tests wait on it rather than assuming a synchronous open.
    fn wait_for_scan(engine: &Engine, id: u64) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline {
            if engine.project_scan_complete(id) {
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        panic!("project {id} did not finish scanning");
    }

    fn fixture() -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("src/nested")).unwrap();
        // A worktree outside a git repository defers directories deeper than
        // `file_scan_depth`; a .git makes this a repo, as real projects are.
        std::fs::create_dir_all(root.join(".git")).unwrap();
        std::fs::write(root.join("Cargo.toml"), "[package]\n").unwrap();
        std::fs::write(root.join(".gitignore"), "target\n").unwrap();
        std::fs::write(root.join("src/main.rs"), "fn main() {}\n").unwrap();
        std::fs::write(root.join("src/nested/deep.rs"), "// deep\n").unwrap();
        std::fs::create_dir_all(root.join("target")).unwrap();
        std::fs::write(root.join("target/artifact.bin"), "binary").unwrap();
        dir
    }

    #[test]
    fn scans_a_project_tree() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);

        let root = engine.project_entries(id, "");
        let names: Vec<&str> = root.iter().map(|entry| entry.name.as_str()).collect();
        // Directories first, then files, each alphabetically. `.git` is
        // absent: Zed's default `file_scan_exclusions` drop it.
        assert_eq!(names, vec!["src", "target", ".gitignore", "Cargo.toml"]);
        assert!(root.iter().find(|e| e.name == "src").unwrap().is_dir);
        assert!(root.iter().find(|e| e.name == "target").unwrap().is_ignored);
        assert!(!root.iter().find(|e| e.name == "src").unwrap().is_ignored);
        assert!(
            root.iter()
                .find(|e| e.name == ".gitignore")
                .unwrap()
                .is_hidden
        );

        let src = engine.project_entries(id, "src");
        let names: Vec<&str> = src.iter().map(|entry| entry.name.as_str()).collect();
        assert_eq!(names, vec!["nested", "main.rs"]);
        let main = src.iter().find(|e| e.name == "main.rs").unwrap();
        assert_eq!(main.path, "src/main.rs");
        assert_eq!(main.size, "fn main() {}\n".len() as u64);

        assert_eq!(
            engine
                .project_entries(id, "src/nested")
                .iter()
                .map(|e| e.name.as_str())
                .collect::<Vec<_>>(),
            vec!["deep.rs"]
        );

        assert_eq!(
            engine.project_root_name(id).as_deref(),
            dir.path().file_name().unwrap().to_str()
        );
        assert!(engine.project_version(id) > 0);
        assert_eq!(engine.project_error(id), None);
    }

    #[test]
    fn ignored_directories_are_expanded_on_demand() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);

        // `target` is gitignored, so Zed lists it but not its contents.
        let target = engine
            .project_entries(id, "")
            .into_iter()
            .find(|entry| entry.name == "target")
            .unwrap();
        assert!(target.is_ignored);
        assert!(engine.project_entries(id, "target").is_empty());

        assert!(engine.expand_directory(id, "target"));
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && engine.project_entries(id, "target").is_empty() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(
            engine
                .project_entries(id, "target")
                .iter()
                .map(|e| e.name.as_str())
                .collect::<Vec<_>>(),
            vec!["artifact.bin"]
        );
    }

    #[test]
    fn reports_a_missing_project() {
        let engine = Engine::new();
        let id = engine.open_project(std::path::Path::new("/definitely/not/here"));
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && engine.project_error(id).is_none() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(engine.project_error(id).is_some());
        assert!(engine.project_entries(id, "").is_empty());
    }

    #[test]
    fn abs_paths_cannot_escape_the_root() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        assert_eq!(
            engine.project_entry_abs_path(id, "src/main.rs"),
            Some(dir.path().join("src/main.rs"))
        );
        assert_eq!(
            engine.project_entry_abs_path(id, ""),
            Some(dir.path().to_path_buf())
        );
        assert_eq!(engine.project_entry_abs_path(id, "../secrets"), None);
        assert_eq!(engine.project_entry_abs_path(id, "src//main.rs"), None);
    }

    #[test]
    fn the_worktree_watcher_flags_an_open_buffer() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);

        let file = dir.path().join("src/main.rs");
        let buffer = engine.open_file(&file).unwrap();
        assert!(!engine.buffer_has_disk_change(buffer));

        // Write from "outside" and let the watcher notice. This exercises the
        // real notify-backed path, not `note_disk_changes` directly, so a
        // watcher that silently does nothing fails the test.
        std::fs::write(&file, "fn main() { /* edited elsewhere */ }\n").unwrap();

        let deadline = Instant::now() + Duration::from_secs(15);
        while Instant::now() < deadline && !engine.buffer_has_disk_change(buffer) {
            std::thread::sleep(Duration::from_millis(20));
        }
        assert!(
            engine.buffer_has_disk_change(buffer),
            "the worktree watcher never reported the change"
        );
        // Flagged only — the buffer still holds what was loaded.
        assert_eq!(engine.text(buffer).unwrap(), "fn main() {}\n");
    }

    #[test]
    fn saving_from_the_engine_is_not_an_external_change() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);

        let buffer = engine.open_file(&dir.path().join("src/main.rs")).unwrap();
        let end = engine.text(buffer).unwrap().len();
        engine.edit(buffer, end, end, "// ours\n").unwrap();
        engine.save_buffer(buffer).unwrap();

        // Give the watcher time to deliver our own write, which must not be
        // mistaken for somebody else's.
        std::thread::sleep(Duration::from_millis(1500));
        assert!(!engine.buffer_has_disk_change(buffer));
        assert!(!engine.buffer_is_dirty(buffer));
    }

    /// A project's `.zed/settings.json` is read when the project opens and
    /// overlays the user's settings for that project's buffers — Zed's local
    /// settings — with a parse error kept, not swallowed.
    #[test]
    fn a_projects_own_settings_file_is_read_on_open() {
        use crate::config::{LanguageSettings, Settings};
        let dir = fixture();
        std::fs::create_dir_all(dir.path().join(".zed")).unwrap();
        std::fs::write(
            dir.path().join(".zed/settings.json"),
            "// local\n{ \"tab_size\": 2, \"languages\": { \"Rust\": { \"format_on_save\": \"on\" } } }",
        )
        .unwrap();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);

        assert!(engine.project_settings_version(id) > 0);
        assert_eq!(engine.project_settings_error(id), None);
        let local = engine.project_local_settings(id).expect("the file was read");
        let rust = LanguageSettings::resolve(&Settings::default(), None, Some(&local), Some("Rust"));
        assert_eq!(rust.tab_size, 2);
        assert_eq!(rust.format_on_save, crate::config::FormatOnSave::On);
        let toml = LanguageSettings::resolve(&Settings::default(), None, Some(&local), Some("TOML"));
        assert_eq!(toml.tab_size, 2);
        assert_eq!(toml.format_on_save, crate::config::FormatOnSave::Off);

        // A broken file is refused whole, and says why.
        std::fs::write(dir.path().join(".zed/settings.json"), "{ nope").unwrap();
        let before = engine.project_settings_version(id);
        assert!(engine.reload_project_settings(id));
        assert!(engine.project_settings_version(id) > before);
        assert!(engine.project_settings_error(id).is_some());
        assert!(engine.project_local_settings(id).is_none());

        // And no file at all is the ordinary state: no error, no overlay.
        std::fs::remove_file(dir.path().join(".zed/settings.json")).unwrap();
        engine.reload_project_settings(id);
        assert_eq!(engine.project_settings_error(id), None);
        assert!(engine.project_local_settings(id).is_none());
        assert!(!engine.reload_project_settings(id + 100));
    }

    /// The worktree watcher re-reads `.zed/settings.json` when it changes on
    /// disk, which is how an edit from the terminal — or a `git pull` —
    /// takes effect without a reopen. The same notify-backed path as the
    /// buffer test above, so a watcher that does nothing fails it.
    #[test]
    fn the_worktree_watcher_reloads_the_projects_settings() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);
        let before = engine.project_settings_version(id);
        assert!(engine.project_local_settings(id).is_none());

        std::fs::create_dir_all(dir.path().join(".zed")).unwrap();
        std::fs::write(dir.path().join(".zed/settings.json"), "{ \"tab_size\": 3 }").unwrap();

        let deadline = Instant::now() + Duration::from_secs(15);
        while Instant::now() < deadline && engine.project_settings_version(id) == before {
            std::thread::sleep(Duration::from_millis(20));
        }
        let local = engine
            .project_local_settings(id)
            .expect("the watcher never reloaded the project's settings");
        assert_eq!(local.defaults.tab_size, Some(3));
    }


    /// A second folder: `add_worktree` appends it, its entries are listed
    /// through its own handle, and the project's paths gain the folder's name
    /// in front of them — Zed's `AddFolderToProject` and its `path_prefix`.
    #[test]
    fn a_second_folder_joins_the_project() {
        let first = fixture();
        let second = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(second.path().join("lib")).unwrap();
        std::fs::write(second.path().join("lib/helper.rs"), "// helper\n").unwrap();
        std::fs::write(second.path().join("notes.md"), "# notes\n").unwrap();

        let engine = Engine::new();
        let id = engine.open_project(first.path());
        wait_for_scan(&engine, id);
        let added = engine.add_worktree(id, second.path()).unwrap();
        wait_for_scan(&engine, id);

        let folders = engine.project_worktrees(id);
        assert_eq!(folders.len(), 2);
        assert!(folders[0].is_primary);
        assert!(!folders[1].is_primary);
        assert_eq!(folders[1].id, added);
        assert_eq!(
            folders[1].name,
            second.path().file_name().unwrap().to_str().unwrap()
        );

        // Each folder lists its own tree, through its own handle.
        let primary = folders[0].id;
        assert_eq!(
            engine
                .worktree_entries(id, primary, "")
                .iter()
                .map(|entry| entry.name.as_str())
                .collect::<Vec<_>>(),
            vec!["src", "target", ".gitignore", "Cargo.toml"]
        );
        assert_eq!(
            engine
                .worktree_entries(id, added, "")
                .iter()
                .map(|entry| entry.name.as_str())
                .collect::<Vec<_>>(),
            vec!["lib", "notes.md"]
        );
        assert_eq!(
            engine
                .worktree_entries(id, added, "lib")
                .iter()
                .map(|entry| entry.name.as_str())
                .collect::<Vec<_>>(),
            vec!["helper.rs"]
        );
        // The single-folder spelling still means the project's own folder.
        assert_eq!(
            engine.project_entries(id, ""),
            engine.worktree_entries(id, primary, "")
        );

        // Project paths: unprefixed in the primary, folder-name-prefixed in
        // the other, and both resolve back to the right file.
        let name = &folders[1].name;
        assert_eq!(
            engine.project_display_path(id, primary, "src/main.rs"),
            "src/main.rs"
        );
        assert_eq!(
            engine.project_display_path(id, added, "lib/helper.rs"),
            format!("{name}/lib/helper.rs")
        );
        assert_eq!(
            engine.project_entry_abs_path(id, "src/main.rs"),
            Some(first.path().join("src/main.rs"))
        );
        assert_eq!(
            engine.worktree_entry_abs_path(id, added, "lib/helper.rs"),
            Some(second.path().join("lib/helper.rs"))
        );
        assert_eq!(
            engine.project_entry_abs_path(id, &format!("{name}/lib/helper.rs")),
            Some(second.path().join("lib/helper.rs"))
        );
        assert_eq!(
            engine.project_entry_abs_path(id, name),
            Some(second.path().to_path_buf())
        );
    }

    /// A path already covered by one of the project's folders is not added
    /// twice — Zed's `find_or_create_worktree` hands back the folder it found.
    /// And the folder the project was opened with cannot be removed.
    #[test]
    fn folders_are_deduplicated_and_the_first_one_stays() {
        let dir = fixture();
        let other = tempfile::tempdir().unwrap();
        std::fs::write(other.path().join("a.txt"), "a\n").unwrap();

        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);
        let primary = engine.project_worktrees(id)[0].id;

        assert_eq!(engine.add_worktree(id, dir.path()), Ok(primary));
        // A directory *inside* an open folder is covered by it already.
        assert_eq!(engine.add_worktree(id, &dir.path().join("src")), Ok(primary));
        assert_eq!(engine.project_worktrees(id).len(), 1);

        let added = engine.add_worktree(id, other.path()).unwrap();
        assert_eq!(engine.project_worktrees(id).len(), 2);
        assert!(engine.remove_worktree(id, primary).is_err());
        assert!(engine.remove_worktree(id, added).is_ok());
        assert_eq!(engine.project_worktrees(id).len(), 1);
        // Removing it twice is an error, not a silent success.
        assert!(engine.remove_worktree(id, added).is_err());
        assert!(engine.worktree_entries(id, added, "").is_empty());
        assert!(engine.add_worktree(id + 100, other.path()).is_err());
    }

    /// Local settings come from the project's own folder first — the
    /// precedence Zed applies when several worktrees each have one.
    #[test]
    fn the_first_folders_settings_win() {
        let first = fixture();
        let second = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(second.path().join(".zed")).unwrap();
        std::fs::write(
            second.path().join(".zed/settings.json"),
            "{ \"tab_size\": 8 }",
        )
        .unwrap();

        let engine = Engine::new();
        let id = engine.open_project(first.path());
        wait_for_scan(&engine, id);
        engine.add_worktree(id, second.path()).unwrap();
        wait_for_scan(&engine, id);

        // Only the added folder has a file, so it is the one in effect.
        assert_eq!(
            engine.project_local_settings(id).unwrap().defaults.tab_size,
            Some(8)
        );

        // Give the project's own folder one and it takes over.
        std::fs::create_dir_all(first.path().join(".zed")).unwrap();
        std::fs::write(
            first.path().join(".zed/settings.json"),
            "{ \"tab_size\": 2 }",
        )
        .unwrap();
        assert!(engine.reload_project_settings(id));
        assert_eq!(
            engine.project_local_settings(id).unwrap().defaults.tab_size,
            Some(2)
        );
    }

    /// A folder that will not scan says so without stopping the project: the
    /// error is readable and the other folders still finish.
    #[test]
    fn a_folder_that_is_not_there_is_reported() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);
        let missing = engine
            .add_worktree(id, std::path::Path::new("/definitely/not/here"))
            .unwrap();

        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && engine.project_error(id).is_none() {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(engine.project_error(id).is_some());
        let folders = engine.project_worktrees(id);
        assert!(folders[0].error.is_none());
        assert!(folders[1].error.is_some());
        // Still scannable overall, so nothing waits on the folder for ever.
        assert!(engine.project_scan_complete(id));
        assert!(engine.remove_worktree(id, missing).is_ok());
        assert_eq!(engine.project_error(id), None);
    }

    #[test]
    fn closing_a_project_forgets_it() {
        let dir = fixture();
        let engine = Engine::new();
        let id = engine.open_project(dir.path());
        wait_for_scan(&engine, id);
        assert!(engine.close_project(id));
        assert!(!engine.close_project(id));
        assert_eq!(engine.project_version(id), 0);
        assert!(engine.project_entries(id, "").is_empty());
    }

    fn entry(name: &str, is_dir: bool) -> TreeEntry {
        TreeEntry {
            path: name.to_owned(),
            name: name.to_owned(),
            is_dir,
            is_ignored: false,
            is_hidden: false,
            is_unloaded: false,
            size: 0,
        }
    }

    fn names(entries: &[TreeEntry]) -> Vec<&str> {
        entries.iter().map(|entry| entry.name.as_str()).collect()
    }

    /// Zed's `project_panel.sort_mode`: the grouping changes, the name order
    /// inside a group never does.
    #[test]
    fn sort_mode_decides_the_grouping_only() {
        let original = vec![
            entry("Zebra.md", false),
            entry("src", true),
            entry("apple.rs", false),
            entry("Assets", true),
        ];

        let mut entries = original.clone();
        sort_entries(&mut entries, ProjectPanelSortMode::DirectoriesFirst);
        assert_eq!(names(&entries), ["Assets", "src", "apple.rs", "Zebra.md"]);

        let mut entries = original.clone();
        sort_entries(&mut entries, ProjectPanelSortMode::FilesFirst);
        assert_eq!(names(&entries), ["apple.rs", "Zebra.md", "Assets", "src"]);

        // Mixed is one list by name, case-insensitively — so `apple.rs` sits
        // between `Assets` and `src`, which is the whole point of the mode.
        let mut entries = original;
        sort_entries(&mut entries, ProjectPanelSortMode::Mixed);
        assert_eq!(names(&entries), ["apple.rs", "Assets", "src", "Zebra.md"]);
    }

    /// Two names that differ only in case must not swap between rebuilds —
    /// the case-insensitive compare ties, so the raw compare has to break it.
    #[test]
    fn names_differing_only_in_case_have_a_stable_order() {
        let mut entries = vec![entry("readme", false), entry("README", false)];
        sort_entries(&mut entries, ProjectPanelSortMode::DirectoriesFirst);
        assert_eq!(names(&entries), ["README", "readme"]);
        let mut reversed = vec![entry("README", false), entry("readme", false)];
        sort_entries(&mut reversed, ProjectPanelSortMode::DirectoriesFirst);
        assert_eq!(names(&reversed), ["README", "readme"]);
    }
}
