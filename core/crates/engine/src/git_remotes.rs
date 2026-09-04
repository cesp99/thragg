//! Remotes: fetch, pull, push, and the listing the pickers read.
//!
//! Separate from [`crate::git`] the way branches are: these commands talk to a
//! *network*, block for as long as one takes, and their output is something
//! the UI formats rather than merely shows — Zed turns a pull's stdout into
//! "Received 3 file changes from origin" and a push's stderr into a
//! pull-request button (`crates/git_ui/src/remote_output.rs`). That is why
//! everything here runs through [`run_git_split`], which keeps the two streams
//! apart, where the rest of the git module happily merges them.
//!
//! Every argv is Zed's, flag for flag (`crates/git/src/repository.rs`; line
//! citations below are into that file unless said otherwise): fetch-all is
//! literally `fetch --all`, a pull names the branch only when it has no
//! upstream, and a force push is `--force-with-lease` — the lease is the
//! safety, there is no confirmation dialog in front of it.
//!
//! A credential prompt — an HTTPS token, a key's passphrase, ssh's host-key
//! question — is answered the way Zed answers it: the command runs with
//! `GIT_ASKPASS`/`SSH_ASKPASS` naming a helper that hands the prompt to the
//! app, and the app's dialog hands the answer back (`crates/askpass`,
//! `repository.rs:4022-4040`; ours is [`crate::askpass`]). Every command here
//! runs through [`remote_run`], which is what adds that environment and —
//! where the userland's git has the cache helper — `credential.helper=cache`,
//! so one operation's token serves the next. `GIT_TERMINAL_PROMPT=0` (set by
//! [`crate::git::git_command`]) stays: with the helper refused, git must
//! fail with its own sentence rather than wait on a terminal nobody has.

use std::ffi::OsString;

use crate::ProjectId;
use crate::askpass::credential_cache_args;
use crate::git::{
    GitSplitRun, Repo, checked_branch, failure_line, git_argv, run_git, run_git_split,
};

/// One remote, as `git remote -v` lists it: the name the pickers show and the
/// argvs take, and the fetch URL — which is what tells a github.com remote
/// from any other, the fact the graph's open-on-web actions will need. Zed
/// keeps only the name (repository.rs:2858-2884); the URL rides along here
/// because listing again to get it would be a second `git remote -v`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct RemoteEntry {
    pub name: String,
    pub url: String,
}

/// What a remote command said, stream by stream, plus where it was pointed.
///
/// The non-zero exit lives *inside* this rather than in an `Err`: a failed
/// push's whole log is what the View Log button shows, and Zed hands the
/// output back whole either way. [`Self::message`] is the one-line version
/// for the toast; `Err` from the functions below is reserved for the guest
/// itself failing, where there is no output to keep.
#[derive(Debug, Clone, serde::Serialize)]
pub struct RemoteOutput {
    /// The remote the command ran against — `None` for `fetch --all`, which
    /// runs against every one of them ("Synchronized with remotes").
    pub remote: Option<String>,
    pub stdout: String,
    pub stderr: String,
    pub status: i32,
}

impl RemoteOutput {
    pub fn ok(&self) -> bool {
        self.status == 0
    }

    /// The one-line complaint, stdout before stderr so the fallback — the
    /// last thing said — is stderr's last line, which is where a remote
    /// command actually says why it failed.
    pub fn message(&self) -> String {
        failure_line(&format!("{}\n{}", self.stdout, self.stderr), self.status)
    }
}

impl crate::Engine {
    /// Every remote, in the order `git remote -v` lists them (alphabetical —
    /// stable, where Zed's `HashSet` shuffles). Feeds the Fetch From and Push
    /// To pickers, and the zero-remotes refusals the UI owns. **Blocking**:
    /// it runs git.
    pub fn git_remotes(&self, id: ProjectId) -> Result<Vec<RemoteEntry>, String> {
        let repo = self.repo_for(id)?;
        let args: Vec<OsString> = ["remote", "-v"].iter().map(OsString::from).collect();
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git remote",
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            return Err(run.message());
        }
        Ok(parse_remotes(&run.output))
    }

    /// The remote the branch is configured to talk to, or `None` when nothing
    /// is configured — the caller's cue to fall back to [`Self::git_remotes`]
    /// and a picker, exactly Zed's `get_remote` flow (git_panel.rs:4130-4175,
    /// git_store.rs:8550-8572). Two different questions for the two
    /// directions, as in Zed: pushing asks `git rev-parse --abbrev-ref
    /// <branch>@{push}` and takes the text before the `/`
    /// (repository.rs:2813-2835); pulling asks `git config --get
    /// branch.<branch>.remote` (repository.rs:2837-2856). A non-zero exit is
    /// "not configured", which is an answer. **Blocking**.
    pub fn git_branch_remote(
        &self,
        id: ProjectId,
        branch: &str,
        is_push: bool,
    ) -> Result<Option<String>, String> {
        let branch = checked_branch(branch)?;
        let repo = self.repo_for(id)?;
        let (label, args) = if is_push {
            (
                "git rev-parse",
                vec![
                    OsString::from("rev-parse"),
                    OsString::from("--abbrev-ref"),
                    OsString::from(format!("{branch}@{{push}}")),
                ],
            )
        } else {
            (
                "git config",
                vec![
                    OsString::from("config"),
                    OsString::from("--get"),
                    OsString::from(format!("branch.{branch}.remote")),
                ],
            )
        };
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            label,
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            return Ok(None);
        }
        let answer = run.output.trim();
        let name = if is_push {
            // `origin/main` — the remote is the half before the slash.
            answer.split('/').next().unwrap_or(answer)
        } else {
            answer
        };
        let name = name.trim();
        if name.is_empty() {
            return Ok(None);
        }
        Ok(Some(name.to_owned()))
    }

    /// Fetch from one remote, or from all of them — Zed's Fetch is literally
    /// fetch-all (git_panel.rs:3674-3732), and Fetch From passes the picked
    /// name. See [`fetch_args`]. Bumps the status cache either way: a fetch
    /// that failed halfway may still have moved remote refs. **Blocking**: it
    /// talks to the network.
    pub fn git_fetch(
        &self,
        id: ProjectId,
        remote: Option<&str>,
    ) -> Result<RemoteOutput, String> {
        let remote = remote.map(checked_branch).transpose()?;
        let repo = self.repo_for(id)?;
        let run = self.remote_run(&repo, "git fetch", fetch_args(remote.as_deref()));
        // "Either way" includes the guest failing: a fetch the deadline
        // killed may still have moved remote refs before the kill landed.
        self.git_state_changed(id);
        let run = run?;
        Ok(RemoteOutput {
            remote,
            stdout: run.stdout,
            stderr: run.stderr,
            status: run.status,
        })
    }

    /// Pull from a remote the caller already resolved (through
    /// [`Self::git_branch_remote`] and the picker) — Zed's Pull and Pull
    /// (Rebase) (git_panel.rs:3830-3892). The branch name rides along only
    /// when the branch has no upstream, exactly Zed's
    /// `branch.upstream.is_none().then(...)`; with one, git resolves the
    /// merge target itself. See [`pull_args`]. **Blocking**: network.
    pub fn git_pull(
        &self,
        id: ProjectId,
        branch: &str,
        remote: &str,
        rebase: bool,
    ) -> Result<RemoteOutput, String> {
        let branch = checked_branch(branch)?;
        let remote = checked_branch(remote)?;
        let repo = self.repo_for(id)?;
        let has_upstream = has_configured_upstream(&repo, &branch)?;
        let publish_branch = (!has_upstream).then_some(branch.as_str());
        let run = self.remote_run(&repo, "git pull", pull_args(&remote, rebase, publish_branch));
        // Ok or Err alike: a pull the deadline killed may already have moved
        // remote refs or begun a merge, and the panel must not keep showing
        // the pre-command state.
        self.git_state_changed(id);
        let run = run?;
        Ok(RemoteOutput {
            remote: Some(remote),
            stdout: run.stdout,
            stderr: run.stderr,
            status: run.status,
        })
    }

    /// Send commits to the remote — Zed's push, its "Publish"/"Republish"
    /// when [set_upstream] is on, its Force Push when [force] is
    /// (git_panel.rs:3894-3986). The refspec is Zed's
    /// `<branch>:<remote_branch>`: the upstream's branch name when the branch
    /// tracks one that still exists, the local name otherwise — so a branch
    /// tracking `origin/other` keeps pushing to `other`, and a publish names
    /// the new remote branch after the local one. See [`push_args`] for the
    /// flags. **Blocking**: it talks to the network.
    pub fn git_push(
        &self,
        id: ProjectId,
        branch: &str,
        remote: &str,
        set_upstream: bool,
        force: bool,
    ) -> Result<RemoteOutput, String> {
        let branch = checked_branch(branch)?;
        let remote = checked_branch(remote)?;
        let repo = self.repo_for(id)?;
        // The upstream is asked of git *now*, for this exact branch — never
        // read from the cached status snapshot, which can still describe the
        // previously checked-out branch and would pair this branch with that
        // one's upstream: `git push origin feature:main`, silently. What git
        // answers still passes the same gate the caller's arguments do
        // before joining an argv.
        let remote_branch = match tracked_upstream(&repo, &branch)?
            .and_then(|upstream| upstream.split_once('/').map(|(_, name)| name.to_owned()))
        {
            Some(name) => checked_branch(&name)?,
            None => branch.clone(),
        };
        let run = self.remote_run(
            &repo,
            "git push",
            push_args(&remote, &branch, &remote_branch, set_upstream, force),
        );
        // Ok or Err alike: even a killed push may have reached the remote.
        self.git_state_changed(id);
        let run = run?;
        Ok(RemoteOutput {
            remote: Some(remote),
            stdout: run.stdout,
            stderr: run.stderr,
            status: run.status,
        })
    }
}

impl crate::Engine {
    /// The shared run of the three network commands: the argv under
    /// [`remote_argv`], and the askpass environment on top of the guest's —
    /// Zed's `run_git_command` sets exactly these three variables around a
    /// remote command (`repository.rs:4033-4036`). Without a helper — no
    /// userland, or a rootfs whose `/tmp` refused us — the command runs
    /// bare, and a private remote fails with git's own words as before.
    fn remote_run(
        &self,
        repo: &Repo,
        label: &str,
        args: Vec<OsString>,
    ) -> Result<GitSplitRun, String> {
        let env = self
            .askpass()
            .map(|server| server.environment())
            .unwrap_or_default();
        run_git_split(
            &repo.userland,
            &repo.repo_root,
            label,
            remote_argv(repo, &args),
            &env,
        )
    }
}

/// [`git_argv`] with the credential cache in front of the subcommand —
/// `-c credential.helper=cache --timeout=3600` when the userland's git ships
/// the helper ([`credential_cache_args`]), nothing otherwise. A git-level
/// option, so it sits with git's own before `fetch`, `pull` or `push`.
fn remote_argv(repo: &Repo, args: &[OsString]) -> Vec<OsString> {
    let mut with_cache = credential_cache_args(repo.userland.rootfs());
    with_cache.extend(args.iter().cloned());
    git_argv(&repo.project_root, &with_cache)
}

/// The `remote/branch` upstream this exact branch tracks *right now*, asked
/// of git — `git rev-parse --abbrev-ref <branch>@{upstream}` — rather than
/// read from the cached status snapshot. The cache can still be describing
/// the previously checked-out branch for the seconds a status run takes on
/// device, and pairing the caller's `branch` with that other branch's
/// upstream is how a refspec goes silently wrong. Zed cannot make that
/// mistake — its name and upstream travel in one snapshot object — so the
/// live question is what keeps parity.
///
/// A failed run is `None`, which is an answer: no upstream configured, or
/// its remote-tracking ref gone — `@{upstream}` resolves only a live one,
/// which is exactly the tracked-and-not-gone filter the push refspec wants
/// (git_panel.rs:3961-3971). `Err` stays what it means everywhere: the guest
/// itself failed.
fn tracked_upstream(repo: &Repo, branch: &str) -> Result<Option<String>, String> {
    let args = vec![
        OsString::from("rev-parse"),
        OsString::from("--abbrev-ref"),
        OsString::from(format!("{branch}@{{upstream}}")),
    ];
    let run = run_git(
        &repo.userland,
        &repo.repo_root,
        "git rev-parse",
        git_argv(&repo.project_root, &args),
    )?;
    if run.status != 0 {
        return Ok(None);
    }
    let name = run.output.trim();
    Ok((!name.is_empty()).then(|| name.to_owned()))
}

/// Whether the branch has an upstream *configured* — `branch.<name>.merge`
/// exists — which is a different question from [`tracked_upstream`]'s: a
/// gone upstream is still configured, and Zed's pull omits the branch
/// argument for it (`branch.upstream.is_none()`, git_panel.rs:3868-3871)
/// where its push falls back to the local name. Asked of git at call time
/// for the same staleness reason.
fn has_configured_upstream(repo: &Repo, branch: &str) -> Result<bool, String> {
    let args = vec![
        OsString::from("config"),
        OsString::from("--get"),
        OsString::from(format!("branch.{branch}.merge")),
    ];
    let run = run_git(
        &repo.userland,
        &repo.repo_root,
        "git config",
        git_argv(&repo.project_root, &args),
    )?;
    Ok(run.status == 0 && !run.output.trim().is_empty())
}

/// The fetch argv: `["fetch", <remote>]`, where no remote means `--all` —
/// Zed's `FetchOptions` displays as the remote's name or the literal flag,
/// and the command is built as `["fetch", &remote_name]`
/// (repository.rs:664-678, 2778-2807).
pub(crate) fn fetch_args(remote: Option<&str>) -> Vec<OsString> {
    vec![
        OsString::from("fetch"),
        OsString::from(remote.unwrap_or("--all")),
    ]
}

/// The pull argv: `["pull"]`, `--rebase` when asked, the remote, then the
/// branch only when it has no upstream (repository.rs:2735-2776).
pub(crate) fn pull_args(
    remote: &str,
    rebase: bool,
    publish_branch: Option<&str>,
) -> Vec<OsString> {
    let mut args: Vec<OsString> = vec![OsString::from("pull")];
    if rebase {
        args.push(OsString::from("--rebase"));
    }
    args.push(OsString::from(remote));
    if let Some(branch) = publish_branch {
        args.push(OsString::from(branch));
    }
    args
}

/// The push argv: at most one flag, and force wins — Zed's options are a
/// single `Option<PushOptions>`, `Force` chosen before `SetUpstream` is even
/// considered (git_panel.rs:3915-3929), so a force push of an unpublished
/// branch carries `--force-with-lease` alone. Then the remote and the
/// `<branch>:<remote_branch>` refspec (repository.rs:2717-2727).
pub(crate) fn push_args(
    remote: &str,
    branch: &str,
    remote_branch: &str,
    set_upstream: bool,
    force: bool,
) -> Vec<OsString> {
    let mut args: Vec<OsString> = vec![OsString::from("push")];
    if force {
        args.push(OsString::from("--force-with-lease"));
    } else if set_upstream {
        args.push(OsString::from("--set-upstream"));
    }
    args.push(OsString::from(remote));
    args.push(OsString::from(format!("{branch}:{remote_branch}")));
    args
}

/// `git remote -v`, one line per name *and direction*: `origin<TAB>url
/// (fetch)`. Names dedupe to one entry each, keeping the first line's URL —
/// the fetch one, which git lists first. Zed reads only the first word of
/// each line (repository.rs:2858-2884); the stricter shape here — a tab, and
/// a parenthesised direction — is what silently drops any stderr line the
/// wrapper of a *merged*-stream caller might feed this, the same trick the
/// branch listing plays with its NULs.
pub(crate) fn parse_remotes(output: &str) -> Vec<RemoteEntry> {
    let mut remotes: Vec<RemoteEntry> = Vec::new();
    for line in output.lines() {
        let Some((name, rest)) = line.split_once('\t') else {
            continue;
        };
        let Some((url, direction)) = rest.rsplit_once(' ') else {
            continue;
        };
        if direction != "(fetch)" && direction != "(push)" {
            continue;
        }
        if name.is_empty() || remotes.iter().any(|remote| remote.name == name) {
            continue;
        }
        remotes.push(RemoteEntry {
            name: name.to_owned(),
            url: url.trim().to_owned(),
        });
    }
    remotes
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::Path;
    use std::process::Command;

    fn strings(args: Vec<OsString>) -> Vec<String> {
        args.into_iter()
            .map(|arg| arg.into_string().unwrap())
            .collect()
    }

    /// Zed's fetch table: no remote is the literal `--all`, a name is the
    /// name.
    #[test]
    fn the_fetch_argv_is_zeds() {
        assert_eq!(strings(fetch_args(None)), ["fetch", "--all"]);
        assert_eq!(strings(fetch_args(Some("origin"))), ["fetch", "origin"]);
    }

    /// The branch rides along only for a branch with no upstream, and
    /// `--rebase` sits before the remote — the order Zed builds.
    #[test]
    fn the_pull_argv_names_the_branch_only_without_an_upstream() {
        assert_eq!(strings(pull_args("origin", false, None)), ["pull", "origin"]);
        assert_eq!(
            strings(pull_args("origin", true, None)),
            ["pull", "--rebase", "origin"]
        );
        assert_eq!(
            strings(pull_args("fork", false, Some("main"))),
            ["pull", "fork", "main"]
        );
        assert_eq!(
            strings(pull_args("fork", true, Some("main"))),
            ["pull", "--rebase", "fork", "main"]
        );
    }

    /// One flag at most, force first — and the refspec, which is what lets a
    /// branch track a remote branch of a different name.
    #[test]
    fn the_push_argv_carries_one_flag_and_the_refspec() {
        assert_eq!(
            strings(push_args("origin", "main", "main", false, false)),
            ["push", "origin", "main:main"]
        );
        assert_eq!(
            strings(push_args("origin", "feature", "feature", true, false)),
            ["push", "--set-upstream", "origin", "feature:feature"]
        );
        // Zed's force push is exactly `--force-with-lease` — never `--force`,
        // and never both flags: force wins even on an unpublished branch.
        assert_eq!(
            strings(push_args("origin", "main", "main", false, true)),
            ["push", "--force-with-lease", "origin", "main:main"]
        );
        assert_eq!(
            strings(push_args("origin", "main", "main", true, true)),
            ["push", "--force-with-lease", "origin", "main:main"]
        );
        assert_eq!(
            strings(push_args("fork", "local", "other", false, false)),
            ["push", "fork", "local:other"]
        );
    }

    /// The two-lines-per-remote listing collapses to one entry each, keeping
    /// the fetch URL, and lines that are not records drop.
    #[test]
    fn a_remote_listing_parses_and_dedupes() {
        let output = "origin\thttps://github.com/cesp99/thragg.git (fetch)\n\
origin\thttps://github.com/cesp99/thragg-push.git (push)\n\
fork\tgit@example.com:someone/fork.git (fetch)\n\
fork\tgit@example.com:someone/fork.git (push)\n";
        let remotes = parse_remotes(output);
        assert_eq!(remotes.len(), 2);
        assert_eq!(remotes[0].name, "origin");
        // The first line — the fetch URL, which is the one github.com
        // detection wants.
        assert_eq!(remotes[0].url, "https://github.com/cesp99/thragg.git");
        assert_eq!(remotes[1].name, "fork");
        assert_eq!(remotes[1].url, "git@example.com:someone/fork.git");

        assert!(parse_remotes("").is_empty());
        // A merged-in stderr line has no tab and no direction; neither
        // becomes a remote called "fatal:".
        assert!(parse_remotes("fatal: not a git repository\n").is_empty());
        assert!(parse_remotes("origin\tno-direction-here\n").is_empty());
    }

    /// Run the host's git hermetically, as the other git test modules do.
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

    /// One of ours through the host's git, minus the program name.
    fn run_argv(dir: &Path, argv: Vec<OsString>) -> std::process::Output {
        let args: Vec<&str> = argv
            .iter()
            .skip(1)
            .map(|arg| arg.to_str().expect("argv is UTF-8 in this test"))
            .collect();
        host_git(dir, &args)
    }

    /// Every flag test above asserts about strings we wrote ourselves. So: a
    /// bare repository standing in for the network, and every remote argv —
    /// publish, plain push, force push, fetch, fetch-all, pull, the two
    /// configured-remote questions, and the listing — through the real
    /// binary.
    #[test]
    fn real_git_accepts_the_remote_argvs() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let remote = dir.path().join("remote.git");
        let work = dir.path().join("work");
        std::fs::create_dir_all(&work).unwrap();
        assert!(
            host_git(dir.path(), &["init", "--quiet", "--bare", "remote.git"])
                .status
                .success()
        );
        assert!(host_git(&work, &["init", "--quiet", "-b", "main"]).status.success());
        std::fs::write(work.join("README"), "one\n").unwrap();
        assert!(host_git(&work, &["add", "README"]).status.success());
        assert!(
            host_git(&work, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );
        assert!(
            host_git(&work, &["remote", "add", "origin", remote.to_str().unwrap()])
                .status
                .success()
        );

        // The listing, through the real output.
        let out = run_argv(&work, git_argv(&work, &["remote", "-v"]));
        assert!(out.status.success());
        let remotes = parse_remotes(&String::from_utf8_lossy(&out.stdout));
        assert_eq!(remotes.len(), 1);
        assert_eq!(remotes[0].name, "origin");
        assert!(remotes[0].url.ends_with("remote.git"));

        // Publish: `push --set-upstream origin main:main`.
        let out = run_argv(
            &work,
            git_argv(&work, &push_args("origin", "main", "main", true, false)),
        );
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );

        // Now both configured-remote questions have an answer.
        let out = run_argv(
            &work,
            git_argv(&work, &["config", "--get", "branch.main.remote"]),
        );
        assert!(out.status.success());
        assert_eq!(String::from_utf8_lossy(&out.stdout).trim(), "origin");
        let out = run_argv(
            &work,
            git_argv(&work, &["rev-parse", "--abbrev-ref", "main@{push}"]),
        );
        assert!(out.status.success());
        assert_eq!(
            String::from_utf8_lossy(&out.stdout)
                .trim()
                .split('/')
                .next(),
            Some("origin")
        );

        // Fetch, both flavours.
        let out = run_argv(&work, git_argv(&work, &fetch_args(Some("origin"))));
        assert!(out.status.success());
        let out = run_argv(&work, git_argv(&work, &fetch_args(None)));
        assert!(out.status.success());

        // Pull with an upstream: no branch argument, and git's up-to-date
        // sentence lands on *stdout* — the fact the toast formatting reads.
        let out = run_argv(&work, git_argv(&work, &pull_args("origin", false, None)));
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        assert!(
            String::from_utf8_lossy(&out.stdout).contains("Already up to date")
        );

        // Force push, after rewriting history so a plain push would refuse.
        std::fs::write(work.join("README"), "two\n").unwrap();
        assert!(host_git(&work, &["add", "README"]).status.success());
        assert!(
            host_git(&work, &["commit", "--quiet", "--amend", "-m", "first, again"])
                .status
                .success()
        );
        let refused = run_argv(
            &work,
            git_argv(&work, &push_args("origin", "main", "main", false, false)),
        );
        assert!(!refused.status.success());
        let out = run_argv(
            &work,
            git_argv(&work, &push_args("origin", "main", "main", false, true)),
        );
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
    }

    /// A guest that is just the host: strips proot's flags and execs the
    /// rest, with a HOME of the test's own so git can read a config —
    /// `git.rs`'s `fake_guest`, repeated here because the two staleness tests
    /// below need the engine's *cache* in the loop rather than a bare argv.
    #[cfg(unix)]
    fn fake_guest(dir: &Path) -> std::path::PathBuf {
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

    /// A working repository whose `main` is published to a bare stand-in
    /// remote, an engine watching it through the fake guest, and the status
    /// cache already describing main-with-its-upstream — the stale snapshot
    /// the two regression tests below then race against.
    #[cfg(unix)]
    fn published_repo(
        dir: &Path,
    ) -> (
        crate::Engine,
        crate::ProjectId,
        std::path::PathBuf,
        std::path::PathBuf,
    ) {
        let remote = dir.join("remote.git");
        let projects = dir.join("projects");
        let work = projects.join("thing");
        std::fs::create_dir_all(&work).unwrap();
        assert!(
            host_git(dir, &["init", "--quiet", "--bare", "remote.git"])
                .status
                .success()
        );
        assert!(host_git(&work, &["init", "--quiet", "-b", "main"]).status.success());
        std::fs::write(work.join("README"), "one\n").unwrap();
        assert!(host_git(&work, &["add", "README"]).status.success());
        assert!(
            host_git(&work, &["commit", "--quiet", "-m", "first"])
                .status
                .success()
        );
        assert!(
            host_git(&work, &["remote", "add", "origin", remote.to_str().unwrap()])
                .status
                .success()
        );
        assert!(
            host_git(&work, &["push", "--quiet", "--set-upstream", "origin", "main"])
                .status
                .success()
        );

        let engine = crate::Engine::new();
        engine.set_userland(&fake_guest(dir), dir, dir, &projects);
        let id = engine.open_project(&work);
        // Poll until the cache holds main's upstream — the snapshot the old
        // code trusted — then stop polling, so nothing refreshes it before
        // the command under test runs.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        loop {
            if let Some(branch) = engine.git_branch(id)
                && branch.upstream.is_some()
            {
                break;
            }
            assert!(
                std::time::Instant::now() < deadline,
                "the status cache never learned the upstream"
            );
            std::thread::sleep(std::time::Duration::from_millis(25));
        }
        (engine, id, work, remote)
    }

    #[cfg(unix)]
    fn sha_of(dir: &Path, rev: &str) -> String {
        let out = host_git(dir, &["rev-parse", rev]);
        assert!(
            out.status.success(),
            "rev-parse {rev}: {}",
            String::from_utf8_lossy(&out.stderr)
        );
        String::from_utf8_lossy(&out.stdout).trim().to_owned()
    }

    /// The wrong-refspec race, pinned: the cached status snapshot still
    /// describes `main` (upstream `origin/main`) while the caller pushes
    /// `feature` — the pairing that used to build `git push origin
    /// feature:main` and silently fast-forward the remote's `main` with
    /// feature's commits. The upstream must be asked of git for the exact
    /// branch being pushed.
    #[test]
    #[cfg(unix)]
    fn a_push_right_after_checkout_cannot_borrow_the_old_branchs_upstream() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, id, work, remote) = published_repo(dir.path());

        // The race, frozen: a new branch, a commit of its own, and a push
        // before any status run has described it.
        assert!(
            host_git(&work, &["checkout", "--quiet", "-b", "feature"])
                .status
                .success()
        );
        std::fs::write(work.join("extra"), "x\n").unwrap();
        assert!(host_git(&work, &["add", "extra"]).status.success());
        assert!(
            host_git(&work, &["commit", "--quiet", "-m", "second"])
                .status
                .success()
        );

        let out = engine.git_push(id, "feature", "origin", false, false).unwrap();
        assert!(out.ok(), "stdout {:?} stderr {:?}", out.stdout, out.stderr);

        // feature went to *feature*; the remote's main did not move.
        assert_eq!(
            sha_of(&remote, "main"),
            sha_of(&work, "main"),
            "origin/main must not receive feature's commits"
        );
        assert_eq!(sha_of(&remote, "feature"), sha_of(&work, "feature"));
    }

    /// Pull's publish-or-not question is the same staleness: pulling a
    /// just-created branch while the cache still says the old branch had an
    /// upstream used to run `git pull origin` with no branch name, and git
    /// refused with "no tracking information". The question belongs to the
    /// branch being pulled, asked at call time.
    #[test]
    #[cfg(unix)]
    fn a_pull_of_a_new_branch_names_it_rather_than_trusting_the_stale_upstream() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let (engine, id, work, _remote) = published_repo(dir.path());

        // A branch the remote holds but the local repo does not track: no
        // `--set-upstream`, so `branch.feature.merge` stays unset.
        assert!(
            host_git(&work, &["checkout", "--quiet", "-b", "feature"])
                .status
                .success()
        );
        assert!(
            host_git(&work, &["push", "--quiet", "origin", "feature"])
                .status
                .success()
        );

        let out = engine.git_pull(id, "feature", "origin", false).unwrap();
        assert!(out.ok(), "stdout {:?} stderr {:?}", out.stdout, out.stderr);
    }
}
