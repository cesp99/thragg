package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostics as the editor wields them: going to one, what that does to a
 * fold in the way, and the one thing the UI derives rather than reads —
 * whether the rows still describe the text they were published against.
 */
class EditorDiagnosticsTest {

    private val nested = listOf(
        "fn outer() {", //      0
        "    if x {", //        1
        "        inner", //     2
        "    }", //             3
        "    after", //         4
        "}", //                 5
    ).joinToString("\n")

    private fun editorOf(text: String): EditorState = EditorState(FakeEditorBuffer(text))

    private fun EditorState.caretAt(row: Int, col: Int) =
        setCarets(listOf(Caret(row, col)), Caret(row, col))

    private fun EditorState.head(): Pair<Int, Int> = primaryCaret().let { it.headRow to it.headCol }

    private fun diagnosticsOf(
        vararg rows: Diagnostic,
        bufferVersion: Long? = 1L,
    ) = BufferDiagnostics(version = 1L, bufferVersion = bufferVersion, rows = rows.toList())

    private fun errorAt(row: Int, col: Int, endCol: Int = col + 3) =
        Diagnostic(row, col, row, endCol, DiagnosticSeverity.Error, "boom")

    // ---- going to a diagnostic ----

    @Test
    fun goingToADiagnosticPutsABareCaretOnItsStart() {
        val state = editorOf(nested)
        state.showDiagnostics(diagnosticsOf(errorAt(4, 4)))
        state.caretAt(0, 0)

        assertTrue(state.goToDiagnostic(forward = true))

        // Zed selects `start..start` rather than the range — the caret lands
        // on the problem, and typing does not delete it
        // (crates/editor/src/diagnostics.rs:190-192).
        assertEquals(4 to 4, state.head())
        assertFalse(state.hasSelection)
    }

    @Test
    fun goingToADiagnosticWrapsInBothDirections() {
        val state = editorOf(nested)
        state.showDiagnostics(diagnosticsOf(errorAt(1, 4), errorAt(4, 4)))

        state.caretAt(4, 4)
        assertTrue(state.goToDiagnostic(forward = true))
        assertEquals(1 to 4, state.head())

        assertTrue(state.goToDiagnostic(forward = false))
        assertEquals(4 to 4, state.head())
    }

    @Test
    fun goingToADiagnosticWithNoneToGoToIsUnhandled() {
        val state = editorOf(nested)
        state.caretAt(2, 0)

        // False, not a silent no-op: the key stays free to mean something
        // else in a file no server has spoken about.
        assertFalse(state.goToDiagnostic(forward = true))
        assertFalse(state.goToDiagnostic(forward = false))
        assertEquals(2 to 0, state.head())
    }

    @Test
    fun goingToADiagnosticRevealsTheFoldHidingIt() {
        val state = editorOf(nested)
        state.caretAt(1, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(1, 2)), state.folds)
        assertTrue(state.isRowFoldedAway(2))

        state.showDiagnostics(diagnosticsOf(errorAt(2, 8)))
        state.caretAt(0, 0)

        assertTrue(state.goToDiagnostic(forward = true))

        // Zed unfolds anything intersecting the diagnostic's range before it
        // selects (diagnostics.rs:187-189) — the same machinery a search hit
        // inside a fold already uses here.
        assertTrue(state.folds.isEmpty())
        assertFalse(state.isRowFoldedAway(2))
        assertEquals(2 to 8, state.head())
    }

    @Test
    fun aDiagnosticSpanningAFoldOpensAllOfIt() {
        val state = editorOf(nested)
        state.caretAt(0, 0)
        state.foldAtCarets()
        assertEquals(listOf(FoldRange(0, 4)), state.folds)

        // A range that starts on the fold's own visible row but reaches into
        // the hidden block: unfolding only the caret's row would leave the
        // end of it hidden.
        state.showDiagnostics(
            diagnosticsOf(Diagnostic(0, 3, 3, 5, DiagnosticSeverity.Error, "block"))
        )
        assertTrue(state.goToDiagnostic(forward = true))

        assertTrue(state.folds.isEmpty())
        assertEquals(0 to 3, state.head())
    }

    @Test
    fun theGutterMarkGoesToTheWorstDiagnosticOnItsRow() {
        val state = editorOf(nested)
        state.showDiagnostics(
            diagnosticsOf(
                Diagnostic(4, 0, 4, 2, DiagnosticSeverity.Hint, "hint"),
                Diagnostic(4, 6, 4, 9, DiagnosticSeverity.Error, "error"),
            )
        )
        state.caretAt(0, 0)

        assertTrue(state.goToDiagnosticOnRow(4))
        assertEquals(4 to 6, state.head())
        assertFalse(state.goToDiagnosticOnRow(5))
    }

    // ---- staleness ----

    @Test
    fun freshRowsAreNotStale() {
        val state = editorOf(nested)
        state.showDiagnostics(diagnosticsOf(errorAt(2, 8), bufferVersion = 1L))
        assertFalse(state.diagnosticsAreStale)
    }

    @Test
    fun typingMakesTheRowsStaleWithoutAskingTheEngineAnything() {
        val state = editorOf(nested)
        state.showDiagnostics(diagnosticsOf(errorAt(2, 8), bufferVersion = 1L))
        state.caretAt(4, 4)

        state.typeCharacter("x")

        // The whole point: `bufferDiagnosticsVersion` deliberately does not
        // move on the user's own typing, so believing the payload's `stale`
        // flag would need a JNI read per keystroke. The comparison the engine
        // makes is `buffer_version != current`, and both halves are already
        // on this side.
        assertTrue(state.diagnosticsAreStale)
        // And the rows themselves have not moved: dimmed, not relocated.
        assertEquals(2, state.diagnostics.rows[0].row)
        assertEquals(8, state.diagnostics.rows[0].colUtf16)
    }

    @Test
    fun rowsDatedAgainstTextWeNoLongerHoldAreStale() {
        val state = editorOf(nested)
        state.showDiagnostics(diagnosticsOf(errorAt(2, 8), bufferVersion = null))
        assertTrue(state.diagnosticsAreStale)
    }

    @Test
    fun aBufferNothingHasEverBeenPublishedForIsNotStale() {
        val state = editorOf(nested)
        state.caretAt(0, 0)
        state.typeCharacter("x")

        // version 0 means "nothing has ever been published", where `stale` is
        // meaningless — and dimming nothing at all is not a state worth
        // computing.
        assertFalse(state.diagnosticsAreStale)
        assertTrue(state.diagnostics.isEmpty)
    }

    @Test
    fun aFreshPublishClearsTheStaleFlag() {
        val state = editorOf(nested)
        state.showDiagnostics(diagnosticsOf(errorAt(2, 8), bufferVersion = 1L))
        state.caretAt(4, 4)
        state.typeCharacter("x")
        assertTrue(state.diagnosticsAreStale)

        // The server catches up: the buffer is on version 2 now.
        state.showDiagnostics(diagnosticsOf(errorAt(2, 8), bufferVersion = 2L))
        assertFalse(state.diagnosticsAreStale)
    }

    // ---- at the cursor ----

    @Test
    fun theStatusBarDescribesTheDiagnosticUnderTheCaret() {
        val state = editorOf(nested)
        state.showDiagnostics(diagnosticsOf(errorAt(2, 8, endCol = 13)))

        state.caretAt(2, 10)
        assertEquals("boom", state.diagnosticAtCursor()?.message)

        state.caretAt(2, 0)
        assertNull(state.diagnosticAtCursor())
    }
}
