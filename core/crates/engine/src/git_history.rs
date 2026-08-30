//! Commit history: the list behind the git panel's History tab, and what one
//! commit contains.
//!
//! Separate from [`crate::git`] because it asks git different questions with
//! different costs. Status is polled and cached and has to be quick; history is
//! asked for when somebody opens the tab, a page at a time, and is allowed to
//! take a moment. What they share — the argv builder, the guest command and the
//! shell wrapper that makes a failure readable — lives in `git`.

use std::ffi::OsString;

use crate::ProjectId;
use crate::git::{git_argv, run_git};

/// How many commits one page asks for. Zed reads its graph in chunks of 1000;
/// a phone draws a list, so a page is what a thumb can flick through before
/// the next one is wanted.
pub const PAGE: u32 = 100;

/// One commit, as a list row needs it.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct Commit {
    pub sha: String,
    /// Parent hashes, oldest first. Two of them means a merge, and the graph
    /// needs them; a list row only needs to know there is more than one.
    pub parents: Vec<String>,
    pub author: String,
    pub author_email: String,
    /// Seconds since the Unix epoch.
    pub author_time: i64,
    /// The first line of the message.
    pub subject: String,
    /// `HEAD -> main, origin/main, tag: v1` — git's own `%D`, split.
    pub refs: Vec<String>,
}

impl Commit {
    pub fn is_merge(&self) -> bool {
        self.parents.len() > 1
    }
}

/// One commit in full: its whole message, and what it touched.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct CommitDetails {
    #[serde(flatten)]
    pub commit: Commit,
    /// The whole message, subject line included.
    pub message: String,
    pub files: Vec<CommitFile>,
}

/// A path a commit touched, with git's own status letter.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct CommitFile {
    /// `A`, `M`, `D`, `R`, `C`, `T`.
    pub status: char,
    pub path: String,
    /// Where a rename came from.
    pub original: Option<String>,
}

/// Field and record separators.
///
/// git's `%x00` and `%x1e` put NUL between fields and a record separator
/// between commits, which is the only pair of bytes a commit message and a
/// path cannot both contain — a subject is free to hold newlines' worth of
/// anything else, and splitting on newlines is how naive log parsers break.
const FIELDS: &str = "%H%x00%P%x00%an%x00%ae%x00%at%x00%s%x00%D";
const RECORD: char = '\x1e';

impl crate::Engine {
    /// A page of history, newest first. `skip` pages backwards through it.
    ///
    /// `all_refs` is the graph's walk: every branch, remote and tag rather
    /// than just what HEAD can reach, in `--date-order` — exactly Zed's
    /// `LogSource::All` plus its default `LogOrder` (repository.rs:683,
    /// 711-728). The panel's History tab keeps the plain HEAD walk.
    ///
    /// **Blocking**: it runs git inside the guest.
    pub fn git_log(
        &self,
        id: ProjectId,
        limit: u32,
        skip: u32,
        all_refs: bool,
    ) -> Result<Vec<Commit>, String> {
        let repo = self.repo_for(id)?;
        let mut args: Vec<OsString> = vec![
            OsString::from("log"),
            OsString::from(format!("--max-count={}", limit.clamp(1, 1000))),
            OsString::from(format!("--skip={skip}")),
            // Dates as numbers: a locale-formatted date is a parsing problem
            // and the UI wants "3 days ago" anyway.
            OsString::from(format!("--format={FIELDS}{RECORD}")),
        ];
        if all_refs {
            // Zed's graph argv, in its order: `--date-order` first, then the
            // source args, with `--ignore-missing` "needed in case of unborn
            // HEAD" (repository.rs:690-698, 711-728).
            args.push(OsString::from("--date-order"));
            args.push(OsString::from("--ignore-missing"));
            args.push(OsString::from("--branches"));
            args.push(OsString::from("--remotes"));
            args.push(OsString::from("--tags"));
            args.push(OsString::from("HEAD"));
        }
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git log",
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            // A repository with no commits yet is not an error worth showing:
            // git says "does not have any commits yet" and the panel says the
            // same thing in its own words.
            if run.output.contains("does not have any commits yet") {
                return Ok(Vec::new());
            }
            return Err(run.message());
        }
        Ok(parse_log(&run.output))
    }

    /// One commit: its message and the paths it touched.
    ///
    /// **Blocking**: it runs git inside the guest.
    pub fn git_commit_details(&self, id: ProjectId, sha: &str) -> Result<CommitDetails, String> {
        let sha = checked_sha(sha)?;
        let repo = self.repo_for(id)?;
        let header_args: Vec<OsString> = vec![
            OsString::from("show"),
            OsString::from("--no-patch"),
            OsString::from(format!("--format={FIELDS}{RECORD}%B")),
            OsString::from(&sha),
        ];
        let header = run_git(
            &repo.userland,
            &repo.repo_root,
            "git show",
            git_argv(&repo.project_root, &header_args),
        )?;
        if header.status != 0 {
            return Err(header.message());
        }
        let (fields, message) = header
            .output
            .split_once(RECORD)
            .ok_or_else(|| "git show said nothing about that commit".to_owned())?;
        let commit = parse_commit(fields).ok_or_else(|| "Could not read that commit".to_owned())?;

        let file_args: Vec<OsString> = vec![
            OsString::from("show"),
            OsString::from("--name-status"),
            OsString::from("--format="),
            // Same rule as everywhere else here: NUL-separated, raw bytes, so
            // a path with a newline or a quote in it cannot lie about itself.
            OsString::from("-z"),
            OsString::from(&sha),
        ];
        let listed = run_git(
            &repo.userland,
            &repo.repo_root,
            "git show",
            git_argv(&repo.project_root, &file_args),
        )?;
        let files = if listed.status == 0 {
            parse_name_status(&listed.output)
        } else {
            Vec::new()
        };

        Ok(CommitDetails {
            commit,
            message: message.trim_end().to_owned(),
            files,
        })
    }
}

/// A revision this will hand to git.
///
/// Deliberately narrow: hex, and nothing else. The UI only ever passes back a
/// hash it was given, but "it came from us a moment ago" is not something this
/// function can check, and `--` would not save an argument that git reads as a
/// path or an option.
pub(crate) fn checked_sha(sha: &str) -> Result<String, String> {
    let trimmed = sha.trim();
    if trimmed.len() < 4 || trimmed.len() > 64 || !trimmed.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(format!("{sha:?} is not a commit hash"));
    }
    Ok(trimmed.to_owned())
}

pub(crate) fn parse_log(output: &str) -> Vec<Commit> {
    output
        .split(RECORD)
        .filter_map(|record| parse_commit(record))
        .collect()
}

fn parse_commit(record: &str) -> Option<Commit> {
    let record = record.trim_start_matches(['\n', '\r']);
    if record.trim().is_empty() {
        return None;
    }
    let mut fields = record.split('\0');
    let sha = fields.next()?.trim().to_owned();
    if sha.is_empty() {
        return None;
    }
    let parents = fields
        .next()
        .unwrap_or_default()
        .split_whitespace()
        .map(str::to_owned)
        .collect();
    let author = fields.next().unwrap_or_default().to_owned();
    let author_email = fields.next().unwrap_or_default().to_owned();
    let author_time = fields
        .next()
        .unwrap_or_default()
        .trim()
        .parse::<i64>()
        .unwrap_or(0);
    let subject = fields.next().unwrap_or_default().to_owned();
    let refs = fields
        .next()
        .unwrap_or_default()
        .split(", ")
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .map(str::to_owned)
        .collect();
    Some(Commit {
        sha,
        parents,
        author,
        author_email,
        author_time,
        subject,
        refs,
    })
}

/// `git show --name-status -z`: a status field, then one path, or *two* for a
/// rename — which is why this is a cursor rather than a `chunks(2)`.
pub(crate) fn parse_name_status(output: &str) -> Vec<CommitFile> {
    let mut files = Vec::new();
    let mut parts = output.split('\0').filter(|part| !part.trim().is_empty());
    while let Some(status) = parts.next() {
        let status = status.trim();
        let letter = status.chars().next().unwrap_or('?');
        let Some(path) = parts.next() else { break };
        if letter == 'R' || letter == 'C' {
            // The record is <status>\0<from>\0<to>; the row is about the name
            // the commit produced.
            let Some(to) = parts.next() else { break };
            files.push(CommitFile {
                status: letter,
                path: to.to_owned(),
                original: Some(path.to_owned()),
            });
        } else {
            files.push(CommitFile {
                status: letter,
                path: path.to_owned(),
                original: None,
            });
        }
    }
    files
}

#[cfg(test)]
mod tests {
    use super::*;

    /// git's own output, byte for byte, for a commit with a comma in its
    /// subject and a ref list — both of which a naive split would mangle.
    #[test]
    fn a_log_page_parses() {
        let output = "abc123\0def456 789abc\0Carlo Esposito\0carlo@example.com\01700000000\0\
Fix the thing, and the other thing\0HEAD -> main, origin/main\x1e\
def456\0\0Someone Else\0them@example.com\01699999999\0First commit\0\x1e";
        let commits = parse_log(output);
        assert_eq!(commits.len(), 2);
        assert_eq!(commits[0].sha, "abc123");
        assert_eq!(commits[0].parents, vec!["def456", "789abc"]);
        assert!(commits[0].is_merge());
        assert_eq!(commits[0].subject, "Fix the thing, and the other thing");
        assert_eq!(commits[0].refs, vec!["HEAD -> main", "origin/main"]);
        assert_eq!(commits[0].author_time, 1_700_000_000);
        // The root commit has no parents and no refs.
        assert!(commits[1].parents.is_empty());
        assert!(commits[1].refs.is_empty());
        assert!(!commits[1].is_merge());
    }

    /// A subject with a newline in it — git allows it, and splitting records
    /// on newlines instead of \x1e would produce a phantom commit.
    #[test]
    fn a_newline_in_a_subject_does_not_invent_a_commit() {
        let output = "abc123\0\0A\0a@b\01\0two\nlines\0\x1e";
        let commits = parse_log(output);
        assert_eq!(commits.len(), 1);
        assert_eq!(commits[0].subject, "two\nlines");
    }

    #[test]
    fn a_rename_keeps_both_names() {
        let files = parse_name_status("M\0a.txt\0R100\0old.txt\0new.txt\0D\0gone.txt\0");
        assert_eq!(files.len(), 3);
        assert_eq!(files[0].status, 'M');
        assert_eq!(files[1].status, 'R');
        assert_eq!(files[1].path, "new.txt");
        assert_eq!(files[1].original.as_deref(), Some("old.txt"));
        assert_eq!(files[2].status, 'D');
    }

    /// A truncated record is dropped, not panicked on — the same rule the
    /// status parser follows.
    #[test]
    fn a_truncated_record_is_dropped() {
        assert!(parse_log("").is_empty());
        assert!(parse_log("\x1e\x1e").is_empty());
        assert_eq!(
            parse_log("abc\0\0A\0a@b\0notanumber\0s\0\x1e")[0].author_time,
            0
        );
        assert!(parse_name_status("R100\0only-one-name\0").is_empty());
    }

    /// Only a hash reaches git's argv.
    #[test]
    fn a_revision_that_is_not_a_hash_is_refused() {
        assert!(checked_sha("abc123").is_ok());
        assert!(checked_sha("  ABCDEF  ").is_ok());
        assert!(checked_sha("--upload-pack=evil").is_err());
        assert!(checked_sha("HEAD").is_err());
        assert!(checked_sha("main").is_err());
        assert!(checked_sha("").is_err());
    }
}
