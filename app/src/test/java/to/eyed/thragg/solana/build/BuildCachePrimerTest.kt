package to.eyed.thragg.solana.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.eyed.thragg.solana.templates.TemplateFile
import java.io.File

/**
 * The warm-up's four decisions, on the host: what makes the cache stale,
 * what each step runs and where, when it may run at all, and whether the
 * cache directory backs the record's claim.
 *
 * The primed key is the one that matters most. A key that misses a change
 * leaves a phone believing its cache holds the new scaffold's dependencies
 * — and the next new project pays the 4 min 54 s the primer exists to
 * remove, with a row that says "primed". A key that changes for nothing
 * primes 582 MB again for nothing.
 */
class BuildCachePrimerTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A run id in the shape the primer uses: its start, epoch millis. */
    private val runId = 1_758_260_000_000L

    private val manifests = listOf(
        TemplateFile("anchor/Cargo.toml", "[workspace]\nmembers = [\"programs/*\"]\n"),
        TemplateFile("anchor/programs/x/Cargo.toml", "[dependencies]\nanchor-lang = \"1.2\"\n"),
        TemplateFile("native/Cargo.toml", "[dependencies]\nsolana-program = \"5.0\"\n"),
    )
    private val revisions = mapOf(
        "cargo-build-sbf" to "605b",
        "anchor" to "ac89",
        "platform-tools" to "f2f3",
    )

    private fun key(
        manifests: List<TemplateFile> = this.manifests,
        arch: String? = "v3",
        tools: String? = "v1.57",
        revisions: Map<String, String> = this.revisions,
    ) = BuildCachePrimer.key(manifests, arch, tools, revisions)

    // --- the key ---------------------------------------------------------------------

    @Test
    fun `the key is stable over the same inputs and is a sha256`() {
        assertEquals(key(), key())
        assertEquals(64, key().length)
        assertTrue(key().all { it in '0'..'9' || it in 'a'..'f' })
        // The record's map order is not an input.
        assertEquals(key(), key(revisions = revisions.entries.reversed().associate { it.key to it.value }))
    }

    @Test
    fun `a scaffold manifest change changes the key`() {
        val bumped = manifests.map {
            if (it.path == "native/Cargo.toml") it.copy(contents = it.contents.replace("5.0", "5.1")) else it
        }
        assertNotEquals(key(), key(manifests = bumped))
        // A file moving is a change too: the path is hashed with the contents.
        val moved = manifests.map { if (it.path == "native/Cargo.toml") it.copy(path = "native/Other.toml") else it }
        assertNotEquals(key(), key(manifests = moved))
    }

    @Test
    fun `the arch and the tools version change the key`() {
        assertNotEquals(key(), key(arch = "v0"))
        assertNotEquals(key(), key(arch = null))
        assertNotEquals(key(), key(tools = "v1.56"))
        assertNotEquals(key(), key(tools = null))
    }

    @Test
    fun `each keyed driver revision changes the key, and only those`() {
        for (id in BuildCachePrimer.KEYED_COMPONENTS) {
            assertNotEquals(id, key(), key(revisions = revisions + (id to "0000")))
            assertNotEquals(id, key(), key(revisions = revisions - id))
        }
        // Node, Spettro, the editor's Rust: none of them decides what cargo
        // writes into the cache, so an update to them must not re-prime it.
        assertEquals(key(), key(revisions = revisions + ("node" to "22.23.2") + ("spettro" to "abcd")))
    }

    /**
     * The real scaffolds: every `*.toml` of both, nothing else, and each
     * under its step's directory so the two `Cargo.toml`s cannot collapse
     * into one entry.
     */
    @Test
    fun `the manifests are every toml of both scaffolds`() {
        val paths = BuildCachePrimer.manifests().map { it.path }
        assertEquals(
            listOf(
                "anchor/Anchor.toml",
                "anchor/Cargo.toml",
                "anchor/programs/thragg-warm-anchor/Cargo.toml",
                "native/Cargo.toml",
            ),
            paths,
        )
        assertTrue(paths.all { it.endsWith(".toml") })
        // And the dummy names are fixed, so the key is a fact about the
        // app's templates and not about any project the user named.
        val program = BuildCachePrimer.manifests().first { it.path.startsWith("anchor/programs/") }
        assertTrue(program.contents.contains("name = \"thragg-warm-anchor\""))
        assertTrue(program.contents.contains("name = \"thragg_warm_anchor\""))
    }

    // --- the command lines ----------------------------------------------------------

    @Test
    fun `the anchor step runs the build button's anchor build, guarded, niced, in the warm dir`() {
        val line = BuildCachePrimer.stepLine(BuildCachePrimer.Step.Anchor, runId, "v1.57", "v3", listOf("v1.56"))
        assertTrue(line.startsWith("cd /opt/solana/build/warm/$runId/anchor || exit 1; "))
        assertTrue(line.endsWith("nice -n 10 anchor build --arch v3 --tools-version v1.57"))
        // The same guard a real build gets, with the same seeds, and before
        // the command — its relink is what keeps the IDL step alive.
        assertTrue(line.contains(BuildTasks.toolchainGuard("v1.57", listOf("v1.56"))))
        assertTrue(line.indexOf("rustup toolchain link thragg") < line.indexOf("nice -n 10"))
        // Its own `cd` comes first: the guard's mkdir and relink do not care
        // where they run, cargo does.
        assertTrue(line.indexOf("cd /opt/solana/build/warm/$runId/anchor") < line.indexOf("rustup toolchain link thragg"))
        // The display string is BuildTasks' own, so the two cannot drift.
        val build = BuildTasks.buildCommand(
            ProjectLayout("/x", ProjectFramework.Anchor, emptyList()),
            GuestTools(),
            "v1.57",
            "v3",
        )!!
        assertTrue(line.endsWith("nice -n 10 ${build.display}"))
    }

    @Test
    fun `the native step builds the program then compiles its tests without running them`() {
        val line = BuildCachePrimer.stepLine(BuildCachePrimer.Step.Native, runId, "v1.57", "v3")
        assertTrue(line.startsWith("cd /opt/solana/build/warm/$runId/native || exit 1; "))
        assertTrue(
            line.endsWith(
                "nice -n 10 cargo build-sbf --arch v3 --tools-version v1.57 && nice -n 10 cargo test --no-run",
            ),
        )
        // No parser reads this run, so no --message-format on the line.
        assertTrue(!line.contains("--message-format"))
        assertTrue(line.contains("rustup toolchain link thragg"))
        // The second command is BuildTasks' own, so the two cannot drift.
        assertTrue(line.endsWith("nice -n 10 ${BuildTasks.cargoTestNoRunCommand().display}"))
    }

    // --- the run directory ------------------------------------------------------------

    /**
     * Each run under its own directory, named by its id, and each step
     * under that: a run's `finally` deletes only its own, so a corpse
     * still dying cannot take a successor's files, and two ids can never be
     * the same directory.
     */
    @Test
    fun `every run renders under its own directory, and the steps under that`() {
        assertEquals("/opt/solana/build/warm/$runId", BuildCachePrimer.runDir(runId))
        assertEquals("/opt/solana/build/warm/$runId/anchor", BuildCachePrimer.Step.Anchor.guestDir(runId))
        assertEquals("/opt/solana/build/warm/$runId/native", BuildCachePrimer.Step.Native.guestDir(runId))
        assertTrue(BuildCachePrimer.runDir(runId).startsWith(BuildCachePrimer.WARM_ROOT + "/"))
        assertNotEquals(BuildCachePrimer.runDir(runId), BuildCachePrimer.runDir(runId + 1))
        // And the line runs where the directory is, for both steps.
        for (step in BuildCachePrimer.Step.entries) {
            val line = BuildCachePrimer.stepLine(step, runId + 7, "v1.57", "v3")
            assertTrue(line.startsWith("cd ${step.guestDir(runId + 7)} || exit 1; "))
        }
    }

    @Test
    fun `a manifest without an arch or a tools version still runs, without the flags`() {
        val line = BuildCachePrimer.stepLine(BuildCachePrimer.Step.Anchor, runId, null, null)
        assertTrue(line.endsWith("nice -n 10 anchor build"))
        assertTrue(!line.contains("--arch"))
        assertTrue(!line.contains("--tools-version"))
    }

    // --- should it start ---------------------------------------------------------------

    private fun blocker(
        running: Boolean = false,
        primed: Boolean = false,
        cargoBuildSbf: Boolean = true,
        anchor: Boolean = true,
        userlandReady: Boolean = true,
        buildRunning: Boolean = false,
        installerRunning: Boolean = false,
        metered: Boolean = false,
    ) = BuildCachePrimer.startBlocker(
        running = running,
        primed = primed,
        cargoBuildSbf = cargoBuildSbf,
        anchor = anchor,
        userlandReady = userlandReady,
        buildRunning = buildRunning,
        installerRunning = installerRunning,
        metered = metered,
    )

    @Test
    fun `priming starts when not primed, the toolchain is usable and nothing else runs`() {
        assertNull(blocker())
    }

    @Test
    fun `each negation stops it, and says why`() {
        assertNotNull(blocker(primed = true))
        assertNotNull(blocker(cargoBuildSbf = false))
        assertNotNull(blocker(anchor = false))
        assertNotNull(blocker(userlandReady = false))
        assertNotNull(blocker(buildRunning = true))
        assertNotNull(blocker(installerRunning = true))
        // Primed is the answer even while a build runs: the row should say
        // "primed", not "a build is running".
        assertTrue(blocker(primed = true, buildRunning = true)!!.contains("primed"))
        assertTrue(blocker(anchor = false)!!.contains("Anchor"))
    }

    /**
     * A start while a run is live — the button pressed twice, a trigger
     * landing mid-run, a cancel still waiting for its corpse — is refused
     * before anything else is asked: the row is already saying "priming",
     * and nothing may be said over it.
     */
    @Test
    fun `a start while a run is live is refused, whatever else is true`() {
        assertNotNull(blocker(running = true))
        assertTrue(blocker(running = true)!!.contains("already running"))
        assertTrue(blocker(running = true, primed = true)!!.contains("already running"))
        assertTrue(blocker(running = true, anchor = false, buildRunning = true)!!.contains("already running"))
    }

    /**
     * The metered gate is the last "not right now": the automatic triggers
     * pass the network's answer and are refused with the word the log
     * greps for; the button passes false and is not. It never outranks a
     * reason that would still stand on Wi-Fi.
     */
    @Test
    fun `a metered connection stops the automatic triggers, and says so`() {
        assertEquals("metered", blocker(metered = true))
        assertNull(blocker(metered = false))
        assertTrue(blocker(metered = true, primed = true)!!.contains("primed"))
        assertTrue(blocker(metered = true, anchor = false)!!.contains("Anchor"))
        assertTrue(blocker(metered = true, buildRunning = true)!!.contains("build"))
    }

    // --- the cache directory ------------------------------------------------------------

    /**
     * What a landed run leaves in cargo's build-dir: `<triple>/release/
     * .fingerprint` for the SBF profile, `debug/.fingerprint` for the host
     * ones. Either is content; a directory with no `.fingerprint` at
     * either level is what "free the disk" or a scratch cleanup leaves and
     * must read as empty, whatever the record says.
     */
    @Test
    fun `the cache has content only when a profile directory holds a fingerprint`() {
        val cache = temp.newFolder("deps")
        assertFalse(BuildCachePrimer.cacheHasContent(cache))
        assertFalse(BuildCachePrimer.cacheHasContent(File(cache, "missing")))

        // A profile directory without cargo's bookkeeping is not content.
        File(cache, "debug/deps").mkdirs()
        assertFalse(BuildCachePrimer.cacheHasContent(cache))
        // Nor is a file that happens to be called .fingerprint.
        File(cache, "release").mkdirs()
        File(cache, "release/.fingerprint").writeText("")
        assertFalse(BuildCachePrimer.cacheHasContent(cache))

        // The host profile, straight under the root.
        File(cache, "debug/.fingerprint").mkdirs()
        assertTrue(BuildCachePrimer.cacheHasContent(cache))
    }

    @Test
    fun `the sbf profile under its triple counts too, and nothing deeper is looked at`() {
        val cache = temp.newFolder("deps")
        File(cache, "sbpfv3-solana-solana/release/.fingerprint").mkdirs()
        assertTrue(BuildCachePrimer.cacheHasContent(cache))

        val deeper = temp.newFolder("deeper")
        File(deeper, "a/b/release/.fingerprint").mkdirs()
        assertFalse(BuildCachePrimer.cacheHasContent(deeper))
    }
}
