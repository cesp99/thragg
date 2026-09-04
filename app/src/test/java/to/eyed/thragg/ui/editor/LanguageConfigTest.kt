package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the editor does with a language config once it has one: the `not_in`
 * scopes it now honours, the comment tokens it now reads, the indent patterns
 * that replaced the hardcoded "Python's colon", and the multi-character
 * openers the old table could not express at all.
 *
 * The configs themselves are [LanguageFixtures] — copies of what the engine
 * emits, whose fidelity to `config.toml` is proved in Rust.
 */
class LanguageConfigTest {

    private fun editorOf(text: String, language: String): Pair<EditorState, FakeEditorBuffer> {
        val buffer = FakeEditorBuffer(text, language, LanguageFixtures.of(language))
        return EditorState(buffer) to buffer
    }

    // ---- not_in ----------------------------------------------------------

    /**
     * The case the old table could not answer at all: Go's `"` pair carries
     * `not_in = ["comment", "string"]`, so a quote typed inside a comment is
     * a lone quote.
     */
    @Test
    fun aQuoteInsideACommentDoesNotBringACloser() {
        val (state, buffer) = editorOf("// say hello\n", "go")
        // The engine reports the whole comment as a place no pair is live.
        buffer.deadZones.add(0L..12L)
        state.setCarets(listOf(Caret(0, 12)), Caret(0, 12))

        state.typeCharacter("\"")

        assertEquals("// say hello\"\n", buffer.text)
        assertEquals(1, buffer.scopeQueries)
    }

    /** Outside it, the same keystroke closes as it always did. */
    @Test
    fun theSameQuoteInCodeStillCloses() {
        val (state, buffer) = editorOf("x := \n", "go")
        state.setCarets(listOf(Caret(0, 5)), Caret(0, 5))

        state.typeCharacter("\"")

        assertEquals("x := \"\"\n", buffer.text)
        assertEquals(6, state.cursorCol)
    }

    /**
     * `not_in` governs opening, not stepping over — Zed applies it to the
     * opening half alone. Inside a string, the `"` that ends it must still be
     * stepped over rather than doubled.
     */
    @Test
    fun aQuoteInsideAStringStillStepsOverItsCloser() {
        val (state, buffer) = editorOf("x := \"ab\"\n", "go")
        buffer.deadZones.add(6L..8L)
        state.setCarets(listOf(Caret(0, 8)), Caret(0, 8))

        state.typeCharacter("\"")

        assertEquals("x := \"ab\"\n", buffer.text)
        assertEquals(9, state.cursorCol)
    }

    /**
     * A brace has no `not_in` in any config we carry, so the editor never
     * crosses the bridge to ask about one — which is what keeps the scope
     * question off the ordinary typing path.
     */
    @Test
    fun aPlainBracketNeverAsksTheEngineForItsScope() {
        val (state, buffer) = editorOf("fn f() \n", "rust")
        buffer.deadZones.add(0L..100L)
        state.setCarets(listOf(Caret(0, 7)), Caret(0, 7))

        state.typeCharacter("{")

        // The dead zone covers the whole buffer, and the brace closed anyway
        // — because nothing asked.
        assertEquals("fn f() {}\n", buffer.text)
        assertEquals(0, buffer.scopeQueries)
    }

    /**
     * Nor does a `*` in Rust, even though the block-comment pair starts with
     * one: without the `/` in front of it, that pair cannot open here, so
     * there is nothing to ask about.
     */
    @Test
    fun aMultiplicationInRustNeverAsksEither() {
        val (state, buffer) = editorOf("let n = a \n", "rust")
        state.setCarets(listOf(Caret(0, 10)), Caret(0, 10))

        state.typeCharacter("*")

        assertEquals("let n = a *\n", buffer.text)
        assertEquals(0, buffer.scopeQueries)
    }

    /** With the `/` there, it does — and the pair opens. */
    @Test
    fun aBlockCommentOpenerClosesItself() {
        val (state, buffer) = editorOf("let n = 1; /\n", "rust")
        state.setCarets(listOf(Caret(0, 12)), Caret(0, 12))

        state.typeCharacter("*")

        assertEquals("let n = 1; /* */\n", buffer.text)
        assertEquals(1, buffer.scopeQueries)
    }

    /** One call for every caret, not one per caret. */
    @Test
    fun oneScopeQueryServesEveryCaret() {
        val (state, buffer) = editorOf("a := \nb := \nc := \n", "go")
        state.setCarets(
            listOf(Caret(0, 5), Caret(1, 5), Caret(2, 5)),
            Caret(0, 5),
        )

        state.typeCharacter("\"")

        assertEquals("a := \"\"\nb := \"\"\nc := \"\"\n", buffer.text)
        assertEquals(1, buffer.scopeQueries)
    }

    // ---- Comments --------------------------------------------------------

    /** Markdown has no line comment; its block comment is what wraps a row. */
    @Test
    fun markdownTogglesItsBlockComment() {
        val (state, buffer) = editorOf("# Title\nbody\n", "markdown")
        state.setCarets(listOf(Caret(1, 2)), Caret(1, 2))

        assertTrue(state.toggleComment())
        assertEquals("# Title\n<!-- body -->\n", buffer.text)

        assertTrue(state.toggleComment())
        assertEquals("# Title\nbody\n", buffer.text)
    }

    /** CSS is the other one, and its delimiters are the C-style pair. */
    @Test
    fun cssTogglesItsBlockCommentAcrossASelection() {
        val (state, buffer) = editorOf("a {\n  color: red;\n}\n", "css")
        state.setCarets(listOf(Caret(0, 0, 2, 1)), Caret(0, 0, 2, 1))

        assertTrue(state.toggleComment())
        assertEquals("/* a {\n  color: red;\n} */\n", buffer.text)

        assertTrue(state.toggleComment())
        assertEquals("a {\n  color: red;\n}\n", buffer.text)
    }

    /** A hand-written comment with no spaces still uncomments. */
    @Test
    fun aBlockCommentWithoutSpacesUncommentsToo() {
        val (state, buffer) = editorOf("<!--body-->\n", "markdown")
        state.setCarets(listOf(Caret(0, 6)), Caret(0, 6))

        assertTrue(state.toggleComment())
        assertEquals("body\n", buffer.text)
    }

    /** On an empty row there is nothing to wrap, so the caret goes inside. */
    @Test
    fun aBlockCommentOnAnEmptyRowLeavesTheCaretInside() {
        val (state, buffer) = editorOf("# Title\n\n", "markdown")
        state.setCarets(listOf(Caret(1, 0)), Caret(1, 0))

        assertTrue(state.toggleComment())
        assertEquals("# Title\n<!--  -->\n", buffer.text)
        assertEquals(5, state.cursorCol)
    }

    /** A diff has neither kind, so the command does nothing rather than damage. */
    @Test
    fun aDiffHasNoCommentToToggle() {
        val (state, buffer) = editorOf("--- a/x\n+++ b/x\n", "diff")
        state.setCarets(listOf(Caret(0, 0)), Caret(0, 0))

        assertFalse(state.toggleComment())
        assertEquals("--- a/x\n+++ b/x\n", buffer.text)
    }

    /** Rust's first `line_comments` entry wins, as it does in Zed. */
    @Test
    fun rustCommentsWithTheFirstOfItsThreeTokens() {
        val (state, buffer) = editorOf("let x = 1;\n", "rust")
        state.setCarets(listOf(Caret(0, 0)), Caret(0, 0))

        assertTrue(state.toggleComment())
        assertEquals("// let x = 1;\n", buffer.text)
        assertEquals(listOf("// ", "/// ", "//! "), state.languageConfig.lineComments)
    }

    // ---- Indent patterns -------------------------------------------------

    /** Shell scripts have an indent pattern; nothing but Python used to. */
    @Test
    fun enterAfterAShellThenIndents() {
        val (state, buffer) = editorOf("if [ -d x ]; then\nfi\n", "bash")
        state.setCarets(listOf(Caret(0, 17)), Caret(0, 17))

        state.insertNewline()

        assertEquals("if [ -d x ]; then\n    \nfi\n", buffer.text)
    }

    /** And a word that merely ends in one of those keywords does not. */
    @Test
    fun enterAfterAWordEndingInAKeywordDoesNotIndent() {
        val (state, buffer) = editorOf("echo begin\n", "bash")
        state.setCarets(listOf(Caret(0, 10)), Caret(0, 10))

        state.insertNewline()

        assertEquals("echo begin\n\n", buffer.text)
    }

    /** YAML's, which had no rule at all before. */
    @Test
    fun enterAfterAYamlKeyIndents() {
        val (state, buffer) = editorOf("steps:\n", "yaml")
        state.setCarets(listOf(Caret(0, 6)), Caret(0, 6))

        state.insertNewline()

        // Four spaces: the width is the `tab_size` setting, not the
        // language's — see EditorState.tabSize.
        assertEquals("steps:\n    \n", buffer.text)
    }

    /** A YAML value on the same line does not open one. */
    @Test
    fun enterAfterAYamlValueDoesNot() {
        val (state, buffer) = editorOf("name: thragg\n", "yaml")
        state.setCarets(listOf(Caret(0, 14)), Caret(0, 14))

        state.insertNewline()

        assertEquals("name: thragg\n\n", buffer.text)
    }

    /** Rust has no pattern; its braces carry `newline` instead. */
    @Test
    fun rustHasNoIndentPatternAndDoesNotNeedOne() {
        val config = EditorLanguage.parse(LanguageFixtures.Rust)
        assertNull(config.increaseIndentPattern)
        assertFalse(config.opensBlock("fn main() {"))
        assertTrue(config.openerBefore("fn main() {")!!.newline)
    }

    // ---- Multi-character openers -----------------------------------------

    /** Python's `f"` is a pair of its own; a bare `"` is not the same one. */
    @Test
    fun pythonFStringsCloseAsOnePair() {
        val (state, buffer) = editorOf("x = f\n", "python")
        state.setCarets(listOf(Caret(0, 5)), Caret(0, 5))

        state.typeCharacter("\"")

        assertEquals("x = f\"\"\n", buffer.text)
        assertEquals(6, state.cursorCol)
    }

    /**
     * The word rule that keeps a quote from closing after `don't` must not
     * also break `f"`, whose halves differ.
     */
    @Test
    fun anApostropheAfterAWordStillDoesNotClose() {
        val (state, buffer) = editorOf("dont\n", "python")
        state.setCarets(listOf(Caret(0, 3)), Caret(0, 3))

        state.typeCharacter("'")

        assertEquals("don't\n", buffer.text)
    }

    // ---- Parsing ---------------------------------------------------------

    /**
     * A language the engine does not carry keeps its brackets and loses
     * everything a grammar would have had to tell us.
     *
     * "No grammar" is the common case rather than the exotic one — `.kt`,
     * `.java`, `.toml`, `.xml` and plain text all land here — so taking
     * auto-close away from those files to be principled about it would make
     * the editor worse at its main job in an IDE for Android.
     */
    @Test
    fun anUnknownLanguageKeepsItsBracketsAndNothingElse() {
        val config = EditorLanguage.parse(null)
        assertEquals(EditorLanguage.None, config)
        assertNull(config.lineComment)
        assertTrue("a bracket pairs in every language", config.isPairCharacter("("))
        assertTrue(config.isPairCharacter("\""))
        assertTrue("nothing to indent by, with no grammar", config.increaseIndentPattern == null)
    }

    /** And so does one whose JSON the bridge could not produce. */
    @Test
    fun brokenJsonIsNotFatal() {
        assertEquals(EditorLanguage.None, EditorLanguage.parse("{ not json"))
    }

    /** Every flag the old table dropped survives the round trip. */
    @Test
    fun theConfigCarriesEveryFlag() {
        val rust = EditorLanguage.parse(LanguageFixtures.Rust)
        val angle = rust.brackets.first { it.open == "<" }
        assertFalse(angle.autoClose)
        assertTrue(angle.surround)
        assertTrue(angle.newline)
        assertEquals(listOf("string", "comment"), angle.notIn)

        val quote = rust.brackets.first { it.open == "\"" }
        assertTrue(quote.autoClose)
        assertFalse(quote.newline)
        assertEquals(listOf("string"), quote.notIn)

        assertEquals("Rust", rust.name)
        assertEquals("/*", rust.blockComment!!.start)
        assertEquals("*/", rust.blockComment!!.end)
    }
}
