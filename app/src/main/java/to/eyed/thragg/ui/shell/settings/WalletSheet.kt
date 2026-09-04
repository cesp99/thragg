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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.R
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.ChainSigning
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
import to.eyed.thragg.solana.chain.RpcPacer
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.solana.chain.Transaction
import to.eyed.thragg.ui.components.CopyChip
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.SectionHeader
import to.eyed.thragg.ui.components.SeekerCard
import to.eyed.thragg.ui.components.SeekerChip
import to.eyed.thragg.ui.components.SeekerSpinner
import to.eyed.thragg.ui.components.outlinedButtonEdge
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIconButton
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallet = SeedVaultWallet.address
    val walletLabel = SeedVaultWallet.label
    // Bumped by Refresh and by every action that moved SOL; the balance and
    // record effects key on it.
    var refresh by remember { mutableIntStateOf(0) }

    // --- Seed Vault ------------------------------------------------------
    var walletBalance by remember { mutableStateOf<Long?>(null) }
    var walletBalanceFailed by remember { mutableStateOf(false) }
    var walletLoading by remember { mutableStateOf(false) }
    var walletBusy by remember { mutableStateOf(false) }
    LaunchedEffect(wallet, cluster, refresh) {
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
    LaunchedEffect(cluster, refresh) {
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
        scope.launch {
            try {
                SeedVaultWallet.connect(context, cluster)
                    .onSuccess { Notifications.info("Connected Seed Vault ${Base58.short(it)}", key = WALLET_KEY) }
                    .onFailure { Notifications.error(it.message ?: "Seed Vault did not answer", key = WALLET_KEY) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Notifications.error("Seed Vault did not answer: ${e.message ?: e.javaClass.simpleName}", key = WALLET_KEY)
            } finally {
                walletBusy = false
            }
        }
    }

    fun disconnect() {
        if (walletBusy) return
        walletBusy = true
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
        keyBusy = true
        keyBusyLabel = "Asking the ${cluster.display} faucet…"
        miningJob = scope.launch {
            var stopped = false
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val rpc = Rpc(cluster)
                        if (cluster.hasPowFaucet) {
                            val pacer = RpcPacer()
                            val start = pacer.run { rpc.getBalance(key.publicKey.base58) }
                            // A dry key cannot pay for its first claim; the
                            // wallet, when there is one, is asked for a little.
                            val walletAddress = wallet?.let(Pubkey::of)
                            suspend fun fromWallet(lamports: Long) {
                                val from = walletAddress ?: return
                                ChainSigning.signAndSend(
                                    context, cluster, rpc, pacer, from,
                                    listOf(Loader.transfer(from, key.publicKey, lamports)),
                                    local = emptyList(), wallet = from,
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
                            val height = rpc.getLatestBlockhash().lastValidBlockHeight
                            val signature = rpc.requestAirdrop(key.publicKey.base58, ONE_SOL)
                            rpc.confirm(signature, height)
                            "1 SOL from the ${cluster.display} faucet landed in the deploy key"
                        }
                    }
                }
                result
                    .onSuccess { Notifications.info(it, key = WALLET_KEY) }
                    .onFailure {
                        // The message names the address: another source of
                        // SOL is the way out of every failure here, and the
                        // miner's own messages say when it is the first
                        // claim that cannot be paid for.
                        Notifications.error(
                            "The ${cluster.display} faucet refused: ${it.message} — try again in a " +
                                "minute, or send SOL to ${Base58.short(key.publicKey.base58)} by hand",
                            key = WALLET_KEY,
                        )
                    }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } finally {
                keyBusy = false
                miningJob = null
                refresh++
                if (stopped) Notifications.info("Stopped mining — what landed is in the deploy key", key = WALLET_KEY)
            }
        }
    }

    fun returnToWallet() {
        val key = deployKey ?: return
        val to = wallet ?: return
        if (keyBusy) return
        keyBusy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
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
            keyBusy = false
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
        scope.launch {
            // Success is announced by ProgramClose itself, with the amount.
            val result = withContext(Dispatchers.IO) { ProgramClose.closeBuffer(context, cluster, buffer) }
            keyBusy = false
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
            SectionHeader("Seed Vault")
            SeekerCard(modifier = Modifier.fillMaxWidth()) {
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

            SectionHeader("Deploy key", modifier = Modifier.padding(top = MD.space2))
            SeekerCard(modifier = Modifier.fillMaxWidth()) {
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

            if (buffers.isNotEmpty()) {
                SectionHeader("Open buffers", modifier = Modifier.padding(top = MD.space2))
                SeekerCard(modifier = Modifier.fillMaxWidth()) {
                    buffers.forEachIndexed { index, buffer ->
                        if (index > 0) HairlineDivider()
                        FactRow(
                            label = Base58.short(buffer.address),
                            value = bufferDetail(buffer.cluster.display, buffer.programId),
                            trailing = {
                                SeekerChip(label = "Reclaim", enabled = !keyBusy, onClick = { reclaim(buffer) })
                            },
                        )
                    }
                }
            }

            if (programs.isNotEmpty()) {
                SectionHeader("Deployed from this phone", modifier = Modifier.padding(top = MD.space2))
                SeekerCard(modifier = Modifier.fillMaxWidth()) {
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
                    SeekerSpinner(size = 12.dp)
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
        SeekerChip(label = "Refresh", enabled = !loading, onClick = onRefresh)
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
            SeekerSpinner()
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
            SeekerIconButton(
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
 * The Airdrop button's label: devnet mines, and mines more than a faucet
 * gives, because it can; testnet asks its faucet for the one SOL it allows.
 */
internal fun airdropLabel(cluster: Cluster): String =
    if (cluster.hasPowFaucet) "Mine ${Loader.lamportsToSol(MINE_SOL)}" else "Airdrop 1 SOL"

/** Wallet notifications replace each other rather than stacking. */
private const val WALLET_KEY = "chain.wallet"

/** What "Airdrop 1 SOL" asks the testnet faucet for. */
private const val ONE_SOL = 1_000_000_000L

/** What one tap of Mine adds on devnet: about a deploy's worth, a minute or two on the public endpoint. */
internal const val MINE_SOL = 5_000_000_000L

/** Below this (0.001 SOL) a return is a fee for nothing; the button is off. */
private const val RETURN_THRESHOLD = 1_000_000L
