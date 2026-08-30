//! Editable multibuffers — Zed's `crates/multi_buffer`, reduced to what an
//! Android editor pane can actually draw.
//!
//! A multibuffer is an ordered list of *excerpts*: a row range of a file, plus
//! [`CONTEXT_LINES`] rows of context above and below, exactly as Zed's
//! `build_excerpt_ranges` does (search/src/project_search.rs asks for two
//! context lines per match, and `crates/diagnostics` uses the same figure).
//! Excerpts of the same file that overlap — or merely touch — are folded into
//! one, so two matches three lines apart appear as one continuous stretch of
//! the file rather than twice with the same context printed either side.
//!
//! Zed composes the excerpts into a `MultiBufferSnapshot` that the editor
//! renders directly, with the per-file headers drawn as *blocks* above the
//! text. We have no block decorations in the renderer, so the composition is a
//! real engine buffer — the **mirror** — whose text is the header lines and
//! the excerpt lines interleaved. That buys the whole editor for free: the
//! mirror is an ordinary [`crate::BufferId`], so the Kotlin side renders it
//! with the ordinary editor pane and gets selections, syntax highlighting,
//! autoclose, find, IME and everything else without a second renderer.
//!
//! What makes it a multibuffer rather than a scratch copy is that edits are
//! *routed*: [`crate::Engine::edit`] on a mirror looks up which excerpt the
//! offset falls in, converts it to an offset in the underlying file buffer,
//! and edits **that** buffer through the normal path — so undo, `didChange`
//! and the dirty flag all happen per file, which is the whole point of Zed's
//! multibuffer (multi_buffer.rs `edit` → `buffer.update(cx, |buffer, cx|
//! buffer.edit(..))`). The same edit is then replayed into the mirror so the
//! two texts agree. An edit that touches a header row is refused, because a
//! header is not part of any file.
//!
//! Excerpt ranges are held as `text::Anchor`s, so they follow edits made
//! anywhere — inside the excerpt, above it, or in the file's own tab — and
//! grow or shrink for free. After every edit the neighbours are re-checked
//! for touching, and a merge recomposes the mirror.

use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, RwLock};

use rope::Point;
use sum_tree::Bias;
use text::{Anchor, ToPoint};

use crate::{BufferId, EngineError};

pub type MultiBufferId = u64;

/// Rows of context kept above and below a match. Zed's project search asks
/// for exactly two.
pub const CONTEXT_LINES: u32 = 2;

/// How many excerpts one multibuffer may hold. Zed has no cap; a phone does,
/// because the mirror is composed eagerly and a thousand-hit grep would
/// otherwise compose a megabyte of text before the tab opened.
const MAX_EXCERPTS: usize = 512;

/// Where an excerpt should go, before context and merging are applied.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExcerptSpec {
    /// Display path — project-relative, `/`-separated. What the header shows.
    pub path: String,
    /// Absolute path of the file to open.
    pub abs_path: PathBuf,
    /// First and last row of interest, 0-based and inclusive. Context is
    /// added around them here, not by the caller.
    pub start_row: u32,
    pub end_row: u32,
}

/// One excerpt as it currently stands.
struct Excerpt {
    buffer: BufferId,
    abs_path: PathBuf,
    path: String,
    /// The excerpt's span in the *file*, as anchors so it survives edits.
    /// `start` is biased left and `end` right, which is what makes the range
    /// grow rather than slide when text is typed at either edge.
    start: Anchor,
    end: Anchor,
    /// Row of this excerpt's header line in the mirror.
    header_row: u32,
    /// How many content rows follow that header in the mirror.
    rows: u32,
}

impl Excerpt {
    fn first_content_row(&self) -> u32 {
        self.header_row + 1
    }

    fn last_content_row(&self) -> u32 {
        self.header_row + self.rows
    }

    fn contains_content_row(&self, row: u32) -> bool {
        self.rows > 0 && row >= self.first_content_row() && row <= self.last_content_row()
    }
}

/// A resolved excerpt range in file coordinates — the working shape used
/// while composing, before anchors exist.
#[derive(Clone)]
struct Placed {
    buffer: BufferId,
    abs_path: PathBuf,
    path: String,
    first_row: u32,
    last_row: u32,
}

pub(crate) struct MultiBuffer {
    id: MultiBufferId,
    title: String,
    /// "search", "references" or "diagnostics" — what the tab is showing.
    /// Only ever handed back to the UI.
    kind: String,
    /// The composed buffer the editor renders.
    mirror: BufferId,
    excerpts: Vec<Excerpt>,
    /// Buffers this multibuffer opened itself, and must therefore offer to
    /// close again. A file that was already open when it was built is
    /// somebody else's.
    owned: Vec<BufferId>,
    /// The version each source buffer was at when the mirror was last
    /// composed; a mismatch means somebody edited that file elsewhere.
    source_versions: HashMap<BufferId, u64>,
    /// Which buffer each routed edit went to, newest last — undo pops from
    /// here, so Ctrl+Z in a multibuffer undoes in the file the last edit
    /// landed in, as Zed's multibuffer undo does.
    undo_stack: Vec<BufferId>,
    redo_stack: Vec<BufferId>,
    /// Bumped every time the mirror is recomposed wholesale, as against
    /// changed by a routed edit. The UI watches this to know when it has to
    /// re-measure everything rather than the rows one edit touched.
    rebuilds: u64,
}

/// One excerpt, for the UI.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct ExcerptInfo {
    pub path: String,
    pub abs_path: String,
    /// The engine buffer this excerpt reads from — the same id `open_file`
    /// hands the caller for the same path, so a tab can tell whether a
    /// multibuffer is still holding the buffer it was about to release.
    pub buffer: BufferId,
    /// Mirror row of the header line.
    pub header_row: u32,
    /// First and last mirror row carrying file text.
    pub first_row: u32,
    pub last_row: u32,
    /// The same span in the file, 0-based and inclusive.
    pub file_start_row: u32,
    pub file_end_row: u32,
    /// The file has unsaved edits.
    pub dirty: bool,
}

/// Everything the UI needs to draw a multibuffer tab.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct MultiBufferInfo {
    pub id: MultiBufferId,
    pub title: String,
    pub kind: String,
    /// The composed buffer to render.
    pub buffer: BufferId,
    /// The mirror's content version, bumped by every routed edit and rebuild.
    pub version: u64,
    /// How many times the composition has been rewritten wholesale. A caller
    /// whose copy of this is behind must re-measure the whole document; one
    /// that is current only saw its own edits, whose reach it already knows.
    pub rebuilds: u64,
    /// Files with unsaved edits, for the tab's dot.
    pub dirty_files: usize,
    pub excerpts: Vec<ExcerptInfo>,
}

/// Where a mirror row came from.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct MultiBufferLocation {
    pub path: String,
    pub abs_path: String,
    /// 0-based row in the file. For a header row, the excerpt's first row.
    pub row: u32,
    /// This row is a header, not file text.
    pub header: bool,
}

/// What a save-all did.
#[derive(Debug, Clone, Default, PartialEq, serde::Serialize)]
pub struct SaveAllReport {
    pub saved: Vec<String>,
    /// Paths that could not be written, each with the reason.
    pub failed: Vec<String>,
}

/// The engine's multibuffers, and the mirror-to-multibuffer index the edit
/// path consults.
#[derive(Default)]
pub(crate) struct MultiBuffers {
    buffers: Mutex<HashMap<MultiBufferId, Arc<Mutex<MultiBuffer>>>>,
    /// Mirror buffer id → multibuffer id. Read on the edit path, so it sits
    /// behind an `RwLock` gated by [`Self::count`] — with no multibuffer open
    /// an edit pays one relaxed load and nothing else.
    mirrors: RwLock<HashMap<BufferId, MultiBufferId>>,
    count: AtomicUsize,
    next_id: AtomicU64,
}

impl MultiBuffers {
    /// The multibuffer a mirror buffer belongs to, or None — which is the
    /// answer for every ordinary buffer, arrived at without taking a lock.
    fn for_mirror(&self, buffer: BufferId) -> Option<MultiBufferId> {
        if self.count.load(Ordering::Relaxed) == 0 {
            return None;
        }
        self.mirrors.read().unwrap().get(&buffer).copied()
    }

    fn find(&self, id: MultiBufferId) -> Option<Arc<Mutex<MultiBuffer>>> {
        self.buffers.lock().unwrap().get(&id).cloned()
    }
}

impl crate::Engine {
    /// Build a multibuffer over `specs`, opening any file that is not open
    /// already.
    ///
    /// **Blocking**: reads files off disk; call it off the Android main
    /// thread.
    pub fn create_multibuffer(
        &self,
        title: &str,
        kind: &str,
        specs: &[ExcerptSpec],
    ) -> Result<MultiBufferId, EngineError> {
        let mut placed: Vec<Placed> = Vec::with_capacity(specs.len());
        let mut owned: Vec<BufferId> = Vec::new();
        for spec in specs.iter().take(MAX_EXCERPTS) {
            let already_open = self.buffer_for_path(&spec.abs_path).is_some();
            // A file that has been deleted since the search found it is not an
            // error for the whole multibuffer; it simply contributes no
            // excerpt, which is how Zed treats an excerpt whose buffer is gone.
            let Ok(buffer) = self.open_file(&spec.abs_path) else {
                continue;
            };
            if !already_open && !owned.contains(&buffer) {
                owned.push(buffer);
            }
            let Ok(line_count) = self.line_count(buffer) else {
                continue;
            };
            let max_row = line_count.saturating_sub(1);
            let start = spec.start_row.min(max_row);
            let end = spec.end_row.max(spec.start_row).min(max_row);
            placed.push(Placed {
                buffer,
                abs_path: spec.abs_path.clone(),
                path: spec.path.clone(),
                first_row: start.saturating_sub(CONTEXT_LINES),
                last_row: (end + CONTEXT_LINES).min(max_row),
            });
        }
        if placed.is_empty() {
            return Err(EngineError::EmptyMultiBuffer);
        }

        let mirror = self.create_buffer("");
        let id = self.multibuffers.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let mut multibuffer = MultiBuffer {
            id,
            title: title.to_owned(),
            kind: kind.to_owned(),
            mirror,
            excerpts: Vec::new(),
            owned,
            source_versions: HashMap::new(),
            undo_stack: Vec::new(),
            redo_stack: Vec::new(),
            rebuilds: 0,
        };
        self.compose(&mut multibuffer, placed);

        self.multibuffers
            .buffers
            .lock()
            .unwrap()
            .insert(id, Arc::new(Mutex::new(multibuffer)));
        self.multibuffers.mirrors.write().unwrap().insert(mirror, id);
        self.multibuffers.count.fetch_add(1, Ordering::Relaxed);
        Ok(id)
    }

    /// The composed buffer behind a multibuffer, for the editor pane.
    pub fn multibuffer_mirror(&self, id: MultiBufferId) -> Option<BufferId> {
        let multibuffer = self.multibuffers.find(id)?;
        let mirror = multibuffer.lock().unwrap().mirror;
        Some(mirror)
    }

    /// Everything the UI draws its headers from.
    pub fn multibuffer_info(&self, id: MultiBufferId) -> Option<MultiBufferInfo> {
        let multibuffer = self.multibuffers.find(id)?;
        let multibuffer = multibuffer.lock().unwrap();
        Some(self.info(&multibuffer))
    }

    fn info(&self, multibuffer: &MultiBuffer) -> MultiBufferInfo {
        let mut dirty_buffers: HashSet<BufferId> = HashSet::new();
        let excerpts = multibuffer
            .excerpts
            .iter()
            .map(|excerpt| {
                let dirty = self.buffer_is_dirty(excerpt.buffer);
                if dirty {
                    dirty_buffers.insert(excerpt.buffer);
                }
                let (file_start_row, file_end_row) = self
                    .excerpt_file_rows(excerpt)
                    .unwrap_or((0, excerpt.rows.saturating_sub(1)));
                ExcerptInfo {
                    path: excerpt.path.clone(),
                    abs_path: excerpt.abs_path.display().to_string(),
                    buffer: excerpt.buffer,
                    header_row: excerpt.header_row,
                    first_row: excerpt.first_content_row(),
                    last_row: excerpt.last_content_row(),
                    file_start_row,
                    file_end_row,
                    dirty,
                }
            })
            .collect();
        MultiBufferInfo {
            id: multibuffer.id,
            title: multibuffer.title.clone(),
            kind: multibuffer.kind.clone(),
            buffer: multibuffer.mirror,
            version: self.version(multibuffer.mirror).unwrap_or(0),
            rebuilds: multibuffer.rebuilds,
            dirty_files: dirty_buffers.len(),
            excerpts,
        }
    }

    /// Which file, and which row of it, a mirror row shows.
    pub fn multibuffer_locate(&self, id: MultiBufferId, row: u32) -> Option<MultiBufferLocation> {
        let multibuffer = self.multibuffers.find(id)?;
        let multibuffer = multibuffer.lock().unwrap();
        let excerpt = multibuffer
            .excerpts
            .iter()
            .find(|excerpt| excerpt.header_row == row || excerpt.contains_content_row(row))?;
        let (first, _) = self.excerpt_file_rows(excerpt)?;
        let header = excerpt.header_row == row;
        Some(MultiBufferLocation {
            path: excerpt.path.clone(),
            abs_path: excerpt.abs_path.display().to_string(),
            row: if header {
                first
            } else {
                first + (row - excerpt.first_content_row())
            },
            header,
        })
    }

    /// Recompose the mirror if any file behind it moved — because it was
    /// edited in its own tab, reloaded from disk, or undone there. Returns the
    /// mirror's version, which the UI polls; unchanged when nothing moved.
    pub fn multibuffer_sync(&self, id: MultiBufferId) -> Option<u64> {
        let multibuffer = self.multibuffers.find(id)?;
        let mut multibuffer = multibuffer.lock().unwrap();
        let stale = multibuffer.source_versions.iter().any(|(buffer, version)| {
            self.version(*buffer)
                .map(|now| now != *version)
                .unwrap_or(true)
        });
        if stale {
            self.rebuild(&mut multibuffer);
        }
        self.version(multibuffer.mirror).ok()
    }

    /// Zed's `SaveAll` over the files a multibuffer touches
    /// (workspace/src/workspace.rs `save_all_internal`): every dirty buffer is
    /// written, and the ones that fail are named rather than swallowed.
    ///
    /// **Blocking**: writes files; call it off the Android main thread.
    pub fn multibuffer_save_all(&self, id: MultiBufferId) -> Option<SaveAllReport> {
        let multibuffer = self.multibuffers.find(id)?;
        // The paths come out under the lock and the writing happens without
        // it, so saving a large file does not stall the pane's polling.
        let files: Vec<(BufferId, String)> = {
            let multibuffer = multibuffer.lock().unwrap();
            let mut seen = HashSet::new();
            multibuffer
                .excerpts
                .iter()
                .filter(|excerpt| seen.insert(excerpt.buffer))
                .map(|excerpt| (excerpt.buffer, excerpt.path.clone()))
                .collect()
        };
        let mut report = SaveAllReport::default();
        for (buffer, path) in files {
            if !self.buffer_is_dirty(buffer) {
                continue;
            }
            match self.save_buffer(buffer) {
                Ok(_) => report.saved.push(path),
                Err(err) => report.failed.push(format!("{path}: {err}")),
            }
        }
        Some(report)
    }

    /// Close a multibuffer, releasing the mirror and the files it opened.
    ///
    /// `keep` names buffers the caller still holds — the tabs it has open —
    /// which is the one thing the engine cannot know. A file this multibuffer
    /// opened on demand and nobody else has since claimed is closed with it; a
    /// dirty one never is, because dropping it would throw the edits away.
    pub fn close_multibuffer(&self, id: MultiBufferId, keep: &[BufferId]) -> bool {
        let Some(multibuffer) = self.multibuffers.buffers.lock().unwrap().remove(&id) else {
            return false;
        };
        self.multibuffers.count.fetch_sub(1, Ordering::Relaxed);
        let (mirror, owned) = {
            let multibuffer = multibuffer.lock().unwrap();
            (multibuffer.mirror, multibuffer.owned.clone())
        };
        self.multibuffers.mirrors.write().unwrap().remove(&mirror);
        self.close_buffer(mirror);

        let still_held = self.buffers_held_by_multibuffers();
        for buffer in owned {
            if keep.contains(&buffer) || still_held.contains(&buffer) || self.buffer_is_dirty(buffer)
            {
                continue;
            }
            self.close_buffer(buffer);
        }
        true
    }

    /// Every source buffer some still-live multibuffer shows.
    fn buffers_held_by_multibuffers(&self) -> HashSet<BufferId> {
        let mut held = HashSet::new();
        for multibuffer in self.multibuffers.buffers.lock().unwrap().values() {
            let multibuffer = multibuffer.lock().unwrap();
            held.extend(multibuffer.excerpts.iter().map(|excerpt| excerpt.buffer));
        }
        held
    }

    // ---- the edit path ------------------------------------------------

    /// The multibuffer this buffer is the mirror of, if it is one.
    pub(crate) fn multibuffer_for_mirror(&self, buffer: BufferId) -> Option<MultiBufferId> {
        self.multibuffers.for_mirror(buffer)
    }

    /// An edit made in the mirror, routed to the file it belongs to.
    ///
    /// The edit runs against the *file* buffer first, through the ordinary
    /// [`crate::Engine::edit_buffer`] — that is what gives the file its undo
    /// entry, its `didChange` and its dirty flag — and is then replayed into
    /// the mirror so the two texts agree. An edit that reaches a header row,
    /// or spans two excerpts, is refused: neither has one file to go to.
    pub(crate) fn multibuffer_edit(
        &self,
        id: MultiBufferId,
        start: usize,
        end: usize,
        text: &str,
    ) -> Result<u64, EngineError> {
        let Some(multibuffer) = self.multibuffers.find(id) else {
            return Err(EngineError::UnknownMultiBuffer(id));
        };
        let mut multibuffer = multibuffer.lock().unwrap();
        let mirror = multibuffer.mirror;
        let (start_point, end_point) = {
            let state = self.buffer(mirror)?;
            let state = state.lock().unwrap();
            let snapshot = state.buffer.snapshot();
            if start > end || end > snapshot.len() {
                return Err(EngineError::InvalidRange { start, end });
            }
            (
                snapshot.offset_to_point(start),
                snapshot.offset_to_point(end),
            )
        };

        let index = multibuffer
            .excerpts
            .iter()
            .position(|excerpt| excerpt.contains_content_row(start_point.row))
            .ok_or(EngineError::NotInAnExcerpt)?;
        let (source, first_content_row, file_first) = {
            let excerpt = &multibuffer.excerpts[index];
            if !excerpt.contains_content_row(end_point.row) {
                return Err(EngineError::NotInAnExcerpt);
            }
            let (file_first, _) = self
                .excerpt_file_rows(excerpt)
                .ok_or(EngineError::NotInAnExcerpt)?;
            (excerpt.buffer, excerpt.first_content_row(), file_first)
        };
        let source_offset = |point: Point| -> Result<usize, EngineError> {
            let row = file_first + (point.row - first_content_row);
            self.point_to_offset(source, row, point.column)
        };
        let source_start = source_offset(start_point)?;
        let source_end = source_offset(end_point)?;

        // The file first: it is the edit that matters, and if it is refused —
        // a column that is not a character boundary, say — the mirror must not
        // be touched either.
        self.edit_buffer(source, source_start, source_end, text)?;
        let version = self.edit_buffer(mirror, start, end, text)?;
        multibuffer.undo_stack.push(source);
        multibuffer.redo_stack.clear();

        // The anchors have followed the edit; the mirror's row count for this
        // excerpt follows them, and everything below shifts by the same delta.
        if let Some((first, last)) = self.excerpt_file_rows(&multibuffer.excerpts[index]) {
            let rows = last - first + 1;
            let delta = rows as i64 - multibuffer.excerpts[index].rows as i64;
            multibuffer.excerpts[index].rows = rows;
            if delta != 0 {
                for excerpt in &mut multibuffer.excerpts[index + 1..] {
                    excerpt.header_row = excerpt.header_row.saturating_add_signed(delta as i32);
                }
            }
        }
        self.record_source_versions(&mut multibuffer);

        // No re-merge check here, deliberately. An edit routed from the mirror
        // is confined to one excerpt, and the text it inserts or removes moves
        // *both* that excerpt's end anchor and the next excerpt's start anchor
        // by the same amount — so a routed edit can never close the gap
        // between two excerpts of one file. What can is an edit made in the
        // file's own tab, and that arrives through [`Self::multibuffer_sync`],
        // which recomposes and merges there.
        Ok(version)
    }

    /// Ctrl+Z in a multibuffer undoes in the file the last edit went to.
    pub(crate) fn multibuffer_undo(&self, id: MultiBufferId) -> Result<Option<u64>, EngineError> {
        self.multibuffer_history(id, false)
    }

    pub(crate) fn multibuffer_redo(&self, id: MultiBufferId) -> Result<Option<u64>, EngineError> {
        self.multibuffer_history(id, true)
    }

    fn multibuffer_history(
        &self,
        id: MultiBufferId,
        redo: bool,
    ) -> Result<Option<u64>, EngineError> {
        let Some(multibuffer) = self.multibuffers.find(id) else {
            return Err(EngineError::UnknownMultiBuffer(id));
        };
        let mut multibuffer = multibuffer.lock().unwrap();
        let source = if redo {
            multibuffer.redo_stack.pop()
        } else {
            multibuffer.undo_stack.pop()
        };
        let Some(source) = source else {
            return Ok(None);
        };
        let moved = if redo {
            self.redo_buffer(source)?
        } else {
            self.undo_buffer(source)?
        };
        if moved.is_none() {
            return Ok(None);
        }
        if redo {
            multibuffer.undo_stack.push(source);
        } else {
            multibuffer.redo_stack.push(source);
        }
        // The file's rows have moved wholesale; recompose rather than guess.
        self.rebuild(&mut multibuffer);
        self.version(multibuffer.mirror).map(Some)
    }

    // ---- composition ---------------------------------------------------

    /// The excerpt's span in its file, read back from the anchors.
    fn excerpt_file_rows(&self, excerpt: &Excerpt) -> Option<(u32, u32)> {
        let state = self.buffer(excerpt.buffer).ok()?;
        let state = state.lock().unwrap();
        let snapshot = state.buffer.snapshot();
        let first = excerpt.start.to_point(&snapshot).row;
        let last = excerpt.end.to_point(&snapshot).row;
        Some((first, last.max(first)))
    }

    /// Recompose the mirror from where the anchors now say the excerpts are.
    fn rebuild(&self, multibuffer: &mut MultiBuffer) {
        let placed: Vec<Placed> = multibuffer
            .excerpts
            .iter()
            .filter_map(|excerpt| {
                let (first, last) = self.excerpt_file_rows(excerpt)?;
                Some(Placed {
                    buffer: excerpt.buffer,
                    abs_path: excerpt.abs_path.clone(),
                    path: excerpt.path.clone(),
                    first_row: first,
                    last_row: last,
                })
            })
            .collect();
        self.compose(multibuffer, placed);
    }

    /// Sort, merge, lay out and write the mirror.
    fn compose(&self, multibuffer: &mut MultiBuffer, mut placed: Vec<Placed>) {
        // File order, then row order — Zed's multibuffer lists a file's
        // excerpts together and in the order they appear in it.
        placed.sort_by(|a, b| {
            a.path
                .cmp(&b.path)
                .then_with(|| a.first_row.cmp(&b.first_row))
                .then_with(|| a.last_row.cmp(&b.last_row))
        });
        let mut merged: Vec<Placed> = Vec::with_capacity(placed.len());
        for excerpt in placed {
            match merged.last_mut() {
                // `<= last + 1` rather than `<= last`: two excerpts separated
                // by a single row would otherwise print a header and a
                // duplicate of that row's neighbours for nothing.
                Some(previous)
                    if previous.buffer == excerpt.buffer
                        && excerpt.first_row <= previous.last_row + 1 =>
                {
                    previous.last_row = previous.last_row.max(excerpt.last_row);
                }
                _ => merged.push(excerpt),
            }
        }

        let language = shared_language(&merged);
        let comment = language.and_then(line_comment);
        let comment = comment.as_deref();
        let mut text = String::new();
        let mut excerpts = Vec::with_capacity(merged.len());
        let mut row = 0u32;
        for placed in &merged {
            let Some((start, end)) = self.anchors_for(placed) else {
                continue;
            };
            let rows = placed.last_row - placed.first_row + 1;
            text.push_str(&header_line(
                &placed.path,
                placed.first_row,
                placed.last_row,
                comment,
            ));
            text.push('\n');
            text.push_str(
                &self
                    .lines(placed.buffer, placed.first_row, placed.last_row + 1)
                    .unwrap_or_default(),
            );
            text.push('\n');
            excerpts.push(Excerpt {
                buffer: placed.buffer,
                abs_path: placed.abs_path.clone(),
                path: placed.path.clone(),
                start,
                end,
                header_row: row,
                rows,
            });
            row += rows + 1;
        }

        let len = self.len(multibuffer.mirror).unwrap_or(0);
        let _ = self.edit_buffer(multibuffer.mirror, 0, len, &text);
        // The mirror's own history is never walked — undo is routed to the
        // files — but leaving a recomposition ungrouped keeps `text`'s
        // time-based grouping from splicing it into the last routed edit's
        // transaction, which would make that transaction unreplayable.
        if let Ok(state) = self.buffer(multibuffer.mirror) {
            state.lock().unwrap().buffer.finalize_last_transaction();
        }
        multibuffer.excerpts = excerpts;
        multibuffer.rebuilds += 1;
        self.record_source_versions(multibuffer);

        // A multibuffer over one language highlights like that language, and
        // its headers are written as comments in it so they read as
        // annotations rather than as broken code. Files of two languages get
        // no grammar at all, which is honest: there is no one right answer.
        if let Some(language) = language
            && self.buffer_language(multibuffer.mirror) != Some(language)
        {
            let _ = self.set_language(multibuffer.mirror, language);
        }
    }

    fn anchors_for(&self, placed: &Placed) -> Option<(Anchor, Anchor)> {
        let state = self.buffer(placed.buffer).ok()?;
        let state = state.lock().unwrap();
        let snapshot = state.buffer.snapshot();
        let start = snapshot.clip_point(Point::new(placed.first_row, 0), Bias::Left);
        let end = snapshot.clip_point(Point::new(placed.last_row, u32::MAX), Bias::Left);
        Some((snapshot.anchor_before(start), snapshot.anchor_after(end)))
    }

    fn record_source_versions(&self, multibuffer: &mut MultiBuffer) {
        multibuffer.source_versions.clear();
        for excerpt in &multibuffer.excerpts {
            let version = self.version(excerpt.buffer).unwrap_or(0);
            multibuffer.source_versions.insert(excerpt.buffer, version);
        }
    }
}

/// The grammar every excerpt shares, or None when they differ.
fn shared_language(placed: &[Placed]) -> Option<&'static str> {
    let mut shared: Option<&'static str> = None;
    for excerpt in placed {
        let language = crate::language_for_path(&excerpt.path)?;
        match shared {
            None => shared = Some(language),
            Some(previous) if previous == language => {}
            Some(_) => return None,
        }
    }
    shared
}

/// The first line-comment token of a grammar — `//` for Rust, `#` for Python
/// — from the same language config the autoclose path reads
/// (language_config.rs).
fn line_comment(language: &str) -> Option<String> {
    let config = grammars::load_config(language);
    config
        .line_comments
        .first()
        .map(|comment| comment.trim_end().to_owned())
}

/// `// src/main.rs:12-18`, or the same without a comment token when the
/// multibuffer spans languages. Zed draws this as a block above the excerpt;
/// ours is a row of the mirror, refused to edits, which is the one place this
/// implementation visibly differs from Zed's.
fn header_line(path: &str, first_row: u32, last_row: u32, comment: Option<&str>) -> String {
    let range = if first_row == last_row {
        format!("{}", first_row + 1)
    } else {
        format!("{}-{}", first_row + 1, last_row + 1)
    };
    match comment {
        Some(comment) => format!("{comment} {path}:{range}"),
        None => format!("{path}:{range}"),
    }
}

/// Parse the JSON the bridge hands over: an array of `{"path": .., "abs": ..,
/// "row": .., "endRow": ..}`. Rows are 0-based; `endRow` defaults to `row`,
/// and `abs` to `root/path`.
pub fn parse_specs(json: &str, root: Option<&Path>) -> Vec<ExcerptSpec> {
    let Ok(serde_json::Value::Array(items)) = serde_json::from_str::<serde_json::Value>(json)
    else {
        return Vec::new();
    };
    items
        .iter()
        .filter_map(|item| {
            let path = item.get("path")?.as_str()?.to_owned();
            let abs_path = match item.get("abs").and_then(|abs| abs.as_str()) {
                Some(abs) if !abs.is_empty() => PathBuf::from(abs),
                _ => root?.join(&path),
            };
            let start_row = item.get("row").and_then(|row| row.as_u64()).unwrap_or(0) as u32;
            let end_row = item
                .get("endRow")
                .and_then(|row| row.as_u64())
                .map(|row| row as u32)
                .unwrap_or(start_row);
            Some(ExcerptSpec {
                path,
                abs_path,
                start_row,
                end_row: end_row.max(start_row),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Engine;

    /// A project of two files, and the engine that will hold them.
    fn fixture() -> (tempfile::TempDir, Engine) {
        let dir = tempfile::tempdir().unwrap();
        let lines: String = (1..=30)
            .map(|n| format!("// line {n} needle\n"))
            .collect::<Vec<_>>()
            .concat();
        std::fs::write(dir.path().join("a.rs"), &lines).unwrap();
        std::fs::write(dir.path().join("b.rs"), &lines).unwrap();
        (dir, Engine::new())
    }

    fn spec(dir: &Path, name: &str, row: u32) -> ExcerptSpec {
        ExcerptSpec {
            path: name.to_owned(),
            abs_path: dir.join(name),
            start_row: row,
            end_row: row,
        }
    }

    fn mirror_text(engine: &Engine, id: MultiBufferId) -> String {
        engine.text(engine.multibuffer_mirror(id).unwrap()).unwrap()
    }

    /// Two hits four rows apart keep two excerpts with two rows of context
    /// each; two hits three rows apart share context and become one.
    #[test]
    fn excerpts_merge_when_their_context_touches() {
        let (dir, engine) = fixture();
        let far = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "a.rs", 5), spec(dir.path(), "a.rs", 12)],
            )
            .unwrap();
        let info = engine.multibuffer_info(far).unwrap();
        assert_eq!(info.excerpts.len(), 2);
        assert_eq!(
            (info.excerpts[0].file_start_row, info.excerpts[0].file_end_row),
            (3, 7)
        );
        assert_eq!(
            (info.excerpts[1].file_start_row, info.excerpts[1].file_end_row),
            (10, 14)
        );
        // Header, five rows, header, five rows.
        assert_eq!(info.excerpts[0].header_row, 0);
        assert_eq!(info.excerpts[1].header_row, 6);
        assert_eq!(info.excerpts[1].first_row, 7);

        // Rows 5 and 10 give 3..7 and 8..12 — one row apart, so one excerpt.
        let near = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "a.rs", 5), spec(dir.path(), "a.rs", 10)],
            )
            .unwrap();
        let info = engine.multibuffer_info(near).unwrap();
        assert_eq!(info.excerpts.len(), 1);
        assert_eq!(
            (info.excerpts[0].file_start_row, info.excerpts[0].file_end_row),
            (3, 12)
        );

        // Context is clipped to the file rather than running off it.
        let top = engine
            .create_multibuffer("Search: needle", "search", &[spec(dir.path(), "a.rs", 0)])
            .unwrap();
        let info = engine.multibuffer_info(top).unwrap();
        assert_eq!(
            (info.excerpts[0].file_start_row, info.excerpts[0].file_end_row),
            (0, 2)
        );
    }

    /// The composition: one header per excerpt, written as a comment in the
    /// shared language, and the file's rows beneath it.
    #[test]
    fn the_mirror_composes_headers_and_excerpts() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "b.rs", 6), spec(dir.path(), "a.rs", 6)],
            )
            .unwrap();
        let text = mirror_text(&engine, id);
        let rows: Vec<&str> = text.lines().collect();
        // Files in path order, whatever order they were asked for in.
        assert_eq!(rows[0], "// a.rs:5-9");
        assert_eq!(rows[1], "// line 5 needle");
        assert_eq!(rows[5], "// line 9 needle");
        assert_eq!(rows[6], "// b.rs:5-9");
        assert_eq!(rows[7], "// line 5 needle");

        // A display row maps back to the file it came from.
        let at = engine.multibuffer_locate(id, 3).unwrap();
        assert_eq!((at.path.as_str(), at.row, at.header), ("a.rs", 6, false));
        // A header row answers with the excerpt's first *file* row, so a tap
        // on it opens the file where the excerpt starts.
        let header = engine.multibuffer_locate(id, 6).unwrap();
        assert_eq!((header.path.as_str(), header.row, header.header), ("b.rs", 4, true));
        assert!(engine.multibuffer_locate(id, 999).is_none());
    }

    /// The point of the whole exercise: typing in the mirror edits the file.
    #[test]
    fn an_edit_goes_to_the_right_buffer_at_the_right_offset() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "a.rs", 6), spec(dir.path(), "b.rs", 20)],
            )
            .unwrap();
        let mirror = engine.multibuffer_mirror(id).unwrap();
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();
        let b = engine.buffer_for_path(&dir.path().join("b.rs")).unwrap();
        assert!(!engine.buffer_is_dirty(a));

        // Row 6 of the mirror is b.rs's header; row 9 is b.rs file row 20.
        let at = engine.multibuffer_locate(id, 9).unwrap();
        assert_eq!((at.path.as_str(), at.row), ("b.rs", 20));
        let offset = engine.point_to_offset(mirror, 9, 3).unwrap();
        engine.edit(mirror, offset, offset, "X").unwrap();

        // It landed in b.rs, at row 20 column 3 — and nowhere in a.rs.
        assert_eq!(engine.lines(b, 20, 21).unwrap(), "// Xline 21 needle");
        assert_eq!(engine.lines(a, 20, 21).unwrap(), "// line 21 needle");
        assert!(engine.buffer_is_dirty(b));
        assert!(!engine.buffer_is_dirty(a));
        // And the mirror shows it too.
        assert_eq!(engine.lines(mirror, 9, 10).unwrap(), "// Xline 21 needle");

        // Undo goes to the file the edit went to, not to the mirror.
        assert!(engine.undo(mirror).unwrap().is_some());
        assert_eq!(engine.lines(b, 20, 21).unwrap(), "// line 21 needle");
        assert_eq!(engine.lines(mirror, 9, 10).unwrap(), "// line 21 needle");
        // Still dirty: the engine measures that against the version last
        // written, not against the text, so an undo back to the saved state
        // is dirty here exactly as it is in an ordinary buffer.
        assert!(engine.buffer_is_dirty(b));
        // Nothing left on the stack.
        assert_eq!(engine.undo(mirror).unwrap(), None);
        // Redo replays it in the same file.
        assert!(engine.redo(mirror).unwrap().is_some());
        assert_eq!(engine.lines(b, 20, 21).unwrap(), "// Xline 21 needle");
    }

    /// A header belongs to no file, so an edit that reaches one is refused —
    /// and the file is left exactly as it was.
    #[test]
    fn edits_on_a_header_are_refused() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer("Search: needle", "search", &[spec(dir.path(), "a.rs", 6)])
            .unwrap();
        let mirror = engine.multibuffer_mirror(id).unwrap();
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();
        let before = engine.text(a).unwrap();

        assert_eq!(engine.edit(mirror, 0, 0, "x"), Err(EngineError::NotInAnExcerpt));
        // Backspace at the start of the first content row would swallow the
        // newline after the header; that spans a header row too.
        let start = engine.point_to_offset(mirror, 1, 0).unwrap();
        assert_eq!(
            engine.edit(mirror, start - 1, start, ""),
            Err(EngineError::NotInAnExcerpt)
        );
        assert_eq!(engine.text(a).unwrap(), before);
        assert!(!engine.buffer_is_dirty(a));
    }

    /// An edit above an excerpt — made in the file's own tab — moves its rows
    /// under it, and the anchors carry the excerpt along.
    #[test]
    fn excerpt_ranges_survive_edits_above_them() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer("Search: needle", "search", &[spec(dir.path(), "a.rs", 20)])
            .unwrap();
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();
        let info = engine.multibuffer_info(id).unwrap();
        assert_eq!(
            (info.excerpts[0].file_start_row, info.excerpts[0].file_end_row),
            (18, 22)
        );

        // Two lines inserted at the top of the file.
        engine.edit(a, 0, 0, "// one\n// two\n").unwrap();
        engine.multibuffer_sync(id).unwrap();
        let info = engine.multibuffer_info(id).unwrap();
        assert_eq!(
            (info.excerpts[0].file_start_row, info.excerpts[0].file_end_row),
            (20, 24)
        );
        // The same rows of the same file, now two rows further down.
        let text = mirror_text(&engine, id);
        assert_eq!(text.lines().next(), Some("// a.rs:21-25"));
        assert_eq!(text.lines().nth(1), Some("// line 19 needle"));
        let at = engine.multibuffer_locate(id, 3).unwrap();
        assert_eq!(at.row, 22);
    }

    /// Two excerpts whose gap disappears become one, rather than printing the
    /// rows they now share twice.
    ///
    /// The gap can only close from *outside* the multibuffer — a routed edit
    /// moves both excerpts' anchors together — so this is the file's own tab
    /// deleting the rows between them, which reaches the mirror through
    /// [`Engine::multibuffer_sync`].
    #[test]
    fn excerpts_re_merge_when_the_gap_between_them_goes() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "a.rs", 5), spec(dir.path(), "a.rs", 12)],
            )
            .unwrap();
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();
        // 3..7 and 10..14, with rows 8 and 9 between them.
        assert_eq!(engine.multibuffer_info(id).unwrap().excerpts.len(), 2);

        // Delete rows 8 and 9 in the file's own buffer.
        let start = engine.point_to_offset(a, 8, 0).unwrap();
        let end = engine.point_to_offset(a, 10, 0).unwrap();
        engine.edit(a, start, end, "").unwrap();
        engine.multibuffer_sync(id).unwrap();

        let info = engine.multibuffer_info(id).unwrap();
        assert_eq!(info.excerpts.len(), 1);
        assert_eq!(
            (info.excerpts[0].file_start_row, info.excerpts[0].file_end_row),
            (3, 12)
        );
        assert_eq!(mirror_text(&engine, id).lines().next(), Some("// a.rs:4-13"));
    }

    /// Ctrl+S over a multibuffer writes every file in it — Zed's SaveAll.
    #[test]
    fn save_all_writes_every_dirty_file() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "a.rs", 6), spec(dir.path(), "b.rs", 20)],
            )
            .unwrap();
        let mirror = engine.multibuffer_mirror(id).unwrap();
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();
        let b = engine.buffer_for_path(&dir.path().join("b.rs")).unwrap();

        for row in [3u32, 9] {
            let offset = engine.point_to_offset(mirror, row, 3).unwrap();
            engine.edit(mirror, offset, offset, "X").unwrap();
        }
        assert!(engine.buffer_is_dirty(a) && engine.buffer_is_dirty(b));
        assert_eq!(engine.multibuffer_info(id).unwrap().dirty_files, 2);

        let report = engine.multibuffer_save_all(id).unwrap();
        assert_eq!(report.saved, vec!["a.rs".to_owned(), "b.rs".to_owned()]);
        assert!(report.failed.is_empty());
        assert!(!engine.buffer_is_dirty(a) && !engine.buffer_is_dirty(b));
        assert_eq!(engine.multibuffer_info(id).unwrap().dirty_files, 0);
        // On disk, not merely in the buffer.
        assert!(
            std::fs::read_to_string(dir.path().join("a.rs"))
                .unwrap()
                .contains("// Xline 7 needle")
        );
        // A second save-all has nothing to do.
        assert!(engine.multibuffer_save_all(id).unwrap().saved.is_empty());
    }

    /// Closing releases the mirror and the files it opened, and leaves alone
    /// the ones somebody else holds.
    #[test]
    fn closing_releases_what_it_opened() {
        let (dir, engine) = fixture();
        // b.rs is open in a tab before the multibuffer exists.
        let b = engine.open_file(&dir.path().join("b.rs")).unwrap();
        let id = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "a.rs", 6), spec(dir.path(), "b.rs", 6)],
            )
            .unwrap();
        let mirror = engine.multibuffer_mirror(id).unwrap();
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();

        assert!(engine.close_multibuffer(id, &[]));
        assert_eq!(engine.text(mirror), Err(EngineError::UnknownBuffer(mirror)));
        assert_eq!(engine.text(a), Err(EngineError::UnknownBuffer(a)));
        // b.rs was not ours to close.
        assert!(engine.text(b).is_ok());
        assert!(!engine.close_multibuffer(id, &[]));
    }

    /// A file the caller has since opened a tab on stays open, and so does one
    /// with unsaved edits.
    #[test]
    fn closing_keeps_the_buffers_still_in_use() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer(
                "Search: needle",
                "search",
                &[spec(dir.path(), "a.rs", 6), spec(dir.path(), "b.rs", 6)],
            )
            .unwrap();
        let mirror = engine.multibuffer_mirror(id).unwrap();
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();
        let b = engine.buffer_for_path(&dir.path().join("b.rs")).unwrap();
        // b.rs is edited through the multibuffer and left dirty.
        let offset = engine.point_to_offset(mirror, 9, 3).unwrap();
        engine.edit(mirror, offset, offset, "X").unwrap();

        engine.close_multibuffer(id, &[a]);
        assert!(engine.text(a).is_ok(), "the caller asked to keep it");
        assert!(engine.text(b).is_ok(), "dirty buffers are never dropped");
    }

    /// Files of different languages get no grammar, and plain headers.
    #[test]
    fn mixed_languages_get_plain_headers() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("a.rs"), "one\ntwo\nthree\n").unwrap();
        std::fs::write(dir.path().join("b.py"), "one\ntwo\nthree\n").unwrap();
        let engine = Engine::new();
        let id = engine
            .create_multibuffer(
                "References to foo",
                "references",
                &[spec(dir.path(), "a.rs", 1), spec(dir.path(), "b.py", 1)],
            )
            .unwrap();
        let text = mirror_text(&engine, id);
        assert_eq!(text.lines().next(), Some("a.rs:1-4"));
        assert_eq!(text.lines().nth(5), Some("b.py:1-4"));
        assert_eq!(engine.buffer_language(engine.multibuffer_mirror(id).unwrap()), None);
    }

    /// The rebuild counter is what tells the UI "re-measure everything" from
    /// "you already know what your own edit moved".
    #[test]
    fn only_a_recomposition_bumps_the_rebuild_counter() {
        let (dir, engine) = fixture();
        let id = engine
            .create_multibuffer("Search: needle", "search", &[spec(dir.path(), "a.rs", 6)])
            .unwrap();
        let mirror = engine.multibuffer_mirror(id).unwrap();
        let rebuilds = engine.multibuffer_info(id).unwrap().rebuilds;
        assert_eq!(rebuilds, 1, "composing once counts once");

        // A routed edit moves the version, not the composition.
        let offset = engine.point_to_offset(mirror, 3, 3).unwrap();
        engine.edit(mirror, offset, offset, "X").unwrap();
        let after_edit = engine.multibuffer_info(id).unwrap();
        assert!(after_edit.version > 0);
        assert_eq!(after_edit.rebuilds, rebuilds);

        // A file moving in its own tab does recompose it.
        let a = engine.buffer_for_path(&dir.path().join("a.rs")).unwrap();
        engine.edit(a, 0, 0, "// added\n").unwrap();
        engine.multibuffer_sync(id).unwrap();
        assert_eq!(engine.multibuffer_info(id).unwrap().rebuilds, rebuilds + 1);
        // And a sync with nothing to do leaves it alone.
        engine.multibuffer_sync(id).unwrap();
        assert_eq!(engine.multibuffer_info(id).unwrap().rebuilds, rebuilds + 1);
    }

    #[test]
    fn a_multibuffer_over_nothing_is_an_error() {
        let dir = tempfile::tempdir().unwrap();
        let engine = Engine::new();
        assert_eq!(
            engine.create_multibuffer("Search: x", "search", &[spec(dir.path(), "gone.rs", 0)]),
            Err(EngineError::EmptyMultiBuffer)
        );
        assert!(engine.multibuffer_info(42).is_none());
        assert!(engine.multibuffer_mirror(42).is_none());
        assert!(engine.multibuffer_save_all(42).is_none());
    }

    #[test]
    fn specs_come_out_of_json() {
        let root = Path::new("/p");
        let specs = parse_specs(
            r#"[{"path":"src/main.rs","row":4},
                {"path":"x.rs","abs":"/elsewhere/x.rs","row":7,"endRow":9},
                {"nope":1}]"#,
            Some(root),
        );
        assert_eq!(
            specs,
            vec![
                ExcerptSpec {
                    path: "src/main.rs".to_owned(),
                    abs_path: PathBuf::from("/p/src/main.rs"),
                    start_row: 4,
                    end_row: 4,
                },
                ExcerptSpec {
                    path: "x.rs".to_owned(),
                    abs_path: PathBuf::from("/elsewhere/x.rs"),
                    start_row: 7,
                    end_row: 9,
                },
            ]
        );
        assert!(parse_specs("not json", Some(root)).is_empty());
        assert!(parse_specs(r#"[{"path":"a.rs"}]"#, None).is_empty());
    }
}
