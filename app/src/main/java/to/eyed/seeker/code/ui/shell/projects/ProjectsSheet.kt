package to.eyed.seeker.code.ui.shell.projects

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.ProjectSummary
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.core.SafTransfer
import to.eyed.seeker.code.terminal.GitClone
import to.eyed.seeker.code.terminal.TerminalSessions
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.build.ShellModes
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.Notifications
import java.io.File

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
 * session, not once a minute; and the last three rows are pinned because they
 * are the *only* route to Wallet, Toolchain and Settings, none of which is a
 * destination either.
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
     * Open the Wallet sheet — P6's, and null until it lands (docs/UI.md, P6).
     * Null draws the row saying so rather than hiding it: the row is the only
     * route to the wallet and a missing row is a feature nobody can find.
     */
    onOpenWallet: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    // Bumped by anything that changes the directory listing — a create, a
    // delete, a rename, an import, a clone. The list is a directory read, so
    // re-reading it is cheaper than keeping a model in step with it.
    var revision by remember { mutableStateOf(0) }
    var projects by remember { mutableStateOf(emptyList<ProjectRow>()) }
    LaunchedEffect(revision) {
        projects = withContext(Dispatchers.IO) {
            ProjectsRoot.list(context).map { ProjectRow(it, ProjectKind.of(File(it.path))) }
        }
    }

    /** The long-press menu's subject, or null when it is closed. */
    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }
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
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val target = exporting
        exporting = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        busy = "Exporting…"
        ProjectWork.launch {
            val result = withContext(Dispatchers.IO) {
                SafTransfer.exportProject(context, File(target.path), uri)
            }
            busy = null
            when (result) {
                is SafTransfer.Result.Failed -> Notifications.error(result.message, key = "projects")
                is SafTransfer.Result.Exported ->
                    Notifications.info("Exported ${result.files} files", key = "projects")
                else -> Unit
            }
        }
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        modifier = modifier,
        title = "PROJECTS",
        actions = {
            // Pinned: the three doors out of work and into configuration.
            ToolRow(
                label = "Wallet",
                detail = if (onOpenWallet == null) "Not set up yet" else null,
                enabled = onOpenWallet != null,
                onClick = { onOpenWallet?.invoke() },
            )
            ToolRow(
                label = "Toolchain",
                detail = if (state.toolchainReady) "Installed" else "Not installed",
                onClick = { state.push(Route.Setup); onDismiss() },
            )
            ToolRow(
                label = "Settings",
                onClick = { state.push(Route.Settings); onDismiss() },
            )
        },
    ) {
        if (busy != null) {
            Message(busy!!)
        }
        if (projects.isEmpty()) {
            Message("No projects yet. Make one below.")
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(projects, key = { it.summary.path }) { row ->
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        ActionRow(glyph = "＋", label = "New program") {
            state.push(Route.NewProgram)
            onDismiss()
        }
        // Absent rather than greyed where there is no userland: the `play`
        // edition cannot run git at all (GitClone.isSupported), and offering
        // what it cannot do is worse than not mentioning it.
        if (GitClone.isSupported) {
            ActionRow(glyph = "⤓", label = "Clone from GitHub") {
                state.push(Route.Clone)
                onDismiss()
            }
        }
        ActionRow(glyph = "⤒", label = "Import a folder") { importLauncher.launch(null) }
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
                exportLauncher.launch(null)
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
                    // one ordering that produces a scan of nothing.
                    if (wasOpen) {
                        val open = state.project
                        state.reset()
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
}

/**
 * Where project work runs: a scope **outside the composition**, and the same
 * argument [to.eyed.seeker.code.terminal.GitClone] and `UserlandInstaller`
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
     * project (SeekerShell.kt, `ShellBootstrap`), where there is nothing open
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
    // Everything pushed over a destination named the old project's files.
    if (switching) state.reset()
    // …and so did every running shell. WorkspaceScreen.kt:1197 dropped the
    // terminals on a switch and the rule survives the rewrite: a session left
    // `cd`'d into a project that is no longer open answers `ls` with the wrong
    // tree and `cargo build` with the wrong crate. On the main thread by
    // construction — TerminalSession binds a Handler to the calling looper —
    // which is what [ProjectWork] is, and closeAll() also hands the foreground
    // service back. The Shell/Build mode of the old root goes with it.
    if (switching) {
        TerminalSessions.of(context).closeAll()
        previous?.rootPath?.let(ShellModes::forget)
    }
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
    return opened
}

/** A project plus the one thing about it worth reading off disk. */
private data class ProjectRow(val summary: ProjectSummary, val kind: ProjectKind)

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

@Composable
private fun ProjectListRow(
    row: ProjectRow,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCurrent) theme.color("element.selected") else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .heightIn(min = RowHeight)
            .padding(horizontal = SheetPadding, vertical = 6.dp),
    ) {
        // The dot is the whole of "which one is open": a filled row plus a
        // bullet, and no checkmark column stealing 24dp from the name.
        Text(
            text = if (isCurrent) "●" else " ",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.summary.name,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(row.kind.label, relativeTime(row.summary.lastModified))
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActionRow(glyph: String, label: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target, glyph included — 400dp wide and
            // 44dp tall, which is the size every tappable thing in this shell
            // is (docs/UI.md, "Navigation").
            .combinedClickable(onClick = onClick)
            .heightIn(min = RowHeight)
            .padding(horizontal = SheetPadding),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A pinned bottom row: a label, an optional readout, and a chevron. */
@Composable
private fun ToolRow(
    label: String,
    detail: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .combinedClickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                theme.color("text", MaterialTheme.colorScheme.onSurface)
            } else {
                theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
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
    SheetScaffold(state = state, onDismiss = onDismiss, title = project.name.uppercase()) {
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
        title = "RENAME",
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
                        val moved = withContext(Dispatchers.IO) {
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
        title = "DELETE",
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
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isDestructive) {
            theme.color("error", MaterialTheme.colorScheme.error)
        } else {
            theme.color("text", MaterialTheme.colorScheme.onSurface)
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .heightIn(min = RowHeight)
            .padding(horizontal = SheetPadding, vertical = 12.dp),
    )
}

@Composable
internal fun Message(text: String, isError: Boolean = false) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            theme.color("error", MaterialTheme.colorScheme.error)
        } else {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.padding(horizontal = SheetPadding, vertical = 8.dp),
    )
}

/**
 * Cancel and a confirm, in that order, at the bottom.
 *
 * Actions go at the bottom of every surface in this app (docs/UI.md), and
 * within the row the destructive or committing one is on the *right*, under
 * the thumb — which is also the one place a slip is least likely, because the
 * thumb arrives there deliberately rather than on the way past.
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
    val theme = LocalZedTheme.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ButtonHeight)
                .background(
                    theme.color("element.background", MaterialTheme.colorScheme.surfaceVariant),
                    RoundedCornerShape(8.dp),
                )
                .combinedClickable(onClick = onCancel),
        ) {
            Text(
                text = cancelLabel,
                style = MaterialTheme.typography.labelLarge,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ButtonHeight)
                .background(
                    when {
                        !confirmEnabled ->
                            theme.color("element.background", MaterialTheme.colorScheme.surfaceVariant)
                        isDestructive -> theme.color("error", MaterialTheme.colorScheme.error)
                        else -> theme.color("element.selected", MaterialTheme.colorScheme.primary)
                    },
                    RoundedCornerShape(8.dp),
                )
                .combinedClickable(enabled = confirmEnabled, onClick = onConfirm),
        ) {
            Text(
                text = confirmLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (confirmEnabled) {
                    theme.color("text", MaterialTheme.colorScheme.onSurface)
                } else {
                    theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
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

private val RowHeight = 44.dp
private val ButtonHeight = 44.dp
private val SheetPadding = 16.dp

/**
 * One text field, and the only shape a text field takes in this package.
 *
 * `BasicTextField` rather than Material's `TextField` for the reason every
 * field in this app is: Material's carries a 56dp container, a floating label
 * and its own indicator colours, none of which survive contact with a Zed
 * theme, and all of which cost vertical space this column does not have.
 *
 * The error goes *under* the field rather than replacing the placeholder, so
 * a name that is refused is still readable while it is being fixed.
 */
@Composable
internal fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String? = null,
    autoFocus: Boolean = false,
    singleLine: Boolean = true,
) {
    val theme = LocalZedTheme.current
    val focus = remember { FocusRequester() }
    // Only ever requested once per field. A request on every recomposition
    // fights the user for focus the moment a second field exists.
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    theme.color("editor.background", MaterialTheme.colorScheme.surface),
                    RoundedCornerShape(8.dp),
                )
                .heightIn(min = ButtonHeight)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(
                    theme.color("editor.foreground", MaterialTheme.colorScheme.onSurface)
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("error", MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * The interim door to [ProjectsSheet].
 *
 * The sheet's real trigger is the project chip in Code's header, which is
 * P2's (docs/UI.md, "Code"). Until that lands there is nothing on screen that
 * can open it, and a sheet nothing opens is a sheet R8 strips out of the
 * release build along with everything it reaches — which here is
 * SolanaTemplates and SolanaNames, the two files docs/UI.md names as
 * "currently referenced from nowhere".
 *
 * So this is one line in the shell's placeholder destination and it goes when
 * the placeholders do. P2: call [ProjectsSheet] from the chip and delete this.
 */
@Composable
fun ProjectsEntryPoint(state: ShellState, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Text(
        text = "Projects & tools",
        style = MaterialTheme.typography.labelMedium,
        color = LocalZedTheme.current.color("text.accent", MaterialTheme.colorScheme.primary),
        modifier = modifier
            .padding(top = 12.dp)
            .combinedClickable { open = true },
    )
    if (open) {
        ProjectsSheet(state = state, onDismiss = { open = false })
    }
}
