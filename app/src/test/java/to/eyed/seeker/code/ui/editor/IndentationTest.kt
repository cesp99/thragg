package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zed's `Tab`, `Backtab`, `Indent`, `Outdent`, `NewlineAbove` and
 * `NewlineBelow`, as far as their arithmetic goes: which rows a selection
 * claims, how far a row moves, where the carets land afterwards. The rules
 * are Zed's (`indent_selection`, `outdent`, `IndentSize::outdent_len`), and
 * the checks are on the text *and* the carets — an indent that leaves the
 * selection on different characters than it started on is the bug these
 * keys are known for.
 */
class IndentationTest {

    private fun editorOf(text: String, language: String? = null): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text, language, LanguageFixtures.of(language))
        return EditorState(buffer) to buffer
    }

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.select(anchorRow: Int, anchorCol: Int, headRow: Int, headCol: Int) =
        setCarets(
            listOf(Caret(anchorRow, anchorCol, headRow, headCol)),
            Caret(anchorRow, anchorCol, headRow, headCol),
        )

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    private val rustFile = "fn main() {\n    let a = 1;\n    let b = 2;\n}"

    // ---- Tab and Indent -----------------------------------------------------

    @Test
    fun tabWithNothingSelectedInsertsAnIndentLevel() {
        val (state, buffer) = editorOf("x", "rust")
        state.caretAt(0, 1)

        state.tab()

        assertEquals("x    ", buffer.text)
        assertEquals(0 to 5, state.head())
    }

    @Test
    fun tabWithASelectionIndentsEverySelectedRowAndKeepsTheSelectionEndsOnTheirText() {
        val (state, buffer) = editorOf(rustFile, "rust")
        state.select(1, 4, 2, 8)

        state.tab()

        assertEquals("fn main() {\n        let a = 1;\n        let b = 2;\n}", buffer.text)
        assertEquals(Caret(1, 8, 2, 12), state.primaryCaret())
    }

    /** Zed's `spanned_rows(false)`: a selection ending at column 0 has not claimed that row. */
    @Test
    fun aSelectionEndingAtColumnZeroLeavesThatRowAlone() {
        val (state, buffer) = editorOf(rustFile, "rust")
        state.select(1, 0, 2, 0)

        state.indent()

        assertEquals("fn main() {\n        let a = 1;\n    let b = 2;\n}", buffer.text)
        assertEquals(Caret(1, 4, 2, 0), state.primaryCaret())
    }

    /** Zed indents to the next tab stop, not by a fixed width. */
    @Test
    fun indentGoesToTheNextTabStop() {
        val (state, buffer) = editorOf("   x", "rust")
        state.select(0, 0, 0, 4)

        state.indent()

        assertEquals("    x", buffer.text)
        assertEquals(Caret(0, 1, 0, 5), state.primaryCaret())
    }

    @Test
    fun indentWithABareCaretIndentsItsRowAndMovesTheCaretWithTheText() {
        val (state, buffer) = editorOf("let a = 1;", "rust")
        state.caretAt(0, 6)

        state.indent()

        assertEquals("    let a = 1;", buffer.text)
        assertEquals(0 to 10, state.head())
    }

    @Test
    fun aCaretInsideTheIndentKeepsWhatIsBehindIt() {
        // Zed puts the new level in at the caret when the caret stands in
        // the indent (editor.rs:5500-5504).
        val (state, buffer) = editorOf("  x", "rust")
        state.caretAt(0, 1)

        state.indent()

        // Two spaces to the next stop, inserted at column 1.
        assertEquals("    x", buffer.text)
        assertEquals(0 to 3, state.head())
    }

    @Test
    fun twoCaretsOnOneRowIndentItOnce() {
        val (state, buffer) = editorOf("abcdefgh", "rust")
        state.setCarets(listOf(Caret(0, 2), Caret(0, 6)), Caret(0, 2))

        state.indent()

        assertEquals("    abcdefgh", buffer.text)
        assertEquals(0 to 6, state.head())
        assertEquals(listOf(Caret(0, 10)), state.extraCarets)
    }

    @Test
    fun tabWithAMixedBatchIndentsTheSelectionAndInsertsAtTheBareCaret() {
        val (state, buffer) = editorOf("one\ntwo", "rust")
        state.setCarets(listOf(Caret(0, 0, 0, 3), Caret(1, 1)), Caret(0, 0, 0, 3))

        state.tab()

        assertEquals("    one\nt    wo", buffer.text)
        assertEquals(Caret(0, 4, 0, 7), state.primaryCaret())
        assertEquals(listOf(Caret(1, 5)), state.extraCarets)
    }

    @Test
    fun aTabIndentedFileIndentsWithATab() {
        val (state, buffer) = editorOf("func main() {\n\tx()\n}", "go")
        state.caretAt(1, 2)

        state.indent()

        assertEquals("func main() {\n\t\tx()\n}", buffer.text)
        assertEquals(1 to 3, state.head())
    }

    // ---- Backtab and Outdent ------------------------------------------------

    @Test
    fun outdentTakesOneLevelOffEverySelectedRow() {
        val (state, buffer) = editorOf("fn main() {\n        let a = 1;\n        let b = 2;\n}", "rust")
        state.select(1, 8, 2, 12)

        state.outdent()

        assertEquals(rustFile, buffer.text)
        assertEquals(Caret(1, 4, 2, 8), state.primaryCaret())
    }

    /** Zed's `outdent_len`: back to the previous tab stop, whatever the indent. */
    @Test
    fun outdentGoesBackToThePreviousTabStop() {
        val (state, buffer) = editorOf("     x\n\ty\nz", "rust")
        state.select(0, 0, 2, 1)

        state.outdent()

        // Five spaces lose one, a tab loses itself, nothing loses nothing.
        assertEquals("    x\ny\nz", buffer.text)
        assertEquals(Caret(0, 0, 2, 1), state.primaryCaret())
    }

    @Test
    fun outdentNeverTakesText() {
        val (state, buffer) = editorOf("let a = 1;", "rust")
        state.caretAt(0, 3)

        state.outdent()

        assertEquals("let a = 1;", buffer.text)
        assertEquals(0 to 3, state.head())
    }

    @Test
    fun outdentWithABareCaretMovesTheCaretWithTheText() {
        val (state, buffer) = editorOf("        let a = 1;", "rust")
        state.caretAt(0, 12)

        state.outdent()

        assertEquals("    let a = 1;", buffer.text)
        assertEquals(0 to 8, state.head())
    }

    @Test
    fun aCaretInsideTheIndentOutdentsFromBehindItself() {
        // Zed deletes the level just behind a caret that stands in the
        // indent (editor.rs:5555-5563).
        val (state, buffer) = editorOf("        x", "rust")
        state.caretAt(0, 6)

        state.outdent()

        assertEquals("    x", buffer.text)
        assertEquals(0 to 2, state.head())
    }

    @Test
    fun aCaretInsideTheDeletedIndentLandsAtItsStart() {
        val (state, buffer) = editorOf("    x", "rust")
        state.caretAt(0, 2)

        state.outdent()

        assertEquals("x", buffer.text)
        assertEquals(0 to 0, state.head())
    }

    // ---- Newline above and below ---------------------------------------------

    @Test
    fun newlineBelowOpensARowUnderTheCaretsAtItsIndent() {
        val (state, buffer) = editorOf(rustFile, "rust")
        state.caretAt(1, 6)

        state.newlineBelow()

        assertEquals("fn main() {\n    let a = 1;\n    \n    let b = 2;\n}", buffer.text)
        assertEquals(2 to 4, state.head())
        assertTrue(state.primaryCaret().isEmpty)
    }

    @Test
    fun newlineBelowABlockOpenerIndentsOneLevelDeeper() {
        val (state, buffer) = editorOf(rustFile, "rust")
        state.caretAt(0, 3)

        state.newlineBelow()

        assertEquals("fn main() {\n    \n    let a = 1;\n    let b = 2;\n}", buffer.text)
        assertEquals(1 to 4, state.head())
    }

    @Test
    fun newlineBelowTheLastRowAppendsARow() {
        val (state, buffer) = editorOf(rustFile, "rust")
        state.caretAt(3, 1)

        state.newlineBelow()

        assertEquals("$rustFile\n", buffer.text)
        assertEquals(4 to 0, state.head())
    }

    @Test
    fun newlineAboveOpensARowOverTheCaretsIndentedByTheRowAboveIt() {
        val (state, buffer) = editorOf(rustFile, "rust")
        state.caretAt(1, 6)

        state.newlineAbove()

        // Row 0 ends in `{`, so the new row is one level in.
        assertEquals("fn main() {\n    \n    let a = 1;\n    let b = 2;\n}", buffer.text)
        assertEquals(1 to 4, state.head())
    }

    @Test
    fun newlineAboveTheFirstRowIsABareRow() {
        val (state, buffer) = editorOf(rustFile, "rust")
        state.caretAt(0, 5)

        state.newlineAbove()

        assertEquals("\n$rustFile", buffer.text)
        assertEquals(0 to 0, state.head())
    }

    @Test
    fun newlineBelowAtEveryCaretIsOneBatch() {
        val (state, buffer) = editorOf("a\nb", "rust")
        state.setCarets(listOf(Caret(0, 1), Caret(1, 1)), Caret(0, 1))

        state.newlineBelow()

        assertEquals("a\n\nb\n", buffer.text)
        assertEquals(1 to 0, state.head())
        assertEquals(listOf(Caret(3, 0)), state.extraCarets)
    }
}
