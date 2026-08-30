//! What one file's history says about it: the gutter's hunks, and blame.
//!
//! Zed's model is `crates/buffer_diff`, and the important half of it is copied
//! here: a hunk is the difference between the **buffer** and a base text, not
//! between two files on disk. `git diff` would have been one process and no
//! diff algorithm at all, and it would also have been wrong the moment anybody
//! typed — every hunk below the caret shifts by a line the instant a line is
//! added, and a gutter that only catches up on save is a gutter nobody trusts.
//!
//! So this module does what Zed does: fetch the base text once (`git show
//! HEAD:./file`, through the same guest seam as everything else), keep it, and
//! re-diff it against the live buffer whenever the buffer moves. The diff
//! itself is `imara-diff`, which is what Zed uses for the same job.
//!
//! Two counters decide when the cached answer is stale:
//!
//! * the **buffer version**, which moves on every edit and means "re-diff", and
//! * git's **generation** (git.rs), which moves when HEAD or the index might
//!   have — a commit, a checkout, a stage — and means "re-fetch the base".
//!
//! Neither is polled by this module. [`Engine::git_hunks_version`] is, exactly
//! the way `git_status_version` is, and polling it is what schedules the work;
//! it never waits for it.
//!
//! **Blame is the exception, and is honest about it.** `git blame` reads the
//! file *on disk*, because feeding the buffer to it needs a pipe into the guest
//! and [`guest::capture`] does not offer one. Its rows are therefore the saved
//! file's rows, and a dirty buffer's blame is a line or two out until it is
//! saved. Zed passes the buffer on stdin and does not have this problem; when
//! the guest seam grows a stdin, neither will we.

use std::collections::HashMap;
use std::ffi::OsString;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use imara_diff::{Algorithm, Diff, InternedInput};

use crate::guest::Userland;
use crate::{BufferId, Buffers, git};

/// How long the buffer rests before it is diffed. Long enough that a burst of
/// typing costs one diff rather than one per keystroke, short enough that the
/// gutter feels attached to what you just wrote.
const DEBOUNCE: Duration = Duration::from_millis(150);

/// How many times one refresh may immediately re-run because the buffer moved
/// again while it was working — the same cap, for the same reason, as
/// `git::MAX_CHAINED_RUNS`.
const MAX_CHAINED_RUNS: u32 = 4;

/// Beyond this, the two texts together are not gutter material: the diff is
/// seconds of work for a file nobody is reading a hunk marker in. The same
/// 4 MiB per side that project search refuses to read.
const MAX_DIFF_BYTES: usize = 8 * 1024 * 1024;

/// What happened to a run of rows, in the coordinates the editor draws in.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum HunkKind {
    /// Rows that are not in the base at all.
    Added,
    /// Rows that replaced other rows.
    Modified,
    /// Rows the base had and the buffer does not. The range is empty and marks
    /// the *boundary* they were removed from.
    Deleted,
}

/// One difference between the buffer and its base.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
pub struct Hunk {
    pub kind: HunkKind,
    /// First buffer row of the hunk, 0-based.
    pub start_row: u32,
    /// One past its last row. Equal to [`Hunk::start_row`] for a deletion,
    /// which occupies no rows.
    pub end_row: u32,
    /// How many rows the base had here. 0 for an addition; for a deletion, the
    /// number of rows that are gone.
    pub old_rows: u32,
    /// First row of those in the *base* text, 0-based — where the rows this
    /// hunk replaced (or removed) sat in the last commit. What an expanded
    /// hunk reads its deleted lines from, and what unstaging matches on.
    pub old_start: u32,
}

/// Who last touched a run of rows.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct BlameEntry {
    /// Full 40-character commit hash. All zeroes for lines that are not
    /// committed yet, which is git's own way of saying so.
    pub sha: String,
    /// First row of the run, 0-based, in the file *on disk* — see the module
    /// note about dirty buffers.
    pub start_row: u32,
    pub row_count: u32,
    pub author: String,
    /// Seconds since the Unix epoch, as git reports the author date. 0 when
    /// git said nothing — an uncommitted line.
    pub author_time: i64,
    /// The commit's subject line.
    pub summary: String,
}

/// Per-buffer diff state.
#[derive(Default)]
struct BufferDiff {
    /// Whether the file is inside a repository at all, and the git generation
    /// that was decided at.
    ///
    /// Answering it is an ancestor walk of `Path::exists` — one `stat` per
    /// directory up to the filesystem root — and the gutter polls this module
    /// from the main thread, once per open buffer, four times a second. So it
    /// is asked once per buffer per git generation and remembered, on the same
    /// staleness rule the base text below already follows: a `git init` moves
    /// the generation, and nothing else can make a file join a repository.
    in_repo: Option<bool>,
    in_repo_generation: u64,
    /// The file's content at HEAD. `Some("")` for a file HEAD has never seen,
    /// which is a base of no lines and therefore a wholly added file; `None`
    /// until we have managed to ask, and after an ask that failed.
    base: Option<Arc<String>>,
    /// The git generation the base was fetched at, and whether that fetch
    /// happened at all.
    base_generation: u64,
    base_fetched: bool,
    hunks: Arc<Vec<Hunk>>,
    /// The buffer version [`BufferDiff::hunks`] describes.
    diffed_version: u64,
    /// Whether a diff has ever completed, so that "no hunks" can be told from
    /// "not yet".
    diffed: bool,
    /// Bumped only when the hunks actually change.
    version: u64,
    running: bool,
}

#[derive(Default)]
pub(crate) struct BufferDiffs {
    buffers: Mutex<HashMap<BufferId, Arc<Mutex<BufferDiff>>>>,
}

impl crate::Engine {
    /// Generation counter for a buffer's diff hunks; 0 until there is
    /// something to show. Poll it the way the panel polls `git_status_version`
    /// — polling is also what schedules the work, and it never waits for it.
    pub fn git_hunks_version(&self, id: BufferId) -> u64 {
        self.refresh_hunks(id);
        self.with_diff(id, |diff| diff.version).unwrap_or(0)
    }

    /// The hunks, ascending by row. Reads a cache: it takes the buffer locks
    /// briefly, never runs git and never waits for one that is running. Empty
    /// for a buffer with no file, a file in no repository, and a file that
    /// matches HEAD.
    pub fn git_hunks(&self, id: BufferId) -> Vec<Hunk> {
        self.refresh_hunks(id);
        self.with_diff(id, |diff| (*diff.hunks).clone())
            .unwrap_or_default()
    }

    /// Start a diff if one is warranted and none is in flight.
    fn refresh_hunks(&self, id: BufferId) {
        // A buffer the engine has forgotten takes its base text with it. Swept
        // here rather than in `close_buffer` because nothing polls a closed
        // buffer's version, so this is the only moment anybody looks.
        {
            let live = self.buffers.read().unwrap();
            let mut diffs = self.diffs.buffers.lock().unwrap();
            // Not guarded on the two lengths matching: closing one buffer and
            // opening another leaves them equal with a dead entry inside, and a
            // dead entry is a whole file's text.
            diffs.retain(|id, _| live.contains_key(id));
        }

        let Some((path, version)) = self.diff_target(id) else {
            return;
        };
        let Some(userland) = self.userland() else {
            return;
        };
        let generation = git::generation();

        let cache = self
            .diffs
            .buffers
            .lock()
            .unwrap()
            .entry(id)
            .or_default()
            .clone();
        {
            let mut diff = cache.lock().unwrap();
            if diff.in_repo.is_none() || diff.in_repo_generation != generation {
                diff.in_repo = Some(
                    path.parent()
                        .is_some_and(|dir| git::repo_root_of(dir).is_some()),
                );
                diff.in_repo_generation = generation;
            }
            if diff.in_repo != Some(true) {
                return;
            }
            let base_is_current = diff.base_fetched && diff.base_generation == generation;
            let hunks_are_current = diff.diffed && diff.diffed_version == version;
            if diff.running || (base_is_current && hunks_are_current) {
                return;
            }
            diff.running = true;
        }

        let buffers = self.buffers.clone();
        let worker = cache.clone();
        let spawned = thread::Builder::new()
            .name("seeker-git-diff".to_owned())
            .spawn(move || diff_until_settled(id, &userland, &path, &buffers, &worker));
        if let Err(err) = spawned {
            // Leaving `running` set would wedge this buffer's gutter forever.
            log::debug!("buffer {id}: could not spawn a git diff thread: {err}");
            cache.lock().unwrap().running = false;
        }
    }

    /// The file behind a buffer and the buffer's version, or `None` for a
    /// scratch buffer, which has no file to diff against anything.
    ///
    /// Whether the file is in a repository is *not* asked here: that costs a
    /// walk of the filesystem, and this runs on the caller's thread on every
    /// poll. [`Engine::refresh_hunks`] asks it once and remembers.
    fn diff_target(&self, id: BufferId) -> Option<(PathBuf, u64)> {
        let version = self.version(id).ok()?;
        let path = self.buffer_path(id)?;
        Some((path, version))
    }

    fn with_diff<T>(&self, id: BufferId, f: impl FnOnce(&BufferDiff) -> T) -> Option<T> {
        let cache = self.diffs.buffers.lock().unwrap().get(&id).cloned()?;
        let diff = cache.lock().unwrap();
        Some(f(&diff))
    }

    /// The base text the gutter's hunks were diffed against, if it has been
    /// fetched for the current git generation. A cache read: never runs git.
    pub(crate) fn cached_base(&self, id: BufferId) -> Option<Arc<String>> {
        let generation = git::generation();
        self.with_diff(id, |diff| {
            (diff.base_fetched && diff.base_generation == generation)
                .then(|| diff.base.clone())
                .flatten()
        })
        .flatten()
    }

    /// The rows of the base text a hunk replaced — what an expanded hunk
    /// draws as its deleted lines (Zed's expanded `DiffHunk` block,
    /// editor/src/git.rs `expand_diff_hunk`). `old_start` and `old_rows` are
    /// the hunk's own fields; the answer is `None` while the base is still on
    /// its way, so the caller shows nothing rather than a guess.
    ///
    /// A cache read, like [`Engine::git_hunks`]: the base is already in
    /// memory, and slicing rows out of it is the whole cost.
    pub fn git_hunk_base_lines(
        &self,
        id: BufferId,
        old_start: u32,
        old_rows: u32,
    ) -> Option<Vec<String>> {
        let base = self.cached_base(id)?;
        Some(
            base.lines()
                .skip(old_start as usize)
                .take(old_rows as usize)
                .map(str::to_owned)
                .collect(),
        )
    }

    /// Who last touched each run of rows in a buffer's file.
    ///
    /// **Blocking**, and unlike the hunks it is not cached: blame is something
    /// the user asks for, one file at a time, and holding a copy per open
    /// buffer would spend memory on a question nobody asked twice.
    ///
    /// The rows are the *saved* file's rows — see the module note.
    pub fn git_blame(&self, id: BufferId) -> Result<Vec<BlameEntry>, String> {
        let path = self
            .buffer_path(id)
            .ok_or_else(|| "That buffer has no file".to_owned())?;
        let (dir, name) = split_path(&path).ok_or_else(|| "That buffer has no file".to_owned())?;
        let repo_root = git::repo_root_of(dir).ok_or_else(|| "Not a git repository".to_owned())?;
        let userland = self
            .userland()
            .filter(|userland| userland.is_installed())
            .ok_or_else(|| "The Linux userland is not installed".to_owned())?;

        let mut args: Vec<OsString> = ["blame", "--porcelain", "--"]
            .iter()
            .map(OsString::from)
            .collect();
        args.push(name.to_owned());
        let run = git::run_git(
            &userland,
            &repo_root,
            "git blame",
            git::git_argv(dir, &args),
        )?;
        if run.status != 0 {
            return Err(run.message());
        }
        Ok(parse_blame(&run.output))
    }
}

/// Fetch, diff, install — and go round again if the buffer moved while we were
/// working, so the hunks cannot end up describing text that has already
/// changed. `running` stays set for the whole loop.
fn diff_until_settled(
    id: BufferId,
    userland: &Userland,
    path: &Path,
    buffers: &Buffers,
    cache: &Arc<Mutex<BufferDiff>>,
) {
    for _ in 0..MAX_CHAINED_RUNS {
        thread::sleep(DEBOUNCE);

        let generation = git::generation();
        // Read the text *after* sleeping: everything typed during the debounce
        // is covered by the diff we are about to do.
        let Some((text, version)) = snapshot(buffers, id) else {
            break;
        };

        let base = {
            let cached = {
                let diff = cache.lock().unwrap();
                (diff.base_fetched && diff.base_generation == generation).then(|| diff.base.clone())
            };
            match cached {
                Some(base) => base,
                // Not under the lock: this is a process spawn inside proot, and
                // holding a buffer's diff state across it would block every
                // poll of this buffer's gutter for the duration.
                None => {
                    let fetched = base_text(userland, path);
                    let mut diff = cache.lock().unwrap();
                    diff.base = fetched.clone();
                    diff.base_generation = generation;
                    diff.base_fetched = true;
                    fetched
                }
            }
        };

        let hunks = match &base {
            Some(base) => hunks_between(base, &text),
            // No base and no way to get one — no userland, no git in the
            // guest. An empty gutter is the honest answer, not a green file.
            None => Vec::new(),
        };

        {
            let mut diff = cache.lock().unwrap();
            if *diff.hunks != hunks {
                diff.hunks = Arc::new(hunks);
                diff.version += 1;
            }
            diff.diffed = true;
            diff.diffed_version = version;
        }

        let moved = snapshot(buffers, id).is_none_or(|(_, now)| now != version);
        if !moved {
            break;
        }
    }
    cache.lock().unwrap().running = false;
}

/// The buffer's text and version, or `None` if it has been closed.
fn snapshot(buffers: &Buffers, id: BufferId) -> Option<(String, u64)> {
    let buffers = buffers.read().unwrap();
    let state = buffers.get(&id)?.lock().unwrap();
    Some((state.buffer.text(), state.version))
}

/// The file's content at HEAD.
///
/// `Some("")` for a path HEAD has never heard of — an untracked file, or any
/// file in a repository with no commits yet — because a base of no lines is
/// precisely what "every line here is new" means, and that is what the gutter
/// should show. `None` when git could not answer at all, where showing a wholly
/// added file would be inventing one.
fn base_text(userland: &Userland, path: &Path) -> Option<Arc<String>> {
    show_text(userland, path, "HEAD:./")
}

/// The file's content at HEAD, fetched now rather than read from the gutter's
/// cache — for the callers that stage and restore by *path*, which may have
/// no buffer and therefore no cache to read.
pub(crate) fn head_text(userland: &Userland, path: &Path) -> Option<Arc<String>> {
    base_text(userland, path)
}

/// The file's content in the **index** — what `git add` last wrote — or
/// `None` when the index has no entry for it (an untracked file, a staged
/// deletion) and when git could not answer. The two `None`s are one answer
/// on purpose: staging a hunk of a file the index does not hold creates the
/// entry either way.
pub(crate) fn index_text(userland: &Userland, path: &Path) -> Option<Arc<String>> {
    let (dir, name) = split_path(path)?;
    let repo_root = git::repo_root_of(dir)?;
    let mut revision = OsString::from(":./");
    revision.push(name);
    let args = vec![OsString::from("show"), revision];
    let run = git::run_git(userland, &repo_root, "git show", git::git_argv(dir, &args)).ok()?;
    (run.status == 0 && run.output.len() <= MAX_DIFF_BYTES).then(|| Arc::new(run.output))
}

/// `git show <prefix><name>` for a file — [`base_text`]'s body, kept apart so
/// the exit codes are read in one place.
fn show_text(userland: &Userland, path: &Path, revision_prefix: &str) -> Option<Arc<String>> {
    let (dir, name) = split_path(path)?;
    let repo_root = git::repo_root_of(dir)?;

    // `HEAD:./name` rather than a path from the repository root: git resolves
    // it against its own working directory, which `-C` has already set to the
    // file's, so there is no prefix arithmetic to get wrong.
    let mut revision = OsString::from(revision_prefix);
    revision.push(name);
    let args = vec![OsString::from("show"), revision];
    let run = git::run_git(userland, &repo_root, "git show", git::git_argv(dir, &args)).ok()?;
    match run.status {
        0 => {
            // A tracked binary file: there is nothing to draw hunks over, and
            // `from_utf8_lossy` would diff a mangled base against real text.
            if run.output.len() > MAX_DIFF_BYTES {
                return None;
            }
            Some(Arc::new(run.output))
        }
        // git's "no such path in HEAD", and its "invalid object name 'HEAD'"
        // for a repository whose first commit has not happened.
        128 => Some(Arc::new(String::new())),
        // Anything else is git failing, not answering: no git in the guest
        // (127), a repository it refused to open, a deadline.
        _ => {
            log::debug!("git show for {} exited with {}", path.display(), run.status);
            None
        }
    }
}

/// A file path as git wants it: the directory to run in, and the name to ask
/// about.
fn split_path(path: &Path) -> Option<(&Path, &std::ffi::OsStr)> {
    Some((path.parent()?, path.file_name()?))
}

/// The hunks between a base text and a buffer's text.
///
/// Lines rather than characters, and Histogram rather than Myers, because both
/// are what Zed diffs with (`language::text_diff`) — matching it means a hunk
/// lands where a Zed user expects it to.
pub(crate) fn hunks_between(base: &str, text: &str) -> Vec<Hunk> {
    if base.len() + text.len() > MAX_DIFF_BYTES {
        return Vec::new();
    }
    let mut input = InternedInput::default();
    input.update_before(base.lines());
    input.update_after(text.lines());
    let diff = Diff::compute(Algorithm::Histogram, &input);
    diff.hunks()
        .map(|hunk| Hunk {
            kind: if hunk.before.is_empty() {
                HunkKind::Added
            } else if hunk.after.is_empty() {
                HunkKind::Deleted
            } else {
                HunkKind::Modified
            },
            start_row: hunk.after.start,
            end_row: hunk.after.end,
            old_rows: hunk.before.end - hunk.before.start,
            old_start: hunk.before.start,
        })
        .collect()
}

/// Parse `git blame --porcelain`.
///
/// The format is a header line per run of rows — `<sha> <origline> <finalline>
/// <rows>` — followed by key/value lines, followed by the rows themselves, each
/// prefixed with a tab. The metadata is written **once per commit**, not once
/// per run, so a second run attributed to a commit already seen carries only
/// its header: the details have to be remembered per sha or every blame line
/// after the first says nothing.
fn parse_blame(output: &str) -> Vec<BlameEntry> {
    /// What we have learned about a commit, across every run it explains.
    #[derive(Default, Clone)]
    struct Commit {
        author: String,
        author_time: i64,
        summary: String,
    }

    let mut commits: HashMap<String, Commit> = HashMap::new();
    let mut entries: Vec<BlameEntry> = Vec::new();
    let mut current: Option<(String, u32, u32)> = None;

    for line in output.lines() {
        // The content of the blamed line itself. Nothing here needs it — the
        // editor already has the file — and it is the one line that can look
        // like anything, including a header.
        if line.starts_with('\t') {
            continue;
        }
        if let Some((key, value)) = line.split_once(' ') {
            let known = current
                .as_ref()
                .map(|(sha, _, _)| sha.clone())
                .filter(|_| matches!(key, "author" | "author-time" | "summary"));
            if let Some(sha) = known {
                let commit = commits.entry(sha).or_default();
                match key {
                    "author" => commit.author = value.to_owned(),
                    "author-time" => commit.author_time = value.trim().parse().unwrap_or(0),
                    "summary" => commit.summary = value.to_owned(),
                    _ => {}
                }
                continue;
            }
        }

        // A header, then: forty hex characters and three numbers.
        let mut parts = line.split(' ');
        let Some(sha) = parts.next().filter(|sha| is_sha(sha)) else {
            continue;
        };
        let (Some(_), Some(final_line), Some(rows)) = (
            parts.next().and_then(|part| part.parse::<u32>().ok()),
            parts.next().and_then(|part| part.parse::<u32>().ok()),
            // The row count is absent on the header of a *continuation* line,
            // which porcelain writes one of per row after the first.
            parts.next().and_then(|part| part.parse::<u32>().ok()),
        ) else {
            continue;
        };
        current = Some((sha.to_owned(), final_line.saturating_sub(1), rows));
        entries.push(BlameEntry {
            sha: sha.to_owned(),
            start_row: final_line.saturating_sub(1),
            row_count: rows,
            author: String::new(),
            author_time: 0,
            summary: String::new(),
        });
    }

    // Fill every entry from what the commit turned out to say. Done at the end
    // rather than as they arrive, because a commit's details can appear after
    // its first run — the first run is exactly where they appear.
    for entry in &mut entries {
        if let Some(commit) = commits.get(&entry.sha) {
            entry.author = commit.author.clone();
            entry.author_time = commit.author_time;
            entry.summary = commit.summary.clone();
        }
    }
    entries.sort_by_key(|entry| entry.start_row);
    entries
}

fn is_sha(text: &str) -> bool {
    text.len() == 40 && text.bytes().all(|byte| byte.is_ascii_hexdigit())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hunks(base: &str, text: &str) -> Vec<(HunkKind, u32, u32, u32)> {
        hunks_between(base, text)
            .into_iter()
            .map(|hunk| (hunk.kind, hunk.start_row, hunk.end_row, hunk.old_rows))
            .collect()
    }

    #[test]
    fn an_unchanged_file_has_no_hunks() {
        assert!(hunks("a\nb\nc\n", "a\nb\nc\n").is_empty());
        // A trailing newline is not a line, so its presence or absence is not
        // a hunk either. `str::lines` decides that, and it decides the same
        // way for both sides.
        assert!(hunks("a\nb\n", "a\nb").is_empty());
        assert!(hunks("", "").is_empty());
    }

    #[test]
    fn an_added_line_is_an_added_hunk_at_its_row() {
        // b is new, and it is row 1 of the buffer.
        assert_eq!(
            hunks("a\nc\n", "a\nb\nc\n"),
            vec![(HunkKind::Added, 1, 2, 0)]
        );
        // Two lines at the end.
        assert_eq!(hunks("a\n", "a\nb\nc\n"), vec![(HunkKind::Added, 1, 3, 0)]);
        // A wholly new file — the untracked case, where the base is empty.
        assert_eq!(hunks("", "a\nb\n"), vec![(HunkKind::Added, 0, 2, 0)]);
    }

    #[test]
    fn a_removed_line_is_an_empty_hunk_at_the_boundary() {
        // The gutter has no row to paint, so the range is empty and marks
        // where the missing lines were: between rows 0 and 1.
        assert_eq!(
            hunks("a\nb\nc\n", "a\nc\n"),
            vec![(HunkKind::Deleted, 1, 1, 1)]
        );
        // Everything gone.
        assert_eq!(hunks("a\nb\n", ""), vec![(HunkKind::Deleted, 0, 0, 2)]);
    }

    #[test]
    fn a_changed_line_is_a_modified_hunk() {
        assert_eq!(
            hunks("a\nb\nc\n", "a\nB\nc\n"),
            vec![(HunkKind::Modified, 1, 2, 1)]
        );
        // Three rows replaced by one: the range is the buffer's, `old_rows`
        // is what it replaced.
        assert_eq!(
            hunks("a\nb\nc\nd\n", "a\nX\n"),
            vec![(HunkKind::Modified, 1, 2, 3)]
        );
    }

    /// `old_start` is the hunk's row in the *base*, which is what the deleted
    /// lines are read from: after an insertion above it the buffer row and
    /// the base row of the same change drift apart.
    #[test]
    fn a_hunk_remembers_where_its_old_rows_sat_in_the_base() {
        let hunks = hunks_between("a\nb\nc\n", "x\na\nB\nc\n");
        assert_eq!(hunks.len(), 2);
        assert_eq!((hunks[0].start_row, hunks[0].old_start), (0, 0));
        // `B` is buffer row 2 but replaced base row 1.
        assert_eq!(
            (hunks[1].start_row, hunks[1].old_start, hunks[1].old_rows),
            (2, 1, 1)
        );
    }

    #[test]
    fn base_lines_of_a_hunk_come_from_the_cache_only() {
        let engine = crate::Engine::new();
        let id = engine.create_buffer("a\n");
        // No base has been fetched for a scratch buffer — and none ever will.
        assert_eq!(engine.git_hunk_base_lines(id, 0, 1), None);
    }

    #[test]
    fn hunks_are_ascending_and_independent() {
        assert_eq!(
            hunks("a\nb\nc\nd\ne\n", "a\nB\nc\nd\ne\nf\n"),
            vec![(HunkKind::Modified, 1, 2, 1), (HunkKind::Added, 5, 6, 0)]
        );
    }

    #[test]
    fn a_file_too_big_to_diff_reports_nothing_rather_than_stalling() {
        let big = "x\n".repeat(MAX_DIFF_BYTES);
        assert!(hunks_between(&big, "x\n").is_empty());
    }

    /// The porcelain shape, including the trap: the second run of the same
    /// commit carries no author at all.
    #[test]
    fn blame_carries_a_commits_details_to_every_run_of_it() {
        let sha = "0123456789abcdef0123456789abcdef01234567";
        let other = "89abcdef0123456789abcdef0123456789abcdef";
        let output = format!(
            "{sha} 1 1 2\n\
             author Ada Lovelace\n\
             author-mail <ada@example.invalid>\n\
             author-time 1700000000\n\
             author-tz +0000\n\
             summary the first commit\n\
             filename src/main.rs\n\
             \tfirst line\n\
             {sha} 2 2\n\
             \tsecond line\n\
             {other} 9 3 1\n\
             author Grace Hopper\n\
             author-time 1800000000\n\
             summary a later change\n\
             filename src/main.rs\n\
             \tthird line\n\
             {sha} 4 4 1\n\
             filename src/main.rs\n\
             \tfourth line\n"
        );
        let entries = parse_blame(&output);
        assert_eq!(entries.len(), 3);

        assert_eq!(entries[0].start_row, 0);
        assert_eq!(entries[0].row_count, 2);
        assert_eq!(entries[0].author, "Ada Lovelace");
        assert_eq!(entries[0].author_time, 1_700_000_000);
        assert_eq!(entries[0].summary, "the first commit");

        assert_eq!(entries[1].start_row, 2);
        assert_eq!(entries[1].author, "Grace Hopper");

        // The run that repeated the sha and said nothing else still knows who
        // wrote it — the whole reason the details are remembered per commit.
        assert_eq!(entries[2].start_row, 3);
        assert_eq!(entries[2].author, "Ada Lovelace");
        assert_eq!(entries[2].summary, "the first commit");
    }

    #[test]
    fn blame_of_an_uncommitted_line_is_reported_as_git_reports_it() {
        let zeroes = "0".repeat(40);
        let output = format!(
            "{zeroes} 1 1 1\n\
             author Not Committed Yet\n\
             author-time 1700000001\n\
             summary Version of src/main.rs from src/main.rs\n\
             filename src/main.rs\n\
             \tnew line\n"
        );
        let entries = parse_blame(&output);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].sha, zeroes);
        assert_eq!(entries[0].author, "Not Committed Yet");
    }

    /// A blamed line whose *content* looks like a header must not become one.
    #[test]
    fn a_blamed_line_is_never_read_as_a_record() {
        let sha = "0123456789abcdef0123456789abcdef01234567";
        let fake = "ffffffffffffffffffffffffffffffffffffffff";
        let output = format!(
            "{sha} 1 1 1\n\
             author Ada Lovelace\n\
             author-time 1700000000\n\
             summary only one commit\n\
             filename src/main.rs\n\
             \t{fake} 1 1 1\n"
        );
        let entries = parse_blame(&output);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].sha, sha);
    }

    #[test]
    fn blame_of_a_buffer_without_a_file_says_so() {
        let engine = crate::Engine::new();
        let id = engine.create_buffer("scratch");
        assert_eq!(
            engine.git_blame(id),
            Err("That buffer has no file".to_owned())
        );
    }

    #[test]
    fn hunks_are_empty_without_a_userland() {
        let engine = crate::Engine::new();
        let id = engine.create_buffer("a\nb\n");
        assert_eq!(engine.git_hunks_version(id), 0);
        assert!(engine.git_hunks(id).is_empty());
    }

    /// Both entry points are documented as cache reads the gutter may poll from
    /// the main thread. Asking "is this file in a repository?" is an ancestor
    /// walk of `stat`s, and doing it per call made that documentation false —
    /// fifteen open tabs at 250 ms is hundreds of syscalls a second on the UI
    /// thread. It is asked once per buffer per git generation instead.
    #[test]
    fn polling_the_gutter_does_not_walk_the_filesystem_every_time() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        let file = dir.path().join("a.rs");
        std::fs::write(&file, "a\nb\n").unwrap();

        let engine = crate::Engine::new();
        // A userland it can never reach: the diff thread is spawned and fails,
        // which is beside the point — what is measured is the caller's thread.
        engine.set_userland(
            &dir.path().join("no-such-proot"),
            dir.path(),
            dir.path(),
            dir.path(),
        );
        let id = engine.open_file(&file).unwrap();

        let walked = || git::REPO_ROOT_WALKS.with(std::cell::Cell::get);
        let before = walked();
        for _ in 0..20 {
            engine.git_hunks_version(id);
            engine.git_hunks(id);
        }
        let walks = walked() - before;
        assert!(
            walks <= 1,
            "40 polls should walk the tree once, not {walks} times"
        );
    }
}
