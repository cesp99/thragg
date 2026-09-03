package to.eyed.seeker.code.ui.shell.agent

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
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
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SeekerSearchField
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoBody
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.accentIcon
import to.eyed.seeker.code.ui.theme.effectSpec
import to.eyed.seeker.code.ui.theme.mutedIcon
import to.eyed.seeker.code.ui.theme.pressScale
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

/**
 * What the caption under the box says while a turn is running, or null when
 * nothing is.
 *
 * This line carries a job the old composer gave to the button. The send
 * control used to be the *word* "Send"/"Steer"/"Queue" in a transparent box;
 * it is now a 40dp filled circle (docs/VISUAL.md, "Agent — the composer"),
 * and a circle cannot say which of the three it is. Rather than lose the
 * distinction [sendLabel] exists to make, the mode is stated in a sentence
 * while it is *not* the ordinary one — which is exactly when a user needs to
 * be told, because Steer and Queue are the two behaviours nobody guesses. The
 * label itself survives as the button's `onClickLabel`, so TalkBack still
 * announces "Steer" on the control.
 */
internal fun busyNote(mode: SendMode): String? = when (mode) {
    // An offer, not a report: this caption is visible the whole time a turn
    // runs, including with an empty box, and the earlier wording ("Steering —
    // …") read as the app already steering something when nothing had been
    // sent. Only [STEER_NOTE], after an actual send, may speak in the past
    // tense.
    SendMode.Steer -> "Send to steer — the agent takes it at its next step."
    SendMode.Queue -> "Send to queue — it goes when the turn settles."
    SendMode.Send -> null
}

/**
 * Which of the two sentences the empty box shows.
 *
 * The *choice* is here and the *wording* is in strings.xml, for two reasons
 * that pull the same way. The box used to read "Message Spettro" whoever was
 * on the other end — the app bar has always shown the connected agent's own
 * `agentName`, so a hand-written `agent_servers` entry got a composer naming a
 * program that was not running. And the name lands in the middle of the
 * sentence, so it is `%1$s` in a resource rather than a `$name` in a template:
 * a translation has to be free to move it, and a concatenation would not let
 * it. Keeping the branch pure keeps it testable off a device.
 *
 * There used to be a third, `ReadyInProject`, reading "Message %1$s — working
 * in %2$s". It said the project name a third time — the app bar subtitle and
 * the empty state both already carry it — and on a 400 dp column it never
 * survived: the placeholder is one line, so what actually drew was "Message
 * conformance-agent — working in swa…". A hint that truncates mid-word is
 * worse than a shorter hint, and the word it was spending the room on was one
 * the screen had said twice above.
 */
internal enum class ComposerHint {
    /** No session to prompt: the agent is not running. */
    Stopped,

    /** Ready. The agent's own name, and nothing the app bar already says. */
    Ready,
}

/** What the box says when it is empty. */
internal fun composerHint(enabled: Boolean): ComposerHint =
    if (enabled) ComposerHint.Ready else ComposerHint.Stopped

/** [composerHint]'s sentence, with the connected agent's own name in it. */
@Composable
internal fun composerPlaceholder(
    agentName: String,
    enabled: Boolean,
): String = when (composerHint(enabled)) {
    ComposerHint.Stopped -> stringResource(R.string.agent_composer_stopped)
    ComposerHint.Ready -> stringResource(R.string.agent_composer_message, agentName)
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
 * The box, the one button that adds to it, and the one that speaks.
 *
 * THE SHAPE, which is the whole of what changed (docs/VISUAL.md, "Agent — the
 * composer, three states"): a `surfaceContainer` band under a
 * [HairlineDivider], holding a 32dp horizontally-scrolling strip of whatever
 * has been attached and then ONE ROUNDED CONTAINER with two lines in it — the
 * field across the full width, and a control row beneath it carrying `＋`, the
 * caller's config chip, and the circles.
 *
 * THE FIELD AND THE CONTROLS STOPPED SHARING A LINE, and that is the fix.
 * Everything on one line meant the field was what was left over after four
 * controls, a config summary had nowhere to go but a full-width rule of its
 * own above it, and the owner's verdict on the result was "the single line
 * 'mode - model - permission - ultra' thing looks like shit". Stacked, the
 * message gets the whole width at the size it is actually read at, and the
 * config becomes a chip *in* the row rather than a fourth readout above it —
 * which is how spettro-chat-android's `InputBar` has always drawn it, and its
 * argument for the chip is the one this file now inherits: it is the row's
 * only flexible element, so it truncates and the buttons never compress.
 *
 * WHAT DID NOT CHANGE, because every one of them is protocol-correct and has
 * an argument written under it: [SendMode] and its three labels, the
 * **separate** stop control, the long-press [SendOptionsSheet], [STEER_NOTE],
 * [ComposerHint]'s three sentences, the hardware-Enter handling, the
 * optimistic clear with its restore, the whole-token mention filter, and the
 * activation glow travelling through the letters as they are typed.
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
    /** The connected agent's own name — the app bar's, so the two agree. */
    agentName: String,
    enabled: Boolean,
    focus: FocusRequester,
    onOpenMentions: () -> Unit,
    onStop: () -> Unit,
    onSteered: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The config chip that sits in the control row between `＋` and the
     * circles — `ConfigChip`, which belongs to the config surface rather than
     * to the composer because it is that sheet's *reading* of its own state.
     *
     * A slot rather than a call so the two can land independently: the
     * composer draws the flexible slot whether or not anything is in it (an
     * agent that has advertised no selectors leaves it empty, and the row
     * still holds its shape), and the screen decides what the session's
     * settings amount to.
     */
    config: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
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
    var adding by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf<String?>(null) }

    fun setField(value: TextFieldValue) {
        // The slash palette used to render up to five 48dp rows *inline* above
        // the box, so the composer jumped up the screen as the token was
        // typed. It is a sheet now, and this is what opens it: the transition
        // of the whole field into a command token, so it fires once on the `/`
        // and not again on every letter after it.
        val wasToken = CommandToken.matchEntire(field.text) != null
        val token = CommandToken.matchEntire(value.text)?.groupValues?.get(1)
        field = value
        thread?.draft = value.text
        if (!wasToken && token != null && state.commands.isNotEmpty()) palette = token
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
        // The one haptic on this screen, on the one act that commits: the
        // message leaves the phone on this tap, and a confirm under the thumb
        // says so on the same frame the field clears. Not on Stop — a cancel
        // is not a success — and never on a keystroke.
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
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

    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The hairline warms to the accent on focus and cools back. It is the only
    // thing on the screen that says which control the keyboard is typing into,
    // and it is drawn on the CONTAINER rather than on the field: the two lines
    // inside it are one control as far as the user is concerned, and lighting
    // half of it would say they were two.
    val edge by animateColorAsState(
        targetValue = if (focused) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant,
        animationSpec = effectSpec(),
        label = "composer-border",
    )
    val note = steerNote ?: if (busy && enabled) busyNote(mode) else null

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Painted before the inset is taken, so the band stays continuous
            // with the bottom of the window while the IME animates in.
            .background(scheme.surfaceContainer)
            .imePadding(),
    ) {
        HairlineDivider()
        // Mentions and attachments used to stack VERTICALLY, one full-width
        // chip per row: three of them was three rows shoving the composer up a
        // 890dp screen. One scrolling strip is a fixed 32dp however many there
        // are.
        if (mentioned.isNotEmpty() || attached.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MD.space2)
                    .height(DraftStripHeight),
                contentPadding = PaddingValues(horizontal = MD.space4),
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(
                    count = mentioned.size,
                    key = { "m:" + (mentioned[it].textToken ?: mentioned[it].label) },
                ) { index ->
                    val mention = mentioned[index]
                    DraftChip(
                        label = "@" + (mention.textToken ?: mention.label),
                        icon = R.drawable.ic_ui_at,
                        onRemove = { mentioned.remove(mention) },
                    )
                }
                items(count = attached.size, key = { "a:" + it + ":" + attached[it].name }) { index ->
                    val image = attached[index]
                    DraftChip(
                        label = image.name,
                        icon = R.drawable.ic_agent_attach,
                        thumbnail = image,
                        onRemove = { attached.remove(image) },
                    )
                }
            }
        }
        attachError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
                modifier = Modifier.padding(
                    start = MD.space4,
                    end = MD.space4,
                    top = MD.space2,
                ),
            )
        }
        // THE CONTAINER. One rounded surface holds both lines, so the field
        // and the controls read as one object rather than as a pill with
        // things parked either side of it; the border is on the container for
        // the same reason.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MD.space4, vertical = MD.space2)
                .clip(RoundedCornerShape(MD.radiusXl))
                .background(scheme.surfaceContainerHigh)
                .border(MD.hairline, edge, RoundedCornerShape(MD.radiusXl)),
        ) {
            BasicTextField(
                value = field,
                onValueChange = ::setField,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                // The glow travels through the letters of an activation phrase as
                // it is typed — never a box behind them (K10).
                visualTransformation = rememberActivationTransformation(ActivationSurface.COMPOSER),
                // Six lines, and now the controls genuinely do not move: they
                // are on their own line under this one, so growing the field
                // pushes the whole container up the screen instead of
                // re-laying-out the row the thumb is aiming at.
                maxLines = 6,
                interactionSource = interaction,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FieldMinHeight)
                    .padding(start = MD.space4, end = MD.space4, top = MD.space3, bottom = MD.space2)
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
                                text = composerPlaceholder(agentName, enabled),
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            // THE CONTROL ROW. No arrangement spacing on purpose: each disc is
            // [CircleSize] centred in a 48dp target, so two adjacent controls
            // already sit [CircleInset] × 2 apart, and adding a gap on top of
            // that would push send off the container's own edge.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CircleInset, vertical = CircleInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerCircle(
                    icon = R.drawable.ic_ui_plus,
                    description = "Add to this message",
                    enabled = enabled,
                    fill = Color.Transparent,
                    ink = accentIcon,
                    size = IconSize.Action,
                    // A sheet of one row is a worse control than the row itself:
                    // with no image capability and no commands, `＋` *is* the
                    // mention picker, so it opens it.
                    onClick = {
                        if (state.agent?.capabilities?.images == true || state.commands.isNotEmpty()) {
                            adding = true
                        } else {
                            onOpenMentions()
                        }
                    },
                )
                // The row's ONE flexible element, and the reason the controls
                // never compress: whatever the caller draws here is given the
                // slack and told to truncate, so a thirty-character model id
                // cannot squeeze `＋` or send. A `Box` rather than a weight on
                // the slot itself, because a slot has no `RowScope`.
                Box(
                    modifier = Modifier.weight(1f).padding(horizontal = MD.space1),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    config?.invoke()
                }
                // **Cancel is its own control.** Steering and stopping are opposite
                // acts and they are never the same button: a Send that turned into
                // a Stop mid-turn is what makes a steer look like a cancel. Both
                // circles are on screen at once for the whole of a running turn,
                // which is the point spettro-android's composer concedes.
                if (busy) {
                    ComposerCircle(
                        // `ic_agent_stop`, not `ic_ui_stop`: identical art, but
                        // the drawables are named for what they stop and this one
                        // stops a turn. The composer had the build's.
                        icon = R.drawable.ic_agent_stop,
                        description = "Stop the turn",
                        enabled = true,
                        fill = scheme.error,
                        ink = scheme.onError,
                        onClick = onStop,
                    )
                }
                ComposerCircle(
                    icon = if (mode == SendMode.Queue) {
                        R.drawable.ic_agent_queue
                    } else {
                        R.drawable.ic_agent_send
                    },
                    description = sendLabel(mode),
                    enabled = enabled && (field.text.isNotBlank() || attached.isNotEmpty()),
                    // Disabled keeps the accent at 35% rather than going grey: an
                    // empty composer is the resting state of this screen, and a
                    // dead grey disc is what the resting state would look like.
                    fill = scheme.primary,
                    disabledFill = scheme.primary.copy(alpha = 0.35f),
                    ink = scheme.onPrimary,
                    // `onPrimary` is solved against a *full* primary; over a 35%
                    // wash of it it is not, and `onSurfaceVariant` is already
                    // solved against the container the wash sits on.
                    disabledInk = scheme.onSurfaceVariant,
                    longClickLabel = "More ways to send",
                    onLongClick = { if (busy) longPress = true },
                    onClick = { send() },
                )
            }
        }
        note?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = MD.space4,
                    end = MD.space4,
                    bottom = MD.space2,
                ),
            )
        }
    }

    if (adding) {
        ComposerAddSheet(
            shell = shell,
            canAttach = state.agent?.capabilities?.images == true,
            hasCommands = state.commands.isNotEmpty(),
            onAttach = {
                adding = false
                attachError = null
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onMentions = {
                adding = false
                onOpenMentions()
            },
            onCommands = {
                adding = false
                palette = ""
            },
            onDismiss = { adding = false },
        )
    }

    palette?.let { seed ->
        CommandPaletteSheet(
            shell = shell,
            commands = state.commands,
            seed = seed,
            onPick = { command ->
                palette = null
                replaceText("/" + command.name + " ")
                runCatching { focus.requestFocus() }
            },
            onDismiss = { palette = null },
        )
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

/**
 * The field's height at one line, padding included.
 *
 * Bigger than the [CircleSize] it used to match, because it no longer shares a
 * line with the discs: on its own row an empty field that is only as tall as a
 * button reads as a slot rather than as a place to write, and this is the line
 * the screen exists for.
 */
private val FieldMinHeight = 44.dp

/** The drawn height of a control in the input row. */
private val CircleSize = 40.dp

/**
 * Half of what a 48dp touch target adds around a [CircleSize] disc.
 *
 * Also the control row's own padding, which is not a coincidence: at 4dp on
 * the row and 4dp inside each target, a disc's drawn edge lands 8dp from the
 * container's, and two neighbouring discs land 8dp apart. One number, and
 * every gap in the row comes out the same.
 */
private val CircleInset = 4.dp

/** The attachment strip: one row, whatever the count. */
private val DraftStripHeight = 32.dp

/** A thumbnail inside a draft chip. */
private val ThumbSize = 22.dp

/** How wide a chip's label may get before it middle-ellipsises. */
private val ChipLabelMax = 140.dp

/**
 * One of the three controls in the input row: `＋`, stop, send.
 *
 * They are one component because they differ only in their fill — transparent
 * for `＋`, `error` for stop, `primary` for send — and drawing them three
 * times is how three 40dp circles end up three different sizes. The drawn disc
 * is [CircleSize] inside a 48dp target: `minimumInteractiveComponentSize()`
 * grows the hit box and centres the disc in it, which clears WCAG 2.5.8
 * without changing a pixel of what is drawn. [CircleInset] is the other half
 * of that — 4dp each side of a 40dp disc in a 48dp slot — and the control row
 * pads by the same 4dp so the discs sit square in their container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposerCircle(
    @DrawableRes icon: Int,
    description: String,
    enabled: Boolean,
    fill: Color,
    ink: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disabledFill: Color = fill,
    disabledInk: Color = ink,
    size: Dp = IconSize.Inline,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    // The disc CROSSES between its two fills rather than swapping: the send
    // circle wakes from 35% to full primary on the first character typed,
    // and a frame swap there is a blink at the edge of the eye every time a
    // message starts. 200ms on [effectSpec] is a change of meaning the eye
    // can follow.
    val ground by animateColorAsState(
        targetValue = if (enabled) fill else disabledFill,
        animationSpec = effectSpec(),
        label = "composer-circle-fill",
    )
    val glyph by animateColorAsState(
        targetValue = if (enabled) ink else disabledInk,
        animationSpec = effectSpec(),
        label = "composer-circle-ink",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .touchTarget()
            // A filled circle is the most object-like control on the screen,
            // so it is the one that most needs to give under the thumb.
            .pressScale(interaction)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                onClickLabel = description,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(CircleSize)
                .clip(CircleShape)
                .background(ground),
            contentAlignment = Alignment.Center,
        ) {
            SeekerIcon(
                icon = icon,
                contentDescription = description,
                tint = glyph,
                size = size,
            )
        }
    }
}

/**
 * One mention or one attachment, in the strip above the box.
 *
 * THE WHOLE CHIP IS THE REMOVE CONTROL, and the `⨯` on it is decoration. The
 * alternative — a separate icon button — is the correct shape for the
 * affordance and the wrong one for the constraint: `SeekerIconButton` carries
 * `minimumInteractiveComponentSize()`, which reports 48dp of *layout* and
 * would make this strip 48dp tall rather than the 32dp it is specified at.
 * With the row itself clickable the target is 32dp × its full width, which
 * clears WCAG 2.5.8's 24dp floor for a labelled control, and the click label
 * says what the tap does.
 *
 * An attachment draws its own picture: [thumbnail] is decoded off the frame
 * thread from the base64 the prompt will carry, so the chip shows the image
 * that is about to be sent rather than a filename that could be anything.
 */
@Composable
private fun DraftChip(
    label: String,
    @DrawableRes icon: Int,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: PromptAttachment? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val bitmap = thumbnail?.let { rememberThumbnail(it) }
    Row(
        modifier = modifier
            .height(DraftStripHeight)
            .clip(RoundedCornerShape(MD.pill))
            .background(scheme.surfaceContainerHigh)
            .border(MD.hairline, scheme.outlineVariant, RoundedCornerShape(MD.pill))
            .clickable(onClickLabel = "Remove $label", onClick = onRemove)
            .padding(horizontal = MD.space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(ThumbSize).clip(RoundedCornerShape(MD.radiusXs)),
            )
        } else {
            SeekerIcon(
                icon = icon,
                contentDescription = null,
                tint = mutedIcon,
                size = IconSize.Marker,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            // Capped rather than weighted: this Row is inside a `LazyRow`, so
            // its main axis is unbounded and a weight would resolve against
            // the row's *minimum* width — which is zero, and a label of zero
            // width is an invisible chip.
            modifier = Modifier.widthIn(max = ChipLabelMax),
        )
        SeekerIcon(
            icon = R.drawable.ic_ui_close,
            contentDescription = null,
            tint = mutedIcon,
            size = IconSize.Marker,
        )
    }
}

/**
 * [attachment]'s picture at thumbnail size, or null until it has one.
 *
 * The bytes are already in memory as base64 — [PromptImages] re-encoded them
 * when the image was picked — but they are a 1568px JPEG, and decoding one to
 * fill 22dp on the frame thread is the kind of jank that only shows up on the
 * device. Off-thread, at the smallest power-of-two sample that still covers
 * the chip, keyed on the attachment so a strip of four decodes four times and
 * not four times per recomposition.
 */
@Composable
private fun rememberThumbnail(attachment: PromptAttachment): ImageBitmap? {
    val target = with(LocalDensity.current) { ThumbSize.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment, target) {
        value = withContext(Dispatchers.Default) { decodeThumbnail(attachment.data, target) }
    }
    return bitmap
}

/** [rememberThumbnail]'s worker: base64 in, a small bitmap out, never a throw. */
private fun decodeThumbnail(data: String, edgePx: Int): ImageBitmap? = runCatching {
    val bytes = Base64.decode(data, Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    var longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / 2 >= edgePx && sample < 1 shl 10) {
        longest /= 2
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

/**
 * What `＋` offers: the three things that go into a message and are not typed.
 *
 * These were three icon buttons in a row under the box, which cost the field
 * a third of its width to say what two of them do only once each in a session.
 * The wireframe has one `＋` (docs/VISUAL.md, "Agent — the composer"), so the
 * three become rows with sentences — which is also the first time "Commands"
 * has been discoverable without knowing that `/` does something.
 */
@Composable
private fun ComposerAddSheet(
    shell: ShellState,
    canAttach: Boolean,
    hasCommands: Boolean,
    onAttach: () -> Unit,
    onMentions: () -> Unit,
    onCommands: () -> Unit,
    onDismiss: () -> Unit,
) {
    SheetScaffold(state = shell, onDismiss = onDismiss, title = "Add to this message") {
        if (canAttach) {
            SheetAction(
                icon = R.drawable.ic_agent_attach,
                title = "Attach an image",
                detail = "A screenshot or a photograph, scaled down and sent with the message.",
                onClick = onAttach,
            )
        }
        SheetAction(
            icon = R.drawable.ic_ui_at,
            title = "Add context",
            detail = "A file, a directory, a symbol, the editor's selection, a fetched page.",
            onClick = onMentions,
        )
        if (hasCommands) {
            SheetAction(
                icon = R.drawable.ic_ui_slash,
                title = "Commands",
                detail = "What this agent can be asked to do directly, without a prompt.",
                onClick = onCommands,
            )
        }
    }
}

/**
 * The slash palette, as a sheet with a filter.
 *
 * It rendered up to five 48dp rows *inline* above the field, so the composer
 * climbed the screen as the token was typed and the list was capped at five
 * whatever the agent offered. A [SheetScaffold] is a fixed surface with the
 * search field pinned under it where the IME lands, and it shows every command
 * an agent has.
 *
 * [seed] is what was already typed after the `/`, so opening the sheet by
 * typing does not throw the typing away.
 */
@Composable
private fun CommandPaletteSheet(
    shell: ShellState,
    commands: List<AgentCommand>,
    seed: String,
    onPick: (AgentCommand) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember(seed) { mutableStateOf(seed) }
    val search = remember { FocusRequester() }
    val rows = remember(commands, query) { commandMatches(commands, query, commands.size) }

    LaunchedEffect(Unit) { runCatching { search.requestFocus() } }

    SheetScaffold(
        state = shell,
        onDismiss = onDismiss,
        title = "Commands",
        field = {
            SeekerSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Find a command",
                focusRequester = search,
            )
        },
    ) {
        if (rows.isEmpty()) {
            Text(
                text = if (commands.isEmpty()) {
                    "This agent offers no commands."
                } else {
                    "No command starts with that."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space3),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(count = rows.size, key = { rows[it].name }) { index ->
                val command = rows[index]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MD.rowMin)
                        .clickable(onClickLabel = "/" + command.name) { onPick(command) }
                        .padding(horizontal = MD.space4, vertical = MD.rowPadY),
                    verticalArrangement = Arrangement.spacedBy(MD.space05),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MD.iconGap)) {
                        // Mono because a command is typed, and the buffer face
                        // rather than `FontFamily.Monospace` because that is
                        // the mono this app has chosen (Type.kt).
                        Text(
                            text = "/" + command.name,
                            style = MonoBody,
                            color = scheme.onSurface,
                        )
                        command.inputHint?.let { hint ->
                            Text(
                                text = hint,
                                style = MonoBody,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (command.description.isNotBlank()) {
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
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
    val scheme = MaterialTheme.colorScheme
    SheetScaffold(state = shell, onDismiss = onDismiss, title = "Send this how?") {
        SheetAction(
            icon = R.drawable.ic_agent_queue,
            title = "Queue",
            detail = "Hold it until this turn settles, then send it as its own turn.",
            onClick = onQueue,
        )
        SheetAction(
            icon = R.drawable.ic_agent_stop,
            title = "Stop & send",
            detail = "Cancel the running turn, then send this as a new one.",
            tint = scheme.error,
            onClick = onStopAndSend,
        )
        if (mode == SendMode.Steer) {
            Text(
                text = "Tapping Steer instead hands it to the turn that is already " +
                    "running — it keeps working, and nothing is cancelled.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space2),
            )
        }
    }
}

/** One row of a composer sheet: a mark, what it does, and why you would. */
@Composable
private fun SheetAction(
    @DrawableRes icon: Int,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .clickable(onClickLabel = title, onClick = onClick)
            .padding(horizontal = MD.space4, vertical = MD.rowPadY),
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeekerIcon(
            icon = icon,
            contentDescription = null,
            tint = tint ?: mutedIcon,
            size = IconSize.Action,
        )
        Column(verticalArrangement = Arrangement.spacedBy(MD.space05)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = tint ?: scheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
