package to.eyed.seeker.code.ui.editor

/**
 * An [EditorBuffer] backed by a string, with the engine's arithmetic rather
 * than a convenient approximation of it: offsets are UTF-8 bytes, and an
 * edit whose ends are not on code-point boundaries — or past the end of the
 * buffer — is *refused*, which is what `Engine::edit` does with
 * `InvalidRange`.
 *
 * Refusing matters as much as editing: a rejected edit is how a caret ends
 * up at an offset the buffer never had, and the batch machinery has to
 * notice.
 */
internal class FakeEditorBuffer(
    text: String,
    override val language: String? = null,
    /**
     * The language config the engine would hand over, verbatim. Tests that
     * care about a language's rules paste the real thing; the rest pass none
     * and get the empty config, which closes nothing and comments nothing.
     */
    override val languageConfigJson: String? = null,
) : EditorBuffer {

    var text: String = text
        private set

    /**
     * The scopes [bracketScopes] reports, as byte-offset ranges in which
     * *no* pair is live — a comment or a string, as far as the caret is
     * concerned. Empty means everything is live everywhere, which is what the
     * engine answers for a buffer with no language.
     *
     * The real answer comes from the syntax tree and is tested in Rust
     * (`engine::tests::bracket_scopes_follow_the_syntax_tree`); what these
     * tests need from it is only that the editor obeys it.
     */
    val deadZones = mutableListOf<LongRange>()

    /** How many times the editor went to the engine for a scope. */
    var scopeQueries = 0
        private set

    override var version: Long = 1L
        private set

    /**
     * Settable so a test can play the engine's background reparse landing —
     * the one event that must cost a span read and nothing else.
     */
    override var highlightVersion: Long = 0L

    /** Edits the buffer refused, in the order it refused them. */
    val refusedEdits = mutableListOf<Triple<Long, Long, String>>()

    /**
     * Runs once, at the top of the next [edit] — the stand-in for a second
     * writer (an agent's `on_write_text_file`, the disk-change reload)
     * whose engine-side edit lands between the editor's staleness check
     * and the editor's own edit reaching the engine. A hook that edits the
     * buffer makes the editor's edit come back with a version two past the
     * one it checked, which is exactly what the window cache's patch guard
     * has to notice.
     */
    var beforeNextEdit: (() -> Unit)? = null

    private val rows: List<String> get() = text.split('\n')

    override val lineCount: Int get() = rows.size

    /**
     * Rows this buffer has handed out, for the tests that care what a query
     * costs rather than what it answers. The display map's whole claim is
     * that it reads a block of the file, not the file.
     */
    var rowsRead = 0
        private set

    /**
     * Times it was asked at all. Rows are the cheap part of a read; the round
     * trip over JNI is the part a keystroke pays for, so the tests that count
     * the cost of an edit count these.
     */
    var lineCalls = 0
        private set

    override fun lines(firstRow: Int, lastRow: Int): String {
        val all = rows
        val first = firstRow.coerceIn(0, all.size)
        val last = lastRow.coerceIn(first, all.size)
        lineCalls++
        rowsRead += last - first
        return all.subList(first, last).joinToString("\n")
    }

    /** Times the highlight query crossed, counted like [lineCalls]. */
    var highlightCalls = 0
        private set

    override fun highlights(firstRow: Int, lastRow: Int): IntArray? {
        highlightCalls++
        return IntArray(0)
    }

    /**
     * What [syntaxNodeRange] answers, innermost first: the ladder
     * `editor::SelectLargerSyntaxNode` climbs, as row/column ranges. A test
     * that cares about the stack sets it; the real ladder comes from the
     * tree and is tested in Rust
     * (`highlight::syntax_selection_tests`).
     */
    val syntaxLadder = mutableListOf<Caret>()

    override fun syntaxNodeRange(startRow: Int, startCol: Int, endRow: Int, endCol: Int): Caret? =
        syntaxLadder.firstOrNull { range ->
            val startsBefore = range.anchorRow < startRow ||
                (range.anchorRow == startRow && range.anchorCol <= startCol)
            val endsAfter = range.headRow > endRow ||
                (range.headRow == endRow && range.headCol >= endCol)
            val wider = range.anchorRow != startRow || range.anchorCol != startCol ||
                range.headRow != endRow || range.headCol != endCol
            startsBefore && endsAfter && wider
        }

    /**
     * The counting fallback the engine uses for a buffer with no grammar,
     * over the one-line-per-row text this fake holds — enough for the pane's
     * own behaviour, which is all these tests are about.
     */
    override fun enclosingBrackets(
        startRow: Int,
        startCol: Int,
        endRow: Int,
        endCol: Int,
    ): Pair<Caret, Caret>? {
        val text = this.text
        fun offsetOf(row: Int, col: Int): Int {
            var offset = 0
            val all = rows
            for (i in 0 until row.coerceIn(0, all.size - 1)) offset += all[i].length + 1
            return offset + col
        }
        fun pointOf(offset: Int): Pair<Int, Int> {
            var remaining = offset
            for ((row, line) in rows.withIndex()) {
                if (remaining <= line.length) return row to remaining
                remaining -= line.length + 1
            }
            return rows.lastIndex to rows.last().length
        }
        val from = offsetOf(startRow, startCol)
        val to = offsetOf(endRow, endCol)
        for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            var depth = 0
            var openAt = -1
            var i = from
            while (i > 0) {
                i--
                when (text.getOrNull(i)) {
                    close -> depth++
                    open -> if (depth == 0) { openAt = i; i = 0 } else depth--
                }
            }
            if (openAt < 0) continue
            depth = 0
            var closeAt = -1
            i = to
            while (i < text.length) {
                when (text[i]) {
                    open -> depth++
                    close -> if (depth == 0) { closeAt = i; i = text.length } else depth--
                }
                i++
            }
            if (closeAt < 0) continue
            val openPoint = pointOf(openAt)
            val closePoint = pointOf(closeAt)
            return Caret(openPoint.first, openPoint.second, openPoint.first, openPoint.second + 1) to
                Caret(closePoint.first, closePoint.second, closePoint.first, closePoint.second + 1)
        }
        return null
    }

    override fun bracketScopes(offsets: LongArray): LongArray {
        scopeQueries++
        return LongArray(offsets.size) { index ->
            if (deadZones.any { offsets[index] in it }) 0L else -1L
        }
    }

    override fun rowStart(row: Int): Long {
        val all = rows
        var offset = 0
        for (i in 0 until row.coerceIn(0, all.size - 1)) {
            offset += utf8(all[i]).size + 1
        }
        return offset.toLong()
    }

    override fun pointOf(offset: Long): Long {
        val bytes = utf8(text)
        var at = offset.coerceIn(0, bytes.size.toLong()).toInt()
        // `at == bytes.size` is the end of the buffer and a perfectly ordinary
        // answer — it is where the caret sits after appending to the last line
        // — so the walk back to a character boundary must not read there. The
        // engine clips the same way, with `Bias::Left`.
        while (at in 1 until bytes.size && isContinuation(bytes[at])) at--
        var row = 0L
        var rowStart = 0
        for (i in 0 until at) {
            if (bytes[i] == '\n'.code.toByte()) {
                row++
                rowStart = i + 1
            }
        }
        return (row shl 32) or (at - rowStart).toLong()
    }

    override fun edit(start: Long, end: Long, replacement: String): Long {
        beforeNextEdit?.also { beforeNextEdit = null }?.invoke()
        val bytes = utf8(text)
        if (start < 0 || end < start || end > bytes.size) {
            refusedEdits.add(Triple(start, end, replacement))
            return -1L
        }
        val from = start.toInt()
        val to = end.toInt()
        if (isContinuation(bytes, from) || isContinuation(bytes, to)) {
            refusedEdits.add(Triple(start, end, replacement))
            return -1L
        }
        undoStack.add(text)
        redoStack.clear()
        edits.add(Triple(start, end, replacement))
        text = String(bytes, 0, from, Charsets.UTF_8) +
            replacement +
            String(bytes, to, bytes.size - to, Charsets.UTF_8)
        return ++version
    }

    /**
     * Every edit the buffer accepted, in order — what a test reads to know
     * which engine calls an operation cost, and what they asked for.
     */
    val edits = mutableListOf<Triple<Long, Long, String>>()

    // The engine groups edits into transactions by time; here every
    // accepted edit is its own step, which is enough to show whether the
    // state around the buffer survives a history step at all.
    private val undoStack = mutableListOf<String>()
    private val redoStack = mutableListOf<String>()

    override fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.add(text)
        text = previous
        version++
        return true
    }

    override fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.add(text)
        text = next
        version++
        return true
    }

    /** The fake holds no git state: no conflicts, and nothing to resolve. */
    override fun conflicts(): List<ConflictRegion> = emptyList()

    override fun resolveConflict(startRow: Int, keepOurs: Boolean, keepTheirs: Boolean): Long = -1L

    private fun utf8(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun isContinuation(bytes: ByteArray, at: Int): Boolean =
        at < bytes.size && isContinuation(bytes[at])

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80
}
