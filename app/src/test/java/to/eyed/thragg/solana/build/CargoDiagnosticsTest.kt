package to.eyed.thragg.solana.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.ui.editor.DiagnosticSeverity

/**
 * The parser, against the six shapes of failure a build on this device
 * actually produces — a rustc diagnostic in both of its forms, an Anchor IDL
 * error, a proc-macro panic, `Failed to execute rustup`, an lld failure and a
 * mocha failure — plus the property the whole design leans on: **an unparsed
 * line is never lost.**
 *
 * The rest of P4 assumes this file is right, which is why it was written
 * first. The output below is the real shape of each producer's words, not an
 * invented one.
 */
class CargoDiagnosticsTest {

    // --- cargo's JSON ---------------------------------------------------------

    private val e0609Json = """
        {"reason":"compiler-message","package_id":"escrow 0.1.0","manifest_path":"/projects/escrow/programs/escrow/Cargo.toml","target":{"name":"escrow"},"message":{"rendered":"error[E0609]: no field `esrow` on type `EscrowBumps`\n  --> programs/escrow/src/lib.rs:17:26\n   |\n17 |     e.bump = ctx.bumps.esrow;\n   |                        ^^^^^ unknown field\n","children":[],"code":{"code":"E0609","explanation":null},"level":"error","message":"no field `esrow` on type `EscrowBumps`","spans":[{"file_name":"programs/escrow/src/lib.rs","is_primary":true,"line_start":17,"line_end":17,"column_start":26,"column_end":31}]}}
    """.trimIndent()

    @Test
    fun `a rustc error in json carries file line column code and rendered text`() {
        val issues = CargoDiagnostics.issues(e0609Json)
        assertEquals(1, issues.size)
        val issue = issues.single()
        assertEquals("programs/escrow/src/lib.rs", issue.path)
        assertEquals(17, issue.line)
        assertEquals(26, issue.column)
        assertEquals("E0609", issue.code)
        assertEquals(DiagnosticSeverity.Error, issue.severity)
        assertEquals("no field `esrow` on type `EscrowBumps`", issue.message)
        assertTrue(issue.rendered!!.contains("unknown field"))
        assertEquals("programs/escrow/src/lib.rs:17:26", issue.location)
    }

    @Test
    fun `the json envelope records are swallowed rather than printed`() {
        val output = """
            {"reason":"compiler-artifact","package_id":"escrow 0.1.0","filenames":["/projects/escrow/target/deploy/escrow.so"]}
            {"reason":"build-finished","success":false}
        """.trimIndent()
        assertTrue(CargoDiagnostics.parseAll(output).isEmpty())
    }

    @Test
    fun `only the primary span decides where an error is`() {
        val line = """{"reason":"compiler-message","message":{"level":"error","message":"borrow of moved value","code":null,"rendered":"x","spans":[{"file_name":"a.rs","is_primary":false,"line_start":3,"column_start":9},{"file_name":"b.rs","is_primary":true,"line_start":9,"column_start":1}]}}"""
        val issue = CargoDiagnostics.issues(line).single()
        assertEquals("b.rs", issue.path)
        assertEquals(9, issue.line)
    }

    @Test
    fun `notes and helps are not problems of their own`() {
        val line = """{"reason":"compiler-message","message":{"level":"note","message":"this error originates in a macro","spans":[]}}"""
        assertTrue(CargoDiagnostics.issues(line).isEmpty())
    }

    // --- rendered text: the path anchor and seahorse take ---------------------

    @Test
    fun `a rendered rustc error is read from its two lines`() {
        val output = """
            error[E0609]: no field `esrow` on type `EscrowBumps`
              --> programs/escrow/src/lib.rs:17:26
               |
            17 |     e.bump = ctx.bumps.esrow;
               |                        ^^^^^ unknown field
        """.trimIndent()
        val issue = CargoDiagnostics.issues(output, jsonDiagnostics = false).single()
        assertEquals("programs/escrow/src/lib.rs", issue.path)
        assertEquals(17, issue.line)
        assertEquals(26, issue.column)
        assertEquals("E0609", issue.code)
    }

    @Test
    fun `a warning is a warning and keeps its position`() {
        val output = """
            warning: unused import: `std::mem`
             --> programs/escrow/src/lib.rs:2:5
        """.trimIndent()
        val issue = CargoDiagnostics.issues(output, jsonDiagnostics = false).single()
        assertEquals(DiagnosticSeverity.Warning, issue.severity)
        assertEquals(2, issue.line)
        assertEquals(5, issue.column)
        assertNull(issue.code)
    }

    @Test
    fun `a proc-macro panic is an error with the position of the derive`() {
        val output = """
            error: proc-macro derive panicked
              --> programs/escrow/src/lib.rs:41:10
               |
            41 | #[derive(Accounts)]
               |          ^^^^^^^^
               |
               = help: message: called `Option::unwrap()` on a `None` value
        """.trimIndent()
        val issues = CargoDiagnostics.issues(output, jsonDiagnostics = false)
        assertEquals(1, issues.size)
        assertEquals("proc-macro derive panicked", issues.single().message)
        assertEquals(41, issues.single().line)
    }

    @Test
    fun `cargo's epilogue is printed but is not counted as another error`() {
        val output = """
            error[E0609]: no field `esrow` on type `EscrowBumps`
              --> programs/escrow/src/lib.rs:17:26
            error: aborting due to 1 previous error
            error: could not compile `escrow` (lib) due to 1 previous error
        """.trimIndent()
        val events = CargoDiagnostics.parseAll(output, jsonDiagnostics = false)
        val issues = events.filterIsInstance<BuildLogEvent.Issue>()
        assertEquals(1, issues.size)
        // …and both epilogue lines still reach the log.
        val text = events.filterIsInstance<BuildLogEvent.Text>().map { it.line }
        assertTrue(text.any { it.startsWith("error: aborting") })
        assertTrue(text.any { it.startsWith("error: could not compile") })
    }

    @Test
    fun `a diagnostic with no location is still a diagnostic`() {
        val output = """
            error: linking with `rust-lld` failed
            Compiling escrow v0.1.0
        """.trimIndent()
        val issue = CargoDiagnostics.issues(output, jsonDiagnostics = false).single()
        assertNull(issue.path)
        assertEquals(0, issue.line)
        assertNull(issue.location)
    }

    // --- the producers that are not rustc -------------------------------------

    @Test
    fun `an anchor IDL failure is an error`() {
        val issue = CargoDiagnostics
            .issues("Error: failed to generate IDL", jsonDiagnostics = false)
            .single()
        assertEquals("failed to generate IDL", issue.message)
        assertEquals(DiagnosticSeverity.Error, issue.severity)
    }

    @Test
    fun `an unreadable keypair is an error`() {
        val issue = CargoDiagnostics
            .issues("Error: Unable to read keypair file", jsonDiagnostics = false)
            .single()
        assertTrue(issue.message.contains("keypair"))
    }

    @Test
    fun `Failed to execute rustup explains why rustup has to be there`() {
        val issue = CargoDiagnostics
            .issues("Failed to execute rustup: No such file or directory (os error 2)", false)
            .single()
        assertEquals("toolchain", issue.code)
        assertTrue(issue.message.contains("rustup"))
        // The sentence that turns a dead end into an action.
        assertTrue(issue.message.contains("platform-tools"))
    }

    @Test
    fun `an lld failure is an error tagged as a link problem`() {
        val issue = CargoDiagnostics
            .issues("rust-lld: error: undefined symbol: sol_invoke_signed_c", false)
            .single()
        assertEquals("link", issue.code)
        assertTrue(issue.message.contains("undefined symbol"))
    }

    @Test
    fun `a mocha failure points at the test file and keeps its own words`() {
        // Built line by line rather than with trimIndent(): mocha's *indentation*
        // is what distinguishes a failure block from ordinary output, and
        // trimIndent() would take it away.
        val output = listOf(
            "  1) escrow",
            "       is initialized!:",
            "     Error: failed to send transaction: Blockhash not found",
            "      at Context.<anonymous> (tests/escrow.ts:15:5)",
            "      at processTicksAndRejections (node:internal/process/task_queues:95:5)",
        ).joinToString("\n")
        val events = CargoDiagnostics.parseAll(output, jsonDiagnostics = false)
        val issue = events.filterIsInstance<BuildLogEvent.Issue>().single().issue
        assertEquals("tests/escrow.ts", issue.path)
        assertEquals(15, issue.line)
        assertEquals(5, issue.column)
        assertEquals("test", issue.code)
        assertTrue(issue.message.contains("escrow"))
        // mocha's block is readable and is not swallowed the way a rustc
        // header is: every one of its five lines still reaches the log.
        assertEquals(5, events.filterIsInstance<BuildLogEvent.Text>().size)
    }

    @Test
    fun `a mocha failure that never names a file is still reported`() {
        val output = listOf("  1) escrow initialize", "", "  0 passing").joinToString("\n")
        val issues = CargoDiagnostics.issues(output, jsonDiagnostics = false)
        assertEquals(1, issues.size)
        assertNull(issues.single().path)
    }

    // --- the property the design rests on -------------------------------------

    @Test
    fun `every line of an ordinary build survives as text`() {
        val output = """
            BPF SDK: /opt/solana/platform-tools
            Compiling escrow v0.1.0 (/projects/escrow/programs/escrow)
            Finished `release` profile [optimized] target(s) in 1m 11s
        """.trimIndent()
        val events = CargoDiagnostics.parseAll(output, jsonDiagnostics = false)
        assertEquals(3, events.size)
        assertTrue(events.all { it is BuildLogEvent.Text })
    }

    @Test
    fun `a line that looks like json but is not comes back as text`() {
        val line = """{"reason":"unterminated"""
        val events = CargoDiagnostics.parseAll(line)
        assertEquals(1, events.size)
        assertEquals(line, (events.single() as BuildLogEvent.Text).line)
    }

    @Test
    fun `a header at the very end of the stream is flushed rather than dropped`() {
        val parser = CargoDiagnostics(jsonDiagnostics = false)
        assertTrue(parser.feed("error: linking failed").isEmpty())
        val flushed = parser.flush()
        assertEquals(1, flushed.size)
        assertNotNull(flushed.single() as? BuildLogEvent.Issue)
    }

    @Test
    fun `mixed json and plain output keeps both`() {
        val output = e0609Json + "\n" + "Compiling anchor-lang v0.31.1"
        val events = CargoDiagnostics.parseAll(output)
        assertEquals(1, events.filterIsInstance<BuildLogEvent.Issue>().size)
        assertEquals(1, events.filterIsInstance<BuildLogEvent.Text>().size)
    }
}
