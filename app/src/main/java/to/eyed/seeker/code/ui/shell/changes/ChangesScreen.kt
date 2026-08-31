package to.eyed.seeker.code.ui.shell.changes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentReview
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.FileDiff
import to.eyed.seeker.code.core.GitAskpass
import to.eyed.seeker.code.core.GitChange
import to.eyed.seeker.code.core.GitFileStatus
import to.eyed.seeker.code.core.GitPanelState
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.PatchResult
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.RemoteOpResult
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.pollVersion
import to.eyed.seeker.code.ui.git.GitDraftStore
import to.eyed.seeker.code.ui.git.CommitDrafts
import to.eyed.seeker.code.ui.git.GitOps
import to.eyed.seeker.code.ui.git.RemoteAction
import to.eyed.seeker.code.ui.git.askpassTitle
import to.eyed.seeker.code.ui.git.formatRemoteOutput
import to.eyed.seeker.code.ui.git.isGitPanelSupported
import to.eyed.seeker.code.ui.git.remoteFailureMessage
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.build.CodeJump
import to.eyed.seeker.code.ui.shell.build.FlatButton
import to.eyed.seeker.code.ui.shell.projects.ProjectWork
import to.eyed.seeker.code.ui.theme.ChipCaret
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.RowChevron
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.SeekerIconButton
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.workspace.GitFileStatus as PanelStatus
import to.eyed.seeker.code.ui.workspace.GitStatusColours
import to.eyed.seeker.code.ui.workspace.Notifications

/**
 * Changes — the agent's Keep/Reject and git's staging, on one screen.
 *
 * The two questions a phone developer asks about a diff — "what did the agent
 * just do to my files" and "what am I about to commit" — are the same bytes,
 * so the spec deliberately fuses them into one route rather than a review tab
 * and a git panel (docs/UI.md, "Changes"). This is the trust surface of an
 * agent-first product, and everything about its shape follows from that:
 * three blocks in one list, and Commit at the *bottom*, where the thumb is.
 *
 * What it is **not** is a re-host of GitPanel.kt. That file is 3565 lines of
 * keyboard navigation, amend, a co-author editor and four levels of menu, and
 * it is deleted in P10; the engine underneath it — [GitSession], with
 * `stageHunk` and `restoreHunk` — is what survives, and this screen is a
 * reader of that engine plus [AgentSessions]. The one destructive git
 * operation offered anywhere in the shell is Discard, on a long-press, behind
 * a confirmation that names the file and says where it goes.
 *
 * Granularity is honest on both halves and they differ: agent edits are per
 * *file*, because [AgentSessions.keepEdits] and `rejectEdits` take paths and
 * the engine has nothing finer; git rows are per file here and per **hunk** in
 * the Diff route, where the engine does offer it.
 */
@Composable
fun ChangesScreen(state: ShellState, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val project = state.project

    if (project == null || !isGitPanelSupported) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (project == null) {
                    "No project is open."
                } else {
                    // The play edition has no Linux userland, and git only
                    // exists inside it. Saying so beats a screen of nothing.
                    "git lives in the Linux guest, which this edition has no room for."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        return
    }

    val session = remember(project) { GitSession(project) }
    val ops = remember(project) { GitOps.of(project.id) }
    var snapshot by remember(session) { mutableStateOf(ChangesSnapshot()) }
    // One loop, one payload: status and the whole worktree's patch are read
    // together on IO whenever the git counter moves. Two loops would run two
    // git commands per change and could disagree for a frame — a row with a
    // `+4 −0` from before the edit that removed it.
    ResumedEffect(session) {
        pollVersion(
            intervalMs = STATUS_POLL_MS,
            version = { session.version },
            read = {
                withContext(Dispatchers.IO) {
                    val status = session.state()
                    // `git diff HEAD` over the whole tree: one command for
                    // every row's counts, rather than one per row. Skipped
                    // entirely on a clean tree, which is the common case.
                    val patch = if (status.entries.isEmpty()) {
                        PatchResult()
                    } else {
                        session.patch(null, staged = false)
                    }
                    ChangesSnapshot(status, diffCounts(patch.files))
                }
            },
            apply = { snapshot = it },
        )
    }

    val review = rememberAgentReview()
    val model = remember(snapshot, review) { changesModel(review, snapshot.status, snapshot.counts) }

    // The commit draft. It lives in [CommitDrafts], which writes through to
    // disk, because a half-written commit message is the one thing here nobody
    // will retype — and on Android the event that eats it is not a closed
    // panel but the OS killing a backgrounded process.
    var message by remember(project) { mutableStateOf("") }
    LaunchedEffect(project) {
        // On IO because the first `getSharedPreferences` of a process reads
        // the file; the maps it seeds are read only after this returns, on the
        // main thread, which is the thread they belong to.
        withContext(Dispatchers.IO) { GitDraftStore.bind(context, project.id, project.rootPath) }
        message = CommitDrafts.of(project.id)
    }

    var sheet by remember { mutableStateOf<ChangesSheet?>(null) }
    var discardAsk by remember { mutableStateOf<GitChange?>(null) }

    fun perform(action: suspend () -> String?, onDone: suspend (String?) -> Unit = {}) {
        // Every mutation goes through the per-project single-flight, so a
        // second tap while a stage is running is refused rather than queued
        // against a worktree the first still owns. Failures toast themselves.
        if (!GitOps.run(project.id, action, onDone)) {
            Notifications.info("Still running the last git command…", key = "git:busy")
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ChangesHeader(
            status = snapshot.status,
            busy = ops.busy,
            onOpenBranches = { sheet = ChangesSheet.Branches },
            onPull = { pull(session, project.id, snapshot.status) },
            onPush = { push(session, project.id, snapshot.status) },
            onFetch = { runRemote(RemoteAction.Fetch(null), project.id) { session.fetch(null) } },
            onUnstageAll = {
                if (model.stagedPaths.isNotEmpty()) perform({ session.unstage(model.stagedPaths) })
            },
            onInitRepository = { perform({ session.initRepository() }) },
        )
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!snapshot.status.hasRepo && snapshot.status.scanned) {
                item(key = "no-repo") {
                    Note("This project is not a git repository yet. ⋮ → Initialize repository.")
                }
            }
            if (model.agent.isNotEmpty()) {
                item(key = "agent-header") {
                    SectionHeader(
                        title = "AGENT EDITS (${model.agent.size})",
                        action = "Keep all",
                        // Keep, not Reject, is the bulk button offered: the
                        // list is what the agent proposed and the common
                        // answer is yes. Rejecting is per file, in the Diff,
                        // where the bytes are on screen.
                        onAction = { AgentSessions.keepEdits(emptyList()) },
                    )
                }
                items(model.agent, key = { "agent:${it.path}" }) { row ->
                    AgentRow(row) { state.push(Route.Diff(row.path)) }
                }
            }
            if (model.git.isNotEmpty()) {
                item(key = "git-header") {
                    SectionHeader(
                        title = "YOUR CHANGES (${model.git.size})",
                        action = if (model.stageAll.isEmpty()) null else "Stage all",
                        onAction = { perform({ session.stage(model.stageAll) }) },
                    )
                }
                items(model.git, key = { "git:${it.change.path}" }) { row ->
                    GitRow(
                        row = row,
                        onOpen = { state.push(Route.Diff(row.change.path)) },
                        onToggleStage = {
                            val path = listOf(row.change.path)
                            perform({
                                if (row.mark == StageMark.Staged) {
                                    session.unstage(path)
                                } else {
                                    session.stage(path)
                                }
                            })
                        },
                        onLongPress = { discardAsk = row.change },
                    )
                }
            }
            if (model.conflicts.isNotEmpty()) {
                items(model.conflicts, key = { "conflict:${it.path}" }) { change ->
                    ConflictRow(change) {
                        // A conflict is resolved in the editor, with the
                        // inherited tinted regions and Use HEAD / Use branch /
                        // Use both (ui/editor/Conflicts.kt). "Go and use the
                        // terminal" is not an answer for a product whose
                        // thesis is that the phone is enough.
                        state.pop()
                        CodeJump.to(state, absoluteIn(project.rootPath, change.path), null, null)
                    }
                }
            }
            if (model.isEmpty && snapshot.status.scanned) {
                item(key = "clean") {
                    Note(
                        if (snapshot.status.ran) {
                            "Nothing has changed since the last commit."
                        } else {
                            // Not the same as a clean tree, and saying "no
                            // changes" here would be a claim git never made.
                            "git could not be run in this project."
                        }
                    )
                }
            }
        }

        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outlineVariant))
        CommitBar(
            message = message,
            stagedCount = model.stagedCount,
            busy = ops.busy,
            onEdit = { sheet = ChangesSheet.Commit },
            onCommit = { andPush ->
                if (message.isBlank() || model.stagedCount == 0) {
                    sheet = ChangesSheet.Commit
                    return@CommitBar
                }
                commit(project, session, message, andPush, snapshot.status) {
                    CommitDrafts.clear(project.id)
                    message = ""
                }
            },
        )
    }

    when (sheet) {
        ChangesSheet.Commit -> CommitSheet(
            state = state,
            project = project,
            session = session,
            model = model,
            message = message,
            onMessageChange = { text ->
                message = text
                CommitDrafts.put(project.id, text)
            },
            onCommitted = {
                CommitDrafts.clear(project.id)
                message = ""
            },
            onPush = { push(session, project.id, snapshot.status) },
            onDismiss = { sheet = null },
        )

        ChangesSheet.Branches -> BranchSheet(
            state = state,
            project = project,
            session = session,
            status = snapshot.status,
            onDismiss = { sheet = null },
        )

        null -> Unit
    }

    // The only destructive git operation the shell offers, and it names the
    // file and says where the bytes go — [GitSession.discard] restores a
    // tracked file from the last commit and *trashes* one the commit has never
    // seen, which is a difference the confirmation has to state rather than
    // imply.
    discardAsk?.let { change ->
        AlertDialog(
            onDismissRequest = { discardAsk = null },
            title = { Text("Discard ${change.name}?") },
            text = { Text(discardWarning(change)) },
            confirmButton = {
                TextButton(onClick = {
                    discardAsk = null
                    perform({ session.discard(listOf(change.path)) })
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { discardAsk = null }) { Text("Cancel") }
            },
        )
    }
}

/** Which of the two sheets this route can raise is open. */
private enum class ChangesSheet { Commit, Branches }

/** `main ⌄   ↑2 ↓0   ⋮`, under the shell's own ← row. */
@Composable
private fun ChangesHeader(
    status: GitPanelState,
    busy: Boolean,
    onOpenBranches: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onFetch: () -> Unit,
    onUnstageAll: () -> Unit,
    onInitRepository: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var remoteMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val branch = status.branch
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f, fill = false)
                .clickable(
                    enabled = status.hasRepo,
                    onClickLabel = "Switch branch",
                    onClick = onOpenBranches,
                )
                .touchTarget()
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = branch?.name ?: "no branch",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                // The branch name is what identifies it and long ones share a
                // prefix (`feature/…`), so the middle goes, not the end.
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Drawn, not typed: this caret is the affordance that says the
            // name opens a picker, so it belongs at an icon metric rather
            // than at whatever labelMedium's face does with U+25BE.
            ChipCaret(modifier = Modifier.padding(start = 2.dp))
        }
        Box(modifier = Modifier.weight(1f))
        if (branch != null) {
            Box {
                // `↑2 ↓0` was two arrows in a label. The arrows are the
                // whole meaning — which way the commits go — so they are
                // drawables, and the row says the sentence a screen reader
                // needs instead of leaving it to read two arrowheads out.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(onClickLabel = "Sync with the remote") { remoteMenu = true }
                        .touchTarget()
                        .padding(horizontal = 4.dp)
                        .semantics {
                            contentDescription =
                                "${branch.ahead} ahead, ${branch.behind} behind"
                        },
                ) {
                    AheadBehind(R.drawable.ic_ui_arrow_up, branch.ahead)
                    AheadBehind(
                        R.drawable.ic_ui_arrow_down,
                        branch.behind,
                        Modifier.padding(start = 6.dp),
                    )
                }
                ContextMenu(
                    expanded = remoteMenu,
                    onDismiss = { remoteMenu = false },
                    items = listOf(
                        ContextMenuItem("Pull", enabled = !busy) { onPull() },
                        ContextMenuItem("Push", enabled = !busy) { onPush() },
                        ContextMenuItem("Fetch", enabled = !busy) { onFetch() },
                    ),
                )
            }
        }
        Box {
            SeekerIconButton(
                icon = R.drawable.ic_ui_more_vertical,
                description = "More",
                onClick = { overflow = true },
                tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
            ContextMenu(
                expanded = overflow,
                onDismiss = { overflow = false },
                items = buildList {
                    if (status.hasRepo) {
                        add(ContextMenuItem("Unstage all", enabled = !busy) { onUnstageAll() })
                        add(ContextMenuItem("Fetch", enabled = !busy) { onFetch() })
                    } else {
                        add(
                            ContextMenuItem("Initialize repository", enabled = !busy) {
                                onInitRepository()
                            }
                        )
                    }
                },
            )
        }
    }
}

/** A block title with the block's one bulk action on its right. */
@Composable
private fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("editor.subheader.background", MaterialTheme.colorScheme.surfaceVariant))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .touchTarget()
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

/** `~ programs/escrow/src/state.rs  +3 −1 →` — one file the agent edited. */
@Composable
private fun AgentRow(row: AgentChangeRow, onOpen: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.glyph,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
        )
        PathText(row.path, theme.color("text", MaterialTheme.colorScheme.onSurface), Modifier.weight(1f))
        Counts(row.added, row.removed)
        Chevron()
    }
}

/** `☑ M tests/escrow.ts  +4 −0 ›` — one file git has something to say about. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GitRow(
    row: GitChangeRow,
    onOpen: () -> Unit,
    onToggleStage: () -> Unit,
    onLongPress: () -> Unit,
) {
    val theme = LocalZedTheme.current
    // Read outside the `remember`: `MaterialTheme.colorScheme` is a
    // composition-local and cannot be touched from inside a plain lambda.
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    // The map lookups behind these are per-key, so they happen once per theme
    // rather than once per row (GitStatusColours.kt).
    val colours = remember(theme, text, muted) { GitStatusColours.from(theme, text, muted) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .combinedClickable(onLongClick = onLongPress, onClick = onOpen)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The checkbox is its own target: tapping the row opens the diff and
        // tapping the box stages, which are the two things a row is for and
        // must not be the same gesture.
        StageBox(
            mark = row.mark,
            onToggle = onToggleStage,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Text(
            text = statusLetter(row.change),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            // Dimming is off: an ignored file that git bothered to list is a
            // file the user asked about, and greying it here would hide it.
            color = colours.colorFor(paintedStatus(row.change), dimIgnored = false),
        )
        PathText(row.change.path, theme.color("text", MaterialTheme.colorScheme.onSurface), Modifier.weight(1f))
        if (row.added != null && row.removed != null) Counts(row.added, row.removed)
        Chevron()
    }
}

/** `⚠ conflict — Anchor.toml ›`, its own block because it blocks the commit. */
@Composable
private fun ConflictRow(change: GitChange, onOpen: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SeekerIcon(
            icon = R.drawable.ic_ui_warning,
            // Decoration: the sentence beside it already says "conflict".
            contentDescription = null,
            tint = theme.color("conflict", MaterialTheme.colorScheme.error),
            size = IconSize.Marker,
        )
        Text(
            text = "conflict — ${change.name}",
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("conflict", MaterialTheme.colorScheme.error),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Chevron()
    }
}

/**
 * The commit block: the message, and the two ways to end the session.
 *
 * The message is a *row* rather than a field, and the field is in
 * [CommitSheet] — where the scaffold pins it directly above the IME. A field
 * in the last 44dp of the screen is a field the keyboard covers the moment it
 * is touched, and the sheet is the shape this app already uses for that
 * (SheetScaffold.kt).
 */
@Composable
private fun CommitBar(
    message: String,
    stagedCount: Int,
    busy: Boolean,
    onEdit: () -> Unit,
    onCommit: (andPush: Boolean) -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = message.ifBlank { "Commit message…" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.isBlank()) {
                theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                theme.color("text", MaterialTheme.colorScheme.onSurface)
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .touchTarget()
                .padding(vertical = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlatButton(
                label = if (stagedCount == 0) "Commit" else "Commit $stagedCount",
                modifier = Modifier.weight(1f),
            ) { if (!busy) onCommit(false) }
            FlatButton(
                label = "Commit & Push",
                emphasis = true,
                modifier = Modifier.weight(1f),
            ) { if (!busy) onCommit(true) }
        }
    }
}

@Composable
private fun PathText(path: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Text(
        text = path,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        maxLines = 1,
        // The path's *end* is what identifies it — `…/instructions/initialize.rs`
        // — so the middle is what goes (docs/UI.md, "Orientation").
        overflow = TextOverflow.MiddleEllipsis,
        modifier = modifier,
    )
}

@Composable
private fun Counts(added: Int, removed: Int) {
    val theme = LocalZedTheme.current
    Text(
        text = "+$added",
        style = MaterialTheme.typography.labelSmall,
        color = theme.color("created", theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)),
    )
    Text(
        text = "−$removed",
        style = MaterialTheme.typography.labelSmall,
        color = theme.color("deleted", theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)),
    )
}

/**
 * The mark at the end of a row that opens a diff.
 *
 * `→` became `›`: the shared [RowChevron], which is what every other row in
 * the shell that opens something ends with. An arrow and a chevron were
 * saying the same thing in two vocabularies down one screen.
 */
@Composable
private fun Chevron() {
    RowChevron()
}

/**
 * The staging checkbox, in the three states git's status pair can be in.
 *
 * Its own target, not the row's: tapping the row opens the diff and tapping
 * the box stages, which are the two things a row is for and must not be the
 * same gesture. [touchTarget] gives it 48dp of hit box around an 18dp mark.
 *
 * [StageMark.Partial] is drawn rather than picked, because Lucide's pinned
 * snapshot has no half-filled square and adding one means a network fetch of
 * the pinned release. So it composes the two marks that *are* vendored: the
 * empty box, with a dot centred in it. That is the same shape Material's
 * indeterminate checkbox and git clients generally use for "some of this file
 * is staged", and it cannot be confused with either of its neighbours —
 * which was the whole reason the third state exists.
 */
@Composable
private fun StageBox(mark: StageMark, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalZedTheme.current.color("text", MaterialTheme.colorScheme.onSurface)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .touchTarget()
            .clickable(onClickLabel = "Stage or unstage", onClick = onToggle)
            // The box is the only thing that says the state, so it carries
            // the words rather than passing null the way decoration does.
            .semantics { contentDescription = mark.spoken },
    ) {
        SeekerIcon(
            icon = when (mark) {
                StageMark.Staged -> R.drawable.ic_ui_checkbox_checked
                StageMark.Partial, StageMark.Unstaged -> R.drawable.ic_ui_checkbox
            },
            contentDescription = null,
            tint = ink,
            size = IconSize.Inline,
        )
        if (mark == StageMark.Partial) {
            SeekerIcon(
                icon = R.drawable.ic_ui_dot,
                contentDescription = null,
                tint = ink,
                size = 10.dp,
            )
        }
    }
}

/** One half of `↑2 ↓0`: the arrow, drawn, and its number. */
@Composable
private fun AheadBehind(@DrawableRes icon: Int, count: Int, modifier: Modifier = Modifier) {
    val tint = LocalZedTheme.current
        .color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        SeekerIcon(icon = icon, contentDescription = null, tint = tint, size = IconSize.Marker)
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalZedTheme.current.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
    )
}

// ---- the engine seams, shared with DiffScreen --------------------------------

/**
 * The active thread's edited files, polled while the caller is on screen.
 *
 * The same loop [to.eyed.seeker.code.ui.agent.AgentReviewPane] runs, hoisted
 * here because two surfaces in this package need it: Changes lists the files
 * and Diff asks whether the one it is showing is among them.
 */
@Composable
internal fun rememberAgentReview(): AgentReview {
    val sessionId = AgentSessions.sessionId.takeIf { it >= 0 }
    var review by remember(sessionId) { mutableStateOf(AgentReview.NONE) }
    ResumedEffect(sessionId) {
        if (sessionId == null) return@ResumedEffect
        pollVersion(
            intervalMs = REVIEW_POLL_MS,
            version = { CoreBridge.acpSessionVersion(sessionId) },
            read = { AgentReview.parse(CoreBridge.acpEditedFiles(sessionId)) },
            apply = { review = it },
        )
    }
    return review
}

/** Commit what is staged, and push after it when asked. */
private fun commit(
    project: ProjectSession,
    session: GitSession,
    message: String,
    andPush: Boolean,
    status: GitPanelState,
    onCommitted: () -> Unit,
) {
    val started = GitOps.run(
        project.id,
        action = { session.commit(message) },
        onDone = { failure ->
            if (failure != null) return@run
            // The message has done its job; the draft goes with the commit.
            onCommitted()
            Notifications.info("Committed", key = "git:commit")
            if (andPush) push(session, project.id, status)
        },
    )
    if (!started) Notifications.info("Still running the last git command…", key = "git:busy")
}

/**
 * Push HEAD, publishing the branch when it has no upstream.
 *
 * `--set-upstream` on a branch with none, or with one whose remote branch is
 * gone, is the difference between "send these commits" and "make this branch
 * exist on the remote", and it is the case a phone hits first: a project
 * created here has a `main` no remote has heard of.
 */
private fun push(session: GitSession, projectId: Long, status: GitPanelState) {
    val branch = status.branch ?: return
    val name = branch.name ?: return
    // [ProjectWork], never a composition scope: resolving the remote is a git
    // command, and a push started from a route the user then backs out of must
    // still happen — a "Commit & Push" that silently pushed nothing because
    // the screen left is the worst kind of bug this surface could have.
    ProjectWork.launch {
        val remote = resolveRemote(session, name, forPush = true)
        if (remote == null) {
            Notifications.error(
                "No remote to push to. Add one with `git remote add origin …` in Shell.",
                key = "git:remote",
            )
            return@launch
        }
        runRemote(RemoteAction.Push(name, remote), projectId) {
            session.push(
                name,
                remote,
                setUpstream = !branch.hasUpstream || branch.upstreamGone,
            )
        }
    }
}

private fun pull(session: GitSession, projectId: Long, status: GitPanelState) {
    val name = status.branch?.name ?: return
    ProjectWork.launch {
        val remote = resolveRemote(session, name, forPush = false) ?: return@launch
        runRemote(RemoteAction.Pull(remote), projectId) {
            session.pull(name, remote, rebase = false)
        }
    }
}

/**
 * Which remote a push or a pull talks to — [pickRemote] over what git says,
 * read off the main thread because both halves run git.
 */
private suspend fun resolveRemote(
    session: GitSession,
    branch: String,
    forPush: Boolean,
): String? = withContext(Dispatchers.IO) {
    pickRemote(session.branchRemote(branch, forPush), session.remotes().remotes.map { it.name })
}

/**
 * One remote command, with the credential dialog watching it and its outcome
 * said in git's own words.
 *
 * The askpass window is the whole reason this is not a bare [GitOps.run]:
 * `GIT_ASKPASS` fires while the command is blocked, and the dialog only exists
 * inside [GitAskpass.during]. Without it a push over HTTPS hangs forever on a
 * question nobody can see.
 */
private fun runRemote(
    action: RemoteAction,
    projectId: Long,
    command: suspend () -> RemoteOpResult,
) {
    // Written on IO by the command, read on the main thread by [GitOps.run]'s
    // callback — which is where every toast in this file is raised, because
    // the notification stack is composition state.
    var toast: String? = null
    val started = GitOps.run(
        projectId,
        action = {
            val result = GitAskpass.during(askpassTitle(action)) { command() }
            if (result.ok) {
                toast = formatRemoteOutput(action, result).message
                null
            } else {
                // git's own sentence, not the exit status: "git exited with 2"
                // never says which host, branch or credential went wrong.
                remoteFailureMessage(action, result)
            }
        },
        onDone = { failure ->
            if (failure == null) toast?.let { Notifications.info(it, key = "git:remote") }
        },
    )
    if (!started) Notifications.info("Still running the last git command…", key = "git:busy")
}

private val RowHeight = 44.dp

/** The git counter is cheap to read; the reads behind it are not. */
private const val STATUS_POLL_MS = 400L

/** The same beat the agent panel keeps. */
private const val REVIEW_POLL_MS = 250L


// ---- the model, pure and host-tested (ChangesModelTest) ----------------------

/**
 * One reading of git, both halves taken together: what changed, and how much.
 *
 * The counts are a separate map rather than a field on [GitChange] because
 * they come from a different command — `git status` says *that* a file
 * changed, `git diff HEAD` says by how much — and because an untracked file
 * has no entry in the second at all, which is exactly the row the wireframe
 * draws without numbers.
 */
data class ChangesSnapshot(
    val status: GitPanelState = GitPanelState(),
    val counts: Map<String, DiffCount> = emptyMap(),
)

/** `+4 −0` for one file. */
data class DiffCount(val added: Int, val removed: Int)

/** The whole worktree's patch, reduced to the numbers a row shows. */
internal fun diffCounts(files: List<FileDiff>): Map<String, DiffCount> =
    files.associate { file -> file.path to DiffCount(file.added, file.removed) }

/**
 * What the checkbox says about a file, which git's status pair can answer
 * three ways.
 *
 * The third one is the one that matters and the one a plain checkbox would
 * lose: a file edited, staged, and *edited again* is in both halves of the
 * pair, and committing now would commit the older of the two versions. It gets
 * its own mark rather than a tick that would be a lie.
 *
 * [spoken] rather than a glyph, because the mark is now drawn ([StageBox])
 * and a drawable cannot be read aloud. `☑`/`◪`/`☐` were three codepoints a
 * phone's UI face is not obliged to carry — `◪` least of all — so the state
 * that matters most was the one likeliest to draw as tofu.
 */
enum class StageMark(val spoken: String) {
    Staged("staged"),
    Partial("staged, then edited again"),
    Unstaged("not staged"),
}

internal fun stageMark(change: GitChange): StageMark = when {
    change.staged == null -> StageMark.Unstaged
    change.unstaged == null -> StageMark.Staged
    else -> StageMark.Partial
}

/** The letter git itself prints for the row — its unstaged half, or its staged one. */
internal fun statusLetter(change: GitChange): String {
    if (change.conflicted) return "U"
    return when (change.unstaged ?: change.staged) {
        GitFileStatus.Modified -> "M"
        GitFileStatus.Added -> "A"
        GitFileStatus.Deleted -> "D"
        GitFileStatus.Renamed -> "R"
        GitFileStatus.Untracked -> "?"
        GitFileStatus.Conflicted -> "U"
        GitFileStatus.Ignored -> "!"
        null -> ""
    }
}

/**
 * The row's status in the vocabulary the theme's colours are keyed by.
 *
 * Two enums of the same name exist in this app because they answer different
 * questions — one is what git said, the other is what a row is painted — and
 * this is where they meet for the Changes list. (GitPanel.kt has its own copy
 * of the same six lines; it is deleted in P10 and this is not a reference to
 * it on purpose.)
 */
internal fun paintedStatus(change: GitChange): PanelStatus = when {
    change.conflicted -> PanelStatus.Conflicted
    else -> when (change.unstaged ?: change.staged) {
        GitFileStatus.Modified -> PanelStatus.Modified
        GitFileStatus.Added -> PanelStatus.Added
        GitFileStatus.Deleted -> PanelStatus.Deleted
        GitFileStatus.Renamed -> PanelStatus.Renamed
        GitFileStatus.Conflicted -> PanelStatus.Conflicted
        GitFileStatus.Untracked -> PanelStatus.Untracked
        GitFileStatus.Ignored -> PanelStatus.Ignored
        null -> PanelStatus.None
    }
}

/** One file the agent is waiting to be told about. */
data class AgentChangeRow(
    val path: String,
    val added: Int,
    val removed: Int,
    val created: Boolean,
    val deleted: Boolean,
) {
    /** git's own vocabulary for the three things an edit can be. */
    val glyph: String get() = when {
        created -> "+"
        deleted -> "−"
        else -> "~"
    }
}

/** One file git has something to say about. */
data class GitChangeRow(
    val change: GitChange,
    val mark: StageMark,
    /** Null for a file `git diff` has no numbers for — an untracked one. */
    val added: Int?,
    val removed: Int?,
)

/** The three blocks of the screen, and the two numbers its buttons need. */
data class ChangesModel(
    val agent: List<AgentChangeRow>,
    val git: List<GitChangeRow>,
    val conflicts: List<GitChange>,
    /**
     * Every path the next commit would carry, listed for the commit sheet and
     * counted by the commit button.
     *
     * Over *every* entry, not just the ones listed below "YOUR CHANGES": a
     * staged file the agent also touched is in the agent block and still in
     * the commit, and a list that left it out would understate what Commit is
     * about to do.
     */
    val stagedPaths: List<String>,
    /** What "Stage all" sends: everything listed with anything left to stage. */
    val stageAll: List<String>,
) {
    val stagedCount: Int get() = stagedPaths.size

    val isEmpty: Boolean get() = agent.isEmpty() && git.isEmpty() && conflicts.isEmpty()
}

/**
 * The three blocks, from the two engines that fill them.
 *
 * Three rules, and each is a decision rather than a formatting choice:
 *
 *  1. **A file waiting on Keep/Reject is listed once, in the agent block.**
 *     It is also, always, a git change — the agent wrote to the worktree — so
 *     without this every agent edit would appear twice and the second copy
 *     would offer the wrong verb. Keep or Reject first; after Keep it is an
 *     ordinary change and appears below with everything else.
 *  2. **Kept files are not agent rows.** `status == "kept"` means the question
 *     has been answered, and re-asking it is how a user loses track of what is
 *     still pending.
 *  3. **Conflicts are neither.** There is nothing to stage until one is
 *     resolved, and offering a checkbox on it would offer to commit half a
 *     merge (GitSession.discard refuses them for the same reason).
 */
internal fun changesModel(
    review: AgentReview,
    status: GitPanelState,
    counts: Map<String, DiffCount>,
): ChangesModel {
    val pending = review.pending
    val agentPaths = pending.mapTo(HashSet()) { it.path }
    val agent = pending
        .sortedBy { it.path }
        .map { file ->
            AgentChangeRow(
                path = file.path,
                added = file.diff.added,
                removed = file.diff.removed,
                created = file.created,
                deleted = file.deleted,
            )
        }
    val conflicts = status.entries.filter { it.conflicted }
    val git = status.entries
        .filterNot { it.conflicted || it.path in agentPaths }
        .map { change ->
            val count = counts[change.path]
            GitChangeRow(
                change = change,
                mark = stageMark(change),
                added = count?.added,
                removed = count?.removed,
            )
        }
    return ChangesModel(
        agent = agent,
        git = git,
        conflicts = conflicts,
        stagedPaths = status.entries.filter { it.staged != null }.map { it.path },
        // A conflict's staging *is* its resolution, so it is in; a wholly
        // staged file has nothing left to add and is not.
        stageAll = (git.filter { it.mark != StageMark.Staged }.map { it.change.path } +
            conflicts.map { it.path }),
    )
}

/**
 * What discarding this file will actually do, said before it happens.
 *
 * The two outcomes are not the same and the difference is not recoverable by
 * guessing: a file the last commit holds goes back to what the commit holds; a
 * file it has never seen — untracked, newly staged, the new name of a rename —
 * has nowhere to go back to and is moved to the app's trash instead
 * ([GitSession.discard]).
 */
internal fun discardWarning(change: GitChange): String = when {
    change.original != null ->
        "${change.original} comes back from the last commit and ${change.name} " +
            "goes to the trash. This cannot be undone from here."
    change.inHead ->
        "Every uncommitted change to ${change.name} goes back to the last commit. " +
            "This cannot be undone from here."
    else ->
        "${change.name} is not in the last commit, so it is moved to the app's trash " +
            "rather than restored."
}

/**
 * Which remote a push or a pull talks to.
 *
 * The branch's own configured remote wins, which is what git would have used.
 * With none, a single remote picks itself — there is nothing to ask about —
 * and among several, `origin` is the convention every host and every tutorial
 * writes. Null is "there is nothing to push to", which is a real state on a
 * project this app created and is answered with a sentence rather than a
 * picker: choosing between four remotes is a desktop question, and the fix
 * (`git remote add`) is one line in Shell.
 */
internal fun pickRemote(configured: String?, remotes: List<String>): String? = when {
    configured != null -> configured
    remotes.size == 1 -> remotes.first()
    "origin" in remotes -> "origin"
    else -> null
}
