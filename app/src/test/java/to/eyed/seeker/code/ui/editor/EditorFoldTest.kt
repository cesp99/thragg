package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folding as the editor wields it: the commands Zed binds
 * (crates/editor/src/fold.rs), the caret rules around hidden rows, and the
 * one behaviour anchors would otherwise give us for free — what happens to a
 * fold when the buffer changes under it.
 */
class EditorFoldTest {

    private fun editorOf(text: String): EditorState = EditorState(FakeEditorBuffer(text))

    /**
     * A pane with pixels, for the tests that take a frame: a 10px line in a
     * viewport [viewportRows] of them tall.
     */
    private fun paneOver(buffer: FakeEditorBuffer, viewportRows: Int = 40): EditorState {
        val state = EditorState(buffer)
        state.updateMetrics(
            lineHeight = 10f,
            charWidth = 10f,
            gutterPadding = 0f,
            textPadding = 0f,
        )
        state.updateViewport(width = 400f, height = viewportRows * 10f)
        return state
    }

    /**
     * What the pane's draw pass asks of the state, without the pixels:
     * resolve the top of the viewport, work out how far the screen reaches,
     * read the rows it draws and fill the window.
     */
    private fun EditorState.drawFrame(): DisplayWindow {
        val first = firstDisplayRow()
        val firstRow = displayMap.bufferRowOf(first)
        val last = lastDisplayRow(first)
        val lastRow = displayMap.bufferRowOf((last - 1).coerceAtLeast(first))
        val rows = visibleRows(firstRow, lastRow)
        displayMap.fillWindow(displayWindow, first, last, firstRow, rows::text)
        return displayWindow
    }

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    private val nested = listOf(
        "fn outer() {", //      0
        "    if x {", //        1
        "        inner", //     2
        "    }", //             3
        "    after", //         4
        "}", //                 5
    ).joinToString("\n")

    @Test
    fun foldAtCaretFoldsTheInnermostBlockAroundIt() {
        val state = editorOf(nested)
        state.caretAt(2, 5)

        state.foldAtCarets()

        // The `if` block, not the function: the innermost range containing
        // the caret, found by Zed's walk up from the caret's row
        // (fold.rs:195-204).
        assertEquals(listOf(FoldRange(1, 2)), state.folds)
        assertTrue(state.isRowFoldedAway(2))
        assertFalse(state.isRowFoldedAway(3))
        // The caret stood on a row that left the screen; it snapped to the
        // end of the fold's own line.
        assertEquals(1 to "    if x {".length, state.head())
    }

    @Test
    fun foldOnTheBlocksOwnRowFoldsThatBlock() {
        val state = editorOf(nested)
        state.caretAt(0, 3)

        state.foldAtCarets()

        assertEquals(listOf(FoldRange(0, 4)), state.folds)
        assertEquals(0 to 3, state.head())
    }

    @Test
    fun unfoldOnTheChipRowOpensTheFold() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(1, 2)), state.folds)

        state.unfoldAtCarets()
        assertTrue(state.folds.isEmpty())
    }

    @Test
    fun foldAllRecordsNestedBlocksAndUnfoldingTheOuterKeepsTheInner() {
        val state = editorOf(nested)
        state.caretAt(0, 0)

        state.foldAllRows()
        assertEquals(listOf(FoldRange(0, 4), FoldRange(1, 2)), state.folds)
        // Only the function's first line and the closing brace survive.
        assertFalse(state.isRowFoldedAway(0))
        assertTrue(state.isRowFoldedAway(1))
        assertTrue(state.isRowFoldedAway(4))
        assertFalse(state.isRowFoldedAway(5))

        // Zed keeps the inner crease folded when the outer opens
        // (fold_map holds overlapping folds); the chevron's toggle on the
        // outer row proves it.
        state.toggleFoldAt(0)
        assertEquals(listOf(FoldRange(1, 2)), state.folds)
        assertTrue(state.isRowFoldedAway(2))
        assertFalse(state.isRowFoldedAway(4))

        state.unfoldAllRows()
        assertTrue(state.folds.isEmpty())
    }

    @Test
    fun arrowDownFromTheChipRowLandsAfterTheFold() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets() // folds (1, 2)

        state.moveCursorVertically(1)
        assertEquals("row 2 is hidden; the arrow lands past it", 3, state.head().first)
    }

    @Test
    fun arrowRightAtTheChipsEndStepsOverTheHiddenRows() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets()

        state.caretAt(1, "    if x {".length)
        state.moveCursorHorizontally(1)
        assertEquals(3 to 0, state.head())

        // And back again.
        state.moveCursorHorizontally(-1)
        assertEquals(1 to "    if x {".length, state.head())
    }

    @Test
    fun aStructuralEditOnTheFoldUnfoldsIt() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(1, 2)), state.folds)

        // Enter on the chip row changes the row count under the fold's feet;
        // with no anchors to ride the edit, it opens (Zed's anchored folds
        // ride instead — fold_map.rs:562 only drops the collapsed ones).
        state.caretAt(1, "    if x {".length)
        state.insertNewline()
        assertTrue(state.folds.isEmpty())
    }

    @Test
    fun typingOnTheChipRowLeavesTheFoldAlone() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets()

        state.caretAt(1, 4)
        state.typeCharacter("x")
        assertEquals(listOf(FoldRange(1, 2)), state.folds)
        assertTrue(state.isRowFoldedAway(2))
    }

    @Test
    fun anEditAboveAFoldShiftsItAndAnEditBelowLeavesIt() {
        val text = listOf(
            "top", //           0
            "def f():", //      1
            "    a", //         2
            "    b", //         3
            "tail", //          4
        ).joinToString("\n")
        val state = editorOf(text)
        state.caretAt(1, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(1, 3)), state.folds)

        // A new row above: the fold rides down with its text.
        state.caretAt(0, 0)
        state.insertNewline()
        assertEquals(listOf(FoldRange(2, 4)), state.folds)

        // An edit below the fold does not touch it.
        state.caretAt(5, 0)
        state.typeCharacter("!")
        assertEquals(listOf(FoldRange(2, 4)), state.folds)
    }

    @Test
    fun undoOpensEveryFold() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets()

        // FakeEditorBuffer has no history; refreshLineCount() is what an
        // undo runs, and it must clear the folds it cannot re-anchor.
        state.refreshLineCount()
        assertTrue(state.folds.isEmpty())
    }

    @Test
    fun revealingASearchHitOpensTheFoldOverIt() {
        val state = editorOf(nested)
        state.caretAt(0, 0)
        state.foldAllRows()
        assertTrue(state.isRowFoldedAway(2))

        state.selectRange(EditorState.SelectionRange(2, 8, 2, 13))
        assertFalse(state.isRowFoldedAway(2))
        assertEquals(2 to 13, state.head())
    }

    @Test
    fun documentEndStopsOnTheLastVisibleRow() {
        val text = listOf(
            "top",
            "def f():",
            "    a",
            "    b",
        ).joinToString("\n")
        val state = editorOf(text)
        state.caretAt(1, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(1, 3)), state.folds)

        state.caretAt(0, 0)
        state.moveToDocumentEnd()
        assertEquals(1 to "def f():".length, state.head())
    }

    @Test
    fun aJumpIntoAFoldOpensItRatherThanMovingTheCaret() {
        val state = editorOf(nested)
        state.caretAt(0, 0)
        state.foldAllRows()
        assertTrue(state.isRowFoldedAway(2))

        // Exactly the call go-to-line and the outline picker make. The caret
        // has to land on the line that was asked for: the next character
        // typed goes where it is, and a caret quietly moved to the chip row
        // types into the wrong line with nothing to show for it.
        val caret = Caret(2, 4)
        state.setCarets(listOf(caret), caret)

        assertEquals(2 to 4, state.head())
        assertFalse(state.isRowFoldedAway(2))
    }

    @Test
    fun aJumpToAChipRowLeavesTheFoldShut() {
        val state = editorOf(nested)
        state.caretAt(0, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(0, 4)), state.folds)

        // Landing *on* a fold's own line is landing somewhere visible; the
        // block under it stays closed (`hiddenOnly`), as it does for a
        // search hit on a chip row.
        val caret = Caret(0, 3)
        state.setCarets(listOf(caret), caret)

        assertEquals(listOf(FoldRange(0, 4)), state.folds)
        assertEquals(0 to 3, state.head())
    }

    @Test
    fun aNewFoldOverTheCaretStillTakesItToTheChipRow() {
        val state = editorOf(nested)
        state.caretAt(2, 5)

        // The other half of the rule: this fold is being created *around* the
        // caret, so revealing it again would undo the command on the spot.
        // Only this path snaps.
        state.foldRanges(listOf(FoldRange(1, 2)))

        assertEquals(listOf(FoldRange(1, 2)), state.folds)
        assertEquals(1 to "    if x {".length, state.head())
    }

    @Test
    fun foldingWithNothingToFoldStopsAtTheTopLevelRow() {
        // A flat file: no row hangs a block under itself, so the walk up from
        // the caret finds nothing — and must not read the twenty thousand
        // rows above it to find that out.
        val buffer = FakeEditorBuffer(List(2_000) { "line $it" }.joinToString("\n"))
        val state = EditorState(buffer)
        state.caretAt(1_999, 0)
        val before = buffer.lineCalls

        state.foldAtCarets()

        assertTrue(state.folds.isEmpty())
        // One 256-row chunk: the caret's own row, which is at column zero and
        // therefore ends the walk.
        assertEquals("bridge calls", 1, buffer.lineCalls - before)
    }

    @Test
    fun aFrameBesideAFoldCostsTheRowsOnScreenAndNotTheFold() {
        // One top-level block over a whole file, folded: the first row the
        // frame draws and the last are twenty thousand apart, and reading
        // between them would put the file on the UI thread of every frame —
        // and of every keystroke whose edit drops the window.
        val text = buildString {
            append("class Big {\n")
            for (row in 1..20_001) append("    row $row\n")
            append("}")
        }
        val buffer = FakeEditorBuffer(text)
        val state = paneOver(buffer)
        state.caretAt(0, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(0, 20_001)), state.folds)

        var before = buffer.rowsRead
        val window = state.drawFrame()
        assertEquals(
            "the chip row and the closing brace, nothing between",
            listOf(0, 20_002),
            (0 until window.size).map(window::bufferRow),
        )
        assertTrue(
            "first frame read ${buffer.rowsRead - before} rows",
            buffer.rowsRead - before < 500,
        )

        // And again after a keystroke on the chip row, which is the case the
        // whole cost is paid in: the fold survives the edit, so the frame
        // after it resolves the same two rows.
        state.caretAt(0, "class Big {".length)
        state.drawFrame()
        before = buffer.rowsRead
        state.typeCharacter("x")
        state.drawFrame()
        assertEquals(listOf(FoldRange(0, 20_001)), state.folds)
        assertTrue(
            "keystroke read ${buffer.rowsRead - before} rows",
            buffer.rowsRead - before < 500,
        )
    }

    @Test
    fun aSelectionSpanningRowsFoldsEveryBlockInsideIt() {
        val state = editorOf(nested)
        state.setCarets(listOf(Caret(0, 0, 5, 1)), Caret(0, 0, 5, 1))

        state.foldAtCarets()
        // Zed folds each foldable row in the selection, skipping by each
        // crease's end (fold.rs:178-193): the outer block swallows the walk
        // past the inner one.
        assertEquals(listOf(FoldRange(0, 4)), state.folds)
    }
}
