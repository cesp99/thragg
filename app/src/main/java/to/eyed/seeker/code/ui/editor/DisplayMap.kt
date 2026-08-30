package to.eyed.seeker.code.ui.editor

import kotlin.math.max
import kotlin.math.min

/**
 * Zed's `soft_wrap`, cut down to the two modes a pane can honour.
 *
 * `bounded` and `preferred_line_length` belong to the wrap-guide feature and
 * are deliberately not here; `prefer_line` is Zed's own deprecated spelling
 * of `none` (assets/settings/default.json:1530-1536).
 */
enum class SoftWrapMode(val key: String) {
    /** Zed's default — a long line runs off the right edge and scrolls. */
    None("none"),

    /** Wrap at the width of the text area. */
    EditorWidth("editor_width"),

    /**
     * Wrap at `preferred_line_length` or the text area's width, whichever
     * is narrower — Zed's `bounded` (editor/src/config.rs:268-270).
     */
    Bounded("bounded");

    val wraps: Boolean get() = this != None

    companion object {
        fun fromKey(key: String?): SoftWrapMode = entries.firstOrNull { it.key == key } ?: None
    }
}

/**
 * Where one buffer row's soft-wrapped segments begin and end, in UTF-16
 * columns.
 *
 * A row that fits is [FITS] — one segment, no breaks, shared by every such
 * row — which is what most rows of most files are and the case this is
 * shaped for.
 */
internal class WrappedLine(
    private val breaks: IntArray,
    /**
     * Columns every segment after the first is pushed right by: the row's own
     * indent, so a wrapped line still reads as one paragraph. Zed carries the
     * same number out of its wrapper (gpui/src/text_system/line_wrapper.rs:110).
     */
    val indentColumns: Int,
) {
    val segmentCount: Int get() = breaks.size + 1

    val wraps: Boolean get() = breaks.isNotEmpty()

    fun startOf(segment: Int): Int = if (segment <= 0) 0 else breaks[segment - 1]

    fun endOf(segment: Int, length: Int): Int =
        if (segment >= breaks.size) length else breaks[segment]

    /**
     * The segment UTF-16 column [col] is drawn in. A column that is exactly a
     * break belongs to the segment it *starts* — the caret after the last
     * character of a wrapped segment sits at the head of the next one, which
     * is where every editor puts it.
     */
    fun segmentOf(col: Int): Int {
        var low = 0
        var high = breaks.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (breaks[mid] <= col) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        val FITS = WrappedLine(IntArray(0), 0)
    }
}

/**
 * Where a line breaks when it is wrapped to a width, in columns.
 *
 * A port of Zed's `LineWrapper::wrap_line`
 * (crates/gpui/src/text_system/line_wrapper.rs:39) with pixels replaced by
 * columns. The buffer font is monospace and every other measurement in this
 * renderer already assumes so — the gutter's width, the indent guides' step —
 * and the substitution buys two things worth more than per-glyph accuracy:
 * the scan is pure arithmetic, so counting the display rows of a block of the
 * file costs no text measurement at all, and the whole of it is testable on
 * the host, where no `TextMeasurer` exists.
 *
 * What it costs: a row of double-width glyphs (CJK, emoji) is counted one
 * column per code point and overhangs the right edge rather than breaking
 * early. The breaks stay consistent between counting and drawing, which is
 * what keeps the map honest about its own geometry.
 */
internal object SoftWrap {

    /** Zed's cap on how far a continuation may be pushed right. */
    private const val MAX_INDENT = 256

    /**
     * Columns of [text]'s leading whitespace, which is what its continuations
     * are indented by. A tab counts as [tabSize] columns, the same as it does
     * for the indent guides.
     *
     * A row that is nothing but whitespace has none: there is no text on it
     * for a continuation to line up under.
     */
    fun indentColumns(text: String, tabSize: Int): Int {
        var columns = 0
        for (char in text) {
            when (char) {
                '\t' -> columns += tabSize
                ' ' -> columns++
                else -> return min(columns, MAX_INDENT)
            }
        }
        return 0
    }

    /**
     * Number of display rows [text] occupies at [wrapColumns], appending each
     * break's UTF-16 offset to [breaks] when one is given.
     *
     * Passing no list is the counting path — it walks the same characters and
     * allocates nothing, which is what makes measuring a block of the file
     * cheap enough to do while drawing.
     */
    fun wrap(
        text: String,
        wrapColumns: Int,
        tabSize: Int,
        indentColumns: Int,
        breaks: MutableList<Int>? = null,
    ): Int {
        if (wrapColumns <= 0 || text.isEmpty()) return 1
        // A continuation indent as wide as the wrap width would leave no room
        // for the text it is indenting; half the width is where an indent
        // stops helping and starts being the problem.
        val indent = indentColumns.coerceIn(0, wrapColumns / 2)
        var segments = 1
        var width = 0
        var seenText = false
        var lastCandidate = 0
        var lastCandidateWidth = 0
        var lastWrap = 0
        var previous = ' '
        var i = 0
        while (i < text.length) {
            val at = i
            val char = text[i]
            val step = if (
                Character.isHighSurrogate(char) && i + 1 < text.length &&
                Character.isLowSurrogate(text[i + 1])
            ) 2 else 1
            // Where the line would rather break: in front of a word that
            // follows a space, or in front of anything that is not a word
            // character at all — which is how CJK, written without spaces,
            // breaks at all (line_wrapper.rs:66-80).
            if (seenText) {
                if (isWordChar(char)) {
                    if (previous == ' ' && char != ' ') {
                        lastCandidate = at
                        lastCandidateWidth = width
                    }
                } else if (char != ' ') {
                    lastCandidate = at
                    lastCandidateWidth = width
                }
            }
            if (!seenText && !char.isWhitespace()) seenText = true
            val charWidth = if (char == '\t') tabSize else 1
            width += charWidth
            // `at > lastWrap` guarantees the scan advances: a single character
            // wider than the whole width still gets a row to itself instead of
            // breaking forever in front of itself.
            if (width > wrapColumns && at > lastWrap) {
                if (lastCandidate > lastWrap) {
                    lastWrap = lastCandidate
                    width -= lastCandidateWidth
                    lastCandidate = 0
                } else {
                    lastWrap = at
                    width = charWidth
                }
                width += indent
                breaks?.add(lastWrap)
                segments++
            }
            previous = char
            i += step
        }
        return segments
    }

    /** [text]'s segments at this width — the shared [WrappedLine.FITS] if it fits. */
    fun of(text: String, wrapColumns: Int, tabSize: Int): WrappedLine {
        if (wrapColumns <= 0 || text.isEmpty()) return WrappedLine.FITS
        val indent = indentColumns(text, tabSize)
        val breaks = ArrayList<Int>(4)
        wrap(text, wrapColumns, tabSize, indent, breaks)
        if (breaks.isEmpty()) return WrappedLine.FITS
        return WrappedLine(breaks.toIntArray(), indent.coerceIn(0, wrapColumns / 2))
    }

    /**
     * Zed's `is_word_char` (line_wrapper.rs:450): the characters that must
     * stay glued to what is beside them. The script ranges are Zed's own, and
     * the point of listing them rather than asking [Char.isLetterOrDigit] is
     * that CJK must fall *outside* the set — a language written without
     * spaces can only break between its characters.
     */
    private fun isWordChar(char: Char): Boolean = when (char) {
        in 'a'..'z', in 'A'..'Z', in '0'..'9' -> true
        // Latin-1 Supplement, Latin Extended-A and -B, combining marks.
        in '\u00C0'..'\u00FF', in '\u0100'..'\u017F' -> true
        in '\u0180'..'\u024F', in '\u0300'..'\u036F' -> true
        // Cyrillic, Bengali, Latin Extended Additional (Vietnamese).
        in '\u0400'..'\u04FF', in '\u0980'..'\u09FF' -> true
        in '\u1E00'..'\u1EFF' -> true
        // `a-b`, `var_name`, `won't`, `@mention`, `3.1415`, and the trailing
        // punctuation that should stay on the word in front of it.
        '-', '_', '.', '\'', '\u2019', '\u2018', '$', '%', '@', '#' -> true
        '^', '~', ',', '=', ':', ';', '\u22EF' -> true
        // Non-breaking glue: NNBSP, NBSP, non-breaking hyphen.
        '\u202F', '\u00A0', '\u2011' -> true
        else -> false
    }
}

/**
 * One folded block of the file: rows `startRow + 1..endRow` are hidden, and
 * [startRow] — the line the block hangs off — stays visible with the "⋯"
 * chip after its text.
 *
 * Zed's fold is a range from the *end* of the first line into the block
 * (crates/editor/src/display_map.rs:2317-2426), anchored in the buffer so it
 * rides through edits. This is the same fold flattened to whole rows, which
 * is what a row-based renderer can hide; the one look this loses is the
 * closing bracket joining the chip's own line, and that is recorded where
 * [IndentFolds.rangeAt] decides the end row.
 */
internal data class FoldRange(val startRow: Int, val endRow: Int)

/**
 * Which rows a fold covers, computed from indentation alone — a port of
 * Zed's indent-based crease logic, which is what Zed itself uses whenever a
 * language server has not supplied folding ranges
 * (`crease_for_buffer_row`, crates/editor/src/display_map.rs:2317-2430).
 *
 * Everything here reads lines through a `(Int) -> String` accessor and
 * carries no state, so the whole of it runs on the host against plain
 * strings — which is where its tests live.
 */
internal object IndentFolds {

    /**
     * Zed's `LineIndent::raw_len`: the *count* of leading tab and space
     * characters, not their expanded width (crates/text/src/text.rs:706-708).
     * Zed compares raw lengths when it walks a block's rows, so we do too.
     */
    fun indentOf(text: String): Int {
        for (i in text.indices) {
            if (text[i] != ' ' && text[i] != '\t') return i
        }
        return text.length
    }

    /** Zed's `is_line_blank`: nothing on the row but whitespace. */
    fun isBlank(text: String): Boolean {
        for (char in text) {
            if (char != ' ' && char != '\t') return false
        }
        return true
    }

    /**
     * Whether [row] hangs a deeper-indented block under itself — Zed's
     * `starts_indent` (crates/editor/src/display_map.rs:2268-2292): the
     * first non-blank row after it is indented further. The last row of the
     * file never does, and a blank row never does.
     *
     * [scanLimit] bounds the walk over blank rows; Zed's walk is unbounded,
     * but the gutter asks this once per visible row per frame and a file
     * that is ten thousand blank lines should not make it pay for them.
     * Callers that decide a *fold* rather than a chevron pass no limit.
     */
    fun startsIndent(
        rowCount: Int,
        row: Int,
        line: (Int) -> String,
        scanLimit: Int = Int.MAX_VALUE,
    ): Boolean {
        if (row < 0 || row >= rowCount - 1) return false
        val text = line(row)
        if (isBlank(text)) return false
        val indent = indentOf(text)
        val last = if (scanLimit >= rowCount - row) rowCount - 1 else row + scanLimit
        for (next in row + 1..last) {
            val nextText = line(next)
            if (isBlank(nextText)) continue
            return indentOf(nextText) > indent
        }
        return false
    }

    /**
     * The fold hanging off [row], or null when it starts none.
     *
     * The shape is Zed's, step for step (display_map.rs:2355-2426): walk
     * forward to the first non-blank row indented at or shallower than
     * [row] — the closing row — then end the fold in front of it. When the
     * closing row opens with one of the language's closing brackets
     * (`closing_bracket_indent_len`, display_map.rs:2294-2314) the blank
     * rows before it fold away with the block; otherwise they stay outside
     * it (`last_non_blank_row`, display_map.rs:2400-2417).
     *
     * Two of Zed's refinements need the syntax tree and are deliberately
     * not here: unindented rows *inside* a multi-line string or comment do
     * not close a fold for Zed (display_map.rs:2380-2393), and Zed joins the
     * closing bracket onto the chip's own display line rather than leaving
     * it on the next one (display_map.rs:2408-2412). Both are listed in the
     * feature's deviations.
     */
    fun rangeAt(
        rowCount: Int,
        row: Int,
        line: (Int) -> String,
        closesBlock: (String) -> Boolean,
    ): FoldRange? {
        if (!startsIndent(rowCount, row, line)) return null
        val startIndent = indentOf(line(row))
        var closingRow = -1
        for (r in row + 1 until rowCount) {
            val text = line(r)
            if (!isBlank(text) && indentOf(text) <= startIndent) {
                closingRow = r
                break
            }
        }
        val endRow = when {
            closingRow < 0 -> lastNonBlank(rowCount - 1, row, line)
            closesBlock(line(closingRow).substring(indentOf(line(closingRow)))) -> closingRow - 1
            else -> lastNonBlank(closingRow - 1, row, line)
        }
        return if (endRow > row) FoldRange(row, endRow) else null
    }

    /** Zed's `last_non_blank_row`: back up over blanks, never past [floor]. */
    private fun lastNonBlank(from: Int, floor: Int, line: (Int) -> String): Int {
        var row = from
        while (row > floor && isBlank(line(row))) row--
        return row
    }
}

/**
 * One frame's worth of display rows, as parallel arrays reused between
 * frames.
 *
 * Parallel `IntArray`s rather than a list of objects on purpose: this is
 * filled on every draw, and fifty small objects a frame is fifty small
 * objects a frame. Nothing here allocates once the arrays are big enough.
 */
internal class DisplayWindow {
    var firstDisplayRow: Int = 0
        private set
    var size: Int = 0
        private set

    private var rows = IntArray(0)
    private var segments = IntArray(0)
    private var starts = IntArray(0)
    private var ends = IntArray(0)
    private var indents = IntArray(0)

    /** The buffer row display row `firstDisplayRow + i` comes from. */
    fun bufferRow(i: Int): Int = rows[i]

    /** Which segment of that row; 0 is the one the line number sits on. */
    fun segment(i: Int): Int = segments[i]

    fun startCol(i: Int): Int = starts[i]

    /** Exclusive end, or [Int.MAX_VALUE] for "to the end of the row". */
    fun endCol(i: Int): Int = ends[i]

    /** Columns this segment is pushed right by; 0 on a row's first segment. */
    fun indentColumns(i: Int): Int = indents[i]

    /** True when this display row carries the row's line number. */
    fun isFirstSegment(i: Int): Boolean = segments[i] == 0

    /**
     * True when this display row is part of the block *above* its buffer
     * row — an expanded hunk's header or one of its deleted lines — and so
     * holds no text of the file. Its segment is negative: `-height` for the
     * header, `-1` for the block's last row.
     */
    fun isBlockRow(i: Int): Boolean = segments[i] < 0

    /**
     * Which row of its block this is, 0 for the header; -1 for a text row.
     * A block entry keeps it in the start column, which no text range reads
     * because its end column is the same number — an empty range.
     */
    fun blockRowIndex(i: Int): Int = if (segments[i] < 0) starts[i] else -1

    fun firstBufferRow(): Int = if (size == 0) 0 else rows[0]

    fun lastBufferRow(): Int = if (size == 0) -1 else rows[size - 1]

    /**
     * The window entry that draws (row, col), or -1.
     *
     * Scanned rather than indexed: a caret is asked about a handful of times
     * a frame and the window is a few dozen rows long. Callers reject rows
     * outside [firstBufferRow]..[lastBufferRow] first, which is what keeps a
     * thousand carets off this path.
     */
    fun indexOf(row: Int, col: Int): Int {
        for (i in 0 until size) {
            if (rows[i] == row && col >= starts[i] && col < ends[i]) return i
        }
        return -1
    }

    /**
     * The first entry drawing buffer row [row], or -1. Binary searched
     * because the search bar asks it once per match per frame and a file can
     * have a thousand matches on screen's worth of rows.
     */
    fun firstIndexOf(row: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (rows[mid] < row) low = mid + 1 else high = mid
        }
        // Past the row's block, if it has one: every caller walks on from
        // here reading text ranges, and a block row has none. The block sits
        // in front of the text, so skipping it is a step forward.
        while (low < size && rows[low] == row && segments[low] < 0) low++
        return if (low < size && rows[low] == row) low else -1
    }

    internal fun reset(firstDisplayRow: Int, capacity: Int) {
        this.firstDisplayRow = firstDisplayRow
        size = 0
        if (rows.size >= capacity) return
        rows = IntArray(capacity)
        segments = IntArray(capacity)
        starts = IntArray(capacity)
        ends = IntArray(capacity)
        indents = IntArray(capacity)
    }

    internal fun add(row: Int, segment: Int, start: Int, end: Int, indent: Int) {
        if (size >= rows.size) return
        rows[size] = row
        segments[size] = segment
        starts[size] = start
        ends[size] = end
        indents[size] = indent
        size++
    }
}

/**
 * What is on screen, expressed separately from what is in the file.
 *
 * Zed keeps one of these (crates/editor/src/display_map.rs) so that wrapping,
 * folding and sticky scroll can all say "display row 40" without meaning
 * "buffer row 40". Everything in this pane that used to multiply a buffer row
 * by the line height asks here first.
 *
 * ### What it costs
 *
 * With soft wrap off the map is the identity: [isIdentity] is true, every
 * query is one clamp, and not a single array is allocated. That is the
 * default — Zed's `soft_wrap` is `"none"` — and it is why the map costs the
 * existing editor nothing.
 *
 * With wrap on, the file is cut into fixed blocks of `BLOCK_ROWS` rows. A
 * block is measured — one batched read of its text, then [SoftWrap.wrap] over
 * each row — the first time a query lands in it, and not again until an edit
 * invalidates it. Blocks nobody has visited are *estimated* at one display
 * row per buffer row it does not hide — the fold stage's answer is exact and
 * costs no text at all — so the document has a height from the first frame;
 * visiting one replaces the estimate with the truth, which can only make the
 * document taller and therefore never moves anything already above the
 * viewport.
 *
 * A Fenwick tree over the blocks answers both directions in O(log blocks) —
 * eleven steps for a 100k-line file — and a measured block carries a running
 * count of its own rows, so the second half of a lookup is a binary search
 * over at most `BLOCK_ROWS` entries.
 */
internal class DisplayMap(
    private val rowCount: () -> Int,
    /** Text of buffer rows [first, last), served from the pane's line window. */
    private val textOfRows: (first: Int, last: Int) -> List<String>,
) {
    var wrapColumns: Int = 0
        private set
    var tabSize: Int = 4
        private set

    /** True while every buffer row is exactly one display row. */
    val isIdentity: Boolean get() = wrapColumns <= 0 && hiddenStarts.isEmpty() && blockRows.isEmpty()

    // ---- Blocks ----------------------------------------------------------
    //
    // The third client: rows on screen that are not rows of the file at all.
    // An expanded diff hunk shows the lines the commit had — struck through,
    // read-only — *above* the rows that replaced them, plus a header row with
    // its buttons. Zed splices these in as `BlockMap` blocks, a coordinate
    // space above the wrap map (display_map/block_map.rs); here they are a
    // height hung off the buffer row they sit above, fed into the same block
    // index as the wrap and the fold, so every query composes the three.

    /** Sorted buffer rows with a block above them, and each block's height. */
    private var blockRows = IntArray(0)
    private var blockHeights = IntArray(0)

    val hasBlocks: Boolean get() = blockRows.isNotEmpty()

    /**
     * Install the blocks: `(buffer row, display rows above it)`, in any
     * order. Everything measured is thrown away, as for a fold — a block is
     * a command's doing, not a keystroke's.
     */
    fun setBlocks(blocks: List<Pair<Int, Int>>) {
        val sorted = blocks.filter { it.second > 0 }.sortedBy { it.first }
        val rows = IntArray(sorted.size) { sorted[it].first }
        val heights = IntArray(sorted.size) { sorted[it].second }
        if (rows.contentEquals(blockRows) && heights.contentEquals(blockHeights)) return
        blockRows = rows
        blockHeights = heights
        this.rows = -1
        forgetWraps()
        if (!isIdentity) ensureShape()
    }

    /** Display rows sitting above buffer row [row]; 0 for most rows. */
    fun blockHeightAbove(row: Int): Int {
        var low = 0
        var high = blockRows.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (blockRows[mid] < row) low = mid + 1 else high = mid
        }
        return if (low < blockRows.size && blockRows[low] == row) blockHeights[low] else 0
    }

    /** Total block height over rows [from, to). */
    private fun blockHeightIn(from: Int, to: Int): Int {
        if (blockRows.isEmpty()) return 0
        var sum = 0
        var i = 0
        while (i < blockRows.size && blockRows[i] < from) i++
        while (i < blockRows.size && blockRows[i] < to) {
            if (!isRowHidden(blockRows[i])) sum += blockHeights[i]
            i++
        }
        return sum
    }

    /**
     * The display row buffer row [row]'s block starts on — equal to
     * [displayRowOf] for a row with no block above it.
     */
    fun blockStartRowOf(row: Int): Int = displayRowOf(row) - blockHeightAbove(row)

    /**
     * Whether display row [displayRow] is a block row rather than a row of
     * text — what the caret's vertical motion steps over.
     */
    fun isBlockDisplayRow(displayRow: Int): Boolean {
        if (blockRows.isEmpty()) return false
        val row = bufferRowOf(displayRow)
        return displayRow < displayRowOf(row)
    }

    /**
     * Set the wrap width (0 turns wrapping off) and the tab width. Everything
     * measured is thrown away when either changes, because both change every
     * break in the file.
     */
    fun configure(wrapColumns: Int, tabSize: Int) {
        if (wrapColumns == this.wrapColumns && tabSize == this.tabSize) return
        this.wrapColumns = wrapColumns
        this.tabSize = tabSize
        rows = -1
        forgetWraps()
        if (!isIdentity) ensureShape()
    }

    // ---- Folds -----------------------------------------------------------
    //
    // The second client this map was built for: where soft wrap turns one
    // buffer row into several display rows, a fold turns several into none.
    // Both are the same question — how tall is this row on screen — so the
    // fold stage is a height of zero fed into the very same block index the
    // wrapper fills, and every query (displayRowOf, bufferRowOf, fillWindow)
    // composes the two for free. Zed layers it the same way: FoldMap and
    // WrapMap are successive coordinate spaces of one DisplayMap
    // (crates/editor/src/display_map.rs:24-42).

    /**
     * The hidden rows, as sorted disjoint inclusive ranges that never touch —
     * the *merged* interiors of the folds, not the folds themselves. The
     * editor keeps the fold list (nested folds and all, the way Zed's fold
     * map holds overlapping creases); this map only needs to know which rows
     * are not on screen.
     */
    private var hiddenStarts = IntArray(0)
    private var hiddenEnds = IntArray(0)

    val hasFolds: Boolean get() = hiddenStarts.isNotEmpty()

    /**
     * Replace the hidden-row set. Everything measured is thrown away: every
     * block's height depended on which of its rows were hidden. Folding is a
     * command, not a keystroke, so the re-measure is paid where the user can
     * see why.
     */
    fun setFoldedRows(ranges: List<IntRange>) {
        val starts = IntArray(ranges.size) { ranges[it].first }
        val ends = IntArray(ranges.size) { ranges[it].last }
        if (starts.contentEquals(hiddenStarts) && ends.contentEquals(hiddenEnds)) return
        hiddenStarts = starts
        hiddenEnds = ends
        rows = -1
        if (!isIdentity) ensureShape()
    }

    /** Whether buffer row [row] is inside a fold and off the screen. */
    fun isRowHidden(row: Int): Boolean {
        val i = hiddenRangeBefore(row)
        return i >= 0 && row <= hiddenEnds[i]
    }

    /**
     * The nearest visible row at or before [row]. Always exists for a row
     * that is in the buffer: a fold hides rows *after* its own first line,
     * so row 0 is never hidden, and the merged ranges never touch — the row
     * in front of one is visible by construction.
     */
    fun prevVisibleRow(row: Int): Int {
        val at = row.coerceAtLeast(0)
        val i = hiddenRangeBefore(at)
        return if (i >= 0 && at <= hiddenEnds[i]) hiddenStarts[i] - 1 else at
    }

    /**
     * The nearest visible row at or after [row] — which can be past the end
     * of the buffer when a fold runs to it, so callers check the answer
     * against their row count.
     */
    fun nextVisibleRow(row: Int): Int {
        val at = row.coerceAtLeast(0)
        val i = hiddenRangeBefore(at)
        return if (i >= 0 && at <= hiddenEnds[i]) hiddenEnds[i] + 1 else at
    }

    /** Index of the last hidden range starting at or before [row], or -1. */
    private fun hiddenRangeBefore(row: Int): Int {
        var low = 0
        var high = hiddenStarts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (hiddenStarts[mid] <= row) low = mid + 1 else high = mid
        }
        return low - 1
    }

    // ---- Remembered breaks -----------------------------------------------

    /**
     * The last few long rows' breaks, keyed by the text they were computed
     * from.
     *
     * Wrapping a row costs a scan of the whole row, and one frame asks for
     * the same row's breaks several times over: the draw pass for the
     * segments on screen, the caret for the segment it sits in, the
     * selection handles, `positionAt` for a tap, and an arrow key three more
     * times. On an ordinary row that is a few hundred characters of
     * arithmetic and not worth remembering; on the single 50k-character line
     * a minified file is, it is the frame budget.
     *
     * Keyed by the text rather than by row number because a row's index
     * moves under an edit while its breaks depend on nothing but its own
     * text, this width and this tab size — so a stale entry cannot be
     * wrong, only unused.
     */
    private val wrapKeys = arrayOfNulls<String>(WRAP_CACHE_SLOTS)
    private val wrapValues = arrayOfNulls<WrappedLine>(WRAP_CACHE_SLOTS)
    private var wrapNext = 0

    /**
     * Rows this map has scanned to find their breaks, for the tests that
     * hold a frame to the rows on screen rather than the characters behind
     * them.
     */
    internal var wrapScans = 0
        private set

    private fun forgetWraps() {
        wrapKeys.fill(null)
        wrapValues.fill(null)
        wrapNext = 0
    }

    private fun rememberedWrap(text: String): WrappedLine? {
        for (i in wrapKeys.indices) {
            if (wrapKeys[i] == text) return wrapValues[i]
        }
        return null
    }

    private fun rememberWrap(text: String, wrapped: WrappedLine) {
        wrapKeys[wrapNext] = text
        wrapValues[wrapNext] = wrapped
        wrapNext = (wrapNext + 1) % WRAP_CACHE_SLOTS
    }

    // ---- Block index -----------------------------------------------------

    private var rows = -1
    private var blockCount = 0

    /** Per block, a running display-row count of its rows; null = unmeasured. */
    private var measured: Array<IntArray?> = emptyArray()

    /** Display rows per block, and a Fenwick tree over that. */
    private var blockValue = IntArray(0)
    private var tree = IntArray(1)
    private var total = 0

    private companion object {
        /**
         * Rows per block. Small enough that measuring one is a few dozen
         * microseconds and that the pane's own line window usually already
         * holds it; large enough that a 100k-line file is 1563 blocks rather
         * than a tree over a hundred thousand rows.
         */
        const val BLOCK_ROWS = 64

        /**
         * Rows whose breaks are remembered at once. A screenful of wrapped
         * rows is more than this, but the rows worth remembering are the
         * long ones and there are never many of those on screen at a time.
         */
        const val WRAP_CACHE_SLOTS = 8

        /**
         * Shortest row worth remembering. A row that wraps into two or three
         * is cheaper to scan again than to keep, and keeping it would evict
         * the one row on screen whose scan actually costs something.
         */
        const val WRAP_CACHE_MIN_LENGTH = 1024
    }

    private fun rowsIn(block: Int): Int = min(BLOCK_ROWS, rows - block * BLOCK_ROWS)

    /**
     * What [block] is worth before anyone has measured it: one display row
     * per row of it a fold does not hide.
     *
     * The hidden ranges are exact and already installed by the time anything
     * asks — [setFoldedRows] hands them over before it reshapes — so guessing
     * a folded block's rows are on screen would be guessing against something
     * this map already knows. It is the difference between a document that is
     * the right height the moment a block folds and one that is the right
     * height a frame later, and it is what lets [measureWindow] stop at the
     * bottom of the viewport instead of walking a fold to get there.
     *
     * With nothing folded this is [rowsIn] and the walk below never runs,
     * which is the shape every unfolded pane is in.
     */
    private fun estimateOf(block: Int): Int {
        val count = rowsIn(block)
        val start = block * BLOCK_ROWS
        val end = start + count
        // A block's rows are exact too — they are installed whole, like the
        // folds — so the estimate carries them from the first frame.
        val blocks = blockHeightIn(start, end)
        if (hiddenStarts.isEmpty()) return count + blocks
        var hidden = 0
        var i = hiddenRangeBefore(start).coerceAtLeast(0)
        while (i < hiddenStarts.size && hiddenStarts[i] < end) {
            val from = max(hiddenStarts[i], start)
            val to = min(hiddenEnds[i], end - 1)
            if (to >= from) hidden += to - from + 1
            i++
        }
        return count - hidden + blocks
    }

    private fun ensureShape() {
        val count = rowCount().coerceAtLeast(1)
        if (count == rows) return
        rows = count
        blockCount = (count + BLOCK_ROWS - 1) / BLOCK_ROWS
        measured = arrayOfNulls(blockCount)
        blockValue = IntArray(blockCount) { estimateOf(it) }
        rebuildTree()
    }

    private fun rebuildTree() {
        val n = blockCount
        tree = IntArray(n + 1)
        total = 0
        for (i in 0 until n) {
            tree[i + 1] = blockValue[i]
            total += blockValue[i]
        }
        for (i in 1..n) {
            val parent = i + (i and -i)
            if (parent <= n) tree[parent] += tree[i]
        }
    }

    private fun setBlock(block: Int, value: Int) {
        val delta = value - blockValue[block]
        if (delta == 0) return
        blockValue[block] = value
        total += delta
        var i = block + 1
        while (i <= blockCount) {
            tree[i] += delta
            i += i and -i
        }
    }

    /** Display rows in front of [block]. */
    private fun prefixOf(block: Int): Int {
        var i = block
        var sum = 0
        while (i > 0) {
            sum += tree[i]
            i -= i and -i
        }
        return sum
    }

    /** The block display row [at] falls in. */
    private fun blockAt(at: Int): Int {
        var index = 0
        var bit = Integer.highestOneBit(blockCount.coerceAtLeast(1))
        var remaining = at
        while (bit > 0) {
            val next = index + bit
            if (next <= blockCount && tree[next] <= remaining) {
                index = next
                remaining -= tree[next]
            }
            bit = bit shr 1
        }
        return index.coerceIn(0, blockCount - 1)
    }

    /**
     * Measure [block] if it has not been, and return the running display-row
     * count of its rows.
     *
     * Measuring only ever makes a block taller — a visible row is at least
     * one display row and a hidden one is counted at none by [estimateOf]
     * already — so anything above the viewport stays put. The walkers over
     * this index still re-resolve rather than assume ([bufferRowOf]'s loop,
     * [measureWindow]): the estimate is a floor, not a promise.
     *
     * A block a fold hides entirely is worth nothing and is not read at all,
     * which is what keeps a folded file's blocks off the bridge.
     */
    private fun blockPrefix(block: Int): IntArray {
        measured[block]?.let { return it }
        val start = block * BLOCK_ROWS
        val count = rowsIn(block)
        val prefix = IntArray(count + 1)
        val texts = if (estimateOf(block) == 0) emptyList() else textOfRows(start, start + count)
        var sum = 0
        for (i in 0 until count) {
            prefix[i] = sum
            // A folded row is on no display row at all; its height is the
            // fold stage's whole contribution to this index. A visible row
            // is worth its block above it and then its own segments — the
            // prefix names where the block starts, and [displayRowOf] adds
            // the block back to find the text.
            if (!isRowHidden(start + i)) {
                sum += blockHeightAbove(start + i) + segmentCountOf(texts.getOrElse(i) { "" })
            }
        }
        prefix[count] = sum
        measured[block] = prefix
        setBlock(block, sum)
        return prefix
    }

    private fun wrap(text: String, indentColumns: Int): Int =
        SoftWrap.wrap(text, wrapColumns, tabSize, indentColumns)

    // ---- Invalidation ----------------------------------------------------

    /**
     * Forget what was measured for buffer rows [fromRow]..[toRow].
     *
     * When the row count changed, everything after [fromRow] has shifted
     * under its block and goes with it — that is the case a newline or a
     * deleted line lands in. When it did not, only the rows the edit actually
     * rewrote are dropped, which is what keeps typing in a 100k-line file
     * from re-measuring the file on every keystroke.
     */
    fun invalidate(fromRow: Int, toRow: Int) {
        if (isIdentity) return
        val count = rowCount().coerceAtLeast(1)
        if (count != rows) {
            reshapeKeeping(count, fromRow)
            return
        }
        val first = fromRow.coerceAtLeast(0) / BLOCK_ROWS
        val last = toRow.coerceIn(0, rows - 1) / BLOCK_ROWS
        if (first > last) return
        var changed = false
        for (block in first..last) {
            if (measured[block] == null) continue
            measured[block] = null
            blockValue[block] = estimateOf(block)
            changed = true
        }
        // One O(blocks) pass rather than a Fenwick update per block: it is a
        // few thousand additions even for a huge file, and it does not care
        // how many blocks the edit reached.
        if (changed) rebuildTree()
    }

    /** Everything is suspect — a reload, an undo, a new wrap width. */
    fun invalidateAll() {
        if (isIdentity) return
        rows = -1
        ensureShape()
    }

    /**
     * Re-block for a new row count, keeping what was measured in front of
     * [fromRow]: that text did not change, and neither did its rows' indices.
     */
    private fun reshapeKeeping(count: Int, fromRow: Int) {
        val keptBlocks = fromRow.coerceAtLeast(0) / BLOCK_ROWS
        val old = measured
        rows = count
        blockCount = (count + BLOCK_ROWS - 1) / BLOCK_ROWS
        measured = arrayOfNulls(blockCount)
        blockValue = IntArray(blockCount) { estimateOf(it) }
        for (block in 0 until min(keptBlocks, blockCount)) {
            val prefix = old.getOrNull(block) ?: continue
            // A block only keeps its measurement if it still holds the same
            // rows; the last block of a file that just got shorter does not.
            if (prefix.size - 1 != rowsIn(block)) continue
            measured[block] = prefix
            blockValue[block] = prefix[prefix.size - 1]
        }
        rebuildTree()
    }

    // ---- Queries ---------------------------------------------------------

    val displayRowCount: Int
        get() {
            if (isIdentity) return rowCount().coerceAtLeast(1)
            ensureShape()
            return total
        }

    /** How many display rows a row holding [text] takes at this width. */
    fun segmentCountOf(text: String): Int {
        if (isIdentity) return 1
        // A long row's breaks are worth keeping, and counting them is the
        // same scan; a short one counts without allocating anything.
        if (text.length >= WRAP_CACHE_MIN_LENGTH) return wrapOf(text).segmentCount
        wrapScans++
        return wrap(text, SoftWrap.indentColumns(text, tabSize))
    }

    /** [text]'s segments; [WrappedLine.FITS] when it takes a single row. */
    fun wrapOf(text: String): WrappedLine {
        if (isIdentity || text.isEmpty()) return WrappedLine.FITS
        if (text.length >= WRAP_CACHE_MIN_LENGTH) {
            rememberedWrap(text)?.let { return it }
        }
        wrapScans++
        val wrapped = SoftWrap.of(text, wrapColumns, tabSize)
        if (text.length >= WRAP_CACHE_MIN_LENGTH && wrapped.wraps) rememberWrap(text, wrapped)
        return wrapped
    }

    /**
     * The display row buffer row [row]'s *text* starts on — past any block
     * above it; [blockStartRowOf] is the row the block itself starts on.
     */
    fun displayRowOf(row: Int): Int {
        if (isIdentity) return row.coerceIn(0, rowCount() - 1)
        ensureShape()
        val clamped = row.coerceIn(0, rows - 1)
        val block = clamped / BLOCK_ROWS
        return prefixOf(block) + blockPrefix(block)[clamped - block * BLOCK_ROWS] +
            blockHeightAbove(clamped)
    }

    /**
     * The buffer row display row [displayRow] comes from.
     *
     * Loops because the document may be taller than it currently claims:
     * clamping to an estimate that measuring is about to raise would answer
     * for a row that is not the one asked about. Each turn either resolves or
     * measures a block further on, so it stops after one or two.
     */
    fun bufferRowOf(displayRow: Int): Int {
        if (isIdentity) return displayRow.coerceIn(0, rowCount() - 1)
        ensureShape()
        val want = displayRow.coerceAtLeast(0)
        var block: Int
        var prefix: IntArray
        var within: Int
        var turns = 0
        do {
            val at = want.coerceAtMost((total - 1).coerceAtLeast(0))
            block = blockAt(at)
            val settled = measured[block] != null
            prefix = blockPrefix(block)
            within = at - prefixOf(block)
            if (settled) break
        } while (turns++ < blockCount)
        var low = 0
        var high = prefix.size - 2
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (prefix[mid] <= within) low = mid else high = mid - 1
        }
        // The search takes the *last* row whose prefix fits, and a hidden
        // row's prefix equals the prefix of the visible row that follows it
        // — so a run of folded rows always loses the tie to the visible row
        // after them, and the answer is never a row that is not on screen.
        return (block * BLOCK_ROWS + low).coerceIn(0, rows - 1)
    }

    /** The buffer rows display rows [first, last) are drawn from, inclusive. */
    fun bufferRowRange(first: Int, last: Int): IntRange {
        val firstRow = bufferRowOf(first)
        val lastRow = bufferRowOf((last - 1).coerceAtLeast(first))
        return firstRow..lastRow.coerceAtLeast(firstRow)
    }

    /**
     * Fill [window] with display rows [first, last). [lines] holds the text
     * of buffer rows from [firstBufferRow] on, which the caller has already
     * fetched in one read.
     *
     * Costs the display rows it fills, not the characters behind them: an
     * ordinary row's breaks are a few dozen characters of arithmetic, a long
     * row's come from [wrapOf]'s memory, and either way only the segments
     * inside the window are looked at.
     */
    fun fillWindow(
        window: DisplayWindow,
        first: Int,
        last: Int,
        firstBufferRow: Int,
        lines: List<String>,
    ) = fillWindow(window, first, last, firstBufferRow) { row ->
        lines.getOrElse(row - firstBufferRow) { "" }
    }

    /**
     * The same, reading each row's text through [textOf] rather than out of
     * one contiguous list.
     *
     * Which is what a folded pane needs: the rows a frame draws are not one
     * stretch of the file once a block is folded — the first and the last of
     * them can be a whole file apart — so the caller reads them in the runs
     * they actually form and answers here per row. Nothing in this loop is
     * allowed to depend on the distance between [firstBufferRow] and the
     * bottom of the window.
     */
    fun fillWindow(
        window: DisplayWindow,
        first: Int,
        last: Int,
        firstBufferRow: Int,
        textOf: (Int) -> String,
    ) {
        val count = (last - first).coerceAtLeast(0)
        window.reset(first, count)
        if (count == 0) return
        if (isIdentity) {
            val rowCount = rowCount()
            for (i in 0 until count) {
                val row = first + i
                if (row >= rowCount) break
                window.add(row, 0, 0, Int.MAX_VALUE, 0)
            }
            return
        }
        ensureShape()
        var display = blockStartRowOf(firstBufferRow)
        var row = firstBufferRow
        while (display < last && row < rows) {
            // Folded rows are simply not there: they cost the window nothing,
            // which is the whole fold feature from the renderer's side, and
            // they cost the loop one step per *fold* rather than one per row
            // — a window beside a ten-thousand-row fold must not walk it.
            if (isRowHidden(row)) {
                row = nextVisibleRow(row)
                continue
            }
            // The block above the row first, one entry per display row of
            // it, with a *negative* segment counting up to the text: the
            // header of a block of height h is segment -h, its last row -1.
            // Nothing that reads a segment as a text range ever sees one —
            // [DisplayWindow.isBlockRow] is the guard.
            val blockHeight = blockHeightAbove(row)
            for (j in 0 until blockHeight) {
                if (display + j >= first && display + j < last) {
                    window.add(row = row, segment = j - blockHeight, start = j, end = j, indent = 0)
                }
            }
            display += blockHeight
            val text = textOf(row)
            val wrapped = wrapOf(text)
            val segments = wrapped.segmentCount
            // Open at the segment the window starts on rather than walking
            // the row from its first one: a window forty rows into the single
            // long line a minified file is must cost forty rows of work, not
            // the line's thousand.
            var segment = (first - display).coerceAtLeast(0)
            while (segment < segments && display + segment < last) {
                window.add(
                    row = row,
                    segment = segment,
                    start = wrapped.startOf(segment),
                    end = if (segment == segments - 1) {
                        Int.MAX_VALUE
                    } else {
                        wrapped.endOf(segment, text.length)
                    },
                    indent = if (segment == 0) 0 else wrapped.indentColumns,
                )
                segment++
            }
            display += segments
            row++
        }
    }

    /**
     * Measure every block the display rows [first, last) fall in.
     *
     * Anything that decides *where* to scroll has to do this before it works
     * out where the caret is drawn. The draw pass measures these blocks as it
     * resolves the top of the viewport, measuring can only make a block
     * taller, and a block that grows above the caret pushes the caret's own
     * display row down — which is how a jump used to scroll to a row the
     * caret was no longer on.
     */
    fun measureWindow(first: Int, last: Int) {
        if (isIdentity) return
        ensureShape()
        var at = first.coerceAtLeast(0)
        while (at < last && at < total) {
            val block = blockAt(at)
            val wasMeasured = measured[block] != null
            blockPrefix(block)
            val end = prefixOf(block) + blockValue[block]
            if (end > at) {
                at = end
            } else if (wasMeasured) {
                // A measured block that still does not reach past `at` is
                // the empty-file shape; nothing further to learn.
                break
            }
            // Otherwise the block came out no taller than where `at` already
            // stands. [estimateOf] counts a folded block at nothing, so this
            // is the empty-file shape rather than a fold — but the walk is
            // still written not to assume it: each turn either advances `at`
            // or measures a block it had not, so it terminates either way.
        }
    }
}
