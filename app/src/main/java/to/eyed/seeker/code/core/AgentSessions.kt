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
import to.eyed.seeker.code.terminal.Userland

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
        startThread(project, projectName, rootPath, pastSessionId)
    }

    private fun startThread(project: Long, projectName: String, rootPath: String, resume: String?) {
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
        val spec = agent.toSpecJson()
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
            val thread = AgentThread(id, project, projectName, rootPath, nextOrdinal++, resume != null)
            threads.add(thread)
            active = thread
            isStarting = false
        }
    }

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
                    if (!first) {
                        val agentName = state.agent?.agentName ?: agent?.name ?: "Agent"
                        val message = when {
                            wanting -> "Waiting for you"
                            finished && state.error != null -> "The turn failed"
                            finished -> "Finished"
                            else -> null
                        }
                        if (message != null) announce(app, agentName, message)
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

    /** Change one of the agent's config options — model, effort, a toggle. */
    fun setConfigOption(configId: String, valueJson: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            runCatching { CoreBridge.acpSetConfigOption(session, configId, valueJson) }
        }
    }

    fun cancelTurn() {
        val session = sessionId.takeIf { it >= 0 } ?: return
        scope.launch { runCatching { CoreBridge.acpCancel(session) } }
    }

    fun respondToPermission(toolCallId: String, optionId: String) {
        val session = sessionId.takeIf { it >= 0 } ?: return
        lastRefusal = null
        scope.launch {
            val answered = runCatching {
                CoreBridge.acpRespondPermission(session, toolCallId, optionId)
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

    /** Forget the chosen agent as well — back to the picker. */
    fun reset() {
        close()
        agent = null
    }
}
