package to.eyed.thragg.ui.editor

import to.eyed.thragg.core.BufferSession
import to.eyed.thragg.core.CoreBridge

/**
 * Everything the editor asks of the buffer it is editing.
 *
 * There is exactly one implementation in the app — [SessionBuffer], a
 * straight forward of one JNI call per method. The interface exists so the
 * caret arithmetic can be run on the host against an in-memory buffer: the
 * offsets it computes *are* the user's file, an edit at the wrong byte
 * corrupts it silently, and a test that only runs on a device with the
 * engine behind it is a test nobody runs.
 *
 * Rows are 0-based; offsets are UTF-8 bytes, as everywhere the engine is
 * involved.
 */
internal interface EditorBuffer {
    val version: Long
    val highlightVersion: Long
    val lineCount: Int
    val language: String?

    /**
     * The language's editing rules as JSON, for
     * [EditorLanguage.configFor] to parse. Asked at most once per grammar —
     * the answer is the same for every buffer in it.
     */
    val languageConfigJson: String?

    /** Text of rows [firstRow, lastRow), joined by '\n', clipped. */
    fun lines(firstRow: Int, lastRow: Int): String

    /**
     * Per offset, a bitmask of the bracket pairs live there (bit *i* for pair
     * *i* of the language's `brackets`). Answers the `not_in` scopes, which
     * need the syntax tree — see [to.eyed.thragg.core.CoreBridge.bufferBracketScopes].
     */
    fun bracketScopes(offsets: LongArray): LongArray

    /** Flat [row, start, end, style] highlight groups for the same range. */
    fun highlights(firstRow: Int, lastRow: Int): IntArray?

    /**
     * The smallest syntax node that strictly contains the given row/column
     * range — what `editor::SelectLargerSyntaxNode` grows a selection to.
     * Null when nothing wider exists, the buffer has no grammar, or the tree
     * has not been parsed yet, in which case the selection is left alone.
     */
    fun syntaxNodeRange(startRow: Int, startCol: Int, endRow: Int, endCol: Int): Caret?

    /**
     * The innermost bracket pair around the given range, as `(open, close)` —
     * what the pane highlights and what `editor::MoveToEnclosingBracket`
     * jumps between. Null when nothing encloses it.
     */
    fun enclosingBrackets(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
    ): Pair<Caret, Caret>?

    /** Byte offset of the start of [row]. */
    fun rowStart(row: Int): Long

    /** (row, byte column) of [offset], packed as `(row shl 32) or column`. */
    fun pointOf(offset: Long): Long

    /**
     * Replace the byte range [start, end) with [replacement]. Returns the
     * buffer version this edit produced — the engine bumps the version by
     * exactly one per edit, under the buffer's lock, which is what lets
     * [EditorState.applyLineDiff]'s in-place window patch tell a lone edit
     * (returned == checked version + 1) from one that a concurrent writer
     * slipped in ahead of. Returns -1 when the engine refused the edit —
     * an offset off a code-point boundary or past the end of the buffer —
     * in which case nothing changed.
     */
    fun edit(start: Long, end: Long, replacement: String): Long

    fun undo(): Boolean

    fun redo(): Boolean

    /**
     * The merge-conflict regions in the buffer, in order — a linear scan of
     * the whole text on the engine side, so ask when the version has moved,
     * not per frame. See [ConflictRegion].
     */
    fun conflicts(): List<ConflictRegion>

    /**
     * Resolve the conflict whose `<<<<<<<` line is [startRow], keeping the
     * chosen sides, as one edit. Returns the version it produced, or -1 when
     * the row no longer opens a conflict, in which case nothing changed.
     */
    fun resolveConflict(startRow: Int, keepOurs: Boolean, keepTheirs: Boolean): Long
}

/**
 * A buffer that can be read, searched and scrolled but never changed — what
 * `zed::OpenDefaultSettings` shows (zed/src/zed.rs:306-316, `open_bundled_file`
 * makes its editor read-only). Every edit is refused the way the engine
 * refuses a bad range, which every caller already handles: nothing lands,
 * nothing is left half-applied, and the tab stays clean.
 */
internal class ReadOnlyBuffer(private val inner: EditorBuffer) : EditorBuffer by inner {
    override fun edit(start: Long, end: Long, replacement: String): Long = -1L
    override fun undo(): Boolean = false
    override fun redo(): Boolean = false
}

/** The real thing: one open engine buffer, over the JNI bridge. */
internal class SessionBuffer(private val session: BufferSession) : EditorBuffer {
    override val version: Long get() = session.version
    override val highlightVersion: Long get() = session.highlightVersion
    override val lineCount: Int get() = session.lineCount
    override val language: String? get() = session.language

    override val languageConfigJson: String?
        get() = session.language?.let(CoreBridge::languageConfig)

    override fun lines(firstRow: Int, lastRow: Int): String =
        CoreBridge.bufferLines(session.id, firstRow.toLong(), lastRow.toLong()).orEmpty()

    override fun bracketScopes(offsets: LongArray): LongArray =
        CoreBridge.bufferBracketScopes(session.id, offsets)

    override fun highlights(firstRow: Int, lastRow: Int): IntArray? =
        CoreBridge.bufferHighlights(session.id, firstRow.toLong(), lastRow.toLong())

    override fun syntaxNodeRange(startRow: Int, startCol: Int, endRow: Int, endCol: Int): Caret? =
        parseTextRange(
            CoreBridge.bufferSyntaxNodeRange(
                session.id,
                startRow.toLong(),
                startCol.toLong(),
                endRow.toLong(),
                endCol.toLong(),
            )
        )

    override fun enclosingBrackets(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
    ): Pair<Caret, Caret>? {
        val json = CoreBridge.bufferEnclosingBrackets(
            session.id,
            startRow.toLong(),
            startCol.toLong(),
            endRow.toLong(),
            endCol.toLong(),
        ) ?: return null
        val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        val open = textRangeOf(root.optJSONObject("open")) ?: return null
        val close = textRangeOf(root.optJSONObject("close")) ?: return null
        return open to close
    }

    override fun rowStart(row: Int): Long = CoreBridge.pointToOffset(session.id, row.toLong(), 0)

    override fun pointOf(offset: Long): Long = CoreBridge.offsetToPoint(session.id, offset)

    override fun edit(start: Long, end: Long, replacement: String): Long =
        session.editBytes(start, end, replacement)

    override fun undo(): Boolean = session.undo()

    override fun redo(): Boolean = session.redo()

    override fun conflicts(): List<ConflictRegion> =
        ConflictRegion.parseAll(CoreBridge.bufferConflicts(session.id))

    override fun resolveConflict(startRow: Int, keepOurs: Boolean, keepTheirs: Boolean): Long =
        session.resolveConflict(startRow, keepOurs, keepTheirs)
}

/**
 * The engine's `{start_row, start_col_utf16, end_row, end_col_utf16}` as the
 * pane's own [Caret] — the shape a selection is set from, anchor at the start
 * and head at the end.
 */
internal fun parseTextRange(json: String?): Caret? {
    if (json.isNullOrEmpty()) return null
    return textRangeOf(runCatching { org.json.JSONObject(json) }.getOrNull())
}

/** [parseTextRange] over an object that has already been parsed. */
internal fun textRangeOf(range: org.json.JSONObject?): Caret? {
    if (range == null) return null
    val startRow = range.optInt("start_row", -1)
    val endRow = range.optInt("end_row", -1)
    if (startRow < 0 || endRow < 0) return null
    return Caret(
        anchorRow = startRow,
        anchorCol = range.optInt("start_col_utf16", 0),
        headRow = endRow,
        headCol = range.optInt("end_col_utf16", 0),
    )
}
