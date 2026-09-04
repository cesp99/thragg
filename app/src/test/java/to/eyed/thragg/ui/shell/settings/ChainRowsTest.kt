package to.eyed.thragg.ui.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.solana.chain.Cluster

/**
 * The sentences the Solana rows and sheets print, held still.
 *
 * Every chooser here is pure exactly so this test exists: the Cluster row
 * must print `localnet · set in Anchor.toml` rather than the devnet fallback
 * it would otherwise show, the Program row must say it is still asking
 * before it says anything else, and the close confirm's body — the one
 * dialog in the app about a thing that cannot be undone — must keep its
 * warning word for word.
 */
class ChainRowsTest {

    private val wallet = "7NJdQ4eGxKQK3cL7Uk1zQ9PjxB1rQ5h9fdxyC6hZ4kQz"
    private val deployKey = "9wQtVbT7kLmN2pR8sU4vW6xY1zA3bC5dE7fG9hJ2kLm2"
    private val other = "AbCdEfGhJkLmNpQrStUvWxYz123456789ABCDEFGh"

    @Test
    fun `cluster row prints the cluster, and the file's own word when it is not one`() {
        assertEquals("devnet", clusterRowDetail("devnet", anchorSays = null, hasProject = true))
        assertEquals("devnet", clusterRowDetail("devnet", anchorSays = "devnet", hasProject = true))
        // Anchor's own spelling of mainnet is a cluster, not a stranger.
        assertEquals("mainnet-beta", clusterRowDetail("mainnet-beta", anchorSays = "Mainnet", hasProject = true))
        assertEquals(
            "localnet · set in Anchor.toml",
            clusterRowDetail("devnet", anchorSays = "localnet", hasProject = true),
        )
        // With no project there is no file to disagree with.
        assertEquals("devnet", clusterRowDetail("devnet", anchorSays = "localnet", hasProject = false))
        // An empty row in the file is no row.
        assertEquals("devnet", clusterRowDetail("devnet", anchorSays = "  ", hasProject = true))
    }

    @Test
    fun `wallet row names the account or the absence`() {
        assertEquals("not connected", walletRowDetail(null))
        assertEquals("Seed Vault · 7NJd…4kQz", walletRowDetail(wallet))
    }

    @Test
    fun `program row says it is asking before it says anything else`() {
        assertEquals(
            "checking on devnet…",
            programRowDescription(checking = true, unreachable = false, described = "not deployed on devnet", cluster = "devnet"),
        )
        assertEquals(
            "could not reach testnet",
            programRowDescription(checking = false, unreachable = true, described = null, cluster = "testnet"),
        )
        assertEquals(
            "deployed on devnet · upgradeable by Seed Vault",
            programRowDescription(
                checking = false,
                unreachable = false,
                described = "deployed on devnet · upgradeable by Seed Vault",
                cluster = "devnet",
            ),
        )
        assertNull(programRowDescription(checking = false, unreachable = false, described = null, cluster = "devnet"))
        // Anchor.toml on localnet: nothing was asked, whatever else is true.
        assertEquals(
            "localnet · pick a cluster",
            programRowDescription(checking = true, unreachable = false, described = null, cluster = "devnet", localnet = true),
        )
    }

    @Test
    fun `localnet is a file naming a cluster the picker does not offer`() {
        assertTrue(anchorTomlNamesLocalnet("localnet", hasProject = true))
        assertTrue(anchorTomlNamesLocalnet(" Localnet ", hasProject = true))
        assertFalse(anchorTomlNamesLocalnet("devnet", hasProject = true))
        assertFalse(anchorTomlNamesLocalnet("Mainnet", hasProject = true))
        assertFalse(anchorTomlNamesLocalnet("localnet", hasProject = false))
        assertFalse(anchorTomlNamesLocalnet(null, hasProject = true))
        assertFalse(anchorTomlNamesLocalnet("  ", hasProject = true))
    }

    @Test
    fun `every cluster has its sentence and mainnet's says money`() {
        for (cluster in Cluster.entries) {
            assertTrue(cluster.name, clusterDescription(cluster).isNotBlank())
        }
        assertTrue(clusterDescription(Cluster.MainnetBeta).contains("Real SOL"))
        assertTrue(clusterDescription(Cluster.MainnetBeta).contains("cannot be undone"))
        assertTrue(clusterDescription(Cluster.Devnet).contains("faucet"))
    }

    @Test
    fun `authority is named by its role`() {
        assertEquals("7NJd…4kQz · this wallet", authorityDetail(wallet, wallet, deployKey, whenNull = "none"))
        assertEquals("9wQt…kLm2 · deploy key", authorityDetail(deployKey, wallet, deployKey, whenNull = "none"))
        assertEquals("AbCd…EFGh · someone else", authorityDetail(other, wallet, deployKey, whenNull = "none"))
        // The wallet's address with no wallet connected is not "this wallet".
        assertEquals("7NJd…4kQz · someone else", authorityDetail(wallet, null, deployKey, whenNull = "none"))
        assertEquals("none · immutable", authorityDetail(null, wallet, deployKey, whenNull = "none · immutable"))
    }

    @Test
    fun `a close pays the wallet first, then the deploy key`() {
        assertEquals("Seed Vault 7NJd…4kQz", closeRecipient(wallet, deployKey))
        assertEquals("the deploy key 9wQt…kLm2", closeRecipient(null, deployKey))
        assertEquals("this phone", closeRecipient(null, null))
    }

    @Test
    fun `the close body keeps its warning, and mainnet adds a sentence`() {
        val devnet = closeProgramBody(
            programId = other,
            reclaimed = "1.2345 SOL",
            recipient = "Seed Vault 7NJd…4kQz",
            mainnet = false,
        )
        assertEquals(
            "Program $other will be closed and 1.2345 SOL returned to Seed Vault 7NJd…4kQz. " +
                "This cannot be undone. The program id can never be deployed again.",
            devnet,
        )
        val mainnet = closeProgramBody(other, "1.2345 SOL", "Seed Vault 7NJd…4kQz", mainnet = true)
        assertTrue(mainnet.contains("This is mainnet-beta. "))
        assertTrue(mainnet.endsWith("The program id can never be deployed again."))
    }

    @Test
    fun `balance line is the number, the failure, or the wait`() {
        assertEquals("2.41 SOL on devnet", balanceDetail(2_410_000_000L, failed = false, cluster = "devnet"))
        assertEquals("0 SOL on testnet", balanceDetail(0L, failed = false, cluster = "testnet"))
        assertEquals("could not reach devnet", balanceDetail(null, failed = true, cluster = "devnet"))
    }

    @Test
    fun `the airdrop button mines on devnet and asks the faucet on testnet`() {
        assertEquals("Mine 5 SOL", airdropLabel(Cluster.Devnet))
        assertEquals("Airdrop 1 SOL", airdropLabel(Cluster.Testnet))
        assertEquals("…", balanceDetail(null, failed = false, cluster = "devnet"))
    }

    @Test
    fun `an open buffer says what it was for`() {
        assertEquals("devnet · for AbCd…EFGh", bufferDetail("devnet", other))
        assertEquals("devnet · left by an unfinished deploy", bufferDetail("devnet", null))
    }
}
