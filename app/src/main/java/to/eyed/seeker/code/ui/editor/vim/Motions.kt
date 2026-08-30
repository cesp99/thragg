package to.eyed.seeker.code.ui.editor.vim

import to.eyed.seeker.code.ui.editor.EditorState

/**
 * How a motion's range is taken by an operator — Vim's `:help exclusive`,
 * `:help inclusive`, `:help linewise`, which Zed keeps as `Motion::linewise`
 * and `Motion::inclusive` (crates/vim/src/motion.rs).
 */
internal enum class MotionKind { Exclusive, Inclusive, Linewise }

/**
 * Where a motion lands and how an operator takes it. [goalCol] is the
 * column `j`/`k` should keep aiming for afterwards: `$` sets it to "the end
 * of whatever line", which is why `$jj` stays at the ends
 * (motion.rs `SelectionGoal::EndOfLine`). A [jump] is one of the motions
 * `:help jump-motions` lists — `G`, `%`, `{`, a search, a mark — which set
 * the `''` mark to where they left from.
 */
internal class MotionTarget(
    val pos: Pos,
    val kind: MotionKind,
    val goalCol: Int? = null,
    val jump: Boolean = false,
)

/** `j`/`k` aim for this column when `$` was the last horizontal motion. */
internal const val GOAL_END_OF_LINE = Int.MAX_VALUE

/** How many rows a whole-buffer scan (`%`, the paragraph motions) may cross. */
private const val SCAN_LIMIT_ROWS = 20_000

/**
 * Vim's motions as pure functions of the buffer: a start position and a
 * count in, a target out. None of them moves the caret — the handler does
 * that, or hands the target to an operator — which is what lets a motion be
 * tested as arithmetic and reused for both.
 *
 * Behaviour follows Zed's `crates/vim/src/motion.rs`, which follows Vim; the
 * comments cite the Vim rule where one is being followed.
 */
internal object Motions {

    // ---- Words -------------------------------------------------------------

    /**
     * `w` / `W`: to the start of the next word, [count] times. Crossing into
     * a new row lands on its first word; an empty row is a word of its own
     * (`:help w`). Running out of buffer leaves the position at the end of
     * the last row, which the caller clips for a caret and keeps for an
     * operator.
     */
    fun nextWordStart(editor: EditorState, from: Pos, count: Int, big: Boolean): Pos {
        var pos = from
        repeat(count) { pos = stepWordStart(editor, pos, big) }
        return pos
    }

    private fun stepWordStart(editor: EditorState, from: Pos, big: Boolean): Pos {
        var row = from.row
        var col = from.col
        var text = editor.line(row)
        if (col < text.length) {
            val kind = charClass(text[col], big)
            if (kind != 0) {
                while (col < text.length && charClass(text[col], big) == kind) col++
            }
        }
        while (true) {
            while (col < text.length && text[col].isWhitespace()) col++
            if (col < text.length) return Pos(row, col)
            if (row >= editor.lastRow) return Pos(row, text.length)
            row++
            col = 0
            text = editor.line(row)
            if (text.isEmpty()) return Pos(row, 0)
        }
    }

    /**
     * `w` under an operator: "when the last word moved over is at the end
     * of a line, the end of that word becomes the end of the operated text,
     * not the first word in the next line" (`:help w`), so `dw` on a line's
     * last word never pulls the next line up.
     */
    fun nextWordStartForOperator(editor: EditorState, from: Pos, count: Int, big: Boolean): Pos {
        var pos = from
        repeat(count) { step ->
            val next = stepWordStart(editor, pos, big)
            if (next.row > pos.row && step == count - 1) {
                return editor.endOfRow(pos.row)
            }
            pos = next
        }
        return pos
    }

    /** `e` / `E`: to the end of the word, [count] times. Inclusive. */
    fun nextWordEnd(editor: EditorState, from: Pos, count: Int, big: Boolean): Pos {
        var pos = from
        repeat(count) { pos = stepWordEnd(editor, pos, big) }
        return pos
    }

    private fun stepWordEnd(editor: EditorState, from: Pos, big: Boolean): Pos {
        var row = from.row
        var col = from.col + 1
        var text = editor.line(row)
        while (true) {
            while (col < text.length && text[col].isWhitespace()) col++
            if (col < text.length) break
            if (row >= editor.lastRow) return Pos(row, (text.length - 1).coerceAtLeast(0))
            row++
            col = 0
            text = editor.line(row)
        }
        val kind = charClass(text[col], big)
        while (col + 1 < text.length && charClass(text[col + 1], big) == kind) col++
        return Pos(row, col)
    }

    /** `b` / `B`: back to the start of the word, [count] times. Exclusive. */
    fun previousWordStart(editor: EditorState, from: Pos, count: Int, big: Boolean): Pos {
        var pos = from
        repeat(count) { pos = stepWordBack(editor, pos, big) }
        return pos
    }

    private fun stepWordBack(editor: EditorState, from: Pos, big: Boolean): Pos {
        var row = from.row
        var col = from.col
        var text = editor.line(row)
        while (true) {
            // One step back, over the newline when at the start of a row. An
            // empty row is a word (`:help b`), so it is a stop.
            if (col > 0) {
                col--
            } else {
                if (row == 0) return Pos(0, 0)
                row--
                text = editor.line(row)
                if (text.isEmpty()) return Pos(row, 0)
                col = text.length - 1
            }
            if (col < text.length && !text[col].isWhitespace()) break
            // Whitespace: keep going, which may cross another newline.
        }
        val kind = charClass(text[col], big)
        while (col > 0 && charClass(text[col - 1], big) == kind) col--
        return Pos(row, col)
    }

    /** `ge` / `gE`: back to the end of the previous word, [count] times. Inclusive. */
    fun previousWordEnd(editor: EditorState, from: Pos, count: Int, big: Boolean): Pos {
        var pos = from
        repeat(count) { pos = stepWordEndBack(editor, pos, big) }
        return pos
    }

    private fun stepWordEndBack(editor: EditorState, from: Pos, big: Boolean): Pos {
        var row = from.row
        var col = from.col
        var text = editor.line(row)
        // Out of the word the cursor is in, then back over whitespace, which
        // may cross rows; an empty row is a word (`:help ge`).
        if (col < text.length) {
            val kind = charClass(text[col], big)
            if (kind != 0) {
                while (col >= 0 && charClass(text[col], big) == kind) col--
            }
        }
        while (true) {
            while (col >= 0 && col < text.length && text[col].isWhitespace()) col--
            if (col >= 0 && col < text.length) return Pos(row, col)
            if (row == 0) return Pos(0, 0)
            row--
            text = editor.line(row)
            if (text.isEmpty()) return Pos(row, 0)
            col = text.length - 1
        }
    }

    // ---- Within the line ----------------------------------------------------

    /**
     * `f`/`F`/`t`/`T` and their `;`/`,` repeats. [till] stops one short of
     * the character; [repeat] is a `;` after a `t`, which Vim (with `cpo`'s
     * `;` flag off, its default) starts one further along so it does not
     * find the character it is already beside. Null when the character is
     * not on the line, which cancels the whole command.
     */
    fun findInLine(
        editor: EditorState,
        from: Pos,
        target: Char,
        count: Int,
        forward: Boolean,
        till: Boolean,
        repeat: Boolean = false,
    ): Pos? {
        val text = editor.line(from.row)
        var col = from.col
        repeat(count) { step ->
            val skip = if (till && repeat && step == 0) 1 else 0
            col = if (forward) {
                val found = text.indexOf(target, col + 1 + skip)
                if (found < 0) return null
                if (till) found - 1 else found
            } else {
                val start = col - 1 - skip
                if (start < 0) return null
                val found = text.lastIndexOf(target, start)
                if (found < 0) return null
                if (till) found + 1 else found
            }
        }
        return Pos(from.row, col)
    }

    // ---- Lines and the buffer ---------------------------------------------

    /**
     * `}` / `{`: to the next / previous blank row after a run of text, or the
     * end / start of the buffer (`:help }`). Capped, like every scan that
     * can cross the whole file.
     */
    fun paragraph(editor: EditorState, from: Pos, count: Int, forward: Boolean): Pos {
        var row = from.row
        repeat(count) {
            var walked = 0
            if (forward) {
                while (row < editor.lastRow && editor.isBlankRow(row) && walked++ < SCAN_LIMIT_ROWS) row++
                while (row < editor.lastRow && !editor.isBlankRow(row) && walked++ < SCAN_LIMIT_ROWS) row++
                if (row >= editor.lastRow && !editor.isBlankRow(row)) return editor.endOfRow(editor.lastRow)
            } else {
                while (row > 0 && editor.isBlankRow(row) && walked++ < SCAN_LIMIT_ROWS) row--
                while (row > 0 && !editor.isBlankRow(row) && walked++ < SCAN_LIMIT_ROWS) row--
                if (row <= 0 && !editor.isBlankRow(0)) return Pos(0, 0)
            }
        }
        return Pos(row, 0)
    }

    /**
     * `%`: the bracket matching the one under the cursor — or the first one
     * after it on the line (`:help %`). Nesting is counted by character,
     * which is what Vim does; Zed asks the syntax tree and so pairs `|` in
     * Rust closures, a nicety this port does without.
     */
    fun matchingBracket(editor: EditorState, from: Pos): Pos? {
        val text = editor.line(from.row)
        var col = from.col
        while (col < text.length && text[col] !in OPENERS && text[col] !in CLOSERS) col++
        if (col >= text.length) return null
        val c = text[col]
        val forward = c in OPENERS
        val open = if (forward) c else OPENERS[CLOSERS.indexOf(c)]
        val close = if (forward) CLOSERS[OPENERS.indexOf(c)] else c
        return if (forward) scanForward(editor, Pos(from.row, col), open, close) else scanBackward(editor, Pos(from.row, col), open, close)
    }

    /**
     * The closer matching an opener at [start], counting nesting forward —
     * or, with [startsInside], the unmatched closer after a [start] that
     * is neither bracket (`])`, `]}`).
     */
    internal fun scanForward(
        editor: EditorState,
        start: Pos,
        open: Char,
        close: Char,
        startsInside: Boolean = false,
    ): Pos? {
        var depth = if (startsInside) 1 else 0
        var row = start.row
        var col = start.col
        var text = editor.line(row)
        var rows = 0
        while (true) {
            while (col < text.length) {
                val ch = text[col]
                if (ch == open) depth++ else if (ch == close && --depth == 0) return Pos(row, col)
                col++
            }
            if (row >= editor.lastRow || ++rows > SCAN_LIMIT_ROWS) return null
            row++
            col = 0
            text = editor.line(row)
        }
    }

    /**
     * The opener matching a closer at [start], counting nesting backward —
     * or, with [startsInside], the unmatched opener before a [start] that
     * is neither bracket, which is how `i(` finds its pair from the middle.
     */
    internal fun scanBackward(
        editor: EditorState,
        start: Pos,
        open: Char,
        close: Char,
        startsInside: Boolean = false,
    ): Pos? {
        var depth = if (startsInside) 1 else 0
        var row = start.row
        var col = start.col
        var text = editor.line(row)
        var rows = 0
        while (true) {
            while (col >= 0) {
                val ch = text.getOrNull(col)
                if (ch == close) depth++ else if (ch == open && --depth == 0) return Pos(row, col)
                col--
            }
            if (row == 0 || ++rows > SCAN_LIMIT_ROWS) return null
            row--
            text = editor.line(row)
            col = text.length - 1
        }
    }

    /**
     * `H` / `M` / `L`: rows of the viewport, counted from its top or bottom
     * (`:help H`). Display rows, so a wrapped pane counts what is on screen.
     */
    fun windowRow(editor: EditorState, which: Char, count: Int): Int {
        val first = editor.firstDisplayRow()
        val last = (editor.lastDisplayRow(first) - 1).coerceAtLeast(first)
        val display = when (which) {
            'H' -> (first + count - 1).coerceAtMost(last)
            'L' -> (last - count + 1).coerceAtLeast(first)
            else -> (first + last) / 2
        }
        return editor.pointAtDisplayRow(display, 0).first.coerceIn(0, editor.lastRow)
    }

    private const val OPENERS = "([{"
    private const val CLOSERS = ")]}"
}
