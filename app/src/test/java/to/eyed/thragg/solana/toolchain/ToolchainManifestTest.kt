package to.eyed.thragg.solana.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest is data, so these are assertions about *the shipped file*, not
 * about a fixture.
 *
 * That is the point of the chunk: "a toolchain bump is a manifest edit rather
 * than a release" (docs/SOLANA.md), which is only safe if a bad edit fails on
 * the host in a second rather than on a phone after 505 MB. Every check here
 * is one that has a failure mode measured on the device behind it.
 *
 * The file is read off the source tree rather than the classpath: Gradle runs
 * a unit test with the module directory as its working directory, and the
 * manifest ships as an asset (see the note on [ToolchainManifest]).
 */
class ToolchainManifestTest {

    private val manifest = ToolchainManifest.parse(manifestText())

    private fun manifestText(): String {
        val relative = "src/main/assets/${ToolchainManifest.ASSET_PATH}"
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("the toolchain manifest is not at $relative (cwd ${File(".").absolutePath})")
        return file.readText()
    }

    @Test
    fun `parses the shipped manifest`() {
        assertEquals(ToolchainManifest.SCHEMA, manifest.schema)
        assertTrue(manifest.components.isNotEmpty())
    }

    /**
     * Eight rows, every one of them a sized download, which is what the Setup
     * screen promises in words (docs/UI.md, "First run"). The two build
     * drivers used to compile on the phone; since 2026-09-02 they come
     * prebuilt from cesp99/solana-tools-arm64, and a row going back to
     * `cargo-install` without the screen's copy changing would be a silent
     * nine-minute lie.
     */
    @Test
    fun `lists eight components, none of them compiled on the device`() {
        assertEquals(8, manifest.components.size)
        assertEquals(0, manifest.components.count { it.isCompiled })
    }

    /**
     * Every fetched component pins a hash, and it is a real SHA-256.
     *
     * The three that pin nothing are the three that fetch nothing this way:
     * the Debian rootfs verifies the registry's own digest as it streams, the
     * apt step is Debian's package signing, and the editor's Rust is fetched
     * and verified by rustup. Five fetch: the three upstream binaries and the
     * two drivers from our own build repository.
     */
    @Test
    fun `every downloaded component pins a sha256 and an https url`() {
        val fetched = manifest.components.filter { it.url != null }
        assertEquals(5, fetched.size)
        for (component in fetched) {
            val sha = component.sha256
            assertNotNull("${component.id} has no sha256", sha)
            assertTrue(
                "${component.id}'s sha256 is not 64 hex characters",
                sha!!.matches(Regex("[0-9a-f]{64}")),
            )
            assertTrue(
                "${component.id} is not fetched over https",
                component.url!!.startsWith("https://"),
            )
            assertTrue("${component.id} declares no size", component.downloadBytes > 0L)
        }
    }

    /**
     * The tools version and the platform-tools URL have to agree.
     *
     * `cargo-build-sbf --tools-version` is passed the former while the latter
     * decides what is actually on disk, and a mismatch does not fail at
     * install time — it fails much later, inside a build, as a toolchain the
     * driver cannot find.
     */
    @Test
    fun `the platform-tools url carries the version the driver is told`() {
        val platformTools = manifest.component("platform-tools")
        assertNotNull(platformTools)
        assertTrue(
            "platform-tools' url does not contain ${manifest.platformToolsVersion}",
            platformTools!!.url!!.contains("/${manifest.platformToolsVersion}/"),
        )
        assertTrue(platformTools.url!!.endsWith("linux-aarch64.tar.bz2"))
    }

    /**
     * rustup must be installed before platform-tools, because platform-tools
     * registers *itself* with rustup in its own postInstall — and `rustup
     * toolchain link` against a rustup that is not there is the failure that
     * surfaces as "Failed to execute rustup" during the first build, not
     * during setup (docs/SOLANA.md).
     */
    @Test
    fun `rustup is installed before platform-tools links itself into it`() {
        val ids = manifest.components.map { it.id }
        assertTrue(ids.indexOf("rustup") < ids.indexOf("platform-tools"))
        val links = manifest.component("platform-tools")!!.postInstall
        assertTrue(links.any { it.contains("link") && it.contains("thragg") })
        assertTrue(links.any { it.contains("default") && it.contains("thragg") })
    }

    /**
     * cargo-build-sbf's install must seed the driver's own tools cache with
     * symlinks to the platform-tools that is already installed — for the
     * manifest's version *and* for the driver's own pinned default (v1.56
     * for 4.2.0) — because a cold cache does not fail: it makes the first
     * build download ~450 MB of a toolchain the phone already has, which on
     * the 2026-08 device rehearsal ran 27 minutes and died. `ln -sfn`,
     * because devices repaired by hand already carry the same symlink.
     */
    @Test
    fun `cargo-build-sbf seeds the tools cache it would otherwise re-download`() {
        val seed = manifest.component("cargo-build-sbf")!!
            .postInstall.flatten().joinToString(" ")
        assertTrue(
            "the seeding must be idempotent over hand-made symlinks (ln -sfn)",
            seed.contains("ln -sfn /opt/solana/platform-tools /root/.cache/solana/"),
        )
        assertTrue(
            "the cache is not seeded for platformToolsVersion " +
                "(${manifest.platformToolsVersion}) — --tools-version would download",
            seed.contains(manifest.platformToolsVersion),
        )
        assertTrue(
            "the cache is not seeded for 4.2.0's rehearsal-proven pin (v1.56) — " +
                "a bare `anchor build` would download",
            seed.contains("v1.56"),
        )
        // Belt and braces for a future bump: the pin is also discovered from
        // the driver itself, which prints it without triggering the download.
        assertTrue(seed.contains("cargo-build-sbf --version"))
    }

    /**
     * Every tag a driver can ask for is seeded, and each one is owned: the
     * manifest's own version, cargo-build-sbf 4.2.0's pin, and the v1.52
     * anchor-cli 1.1.2 hard-codes (`BUILD_SUBCOMMAND` in its src/lib.rs). The
     * day this failed on the phone, `anchor build` spent 5 min 51 s pulling
     * 1.6 GB it already had and then lost its IDL step to a relinked rustup.
     * Anchor's own postInstall seeds its tag too, for the install-time half.
     */
    @Test
    fun `every platform-tools tag a driver asks for is seeded`() {
        val seeds = manifest.toolsCacheSeeds
        assertTrue(manifest.platformToolsVersion in seeds)
        assertTrue("cargo-build-sbf 4.2.0's pin (v1.56) is not seeded", "v1.56" in seeds)
        assertTrue("anchor-cli 1.1.2's --tools-version (v1.52) is not seeded", "v1.52" in seeds)
        val anchorSeed = manifest.component("anchor")!!.postInstall.flatten().joinToString(" ")
        assertTrue(anchorSeed.contains("ln -sfn /opt/solana/platform-tools /root/.cache/solana/"))
        assertTrue(anchorSeed.contains("v1.52"))
    }

    /**
     * rustup is the *manager*, never a compiler: platform-tools already
     * carries a host toolchain, and a second Rust would be a gigabyte of
     * download nothing ever runs.
     */
    @Test
    fun `rustup installs no toolchain of its own`() {
        val rustup = manifest.component("rustup")!!
        val init = rustup.postInstall.first { it.first().endsWith("rustup-init") }
        assertTrue(init.containsAll(listOf("--default-toolchain", "none")))
        assertFalse(init.contains("--modify-path"))
    }

    /**
     * The two drivers wait for apt and platform-tools: both link libssl,
     * which apt brings, and cargo-build-sbf execs rustup's `thragg`
     * toolchain, which platform-tools' postInstall links. Their verify runs
     * the binary, so it must not run before either is in. apt still carries
     * build-essential, because a *user's* program needs a C linker.
     */
    @Test
    fun `the prebuilt drivers wait for apt and platform-tools`() {
        for (id in listOf("cargo-build-sbf", "anchor")) {
            val needs = manifest.component(id)!!.needs.toSet()
            assertTrue("$id does not wait for apt", "apt-build-tools" in needs)
            assertTrue("$id does not wait for platform-tools", "platform-tools" in needs)
        }
        assertTrue(manifest.component("apt-build-tools")!!.packages.contains("gcc"))
    }

    /**
     * Every component names a marker that proves it landed, as a guest
     * absolute path — this is what catches `cargo install`'s dangling symlink
     * under proot's `--link2symlink`, and it is only a check if it exists.
     */
    @Test
    fun `every component has a guest-absolute marker`() {
        for (component in manifest.components) {
            assertTrue(
                "${component.id}'s marker is not absolute",
                component.marker.startsWith("/"),
            )
            assertFalse(component.markerInRootfs.startsWith("/"))
        }
    }

    /**
     * The two drivers come from our own build repository, and from the
     * release whose tag names the version the row pins — so a bump that
     * edits the version and forgets the URL, or the other way round, fails
     * here rather than installing the wrong binary under the right name.
     */
    @Test
    fun `the prebuilt drivers come from the build repository, tagged with their version`() {
        val repo = "https://github.com/cesp99/solana-tools-arm64/releases/download/"
        for ((id, tool) in listOf("cargo-build-sbf" to "cargo-build-sbf", "anchor" to "anchor-cli")) {
            val component = manifest.component(id)!!
            assertEquals(InstallMethod.Tarball, component.method)
            val version = component.version ?: error("$id pins no version")
            assertEquals(
                "$repo$tool-v$version/$tool-v$version-aarch64-unknown-linux-gnu.tar.gz",
                component.url,
            )
            assertEquals("/opt/solana/cli", component.installPath)
            assertTrue("${component.id} has no download size", component.downloadBytes > 0L)
        }
    }

    /**
     * Everything Build actually needs is marked required, and the three that
     * are conveniences are not: the toolchain must not be reported as absent
     * because the agent's binary is missing.
     */
    @Test
    fun `required components are the ones Build cannot run without`() {
        assertEquals(
            listOf("debian", "rustup", "platform-tools", "apt-build-tools", "cargo-build-sbf"),
            manifest.requiredIds,
        )
    }

    /**
     * The revision is what makes a bump re-install one row. Distinct per
     * component, and derived from the hash where there is one, so editing a
     * URL without editing its hash cannot silently reuse the old bytes.
     */
    @Test
    fun `revisions are per component and follow the hash`() {
        val revisions = manifest.components.map { it.revision }
        assertEquals(revisions.size, revisions.toSet().size)
        val platformTools = manifest.component("platform-tools")!!
        assertEquals(platformTools.sha256, platformTools.revision)
    }

    /**
     * The two headline numbers, which the Setup screen sums rather than
     * writes down. Asserted as ranges: the point is that they are different
     * quantities of the right magnitude, not that they are exact.
     */
    @Test
    fun `the manifest states a transfer and a disk cost, and they differ`() {
        assertTrue(manifest.totalDownloadBytes > 500_000_000L)
        assertTrue(manifest.totalDownloadBytes < 1_000_000_000L)
        assertTrue(manifest.totalInstallBytes > manifest.totalDownloadBytes * 2)
    }

    @Test
    fun `a manifest from the future is refused rather than half understood`() {
        val text = manifestText().replaceFirst("\"schema\": 1", "\"schema\": 2")
        val failure = runCatching { ToolchainManifest.parse(text) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("schema"))
    }

    @Test
    fun `a component with an unknown install method is refused`() {
        val text = manifestText().replaceFirst("\"method\": \"tarball\"", "\"method\": \"magic\"")
        val failure = runCatching { ToolchainManifest.parse(text) }.exceptionOrNull()
        assertNotNull(failure)
    }

    // ---- The dependency graph ----------------------------------------------

    /**
     * The install is a graph now, and these are the edges that were paid for
     * on the device: platform-tools' postInstall execs rustup, and the two
     * drivers' verify runs a binary that links apt's libssl and (for
     * cargo-build-sbf) execs the rustup toolchain platform-tools links.
     */
    @Test
    fun `the load-bearing edges are in the graph`() {
        assertEquals(listOf("rustup"), manifest.component("platform-tools")!!.needs)
        for (id in listOf("cargo-build-sbf", "anchor")) {
            assertEquals(
                setOf("platform-tools", "apt-build-tools"),
                manifest.component(id)!!.needs.toSet(),
            )
        }
    }

    /** Everything but the userland waits for the userland, one way or another. */
    @Test
    fun `every component but the userland depends on the userland`() {
        val byId = manifest.components.associateBy { it.id }
        fun reaches(id: String, target: String): Boolean =
            byId.getValue(id).needs.any { it == target || reaches(it, target) }
        for (component in manifest.components) {
            if (component.id == "debian") assertTrue(component.needs.isEmpty())
            else assertTrue("${component.id} does not reach debian", reaches(component.id, "debian"))
        }
    }

    @Test
    fun `a need that names no component is refused`() {
        val text = manifestText().replaceFirst("\"needs\": [\"rustup\"]", "\"needs\": [\"rustop\"]")
        val failure = runCatching { ToolchainManifest.parse(text) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("rustop"))
    }

    @Test
    fun `a dependency cycle is refused`() {
        // rustup needs platform-tools, and platform-tools already needs rustup.
        val text = manifestText().replaceFirst("\"needs\": [\"debian\"]", "\"needs\": [\"platform-tools\"]")
        val failure = runCatching { ToolchainManifest.parse(text) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("cycle"))
    }

    /** The gate opens on the required rows; none of them may wait on Anchor. */
    @Test
    fun `a required component never waits on an optional one`() {
        val byId = manifest.components.associateBy { it.id }
        for (component in manifest.components.filter { it.required }) {
            for (need in component.needs) assertTrue(byId.getValue(need).required)
        }
        val text = manifestText().replaceFirst(
            "\"needs\": [\"platform-tools\", \"apt-build-tools\"]",
            "\"needs\": [\"platform-tools\", \"apt-build-tools\", \"anchor\"]",
        )
        assertNotNull(runCatching { ToolchainManifest.parse(text) }.exceptionOrNull())
    }

    // ---- The estimate ------------------------------------------------------

    /**
     * Every row carries a measured time, and the wall estimate is the guest
     * lane's sum — the userland, apt, and the editor's Rust, whose download
     * rustup does inside the guest — not the serial total, because the fetch
     * lane hides every other download behind them.
     */
    @Test
    fun `every component is measured and the wall estimate is the guest lane`() {
        for (component in manifest.components) {
            assertTrue("${component.id} has no estimatedSeconds", component.estimatedSeconds > 0L)
        }
        val lane = manifest.components.filter { it.onGuestLane }.map { it.id }.toSet()
        assertEquals(setOf("debian", "rust-editor", "apt-build-tools"), lane)
        assertTrue(manifest.estimatedWallSeconds < manifest.totalEstimatedSeconds)
        assertEquals(
            manifest.components.filter { it.id in lane }.sumOf { it.estimatedSeconds },
            manifest.estimatedWallSeconds,
        )
    }

    // ---- Updates -------------------------------------------------------------

    /**
     * The manifest carries the date the Update button compares, in a form
     * whose lexical order is date order, and the remote copy it fetches is
     * the same file over https from the repository that builds the drivers.
     * A manifest without a date must fail to parse: an undated remote could
     * never be told apart from the asset.
     */
    @Test
    fun `the manifest is dated, and the remote copy is fetched over https`() {
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(manifest.released))
        assertTrue("2026-09-02" <= manifest.released)
        assertTrue(ToolchainManifest.REMOTE_URL.startsWith("https://raw.githubusercontent.com/cesp99/solana-tools-arm64/"))
        assertTrue(ToolchainManifest.REMOTE_URL.endsWith("/manifest.json"))
        val undated = manifestText().replaceFirst(Regex("\"released\": \"[^\"]*\","), "")
        assertNotNull(runCatching { ToolchainManifest.parse(undated) }.exceptionOrNull())
    }

    /** apt installs a C toolchain, not a C++ one: g++ was 40 % of dpkg's work for nothing. */
    @Test
    fun `apt installs gcc and the crt files, not build-essential`() {
        val packages = manifest.component("apt-build-tools")!!.packages
        assertTrue(packages.containsAll(listOf("gcc", "libc6-dev", "make", "git", "libssl-dev", "pkg-config")))
        assertFalse(packages.contains("build-essential"))
    }

    /** Sizes read the way a download page writes them. */
    @Test
    fun `formats bytes the way the screen quotes them`() {
        assertEquals("505 MB", formatBytes(505_000_000L))
        assertEquals("1.4 GB", formatBytes(1_400_000_000L))
        assertEquals("30 MB", formatBytes(30_000_000L))
    }

    /**
     * The editor's Rust is a rustup install pinned by version and linked
     * under the one name the engine knows (lsp.rs, `EDITOR_TOOLCHAIN`): a
     * bump here that forgot the link would leave rust-analyzer running on
     * the previous toolchain, or on none.
     */
    @Test
    fun `the editor toolchain is pinned, brings rust-analyzer, and is linked as thragg-editor`() {
        val editor = manifest.components.first { it.id == "rust-editor" }
        assertEquals(InstallMethod.RustupToolchain, editor.method)
        assertTrue(editor.isTimed)
        assertFalse(editor.isCompiled)
        assertTrue(editor.onGuestLane)
        assertFalse(editor.required)
        assertTrue("rustup" in editor.needs)
        val version = editor.version
        assertNotNull(version)
        assertTrue(version!!.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        assertTrue("rust-analyzer" in editor.packages)
        // The standard library's source, or rust-analyzer resolves nothing
        // from std and says "can't load standard library from sysroot".
        assertTrue("rust-src" in editor.packages)
        assertTrue(editor.marker.contains(version))
        assertTrue(editor.marker.endsWith("/libexec/rust-analyzer-proc-macro-srv"))
        val link = editor.postInstall.single()
        assertEquals(listOf("toolchain", "link", "thragg-editor"), link.subList(1, 4))
        assertTrue(link.last().contains(version))
        assertTrue(editor.verify!!.contains("+thragg-editor"))
    }
}
