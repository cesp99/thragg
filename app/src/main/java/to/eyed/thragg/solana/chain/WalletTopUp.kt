package to.eyed.thragg.solana.chain

import android.content.Context
import java.math.BigDecimal
import java.math.RoundingMode

/** What a finished top-up moved, and what the deploy key held straight after. */
class TopUpReceipt(
    val signature: String,
    val amount: Long,
    /** The deploy key's balance after the transfer confirmed; null when that read failed. */
    val keyBalance: Long?,
)

/**
 * One transfer, the other way: Seed Vault → deploy key.
 *
 * THE PROBLEM THIS EXISTS FOR. A developer's SOL lives in their Seed Vault
 * wallet, and the deploy key (ChainRecords.kt, `files/chain/deploy-key.json`)
 * is a second, plain keypair that pays for two hundred buffer writes so the
 * wallet is asked once instead of two hundred times. Until this file the only
 * way to put SOL into that second key on devnet was to mine the proof-of-work
 * faucet (PowFaucet.kt), and a faucet whose source PDA is empty cannot be
 * mined — measured on the phone 2026-09-09, with 16541 devnet SOL sitting in
 * the wallet two centimetres away. So: the wallet funds the key, by hand,
 * for an amount the user picks.
 *
 * It is [WalletSheet.returnToWallet]'s mirror image and is built the same way,
 * with the two differences the direction forces:
 *
 *  * **The wallet signs, so the wallet pays.** Fee payer and source are both
 *    the connected pubkey; the deploy key is only a destination and never
 *    signs. That is why [send] goes through [ChainSigning.signAndSend] with
 *    `local = emptyList()` — one Seed Vault prompt, our RPC, our confirmation
 *    loop. Never `signAndSendTransactions`: the wallet would send through its
 *    own node on its own cluster (SeedVaultWallet.kt:62).
 *  * **The balance is re-read, never taken from a row.** A row is however old
 *    the last Refresh is, and the refusal that matters — "the wallet cannot
 *    cover this" — has to be true at the moment the wallet app opens, not at
 *    the moment the sheet did.
 *
 * Everything a sheet needs to decide *before* starting the wallet app is a
 * pure function here — [parseAmount], [enoughForDeploy], [refusal],
 * [afterDetail], [landedDetail] — so the arithmetic and the whole refusal
 * matrix are pinned on the JVM (WalletTopUpTest) rather than on a device with
 * real money in it.
 */
object WalletTopUp {

    /** Wallet notifications replace each other rather than stacking; the same key the Wallet sheet uses. */
    const val NOTIFICATION_KEY = "chain.wallet"

    /** A SOL. */
    const val LAMPORTS_PER_SOL = 1_000_000_000L

    /**
     * What the wallet must hold on top of the amount: one signature, doubled,
     * because the Seeker's wallet puts its own compute-budget instructions in
     * front of ours (WalletAnswers.kt) and a transaction that cannot pay its
     * fee is refused after the user has already tapped through the prompt.
     */
    const val FEE_RESERVE = Loader.LAMPORTS_PER_SIGNATURE * 2

    /** The picker's fixed chips, in lamports: 0.5, 1, 2, 5 SOL. */
    val PRESETS: List<Long> = listOf(500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L)

    /** The step "Enough for this deploy" rounds up to: a tenth of a SOL. */
    const val TOP_UP_STEP = 100_000_000L

    /**
     * What is added to a shortfall before rounding. A deploy's own estimate
     * already carries a tenth of slack (ProgramDeploy.fund), and this is on
     * top of it for the fees the transfer itself and the deploy's last few
     * signatures cost — small enough that the chip does not read as a
     * different number from the one the Deploy sheet just showed.
     */
    const val DEPLOY_MARGIN = 20_000_000L

    /** The most the field will take, so no arithmetic here can overflow a Long. */
    const val MAX_AMOUNT = 1_000_000L * LAMPORTS_PER_SOL

    // ---- the pure half ------------------------------------------------------

    /**
     * `1.5`, `.5`, `1,5`, `2 SOL` → lamports; anything else → null.
     *
     * Deliberately not `toBigDecimalOrNull` alone: that accepts `1e9`, which
     * on a numeric keyboard is a typo and here would be a billion SOL. The
     * comma is accepted because a phone keyboard in a decimal-comma locale
     * emits one and refusing it would look like the field is broken.
     */
    fun parseAmount(text: String): Long? {
        val cleaned = text.trim().removeSuffix("SOL").trim().replace(',', '.')
        if (cleaned.isEmpty() || cleaned == ".") return null
        if (!AMOUNT.matches(cleaned)) return null
        val value = cleaned.toBigDecimalOrNull() ?: return null
        // The regex caps the fraction at nine places, so DOWN never actually
        // discards anything; it is here so a future looser regex cannot make
        // this round a lamport into existence.
        val lamports = value.movePointRight(9).setScale(0, RoundingMode.DOWN).toLong()
        return if (lamports > MAX_AMOUNT) null else lamports
    }

    /** `0.5`, for putting an amount back into the field. No unit: the field's placeholder carries it. */
    fun solText(lamports: Long): String =
        BigDecimal.valueOf(lamports, 9).stripTrailingZeros().toPlainString()

    /**
     * The first chip's amount when the Deploy sheet said how short the key
     * is: the gap plus [DEPLOY_MARGIN], rounded up to the next [TOP_UP_STEP],
     * and never less than one step — a chip offering 0.0004 SOL is a chip
     * nobody taps.
     */
    fun enoughForDeploy(shortfall: Long): Long {
        val wanted = shortfall.coerceAtLeast(0L) + DEPLOY_MARGIN
        val steps = (wanted + TOP_UP_STEP - 1) / TOP_UP_STEP
        return (steps * TOP_UP_STEP).coerceAtLeast(TOP_UP_STEP)
    }

    /**
     * Why the top-up cannot start, or null when it can — the whole matrix in
     * one place so the Wallet sheet's button and the picker's button refuse
     * for the same reasons in the same words.
     *
     * The order is the order a person would find them: a key that does not
     * exist, then a wallet that is not connected, then a wallet connected
     * somewhere else, then the amount, then the money. [amount] is null when
     * the field does not parse; [walletBalance] is null while the read is
     * still out; [walletCluster] is null when the wallet's cluster is not
     * known, which is not a refusal — the wallet will simply be asked to
     * authorize for [cluster].
     */
    fun refusal(
        amount: Long?,
        walletAddress: String?,
        walletCluster: Cluster?,
        walletBalance: Long?,
        deployKey: String?,
        cluster: Cluster,
    ): String? = entryRefusal(walletAddress, walletCluster, walletBalance, deployKey, cluster) ?: when {
        amount == null -> "Enter an amount in SOL"
        amount <= 0L -> "The amount must be more than zero"
        walletBalance == null -> "Asking ${cluster.display} what Seed Vault holds…"
        walletBalance < amount + FEE_RESERVE -> shortWalletDetail(walletBalance, amount, cluster)
        else -> null
    }

    /**
     * The one sentence for "the wallet cannot cover this", said the same by
     * the sheet before the wallet app opens and by [transfer] after it has
     * re-read the balance.
     */
    fun shortWalletDetail(walletBalance: Long, amount: Long, cluster: Cluster): String =
        "Seed Vault holds ${Loader.lamportsToSol(walletBalance)} on ${cluster.display} — " +
            "${Loader.lamportsToSol(amount)} plus the ${Loader.lamportsToSol(FEE_RESERVE)} fee is more than that"

    /**
     * Why the door to the picker is shut, or null when it can be opened —
     * everything [refusal] asks that is not about the amount, because the
     * amount is chosen *inside* the picker. This is what the Wallet sheet's
     * button is enabled by and what it prints under itself when it is not.
     */
    fun entryRefusal(
        walletAddress: String?,
        walletCluster: Cluster?,
        walletBalance: Long?,
        deployKey: String?,
        cluster: Cluster,
    ): String? = when {
        deployKey == null -> NO_KEY
        walletAddress == null -> NOT_CONNECTED
        walletCluster != null && walletCluster != cluster ->
            "Seed Vault is connected for ${walletCluster.display}, and this project is on ${cluster.display} — " +
                "reconnect it on ${cluster.display} first"
        walletBalance != null && walletBalance <= FEE_RESERVE ->
            "Seed Vault holds ${Loader.lamportsToSol(walletBalance)} on ${cluster.display} — there is nothing to send"
        else -> null
    }

    /** Whether an amount this size needs the second, named confirm: real money does. */
    fun needsConfirm(cluster: Cluster): Boolean = cluster.isMainnet

    /** The mainnet confirm's body, in the Deploy sheet's register: the sum, and that it is real. */
    fun confirmBody(amount: Long, deployKey: String): String =
        "${Loader.lamportsToSol(amount)} of real money moves from Seed Vault to the deploy key " +
            "${Base58.short(deployKey)}. It can be sent back from the Wallet sheet, less the fees."

    /** The picker's "and then it holds" line, or the honest ellipsis while the read is out. */
    fun afterDetail(keyBalance: Long?, amount: Long?): String = when {
        keyBalance == null -> "…"
        amount == null || amount <= 0L -> Loader.lamportsToSol(keyBalance)
        else -> "${Loader.lamportsToSol(keyBalance)} now, ${Loader.lamportsToSol(keyBalance + amount)} after"
    }

    /** What the notification says when it lands. The new balance is the point, so it leads with the amount and ends with it. */
    fun landedDetail(amount: Long, keyBalance: Long?, cluster: Cluster): String =
        if (keyBalance == null) {
            "Seed Vault sent ${Loader.lamportsToSol(amount)} to the deploy key on ${cluster.display}"
        } else {
            "Seed Vault sent ${Loader.lamportsToSol(amount)} — the deploy key holds " +
                "${Loader.lamportsToSol(keyBalance)} on ${cluster.display}"
        }

    /** The one line the sheet writes before the wallet app takes the screen. */
    fun askingDetail(wallet: String, deployKey: String, amount: Long): String =
        "Asking Seed Vault ${Base58.short(wallet)} to send ${Loader.lamportsToSol(amount)} to the deploy key " +
            "${Base58.short(deployKey)} · keep Thragg on screen while it answers"

    /**
     * The one sentence for the failure a Seeker actually hits: the wallet app
     * is set to a different network than the project. It names Thragg's
     * cluster, names the wallet's when one is remembered and it differs, and
     * says exactly where the setting is — the wallet's own words are "Network
     * mismatch" and nothing else, which is not something a person can act on.
     * Measured on the phone 2026-09-09: a devnet project against a wallet app
     * left on mainnet closed the association with no JSON-RPC error at all,
     * so this sentence is the only thing the user gets.
     */
    /**
     * What a Seed Vault request that was never answered is told, when the
     * layers under MWA give no sentence of their own.
     *
     * Three Connect prompts were left to lapse on the Seeker 2026-09-09 and
     * each came back to a sheet reading "Not connected" and nothing else —
     * indistinguishable from three taps that did nothing (QA r4 §14c). The
     * transact path has said this since P-18; connect says it too, with the
     * one word that differs, because nothing was *connected* rather than
     * nothing was *sent*.
     */
    fun didNotAnswer(cluster: Cluster, connecting: Boolean): String =
        "Seed Vault did not answer in time for ${cluster.display} — " +
            (if (connecting) "nothing was connected" else "nothing was sent") + "; try again"

    fun networkMismatch(cluster: Cluster, wallet: Cluster?): String {
        val was = if (wallet != null && wallet != cluster) {
            " It last authorized Thragg for ${wallet.display}."
        } else {
            ""
        }
        return "Thragg is on ${cluster.display} and the Seed Vault Wallet app is set to another network.$was " +
            "Open Seed Vault Wallet, set Network to ${cluster.display} under its Settings, then try again."
    }

    /** A failure as a sentence, for callers outside this package. */
    fun readable(e: Throwable): String = ChainSigning.readable(e)

    // ---- the network half ---------------------------------------------------

    /**
     * Move [amount] lamports from the connected wallet to [deployKey] on
     * [cluster], and return the signature with what the key holds afterwards.
     *
     * Suspends for as long as the person takes to answer Seed Vault, so the
     * caller must hold the foreground service around it
     * (solana/chain/BackgroundWork.kt) — a sheet's scope is not enough, and
     * a top-up that dies because the sheet was dismissed is a Seed Vault
     * prompt the user answered for nothing.
     */
    suspend fun send(
        context: Context,
        cluster: Cluster,
        amount: Long,
        deployKey: Pubkey,
        onLine: (String) -> Unit = {},
    ): TopUpReceipt {
        val connected = SeedVaultWallet.address ?: throw ChainException(NOT_CONNECTED)
        val from = Pubkey.ofOrNull(connected)
            ?: throw ChainException("The remembered wallet address is not valid — reconnect Seed Vault in Settings, under Wallet")
        val rpc = Rpc(cluster)
        val pacer = RpcPacer()
        val signature = transfer(context, cluster, rpc, pacer, from, deployKey, amount, onLine)
        // The transfer has confirmed by here; a balance read that fails is a
        // network hiccup after the money moved, not a failed top-up.
        val held = runCatching { pacer.run { rpc.getBalance(deployKey.base58) } }.getOrNull()
        return TopUpReceipt(signature, amount, held)
    }

    /**
     * THE wallet-to-deploy-key transfer. Every caller in the app goes through
     * this one function: the Wallet sheet's Top up, the Deploy sheet's, the
     * proof-of-work faucet's bootstrap (PowFaucet.fund's `walletTransfer`,
     * wired in WalletSheet) and the deployer's own funding step
     * (ProgramDeploy, `walletTransfer`). There was one copy per caller before,
     * which is three chances for the balance check, the fee reserve and the
     * "keep Thragg on screen" line to drift apart.
     *
     * Nothing is half-done when it fails. The instructions are compiled, the
     * wallet is asked, and only a returned signature is sent — a decline, a
     * network mismatch or the MWA prompt's ninety-second timeout all throw
     * before anything reaches the cluster, so the caller's remedy is always
     * simply to call this again.
     *
     * [rpc] and [pacer] are the caller's, so a deploy's pacing window is not
     * split in two by this call.
     */
    suspend fun transfer(
        context: Context,
        cluster: Cluster,
        rpc: Rpc,
        pacer: RpcPacer,
        from: Pubkey,
        to: Pubkey,
        lamports: Long,
        onLine: (String) -> Unit = {},
    ): String {
        if (lamports <= 0L) throw ChainException("The amount must be more than zero")
        if (from == to) throw ChainException("Seed Vault and the deploy key are the same address here — nothing to send")
        // Re-read rather than trust a row: the row is however old the last
        // Refresh is, and this refusal has to be true now.
        val balance = pacer.run { rpc.getBalance(from.base58) }
        if (balance < lamports + FEE_RESERVE) throw ChainException(shortWalletDetail(balance, lamports, cluster))
        onLine(askingDetail(from.base58, to.base58, lamports))
        return ChainSigning.signAndSend(
            context.applicationContext, cluster, rpc, pacer,
            feePayer = from,
            instructions = listOf(Loader.transfer(from, to, lamports)),
            local = emptyList(),
            wallet = from,
        )
    }

    /** Up to nine digits either side of the point, and nothing exotic. */
    private val AMOUNT = Regex("^[0-9]{0,9}(\\.[0-9]{0,9})?$")

    internal const val NO_KEY = "The deploy key has not been created yet — open Wallet and let it generate one"
    internal const val NOT_CONNECTED = "Connect Seed Vault to send SOL from it"
}
