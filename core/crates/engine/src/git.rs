//! Git status, computed by the `git` inside the Debian userland.
//!
//! There is no git library here on purpose. Zed's vendored `git` crate shells
//! out to a `git` binary, and ours lives inside the proot guest — Android will
//! not execute anything that arrived after install, so the only reachable git
//! is the one `apt` put in the rootfs, and the only way to reach it is through
//! proot (agent-docs/archive/research/proot-spike.md, "Open items", item 4).
//!
//! Getting *into* the guest is not this module's job — `guest.rs` owns the
//! proot command line, and git's own share of it is the argv below and two
//! environment variables. What is worth knowing here is that guest.rs binds
//! every host path onto the identical guest path, so the `-C` going in and the
//! paths coming back are the same strings on both sides of the boundary, with
//! nothing to translate.
//!
//! Two more things shape this module.
//!
//! **Nothing waits on git.** A status run is a process spawn inside an
//! emulated filesystem: tens of milliseconds at best, seconds on a cold cache.
//! So [`Engine::git_status`] only ever reads a cache, and
//! [`Engine::git_status_version`] is the generation counter the UI polls —
//! deliberately the same shape as `project_version` in `project.rs`, so the
//! project panel watches two counters with one mechanism.
//!
//! **Silence when there is no userland.** The `play` flavour has no guest at
//! all, and the `full` flavour has none until the user installs one. Both must
//! look like "this repository has no changes", not like an error: every failure
//! path here logs at debug and yields an empty map.
//!
//! **Queries are silent; commands are not.** Everything above is a query, and a
//! query that fails shows the user nothing. The four operations at the bottom
//! of this file — stage, unstage, discard, commit — are the opposite: they are
//! the user's own act, they block until git has finished, and when git refuses
//! ("Please tell me who you are") the message is what they need to read. That
//! is why they go through [`run_git`] rather than [`capture`], which reports
//! only that something went wrong.

use std::collections::{BTreeMap, HashMap};
use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use crate::guest::{self, GuestCommand, Userland};
use crate::project::ProjectId;

/// How long to wait after a worktree change before asking git. A save, a
/// branch switch or a `cargo build` all produce bursts of file events; running
/// once at the end of the burst is the whole point.
const DEBOUNCE: Duration = Duration::from_millis(400);

/// A status run that takes longer than this is assumed wedged and killed.
/// Generous on purpose: the first proot spawn after boot pays for page cache
/// misses across the whole rootfs.
const RUN_TIMEOUT: Duration = Duration::from_secs(20);

/// How many times one refresh may immediately re-run because the worktree
/// moved again while it was running. See [`run_until_settled`].
/// How long a run that could not reach git is believed before it is retried.
const FAILED_RUN_RETRY: Duration = Duration::from_secs(5);

const MAX_CHAINED_RUNS: u32 = 4;

/// What happened to one path, as the project panel needs to colour it.
///
/// Deliberately smaller than git's own vocabulary: the panel paints a row, it
/// does not explain a diff. Typechanges and copies fold into `Modified` and
/// `Renamed` respectively.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum GitStatus {
    Modified,
    Added,
    Deleted,
    Renamed,
    Conflicted,
    Untracked,
    /// Only ever produced if `--ignored` is passed, which it is not — see
    /// [`status_args`]. Kept so the mapping is complete rather than lossy.
    Ignored,
}

/// One changed path, with both of git's status letters kept.
///
/// The rolled-up [`GitStatus`] is what a project-panel row needs; the git panel
/// needs the letters themselves, because "staged" and "unstaged" are exactly
/// the distinction they carry and nothing downstream can recover it once the
/// pair has been reduced to one enum.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct FileChange {
    /// Repository-relative while parsing; project-relative once
    /// [`parse_porcelain`] has re-based it.
    ///
    /// A record for an untracked *directory* keeps its trailing `/` — that is
    /// how `--untracked-files=normal` collapses a whole new directory into one
    /// line, and dropping the slash would turn it into a file that is not there.
    pub path: String,
    /// Where an `R` or `C` record's file came from: porcelain's *second*
    /// record, the one git writes immediately after. `None` for everything
    /// else — and for a rename whose source lies outside the project, which
    /// this cannot name and therefore cannot act on.
    pub original: Option<String>,
    /// The index against HEAD.
    pub x: u8,
    /// The worktree against the index.
    pub y: u8,
}

impl FileChange {
    fn status(&self) -> GitStatus {
        classify(self.x, self.y)
    }

    fn is_conflicted(&self) -> bool {
        self.status() == GitStatus::Conflicted
    }

    /// What is staged for the next commit, or `None` when nothing is.
    ///
    /// An untracked file has nothing staged even though its `X` is `?`, and a
    /// conflict is not a staged change either — it is a decision the user has
    /// not made yet, which is why it gets a section of its own in the panel.
    fn staged(&self) -> Option<GitStatus> {
        if self.is_conflicted() || self.x == b'?' || self.x == b'!' || self.x == b' ' {
            return None;
        }
        Some(letter(self.x))
    }

    /// What is changed in the worktree and not staged.
    fn unstaged(&self) -> Option<GitStatus> {
        if self.is_conflicted() {
            return None;
        }
        if self.x == b'?' || self.y == b'?' {
            return Some(GitStatus::Untracked);
        }
        if self.y == b' ' || self.y == b'!' {
            return None;
        }
        Some(letter(self.y))
    }

    /// Whether the last commit has a version of **this** path to restore.
    ///
    /// `A` is "added to the index", `?` is "git has never seen it", and the
    /// destination of an `R` or a `C` is a name HEAD has never held either —
    /// the *source* is the one it knows. In all three cases HEAD holds nothing
    /// under this path, and `git restore --source=HEAD` on it does not refuse:
    /// it removes the path from the index and the worktree and exits 0. So
    /// discarding cannot mean `git restore` here — see [`discard_steps`].
    fn in_head(&self) -> bool {
        !matches!(self.x, b'A' | b'?' | b'R' | b'C')
    }
}

/// One porcelain letter to a status. The pair is [`classify`]'s job; this is
/// for the panel, which shows each side of the pair separately.
fn letter(code: u8) -> GitStatus {
    match code {
        b'A' => GitStatus::Added,
        b'D' => GitStatus::Deleted,
        b'R' | b'C' => GitStatus::Renamed,
        b'?' => GitStatus::Untracked,
        b'!' => GitStatus::Ignored,
        b'U' => GitStatus::Conflicted,
        _ => GitStatus::Modified,
    }
}

/// Which branch the repository is on, and how far it has drifted from its
/// upstream. From the `## ` record `--branch` adds to the porcelain output, so
/// it costs no second git run.
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize)]
pub struct BranchInfo {
    /// `None` on a detached HEAD, which has no branch to name.
    pub name: Option<String>,
    /// Commits the branch has that its upstream does not, and the reverse.
    /// Both 0 when there is no upstream to compare against.
    pub ahead: u32,
    pub behind: u32,
    /// The branch exists but has no commits yet — a repository just created.
    pub unborn: bool,
    /// The upstream it tracks, `origin/main`-style, or `None` for a branch
    /// that has never been pushed — the difference between "push" and Zed's
    /// "Publish".
    pub upstream: Option<String>,
    /// The upstream is configured but its ref is gone — git's `[gone]`,
    /// usually a branch deleted on the remote. Zed's `UpstreamTracking::Gone`:
    /// the remote button reads "Republish", and a push re-creates the branch
    /// with `--set-upstream` (git_ui.rs:822-829, git_panel.rs:3920-3929).
    pub upstream_gone: bool,
}

/// Everything the git panel draws, as one snapshot.
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct GitChanges {
    /// A status run has completed. Until it has, an empty list means "not
    /// asked yet" rather than "nothing changed", and the panel says so.
    pub scanned: bool,
    /// A `git status` actually ran and was parsed. False means git could not
    /// be run at all — no userland, or no git inside it — and *not* that the
    /// tree is clean, which is what an empty change list on its own says.
    pub ran: bool,
    /// The project is inside a git repository at all.
    pub has_repo: bool,
    pub branch: Option<BranchInfo>,
    /// The commit HEAD names — see [`read_head`]. A staleness key for views of
    /// the commit *graph*: a history pane that reloads on every status change
    /// re-runs `git log` on every save, when what it actually depends on is
    /// this. `None` when it is not known, which a caller must treat as
    /// "reload", never as "nothing changed".
    pub head: Option<String>,
    pub entries: Vec<ChangedFile>,
}

/// One row of the git panel.
#[derive(Debug, Clone, serde::Serialize)]
pub struct ChangedFile {
    /// Project-relative, `/`-separated — the same spelling `TreeEntry::path`
    /// uses, so the panel can open it through the project it already holds.
    ///
    /// Ends in `/` when git collapsed a whole new directory into one record.
    /// The panel has to be able to say so: the row is a folder, and discarding
    /// it trashes everything under it.
    pub path: String,
    /// What this file was called in the last commit, when it has been renamed
    /// or copied. The panel names both halves, and discarding is the one
    /// operation that needs the old name to do anything at all.
    pub original: Option<String>,
    /// What is staged for the next commit; `None` when nothing of this file is.
    pub staged: Option<GitStatus>,
    /// What is changed and not staged.
    pub unstaged: Option<GitStatus>,
    pub conflicted: bool,
    /// HEAD has a version of **this path** — so discarding restores it rather
    /// than throwing it away. False for the destination of a rename, whose old
    /// name is the one HEAD holds. See [`discard_steps`].
    pub in_head: bool,
}

/// Cached status for one project.
#[derive(Default)]
pub(crate) struct ProjectGit {
    statuses: Arc<BTreeMap<String, GitStatus>>,
    /// The same run's output, unreduced, for the git panel.
    pub(crate) changes: Arc<Vec<FileChange>>,
    /// Read by the remote commands too: a push's refspec names the upstream's
    /// branch, which this already knows.
    pub(crate) branch: Option<BranchInfo>,
    /// The commit HEAD named when the run finished — see [`read_head`].
    head: Option<String>,
    /// Where the enclosing repository is, once a run has looked. `None` after
    /// a completed run means the project is not in a repository.
    repo_root: Option<PathBuf>,
    /// Bumped when anything above actually changed — and when a run completed
    /// that a reader could have seen pending (`scanned` was false, or `ran`
    /// flipped), so a poll loop that sees a steady number can skip the JNI
    /// read entirely and still never sits on a transient "asking git" state.
    version: u64,
    /// A run has completed at least once (successfully or not).
    scanned: bool,
    /// Whether the last run actually ran git. See [`GitChanges::ran`].
    ran: bool,
    /// When the last run finished, for the retry above.
    last_run: Option<Instant>,
    /// The worktree version the last completed run observed.
    scanned_worktree_version: u64,
    /// A run is in flight. Never more than one per project: git is the
    /// expensive part, and two concurrent runs would race to install results.
    running: bool,
}

#[derive(Default)]
pub(crate) struct GitStatuses {
    projects: Mutex<HashMap<ProjectId, Arc<Mutex<ProjectGit>>>>,
}

/// Bumped whenever anything git owns might have moved: a status run that found
/// a change, and every write below.
///
/// It exists for the diff gutter (git_diff.rs), whose base text comes from HEAD
/// and is therefore stale only when HEAD or the index moves — which is
/// precisely what this reports.
///
/// Process-global rather than a field, because the threads that bump it outlive
/// any borrow of the engine and because it is deliberately coarse: a bump from
/// one project costs another project's gutter one redundant `git show`, and
/// nothing else. A test that builds two engines shares it, with the same
/// harmless consequence.
static GENERATION: AtomicU64 = AtomicU64::new(0);

/// HEAD or the index moved: every gutter's base is suspect. `pub(crate)` for
/// the one caller that changes the index without a project id to hand
/// [`crate::Engine::git_state_changed`] — a hunk staged from a buffer.
pub(crate) fn bump_generation() {
    GENERATION.fetch_add(1, Ordering::Relaxed);
}

/// The current value, for the diff gutter's staleness check.
pub(crate) fn generation() -> u64 {
    GENERATION.load(Ordering::Relaxed)
}

#[cfg(test)]
thread_local! {
    /// How many ancestor walks [`repo_root_of`] has done **on this thread**.
    ///
    /// Test-only, and it exists because "this call is cheap enough for the main
    /// thread" is a claim about a syscall count that no other assertion can
    /// see. Per-thread so that the suite's own parallelism, and the diff
    /// worker's legitimate walks, cannot be mistaken for the caller's.
    pub(crate) static REPO_ROOT_WALKS: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
}

impl crate::Engine {
    /// Generation counter for a project's git status. Bumped when the statuses
    /// change; 0 until the first run produces something. Poll it the way the
    /// panel already polls `project_version`.
    ///
    /// Polling it is also what *drives* refreshing: this call notices that the
    /// worktree moved and schedules a run. It never waits for one.
    pub fn git_status_version(&self, id: ProjectId) -> u64 {
        self.refresh_git_status(id);
        self.with_git(id, |git| git.version).unwrap_or(0)
    }

    /// Every path with a status, plus every ancestor directory of one — see
    /// [`roll_up`]. Paths are project-relative and `/`-separated, matching
    /// `TreeEntry::path`, so the panel can look up a row directly.
    ///
    /// Reads the cache and returns immediately. Empty when there is no
    /// userland, no repository, or no git inside the guest.
    pub fn git_status(&self, id: ProjectId) -> BTreeMap<String, GitStatus> {
        self.refresh_git_status(id);
        self.with_git(id, |git| (*git.statuses).clone())
            .unwrap_or_default()
    }

    /// The same run's output as the git panel needs it: the branch, and every
    /// changed file with its staged and unstaged halves kept apart.
    ///
    /// Reads the same cache [`Engine::git_status`] does and schedules the same
    /// refresh, so a panel and a project tree looking at one project share one
    /// `git status` between them and one counter to poll.
    pub fn git_changes(&self, id: ProjectId) -> GitChanges {
        self.refresh_git_status(id);
        self.with_git(id, |git| GitChanges {
            scanned: git.scanned,
            ran: git.ran,
            has_repo: git.repo_root.is_some(),
            branch: git.branch.clone(),
            head: git.head.clone(),
            entries: git
                .changes
                .iter()
                .map(|change| ChangedFile {
                    path: change.path.clone(),
                    original: change.original.clone(),
                    staged: change.staged(),
                    unstaged: change.unstaged(),
                    conflicted: change.is_conflicted(),
                    in_head: change.in_head(),
                })
                .collect(),
        })
        .unwrap_or_default()
    }

    /// The branch the project is on, from the same cache — and nothing else.
    ///
    /// A title bar that only names the branch has no business paying for
    /// [`Engine::git_changes`], which clones and serializes every changed
    /// file. The whole [`BranchInfo`] rather than the name alone, because the
    /// same cheap read feeds the title bar's ↑↓ drift arrows and the reload
    /// keys of the history views, which go stale when a fetch or push moves
    /// the upstream without touching HEAD. `None` when nothing is known: no
    /// repository, or no completed run yet. A detached HEAD is `Some` with a
    /// `name` of `None` — still a repository, just on no branch — which a
    /// caller must keep distinct from `None`.
    pub fn git_branch(&self, id: ProjectId) -> Option<BranchInfo> {
        self.refresh_git_status(id);
        self.with_git(id, |git| git.branch.clone()).flatten()
    }

    /// The commit HEAD names, from the same cache — the staleness key for the
    /// commit graph, without the rest of [`Engine::git_changes`]. `None` when
    /// it is not known; see [`GitChanges::head`] for what a caller must make
    /// of that.
    pub fn git_head(&self, id: ProjectId) -> Option<String> {
        self.refresh_git_status(id);
        self.with_git(id, |git| git.head.clone()).flatten()
    }

    /// Start a run if one is warranted and none is in flight.
    fn refresh_git_status(&self, id: ProjectId) {
        // A project the engine no longer knows takes its cache with it, so a
        // long session of opening and closing projects doesn't accumulate.
        let Some(project) = self.projects.lock().unwrap().get(&id).cloned() else {
            self.git.projects.lock().unwrap().remove(&id);
            return;
        };
        let Some(userland) = self.userland() else {
            return;
        };

        let (root, worktree_version) = {
            let state = project.lock().unwrap();
            (state.root(), state.version)
        };
        // Nothing mirrored yet: the panel has nothing to colour, and the scan
        // landing will bump the version and bring us back here.
        if worktree_version == 0 {
            return;
        }

        let cache = self
            .git
            .projects
            .lock()
            .unwrap()
            .entry(id)
            .or_default()
            .clone();
        {
            let mut git = cache.lock().unwrap();
            // A run that could not reach git is not an answer worth keeping:
            // the userland can gain git while the app is open — `apt install
            // git` in the terminal two lines below the panel — and a cache
            // that only refreshes when the *worktree* changes would go on
            // saying "could not run git" over a repository it can now read.
            // Retried on a timer rather than every poll, because the failure
            // costs a proot spawn.
            let failed_run_is_stale = !git.ran
                && git
                    .last_run
                    .is_none_or(|at| at.elapsed() >= FAILED_RUN_RETRY);
            if git.running
                || (git.scanned
                    && git.scanned_worktree_version == worktree_version
                    && !failed_run_is_stale)
            {
                return;
            }
            git.running = true;
        }

        let worker_cache = cache.clone();
        let spawned = thread::Builder::new()
            .name("seeker-git-status".to_owned())
            .spawn(move || run_until_settled(id, &userland, &root, &project, &worker_cache));
        if let Err(err) = spawned {
            // Leaving `running` set would wedge this project forever.
            log::debug!("project {id}: could not spawn a git status thread: {err}");
            cache.lock().unwrap().running = false;
        }
    }

    pub(crate) fn with_git<T>(&self, id: ProjectId, f: impl FnOnce(&ProjectGit) -> T) -> Option<T> {
        let cache = self.git.projects.lock().unwrap().get(&id).cloned()?;
        let git = cache.lock().unwrap();
        Some(f(&git))
    }
}

/// Debounce, run, install — and go round again if the worktree moved while we
/// were running, so the cache cannot end up describing a state that is already
/// stale. `running` stays set for the whole loop, which is what enforces "never
/// more than one run in flight per project".
///
/// The chain is capped rather than unbounded: something that writes files
/// continuously — a build inside the terminal — would otherwise keep this
/// thread spawning git forever, including after the user has navigated away
/// and nobody is polling. Giving up re-arms it on the next poll, so the only
/// cost of the cap is a slightly later refresh in exactly the case where the
/// answer was going to be stale anyway.
fn run_until_settled(
    id: ProjectId,
    userland: &Userland,
    root: &Path,
    project: &Arc<Mutex<crate::project::ProjectState>>,
    cache: &Arc<Mutex<ProjectGit>>,
) {
    for _ in 0..MAX_CHAINED_RUNS {
        thread::sleep(DEBOUNCE);

        // Read the version *after* sleeping: everything that happened during
        // the debounce is covered by the run we are about to do.
        let observed = project.lock().unwrap().version;
        let outcome = status_for(id, userland, root);

        {
            let mut git = cache.lock().unwrap();
            // Compared on the unreduced changes rather than the rolled-up map:
            // staging a file moves its letters without moving its colour, and
            // a panel watching this counter has to see that.
            let content_changed = *git.changes != outcome.changes
                || git.branch != outcome.branch
                || git.head != outcome.head
                || git.repo_root != outcome.repo_root;
            if content_changed {
                git.statuses = Arc::new(outcome.statuses);
                git.changes = Arc::new(outcome.changes);
                git.branch = outcome.branch;
                git.head = outcome.head;
                git.repo_root = outcome.repo_root;
                bump_generation();
            }
            // The version moves for a flag flip too, not only for content: a
            // mutation calls [`Engine::git_state_changed`], which drops
            // `scanned`, and whoever reads the state right then renders
            // "Asking git…". When this run's snapshot turns out *identical* —
            // a push that failed, a commit git refused — a version pegged to
            // content would never tell the poll loop that the answer came
            // back, and the panel sat on that transient forever. Same for
            // `ran`: git becoming reachable (or not) changes what the panel
            // says without changing a single path. The generation above stays
            // content-only — the diff gutter's base text cannot go stale on a
            // no-op.
            if content_changed || !git.scanned || git.ran != outcome.ran {
                git.version += 1;
            }
            git.scanned = true;
            git.ran = outcome.ran;
            git.last_run = Some(Instant::now());
            git.scanned_worktree_version = observed;
        }

        let now = project.lock().unwrap().version;
        if now == observed {
            cache.lock().unwrap().running = false;
            return;
        }
    }
    log::debug!("project {id}: git status still chasing a moving worktree; pausing");
    cache.lock().unwrap().running = false;
}

/// What one status run learned. Every field is "nothing" when git could not be
/// asked, which is not an error and is never shown to the user — except
/// [`RunOutcome::repo_root`], which is answered by the host filesystem and is
/// therefore known even with no guest to run git in.
#[derive(Default)]
struct RunOutcome {
    repo_root: Option<PathBuf>,
    changes: Vec<FileChange>,
    branch: Option<BranchInfo>,
    /// Answered by the host filesystem, like `repo_root` — see [`read_head`].
    head: Option<String>,
    statuses: BTreeMap<String, GitStatus>,
    /// git ran and its output was parsed. See [`GitChanges::ran`].
    ran: bool,
}

/// One status run.
fn status_for(id: ProjectId, userland: &Userland, root: &Path) -> RunOutcome {
    // Cheapest gate first, and it needs no guest at all: a handful of `stat`
    // calls up the host filesystem. A project that isn't in a repository never
    // pays for a proot spawn.
    let Some(repo_root) = repo_root_of(root) else {
        return RunOutcome::default();
    };
    let outcome = RunOutcome {
        repo_root: Some(repo_root.clone()),
        head: read_head(&repo_root),
        ..RunOutcome::default()
    };
    let Some(prefix) = relative_prefix(&repo_root, root) else {
        return outcome;
    };
    if !userland.is_installed() {
        return outcome;
    }

    let started = Instant::now();
    let Some(output) = capture(userland, &repo_root, root) else {
        return outcome;
    };
    let changes = rebase(parse_changes(&output), &prefix);
    let statuses = roll_up(&statuses_of(&changes));
    log::debug!(
        "project {id}: git status took {:?}, {} paths",
        started.elapsed(),
        changes.len()
    );
    RunOutcome {
        branch: parse_branch(&output),
        changes,
        statuses,
        ran: true,
        ..outcome
    }
}

/// `git status` arguments, minus the `-C`.
///
/// `--porcelain=v1 -z` is the only machine format that is both stable and
/// unambiguous: `-z` means NUL-separated records with **raw** path bytes, so
/// `core.quotePath` never applies and there is no C-style unquoting to get
/// wrong (see the tests).
///
/// Keeping this query from writing `index.lock` is `--no-optional-locks`,
/// which is *git's* option and not `git status`'s: passed after the
/// subcommand, real git exits 129 with "unknown option", every run produces
/// nothing, and the panel is silently colourless. It is first in the list
/// below for that reason, and only the *queries* pass it: a `git add` needs the
/// index lock it is refusing, and asking for it back is not this flag's job.
///
/// `--ignored` is *not* passed. It would list every file under `target/` and
/// `node_modules/`, which is the opposite of cheap, and the worktree already
/// knows what is ignored (`TreeEntry::is_ignored`) from the same `.gitignore`
/// files.
///
/// `--branch` costs nothing — one extra `## ` record at the head of the output —
/// and is the only way to learn the branch and its drift without a second
/// process spawn, which on this filesystem is the expensive part.
fn status_args() -> [&'static str; 6] {
    [
        // Read-only query: don't refresh the index, don't write index.lock. The
        // panel polls this, so a lock here would fight the user's own git.
        // Git-level, so before the subcommand — see the doc above.
        "--no-optional-locks",
        "status",
        "--porcelain=v1",
        "-z",
        "--branch",
        // Not the default everywhere: `status.showUntrackedFiles` can turn it
        // off, and a repository configured that way would silently show no new
        // files at all.
        "--untracked-files=normal",
    ]
}

/// Everything from `git` onwards, in order: the git-level options first, then
/// the subcommand and its own.
///
/// Assembled in one place so the host tests can run the real git binary over
/// the very argv the device uses. That is the only thing that catches a
/// git-level option written after the subcommand: git exits 129 with "unknown
/// option" while every parser test in this file still passes.
pub(crate) fn git_argv<S: AsRef<OsStr>>(project: &Path, args: &[S]) -> Vec<OsString> {
    let mut argv: Vec<OsString> = vec![OsString::from("git")];
    argv.push(OsString::from("-C"));
    argv.push(project.as_os_str().to_owned());
    // Belt and braces with proot's fake_id0, and harmless if the "dubious
    // ownership" check would have passed anyway. Supported since git 2.35.3;
    // Debian stable is well past that.
    argv.extend(["-c", "safe.directory=*"].map(OsString::from));
    // A file called `*.rs` is a file, not a glob, and a file called `-f` is not
    // an option. Every path this module hands git comes from the user's own
    // worktree, so both are reachable — and pathspec magic would silently touch
    // the wrong files.
    argv.push(OsString::from("--literal-pathspecs"));
    argv.extend(args.iter().map(|arg| arg.as_ref().to_owned()));
    argv
}

/// Run git inside the guest and return its stdout.
///
/// The proot half of this — the flags, the binds, the guest environment, the
/// deadline and the pipe draining — belongs to [`guest::capture`]. What is
/// git's own is the argv, a bind for a repository that lives outside the
/// projects directory, and these two variables.
fn capture(userland: &Userland, repo_root: &Path, project: &Path) -> Option<Vec<u8>> {
    let argv = git_argv(project, &status_args());
    guest::capture(
        userland,
        &git_command("git status", repo_root, argv),
        RUN_TIMEOUT,
    )
}

/// git's half of the guest command: the argv, the bind for a repository that
/// lives outside the projects directory, and two variables.
pub(crate) fn git_command(label: &str, repo_root: &Path, argv: Vec<OsString>) -> GuestCommand {
    GuestCommand::new(label.to_owned(), argv)
        .bind(repo_root)
        // `--no-optional-locks` in its environment form, which git(1) gives as
        // equivalent; unlike the flag it is inherited, so a git that runs
        // another git still writes no lock. Set for the writes below as well,
        // where it costs nothing: what it suppresses is the *optional* locking,
        // and a `git add` takes the index lock because it must.
        .env("GIT_OPTIONAL_LOCKS", "0")
        // There is nobody on this end to answer a credential prompt, and a git
        // waiting for one would sit there until the deadline killed it.
        .env("GIT_TERMINAL_PROMPT", "0")
}

/// The enclosing repository's root, or `None` if there isn't one.
///
/// `.git` is a directory in a normal clone and a *file* in a worktree or
/// submodule, so this only asks whether the name exists. A few `stat` calls,
/// no subprocess: this is what makes "not a repository" free.
///
/// Free is not free enough to do per poll, though — a file deep in a tree is a
/// `stat` per level, and a file in *no* repository walks to the filesystem
/// root. Every caller here either runs on a worker thread or remembers the
/// answer, which is what [`REPO_ROOT_WALKS`] exists to hold callers to.
pub(crate) fn repo_root_of(project: &Path) -> Option<PathBuf> {
    #[cfg(test)]
    REPO_ROOT_WALKS.with(|walks| walks.set(walks.get() + 1));
    let mut dir = Some(project);
    while let Some(candidate) = dir {
        if candidate.join(".git").exists() {
            return Some(candidate.to_path_buf());
        }
        dir = candidate.parent();
    }
    None
}

/// The commit HEAD names, read from the repository's own files on the host.
///
/// Why not ask git: the `--branch` header of porcelain v1 carries no commit
/// id, and a `rev-parse` of its own would be a second proot spawn per refresh
/// — the process spawn being the expensive part of everything this module
/// does. `HEAD` plus one ref file is a handful of host reads, done on the same
/// worker thread as the status run.
///
/// The answer is only ever a *staleness key* — "has the commit graph moved?" —
/// so a layout this cannot resolve degrades to `None`, which callers must
/// read as "unknown, assume moved", never as a wrong-but-confident id. An
/// unborn branch is `None` too: there is no commit to name yet, and the first
/// commit changes the answer, which is exactly what a key must do.
pub(crate) fn read_head(repo_root: &Path) -> Option<String> {
    let dot_git = repo_root.join(".git");
    // A worktree or submodule has a `.git` *file* naming the real git dir,
    // relative to the directory holding it.
    let git_dir = if dot_git.is_file() {
        let text = std::fs::read_to_string(&dot_git).ok()?;
        let target = Path::new(text.strip_prefix("gitdir:")?.trim());
        if target.is_absolute() {
            target.to_path_buf()
        } else {
            repo_root.join(target)
        }
    } else {
        dot_git
    };
    let head = std::fs::read_to_string(git_dir.join("HEAD")).ok()?;
    let head = head.trim();
    // A detached HEAD holds the commit id itself.
    let Some(refname) = head.strip_prefix("ref:").map(str::trim) else {
        return Some(head.to_owned()).filter(|head| !head.is_empty());
    };
    // A linked worktree has a HEAD of its own but shares its refs through
    // `commondir`, which names the main repository's git dir.
    let common = match std::fs::read_to_string(git_dir.join("commondir")) {
        Ok(text) => {
            let target = Path::new(text.trim());
            if target.is_absolute() {
                target.to_path_buf()
            } else {
                git_dir.join(target)
            }
        }
        Err(_) => git_dir,
    };
    // A loose ref is a file holding the id; a packed one is a line in
    // `packed-refs`. Loose first, because git itself resolves in that order —
    // a ref that is both was moved after packing, and the loose file is the
    // truth.
    if let Ok(id) = std::fs::read_to_string(common.join(refname)) {
        return Some(id.trim().to_owned()).filter(|id| !id.is_empty());
    }
    let packed = std::fs::read_to_string(common.join("packed-refs")).ok()?;
    packed
        .lines()
        // `#` opens the header line, `^` a peeled-tag line; neither is a ref.
        .filter(|line| !line.starts_with(['#', '^']))
        .find_map(|line| {
            let (id, name) = line.split_once(' ')?;
            (name.trim() == refname).then(|| id.to_owned())
        })
}

/// Where the project sits inside its repository, as a `/`-terminated prefix
/// (empty when the project *is* the repository root).
///
/// Porcelain paths are always relative to the repository root, not to `-C`, so
/// a project that is a subdirectory of a bigger repository needs this to turn
/// them back into project-relative paths.
pub(crate) fn relative_prefix(repo_root: &Path, project: &Path) -> Option<String> {
    let relative = project.strip_prefix(repo_root).ok()?;
    let relative = relative.to_string_lossy();
    if relative.is_empty() {
        Some(String::new())
    } else {
        Some(format!("{}/", relative.trim_end_matches('/')))
    }
}

/// Parse `git status --porcelain=v1 -z` output into project-relative paths,
/// with directories rolled up (see [`roll_up`]).
///
/// Only the tests reach for this now: a real run keeps the unreduced changes
/// too, so [`status_for`] builds both halves from the one parse.
#[cfg(test)]
pub(crate) fn parse_porcelain(output: &[u8], strip_prefix: &str) -> BTreeMap<String, GitStatus> {
    let changes = rebase(parse_changes(output), strip_prefix);
    roll_up(&statuses_of(&changes))
}

/// Drop everything outside the project and re-base what is left on its root.
///
/// Porcelain paths are relative to the *repository* root, so a project that is
/// a subdirectory of a bigger repository sees paths that mean nothing to it —
/// and files outside it that are none of its business.
fn rebase(mut changes: Vec<FileChange>, strip_prefix: &str) -> Vec<FileChange> {
    if strip_prefix.is_empty() {
        return changes;
    }
    changes.retain(|change| change.path.starts_with(strip_prefix));
    for change in &mut changes {
        change.path = change.path[strip_prefix.len()..].to_owned();
        // A rename *into* the project from outside it keeps its status row and
        // loses its source: the source is not a path this project can name, so
        // there is nothing here that could restore it. `None` is what
        // [`discard_steps`] refuses on, which is the right answer for a file
        // whose other half we cannot reach.
        change.original = change
            .original
            .as_ref()
            .filter(|original| original.starts_with(strip_prefix))
            .map(|original| original[strip_prefix.len()..].to_owned());
    }
    changes
}

fn statuses_of(changes: &[FileChange]) -> Vec<(String, GitStatus)> {
    changes
        .iter()
        .map(|change| (change.path.clone(), change.status()))
        .collect()
}

/// The reduced form: one `(path, status)` per changed file, in git's order.
#[cfg(test)]
pub(crate) fn parse_records(output: &[u8]) -> Vec<(String, GitStatus)> {
    statuses_of(&parse_changes(output))
}

/// The records themselves: one entry per changed file, in git's order, with
/// both status letters kept.
///
/// Each record is `XY<space><path>`, NUL-terminated. A rename or copy emits
/// **two** paths — the new one in its own record, the original in the record
/// immediately after — so the loop is index-based rather than a plain
/// iterator: it has to consume that second record itself, or the old path
/// would be read back as a garbled status line.
///
/// `--branch` puts one more record in front of all of them, `## <branch>…`,
/// which [`parse_branch`] reads and this skips. It has to be skipped
/// explicitly: `#` and `#` are two characters followed by a space, so the
/// shape test below would let it through as a change to a file called `main`.
pub(crate) fn parse_changes(output: &[u8]) -> Vec<FileChange> {
    let records: Vec<&[u8]> = output
        .split(|byte| *byte == 0)
        .filter(|record| !record.is_empty())
        .collect();

    let mut out = Vec::new();
    let mut index = 0;
    while index < records.len() {
        let record = records[index];
        index += 1;
        if record.starts_with(BRANCH_HEADER) {
            continue;
        }
        // "XY path": two code letters, a space, and at least one path byte.
        if record.len() < 4 || record[2] != b' ' {
            continue;
        }
        let x = record[0];
        let y = record[1];

        // Paths arrive as raw bytes. They are UTF-8 in every case we can
        // create, but a repository cloned from elsewhere can hold anything, and
        // a status query is not the place to fail over it.
        let path = String::from_utf8_lossy(&record[3..]).into_owned();

        // Whether a second (source) record follows is a property of the raw
        // X byte, not of the classified status: git emits `RD` and `CD` for a
        // rename whose destination was then deleted, and those classify as
        // Deleted. Deciding from the status desynchronises the whole parse and
        // invents a path — covered by the RD/CD tests below.
        let mut original = None;
        if x == b'R' || x == b'C' {
            // The source path. It is *not* reported as a change of its own —
            // the file moved, and painting its old location would be showing
            // the user something that no longer exists — but it is kept,
            // because it is the only name HEAD knows this file by and
            // discarding a rename is impossible without it.
            original = records
                .get(index)
                .map(|record| String::from_utf8_lossy(record).into_owned());
            index += 1;
        }
        out.push(FileChange {
            path,
            original,
            x,
            y,
        });
    }
    out
}

/// What `--branch` prefixes the output with.
const BRANCH_HEADER: &[u8] = b"## ";

/// The branch, from that header record.
///
/// git writes it in four shapes, and all four are here: `## main`, `##
/// main...origin/main [ahead 1, behind 2]`, `## No commits yet on main`, and
/// `## HEAD (no branch)` for a detached head.
pub(crate) fn parse_branch(output: &[u8]) -> Option<BranchInfo> {
    let record = output
        .split(|byte| *byte == 0)
        .find(|record| record.starts_with(BRANCH_HEADER))?;
    let text = String::from_utf8_lossy(&record[BRANCH_HEADER.len()..]).into_owned();

    if text.starts_with("HEAD (no branch)") {
        return Some(BranchInfo::default());
    }
    let (text, unborn) = match text.strip_prefix("No commits yet on ") {
        Some(rest) => (rest, true),
        None => (text.as_str(), false),
    };

    // The upstream half and the drift are both optional, and the name is
    // whatever comes before whichever of them is present.
    let (name, rest) = match text.split_once("...") {
        Some((name, rest)) => (name, Some(rest)),
        None => match text.split_once(" [") {
            Some((name, rest)) => (name, Some(rest)),
            None => (text, None),
        },
    };
    let mut info = BranchInfo {
        name: Some(name.trim().to_owned()).filter(|name| !name.is_empty()),
        unborn,
        ..BranchInfo::default()
    };
    if let Some(rest) = rest {
        info.ahead = drift(rest, "ahead ");
        info.behind = drift(rest, "behind ");
        // `main...origin/main [ahead 1]` — the upstream is what sits between
        // the `...` and the drift, and its *absence* is what makes a push a
        // publish.
        if text.contains("...") {
            let upstream = rest.split_once(" [").map_or(rest, |(head, _)| head).trim();
            if !upstream.is_empty() {
                info.upstream = Some(upstream.to_owned());
            }
        }
        // `main...origin/main [gone]` — the upstream is configured but its
        // ref no longer exists. Only ever written whole, and only ever with
        // an upstream to be gone.
        if info.upstream.is_some()
            && let Some((_, bracket)) = rest.split_once('[')
        {
            info.upstream_gone = bracket.trim_end_matches(']').trim() == "gone";
        }
    }
    Some(info)
}

/// `[ahead 1, behind 2]` → the number after `keyword`, or 0.
fn drift(text: &str, keyword: &str) -> u32 {
    text.split_once(keyword)
        .map(|(_, rest)| {
            rest.chars()
                .take_while(char::is_ascii_digit)
                .collect::<String>()
        })
        .and_then(|digits| digits.parse().ok())
        .unwrap_or(0)
}

/// One porcelain code pair to one status.
///
/// `X` is the index against HEAD, `Y` the worktree against the index. The
/// order below is the precedence we show, and it is a UI decision as much as a
/// git one:
///
/// 1. Unmerged pairs are conflicts, and nothing outranks a conflict.
/// 2. `?` untracked, `!` ignored — these never combine with anything.
/// 3. A `D` on either side means the file is not there any more, which the
///    user needs to know before they need to know how it got that way (so
///    `AD`, staged-then-deleted, reads as deleted).
/// 4. `R`/`C` — a rename, or a copy shown as one.
/// 5. `A` — new to the index.
/// 6. anything left (`M`, `T`, and combinations) — modified.
fn classify(x: u8, y: u8) -> GitStatus {
    // The seven unmerged combinations from git-status(1).
    let unmerged = matches!(
        (x, y),
        (b'D', b'D')
            | (b'A', b'U')
            | (b'U', b'D')
            | (b'U', b'A')
            | (b'D', b'U')
            | (b'A', b'A')
            | (b'U', b'U')
    );
    if unmerged {
        return GitStatus::Conflicted;
    }
    if x == b'?' || y == b'?' {
        return GitStatus::Untracked;
    }
    if x == b'!' || y == b'!' {
        return GitStatus::Ignored;
    }
    if x == b'D' || y == b'D' {
        return GitStatus::Deleted;
    }
    if x == b'R' || x == b'C' {
        return GitStatus::Renamed;
    }
    if x == b'A' {
        return GitStatus::Added;
    }
    GitStatus::Modified
}

/// Give every ancestor directory of a changed file a status of its own.
///
/// **We do roll up, and we do it here, once per run.** The alternative — the
/// panel asking "does anything under this directory have a status?" per drawn
/// row — is a prefix scan per row on every frame. Rolling up costs one pass
/// over the changed paths (which is a *small* list; that is the whole nature of
/// a status) times their depth, and leaves the panel with a single map lookup
/// per row, directories included.
///
/// A directory does not get its descendant's exact status, because there isn't
/// one — a directory can easily hold a deletion and an addition at once.
/// It gets a summary, in three tiers:
///
/// * anything conflicted below → `Conflicted`
/// * anything modified, deleted or renamed below → `Modified`
/// * only new files below → `Added` if any is staged, else `Untracked`
///
/// `Ignored` never rolls up: a directory holding an ignored file is not itself
/// ignored, and the worktree already reports real ignore status per entry.
pub(crate) fn roll_up(files: &[(String, GitStatus)]) -> BTreeMap<String, GitStatus> {
    let mut out = BTreeMap::new();
    for (path, status) in files {
        out.insert(path.clone(), *status);
    }
    for (path, status) in files {
        let Some(tier) = tier(*status) else { continue };
        // Walk the ancestors by slicing at each '/', so no allocation happens
        // for a directory that is already recorded at the same or a higher
        // tier.
        for (index, byte) in path.bytes().enumerate() {
            if byte != b'/' {
                continue;
            }
            let dir = &path[..index];
            match out.get(dir) {
                Some(existing) if tier_of_summary(*existing) >= tier => {}
                _ => {
                    out.insert(dir.to_owned(), summary(tier, *status));
                }
            }
        }
    }
    out
}

/// How loudly a file status speaks for its ancestors. `None` for statuses that
/// don't propagate at all.
fn tier(status: GitStatus) -> Option<u8> {
    match status {
        GitStatus::Conflicted => Some(3),
        GitStatus::Modified | GitStatus::Deleted | GitStatus::Renamed => Some(2),
        GitStatus::Added => Some(1),
        GitStatus::Untracked => Some(0),
        GitStatus::Ignored => None,
    }
}

/// The tier a directory summary already represents, for the "is this louder
/// than what's there?" comparison.
fn tier_of_summary(status: GitStatus) -> u8 {
    tier(status).unwrap_or(0)
}

/// The single status that stands for a tier. `Added` and `Untracked` share a
/// tier — both mean "new here" — and keep whichever one produced it.
fn summary(tier: u8, from: GitStatus) -> GitStatus {
    match tier {
        3 => GitStatus::Conflicted,
        2 => GitStatus::Modified,
        1 => GitStatus::Added,
        _ => from,
    }
}

// ---------------------------------------------------------------------------
// Commands. Everything above answers a question; everything below changes the
// repository, blocks until git says it is done, and hands back git's own words
// when it refuses.
// ---------------------------------------------------------------------------

/// A *read-only* command that takes longer than this has gone wrong. Shorter
/// than the query's deadline because somebody is waiting on it with a finger
/// on a button, and a spinner that never ends is worse than an error. Only
/// reads may be killed this eagerly — a killed read costs a retry and nothing
/// else; a killed write is [`MUTATION_TIMEOUT`]'s story.
const COMMAND_TIMEOUT: Duration = Duration::from_secs(30);

/// The safety net for commands that *change* the repository — a checkout, a
/// commit, and the remote trio that moves commits over a network that may be
/// a phone's. Deliberately not a responsiveness promise: killing a mutation
/// mid-flight leaves half-applied state behind — a fetch that already moved
/// remote refs, a merge with `MERGE_HEAD` in place, a stale `index.lock`
/// nothing in the app can clean up — which is strictly worse than a long
/// wait. Zed's equivalents run with no deadline at all; ten minutes is the
/// something-is-truly-wedged backstop, not a duration any healthy command
/// reaches.
pub(crate) const MUTATION_TIMEOUT: Duration = Duration::from_secs(600);

/// What the shell wrapper prints after the command it ran.
const EXIT_MARKER: &str = "seeker-exit:";

/// The wrapper itself.
///
/// [`guest::capture`] gives back stdout and only on success, which is right for
/// a query and useless for a command: the whole value of a failed `git commit`
/// is the sentence on *stderr*, and a non-zero exit is a fact the user has to
/// be told rather than a reason to hand back `None`. Both are fixed by running
/// git under `sh`, which merges the two streams and always exits 0 itself,
/// printing git's real status where it can be read back.
///
/// `"$@"` and not an interpolated command line: the arguments arrive as `sh`'s
/// own positional parameters, so a path with a quote or a space in it is passed
/// through untouched and there is no quoting to get wrong. A user's file name
/// must never be able to become shell syntax.
///
/// The marker is printed with no newline before it, deliberately. `git show` is
/// read through this too, and one added newline is one added *line* — a phantom
/// deleted row at the end of every file the gutter draws.
const WRAPPER: &str = r#""$@" 2>&1; printf 'seeker-exit:%d' "$?""#;

/// What one command did.
#[derive(Debug)]
pub(crate) struct GitRun {
    /// git's own exit status.
    pub status: i32,
    /// stdout and stderr together, in the order git wrote them.
    pub output: String,
}

impl GitRun {
    /// git's complaint, shortened to the one line a panel can show.
    ///
    /// Not the first line, which is what it looks like it should be: a commit
    /// with nothing staged opens with "On branch main" and says what is wrong
    /// three lines later, and an identity git cannot guess opens with a blank
    /// line and a paragraph of advice. What is reliable is that git marks the
    /// sentence with `fatal:` or `error:` when there is one, and that when
    /// there is not — the commit case — it is the last thing said.
    pub fn message(&self) -> String {
        failure_line(&self.output, self.status)
    }
}

/// The shared body of [`GitRun::message`] — also what a split-stream command
/// ([`run_git_split`]) summarizes its two streams through.
pub(crate) fn failure_line(output: &str, status: i32) -> String {
    let lines: Vec<&str> = output
        .lines()
        .map(str::trim_end)
        .filter(|line| !line.trim().is_empty())
        .collect();
    let marked = lines.iter().find_map(|line| {
        line.strip_prefix("fatal: ")
            .or_else(|| line.strip_prefix("error: "))
    });
    match marked.or_else(|| lines.last().copied()) {
        Some(line) => line.trim().to_owned(),
        None => format!("git exited with {status}"),
    }
}

/// Run one *read-only* git command inside the guest and read back everything
/// it said.
///
/// `Err` means the *guest* failed — no proot, no `sh`, the deadline — which
/// is not something git said. A command that changes the repository goes
/// through [`run_git_mutating`] instead, whose deadline is a safety net
/// rather than a promise of responsiveness.
pub(crate) fn run_git(
    userland: &Userland,
    repo_root: &Path,
    label: &str,
    argv: Vec<OsString>,
) -> Result<GitRun, String> {
    run_git_with(userland, repo_root, label, argv, COMMAND_TIMEOUT)
}

/// [`run_git`] for a command that *changes* the repository. Same guest, same
/// wrapper, same retry — but the deadline is [`MUTATION_TIMEOUT`]: killing
/// `git checkout` or `git commit` mid-flight leaves a half-switched worktree
/// or a stale `index.lock` behind, which is strictly worse than a long wait,
/// so only a truly wedged command is ever killed.
pub(crate) fn run_git_mutating(
    userland: &Userland,
    repo_root: &Path,
    label: &str,
    argv: Vec<OsString>,
) -> Result<GitRun, String> {
    run_git_with(userland, repo_root, label, argv, MUTATION_TIMEOUT)
}

/// The shared body of the two above.
fn run_git_with(
    userland: &Userland,
    repo_root: &Path,
    label: &str,
    argv: Vec<OsString>,
    timeout: Duration,
) -> Result<GitRun, String> {
    // What was actually run, which is the one thing missing when a command
    // works by hand in the terminal and fails here.
    log::debug!("{label}: {argv:?}");
    let retry_argv = argv.clone();
    let output = captured(
        userland,
        &git_command(label, repo_root, wrapped_argv(argv)),
        label,
        timeout,
    )?;
    let run = parse_run(&output)?;
    if run.status == 0 {
        return Ok(run);
    }
    log::debug!("{label} exited {}: {:?}", run.status, run.output);
    // A guest that has just told git the project does not exist is a guest
    // that was not ready, not a repository that is missing: the same command
    // run a moment later — by hand, or by this retry — finds it. Seen with a
    // terminal session alive, and only ever once in a row.
    if run.output.contains("cannot change to") {
        log::debug!("{label}: the guest lost the project; running it once more");
        let output = captured(
            userland,
            &git_command(label, repo_root, wrapped_argv(retry_argv)),
            label,
            timeout,
        )?;
        return parse_run(&output);
    }
    Ok(run)
}

/// One capture, with the deadline's own sentence kept apart from the guest
/// failing to run git at all: by the time a deadline fires git *was* running,
/// and a mutating command killed mid-flight may have partly applied — the
/// error has to say so rather than blame the userland.
fn captured(
    userland: &Userland,
    command: &GuestCommand,
    label: &str,
    timeout: Duration,
) -> Result<Vec<u8>, String> {
    match guest::capture_outcome(userland, command, timeout) {
        guest::Captured::Output(output) => Ok(output),
        guest::Captured::TimedOut => Err(format!(
            "{label} was stopped after {} seconds and may have partly applied; check the repository before retrying",
            timeout.as_secs()
        )),
        guest::Captured::Failed => Err("Could not run git in the Linux userland".to_owned()),
    }
}

/// git's argv, wrapped in the shell that makes its failure readable.
fn wrapped_argv(argv: Vec<OsString>) -> Vec<OsString> {
    let mut wrapped: Vec<OsString> = vec![
        OsString::from("/bin/sh"),
        OsString::from("-c"),
        OsString::from(WRAPPER),
        // `$0`. Names the wrapper in any diagnostic `sh` itself produces; git
        // starts at `$1`, which is what `"$@"` expands to.
        OsString::from("seeker-git"),
    ];
    wrapped.extend(argv);
    wrapped
}

/// Split the wrapper's output back into what git said and how it exited.
fn parse_run(output: &[u8]) -> Result<GitRun, String> {
    let output = String::from_utf8_lossy(output).into_owned();
    // From the *last* marker: git is free to print the string itself, and a
    // repository holding a file called `seeker-exit:0` must not be able to
    // make a failure read as a success.
    let (before, after) = output
        .rsplit_once(EXIT_MARKER)
        .ok_or_else(|| "git produced no result".to_owned())?;
    Ok(GitRun {
        status: after.trim().parse().unwrap_or(-1),
        output: before.to_owned(),
    })
}

/// What the split wrapper prints between the two streams.
const STDERR_MARKER: &str = "seeker-stderr:";

/// A second wrapper, for the commands whose two streams *mean* different
/// things. Zed's remote-output toasts read them separately — a pull's file
/// count is parsed off stdout while the fetch progress scrolls by on stderr,
/// and a push's "Everything up-to-date" is a stderr sentence
/// (`crates/git_ui/src/remote_output.rs:82-186`) — so merging them the way
/// [`WRAPPER`] does would leave nothing to format.
///
/// stderr is parked in a file while stdout streams through, then replayed
/// after its marker; both markers are printed with no added newline so each
/// stream comes back byte-for-byte, trailing newlines included — the toast
/// rules end on `\n` and have to keep matching.
///
/// The park is `/tmp` **literally**, never `$TMPDIR`. This wrapper only ever
/// runs inside our own Debian rootfs, where `/tmp` always exists — while
/// TMPDIR is whatever the environment happened to hand the shell, and on
/// Android the app process carries one naming the *host* cache directory, a
/// path that does not exist inside the fake root. `sh` then fails the
/// redirection before git ever runs and moves on: exit 2, both streams
/// empty, and the sentence explaining it all on sh's own stderr, which the
/// capture does not return. A fetch that failed for a real reason came back
/// as "git exited with 2" with nothing to show — the guest environment now
/// pins TMPDIR too ([`crate::guest`]), and this wrapper additionally refuses
/// to trust it.
const SPLIT_WRAPPER: &str = r#"t="/tmp/seeker-stderr.$$"; "$@" 2>"$t"; s=$?; printf 'seeker-stderr:'; cat "$t" 2>/dev/null; rm -f "$t"; printf 'seeker-exit:%d' "$s""#;

/// What one split-stream command did.
pub(crate) struct GitSplitRun {
    pub status: i32,
    pub stdout: String,
    pub stderr: String,
}

/// [`run_git`] with the two streams kept apart — for fetch, pull and push,
/// whose output the UI formats rather than merely shows. Same guest, same
/// retry, and every caller is a mutation that talks to a network, so the
/// deadline is [`MUTATION_TIMEOUT`]'s safety net rather than a short one that
/// would kill a legitimate first fetch of a real repository over a phone's
/// network — mid-merge, refs already moved.
///
/// `env` is the caller's addition to the guest environment: the askpass
/// variables, for a command that may have to ask for a credential
/// (`crate::askpass`). Every other variable is [`git_command`]'s.
pub(crate) fn run_git_split(
    userland: &Userland,
    repo_root: &Path,
    label: &str,
    argv: Vec<OsString>,
    env: &[(OsString, OsString)],
) -> Result<GitSplitRun, String> {
    log::debug!("{label}: {argv:?}");
    let retry_argv = argv.clone();
    let with_env = |command: GuestCommand| {
        env.iter()
            .fold(command, |command, (key, value)| command.env(key, value))
    };
    let output = captured(
        userland,
        &with_env(git_command(label, repo_root, split_wrapped_argv(argv))),
        label,
        MUTATION_TIMEOUT,
    )?;
    let run = parse_split_run(&output)?;
    if run.status == 0 {
        return Ok(run);
    }
    log::debug!(
        "{label} exited {}: stdout {:?} stderr {:?}",
        run.status,
        run.stdout,
        run.stderr
    );
    // The same not-ready-guest retry `run_git` documents.
    if run.stderr.contains("cannot change to") {
        log::debug!("{label}: the guest lost the project; running it once more");
        let output = captured(
            userland,
            &with_env(git_command(label, repo_root, split_wrapped_argv(retry_argv))),
            label,
            MUTATION_TIMEOUT,
        )?;
        return parse_split_run(&output);
    }
    Ok(run)
}

/// git's argv under the split wrapper — [`wrapped_argv`]'s twin.
fn split_wrapped_argv(argv: Vec<OsString>) -> Vec<OsString> {
    let mut wrapped: Vec<OsString> = vec![
        OsString::from("/bin/sh"),
        OsString::from("-c"),
        OsString::from(SPLIT_WRAPPER),
        OsString::from("seeker-git"),
    ];
    wrapped.extend(argv);
    wrapped
}

/// Split the split wrapper's output back into stdout, stderr and the status.
///
/// The exit marker is taken from the *last* occurrence for the same reason
/// [`parse_run`] does. The stderr marker is taken from the *first*: the
/// wrapper prints it exactly once, between the streams, so an occurrence git
/// itself wrote can only sit inside one of them — and a remote command's
/// stdout containing the literal is a curiosity, not a failure mode worth a
/// byte-count protocol.
fn parse_split_run(output: &[u8]) -> Result<GitSplitRun, String> {
    let output = String::from_utf8_lossy(output).into_owned();
    let (before, after) = output
        .rsplit_once(EXIT_MARKER)
        .ok_or_else(|| "git produced no result".to_owned())?;
    let (stdout, stderr) = before
        .split_once(STDERR_MARKER)
        .ok_or_else(|| "git produced no result".to_owned())?;
    Ok(GitSplitRun {
        status: after.trim().parse().unwrap_or(-1),
        stdout: stdout.to_owned(),
        stderr: stderr.to_owned(),
    })
}

/// Where a command is going to run: the guest, the project, and the repository
/// around it. Resolved once per command, and every failure here is a sentence
/// the panel shows rather than a log line.
pub(crate) struct Repo {
    pub(crate) userland: Arc<Userland>,
    pub(crate) project_root: PathBuf,
    pub(crate) repo_root: PathBuf,
}

impl crate::Engine {
    pub(crate) fn repo_for(&self, id: ProjectId) -> Result<Repo, String> {
        let project = self
            .projects
            .lock()
            .unwrap()
            .get(&id)
            .cloned()
            .ok_or_else(|| "That project is not open".to_owned())?;
        let project_root = project.lock().unwrap().root();
        let userland = self
            .userland()
            .filter(|userland| userland.is_installed())
            .ok_or_else(|| "The Linux userland is not installed".to_owned())?;
        let repo_root =
            repo_root_of(&project_root).ok_or_else(|| "Not a git repository".to_owned())?;
        Ok(Repo {
            userland,
            project_root,
            repo_root,
        })
    }

    /// Every changed file the last status run saw, project-relative.
    fn changed_files(&self, id: ProjectId) -> Arc<Vec<FileChange>> {
        self.with_git(id, |git| git.changes.clone())
            .unwrap_or_default()
    }

    /// Force the next poll to ask git again, and tell the diff gutter that
    /// HEAD or the index has moved.
    ///
    /// Without this the panel would sit on the pre-command status until the
    /// worktree happened to change — and staging a file changes the index, not
    /// the worktree, so for staging that is *never*.
    pub(crate) fn git_state_changed(&self, id: ProjectId) {
        if let Some(cache) = self.git.projects.lock().unwrap().get(&id).cloned() {
            cache.lock().unwrap().scanned = false;
        }
        bump_generation();
    }

    /// Stage every listed path. Blocking; call it off the main thread.
    ///
    /// `add -A` rather than `add`, so that staging a file the user deleted
    /// stages the deletion. Both are what the panel's checkbox means.
    pub fn git_stage(&self, id: ProjectId, paths: &[String]) -> Result<(), String> {
        let repo = self.repo_for(id)?;
        let paths = checked_paths(paths)?;
        let mut args: Vec<OsString> = ["add", "-A", "--"].iter().map(OsString::from).collect();
        args.extend(paths.iter().map(OsString::from));
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git add",
            git_argv(&repo.project_root, &args),
        );
        // On the Err path too: a run the guest lost may still have moved the
        // index before the kill landed, and a `?` that skipped the bump left
        // the panel sitting on the pre-command state exactly then.
        self.git_state_changed(id);
        let run = run?;
        if run.status == 0 {
            Ok(())
        } else {
            Err(run.message())
        }
    }

    /// Take every listed path back out of the index. Blocking.
    ///
    /// Two commands, because a repository with no commits yet has no HEAD to
    /// restore from and `git restore --staged` says so rather than doing the
    /// obvious thing. `git rm --cached` is what "unstage" means there — the
    /// file is new, and only the index entry goes — and it is only reached
    /// when the first command has already failed.
    pub fn git_unstage(&self, id: ProjectId, paths: &[String]) -> Result<(), String> {
        let repo = self.repo_for(id)?;
        let paths = checked_paths(paths)?;

        let mut args: Vec<OsString> = ["restore", "--staged", "--"]
            .iter()
            .map(OsString::from)
            .collect();
        args.extend(paths.iter().map(OsString::from));
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git restore --staged",
            git_argv(&repo.project_root, &args),
        );
        // Ok or Err alike — the run may have moved the index either way.
        self.git_state_changed(id);
        let run = run?;
        if run.status == 0 {
            return Ok(());
        }

        let mut args: Vec<OsString> = ["rm", "--cached", "--quiet", "-r", "--"]
            .iter()
            .map(OsString::from)
            .collect();
        args.extend(paths.iter().map(OsString::from));
        let fallback = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git rm --cached",
            git_argv(&repo.project_root, &args),
        );
        self.git_state_changed(id);
        let fallback = fallback?;
        if fallback.status == 0 {
            Ok(())
        } else {
            // The first command's complaint, not the fallback's: the fallback
            // is a guess about an unborn HEAD, and its error is about `git rm`.
            Err(run.message())
        }
    }

    /// Throw away the changes to every listed path. Blocking.
    ///
    /// **This is the destructive one**, and it is two different operations
    /// wearing one name:
    ///
    /// * A path HEAD knows is *restored* to what HEAD has, index and worktree
    ///   together. What is lost is uncommitted, and nothing else.
    /// * A path HEAD has never seen — untracked, staged-new, or the
    ///   destination of a rename — cannot be restored from anywhere, so it goes
    ///   to the app's trash instead of being unlinked. `git clean` would delete
    ///   it outright; Zed's own panel trashes untracked files for the same
    ///   reason (`TrashUntrackedFiles`), and here it is the difference between
    ///   a mistake and a loss.
    ///
    /// Which of the two a row gets is [`discard_steps`]'s decision, taken from
    /// the status letters, and a row it cannot explain — a conflict, a rename
    /// with no source, a path no status run has seen — refuses the whole list
    /// before anything runs. That is not caution for its own sake: `git restore
    /// --source=HEAD` deletes a path HEAD does not hold rather than refusing it.
    ///
    /// The caller is expected to have confirmed with the user first, naming the
    /// files. Nothing in this function asks.
    pub fn git_discard(&self, id: ProjectId, paths: &[String]) -> Result<(), String> {
        let repo = self.repo_for(id)?;
        let checked = checked_paths(paths)?;
        let known = self.changed_files(id);

        // The whole plan first, and every path in it checked again: a rename's
        // source came from git rather than from the UI, and "git said it" is
        // not a reason to hand a path to a command that deletes things.
        let mut plan: Vec<Discard> = Vec::new();
        for path in &checked {
            let change = known
                .iter()
                .find(|change| change.path.trim_end_matches('/') == path);
            for step in discard_steps(path, change)? {
                plan.push(match step {
                    Discard::Restore(path) => Discard::Restore(checked_path(&path)?),
                    Discard::Trash(path) => Discard::Trash(checked_path(&path)?),
                    Discard::Forget(path) => Discard::Forget(checked_path(&path)?),
                });
            }
        }
        let restore: Vec<&String> = plan
            .iter()
            .filter_map(|step| match step {
                Discard::Restore(path) => Some(path),
                _ => None,
            })
            .collect();

        let mut failures = Vec::new();
        if !restore.is_empty() {
            let mut args: Vec<OsString> = [
                "restore",
                "--source=HEAD",
                "--staged",
                "--worktree",
                // A dirty submodule is ` M sub`, indistinguishable from a file
                // here, and `submodule.recurse` in the user's config would turn
                // restoring its gitlink into restoring its whole worktree.
                "--no-recurse-submodules",
                "--",
            ]
            .iter()
            .map(OsString::from)
            .collect();
            args.extend(restore.iter().map(OsString::from));
            let run = match run_git_mutating(
                &repo.userland,
                &repo.repo_root,
                "git restore",
                git_argv(&repo.project_root, &args),
            ) {
                Ok(run) => run,
                Err(err) => {
                    // The restore may have partly run before the guest lost
                    // it; the cache must not keep the pre-command state.
                    self.git_state_changed(id);
                    return Err(err);
                }
            };
            if run.status != 0 {
                failures.push(run.message());
            }
        }
        for step in &plan {
            let path = match step {
                Discard::Restore(_) => continue,
                Discard::Trash(path) => {
                    // Joined onto the project root rather than passed to git:
                    // this half never enters the guest, and the trash is the
                    // engine's own.
                    let absolute = repo.project_root.join(Path::new(path));
                    if let Err(err) = trash::delete_with_info(&absolute) {
                        failures.push(format!("{}: {err}", absolute.display()));
                    }
                    path
                }
                // Nothing on disk to move — see [`Discard::Forget`]. The index
                // entry below is the whole of it, and the old code's `continue`
                // after a failed trash skipped exactly that, leaving the row in
                // the panel forever with an errno beside it.
                Discard::Forget(path) => path,
            };
            // A staged-new file also has an index entry, which trashing the
            // file does not remove; without this the panel would keep showing
            // it as staged for a file that is no longer there. `-r` because one
            // record can be a whole untracked directory.
            let mut args: Vec<OsString> =
                ["rm", "--cached", "--quiet", "--ignore-unmatch", "-r", "--"]
                    .iter()
                    .map(OsString::from)
                    .collect();
            args.push(OsString::from(&path));
            let _ = run_git_mutating(
                &repo.userland,
                &repo.repo_root,
                "git rm --cached",
                git_argv(&repo.project_root, &args),
            );
        }

        self.git_state_changed(id);
        if failures.is_empty() {
            Ok(())
        } else {
            Err(failures.join("; "))
        }
    }

    /// Commit what is staged. Blocking.
    ///
    /// An empty message is refused here rather than by git: `git commit
    /// --allow-empty-message` is not passed, so git would refuse it too, but it
    /// would do so after a process spawn and with a sentence about editors. A
    /// message that is only whitespace is empty — git strips it and ends up in
    /// the same place. That refusal covers amending too: Zed's editor arrives
    /// prefilled with the old message, so a blank one is still a mistake.
    ///
    /// The three flags are the three of Zed's `CommitOptions` its panel can
    /// set: the split-button's Amend, Signoff and Skip Hooks entries
    /// (git_panel.rs:5568-5610). See [`commit_args`] for the argv.
    pub fn git_commit(
        &self,
        id: ProjectId,
        message: &str,
        amend: bool,
        signoff: bool,
        no_verify: bool,
    ) -> Result<(), String> {
        if message.trim().is_empty() {
            return Err("Write a commit message first".to_owned());
        }
        let repo = self.repo_for(id)?;
        let args = commit_args(message, amend, signoff, no_verify);
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git commit",
            git_argv(&repo.project_root, &args),
        );
        // Ok or Err alike — a lost run may still have committed.
        self.git_state_changed(id);
        let run = run?;
        if run.status == 0 {
            Ok(())
        } else {
            Err(run.message())
        }
    }

    /// Undo the last commit, keeping everything it held staged — Zed's
    /// Uncommit. What runs is exactly `git reset --soft HEAD^`
    /// (git_panel.rs:3164, repository.rs:1501-1504): always `--soft`, whatever
    /// the button's tooltip implies about a mixed reset. Blocking.
    ///
    /// Nothing here asks anything. The caller is expected to have warned when
    /// the commit was already pushed — [`Engine::git_head_pushed_remotes`] is
    /// that check — and to have read the old message back for the commit box
    /// *before* resetting, while HEAD still names it.
    pub fn git_uncommit(&self, id: ProjectId) -> Result<(), String> {
        let repo = self.repo_for(id)?;
        let args: Vec<OsString> = ["reset", "--soft", "HEAD^"]
            .iter()
            .map(OsString::from)
            .collect();
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git reset",
            git_argv(&repo.project_root, &args),
        );
        // Ok or Err alike — a lost run may still have moved HEAD.
        self.git_state_changed(id);
        let run = run?;
        if run.status == 0 {
            Ok(())
        } else {
            Err(run.message())
        }
    }

    /// Every `remote/branch` whose remote-tracking ref already contains HEAD —
    /// the commit Uncommit is about to undo has been pushed there, and the
    /// user should be asked first. Zed's `check_for_pushed_commit`
    /// (repository.rs:2907-2933): `git for-each-ref --format=%(refname)
    /// --contains HEAD refs/remotes/`, minus every `…/HEAD` symref. A repo
    /// with no remotes answers with an empty list, and so does a failed run —
    /// Zed proceeds silently there, and refusing to uncommit because a *check*
    /// failed would be worse than not asking. **Blocking**.
    pub fn git_head_pushed_remotes(&self, id: ProjectId) -> Result<Vec<String>, String> {
        let repo = self.repo_for(id)?;
        let args: Vec<OsString> = [
            "for-each-ref",
            "--format=%(refname)",
            "--contains",
            "HEAD",
            "refs/remotes/",
        ]
        .iter()
        .map(OsString::from)
        .collect();
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git for-each-ref",
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            // An unborn HEAD makes `--contains HEAD` fail, and there is
            // nothing pushed in that world either.
            return Ok(Vec::new());
        }
        Ok(parse_pushed_remotes(&run.output))
    }

    /// Make the project a repository — the panel's "Initialize Repository"
    /// empty state. Zed's two commands exactly (fs.rs:1208-1235): ask
    /// `git config --global --get init.defaultBranch` first, use its answer
    /// when there is one, and fall back to the caller's name — Zed's setting
    /// defaults it to `main` — for `git init -b <branch>`. **Blocking**.
    ///
    /// The one git operation that must not go through [`Engine::repo_for`],
    /// whose "Not a git repository" is the very state this fixes.
    pub fn git_init(&self, id: ProjectId, fallback_branch: &str) -> Result<(), String> {
        let project = self
            .projects
            .lock()
            .unwrap()
            .get(&id)
            .cloned()
            .ok_or_else(|| "That project is not open".to_owned())?;
        let project_root = project.lock().unwrap().root();
        let userland = self
            .userland()
            .filter(|userland| userland.is_installed())
            .ok_or_else(|| "The Linux userland is not installed".to_owned())?;

        let config_args: Vec<OsString> = ["config", "--global", "--get", "init.defaultBranch"]
            .iter()
            .map(OsString::from)
            .collect();
        let configured = run_git(
            &userland,
            &project_root,
            "git config",
            git_argv(&project_root, &config_args),
        )
        .ok()
        // Exit 1 with no output is "unset", which is an answer; and a value
        // this could not hand to git as an argument falls back too, rather
        // than making the empty state unusable over one odd config line.
        .filter(|run| run.status == 0)
        .and_then(|run| checked_branch(run.output.trim()).ok());
        let branch = match configured {
            Some(branch) => branch,
            None => checked_branch(fallback_branch)?,
        };

        let args: Vec<OsString> = vec![
            OsString::from("init"),
            OsString::from("-b"),
            OsString::from(&branch),
        ];
        let run = run_git_mutating(
            &userland,
            &project_root,
            "git init",
            git_argv(&project_root, &args),
        );
        // Ok or Err alike — a lost run may still have created the repository.
        self.git_state_changed(id);
        let run = run?;
        if run.status == 0 {
            Ok(())
        } else {
            Err(run.message())
        }
    }

    /// The identity commits will be recorded under, as git resolves it here.
    ///
    /// Empty strings for "git has none", which is the state a fresh Debian is
    /// in: it guesses `root@localhost.(none)` from the hostname, refuses to
    /// use it, and every commit fails until somebody says who they are.
    ///
    /// **Blocking**: it runs git.
    pub fn git_identity(&self, id: ProjectId) -> Result<(String, String), String> {
        let repo = self.repo_for(id)?;
        let read = |key: &str| -> String {
            let args = [
                OsString::from("config"),
                OsString::from("--get"),
                OsString::from(key),
            ];
            run_git(
                &repo.userland,
                &repo.repo_root,
                "git config",
                git_argv(&repo.project_root, &args),
            )
            .ok()
            // `--get` exits 1 with no output when the key is unset, which is
            // an answer rather than a failure.
            .filter(|run| run.status == 0)
            .map(|run| run.output.trim().to_owned())
            .unwrap_or_default()
        };
        Ok((read("user.name"), read("user.email")))
    }

    /// Record who commits are by, in the guest's global config.
    ///
    /// Global rather than per-repository because the alternative is answering
    /// this question once per clone, and the userland is one machine with one
    /// person at it. `HOME` inside the guest is `/root`, so this lands in the
    /// rootfs and survives everything but removing the userland.
    ///
    /// **Blocking**: it runs git.
    pub fn git_set_identity(&self, id: ProjectId, name: &str, email: &str) -> Result<(), String> {
        let name = name.trim();
        let email = email.trim();
        if name.is_empty() || email.is_empty() {
            return Err("Both a name and an email are needed".to_owned());
        }
        // Not a validation of what an email *is* — that argument has no end —
        // but of what git will accept: it refuses a value with a newline in
        // it, and a leading dash would be read as an option by anything that
        // later passes this through a shell.
        for value in [name, email] {
            if value.contains('\n') || value.contains('\r') || value.starts_with('-') {
                return Err(
                    "A name or email cannot start with '-' or contain a line break".to_owned(),
                );
            }
        }
        if !email.contains('@') {
            return Err("That does not look like an email address".to_owned());
        }
        let repo = self.repo_for(id)?;
        for (key, value) in [("user.name", name), ("user.email", email)] {
            let args = [
                OsString::from("config"),
                OsString::from("--global"),
                OsString::from(key),
                OsString::from(value),
            ];
            let run = run_git(
                &repo.userland,
                &repo.repo_root,
                "git config",
                git_argv(&repo.project_root, &args),
            )?;
            if run.status != 0 {
                return Err(run.message());
            }
        }
        Ok(())
    }
}

/// The commit argv, flag for flag as Zed builds it (repository.rs:2638-2663):
/// `--cleanup=strip` right after the message, then `--amend`, `--signoff`,
/// `--no-verify`, in that order. Zed's order also holds `--allow-empty` and
/// `--author` between the last two, which its panel never passes — it commits
/// with `allow_empty: false` and `name_and_email: None` (git_panel.rs:3046-
/// 3053) — so neither exists here.
pub(crate) fn commit_args(
    message: &str,
    amend: bool,
    signoff: bool,
    no_verify: bool,
) -> Vec<OsString> {
    let mut args: Vec<OsString> = vec![
        OsString::from("commit"),
        OsString::from("--quiet"),
        OsString::from("-m"),
        OsString::from(message),
        OsString::from("--cleanup=strip"),
    ];
    if amend {
        args.push(OsString::from("--amend"));
    }
    if signoff {
        args.push(OsString::from("--signoff"));
    }
    if no_verify {
        args.push(OsString::from("--no-verify"));
    }
    args
}

/// `for-each-ref --format=%(refname) --contains HEAD refs/remotes/`, read the
/// way Zed reads it (repository.rs:2927-2933): every line is a remote-tracking
/// ref that already holds HEAD, `…/HEAD` symrefs dropped — `origin/HEAD` names
/// the remote's default branch, not a place anything was pushed — and the
/// `refs/remotes/` prefix stripped, leaving `origin/main`.
pub(crate) fn parse_pushed_remotes(output: &str) -> Vec<String> {
    output
        .lines()
        .map(str::trim)
        .filter(|line| !line.ends_with("/HEAD"))
        .filter_map(|line| line.strip_prefix("refs/remotes/"))
        .map(str::to_owned)
        .collect()
}

/// A branch or remote name this will hand to git as an argument.
///
/// The argv never crosses a shell as *syntax*: `GuestCommand` execs proot
/// with these strings as real arguments, and the `sh -c '"$@" …'` wrapper
/// re-expands them as its own positional parameters — unsplit, unglobbed,
/// never re-parsed. So the only reader who can misread a name is git itself,
/// and the extra rules beyond git's own are exactly those two misreadings:
///
/// * option parsing — a leading `-` reads as a flag;
/// * refspec and rev syntax, where a name becomes `<branch>:<remote_branch>`
///   or `<branch>@{upstream}` — a leading `+` is a force marker (`git push
///   origin +main` force-updates `main` on the remote and discards commits),
///   and `:` would split the refspec. `*`, `@{` and a bare `@` are already
///   git's own refusals.
///
/// Everything else is `git check-ref-format`'s rulebook, so any name git
/// itself lists — unicode, `#`, a `@` or `+` mid-name — passes, and the rows
/// [`crate::Engine::git_branches`] and [`crate::Engine::git_remotes`] hand
/// the pickers stay actionable. The old ASCII whitelist refused those and
/// left legal branches permanently un-actionable.
pub(crate) fn checked_branch(name: &str) -> Result<String, String> {
    let name = name.trim();
    // What check-ref-format refuses anywhere in a refname: ASCII control
    // bytes, space, and these metacharacters.
    let forbidden = |c: char| {
        c.is_ascii_control() || matches!(c, ' ' | '~' | '^' | ':' | '?' | '*' | '[' | '\\')
    };
    if name.is_empty()
        || name.len() > 255
        || name == "@"
        || name.chars().any(forbidden)
        || name.starts_with('-')
        || name.starts_with('+')
        || name.ends_with('.')
        || name.contains("..")
        || name.contains("@{")
        || name
            .split('/')
            .any(|part| part.is_empty() || part.starts_with('.') || part.ends_with(".lock"))
    {
        return Err(format!("{name:?} is not a branch or remote name this can use"));
    }
    Ok(name.to_owned())
}

/// Turn caller-supplied project-relative paths into arguments, or refuse.
///
/// Every path here came across the JNI boundary from a UI holding a list the
/// engine gave it — but "came from us a moment ago" is not a property this
/// function can check, and the commands below delete files. So the rules are
/// the ones that make a path unable to name anything outside the project:
/// relative, no `..`, no empty component, no NUL. A path that breaks them is an
/// error rather than a silent skip, because a discard that quietly did nothing
/// to one of three files is worse than one that did nothing at all.
fn checked_paths(paths: &[String]) -> Result<Vec<String>, String> {
    if paths.is_empty() {
        return Err("No files given".to_owned());
    }
    paths.iter().map(String::as_str).map(checked_path).collect()
}

/// One path, checked. Also the gate every path *we* produce goes through —
/// a rename's source comes from git rather than from the UI, and "git said it"
/// is not a reason to hand it to a command that deletes things.
pub(crate) fn checked_path(path: &str) -> Result<String, String> {
    // One trailing slash is git's own spelling for an untracked directory
    // (`?? newdir/`), which is a row the panel draws and a path the user can
    // stage or discard like any other. Trimming it here rather than rejecting
    // it is the difference between "Stage all" working and the whole list
    // being refused because one record names a folder.
    let trimmed = path.strip_suffix('/').unwrap_or(path);
    let bad = trimmed.is_empty()
        || trimmed.starts_with('/')
        || trimmed.contains('\0')
        || trimmed
            .split('/')
            .any(|part| part.is_empty() || part == "..");
    if bad {
        return Err(format!("{path:?} is not a path inside the project"));
    }
    Ok(trimmed.to_owned())
}

/// One thing discarding does to one path.
///
/// The plan is built in full before any of it runs, because a plan that cannot
/// be built must not run the half of itself that it could.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Discard {
    /// HEAD holds this path: `git restore --source=HEAD`, index and worktree.
    Restore(String),
    /// HEAD has never held it, so there is nothing to restore it *from*: the
    /// app's trash, and then whatever index entry it left behind.
    Trash(String),
    /// HEAD has never held it and the worktree no longer has it either — `AD`,
    /// staged and then deleted. The index entry is the whole of what is left,
    /// and there is nothing to trash: saying "no such file" here would be
    /// reporting the very state the user asked us to clean up.
    Forget(String),
}

/// What discarding one row actually means, decided from the status letters
/// rather than guessed at the git command line.
///
/// The rule that shapes all of it: **`git restore --source=HEAD` on a path
/// HEAD does not have is not refused.** It removes the path from the index and
/// the worktree and exits 0, so anything the user typed into a file git has
/// never hashed is gone with no copy anywhere. Every case below therefore has
/// to know whether HEAD holds the name before that command can see it.
///
/// A submodule is the one row this cannot tell apart: porcelain v1 spells a
/// dirty submodule ` M sub`, exactly like a file, and the restore below touches
/// only the gitlink in the index — which is why the argv says
/// `--no-recurse-submodules` rather than trusting the user's `submodule.recurse`.
pub(crate) fn discard_steps(
    path: &str,
    known: Option<&FileChange>,
) -> Result<Vec<Discard>, String> {
    let Some(change) = known else {
        // The panel discards rows it was given by the last status run, so this
        // is a path we have no letters for — and without letters there is no
        // way to tell a restore from a delete. Refusing is the only safe
        // answer: the old code assumed `git restore` would refuse for us.
        return Err(format!(
            "Seeker has no git status for {path:?}. Let the panel refresh and try again."
        ));
    };
    if change.is_conflicted() || change.x == b'U' || change.y == b'U' {
        // `git restore --source=HEAD` on an unmerged path *succeeds*: it takes
        // "ours", marks the path resolved and staged, and leaves `MERGE_HEAD`
        // set, so the panel goes quiet and the next commit drops the incoming
        // side without ever saying so. There is no version of that which is
        // what the user meant by "discard".
        return Err(format!(
            "{path} has a merge conflict. Resolve it in the editor and stage the result — \
             discarding it would keep one side of the merge and say nothing."
        ));
    }
    // `Y` of `D` is git saying the worktree has already lost the file.
    let away = if change.y == b'D' {
        Discard::Forget(change.path.clone())
    } else {
        Discard::Trash(change.path.clone())
    };
    match change.x {
        // A rename is two paths and both have to move. The old name is what
        // HEAD holds, so it is restored; the new name is one HEAD has never
        // seen — whatever the user has typed into it since is unhashed and
        // unrecoverable — so it goes to the trash, never under `git restore`.
        b'R' => {
            let Some(original) = &change.original else {
                return Err(format!(
                    "Seeker cannot tell where {path} was renamed from, so it will not \
                     discard it. Use the terminal."
                ));
            };
            Ok(vec![Discard::Restore(original.clone()), away])
        }
        // A copy leaves its source alone: only the copy is new, and only the
        // copy goes.
        b'C' | b'A' | b'?' => Ok(vec![away]),
        _ => Ok(vec![Discard::Restore(change.path.clone())]),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    /// Build porcelain output the way git does: NUL after every record.
    fn porcelain(records: &[&str]) -> Vec<u8> {
        let mut out = Vec::new();
        for record in records {
            out.extend_from_slice(record.as_bytes());
            out.push(0);
        }
        out
    }

    fn statuses(records: &[&str]) -> Vec<(String, GitStatus)> {
        parse_records(&porcelain(records))
    }

    #[test]
    fn empty_output_is_a_clean_repository() {
        assert!(parse_records(b"").is_empty());
        assert!(parse_porcelain(b"", "").is_empty());
        // git emits nothing at all when clean, but a stray trailing NUL must
        // not become a phantom entry either.
        assert!(parse_records(b"\0\0").is_empty());
    }

    #[test]
    fn maps_the_ordinary_codes() {
        assert_eq!(
            statuses(&[
                " M src/main.rs",
                "M  staged.rs",
                "MM both.rs",
                "A  new.rs",
                " D gone.rs",
                "D  staged-delete.rs",
                "?? untracked.rs",
                "T  typechange.rs",
            ]),
            vec![
                ("src/main.rs".to_owned(), GitStatus::Modified),
                ("staged.rs".to_owned(), GitStatus::Modified),
                ("both.rs".to_owned(), GitStatus::Modified),
                ("new.rs".to_owned(), GitStatus::Added),
                ("gone.rs".to_owned(), GitStatus::Deleted),
                ("staged-delete.rs".to_owned(), GitStatus::Deleted),
                ("untracked.rs".to_owned(), GitStatus::Untracked),
                ("typechange.rs".to_owned(), GitStatus::Modified),
            ]
        );
    }

    #[test]
    fn a_rename_consumes_its_source_record() {
        // The trap: `R  new` is followed by a bare record holding the old
        // path. Read naively it looks like a status line whose code letters
        // are the first two characters of a filename.
        let parsed = statuses(&[
            "R  src/new.rs",
            "src/old.rs",
            " M after.rs",
            "C  copy.rs",
            "src/source.rs",
            "?? last.rs",
        ]);
        assert_eq!(
            parsed,
            vec![
                ("src/new.rs".to_owned(), GitStatus::Renamed),
                ("after.rs".to_owned(), GitStatus::Modified),
                ("copy.rs".to_owned(), GitStatus::Renamed),
                ("last.rs".to_owned(), GitStatus::Untracked),
            ]
        );
        // The source path is gone from the report entirely — not reported as
        // deleted, and above all not mistaken for a status record.
        assert!(!parsed.iter().any(|(path, _)| path == "src/old.rs"));
        assert!(!parsed.iter().any(|(path, _)| path.contains("source")));
    }

    #[test]
    fn a_rename_with_a_modification_is_still_a_rename() {
        assert_eq!(
            statuses(&["RM moved.rs", "original.rs"]),
            vec![("moved.rs".to_owned(), GitStatus::Renamed)]
        );
    }

    /// The source record is consumed, and *kept*: it is the only name HEAD
    /// knows the file by, and without it discarding a rename cannot be done at
    /// all — see [`discard_steps`].
    #[test]
    fn a_rename_keeps_the_name_head_knows_it_by() {
        let changes = parse_changes(&porcelain(&["RM renamed.txt", "a.txt", " M after.rs"]));
        assert_eq!(changes[0].path, "renamed.txt");
        assert_eq!(changes[0].original.as_deref(), Some("a.txt"));
        // And the new name is *not* in HEAD, whatever the letters look like.
        assert!(!changes[0].in_head());

        assert_eq!(changes[1].path, "after.rs");
        assert_eq!(changes[1].original, None);
        assert!(changes[1].in_head());

        // A rename out of a subdirectory the project does not contain has no
        // source this project can name.
        let outside = rebase(
            parse_changes(&porcelain(&["R  app/moved.rs", "lib/old.rs"])),
            "app/",
        );
        assert_eq!(outside[0].path, "moved.rs");
        assert_eq!(outside[0].original, None);
    }

    /// The plan behind the most dangerous button in the app.
    #[test]
    fn discarding_a_rename_restores_the_old_name_and_never_restores_the_new_one() {
        let changes = parse_changes(&porcelain(&["RM renamed.txt", "a.txt"]));
        // `git restore --source=HEAD -- renamed.txt` would *delete* it — HEAD
        // has no such path — and exit 0. So: a.txt comes back from HEAD, and
        // renamed.txt, whose content git has never hashed, goes to the trash.
        assert_eq!(
            discard_steps("renamed.txt", changes.first()).unwrap(),
            vec![
                Discard::Restore("a.txt".to_owned()),
                Discard::Trash("renamed.txt".to_owned()),
            ]
        );
        assert!(
            !discard_steps("renamed.txt", changes.first())
                .unwrap()
                .contains(&Discard::Restore("renamed.txt".to_owned()))
        );

        // A copy's source was not touched, so only the copy goes.
        let copied = parse_changes(&porcelain(&["C  copy.rs", "source.rs"]));
        assert_eq!(
            discard_steps("copy.rs", copied.first()).unwrap(),
            vec![Discard::Trash("copy.rs".to_owned())]
        );

        // A rename whose source we could not read is refused rather than
        // half-done.
        let sourceless = FileChange {
            path: "renamed.txt".to_owned(),
            original: None,
            x: b'R',
            y: b'M',
        };
        assert!(discard_steps("renamed.txt", Some(&sourceless)).is_err());
    }

    #[test]
    fn discarding_decides_restore_or_trash_from_the_letters() {
        let changes = parse_changes(&porcelain(&[
            " M tracked.rs",
            "M  staged.rs",
            " D deleted.rs",
            "A  new.rs",
            "AD staged-then-deleted.rs",
            "?? untracked.rs",
            "?? newdir/",
        ]));
        let steps = |path: &str| {
            discard_steps(path, changes.iter().find(|change| change.path == path)).unwrap()
        };
        for path in ["tracked.rs", "staged.rs", "deleted.rs"] {
            assert_eq!(steps(path), vec![Discard::Restore(path.to_owned())]);
        }
        for path in ["new.rs", "untracked.rs", "newdir/"] {
            assert_eq!(steps(path), vec![Discard::Trash(path.to_owned())]);
        }
        // `AD`: staged, then deleted from the worktree. There is nothing to
        // trash and the index entry is the whole of what discarding it means,
        // so "no such file" is not a failure to report — it is the state the
        // user asked us to clear.
        assert_eq!(
            steps("staged-then-deleted.rs"),
            vec![Discard::Forget("staged-then-deleted.rs".to_owned())]
        );
        // The same for a rename whose destination has since been deleted.
        let renamed_away = parse_changes(&porcelain(&["RD gone.rs", "was.rs"]));
        assert_eq!(
            discard_steps("gone.rs", renamed_away.first()).unwrap(),
            vec![
                Discard::Restore("was.rs".to_owned()),
                Discard::Forget("gone.rs".to_owned()),
            ]
        );
    }

    /// Discard is the one command that must not guess. A conflict resolved by
    /// `git restore --source=HEAD` keeps "ours", stages it, and leaves
    /// `MERGE_HEAD` set — the panel then shows a clean tree and the next commit
    /// drops the incoming side without a word.
    #[test]
    fn discarding_refuses_a_conflict_and_anything_it_has_no_status_for() {
        for code in ["DD", "AU", "UD", "UA", "DU", "AA", "UU"] {
            let changes = parse_changes(&porcelain(&[&format!("{code} conflict.rs")]));
            let refusal = discard_steps("conflict.rs", changes.first()).unwrap_err();
            assert!(
                refusal.contains("merge conflict"),
                "{code} should be refused by name, got {refusal:?}"
            );
        }
        // No status at all: without the letters there is no way to tell a
        // restore from a delete, and the old code called that "tracked".
        let refusal = discard_steps("mystery.rs", None).unwrap_err();
        assert!(refusal.contains("no git status"), "got {refusal:?}");
    }

    #[test]
    fn every_unmerged_pair_is_a_conflict() {
        for code in ["DD", "AU", "UD", "UA", "DU", "AA", "UU"] {
            let record = format!("{code} conflict.rs");
            assert_eq!(
                statuses(&[&record]),
                vec![("conflict.rs".to_owned(), GitStatus::Conflicted)],
                "{code} should be a conflict"
            );
        }
    }

    #[test]
    fn paths_are_raw_bytes_because_of_minus_z() {
        // `-z` turns off git's C-style quoting, so a path with a quote, a
        // backslash, a space or a newline arrives verbatim and must survive
        // verbatim. Without `-z` this record would read `"od\303\251on"`.
        let parsed = statuses(&[
            " M répertoire/fichier éé.rs",
            "?? 日本語/ファイル.txt",
            " M weird \"quoted\"\\name.rs",
            " M with\nnewline.rs",
        ]);
        assert_eq!(
            parsed,
            vec![
                ("répertoire/fichier éé.rs".to_owned(), GitStatus::Modified),
                ("日本語/ファイル.txt".to_owned(), GitStatus::Untracked),
                ("weird \"quoted\"\\name.rs".to_owned(), GitStatus::Modified),
                ("with\nnewline.rs".to_owned(), GitStatus::Modified),
            ]
        );
    }

    #[test]
    fn invalid_bytes_do_not_lose_the_record() {
        // 0xff is not UTF-8. The path is mangled; the status still arrives.
        let mut output = b" M bad".to_vec();
        output.push(0xff);
        output.extend_from_slice(b".rs");
        output.push(0);
        let parsed = parse_records(&output);
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].1, GitStatus::Modified);
        assert!(parsed[0].0.starts_with("bad"));
    }

    #[test]
    fn truncated_records_are_dropped_not_panicked_on() {
        assert!(parse_records(b"M\0").is_empty());
        assert!(parse_records(b"M  \0").is_empty());
        assert!(parse_records(b"?\0").is_empty());
        // A record whose third byte isn't a space isn't a status line.
        assert!(parse_records(b"MMMnot-a-record\0").is_empty());
    }

    #[test]
    fn directories_roll_up_to_a_summary() {
        let rolled = parse_porcelain(
            &porcelain(&[
                " M src/deep/nested/edited.rs",
                "?? docs/new.md",
                "A  assets/icons/added.svg",
                "UU merge/conflicted.rs",
            ]),
            "",
        );

        // Files keep their own status.
        assert_eq!(
            rolled.get("src/deep/nested/edited.rs"),
            Some(&GitStatus::Modified)
        );
        // Every ancestor is present, so the panel needs one lookup per row.
        assert_eq!(rolled.get("src"), Some(&GitStatus::Modified));
        assert_eq!(rolled.get("src/deep"), Some(&GitStatus::Modified));
        assert_eq!(rolled.get("src/deep/nested"), Some(&GitStatus::Modified));
        // New-only directories stay in the "new" tier.
        assert_eq!(rolled.get("docs"), Some(&GitStatus::Untracked));
        assert_eq!(rolled.get("assets"), Some(&GitStatus::Added));
        assert_eq!(rolled.get("assets/icons"), Some(&GitStatus::Added));
        // A conflict outranks everything.
        assert_eq!(rolled.get("merge"), Some(&GitStatus::Conflicted));
        // The project root is never an entry: "" has no separator before it.
        assert!(!rolled.contains_key(""));
    }

    #[test]
    fn the_loudest_descendant_wins() {
        let rolled = roll_up(&[
            ("a/untracked.rs".to_owned(), GitStatus::Untracked),
            ("a/added.rs".to_owned(), GitStatus::Added),
            ("a/deleted.rs".to_owned(), GitStatus::Deleted),
        ]);
        // Deleted sits in the "changed" tier, which beats both new ones.
        assert_eq!(rolled.get("a"), Some(&GitStatus::Modified));

        let rolled = roll_up(&[
            ("b/modified.rs".to_owned(), GitStatus::Modified),
            ("b/conflict.rs".to_owned(), GitStatus::Conflicted),
        ]);
        assert_eq!(rolled.get("b"), Some(&GitStatus::Conflicted));

        // Order must not matter: the conflict is seen first here.
        let rolled = roll_up(&[
            ("b/conflict.rs".to_owned(), GitStatus::Conflicted),
            ("b/modified.rs".to_owned(), GitStatus::Modified),
        ]);
        assert_eq!(rolled.get("b"), Some(&GitStatus::Conflicted));
    }

    #[test]
    fn ignored_files_never_colour_their_parents() {
        let rolled = roll_up(&[("target/debug/thing".to_owned(), GitStatus::Ignored)]);
        assert_eq!(rolled.get("target/debug/thing"), Some(&GitStatus::Ignored));
        assert_eq!(rolled.get("target"), None);
        assert_eq!(rolled.get("target/debug"), None);
    }

    #[test]
    fn a_project_below_the_repository_root_gets_relative_paths() {
        // Porcelain paths are relative to the repository root, so a project
        // that is a subdirectory of one has to be re-based — and anything
        // outside the project simply isn't its business.
        let rolled = parse_porcelain(
            &porcelain(&[
                " M apps/editor/src/main.rs",
                " M apps/other/src/main.rs",
                "?? README.md",
            ]),
            "apps/editor/",
        );
        assert_eq!(rolled.get("src/main.rs"), Some(&GitStatus::Modified));
        assert_eq!(rolled.get("src"), Some(&GitStatus::Modified));
        assert_eq!(rolled.len(), 2);
    }

    /// git's own half of the command line, spelled out. guest.rs pins the
    /// proot half the same way, and between them the whole thing is covered —
    /// which matters more now that the two halves are written in different
    /// files, because a flag lost in either is invisible until a phone shows
    /// an uncoloured panel.
    /// The bind and the two variables are as load-bearing as the argv and were
    /// pinned by nothing: dropping the bind silently reports no status for an
    /// imported project whose repository lives elsewhere, and dropping
    /// GIT_TERMINAL_PROMPT hangs every poll of a private https remote until
    /// the deadline kills it. Both stay green in every other test here.
    #[test]
    fn the_git_command_binds_the_repository_and_silences_prompts() {
        let command = git_command(
            "git status",
            Path::new("/elsewhere/repo"),
            git_argv(Path::new("/elsewhere/repo/sub"), &status_args()),
        );
        assert_eq!(command.binds(), [PathBuf::from("/elsewhere/repo")]);
        let env: Vec<(String, String)> = command
            .env_pairs()
            .iter()
            .map(|(k, v)| {
                (
                    k.to_string_lossy().into_owned(),
                    v.to_string_lossy().into_owned(),
                )
            })
            .collect();
        assert_eq!(
            env,
            vec![
                ("GIT_OPTIONAL_LOCKS".to_owned(), "0".to_owned()),
                ("GIT_TERMINAL_PROMPT".to_owned(), "0".to_owned()),
            ]
        );
    }

    /// Everything `git_argv` produces, for the query and for one command each
    /// way it can be shaped. What is pinned is the *order*: git-level options
    /// before the subcommand, `--` before any path, and paths last.
    #[test]
    fn the_git_argv_is_exactly_this() {
        let project = Path::new("/files/projects/thing");
        assert_eq!(
            argv_strings(git_argv(project, &status_args())),
            vec![
                "git",
                "-C",
                "/files/projects/thing",
                "-c",
                "safe.directory=*",
                "--literal-pathspecs",
                "--no-optional-locks",
                "status",
                "--porcelain=v1",
                "-z",
                "--branch",
                "--untracked-files=normal",
            ]
        );

        let args: Vec<OsString> = ["add", "-A", "--", "src/main.rs"]
            .iter()
            .map(OsString::from)
            .collect();
        assert_eq!(
            argv_strings(git_argv(project, &args)),
            vec![
                "git",
                "-C",
                "/files/projects/thing",
                "-c",
                "safe.directory=*",
                "--literal-pathspecs",
                "add",
                "-A",
                "--",
                "src/main.rs",
            ]
        );
    }

    fn argv_strings(argv: Vec<OsString>) -> Vec<String> {
        argv.iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect()
    }

    /// The wrapper is the only reason a command's failure is visible at all,
    /// and every part of it is load-bearing: `sh -c` with the argv as
    /// positional parameters (so a file name can never become shell syntax),
    /// `2>&1` (so git's complaint survives), and the marker (so the exit status
    /// does, through a `capture` that only reports success).
    #[test]
    fn a_command_runs_under_a_wrapper_that_reports_what_git_said() {
        let argv = argv_strings(wrapped_argv(git_argv(Path::new("/repo"), &["add", "-A"])));
        assert_eq!(argv[0], "/bin/sh");
        assert_eq!(argv[1], "-c");
        assert_eq!(argv[2], r#""$@" 2>&1; printf 'seeker-exit:%d' "$?""#);
        // `$0` is not the program: git starts at `$1`, which is what `"$@"`
        // expands to.
        assert_eq!(argv[3], "seeker-git");
        assert_eq!(argv[4], "git");
    }

    #[test]
    fn a_run_is_read_back_from_the_last_marker() {
        let run = parse_run(b"nothing to commit\nseeker-exit:1").unwrap();
        assert_eq!(run.status, 1);
        assert_eq!(run.message(), "nothing to commit");

        let run = parse_run(b"seeker-exit:0").unwrap();
        assert_eq!(run.status, 0);

        // git printing the marker itself — a file with that name, a commit
        // message quoting one — must not be able to fake a success.
        let run = parse_run(b"error: seeker-exit:0 is unmerged\nseeker-exit:1").unwrap();
        assert_eq!(run.status, 1);
        assert_eq!(run.message(), "seeker-exit:0 is unmerged");

        // The wrapper never ran, so there is nothing to believe.
        assert!(parse_run(b"killed").is_err());
    }

    #[test]
    fn a_split_run_keeps_the_two_streams_apart() {
        let run = parse_split_run(
            b"Updating abc..def\n 1 file changed\nseeker-stderr:From github.com:x/y\nseeker-exit:0",
        )
        .unwrap();
        assert_eq!(run.status, 0);
        assert_eq!(run.stdout, "Updating abc..def\n 1 file changed\n");
        assert_eq!(run.stderr, "From github.com:x/y\n");

        // Both streams empty is still an answer — a fetch with nothing to
        // say, which is exactly the "Already up to date" toast's evidence.
        let run = parse_split_run(b"seeker-stderr:seeker-exit:0").unwrap();
        assert_eq!(run.status, 0);
        assert!(run.stdout.is_empty() && run.stderr.is_empty());

        // A failure's one-liner comes off stderr, where git says why — the
        // shared summarizer over stdout-then-stderr finds the marked line.
        let run = parse_split_run(
            b"seeker-stderr:fatal: could not read from remote repository\nseeker-exit:128",
        )
        .unwrap();
        assert_eq!(run.status, 128);
        assert_eq!(
            failure_line(&format!("{}\n{}", run.stdout, run.stderr), run.status),
            "could not read from remote repository"
        );

        // Half a wrapper is no wrapper: without both markers there is
        // nothing to believe.
        assert!(parse_split_run(b"seeker-exit:0").is_err());
        assert!(parse_split_run(b"killed").is_err());
    }

    /// The split wrapper through the host's real `sh`, with a command that
    /// writes to both streams — every assertion above is about bytes we wrote
    /// ourselves, and the wrapper is shell this test can actually run.
    #[test]
    fn the_real_shell_runs_the_split_wrapper() {
        let out = Command::new("/bin/sh")
            .arg("-c")
            .arg(SPLIT_WRAPPER)
            .arg("seeker-git")
            .arg("/bin/sh")
            .arg("-c")
            .arg("printf 'out\\n'; printf 'err\\n' >&2; exit 3")
            .output()
            .expect("failed to run sh");
        let run = parse_split_run(&out.stdout).unwrap();
        assert_eq!(run.status, 3);
        assert_eq!(run.stdout, "out\n");
        assert_eq!(run.stderr, "err\n");

        // And with a TMPDIR naming a directory that does not exist — the
        // environment every Android app process hands its children, where
        // TMPDIR is the app's *host* cache directory, unreachable inside the
        // fake root. A wrapper that trusted it failed its own redirection
        // before the command ever ran: exit 2, both streams empty, and a
        // remote failure reduced to "git exited with 2" with nothing to show.
        let out = Command::new("/bin/sh")
            .arg("-c")
            .arg(SPLIT_WRAPPER)
            .arg("seeker-git")
            .arg("/bin/sh")
            .arg("-c")
            .arg("printf 'out\\n'; printf 'fatal: no\\n' >&2; exit 128")
            .env("TMPDIR", "/definitely/not/there")
            .output()
            .expect("failed to run sh");
        let run = parse_split_run(&out.stdout).unwrap();
        assert_eq!(run.status, 128, "the real exit status was lost");
        assert_eq!(run.stdout, "out\n");
        assert_eq!(run.stderr, "fatal: no\n", "stderr was lost");
    }

    #[test]
    fn a_message_is_the_line_git_marked_or_the_last_thing_it_said() {
        // git's own shape for an identity it cannot guess: a heading, a
        // paragraph of advice, and the actual reason at the bottom.
        let run = parse_run(
            b"Author identity unknown\n\n*** Please tell me who you are.\n\n\
              Run\n  git config --global user.email \"you@example.com\"\n\
              fatal: unable to auto-detect email address (got 'root@x.(none)')\n\
              seeker-exit:128",
        )
        .unwrap();
        assert_eq!(
            run.message(),
            "unable to auto-detect email address (got 'root@x.(none)')"
        );

        // And its shape for a commit with nothing staged, where nothing is
        // marked at all and the first line is the least useful one there is.
        let run = parse_run(
            b"On branch main\nChanges not staged for commit:\n\t\
              modified:   README\n\nnothing added to commit\nseeker-exit:1",
        )
        .unwrap();
        assert_eq!(run.message(), "nothing added to commit");

        // Nothing said at all: the status is the only thing left to report.
        let run = parse_run(b"seeker-exit:129").unwrap();
        assert_eq!(run.message(), "git exited with 129");
    }

    /// The paths reaching git come from a UI that got them from us — and this
    /// is the function that does not take that on trust, because what is on the
    /// other end of it deletes files.
    #[test]
    fn a_path_that_could_escape_the_project_is_refused() {
        assert!(checked_paths(&[]).is_err());
        for bad in [
            "",
            "/etc/passwd",
            "../outside.rs",
            "src/../../outside.rs",
            "src//main.rs",
            "with\0nul.rs",
        ] {
            assert!(
                checked_paths(&[bad.to_owned()]).is_err(),
                "{bad:?} should be refused"
            );
        }
        // One bad path refuses the whole list: a discard that silently did
        // two of three files is worse than one that did none.
        assert!(checked_paths(&["ok.rs".to_owned(), "../bad.rs".to_owned()]).is_err());

        assert_eq!(
            checked_paths(&["src/main.rs".to_owned(), "a b/-weird.rs".to_owned()]).unwrap(),
            vec!["src/main.rs".to_owned(), "a b/-weird.rs".to_owned()]
        );
    }

    /// `--untracked-files=normal` collapses a whole new directory into one
    /// record with a trailing slash, and that record is a row the panel draws.
    /// Refusing it refused every list it appeared in — which is to say "Stage
    /// all" stopped working the moment anyone made a directory.
    #[test]
    fn an_untracked_directory_is_a_path_like_any_other() {
        assert_eq!(
            checked_paths(&["newdir/".to_owned()]).unwrap(),
            vec!["newdir".to_owned()]
        );
        assert_eq!(
            checked_paths(&["src/feature/".to_owned(), "src/main.rs".to_owned()]).unwrap(),
            vec!["src/feature".to_owned(), "src/main.rs".to_owned()]
        );
        // The slash is *one* trailing slash and nothing else: everything the
        // rule above refuses stays refused.
        for bad in ["/", "//", "src//", "src/../"] {
            assert!(
                checked_paths(&[bad.to_owned()]).is_err(),
                "{bad:?} should be refused"
            );
        }
    }

    #[test]
    fn an_empty_commit_message_never_reaches_git() {
        let engine = crate::Engine::new();
        let dir = tempfile::tempdir().unwrap();
        let id = engine.open_project(dir.path());
        // Refused before anything is resolved — there is no userland here, and
        // the message says nothing about one.
        assert_eq!(
            engine.git_commit(id, "   \n\t ", false, false, false),
            Err("Write a commit message first".to_owned())
        );
    }

    #[test]
    fn commands_without_a_userland_say_so_rather_than_failing_silently() {
        let engine = crate::Engine::new();
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        let id = engine.open_project(dir.path());
        let paths = vec!["a.rs".to_owned()];
        for result in [
            engine.git_stage(id, &paths),
            engine.git_unstage(id, &paths),
            engine.git_discard(id, &paths),
            engine.git_commit(id, "a message", false, false, false),
        ] {
            assert_eq!(
                result,
                Err("The Linux userland is not installed".to_owned())
            );
        }
    }

    #[test]
    fn prefixes_come_from_the_repository_layout() {
        assert_eq!(
            relative_prefix(Path::new("/p/repo"), Path::new("/p/repo")),
            Some(String::new())
        );
        assert_eq!(
            relative_prefix(Path::new("/p/repo"), Path::new("/p/repo/apps/editor")),
            Some("apps/editor/".to_owned())
        );
        assert_eq!(
            relative_prefix(Path::new("/p/repo"), Path::new("/elsewhere")),
            None
        );
    }

    #[test]
    fn a_directory_without_a_git_is_not_a_repository() {
        let dir = tempfile::tempdir().unwrap();
        let project = dir.path().join("project/src");
        std::fs::create_dir_all(&project).unwrap();
        assert_eq!(repo_root_of(&project), None);

        // The repository root is found by walking up, not by assuming the
        // project is one.
        std::fs::create_dir_all(dir.path().join("project/.git")).unwrap();
        assert_eq!(repo_root_of(&project), Some(dir.path().join("project")));

        // A worktree or submodule has a `.git` *file*, which counts too.
        let sub = dir.path().join("sub");
        std::fs::create_dir_all(&sub).unwrap();
        std::fs::write(sub.join(".git"), "gitdir: /elsewhere\n").unwrap();
        assert_eq!(repo_root_of(&sub), Some(sub));
    }

    #[test]
    fn status_is_empty_without_a_userland() {
        // The play flavour, and the full flavour before the rootfs lands: the
        // query answers "nothing to show", never an error.
        let engine = crate::Engine::new();
        let dir = tempfile::tempdir().unwrap();
        let id = engine.open_project(dir.path());
        assert!(engine.git_status(id).is_empty());
        assert_eq!(engine.git_status_version(id), 0);
        assert_eq!(engine.git_branch(id), None);
        assert_eq!(engine.git_head(id), None);
    }

    /// [`read_head`] answers from the repository's files, so it has to agree
    /// with git in every shape a HEAD comes in — loose, packed, detached — and
    /// say "unknown" rather than something wrong for a branch with no commits.
    #[test]
    fn head_is_read_from_the_repository_files_and_agrees_with_git() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        assert!(
            host_git(repo, &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        // Unborn: `HEAD` names a ref that exists nowhere yet.
        assert_eq!(read_head(repo), None);

        std::fs::write(repo.join("README"), "one\n").unwrap();
        assert!(host_git(repo, &["add", "README"]).status.success());
        assert!(
            host_git(repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );
        let rev_parse = |repo: &Path| {
            let out = host_git(repo, &["rev-parse", "HEAD"]);
            String::from_utf8_lossy(&out.stdout).trim().to_owned()
        };
        // Loose: the ref is a file under `.git/refs/heads`.
        assert_eq!(read_head(repo).as_deref(), Some(rev_parse(repo).as_str()));

        // Packed: `git pack-refs` moves it into `.git/packed-refs`.
        assert!(
            host_git(repo, &["pack-refs", "--all", "--prune"])
                .status
                .success()
        );
        assert!(!repo.join(".git/refs/heads/main").exists());
        assert_eq!(read_head(repo).as_deref(), Some(rev_parse(repo).as_str()));

        // Detached: `HEAD` holds the id itself.
        let head = rev_parse(repo);
        assert!(
            host_git(repo, &["checkout", "--quiet", "--detach", &head])
                .status
                .success()
        );
        assert_eq!(read_head(repo).as_deref(), Some(head.as_str()));

        // A linked worktree: `.git` is a file, and the ref lives in the main
        // repository's git dir, reached through `commondir`.
        let linked = dir.path().join("linked");
        assert!(
            host_git(
                repo,
                &["worktree", "add", "--quiet", linked.to_str().unwrap(), "main"]
            )
            .status
            .success()
        );
        assert_eq!(read_head(&linked).as_deref(), Some(rev_parse(&linked).as_str()));
    }

    #[test]
    fn a_missing_proot_is_silent_too() {
        let engine = crate::Engine::new();
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        engine.set_userland(
            &dir.path().join("no-such-proot"),
            &dir.path().join("no-such-rootfs"),
            dir.path(),
            dir.path(),
        );
        let id = engine.open_project(dir.path());

        // Give the worktree time to mirror and the refresh to run and fail.
        let deadline = Instant::now() + Duration::from_secs(10);
        while Instant::now() < deadline && !engine.project_scan_complete(id) {
            thread::sleep(Duration::from_millis(10));
        }
        thread::sleep(DEBOUNCE * 2);

        assert!(engine.git_status(id).is_empty());
        assert_eq!(engine.git_status_version(id), 0);

        engine.clear_userland();
        assert!(engine.git_status(id).is_empty());
    }

    /// `RD` is "renamed in the index, deleted in the worktree". Its second
    /// record is the *source* path; a parser that decides whether to consume
    /// it from the classified status (Deleted) reads that source as the next
    /// status line and invents a path from it.
    #[test]
    fn rename_then_delete_does_not_desync_the_parse() {
        let porcelain = b"RD new.rs\0old.rs\0 M other.rs\0";
        let parsed = parse_records(porcelain);
        assert_eq!(
            parsed,
            vec![
                ("new.rs".to_string(), GitStatus::Deleted),
                ("other.rs".to_string(), GitStatus::Modified),
            ],
            "the rename source must be consumed, not parsed as a record"
        );
    }

    #[test]
    fn copy_then_delete_does_not_desync_the_parse() {
        let parsed = parse_records(b"CD copy.rs\0source.rs\0?? new.txt\0");
        assert_eq!(
            parsed,
            vec![
                ("copy.rs".to_string(), GitStatus::Deleted),
                ("new.txt".to_string(), GitStatus::Untracked),
            ]
        );
    }

    /// Run the host's git hermetically: no user, system or global config, and
    /// an identity of our own, so the result cannot depend on the machine.
    fn host_git(dir: &Path, args: &[&str]) -> std::process::Output {
        Command::new("git")
            .args(args)
            .current_dir(dir)
            .env("GIT_CONFIG_GLOBAL", "/dev/null")
            .env("GIT_CONFIG_SYSTEM", "/dev/null")
            // Run the suite from a git hook or `git rebase --exec` and these
            // are set, pointing every `git` below at the *outer* repository:
            // the argv would be accepted, the paths would be someone else's,
            // and the failure would read as an argv bug.
            .env_remove("GIT_DIR")
            .env_remove("GIT_WORK_TREE")
            .env_remove("GIT_INDEX_FILE")
            .env("GIT_AUTHOR_NAME", "test")
            .env("GIT_AUTHOR_EMAIL", "test@example.invalid")
            .env("GIT_COMMITTER_NAME", "test")
            .env("GIT_COMMITTER_EMAIL", "test@example.invalid")
            .output()
            .expect("failed to run git")
    }

    /// Every other test in this file feeds `parse_records` bytes we wrote
    /// ourselves, so all of them pass no matter what argv git is handed. That
    /// is exactly how `--no-optional-locks` came to sit *after* the
    /// subcommand: it is a git-level option, real git answered "unknown
    /// option" and exit 129 for months of runs, and the panel stayed
    /// colourless with nothing failing anywhere.
    ///
    /// So: run the real binary over the real argv, and read the real output.
    #[test]
    fn real_git_accepts_the_argv_and_the_output_parses() {
        // No git on this machine (a CI image can be that bare) — say so rather
        // than fail, and rather than pretend the check ran.
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }

        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        assert!(
            host_git(repo, &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        std::fs::write(repo.join("README"), "Hello World!\n").unwrap();
        assert!(host_git(repo, &["add", "README"]).status.success());
        assert!(
            host_git(repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );

        // The device's reproduction, exactly: one tracked file changed, one
        // new file that git has never seen.
        std::fs::write(repo.join("README"), "Hello World!\nx\n").unwrap();
        std::fs::write(repo.join("new.txt"), "").unwrap();

        let out = run_argv(repo, git_argv(repo, &status_args()));
        assert!(
            out.status.success(),
            "git rejected the argv we send on device:\n{}",
            String::from_utf8_lossy(&out.stderr)
        );

        let statuses = parse_porcelain(&out.stdout, "");
        assert_eq!(statuses.get("README"), Some(&GitStatus::Modified));
        assert_eq!(statuses.get("new.txt"), Some(&GitStatus::Untracked));

        // `--branch` is part of that same argv, and its record has to survive
        // the parse rather than arriving as a file called `main`.
        let branch = parse_branch(&out.stdout).expect("the header record is there");
        assert_eq!(branch.name.as_deref(), Some("main"));
        assert!(
            !parse_changes(&out.stdout)
                .iter()
                .any(|change| change.path.contains("main"))
        );
    }

    /// Run one of ours through the host's git, minus the program name.
    fn run_argv(dir: &Path, argv: Vec<OsString>) -> std::process::Output {
        let args: Vec<&str> = argv
            .iter()
            .skip(1) // the program name; `host_git` supplies it
            .map(|arg| arg.to_str().expect("argv is UTF-8 in this test"))
            .collect();
        host_git(dir, &args)
    }

    /// The same check for the four commands: real git, the real argv.
    ///
    /// Every other test of them asserts about strings we wrote ourselves, so
    /// all of them pass whatever flags the argv carries — which is exactly how
    /// `--no-optional-locks` came to sit after the subcommand for months. A
    /// command's version of that mistake is worse than the query's: the panel
    /// would report "unknown option" to the user for every stage and commit.
    #[test]
    fn real_git_accepts_the_command_argvs() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        assert!(
            host_git(repo, &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        std::fs::write(repo.join("README"), "one\n").unwrap();

        let staged: Vec<OsString> = ["add", "-A", "--", "README"]
            .iter()
            .map(OsString::from)
            .collect();
        let out = run_argv(repo, git_argv(repo, &staged));
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );

        let commit: Vec<OsString> = ["commit", "--quiet", "-m", "first commit"]
            .iter()
            .map(OsString::from)
            .collect();
        let out = run_argv(repo, git_argv(repo, &commit));
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );

        // Now the other three, over a file HEAD has: stage, unstage, restore.
        std::fs::write(repo.join("README"), "two\n").unwrap();
        for args in [
            vec!["add", "-A", "--", "README"],
            vec!["restore", "--staged", "--", "README"],
            vec![
                "restore",
                "--source=HEAD",
                "--staged",
                "--worktree",
                "--",
                "README",
            ],
        ] {
            let argv: Vec<OsString> = args.iter().map(OsString::from).collect();
            let out = run_argv(repo, git_argv(repo, &argv));
            assert!(
                out.status.success(),
                "git rejected {args:?}:\n{}",
                String::from_utf8_lossy(&out.stderr)
            );
        }
        // The restore put the committed content back, which is what discard
        // promises for a file HEAD knows.
        assert_eq!(
            std::fs::read_to_string(repo.join("README")).unwrap(),
            "one\n"
        );

        // And the fallback path for a repository with no commits at all.
        let fresh = tempfile::tempdir().unwrap();
        assert!(
            host_git(fresh.path(), &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        std::fs::write(fresh.path().join("new.rs"), "").unwrap();
        let argv: Vec<OsString> = ["add", "-A", "--", "new.rs"]
            .iter()
            .map(OsString::from)
            .collect();
        assert!(
            run_argv(fresh.path(), git_argv(fresh.path(), &argv))
                .status
                .success()
        );
        // `restore --staged` is the one that cannot work without a HEAD — the
        // reason `git_unstage` has a second command at all.
        let argv: Vec<OsString> = ["restore", "--staged", "--", "new.rs"]
            .iter()
            .map(OsString::from)
            .collect();
        assert!(
            !run_argv(fresh.path(), git_argv(fresh.path(), &argv))
                .status
                .success(),
            "an unborn HEAD should refuse `restore --staged`; the fallback exists for it"
        );
        let argv: Vec<OsString> = ["rm", "--cached", "--quiet", "-r", "--", "new.rs"]
            .iter()
            .map(OsString::from)
            .collect();
        assert!(
            run_argv(fresh.path(), git_argv(fresh.path(), &argv))
                .status
                .success()
        );
    }

    /// Zed's flag order, exactly — the tooltip previews `git commit --amend
    /// --signoff --no-verify` in that order and the argv must be the command
    /// it previews (git_panel.rs:6097-6109, repository.rs:2638-2663).
    #[test]
    fn the_commit_argv_carries_zeds_flags_in_zeds_order() {
        let all: Vec<OsString> = [
            "commit",
            "--quiet",
            "-m",
            "msg",
            "--cleanup=strip",
            "--amend",
            "--signoff",
            "--no-verify",
        ]
        .iter()
        .map(OsString::from)
        .collect();
        assert_eq!(commit_args("msg", true, true, true), all);

        let plain: Vec<OsString> = ["commit", "--quiet", "-m", "msg", "--cleanup=strip"]
            .iter()
            .map(OsString::from)
            .collect();
        assert_eq!(commit_args("msg", false, false, false), plain);

        // Each flag stands alone: skipping hooks must not imply amending.
        assert!(
            commit_args("msg", false, false, true)
                .contains(&OsString::from("--no-verify"))
        );
        assert!(
            !commit_args("msg", false, false, true)
                .contains(&OsString::from("--amend"))
        );
    }

    /// The uncommit confirmation's evidence: remote refs that hold HEAD, with
    /// the two shapes that must *not* count — a `…/HEAD` symref, and a local
    /// ref that somehow got in.
    #[test]
    fn pushed_remotes_drop_head_symrefs_and_keep_the_short_names() {
        assert_eq!(
            parse_pushed_remotes(
                "refs/remotes/origin/HEAD\nrefs/remotes/origin/main\n  refs/remotes/fork/main  \nrefs/heads/main\n"
            ),
            vec!["origin/main".to_owned(), "fork/main".to_owned()]
        );
        assert!(parse_pushed_remotes("").is_empty());
    }

    /// The new command argvs — commit with Zed's flags, uncommit, init —
    /// through the real binary, for the same reason as the test above: every
    /// flag test asserts about strings we wrote ourselves.
    #[test]
    fn real_git_accepts_the_commit_flags_uncommit_and_init() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();

        // `git init -b <branch>` is the argv `git_init` sends.
        let argv: Vec<OsString> = ["init", "-b", "trunk"].iter().map(OsString::from).collect();
        let out = run_argv(repo, git_argv(repo, &argv));
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );

        std::fs::write(repo.join("README"), "one\n").unwrap();
        assert!(host_git(repo, &["add", "README"]).status.success());
        let out = run_argv(repo, git_argv(repo, &commit_args("first", false, false, false)));
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );

        std::fs::write(repo.join("README"), "two\n").unwrap();
        assert!(host_git(repo, &["add", "README"]).status.success());
        let out = run_argv(repo, git_argv(repo, &commit_args("second", false, false, false)));
        assert!(out.status.success());

        // Uncommit: exactly `git reset --soft HEAD^`, and the undone commit's
        // content stays staged — that is what "soft" promises the panel.
        let argv: Vec<OsString> = ["reset", "--soft", "HEAD^"]
            .iter()
            .map(OsString::from)
            .collect();
        let out = run_argv(repo, git_argv(repo, &argv));
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        let staged = host_git(repo, &["diff", "--cached", "--name-only"]);
        assert!(String::from_utf8_lossy(&staged.stdout).contains("README"));

        // And amend with every flag: the staged change folds into `first`.
        let out = run_argv(repo, git_argv(repo, &commit_args("first, again", true, true, true)));
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        let log = host_git(repo, &["log", "--format=%s"]);
        assert_eq!(
            String::from_utf8_lossy(&log.stdout).trim(),
            "first, again",
            "one commit, the amended one"
        );
        // `--signoff` really was in effect: the trailer names the committer.
        let body = host_git(repo, &["log", "-1", "--format=%b"]);
        assert!(String::from_utf8_lossy(&body.stdout).contains("Signed-off-by: test"));
    }

    #[test]
    fn the_branch_header_is_read_in_every_shape_git_writes_it() {
        let branch = |header: &str| parse_branch(&porcelain(&[header])).unwrap();

        assert_eq!(branch("## main").name.as_deref(), Some("main"));

        let tracked = branch("## main...origin/main");
        assert_eq!(tracked.name.as_deref(), Some("main"));
        assert_eq!((tracked.ahead, tracked.behind), (0, 0));

        let drifted = branch("## feature/x...origin/feature/x [ahead 12, behind 3]");
        assert_eq!(drifted.name.as_deref(), Some("feature/x"));
        assert_eq!((drifted.ahead, drifted.behind), (12, 3));

        let ahead = branch("## main...origin/main [ahead 1]");
        assert_eq!((ahead.ahead, ahead.behind), (1, 0));
        assert!(!ahead.upstream_gone);

        // The upstream is configured but deleted on the remote — the shape
        // that turns the remote button into "Republish".
        let gone = branch("## feature...origin/feature [gone]");
        assert_eq!(gone.upstream.as_deref(), Some("origin/feature"));
        assert!(gone.upstream_gone);
        assert_eq!((gone.ahead, gone.behind), (0, 0));

        let unborn = branch("## No commits yet on main");
        assert_eq!(unborn.name.as_deref(), Some("main"));
        assert!(unborn.unborn);

        // A detached HEAD is on no branch, and saying it is on one called
        // "HEAD (no branch)" would be a lie the panel would print.
        let detached = branch("## HEAD (no branch)");
        assert_eq!(detached.name, None);

        // No header at all — `--branch` not passed, or output from an older
        // cache — is "we don't know", not a branch called "".
        assert_eq!(parse_branch(&porcelain(&[" M a.rs"])), None);
    }

    #[test]
    fn the_branch_header_is_not_a_changed_file() {
        let output = porcelain(&["## main...origin/main [ahead 1]", " M src/main.rs"]);
        let changes = parse_changes(&output);
        assert_eq!(changes.len(), 1);
        assert_eq!(changes[0].path, "src/main.rs");
    }

    /// The whole point of keeping both letters: which section a file is in.
    #[test]
    fn a_file_can_be_staged_and_unstaged_at_once() {
        let changes = parse_changes(&porcelain(&[
            "M  staged.rs",
            " M unstaged.rs",
            "MM both.rs",
            "A  new.rs",
            "?? untracked.rs",
            "D  staged-delete.rs",
            " D deleted.rs",
            "UU conflict.rs",
        ]));
        let by_path = |name: &str| {
            changes
                .iter()
                .find(|change| change.path == name)
                .unwrap_or_else(|| panic!("{name} is missing"))
        };

        assert_eq!(by_path("staged.rs").staged(), Some(GitStatus::Modified));
        assert_eq!(by_path("staged.rs").unstaged(), None);
        assert_eq!(by_path("unstaged.rs").staged(), None);
        assert_eq!(by_path("unstaged.rs").unstaged(), Some(GitStatus::Modified));
        // The same file in both sections, which is what `MM` means and what a
        // single rolled-up status cannot say.
        assert_eq!(by_path("both.rs").staged(), Some(GitStatus::Modified));
        assert_eq!(by_path("both.rs").unstaged(), Some(GitStatus::Modified));
        assert_eq!(by_path("new.rs").staged(), Some(GitStatus::Added));
        assert_eq!(by_path("untracked.rs").staged(), None);
        assert_eq!(
            by_path("untracked.rs").unstaged(),
            Some(GitStatus::Untracked)
        );
        assert_eq!(
            by_path("staged-delete.rs").staged(),
            Some(GitStatus::Deleted)
        );
        assert_eq!(by_path("deleted.rs").unstaged(), Some(GitStatus::Deleted));

        // A conflict is in neither section: it is a decision, not a change.
        assert!(by_path("conflict.rs").is_conflicted());
        assert_eq!(by_path("conflict.rs").staged(), None);
        assert_eq!(by_path("conflict.rs").unstaged(), None);

        // And which of them discarding can restore rather than trash.
        assert!(by_path("staged.rs").in_head());
        assert!(!by_path("new.rs").in_head());
        assert!(!by_path("untracked.rs").in_head());
    }

    /// Stand in for the guest, so the commands can be run end to end on a host
    /// with no rootfs.
    ///
    /// It drops every flag up to and including `-w <dir>` and execs the rest —
    /// the contract `guest::proot_command` relies on — and then does the one
    /// other thing a real guest does that these commands depend on: it gives
    /// git a `HOME` that exists and a config it can read. Inside proot that is
    /// `/root` in the Debian rootfs; here it is a directory of the test's own,
    /// with an identity in it, because a git with nowhere to read a config from
    /// refuses to commit and the point of this test is that a commit works.
    #[cfg(unix)]
    fn fake_guest(dir: &Path) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;

        let home = dir.join("home");
        std::fs::create_dir_all(&home).unwrap();
        std::fs::write(
            home.join(".gitconfig"),
            "[user]\n\tname = test\n\temail = test@example.invalid\n",
        )
        .unwrap();

        let path = dir.join("fake-proot");
        std::fs::write(
            &path,
            format!(
                "#!/bin/sh\n\
                 while [ \"$1\" != \"-w\" ]; do shift; done\n\
                 shift 2\n\
                 HOME={home}\n\
                 GIT_CONFIG_GLOBAL={home}/.gitconfig\n\
                 GIT_CONFIG_SYSTEM=/dev/null\n\
                 export HOME GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM\n\
                 exec \"$@\"\n",
                home = home.display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        path
    }

    /// The commands, end to end: our argv, our shell wrapper, a real `/bin/sh`,
    /// a real git and a real repository.
    ///
    /// Every other test of the commands checks a string we built. This one
    /// checks that the thing we built *works* — that the wrapper passes the
    /// arguments through, that git's exit status survives a `capture` which
    /// reports only success, and that a failure comes back as the sentence git
    /// actually wrote.
    #[test]
    #[cfg(unix)]
    fn the_commands_reach_git_and_report_what_it_said() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let projects = dir.path().join("projects");
        let repo = projects.join("thing");
        std::fs::create_dir_all(&repo).unwrap();
        assert!(
            host_git(&repo, &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        std::fs::write(repo.join("README"), "one\n").unwrap();
        assert!(host_git(&repo, &["add", "README"]).status.success());
        assert!(
            host_git(&repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );

        let engine = crate::Engine::new();
        engine.set_userland(&fake_guest(dir.path()), dir.path(), dir.path(), &projects);
        let id = engine.open_project(&repo);

        // Staging a modification, and reading it back through the panel's own
        // query rather than through git.
        std::fs::write(repo.join("README"), "two\n").unwrap();
        std::fs::write(repo.join("new.rs"), "fn main() {}\n").unwrap();
        engine.git_stage(id, &["README".to_owned()]).unwrap();
        let staged = await_change(&engine, id, "README");
        assert_eq!(staged.staged, Some(GitStatus::Modified));
        assert_eq!(staged.unstaged, None);
        assert!(staged.in_head);

        // …and back out of the index again.
        engine.git_unstage(id, &["README".to_owned()]).unwrap();
        let unstaged = await_change(&engine, id, "README");
        assert_eq!(unstaged.staged, None);
        assert_eq!(unstaged.unstaged, Some(GitStatus::Modified));

        // Discarding a tracked file restores what HEAD has; discarding an
        // untracked one takes it out of the worktree, and not with `rm`.
        engine
            .git_discard(id, &["README".to_owned(), "new.rs".to_owned()])
            .unwrap();
        assert_eq!(
            std::fs::read_to_string(repo.join("README")).unwrap(),
            "one\n"
        );
        assert!(!repo.join("new.rs").exists());

        // Committing with nothing staged is git's own refusal, in git's own
        // words — which is the whole reason the wrapper exists.
        let refused = engine
            .git_commit(id, "nothing here", false, false, false)
            .unwrap_err();
        assert!(
            refused.to_lowercase().contains("nothing"),
            "expected git's own complaint, got {refused:?}"
        );

        // And a real commit lands.
        std::fs::write(repo.join("README"), "three\n").unwrap();
        engine.git_stage(id, &["README".to_owned()]).unwrap();
        engine.git_commit(id, "second", false, false, false).unwrap();
        let log = host_git(&repo, &["log", "--format=%s"]);
        assert_eq!(String::from_utf8_lossy(&log.stdout).trim(), "second\nfirst");
    }

    /// A failing remote command through the whole split-stream chain —
    /// [`run_git_split`], the fake guest, a real `/bin/sh`, a real git — in
    /// the environment an Android app process actually spawns from: TMPDIR
    /// names a directory that only exists on the host. What must come back
    /// is git's own "fatal:" sentence and git's own exit status.
    ///
    /// This is the device defect pinned down: the split wrapper parked
    /// stderr under `${TMPDIR}`, the app's inherited TMPDIR pointed at the
    /// host cache directory (set by the runtime after fork, invisible in the
    /// initial `/proc/<pid>/environ`), `sh` failed the redirection before
    /// git ever ran, and every failed fetch surfaced as "git exited with 2"
    /// with both streams empty — while the same command in the app's own
    /// terminal printed two `fatal:` lines and exited 128.
    #[test]
    #[cfg(unix)]
    fn a_hostile_tmpdir_cannot_swallow_what_a_failing_fetch_said() {
        use std::os::unix::fs::PermissionsExt;

        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let projects = dir.path().join("projects");
        let repo = projects.join("thing");
        std::fs::create_dir_all(&repo).unwrap();
        assert!(
            host_git(&repo, &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        assert!(
            host_git(&repo, &["remote", "add", "origin", "/nonexistent-repo"])
                .status
                .success()
        );

        // The fake guest, with the one addition that matters here: it hands
        // the wrapper the hostile TMPDIR, overriding the sane one the fixed
        // guest environment now pins — because the wrapper itself must not
        // trust whatever the environment says by the time it runs.
        let proot = dir.path().join("fake-proot");
        std::fs::write(
            &proot,
            format!(
                "#!/bin/sh\n\
                 while [ \"$1\" != \"-w\" ]; do shift; done\n\
                 shift 2\n\
                 TMPDIR={gone}\n\
                 GIT_CONFIG_GLOBAL=/dev/null\n\
                 GIT_CONFIG_SYSTEM=/dev/null\n\
                 export TMPDIR GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM\n\
                 exec \"$@\"\n",
                gone = dir.path().join("not-there").display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&proot, std::fs::Permissions::from_mode(0o755)).unwrap();

        let engine = crate::Engine::new();
        engine.set_userland(&proot, dir.path(), dir.path(), &projects);
        let userland = engine.userland().unwrap();

        let run = run_git_split(
            &userland,
            &repo,
            "git fetch",
            git_argv(&repo, &["fetch", "--all"]),
            &[],
        )
        .expect("the guest ran");
        assert!(
            run.stderr.contains("fatal:"),
            "git's own words were lost: status {} stdout {:?} stderr {:?}",
            run.status,
            run.stdout,
            run.stderr
        );
        assert_ne!(run.status, 0, "a failing fetch reported success");
        assert_ne!(
            run.status, 2,
            "the wrapper's own redirection failure leaked through as git's exit"
        );
    }

    /// A command past its deadline gets the deadline's own sentence — by then
    /// git *was* running, and a mutation killed mid-flight may have partly
    /// applied — never the userland-installation error, which would send the
    /// user reinstalling Debian over a slow network.
    #[test]
    #[cfg(unix)]
    fn a_command_past_its_deadline_says_it_may_have_partly_applied() {
        let dir = tempfile::tempdir().unwrap();
        let engine = crate::Engine::new();
        engine.set_userland(&fake_guest(dir.path()), dir.path(), dir.path(), dir.path());
        let userland = engine.userland().unwrap();
        // Detached from the pipes, because the fake guest has no proot to
        // take tracees down on the kill: an orphan holding stdout would make
        // `capture`'s readers wait out the whole sleep.
        let argv: Vec<OsString> = ["/bin/sh", "-c", "exec sleep 5 >/dev/null 2>&1"]
            .iter()
            .map(OsString::from)
            .collect();
        let err = run_git_with(
            &userland,
            dir.path(),
            "git sleep",
            argv,
            Duration::from_millis(200),
        )
        .unwrap_err();
        assert!(
            err.contains("may have partly applied"),
            "expected the deadline's own sentence, got {err:?}"
        );
        assert!(
            !err.contains("Linux userland"),
            "a deadline must not read as a missing userland: {err:?}"
        );
    }

    /// A mutation the guest *lost* — spawn failed, deadline fired — must
    /// still invalidate the status cache: the command may have run for a
    /// while before dying, and a `?` that skipped the bump left the panel
    /// sitting on the pre-command state until an unrelated worktree event.
    #[test]
    #[cfg(unix)]
    fn a_mutation_the_guest_lost_still_invalidates_the_status_cache() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, repo) = live_repo(dir.path());
        std::fs::write(repo.join("a.txt"), "one\n").unwrap();
        let id = engine.open_project(&repo);
        await_change(&engine, id, "a.txt");
        // Let any in-flight status run finish: one completing *after* the
        // failed commit below would set `scanned` back and fake a pass.
        let deadline = Instant::now() + Duration::from_secs(20);
        while engine.with_git(id, |git| git.running).unwrap_or(false) {
            assert!(Instant::now() < deadline, "the status run never settled");
            thread::sleep(Duration::from_millis(25));
        }
        assert_eq!(engine.with_git(id, |git| git.scanned), Some(true));

        // A proot that exists but cannot run: `is_installed` passes, the
        // spawn fails — the same shape as a rootfs pulled out mid-session.
        let broken = dir.path().join("broken-proot");
        std::fs::write(&broken, "").unwrap();
        engine.set_userland(&broken, dir.path(), dir.path(), &dir.path().join("projects"));

        let err = engine
            .git_commit(id, "doomed", false, false, false)
            .unwrap_err();
        assert!(err.contains("Could not run git"), "{err:?}");
        assert_eq!(
            engine.with_git(id, |git| git.scanned),
            Some(false),
            "the Err path must invalidate the cache too"
        );
    }

    /// An invalidation whose rescan finds an *identical* snapshot — a failed
    /// push, a commit git refused — must still move the version: the panel
    /// reads the state mid-invalidation, renders "Asking git…", and from then
    /// on re-reads only when [`Engine::git_status_version`] moves. A version
    /// pegged to content never told it the answer came back, and the panel
    /// sat on that transient forever — through tab switches and reopen both.
    #[test]
    #[cfg(unix)]
    fn a_rescan_with_an_identical_snapshot_still_moves_the_version() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, repo) = live_repo(dir.path());
        std::fs::write(repo.join("a.txt"), "one\n").unwrap();
        let id = engine.open_project(&repo);
        await_change(&engine, id, "a.txt");
        // Let the run settle completely, so the baseline version below cannot
        // be moved by anything but the invalidation's own rescan.
        let deadline = Instant::now() + Duration::from_secs(20);
        while engine.with_git(id, |git| git.running).unwrap_or(false) {
            assert!(Instant::now() < deadline, "the status run never settled");
            thread::sleep(Duration::from_millis(25));
        }
        assert_eq!(engine.with_git(id, |git| git.scanned), Some(true));
        let before = engine.with_git(id, |git| git.version).unwrap();

        // What every mutating command does — and the worktree is untouched,
        // so the rescan's snapshot will be byte-identical to the cache.
        engine.git_state_changed(id);

        // The panel's loop: poll the version, which is also what schedules
        // the rescan. Without the flag-flip bump this never moves.
        let deadline = Instant::now() + Duration::from_secs(20);
        loop {
            if engine.git_status_version(id) != before {
                break;
            }
            assert!(
                Instant::now() < deadline,
                "an identical rescan never moved the version; \
                 the panel would show 'Asking git…' forever"
            );
            thread::sleep(Duration::from_millis(25));
        }
        // And the reread the bump provokes must see the settled state again.
        assert_eq!(engine.with_git(id, |git| git.scanned), Some(true));
        // The snapshot really was identical — the bump came from the flag.
        let changes = engine.git_changes(id);
        assert_eq!(changes.entries.len(), 1);
        assert_eq!(changes.entries[0].path, "a.txt");
    }

    /// Wait for the status cache to catch up with a command, and hand back what
    /// it says about one path. Commands invalidate the cache rather than
    /// refilling it — refilling means a `git status` the caller did not ask for
    /// — so the next poll runs it, one debounce later.
    #[cfg(unix)]
    fn await_change(engine: &crate::Engine, id: ProjectId, path: &str) -> ChangedFile {
        let deadline = Instant::now() + Duration::from_secs(20);
        while Instant::now() < deadline {
            let changes = engine.git_changes(id);
            if changes.scanned
                && let Some(found) = changes.entries.iter().find(|entry| entry.path == path)
            {
                return found.clone();
            }
            thread::sleep(Duration::from_millis(25));
        }
        panic!("git status never reported {path}");
    }

    /// Wait for the cached branch record to satisfy `pred` — the cache refills
    /// behind the same debounce [`await_change`] describes, and reading it is
    /// what schedules the run.
    #[cfg(unix)]
    fn await_branch(
        engine: &crate::Engine,
        id: ProjectId,
        pred: impl Fn(&BranchInfo) -> bool,
    ) -> BranchInfo {
        let deadline = Instant::now() + Duration::from_secs(20);
        while Instant::now() < deadline {
            if let Some(branch) = engine.git_branch(id)
                && pred(&branch)
            {
                return branch;
            }
            thread::sleep(Duration::from_millis(25));
        }
        panic!("git status never reported the expected branch");
    }

    /// Is there a git to test against at all?
    fn has_git() -> bool {
        if Command::new("git").arg("--version").output().is_ok() {
            return true;
        }
        eprintln!("skipping: no git on PATH");
        false
    }

    /// A real repository with a real git, and an engine pointed at both.
    ///
    /// The tests below are about what git *does* rather than about what we
    /// think it does — every one of them exists because a plausible reading of
    /// git-restore(1) turned out to be wrong on a file somebody cared about.
    #[cfg(unix)]
    fn live_repo(dir: &Path) -> (crate::Engine, PathBuf) {
        let projects = dir.join("projects");
        let repo = projects.join("thing");
        std::fs::create_dir_all(&repo).unwrap();
        assert!(
            host_git(&repo, &["init", "--quiet", "-b", "main"])
                .status
                .success()
        );
        let engine = crate::Engine::new();
        engine.set_userland(&fake_guest(dir), dir, dir, &projects);
        (engine, repo)
    }

    /// `git status --porcelain`, straight from the host's git.
    #[cfg(unix)]
    fn plain_status(repo: &Path) -> String {
        let out = host_git(repo, &["status", "--porcelain"]);
        String::from_utf8_lossy(&out.stdout).into_owned()
    }

    /// "git could not run" and "nothing has changed" are the same empty list,
    /// and a panel that cannot tell them apart tells the user their tree is
    /// clean when it has no idea. Found on a device whose Debian had no git.
    /// The one-character hole a skeptic found: `+main` is a legal branch name
    /// *and* refspec syntax, and `git push origin +main` force-updates `main`
    /// on the remote — discarding whatever was there.
    #[test]
    fn a_branch_name_that_is_also_a_refspec_is_refused() {
        assert!(checked_branch("main").is_ok());
        assert!(checked_branch("feature/new-thing").is_ok());
        assert!(checked_branch("release-1.2.3").is_ok());
        assert!(checked_branch("+main").is_err());
        assert!(checked_branch("--force").is_err());
        assert!(checked_branch("main:other").is_err());
        assert!(checked_branch("a b").is_err());
        assert!(checked_branch("../etc").is_err());
        assert!(checked_branch("main.lock").is_err());
        assert!(checked_branch("HEAD~2").is_err());
        assert!(checked_branch("main^{}").is_err());
        assert!(checked_branch("").is_err());
    }

    /// The gate is git's own `check-ref-format` rulebook plus the two argv
    /// misreadings (leading `-`, leading `+`), **not** an ASCII whitelist:
    /// the listings hand the pickers whatever git prints, and a legal branch
    /// the checker refuses is a row no operation can act on. Found with
    /// `wip@review` — checkout, delete, pull and push all failed on a branch
    /// the picker itself listed.
    #[test]
    fn names_git_itself_lists_pass_the_gate() {
        // Legal per check-ref-format, once refused by the old whitelist.
        assert!(checked_branch("wip@review").is_ok());
        assert!(checked_branch("fix#123").is_ok());
        assert!(checked_branch("feature+x").is_ok());
        assert!(checked_branch("héllo").is_ok());
        assert!(checked_branch("功能分支").is_ok());
        assert!(checked_branch("wip{2024}").is_ok());

        // Still refused: what check-ref-format itself refuses…
        assert!(checked_branch("@").is_err());
        assert!(checked_branch("a@{b").is_err());
        assert!(checked_branch("done.").is_err());
        assert!(checked_branch("feat/.hidden").is_err());
        assert!(checked_branch("sub/name.lock").is_err());
        assert!(checked_branch("/lead").is_err());
        assert!(checked_branch("trail/").is_err());
        assert!(checked_branch("a//b").is_err());
        assert!(checked_branch("glob*").is_err());
        assert!(checked_branch("what?").is_err());
        assert!(checked_branch("set[1]").is_err());
        assert!(checked_branch("back\\slash").is_err());
        assert!(checked_branch("tab\there").is_err());
        assert!(checked_branch("nl\nhere").is_err());
        // …and the two shapes git would misread in an argv or refspec.
        assert!(checked_branch("-b").is_err());
        assert!(checked_branch("+force/me").is_err());
    }

    /// The upstream half of porcelain's branch header is what tells "push"
    /// from Zed's "Publish": a branch nobody has pushed has no `...` at all.
    #[test]
    fn the_branch_header_says_whether_there_is_an_upstream() {
        let tracked = parse_branch(b"## main...origin/main [ahead 1, behind 2]\0").unwrap();
        assert_eq!(tracked.name.as_deref(), Some("main"));
        assert_eq!(tracked.upstream.as_deref(), Some("origin/main"));
        assert_eq!((tracked.ahead, tracked.behind), (1, 2));

        let local_only = parse_branch(b"## feature/new-thing\0").unwrap();
        assert_eq!(local_only.name.as_deref(), Some("feature/new-thing"));
        assert!(local_only.upstream.is_none());

        let detached = parse_branch(b"## HEAD (no branch)\0").unwrap();
        assert!(detached.name.is_none());
        assert!(detached.upstream.is_none());
    }

    #[test]
    fn a_status_that_could_not_run_git_says_so_rather_than_reading_as_clean() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("project");
        std::fs::create_dir_all(root.join(".git")).unwrap();
        // A userland that is not installed: `status_for` can spawn nothing,
        // which is exactly the device this was found on — a Debian with no
        // git in it answers the same way.
        let engine = crate::Engine::new();
        engine.set_userland(
            &dir.path().join("no-such-proot"),
            &dir.path().join("no-such-rootfs"),
            dir.path(),
            dir.path(),
        );
        let userland = engine.userland().unwrap();
        let outcome = status_for(1, &userland, &root);
        assert!(
            outcome.repo_root.is_some(),
            "the .git directory is right there"
        );
        assert!(
            !outcome.ran,
            "no git ran, so the empty change list means nothing"
        );
        assert!(outcome.changes.is_empty());
    }

    /// Discarding a rename used to delete the file and lose everything typed
    /// into it since: `git restore --source=HEAD -- <new name>` does not refuse
    /// a path HEAD has never held, it removes it from the index and the
    /// worktree and exits 0.
    #[test]
    #[cfg(unix)]
    fn discarding_a_rename_brings_the_old_name_back_instead_of_deleting_the_file() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, repo) = live_repo(dir.path());
        std::fs::write(repo.join("a.txt"), "original\n").unwrap();
        assert!(host_git(&repo, &["add", "a.txt"]).status.success());
        assert!(
            host_git(&repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );
        assert!(
            host_git(&repo, &["mv", "a.txt", "renamed.txt"])
                .status
                .success()
        );
        std::fs::write(repo.join("renamed.txt"), "original\nprecious new work\n").unwrap();

        let id = engine.open_project(&repo);
        let change = await_change(&engine, id, "renamed.txt");
        // What the panel is told, which is what its dialog has to say.
        assert_eq!(change.original.as_deref(), Some("a.txt"));
        assert!(!change.in_head);

        engine.git_discard(id, &["renamed.txt".to_owned()]).unwrap();

        assert_eq!(
            std::fs::read_to_string(repo.join("a.txt")).unwrap(),
            "original\n",
            "the name HEAD holds has to come back"
        );
        assert!(
            !repo.join("renamed.txt").exists(),
            "the new name goes — to the trash, not with `git restore`"
        );
        // And the index goes with it: the old code left `D a.txt` staged, a
        // deletion the user never asked for.
        assert_eq!(plain_status(&repo), "");
    }

    /// A conflict is a decision, and `git restore --source=HEAD` makes it
    /// silently: it keeps "ours", stages the path, leaves `MERGE_HEAD` set and
    /// exits 0, so the panel goes quiet while the merge is still half-done.
    #[test]
    #[cfg(unix)]
    fn discarding_a_conflict_is_refused_and_leaves_the_merge_alone() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, repo) = live_repo(dir.path());
        std::fs::write(repo.join("f.txt"), "base\n").unwrap();
        assert!(host_git(&repo, &["add", "f.txt"]).status.success());
        assert!(
            host_git(&repo, &["commit", "--quiet", "-m", "base"])
                .status
                .success()
        );
        assert!(
            host_git(&repo, &["checkout", "--quiet", "-b", "other"])
                .status
                .success()
        );
        std::fs::write(repo.join("f.txt"), "theirs\n").unwrap();
        assert!(
            host_git(&repo, &["commit", "--quiet", "-am", "theirs"])
                .status
                .success()
        );
        assert!(
            host_git(&repo, &["checkout", "--quiet", "main"])
                .status
                .success()
        );
        std::fs::write(repo.join("f.txt"), "ours\n").unwrap();
        assert!(
            host_git(&repo, &["commit", "--quiet", "-am", "ours"])
                .status
                .success()
        );
        assert!(
            !host_git(&repo, &["merge", "other"]).status.success(),
            "the merge is supposed to conflict"
        );

        let id = engine.open_project(&repo);
        let change = await_change(&engine, id, "f.txt");
        assert!(change.conflicted);

        let refused = engine.git_discard(id, &["f.txt".to_owned()]).unwrap_err();
        assert!(
            refused.contains("merge conflict"),
            "expected a refusal naming the conflict, got {refused:?}"
        );
        let content = std::fs::read_to_string(repo.join("f.txt")).unwrap();
        assert!(
            content.contains("<<<<<<<") && content.contains("theirs"),
            "the incoming side must still be in the file, got {content:?}"
        );
        assert!(
            repo.join(".git/MERGE_HEAD").exists(),
            "the merge is still in progress and must stay that way"
        );
    }

    /// `--untracked-files=normal` collapses a new directory into one record
    /// with a trailing slash. Refusing that path refused every list it was in,
    /// so "Stage all" stopped working the moment anyone started new work.
    #[test]
    #[cfg(unix)]
    fn a_whole_untracked_directory_can_be_staged_and_discarded() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, repo) = live_repo(dir.path());
        std::fs::write(repo.join("README"), "one\n").unwrap();
        assert!(host_git(&repo, &["add", "README"]).status.success());
        assert!(
            host_git(&repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );
        std::fs::create_dir_all(repo.join("src/feature")).unwrap();
        std::fs::write(repo.join("src/feature/a.rs"), "fn a() {}\n").unwrap();
        std::fs::write(repo.join("src/feature/b.rs"), "fn b() {}\n").unwrap();
        std::fs::create_dir_all(repo.join("scratch")).unwrap();
        std::fs::write(repo.join("scratch/notes.md"), "notes\n").unwrap();

        let id = engine.open_project(&repo);
        // One record, one row, and the row's path is a directory's.
        let change = await_change(&engine, id, "src/");
        assert_eq!(change.unstaged, Some(GitStatus::Untracked));

        // "Stage all" hands the section's paths over as one list, so a single
        // unstageable row used to refuse every other row with it.
        engine
            .git_stage(id, &["src/".to_owned(), "scratch/".to_owned()])
            .unwrap();
        let staged = plain_status(&repo);
        assert!(
            staged.contains("A  src/feature/a.rs") && staged.contains("A  scratch/notes.md"),
            "expected both directories staged, got {staged:?}"
        );

        // And the destructive half: a directory HEAD has never seen goes to the
        // trash whole, rather than under a `git restore` that would delete it.
        engine.git_unstage(id, &["scratch/".to_owned()]).unwrap();
        let scratch = await_change(&engine, id, "scratch/");
        assert!(!scratch.in_head);
        engine.git_discard(id, &["scratch/".to_owned()]).unwrap();
        assert!(!repo.join("scratch").exists());
    }

    /// `AD` — staged, then deleted from the worktree. There is nothing to
    /// trash, and the index entry is the whole of what discarding it means.
    #[test]
    #[cfg(unix)]
    fn discarding_a_staged_file_already_gone_from_disk_clears_its_index_entry() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, repo) = live_repo(dir.path());
        std::fs::write(repo.join("README"), "one\n").unwrap();
        assert!(host_git(&repo, &["add", "README"]).status.success());
        assert!(
            host_git(&repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );
        std::fs::write(repo.join("new.rs"), "fn main() {}\n").unwrap();
        assert!(host_git(&repo, &["add", "new.rs"]).status.success());
        std::fs::remove_file(repo.join("new.rs")).unwrap();

        let id = engine.open_project(&repo);
        let change = await_change(&engine, id, "new.rs");
        assert!(!change.in_head);

        // The old code reported the trash's `No such file or directory` and
        // skipped the `git rm --cached` that was the point, so the row stayed
        // in the panel forever.
        engine.git_discard(id, &["new.rs".to_owned()]).unwrap();
        assert_eq!(plain_status(&repo), "");
    }

    /// The cheap reads answer from the status run's cache: the branch record a
    /// title bar polls every half second — name, drift and upstream, since the
    /// ↑↓ arrows and the history panes' reload keys are built from it — and
    /// the head a history pane keys its reloads on, both without a JSON
    /// snapshot of every changed file, and both agreeing with what git itself
    /// says about the same repository.
    #[test]
    #[cfg(unix)]
    fn the_branch_and_head_are_cached_for_cheap_reads() {
        if !has_git() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, repo) = live_repo(dir.path());
        std::fs::write(repo.join("README"), "one\n").unwrap();
        assert!(host_git(&repo, &["add", "README"]).status.success());
        assert!(
            host_git(&repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );
        std::fs::write(repo.join("README"), "two\n").unwrap();

        let id = engine.open_project(&repo);
        await_change(&engine, id, "README");

        let branch = engine.git_branch(id).expect("a run has completed");
        assert_eq!(branch.name.as_deref(), Some("main"));
        assert_eq!((branch.ahead, branch.behind), (0, 0));
        assert_eq!(branch.upstream, None);
        let head = engine.git_head(id).expect("a commit exists to name");
        let out = host_git(&repo, &["rev-parse", "HEAD"]);
        assert_eq!(head, String::from_utf8_lossy(&out.stdout).trim());
        // The panel's snapshot carries the same key, so a caller already
        // holding one pays nothing extra for it.
        assert_eq!(engine.git_changes(id).head.as_deref(), Some(head.as_str()));

        // Publish the branch and commit past it: the cheap read has to carry
        // the drift and the upstream, because a fetch or push moves these
        // without moving HEAD and the views keyed on this read must see it.
        assert!(
            host_git(dir.path(), &["init", "--quiet", "--bare", "remote.git"])
                .status
                .success()
        );
        let remote = dir.path().join("remote.git");
        let remote = remote.to_str().unwrap();
        assert!(
            host_git(&repo, &["remote", "add", "origin", remote])
                .status
                .success()
        );
        assert!(
            host_git(&repo, &["push", "--quiet", "-u", "origin", "main"])
                .status
                .success()
        );
        assert!(
            host_git(&repo, &["commit", "--allow-empty", "-qm", "ahead"])
                .status
                .success()
        );
        // Host git moved refs behind the engine's back; the engine's own
        // commands do this for themselves.
        engine.git_state_changed(id);
        let ahead = await_branch(&engine, id, |branch| branch.ahead == 1);
        assert_eq!(ahead.name.as_deref(), Some("main"));
        assert_eq!((ahead.ahead, ahead.behind), (1, 0));
        assert_eq!(ahead.upstream.as_deref(), Some("origin/main"));

        // A detached HEAD is a *present* record with no name: on no branch is
        // not the same answer as no repository, and the title bar's "no
        // branch" chip hangs on the difference.
        assert!(
            host_git(&repo, &["checkout", "--quiet", "--detach"])
                .status
                .success()
        );
        engine.git_state_changed(id);
        let detached = await_branch(&engine, id, |branch| branch.name.is_none());
        assert_eq!(detached.name, None);
    }

    #[test]
    fn changes_are_empty_and_repoless_without_a_project() {
        let engine = crate::Engine::new();
        let changes = engine.git_changes(404);
        assert!(!changes.scanned);
        assert!(!changes.has_repo);
        assert!(changes.entries.is_empty());
    }
}
