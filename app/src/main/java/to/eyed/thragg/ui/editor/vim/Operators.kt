package to.eyed.thragg.ui.editor.vim

import to.eyed.thragg.ui.editor.Caret
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.editor.joinLines
import to.eyed.thragg.ui.editor.toggleComment

/**
 * The span an operator works on: [start] up to but not including [end]
 * for a characterwise range, or every row from [start].row to [end].row
 * for a linewise one.
 */
internal class OpRange(val start: Pos, val end: Pos, val linewise: Boolean) {
    val firstRow: Int get() = start.row
    val lastRow: Int get() = end.row
}

/**
 * The operators — what `d`, `c`, `y`, `>`, `<`, `=`, `~`, `gu`, `gU`, `gc`
 * and `p` do once they have their range — as edits on the [EditorState].
 *
 * Every one of them is a single batch through [EditorState.applyCaretEdits],
 * which is what makes each Vim command one undo step: the engine groups a
 * batch this tight into one transaction, so `u` after `3dd` puts three lines
 * back at once, as it does in Zed (whose vim layer wraps each operator in
 * `transact`, vim/src/normal.rs).
 *
 * Text is read before the edit and written to the register by the caller,
 * so what a register holds is the text exactly as the buffer had it.
 */
internal object Operators {

    /** The text [range] covers, as a register would hold it. */
    fun textOf(editor: EditorState, range: OpRange): Register =
        if (range.linewise) {
            Register(editor.textOfRows(range.firstRow, range.lastRow) + "\n", linewise = true)
        } else {
            Register(editor.textBetween(range.start, range.end), linewise = false)
        }

    /**
     * Delete [range]. A linewise delete takes the rows' newlines with them
     * and leaves the cursor on the first non-blank of the row that closes
     * the gap; a characterwise one leaves it at the range's start.
     */
    fun delete(editor: EditorState, range: OpRange): Pos {
        if (range.linewise) {
            val first = range.firstRow
            val last = range.lastRow
            val start: Long
            val end: Long
            if (last < editor.lastRow) {
                start = editor.lineStartOffset(first)
                end = editor.lineStartOffset(last + 1)
            } else {
                // Nothing follows, so the newline that goes is the one before
                // the range — unless the range is the whole buffer.
                start = if (first > 0) editor.lineStartOffset(first) - 1 else 0
                end = editor.offsetOf(editor.endOfRow(last))
            }
            editor.applyCaretEdits(listOf(EditorState.CaretEdit(start, end, "", head = 0, isPrimary = true)))
            val row = first.coerceAtMost(editor.lastRow)
            return Pos(row, editor.firstNonBlank(row))
        }
        editor.applyCaretEdits(
            listOf(
                EditorState.CaretEdit(
                    editor.offsetOf(range.start),
                    editor.offsetOf(range.end),
                    "",
                    head = 0,
                    isPrimary = true,
                )
            )
        )
        return editor.clampToLine(range.start)
    }

    /**
     * `c` over a linewise range: the rows go, but the first one's indent
     * stays as an empty line to type into (`:help cc`: "the indent is
     * preserved"). Returns where insert mode starts.
     */
    fun changeLines(editor: EditorState, range: OpRange): Pos {
        val indent = editor.indentOf(range.firstRow)
        val start = editor.lineStartOffset(range.firstRow)
        val end = editor.offsetOf(editor.endOfRow(range.lastRow))
        editor.applyCaretEdits(listOf(EditorState.CaretEdit(start, end, indent, isPrimary = true)))
        return Pos(range.firstRow, indent.length)
    }

    /** Replace [range] with [text] in one edit; the caret lands after it. */
    fun replace(editor: EditorState, range: OpRange, text: String): Pos {
        val start: Long
        val end: Long
        if (range.linewise) {
            start = editor.lineStartOffset(range.firstRow)
            end = editor.offsetOf(editor.endOfRow(range.lastRow))
        } else {
            start = editor.offsetOf(range.start)
            end = editor.offsetOf(range.end)
        }
        editor.applyCaretEdits(listOf(EditorState.CaretEdit(start, end, text, isPrimary = true)))
        return Pos(editor.cursorRow, editor.cursorCol)
    }

    /**
     * `>` / `<` over rows, [times] levels. Indenting uses the file's own
     * unit (tabs where the file has them); outdenting takes up to one
     * unit's worth of leading whitespace per level, as `:help <` says.
     */
    fun shift(editor: EditorState, firstRow: Int, lastRow: Int, times: Int, indent: Boolean) {
        val unit = editor.indentUnit()
        val edits = ArrayList<EditorState.CaretEdit>()
        for (row in firstRow..lastRow) {
            val text = editor.line(row)
            if (indent) {
                if (text.isEmpty()) continue
                val at = editor.lineStartOffset(row)
                edits.add(EditorState.CaretEdit(at, at, unit.repeat(times), isPrimary = row == firstRow))
            } else {
                var take = 0
                repeat(times) {
                    if (text.startsWith("\t", take)) {
                        take += 1
                    } else {
                        var spaces = 0
                        while (spaces < unit.length && text.getOrNull(take + spaces) == ' ') spaces++
                        take += spaces
                    }
                }
                if (take == 0) continue
                val at = editor.lineStartOffset(row)
                edits.add(EditorState.CaretEdit(at, at + editor.utf8Length(text, take), "", isPrimary = row == firstRow))
            }
        }
        if (edits.isEmpty()) return
        if (edits.none { it.isPrimary }) {
            edits[0] = EditorState.CaretEdit(edits[0].start, edits[0].end, edits[0].replacement, isPrimary = true)
        }
        editor.applyCaretEdits(edits)
    }

    /**
     * `=`: re-indent rows from their brackets — each row takes the indent of
     * the previous non-blank row, one level deeper after a row that ends in
     * an opener and one shallower when it starts with a closer. Zed's
     * `editor::AutoIndent` asks the syntax tree; this is the bracket
     * heuristic that answers the same for the code it is mostly asked about.
     */
    fun autoIndent(editor: EditorState, firstRow: Int, lastRow: Int) {
        val unit = editor.indentUnit()
        val edits = ArrayList<EditorState.CaretEdit>()
        var previousIndent: String? = null
        var previousText: String? = null
        var probe = firstRow - 1
        while (probe >= 0 && previousText == null) {
            val text = editor.line(probe)
            if (text.isNotBlank()) {
                previousText = text
                previousIndent = editor.indentOf(probe)
            }
            probe--
        }
        for (row in firstRow..lastRow) {
            val text = editor.line(row)
            if (text.isBlank()) continue
            val content = text.trimStart(' ', '\t')
            var indent = previousIndent ?: ""
            val before = previousText?.trimEnd()
            if (before != null && before.isNotEmpty() && before.last() in "([{") {
                indent += unit
            }
            if (content.first() in ")]}" && indent.isNotEmpty()) {
                indent = indent.dropLast(if (indent.endsWith(unit)) unit.length else 1)
            }
            val current = editor.indentOf(row)
            if (current != indent) {
                val at = editor.lineStartOffset(row)
                edits.add(EditorState.CaretEdit(at, at + editor.utf8Length(current), indent, isPrimary = edits.isEmpty()))
            }
            previousIndent = indent
            previousText = content
        }
        if (edits.isNotEmpty()) editor.applyCaretEdits(edits)
    }

    /** `~`, `gu`, `gU`, `g~` over [range]. */
    fun changeCase(editor: EditorState, range: OpRange, kind: CaseKind) {
        val text = textOf(editor, range).text.let { if (range.linewise) it.dropLast(1) else it }
        val changed = when (kind) {
            CaseKind.Lower -> text.lowercase()
            CaseKind.Upper -> text.uppercase()
            CaseKind.Toggle -> buildString(text.length) {
                for (c in text) append(if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar())
            }
        }
        if (changed == text) return
        replace(editor, range, changed)
    }

    /** `gc`: the editor's own comment toggle over the rows. */
    fun toggleComment(editor: EditorState, firstRow: Int, lastRow: Int) {
        val caret = Caret(firstRow, 0, lastRow, editor.lineLength(lastRow))
        editor.setCarets(listOf(caret), caret, notify = false)
        editor.toggleComment()
    }

    /** `J` over [count] rows (at least two). The editor's own join. */
    fun join(editor: EditorState, row: Int, count: Int) {
        val last = (row + (count - 1).coerceAtLeast(1)).coerceAtMost(editor.lastRow)
        if (last == row) return
        // The selection reaches into the last row: one that ends at its
        // column 0 leaves that row alone, as it does for the editor's own
        // `Tab` and `JoinLines`, and `3J` would join two.
        val caret = Caret(row, 0, last, editor.lineLength(last))
        editor.setCarets(listOf(caret), caret, notify = false)
        editor.joinLines()
    }

    /**
     * `p` / `P`: put [register] after or before [at], [count] times. Lines go
     * on a line of their own with the cursor on the first non-blank of the
     * first one; characters go beside the cursor with it on the last
     * character put (`:help p`).
     */
    fun put(editor: EditorState, at: Pos, register: Register, after: Boolean, count: Int): Pos {
        val body = if (register.linewise) register.text.removeSuffix("\n") else register.text
        val text = List(count) { body }.joinToString(if (register.linewise) "\n" else "")
        if (register.linewise) {
            val row = at.row
            val edit = if (after) {
                val end = editor.offsetOf(editor.endOfRow(row))
                EditorState.CaretEdit(end, end, "\n$text", head = 1, isPrimary = true)
            } else {
                val start = editor.lineStartOffset(row)
                EditorState.CaretEdit(start, start, "$text\n", head = 0, isPrimary = true)
            }
            editor.applyCaretEdits(listOf(edit))
            val landed = if (after) row + 1 else row
            return Pos(landed, editor.firstNonBlank(landed))
        }
        val col = if (after && editor.lineLength(at.row) > 0) at.col + 1 else at.col
        val offset = editor.byteOffsetOf(at.row, col.coerceAtMost(editor.lineLength(at.row)))
        // On the last character put for one line of text; at the start of
        // the text for several (`:help p`).
        val head = when {
            text.contains('\n') || text.isEmpty() -> 0
            else -> editor.utf8Length(text, text.offsetByCodePoints(text.length, -1))
        }
        editor.applyCaretEdits(listOf(EditorState.CaretEdit(offset, offset, text, head = head, isPrimary = true)))
        return Pos(editor.cursorRow, editor.cursorCol)
    }

    /** `r` on [count] characters from [at]: each becomes [replacement]. */
    fun replaceChars(editor: EditorState, at: Pos, count: Int, replacement: Char): Pos? {
        val text = editor.line(at.row)
        if (at.col + count > text.length) return null
        val piece = replacement.toString().repeat(count)
        val start = editor.byteOffsetOf(at.row, at.col)
        val end = editor.byteOffsetOf(at.row, at.col + count)
        editor.applyCaretEdits(listOf(EditorState.CaretEdit(start, end, piece, head = editor.utf8Length(piece) - 1, isPrimary = true)))
        return Pos(at.row, at.col + count - 1)
    }

    /** `o` / `O`: a new row below or above, with [indent], to type into. */
    fun openLine(editor: EditorState, row: Int, below: Boolean, indent: String): Pos {
        val edit = if (below) {
            val end = editor.offsetOf(editor.endOfRow(row))
            EditorState.CaretEdit(end, end, "\n$indent", isPrimary = true)
        } else {
            val start = editor.lineStartOffset(row)
            EditorState.CaretEdit(start, start, "$indent\n", head = editor.utf8Length(indent), isPrimary = true)
        }
        editor.applyCaretEdits(listOf(edit))
        return Pos(if (below) row + 1 else row, indent.length)
    }

    /** Insert [text] at [at] in one edit, leaving the caret after it. */
    fun insert(editor: EditorState, at: Pos, text: String): Pos {
        val offset = editor.offsetOf(editor.clampToLine(at))
        editor.applyCaretEdits(listOf(EditorState.CaretEdit(offset, offset, text, isPrimary = true)))
        return Pos(editor.cursorRow, editor.cursorCol)
    }

    enum class CaseKind { Lower, Upper, Toggle }
}
