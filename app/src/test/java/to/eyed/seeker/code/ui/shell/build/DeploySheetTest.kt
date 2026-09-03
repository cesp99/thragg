package to.eyed.seeker.code.ui.shell.build

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.seeker.code.solana.build.ArtifactFreshness

/**
 * The Deploy sheet's rows, held still. Each is a pure function of the facts
 * the sheet fetched, so the wording a person reads before spending SOL can be
 * pinned here rather than on a device: the artifact row must say `stale` and
 * `missing` in those words (docs/UI.md, "Build"), the signer row must not
 * claim a wallet that is not connected, and the cost must carry its tilde.
 */
class DeploySheetTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `artifact row is name, size and age when fresh`() {
        assertEquals(
            "my_program.so · 214 kB · built 30 s ago",
            artifactDetail("my_program.so", 214_000L, ArtifactFreshness.Fresh(now - 30_000L), now),
        )
    }

    @Test
    fun `artifact row says stale and missing in those words`() {
        assertEquals(
            "my_program.so · 214 kB · stale — edited since the last build",
            artifactDetail("my_program.so", 214_000L, ArtifactFreshness.Stale(now - 90_000L), now),
        )
        assertEquals("my_program.so · missing", artifactDetail("my_program.so", null, ArtifactFreshness.Missing, now))
        // A size that has not been read yet is left out rather than printed as 0 B.
        assertEquals(
            "my_program.so · built 5 min ago",
            artifactDetail("my_program.so", null, ArtifactFreshness.Fresh(now - 300_000L), now),
        )
    }

    @Test
    fun `built ago coarsens with age and never goes negative`() {
        assertEquals("built 0 s ago", builtAgo(now + 5_000L, now))
        assertEquals("built 59 s ago", builtAgo(now - 59_000L, now))
        assertEquals("built 1 min ago", builtAgo(now - 60_000L, now))
        assertEquals("built 2 h ago", builtAgo(now - 7_200_000L, now))
        assertEquals("built 3 d ago", builtAgo(now - 3L * 86_400_000L, now))
    }

    @Test
    fun `signer row names the wallet or admits there is none`() {
        assertEquals("Seed Vault · 7NJd…4kQz", signerDetail("7NJdQ4eGxKQK3cL7Uk1zQ9PjxB1rQ5h9fdxyC6hZ4kQz"))
        assertEquals("Deploy key (no wallet connected)", signerDetail(null))
    }

    @Test
    fun `cost carries its tilde and the buffer line says what comes back`() {
        assertEquals("~1.49 SOL", costDetail(1_490_000_000L))
        assertEquals("not known yet", costDetail(null))
        assertEquals("of which 0.35 SOL comes back after deploy", comesBackDetail(350_000_000L))
    }

    @Test
    fun `deploy key balance line follows the facts as they arrive`() {
        assertEquals("…", keyBalanceDetail(address = null, loaded = false, balance = null, failed = false, cluster = "devnet"))
        assertEquals(
            "no deploy key yet · the first deploy creates one",
            keyBalanceDetail(address = null, loaded = true, balance = null, failed = false, cluster = "devnet"),
        )
        assertEquals(
            "could not reach devnet",
            keyBalanceDetail(address = "9wQt", loaded = true, balance = null, failed = true, cluster = "devnet"),
        )
        assertEquals(
            "0.31 SOL on devnet",
            keyBalanceDetail(address = "9wQt", loaded = true, balance = 310_000_000L, failed = false, cluster = "devnet"),
        )
    }
}

class DeployedCardTest {
    @org.junit.Test
    fun `deployed ago reads like a person`() {
        val now = 1_000_000_000_000L
        org.junit.Assert.assertEquals("just now", deployedAgo(now - 30_000, now))
        org.junit.Assert.assertEquals("3 min ago", deployedAgo(now - 3 * 60_000, now))
        org.junit.Assert.assertEquals("2 h ago", deployedAgo(now - 2 * 3_600_000, now))
        org.junit.Assert.assertEquals("yesterday", deployedAgo(now - 30 * 3_600_000, now))
        org.junit.Assert.assertEquals("just now", deployedAgo(now + 5_000, now))
    }
}
