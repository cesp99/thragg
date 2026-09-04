package to.eyed.thragg.solana.build

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.editor.DiagnosticSummary
import to.eyed.thragg.ui.editor.FileDiagnosticRows
import to.eyed.thragg.ui.editor.FileDiagnostics
import to.eyed.thragg.ui.editor.ProjectDiagnosticRows

/**
 * The merge layer, which exists because the engine's diagnostics store is
 * read-only across JNI. What is tested here is everything a consumer relies on
 * being true: the coordinates convert, the paths reduce to the spelling the
 * engine uses, the two producers' rows coexist and stay labelled, and a run
 * clears its predecessor rather than accumulating.
 */
class BuildDiagnosticsTest {

    private val root = "/data/user/0/to.eyed.thragg/files/projects/escrow"
    private val producer = "cargo · anchor build"

    @Before
    fun reset() = BuildDiagnostics.clear()

    @After
    fun tearDown() = BuildDiagnostics.clear()

    private fun issue(
        path: String?,
        line: Int = 0,
        column: Int = 0,
        severity: DiagnosticSeverity = DiagnosticSeverity.Error,
        message: String = "no field `esrow` on type `EscrowBumps`",
        code: String? = "E0609",
    ) = BuildIssue(path, line, column, severity, message, code, rendered = null)

    // --- coordinates ------------------------------------------------------------

    @Test
    fun `one-based compiler positions become zero-based editor rows`() {
        val row = BuildDiagnostics.toDiagnostic(issue("src/lib.rs", 17, 26), producer)
        assertEquals(16, row.row)
        assertEquals(25, row.colUtf16)
        assertEquals(16, row.endRow)
    }

    @Test
    fun `a diagnostic with no position lands on the first row rather than at minus one`() {
        val row = BuildDiagnostics.toDiagnostic(issue("src/lib.rs"), producer)
        assertEquals(0, row.row)
        assertEquals(0, row.colUtf16)
    }

    @Test
    fun `the producer rides on every row, which is what makes a stale error legible`() {
        val row = BuildDiagnostics.toDiagnostic(issue("src/lib.rs", 3, 1), producer)
        assertEquals(producer, row.source)
        assertTrue(row.label.contains("cargo"))
        assertTrue(row.label.contains("E0609"))
    }

    // --- paths --------------------------------------------------------------------

    @Test
    fun `a relative path is already the engine's spelling`() {
        assertEquals(
            "programs/escrow/src/lib.rs",
            BuildDiagnostics.normalizePath(root, "programs/escrow/src/lib.rs"),
        )
    }

    @Test
    fun `a guest absolute path reduces to the project-relative one`() {
        assertEquals(
            "programs/escrow/src/lib.rs",
            BuildDiagnostics.normalizePath(root, "/projects/escrow/programs/escrow/src/lib.rs"),
        )
    }

    @Test
    fun `a host absolute path reduces too`() {
        assertEquals(
            "src/lib.rs",
            BuildDiagnostics.normalizePath(root, "$root/src/lib.rs"),
        )
    }

    @Test
    fun `a file outside the project keeps its absolute path`() {
        val registry = "/root/.cargo/registry/src/index.crates.io/anchor-lang-0.31.1/src/lib.rs"
        assertEquals(registry, BuildDiagnostics.normalizePath(root, registry))
    }

    @Test
    fun `dot segments are folded away so a row keys the file the tree knows`() {
        assertEquals("src/lib.rs", BuildDiagnostics.normalizePath(root, "./src/lib.rs"))
        assertEquals(
            "src/lib.rs",
            BuildDiagnostics.normalizePath(root, "programs/../src/lib.rs"),
        )
    }

    // --- publishing ----------------------------------------------------------------

    @Test
    fun `publishing groups by file, counts by severity and bumps the version`() {
        val before = BuildDiagnostics.version
        BuildDiagnostics.publish(
            root,
            producer,
            listOf(
                issue("src/lib.rs", 17, 26),
                issue("src/lib.rs", 2, 5, DiagnosticSeverity.Warning, "unused import", null),
                issue("src/state.rs", 8, 5, DiagnosticSeverity.Warning, "never read", null),
            ),
        )
        assertEquals(1, BuildDiagnostics.errors)
        assertEquals(2, BuildDiagnostics.warnings)
        assertEquals(2, BuildDiagnostics.files.size)
        assertTrue(BuildDiagnostics.version > before)
        // Rows within a file come back in document order, not in the order the
        // compiler happened to find them.
        val lib = BuildDiagnostics.files.first { it.path == "src/lib.rs" }
        assertEquals(listOf(1, 16), lib.rows.map { it.row })
    }

    @Test
    fun `a problem with no file is counted but is not a row in anybody's list`() {
        BuildDiagnostics.publish(
            root,
            producer,
            listOf(issue(null, message = "Failed to execute rustup", code = "toolchain")),
        )
        assertEquals(1, BuildDiagnostics.errors)
        assertTrue(BuildDiagnostics.files.isEmpty())
    }

    @Test
    fun `a second run replaces the first rather than adding to it`() {
        BuildDiagnostics.publish(root, producer, listOf(issue("src/lib.rs", 17, 26)))
        BuildDiagnostics.publish(root, producer, listOf(issue("src/other.rs", 1, 1)))
        assertEquals(1, BuildDiagnostics.files.size)
        assertEquals("src/other.rs", BuildDiagnostics.files.single().path)
    }

    @Test
    fun `clear empties the set and is idempotent`() {
        BuildDiagnostics.publish(root, producer, listOf(issue("src/lib.rs", 1, 1)))
        BuildDiagnostics.clear()
        assertTrue(BuildDiagnostics.files.isEmpty())
        assertEquals(0, BuildDiagnostics.errors)
        val version = BuildDiagnostics.version
        BuildDiagnostics.clear()
        assertEquals(version, BuildDiagnostics.version)
    }

    @Test
    fun `rows for a file are found by its absolute path`() {
        BuildDiagnostics.publish(root, producer, listOf(issue("src/lib.rs", 17, 26)))
        assertEquals(1, BuildDiagnostics.rowsFor("$root/src/lib.rs").size)
        assertTrue(BuildDiagnostics.rowsFor("$root/src/other.rs").isEmpty())
    }

    // --- merging --------------------------------------------------------------------

    private fun lspRows() = ProjectDiagnosticRows(
        version = 7,
        files = listOf(
            FileDiagnosticRows(
                "src/lib.rs",
                listOf(
                    Diagnostic(
                        row = 30,
                        colUtf16 = 4,
                        endRow = 30,
                        endColUtf16 = 9,
                        severity = DiagnosticSeverity.Warning,
                        message = "unused variable",
                        source = "rust-analyzer",
                    )
                ),
            )
        ),
    )

    @Test
    fun `with nothing published the engine's rows pass through untouched`() {
        val lsp = lspRows()
        assertEquals(lsp, BuildDiagnostics.merge(lsp))
    }

    @Test
    fun `both producers' rows share a file and stay in document order`() {
        BuildDiagnostics.publish(root, producer, listOf(issue("src/lib.rs", 17, 26)))
        val merged = BuildDiagnostics.merge(lspRows())
        val lib = merged.files.single { it.path == "src/lib.rs" }
        assertEquals(2, lib.rows.size)
        assertEquals(listOf(16, 30), lib.rows.map { it.row })
        assertEquals(listOf(producer, "rust-analyzer"), lib.rows.map { it.source })
    }

    @Test
    fun `a file only the build knows about joins the list`() {
        BuildDiagnostics.publish(root, producer, listOf(issue("tests/escrow.ts", 8, 1)))
        val merged = BuildDiagnostics.merge(lspRows())
        assertEquals(listOf("src/lib.rs", "tests/escrow.ts"), merged.files.map { it.path })
    }

    @Test
    fun `the counts a header shows add both producers up`() {
        BuildDiagnostics.publish(
            root,
            producer,
            listOf(
                issue("src/lib.rs", 17, 26),
                issue("src/lib.rs", 2, 5, DiagnosticSeverity.Warning, "unused import", null),
            ),
        )
        val summary = DiagnosticSummary(
            version = 7,
            errors = 0,
            warnings = 1,
            infos = 0,
            hints = 0,
            files = listOf(FileDiagnostics("src/lib.rs", 0, 1, 0, 0)),
        )
        val merged = BuildDiagnostics.merge(summary)
        assertEquals(1, merged.errors)
        assertEquals(2, merged.warnings)
        assertEquals(1, merged.files.size)
    }

    // --- the agent's prompt ------------------------------------------------------------

    @Test
    fun `the agent prompt names the command, counts the errors and carries the snippet`() {
        val rendered = "error[E0609]: no field `esrow`\n  --> src/lib.rs:17:26\n   |\n17 | x\n"
        val prompt = BuildDiagnostics.agentPrompt(
            listOf(issue("src/lib.rs", 17, 26).copy(rendered = rendered)),
            command = "anchor build",
        )
        assertTrue(prompt.contains("anchor build"))
        assertTrue(prompt.contains("1 error"))
        assertTrue(prompt.contains("src/lib.rs:17:26"))
        assertTrue(prompt.contains("17 | x"))
    }

    @Test
    fun `the agent prompt prefers errors and says how many it left out`() {
        val issues = (1..12).map { issue("src/lib.rs", it, 1, message = "error $it") }
        val prompt = BuildDiagnostics.agentPrompt(issues, "cargo build-sbf", limit = 3)
        assertTrue(prompt.contains("error 1"))
        assertTrue(prompt.contains("9 more not shown"))
    }
}
