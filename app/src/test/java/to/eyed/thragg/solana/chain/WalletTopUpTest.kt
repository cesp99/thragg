package to.eyed.thragg.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the top-up decides before Seed Vault is started.
 *
 * The whole point of putting the arithmetic and the refusal matrix in pure
 * functions is that they can be pinned here rather than on a phone holding
 * real money: a parse that turns `1e9` into a billion SOL, or a refusal that
 * lets a transfer through when the wallet cannot cover the fee, is a mistake
 * that only shows up after the wallet app has already been answered.
 */
class WalletTopUpTest {

    private val wallet = "7NJdQ4eGxKQK3cL7Uk1zQ9PjxB1rQ5h9fdxyC6hZ4kQz"
    private val key = "BPM9XtpwnBHxCmZ1ujghe98Uf7ZuPaPiJAfCa82GUKwJ"
    private val sol = 1_000_000_000L

    // ---- the amount ---------------------------------------------------------

    @Test
    fun `an amount in SOL becomes lamports, with or without its unit`() {
        assertEquals(sol, WalletTopUp.parseAmount("1"))
        assertEquals(sol, WalletTopUp.parseAmount(" 1 SOL "))
        assertEquals(500_000_000L, WalletTopUp.parseAmount("0.5"))
        assertEquals(500_000_000L, WalletTopUp.parseAmount(".5"))
        assertEquals(1_500_000_000L, WalletTopUp.parseAmount("1.5"))
        // A decimal-comma keyboard emits this and it means the same thing.
        assertEquals(1_500_000_000L, WalletTopUp.parseAmount("1,5"))
        assertEquals(1L, WalletTopUp.parseAmount("0.000000001"))
        assertEquals(0L, WalletTopUp.parseAmount("0"))
    }

    @Test
    fun `anything that is not a plain decimal is refused rather than guessed at`() {
        assertNull(WalletTopUp.parseAmount(""))
        assertNull(WalletTopUp.parseAmount("   "))
        assertNull(WalletTopUp.parseAmount("."))
        assertNull(WalletTopUp.parseAmount("-1"))
        assertNull(WalletTopUp.parseAmount("all of it"))
        assertNull(WalletTopUp.parseAmount("1.2.3"))
        assertNull(WalletTopUp.parseAmount("0x10"))
        // The one that matters: scientific notation on a numeric keyboard is
        // a typo, and BigDecimal would happily read it as a billion SOL.
        assertNull(WalletTopUp.parseAmount("1e9"))
        // Finer than a lamport, and larger than anyone means.
        assertNull(WalletTopUp.parseAmount("0.0000000001"))
        assertNull(WalletTopUp.parseAmount("2000000"))
    }

    @Test
    fun `an amount round-trips through the field`() {
        for (preset in WalletTopUp.PRESETS) {
            assertEquals(preset, WalletTopUp.parseAmount(WalletTopUp.solText(preset)))
        }
        assertEquals("0.5", WalletTopUp.solText(500_000_000L))
        assertEquals("1", WalletTopUp.solText(sol))
        assertEquals("0.0001", WalletTopUp.solText(100_000L))
    }

    // ---- "enough for this deploy" -------------------------------------------

    @Test
    fun `enough for this deploy rounds the gap up and adds a margin`() {
        // 1.23 SOL short → 1.25 wanted → the next tenth.
        assertEquals(1_300_000_000L, WalletTopUp.enoughForDeploy(1_230_000_000L))
        // Exactly on a step still gets the margin, so the chip is never short.
        assertEquals(1_100_000_000L, WalletTopUp.enoughForDeploy(sol))
        // A tiny gap is a tenth, not four ten-thousandths.
        assertEquals(WalletTopUp.TOP_UP_STEP, WalletTopUp.enoughForDeploy(5_000L))
        assertEquals(WalletTopUp.TOP_UP_STEP, WalletTopUp.enoughForDeploy(0L))
        assertEquals(WalletTopUp.TOP_UP_STEP, WalletTopUp.enoughForDeploy(-1L))
    }

    @Test
    fun `enough for this deploy always covers the gap it was given`() {
        for (gap in listOf(1L, 5_000L, 79_999_999L, 1_230_000_000L, 4_400_000_000L)) {
            assertTrue(gap.toString(), WalletTopUp.enoughForDeploy(gap) >= gap)
        }
    }

    // ---- the refusal matrix -------------------------------------------------

    private fun refuse(
        amount: Long? = sol,
        walletAddress: String? = wallet,
        walletCluster: Cluster? = Cluster.Devnet,
        walletBalance: Long? = 10 * sol,
        deployKey: String? = key,
        cluster: Cluster = Cluster.Devnet,
    ) = WalletTopUp.refusal(amount, walletAddress, walletCluster, walletBalance, deployKey, cluster)

    @Test
    fun `a wallet with the money and a key to send it to is not refused`() {
        assertNull(refuse())
        // The wallet's cluster is simply unknown until it has been connected
        // once on this install; that is not a reason to refuse.
        assertNull(refuse(walletCluster = null))
        // Exactly the amount plus the fee is enough.
        assertNull(refuse(amount = sol, walletBalance = sol + WalletTopUp.FEE_RESERVE))
    }

    @Test
    fun `no deploy key comes before everything else`() {
        assertEquals(WalletTopUp.NO_KEY, refuse(deployKey = null))
        // Even when nothing else is right either.
        assertEquals(WalletTopUp.NO_KEY, refuse(deployKey = null, walletAddress = null, amount = null))
    }

    @Test
    fun `no wallet is the connect refusal`() {
        assertEquals(WalletTopUp.NOT_CONNECTED, refuse(walletAddress = null))
        assertEquals(WalletTopUp.NOT_CONNECTED, refuse(walletAddress = null, walletBalance = null))
    }

    @Test
    fun `a wallet connected somewhere else names both clusters`() {
        val said = refuse(walletCluster = Cluster.Devnet, cluster = Cluster.MainnetBeta)!!
        assertTrue(said, "devnet" in said && "mainnet-beta" in said)
        assertTrue(said, "reconnect" in said)
    }

    @Test
    fun `zero, negative and unreadable amounts each say what to do`() {
        assertEquals("Enter an amount in SOL", refuse(amount = null))
        assertEquals("The amount must be more than zero", refuse(amount = 0L))
        assertEquals("The amount must be more than zero", refuse(amount = -sol))
    }

    @Test
    fun `a balance still being read is a wait, not a refusal to spend`() {
        val said = refuse(walletBalance = null)!!
        assertTrue(said, said.startsWith("Asking devnet"))
    }

    @Test
    fun `a wallet that cannot cover the amount plus the fee says both numbers`() {
        // One lamport short of the fee is short.
        val said = refuse(amount = sol, walletBalance = sol + WalletTopUp.FEE_RESERVE - 1)!!
        assertTrue(said, "1 SOL" in said && "devnet" in said)
        assertTrue(said, "fee" in said)
    }

    @Test
    fun `an empty wallet is refused at the door, before an amount is picked`() {
        val said = WalletTopUp.entryRefusal(wallet, Cluster.Devnet, 0L, key, Cluster.Devnet)!!
        assertTrue(said, "nothing to send" in said)
        // The door is open as soon as there is more than a fee in there.
        assertNull(WalletTopUp.entryRefusal(wallet, Cluster.Devnet, sol, key, Cluster.Devnet))
        // And it does not ask about an amount, because the amount is chosen
        // on the other side of it.
        assertNull(WalletTopUp.entryRefusal(wallet, null, null, key, Cluster.Devnet))
    }

    // ---- what the sheets say ------------------------------------------------

    @Test
    fun `only mainnet asks a second time`() {
        assertTrue(WalletTopUp.needsConfirm(Cluster.MainnetBeta))
        assertFalse(WalletTopUp.needsConfirm(Cluster.Devnet))
        assertFalse(WalletTopUp.needsConfirm(Cluster.Testnet))
        val body = WalletTopUp.confirmBody(2 * sol, key)
        assertTrue(body, "2 SOL" in body && "real money" in body)
        assertTrue(body, Base58.short(key) in body)
    }

    @Test
    fun `the deploy key row shows where the balance is going`() {
        assertEquals("…", WalletTopUp.afterDetail(null, sol))
        assertEquals("0.5 SOL", WalletTopUp.afterDetail(500_000_000L, null))
        assertEquals("0.5 SOL", WalletTopUp.afterDetail(500_000_000L, 0L))
        assertEquals("0.5 SOL now, 1.5 SOL after", WalletTopUp.afterDetail(500_000_000L, sol))
    }

    @Test
    fun `landing says how much and what the key holds now`() {
        assertEquals(
            "Seed Vault sent 1 SOL — the deploy key holds 1.5 SOL on devnet",
            WalletTopUp.landedDetail(sol, 1_500_000_000L, Cluster.Devnet),
        )
        // A balance read that failed after the transfer confirmed must not
        // turn a success into a sentence with a hole in it.
        assertEquals(
            "Seed Vault sent 1 SOL to the deploy key on devnet",
            WalletTopUp.landedDetail(sol, null, Cluster.Devnet),
        )
    }

    @Test
    fun `a network mismatch names both networks and where the setting is`() {
        // Observed on the phone 2026-09-09: the wallet app was on mainnet,
        // Thragg on devnet, and the wallet closed the association saying only
        // "Network mismatch".
        val said = WalletTopUp.networkMismatch(Cluster.Devnet, Cluster.MainnetBeta)
        assertTrue(said, "devnet" in said && "mainnet-beta" in said)
        assertTrue(said, "Seed Vault Wallet" in said && "Network" in said && "Settings" in said)
        // With nothing remembered it still says which network to choose.
        val bare = WalletTopUp.networkMismatch(Cluster.Devnet, null)
        assertTrue(bare, "devnet" in bare && "last authorized" !in bare)
        // And it does not invent a second network when they agree.
        val same = WalletTopUp.networkMismatch(Cluster.Devnet, Cluster.Devnet)
        assertTrue(same, "last authorized" !in same)
    }

    @Test
    fun `the asking line names both addresses and tells the user to stay put`() {
        val said = WalletTopUp.askingDetail(wallet, key, sol)
        assertTrue(said, Base58.short(wallet) in said && Base58.short(key) in said)
        assertTrue(said, "1 SOL" in said && "keep Thragg on screen" in said)
    }
}
