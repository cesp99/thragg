package to.eyed.thragg.ui.shell.build

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.thragg.solana.build.ArtifactFreshness
import to.eyed.thragg.solana.build.BuildAction
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.solana.build.ProgramTarget
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.BackgroundWork
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.ClusterStore
import to.eyed.thragg.solana.chain.DeployKey
import to.eyed.thragg.solana.chain.Loader
import to.eyed.thragg.solana.chain.OnChainProgram
import to.eyed.thragg.solana.chain.ProgramIds
import to.eyed.thragg.solana.chain.ProgramStatus
import to.eyed.thragg.solana.chain.Rpc
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.solana.toolchain.formatBytes
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.SeekerCard
import to.eyed.thragg.ui.components.SeekerChip
import to.eyed.thragg.ui.components.Severity
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.projects.SheetButtons
import to.eyed.thragg.ui.shell.settings.FactRow
import to.eyed.thragg.ui.theme.MD

/**
 * The Deploy confirm: everything a deploy is about to do, on one sheet,
 * before it does any of it.
 *
 * Deploy is the one button in the app that spends. On devnet it spends
 * faucet SOL and a program id; on mainnet it spends money, and a mistake —
 * the wrong cluster, a stale artifact, an id that `declare_id!` does not
 * agree with — is not undone by a second deploy. So the overflow's Deploy
 * item no longer starts the run; it opens this, and this is a SUMMARY, not a
 * form: six facts, the notices that apply, Cancel and Deploy. Nothing here
 * can be edited, because everything here is set elsewhere (the Cluster sheet,
 * the Wallet sheet, a build) and a sheet that let the user change the cluster
 * on the way past would be the place the wrong-cluster deploy came from.
 *
 * THE FACTS ARE FETCHED, in one IO pass when the sheet opens: the id resolved
 * the way the deployer will resolve it (ProgramIds.kt), the artifact's size,
 * the deploy key's balance and what the cluster already has at the id —
 * which decides whether this is a fresh deploy or an upgrade, and therefore
 * the estimate. The estimate is [Loader.estimateDeploy]'s formula, marked
 * `~` because the deployer asks the RPC for the real rent figures; what the
 * sheet promises is the shape of the cost, and the second line says how much
 * of it comes back when the buffer is drained.
 *
 * THE BUTTON WAITS FOR THE FACTS. Until the IO pass is back, and whenever the
 * cluster did not answer, Deploy is off: what the cluster has at the id is
 * the one fact that decides fresh-or-upgrade and every refusal below, and a
 * button that is live before it is known is a button that deploys blind. A
 * `declare_id!` that disagrees with the keypair is a refusal too, not just a
 * notice — the program it would produce rejects every instruction.
 *
 * MAINNET GETS A SECOND, RED CONFIRM. One tap on a sheet is the right price
 * for a devnet deploy and the wrong price for real money; the dialog names
 * the program, the cluster and the SOL, and its confirm is drawn in `error`.
 * Confirming dismisses the sheet and calls exactly what the overflow used to
 * call — `BuildRunner.start(context, state, BuildAction.Deploy)` — so the run,
 * the log and the foreground service are unchanged by this sheet's existence.
 *
 * [DeployPrompt] is the one bit of state BuildScreen needs: its overflow sets
 * `open = true`, and BuildScreen composes this sheet while it is.
 */
object DeployPrompt {
    /** Whether the Deploy sheet is up. Set by BuildScreen's overflow; cleared on dismiss. */
    var open: Boolean by mutableStateOf(false)
}

/** What the sheet learned on IO; null until it has. */
private class DeployFacts(
    val resolved: ProgramIds.Resolved,
    /** The artifact's size, or null when there is no file. */
    val artifactBytes: Long?,
    /** The deploy key's address, or null when none has been generated yet. */
    val deployKey: String?,
    /** The deploy key's balance; null when there is no key, a failure when the RPC did not answer. */
    val keyBalance: Result<Long>?,
    /** What the cluster has at the id; null when there is no id, a failure when the RPC did not answer. */
    val status: Result<OnChainProgram>?,
    val estimate: Loader.CostEstimate?,
)

@Composable
internal fun DeploySheet(state: ShellState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // The system's battery dialog returns no result, so the answer is read
    // again every time this activity comes back to the front — which is
    // exactly when the dialog has gone.
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
    val root = state.project?.rootPath
    val layout = BuildRunner.layout?.takeIf { it.root == root }
    val program = layout?.primary
    val clusterVersion = ClusterStore.version
    // Read on IO — Anchor.toml, or the prefs the first time — and null until
    // it has landed; the facts pass below waits for it, because every one of
    // its questions is asked of this cluster.
    val cluster by produceState<Cluster?>(initialValue = null, root, clusterVersion) {
        value = null
        value = withContext(Dispatchers.IO) { ClusterStore.of(context, root) }
    }
    val where = cluster?.display ?: "…"
    val freshness = BuildRunner.freshness
    val wallet = SeedVaultWallet.address
    var facts by remember { mutableStateOf<DeployFacts?>(null) }
    var mainnetAsk by remember { mutableStateOf(false) }
    // Bumped by "Try again" on the could-not-reach notice.
    var retry by remember { mutableIntStateOf(0) }

    LaunchedEffect(root, program, cluster, freshness, retry) {
        facts = null
        val known = cluster
        if (root == null || program == null || known == null) return@LaunchedEffect
        facts = withContext(Dispatchers.IO) { gather(context, root, program, known) }
    }

    val artifactName = program?.artifactPath?.substringAfterLast('/') ?: "the artifact"
    val status = facts?.status?.getOrNull()
    val deployed = status as? OnChainProgram.Deployed
    val closed = status is OnChainProgram.Closed
    // An authority this phone does not hold — or none at all — is an upgrade
    // that cannot be signed here, whatever the button says.
    val foreign = deployed != null && (
        deployed.authority == null ||
            (deployed.authority != wallet && deployed.authority != facts?.deployKey)
        )
    val mainnet = cluster?.isMainnet == true
    val mainnetUnfunded = mainnet && wallet == null
    val unreachable = facts?.status?.isFailure == true
    val disagree = facts?.resolved?.disagree == true
    val canDeploy = program != null && cluster != null &&
        facts != null && !unreachable && !disagree &&
        freshness !is ArtifactFreshness.Missing &&
        !mainnetUnfunded && !closed && !foreign &&
        !BuildRunner.isRunning

    val deploy = {
        onDismiss()
        BuildRunner.start(context, state, BuildAction.Deploy)
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Deploy",
        containerColor = MaterialTheme.colorScheme.background,
        actions = {
            SheetButtons(
                cancelLabel = "Cancel",
                onCancel = onDismiss,
                confirmLabel = "Deploy",
                confirmEnabled = canDeploy,
                onConfirm = { if (mainnet) mainnetAsk = true else deploy() },
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
            // The notices first, above the facts: each one is a reason the
            // Deploy button is off or a reason to press Cancel, and it should
            // be read before the number under it.
            if (program == null) {
                NoticeCard(
                    severity = Severity.Warn,
                    title = "Nothing here to deploy",
                    body = "No Anchor.toml and no Solana crate in this project.",
                )
            }
            if (disagree) {
                NoticeCard(
                    severity = Severity.Error,
                    title = "Ids disagree",
                    body = ProgramIds.DISAGREE,
                )
            }
            if (unreachable) {
                NoticeCard(
                    severity = Severity.Error,
                    title = "Could not reach $where",
                    body = "A deploy has to know what $where already has at this id before it starts.",
                    actions = { SeekerChip(label = "Try again", onClick = { retry++ }) },
                )
            }
            // Doze suspends the network for an app the user has not exempted,
            // and a deploy is minutes of it — the one thing a foreground
            // service cannot fix (solana/chain/BackgroundWork.kt). Asked
            // here, where the minutes are about to be spent, and only until
            // it is granted.
            if (!unrestricted) {
                NoticeCard(
                    severity = Severity.Warn,
                    title = "Android may pause this while the screen is off",
                    body = "A deploy takes minutes of network. Letting Thragg run unrestricted keeps it going in your pocket.",
                    actions = {
                        SeekerChip(
                            label = "Allow in background",
                            onClick = { BackgroundWork.requestUnrestricted(context) },
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
            when (freshness) {
                ArtifactFreshness.Missing -> if (program != null) {
                    NoticeCard(
                        severity = Severity.Warn,
                        title = "Build first",
                        body = "There is no $artifactName to deploy.",
                    )
                }

                is ArtifactFreshness.Stale -> NoticeCard(
                    severity = Severity.Warn,
                    title = "Build first",
                    body = "$artifactName is stale — edited since the last build. Deploying it ships old code.",
                )

                is ArtifactFreshness.Fresh -> Unit
            }
            if (mainnetUnfunded) {
                NoticeCard(
                    severity = Severity.Error,
                    title = "Connect Seed Vault first",
                    body = "There is no faucet on mainnet-beta: the wallet funds the deploy key. " +
                        "Connect it in Settings, under Wallet.",
                )
            }
            if (closed) {
                NoticeCard(
                    severity = Severity.Error,
                    title = "This id was closed",
                    body = "Program ${facts?.resolved?.id} was closed on $where; that id can " +
                        "never be reused. Delete target/deploy/${program?.moduleName}-keypair.json and " +
                        "rebuild to get a new id.",
                )
            }
            if (deployed != null && foreign) {
                NoticeCard(
                    severity = Severity.Error,
                    title = "Not this phone's to upgrade",
                    body = if (deployed.authority == null) {
                        "The program on $where is immutable."
                    } else {
                        "The program on $where is upgradeable only by " +
                            "${Base58.short(deployed.authority)}, which is neither Seed Vault nor the deploy key."
                    },
                )
            }

            SeekerCard(modifier = Modifier.fillMaxWidth()) {
                FactRow(label = "Cluster", value = where)
                HairlineDivider()
                FactRow(
                    label = "Program id",
                    value = facts?.resolved?.id
                        ?: if (facts == null) "…" else "no id yet — the first build creates the keypair",
                )
                HairlineDivider()
                FactRow(
                    label = "Artifact",
                    value = artifactDetail(
                        fileName = artifactName,
                        bytes = facts?.artifactBytes,
                        freshness = freshness,
                        now = System.currentTimeMillis(),
                    ),
                )
                HairlineDivider()
                FactRow(label = "Signer", value = signerDetail(wallet))
                HairlineDivider()
                val estimate = facts?.estimate
                FactRow(
                    label = if (deployed != null) "Estimated cost (upgrade)" else "Estimated cost",
                    value = costDetail(estimate?.total),
                )
                if (estimate != null && estimate.bufferRent > 0) {
                    Text(
                        text = comesBackDetail(estimate.bufferRent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = MD.space3, end = MD.space3, bottom = MD.space2),
                    )
                }
                HairlineDivider()
                FactRow(
                    label = "Deploy key balance",
                    value = keyBalanceDetail(
                        address = facts?.deployKey,
                        loaded = facts != null,
                        balance = facts?.keyBalance?.getOrNull(),
                        failed = facts?.keyBalance?.isFailure == true,
                        cluster = where,
                    ),
                )
            }
        }
    }

    if (mainnetAsk) {
        AlertDialog(
            onDismissRequest = { mainnetAsk = false },
            tonalElevation = 0.dp,
            title = { Text("Deploy ${program?.moduleName ?: "this program"} to mainnet-beta?") },
            text = {
                Text(
                    "${costDetail(facts?.estimate?.total)} of real money. " +
                        "A mainnet deploy cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mainnetAsk = false
                        deploy()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Deploy") }
            },
            dismissButton = {
                TextButton(onClick = { mainnetAsk = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The one IO pass. Each network read is its own `runCatching` so a cluster
 * that does not answer still leaves the sheet with the id, the artifact and
 * the estimate — the facts that live on this phone.
 */
private fun gather(context: Context, root: String, program: ProgramTarget, cluster: Cluster): DeployFacts {
    val resolved = ProgramIds.resolve(root, program, cluster)
    val artifact = File(root, program.artifactPath)
    val bytes = artifact.takeIf { it.isFile }?.length()
    // Read, never generated: the deployer makes the key when it needs one.
    val key = runCatching {
        if (DeployKey.exists(context)) DeployKey.get(context).publicKey.base58 else null
    }.getOrNull()
    val rpc = Rpc(cluster)
    val balance = key?.let { runCatching { rpc.getBalance(it) } }
    val status = resolved.id?.let { runCatching { ProgramStatus.inspect(rpc, it) } }
    val upgrade = status?.getOrNull() is OnChainProgram.Deployed
    val estimate = bytes?.let { Loader.estimateDeploy(it.toInt(), upgrade) }
    return DeployFacts(resolved, bytes, key, balance, status, estimate)
}

/**
 * The Artifact row: `my_program.so · 214 kB · built 30 s ago`, or the two
 * ways it is not ready. Pure, tested (DeploySheetTest); [now] is a parameter
 * so the test can hold the clock still.
 */
internal fun artifactDetail(fileName: String, bytes: Long?, freshness: ArtifactFreshness, now: Long): String {
    val size = bytes?.let { formatBytes(it) }
    return when (freshness) {
        ArtifactFreshness.Missing -> "$fileName · missing"
        is ArtifactFreshness.Fresh -> listOfNotNull(fileName, size, builtAgo(freshness.at, now)).joinToString(" · ")
        is ArtifactFreshness.Stale -> listOfNotNull(fileName, size, "stale — edited since the last build").joinToString(" · ")
    }
}

/** `built 30 s ago`, coarsening with age; a build from before now reads as `0 s`. */
internal fun builtAgo(at: Long, now: Long): String {
    val seconds = ((now - at) / 1000L).coerceAtLeast(0L)
    return when {
        seconds < 60L -> "built $seconds s ago"
        seconds < 3_600L -> "built ${seconds / 60L} min ago"
        seconds < 86_400L -> "built ${seconds / 3_600L} h ago"
        else -> "built ${seconds / 86_400L} d ago"
    }
}

/** Who ends up holding the authority: the wallet by address, or the plain fact that there is none. */
internal fun signerDetail(wallet: String?): String =
    if (wallet != null) "Seed Vault · ${Base58.short(wallet)}" else "Deploy key (no wallet connected)"

/** `~1.49 SOL` — the tilde is the formula admitting the RPC has the last word. */
internal fun costDetail(total: Long?): String =
    if (total == null) "not known yet" else "~${Loader.lamportsToSol(total)}"

/** The second line under the cost: the buffer rent, which the deploy drains back. */
internal fun comesBackDetail(bufferRent: Long): String =
    "of which ${Loader.lamportsToSol(bufferRent)} comes back after deploy"

/** The deploy key's balance line, in the order the facts arrive. */
internal fun keyBalanceDetail(
    address: String?,
    loaded: Boolean,
    balance: Long?,
    failed: Boolean,
    cluster: String,
): String = when {
    !loaded -> "…"
    address == null -> "no deploy key yet · the first deploy creates one"
    failed -> "could not reach $cluster"
    balance == null -> "…"
    else -> "${Loader.lamportsToSol(balance)} on $cluster"
}
