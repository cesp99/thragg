package to.eyed.thragg.ui.editor

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys the pane answers, and the fact that something answers them.
 *
 * The keymap pass that used to resolve a stroke to an `editor::` action was
 * deleted, and nothing took its place: [EditorState.runAction] had no callers
 * at all, so ⌫ from the soft keyboard — which GBoard sends as a key event
 * with no printable character — fell into the "not text" branch of
 * `handleEditorKey` and was dropped. The line never changed while the
 * suggestion strip believed it had, which is the shape of the worst kind of
 * editor bug: the buffer and the keyboard disagreeing about the text.
 *
 * Both halves are tested here — the table that names the action, and the
 * table that runs it — because either one alone is a dispatcher with nothing
 * on the other end.
 */
class EditorKeyDispatchTest {

    private fun editorOf(text: String): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text, "rust", LanguageFixtures.of("rust"))
        val state = EditorState(buffer)
        state.actionHandlers = editorTypingHandlers(state)
        return state to buffer
    }

    /** Press [key] the way `handleEditorKey` does: name the action, run it. */
    private fun EditorState.press(
        key: Key,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): Boolean {
        val action = editorKeyAction(key, ctrl, shift, alt) ?: return false
        return runAction(action)
    }

    // ---- the repro ----

    /** s2 blocker #2: type `ZZZ`, press ⌫ three times, and the line is back. */
    @Test
    fun backspaceDeletesWhatWasJustTyped() {
        val (state, buffer) = editorOf("start")
        state.setCarets(listOf(Caret(0, 5)), Caret(0, 5))

        repeat(3) { state.typeCharacter("Z") }
        assertEquals("startZZZ", buffer.text)

        repeat(3) { assertTrue("⌫ was not answered", state.press(Key.Backspace)) }

        assertEquals("start", buffer.text)
        assertEquals(0 to 5, state.primaryCaret().let { it.headRow to it.headCol })
    }

    /** And the other three a phone's keyboard sends as key events. */
    @Test
    fun enterTabAndForwardDeleteAllReachTheBuffer() {
        val (state, buffer) = editorOf("ab")
        state.setCarets(listOf(Caret(0, 1)), Caret(0, 1))

        assertTrue(state.press(Key.Delete))
        assertEquals("a", buffer.text)

        assertTrue(state.press(Key.Enter))
        assertEquals("a\n", buffer.text)

        assertTrue(state.press(Key.Tab))
        assertEquals("a\n    ", buffer.text)
    }

    /** A word-wise delete is the same key with a modifier on it. */
    @Test
    fun controlBackspaceTakesTheWholeWord() {
        val (state, buffer) = editorOf("one two")
        state.setCarets(listOf(Caret(0, 7)), Caret(0, 7))

        assertTrue(state.press(Key.Backspace, ctrl = true))

        assertEquals("one ", buffer.text)
    }

    /** The arrows move the caret, and Shift makes the move a selection. */
    @Test
    fun theArrowsMoveAndShiftSelects() {
        val (state, _) = editorOf("hello\nworld")
        state.setCarets(listOf(Caret(0, 0)), Caret(0, 0))

        assertTrue(state.press(Key.DirectionRight))
        assertEquals(0 to 1, state.primaryCaret().let { it.headRow to it.headCol })

        assertTrue(state.press(Key.DirectionRight, shift = true))
        assertEquals(EditorState.SelectionRange(0, 1, 0, 2), state.selectionRange())

        assertTrue(state.press(Key.DirectionDown))
        assertEquals(1, state.primaryCaret().headRow)
    }

    // ---- the table ----

    @Test
    fun theEditingKeysNameZedsOwnActions() {
        assertEquals(EditorAction.Backspace, editorKeyAction(Key.Backspace, false, false, false))
        assertEquals(EditorAction.Delete, editorKeyAction(Key.Delete, false, false, false))
        assertEquals(EditorAction.Newline, editorKeyAction(Key.Enter, false, false, false))
        assertEquals(EditorAction.Tab, editorKeyAction(Key.Tab, false, false, false))
        assertEquals(EditorAction.Backtab, editorKeyAction(Key.Tab, false, true, false))
        assertEquals(EditorAction.MoveLeft, editorKeyAction(Key.DirectionLeft, false, false, false))
        assertEquals(EditorAction.SelectUp, editorKeyAction(Key.DirectionUp, false, true, false))
        assertEquals(
            EditorAction.MoveToPreviousWordStart,
            editorKeyAction(Key.DirectionLeft, true, false, false),
        )
        assertEquals(
            EditorAction.SelectToNextWordEnd,
            editorKeyAction(Key.DirectionRight, true, true, false),
        )
        assertEquals(
            EditorAction.MoveToBeginning,
            editorKeyAction(Key.MoveHome, true, false, false),
        )
        assertEquals(
            EditorAction.MoveToEndOfLine,
            editorKeyAction(Key.MoveEnd, false, false, false),
        )
        assertEquals(EditorAction.MovePageDown, editorKeyAction(Key.PageDown, false, false, false))
        assertEquals(EditorAction.Cancel, editorKeyAction(Key.Escape, false, false, false))
    }

    /** A letter is text, and text is not this table's business. */
    @Test
    fun aPrintableKeyNamesNoAction() {
        assertNull(editorKeyAction(Key.A, false, false, false))
        assertNull(editorKeyAction(Key.Spacebar, false, false, false))
    }

    /**
     * The dispatcher's other end. Every action a key can name has to be in
     * the handler map the pane registers, or the key is answered by nobody —
     * which is exactly the state this whole file exists to stop coming back.
     * `Cancel` is the one exception: it dismisses the popups, so it is
     * registered with them rather than with the buffer's own keys.
     */
    @Test
    fun everyKeyTheTableNamesHasSomethingToRunIt() {
        val (state, _) = editorOf("text")
        val handlers = editorTypingHandlers(state)
        val keys = listOf(
            Key.Backspace, Key.Delete, Key.Enter, Key.NumPadEnter, Key.Tab,
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
            Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown,
        )
        for (key in keys) {
            for (ctrl in listOf(false, true)) {
                for (shift in listOf(false, true)) {
                    for (alt in listOf(false, true)) {
                        val action = editorKeyAction(key, ctrl, shift, alt) ?: continue
                        assertTrue(
                            "$action (${key}, ctrl=$ctrl shift=$shift alt=$alt) has no handler",
                            handlers.containsKey(action),
                        )
                    }
                }
            }
        }
    }
}
