package to.eyed.thragg.ui.shell.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.Loader
import to.eyed.thragg.solana.chain.OnChainProgram
import to.eyed.thragg.solana.chain.ProgramClose
import to.eyed.thragg.solana.chain.ProgramStatus
import to.eyed.thragg.ui.components.CopyChip
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.SeekerCard
import to.eyed.thragg.ui.components.SeekerSpinner
import to.eyed.thragg.ui.components.outlinedButtonEdge
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.MonoSmall
import to.eyed.thragg.ui.workspace.Notifications

/**
 * The Program sheet: what the cluster says about this project's program id,
 * and the one irreversible thing that can be done about it.
 *
 * Every fact here was fetched by SettingsScreen's row effect and is handed in
 * rather than re-read: the sheet is a bigger view of the row, not a second
 * asker, so the two can never disagree about whether the program is
 * deployed. What the sheet adds is the full id (the row prints `7NJd…4kQz`),
 * the authority named by its role — "this wallet" or "deploy key" is the
 * whole answer to "can I upgrade it", a short address is not — the rent a
 * close would return, and the slot of the last deploy.
 *
 * CLOSING IS THE ONLY VERB, AND IT IS RED. A close returns the programdata
 * account's lamports and leaves the 36-byte Program account behind as a
 * tombstone the loader will never let anyone deploy to again (ProgramStatus.kt
 * on `Closed`). So the button is offered only when this phone holds the
 * authority ([ProgramStatus.canClose]), it is drawn in `error`, and it opens
 * [CloseProgramConfirm], whose body says the id, where the SOL goes, and that
 * the id is spent — on mainnet with the extra sentence. Progress then shows
 * *in the sheet* as a small log, because a close is three round trips and a
 * spinner over the button would leave the user with nothing to read while
 * they wait for the one thing they cannot take back.
 *
 * The close machinery — the progress holder, the confirm, the launcher, the
 * log — is `internal` because the Wallet sheet's "Deployed from this phone"
 * card closes programs through exactly the same dialog and log; two copies
 * of a destructive confirm are two chances for the wording to drift.
 *
 * A CLOSE OUTLIVES THE SHEET. It runs on [CloseProgress]'s own scope, not the
 * sheet's: a scrim tap or a back press mid-close must not cancel a signed
 * transaction that may already be on its way to the node, and must not lose
 * the record removal and the notification that follow it. The row's
 * re-inspect is driven by `DeployedPrograms.version`, which the close bumps
 * when it lands, so nothing here needs the sheet to still be open.
 */
@Composable
internal fun ProgramSheet(
    state: ShellState,
    cluster: Cluster,
    /** The program's module name — the sheet's title. */
    name: String,
    programId: String?,
    status: OnChainProgram?,
    checking: Boolean,
    unreachable: Boolean,
    wallet: String?,
    deployKey: String?,
    onDismiss: () -> Unit,
    /** Anchor.toml says localnet: the cluster was never asked, and the status row says why. */
    localnet: Boolean = false,
) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val deployed = status as? OnChainProgram.Deployed
    val closable = deployed != null && !CloseProgress.running &&
        ProgramStatus.canClose(deployed, wallet, deployKey)

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = name,
        // Cards in the body, so the sheet takes the canvas (SheetScaffold.kt).
        containerColor = MaterialTheme.colorScheme.background,
        actions = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                if (programId != null) {
                    OutlinedButton(
                        onClick = { openExplorer(context, cluster.explorerAddress(programId)) },
                        border = outlinedButtonEdge(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("View on explorer", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (closable) {
                    DestructiveButton(
                        label = "Close program and reclaim rent",
                        onClick = { confirm = true },
                    )
                }
            }
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
            SeekerCard(modifier = Modifier.fillMaxWidth()) {
                val copy: (@Composable () -> Unit)? = if (programId != null) {
                    { CopyChip(text = programId) }
                } else {
                    null
                }
                FactRow(label = "Program id", value = programId ?: "no id yet", trailing = copy)
                HairlineDivider()
                FactRow(label = "Cluster", value = cluster.display)
                HairlineDivider()
                FactRow(
                    label = "Status",
                    value = programRowDescription(
                        checking = checking,
                        unreachable = unreachable,
                        described = status?.let { ProgramStatus.describe(it, cluster, wallet, deployKey) },
                        cluster = cluster.display,
                        localnet = localnet,
                    ) ?: "no id yet — the first build creates the program keypair",
                )
                if (deployed != null) {
                    HairlineDivider()
                    FactRow(
                        label = "Upgrade authority",
                        value = authorityDetail(deployed.authority, wallet, deployKey, whenNull = "none · immutable"),
                    )
                    HairlineDivider()
                    FactRow(label = "Reclaimable rent", value = Loader.lamportsToSol(deployed.reclaimable))
                    HairlineDivider()
                    FactRow(label = "Last deploy slot", value = deployed.slot.toString())
                }
            }
            CloseLog()
        }
    }

    if (confirm && deployed != null) {
        CloseProgramConfirm(
            name = name,
            cluster = cluster,
            status = deployed,
            wallet = wallet,
            deployKey = deployKey,
            onCancel = { confirm = false },
            onConfirm = {
                confirm = false
                launchClose(context, cluster, deployed)
            },
        )
    }
}

/**
 * A label over a value — the statement row of every chain sheet.
 *
 * Not SettingsScreen's `LinkRow` with a null click: that row puts its readout
 * in a 168dp trailing column, which is right for `devnet` and wrong for a
 * 44-character base58 id. Here the value goes UNDER the label at full width
 * and may take two lines, and [trailing] is the one control a fact can carry
 * — a Copy chip, a Refresh chip.
 */
@Composable
internal fun FactRow(
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
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
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MD.space05),
            )
        }
        trailing?.invoke()
    }
}

/**
 * The filled `error` button every irreversible chain action is. Stock M3
 * `Button` with the container swapped, the same treatment ProjectsSheet's
 * Delete gets, so a red button means the same thing everywhere.
 */
@Composable
internal fun DestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * What the running — or last — close has said so far, and the scope it runs
 * on. One for the process, like BuildRunner's, so a sheet that was dismissed
 * mid-close neither cancels it nor loses its log: reopen the sheet and the
 * lines are there. Lines land from the IO thread ([ProgramClose.close]'s
 * `onLine`), which a snapshot list takes without a hop to Main; [running]
 * is what hides the red button while it is true, and there is only ever one
 * close at a time.
 */
internal object CloseProgress {
    val lines = mutableStateListOf<String>()
    var running: Boolean by mutableStateOf(false)

    /** Process-level, so a dismissed sheet does not cancel a signed transaction. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}

/**
 * The red confirm. Title names the program and the cluster; body names the
 * id, the SOL and who receives it, and says the id is spent — the wording is
 * [closeProgramBody]'s, tested, because this is the one dialog in the app
 * whose sentence must not soften in a refactor.
 */
@Composable
internal fun CloseProgramConfirm(
    name: String,
    cluster: Cluster,
    status: OnChainProgram.Deployed,
    wallet: String?,
    deployKey: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        tonalElevation = 0.dp,
        title = { Text("Close $name on ${cluster.display}?") },
        text = {
            Text(
                closeProgramBody(
                    programId = status.programId,
                    reclaimed = Loader.lamportsToSol(status.reclaimable),
                    recipient = closeRecipient(wallet, deployKey),
                    mainnet = cluster.isMainnet,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Close and reclaim") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

/**
 * Run the close on IO, on [CloseProgress]'s scope, feeding its log. The
 * success notification and the record removal are [ProgramClose.close]'s
 * own; this adds the failure to both the log and the notification tray, so
 * a sheet that was dismissed mid-close still tells the user what happened.
 * The application context is what the job holds, since it may outlive the
 * activity that launched it.
 */
internal fun launchClose(
    context: Context,
    cluster: Cluster,
    status: OnChainProgram.Deployed,
) {
    val progress = CloseProgress
    if (progress.running) {
        // The buttons that lead here hide while a close runs, but a confirm
        // that was already up does not: a second "Close and reclaim" must
        // say why nothing happened rather than do nothing.
        Notifications.warn(CLOSE_RUNNING, key = ProgramClose.NOTIFICATION_KEY)
        return
    }
    progress.lines.clear()
    progress.running = true
    val app = context.applicationContext
    progress.scope.launch {
        val result = withContext(Dispatchers.IO) {
            ProgramClose.close(app, cluster, status) { line -> progress.lines.add(line) }
        }
        progress.running = false
        result.onFailure {
            val message = it.message ?: "Close failed"
            progress.lines.add(message)
            Notifications.error(message, key = ProgramClose.NOTIFICATION_KEY)
        }
    }
}

/** What a second close is told while the first is still out. */
internal const val CLOSE_RUNNING = "A close is already running — wait for it to finish"

/** The small log a close writes into the sheet. Nothing until there is a line. */
@Composable
internal fun CloseLog() {
    val progress = CloseProgress
    if (progress.lines.isEmpty() && !progress.running) return
    SeekerCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MD.space3),
            verticalArrangement = Arrangement.spacedBy(MD.space1),
        ) {
            for (line in progress.lines) {
                Text(
                    text = line,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress.running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
                ) {
                    SeekerSpinner(size = 12.dp)
                    Text(
                        text = "Closing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The authority row's value: the short address and its ROLE, because "can I
 * upgrade this" is answered by the role and not by the address. [whenNull]
 * is the caller's word for no authority — `immutable` on chain, `unknown` in
 * a record. Pure, tested.
 */
internal fun authorityDetail(
    authority: String?,
    wallet: String?,
    deployKey: String?,
    whenNull: String,
): String = when {
    authority == null -> whenNull
    wallet != null && authority == wallet -> "${Base58.short(authority)} · this wallet"
    deployKey != null && authority == deployKey -> "${Base58.short(authority)} · deploy key"
    else -> "${Base58.short(authority)} · someone else"
}

/**
 * Who a close pays: the same rule as `ProgramClose.recipientFor` — the
 * connected wallet, else the deploy key — spelled for a sentence.
 */
internal fun closeRecipient(wallet: String?, deployKey: String?): String = when {
    wallet != null -> "Seed Vault ${Base58.short(wallet)}"
    deployKey != null -> "the deploy key ${Base58.short(deployKey)}"
    else -> "this phone"
}

/**
 * The confirm's body. Three facts and the warning, in that order; on mainnet
 * one more sentence between them, because "real money" is the one thing the
 * cluster name alone does not say to everyone.
 */
internal fun closeProgramBody(
    programId: String,
    reclaimed: String,
    recipient: String,
    mainnet: Boolean,
): String = buildString {
    append("Program ").append(programId).append(" will be closed and ")
    append(reclaimed).append(" returned to ").append(recipient).append(". ")
    if (mainnet) append("This is mainnet-beta. ")
    append("This cannot be undone. The program id can never be deployed again.")
}

/** Open [url] in whatever handles https; say so when nothing does. */
internal fun openExplorer(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Notifications.error("No browser on this device to open $url", key = "chain.explorer") }
}

/** Copy [text] and say what was copied, for the rows that are not a [CopyChip]. */
internal fun copyAddress(context: Context, text: String, what: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(what, text))
    Notifications.info("Copied the $what", key = "chain.copy")
}
