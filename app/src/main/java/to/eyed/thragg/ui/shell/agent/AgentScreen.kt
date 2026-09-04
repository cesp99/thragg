// `ThraggTopBar` takes a `TopAppBarScrollBehavior?`, which is still an
// experimental type in material3 1.4.0, so every caller of it repeats this one
// line — the component's own KDoc says so. Nothing else in this file is
// experimental.
@file:OptIn(ExperimentalMaterial3Api::class)

package to.eyed.thragg.ui.shell.agent

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentChoice
import to.eyed.thragg.core.AgentEntry
import to.eyed.thragg.core.AgentMention
import to.eyed.thragg.core.AgentNotifier
import to.eyed.thragg.core.AgentPastSession
import to.eyed.thragg.core.AgentPhase
import to.eyed.thragg.core.AgentSessionState
import to.eyed.thragg.core.AgentSessions
import to.eyed.thragg.core.OrchRun
import to.eyed.thragg.core.SpettroAnswer
import to.eyed.thragg.core.SpettroAnswers
import to.eyed.thragg.core.SpettroQuestion
import to.eyed.thragg.core.SpettroSetup
import to.eyed.thragg.core.ToolCallStatus
import to.eyed.thragg.core.TranscriptRow
import to.eyed.thragg.core.foldOrchestration
import to.eyed.thragg.core.rememberAgentSession
import to.eyed.thragg.core.rememberAgentSessionList
import to.eyed.thragg.core.rememberSpettroQuestions
import to.eyed.thragg.solana.agents.SpettroInstall
import to.eyed.thragg.ui.agent.spettro.ConfigChip
import to.eyed.thragg.ui.agent.spettro.ContextNotice
import to.eyed.thragg.ui.agent.spettro.ContextSheet
import to.eyed.thragg.ui.agent.spettro.LiveRunPeek
import to.eyed.thragg.ui.agent.spettro.PermissionChoice
import to.eyed.thragg.ui.agent.spettro.PermissionChoiceSheet
import to.eyed.thragg.ui.agent.spettro.PermissionSheet
import to.eyed.thragg.ui.agent.spettro.PlanProgress
import to.eyed.thragg.ui.agent.spettro.PlanSheet
import to.eyed.thragg.ui.agent.spettro.PlanUnfold
import to.eyed.thragg.ui.agent.spettro.QuestionForm
import to.eyed.thragg.ui.agent.spettro.QuestionSheet
import to.eyed.thragg.ui.agent.spettro.ReplayedSessionNotice
import to.eyed.thragg.ui.agent.spettro.SessionOpen
import to.eyed.thragg.ui.agent.spettro.SessionPicker
import to.eyed.thragg.ui.agent.spettro.SessionReplaySkeleton
import to.eyed.thragg.ui.agent.spettro.SessionScope
import to.eyed.thragg.ui.agent.spettro.SessionSearchField
import to.eyed.thragg.ui.agent.spettro.SpettroSetupScreen
import to.eyed.thragg.ui.agent.spettro.UsageReadout
import to.eyed.thragg.ui.agent.spettro.contextBlocksComposer
import to.eyed.thragg.ui.agent.spettro.sessionOpenMode
import to.eyed.thragg.ui.components.EmptyState
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.NoticeCard
import to.eyed.thragg.ui.components.RunTicker
import to.eyed.thragg.ui.components.ThraggChip
import to.eyed.thragg.ui.components.ThraggTopBar
import to.eyed.thragg.ui.components.Severity
import to.eyed.thragg.ui.components.StatusDot
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.projects.AgentThreadSeed
import to.eyed.thragg.ui.shell.projects.ProjectsSheet
import to.eyed.thragg.ui.theme.Durations
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIconButton
import to.eyed.thragg.ui.theme.animateSize
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.theme.revealItem

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

/**
 * What to call the agent on screen, from the two names that may exist.
 *
 * [connected] is `initialize`'s own `agentInfo.name` — what the program on the
 * other end of the pipe calls itself, and the only name that is certainly
 * true. [chosen] is the `agent_servers` key the user launched, which is all
 * there is before the handshake lands. "Agent" is the last resort and is
 * never wrong, only vague.
 *
 * One function because the name is now read in six places (the bar, the
 * composer, the approval sheet, the config sheet, the session picker, the
 * notification) and six copies of the same elvis chain is how they drift.
 */
internal fun agentDisplayName(connected: String?, chosen: String?): String =
    connected?.takeIf { it.isNotBlank() }
        ?: chosen?.takeIf { it.isNotBlank() }
        ?: "Agent"

/**
 * `Fix the resume crash` — the top bar's first line.
 *
 * THE BAR NAMES THE CONVERSATION, not the window it is in. One project is
 * open at a time and its name is already on the Projects control beside this
 * text, so a title reading `thragg-ide` spent the widest, boldest slot on the
 * screen telling the user the one thing they could not have got wrong. The
 * thread's own name is the thing that differs between the threads `+` makes
 * and that the picker lists, and it is the only line on this screen that says
 * *what you are working on*.
 *
 * An untitled thread falls back to the project. The fallback is not
 * `listTitle`'s `Thread 3` on purpose: an ordinal is the picker's way of
 * telling two rows apart in a list, and as a page title it is a label with no
 * content. A thread is titled from its first prompt
 * ([AgentSessions.provisionalTitle]) and by the agent after that, so the
 * fallback lasts exactly as long as the empty state does.
 */
internal fun barTitle(threadTitle: String?, projectName: String?): String =
    threadTitle?.takeIf { it.isNotBlank() }
        ?: projectName?.takeIf { it.isNotBlank() }
        ?: "No project"

/**
 * `Spettro · thragg-ide` — the top bar's second line.
 *
 * THE MODE IS GONE FROM HERE, and that is the fix rather than an omission.
 * It was being said three times at once — this line, the status strip's
 * `ModeChip` and the first value of the composer's config summary — and none
 * of the three could act on it. The mode is now a control and not a readout:
 * it is in the config sheet, one tap behind the composer's `ConfigChip`,
 * which is the only place it can be *changed*. What belongs here instead is
 * the context the title gave up when it took the thread's name — the agent
 * you are talking to, and the project it is in.
 *
 * The project is dropped when [barTitle] is showing it, because a bar that
 * prints the same word twice is the bug this is fixing in a smaller frame.
 */
internal fun barSubtitle(agentName: String, projectName: String?, threadTitle: String?): String {
    val project = projectName?.takeIf { it.isNotBlank() }
    return if (project != null && !threadTitle.isNullOrBlank()) {
        "$agentName · $project"
    } else {
        agentName
    }
}

/**
 * `Spettro is waiting on you — 2 requests`, or null when it is not.
 *
 * The bar this labels is a LINK and not a form: it names what is parked and
 * takes you to the one sheet that can answer it. Answering in place would put
 * a permission decision in a 40 dp strip above the keyboard, which is the
 * shape of every consent dialog anybody has ever tapped through by accident.
 */
internal fun attentionLabel(agentName: String, count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "$agentName is waiting on you — 1 request"
    else -> "$agentName is waiting on you — $count requests"
}

/**
 * Whether the transcript's own tail is on screen.
 *
 * [lastVisibleIndex] is null for a list that has laid nothing out yet, which
 * counts as "at the tail": a conversation that has not drawn a row cannot have
 * been scrolled away from, and answering `false` there would leave the very
 * first reply un-followed.
 *
 * The last item is the transcript's `tail` slot (the notices / stop reason), so
 * "the tail is visible" and "the newest words are visible" are the same
 * question.
 */
internal fun transcriptAtTail(lastVisibleIndex: Int?, totalItems: Int): Boolean =
    totalItems <= 0 || lastVisibleIndex == null || lastVisibleIndex >= totalItems - 1

/**
 * Whether to keep following the tail, one poll later.
 *
 * The rule that matters is the third branch. While the reader's finger is down
 * their position *is* the answer: dragging away from the tail means stop,
 * dragging back to it means resume. With no finger down, arriving at the tail
 * re-arms it — but *leaving* the tail does not switch it off, because the
 * commonest way to leave the tail with nobody touching the screen is a reply
 * growing under the fold, and treating that as "the reader scrolled up" is
 * precisely the bug this exists to avoid.
 */
internal fun followsTail(previous: Boolean, dragging: Boolean, atTail: Boolean): Boolean = when {
    dragging -> atTail
    atTail -> true
    else -> previous
}

/**
 * Whether the 36 dp status strip has anything to report.
 *
 * THE MODE IS NOT STATUS, and leaving it out of this test is the whole
 * change. A strip that stayed up for the mode alone was up from the first
 * frame of every thread and, until the first turn produced a plan or a token
 * count, carried one pill across a 400 dp band. A band that is always there
 * and almost always empty is worse than either extreme: it costs the
 * transcript 37 dp for ever and teaches the eye to stop looking at the one
 * line that is supposed to be the screen's live readout.
 *
 * So the strip reports RUN STATE and nothing else: a turn in flight, a plan
 * to work through, a context window with a number in it. Any one of the three
 * and it is a line worth reading; none of them and it is not there at all
 * (docs/VISUAL.md, "Agent — the screen at rest"). The mode is not in the left
 * slot either any more — see [AgentStatusStrip].
 */
internal fun stripReports(busy: Boolean, hasPlan: Boolean, hasUsage: Boolean): Boolean =
    busy || hasPlan || hasUsage

/** `2 files changed`, or null when there is nothing waiting to be reviewed. */
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
/**
 * The failed turn's error, unwrapped for a person.
 *
 * Spettro reports a turn failure as `Internal error: { "error": "plan agent:
 * agent call failed: unauthorized: Not Enough Credits" }` — a JSON body,
 * braces and all, inside a sentence built for a log. The card keeps the
 * provider's own words (the rule [SpettroSetup.lastError] states: verbatim,
 * because they were written to be read) but sheds the transport wrapper the
 * provider never meant a screen to print. Anything that does not match the
 * shape passes through untouched — an error we fail to prettify must still
 * be an error we show.
 */
internal fun humanTurnError(raw: String): String {
    val brace = raw.indexOf('{')
    if (brace < 0) return raw
    val inner = runCatching { JSONObject(raw.substring(brace)).optString("error") }
        .getOrDefault("")
    return inner.takeIf { it.isNotBlank() } ?: raw
}

internal fun emptyHeadline(): String = "How can I help?"

internal fun emptySubhead(projectName: String?): String =
    if (projectName.isNullOrBlank()) "No project is open" else "Working in $projectName"

/**
 * Three things worth asking a Solana program's agent first, for the empty
 * thread to offer as chips.
 *
 * An empty screen is an invitation to act, and "How can I help?" over a blank
 * composer is an invitation with no verb in it. These are the three a new
 * thread is most often opened for, in the order they are wanted: know what
 * is here, find what is wrong with it, cover it. They name the project where
 * it makes the sentence specific — "Explain what escrow does" reads as a
 * question about *this* program, "Explain what this program does" as a
 * template — and fall back to the generic noun when there is nothing to name.
 *
 * "Instructions" is Solana's word for a program's entry points, and the
 * audience is Solana developers; a chip that says "functions" would be
 * talking down. A pure function so the copy has a test.
 */
internal fun starterPrompts(projectName: String?): List<String> {
    val subject = projectName?.takeIf { it.isNotBlank() } ?: "this program"
    return listOf(
        "Explain what $subject does",
        "Look for bugs before I deploy",
        "Write tests for the instructions",
    )
}

/**
 * Whether the vertical budget has room for the secondary surfaces.
 *
 * With the IME open the keyboard eats ~340 dp of 890 and the plan unfold and
 * the live-run strip collapse to zero, so at least two transcript rows stay
 * visible (docs/SPETTRO.md, "Screen shell"). They are *hidden*, never shrunk.
 *
 * The 36 dp status strip itself is NOT one of them any more: it is one of the
 * three bands that always survive, it costs one line, and the readings on it
 * — how long this has been running, how full the window is — are exactly the
 * ones you want while typing the next message.
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
 * effect is to ask them again. The file is [AgentChoice]'s — one place for the
 * panel's small facts about this device, and the chosen agent is the other one.
 */
private const val ASK_PREFS = AgentChoice.PREFS
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
 * **THREE FIXED BANDS, and that is the redesign** (docs/VISUAL.md, "Agent —
 * the screen at rest"). This screen used to stack up to SEVEN pinned surfaces
 * between the transcript and the composer — the review bar, the setup banner,
 * the context warning, two config notices, the 32 dp plan strip and the
 * live-run peek — and only two of them collapsed with the IME. On an 890 dp
 * column with the keyboard up that left the conversation a slot. What survives
 * is a real `TopAppBar`, one 36 dp [AgentStatusStrip] and the composer;
 * everything else moved to the one place where it is still true:
 *
 *  - the review count is an action in the `⋮` overflow, badged;
 *  - the setup banner is a card in the empty state, where the user is anyway;
 *  - both config notices are [NoticeCard]s **in the transcript**, at the point
 *    in the conversation they happened, so they scroll away with the thing
 *    they are about instead of standing over it for ever;
 *  - the plan folds into the strip's [PlanProgress] and unfolds inside it;
 *  - the context ring becomes a tabular percentage on the same strip, and the
 *    warning becomes a card that appears only at 90 % — and only at 90 % *with
 *    a refusal* does it take the composer's place;
 *  - the attention bar is the fourth band and exists only while something is
 *    parked, which is the one interruption worth a band of its own.
 *
 * Three decisions here are load-bearing rather than cosmetic:
 *
 *  1. **The composer stays enabled while a turn runs**, and the button reads
 *     *Steer*. Steering is the whole reason a phone is worth having in this
 *     loop — you correct a run from a bus — and it is *not* a cancel: the text
 *     is delivered to the turn already in flight at its next step boundary,
 *     the steering prompt itself ends immediately, and the original turn keeps
 *     going. Cancel is a separate control beside it (see [AgentComposer]).
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = state.project
    val agent = AgentSessions.agent
    val thread = AgentSessions.active
    val sessionId = AgentSessions.sessionId.takeIf { it >= 0 }
    val snapshot = rememberAgentSession(sessionId)
    val session = snapshot.state
    val composerFocus = remember { FocusRequester() }
    val rows = remember(snapshot.conversation) {
        foldOrchestration(snapshot.conversation.entries)
    }
    // Born at the tail, and born *when the rows arrive*: the transcript is not
    // in the composition until there is a row (the empty state is), so its
    // first layout is the frame the history lands in, and a `scrollToItem`
    // issued from an effect has to wait for that layout — which put one frame
    // of the thread's *top* on screen before the snap to the end (seen on the
    // device). A state that starts at the tail index has nothing to wait for.
    // `rows.size` is the trailing `tail` item; the measure clamps to the
    // bottom for it just as a scroll would.
    //
    // Keyed on the session too: a thread opened from the picker must not
    // inherit the offset the previous thread was left at.
    val listState = remember(sessionId, rows.isEmpty()) {
        LazyListState(firstVisibleItemIndex = rows.size)
    }
    // Whether this session's transcript has been put at its tail once *with
    // the agent ready*. The first scroll to the tail is a *landing*, not an
    // arrival: animating it played the whole conversation past the reader
    // before it settled on the last message.
    var landed by remember(sessionId) { mutableStateOf(false) }
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
    var planOpen by remember(sessionId) { mutableStateOf(false) }
    // The notice says "raise the permission level first". Once the level *has*
    // been raised the sentence is no longer true, and it used to stay on
    // screen until the user tapped it away — which put it directly above an
    // Ultra control the same screen had just drawn solid and enabled, two
    // contradictory claims at once (seen on the device: wave 4's
    // build/conformance-shots/10-ultra-on.png). The lock going away is what
    // retires the notice, not the user acknowledging it.
    val ultraUnlocked = session.toolbar.canToggleUltra
    LaunchedEffect(ultraUnlocked) {
        if (ultraUnlocked) lockedNotice = null
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
    //
    // It runs even when an agent is already chosen, because registration is
    // also where a stale entry's environment gets refreshed: the launch env
    // grows over time (GODEBUG landed this way), and a device configured
    // before a new variable existed would otherwise keep launching Spettro
    // without it forever. Only the *choosing* below is gated on nothing
    // being picked.
    LaunchedEffect(agent) {
        if (!AgentSessions.isSupported) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { SpettroInstall.ensureRegistered(context) }
        if (agent != null) return@LaunchedEffect
        // The restore of the user's own choice runs on IO too and may have
        // landed while this was in flight. It wins: falling back to the
        // bundled agent over an agent that was deliberately picked is the
        // forgetting this pair of effects exists to stop.
        if (AgentSessions.agent != null) return@LaunchedEffect
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
    // A control tapped while the agent was still starting was queued rather
    // than dropped; this is where it goes out.
    LaunchedEffect(session.phase) {
        if (session.phase == AgentPhase.Ready) AgentSessions.flushQueuedConfig()
    }
    // The gate, immediately after the handshake and never before it: only a
    // *successful* providers/list can say NEEDED, and NEEDED is the only
    // answer that blocks (docs/SPETTRO.md, step 2). The same moment loads the
    // subscription's models into the agent — a fresh process has none until
    // asked, and the model picker showed only the active model for as long
    // as nothing on this path asked.
    LaunchedEffect(session.spettro, session.acpSessionId) {
        if (session.spettro != null) SpettroSetup.refreshOnHandshake()
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
    // Whether the transcript should keep following its own tail.
    //
    // **Latched, not derived fresh every frame**, and that is the whole
    // difficulty of the fix. "Is the last item visible?" answers *no* the
    // instant a growing reply pushes the tail item under the fold, which is
    // exactly the moment we most want to follow — so a transcript wired that
    // way stops following after the first paragraph that overflows the
    // screen. It is turned off only by the reader's own hand (a drag that
    // ends away from the tail) and back on the moment they come back to it,
    // which is the rule the reader can actually feel: scroll up to read
    // something and the stream stops chasing you; scroll back down and it
    // resumes.
    var following by remember(sessionId) { mutableStateOf(true) }
    val atTail by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            transcriptAtTail(info.visibleItemsInfo.lastOrNull()?.index, info.totalItemsCount)
        }
    }
    // The drag, and not `isScrollInProgress`: our own `revealItem` is a scroll
    // in progress too, and reading that would let the follow switch itself off.
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging, atTail) {
        following = followsTail(previous = following, dragging = dragging, atTail = atTail)
    }

    // "Re-tapping the current destination scrolls it to the newest message."
    //
    // The newest message is at the END: this list is not `reverseLayout`, so
    // index 0 is the *first* thing ever said in the thread. `rows.size` is the
    // trailing `tail` item (the notices / stop reason), which is the one row
    // that is always below the last message — and the same target the arrival
    // effect below uses, so a retap and a new row land in the same place.
    val retapSeen = remember { intArrayOf(state.retapCount) }
    LaunchedEffect(state.retapCount) {
        if (state.retapCount != retapSeen[0]) {
            retapSeen[0] = state.retapCount
            // A retap is an explicit request for the tail, so it also re-arms
            // the follow below: asking for the newest message and then not
            // being shown the next one would be the wrong half of an answer.
            following = true
            runCatching { listState.revealItem(rows.size) }
        }
    }
    // The tail is where a conversation is read from, and a streamed answer
    // that arrives off-screen is an answer nobody sees.
    //
    // Keyed on the conversation's **revision** and not on `rows.size`. Text
    // streams into the *last* row, which does not change the count, so a reply
    // longer than the viewport used to grow past the bottom of the screen with
    // the list standing still — the answer was being written somewhere below
    // the fold and nothing moved until the next row arrived. The revision is
    // the engine's own per-merge counter, so it ticks for every chunk.
    //
    // `rows.size` is the trailing `tail` item and it is the last item in the
    // list, so scrolling to it clamps to the very bottom rather than parking
    // its top at the top of the viewport: that is what keeps the view pinned
    // while a single row grows taller than the screen.
    //
    // A reopened thread SNAPS to its tail rather than animating. `session/load`
    // replays the whole history as ordinary updates, several polls' worth,
    // and the phase stays `Starting` until the agent's response says the
    // replay is over (acp.rs, `create_session`). Animating those merges
    // played the conversation past the reader in strides when all they asked
    // for was to read where the thread ended.
    //
    // `landed` is set on the first merge seen with the phase past `Starting`,
    // and that merge snaps too. Measured on the device: the replay's last
    // chunk and the `Ready` flip arrived in the *same* poll, so a rule that
    // snapped only while `Starting` snapped on a two-line partial reply and
    // then animated the whole real one in under a `Ready` phase.
    val replaying = session.phase == AgentPhase.Starting
    LaunchedEffect(snapshot.conversation.revision, following) {
        if (rows.isEmpty() || !following) return@LaunchedEffect
        if (!landed || replaying) {
            if (!replaying) landed = true
            runCatching { listState.scrollToItem(rows.size) }
        } else {
            runCatching { listState.revealItem(rows.size) }
        }
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
        val name = agentDisplayName(session.agent?.agentName, agent?.name)
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
    val agentName = agentDisplayName(session.agent?.agentName, agent?.name)
    val review = reviewLabel(session.editedFiles)

    // The moment the running turn started, computed in composition rather than
    // stamped by an effect: an effect lands a frame late, and the ticker would
    // spend that frame reading the epoch. Keyed on `isBusy` so the clock
    // restarts with the turn and on the session so a thread switch never
    // inherits the previous one's start.
    val turnStartedAt = remember(sessionId, session.isBusy) {
        if (session.isBusy) System.currentTimeMillis() else 0L
    }

    fun newThread() {
        val open = project ?: return
        AgentSessions.newThread(open.id, open.rootName, open.rootPath)
    }

    // The one thing the destination is doing that is not a band: `/compact` is
    // a prompt on the wire like any other command, and the agent owns what it
    // means.
    fun compact() = AgentSessions.prompt("/compact", emptyList(), emptyList()) {}

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ThraggTopBar(
            title = barTitle(thread?.title, project?.rootName),
            subtitle = barSubtitle(agentName, project?.rootName, thread?.title),
            actions = {
                ThraggIconButton(
                    icon = R.drawable.ic_ui_chevron_down,
                    description = "Projects",
                    onClick = { sheet = AgentSheet.Projects },
                    tint = mutedIcon,
                )
                ThraggIconButton(
                    icon = R.drawable.ic_ui_plus,
                    description = "New thread",
                    onClick = { newThread() },
                    tint = mutedIcon,
                )
                OverflowAction(badge = review, onClick = { sheet = AgentSheet.Overflow })
            },
        )
        HairlineDivider()
        AgentStatusStrip(
            state = session,
            startedAt = turnStartedAt,
            planOpen = planOpen && bands,
            onTogglePlan = { planOpen = !planOpen },
            onOpenContext = { sheet = AgentSheet.Context },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                // Defensive rather than reachable: the agent runs inside the
                // Linux guest and every build that ships has one. Kept as an
                // honest dead end rather than a broken screen if the seam ever
                // answers no — but it no longer blames an "edition", because
                // there is only one.
                !AgentSessions.isSupported -> AgentEmpty(
                    headline = "No agent available",
                    body = "The agent runs inside the Linux guest, which is not available.",
                    action = null,
                    onAction = {},
                )

                project == null -> AgentEmpty(
                    headline = "No project is open",
                    body = "Open or create one, and the thread opens with it.",
                    action = "Projects & tools",
                    onAction = { sheet = AgentSheet.Projects },
                )

                // The setup takeover, and only for a NEEDED gate: UNKNOWN
                // never blocks, because not knowing is not "no".
                SpettroSetup.isBlocking -> SpettroSetupScreen(
                    state = state,
                    onSkip = { SpettroSetup.skip() },
                    onDone = { scope.launch { SpettroSetup.refreshOnHandshake() } },
                    quotedError = session.error,
                )

                agent == null -> AgentEmpty(
                    headline = "No agent yet",
                    body = "Spettro is a 15 MB download and needs no Node, no Python and " +
                        "no compiler.",
                    action = "Set up Spettro",
                    onAction = { state.push(Route.Setup) },
                )

                AgentSessions.startError != null -> AgentEmpty(
                    headline = "The agent did not start",
                    body = AgentSessions.startError.orEmpty(),
                    action = "Try again",
                    onAction = { newThread() },
                )

                AgentSessions.isStarting -> AgentEmpty(
                    headline = "Starting " + agent.name + "…",
                    body = "The first launch unpacks the runtime; later ones are instant.",
                    action = null,
                    onAction = {},
                )

                sessionId == null -> AgentEmpty(
                    headline = "No thread open.",
                    body = "Start one to talk to $agentName.",
                    action = "New thread",
                    onAction = { newThread() },
                )

                // A loaded session streams its whole transcript back before
                // the response returns; a skeleton says so rather than showing
                // an empty conversation that fills in a second later.
                thread?.expectsReplay == true && rows.isEmpty() -> SessionReplaySkeleton()

                rows.isEmpty() -> AgentEmpty(
                    headline = emptyHeadline(),
                    body = emptySubhead(project.rootName),
                    action = null,
                    onAction = {},
                    // The chips go through the seam the rest of the app uses
                    // to put words in the composer, and the composer drains
                    // it, focuses the field and raises the keyboard — so a
                    // tap on a chip lands the user at the end of a sentence
                    // they can send or finish, never in a sent message.
                    suggestions = starterPrompts(project.rootName),
                    onSuggest = { prompt -> AgentSeams.offer(prompt) },
                    // The setup banner used to be a permanent 40 dp band above
                    // the composer. It says "no model connected", which is
                    // only ever true of a conversation that has not happened
                    // yet — so this is where it belongs, and it costs nothing
                    // on every other screen state.
                    notice = {
                        AgentNotices(
                            refusal = session.notice ?: AgentSessions.lastRefusal,
                            onDismissRefusal = {
                                AgentSessions.clearRefusal()
                                AgentSessions.clearNotice()
                            },
                            locked = lockedNotice,
                            onDismissLocked = { lockedNotice = null },
                            setup = SpettroSetup.needsBanner,
                            onOpenSetup = { SpettroSetup.unskip() },
                        )
                    },
                )

                else -> Column(modifier = Modifier.fillMaxSize()) {
                    if (thread?.isReopened == true) ReplayedSessionNotice()
                    AgentTranscript(
                        shell = state,
                        rows = rows,
                        listState = listState,
                        expanded = expanded,
                        // Rows keep still until the list has landed on its
                        // tail: a replayed reply that sprang to its height
                        // *after* the snap left the list at the top of it
                        // (the second thing the device showed).
                        animateRows = landed,
                        onOpenPath = { path -> state.push(Route.Diff(path)) },
                        onOpenPermission = { call -> sheet = AgentSheet.Approval(call.key) },
                        onRestoreCheckpoint = { index ->
                            AgentSessions.restoreCheckpoint(index)
                        },
                        tail = {
                            TranscriptTail(
                                state = session,
                                refusal = session.notice ?: AgentSessions.lastRefusal,
                                onDismissRefusal = {
                                    AgentSessions.clearRefusal()
                                    AgentSessions.clearNotice()
                                },
                                locked = lockedNotice,
                                onDismissLocked = { lockedNotice = null },
                                setup = SpettroSetup.needsBanner,
                                onOpenSetup = { SpettroSetup.unskip() },
                                onRetry = { AgentSessions.retryLastPrompt() },
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // Everything below here is pinned, and there is very little of it.
        if (bands) {
            LiveRunPeek(
                runs = liveRuns,
                expanded = peekOpen,
                onToggle = { peekOpen = !peekOpen },
            )
        }
        AttentionBar(
            label = attentionLabel(agentName, waitingCount),
            onAnswer = {
                // Re-raise even a request the user put away: they asked for it
                // by name this time.
                val form = questions.firstOrNull()
                val approval = approvals.firstOrNull()
                when {
                    form != null -> {
                        dismissed.remove(form.id)
                        sheet = AgentSheet.Form(form.id)
                    }

                    approval != null -> {
                        dismissed.remove(approval.key)
                        sheet = AgentSheet.Approval(approval.key)
                    }
                }
            },
        )
        // Tier three. Above the composer at 90 %, and INSTEAD of it once the
        // host has actually refused a turn: there is nothing useful to type,
        // and leaving the field there means the user writes a paragraph before
        // finding that out.
        val blocked = contextBlocksComposer(
            fraction = session.usage?.takeIf { it.size > 0L }?.fraction ?: 0f,
            refused = session.stopReason == "refusal",
        )
        ContextNotice(
            usage = session.usage,
            onCompact = { compact() },
            onNewThread = { newThread() },
            modifier = Modifier.padding(
                start = MD.space4,
                end = MD.space4,
                top = MD.space2,
                bottom = MD.space2,
            ),
        )
        if (!blocked) {
            AgentComposer(
                shell = state,
                state = session,
                thread = thread,
                agentName = agentName,
                enabled = session.canPrompt && sessionId != null,
                focus = composerFocus,
                onOpenMentions = { sheet = AgentSheet.Mentions },
                onStop = { AgentSessions.cancelTurn() },
                onSteered = {},
                // The five selectors, as one chip inside the control row.
                // This slot is the ONLY reason the 36dp chip band could be
                // deleted: without something drawn here, the thinking slider
                // and the model list are reachable only from the overflow, and
                // the two controls the agent screen exists to expose would be
                // two taps behind a glyph. `ConfigChip` returns Unit when
                // there is nothing on the wire yet, so a session that has not
                // reported its selectors leaves the slot empty rather than
                // drawing a pill with nothing in it.
                config = {
                    ConfigChip(
                        toolbar = session.toolbar,
                        onOpen = { sheet = AgentSheet.Config },
                    )
                },
            )
        }
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
        onLocked = { lockedNotice = to.eyed.thragg.core.ULTRA_LOCK_REASON },
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
    onLocked: () -> Unit,
    onMention: (AgentMention) -> Unit,
    onPermissionChosen: (String) -> Unit,
) {
    val project = state.project
    val workspace = rememberCodeWorkspace()
    // Every sheet below that names the agent names *this*, and never the one
    // we bundle: `AgentSessions.agent` is the entry the user launched and
    // `session.agent` is what the program calls itself once it has answered
    // `initialize`.
    val agentName = agentDisplayName(session.agent?.agentName, AgentSessions.agent?.name)

    when (sheet) {
        null -> Unit

        AgentSheet.Projects -> ProjectsSheet(state = state, onDismiss = { onDismiss(null) })

        AgentSheet.Config -> AgentConfigSheet(
            shell = state,
            state = session,
            onPick = { option, valueJson ->
                AgentSessions.setConfigOption(option.id, valueJson)
            },
            // A NoticeCard in the transcript rather than a toast: the sentence
            // is "raise the permission level first", which is an instruction
            // the user has to be able to re-read while doing it.
            onLocked = onLocked,
            onDismiss = { onDismiss(null) },
        )

        AgentSheet.Overflow -> AgentOverflowSheet(
            shell = state,
            items = listOf(
                // First, and it is new here: the selectors used to be a 36 dp
                // band of horizontally-scrolling chips. They are the
                // composer's `ConfigChip` now, and this is the second
                // way in, for the same reason every sheet has one.
                OverflowItem("Configure", session.toolbar.mode?.currentLabel) {
                    onSheet(AgentSheet.Config)
                },
                OverflowItem(
                    "Sessions",
                    stringResource(R.string.agent_overflow_sessions, agentName),
                ) {
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
                // The old sticky review bar, now an action with the count on
                // it — and the `⋮` itself carries a dot while it says
                // anything, so the trust surface is still visible without
                // opening this (docs/VISUAL.md, "Agent — the screen at rest").
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
                // The account surface where the user actually is: the same
                // Route.SpettroSettings page the Settings row reaches, pushed
                // onto this destination's stack the way "Review changes"
                // pushes its route, so back lands on the conversation. The
                // detail is the email when there is one — the one fact worth
                // a glance without opening the page.
                OverflowItem("Account & models", SpettroSetup.account?.email) {
                    onDismiss(null)
                    state.push(Route.SpettroSettings)
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
            agentName = agentName,
            onDismiss = { onDismiss(null) },
        )

        AgentSheet.Context -> ContextSheet(
            state = state,
            usage = session.usage,
            turnUsage = session.turnUsage,
            agentName = agentName,
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
                        agentName = agentName,
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
            agentName = agentDisplayName(session.agent?.agentName, AgentSessions.agent?.name),
            onScopeChange = onScope,
            openSessionIds = open,
            canReplay = canReplay,
            loading = list.loading,
            error = list.error,
        )
    }
}

// ---------------------------------------------------------------------------
// The status strip
// ---------------------------------------------------------------------------

/**
 * The 36 dp line under the app bar: what is running, what is planned, and how
 * much room is left.
 *
 * FOUR SURFACES MERGED INTO ONE, at a quarter of the height they cost apart —
 * the app bar's mode text, the app bar's 12 dp context ring, the 32 dp plan
 * strip, and the elapsed/token readout that used to live at the SCROLLING
 * transcript tail, where it disappeared the moment you read anything above it.
 * Elapsed time that scrolls away is not status.
 *
 * Left to right, and the order is the argument: the thing that is happening
 * now, the thing it is working through, then a gap, then the thing that limits
 * it. Nothing on this line is a control that changes the conversation — two of
 * the three open something, and the third is a label.
 *
 * THE MODE PILL IS GONE FROM THE LEFT SLOT. It used to fill it whenever the
 * strip was up and no turn was running — the plan-only and usage-only cases —
 * so a band raised to report a plan spent its widest slot on a word that is
 * not status and cannot be tapped. The mode is a control now, in the config
 * sheet behind the composer's `ConfigChip`; when nothing is running, the left
 * slot simply belongs to the plan. The strip is one thing again: what the run
 * is doing.
 *
 * The strip is drawn at all only when [stripReports] says there is something
 * on it; neither the mode nor anything else holds it open, so a fresh thread
 * has no band under its app bar at all.
 *
 * [Modifier.animateSize] because the plan unfolds INTO this strip. A strip that
 * jumped from 36 dp to 250 dp would take the transcript's scroll position with
 * it; springing there keeps the reader's place.
 */
@Composable
private fun AgentStatusStrip(
    state: AgentSessionState,
    startedAt: Long,
    planOpen: Boolean,
    onTogglePlan: () -> Unit,
    onOpenContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalThraggColors.current
    val hasUsage = (state.usage?.size ?: 0L) > 0L
    val busy = state.isBusy && startedAt > 0L
    // Nothing to report is a strip that is not there, rather than an empty
    // rule across the screen — and the mode alone is not something to report,
    // because it is a control on the composer a thumb's width away rather
    // than a reading.
    if (!stripReports(busy, state.plan.isNotEmpty(), hasUsage)) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .animateSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space3),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MD.stripHeight)
                .padding(horizontal = MD.space4),
        ) {
            if (busy) {
                RunTicker(
                    startedAt = startedAt,
                    tokens = state.turnUsage?.totalTokens?.takeIf { it > 0L },
                    tint = colors.accentMark,
                )
            }
            PlanProgress(plan = state.plan, expanded = planOpen, onToggle = onTogglePlan)
            Spacer(Modifier.weight(1f))
            UsageReadout(usage = state.usage, onClick = onOpenContext)
        }
        if (planOpen) {
            HairlineDivider()
            PlanUnfold(plan = state.plan)
        }
        HairlineDivider()
    }
}

/**
 * `⋮`, with a dot on it while something is unreviewed.
 *
 * The dot is decoration and the COUNT is in the button's description, so
 * TalkBack says "More, 2 files changed" in one node instead of announcing a
 * button and then an unlabelled circle. That is the whole trick that lets the
 * review bar stop being a band: the number is still on screen, it just costs
 * six dp instead of forty.
 */
@Composable
private fun OverflowAction(badge: String?, onClick: () -> Unit) {
    Box {
        ThraggIconButton(
            icon = R.drawable.ic_ui_more_vertical,
            description = if (badge == null) "More" else "More — $badge",
            onClick = onClick,
            tint = mutedIcon,
        )
        if (badge != null) {
            StatusDot(
                color = MaterialTheme.colorScheme.primary,
                size = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp),
            )
        }
    }
}

/**
 * The fourth band, and the only one that earns being one: something is parked
 * and the turn is not moving until you deal with it.
 *
 * A LINK AND NOT A FORM. The sheet it opens has the options, their
 * descriptions and the queue position; a 40 dp strip above the keyboard has
 * room for none of that, and a consent control at that size is the shape of
 * every permission anybody has ever granted by accident.
 *
 * It arrives at [Durations.BAND_IN] and leaves at [Durations.BAND_OUT] —
 * slower out, so answering the last request settles rather than blinking the
 * bar away under the finger that answered it.
 */
@Composable
private fun AttentionBar(label: String?, onAnswer: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // Held so the exit transition has a sentence to draw: `label` is null the
    // instant the last request is answered, and an AnimatedVisibility whose
    // content went blank animates a blank bar out.
    //
    // A plain array and not `mutableStateOf`, for the same reason `retapSeen`
    // above is one: nothing needs to *observe* this — it is read in the very
    // composition that writes it — and a snapshot write during composition
    // would invalidate the frame that just made it.
    val held = remember { arrayOfNulls<String>(1) }
    if (label != null) held[0] = label
    val shown = held[0].orEmpty()
    AnimatedVisibility(
        visible = label != null,
        enter = fadeIn(tween(Durations.BAND_IN)) + expandVertically(tween(Durations.BAND_IN)),
        exit = fadeOut(tween(Durations.BAND_OUT)) + shrinkVertically(tween(Durations.BAND_OUT)),
        modifier = modifier,
    ) {
        Column {
            HairlineDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainer)
                    .clickable(onClickLabel = "Answer", onClick = onAnswer)
                    .heightIn(min = AttentionBarHeight)
                    .padding(start = MD.space4, end = MD.space2),
            ) {
                StatusDot(color = scheme.primary, pulsing = true)
                Text(
                    text = shown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAnswer) { Text(text = "Answer") }
            }
        }
    }
}

/** docs/VISUAL.md's wireframe gives the attention bar exactly this much. */
private val AttentionBarHeight = 40.dp

// ---------------------------------------------------------------------------
// The transcript's tail, and the notices that live in it
// ---------------------------------------------------------------------------

/**
 * The end of the transcript: what the turn ended with, and any notice the
 * session raised while it ran.
 *
 * THE RUN TICKER IS NOT HERE ANY MORE. It was the whole reason this slot
 * existed, and it was in the wrong place: a readout at the end of a scrolling
 * list is only visible while the reader happens to be at the end of the list,
 * which is exactly not the case while they are reading what the agent wrote.
 * It is on [AgentStatusStrip] now, pinned, and this slot keeps the two things
 * that genuinely belong to a *position in the conversation* — how the turn
 * ended, and what went wrong.
 *
 * The notices arrive here for the same reason. A refused config change and a
 * locked Ultra used to be permanent bands above the composer; they are facts
 * about a moment, so they are drawn at that moment, dismissible, and they
 * scroll away with it.
 */
@Composable
private fun TranscriptTail(
    state: AgentSessionState,
    refusal: String?,
    onDismissRefusal: () -> Unit,
    locked: String?,
    onDismissLocked: () -> Unit,
    setup: Boolean,
    onOpenSetup: () -> Unit,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // NO horizontal padding. This composable is the transcript's `tail`
            // slot, so it is drawn INSIDE the LazyColumn's own 16dp
            // `contentPadding` (AgentTranscript.kt:476) — the gutter belongs to
            // the list, once, and a row that adds its own lands at 32dp while
            // every message above it sits at 16dp.
            .padding(vertical = MD.space1),
        verticalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        AgentNotices(
            refusal = refusal,
            onDismissRefusal = onDismissRefusal,
            locked = locked,
            onDismissLocked = onDismissLocked,
            setup = setup,
            onOpenSetup = onOpenSetup,
        )
        stopReasonNotice(state.stopReason)?.let { notice ->
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (notice.isError) {
                    LocalThraggColors.current.dangerInk
                } else {
                    scheme.onSurfaceVariant
                },
            )
        }
        state.error?.let { error ->
            NoticeCard(
                severity = Severity.Error,
                title = "The turn failed",
                body = humanTurnError(error),
                actions = {
                    if (state.canRetry) {
                        TextButton(onClick = onRetry) { Text(text = "Try again") }
                    }
                },
            )
        }
    }
}

/**
 * The three notices that used to be pinned bands, as cards.
 *
 * One composable because they appear in two places — at the transcript's tail
 * and under the empty state — and the empty state is the case that matters
 * for the setup card: "no model connected" is only ever the explanation for a
 * conversation that has not happened yet.
 *
 * `Warn` rather than `Error` for all three: none of them is a failure. A
 * refused config option, a locked Ultra and an unfinished provider setup are
 * all states with a way out, and spending the red on them leaves nothing for
 * the turn that actually broke.
 */
@Composable
private fun AgentNotices(
    refusal: String?,
    onDismissRefusal: () -> Unit,
    locked: String?,
    onDismissLocked: () -> Unit,
    setup: Boolean,
    onOpenSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (refusal == null && locked == null && !setup) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        refusal?.let { text ->
            NoticeCard(
                severity = Severity.Warn,
                title = null,
                body = text,
                onDismiss = onDismissRefusal,
            )
        }
        locked?.let { text ->
            NoticeCard(
                severity = Severity.Warn,
                title = null,
                body = text,
                onDismiss = onDismissLocked,
            )
        }
        if (setup) {
            // The whole reason **Skip for now** is allowed to exist: it must
            // not produce a raw provider error at the first prompt.
            NoticeCard(
                severity = Severity.Warn,
                title = "No model connected",
                body = "Spettro cannot answer until a provider is set up.",
                actions = {
                    TextButton(onClick = onOpenSetup) { Text(text = "Set up") }
                },
            )
        }
    }
}

/**
 * A headline, a sentence, at most one button — and never a blank rectangle.
 *
 * [EmptyState] rather than a local `Column` of `Text`s: the quiet 40 dp mark,
 * the 32 dp gutter and the titleMedium/bodyMedium pair are the same on every
 * screen in the app that has nothing to show, and this one is reached by seven
 * different routes.
 *
 * The copy register is the existing one and it is right — "No thread open.
 * Start one to talk to Spettro." names the way out, because unlike a chat app
 * the composer here is not always available.
 *
 * [suggestions] are the empty thread's way in when there is no button: a row
 * of chips that each put a first message in the composer ([starterPrompts]).
 * They take the action slot, under the body, where the button would be.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentEmpty(
    headline: String,
    body: String,
    onAction: () -> Unit,
    action: String?,
    notice: @Composable () -> Unit = {},
    suggestions: List<String> = emptyList(),
    onSuggest: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = MD.space4),
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            headline = headline,
            body = body,
            action = when {
                action != null -> {
                    { TextButton(onClick = onAction) { Text(text = action) } }
                }
                suggestions.isNotEmpty() -> {
                    {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                MD.space2,
                                Alignment.CenterHorizontally,
                            ),
                            verticalArrangement = Arrangement.spacedBy(MD.space2),
                        ) {
                            for (prompt in suggestions) {
                                ThraggChip(label = prompt, onClick = { onSuggest(prompt) })
                            }
                        }
                    }
                }
                else -> null
            },
        )
        notice()
    }
}
