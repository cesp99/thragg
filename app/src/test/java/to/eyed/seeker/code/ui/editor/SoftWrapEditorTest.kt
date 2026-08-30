package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What soft wrap changes about the pane itself: which way the arrows move,
 * how far a page goes, where the viewport scrolls to, and — the part that
 * would be a silent corruption rather than a visible bug — that none of it
 * changes which bytes an edit touches.
 */
class SoftWrapEditorTest {

    /**
     * A pane [columns] characters wide. The width is worked back from the
     * pane's own arithmetic: a 10px character, a gutter of four digits plus
     * Zed's seven characters of padding, and the scrollbar's track.
     */
    private fun editorOf(
        text: String,
        columns: Int,
        viewportRows: Int = 10,
        wrap: Boolean = true,
    ): EditorState {
        val state = EditorState(FakeEditorBuffer(text))
        state.softWrap = if (wrap) SoftWrapMode.EditorWidth else SoftWrapMode.None
        state.updateMetrics(
            lineHeight = 10f,
            charWidth = 10f,
            gutterPadding = 0f,
            textPadding = 0f,
        )
        state.updateViewport(width = columns * 10f + 120f, height = viewportRows * 10f)
        return state
    }

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    /**
     * Look at every row, which is what scrolling through the file does.
     *
     * The map measures a block the first time a query lands in it and
     * estimates the rest at one display row per file row, so a test that
     * wants the document's true height has to have looked at it — exactly as
     * the draw pass does before it reads the scroll extent.
     */
    private fun EditorState.measureWholeFile(): EditorState = apply {
        for (row in 0 until lineCount) displayMap.displayRowOf(row)
    }

    /**
     * What the pane's draw pass asks of the map, without the pixels: resolve
     * the top of the viewport, work out how far the screen reaches, read the
     * rows and fill the window.
     *
     * Measuring a block is a side effect of drawing, and these tests are
     * about what that side effect does to a decision taken before the frame,
     * so they have to take the frame.
     */
    private fun EditorState.drawFrame(): DisplayWindow {
        val first = firstDisplayRow()
        val firstRow = displayMap.bufferRowOf(first)
        val last = lastDisplayRow(first)
        val lastRow = displayMap.bufferRowOf((last - 1).coerceAtLeast(first))
        val lines = linesWindow(firstRow, lastRow + 1)
        displayMap.fillWindow(displayWindow, first, last, firstRow, lines)
        return displayWindow
    }

    /** A file of [rows] rows that each take exactly two display rows at ten columns. */
    private fun twoRowLines(rows: Int): String =
        List(rows) { "x".repeat(15) }.joinToString("\n")

    @Test
    fun thePaneWorksOutItsOwnWrapWidth() {
        assertEquals(20, editorOf("hello", columns = 20).displayMap.wrapColumns)
        assertFalse(editorOf("hello", columns = 20).displayMap.isIdentity)
        assertTrue(editorOf("hello", columns = 20, wrap = false).displayMap.isIdentity)
    }

    @Test
    fun theDocumentIsAsTallAsTheWrappedRowsMakeIt() {
        // "the quick brown fox jumps over it" is four rows at ten columns.
        val state = editorOf("one\nthe quick brown fox jumps over it\nthree", columns = 10)
            .measureWholeFile()
        assertEquals(6, state.displayMap.displayRowCount)
        assertEquals(0, state.displayRowOf(0, 0))
        assertEquals(1, state.displayRowOf(1, 0))
        assertEquals(3, state.displayRowOf(1, 20))
        assertEquals(5, state.displayRowOf(2, 0))
    }

    @Test
    fun theArrowsMoveByARowOfTheScreen() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10)
        state.caretAt(0, 0)

        state.moveCursorVertically(1)
        assertEquals("down one screen row, still inside row 0", 0 to 10, state.head())

        state.moveCursorVertically(1)
        assertEquals(0 to 20, state.head())

        state.moveCursorVertically(1)
        assertEquals(0 to 26, state.head())

        state.moveCursorVertically(1)
        assertEquals("and only now onto the next file row", 1 to 0, state.head())

        state.moveCursorVertically(-1)
        assertEquals(0 to 26, state.head())
    }

    @Test
    fun theArrowsKeepTheirColumnWithinTheSegment() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10)
        // Third character of the first segment.
        state.caretAt(0, 3)

        state.moveCursorVertically(1)
        assertEquals("third character of the second segment", 0 to 13, state.head())
    }

    @Test
    fun withWrappingOffTheArrowsMoveByFileRows() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10, wrap = false)
        state.caretAt(0, 3)

        state.moveCursorVertically(1)
        assertEquals(1 to 3, state.head())
    }

    @Test
    fun aPageIsAScreenfulOfDisplayRows() {
        // Ten rows of viewport, so a page is nine display rows: two whole
        // wrapped lines of four, plus one.
        val long = "the quick brown fox jumps over it"
        val state = editorOf("$long\n$long\n$long\nlast", columns = 10, viewportRows = 10)
        state.caretAt(0, 0)

        state.movePage(down = true)
        assertEquals("nine display rows down is row 2's second segment", 2 to 10, state.head())
    }

    @Test
    fun scrollingToTheCaretFollowsItsOwnSegment() {
        val long = "the quick brown fox jumps over it"
        val state = editorOf("$long\n$long\n$long\n$long", columns = 10, viewportRows = 4)
            .measureWholeFile()
        // The last segment of the last row is display row 15; a viewport of
        // four rows has to end there.
        state.caretAt(3, 30)
        state.ensureCursorVisible()

        assertEquals(15, state.displayRowOf(3, 30))
        assertEquals((15 + 1) * 10f - 40f, state.scrollY, 0.01f)
    }

    @Test
    fun theScrollExtentCountsDisplayRowsNotFileRows() {
        val long = "the quick brown fox jumps over it"
        val wrapped = editorOf("$long\n$long", columns = 10, viewportRows = 4)
            .measureWholeFile()
        val plain = editorOf("$long\n$long", columns = 10, viewportRows = 4, wrap = false)

        assertEquals(8 * 10f - 40f, wrapped.maxScrollY, 0.01f)
        assertEquals("two file rows do not fill four", 0f, plain.maxScrollY, 0.01f)
    }

    @Test
    fun aWrappedPaneHasNothingToScrollSideways() {
        val state = editorOf("the quick brown fox jumps over it", columns = 10)
        assertEquals(0f, state.applyScrollDeltaX(-500f), 0.01f)
        assertEquals(0f, state.effectiveScrollX, 0.01f)
    }

    @Test
    fun typingInAWrappedBufferStillEditsTheRightBytes() {
        val buffer = FakeEditorBuffer("the quick brown fox jumps over it\nnext")
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 100f)
        state.caretAt(0, 20)

        state.insertAtCursor("XY")

        assertEquals("the quick brown fox XYjumps over it\nnext", buffer.text)
        assertEquals(0 to 22, state.head())
    }

    @Test
    fun aNewlineRetallsTheDocumentAndTheRowsBelowIt() {
        val long = "the quick brown fox jumps over it"
        val buffer = FakeEditorBuffer("$long\n$long")
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 100f)

        assertEquals(8, state.measureWholeFile().displayMap.displayRowCount)

        // Split the first row in the middle of its second segment.
        state.caretAt(0, 15)
        state.insertNewline()

        assertEquals(3, state.lineCount)
        // "the quick brown" is two rows; " fox jumps over it" is three,
        // because its leading space is an indent every continuation carries;
        // and the untouched second row is still four.
        assertEquals(2 + 3 + 4, state.measureWholeFile().displayMap.displayRowCount)
        assertEquals(5, state.displayRowOf(2, 0))
    }

    @Test
    fun deletingRowsShortensTheDocument() {
        val long = "the quick brown fox jumps over it"
        val buffer = FakeEditorBuffer("$long\n$long\nshort")
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 100f)

        assertEquals(9, state.measureWholeFile().displayMap.displayRowCount)

        state.caretAt(0, 0)
        state.deleteLines()

        assertEquals("$long\nshort", buffer.text)
        assertEquals(5, state.measureWholeFile().displayMap.displayRowCount)
    }

    @Test
    fun undoingPutsTheDocumentBack() {
        // Undo can rewrite anything, so the map has to give up everything it
        // measured rather than trust a row hint it was never given.
        val long = "the quick brown fox jumps over it"
        val state = editorOf("$long\n$long", columns = 10)
        assertEquals(8, state.measureWholeFile().displayMap.displayRowCount)

        state.caretAt(0, 0)
        state.deleteLines()
        assertEquals(4, state.measureWholeFile().displayMap.displayRowCount)

        // The undo brings the deleted row back without a row hint, and the
        // map has to measure it afresh — the same invalidation as
        // `refreshLineCount`, which the tests above exercise.
        state.undo()
        assertEquals(8, state.measureWholeFile().displayMap.displayRowCount)
    }

    @Test
    fun aCaretJumpLandsWhereTheNextFrameDrawsIt() {
        // 2000 rows of two display rows each, 40 rows of viewport, and a
        // caret dropped into a block nobody has looked at — a search hit or a
        // goto-line. Everything above it is still estimated at one display
        // row per file row, which is half the truth, and the frame that
        // follows measures exactly the block the estimate was wrong about.
        val state = editorOf(twoRowLines(2000), columns = 10, viewportRows = 40)

        state.caretAt(1024, 0)
        val window = state.drawFrame()

        val first = state.firstDisplayRow()
        val last = state.lastDisplayRow(first)
        assertTrue("nothing was drawn", window.size > 0)
        assertTrue(
            "caret display row ${state.displayRowOf(1024, 0)} outside [$first, $last)",
            state.displayRowOf(1024, 0) in first until last,
        )
        assertTrue("the caret's row is not on screen", window.firstIndexOf(1024) >= 0)
    }

    @Test
    fun scrollingToACaretNearTheEndOfALongFileStaysInsideTheDocument() {
        // The same jump, to the last row: the reveal must not run off the
        // bottom of what the document turns out to be either.
        val state = editorOf(twoRowLines(2000), columns = 10, viewportRows = 40)

        state.caretAt(1999, 0)
        val window = state.drawFrame()

        val first = state.firstDisplayRow()
        assertTrue(state.scrollY <= state.maxScrollY)
        assertTrue(
            "caret display row ${state.displayRowOf(1999, 0)} outside the viewport",
            state.displayRowOf(1999, 0) in first until state.lastDisplayRow(first),
        )
        assertTrue(window.firstIndexOf(1999) >= 0)
    }

    @Test
    fun aWiderPaneStillHasSomethingToDraw() {
        // A fold opening, a rotation, a DeX resize: the wrap width changes,
        // every measurement goes with it, and the display row `scrollY` names
        // is suddenly past the end of the document.
        val state = editorOf(twoRowLines(400), columns = 10, viewportRows = 40)
            .measureWholeFile()
        assertEquals(800, state.displayMap.displayRowCount)
        state.scrollToY(state.maxScrollY)
        assertEquals(7600f, state.scrollY, 0.01f)

        state.updateViewport(width = 40 * 10f + 120f, height = 400f)

        assertEquals(40, state.displayMap.wrapColumns)
        assertTrue(
            "scrollY ${state.scrollY} past ${state.maxScrollY}",
            state.scrollY <= state.maxScrollY,
        )
        val window = state.drawFrame()
        assertTrue("nothing was drawn", window.size > 0)
        assertTrue(
            "the reader was thrown back to row ${window.firstBufferRow()}",
            window.firstBufferRow() >= 350,
        )
    }

    @Test
    fun turningWrappingOffStillHasSomethingToDraw() {
        val state = editorOf(twoRowLines(400), columns = 10, viewportRows = 40)
            .measureWholeFile()
        state.scrollToY(state.maxScrollY)

        state.softWrap = SoftWrapMode.None

        assertTrue(state.displayMap.isIdentity)
        assertTrue(
            "scrollY ${state.scrollY} past ${state.maxScrollY}",
            state.scrollY <= state.maxScrollY,
        )
        val window = state.drawFrame()
        assertTrue("nothing was drawn", window.size > 0)
        assertTrue(window.firstBufferRow() >= 350)
    }

    @Test
    fun aKeystrokeReadsAtMostOneBlock() {
        val buffer = FakeEditorBuffer(
            List(400) { "the quick brown fox jumps over it" }.joinToString("\n"),
        )
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 400f)
        state.caretAt(100, 5)
        state.drawFrame()

        val before = buffer.lineCalls
        state.insertAtCursor("X")
        state.drawFrame()

        // A one-line edit is patched into the window in place, so the
        // keystroke itself marshals nothing; all that can remain is a single
        // read where the display map's 64-row block being re-measured pokes
        // past the cached window's edge.
        assertTrue(
            "keystroke cost ${buffer.lineCalls - before} reads",
            buffer.lineCalls - before <= 1,
        )
        val afterFrame = buffer.lineCalls
        state.drawFrame()
        assertEquals("and the frame after it reads nothing", afterFrame, buffer.lineCalls)
    }

    @Test
    fun aQueryFarFromTheWindowStillReadsOneBlock() {
        // Reading the caret's block as a window is only right where the two
        // overlap; a drag to the far end of a file must still cost a block,
        // which is what makes a 100k-line file openable.
        val buffer = FakeEditorBuffer(
            List(2000) { "the quick brown fox jumps over it" }.joinToString("\n"),
        )
        val state = EditorState(buffer)
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(width = 220f, height = 400f)
        state.drawFrame()

        val calls = buffer.lineCalls
        val rows = buffer.rowsRead
        state.displayMap.bufferRowOf(3_000)

        assertEquals("one read", calls + 1, buffer.lineCalls)
        assertTrue("read ${buffer.rowsRead - rows} rows", buffer.rowsRead - rows <= 64)
    }

    @Test
    fun aTapResolvesToTheSegmentUnderIt() {
        val state = editorOf("the quick brown fox jumps over it\nnext", columns = 10)
        // Display row 2 is the third segment, which starts at column 20.
        assertEquals(0 to 20, state.pointAtDisplayRow(2, 0))
        assertEquals(0 to 23, state.pointAtDisplayRow(2, 3))
        assertEquals("clamped to the segment it landed on", 0 to 26, state.pointAtDisplayRow(2, 99))
        assertEquals(1 to 4, state.pointAtDisplayRow(4, 99))
    }
}
