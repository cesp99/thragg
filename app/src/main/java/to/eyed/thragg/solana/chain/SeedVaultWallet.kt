package to.eyed.thragg.solana.chain

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.AdapterOperations
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.common.ProtocolContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A wallet request that did not end in a signature, with a message a person
 * can act on — every one names the cluster, because on a Seeker the usual
 * reason is that the wallet app is set to a different network than this
 * project (docs/SOLANA.md).
 */
class WalletException(message: String) : Exception(message)

/**
 * The Seeker's Seed Vault wallet, reached through Mobile Wallet Adapter.
 *
 * MWA is a local association: every request starts the wallet app through an
 * `ActivityResultLauncher`, talks to it over a loopback websocket, and comes
 * back with the wallet's answer. Two facts about that protocol shape this
 * object:
 *
 *  * **The launcher has to be registered before the activity starts**, like
 *    every other `registerForActivityResult`, so it is a field of
 *    [to.eyed.thragg.MainActivity] and is handed in through [attach].
 *    Nothing here can talk to the wallet before that has happened, and
 *    [connect] and [sign] say so rather than crash.
 *  * **The wallet fills only its own signature slot.** A deploy is hundreds of
 *    transactions and the wallet is asked to sign as few of them as possible
 *    (the deploy key signs the rest — see ProgramDeploy.kt), so [sign] takes
 *    transactions whose other slots may still be zero, asks the wallet for
 *    its signature FIRST, and checks on the way back that the slot it was
 *    asked for is now filled and verifies. The other slots are filled by the
 *    caller afterwards and the transaction is sent through *our* RPC, so the
 *    cluster is the project's and not whatever the wallet app is pointed at.
 *
 * **What is remembered.** The address, the account label and the auth token,
 * in the `thragg.chain` preferences file shared with the cluster choice, so a
 * Settings row can say "Seed Vault · 7NJd…4kQz" after process death without
 * starting the wallet app. The token is remembered with the cluster it was
 * issued for: MWA tokens are per chain, and presenting a mainnet token on
 * devnet is refused, so a token for another cluster is simply not offered and
 * the wallet asks the user afresh. Nothing else is persisted — no keys ever
 * pass through here; that is the point of a Seed Vault.
 *
 * **Why not `signAndSendTransactions`.** The wallet would send through its
 * own RPC on its own cluster. The deploy needs the send to go through the
 * cluster the project chose, with our confirmation loop and our pacing, so
 * every send is ours and the wallet only signs.
 *
 * The state is Compose state so the Settings rows redraw on connect and
 * disconnect. All wallet calls suspend; none touch UI.
 */
object SeedVaultWallet {

    /** Shared with ClusterStore: one preferences file for the chain layer's small facts. */
    const val PREFS = "thragg.chain"

    private const val KEY_ADDRESS = "wallet.address"
    private const val KEY_LABEL = "wallet.label"
    private const val KEY_AUTH_TOKEN = "wallet.authToken"
    /** The [Cluster.id] the token was issued for; a token is only offered on that cluster. */
    private const val KEY_AUTH_CLUSTER = "wallet.authCluster"

    private const val TAG = "SeedVaultWallet"

    /**
     * Who the wallet shows as asking. The icon is relative to the identity
     * URI, as the MWA spec requires; a wallet that cannot fetch it shows the
     * name alone.
     */
    private val identity = ConnectionIdentity(
        identityUri = Uri.parse("https://eyed.to"),
        iconUri = Uri.parse("favicon.ico"),
        identityName = "Thragg",
    )

    /**
     * One adapter for the process. It holds the auth token and the chain
     * between requests, and changing its `blockchain` drops the token — which
     * is what a cluster switch should do.
     */
    private val adapter = MobileWalletAdapter(identity)

    /** The activity's launcher, replaced on every [attach]; null before the first. */
    @Volatile
    private var sender: ActivityResultSender? = null

    /** The connected wallet's address in base58, or null when none is connected. */
    var address: String? by mutableStateOf(null)
        private set

    /** The account label the wallet reported, when it reported one. */
    var label: String? by mutableStateOf(null)
        private set

    val isConnected: Boolean get() = address != null

    private val lock = Any()

    /** Set once the preferences have been read, or once something newer has been written. */
    @Volatile
    private var restored = false

    /**
     * Hand over the activity's launcher. Called from `MainActivity.onCreate`
     * on every creation, so the wallet always launches from the activity on
     * screen. Holding the newest one is holding one activity, which is
     * what MWA's design costs; the previous one is released here.
     */
    fun attach(sender: ActivityResultSender) {
        this.sender = sender
    }

    /**
     * Bring the remembered address back off disk, on a background thread.
     * Returns at once; the Settings rows observe [address] and redraw when it
     * lands. Idempotent, and it stands down if a [connect] has already written
     * something newer.
     */
    fun restore(context: Context) {
        if (restored) return
        val app = context.applicationContext
        Thread({ runCatching { restoreBlocking(app) } }, "wallet-restore")
            .apply { isDaemon = true }
            .start()
    }

    /** The MWA chain object for [cluster]. Here, and not on the enum, to keep Cluster.kt free of MWA for host tests. */
    fun blockchainOf(cluster: Cluster): Blockchain = when (cluster) {
        Cluster.Devnet -> Solana.Devnet
        Cluster.Testnet -> Solana.Testnet
        Cluster.MainnetBeta -> Solana.Mainnet
    }

    /**
     * Authorize with the wallet for [cluster] and remember the account it
     * chose. Success is the address in base58.
     */
    suspend fun connect(context: Context, cluster: Cluster): Result<String> {
        val sender = this.sender ?: return Result.failure(WalletException(NOT_ATTACHED))
        val app = context.applicationContext
        withContext(Dispatchers.IO) { restoreBlocking(app) }
        return when (val result = transact(sender, cluster) { authResult ->
            val account = authResult.accounts.first()
            Base58.encode(account.publicKey) to account.accountLabel
        }) {
            is TransactionResult.Success -> {
                val (walletAddress, walletLabel) = result.payload
                remember(app, walletAddress, walletLabel, result.authResult.authToken, cluster)
                Result.success(walletAddress)
            }
            is TransactionResult.Failure -> Result.failure(describe(result, cluster, Asking.Connect))
            is TransactionResult.NoWalletFound -> Result.failure(WalletException(NO_WALLET))
        }
    }

    /**
     * Forget the wallet here, and tell it so. The local half always happens —
     * the address and token are gone from memory and disk before the wallet
     * app is even started — so a wallet that does not answer leaves the app
     * disconnected all the same; the failure then only says the wallet may
     * still list Thragg among its connected apps.
     */
    suspend fun disconnect(context: Context): Result<Unit> {
        val app = context.applicationContext
        withContext(Dispatchers.IO) { restoreBlocking(app) }
        val token = adapter.authToken
        forget(app)
        val sender = this.sender
        if (token == null || sender == null) return Result.success(Unit)
        adapter.authToken = token
        return when (val result = guarded("deauthorize") { adapter.disconnect(sender) }) {
            is TransactionResult.Success -> Result.success(Unit)
            is TransactionResult.Failure -> {
                adapter.authToken = null
                Log.w(TAG, "deauthorize: ${result.message}", result.e)
                Result.failure(
                    WalletException(
                        "Disconnected here, but Seed Vault did not confirm — " +
                            "it may still list Thragg among its connected apps"
                    )
                )
            }
            is TransactionResult.NoWalletFound -> {
                adapter.authToken = null
                Result.success(Unit)
            }
        }
    }

    /**
     * Ask the wallet to sign [transactions], already compiled, in one request.
     * The wallet's slot in each comes back filled; every other slot is left as
     * it was. Fails — with nothing sent anywhere — when the wallet is not the
     * account a transaction names, alters a message, or returns a slot that is
     * still empty or does not verify.
     */
    suspend fun sign(
        context: Context,
        cluster: Cluster,
        transactions: List<Transaction>,
    ): Result<List<Transaction>> {
        if (transactions.isEmpty()) return Result.success(emptyList())
        val sender = this.sender ?: return Result.failure(WalletException(NOT_ATTACHED))
        val app = context.applicationContext
        withContext(Dispatchers.IO) { restoreBlocking(app) }
        val connected = address ?: return Result.failure(WalletException(NOT_CONNECTED))
        val wallet = Pubkey.ofOrNull(connected)
            ?: return Result.failure(WalletException("The remembered wallet address is not valid — reconnect Seed Vault in Settings, under Wallet"))
        // Before starting the wallet app: a transaction that does not name the
        // wallet as a signer cannot be signed by it, and saying so now is
        // better than a wallet error after the user has tapped through.
        transactions.forEachIndexed { index, tx ->
            val slot = tx.message.indexOf(wallet)
            if (slot < 0 || slot >= tx.message.signerCount) {
                return Result.failure(
                    WalletException("Transaction ${index + 1} does not name Seed Vault ${Base58.short(connected)} as a signer")
                )
            }
        }
        val payloads = transactions.map { it.serialize() }.toTypedArray()
        val result = transact(sender, cluster) { signTransactions(payloads).signedPayloads.toList() }
        return when (result) {
            is TransactionResult.Success -> {
                val signer = result.authResult.accounts.firstOrNull()?.let { Base58.encode(it.publicKey) }
                if (signer != null && signer != connected) {
                    // The wallet authorized a different account than the one
                    // these transactions were built for. Remember what it is
                    // now, so the rows are honest, and let the caller rebuild.
                    remember(app, signer, result.authResult.accounts.first().accountLabel, result.authResult.authToken, cluster)
                    return Result.failure(
                        WalletException(
                            "Seed Vault signed as ${Base58.short(signer)}, but these transactions expect " +
                                "${Base58.short(connected)} — the wallet's account changed; try again"
                        )
                    )
                }
                remember(app, connected, label, result.authResult.authToken, cluster)
                runCatching { merge(transactions, result.payload, wallet) }
            }
            is TransactionResult.Failure -> Result.failure(describe(result, cluster, Asking.Sign))
            is TransactionResult.NoWalletFound -> Result.failure(WalletException(NO_WALLET))
        }
    }

    // -- The wallet's answer --

    /**
     * The signed payloads back into transactions, each checked by
     * [WalletAnswers.accept]: the wallet may have refreshed the blockhash and
     * added compute-budget instructions — the Seeker's own wallet does both —
     * but it must have kept our instructions, our fee payer, and signed what
     * it returned.
     */
    private fun merge(
        originals: List<Transaction>,
        signed: List<ByteArray>,
        wallet: Pubkey,
    ): List<Transaction> {
        if (signed.size != originals.size) {
            throw WalletException("Seed Vault returned ${signed.size} signed transactions for ${originals.size} sent")
        }
        return originals.mapIndexed { index, original ->
            WalletAnswers.accept(original, signed[index], wallet, index + 1)
        }
    }

    // -- Talking to the wallet --

    /**
     * One `transact` on [cluster], with one retry for the case the protocol
     * makes routine: a remembered auth token the wallet no longer honours.
     * The wallet answers `ERROR_AUTHORIZATION_FAILED` to a stale token;
     * dropping it and asking again turns that into the ordinary "may Seeker
     * IDE connect?" prompt instead of an error about a token the user never
     * saw.
     *
     * The chain is set here, and put back when the wallet does not answer.
     * Setting the adapter's `blockchain` to a different chain drops its
     * token on the spot — before the wallet has been asked anything — so a
     * connect for testnet that is declined, or times out, or finds no wallet
     * would otherwise have cost the devnet token that was working fine. On
     * anything but success the previous chain and token are restored, unless
     * the token was the thing the wallet refused.
     */
    private suspend fun <T> transact(
        sender: ActivityResultSender,
        cluster: Cluster,
        block: suspend AdapterOperations.(AuthorizationResult) -> T,
    ): TransactionResult<T> {
        val previousChain = adapter.blockchain
        val previousToken = adapter.authToken
        adapter.blockchain = blockchainOf(cluster)
        val hadToken = adapter.authToken != null
        var result = guarded("transact") { adapter.transact(sender, block = block) }
        var refused = false
        if (hadToken && result is TransactionResult.Failure && result.remoteCode == ProtocolContract.ERROR_AUTHORIZATION_FAILED) {
            Log.i(TAG, "auth token for ${cluster.display} refused; asking afresh")
            adapter.authToken = null
            refused = true
            result = guarded("transact") { adapter.transact(sender, block = block) }
        }
        if (result !is TransactionResult.Success && !refused) {
            adapter.blockchain = previousChain
            adapter.authToken = previousToken
        }
        return result
    }

    /**
     * The adapter's call as a [TransactionResult], whatever it does. The
     * library's `transact` catches what its own protocol throws, but its
     * `finally` waits ten seconds for the association to close and lets that
     * wait's `TimeoutException` (and an `InterruptedException`) out — after
     * the wallet may already have answered. A thrown exception here would
     * unwind past every caller's `when` and take the app down; a Failure is
     * a sentence in a notification. Cancellation is not an answer and is
     * passed on.
     */
    private suspend fun <T> guarded(what: String, call: suspend () -> TransactionResult<T>): TransactionResult<T> =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what threw", e)
            TransactionResult.Failure("the session with the wallet ended in ${e.javaClass.simpleName}", e)
        }

    private enum class Asking { Connect, Sign }

    /**
     * The wallet's JSON-RPC error code, when the failure was one — found by
     * walking the cause chain, not by looking at `e`. The library's `transact`
     * runs the block inside the same `try` as the association's `start()`, so
     * a decline, a refused chain or a stale token surfaces as
     * `Failure("Failed establishing local association with wallet", ExecutionException)`
     * whose cause is the remote exception (verified against clientlib-ktx
     * 2.2.0's bytecode). `InvalidPayloadsException` and `NotSubmittedException`
     * subclass the remote exception, so they answer too.
     */
    private val TransactionResult.Failure<*>.remoteCode: Int?
        get() = generateSequence<Throwable>(e) { it.cause }
            .filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>()
            .firstOrNull()
            ?.code

    /**
     * A failure as a sentence. The library's own messages are for its authors
     * ("Remote exception", "Auth token invalid"); these name what the person
     * can do, and they name the cluster, because a wallet app set to the wrong
     * network is the common case on a Seeker.
     */
    private fun describe(failure: TransactionResult.Failure<*>, cluster: Cluster, asking: Asking): WalletException {
        val where = cluster.display
        val message = when (failure.remoteCode) {
            ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED ->
                if (cluster.hasFaucet) {
                    "Seed Vault refused to authorize for $where — switch the wallet app to $where, " +
                        "or fund the deploy key from the faucet instead"
                } else {
                    "Seed Vault refused to authorize for $where — switch the wallet app to $where"
                }
            ProtocolContract.ERROR_AUTHORIZATION_FAILED -> when (asking) {
                Asking.Connect -> "Seed Vault declined to connect for $where"
                Asking.Sign -> "Seed Vault no longer authorizes Thragg on $where — reconnect it in Settings, under Wallet"
            }
            ProtocolContract.ERROR_NOT_SIGNED -> "Seed Vault declined to sign for $where"
            ProtocolContract.ERROR_TOO_MANY_PAYLOADS -> "Seed Vault will not sign that many transactions in one request on $where"
            ProtocolContract.ERROR_INVALID_PAYLOADS -> "Seed Vault rejected the transactions for $where as malformed"
            else -> when {
                failure.message.startsWith("Request was interrupted") || failure.message.startsWith("Request was cancelled") ->
                    "Seed Vault was closed before it answered for $where"
                // The launcher waits for the activity to be RESUMED before it
                // starts the wallet, and gives up after twenty seconds: this
                // one means the app was not on screen, not that the wallet
                // was slow.
                failure.message.startsWith("Timed out waiting to send association intent") ->
                    "Thragg must be on screen for Seed Vault to be asked — bring it to the front and try again"
                failure.message.startsWith("Timed out") ->
                    "Seed Vault did not answer in time for $where"
                failure.message.startsWith("Received an activity start request") ->
                    "Seed Vault is already being asked — finish that request first"
                // Measured on a Seeker whose wallet was set to mainnet: the
                // wallet shows its own "Network mismatch" sheet and closes
                // the association without a JSON-RPC error, so from here it
                // looks like a plain cancel. Off mainnet, say what to check.
                failure.message.startsWith("Local association was cancelled") && !cluster.isMainnet ->
                    "Seed Vault closed without signing for $where — if it said \"Network mismatch\", " +
                        "set its Network to $where under the wallet app's Settings, then try again"
                failure.message.startsWith("Local association was cancelled") ->
                    "Seed Vault closed without signing for $where"
                else -> "Seed Vault, on $where: ${failure.message}"
            }
        }
        Log.w(TAG, "$asking on ${cluster.display}: ${failure.message}", failure.e)
        return WalletException(message)
    }

    // -- What is remembered --

    private fun restoreBlocking(app: Context) {
        if (restored) return
        synchronized(lock) {
            if (restored) return
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_ADDRESS, null)?.takeIf { Base58.isPubkey(it) }
            if (saved != null) {
                address = saved
                label = prefs.getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() }
                val token = prefs.getString(KEY_AUTH_TOKEN, null)?.takeIf { it.isNotBlank() }
                val issuedFor = Cluster.fromId(prefs.getString(KEY_AUTH_CLUSTER, null))
                if (token != null && issuedFor != null) {
                    // Set the chain first: the adapter drops its token on a
                    // chain change, and a token is only offered on its own.
                    adapter.blockchain = blockchainOf(issuedFor)
                    adapter.authToken = token
                }
            }
            restored = true
        }
    }

    private fun remember(app: Context, walletAddress: String, walletLabel: String?, token: String?, cluster: Cluster) {
        synchronized(lock) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ADDRESS, walletAddress)
                .apply { if (walletLabel.isNullOrBlank()) remove(KEY_LABEL) else putString(KEY_LABEL, walletLabel) }
                .apply { if (token.isNullOrBlank()) remove(KEY_AUTH_TOKEN) else putString(KEY_AUTH_TOKEN, token) }
                .putString(KEY_AUTH_CLUSTER, cluster.id)
                .apply()
            address = walletAddress
            label = walletLabel?.takeIf { it.isNotBlank() }
            restored = true
        }
    }

    private fun forget(app: Context) {
        synchronized(lock) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_ADDRESS)
                .remove(KEY_LABEL)
                .remove(KEY_AUTH_TOKEN)
                .remove(KEY_AUTH_CLUSTER)
                .apply()
            address = null
            label = null
            adapter.authToken = null
            restored = true
        }
    }

    private const val NOT_ATTACHED = "Wallet is not available yet"
    private const val NOT_CONNECTED = "Connect Seed Vault in Settings, under Wallet, first"
    private const val NO_WALLET = "No wallet app found — this phone has no Mobile Wallet Adapter wallet to ask"
}
