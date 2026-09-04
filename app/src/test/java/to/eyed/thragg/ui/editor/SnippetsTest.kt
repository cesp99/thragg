package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LSP snippet bodies and the tabstops they carry — the syntax the completion
 * path used to flatten and now drives.
 */
class SnippetsTest {

    @Test
    fun aPlaceholderIsInsertedAndItsRangeIsTheStop() {
        val parsed = parseSnippet("for (\${1:item} in \${2:list}) {\n    \$0\n}")
        assertEquals("for (item in list) {\n    \n}", parsed.text)
        assertEquals(listOf(1, 2, SnippetStop.FINAL), parsed.stops.map { it.index })
        val first = parsed.stops.first()
        assertEquals("item", parsed.text.substring(first.ranges[0].first, first.ranges[0].last + 1))
        val second = parsed.stops[1]
        assertEquals("list", parsed.text.substring(second.ranges[0].first, second.ranges[0].last + 1))
    }

    @Test
    fun aBareStopIsAnEmptyRangeAtItsPlace() {
        val parsed = parseSnippet("println!(\$1);\$0")
        assertEquals("println!();", parsed.text)
        val stop = parsed.stops.first()
        assertEquals(1, stop.index)
        assertEquals(9, stop.ranges.single().first)
        assertTrue(stop.ranges.single().isEmpty())
    }

    @Test
    fun theFinalStopIsVisitedLastWhateverItsPlace() {
        val parsed = parseSnippet("\$0 middle \$2 end \$1")
        assertEquals(listOf(1, 2, SnippetStop.FINAL), parsed.stops.map { it.index })
    }

    @Test
    fun theSameNumberTwiceIsOneStopWithTwoRanges() {
        val parsed = parseSnippet("\${1:name} = \${1:name}")
        assertEquals("name = name", parsed.text)
        val stop = parsed.stops.single()
        assertEquals(2, stop.ranges.size)
        assertEquals(0, stop.ranges[0].first)
        assertEquals(7, stop.ranges[1].first)
    }

    @Test
    fun choicesInsertTheFirstAndAreRemembered() {
        val parsed = parseSnippet("let \${1|mut ,|}x = 1;")
        // The first choice is what goes in, and an empty one is a real
        // alternative — "let x = 1;" is the other reading of this snippet.
        assertEquals("let mut x = 1;", parsed.text)
        assertEquals(listOf("mut ", ""), parsed.stops.single().choices)
    }

    @Test
    fun anEscapedDollarIsALiteralOne() {
        val parsed = parseSnippet("echo \\\$HOME \$1")
        assertEquals("echo \$HOME ", parsed.text)
        assertEquals(listOf(1), parsed.stops.map { it.index })
    }

    @Test
    fun aBodyWithNoStopsIsPlainText() {
        val parsed = parseSnippet("plain text")
        assertEquals("plain text", parsed.text)
        assertTrue(parsed.stops.isEmpty())
        assertEquals("plain text".length, parsed.caret)
    }

    // ---- the user's own snippet files -------------------------------------

    @Test
    fun zedsSnippetFormatIsRead() {
        val json = """
            {
              "Log": {
                "prefix": "log",
                "body": ["console.log($1);", "$0"],
                "description": "Log to the console"
              }
            }
        """.trimIndent()
        val snippets = parseUserSnippets(json)
        assertEquals(1, snippets.size)
        assertEquals("Log", snippets[0].name)
        assertEquals("log", snippets[0].prefix)
        assertEquals("console.log(\$1);\n\$0", snippets[0].body)
        assertEquals("Log to the console", snippets[0].description)
    }

    @Test
    fun anArrayOfPrefixesBecomesOneRowEach() {
        val json = """{"Log": {"prefix": ["log", "cl"], "body": "console.log($1)"}}"""
        assertEquals(listOf("cl", "log"), parseUserSnippets(json).map { it.prefix }.sorted())
    }

    @Test
    fun anEntryWithNoBodyIsDroppedAndBadJsonIsNoSnippets() {
        assertEquals(emptyList<UserSnippet>(), parseUserSnippets("""{"X": {"prefix": "x"}}"""))
        assertEquals(emptyList<UserSnippet>(), parseUserSnippets("not json"))
        assertEquals(emptyList<UserSnippet>(), parseUserSnippets(null))
    }

    // ---- the offsets a session keeps --------------------------------------

    /**
     * Typing into a mirrored stop lengthens every one of its ranges and pushes
     * the stops after it along by all of the carets' contributions.
     */
    @Test
    fun typingIntoAMirroredStopMovesTheLaterStopsByEveryCaret() {
        // `${1:x} = ${1:x}; $2` — two ranges on stop 1, one on stop 2.
        val stopOne = listOf(0L..1L, 4L..5L)
        val stopTwo = listOf(8L..8L)
        // Two carets replaced one byte with three: +2 each, +4 in all.
        val shifted = shiftedOffsets(listOf(stopOne, stopTwo), stopOne, delta = 4L, caretCount = 2)
        // The first range grew; the second moved by one caret and grew.
        assertEquals(0L..3L, shifted[0][0])
        assertEquals(6L..9L, shifted[0][1])
        // The later stop moved by both.
        assertEquals(12L..12L, shifted[1][0])
    }

    @Test
    fun anEditOfNothingLeavesTheOffsetsAlone() {
        val stops = listOf(listOf(0L..1L), listOf(4L..4L))
        assertEquals(stops, shiftedOffsets(stops, stops[0], delta = 0L, caretCount = 1))
    }

    /** A stop *before* the one being typed in never moves. */
    @Test
    fun earlierStopsDoNotMove() {
        val earlier = listOf(0L..1L)
        val current = listOf(4L..5L)
        val shifted = shiftedOffsets(listOf(earlier, current), current, delta = 3L, caretCount = 1)
        assertEquals(earlier, shifted[0])
        assertEquals(listOf(4L..8L), shifted[1])
    }

    // ---- driving one against a real editor --------------------------------

    private fun editorOf(text: String): EditorState = EditorState(FakeEditorBuffer(text))

    @Test
    fun insertingASnippetSelectsTheFirstStopAndTabWalksTheRest() {
        val state = editorOf("")
        val parsed = parseSnippet("fn \${1:name}(\${2:args}) {}\$0")
        state.replaceRange(LspRange(0, 0, 0, 0), parsed.text, 0)
        assertTrue(state.startSnippet(parsed, state.lastReplacementAt))
        // Stop 1 is selected — "name".
        assertEquals("name", state.selectionText())

        assertTrue(state.snippetTab(forward = true))
        assertEquals("args", state.selectionText())

        // The last hop is `$0`, which ends the session.
        assertTrue(state.snippetTab(forward = true))
        assertNull(state.snippet)
    }

    @Test
    fun typingIntoAStopAndTabbingLandsOnTheNextOne() {
        val state = editorOf("")
        val parsed = parseSnippet("\${1:a} + \${2:b}\$0")
        state.replaceRange(LspRange(0, 0, 0, 0), parsed.text, 0)
        state.startSnippet(parsed, state.lastReplacementAt)
        state.typeCharacter("xyz")
        assertEquals("xyz + b", state.line(0))

        assertTrue(state.snippetTab(forward = true))
        assertEquals("b", state.selectionText())
    }

    @Test
    fun escapeEndsTheSessionAndTabIndentsAgain() {
        val state = editorOf("")
        val parsed = parseSnippet("\${1:a} \${2:b}")
        state.replaceRange(LspRange(0, 0, 0, 0), parsed.text, 0)
        state.startSnippet(parsed, state.lastReplacementAt)
        assertTrue(state.endSnippet())
        assertNull(state.snippet)
        // With no session, Tab is the editor's own again.
        assertTrue(!state.snippetTab(forward = true))
    }

    @Test
    fun aBodyWhoseOnlyStopIsTheFinalOneDrivesNothing() {
        val state = editorOf("")
        val parsed = parseSnippet("done\$0")
        state.replaceRange(LspRange(0, 0, 0, 0), parsed.text, 0)
        assertTrue(!state.startSnippet(parsed, state.lastReplacementAt))
        assertNull(state.snippet)
    }
}
