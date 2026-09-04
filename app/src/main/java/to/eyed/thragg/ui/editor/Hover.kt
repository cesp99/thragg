package to.eyed.thragg.ui.editor

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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.json.JSONObject
import to.eyed.thragg.ui.preview.InlineSpan
import to.eyed.thragg.ui.preview.MarkdownBlock
import to.eyed.thragg.ui.preview.parseMarkdown
import to.eyed.thragg.ui.theme.LocalZedTheme

/**
 * Hover, and go-to-definition — the two answers about the symbol under a
 * point rather than about the caret's own word.
 *
 * They share their two routes in, which is why they share a file. On a desktop
 * they are Zed's: the pointer resting over a symbol shows the card after
 * `hover_popover_delay`, and Ctrl-clicking one goes to where it is defined
 * (crates/editor/src/hover_links.rs:162, 202-262). On a phone there is no
 * pointer to rest and no Ctrl to hold, so both hang off the long press: it
 * shows the card, and the card carries the definition as a row you can tap.
 *
 * The markdown a server sends is rendered as plain readable text rather than
 * as a document — the reader in `ui/preview` does the parsing (it is the same
 * markdown, and forking it would be two dialects to fix bugs in twice), and
 * [markdownToText] flattens its blocks. A signature in a fenced block is the
 * common case and it survives verbatim.
 */

/** Zed's `MIN_POPOVER_CHARACTER_WIDTH` is 20 chars; this is its phone-sized twin. */
private val CARD_WIDTH = 320.dp

/** Zed's popover: `rounded_lg` 8px, 1px `border.variant`, elevated surface. */
private val CARD_PADDING = 8.dp

/** How tall a card may get before it scrolls inside itself. */
private val CARD_MAX_HEIGHT = 220.dp

/** What a server said about a symbol. */
data class HoverInfo(
    /** Markdown, trimmed; `""` when the server had nothing to say. */
    val contents: String,
    /** The range the card is about, or null when the server gave none. */
    val range: LspRange?,
) {
    val isEmpty: Boolean get() = contents.isBlank()

    companion object {
        val EMPTY = HoverInfo("", null)

        fun parse(payload: JSONObject?): HoverInfo {
            if (payload == null) return EMPTY
            return HoverInfo(
                contents = if (payload.isNull("contents")) {
                    ""
                } else {
                    payload.optString("contents", "")
                },
                range = parseLspRange(payload.optJSONObject("range")),
            )
        }
    }
}

/** One place a symbol is defined. [path] is absolute and openable. */
data class DefinitionTarget(
    val path: String,
    val row: Int,
    val colUtf16: Int,
    val endRow: Int,
    val endColUtf16: Int,
)

/**
 * The targets in a definition payload, in the order the server gave them.
 *
 * `targets` may legitimately be empty — a keyword has no definition — and
 * targets in URIs that are not files have already been dropped by the engine
 * rather than handed over as paths that do not exist.
 */
fun parseDefinitionTargets(payload: JSONObject?): List<DefinitionTarget> {
    val array = payload?.optJSONArray("targets") ?: return emptyList()
    val targets = ArrayList<DefinitionTarget>(array.length())
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val path = if (entry.isNull("path")) null else entry.optString("path", "")
        if (path.isNullOrEmpty()) continue
        val row = entry.optInt("row", 0)
        val col = entry.optInt("col_utf16", 0)
        targets.add(
            DefinitionTarget(
                path = path,
                row = row,
                colUtf16 = col,
                endRow = entry.optInt("end_row", row),
                endColUtf16 = entry.optInt("end_col_utf16", col),
            )
        )
    }
    return targets
}

// ---- markdown, read out loud ------------------------------------------------

/**
 * An LSP markdown string as plain readable text.
 *
 * A hover card is two or three lines of signature and a sentence of prose, and
 * what a reader wants from it is the words — so headings lose their hashes,
 * emphasis loses its asterisks, a link becomes its label, a list becomes
 * bullets, and a fenced code block is copied out verbatim, because a fenced
 * code block in a hover *is* the signature and every character of it counts.
 *
 * The parsing is `ui/preview`'s, unchanged and unforked: it is the same
 * markdown, it already refuses to recurse as deep as a hostile document asks,
 * and a second dialect would be a second set of bugs.
 */
fun markdownToText(markdown: String): String {
    if (markdown.isBlank()) return ""
    val out = StringBuilder()
    appendBlocks(out, parseMarkdown(markdown), prefix = "")
    return out.toString().trim('\n', ' ')
}

private fun appendBlocks(out: StringBuilder, blocks: List<MarkdownBlock>, prefix: String) {
    for (block in blocks) {
        if (out.isNotEmpty() && !out.endsWith("\n\n")) {
            if (!out.endsWith("\n")) out.append('\n')
        }
        when (block) {
            is MarkdownBlock.Heading -> {
                out.append(prefix).append(inlineText(block.content)).append('\n')
            }
            is MarkdownBlock.Paragraph -> {
                out.append(prefix).append(inlineText(block.content)).append('\n')
            }
            is MarkdownBlock.Code -> {
                for (line in block.code.trimEnd('\n').split('\n')) {
                    out.append(prefix).append(line).append('\n')
                }
            }
            is MarkdownBlock.Quote -> appendBlocks(out, block.blocks, "$prefix> ")
            is MarkdownBlock.Bullets -> {
                for (item in block.items) {
                    val marker = when (item.checked) {
                        true -> "${item.marker} [x] "
                        false -> "${item.marker} [ ] "
                        null -> "${item.marker} "
                    }
                    val nested = StringBuilder()
                    appendBlocks(nested, item.blocks, prefix = "")
                    val lines = nested.toString().trim('\n').split('\n')
                    lines.forEachIndexed { index, line ->
                        out.append(prefix)
                            .append(if (index == 0) marker else " ".repeat(marker.length))
                            .append(line)
                            .append('\n')
                    }
                }
            }
            // A `$$…$$` block is TeX; hover text has no way to set it, and the
            // source is what a hover of a formula should say anyway.
            is MarkdownBlock.Math -> {
                for (line in block.source.split('\n')) {
                    out.append(prefix).append(line).append('\n')
                }
            }
            MarkdownBlock.Rule -> out.append(prefix).append("———").append('\n')
            is MarkdownBlock.Table -> {
                val rows = listOf(block.header) + block.rows
                for (row in rows) {
                    out.append(prefix)
                        .append(row.joinToString("  ") { inlineText(it) })
                        .append('\n')
                }
            }
        }
        out.append('\n')
    }
}

private fun inlineText(spans: List<InlineSpan>): String {
    val text = StringBuilder()
    for (span in spans) {
        // An image is its alt text, which is the only part of it a reader can
        // use here — nothing in this editor fetches a picture for a tooltip.
        text.append(span.text)
    }
    return text.toString().trim()
}

// ---- the hover card ---------------------------------------------------------

/** Where a hover was asked about, and what came back. */
private data class HoverQuestion(val row: Int, val col: Int, val generation: Int)

/**
 * The hover card's state: what is showing, where, and what asked for it.
 *
 * Two things ask. The pointer resting over a symbol asks after Zed's own
 * `hover_popover_delay` of 300 ms — the delay is the whole affordance, since a
 * card that appeared instantly would follow the pointer around the screen. A
 * long press asks at once, because the gesture *is* the deliberate act the
 * delay exists to detect.
 */
@Stable
class HoverCardState internal constructor(private val editor: EditorState) {

    var text: String by mutableStateOf("")
        private set

    /** Where the card is anchored, in buffer coordinates. */
    var row: Int by mutableStateOf(-1)
        private set
    var col: Int by mutableStateOf(0)
        private set

    private var question: HoverQuestion? by mutableStateOf(null)
    private var generation = 0
    /**
     * Whether the card on screen was raised by a long press — which hid the
     * clipboard toolbar to make room for it, so dismissing has to put the
     * toolbar back. Readable by the pane for exactly that.
     */
    var askedByTouch = false
        private set
    private var askedWithDelay = false

    val isShowing: Boolean get() = text.isNotEmpty()

    /** A question is out and has not come back — a long press waiting on it. */
    val isPending: Boolean get() = question != null

    /**
     * Nothing came back for a long press, so the gesture meant what it always
     * meant. Set by the pane, which is the only thing that knows what else a
     * long press does (select the word, and offer the clipboard toolbar).
     */
    internal var onNothingToSay: (() -> Unit)? = null

    /**
     * The pointer is resting at (row, col) — Zed's `hover_at`
     * (hover_popover.rs:49). Delayed, because the pointer crosses a dozen
     * symbols on its way anywhere.
     */
    fun pointerAt(row: Int, col: Int) {
        if (askedByTouch && isShowing) return
        ask(row, col, byTouch = false, delayed = true)
    }

    /**
     * A long press landed on (row, col). No delay: the gesture already *is*
     * the deliberate pause the pointer's delay exists to detect.
     */
    fun longPressAt(row: Int, col: Int) {
        ask(row, col, byTouch = true, delayed = false)
    }

    /** Zed's `editor::Hover` action — asked for by name, so asked at once. */
    fun invokeAt(row: Int, col: Int) {
        ask(row, col, byTouch = false, delayed = false)
    }

    private fun ask(row: Int, col: Int, byTouch: Boolean, delayed: Boolean) {
        val pending = question
        if (pending != null && pending.row == row && pending.col == col &&
            askedByTouch == byTouch
        ) {
            return
        }
        if (isShowing && this.row == row && this.col == col && askedByTouch == byTouch) return
        clear()
        askedByTouch = byTouch
        askedWithDelay = delayed
        generation++
        question = HoverQuestion(row, col, generation)
    }

    /** The pointer left, the caret moved, something was typed: no card. */
    fun clear(): Boolean {
        val was = isShowing || question != null
        text = ""
        row = -1
        question = null
        return was
    }

    private fun show(info: HoverInfo, row: Int, col: Int) {
        val flattened = markdownToText(info.contents)
        if (flattened.isEmpty()) {
            if (askedByTouch) onNothingToSay?.invoke()
            clear()
            return
        }
        text = flattened
        this.row = row
        this.col = col
        question = null
    }

    @Composable
    internal fun Poller() {
        val pending = question
        LaunchedEffect(pending) {
            if (pending == null) return@LaunchedEffect
            val session = editor.sessionOrNull ?: return@LaunchedEffect
            // The pointer's delay is Zed's; a gesture or an action that named
            // this by hand has already waited.
            if (askedWithDelay) delay(HOVER_DELAY_MILLIS)
            val answer = requestLsp(
                LspRequestKind.Hover,
                session.id,
                pending.row,
                pending.col,
            )
            if (answer == null) {
                if (askedByTouch) onNothingToSay?.invoke()
                clear()
                return@LaunchedEffect
            }
            // A hover's range points at *text*, so an answer dated against a
            // buffer that has moved describes columns that have moved with it.
            // Compared against the buffer as it is *now*, not as it was when
            // the question was asked: the engine stamps the request with the
            // version it saw at the start (lsp.rs:1335) and echoes that back,
            // so testing it against the same captured value is `x == x` and
            // guards nothing.
            if (!answer.describes(
                    session.id,
                    editor.bufferVersion,
                    pending.row,
                    pending.col,
                )
            ) {
                if (askedByTouch) onNothingToSay?.invoke()
                clear()
                return@LaunchedEffect
            }
            show(HoverInfo.parse(answer.payload), pending.row, pending.col)
        }
    }
}

@Composable
internal fun rememberHoverCard(state: EditorState): HoverCardState {
    val card = remember(state) { HoverCardState(state) }
    // Typing under a card makes it describe text that is no longer there.
    // Watched through [snapshotFlow] rather than passed as an effect key: a
    // key is read during composition, and this helper returns a value, so it
    // composes in the pane's own scope — keying on [EditorState.revision]
    // recomposed the whole pane on every keystroke.
    LaunchedEffect(state) {
        snapshotFlow { state.revision }.collect { card.clear() }
    }
    card.Poller()
    return card
}

/**
 * The card, placed by the same arithmetic as the completion menu — which is to
 * say above the caret's line whenever the keyboard has taken the bottom of the
 * screen. A long-press card in particular is *always* asked for with the IME
 * up, so this is not an edge case.
 */
@Composable
internal fun HoverCard(
    card: HoverCardState,
    anchorX: Float,
    anchorTop: Float,
    lineHeight: Float,
    areaWidth: Float,
    areaBottom: Float,
    /**
     * The card's "go to" rows — one per [GoToKind], the touch route to
     * Zed's four navigation actions. Null leaves the card read-only.
     */
    onGoTo: ((GoToKind) -> Unit)?,
    onDismiss: () -> Unit,
) {
    if (!card.isShowing) return
    val theme = LocalZedTheme.current
    val density = LocalDensity.current
    // As the completion menu: a pane narrower than the card is the card's
    // problem, not the pane's.
    val widthPx = with(density) { min(CARD_WIDTH.toPx(), areaWidth) }
    val placement = with(density) {
        placeMenuAtCaret(
            caretX = anchorX,
            caretTop = anchorTop,
            lineHeight = lineHeight,
            wantedWidth = widthPx,
            wantedHeight = CARD_MAX_HEIGHT.toPx(),
            minHeight = min(CARD_MAX_HEIGHT.toPx(), lineHeight * 3f),
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
            // Tapping the card itself dismisses it: on a phone there is no
            // "move the pointer away".
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = card.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
        )
        if (onGoTo != null) {
            HoverAction(label = "Go to definition", onClick = { onGoTo(GoToKind.Definition) })
            HoverAction(label = "Go to type definition", onClick = { onGoTo(GoToKind.TypeDefinition) })
            HoverAction(label = "Go to implementation", onClick = { onGoTo(GoToKind.Implementation) })
            HoverAction(label = "Go to declaration", onClick = { onGoTo(GoToKind.Declaration) })
        }
    }
}

@Composable
private fun HoverAction(label: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (hovered) theme.color("ghost_element.hover", Color.Transparent) else Color.Transparent
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
        )
    }
}

// ---- go to definition -------------------------------------------------------

/**
 * The four "go to" questions Zed's editor asks — `GoToDefinition` and its
 * siblings (crates/editor/src/navigation.rs `go_to_definition_of_kind`),
 * one request kind each and one payload shape between them.
 */
enum class GoToKind(internal val request: LspRequestKind, val title: String) {
    Definition(LspRequestKind.Definition, "definition"),
    TypeDefinition(LspRequestKind.TypeDefinition, "type definition"),
    Implementation(LspRequestKind.Implementation, "implementation"),
    Declaration(LspRequestKind.Declaration, "declaration"),
}

/** Where a definition was asked about. */
private data class DefinitionQuestion(
    val row: Int,
    val col: Int,
    val kind: GoToKind,
    val generation: Int,
)

/**
 * Go-to-definition: ask, then land.
 *
 * Landing is Zed's: unfold whatever hides the target *before* selecting it, put
 * a bare caret on the name and scroll it into view
 * (crates/editor/src/navigation.rs:1289-1300) — which is exactly what `F8`
 * already does for a diagnostic, through the same [EditorState] machinery.
 *
 * A target in another file cannot be opened from here: this pane has one
 * buffer and no way to make a second. [onOpenElsewhere] is the workspace's, and
 * the whole reason it is a callback — see the wiring note in the handover.
 */
@Stable
class DefinitionState internal constructor(private val editor: EditorState) {
    private var question: DefinitionQuestion? by mutableStateOf(null)
    private var generation = 0

    /**
     * Where a target in another file goes. A plain field rather than a
     * constructor argument because the workspace's lambda is rebuilt on every
     * composition, and a state remembered across them must read the current
     * one rather than the one it was born with.
     */
    internal var onOpenElsewhere: (DefinitionTarget) -> Unit = {}

    /**
     * Several answers — a trait with three implementations. Zed opens them
     * all in a multibuffer (navigation.rs:1805-1820); there is no multibuffer
     * here, so the pane's references list shows them, titled by the kind.
     */
    internal var onMultiple: (GoToKind, Int, Int, List<DefinitionTarget>) -> Unit = { _, _, _, _ -> }

    /** Ask about the symbol at (row, col) — Zed's `editor::GoToDefinition` and its kin. */
    fun goTo(row: Int, col: Int, kind: GoToKind = GoToKind.Definition) {
        if (editor.sessionOrNull == null) return
        generation++
        question = DefinitionQuestion(row, col, kind, generation)
    }

    /** Ask about the symbol the caret is on — what `F12` means. */
    fun goToCaret(kind: GoToKind = GoToKind.Definition) {
        goTo(editor.cursorRow, editor.cursorCol, kind)
    }

    /**
     * Land on [target]. In this file it is a caret move; anywhere else it is
     * the workspace's to open.
     */
    private fun reveal(target: DefinitionTarget) {
        val here = editor.sessionOrNull?.path
        if (here != null && here == target.path) {
            editor.revealDefinition(target.row, target.colUtf16)
        } else {
            onOpenElsewhere(target)
        }
    }

    @Composable
    internal fun Poller() {
        val pending = question
        LaunchedEffect(pending) {
            if (pending == null) return@LaunchedEffect
            val session = editor.sessionOrNull ?: return@LaunchedEffect
            val answer = requestLsp(
                pending.kind.request,
                session.id,
                pending.row,
                pending.col,
            )
            question = null
            if (answer == null) return@LaunchedEffect
            // A definition is about the symbol that was under the point when
            // it was asked: if the buffer has moved since, the answer is about
            // a name that may not be there any more — and jumping would throw
            // the caret out of the line being typed. Against the *current*
            // version, for the reason spelled out in the hover poller above.
            if (!answer.describes(
                    session.id,
                    editor.bufferVersion,
                    pending.row,
                    pending.col,
                )
            ) {
                return@LaunchedEffect
            }
            val targets = parseDefinitionTargets(answer.payload)
            when (targets.size) {
                0 -> return@LaunchedEffect
                // One answer is a jump. Zed's rule for one target
                // (navigation.rs:1789-1803): go there.
                1 -> reveal(targets[0])
                // More is a list — see [onMultiple].
                else -> onMultiple(pending.kind, pending.row, pending.col, targets)
            }
        }
    }
}

/**
 * Land on a definition in a file that has just been opened.
 *
 * Suspending for the same reason `revealProjectSearchMatch` is: a pane learns
 * its height in the draw pass, so a file opened a moment ago has no viewport
 * yet and `ensureCursorVisible` finds nothing to scroll within. Waiting two
 * frames — one for the composition that adds the pane, one for the draw that
 * measures it — and asking again is what puts line 900 on screen instead of
 * line 1.
 *
 * Call it from a composition's scope, which is where the frame clock lives.
 */
suspend fun EditorState.revealDefinitionTarget(target: DefinitionTarget) {
    revealDefinition(target.row, target.colUtf16)
    withFrameNanos { }
    withFrameNanos { }
    ensureCursorVisible()
}

@Composable
internal fun rememberDefinition(
    state: EditorState,
    onOpenElsewhere: (DefinitionTarget) -> Unit,
    onMultiple: (GoToKind, Int, Int, List<DefinitionTarget>) -> Unit,
): DefinitionState {
    val definition = remember(state) { DefinitionState(state) }
    definition.onOpenElsewhere = onOpenElsewhere
    definition.onMultiple = onMultiple
    definition.Poller()
    return definition
}
