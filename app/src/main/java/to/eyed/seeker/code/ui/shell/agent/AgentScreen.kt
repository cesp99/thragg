package to.eyed.seeker.code.ui.shell.agent

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.AgentMention
import to.eyed.seeker.code.core.AgentNotifier
import to.eyed.seeker.code.core.AgentPastSession
import to.eyed.seeker.code.core.AgentPhase
import to.eyed.seeker.code.core.AgentSessionState
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.OrchRun
import to.eyed.seeker.code.core.SpettroAnswer
import to.eyed.seeker.code.core.SpettroAnswers
import to.eyed.seeker.code.core.SpettroQuestion
import to.eyed.seeker.code.core.SpettroSetup
import to.eyed.seeker.code.core.ToolCallStatus
import to.eyed.seeker.code.core.TranscriptRow
import to.eyed.seeker.code.core.foldOrchestration
import to.eyed.seeker.code.core.rememberAgentSession
import to.eyed.seeker.code.core.rememberAgentSessionList
import to.eyed.seeker.code.core.rememberSpettroQuestions
import to.eyed.seeker.code.solana.agents.SpettroInstall
import to.eyed.seeker.code.ui.agent.spettro.ConfigChips
import to.eyed.seeker.code.ui.agent.spettro.ConfigNotice
import to.eyed.seeker.code.ui.agent.spettro.ContextRing
import to.eyed.seeker.code.ui.agent.spettro.ContextSheet
import to.eyed.seeker.code.ui.agent.spettro.ContextWarningRow
import to.eyed.seeker.code.ui.agent.spettro.LiveRunPeek
import to.eyed.seeker.code.ui.agent.spettro.PermissionChoice
import to.eyed.seeker.code.ui.agent.spettro.PermissionChoiceSheet
import to.eyed.seeker.code.ui.agent.spettro.PermissionSheet
import to.eyed.seeker.code.ui.agent.spettro.PlanSheet
import to.eyed.seeker.code.ui.agent.spettro.PlanStrip
import to.eyed.seeker.code.ui.agent.spettro.QuestionForm
import to.eyed.seeker.code.ui.agent.spettro.QuestionSheet
import to.eyed.seeker.code.ui.agent.spettro.ReplayedSessionNotice
import to.eyed.seeker.code.ui.agent.spettro.SessionOpen
import to.eyed.seeker.code.ui.agent.spettro.SessionPicker
import to.eyed.seeker.code.ui.agent.spettro.SessionReplaySkeleton
import to.eyed.seeker.code.ui.agent.spettro.SessionScope
import to.eyed.seeker.code.ui.agent.spettro.SessionSearchField
import to.eyed.seeker.code.ui.agent.spettro.SpettroSetupBanner
import to.eyed.seeker.code.ui.agent.spettro.SpettroSetupScreen
import to.eyed.seeker.code.ui.agent.spettro.SpettroSpinner
import to.eyed.seeker.code.ui.agent.spettro.elapsedLabel
import to.eyed.seeker.code.ui.agent.spettro.sessionOpenMode
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.projects.AgentThreadSeed
import to.eyed.seeker.code.ui.shell.projects.ProjectsSheet
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.Notifications

// ---------------------------------------------------------------------------
// The pure half
// ---------------------------------------------------------------------------

/**
 * Every tool call the agent is stopped on, oldest first.
 *
 * The turn does not move until one of these is answered, which is why the
 * screen raises the sheet by itself rather than waiting to be asked, and why
 * the count is worth showing: with three parked prompts, answering one puts
 * the next one up.
 */
internal fun pendingApprovals(entries: List<AgentEntry>): List<AgentEntry.ToolCall> =
    entries.filterIsInstance<AgentEntry.ToolCall>()
        .filter { it.status == ToolCallStatus.WaitingForConfirmation && it.options.isNotEmpty() }

/**
 * The form hiding inside a permission request, when there is one.
 *
 * **Sniffed first, before anything draws Allow/Deny** (docs/SPETTRO.md W-10):
 * a prompt whose `_meta` carries `spettro.app/question` is not asking for
 * approval at all, it is one page of an ask-user form walked through the
 * permission channel because the client did not advertise the extension — or
 * because the agent chose to walk it. Drawing "Allow / Deny" over "Which
 * database?" turns a product question into a security decision.
 */
internal fun walkedQuestion(call: AgentEntry.ToolCall): SpettroQuestion? {
    val meta = call.permissionMeta ?: return null
    val json = runCatching { JSONObject(meta) }.getOrNull() ?: return null
    return SpettroQuestion.fromPermissionMeta(call.key, json)
}

/**
 * The forms waiting on the user: the session's own, plus any raised before a
 * session existed.
 *
 * A question that names a session is already in `state.questions`, so the
 * session-less poll can repeat one the screen is showing; matching on id is
 * what stops the same form being drawn twice.
 */
internal fun mergedQuestions(
    sessionQuestions: List<SpettroQuestion>,
    loose: List<SpettroQuestion>,
): List<SpettroQuestion> =
    sessionQuestions + loose.filter { free -> sessionQuestions.none { it.id == free.id } }

/** `2 files changed`, or null when the review bar has nothing to say. */
internal fun reviewLabel(editedFiles: Int): String? = when {
    editedFiles <= 0 -> null
    editedFiles == 1 -> "1 file changed"
    else -> "$editedFiles files changed"
}

/** The runs a peek should be given: the ones a fold produced, in order. */
internal fun runsIn(rows: List<TranscriptRow>): List<OrchRun> =
    rows.mapNotNull { (it as? TranscriptRow.Run)?.run }

/**
 * What the empty transcript says.
 *
 * docs/SPETTRO.md, step 6, to the word: the question over the place it will be
 * answered about. A blank rectangle here reads as a crash, and this is the
 * screen a fresh install lands on straight after creating a program.
 */
internal fun emptyHeadline(): String = "How can I help?"

internal fun emptySubhead(projectName: String?): String =
    if (projectName.isNullOrBlank()) "No project is open" else "Working in $projectName"

/**
 * Whether the vertical budget has room for the plan strip and the run peek.
 *
 * With the IME open the keyboard eats ~340 dp of 890 and these two collapse to
 * zero, so at least two transcript rows stay visible (docs/SPETTRO.md, "Screen
 * shell"). They are *hidden*, never shrunk: this layout animates opacity and
 * transform only, because these surfaces repaint several times a second during
 * a fan-out and an animated height would fight the repaint.
 */
internal fun showsSecondaryBands(imeVisible: Boolean): Boolean = !imeVisible

/** Which modal surface the destination has up. */
private sealed interface AgentSheet {
    data object Projects : AgentSheet
    data object Config : AgentSheet
    data object Overflow : AgentSheet
    data object Sessions : AgentSheet
    data object Plan : AgentSheet
    data object Context : AgentSheet
    data object Mentions : AgentSheet
    data object Picker : AgentSheet
    data object PermissionChoice : AgentSheet
    data class Approval(val key: String) : AgentSheet
    data class Form(val id: String) : AgentSheet
}

/**
 * Whether the one-time "How much should Spettro ask?" sheet has been answered
 * on this device.
 *
 * SharedPreferences rather than settings.json: it is not a setting anybody
 * edits, it is a record that a question was asked, and putting it in the file
 * the user opens in the editor would invite them to flip a flag whose only
 * effect is to ask them again.
 */
private const val ASK_PREFS = "seeker.agent"
private const val ASKED_PERMISSION_KEY = "permission_choice_answered"

private fun permissionChoiceAnswered(context: Context): Boolean =
    context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE)
        .getBoolean(ASKED_PERMISSION_KEY, false)

private fun markPermissionChoiceAnswered(context: Context) {
    context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(ASKED_PERMISSION_KEY, true)
        .apply()
}

// ---------------------------------------------------------------------------
// The destination
// ---------------------------------------------------------------------------

/**
 * Agent — the whole Spettro superset on a 400 x 890 dp column.
 *
 * The layout is docs/SPETTRO.md's "Screen shell" from top to bottom: a 48 dp
 * app bar carrying identity and the context ring and nothing else; the
 * transcript, folded so a workflow's two hundred sibling tool calls are one
 * card; the run ticker at its tail; the sticky review bar; the plan strip and
 * the live-run peek, both of which collapse when the keyboard is up; the chip
 * row; and the composer.
 *
 * Three decisions here are load-bearing rather than cosmetic:
 *
 *  1. **The composer stays enabled while a turn runs**, and the button reads
 *     *Steer*. Steering is the whole reason a phone is worth having in this
 *     loop — you correct a run from a bus — and it is *not* a cancel: the text
 *     is delivered to the turn already in flight at its next step boundary,
 *     the steering prompt itself ends immediately, and the original turn keeps
 *     going. Cancel is a separate ■ button beside it (see [AgentComposer]).
 *  2. **A parked question or permission raises its sheet by itself.** The turn
 *     is stopped until it is answered; a prompt that has scrolled away is an
 *     app that looks hung. Dismissing the sheet is not answering it — the
 *     request stays parked and the row that raised it stays tappable.
 *  3. **`permissionMeta` is sniffed before anything draws Allow/Deny.** A
 *     walked form is a product question, not a security decision (W-10).
 *
 * Everything Spettro-specific gates on `state.spettro` being non-null. Null is
 * not an error — it is a generic ACP agent, which gets the same screen without
 * steering, without the question sheet and with whatever `configOptions` it
 * happens to advertise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentScreen(state: ShellState, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = state.project
    val agent = AgentSessions.agent
    val thread = AgentSessions.active
    val sessionId = AgentSessions.sessionId.takeIf { it >= 0 }
    val snapshot = rememberAgentSession(sessionId)
    val session = snapshot.state
    val composerFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Held outside the list: a LazyColumn destroys an item's state when it
    // scrolls off, so a card the user opened would forget it and a running
    // command would be one grey line for its whole life.
    val expanded = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    var sheet by remember { mutableStateOf<AgentSheet?>(null) }
    // Requests whose sheet the user put away. Dismissing is not answering, so
    // the request stays parked; what this stops is the sheet coming straight
    // back up on the next poll.
    val dismissed = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    var sessionQuery by remember { mutableStateOf("") }
    var sessionScope by remember { mutableStateOf(SessionScope.PROJECT) }
    var lockedNotice by remember { mutableStateOf<String?>(null) }

    val rows = remember(snapshot.conversation) {
        foldOrchestration(snapshot.conversation.entries)
    }
    val approvals = remember(snapshot.conversation) {
        pendingApprovals(snapshot.conversation.entries)
    }
    // Polled separately because there may be no session: a form can be raised
    // while the agent is authenticating, and one left unanswered blocks it for
    // ever.
    val loose = rememberSpettroQuestions(agent != null)
    val questions = mergedQuestions(session.questions, loose)

    // The panel being *on screen* is what silences the background watcher and
    // takes its notification down.
    DisposableEffect(Unit) {
        AgentSessions.panelVisible = true
        onDispose { AgentSessions.panelVisible = false }
    }
    LaunchedEffect(Unit) { AgentNotifier.dismiss(context) }
    // Leaving with the agent still blocked or still working is what the nav
    // bar's dot is for: it says "something happened over there" for the case
    // the watcher's notification cannot cover, which is the app in the
    // foreground on another destination.
    // `rememberUpdatedState` and a keyless effect, not a keyed one: a
    // `DisposableEffect(needsUser, isBusy)` runs its *dispose* every time
    // either value moves, so the badge would light while the user was sitting
    // on the very screen it points at.
    val leavingBlocked by rememberUpdatedState(session.needsUser > 0 || session.isBusy)
    DisposableEffect(Unit) {
        onDispose { if (leavingBlocked) state.agentAttention = true }
    }

    // Opening a thread is the destination's own business: with an agent chosen
    // and a project open there is nothing else the user could mean. A no-op
    // once this project has one.
    LaunchedEffect(agent, project?.id) {
        val open = project ?: return@LaunchedEffect
        if (agent != null) AgentSessions.open(open.id, open.rootName, open.rootPath)
    }
    // The bundled agent registers itself the first time this screen is opened
    // with nothing configured. Idempotent and cheap when there is nothing to
    // do — a settings read and a stat — but it blocks, so it goes to IO.
    LaunchedEffect(agent) {
        if (agent != null || !AgentSessions.isSupported) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { SpettroInstall.ensureRegistered(context) }
        when (result) {
            is SpettroInstall.Result.Registered -> AgentSessions.choose(result.agent)
            is SpettroInstall.Result.Unconfirmed -> AgentSessions.choose(result.agent)
            // Not a toast: the empty state below says it, with the way out in
            // it, and a toast over a screen that already explains itself is
            // noise.
            is SpettroInstall.Result.Failed -> Unit
        }
    }
    // Stamp the agent's own title and session id onto the thread, so the
    // picker can tell a conversation that is already open from one that is not.
    LaunchedEffect(session.title, session.acpSessionId, thread) {
        session.title?.let { title -> thread?.title = title }
        session.acpSessionId?.let { id -> thread?.acpSessionId = id }
    }
    // A chip tapped while the agent was still starting was queued rather than
    // dropped; this is where it goes out.
    LaunchedEffect(session.phase) {
        if (session.phase == AgentPhase.Ready) AgentSessions.flushQueuedConfig()
    }
    // The gate, immediately after the handshake and never before it: only a
    // *successful* providers/list can say NEEDED, and NEEDED is the only
    // answer that blocks (docs/SPETTRO.md, step 2).
    LaunchedEffect(session.spettro, session.acpSessionId) {
        if (session.spettro != null) SpettroSetup.refreshProviders()
    }
    // Spettro saves after every prompt turn, so a list that is not refreshed is
    // stale within one message.
    LaunchedEffect(session.isBusy) {
        if (!session.isBusy) AgentSessions.refreshSessionList()
    }
    // The one-time permission decision, once a session and its options exist.
    LaunchedEffect(session.phase, session.configOptions) {
        if (sheet != null || session.phase != AgentPhase.Ready) return@LaunchedEffect
        if (session.toolbar.permission == null) return@LaunchedEffect
        val answered = withContext(Dispatchers.IO) { permissionChoiceAnswered(context) }
        if (!answered) sheet = AgentSheet.PermissionChoice
    }
    // "Re-tapping the current destination scrolls it to the newest message."
    //
    // The newest message is at the END: this list is not `reverseLayout`, so
    // index 0 is the *first* thing ever said in the thread. `rows.size` is the
    // trailing `tail` item (the ticker / stop notice), which is the one row
    // that is always below the last message — and the same target the arrival
    // effect below uses, so a retap and a new row land in the same place.
    val retapSeen = remember { intArrayOf(state.retapCount) }
    LaunchedEffect(state.retapCount) {
        if (state.retapCount != retapSeen[0]) {
            retapSeen[0] = state.retapCount
            runCatching { listState.animateScrollToItem(rows.size) }
        }
    }
    // The tail is where a conversation is read from, and a streamed answer
    // that arrives off-screen is an answer nobody sees.
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) runCatching { listState.animateScrollToItem(rows.size) }
    }
    // A program scaffolded next door wants its first sentence said here.
    LaunchedEffect(project?.id, thread) {
        val open = project ?: return@LaunchedEffect
        val seeded = AgentThreadSeed.take(open.id) ?: return@LaunchedEffect
        AgentSeams.offer(seeded.prompt, listOf(AgentMention.File(seeded.openPath)))
    }

    // A form or an approval raises itself. `AgentSheet.Approval` carries the
    // key rather than the call so the sheet follows the request across polls —
    // the entry list is replaced wholesale on every one of them.
    val nextForm = questions.firstOrNull { dismissed[it.id] != true }
    val nextApproval = approvals.firstOrNull { dismissed[it.key] != true }
    LaunchedEffect(nextForm?.id, nextApproval?.key, sheet) {
        if (sheet != null) return@LaunchedEffect
        when {
            nextForm != null -> sheet = AgentSheet.Form(nextForm.id)
            nextApproval != null -> sheet = AgentSheet.Approval(nextApproval.key)
        }
    }
    // …and the phone in a pocket is told, loudly, that the run has stopped for
    // it. Cancelled the moment nothing is waiting — see [AgentNotifier.waiting].
    val waitingCount = questions.size + approvals.size
    LaunchedEffect(waitingCount, AgentSessions.appInForeground) {
        val name = session.agent?.agentName ?: agent?.name ?: "Agent"
        if (waitingCount > 0 && !AgentSessions.appInForeground) {
            AgentNotifier.waiting(context, name, AgentNotifier.waitingMessage(waitingCount))
        } else {
            AgentNotifier.clearWaiting(context)
        }
    }
    DisposableEffect(Unit) { onDispose { AgentNotifier.clearWaiting(context) } }

    val bands = showsSecondaryBands(WindowInsets.isImeVisible)
    val liveRuns = remember(rows) { runsIn(rows) }
    var peekOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background", theme.color("surface.background"))),
    ) {
        AgentBar(
            projectName = project?.rootName,
            state = session,
            onProjects = { sheet = AgentSheet.Projects },
            onConfig = { sheet = AgentSheet.Config },
            onContext = { sheet = AgentSheet.Context },
            onNewThread = {
                val open = project ?: return@AgentBar
                AgentSessions.newThread(open.id, open.rootName, open.rootPath)
            },
            onOverflow = { sheet = AgentSheet.Overflow },
        )
        HorizontalDivider(color = theme.color("border", MaterialTheme.colorScheme.outline))

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                // The `play` edition has no Linux userland and never will, so
                // this is an honest dead end rather than a broken screen.
                !AgentSessions.isSupported -> AgentEmpty(
                    line = "This edition has no Linux userland, so it cannot run an agent.",
                    action = null,
                    onAction = {},
                )

                project == null -> AgentEmpty(
                    line = "No project is open.",
                    action = "Projects & tools",
                    onAction = { sheet = AgentSheet.Projects },
                )

                // The setup takeover, and only for a NEEDED gate: UNKNOWN
                // never blocks, because not knowing is not "no".
                SpettroSetup.isBlocking -> SpettroSetupScreen(
                    state = state,
                    onSkip = { SpettroSetup.skip() },
                    onDone = { scope.launch { SpettroSetup.refreshProviders() } },
                    quotedError = session.error,
                )

                agent == null -> AgentEmpty(
                    line = "No agent yet. Spettro is a 15 MB download and needs no Node, " +
                        "no Python and no compiler.",
                    action = "Set up Spettro",
                    onAction = { state.push(Route.Setup) },
                )

                AgentSessions.startError != null -> AgentEmpty(
                    line = AgentSessions.startError.orEmpty(),
                    action = "Try again",
                    onAction = {
                        AgentSessions.newThread(project.id, project.rootName, project.rootPath)
                    },
                )

                AgentSessions.isStarting -> AgentEmpty(
                    line = "Starting " + agent.name + "…",
                    action = null,
                    onAction = {},
                )

                sessionId == null -> AgentEmpty(
                    line = "No thread open.",
                    action = "New thread",
                    onAction = {
                        AgentSessions.newThread(project.id, project.rootName, project.rootPath)
                    },
                )

                // A loaded session streams its whole transcript back before
                // the response returns; a skeleton says so rather than showing
                // an empty conversation that fills in a second later.
                thread?.expectsReplay == true && rows.isEmpty() -> SessionReplaySkeleton()

                rows.isEmpty() -> AgentEmpty(
                    line = emptyHeadline(),
                    detail = emptySubhead(project.rootName),
                    action = null,
                    onAction = {},
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (thread?.isReopened == true) ReplayedSessionNotice()
                    AgentTranscript(
                        shell = state,
                        rows = rows,
                        listState = listState,
                        expanded = expanded,
                        onOpenPath = { path -> state.push(Route.Diff(path)) },
                        onOpenPermission = { call -> sheet = AgentSheet.Approval(call.key) },
                        onRestoreCheckpoint = { index ->
                            AgentSessions.restoreCheckpoint(index)
                        },
                        tail = {
                            TranscriptTail(
                                state = session,
                                onRetry = { AgentSessions.retryLastPrompt() },
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Everything below here is pinned. The review bar first, because it is
        // the trust surface and it must not scroll away.
        reviewLabel(session.editedFiles)?.let { label ->
            ReviewBar(label) { state.push(Route.Changes) }
        }
        if (SpettroSetup.needsBanner) {
            SpettroSetupBanner(onOpen = { SpettroSetup.unskip() })
        }
        ContextWarningRow(
            usage = session.usage,
            onOpenGauge = { sheet = AgentSheet.Context },
        )
        val refusal = session.notice ?: AgentSessions.lastRefusal
        if (refusal != null) {
            ConfigNotice(
                text = refusal,
                onDismiss = {
                    AgentSessions.clearRefusal()
                    AgentSessions.clearNotice()
                },
            )
        }
        lockedNotice?.let { text ->
            ConfigNotice(text = text, onDismiss = { lockedNotice = null })
        }
        if (bands && session.plan.isNotEmpty()) {
            PlanStrip(plan = session.plan, onExpand = { sheet = AgentSheet.Plan })
        }
        if (bands) {
            LiveRunPeek(
                runs = liveRuns,
                expanded = peekOpen,
                onToggle = { peekOpen = !peekOpen },
            )
        }
        ConfigChips(
            toolbar = session.toolbar,
            busy = session.phase == AgentPhase.Starting,
            onSelect = { sheet = AgentSheet.Config },
            onToggleUltra = { option, value ->
                AgentSessions.setConfigOption(option.id, configValueJson(value))
            },
            onLockedTap = { lockedNotice = to.eyed.seeker.code.core.ULTRA_LOCK_REASON },
        )
        AgentComposer(
            shell = state,
            state = session,
            thread = thread,
            projectName = project?.rootName,
            enabled = session.canPrompt && sessionId != null,
            focus = composerFocus,
            onOpenMentions = { sheet = AgentSheet.Mentions },
            onStop = { AgentSessions.cancelTurn() },
            onSteered = {},
        )
    }

    AgentSheets(
        state = state,
        sheet = sheet,
        session = session,
        approvals = approvals,
        questions = questions,
        sessionQuery = sessionQuery,
        sessionScope = sessionScope,
        onSessionQuery = { sessionQuery = it },
        onSessionScope = { sessionScope = it },
        onDismiss = { key ->
            if (key != null) dismissed[key] = true
            sheet = null
        },
        onSheet = { sheet = it },
        onDismissedAnswered = { key -> dismissed.remove(key) },
        onMention = { mention ->
            thread?.let { open ->
                if (mention !in open.draftMentions) open.draftMentions.add(mention)
                // A mention that stands as a token in the text is only sent
                // when the token is still there, so the token goes in with it.
                mention.textToken?.let { token ->
                    open.draft = (open.draft.trimEnd() + " @" + token + " ").trimStart()
                }
            }
        },
        onPermissionChosen = { value ->
            AgentSessions.setConfigOption("permission", configValueJson(value))
            markPermissionChoiceAnswered(context)
            sheet = null
        },
    )
}

/**
 * Every sheet the destination can have up, in one place.
 *
 * Lifted out of [AgentScreen] because the body of that function is the layout
 * and this is the modal layer over it; keeping them together made the `when`
 * over the sheet unreadable at exactly the point where the routing rules — a
 * walked form beats an approval — have to be obvious.
 */
@Composable
private fun AgentSheets(
    state: ShellState,
    sheet: AgentSheet?,
    session: AgentSessionState,
    approvals: List<AgentEntry.ToolCall>,
    questions: List<SpettroQuestion>,
    sessionQuery: String,
    sessionScope: SessionScope,
    onSessionQuery: (String) -> Unit,
    onSessionScope: (SessionScope) -> Unit,
    onDismiss: (key: String?) -> Unit,
    onSheet: (AgentSheet?) -> Unit,
    onDismissedAnswered: (String) -> Unit,
    onMention: (AgentMention) -> Unit,
    onPermissionChosen: (String) -> Unit,
) {
    val project = state.project
    val workspace = rememberCodeWorkspace()

    when (sheet) {
        null -> Unit

        AgentSheet.Projects -> ProjectsSheet(state = state, onDismiss = { onDismiss(null) })

        AgentSheet.Config -> AgentConfigSheet(
            shell = state,
            state = session,
            onPick = { option, valueJson ->
                AgentSessions.setConfigOption(option.id, valueJson)
            },
            onOpenContext = { onSheet(AgentSheet.Context) },
            onLocked = {
                Notifications.info(
                    to.eyed.seeker.code.core.ULTRA_LOCK_REASON,
                    key = "ultra-locked",
                )
            },
            onDismiss = { onDismiss(null) },
        )

        AgentSheet.Overflow -> AgentOverflowSheet(
            shell = state,
            items = listOf(
                OverflowItem("Sessions", "Spettro's own saved conversations") {
                    AgentSessions.refreshSessionList()
                    onSheet(AgentSheet.Sessions)
                },
                OverflowItem(
                    label = "Plan",
                    detail = if (session.plan.isEmpty()) "nothing planned yet" else null,
                    enabled = session.plan.isNotEmpty(),
                ) { onSheet(AgentSheet.Plan) },
                OverflowItem("Context", "what the window is holding") {
                    onSheet(AgentSheet.Context)
                },
                OverflowItem("Review changes", reviewLabel(session.editedFiles)) {
                    onDismiss(null)
                    state.push(Route.Changes)
                },
                OverflowItem("How much should Spettro ask?", "the permission decision") {
                    onSheet(AgentSheet.PermissionChoice)
                },
                OverflowItem("Agent", session.agent?.agentName ?: "not connected") {
                    onSheet(AgentSheet.Picker)
                },
                OverflowItem("Settings") {
                    onDismiss(null)
                    state.push(Route.Settings)
                },
            ),
            onDismiss = { onDismiss(null) },
        )

        AgentSheet.Picker -> AgentPickerSheet(
            shell = state,
            onDismiss = { onDismiss(null) },
            onOpenSettings = {
                onDismiss(null)
                state.push(Route.Settings)
            },
            onOpenSetup = {
                onDismiss(null)
                state.push(Route.Setup)
            },
        )

        AgentSheet.Plan -> PlanSheet(
            state = state,
            plan = session.plan,
            onDismiss = { onDismiss(null) },
        )

        AgentSheet.Context -> ContextSheet(
            state = state,
            usage = session.usage,
            turnUsage = session.turnUsage,
            // `/compact` is a prompt on the wire like any other command; the
            // agent owns what it means.
            onCompact = { AgentSessions.prompt("/compact", emptyList(), emptyList()) {} },
            onToggleAutoCompact = { on ->
                AgentSessions.prompt(
                    "/compact auto " + if (on) "on" else "off",
                    emptyList(),
                    emptyList(),
                ) {}
            },
            onDismiss = { onDismiss(null) },
        )

        AgentSheet.Mentions -> MentionSheet(
            shell = state,
            project = project,
            workspace = workspace,
            onPick = onMention,
            onDismiss = { onDismiss(null) },
        )

        AgentSheet.PermissionChoice -> {
            val option = session.toolbar.permission
            PermissionChoiceSheet(
                state = state,
                options = option?.choices.orEmpty().map { choice ->
                    PermissionChoice(choice.value, choice.name, choice.description)
                },
                onChoose = onPermissionChosen,
                onDismiss = { onDismiss(null) },
            )
        }

        AgentSheet.Sessions -> SessionsSheet(
            state = state,
            session = session,
            query = sessionQuery,
            scope = sessionScope,
            onQuery = onSessionQuery,
            onScope = onSessionScope,
            onDismiss = { onDismiss(null) },
        )

        is AgentSheet.Approval -> {
            val call = approvals.firstOrNull { it.key == sheet.key }
            if (call == null) {
                // Answered from somewhere else, or the turn moved on. Nothing
                // to draw and nothing to say — and the close goes through an
                // effect, because writing the sheet away *during* composition
                // is a state write the same frame already read.
                LaunchedEffect(sheet) { onDismiss(null) }
            } else {
                val walked = walkedQuestion(call)
                if (walked != null) {
                    // W-10: a form walked through the permission channel. The
                    // question sheet, and a reply that names the option *and*
                    // tags what the option meant.
                    QuestionSheet(
                        state = state,
                        request = walked,
                        onAnswer = { answers ->
                            val reply = QuestionForm.permissionReply(answers, call.options)
                            if (reply != null) {
                                AgentSessions.respondToPermission(
                                    call.id,
                                    reply.optionId,
                                    reply.answerMetaJson,
                                )
                            }
                            onDismissedAnswered(call.key)
                            onDismiss(null)
                        },
                        onDecline = {
                            val reject = call.options.firstOrNull { !it.isAllow }
                                ?: call.options.lastOrNull()
                            if (reject != null) {
                                AgentSessions.respondToPermission(
                                    call.id,
                                    reject.id,
                                    SpettroAnswers.DECLINED_META,
                                )
                            }
                            onDismissedAnswered(call.key)
                            onDismiss(null)
                        },
                        onDismiss = { onDismiss(call.key) },
                        queuePosition = approvals.indexOf(call) + 1,
                        queueDepth = approvals.size,
                    )
                } else {
                    PermissionSheet(
                        state = state,
                        request = call,
                        onSelect = { option, answerMeta ->
                            AgentSessions.respondToPermission(call.id, option.id, answerMeta)
                            onDismissedAnswered(call.key)
                            onDismiss(null)
                        },
                        onDismiss = { onDismiss(call.key) },
                        queuePosition = approvals.indexOf(call) + 1,
                        queueDepth = approvals.size,
                    )
                }
            }
        }

        is AgentSheet.Form -> {
            val form = questions.firstOrNull { it.id == sheet.id }
            if (form == null) {
                LaunchedEffect(sheet) { onDismiss(null) }
            } else {
                QuestionSheet(
                    state = state,
                    request = form,
                    onAnswer = { answers: List<SpettroAnswer> ->
                        AgentSessions.answerQuestion(form.id, answers, form.version)
                        onDismissedAnswered(form.id)
                        onDismiss(null)
                    },
                    // Declining is all-or-nothing and travels at the top level.
                    onDecline = {
                        AgentSessions.answerQuestion(form.id, null, form.version)
                        onDismissedAnswered(form.id)
                        onDismiss(null)
                    },
                    onDismiss = { onDismiss(form.id) },
                    queuePosition = questions.indexOf(form) + 1,
                    queueDepth = questions.size,
                )
            }
        }
    }

    // Nothing above touches the engine on the main thread; the two that would
    // (the setup gate, the session list) are suspending and already on IO.
    LaunchedEffect(sheet) {
        if (sheet is AgentSheet.Sessions) AgentSessions.refreshSessionList()
    }
}

/**
 * Spettro's own conversations, listed from its on-disk store.
 *
 * The list is `session/list` rather than an app-local one, so a conversation
 * started in the Spettro TUI on this device is here and one started here is
 * there. The search field is in the scaffold's bottom slot, above the IME,
 * because that is where every field in this app lives.
 */
@Composable
private fun SessionsSheet(
    state: ShellState,
    session: AgentSessionState,
    query: String,
    scope: SessionScope,
    onQuery: (String) -> Unit,
    onScope: (SessionScope) -> Unit,
    onDismiss: () -> Unit,
) {
    val list = rememberAgentSessionList(enabled = true, refreshToken = AgentSessions.sessionListToken)
    val project = state.project
    val canReplay = session.agent?.capabilities?.loadSession ?: false
    val open = AgentSessions.threads.mapNotNull { it.acpSessionId }.toSet()

    fun open(past: AgentPastSession, force: SessionOpen? = null) {
        val root = project ?: return
        val already = AgentSessions.threadFor(past.sessionId)
        if (already != null && force == null) {
            AgentSessions.select(already)
            onDismiss()
            return
        }
        val mode = force ?: sessionOpenMode(alreadyOpen = already != null, canReplay = canReplay)
        when (mode) {
            SessionOpen.LOAD ->
                AgentSessions.loadSession(root.id, root.rootName, root.rootPath, past.sessionId)
            SessionOpen.RESUME ->
                AgentSessions.resumeSession(root.id, root.rootName, root.rootPath, past.sessionId)
        }
        onDismiss()
    }

    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = "Sessions",
        field = { SessionSearchField(query = query, onQueryChange = onQuery) },
    ) {
        SessionPicker(
            sessions = list.sessions,
            scope = scope,
            query = query,
            onOpen = { past -> open(past) },
            onResume = { past -> open(past, SessionOpen.RESUME) },
            onNew = {
                val root = project ?: return@SessionPicker
                AgentSessions.newThread(root.id, root.rootName, root.rootPath)
                onDismiss()
            },
            onScopeChange = onScope,
            openSessionIds = open,
            canReplay = canReplay,
            loading = list.loading,
            error = list.error,
        )
    }
}

/**
 * The 48 dp app bar: who you are talking to, and the one number that changes
 * what you should do next.
 *
 * Nothing else is up here. The plan, the git state and the statistics are in
 * the overflow, because a bar that carries five readings is a bar nobody
 * reads (docs/SPETTRO.md, "Screen shell").
 */
@Composable
private fun AgentBar(
    projectName: String?,
    state: AgentSessionState,
    onProjects: () -> Unit,
    onConfig: () -> Unit,
    onContext: () -> Unit,
    onNewThread: () -> Unit,
    onOverflow: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val agentName = state.agent?.agentName ?: "Agent"
    val mode = state.toolbar.mode?.currentLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(theme.color("status_bar.background", MaterialTheme.colorScheme.surface))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = (projectName ?: "No project") + " ▾",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .touchTarget()
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClickLabel = "Projects", onClick = onProjects)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
        Text(
            text = if (mode.isNullOrBlank()) agentName else "$agentName · $mode ▾",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .weight(1f)
                .touchTarget()
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClickLabel = "Agent settings", onClick = onConfig)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
        ContextRing(usage = state.usage, onClick = onContext)
        BarAction("＋", "New thread", onNewThread)
        BarAction("⋮", "More", onOverflow)
    }
}

@Composable
private fun BarAction(label: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

/**
 * The end of the transcript: the run ticker, the stop-reason notice and any
 * error the turn ended with.
 *
 * The ticker is Spettro's braille spinner rather than a Material progress
 * ring — this is brand, and it is also the only thing on screen during a
 * two-minute silent turn that says work is happening rather than nothing.
 */
@Composable
private fun TranscriptTail(state: AgentSessionState, onRetry: () -> Unit) {
    val theme = LocalZedTheme.current
    var startedAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(0L) }
    LaunchedEffect(state.isBusy) {
        if (!state.isBusy) {
            startedAt = 0L
            return@LaunchedEffect
        }
        startedAt = System.currentTimeMillis()
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.isBusy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpettroSpinner(
                    color = theme.color("text.accent", MaterialTheme.colorScheme.primary)
                )
                Text(
                    text = tickerLabel(
                        elapsedMillis = if (startedAt == 0L) 0L else (now - startedAt),
                        tokens = state.turnUsage?.totalTokens ?: 0L,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
        stopReasonNotice(state.stopReason)?.let { notice ->
            Text(
                text = notice.text,
                style = MaterialTheme.typography.labelSmall,
                color = if (notice.isError) {
                    theme.color("error", MaterialTheme.colorScheme.error)
                } else {
                    theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("error", MaterialTheme.colorScheme.error),
            )
            if (state.canRetry) {
                Text(
                    text = "Try again",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .touchTarget()
                        .clickable(onClickLabel = "Try again", onClick = onRetry)
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * `1m 07s · 3.4k tok`, or just the clock before anything has been spent.
 *
 * The token figure is the *turn's*, not the context occupancy: occupancy falls
 * after a compaction and a ticker that ran backwards would be describing the
 * wrong quantity.
 */
internal fun tickerLabel(elapsedMillis: Long, tokens: Long): String {
    val clock = elapsedLabel(elapsedMillis.coerceAtLeast(0))
    if (tokens <= 0) return clock
    val spent = if (tokens >= 1000) {
        String.format(java.util.Locale.US, "%.1fk", tokens / 1000.0)
    } else {
        tokens.toString()
    }
    return "$clock · $spent tok"
}

/**
 * `2 files changed · Review →` — the trust surface, pinned above the composer.
 *
 * It must not scroll away: this is the count of files an agent has written
 * that nobody has looked at, and it is the one number in the app that is worth
 * interrupting a conversation for.
 */
@Composable
private fun ReviewBar(label: String, onOpen: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(theme.color("element.background", MaterialTheme.colorScheme.surface))
            .clickable(onClickLabel = "Review changes", onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Review →",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
        )
    }
}

/** One line of body text and at most one button — never a blank rectangle. */
@Composable
private fun AgentEmpty(
    line: String,
    onAction: () -> Unit,
    action: String?,
    detail: String? = null,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .padding(top = 12.dp)
                    .touchTarget()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = action, onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
