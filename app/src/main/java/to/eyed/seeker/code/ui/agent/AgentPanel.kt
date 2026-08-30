package to.eyed.seeker.code.ui.agent

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateValue
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentCommand
import to.eyed.seeker.code.core.AgentConversation
import to.eyed.seeker.code.core.AgentMention
import to.eyed.seeker.code.core.AgentNotifier
import to.eyed.seeker.code.core.FetchMention
import to.eyed.seeker.code.core.AgentDefinition
import to.eyed.seeker.code.core.AgentAuthMethod
import to.eyed.seeker.code.core.AgentCapabilities
import to.eyed.seeker.code.core.AgentElicitation
import to.eyed.seeker.code.core.AgentErrorKind
import to.eyed.seeker.code.core.AgentPlanEntry
import to.eyed.seeker.code.core.AgentQueuedPrompt
import to.eyed.seeker.code.core.AgentPastSession
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.AgentThread
import to.eyed.seeker.code.core.AgentPhase
import to.eyed.seeker.code.core.AgentSessionState
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.AgentTerminalState
import to.eyed.seeker.code.core.ElicitationAnswer
import to.eyed.seeker.code.core.ElicitationField
import to.eyed.seeker.code.core.Agents
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.FileDiff
import to.eyed.seeker.code.core.PermissionOption
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.PromptImages
import to.eyed.seeker.code.core.PromptAttachment
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.core.ProjectSummary
import to.eyed.seeker.code.core.ToolCallStatus
import to.eyed.seeker.code.core.ToolKind
import to.eyed.seeker.code.core.rememberAgentSession
import to.eyed.seeker.code.core.rememberAgentSessionList
import to.eyed.seeker.code.core.rememberPendingElicitations
import to.eyed.seeker.code.core.rememberAgentTerminal
import to.eyed.seeker.code.core.stripAnsi
import to.eyed.seeker.code.terminal.TerminalSessions
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.git.DiffLineRow
import to.eyed.seeker.code.ui.preview.MarkdownText
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.workspace.ContextMenu
import to.eyed.seeker.code.ui.workspace.ContextMenuItem
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.theme.revealBy

/**
 * Zed's `agent_panel.default_width` (assets/settings/default.json:1024).
 */
internal val AgentPanelDockWidth = 400.dp

/** `Tab::container_height` = 32px, as every other panel's bar is. */
private val BarHeight = 32.dp

/** Rows are `pl_2p5` / `pr_1` — 10px in, 4px out, as the git panel's are. */
private val RowStartPadding = 10.dp

/** Inputs are `rounded_md` = 6px (search_bar.rs:78). */
private val FieldRadius = 6.dp

/**
 * The switch a boolean config option is, at Zed's proportions: track 32×20
 * with a 12 thumb inset by 2 (`DynamicSpacing::Base32`/`Base20`/`Base12`/
 * `Base02`, toggle.rs:518-539), scaled down a touch because the panel's own
 * text is smaller than Zed's on a desktop.
 */
private val SwitchWidth = 28.dp
private val SwitchHeight = 16.dp
private val SwitchInset = 2.dp

/**
 * Six lines of composer and no more, which is what Zed pins its own panel
 * editors to (`MAX_PANEL_EDITOR_LINES = 6`, git_panel.rs:1080) — past that the
 * conversation would lose the panel.
 */
private const val ComposerLines = 6

/**
 * A tool-call diff is a card, not a document: past this many lines it is
 * summarised rather than unrolled, because the conversation scrolls and a
 * generated file would bury it.
 */
private const val MaxDiffLines = 200

/**
 * How many lines of a terminal's output the card shows.
 *
 * The engine already caps what it *keeps* (a megabyte), which is the memory
 * question; this is the reading question. A build log unrolled in full pushes
 * the rest of the conversation off the screen, so the card shows the tail —
 * the end is where the error is — and says how much it left out.
 */
private const val MaxTerminalLines = 40

/**
 * How long a keystroke in the composer waits before the `@` popup's files are
 * matched. [findMentionFiles] is a blocking call under the engine's project
 * mutex, so firing on every keystroke queues stale searches that contend with
 * the fresh one — the file finder's debounce, for the same reason.
 */
private const val MentionDebounceMillis = 120L

/**
 * Whether this build can show an agent panel at all.
 *
 * Agents run inside the Linux userland, so the `play` edition — which has no
 * userland and never will — is not offered one, greyed or otherwise. The
 * same rule the git panel, the clone action and the language-server install
 * already follow.
 */
val isAgentPanelSupported: Boolean
    get() = AgentSessions.isSupported

/**
 * The agent panel — Zed's `crates/agent_ui`, in the shape a phone can hold.
 *
 * A conversation with whatever ACP agent the user configured: their prompt,
 * the agent's reply as markdown, its plan, and a card per tool call carrying
 * the diff of anything it wants to write. Nothing it writes lands without a
 * decision — a permission request stops the turn and puts Allow and Deny in
 * the transcript where the change is, so the diff and the choice are the same
 * screen rather than two.
 *
 * A dock beside the editor on a wide screen and the whole work area on a
 * compact one, which is the split every other panel already makes.
 *
 * Unified diffs only, which is a locked decision rather than a shortcut
 * (DECISIONS.md): side-by-side is wrong on a phone.
 *
 * Touch, keyboard and mouse in the same change: every row and button is a tap
 * target with a hand cursor, `Enter` sends and `Shift+Enter` breaks the line,
 * `Esc` stops a running turn, and the composer takes focus whenever the
 * panel's chord is pressed.
 */
@Composable
fun AgentPanel(
    project: ProjectSession,
    /**
     * Bumped by the workspace whenever the panel's chord is pressed, so
     * pressing it again puts the keyboard back in the composer.
     */
    focusToken: Int,
    onOpenPath: (String) -> Unit,
    /** Open the settings screen — where agents are added and edited. */
    onOpenSettings: () -> Unit,
    /** Open the review tab — Zed's `agent::OpenAgentDiff`, the badge's tap. */
    onOpenReview: () -> Unit = {},
    /** The open buffers and the editor's selection, for the `@` picker. */
    workspace: AgentWorkspaceAccess = AgentWorkspaceAccess.NONE,
    /**
     * The composer reporting whether it holds the keyboard, so the
     * workspace can scope `ctrl-n` to `agent::NewThread` while it does —
     * Zed's `AgentPanel` context (default-linux.json:218-220).
     */
    onFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val composer = remember { FocusRequester() }
    // The background watcher notifies only while the panel is *not* on
    // screen, and takes its notification down the moment it is.
    DisposableEffect(Unit) {
        AgentSessions.panelVisible = true
        onDispose {
            AgentSessions.panelVisible = false
            onFocusChanged(false)
        }
    }
    val panelScope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(context) { AgentNotifier.dismiss(context) }
    // The dock, for a terminal sign-in. Reached here rather than threaded
    // through the dock plumbing: it is one shared object per context, and
    // this is the only panel that ever opens a session in it.
    val terminals = remember(context) { TerminalSessions.of(context) }

    val agent = AgentSessions.agent
    val activeThread = AgentSessions.active
    val sessionId = AgentSessions.sessionId.takeIf { it >= 0 }
    val snapshot = rememberAgentSession(sessionId)
    val state = snapshot.state
    // Polled whenever an agent is running, session or no session.
    val connectionQuestions = rememberPendingElicitations(agent != null)
    // Bumped by the strip's "Show", read by the transcript, which is the only
    // thing that can scroll itself.
    var scrollToPending by remember { mutableStateOf(0) }
    // How much the agent is blocked on that the reader cannot currently see.
    // Reported by the transcript, which is the only thing that knows what is
    // on screen: a permission prompt in front of you needs no banner, and one
    // that has scrolled away stalls the turn with nothing to explain it.
    var pendingCount by remember { mutableStateOf(0) }
    // The thread list — Zed's history view, toggled from the bar.
    var showThreads by remember { mutableStateOf(false) }

    // Opening a thread is the panel's own business, not a button's: with an
    // agent chosen and a project open there is nothing else the user could
    // mean. It is a no-op once the project has one showing.
    LaunchedEffect(agent, project.id) {
        if (agent != null) AgentSessions.open(project.id, project.rootName, project.rootPath)
    }
    LaunchedEffect(focusToken) {
        if (agent != null) runCatching { composer.requestFocus() }
    }
    // Stamp the agent's own title onto the thread, so the history list can
    // name it after it stops being the one showing.
    LaunchedEffect(state.title, state.acpSessionId, activeThread) {
        state.title?.let { title -> activeThread?.title = title }
        // Stamped onto the thread so the history list can tell a conversation
        // that is already open from one that is not.
        state.acpSessionId?.let { id -> activeThread?.acpSessionId = id }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background", theme.color("surface.background"))),
    ) {
        AgentBar(
            state = state,
            agent = agent,
            thread = activeThread,
            showingThreads = showThreads,
            agents = settings.agents,
            editedFiles = state.editedFiles,
            onOpenReview = onOpenReview,
            onUseAgent = { chosen ->
                // A *new thread* with that agent, not a purge. The threads
                // this agent already has stay open.
                AgentSessions.startWith(chosen, project.id, project.rootName, project.rootPath)
            },
            onSignOut = {
                // Deliberately does not end the open threads: what signing
                // out means for a conversation in flight is the agent's call,
                // and the next thing it refuses arrives through the ordinary
                // "needs signing in" path the panel already draws.
                panelScope.launch {
                    withContext(Dispatchers.Default) { CoreBridge.acpLogout() }
                }
            },
            onNewThread = {
                // Re-resolved by name first: settings.json may have been
                // edited since this definition was captured, and "+" should
                // launch what the file says *now* — a running thread
                // deliberately keeps the argv it started with, so this is the
                // moment an edit takes effect. An entry edited away entirely
                // keeps the old definition: the user asked for a thread, not
                // the picker.
                agent?.let { current ->
                    settings.agents
                        .firstOrNull { it.name == current.name }
                        ?.let(AgentSessions::choose)
                }
                AgentSessions.newThread(project.id, project.rootName, project.rootPath)
                showThreads = false
            },
            onToggleThreads = { showThreads = !showThreads },
        )
        HorizontalDivider(color = theme.color("border"))

        when {
            // No userland at all: the command that opens this is absent in the
            // `play` edition, so this is a backstop rather than a path.
            !isAgentPanelSupported -> Notice(
                "This edition has no Linux userland, so it cannot run an agent.",
            )

            // Questions that belong to no conversation, over everything else.
            // One of these can be raised before any session exists — an
            // `authenticate` that wants a token — and the agent is stuck
            // until it is answered; drawing it only inside the transcript
            // made it unreachable exactly then.
            connectionQuestions.isNotEmpty() -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (question in connectionQuestions) ElicitationCard(question)
            }

            agent == null -> AgentPicker(
                agents = settings.agents,
                onChoose = { AgentSessions.choose(it) },
                onOpenSettings = onOpenSettings,
            )

            // Zed's history: every thread, grouped by project, searchable
            // (agent_ui/src/threads_archive_view.rs).
            showThreads -> ThreadsView(
                currentProject = project,
                capabilities = state.agent?.capabilities ?: AgentCapabilities(),
                onSelect = { thread ->
                    AgentSessions.select(thread)
                    showThreads = false
                },
                onClose = { thread -> AgentSessions.closeThread(thread) },
                onReopen = { past ->
                    // Already open? Show it. Resuming it again would index
                    // the agent's session id onto a second thread and steal
                    // the first one's updates — leaving a thread on screen
                    // that could never receive anything again.
                    val open = AgentSessions.threadFor(past.sessionId)
                    if (open != null) {
                        AgentSessions.select(open)
                    } else {
                        AgentSessions.resumeThread(
                            project.id,
                            project.rootName,
                            project.rootPath,
                            past.sessionId,
                        )
                    }
                    showThreads = false
                },
                onNewThread = {
                    AgentSessions.newThread(project.id, project.rootName, project.rootPath)
                    showThreads = false
                },
            )

            AgentSessions.startError != null -> Notice(
                AgentSessions.startError!!,
                isError = true,
            )

            AgentSessions.isStarting -> Notice("Starting the agent…")

            // Every thread closed. Saying "starting" here was simply untrue —
            // nothing was starting, and the panel sat on that lie until the
            // user happened to press New. An empty state that says so, with
            // the way out in it.
            sessionId == null -> Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Notice("No thread open. Start one to talk to ${agent?.name ?: "the agent"}.")
                PanelButton("+ New Thread", isPrimary = true, onClick = {
                    AgentSessions.newThread(project.id, project.rootName, project.rootPath)
                })
            }

            else -> {
                Conversation(
                    state = state,
                    conversation = snapshot.conversation,
                    agent = agent,
                    scrollToPending = scrollToPending,
                    onPendingOffscreen = { pendingCount = it },
                    onOpenPath = onOpenPath,
                    onRespond = AgentSessions::respondToPermission,
                    onRestoreCheckpoint = AgentSessions::restoreCheckpoint,
                    onAuthenticate = { method ->
                        // Two different things wear the same button. An
                        // ordinary method means "ask the agent to sign in",
                        // which is the `authenticate` request. A **terminal**
                        // method means "run me with these arguments and let
                        // the user answer" — sending `authenticate` for one
                        // of those signs nobody in and says nothing about
                        // why. It needs a real pty and a keyboard, which is
                        // the terminal dock, not the pipe-shaped terminals
                        // the agent itself drives.
                        if (method.isTerminal) {
                            val login = Userland.backend.execCommand(
                                context,
                                project.rootPath,
                                agent?.argv.orEmpty() + method.args,
                                (agent?.env.orEmpty() + method.env)
                                    .map { (name, value) -> "$name=$value" },
                            )
                            if (login == null) {
                                AgentSessions.reportRefusal(
                                    "This build has no Linux userland to sign in with.",
                                )
                            } else {
                                terminals.runSession(
                                    project.rootPath,
                                    method.name,
                                    login,
                                )
                            }
                        } else {
                            AgentSessions.authenticate(method.id)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                // The pinned strip: the plan, a turn that failed, anything
                // waiting on the user. Everything in it used to be in the
                // transcript, where it scrolled away exactly when it mattered.
                ActivityStrip(
                    state = state,
                    pendingCount = pendingCount,
                    onScrollToPending = { scrollToPending++ },
                )
                HorizontalDivider(color = theme.color("border"))
                Composer(
                    state = state,
                    enabled = state.canPrompt,
                    isBusy = state.isBusy,
                    focus = composer,
                    project = project,
                    thread = activeThread,
                    commands = state.commands,
                    workspace = workspace,
                    onFocusChanged = onFocusChanged,
                    onSend = { text, mentions, images, onRefused ->
                        AgentSessions.prompt(text, mentions, images, onRefused)
                    },
                    onStop = AgentSessions::cancelTurn,
                )
            }
        }
    }
}

/** The bar: which thread, what it is doing, and the ways to the others. */
@Composable
private fun AgentBar(
    state: AgentSessionState,
    agent: AgentDefinition?,
    thread: AgentThread?,
    showingThreads: Boolean,
    /** Every agent settings.json configures, for the bar's Agent menu. */
    agents: List<AgentDefinition>,
    /** How many edited files await a Keep or Reject — the review badge. */
    editedFiles: Int,
    onOpenReview: () -> Unit,
    onUseAgent: (AgentDefinition) -> Unit,
    onSignOut: () -> Unit,
    onNewThread: () -> Unit,
    onToggleThreads: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .padding(horizontal = RowStartPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (showingThreads) {
                "Threads"
            } else {
                state.title ?: thread?.listTitle ?: agent?.name ?: "Agent"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        state.usage?.let { usage ->
            usage.fraction?.let { fraction ->
                Text(
                    text = "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                )
            }
        }
        Box(modifier = Modifier.weight(1f))
        if (agent != null) {
            // Zed's "Review Changes" button with its count, shown only while
            // there is something to review (thread_view.rs:4175-4183).
            if (editedFiles > 0) {
                BarAction(
                    if (editedFiles == 1) "Review 1 file" else "Review $editedFiles files",
                    onClick = onOpenReview,
                )
            }
            // Zed's bar: `+` starts a thread, the history icon lists them
            // (agent_panel.rs — the panel toolbar). Words, at this size.
            BarAction("+ New", onClick = onNewThread)
            BarAction(if (showingThreads) "Back" else "Threads", onClick = onToggleThreads)
            // A menu rather than a fourth word: the bar is 32px on a phone,
            // and everything in it is about *which agent this is*.
            // The configured agents, inline. It used to be one item —
            // "Change agent…" — that closed **every** open thread behind a
            // single tap, with no confirmation and no undo. Zed's own New
            // Thread menu lists each registered agent and leaves the existing
            // threads alone (agent_panel.rs:5817-5985).
            SelectorChip(
                label = "Agent",
                items = buildList {
                    for (other in agents) {
                        add(
                            ContextMenuItem(
                                label = if (other.name == agent?.name) {
                                    "✓ ${other.name}"
                                } else {
                                    "New thread with ${other.name}"
                                },
                            ) { onUseAgent(other) },
                        )
                    }
                    if (state.agent?.capabilities?.logout == true) {
                        add(ContextMenuItem("Sign out", onClick = onSignOut))
                    }
                },
            )
        }
    }
}

@Composable
private fun BarAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = when {
            !enabled -> theme.color("text.disabled", theme.color("text.muted"))
            hovered -> theme.color("text")
            else -> theme.color("text.muted")
        },
        maxLines = 1,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/**
 * Which agent to talk to — Zed's External Agents list, as the panel's front
 * door (settings_ui/src/pages/external_agents_page.rs:51-58): the agents
 * connected through the Agent Client Protocol, which means exactly what
 * `agent_servers` configures. No agent is named in code and none is offered
 * for installation — ACP is a standard, and which agent to run (and how it
 * gets onto the userland's PATH) is the user's own business.
 */
@Composable
private fun AgentPicker(
    agents: List<AgentDefinition>,
    onChoose: (AgentDefinition) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "EXTERNAL AGENTS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = theme.color("text.muted"),
        )
        Text(
            text = "Agents connected through the Agent Client Protocol, run inside " +
                "${Userland.backend.displayName} against this project.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text.muted"),
        )
        if (agents.isEmpty()) {
            // Zed's dashed empty-state box (external_agents_page.rs:111-125),
            // pointing at the two ways in: the settings section's form, and
            // the settings.json key it writes.
            Text(
                text = "No external agents added yet. Add one in Settings, or under " +
                    "agent_servers in settings.json.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        theme.color("border").copy(alpha = 0.6f),
                        RoundedCornerShape(FieldRadius),
                    )
                    .padding(12.dp),
            )
        }
        for (definition in agents) {
            AgentChoice(definition, onClick = { onChoose(definition) })
        }
        PanelButton("Add Agent", isPrimary = agents.isEmpty(), onClick = onOpenSettings)
    }
}

@Composable
private fun AgentChoice(agent: AgentDefinition, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FieldRadius))
            .background(
                when {
                    pressed -> theme.color("element.active", Color.Transparent)
                    hovered -> theme.color("element.hover", Color.Transparent)
                    else -> theme.color("element.background", Color.Transparent)
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = agent.name,
                onClick = onClick,
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = agent.name,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
        )
        // The command line it will run — identification, not instruction.
        Text(
            text = agent.argv.joinToString(" "),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = BufferFontFamily),
            color = theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The thread history — Zed's threads view
 * (agent_ui/src/threads_archive_view.rs): a search field, then every project
 * with its threads under it, "No threads yet" where there are none. Threads
 * live in memory with their engine sessions; a project's group is its live
 * conversations, and other projects list so the shape of the feature is
 * visible — their threads begin when a thread is opened *in* them.
 */
@Composable
private fun ThreadsView(
    currentProject: ProjectSession,
    /** What the agent said it can do, so history is offered only when it exists. */
    capabilities: AgentCapabilities,
    onSelect: (AgentThread) -> Unit,
    onClose: (AgentThread) -> Unit,
    onNewThread: () -> Unit,
    onReopen: (AgentPastSession) -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(emptyList<ProjectSummary>()) }
    LaunchedEffect(Unit) {
        projects = withContext(Dispatchers.IO) { ProjectsRoot.list(context) }
    }
    // Bumped after a delete, which is what asks for a fresh `session/list`
    // rather than another read of the same cache.
    var refreshToken by remember { mutableStateOf(0) }
    val history = rememberAgentSessionList(capabilities.hasHistory, refreshToken)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Zed's "Search threads…" field, over titles and project names.
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background", Color.Transparent))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search threads…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color("text.muted"),
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )

        val names = buildList {
            add(currentProject.rootName)
            for (project in projects) {
                if (project.name != currentProject.rootName) add(project.name)
            }
        }
        // Whether anything at all matched, so the view can say so once at the
        // end rather than printing "No threads yet" under every project.
        var anything = false
        for (name in names) {
            // A query that matches the *project* keeps all of its threads.
            // Typing a project's name used to print "No threads yet" under
            // that project's own header while its threads sat one filter
            // away, because the filter only ever looked at the thread title
            // (threads_archive_view.rs:301 matches the folder name too).
            val matchesProject = name.contains(query, ignoreCase = true)
            val mine = AgentSessions.threads
                .filter { thread ->
                    thread.projectName == name &&
                        (query.isBlank() || matchesProject ||
                            thread.listTitle.contains(query, ignoreCase = true))
                }
                .sortedByDescending { it.ordinal }
            if (query.isNotBlank() && mine.isEmpty() && !matchesProject) {
                continue
            }
            if (mine.isNotEmpty()) anything = true
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (mine.isEmpty()) {
                Text(
                    text = if (query.isBlank()) "No threads yet" else "No threads match",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            for (thread in mine) {
                ThreadRow(
                    thread = thread,
                    isActive = thread == AgentSessions.active,
                    onSelect = { onSelect(thread) },
                    onClose = { onClose(thread) },
                )
            }
            if (name == currentProject.rootName) {
                // The `+` in the bar, for a finger scrolling the list.
                BarAction("+ New Thread", onClick = onNewThread)
            }
        }

        // The agent's own memory, which is a different thing from the threads
        // above: those are conversations this app is holding open, these are
        // ones the agent kept and can hand back. Only shown when it says it
        // can both list and reopen them — `session/list` without
        // `session/load` or `session/resume` is a list of rows that do
        // nothing.
        if (capabilities.hasHistory) {
            HorizontalDivider(
                color = theme.color("border"),
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                text = "Kept by the agent",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
            )
            if (!capabilities.loadSession) {
                Text(
                    text = "This agent reopens a conversation without its transcript.",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                )
            }
            // Searched by the directory too, for the same reason as above:
            // an agent's own sessions are named after where they ran when
            // the agent gave them no title.
            val past = history.sessions.filter {
                query.isBlank() ||
                    it.label.contains(query, ignoreCase = true) ||
                    it.cwd.contains(query, ignoreCase = true)
            }
            if (past.isNotEmpty()) anything = true
            when {
                history.error != null -> Notice(history.error, isError = true)
                past.isEmpty() && history.loading -> Text(
                    text = "Asking the agent…",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    modifier = Modifier.padding(start = 10.dp),
                )
                past.isEmpty() -> Text(
                    text = "Nothing kept",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    modifier = Modifier.padding(start = 10.dp),
                )
                else -> for (session in past) {
                    PastSessionRow(
                        session = session,
                        canDelete = capabilities.delete,
                        isOpen = AgentSessions.threadFor(session.sessionId) != null,
                        onOpen = { onReopen(session) },
                        onDelete = {
                            scope.launch {
                                withContext(Dispatchers.Default) {
                                    CoreBridge.acpDeleteSession(session.sessionId)
                                }
                                // The engine refreshes the list itself when
                                // the delete lands; this asks the poller to
                                // stop coasting on the cache it has.
                                refreshToken++
                            }
                        },
                    )
                }
            }
        }

        // One honest sentence rather than a column of "No threads yet"
        // headings — a search that matches nothing should say so once.
        if (!anything && query.isNotBlank()) {
            Text(
                text = "No threads match “$query”.",
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** One of the agent's own past conversations, in the threads view. */
@Composable
private fun PastSessionRow(
    session: AgentPastSession,
    canDelete: Boolean,
    /** Whether a live thread is already showing this conversation. */
    isOpen: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("element.hover", Color.Transparent) else Color.Transparent,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = session.label,
                onClick = onOpen,
            )
            .padding(start = RowStartPadding, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = session.label,
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isOpen) {
            Text(
                text = "open",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                maxLines = 1,
            )
        }
        session.updatedAt?.take(10)?.let { day ->
            Text(
                text = day,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                maxLines = 1,
            )
        }
        if (canDelete) {
            BarAction("Forget", onClick = onDelete)
        }
    }
}

@Composable
private fun ThreadRow(
    thread: AgentThread,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    isActive -> theme.color("element.selected", Color.Transparent)
                    hovered -> theme.color("element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = thread.listTitle,
                onClick = onSelect,
            )
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = thread.listTitle,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BarAction("Close", onClick = onClose)
    }
}

/**
 * The selectors in the composer's bottom row — Zed's right-hand group (token
 * usage, then mode, then config options; thread_view.rs:4441-4454), rendered
 * entirely from what the agent advertised: its session modes, and its config
 * options (`session/set_config_option` behind each). Nothing is hardcoded;
 * an agent with none gets nothing here, and the send button stands alone.
 */
@Composable
private fun ComposerChrome(state: AgentSessionState, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val modes = state.modes
    // Gated on there being modes to *choose between*, not on the current one
    // resolving. One `currentModeId` the agent never listed used to remove
    // the whole strip — every config chip with it. Zed labels the unmatched
    // case and keeps the selector (mode_selector.rs:132-195).
    val hasModes = modes != null && modes.available.isNotEmpty()
    if (!hasModes && state.configOptions.isEmpty() && state.usage?.cost == null) return
    // The agent is mid-turn: changing its model or mode underneath it is not
    // a thing it can honour, so the chips go quiet rather than lying.
    val live = !state.isBusy
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // What the turn has cost, when the agent says. An agent that reports
        // it and a client that drops it is a bill the user cannot see. First
        // in the group, where Zed puts its token usage (thread_view.rs:4446).
        state.usage?.cost?.let { cost ->
            Text(
                text = "%.2f %s".format(cost.amount, cost.currency),
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        if (hasModes && modes != null) {
            SelectorChip(
                label = modes.current?.name
                    ?: modes.currentId.ifEmpty { "Unknown" },
                enabled = live,
                items = modes.available.map { mode ->
                    ContextMenuItem(
                        label = if (mode.id == modes.currentId) "✓ ${mode.name}" else mode.name,
                    ) { AgentSessions.setMode(mode.id) }
                },
            )
        }
        for (option in state.configOptions) {
            when (option.kind) {
                "select" -> SelectorChip(
                    label = option.currentLabel,
                    enabled = live,
                    items = option.values.map { value ->
                        ContextMenuItem(
                            // A tick, so an open menu says which one is
                            // already chosen.
                            label = if (value.id == option.currentValueId) {
                                "✓ ${value.name}"
                            } else {
                                value.name
                            },
                        ) {
                            // JSONObject.quote, because a value id is wire
                            // data and may carry anything.
                            AgentSessions.setConfigOption(
                                option.id,
                                org.json.JSONObject.quote(value.id),
                            )
                        }
                    },
                )
                // A switch with its label in front, not a "Name: Off" button
                // — Zed renders a boolean option as exactly that
                // (config_options.rs:579-587, SwitchLabelPosition::Start).
                "boolean" -> SwitchChip(
                    label = option.name,
                    checked = option.currentBool == true,
                    enabled = live,
                ) {
                    AgentSessions.setConfigOption(
                        option.id,
                        (option.currentBool != true).toString(),
                    )
                }
            }
        }
    }
}

/** A tappable label that drops the choices under itself. */
@Composable
private fun SelectorChip(
    label: String,
    items: List<ContextMenuItem>,
    enabled: Boolean = true,
) {
    val theme = LocalZedTheme.current
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = label,
                ) { if (enabled) open = true }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            Text(
                // Zed cuts the value name at 32 graphemes with an ellipsis
                // (config_options.rs:426-432); a select whose current value
                // is a sentence would otherwise push the send button away.
                text = if (label.length > 32) label.take(32) + "…" else label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    !enabled -> theme.color("text.disabled", theme.color("text.muted"))
                    hovered -> theme.color("text")
                    else -> theme.color("text.muted")
                },
                maxLines = 1,
            )
            // The chevron that says "this drops a menu", flipped while it is
            // open — Zed's trigger button ends in the same icon
            // (config_options.rs:419-423, 440).
            Text(
                text = if (open) "⌃" else "⌄",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        ContextMenu(
            expanded = open,
            onDismiss = { open = false },
            items = items,
        )
    }
}

/**
 * A boolean config option: the label, then a small switch — Zed's `Switch`
 * with `SwitchLabelPosition::Start` (config_options.rs:579-587). Geometry
 * and colours follow ui/src/components/toggle.rs:298-311 and 516-541: a
 * rounded track, `element.disabled` when off and `info` at 40% when on, with
 * a `text`-coloured thumb at half opacity while off.
 */
@Composable
private fun SwitchChip(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val track = if (checked) {
        theme.color("info", theme.color("text.accent")).copy(alpha = 0.4f)
    } else {
        theme.color("element.disabled", theme.color("element.background"))
    }
    val outline = if (checked) {
        theme.color("text.accent").copy(alpha = 0.2f)
    } else {
        theme.color("border")
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = label,
                onClick = onToggle,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> theme.color("text.disabled", theme.color("text.muted"))
                hovered -> theme.color("text")
                else -> theme.color("text.muted")
            },
            maxLines = 1,
        )
        // **Drawn, not composed, and both halves of that are the bug fix.**
        // A track Box with the thumb as an aligned child rendered as a plain
        // blue pill on the device: the thumb never appeared, and the track
        // came out 24dp wide against the 30dp it asked for, because a `size`
        // only *proposes* — the row it sits in clamped it and the inset child
        // was squeezed out of existence. `requiredSize` refuses the clamp (the
        // row scrolls, so there is somewhere to overflow to), and one canvas
        // puts the thumb at a position derived from the size actually
        // measured, so it cannot go missing however tight the row becomes.
        val thumbColor = theme.color("text")
        val thumbAlpha = when {
            !enabled -> 0.2f
            checked -> 1f
            else -> 0.5f
        }
        Canvas(modifier = Modifier.requiredSize(SwitchWidth, SwitchHeight)) {
            val radius = size.height / 2f
            drawRoundRect(color = track, cornerRadius = CornerRadius(radius, radius))
            drawRoundRect(
                color = outline,
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1.dp.toPx()),
            )
            val inset = SwitchInset.toPx()
            val thumbRadius = (size.height - inset * 2f) / 2f
            drawCircle(
                color = thumbColor.copy(alpha = thumbAlpha),
                radius = thumbRadius,
                center = Offset(
                    x = if (checked) {
                        size.width - inset - thumbRadius
                    } else {
                        inset + thumbRadius
                    },
                    y = size.height / 2f,
                ),
            )
        }
    }
}

/** The transcript, its plan, and whatever the session needs said about it. */
@Composable
private fun Conversation(
    state: AgentSessionState,
    conversation: AgentConversation,
    agent: AgentDefinition?,
    /**
     * Bumped by the strip's "Show" to jump to the first thing waiting on the
     * user — a permission prompt that scrolled away stalls the whole turn
     * with nothing on screen to explain it.
     */
    scrollToPending: Int,
    /** How many things waiting on the user are currently off screen. */
    onPendingOffscreen: (Int) -> Unit,
    onOpenPath: (String) -> Unit,
    onRespond: (toolCall: String, option: String) -> Unit,
    /** "Restore checkpoint" on the user row at that index. */
    onRestoreCheckpoint: (Int) -> Unit,
    onAuthenticate: (AgentAuthMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    // **Hoisted out of the cards.** `remember` inside a LazyColumn item dies
    // with the item, so a card the user opened forgot it the moment it
    // scrolled off — and a running command showed one grey line for its whole
    // life. Zed keeps the same thing outside its list
    // (entry_view_state.rs:76, a HashSet<ToolCallId>).
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Follow the tail while the agent is talking — but only while the reader
    // is *at* the tail. Scrolling on every version bump (eight a second during
    // a turn) undid any scroll-back within 120 ms, so the transcript could not
    // be read while it was being written.
    val following by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= list.layoutInfo.totalItemsCount - 1
        }
    }
    LaunchedEffect(conversation.entries.size, state.version, following) {
        if (conversation.entries.isEmpty() || !following) return@LaunchedEffect
        runCatching {
            val last = conversation.entries.lastIndex
            list.revealItem(last)
            // `animateScrollToItem` puts the item's *top* at the viewport's
            // top, so a reply taller than the screen would scroll away from
            // the words being written. Go the rest of the way to its end.
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == last }
            val overflow = item?.let {
                it.size - (info.viewportEndOffset - info.viewportStartOffset)
            } ?: 0
            if (overflow > 0) list.revealBy(overflow.toFloat())
        }
    }

    // What the agent is blocked on, and whether any of it is on screen. The
    // elicitation items sit after the entries in the same list, which is what
    // makes the index arithmetic below work.
    val pendingIndices = remember(conversation.entries, state.elicitations) {
        val waiting = conversation.entries.mapIndexedNotNull { index, entry ->
            index.takeIf {
                entry is AgentEntry.ToolCall &&
                    entry.status == ToolCallStatus.WaitingForConfirmation
            }
        }
        val questions = state.elicitations.mapIndexedNotNull { index, question ->
            (conversation.entries.size + index).takeIf { !question.accepted }
        }
        waiting + questions
    }
    val offscreen by remember {
        derivedStateOf {
            val visible = list.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
            pendingIndices.count { it !in visible }
        }
    }
    LaunchedEffect(offscreen) { onPendingOffscreen(offscreen) }

    // Jump to whatever is waiting. Guarded on a non-zero token so it does not
    // fire on first composition.
    LaunchedEffect(scrollToPending) {
        if (scrollToPending == 0) return@LaunchedEffect
        val target = pendingIndices.firstOrNull() ?: conversation.entries.lastIndex
        if (target >= 0) runCatching { list.revealItem(target) }
    }

    LazyColumn(
        state = list,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The plan is **not** here: it lives in the pinned strip above the
        // composer. It used to be item 0, which also meant every entry index
        // was off by one whenever a plan existed — so the tail-follow above
        // scrolled to the wrong row and measured the wrong overflow.
        //
        // Keyed by position rather than by content: two identical messages are
        // perfectly ordinary, and a duplicate key throws inside LazyLayout.
        items(count = conversation.entries.size, key = { "entry:$it" }) { index ->
            val entry = conversation.entries[index]
            // A row after a restored checkpoint is history the project no
            // longer reflects: still readable, drawn as such.
            Box(modifier = Modifier.alpha(if (entry.reverted) 0.5f else 1f)) {
            when (entry) {
                is AgentEntry.User -> UserRow(entry, onRestore = { onRestoreCheckpoint(index) })

                // An assistant turn with nothing in it is not a row. It used
                // to be a padded Column with 8dp of list spacing around it,
                // which on a phone is a visible hole. Zed returns Empty for
                // the same case (thread_view.rs:6374-6376).
                is AgentEntry.Assistant ->
                    if (entry.spoken.isNotBlank() || entry.thoughts.isNotBlank()) {
                        AssistantRow(
                            entry = entry,
                            // Zed's `Auto` thinking display: the block opens
                            // by itself while the thought is streaming and
                            // closes when it stops (entry_view_state.rs:
                            // 116-148). The live thought is the last entry of
                            // a running turn that has not started speaking —
                            // once there is an answer, the thinking is over.
                            thinkingNow = state.isBusy &&
                                index == conversation.entries.lastIndex &&
                                entry.spoken.isBlank(),
                            onOpenPath = onOpenPath,
                        )
                    }

                // Nor is a cancelled call that never produced anything.
                // Pressing Stop on a turn with five parallel reads left five
                // empty grey slabs, which is most of a phone screen
                // (thread_view.rs:6394-6407).
                is AgentEntry.ToolCall ->
                    if (entry.status != ToolCallStatus.Canceled ||
                        entry.content.isNotEmpty() ||
                        entry.options.isNotEmpty()
                    ) {
                        ToolCallCard(
                            call = entry,
                            expanded = expanded,
                            onOpenPath = onOpenPath,
                            onRespond = onRespond,
                        )
                    }

                is AgentEntry.CompletedPlan -> CompletedPlanCard(entry)

                // A kind this build predates. One quiet line, so the rest of
                // the conversation stays readable and honest about the gap.
                AgentEntry.Unsupported -> Notice(
                    "This version of Seeker IDE cannot show that message.",
                )
            }
            }
        }
        // Between Send and the first token the transcript was indistinguishable
        // from idle. Zed draws a generating indicator for exactly this gap
        // (thread_view.rs:7295-7399).
        if (state.phase == AgentPhase.Running) {
            item(key = "working") { WorkingRow() }
        }
        // The questions go last, under everything, because a question is the
        // next thing to do: the agent is blocked on it and nothing further
        // will arrive until it is answered.
        items(
            count = state.elicitations.size,
            key = { "elicit:${state.elicitations[it].id}" },
        ) { index ->
            ElicitationCard(state.elicitations[index])
        }
        if (state.phase == AgentPhase.Unavailable || state.needsAuth) {
            item(key = "trouble") {
                Trouble(state, agent, onAuthenticate)
            }
        }
    }
}

/**
 * The pinned strip between the transcript and the composer.
 *
 * Zed calls it the activity bar (thread_view.rs:3086-3197): the few things
 * that must not scroll away because they are what to do next — the plan, a
 * turn that failed, a question waiting to be answered. Everything in it used
 * to live in the transcript, which meant it scrolled off exactly when it
 * mattered, and the plan being item 0 also put every entry index out by one.
 *
 * Empty means no strip at all: a pinned bar with nothing in it is a bar that
 * has taken height off a phone screen for nothing.
 */
@Composable
private fun ActivityStrip(
    state: AgentSessionState,
    pendingCount: Int,
    onScrollToPending: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val notices = listOfNotNull(state.notice, AgentSessions.lastRefusal)
    val stopNotice = stopReasonNotice(state)
    val showError = state.error != null
    if (state.plan.isEmpty() && notices.isEmpty() && stopNotice == null &&
        !showError && pendingCount == 0 && state.queue.isEmpty()
    ) {
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("element.background", Color.Transparent)),
    ) {
        HorizontalDivider(color = theme.color("border"))
        if (pendingCount > 0) {
            StripRow(
                label = if (pendingCount == 1) {
                    "Waiting for you"
                } else {
                    "Waiting for you — $pendingCount"
                },
                accent = true,
                action = "Show",
                onAction = onScrollToPending,
            )
        }
        if (showError) {
            ErrorRow(state)
        }
        stopNotice?.let { NoticeRow(it, onDismiss = null) }
        for (notice in notices) {
            NoticeRow(notice, onDismiss = { AgentSessions.clearNotice() })
        }
        // Prompts waiting their turn. They are not in the transcript because
        // they have not been sent — this is where they live until they are,
        // and where they can be taken back or pushed to the front.
        for (queued in state.queue) {
            QueuedRow(queued, sendNow = !state.isBusy)
        }
        if (state.plan.isNotEmpty()) {
            PlanStrip(state)
        }
    }
}

/** One prompt in the queue, with the two things to do about it. */
@Composable
private fun QueuedRow(queued: AgentQueuedPrompt, sendNow: Boolean) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowStartPadding, vertical = 6.dp),
    ) {
        Text(
            text = "queued",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
        )
        Text(
            text = queued.text,
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text"),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Only when a turn is not running: "send now" while the agent is
        // working would mean interrupting it, which is a different button.
        if (sendNow) BarAction("Send now") { AgentSessions.sendQueuedNow() }
        BarAction("✕") { AgentSessions.removeQueued(queued.id) }
    }
}

/** One line in the strip: a label, and one thing to do about it. */
@Composable
private fun StripRow(
    label: String,
    accent: Boolean,
    action: String?,
    onAction: (() -> Unit)?,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowStartPadding, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) {
                theme.color("text.accent", MaterialTheme.colorScheme.primary)
            } else {
                theme.color("text")
            },
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) BarAction(action, onClick = onAction)
    }
}

/**
 * A turn that failed, said properly.
 *
 * Independent of `phase`, which is the bug this replaces: `Trouble` was gated
 * on `Unavailable || needsAuth`, so a *turn* that failed on a session that is
 * otherwise fine — the common case, a rate limit — left `error` set and drawn
 * by nobody. A dead turn looked exactly like an idle one.
 */
@Composable
private fun ErrorRow(state: AgentSessionState) {
    val theme = LocalZedTheme.current
    val kind = state.errorKind
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowStartPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = kind?.heading ?: "Something went wrong",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = theme.color("error", MaterialTheme.colorScheme.error),
        )
        state.error?.let { MarkdownText(it) }
        kind?.advice?.let { advice ->
            Text(
                text = advice,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.canRetry) {
                PanelButton("Try again", isPrimary = true) { AgentSessions.retryLastPrompt() }
            }
            if (state.errorKind == AgentErrorKind.ContextWindow ||
                state.errorKind == AgentErrorKind.Transport
            ) {
                PanelButton("New thread") { AgentSessions.newThreadHere() }
            }
        }
    }
}

/** One dismissible line saying why something did not happen. */
@Composable
private fun NoticeRow(text: String, onDismiss: (() -> Unit)?) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowStartPadding, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            modifier = Modifier.weight(1f),
        )
        if (onDismiss != null) BarAction("✕", onClick = onDismiss)
    }
}

/**
 * What the last turn's stop reason means, when it means anything.
 *
 * `stopReason` was parsed and read by nothing, so a refused prompt and a
 * reply cut off at the token limit both ended in silence.
 */
private fun stopReasonNotice(state: AgentSessionState): String? = when {
    state.isBusy -> null
    state.stopReason == "refusal" ->
        "The agent declined that prompt, and removed it from the conversation."
    state.stopReason == "max_tokens" ->
        "The reply hit the model's length limit. Ask it to carry on."
    state.stopReason == "max_turn_requests" ->
        "The agent used its whole turn. Ask it to carry on."
    else -> null
}

/** The live plan, in the strip. Collapsed by default: it is a reference, not a feed. */
@Composable
private fun PlanStrip(state: AgentSessionState) {
    val theme = LocalZedTheme.current
    var expanded by remember { mutableStateOf(false) }
    val done = state.plan.count { it.status == "completed" }
    val current = state.plan.firstOrNull { it.status == "in_progress" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RowStartPadding, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = "Plan") { expanded = !expanded },
        ) {
            Text(
                text = "PLAN  $done/${state.plan.size}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = theme.color("text.muted"),
            )
            // Collapsed, the one line worth having is what it is doing *now*.
            Text(
                text = if (expanded) "" else current?.content.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "hide" else "show",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        if (expanded) {
            // Bounded and scrolling: a fifteen-step plan must not take the
            // composer off the screen (Zed caps it the same way,
            // thread_view.rs:3796-3824).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (entry in state.plan) PlanRow(entry)
            }
        }
    }
}

/** A plan the agent finished, filed in the transcript where its turn was. */
@Composable
private fun CompletedPlanCard(entry: AgentEntry.CompletedPlan) {
    val theme = LocalZedTheme.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("element.background", Color.Transparent))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = "Finished plan") { expanded = !expanded },
        ) {
            Text(
                text = "PLAN  done (${entry.entries.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = theme.color("text.muted"),
            )
            Box(modifier = Modifier.weight(1f))
            Text(
                text = if (expanded) "hide" else "show",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        if (expanded) for (row in entry.entries) PlanRow(row)
    }
}

/** One step of a plan, live or finished. */
@Composable
private fun PlanRow(entry: AgentPlanEntry) {
    val theme = LocalZedTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = when (entry.status) {
                "completed" -> "✓"
                "in_progress" -> "▸"
                else -> "·"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (entry.status) {
                "completed" -> theme.color("created", theme.color("text.muted"))
                "in_progress" -> theme.color("text.accent")
                else -> theme.color("text.muted")
            },
        )
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.status == "completed") {
                theme.color("text.muted")
            } else {
                theme.color("text")
            },
        )
    }
}

/**
 * The three dots between Send and the first token.
 *
 * Not decoration: the transcript was otherwise indistinguishable from idle
 * for however long the model took to start, and a phone gives no other clue
 * that anything is happening.
 */
@Composable
private fun WorkingRow() {
    val theme = LocalZedTheme.current
    val cycle by rememberInfiniteTransition(label = "working").animateValue(
        initialValue = 0,
        targetValue = 3,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
        ),
        label = "dots",
    )
    // Zed's "pulsating labels … in a static state" (default.json:281-282):
    // the row still says the agent is working, it just stops counting.
    val step = if (LocalReduceMotion.current) 2 else cycle
    Text(
        text = "·".repeat(step + 1),
        style = MaterialTheme.typography.bodyMedium,
        color = theme.color("text.muted"),
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * The user's own message: the editor background inside a bordered, rounded
 * block, which is exactly how Zed draws it — `rounded_md`, `bg(editor
 * _background)`, `border_1` in `border` (thread_view.rs:6207-6218). It used to
 * be a fill with no border, which on a panel whose own background is close to
 * `element.background` left the prompt barely distinguishable from the reply
 * under it.
 */
@Composable
private fun UserRow(entry: AgentEntry.User, onRestore: () -> Unit) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("editor.background", Color.Transparent))
            .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MarkdownText(entry.markdown)
        // Zed's "Restore checkpoint" under a message whose turn changed
        // files (thread_view.rs:2965 and the message header): the files go
        // back to what they held before it, through the panel's own
        // record of the agent's writes.
        if (entry.checkpoint || entry.reverted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entry.reverted) {
                    Text(
                        text = "reverted",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted"),
                    )
                }
                if (entry.checkpoint) {
                    Text(
                        text = "Restore checkpoint",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClickLabel = "Restore checkpoint", onClick = onRestore)
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The reply, with its reasoning above it in Zed's thinking block: the think
 * icon, the word *Thinking*, a chevron, and — when open — the reasoning behind
 * a left rule, indented (thread_view.rs:7462-7516). It used to be the words
 * `thinking…` in the muted label size, which said neither that it could be
 * opened nor that anything was in it.
 */
@Composable
private fun AssistantRow(
    entry: AgentEntry.Assistant,
    /** The thought is still arriving, so the block shows itself. */
    thinkingNow: Boolean,
    onOpenPath: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
    // Null until the reader has an opinion, and their opinion then outranks
    // the streaming state for good — Zed keeps the same two facts apart
    // (`expanded_thinking_blocks` and `user_toggled_thinking_blocks`),
    // because a block the reader closed must not spring open on the next
    // chunk of the same thought.
    var openedByHand by remember { mutableStateOf<Boolean?>(null) }
    val thoughts = entry.thoughts
    val open = openedByHand ?: thinkingNow
    val ruleColor = theme.color("border.variant", theme.color("border"))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (thoughts.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = "Thinking") { openedByHand = !open },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_agent_think),
                    contentDescription = null,
                    tint = theme.color("text.muted"),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = if (open) "⌃" else "⌄",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                )
            }
            if (open) {
                // The left rule is the whole reason this reads as reasoning
                // rather than as more answer: `ml_1p5 pl_3p5 border_l_1` in
                // the tool card's border colour (thread_view.rs:7507-7512).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp)
                        .drawBehind {
                            val width = 1.dp.toPx()
                            drawRect(
                                color = ruleColor,
                                size = androidx.compose.ui.geometry.Size(width, size.height),
                            )
                        }
                        .padding(start = 14.dp),
                ) {
                    MarkdownText(thoughts)
                }
            }
        }
        if (entry.spoken.isNotEmpty()) {
            MarkdownText(entry.spoken, onLink = onOpenPath)
        }
    }
}

/**
 * One tool call: what it is doing, what it produced, and — when it is waiting
 * — the decision, right beside the diff it is asking about.
 */
@Composable
private fun ToolCallCard(
    call: AgentEntry.ToolCall,
    /**
     * Which cards are open, held by the transcript.
     *
     * Not `remember` inside the item: a LazyColumn destroys an item's state
     * when it scrolls off, so a card the user opened forgot it, and a running
     * command was one grey line for its whole life. Zed keeps the same set
     * outside its list (entry_view_state.rs:76).
     */
    expanded: MutableMap<String, Boolean>,
    onOpenPath: (String) -> Unit,
    onRespond: (toolCall: String, option: String) -> Unit,
) {
    val theme = LocalZedTheme.current
    val waiting = call.status == ToolCallStatus.WaitingForConfirmation
    val hasDiff = call.content.any { it is to.eyed.seeker.code.core.ToolContent.Diff }
    val hasTerminal = call.content.any { it is to.eyed.seeker.code.core.ToolContent.Terminal }

    // Seeded rather than closed. Zed opens edit and terminal cards by default
    // (entry_view_state.rs:308-334) for the obvious reason: a diff you are
    // being asked to approve and a command that is producing output are the
    // two things you were going to open anyway. The user's own toggle wins
    // afterwards, which is what the map remembers.
    val open = expanded[call.id] ?: (hasDiff || hasTerminal)
    val showBody = open || waiting

    // Zed's `use_card_layout` (thread_view.rs:8203): only calls worth
    // stopping at get the box. Ten reads and one pending edit used to be
    // eleven identical grey slabs.
    val isCard = waiting || call.kind == ToolKind.Edit || hasDiff || call.kind == ToolKind.Execute
    val failed = call.status == ToolCallStatus.Failed ||
        call.status == ToolCallStatus.Rejected ||
        call.status == ToolCallStatus.Canceled

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .then(
                if (isCard) {
                    Modifier
                        .clip(RoundedCornerShape(FieldRadius))
                        .background(theme.color("element.background", Color.Transparent))
                        .border(
                            width = 1.dp,
                            color = if (failed) {
                                theme.color("error", MaterialTheme.colorScheme.error)
                            } else {
                                theme.color("border", Color.Transparent)
                            },
                            shape = RoundedCornerShape(FieldRadius),
                        )
                        .padding(8.dp)
                } else {
                    Modifier.padding(vertical = 2.dp)
                }
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = call.title) { expanded[call.id] = !open },
        ) {
            // A chevron, so a card that *can* be opened says so. Without one
            // the only way to find out was to tap every row.
            Text(
                text = if (call.content.isEmpty() && call.rawInput == null) {
                    " "
                } else if (showBody) {
                    "⌄"
                } else {
                    "›"
                },
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
            Text(
                text = glyph(call.kind),
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
            )
            Text(
                text = call.title,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(call.status)
        }

        if (showBody) {
            // Not a card: hang the body off a rail rather than a box, as Zed
            // does (thread_view.rs:10408). A Row with a one-pixel Box is the
            // whole trick — Compose has no left-only border.
            Row(modifier = Modifier.fillMaxWidth()) {
                if (!isCard) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(theme.color("border", Color.Transparent)),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (isCard) 0.dp else 13.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (content in call.content) {
                        when (content) {
                            is to.eyed.seeker.code.core.ToolContent.Markdown ->
                                MarkdownText(content.markdown)

                            is to.eyed.seeker.code.core.ToolContent.Diff ->
                                DiffCard(content.file, waiting, onOpenPath)

                            is to.eyed.seeker.code.core.ToolContent.Terminal ->
                                TerminalCard(content.terminalId, content.sealed)
                        }
                    }
                    // What the agent is actually asking to run. A title like
                    // "Edit notes.md" does not say what the edit is, and a
                    // permission prompt asks the user to approve the *call*.
                    call.rawInput?.let {
                        RawInputBlock(it, startOpen = waiting && call.content.isEmpty())
                    }
                }
            }
        }

        if (waiting && call.options.isNotEmpty()) {
            PermissionRow(call.options) { option -> onRespond(call.id, option.id) }
        }
    }
}

/** The tool call's arguments, folded away — it is JSON, and mostly not needed. */
@Composable
private fun RawInputBlock(rawInput: String, startOpen: Boolean) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    var open by remember(rawInput) { mutableStateOf(startOpen) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (open) "hide arguments" else "show arguments",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = "Arguments") { open = !open },
        )
        if (open) {
            Text(
                text = rawInput,
                style = TextStyle(
                    fontFamily = BufferFontFamily,
                    fontSize = (settings.bufferFontSize * 0.85f).sp,
                    lineHeight = (settings.bufferFontSize * 1.3f).sp,
                ),
                color = theme.color("text.muted"),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** ACP's tool kinds, as the one glyph each that a 32px row can hold. */
private fun glyph(kind: ToolKind): String = when (kind) {
    ToolKind.Read -> "◇"
    ToolKind.Edit -> "✎"
    ToolKind.Delete -> "✕"
    ToolKind.Move -> "→"
    ToolKind.Search -> "⌕"
    ToolKind.Execute -> "▸"
    ToolKind.Think -> "◌"
    ToolKind.Fetch -> "↓"
    ToolKind.SwitchMode -> "⇄"
    ToolKind.Other -> "•"
}

@Composable
private fun StatusChip(status: ToolCallStatus) {
    val theme = LocalZedTheme.current
    val (label, color) = when (status) {
        ToolCallStatus.Pending -> "pending" to theme.color("text.muted")
        ToolCallStatus.WaitingForConfirmation -> "asks" to theme.color("text.accent")
        ToolCallStatus.InProgress -> "running" to theme.color("text.muted")
        ToolCallStatus.Completed -> "done" to theme.color("created", theme.color("text.muted"))
        ToolCallStatus.Failed -> "failed" to theme.color("error", MaterialTheme.colorScheme.error)
        ToolCallStatus.Rejected -> "denied" to theme.color("text.muted")
        ToolCallStatus.Canceled -> "cancelled" to theme.color("text.muted")
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/**
 * A question the agent is waiting on — ACP's elicitation, which is how every
 * ask that is not a permission arrives.
 *
 * Two shapes, and they end differently. A **form** is a set of fields; Accept
 * sends them and the card goes. A **url** is "sign in over there and come
 * back"; Accept answers the agent immediately, and the card stays — greyed,
 * saying it is waiting — until the agent confirms it saw the sign-in with
 * `elicitation/complete`. Zed keeps the same distinction
 * (acp_thread.rs:515-527), and it is not cosmetic: the user has said they
 * did it, but only the agent knows whether it worked.
 *
 * Touch, keyboard and mouse together, as every control here must be: fields
 * take the keyboard with `Tab` moving between them the way any Compose form
 * does, every row is a tap target, and the buttons carry the hand cursor.
 */
@Composable
private fun ElicitationCard(question: AgentElicitation) {
    val theme = LocalZedTheme.current
    val scope = rememberCoroutineScope()
    // Keyed by the question: a second question arriving must not inherit the
    // first one's half-typed answers.
    val values = remember(question.id) {
        mutableStateMapOf<String, Any>().apply {
            question.fields.forEach { field -> put(field.key, ElicitationAnswer.initialValue(field)) }
        }
    }
    var sending by remember(question.id) { mutableStateOf(false) }
    // Empty until Send is pressed. Showing every field's complaint before the
    // user has touched anything is scolding, not helping — Zed validates on
    // submit too (elicitation.rs:1985).
    var errors by remember(question.id) { mutableStateOf(emptyMap<String, String>()) }
    val missing = ElicitationAnswer.missing(question.fields, values)

    fun answer(json: String) {
        if (sending) return
        sending = true
        scope.launch {
            withContext(Dispatchers.Default) {
                CoreBridge.acpRespondElicitation(question.id, json)
            }
            // Not reset on success: the card is either gone on the next poll
            // or — a URL question — waiting on the agent, and in both cases
            // pressing again would be a second answer to one question.
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("element.background", Color.Transparent))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        question.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text"),
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = question.message,
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text"),
        )
        question.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }

        when {
            question.isUrl -> {
                Text(
                    text = question.url.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (question.accepted) {
                    Text(
                        text = "Waiting for the agent to confirm…",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted"),
                    )
                }
            }

            question.isForm -> {
                for (field in question.fields) {
                    ElicitationFieldRow(
                        field = field,
                        value = values[field.key],
                        error = errors[field.key],
                    ) {
                        values[field.key] = it
                        // Clear the complaint the moment the field changes:
                        // a red box that stays red while you fix it is worse
                        // than no box at all.
                        if (errors.containsKey(field.key)) errors = errors - field.key
                    }
                }
            }

            // A mode the engine accepted but this build cannot draw. It should
            // not happen — the engine refuses unknown modes at the wire — but
            // saying so beats a card with nothing in it.
            else -> Notice("The agent asked something this version cannot show.")
        }

        if (!question.accepted) {
            if (missing.isNotEmpty() && errors.isEmpty()) {
                Text(
                    text = "Still needed: ${missing.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                )
            }
            // **Send is always here.** It used to appear only once every
            // required field was filled, which meant the button moved as you
            // typed — under a soft keyboard, on a phone — and a form you
            // could not complete was a form with no way to find out why.
            // Pressing it is how you learn what is wrong.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelButton("Decline", enabled = !sending) {
                    answer(ElicitationAnswer.decline())
                }
                PanelButton(
                    label = if (question.isUrl) "I've done that" else "Send",
                    isPrimary = true,
                    enabled = !sending,
                ) {
                    val found = ElicitationAnswer.validate(question.fields, values)
                    errors = found
                    if (found.isEmpty()) {
                        answer(ElicitationAnswer.accept(question.fields, values))
                    }
                }
            }
        }
    }
}

/** One field of a form question, drawn by what its type actually is. */
@Composable
private fun ElicitationFieldRow(
    field: ElicitationField,
    value: Any?,
    /** What is wrong with this field's answer, once Send has been pressed. */
    error: String?,
    onValue: (Any) -> Unit,
) {
    val theme = LocalZedTheme.current
    val errorColor = theme.color("error", MaterialTheme.colorScheme.error)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (field.type != "boolean") {
            Text(
                text = if (field.required) "${field.label} *" else field.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (error != null) errorColor else theme.color("text.muted"),
            )
        }
        field.description?.takeIf { field.type != "boolean" }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
            )
        }
        when {
            field.isUnsupported -> Notice("“${field.label}” is a kind of field this version cannot show.")

            field.type == "boolean" -> {
                val checked = value as? Boolean ?: false
                CheckRow(field.label, checked) { onValue(!checked) }
                field.description?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted"),
                    )
                }
            }

            field.type == "array" -> {
                val chosen = (value as? List<*>)?.filterIsInstance<String>().orEmpty()
                for (option in field.options) {
                    val on = option.value in chosen
                    CheckRow(option.title, on) {
                        onValue(if (on) chosen - option.value else chosen + option.value)
                    }
                }
                if (field.options.isEmpty()) {
                    Notice("The agent offered no choices for this.")
                }
            }

            // A string with a fixed set of answers is a picker, not a text
            // box: the agent will only accept one of these, so typing is a
            // way to get it wrong.
            field.options.isNotEmpty() -> {
                val current = value as? String
                val chosen = field.options.firstOrNull { it.value == current }
                SelectorChip(
                    label = chosen?.title ?: "Choose…",
                    items = field.options.map { option ->
                        ContextMenuItem(
                            // A tick, because a menu with no state shown
                            // gives no way to see what is already picked.
                            label = if (option.value == current) "✓ ${option.title}" else option.title,
                            onClick = { onValue(option.value) },
                        )
                    },
                )
            }

            else -> FormLine(
                value = value as? String ?: "",
                field = field,
                isError = error != null,
                onValue = onValue,
            )
        }
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = errorColor,
            )
        }
    }
}

/** A tappable check row: touch, keyboard focus and a hand cursor, as always. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = label, onClick = onToggle)
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = if (checked) "☑" else "☐",
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) {
                theme.color("text.accent", MaterialTheme.colorScheme.primary)
            } else {
                theme.color("text.muted")
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("text"),
        )
    }
}

/**
 * One line of text in a form question — Zed's input box: 32px minimum, 6px
 * corners, one-pixel border, the editor's own background
 * (external_agents_page.rs:558-576), which is the box the settings screen's
 * fields already use.
 */
@Composable
private fun FormLine(
    value: String,
    field: ElicitationField,
    isError: Boolean,
    onValue: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("editor.background"))
            .border(
                width = 1.dp,
                color = if (isError) {
                    theme.color("error", MaterialTheme.colorScheme.error)
                } else {
                    theme.color("border")
                },
                shape = RoundedCornerShape(FieldRadius),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            // The keyboard has to be able to type the answer. `Number` has no
            // minus sign and no decimal point, so a `number` field with a
            // fractional or negative answer — and an `integer` one that
            // accepts negatives — was literally untypeable on a phone.
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    field.type == "number" -> KeyboardType.Decimal
                    field.type == "integer" -> KeyboardType.Number
                    field.format == "email" -> KeyboardType.Email
                    field.format == "uri" -> KeyboardType.Uri
                    else -> KeyboardType.Text
                },
            ),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A command the agent asked us to run, and what it printed.
 *
 * This is what `terminal: true` in the client capabilities buys the user: an
 * agent that would otherwise shell out invisibly inside its own process
 * instead runs here, where the command line, its output and its exit status
 * are all on screen. Zed draws the same card off the same `terminal` content
 * block (agent_ui/src/entry_view_state.rs:311).
 *
 * It polls itself rather than riding the transcript's delta — see
 * [rememberAgentTerminal] for why — and stops the moment the command ends.
 */
@Composable
private fun TerminalCard(terminalId: String, sealed: AgentTerminalState?) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    // The live poll while the command is running; the copy sealed onto the
    // entry once the agent released it. The engine keeps only the last few
    // released terminals resident, so an agent that ran seventeen commands
    // had silently emptied its first card — the transcript is the record and
    // has to be able to stand on its own.
    val live = rememberAgentTerminal(terminalId, enabled = sealed == null)
    val terminal = if (live.revision > 0L) live else sealed ?: live

    // Read outside the `remember`: the buffer family is a composition local
    // now that `buffer_font_family` can name a font, so it is also a key.
    val bufferFont = BufferFontFamily
    val code = remember(settings.bufferFontSize, bufferFont) {
        TextStyle(
            fontFamily = bufferFont,
            fontSize = (settings.bufferFontSize * 0.9f).sp,
            lineHeight = (settings.bufferFontSize * 1.4f).sp,
        )
    }
    var showAll by remember(terminalId) { mutableStateOf(false) }
    // The tail, because the end of a command's output is where the answer is.
    val (total, shown) = remember(terminal.output, showAll) {
        // Stripped here, in the display path only: what the agent reads back
        // over `terminal/output` has to stay byte-faithful, because the agent
        // is parsing it.
        val all = stripAnsi(terminal.output).trimEnd('\n').split('\n')
        all.size to if (showAll) all else all.takeLast(MaxTerminalLines)
    }
    val pane = rememberScrollState()
    // Pinned to the bottom while it is still printing — a build log that
    // scrolls itself is how you watch a build.
    LaunchedEffect(terminal.revision, terminal.running) {
        if (terminal.running) runCatching { pane.scrollTo(pane.maxValue) }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The command, wrapping. It used to be `maxLines = 1` ellipsised,
        // which on a dock cut every real command at about four words.
        Text(
            text = if (terminal.label.isEmpty()) "$ …" else "$ ${terminal.label}",
            style = code,
            color = theme.color("text"),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Project-relative, and absent for the project root itself —
            // which is where most commands run, and a line saying so every
            // time would be noise. Start-ellipsised when it is long, because
            // the interesting end of a path is the tail (Zed truncates the
            // same way, terminal_tool_header.rs:145-153).
            Text(
                text = when {
                    terminal.cwd.isEmpty() -> ""
                    terminal.cwd.length > 34 -> "…" + terminal.cwd.takeLast(33)
                    else -> terminal.cwd
                },
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            terminal.elapsedLabel?.let { elapsed ->
                Text(
                    text = elapsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                )
            }
            Text(
                text = terminalOutcome(terminal),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    terminal.running -> theme.color("text.muted")
                    terminal.exitCode == 0 -> theme.color("created", theme.color("text.muted"))
                    else -> theme.color("error", MaterialTheme.colorScheme.error)
                },
                maxLines = 1,
            )
        }
        when {
            terminal.revision == 0L && terminal.output.isEmpty() ->
                Notice("That command is over; its output is no longer kept.")

            terminal.output.isEmpty() ->
                Notice(if (terminal.running) "Running…" else "It printed nothing.")

            else -> {
                terminal.droppedSentence?.let { sentence ->
                    Text(
                        text = sentence,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted"),
                    )
                }
                if (total > shown.size) {
                    Text(
                        text = "Showing the last ${shown.size} of $total lines — show all",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClickLabel = "Show every line") { showAll = true },
                    )
                }
                // A bounded pane that scrolls **vertically**. The old card
                // printed a fixed 40 lines with no way to reach the rest, so
                // the compiler error at the top of a 200-line log existed in
                // the engine and could not be looked at. Lines soft-wrap
                // rather than scrolling sideways: a horizontal drag inside a
                // vertical transcript fights the transcript, and Zed wraps
                // for the same reason (thread_view.rs:7777-7782).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(pane),
                ) {
                    for (line in shown) {
                        Text(
                            text = line,
                            style = code,
                            color = theme.color("text"),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** The one phrase that says how a command is doing, or how it went. */
private fun terminalOutcome(terminal: AgentTerminalState): String = when {
    terminal.running -> "running"
    terminal.signal != null -> "killed (${terminal.signal})"
    terminal.exitCode == 0 -> "done"
    terminal.exitCode != null -> "exit ${terminal.exitCode}"
    else -> "ended"
}

/**
 * The agent's proposed edit, drawn by the same rows a commit's diff is.
 *
 * Not [to.eyed.seeker.code.ui.git.DiffBody], which is a list of its own and
 * cannot nest inside the conversation's — but the rows, the numbers and the
 * created/deleted colours are its, so an agent's change reads exactly like a
 * git one. Unified only, as decided.
 */
@Composable
private fun DiffCard(file: FileDiff, whole: Boolean, onOpenPath: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val bufferFont = BufferFontFamily
    val code = remember(settings.bufferFontSize, bufferFont) {
        TextStyle(
            fontFamily = bufferFont,
            fontSize = settings.bufferFontSize.sp,
            lineHeight = (settings.bufferFontSize * 1.618034f).sp,
        )
    }
    val across = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val lines = remember(file) { file.hunks.flatMap { it.lines } }
    val contentWidth = remember(file, code) {
        val longest = lines.maxOfOrNull { it.text.length + 1 } ?: 0
        (longest * measurer.measure("M", code).size.width).coerceAtLeast(1)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = file.path) { onOpenPath(file.path) }
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = file.path,
                style = code.copy(fontSize = settings.bufferFontSize.sp * 0.9f),
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "+${file.added}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("created", theme.color("text.muted")),
            )
            Text(
                text = "−${file.removed}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("deleted", theme.color("text.muted")),
            )
        }
        when {
            file.isBinary -> Notice("Binary file — nothing to show line by line.")
            lines.isEmpty() -> Notice("Nothing to show.")
            else -> {
                // `whole` is set while the user is being *asked* about this
                // diff. Cutting it there is the one place the cut must never
                // happen: the card is force-open under an Allow button, and
                // approving an edit you were shown 200 of 400 lines of is
                // approving something you did not see. Zed force-expands
                // every hunk for the same reason (entry_view_state.rs:655).
                var showAll by remember(file) { mutableStateOf(false) }
                val limit = if (whole || showAll) lines.size else MaxDiffLines
                for (line in lines.take(limit)) {
                    DiffLineRow(line, code, across, contentWidth)
                }
                if (lines.size > limit) {
                    Text(
                        text = "Show all ${lines.size} lines",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClickLabel = "Show the whole diff") { showAll = true },
                    )
                }
            }
        }
    }
}

/**
 * The decision. Allow first, deny beside it, and nothing pre-selected — the
 * agent is asking, and the answer has to be the user's.
 */
@Composable
private fun PermissionRow(options: List<PermissionOption>, onChoose: (PermissionOption) -> Unit) {
    val theme = LocalZedTheme.current
    // A **column**, not a row. Agents offer up to five options ("Allow",
    // "Allow always for this session", "Allow all edits in this file",
    // "Reject", "Reject always") and on a 400dp dock a row loses the last of
    // them off the right edge — which is always a rejection.
    //
    // And in **wire order**: the old `sortedByDescending { isAllow }` threw
    // away the agent's own ordering, which is its editorial choice about
    // which answer it expects.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in options) {
            val allow = option.isAllow
            val always = option.kind.endsWith("always")
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        // The primary fill goes to the *once* grant only, so
                        // the permanent one is not the easiest thing to tap.
                        if (allow && !always) {
                            theme.color("element.background", Color.Transparent)
                        } else {
                            Color.Transparent
                        }
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(onClickLabel = option.name) { onChoose(option) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (allow) (if (always) "✓✓" else "✓") else "✕",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (allow) {
                        theme.color("created", theme.color("text.accent"))
                    } else {
                        theme.color("error", MaterialTheme.colorScheme.error)
                    },
                )
                Text(
                    text = option.name,
                    style = MaterialTheme.typography.labelMedium,
                    // Two lines, because "Allow all edits in this file" does
                    // not fit on one in a dock and the fixed-height button
                    // clipped it.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (allow && !always) {
                        theme.color("text.accent", MaterialTheme.colorScheme.primary)
                    } else {
                        theme.color("text")
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** What is wrong, said plainly — the fix is the user's, and they know how. */
@Composable
private fun Trouble(
    state: AgentSessionState,
    agent: AgentDefinition?,
    onAuthenticate: (AgentAuthMethod) -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = state.error ?: "The agent stopped.",
            style = MaterialTheme.typography.bodySmall,
            color = theme.color("error", MaterialTheme.colorScheme.error),
        )

        when {
            // Signing in is the agent's own business; we only run the method
            // it advertised.
            state.needsAuth -> {
                val methods = state.agent?.authMethods.orEmpty()
                if (methods.isEmpty()) {
                    Text(
                        text = "Sign in with the agent's own command in the terminal, " +
                            "then start a new session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("text.muted"),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (method in methods) {
                            PanelButton(method.name, isPrimary = true) {
                                onAuthenticate(method)
                            }
                        }
                    }
                    if (methods.any { it.isTerminal }) {
                        Text(
                            text = "A terminal sign-in opens a terminal running the " +
                                "agent's own command. Finish there, then start a new " +
                                "thread.",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color("text.muted"),
                        )
                    }
                }
            }

            // The guest could not find the program. Nothing is offered for
            // installation — the panel names the command and where it is
            // configured, and leaves the terminal to the developer.
            Agents.looksLikeMissingProgram(state.error) -> {
                Text(
                    text = "${Userland.backend.displayName} has no " +
                        "\"${agent?.argv?.firstOrNull() ?: "agent"}\". Install it in the " +
                        "terminal, or point this agent's entry in Settings at the right " +
                        "command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted"),
                )
            }
        }
    }
}

/**
 * A leading `/word` being typed — the composer's command token, completed
 * from the agent's advertised commands. Commands are prompt text on the
 * wire; `availableCommands` exists so the client can offer them, which is
 * exactly what Zed's completion provider does with it
 * (agent_ui/src/completion_provider.rs:1026).
 */
private val CommandToken = Regex("^/([\\w-]*)$")

/** A trailing `@path` being typed — the mention token, completed from files. */
private val MentionToken = Regex("(?:^|\\s)@([^\\s@]*)$")

/** Every complete `@path` token in a message — what actually gets sent. */
private val MentionTokens = Regex("(?:^|\\s)@([^\\s@]+)")

/**
 * The `@path` tokens actually standing in [message].
 *
 * Whole tokens, which is the whole point: a substring test matched a path
 * that is a *prefix* of another, so a mention the user had deleted and
 * replaced went out anyway and the engine embedded its contents.
 */
internal fun mentionTokensIn(message: String): Set<String> =
    MentionTokens.findAll(message).map { it.groupValues[1] }.toSet()

/**
 * The completion token being typed at the caret — `"/plan"`, `"@src/lib"` —
 * or null when there is none. Used both to open the strip and to decide when
 * an Esc dismissal has expired.
 */
private fun tokenIn(text: String): String? =
    CommandToken.matchEntire(text)?.groupValues?.get(1)?.let { "/" + it }
        ?: MentionToken.find(text)?.groupValues?.get(1)?.let { "@" + it }

/** The composer. */
@Composable
private fun Composer(
    /** For the bottom row's selectors, which live inside the composer now. */
    state: AgentSessionState,
    enabled: Boolean,
    isBusy: Boolean,
    focus: FocusRequester,
    project: ProjectSession,
    /** Whose draft this is; null while there is no thread to draft into. */
    thread: AgentThread?,
    /** The agent's slash commands, for the `/` popup. */
    commands: List<AgentCommand>,
    /** The open buffers and the editor's selection, for the `@` picker. */
    workspace: AgentWorkspaceAccess,
    /** Whether the box holds the keyboard — see [AgentPanel]'s parameter. */
    onFocusChanged: (Boolean) -> Unit,
    /** Send it, and put it back in the box if the engine would not take it. */
    onSend: (
        text: String,
        mentions: List<AgentMention>,
        images: List<PromptAttachment>,
        onRefused: () -> Unit,
    ) -> Unit,
    onStop: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    /** Why the last attachment did not happen — a picture, or a page. */
    var attachError by remember(thread) { mutableStateOf<String?>(null) }
    // A TextFieldValue, not a String: a completion replaces the text from
    // code, and the caret must land at the end of what was inserted — with a
    // bare String the IME keeps its old offset and the next keystroke lands
    // mid-word, which is exactly what happened on the device.
    //
    // Seeded from the thread's own draft and written back on every change,
    // keyed by thread: this composable is a branch of the panel's `when`, so
    // opening the threads view disposes it, and without the write-back an
    // unsent prompt died there. Per thread, so each conversation keeps its
    // own — Zed's behaviour too.
    var field by remember(thread) {
        val draft = thread?.draft.orEmpty()
        mutableStateOf(
            androidx.compose.ui.text.input.TextFieldValue(
                text = draft,
                selection = androidx.compose.ui.text.TextRange(draft.length),
            )
        )
    }
    val text = field.text
    /** What the `@` picker attached, candidates for the send. */
    val mentioned = thread?.draftMentions ?: remember { mutableStateListOf<AgentMention>() }
    /** Pictures attached to this draft, on the thread for the same reason. */
    val attached = thread?.draftImages ?: remember { mutableStateListOf() }
    /** Only an agent that reads images is offered a way to send one. */
    val canAttach = enabled && state.agent?.capabilities?.images == true
    /**
     * The *token* a popup was dismissed on — not the whole text, and cleared
     * the moment the token changes or the message is sent. Keyed on the whole
     * text it never cleared, so one Esc on a bare `@` killed the file strip
     * for every later bare `@` in that composition: the feature's own entry
     * point looked broken with no way back.
     */
    var dismissedToken by remember(thread) { mutableStateOf<String?>(null) }

    fun setField(value: androidx.compose.ui.text.input.TextFieldValue) {
        field = value
        thread?.draft = value.text
        // A dismissal lasts exactly as long as the token it was for.
        // Backspace past the `@` and the strip is armed again — otherwise
        // Esc on a bare `@` suppressed every later bare `@` too, and the
        // feature's own entry point looked broken.
        if (tokenIn(value.text) == null) dismissedToken = null
    }

    fun replaceText(newText: String) {
        setField(
            androidx.compose.ui.text.input.TextFieldValue(
                text = newText,
                selection = androidx.compose.ui.text.TextRange(newText.length),
            )
        )
    }

    val commandQuery = CommandToken.matchEntire(text)?.groupValues?.get(1)
        ?.takeIf { enabled && "/$it" != dismissedToken }
    val commandChoices = if (commandQuery == null) {
        emptyList()
    } else {
        commands.filter { it.name.startsWith(commandQuery, ignoreCase = true) }.take(4)
    }

    val mentionQuery = MentionToken.find(text)?.groupValues?.get(1)
        ?.takeIf { enabled && "@$it" != dismissedToken }
    // Which section of the picker is showing — Zed's context-picker mode.
    var section by remember(thread) { mutableStateOf(MentionSection.Files) }
    // A query that is plainly a URL is a Fetch, whatever section was up.
    LaunchedEffect(mentionQuery) {
        if (mentionQuery != null && FetchMention.looksLikeUrl(mentionQuery)) {
            section = MentionSection.Fetch
        }
    }
    var pickerRows by remember { mutableStateOf(emptyList<MentionChoice>()) }
    LaunchedEffect(mentionQuery, section) {
        if (mentionQuery == null) {
            // Dismissing the token closes the popup now, not a debounce later.
            pickerRows = emptyList()
            return@LaunchedEffect
        }
        delay(MentionDebounceMillis)
        pickerRows = withContext(Dispatchers.IO) {
            mentionChoices(section, mentionQuery, project.id, project.rootPath, workspace)
        }
    }
    /** A page on its way — the one picker row that costs a request. */
    var fetching by remember(thread) { mutableStateOf<String?>(null) }

    fun completeCommand(command: AgentCommand) {
        replaceText("/" + command.name + " ")
    }

    fun completeMention(choice: MentionChoice) {
        // The token is at the end and holds the only trailing `@`.
        val at = text.lastIndexOf('@')
        if (at < 0) return
        val mention = choice.mention
        val token = mention.textToken
        if (token != null) {
            // A file, a directory, a rules file: the `@path` stays in the
            // text, and deleting it there is deleting the mention.
            replaceText(text.substring(0, at) + "@" + token + " ")
            if (mention !in mentioned) mentioned.add(mention)
            return
        }
        // Everything else lives as a chip: the `@…` comes out of the text.
        replaceText(text.substring(0, at))
        if (mention is AgentMention.Fetch) {
            // Fetched now, and only now — the user picked it. Nothing was
            // requested while the row was merely offered.
            attachError = null
            fetching = mention.url
            scope.launch {
                val fetched = withContext(Dispatchers.IO) { FetchMention.fetch(mention.url) }
                fetching = null
                fetched
                    .onSuccess { page -> if (page !in mentioned) mentioned.add(page) }
                    .onFailure { attachError = "Could not fetch ${mention.url}: ${it.message}" }
            }
            return
        }
        if (mention !in mentioned) mentioned.add(mention)
    }

    fun send() {
        val message = text.trim()
        // A picture on its own is a message — "what is this?" is the whole
        // point of attaching one — so the guard is "nothing at all", not
        // "no words".
        if ((message.isEmpty() && attached.isEmpty()) || !enabled) return
        // Only mentions still standing in the text count: one deleted after
        // completion was deleted on purpose.
        //
        // **Whole tokens, not substrings.** `contains("@" + it)` matched a
        // path that is a *prefix* of another, so completing `.env` by
        // mistake, deleting it and completing `.env.example` sent both — and
        // the engine then embedded `.env`'s contents in the prompt. Every
        // real project has such pairs (`Dockerfile`/`Dockerfile.dev`,
        // `index.js`/`index.js.map`), so this was a live way to hand an
        // agent a file the user had explicitly taken back.
        val present = mentionTokensIn(message)
        val mentions = mentioned.filter { mention ->
            // A chip-only mention (a selection, a page) has no token to
            // stand in the text; its ✕ is how it is taken back.
            mention.textToken?.let { it in present } ?: true
        }
        val images = attached.toList()
        // Cleared optimistically, because the transcript shows the message the
        // instant the engine takes it and two copies would be worse than a
        // moment's blank. Restored if it turns out nothing took it.
        replaceText("")
        mentioned.clear()
        attached.clear()
        // The strip was dismissed for a message that no longer exists.
        dismissedToken = null
        onSend(message, mentions, images) {
            replaceText(message)
            mentioned.addAll(mentions)
            attached.addAll(images)
        }
    }

    // The system photo picker: no permission, no gallery of our own, and on
    // Android 13+ it is the OS's own sheet. `PickVisualMedia` rather than
    // `GetContent` because it is the modern, scoped route — the user grants
    // this one picture, not the photo library.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Decoding and re-encoding a photograph is tens of milliseconds
            // and tens of megabytes; neither belongs on the frame thread.
            val loaded = withContext(Dispatchers.IO) { PromptImages.load(context, uri) }
            when {
                loaded == null ->
                    attachError = "That image could not be read."
                !PromptImages.fits(attached.sumOf { it.approximateBytes }, loaded.approximateBytes) ->
                    attachError = "That would make the message too big to send."
                else -> {
                    attached.add(loaded)
                    attachError = null
                }
            }
        }
    }

    // What is attached, above the box — with an ✕ each, because an attachment
    // you cannot take back is a trap, and the same shape the queued-prompt
    // rows already use. Mentions are chips here too, as Zed's are creases in
    // its editor: one place to see what the prompt carries.
    if (attached.isNotEmpty() || mentioned.isNotEmpty() || fetching != null || attachError != null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (mention in mentioned) {
                MentionChip(mention) { mentioned.remove(mention) }
            }
            fetching?.let { url ->
                Text(
                    text = "Fetching $url…",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            for (image in attached) {
                AttachmentChip(image) { attached.remove(image) }
            }
            attachError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("error", theme.color("text.muted")),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }

    if (commandChoices.isNotEmpty() || mentionQuery != null) {
        // The completion strip, just above the box — tap to take one; Tab
        // takes the first, Esc puts the strip away. For `@`, Zed's context
        // picker: its sections as a strip of chips, the section's matches
        // under them.
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            for (command in commandChoices) {
                SuggestionRow(
                    primary = "/" + command.name,
                    secondary = command.description,
                    onClick = { completeCommand(command) },
                )
            }
            if (mentionQuery != null) {
                MentionSectionStrip(selected = section, onSelect = { section = it })
                for (choice in pickerRows) {
                    SuggestionRow(
                        primary = choice.primary,
                        secondary = choice.secondary,
                        onClick = { completeMention(choice) },
                    )
                }
                if (pickerRows.isEmpty()) {
                    Text(
                        text = when (section) {
                            MentionSection.Selection -> "Select something in the editor first."
                            MentionSection.Symbols -> "Open a file with symbols first."
                            MentionSection.Threads -> "No other thread in this project."
                            MentionSection.Fetch -> "Type an https:// address after the @."
                            MentionSection.Rules -> "No rules file (AGENTS.md, CLAUDE.md, .rules…) at the project root."
                            else -> "Nothing matches."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted"),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    // **The box on top, its controls underneath** — Zed's composer, where the
    // message editor spans the panel and a single row beneath it carries the
    // selectors on the left and the send button on the right
    // (thread_view.rs:4390-4455). Ours used to sit the selectors in a strip
    // *above* the divider and put a "Send" word-button beside the box, which
    // read as two unrelated rows rather than as one composer.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicTextField(
            value = field,
            onValueChange = ::setField,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            maxLines = ComposerLines,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background", Color.Transparent))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focus)
                .onFocusChanged { onFocusChanged(it.isFocused) }
                // A hardware Enter sends and Shift+Enter breaks the line — the
                // convention every chat has, and the reason it is safe here is
                // that a *soft* keyboard's Enter never arrives as a key event
                // at all (CONVENTIONS § Traps, item 4): it is committed text,
                // so on a phone Enter still inserts a newline and the button
                // is how you send.
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        // Tab takes the first suggestion — the keyboard twin
                        // of tapping it.
                        event.key == Key.Tab &&
                            (commandChoices.isNotEmpty() || pickerRows.isNotEmpty()) -> {
                            commandChoices.firstOrNull()?.let(::completeCommand)
                                ?: pickerRows.firstOrNull()?.let(::completeMention)
                            true
                        }
                        // Zed's own chord for the composer's add-context
                        // control (`ctrl-;` → `agent::OpenAddContextMenu`,
                        // default-linux.json:344). The button is the touch
                        // and mouse route; this is the one for a DeX desk,
                        // where there is no touch at all.
                        event.key == Key.Semicolon && event.isCtrlPressed -> {
                            if (canAttach) {
                                attachError = null
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                            // Swallowed either way: a `;` typed into the
                            // message because the agent takes no pictures
                            // would be a stray character, not a shortcut.
                            true
                        }
                        // Escape puts the suggestion strip away first; with no
                        // strip up it stops the turn rather than closing the
                        // panel — the panel is a dock with its own chord, and
                        // while an agent is running "stop" is what Escape
                        // means everywhere else in this app too.
                        event.key == Key.Escape -> {
                            when {
                                commandChoices.isNotEmpty() || mentionQuery != null -> {
                                    dismissedToken = commandChoices
                                        .firstOrNull()
                                        ?.let { "/" + (commandQuery ?: "") }
                                        ?: "@" + (mentionQuery ?: "")
                                    true
                                }
                                isBusy -> {
                                    onStop()
                                    true
                                }
                                else -> false
                            }
                        }

                        event.key != Key.Enter && event.key != Key.NumPadEnter -> false
                        event.isShiftPressed -> false
                        else -> {
                            send()
                            true
                        }
                    }
                },
            decorationBox = { field ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = if (enabled) {
                            "Message the agent — @ for context, / for commands"
                        } else {
                            "The agent is not running"
                        },
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color("text.muted"),
                            maxLines = 1,
                        )
                    }
                    field()
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            // **Leading the row, as in Zed**, whose composer puts its context
            // controls on the left of the same line the send button ends
            // (thread_view.rs:4431-4440). Offered only to an agent that said
            // it reads images: a `+` that produces a picture the agent will
            // never see is worse than no `+` at all.
            if (state.agent?.capabilities?.images == true) {
                ComposerIconButton(
                    icon = R.drawable.ic_agent_attach,
                    label = "Attach an image",
                    tint = theme.color("text.muted"),
                    enabled = canAttach,
                    onClick = {
                        attachError = null
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                )
            }
            // Zed's bottom row: the mode, the model, whatever else the
            // agent's config options advertise — selectors, driven entirely
            // by what came over the wire. `fill = false` so a long row of
            // chips scrolls within what is left instead of pushing the send
            // button off the panel.
            ComposerChrome(state, modifier = Modifier.weight(1f, fill = false))
            // **Four ways, not two.** Stop used to *replace* Send whenever a
            // turn was running, so on a phone — where the soft keyboard's
            // Enter arrives as committed text rather than as a keystroke — a
            // follow-up typed mid-turn could not be sent at all. Zed's send
            // button has the same three live states, and is an *icon*:
            // paper plane to send, list-with-arrow to queue behind a running
            // turn, a square to stop (thread_view.rs:5397-5476).
            val hasText = field.text.isNotBlank()
            when {
                isBusy && hasText -> ComposerIconButton(
                    icon = R.drawable.ic_agent_queue,
                    label = "Queue",
                    onClick = { send() },
                )
                isBusy -> ComposerIconButton(
                    icon = R.drawable.ic_agent_stop,
                    label = "Stop",
                    // Zed tints stop with the error colour rather than the
                    // accent, so the one destructive control in the row does
                    // not look like the one that sends (thread_view.rs:5412-5414).
                    tint = theme.color("error", theme.color("text.accent")),
                    onClick = onStop,
                )
                else -> ComposerIconButton(
                    icon = R.drawable.ic_agent_send,
                    label = "Send",
                    enabled = hasText,
                    onClick = { send() },
                )
            }
        }
    }
}

/**
 * The composer's one icon button — Zed's `IconButton` at `ButtonSize::Default`
 * (22px square, button_like.rs:469) holding a 16px icon, filled, with the
 * accent tint the send button carries when it can act and the muted one it
 * carries when it cannot (thread_view.rs:5426-5434).
 */
@Composable
private fun ComposerIconButton(
    @androidx.annotation.DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    // `ButtonStyle::Filled`'s four states, in Zed's own order
    // (button_like.rs:217, 263-266, 317, 410): the filled background, faded
    // by half on hover, `element.active` while pressed, and `element.disabled`
    // when it cannot act — a disabled send still reads as a button, which is
    // what tells you where to press once you have typed something.
    val fill = when {
        !enabled -> theme.color("element.disabled", Color.Transparent)
        pressed -> theme.color("element.active", Color.Transparent)
        hovered -> theme.color("element.background", Color.Transparent).copy(alpha = 0.5f)
        else -> theme.color("element.background", Color.Transparent)
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = when {
                !enabled -> theme.color("text.disabled", theme.color("text.muted"))
                else -> tint ?: theme.color("text.accent", MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * One attached picture: what it is called, what it weighs, and the ✕ that
 * takes it back off the message before it goes.
 */
@Composable
private fun AttachmentChip(image: PromptAttachment, onRemove: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color("element.background", Color.Transparent))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_agent_attach),
            contentDescription = null,
            tint = theme.color("text.muted"),
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = image.name,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${(image.approximateBytes + 1023) / 1024} KB",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            maxLines = 1,
        )
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = "Remove ${image.name}", onClick = onRemove)
                .padding(horizontal = 4.dp),
        )
    }
}

/**
 * One mention on the draft: its kind, its label, and the ✕ that takes it
 * back — Zed's mention creases, as a row above the box.
 */
@Composable
private fun MentionChip(mention: AgentMention, onRemove: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color("element.background", Color.Transparent))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "@",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
        )
        Text(
            text = mention.label,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = mention.kind,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            maxLines = 1,
        )
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            modifier = Modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClickLabel = "Remove ${mention.label}", onClick = onRemove)
                .padding(horizontal = 4.dp),
        )
    }
}

/** One row of the completion strip: the name, and what it is, muted. */
@Composable
private fun SuggestionRow(primary: String, secondary: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("element.hover", Color.Transparent) else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = primary,
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = primary,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = BufferFontFamily),
            color = theme.color("text"),
            maxLines = 1,
        )
        Text(
            text = secondary,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One line of explanation, `Color::Muted` as Zed's notification bodies are. */
@Composable
private fun Notice(text: String, isError: Boolean = false) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            theme.color("error", MaterialTheme.colorScheme.error)
        } else {
            theme.color("text.muted")
        },
        modifier = Modifier.padding(12.dp),
    )
}

/**
 * A panel button: filled for the answer that goes on, ghost for the way out.
 * Zed's ramps, swapped instantly with no ripple (button_like.rs:298-329).
 */
@Composable
private fun PanelButton(
    label: String,
    isPrimary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        pressed -> theme.color(
            if (isPrimary) "element.active" else "ghost_element.active",
            Color.Transparent,
        )
        hovered -> theme.color(
            if (isPrimary) "element.hover" else "ghost_element.hover",
            Color.Transparent,
        )
        isPrimary -> theme.color("element.background", Color.Transparent)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> theme.color("text.disabled", theme.color("text.muted"))
                isPrimary -> theme.color("text.accent", MaterialTheme.colorScheme.primary)
                else -> theme.color("text.muted")
            },
            maxLines = 1,
        )
    }
}
