package to.eyed.seeker.code.ui.editor

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of Zed's line and text manipulations — the sorts, the
 * filters, the case conversions and the reflow. Each is a function over
 * strings, which is what makes it testable here rather than on a phone.
 */
class LineTransformsTest {

    @Test
    fun sortIsPlainAndTheCaseInsensitiveOneIsStable() {
        val lines = listOf("beta", "Alpha", "alpha", "Beta")
        assertEquals(listOf("Alpha", "Beta", "alpha", "beta"), LineTransforms.sort(lines))
        // Equal-but-for-case lines keep the order they were written in.
        assertEquals(
            listOf("Alpha", "alpha", "beta", "Beta"),
            LineTransforms.sortCaseInsensitive(lines),
        )
    }

    @Test
    fun uniqueKeepsTheFirstOfEachAndLeavesTheOrderAlone() {
        val lines = listOf("b", "a", "b", "A", "a")
        assertEquals(listOf("b", "a", "A"), LineTransforms.unique(lines))
        assertEquals(listOf("b", "a"), LineTransforms.uniqueCaseInsensitive(lines))
    }

    @Test
    fun reverseAndShuffleKeepEveryLine() {
        val lines = listOf("one", "two", "three", "four")
        assertEquals(listOf("four", "three", "two", "one"), LineTransforms.reverse(lines))
        val shuffled = LineTransforms.shuffle(lines, Random(7))
        assertEquals(lines.sorted(), shuffled.sorted())
        assertEquals(lines.size, shuffled.size)
    }

    @Test
    fun oppositeCaseFlipsEveryCharacter() {
        assertEquals("FOObar_123", LineTransforms.oppositeCase("fooBAR_123"))
    }

    @Test
    fun identifierWordsSplitOnEveryBoundaryConvertCaseUses() {
        assertEquals(listOf("foo", "Bar"), LineTransforms.identifierWords("fooBar"))
        assertEquals(listOf("foo", "bar"), LineTransforms.identifierWords("foo_bar"))
        assertEquals(listOf("foo", "bar"), LineTransforms.identifierWords("foo-bar"))
        // The acronym break: the last capital of a run opens the next word.
        assertEquals(listOf("HTTP", "Response"), LineTransforms.identifierWords("HTTPResponse"))
        // Letters and digits are separate runs.
        assertEquals(listOf("utf", "8", "Text"), LineTransforms.identifierWords("utf8Text"))
    }

    @Test
    fun theCaseConversionsMatchZedsNames() {
        val source = "someHTTPValue"
        // Title case lowercases the rest of each word, as convert_case does:
        // the acronym is a word, and "HTTP" becomes "Http".
        assertEquals("Some Http Value", LineTransforms.convertCase(source, LineTransforms.Case.Title))
        assertEquals("some_http_value", LineTransforms.convertCase(source, LineTransforms.Case.Snake))
        assertEquals("some-http-value", LineTransforms.convertCase(source, LineTransforms.Case.Kebab))
        assertEquals(
            "SomeHttpValue",
            LineTransforms.convertCase(source, LineTransforms.Case.UpperCamel),
        )
        assertEquals(
            "someHttpValue",
            LineTransforms.convertCase(source, LineTransforms.Case.LowerCamel),
        )
    }

    /** Zed keeps the indent and the trailing space around what it converts. */
    @Test
    fun caseConversionLeavesTheSurroundingWhitespaceAlone() {
        assertEquals(
            "    some_value  ",
            LineTransforms.convertCase("    someValue  ", LineTransforms.Case.Snake),
        )
    }

    @Test
    fun rewrapReflowsToTheColumnAndKeepsThePrefix() {
        val lines = listOf(
            "// one two three four five",
            "// six seven eight nine",
        )
        val wrapped = LineTransforms.rewrap(lines, "// ", 20)
        assertTrue(wrapped.all { it.startsWith("// ") })
        assertTrue(wrapped.all { it.length <= 20 })
        // Nothing is lost and nothing is invented.
        assertEquals(
            lines.joinToString(" ").replace("// ", "").trim(),
            wrapped.joinToString(" ").replace("// ", "").trim(),
        )
    }

    @Test
    fun rewrapJoinsAParagraphThatNowFits() {
        val lines = listOf("one", "two", "three")
        assertEquals(listOf("one two three"), LineTransforms.rewrap(lines, "", 80))
    }

    /** A word longer than the column still gets a line; it is not chopped. */
    @Test
    fun rewrapNeverSplitsAWord() {
        val long = "supercalifragilistic"
        assertEquals(listOf(long), LineTransforms.rewrap(listOf(long), "", 5))
    }

    @Test
    fun rewrapPrefixTakesTheIndentAndTheCommentMarker() {
        assertEquals("    // ", LineTransforms.rewrapPrefix("    // hello", listOf("// ")))
        // A marker written without its space still wraps with one.
        assertEquals("  # ", LineTransforms.rewrapPrefix("  # hello", listOf("#")))
        // No marker: the indent alone.
        assertEquals("  ", LineTransforms.rewrapPrefix("  hello", listOf("// ")))
    }
}
