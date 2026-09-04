package to.eyed.thragg.core

/**
 * Handle for one open engine buffer. Thin: raw byte-range edits, undo/redo
 * and version tracking. Reading is done by callers through the line-window
 * API ([CoreBridge.bufferLines]) — the UI layer never holds the whole
 * buffer.
 */
class BufferSession private constructor(val id: Long) {
    constructor(initialText: String) : this(CoreBridge.createBuffer(initialText))

    var version: Long = CoreBridge.bufferVersion(id)
        private set

    val lineCount: Int
        get() = CoreBridge.bufferLineCount(id).toInt().coerceAtLeast(1)

    /**
     * Replace the byte range [start, end) with [replacement]. Offsets must
     * lie on UTF-8 code-point boundaries. Returns the version this edit
     * produced — the engine bumps it by exactly one per edit, under the
     * buffer's lock, so the caller can tell a lone edit from one that
     * another writer's edit slipped in ahead of — or -1 if the engine
     * rejected the edit.
     */
    fun editBytes(start: Long, end: Long, replacement: String): Long {
        val newVersion = CoreBridge.applyEdit(id, start, end, replacement)
        if (newVersion >= 0) version = newVersion
        return newVersion
    }

    /**
     * Resolve the merge conflict whose `<<<<<<<` line is [startRow], keeping
     * ours, theirs or both, as one edit — see [CoreBridge.resolveConflict].
     * Returns the version it produced, or -1 when the row no longer opens a
     * conflict and nothing changed.
     */
    fun resolveConflict(startRow: Int, keepOurs: Boolean, keepTheirs: Boolean): Long {
        val newVersion = CoreBridge.resolveConflict(id, startRow.toLong(), keepOurs, keepTheirs)
        if (newVersion >= 0) version = newVersion
        return newVersion
    }

    /**
     * Assign a tree-sitter language (grammar name, e.g. "rust"). Returns
     * false for unknown language names.
     */
    fun setLanguage(language: String): Boolean = CoreBridge.bufferSetLanguage(id, language)

    /**
     * Bumped when the engine's background reparse lands. Syntax highlighting
     * is allowed to trail the text by a frame or two — reparsing on the
     * keystroke path is what made large files stutter.
     */
    val highlightVersion: Long
        get() = CoreBridge.bufferHighlightVersion(id)

    /** Grammar name for the status bar; null when no language is assigned. */
    val language: String?
        get() = CoreBridge.bufferLanguage(id)

    /** Absolute path of the backing file; null for scratch buffers. */
    val path: String?
        get() = CoreBridge.bufferPath(id)

    /**
     * The settings in force for this buffer, every layer resolved by the
     * engine. **Blocking** (reads settings.json) — call it off the main
     * thread, and only when something may have changed them: a settings
     * write, a project settings version bump, the buffer opening.
     */
    fun languageSettings(): LanguageSettings = LanguageSettings.load(id)

    /** Edits not yet written to disk. Always false without a backing file. */
    val isDirty: Boolean
        get() = CoreBridge.bufferIsDirty(id)

    /**
     * The file changed on disk since this buffer last synced with it, as
     * reported by the engine's file watcher. Cleared by [save] or [reload].
     */
    val hasDiskChange: Boolean
        get() = CoreBridge.bufferHasDiskChange(id)

    /** The backing file has been deleted from disk. */
    val isFileDeleted: Boolean
        get() = CoreBridge.bufferFileDeleted(id)

    /**
     * Write to the backing file. Returns false if there is none or the write
     * failed. **Blocking** — call from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun save(): Boolean = applyVersion(CoreBridge.saveBuffer(id))

    /**
     * Re-read the backing file, discarding local edits (undoably). Returns
     * false on failure. **Blocking** — call from
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun reload(): Boolean = applyVersion(CoreBridge.reloadBuffer(id))

    /**
     * The line ending the backing file uses and the next save writes; null
     * for a scratch buffer, which has nothing to write.
     */
    val lineEnding: LineEnding?
        get() = CoreBridge.bufferLineEnding(id)?.let(LineEnding::fromKey)

    /**
     * Choose the line ending the next save writes. The text is untouched —
     * it is `\n`-separated either way — and the buffer turns dirty, because
     * the file no longer says what a save would write.
     */
    fun setLineEnding(lineEnding: LineEnding): Boolean =
        CoreBridge.setBufferLineEnding(id, lineEnding.key)

    /** The encoding the backing file is read and written in; null for a scratch buffer. */
    val encoding: BufferEncoding?
        get() = CoreBridge.bufferEncoding(id)?.let(BufferEncoding::fromJson)

    /**
     * Choose the encoding the next save writes, keeping the text as it is.
     * Dirties the buffer like [setLineEnding].
     */
    fun setEncoding(encoding: BufferEncoding): Boolean =
        CoreBridge.setBufferEncoding(id, encoding.name, encoding.hasBom)

    /**
     * Re-read the backing file decoded as [encoding] — "reopen with
     * encoding". Discards local edits, undoably, like [reload]. Returns
     * false on failure. **Blocking** — call from
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun reopenWithEncoding(encoding: String): Boolean =
        applyVersion(CoreBridge.reopenBufferWithEncoding(id, encoding))

    /** Undo the last edit transaction. Returns false if nothing to undo. */
    fun undo(): Boolean = applyVersion(CoreBridge.undoBuffer(id))

    /** Redo the last undone transaction. Returns false if nothing to redo. */
    fun redo(): Boolean = applyVersion(CoreBridge.redoBuffer(id))

    fun close(): Boolean = CoreBridge.closeBuffer(id)

    /**
     * Re-read the version after the engine edited the buffer without going
     * through this handle — a search-and-replace, a workspace edit a
     * language server applied. [version] is what the editor's line window
     * is keyed on, so a stale one would keep serving the text from before.
     */
    fun refreshVersion() {
        version = CoreBridge.bufferVersion(id)
    }

    /** Adopt a version the engine returned, or report the -1 failure. */
    private fun applyVersion(newVersion: Long): Boolean {
        if (newVersion < 0) return false
        version = newVersion
        return true
    }

    companion object {
        /**
         * A handle on a buffer the engine already holds — the composed buffer
         * behind a multibuffer, which is created by
         * [CoreBridge.multibufferCreate] rather than here.
         *
         * Closing it is the multibuffer's job, not this handle's: see
         * [MultiBufferSession.close].
         */
        fun adopt(id: Long): BufferSession = BufferSession(id)

        /**
         * Read a file from disk into a new engine buffer, with the language
         * chosen from its name. Returns null if it could not be read.
         *
         * **Blocking** (file I/O in the engine) — call it off the main thread.
         */
        fun openFile(absolutePath: String): BufferSession? {
            val id = CoreBridge.openFile(absolutePath)
            return if (id < 0) null else BufferSession(id)
        }
    }
}
