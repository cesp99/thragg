package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A multi-row indent is one batch of edits, applied back to front, and a
 * history step afterwards has to leave the state — text, line count, carets
 * — on the restored buffer, with nothing written back on top of it. On a
 * device the batch's undo came back corrupted (the inserted spaces kept and
 * the bytes after them deleted); the engine and this state are both clean,
 * which is what pinned the fault on the soft keyboard's own handling of the
 * chord — see `Keystroke.beforeIme`. These keep the two halves honest.
 */
class IndentUndoTest {

    private fun editorOf(text: String): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text, "rust", LanguageFixtures.of("rust"))
        return EditorState(buffer) to buffer
    }

    private val file = "fn main() {\n    println!(\"Hello, world!\");\n}\n"

    @Test
    fun tabOnASelectionIsOneEditAndUndoRestoresTheRows() {
        val (state, buffer) = editorOf(file)
        state.setCarets(listOf(Caret(1, 0, 2, 0)), Caret(1, 0, 2, 0))

        state.tab()

        // The row the selection ends on at column 0 is not claimed.
        assertEquals(listOf(Triple(12L, 12L, "    ")), buffer.edits)
        assertEquals("fn main() {\n        println!(\"Hello, world!\");\n}\n", buffer.text)

        state.undo()

        assertEquals(file, buffer.text)
        assertEquals(4, state.lineCount)
        assertEquals(listOf(Triple(12L, 12L, "    ")), buffer.edits)
        assertEquals("    println!(\"Hello, world!\");", state.line(1))
        assertEquals("}", state.line(2))
    }

    @Test
    fun aMultiCaretIndentIsAppliedBackToFrontAndUndoneWithoutTouchingTheText() {
        val (state, buffer) = editorOf(file)
        state.setCarets(listOf(Caret(0, 4), Caret(1, 4), Caret(2, 1)), Caret(0, 4))

        state.indent()

        assertEquals(
            listOf(Triple(43L, 43L, "    "), Triple(16L, 16L, "    "), Triple(0L, 0L, "    ")),
            buffer.edits,
        )
        assertEquals("    fn main() {\n        println!(\"Hello, world!\");\n    }\n", buffer.text)
        assertEquals(listOf(Caret(0, 8), Caret(1, 8), Caret(2, 5)), state.caretsInOrder())

        // The engine groups the three into one transaction; the fake keeps a
        // step per edit, so three steps stand in for its one.
        repeat(3) { state.undo() }

        assertEquals(file, buffer.text)
        assertEquals(3, buffer.edits.size)
        // The extra carets go with the undone edit, as in Zed; the primary
        // stays on its row, inside the line.
        assertEquals(1, state.caretsInOrder().size)
        assertEquals(0, state.cursorRow)
        assertEquals("fn main() {", state.line(0))
    }
}
