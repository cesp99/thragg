package to.eyed.thragg.ui.shell.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.editor.FileDiagnosticRows
import to.eyed.thragg.ui.editor.ProjectDiagnosticRows

/**
 * The Problems route's three pure parts: the filter, the counts and the
 * sentence `[ Fix with agent ]` hands the composer.
 *
 * The prompt is tested because the wording *is* the feature — an agent given
 * ninety rows spends its context on the list rather than on the fix — and the
 * filter because a list that quietly drops a file's last row while keeping its
 * header is the one way a diagnostics list lies.
 */
class ProblemsScreenTest {

    private fun row(
        line: Int,
        severity: DiagnosticSeverity,
        message: String,
        source: String? = null,
        code: String? = null,
    ) = Diagnostic(
        row = line,
        colUtf16 = 4,
        endRow = line,
        endColUtf16 = 4,
        severity = severity,
        message = message,
        source = source,
        code = code,
    )

    private val rows = ProjectDiagnosticRows(
        version = 7,
        files = listOf(
            FileDiagnosticRows(
                "programs/escrow/src/lib.rs",
                listOf(
                    row(16, DiagnosticSeverity.Error, "no field `esrow`", "cargo · anchor build", "E0609"),
                    row(1, DiagnosticSeverity.Warning, "unused import: `std::mem`", "rust-analyzer"),
                ),
            ),
            FileDiagnosticRows(
                "programs/escrow/src/state.rs",
                listOf(row(7, DiagnosticSeverity.Warning, "field is never read: `bump`", "rust-analyzer")),
            ),
            FileDiagnosticRows(
                "tests/escrow.ts",
                listOf(row(7, DiagnosticSeverity.Hint, "'anchor' is declared but never read")),
            ),
        ),
    )

    @Test
    fun allKeepsEverythingAndKeepsItsIdentity() {
        assertEquals(rows, filterProblems(rows, ProblemFilter.All))
    }

    @Test
    fun errorsDropsTheFilesThatHadOnlyWarnings() {
        val only = filterProblems(rows, ProblemFilter.Errors)
        assertEquals(listOf("programs/escrow/src/lib.rs"), only.files.map { it.path })
        assertEquals(1, only.files.single().rows.size)
        // The version rides through untouched, so a consumer keyed on it still
        // recomputes when either producer moves.
        assertEquals(7L, only.version)
    }

    @Test
    fun warningsMeansEverythingThatIsNotAnError() {
        val only = filterProblems(rows, ProblemFilter.Warnings)
        assertEquals(
            listOf("programs/escrow/src/lib.rs", "programs/escrow/src/state.rs", "tests/escrow.ts"),
            only.files.map { it.path },
        )
        // The hint is listed: it has nowhere else to be, and a filter with rows
        // behind it that shows none is a list that lies.
        assertTrue(only.files.last().rows.single().severity == DiagnosticSeverity.Hint)
    }

    @Test
    fun theHeaderCountsWhatIsListed() {
        assertEquals(1, countBy(rows, DiagnosticSeverity.Error))
        assertEquals(2, countBy(rows, DiagnosticSeverity.Warning))
        assertEquals(0, countBy(filterProblems(rows, ProblemFilter.Errors), DiagnosticSeverity.Warning))
    }

    @Test
    fun thePromptLeadsWithTheErrorsAndSpellsPositionsOneBased() {
        val prompt = problemsPrompt(rows)
        val error = prompt.indexOf("no field `esrow`")
        val warning = prompt.indexOf("unused import")
        assertTrue(error in 0 until warning)
        // 1-based, as the compiler and the terminal spell a position; the
        // engine's rows and columns are 0-based.
        assertTrue("programs/escrow/src/lib.rs:17:5" in prompt)
        assertTrue("[E0609]" in prompt)
        // The producer travels with the row: the two tools disagreeing about
        // one line is a fact the agent cannot recover from the message.
        assertTrue("cargo · anchor build" in prompt)
    }

    @Test
    fun thePromptStopsAtTheLimitAndSaysHowManyItLeftOut() {
        val prompt = problemsPrompt(rows, limit = 2)
        assertTrue("2 more are not listed here." in prompt)
        assertFalse("never read" in prompt)
    }

    @Test
    fun aCleanProjectGetsASentenceRatherThanAnEmptyPrompt() {
        assertEquals(
            "There are no problems in this project.",
            problemsPrompt(ProjectDiagnosticRows(0, emptyList())),
        )
    }

    @Test
    fun aPathOutsideTheProjectIsLeftAloneAndOneInsideIsRooted() {
        assertEquals("/root/src/lib.rs", absoluteIn("/root", "src/lib.rs"))
        // A crate in the registry, a header in /usr/include: the engine and
        // the cargo merge layer both leave those absolute.
        assertEquals("/usr/include/stdio.h", absoluteIn("/root", "/usr/include/stdio.h"))
    }
}
