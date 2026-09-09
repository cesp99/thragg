package to.eyed.thragg.ui.shell.projects

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentSessions
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.ProjectSession
import to.eyed.thragg.core.ProjectSummary
import to.eyed.thragg.core.ProjectsRoot
import to.eyed.thragg.core.SafTransfer
import to.eyed.thragg.solana.chain.Base58
import to.eyed.thragg.solana.chain.Cluster
import to.eyed.thragg.solana.chain.ClusterStore
import to.eyed.thragg.solana.chain.SeedVaultWallet
import to.eyed.thragg.terminal.GitClone
import to.eyed.thragg.terminal.TerminalSessions
import to.eyed.thragg.ui.components.BottomActionsGap
import to.eyed.thragg.ui.components.EmptyState
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.SectionHeader
import to.eyed.thragg.ui.components.ThraggCard
import to.eyed.thragg.ui.components.ThraggSearchField
import to.eyed.thragg.ui.components.ThraggSpinner
import to.eyed.thragg.ui.components.StatusDot
import to.eyed.thragg.ui.components.fadeUnderBottomActions
import to.eyed.thragg.ui.components.outlinedButtonEdge
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.ui.shell.BuildState
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.SessionRestore
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.build.ShellModes
import to.eyed.thragg.ui.shell.code.CodeState
import to.eyed.thragg.ui.shell.settings.WalletSheet
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.longPressDoor
import to.eyed.thragg.ui.theme.pressScale
import to.eyed.thragg.ui.theme.MonoSmall
import to.eyed.thragg.ui.theme.RowChevron
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.accentIcon
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.workspace.Notifications

/**
 * Projects & tools — the sheet behind the project chip.
 *
 * One list replaces two inherited overlays: ProjectPicker.kt's modal (create,
 * clone, import, export, delete, all behind a dialog that also hosted the
 * clone form) and RecentProjectsPicker.kt's ranked recents. There is no
 * "recent" list any more because there is no distinction left to draw — one
 * project is one folder in app-private storage, every folder is listed, and
 * the order is the order they were last touched (docs/UI.md, "Projects &
 * tools").
 *
 * A sheet rather than a fourth destination because it is visited once a
 * session, not once a minute; and the tool rows are here because this is the
 * *only* route to Wallet, Toolchain and Settings, none of which is a
 * destination either.
 *
 * WHAT THE MATERIAL PASS CHANGED (docs/VISUAL.md, "Projects"): the rows are
 * one [ThraggCard] group with a [HairlineDivider] between them rather than a
 * per-row hand-drawn fill; the filter is the SAME pill as the composer and the
 * model drill ([ThraggSearchField]), which is the entire point of having one
 * of them; and the two ways to get a *new* project moved into SheetScaffold's
 * pinned `actions` slot, where a list of forty projects cannot push them off
 * the bottom. Press feedback comes back for free with the ripple, and that
 * matters most here: a 56dp project row that does not respond to a press is
 * the clearest single instance of the "this is not a real Android app" tell
 * the owner named.
 *
 * The two hard rules of switching, both inherited from
 * WorkspaceScreen.kt:1197 and both easy to lose in a rewrite:
 *
 *  1. **The old session is closed before the new one is opened.** A
 *     `ProjectSession` is an open worktree inside the engine with a scan
 *     thread behind it; leaking one leaks the scan.
 *  2. **Everything pushed over a destination goes.** A route naming a file in
 *     a project that is no longer open cannot draw itself, which is what
 *     [ShellState.reset] is for.
 */
@Composable
fun ProjectsSheet(
    state: ShellState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Open the Wallet sheet somewhere else. Null — the only caller today —
     * opens this file's own copy of [WalletSheet] over this sheet, for the
     * cluster of the open project (devnet with none), because the row is the
     * only route to the wallet from Projects and a row that says "not in this
     * build yet" over a wallet that is in the build is a lie with a chevron.
     */
    onOpenWallet: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    // The wallet row's readout and its own sheet. The cluster is read once,
    // off the main thread: `ClusterStore.of` opens Anchor.toml.
    var walletOpen by remember { mutableStateOf(false) }
    val walletCluster by produceState(Cluster.DEFAULT, state.project?.rootPath, ClusterStore.version) {
        value = withContext(Dispatchers.IO) { ClusterStore.of(context, state.project?.rootPath) }
    }

    // Bumped by anything that changes the directory listing — a create, a
    // delete, a rename, an import, a clone. The list is a directory read, so
    // re-reading it is cheaper than keeping a model in step with it.
    var revision by remember { mutableStateOf(0) }
    var projects by remember { mutableStateOf(emptyList<ProjectRow>()) }
    LaunchedEffect(revision) {
        projects = withContext(Dispatchers.IO) {
            ProjectsRoot.list(context).map { summary ->
                val root = File(summary.path)
                ProjectRow(summary, ProjectKind.of(root), headBranch(root))
            }
        }
    }

    /** The filter, over names and framework labels. Empty is the whole list. */
    var query by remember { mutableStateOf("") }
    val matches = remember(projects, query) {
        val needle = query.trim().lowercase(Locale.getDefault())
        if (needle.isEmpty()) {
            projects
        } else {
            projects.filter { row ->
                row.summary.name.lowercase(Locale.getDefault()).contains(needle) ||
                    row.kind.label?.lowercase(Locale.getDefault())?.contains(needle) == true
            }
        }
    }

    /** The long-press menu's subject, or null when it is closed. */
    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }
    /** Whether the Import sheet — a folder, or files — is up. */
    var importing by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    var exporting by remember { mutableStateOf<ProjectSummary?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    // SAF, both ways. The tree grant is the user's answer to a system picker,
    // so both launchers are registered here and the work is done on IO —
    // `SafTransfer` copies file by file and a project is thousands of them.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Importing…"
        ProjectWork.launch {
            val result = withContext(Dispatchers.IO) { SafTransfer.importAsProject(context, uri) }
            busy = null
            revision++
            when (result) {
                is SafTransfer.Result.Failed -> Notifications.error(result.message, key = "projects")
                is SafTransfer.Result.Imported -> {
                    openProjectInShell(context, state, result.project.absolutePath)
                    onDismiss()
                }
                else -> Unit
            }
        }
    }
    // Files rather than a tree — the fallback that makes Import work at all on
    // this device. The Seeker's own DocumentsUI refuses every folder ("Can't
    // use this folder — To protect your privacy, choose another folder") with
    // USE THIS FOLDER greyed, including folders it has just created, so the
    // tree grant above can never be given and Import was simply dead (QA
    // G-24). Picking files needs no tree permission.
    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = "Importing…"
        ProjectWork.launch {
            val imported = withContext(Dispatchers.IO) { importFiles(context, uris) }
            busy = null
            revision++
            imported.fold(
                onSuccess = { project ->
                    openProjectInShell(context, state, project.absolutePath)
                    onDismiss()
                },
                onFailure = {
                    Notifications.error(
                        it.message ?: "Those files could not be imported",
                        key = "projects",
                    )
                },
            )
        }
    }
    // Export writes one FILE the user names, rather than asking for a folder
    // to write into: the same broken tree provider made "Export a copy" a
    // picker that could never be satisfied, and a zip is also the shape
    // somebody wants a project copy in (QA G-24).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val target = exporting
        exporting = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        busy = "Exporting…"
        ProjectWork.launch {
            val written = withContext(Dispatchers.IO) {
                zipProject(context, File(target.path), uri)
            }
            busy = null
            written.fold(
                onSuccess = { files ->
                    Notifications.info(
                        "Exported $files ${if (files == 1) "file" else "files"} to ${target.name}.zip",
                        key = "projects",
                    )
                },
                onFailure = {
                    Notifications.error(
                        it.message ?: "That project could not be exported",
                        key = "projects",
                    )
                },
            )
        }
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        modifier = modifier,
        title = "Projects",
        actions = {
            // Pinned, and only these two: the ways to get a project that does
            // not exist yet. Filled for the one this screen exists to
            // encourage, outlined for the other — Material's own pairing for
            // a primary action beside its alternative.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MD.space3),
            ) {
                Button(
                    onClick = { state.push(Route.NewProgram); onDismiss() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("New program", style = MaterialTheme.typography.labelLarge)
                }
                // Absent rather than greyed where there is no userland: a
                // build without the Linux guest cannot run git at all
                // (GitClone.isSupported), and offering what it cannot do is
                // worse than not mentioning it.
                if (GitClone.isSupported) {
                    OutlinedButton(
                        onClick = { state.push(Route.Clone); onDismiss() },
                        modifier = Modifier.weight(1f),
                        // The shared edge, because Material's default one
                        // is invisible under this scheme and this pair is
                        // where that was first seen (see [outlinedButtonEdge]).
                        border = outlinedButtonEdge(),
                    ) {
                        Text("Clone…", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) {
        // Fixed above the list rather than scrolling with it: a filter that
        // scrolls away is a filter you have to hunt for to correct.
        ThraggSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Filter projects…",
            modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space1),
        )
        if (busy != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
                modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space2),
            ) {
                ThraggSpinner()
                Text(
                    text = busy!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            // The list is CLIPPED where the pinned New program / Clone row
            // begins, and the Tools card is the last thing in it — so Settings,
            // the bottom row of that card, was drawn sliced in half. The fade
            // says "there is more below" where a hard cut said "this is
            // broken"; the gap below matches it, so scrolled to the end the
            // gradient lands on padding rather than on the row you came for.
            modifier = Modifier.fillMaxWidth().weight(1f).fadeUnderBottomActions(),
            contentPadding = PaddingValues(
                start = MD.space4,
                end = MD.space4,
                top = MD.space3,
                bottom = BottomActionsGap,
            ),
            verticalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            if (projects.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        headline = "No projects yet",
                        body = "New program scaffolds one. Clone brings one down from GitHub.",
                    )
                }
            } else if (matches.isEmpty()) {
                item(key = "no-matches") {
                    Text(
                        text = "Nothing matches “$query”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MD.space6),
                    )
                }
            } else {
                item(key = "recent-header") { SectionHeader("Recent") }
                // One card for the whole group rather than one per row: the
                // rows are a list, and a list is one object with rules in it.
                // Not `items()` — a phone's project root holds tens of
                // folders, not thousands, so the recycling a lazy row would
                // buy is worth less than the group's single edge.
                item(key = "recent") {
                    ThraggCard(modifier = Modifier.fillMaxWidth()) {
                        matches.forEachIndexed { index, row ->
                            if (index > 0) HairlineDivider()
                            ProjectListRow(
                                row = row,
                                isCurrent = row.summary.path == state.project?.rootPath,
                                onOpen = {
                                    ProjectWork.launch {
                                        openProjectInShell(context, state, row.summary.path)
                                        onDismiss()
                                    }
                                },
                                onLongPress = { menuFor = row.summary },
                            )
                        }
                    }
                }
            }

            item(key = "tools-header") {
                SectionHeader("Tools", modifier = Modifier.padding(top = MD.space4))
            }
            item(key = "tools") {
                ThraggCard(modifier = Modifier.fillMaxWidth()) {
                    ToolRow(
                        label = "Import",
                        icon = R.drawable.ic_ui_folder_import,
                        onClick = { importing = true },
                    )
                    HairlineDivider()
                    ToolRow(
                        label = "Wallet",
                        // The wallet IS a keypair, so the key glyph is the
                        // literal thing rather than a metaphor.
                        icon = R.drawable.ic_ui_key,
                        // The readout is the connection, not a promise: the
                        // address when Seed Vault has been connected, and the
                        // plain fact when it has not.
                        detail = SeedVaultWallet.address?.let { "Seed Vault · ${Base58.short(it)}" }
                            ?: "Not connected",
                        onClick = onOpenWallet ?: { walletOpen = true },
                    )
                    HairlineDivider()
                    ToolRow(
                        label = "Toolchain",
                        // The row reads "Not installed" and opens the
                        // installer, so the glyph is the download it offers.
                        icon = R.drawable.ic_ui_download,
                        detail = if (state.toolchainReady) "Installed" else "Not installed",
                        onClick = { state.push(Route.Setup); onDismiss() },
                    )
                    HairlineDivider()
                    ToolRow(
                        label = "Settings",
                        icon = R.drawable.ic_file_settings,
                        onClick = { state.push(Route.Settings); onDismiss() },
                    )
                }
            }
        }
    }

    if (importing) {
        ImportSheet(
            state = state,
            onDismiss = { importing = false },
            onFolder = { importing = false; importLauncher.launch(null) },
            onFiles = { importing = false; importFilesLauncher.launch(arrayOf("*/*")) },
        )
    }

    val menuTarget = menuFor
    if (menuTarget != null) {
        ProjectMenuSheet(
            state = state,
            project = menuTarget,
            onDismiss = { menuFor = null },
            onRename = { menuFor = null; renaming = menuTarget },
            onExport = {
                menuFor = null
                exporting = menuTarget
                exportLauncher.launch("${menuTarget.name}.zip")
            },
            onDelete = { menuFor = null; deleting = menuTarget },
        )
    }

    val renameTarget = renaming
    if (renameTarget != null) {
        RenameProjectSheet(
            state = state,
            project = renameTarget,
            onDismiss = { renaming = null },
            onRenamed = { renamed ->
                renaming = null
                revision++
                // The engine is holding the *old* path. Reopening on the new
                // one is the whole of "telling it": a worktree cannot be
                // moved under the engine's feet.
                if (state.project?.rootPath == renameTarget.path) {
                    ProjectWork.launch { openProjectInShell(context, state, renamed.absolutePath) }
                }
            },
        )
    }

    val deleteTarget = deleting
    if (deleteTarget != null) {
        ConfirmDeleteSheet(
            state = state,
            project = deleteTarget,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                ProjectWork.launch {
                    val wasOpen = state.project?.rootPath == deleteTarget.path
                    // Closed before the directory goes: the engine is
                    // watching it, and deleting under a live worktree is the
                    // one ordering that produces a scan of nothing. The
                    // terminals, the Shell mode and the build runner go with
                    // it — the same teardown a switch does, which this path
                    // used to skip: a proot left running inside a directory
                    // SafeDelete was removing, pinned foreground by
                    // TerminalService and unreachable, because with no
                    // project open the Build destination never draws a
                    // terminal at all (QA G-18).
                    if (wasOpen) {
                        val open = state.project
                        state.reset()
                        closeOpenProject(context, state, deleteTarget.path)
                        withContext(Dispatchers.IO) { open?.close() }
                    }
                    val gone = withContext(Dispatchers.IO) {
                        ProjectsRoot.delete(context, deleteTarget.name)
                    }
                    revision++
                    if (!gone) Notifications.error("Could not delete ${deleteTarget.name}", key = "projects")
                }
            },
        )
    }
    if (walletOpen) {
        WalletSheet(state = state, cluster = walletCluster, onDismiss = { walletOpen = false })
    }
}

/**
 * Where project work runs: a scope **outside the composition**, and the same
 * argument [to.eyed.thragg.terminal.GitClone] and `UserlandInstaller`
 * make for keeping theirs there.
 *
 * The hazard is specific and it is not hypothetical. Creating a program ends
 * by opening the new project, and opening a project calls
 * [ShellState.reset] — which clears the route stacks, which takes the New
 * program route out of the composition, which cancels a
 * `rememberCoroutineScope()` launched from it. Everything after the open —
 * the agent seed, the navigation, the file to show — would silently never
 * run, and the symptom would be a project that was created correctly and then
 * did nothing. The same applies to the clone callback, which fires from
 * `GitClone`'s own scope minutes after the route it was started from has gone.
 *
 * `Dispatchers.Main.immediate` because everything it touches is snapshot
 * state the UI reads; the blocking parts do their own `withContext(IO)`.
 */
internal val ProjectWork: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Open [path] as the shell's one project, closing whatever was open.
 *
 * `internal` and in this file rather than on [ShellState] because opening a
 * project is *policy* — what it drops, what it keeps, what it writes down —
 * and ShellState is wave 1's, deliberately kept to state with no opinions.
 * Every caller in this package (the sheet, New program, Clone) goes through
 * here so the policy is in one place.
 *
 * Suspending, because all three of the calls it makes are blocking:
 * `ProjectSession()` opens a worktree over JNI, `close()` tears one down and
 * `setLastOpened` writes a preference.
 */
internal suspend fun openProjectInShell(
    context: Context,
    state: ShellState,
    path: String,
    /**
     * Whether this is a *switch* — that is, whether there is a shell full of
     * the old project to tear down.
     *
     * False for exactly one caller: the launch-time restore of the last
     * project (ThraggShell.kt, `ShellBootstrap`), where there is nothing open
     * to reset and [ShellState.reset] would do real damage — it forces the
     * destination back to Code, and the restore is a suspending call that
     * lands after the user has had the shell in their hands for a moment. It
     * would undo an agent notification's navigation, and it would undo a tap
     * on the bar.
     */
    switching: Boolean = true,
): ProjectSession? {
    val previous = state.project
    if (previous?.rootPath == path) return previous
    // The old project's place is written before anything of it is torn down:
    // the buffers, the carets and the destination are all still here — unless
    // the directory has gone, which is what a rename and a delete both leave
    // behind. Writing a session for a path that no longer exists is how the
    // orphan documents in `files/sessions/` were made (QA G-17).
    val previousRoot = previous?.rootPath
    if (switching && previousRoot != null && File(previousRoot).isDirectory) {
        SessionRestore.save(state, CodeState.current)
    }
    // Everything pushed over a destination named the old project's files.
    if (switching) state.reset()
    // …and so did every running shell, and the build runner. Everything the
    // old project owned goes in one place ([closeOpenProject]), because the
    // delete path needs the identical teardown and used to do half of it (QA
    // G-18).
    if (switching) closeOpenProject(context, state, previousRoot)
    // The conversation belongs to the project it was about. AgentSessions is
    // keyed by project id, and its own `open` drops the threads of every
    // other project — this is the close half, for the case where the next
    // project has no agent at all (core/AgentSessions.kt:344).
    AgentSessions.close()
    val opened = withContext(Dispatchers.IO) {
        previous?.close()
        val session = ProjectSession(path)
        ProjectsRoot.setLastOpened(context, File(path).name)
        session
    }
    if (opened.error != null) {
        Notifications.error(opened.error ?: "That project could not be opened", key = "projects")
        withContext(Dispatchers.IO) { opened.close() }
        return null
    }
    state.project = opened
    // The build layer is pointed at the project the shell is showing, here
    // and nowhere else. This is QA B-10's fix and half of G-01's: `refresh`
    // used to be called only from BuildScreen's own LaunchedEffect, so a
    // switch (which forces you onto Code) left BuildRunner holding the
    // *previous* project's layout — ▶ from the editor built the old tree and
    // published its diagnostics — and a cold start onto Code left it holding
    // none at all, which is why ▶ did nothing until Build had been opened
    // once. `probeTools = false` keeps the proot probe to once per process:
    // what the guest has installed is not a fact about the project.
    withContext(Dispatchers.IO) { BuildRunner.refresh(context, path, probeTools = false) }
    // …and the new one's place comes back: its files into Code's queue, its
    // Shell mode, and — on the launch-time restore only — the destination it
    // was on. A switch lands on Code, as `reset()` decided a line ago.
    SessionRestore.restore(state, CodeState.current, opened, applyDestination = !switching)
    return opened
}

/**
 * Everything the open project owns, let go of — the teardown a switch does,
 * factored so the delete path can do the same one.
 *
 * Three things, and the delete path used to do none of them (QA G-18): the
 * terminals, which are `cd`'d into a directory that is about to go and which
 * hold the foreground service up ("1 session running", with no screen left
 * that can reach them); the Shell/Build mode of that root; and the build
 * runner, whose log, warnings, Problems rows and *layout* otherwise follow
 * you into the next project (QA B-10).
 *
 * Main thread by construction — `TerminalSession` binds a Handler to the
 * calling looper, which is what [ProjectWork] is.
 */
internal fun closeOpenProject(context: Context, state: ShellState, root: String?) {
    TerminalSessions.of(context).closeAll()
    root?.let(ShellModes::forget)
    BuildRunner.forget()
    // The verdict is the project's too: a red "Failed" badge from the project
    // you just left is a claim about the one you just opened.
    state.build = BuildState.Idle
}

/** A project, the one thing about it worth reading off disk, and its branch. */
private data class ProjectRow(
    val summary: ProjectSummary,
    val kind: ProjectKind,
    val branch: String?,
)

/**
 * The checked-out branch, read straight out of `.git/HEAD`.
 *
 * One small file per row, which is why it is affordable where a status is not:
 * the wireframe's `main ● 3` wants a dirty count beside the branch, and a
 * count means `git status` over a whole worktree, per project, on a phone.
 * The branch is a single line of text and the honest half of that pair, so it
 * is the half this list draws (docs/VISUAL.md, "Projects").
 *
 * Null for a detached HEAD as well as for a folder that is not a repository:
 * a bare object id is not a branch, and printing forty hex characters where a
 * name goes would be worse than printing nothing.
 *
 * **Blocking** — one file read. It runs in the same IO block as the listing.
 */
private fun headBranch(root: File): String? {
    val head = File(root, ".git/HEAD")
    if (!head.isFile) return null
    val text = runCatching { head.readText() }.getOrNull()?.trim() ?: return null
    if (!text.startsWith("ref:")) return null
    return text.substringAfterLast('/').takeIf { it.isNotBlank() }
}

/**
 * What kind of Solana project a directory holds, by the files in it.
 *
 * Three `File.exists` calls at the top level, which is why this is safe to do
 * for every row: it never walks the tree. The order matters — a Seahorse
 * project *is* an Anchor project (it has an `Anchor.toml` and `seahorse
 * build` hands off to `anchor build`), so `programs_py/` is asked about
 * first or every Seahorse project reads as Anchor.
 */
internal enum class ProjectKind(val label: String?) {
    Seahorse("Seahorse"),
    Anchor("Anchor"),
    Native("Native"),
    Unknown(null);

    companion object {
        /** **Blocking** — three directory reads. Call it off the main thread. */
        fun of(root: File): ProjectKind = when {
            File(root, "programs_py").isDirectory -> Seahorse
            File(root, "Anchor.toml").isFile -> Anchor
            File(root, "Cargo.toml").isFile -> Native
            else -> Unknown
        }
    }
}

/**
 * One project: which one is open, what it is, where it is, and when it was
 * last touched.
 *
 * 56dp because that is the height a two-line list row is on Android and this
 * is now one. The open marker is a [StatusDot] in a fixed 8dp slot drawn
 * whether or not the dot is in it, so every name below starts at the same x —
 * and it takes a description, because unlike most dots in this app it is the
 * only thing on the row saying what it says.
 *
 * The branch is [MonoSmall]: it is a git identifier, and identifiers are set
 * in the buffer face here for the same reason a path is. The elapsed time
 * carries [TabularNums] so the right-hand column stops jittering while a build
 * touches the tree.
 */
@Composable
private fun ProjectListRow(
    row: ProjectRow,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The long-press is a door (the row's menu) and a door always
            // vibrates; the helper keeps the ripple a Material row has.
            .longPressDoor(onLongClick = onLongPress, onClick = onOpen)
            .heightIn(min = RowHeight)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Box(
            // Padding first, then the slot: the other way round the 10dp is
            // taken *out of* the 8dp and the dot measures zero.
            modifier = Modifier.padding(end = DotGap).width(CurrentDotSlot),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                StatusDot(
                    color = scheme.primary,
                    size = CurrentDotSlot,
                    contentDescription = "Open",
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(end = MD.space2)) {
            Text(
                text = row.summary.name,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
                modifier = Modifier.padding(top = MD.space05),
            ) {
                row.kind.label?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (row.branch != null) {
                    ThraggIcon(
                        icon = R.drawable.ic_ui_git_branch,
                        contentDescription = null,
                        tint = mutedIcon,
                        size = IconSize.Marker,
                    )
                    Text(
                        text = row.branch,
                        style = MonoSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Text(
            text = relativeTime(row.summary.lastModified),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TabularNums),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * A row in a card group: a label, an optional readout, and a chevron.
 *
 * [onClick] is nullable and null draws a STATEMENT rather than a disabled
 * control — no chevron, no target, full-strength ink — for the reason
 * SettingsScreen's `LinkRow` gives at length: a greyed row with an arrow on it
 * names a destination, promises it is one tap away and then refuses the tap.
 * The Wallet row is the case that forced it. It has to stay visible, because
 * it is the only route to the wallet and a feature nobody can find is worse
 * than one that is not there yet, but until P6 lands there is nothing behind
 * it — so it is a line of information and it is drawn as one.
 */
@Composable
private fun ToolRow(
    label: String,
    detail: String? = null,
    @DrawableRes icon: Int? = null,
    onClick: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3),
    ) {
        if (icon != null) {
            ThraggIcon(
                icon = icon,
                contentDescription = null,
                tint = accentIcon,
                size = IconSize.Inline,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // MEASURED: the label is the ONLY weighted child. It was weighted
            // alongside a `weight(1f, fill = false)` readout, and two weights
            // split the row in half — so the readout and the chevron were laid
            // out in the middle of the card while the icon-bearing row above
            // them put its chevron on the edge, and one card drew three rows on
            // three different grids. The label absorbing the slack is what
            // pushes the readout and the chevron to the edge; a long readout
            // now ellipsises the label instead of moving the chevron.
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Capped for the reason SettingsScreen's LinkRow caps it: an
                // unweighted child measures first, so an uncapped readout would
                // squeeze the label instead of ellipsising itself.
                modifier = Modifier.widthIn(max = DetailMax),
            )
        }
        // The chevron IS the affordance, so it is drawn only when there is
        // something behind it.
        if (onClick != null) RowChevron()
    }
}

/** The share of a 400dp row a readout may take before it is the one that ellipsises. */
private val DetailMax = 168.dp

/**
 * Import: a folder, or the files in one.
 *
 * Two ways because on this phone only one of them works. The folder picker is
 * Android's tree grant and is the better answer when the device's own
 * `ExternalStorageProvider` will give one; the Seeker's refuses every folder
 * with "Can't use this folder", including ones it has just made, so the
 * sentence says so and the second row is the way through (QA G-24).
 */
@Composable
private fun ImportSheet(
    state: ShellState,
    onDismiss: () -> Unit,
    onFolder: () -> Unit,
    onFiles: () -> Unit,
) {
    SheetScaffold(state = state, onDismiss = onDismiss, title = "Import") {
        MenuRow("A folder, as a new project", onClick = onFolder)
        MenuRow("Files, into a new project", onClick = onFiles)
        Message(
            "A project is copied in rather than opened where it is: the engine watches a " +
                "real directory, and a Storage Access Framework tree has no path behind " +
                "it. If the folder picker says it cannot use the folder you chose — some " +
                "builds of Android's file app refuse every one of them — pick the files " +
                "instead."
        )
    }
}

/** Rename / Export / Delete — docs/UI.md, "Long-press a row". */
@Composable
private fun ProjectMenuSheet(
    state: ShellState,
    project: ProjectSummary,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    SheetScaffold(state = state, onDismiss = onDismiss, title = project.name) {
        MenuRow("Rename", onClick = onRename)
        MenuRow("Export a copy", onClick = onExport)
        MenuRow("Delete", isDestructive = true, onClick = onDelete)
    }
}

@Composable
private fun RenameProjectSheet(
    state: ShellState,
    project: ProjectSummary,
    onDismiss: () -> Unit,
    onRenamed: (File) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(project.name) }
    // Not asked about the name it already has, or every rename sheet opens
    // saying "a project called that already exists".
    val error = remember(name) {
        if (name.trim() == project.name) null else ProjectsRoot.nameError(context, name)
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Rename",
        field = {
            SheetTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Project name",
                error = error,
                autoFocus = true,
            )
        },
        actions = {
            SheetButtons(
                cancelLabel = "Cancel",
                onCancel = onDismiss,
                confirmLabel = "Rename",
                confirmEnabled = error == null && name.isNotBlank(),
                onConfirm = {
                    ProjectWork.launch {
                        // The session document is keyed by path, and
                        // [ProjectsRoot.rename] moves it — so what it moves
                        // has to be current. Snapshotting here, on the main
                        // thread, is the only moment the buffers and carets
                        // of the project being renamed are still open (QA
                        // G-17).
                        val document = if (state.project?.rootPath == project.path) {
                            SessionRestore.snapshot(state, CodeState.current)
                        } else {
                            null
                        }
                        val moved = withContext(Dispatchers.IO) {
                            if (document != null) {
                                runCatching { CoreBridge.sessionSave(document.root, document.toJson()) }
                            }
                            ProjectsRoot.rename(context, project.name, name)
                        }
                        if (moved == null) {
                            Notifications.error("Could not rename ${project.name}", key = "projects")
                            onDismiss()
                        } else {
                            onRenamed(moved)
                        }
                    }
                },
            )
        },
    ) {
        Message("The folder is renamed. Nothing inside it is — a scaffold names the crate, not the folder.")
    }
}

@Composable
private fun ConfirmDeleteSheet(
    state: ShellState,
    project: ProjectSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Delete",
        actions = {
            SheetButtons(
                cancelLabel = "Keep it",
                onCancel = onDismiss,
                confirmLabel = "Delete",
                confirmEnabled = true,
                isDestructive = true,
                onConfirm = onConfirm,
            )
        },
    ) {
        Message("Delete “${project.name}” and everything in it? There is no undo, and it is not in the system trash.")
    }
}

@Composable
private fun MenuRow(label: String, isDestructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isDestructive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space4, vertical = MD.space3),
    )
}

@Composable
internal fun Message(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space2),
    )
}

/**
 * Cancel and a confirm, in that order, at the bottom.
 *
 * Actions go at the bottom of every surface in this app (docs/UI.md), and
 * within the row the destructive or committing one is on the *right*, under
 * the thumb — which is also the one place a slip is least likely, because the
 * thumb arrives there deliberately rather than on the way past.
 *
 * Stock `TextButton` and `Button` now, rather than two `Box`es with a
 * background and a `combinedClickable`: what the hand-rolled pair could not
 * give is exactly what a confirm needs — a button role for TalkBack, a
 * disabled state the platform draws consistently, and press feedback. The
 * destructive confirm swaps the container for `error` rather than tinting the
 * label, because a Delete that reads like every other button is a Delete
 * somebody presses on the way past.
 */
@Composable
internal fun SheetButtons(
    cancelLabel: String,
    onCancel: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    isDestructive: Boolean = false,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MD.space3, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = MD.space2),
    ) {
        TextButton(onClick = onCancel) {
            Text(cancelLabel, style = MaterialTheme.typography.labelLarge)
        }
        // The confirm is the filled object in the pair, and it gives under
        // the thumb; the cancel is text on the sheet and stays still.
        val interaction = remember { MutableInteractionSource() }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            interactionSource = interaction,
            modifier = Modifier.pressScale(interaction),
            colors = if (isDestructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * "2 min ago", as a pure function of two millisecond stamps.
 *
 * Deliberately not `DateUtils.getRelativeTimeSpanString`: it is an Android
 * class, so the rule this list is sorted and read by would only be checkable
 * on a device, and it says "0 minutes ago" for anything under a minute.
 */
internal fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - then) / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 60 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours ${if (hours == 1L) "hour" else "hours"} ago"
        days == 1L -> "yesterday"
        days < 30 -> "$days days ago"
        else -> "${days / 30} ${if (days / 30 == 1L) "month" else "months"} ago"
    }
}

/** Two lines of name and detail, at Android's list-row height. */
private val RowHeight = 56.dp

/**
 * One text field, and the only shape a text field takes in this package.
 *
 * Now a stock `OutlinedTextField` rather than a `BasicTextField` in a themed
 * box. The old note said Material's container "does not survive contact with
 * a Zed theme" — that was true while the scheme was a Zed theme wearing
 * Material's names, and it stopped being true when the bridge started solving
 * the Material half's roles (docs/VISUAL.md, THE HYBRID). What the stock field
 * brings back is the part that was quietly missing: [isError] and
 * [supportingText] mean a refused name is *announced* as an error by TalkBack
 * rather than drawn as a red line underneath it, and the label, the focus
 * indicator and the IME handling stop being this file's problem.
 *
 * The error still sits under the field rather than replacing the placeholder,
 * so a name that is refused is readable while it is being fixed — that part of
 * the old design was right.
 */
@Composable
internal fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String? = null,
    autoFocus: Boolean = false,
    singleLine: Boolean = true,
    /**
     * What the IME should be for this field. The default is a name; a URL
     * field passes one that turns capitalisation and autocorrect off —
     * GBoard capitalised the clone URL into `https://GitHub.com/…`, which
     * git then refused (QA P-14).
     */
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val focus = remember { FocusRequester() }
    // Only ever requested once per field. A request on every recomposition
    // fights the user for focus the moment a second field exists.
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    // A `TextFieldValue`, not a String, for one reason: a field opened with
    // text already in it puts the caret at index 0, so the first thing typed
    // into the Rename sheet prefixed the name — `2qa_native` (QA P-14). The
    // selection is carried here and rebuilt at the *end* of the text whenever
    // the caller rewrites it from outside.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    OutlinedTextField(
        value = shown,
        onValueChange = {
            field = it
            if (it.text != value) onValueChange(it.text)
        },
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = singleLine,
        isError = error != null,
        supportingText = if (error == null) {
            null
        } else {
            { Text(text = error, style = MaterialTheme.typography.bodySmall) }
        },
    )
}

/** The "open" slot: a fixed width, so the names beside it line up. */
private val CurrentDotSlot = 8.dp

/**
 * The gap between the open marker and the name.
 *
 * 10dp rather than 8 or 12 because the slot either side of it is 8dp of dot
 * and the name is 14sp: on the 4dp grid this pair reads as either crowded or
 * detached, and this is the one place in the row where that matters.
 */
private val DotGap = 10.dp


// ---- Import and export, without a tree grant ---------------------------------
//
// These belong in `core/SafTransfer.kt` beside the tree copiers and should
// move there; they are here because that file was another worker's during the
// QA fix pass. Both are **blocking**.

/**
 * The directories a project export leaves out.
 *
 * `target/` is measured in gigabytes and is reproducible from the source;
 * `node_modules/` is reproducible from `yarn.lock`. Exporting either turns a
 * 2 MB project into a copy no messaging app will carry.
 */
private val ExportSkip = setOf("target", "node_modules")

/**
 * Write [project] into [target] as a zip, and answer how many files went in.
 *
 * Symlinks are skipped rather than followed: a clone can hold one pointing
 * anywhere, and a zip that followed it would copy something outside the
 * project — the same rule [to.eyed.thragg.core.SafeDelete] follows for the
 * same reason.
 */
private fun zipProject(context: Context, project: File, target: Uri): Result<Int> = runCatching {
    var files = 0
    val stream = context.contentResolver.openOutputStream(target, "wt")
        ?: error("That file could not be opened for writing")
    stream.use { out ->
        java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(out)).use { zip ->
            project.walkTopDown()
                .onEnter { dir -> dir.name !in ExportSkip && !isLink(dir) }
                .forEach { file ->
                    if (!file.isFile || isLink(file)) return@forEach
                    val relative = file.relativeToOrNull(project)?.path ?: return@forEach
                    zip.putNextEntry(java.util.zip.ZipEntry("${project.name}/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files++
                }
        }
    }
    files
}

/** Whether [file] is a symbolic link, without java.nio's path arithmetic. */
private fun isLink(file: File): Boolean =
    runCatching { file.canonicalFile != file.absoluteFile }.getOrDefault(true)

/**
 * Copy [uris] into a new project and return it.
 *
 * The project is named after the first file's own name without its extension,
 * which is the only name the picker gives us — a tree grant would have had a
 * folder name, and this is the path taken when there is no tree grant to be
 * had.
 */
private fun importFiles(context: Context, uris: List<Uri>): Result<File> = runCatching {
    val first = uris.firstOrNull()?.let { displayName(context, it) }
    val desired = first?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "imported"
    val project = ProjectsRoot.create(context, ProjectsRoot.uniqueName(context, desired))
        ?: error("A project for those files could not be made")
    var written = 0
    for (uri in uris) {
        val name = (displayName(context, uri) ?: "file")
            .replace('/', '_')
            .replace('\\', '_')
            .trimStart('.')
            .ifBlank { "file" }
        val file = freeFile(project, name)
        val input = context.contentResolver.openInputStream(uri) ?: continue
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        written++
    }
    if (written == 0) {
        project.delete()
        error("Nothing could be read from those files")
    }
    project
}

/** `name`, `name 2`, `name 3`… — the first one [dir] has not got. */
private fun freeFile(dir: File, name: String): File {
    val candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val stem = name.substringBeforeLast('.', name)
    val suffix = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var index = 2
    while (File(dir, "$stem $index$suffix").exists()) index++
    return File(dir, "$stem $index$suffix")
}

/** What the picker calls a document, or null when it will not say. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()
