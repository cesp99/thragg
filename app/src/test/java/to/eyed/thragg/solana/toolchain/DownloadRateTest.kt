package to.eyed.thragg.solana.toolchain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A row may only claim a speed it has actually measured — P-04.
 *
 * Both download loops reported on an interval, but seeded `lastReport` with
 * zero, so the *first* buffer read always satisfied the interval test: one
 * 64 KB chunk over the millisecond or two since the loop opened its stream is
 * "19 MB/s" on a phone whose real throughput was a twentieth of that. It was
 * most visible after a Retry, printed beside a row that was resuming from a
 * local `.part` (s1, `/tmp/qa/s1-setup/10-retry-tapped.png`).
 *
 * The floor lives in the accessor rather than at the two call sites, so a
 * third download loop cannot reintroduce it.
 */
class DownloadRateTest {

    @Test
    fun `a full window is a rate`() {
        // 1 MB over a second.
        assertEquals(1_000_000L, ToolchainInstaller.downloadRate(1_000_000L, 1_000L))
    }

    @Test
    fun `a window shorter than the report interval is not a measurement`() {
        assertNull(ToolchainInstaller.downloadRate(64 * 1024L, 3L))
    }

    @Test
    fun `a zero window is never a division`() {
        assertNull(ToolchainInstaller.downloadRate(64 * 1024L, 0L))
    }

    @Test
    fun `no bytes in the window is no rate, not zero`() {
        assertNull(ToolchainInstaller.downloadRate(0L, 5_000L))
    }

    // ---- P-03: the fetch lane's order --------------------------------------

    /**
     * Smallest first, which is what docs/SOLANA.md specifies and what the
     * two-lane measurement was made with. Fetching the 528 MB of
     * platform-tools first put rustup (19 MB) — a row the guest lane installs
     * in under a second, and one four other components need — behind it, and
     * the guest lane finished apt and then sat idle for about three and a half
     * minutes (s1).
     */
    @Test
    fun `the fetch lane pulls the small rows before the gigabyte`() {
        val components = manifest().components
        val order = ToolchainInstaller.fetchOrder(components).map { it.id }
        assertTrue("nothing to fetch in the shipped manifest", order.size >= 5)
        assertEquals("platform-tools", order.last())
        assertTrue(order.indexOf("rustup") < order.indexOf("platform-tools"))
        assertTrue(order.indexOf("cargo-build-sbf") < order.indexOf("platform-tools"))
        assertTrue(order.indexOf("anchor") < order.indexOf("platform-tools"))
    }

    /** A component with no URL has nothing to fetch and is not in the lane. */
    @Test
    fun `only the rows that download are in the fetch lane`() {
        val components = manifest().components
        val order = ToolchainInstaller.fetchOrder(components)
        assertTrue(order.all { it.url != null })
        assertEquals(components.count { it.url != null }, order.size)
    }

    private fun manifest(): ToolchainManifest {
        val relative = "src/main/assets/${ToolchainManifest.ASSET_PATH}"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return ToolchainManifest.parse(file.readText())
    }
}
