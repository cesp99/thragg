package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things the find bar asks the editor for: what to open with, and
 * a caret on every match when the user asks for them all.
 */
class SearchSeedAndSelectAllTest {

    private fun editorOf(text: String) = EditorState(FakeEditorBuffer(text))

    @Test
    fun theSeedIsTheSelectionWhenItSitsOnOneLine() {
        val state = editorOf("let needle = haystack;\nlet other = 1;")
        state.setCarets(listOf(Caret(0, 4, 0, 10)), Caret(0, 4, 0, 10))
        assertEquals("needle", state.searchSeed())
    }

    @Test
    fun theSeedIsTheWordUnderTheCaretWithNothingSelected() {
        val state = editorOf("let needle = haystack;")
        state.setCarets(listOf(Caret(0, 6)), Caret(0, 6))
        assertEquals("needle", state.searchSeed())
        // On whitespace there is no word, and no seed.
        state.setCarets(listOf(Caret(0, 3)), Caret(0, 3))
        assertEquals("", state.searchSeed())
    }

    @Test
    fun aSelectionAcrossLinesSeedsNothing() {
        val state = editorOf("needle\nneedle")
        state.setCarets(listOf(Caret(0, 0, 1, 6)), Caret(0, 0, 1, 6))
        assertEquals("", state.searchSeed())
    }

    @Test
    fun selectingEveryMatchPutsACaretOnEachWithTheActiveOneAsPrimary() {
        val state = editorOf("a b a\na")
        val matches = listOf(
            EditorState.SelectionRange(0, 0, 0, 1),
            EditorState.SelectionRange(0, 4, 0, 5),
            EditorState.SelectionRange(1, 0, 1, 1),
        )
        assertTrue(state.selectAllSearchMatches(matches, active = 1))
        assertEquals(
            listOf(Caret(0, 0, 0, 1), Caret(0, 4, 0, 5), Caret(1, 0, 1, 1)),
            state.caretsInOrder(),
        )
        // The primary is the bar's current match, so the view does not jump.
        assertEquals(Caret(0, 4, 0, 5), state.primaryCaret())
    }

    @Test
    fun selectingNoMatchesChangesNothing() {
        val state = editorOf("a b a")
        state.setCarets(listOf(Caret(0, 2)), Caret(0, 2))
        assertFalse(state.selectAllSearchMatches(emptyList(), active = 0))
        assertEquals(listOf(Caret(0, 2)), state.caretsInOrder())
    }

    @Test
    fun anActiveIndexPastTheListFallsBackToTheFirstMatch() {
        val state = editorOf("a a")
        val matches = listOf(
            EditorState.SelectionRange(0, 0, 0, 1),
            EditorState.SelectionRange(0, 2, 0, 3),
        )
        assertTrue(state.selectAllSearchMatches(matches, active = 7))
        assertEquals(Caret(0, 0, 0, 1), state.primaryCaret())
    }
}
