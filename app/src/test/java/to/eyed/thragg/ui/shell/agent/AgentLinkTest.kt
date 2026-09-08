package to.eyed.thragg.ui.shell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [linkTarget]: where a markdown link in a reply points. */
class AgentLinkTest {

    @Test
    fun `http and https go to the browser untouched`() {
        assertEquals(LinkTarget.Web("https://docs.rs/anchor-lang"), linkTarget("https://docs.rs/anchor-lang"))
        assertEquals(LinkTarget.Web("http://localhost:8899"), linkTarget("http://localhost:8899"))
        assertEquals(LinkTarget.Web("HTTPS://x.y"), linkTarget(" HTTPS://x.y "))
    }

    @Test
    fun `a bare path is a source target with no position`() {
        assertEquals(LinkTarget.Source("src/lib.rs", null, null), linkTarget("src/lib.rs"))
        assertEquals(LinkTarget.Source("Anchor.toml", null, null), linkTarget("Anchor.toml"))
    }

    @Test
    fun `the compiler's colon spelling carries line and column`() {
        assertEquals(LinkTarget.Source("src/lib.rs", 42, null), linkTarget("src/lib.rs:42"))
        assertEquals(LinkTarget.Source("src/lib.rs", 42, 7), linkTarget("src/lib.rs:42:7"))
    }

    @Test
    fun `a forge anchor carries the line`() {
        assertEquals(LinkTarget.Source("programs/x/src/lib.rs", 12, null), linkTarget("programs/x/src/lib.rs#L12"))
    }

    @Test
    fun `file scheme is a path, not a browser address`() {
        assertEquals(LinkTarget.Source("/data/p/src/lib.rs", 3, null), linkTarget("file:///data/p/src/lib.rs:3"))
    }

    @Test
    fun `blank is nothing to open`() {
        assertNull(linkTarget(""))
        assertNull(linkTarget("   "))
        assertNull(linkTarget(":12"))
    }
}
