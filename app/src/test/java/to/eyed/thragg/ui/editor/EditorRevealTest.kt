package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a jump puts the viewport.
 *
 * Six device sightings said the same thing: go to line 55, read `Ln 55` in
 * the status line, and watch the view stop at 49 — the caret resolved and the
 * viewport did not follow it. Two causes, both here.
 *
 * The first is arithmetic. Blocks nobody has measured are estimated at one
 * display row per file row, which in a wrapped file is a floor and not a
 * truth, and the settle loop only ever measured the window it had just
 * scrolled to — never the rows *above* it, which are exactly the ones the
 * estimate was wrong about. Go to line 70 stopped at 64: one block.
 *
 * The second is the viewport itself. With the keyboard up the pane keeps its
 * full height and the keyboard is drawn over its bottom third, so scrolling
 * the caret to the pane's bottom edge scrolled it behind the keyboard —
 * and the end of the file could not be reached at all.
 */
class EditorRevealTest {

    private fun editorOf(
        text: String,
        columns: Int = 10,
        viewportRows: Int = 20,
    ): EditorState {
        val state = EditorState(FakeEditorBuffer(text))
        state.softWrap = SoftWrapMode.EditorWidth
        state.updateMetrics(lineHeight = 10f, charWidth = 10f, gutterPadding = 0f, textPadding = 0f)
        state.updateViewport(
            width = columns * 10f + state.gutterWidthPx + state.textPaddingPx + 10f,
            height = viewportRows * 10f,
        )
        return state
    }

    /** What the draw pass asks of the map, without the pixels. */
    private fun EditorState.drawFrame(): DisplayWindow {
        val first = firstDisplayRow()
        val firstRow = displayMap.bufferRowOf(first)
        val last = lastDisplayRow(first)
        val lastRow = displayMap.bufferRowOf((last - 1).coerceAtLeast(first))
        val lines = linesWindow(firstRow, lastRow + 1)
        displayMap.fillWindow(displayWindow, first, last, firstRow, lines)
        return displayWindow
    }

    private fun EditorState.jumpTo(row: Int, col: Int = 0) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    /** Rows that wrap into two, so an unmeasured block is half the truth. */
    private fun wrappedFile(rows: Int): String =
        List(rows) { "x".repeat(15) }.joinToString("\n")

    // ---- the arithmetic ----

    /**
     * The device's own file: a jump over a block boundary. Every row above
     * row 70 is in a block the estimate counts at one display row each, and
     * the reveal has to measure them before it can know where row 70 is
     * drawn.
     */
    @Test
    fun aJumpPastAnUnmeasuredBlockLandsOnScreen() {
        val state = editorOf(wrappedFile(90))

        state.jumpTo(70)

        val display = state.displayRowOf(70, 0)
        assertEquals("the row's true display row, not the estimate", 140, display)
        assertTrue(
            "row 70 is drawn at $display, viewport ${state.scrollY}..${state.scrollY + 200f}",
            display * 10f >= state.scrollY && display * 10f < state.scrollY + 200f,
        )
        assertTrue("row 70 was not in the window", state.drawFrame().firstIndexOf(70) >= 0)
    }

    /** And the frame that follows must not measure it back off the screen. */
    @Test
    fun theFrameAfterAJumpDrawsTheRowItScrolledTo() {
        val state = editorOf(wrappedFile(2000), viewportRows = 40)

        state.jumpTo(1024)
        state.drawFrame()

        val first = state.firstDisplayRow()
        assertTrue(
            "caret display row ${state.displayRowOf(1024, 0)} outside " +
                "[$first, ${state.lastDisplayRow(first)})",
            state.displayRowOf(1024, 0) in first until state.lastDisplayRow(first),
        )
    }

    /** A jump lands with its surroundings, not on the last row of the screen. */
    @Test
    fun aJumpLandsAThirdOfTheWayDown() {
        val state = editorOf(wrappedFile(200))

        state.jumpTo(100)

        val display = state.displayRowOf(100, 0)
        val rowsAbove = (display * 10f - state.scrollY) / 10f
        assertEquals("a third of a 20-row viewport", 6f, rowsAbove, 1f)
    }

    // ---- what typing does, which is not a jump ----

    /** A caret already on screen never moves the viewport. */
    @Test
    fun aCaretInViewDoesNotScroll() {
        val state = editorOf(wrappedFile(200))
        state.jumpTo(100)
        val settled = state.scrollY

        state.jumpTo(101)
        assertEquals(settled, state.scrollY, 0.01f)

        state.jumpTo(99)
        assertEquals(settled, state.scrollY, 0.01f)
    }

    /**
     * A step past the edge scrolls by as little as it can — plus Zed's
     * `vertical_scroll_margin`, so the caret is never flush against the
     * bottom of the screen where a keyboard or a card would be over it.
     */
    @Test
    fun aStepPastTheEdgeKeepsTheScrollMargin() {
        val state = editorOf(wrappedFile(200))
        state.jumpTo(100)
        val settled = state.scrollY

        // A row at a time down the screen, until the caret reaches the edge.
        var row = 100
        while (state.scrollY == settled && row < 130) {
            row++
            state.jumpTo(row)
        }

        assertTrue("nothing ever scrolled", row < 130)
        val caretBottom = state.displayRowOf(row, 0) * 10f + 10f
        val marginRows = (state.scrollY + 200f - caretBottom) / 10f
        assertEquals("three rows of margin below the caret", 3f, marginRows, 0.01f)
        assertTrue(
            "a step off the edge re-centred instead of stepping",
            state.scrollY - settled < 100f,
        )
    }

    // ---- the keyboard's share of the pane ----

    /**
     * The pane keeps its full height with the keyboard up, so the state is
     * told what is covered. The end of the file has to stay reachable: the
     * scroll extent is measured against what can be seen, not against the
     * canvas.
     */
    @Test
    fun theScrollExtentAnswersToWhatCanBeSeen() {
        val state = editorOf(wrappedFile(100))
        for (row in 0 until state.lineCount) state.displayMap.displayRowOf(row)
        val whole = state.maxScrollY

        state.bottomInsetPx = 120f

        assertEquals(
            "120px of keyboard is 120px more to scroll",
            whole + 120f,
            state.maxScrollY,
            0.01f,
        )
    }

    /** And a reveal puts the caret where the keyboard is not. */
    @Test
    fun aRevealWithTheKeyboardUpStaysAboveIt() {
        val state = editorOf(wrappedFile(200))
        // Half the pane is keyboard — what a phone does with the IME up.
        state.bottomInsetPx = 100f

        state.jumpTo(100)

        val caretTop = state.displayRowOf(100, 0) * 10f
        assertTrue(
            "caret at $caretTop is under the keyboard, " +
                "visible ${state.scrollY}..${state.scrollY + 100f}",
            caretTop >= state.scrollY && caretTop + 10f <= state.scrollY + 100f,
        )
    }
}
