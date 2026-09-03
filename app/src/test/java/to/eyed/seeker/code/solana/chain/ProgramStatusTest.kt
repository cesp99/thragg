package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings row's sentence and the close permission, pinned word for
 * word. These are the only two things a person reads before deciding to
 * close a program — the sentence says who holds it, the permission decides
 * whether the red button appears — so the four states times the three
 * possible authorities are each spelled out rather than sampled.
 *
 * Addresses are real-looking Base58 so that [Base58.short] produces the
 * four-and-four form the rows show; the wallet and deploy key are told apart
 * by value only, as they are in the app.
 */
class ProgramStatusTest {

    private val wallet = "7NJdbJ9k1yWAyQtcGCZbQq7TqMbxLnLrjXvS2jsn4kQz"
    private val deployKey = "9wQtE3PsyfvzSZtqTAgLvGq7mTb2p8H6mKXa1Vn3m2Xf"
    private val stranger = "3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de2"
    private val id = "EHc612Y1t3pD7bTkVAbsvT6Q1JNyNZ4YfP1ZsGgW5EuS"
    private val programData = "PwDiXFxQsGra4sFFTT8r1QWRMd4vfumiWC1jfWNfdYT"

    private fun deployed(authority: String?) = OnChainProgram.Deployed(
        programId = id,
        programData = programData,
        slot = 1234,
        authority = authority,
        reclaimable = 1_500_000_000L,
        dataLen = 200_000L,
    )

    // ---- describe ------------------------------------------------------------

    @Test
    fun `not found reads as not deployed on the cluster`() {
        assertEquals("not deployed on devnet", ProgramStatus.describe(OnChainProgram.NotFound, Cluster.Devnet, wallet, deployKey))
        assertEquals("not deployed on mainnet-beta", ProgramStatus.describe(OnChainProgram.NotFound, Cluster.MainnetBeta, null, null))
    }

    @Test
    fun `closed says the id is burned`() {
        assertEquals(
            "closed on testnet · id can never be reused",
            ProgramStatus.describe(OnChainProgram.Closed(id), Cluster.Testnet, wallet, deployKey),
        )
    }

    @Test
    fun `not a program names the owner short`() {
        assertEquals(
            "not a program on devnet · owned by 7NJd…4kQz",
            ProgramStatus.describe(OnChainProgram.NotAProgram(wallet), Cluster.Devnet, null, null),
        )
    }

    @Test
    fun `deployed with the wallet as authority is upgradeable by Seed Vault`() {
        assertEquals(
            "deployed on devnet · upgradeable by Seed Vault",
            ProgramStatus.describe(deployed(wallet), Cluster.Devnet, wallet, deployKey),
        )
    }

    @Test
    fun `deployed with the deploy key as authority names the deploy key`() {
        assertEquals(
            "deployed on devnet · upgradeable by the deploy key",
            ProgramStatus.describe(deployed(deployKey), Cluster.Devnet, wallet, deployKey),
        )
        // With no wallet connected the deploy key is still recognised.
        assertEquals(
            "deployed on devnet · upgradeable by the deploy key",
            ProgramStatus.describe(deployed(deployKey), Cluster.Devnet, null, deployKey),
        )
    }

    @Test
    fun `deployed with a stranger as authority shows the short address`() {
        assertEquals(
            "deployed on mainnet-beta · upgradeable by 3b6a…1de2",
            ProgramStatus.describe(deployed(stranger), Cluster.MainnetBeta, wallet, deployKey),
        )
    }

    @Test
    fun `the wallet address is not mistaken for the deploy key when neither is known`() {
        assertEquals(
            "deployed on devnet · upgradeable by 7NJd…4kQz",
            ProgramStatus.describe(deployed(wallet), Cluster.Devnet, null, null),
        )
    }

    @Test
    fun `deployed without an authority is immutable`() {
        assertEquals(
            "deployed on devnet · immutable",
            ProgramStatus.describe(deployed(null), Cluster.Devnet, wallet, deployKey),
        )
    }

    // ---- canClose ------------------------------------------------------------

    @Test
    fun `only a deployed program with our authority can be closed`() {
        assertTrue(ProgramStatus.canClose(deployed(wallet), wallet, deployKey))
        assertTrue(ProgramStatus.canClose(deployed(deployKey), wallet, deployKey))
        assertTrue(ProgramStatus.canClose(deployed(deployKey), null, deployKey))
        assertTrue(ProgramStatus.canClose(deployed(wallet), wallet, null))
    }

    @Test
    fun `a stranger's authority cannot be closed from here`() {
        assertFalse(ProgramStatus.canClose(deployed(stranger), wallet, deployKey))
    }

    @Test
    fun `an immutable program cannot be closed`() {
        assertFalse(ProgramStatus.canClose(deployed(null), wallet, deployKey))
    }

    @Test
    fun `the wallet's program is not closable once the wallet is disconnected`() {
        assertFalse(ProgramStatus.canClose(deployed(wallet), null, deployKey))
    }

    @Test
    fun `the other states are never closable`() {
        assertFalse(ProgramStatus.canClose(OnChainProgram.NotFound, wallet, deployKey))
        assertFalse(ProgramStatus.canClose(OnChainProgram.Closed(id), wallet, deployKey))
        assertFalse(ProgramStatus.canClose(OnChainProgram.NotAProgram(stranger), wallet, deployKey))
    }
}
