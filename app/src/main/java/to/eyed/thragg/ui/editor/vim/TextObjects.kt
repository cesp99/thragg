package to.eyed.thragg.ui.editor.vim

import to.eyed.thragg.ui.editor.EditorState

/**
 * A text object's extent: [start] up to but not including [end], or the
 * rows [start].row..[end].row when [linewise].
 */
internal class ObjectRange(val start: Pos, val end: Pos, val linewise: Boolean)

/** How many rows either side of the cursor a tag object may look. */
private const val TAG_WINDOW_ROWS = 300

/**
 * Vim's text objects — Zed's `crates/vim/src/object.rs`, the character
 * based ones. What each one selects follows `:help text-objects`; the
 * syntax-tree objects (`af`, `ac`, `ia`) need the engine's tree and are not
 * here.
 */
internal object TextObjects {

    /** `iw`/`aw`/`iW`/`aW`. Null on an empty row, where there is no word. */
    fun word(editor: EditorState, at: Pos, around: Boolean, big: Boolean): ObjectRange? {
        val text = editor.line(at.row)
        if (text.isEmpty()) return null
        val col = at.col.coerceIn(0, text.length - 1)
        val kind = charClass(text[col], big)
        var start = col
        while (start > 0 && charClass(text[start - 1], big) == kind) start--
        var end = col + 1
        while (end < text.length && charClass(text[end], big) == kind) end++
        if (around) {
            // `aw` takes the trailing whitespace, or the leading whitespace
            // when there is none after (`:help aw`). On whitespace itself it
            // takes the word that follows.
            if (kind == 0) {
                val wordKind = if (end < text.length) charClass(text[end], big) else -1
                while (end < text.length && charClass(text[end], big) == wordKind) end++
            } else {
                var trailing = end
                while (trailing < text.length && text[trailing].isWhitespace()) trailing++
                if (trailing > end) {
                    end = trailing
                } else {
                    while (start > 0 && text[start - 1].isWhitespace()) start--
                }
            }
        }
        return ObjectRange(Pos(at.row, start), Pos(at.row, end), linewise = false)
    }

    /**
     * `i(` / `a(` and the other bracket pairs: the innermost pair around the
     * cursor, counting nesting across lines. A cursor on either bracket
     * counts as inside. An inner block whose brackets each sit on a line of
     * their own is taken by lines (`:help i{`: "When the inner block starts
     * with a line break… becomes linewise").
     */
    fun bracket(editor: EditorState, at: Pos, open: Char, close: Char, around: Boolean): ObjectRange? {
        val text = editor.line(at.row)
        val here = text.getOrNull(at.col)
        val openPos = when (here) {
            open -> at
            close -> Motions.scanBackward(editor, at, open, close)
            // Scanning back from one before the cursor with a depth that
            // counts the cursor's own row: start from the cursor as if it
            // were a closer.
            else -> Motions.scanBackward(editor, at, open, close, startsInside = true)
        } ?: return null
        val closePos = Motions.scanForward(editor, openPos, open, close) ?: return null
        if (around) {
            return ObjectRange(openPos, Pos(closePos.row, closePos.col + 1), linewise = false)
        }
        val innerStart = Pos(openPos.row, openPos.col + 1)
        val openerEndsRow = editor.line(openPos.row).drop(openPos.col + 1).isBlank()
        val closerStartsRow = editor.line(closePos.row).take(closePos.col).isBlank()
        if (openerEndsRow && closerStartsRow && closePos.row > openPos.row + 1) {
            return ObjectRange(Pos(openPos.row + 1, 0), Pos(closePos.row - 1, 0), linewise = true)
        }
        return ObjectRange(innerStart, closePos, linewise = false)
    }

    /**
     * `i"` / `a"` and the other quotes, on the cursor's row: quotes pair up
     * from the start of the line, the cursor's pair is the one it is in or
     * on, and with none around it the first pair after it is taken
     * (`:help i"`). `a"` takes trailing whitespace, or leading if none.
     */
    fun quote(editor: EditorState, at: Pos, quote: Char, around: Boolean): ObjectRange? {
        val text = editor.line(at.row)
        val quotes = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '\\') {
                i += 2
                continue
            }
            if (text[i] == quote) quotes.add(i)
            i++
        }
        if (quotes.size < 2) return null
        var open = -1
        var close = -1
        var pair = 0
        while (pair + 1 < quotes.size) {
            val a = quotes[pair]
            val b = quotes[pair + 1]
            if (at.col <= b) {
                open = a
                close = b
                break
            }
            pair += 2
        }
        if (open < 0) return null
        if (!around) return ObjectRange(Pos(at.row, open + 1), Pos(at.row, close), linewise = false)
        var end = close + 1
        var start = open
        var trailing = end
        while (trailing < text.length && text[trailing].isWhitespace()) trailing++
        if (trailing > end) {
            end = trailing
        } else {
            while (start > 0 && text[start - 1].isWhitespace()) start--
        }
        return ObjectRange(Pos(at.row, start), Pos(at.row, end), linewise = false)
    }

    /**
     * `it` / `at`: the innermost HTML-like tag pair around the cursor, found
     * by matching tag names within a window of rows — a whole file of markup
     * is too much to read on a keystroke, and a tag that spans more than
     * three hundred lines is a tag `it` was never going to help with.
     */
    fun tag(editor: EditorState, at: Pos, around: Boolean): ObjectRange? {
        val firstRow = (at.row - TAG_WINDOW_ROWS).coerceAtLeast(0)
        val lastRow = (at.row + TAG_WINDOW_ROWS).coerceAtMost(editor.lastRow)
        val rows = editor.linesOf(firstRow, lastRow + 1).split('\n')
        // Offsets into the joined text, and the cursor's own.
        val rowStart = IntArray(rows.size)
        var acc = 0
        for (r in rows.indices) {
            rowStart[r] = acc
            acc += rows[r].length + 1
        }
        val text = rows.joinToString("\n")
        val cursor = rowStart[at.row - firstRow] + at.col
        fun posOf(offset: Int): Pos {
            var r = rowStart.size - 1
            while (r > 0 && rowStart[r] > offset) r--
            return Pos(firstRow + r, offset - rowStart[r])
        }
        class Open(val name: String, val start: Int, val end: Int)
        val stack = ArrayList<Open>()
        var best: ObjectRange? = null
        for (m in TAG.findAll(text)) {
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val selfClosing = m.groupValues[3] == "/" || name.lowercase() in VOID_TAGS
            when {
                selfClosing && !closing -> {}
                !closing -> stack.add(Open(name, m.range.first, m.range.last + 1))
                else -> {
                    val idx = stack.indexOfLast { it.name == name }
                    if (idx < 0) continue
                    val open = stack[idx]
                    while (stack.size > idx) stack.removeAt(stack.lastIndex)
                    val closeStart = m.range.first
                    val closeEnd = m.range.last + 1
                    if (cursor >= open.start && cursor < closeEnd) {
                        // Innermost wins: pairs close inner-first, so the
                        // first enclosing pair seen is the innermost.
                        if (best == null) {
                            best = if (around) {
                                ObjectRange(posOf(open.start), posOf(closeEnd), linewise = false)
                            } else {
                                ObjectRange(posOf(open.end), posOf(closeStart), linewise = false)
                            }
                        }
                    }
                }
            }
        }
        return best
    }

    /**
     * `ip` / `ap`: the run of rows like the cursor's — text or blank —
     * and, around, the run of blank rows after it (or before, when it is the
     * last paragraph). Always linewise (`:help ap`).
     */
    fun paragraph(editor: EditorState, at: Pos, around: Boolean): ObjectRange {
        val blank = editor.isBlankRow(at.row)
        var first = at.row
        while (first > 0 && editor.isBlankRow(first - 1) == blank) first--
        var last = at.row
        while (last < editor.lastRow && editor.isBlankRow(last + 1) == blank) last++
        if (around) {
            if (last < editor.lastRow) {
                val nextBlank = !blank
                while (last < editor.lastRow && editor.isBlankRow(last + 1) == nextBlank) last++
            } else if (!blank) {
                while (first > 0 && editor.isBlankRow(first - 1)) first--
            }
        }
        return ObjectRange(Pos(first, 0), Pos(last, 0), linewise = true)
    }

    private val TAG = Regex("""<(/?)([A-Za-z][\w:.-]*)(?:\s[^<>]*?)?(/?)>""")
    private val VOID_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr",
    )
}
