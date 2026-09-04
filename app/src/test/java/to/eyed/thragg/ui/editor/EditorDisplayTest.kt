package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The display settings' pure halves: which whitespace gets a glyph, what
 * number the gutter draws, and which diagnostics earn a scrollbar mark.
 */
class EditorDisplayTest {

    /** `"    a  b   "` — leading spaces, two in the middle, three trailing. */
    private val line = "    a  b   "

    @Test
    fun offMarksNothingAndAllMarksEveryWhitespace() {
        assertEquals(emptySet<Int>(), whitespaceColumns(line, ShowWhitespaces.Off))
        assertEquals(
            line.indices.filter { line[it] == ' ' }.toSet(),
            whitespaceColumns(line, ShowWhitespaces.All),
        )
    }

    @Test
    fun trailingMarksOnlyWhatFollowsTheLastVisibleCharacter() {
        assertEquals(setOf(8, 9, 10), whitespaceColumns(line, ShowWhitespaces.Trailing))
    }

    @Test
    fun selectionMarksOnlyWhatTheSelectionCovers() {
        // Columns 5..6 are the two spaces between `a` and `b`.
        assertEquals(setOf(5, 6), whitespaceColumns(line, ShowWhitespaces.Selection, 4..7))
        assertEquals(
            emptySet<Int>(),
            whitespaceColumns(line, ShowWhitespaces.Selection, IntRange.EMPTY),
        )
    }

    /**
     * Zed's three boundary conditions: a tab, an edge, or whitespace next to
     * whitespace. A single space between two words meets none of them.
     */
    @Test
    fun boundaryMarksRunsEdgesAndTabsButNotALoneSpace() {
        assertEquals(emptySet<Int>(), whitespaceColumns("a b", ShowWhitespaces.Boundary))
        assertEquals(setOf(1, 2), whitespaceColumns("a  b", ShowWhitespaces.Boundary))
        assertEquals(setOf(0), whitespaceColumns(" ab", ShowWhitespaces.Boundary))
        assertEquals(setOf(2), whitespaceColumns("ab ", ShowWhitespaces.Boundary))
        assertEquals(setOf(1), whitespaceColumns("a\tb", ShowWhitespaces.Boundary))
    }

    @Test
    fun absoluteNumbersAreOneBasedAndRelativeOnesCountFromTheCaret() {
        assertEquals(4, gutterLineNumber(row = 3, cursorRow = 9, relative = false))
        assertEquals(6, gutterLineNumber(row = 3, cursorRow = 9, relative = true))
        assertEquals(2, gutterLineNumber(row = 11, cursorRow = 9, relative = true))
        // The caret's own row keeps its absolute number, so the file position
        // is never lost — which is what Zed does too.
        assertEquals(10, gutterLineNumber(row = 9, cursorRow = 9, relative = true))
    }

    @Test
    fun numbersWiderThanTheColumnKeepTheirLeadingDigitsAndAnEllipsis() {
        assertEquals("7", gutterLabel(7, digits = 3))
        assertEquals("999", gutterLabel(999, digits = 3))
        assertEquals("10\u2026", gutterLabel(1000, digits = 3))
        assertEquals("12\u2026", gutterLabel(123456, digits = 3))
        // Never wider than the column, whatever it is asked to fit.
        assertEquals("\u2026", gutterLabel(42, digits = 1))
    }

    @Test
    fun theScrollbarDiagnosticsFloorIsASeverityNotAFlag() {
        assertFalse(ScrollbarDiagnostics.None.marks(DiagnosticSeverity.Error))
        assertTrue(ScrollbarDiagnostics.Error.marks(DiagnosticSeverity.Error))
        assertFalse(ScrollbarDiagnostics.Error.marks(DiagnosticSeverity.Warning))
        assertTrue(ScrollbarDiagnostics.Warning.marks(DiagnosticSeverity.Warning))
        assertFalse(ScrollbarDiagnostics.Warning.marks(DiagnosticSeverity.Info))
        assertTrue(ScrollbarDiagnostics.All.marks(DiagnosticSeverity.Hint))
    }

    @Test
    fun zedsBooleanSpellingsOfTheFloorAreRead() {
        assertEquals(ScrollbarDiagnostics.All, ScrollbarDiagnostics.fromKey(true))
        assertEquals(ScrollbarDiagnostics.None, ScrollbarDiagnostics.fromKey(false))
        assertEquals(ScrollbarDiagnostics.Warning, ScrollbarDiagnostics.fromKey("warning"))
    }

    @Test
    fun currentLineHighlightSaysWhichSideIsWashed() {
        assertTrue(CurrentLineHighlight.All.washesGutter && CurrentLineHighlight.All.washesText)
        assertTrue(CurrentLineHighlight.Gutter.washesGutter)
        assertFalse(CurrentLineHighlight.Gutter.washesText)
        assertFalse(CurrentLineHighlight.Line.washesGutter)
        assertTrue(CurrentLineHighlight.Line.washesText)
        assertFalse(CurrentLineHighlight.None.washesGutter || CurrentLineHighlight.None.washesText)
    }

    /** A file shorter than the map starts at its first row; a longer one scrolls. */
    @Test
    fun theMinimapScrollsProportionallyWithTheEditor() {
        assertEquals(0, minimapFirstRow(topRow = 0, viewportRows = 40, minimapRows = 400, totalRows = 100))
        // 1000 rows, 400 on the map, 40 on screen: at the top it starts at 0…
        assertEquals(0, minimapFirstRow(topRow = 0, viewportRows = 40, minimapRows = 400, totalRows = 1000))
        // …and at the bottom the map's last row is the file's last row.
        assertEquals(
            600,
            minimapFirstRow(topRow = 960, viewportRows = 40, minimapRows = 400, totalRows = 1000),
        )
    }
}
