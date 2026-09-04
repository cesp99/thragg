package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer row ↔ display row index: that it answers the same in both
 * directions, that it stays right across an edit, and — the part that decides
 * whether the editor still opens a 100k-line file — that answering a question
 * near the end of one does not read the file to get there.
 */
class DisplayMapTest {

    /** Rows the map has asked for, so a test can say what a query cost. */
    private class Reader(val lines: MutableList<String>) {
        var rowsRead = 0

        fun read(first: Int, last: Int): List<String> {
            rowsRead += last - first
            return lines.subList(first, last)
        }
    }

    private fun mapOver(reader: Reader, columns: Int, tabSize: Int = 4): DisplayMap =
        DisplayMap({ reader.lines.size }, reader::read).also { it.configure(columns, tabSize) }

    /** Every display row maps back to the buffer row it was drawn from. */
    private fun assertRoundTrips(map: DisplayMap, rows: Int, texts: List<String>) {
        var display = 0
        for (row in 0 until rows) {
            assertEquals("row $row starts at $display", display, map.displayRowOf(row))
            val segments = map.segmentCountOf(texts[row])
            for (segment in 0 until segments) {
                assertEquals(
                    "display row ${display + segment} belongs to row $row",
                    row,
                    map.bufferRowOf(display + segment),
                )
            }
            display += segments
        }
        assertEquals(display, map.displayRowCount)
    }

    @Test
    fun withWrappingOffTheMapIsTheIdentity() {
        val reader = Reader(MutableList(500) { "line $it, which is quite a long line indeed" })
        val map = mapOver(reader, columns = 0)

        assertTrue(map.isIdentity)
        assertEquals(500, map.displayRowCount)
        assertEquals(311, map.displayRowOf(311))
        assertEquals(311, map.bufferRowOf(311))
        assertEquals("not one row read", 0, reader.rowsRead)
    }

    @Test
    fun aWrappedRowOccupiesEveryDisplayRowItNeeds() {
        val texts = listOf("short", "the quick brown fox jumps over it", "also short")
        val map = mapOver(Reader(texts.toMutableList()), columns = 10)

        // "the quick brown fox jumps over it" at ten columns is four rows.
        assertEquals(4, map.segmentCountOf(texts[1]))
        assertRoundTrips(map, texts.size, texts)
        assertEquals(6, map.displayRowCount)
    }

    @Test
    fun theIndexHoldsAcrossBlockBoundaries() {
        // Blocks are 64 rows, so 400 rows is seven of them and the long rows
        // fall in every one.
        val texts = List(400) { if (it % 7 == 0) "a word ".repeat(9) else "short line $it" }
        val map = mapOver(Reader(texts.toMutableList()), columns = 20)

        assertRoundTrips(map, texts.size, texts)
    }

    @Test
    fun aQueryNearTheEndOfAHugeFileReadsOneBlock() {
        // The whole performance claim in one assertion: 40k rows, and finding
        // out which row display row 39_000 belongs to costs a block of text,
        // not a file of it.
        val reader = Reader(MutableList(40_000) { "a fairly long line of text, number $it" })
        val map = mapOver(reader, columns = 20)

        assertEquals("unvisited blocks are estimated at one row each", 40_000, map.displayRowCount)
        map.bufferRowOf(39_000)
        assertTrue("read ${reader.rowsRead} rows", reader.rowsRead <= 64)

        val afterOneBlock = reader.rowsRead
        map.displayRowOf(39_010)
        assertEquals("the same block again is free", afterOneBlock, reader.rowsRead)
        assertTrue("the document grew as it was measured", map.displayRowCount > 40_000)
    }

    @Test
    fun measuringOnlyEverMakesTheDocumentTaller() {
        val reader = Reader(MutableList(5_000) { "a fairly long line of text, number $it" })
        val map = mapOver(reader, columns = 20)

        var previous = map.displayRowCount
        for (display in listOf(100, 4_000, 2_000, 4_900, 0)) {
            map.bufferRowOf(display)
            val now = map.displayRowCount
            assertTrue("$now < $previous", now >= previous)
            previous = now
        }
    }

    @Test
    fun anEditDropsOnlyTheRowsItRewrote() {
        val reader = Reader(MutableList(400) { "short line $it" })
        val map = mapOver(reader, columns = 20)
        // Measure the first and the last block.
        map.bufferRowOf(0)
        map.bufferRowOf(399)
        val measured = reader.rowsRead

        reader.lines[10] = "a word ".repeat(9)
        map.invalidate(10, 10)
        // Until it is looked at again the block is back to its estimate; the
        // draw pass looks at it on the very next frame, which is why an edit
        // does not visibly resize the document.
        assertEquals(400, map.displayRowCount)
        map.displayRowOf(10)
        assertEquals(400 + map.segmentCountOf(reader.lines[10]) - 1, map.displayRowCount)
        assertEquals("only row 10's own block was re-measured", measured + 64, reader.rowsRead)

        // The last block never changed, so nothing re-read it.
        map.bufferRowOf(map.displayRowCount - 1)
        assertEquals(measured + 64, reader.rowsRead)
    }

    @Test
    fun addingARowShiftsEverythingAfterItAndKeepsWhatIsInFront() {
        val reader = Reader(MutableList(400) { "short line $it" })
        val map = mapOver(reader, columns = 20)
        map.bufferRowOf(0)
        map.bufferRowOf(399)
        val measured = reader.rowsRead

        reader.lines.add(300, "a word ".repeat(9))
        map.invalidate(300, 300)
        val extra = map.segmentCountOf(reader.lines[300]) - 1

        assertRoundTrips(map, reader.lines.size, reader.lines)
        assertEquals(401 + extra, map.displayRowCount)
        assertTrue("the first block was kept", reader.rowsRead < measured + 401)
    }

    @Test
    fun aShorterFileForgetsTheRowsItLost() {
        val reader = Reader(MutableList(200) { "short line $it" })
        val map = mapOver(reader, columns = 20)
        map.bufferRowOf(199)

        repeat(150) { reader.lines.removeAt(reader.lines.size - 1) }
        map.invalidate(50, 199)

        assertEquals(50, map.displayRowCount)
        assertEquals(49, map.bufferRowOf(1_000))
    }

    @Test
    fun changingTheWrapWidthRemeasuresEverything() {
        val reader = Reader(MutableList(100) { "a word ".repeat(9) })
        val map = mapOver(reader, columns = 20)
        val atTwenty = map.let { it.bufferRowOf(99); it.displayRowCount }

        map.configure(40, 4)
        map.bufferRowOf(99)
        assertTrue("wider means fewer rows", map.displayRowCount < atTwenty)

        map.configure(0, 4)
        assertTrue(map.isIdentity)
        assertEquals(100, map.displayRowCount)
    }

    @Test
    fun theWindowIsFilledWithTheRowsOnScreen() {
        val texts = listOf("one", "the quick brown fox jumps over it", "three")
        val map = mapOver(Reader(texts.toMutableList()), columns = 10)
        val window = DisplayWindow()

        map.fillWindow(window, 0, 6, 0, texts)

        assertEquals(6, window.size)
        assertEquals(listOf(0, 1, 1, 1, 1, 2), (0 until window.size).map(window::bufferRow))
        assertEquals(listOf(0, 0, 1, 2, 3, 0), (0 until window.size).map(window::segment))
        assertTrue("only the first segment carries the line number", window.isFirstSegment(1))
        assertTrue(!window.isFirstSegment(2))
        assertEquals("a whole row runs to the end", Int.MAX_VALUE, window.endCol(0))
        assertEquals(
            "the segments tile the row",
            texts[1],
            (1..4).joinToString("") { i ->
                texts[1].substring(
                    window.startCol(i),
                    minOf(window.endCol(i), texts[1].length),
                )
            },
        )
        assertEquals(1, window.firstIndexOf(1))
        assertEquals(5, window.firstIndexOf(2))
        assertEquals(-1, window.firstIndexOf(9))
        assertEquals("the caret's column picks its segment", 2, window.indexOf(1, 11))
    }

    @Test
    fun aWindowThatStartsMidRowStillDrawsFromTheRightSegment() {
        val texts = listOf("one", "the quick brown fox jumps over it", "three")
        val map = mapOver(Reader(texts.toMutableList()), columns = 10)
        val window = DisplayWindow()

        // Display rows 3 and 4 are the third and fourth segments of row 1.
        map.fillWindow(window, 3, 5, 1, texts.subList(1, texts.size))

        assertEquals(2, window.size)
        assertEquals(1, window.bufferRow(0))
        assertEquals(2, window.segment(0))
        assertEquals(3, window.segment(1))
    }

    @Test
    fun drawingIntoALongLineDoesNotScanItAgain() {
        // A minified file: one line of 53,780 characters. Filling forty
        // display rows six hundred rows into it must cost forty rows of
        // work, not a scan of the line — sixty times a second, on a phone.
        val line = "a1b2c3d4e5,".repeat(4_889) + "a"
        val texts = mutableListOf(line, "after it")
        val map = mapOver(Reader(texts), columns = 80)
        val window = DisplayWindow()

        map.fillWindow(window, 600, 640, 0, texts)
        val scanned = map.wrapScans
        assertTrue("the line was never scanned", scanned > 0)

        map.fillWindow(window, 600, 640, 0, texts)
        assertEquals(40, window.size)
        assertEquals(0, window.bufferRow(0))
        assertEquals(600, window.segment(0))
        assertEquals("the window is the segments it was asked for", 639, window.segment(39))

        map.fillWindow(window, 601, 641, 0, texts)
        assertEquals("the frames after the first scan nothing", scanned, map.wrapScans)

        // The caret, the handles and `positionAt` ask the same question the
        // draw pass just answered, and get the same answer back.
        assertSame(map.wrapOf(line), map.wrapOf(line))
        assertEquals(scanned, map.wrapScans)
    }

    @Test
    fun theSegmentsOfALongLineStillTileIt() {
        // The window opens mid-line now instead of walking to it; the
        // segments either side of the seam still have to add up to the row.
        val line = "a1b2c3d4e5,".repeat(200)
        val texts = mutableListOf(line)
        val map = mapOver(Reader(texts), columns = 80)
        val window = DisplayWindow()
        val segments = map.segmentCountOf(line)

        map.fillWindow(window, 0, segments, 0, texts)
        val whole = (0 until window.size).joinToString("") {
            line.substring(window.startCol(it), minOf(window.endCol(it), line.length))
        }
        assertEquals(line, whole)

        // The same rows, asked for one window at a time.
        for (start in 0 until segments) {
            map.fillWindow(window, start, start + 1, 0, texts)
            assertEquals(1, window.size)
            assertEquals(start, window.segment(0))
            assertEquals(0, window.bufferRow(0))
        }
    }

    @Test
    fun withWrappingOffTheWindowIsJustTheRows() {
        val map = mapOver(Reader(MutableList(100) { "line $it" }), columns = 0)
        val window = DisplayWindow()

        map.fillWindow(window, 40, 45, 40, emptyList())

        assertEquals(5, window.size)
        assertEquals(40, window.bufferRow(0))
        assertEquals(44, window.bufferRow(4))
        assertEquals(0, window.indentColumns(4))
    }
}
