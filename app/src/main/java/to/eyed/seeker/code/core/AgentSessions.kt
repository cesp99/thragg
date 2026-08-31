package to.eyed.seeker.code.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.shell.ShellState

/**
 * One conversation with the agent — Zed's thread. The engine session behind
 * it holds the transcript; this is the identity the thread list renders.
 */
class AgentThread internal constructor(
    val sessionId: Long,
    val projectId: Long,
    val projectName: String,
    /** The project's absolute root, for the rules files the first prompt carries. */
    val rootPath: String,
    /** Creation order, newest last — the list shows newest first. */
    val ordinal: Int,
    /**
     * Whether this thread was reopened from the agent's own history rather
     * than started fresh. Worth saying in the list, because an agent that
     * could only `session/resume` gave the conversation back *without* its
     * transcript: the thread is genuinely a continuation of something the
     * panel cannot show.
     */
    val isReopened: Boolean = false,
    /**
     * Whether the agent is expected to *replay* this conversation rather than
     * merely continue it — `session/load` against an agent that advertises it,
     * where Spettro streams every `user_message_chunk`, `agent_thought_chunk`
     * and `agent_message_chunk` back **before** the response returns.
     *
     * The panel needs it as a hint and not as a fact: it decides whether to
     * show a replay skeleton and whether to clear the view first. A loaded
     * conversation legitimately has no tool-call cards, no plan and no usage —
     * the agent's on-disk store keeps the flat transcript and nothing else —
     * so a screen that waited for those would wait for ever.
     */
    val expectsReplay: Boolean = false,
) {
    /**
     * The agent's own name for the conversation, once it sends one
     * (`SessionInfoUpdate`); stamped by the panel while the thread is
     * showing, so the history list can name it after it stops being active.
     */
    var title by mutableStateOf<String?>(null)
        internal set

    /**
     * The agent's own id for this session, once it has one.
     *
     * Kept so the history list can tell a conversation that is already open
     * from one that is not — reopening the former would steal the live
     * thread's updates.
     */
    var acpSessionId by mutableStateOf<String?>(null)
        internal set

    /** What the history list prints. */
    val listTitle: String get() = title ?: "Thread $ordinal"

    /**
     * The unsent message, and the paths @-mentioned in it.
     *
     * Held on the thread rather than inside the composer because the
     * composer is a *branch* of the panel's `when`: opening the threads
     * view, or a `+ New` that flips the panel to "starting", disposes it and
     * would take an unsent prompt with it. Per thread, so each conversation
     * keeps its own draft — which is what Zed does too.
     */
    var draft by mutableStateOf("")

    /** What the draft carries besides its text — every kind of [AgentMention]. */
    val draftMentions = mutableStateListOf<AgentMention>()

    /**
     * How many prompts this thread has sent. The first one carries the
     * project's rules files, as Zed's first message carries its project
     * context.
     */
    var promptCount by mutableStateOf(0)
        internal set

    /**
     * Pictures attached to the unsent message, held here for the same reason
     * the draft is: the composer is a branch of the panel's `when`, and
     * opening the threads view would otherwise throw away an attachment that
     * cost the user a trip through the photo picker.
     */
    val draftImages = mutableStateListOf<PromptAttachment>()
}

/**
 * The agent threads the panel is showing, and which agent they are with.
 *
 * Outside the composition on purpose, the same way
 * [to.eyed.seeker.code.terminal.UserlandInstaller] and `GitClone` are:
 * closing the panel — or the dock reshuffling on a fold — must not end a
 * conversation or kill the agent process behind it. Reopening the panel finds
 * the conversation where it was left.
 *
 * **Threads, plural, within one project** — Zed's shape: the panel shows one
 * thread, `+` starts another, and the history view lists them per project.
 * They share the one agent process the engine holds (a session is a protocol
 * object, not a process). Threads in *different* projects cannot share it —
 * the guest binds the project directory at spawn — so opening a thread in a
 * new project closes every thread of the old one, exactly as the engine
 * replaces the agent underneath.
 */
object AgentSessions {

    private const val TAG = "seeker-agent"

    /** Zed's cut for a title taken from a message (thread_view.rs:1730). */
    private const val PROVISIONAL_TITLE_MAX = 200

    /**
     * The agent the user picked; null until they do.
     *
     * The definition itself rather than its id, because a configured agent
     * exists only in settings.json — there is no table to look it up in, and
     * a stale id would silently resolve to nothing after an edit.
     */
    var agent by mutableStateOf<AgentDefinition?>(null)
        private set

    /** Every live thread, in creation order. */
    val threads = androidx.compose.runtime.mutableStateListOf<AgentThread>()

    /** The thread the conversation view is showing. */
    var active by mutableStateOf<AgentThread?>(null)
        private set

    private var nextOrdinal = 1

    /** The active thread's session, or -1 — what the panel polls. */
    val sessionId: Long get() = active?.sessionId ?: -1L

    /** Which project [active] belongs to, or -1. */
    val projectId: Long get() = active?.projectId ?: -1L

    /** A session is being asked for; the engine call blocks on a spawn. */
    var isStarting by mutableStateOf(false)
        private set

    /**
     * Why no session could be asked for at all — a caller-side refusal, which
     * is the only thing [CoreBridge.acpStartSession] answers with -1 for.
     * Everything the *agent* got wrong arrives as session state instead.
     */
    var startError by mutableStateOf<String?>(null)
        private set

    /**
     * Where a failure the panel cannot show goes — set by the workspace to
     * its notification stack.
     *
     * A hook rather than a direct call because this file is `core`: it knows
     * about the engine and nothing about the UI, and an import the other way
     * would be the first one. The panel keeps showing what it can show; this
     * is for the failures that happen while the panel is closed, which is
     * most of them on a phone.
     */
    var onProblem: ((String) -> Unit)? = null

    /** False in builds with no userland: there is no agent panel there at all. */
    val isSupported: Boolean get() = Userland.backend.isSupported

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Bumped by every start and every close, so a start that finished after it
     * was abandoned cannot publish its session over whatever is current.
     *
     * The same guard `AptInstaller` carries, and needed for the same reason
     * with more teeth: `acpStartSession` **blocks** — it spawns proot and a
     * Node process — and has no suspension point after it, so cancelling the
     * job cannot stop the lines that follow. Without this, pressing *New* or
     * changing agent while a start was in flight left two live engine
     * sessions, one of them unreachable and never closed, and pointed the
     * panel at whichever returned last.
     */
    @Volatile
    private var generation = 0

    /** Remember the choice without starting anything. */
    fun choose(chosen: AgentDefinition) {
        if (agent == chosen) return
        close()
        agent = chosen
    }

    /**
     * Start a thread with [chosen], keeping every thread that is already
     * open.
     *
     * The bar's old "Change agent…" called [choose], which closes the lot —
     * one tap, no confirmation, no undo. Choosing a different agent is a
     * reason to start a conversation, not to end the ones you have; Zed's New
     * Thread menu works the same way (agent_panel.rs:5817-5985).
     *
     * Switching agents *does* replace the running process — the engine keeps
     * one at a time — so the other agent's threads lose their sessions
     * whatever we do. What this avoids is throwing away the threads of the
     * agent you are staying with.
     */
    fun startWith(chosen: AgentDefinition, project: Long, projectName: String, rootPath: String) {
        if (agent != chosen) {
            // Another agent's threads cannot survive the process being
            // replaced, so close those and only those.
            val doomed = threads.toList()
            threads.clear()
            active = null
            for (thread in doomed) {
                scope.launch { runCatching { CoreBridge.acpCloseSession(thread.sessionId) } }
            }
            agent = chosen
        }
        newThread(project, projectName, rootPath)
    }

    /**
     * Make sure [project] has a thread showing: select its most recent, or
     * start its first. Returns at once; watch [active] and then
     * [rememberAgentSession].
     */
    fun open(project: Long, projectName: String, rootPath: String) {
        if (active?.projectId == project) return
        val existing = threads.lastOrNull { it.projectId == project }
        if (existing != null) {
            active = existing
            return
        }
        newThread(project, projectName, rootPath)
    }

    /**
     * Start another thread for [project] — Zed's `+`. The new thread becomes
     * the one showing; the others stay live in [threads].
     */
    fun newThread(project: Long, projectName: String, rootPath: String) {
        startThread(project, projectName, rootPath, null)
    }

    /**
     * Reopen one of the agent's *own* past conversations as a thread —
     * `session/load` where the agent can replay the history, `session/resume`
     * where it can only continue.
     *
     * Only offered when `agent.capabilities.hasHistory` says both halves are
     * there; the panel gates the view on that.
     */
    fun resumeThread(project: Long, projectName: String, rootPath: String, pastSessionId: String) {
        startThread(project, projectName, rootPath, pastSessionId, replay = false)
    }

    /**
     * Open one of the agent's own past conversations *with* its transcript —
     * `session/load`, the picker's ordinary tap on a session the phone holds
     * no rows for.
     *
     * The engine chooses the method (`create_session` prefers `session/load`
     * when the agent advertises `loadSession`, and falls back to
     * `session/resume`), so the difference between this and [resumeSession] is
     * what the *panel* should expect: a replay streams the whole conversation
     * back as updates before the call returns, which is worth a skeleton and
     * worth clearing the view for.
     */
    fun loadSession(project: Long, projectName: String, rootPath: String, pastSessionId: String) {
        startThread(project, projectName, rootPath, pastSessionId, replay = true)
    }

    /**
     * Re-attach to a conversation that is already on screen — `session/resume`,
     * no replay. Used when the transcript is already held and re-streaming it
     * would duplicate every row.
     */
    fun resumeSession(project: Long, projectName: String, rootPath: String, pastSessionId: String) {
        startThread(project, projectName, rootPath, pastSessionId, replay = false)
    }

    private fun startThread(
        project: Long,
        projectName: String,
        rootPath: String,
        resume: String?,
        replay: Boolean = false,
    ) {
        val agent = agent ?: return
        // A start already in flight is **superseded, never ignored**. It used
        // to `return` here without bumping the generation, so a project
        // switch during the (blocking) spawn published the old project's
        // thread as active: the panel showed project B while every prompt and
        // @-mention resolved against project A's tree, silently. Bumping the
        // generation below is what makes the in-flight start close its own
        // session instead of publishing it.
        job?.cancel()
        // A thread in another project cannot share the agent process — the
        // guest binds the project directory at spawn — so the engine replaces
        // the agent underneath and those sessions die. Close them here too,
        // or the list would show threads whose transcripts are gone.
        if (threads.any { it.projectId != project }) {
            closeThreadsExcept(project)
        }

        isStarting = true
        startError = null
        // The launch spec is built here and only here, so the one substitution
        // the bundled entry depends on cannot be forgotten: `--cwd
        // $PROJECT_ROOT` in settings.json becomes this thread's absolute root
        // (AgentDefinition.forProjectRoot). An unresolved token would reach
        // Spettro as a relative path and `session/new` would answer -32602.
        val spec = agent.forProjectRoot(rootPath).toSpecJson()
        val mine = ++generation
        job = scope.launch {
            // Blocking: it spawns proot and the agent behind it. Anything that
            // throws out of the bridge — a JNI failure — must leave the panel
            // saying so rather than stuck on "starting" for ever.
            val id = runCatching {
                if (resume == null) {
                    CoreBridge.acpStartSession(project, spec)
                } else {
                    CoreBridge.acpResumeSession(project, spec, resume)
                }
            }.getOrElse { error ->
                Log.e(TAG, "could not start an agent session", error)
                -1L
            }
            if (generation != mine) {
                // Abandoned while we were spawning: this session belongs to
                // nobody, so close it rather than leak the process behind it.
                if (id >= 0) runCatching { CoreBridge.acpCloseSession(id) }
                return@launch
            }
            if (id < 0) {
                startError = "The agent could not be launched — its command may be misconfigured."
                onProblem?.invoke("${agent.name}: ${startError.orEmpty()}")
                isStarting = false
                return@launch
            }
            val thread = AgentThread(
                sessionId = id,
                projectId = project,
                projectName = projectName,
                rootPath = rootPath,
                ordinal = nextOrdinal++,
                isReopened = resume != null,
                expectsReplay = replay,
            )
            threads.add(thread)
            active = thread
            isStarting = false
            // Both `session/load` and `session/resume` hand the conversation
            // back at the agent's *manifest default* mode — usually `plan` —
            // whatever it was when it was saved. Nothing on the wire carries
            // the old one, so the remembered value is re-applied here, once,
            // right after the session lands (docs/SPETTRO.md, W-15).
            if (resume != null) reapplyRememberedMode(id)
        }
    }

    /**
     * The mode the user last chose, re-sent after a load or a resume.
     *
     * Session-scoped state on the agent's side, and the only one of the five
     * selectors that is: model, permission, thinking and Ultra live in
     * `~/.spettro/config.json` and survive on their own. Kept here rather than
     * in settings because it is a *conversation* preference — the panel's, for
     * as long as the app is running.
     */
    private var rememberedMode: String? = null

    private suspend fun reapplyRememberedMode(session: Long) {
        val mode = rememberedMode ?: return
        // Best effort and deliberately quiet: a failure here is a chip showing
        // `plan` when the user wanted `code`, which the chip itself will show
        // correctly at the next poll. Raising a refusal for it would put an
        // error on screen for something nobody asked for in this moment.
        runCatching {
            CoreBridge.acpSetConfigOption(session, MODE_CONFIG_ID, JSONObject.quote(mode))
        }
    }

    /**
     * Spettro's mode lives in `configOptions`, not in ACP's `session/set_mode`
     * — it advertises no modes at all, so `acpSetMode` would answer false
     * (docs/SPETTRO.md, W-18).
     */
    private const val MODE_CONFIG_ID = "mode"

    /** Whether [project] has any thread at all — the panel's empty state. */
    fun hasThreadFor(project: Long): Boolean = threads.any { it.projectId == project }

    /**
     * The thread already showing the agent's session [acpSessionId], if one
     * is.
     *
     * Reopening a conversation that is already open used to index the agent's
     * session id onto a *new* thread, which silently stole the updates from
     * the old one — that thread was still on screen, still listed, and would
     * never receive anything again.
     */
    fun threadFor(acpSessionId: String): AgentThread? =
        threads.firstOrNull { it.acpSessionId == acpSessionId }

    /** Show [thread] — the history view's tap. */
    fun select(thread: AgentThread) {
        if (thread in threads) active = thread
    }

    /**
     * End one thread. The engine closes its session and, with the last one,
     * the agent process — through the engine, which takes proot down the
     * careful way.
     */
    fun closeThread(thread: AgentThread) {
        threads.remove(thread)
        if (active == thread) {
            active = threads.lastOrNull { it.projectId == thread.projectId }
        }
        scope.launch { runCatching { CoreBridge.acpCloseSession(thread.sessionId) } }
    }

    private fun closeThreadsExcept(project: Long) {
        val doomed = threads.filter { it.projectId != project }
        threads.removeAll(doomed)
        if (active?.projectId != project) active = null
        for (thread in doomed) {
            scope.launch { runCatching { CoreBridge.acpCloseSession(thread.sessionId) } }
        }
    }

    /** End every thread and the agent process behind them. */
    fun close() {
        val doomed = threads.toList()
        threads.clear()
        active = null
        startError = null
        isStarting = false
        generation++
        job?.cancel()
        job = null
        for (thread in doomed) {
            scope.launch { runCatching { CoreBridge.acpCloseSession(thread.sessionId) } }
        }
    }

    // --- what the panel does, on a scope that outlives it --------------------
    //
    // The panel is a dock: it comes and goes with a chord, a fold, a rotation.
    // A send whose coroutine died with the composition would clear the box and
    // never reach the agent, so these run here instead — and they report a
    // refusal rather than discarding it, because the bridge answers `false`
    // for a session the engine has forgotten and that is exactly the case a
    // user would otherwise see as "my message vanished".

    /**
     * Why the last thing the user did did not happen, or null. Cleared by the
     * next thing they do.
     */
    var lastRefusal by mutableStateOf<String?>(null)
        private set

    /**
     * Say why something the user asked for did not happen, from a caller
     * outside this object — the panel's terminal sign-in, which needs a
     * Context and so cannot live here.
     */
    fun reportRefusal(message: String) {
        lastRefusal = message
    }

    /**
     * Clear the engine's own notice — why the last mode or config change did
     * not take — and ours.
     */
    fun clearNotice() {
        lastRefusal = null
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch { runCatching { CoreBridge.acpClearNotice(session) } }
    }

    /**
     * Send the last prompt again, for a turn that failed on something worth
     * retrying — a rate limit, a provider hiccup.
     *
     * The text is remembered rather than read back out of the transcript,
     * because a refusal truncates the transcript past it and the whole point
     * is to be able to try again after one.
     */
    fun retryLastPrompt() {
        val last = lastPrompt ?: return
        prompt(last.text, last.mentions, last.images) { }
    }

    /** Start a fresh thread on the project the active one is in — `agent::NewThread`. */
    fun newThreadHere() {
        val thread = active ?: return
        newThread(thread.projectId, thread.projectName, thread.rootPath)
    }

    /**
     * The last prompt sent, for [retryLastPrompt] — attachments included, so
     * a retry after a rate limit sends the picture again rather than a
     * question about a picture the agent was never given.
     */
    private var lastPrompt: SentPrompt? = null

    private data class SentPrompt(
        val text: String,
        val mentions: List<AgentMention>,
        val images: List<PromptAttachment>,
    )

    fun clearRefusal() {
        lastRefusal = null
    }

    /**
     * Send a prompt; [onRefused] runs when the engine would not take it.
     * [mentions] are what the user attached — files, a selection, a thread,
     * a fetched page; the engine turns each into a resource block beside the
     * text. [images] are the pictures attached to it, already shrunk and
     * encoded by [PromptImages] — the engine drops them for an agent that did
     * not advertise `promptCapabilities.image`.
     *
     * **The first prompt of a thread carries the project's rules files** —
     * `AGENTS.md`, `CLAUDE.md`, `.rules` and the rest of Zed's list
     * ([RulesFiles]) — when they exist and are not already attached, as
     * Zed's project context does for its first message. Later prompts do
     * not: the agent has read them, and the picker's Rules section is there
     * to send them again on purpose.
     */
    fun prompt(
        text: String,
        mentions: List<AgentMention>,
        images: List<PromptAttachment> = emptyList(),
        onRefused: () -> Unit,
    ) {
        val session = sessionId
        val thread = active
        if (session < 0 || thread == null) {
            onRefused()
            lastRefusal = "There is no agent session to send that to."
            return
        }
        lastRefusal = null
        val mentions = if (thread.promptCount == 0) {
            mentions + RulesFiles.mentions(thread.rootPath, mentions)
        } else {
            mentions
        }
        // Kept for `retryLastPrompt`: a refusal truncates the transcript past
        // the prompt it refused, so the transcript cannot be the source.
        lastPrompt = SentPrompt(text, mentions, images)
        // Name the thread after the first thing said in it, until the agent
        // sends a name of its own — Zed's provisional title, set at exactly
        // this moment (thread_view.rs:1720-1732). Without it every
        // conversation was "Thread 1", "Thread 2", and the history list was a
        // column of numbers with no way to tell one from another.
        active?.let { thread ->
            if (thread.title == null) thread.title = provisionalTitle(text)
        }
        val mentionsJson = AgentMention.toJson(mentions)
        val imagesJson = PromptImages.toJson(images)
        thread.promptCount++
        scope.launch {
            val sent = runCatching { CoreBridge.acpPrompt(session, text, mentionsJson, imagesJson) }
                .getOrDefault(false)
            if (!sent) {
                onRefused()
                lastRefusal = "The agent did not take that message; the session may have ended."
            }
        }
    }

    /**
     * Put the editor's selection on the active thread's draft —
     * `agent::AddSelectionToThread` (agent_panel.rs:644-700). False when
     * there is no thread to add it to; the caller opens the panel either way,
     * which is what the command does in Zed.
     */
    fun addSelectionToThread(selection: AgentMention.Selection): Boolean {
        val thread = active ?: return false
        if (selection !in thread.draftMentions) thread.draftMentions.add(selection)
        return true
    }

    // --- checkpoints and the review -----------------------------------------

    /**
     * Zed's "Restore checkpoint" on the user message at [entryIndex]: the
     * files the agent edited from that turn on go back to what they held
     * before, and the later rows are marked reverted. Only what the engine
     * saw the agent write is covered — see [CoreBridge.acpRestoreCheckpoint].
     */
    fun restoreCheckpoint(entryIndex: Int) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val restored = runCatching { CoreBridge.acpRestoreCheckpoint(session, entryIndex.toLong()) }
                .getOrDefault(false)
            if (!restored) {
                lastRefusal = "There is nothing to restore at that message."
            }
        }
    }

    /** Zed's `agent::Keep` / `KeepAll`: an empty list keeps every file. */
    fun keepEdits(paths: List<String>) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch {
            runCatching { CoreBridge.acpKeepEdits(session, org.json.JSONArray(paths).toString()) }
        }
    }

    /** Zed's `agent::Reject` / `RejectAll`: an empty list rejects every file. */
    fun rejectEdits(paths: List<String>) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val rejected = runCatching {
                CoreBridge.acpRejectEdits(session, org.json.JSONArray(paths).toString())
            }.getOrDefault(false)
            if (!rejected) lastRefusal = "There is nothing to reject."
        }
    }

    /**
     * Answer the first waiting permission prompt with the option of [kind]
     * — `agent::AllowOnce` (`allow_once`), `AllowAlways` (`allow_always`),
     * `RejectOnce` (`reject_once`). Says so when nothing is waiting, because
     * a chord that silently did nothing is indistinguishable from a chord
     * that is not bound.
     */
    fun answerWaiting(kind: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val answered = runCatching { CoreBridge.acpAnswerWaiting(session, kind) }
                .getOrDefault(false)
            if (!answered) {
                lastRefusal = "Nothing is waiting for that answer."
            }
        }
    }

    // --- telling the user while they are not looking -------------------------
    //
    // Zed pops a notification when a turn ends or a permission is asked for
    // while its window is not focused (agent_panel.rs:2692-2790). The panel's
    // own poll only runs while the panel is composed, so this is a second,
    // slower watcher on the active thread that runs whether or not the panel
    // is on screen — it is exactly the case the panel cannot cover that it
    // exists for.

    /** Whether the agent panel is composed and showing. Set by the panel. */
    @Volatile
    var panelVisible: Boolean = false

    /** Whether the app has a visible activity. Set by `MainActivity`. */
    @Volatile
    var appInForeground: Boolean = true

    /** Zed's `agent.notify_when_agent_waiting`, kept current by the workspace. */
    @Volatile
    var notifyMode: NotifyWhenAgentWaiting = NotifyWhenAgentWaiting.PrimaryScreen

    /**
     * Bumped when a notification tap asks for the panel; the workspace
     * watches it and opens the dock.
     */
    var openPanelRequest by mutableStateOf(0)

    /** A notification tap arrived — `MainActivity` calls this. */
    fun requestPanel() {
        openPanelRequest++
    }

    private var watcher: Job? = null

    /** How often the watcher looks; slower than the panel, which is on screen. */
    private const val WATCH_MS = 500L

    /**
     * Start the background watcher, once. [context] is kept as the
     * application context — the only thing a notification needs.
     */
    fun watch(context: Context) {
        if (watcher != null) return
        val app = context.applicationContext
        watcher = scope.launch { watchLoop(app) }
    }

    private suspend fun watchLoop(app: Context) {
        var watched = -1L
        var seenVersion = -1L
        var wasBusy = false
        var needed = 0
        // Carried across ticks because the blocked-run notification below is
        // re-evaluated on every one of them, not only when the version moves,
        // and it has no session state in hand to read the name out of.
        var lastName = "Agent"
        var raised = false
        while (true) {
            val thread = active
            val session = thread?.sessionId ?: -1L
            if (session != watched) {
                // A new thread: take its state as the baseline rather than
                // announcing whatever it already is.
                watched = session
                seenVersion = -1L
                wasBusy = false
                needed = 0
            }
            if (session >= 0) {
                val version = runCatching { CoreBridge.acpSessionVersion(session) }.getOrDefault(0L)
                if (version != 0L && version != seenVersion) {
                    val state = AgentSessionState.parse(CoreBridge.acpSessionState(session))
                    val first = seenVersion < 0
                    seenVersion = version
                    val finished = wasBusy && !state.isBusy
                    val wanting = state.needsUser > needed
                    wasBusy = state.isBusy
                    needed = state.needsUser
                    lastName = state.agent?.agentName ?: agent?.name ?: "Agent"
                    if (!first) {
                        val agentName = lastName
                        val message = when {
                            wanting -> "Waiting for you"
                            finished && state.error != null -> "The turn failed"
                            finished -> "Finished"
                            else -> null
                        }
                        if (message != null) announce(app, agentName, message)
                        // The nav bar's dot. It says the same thing as the
                        // notification for the one case a notification cannot
                        // reach: the app in the foreground on Code or Build,
                        // where nothing is covering the screen to be pulled
                        // back from. `panelVisible` is the whole condition —
                        // if the destination is on screen the user is already
                        // looking at what the dot would point them at, and
                        // `ShellState.show` clears it on arrival anyway.
                        if (message != null && !panelVisible) {
                            withContext(Dispatchers.Main) {
                                ShellState.current.agentAttention = true
                            }
                        }
                        // A turn that failed is a failure like any other, and
                        // it belongs in the toast stack whether or not the
                        // notify setting is on — `announce` is about pulling
                        // attention back to the app, this is about the app
                        // not silently swallowing an error.
                        if (finished && state.error != null) {
                            onProblem?.invoke("$agentName: ${state.error}")
                        }
                    }
                }
            }
            // The high-priority, haptic, un-swipeable notification for a run
            // that has stopped and will not restart on its own. The Agent
            // destination owns this while it is composed (it also knows about
            // forms raised with no session at all); this is the half it cannot
            // own, because leaving the destination disposes its effect while
            // the request stays parked.
            //
            // Outside the version gate on purpose: backgrounding the app is
            // not a session event, so a permission raised while the phone was
            // in the hand and then pocketed would otherwise never be spoken.
            // `raised` keeps it to one call per edge rather than two a second.
            val wants = !panelVisible && needed > 0 && !appInForeground
            if (wants != raised) {
                raised = wants
                if (wants) {
                    AgentNotifier.waiting(app, lastName, AgentNotifier.waitingMessage(needed))
                } else {
                    AgentNotifier.clearWaiting(app)
                }
            }
            delay(WATCH_MS)
        }
    }

    /**
     * Say it, in the shape the moment calls for: nothing while the panel is
     * on screen or the setting says never; a toast when the app is in front
     * with the panel hidden; a notification when the app is not.
     */
    private suspend fun announce(app: Context, agentName: String, message: String) {
        if (!notifyMode.isOn) return
        if (panelVisible && appInForeground) return
        if (appInForeground) {
            withContext(Dispatchers.Main) { AgentNotifier.toast(app, agentName, message) }
        } else {
            AgentNotifier.notify(app, agentName, message)
        }
    }

    /**
     * What to call a thread whose agent has not named it: the first line of
     * the first thing the user said, trimmed — Zed's rule, and its 200
     * characters (thread_view.rs:1728-1730), which the bar ellipsizes and the
     * threads list has room for.
     *
     * Null for a message with nothing in it but whitespace, so an empty name
     * never displaces `Thread N`.
     */
    internal fun provisionalTitle(text: String): String? {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (line.isEmpty()) return null
        return if (line.length > PROVISIONAL_TITLE_MAX) {
            line.take(PROVISIONAL_TITLE_MAX).trimEnd() + "…"
        } else {
            line
        }
    }

    /**
     * Interrupt the running turn and send the first queued prompt now.
     *
     * Only offered when nothing is running, in which case it merely nudges
     * the queue along; interrupting *is* the deliberate act and lives on its
     * own control.
     */
    fun sendQueuedNow() {
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch { runCatching { CoreBridge.acpCancel(session) } }
    }

    /** Take one queued prompt back before it goes out. */
    fun removeQueued(queuedId: Long) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch { runCatching { CoreBridge.acpRemoveQueuedPrompt(session, queuedId) } }
    }

    /**
     * Change one of the agent's config options — model, permission, thinking,
     * Ultra, mode.
     *
     * Two failures live behind one boolean and they want opposite handling:
     *
     *  * **A refusal.** The agent got the request and said no — an Ultra that
     *    the permission level does not allow, a model the provider dropped.
     *    The change is *dropped*: the chip rolls back to whatever the next
     *    poll reports, and the user is told why. Re-sending it would be
     *    arguing with the agent.
     *  * **No transport.** There is no session, or the call threw on the way
     *    to it. The user's intent is still good, so it is **queued** and
     *    re-sent the next time a session attaches. This is the case that
     *    matters during onboarding, where a chip is tapped while the agent is
     *    still starting.
     *
     * [MODE_CONFIG_ID] is also remembered here, because a load or a resume
     * resets it agent-side and only this side knows what it was.
     */
    fun setConfigOption(configId: String, valueJson: String) {
        if (configId == MODE_CONFIG_ID) {
            // Parsed rather than string-trimmed: `valueJson` is a JSON *value*
            // (`"plan"`, quotes included), and an id with a quote or a
            // backslash in it would survive a trim as the wrong id.
            rememberedMode = runCatching { JSONTokener(valueJson).nextValue() as? String }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: rememberedMode
        }
        val session = sessionId.takeIf { it >= 0 }
        if (session == null) {
            queuedConfig[configId] = valueJson
            return
        }
        lastRefusal = null
        scope.launch {
            val accepted = runCatching { CoreBridge.acpSetConfigOption(session, configId, valueJson) }
                .getOrElse {
                    // Never reached the wire: keep it for the next attach.
                    queuedConfig[configId] = valueJson
                    return@launch
                }
            if (accepted) {
                queuedConfig.remove(configId)
            } else {
                // The agent answered, and the answer was no. The engine puts
                // its own sentence in `notice`; this is the fallback for a
                // session the engine has simply forgotten.
                queuedConfig.remove(configId)
                lastRefusal = "The agent would not take that setting."
            }
        }
    }

    /**
     * Config changes made with no session to send them to, by option id.
     *
     * A map rather than a list so the last value for an option wins: tapping
     * three models while the agent starts must send one, not three.
     */
    private val queuedConfig = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Re-send what was queued while there was nowhere to send it. Called by
     * the panel once a session reports `ready`.
     */
    fun flushQueuedConfig() {
        if (queuedConfig.isEmpty()) return
        val session = sessionId.takeIf { it >= 0 } ?: return
        val pending = queuedConfig.toMap()
        queuedConfig.clear()
        scope.launch {
            for ((configId, valueJson) in pending) {
                runCatching { CoreBridge.acpSetConfigOption(session, configId, valueJson) }
            }
        }
    }

    fun cancelTurn() {
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch { runCatching { CoreBridge.acpCancel(session) } }
    }

    /**
     * Answer a permission request with one of the options it offered.
     *
     * [answerMetaJson] is empty for the ordinary Allow / Always / Deny, and
     * carries `{"spettro.app/questionAnswer":…}` when the request is really a
     * *question* walked through the permission channel — Spettro's fallback
     * when the client did not advertise `_spettro/question/ask`, and still the
     * transport for a form the agent chose to walk. The option id says which
     * choice was taken; the `_meta` says what that choice *was*, which is the
     * only part free text can travel in (docs/SPETTRO.md, W-10). Build it with
     * [SpettroAnswers.optionMeta] or [SpettroAnswers.DECLINED_META].
     */
    fun respondToPermission(toolCallId: String, optionId: String, answerMetaJson: String = "") {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val answered = runCatching {
                CoreBridge.acpRespondPermission(session, toolCallId, optionId, answerMetaJson)
            }.getOrDefault(false)
            if (!answered) {
                lastRefusal = "That request is no longer waiting for an answer."
            }
        }
    }

    fun authenticate(methodId: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch { runCatching { CoreBridge.acpAuthenticate(session, methodId) } }
    }

    /** Ask the agent to work in a different mode; it confirms, or does not. */
    fun setMode(modeId: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val accepted = runCatching { CoreBridge.acpSetMode(session, modeId) }
                .getOrDefault(false)
            if (!accepted) {
                lastRefusal = "This agent would not change mode."
            }
        }
    }

    // --- Spettro's superset ---------------------------------------------------
    //
    // Everything below gates on `agent.spettroExtensions` being present in the
    // session state — absent means a generic ACP agent, for which steering is
    // two concurrent turns, a question has no transport, and `_spettro/*` is
    // nineteen methods that answer -32601. The gate itself is the panel's
    // (K1's `SpettroSurface`); these calls are safe to make either way, and
    // each says what it does when the agent is not Spettro.

    /**
     * Send a message *into* the turn that is already running — **steering**.
     *
     * Not a new turn and not a cancel. Spettro queues the text into the step
     * it is on, answers this prompt within milliseconds with `end_turn`, and
     * keeps working; the original turn stays the one the panel is busy on. Use
     * [prompt] to queue a whole new turn behind the current one, and
     * [cancelTurn] to stop it.
     *
     * Refused — with a sentence — when no turn is running or the agent is not
     * Spettro, because a second concurrent `session/prompt` to a generic agent
     * is two turns at once and the protocol says nothing about that.
     */
    fun steer(
        text: String,
        mentions: List<AgentMention>,
        images: List<PromptAttachment> = emptyList(),
        onRefused: () -> Unit = {},
    ) {
        val session = sessionId
        if (session < 0 || active == null) {
            onRefused()
            lastRefusal = "There is no agent session to steer."
            return
        }
        lastRefusal = null
        val mentionsJson = AgentMention.toJson(mentions)
        val imagesJson = PromptImages.toJson(images)
        scope.launch {
            val sent = runCatching { CoreBridge.acpSteer(session, text, mentionsJson, imagesJson) }
                .getOrDefault(false)
            if (!sent) {
                onRefused()
                lastRefusal = "There is nothing running to steer; send it as a message instead."
            }
        }
    }

    /**
     * Answer one of Spettro's question forms — `_spettro/question/ask`, which
     * carries the whole form in one request instead of walking it one
     * permission prompt at a time.
     *
     * [answers] in **option order** (the order the question listed its
     * choices), never tick order; a question the user did not touch is simply
     * absent from the list, which tells the model nobody answered it and is
     * not the same as defaulting to its recommendation. A null [answers]
     * declines the *whole* form — declining is all-or-nothing.
     *
     * [version] is the question payload's own `version`: 1 has no `questions`
     * array and takes the bare shape of the first answer.
     */
    fun answerQuestion(questionId: String, answers: List<SpettroAnswer>?, version: Int = 2) {
        lastRefusal = null
        val answerJson = SpettroAnswers.encode(answers, version)
        scope.launch {
            val answered = runCatching { CoreBridge.acpRespondQuestion(questionId, answerJson) }
                .getOrDefault(false)
            if (!answered) {
                lastRefusal = "That question is no longer waiting for an answer."
            }
        }
    }

    /**
     * Call one of the agent's `_spettro/…` methods and decode the envelope.
     *
     * **Suspends on `Dispatchers.IO`** because the bridge blocks for up to 45
     * seconds: `_spettro/providers/connect` verifies the key against the
     * provider's own API before it persists anything, and
     * `_spettro/account/status` blocks for up to fifteen. Nothing here retries
     * — a caller that wants a second attempt has to want it out loud, since
     * one of these calls charges a provider round trip.
     *
     * [projectId] defaults to the active thread's project, which is the one
     * the agent process is bound to.
     */
    suspend fun callExtension(
        method: String,
        paramsJson: String = "{}",
        projectId: Long = this.projectId,
    ): ExtResult = withContext(Dispatchers.IO) {
        if (projectId < 0) return@withContext ExtResult.Offline("The agent is not running.")
        val envelope = runCatching { CoreBridge.acpCallExtension(projectId, method, paramsJson) }
            .getOrElse { error ->
                Log.w(TAG, "$method could not be called", error)
                return@withContext ExtResult.Offline("The agent could not be reached.")
            }
        ExtResult.parse(envelope)
    }

    /**
     * Bumped whenever the agent's own session list should be re-fetched, and
     * read by `rememberAgentSessionList(enabled, refreshToken)`.
     *
     * A token rather than a call because the list lives in the engine's cache
     * and the composable owns the poll. Spettro saves after **every** prompt
     * turn, so a list that is not refreshed is stale within one message — the
     * picker bumps this when it opens, and the panel bumps it when a turn
     * settles.
     */
    var sessionListToken by mutableStateOf(0)
        private set

    /** Ask for a fresh `session/list` — a round trip to the agent. */
    fun refreshSessionList() {
        sessionListToken++
    }

    /** Forget the chosen agent as well — back to the picker. */
    fun reset() {
        close()
        agent = null
        rememberedMode = null
        queuedConfig.clear()
    }
}

/**
 * The answer to one question of a Spettro form, before it is encoded.
 *
 * Two shapes because the agent distinguishes them: picking from the list it
 * offered, and typing something it did not think of. A note is the free text
 * that travels *beside* a pick ("vet is slow"), and is dropped when blank.
 */
sealed interface SpettroAnswer {

    /** Which question of the form this answers — the payload's `questions[].id`. */
    val questionId: String

    data class Option(
        override val questionId: String,
        /** In OPTION order, never tick order. Empty means the question was skipped. */
        val optionIds: List<String>,
        val notes: String? = null,
    ) : SpettroAnswer

    data class Custom(
        override val questionId: String,
        val text: String,
        val notes: String? = null,
    ) : SpettroAnswer
}

/**
 * The wire shapes of a question answer, in one place so the encoder can be
 * tested without an agent.
 *
 * Two transports carry the same decision and neither can be derived from the
 * other, which is why both live here: the extension answers with a JSON-RPC
 * *result* ([encode]), while a form walked through the permission channel
 * answers by selecting an option id and attaching the same decision as `_meta`
 * ([optionMeta], [DECLINED_META]) — docs/SPETTRO.md, §5 and W-10.
 */
object SpettroAnswers {

    /** The `_meta` key both transports agree on. */
    private const val ANSWER_KEY = "spettro.app/questionAnswer"

    /**
     * The JSON-RPC result for `_spettro/question/ask`.
     *
     * Rules, all of them load-bearing:
     *  * a null [answers] is a decline of the whole form, at the TOP level;
     *  * an answer with nothing in it — no option, no text, no note — is
     *    **omitted**, so the model is told nobody answered that question
     *    rather than being told the recommendation was chosen;
     *  * `optionIds` always travels, and `optionId` travels *as well* for a
     *    single pick, because the agent reads whichever it knows about;
     *  * version 1 has no `questions[]` and therefore no envelope: it takes
     *    the first answer's own object, and a version-1 form with nothing
     *    answered can only be declined.
     */
    fun encode(answers: List<SpettroAnswer>?, version: Int = 2): String {
        if (answers == null) return declined()
        val objects = answers.mapNotNull(::encodeOne)
        if (version < 2) {
            return objects.firstOrNull()?.toString() ?: declined()
        }
        return JSONObject().put("answers", JSONArray(objects)).toString()
    }

    /** `{"kind":"declined"}` — the whole form, refused. */
    fun declined(): String = JSONObject().put("kind", "declined").toString()

    /**
     * The `_meta` that goes back with a permission-channel answer, naming the
     * option that was chosen. The option id is on the outcome as well; this is
     * what tells Spettro the outcome was an *answer* and not an approval.
     */
    fun optionMeta(optionId: String): String = JSONObject()
        .put(ANSWER_KEY, JSONObject().put("kind", "option").put("optionId", optionId))
        .toString()

    /**
     * Free text typed into the walked form's custom-input option, whose id is
     * `"custom"` by convention (`spettro.app/isCustomInput` on the option's
     * own `_meta` is what identifies it).
     */
    fun customMeta(text: String): String = JSONObject()
        .put(ANSWER_KEY, JSONObject().put("kind", "custom").put("text", text))
        .toString()

    /** Declining a walked form: paired with the `cancelled` outcome. */
    val DECLINED_META: String = JSONObject()
        .put(ANSWER_KEY, JSONObject().put("kind", "declined"))
        .toString()

    private fun encodeOne(answer: SpettroAnswer): JSONObject? = when (answer) {
        is SpettroAnswer.Option -> {
            val notes = answer.notes?.takeIf { it.isNotBlank() }
            if (answer.optionIds.isEmpty() && notes == null) {
                null
            } else {
                JSONObject().apply {
                    put("questionId", answer.questionId)
                    put("kind", "option")
                    put("optionIds", JSONArray(answer.optionIds))
                    // Both, deliberately: `optionId` is what the single-select
                    // half of the CLI reads and `optionIds` is what the
                    // multi-select half reads, and one build has been seen to
                    // read each.
                    if (answer.optionIds.size == 1) put("optionId", answer.optionIds.single())
                    if (notes != null) put("notes", notes)
                }
            }
        }
        is SpettroAnswer.Custom -> {
            val text = answer.text.trim()
            val notes = answer.notes?.takeIf { it.isNotBlank() }
            if (text.isEmpty() && notes == null) {
                null
            } else {
                JSONObject().apply {
                    put("questionId", answer.questionId)
                    put("kind", "custom")
                    put("text", text)
                    if (notes != null) put("notes", notes)
                }
            }
        }
    }
}

/**
 * What one `_spettro/…` call came back with.
 *
 * The bridge answers with an envelope rather than throwing — JNI has no
 * exception channel here — and this is the one place it is decoded. Four
 * outcomes because the UI has four different things to say, and collapsing any
 * two of them costs a user an accurate sentence:
 *
 *  * [Ok] — the method's own result object.
 *  * [Rpc] — the agent answered with an error, and [Rpc.message] is *its*
 *    words ("key rejected (401)"), written to be read by the person who typed
 *    the key. Show it verbatim; do not wrap it in a sentence of ours.
 *  * [Unsupported] — `-32601`, which on this protocol means an older CLI
 *    rather than a failure. "Update Spettro", never "that did not work".
 *  * [Offline] — the call never reached the wire.
 */
sealed interface ExtResult {

    data class Ok(val result: JSONObject) : ExtResult

    data class Rpc(val code: Int, val message: String, val data: JSONObject?) : ExtResult

    data object Unsupported : ExtResult

    data class Offline(val message: String) : ExtResult

    /** The result object, or null for anything that was not an [Ok]. */
    val objectOrNull: JSONObject? get() = (this as? Ok)?.result

    companion object {

        /** `-32601`: the method is not there, which is a version, not a fault. */
        const val METHOD_NOT_FOUND = -32601

        /** The engine's own code for "this never left the phone". */
        const val NO_TRANSPORT = 0

        /**
         * Decode the bridge's envelope. Never throws: an envelope that is not
         * JSON at all is [Offline], because something is wrong on this side of
         * the wire and telling the user their key was rejected would be a lie.
         *
         * A result that is not an object — no `_spettro/…` method returns one
         * today, but the extension is versioned and may — is wrapped under
         * `result`, so decoders always have an object to read from.
         */
        fun parse(envelope: String): ExtResult {
            val root = runCatching { JSONObject(envelope) }.getOrNull()
                ?: return Offline("The agent's answer could not be read.")
            if (root.optBoolean("ok", false)) {
                val result = root.opt("result")
                return Ok(result as? JSONObject ?: JSONObject().put("result", result ?: JSONObject.NULL))
            }
            val code = root.optInt("code", NO_TRANSPORT)
            val message = root.optString("message").takeIf { it.isNotBlank() }
                ?: "The agent did not say what went wrong."
            return when (code) {
                METHOD_NOT_FOUND -> Unsupported
                NO_TRANSPORT -> Offline(message)
                else -> Rpc(code, message, root.optJSONObject("data"))
            }
        }
    }
}
