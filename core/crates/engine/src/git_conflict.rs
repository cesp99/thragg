//! Merge-conflict regions in a buffer, and resolving one.
//!
//! Zed's model is `crates/project/src/git_store/conflict_set.rs`: a conflict
//! is not something git tells the editor about, it is *text* — the
//! `<<<<<<<`, `|||||||`, `=======` and `>>>>>>>` lines git leaves in a file
//! it could not merge — and the editor finds it by reading the buffer. So
//! this module reads the buffer, exactly as `ConflictSet::parse` reads the
//! snapshot there (conflict_set.rs:178-283), and hands back the regions in
//! byte offsets for the edit and in rows for the drawing.
//!
//! Resolving one is Zed's `ConflictRegion::resolve` (conflict_set.rs:103-131):
//! keep the chosen side or sides, delete the rest of the region, markers
//! included. Zed does it as several deletions so that its anchors survive;
//! we do it as **one replacement** of the whole region, which is the same
//! text afterwards and, being one `Engine::edit`, is one undo step by
//! construction rather than by bracketing.
//!
//! The rule for what is a marker is git's rather than Zed's where the two
//! differ. Zed matches `=======` with `starts_with`, so a line of eight
//! equals signs — a Markdown setext underline, a comment rule — would split a
//! conflict it happens to fall inside. git's own check (`is_conflict_marker`
//! in diff.c) wants exactly seven marker characters at the start of the line,
//! followed by a space or the end of the line; that is what git writes, and
//! it is what we read. Either way a marker is only ever at the *start* of a
//! line: `"<<<<<<< "` inside a string literal is text, as it is to git.

use std::ops::Range;

use serde::Serialize;

use crate::{BufferId, Engine, EngineError};

/// Beyond this a buffer is not conflict material: the scan is linear and
/// cheap, but the text is copied out of the rope to run it, and a file this
/// size is not one anybody is resolving by hand. The same cap the gutter's
/// diff uses.
const MAX_SCAN_BYTES: usize = 8 * 1024 * 1024;

/// One conflict, as git left it in the file.
///
/// Offsets are bytes into the buffer; rows are 0-based buffer rows and every
/// row range is half-open. `range` runs from the start of the `<<<<<<<` line
/// to just past the newline ending the `>>>>>>>` line; `ours`, `base` and
/// `theirs` are the lines between the markers, each ending just past its
/// last newline — so keeping a side keeps whole lines, and dropping one
/// leaves no blank line behind.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct ConflictRegion {
    /// The label after `<<<<<<<` — `HEAD`, most of the time. Zed's default
    /// when git wrote none (conflict_set.rs:258).
    pub ours_branch_name: String,
    /// The label after `>>>>>>>` — the branch being merged in. Zed's default
    /// when there is none is `Origin` (conflict_set.rs:261).
    pub theirs_branch_name: String,
    pub range: Range<usize>,
    pub ours: Range<usize>,
    pub theirs: Range<usize>,
    /// The common ancestor, present with `merge.conflictStyle = diff3` or
    /// `zdiff3`. Never kept by a resolution: it is what both sides changed
    /// *from*, and neither button offers it — Zed's offer none either.
    pub base: Option<Range<usize>>,
    /// Row of the `<<<<<<<` line.
    pub start_row: u32,
    /// One past the row of the `>>>>>>>` line.
    pub end_row: u32,
    pub ours_rows: Range<u32>,
    pub base_rows: Option<Range<u32>>,
    pub theirs_rows: Range<u32>,
}

impl ConflictRegion {
    /// The one edit that resolves this region keeping [`Keep`]'s sides:
    /// replace the whole region with the kept text, ours before theirs when
    /// both are kept, which is the order Zed's "Use Both" passes its ranges
    /// in (conflict_view.rs:373-376).
    pub fn resolution(&self, text: &str, keep: Keep) -> (Range<usize>, String) {
        let mut kept = String::new();
        if keep.ours {
            kept.push_str(&text[self.ours.clone()]);
        }
        if keep.theirs {
            kept.push_str(&text[self.theirs.clone()]);
        }
        (self.range.clone(), kept)
    }
}

/// Which side or sides a resolution keeps. Both false is a legal answer —
/// it deletes the conflict outright — but nothing offers it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Keep {
    pub ours: bool,
    pub theirs: bool,
}

/// Whether `line` (without its line ending) opens with `marker` the way git
/// writes one: the seven characters, then a space or nothing.
///
/// The line is what is *after* the marker, or `None` when it is not one.
fn marker<'a>(line: &'a str, marker: &str) -> Option<&'a str> {
    let rest = line.strip_prefix(marker)?;
    if rest.is_empty() {
        Some(rest)
    } else {
        rest.strip_prefix(' ')
    }
}

/// Zed's `ConflictSet::parse` (conflict_set.rs:178-283), over a string.
///
/// A `<<<<<<<` seen while a region is already open abandons the earlier
/// region and starts again, as Zed's does — the opening marker is the one
/// line git never writes twice in a region, so a second one means the first
/// region is not going to close. A `=======` or `>>>>>>>` with no region open
/// is ordinary text.
pub fn parse(text: &str) -> Vec<ConflictRegion> {
    let mut conflicts = Vec::new();
    if text.len() > MAX_SCAN_BYTES {
        return conflicts;
    }

    // Offsets and rows of the region under construction. `None` until the
    // marker that sets each has been seen; every one of them is reset when a
    // region closes or is abandoned.
    let mut conflict_start: Option<(usize, u32)> = None;
    let mut ours_start: Option<(usize, u32)> = None;
    let mut ours_end: Option<(usize, u32)> = None;
    let mut ours_branch_name: Option<String> = None;
    let mut base_start: Option<(usize, u32)> = None;
    let mut base_end: Option<(usize, u32)> = None;
    let mut theirs_start: Option<(usize, u32)> = None;

    let mut line_pos = 0usize;
    for (row, line) in text.split_inclusive('\n').enumerate() {
        let row = row as u32;
        // Where the next line starts — past this one's newline, when it
        // has one. The last line of a file with no trailing newline ends
        // at the text's end, which is also where the region then ends.
        let next_pos = line_pos + line.len();
        let content = line.strip_suffix('\n').unwrap_or(line);
        let content = content.strip_suffix('\r').unwrap_or(content);

        if let Some(label) = marker(content, "<<<<<<<") {
            conflict_start = Some((line_pos, row));
            ours_start = Some((next_pos, row + 1));
            ours_end = None;
            base_start = None;
            base_end = None;
            theirs_start = None;
            let label = label.trim();
            ours_branch_name = (!label.is_empty()).then(|| label.to_owned());
        } else if marker(content, "|||||||").is_some()
            && conflict_start.is_some()
            && ours_start.is_some()
            && ours_end.is_none()
        {
            ours_end = Some((line_pos, row));
            base_start = Some((next_pos, row + 1));
        } else if marker(content, "=======").is_some()
            && conflict_start.is_some()
            && ours_start.is_some()
            && theirs_start.is_none()
        {
            // Ours ends here unless a base marker already ended it, in which
            // case this is where the base ends (conflict_set.rs:212-220).
            if ours_end.is_none() {
                ours_end = Some((line_pos, row));
            } else if base_start.is_some() {
                base_end = Some((line_pos, row));
            }
            theirs_start = Some((next_pos, row + 1));
        } else if let Some(label) = marker(content, ">>>>>>>")
            && let (Some(start), Some(ours_from), Some(ours_to), Some(theirs_from)) =
                (conflict_start, ours_start, ours_end, theirs_start)
        {
            let label = label.trim();
            let theirs_branch_name = (!label.is_empty()).then(|| label.to_owned());
            let theirs_end = (line_pos, row);
            let conflict_end = next_pos.min(text.len());

            conflicts.push(ConflictRegion {
                ours_branch_name: ours_branch_name.take().unwrap_or_else(|| "HEAD".to_owned()),
                theirs_branch_name: theirs_branch_name.unwrap_or_else(|| "Origin".to_owned()),
                range: start.0..conflict_end,
                ours: ours_from.0..ours_to.0,
                theirs: theirs_from.0..theirs_end.0,
                base: base_start.zip(base_end).map(|(from, to)| from.0..to.0),
                start_row: start.1,
                end_row: row + 1,
                ours_rows: ours_from.1..ours_to.1,
                base_rows: base_start.zip(base_end).map(|(from, to)| from.1..to.1),
                theirs_rows: theirs_from.1..theirs_end.1,
            });

            conflict_start = None;
            ours_start = None;
            ours_end = None;
            base_start = None;
            base_end = None;
            theirs_start = None;
        }

        line_pos = next_pos;
    }

    conflicts
}

impl Engine {
    /// Every conflict region in the buffer, in order. Reads the whole text
    /// under the buffer's lock and scans it once — linear, and only worth
    /// asking again when the buffer's version has moved.
    pub fn conflicts(&self, id: BufferId) -> Result<Vec<ConflictRegion>, EngineError> {
        let text = self.text(id)?;
        Ok(parse(&text))
    }

    /// Resolve the conflict whose `<<<<<<<` line is `start_row`, keeping the
    /// sides in `keep`. One edit, one undo step. Returns the new version.
    ///
    /// The region is found again in the buffer as it is *now*, not trusted
    /// from whatever the caller drew: the UI's copy of the regions is a
    /// poll behind the text, and an edit against stale offsets would delete
    /// the wrong lines. A start row that no longer opens a conflict is an
    /// [`EngineError::InvalidRange`], and nothing changes.
    pub fn resolve_conflict(
        &self,
        id: BufferId,
        start_row: u32,
        keep: Keep,
    ) -> Result<u64, EngineError> {
        let text = self.text(id)?;
        let region = parse(&text)
            .into_iter()
            .find(|region| region.start_row == start_row)
            .ok_or(EngineError::InvalidRange {
                start: start_row as usize,
                end: start_row as usize,
            })?;
        let (range, replacement) = region.resolution(&text, keep);
        // Bracketed like a formatter's replacement (format.rs): a
        // resolution is a discrete event, and must not merge into the
        // typing before or after it.
        self.finalize_history(id);
        let version = self.edit(id, range.start, range.end, &replacement)?;
        self.finalize_history(id);
        Ok(version)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const TWO_WAY: &str = "\
fn main() {
<<<<<<< HEAD
    println!(\"ours\");
=======
    println!(\"theirs\");
>>>>>>> feature
}
";

    const DIFF3: &str = "\
<<<<<<< HEAD
ours
||||||| merged common ancestors
base
=======
theirs
>>>>>>> feature
tail
";

    fn rows(region: &ConflictRegion) -> (u32, u32, Range<u32>, Option<Range<u32>>, Range<u32>) {
        (
            region.start_row,
            region.end_row,
            region.ours_rows.clone(),
            region.base_rows.clone(),
            region.theirs_rows.clone(),
        )
    }

    #[test]
    fn a_two_way_conflict_is_parsed_with_its_labels() {
        let regions = parse(TWO_WAY);
        assert_eq!(regions.len(), 1);
        let region = &regions[0];
        assert_eq!(region.ours_branch_name, "HEAD");
        assert_eq!(region.theirs_branch_name, "feature");
        assert_eq!(rows(region), (1, 6, 2..3, None, 4..5));
        assert_eq!(&TWO_WAY[region.ours.clone()], "    println!(\"ours\");\n");
        assert_eq!(&TWO_WAY[region.theirs.clone()], "    println!(\"theirs\");\n");
        assert_eq!(
            &TWO_WAY[region.range.clone()],
            "<<<<<<< HEAD\n    println!(\"ours\");\n=======\n    println!(\"theirs\");\n>>>>>>> feature\n"
        );
        assert!(region.base.is_none());
    }

    #[test]
    fn a_diff3_conflict_carries_its_base() {
        let regions = parse(DIFF3);
        assert_eq!(regions.len(), 1);
        let region = &regions[0];
        assert_eq!(rows(region), (0, 7, 1..2, Some(3..4), 5..6));
        assert_eq!(&DIFF3[region.ours.clone()], "ours\n");
        assert_eq!(&DIFF3[region.base.clone().unwrap()], "base\n");
        assert_eq!(&DIFF3[region.theirs.clone()], "theirs\n");
    }

    #[test]
    fn markers_are_only_markers_at_the_start_of_a_line() {
        // Every marker string appears inside code here; none opens a region.
        let text = "let s = \"<<<<<<< HEAD\";\n  =======\nlet t = \">>>>>>> x\";\n";
        assert!(parse(text).is_empty());

        // And a real conflict wrapped around such lines still closes on the
        // real markers, not on the ones inside strings.
        let text = "<<<<<<< HEAD\nlet s = \"=======\";\n=======\nlet t = \">>>>>>> x\";\n>>>>>>> b\n";
        let regions = parse(text);
        assert_eq!(regions.len(), 1);
        assert_eq!(&text[regions[0].ours.clone()], "let s = \"=======\";\n");
        assert_eq!(&text[regions[0].theirs.clone()], "let t = \">>>>>>> x\";\n");
    }

    #[test]
    fn a_marker_is_exactly_seven_characters_then_a_space_or_the_end() {
        // Eight equals signs — a setext underline — inside a conflict is text.
        let text = "<<<<<<< HEAD\nTitle\n========\n=======\nother\n>>>>>>> b\n";
        let regions = parse(text);
        assert_eq!(regions.len(), 1);
        assert_eq!(&text[regions[0].ours.clone()], "Title\n========\n");
        // Six is not a marker either.
        assert!(parse("<<<<<< HEAD\na\n======\nb\n>>>>>> c\n").is_empty());
        // `<<<<<<<HEAD` with no space is what git never writes, and not one.
        assert!(parse("<<<<<<<HEAD\na\n=======\nb\n>>>>>>>c\n").is_empty());
    }

    #[test]
    fn labels_default_to_zeds_when_git_wrote_none() {
        let text = "<<<<<<<\na\n=======\nb\n>>>>>>>\n";
        let regions = parse(text);
        assert_eq!(regions.len(), 1);
        assert_eq!(regions[0].ours_branch_name, "HEAD");
        assert_eq!(regions[0].theirs_branch_name, "Origin");
    }

    #[test]
    fn stray_closing_markers_and_an_unfinished_region_are_ignored() {
        assert!(parse("=======\nb\n>>>>>>> x\n").is_empty());
        assert!(parse("<<<<<<< HEAD\na\n=======\nb\n").is_empty());
        // A second opener abandons the first region.
        let text = "<<<<<<< HEAD\na\n<<<<<<< HEAD\nb\n=======\nc\n>>>>>>> x\n";
        let regions = parse(text);
        assert_eq!(regions.len(), 1);
        assert_eq!(regions[0].start_row, 2);
        assert_eq!(&text[regions[0].ours.clone()], "b\n");
    }

    #[test]
    fn several_regions_come_back_in_order() {
        let text = format!("{TWO_WAY}\n{TWO_WAY}");
        let regions = parse(&text);
        assert_eq!(regions.len(), 2);
        assert!(regions[0].range.end <= regions[1].range.start);
        assert_eq!(regions[0].start_row, 1);
        assert_eq!(regions[1].start_row, 9);
    }

    #[test]
    fn crlf_endings_keep_the_offsets_honest() {
        let text = "<<<<<<< HEAD\r\nours\r\n=======\r\ntheirs\r\n>>>>>>> b\r\nafter\r\n";
        let regions = parse(text);
        assert_eq!(regions.len(), 1);
        assert_eq!(&text[regions[0].ours.clone()], "ours\r\n");
        assert_eq!(&text[regions[0].theirs.clone()], "theirs\r\n");
        assert_eq!(&text[regions[0].range.end..], "after\r\n");
    }

    #[test]
    fn a_region_closing_on_the_last_line_without_a_newline_ends_at_the_text() {
        let text = "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> b";
        let regions = parse(text);
        assert_eq!(regions.len(), 1);
        assert_eq!(regions[0].range.end, text.len());
        assert_eq!(&text[regions[0].theirs.clone()], "theirs\n");
    }

    #[test]
    fn resolution_keeps_the_chosen_sides_and_drops_the_markers() {
        let region = parse(TWO_WAY).remove(0);
        let ours = Keep { ours: true, theirs: false };
        let theirs = Keep { ours: false, theirs: true };
        let both = Keep { ours: true, theirs: true };
        assert_eq!(region.resolution(TWO_WAY, ours).1, "    println!(\"ours\");\n");
        assert_eq!(region.resolution(TWO_WAY, theirs).1, "    println!(\"theirs\");\n");
        assert_eq!(
            region.resolution(TWO_WAY, both).1,
            "    println!(\"ours\");\n    println!(\"theirs\");\n"
        );
        // The base is never kept, whichever side is.
        let region = parse(DIFF3).remove(0);
        assert_eq!(region.resolution(DIFF3, both).1, "ours\ntheirs\n");
    }

    #[test]
    fn resolving_in_a_buffer_is_one_undo_step() {
        let engine = Engine::new();
        let id = engine.create_buffer(TWO_WAY);
        let version = engine
            .resolve_conflict(id, 1, Keep { ours: true, theirs: true })
            .unwrap();
        assert_eq!(version, 1);
        assert_eq!(
            engine.text(id).unwrap(),
            "fn main() {\n    println!(\"ours\");\n    println!(\"theirs\");\n}\n"
        );
        assert!(engine.conflicts(id).unwrap().is_empty());
        // One undo brings the whole region back, markers and all.
        assert!(engine.undo(id).unwrap().is_some());
        assert_eq!(engine.text(id).unwrap(), TWO_WAY);
        assert_eq!(engine.conflicts(id).unwrap().len(), 1);
    }

    #[test]
    fn resolving_a_row_that_is_not_a_conflict_changes_nothing() {
        let engine = Engine::new();
        let id = engine.create_buffer(TWO_WAY);
        let result = engine.resolve_conflict(id, 0, Keep { ours: true, theirs: false });
        assert!(matches!(result, Err(EngineError::InvalidRange { .. })));
        assert_eq!(engine.text(id).unwrap(), TWO_WAY);
        assert!(engine.undo(id).unwrap().is_none());
    }

    #[test]
    fn resolving_one_of_two_leaves_the_other_where_it_now_is() {
        let engine = Engine::new();
        let text = format!("{TWO_WAY}\n{TWO_WAY}");
        let id = engine.create_buffer(&text);
        engine
            .resolve_conflict(id, 1, Keep { ours: true, theirs: false })
            .unwrap();
        let remaining = engine.conflicts(id).unwrap();
        assert_eq!(remaining.len(), 1);
        // Five lines became one, so the second region moved up by four.
        assert_eq!(remaining[0].start_row, 5);
    }
}
