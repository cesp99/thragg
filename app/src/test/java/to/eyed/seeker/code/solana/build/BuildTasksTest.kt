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
        val anchor = BuildTasks.buildCommand(layout(ProjectFramework.Anchor), GuestTools())!!
        assertTrue(anchor.line.endsWith("anchor build"))
        assertEquals("anchor build", anchor.display)
        val seahorse = BuildTasks.buildCommand(layout(ProjectFramework.Seahorse), GuestTools())!!
        assertTrue(seahorse.line.endsWith("seahorse build"))
        assertEquals("seahorse build", seahorse.display)
    }

    @Test
    fun `native asks cargo for json diagnostics through cargo-build-sbf`() {
        val command = BuildTasks.buildCommand(
            layout(ProjectFramework.Native),
            GuestTools(cargoBuildSbf = true, platformCargo = true),
        )!!
        assertTrue(
            command.line.endsWith(
                "cargo build-sbf -- --message-format=json-diagnostic-rendered-ansi"
            )
        )
        assertTrue(command.jsonDiagnostics)
        assertNull(command.note)
    }

    /**
     * The rehearsal incident this table must never repeat: without
     * `--tools-version <installed>`, cargo-build-sbf 4.2.0 ignores the 1.4 GB
     * platform-tools already on the phone and downloads its own pinned
     * release — 27 minutes into a demo, over the venue's Wi-Fi.
     */
    @Test
    fun `the manifest's tools version is passed to cargo-build-sbf`() {
        val command = BuildTasks.buildCommand(
            layout(ProjectFramework.Native),
            GuestTools(cargoBuildSbf = true),
            platformToolsVersion = "v1.57",
        )!!
        assertTrue(
            command.line.endsWith(
                "cargo build-sbf --tools-version v1.57 " +
                    "-- --message-format=json-diagnostic-rendered-ansi"
            )
        )
        // The display is what the log's first row and the agent prompt see,
        // and the flag is part of how the command actually works.
        assertEquals("cargo build-sbf --tools-version v1.57", command.display)
        // And the guard seeds the cache for that same version, so even a
        // driver that ignores the flag finds its download already satisfied.
        assertTrue(command.line.contains("/root/.cache/solana/"))
        assertTrue(command.line.contains("for v in v1.57 "))
    }

    /**
     * cargo-build-sbf's tools-install step uninstalls rustup's `solana`
     * toolchain and links its own; the next build then dies with "override
     * toolchain 'solana' is not installed" (the rehearsal's second hand
     * repair). Every command line that reaches cargo-build-sbf — or rustup's
     * cargo shim at all — must therefore relink first, silently.
     */
    @Test
    fun `every rustup-shimmed command repairs the solana toolchain link first`() {
        val guarded = listOf(
            BuildTasks.buildCommand(
                layout(ProjectFramework.Native),
                GuestTools(cargoBuildSbf = true),
                "v1.57",
            )!!,
            BuildTasks.buildCommand(layout(ProjectFramework.Anchor), GuestTools(), "v1.57")!!,
            BuildTasks.buildCommand(layout(ProjectFramework.Seahorse), GuestTools(), "v1.57")!!,
            BuildTasks.testCommand(layout(ProjectFramework.Anchor), "v1.57")!!,
            BuildTasks.testCommand(layout(ProjectFramework.Native), "v1.57")!!,
        )
        for (command in guarded) {
            val line = command.line
            assertTrue(
                "$line does not relink platform-tools as the default",
                line.contains(
                    "rustup toolchain link seeker ${BuildTasks.PLATFORM_TOOLS}/rust; rustup default seeker"
                ),
            )
            // The name must never contain "solana": cargo-build-sbf uninstalls
            // the first rustup toolchain whose name does (BuildTasks, guard).
            assertFalse(line.contains("toolchain link solana"))
            assertTrue(
                "$line seeds no tools cache",
                line.contains(
                    "ln -sfn ${BuildTasks.PLATFORM_TOOLS} ${BuildTasks.TOOLS_CACHE}/"
                ),
            )
            // stdout is the JSON diagnostics pipe; the guard must be silenced
            // and finished before the real command starts.
            assertTrue(
                "$line leaks guard output into the diagnostics pipe",
                line.contains(">/dev/null 2>&1; "),
            )
            assertTrue(
                "$line runs the guard after the build",
                line.indexOf("rustup toolchain link seeker") <
                    line.indexOf(command.display.substringBefore(' ')),
            )
        }
    }

    /**
     * The manifest's seeds reach the guard, each as a `mkdir -p` + `ln -sfn`
     * pair — this is the line that keeps anchor-cli's hard-coded v1.52 from
     * turning every Anchor build into a 450 MB download.
     */
    @Test
    fun `the guard seeds every tag the manifest lists`() {
        val line = BuildTasks.toolchainGuard("v1.57", listOf("v1.56", "v1.52"))
        assertTrue(line.contains("for v in v1.57 v1.56 v1.52 \$(cargo-build-sbf --version"))
        assertTrue(line.contains("ln -sfn ${BuildTasks.PLATFORM_TOOLS} ${BuildTasks.TOOLS_CACHE}/\$v/platform-tools"))
    }

    @Test
    fun `the guard survives a manifest that failed to load`() {
        // Null version: no --tools-version (there is nothing true to pass),
        // but the relink and the discovered-pin seeding still run.
        val command = BuildTasks.buildCommand(
            layout(ProjectFramework.Native),
            GuestTools(cargoBuildSbf = true),
            platformToolsVersion = null,
        )!!
        assertFalse(command.line.contains("--tools-version"))
        assertTrue(command.line.contains("rustup toolchain link seeker"))
        assertTrue(command.line.contains("cargo-build-sbf --version"))
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
