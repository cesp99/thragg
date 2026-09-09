package to.eyed.thragg.ui.shell.build

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.eyed.thragg.solana.build.ArtifactFreshness
import to.eyed.thragg.solana.build.BuildAction
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.solana.build.ProgramTarget
import to.eyed.thragg.solana.chain.AdoptableBuffer
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.BackgroundWork
import to.eyed.thragg.solana.chain.BufferAdoption
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.ClusterStore
import to.eyed.thragg.solana.chain.DeployKey
import to.eyed.thragg.solana.chain.Loader
import to.eyed.thragg.solana.chain.OnChainProgram
import to.eyed.thragg.solana.chain.ProgramIds
import to.eyed.thragg.solana.chain.ProgramStatus
import to.eyed.thragg.solana.chain.Pubkey
import to.eyed.thragg.solana.chain.Rpc
import to.eyed.thragg.solana.chain.RpcException
import to.eyed.thragg.solana.chain.RpcPacer
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.solana.toolchain.formatBytes
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.ThraggCard
import to.eyed.thragg.ui.components.ThraggChip
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
 * the estimate. The estimate is [Loader.estimateDeploy] over the rent the
 * cluster quotes (the same `getMinimumBalanceForRentExemption` the deployer
 * asks, for the sizes [Loader.rentSizes] names), falling back to the formula
 * — for every size, never a mix — when the cluster does not answer, and
 * saying so under the row. An upgrade is priced with the deployed account in
 * hand, so the `ExtendProgram` rent an artifact that grew by a byte will pay
 * is in the number. It keeps its `~` because the fee count is a prediction of
 * how many writes land first time. The second line says how much comes back
 * when the buffer is drained.
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
 * THE BALANCE ROW SAYS WHAT HAPPENS WHEN IT IS SHORT. The deployer covers a
 * shortfall by itself — on devnet it mines the proof-of-work faucet, on
 * testnet it asks the faucet then the wallet, on mainnet the wallet
 * (solana/chain/ProgramDeploy.kt, `fund`) — and a sheet that printed
 * "3.05 SOL" under "needs ~2.6" and nothing else left the user to work out
 * whether Deploy would refuse. So [shortfallDetail] names the gap and the
 * way it is closed, and [onWallet] puts the Wallet sheet — where the deploy
 * key's "Mine 5 SOL" and "Return SOL to wallet" live — one tap away instead
 * of five (Code, Files, Projects, Wallet, scroll: measured on the Seeker
 * 2026-09-08, the path it took to find the miner from the Build tab).
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
    /**
     * Whether the rent in [estimate] is the cluster's own quote. False means
     * every figure in it came from the built-in formula — never a mix of the
     * two, which on devnet is a third of a SOL apart on a 200 kB program.
     */
    val rentQuoted: Boolean = true,
    /**
     * What this deploy will actually be asked to pay: [estimate] less what an
     * adoptable buffer has already paid for. THE SHEET USED TO IGNORE THE
     * BUFFER the deployer was about to reuse and quoted ~1.8111 SOL for a run
     * that then cost 0.00113 (QA r4 §7).
     */
    val outstanding: Long? = null,
    /** The buffer rent that comes back after the deploy: an adopted buffer's own lamports. */
    val comesBack: Long = 0L,
    /** The line naming the buffer being reused, or null when there is none. */
    val adopted: String? = null,
    /**
     * Every read this pass attempted failed or timed out — the phone cannot
     * reach the cluster at all. Distinct from a single failed read, and the
     * reason the sheet no longer sits on "…" for minutes (QA).
     */
    val offline: Boolean = false,
)

@Composable
internal fun DeploySheet(
    state: ShellState,
    onDismiss: () -> Unit,
    onWallet: (() -> Unit)? = null,
    /** Opens the top-up picker with this many lamports pre-filled. */
    onTopUp: ((shortfall: Long) -> Unit)? = null,
) {
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
    val unreachable = facts?.status?.isFailure == true || facts?.offline == true
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
                    body = if (facts?.status?.isFailure == true) {
                        "A deploy has to know what $where already has at this id before it starts."
                    } else {
                        "Nothing could be read from $where — check the network and try again."
                    },
                    actions = { ThraggChip(label = "Try again", onClick = { retry++ }) },
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
                        ThraggChip(
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

            ThraggCard(modifier = Modifier.fillMaxWidth()) {
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
                    // The OUTSTANDING cost, not the estimate's total: an
                    // earlier attempt's buffer is rent already on chain and
                    // chunks already written, and the deployer will adopt it
                    // (QA r4 §7).
                    value = costDetail(facts?.outstanding),
                )
                facts?.adopted?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = MD.space3, end = MD.space3, bottom = MD.space2),
                    )
                }
                if (estimate != null && facts?.rentQuoted == false) {
                    Text(
                        text = rentFallbackDetail(where),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = MD.space3, end = MD.space3, bottom = MD.space2),
                    )
                }
                val comesBack = facts?.comesBack ?: 0L
                if (estimate != null && comesBack > 0L) {
                    Text(
                        text = comesBackDetail(comesBack),
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
                    trailing = onWallet?.let { open ->
                        {
                            TextButton(onClick = { onDismiss(); open() }) {
                                Text("Wallet", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    },
                )
                val gap = shortfallOf(facts?.keyBalance?.getOrNull(), facts?.outstanding)
                val shortfall = shortfallDetailOf(facts?.keyBalance?.getOrNull(), facts?.outstanding, cluster)
                if (shortfall != null) {
                    Text(
                        text = shortfall,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = MD.space3, end = MD.space3, bottom = MD.space2),
                    )
                    // This is the moment the user is blocked, so the way out
                    // is here rather than five taps away — and it carries the
                    // gap this sheet just computed, so the picker's first chip
                    // is the number they have already read.
                    if (onTopUp != null && gap != null) {
                        Row(modifier = Modifier.padding(start = MD.space3, end = MD.space3, bottom = MD.space3)) {
                            ThraggChip(
                                label = "Top up from wallet",
                                onClick = { onDismiss(); onTopUp(gap) },
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            // Something is happening, and it is bounded: the facts pass gives
            // the cluster REACH_BUDGET_MS and then says so. Without this the
            // sheet was six ellipses and a dead button (QA).
            if (facts == null && cluster != null && program != null) {
                Text(
                    text = "Asking $where…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MD.space3),
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
                    "${costDetail(facts?.outstanding)} of real money. " +
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
 * How long the whole IO pass may spend trying to reach the cluster.
 *
 * A BUDGET, NOT A PER-READ TIMEOUT. [RpcPacer] retries a dropped socket five
 * times, 1 s / 2 s / 5 s / 10 s / 15 s apart, which is right for a deploy
 * that has already started and wrong for a sheet: with the network off, five
 * reads each took the full 33 s and the sheet sat on "…" with Deploy greyed,
 * no spinner and no explanation for nearly three minutes. Twenty seconds
 * shared between them means the "Could not reach devnet" notice and its
 * "Try again" arrive while the user is still looking (QA).
 */
private const val REACH_BUDGET_MS = 25_000L

/**
 * And the most any one of them may spend, so a single slow read cannot eat
 * the whole budget and make the four after it report a cluster that is
 * answering perfectly well as unreachable.
 */
private const val REACH_READ_MS = 12_000L

/**
 * The one IO pass. Each network read is its own [Result] so a cluster that
 * does not answer still leaves the sheet with the id, the artifact and the
 * estimate — the facts that live on this phone — and all of them share
 * [REACH_BUDGET_MS], so "does not answer" is a thing the sheet says rather
 * than a thing it does.
 */
private suspend fun gather(context: Context, root: String, program: ProgramTarget, cluster: Cluster): DeployFacts {
    val resolved = ProgramIds.resolve(root, program, cluster)
    val artifact = File(root, program.artifactPath)
    val bytes = artifact.takeIf { it.isFile }?.length()
    // Read, never generated: the deployer makes the key when it needs one.
    val key = runCatching {
        if (DeployKey.exists(context)) DeployKey.get(context).publicKey.base58 else null
    }.getOrNull()
    val rpc = Rpc(cluster)
    // Six reads at once from a composition is how a sheet gets rate limited
    // by the endpoint it is about to deploy through; the pacer is the same
    // gate the deployer uses, and it retries a 429 rather than falling back
    // to the formula on one.
    val pacer = RpcPacer()
    val deadline = System.currentTimeMillis() + REACH_BUDGET_MS
    var attempts = 0
    var failures = 0
    suspend fun <T> reach(block: suspend () -> T): Result<T> {
        attempts++
        val left = minOf(deadline - System.currentTimeMillis(), REACH_READ_MS)
        if (left <= 0L) {
            failures++
            return Result.failure(RpcException("Gave up waiting for ${cluster.display}"))
        }
        return try {
            Result.success(withTimeout(left) { block() })
        } catch (e: TimeoutCancellationException) {
            failures++
            Result.failure(RpcException("Timed out after ${REACH_READ_MS / 1_000} s waiting for ${cluster.display}"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++
            Result.failure(e)
        }
    }

    val balance = key?.let { reach { pacer.run { rpc.getBalance(it) } } }
    val status = resolved.id?.let { reach { pacer.run { ProgramStatus.inspect(rpc, it) } } }
    val deployed = status?.getOrNull() as? OnChainProgram.Deployed
    // The deployed account, so an upgrade's ExtendProgram rent is in the row
    // — the deployer charges it whenever the artifact grew by a byte, and
    // the sheet used to print zero for it (QA G-21).
    val existing = deployed?.let { Loader.Existing(it.dataLen, it.reclaimable) }
    val upgrade = deployed != null
    // The cluster's rent, so the cost and the "short by" line under the
    // balance are the deployer's own numbers (ProgramDeploy.fund asks the
    // same sizes, through Loader.rentSizes); the formula only when the
    // cluster does not answer, and then for EVERY size, because a row that
    // adds a quoted buffer to a formula's programdata is neither number.
    val quotes = HashMap<Int, Long>()
    var quoted = true
    if (bytes != null) {
        for (size in Loader.rentSizes(bytes.toInt(), upgrade, existing)) {
            val answer = reach { pacer.run { rpc.getMinimumBalanceForRentExemption(size) } }.getOrNull()
            if (answer == null) quoted = false else quotes[size] = answer
        }
    }
    val estimate = bytes?.let {
        val rent: (Int) -> Long = if (quoted) { size -> quotes.getValue(size) } else Loader::rentExempt
        Loader.estimateDeploy(it.toInt(), upgrade, rent, existing)
    }
    // The buffer an earlier attempt left, priced the way the deployer prices
    // it: the same scan, so the sheet cannot quote a whole upload for a run
    // that will adopt one (QA r4 §7). The artifact is only read when a
    // record for this program exists at all, which is almost never.
    var adopted: AdoptableBuffer? = null
    val id = resolved.id
    val payer = key?.let { Pubkey.ofOrNull(it) }
    if (bytes != null && id != null && payer != null && BufferAdoption.anyRecorded(context, cluster, id)) {
        val elf = runCatching { artifact.readBytes() }.getOrNull()
        if (elf != null && elf.isNotEmpty()) {
            adopted = reach {
                BufferAdoption.scan(
                    app = context.applicationContext,
                    rpc = rpc,
                    pacer = pacer,
                    cluster = cluster,
                    programId = id,
                    elfSize = elf.size,
                    chunks = Loader.chunks(elf),
                    payer = payer,
                    // The wallet holds the authority when the program on
                    // chain says someone other than the deploy key does.
                    walletAuthority = deployed?.authority?.let { Pubkey.ofOrNull(it) }?.takeIf { it != payer },
                    // A sheet reports; the deployer is what forgets records.
                    forget = false,
                )
            }.getOrNull()
        }
    }
    val outstanding = estimate?.let {
        Loader.outstanding(it, bufferAlreadyPaid = adopted != null, writesAlreadyLanded = adopted?.done ?: 0)
    }
    return DeployFacts(
        resolved = resolved,
        artifactBytes = bytes,
        deployKey = key,
        keyBalance = balance,
        status = status,
        estimate = estimate,
        rentQuoted = quoted,
        outstanding = outstanding,
        comesBack = adopted?.lamports ?: estimate?.bufferRent ?: 0L,
        adopted = adopted?.let { BufferAdoption.detail(it.key.base58, it.lamports, it.done, it.chunks) },
        offline = attempts > 0 && failures == attempts,
    )
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

/**
 * The line under the balance when it will not cover the deploy, or null when
 * it will or nothing is known yet. The threshold is the deployer's own —
 * the estimate plus a tenth (ProgramDeploy.kt, `fund`) — so this
 * says "short" exactly when the deploy would go and top up first. Each
 * cluster names its own remedy, because each has a different one: devnet
 * mines (about a minute per few SOL on the public endpoint, measured
 * 2026-09-04), testnet asks its faucet and then Seed Vault, mainnet asks
 * Seed Vault for real SOL.
 */
internal fun shortfallDetail(balance: Long?, estimate: Loader.CostEstimate?, cluster: Cluster?): String? =
    shortfallDetailOf(balance, estimate?.total, cluster)

/**
 * [shortfallDetail] against a total the caller worked out — the estimate less
 * what an adoptable buffer has already paid for. The sheet uses this one; the
 * pair above is the same question asked of a bare estimate.
 */
internal fun shortfallDetailOf(balance: Long?, total: Long?, cluster: Cluster?): String? {
    if (cluster == null) return null
    val gap = shortfallOf(balance, total)?.let { Loader.lamportsToSol(it) } ?: return null
    // The tenth is named because the deploy's own first log line prints the
    // bigger number, and two figures that differ by 10 % with nothing to
    // explain them read as a contradiction (QA, s4).
    val margin = "the deploy asks a tenth over the estimate as margin, and"
    return when {
        cluster.hasPowFaucet ->
            "short by about $gap — $margin mines the difference from the devnet proof-of-work " +
                "faucet first, a minute or two; Wallet has Mine 5 SOL to do it ahead of time. " +
                "When the faucet is empty, top up from Seed Vault instead"
        cluster.hasFaucet ->
            "short by about $gap — $margin asks the ${cluster.display} faucet first, " +
                "then Seed Vault for what the faucet will not give"
        else ->
            "short by about $gap — $margin has Seed Vault sign one transfer of real SOL to the deploy key when you confirm"
    }
}

/**
 * The line under the cost when the cluster did not quote its rent. Measured
 * on devnet 2026-09-08: the formula wanted 1.3985 SOL for a 200 kB buffer the
 * cluster prices at 1.0207, so an unannotated fallback is a row that is a
 * third of a SOL wrong and says nothing about it.
 */
internal fun rentFallbackDetail(cluster: String): String =
    "$cluster did not quote its rent, so this is the built-in formula — it reads high, and the deploy will use the cluster's own figures"

/**
 * The gap in lamports, or null when there is none or nothing is known — the
 * one number behind both [shortfallDetail]'s sentence and the top-up chip's
 * pre-filled amount, so the sheet cannot say one figure and hand over
 * another. The threshold is the deployer's own: the estimate plus a tenth
 * (ProgramDeploy.kt, `fund`).
 */
internal fun shortfallLamports(balance: Long?, estimate: Loader.CostEstimate?): Long? =
    shortfallOf(balance, estimate?.total)

/** [shortfallLamports] against a total the caller worked out. One place, both callers. */
internal fun shortfallOf(balance: Long?, total: Long?): Long? {
    if (balance == null || total == null) return null
    return (Loader.withMargin(total) - balance).takeIf { it > 0L }
}

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
