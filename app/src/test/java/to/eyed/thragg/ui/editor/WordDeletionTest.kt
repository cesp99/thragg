package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The deletion keys Zed binds beside Backspace — `delete`, `ctrl-backspace`
 * and `ctrl-delete` — and the boundary rules they follow. The rules are
 * Zed's own (movement.rs `previous_word_start_or_newline`,
 * `next_word_end_or_newline` and `adjust_greedy_deletion`), and the cases
 * here are the ones a plain word motion would get wrong: trailing runs of
 * spaces, brackets, and the ends of a line.
 */
class WordDeletionTest {

    private fun editorOf(text: String, language: String? = null): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text, language, LanguageFixtures.of(language))
        return EditorState(buffer) to buffer
    }

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    // ---- The boundary arithmetic ------------------------------------------

    @Test
    fun backwardsTakesTheWordBehindTheCaret() {
        assertEquals(4, wordDeletionStart("let value", 9))
        // One space goes with the word before it.
        assertEquals(4, wordDeletionStart("let value ", 10))
    }

    @Test
    fun backwardsARunOfSpacesGoesOnItsOwn() {
        // Zed's `adjust_greedy_deletion`: two or more spaces stop the
        // deletion after themselves, the word waits for the next press.
        assertEquals(5, wordDeletionStart("value   ", 8))
        // The run inside the range wins over the boundary the motion found.
        assertEquals(3, wordDeletionStart("let  value", 5))
    }

    @Test
    fun backwardsStopsAtAClassChange() {
        // Punctuation is its own run.
        assertEquals(4, wordDeletionStart("foo(bar", 7))
        assertEquals(3, wordDeletionStart("foo(", 4))
        assertEquals(5, wordDeletionStart("a == b", 6))
        assertEquals(2, wordDeletionStart("a ==", 4))
    }

    @Test
    fun backwardsStopsAtTheNearestBracket() {
        val brackets = setOf('(', ')', '"')
        // `f(x);|` — the punctuation run is `);`, and the `)` is a bracket
        // edge inside it, so only the `;` goes.
        assertEquals(4, wordDeletionStart("f(x);", 5, brackets))
        // Without a bracket set the whole run goes, as the raw motion says.
        assertEquals(3, wordDeletionStart("f(x);", 5))
        // A bracket at the very start of the range is the range's own end,
        // not something inside it: `(` alone goes.
        assertEquals(3, wordDeletionStart("foo(", 4, brackets))
        // A closing quote right behind the caret goes on its own.
        assertEquals(6, wordDeletionStart("x = \"a\"", 7, brackets))
    }

    @Test
    fun backwardsFromTheIndentTakesTheIndent() {
        assertEquals(0, wordDeletionStart("    foo", 4))
        assertEquals(0, wordDeletionStart("\tfoo", 1))
    }

    @Test
    fun forwardsTakesTheWordInFrontOfTheCaret() {
        assertEquals(3, wordDeletionEnd("let value", 0))
        // A single leading space goes with the word after it ...
        assertEquals(9, wordDeletionEnd("let value", 3))
        // ... unless there are two or more, which go on their own.
        assertEquals(5, wordDeletionEnd("let  value", 3))
    }

    @Test
    fun forwardsStopsAtAClassChangeAndAtABracket() {
        assertEquals(3, wordDeletionEnd("foo(bar)", 0))
        assertEquals(4, wordDeletionEnd("foo(bar)", 3))
        // `|);` — the `)` is a bracket, and its far edge is inside the
        // range, so only the `)` goes and the `;` waits.
        assertEquals(1, wordDeletionEnd(");", 0, setOf('(', ')')))
        assertEquals(2, wordDeletionEnd(");", 0))
    }

    @Test
    fun characterClassesAreZeds() {
        assertEquals(CharKind.Word, charKind('a'))
        assertEquals(CharKind.Word, charKind('_'))
        assertEquals(CharKind.Word, charKind('9'))
        assertEquals(CharKind.Whitespace, charKind('\t'))
        assertEquals(CharKind.Punctuation, charKind('('))
        assertEquals(CharKind.Punctuation, charKind('-'))
    }

    // ---- Through the editor -------------------------------------------------

    @Test
    fun ctrlBackspaceDeletesTheWordAndLandsWhereItStarted() {
        val (state, buffer) = editorOf("let value = 1", "rust")
        state.caretAt(0, 9)

        state.deleteToPreviousWordStart()

        assertEquals("let  = 1", buffer.text)
        assertEquals(0 to 4, state.head())
    }

    @Test
    fun ctrlBackspaceAtColumnZeroJoinsWithTheRowAboveAndStopsThere() {
        val (state, buffer) = editorOf("one\ntwo", "rust")
        state.caretAt(1, 0)

        state.deleteToPreviousWordStart()

        assertEquals("onetwo", buffer.text)
        assertEquals(0 to 3, state.head())
    }

    @Test
    fun ctrlBackspaceAtTheTopOfTheFileDoesNothing() {
        val (state, buffer) = editorOf("one", "rust")
        state.caretAt(0, 0)

        state.deleteToPreviousWordStart()

        assertEquals("one", buffer.text)
        assertEquals(0 to 0, state.head())
    }

    @Test
    fun ctrlBackspaceWithASelectionDeletesTheSelection() {
        val (state, buffer) = editorOf("alpha beta gamma")
        state.setCarets(listOf(Caret(0, 6, 0, 10)), Caret(0, 6, 0, 10))

        state.deleteToPreviousWordStart()

        assertEquals("alpha  gamma", buffer.text)
        assertEquals(0 to 6, state.head())
    }

    @Test
    fun ctrlBackspaceStopsAtTheLanguagesBrackets() {
        val (state, buffer) = editorOf("f(x);", "rust")
        state.caretAt(0, 5)

        state.deleteToPreviousWordStart()

        assertEquals("f(x)", buffer.text)
    }

    @Test
    fun ctrlDeleteDeletesForwardAndAtTheEndOfARowTakesTheBreak() {
        val (state, buffer) = editorOf("one two\nthree", "rust")
        state.caretAt(0, 3)

        state.deleteToNextWordEnd()
        assertEquals("one\nthree", buffer.text)
        assertEquals(0 to 3, state.head())

        state.deleteToNextWordEnd()
        assertEquals("onethree", buffer.text)
        assertEquals(0 to 3, state.head())
    }

    @Test
    fun deleteTakesTheCharacterInFrontOrTheSelectionOrTheBreak() {
        val (state, buffer) = editorOf("ab\ncd")
        state.caretAt(0, 1)

        state.delete()
        assertEquals("a\ncd", buffer.text)
        assertEquals(0 to 1, state.head())

        state.delete()
        assertEquals("acd", buffer.text)
        assertEquals(0 to 1, state.head())

        state.setCarets(listOf(Caret(0, 0, 0, 2)), Caret(0, 0, 0, 2))
        state.delete()
        assertEquals("d", buffer.text)

        // Nothing in front at the end of the buffer.
        state.caretAt(0, 1)
        state.delete()
        assertEquals("d", buffer.text)
    }

    @Test
    fun deleteTakesAWholeCodePointNotHalfOfOne() {
        val (state, buffer) = editorOf("a😀b")
        state.caretAt(0, 1)

        state.delete()

        assertEquals("ab", buffer.text)
        assertEquals(0, buffer.refusedEdits.size)
    }

    @Test
    fun wordDeletionsRunAtEveryCaretInOneBatch() {
        val (state, buffer) = editorOf("let alpha = 1\nlet beta = 2", "rust")
        state.setCarets(listOf(Caret(0, 9), Caret(1, 8)), Caret(0, 9))

        state.deleteToPreviousWordStart()

        assertEquals("let  = 1\nlet  = 2", buffer.text)
        assertEquals(0 to 4, state.head())
        assertEquals(listOf(Caret(1, 4)), state.extraCarets)
    }
}
