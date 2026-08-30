//! Branches: the picker's listing, and what it can do about one.
//!
//! Separate from [`crate::git`] for the same reason history is: it asks git a
//! different question at a different time. Status is polled and cached; the
//! branch list is read when somebody opens the picker, and switching, creating
//! and deleting are the user's own acts — blocking, with git's refusal shown
//! whole. What they share — [`run_git`], [`git_argv`], the shell wrapper —
//! lives in `git`.
//!
//! Every command here is Zed's, argv for argv (`crates/git/src/repository.rs`;
//! line citations below are into that file), because the branch picker being
//! ported promises Zed's behavior: a remote branch checks out as a local
//! tracking branch with the remote prefix cut off, a delete of a
//! remote-tracking ref folds `-r` into the flag, and the listing is one
//! `for-each-ref` whose nine NUL-joined fields feed the rows.

use std::ffi::OsString;

use crate::ProjectId;
use crate::git::{checked_branch, git_argv, run_git, run_git_mutating};

/// `for-each-ref`'s format: Zed's nine fields, joined with `%00` so git
/// separates them with NUL — the one byte a subject, an author, and a ref name
/// all cannot hold (repository.rs:2047-2059).
const FIELDS: &str = "%(HEAD)%00%(objectname)%00%(parent)%00%(refname)%00%(upstream)%00%(upstream:track)%00%(committerdate:unix)%00%(authorname)%00%(contents:subject)";

/// One branch, as a picker row draws it: the name, the tip commit's subject,
/// author and date for the meta line, and the head/upstream facts the list's
/// ordering and collapsing rules read. Zed's `Branch` plus its
/// `CommitSummary`, flattened (repository.rs:232-238, 511-519).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct BranchEntry {
    /// `main` — or `origin/main` for a remote-tracking branch: the refname
    /// with `refs/heads/` or `refs/remotes/` stripped, which is Zed's
    /// `Branch::name()` and the spelling [`crate::Engine::git_change_branch`]
    /// takes back.
    pub name: String,
    pub is_remote: bool,
    /// The branch HEAD is on — `*` in `%(HEAD)`'s column. First in the
    /// picker's sort, and its row gets the check icon instead of a delete.
    pub is_head: bool,
    /// The tip commit. Empty on an unborn branch, which has no commit yet —
    /// the fallback in [`crate::Engine::git_branches`] synthesizes that row.
    pub sha: String,
    pub subject: String,
    /// `%(committerdate:unix)`; 0 when there is no commit.
    pub committer_date: i64,
    pub author: String,
    /// The tip commit has a parent — false for a root commit. What hides
    /// Zed's Uncommit button (git_panel.rs:6215).
    pub has_parent: bool,
    /// The upstream a local branch tracks, `origin/main`-style — short, like
    /// [`name`](Self::name), and the key the picker collapses remote rows by:
    /// a remote branch some local branch already tracks is hidden.
    pub upstream: Option<String>,
    /// Drift against that upstream, from `%(upstream:track)`. Both 0 when in
    /// sync — and when there is no upstream at all.
    pub ahead: u32,
    pub behind: u32,
    /// The upstream is configured but its ref is gone — git's `[gone]`,
    /// usually a branch deleted on the remote.
    pub upstream_gone: bool,
}

/// Every branch, plus the warning when git could only list some of them. Zed
/// keeps the partial listing and shows the error in the picker's banner
/// rather than throwing the branches away (repository.rs:2068-2074).
#[derive(Debug, Clone, Default, serde::Serialize)]
pub struct BranchList {
    pub branches: Vec<BranchEntry>,
    pub error: Option<String>,
}

impl crate::Engine {
    /// Every local and remote-tracking branch, with the tip commit each row
    /// shows. Zed's listing exactly: `git for-each-ref refs/heads/**/*
    /// refs/remotes/**/* --format <FIELDS>` (repository.rs:2043-2100), and
    /// when it names nothing — an unborn HEAD, a repository just initialized —
    /// `git symbolic-ref --quiet HEAD` synthesizes the one branch the
    /// repository is on, with no commit. **Blocking**: it runs git.
    pub fn git_branches(&self, id: ProjectId) -> Result<BranchList, String> {
        let repo = self.repo_for(id)?;
        let args: Vec<OsString> = [
            "for-each-ref",
            "refs/heads/**/*",
            "refs/remotes/**/*",
            "--format",
            FIELDS,
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
        // The wrapper merges stderr into the output, and a stderr line has no
        // NUL in it, so the parser drops it on its own; what survives is the
        // partial listing Zed would keep, with the complaint alongside.
        let mut branches = parse_branches(&run.output);
        let error = (run.status != 0).then(|| run.message());

        if branches.is_empty() {
            let args: Vec<OsString> = ["symbolic-ref", "--quiet", "HEAD"]
                .iter()
                .map(OsString::from)
                .collect();
            let head = run_git(
                &repo.userland,
                &repo.repo_root,
                "git symbolic-ref",
                git_argv(&repo.project_root, &args),
            )?;
            // Non-zero means HEAD points at something other than a branch,
            // which is an answer — an empty list — not a failure.
            if head.status == 0 {
                let name = head.output.trim();
                branches.push(BranchEntry {
                    name: name.strip_prefix("refs/heads/").unwrap_or(name).to_owned(),
                    is_remote: false,
                    is_head: true,
                    sha: String::new(),
                    subject: String::new(),
                    committer_date: 0,
                    author: String::new(),
                    has_parent: false,
                    upstream: None,
                    ahead: 0,
                    behind: 0,
                    upstream_gone: false,
                });
            }
        }
        Ok(BranchList { branches, error })
    }

    /// Check out a branch by the name a [`BranchEntry`] carries. Zed's
    /// three-way flow, command for command (repository.rs:2262-2314):
    ///
    /// 1. `refs/heads/<name>` exists → `git checkout <name>`.
    /// 2. Else `refs/remotes/<name>` exists — the name is `origin/feature` —
    ///    resolve a symref through `git symbolic-ref`, split off the remote,
    ///    then either `git branch --set-upstream-to <name> <branch>` when the
    ///    local branch already exists or `git branch --track <branch> <name>`
    ///    when it does not, and `git checkout <branch>`: the local branch is
    ///    named after the remote one, minus the remote prefix.
    /// 3. Else: `Branch '<name>' not found`.
    ///
    /// **Blocking**, and it can refuse — a dirty worktree that would be
    /// overwritten is git's own sentence, shown whole.
    pub fn git_change_branch(&self, id: ProjectId, name: &str) -> Result<(), String> {
        let name = checked_branch(name)?;
        let repo = self.repo_for(id)?;
        let run = |label: &str, args: &[&str]| {
            let args: Vec<OsString> = args.iter().map(OsString::from).collect();
            run_git(
                &repo.userland,
                &repo.repo_root,
                label,
                git_argv(&repo.project_root, &args),
            )
        };
        // For the commands that *change* the repository: the long safety-net
        // deadline — a checkout of thousands of files under proot must not be
        // killed mid worktree update — and the cache invalidated on the Err
        // path too, since a lost run may still have moved things.
        let change = |label: &str, args: &[&str]| {
            let args: Vec<OsString> = args.iter().map(OsString::from).collect();
            let run = run_git_mutating(
                &repo.userland,
                &repo.repo_root,
                label,
                git_argv(&repo.project_root, &args),
            );
            self.git_state_changed(id);
            run
        };

        let local_ref = format!("refs/heads/{name}");
        if run("git show-ref", &["show-ref", "--verify", "--quiet", &local_ref])?.status == 0 {
            let checkout = change("git checkout", &["checkout", &name])?;
            return if checkout.status == 0 {
                Ok(())
            } else {
                Err(checkout.message())
            };
        }

        let remote_ref = format!("refs/remotes/{name}");
        if run("git show-ref", &["show-ref", "--verify", "--quiet", &remote_ref])?.status == 0 {
            // `origin/HEAD` is a symref; checking it out means checking out
            // whatever it points at, so resolve it first, as Zed does.
            let resolved = run("git symbolic-ref", &["symbolic-ref", &remote_ref])?;
            let name = if resolved.status == 0 {
                let target = resolved.output.trim();
                // What symbolic-ref said goes straight back into an argv, so
                // it passes the same gate the caller's name did.
                checked_branch(target.strip_prefix("refs/remotes/").unwrap_or(&name))?
            } else {
                name
            };
            let (_, branch_name) = name
                .split_once('/')
                .ok_or_else(|| "Unexpected branch format".to_owned())?;
            let branch_name = checked_branch(branch_name)?;
            let local_branch_ref = format!("refs/heads/{branch_name}");
            let tracked = if run(
                "git show-ref",
                &["show-ref", "--verify", "--quiet", &local_branch_ref],
            )?
            .status
                == 0
            {
                change(
                    "git branch",
                    &["branch", "--set-upstream-to", &name, &branch_name],
                )?
            } else {
                change("git branch", &["branch", "--track", &branch_name, &name])?
            };
            if tracked.status != 0 {
                return Err(tracked.message());
            }
            let checkout = change("git checkout", &["checkout", &branch_name])?;
            return if checkout.status == 0 {
                Ok(())
            } else {
                Err(checkout.message())
            };
        }

        Err(format!("Branch '{name}' not found"))
    }

    /// Create a branch and switch to it: `git switch -c <name> [<base>]`,
    /// exactly Zed's create_branch (repository.rs:2316-2337). No base means
    /// branching off HEAD; the picker's "Create New From" confirm passes the
    /// default branch as the base. **Blocking**.
    pub fn git_create_branch(
        &self,
        id: ProjectId,
        name: &str,
        base: Option<&str>,
    ) -> Result<(), String> {
        let name = checked_branch(name)?;
        let repo = self.repo_for(id)?;
        let mut args: Vec<OsString> = vec![
            OsString::from("switch"),
            OsString::from("-c"),
            OsString::from(&name),
        ];
        if let Some(base) = base {
            args.push(OsString::from(checked_branch(base)?));
        }
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git switch",
            git_argv(&repo.project_root, &args),
        );
        // Ok or Err alike — a lost run may still have switched.
        self.git_state_changed(id);
        let run = run?;
        if run.status == 0 {
            Ok(())
        } else {
            Err(run.message())
        }
    }

    /// Delete a branch: `git branch <flag> <name>` with Zed's flag table —
    /// see [`delete_branch_flag`]. An unmerged branch is git's own refusal
    /// ("not fully merged"), which the picker turns into its force-delete
    /// prompt rather than anything here deciding to force. **Blocking**.
    pub fn git_delete_branch(
        &self,
        id: ProjectId,
        name: &str,
        is_remote: bool,
        force: bool,
    ) -> Result<(), String> {
        let name = checked_branch(name)?;
        let repo = self.repo_for(id)?;
        let args: Vec<OsString> = vec![
            OsString::from("branch"),
            OsString::from(delete_branch_flag(is_remote, force)),
            OsString::from(&name),
        ];
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git branch",
            git_argv(&repo.project_root, &args),
        );
        // Ok or Err alike — a lost run may still have deleted.
        self.git_state_changed(id);
        let run = run?;
        if run.status == 0 {
            Ok(())
        } else {
            Err(run.message())
        }
    }

    /// The repository's default branch, for the picker's "Create New From:"
    /// entry. Zed's resolution chain with `include_remote_name = false`, the
    /// way the picker asks for it (repository.rs:3133-3197,
    /// branch_picker.rs:279-281): one `for-each-ref` over the two remotes'
    /// `HEAD` symrefs and the local heads, preferring `upstream/HEAD`, then
    /// `origin/HEAD`, then `git config init.defaultBranch` if that local
    /// branch exists, then local `main`, then local `master`. `None` when
    /// nothing matches — the entry is simply not offered. **Blocking**.
    pub fn git_default_branch(&self, id: ProjectId) -> Result<Option<String>, String> {
        let repo = self.repo_for(id)?;
        let args: Vec<OsString> = [
            "for-each-ref",
            "--format=%(refname)\t%(symref)",
            "refs/remotes/upstream/HEAD",
            "refs/remotes/origin/HEAD",
            "refs/heads/",
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
        // Zed reads a failed listing as an empty one and lets the chain fall
        // through to the config.
        let listing = if run.status == 0 { run.output } else { String::new() };
        Ok(resolve_default_branch(&listing, || {
            let args: Vec<OsString> = ["config", "init.defaultBranch"]
                .iter()
                .map(OsString::from)
                .collect();
            run_git(
                &repo.userland,
                &repo.repo_root,
                "git config",
                git_argv(&repo.project_root, &args),
            )
            .ok()
            .filter(|run| run.status == 0)
            .map(|run| run.output.trim().to_owned())
        }))
    }
}

/// Zed's own flag table (repository.rs:744-751): `-r` folded into the flag
/// for a remote-tracking ref, capitalized to force.
fn delete_branch_flag(is_remote: bool, force: bool) -> &'static str {
    match (is_remote, force) {
        (true, true) => "-Dr",
        (true, false) => "-dr",
        (false, true) => "-D",
        (false, false) => "-d",
    }
}

/// One line per ref, nine NUL-joined fields — see [`FIELDS`]. A line that
/// does not carry all nine is dropped, exactly as Zed's `parse_branch_input`
/// drops it (repository.rs:4025-4083); that rule is also what silently
/// discards any stderr the wrapper merged in, which has no NULs at all.
pub(crate) fn parse_branches(output: &str) -> Vec<BranchEntry> {
    let mut branches = Vec::new();
    for line in output.lines() {
        if line.is_empty() {
            continue;
        }
        let mut fields = line.split('\x00');
        let Some(head) = fields.next() else { continue };
        let Some(sha) = fields.next() else { continue };
        let Some(parent) = fields.next() else { continue };
        let Some(ref_name) = fields.next() else {
            continue;
        };
        let Some(upstream) = fields.next() else {
            continue;
        };
        let Some(track) = fields.next().and_then(parse_upstream_track) else {
            continue;
        };
        let Some(committer_date) = fields.next().and_then(|date| date.trim().parse::<i64>().ok())
        else {
            continue;
        };
        let Some(author) = fields.next() else { continue };
        let Some(subject) = fields.next() else { continue };

        // Only the two namespaces the argv asked for can come back; anything
        // else has no name a picker row could show.
        let (name, is_remote) = if let Some(name) = ref_name.strip_prefix("refs/heads/") {
            (name, false)
        } else if let Some(name) = ref_name.strip_prefix("refs/remotes/") {
            (name, true)
        } else {
            continue;
        };

        let (ahead, behind, upstream_gone) = match track {
            UpstreamTrack::Tracked { ahead, behind } => (ahead, behind, false),
            UpstreamTrack::Gone => (0, 0, true),
        };
        branches.push(BranchEntry {
            name: name.to_owned(),
            is_remote,
            is_head: head == "*",
            sha: sha.to_owned(),
            subject: subject.to_owned(),
            committer_date,
            author: author.to_owned(),
            has_parent: !parent.is_empty(),
            upstream: (!upstream.is_empty()).then(|| {
                // Short like `name`: the collapse rule compares this against
                // remote entries' names, and `git branch --set-upstream-to`
                // takes the same spelling back.
                upstream
                    .strip_prefix("refs/remotes/")
                    .unwrap_or(upstream)
                    .to_owned()
            }),
            ahead,
            behind,
            upstream_gone,
        });
    }
    branches
}

/// What `%(upstream:track)` said about the drift.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum UpstreamTrack {
    Tracked { ahead: u32, behind: u32 },
    Gone,
}

/// `[ahead 3, behind 1]`, `[ahead 3]`, `[gone]`, or empty for "in sync" —
/// Zed's `parse_upstream_track` (repository.rs:4096-4123). `None` for a shape
/// git never writes, which drops the whole line rather than inventing zeros.
pub(crate) fn parse_upstream_track(track: &str) -> Option<UpstreamTrack> {
    if track.is_empty() {
        return Some(UpstreamTrack::Tracked { ahead: 0, behind: 0 });
    }
    let track = track.strip_prefix('[')?.strip_suffix(']')?;
    let mut ahead: u32 = 0;
    let mut behind: u32 = 0;
    for component in track.split(", ") {
        if component == "gone" {
            return Some(UpstreamTrack::Gone);
        }
        if let Some(count) = component.strip_prefix("ahead ") {
            ahead = count.parse().ok()?;
        }
        if let Some(count) = component.strip_prefix("behind ") {
            behind = count.parse().ok()?;
        }
    }
    Some(UpstreamTrack::Tracked { ahead, behind })
}

/// Zed's default-branch chain over the `%(refname)\t%(symref)` listing, with
/// the remote name stripped the way the picker asks for it
/// (repository.rs:3133-3197). `configured` is `git config
/// init.defaultBranch`, fetched only when the symrefs answer nothing — a
/// closure so the resolution stays a function of strings a test can write.
pub(crate) fn resolve_default_branch(
    listing: &str,
    configured: impl FnOnce() -> Option<String>,
) -> Option<String> {
    let refs: std::collections::HashMap<&str, &str> = listing
        .lines()
        .filter_map(|line| line.split_once('\t'))
        .collect();

    if let Some(target) = refs.get("refs/remotes/upstream/HEAD")
        && let Some(branch) = target.strip_prefix("refs/remotes/upstream/")
    {
        return Some(branch.to_owned());
    }
    if let Some(target) = refs.get("refs/remotes/origin/HEAD")
        && let Some(branch) = target.strip_prefix("refs/remotes/origin/")
    {
        return Some(branch.to_owned());
    }

    let local_exists = |branch: &str| refs.contains_key(format!("refs/heads/{branch}").as_str());
    if let Some(default) = configured()
        && local_exists(&default)
    {
        return Some(default);
    }
    if local_exists("main") {
        return Some("main".to_owned());
    }
    if local_exists("master") {
        return Some("master".to_owned());
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;
    use std::process::Command;

    /// A listing the way git writes it, with every kind of row the picker has
    /// to tell apart.
    #[test]
    fn a_branch_listing_parses() {
        let output = "*\0abc123\0def456\0refs/heads/main\0refs/remotes/origin/main\0[ahead 2, behind 1]\01700000000\0Carlo Esposito\0Fix the thing, and the other thing\n\
 \0def456\0\0refs/heads/root\0\0\01699999999\0Someone Else\0First commit\n\
 \0abc123\0def456\0refs/remotes/origin/main\0\0\01700000000\0Carlo Esposito\0Fix the thing, and the other thing\n";
        let branches = parse_branches(output);
        assert_eq!(branches.len(), 3);

        assert_eq!(branches[0].name, "main");
        assert!(branches[0].is_head);
        assert!(!branches[0].is_remote);
        assert!(branches[0].has_parent);
        assert_eq!(branches[0].upstream.as_deref(), Some("origin/main"));
        assert_eq!(branches[0].ahead, 2);
        assert_eq!(branches[0].behind, 1);
        assert!(!branches[0].upstream_gone);
        assert_eq!(branches[0].committer_date, 1_700_000_000);
        assert_eq!(branches[0].subject, "Fix the thing, and the other thing");
        assert_eq!(branches[0].author, "Carlo Esposito");

        // A root commit has no parent — the fact that hides Uncommit.
        assert!(!branches[1].has_parent);
        assert!(branches[1].upstream.is_none());

        // The remote row keeps its remote prefix in the name; that name is
        // what checking it out hands back.
        assert_eq!(branches[2].name, "origin/main");
        assert!(branches[2].is_remote);
        assert!(!branches[2].is_head);
    }

    /// A stderr line the wrapper merged in has no NULs, and a truncated
    /// record has too few — both drop, neither invents a branch.
    #[test]
    fn lines_that_are_not_records_are_dropped() {
        assert!(parse_branches("").is_empty());
        assert!(parse_branches("fatal: not a git repository\n").is_empty());
        assert!(parse_branches("*\0abc\0\0refs/heads/main\0\0\n").is_empty());
        // A date that is not a number is a malformed record, not zero.
        assert!(
            parse_branches(" \0abc\0\0refs/heads/x\0\0\0notadate\0A\0s\n").is_empty()
        );
        // A namespace the argv never asked for.
        assert!(parse_branches(" \0abc\0\0refs/tags/v1\0\0\01\0A\0s\n").is_empty());
    }

    #[test]
    fn upstream_track_reads_every_shape_git_writes() {
        assert_eq!(
            parse_upstream_track(""),
            Some(UpstreamTrack::Tracked { ahead: 0, behind: 0 })
        );
        assert_eq!(
            parse_upstream_track("[ahead 3]"),
            Some(UpstreamTrack::Tracked { ahead: 3, behind: 0 })
        );
        assert_eq!(
            parse_upstream_track("[behind 12]"),
            Some(UpstreamTrack::Tracked { ahead: 0, behind: 12 })
        );
        assert_eq!(
            parse_upstream_track("[ahead 1, behind 2]"),
            Some(UpstreamTrack::Tracked { ahead: 1, behind: 2 })
        );
        assert_eq!(parse_upstream_track("[gone]"), Some(UpstreamTrack::Gone));
        assert_eq!(parse_upstream_track("nonsense"), None);
        assert_eq!(parse_upstream_track("[ahead x]"), None);
    }

    /// The whole preference chain, one rung at a time.
    #[test]
    fn the_default_branch_chain_prefers_upstream_then_origin_then_config() {
        let both = "refs/remotes/upstream/HEAD\trefs/remotes/upstream/dev\n\
refs/remotes/origin/HEAD\trefs/remotes/origin/main\n\
refs/heads/main\t\n";
        assert_eq!(
            resolve_default_branch(both, || panic!("config must not be asked")),
            Some("dev".to_owned())
        );

        let origin = "refs/remotes/origin/HEAD\trefs/remotes/origin/trunk\nrefs/heads/trunk\t\n";
        assert_eq!(
            resolve_default_branch(origin, || panic!("config must not be asked")),
            Some("trunk".to_owned())
        );

        // The config only counts when the branch it names exists locally.
        let locals = "refs/heads/dev\t\nrefs/heads/master\t\n";
        assert_eq!(
            resolve_default_branch(locals, || Some("dev".to_owned())),
            Some("dev".to_owned())
        );
        assert_eq!(
            resolve_default_branch(locals, || Some("elsewhere".to_owned())),
            Some("master".to_owned())
        );
        assert_eq!(
            resolve_default_branch("refs/heads/main\t\nrefs/heads/master\t\n", || None),
            Some("main".to_owned())
        );
        assert_eq!(resolve_default_branch("", || None), None);
    }

    /// Zed's exact table: `-r` folded in, capitalized to force.
    #[test]
    fn the_delete_flag_matches_zeds_table() {
        assert_eq!(delete_branch_flag(false, false), "-d");
        assert_eq!(delete_branch_flag(false, true), "-D");
        assert_eq!(delete_branch_flag(true, false), "-dr");
        assert_eq!(delete_branch_flag(true, true), "-Dr");
    }

    /// Run the host's git hermetically, as `git.rs`'s tests do: no user or
    /// system config, an identity of our own.
    fn host_git(dir: &Path, args: &[&str]) -> std::process::Output {
        Command::new("git")
            .args(args)
            .current_dir(dir)
            .env("GIT_CONFIG_GLOBAL", "/dev/null")
            .env("GIT_CONFIG_SYSTEM", "/dev/null")
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

    /// Every parser test above feeds strings we wrote ourselves — the same
    /// blind spot `git.rs` documents. So: real git, the real argv, and the
    /// real output through the real parser.
    #[test]
    fn real_git_accepts_the_branch_argvs_and_the_listing_parses() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        let run_argv = |args: &[&str]| {
            let argv: Vec<OsString> = args.iter().map(OsString::from).collect();
            let argv = git_argv(repo, &argv);
            let args: Vec<&str> = argv
                .iter()
                .skip(1)
                .map(|arg| arg.to_str().expect("argv is UTF-8 in this test"))
                .collect();
            host_git(repo, &args)
        };

        assert!(host_git(repo, &["init", "--quiet", "-b", "main"]).status.success());
        std::fs::write(repo.join("README"), "one\n").unwrap();
        assert!(host_git(repo, &["add", "README"]).status.success());
        assert!(
            host_git(repo, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );

        // Create-and-switch, the create entry's command, and one commit of
        // its own so the tips differ.
        let out = run_argv(&["switch", "-c", "feature"]);
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        std::fs::write(repo.join("extra"), "x\n").unwrap();
        assert!(host_git(repo, &["add", "extra"]).status.success());
        assert!(
            host_git(repo, &["commit", "--quiet", "-m", "second"])
                .status
                .success()
        );

        // The listing argv, and the parse of what actually came back.
        let out = run_argv(&[
            "for-each-ref",
            "refs/heads/**/*",
            "refs/remotes/**/*",
            "--format",
            FIELDS,
        ]);
        assert!(
            out.status.success(),
            "git rejected the listing argv:\n{}",
            String::from_utf8_lossy(&out.stderr)
        );
        let branches = parse_branches(&String::from_utf8_lossy(&out.stdout));
        assert_eq!(branches.len(), 2);
        let feature = branches.iter().find(|b| b.name == "feature").unwrap();
        assert!(feature.is_head && !feature.is_remote && feature.has_parent);
        assert_eq!(feature.subject, "second");
        let main = branches.iter().find(|b| b.name == "main").unwrap();
        assert!(!main.is_head);
        // `main`'s tip is the root commit — the shape that hides Uncommit.
        assert!(!main.has_parent);

        // Switching back through the change-branch probes, then both delete
        // flavours: the unmerged branch refuses `-d` with the sentence the
        // picker's force prompt matches on, and `-D` deletes it.
        assert!(run_argv(&["show-ref", "--verify", "--quiet", "refs/heads/main"]).status.success());
        assert!(run_argv(&["checkout", "main"]).status.success());
        let refused = run_argv(&["branch", "-d", "feature"]);
        assert!(!refused.status.success());
        assert!(String::from_utf8_lossy(&refused.stderr).contains("not fully merged"));
        assert!(run_argv(&["branch", "-D", "feature"]).status.success());

        // The default-branch listing argv (the `\t` format survives the trip).
        let out = run_argv(&[
            "for-each-ref",
            "--format=%(refname)\t%(symref)",
            "refs/remotes/upstream/HEAD",
            "refs/remotes/origin/HEAD",
            "refs/heads/",
        ]);
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        assert_eq!(
            resolve_default_branch(&String::from_utf8_lossy(&out.stdout), || None),
            Some("main".to_owned())
        );
    }

    /// A repository with no commits yet lists nothing, and the fallback's
    /// `symbolic-ref` names the unborn branch.
    #[test]
    fn real_git_answers_the_unborn_fallback() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        assert!(host_git(repo, &["init", "--quiet", "-b", "main"]).status.success());

        let listed = host_git(
            repo,
            &["for-each-ref", "refs/heads/**/*", "refs/remotes/**/*", "--format", FIELDS],
        );
        assert!(listed.status.success());
        assert!(parse_branches(&String::from_utf8_lossy(&listed.stdout)).is_empty());

        let head = host_git(repo, &["symbolic-ref", "--quiet", "HEAD"]);
        assert!(head.status.success());
        assert_eq!(
            String::from_utf8_lossy(&head.stdout).trim(),
            "refs/heads/main"
        );
    }
}
