package to.eyed.seeker.code.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/**
 * The caret's remaining language questions: find references, code actions
 * and formatting — each a [Stable] state beside the hover's, driven by the
 * same ask-then-poll machinery, drawn by the same anchored-popup arithmetic.
 *
 * Rename is deliberately not here: its dialog needs the whole workspace (the
 * edit lands in files this pane cannot open), so it lives in
 * `ui/workspace/RenameSymbol.kt` and this pane only raises it.
 */

private val LIST_WIDTH = 340.dp
private val LIST_MAX_HEIGHT = 300.dp
private val LIST_PADDING = 6.dp

// ---- find references --------------------------------------------------------

private data class ReferencesQuestion(val row: Int, val col: Int, val generation: Int)

/**
 * Zed's `editor::FindAllReferences`. Zed opens the answers straight into a
 * multibuffer; this shows the list first, because on a phone a popup beats a
 * whole tab for the common "one or two hits" answer — and then offers the
 * multibuffer, which is Zed's surface, from [openAll].
 */
@Stable
class ReferencesState internal constructor(private val editor: EditorState) {
    private var question: ReferencesQuestion? by mutableStateOf(null)
    private var generation = 0

    /** See [DefinitionState.onOpenElsewhere] for why a field, not an arg. */
    internal var onOpenElsewhere: (DefinitionTarget) -> Unit = {}

    /**
     * Every answer at once, as an editable multibuffer — Zed's own surface for
     * `FindAllReferences`. Null where the host cannot open tabs, which is the
     * host tests' state and leaves the row off the popup.
     */
    internal var onOpenAll: ((List<ReferenceTarget>) -> Unit)? = null

    /** The settled answer; empty is a real answer ("nothing uses this"). */
    var targets: List<ReferenceTarget>? by mutableStateOf(null)
        private set

    /** What the rows are — "references", or the [GoToKind] title a jump with several answers lent the list. */
    var noun: String = "references"
        private set

    /** Where the question was asked — the popup's anchor. */
    var row: Int = 0
        private set
    var col: Int = 0
        private set

    val isShowing: Boolean get() = targets != null

    /** Ask about the symbol the caret is on — Zed's `Shift+F12`. */
    fun findAtCaret() {
        if (editor.sessionOrNull == null) return
        generation++
        targets = null
        question = ReferencesQuestion(editor.cursorRow, editor.cursorCol, generation)
    }

    /**
     * Show a list somebody else already has — a go-to with several
     * answers, which Zed would open as a multibuffer. Same rows, same
     * taps; only the heading changes.
     */
    fun show(noun: String, row: Int, col: Int, targets: List<DefinitionTarget>) {
        question = null
        this.noun = noun
        this.row = row
        this.col = col
        this.targets = targets.map { target ->
            ReferenceTarget(
                path = target.path,
                row = target.row,
                colUtf16 = target.colUtf16,
                endRow = target.endRow,
                endColUtf16 = target.endColUtf16,
                lineText = null,
            )
        }
    }

    fun clear() {
        question = null
        targets = null
    }

    /**
     * Every reference in one editable document — Zed's `FindAllReferences`
     * result, which is a multibuffer. True when there was something to open.
     */
    fun openAll(): Boolean {
        val found = targets?.takeIf { it.isNotEmpty() } ?: return false
        val open = onOpenAll ?: return false
        clear()
        open(found)
        return true
    }

    /** A row was tapped: land on it, here or in another file. */
    fun open(target: ReferenceTarget) {
        clear()
        val here = editor.sessionOrNull?.path
        if (here != null && here == target.path) {
            editor.revealDefinition(target.row, target.colUtf16)
        } else {
            onOpenElsewhere(target.asDefinition())
        }
    }

    @Composable
    internal fun Poller() {
        val pending = question
        LaunchedEffect(pending) {
            if (pending == null) return@LaunchedEffect
            val session = editor.sessionOrNull ?: return@LaunchedEffect
            val answer =
                requestLsp(LspRequestKind.References, session.id, pending.row, pending.col)
            question = null
            if (answer == null) return@LaunchedEffect
            // Strict, like a definition's: the list carries positions, and
            // positions in a buffer that has moved since point at the wrong
            // text.
            if (!answer.describes(session.id, editor.bufferVersion, pending.row, pending.col)) {
                return@LaunchedEffect
            }
            noun = "references"
            row = pending.row
            col = pending.col
            targets = parseReferenceTargets(answer.payload)
        }
    }
}

@Composable
internal fun rememberReferences(
    state: EditorState,
    onOpenAll: ((List<ReferenceTarget>) -> Unit)? = null,
    onOpenElsewhere: (DefinitionTarget) -> Unit,
): ReferencesState {
    val references = remember(state) { ReferencesState(state) }
    references.onOpenElsewhere = onOpenElsewhere
    references.onOpenAll = onOpenAll
    // An edit moves every position in the list; close rather than lie.
    LaunchedEffect(state) {
        snapshotFlow { state.revision }.collect { references.clear() }
    }
    references.Poller()
    return references
}

// ---- code actions -----------------------------------------------------------

private data class ActionsQuestion(val row: Int, val col: Int, val generation: Int)

private data class ActionPick(val listId: Long, val index: Int, val generation: Int)

/** What the code-actions popup is showing right now. */
internal sealed interface ActionsPhase {
    /** The menu of titles. [listId] is what holds the actions engine-side. */
    data class Menu(val listId: Long, val actions: List<CodeActionItem>) : ActionsPhase

    /** A pick is being resolved and applied. */
    data object Applying : ActionsPhase

    /** A sentence to read — "nothing to change", a refusal — then dismiss. */
    data class Note(val text: String) : ActionsPhase
}

/**
 * Zed's `editor::ToggleCodeActions`: ask what can be done at the caret, show
 * the titles, and land the picked action's edit through the engine.
 *
 * The two-step is the bridge's contract: the list request stays alive while
 * the menu shows — it is what holds the parsed actions — and the pick starts
 * a second request that resolves the action and readies its edit, which
 * [applyPendingEdit] then lands. The receipt goes to [onApplied], where the
 * workspace resyncs every editor the engine edited underneath.
 */
@Stable
class CodeActionsState internal constructor(private val editor: EditorState) {
    private var question: ActionsQuestion? by mutableStateOf(null)
    private var pick: ActionPick? by mutableStateOf(null)
    private var generation = 0

    internal var phase: ActionsPhase? by mutableStateOf(null)
        private set

    /** See [DefinitionState.onOpenElsewhere] for why a field, not an arg. */
    internal var onApplied: (EditReceipt) -> Unit = {}

    var row: Int = 0
        private set
    var col: Int = 0
        private set

    val isShowing: Boolean get() = phase != null

    /** Ask at the caret — Zed's `Ctrl+.`, and the action row's "fix". */
    fun invokeAtCaret() {
        if (editor.sessionOrNull == null) return
        generation++
        phase = null
        question = ActionsQuestion(editor.cursorRow, editor.cursorCol, generation)
    }

    /** Dismiss whatever is showing. The engine's slot is retired by the next
     * ask superseding it — or by the buffer closing — so nothing leaks. */
    fun clear() {
        question = null
        pick = null
        phase = null
    }

    /**
     * Escape and tap-away: hide the popup without killing an apply in
     * flight — the engine is editing buffers either way, and the receipt
     * that resyncs their editors must land. If the apply ends in an error,
     * its note reopens the popup; success stays quiet.
     */
    fun dismiss(): Boolean {
        if (phase == null) return false
        question = null
        if (phase == ActionsPhase.Applying) {
            phase = null
        } else {
            clear()
        }
        return true
    }

    /**
     * The edit-driven dismissal, which must not kill an apply in flight: the
     * apply itself is about to edit this very buffer, and cancelling it on
     * its own edit would lose the receipt that resyncs the other editors.
     */
    internal fun clearOnEdit() {
        if (phase == ActionsPhase.Applying) return
        clear()
    }

    /** A menu row was tapped. */
    fun pick(action: CodeActionItem) {
        val menu = phase as? ActionsPhase.Menu ?: return
        if (action.disabled != null) {
            phase = ActionsPhase.Note(action.disabled)
            return
        }
        generation++
        phase = ActionsPhase.Applying
        pick = ActionPick(menu.listId, action.index, generation)
    }

    @Composable
    internal fun Poller() {
        val pendingQuestion = question
        LaunchedEffect(pendingQuestion) {
            if (pendingQuestion == null) return@LaunchedEffect
            val session = editor.sessionOrNull ?: return@LaunchedEffect
            val answer = requestLsp(
                LspRequestKind.CodeAction,
                session.id,
                pendingQuestion.row,
                pendingQuestion.col,
            )
            question = null
            if (answer == null || answer.state != LspRequestState.Done) return@LaunchedEffect
            // Row-level looseness would be wrong here: the *edits* an action
            // carries are dated against the asked-at version, so a buffer
            // that moved since needs a fresh ask, not a clamped one.
            if (!answer.describes(
                    session.id,
                    editor.bufferVersion,
                    pendingQuestion.row,
                    pendingQuestion.col,
                )
            ) {
                return@LaunchedEffect
            }
            val actions = parseCodeActions(answer.payload)
            row = pendingQuestion.row
            col = pendingQuestion.col
            phase = if (actions.isEmpty()) {
                ActionsPhase.Note("No code actions here")
            } else {
                ActionsPhase.Menu(answer.id, actions)
            }
        }

        val pendingPick = pick
        LaunchedEffect(pendingPick) {
            if (pendingPick == null) return@LaunchedEffect
            val answer = requestCodeActionApply(pendingPick.listId, pendingPick.index)
            if (pick != pendingPick) return@LaunchedEffect
            pick = null
            if (answer == null || answer.state != LspRequestState.Done) {
                phase = ActionsPhase.Note("The server did not answer")
                return@LaunchedEffect
            }
            val summary = EditSummary.parse(answer.payload)
            when {
                summary.error != null -> phase = ActionsPhase.Note(summary.error)
                summary.resourceOps -> phase = ActionsPhase.Note(
                    "This action creates, renames or deletes files, " +
                        "which is not supported yet"
                )
                summary.isEmpty -> phase = ActionsPhase.Note("Nothing to change")
                else -> {
                    // The apply must outlive a cancellation landing mid-way:
                    // the engine edits buffers either way, and losing the
                    // receipt would leave their editors unsynced.
                    val receipt = withContext(NonCancellable + Dispatchers.IO) {
                        EditReceipt.parse(CoreBridge.lspApplyPendingEdit(answer.id))
                    }
                    if (!receipt.applied || receipt.error != null) {
                        phase = ActionsPhase.Note(receipt.error ?: "Nothing was applied")
                        if (receipt.files.isNotEmpty()) onApplied(receipt)
                    } else {
                        phase = null
                        onApplied(receipt)
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberCodeActions(
    state: EditorState,
    onApplied: (EditReceipt) -> Unit,
): CodeActionsState {
    val actions = remember(state) { CodeActionsState(state) }
    actions.onApplied = onApplied
    LaunchedEffect(state) {
        snapshotFlow { state.revision }.collect { actions.clearOnEdit() }
    }
    actions.Poller()
    return actions
}

// ---- format document --------------------------------------------------------

/**
 * Zed's `editor::Format`, as fire-and-forget: ask, and land what comes back.
 * No popup — a well-formatted file answers with zero edits and there is
 * nothing to say — and failures are quiet for Zed's reason: a formatter that
 * is not installed is the status bar's story, not a dialog's.
 */
@Stable
class FormatState internal constructor(private val editor: EditorState) {
    private var request: Int by mutableStateOf(0)
    private var generation = 0

    internal var onApplied: (EditReceipt) -> Unit = {}

    fun format() {
        if (editor.sessionOrNull == null) return
        request = ++generation
    }

    @Composable
    internal fun Poller() {
        val pending = request
        LaunchedEffect(pending) {
            if (pending == 0) return@LaunchedEffect
            val session = editor.sessionOrNull ?: return@LaunchedEffect
            val answer = requestFormatting(session.id)
            request = 0
            if (answer == null || answer.state != LspRequestState.Done) return@LaunchedEffect
            val summary = EditSummary.parse(answer.payload)
            if (summary.error != null || summary.isEmpty) return@LaunchedEffect
            val receipt = withContext(NonCancellable + Dispatchers.IO) {
                EditReceipt.parse(CoreBridge.lspApplyPendingEdit(answer.id))
            }
            if (receipt.files.isNotEmpty()) onApplied(receipt)
        }
    }
}

@Composable
internal fun rememberFormat(state: EditorState, onApplied: (EditReceipt) -> Unit): FormatState {
    val format = remember(state) { FormatState(state) }
    format.onApplied = onApplied
    format.Poller()
    return format
}

// ---- the popups -------------------------------------------------------------

/**
 * The references list, hung off the asked-at position by the completion
 * menu's arithmetic and dressed like the hover card.
 */
@Composable
internal fun ReferencesPopup(
    references: ReferencesState,
    anchorX: Float,
    anchorTop: Float,
    lineHeight: Float,
    areaWidth: Float,
    areaBottom: Float,
    onDismiss: () -> Unit,
) {
    val targets = references.targets ?: return
    ListPopup(
        anchorX = anchorX,
        anchorTop = anchorTop,
        lineHeight = lineHeight,
        areaWidth = areaWidth,
        areaBottom = areaBottom,
        onDismiss = onDismiss,
    ) {
        Text(
            text = if (targets.isEmpty()) "No ${references.noun}" else "${targets.size} ${references.noun}",
            style = MaterialTheme.typography.labelSmall,
            color = LocalZedTheme.current.color("text.muted"),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        for (target in targets) {
            ReferenceRow(target = target, onClick = { references.open(target) })
        }
        // Zed's actual answer to FindAllReferences, one tap away: every hit in
        // one editable document. The keyboard reaches it with Alt+Enter, Zed's
        // `editor::OpenExcerpts` chord.
        if (targets.isNotEmpty() && references.onOpenAll != null) {
            OpenAllRow(onClick = { references.openAll() })
        }
    }
}

/** The footer row into the multibuffer. */
@Composable
private fun OpenAllRow(onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = "Open all in a multibuffer",
        style = MaterialTheme.typography.labelMedium,
        color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("ghost_element.hover", Color.Transparent)
                else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun ReferenceRow(target: ReferenceTarget, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("ghost_element.hover", Color.Transparent)
                else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            // The name is the part that tells rows apart; the directory is
            // usually shared. Ellipsis at the start keeps the name visible.
            text = "${target.path.substringAfterLast('/')}:${target.row + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val line = target.lineText
        if (line != null) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The code-actions menu — titles, an applying line, or a sentence. */
@Composable
internal fun CodeActionsPopup(
    actions: CodeActionsState,
    anchorX: Float,
    anchorTop: Float,
    lineHeight: Float,
    areaWidth: Float,
    areaBottom: Float,
    onDismiss: () -> Unit,
) {
    val phase = actions.phase ?: return
    val theme = LocalZedTheme.current
    ListPopup(
        anchorX = anchorX,
        anchorTop = anchorTop,
        lineHeight = lineHeight,
        areaWidth = areaWidth,
        areaBottom = areaBottom,
        onDismiss = onDismiss,
    ) {
        when (phase) {
            is ActionsPhase.Menu -> for (action in phase.actions) {
                ActionRow(action = action, onClick = { actions.pick(action) })
            }
            ActionsPhase.Applying -> Text(
                text = "Applying…",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(6.dp),
            )
            is ActionsPhase.Note -> Text(
                text = phase.text,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(action: CodeActionItem, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val enabled = action.disabled == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered && enabled) theme.color("ghost_element.hover", Color.Transparent)
                else Color.Transparent
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = action.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                theme.color("text.disabled", theme.color("text.muted"))
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (action.disabled != null) {
            Text(
                text = action.disabled,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The shared shell: the completion menu's placement, the hover card's dress,
 * scrolling when the rows outgrow the box.
 */
@Composable
private fun ListPopup(
    anchorX: Float,
    anchorTop: Float,
    lineHeight: Float,
    areaWidth: Float,
    areaBottom: Float,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val theme = LocalZedTheme.current
    val density = LocalDensity.current
    val widthPx = with(density) { min(LIST_WIDTH.toPx(), areaWidth) }
    val placement = with(density) {
        placeMenuAtCaret(
            caretX = anchorX,
            caretTop = anchorTop,
            lineHeight = lineHeight,
            wantedWidth = widthPx,
            wantedHeight = LIST_MAX_HEIGHT.toPx(),
            minHeight = min(LIST_MAX_HEIGHT.toPx(), lineHeight * 3f),
            areaWidth = areaWidth,
            areaTop = 0f,
            areaBottom = areaBottom,
        )
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .offset { IntOffset(placement.x.roundToInt(), placement.y.roundToInt()) }
            .width(with(density) { widthPx.toDp() })
            .heightIn(max = with(density) { placement.height.toDp() })
            .clip(shape)
            .background(theme.color("elevated_surface.background"))
            .border(1.dp, theme.color("border.variant"), shape)
            .padding(LIST_PADDING)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        content()
        // A tappable dismiss at the tail, because a phone has no Escape and
        // no "move the pointer away" — the hover card's convention, as a row.
        Text(
            text = "Dismiss",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted"),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}
