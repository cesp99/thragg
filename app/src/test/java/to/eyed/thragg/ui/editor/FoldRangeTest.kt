package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The indent arithmetic behind folding, over plain strings — the port of
 * Zed's `starts_indent` and `crease_for_buffer_row`
 * (crates/editor/src/display_map.rs:2268-2430), held to what those actually
 * do rather than to what a folding feature usually does.
 */
class FoldRangeTest {

    private fun rangeAt(row: Int, vararg lines: String, brackets: Boolean = true): FoldRange? =
        IndentFolds.rangeAt(lines.size, row, { lines[it] }) { content ->
            brackets && (
                content.startsWith("}") || content.startsWith(")") || content.startsWith("]")
                )
        }

    private fun startsIndent(row: Int, vararg lines: String): Boolean =
        IndentFolds.startsIndent(lines.size, row, { lines[it] })

    @Test
    fun indentIsTheRawCharacterCountLikeZedsRawLen() {
        // Zed compares `LineIndent::raw_len` — tabs and spaces *counted*,
        // not expanded (crates/text/src/text.rs:706-708). A tab is one.
        assertEquals(0, IndentFolds.indentOf("fn main() {"))
        assertEquals(4, IndentFolds.indentOf("    body"))
        assertEquals(1, IndentFolds.indentOf("\tbody"))
        assertEquals(3, IndentFolds.indentOf("\t\t body"))
        assertEquals(2, IndentFolds.indentOf("  "))
    }

    @Test
    fun aRowStartsAnIndentWhenItsNextNonBlankRowIsDeeper() {
        assertTrue(startsIndent(0, "fn main() {", "    body", "}"))
        // Blank rows between do not break the answer.
        assertTrue(startsIndent(0, "def f():", "", "    body"))
        // A next row at the same depth means no block hangs here.
        assertFalse(startsIndent(0, "let a = 1", "let b = 2"))
        // A blank row starts nothing, and neither does the last row.
        assertFalse(startsIndent(1, "def f():", "", "    body"))
        assertFalse(startsIndent(2, "def f():", "    body", "    more"))
    }

    @Test
    fun aBracketBlockFoldsItsBodyAndLeavesTheClosingRow() {
        val range = rangeAt(0, "fn main() {", "    one", "    two", "}")
        // Rows 1..2 hide; the `}` row closes the block and stays visible.
        assertEquals(FoldRange(0, 2), range)
    }

    @Test
    fun blankRowsBeforeAClosingBracketFoldAwayWithTheBlock() {
        // Zed ends a bracket-closed fold at the closing row's indent
        // (display_map.rs:2408-2412), so the blank row before the `}` is
        // inside it.
        val range = rangeAt(0, "fn main() {", "    one", "", "}")
        assertEquals(FoldRange(0, 2), range)
    }

    @Test
    fun trailingBlanksStayOutsideAnIndentOnlyBlock() {
        // No closing bracket: the fold ends on the last non-blank row
        // (`last_non_blank_row`, display_map.rs:2400-2417).
        val range = rangeAt(0, "def f():", "    one", "", "def g():", "    two")
        assertEquals(FoldRange(0, 1), range)
    }

    @Test
    fun aBlockAtTheEndOfTheFileFoldsToItsLastNonBlankRow() {
        val range = rangeAt(0, "def f():", "    one", "    two", "")
        assertEquals(FoldRange(0, 2), range)
    }

    @Test
    fun nestedBlocksEachGetTheirOwnRange() {
        val lines = arrayOf(
            "fn outer() {", //      0
            "    if x {", //        1
            "        inner", //     2
            "    }", //             3
            "    after", //         4
            "}", //                 5
        )
        assertEquals(FoldRange(0, 4), rangeAt(0, *lines))
        assertEquals(FoldRange(1, 2), rangeAt(1, *lines))
        assertNull(rangeAt(2, *lines))
        assertNull(rangeAt(3, *lines))
    }

    @Test
    fun rowsThatHangNothingFoldNothing() {
        assertNull(rangeAt(0, "flat", "also flat"))
        // The last row of the file can never fold.
        assertNull(rangeAt(1, "def f():", "    body"))
    }

    @Test
    fun withoutBracketKnowledgeTheClosingRowJustEndsTheFold() {
        // A language config with no pairs (the empty config): the `}` row is
        // an ordinary shallower row, so the blanks in front of it stay out.
        val range = rangeAt(0, "fn main() {", "    one", "", "}", brackets = false)
        assertEquals(FoldRange(0, 1), range)
    }

    @Test
    fun theChevronsBoundedScanStillSeesThroughNearbyBlanks() {
        val lines = Array(40) { if (it == 0) "def f():" else if (it == 39) "    body" else "" }
        assertTrue(
            IndentFolds.startsIndent(lines.size, 0, { lines[it] }, scanLimit = 64),
        )
        assertFalse(
            "past the limit the chevron gives up, the fold command does not",
            IndentFolds.startsIndent(lines.size, 0, { lines[it] }, scanLimit = 8),
        )
    }
}
