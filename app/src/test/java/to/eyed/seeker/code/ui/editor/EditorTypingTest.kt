package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What typing does: the indent Enter carries, the pairs each language
 * actually has, and the walk Ctrl+Shift+L makes over the buffer.
 */
class EditorTypingTest {

    private fun editorOf(text: String, language: String? = null): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text, language, LanguageFixtures.of(language))
        return EditorState(buffer) to buffer
    }

    /** A trailing quote closes a string; it does not open a block. */
    @Test
    fun enterAfterAStringLiteralKeepsTheIndent() {
        val (state, buffer) = editorOf("def f():\n    x = \"hello\"", "python")
        state.setCarets(listOf(Caret(1, 15)), Caret(1, 15))

        state.insertNewline()

        assertEquals("def f():\n    x = \"hello\"\n    ", buffer.text)
    }

    /** Python's colon still does. */
    @Test
    fun enterAfterAPythonColonIndentsOneLevel() {
        val (state, buffer) = editorOf("def f():\n    x = 1", "python")
        state.setCarets(listOf(Caret(0, 8)), Caret(0, 8))

        state.insertNewline()

        assertEquals("def f():\n    \n    x = 1", buffer.text)
    }

    /** And so does a brace, with its closer pushed down when it is waiting. */
    @Test
    fun enterBetweenBracesGivesTheCloserItsOwnLine() {
        val (state, buffer) = editorOf("fn main() {}", "rust")
        state.setCarets(listOf(Caret(0, 11)), Caret(0, 11))

        state.insertNewline()

        assertEquals("fn main() {\n    \n}", buffer.text)
    }

    /**
     * At column zero the row's own indent says nothing, so the file has to
     * be asked: a tab-indented file that got spaces for its first level
     * would be mixed from the second line on.
     */
    @Test
    fun enterInATabIndentedFileIndentsWithATab() {
        val (state, buffer) = editorOf("func main() {\n\tprintln(1)\n}", "go")
        state.setCarets(listOf(Caret(0, 13)), Caret(0, 13))

        state.insertNewline()

        assertEquals("func main() {\n\t\n\tprintln(1)\n}", buffer.text)
    }

    /** A file with nothing to say leaves it to the language. */
    @Test
    fun anEmptyGoFileStillIndentsWithATab() {
        val (state, _) = editorOf("", "go")
        assertEquals("\t", state.indentUnit())
    }

    @Test
    fun aSpaceIndentedFileKeepsSpaces() {
        val (state, _) = editorOf("func main() {\n    println(1)\n}", "go")
        assertEquals("    ", state.indentUnit())
    }

    /** JSON has no `'` pair, and `''` in a `.json` file is not JSON. */
    @Test
    fun aQuoteInJsonDoesNotAutoClose() {
        val (state, buffer) = editorOf("", "json")

        state.typeCharacter("'")

        assertEquals("'", buffer.text)
        assertFalse(state.languageConfig.brackets.any { it.open == "'" || it.open == "`" })
    }

    /** Shell scripts close in front of far fewer characters than Rust. */
    @Test
    fun anOpenerInAShellScriptDoesNotCloseInFrontOfASemicolon() {
        val (state, buffer) = editorOf("foo;", "bash")
        state.setCarets(listOf(Caret(0, 3)), Caret(0, 3))

        state.typeCharacter("(")

        assertEquals("foo(;", buffer.text)
        assertEquals("}])", state.languageConfig.autocloseBefore)
        assertEquals(",]}", EditorLanguage.parse(LanguageFixtures.Json).autocloseBefore)
        assertEquals(";:.,=}])>", EditorLanguage.parse(LanguageFixtures.Rust).autocloseBefore)
    }

    /** Rust's `(` still closes in front of one, which is why it differs. */
    @Test
    fun anOpenerInRustClosesInFrontOfASemicolon() {
        val (state, buffer) = editorOf("foo;", "rust")
        state.setCarets(listOf(Caret(0, 3)), Caret(0, 3))

        state.typeCharacter("(")

        assertEquals("foo();", buffer.text)
    }

    /**
     * "Every occurrence at once" walks each row once; "the next occurrence"
     * ends where it started so the matches before the cursor come last.
     */
    @Test
    fun theSearchWalkVisitsTheStartingRowTwiceOnlyWhenAsked() {
        val (state, _) = editorOf("a a\nb")

        var once = 0
        state.forEachOccurrence("a", wordwise = false, fromRow = 0, revisitFirstRow = false) { _, _, _ ->
            once++
            true
        }
        assertEquals(2, once)

        var twice = 0
        state.forEachOccurrence("a", wordwise = false, fromRow = 0, revisitFirstRow = true) { _, _, _ ->
            twice++
            true
        }
        assertEquals(4, twice)
    }
}
