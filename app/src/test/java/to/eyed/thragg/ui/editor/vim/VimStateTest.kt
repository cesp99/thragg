package to.eyed.thragg.ui.editor.vim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.VimClipboard
import to.eyed.thragg.ui.editor.Caret
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.editor.FakeEditorBuffer
import to.eyed.thragg.ui.editor.LanguageFixtures
import to.eyed.thragg.ui.editor.typeCharacter

/**
 * The vim layer's state machine over a fake buffer: counts, operator
 * pending, motions, text objects, registers, dot-repeat, visual modes,
 * search and the `:` line. Each test feeds keystrokes exactly as the pane
 * would — one [VimState.handleKey] per key — and reads the buffer and the
 * caret back.
 *
 * Insert mode is the editor's own typing, so a test that types in insert
 * mode calls [typeCharacter] as the input connection would.
 */
class VimStateTest {

    private class Rig(
        text: String,
        startMode: VimMode = VimMode.Normal,
        clipboard: VimClipboard = VimClipboard.Never,
        language: String? = null,
    ) {
        val buffer = FakeEditorBuffer(text, language, LanguageFixtures.of(language))
        val editor = EditorState(buffer)
        val host = FakeVimHost(buffer)
        val globals = VimGlobals { clipboard }
        val vim = VimState(editor, host, globals, startMode)

        fun keys(vararg keys: String): Rig {
            for (key in keys) vim.handleKey(key)
            return this
        }

        /** Every character of [text] is one keystroke, as a soft keyboard commits them. */
        fun type(text: String): Rig {
            for (c in text) vim.handleKey(c.toString())
            return this
        }

        /** Insert-mode typing goes to the editor, not the layer. */
        fun insert(text: String): Rig {
            for (c in text) editor.typeCharacter(c.toString())
            return this
        }

        fun at(row: Int, col: Int): Rig {
            editor.setCarets(listOf(Caret(row, col)), Caret(row, col))
            return this
        }

        val text: String get() = buffer.text
        val cursor: Pair<Int, Int> get() = vim.cursor().let { it.row to it.col }
    }

    // ---- Modes ---------------------------------------------------------------

    @Test
    fun theLayerStartsInTheConfiguredMode() {
        assertEquals(VimMode.Normal, Rig("x").vim.mode)
        assertEquals(VimMode.Insert, Rig("x", VimMode.Insert).vim.mode)
        assertEquals(VimMode.VisualLine, Rig("x", VimMode.VisualLine).vim.mode)
    }

    @Test
    fun insertAppendAndEscapeMoveBetweenModesAndStepBackOnEscape() {
        val rig = Rig("hello").at(0, 2)
        rig.keys("i")
        assertEquals(VimMode.Insert, rig.vim.mode)
        assertEquals(VimCursorShape.Bar, rig.vim.cursorShape)
        rig.keys("escape")
        assertEquals(VimMode.Normal, rig.vim.mode)
        assertEquals(VimCursorShape.Block, rig.vim.cursorShape)
        // Escape steps back one, as Vim does.
        assertEquals(0 to 1, rig.cursor)

        rig.keys("A").insert("!").keys("escape")
        assertEquals("hello!", rig.text)
        assertEquals(0 to 5, rig.cursor)

        rig.keys("I").insert("> ").keys("escape")
        assertEquals("> hello!", rig.text)
    }

    @Test
    fun openLineBelowAndAboveKeepTheIndent() {
        val rig = Rig("    a\n    b").at(0, 4)
        rig.keys("o").insert("x").keys("escape")
        assertEquals("    a\n    x\n    b", rig.text)
        rig.keys("O").insert("y").keys("escape")
        assertEquals("    a\n    y\n    x\n    b", rig.text)
    }

    @Test
    fun replaceModeOverwritesAndBackspaceRestores() {
        val rig = Rig("abcd").at(0, 1)
        rig.keys("R")
        assertEquals(VimMode.Replace, rig.vim.mode)
        assertEquals(VimCursorShape.Underline, rig.vim.cursorShape)
        rig.type("XY")
        assertEquals("aXYd", rig.text)
        rig.keys("backspace")
        assertEquals("aXcd", rig.text)
        rig.keys("escape")
        assertEquals(VimMode.Normal, rig.vim.mode)
    }

    @Test
    fun theModeIndicatorShowsThePendingCountAndOperator() {
        val rig = Rig("a b c")
        rig.keys("2", "d")
        assertEquals("2d", rig.vim.pendingKeys)
        rig.keys("escape")
        assertEquals("", rig.vim.pendingKeys)
        rig.keys("\"", "a")
        assertEquals("\"a", rig.vim.pendingKeys)
    }

    // ---- Motions and counts -------------------------------------------------------

    @Test
    fun hjklTakeCountsAndClipToTheLine() {
        val rig = Rig("abcdef\nxy\nlonger line").at(0, 0)
        rig.keys("3", "l")
        assertEquals(0 to 3, rig.cursor)
        rig.keys("l", "l", "l", "l")
        assertEquals("l never leaves the last character", 0 to 5, rig.cursor)
        rig.keys("j")
        assertEquals("a short row clips the column", 1 to 1, rig.cursor)
        rig.keys("j")
        assertEquals("but the goal column is remembered", 2 to 5, rig.cursor)
        rig.keys("2", "k", "h")
        assertEquals(0 to 4, rig.cursor)
    }

    @Test
    fun wordMotionsFollowVimsThreeClasses() {
        val rig = Rig("let value = compute(x);").at(0, 0)
        rig.keys("w")
        assertEquals(0 to 4, rig.cursor)
        rig.keys("w")
        assertEquals("= is a word of its own", 0 to 10, rig.cursor)
        rig.keys("2", "w")
        assertEquals("( is punctuation, so it is the stop after compute", 0 to 19, rig.cursor)
        rig.keys("W")
        assertEquals("W skips the whole run", 0 to 22, rig.cursor)
        rig.keys("b")
        assertEquals(0 to 21, rig.cursor)
        rig.keys("0", "e")
        assertEquals(0 to 2, rig.cursor)
        rig.keys("$")
        assertEquals(0 to 22, rig.cursor)
        rig.keys("^")
        assertEquals(0 to 0, rig.cursor)
    }

    @Test
    fun lineMotionsLandOnTheFirstNonBlank() {
        val rig = Rig("one\n  two\n    three\nfour").at(0, 0)
        rig.keys("G")
        assertEquals(3 to 0, rig.cursor)
        rig.keys("g", "g")
        assertEquals(0 to 0, rig.cursor)
        rig.keys("3", "G")
        assertEquals(2 to 4, rig.cursor)
        rig.keys("2", "g", "g")
        assertEquals(1 to 2, rig.cursor)
    }

    @Test
    fun findTillAndTheirRepeats() {
        val rig = Rig("a,b,c,d").at(0, 0)
        rig.keys("f", ",")
        assertEquals(0 to 1, rig.cursor)
        rig.keys(";")
        assertEquals(0 to 3, rig.cursor)
        rig.keys(",")
        assertEquals(0 to 1, rig.cursor)
        rig.keys("t", "d")
        assertEquals("t stops one short", 0 to 5, rig.cursor)
        rig.keys("2", "F", ",")
        assertEquals(0 to 1, rig.cursor)
        // A character that is not there cancels the motion and the count.
        rig.keys("f", "z")
        assertEquals(0 to 1, rig.cursor)
    }

    @Test
    fun percentJumpsBetweenBracketsAcrossLines() {
        val rig = Rig("fn f() {\n  g(1, (2));\n}").at(0, 7)
        rig.keys("%")
        assertEquals(2 to 0, rig.cursor)
        rig.keys("%")
        assertEquals(0 to 7, rig.cursor)
        // From plain text, the first bracket after the cursor on the line.
        rig.at(1, 0).keys("%")
        assertEquals(1 to 10, rig.cursor)
    }

    @Test
    fun paragraphMotionsStopAtBlankLines() {
        val rig = Rig("a\nb\n\nc\nd\n\ne").at(0, 0)
        rig.keys("}")
        assertEquals(2 to 0, rig.cursor)
        rig.keys("}")
        assertEquals(5 to 0, rig.cursor)
        rig.keys("{")
        assertEquals(2 to 0, rig.cursor)
    }

    @Test
    fun arrowKeysAreMotionsToo() {
        val rig = Rig("ab\ncd").at(0, 0)
        rig.keys("right", "down")
        assertEquals(1 to 1, rig.cursor)
        rig.keys("left", "up")
        assertEquals(0 to 0, rig.cursor)
    }

    // ---- Operators ------------------------------------------------------------------

    @Test
    fun deleteWithMotionsAndCounts() {
        val rig = Rig("one two three four").at(0, 0)
        rig.keys("d", "w")
        assertEquals("two three four", rig.text)
        rig.keys("2", "d", "w")
        assertEquals("four", rig.text)
        rig.keys("d", "$")
        assertEquals("", rig.text)
    }

    @Test
    fun dwOnTheLastWordOfALineDoesNotJoinTheNext() {
        val rig = Rig("foo bar\nbaz").at(0, 4)
        rig.keys("d", "w")
        assertEquals("foo \nbaz", rig.text)
    }

    @Test
    fun linewiseOperatorsTakeWholeRows() {
        val rig = Rig("one\ntwo\nthree\nfour").at(1, 1)
        rig.keys("d", "d")
        assertEquals("one\nthree\nfour", rig.text)
        assertEquals(1 to 0, rig.cursor)
        rig.keys("2", "d", "d")
        assertEquals("one", rig.text)
        rig.keys("d", "d")
        assertEquals("", rig.text)
    }

    @Test
    fun dGandDggTakeToTheEnds() {
        val rig = Rig("a\nb\nc\nd").at(1, 0)
        rig.keys("d", "G")
        assertEquals("a", rig.text)
        val rig2 = Rig("a\nb\nc\nd").at(2, 0)
        rig2.keys("d", "g", "g")
        assertEquals("d", rig2.text)
    }

    @Test
    fun changeWordIsChangeToEndOfWord() {
        val rig = Rig("hello world").at(0, 0)
        rig.keys("c", "w").insert("bye").keys("escape")
        assertEquals("bye world", rig.text)
        assertEquals(VimMode.Normal, rig.vim.mode)
    }

    @Test
    fun ccKeepsTheIndentAndCAndDAreToEndOfLine() {
        val rig = Rig("    old text").at(0, 6)
        rig.keys("c", "c").insert("new").keys("escape")
        assertEquals("    new", rig.text)

        val rig2 = Rig("keep this drop that").at(0, 10)
        rig2.keys("D")
        assertEquals("keep this ", rig2.text)
        rig2.at(0, 5).keys("C").insert("it").keys("escape")
        assertEquals("keep it", rig2.text)
    }

    @Test
    fun xXsSAndTildeWorkOnCharacters() {
        val rig = Rig("abcdef").at(0, 2)
        rig.keys("x")
        assertEquals("abdef", rig.text)
        rig.keys("2", "X")
        assertEquals("def", rig.text)
        rig.keys("s").insert("D").keys("escape")
        assertEquals("Def", rig.text)
        rig.keys("~")
        assertEquals("def", rig.text)
        assertEquals("~ advances", 0 to 1, rig.cursor)
        rig.keys("S").insert("all new").keys("escape")
        assertEquals("all new", rig.text)
    }

    @Test
    fun rReplacesUnderTheCursorWithACount() {
        val rig = Rig("abcd").at(0, 1)
        rig.keys("r", "x")
        assertEquals("axcd", rig.text)
        rig.keys("2", "r", "y")
        assertEquals("ayyd", rig.text)
        assertEquals("the cursor ends on the last replaced character", 0 to 2, rig.cursor)
        // A count longer than the line does nothing, as in Vim.
        rig.keys("5", "r", "z")
        assertEquals("ayyd", rig.text)
    }

    @Test
    fun joinLinesWithCountAndWithoutSpaces() {
        val rig = Rig("a\n  b\nc\nd").at(0, 0)
        rig.keys("J")
        assertEquals("a b\nc\nd", rig.text)
        rig.keys("3", "J")
        assertEquals("a b c d", rig.text)
        val rig2 = Rig("a\n  b").at(0, 0)
        rig2.keys("g", "J")
        assertEquals("a  b", rig2.text)
    }

    @Test
    fun shiftAndIndentOperatorsTakeRows() {
        val rig = Rig("a\nb\nc").at(0, 0)
        rig.keys(">", "j")
        assertEquals("    a\n    b\nc", rig.text)
        rig.keys("<", "<")
        assertEquals("a\n    b\nc", rig.text)
        rig.keys("j", "2", ">", ">")
        assertEquals("a\n        b\n    c", rig.text)
        val rig2 = Rig("fn f() {\nx();\n  }").at(0, 0)
        rig2.keys("=", "G")
        assertEquals("fn f() {\n    x();\n}", rig2.text)
    }

    @Test
    fun caseOperatorsTakeMotionsAndLines() {
        val rig = Rig("hello World").at(0, 0)
        rig.keys("g", "U", "i", "w")
        assertEquals("HELLO World", rig.text)
        rig.keys("g", "u", "u")
        assertEquals("hello world", rig.text)
        rig.keys("g", "~", "$")
        assertEquals("HELLO WORLD", rig.text)
    }

    @Test
    fun commentOperatorIsBoundAsZedBindsIt() {
        val rig = Rig("let a = 1;\nlet b = 2;", language = "rust").at(0, 0)
        rig.keys("g", "c", "c")
        assertTrue(rig.text, rig.text.startsWith("//"))
        rig.keys("g", "c", "c")
        assertEquals("let a = 1;\nlet b = 2;", rig.text)
    }

    // ---- Text objects -----------------------------------------------------------------

    @Test
    fun wordObjectsInnerAndAround() {
        val rig = Rig("one two three").at(0, 5)
        rig.keys("d", "i", "w")
        assertEquals("one  three", rig.text)
        rig.at(0, 1).keys("d", "a", "w")
        assertEquals("aw takes all the trailing whitespace", "three", rig.text)
    }

    @Test
    fun bracketObjectsFindTheInnermostPairAcrossLines() {
        val rig = Rig("f(a, (b), c)").at(0, 3)
        rig.keys("d", "i", "(")
        assertEquals("f()", rig.text)
        val rig2 = Rig("f(a, (b), c)").at(0, 6)
        rig2.keys("c", "i", "b").insert("x").keys("escape")
        assertEquals("f(a, (x), c)", rig2.text)
        val rig3 = Rig("if x {\n    a();\n    b();\n}").at(1, 5)
        rig3.keys("d", "i", "{")
        assertEquals("a block on its own lines is taken by lines", "if x {\n}", rig3.text)
        val rig4 = Rig("call[1, 2]").at(0, 6)
        rig4.keys("d", "a", "[")
        assertEquals("call", rig4.text)
    }

    @Test
    fun quoteAndTagAndParagraphObjects() {
        val rig = Rig("say(\"hi there\", 'x')").at(0, 7)
        rig.keys("c", "i", "\"").insert("yo").keys("escape")
        assertEquals("say(\"yo\", 'x')", rig.text)
        rig.at(0, 11).keys("d", "a", "'")
        assertEquals("with nothing after the quote, a' takes the space before it", "say(\"yo\",)", rig.text)

        val tag = Rig("<p>hello <b>bold</b> end</p>").at(0, 13)
        tag.keys("d", "i", "t")
        assertEquals("<p>hello <b></b> end</p>", tag.text)
        tag.at(0, 10).keys("d", "a", "t")
        assertEquals("<p>hello  end</p>", tag.text)

        val para = Rig("a\nb\n\nc").at(0, 0)
        para.keys("d", "a", "p")
        assertEquals("c", para.text)
        val para2 = Rig("a\nb\n\nc").at(1, 0)
        para2.keys("d", "i", "p")
        assertEquals("\nc", para2.text)
    }

    // ---- Registers, yank and put -----------------------------------------------------------

    @Test
    fun yankAndPutLinesAndCharacters() {
        val rig = Rig("one\ntwo").at(0, 0)
        rig.keys("y", "y", "p")
        assertEquals("one\none\ntwo", rig.text)
        assertEquals(1 to 0, rig.cursor)
        rig.keys("P")
        assertEquals("one\none\none\ntwo", rig.text)
        rig.at(0, 0).keys("y", "i", "w", "$", "p")
        assertEquals("oneone\none\none\ntwo", rig.text)
        assertEquals("p lands on the last character put", 0 to 5, rig.cursor)
        rig.keys("2", "p")
        assertEquals("oneoneoneone\none\none\ntwo", rig.text)
    }

    @Test
    fun namedRegistersAndTheYankAndDeleteRegisters() {
        val rig = Rig("alpha beta").at(0, 0)
        rig.keys("\"", "a", "y", "i", "w")
        rig.keys("w", "d", "i", "w")
        assertEquals("alpha ", rig.text)
        rig.keys("\"", "a", "p")
        assertEquals("alpha alpha", rig.text)
        rig.keys("\"", "0", "p")
        assertEquals("0 holds only an unnamed yank, and a delete never fills it", "alpha alpha", rig.text)
        rig.keys("\"", "-", "p")
        assertEquals("- is the last small delete", "alpha alphabeta", rig.text)
        rig.keys("\"", "A", "y", "i", "w")
        rig.keys("$", "\"", "a", "p")
        assertEquals("A appends to a", "alpha alphabetaalphaalphabeta", rig.text)
    }

    @Test
    fun theBlackHoleRegisterLeavesTheOthersAlone() {
        val rig = Rig("keep drop").at(0, 0)
        rig.keys("y", "i", "w", "w", "\"", "_", "d", "i", "w", "p")
        assertEquals("keep keep", rig.text)
    }

    @Test
    fun theClipboardRegistersFollowTheSetting() {
        val always = Rig("copy me", clipboard = VimClipboard.Always).at(0, 0)
        always.keys("y", "i", "w")
        assertEquals("copy", always.host.clipboard)
        always.host.clipboard = "from elsewhere"
        always.keys("$", "p")
        assertEquals("a newer clipboard wins on paste", "copy mefrom elsewhere", always.text)

        val never = Rig("copy me", clipboard = VimClipboard.Never).at(0, 0)
        never.keys("y", "i", "w")
        assertNull(never.host.clipboard)
        never.keys("\"", "+", "y", "i", "w")
        assertEquals("copy", never.host.clipboard)

        val onYank = Rig("copy me", clipboard = VimClipboard.OnYank).at(0, 0)
        onYank.keys("d", "i", "w")
        assertNull("a delete stays in Vim's registers", onYank.host.clipboard)
        onYank.keys("y", "i", "w")
        assertEquals(" ", onYank.host.clipboard)
    }

    // ---- Dot repeat ------------------------------------------------------------------------------

    @Test
    fun dotRepeatsDeletesWithTheirCountsAndANewCountOverrides() {
        val rig = Rig("a b c d e f g").at(0, 0)
        rig.keys("2", "d", "w", ".")
        assertEquals("e f g", rig.text)
        rig.keys("3", "x", ".")
        assertEquals("", rig.text.trim().let { if (it.isEmpty()) "" else it })
    }

    @Test
    fun dotRepeatsAnInsertWithTheTextTyped() {
        val rig = Rig("one two three").at(0, 0)
        rig.keys("c", "i", "w").insert("1").keys("escape")
        assertEquals("1 two three", rig.text)
        rig.keys("w", ".")
        assertEquals("1 1 three", rig.text)
        rig.keys("w", ".")
        assertEquals("1 1 1", rig.text)
        val rig2 = Rig("x").at(0, 0)
        rig2.keys("A").insert("!").keys("escape", ".")
        assertEquals("x!!", rig2.text)
    }

    @Test
    fun dotRepeatsAVisualChangeOverTheSameExtent() {
        val rig = Rig("abcdef").at(0, 0)
        rig.keys("v", "l", "d")
        assertEquals("cdef", rig.text)
        rig.keys(".")
        assertEquals("ef", rig.text)
    }

    // ---- Visual modes ------------------------------------------------------------------------------

    @Test
    fun visualSelectionsIncludeTheCursorAndOperatorsTakeThem() {
        val rig = Rig("hello world").at(0, 0)
        rig.keys("v", "e")
        assertEquals(VimMode.Visual, rig.vim.mode)
        assertEquals("hello", rig.editor.selectionText())
        rig.keys("d")
        assertEquals(" world", rig.text)
        assertEquals(VimMode.Normal, rig.vim.mode)
        rig.keys("v", "i", "w")
        assertEquals("iw on a space is the run of spaces", " ", rig.editor.selectionText())
        rig.keys("escape", "w", "v", "i", "w")
        assertEquals("world", rig.editor.selectionText())
        rig.keys("escape")
        assertEquals(VimMode.Normal, rig.vim.mode)
    }

    @Test
    fun visualLineTakesRowsAndVisualBlockTakesColumns() {
        val rig = Rig("aa\nbb\ncc\ndd").at(1, 1)
        rig.keys("V", "j")
        assertEquals(VimMode.VisualLine, rig.vim.mode)
        rig.keys("y", "G", "p")
        assertEquals("aa\nbb\ncc\ndd\nbb\ncc", rig.text)

        val block = Rig("abc\ndef\nghi").at(0, 1)
        block.keys("ctrl-v", "j", "j", "d")
        assertEquals("ac\ndf\ngi", block.text)
        assertEquals(VimMode.Normal, block.vim.mode)
    }

    @Test
    fun visualBlockInsertTypesOnEveryRow() {
        val rig = Rig("a\nb\nc").at(0, 0)
        rig.keys("ctrl-v", "j", "j", "I").insert("- ").keys("escape")
        assertEquals("- a\n- b\n- c", rig.text)
    }

    @Test
    fun gvReselectsAndOSwapsTheEnds() {
        val rig = Rig("one two").at(0, 0)
        rig.keys("v", "e", "escape")
        assertEquals(VimMode.Normal, rig.vim.mode)
        rig.keys("g", "v")
        assertEquals("one", rig.editor.selectionText())
        rig.keys("o")
        assertEquals(0 to 0, rig.cursor)
        rig.keys("o", "l", "l")
        assertEquals("one t", rig.editor.selectionText())
    }

    @Test
    fun marksJumpAndTheApostropheGoesToTheLine() {
        val rig = Rig("one\n  two\nthree").at(1, 3)
        rig.keys("m", "a", "G")
        assertEquals(2 to 0, rig.cursor)
        rig.keys("'", "a")
        assertEquals(1 to 2, rig.cursor)
        rig.keys("`", "a")
        assertEquals(1 to 3, rig.cursor)
        rig.keys("d", "'", "a")
        assertEquals("d'a is linewise", "one\nthree", rig.text)
    }

    @Test
    fun theQuoteMarkGoesBackToWhereAJumpLeftFrom() {
        val rig = Rig("one\ntwo\nthree\nfour").at(1, 1)
        rig.keys("G")
        assertEquals(3 to 0, rig.cursor)
        rig.keys("'", "'")
        assertEquals(1 to 0, rig.cursor)
        rig.keys("`", "`")
        assertEquals("and `` goes back again, to the exact spot", 3 to 0, rig.cursor)
        // `k` is not a jump and leaves the mark where `` set it: row 1.
        rig.keys("k", "k", "'", "'")
        assertEquals(1 to 0, rig.cursor)
    }

    // ---- Search ---------------------------------------------------------------------------------

    @Test
    fun slashSearchesForwardAndNRepeats() {
        val rig = Rig("foo bar\nfoo baz\nqux foo").at(0, 0)
        rig.keys("/")
        assertEquals('/', rig.vim.commandLine?.prefix)
        rig.type("foo").keys("enter")
        assertNull(rig.vim.commandLine)
        assertEquals(1 to 0, rig.cursor)
        rig.keys("n")
        assertEquals(2 to 4, rig.cursor)
        rig.keys("n")
        assertEquals("wraps", 0 to 0, rig.cursor)
        assertEquals("search hit BOTTOM, continuing at TOP", rig.vim.message)
        rig.keys("N")
        assertEquals(2 to 4, rig.cursor)
        rig.keys("?").type("bar").keys("enter")
        assertEquals(0 to 4, rig.cursor)
    }

    @Test
    fun starSearchesTheWordUnderTheCursorAndAMissReportsIt() {
        val rig = Rig("x = y + x\nx").at(0, 0)
        rig.keys("*")
        assertEquals(0 to 8, rig.cursor)
        rig.keys("#")
        assertEquals(0 to 0, rig.cursor)
        rig.keys("/").type("nothing").keys("enter")
        assertEquals("E486: Pattern not found: nothing", rig.vim.message)
        assertEquals(0 to 0, rig.cursor)
    }

    @Test
    fun anOperatorTakesASearchAsItsMotion() {
        val rig = Rig("delete up to here").at(0, 0)
        rig.keys("d", "/").type("here").keys("enter")
        assertEquals("here", rig.text)
    }

    // ---- The command line ----------------------------------------------------------------------------

    @Test
    fun colonCommandsReachTheHost() {
        val rig = Rig("text").at(0, 0)
        rig.keys(":").type("w").keys("enter")
        rig.keys(":").type("q").keys("enter")
        rig.keys(":").type("q!").keys("enter")
        rig.keys(":").type("wq").keys("enter")
        rig.keys(":").type("x").keys("enter")
        rig.keys(":").type("e src/main.rs").keys("enter")
        assertEquals(listOf("save", "close", "close!", "save+close", "save+close", "open src/main.rs"), rig.host.calls)
        rig.keys("Z", "Z")
        assertEquals("save+close", rig.host.calls.last())
        rig.keys("g", "d")
        assertEquals("definition", rig.host.calls.last())
    }

    @Test
    fun aBareLineNumberJumpsAndSplitsAreRefusedWithASentence() {
        val rig = Rig("a\nb\n  c\nd").at(0, 0)
        rig.keys(":").type("3").keys("enter")
        assertEquals(2 to 2, rig.cursor)
        rig.keys(":").type("$").keys("enter")
        assertEquals(3 to 0, rig.cursor)
        rig.keys(":").type("vsp").keys("enter")
        assertTrue(rig.vim.message.orEmpty(), rig.vim.message.orEmpty().contains("Split panes"))
        rig.keys(":").type("nonsense").keys("enter")
        assertEquals("E492: Not an editor command: nonsense", rig.vim.message)
    }

    @Test
    fun substituteTakesARangeAndTheGFlagInOneEdit() {
        val rig = Rig("a a\na a\na a").at(0, 0)
        rig.keys(":").type("%s/a/b/").keys("enter")
        assertEquals("b a\nb a\nb a", rig.text)
        rig.keys(":").type("2,3s/a/c/g").keys("enter")
        assertEquals("b a\nb c\nb c", rig.text)
        rig.keys(":").type("s/z/y/").keys("enter")
        assertEquals("E486: Pattern not found: z", rig.vim.message)
        val visual = Rig("x\nx\nx").at(0, 0)
        visual.keys("V", "j", ":")
        assertEquals("'<,'>", visual.vim.commandLine?.text)
        visual.type("s/x/y/").keys("enter")
        assertEquals("y\ny\nx", visual.text)
    }

    @Test
    fun rangeCommandsDeleteYankAndShift() {
        val rig = Rig("a\nb\nc\nd").at(0, 0)
        rig.keys(":").type("2,3d").keys("enter")
        assertEquals("a\nd", rig.text)
        rig.keys(":").type("1y").keys("enter")
        rig.keys("G", "p")
        assertEquals("a\nd\na", rig.text)
        rig.keys(":").type("%>").keys("enter")
        assertEquals("    a\n    d\n    a", rig.text)
    }

    @Test
    fun escapeAndBackspaceLeaveTheCommandLine() {
        val rig = Rig("x").at(0, 0)
        rig.keys(":").type("ab").keys("backspace")
        assertEquals("a", rig.vim.commandLine?.text)
        rig.keys("backspace", "backspace")
        assertNull("backspace on an empty line closes it", rig.vim.commandLine)
        rig.keys(":").type("wq").keys("escape")
        assertNull(rig.vim.commandLine)
        assertTrue(rig.host.calls.isEmpty())
    }

    // ---- The soft keyboard and the editor's own moves ------------------------------------------------

    @Test
    fun typedTextFromASoftKeyboardIsKeystrokes() {
        val rig = Rig("one two").at(0, 0)
        rig.vim.handleTyped("dw")
        assertEquals("two", rig.text)
        assertTrue(rig.vim.wantsRawInput)
        rig.vim.handleTyped("i")
        assertEquals(VimMode.Insert, rig.vim.mode)
        assertTrue(!rig.vim.wantsRawInput)
    }

    @Test
    fun aSelectionMadeByTouchBecomesVisualMode() {
        val rig = Rig("hello world").at(0, 0)
        rig.editor.setCarets(listOf(Caret(0, 0, 0, 5)), Caret(0, 0, 0, 5))
        rig.keys("d")
        assertEquals(" world", rig.text)
        assertEquals(VimMode.Normal, rig.vim.mode)
    }

    @Test
    fun unknownChordsFallThroughAndPrintablesAreSwallowed() {
        val rig = Rig("x").at(0, 0)
        assertTrue(!rig.vim.handleKey("ctrl-s"))
        assertTrue(rig.vim.handleKey("q"))
        assertEquals("x", rig.text)
    }
}
