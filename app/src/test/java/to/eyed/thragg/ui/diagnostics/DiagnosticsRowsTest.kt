package to.eyed.thragg.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.editor.FileDiagnosticRows

class DiagnosticsRowsTest {

    private fun issue(row: Int, severity: DiagnosticSeverity) = Diagnostic(
        row = row,
        colUtf16 = 0,
        endRow = row,
        endColUtf16 = 3,
        severity = severity,
        message = "problem",
    )

    private val files = listOf(
        FileDiagnosticRows(
            path = "src/main.rs",
            rows = listOf(
                issue(3, DiagnosticSeverity.Error),
                issue(9, DiagnosticSeverity.Warning),
            ),
        ),
        FileDiagnosticRows(
            path = "notes.md",
            rows = listOf(issue(0, DiagnosticSeverity.Hint)),
        ),
    )

    @Test
    fun aFileIsAHeaderAndItsIssuesInOrder() {
        val rows = diagnosticsRows(files, collapsed = emptySet(), includeWarnings = true)
        assertEquals(5, rows.size)
        val header = rows[0] as DiagnosticsRow.FileRow
        assertEquals("main.rs", header.name)
        assertEquals("src", header.directory)
        assertEquals(1, header.errors)
        assertEquals(1, header.warnings)
        assertEquals(3, (rows[1] as DiagnosticsRow.IssueRow).diagnostic.row)
        assertEquals(9, (rows[2] as DiagnosticsRow.IssueRow).diagnostic.row)
        // A file at the project root has no directory.
        assertEquals("", (rows[3] as DiagnosticsRow.FileRow).directory)
    }

    @Test
    fun aCollapsedFileKeepsItsHeaderOnly() {
        val rows = diagnosticsRows(files, collapsed = setOf("src/main.rs"), includeWarnings = true)
        assertEquals(3, rows.size)
        assertTrue((rows[0] as DiagnosticsRow.FileRow).isCollapsed)
        assertEquals("notes.md", rows[1].path)
    }

    @Test
    fun errorsOnlyDropsEverythingBelowThemFilesIncluded() {
        // Zed's ToggleWarnings off: warnings, infos and hints all wait behind
        // it, and a file left with nothing to show disappears whole.
        val rows = diagnosticsRows(files, collapsed = emptySet(), includeWarnings = false)
        assertEquals(2, rows.size)
        val header = rows[0] as DiagnosticsRow.FileRow
        assertEquals(1, header.errors)
        assertEquals(0, header.warnings)
        assertEquals(
            DiagnosticSeverity.Error,
            (rows[1] as DiagnosticsRow.IssueRow).diagnostic.severity,
        )
    }

    @Test
    fun keysAreUniqueAcrossTheList() {
        val rows = diagnosticsRows(files, collapsed = emptySet(), includeWarnings = true)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
