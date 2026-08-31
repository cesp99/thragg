package to.eyed.seeker.code.solana.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framework → command table, and the manifest reading that decides which
 * row of it applies.
 *
 * Two of these are the difference between a build that deploys and one that
 * does not: `[lib] name` deciding the artifact's name, and the fallback to
 * platform-tools' cargo saying out loud that its output is in the wrong place.
 */
class BuildTasksTest {

    // --- manifests ---------------------------------------------------------------

    @Test
    fun `the lib name wins over the package name for the artifact`() {
        val manifest = BuildTasks.parseManifest(
            """
            [package]
            name = "my-program"
            version = "0.1.0"

            [lib]
            crate-type = ["cdylib", "lib"]
            name = "my_program"

            [dependencies]
            solana-program = "2.2"
            """.trimIndent()
        )
        val target = manifest.toTarget()!!
        assertEquals("my-program", target.crateName)
        assertEquals("my_program", target.moduleName)
        assertEquals("target/deploy/my_program.so", target.artifactPath)
    }

    @Test
    fun `without a lib name the crate name is hyphen-swapped, as cargo does it`() {
        val target = BuildTasks.parseManifest(
            """
            [package]
            name = "hello-solana"

            [dependencies]
            solana-program = "2.2"
            """.trimIndent()
        ).toTarget()!!
        assertEquals("hello_solana", target.moduleName)
        assertEquals("target/deploy/hello_solana.so", target.artifactPath)
    }

    @Test
    fun `a crate with no solana dependency is not a solana project`() {
        val manifest = BuildTasks.parseManifest(
            """
            [package]
            name = "notes"

            [dependencies]
            serde = "1"
            """.trimIndent()
        )
        assertFalse(manifest.dependsOnSolana)
    }

    @Test
    fun `anchor-lang counts as the dependency too, and comments do not`() {
        assertTrue(
            BuildTasks.parseManifest(
                """
                [package]
                name = "escrow"

                [dependencies]
                anchor-lang = { version = "0.31.1", features = ["init-if-needed"] }
                """.trimIndent()
            ).dependsOnSolana
        )
        assertFalse(
            BuildTasks.parseManifest(
                """
                [dependencies]
                # solana-program = "2.2"
                """.trimIndent()
            ).dependsOnSolana
        )
    }

    @Test
    fun `an Anchor toml's program table names the artifacts`() {
        val programs = BuildTasks.anchorTomlPrograms(
            """
            [features]
            resolution = true

            [programs.localnet]
            escrow = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"
            vault = "9wQtN4bR"

            [programs.devnet]
            escrow = "Fg6PaFpoGXkYsidMpWTK6W2BeZ7FEfcYkg476zPFsLnS"

            [provider]
            cluster = "Localnet"
            """.trimIndent()
        )
        assertEquals(listOf("escrow", "vault"), programs.map { it.crateName })
        assertEquals("target/deploy/escrow.so", programs.first().artifactPath)
    }

    // --- the command table ----------------------------------------------------------

    private fun layout(framework: ProjectFramework) = ProjectLayout(
        root = "/projects/escrow",
        framework = framework,
        programs = listOf(ProgramTarget("escrow", "escrow", "target/deploy/escrow.so")),
    )

    @Test
    fun `anchor and seahorse take their own commands`() {
        assertEquals(
            "anchor build",
            BuildTasks.buildCommand(layout(ProjectFramework.Anchor), GuestTools())!!.line,
        )
        assertEquals(
            "seahorse build",
            BuildTasks.buildCommand(layout(ProjectFramework.Seahorse), GuestTools())!!.line,
        )
    }

    @Test
    fun `native asks cargo for json diagnostics through cargo-build-sbf`() {
        val command = BuildTasks.buildCommand(
            layout(ProjectFramework.Native),
            GuestTools(cargoBuildSbf = true, platformCargo = true),
        )!!
        assertTrue(command.line.startsWith("cargo build-sbf"))
        assertTrue(command.jsonDiagnostics)
        assertNull(command.note)
    }

    @Test
    fun `without cargo-build-sbf the fallback runs and says what it costs`() {
        val command = BuildTasks.buildCommand(
            layout(ProjectFramework.Native),
            GuestTools(cargoBuildSbf = false, platformCargo = true),
        )!!
        assertTrue(command.line.contains(BuildTasks.PLATFORM_CARGO))
        assertTrue(command.line.contains("--target ${BuildTasks.SBF_TARGET}"))
        assertNotNull(command.note)
        // The whole point of the note: the artifact is not where a deploy
        // will look for it.
        assertTrue(command.note!!.contains("target/${BuildTasks.SBF_TARGET}/release/"))
    }

    @Test
    fun `with no compiler at all there is no command to run`() {
        assertNull(BuildTasks.buildCommand(layout(ProjectFramework.Native), GuestTools.NONE))
        assertNull(BuildTasks.buildCommand(layout(ProjectFramework.Unknown), GuestTools()))
    }

    @Test
    fun `test skips the local validator, because this phone has none`() {
        val command = BuildTasks.testCommand(layout(ProjectFramework.Anchor))!!
        assertTrue(command.line.contains("--skip-local-validator"))
        assertEquals("cargo test", BuildTasks.testCommand(layout(ProjectFramework.Native))!!.display)
    }

    @Test
    fun `only the anchor-shaped frameworks need node to test`() {
        assertTrue(BuildTasks.anchorTestNeedsNode(layout(ProjectFramework.Anchor)))
        assertTrue(BuildTasks.anchorTestNeedsNode(layout(ProjectFramework.Seahorse)))
        assertFalse(BuildTasks.anchorTestNeedsNode(layout(ProjectFramework.Native)))
    }

    // --- the environment ---------------------------------------------------------------

    @Test
    fun `the path puts the phone-built binaries first and platform-tools' rust nowhere`() {
        val path = BuildTasks.guestEnvironment().first { it.startsWith("PATH=") }
            .removePrefix("PATH=")
        val entries = path.split(':')
        assertEquals(BuildTasks.CARGO_BIN, entries[0])
        assertEquals(BuildTasks.CLI_BIN, entries[1])
        assertEquals(BuildTasks.LLVM_BIN, entries[2])
        assertTrue(entries.last().startsWith("/"))
        // The one that must NOT be there: a second cargo ahead of
        // $CARGO_HOME/bin shadows the cargo-build-sbf subcommand shim.
        assertFalse(entries.any { it.endsWith("platform-tools/rust/bin") })
    }

    @Test
    fun `rustup's home is exported even though it owns no compiler`() {
        val environment = BuildTasks.guestEnvironment()
        assertTrue(environment.any { it == "RUSTUP_HOME=${BuildTasks.RUSTUP_HOME}" })
        assertTrue(environment.any { it == "CARGO_HOME=${BuildTasks.CARGO_HOME}" })
    }

    // --- the summary line ----------------------------------------------------------------

    @Test
    fun `the summary line is the one docs slash UI dot md prints`() {
        assertEquals(
            "failed · 1 error, 1 warning · 1m11s",
            BuildRunner.summaryLine(BuildAction.Build, failed = true, 1, 1, 71_000),
        )
        assertEquals(
            "built · 34s",
            BuildRunner.summaryLine(BuildAction.Build, failed = false, 0, 0, 34_000),
        )
        assertEquals(
            "built · 2 warnings · 34s",
            BuildRunner.summaryLine(BuildAction.Build, failed = false, 0, 2, 34_000),
        )
    }

    @Test
    fun `the stop row's clock counts in minutes and seconds`() {
        assertEquals("0:38", BuildRunner.clock(38_000))
        assertEquals("1:11", BuildRunner.clock(71_000))
        assertEquals("0:00", BuildRunner.clock(-5))
    }
}
