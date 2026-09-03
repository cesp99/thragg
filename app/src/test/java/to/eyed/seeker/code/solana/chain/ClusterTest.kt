package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four spellings of a cluster, pinned. Each one is read by a different
 * party — our prefs, Anchor, the public RPC, the explorer — and a typo in any
 * of them is a deploy to the wrong place or a link to nowhere.
 */
class ClusterTest {

    @Test
    fun `ids are the persisted spelling and never collide`() {
        assertEquals("devnet", Cluster.Devnet.id)
        assertEquals("testnet", Cluster.Testnet.id)
        assertEquals("mainnet-beta", Cluster.MainnetBeta.id)
        assertEquals(3, Cluster.entries.map { it.id }.distinct().size)
    }

    @Test
    fun `anchor names are what Anchor accepts, which is not mainnet-beta`() {
        assertEquals("devnet", Cluster.Devnet.anchorName)
        assertEquals("testnet", Cluster.Testnet.anchorName)
        assertEquals("mainnet", Cluster.MainnetBeta.anchorName)
    }

    @Test
    fun `rpc endpoints are the public hosts`() {
        assertEquals("https://api.devnet.solana.com", Cluster.Devnet.rpcUrl)
        assertEquals("https://api.testnet.solana.com", Cluster.Testnet.rpcUrl)
        assertEquals("https://api.mainnet-beta.solana.com", Cluster.MainnetBeta.rpcUrl)
    }

    @Test
    fun `only mainnet is mainnet, and only the others have a faucet`() {
        assertTrue(Cluster.MainnetBeta.isMainnet)
        assertFalse(Cluster.Devnet.isMainnet)
        assertFalse(Cluster.Testnet.isMainnet)
        assertTrue(Cluster.Devnet.hasFaucet)
        assertTrue(Cluster.Testnet.hasFaucet)
        assertFalse(Cluster.MainnetBeta.hasFaucet)
    }

    @Test
    fun `explorer links name the cluster except on mainnet`() {
        val id = "BPFLoaderUpgradeab1e11111111111111111111111"
        assertEquals(
            "https://explorer.solana.com/address/$id?cluster=devnet",
            Cluster.Devnet.explorerAddress(id),
        )
        assertEquals(
            "https://explorer.solana.com/address/$id?cluster=testnet",
            Cluster.Testnet.explorerAddress(id),
        )
        assertEquals("https://explorer.solana.com/address/$id", Cluster.MainnetBeta.explorerAddress(id))
        assertEquals("https://explorer.solana.com/tx/sig?cluster=devnet", Cluster.Devnet.explorerTx("sig"))
        assertEquals("https://explorer.solana.com/tx/sig", Cluster.MainnetBeta.explorerTx("sig"))
    }

    @Test
    fun `fromId round trips every cluster and forgives case`() {
        for (cluster in Cluster.entries) {
            assertEquals(cluster, Cluster.fromId(cluster.id))
            assertEquals(cluster, Cluster.fromId(cluster.id.uppercase()))
            assertEquals(cluster, Cluster.fromId(" ${cluster.id} "))
        }
        assertNull(Cluster.fromId(null))
        assertNull(Cluster.fromId(""))
        assertNull(Cluster.fromId("localnet"))
    }

    @Test
    fun `fromAnchor is case-insensitive and leaves localnet to the caller`() {
        assertEquals(Cluster.Devnet, Cluster.fromAnchor("devnet"))
        assertEquals(Cluster.Devnet, Cluster.fromAnchor("Devnet"))
        assertEquals(Cluster.Testnet, Cluster.fromAnchor("TESTNET"))
        assertEquals(Cluster.MainnetBeta, Cluster.fromAnchor("mainnet"))
        assertEquals(Cluster.MainnetBeta, Cluster.fromAnchor("Mainnet"))
        assertNull(Cluster.fromAnchor("localnet"))
        assertNull(Cluster.fromAnchor("Localnet"))
        assertNull(Cluster.fromAnchor(null))
        assertNull(Cluster.fromAnchor("  "))
        assertNull(Cluster.fromAnchor("http://localhost:8899"))
    }

    @Test
    fun `fromAnchor also reads the explorer spelling a hand edit might leave`() {
        assertEquals(Cluster.MainnetBeta, Cluster.fromAnchor("mainnet-beta"))
    }

    @Test
    fun `the default is devnet`() {
        assertEquals(Cluster.Devnet, Cluster.DEFAULT)
    }
}
