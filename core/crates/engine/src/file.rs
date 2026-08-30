//! File-backed buffers: open, save, dirty state, and disk conflicts.
//!
//! A buffer is "dirty" when its content version has moved past the version
//! last written to (or read from) disk. That is a comparison of two integers,
//! not a hash of the text, so it is exact and free.
//!
//! Disk changes are *detected*, never silently resolved. The worktree's
//! existing file watcher (see `project.rs`) flags an open buffer whose file
//! moved underneath it; deciding what to do — reload, or keep local edits —
//! belongs to the UI, which is the only layer that can ask the user. That
//! also keeps blocking reads off the runtime thread: flagging costs one
//! `stat`, and [`Engine::reload_buffer`] does the reading from whatever
//! thread called it.
//!
//! A file's *shape* — encoding, byte-order mark, line ending — is recorded
//! here on load and put back on save (see `encoding.rs`), so the buffer
//! itself can stay plain UTF-8 with `\n` breaks, which is what Zed's buffers
//! hold too (`language/src/buffer.rs`, `text::LineEnding::normalize`).

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::SystemTime;

use encoding_rs::Encoding;

use crate::encoding::{self, DecodedText, LineEnding};
use crate::{BufferId, EngineError};

/// What the engine remembers about the file behind a buffer.
pub(crate) struct FileState {
    pub path: PathBuf,
    /// Buffer version as of the last successful load or save.
    pub saved_version: u64,
    /// Modification time and length as of that same moment. Together they
    /// distinguish "someone else wrote this file" from "we wrote it", which
    /// matters because our own save fires the watcher too.
    pub disk_mtime: Option<SystemTime>,
    pub disk_len: u64,
    /// The file changed on disk since we last loaded or saved it.
    pub external_change: bool,
    /// The file is no longer on disk.
    pub deleted: bool,
    /// The line break the file uses, detected on load from its first one
    /// (`text::LineEnding::detect`) and written back on save. The buffer's
    /// own text is always `\n`-separated.
    pub line_ending: LineEnding,
    /// The encoding the file was read in, and will be written in.
    pub encoding: &'static Encoding,
    /// Whether the file opened with a byte-order mark, which the save puts
    /// back — Zed's `Buffer::has_bom`.
    pub has_bom: bool,
    /// The shape changed (a new line ending or encoding was chosen) since the
    /// last save. The text may be untouched, but the bytes on disk no longer
    /// say what a save would write, and that is what dirty means.
    pub shape_changed: bool,
    /// The reloads that changed the encoding, by the transaction each one
    /// landed as, with what is on the *other* side of that transaction:
    /// the encoding (and byte-order mark) it replaced, and whether the
    /// buffer was dirty there. Undoing such a transaction puts the text
    /// back to what the old encoding decoded, so the old encoding goes back
    /// with it — otherwise a save after the undo would write mojibake in
    /// the new alphabet — and so does the dirty state, since the text on
    /// that side is only the file on disk if it was clean the first time.
    /// Zed's `reload_with_encoding_txns` (`language/src/buffer.rs:145`).
    reload_encodings: HashMap<text::TransactionId, ReloadSide>,
}

/// One side of an encoding reload, for crossing back to it (see
/// `FileState::reload_encodings`).
#[derive(Clone, Copy)]
struct ReloadSide {
    encoding: &'static Encoding,
    has_bom: bool,
    /// Whether the buffer had unsaved changes on that side. The reload's
    /// own side never does, by construction; the side before it did if the
    /// user had typed before choosing to reopen.
    dirty: bool,
}

impl FileState {
    pub fn new(path: PathBuf) -> Self {
        FileState {
            path,
            saved_version: 0,
            disk_mtime: None,
            disk_len: 0,
            external_change: false,
            deleted: false,
            line_ending: LineEnding::default(),
            encoding: encoding_rs::UTF_8,
            has_bom: false,
            shape_changed: false,
            reload_encodings: HashMap::new(),
        }
    }

    /// Adopt what a load found: the line ending detected before the text was
    /// normalised, and the encoding it decoded with.
    fn adopt(&mut self, line_ending: LineEnding, decoded: &DecodedText) {
        self.line_ending = line_ending;
        self.encoding = decoded.encoding;
        self.has_bom = decoded.has_bom;
        self.shape_changed = false;
    }

    /// Remember that `transaction` is the reload that swapped `previous`
    /// for the encoding now in use, so an undo can swap back. A reload in
    /// the same encoding — a plain reload from disk — is not noted: there
    /// is nothing to swap, and the undo of it is an ordinary edit.
    fn note_reload_encoding(
        &mut self,
        transaction: Option<text::TransactionId>,
        previous: ReloadSide,
    ) {
        let Some(transaction) = transaction else {
            return;
        };
        if (previous.encoding, previous.has_bom) != (self.encoding, self.has_bom) {
            self.reload_encodings.insert(transaction, previous);
        }
    }

    /// Undo or redo just crossed `transaction`, and `version` is the
    /// buffer's version now. If it was a reload that changed the encoding,
    /// the encoding crosses back with it: the text is now what the *other*
    /// encoding decoded these bytes to, and it is that encoding a save
    /// must write. The dirty state crosses too — Zed keeps a clean buffer
    /// clean across the step (`restore_encoding_for_transaction`,
    /// `language/src/buffer.rs:3366-3382`), since both sides of a reload
    /// are the file on disk read two ways; here the side is also
    /// remembered as dirty when it *was*, so undoing a reload that threw
    /// unsaved edits away does not present those edits as saved.
    pub(crate) fn restore_encoding_for_transaction(
        &mut self,
        transaction: text::TransactionId,
        was_dirty: bool,
        version: u64,
    ) {
        let Some(&other) = self.reload_encodings.get(&transaction) else {
            return;
        };
        let this_side = ReloadSide {
            encoding: self.encoding,
            has_bom: self.has_bom,
            dirty: was_dirty,
        };
        self.encoding = other.encoding;
        self.has_bom = other.has_bom;
        if !other.dirty {
            self.saved_version = version;
            self.shape_changed = false;
        }
        self.reload_encodings.insert(transaction, this_side);
    }

    /// Record the file's current identity as the one we are in sync with.
    pub fn mark_synced(&mut self, version: u64) {
        let (mtime, len) = stat(&self.path);
        self.saved_version = version;
        self.disk_mtime = mtime;
        self.disk_len = len;
        self.external_change = false;
        self.deleted = mtime.is_none();
        self.shape_changed = false;
    }

    /// Compare the file on disk against what we last synced with, and record
    /// the verdict. Returns true if it differs — i.e. somebody else wrote it.
    pub fn note_disk_change(&mut self) -> bool {
        let (mtime, len) = stat(&self.path);
        if mtime.is_none() {
            // Absent files are reported as deleted rather than changed:
            // there is nothing to reload, so the UI shouldn't offer to.
            self.deleted = true;
            return false;
        }
        self.deleted = false;
        if mtime == self.disk_mtime && len == self.disk_len {
            return false;
        }
        self.external_change = true;
        true
    }
}

fn stat(path: &Path) -> (Option<SystemTime>, u64) {
    match std::fs::metadata(path) {
        Ok(metadata) => (metadata.modified().ok(), metadata.len()),
        Err(_) => (None, 0),
    }
}

pub(crate) fn io_error(path: &Path, err: std::io::Error) -> EngineError {
    EngineError::Io {
        path: path.display().to_string(),
        message: err.to_string(),
    }
}

impl crate::Engine {
    /// Read a file into a buffer, choosing the language from its name.
    /// Opening the same path twice returns the same buffer — tabs and the
    /// project panel must not be able to fork a file into two histories.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn open_file(&self, path: &Path) -> Result<BufferId, EngineError> {
        // Canonicalize so `a/../b.rs` and `b.rs` are recognised as one file.
        // A path that doesn't exist yet can't be canonicalized, and also
        // can't be read, so failing here loses nothing.
        let path = std::fs::canonicalize(path).map_err(|err| io_error(path, err))?;
        if let Some(id) = self.buffer_for_path(&path) {
            return Ok(id);
        }

        let (decoded, line_ending) = read_file(&path, None)?;
        let id = self.create_buffer(&decoded.text);
        // Settings-aware, so the user's `file_types` decides — this is the one
        // moment a buffer's language is chosen, and it already costs a read of
        // the file itself.
        if let Some(language) = self.language_for_path(&path.to_string_lossy()) {
            let _ = self.set_language(id, language);
        }

        let mut file = FileState::new(path);
        file.adopt(line_ending, &decoded);
        file.mark_synced(0);
        if let Ok(state) = self.buffer(id) {
            state.lock().unwrap().file = Some(file);
        }
        // The buffer now has both a path and a language, which is everything a
        // language server needs to be started for it — lazily, off this thread,
        // and silently when there is nothing to start (see lsp.rs).
        self.lsp_did_open(id);
        Ok(id)
    }

    /// The buffer already holding `path`, if any.
    pub fn buffer_for_path(&self, path: &Path) -> Option<BufferId> {
        self.buffers
            .read()
            .unwrap()
            .iter()
            .find(|(_, state)| {
                state
                    .lock()
                    .unwrap()
                    .file
                    .as_ref()
                    .is_some_and(|file| file.path == path)
            })
            .map(|(id, _)| *id)
    }

    /// Absolute path of the file behind a buffer, if it has one.
    pub fn buffer_path(&self, id: BufferId) -> Option<PathBuf> {
        self.with_buffer(id, |state| {
            state.file.as_ref().map(|file| file.path.clone())
        })
        .ok()
        .flatten()
    }

    /// Whether the buffer has edits not yet written to disk. Buffers with no
    /// file are never dirty — there is nowhere for them to be dirty against.
    pub fn buffer_is_dirty(&self, id: BufferId) -> bool {
        self.with_buffer(id, |state| match &state.file {
            Some(file) => state.version != file.saved_version || file.shape_changed,
            None => false,
        })
        .unwrap_or(false)
    }

    /// The line ending the buffer's file uses, or None for a buffer with no
    /// file — there is nothing to write it with.
    pub fn buffer_line_ending(&self, id: BufferId) -> Option<LineEnding> {
        self.with_buffer(id, |state| state.file.as_ref().map(|file| file.line_ending))
            .ok()
            .flatten()
    }

    /// Choose the line ending the next save writes — Zed's
    /// `Buffer::set_line_ending` (`text/src/text.rs:905`). The text is left
    /// alone, since it is `\n`-separated whichever is chosen; the buffer
    /// turns dirty because the bytes on disk no longer match what a save
    /// would write.
    pub fn set_buffer_line_ending(
        &self,
        id: BufferId,
        line_ending: LineEnding,
    ) -> Result<(), EngineError> {
        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        let file = state.file.as_mut().ok_or(EngineError::NoFile(id))?;
        if file.line_ending != line_ending {
            file.line_ending = line_ending;
            file.shape_changed = true;
        }
        Ok(())
    }

    /// The encoding the buffer's file is read and written in, and whether it
    /// carries a byte-order mark. None for a buffer with no file.
    pub fn buffer_encoding(&self, id: BufferId) -> Option<(&'static Encoding, bool)> {
        self.with_buffer(id, |state| {
            state
                .file
                .as_ref()
                .map(|file| (file.encoding, file.has_bom))
        })
        .ok()
        .flatten()
    }

    /// Choose the encoding the next save writes, keeping the text as it is.
    /// A byte-order mark is only ever written for the Unicode encodings, so
    /// `with_bom` is ignored for the others.
    pub fn set_buffer_encoding(
        &self,
        id: BufferId,
        encoding: &'static Encoding,
        with_bom: bool,
    ) -> Result<(), EngineError> {
        let has_bom = with_bom
            && (encoding == encoding_rs::UTF_8
                || encoding == encoding_rs::UTF_16LE
                || encoding == encoding_rs::UTF_16BE);
        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        let file = state.file.as_mut().ok_or(EngineError::NoFile(id))?;
        if file.encoding != encoding || file.has_bom != has_bom {
            file.encoding = encoding;
            file.has_bom = has_bom;
            file.shape_changed = true;
        }
        Ok(())
    }

    /// Whether the file changed on disk since the buffer last synced with it.
    pub fn buffer_has_disk_change(&self, id: BufferId) -> bool {
        self.with_buffer(id, |state| {
            state.file.as_ref().is_some_and(|file| file.external_change)
        })
        .unwrap_or(false)
    }

    /// Whether the file behind the buffer has been deleted.
    pub fn buffer_file_deleted(&self, id: BufferId) -> bool {
        self.with_buffer(id, |state| {
            state.file.as_ref().is_some_and(|file| file.deleted)
        })
        .unwrap_or(false)
    }

    /// Write the buffer to its file. Returns the version that is now on disk.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn save_buffer(&self, id: BufferId) -> Result<u64, EngineError> {
        // Take the text and path under the lock, then write without holding
        // it: a save of a large file must not stall every other buffer query.
        let (path, bytes, version) = {
            let state = self.buffer(id)?;
            let state = state.lock().unwrap();
            let file = state.file.as_ref().ok_or(EngineError::NoFile(id))?;
            // The buffer holds `\n`; the file gets its own line ending and
            // encoding back — Zed's `LocalWorktree::write_file`
            // (`worktree/src/worktree.rs:1856-1873`).
            let text = file.line_ending.apply(state.buffer.text());
            let bytes = encoding::encode_text(&text, file.encoding, file.has_bom);
            (file.path.clone(), bytes, state.version)
        };

        write_atomically(&path, &bytes)?;

        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        if let Some(file) = &mut state.file {
            // Record the version we actually wrote, not the current one: an
            // edit that landed during the write must leave the buffer dirty.
            file.mark_synced(version);
        }
        drop(state);
        // settings.json edited as a tab and saved is a settings write like
        // any other; the generation counter has to say so.
        if crate::config::settings_path().is_some_and(|settings| settings == path) {
            self.note_settings_written();
        }
        // rust-analyzer runs `cargo check` on save and nowhere else, so most of
        // its diagnostics arrive because of this line.
        self.lsp_did_save(id);
        Ok(version)
    }

    /// Re-read the file into the buffer, discarding local edits. Applied as a
    /// single edit, so it lands in the undo history like anything else and a
    /// mistaken reload is recoverable.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn reload_buffer(&self, id: BufferId) -> Result<u64, EngineError> {
        // The encoding the buffer already has: a reload re-reads the bytes,
        // it does not second-guess how they were read, except where a
        // byte-order mark says otherwise — Zed's `reload_impl` without a
        // forced encoding (`language/src/buffer.rs:1625-1652`).
        let encoding = self.buffer_encoding(id).map(|(encoding, _)| encoding);
        self.reload_buffer_as(id, encoding)
    }

    /// Re-read the file, decoding it as `encoding` instead of whatever it was
    /// read as before — the encoding picker's "reopen with encoding", Zed's
    /// `Buffer::reload_with_encoding` (`language/src/buffer.rs:1595`). The
    /// buffer's encoding becomes `encoding`, so a later save writes it back
    /// the same way. Undoable, like a plain reload.
    ///
    /// **Blocking**: call it off the Android main thread.
    pub fn reload_buffer_with_encoding(
        &self,
        id: BufferId,
        encoding: &'static Encoding,
    ) -> Result<u64, EngineError> {
        self.reload_buffer_as(id, Some(encoding))
    }

    fn reload_buffer_as(
        &self,
        id: BufferId,
        encoding: Option<&'static Encoding>,
    ) -> Result<u64, EngineError> {
        let path = self.buffer_path(id).ok_or(EngineError::NoFile(id))?;
        let (decoded, line_ending) = read_file(&path, encoding)?;
        let text = decoded.text.as_str();

        let state = self.buffer(id)?;
        let mut state = state.lock().unwrap();
        let len = state.buffer.len();
        let old_end = self
            .lsp_is_live()
            .then(|| state.buffer.snapshot().max_point_utf16());
        // `text`'s history groups edits that land close together in time, so
        // without these boundaries a reload would merge into whatever the
        // user typed a moment earlier and one undo would revert both. A
        // reload is a discrete event; make it a discrete transaction.
        let was_dirty = state.is_dirty();
        state.buffer.finalize_last_transaction();
        state.buffer.edit([(0..len, text)]);
        let transaction = state
            .buffer
            .finalize_last_transaction()
            .map(|transaction| transaction.id);
        state.version += 1;
        let needs_highlight = state.reset_highlighter();
        let version = state.version;
        if let Some(file) = &mut state.file {
            let previous = ReloadSide {
                encoding: file.encoding,
                has_bom: file.has_bom,
                dirty: was_dirty,
            };
            file.adopt(line_ending, &decoded);
            file.note_reload_encoding(transaction, previous);
            file.mark_synced(version);
        }
        let lsp_change = self.history_change(&state, old_end);
        drop(state);
        if needs_highlight {
            self.request_highlight(id);
        }
        if let Some(change) = lsp_change {
            self.lsp_did_change(id, change);
        }
        Ok(version)
    }

    /// Ask every buffer backed by one of `paths` whether its file moved
    /// underneath it.
    pub fn note_disk_changes(&self, paths: &[PathBuf]) {
        note_disk_changes(&self.buffers, paths);
    }
}

/// Same, against the shared buffer map — this is what the worktree watcher
/// calls from the runtime thread, where there is no `&Engine` to be had.
/// Costs one `stat` per *matching* buffer and nothing at all for the paths we
/// don't have open, which is almost all of them.
pub(crate) fn note_disk_changes(buffers: &crate::Buffers, paths: &[PathBuf]) {
    if paths.is_empty() {
        return;
    }
    let buffers = buffers.read().unwrap();
    for state in buffers.values() {
        let mut state = state.lock().unwrap();
        let Some(file) = &mut state.file else {
            continue;
        };
        if paths.iter().any(|path| *path == file.path) {
            file.note_disk_change();
        }
    }
}

/// Read and decode a file — guessing the encoding from its bytes, or taking
/// the one given — and detect its line ending *before* normalising the text
/// to `\n`, which is the order Zed does it in (`worktree.rs:7294-7296`).
fn read_file(
    path: &Path,
    encoding: Option<&'static Encoding>,
) -> Result<(DecodedText, LineEnding), EngineError> {
    let bytes = std::fs::read(path).map_err(|err| io_error(path, err))?;
    let mut decoded = match encoding {
        Some(encoding) => encoding::decode_text_as(bytes, encoding),
        None => encoding::decode_text(bytes),
    };
    let line_ending = LineEnding::detect(&decoded.text);
    LineEnding::normalize(&mut decoded.text);
    Ok((decoded, line_ending))
}

/// Write via a temporary file in the same directory, then rename. A crash or
/// a full disk then leaves the previous contents intact rather than a
/// half-written source file.
pub(crate) fn write_atomically(path: &Path, bytes: &[u8]) -> Result<(), EngineError> {
    write_atomically_io(path, bytes).map_err(|err| EngineError::Io {
        path: path.display().to_string(),
        message: err.to_string(),
    })
}

/// The same write, speaking `io::Error` — for the ACP fs handler, whose
/// errors go back over a wire rather than into an [`EngineError`].
pub(crate) fn write_atomically_io(path: &Path, bytes: impl AsRef<[u8]>) -> std::io::Result<()> {
    let directory = path.parent().unwrap_or(Path::new("."));
    let temporary = directory.join(format!(
        ".{}.seeker-tmp",
        path.file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| "buffer".to_owned())
    ));
    std::fs::write(&temporary, bytes)?;
    std::fs::rename(&temporary, path).inspect_err(|_| {
        let _ = std::fs::remove_file(&temporary);
    })
}

#[cfg(test)]
mod tests {
    use super::LineEnding;
    use crate::{Engine, EngineError};

    #[test]
    fn opening_the_same_file_twice_shares_one_buffer() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("main.rs");
        std::fs::write(&file, "fn main() {}\n").unwrap();

        let engine = Engine::new();
        let first = engine.open_file(&file).unwrap();
        let second = engine.open_file(&file).unwrap();
        assert_eq!(first, second);
        // …including through a path spelled differently.
        let indirect = dir.path().join("src/../main.rs");
        std::fs::create_dir_all(dir.path().join("src")).unwrap();
        assert_eq!(engine.open_file(&indirect).unwrap(), first);
    }

    #[test]
    fn dirty_tracking_follows_saves() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("notes.txt");
        std::fs::write(&file, "one").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(
            engine.buffer_path(id).unwrap(),
            std::fs::canonicalize(&file).unwrap()
        );

        engine.edit(id, 3, 3, " two").unwrap();
        assert!(engine.buffer_is_dirty(id));

        engine.save_buffer(id).unwrap();
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "one two");

        // Undo is an edit like any other: it makes the buffer dirty again.
        engine.undo(id).unwrap();
        assert!(engine.buffer_is_dirty(id));
    }

    #[test]
    fn buffers_without_a_file_cannot_be_saved_or_dirty() {
        let engine = Engine::new();
        let id = engine.create_buffer("scratch");
        engine.edit(id, 7, 7, "!").unwrap();
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(engine.buffer_path(id), None);
        assert!(matches!(
            engine.save_buffer(id),
            Err(EngineError::NoFile(_))
        ));
    }

    #[test]
    fn disk_changes_are_flagged_but_not_applied() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("shared.txt");
        std::fs::write(&file, "original").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        let path = engine.buffer_path(id).unwrap();
        assert!(!engine.buffer_has_disk_change(id));

        // Our own save must not read as an external change.
        engine.edit(id, 8, 8, "!").unwrap();
        engine.save_buffer(id).unwrap();
        engine.note_disk_changes(&[path.clone()]);
        assert!(!engine.buffer_has_disk_change(id));

        // Somebody else's write must.
        std::fs::write(&file, "clobbered by someone else").unwrap();
        engine.note_disk_changes(&[path.clone()]);
        assert!(engine.buffer_has_disk_change(id));
        // Flagged, not applied: the buffer still holds what the user had.
        assert_eq!(engine.text(id).unwrap(), "original!");

        let version = engine.reload_buffer(id).unwrap();
        assert_eq!(engine.text(id).unwrap(), "clobbered by someone else");
        assert!(!engine.buffer_has_disk_change(id));
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(engine.version(id).unwrap(), version);

        // A reload is undoable, so a mistaken one is recoverable.
        engine.undo(id).unwrap();
        assert_eq!(engine.text(id).unwrap(), "original!");
    }

    #[test]
    fn deletion_is_reported_separately_from_change() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("doomed.txt");
        std::fs::write(&file, "here").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        let path = engine.buffer_path(id).unwrap();

        std::fs::remove_file(&file).unwrap();
        engine.note_disk_changes(&[path]);
        assert!(engine.buffer_file_deleted(id));
        // Nothing to reload to, so no change is offered.
        assert!(!engine.buffer_has_disk_change(id));
        // The content is still there — a deleted file is not a lost buffer.
        assert_eq!(engine.text(id).unwrap(), "here");

        // Saving recreates it.
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "here");
        assert!(!engine.buffer_file_deleted(id));
    }

    #[test]
    fn crlf_files_round_trip_through_an_lf_buffer() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("dos.txt");
        std::fs::write(&file, "one\r\ntwo\r\n").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(engine.buffer_line_ending(id), Some(LineEnding::Windows));
        // Normalised in memory, as Zed's buffers are.
        assert_eq!(engine.text(id).unwrap(), "one\ntwo\n");
        assert_eq!(engine.line_count(id).unwrap(), 3);

        engine.edit(id, 4, 4, "and a half\n").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(
            std::fs::read(&file).unwrap(),
            b"one\r\nand a half\r\ntwo\r\n"
        );
        assert!(!engine.buffer_is_dirty(id));
    }

    #[test]
    fn choosing_a_line_ending_dirties_the_buffer_and_the_save_writes_it() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("unix.txt");
        std::fs::write(&file, "a\nb\n").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(engine.buffer_line_ending(id), Some(LineEnding::Unix));

        // The same choice again changes nothing, so it is not an edit.
        engine.set_buffer_line_ending(id, LineEnding::Unix).unwrap();
        assert!(!engine.buffer_is_dirty(id));

        engine.set_buffer_line_ending(id, LineEnding::Windows).unwrap();
        assert!(engine.buffer_is_dirty(id));
        assert_eq!(engine.text(id).unwrap(), "a\nb\n");
        engine.save_buffer(id).unwrap();
        assert!(!engine.buffer_is_dirty(id));
        assert_eq!(std::fs::read(&file).unwrap(), b"a\r\nb\r\n");

        engine.set_buffer_line_ending(id, LineEnding::Unix).unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), b"a\nb\n");

        // A scratch buffer has no file to write with any line ending.
        let scratch = engine.create_buffer("x");
        assert_eq!(engine.buffer_line_ending(scratch), None);
        assert!(matches!(
            engine.set_buffer_line_ending(scratch, LineEnding::Windows),
            Err(EngineError::NoFile(_))
        ));
    }

    #[test]
    fn mixed_line_endings_follow_the_first() {
        let dir = tempfile::tempdir().unwrap();
        let engine = Engine::new();

        let crlf_first = dir.path().join("crlf-first.txt");
        std::fs::write(&crlf_first, "one\r\ntwo\nthree\r\n").unwrap();
        let id = engine.open_file(&crlf_first).unwrap();
        assert_eq!(engine.buffer_line_ending(id), Some(LineEnding::Windows));
        assert_eq!(engine.text(id).unwrap(), "one\ntwo\nthree\n");
        // The save makes the file consistent, in the ending it led with.
        engine.set_buffer_line_ending(id, LineEnding::Windows).unwrap();
        engine.edit(id, 0, 0, "").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&crlf_first).unwrap(), b"one\r\ntwo\r\nthree\r\n");

        let lf_first = dir.path().join("lf-first.txt");
        std::fs::write(&lf_first, "one\ntwo\r\nthree\n").unwrap();
        let id = engine.open_file(&lf_first).unwrap();
        assert_eq!(engine.buffer_line_ending(id), Some(LineEnding::Unix));
        assert_eq!(engine.text(id).unwrap(), "one\ntwo\nthree\n");

        // No line break at all: the platform default, which is LF here.
        let none = dir.path().join("one-line.txt");
        std::fs::write(&none, "just this").unwrap();
        let id = engine.open_file(&none).unwrap();
        assert_eq!(engine.buffer_line_ending(id), Some(LineEnding::Unix));
    }

    #[test]
    fn a_byte_order_mark_is_kept_out_of_the_buffer_and_written_back() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("bom.txt");
        std::fs::write(&file, b"\xEF\xBB\xBFhello\n").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(engine.text(id).unwrap(), "hello\n");
        assert_eq!(engine.buffer_encoding(id), Some((encoding_rs::UTF_8, true)));

        engine.edit(id, 5, 5, ", world").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), b"\xEF\xBB\xBFhello, world\n");

        // Dropping the mark is a choice that makes the buffer dirty, and the
        // next save honours it.
        engine.set_buffer_encoding(id, encoding_rs::UTF_8, false).unwrap();
        assert!(engine.buffer_is_dirty(id));
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), b"hello, world\n");
        assert_eq!(engine.buffer_encoding(id), Some((encoding_rs::UTF_8, false)));
    }

    #[test]
    fn utf16_with_a_mark_round_trips() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("wide.txt");
        let bytes = crate::encoding::encode_text("héllo\r\nwörld\r\n", encoding_rs::UTF_16LE, true);
        std::fs::write(&file, &bytes).unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(engine.text(id).unwrap(), "héllo\nwörld\n");
        assert_eq!(engine.buffer_encoding(id), Some((encoding_rs::UTF_16LE, true)));
        assert_eq!(engine.buffer_line_ending(id), Some(LineEnding::Windows));

        engine.edit(id, 0, 0, "").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), bytes);
    }

    #[test]
    fn windows_1252_files_open_and_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("latin1.txt");
        // "café — naïve" in windows-1252: bytes no UTF-8 decoder accepts.
        let bytes = b"caf\xE9 \x97 na\xEFve\n".to_vec();
        std::fs::write(&file, &bytes).unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(engine.text(id).unwrap(), "café — naïve\n");
        assert_eq!(
            engine.buffer_encoding(id),
            Some((encoding_rs::WINDOWS_1252, false))
        );

        engine.edit(id, 0, 0, "").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), bytes);

        // An edit in the buffer's own alphabet comes back in that alphabet.
        let len = engine.len(id).unwrap();
        engine.edit(id, len, len, "ça va\n").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(
            std::fs::read(&file).unwrap(),
            b"caf\xE9 \x97 na\xEFve\n\xE7a va\n"
        );
    }

    #[test]
    fn reopening_with_an_encoding_reinterprets_the_same_bytes() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("cyrillic.txt");
        // "привет" in windows-1251, which is also six perfectly good
        // windows-1252 characters, so the guess lands on 1252.
        let (bytes, _, _) = encoding_rs::WINDOWS_1251.encode("привет\n");
        std::fs::write(&file, &bytes).unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        assert_eq!(
            engine.buffer_encoding(id),
            Some((encoding_rs::WINDOWS_1252, false))
        );
        assert_ne!(engine.text(id).unwrap(), "привет\n");

        engine
            .reload_buffer_with_encoding(id, encoding_rs::WINDOWS_1251)
            .unwrap();
        assert_eq!(engine.text(id).unwrap(), "привет\n");
        assert_eq!(
            engine.buffer_encoding(id),
            Some((encoding_rs::WINDOWS_1251, false))
        );
        assert!(!engine.buffer_is_dirty(id));

        // And a save now writes 1251, so the file is unchanged.
        engine.edit(id, 0, 0, "").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), bytes.as_ref());

        // Choosing to save as UTF-8 instead transcodes on the way out.
        engine.set_buffer_encoding(id, encoding_rs::UTF_8, false).unwrap();
        assert!(engine.buffer_is_dirty(id));
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), "привет\n".as_bytes());

        // The reinterpretation is a reload, so it is undoable — and the
        // file was saved as UTF-8 since, so undoing it dirties the buffer.
        engine.undo(id).unwrap();
        assert!(engine.buffer_is_dirty(id));
    }

    /// The picker's reopen, then Ctrl+Z, then Ctrl+Shift+Z, as they land on
    /// a clean buffer: one step each way, the encoding travelling with the
    /// text, and the buffer clean on both sides — each is the file on disk
    /// read one way or the other (Zed's `restore_encoding_for_transaction`).
    #[test]
    fn undoing_a_reopen_restores_the_old_encoding_and_stays_clean() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("cyrillic.txt");
        let (bytes, _, _) = encoding_rs::WINDOWS_1251.encode("привет\n");
        std::fs::write(&file, &bytes).unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        let mojibake = engine.text(id).unwrap();
        engine
            .reload_buffer_with_encoding(id, encoding_rs::WINDOWS_1251)
            .unwrap();
        assert_eq!(engine.text(id).unwrap(), "привет\n");
        assert!(!engine.buffer_is_dirty(id));

        // One undo: the mojibake is back, read as 1252 again, still clean.
        assert!(engine.undo(id).unwrap().is_some());
        assert_eq!(engine.text(id).unwrap(), mojibake);
        assert_eq!(
            engine.buffer_encoding(id),
            Some((encoding_rs::WINDOWS_1252, false))
        );
        assert!(!engine.buffer_is_dirty(id));
        // Nothing before the reload to undo: the open is not an edit.
        assert!(engine.undo(id).unwrap().is_none());

        // One redo: 1251 again, and still nothing to save.
        assert!(engine.redo(id).unwrap().is_some());
        assert_eq!(engine.text(id).unwrap(), "привет\n");
        assert_eq!(
            engine.buffer_encoding(id),
            Some((encoding_rs::WINDOWS_1251, false))
        );
        assert!(!engine.buffer_is_dirty(id));

        // A save in the restored encoding writes the bytes back unchanged.
        engine.undo(id).unwrap();
        engine.edit(id, 0, 0, "").unwrap();
        engine.save_buffer(id).unwrap();
        assert_eq!(std::fs::read(&file).unwrap(), bytes.as_ref());
    }

    /// A reload undone over a buffer that was already dirty is dirty
    /// again: the edits it discarded are back, and still not on disk. (Zed
    /// marks this side clean too, since the reload itself was clean; the
    /// dirty dot is kept here because the edits it stands for are real.)
    #[test]
    fn undoing_a_reopen_over_a_dirty_buffer_keeps_it_dirty() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("cyrillic.txt");
        let (bytes, _, _) = encoding_rs::WINDOWS_1251.encode("привет\n");
        std::fs::write(&file, &bytes).unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        engine.edit(id, 0, 0, "x").unwrap();
        engine
            .reload_buffer_with_encoding(id, encoding_rs::WINDOWS_1251)
            .unwrap();
        assert!(!engine.buffer_is_dirty(id));
        // The reload replaced the whole text, so one undo brings back the
        // edited mojibake — which is not what is on disk.
        engine.undo(id).unwrap();
        assert!(engine.text(id).unwrap().starts_with('x'));
        assert_eq!(
            engine.buffer_encoding(id),
            Some((encoding_rs::WINDOWS_1252, false))
        );
        assert!(engine.buffer_is_dirty(id));
    }

    #[test]
    fn a_failed_save_leaves_the_previous_contents() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("readonly/file.txt");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, "safe").unwrap();

        let engine = Engine::new();
        let id = engine.open_file(&file).unwrap();
        engine.edit(id, 4, 4, " edit").unwrap();

        // Make the directory unwritable so the temporary file can't be made.
        let directory = file.parent().unwrap();
        let mut permissions = std::fs::metadata(directory).unwrap().permissions();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            permissions.set_mode(0o555);
        }
        std::fs::set_permissions(directory, permissions).unwrap();

        let result = engine.save_buffer(id);

        let mut permissions = std::fs::metadata(directory).unwrap().permissions();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            permissions.set_mode(0o755);
        }
        std::fs::set_permissions(directory, permissions).unwrap();

        assert!(matches!(result, Err(EngineError::Io { .. })));
        assert_eq!(std::fs::read_to_string(&file).unwrap(), "safe");
        // Still dirty: the edit was not written, and the UI must keep saying so.
        assert!(engine.buffer_is_dirty(id));
    }
}
