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

    /**
     * The one counting function, and the property that makes five numbers
     * impossible: a chip's count is the size of the list that chip opens
     * (QA G-19).
     */
    @Test
    fun everyCountIsTheSizeOfTheListItOpens() {
        for (filter in ProblemFilter.entries) {
            val shown = filterProblems(rows, filter)
            assertEquals(
                shown.files.sumOf { it.rows.size },
                problemCount(rows, filter),
            )
        }
        // And the subtitle's two numbers add up to the whole, which the old
        // pair — strict `Warning` beside "everything that is not an error" —
        // did not: the hint belonged to neither.
        assertEquals(
            problemCount(rows, ProblemFilter.All),
            problemCount(rows, ProblemFilter.Errors) + problemCount(rows, ProblemFilter.Warnings),
        )
    }

    @Test
    fun theSameProblemFromTwoProducersIsListedOnceAndNamesBoth() {
        val doubled = ProjectDiagnosticRows(
            version = 3,
            files = listOf(
                FileDiagnosticRows(
                    "programs/escrow/src/lib.rs",
                    listOf(
                        row(4, DiagnosticSeverity.Warning, "unexpected `cfg` condition value", "rustc"),
                        row(
                            4,
                            DiagnosticSeverity.Warning,
                            "unexpected `cfg` condition value",
                            "cargo · anchor build",
                        ),
                    ),
                ),
            ),
        )
        val once = normalizeProblems(doubled).files.single().rows
        assertEquals(1, once.size)
        assertEquals("rustc + cargo · anchor build", once.single().source)
        assertEquals(1, problemCount(normalizeProblems(doubled), ProblemFilter.All))
    }

    @Test
    fun twoToolsDisagreeingAboutOneLineAreBothKept() {
        // rust-analyzer and rustc describing the same syntax error in
        // different words is a fact the list exists to show, not a duplicate.
        val disagreeing = ProjectDiagnosticRows(
            version = 1,
            files = listOf(
                FileDiagnosticRows(
                    "programs/escrow/src/lib.rs",
                    listOf(
                        row(9, DiagnosticSeverity.Error, "Syntax Error: expected SEMICOLON", "rust-analyzer"),
                        row(9, DiagnosticSeverity.Error, "expected `;`, found `msg`", "cargo · anchor build"),
                    ),
                ),
            ),
        )
        assertEquals(2, normalizeProblems(disagreeing).files.single().rows.size)
    }

    @Test
    fun errorsSortAboveWarningsInsideAFile() {
        // The device saw a file's warnings listed above its errors, because
        // document order is all the merge knew about.
        val mixed = ProjectDiagnosticRows(
            version = 1,
            files = listOf(
                FileDiagnosticRows(
                    "programs/escrow/src/lib.rs",
                    listOf(
                        row(1, DiagnosticSeverity.Warning, "unused import: `std::mem`"),
                        row(16, DiagnosticSeverity.Error, "no field `esrow`"),
                    ),
                ),
            ),
        )
        val ordered = normalizeProblems(mixed).files.single().rows
        assertEquals(DiagnosticSeverity.Error, ordered.first().severity)
        assertEquals(DiagnosticSeverity.Warning, ordered.last().severity)
    }

    @Test
    fun thePromptCarriesTheErrorsAloneAndSpellsPositionsOneBased() {
        val prompt = problemsPrompt(rows)
        assertTrue("no field `esrow`" in prompt)
        // Warnings are left out while there is an error to fix: a project with
        // three errors and fourteen `unexpected cfg` warnings from anchor-lang
        // itself handed the agent all seventeen (QA P-10).
        assertFalse("unused import" in prompt)
        assertTrue("3 more are not listed here." in prompt)
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
        val warningsOnly = ProjectDiagnosticRows(
            version = 1,
            files = listOf(
                FileDiagnosticRows(
                    "programs/escrow/src/lib.rs",
                    listOf(
                        row(1, DiagnosticSeverity.Warning, "unused import: `std::mem`"),
                        row(2, DiagnosticSeverity.Warning, "field is never read: `bump`"),
                        row(3, DiagnosticSeverity.Warning, "unused variable: `ctx`"),
                    ),
                ),
            ),
        )
        // With no errors the warnings ARE what there is to say.
        val prompt = problemsPrompt(warningsOnly, limit = 2)
        assertTrue("unused import" in prompt)
        assertTrue("1 more are not listed here." in prompt)
        assertFalse("unused variable" in prompt)
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
