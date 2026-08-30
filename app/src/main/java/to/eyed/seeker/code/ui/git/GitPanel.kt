package to.eyed.seeker.code.ui.git

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import to.eyed.seeker.code.core.Commit
import to.eyed.seeker.code.core.CommitDetails
import to.eyed.seeker.code.core.CommitPage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.GitAskpass
import to.eyed.seeker.code.core.GitChange
import to.eyed.seeker.code.core.GitFileStatus
import to.eyed.seeker.code.core.GitPanelState
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.RemoteOpResult
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.StashKind
import to.eyed.seeker.code.core.pollVersion
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.workspace.Activities
import to.eyed.seeker.code.ui.workspace.ActivityTarget
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.workspace.GitStatusColours
import to.eyed.seeker.code.ui.workspace.onSecondaryClick
import to.eyed.seeker.code.ui.workspace.GitFileStatus as PanelStatus

/**
 * Whether this build can show a git panel at all.
 *
 * Everything the panel does runs the `git` inside the Linux userland, so the
 * `play` edition — which has no userland and never will — is not offered it,
 * greyed or otherwise. That is the same rule the clone action already follows:
 * an editor should not advertise what it cannot ever do.
 */
val isGitPanelSupported: Boolean
    get() = Userland.backend.isSupported

/**
 * Zed's `git_panel.default_width` (assets/settings/default.json:997) — what
 * the workspace budgets for this dock when it decides on a layout.
 */
internal val GitPanelDockWidth = 360.dp

/**
 * Every list surface in the panel — entry rows, section headers, empty-section
 * notes — is `list_item_height()` = `rems(1.75)` = 28px (git_panel.rs:7257-7259).
 */
private val ListItemHeight = 28.dp

/**
 * The bar above the change list is `min_h(Tab::container_height)` = `Base32` =
 * 32px (git_panel.rs:5787; ui/src/components/tab.rs:83-85), and the tab strip
 * at the top of the panel is the same `Tab::container_height` (git_panel.rs:6303).
 */
private val BarHeight = 32.dp

/** Rows are `pl_2p5` / `pr_1` — 10px in, 4px out (git_panel.rs:7690-7691). */
private val RowStartPadding = 10.dp
private val RowEndPadding = 4.dp

/** Inputs are `rounded_md` = 6px (search_bar.rs:78). */
private val FieldRadius = 6.dp

/** The engine debounces git by 400 ms; polling faster only costs JNI calls. */
private const val POLL_MS = 250L

/**
 * The Fetch From picker's extra row when there is more than one remote —
 * `FetchOptions::All.name()` (repository.rs:664-669; git_panel.rs:3653-3655).
 */
private const val FetchAllRemotes = "Fetch all remotes"

/** How far PageUp and PageDown move the selection. */
private const val PAGE_ROWS = 10

/**
 * Zed's commit box is exactly six lines of the commit font and grows no
 * further — `MAX_PANEL_EDITOR_LINES = 6`, pinned as both `min_lines` and
 * `max_lines` (git_panel.rs:1080, 1091-1095) — so the file list keeps the
 * panel.
 */
private const val CommitEditorLines = 6

/**
 * Zed pins the commit editor's type to 12px in its own defaults
 * (`git_commit_buffer_font_size`, assets/settings/default.json:81); the
 * buffer-size fallback in settings.rs:446-451 never applies at defaults.
 */
private const val CommitBufferFontSize = 12f

/**
 * gpui's φ — the `buffer_line_height: "comfortable"` the commit editor is laid
 * out in (theme_settings/src/settings.rs:390).
 */
private const val BufferLineHeight = 1.618034f

/**
 * The git panel — Zed's `crates/git_ui/src/git_panel.rs`, in the shape a phone
 * can hold: the changed files in their sections, a checkbox each for staging, a
 * commit message and a commit button.
 *
 * A dock beside the editor on a wide screen and the whole work area on a
 * compact one, which is the split project search already makes.
 *
 * What it deliberately does not have is Zed's diff view: opening a row opens
 * the *file*, and the gutter beside it is where its hunks are. Side-by-side is
 * wrong on a phone, and a unified diff of a whole repository is a second
 * editor's worth of surface for a wave that is building three other things.
 *
 * Two of the actions here destroy work, so both are guarded. Discard confirms,
 * names the file, and says which of its meanings applies — restore, trash, or a
 * rename undone — and it cannot be reached in one tap from anywhere. A row it
 * cannot state a promise for, a conflict above all, it refuses instead. Commit
 * refuses an empty message rather than making an empty commit.
 */
@Composable
fun GitPanel(
    project: ProjectSession,
    /**
     * Bumped by the workspace whenever the panel's chord is pressed, so pressing
     * it again puts the keyboard back on the file list rather than doing nothing.
     */
    focusToken: Int,
    onOpenFile: (String) -> Unit,
    /** Open a diff view — one file's, or the whole project's for null. */
    onOpenDiff: (String?) -> Unit,
    /**
     * Open a conflicted file on its first conflict, where the editor's
     * conflict header offers the resolution. What a conflicted row does on
     * tap, Enter and its "Resolve" affordance: Zed opens the diff for a
     * click on any change, but a diff of a file full of markers answers a
     * question nobody with a conflict is asking.
     */
    onResolveConflict: (String) -> Unit,
    /**
     * Open the branch diff against the given base branch — the clean tree's
     * "View Branch Diff", Zed's `DeployBranchDiff` (branch_diff.rs:80-137).
     */
    onOpenBranchDiff: (String) -> Unit,
    /**
     * Open one commit as a diff tab — Zed's `CommitView::open`, which is what
     * the footer's subject line dispatches (git_panel.rs:6183-6197). Takes
     * the sha and the subject the tab is titled by.
     */
    onOpenCommit: (String, String) -> Unit,
    /** Open the commit graph, which is a view of the whole repository. */
    onOpenGraph: () -> Unit,
    /** Open the branch picker — what the header's branch button dispatches. */
    onSwitchBranch: () -> Unit,
    /** Open the stash picker — Zed's `git::ViewStash`, the Stash menu's last row. */
    onViewStash: () -> Unit = {},
    onDismiss: () -> Unit,
    /**
     * A command the palette ran on the panel's behalf — see [GitPanelRequest].
     * Handled once the first `git status` has landed, and answered with
     * [onRequestHandled] so the same ask cannot run twice.
     */
    request: GitPanelRequest? = null,
    onRequestHandled: () -> Unit = {},
    /**
     * The panel reporting whether it — or anything in it — holds the
     * keyboard. The workspace's root key pass gates go-to-line and the
     * editor-tab digits on it, so ctrl-g can be this panel's leader.
     */
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    val session = remember(project) { GitSession(project) }

    var state by remember(project) { mutableStateOf(GitPanelState()) }
    /**
     * The commit HEAD names, from the engine's cached status run. It is the
     * history tab's staleness key: `git log` needs re-running when *this*
     * moves, not when the status snapshot is replaced — which happens on
     * every save while the panel is open.
     */
    var head by remember(project) { mutableStateOf<String?>(null) }
    /**
     * The commit HEAD names, whole — the footer's one line of history, Zed's
     * `most_recent_commit` (repository.rs:512-518). Reloaded when [head]
     * moves, which is exactly when the answer changes.
     */
    var lastCommit by remember(project) { mutableStateOf<Commit?>(null) }
    // Drafts live in project-keyed objects backed by SharedPreferences (see
    // GitDrafts.kt); the binding installs this project's id → path key and
    // seeds the maps from disk, and it must run before anything below reads a
    // draft — which the order of these remembers is.
    val draftContext = LocalContext.current
    remember(project) { GitDraftStore.bind(draftContext, project.id, project.rootPath) }
    // Seeded from, and written back to, the draft the panel was closed with:
    // Escape and — on a phone — opening a file both take the panel out of the
    // composition, and a commit message is the one thing here nobody wants to
    // type twice. Cleared on a commit that succeeded, and nowhere else.
    var message by remember(project) {
        val draft = CommitDrafts.of(project.id)
        // Caret at the end of what was already typed, which is where the user
        // left it and where they expect to carry on.
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    var selected by remember(project) { mutableIntStateOf(-1) }
    /**
     * The mutation single-flight — busy, the error strip, the success notice,
     * the remote spinner — lives in [GitOps], not the composition: the panel
     * is removed by Escape, by a compact screen opening a file, by a fold
     * crossing the width threshold, while the git command under a running
     * pull finishes regardless. Re-attaching here is what lets a reopened
     * panel show the spinner for — and the outcome of — an operation it did
     * not start, and what keeps the branch picker from running a checkout
     * through the middle of it.
     */
    val ops = remember(project) { GitOps.of(project.id) }
    var confirming by remember(project) { mutableStateOf<GitChange?>(null) }
    /**
     * A stash push waiting for its message — Zed's `StashMessageModal`
     * (git_panel.rs:231-300), raised by Stash All / Tracked / Staged and
     * answered with `git stash push` of that kind.
     */
    var stashAsk by remember(project) { mutableStateOf<StashKind?>(null) }
    /**
     * An uncommit stopped at the door because some remote already holds the
     * commit — Zed's "Are you sure?" prompt (git_panel.rs:3209-3230). Carries
     * what the dialog needs to finish the job; see [PendingUncommit].
     */
    var pendingUncommit by remember(project) { mutableStateOf<PendingUncommit?>(null) }
    /**
     * The identity form, shown when git refuses to commit without one. Not a
     * setting and not a dialog: it is the answer to the error immediately
     * above it, and it goes away as soon as it is answered.
     */
    /** Zed's two tabs: what has changed, and what has been committed. */
    var tab by remember(project) { mutableStateOf(GitPanelTab.Changes) }
    var history by remember(project) { mutableStateOf<CommitPage?>(null) }
    /** The commit whose detail is expanded, by sha. */
    var openCommit by remember(project) { mutableStateOf<CommitDetails?>(null) }
    /**
     * The sha the user last asked the History tab to expand. Loads race —
     * two quick clicks run two `git show`s, and the *larger* commit answers
     * last — so an arriving detail is applied only while it is still the one
     * asked for, the guard [setAmendPending] and GitGraphPane already keep.
     */
    var requestedCommit by remember(project) { mutableStateOf<String?>(null) }
    var identityWanted by remember(project) { mutableStateOf(false) }
    var identityName by remember(project) { mutableStateOf(TextFieldValue()) }
    var identityEmail by remember(project) { mutableStateOf(TextFieldValue()) }
    var messageFocused by remember { mutableStateOf(false) }
    // The split button's three toggles, seeded from the objects that outlive
    // the composition — the panel is removed by Escape, and losing a pending
    // amend that way would quietly turn the next Ctrl+Enter into a plain
    // commit. Every write goes back through the object.
    var amendPending by remember(project) { mutableStateOf(AmendDrafts.pending(project.id)) }
    var signoffEnabled by remember(project) { mutableStateOf(CommitToggles.signoff) }
    var skipHooks by remember(project) { mutableStateOf(CommitToggles.skipHooks) }
    /**
     * Zed's pre-flight warnings are blocking prompts with a single OK —
     * `window.prompt(PromptLevel::Warning, …, ["OK"])` (git_panel.rs:3072-3079,
     * 3109-3112) — not toasts, so ours are a dialog and not the error strip.
     */
    var warning by remember(project) { mutableStateOf<String?>(null) }
    /** A "which remote?" question waiting on the user — see [RemotePickerRequest]. */
    var remotePicker by remember(project) { mutableStateOf<RemotePickerRequest?>(null) }
    /**
     * When ctrl-g armed a pending chord, or null — the leader state the key
     * handler resolves against and the hint chip is drawn from. Deliberately
     * not keyed on the project: a half-typed chord has no business surviving
     * anything, and it does not.
     */
    var chordArmedAt by remember { mutableStateOf<Long?>(null) }

    val listState = rememberLazyListState()
    // History's own scroll survives a round trip through the Changes tab.
    val historyListState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    // Map reads, so once per theme rather than once per row per frame.
    val colours = remember(theme) {
        GitStatusColours.from(theme, theme.color("text"), theme.color("text.muted"))
    }
    LaunchedEffect(focusToken) { listFocus.requestFocus() }

    // One counter, polled; the parse happens only when it moves. Reading the
    // counter is itself a JNI call that schedules a `git status`, so it is off
    // the main thread too — cheap, but it takes the engine's locks. Gated on
    // the lifecycle for the same reason: a backgrounded app must not keep
    // scheduling `git status` runs under proot.
    ResumedEffect(session) {
        pollVersion(
            intervalMs = POLL_MS,
            version = { session.version },
            read = { session.state() to CoreBridge.gitHead(project.id) },
            apply = { (newState, newHead) ->
                state = newState
                head = newHead
            },
        )
    }

    // Loaded when the History tab is opened, and again whenever the commit
    // graph could have moved — a commit made in this panel changes HEAD, so
    // it appears at the top without asking the user to come back, while a
    // save only replaces the status snapshot and reloads nothing. The branch
    // is keyed whole — name, ahead/behind, upstream — because a fetch or push
    // moves upstream refs without touching HEAD, and the ref chips beside the
    // subjects would otherwise go stale. A pure `git tag` moves neither and
    // still does not reload, which is the one residual this key accepts. When
    // the engine cannot name HEAD, the snapshot itself is the key, which is
    // the old trigger: eager, but never stale.
    LaunchedEffect(session, tab, state.branch, head ?: state) {
        if (tab != GitPanelTab.History) return@LaunchedEffect
        history = withContext(Dispatchers.IO) { session.log() }
    }

    // The footer's subject line, from the same log API at limit 1. Keyed by
    // HEAD because that is the only thing that changes the answer; no HEAD —
    // an unborn branch, no userland — is no commit, and no footer row.
    LaunchedEffect(session, head) {
        val sha = head
        lastCommit = if (sha == null) {
            null
        } else {
            withContext(Dispatchers.IO) { session.log(limit = 1).commits.firstOrNull() }
        }
    }

    val rows = remember(state) { gitPanelRows(state) }
    // A selection is an index into a list that grows and shrinks under it, so
    // it is clamped here rather than trusted.
    val selection = selected.takeIf { it in rows.indices } ?: -1
    val selectedChange = (rows.getOrNull(selection) as? GitPanelRow.FileRow)?.change

    /**
     * Every command: off the main thread, one at a time — across every
     * surface, which is [GitOps]'s job — and whatever git said about it shown
     * rather than logged.
     *
     * [onSuccess] runs back on the main thread, so a command that clears a
     * field does it here and not from an IO dispatcher. Both callbacks run in
     * [GitOps]'s own scope, not the composition's: a pull outlives a panel
     * dismissed mid-flight, and its outcome must still land in the strip a
     * reopened panel reads.
     */
    fun perform(
        action: suspend () -> String?,
        onFailure: (String) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        val started = GitOps.run(project.id, action) { failure ->
            ops.error = failure
            if (failure == null) onSuccess() else onFailure(failure)
            // The list still shows what git said *before* the command: the
            // engine invalidates its cache and re-runs `git status` behind a
            // debounce, so the row moves a fraction of a second later, when the
            // poll above sees the counter change. Asking here is what schedules
            // that run rather than waiting for the next poll to.
            state = withContext(Dispatchers.Default) { session.state() }
        }
        // One at a time, and *said* rather than swallowed: a `git add` inside
        // proot is easily a second, and a Ctrl+Enter that vanished into it
        // looks exactly like a keybinding that does not work.
        if (!started) {
            ops.error = "Still running the last git command…"
            return
        }
        ops.error = null
        ops.notice = null
    }

    fun toggleStaged(change: GitChange) {
        if (change.conflicted) return
        // A file that is staged *and* modified again stages the rest of it,
        // which is what its checkbox showing "partly staged" invites. Only a
        // wholly staged file unstages.
        if (change.staged != null && change.unstaged == null) {
            perform({ session.unstage(listOf(change.path)) })
        } else {
            perform({ session.stage(listOf(change.path)) })
        }
    }

    /**
     * Zed's `git::StageAll` / `git::UnstageAll` (git_panel.rs:2602-2608),
     * whose menu asides name the commands they stand for: `git add --all` and
     * `git reset` (git_panel.rs:5741-5743). Ours go by path through the same
     * engine calls a row's checkbox uses, so the status cache and the poll
     * behave exactly as they do for a single file.
     */
    fun stageAll() {
        val paths = stageAllPaths(state.entries)
        if (paths.isEmpty()) return
        perform({ session.stage(paths) })
    }

    fun unstageAll() {
        val paths = unstageAllPaths(state.entries)
        if (paths.isEmpty()) return
        perform({ session.unstage(paths) })
    }

    /**
     * Zed's `git_panel::ActivateChangesTab` / `ActivateHistoryTab`
     * (default-linux.json:1010-1011), and the tab strip's own click.
     * Re-selecting the open tab is a no-op: it must not throw away the
     * expanded commit the user is reading.
     */
    fun selectTab(next: GitPanelTab) {
        if (next == tab) return
        tab = next
        openCommit = null
        requestedCommit = null
    }

    /**
     * Leave amend mode, or enter it — Zed's `set_amend_pending`
     * (git_panel.rs:8029-8049). Entering saves whatever is typed as the
     * original message and replaces it with HEAD's full message
     * (`load_last_commit_message`, git_panel.rs:2971-2991); leaving — by the
     * Cancel button, by unticking the menu entry, or by the amend commit
     * landing — puts the saved draft back.
     */
    fun setAmendPending(on: Boolean) {
        if (on == amendPending) return
        if (on) {
            val sha = head ?: return
            AmendDrafts.enter(project.id, message.text)
            amendPending = true
            scope.launch {
                val details = withContext(Dispatchers.IO) { session.commitDetails(sha) }
                val last = details?.message?.trimEnd('\n')
                // Only while the amend is still pending: the HEAD message
                // arriving after a quick Cancel must not stamp on the restored
                // draft.
                if (last != null && AmendDrafts.pending(project.id)) {
                    message = TextFieldValue(last, TextRange(last.length))
                    CommitDrafts.put(project.id, last)
                }
            }
        } else {
            val original = AmendDrafts.original(project.id)
            AmendDrafts.clear(project.id)
            amendPending = false
            message = TextFieldValue(original, TextRange(original.length))
            CommitDrafts.put(project.id, original)
        }
    }

    /**
     * Zed's `commit_changes` (git_panel.rs:3055-3148), which is what both the
     * button and Ctrl+Enter run: commit the index when anything is staged;
     * otherwise stage every *tracked* change first — never the untracked ones —
     * and commit that, which is what the "Commit Tracked" label promises.
     */
    fun commit() {
        // Zed's guard and its words (git_panel.rs:3072-3079). Ours has no
        // staged half of a conflict — staging the resolution clears the
        // conflict — so any conflict at all is an unstaged one.
        if (state.conflicts.isNotEmpty()) {
            warning = "There are still conflicts. You must stage these before committing"
            return
        }
        val text = message.text
        // Refused here as well as in the engine, so the button can say why
        // before it is pressed rather than after.
        if (text.isBlank()) {
            ops.error = "Write a commit message first"
            return
        }
        val amend = amendPending
        val hasStaged = state.staged.isNotEmpty()
        val tracked = if (hasStaged) emptyList() else trackedCommitPaths(state.entries)
        // Zed's words (git_panel.rs:3109-3112) — and amend is excused, because
        // folding a better message into HEAD changes nothing on disk.
        if (!hasStaged && tracked.isEmpty() && !amend) {
            warning = "No changes to commit"
            return
        }
        // The message is cleared only on success: one the user would have to
        // retype because git refused the commit is the wrong thing to lose.
        perform(
            action = {
                if (tracked.isNotEmpty()) {
                    // Stage-then-commit, as Zed's stage_entries-before-commit
                    // (git_panel.rs:3114-3122); a stage that failed is the
                    // whole answer, and the commit is not attempted after it.
                    val failure = session.stage(tracked)
                    if (failure != null) return@perform failure
                }
                session.commit(
                    text,
                    amend = amend,
                    signoff = signoffEnabled,
                    noVerify = skipHooks,
                )
            },
            onFailure = { failure ->
                // A fresh Debian has no git identity, guesses one from the
                // hostname and refuses to use it. Every commit in a new
                // userland hits this, and the error alone leaves the user to
                // work out that the fix is two `git config` commands in a
                // shell. Offer the form instead — and prefill it, in case git
                // has half of it already.
                if (needsIdentity(failure)) {
                    identityWanted = true
                    scope.launch {
                        val known = withContext(Dispatchers.IO) { session.identity() }
                        if (known != null) {
                            if (identityName.text.isEmpty() && known.name.isNotBlank()) {
                                identityName = TextFieldValue(known.name)
                            }
                            if (identityEmail.text.isEmpty() && known.email.isNotBlank()) {
                                identityEmail = TextFieldValue(known.email)
                            }
                        }
                    }
                }
            },
        ) {
            // Skip Hooks is spent by the commit it was armed for
            // (git_panel.rs:3131); Signoff, deliberately, is not.
            skipHooks = false
            CommitToggles.skipHooks = false
            if (amend) {
                // Leaving amend mode is what restores the pre-amend draft:
                // Zed does not clear the editor in the amend branch
                // (git_panel.rs:3132-3133).
                setAmendPending(false)
            } else {
                message = TextFieldValue()
                CommitDrafts.clear(project.id)
            }
        }
    }

    /**
     * The `git::Amend` action, two-phase as in Zed (git_panel.rs:2944-2963):
     * the first Ctrl+Shift+Enter only *enters* amend mode — the button relabels
     * and HEAD's message fills the editor for editing — and the second performs
     * the commit. Nothing to amend in a repository with no commits.
     */
    fun amend() {
        if (head == null) return
        if (amendPending) commit() else setAmendPending(true)
    }

    fun toggleSignoff() {
        signoffEnabled = !signoffEnabled
        CommitToggles.signoff = signoffEnabled
    }

    fun toggleSkipHooks() {
        skipHooks = !skipHooks
        CommitToggles.skipHooks = skipHooks
    }

    /**
     * A remote command through [perform]: one at a time, the split button's
     * spinner while it runs, and the outcome *said* — Zed's toast sentence on
     * success ([formatRemoteOutput]), git's own refusal in the error strip on
     * failure, where Zed shows a "git {fetch|pull|push} failed" toast with a
     * log view (notifications.rs:36-73).
     *
     * A credential question along the way — an HTTPS token, a key's
     * passphrase, a host key — surfaces in [AskpassDialog] while the command
     * blocks, under the title Zed gives its modal for the same command
     * ([askpassTitle]); a cancelled dialog is git failing with its own
     * words, in the strip like any other refusal.
     */
    fun runRemote(action: RemoteAction, command: () -> RemoteOpResult) {
        var toast: RemoteToast? = null
        val activity = "git-remote:${project.id}"
        perform(
            action = {
                // Set here, past [perform]'s busy guard, so a refused second
                // command never claims the spinner.
                ops.pendingRemote = true
                // And in the status bar, where a fetch over a slow network is
                // otherwise invisible from anywhere but this panel — Zed's
                // activity indicator carries git work too.
                Activities.begin(activity, "git ${action.name}…", ActivityTarget.GitPanel)
                val result = GitAskpass.during(askpassTitle(action)) { command() }
                if (result.ok) {
                    toast = formatRemoteOutput(action, result)
                    null
                } else {
                    // git's own words, not the bare exit status: "git exited
                    // with 2" names the strip, [remoteFailureMessage] the
                    // reason — Zed shows the command's output the same way
                    // (notifications.rs:36-73).
                    remoteFailureMessage(action, result)
                }
            },
            onFailure = {
                ops.pendingRemote = false
                Activities.end(activity)
            },
            onSuccess = {
                ops.pendingRemote = false
                Activities.end(activity)
                ops.notice = toast?.message
            },
        )
    }

    /**
     * Zed's `get_remote` (git_panel.rs:4130-4175): the branch's configured
     * remote for that direction first — skipped when [alwaysSelect], which is
     * what makes Push To always ask — then the whole `git remote -v` list,
     * where none at all is [onNone]'s problem, exactly one picks itself with
     * no modal, and several go to the picker (picker_prompt.rs:27-31).
     */
    fun resolveRemote(
        branch: String,
        forPush: Boolean,
        alwaysSelect: Boolean,
        onNone: () -> Unit,
        onRemote: (String) -> Unit,
    ) {
        scope.launch {
            val configured = if (alwaysSelect) {
                null
            } else {
                withContext(Dispatchers.IO) { session.branchRemote(branch, forPush) }
            }
            if (configured != null) {
                onRemote(configured)
                return@launch
            }
            val listing = withContext(Dispatchers.IO) { session.remotes() }
            if (listing.error != null) {
                ops.error = listing.error
                return@launch
            }
            val names = listing.remotes.map { it.name }
            when {
                names.isEmpty() -> onNone()
                names.size == 1 -> onRemote(names.first())
                else -> remotePicker = RemotePickerRequest(
                    // Zed's prompt — pulls included: the same helper serves
                    // both directions (git_panel.rs:4160-4166).
                    prompt = "Pick which remote to push to",
                    options = names,
                    onPick = onRemote,
                )
            }
        }
    }

    /**
     * Zed's `git::Fetch` and `git::FetchFrom` (git_panel.rs:3637-3732):
     * [fetchAll] is the plain Fetch — `git fetch --all` — while Fetch From
     * lists the remotes, appends a "Fetch all remotes" row when there are
     * several (git_panel.rs:3653-3655), and fetches the one picked. No
     * remotes at all is silently nothing, as in Zed (git_panel.rs:3705-3707).
     */
    fun fetch(fetchAll: Boolean) {
        if (fetchAll) {
            runRemote(RemoteAction.Fetch(null)) { session.fetch(null) }
            return
        }
        scope.launch {
            val listing = withContext(Dispatchers.IO) { session.remotes() }
            if (listing.error != null) {
                ops.error = listing.error
                return@launch
            }
            val names = listing.remotes.map { it.name }
            when {
                names.isEmpty() -> {}
                names.size == 1 -> runRemote(RemoteAction.Fetch(names.first())) {
                    session.fetch(names.first())
                }
                else -> remotePicker = RemotePickerRequest(
                    // Zed's prompt (git_panel.rs:3660); the extra row's label
                    // is `FetchOptions::All.name()` (repository.rs:664-669).
                    prompt = "Pick which remote to fetch",
                    options = names + FetchAllRemotes,
                    onPick = { choice ->
                        val remote = choice.takeUnless { it == FetchAllRemotes }
                        runRemote(RemoteAction.Fetch(remote)) { session.fetch(remote) }
                    },
                )
            }
        }
    }

    /**
     * Zed's `git::Pull` / `git::PullRebase` (git_panel.rs:3830-3892). The
     * branch name joins the argv only when the branch has no upstream, and
     * the engine is what knows that.
     */
    fun pull(rebase: Boolean) {
        // No branch, no pull — the handler's own early-return (git_panel.rs:3837).
        val branch = state.branch?.name ?: return
        resolveRemote(
            branch = branch,
            forPush = false,
            alwaysSelect = false,
            // Pull with no remotes is silently nothing (git_panel.rs:3850-3854).
            onNone = {},
        ) { remote ->
            runRemote(RemoteAction.Pull(remote)) { session.pull(branch, remote, rebase) }
        }
    }

    /**
     * Push, or publish a branch that has no upstream yet — and Force Push and
     * Push To, which are the same command with a flag or a question in front
     * (git_panel.rs:3894-3986).
     *
     * Zed's own button says "Publish" for the no-upstream case and shows the
     * push for the first; the difference is `-u`, and it is the difference
     * between "send these commits" and "make this branch exist on the remote".
     * [force] is `--force-with-lease`, never plain `--force`, and the lease is
     * the only safety Zed puts in front of it.
     */
    fun push(force: Boolean = false, selectRemote: Boolean = false) {
        val branch = state.branch ?: return
        // The handler's early-return on a detached HEAD (git_panel.rs:3908).
        val name = branch.name ?: return
        resolveRemote(
            branch = name,
            forPush = true,
            alwaysSelect = selectRemote,
            onNone = {
                // Zed git_panel.rs:3941
                ops.error = "No remote available to push to. Add a remote to be able to publish changes."
            },
        ) { remote ->
            runRemote(RemoteAction.Push(name, remote)) {
                session.push(
                    name,
                    remote,
                    // Publish and Republish both: no upstream, or an upstream
                    // whose remote branch is gone (git_panel.rs:3920-3929).
                    // Force wins over it in the argv, exactly as in Zed's
                    // options (repository.rs:2717-2727).
                    setUpstream = !branch.hasUpstream || branch.upstreamGone,
                    force = force,
                )
            }
        }
    }

    /**
     * One dispatcher for every keyboard into the panel: the second key of a
     * ctrl-g chord, and a palette run arriving as [request]. Zed's fetch,
     * push and pull actions share one workspace registration the same way
     * (git_ui.rs:193-241), which is what keeps the buttons, the chords and
     * the palette from drifting apart.
     */
    fun runPanelCommand(command: GitPanelCommand) {
        when (command) {
            GitPanelCommand.Fetch -> fetch(fetchAll = true)
            GitPanelCommand.Push -> push()
            GitPanelCommand.Pull -> pull(rebase = false)
            GitPanelCommand.ForcePush -> push(force = true)
            GitPanelCommand.PullRebase -> pull(rebase = true)
            GitPanelCommand.Diff -> onOpenDiff(null)
            GitPanelCommand.StageAll -> stageAll()
            GitPanelCommand.UnstageAll -> unstageAll()
            GitPanelCommand.StashAll -> stashAsk = StashKind.All
            GitPanelCommand.StashTracked -> stashAsk = StashKind.Tracked
            GitPanelCommand.StashStaged -> stashAsk = StashKind.Staged
            // Zed's `git::StashPop` / `StashApply` take the newest entry
            // (git_panel.rs:2897-2941); the picker is where one is chosen.
            GitPanelCommand.StashPop -> perform({ session.stashPop() })
            GitPanelCommand.StashApply -> perform({ session.stashApply() })
        }
    }

    /** Save the identity, then commit — which is what the user asked for. */
    fun saveIdentity() {
        perform({ session.setIdentity(identityName.text, identityEmail.text) }) {
            identityWanted = false
            commit()
        }
    }

    /**
     * The reset, pinned to [sha]: the engine's is a blind `git reset --soft
     * HEAD^`, so HEAD is re-read from git itself first and the reset refused
     * when it no longer names the commit the reads described — a commit landed
     * from a terminal, or while the pushed-commit dialog sat open. Blocking;
     * call it from [perform]'s action.
     */
    fun pinnedUncommit(sha: String): String? {
        val fresh = session.log(limit = 1).commits.firstOrNull()?.sha
        return uncommitPinRefusal(expected = sha, fresh = fresh) ?: session.uncommit()
    }

    /**
     * The commit box refilled with the message the commit held, which is
     * Zed's own order: [prior] was read *before* the reset, while HEAD still
     * named the commit (git_panel.rs:3157-3183).
     */
    fun refillCommitMessage(prior: String?) {
        val text = prior?.trimEnd('\n').orEmpty()
        if (text.isNotEmpty()) {
            message = TextFieldValue(text, TextRange(text.length))
            CommitDrafts.put(project.id, text)
        }
    }

    /**
     * The reset itself, past every question. Pinned to the sha the
     * confirmation was about — the dialog can sit open while a commit lands,
     * and `HEAD^` would then name the wrong parent.
     */
    fun runUncommit(request: PendingUncommit) {
        perform({ pinnedUncommit(request.sha) }) {
            refillCommitMessage(request.priorMessage)
        }
    }

    /**
     * Zed's `GitPanel::uncommit` (git_panel.rs:3150-3192): the old message and
     * the pushed evidence are read first, and only a commit some
     * `remote/branch` already holds gets a confirmation — nothing pushed
     * proceeds silently, exactly as there (git_panel.rs:3205-3230).
     *
     * The whole task — reads and reset — is one [perform] window, as Zed
     * holds `pending_commit` across it (git_panel.rs:3147, 3191). With the
     * reads outside it, a second tap during them queued a second soft reset,
     * and a commit landing mid-read was reset in place of the one the
     * evidence described.
     */
    fun uncommit() {
        var ask: PendingUncommit? = null
        var prior: String? = null
        perform(
            action = {
                // HEAD from git itself, not the status cache: the sha the
                // reset is pinned to must be the commit git holds *now*, and
                // the poll's copy can be a commit behind. Zed asks for "HEAD"
                // by name; the engine's revision check is hex-only
                // (git_history.rs:166-173), so it goes by sha here. None — an
                // unborn branch — leaves nothing to uncommit.
                val sha = session.log(limit = 1).commits.firstOrNull()?.sha
                    ?: return@perform null
                prior = session.commitDetails(sha)?.message
                val pushed = session.headPushedRemotes()
                if (pushed.isEmpty()) {
                    pinnedUncommit(sha)
                } else {
                    ask = PendingUncommit(pushed, prior, sha)
                    null
                }
            },
            onSuccess = {
                // Either the question, or the prefill the reset earned —
                // which is a no-op when nothing was reset.
                ask?.let { pendingUncommit = it } ?: refillCommitMessage(prior)
            },
        )
    }

    /**
     * The empty state's "Initialize Repository" — `git init`, with Zed's
     * branch-name rule inside ([GitSession.initRepository]). No follow-up
     * needed here: the engine invalidates its cache, and the poll redraws
     * this as an ordinary clean repository.
     */
    fun initRepository() {
        perform({ session.initRepository() })
    }

    /**
     * The clean tree's way out — Zed's `DeployBranchDiff` (branch_diff.rs:
     * 80-137): resolve the repository's default branch as the base and open
     * the branch-vs-base diff; a repository with no default branch falls back
     * to the plain project diff, exactly as Zed falls back to `ProjectDiff`.
     * The button only shows on a clean tree, where the project diff is always
     * empty — which is why this must not open it when a base exists.
     */
    fun viewBranchDiff() {
        scope.launch {
            val base = withContext(Dispatchers.IO) { session.defaultBranch() }
            if (base == null) onOpenDiff(null) else onOpenBranchDiff(base)
        }
    }

    /**
     * Expand one History row, applying the answer only while it is still the
     * one asked for — last *clicked* wins, not last to finish.
     */
    fun expandCommit(sha: String) {
        requestedCommit = sha
        scope.launch {
            val details = withContext(Dispatchers.IO) { session.commitDetails(sha) }
            if (requestedCommit == sha) openCommit = details
        }
    }

    /**
     * Discard, from every route to it — the menu, the row, and Delete.
     *
     * A conflict never reaches the dialog. `git restore --source=HEAD` on an
     * unmerged path does not refuse: it keeps "ours", stages it, and leaves the
     * merge half-done with nothing on screen to say so. The engine refuses it
     * too; this is the half that can explain *why* without a round trip.
     */
    fun requestDiscard(change: GitChange) {
        val refusal = discardRefusal(change)
        if (refusal != null) {
            ops.error = refusal
            return
        }
        confirming = change
    }

    /** Walk the file rows, stepping over the section headers between them. */
    fun move(delta: Int) {
        val stops = rows.indices.filter { rows[it] is GitPanelRow.FileRow }
        if (stops.isEmpty()) return
        val at = stops.indexOf(selection)
        val next = when {
            at < 0 -> if (delta > 0) 0 else stops.lastIndex
            else -> (at + delta).coerceIn(0, stops.lastIndex)
        }
        selected = stops[next]
        scope.launch { listState.revealItem(stops[next]) }
    }

    // A palette command arriving from outside the panel. It waits for the
    // first status scan: "git: push" run with the panel closed composes this
    // panel *and* asks it to push in the same breath, and a push before the
    // branch is known would silently do nothing. A request stamped for
    // another project — asked on one, landing after a switch — is answered
    // without running, never pushed against the wrong repository.
    LaunchedEffect(request, state.scanned) {
        val asked = request ?: return@LaunchedEffect
        when (panelRequestStep(asked, project.id, state.scanned)) {
            PanelRequestStep.Wait -> {}
            PanelRequestStep.Drop -> onRequestHandled()
            PanelRequestStep.Run -> {
                onRequestHandled()
                runPanelCommand(asked.command)
            }
        }
    }

    // The pending leader's clock — see GIT_CHORD_TIMEOUT_MS for why Zed's
    // wait-forever is not copied here.
    LaunchedEffect(chordArmedAt) {
        if (chordArmedAt != null) {
            delay(GIT_CHORD_TIMEOUT_MS)
            chordArmedAt = null
        }
    }

    // The focus flag must not survive the panel — on a compact screen opening
    // a file removes the whole dock, and Compose does not promise a parting
    // onFocusChanged(false) on the way out — and neither must a request still
    // waiting on its first scan: dismissed there, the forgotten push would
    // otherwise run, unasked, whenever the panel is next opened. Both read
    // through rememberUpdatedState, because DisposableEffect(Unit) would
    // otherwise dispose with the values it was born with.
    val pendingRequest by rememberUpdatedState(request)
    val dropRequest by rememberUpdatedState(onRequestHandled)
    DisposableEffect(Unit) {
        onDispose {
            onFocusChanged(false)
            if (pendingRequest != null) dropRequest()
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            // How the workspace's root key pass knows this panel has the
            // keyboard — `hasFocus`, not `isFocused`, because the commit box
            // holding the caret still means the git panel is what is focused.
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            // The panel itself is the focus target the arrows talk to. The
            // commit box takes focus away from it while it is being typed in,
            // which is exactly what `messageFocused` below is watching for.
            .focusRequester(listFocus)
            .focusable()
            // The list's keys are taken before the list sees them, and the
            // commit box's are left alone while it has the caret — Space and
            // Backspace mean "stage" and "discard" in a list and something else
            // entirely in a text field.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // An armed ctrl-g owns the next keystroke whole: a matched
                // second key runs its command, anything else — Escape
                // included — aborts, and both are consumed, exactly as Zed's
                // resolver treats a sequence (see GitChords.kt). Bare
                // modifiers pass, or `ctrl-g shift-up` could never be typed.
                if (chordArmedAt != null) {
                    return@onPreviewKeyEvent when (
                        val step = gitChordStep(gitChordKeyOf(event.key), event.isShiftPressed)
                    ) {
                        is GitChordStep.Match -> {
                            chordArmedAt = null
                            runPanelCommand(step.command)
                            true
                        }
                        GitChordStep.StillPending -> false
                        GitChordStep.Abort -> {
                            chordArmedAt = null
                            true
                        }
                    }
                }
                if (event.isCtrlPressed) {
                    // Zed's ctrl-g leader, panel-scoped as its whole chord
                    // block is ("GitPanel" context, default-linux.json:
                    // 1060-1069) — never global, because in the editor plain
                    // ctrl-g is go-to-line (:622). Never armed from inside
                    // the commit box either — see [armsGitChord].
                    if (armsGitChord(
                            gitChordKeyOf(event.key),
                            event.isShiftPressed,
                            event.isAltPressed,
                            messageFocused,
                        )
                    ) {
                        chordArmedAt = SystemClock.uptimeMillis()
                        return@onPreviewKeyEvent true
                    }
                    // Zed's `ctrl-enter` for commit and `ctrl-shift-enter` for
                    // amend (default-linux.json:1054-1055), and both work from
                    // the message box as well — that is where they are wanted.
                    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (isEnter) {
                        if (event.isShiftPressed) amend() else commit()
                        return@onPreviewKeyEvent true
                    }
                    // Zed's `ctrl-space` Stage All and `ctrl-shift-space`
                    // Unstage All (default-linux.json:1070-1071).
                    if (event.key == Key.Spacebar && !event.isAltPressed) {
                        if (event.isShiftPressed) unstageAll() else stageAll()
                        return@onPreviewKeyEvent true
                    }
                    // Zed's `ctrl-1` / `ctrl-2` tab switching
                    // (default-linux.json:1010-1011). The root pass leaves
                    // these two digits alone while this panel has the
                    // keyboard; the rest still pick editor tabs from it.
                    if (!event.isShiftPressed && !event.isAltPressed) {
                        if (event.key == Key.One) {
                            selectTab(GitPanelTab.Changes)
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == Key.Two) {
                            selectTab(GitPanelTab.History)
                            return@onPreviewKeyEvent true
                        }
                    }
                    return@onPreviewKeyEvent false
                }
                if (event.key == Key.Escape) {
                    onDismiss()
                    return@onPreviewKeyEvent true
                }
                if (messageFocused) return@onPreviewKeyEvent false
                // The keys below act on the Changes list. On the History tab
                // that list is not on screen, and Space or Delete would stage
                // or discard a file the user cannot see — silent git mutation
                // from the keyboard.
                if (tab != GitPanelTab.Changes) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.PageDown -> { move(PAGE_ROWS); true }
                    Key.PageUp -> { move(-PAGE_ROWS); true }
                    Key.Enter, Key.NumPadEnter -> {
                        val change = selectedChange
                        when {
                            change == null -> move(1)
                            change.conflicted -> onResolveConflict(change.path)
                            else -> onOpenDiff(change.path)
                        }
                        true
                    }
                    // Zed's `space: git::ToggleStaged`.
                    Key.Spacebar -> {
                        selectedChange?.let(::toggleStaged)
                        true
                    }
                    // Zed's `delete` / `backspace: git::RestoreFile`, which it
                    // also binds with `skip_prompt: false`. Ours has no version
                    // that skips the prompt.
                    Key.Delete, Key.Backspace -> {
                        selectedChange?.let(::requestDiscard)
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(theme.color("panel.background"))
        ) {
            TabBar(
                tab = tab,
                changeCount = state.entries.size,
                // The same switch Ctrl+1 and Ctrl+2 run, no-op included.
                onTab = ::selectTab,
            )

            if (tab == GitPanelTab.Changes) {
                ActionBar(
                    state = state,
                    onViewDiff = { onOpenDiff(null) },
                    onStash = { command -> runPanelCommand(command) },
                    onViewStash = onViewStash,
                )
            }

            if (tab == GitPanelTab.History) {
                // Zed's History tab is the bare list; the Graph view is ours,
                // so its way in wears the changes header's clothes — the same
                // 32px row, a ghost button at its end (git_panel.rs:5786-5796).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BarHeight)
                        .padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Ctrl+Enter commits from this tab too; the repo row
                    // below carries the busy mark for every tab now.
                    Spacer(modifier = Modifier.weight(1f))
                    GhostButton(label = "Graph", enabled = true, onClick = onOpenGraph)
                }
                HistoryList(
                    page = history,
                    open = openCommit,
                    listState = historyListState,
                    onOpen = { commit ->
                        if (openCommit?.commit?.sha == commit.sha) {
                            openCommit = null
                            requestedCommit = null
                        } else {
                            expandCommit(commit.sha)
                        }
                    },
                    onOpenFile = onOpenFile,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (rows.isEmpty()) {
                    // The list must leave the composition entirely here, not
                    // just render nothing: an empty LazyColumn still fills the
                    // Box and its scroll modifier wins sibling hit-testing, so
                    // the empty state's buttons underneath never saw a tap.
                    EmptyMessage(
                        state = state,
                        busy = ops.busy,
                        onViewBranchDiff = ::viewBranchDiff,
                        onInitRepository = ::initRepository,
                    )
                } else
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        when (row) {
                            is GitPanelRow.SectionRow -> SectionHeader(
                                row = row,
                                enabled = !ops.busy,
                                onStageAll = {
                                    val paths = row.paths
                                    if (paths.isNotEmpty()) {
                                        perform({
                                            if (row.section == GitSection.Staged) {
                                                session.unstage(paths)
                                            } else {
                                                session.stage(paths)
                                            }
                                        })
                                    }
                                },
                            )
                            is GitPanelRow.FileRow -> ChangeRow(
                                change = row.change,
                                section = row.section,
                                colours = colours,
                                isSelected = index == selection,
                                enabled = !ops.busy,
                                onSelect = { selected = index },
                                // Zed opens the *diff* when a change is
                                // clicked, not the file: the question a
                                // changed row asks is "what changed".
                                onOpen = {
                                    selected = index
                                    if (row.change.conflicted) {
                                        onResolveConflict(row.change.path)
                                    } else {
                                        onOpenDiff(row.change.path)
                                    }
                                },
                                onOpenDiff = {
                                    selected = index
                                    onOpenDiff(row.change.path)
                                },
                                onToggleStaged = {
                                    selected = index
                                    toggleStaged(row.change)
                                },
                                onDiscard = {
                                    selected = index
                                    requestDiscard(row.change)
                                },
                            )
                        }
                    }
                }
            }

            // The armed leader, made visible: Zed echoes pending keystrokes
            // in its status bar, which Android has no equivalent of, so the
            // panel itself wears a chip in the strip the notices use, saying
            // a chord is waiting on its second key.
            if (chordArmedAt != null) {
                HorizontalDivider(color = theme.color("border.variant"))
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(theme.color("elevated_surface.background"))
                            .border(
                                1.dp,
                                theme.color("border.variant"),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        // Spelled as the remote menu's shortcut column spells
                        // the same chords.
                        Text(
                            text = "Ctrl G",
                            style = MaterialTheme.typography.labelMedium,
                            color = theme.color("text.muted"),
                        )
                    }
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted"),
                    )
                }
            }

            ops.error?.let { text ->
                HorizontalDivider(color = theme.color("border.variant"))
                // Dismissible, because unlike a toast it never times out: a
                // failed fetch's refusal otherwise sat over the commit box
                // until the next command happened to replace it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("error"),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FooterIconButton(
                        icon = R.drawable.ic_ui_close,
                        label = "Dismiss",
                        enabled = true,
                        onClick = { ops.error = null },
                    )
                }
            }

            // What a remote command said when it *worked* — the strip the
            // errors use, in quieter clothes: the panel's stand-in for Zed's
            // success StatusToast (git_panel.rs:5278-5334).
            ops.notice?.let { text ->
                HorizontalDivider(color = theme.color("border.variant"))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            if (identityWanted) {
                HorizontalDivider(color = theme.color("border.variant"))
                IdentityForm(
                    name = identityName,
                    email = identityEmail,
                    onName = { identityName = it },
                    onEmail = { identityEmail = it },
                    busy = ops.busy,
                    onSave = ::saveIdentity,
                    onDismiss = { identityWanted = false },
                )
            }

            // The branch on the left, the remote split button on the right —
            // Zed's `PanelRepoFooter` row (git_panel.rs:8711-8746), hung where
            // Zed hangs it: at the bottom, directly above the commit editor,
            // and still standing on the History tab so every tab carries the
            // branch and the busy mark.
            HorizontalDivider(color = theme.color("border.variant"))
            RepoHeader(
                state = state,
                head = head,
                busy = ops.busy,
                pendingRemote = ops.pendingRemote,
                onSwitchBranch = onSwitchBranch,
                onFetch = { fetch(fetchAll = true) },
                onFetchFrom = { fetch(fetchAll = false) },
                onPull = { pull(rebase = false) },
                onPullRebase = { pull(rebase = true) },
                onPush = { push() },
                onPushTo = { push(selectRemote = true) },
                onForcePush = { push(force = true) },
            )

            if (tab == GitPanelTab.Changes) {
            // The commit editor's own `border_t_1` in `border`
            // (git_panel.rs:5991-5996).
            HorizontalDivider(color = theme.color("border"))
            CommitBox(
                message = message,
                onMessage = {
                    message = it
                    CommitDrafts.put(project.id, it.text)
                },
                onFocusChanged = { focused ->
                    messageFocused = focused
                    // A pending chord dies when the caret enters the box:
                    // the next keystroke there is *typing*, and a leader
                    // armed a moment earlier must not intercept it.
                    if (focused) chordArmedAt = null
                },
                stagedCount = state.staged.size,
                busy = ops.busy,
                commitLabel = commitButtonLabel(
                    amendPending = amendPending,
                    hasStaged = state.staged.isNotEmpty(),
                    hasTracked = hasTrackedChanges(state.entries),
                ),
                // The menu's Amend entry exists only where a commit does
                // (`has_previous_commit`, git_panel.rs:5563, 5574).
                hasHeadCommit = head != null,
                amendPending = amendPending,
                signoffEnabled = signoffEnabled,
                skipHooks = skipHooks,
                onCommit = ::commit,
                onToggleAmend = { setAmendPending(!amendPending) },
                onToggleSignoff = ::toggleSignoff,
                onToggleSkipHooks = ::toggleSkipHooks,
            )
            // The panel's very last row is Zed's own bottom slot
            // (git_panel.rs:8356-8361): while an amend is pending, a banner
            // saying what the button will now do with the way out beside it
            // (git_panel.rs:6125-6150); otherwise the previous-commit row —
            // the last subject, Uncommit and the graph (git_panel.rs:6152-6255).
            if (amendPending) {
                HorizontalDivider(color = theme.color("border.variant"))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.color("editor.background"))
                        // `py_1p5 px_2 gap_1p5 justify_between` (git_panel.rs:6131-6136).
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        // Zed's banner label (git_panel.rs:6139-6141).
                        text = "This will update your most recent commit.",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GhostButton(
                        // Its "Cancel" (git_panel.rs:6143-6148).
                        label = "Cancel",
                        enabled = !ops.busy,
                        onClick = { setAmendPending(false) },
                    )
                }
            } else {
                val footerCommit = lastCommit
                // The row exists only when there is a repository, a branch and
                // a commit to describe (git_panel.rs:6157-6159).
                if (state.hasRepo && state.branch != null && footerCommit != null) {
                    RepoFooter(
                        commit = footerCommit,
                        hasUnstaged = state.unstaged.isNotEmpty(),
                        enabled = !ops.busy,
                        // What Zed's footer opens is the commit view
                        // (`CommitView::open`, git_panel.rs:6183-6197), and a
                        // commit diff tab is exactly that — the same tab the
                        // graph's rows open, so one commit looks the same
                        // from every door.
                        onOpenCommit = { onOpenCommit(footerCommit.sha, footerCommit.subject) },
                        onUncommit = ::uncommit,
                        onOpenGraph = onOpenGraph,
                    )
                }
            }
            }
        }
    }

    // Fetch From, Push To, and any pull or push whose branch names no remote
    // of its own: the "which remote?" modal (picker_prompt.rs:27-42).
    remotePicker?.let { request ->
        RemotePickerDialog(request = request, onDismiss = { remotePicker = null })
    }

    stashAsk?.let { kind ->
        StashMessageDialog(
            kind = kind,
            onDismiss = { stashAsk = null },
            onConfirm = { text ->
                stashAsk = null
                perform({ session.stashPush(kind, text) })
            },
        )
    }

    val pending = confirming
    if (pending != null) {
        DiscardDialog(
            change = pending,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                perform({ session.discard(listOf(pending.path)) })
            },
        )
    }

    // A commit turned back before it ran — Zed's blocking warning prompt with
    // its single "OK" (git_panel.rs:3072-3079, 3109-3112).
    warning?.let { text ->
        AlertDialog(
            onDismissRequest = { warning = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { warning = null }) { Text("OK") } },
        )
    }

    // A commit that was already pushed does not uncommit silently — Zed's
    // prompt, title, detail and both options (git_panel.rs:3209-3230).
    pendingUncommit?.let { request ->
        AlertDialog(
            onDismissRequest = { pendingUncommit = null },
            title = { Text("Are you sure?") },
            text = { Text(uncommitPushedDetail(request.remotes)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingUncommit = null
                    runUncommit(request)
                }) { Text("Uncommit") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUncommit = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Zed's `PanelRepoFooter` — the git-branch icon and the branch on the left,
 * the remote split button on the right, an `px_2` / `py_1p5` row,
 * `justify_between` with a `gap_1` (git_panel.rs:8711-8746). It hangs where
 * Zed hangs it, directly above the commit editor. The branch name is a
 * `LabelSize::Small` button label there (git_panel.rs:8687-8692).
 *
 * No branch to speak for — detached HEAD, nothing committed — and there is no
 * remote button at all (git_panel.rs:5851 via [remoteButtonSpec]).
 */
@Composable
private fun RepoHeader(
    state: GitPanelState,
    head: String?,
    busy: Boolean,
    pendingRemote: Boolean,
    onSwitchBranch: () -> Unit,
    onFetch: () -> Unit,
    onFetchFrom: () -> Unit,
    onPull: () -> Unit,
    onPullRebase: () -> Unit,
    onPush: () -> Unit,
    onPushTo: () -> Unit,
    onForcePush: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The `GitBranch` icon leading the row — `IconSize::Small` = 14px,
        // Disabled with a single repository, which is all this app opens
        // (git_panel.rs:8721-8727).
        Image(
            painter = painterResource(R.drawable.ic_ui_git_branch),
            contentDescription = null,
            colorFilter = ColorFilter.tint(theme.color("text.disabled")),
            modifier = Modifier.size(14.dp),
        )
        // The name is Zed's "branch-selector" button — `ButtonSize::None`,
        // `LabelSize::Small`, truncating — whose click dispatches
        // `git::Switch`, the branch picker (git_panel.rs:8687-8709). A ghost
        // hug around the text, not the whole row: the gap keeps belonging to
        // the header. Without a repository there is nothing to switch, so the
        // label stays a label.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            val branchInteraction = remember { MutableInteractionSource() }
            val branchHovered by branchInteraction.collectIsHoveredAsState()
            val branchPressed by branchInteraction.collectIsPressedAsState()
            Text(
                text = branchLabel(state, head),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            !state.hasRepo -> Color.Transparent
                            branchPressed -> theme.color("ghost_element.active")
                            branchHovered -> theme.color("ghost_element.hover")
                            else -> Color.Transparent
                        }
                    )
                    .then(
                        if (state.hasRepo) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = branchInteraction,
                                    indication = null,
                                    // Zed's tooltip title for the trigger
                                    // (git_panel.rs:8705-8707).
                                    onClickLabel = "Switch Branch",
                                    onClick = onSwitchBranch,
                                )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 4.dp),
            )
        }
        // The non-remote commands — stages, commits — have no spinner of
        // their own, so their busy mark stays; a running remote command is
        // the split button's own disabled-and-turning state (git_ui.rs:1110-1123).
        if (busy && !pendingRemote) {
            Text(
                text = "…",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
            )
        }
        val spec = remoteButtonSpec(state.branch)
        if (spec != null) {
            RemoteSplitButton(
                spec = spec,
                enabled = !busy,
                remotePending = pendingRemote,
                onFetch = onFetch,
                onFetchFrom = onFetchFrom,
                onPull = onPull,
                onPullRebase = onPullRebase,
                onPush = onPush,
                onPushTo = onPushTo,
                onForcePush = onForcePush,
            )
        }
    }
}

/**
 * The branch's name, or what stands in for one (git_panel.rs:8640-8654).
 * An unborn branch is still a *named* branch — Zed synthesizes it from
 * `symbolic-ref HEAD` and shows the bare name (repository.rs:2076-2094) —
 * so `git init` reads "main" here, no suffix: the empty state's body already
 * says nothing has been committed.
 */
internal fun branchLabel(state: GitPanelState, head: String?): String {
    if (!state.hasRepo) return "No repository"
    val branch = state.branch ?: return "git"
    val name = branch.name
    return when {
        // The drift arrows are the split button's counts now, as in Zed —
        // the name is only the name.
        name != null -> name
        // A detached HEAD wears the first 8 characters of its sha —
        // `MAX_SHORT_SHA_LEN` — and a repository with no commit at all Zed's
        // "(no branch)" (git_panel.rs:8640-8654).
        head != null -> head.take(8)
        else -> "(no branch)"
    }
}

/**
 * The panel's very last row — Zed's `render_previous_commit`
 * (git_panel.rs:6152-6255): the last commit's subject on the left, one
 * truncated `LabelSize::Small` line that opens the commit, and on the right
 * the Uncommit and Git Graph icon buttons in a `gap_0p5` cluster. The row is
 * `p_1p5` with a `gap_1p5` under a 1px top border in `border` at 0.8
 * (git_panel.rs:6164-6169). Uncommit exists only when the commit has a
 * parent — a root commit has nothing to reset back to (git_panel.rs:6215).
 */
@Composable
private fun RepoFooter(
    commit: Commit,
    /** What words the Uncommit button's meta — see [uncommitMeta]. */
    hasUnstaged: Boolean,
    enabled: Boolean,
    onOpenCommit: () -> Unit,
    onUncommit: () -> Unit,
    onOpenGraph: () -> Unit,
) {
    val theme = LocalZedTheme.current
    HorizontalDivider(color = theme.color("border").copy(alpha = 0.8f))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        // `justify_between`: the subject keeps the left and gives way, the
        // buttons keep the right whole.
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Text(
                text = commit.subject.ifBlank { "(no message)" },
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    // `px_1 rounded_sm`, `element_hover` under the pointer,
                    // and the pointer a hand (git_panel.rs:6171-6177).
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (hovered) theme.color("element.hover") else Color.Transparent
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = "Show this commit",
                        onClick = onOpenCommit,
                    )
                    .padding(horizontal = 4.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // `gap_0p5` (git_panel.rs:6213).
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (commit.parents.isNotEmpty()) {
                FooterIconButton(
                    icon = R.drawable.ic_ui_undo,
                    // Zed's tooltip title and its meta line, worn as the
                    // accessibility label (git_panel.rs:6222-6231).
                    label = "Uncommit — ${uncommitMeta(hasUnstaged)}",
                    enabled = enabled,
                    onClick = onUncommit,
                )
            }
            FooterIconButton(
                icon = R.drawable.ic_ui_git_graph,
                // Zed's tooltip (git_panel.rs:6242-6248).
                label = "Open Git Graph",
                enabled = true,
                onClick = onOpenGraph,
            )
        }
    }
}

/**
 * Zed's `IconButton` in its Subtle clothes — a 22px `rounded_sm` square,
 * transparent at rest, `ghost_element.hover`/`.active` under the pointer
 * (button_like.rs:245-330), the glyph at `IconSize::Small` = 14px in the
 * label's muted colour. The tap target is the taller invisible wrapper, as
 * every small control here (density decision, DECISIONS.md).
 */
@Composable
private fun FooterIconButton(
    icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(30.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        !enabled -> Color.Transparent
                        pressed -> theme.color("ghost_element.active", Color.Transparent)
                        hovered -> theme.color("ghost_element.hover", Color.Transparent)
                        else -> Color.Transparent
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = label,
                colorFilter = ColorFilter.tint(
                    theme.color(if (enabled) "text.muted" else "text.disabled")
                ),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * What the empty change-list region says — Zed's `render_empty_state`
 * dispatch (git_panel.rs:6920-6931): a centred column, the label a muted
 * default-size line, its way out an Outlined button a `gap_1` under it. Two
 * of the states are ours alone — a scan still out, a userland with no git —
 * because Zed never has to explain either; the other two are Zed's, words,
 * buttons and all.
 */
@Composable
private fun EmptyMessage(
    state: GitPanelState,
    busy: Boolean,
    onViewBranchDiff: () -> Unit,
    onInitRepository: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // `gap_1` between the label and its button (git_panel.rs:6937, 7009).
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (gitPanelEmptyState(state)) {
                // Asked before told: an empty list is not yet a clean tree,
                // and not yet a missing repository either — the init button
                // must not flash while the first scan is still out.
                GitPanelEmptyState.Scanning -> EmptyLabel("Asking git…")
                // An empty list is what "clean" looks like *and* what "git
                // never ran" looks like. Claiming the first when it is the
                // second told a user with no git in their Debian that their
                // tree was clean.
                GitPanelEmptyState.NoGit -> EmptyLabel(
                    "Could not run git here — ${Userland.backend.displayName} needs git " +
                        "installed before the panel can show anything"
                )
                GitPanelEmptyState.NoRepo -> {
                    // Zed's uninitialized state, label and button both
                    // (git_panel.rs:7008-7027); the button is `git init`, and
                    // the poll redraws the panel as a clean repository after.
                    EmptyLabel("No Git Repositories")
                    OutlinedButton(
                        label = "Initialize Repository",
                        enabled = !busy,
                        onClick = onInitRepository,
                    )
                }
                GitPanelEmptyState.Clean -> {
                    // Zed's words for a clean tree (git_panel.rs:6940), and
                    // its branch-diff way out on every branch that is not the
                    // main one (git_panel.rs:6935-6951).
                    EmptyLabel("No changes to commit")
                    if (showsViewBranchDiff(state.branch?.name)) {
                        OutlinedButton(
                            label = "View Branch Diff",
                            enabled = !busy,
                            onClick = onViewBranchDiff,
                        )
                    }
                }
            }
        }
    }
}

/** The empty state's sentence: muted, default size (git_panel.rs:6940, 7012). */
@Composable
private fun EmptyLabel(text: String) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = theme.color("text.muted"),
        textAlign = TextAlign.Center,
    )
}

/**
 * Zed's `ButtonStyle::Outlined` at `ButtonSize::Default`: a 22px `rounded_sm`
 * pill ringed 1px in `border.variant`, filled `element.background` at rest;
 * under the pointer the fill goes `ghost_element.hover` and the ring sharpens
 * to `border`; pressed is `element.active` (button_like.rs:224-229, 280-285,
 * 336-341, 469). The label is `LabelSize::Small` in plain `text` — and the
 * tap target is the taller invisible wrapper, as every small control here
 * (density decision, DECISIONS.md).
 */
@Composable
private fun OutlinedButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        pressed && enabled -> theme.color("element.active")
                        hovered && enabled -> theme.color("ghost_element.hover", Color.Transparent)
                        else -> theme.color("element.background", Color.Transparent)
                    }
                )
                .border(
                    1.dp,
                    if (hovered && enabled) theme.color("border") else theme.color("border.variant"),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color(if (enabled) "text" else "text.disabled"),
            )
        }
    }
}

/**
 * A section header, in Zed's `render_list_header` shape: the same 28px row as
 * an entry, `pl_2p5`/`pr_1`, the title a `LabelSize::Small` in `text.muted`,
 * and the stage-all control a checkbox at the row's end rather than a word
 * (git_panel.rs:7288-7318, 7322-7345). No count — Zed's headers carry none;
 * the tab already says how many. No chevron either: Zed's collapses the
 * section (git_panel.rs:7307-7315) and ours does not, and a disclosure that
 * discloses nothing would be a lie.
 */
@Composable
private fun SectionHeader(
    row: GitPanelRow.SectionRow,
    enabled: Boolean,
    onStageAll: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ListItemHeight)
            .padding(start = RowStartPadding, end = RowEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.section.title,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
            modifier = Modifier.weight(1f),
        )
        if (row.section != GitSection.Conflicts) {
            ZedCheckbox(
                checked = row.section == GitSection.Staged,
                partial = false,
                enabled = enabled && row.paths.isNotEmpty(),
                label = if (row.section == GitSection.Staged) "Unstage all" else "Stage all",
                onClick = onStageAll,
            )
        }
    }
}

@Composable
private fun ChangeRow(
    change: GitChange,
    section: GitSection,
    colours: GitStatusColours,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    /** A tap or Enter: the diff, or for a conflicted row its resolution. */
    onOpen: () -> Unit,
    /** The diff, whatever the row is — the menu's "Open". */
    onOpenDiff: () -> Unit,
    onToggleStaged: () -> Unit,
    onDiscard: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }

    val status = if (section == GitSection.Staged) change.staged else change.unstaged
    val deleted = status == GitFileStatus.Deleted
    // Zed's entry ramp: a selected row is `status.info` at 0.08 alpha, not a
    // `ghost_element` fill — and hover/press on a selected row brighten the
    // same wash to 0.12/0.16 rather than swapping to the ghost pair
    // (git_panel.rs:7616-7640).
    val background = when {
        isSelected && pressed -> theme.color("info").copy(alpha = 0.16f)
        isSelected && hovered -> theme.color("info").copy(alpha = 0.12f)
        isSelected -> theme.color("info").copy(alpha = 0.08f)
        pressed -> theme.color("ghost_element.active", Color.Transparent)
        hovered -> theme.color("ghost_element.hover", Color.Transparent)
        else -> Color.Transparent
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // `list_item_height()` exactly — no minimum, no padding
                // (git_panel.rs:7688-7689).
                .height(ListItemHeight)
                .background(background)
                .pointerHoverIcon(PointerIcon.Hand)
                // The list is the one focus target; rows taking it in turn
                // would fight the arrows for the selection.
                .focusProperties { canFocus = false }
                .onSecondaryClick { at -> onSelect(); menuAt = at }
                .combinedClickable(
                    interactionSource = interaction,
                    // Zed swaps a row's colour instantly and has no ripple.
                    indication = null,
                    onLongClick = { onSelect(); menuAt = DpOffset.Zero },
                    onClick = onOpen,
                )
                // `pl_2p5` / `pr_1` (git_panel.rs:7690-7691).
                .padding(start = RowStartPadding, end = RowEndPadding),
            verticalAlignment = Alignment.CenterVertically,
            // `gap_1p5` between the name row and the checkbox
            // (git_panel.rs:7692).
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Zed leads with the status mark, tinted by the
            // `version_control.*` colour for that status (git_ui.rs:1185-1207);
            // ours is git's letter where Zed draws an icon, in a fixed slot so
            // the filenames line up.
            Text(
                text = statusLetter(change, section),
                style = MaterialTheme.typography.labelMedium,
                color = colours.colorFor(status.forColours(), dimIgnored = false),
                maxLines = 1,
                modifier = Modifier.width(14.dp),
            )
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // With the mark carrying the status, the filename is plain
                // `text` and the directory `text.muted` — a deleted file goes
                // `text.disabled` and struck through instead of shouting in
                // red (git_panel.rs:7571-7592, 7965-8003).
                Text(
                    text = change.name,
                    style = if (deleted) {
                        MaterialTheme.typography.bodyMedium
                            .copy(textDecoration = TextDecoration.LineThrough)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = theme.color(if (deleted) "text.disabled" else "text"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = change.directory,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color(if (deleted) "text.disabled" else "text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Zed separates name from path with a literal space
                    // (git_panel.rs:7978).
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (change.conflicted) {
                // A conflict has nothing to stage until it is resolved, so
                // the checkbox's slot carries the way to resolving it
                // instead: the file, opened on its first conflict.
                ResolveAffordance(enabled = enabled, onClick = onOpen)
            } else {
                // Zed's staging checkbox sits at the *end* of the row
                // (git_panel.rs:7712-7724).
                ZedCheckbox(
                    checked = change.staged != null,
                    partial = change.staged != null && change.unstaged != null,
                    enabled = enabled,
                    label = if (change.staged != null) "Unstage ${change.path}" else "Stage ${change.path}",
                    onClick = onToggleStaged,
                )
            }
        }

        ContextMenu(
            expanded = menuAt != null,
            onDismiss = { menuAt = null },
            offset = menuAt ?: DpOffset.Zero,
            items = listOfNotNull(
                if (change.conflicted) {
                    ContextMenuItem("Resolve", shortcut = "Enter", onClick = onOpen)
                } else {
                    null
                },
                ContextMenuItem("Open", onClick = onOpenDiff),
                if (change.conflicted) {
                    null
                } else if (change.staged != null && change.unstaged == null) {
                    ContextMenuItem("Unstage", shortcut = "Space", enabled = enabled, onClick = onToggleStaged)
                } else {
                    ContextMenuItem("Stage", shortcut = "Space", enabled = enabled, onClick = onToggleStaged)
                },
                // Named for what it will actually do to *this* file, and it
                // opens the confirmation rather than doing it. A conflicted row
                // keeps the item and gets the reason it cannot: an item that
                // silently vanishes teaches nothing.
                ContextMenuItem(
                    label = discardLabel(change),
                    shortcut = "Delete",
                    enabled = enabled,
                    onClick = onDiscard,
                ),
            ),
        )
    }
}

/**
 * Zed's checkbox, as the git panel builds it: `Checkbox::new(..).fill()
 * .elevation(ElevationIndex::Surface)` (git_panel.rs:7718-7720). The container
 * is a 20px square (toggle.rs:180-182); the box inside it is `size_4` = 16px,
 * `rounded_xs` 2px, bordered 1px in `border` — `border.variant` when disabled
 * — and filled `editor.background`, which is what `darker_bg` resolves to at
 * Surface elevation (toggle.rs:169-178, 226-236; elevation.rs:108-111). The
 * mark is a check or a dash in `Color::Selected` → `text.accent`
 * (toggle.rs:186-208; color.rs:108). Hovering fades the border to 0.7 alpha
 * (toggle.rs:215).
 *
 * 20dp is under the 40dp thumb rule; per the 2026-08-17 density decision that
 * is accepted, and staging keeps its other routes — Space on the selected row
 * and the long-press menu's Stage/Unstage item.
 */
/**
 * The word "Resolve" in the checkbox's slot of a conflicted row. The
 * affordance the conflict list has in place of staging: the resolution
 * happens in the editor, and staging the result is what the Conflicts
 * section's own header and the banner over the resolved file offer.
 */
@Composable
private fun ResolveAffordance(enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = "Resolve",
        style = MaterialTheme.typography.labelMedium,
        color = theme.color(if (enabled) "text.accent" else "text.disabled"),
        maxLines = 1,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(enabled = enabled, onClickLabel = "Resolve", onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun ZedCheckbox(
    checked: Boolean,
    partial: Boolean,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border = when {
        !enabled -> theme.color("border.variant")
        hovered -> theme.color("border").copy(alpha = 0.7f)
        else -> theme.color("border")
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            // Instant, rippleless, as every toggle in Zed.
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (enabled) {
                        theme.color("editor.background")
                    } else {
                        theme.color("element.disabled").copy(alpha = 0.6f)
                    }
                )
                .border(1.dp, border, RoundedCornerShape(2.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked || partial) {
                Text(
                    text = if (partial) "–" else "✓",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color(if (enabled) "text.accent" else "text.disabled"),
                )
            }
        }
    }
}

/**
 * The commit editor and its footer, in Zed's anatomy: not a rounded input but
 * a bare region of `editor.background` under a 1px top border (drawn by the
 * caller), the message set in the *buffer* font at
 * `git_commit_buffer_font_size` — pinned to **12** in Zed's own defaults
 * (default.json:81), so the buffer_font_size fallback in
 * theme_settings/src/settings.rs:446-451 never applies at defaults —
 * exactly [CommitEditorLines] lines tall, with `pt_2`/`px_2` around the text
 * (git_panel.rs:6002-6006). Below it, a `p_1p5` footer row with the commit
 * button at its end (git_panel.rs:6021-6045).
 */
@Composable
private fun CommitBox(
    message: TextFieldValue,
    onMessage: (TextFieldValue) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    stagedCount: Int,
    busy: Boolean,
    /** What the split button's left half reads — see [commitButtonLabel]. */
    commitLabel: String,
    /** Whether HEAD names a commit at all, which is what Amend needs. */
    hasHeadCommit: Boolean,
    amendPending: Boolean,
    signoffEnabled: Boolean,
    skipHooks: Boolean,
    onCommit: () -> Unit,
    onToggleAmend: () -> Unit,
    onToggleSignoff: () -> Unit,
    onToggleSkipHooks: () -> Unit,
) {
    val theme = LocalZedTheme.current
    // sp treated as dp, which at font scale 1 is what Zed's px-per-line rule
    // means; a user's font scale then grows the text but not the box, which
    // scrolls — the list keeping the panel matters more than the sixth line.
    val fontSize = CommitBufferFontSize
    val lineHeight = fontSize * BufferLineHeight
    val editorStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = BufferFontFamily,
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        color = theme.color("text"),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((lineHeight * CommitEditorLines).dp + 8.dp)
                .background(theme.color("editor.background"))
                .pointerHoverIcon(PointerIcon.Text)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
        ) {
            BasicTextField(
                value = message,
                onValueChange = onMessage,
                textStyle = editorStyle,
                cursorBrush = SolidColor(theme.cursor),
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { onFocusChanged(it.isFocused) },
            )
            if (message.text.isEmpty()) {
                Text(
                    // Zed's own placeholder (git_panel.rs:1109).
                    text = "Enter commit message",
                    style = editorStyle,
                    color = theme.color("text.placeholder"),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.color("editor.background"))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Zed's footer keeps an AI message button here; ours counts what
            // will be committed, which is the more honest use of the corner.
            Text(
                text = if (stagedCount == 0) {
                    "Nothing staged"
                } else if (stagedCount == 1) {
                    "1 file staged"
                } else {
                    "$stagedCount files staged"
                },
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.weight(1f),
            )
            CommitSplitButton(
                label = commitLabel,
                // Enabled with nothing staged on purpose: git's refusal is the
                // honest explanation, and a button that greys out for reasons
                // the user cannot see is worse than one that answers.
                enabled = !busy && message.text.isNotBlank(),
                hasHeadCommit = hasHeadCommit,
                amendPending = amendPending,
                signoffEnabled = signoffEnabled,
                skipHooks = skipHooks,
                onCommit = onCommit,
                onToggleAmend = onToggleAmend,
                onToggleSignoff = onToggleSignoff,
                onToggleSkipHooks = onToggleSkipHooks,
            )
        }
    }
}

/**
 * Zed's ghost button: `ButtonSize::Default` = 22px tall, `rounded_sm`, `px`
 * Base04 = 4px, and the Subtle ramp — transparent at rest,
 * `ghost_element.hover` under the pointer, `ghost_element.active` pressed
 * (button_like.rs:469, 796-803; 245-330). The label is `LabelSize::Small` in
 * `text.muted`, as the changes header's own buttons wear it
 * (git_panel.rs:5805-5809).
 */
@Composable
private fun GhostButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // As with [FilledButton]: the 22dp ghost box is the visual, the tap
    // target is the taller invisible wrapper (density decision, DECISIONS.md).
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        !enabled -> Color.Transparent
                        pressed -> theme.color("ghost_element.active", Color.Transparent)
                        hovered -> theme.color("ghost_element.hover", Color.Transparent)
                        else -> Color.Transparent
                    }
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color(if (enabled) "text.muted" else "text.disabled"),
            )
        }
    }
}

/**
 * Zed's filled small button — the commit and remote buttons are `ButtonLike`s
 * at `ButtonSize::Compact` = 18px on the ModalSurface layer, whose fill is the
 * `background` token (git_panel.rs:6072-6075; button_like.rs:470, 200-207),
 * with the 1px `border`-at-0.8 ring their `SplitButton` wrapper paints
 * (split_button.rs:71-73, 88-95) and a `LabelSize::Small` label. Hover fades
 * the fill to half (button_like.rs:263-272); press is `element.active`
 * (button_like.rs:317-321).
 */
@Composable
private fun FilledButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    shortcut: String? = null,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = theme.color("background")
    // The 18dp pill is the *visual*; the tap target is this outer box, held
    // open to 30dp with a little horizontal slack — the density decision's
    // remedy for a small control with no keyboard twin: expand the hit area
    // invisibly, never the drawing.
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = shortcut?.let { "$label ($it)" } ?: label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        pressed && enabled -> theme.color("element.active")
                        hovered && enabled -> fill.copy(alpha = fill.alpha * 0.5f)
                        else -> fill
                    }
                )
                .border(
                    1.dp,
                    theme.color("border").copy(alpha = 0.8f),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color(if (enabled) "text" else "text.disabled"),
            )
        }
    }
}

/**
 * Zed's `SplitButton` around the commit action (git_panel.rs:6071-6122): the
 * left half is the commit button in [FilledButton]'s clothes, rounded only on
 * its left; the right half a 20px chevron that deploys the picker menu — down
 * while closed, up while it is open (git_ui.rs:1150-1167). One ring of
 * `border` at 0.8 wraps both halves, with a matching divider between them
 * (split_button.rs:71-95). The menu anchors below with Zed's 2px drop
 * (git_panel.rs:5613-5617).
 */
@Composable
private fun CommitSplitButton(
    label: String,
    enabled: Boolean,
    hasHeadCommit: Boolean,
    amendPending: Boolean,
    signoffEnabled: Boolean,
    skipHooks: Boolean,
    onCommit: () -> Unit,
    onToggleAmend: () -> Unit,
    onToggleSignoff: () -> Unit,
    onToggleSkipHooks: () -> Unit,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }
    val leftInteraction = remember { MutableInteractionSource() }
    val leftHovered by leftInteraction.collectIsHoveredAsState()
    val leftPressed by leftInteraction.collectIsPressedAsState()
    val rightInteraction = remember { MutableInteractionSource() }
    val rightHovered by rightInteraction.collectIsHoveredAsState()
    val rightPressed by rightInteraction.collectIsPressedAsState()
    val fill = theme.color("background")
    val ring = theme.color("border").copy(alpha = 0.8f)
    val shape = RoundedCornerShape(4.dp)
    // As [FilledButton]: the 18dp pill is the visual, the tap target is the
    // taller invisible wrapper (density decision, DECISIONS.md).
    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(18.dp)
                .clip(shape)
                .border(1.dp, ring, shape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .then(
                        if (enabled) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = leftInteraction,
                                    indication = null,
                                    onClickLabel = "$label (Ctrl Enter)",
                                    onClick = onCommit,
                                )
                        } else {
                            Modifier
                        }
                    )
                    .background(
                        when {
                            leftPressed && enabled -> theme.color("element.active")
                            leftHovered && enabled -> fill.copy(alpha = fill.alpha * 0.5f)
                            else -> fill
                        }
                    )
                    // The label wears `mr_0p5` inside its half (git_panel.rs:6077-6080).
                    .padding(start = 4.dp, end = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color(if (enabled) "text" else "text.disabled"),
                )
            }
            // `border_l` between the halves (split_button.rs:88-95).
            Box(Modifier.width(1.dp).fillMaxHeight().background(ring))
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(
                        interactionSource = rightInteraction,
                        indication = null,
                        onClickLabel = "Commit options",
                    ) { menuOpen = !menuOpen }
                    .background(
                        when {
                            rightPressed || menuOpen -> theme.color("element.active")
                            rightHovered -> fill.copy(alpha = fill.alpha * 0.5f)
                            else -> fill
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(
                        if (menuOpen) R.drawable.ic_ui_chevron_up else R.drawable.ic_ui_chevron_down
                    ),
                    contentDescription = if (menuOpen) "Close commit options" else "Commit options",
                    colorFilter = ColorFilter.tint(theme.color("text")),
                    // `IconSize::XSmall` = 12px (git_ui.rs:1160).
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        ContextMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            offset = DpOffset(0.dp, 2.dp),
            items = listOfNotNull(
                // Only where a commit exists to amend (git_panel.rs:5563, 5574);
                // ticking it is `toggle_amend_pending` (git_panel.rs:5575-5590).
                if (hasHeadCommit) {
                    ContextMenuItem(
                        label = "Amend",
                        shortcut = "Ctrl Shift Enter",
                        checked = amendPending,
                        onClick = onToggleAmend,
                    )
                } else {
                    null
                },
                // No default binding, so no chord (git_panel.rs:5592-5598).
                ContextMenuItem(
                    label = "Signoff",
                    checked = signoffEnabled,
                    onClick = onToggleSignoff,
                ),
                // Aside and all: the literal flag it arms (git_panel.rs:5599-5608).
                ContextMenuItem(
                    label = "Skip Hooks",
                    checked = skipHooks,
                    aside = "git commit --no-verify",
                    onClick = onToggleSkipHooks,
                ),
            ),
        )
    }
}

/**
 * The confirmation. It names the file, and it says which of discard's three
 * meanings this one is — restored from the last commit, moved to the trash, or
 * a rename undone, which is both at once. They are not the same promise, and
 * only the first is reversible with a `git` command.
 *
 * A conflicted row never gets here: [discardRefusal] turns it back at the door,
 * because there is no wording that would make "keep ours and say nothing" the
 * thing the user meant.
 */
@Composable
private fun DiscardDialog(change: GitChange, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val renamedFrom = change.original
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    renamedFrom != null -> "Undo the rename of ${change.name}?"
                    change.inHead -> "Discard changes to ${change.name}?"
                    else -> "Move ${change.name} to the trash?"
                }
            )
        },
        text = {
            Text(
                buildString {
                    append(change.path)
                    append("\n\n")
                    when {
                        // Both halves, named, because the destructive half is
                        // the one the old name does not cover: the last commit
                        // has never held this file under its new name, so what
                        // has been typed into it since is not in git anywhere.
                        renamedFrom != null -> append(
                            "$renamedFrom comes back as the last commit holds it, and " +
                                "${change.name} goes to the app's trash — the commit has " +
                                "never seen it under that name, so git has no copy of " +
                                "anything you have written in it."
                        )
                        change.inHead -> append(
                            "The file goes back to what the last commit holds. " +
                                "Everything you have changed in it since then is gone, " +
                                "and git has no copy of it."
                        )
                        change.isDirectory -> append(
                            "The last commit has never seen this folder, so there is " +
                                "nothing to restore it from. It goes to the app's trash " +
                                "with everything in it, rather than being deleted."
                        )
                        else -> append(
                            "The last commit has never seen this file, so there is " +
                                "nothing to restore it from. It goes to the app's " +
                                "trash rather than being deleted."
                        )
                    }
                    append(
                        "\n\nA copy open in the editor keeps whatever you have not " +
                            "saved; the tab will say the file changed underneath it."
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    when {
                        renamedFrom != null -> "Undo the rename"
                        change.inHead -> "Discard"
                        else -> "Move to the trash"
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Why discarding this row cannot be offered, or null when it can.
 *
 * One conflict, one sentence, every route to discard. `git restore
 * --source=HEAD` on an unmerged path is not refused by git: it keeps "ours",
 * marks the path resolved and staged, exits 0 and leaves `MERGE_HEAD` set — so
 * the panel would go quiet, the section would empty, and the next commit would
 * drop the incoming side of the merge with nothing ever said about it.
 */
internal fun discardRefusal(change: GitChange): String? = when {
    change.conflicted ->
        "${change.name} has a merge conflict. Resolve it in the editor and stage the " +
            "result — discarding it would keep one side of the merge and say nothing."
    else -> null
}

/**
 * An uncommit stopped at the door because some remote already holds HEAD:
 * the evidence the dialog names, the old message — read before the reset,
 * while HEAD still named the commit — that will refill the commit box when
 * the reset lands, and [sha], the commit all of it was read against, which
 * pins the eventual reset to that commit and no other.
 */
internal data class PendingUncommit(
    val remotes: List<String>,
    val priorMessage: String?,
    val sha: String,
)

/**
 * Whether the reset may run — null — or why it must not: the engine's
 * uncommit is a blind `git reset --soft HEAD^`, so [fresh] (HEAD as git
 * names it *now*) has to still be [expected] (the commit whose message and
 * pushed evidence were read). Anything else — a commit landed from a
 * terminal while the confirmation sat open, a HEAD lost altogether — would
 * soft-reset a commit nobody was asked about.
 */
internal fun uncommitPinRefusal(expected: String, fresh: String?): String? =
    if (fresh == expected) {
        null
    } else {
        "The commit at HEAD changed while Uncommit was waiting, so nothing was reset. " +
            "Check the last commit and try again."
    }

/** Which empty state the change-list region is in — see [gitPanelEmptyState]. */
internal enum class GitPanelEmptyState { Scanning, NoGit, NoRepo, Clean }

/**
 * The empty state's dispatch, Zed's `render_empty_state` order
 * (git_panel.rs:6920-6931) with the two states of our own in front.
 *
 * The order is load-bearing: a project outside any repository never runs git
 * at all — the engine's `status_for` answers "no repository" from the host
 * filesystem without a proot spawn, so `ran` is false there *by design* — and
 * reading that as "git is not installed" hid the Initialize Repository state
 * behind a complaint about a git that was fine. No-repo is answered first,
 * from the walk that actually ran; "git never ran" is only meaningful where
 * there was a repository to run it in.
 */
internal fun gitPanelEmptyState(state: GitPanelState): GitPanelEmptyState = when {
    !state.scanned -> GitPanelEmptyState.Scanning
    !state.hasRepo -> GitPanelEmptyState.NoRepo
    !state.ran -> GitPanelEmptyState.NoGit
    else -> GitPanelEmptyState.Clean
}

/**
 * Whether the clean tree's way out is offered — Zed shows "View Branch Diff"
 * on every branch that is not the main one: `!is_on_main_branch`, where main
 * means the name is exactly `main` or `master` (git_panel.rs:6935,
 * 7049-7059). No branch at all — a detached HEAD — is not the main branch
 * either, there as here.
 */
internal fun showsViewBranchDiff(branchName: String?): Boolean =
    branchName != "main" && branchName != "master"

/**
 * The Uncommit button's meta line — the command it stands for. Zed words it
 * `--soft` only when something is unstaged (git_panel.rs:6218-6231), though
 * the reset it runs is always soft (repository.rs:1492-1516); the wording is
 * kept as-is because parity is the point.
 */
internal fun uncommitMeta(hasUnstaged: Boolean): String =
    if (hasUnstaged) "git reset HEAD^ --soft" else "git reset HEAD^"

/**
 * The pushed-commit confirmation's sentence — Zed's own, the remotes
 * comma-joined (git_panel.rs:3216-3228).
 */
internal fun uncommitPushedDetail(remotes: List<String>): String =
    "This commit was already pushed to ${remotes.joinToString(", ")}."

/** What the discard item says it will do to *this* row. */
internal fun discardLabel(change: GitChange): String = when {
    // A rename is both halves at once, and "discard changes" describes neither.
    change.original != null -> "Undo the rename…"
    change.inHead -> "Discard changes…"
    change.isDirectory -> "Move the folder to the trash…"
    else -> "Move to the trash…"
}

/**
 * The split button's title — Zed's `commit_button_title()`
 * (git_panel.rs:5642-5656). Exactly four labels: staging anything makes it a
 * plain "Commit"/"Amend" of the index; with nothing staged the button promises
 * to stage every tracked change first — except that an amend with nothing
 * tracked either is still just "Amend", since amending needs no changes at
 * all. "Commit Tracked" shows even over a clean tree; whether it is *enabled*
 * is a different function's answer, there as here.
 */
internal fun commitButtonLabel(
    amendPending: Boolean,
    hasStaged: Boolean,
    hasTracked: Boolean,
): String = when {
    !amendPending -> if (hasStaged) "Commit" else "Commit Tracked"
    hasStaged || !hasTracked -> "Amend"
    else -> "Amend Tracked"
}

/**
 * Zed's `FileStatus::is_created` (crates/git/src/status.rs:183-192): untracked,
 * or Added on either half of the pair. A conflict is its own category and never
 * "created" — Zed's Unmerged variant falls through the same match arm.
 */
internal fun isCreatedChange(change: GitChange): Boolean {
    if (change.conflicted) return false
    return change.staged == GitFileStatus.Added || change.staged == GitFileStatus.Untracked ||
        change.unstaged == GitFileStatus.Added || change.unstaged == GitFileStatus.Untracked
}

/**
 * What "Commit Tracked" stages before it commits — Zed's
 * `change_entries_by_path()` filtered to `!status.is_created()`
 * (git_panel.rs:3103-3107): every changed path *except* the untracked and
 * newly added ones. A conflicted path passes the filter there as here; the
 * conflicts guard has already turned the commit back before this list is
 * asked for.
 */
internal fun trackedCommitPaths(entries: List<GitChange>): List<String> =
    entries.filterNot(::isCreatedChange).map { it.path }

/**
 * What `git::StageAll` sends — every path with anything left to stage: the
 * unstaged half of every row, and the conflicts, whose staging *is* the
 * resolution (the Conflicts section's own header already stages them). A row
 * wholly staged has nothing to add and is left out. Zed's version is
 * `git add --all` on the repository (git_panel.rs:2602-2604, 5741); by path
 * it comes to the same set.
 */
internal fun stageAllPaths(entries: List<GitChange>): List<String> =
    entries.filter { it.conflicted || it.unstaged != null }.map { it.path }

/** What `git::UnstageAll` sends — every path with a staged half (git_panel.rs:2606-2608). */
internal fun unstageAllPaths(entries: List<GitChange>): List<String> =
    entries.filter { it.staged != null }.map { it.path }

/**
 * Zed's `has_tracked_changes()` = `tracked_count > 0` (git_panel.rs:5162-5164),
 * whose count buckets conflicted and created entries elsewhere
 * (git_panel.rs:5129-5139) — so for the *label*, a conflict is not a tracked
 * change, even though the commit filter above would carry it.
 */
internal fun hasTrackedChanges(entries: List<GitChange>): Boolean =
    entries.any { !it.conflicted && !isCreatedChange(it) }

/**
 * Which section a row belongs to. The titles are Zed's own for grouping by
 * staging: "Conflicts", "Staged", "Unstaged" (git_panel.rs:641-645).
 */
internal enum class GitSection(val title: String) {
    Conflicts("Conflicts"),
    Staged("Staged"),
    Changes("Unstaged"),
}

/** The flat list the panel draws: section headers and file rows, in order. */
internal sealed interface GitPanelRow {
    val key: String

    data class SectionRow(
        val section: GitSection,
        /** Every path in it, for the section's own stage-all action. */
        val paths: List<String>,
    ) : GitPanelRow {
        override val key: String get() = "section:${section.name}"
    }

    data class FileRow(val section: GitSection, val change: GitChange) : GitPanelRow {
        // Keyed by section as well as path: a file that is staged *and*
        // modified again appears in two sections, and two rows sharing a key
        // is a crash in LazyColumn rather than a cosmetic problem.
        override val key: String get() = "${section.name}:${change.path}"
    }
}

/**
 * Group the changes the way Zed's panel does: conflicts first, because they
 * block everything else; then what is staged, next to the commit box that will
 * use it; then everything else.
 *
 * A file can appear twice, in Staged and in Changes. That is not a bug to fix
 * on this side — it is what `MM` means, and hiding half of it would be hiding
 * that staging captured a version of the file that is no longer the one on disk.
 */
internal fun gitPanelRows(state: GitPanelState): List<GitPanelRow> {
    val rows = ArrayList<GitPanelRow>()
    for (section in GitSection.entries) {
        val changes = when (section) {
            GitSection.Conflicts -> state.conflicts
            GitSection.Staged -> state.staged
            GitSection.Changes -> state.unstaged
        }
        if (changes.isEmpty()) continue
        rows += GitPanelRow.SectionRow(section, changes.map { it.path })
        changes.forEach { rows += GitPanelRow.FileRow(section, it) }
    }
    return rows
}

/** The letter git itself uses for that half of the pair. */
private fun statusLetter(change: GitChange, section: GitSection): String {
    if (change.conflicted) return "U"
    val status = if (section == GitSection.Staged) change.staged else change.unstaged
    return when (status) {
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
 * The engine's status, in the vocabulary the theme's colours are keyed by.
 *
 * Two enums of the same name exist because they answer different questions —
 * one is what git said, the other is what a row is painted — and this is the
 * one place they meet.
 */
private fun GitFileStatus?.forColours(): PanelStatus = when (this) {
    GitFileStatus.Modified -> PanelStatus.Modified
    GitFileStatus.Added -> PanelStatus.Added
    GitFileStatus.Deleted -> PanelStatus.Deleted
    GitFileStatus.Renamed -> PanelStatus.Renamed
    GitFileStatus.Conflicted -> PanelStatus.Conflicted
    GitFileStatus.Untracked -> PanelStatus.Untracked
    GitFileStatus.Ignored -> PanelStatus.Ignored
    null -> PanelStatus.None
}

/**
 * git's own complaint, recognised.
 *
 * Matched on the phrases rather than on an exit code because git says this
 * three different ways depending on version and on whether it found half an
 * identity: "unable to auto-detect email address", "Please tell me who you
 * are", and "empty ident name". All three mean the same thing and have the
 * same fix.
 */
internal fun needsIdentity(failure: String): Boolean {
    val text = failure.lowercase()
    return "unable to auto-detect email address" in text ||
        "please tell me who you are" in text ||
        "empty ident name" in text ||
        "no name was given" in text
}

/**
 * Who commits are by, asked at the moment git refuses to guess.
 *
 * It writes `user.name` and `user.email` into the *guest's* global config, so
 * it is answered once per userland rather than once per clone — and then it
 * runs the commit that was refused, because that is what the user pressed.
 */
@Composable
private fun IdentityForm(
    name: TextFieldValue,
    email: TextFieldValue,
    onName: (TextFieldValue) -> Unit,
    onEmail: (TextFieldValue) -> Unit,
    busy: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "git records who made each commit, and has nobody to record.",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted"),
        )
        IdentityField(value = name, onValue = onName, placeholder = "Your name")
        IdentityField(
            value = email,
            onValue = onEmail,
            placeholder = "you@example.com",
            keyboard = KeyboardType.Email,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GhostButton("Not now", enabled = !busy, onClick = onDismiss)
            Spacer(modifier = Modifier.width(8.dp))
            FilledButton(
                "Save and commit",
                enabled = !busy && name.text.isNotBlank() && email.text.isNotBlank(),
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun IdentityField(
    value: TextFieldValue,
    onValue: (TextFieldValue) -> Unit,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    /** Enter's meaning, where the field is the last one: null moves on. */
    onDone: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    // Zed's input: `min_h_8` 32px, `rounded_md` 6px, 1px `border`, `pl_2` /
    // `pr_1`, the text on `py_1` (search_bar.rs:69-79; buffer_search.rs:233).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("editor.background"))
            .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.placeholder", theme.color("text.muted")),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onDone == null) {
                        Modifier
                    } else {
                        Modifier.onPreviewKeyEvent { event ->
                            val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (event.type == KeyEventType.KeyDown && isEnter) {
                                onDone()
                                true
                            } else {
                                false
                            }
                        }
                    }
                ),
        )
    }
}

/** Zed's two tabs (`git_panel.rs:507`). */
enum class GitPanelTab { Changes, History }

/**
 * The tab strip, stroke for stroke from Zed's `render_tab_bar`
 * (git_panel.rs:6257-6325): `Tab::container_height` = 32px (tab.rs:83-85),
 * each half `flex_1` and centred with a `gap_1`. The *inactive* tab is set
 * back — `editor.background` at 0.6 alpha under a 1px bottom border in
 * `border` at 0.6 — while the active tab has neither, so it opens into the
 * panel below (git_panel.rs:6276-6282; gpui's unset border colour is
 * transparent, style.rs:746). Both swap to `element.hover` under the pointer
 * (git_panel.rs:6277), instantly, and a `BorderFaded` divider stands between
 * them (git_panel.rs:6313-6317; divider.rs:30).
 */
@Composable
private fun TabBar(tab: GitPanelTab, changeCount: Int, onTab: (GitPanelTab) -> Unit) {
    val theme = LocalZedTheme.current
    val fadedBorder = theme.color("border").copy(alpha = 0.6f)
    Row(modifier = Modifier.fillMaxWidth().height(BarHeight)) {
        for (candidate in GitPanelTab.entries) {
            val active = candidate == tab
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        when {
                            hovered -> theme.color("element.hover")
                            !active -> theme.color("editor.background").copy(alpha = 0.6f)
                            else -> Color.Transparent
                        }
                    )
                    .drawBehind {
                        if (!active) {
                            drawRect(
                                color = fadedBorder,
                                topLeft = Offset(0f, size.height - 1.dp.toPx()),
                                size = Size(size.width, 1.dp.toPx()),
                            )
                        }
                    }
                    .clickable(
                        interactionSource = interaction,
                        // Instant swap, no ripple — Zed's tabs never animate.
                        indication = null,
                        onClickLabel = "Show ${candidate.name}",
                    ) { onTab(candidate) }
                    .pointerHoverIcon(PointerIcon.Hand),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) theme.color("text") else theme.color("text.muted"),
                )
                // The count rides the Changes tab as a Small muted "(n)"
                // (git_panel.rs:6284-6290).
                if (candidate == GitPanelTab.Changes && changeCount > 0) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "($changeCount)",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted"),
                    )
                }
            }
            if (candidate == GitPanelTab.Changes) {
                VerticalDivider(thickness = 1.dp, color = fadedBorder)
            }
        }
    }
}

/**
 * What has been committed — Zed's History tab.
 *
 * A row is the subject, then who and when and which commit, which is what
 * Zed's own rows carry. Tapping one expands what it touched underneath it
 * rather than opening a view of its own: a phone has one work area, and the
 * question "what was in that commit" is usually answered by a glance at the
 * file list.
 */
@Composable
private fun HistoryList(
    page: CommitPage?,
    open: CommitDetails?,
    onOpen: (Commit) -> Unit,
    onOpenFile: (String) -> Unit,
    /** Hoisted by the panel so switching tabs keeps the scroll position. */
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val now = remember(page) { System.currentTimeMillis() / 1000L }
    when {
        page == null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Reading history…",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted"),
            )
        }
        page.error != null -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = page.error,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("error"),
                modifier = Modifier.padding(24.dp),
            )
        }
        page.commits.isEmpty() -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Nothing has been committed yet",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(24.dp),
            )
        }
        else -> LazyColumn(state = listState, modifier = modifier) {
            items(page.commits, key = { it.sha }) { commit ->
                CommitRow(
                    commit = commit,
                    now = now,
                    isOpen = open?.commit?.sha == commit.sha,
                    onClick = { onOpen(commit) },
                )
                if (open?.commit?.sha == commit.sha) {
                    CommitDetail(details = open, onOpenFile = onOpenFile)
                }
                // No divider: Zed's history rows meet edge to edge and are
                // told apart by hover alone (git_panel.rs:6718-6734).
            }
        }
    }
}

/**
 * One commit, in Zed's history-row anatomy (git_panel.rs:6718-6835): a
 * `py_1` / `px_2` column with `gap_0p5`, the subject a single truncated
 * default-size line beside its tag chips, and the meta underneath — author,
 * relative time, short sha — as Small muted labels between half-faded "•"
 * separators. Hover, like the open row, is `element.hover`.
 */
@Composable
private fun CommitRow(commit: Commit, now: Long, isOpen: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isOpen || hovered) theme.color("element.hover") else Color.Transparent
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Show what this commit changed",
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = commit.subject.ifBlank { "(no message)" },
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // Branch and tag chips beside the subject, in Zed's Chip clothes:
            // `px_1`, 1px `border`, `rounded_sm`, `element.background`
            // (git_panel.rs:6746-6764; chip.rs:106-115).
            for (name in commit.refs.take(3)) {
                Text(
                    text = name.removePrefix("HEAD -> "),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text"),
                    maxLines = 1,
                    modifier = Modifier
                        .background(
                            theme.color("element.background", theme.color("border.variant")),
                            RoundedCornerShape(4.dp),
                        )
                        .border(1.dp, theme.color("border"), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
            }
        }
        // Meta gap is `gap_1p5` (git_panel.rs:6839-6843); the dots sit in it
        // rather than carrying padding of their own.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = commit.author.ifBlank { "Unknown" },
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Dot(theme)
            Text(
                text = relativeTime(commit.authorTime, now),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
            )
            Dot(theme)
            Text(
                text = commit.shortSha,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                maxLines = 1,
            )
            if (commit.isMerge) {
                Dot(theme)
                Text(
                    text = "merge",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                )
            }
        }
    }
}

/** Zed's separator: "•" at Small size, muted, half faded (git_panel.rs:6711-6716). */
@Composable
private fun Dot(theme: to.eyed.seeker.code.ui.theme.ZedTheme) {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.muted").copy(alpha = 0.5f),
    )
}

/** What one commit said and touched. */
@Composable
private fun CommitDetail(details: CommitDetails, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val colours = remember(theme) {
        GitStatusColours.from(theme, theme.color("text"), theme.color("text.muted"))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("editor.background"))
            // The history row's own `px_2` grid (git_panel.rs:6724).
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val body = details.message.substringAfter('\n', "").trim()
        if (body.isNotEmpty()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
            )
        }
        Text(
            text = "${details.commit.authorEmail} · ${details.files.size} " +
                if (details.files.size == 1) "file" else "files",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
        )
        for (file in details.files) {
            // A file line is its label's line box — Zed's dense-list rule
            // (list_item.rs:365-368) — and the whole width is the tap target.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = "Open ${file.path}") { onOpenFile(file.path) }
                    .pointerHoverIcon(PointerIcon.Hand),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = file.status.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colours.colorFor(statusOf(file.status)),
                    modifier = Modifier.width(16.dp),
                )
                Text(
                    text = file.original?.let { "${it} → ${file.path}" } ?: file.path,
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** git's letter, in the vocabulary the panel paints with. */
private fun statusOf(letter: Char): to.eyed.seeker.code.ui.workspace.GitFileStatus = when (letter) {
    'A' -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Added
    'D' -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Deleted
    'R', 'C' -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Renamed
    else -> to.eyed.seeker.code.ui.workspace.GitFileStatus.Modified
}

/**
 * Zed's `render_changes_header` — the `min_h(Tab::container_height)` row above
 * the change list, `pl_1` / `pr_2`, with "View Diff" as a ghost button of
 * Small muted text at its start (git_panel.rs:5786-5809). Its right-hand
 * menus (view options, more-actions) have no counterpart here; pushing lives
 * in the repo footer, where Zed keeps its remote button too.
 */
@Composable
private fun ActionBar(
    state: GitPanelState,
    onViewDiff: () -> Unit,
    /** One of the stash pushes or pops — the Stash menu's rows. */
    onStash: (GitPanelCommand) -> Unit,
    onViewStash: () -> Unit,
) {
    var stashMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BarHeight)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostButton(
            label = "View Diff",
            enabled = !state.isClean,
            onClick = onViewDiff,
        )
        Spacer(modifier = Modifier.weight(1f))
        // The stash rows of Zed's more-actions menu (git_panel.rs:320-347),
        // under a button of their own so a finger finds them: Stash All
        // (untracked included), Stash Tracked, Stash Staged — each asking
        // for a message — then Stash Pop and View Stash. Greyed on the same
        // conditions Zed greys them.
        Box {
            GhostButton(
                label = "Stash",
                enabled = state.hasRepo,
                onClick = { stashMenuOpen = true },
            )
            val hasTracked = state.entries.any { it.inHead }
            ContextMenu(
                expanded = stashMenuOpen,
                onDismiss = { stashMenuOpen = false },
                items = listOf(
                    ContextMenuItem(
                        label = "Stash All",
                        enabled = !state.isClean,
                        onClick = { onStash(GitPanelCommand.StashAll) },
                    ),
                    ContextMenuItem(
                        label = "Stash Tracked",
                        enabled = hasTracked,
                        onClick = { onStash(GitPanelCommand.StashTracked) },
                    ),
                    ContextMenuItem(
                        label = "Stash Staged",
                        enabled = state.staged.isNotEmpty(),
                        onClick = { onStash(GitPanelCommand.StashStaged) },
                    ),
                    ContextMenuItem(
                        label = "Stash Pop",
                        separatorAbove = true,
                        onClick = { onStash(GitPanelCommand.StashPop) },
                    ),
                    ContextMenuItem(
                        label = "View Stash",
                        onClick = onViewStash,
                    ),
                ),
            )
        }
    }
}

/**
 * Zed's `StashMessageModal` (git_panel.rs:231-300): a one-line field for
 * the stash message, `Enter` stashing and `Esc` cancelling, titled by the
 * kind — "Stash All", "Stash Tracked", "Stash Staged". An empty message
 * lets git write its own `WIP on <branch>` line, as Zed's empty field does.
 */
@Composable
private fun StashMessageDialog(
    kind: StashKind,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var message by remember(kind) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(kind) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.label) },
        text = {
            Column {
                Text(
                    text = when (kind) {
                        StashKind.All -> "Every change, untracked files included, goes into the stash."
                        StashKind.Tracked -> "Changes to tracked files go into the stash."
                        StashKind.Staged -> "What is staged goes into the stash; the rest stays."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Box(modifier = Modifier.focusRequester(focus)) {
                    IdentityField(
                        value = message,
                        onValue = { message = it },
                        placeholder = "Stash message (optional)",
                        onDone = { onConfirm(message.text) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(message.text) }) { Text("Stash") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
