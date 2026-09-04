package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold stage of the display map, and its composition with soft wrap:
 * buffer rows → folded rows → wrapped display rows, the same layering as
 * Zed's FoldMap under its WrapMap (crates/editor/src/display_map.rs:24-42).
 */
class FoldDisplayMapTest {

    private class Reader(val lines: MutableList<String>) {
        var rowsRead = 0

        fun read(first: Int, last: Int): List<String> {
            rowsRead += last - first
            return lines.subList(first, last)
        }
    }

    private fun mapOver(reader: Reader, columns: Int = 0): DisplayMap =
        DisplayMap({ reader.lines.size }, reader::read).also { it.configure(columns, 4) }

    @Test
    fun foldingAloneMakesTheMapNonIdentity() {
        val map = mapOver(Reader(MutableList(10) { "line $it" }))
        assertTrue(map.isIdentity)

        map.setFoldedRows(listOf(3..5))
        assertFalse(map.isIdentity)
        // An unvisited block is estimated at one display row per row it does
        // not hide — the hidden rows are known exactly and cost no text — so
        // the height is right before anything is measured, and measuring
        // agrees with it.
        assertEquals(7, map.displayRowCount)
        map.measureWindow(0, 10)
        assertEquals(7, map.displayRowCount)

        map.setFoldedRows(emptyList())
        assertTrue(map.isIdentity)
        assertEquals(10, map.displayRowCount)
    }

    @Test
    fun hiddenRowsHaveNoDisplayRowAndVisibleOnesRenumber() {
        val map = mapOver(Reader(MutableList(10) { "line $it" }))
        map.setFoldedRows(listOf(3..5))

        assertEquals(2, map.displayRowOf(2))
        // The rows after the fold close the gap.
        assertEquals(3, map.displayRowOf(6))
        assertEquals(6, map.displayRowOf(9))

        // Display rows map back to visible rows only.
        assertEquals(2, map.bufferRowOf(2))
        assertEquals(6, map.bufferRowOf(3))
        assertEquals(9, map.bufferRowOf(6))
        // Past the end clamps to the last visible row.
        assertEquals(9, map.bufferRowOf(100))
    }

    @Test
    fun theWindowSkipsTheFoldedRows() {
        val texts = MutableList(8) { "line $it" }
        val map = mapOver(Reader(texts))
        map.setFoldedRows(listOf(2..4))
        val window = DisplayWindow()

        map.fillWindow(window, 0, 5, 0, texts)

        assertEquals(5, window.size)
        assertEquals(listOf(0, 1, 5, 6, 7), (0 until window.size).map(window::bufferRow))
    }

    @Test
    fun foldsComposeWithSoftWrap() {
        val texts = mutableListOf(
            "short one",
            "the quick brown fox jumps over it", // wraps into 4 at ten columns
            "short two",
            "the quick brown fox jumps over it",
            "short 3",
        )
        val map = mapOver(Reader(texts), columns = 10)
        assertEquals(4, map.segmentCountOf(texts[1]))
        map.measureWindow(0, 20)
        assertEquals(11, map.displayRowCount)

        // Fold the first wrapped row away: its four display rows go with it.
        map.setFoldedRows(listOf(1..1))
        map.measureWindow(0, 20)
        assertEquals(7, map.displayRowCount)
        assertEquals(1, map.displayRowOf(2))

        val window = DisplayWindow()
        map.fillWindow(window, 0, 7, 0, texts)
        assertEquals(listOf(0, 2, 3, 3, 3, 3, 4), (0 until window.size).map(window::bufferRow))
        // The surviving wrapped row still wraps.
        assertEquals(listOf(0, 0, 0, 1, 2, 3, 0), (0 until window.size).map(window::segment))
    }

    @Test
    fun aFoldSpanningWholeBlocksMeasuresRight() {
        // Blocks are 64 rows; hide rows 10..200 so several blocks are
        // entirely folded, including some that were only ever estimated.
        val reader = Reader(MutableList(300) { "line $it" })
        val map = mapOver(reader)
        map.setFoldedRows(listOf(10..200))

        // measureWindow must walk past entirely-folded (zero-height) blocks
        // without sticking on them.
        map.measureWindow(0, 300)
        assertEquals(109, map.displayRowCount)
        assertEquals(10, map.displayRowOf(201))
        assertEquals(201, map.bufferRowOf(10))
        assertEquals(299, map.bufferRowOf(108))
    }

    @Test
    fun neighbourVisibleRowQueriesStepOverTheFolds() {
        val map = mapOver(Reader(MutableList(20) { "line $it" }))
        map.setFoldedRows(listOf(3..5, 10..12))

        assertTrue(map.isRowHidden(4))
        assertFalse(map.isRowHidden(2))
        assertFalse(map.isRowHidden(6))

        assertEquals(2, map.prevVisibleRow(5))
        assertEquals(6, map.nextVisibleRow(3))
        assertEquals(7, map.prevVisibleRow(7))
        assertEquals(7, map.nextVisibleRow(7))
        // A fold running to the file's end answers one past it; the caller
        // clamps.
        map.setFoldedRows(listOf(15..19))
        assertEquals(20, map.nextVisibleRow(17))
        assertEquals(14, map.prevVisibleRow(19))
    }

    @Test
    fun aFoldIsInTheDocumentHeightBeforeAnythingIsMeasured() {
        // The estimate is what `maxScrollY`, the scrollbar's thumb and the
        // first frame after a fold all read. A mid-file fold that is not in
        // it is a document that is nine hundred rows too tall for a frame,
        // every time the block is folded and after every edit near it.
        val reader = Reader(MutableList(1_000) { "line $it" })
        val map = mapOver(reader)

        map.setFoldedRows(listOf(11..910))

        assertEquals(100, map.displayRowCount)
        assertEquals("no row of the file was read to know it", 0, reader.rowsRead)

        // And because the estimate is honest, measuring a viewport's worth
        // stops at the viewport instead of walking the whole fold to
        // accumulate forty display rows: two blocks, not a thousand rows.
        map.measureWindow(0, 42)
        assertTrue("read ${reader.rowsRead} rows for 42 display rows", reader.rowsRead <= 128)
        assertEquals(100, map.displayRowCount)
    }

    @Test
    fun aBlockAFoldHidesEntirelyIsNeverRead() {
        val reader = Reader(MutableList(300) { "line $it" })
        val map = mapOver(reader)
        map.setFoldedRows(listOf(10..200))

        map.measureWindow(0, 300)

        assertEquals(109, map.displayRowCount)
        // Blocks 1 and 2 (rows 64..191) are hidden end to end and are worth
        // nothing; there is nothing about them the file could say.
        assertTrue("read ${reader.rowsRead} rows", reader.rowsRead <= 192)
    }

    @Test
    fun theWindowReadsOnlyTheRowsItDraws() {
        // A file that is one folded block: the first row a frame draws and
        // the last are twenty thousand apart, and the window still has to
        // cost the rows on screen.
        val texts = MutableList(20_003) { "line $it" }
        val map = mapOver(Reader(texts))
        map.setFoldedRows(listOf(1..20_000))
        val window = DisplayWindow()
        val asked = ArrayList<Int>()

        map.fillWindow(window, 0, 3, 0) { row ->
            asked.add(row)
            texts[row]
        }

        assertEquals(listOf(0, 20_001, 20_002), (0 until window.size).map(window::bufferRow))
        assertEquals("only the rows on screen were asked for", listOf(0, 20_001, 20_002), asked)
    }

    @Test
    fun refoldingAfterAnEditKeepsTheIndexHonest() {
        val reader = Reader(MutableList(50) { "line $it" })
        val map = mapOver(reader)
        map.setFoldedRows(listOf(5..9))
        map.measureWindow(0, 50)
        assertEquals(45, map.displayRowCount)

        // The editor unfolds on an edit and re-installs the shifted set.
        reader.lines.add(2, "inserted")
        map.setFoldedRows(listOf(6..10))
        assertEquals(11, map.bufferRowOf(6))
        assertEquals(46, map.displayRowCount)
    }
}
