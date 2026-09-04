//! One hunk at a time: stage it, unstage it, or put it back.
//!
//! Zed's model is `BufferDiff::stage_or_unstage_hunks` (crates/buffer_diff):
//! the gutter's hunks are the buffer against **HEAD**, and a hunk is "staged"
//! when the **index** already holds the buffer's text for it. Staging one
//! therefore means writing a new index text — the old index with just that
//! hunk's rows replaced by the buffer's — and unstaging means the same with
//! HEAD's rows put back. Neither is a `git add`: `git add` takes the whole
//! file, and the whole point of a hunk is that it is not the whole file.
//!
//! Zed writes the new index blob straight into the object store. This engine
//! cannot open a pipe into the guest, so it does the equivalent through the
//! porcelain instead: the difference between the old index text and the new
//! one is written out as a unified patch and handed to `git apply --cached`,
//! which is exactly "make the index look like this" for the rows the patch
//! names and nothing else. Both directions go through the one patch writer,
//! so an unstage is a stage's patch read the other way.
//!
//! Restoring a hunk (`git::Restore`) never touches git at all: it is an
//! *edit* of the buffer that puts HEAD's rows back, undoable like any other
//! (editor/src/git.rs `restore_hunks_in_ranges`). Only a path with no open
//! buffer — the project diff's row for a file nobody has open — writes the
//! file on disk instead.
//!
//! The row arithmetic lives in pure functions and is tested on strings; the
//! patch they produce is tested against the host's real git, because "a patch
//! that looks right" and "a patch git applies" have differed before.

use std::ffi::OsString;
use std::ops::Range;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use imara_diff::{Algorithm, Diff, InternedInput};

use crate::git::{self, git_argv, run_git_mutating};
use crate::git_diff::{self, Hunk, hunks_between};
use crate::guest::Userland;
use crate::{BufferId, ProjectId};

/// Lines of context on each side of a change — git's own default, and what
/// makes the patch land on the right rows even if the index moved a little
/// between the read and the apply.
const CONTEXT: usize = 3;

/// One gutter hunk and whether the index already holds it — Zed's
/// `DiffHunkSecondaryStatus`, reduced to the one bit the header button reads.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
pub struct HunkState {
    #[serde(flatten)]
    pub hunk: Hunk,
    /// True when the index has the buffer's text for these rows, so the
    /// header offers Unstage; false — unstaged, or partly staged — offers
    /// Stage, exactly as Zed's `has_secondary_hunk` decides
    /// (editor/src/git.rs:3091).
    pub staged: bool,
}

/// The three texts every question here is asked against.
struct Texts {
    /// The file at HEAD; `""` for a file HEAD has never seen.
    head: Arc<String>,
    /// The file in the index; `None` when the index has no entry for it.
    index: Option<Arc<String>>,
    /// The file as it is now — the buffer if one is open, else the disk.
    work: String,
}

impl crate::Engine {
    /// The gutter's hunks with their staged bit.
    ///
    /// **Blocking**: one `git show` of the index. Ask when hunks are expanded
    /// or the git generation moved, off the main thread — never per frame.
    pub fn git_hunk_states(&self, id: BufferId) -> Result<Vec<HunkState>, String> {
        let path = self.buffer_file(id)?;
        let texts = self.texts_for(id, &path)?;
        Ok(hunk_states(&texts.head, texts.index.as_deref().map(String::as_str), &texts.work))
    }

    /// Stage (or, with `stage == false`, unstage) every hunk touching buffer
    /// rows `rows`. **Blocking**: git runs in the guest.
    ///
    /// Nothing to do — the hunk is already where it was asked to go — is a
    /// success, not an error: a toggle pressed twice quickly should settle,
    /// not complain.
    pub fn git_hunk_stage(
        &self,
        id: BufferId,
        rows: Range<u32>,
        stage: bool,
    ) -> Result<(), String> {
        let path = self.buffer_file(id)?;
        let texts = self.texts_for(id, &path)?;
        self.apply_index_text(&path, &texts, rows, stage)
    }

    /// Put HEAD's rows back over every hunk touching `rows` — an ordinary,
    /// undoable edit of the buffer. Nothing runs in the guest unless the base
    /// text is not cached yet.
    pub fn git_hunk_restore(&self, id: BufferId, rows: Range<u32>) -> Result<(), String> {
        let path = self.buffer_file(id)?;
        let head = self.head_text_for(id, &path)?;
        let work = self.text(id).map_err(|err| err.to_string())?;
        let Some((start, end, replacement)) = restore_edit(&work, &head, rows) else {
            return Ok(());
        };
        self.edit(id, start, end, &replacement)
            .map(|_| ())
            .map_err(|err| err.to_string())
    }

    /// [`Engine::git_hunk_states`] for a project-relative path — the project
    /// diff's route, where the file may have no buffer. With one open, the
    /// buffer's text is the truth, exactly as it is for the gutter.
    pub fn git_path_hunk_states(
        &self,
        project: ProjectId,
        path: &str,
    ) -> Result<Vec<HunkState>, String> {
        let full = self.project_file(project, path)?;
        if let Some(id) = self.buffer_for_path(&full) {
            return self.git_hunk_states(id);
        }
        let texts = self.texts_for_disk(&full)?;
        Ok(hunk_states(&texts.head, texts.index.as_deref().map(String::as_str), &texts.work))
    }

    /// [`Engine::git_hunk_stage`] by path. `rows` are rows of the file as it
    /// is now — the `+` side of the patch the diff view drew, 0-based.
    pub fn git_path_hunk_stage(
        &self,
        project: ProjectId,
        path: &str,
        rows: Range<u32>,
        stage: bool,
    ) -> Result<(), String> {
        let full = self.project_file(project, path)?;
        let result = match self.buffer_for_path(&full) {
            Some(id) => self.git_hunk_stage(id, rows, stage),
            None => {
                let texts = self.texts_for_disk(&full)?;
                self.apply_index_text(&full, &texts, rows, stage)
            }
        };
        // The panel's list and every gutter: the index moved.
        self.git_state_changed(project);
        result
    }

    /// [`Engine::git_hunk_restore`] by path. A file with no buffer is written
    /// on disk, which is what "restore" means for it; one with a buffer is
    /// edited, so the restore is undoable and the file is dirty until saved —
    /// the same split Zed makes between an editor and a project entry.
    pub fn git_path_hunk_restore(
        &self,
        project: ProjectId,
        path: &str,
        rows: Range<u32>,
    ) -> Result<(), String> {
        let full = self.project_file(project, path)?;
        if let Some(id) = self.buffer_for_path(&full) {
            return self.git_hunk_restore(id, rows);
        }
        let texts = self.texts_for_disk(&full)?;
        let Some((start, end, replacement)) = restore_edit(&texts.work, &texts.head, rows) else {
            return Ok(());
        };
        let mut restored = String::with_capacity(texts.work.len() + replacement.len());
        restored.push_str(&texts.work[..start]);
        restored.push_str(&replacement);
        restored.push_str(&texts.work[end..]);
        crate::file::write_atomically_io(&full, &restored)
            .map_err(|err| format!("Could not write {}: {err}", full.display()))?;
        self.git_state_changed(project);
        Ok(())
    }

    // ---- texts ----------------------------------------------------------

    fn buffer_file(&self, id: BufferId) -> Result<PathBuf, String> {
        self.buffer_path(id)
            .ok_or_else(|| "That buffer has no file".to_owned())
    }

    fn project_file(&self, project: ProjectId, path: &str) -> Result<PathBuf, String> {
        let path = git::checked_path(path)?;
        let root = self
            .project_root(project)
            .ok_or_else(|| "That project is not open".to_owned())?;
        Ok(root.join(path))
    }

    /// The open project one of whose folders holds `path`, if any — so a hunk
    /// staged from a buffer can invalidate that project's status cache the way
    /// a panel stage does. The deepest root wins when projects (or the folders
    /// inside one) nest.
    fn project_containing(&self, path: &Path) -> Option<ProjectId> {
        let projects: Vec<(ProjectId, Vec<PathBuf>)> = self
            .projects
            .lock()
            .unwrap()
            .iter()
            .map(|(id, state)| {
                let roots = state
                    .lock()
                    .unwrap()
                    .worktrees
                    .iter()
                    .map(|tree| tree.root.clone())
                    .collect();
                (*id, roots)
            })
            .collect();
        projects
            .into_iter()
            .filter_map(|(id, roots)| {
                roots
                    .into_iter()
                    .filter(|root| path.starts_with(root))
                    .max_by_key(|root| root.as_os_str().len())
                    .map(|root| (id, root))
            })
            .max_by_key(|(_, root)| root.as_os_str().len())
            .map(|(id, _)| id)
    }

    fn installed_userland(&self) -> Result<Arc<Userland>, String> {
        self.userland()
            .filter(|userland| userland.is_installed())
            .ok_or_else(|| "The Linux userland is not installed".to_owned())
    }

    /// HEAD's text for a buffer's file: the gutter's cached base when it is
    /// current, else fetched now.
    fn head_text_for(&self, id: BufferId, path: &Path) -> Result<Arc<String>, String> {
        if let Some(base) = self.cached_base(id) {
            return Ok(base);
        }
        let userland = self.installed_userland()?;
        git_diff::head_text(&userland, path).ok_or_else(|| "Could not read the file at HEAD".to_owned())
    }

    fn texts_for(&self, id: BufferId, path: &Path) -> Result<Texts, String> {
        let head = self.head_text_for(id, path)?;
        let userland = self.installed_userland()?;
        let index = git_diff::index_text(&userland, path);
        let work = self.text(id).map_err(|err| err.to_string())?;
        Ok(Texts { head, index, work })
    }

    fn texts_for_disk(&self, path: &Path) -> Result<Texts, String> {
        let userland = self.installed_userland()?;
        let head = git_diff::head_text(&userland, path)
            .ok_or_else(|| "Could not read the file at HEAD".to_owned())?;
        let index = git_diff::index_text(&userland, path);
        let work = std::fs::read_to_string(path)
            .map_err(|err| format!("Could not read {}: {err}", path.display()))?;
        Ok(Texts { head, index, work })
    }

    /// Work out the new index text and apply it with `git apply --cached`.
    fn apply_index_text(
        &self,
        path: &Path,
        texts: &Texts,
        rows: Range<u32>,
        stage: bool,
    ) -> Result<(), String> {
        let index = texts.index.as_deref().map(String::as_str);
        let Some(new_index) = staged_index_text(&texts.head, index, &texts.work, rows, stage)
        else {
            return Ok(());
        };
        let userland = self.installed_userland()?;
        let dir = path
            .parent()
            .ok_or_else(|| "That file has no directory".to_owned())?;
        let repo_root = git::repo_root_of(dir).ok_or_else(|| "Not a git repository".to_owned())?;
        let relative = path
            .strip_prefix(&repo_root)
            .map_err(|_| "That file is outside the repository".to_owned())?
            .to_string_lossy()
            .into_owned();
        let patch = unified_patch(&relative, index, &new_index);

        // The patch file has to be a path the guest can read, and the
        // repository is the one directory both sides are guaranteed to
        // share. `.git/` is the tidy place; a linked worktree's `.git` is a
        // file, and the fallback beside it lives for one command.
        let patch_path = patch_file_path(&repo_root);
        std::fs::write(&patch_path, &patch)
            .map_err(|err| format!("Could not write the patch: {err}"))?;
        let args: Vec<OsString> = vec![
            OsString::from("apply"),
            OsString::from("--cached"),
            OsString::from("--whitespace=nowarn"),
            OsString::from("--"),
            patch_path.as_os_str().to_owned(),
        ];
        let run = run_git_mutating(
            &userland,
            &repo_root,
            "git apply --cached",
            git_argv(&repo_root, &args),
        );
        let _ = std::fs::remove_file(&patch_path);
        // The index may have moved on the Err path too: tell every gutter,
        // and the panel of whichever project holds the file.
        match self.project_containing(path) {
            Some(project) => self.git_state_changed(project),
            None => git::bump_generation(),
        }
        let run = run?;
        if run.status == 0 {
            Ok(())
        } else {
            Err(run.message())
        }
    }
}

/// Where the one-shot patch is written for the guest to read.
fn patch_file_path(repo_root: &Path) -> PathBuf {
    let git_dir = repo_root.join(".git");
    if git_dir.is_dir() {
        git_dir.join("thragg-hunk.patch")
    } else {
        repo_root.join(".thragg-hunk.patch")
    }
}

/// Whether two row ranges touch — inclusive at both ends, so a deletion (an
/// empty range on a boundary) matches the hunk it sits against. Zed compares
/// its hunk anchors with the same `<=` pair (buffer_diff.rs
/// `stage_or_unstage_hunks`). Two hunks of *one* diff are always a common
/// row apart and never touch; the test is only ever between two diffs.
fn touches(a: &Range<u32>, b: &Range<u32>) -> bool {
    a.start <= b.end && b.start <= a.end
}

fn rows_of(hunk: &Hunk) -> Range<u32> {
    hunk.start_row..hunk.end_row
}

fn old_rows_of(hunk: &Hunk) -> Range<u32> {
    hunk.old_start..hunk.old_start + hunk.old_rows
}

/// Every HEAD hunk of `work`, with whether the index already holds it.
///
/// A hunk is staged when no hunk of the index-against-work diff touches it:
/// the index has the buffer's rows there. Partly staged counts as unstaged,
/// which is the button Zed shows for it too.
pub(crate) fn hunk_states(head: &str, index: Option<&str>, work: &str) -> Vec<HunkState> {
    let hunks = hunks_between(head, work);
    let unstaged = index.map(|index| hunks_between(index, work));
    hunks
        .into_iter()
        .map(|hunk| {
            let staged = match &unstaged {
                // No index entry: nothing of this file is staged.
                None => false,
                Some(unstaged) => !unstaged.iter().any(|u| touches(&rows_of(u), &rows_of(&hunk))),
            };
            HunkState { hunk, staged }
        })
        .collect()
}

/// The index text after staging or unstaging the hunks touching `rows`
/// (rows of `work`), or `None` when there is nothing to change.
pub(crate) fn staged_index_text(
    head: &str,
    index: Option<&str>,
    work: &str,
    rows: Range<u32>,
    stage: bool,
) -> Option<String> {
    if stage {
        // An index without the file holds none of it: every row is unstaged,
        // and the diff against "" says exactly that.
        let index = index.unwrap_or("");
        let unstaged = hunks_between(index, work);
        let matching: Vec<(Range<u32>, Range<u32>)> = unstaged
            .iter()
            .filter(|u| touches(&rows_of(u), &rows))
            .map(|u| (old_rows_of(u), rows_of(u)))
            .collect();
        if matching.is_empty() {
            return None;
        }
        Some(splice_lines(index, work, &matching))
    } else {
        // Nothing in the index means nothing to unstage.
        let index = index?;
        let asked: Vec<Range<u32>> = hunks_between(head, work)
            .iter()
            .filter(|h| touches(&rows_of(h), &rows))
            .map(old_rows_of)
            .collect();
        // The staged diff runs HEAD → index: its old rows are HEAD's, its new
        // rows the index's — so a staged hunk is matched on the HEAD side,
        // where the asked hunk and it describe the same lines.
        let staged = hunks_between(head, index);
        let matching: Vec<(Range<u32>, Range<u32>)> = staged
            .iter()
            .filter(|s| asked.iter().any(|a| touches(&old_rows_of(s), a)))
            .map(|s| (rows_of(s), old_rows_of(s)))
            .collect();
        if matching.is_empty() {
            return None;
        }
        Some(splice_lines(index, head, &matching))
    }
}

/// `target` with each `(target rows, source rows)` pair replaced by the
/// source's rows. Line terminators travel with their lines; the one rule is
/// that a line is only allowed to lack its newline when nothing follows it.
fn splice_lines(target: &str, source: &str, replacements: &[(Range<u32>, Range<u32>)]) -> String {
    let target_lines: Vec<&str> = target.split_inclusive('\n').collect();
    let source_lines: Vec<&str> = source.split_inclusive('\n').collect();
    let mut ordered = replacements.to_vec();
    ordered.sort_by_key(|(target_rows, _)| target_rows.start);

    let mut pieces: Vec<&str> = Vec::with_capacity(target_lines.len() + 8);
    let mut at = 0usize;
    for (target_rows, source_rows) in &ordered {
        let start = (target_rows.start as usize).min(target_lines.len());
        let end = (target_rows.end as usize).clamp(start, target_lines.len());
        pieces.extend_from_slice(&target_lines[at.min(start)..start]);
        let source_start = (source_rows.start as usize).min(source_lines.len());
        let source_end = (source_rows.end as usize).clamp(source_start, source_lines.len());
        pieces.extend_from_slice(&source_lines[source_start..source_end]);
        at = end;
    }
    pieces.extend_from_slice(&target_lines[at.min(target_lines.len())..]);

    let mut out = String::with_capacity(target.len() + source.len());
    let last = pieces.len().saturating_sub(1);
    for (i, piece) in pieces.iter().enumerate() {
        out.push_str(piece);
        if i != last && !piece.ends_with('\n') {
            out.push('\n');
        }
    }
    out
}

/// Byte offset where row `row` starts in `text`; the text's length for the
/// row after its last.
fn offset_of_row(text: &str, row: u32) -> usize {
    let mut offset = 0usize;
    for (i, line) in text.split_inclusive('\n').enumerate() {
        if i as u32 == row {
            return offset;
        }
        offset += line.len();
    }
    text.len()
}

/// The edit that puts HEAD's rows back over every hunk touching `rows`:
/// `(start, end, replacement)` in bytes of `work`, or `None` with nothing to
/// restore. Consecutive hunks are one edit — the rows between them are the
/// same in both texts, so the union range on each side lines up.
pub(crate) fn restore_edit(work: &str, head: &str, rows: Range<u32>) -> Option<(usize, usize, String)> {
    let hunks = hunks_between(head, work);
    let matching: Vec<&Hunk> = hunks.iter().filter(|h| touches(&rows_of(h), &rows)).collect();
    let first = matching.first()?;
    let last = matching.last()?;
    let start = offset_of_row(work, first.start_row);
    let end = offset_of_row(work, last.end_row);
    let head_start = offset_of_row(head, first.old_start);
    let head_end = offset_of_row(head, last.old_start + last.old_rows);
    let mut replacement = String::new();
    // Rows put back at the very end of a file whose last line has no newline
    // need one in front of them, or they would join that line.
    if start == work.len() && !work.is_empty() && !work.ends_with('\n') {
        replacement.push('\n');
    }
    replacement.push_str(&head[head_start..head_end]);
    // And rows put back in the middle need one after them.
    if end < work.len() && !replacement.ends_with('\n') && !replacement.is_empty() {
        replacement.push('\n');
    }
    Some((start, end, replacement))
}

/// A unified patch turning `old` into `new` for one file, in the shape
/// `git apply` reads: `diff --git` header, `---`/`+++` with `/dev/null` for
/// a file the index does not have, then hunks with [`CONTEXT`] lines around
/// each change, and git's own `\ No newline at end of file` marker.
///
/// Lines are diffed *with* their terminators — `split_inclusive` rather than
/// `lines` — so that "the last line lost its newline" is a change the patch
/// carries rather than one it silently drops.
pub(crate) fn unified_patch(path: &str, old: Option<&str>, new: &str) -> String {
    let old_lines: Vec<&str> = old
        .map(|text| text.split_inclusive('\n').collect())
        .unwrap_or_default();
    let new_lines: Vec<&str> = new.split_inclusive('\n').collect();

    let mut patch = format!("diff --git a/{path} b/{path}\n");
    if old.is_none() {
        patch.push_str("new file mode 100644\n--- /dev/null\n");
    } else {
        patch.push_str(&format!("--- a/{path}\n"));
    }
    patch.push_str(&format!("+++ b/{path}\n"));

    let mut input = InternedInput::default();
    input.update_before(old_lines.iter().copied());
    input.update_after(new_lines.iter().copied());
    let diff = Diff::compute(Algorithm::Histogram, &input);

    // Group hunks whose context would overlap into one `@@` block, as git
    // does: two changes fewer than 2 × CONTEXT rows apart share a block.
    let mut groups: Vec<Vec<imara_diff::Hunk>> = Vec::new();
    for hunk in diff.hunks() {
        match groups.last_mut() {
            Some(group)
                if hunk.before.start as usize
                    <= group.last().map(|last| last.before.end as usize).unwrap_or(0)
                        + 2 * CONTEXT =>
            {
                group.push(hunk);
            }
            _ => groups.push(vec![hunk]),
        }
    }

    for group in groups {
        let first = &group[0];
        let last = &group[group.len() - 1];
        let old_from = (first.before.start as usize).saturating_sub(CONTEXT);
        let old_to = (last.before.end as usize + CONTEXT).min(old_lines.len());
        let new_from = (first.after.start as usize).saturating_sub(CONTEXT);
        let new_to = (last.after.end as usize + CONTEXT).min(new_lines.len());
        patch.push_str(&format!(
            "@@ -{} +{} @@\n",
            range_header(old_from, old_to),
            range_header(new_from, new_to),
        ));
        let mut old_at = old_from;
        let mut new_at = new_from;
        for hunk in &group {
            // Context up to the change.
            while old_at < hunk.before.start as usize {
                push_line(&mut patch, ' ', old_lines[old_at]);
                old_at += 1;
                new_at += 1;
            }
            while old_at < hunk.before.end as usize {
                push_line(&mut patch, '-', old_lines[old_at]);
                old_at += 1;
            }
            while new_at < hunk.after.end as usize {
                push_line(&mut patch, '+', new_lines[new_at]);
                new_at += 1;
            }
        }
        // Trailing context.
        while old_at < old_to {
            push_line(&mut patch, ' ', old_lines[old_at]);
            old_at += 1;
        }
    }
    patch
}

/// `start,count` as git writes it: 1-based, and `0,0` for an empty side.
fn range_header(from: usize, to: usize) -> String {
    let count = to.saturating_sub(from);
    let start = if count == 0 { from } else { from + 1 };
    format!("{start},{count}")
}

fn push_line(patch: &mut String, sign: char, line: &str) {
    patch.push(sign);
    patch.push_str(line);
    if !line.ends_with('\n') {
        patch.push_str("\n\\ No newline at end of file\n");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    fn ranges(states: &[HunkState]) -> Vec<(u32, u32, bool)> {
        states
            .iter()
            .map(|state| (state.hunk.start_row, state.hunk.end_row, state.staged))
            .collect()
    }

    #[test]
    fn a_hunk_is_staged_when_the_index_already_has_its_rows() {
        let head = "a\nb\nc\nd\ne\n";
        let work = "a\nB\nc\nd\nE\n";
        // Only the first change has been staged.
        let index = "a\nB\nc\nd\ne\n";
        assert_eq!(
            ranges(&hunk_states(head, Some(index), work)),
            vec![(1, 2, true), (4, 5, false)]
        );
        // No index entry at all: nothing is staged.
        assert_eq!(
            ranges(&hunk_states(head, None, work)),
            vec![(1, 2, false), (4, 5, false)]
        );
        // Everything staged.
        assert_eq!(
            ranges(&hunk_states(head, Some(work), work)),
            vec![(1, 2, true), (4, 5, true)]
        );
    }

    #[test]
    fn a_partly_staged_hunk_still_offers_stage() {
        let head = "a\nb\nc\n";
        let work = "a\nB\nC\n";
        let index = "a\nB\nc\n";
        // One HEAD hunk covers both rows; the index has only half of it.
        assert_eq!(ranges(&hunk_states(head, Some(index), work)), vec![(1, 3, false)]);
    }

    #[test]
    fn staging_one_hunk_leaves_the_others_out_of_the_index() {
        let head = "a\nb\nc\nd\ne\n";
        let work = "a\nB\nc\nd\nE\n";
        let staged = staged_index_text(head, Some(head), work, 4..5, true).unwrap();
        assert_eq!(staged, "a\nb\nc\nd\nE\n");
        // The other hunk next: the index now has both.
        let staged = staged_index_text(head, Some(&staged), work, 1..2, true).unwrap();
        assert_eq!(staged, work);
        // Already there: nothing to do.
        assert_eq!(staged_index_text(head, Some(&staged), work, 1..2, true), None);
    }

    #[test]
    fn unstaging_one_hunk_puts_heads_rows_back_in_the_index() {
        let head = "a\nb\nc\nd\ne\n";
        let work = "a\nB\nc\nd\nE\n";
        let index = work;
        let unstaged = staged_index_text(head, Some(index), work, 1..2, false).unwrap();
        assert_eq!(unstaged, "a\nb\nc\nd\nE\n");
        // Nothing staged for those rows any more.
        assert_eq!(staged_index_text(head, Some(&unstaged), work, 1..2, false), None);
        // And with no index entry there is nothing to unstage at all.
        assert_eq!(staged_index_text(head, None, work, 1..2, false), None);
    }

    #[test]
    fn a_deletion_hunk_is_matched_on_its_boundary() {
        let head = "a\nb\nc\n";
        let work = "a\nc\n";
        // The gutter's deletion sits at row 1 with no rows of its own.
        assert_eq!(ranges(&hunk_states(head, Some(head), work)), vec![(1, 1, false)]);
        let staged = staged_index_text(head, Some(head), work, 1..1, true).unwrap();
        assert_eq!(staged, work);
        let unstaged = staged_index_text(head, Some(&staged), work, 1..1, false).unwrap();
        assert_eq!(unstaged, head);
    }

    #[test]
    fn staging_a_file_the_index_does_not_have_stages_all_of_it() {
        let work = "x\ny\n";
        assert_eq!(staged_index_text("", None, work, 0..1, true).unwrap(), work);
    }

    #[test]
    fn splicing_keeps_a_newline_between_lines() {
        // Source rows without a trailing newline get one when followed.
        assert_eq!(splice_lines("a\nb\nc\n", "X", &[(1..2, 0..1)]), "a\nX\nc\n");
        // A target whose last line lacks one gets one before an insertion.
        assert_eq!(splice_lines("a\nb", "c\n", &[(2..2, 0..1)]), "a\nb\nc\n");
        // Two replacements, given out of order.
        assert_eq!(
            splice_lines("a\nb\nc\nd\n", "B\nD\n", &[(3..4, 1..2), (1..2, 0..1)]),
            "a\nB\nc\nD\n"
        );
    }

    #[test]
    fn restoring_a_hunk_is_an_edit_that_puts_heads_rows_back() {
        let head = "a\nb\nc\n";
        // A modification.
        assert_eq!(
            restore_edit("a\nB\nc\n", head, 1..2),
            Some((2, 4, "b\n".to_owned()))
        );
        // A deletion in the middle.
        assert_eq!(restore_edit("a\nc\n", head, 1..1), Some((2, 2, "b\n".to_owned())));
        // A deletion at the end of a file with no trailing newline.
        assert_eq!(
            restore_edit("a", head, 1..1),
            Some((1, 1, "\nb\nc\n".to_owned()))
        );
        // An addition: its rows go, nothing comes back.
        assert_eq!(restore_edit("a\nb\nx\nc\n", head, 2..3), Some((4, 6, String::new())));
        // Nothing there.
        assert_eq!(restore_edit(head, head, 0..1), None);
    }

    #[test]
    fn restoring_a_range_over_two_hunks_is_one_edit() {
        let head = "a\nb\nc\nd\ne\n";
        let work = "a\nB\nc\nD\ne\n";
        let (start, end, text) = restore_edit(work, head, 1..4).unwrap();
        assert_eq!(&work[start..end], "B\nc\nD\n");
        assert_eq!(text, "b\nc\nd\n");
    }

    #[test]
    fn the_patch_has_gits_shape() {
        let patch = unified_patch("src/a.rs", Some("a\nb\nc\nd\ne\nf\ng\n"), "a\nb\nc\nD\ne\nf\ng\n");
        assert_eq!(
            patch,
            "diff --git a/src/a.rs b/src/a.rs\n\
             --- a/src/a.rs\n\
             +++ b/src/a.rs\n\
             @@ -1,7 +1,7 @@\n a\n b\n c\n-d\n+D\n e\n f\n g\n"
        );
        // A file the index has never seen.
        assert_eq!(
            unified_patch("n", None, "x\n"),
            "diff --git a/n b/n\nnew file mode 100644\n--- /dev/null\n+++ b/n\n@@ -0,0 +1,1 @@\n+x\n"
        );
        // A missing final newline is said the way git says it.
        assert_eq!(
            unified_patch("n", Some("x\n"), "x"),
            "diff --git a/n b/n\n--- a/n\n+++ b/n\n@@ -1,1 +1,1 @@\n-x\n+x\n\\ No newline at end of file\n"
        );
    }

    #[test]
    fn far_apart_changes_are_separate_blocks_and_close_ones_share() {
        let old: String = (0..20).map(|i| format!("{i}\n")).collect();
        let far = old.replace("2\n", "two\n").replace("17\n", "seventeen\n");
        let patch = unified_patch("f", Some(&old), &far);
        assert_eq!(patch.matches("@@ ").count(), 2);
        let near = old.replace("2\n", "two\n").replace("6\n", "six\n");
        let patch = unified_patch("f", Some(&old), &near);
        assert_eq!(patch.matches("@@ ").count(), 1);
    }

    /// Run the host's git hermetically, as the other git modules' tests do.
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

    fn index_of(repo: &Path, path: &str) -> String {
        let out = host_git(repo, &["show", &format!(":{path}")]);
        assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
        String::from_utf8_lossy(&out.stdout).into_owned()
    }

    fn apply_cached(repo: &Path, patch: &str) {
        let file = patch_file_path(repo);
        std::fs::write(&file, patch).unwrap();
        let out = host_git(
            repo,
            &["apply", "--cached", "--whitespace=nowarn", "--", file.to_str().unwrap()],
        );
        let _ = std::fs::remove_file(&file);
        assert!(
            out.status.success(),
            "git apply refused the patch:\n{}\n{patch}",
            String::from_utf8_lossy(&out.stderr)
        );
    }

    /// The whole round trip against real git: stage one of two hunks, see
    /// the index hold only it, unstage it again, restore it in the worktree.
    #[test]
    fn real_git_applies_the_stage_unstage_and_restore_patches() {
        if Command::new("git").arg("--version").output().is_err() {
            eprintln!("skipping: no git on PATH");
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let repo = dir.path();
        let head = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        assert!(host_git(repo, &["init", "--quiet", "-b", "main"]).status.success());
        std::fs::write(repo.join("f.txt"), head).unwrap();
        assert!(host_git(repo, &["add", "f.txt"]).status.success());
        assert!(host_git(repo, &["commit", "--quiet", "-m", "first"]).status.success());

        // Two hunks far enough apart to be two hunks.
        let work = "a\nB\nc\nd\ne\nf\ng\nh\nI\nj\n";
        std::fs::write(repo.join("f.txt"), work).unwrap();
        let states = hunk_states(head, Some(head), work);
        assert_eq!(ranges(&states), vec![(1, 2, false), (8, 9, false)]);

        // Stage the second hunk only.
        let index = index_of(repo, "f.txt");
        let new_index = staged_index_text(head, Some(&index), work, 8..9, true).unwrap();
        apply_cached(repo, &unified_patch("f.txt", Some(&index), &new_index));
        assert_eq!(index_of(repo, "f.txt"), "a\nb\nc\nd\ne\nf\ng\nh\nI\nj\n");
        let states = hunk_states(head, Some(&index_of(repo, "f.txt")), work);
        assert_eq!(ranges(&states), vec![(1, 2, false), (8, 9, true)]);

        // Unstage it again: the index is back at HEAD.
        let index = index_of(repo, "f.txt");
        let new_index = staged_index_text(head, Some(&index), work, 8..9, false).unwrap();
        apply_cached(repo, &unified_patch("f.txt", Some(&index), &new_index));
        assert_eq!(index_of(repo, "f.txt"), head);

        // Restore the first hunk in the worktree.
        let (start, end, text) = restore_edit(work, head, 1..2).unwrap();
        let restored = format!("{}{}{}", &work[..start], text, &work[end..]);
        assert_eq!(restored, "a\nb\nc\nd\ne\nf\ng\nh\nI\nj\n");

        // A file the index has never seen: the /dev/null patch creates it.
        std::fs::write(repo.join("new.txt"), "n\n").unwrap();
        let new_index = staged_index_text("", None, "n\n", 0..1, true).unwrap();
        apply_cached(repo, &unified_patch("new.txt", None, &new_index));
        assert_eq!(index_of(repo, "new.txt"), "n\n");

        // And a lost final newline survives the trip through the patch
        // writer: the gutter shows no hunk for it — `hunks_between` diffs
        // lines, not terminators — so it is never staged on its own, but a
        // hunk staged beside it carries the marker, and git must take it.
        let work = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj";
        std::fs::write(repo.join("f.txt"), work).unwrap();
        let index = index_of(repo, "f.txt");
        assert_eq!(staged_index_text(head, Some(&index), work, 9..10, true), None);
        apply_cached(repo, &unified_patch("f.txt", Some(&index), work));
        assert_eq!(index_of(repo, "f.txt"), work);
    }

    #[test]
    fn hunk_calls_on_a_buffer_without_a_file_say_so() {
        let engine = crate::Engine::new();
        let id = engine.create_buffer("a\n");
        assert_eq!(
            engine.git_hunk_stage(id, 0..1, true),
            Err("That buffer has no file".to_owned())
        );
        assert_eq!(
            engine.git_hunk_restore(id, 0..1),
            Err("That buffer has no file".to_owned())
        );
    }
}
