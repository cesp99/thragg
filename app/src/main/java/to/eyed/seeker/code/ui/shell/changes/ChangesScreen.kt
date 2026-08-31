@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.state.ToggleableState
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
import to.eyed.seeker.code.ui.components.DiffStatLabel
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SeekerChip
import to.eyed.seeker.code.ui.components.SeekerTopBar
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.shell.build.CodeJump
import to.eyed.seeker.code.ui.shell.projects.ProjectWork
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoBody
import to.eyed.seeker.code.ui.theme.MonoSmall
import to.eyed.seeker.code.ui.theme.RowChevron
import to.eyed.seeker.code.ui.theme.SeekerColors
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.SeekerIconButton
import to.eyed.seeker.code.ui.theme.TabularNums
import to.eyed.seeker.code.ui.theme.mutedIcon
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.workspace.GitFileStatus as PanelStatus
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
    val context = LocalContext.current
    val project = state.project

    if (project == null || !isGitPanelSupported) {
        Column(modifier = modifier.fillMaxSize()) {
            SeekerTopBar(title = "Changes", onBack = { state.pop() })
            HairlineDivider()
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (project == null) {
                        "No project is open."
                    } else {
                        // Defensive rather than reachable: `isGitPanelSupported`
                        // asks the userland seam, and every build that ships
                        // has one. If the guest is ever missing, saying which
                        // piece is absent beats a screen of nothing — but the
                        // sentence no longer blames an edition, because there
                        // is only one.
                        "git runs inside the Linux guest, which is not available."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            subtitle = changesSummary(model, snapshot.counts),
            onBack = { state.pop() },
            onOpenBranches = { sheet = ChangesSheet.Branches },
            onPull = { pull(session, project.id, snapshot.status) },
            onPush = { push(session, project.id, snapshot.status) },
            onFetch = { runRemote(RemoteAction.Fetch(null), project.id) { session.fetch(null) } },
            onUnstageAll = {
                if (model.stagedPaths.isNotEmpty()) perform({ session.unstage(model.stagedPaths) })
            },
            onInitRepository = { perform({ session.initRepository() }) },
        )
        HairlineDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // The gutter is 16dp and the last row clears the commit bar by 24
            // (docs/VISUAL.md, "Foundations", RHYTHM).
            contentPadding = PaddingValues(
                start = MD.space4,
                end = MD.space4,
                top = MD.space2,
                bottom = MD.space6,
            ),
            verticalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            if (!snapshot.status.hasRepo && snapshot.status.scanned) {
                item(key = "no-repo") {
                    Note("This project is not a git repository yet. ⋮ → Initialize repository.")
                }
            }
            if (model.agent.isNotEmpty()) {
                item(key = "agent-header") {
                    BlockHeader(
                        title = "Agent edits (${model.agent.size})",
                        action = "Keep all",
                        // Keep, not Reject, is the bulk button offered: the
                        // list is what the agent proposed and the common
                        // answer is yes. Rejecting is per file, in the Diff,
                        // where the bytes are on screen.
                        onAction = { AgentSessions.keepEdits(emptyList()) },
                    )
                }
                // One card per block, rows inside it, hairlines between them —
                // rather than a card per row. A list of files is one object
                // with parts, and eleven separate cards down a 400dp column is
                // eleven borders where one is meant (docs/VISUAL.md, "Changes").
                item(key = "agent-rows") {
                    SeekerCard {
                        model.agent.forEachIndexed { index, row ->
                            if (index > 0) HairlineDivider()
                            AgentRow(row) { state.push(Route.Diff(row.path)) }
                        }
                    }
                }
            }
            if (model.git.isNotEmpty()) {
                item(key = "git-header") {
                    BlockHeader(
                        title = "Your changes (${model.git.size})",
                        action = if (model.stageAll.isEmpty()) null else "Stage all",
                        onAction = { perform({ session.stage(model.stageAll) }) },
                    )
                }
                item(key = "git-rows") {
                    SeekerCard {
                        model.git.forEachIndexed { index, row ->
                            if (index > 0) HairlineDivider()
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

        HairlineDivider()
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

/**
 * `Changes · 3 files +128 −47`, with the branch chip and the ⋮ in the bar.
 *
 * This is the route's own [SeekerTopBar] rather than a strip under a shared
 * one, which is why [SeekerShell]'s frame stopped drawing a bar for it: the
 * bar is where a branch belongs — it is the *identity* of what is being looked
 * at, in the same slot the Code destination puts the file — and a screen that
 * had both would spend 92dp on two rows of chrome before the first file.
 *
 * The subtitle counts what is on screen; the chip says which branch it is on
 * and opens the branch sheet; `↑2 ↓0` opens the remote menu and is drawn only
 * when there is a branch to sync.
 */
@Composable
private fun ChangesHeader(
    status: GitPanelState,
    busy: Boolean,
    subtitle: String?,
    onBack: () -> Unit,
    onOpenBranches: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onFetch: () -> Unit,
    onUnstageAll: () -> Unit,
    onInitRepository: () -> Unit,
) {
    var remoteMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val branch = status.branch
    SeekerTopBar(
        title = "Changes",
        subtitle = subtitle,
        onBack = onBack,
        actions = {
            SeekerChip(
                label = branch?.name ?: "no branch",
                onClick = onOpenBranches,
                enabled = status.hasRepo,
                leading = R.drawable.ic_ui_git_branch,
                // Capped, because a branch name is arbitrarily long and the
                // two controls to its right are not optional. The chip
                // ellipsises; the ⋮ never moves.
                modifier = Modifier.widthIn(max = BranchChipMax),
            )
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
                            .padding(horizontal = MD.space1)
                            .semantics {
                                contentDescription =
                                    "${branch.ahead} ahead, ${branch.behind} behind"
                            },
                    ) {
                        AheadBehind(R.drawable.ic_ui_arrow_up, branch.ahead)
                        AheadBehind(
                            R.drawable.ic_ui_arrow_down,
                            branch.behind,
                            Modifier.padding(start = MD.iconGap),
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
                    tint = mutedIcon,
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
        },
    )
}

/**
 * A block title with the block's one bulk action on its right.
 *
 * The title is the shared [SectionHeader] — 12sp caps tracked to 0.8sp, marked
 * as a `heading()` so TalkBack can jump between the blocks — and the private
 * copy of it that used to live here is gone, along with its
 * `editor.subheader.background` fill. There were three of these in the app at
 * three sizes and two colours (SettingsScreen.kt:276 was another), which is
 * what a component library exists to stop.
 */
@Composable
private fun BlockHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = MD.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(text = title, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** `~ programs/escrow/src/state.rs  +3 −1 ›` — one file the agent edited. */
@Composable
private fun AgentRow(row: AgentChangeRow, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(onClick = onOpen)
            .padding(horizontal = MD.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        Text(
            text = row.glyph,
            style = MonoSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        PathText(row.path, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        DiffStatLabel(added = row.added, removed = row.removed)
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
    val colours = LocalSeekerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .combinedClickable(onLongClick = onLongPress, onClick = onOpen)
            .padding(end = MD.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        // The checkbox is its own target: tapping the row opens the diff and
        // tapping the box stages, which are the two things a row is for and
        // must not be the same gesture.
        StageBox(mark = row.mark, onToggle = onToggleStage)
        Text(
            text = statusLetter(row.change),
            // The buffer face at caption size — the letter is git's own
            // vocabulary and it columns with the one above it, which
            // `FontFamily.Monospace` (the *system* mono over Material ink) did
            // not do beside the app's own face.
            style = MonoSmall,
            color = statusInk(row.change, colours, MaterialTheme.colorScheme.onSurfaceVariant),
        )
        PathText(row.change.path, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
        if (row.added != null && row.removed != null) {
            DiffStatLabel(added = row.added, removed = row.removed)
        }
        Chevron()
    }
}

/**
 * The ink a status letter is drawn in.
 *
 * `GitStatusColours.from(theme, …)` is what this replaces, and the swap is the
 * seam rather than a preference: that helper reads Zed's `created`, `deleted`
 * and `conflict` keys raw, which is correct *inside* the diff — where they
 * have to match the hunk fills — and wrong on a Material card, where Ayu Light
 * draws `created` at 2.11:1. These are the same hues solved against the
 * ground they are printed on (docs/VISUAL.md, "THE HYBRID" — inks in the
 * Material half are solved, inks in the Zed half are drawn raw).
 *
 * Dimming is off: an ignored file that git bothered to list is a file the user
 * asked about, and greying it here would hide it.
 */
private fun statusInk(
    change: GitChange,
    colours: SeekerColors,
    neutral: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.graphics.Color = when (paintedStatus(change)) {
    PanelStatus.Added, PanelStatus.Untracked -> colours.addedInk
    PanelStatus.Deleted -> colours.removedInk
    PanelStatus.Modified, PanelStatus.Renamed -> colours.warnInk
    PanelStatus.Conflicted -> colours.dangerInk
    PanelStatus.Ignored, PanelStatus.None -> neutral
}

/** `⚠ conflict — Anchor.toml ›`, its own block because it blocks the commit. */
@Composable
private fun ConflictRow(change: GitChange, onOpen: () -> Unit) {
    val ink = LocalSeekerColors.current.dangerInk
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(onClick = onOpen)
            .padding(horizontal = MD.space3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        SeekerIcon(
            icon = R.drawable.ic_ui_warning,
            // Decoration: the sentence beside it already says "conflict".
            contentDescription = null,
            tint = ink,
            size = IconSize.Marker,
        )
        Text(
            text = "conflict — ${change.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = ink,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = MD.space4, vertical = MD.space2),
    ) {
        Text(
            text = message.ifBlank { "Commit message…" },
            // Material prose, deliberately: a commit message is a sentence
            // somebody wrote, not a snippet. Only the diff is an island
            // (docs/VISUAL.md, "THE SEAM").
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Edit the commit message", onClick = onEdit)
                .touchTarget()
                .padding(vertical = MD.iconGap),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = MD.space1),
            horizontalArrangement = Arrangement.spacedBy(MD.space3),
        ) {
            // Stock buttons, in the pair Material means by them: the outlined
            // one is the ordinary answer and the filled one is the emphasised
            // answer, which is what `FlatButton(emphasis = true)` was drawing
            // by hand out of `element.selected`.
            OutlinedButton(
                onClick = { if (!busy) onCommit(false) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (stagedCount == 0) "Commit" else "Commit $stagedCount",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = { if (!busy) onCommit(true) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = "Commit & Push", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * The path, in the buffer face.
 *
 * The *end* of a path is what identifies it — `…/instructions/initialize.rs`
 * — so the middle is what goes (docs/UI.md, "Orientation"). It is [MonoBody]
 * rather than `FontFamily.Monospace`: the second is the system's mono, which
 * matches neither the app's face nor the buffer's, and this row was one of the
 * eleven sites drawing it (docs/VISUAL.md, "THE SEAM").
 */
@Composable
private fun PathText(path: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Text(
        text = path,
        style = MonoBody,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = modifier,
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
 * A real `TriStateCheckbox`, which is the control Material has for exactly
 * this: [StageMark.Partial] — a file edited, staged, and *edited again* — is
 * `Indeterminate`, and it is the state that matters most, because committing
 * now would commit the older of the two versions. It used to be hand-drawn out
 * of two Lucide glyphs (an empty box with a dot centred in it) because the
 * pinned icon set has no half-filled square; the stock control has one, plus
 * the animation between the three and the `Checkbox` role in semantics.
 *
 * Its own target, not the row's: tapping the row opens the diff and tapping
 * the box stages, which are the two things a row is for and must not be the
 * same gesture. `TriStateCheckbox` brings its own 48dp minimum with it.
 *
 * The `onClick` is *always* a toggle, never a walk through three states — the
 * third one is a fact about the file, not an answer the user can give — so
 * partial stages like unstaged, which is what the caller already does.
 */
@Composable
private fun StageBox(mark: StageMark, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    TriStateCheckbox(
        state = when (mark) {
            StageMark.Staged -> ToggleableState.On
            StageMark.Partial -> ToggleableState.Indeterminate
            StageMark.Unstaged -> ToggleableState.Off
        },
        onClick = onToggle,
        // The box is the only thing that says the state, so it carries the
        // words rather than leaving a screen reader to say "partially checked".
        modifier = modifier.semantics { contentDescription = mark.spoken },
    )
}

/** One half of `↑2 ↓0`: the arrow, drawn, and its number. */
@Composable
private fun AheadBehind(@DrawableRes icon: Int, count: Int, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        SeekerIcon(icon = icon, contentDescription = null, tint = tint, size = IconSize.Marker)
        Text(
            text = "$count",
            // Tabular, because both numbers move on every fetch and a `1`
            // narrower than a `0` makes the pair shimmy (Type.kt).
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularNums),
            color = tint,
        )
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = MD.space6),
    )
}

/**
 * `3 files · +128 −47` — the bar's subtitle, and the one place on the screen
 * that says how big the whole change is.
 *
 * Over every block, agent edits included, because the question it answers is
 * "how much is different" and not "how much is staged". A file git has no
 * numbers for — an untracked one — counts as a file and adds nothing to the
 * totals, which is the same thing its row does.
 *
 * Pure and internal so the wording is checkable on the host, like every other
 * sentence this screen prints.
 */
internal fun changesSummary(model: ChangesModel, counts: Map<String, DiffCount>): String? {
    val files = model.agent.size + model.git.size + model.conflicts.size
    if (files == 0) return null
    val added = model.agent.sumOf { it.added } +
        model.git.sumOf { counts[it.change.path]?.added ?: 0 } +
        model.conflicts.sumOf { counts[it.path]?.added ?: 0 }
    val removed = model.agent.sumOf { it.removed } +
        model.git.sumOf { counts[it.change.path]?.removed ?: 0 } +
        model.conflicts.sumOf { counts[it.path]?.removed ?: 0 }
    val noun = if (files == 1) "file" else "files"
    if (added == 0 && removed == 0) return "$files $noun"
    return "$files $noun · +$added \u2212$removed"
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

/**
 * 128dp — the branch chip in the bar, before it ellipsises.
 *
 * `feature/an-arbitrarily-long-name` is a real branch name and the ⋮ beside it
 * is not optional; the chip is the thing that gives way.
 */
private val BranchChipMax = 128.dp

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
