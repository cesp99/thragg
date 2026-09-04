package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What accepting a completion and following a definition do to the buffer.
 *
 * Both write to the engine through byte offsets, which is where an off-by-one
 * is not a wrong pixel but a corrupted file — and both take positions from a
 * *server*, which may be describing the file as it was a moment ago.
 */
class EditorCompletionTest {

    private fun editorOf(text: String, language: String? = null) = EditorState(
        FakeEditorBuffer(text, language, LanguageFixtures.of(language))
    )

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    // ---- accepting ----

    @Test
    fun acceptingReplacesTheServersRangeAndPutsTheCaretInTheSnippet() {
        val state = editorOf("let v = vec.pus\n")
        state.caretAt(0, 15)

        val (text, caret) = expandSnippet("push(\${1:value})")
        assertTrue(state.replaceRange(LspRange(0, 12, 0, 15), text, caret))

        assertEquals("let v = vec.push(value)", state.line(0))
        // The caret lands on the first placeholder, not after the whole
        // insert: the placeholder is what the user has to replace.
        assertEquals(0 to 17, state.head())
    }

    @Test
    fun theRangeIsCountedInBytesWhereverTheColumnsCameFrom() {
        // UTF-16 columns in, UTF-8 offsets out — the whole point of the
        // conversion, and the one that silently corrupts a file when it is
        // wrong.
        val state = editorOf("é.pus\n")
        state.caretAt(0, 5)
        assertTrue(state.replaceRange(LspRange(0, 2, 0, 5), "push", 4))
        assertEquals("é.push", state.line(0))
        assertEquals(0 to 6, state.head())
    }

    @Test
    fun aRangeReachingPastTheEndOfALineIsClamped() {
        // A completion answer is allowed to be a little out of date; an
        // offset past the end of the buffer is an edit the engine refuses
        // outright, and refusing it here would be a keystroke lost.
        val state = editorOf("ab\n")
        state.caretAt(0, 2)
        assertTrue(state.replaceRange(LspRange(0, 1, 0, 99), "cd", 2))
        assertEquals("acd", state.line(0))
    }

    @Test
    fun acceptingOverAnExistingIdentifierReplacesIt() {
        // The bridge reports the REPLACE range of an insert-and-replace edit
        // for exactly this: the caret sits inside a word and the word goes.
        val state = editorOf("vec.push_all\n")
        state.caretAt(0, 8)
        assertTrue(state.replaceRange(LspRange(0, 4, 0, 12), "pop", 3))
        assertEquals("vec.pop", state.line(0))
    }

    // ---- going to a definition ----

    private val nested = listOf(
        "fn outer() {", //      0
        "    if x {", //        1
        "        inner()", //   2
        "    }", //             3
        "}", //                 4
    ).joinToString("\n")

    @Test
    fun goingToADefinitionPutsABareCaretOnTheName() {
        val state = editorOf(nested)
        state.caretAt(4, 0)

        assertTrue(state.revealDefinition(2, 8))

        assertEquals(2 to 8, state.head())
        assertFalse(state.hasSelection)
    }

    @Test
    fun goingToADefinitionUnfoldsWhatHidesIt() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets()
        assertTrue(state.isRowFoldedAway(2))

        state.revealDefinition(2, 8)

        // Zed unfolds before it selects (navigation.rs:1297), which is the
        // same order the diagnostic navigation already uses here.
        assertFalse(state.isRowFoldedAway(2))
        assertEquals(2 to 8, state.head())
    }

    @Test
    fun aTargetPastTheEndOfTheFileLandsInsideIt() {
        // The server described a file that has since got shorter. Clamp, as
        // every other navigation here does; never refuse.
        val state = editorOf(nested)
        state.revealDefinition(900, 900)
        assertEquals(4 to 1, state.head())
    }

    // ---- what counts as typing ----

    @Test
    fun aKeystrokeIsReportedExactlyOnce() {
        val state = editorOf("vec.\n")
        val typed = mutableListOf<String>()
        state.onTextTyped = { typed.add(it) }
        state.caretAt(0, 4)

        state.typeCharacter("p")
        // The pane reports again after every `typeCharacter`, because the
        // bracket-pair path never reaches `applyLineDiff`. The second report
        // for one keystroke is dropped.
        state.noteTyped("p")

        assertEquals(listOf("p"), typed)
    }

    @Test
    fun eachKeystrokeIsReported() {
        val state = editorOf("vec.\n")
        val typed = mutableListOf<String>()
        state.onTextTyped = { typed.add(it) }
        state.caretAt(0, 4)

        state.typeCharacter("p")
        state.typeCharacter("u")
        state.typeCharacter("s")

        assertEquals(listOf("p", "u", "s"), typed)
        assertEquals("vec.pus", state.line(0))
    }

    @Test
    fun aBracketPairIsReportedByThePaneRatherThanByTheDiff() {
        // `(` auto-closes, which goes through the batch edit path instead of
        // the line diff — so the state cannot see it, and the pane's own
        // report after `typeCharacter` is what makes a trigger character
        // typed on a hardware keyboard open the menu.
        val state = editorOf("fn f\n", "rust")
        val typed = mutableListOf<String>()
        state.onTextTyped = { typed.add(it) }
        state.caretAt(0, 4)

        state.typeCharacter("(")
        assertEquals("fn f()", state.line(0))
        assertTrue(typed.isEmpty())

        state.noteTyped("(")
        assertEquals(listOf("("), typed)
    }

    @Test
    fun anImeCommitIsReportedToo() {
        // The soft-keyboard path: a whole line handed back with one character
        // more in it. Everything that reacts to typing has to see this or it
        // would only ever work with a hardware keyboard.
        val state = editorOf("vec.\n")
        val typed = mutableListOf<String>()
        state.onTextTyped = { typed.add(it) }
        state.caretAt(0, 4)

        state.applyLineDiff(0, "vec.p", 5)

        assertEquals(listOf("p"), typed)
    }

    @Test
    fun anImeCorrectionOfAWholeWordIsNotAKeystroke() {
        // Autocorrect and swipe typing replace a run of text; Zed's trigger
        // test takes one character and gives up if there is a second
        // (completions.rs:1521-1528), which is what keeps a menu from opening
        // on a word the user did not type letter by letter.
        val state = editorOf("teh\n")
        val typed = mutableListOf<String>()
        state.onTextTyped = { typed.add(it) }
        state.caretAt(0, 3)

        state.applyLineDiff(0, "the", 3)

        assertTrue(typed.isEmpty())
    }
}
