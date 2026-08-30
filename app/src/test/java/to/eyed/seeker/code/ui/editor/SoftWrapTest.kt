package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a line breaks, which is the one piece of the display map that is pure
 * arithmetic and therefore the one piece that can be pinned exactly.
 *
 * Everything above it — the block index, the pane — trusts these breaks to be
 * the same whether they were counted or drawn, so this is where that has to
 * be true.
 */
class SoftWrapTest {

    /** The segments [text] is drawn as, as text, so a failure is readable. */
    private fun segments(text: String, columns: Int, tabSize: Int = 4): List<String> {
        val wrap = SoftWrap.of(text, columns, tabSize)
        return (0 until wrap.segmentCount).map {
            text.substring(wrap.startOf(it), wrap.endOf(it, text.length))
        }
    }

    @Test
    fun aLineThatFitsIsOneSegment() {
        assertEquals(listOf("hello world"), segments("hello world", 20))
        assertEquals(listOf(""), segments("", 20))
    }

    @Test
    fun wrappingOffLeavesEveryLineWhole() {
        val text = "a line far longer than nothing"
        assertEquals(listOf(text), segments(text, 0))
    }

    @Test
    fun breaksLandBetweenWordsNotInsideThem() {
        assertEquals(
            listOf("the quick ", "brown fox ", "jumps"),
            segments("the quick brown fox jumps", 10),
        )
    }

    @Test
    fun aWordWiderThanThePaneBreaksRatherThanLooping() {
        // No candidate to break at, so it breaks hard — and, crucially, keeps
        // advancing: the guard that makes that true is the one that stops the
        // scan spinning in front of a character it cannot fit.
        assertEquals(listOf("aaaaa", "aaaaa", "aaaaa"), segments("a".repeat(15), 5))
    }

    @Test
    fun continuationsLineUpUnderTheLinesOwnIndent() {
        assertEquals(
            listOf("    a bb ", "ccc ", "dddd"),
            segments("    a bb ccc dddd", 10),
        )
        assertEquals(
            "four columns of indent, carried onto every continuation",
            4,
            SoftWrap.of("    a bb ccc dddd", 10, tabSize = 4).indentColumns,
        )
    }

    @Test
    fun anIndentWiderThanHalfThePaneIsCappedThere() {
        // Otherwise a deeply indented line would push its own continuations
        // off the right edge and break after every character.
        val wrap = SoftWrap.of(" ".repeat(40) + "alpha beta gamma delta", 20, tabSize = 4)
        assertEquals(10, wrap.indentColumns)
        assertTrue("still makes progress", wrap.segmentCount in 2..8)
    }

    @Test
    fun aTabIsAsWideAsTheTabSize() {
        assertEquals(8, SoftWrap.indentColumns("\t\tx", tabSize = 4))
        // 9 characters fit in 10 columns; a tab in front of 8 of them does not.
        assertEquals(1, segments("a".repeat(9), 10).size)
        assertEquals(2, segments("\t" + "a".repeat(8), 10, tabSize = 4).size)
        assertEquals(1, segments("\t" + "a".repeat(8), 10, tabSize = 1).size)
    }

    @Test
    fun aWhitespaceOnlyLineHasNoIndentToCarry() {
        assertEquals(0, SoftWrap.indentColumns("      ", tabSize = 4))
    }

    @Test
    fun textWithoutSpacesBreaksBetweenItsCharacters() {
        // CJK is deliberately outside the word set: a script written without
        // spaces can only break between characters, and treating it as one
        // long word would send every paragraph off the right edge.
        assertEquals(
            listOf("你好世界你", "好世界你好", "世界"),
            segments("你好世界你好世界你好世界", 5),
        )
    }

    @Test
    fun punctuationStaysWithTheWordInFrontOfIt() {
        // `is_word_char` keeps `,` `.` `:` on the word they trail, so a break
        // never leaves a comma alone at the head of a line.
        assertEquals(listOf("alpha, ", "beta, ", "gamma"), segments("alpha, beta, gamma", 7))
    }

    @Test
    fun theSegmentOfAColumnIsTheOneItStarts() {
        val wrap = SoftWrap.of("the quick brown fox jumps", 10, tabSize = 4)
        assertEquals("before the first break", 0, wrap.segmentOf(9))
        assertEquals("a break column heads the next segment", 1, wrap.segmentOf(10))
        assertEquals(1, wrap.segmentOf(19))
        assertEquals(2, wrap.segmentOf(20))
        assertEquals("past the end still resolves", 2, wrap.segmentOf(25))
    }

    @Test
    fun countingAndDrawingAgreeOnTheSameLine() {
        // The block index counts segments without collecting the breaks; the
        // draw collects them. A disagreement between the two is a document
        // whose height does not match what is in it.
        val samples = listOf(
            "",
            "short",
            "the quick brown fox jumps over the lazy dog",
            "\t\tdeeply.indented(call, with, arguments)",
            "a".repeat(200),
            "你好世界".repeat(20),
            "   ",
        )
        for (text in samples) {
            for (columns in listOf(5, 10, 17, 40)) {
                val counted = SoftWrap.wrap(text, columns, 4, SoftWrap.indentColumns(text, 4))
                val drawn = SoftWrap.of(text, columns, 4).segmentCount
                assertEquals("$text at $columns", counted, drawn)
            }
        }
    }
}
