package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caret machinery against a buffer that behaves like the engine —
 * UTF-8 offsets, and an outright refusal for a range that does not land on
 * a code-point boundary.
 *
 * These are the cases where a wrong offset does not throw, does not draw
 * anything odd, and silently writes the wrong bytes into the user's file.
 */
class MultiCaretEditingTest {

    private fun editorOf(text: String, language: String? = null): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text, language, LanguageFixtures.of(language))
        return EditorState(buffer) to buffer
    }

    /**
     * The soft keyboard reports its change in bytes of the caret's own line.
     * Replaying that count at a caret on a line of plain ASCII deletes one
     * character per byte the multi-byte one took.
     */
    @Test
    fun imeBackspaceOverAMultiByteCharacterDeletesOneCharacterAtEveryCaret() {
        val (state, buffer) = editorOf("let café = 1;\nlet x = 2;", "rust")
        state.setCarets(listOf(Caret(0, 8), Caret(1, 8)), Caret(0, 8))

        // deleteSurroundingText(1, 0) on the primary's line: café → caf.
        state.applyLineDiff(0, "let caf = 1;", 7)

        assertEquals("let caf = 1;\nlet x =2;", buffer.text)
        assertEquals(0, buffer.refusedEdits.size)
        assertEquals(1, state.extraCarets.size)
        assertEquals(Caret(1, 7), state.extraCarets[0])
        assertEquals(7, state.cursorCol)
    }

    /** The same spread with the multi-byte character at the *other* caret. */
    @Test
    fun imeBackspaceSpreadOntoAMultiByteLineDeletesOneCharacterThere() {
        val (state, buffer) = editorOf("let x = 1;\nlet café = 2;", "rust")
        state.setCarets(listOf(Caret(0, 8), Caret(1, 8)), Caret(0, 8))

        // The primary deletes a space (one byte); the caret on line 1 sits
        // just past 'é', which is two.
        state.applyLineDiff(0, "let x =1;", 7)

        assertEquals("let x =1;\nlet caf = 2;", buffer.text)
        assertEquals(0, buffer.refusedEdits.size)
    }

    /**
     * An edit the engine refuses changed nothing, so counting its length
     * against the carets after it puts every one of them at an offset the
     * buffer never had.
     */
    @Test
    fun anEditTheEngineRefusesDoesNotShiftTheCaretsAfterIt() {
        val (state, buffer) = editorOf("café\nxyzw")
        // [3, 4) ends inside 'é' — Engine::edit answers InvalidRange.
        state.applyCaretEdits(
            listOf(
                EditorState.CaretEdit(start = 3, end = 4, replacement = ""),
                EditorState.CaretEdit(start = 7, end = 7, replacement = "!", isPrimary = true),
            )
        )

        assertEquals(1, buffer.refusedEdits.size)
        assertEquals("café\nx!yzw", buffer.text)
        // Offset 8, which is column 2 — not column 1, which is where the
        // refused edit's phantom -1 byte would have put it.
        assertEquals(1, state.cursorRow)
        assertEquals(2, state.cursorCol)
        assertTrue(state.extraCarets.isEmpty())
    }

    /**
     * The IME's cursor control moves the primary without going through the
     * caret set. Landing it on an extra caret used to leave two carets in
     * one place, and everything typed from then on went in twice.
     */
    @Test
    fun anImeCaretMoveOntoAnExtraCaretMergesInsteadOfDoubling() {
        val (state, buffer) = editorOf("hello world")
        state.setCarets(listOf(Caret(0, 3), Caret(0, 6)), Caret(0, 3))

        // finishComposingText with the line unchanged and the caret moved.
        state.applyLineDiff(0, "hello world", 6)
        assertTrue(state.extraCarets.isEmpty())

        state.insertAtCursor("x")
        assertEquals("hello xworld", buffer.text)
    }

    /** And the batch itself refuses to run two inserts at one offset. */
    @Test
    fun twoZeroWidthInsertsAtOneOffsetRunOnce() {
        val (state, buffer) = editorOf("hello world")
        state.applyCaretEdits(
            listOf(
                EditorState.CaretEdit(start = 6, end = 6, replacement = "x", isPrimary = true),
                EditorState.CaretEdit(start = 6, end = 6, replacement = "x"),
            )
        )
        assertEquals("hello xworld", buffer.text)
    }

    /**
     * Backspace at column zero with a column of carets: every caret joins
     * its row, and none of them is left naming a row that moved under it.
     * This is the command the IME's own backspace now goes through.
     */
    @Test
    fun backspaceAtColumnZeroJoinsAtEveryCaret() {
        val (state, buffer) = editorOf("one\ntwo\nthree\nfour")
        state.setCarets(listOf(Caret(1, 0), Caret(2, 0), Caret(3, 0)), Caret(1, 0))

        state.backspace()

        assertEquals("onetwothreefour", buffer.text)
        assertEquals(listOf(Caret(0, 6), Caret(0, 11)), state.extraCarets.sortedWith(CaretOrder))
    }

    /** A press with a mixed caret set does both halves of the work. */
    @Test
    fun backspaceDeletesAtBareCaretsWhileAnotherCaretHasASelection() {
        val (state, buffer) = editorOf("alpha beta\nxyz")
        state.setCarets(listOf(Caret(0, 6, 0, 10), Caret(1, 3)), Caret(0, 6, 0, 10))

        state.backspace()

        assertEquals("alpha \nxy", buffer.text)
    }

    /** Rust's `<` never auto-closes, so backspace may not take the `>`. */
    @Test
    fun backspaceInsideRustAngleBracketsDeletesOnlyTheOpener() {
        val (state, buffer) = editorOf("Vec<>", "rust")
        state.setCarets(listOf(Caret(0, 4)), Caret(0, 4))

        state.backspace()

        assertEquals("Vec>", buffer.text)
    }

    /** A pair we did close comes out whole. */
    @Test
    fun backspaceInsideAnEmptyPairDeletesBothHalves() {
        val (state, buffer) = editorOf("foo()", "rust")
        state.setCarets(listOf(Caret(0, 4)), Caret(0, 4))

        state.backspace()

        assertEquals("foo", buffer.text)
    }

    /** Join with a caret on the last row: the join happens, the caret lives. */
    @Test
    fun joinLinesKeepsTheCaretOnTheLastRow() {
        val (state, buffer) = editorOf("one\ntwo\nthree\nfour")
        state.setCarets(listOf(Caret(1, 0), Caret(3, 2)), Caret(1, 0))

        state.joinLines()

        assertEquals("one\ntwo three\nfour", buffer.text)
        // The join took a row out from above it, so the surviving caret is
        // on row 2 — but it is still there, and still in its column.
        assertEquals(listOf(Caret(2, 2)), state.extraCarets)
    }

    /** Copy leaves nothing highlighted — at every caret, not just the one. */
    @Test
    fun collapsingSelectionsReachesEveryCaret() {
        val (state, _) = editorOf("alpha beta gamma")
        state.setCarets(
            listOf(Caret(0, 0, 0, 5), Caret(0, 6, 0, 10), Caret(0, 11, 0, 16)),
            Caret(0, 0, 0, 5),
        )

        state.collapseSelections()

        assertTrue(state.caretsInOrder().all { it.isEmpty })
        assertEquals(3, state.caretsInOrder().size)
    }
}
