package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.seeker.code.core.ShowDiagnostics
import to.eyed.seeker.code.ui.editor.FileDiagnostics

/**
 * `project_panel.show_diagnostics`, and the roll-up that makes it useful: a
 * collapsed `src/` has to say that something under it is broken, which is what
 * Zed's `diagnostic_summary` does for every ancestor of a file.
 */
class DiagnosticMarksTest {

    private fun file(path: String, errors: Int = 0, warnings: Int = 0) =
        FileDiagnostics(path = path, errors = errors, warnings = warnings, infos = 0, hints = 0)

    @Test
    fun `a file with errors marks itself and every folder above it`() {
        val marks = DiagnosticMarks.of(
            listOf(file("src/ui/main.rs", errors = 2)),
            ShowDiagnostics.All,
        )
        assertEquals(DiagnosticMark.Error, marks.severityOf("src/ui/main.rs"))
        assertEquals(DiagnosticMark.Error, marks.severityOf("src/ui"))
        assertEquals(DiagnosticMark.Error, marks.severityOf("src"))
        assertNull(marks.severityOf("Cargo.toml"))
    }

    @Test
    fun `errors is only errors`() {
        val marks = DiagnosticMarks.of(
            listOf(file("a.rs", warnings = 4), file("b.rs", errors = 1)),
            ShowDiagnostics.Errors,
        )
        assertNull(marks.severityOf("a.rs"))
        assertEquals(DiagnosticMark.Error, marks.severityOf("b.rs"))
    }

    @Test
    fun `off marks nothing at all`() {
        val marks = DiagnosticMarks.of(listOf(file("a.rs", errors = 9)), ShowDiagnostics.Off)
        assertNull(marks.severityOf("a.rs"))
    }

    @Test
    fun `an error anywhere under a folder outranks a warning`() {
        val marks = DiagnosticMarks.of(
            listOf(file("src/a.rs", warnings = 1), file("src/b.rs", errors = 1)),
            ShowDiagnostics.All,
        )
        assertEquals(DiagnosticMark.Warning, marks.severityOf("src/a.rs"))
        assertEquals(DiagnosticMark.Error, marks.severityOf("src/b.rs"))
        assertEquals(DiagnosticMark.Error, marks.severityOf("src"))
    }

    @Test
    fun `a clean file marks nothing`() {
        val marks = DiagnosticMarks.of(listOf(file("a.rs")), ShowDiagnostics.All)
        assertNull(marks.severityOf("a.rs"))
    }
}
