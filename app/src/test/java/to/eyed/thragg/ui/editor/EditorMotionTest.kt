package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The motions a paired keyboard needs. They are pure caret arithmetic over a
 * fake buffer, which is exactly the kind of thing that used to be untestable
 * here and could only be checked by hand on a phone.
 */
class EditorMotionTest {

    private fun editorOf(text: String): EditorState = EditorState(FakeEditorBuffer(text))

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    @Test
    fun homeGoesToTheIndentFirstAndTheMarginSecond() {
        val state = editorOf("    hello()")
        state.caretAt(0, 9)

        state.moveToLineStart()
        assertEquals(0 to 4, state.head())

        // Pressed again from the indent, it goes all the way.
        state.moveToLineStart()
        assertEquals(0 to 0, state.head())
    }

    @Test
    fun endGoesToTheEndOfTheLineNotOfTheFile() {
        val state = editorOf("one\ntwo\nthree")
        state.caretAt(1, 0)

        state.moveToLineEnd()
        assertEquals(1 to 3, state.head())
    }

    @Test
    fun wordMotionStopsAtRunBoundaries() {
        val state = editorOf("let value = compute(x)")
        state.caretAt(0, 0)

        state.moveByWord(forward = true)
        assertEquals("past 'let' and its space", 0 to 4, state.head())

        state.moveByWord(forward = true)
        assertEquals("past 'value'", 0 to 10, state.head())

        // Punctuation is its own run: '=' does not swallow the word after it.
        state.moveByWord(forward = true)
        assertEquals(0 to 12, state.head())
    }

    @Test
    fun wordMotionBackwardsLandsOnTheStartOfTheWordItWasInside() {
        val state = editorOf("let value = 1")
        state.caretAt(0, 8)

        state.moveByWord(forward = false)
        assertEquals(0 to 4, state.head())
    }

    @Test
    fun wordMotionCrossesTheLineEnding() {
        val state = editorOf("one\ntwo")
        state.caretAt(0, 3)

        state.moveByWord(forward = true)
        assertEquals("the next line's near end, not past its first word", 1 to 0, state.head())
    }

    @Test
    fun documentMotionsReachBothEnds() {
        val state = editorOf("one\ntwo\nthree")
        state.caretAt(1, 1)

        state.moveToDocumentEnd()
        assertEquals(2 to 5, state.head())

        state.moveToDocumentStart()
        assertEquals(0 to 0, state.head())
    }

    @Test
    fun shiftedMotionsSelectRatherThanJump() {
        val state = editorOf("    hello()")
        state.caretAt(0, 11)

        state.moveToLineStart(extend = true)
        val caret = state.primaryCaret()
        assertEquals("the anchor stays where the caret was", 0 to 11, caret.anchorRow to caret.anchorCol)
        assertEquals(0 to 4, caret.headRow to caret.headCol)
    }

    @Test
    fun everyCaretMovesTogether() {
        val state = editorOf("    one\n    two\n    three")
        state.setCarets(
            listOf(Caret(0, 7), Caret(1, 7), Caret(2, 9)),
            Caret(0, 7),
        )

        state.moveToLineStart()

        assertEquals(
            listOf(0 to 4, 1 to 4, 2 to 4),
            state.caretsInOrder().map { it.headRow to it.headCol },
        )
    }
}
