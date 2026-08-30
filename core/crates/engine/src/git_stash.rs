//! The stash: what is in it, and the four things done to it.
//!
//! Zed's `GitStash` (crates/git/src/stash.rs) is copied here, field for
//! field: `git stash list` printed with Zed's own format string, parsed into
//! index, sha, timestamp and message — with the branch pulled out of git's
//! `WIP on <branch>: …` / `On <branch>: …` prefixes, which is what its picker
//! shows on the second line of each row.
//!
//! The commands are Zed's argvs (repository.rs:2601-2731): `stash push
//! --quiet` with `--include-untracked` for Stash All and `--staged` for Stash
//! Staged; `pop`, `apply` and `drop` take an optional `stash@{N}`. Every one
//! of them moves the worktree or the index, so every one bumps the git
//! generation — the panel's list and every gutter re-read afterwards.

use std::ffi::OsString;

use crate::ProjectId;
use crate::git::{git_argv, run_git, run_git_mutating};

/// One `stash@{N}`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct StashEntry {
    /// `N` of `stash@{N}`: 0 is the newest.
    pub index: usize,
    /// The stash commit's full hash.
    pub sha: String,
    /// The message, minus git's `WIP on branch:` prefix.
    pub message: String,
    /// The branch the stash was taken on, when git's prefix named one.
    pub branch: Option<String>,
    /// Seconds since the Unix epoch — the stash commit's committer date.
    pub timestamp: i64,
}

/// What to put in the stash — Zed's `StashKind` (git_panel.rs:205-227).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StashKind {
    /// Everything, untracked files included: `--include-untracked`.
    All,
    /// Tracked changes only, the plain `git stash push`.
    Tracked,
    /// The index only: `--staged` (git 2.35+).
    Staged,
}

impl StashKind {
    /// The JNI's integer spelling, 0/1/2 in declaration order.
    pub fn from_code(code: i32) -> Self {
        match code {
            1 => StashKind::Tracked,
            2 => StashKind::Staged,
            _ => StashKind::All,
        }
    }
}

/// Zed's format: ref, sha, committer date, subject, NUL-separated
/// (repository.rs:2113).
const LIST_FORMAT: &str = "--pretty=format:%gd%x00%H%x00%ct%x00%s";

impl crate::Engine {
    /// Every stash entry, newest first — `git stash list`. **Blocking**.
    pub fn git_stash_list(&self, id: ProjectId) -> Result<Vec<StashEntry>, String> {
        let repo = self.repo_for(id)?;
        let args: Vec<OsString> = vec![
            OsString::from("stash"),
            OsString::from("list"),
            OsString::from(LIST_FORMAT),
        ];
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git stash list",
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            return Err(run.message());
        }
        Ok(parse_stash_list(&run.output))
    }

    /// `git stash push`, of the given kind, with an optional message.
    /// **Blocking**.
    pub fn git_stash_push(
        &self,
        id: ProjectId,
        kind: StashKind,
        message: Option<&str>,
    ) -> Result<(), String> {
        let repo = self.repo_for(id)?;
        let args = stash_push_args(kind, message);
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            "git stash push",
            git_argv(&repo.project_root, &args),
        );
        // Ok or Err alike — a lost run may still have moved the worktree.
        self.git_state_changed(id);
        finish(run)
    }

    /// `git stash pop [stash@{N}]`. **Blocking**.
    pub fn git_stash_pop(&self, id: ProjectId, index: Option<usize>) -> Result<(), String> {
        self.stash_command(id, "pop", index)
    }

    /// `git stash apply [stash@{N}]`. **Blocking**.
    pub fn git_stash_apply(&self, id: ProjectId, index: Option<usize>) -> Result<(), String> {
        self.stash_command(id, "apply", index)
    }

    /// `git stash drop [stash@{N}]`. **Blocking**.
    pub fn git_stash_drop(&self, id: ProjectId, index: Option<usize>) -> Result<(), String> {
        self.stash_command(id, "drop", index)
    }

    fn stash_command(
        &self,
        id: ProjectId,
        verb: &str,
        index: Option<usize>,
    ) -> Result<(), String> {
        let repo = self.repo_for(id)?;
        let args = stash_ref_args(verb, index);
        let run = run_git_mutating(
            &repo.userland,
            &repo.repo_root,
            &format!("git stash {verb}"),
            git_argv(&repo.project_root, &args),
        );
        self.git_state_changed(id);
        finish(run)
    }
}

fn finish(run: Result<crate::git::GitRun, String>) -> Result<(), String> {
    let run = run?;
    if run.status == 0 {
        Ok(())
    } else {
        Err(run.message())
    }
}

/// The push argv, minus the `-C` — Zed's, flag for flag (repository.rs:2611,
/// 2644). The message travels as `--message=<text>`, one argument, so a
/// message that starts with `-` cannot become a flag.
pub(crate) fn stash_push_args(kind: StashKind, message: Option<&str>) -> Vec<OsString> {
    let mut args: Vec<OsString> = vec![
        OsString::from("stash"),
        OsString::from("push"),
        OsString::from("--quiet"),
    ];
    match kind {
        StashKind::All => args.push(OsString::from("--include-untracked")),
        StashKind::Staged => args.push(OsString::from("--staged")),
        StashKind::Tracked => {}
    }
    if let Some(message) = message.map(str::trim).filter(|message| !message.is_empty()) {
        args.push(OsString::from(format!("--message={message}")));
    }
    args
}

/// `stash <verb> [stash@{N}]` (repository.rs:2669-2721).
pub(crate) fn stash_ref_args(verb: &str, index: Option<usize>) -> Vec<OsString> {
    let mut args: Vec<OsString> = vec![OsString::from("stash"), OsString::from(verb)];
    if let Some(index) = index {
        args.push(OsString::from(format!("stash@{{{index}}}")));
    }
    args
}

/// Parse the listing. A line that does not parse is skipped rather than
/// failing the list, as Zed's `GitStash::from_str` does when it has parsed
/// anything at all.
pub(crate) fn parse_stash_list(output: &str) -> Vec<StashEntry> {
    output.lines().filter_map(parse_stash_line).collect()
}

/// `stash@{N}\0<sha>\0<timestamp>\0<message>` (stash.rs `parse_stash_line`).
fn parse_stash_line(line: &str) -> Option<StashEntry> {
    let mut parts = line.splitn(4, '\0');
    let index = parse_stash_index(parts.next()?)?;
    let sha = parts.next()?.trim().to_owned();
    if sha.is_empty() || !sha.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    let timestamp = parts.next()?.trim().parse::<i64>().ok()?;
    let (branch, message) = parse_stash_message(parts.next().unwrap_or(""));
    Some(StashEntry {
        index,
        sha,
        message: message.to_owned(),
        branch: branch.map(str::to_owned),
        timestamp,
    })
}

/// `stash@{N}` → N.
fn parse_stash_index(text: &str) -> Option<usize> {
    text.trim()
        .strip_prefix("stash@{")?
        .strip_suffix('}')?
        .parse()
        .ok()
}

/// Split git's `WIP on <branch>: <message>` and `On <branch>: <message>`
/// prefixes off — Zed's `parse_stash_message`, including its refusal to
/// split when either half would be empty.
pub(crate) fn parse_stash_message(input: &str) -> (Option<&str>, &str) {
    for prefix in ["WIP on ", "On "] {
        if let Some(rest) = input.strip_prefix(prefix) {
            if let Some(colon) = rest.find(": ") {
                let branch = &rest[..colon];
                let message = &rest[colon + 2..];
                if !branch.is_empty() && !message.is_empty() {
                    return (Some(branch), message);
                }
            }
        }
    }
    (None, input)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strs(args: &[OsString]) -> Vec<&str> {
        args.iter().map(|arg| arg.to_str().unwrap()).collect()
    }

    #[test]
    fn the_listing_parses_with_zeds_fields() {
        let output = "stash@{0}\0abc123\01234567890\0WIP on main: first stash\n\
                      stash@{1}\0def456\01234567891\0On feature: second stash\n\
                      stash@{2}\0abcdef\01234567892\0just a message\n";
        let entries = parse_stash_list(output);
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[0].index, 0);
        assert_eq!(entries[0].sha, "abc123");
        assert_eq!(entries[0].timestamp, 1_234_567_890);
        assert_eq!(entries[0].branch.as_deref(), Some("main"));
        assert_eq!(entries[0].message, "first stash");
        assert_eq!(entries[1].branch.as_deref(), Some("feature"));
        assert_eq!(entries[1].message, "second stash");
        assert_eq!(entries[2].branch, None);
        assert_eq!(entries[2].message, "just a message");
    }

    #[test]
    fn an_empty_or_broken_listing_is_no_entries() {
        assert!(parse_stash_list("").is_empty());
        assert!(parse_stash_list("   \n  \n").is_empty());
        // A line without the four fields is skipped, the others kept.
        let output = "garbage\nstash@{0}\0abc\01\0m\n";
        assert_eq!(parse_stash_list(output).len(), 1);
        // A stash index that is not a number.
        assert!(parse_stash_list("stash@{x}\0abc\01\0m\n").is_empty());
    }

    #[test]
    fn the_message_prefixes_are_zeds_edge_cases() {
        assert_eq!(
            parse_stash_message("WIP on main: working on feature"),
            (Some("main"), "working on feature")
        );
        assert_eq!(
            parse_stash_message("On feature-branch: some changes"),
            (Some("feature-branch"), "some changes")
        );
        assert_eq!(parse_stash_message("plain"), (None, "plain"));
        // Neither half may be empty.
        assert_eq!(
            parse_stash_message("WIP on : empty message"),
            (None, "WIP on : empty message")
        );
        assert_eq!(parse_stash_message("On branch-name:"), (None, "On branch-name:"));
    }

    #[test]
    fn push_argvs_are_zeds() {
        assert_eq!(
            strs(&stash_push_args(StashKind::All, None)),
            ["stash", "push", "--quiet", "--include-untracked"]
        );
        assert_eq!(
            strs(&stash_push_args(StashKind::Tracked, Some("  "))),
            ["stash", "push", "--quiet"]
        );
        assert_eq!(
            strs(&stash_push_args(StashKind::Staged, Some("-rf looks like a flag"))),
            ["stash", "push", "--quiet", "--staged", "--message=-rf looks like a flag"]
        );
        assert_eq!(strs(&stash_ref_args("pop", None)), ["stash", "pop"]);
        assert_eq!(strs(&stash_ref_args("drop", Some(3))), ["stash", "drop", "stash@{3}"]);
    }

    #[test]
    fn kind_codes_round_trip() {
        assert_eq!(StashKind::from_code(0), StashKind::All);
        assert_eq!(StashKind::from_code(1), StashKind::Tracked);
        assert_eq!(StashKind::from_code(2), StashKind::Staged);
        assert_eq!(StashKind::from_code(9), StashKind::All);
    }

    #[test]
    fn stash_calls_on_a_closed_project_say_so() {
        let engine = crate::Engine::new();
        assert_eq!(
            engine.git_stash_list(99),
            Err("That project is not open".to_owned())
        );
        assert_eq!(
            engine.git_stash_pop(99, None),
            Err("That project is not open".to_owned())
        );
    }
}
