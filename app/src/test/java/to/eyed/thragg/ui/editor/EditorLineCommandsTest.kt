package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line and text commands against a real [EditorState]: the machinery that
 * decides *which* text a transform runs over, which is the half
 * [LineTransformsTest] cannot see.
 */
class EditorLineCommandsTest {

    private fun editorOf(text: String): EditorState = EditorState(FakeEditorBuffer(text))

    /** An editor whose language comments with `//`, for the rewrap tests. */
    private fun rustish(text: String): EditorState = EditorState(
        FakeEditorBuffer(
            text,
            language = "rust",
            languageConfigJson = """{"name": "Rust", "line_comments": ["// "]}""",
        )
    )

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.selectRows(from: Int, to: Int) {
        val caret = Caret(from, 0, to, line(to).length)
        setCarets(listOf(caret), caret)
    }

    private fun EditorState.text(): String = linesOf(0, lineCount)

    @Test
    fun sortingRewritesTheSelectedRowsAndLeavesThemSelected() {
        val state = editorOf("c\na\nb\ntail")
        state.selectRows(0, 2)

        assertTrue(state.manipulateLines(LineTransforms::sort))
        assertEquals("a\nb\nc\ntail", state.text())
        assertEquals("a\nb\nc", state.selectionText())
    }

    @Test
    fun uniqueDropsRowsAndTheRestMovesUp() {
        val state = editorOf("a\nb\na\nb\nend")
        state.selectRows(0, 3)

        assertTrue(state.manipulateLines(LineTransforms::unique))
        assertEquals("a\nb\nend", state.text())
    }

    /** A transform that changes nothing touches nothing — and says so. */
    @Test
    fun anAlreadySortedBlockIsLeftAlone() {
        val state = editorOf("a\nb\nc")
        state.selectRows(0, 2)
        assertFalse(state.manipulateLines(LineTransforms::sort))
        assertEquals("a\nb\nc", state.text())
    }

    @Test
    fun aCaseConversionWithNoSelectionTakesTheWordUnderTheCaret() {
        val state = editorOf("let someValue = other")
        state.caretAt(0, 8)

        assertTrue(state.manipulateText { LineTransforms.convertCase(it, LineTransforms.Case.Snake) })
        assertEquals("let some_value = other", state.text())
        // What it rewrote comes back selected, as Zed leaves it.
        assertEquals("some_value", state.selectionText())
    }

    @Test
    fun aCaseConversionWithASelectionTakesExactlyTheSelection() {
        val state = editorOf("alpha beta")
        val caret = Caret(0, 0, 0, 5)
        state.setCarets(listOf(caret), caret)

        assertTrue(state.manipulateText { it.uppercase() })
        assertEquals("ALPHA beta", state.text())
    }

    @Test
    fun selectLineTakesTheWholeRowIncludingItsBreak() {
        val state = editorOf("one\ntwo\nthree")
        state.caretAt(1, 2)

        assertTrue(state.selectLines())
        assertEquals("two\n", state.selectionText())
    }

    /** The last row has no break to take, so the selection stops at its end. */
    @Test
    fun selectLineOnTheLastRowStopsAtItsEnd() {
        val state = editorOf("one\ntwo")
        state.caretAt(1, 1)

        assertTrue(state.selectLines())
        assertEquals("two", state.selectionText())
    }

    @Test
    fun transposeSwapsTheCharactersAroundTheCaret() {
        val state = editorOf("abcd")
        state.caretAt(0, 2)

        assertTrue(state.transpose())
        assertEquals("acbd", state.text())
    }

    /** At the end of a line Zed steps back and swaps the last two. */
    @Test
    fun transposeAtTheEndOfALineSwapsTheLastTwo() {
        val state = editorOf("ab")
        state.caretAt(0, 2)

        assertTrue(state.transpose())
        assertEquals("ba", state.text())
    }

    @Test
    fun transposeAtTheStartOfTheBufferDoesNothing() {
        val state = editorOf("ab")
        state.caretAt(0, 0)
        assertFalse(state.transpose())
        assertEquals("ab", state.text())
    }

    @Test
    fun rewrapReflowsTheCommentBlockTheCaretSitsIn() {
        val state = rustish(
            "// one two three four five six seven\n" +
                "// eight nine\n" +
                "code()"
        )
        state.caretAt(0, 5)

        assertTrue(state.rewrap(preferredLineLength = 20))
        val lines = state.text().split("\n")
        // The code below the block is untouched.
        assertEquals("code()", lines.last())
        // Every reflowed row keeps the marker and fits the column.
        val comment = lines.dropLast(1)
        assertTrue(comment.all { it.startsWith("// ") })
        assertTrue(comment.all { it.length <= 20 })
        assertTrue(comment.size > 2)
    }

    @Test
    fun rewrapOverASelectionUsesExactlyTheSelectedRows() {
        val state = rustish("one\ntwo\nthree\nfour")
        state.selectRows(0, 1)

        assertTrue(state.rewrap(preferredLineLength = 80))
        assertEquals("one two\nthree\nfour", state.text())
    }
}
