package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file rewritten on disk under an open editor — an agent's edit, a
 * `seahorse build` regenerating its sources, a `git checkout` — and reloaded
 * by the engine (`Engine::reload_buffer`) with the editor told afterwards, or
 * not yet. Every caret, the selection's anchor and the scroll have to land
 * inside the text that is there now *before* anything reaches the engine, and
 * a keystroke that raced the rewrite must not become an edit against text
 * that is gone.
 */
class ExternalReloadTest {

    private fun editorOf(rows: Int): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(List(rows) { "line number $it with some text" }.joinToString("\n"))
        val state = EditorState(buffer)
        state.updateMetrics(lineHeight = 20f, charWidth = 8f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(400f, 200f)
        return state to buffer
    }

    /** The reload path the status loop takes: reload, then [EditorState.noteExternalEdit]. */
    private fun reload(state: EditorState, buffer: FakeEditorBuffer, text: String) {
        buffer.rewriteFromDisk(text)
        state.noteExternalEdit()
    }

    @Test
    fun aCaretPastTheNewEndOfTheBufferIsPulledBackIn() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(30, 20)), Caret(30, 20))

        reload(state, buffer, "a\nb")

        assertEquals(1, state.cursorRow)
        assertEquals(1, state.cursorCol)
        state.insertAtCursor("x")
        assertEquals("a\nbx", buffer.text)
        assertTrue(buffer.refusedEdits.isEmpty())
    }

    @Test
    fun aCaretPastTheNewEndOfItsLineIsPulledBackIn() {
        val (state, buffer) = editorOf(3)
        state.setCarets(listOf(Caret(1, 25)), Caret(1, 25))

        reload(state, buffer, "line 0\nshort\nline 2")

        assertEquals(1, state.cursorRow)
        assertEquals(5, state.cursorCol)
        state.insertAtCursor("!")
        assertEquals("line 0\nshort!\nline 2", buffer.text)
    }

    /**
     * This is the one that was missed: the head was clamped, the anchor was
     * not, and a selection whose anchor named a row the file no longer had
     * became a range the engine refused — `end` before `start` — on the next
     * keystroke.
     */
    @Test
    fun aSelectionAnchoredPastTheNewEndIsClampedWithItsHead() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(35, 10, 2, 3)), Caret(35, 10, 2, 3))

        reload(state, buffer, "abc\ndef\nghi")

        // Head and anchor both resolve to (2, 3): a caret, not a selection.
        assertNull(state.selectionRange())
        assertEquals(2, state.cursorRow)
        assertEquals(3, state.cursorCol)
        state.insertAtCursor("x")
        assertEquals("abc\ndef\nghix", buffer.text)
        assertTrue("no range the engine had to refuse", buffer.refusedEdits.isEmpty())
    }

    @Test
    fun aSelectionWhoseHeadWasPastTheEndKeepsWhatStillResolves() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(1, 1, 35, 10)), Caret(1, 1, 35, 10))

        reload(state, buffer, "abc\ndef\nghi")

        assertEquals(EditorState.SelectionRange(1, 1, 2, 3), state.selectionRange())
        assertTrue(state.deleteSelection())
        assertEquals("abc\nd", buffer.text)
        assertTrue(buffer.refusedEdits.isEmpty())
    }

    @Test
    fun anAnchorThatLandsOnTheHeadIsNoSelection() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(30, 3, 35, 10)), Caret(30, 3, 35, 10))

        reload(state, buffer, "abc\ndef\nghi")

        assertNull(state.selectionRange())
        assertFalse(state.hasSelection)
    }

    @Test
    fun theScrollComesBackIntoTheShorterFile() {
        val (state, buffer) = editorOf(400)
        state.setCarets(listOf(Caret(390, 5)), Caret(390, 5))
        state.scrollToY(state.maxScrollY)
        assertTrue(state.scrollY > 0f)

        reload(state, buffer, "a\nb")

        assertEquals(0f, state.scrollY)
        assertEquals(1, state.cursorRow)
    }

    // ---- The tick before the status loop notices ---------------------------

    /**
     * The reload lands on the IO thread and the editor learns of it up to a
     * poll tick later. A keystroke in that window used to be applied at
     * offsets measured against the old text — the engine clipped them to
     * the end of the new file and the character landed where nobody typed.
     * Now the editor notices the buffer moved, drops that one keystroke and
     * resyncs, so the next lands where the caret is shown.
     */
    @Test
    fun aKeystrokeThatRacedTheReloadIsDroppedAndTheEditorResyncs() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(30, 20)), Caret(30, 20))
        buffer.rewriteFromDisk("a\nb")

        state.insertAtCursor("x")

        assertEquals("nothing reached the engine", "a\nb", buffer.text)
        assertEquals(1, state.cursorRow)
        assertEquals(1, state.cursorCol)
        state.insertAtCursor("y")
        assertEquals("a\nby", buffer.text)
    }

    /**
     * The IME's connection holds a shadow of the caret's line as it was
     * seeded. Its next commit diffed that shadow against the *new* text of
     * the row and re-inserted the whole old line into the new file.
     */
    @Test
    fun anImeCommitSeededFromTheOldLineDoesNotReinsertIt() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(30, 20)), Caret(30, 20))
        val shadow = state.currentLine()
        buffer.rewriteFromDisk("a\nb")

        val structural = state.applyLineDiff(30, shadow + "x", 21)

        assertFalse(structural)
        assertEquals("a\nb", buffer.text)
        assertEquals(1, state.cursorRow)
        assertEquals(1, state.cursorCol)
        // The connection re-seeds from the line as it is now, and the next
        // commit is an ordinary one.
        state.applyLineDiff(1, "bx", 2)
        assertEquals("a\nbx", buffer.text)
    }

    @Test
    fun aSelectionDeleteThatRacedTheReloadReachesNothing() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(2, 3, 35, 10)), Caret(2, 3, 35, 10))
        buffer.rewriteFromDisk("abc\ndef\nghi")

        state.deleteSelection()

        assertEquals("abc\ndef\nghi", buffer.text)
        assertTrue(buffer.refusedEdits.isEmpty())
        assertNull(state.selectionRange())
        assertEquals(2, state.cursorRow)
        assertEquals(3, state.cursorCol)
    }

    @Test
    fun theRestoredCaretNotifiesTheInputSessionToReseed() {
        val (state, buffer) = editorOf(40)
        state.setCarets(listOf(Caret(30, 20)), Caret(30, 20))
        var restarts = 0
        state.onCursorChangedExternally = { restarts++ }

        reload(state, buffer, "a\nb")

        assertEquals(1, restarts)
    }
}
