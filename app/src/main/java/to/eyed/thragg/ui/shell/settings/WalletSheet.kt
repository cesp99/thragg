package to.eyed.thragg.ui.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.R
import to.eyed.thragg.solana.chain.BackgroundWork
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.DeployKey
import to.eyed.thragg.solana.chain.DeployedProgram
import to.eyed.thragg.solana.chain.DeployedPrograms
import to.eyed.thragg.solana.chain.Keypair
import to.eyed.thragg.solana.chain.Loader
import to.eyed.thragg.solana.chain.Message
import to.eyed.thragg.solana.chain.OnChainProgram
import to.eyed.thragg.solana.chain.OpenBuffer
import to.eyed.thragg.solana.chain.OpenBuffers
import to.eyed.thragg.solana.chain.PowFaucet
import to.eyed.thragg.solana.chain.ProgramClose
import to.eyed.thragg.solana.chain.ProgramStatus
import to.eyed.thragg.solana.chain.Pubkey
import to.eyed.thragg.solana.chain.Rpc
import to.eyed.thragg.solana.chain.RpcException
import to.eyed.thragg.solana.chain.RpcPacer
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.solana.chain.Transaction
import to.eyed.thragg.solana.chain.WalletTopUp
import to.eyed.thragg.ui.components.CopyChip
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.Severity
import to.eyed.thragg.ui.components.SectionHeader
import to.eyed.thragg.ui.components.ThraggCard
import to.eyed.thragg.ui.components.ThraggChip
import to.eyed.thragg.ui.components.ThraggSpinner
import to.eyed.thragg.ui.components.outlinedButtonEdge
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIconButton
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.workspace.ContextMenu
import to.eyed.thragg.ui.workspace.ContextMenuItem
import to.eyed.thragg.ui.workspace.Notifications

/**
 * The Wallet sheet: the two keys this phone signs with, and what they hold.
 *
 * TWO KEYS, AND THE SHEET HAS TO EXPLAIN WHY. The Seed Vault wallet is the
 * user's — it is what ends up holding the upgrade authority and, on mainnet,
 * what pays. But a deploy is two hundred Write transactions, and asking the
 * wallet app to sign two hundred times is a deploy nobody finishes. So the
 * app keeps a second, plain keypair on disk (`DeployKey`, ChainRecords.kt)
 * that signs the writes: on devnet the faucet funds it, on mainnet the wallet
 * hands it what the deploy needs and gets the change back. The "Deploy key"
 * card's first line is that explanation, in one sentence, because a second
 * address with a balance in it is otherwise a thing the user did not ask for
 * and does not know if they can lose.
 *
 * EVERY BALANCE IS FETCHED, NEVER REMEMBERED. Two RPC reads on IO when the
 * sheet opens, a spinner while they are out, a sentence when they fail, and
 * a Refresh chip — a balance is the one number here that changes without us.
 * The faucet and the return-to-wallet transfer are the deploy key's two ways
 * to move SOL and both are built here in Kotlin (there is no `solana` CLI on
 * this phone, docs/SOLANA.md): the transfer signs locally with the deploy key
 * and leaves exactly one fee behind.
 *
 * The last two cards are the records ChainRecords.kt keeps so a deploy that
 * died halfway is recoverable: a buffer left holding rent gets a Reclaim, and
 * a program deployed from this phone gets its id, its explorer page and the
 * same red close the Program sheet has. Both cards are absent when there is
 * nothing in them; an empty "Open buffers" heading would be a worry with no
 * content.
 */
@Composable
internal fun WalletSheet(
    state: ShellState,
    cluster: Cluster,
    onDismiss: () -> Unit,
    /** Put the deploy key's card above Seed Vault's — for a caller who came to fund it. */
    deployKeyFirst: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Doze is the one thing the foreground service and the wake lock cannot
    // fix, and mining is minutes of network with the screen off — the same
    // ask the Deploy sheet makes, made here, on the screen the miner is
    // started from (QA G-23). The system dialog returns no result, so the
    // answer is read again every time this activity comes back to the front.
    var unrestrictedPoll by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) unrestrictedPoll++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val unrestricted by produceState(true, unrestrictedPoll) {
        value = BackgroundWork.isUnrestricted(context)
    }
    val wallet = SeedVaultWallet.address
    val walletLabel = SeedVaultWallet.label
    // Bumped by Refresh and by every action that moved SOL; the balance and
    // record effects key on it. A top-up runs on its own scope and may land
    // after this sheet was dismissed and reopened, so its version counts too.
    var refresh by remember { mutableIntStateOf(0) }
    val topUpVersion = TopUpProgress.version
    // The amount picker, and the one refusal the miner can discover for us.
    var topUpOpen by remember { mutableStateOf(false) }
    var faucetDry by remember { mutableStateOf(false) }
    /**
     * What Seed Vault last said when asked to connect, or null.
     *
     * A notification is not enough here. Three Connect prompts were left to
     * lapse on the Seeker 2026-09-09 and each one came back to a sheet
     * reading "Not connected" with no message at all — indistinguishable
     * from three taps that did nothing (QA r4 §14c). The transact path has
     * said "Seed Vault did not answer in time … nothing was sent; try again"
     * since P-18; this is the same sentence, printed where the person is
     * looking, because a toast raised behind a bottom sheet is not (G-15).
     */
    var connectFailure by remember { mutableStateOf<String?>(null) }
    // Why it is dry, in the miner's own words: with two difficulty specs the
    // one this miner claims from can be empty while the other is not, and
    // "the faucet is empty" alone reads as wrong when it is (B-06).
    var faucetDryReason by remember { mutableStateOf(PowFaucet.EMPTY_REASON) }

    // --- Seed Vault ------------------------------------------------------
    var walletBalance by remember { mutableStateOf<Long?>(null) }
    var walletBalanceFailed by remember { mutableStateOf(false) }
    var walletLoading by remember { mutableStateOf(false) }
    var walletBusy by remember { mutableStateOf(false) }
    LaunchedEffect(wallet, cluster, refresh, topUpVersion) {
        walletBalance = null
        walletBalanceFailed = false
        if (wallet == null) {
            walletLoading = false
            return@LaunchedEffect
        }
        walletLoading = true
        val read = withContext(Dispatchers.IO) { runCatching { Rpc(cluster).getBalance(wallet) } }
        walletLoading = false
        walletBalance = read.getOrNull()
        walletBalanceFailed = read.isFailure
    }

    // --- Deploy key ------------------------------------------------------
    // Generated on first use, here: this sheet is where the address is shown
    // and where it is funded, so a key that does not exist yet is exactly
    // what the user came to see.
    var deployKey by remember { mutableStateOf<Keypair?>(null) }
    var keyBalance by remember { mutableStateOf<Long?>(null) }
    var keyBalanceFailed by remember { mutableStateOf(false) }
    // The key could not be read or generated — a disk fact, not a network
    // one, so it is the Address row that says so and not the balance's
    // "could not reach", which would send the user looking at the wrong thing.
    var keyMissing by remember { mutableStateOf(false) }
    var keyLoading by remember { mutableStateOf(false) }
    var keyBusy by remember { mutableStateOf(false) }
    // What the busy row says: a mining session rewrites it every couple of
    // seconds with what has landed, so a two-minute wait is not a spinner.
    var keyBusyLabel by remember { mutableStateOf("") }
    // The mining job, so the busy row's Stop can cancel it; null otherwise.
    var miningJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(cluster, refresh, topUpVersion) {
        keyLoading = true
        keyBalance = null
        keyBalanceFailed = false
        keyMissing = false
        val key = withContext(Dispatchers.IO) { runCatching { DeployKey.get(context) }.getOrNull() }
        deployKey = key
        if (key == null) {
            keyLoading = false
            keyMissing = true
            return@LaunchedEffect
        }
        val read = withContext(Dispatchers.IO) {
            runCatching { Rpc(cluster).getBalance(key.publicKey.base58) }
        }
        keyLoading = false
        keyBalance = read.getOrNull()
        keyBalanceFailed = read.isFailure
    }
    val deployKeyAddress = deployKey?.publicKey?.base58

    // --- Records ---------------------------------------------------------
    val buffersVersion = OpenBuffers.version
    val programsVersion = DeployedPrograms.version
    var buffers by remember { mutableStateOf<List<OpenBuffer>>(emptyList()) }
    var programs by remember { mutableStateOf<List<DeployedProgram>>(emptyList()) }
    LaunchedEffect(cluster, buffersVersion, programsVersion, refresh) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                OpenBuffers.all(context).filter { it.cluster == cluster } to
                    DeployedPrograms.all(context).filter { it.cluster == cluster }
            }.getOrNull()
        }
        if (loaded != null) {
            buffers = loaded.first
            programs = loaded.second
        }
    }

    // --- Close, shared with the Program sheet ------------------------------
    // The close itself runs on CloseProgress's own scope (ProgramSheet.kt);
    // the record list re-reads on DeployedPrograms.version when it lands.
    var closeTarget by remember { mutableStateOf<Pair<String, OnChainProgram.Deployed>?>(null) }

    // Both wallet calls answer with a Result, and both are held in a try all
    // the same: an exception out of a launch on the composition's scope is
    // an uncaught exception on Main, and a spinner that never stops is the
    // best case of that. The finally is what puts the buttons back.
    fun connect() {
        if (walletBusy) return
        walletBusy = true
        connectFailure = null
        scope.launch {
            try {
                SeedVaultWallet.connect(context, cluster)
                    .onSuccess { Notifications.info("Connected Seed Vault ${Base58.short(it)}", key = WALLET_KEY) }
                    .onFailure {
                        val said = it.message ?: WalletTopUp.didNotAnswer(cluster, connecting = true)
                        connectFailure = said
                        Notifications.error(said, key = WALLET_KEY)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val said = "Seed Vault did not answer: ${e.message ?: e.javaClass.simpleName}"
                connectFailure = said
                Notifications.error(said, key = WALLET_KEY)
            } finally {
                walletBusy = false
            }
        }
    }

    fun disconnect() {
        if (walletBusy) return
        walletBusy = true
        connectFailure = null
        scope.launch {
            try {
                SeedVaultWallet.disconnect(context)
                    .onSuccess { Notifications.info("Disconnected Seed Vault", key = WALLET_KEY) }
                    .onFailure { Notifications.warn(it.message ?: "Seed Vault did not answer", key = WALLET_KEY) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Notifications.warn("Seed Vault did not answer: ${e.message ?: e.javaClass.simpleName}", key = WALLET_KEY)
            } finally {
                walletBusy = false
            }
        }
    }

    /**
     * Devnet mines the proof-of-work faucet until the key holds [MINE_SOL]
     * more than it does now; testnet asks `requestAirdrop` for one SOL, the
     * only faucet it has. A mining session can be stopped from the busy row,
     * and whatever landed before the stop is in the key — the finally is
     * what puts the buttons back either way, because a cancelled job never
     * reaches the line after its withContext.
     */
    fun airdrop() {
        val key = deployKey ?: return
        if (keyBusy) return
        faucetDry = false
        faucetDryReason = PowFaucet.EMPTY_REASON
        keyBusy = true
        keyBusyLabel = if (cluster.hasPowFaucet) {
            "Starting the ${cluster.display} miner…"
        } else {
            "Asking the ${cluster.display} faucet…"
        }
        val app = context.applicationContext
        miningJob = WalletWork.scope.launch {
            var stopped = false
            try {
                val result = BackgroundWork.hold(app, "mine") {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val rpc = Rpc(cluster)
                            // Every request on both branches goes through one
                            // pacer: it is the endpoint's rate limit, and it
                            // is also what makes the blocking poll inside
                            // Rpc.confirm interruptible, so Stop stops (P-20).
                            val pacer = RpcPacer()
                            if (cluster.hasPowFaucet) {
                                val start = pacer.run { rpc.getBalance(key.publicKey.base58) }
                                // A dry key cannot pay for its first claim; the
                                // wallet, when there is one, is asked for a
                                // little — through the same one transfer the
                                // Top up button uses (WalletTopUp.transfer).
                                val walletAddress = wallet?.let(Pubkey::of)
                                suspend fun fromWallet(lamports: Long) {
                                    val from = walletAddress ?: return
                                    WalletTopUp.transfer(
                                        app, cluster, rpc, pacer, from, key.publicKey, lamports,
                                        onLine = { keyBusyLabel = it },
                                    )
                                }
                                val end = PowFaucet.fund(
                                    rpc, pacer, key, target = start + MINE_SOL,
                                    walletTransfer = if (walletAddress == null) null else ::fromWallet,
                                    onLine = { keyBusyLabel = it },
                                    onProgress = { keyBusyLabel = it.describe() },
                                )
                                "Mined ${Loader.lamportsToSol(end - start)} — the deploy key holds ${Loader.lamportsToSol(end)}"
                            } else {
                                // The height first, so the wait is bounded by a blockhash
                                // that was valid when the faucet was asked.
                                val height = pacer.run { rpc.getLatestBlockhash().lastValidBlockHeight }
                                // One attempt at the faucet itself: it hangs
                                // rather than refuses when it is dry, and
                                // five paced retries would stretch that over
                                // minutes.
                                val signature = pacer.run(retry = false) {
                                    rpc.requestAirdrop(key.publicKey.base58, ONE_SOL)
                                }
                                keyBusyLabel = "Waiting for the ${cluster.display} faucet's transaction to confirm…"
                                pacer.run { rpc.confirm(signature, height) }
                                "1 SOL from the ${cluster.display} faucet landed in the deploy key"
                            }
                        }
                    }
                }
                result
                    .onSuccess { Notifications.info(it, key = WALLET_KEY) }
                    .onFailure {
                        // A dry faucet is not a refusal to retry: every claim
                        // against an empty source is rent paid for nothing
                        // (PowFaucet.FaucetEmpty), so the miner stops and the
                        // card offers the wallet instead.
                        if (it is PowFaucet.FaucetEmpty) {
                            faucetDry = true
                            // The exception's own sentence, not the constant:
                            // it names the difficulty that is empty and the
                            // one that is not, when they differ (B-06).
                            faucetDryReason = it.reason
                            Notifications.error(it.message ?: PowFaucet.EMPTY, key = WALLET_KEY)
                        } else {
                            // The message names the address: another source of
                            // SOL is the way out of every failure here, and the
                            // miner's own messages say when it is the first
                            // claim that cannot be paid for. A rate limit is
                            // said as what it is — the endpoint throttling us,
                            // not the faucet declining (P-20).
                            Notifications.error(
                                faucetFailure(cluster, it, key.publicKey.base58),
                                key = WALLET_KEY,
                            )
                        }
                    }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } finally {
                keyBusy = false
                keyBusyLabel = ""
                miningJob = null
                refresh++
                if (stopped) {
                    Notifications.info(
                        if (cluster.hasPowFaucet) {
                            "Stopped mining — what landed is in the deploy key"
                        } else {
                            "Stopped asking the ${cluster.display} faucet — anything it already sent is in the deploy key"
                        },
                        key = WALLET_KEY,
                    )
                }
            }
        }
    }

    fun returnToWallet() {
        val key = deployKey ?: return
        val to = wallet ?: return
        if (keyBusy) return
        keyBusy = true
        keyBusyLabel = "Sending the deploy key's balance to Seed Vault ${Base58.short(to)}…"
        val app = context.applicationContext
        WalletWork.scope.launch {
            val result = BackgroundWork.hold(app, "return") {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val rpc = Rpc(cluster)
                        // Re-read rather than trust the row: the row is however
                        // old the last Refresh is.
                        val balance = rpc.getBalance(key.publicKey.base58)
                        val amount = balance - Loader.LAMPORTS_PER_SIGNATURE
                        check(amount > 0) {
                            "The deploy key holds ${Loader.lamportsToSol(balance)}, not enough to pay the fee"
                        }
                        val blockhash = rpc.getLatestBlockhash()
                        val message = Message.compile(
                            feePayer = key.publicKey,
                            instructions = listOf(Loader.transfer(key.publicKey, Pubkey.of(to), amount)),
                            recentBlockhash = blockhash.blockhash,
                        )
                        val tx = Transaction.unsigned(message)
                            .withSignature(key.publicKey, key.sign(message.serialize()))
                        rpc.sendAndConfirm(tx, blockhash.lastValidBlockHeight)
                        amount
                    }
                }
            }
            keyBusy = false
            keyBusyLabel = ""
            result
                .onSuccess {
                    Notifications.info(
                        "Returned ${Loader.lamportsToSol(it)} to Seed Vault ${Base58.short(to)}",
                        key = WALLET_KEY,
                    )
                }
                .onFailure { Notifications.error("Could not return SOL: ${it.message}", key = WALLET_KEY) }
            refresh++
        }
    }

    fun reclaim(buffer: OpenBuffer) {
        if (keyBusy) return
        keyBusy = true
        // A close signed by Seed Vault starts the wallet app and can sit
        // there: the row has to say what it is waiting for, or a generic
        // "Working on devnet…" invites a second tap (QA P-18).
        keyBusyLabel = "Closing buffer ${Base58.short(buffer.address)} · Seed Vault may ask you to sign"
        val app = context.applicationContext
        WalletWork.scope.launch {
            // Success is announced by ProgramClose itself, with the amount.
            val result = BackgroundWork.hold(app, "reclaim") {
                withContext(Dispatchers.IO) { ProgramClose.closeBuffer(app, cluster, buffer) }
            }
            keyBusy = false
            keyBusyLabel = ""
            result.onFailure {
                Notifications.error(
                    it.message ?: "Could not reclaim buffer ${Base58.short(buffer.address)}",
                    key = ProgramClose.NOTIFICATION_KEY,
                )
            }
            refresh++
        }
    }

    fun askClose(program: DeployedProgram) {
        // One close at a time, and said out loud: launchClose would refuse a
        // second one silently, after the user had read the red confirm and
        // tapped through it.
        if (CloseProgress.running) {
            Notifications.warn(CLOSE_RUNNING, key = ProgramClose.NOTIFICATION_KEY)
            return
        }
        scope.launch {
            // A record says what this phone did; the cluster says what is
            // there now. The confirm needs the second — the lamports and the
            // programdata address are its facts — so ask before offering.
            val looked = withContext(Dispatchers.IO) {
                runCatching { ProgramStatus.inspect(Rpc(cluster), program.programId) }
            }
            looked
                .onFailure { Notifications.error("Could not reach ${cluster.display}: ${it.message}", key = WALLET_KEY) }
                .onSuccess { found ->
                    if (found is OnChainProgram.Deployed && ProgramStatus.canClose(found, wallet, deployKeyAddress)) {
                        closeTarget = program.name to found
                    } else {
                        Notifications.warn(
                            "${program.name} cannot be closed from this phone: " +
                                ProgramStatus.describe(found, cluster, wallet, deployKeyAddress),
                            key = WALLET_KEY,
                        )
                    }
                }
        }
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Wallet",
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4)
                .padding(bottom = MD.space4),
            verticalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            // The two accounts, in the order the caller came for. From Build
            // (the overflow, the Deploy sheet) it is the deploy key — the one
            // that spends and mines — so it opens above the fold there; from
            // Projects and Settings the wallet itself comes first, as before.
            val seedVault: @Composable (first: Boolean) -> Unit = { first ->
                SectionHeader("Seed Vault", modifier = if (first) Modifier else Modifier.padding(top = MD.space2))
                ThraggCard(modifier = Modifier.fillMaxWidth()) {
                    if (wallet != null) {
                        FactRow(label = "Address", value = wallet, trailing = { CopyChip(text = wallet) })
                        if (walletLabel != null) {
                            HairlineDivider()
                            FactRow(label = "Account", value = walletLabel)
                        }
                        HairlineDivider()
                        BalanceRow(
                            lamports = walletBalance,
                            loading = walletLoading,
                            failed = walletBalanceFailed,
                            cluster = cluster,
                            onRefresh = { refresh++ },
                        )
                    } else {
                        FactRow(
                            label = "Not connected",
                            value = "Connect to hold the upgrade authority of what you deploy, " +
                                "and to fund deploys on mainnet-beta.",
                        )
                        connectFailure?.let { said ->
                            Text(
                                text = said,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = MD.space3, end = MD.space3, bottom = MD.space2),
                            )
                        }
                    }
                    HairlineDivider()
                    ActionRow(busy = walletBusy, busyLabel = "Asking Seed Vault…") {
                        if (wallet == null) {
                            Button(onClick = { connect() }, modifier = Modifier.weight(1f)) {
                                Text("Connect Seed Vault", style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            OutlinedButton(
                                onClick = { disconnect() },
                                border = outlinedButtonEdge(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Disconnect", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

            }
            val deployKeyCard: @Composable (first: Boolean) -> Unit = { first ->
                SectionHeader("Deploy key", modifier = if (first) Modifier else Modifier.padding(top = MD.space2))
                ThraggCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Signs buffer writes so the wallet is asked once, not two hundred times. " +
                            "Funded by mining devnet's proof-of-work faucet, by the faucet on testnet, and by Seed Vault on mainnet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = MD.space3, vertical = MD.space2),
                    )
                    HairlineDivider()
                    val copyKey: (@Composable () -> Unit)? = if (deployKeyAddress != null) {
                        { CopyChip(text = deployKeyAddress) }
                    } else {
                        null
                    }
                    FactRow(
                        label = "Address",
                        value = deployKeyAddress ?: if (keyMissing) "could not create the deploy key" else "generating…",
                        trailing = copyKey,
                    )
                    HairlineDivider()
                    BalanceRow(
                        lamports = keyBalance,
                        loading = keyLoading,
                        failed = keyBalanceFailed,
                        cluster = cluster,
                        onRefresh = { refresh++ },
                    )
                    HairlineDivider()
                    // The primary way in and out of this key is the wallet the
                    // phone already has. It gets its own row, filled, above
                    // the two outlined faucet/return actions: it is the one
                    // that works on every cluster.
                    val topUpReason = WalletTopUp.entryRefusal(
                        walletAddress = wallet,
                        walletCluster = SeedVaultWallet.authorizedCluster,
                        walletBalance = walletBalance,
                        deployKey = deployKeyAddress,
                        cluster = cluster,
                    )
                    ActionRow(busy = TopUpProgress.running, busyLabel = "Seed Vault is signing the top-up…") {
                        Button(
                            onClick = { topUpOpen = true },
                            enabled = topUpReason == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Top up the deploy key", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    // A blocked control keeps its label and says why, rather
                    // than greying in silence.
                    if (topUpReason != null && !TopUpProgress.running) {
                        Text(
                            text = topUpReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = MD.space3, end = MD.space3, bottom = MD.space2),
                        )
                    }
                    HairlineDivider()
                    val canReturn = wallet != null && deployKey != null && (keyBalance ?: 0L) > RETURN_THRESHOLD
                    ActionRow(
                        busy = keyBusy,
                        busyLabel = keyBusyLabel.ifEmpty { "Working on ${cluster.display}…" },
                        onStop = miningJob?.let { job -> { job.cancel() } },
                    ) {
                        if (cluster.hasFaucet) {
                            OutlinedButton(
                                onClick = { airdrop() },
                                enabled = deployKey != null,
                                border = outlinedButtonEdge(deployKey != null),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(airdropLabel(cluster), style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Text(
                                text = "No faucet on mainnet-beta",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedButton(
                            onClick = { returnToWallet() },
                            enabled = canReturn,
                            border = outlinedButtonEdge(canReturn),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Return SOL to wallet", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                // Only where there is a faucet to work: on mainnet-beta this
                // card has no minutes-long job to protect.
                if (!unrestricted && cluster.hasFaucet) {
                    NoticeCard(
                        severity = Severity.Warn,
                        title = "Android may pause this while the screen is off",
                        body = if (cluster.hasPowFaucet) {
                            "Mining is minutes of network. Letting Thragg run unrestricted keeps it going in your pocket."
                        } else {
                            "Waiting on the faucet is minutes of network. Letting Thragg run unrestricted keeps it going in your pocket."
                        },
                        actions = {
                            ThraggChip(
                                label = "Allow in background",
                                onClick = { BackgroundWork.requestUnrestricted(context) },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
                if (faucetDry) {
                    NoticeCard(
                        severity = Severity.Warn,
                        title = "Nothing to mine on ${cluster.display}",
                        body = "$faucetDryReason. Mining it costs the deploy key rent for every claim and pays " +
                            "nothing back, so the miner stopped. Seed Vault holds SOL on ${cluster.display} — " +
                            "send some across instead.",
                        actions = {
                            ThraggChip(
                                label = "Top up from wallet",
                                onClick = { topUpOpen = true },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
            if (deployKeyFirst) {
                deployKeyCard(true)
                seedVault(false)
            } else {
                seedVault(true)
                deployKeyCard(false)
            }

            if (buffers.isNotEmpty()) {
                SectionHeader("Open buffers", modifier = Modifier.padding(top = MD.space2))
                ThraggCard(modifier = Modifier.fillMaxWidth()) {
                    buffers.forEachIndexed { index, buffer ->
                        if (index > 0) HairlineDivider()
                        FactRow(
                            label = Base58.short(buffer.address),
                            value = bufferDetail(buffer.cluster.display, buffer.programId),
                            trailing = {
                                ThraggChip(label = "Reclaim", enabled = !keyBusy, onClick = { reclaim(buffer) })
                            },
                        )
                    }
                }
                // Where the money goes, said before it moves: the rent was
                // fronted by the deploy key, so it is the deploy key that
                // gets it back (ProgramClose.bufferRecipient). Without this
                // line Reclaim read as taking 5,000 lamports of fee and
                // giving nothing back (QA r4 §12).
                Text(
                    text = ProgramClose.reclaimDestination(deployKeyAddress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MD.space3),
                )
            }

            if (programs.isNotEmpty()) {
                SectionHeader("Deployed from this phone", modifier = Modifier.padding(top = MD.space2))
                ThraggCard(modifier = Modifier.fillMaxWidth()) {
                    programs.forEachIndexed { index, program ->
                        if (index > 0) HairlineDivider()
                        DeployedRow(
                            program = program,
                            wallet = wallet,
                            deployKey = deployKeyAddress,
                            onCopy = { copyAddress(context, program.programId, "program id") },
                            onExplorer = { openExplorer(context, cluster.explorerAddress(program.programId)) },
                            onClose = { askClose(program) },
                        )
                    }
                }
            }

            CloseLog()
        }
    }

    if (topUpOpen) {
        TopUpSheet(
            state = state,
            cluster = cluster,
            shortfall = null,
            onConnect = { connect() },
            onDismiss = { topUpOpen = false },
            seedDeployKey = deployKeyAddress,
            seedWalletBalance = walletBalance,
            seedKeyBalance = keyBalance,
        )
    }

    closeTarget?.let { (name, deployed) ->
        CloseProgramConfirm(
            name = name,
            cluster = cluster,
            status = deployed,
            wallet = wallet,
            deployKey = deployKeyAddress,
            onCancel = { closeTarget = null },
            onConfirm = {
                closeTarget = null
                launchClose(context, cluster, deployed)
            },
        )
    }
}

/**
 * A balance: the label, the number or the wait or the failure under it, and
 * the Refresh chip that re-asks. The chip stays while the read is out (at
 * 38 %, disabled) so the row does not change shape between two states.
 */
@Composable
private fun BalanceRow(
    lamports: Long?,
    loading: Boolean,
    failed: Boolean,
    cluster: Cluster,
    onRefresh: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Balance",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
                    modifier = Modifier.padding(top = MD.space05),
                ) {
                    ThraggSpinner(size = 12.dp)
                    Text(
                        text = "asking ${cluster.display}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = balanceDetail(lamports, failed, cluster.display),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        ThraggChip(label = "Refresh", enabled = !loading, onClick = onRefresh)
    }
}

/**
 * A card's button row, or — while one of its actions is out on the network —
 * a spinner and a sentence in its place. Swapping the buttons for the wait
 * rather than greying them is what stops a second tap during the first
 * airdrop, and says what the wait is for. A wait that can be cut short — a
 * mining session — gets a Stop beside the sentence.
 */
@Composable
private fun ActionRow(
    busy: Boolean,
    busyLabel: String,
    onStop: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        if (busy) {
            ThraggSpinner()
            Text(
                text = busyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onStop != null) {
                OutlinedButton(
                    onClick = onStop,
                    border = outlinedButtonEdge(true),
                ) {
                    Text("Stop", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            content()
        }
    }
}

/**
 * One program this phone deployed: name, short id and the authority's role,
 * with its three actions behind the overflow. Behind a menu rather than in
 * a row of chips because one of the three is a close, and a red chip beside
 * a Copy chip is a slip waiting to happen; the menu's own confirm still
 * follows.
 */
@Composable
private fun DeployedRow(
    program: DeployedProgram,
    wallet: String?,
    deployKey: String?,
    onCopy: () -> Unit,
    onExplorer: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .padding(start = MD.space3, end = MD.space1, top = MD.space2, bottom = MD.space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = program.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Base58.short(program.programId) + " · " +
                    authorityDetail(program.authority, wallet, deployKey, whenNull = "authority unknown"),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MD.space05),
            )
        }
        Box {
            ThraggIconButton(
                icon = R.drawable.ic_ui_more_vertical,
                description = "More",
                onClick = { menu = true },
                tint = mutedIcon,
            )
            ContextMenu(
                expanded = menu,
                onDismiss = { menu = false },
                items = listOf(
                    ContextMenuItem("Copy id", onClick = onCopy),
                    ContextMenuItem("View on explorer", onClick = onExplorer),
                    ContextMenuItem("Close program", onClick = onClose),
                ),
            )
        }
    }
}

/**
 * The balance sentence: `2.41 SOL on devnet`, the failure to ask, or the
 * ellipsis of a read that has not started. Pure, tested.
 */
internal fun balanceDetail(lamports: Long?, failed: Boolean, cluster: String): String = when {
    failed -> "could not reach $cluster"
    lamports == null -> "…"
    else -> "${Loader.lamportsToSol(lamports)} on $cluster"
}

/** An open buffer's second line: where it is and what it was for. */
internal fun bufferDetail(cluster: String, programId: String?): String =
    if (programId != null) "$cluster · for ${Base58.short(programId)}" else "$cluster · left by an unfinished deploy"

/**
 * What went wrong asking for SOL, said as what it is.
 *
 * A 429 is the *endpoint* throttling this phone, not the faucet declining —
 * reported as "the testnet faucet refused" it sent QA looking for a dry
 * faucet that was fine a minute later (QA P-20). Everything else names the
 * deploy key, because another source of SOL is the way out of all of it.
 * Pure, tested.
 */
internal fun faucetFailure(cluster: Cluster, error: Throwable, address: String): String {
    val rpc = error as? RpcException
    val throttled = rpc?.httpStatus == 429 || rpc?.isTransient == true
    return if (throttled) {
        "${cluster.display} is rate-limiting Thragg rather than refusing the request — " +
            "wait a minute and try again, or top up from Seed Vault"
    } else {
        "The ${cluster.display} faucet refused: ${error.message} — try again in a " +
            "minute, top up from Seed Vault, or send SOL to ${Base58.short(address)} by hand"
    }
}

/**
 * The Airdrop button's label: devnet mines, and mines more than a faucet
 * gives, because it can; testnet asks its faucet for the one SOL it allows.
 */
internal fun airdropLabel(cluster: Cluster): String =
    if (cluster.hasPowFaucet) "Mine ${Loader.lamportsToSol(MINE_SOL)}" else "Airdrop 1 SOL"

/**
 * The scope Mine, Reclaim and Return run on.
 *
 * NOT THE SHEET'S. Measured in QA 2026-09-09: all three ran on the
 * composition's `rememberCoroutineScope`, so a scrim tap during a two-minute
 * mining session — or during a Seed Vault prompt — cancelled work that had
 * already spent SOL. They now run here, inside [BackgroundWork.hold], for the
 * same reason a close does (ProgramSheet.kt, `CloseProgress`): the foreground
 * service and the wake lock are held for the length of the work, and the
 * sheet's own state is only where the *progress* is drawn. Stop still works —
 * the job is the sheet's to cancel, it is just no longer the sheet's to lose.
 */
internal object WalletWork {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}

/** Wallet notifications replace each other rather than stacking. */
private const val WALLET_KEY = "chain.wallet"

/** What "Airdrop 1 SOL" asks the testnet faucet for. */
private const val ONE_SOL = 1_000_000_000L

/** What one tap of Mine adds on devnet: about a deploy's worth, a minute or two on the public endpoint. */
internal const val MINE_SOL = 5_000_000_000L

/** Below this (0.001 SOL) a return is a fee for nothing; the button is off. */
private const val RETURN_THRESHOLD = 1_000_000L
