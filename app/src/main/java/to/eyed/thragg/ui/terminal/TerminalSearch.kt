package to.eyed.thragg.ui.terminal

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.WcWidth
import com.termux.view.TerminalView

/**
 * One hit in the transcript: a row in the emulator's external coordinates
 * (negative rows are scrollback, 0 is the top of the screen) and the columns
 * it covers, inclusive — the shape the vendored renderer highlights.
 */
data class TerminalMatch(val row: Int, val startColumn: Int, val endColumn: Int)

/**
 * A row of the screen or the transcript as text, trailing blanks trimmed —
 * the same call the view's own copy uses, so what is searched is what would
 * be copied. Wrapped lines are *not* joined: a hit is highlighted by cell, and
 * a cell range cannot span the wrap.
 */
fun TerminalBuffer.rowText(row: Int, columns: Int): String =
    getSelectedText(0, row, columns - 1, row, false)

/**
 * The column a character index in [rowText] lands in. Wide characters (CJK,
 * most emoji) take two cells, so index and column part ways after the first
 * one; the emulator's own width table settles it.
 */
fun columnOfIndex(text: String, index: Int): Int {
    var column = 0
    var i = 0
    while (i < index && i < text.length) {
        val codePoint = text.codePointAt(i)
        column += WcWidth.width(codePoint).coerceAtLeast(0)
        i += Character.charCount(codePoint)
    }
    return column
}

/**
 * The character index at [column] of [text], or null past the row's end —
 * the inverse of [columnOfIndex], for a touch that has been turned into a
 * column.
 */
fun indexOfColumn(text: String, column: Int): Int? {
    var current = 0
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        val width = WcWidth.width(codePoint).coerceAtLeast(1)
        if (column < current + width) return i
        current += width
        i += Character.charCount(codePoint)
    }
    return null
}

/**
 * Every occurrence of [query] in the transcript and the screen, oldest first
 * — Zed's terminal search walks the whole grid the same way
 * (terminal_view.rs `regex_search_for_query`, then `Term::search`). Plain
 * text, case-folded unless [caseSensitive]; the buffer search bar's regex and
 * whole-word toggles are not offered here, matching Zed's terminal bar, which
 * has case only.
 *
 * Runs on the main thread by design: the emulator is mutated there on every
 * chunk of output, and a scan on another thread would read rows mid-write.
 * Ten thousand rows of eighty columns is under a megabyte of chars — a few
 * milliseconds — and the caller debounces.
 */
fun searchTerminal(
    screen: TerminalBuffer,
    columns: Int,
    screenRows: Int,
    query: String,
    caseSensitive: Boolean,
): List<TerminalMatch> {
    if (query.isEmpty()) return emptyList()
    val matches = ArrayList<TerminalMatch>()
    val needle = if (caseSensitive) query else query.lowercase()
    for (row in -screen.activeTranscriptRows until screenRows) {
        val text = screen.rowText(row, columns)
        if (text.isEmpty()) continue
        val haystack = if (caseSensitive) text else text.lowercase()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            // Case folding can change a string's length (ß → ss); the cells
            // are measured on the original text, and a fold that shifted the
            // index is simply clamped to the row.
            val start = columnOfIndex(text, at.coerceAtMost(text.length))
            val end = columnOfIndex(text, (at + needle.length).coerceAtMost(text.length)) - 1
            matches += TerminalMatch(row, start, end.coerceAtLeast(start))
            from = at + needle.length.coerceAtLeast(1)
        }
    }
    return matches
}

/**
 * Bring [match] on screen and paint it — Zed's `Terminal::activate_match`
 * scrolls so the hit is visible and selects it (terminal.rs `set_selection`
 * from `activate_match`). Here the selection is the vendored view's passive
 * highlight, not a text selection: a selection brings handles and a toolbar.
 *
 * The scroll puts the match a third of the way down when it is in the
 * scrollback, which reads better than pinning it to the top edge; a match
 * already on the visible screen leaves the scroll alone.
 */
fun revealTerminalMatch(view: TerminalView, match: TerminalMatch?) {
    if (match == null) {
        view.clearHighlight()
        return
    }
    val emulator = view.mEmulator ?: return
    val rows = emulator.mRows
    val transcript = emulator.screen.activeTranscriptRows
    val top = view.topRow
    if (match.row < top || match.row >= top + rows) {
        view.topRow = (match.row - rows / 3).coerceIn(-transcript, 0)
    }
    view.setHighlight(match.startColumn, match.row, match.endColumn, match.row)
}
