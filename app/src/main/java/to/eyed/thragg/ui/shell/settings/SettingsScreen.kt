package to.eyed.thragg.ui.shell.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.BuildConfig
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentSessions
import to.eyed.thragg.core.AppSettings
import to.eyed.thragg.core.Autosave
import to.eyed.thragg.core.FormatOnSave
import to.eyed.thragg.core.SpettroSetup
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.solana.build.BuildTasks
import to.eyed.thragg.solana.build.ProgramTarget
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.ClusterStore
import to.eyed.thragg.solana.chain.DeployKey
import to.eyed.thragg.solana.chain.DeployedPrograms
import to.eyed.thragg.solana.chain.OnChainProgram
import to.eyed.thragg.solana.chain.ProgramIds
import to.eyed.thragg.solana.chain.ProgramStatus
import to.eyed.thragg.solana.chain.Rpc
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.solana.toolchain.SolanaToolchain
import to.eyed.thragg.solana.toolchain.formatBytes
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.SectionHeader
import to.eyed.thragg.ui.components.ThraggCard
import to.eyed.thragg.ui.editor.SoftWrapMode
import to.eyed.thragg.ui.shell.Destination
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.RowChevron
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.workspace.AboutDialog
import to.eyed.thragg.ui.workspace.Notifications

/**
 * Settings — one scrolling list, four sections, and one door to the JSON.
 *
 * This replaces ui/workspace/SettingsScreen.kt (1842 lines, a filter box, a
 * per-language matrix, an agent form, an MCP form and rows for six things
 * that no longer exist) with the rows docs/UI.md keeps and nothing else. The
 * deletions are not an omission and must not be re-added here: vim, the base
 * keymap, dock sides, chrome visibility, the minimap, inlay hints, the
 * project-panel sort/fold/spacing block, preview tabs and icon themes all
 * configure subsystems that P10 deletes. A row for a setting that changes
 * nothing is worse than no row.
 *
 * Every key that *is* still in the engine stays reachable, through the one
 * "Edit settings.json" row: the file is JSONC, the engine preserves its
 * comments through the writes this screen makes, and the person who wants
 * `lsp.rust-analyzer.initialization_options` opens it in Code and types it
 * (docs/UI.md, "Settings").
 *
 * Writes go one key at a time through [AppSettings.set], which is **blocking**
 * — it is a JNI hop that rewrites a file — so every one of them is on IO and
 * the resolved settings come back to [onSettingsChanged], which is what
 * repaints the theme.
 *
 * THE MATERIAL PASS (docs/VISUAL.md, "Settings") changed three things and no
 * behaviour. Each section is a [ThraggCard] group with a [HairlineDivider]
 * between rows, under the shared [SectionHeader] — this file's own private
 * copy of that header, one of three in the app, is gone. The booleans are a
 * real `Switch`: the note that used to be here said Material's takes its
 * colours from the M3 scheme "and this app's colours come from a Zed theme
 * file", which was true until the bridge made the M3 scheme *be* the Zed
 * theme, and two boxes and a circle never had the drag gesture, the state
 * description or the disabled treatment. [SliderRow] keeps its write-on-
 * release rule exactly and simply loses its three colour overrides.
 *
 * THE SOLANA CARD IS LIVE. Cluster, Wallet and Program are readouts of
 * things that live elsewhere — Anchor.toml or the chain prefs, the Seed Vault
 * connection, the cluster's own RPC — and each opens the sheet that changes
 * it ([ClusterSheet], [WalletSheet], [ProgramSheet], all in this package).
 * The Program row is the one that asks the network: it says what the cluster
 * has at this project's id right now, not what a record on this phone last
 * believed, so it carries a "checking" state and a "could not reach" state
 * and never a stale answer dressed as a fresh one.
 */
@Composable
fun SettingsScreen(
    state: ShellState,
    settings: AppSettings,
    /** The real settings.json, which the ADVANCED row opens in Code. */
    settingsPath: String?,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    /** Open the Cluster sheet; null opens this file's own [ClusterSheet]. */
    onOpenCluster: (() -> Unit)? = null,
    /** Open the Wallet sheet; null opens this file's own [WalletSheet]. */
    onOpenWallet: (() -> Unit)? = null,
    /** Open the agent picker / install sheet — P3's, null until it lands. */
    onOpenAgentPicker: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var themeListOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }

    // What the toolchain is actually holding, rather than the doc's round
    // number: [SolanaToolchain.diskBytes] sums the *installed* components'
    // declared sizes, so a partial install reads as what it is. Blocking (one
    // small file plus a stat per component), re-asked whenever the flag moves,
    // and null until it answers so the row never invents a figure.
    var toolchainBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.toolchainReady) {
        toolchainBytes = withContext(Dispatchers.IO) {
            runCatching { SolanaToolchain.diskBytes(context) }.getOrNull()
        }?.takeIf { it > 0L }
    }

    // The account row below reads the cache the agent pushes; re-ask once
    // when the screen opens so a sign-in that happened in the terminal is not
    // shown stale. Only with an agent process up to answer — the same
    // `projectId < 0` line callExtension itself refuses on — because with no
    // agent the call can only come back Offline and the cached value, null
    // included, is already the truth this device has.
    LaunchedEffect(Unit) {
        if (AgentSessions.projectId >= 0) SpettroSetup.refreshAccount()
    }

    // THE CHAIN ROWS. The cluster is read from disk, not held here: for an
    // Anchor project Anchor.toml is the truth and for a Native one the prefs
    // are, and [ClusterStore.of] is the one place that knows which
    // (solana/chain/ClusterStore.kt). It is a small file read, but a file
    // read and a preferences load are not the main thread's to do, so it
    // goes to IO and the rows print "…" until it is back; it is re-done
    // only when [ClusterStore.version] moves, which is what every write to
    // it does. `anchorSays` is the raw value for the one case the enum
    // cannot hold: a project whose file says `localnet`, which this phone
    // cannot deploy to and must not pretend is devnet — while it says that,
    // the Cluster sheet ticks no row and the Program row asks nothing.
    val root = state.project?.rootPath
    val clusterVersion = ClusterStore.version
    val chain by produceState<ChainChoice?>(initialValue = null, root, clusterVersion) {
        value = null
        value = withContext(Dispatchers.IO) { ChainChoice.read(context, root) }
    }
    val cluster = chain?.cluster
    val localnet = chain?.localnet == true
    val wallet = SeedVaultWallet.address
    var clusterOpen by remember { mutableStateOf(false) }
    var walletOpen by remember { mutableStateOf(false) }
    var programOpen by remember { mutableStateOf(false) }

    // The program row is live: it asks the cluster's RPC what is at the id,
    // rather than repeating what a record on this phone last believed. The
    // resolve (keypair, then declare_id!, then Anchor.toml) and the two RPC
    // reads all block, so they run on IO behind a "checking" state, and the
    // deploy key is only *read* here — a key that does not exist yet is not
    // generated by opening Settings, because until a deploy happens there is
    // nothing it could be the authority of.
    //
    // WHICH PROGRAM: BuildRunner's layout when it has one for this root, and
    // otherwise a detection of our own — the runner only looks when the Build
    // screen opens, and a project opened straight into Settings (or brought
    // back after process death) has a program all the same. The effect keys
    // on the root and the layout, not on the program it derives from them.
    // `DeployedPrograms.version` is the re-ask a close performs: the close
    // removes the record when it lands, from this sheet or the Wallet
    // sheet's, and the row must not go on saying "deployed".
    val layout = BuildRunner.layout?.takeIf { it.root == root }
    val programsVersion = DeployedPrograms.version
    var program by remember { mutableStateOf<ProgramTarget?>(null) }
    var programId by remember { mutableStateOf<String?>(null) }
    var deployKey by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<OnChainProgram?>(null) }
    var checking by remember { mutableStateOf(false) }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(root, layout, chain, programsVersion) {
        status = null
        unreachable = false
        if (root == null) {
            program = null
            programId = null
            checking = false
            return@LaunchedEffect
        }
        layout?.primary?.let { program = it }
        checking = true
        // Keyed on the cluster read above, which lands a moment after the
        // screen does: until it has there is no cluster to resolve the id
        // against, let alone ask.
        val choice = chain ?: return@LaunchedEffect
        val looked = withContext(Dispatchers.IO) {
            val target = layout?.primary
                ?: runCatching { BuildTasks.detect(File(root)).primary }.getOrNull()
                ?: return@withContext null
            val id = runCatching { ProgramIds.resolve(root, target, choice.cluster).id }.getOrNull()
            val key = runCatching {
                if (DeployKey.exists(context)) DeployKey.get(context).publicKey.base58 else null
            }.getOrNull()
            // Anchor.toml on localnet: there is no cluster to ask, and an
            // answer from the devnet fallback would be about the wrong one.
            val found = if (choice.localnet) null else id?.let { runCatching { ProgramStatus.inspect(Rpc(choice.cluster), it) } }
            ProgramLookup(target, id, key, found)
        }
        program = looked?.target
        programId = looked?.id
        deployKey = looked?.deployKey
        status = looked?.status?.getOrNull()
        unreachable = looked?.status?.isFailure == true
        checking = false
    }

    /** One key, written off the main thread, with the refusal made visible. */
    fun write(key: String, valueJson: String) {
        scope.launch {
            val updated = withContext(Dispatchers.IO) { AppSettings.set(key, valueJson) }
            if (updated == null) {
                // A value the engine refused never reached the file and the
                // rest of the settings are untouched — but silence here is
                // what lets a toggle look like it worked.
                Notifications.error("The engine refused that setting", key = "settings")
            } else {
                onSettingsChanged(updated)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MD.space4)
            // 24dp so the last row clears the nav bar rather than sitting
            // under it (docs/VISUAL.md, "Foundations", RHYTHM).
            .padding(bottom = MD.space6),
        verticalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        SectionHeader("Solana", modifier = Modifier.padding(top = MD.space4))
        ThraggCard(modifier = Modifier.fillMaxWidth()) {
            LinkRow(
                label = "Toolchain",
                detail = when {
                    !state.toolchainReady -> "not installed"
                    toolchainBytes != null -> "installed · ${formatBytes(toolchainBytes!!)}"
                    else -> "installed"
                },
                onClick = { state.push(Route.Setup) },
            )
            HairlineDivider()
            // The readout is the cluster's own spelling, and for the one
            // value the picker does not offer — `localnet` in Anchor.toml —
            // it is that word plus where it came from, so the user knows what
            // they are about to overwrite. The sheet is always reachable: with
            // no project open the choice still lands in prefs and is what the
            // next project starts on.
            LinkRow(
                label = "Cluster",
                detail = chain?.let { clusterRowDetail(it.cluster.display, it.anchorSays, hasProject = root != null) } ?: "…",
                description = "open a project".takeIf { root == null },
                onClick = onOpenCluster ?: { clusterOpen = true },
            )
            HairlineDivider()
            LinkRow(
                label = "Wallet",
                detail = walletRowDetail(wallet),
                onClick = onOpenWallet ?: { walletOpen = true },
            )
            // Only with a program to ask about. The readout is the id, the
            // sentence under the label is what the cluster says about it —
            // and while the cluster has not answered the sentence says so,
            // because a row that is silent for two seconds and then says
            // "not deployed" has told the user two different things.
            program?.let {
                HairlineDivider()
                LinkRow(
                    label = "Program",
                    detail = programId?.let { Base58.short(it) } ?: "no id yet",
                    description = if (cluster == null) {
                        "…"
                    } else {
                        programRowDescription(
                            checking = checking,
                            unreachable = unreachable,
                            described = status?.let { ProgramStatus.describe(it, cluster, wallet, deployKey) },
                            cluster = cluster.display,
                            localnet = localnet,
                        )
                    },
                    onClick = { programOpen = true },
                )
            }
        }

        SectionHeader("Agent", modifier = Modifier.padding(top = MD.space4))
        ThraggCard(modifier = Modifier.fillMaxWidth()) {
            // ONE ROW, NOT TWO. "Install an agent" used to sit under this
            // one carrying the SAME onClick — two rows, one action, and while
            // the picker is null neither could perform it. A permanently grey
            // row whose whole text is a verb is the worst case of the dead
            // affordance: it has no readout to fall back on, so with the
            // chevron gone there would be nothing left of it but a promise.
            // This row already says what is installed and is the route to the
            // picker the day it lands, so the second row was only ever a
            // second door into the same room.
            LinkRow(
                label = "Coding agent",
                // The agents in settings.json are the only agents there are —
                // the panel names none of its own (core/AppSettings.kt,
                // `agents`).
                detail = if (settings.agents.isNotEmpty()) {
                    settings.agents.first().name
                } else {
                    "none installed"
                },
                // The last row that ran a readout and a sentence together in
                // the trailing column, and it clipped for exactly the reason
                // the Advanced three did: `none installed · no installer in
                // this build yet` is 46 characters against [DetailMax]'s
                // 168dp, so what reached the screen was `none installed · no
                // in…` — the readout survived and the half that explained
                // the missing chevron did not. The readout stays right, the
                // precondition goes under the label where a sentence has
                // room to be read.
                description = "no installer in this build yet"
                    .takeIf { settings.agents.isEmpty() && onOpenAgentPicker == null },
                onClick = onOpenAgentPicker,
            )
            HairlineDivider()
            // Named for the STATE, not the destination: the email is proof of
            // which account this phone is on, and "Sign in to Spettro" is the
            // verb while there is no account to name. Both readings come from
            // the pure choosers below so a JVM test can pin them
            // (SpettroAccountRowTest). The plan is a sentence-slot description
            // rather than a trailing readout for the reason on [LinkRow]:
            // "Pro · active" beside an email would fight it for the row.
            val account = SpettroSetup.account
            LinkRow(
                label = spettroAccountLabel(account?.signedIn == true, account?.email),
                description = spettroAccountDescription(account?.signedIn == true, account?.plan),
                onClick = { state.push(Route.SpettroSettings) },
            )
        }

        SectionHeader("Editor", modifier = Modifier.padding(top = MD.space4))
        ThraggCard(modifier = Modifier.fillMaxWidth()) {
            LinkRow(
                label = "Theme",
                detail = settings.themeSelection.let { selection ->
                    if (selection.isStatic) selection.light else "${selection.dark} / ${selection.light}"
                },
                onClick = { themeListOpen = true },
            )
            HairlineDivider()
            SliderRow(
                label = "Font size",
                value = settings.bufferFontSize,
                range = MIN_FONT_SIZE..MAX_FONT_SIZE,
                onValue = { size ->
                    write(AppSettings.KEY_FONT_SIZE, size.toInt().toString())
                },
            )
            HairlineDivider()
            ToggleRow(
                label = "Wrap long lines",
                checked = settings.softWrap.wraps,
                onToggle = { on ->
                    // `editor_width` and not `bounded`: bounded also wraps at
                    // preferred_line_length, and an 80-column wrap on a 400dp
                    // screen would leave a strip of empty gutter down the right.
                    val mode = if (on) SoftWrapMode.EditorWidth else SoftWrapMode.None
                    write(AppSettings.KEY_SOFT_WRAP, "\"${mode.key}\"")
                },
            )
            HairlineDivider()
            ToggleRow(
                label = "Format on save",
                checked = settings.formatOnSave != FormatOnSave.Off,
                onToggle = { on ->
                    val value = if (on) FormatOnSave.On else FormatOnSave.Off
                    write(AppSettings.KEY_FORMAT_ON_SAVE, "\"${value.key}\"")
                },
            )
            HairlineDivider()
            ToggleRow(
                label = "Autosave on leaving a file",
                detail = "A build reads the file on disk. 71 seconds is a long time to spend on a stale one.",
                checked = settings.autosave != Autosave.Off,
                onToggle = { on ->
                    val value = if (on) Autosave.OnFocusChange else Autosave.Off
                    write(AppSettings.KEY_AUTOSAVE, value.toJson())
                },
            )
        }

        SectionHeader("Advanced", modifier = Modifier.padding(top = MD.space4))
        ThraggCard(modifier = Modifier.fillMaxWidth()) {
            // Null rather than disabled, for the reason on [LinkRow]: with no
            // settings.json on disk and no editor registered to open it there
            // is nothing behind the chevron, so there is no chevron.
            val openInEditor = state.openPath
            LinkRow(
                label = "Edit settings.json",
                description = "every key, including the ones with no row",
                onClick = if (settingsPath != null && openInEditor != null) {
                    {
                        state.show(Destination.Code)
                        openInEditor(settingsPath)
                    }
                } else {
                    null
                },
            )
            HairlineDivider()
            // Between the JSON door and About, which is where
            // docs/LICENSING.md §5 puts it. Two taps from anywhere in the app
            // to every notice in the package — that reachability is the
            // compliance requirement, not the existence of the files.
            LinkRow(
                label = stringResource(R.string.licences_settings_row),
                description = stringResource(R.string.licences_settings_detail),
                onClick = { state.push(Route.Licences) },
            )
            HairlineDivider()
            LinkRow(
                label = "About this device",
                description = "engine version, ABI, page size",
                onClick = { aboutOpen = true },
            )
            HairlineDivider()
            // The app's own version, as a statement rather than behind About:
            // "which build am I on" is the question a user asks before
            // reporting anything, and it should not cost a tap and a dialog.
            // `versionName` in app/build.gradle.kts is the single source;
            // it moves with every release (0.0.6 → 0.0.7 → …).
            LinkRow(
                label = "Version",
                detail = BuildConfig.VERSION_NAME,
                description = "Thragg",
                onClick = null,
            )
        }
    }

    if (themeListOpen) {
        ThemeList(
            state = state,
            settings = settings,
            onDismiss = { themeListOpen = false },
            onSet = { key, json -> write(key, json) },
        )
    }
    // The three chain sheets wait for the cluster read like the rows do: a
    // tap during the read leaves the flag set, and the sheet comes up the
    // moment the cluster lands.
    val choice = chain
    if (clusterOpen && choice != null) {
        ClusterSheet(
            state = state,
            // No row ticked while the file says localnet: the tick is the
            // choice, and none has been made.
            current = choice.cluster.takeUnless { choice.localnet },
            projectRoot = root,
            isAnchorProject = choice.isAnchorProject,
            onDismiss = { clusterOpen = false },
        )
    }
    if (walletOpen && choice != null) {
        WalletSheet(
            state = state,
            cluster = choice.cluster,
            onDismiss = { walletOpen = false },
        )
    }
    val openProgram = program
    if (programOpen && openProgram != null && choice != null) {
        ProgramSheet(
            state = state,
            cluster = choice.cluster,
            name = openProgram.moduleName,
            programId = programId,
            status = status,
            checking = checking,
            unreachable = unreachable,
            wallet = wallet,
            deployKey = deployKey,
            onDismiss = { programOpen = false },
            localnet = choice.localnet,
        )
    }
    if (aboutOpen) {
        // Kept whole from the inherited workspace: 226 lines that produce a
        // copyable bug report with the engine version, the ABI and the
        // kernel's page size. For a product whose premise rests on the page
        // size being 4 KB, that is cheap insurance (docs/UI.md, "Settings").
        AboutDialog(
            onDismiss = { aboutOpen = false },
            onOpenLicences = {
                // Close the dialog first: a route pushed under an open dialog
                // leaves the dialog on top of it, and the back gesture would
                // then close the dialog before popping the route.
                aboutOpen = false
                state.push(Route.Licences)
            },
        )
    }
}

/**
 * What the disk says about the project's cluster, read in one IO pass:
 * the cluster [ClusterStore.of] settles on, the raw Anchor.toml value it
 * settled from, and whether there is an Anchor.toml for the sheet to write
 * into. [localnet] is the one reading the enum cannot hold — the file names
 * a cluster the picker does not offer — and while it is true the cluster
 * here is the default the store fell back to, not a choice anyone made.
 */
private class ChainChoice(
    val cluster: Cluster,
    val anchorSays: String?,
    val isAnchorProject: Boolean,
    val localnet: Boolean,
) {
    companion object {
        fun read(context: Context, root: String?): ChainChoice {
            val cluster = ClusterStore.of(context, root)
            val anchorSays = root?.let { ClusterStore.anchorTomlSays(it) }
            val isAnchorProject = root != null && File(root, "Anchor.toml").isFile
            return ChainChoice(cluster, anchorSays, isAnchorProject, anchorTomlNamesLocalnet(anchorSays, hasProject = root != null))
        }
    }
}

/** What the Program row's IO pass found: the program, its id, the deploy key, and the cluster's answer. */
private class ProgramLookup(
    val target: ProgramTarget,
    val id: String?,
    val deployKey: String?,
    val status: Result<OnChainProgram>?,
)

/**
 * The engine's clamp on `buffer_font_size` is 6..48; these are the sizes a
 * 400dp column can actually hold a line of Rust at. Below 10 the gutter
 * numbers stop being legible, above 24 an `#[account(...)]` attribute does
 * not fit on two lines.
 */
private const val MIN_FONT_SIZE = 10f
private const val MAX_FONT_SIZE = 24f

/**
 * What the Spettro account row prints as its label. Pure and separate from
 * the composable so a JVM test can hold all three readings still — an email
 * only proves which account when there is one, and a signed-in answer with no
 * email (the backend omits it while the profile is still syncing) must not
 * print a blank row.
 */
internal fun spettroAccountLabel(signedIn: Boolean, email: String?): String = when {
    !signedIn -> "Sign in to Spettro"
    email != null -> email
    else -> "Signed in"
}

/**
 * The sentence under the label: the plan, and only for a signed-in account —
 * a leftover plan string from before a sign-out is not a fact about this row.
 */
internal fun spettroAccountDescription(signedIn: Boolean, plan: String?): String? =
    plan?.takeIf { signedIn }

/**
 * The Cluster row's readout. The cluster's own spelling — except when the
 * project's Anchor.toml names something the picker does not offer, where the
 * readout is that word and where it came from: `localnet · set in Anchor.toml`
 * tells the user what the sheet will overwrite, and printing `devnet` (the
 * fallback [ClusterStore.of] answers) would have hidden that. With no project
 * there is no file to disagree, so it is the stored choice, plain. Pure, so a
 * JVM test can hold all three readings still (ChainRowsTest).
 */
internal fun clusterRowDetail(display: String, anchorSays: String?, hasProject: Boolean): String =
    if (anchorTomlNamesLocalnet(anchorSays, hasProject)) {
        "${anchorSays!!.trim()} · set in Anchor.toml"
    } else {
        display
    }

/**
 * Whether the project's Anchor.toml names a cluster the picker does not
 * offer — `localnet`, in practice — so that the cluster the store answers
 * is its fallback and not a choice. With no project there is no file; an
 * empty row is no row. Pure, tested (ChainRowsTest).
 */
internal fun anchorTomlNamesLocalnet(anchorSays: String?, hasProject: Boolean): Boolean {
    val raw = anchorSays?.trim()
    return hasProject && !raw.isNullOrEmpty() && Cluster.fromAnchor(raw) == null
}

/**
 * The Wallet row's readout: which wallet and which account, or the plain
 * fact that there is none. "Seed Vault" alone would read as a choice already
 * made; the address is the proof that it was.
 */
internal fun walletRowDetail(address: String?): String =
    if (address == null) "not connected" else "Seed Vault · ${Base58.short(address)}"

/**
 * The sentence under the Program row's label, in the order the facts arrive:
 * the wait, the failure to ask, then whatever [ProgramStatus.describe] said.
 * Null when there is no id to ask about, so the row is label and readout
 * alone rather than a sentence about nothing. [localnet] comes before all
 * of them: with Anchor.toml on localnet nothing was asked, and the row says
 * what to do about that instead of describing the fallback cluster.
 */
internal fun programRowDescription(
    checking: Boolean,
    unreachable: Boolean,
    described: String?,
    cluster: String,
    localnet: Boolean = false,
): String? = when {
    localnet -> "localnet · pick a cluster"
    checking -> "checking on $cluster…"
    unreachable -> "could not reach $cluster"
    else -> described
}

/**
 * A row that goes somewhere: a label, an optional readout or description, and
 * a chevron.
 *
 * TWO SLOTS, BECAUSE THEY ARE TWO DIFFERENT THINGS, and conflating them is
 * what put "every key, including the ones w…" on screen. [detail] is a
 * READOUT — `devnet`, `installed · 41 MB`, the current theme — a value the
 * row's own control decides, which belongs on the right where the eye scans a
 * column of values and which is capped at [DetailMax] so it cannot squeeze the
 * label. [description] is a SENTENCE about where the row goes, and a sentence
 * has no business in a 168 dp trailing column: cut there it loses the half
 * that carried the meaning, so all three Advanced rows were paying a line's
 * ink to say nothing. It goes under the label instead, at 2 dp, exactly as
 * [ToggleRow] has always drawn its own (docs/VISUAL.md, "Foundations",
 * RHYTHM: 2dp between a label and its description).
 *
 * NO SUCH THING AS A DISABLED LINK ROW. [onClick] is nullable and null draws a
 * STATEMENT — the label, the readout, no chevron and no click target — because
 * the alternative, which this row used to draw, is a lie with an arrow on it.
 * A greyed "Wallet ›" names a destination, promises it is one tap away, and
 * then refuses the tap with no reason given; the user's only reading is that
 * they have done something wrong. A row with no chevron is not a control that
 * failed, it is a line of information, so it keeps its ink at full strength
 * and lets [detail] carry the precondition ("not in this build yet"). The
 * 38 % disabled alpha that used to be here is Material's answer for a control
 * that will become live when the FORM around it is valid — a Create button
 * beside an empty name field — and none of these rows is that.
 */
@Composable
private fun LinkRow(
    label: String,
    detail: String? = null,
    description: String? = null,
    /** Where the row goes; null when there is nowhere for it to go. */
    onClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        // The label is the only WEIGHTED child, so it absorbs the slack and
        // pushes the readout and the chevron to the row's edge. It used to
        // share a weight with the readout, and two weights split the row in
        // half — which parked the chevron in the middle of the card on
        // every row whose readout was short, while an icon-bearing sibling
        // put its own on the edge.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    // Two lines because the widest of these needs one and a
                    // half at 12sp, and a description that ellipsises is the
                    // thing this slot exists to stop.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Unweighted, so it measures first — capped, so that measuring
                // first cannot squeeze the label. An uncapped readout like
                // "installed · 412 MB / One Dark / Solarized Light" takes its
                // whole intrinsic width and leaves the part that says what the
                // row *is* with nothing; [DetailMax] is the share of a 400dp
                // row a readout may have before it is the one that ellipsises.
                // A readout is short by nature, which is why the cap is a
                // safety net here and was a gag on a sentence.
                modifier = Modifier.widthIn(max = DetailMax),
            )
        }
        // The chevron IS the affordance. Drawn only when there is something
        // behind it, so its absence is the row saying so.
        if (onClick != null) RowChevron()
    }
}

/**
 * A row with a switch.
 *
 * A real `Switch` now. The row carries the `toggleable` semantics and the
 * switch is handed `onCheckedChange = null`, which is Material's own idiom for
 * "the control is the mark, the row is the target": one node, announced once,
 * with the whole 400dp width to hit.
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    detail: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onToggle,
            )
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A row with a slider, for the one setting that is a number worth dragging.
 *
 * Material's `Slider`, now with Material's own colours — the three overrides
 * this row used to pour into it were Zed reads whose M3 fallback never fired,
 * and `SliderDefaults.colors()` gives it the accent for free because the
 * accent *is* the theme's (docs/VISUAL.md, "Settings"). A slider is one of the
 * few controls where the platform's own touch handling — the
 * press-anywhere-on-the-track jump, the drag that keeps following a finger
 * that has left the track vertically — is worth more than matching Zed's
 * chrome exactly.
 *
 * The *write* is on release, through `onValueChangeFinished`, and this is not
 * a nicety: [AppSettings.set] rewrites settings.json through the engine, and
 * writing the file on every frame of a drag would be sixty file rewrites a
 * second. What moves live is the local value, which is what the number on the
 * right reads — tabular, because it ticks under a finger.
 */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
) {
    // Null while the setting is what is showing; a number while a finger is
    // on it. Keyed on [value] so a change from anywhere else — a hand edit of
    // settings.json — is picked up rather than pinned by a stale drag.
    var dragging by remember(value) { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = shown.toInt().toString(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            valueRange = range,
            // Whole points only: `buffer_font_size` is written into a JSON
            // file that a person reads, and 14.372 is not a font size anyone
            // chose. The step count is the number of gaps, not of values.
            steps = (range.endInclusive - range.start).toInt() - 1,
            onValueChangeFinished = { dragging?.let { onValue(it) } },
            modifier = Modifier.fillMaxWidth(),
            // THREE ROLES SPELLED OUT, and all three for one reason:
            // material3 1.4.0's slider defaults are written against a stock M3
            // palette, where `secondaryContainer` is a pale lavender well away
            // from `primary`. Under this scheme it is Zed's `element.selected`
            // (MaterialBridge.kt, band A) — a fill a step BEYOND the top of the
            // surface ladder, further from the canvas than
            // `surfaceContainerHighest` on all eleven bundled themes
            // (MaterialBridgeTest, "secondaryContainer is a sixth fill rung").
            // So:
            //
            //  - `inactiveTrackColor` is `SliderTokens.InactiveTrackColor` =
            //    secondaryContainer, which drew the unspent half of the track
            //    as the brightest panel on a Settings page — a raised surface
            //    where the design wanted the quietest rung;
            //  - `defaultSliderColors` (Slider.kt:1169-1171) then sets
            //    `activeTickColor = InactiveTrackColor` and
            //    `inactiveTickColor = ActiveTrackColor`, i.e. each half's ticks
            //    in the OTHER half's colour — so full-strength `primary` dots
            //    ran along the UNSELECTED half and the slider read as if all of
            //    it were chosen.
            //
            // Same trap ShellNavBar.kt avoids by refusing secondaryContainer
            // for its indicator, and the same answer LevelSlider.kt gives.
            colors = SliderDefaults.colors(
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            ),
        )
    }
}

/**
 * The share of a 400dp row a READOUT may take before it is the one that
 * ellipsises. A description does not go here at all — see [LinkRow].
 */
private val DetailMax = 168.dp
