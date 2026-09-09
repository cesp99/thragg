package to.eyed.thragg.ui.shell.changes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import to.eyed.thragg.solana.build.BuildDiagnostics
import to.eyed.thragg.solana.build.BuildIssue
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.editor.FileDiagnosticRows
import to.eyed.thragg.ui.editor.ProjectDiagnosticRows

/**
 * G-19, the half that was still broken: the same problem counted three ways.
 *
 * On the device one warnings-only state printed `8 warnings` on the build
 * strip, `8 warnings in Problems` on the chip, `0 errors · 19 warnings` in
 * the Problems header and `9 problems` in the editor's status line — and the
 * dedupe that was supposed to make the last two agree never fired once,
 * because rust-analyzer forwards a cargo diagnostic with its `note:` and
 * `help:` children appended and cargo does not.
 */
class ProblemCountsTest {

    private val root = "/data/user/0/to.eyed.thragg/files/projects/qa"

    @Before
    @After
    fun clearTheBuild() {
        BuildDiagnostics.clear()
    }

    private fun row(
        line: Int,
        column: Int,
        severity: DiagnosticSeverity,
        message: String,
        source: String,
    ) = Diagnostic(
        row = line,
        colUtf16 = column,
        endRow = line,
        endColUtf16 = column,
        severity = severity,
        message = message,
        source = source,
        code = null,
    )

    /**
     * The exact device pair: cargo's own sentence, and rust-analyzer's
     * forward of the same diagnostic with its notes under it.
     */
    @Test
    fun oneDiagnosticForwardedWithItsNotesIsNotASecondProblem() {
        val rows = ProjectDiagnosticRows(
            version = 1,
            files = listOf(
                FileDiagnosticRows(
                    "programs/qa-anchor2/src/lib.rs",
                    listOf(
                        row(
                            34,
                            9,
                            DiagnosticSeverity.Warning,
                            "unexpected `cfg` condition value: `anchor-debug`\n" +
                                "no expected values for `feature`\n" +
                                "consider removing the condition",
                            "rustc",
                        ),
                        row(
                            34,
                            9,
                            DiagnosticSeverity.Warning,
                            "unexpected `cfg` condition value: `anchor-debug`",
                            "cargo · anchor build",
                        ),
                    ),
                ),
            ),
        )

        val once = normalizeProblems(rows).files.single().rows
        assertEquals(1, once.size)
        assertEquals("rustc + cargo · anchor build", once.single().source)
        // The notes are what the row is worth reading for, so the first row's
        // whole text survives — only the count is merged.
        assertEquals(3, once.single().message.lines().size)
        assertEquals(ProblemCounts(0, 1, 1), countProblems(normalizeProblems(rows)))
    }

    /** Two tools that really disagree still say two different things. */
    @Test
    fun aRealDisagreementIsStillTwoRows() {
        val rows = ProjectDiagnosticRows(
            version = 1,
            files = listOf(
                FileDiagnosticRows(
                    "src/lib.rs",
                    listOf(
                        row(9, 0, DiagnosticSeverity.Error, "Syntax Error: expected SEMICOLON", "rust-analyzer"),
                        row(9, 0, DiagnosticSeverity.Error, "expected `;`, found `msg`\nnote: here", "cargo"),
                    ),
                ),
            ),
        )
        assertEquals(2, normalizeProblems(rows).files.single().rows.size)
    }

    /**
     * THE POINT: the number the Code bar and the status line print for one
     * file is the number that file's card carries in Problems — the same
     * merge and the same dedupe, not the language server's rows alone.
     */
    @Test
    fun aFilesCountIsTheProjectsCountForThatFile() {
        BuildDiagnostics.publish(
            root,
            "cargo · anchor build",
            listOf(
                BuildIssue(
                    path = "src/lib.rs",
                    line = 35,
                    column = 10,
                    severity = DiagnosticSeverity.Warning,
                    message = "unexpected `cfg` condition value: `anchor-debug`",
                    code = null,
                    rendered = null,
                ),
                BuildIssue(
                    path = "src/lib.rs",
                    line = 70,
                    column = 5,
                    severity = DiagnosticSeverity.Error,
                    message = "expected item, found `zzz`",
                    code = null,
                    rendered = null,
                ),
            ),
        )
        val lspRows = listOf(
            // The same warning the build found, with rust-analyzer's notes.
            row(
                34,
                9,
                DiagnosticSeverity.Warning,
                "unexpected `cfg` condition value: `anchor-debug`\nno expected values",
                "rustc",
            ),
            // …and one only the server has.
            row(3, 4, DiagnosticSeverity.Warning, "unused import: `std::mem`", "rust-analyzer"),
        )

        val perFile = countFileProblems("$root/src/lib.rs", lspRows)
        val project = countProblems(
            mergedProblems(
                ProjectDiagnosticRows(
                    version = 1,
                    files = listOf(FileDiagnosticRows("src/lib.rs", lspRows)),
                )
            )
        )

        assertEquals(ProblemCounts(errors = 1, warnings = 2, total = 3), perFile)
        assertEquals(project, perFile)
    }

    /** A file nothing has anything to say about costs no work and counts zero. */
    @Test
    fun aCleanFileIsZero() {
        assertEquals(ProblemCounts(0, 0, 0), countFileProblems("$root/src/other.rs", emptyList()))
    }
}
