//! Diffs as a *patch*: what changed, line by line, for a diff view to draw.
//!
//! Distinct from [`crate::git_diff`], which answers a different question —
//! "which rows of this open buffer differ from HEAD" — for the gutter, from a
//! cache, without ever materialising the old text. A view has to show both
//! sides, so this runs `git diff` and reads the unified patch it prints.

use std::ffi::OsString;

use crate::ProjectId;
use crate::git::{git_argv, run_git};

/// One file's diff.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct FileDiff {
    /// The path as it is now — the `b/` side, or the `a/` side for a deletion.
    pub path: String,
    /// Where it came from, for a rename.
    pub original: Option<String>,
    /// True when git said the content is binary; [`hunks`] is then empty.
    pub is_binary: bool,
    /// `new file mode` stood in the header: the diff creates this file. What
    /// tells an *empty* new file — no hunks — from a mode-only change, which
    /// is otherwise the same hunkless shape.
    pub created: bool,
    /// `deleted file mode`: the diff removes the file — again the only sign,
    /// when the file was empty.
    pub deleted: bool,
    pub hunks: Vec<PatchHunk>,
}

/// One `@@` block.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct PatchHunk {
    /// First line of the block on each side, 1-based as git counts.
    pub old_start: u32,
    pub new_start: u32,
    /// The `@@ … @@ <heading>` tail: the enclosing function, when git finds one.
    pub heading: String,
    pub lines: Vec<PatchLine>,
}

/// One line of a hunk.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct PatchLine {
    /// `' '` unchanged, `'+'` added, `'-'` removed.
    pub kind: char,
    pub text: String,
    /// Its number on the old side, or 0 for an added line.
    pub old_line: u32,
    /// Its number on the new side, or 0 for a removed line.
    pub new_line: u32,
}

impl crate::Engine {
    /// The working tree against HEAD — or against the index, with [`staged`].
    ///
    /// `path` narrows it to one file; `None` is every changed file, which is
    /// what a "view diff" of the whole project shows.
    ///
    /// An **untracked** file has no diff at all as far as `git diff` is
    /// concerned, which would make a view of one an empty page. It is diffed
    /// against nothing instead, so every line reads as added — which is what
    /// it is.
    ///
    /// **Blocking**: it runs git inside the guest.
    pub fn git_patch(
        &self,
        id: ProjectId,
        path: Option<&str>,
        staged: bool,
    ) -> Result<Vec<FileDiff>, String> {
        let repo = self.repo_for(id)?;
        let mut args: Vec<OsString> = vec![OsString::from("diff")];
        if staged {
            args.push(OsString::from("--staged"));
        } else {
            // Against HEAD, not against the index: "what changed in this file"
            // means everything since the last commit, staged or not. Diffing
            // the worktree against the index hides a change the moment it is
            // staged, which read as "this file matches the last commit".
            args.push(OsString::from("HEAD"));
        }
        args.push(OsString::from("--no-color"));
        args.push(OsString::from("--no-ext-diff"));
        // Renames are worth showing as renames rather than as one file deleted
        // and another added in full.
        args.push(OsString::from("--find-renames"));
        args.push(OsString::from("-U3"));
        if let Some(path) = path {
            args.push(OsString::from("--"));
            args.push(OsString::from(crate::git::checked_path(path)?));
        }
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git diff",
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            return Err(run.message());
        }
        let diffs = parse_patch(&run.output);
        if !diffs.is_empty() {
            return Ok(diffs);
        }

        // Nothing — which for a single path may mean "untracked", not
        // "unchanged". The two are told apart from the status the engine
        // already has, and the patch for an untracked file is built here from
        // the file itself: `git diff --no-index` needs both sides to be paths
        // git can stat, and inside proot `/dev/null` and a bound project
        // directory are not the pair they look like — it failed with "failed
        // to stat" on a file that was right there.
        let Some(path) = path.filter(|_| !staged) else {
            return Ok(Vec::new());
        };
        if !self.is_untracked(id, path) {
            return Ok(Vec::new());
        }
        Ok(new_file_patch(&repo.project_root, path))
    }

    /// The branch's changes since it left `base` — Zed's Branch Diff
    /// (`DeployBranchDiff`, branch_diff.rs:80-137), which diffs against the
    /// *merge base* with the default branch, worktree contents included
    /// (git_store.rs `test_merge_base_status_uses_worktree_contents`).
    ///
    /// `git diff <base>...` is exactly that: git defines it as `git diff
    /// $(git merge-base <base> HEAD)` against the working tree — so a clean
    /// worktree still shows everything the branch has *committed* since the
    /// merge base, which is the whole point of the panel's "View Branch Diff"
    /// button appearing only over a clean tree.
    ///
    /// **Blocking**: it runs git inside the guest.
    pub fn git_branch_patch(&self, id: ProjectId, base: &str) -> Result<Vec<FileDiff>, String> {
        let base = crate::git::checked_branch(base)?;
        let repo = self.repo_for(id)?;
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git diff",
            git_argv(&repo.project_root, &branch_patch_args(&base)),
        )?;
        if run.status != 0 {
            return Err(run.message());
        }
        Ok(parse_patch(&run.output))
    }

    /// What one commit changed, against its first parent — the patch behind
    /// the graph's "View Commit" tab.
    ///
    /// `git show` rather than a second walk: Zed's `load_commit` runs
    /// `git show --format= -z --no-renames --raw --no-abbrev --first-parent`
    /// and rebuilds both texts itself (repository.rs:1384-1490); ours reads
    /// the unified patch the same `show` can print, because that is the shape
    /// the diff view already draws. `--first-parent` is kept — a merge shows
    /// what the merge itself brought in, not everything on the side branch.
    ///
    /// `path` narrows it to one file — the sidebar's per-file "View Changes".
    ///
    /// **Blocking**: it runs git inside the guest.
    pub fn git_commit_patch(
        &self,
        id: ProjectId,
        sha: &str,
        path: Option<&str>,
    ) -> Result<Vec<FileDiff>, String> {
        let sha = crate::git_history::checked_sha(sha)?;
        let repo = self.repo_for(id)?;
        let mut args: Vec<OsString> = vec![
            OsString::from("show"),
            // No header: the sidebar already shows the message, and the
            // parser wants a patch that starts at `diff --git`.
            OsString::from("--format="),
            OsString::from("--first-parent"),
            OsString::from("--no-color"),
            OsString::from("--no-ext-diff"),
            OsString::from("--find-renames"),
            OsString::from("-U3"),
            OsString::from(&sha),
        ];
        if let Some(path) = path {
            args.push(OsString::from("--"));
            args.push(OsString::from(crate::git::checked_path(path)?));
        }
        let run = run_git(
            &repo.userland,
            &repo.repo_root,
            "git show",
            git_argv(&repo.project_root, &args),
        )?;
        if run.status != 0 {
            return Err(run.message());
        }
        Ok(parse_patch(&run.output))
    }

    /// Whether the project's last `git status` called this path untracked.
    ///
    /// Read from the cache every other git surface shares rather than asked
    /// again: it is the same question `??` already answered.
    fn is_untracked(&self, id: ProjectId, path: &str) -> bool {
        self.with_git(id, |git| {
            git.changes
                .iter()
                .any(|change| change.path == path && change.x == b'?')
        })
        .unwrap_or(false)
    }
}

/// The branch diff's argv, minus the `-C` — one place, so the host test can
/// run the very strings the device sends. The `...` spelling is the merge-base
/// diff; `base` has been through [`crate::git::checked_branch`], whose
/// leading-`-` refusal is what keeps the first argument an argument.
fn branch_patch_args(base: &str) -> Vec<OsString> {
    vec![
        OsString::from("diff"),
        OsString::from(format!("{base}...")),
        OsString::from("--no-color"),
        OsString::from("--no-ext-diff"),
        OsString::from("--find-renames"),
        OsString::from("-U3"),
    ]
}

/// A file git has never seen, as the patch it would be: every line added.
///
/// Capped, because this is the one path that materialises a whole file to show
/// it, and a 40 MB log dropped into a project should not be a 40 MB patch in
/// the Java heap.
fn new_file_patch(project_root: &std::path::Path, path: &str) -> Vec<FileDiff> {
    const MAX_BYTES: u64 = 2 * 1024 * 1024;
    let full = project_root.join(path);
    let Ok(meta) = std::fs::metadata(&full) else {
        return Vec::new();
    };
    if !meta.is_file() {
        return Vec::new();
    }
    let binary_or_huge = meta.len() > MAX_BYTES;
    let contents = if binary_or_huge {
        None
    } else {
        std::fs::read(&full).ok()
    };
    // git's own rule: a NUL in the first 8000 bytes means binary.
    let is_binary = contents
        .as_ref()
        .map(|bytes| bytes.iter().take(8000).any(|byte| *byte == 0))
        .unwrap_or(true);
    if is_binary {
        return vec![FileDiff {
            path: path.to_owned(),
            original: None,
            is_binary: true,
            created: true,
            deleted: false,
            hunks: Vec::new(),
        }];
    }
    let text = String::from_utf8_lossy(&contents.unwrap_or_default()).into_owned();
    let lines: Vec<&str> = {
        let mut lines: Vec<&str> = text.split('\n').collect();
        if lines.last() == Some(&"") {
            lines.pop();
        }
        lines
    };
    if lines.is_empty() {
        // An untracked *empty* file: no lines, but still a new file — the
        // `created` flag is all that keeps the view from misreading the
        // hunkless shape as a mode change.
        return vec![FileDiff {
            path: path.to_owned(),
            original: None,
            is_binary: false,
            created: true,
            deleted: false,
            hunks: Vec::new(),
        }];
    }
    let hunk = PatchHunk {
        old_start: 0,
        new_start: 1,
        heading: String::new(),
        lines: lines
            .iter()
            .enumerate()
            .map(|(index, line)| PatchLine {
                kind: '+',
                text: (*line).to_owned(),
                old_line: 0,
                new_line: index as u32 + 1,
            })
            .collect(),
    };
    vec![FileDiff {
        path: path.to_owned(),
        original: None,
        is_binary: false,
        created: true,
        deleted: false,
        hunks: vec![hunk],
    }]
}

/// Read `git diff`'s unified output.
///
/// Written against git's format rather than a general patch grammar: the input
/// is always our own `git diff --no-color -U3`, so the only surprises are the
/// ones git itself produces — binary files, renames with no content change,
/// mode-only changes, and `\ No newline at end of file`.
pub(crate) fn parse_patch(output: &str) -> Vec<FileDiff> {
    let mut files: Vec<FileDiff> = Vec::new();
    let mut old_line = 0u32;
    let mut new_line = 0u32;
    // The `---`/`+++` pair is the authority on the two names, not the
    // `diff --git a/x b/y` header: that header has no unambiguous separator
    // (a file called `x b/y` splits wrongly) and git *quotes* a non-ASCII
    // name in it, which left the path empty. These two lines carry one path
    // each, and `/dev/null` for the side that does not exist.
    let mut old_path: Option<String> = None;
    // Kept for the shapes that have no `---`/`+++` at all: a binary file, and
    // a change of mode with no content. Ambiguous for a name containing
    // " b/", which is why it is the fallback rather than the rule.
    let mut headers: Vec<String> = Vec::new();

    // A trailing newline is not a line; anything else is kept verbatim,
    // carriage returns included — a CRLF file's diff is about its bytes.
    let mut lines: Vec<&str> = output.split('\n').collect();
    if lines.last() == Some(&"") {
        lines.pop();
    }

    for line in lines {
        if let Some(rest) = line.strip_prefix("diff --git ") {
            old_path = None;
            headers.push(rest.trim_end_matches('\r').to_owned());
            files.push(FileDiff {
                path: String::new(),
                original: None,
                is_binary: false,
                created: false,
                deleted: false,
                hunks: Vec::new(),
            });
            continue;
        }
        let Some(file) = files.last_mut() else {
            continue;
        };

        if let Some(rest) = line.strip_prefix("--- ") {
            let rest = rest.strip_suffix('\r').unwrap_or(rest);
            old_path = strip_prefix_path(rest, "a/");
            if file.path.is_empty() {
                if let Some(path) = &old_path {
                    file.path = path.clone();
                }
            }
            continue;
        }
        if let Some(rest) = line.strip_prefix("+++ ") {
            let rest = rest.strip_suffix('\r').unwrap_or(rest);
            if let Some(path) = strip_prefix_path(rest, "b/") {
                file.path = path;
            }
            continue;
        }
        if line.starts_with("new file mode ") {
            file.created = true;
            continue;
        }
        if line.starts_with("deleted file mode ") {
            file.deleted = true;
            continue;
        }
        if let Some(from) = line.strip_prefix("rename from ") {
            file.original = Some(from.trim_end_matches('\r').to_owned());
            continue;
        }
        if let Some(to) = line.strip_prefix("rename to ") {
            file.path = to.trim_end_matches('\r').to_owned();
            continue;
        }
        if line.starts_with("Binary files ") || line.starts_with("GIT binary patch") {
            file.is_binary = true;
            continue;
        }
        if let Some(header) = line.strip_prefix("@@ ") {
            let Some((ranges, heading)) = header.split_once("@@") else {
                continue;
            };
            let Some((old, new)) = parse_ranges(ranges) else {
                continue;
            };
            old_line = old;
            new_line = new;
            // A rename with a content change names both sides; `---`/`+++`
            // give the same two names, so this is belt and braces.
            if file.original.is_none() {
                if let Some(from) = &old_path {
                    if *from != file.path {
                        file.original = Some(from.clone());
                    }
                }
            }
            file.hunks.push(PatchHunk {
                old_start: old,
                new_start: new,
                heading: heading.trim().to_owned(),
                lines: Vec::new(),
            });
            continue;
        }
        let Some(hunk) = file.hunks.last_mut() else {
            continue;
        };
        // Inside a hunk. `\ No newline at end of file` is a note about the
        // line above, not a line of its own.
        if line.starts_with('\\') {
            continue;
        }
        let (kind, text) = match line.chars().next() {
            Some('+') => ('+', &line[1..]),
            Some('-') => ('-', &line[1..]),
            Some(' ') => (' ', &line[1..]),
            // A truly empty line inside a hunk is a context line whose
            // leading space something along the way has eaten.
            None => (' ', ""),
            // Anything else ends the hunk.
            _ => continue,
        };
        hunk.lines.push(PatchLine {
            kind,
            text: text.to_owned(),
            old_line: if kind == '+' { 0 } else { old_line },
            new_line: if kind == '-' { 0 } else { new_line },
        });
        if kind != '+' {
            old_line += 1;
        }
        if kind != '-' {
            new_line += 1;
        }
    }
    // Binary files and mode-only changes have no `---`/`+++` pair; their name
    // is only in the header.
    for (index, file) in files.iter_mut().enumerate() {
        if file.path.is_empty() {
            if let Some(header) = headers.get(index) {
                file.path = header_path(header);
            }
        }
    }
    files.retain(|file| !file.path.is_empty());
    files
}

/// `a/logo.png b/logo.png` → `logo.png`.
///
/// Split from the *right*, because the left-hand name is the one that may
/// contain " b/" — and git quotes a name with anything stranger in it, which
/// is why this is only ever reached for binary and mode-only changes.
fn header_path(header: &str) -> String {
    match header.rsplit_once(" b/") {
        Some((_, b)) => b.to_owned(),
        None => String::new(),
    }
}

/// `a/src/main.rs` → `src/main.rs`; `/dev/null` → nothing.
fn strip_prefix_path(rest: &str, prefix: &str) -> Option<String> {
    if rest == "/dev/null" {
        return None;
    }
    Some(rest.strip_prefix(prefix).unwrap_or(rest).to_owned())
}

/// `-12,7 +12,9` → the two starting lines.
fn parse_ranges(ranges: &str) -> Option<(u32, u32)> {
    let mut old = None;
    let mut new = None;
    for part in ranges.split_whitespace() {
        let (sign, rest) = part.split_at(1);
        let start = rest
            .split(',')
            .next()
            .and_then(|number| number.parse::<u32>().ok())?;
        match sign {
            "-" => old = Some(start),
            "+" => new = Some(start),
            _ => {}
        }
    }
    Some((old?, new?))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Real `git diff` output, assembled line by line.
    ///
    /// Not a `\`-continued string literal: Rust's continuation eats the
    /// *leading whitespace* of the next line, which silently strips the space
    /// that marks a context line — so the fixture stopped being a patch and
    /// the parser looked wrong.
    fn patch() -> String {
        [
            "diff --git a/src/main.rs b/src/main.rs",
            "index 1234567..89abcde 100644",
            "--- a/src/main.rs",
            "+++ b/src/main.rs",
            "@@ -1,4 +1,5 @@ fn main()",
            " fn main() {",
            "-    println!(\"old\");",
            "+    println!(\"new\");",
            "+    println!(\"and another\");",
            " }",
            " ",
            "diff --git a/notes.md b/notes.md",
            "--- a/notes.md",
            "+++ b/notes.md",
            "@@ -10,2 +11,2 @@",
            "-gone",
            "+here",
        ]
        .join("\n")
    }

    #[test]
    fn a_patch_becomes_files_and_hunks() {
        let files = parse_patch(&patch());
        assert_eq!(files.len(), 2);
        assert_eq!(files[0].path, "src/main.rs");
        assert_eq!(files[0].hunks.len(), 1);
        let hunk = &files[0].hunks[0];
        assert_eq!(hunk.old_start, 1);
        assert_eq!(hunk.new_start, 1);
        assert_eq!(hunk.heading, "fn main()");
        assert_eq!(hunk.lines.len(), 6);
        assert_eq!(files[1].path, "notes.md");
        assert_eq!(files[1].hunks[0].old_start, 10);
        assert_eq!(files[1].hunks[0].new_start, 11);
    }

    /// The numbers down each side are what a diff view puts in its gutter, and
    /// they are the thing a naive parser gets wrong: an added line has no old
    /// number, a removed line has no new one, and context advances both.
    #[test]
    fn every_line_carries_its_number_on_the_side_it_exists_on() {
        let lines = &parse_patch(&patch())[0].hunks[0].lines;
        assert_eq!(
            (lines[0].kind, lines[0].old_line, lines[0].new_line),
            (' ', 1, 1)
        );
        assert_eq!(
            (lines[1].kind, lines[1].old_line, lines[1].new_line),
            ('-', 2, 0)
        );
        assert_eq!(
            (lines[2].kind, lines[2].old_line, lines[2].new_line),
            ('+', 0, 2)
        );
        assert_eq!(
            (lines[3].kind, lines[3].old_line, lines[3].new_line),
            ('+', 0, 3)
        );
        // Context after the change: the old side has advanced by one line and
        // the new side by two.
        assert_eq!(
            (lines[4].kind, lines[4].old_line, lines[4].new_line),
            (' ', 3, 4)
        );
    }

    #[test]
    fn a_rename_keeps_both_names() {
        let files = parse_patch(
            "diff --git a/old.txt b/new.txt\n\
similarity index 92%\n\
rename from old.txt\n\
rename to new.txt\n\
@@ -1 +1 @@\n\
-a\n\
+b\n",
        );
        assert_eq!(files[0].path, "new.txt");
        assert_eq!(files[0].original.as_deref(), Some("old.txt"));
    }

    #[test]
    fn a_binary_file_says_so_instead_of_pretending() {
        let files = parse_patch(
            "diff --git a/logo.png b/logo.png\n\
Binary files a/logo.png and b/logo.png differ\n",
        );
        assert!(files[0].is_binary);
        assert!(files[0].hunks.is_empty());
    }

    /// `\ No newline at end of file` is a note about the line above it.
    #[test]
    fn the_no_newline_marker_is_not_a_line() {
        let files = parse_patch(
            "diff --git a/a b/a\n@@ -1 +1 @@\n-one\n\\ No newline at end of file\n+one\n",
        );
        assert_eq!(files[0].hunks[0].lines.len(), 2);
    }

    /// The shapes the skeptic fed it: a name git quotes, a name with " b/" in
    /// it, a new file, a deletion, CRLF content, and a hunk header with no
    /// counts. All of them come from `---`/`+++` rather than from the header,
    /// which is what makes them work.
    #[test]
    fn the_paths_come_from_the_two_marker_lines() {
        let quoted = parse_patch(
            &[
                "diff --git \"a/caff\\303\\250.txt\" \"b/caff\\303\\250.txt\"",
                "--- a/caffè.txt",
                "+++ b/caffè.txt",
                "@@ -1 +1 @@",
                "-a",
                "+b",
            ]
            .join("\n"),
        );
        assert_eq!(quoted[0].path, "caffè.txt");

        let awkward = parse_patch(
            &[
                "diff --git a/x b/y b/x b/y",
                "--- a/x b/y",
                "+++ b/x b/y",
                "@@ -1 +1 @@",
                "-a",
                "+b",
            ]
            .join("\n"),
        );
        assert_eq!(awkward[0].path, "x b/y");

        let created = parse_patch(
            &[
                "diff --git a/new.txt b/new.txt",
                "new file mode 100644",
                "--- /dev/null",
                "+++ b/new.txt",
                "@@ -0,0 +1 @@",
                "+hello",
            ]
            .join("\n"),
        );
        assert_eq!(created[0].path, "new.txt");
        assert!(created[0].original.is_none(), "a new file was not renamed");
        assert!(created[0].created, "`new file mode` marks a creation");
        assert!(!created[0].deleted);
        assert_eq!(created[0].hunks[0].lines[0].new_line, 1);

        let deleted = parse_patch(
            &[
                "diff --git a/gone.txt b/gone.txt",
                "deleted file mode 100644",
                "--- a/gone.txt",
                "+++ /dev/null",
                "@@ -1 +0,0 @@",
                "-bye",
            ]
            .join("\n"),
        );
        assert_eq!(deleted[0].path, "gone.txt");
        assert!(deleted[0].original.is_none());
        assert!(deleted[0].deleted, "`deleted file mode` marks a deletion");
        assert!(!deleted[0].created);
    }

    /// An **empty** file added or deleted prints a bare header: no `---`/`+++`
    /// pair, no hunks — the exact shape of a mode-only change, which is why
    /// the flags exist. The device test caught the diff view captioning a
    /// commit's empty new `.gitignore` with "Only the file's mode changed."
    #[test]
    fn empty_files_are_creations_and_deletions_not_mode_changes() {
        let created = parse_patch(
            &[
                "diff --git a/empty b/empty",
                "new file mode 100644",
                "index 0000000..e69de29",
                "",
            ]
            .join("\n"),
        );
        assert_eq!(created[0].path, "empty", "the header names the file");
        assert!(created[0].created);
        assert!(!created[0].deleted);
        assert!(created[0].hunks.is_empty());

        let deleted = parse_patch(
            &[
                "diff --git a/gone b/gone",
                "deleted file mode 100644",
                "index e69de29..0000000",
                "",
            ]
            .join("\n"),
        );
        assert_eq!(deleted[0].path, "gone");
        assert!(deleted[0].deleted);
        assert!(!deleted[0].created);

        // And a real mode-only change stays exactly what it was: neither.
        let mode = parse_patch(
            &[
                "diff --git a/tool.sh b/tool.sh",
                "old mode 100644",
                "new mode 100755",
                "",
            ]
            .join("\n"),
        );
        assert_eq!(mode[0].path, "tool.sh");
        assert!(!mode[0].created);
        assert!(!mode[0].deleted);
        assert!(mode[0].hunks.is_empty());
    }

    /// A carriage return belongs to the line's content; dropping it makes the
    /// diff of a CRLF file disagree with the file.
    #[test]
    fn crlf_content_keeps_its_carriage_return() {
        let files = parse_patch(
            &[
                "diff --git a/a.txt b/a.txt",
                "--- a/a.txt",
                "+++ b/a.txt",
                "@@ -1 +1 @@",
                "-one\r",
                "+two\r",
            ]
            .join("\n"),
        );
        assert_eq!(files[0].hunks[0].lines[0].text, "one\r");
        assert_eq!(files[0].hunks[0].lines[1].text, "two\r");
    }

    /// `@@ -1 +1 @@` — no counts at all, which git writes for a single line.
    #[test]
    fn a_hunk_header_without_counts_still_parses() {
        let files = parse_patch(
            &[
                "diff --git a/a b/a",
                "--- a/a",
                "+++ b/a",
                "@@ -7 +9 @@",
                " ctx",
            ]
            .join("\n"),
        );
        assert_eq!(files[0].hunks[0].old_start, 7);
        assert_eq!(files[0].hunks[0].new_start, 9);
        assert_eq!(files[0].hunks[0].lines[0].old_line, 7);
        assert_eq!(files[0].hunks[0].lines[0].new_line, 9);
    }

    #[test]
    fn nothing_in_nothing_out() {
        assert!(parse_patch("").is_empty());
        assert!(parse_patch("not a patch at all\n").is_empty());
    }

    /// The branch-diff argv through the real binary: a branch's committed
    /// changes show against the merge base even over a **clean worktree**,
    /// which is the only state the panel's "View Branch Diff" button is
    /// reachable in — and commits made on `main` after the branch left it do
    /// not bleed in, which is what `...` (merge base) means and a plain
    /// two-dot diff would get wrong.
    #[test]
    fn real_git_branch_diff_shows_the_branch_against_the_merge_base() {
        use std::process::Command;
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        let git = |args: &[&str]| {
            let out = Command::new("git")
                .current_dir(repo)
                .env("GIT_AUTHOR_NAME", "t")
                .env("GIT_AUTHOR_EMAIL", "t@example.com")
                .env("GIT_COMMITTER_NAME", "t")
                .env("GIT_COMMITTER_EMAIL", "t@example.com")
                .args(args)
                .output()
                .unwrap();
            assert!(
                out.status.success(),
                "git {args:?}: {}",
                String::from_utf8_lossy(&out.stderr)
            );
            out
        };
        git(&["init", "--quiet", "-b", "main"]);
        std::fs::write(repo.join("README"), "one\n").unwrap();
        git(&["add", "README"]);
        git(&["commit", "--quiet", "-m", "first"]);
        git(&["checkout", "--quiet", "-b", "feature"]);
        std::fs::write(repo.join("feature.txt"), "branch work\n").unwrap();
        git(&["add", "feature.txt"]);
        git(&["commit", "--quiet", "-m", "branch work"]);
        // `main` moves on after the branch left it; the merge-base diff must
        // not report main's own change as the branch's.
        git(&["checkout", "--quiet", "main"]);
        std::fs::write(repo.join("mainline.txt"), "mainline\n").unwrap();
        git(&["add", "mainline.txt"]);
        git(&["commit", "--quiet", "-m", "mainline"]);
        git(&["checkout", "--quiet", "feature"]);

        let argv = crate::git::git_argv(repo, &branch_patch_args("main"));
        let out = Command::new(&argv[0])
            .args(&argv[1..])
            .current_dir(repo)
            .output()
            .unwrap();
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        let files = parse_patch(&String::from_utf8_lossy(&out.stdout));
        assert_eq!(files.len(), 1, "only the branch's own change shows");
        assert_eq!(files[0].path, "feature.txt");
        assert_eq!(files[0].hunks[0].lines[0].kind, '+');
    }
}
