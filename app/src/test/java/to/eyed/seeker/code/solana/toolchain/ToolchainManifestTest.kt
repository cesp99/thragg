package to.eyed.seeker.code.solana.toolchain

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
     * Eight rows: six sized and two built here, which is what the Setup screen
     * promises in words (docs/UI.md, "First run"). A ninth appearing without
     * the screen's copy changing would be a silent lie.
     */
    @Test
    fun `lists eight components, two of them on-device compiles`() {
        assertEquals(8, manifest.components.size)
        assertEquals(2, manifest.components.count { it.isCompiled })
    }

    /**
     * Every fetched component pins a hash, and it is a real SHA-256.
     *
     * The two that pin nothing are the two that fetch nothing this way: the
     * Debian rootfs verifies the registry's own digest as it streams, and the
     * apt step is Debian's package signing, not ours.
     */
    @Test
    fun `every downloaded component pins a sha256 and an https url`() {
        val fetched = manifest.components.filter { it.url != null }
        assertEquals(4, fetched.size)
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
        assertTrue(links.any { it.contains("link") && it.contains("solana") })
        assertTrue(links.any { it.contains("default") && it.contains("solana") })
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
     * apt is before the two compiles, because `cargo install` needs a C
     * compiler and a linker, and the row order is also the install order.
     */
    @Test
    fun `apt lands before anything is compiled on the device`() {
        val ids = manifest.components.map { it.id }
        val apt = ids.indexOf("apt-build-tools")
        assertTrue(apt >= 0)
        for (component in manifest.components.filter { it.isCompiled }) {
            assertTrue(
                "${component.id} is compiled before apt has installed a compiler",
                apt < ids.indexOf(component.id),
            )
        }
        assertTrue(manifest.component("apt-build-tools")!!.packages.contains("build-essential"))
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
     * The compiles carry a crate and a pinned version and no size to download.
     * A `downloadBytes` on a compile row would put a MB bar on a four-minute
     * build, which the progress model forbids (docs/UI.md, "Setup").
     */
    @Test
    fun `compiled components name a crate and download nothing`() {
        val compiled = manifest.components.filter { it.isCompiled }
        for (component in compiled) {
            assertNotNull("${component.id} names no crate", component.crate)
            assertNotNull("${component.id} pins no version", component.version)
            assertEquals(0L, component.downloadBytes)
            assertTrue("${component.id} installs to nowhere", component.installBytes > 0L)
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

    /** Sizes read the way a download page writes them. */
    @Test
    fun `formats bytes the way the screen quotes them`() {
        assertEquals("505 MB", formatBytes(505_000_000L))
        assertEquals("1.4 GB", formatBytes(1_400_000_000L))
        assertEquals("30 MB", formatBytes(30_000_000L))
    }
}
