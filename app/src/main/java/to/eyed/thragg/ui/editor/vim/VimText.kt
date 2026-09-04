package to.eyed.thragg.ui.editor.vim

import to.eyed.thragg.ui.editor.Caret
import to.eyed.thragg.ui.editor.EditorState

/**
 * The few questions the motions and operators ask of the buffer, as
 * extensions on [EditorState] so the rest of the package reads like Vim's
 * own description of itself rather than like offset arithmetic.
 *
 * Rows come from [EditorState.line], which serves the drawn window from its
 * cache and asks the engine for anything else — one bridge call per row
 * outside it. The motions that can walk far ([Motions.matchingBracket], the
 * paragraph motions) cap how far they go for that reason.
 */

internal val EditorState.lastRow: Int get() = (lineCount - 1).coerceAtLeast(0)

internal fun EditorState.lineLength(row: Int): Int = line(row).length

/** The column of the first character that is not a space or a tab; 0 on a blank row. */
internal fun EditorState.firstNonBlank(row: Int): Int {
    val text = line(row)
    val at = text.indexOfFirst { it != ' ' && it != '\t' }
    return if (at < 0) 0 else at
}

/** A row's leading whitespace, verbatim. */
internal fun EditorState.indentOf(row: Int): String {
    val text = line(row)
    return text.takeWhile { it == ' ' || it == '\t' }
}

internal fun EditorState.isBlankRow(row: Int): Boolean = line(row).isBlank()

/** Text from [start] up to but not including [end]. */
internal fun EditorState.textBetween(start: Pos, end: Pos): String {
    if (end <= start) return ""
    return textIn(Caret(start.row, start.col, end.row, end.col))
}

/** Rows [first]..[last] inclusive, joined by newlines, without a trailing one. */
internal fun EditorState.textOfRows(first: Int, last: Int): String =
    linesOf(first, last + 1)

internal fun EditorState.offsetOf(pos: Pos): Long = byteOffsetOf(pos.row, pos.col)

/** Vim's three character classes, or two for the `W`/`B`/`E` family. */
internal fun charClass(c: Char, big: Boolean): Int = when {
    c.isWhitespace() -> 0
    big -> 1
    c.isLetterOrDigit() || c == '_' -> 1
    else -> 2
}

/** The row's position one past its last character — where the newline sits. */
internal fun EditorState.endOfRow(row: Int): Pos = Pos(row, lineLength(row))

/**
 * Clamp a position to where a normal-mode cursor may rest: on a character
 * of its row, never on the newline — `l` at the end of a line does not move
 * (vim/src/motion.rs `right`, which clips with `Bias::Left` for the same
 * reason).
 */
internal fun EditorState.clampToRow(pos: Pos): Pos {
    val row = pos.row.coerceIn(0, lastRow)
    val len = lineLength(row)
    return Pos(row, pos.col.coerceIn(0, (len - 1).coerceAtLeast(0)))
}

/** Clamp a position to where an insert-mode cursor may rest: up to and including the line end. */
internal fun EditorState.clampToLine(pos: Pos): Pos {
    val row = pos.row.coerceIn(0, lastRow)
    return Pos(row, pos.col.coerceIn(0, lineLength(row)))
}
