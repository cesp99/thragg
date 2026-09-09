package to.eyed.thragg.ui.shell.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.solana.chain.Cluster

/**
 * The top-up picker's one sentence, held still. Off mainnet it must say the
 * SOL can go back, because a developer who has just been asked to move money
 * out of their own wallet into an address the app invented needs to read that
 * before they tap; on mainnet it must say "real SOL" in those words, the same
 * promise the Deploy sheet's mainnet confirm makes.
 */
class TopUpSheetTest {

    @Test
    fun `the blurb says what the key is for and that the SOL comes back`() {
        val devnet = topUpBlurb(Cluster.Devnet)
        assertTrue(devnet, "deploy key" in devnet && "buffer writes" in devnet)
        assertTrue(devnet, "sent back" in devnet)
        assertFalse(devnet, "real SOL" in devnet)
    }

    @Test
    fun `mainnet's blurb says it is real money`() {
        val mainnet = topUpBlurb(Cluster.MainnetBeta)
        assertTrue(mainnet, "real SOL" in mainnet)
        assertTrue(mainnet, "sent back" in mainnet)
    }

    @Test
    fun `a second top-up is told why nothing happened`() {
        assertTrue(TOP_UP_RUNNING, "already running" in TOP_UP_RUNNING)
    }
}
