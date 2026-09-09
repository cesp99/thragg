package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Undo must not walk back through a reload nobody asked for.
 *
 * `Engine::reload_buffer` replaces the whole text as one finalised
 * transaction so that a *chosen* reload can be taken back. The poll's reload
 * is not chosen: it fires on a CLEAN buffer whose file another writer — the
 * agent, `seahorse build`, `git checkout` — has just rewritten. Undoing past
 * it restores the file as it was before that writer, turns the buffer dirty
 * again, and hands the next autosave a stale copy to write over the newer
 * file. That is the data loss; the floor is the fix.
 */
class UndoAfterReloadTest {

    private fun editorOf(text: String): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text)
        val state = EditorState(buffer)
        state.updateMetrics(lineHeight = 20f, charWidth = 8f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(400f, 200f)
        return state to buffer
    }

    /** The poll's path: reload the buffer, then tell the editor it was one. */
    private fun reload(state: EditorState, buffer: FakeEditorBuffer, text: String) {
        buffer.rewriteFromDisk(text)
        state.noteExternalReload()
    }

    @Test
    fun undoDoesNotRestoreTheFileTheReloadReplaced() {
        val (state, buffer) = editorOf("mine\nold second line")
        var stopped = 0
        state.onUndoStoppedAtReload = { stopped++ }

        reload(state, buffer, "theirs\nnew second line")
        state.undo()

        assertEquals("theirs\nnew second line", buffer.text)
        assertEquals(1, stopped)
    }

    /** Hold-to-repeat sits on the key: it must be refused every time, not once. */
    @Test
    fun theFloorHoldsUnderARepeatedUndo() {
        val (state, buffer) = editorOf("mine")
        reload(state, buffer, "theirs")

        repeat(8) { state.undo() }

        assertEquals("theirs", buffer.text)
    }

    /** What the user typed *after* the reload is still theirs to take back. */
    @Test
    fun editsMadeAfterTheReloadStillUndo() {
        val (state, buffer) = editorOf("mine")
        reload(state, buffer, "theirs")
        state.setCarets(listOf(Caret(0, 6)), Caret(0, 6))
        state.insertAtCursor("!")
        assertEquals("theirs!", buffer.text)

        var stopped = 0
        state.onUndoStoppedAtReload = { stopped++ }
        state.undo()
        assertEquals("theirs", buffer.text)
        assertEquals("the step above the floor is not the floor", 0, stopped)

        // …and the one below it is.
        state.undo()
        assertEquals("theirs", buffer.text)
        assertEquals(1, stopped)
    }

    /**
     * The floor is the text of the LAST reload. A second one moves it, and
     * the first is no longer reachable either.
     */
    @Test
    fun asecondReloadMovesTheFloor() {
        val (state, buffer) = editorOf("one")
        reload(state, buffer, "two")
        reload(state, buffer, "three")

        state.undo()
        state.undo()

        assertEquals("three", buffer.text)
    }

    /**
     * With no reload behind it, undo is the plain history step it always was
     * — the floor must not be armed by anything else.
     */
    @Test
    fun withoutAReloadUndoIsUntouched() {
        val (state, buffer) = editorOf("abc")
        state.setCarets(listOf(Caret(0, 3)), Caret(0, 3))
        state.insertAtCursor("d")
        assertEquals("abcd", buffer.text)

        var stopped = false
        state.onUndoStoppedAtReload = { stopped = true }
        state.undo()

        assertEquals("abc", buffer.text)
        assertFalse(stopped)
    }

    /**
     * A caret past the end of the reloaded text is still pulled back in:
     * [EditorState.noteExternalReload] does everything
     * [EditorState.noteExternalEdit] did and then some.
     */
    @Test
    fun theReloadStillResyncsTheCarets() {
        val (state, buffer) = editorOf(List(40) { "line $it" }.joinToString("\n"))
        state.setCarets(listOf(Caret(30, 4)), Caret(30, 4))

        reload(state, buffer, "a\nb")

        assertEquals(1, state.cursorRow)
        assertTrue(state.cursorCol <= 1)
    }
}
