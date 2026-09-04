package to.eyed.thragg.ui.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which fold list answers, and how a caret or a selection picks from it.
 * The arbitration is the README's promise made concrete: the server's
 * ranges first, the syntax tree's next, the indentation walk last — and an
 * empty answer from a source means "it knows nothing", never "nothing folds".
 */
class SyntaxFoldsTest {

    private val tree = listOf(FoldRange(0, 4), FoldRange(1, 2), FoldRange(6, 9))

    @Test
    fun theServersRangesWinWhenItHasAny() {
        val server = listOf(FoldRange(0, 3))
        assertEquals(server, chooseFolds(server, tree, rowCount = 20))
    }

    @Test
    fun anEmptyServerAnswerFallsThroughToTheTree() {
        assertEquals(tree, chooseFolds(emptyList(), tree, rowCount = 20))
        assertEquals(tree, chooseFolds(null, tree, rowCount = 20))
    }

    @Test
    fun nothingKnownMeansWalkTheIndentation() {
        assertNull(chooseFolds(null, null, rowCount = 20))
        assertNull(chooseFolds(emptyList(), emptyList(), rowCount = 20))
    }

    @Test
    fun rangesAreClippedToTheBufferAsItStands() {
        // The tree describes the text as of the last reparse; a fold past
        // the last row would be a crash later.
        assertEquals(listOf(FoldRange(0, 4), FoldRange(1, 2), FoldRange(6, 7)), chooseFolds(null, tree, rowCount = 8))
        assertEquals(listOf(FoldRange(0, 4), FoldRange(1, 2)), chooseFolds(null, tree, rowCount = 7))
        assertEquals(listOf(FoldRange(0, 1)), chooseFolds(null, tree, rowCount = 2))
    }

    @Test
    fun theChipSitsOnTheFoldsStartRow() {
        assertEquals(FoldRange(1, 2), foldStartingAt(tree, 1))
        assertEquals(FoldRange(6, 9), foldStartingAt(tree, 6))
        assertNull(foldStartingAt(tree, 3))
    }

    @Test
    fun theFoldAroundACaretIsTheInnermostOne() {
        // Zed's editor::Fold walks up from the caret's row (fold.rs:195-204).
        assertEquals(FoldRange(1, 2), foldContaining(tree, 2))
        assertEquals(FoldRange(0, 4), foldContaining(tree, 3))
        assertEquals(FoldRange(6, 9), foldContaining(tree, 9))
        assertNull(foldContaining(tree, 5))
    }

    @Test
    fun aSelectionFoldsEveryOutermostBlockStartingInsideIt() {
        assertEquals(listOf(FoldRange(0, 4), FoldRange(6, 9)), foldsWithin(tree, 0..8))
        assertEquals(listOf(FoldRange(1, 2)), foldsWithin(tree, 1..3))
        assertTrue(foldsWithin(tree, 10..12).isEmpty())
    }

    @Test
    fun parsesBothShapesAndDropsDegenerateRanges() {
        assertEquals(
            listOf(FoldRange(0, 2), FoldRange(4, 9)),
            parseFoldRanges("""[{"start_row":4,"end_row":9},{"start_row":0,"end_row":2},{"start_row":5,"end_row":5},{"start_row":-1,"end_row":3}]"""),
        )
        assertEquals(
            listOf(FoldRange(1, 3)),
            parseFoldingRangesPayload(JSONObject("""{"ranges":[{"start_row":1,"end_row":3}]}""")),
        )
        assertTrue(parseFoldRanges("not json").isEmpty())
        assertTrue(parseFoldRanges(null).isEmpty())
    }
}
