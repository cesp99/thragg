package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a keystroke costs at the window cache: typing inside a line patches
 * the window in place and crosses the bridge for nothing, a reparse landing
 * re-reads only the spans, and only an edit that changes the line structure
 * still pays for a full refetch. The counters are [FakeEditorBuffer]'s —
 * the JNI round trips are the price these tests are about, not the answers.
 */
class EditorWindowCacheTest {

    private fun editorOf(rows: Int = 100): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(List(rows) { "line $it" }.joinToString("\n"))
        return EditorState(buffer) to buffer
    }

    @Test
    fun typingInsideALineRefetchesNoWindow() {
        val (state, buffer) = editorOf()
        state.setCarets(listOf(Caret(10, 4)), Caret(10, 4))
        state.linesWindow(0, 40)
        val lineCalls = buffer.lineCalls
        val highlightCalls = buffer.highlightCalls

        state.insertAtCursor("X")

        assertEquals("the keystroke crossed the bridge for no window",
            lineCalls, buffer.lineCalls)
        assertEquals("lineX 10", state.linesWindow(0, 40)[10])
        assertEquals("and serving the patched window cost nothing either",
            lineCalls, buffer.lineCalls)

        // The other half of a typing burst: deleting inside a line is the
        // same one-line diff and gets the same in-place patch.
        state.backspace()

        assertEquals("line 10", state.linesWindow(0, 40)[10])
        assertEquals(lineCalls, buffer.lineCalls)
        assertEquals("no keystroke re-ran the highlight query",
            highlightCalls, buffer.highlightCalls)
    }

    @Test
    fun aReparseAloneRefetchesOnlyTheSpans() {
        val (state, buffer) = editorOf()
        state.linesWindow(0, 40)
        val lineCalls = buffer.lineCalls
        val highlightCalls = buffer.highlightCalls

        // The engine's background reparse lands and the poll notices.
        buffer.highlightVersion = 7L
        state.refreshHighlightVersion()
        state.linesWindow(0, 40)

        assertEquals("no window of unchanged text was marshaled",
            lineCalls, buffer.lineCalls)
        assertEquals("the spans were re-read once",
            highlightCalls + 1, buffer.highlightCalls)

        state.linesWindow(0, 40)

        assertEquals("and the version once adopted is free", lineCalls, buffer.lineCalls)
        assertEquals(highlightCalls + 1, buffer.highlightCalls)
    }

    @Test
    fun aConcurrentEditForcesARefetchInsteadOfTheInPlacePatch() {
        val (state, buffer) = editorOf()
        state.setCarets(listOf(Caret(10, 4)), Caret(10, 4))
        state.linesWindow(0, 40)

        // A second writer's edit lands between the keystroke's staleness
        // check and its own edit reaching the engine, so the keystroke's
        // edit comes back at checked-version + 2. The patched window does
        // not hold what the other writer wrote, so serving it as current
        // would show row 20's old text forever.
        buffer.beforeNextEdit = {
            val start = buffer.rowStart(20)
            buffer.edit(start, start + "line 20".length, "agent wrote this")
        }
        state.insertAtCursor("X")
        val lineCalls = buffer.lineCalls

        val window = state.linesWindow(0, 40)

        assertTrue("the ambiguous stamp forced a real refetch",
            buffer.lineCalls > lineCalls)
        assertEquals("lineX 10", window[10])
        assertEquals("agent wrote this", window[20])
    }

    @Test
    fun aStructuralEditStillRefetchesTheWindow() {
        val (state, buffer) = editorOf()
        state.setCarets(listOf(Caret(10, 4)), Caret(10, 4))
        state.linesWindow(0, 40)
        val lineCalls = buffer.lineCalls

        state.applyLineDiff(10, "AB\nCD", 2)
        val window = state.linesWindow(0, 41)

        assertTrue("the split forced a real refetch", buffer.lineCalls > lineCalls)
        assertEquals("AB", window[10])
        assertEquals("CD", window[11])
    }
}
