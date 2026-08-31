package to.eyed.seeker.code.ui.shell.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.AgentCommand
import to.eyed.seeker.code.core.AgentMention
import to.eyed.seeker.code.core.AgentSessionState
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.AgentThread
import to.eyed.seeker.code.core.PromptAttachment
import to.eyed.seeker.code.core.PromptImages
import to.eyed.seeker.code.ui.agent.mentionTokensIn
import to.eyed.seeker.code.ui.agent.spettro.ActivationSurface
import to.eyed.seeker.code.ui.agent.spettro.rememberActivationTransformation
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget

// ---------------------------------------------------------------------------
// What the button means — the pure half
// ---------------------------------------------------------------------------

/**
 * What pressing the button does, which is three different things and never a
 * cancel.
 *
 * This is the one place the phone deliberately diverges from Spettro Desktop
 * (docs/SPETTRO.md, "Composer"): **the composer stays enabled while busy**,
 * because without it mid-run steering is unreachable, and steering is the
 * feature a phone has that a desk does not — you read a run going wrong on a
 * bus and correct it in a sentence.
 *
 *  * [Send] — nothing is running; an ordinary `session/prompt`.
 *  * [Steer] — a turn is running and the agent is Spettro: the text is handed
 *    to the turn *already in flight* at its next step boundary. The steering
 *    prompt itself ends within milliseconds with a queued note, and the
 *    original turn keeps running. **It is not a cancel**, and the composer
 *    must never suggest it was one.
 *  * [Queue] — a turn is running and the agent is not Spettro. A second
 *    concurrent `session/prompt` to a generic ACP agent is two turns at once
 *    and the protocol says nothing about that, so it waits its turn.
 */
enum class SendMode { Send, Steer, Queue }

/**
 * [steerable] is `state.spettro != null` — steering is a superset method, and
 * offering it to a generic agent would send a prompt the agent answers as a
 * whole second turn.
 */
internal fun sendMode(busy: Boolean, steerable: Boolean): SendMode = when {
    !busy -> SendMode.Send
    steerable -> SendMode.Steer
    else -> SendMode.Queue
}

internal fun sendLabel(mode: SendMode): String = when (mode) {
    SendMode.Send -> "Send"
    SendMode.Steer -> "Steer"
    SendMode.Queue -> "Queue"
}

/**
 * The sentence shown after a steer lands, which exists to answer the question
 * the screen otherwise raises: the transcript did not change, so did anything
 * happen?
 */
internal const val STEER_NOTE =
    "Steering sent — the agent takes it at its next step. The turn is still running."

/** What the box says when it is empty. */
internal fun composerPlaceholder(projectName: String?, enabled: Boolean): String = when {
    !enabled -> "The agent is not running"
    projectName.isNullOrBlank() -> "Message Spettro"
    else -> "Message Spettro — working in $projectName"
}

/** A leading `/word`, the composer's command token. */
private val CommandToken = Regex("^/([\\w-]*)$")

/** The command rows a `/` shows for [query], most useful first. */
internal fun commandMatches(
    commands: List<AgentCommand>,
    query: String,
    limit: Int = 5,
): List<AgentCommand> = commands
    .filter { it.name.startsWith(query, ignoreCase = true) }
    .take(limit)

// ---------------------------------------------------------------------------
// The composer
// ---------------------------------------------------------------------------

/**
 * The box, its two context buttons and the one button that speaks.
 *
 * Draft state lives on the [AgentThread] rather than here, so leaving Agent
 * for Code to look something up and coming back does not lose a half-written
 * prompt — and each thread keeps its own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentComposer(
    shell: ShellState,
    state: AgentSessionState,
    thread: AgentThread?,
    projectName: String?,
    enabled: Boolean,
    focus: FocusRequester,
    onOpenMentions: () -> Unit,
    onStop: () -> Unit,
    onSteered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = state.isBusy
    val mode = sendMode(busy, state.spettro != null)

    var field by remember(thread) {
        val draft = thread?.draft.orEmpty()
        mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    }
    val mentioned = thread?.draftMentions ?: remember { mutableStateListOf<AgentMention>() }
    val attached = thread?.draftImages ?: remember { mutableStateListOf<PromptAttachment>() }
    var attachError by remember(thread) { mutableStateOf<String?>(null) }
    var steerNote by remember(thread) { mutableStateOf<String?>(null) }
    var longPress by remember { mutableStateOf(false) }

    fun setField(value: TextFieldValue) {
        field = value
        thread?.draft = value.text
    }

    fun replaceText(text: String) {
        setField(TextFieldValue(text, TextRange(text.length)))
    }

    // The mailbox the rest of the app seeds — a diagnostic, a build failure, a
    // freshly scaffolded program. Drained here rather than in the screen
    // because the field is the only thing that can hold it: writing
    // `thread.draft` from outside would be overwritten by this composable's
    // own state on the very next keystroke.
    LaunchedEffect(AgentSeams.pending, thread) {
        if (thread == null) return@LaunchedEffect
        val seed = AgentSeams.take() ?: return@LaunchedEffect
        val existing = field.text.trimEnd()
        val next = if (existing.isEmpty()) seed.text else existing + "\n\n" + seed.text
        replaceText(next)
        for (mention in seed.mentions) if (mention !in mentioned) mentioned.add(mention)
        runCatching { focus.requestFocus() }
    }

    LaunchedEffect(steerNote) {
        if (steerNote == null) return@LaunchedEffect
        delay(6_000)
        steerNote = null
    }

    val commandQuery = CommandToken.matchEntire(field.text)?.groupValues?.get(1)
    val commandRows = if (commandQuery == null) {
        emptyList()
    } else {
        commandMatches(state.commands, commandQuery)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Decoding and re-encoding a photograph is tens of milliseconds and
            // tens of megabytes; neither belongs on the frame thread.
            val loaded = withContext(Dispatchers.IO) { PromptImages.load(context, uri) }
            when {
                loaded == null -> attachError = "That image could not be read."
                !PromptImages.fits(attached.sumOf { it.approximateBytes }, loaded.approximateBytes) ->
                    attachError = "That would make the message too big to send."
                else -> {
                    attached.add(loaded)
                    attachError = null
                }
            }
        }
    }

    fun send(forced: SendMode = mode) {
        val message = field.text.trim()
        // A picture on its own is a message — "what is this?" is the whole
        // point of attaching one.
        if ((message.isEmpty() && attached.isEmpty()) || !enabled) return
        // Only mentions still standing in the text go out: one completed by
        // mistake and deleted was deleted on purpose, and `@.env` is a prefix
        // of `@.env.example`, so this is a whole-token test rather than a
        // substring one.
        val present = mentionTokensIn(message)
        val mentions = mentioned.filter { mention ->
            mention.textToken?.let { it in present } ?: true
        }
        val images = attached.toList()
        // Cleared optimistically: the transcript shows the message the instant
        // the engine takes it, and two copies read worse than a blank frame.
        replaceText("")
        mentioned.clear()
        attached.clear()
        val restore = {
            replaceText(message)
            mentioned.addAll(mentions)
            attached.addAll(images)
        }
        when (forced) {
            SendMode.Steer -> {
                steerNote = STEER_NOTE
                onSteered()
                AgentSessions.steer(message, mentions, images) { restore() }
            }
            // Queue and Send are the same call: the engine queues a prompt
            // sent while a turn runs and sends it when the turn settles. The
            // label is the honest difference, not the code path.
            SendMode.Send, SendMode.Queue ->
                AgentSessions.prompt(message, mentions, images) { restore() }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .background(theme.color("panel.background", theme.color("surface.background")))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steerNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        for (mention in mentioned) {
            ComposerChip("@" + (mention.textToken ?: mention.label)) { mentioned.remove(mention) }
        }
        for (image in attached) {
            ComposerChip("🖼 " + image.name) { attached.remove(image) }
        }
        attachError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("error", MaterialTheme.colorScheme.error),
            )
        }

        // The slash palette, above the box where the thumb is, one 48 dp row
        // per command: the name in mono, its input hint as ghost text, its
        // description underneath.
        for (command in commandRows) {
            CommandRow(command) { replaceText("/" + command.name + " ") }
        }

        BasicTextField(
            value = field,
            onValueChange = ::setField,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(theme.color("text.accent", MaterialTheme.colorScheme.primary)),
            // The glow travels through the letters of an activation phrase as
            // it is typed — never a box behind them (K10).
            visualTransformation = rememberActivationTransformation(ActivationSurface.COMPOSER),
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.color("editor.background", Color.Transparent))
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    // A *soft* keyboard's Enter never arrives as a key event —
                    // it is committed text — so this is the hardware keyboard's
                    // path only, and on a phone the button is how you send.
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key != Key.Enter && event.key != Key.NumPadEnter -> false
                        event.isShiftPressed -> false
                        else -> {
                            send()
                            true
                        }
                    }
                },
            decorationBox = { inner ->
                Box {
                    if (field.text.isEmpty()) {
                        Text(
                            text = composerPlaceholder(projectName, enabled),
                            style = MaterialTheme.typography.bodyMedium,
                            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.agent?.capabilities?.images == true) {
                ComposerAction("＋", "Attach an image", enabled) {
                    attachError = null
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            ComposerAction("@", "Add context", enabled, onClick = onOpenMentions)
            ComposerAction("/", "Commands", enabled) {
                if (field.text.isEmpty()) replaceText("/")
            }
            Box(modifier = Modifier.weight(1f))
            // **Cancel is its own control.** Steering and stopping are opposite
            // acts and they are never the same button: a Send that turned into
            // a Stop mid-turn is what makes a steer look like a cancel.
            if (busy) {
                ComposerAction(
                    label = "■",
                    description = "Stop the turn",
                    enabled = true,
                    tint = theme.color("error", MaterialTheme.colorScheme.error),
                    onClick = onStop,
                )
            }
            SendButton(
                mode = mode,
                enabled = enabled && (field.text.isNotBlank() || attached.isNotEmpty()),
                onClick = { send() },
                onLongClick = { if (busy) longPress = true },
            )
        }
    }

    if (longPress) {
        SendOptionsSheet(
            shell = shell,
            mode = mode,
            onQueue = {
                longPress = false
                send(SendMode.Queue)
            },
            onStopAndSend = {
                longPress = false
                // Stop first, then send: `acpCancel` returns as soon as the
                // cancel is on the wire, and the engine queues what follows
                // behind the `cancelled` it is waiting for.
                onStop()
                send(SendMode.Send)
            },
            onDismiss = { longPress = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(
    mode: SendMode,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val label = sendLabel(mode)
    Text(
        text = "▶ $label",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = if (enabled) {
            theme.color("text.accent", MaterialTheme.colorScheme.primary)
        } else {
            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        maxLines = 1,
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = enabled,
                onClickLabel = label,
                onLongClickLabel = "More ways to send",
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** One tap target in the composer's bottom row. */
@Composable
private fun ComposerAction(
    label: String,
    description: String,
    enabled: Boolean,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            tint != null -> tint
            else -> theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** An attachment or a mention, with the ✕ that takes it back. */
@Composable
private fun ComposerChip(label: String, onRemove: () -> Unit) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(theme.color("element.background", Color.Transparent))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "✕",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier
                .touchTarget()
                .clickable(onClickLabel = "Remove", onClick = onRemove)
                .padding(horizontal = 2.dp),
        )
    }
}

/** One row of the slash palette. */
@Composable
private fun CommandRow(command: AgentCommand, onPick: () -> Unit) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = command.name, onClick = onPick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "/" + command.name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
            command.inputHint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (command.description.isNotBlank()) {
            Text(
                text = command.description,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The long-press menu on the send button: the two things the tap is not.
 *
 * Both exist because *Steer* is a real choice and not the only one. A user
 * mid-run may want the next sentence held until the turn settles (**Queue**,
 * which is the ordinary `session/prompt` an agent queues), or may have decided
 * the run is wrong outright (**Stop & send**, a cancel followed by a fresh
 * turn). Neither is what a tap does, and neither is discoverable without
 * being written down — hence a sheet with sentences rather than two more icons.
 */
@Composable
private fun SendOptionsSheet(
    shell: ShellState,
    mode: SendMode,
    onQueue: () -> Unit,
    onStopAndSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    SheetScaffold(state = shell, onDismiss = onDismiss, title = "Send this how?") {
        SendOption(
            title = "Queue",
            detail = "Hold it until this turn settles, then send it as its own turn.",
            onClick = onQueue,
        )
        SendOption(
            title = "Stop & send",
            detail = "Cancel the running turn, then send this as a new one.",
            tint = theme.color("error", MaterialTheme.colorScheme.error),
            onClick = onStopAndSend,
        )
        if (mode == SendMode.Steer) {
            Text(
                text = "Tapping ▶ Steer instead hands it to the turn that is already " +
                    "running — it keeps working, and nothing is cancelled.",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SendOption(
    title: String,
    detail: String,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = title, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = tint ?: theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}
