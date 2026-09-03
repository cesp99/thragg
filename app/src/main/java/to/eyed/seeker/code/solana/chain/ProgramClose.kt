package to.eyed.seeker.code.solana.chain

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * Closing a program, or a buffer left behind by a deploy that never finished,
 * and getting the rent back.
 *
 * Two closes, one shape. The loader's `Close` instruction drains an account
 * into a recipient when its authority signs; for a programdata account it
 * also flips the Program account so the id can never be deployed again,
 * which is why the Settings sheet puts a red confirm in front of this and why
 * nothing here asks twice — by the time [close] runs the person has read the
 * warning. A buffer close has no such consequence: a buffer is a half-finished
 * upload, and reclaiming it is the recovery path for a deploy that lost the
 * network after step three (see ProgramDeploy.kt).
 *
 * Who signs follows from who the authority is, and there are exactly two
 * answers this phone can give. The deploy key signs locally, on this thread,
 * with no prompt. The Seed Vault wallet signs through Mobile Wallet Adapter,
 * which is a prompt, so it is asked first — on a transaction whose other
 * slots are still zero — and the deploy key fills its slot afterwards, and
 * the send goes through our RPC on the project's cluster rather than the
 * wallet's. That order is [ChainSigning.signAndSend], shared with the
 * deployer because the upgrade-by-wallet path is the same dance.
 *
 * The fee payer is a separate question from the authority. A close returns
 * lamports to the recipient *after* the fee is charged, so an empty deploy
 * key cannot pay for its own close; [ChainSigning.feePayer] falls back to the
 * wallet when one is connected, and says what to do when none is.
 *
 * The rent goes to the wallet when one is connected and to the deploy key
 * otherwise — [recipientFor], pure and pinned, because the confirm dialog
 * names it before anything is signed.
 */
object ProgramClose {

    /** Enough to pay one transaction's fee with a little to spare. */
    private const val FEE_RESERVE = 10_000L

    /** Notifications from a close replace each other rather than stacking. */
    const val NOTIFICATION_KEY = "chain.close"

    /**
     * Where a close sends the rent: the connected wallet when there is one,
     * else the deploy key. The same rule for programs and buffers, so the
     * dialog and the transaction cannot disagree.
     */
    fun recipientFor(wallet: String?, deployKey: String): String = wallet ?: deployKey

    /**
     * Close [status] on [cluster] and return the rent to [recipientFor]'s
     * choice. The authority must be the deploy key or the connected wallet
     * ([ProgramStatus.canClose]); anything else fails before a byte is sent.
     * Success is the transaction signature; the DeployedPrograms record is
     * removed and a notification is raised under [NOTIFICATION_KEY].
     */
    suspend fun close(
        context: Context,
        cluster: Cluster,
        status: OnChainProgram.Deployed,
        onLine: (String) -> Unit,
    ): Result<String> = BackgroundWork.hold(context, "chain.close") {
        attempt {
            val app = context.applicationContext
            val rpc = Rpc(cluster)
            val pacer = RpcPacer()
            val deployKey = DeployKey.get(app)
            val wallet = SeedVaultWallet.address?.let { Pubkey.ofOrNull(it) }
            val program = Pubkey.of(status.programId)
            val programData = Pubkey.of(status.programData)

            val current = status.authority
                ?: throw ChainException("${Base58.short(status.programId)} is immutable on ${cluster.display} — nobody can close it")
            val authority: Pubkey = when {
                current == deployKey.publicKey.base58 -> deployKey.publicKey
                wallet != null && current == wallet.base58 -> wallet
                else -> throw ChainException(
                    "The upgrade authority of ${Base58.short(status.programId)} on ${cluster.display} is " +
                        "${Base58.short(current)}, which neither Seed Vault nor this phone's deploy key can sign for"
                )
            }
            val recipient = Pubkey.of(recipientFor(wallet?.base58, deployKey.publicKey.base58))
            val payer = ChainSigning.feePayer(rpc, pacer, cluster, deployKey, wallet, FEE_RESERVE)
            onLine("Closing ${Base58.short(status.programId)} on ${cluster.display} · ${Loader.lamportsToSol(status.reclaimable)} to ${nameOf(recipient, wallet)}")

            val signature = ChainSigning.signAndSend(
                app, cluster, rpc, pacer, payer,
                listOf(Loader.closeProgram(programData, recipient, authority, program)),
                local = listOf(deployKey), wallet = wallet,
            )
            onLine("Closed · ${cluster.explorerTx(signature)}")
            DeployedPrograms.remove(app, status.programId, cluster)
            Notifications.info(
                "Closed ${Base58.short(status.programId)} · ${Loader.lamportsToSol(status.reclaimable)} returned",
                key = NOTIFICATION_KEY,
            )
            signature
        }
    }

    /**
     * Reclaim a buffer a deploy left open. The recorded authority is normally
     * the deploy key; it is the wallet only when an upgrade handed the buffer
     * over and then failed, and that case signs through the wallet the same
     * way [close] does. A buffer that is already gone is forgotten from
     * OpenBuffers and reported as a failure with no signature, since there
     * was nothing to sign.
     */
    suspend fun closeBuffer(
        context: Context,
        cluster: Cluster,
        buffer: OpenBuffer,
        onLine: (String) -> Unit = {},
    ): Result<String> = BackgroundWork.hold(context, "chain.close") {
        attempt {
            val app = context.applicationContext
            val rpc = Rpc(cluster)
            val pacer = RpcPacer()
            val deployKey = DeployKey.get(app)
            val wallet = SeedVaultWallet.address?.let { Pubkey.ofOrNull(it) }
            val address = Pubkey.ofOrNull(buffer.address)
                ?: throw ChainException("${buffer.address} is not a valid buffer address")

            val account = pacer.run { rpc.getAccountInfo(buffer.address) }
            val state = account?.let { Loader.parse(it.data) } as? Loader.State.Buffer
            if (account == null || state == null) {
                OpenBuffers.remove(app, buffer.address)
                throw ChainException("Buffer ${Base58.short(buffer.address)} is already gone from ${cluster.display} — removed from the list")
            }
            val current = state.authority
                ?: throw ChainException("Buffer ${Base58.short(buffer.address)} has no authority — nobody can close it")
            val authority: Pubkey = when {
                current == deployKey.publicKey -> deployKey.publicKey
                wallet != null && current == wallet -> wallet
                else -> throw ChainException(
                    "Buffer ${Base58.short(buffer.address)} is controlled by ${Base58.short(current.base58)}, " +
                        "which neither Seed Vault nor this phone's deploy key can sign for"
                )
            }
            val recipient = Pubkey.of(recipientFor(wallet?.base58, deployKey.publicKey.base58))
            val payer = ChainSigning.feePayer(rpc, pacer, cluster, deployKey, wallet, FEE_RESERVE)
            onLine("Reclaiming buffer ${Base58.short(buffer.address)} · ${Loader.lamportsToSol(account.lamports)} to ${nameOf(recipient, wallet)}")

            val signature = ChainSigning.signAndSend(
                app, cluster, rpc, pacer, payer,
                listOf(Loader.closeBuffer(address, recipient, authority)),
                local = listOf(deployKey), wallet = wallet,
            )
            OpenBuffers.remove(app, buffer.address)
            onLine("Reclaimed · ${cluster.explorerTx(signature)}")
            Notifications.info(
                "Reclaimed buffer ${Base58.short(buffer.address)} · ${Loader.lamportsToSol(account.lamports)} returned",
                key = NOTIFICATION_KEY,
            )
            signature
        }
    }

    /** "Seed Vault 7NJd…4kQz" or "the deploy key 9wQt…m2Xf". */
    private fun nameOf(recipient: Pubkey, wallet: Pubkey?): String =
        if (wallet != null && recipient == wallet) "Seed Vault ${Base58.short(recipient.base58)}"
        else "the deploy key ${Base58.short(recipient.base58)}"

    /**
     * Run [block] on IO and fold every failure but cancellation into a
     * [Result] whose message a person can act on. Cancellation is rethrown so
     * the caller's scope sees it as what it is.
     */
    private suspend fun attempt(block: suspend () -> String): Result<String> =
        try {
            Result.success(withContext(Dispatchers.IO) { block() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(ChainException(ChainSigning.readable(e), e))
        }
}

/** A chain operation that stopped, with a message written for the person reading the log. */
open class ChainException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The signing order every wallet-involving transaction in this package
 * follows, and the fee-payer choice that goes with it. Internal: the deployer
 * and the close flow share it, nothing else should need it.
 */
internal object ChainSigning {

    /** How many blockhashes a transaction may outlive before the failure is reported. */
    private const val SIGN_ROUNDS = 3

    /**
     * Compile [instructions] with [feePayer] paying, sign, send, and wait for
     * confirmation. The wallet's slot is filled first, through Mobile Wallet
     * Adapter, when [wallet] is one of the message's signers; each [local]
     * keypair that is a signer then fills its own. Returns the signature.
     * Throws when a slot is still empty after that — a message that names a
     * signer nobody here can be.
     *
     * The blockhash is fetched HERE, immediately before the signing, and the
     * whole round is repeated over a fresh one when it runs out. A blockhash
     * lives about a minute, and a person answering Seed Vault's prompt does
     * not: the hash must not be older than the prompt, and a prompt that took
     * two minutes must not cost a deploy its buffer. An expired hash is safe
     * to retry — the chain is past the height at which the old transaction
     * could still land, and a preflight refusal never sent it — so each
     * round is the same instructions, re-signed (the wallet asked again when
     * it is a signer) and resent, up to [SIGN_ROUNDS] times. The one gap,
     * a transaction that landed in the last valid block but was not yet
     * visible when the height was read, is closed by asking after the
     * previous signature before signing again.
     */
    suspend fun signAndSend(
        context: Context,
        cluster: Cluster,
        rpc: Rpc,
        pacer: RpcPacer,
        feePayer: Pubkey,
        instructions: List<Instruction>,
        local: List<Keypair>,
        wallet: Pubkey?,
    ): String {
        var expired: RpcException? = null
        var previous: String? = null
        for (round in 1..SIGN_ROUNDS) {
            previous?.let { sent ->
                val landed = pacer.run { rpc.getSignatureStatuses(listOf(sent)) }.firstOrNull()
                if (landed != null && landed.err == null && landed.confirmed) return sent
            }
            val blockhash = pacer.run { rpc.getLatestBlockhash() }
            val message = Message.compile(feePayer, instructions, blockhash.blockhash)
            var tx = Transaction.unsigned(message)
            if (wallet != null && message.isSigner(wallet)) {
                // MWA launches the wallet app through the activity's launcher;
                // ask from Main, where every other caller of the adapter lives.
                tx = withContext(Dispatchers.Main) { SeedVaultWallet.sign(context, cluster, listOf(tx)) }
                    .getOrThrow()
                    .single()
            }
            // The wallet's message, not ours: it may have refreshed the
            // blockhash or put a compute-budget instruction in front
            // (WalletAnswers), and a local signature over the original bytes
            // would not verify against what is being sent.
            val signedMessage = tx.message
            val bytes = signedMessage.serialize()
            for (keypair in local) {
                if (signedMessage.isSigner(keypair.publicKey)) {
                    tx = tx.withSignature(keypair.publicKey, keypair.sign(bytes))
                }
            }
            if (!tx.isFullySigned) {
                val missing = signedMessage.accountKeys.take(signedMessage.signerCount)
                    .filterIndexed { index, _ -> tx.signatures[index].all { it == 0.toByte() } }
                    .joinToString(", ") { Base58.short(it.base58) }
                throw ChainException("No signer available for $missing")
            }
            try {
                val signature = pacer.run { rpc.sendTransaction(tx) }
                previous = signature
                pacer.run { rpc.confirm(signature, blockhash.lastValidBlockHeight) }
                return signature
            } catch (e: RpcException) {
                if (!e.isBlockhashExpiry) throw e
                expired = e
            }
        }
        throw ChainException(
            "The blockhash expired $SIGN_ROUNDS times before the transaction landed on ${cluster.display}",
            expired,
        )
    }

    /**
     * Who pays the fee: the deploy key when it holds at least [reserve]
     * lamports, else the wallet when one is connected. With neither, the
     * message says which address to fund and how, by cluster.
     */
    suspend fun feePayer(
        rpc: Rpc,
        pacer: RpcPacer,
        cluster: Cluster,
        deployKey: Keypair,
        wallet: Pubkey?,
        reserve: Long,
    ): Pubkey {
        val balance = pacer.run { rpc.getBalance(deployKey.publicKey.base58) }
        if (balance >= reserve) return deployKey.publicKey
        if (wallet != null) return wallet
        val how = if (cluster.hasFaucet) "tap Airdrop in Settings, under Wallet" else "connect Seed Vault in Settings, under Wallet"
        throw ChainException(
            "The deploy key ${Base58.short(deployKey.publicKey.base58)} holds ${Loader.lamportsToSol(balance)} on " +
                "${cluster.display}, not enough for a transaction fee — $how"
        )
    }

    /** The message a log row gets: ours as written, the rest with the exception's name stripped. */
    fun readable(e: Throwable): String = when (e) {
        is ChainException, is RpcException, is WalletException -> e.message ?: "failed"
        is java.net.UnknownHostException -> "No network — could not resolve ${e.message}"
        is java.net.SocketTimeoutException -> "The node did not answer in time"
        is java.io.IOException -> e.message?.let { "Network error: $it" } ?: "Network error"
        else -> e.message ?: e.javaClass.simpleName
    }
}
