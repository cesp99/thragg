//! Search across every folder of a project.
//!
//! Unlike buffer search this is genuinely slow work — thousands of files read
//! off a phone's storage — so it does not answer on the caller's thread. It
//! runs on a thread of its own and publishes a generation counter the UI
//! polls, exactly the shape `git.rs` already uses and the project panel
//! already knows how to consume.
//!
//! That choice is worth spelling out, because the obvious alternative is a
//! blocking JNI call parked on a Kotlin coroutine. It would be *safe* — the
//! main thread would never see it — but it would also be worse in three ways
//! the polling shape gets for free: results appear as they are found instead
//! of all at once at the end; progress ("812 of 3400 files") is readable while
//! it runs; and cancelling is a flag the worker notices between files rather
//! than a coroutine cancellation that cannot actually stop native code.
//!
//! Which files exist, and which of them git ignores, is entirely the
//! worktree's answer — see `project.rs`. This module walks the same mirrored
//! snapshots the project panel draws, one per folder of the project and in
//! the project's own order, so the two can never disagree about what is in
//! the project. One consequence is worth knowing: Zed only scans an
//! ignored directory once it is expanded, so `include_ignored` reaches the
//! ignored files the panel can currently see, not the whole of `target/`.
//! Another: a search asked for before the scan finishes waits for it rather
//! than answering over half a tree — see [`Engine::start_project_search`].
//!
//! Four kinds of file never produce a result, and none of them is an error:
//! one that cannot be read, one bigger than [`MAX_FILE_BYTES`], one holding a
//! NUL byte anywhere, and one that is not valid UTF-8. That list is repeated
//! on the bridge, because a user who greps a 5 MB log or a Latin-1 file and
//! gets nothing deserves better than silence from the UI too.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use path::PathStyle;
use util::paths::PathMatcher;
use worktree::Snapshot;

use crate::EngineError;
use crate::project::{ProjectId, ProjectState, WorktreeHandle};
use crate::search::{SearchOptions, SearchQuery};

pub type SearchId = u64;

/// Caps, all three of them honest rather than silent. Zed stops at 5 000 files
/// and 10 000 ranges; these are lower because the results land in a panel on a
/// phone, where the thousandth file is not a result anyone is going to reach.
const MAX_RESULT_FILES: usize = 1_000;
const MAX_RESULT_MATCHES: usize = 5_000;
/// One minified bundle must not be allowed to eat the whole match budget.
const MAX_MATCHES_PER_FILE: usize = 500;

/// Files larger than this are skipped. Anything this big is generated, and
/// reading it costs more than the result is worth.
const MAX_FILE_BYTES: u64 = 4 * 1024 * 1024;

/// A result line longer than this is windowed around its match. Long lines are
/// generated code, and shipping a megabyte of one to the UI to draw forty
/// pixels of it is pure waste.
const MAX_LINE_BYTES: usize = 512;
/// How much of a windowed line to keep before the match, so the hit is not
/// flush against the left edge.
const CONTEXT_BEFORE_MATCH: usize = 64;

/// How often the worker publishes what it has. Fast enough to feel live, slow
/// enough that the results lock is not the bottleneck.
const PUBLISH_INTERVAL: Duration = Duration::from_millis(100);

/// How often a search started mid-scan looks to see whether the scan landed.
const SCAN_POLL_INTERVAL: Duration = Duration::from_millis(20);

/// How far a search has got.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SearchState {
    /// The project is still being scanned, so there is nothing to search yet.
    /// The search is alive and will start itself when the scan lands.
    Scanning,
    Running,
    Done,
    /// Superseded by a newer search on the same project, or cancelled
    /// outright. Also what an id the engine has forgotten reports.
    Cancelled,
}

/// One hit, shaped for a results panel rather than for an editor: it carries
/// the line it lives on, because the panel draws that line and never opens the
/// file.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct LineMatch {
    /// 1-based, for display. To put a cursor on it, open the file and ask for
    /// `point_to_offset(line - 1, column)`.
    pub line: u32,
    /// Byte column of the match within the *whole* line, which is what
    /// `point_to_offset` wants. Equal to `start` unless `text` was windowed.
    pub column: usize,
    /// Byte range of the match within `text`.
    pub start: usize,
    pub end: usize,
    /// The same range in UTF-16 code units, which is how Kotlin will index
    /// `text` when it highlights the hit.
    pub start_utf16: usize,
    pub end_utf16: usize,
    /// The line, windowed around the match if it was very long.
    pub text: String,
    /// `text` starts mid-line, so the UI should show an ellipsis.
    pub clipped_start: bool,
    /// Something was cut off the end: either `text` stops before the line does
    /// or the match itself ran on past this line. Either way, an ellipsis.
    pub clipped_end: bool,
}

/// Every hit in one file.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct FileMatches {
    /// Path relative to *its folder's* root, `/`-separated — the same
    /// spelling `TreeEntry::path` uses, so the UI can cross-reference a panel
    /// row.
    pub path: String,
    /// Which folder of the project the file is in.
    pub worktree: WorktreeHandle,
    /// That folder's display name, or empty when the project has only one
    /// folder and there is nothing to tell apart.
    pub worktree_name: String,
    /// Absolute path, so a caller does not have to know the folder's root to
    /// open the file. Two folders can hold the same relative path; this is
    /// what makes a result row unique.
    pub abs_path: String,
    /// The path to *open* the hit by — `Engine::project_entry_abs_path`'s
    /// spelling. Identical to `path` in the project's own folder.
    pub project_path: String,
    pub matches: Vec<LineMatch>,
    /// How many matches the file holds. Larger than `matches.len()` when the
    /// per-file cap bit.
    pub match_count: usize,
}

/// A snapshot of a search, from `from_file` onwards.
///
/// `files` is append-only for the life of a search, so the UI keeps what it
/// has and asks only for what is new.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct SearchResults {
    pub id: SearchId,
    pub state: SearchState,
    pub version: u64,
    /// Only ever set for a failure that stopped the search — a project that
    /// vanished, not a file that could not be read.
    pub error: Option<String>,
    pub files_searched: usize,
    /// Files the worktree offered to search, for a progress bar.
    pub total_files: usize,
    /// Files with at least one match, in all — not just in `files`.
    pub file_count: usize,
    /// Matches found in all, counted the same way `FileMatches::match_count`
    /// is: matches dropped by the per-file cap are in here too.
    pub match_count: usize,
    /// One of the caps bit, so this is not the whole truth.
    pub truncated: bool,
    /// The index `files[0]` sits at in the whole result list.
    pub from_file: usize,
    pub files: Vec<FileMatches>,
}

/// What a project-wide replacement did.
#[derive(Debug, Clone, Default, PartialEq, serde::Serialize)]
pub struct ProjectReplaceSummary {
    /// Files with at least one replacement.
    pub files: usize,
    /// Hits rewritten, across every file.
    pub replacements: usize,
    /// The open buffers among them, edited through the buffer path — their
    /// editors have to resync, and their tabs are now dirty.
    pub buffers: Vec<crate::BufferId>,
    /// Files that could not be rewritten, as "path: why". A file that
    /// vanished or turned read-only since the search is not the end of the
    /// operation, only of that file.
    pub errors: Vec<String>,
}

impl SearchResults {
    fn unknown(id: SearchId, from_file: usize) -> Self {
        Self {
            id,
            state: SearchState::Cancelled,
            version: 0,
            error: None,
            files_searched: 0,
            total_files: 0,
            file_count: 0,
            match_count: 0,
            truncated: false,
            from_file,
            files: Vec::new(),
        }
    }
}

/// What the worker writes and the poller reads.
#[derive(Default)]
struct Found {
    files: Vec<FileMatches>,
    version: u64,
    /// Waiting for the project's initial scan; nothing has been read yet.
    scanning: bool,
    finished: bool,
    error: Option<String>,
    files_searched: usize,
    total_files: usize,
    match_count: usize,
    truncated: bool,
}

struct Search {
    id: SearchId,
    cancelled: AtomicBool,
    found: Mutex<Found>,
}

impl Search {
    fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
}

/// At most one search per project: starting a new one supersedes the old,
/// which is what a search bar does anyway and what keeps worker threads from
/// accumulating behind a user who keeps typing.
#[derive(Default)]
pub(crate) struct ProjectSearches {
    searches: Mutex<HashMap<ProjectId, Arc<Search>>>,
    next_id: AtomicU64,
}

impl ProjectSearches {
    fn find(&self, id: SearchId) -> Option<Arc<Search>> {
        self.searches
            .lock()
            .unwrap()
            .values()
            .find(|search| search.id == id)
            .cloned()
    }

    /// Stop whatever is running for a project and forget it.
    pub(crate) fn cancel_project(&self, project: ProjectId) {
        if let Some(search) = self.searches.lock().unwrap().remove(&project) {
            search.cancelled.store(true, Ordering::Relaxed);
        }
    }
}

impl crate::Engine {
    /// Start searching a project. Returns an id to poll with; the search runs
    /// on its own thread and this call never waits for it.
    ///
    /// Any search already running for this project is cancelled, so the id
    /// returned here is the only live one for it.
    ///
    /// A project whose initial scan has not finished is *not* an error and is
    /// not searched early either: the search reports [`SearchState::Scanning`]
    /// and starts itself the moment the scan lands. Both alternatives are
    /// worse — refusing looks to the UI exactly like a bad query, and
    /// searching the half-scanned tree would answer "done, no results" over a
    /// subset of the project.
    pub fn start_project_search(
        &self,
        project: ProjectId,
        options: &SearchOptions,
    ) -> Result<SearchId, EngineError> {
        let query = SearchQuery::new(options).map_err(EngineError::InvalidQuery)?;
        let include = path_matcher(&options.include_globs).map_err(EngineError::InvalidQuery)?;
        let exclude = path_matcher(&options.exclude_globs).map_err(EngineError::InvalidQuery)?;

        let Some(state) = self.projects.lock().unwrap().get(&project).cloned() else {
            return Err(EngineError::UnknownProject(project));
        };

        let id = self.searches.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let search = Arc::new(Search {
            id,
            cancelled: AtomicBool::new(false),
            // Version 1 from birth, so "0" only ever means an id the engine
            // has forgotten — the UI polls this as its liveness signal.
            found: Mutex::new(Found {
                version: 1,
                ..Default::default()
            }),
        });
        if let Some(previous) = self
            .searches
            .searches
            .lock()
            .unwrap()
            .insert(project, search.clone())
        {
            previous.cancelled.store(true, Ordering::Relaxed);
        }

        // An empty query is a finished search with nothing in it, not an
        // error: the search bar hits this on every backspace to empty.
        let Some(query) = query else {
            let mut found = search.found.lock().unwrap();
            found.finished = true;
            found.version += 1;
            return Ok(id);
        };

        search.found.lock().unwrap().scanning = true;
        let include_ignored = options.include_ignored;
        let worker = search.clone();
        let spawned = thread::Builder::new()
            .name("seeker-project-search".to_owned())
            .spawn(move || {
                let Some(folders) = await_scan(&worker, &state) else {
                    return;
                };
                run(
                    &worker,
                    &folders,
                    &query,
                    &include,
                    &exclude,
                    include_ignored,
                )
            });
        if let Err(err) = spawned {
            let mut found = search.found.lock().unwrap();
            found.scanning = false;
            found.finished = true;
            found.error = Some(format!("could not start the search: {err}"));
            found.version += 1;
        }
        Ok(id)
    }

    /// Generation counter for a search, bumped whenever there is something new
    /// to read. 0 before the first results and for an id the engine has
    /// forgotten. Poll it the way the panel polls `project_version`.
    pub fn project_search_version(&self, id: SearchId) -> u64 {
        self.searches
            .find(id)
            .map(|search| search.found.lock().unwrap().version)
            .unwrap_or(0)
    }

    /// Everything found so far, from `from_file` onwards. Results only ever
    /// grow, so a caller that already holds `n` files passes `n` and gets what
    /// it is missing.
    pub fn project_search_results(&self, id: SearchId, from_file: usize) -> SearchResults {
        let Some(search) = self.searches.find(id) else {
            return SearchResults::unknown(id, from_file);
        };
        let found = search.found.lock().unwrap();
        let from_file = from_file.min(found.files.len());
        SearchResults {
            id,
            state: if search.is_cancelled() {
                SearchState::Cancelled
            } else if found.finished {
                SearchState::Done
            } else if found.scanning {
                SearchState::Scanning
            } else {
                SearchState::Running
            },
            version: found.version,
            error: found.error.clone(),
            files_searched: found.files_searched,
            total_files: found.total_files,
            file_count: found.files.len(),
            match_count: found.match_count,
            truncated: found.truncated,
            from_file,
            files: found.files[from_file..].to_vec(),
        }
    }

    /// Replace every hit of `options` with `replacement` in every file the
    /// project's last search found — Zed's `search::ReplaceAll` over the
    /// results (crates/search/src/project_search.rs:1384-1420).
    ///
    /// A file that is open goes through [`crate::Engine::replace_all`], so
    /// its editor sees the change and one undo takes it back; a file that
    /// is not is rewritten on disk, atomically, and that has no undo. The
    /// replacement is expanded per hit exactly as buffer replacement expands
    /// it; `options` should be the query the search ran with, because that
    /// is what the user was shown.
    ///
    /// The search must have finished: replacing over a list that is still
    /// growing would rewrite whatever happened to have arrived. A project
    /// with no search, or one still running, is `InvalidQuery`.
    ///
    /// **Blocking**: reads and writes every file in the list. Call it off
    /// the Android main thread.
    pub fn project_replace_all(
        &self,
        project: ProjectId,
        options: &SearchOptions,
        replacement: &str,
    ) -> Result<ProjectReplaceSummary, EngineError> {
        let query = SearchQuery::new(options).map_err(EngineError::InvalidQuery)?;
        if !self.projects.lock().unwrap().contains_key(&project) {
            return Err(EngineError::UnknownProject(project));
        }
        let Some(search) = self
            .searches
            .searches
            .lock()
            .unwrap()
            .get(&project)
            .cloned()
        else {
            return Err(EngineError::InvalidQuery(
                "nothing has been searched for".to_owned(),
            ));
        };
        let paths: Vec<(String, PathBuf)> = {
            let found = search.found.lock().unwrap();
            if !found.finished || search.is_cancelled() {
                return Err(EngineError::InvalidQuery(
                    "the search has not finished".to_owned(),
                ));
            }
            found
                .files
                .iter()
                .map(|file| (file.path.clone(), PathBuf::from(&file.abs_path)))
                .collect()
        };
        let Some(query) = query else {
            return Ok(ProjectReplaceSummary::default());
        };

        let mut summary = ProjectReplaceSummary::default();
        for (relative, path) in paths {
            // The same spelling `open_file` stores, so a file the user has
            // open is recognised as open rather than rewritten under its
            // buffer.
            let canonical = std::fs::canonicalize(&path).unwrap_or(path);
            let result = match self.buffer_for_path(&canonical) {
                Some(id) => self.replace_all(id, options, replacement).map(|outcome| {
                    if outcome.replaced > 0 {
                        summary.buffers.push(id);
                    }
                    outcome.replaced
                }),
                None => replace_in_file(&canonical, &query, replacement),
            };
            match result {
                Ok(0) => {}
                Ok(replaced) => {
                    summary.files += 1;
                    summary.replacements += replaced;
                }
                Err(err) => summary.errors.push(format!("{relative}: {err}")),
            }
        }
        Ok(summary)
    }

    /// Stop a search and forget it. False if the engine no longer knows the id.
    pub fn cancel_project_search(&self, id: SearchId) -> bool {
        let mut searches = self.searches.searches.lock().unwrap();
        let Some(project) = searches
            .iter()
            .find(|(_, search)| search.id == id)
            .map(|(project, _)| *project)
        else {
            return false;
        };
        if let Some(search) = searches.remove(&project) {
            search.cancelled.store(true, Ordering::Relaxed);
        }
        true
    }
}

/// Rewrite one file on disk. Skips — with `Ok(0)` — the same files the
/// search skips, which cannot have been in the results in the first place.
/// The text is taken as it is on disk, line endings included: a file is
/// written back byte-for-byte except for the hits, never re-normalised.
fn replace_in_file(
    path: &Path,
    query: &SearchQuery,
    replacement: &str,
) -> Result<usize, EngineError> {
    let bytes = std::fs::read(path).map_err(|err| crate::file::io_error(path, err))?;
    if bytes.contains(&0) {
        return Ok(0);
    }
    let Ok(text) = String::from_utf8(bytes) else {
        return Ok(0);
    };
    let (ranges, _) = query.matches_in(&text, usize::MAX);
    if ranges.is_empty() {
        return Ok(0);
    }
    let mut rewritten = String::with_capacity(text.len());
    let mut cursor = 0;
    for range in &ranges {
        rewritten.push_str(&text[cursor..range.start]);
        rewritten.push_str(&query.replacement_for(&text, range.clone(), replacement));
        cursor = range.end;
    }
    rewritten.push_str(&text[cursor..]);
    crate::file::write_atomically(path, rewritten.as_bytes())?;
    Ok(ranges.len())
}

fn path_matcher(globs: &[String]) -> Result<Option<PathMatcher>, String> {
    if globs.is_empty() {
        return Ok(None);
    }
    PathMatcher::new(globs, PathStyle::Unix)
        .map(Some)
        .map_err(|err| err.to_string())
}

/// Wait for the project's initial scan and hand back what to search, or
/// `None` if the search was cancelled or the project failed to open.
///
/// This polls rather than waiting on the runtime: the mirrored state has no
/// notification of its own (`project.rs` publishes a version counter, which is
/// what every other consumer polls), and a search that has not started yet is
/// not costing anybody a frame. It is the search *thread* that sleeps here,
/// never the caller.
fn await_scan(search: &Search, project: &Mutex<ProjectState>) -> Option<Vec<Folder>> {
    loop {
        if search.is_cancelled() {
            return None;
        }
        {
            let state = project.lock().unwrap();
            if let Some(error) = state.error() {
                // The project never opened, so there is no tree to walk and
                // waiting would be waiting forever. That is exactly the
                // failure `error` exists for.
                let mut found = search.found.lock().unwrap();
                found.scanning = false;
                found.finished = true;
                found.error = Some(error);
                found.version += 1;
                return None;
            }
            if state.scan_complete() {
                let named = state.worktrees.len() > 1;
                let folders: Vec<Folder> = state
                    .worktrees
                    .iter()
                    .enumerate()
                    .filter_map(|(index, tree)| {
                        Some(Folder {
                            handle: tree.handle,
                            name: if named { tree.name() } else { String::new() },
                            prefix: if index == 0 {
                                String::new()
                            } else {
                                format!("{}/", tree.name())
                            },
                            root: tree.root.clone(),
                            snapshot: tree.snapshot.clone()?,
                        })
                    })
                    .collect();
                drop(state);
                search.found.lock().unwrap().scanning = false;
                return Some(folders);
            }
        }
        thread::sleep(SCAN_POLL_INTERVAL);
    }
}

/// One folder of the project, as the search walks it.
struct Folder {
    handle: WorktreeHandle,
    /// Empty for a single-folder project — nothing to disambiguate.
    name: String,
    /// What goes in front of a path to make it a project path: empty for the
    /// folder the project was opened with, `<name>/` for any other.
    prefix: String,
    root: PathBuf,
    snapshot: Snapshot,
}

/// Walk the snapshot, search each file, publish as we go.
fn run(
    search: &Search,
    folders: &[Folder],
    query: &SearchQuery,
    include: &Option<PathMatcher>,
    exclude: &Option<PathMatcher>,
    include_ignored: bool,
) {
    {
        // Publish the denominator before reading a single file, so the UI has
        // a progress bar from the first poll rather than after the first
        // batch. Every folder counts towards it: a project search spans them
        // all, and a progress bar that only knew about the first would run to
        // 100% and keep going.
        let mut found = search.found.lock().unwrap();
        found.total_files = folders
            .iter()
            .map(|folder| {
                if include_ignored {
                    folder.snapshot.file_count()
                } else {
                    folder.snapshot.visible_file_count()
                }
            })
            .sum();
        found.version += 1;
    }

    let mut batch: Vec<FileMatches> = Vec::new();
    let mut searched = 0usize;
    let mut matches = 0usize;
    let mut files_with_matches = 0usize;
    let mut truncated = false;
    let mut last_publish = Instant::now();

    let publish = |batch: &mut Vec<FileMatches>, searched: usize, matches: usize, truncated| {
        let mut found = search.found.lock().unwrap();
        found.files.append(batch);
        found.files_searched = searched;
        found.match_count = matches;
        found.truncated = truncated;
        found.version += 1;
    };

    // The project's folders in their own order, so results read the way the
    // project panel is stacked.
    'folders: for folder in folders {
        for entry in folder.snapshot.files(include_ignored, 0) {
            if search.is_cancelled() {
                return;
            }
            searched += 1;

            let path = entry.path.as_unix_str();
            if include
                .as_ref()
                .is_some_and(|matcher| !matcher.is_match(entry.path.as_ref()))
                || exclude
                    .as_ref()
                    .is_some_and(|matcher| matcher.is_match(entry.path.as_ref()))
                || entry.size > MAX_FILE_BYTES
            {
                continue;
            }

            let absolute = folder.root.join(entry.path.as_std_path());
            if let Some(mut found) = search_file(&absolute, query, path) {
                found.worktree = folder.handle;
                found.worktree_name = folder.name.clone();
                found.abs_path = absolute.to_string_lossy().into_owned();
                found.project_path = format!("{}{path}", folder.prefix);
                // Every match the file holds, not just the ones that survived
                // the per-file cap, so this counter means the same thing as
                // the per-file one and a "5 000 results" header is not a lie.
                matches += found.match_count;
                files_with_matches += 1;
                batch.push(found);

                if files_with_matches >= MAX_RESULT_FILES || matches >= MAX_RESULT_MATCHES {
                    truncated = true;
                    publish(&mut batch, searched, matches, truncated);
                    break 'folders;
                }
            }

            if last_publish.elapsed() >= PUBLISH_INTERVAL {
                truncated |= batch
                    .iter()
                    .any(|file| file.matches.len() < file.match_count);
                publish(&mut batch, searched, matches, truncated);
                last_publish = Instant::now();
            }
        }
    }

    truncated |= batch
        .iter()
        .any(|file| file.matches.len() < file.match_count);
    publish(&mut batch, searched, matches, truncated);
    let mut found = search.found.lock().unwrap();
    found.finished = true;
    found.version += 1;
}

/// Read one file and collect its hits, or `None` when there is nothing to
/// report — no matches, or nothing searchable in the first place.
///
/// Three kinds of file are skipped in silence, all of them documented on the
/// bridge because a user will otherwise wonder where their hit went: one that
/// cannot be read (a permission error, or a file deleted a moment ago —
/// ordinary during a walk of a whole tree, and there is no useful place to put
/// a thousand of them), one holding a NUL byte, and one that is not UTF-8.
fn search_file(path: &Path, query: &SearchQuery, relative: &str) -> Option<FileMatches> {
    let bytes = std::fs::read(path).ok()?;
    // A NUL byte is the same binary test `grep` uses. It looks at the whole
    // file rather than a prefix: NUL is valid UTF-8, so a binary blob whose
    // first NUL sits past any sniff window would otherwise come back as a
    // "text" result and be drawn as one. Finding a byte is memchr-fast next
    // to the read that just happened.
    if bytes.contains(&0) {
        return None;
    }
    let mut text = String::from_utf8(bytes).ok()?;
    // The query was normalised when it was compiled, so a file with CRLF
    // endings has to be too or a multi-line query could never match it.
    text::LineEnding::normalize(&mut text);

    let (ranges, match_count) = query.matches_in(&text, MAX_MATCHES_PER_FILE);
    if ranges.is_empty() {
        return None;
    }

    let bytes = text.as_bytes();
    let mut line = 1u32;
    let mut line_start = 0usize;
    let mut cursor = 0usize;
    let matches = ranges
        .iter()
        .map(|range| {
            while cursor < range.start {
                if bytes[cursor] == b'\n' {
                    line += 1;
                    line_start = cursor + 1;
                }
                cursor += 1;
            }
            let line_end = text[line_start..]
                .find('\n')
                .map(|offset| line_start + offset)
                .unwrap_or(text.len());
            // A match may run past its line, either because the query spans
            // lines or because a regex crossed one. It is reported where it
            // starts, clipped to that line — a results panel draws one line —
            // and the clipping is flagged, or the panel would draw a highlight
            // ending flush at the edge with nothing to say it goes on.
            line_match(
                &text[line_start..line_end],
                line,
                range.start - line_start,
                range.end.min(line_end) - line_start,
                range.end > line_end,
            )
        })
        .collect();

    Some(FileMatches {
        path: relative.to_owned(),
        // Filled in by the caller, which is the one that knows the folder.
        worktree: 0,
        worktree_name: String::new(),
        abs_path: String::new(),
        project_path: String::new(),
        matches,
        match_count,
    })
}

/// Package one hit for display, windowing the line if it is long enough that
/// shipping all of it would be waste. `spans_lines` says the match itself
/// continued past this line, which the window cannot tell from the text alone.
fn line_match(line: &str, number: u32, start: usize, end: usize, spans_lines: bool) -> LineMatch {
    let (from, to) = if line.len() <= MAX_LINE_BYTES {
        (0, line.len())
    } else {
        let from = floor_char_boundary(line, start.saturating_sub(CONTEXT_BEFORE_MATCH));
        let to = floor_char_boundary(line, (from + MAX_LINE_BYTES).min(line.len()));
        (from, to)
    };
    let text = &line[from..to];
    let window_start = start - from;
    let window_end = end.min(to) - from;
    LineMatch {
        line: number,
        // `column` stays relative to the whole line, because that is what
        // `point_to_offset` needs to place a cursor; `start`/`end` are
        // relative to `text`, because that is what the panel draws.
        column: start,
        start: window_start,
        end: window_end,
        start_utf16: text[..window_start].chars().map(char::len_utf16).sum(),
        end_utf16: text[..window_end].chars().map(char::len_utf16).sum(),
        text: text.to_owned(),
        clipped_start: from > 0,
        clipped_end: to < line.len() || spans_lines,
    }
}

fn floor_char_boundary(text: &str, mut index: usize) -> usize {
    index = index.min(text.len());
    while !text.is_char_boundary(index) {
        index -= 1;
    }
    index
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;
    use std::time::{Duration, Instant};

    fn options(query: &str) -> SearchOptions {
        SearchOptions {
            query: query.to_owned(),
            ..Default::default()
        }
    }

    /// Block until a project has finished scanning, as `project.rs`'s own
    /// tests do — the worktree is genuinely concurrent.
    fn wait_for_scan(engine: &Engine, id: ProjectId) {
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !engine.project_scan_complete(id) {
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(
            engine.project_scan_complete(id),
            "project {id} never scanned"
        );
    }

    /// A project holding everything the search has to be careful about: an
    /// ignored directory, a binary file, and multi-byte text.
    fn project() -> (Engine, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("src")).unwrap();
        std::fs::create_dir_all(root.join(".git")).unwrap();
        std::fs::create_dir_all(root.join("target")).unwrap();
        std::fs::write(root.join(".gitignore"), "target\n").unwrap();
        std::fs::write(
            root.join("src/main.rs"),
            "fn main() {\n    let needle = 1;\n    println!(\"needle\");\n}\n",
        )
        .unwrap();
        std::fs::write(root.join("src/lib.rs"), "// no NEEDLE here, uppercase\n").unwrap();
        std::fs::write(root.join("src/unicode.rs"), "let héllo = \"needle\";\n").unwrap();
        std::fs::write(root.join("README.md"), "A needle in a haystack.\n").unwrap();
        std::fs::write(root.join("target/build.log"), "needle in the ignored dir\n").unwrap();
        std::fs::write(root.join("blob.bin"), b"needle\0\0\0needle").unwrap();

        let engine = Engine::new();
        let id = engine.open_project(root);
        wait_for_scan(&engine, id);
        (engine, dir)
    }

    /// Run a search to completion and hand back everything it found.
    #[track_caller]
    fn search(engine: &Engine, options: &SearchOptions) -> SearchResults {
        let id = engine.start_project_search(1, options).unwrap();
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let results = engine.project_search_results(id, 0);
            if !matches!(results.state, SearchState::Running | SearchState::Scanning) {
                return results;
            }
            assert!(Instant::now() < deadline, "the search never finished");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    fn paths(results: &SearchResults) -> Vec<&str> {
        results
            .files
            .iter()
            .map(|file| file.path.as_str())
            .collect()
    }

    #[test]
    fn finds_matches_across_the_worktree() {
        let (engine, _dir) = project();
        let results = search(&engine, &options("needle"));

        assert_eq!(results.state, SearchState::Done);
        assert_eq!(results.error, None);
        assert!(!results.truncated);
        // Case-insensitive by default, so lib.rs's NEEDLE counts. The ignored
        // directory and the binary file do not.
        assert_eq!(
            paths(&results),
            vec!["README.md", "src/lib.rs", "src/main.rs", "src/unicode.rs"]
        );
        assert_eq!(results.file_count, 4);
        assert_eq!(results.match_count, 5);
        assert_eq!(results.files_searched, results.total_files);

        let main = &results.files[2];
        assert_eq!(main.match_count, 2);
        let first = &main.matches[0];
        assert_eq!(first.line, 2);
        assert_eq!(first.text, "    let needle = 1;");
        assert_eq!(&first.text[first.start..first.end], "needle");
        assert_eq!(first.column, first.start);
        assert!(!first.clipped_start && !first.clipped_end);
    }


    /// A project search spans every folder, in the project's own order, and
    /// each hit says which folder it is in and where it is on disk.
    #[test]
    fn searches_every_folder_of_the_project() {
        let (engine, _dir) = project();
        let second = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(second.path().join("docs")).unwrap();
        std::fs::write(
            second.path().join("docs/guide.md"),
            "another needle, in the other folder\n",
        )
        .unwrap();
        std::fs::write(second.path().join("quiet.txt"), "nothing here\n").unwrap();
        let added = engine.add_worktree(1, second.path()).unwrap();
        wait_for_scan(&engine, 1);

        let results = search(&engine, &options("needle"));
        assert_eq!(results.state, SearchState::Done);
        // The project's own folder first, then the added one.
        assert_eq!(
            paths(&results),
            vec![
                "README.md",
                "src/lib.rs",
                "src/main.rs",
                "src/unicode.rs",
                "docs/guide.md",
            ]
        );
        let primary = engine.project_worktrees(1)[0].id;
        let guide = results.files.last().unwrap();
        assert_eq!(guide.worktree, added);
        assert_eq!(
            guide.abs_path,
            second
                .path()
                .join("docs/guide.md")
                .to_string_lossy()
                .into_owned()
        );
        // Both folders are named now that there is more than one to tell
        // apart, and the counters cover the whole project.
        assert!(results.files.iter().all(|file| !file.worktree_name.is_empty()));
        assert_eq!(results.files[0].worktree, primary);
        assert_eq!(results.file_count, 5);
        assert_eq!(results.files_searched, results.total_files);
        assert!(results.total_files >= 7);
    }

    #[test]
    fn honours_case_whole_word_and_regex() {
        let (engine, _dir) = project();

        let sensitive = search(
            &engine,
            &SearchOptions {
                case_sensitive: true,
                ..options("NEEDLE")
            },
        );
        assert_eq!(paths(&sensitive), vec!["src/lib.rs"]);

        // "haystack" is a word; "haystac" is only part of one.
        assert_eq!(
            paths(&search(&engine, &options("haystac"))),
            vec!["README.md"]
        );
        assert!(
            search(
                &engine,
                &SearchOptions {
                    whole_word: true,
                    ..options("haystac")
                }
            )
            .files
            .is_empty()
        );

        let regex = search(
            &engine,
            &SearchOptions {
                regex: true,
                ..options(r#"println!\("\w+"\)"#)
            },
        );
        assert_eq!(paths(&regex), vec!["src/main.rs"]);
    }

    #[test]
    fn line_ranges_are_utf8_safe_and_carry_utf16_offsets() {
        let (engine, _dir) = project();
        let results = search(&engine, &options("needle"));
        let unicode = results
            .files
            .iter()
            .find(|file| file.path == "src/unicode.rs")
            .expect("the unicode file matched");
        let found = &unicode.matches[0];

        // "héllo" is 6 bytes but 5 UTF-16 units, so the two offsets part
        // company — and both have to address the same text.
        assert_eq!(found.text, "let héllo = \"needle\";");
        assert_eq!(&found.text[found.start..found.end], "needle");
        assert_eq!(found.start, 14);
        assert_eq!(found.start_utf16, 13);
        assert_eq!(found.end_utf16, 19);
        let utf16: Vec<u16> = found.text.encode_utf16().collect();
        assert_eq!(
            String::from_utf16(&utf16[found.start_utf16..found.end_utf16]).unwrap(),
            "needle"
        );
    }

    #[test]
    fn include_and_exclude_globs() {
        let (engine, _dir) = project();

        let only_rust = search(
            &engine,
            &SearchOptions {
                include_globs: vec!["*.rs".to_owned()],
                ..options("needle")
            },
        );
        assert_eq!(
            paths(&only_rust),
            vec!["src/lib.rs", "src/main.rs", "src/unicode.rs"]
        );

        let not_src = search(
            &engine,
            &SearchOptions {
                exclude_globs: vec!["src/**".to_owned()],
                ..options("needle")
            },
        );
        assert_eq!(paths(&not_src), vec!["README.md"]);

        // Exclusion wins over inclusion.
        let both = search(
            &engine,
            &SearchOptions {
                include_globs: vec!["*.rs".to_owned()],
                exclude_globs: vec!["**/lib.rs".to_owned(), "**/unicode.rs".to_owned()],
                ..options("needle")
            },
        );
        assert_eq!(paths(&both), vec!["src/main.rs"]);

        assert!(matches!(
            engine.start_project_search(
                1,
                &SearchOptions {
                    include_globs: vec!["[".to_owned()],
                    ..options("needle")
                }
            ),
            Err(EngineError::InvalidQuery(_))
        ));
    }

    #[test]
    fn gitignored_files_are_searched_only_when_asked_for() {
        let (engine, _dir) = project();
        assert!(
            !paths(&search(&engine, &options("ignored dir"))).contains(&"target/build.log"),
            "the ignored directory must stay out by default"
        );

        // The worktree only scans an ignored directory once it is expanded, so
        // reaching into `target/` takes the step the project panel takes.
        assert!(engine.expand_directory(1, "target"));
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && engine.project_entries(1, "target").is_empty() {
            std::thread::sleep(Duration::from_millis(10));
        }
        let results = search(
            &engine,
            &SearchOptions {
                include_ignored: true,
                ..options("ignored dir")
            },
        );
        assert_eq!(paths(&results), vec!["target/build.log"]);
    }

    #[test]
    fn long_lines_are_windowed_around_the_match() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        let padding = "x".repeat(2000);
        std::fs::write(
            dir.path().join("bundle.js"),
            format!("{padding}needle{padding}\n"),
        )
        .unwrap();
        let engine = Engine::new();
        wait_for_scan(&engine, engine.open_project(dir.path()));

        let results = search(&engine, &options("needle"));
        let found = &results.files[0].matches[0];
        assert!(found.text.len() <= MAX_LINE_BYTES);
        assert_eq!(&found.text[found.start..found.end], "needle");
        assert!(found.clipped_start && found.clipped_end);
        // The column still addresses the real line, so the editor can jump.
        assert_eq!(found.column, 2000);
        assert_eq!(found.start, CONTEXT_BEFORE_MATCH);
    }

    #[test]
    fn per_file_matches_are_capped_and_the_count_stays_honest() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        std::fs::write(
            dir.path().join("many.txt"),
            "needle\n".repeat(MAX_MATCHES_PER_FILE + 50),
        )
        .unwrap();
        let engine = Engine::new();
        wait_for_scan(&engine, engine.open_project(dir.path()));

        let results = search(&engine, &options("needle"));
        let file = &results.files[0];
        assert_eq!(file.matches.len(), MAX_MATCHES_PER_FILE);
        assert_eq!(file.match_count, MAX_MATCHES_PER_FILE + 50);
        // The aggregate counts the same thing the per-file count does: every
        // match in the file, not the 500 that were kept.
        assert_eq!(results.match_count, MAX_MATCHES_PER_FILE + 50);
        assert!(
            results.truncated,
            "truncation has to be reported, not hidden"
        );
        // Lines are numbered from the file, not from the matches kept.
        assert_eq!(file.matches[0].line, 1);
        assert_eq!(
            file.matches[MAX_MATCHES_PER_FILE - 1].line,
            MAX_MATCHES_PER_FILE as u32
        );
    }

    #[test]
    fn results_can_be_read_incrementally() {
        let (engine, _dir) = project();
        let all = search(&engine, &options("needle"));

        let id = engine.start_project_search(1, &options("needle")).unwrap();
        let deadline = Instant::now() + Duration::from_secs(20);
        while matches!(
            engine.project_search_results(id, 0).state,
            SearchState::Running | SearchState::Scanning
        ) {
            assert!(Instant::now() < deadline, "the search never finished");
            std::thread::sleep(Duration::from_millis(5));
        }
        let tail = engine.project_search_results(id, 2);
        assert_eq!(tail.from_file, 2);
        assert_eq!(tail.file_count, all.file_count);
        assert_eq!(tail.files, all.files[2..]);
        // Asking past the end is empty rather than an error.
        assert!(engine.project_search_results(id, 99).files.is_empty());
        assert!(engine.project_search_version(id) > 0);
    }

    #[test]
    fn a_new_search_supersedes_the_one_before_it() {
        let (engine, _dir) = project();
        let first = engine.start_project_search(1, &options("needle")).unwrap();
        let second = engine
            .start_project_search(1, &options("haystack"))
            .unwrap();
        assert_ne!(first, second);
        assert_eq!(
            engine.project_search_results(first, 0).state,
            SearchState::Cancelled
        );
        assert_eq!(engine.project_search_version(first), 0);

        assert!(engine.cancel_project_search(second));
        assert!(!engine.cancel_project_search(second));
        assert_eq!(
            engine.project_search_results(second, 0).state,
            SearchState::Cancelled
        );
    }

    #[test]
    fn empty_queries_and_unknown_projects() {
        let (engine, _dir) = project();
        let empty = search(&engine, &options(""));
        assert_eq!(empty.state, SearchState::Done);
        assert!(empty.files.is_empty());

        assert_eq!(
            engine.start_project_search(999, &options("needle")),
            Err(EngineError::UnknownProject(999))
        );
        assert!(matches!(
            engine.start_project_search(
                1,
                &SearchOptions {
                    regex: true,
                    ..options("(")
                }
            ),
            Err(EngineError::InvalidQuery(_))
        ));
    }

    /// Opening a project and searching it in the same breath is what
    /// Ctrl+Shift+F on a cold repo does. It used to answer `UnknownProject`,
    /// which the bridge turns into the same -1 a bad query gets.
    #[test]
    fn a_search_started_before_the_scan_finishes_waits_for_the_whole_tree() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join("src")).unwrap();
        for name in ["a.txt", "b.txt", "src/c.txt"] {
            std::fs::write(root.join(name), "needle\n").unwrap();
        }

        let engine = Engine::new();
        let project = engine.open_project(root);
        let id = engine
            .start_project_search(project, &options("needle"))
            .expect("a project that is still scanning is not an unknown project");

        // Read the state before asking whether the scan landed: if the search
        // is not scanning, the scan must already have finished, and it cannot
        // un-finish.
        let first = engine.project_search_results(id, 0);
        if first.state != SearchState::Scanning {
            assert!(
                engine.project_scan_complete(project),
                "left Scanning before the scan completed, state {:?}",
                first.state
            );
        }
        assert!(first.files.is_empty(), "nothing is searched while scanning");
        assert!(
            engine.project_search_version(id) > 0,
            "a live search must never look like a forgotten one"
        );

        // Whatever it reported on the way, `done` is only ever said over the
        // whole tree.
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let results = engine.project_search_results(id, 0);
            match results.state {
                SearchState::Running | SearchState::Scanning => {
                    assert!(Instant::now() < deadline, "the search never finished");
                    std::thread::sleep(Duration::from_millis(5));
                }
                state => {
                    assert_eq!(state, SearchState::Done);
                    assert_eq!(results.error, None);
                    assert_eq!(paths(&results), vec!["a.txt", "b.txt", "src/c.txt"]);
                    assert_eq!(results.total_files, 3);
                    break;
                }
            }
        }
    }

    /// The other way out of the wait: a project that never opens at all must
    /// not leave a search parked forever.
    #[test]
    fn a_search_on_a_project_that_cannot_open_reports_the_failure() {
        let dir = tempfile::tempdir().unwrap();
        let missing = dir.path().join("not-here");
        let engine = Engine::new();
        let project = engine.open_project(&missing);
        let id = engine
            .start_project_search(project, &options("needle"))
            .unwrap();

        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            let results = engine.project_search_results(id, 0);
            if !matches!(results.state, SearchState::Running | SearchState::Scanning) {
                assert_eq!(results.state, SearchState::Done);
                assert!(
                    results
                        .error
                        .is_some_and(|error| error.contains("not-here")),
                    "the project's own failure is the search's failure"
                );
                break;
            }
            assert!(Instant::now() < deadline, "the search waited forever");
            std::thread::sleep(Duration::from_millis(10));
        }
    }

    #[test]
    fn a_match_that_runs_past_its_line_is_flagged_as_clipped() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        std::fs::write(dir.path().join("poem.txt"), "alpha\nbeta\ngamma\n").unwrap();
        let engine = Engine::new();
        wait_for_scan(&engine, engine.open_project(dir.path()));

        for query in [
            options("alpha\nbeta"),
            SearchOptions {
                regex: true,
                ..options("(?s)alpha.*gamma")
            },
        ] {
            let results = search(&engine, &query);
            let found = &results.files[0].matches[0];
            assert_eq!(found.line, 1);
            assert_eq!(found.text, "alpha");
            assert_eq!((found.start, found.end), (0, 5));
            assert!(
                found.clipped_end,
                "{:?} spans lines, so its highlight is cut short",
                query.query
            );
            assert!(!found.clipped_start);
        }
    }

    /// All three of the engine's silent skips, in one project so the scan is
    /// paid for once. Each is documented on the bridge; each is proven here.
    #[test]
    fn unreadably_large_binary_and_non_utf8_files_are_skipped() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        std::fs::create_dir_all(root.join(".git")).unwrap();
        // Bigger than the cap, with the needle at the very end.
        let mut huge = "x".repeat(MAX_FILE_BYTES as usize);
        huge.push_str("\nneedle\n");
        std::fs::write(root.join("huge.log"), huge).unwrap();
        // A NUL well past any prefix-sized sniff: still binary.
        let mut binary = b"x".repeat(20_000);
        binary.extend_from_slice(b"needle\0");
        std::fs::write(root.join("late.bin"), binary).unwrap();
        // Latin-1: the needle is plain ASCII, the file is not UTF-8.
        std::fs::write(root.join("latin1.txt"), b"needle caf\xe9\n").unwrap();
        // A control: something that is searchable.
        std::fs::write(root.join("ok.txt"), "needle\n").unwrap();

        let engine = Engine::new();
        wait_for_scan(&engine, engine.open_project(root));
        let results = search(&engine, &options("needle"));
        assert_eq!(paths(&results), vec!["ok.txt"]);
        // They were walked, just not searched — the progress bar still counts
        // them, which is why `files_searched` is 4 and `file_count` is 1.
        assert_eq!(results.files_searched, 4);
        assert_eq!(results.file_count, 1);
    }

    /// Replace-all over the results: an open buffer goes through the buffer
    /// path (undoable, dirty), a closed file is rewritten on disk, and files
    /// the search did not find are left alone.
    #[test]
    fn replace_all_edits_open_buffers_and_rewrites_closed_files() {
        let (engine, dir) = project();
        let main = engine.open_file(&dir.path().join("src/main.rs")).unwrap();
        let results = search(&engine, &options("needle"));
        assert_eq!(results.file_count, 4);

        let summary = engine
            .project_replace_all(1, &options("needle"), "pin")
            .unwrap();
        assert_eq!(summary.files, 4);
        assert_eq!(summary.replacements, 5);
        assert_eq!(summary.buffers, vec![main]);
        assert!(summary.errors.is_empty(), "{:?}", summary.errors);

        // The open buffer changed in memory, is dirty, and its file is not
        // yet — one undo puts both hits back.
        assert_eq!(
            engine.text(main).unwrap(),
            "fn main() {\n    let pin = 1;\n    println!(\"pin\");\n}\n"
        );
        assert!(engine.buffer_is_dirty(main));
        assert!(
            std::fs::read_to_string(dir.path().join("src/main.rs"))
                .unwrap()
                .contains("needle")
        );
        engine.undo(main).unwrap();
        assert!(engine.text(main).unwrap().contains("let needle"));

        // Closed files were rewritten on disk, case-insensitively as searched.
        assert_eq!(
            std::fs::read_to_string(dir.path().join("README.md")).unwrap(),
            "A pin in a haystack.\n"
        );
        assert_eq!(
            std::fs::read_to_string(dir.path().join("src/lib.rs")).unwrap(),
            "// no pin here, uppercase\n"
        );
        assert_eq!(
            std::fs::read_to_string(dir.path().join("src/unicode.rs")).unwrap(),
            "let héllo = \"pin\";\n"
        );
        // Neither the ignored file nor the binary one was in the results.
        assert!(
            std::fs::read_to_string(dir.path().join("target/build.log"))
                .unwrap()
                .contains("needle")
        );
        assert_eq!(
            std::fs::read(dir.path().join("blob.bin")).unwrap(),
            b"needle\0\0\0needle"
        );
    }

    #[test]
    fn replace_all_honours_word_case_and_regex_groups_on_disk() {
        let (engine, dir) = project();
        let strict = SearchOptions {
            regex: true,
            case_sensitive: true,
            whole_word: true,
            ..options(r"(nee)dle")
        };
        search(&engine, &strict);
        let summary = engine.project_replace_all(1, &strict, "$1d").unwrap();
        assert_eq!((summary.files, summary.replacements), (3, 4));
        assert_eq!(
            std::fs::read_to_string(dir.path().join("README.md")).unwrap(),
            "A need in a haystack.\n"
        );
        // Case-sensitive: the uppercase one stays.
        assert_eq!(
            std::fs::read_to_string(dir.path().join("src/lib.rs")).unwrap(),
            "// no NEEDLE here, uppercase\n"
        );
    }

    #[test]
    fn replace_all_needs_a_finished_search_and_reports_files_it_cannot_write() {
        let (engine, dir) = project();
        assert!(matches!(
            engine.project_replace_all(1, &options("needle"), "x"),
            Err(EngineError::InvalidQuery(_))
        ));
        assert_eq!(
            engine.project_replace_all(999, &options("needle"), "x"),
            Err(EngineError::UnknownProject(999))
        );

        search(&engine, &options("needle"));
        // A result whose file vanished between the search and the replace is
        // one error line, not a failed operation.
        std::fs::remove_file(dir.path().join("README.md")).unwrap();
        let summary = engine
            .project_replace_all(1, &options("needle"), "x")
            .unwrap();
        assert_eq!(summary.files, 3);
        assert_eq!(summary.errors.len(), 1);
        assert!(
            summary.errors[0].starts_with("README.md: "),
            "{:?}",
            summary.errors
        );

        // An empty query replaces nothing and is not an error.
        assert_eq!(
            engine.project_replace_all(1, &options(""), "x").unwrap(),
            ProjectReplaceSummary::default()
        );
    }

    #[test]
    fn closing_a_project_stops_its_search() {
        let (engine, _dir) = project();
        let id = engine.start_project_search(1, &options("needle")).unwrap();
        assert!(engine.close_project(1));
        assert_eq!(
            engine.project_search_results(id, 0).state,
            SearchState::Cancelled
        );
    }
}
