package to.eyed.thragg.ui.shell.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.solana.chain.BackgroundWork
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.DeployKey
import to.eyed.thragg.solana.chain.Loader
import to.eyed.thragg.solana.chain.Pubkey
import to.eyed.thragg.solana.chain.Rpc
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.solana.chain.WalletTopUp
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.ThraggCard
import to.eyed.thragg.ui.components.ThraggChip
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.projects.Message
import to.eyed.thragg.ui.shell.projects.SheetButtons
import to.eyed.thragg.ui.shell.projects.SheetTextField
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.workspace.Notifications

/**
 * "Top up the deploy key": how much, from the wallet the phone already holds.
 *
 * TWO DOORS, ONE SHEET. The Wallet sheet reaches it because that is where the
 * two balances are; the Deploy sheet reaches it at the moment the user is
 * actually blocked, carrying the shortfall it just computed, so the first
 * chip is the answer to the question the previous sheet asked. Both compose
 * exactly this, and both hand the work to [launchTopUp], so there is one
 * refusal matrix ([WalletTopUp.refusal]) and one wording.
 *
 * THE CHIPS ARE THE FORM, the field is the escape hatch. Four fixed amounts
 * cover what a deploy costs on any cluster this app talks to, and a person
 * choosing between 0.5 and 5 SOL with a thumb should not have to open a
 * keyboard for it; the field is there for the fifth case and is pinned at
 * the bottom, over the IME, as every field in this app is (SheetScaffold).
 * Tapping a chip fills the field rather than replacing it, so what is about
 * to be sent is always readable in one place.
 *
 * A BLOCKED BUTTON KEEPS ITS LABEL. When it cannot go, the reason is printed
 * under the field in [Message]'s error ink rather than the button silently
 * greying — except for the one refusal that has an action, no wallet, where
 * the button becomes Connect and runs the Wallet sheet's own connect path.
 *
 * MAINNET GETS THE SECOND CONFIRM, in DeploySheet's shape and register: a
 * dialog that names the sum, with its confirm drawn in `error`. Devnet SOL is
 * free and one tap is the right price for it.
 *
 * THE TOP-UP OUTLIVES THE SHEET. It runs on [TopUpProgress]'s own scope
 * inside [BackgroundWork.hold], because between the tap and the signature
 * there is a wallet app on screen and a person reading it: dismissing this
 * sheet, or Android caching the process while Seed Vault has the foreground,
 * must not cancel a transfer the user already approved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TopUpSheet(
    state: ShellState,
    cluster: Cluster,
    /** The Deploy sheet's gap, when the user came from there. */
    shortfall: Long?,
    /** The Wallet sheet's connect path, so a disconnected wallet has one tap out. */
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    /** The Wallet sheet's rows, so the picker opens with numbers rather than an ellipsis. */
    seedDeployKey: String? = null,
    seedWalletBalance: Long? = null,
    seedKeyBalance: Long? = null,
) {
    val context = LocalContext.current
    val wallet = SeedVaultWallet.address
    // Seeded from the caller's rows, then re-read: the caller's numbers are
    // however old its last Refresh is, and this is the sheet that decides
    // whether the wallet can cover the amount. The deploy key is GENERATED
    // when it does not exist — this is a sheet whose whole purpose is to put
    // SOL in it, so a missing key is the thing to fix, not to report.
    var deployKey by remember { mutableStateOf(seedDeployKey) }
    var walletBalance by remember { mutableStateOf(seedWalletBalance) }
    var keyBalance by remember { mutableStateOf(seedKeyBalance) }
    LaunchedEffect(wallet, cluster) {
        val read = withContext(Dispatchers.IO) {
            val rpc = Rpc(cluster)
            val key = deployKey ?: runCatching { DeployKey.get(context).publicKey.base58 }.getOrNull()
            val held = wallet?.let { runCatching { rpc.getBalance(it) }.getOrNull() }
            val keyHeld = key?.let { runCatching { rpc.getBalance(it) }.getOrNull() }
            Triple(key, held, keyHeld)
        }
        deployKey = read.first
        if (read.second != null) walletBalance = read.second
        if (read.third != null) keyBalance = read.third
    }
    val enough = shortfall?.let { WalletTopUp.enoughForDeploy(it) }
    var amountText by remember {
        mutableStateOf(WalletTopUp.solText(enough ?: WalletTopUp.PRESETS.first()))
    }
    var confirmAsk by remember { mutableStateOf(false) }
    val amount = WalletTopUp.parseAmount(amountText)
    val refusal = WalletTopUp.refusal(
        amount = amount,
        walletAddress = wallet,
        walletCluster = SeedVaultWallet.authorizedCluster,
        walletBalance = walletBalance,
        deployKey = deployKey,
        cluster = cluster,
    )
    val needsConnect = wallet == null
    val start = {
        val key = deployKey
        if (amount != null && key != null) launchTopUp(context, cluster, amount, key)
        onDismiss()
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Top up the deploy key",
        containerColor = MaterialTheme.colorScheme.background,
        field = {
            SheetTextField(
                value = amountText,
                onValueChange = { amountText = it },
                placeholder = "Amount in SOL",
            )
        },
        actions = {
            SheetButtons(
                cancelLabel = "Cancel",
                onCancel = onDismiss,
                confirmLabel = if (needsConnect) "Connect Seed Vault" else "Top up",
                confirmEnabled = needsConnect || refusal == null,
                onConfirm = {
                    when {
                        needsConnect -> {
                            onDismiss()
                            onConnect()
                        }
                        WalletTopUp.needsConfirm(cluster) -> confirmAsk = true
                        else -> start()
                    }
                },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4)
                .padding(bottom = MD.space2),
            verticalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            Text(
                text = topUpBlurb(cluster),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
                verticalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                if (enough != null) {
                    AmountChip(
                        label = "Enough for this deploy · ${Loader.lamportsToSol(enough)}",
                        lamports = enough,
                        selected = amount == enough,
                        onPick = { amountText = WalletTopUp.solText(it) },
                    )
                }
                for (preset in WalletTopUp.PRESETS) {
                    AmountChip(
                        label = Loader.lamportsToSol(preset),
                        lamports = preset,
                        selected = amount == preset,
                        onPick = { amountText = WalletTopUp.solText(it) },
                    )
                }
            }
            ThraggCard(modifier = Modifier.fillMaxWidth()) {
                FactRow(
                    label = "Seed Vault",
                    value = if (wallet == null) {
                        "not connected"
                    } else {
                        "${Base58.short(wallet)} · ${balanceDetail(walletBalance, false, cluster.display)}"
                    },
                )
                HairlineDivider()
                val key = deployKey
                FactRow(
                    label = "Deploy key",
                    value = if (key == null) {
                        "not created yet"
                    } else {
                        "${Base58.short(key)} · ${WalletTopUp.afterDetail(keyBalance, amount)}"
                    },
                )
            }
            if (refusal != null) Message(text = refusal, isError = amount != null && walletBalance != null)
        }
    }

    val confirmKey = deployKey
    if (confirmAsk && amount != null && confirmKey != null) {
        AlertDialog(
            onDismissRequest = { confirmAsk = false },
            tonalElevation = 0.dp,
            title = { Text("Send ${Loader.lamportsToSol(amount)} to the deploy key on ${cluster.display}?") },
            text = { Text(WalletTopUp.confirmBody(amount, confirmKey)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAsk = false
                        start()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Top up") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAsk = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One preset. Selection is the chip's tint, the app's 14 % wash — a preset
 * that has been chosen and the amount in the field are the same fact, so the
 * chip follows the field rather than holding its own state.
 */
@Composable
private fun AmountChip(label: String, lamports: Long, selected: Boolean, onPick: (Long) -> Unit) {
    ThraggChip(
        label = label,
        onClick = { onPick(lamports) },
        tint = if (selected) MaterialTheme.colorScheme.primary else null,
    )
}

/**
 * The sheet's one sentence: what this key is for, and — off mainnet — that
 * the SOL is free anyway. Pure, tested.
 */
internal fun topUpBlurb(cluster: Cluster): String =
    "Seed Vault signs one transfer to the deploy key, which pays for buffer writes and deploys. " +
        if (cluster.isMainnet) {
            "This is mainnet-beta: real SOL. What is left over can be sent back from the Wallet sheet."
        } else {
            "It can be sent back from the Wallet sheet at any time."
        }

/** What a second top-up is told while the first is still out. */
internal const val TOP_UP_RUNNING = "A top-up is already running — wait for Seed Vault to answer"

/**
 * The one running top-up, on a scope of its own.
 *
 * [version] is what the Wallet sheet's balance effects key on, the same way
 * the close flow drives its rows off `DeployedPrograms.version`: nothing here
 * needs the sheet that started it to still exist.
 */
internal object TopUpProgress {
    var running: Boolean by mutableStateOf(false)
    var version: Int by mutableIntStateOf(0)
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}

/**
 * Run the transfer on [TopUpProgress]'s scope, holding the foreground service
 * and the CPU for its length ([BackgroundWork.hold]). The application context
 * is what the job keeps: it outlives the sheet, and on the way it hands the
 * screen to the wallet app.
 */
internal fun launchTopUp(context: Context, cluster: Cluster, amount: Long, deployKey: String) {
    if (TopUpProgress.running) {
        Notifications.warn(TOP_UP_RUNNING, key = WalletTopUp.NOTIFICATION_KEY)
        return
    }
    val to = Pubkey.ofOrNull(deployKey)
    if (to == null) {
        Notifications.error("The deploy key address is not valid — reopen Wallet", key = WalletTopUp.NOTIFICATION_KEY)
        return
    }
    val app = context.applicationContext
    TopUpProgress.running = true
    TopUpProgress.scope.launch {
        val result = runCatching {
            BackgroundWork.hold(app, "topup") {
                withContext(Dispatchers.IO) {
                    WalletTopUp.send(app, cluster, amount, to) { line ->
                        Notifications.info(line, key = WalletTopUp.NOTIFICATION_KEY)
                    }
                }
            }
        }
        TopUpProgress.running = false
        TopUpProgress.version++
        result
            .onSuccess {
                Notifications.info(
                    WalletTopUp.landedDetail(it.amount, it.keyBalance, cluster),
                    key = WalletTopUp.NOTIFICATION_KEY,
                )
            }
            .onFailure {
                Notifications.error(
                    "Could not top up the deploy key: ${WalletTopUp.readable(it)}",
                    key = WalletTopUp.NOTIFICATION_KEY,
                )
            }
    }
}
