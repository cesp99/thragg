package to.eyed.thragg.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import to.eyed.thragg.ui.theme.LocalZedTheme

/**
 * Signature help — the popover naming the call the caret is inside and the
 * parameter it is on (Zed's `editor::ShowSignatureHelp`,
 * crates/editor/src/signature_help.rs).
 *
 * Zed asks in three cases and so does this: a typed trigger character (`(`
 * and `,` for every server we ship, from the server's own
 * `signatureHelpProvider`), a retrigger character while the popover is up,
 * and the chord. While it shows, a caret move on the same row asks again —
 * the active parameter follows the caret across the arguments — and the
 * server answering "no signatures" is how leaving the call closes it,
 * which is Zed's `signature_help_state.hide()` on an empty response.
 */

private val HELP_WIDTH = 360.dp
private val HELP_MAX_HEIGHT = 180.dp
private val HELP_PADDING = 8.dp

/** Zed re-asks once the caret has settled; typing a trigger asks at once. */
private const val CARET_SETTLE_MILLIS = 120L

/** One parameter's span inside the signature's label, in UTF-16 offsets. */
data class SignatureParameter(val start: Int, val end: Int, val documentation: String?)

data class Signature(
    val label: String,
    val documentation: String?,
    val parameters: List<SignatureParameter>,
    /** Index into [parameters], or null when the server named none. */
    val activeParameter: Int?,
)

/** A settled answer; an empty [signatures] is "not in a call". */
data class SignatureHelpInfo(val signatures: List<Signature>, val activeSignature: Int) {
    val active: Signature? get() = signatures.getOrNull(activeSignature)

    companion object {
        val NONE = SignatureHelpInfo(emptyList(), 0)

        fun parse(payload: JSONObject?): SignatureHelpInfo {
            val array = payload?.optJSONArray("signatures") ?: return NONE
            val signatures = ArrayList<Signature>(array.length())
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val label = entry.optString("label", "")
                if (label.isEmpty()) continue
                val parameterArray = entry.optJSONArray("parameters")
                val parameters = ArrayList<SignatureParameter>(parameterArray?.length() ?: 0)
                for (j in 0 until (parameterArray?.length() ?: 0)) {
                    val parameter = parameterArray?.optJSONObject(j) ?: continue
                    parameters.add(
                        SignatureParameter(
                            start = parameter.optInt("start", 0),
                            end = parameter.optInt("end", 0),
                            documentation = parameter.textOrNull("documentation"),
                        )
                    )
                }
                signatures.add(
                    Signature(
                        label = label,
                        documentation = entry.textOrNull("documentation"),
                        parameters = parameters,
                        activeParameter = if (entry.isNull("active_parameter")) null else entry.optInt("active_parameter"),
                    )
                )
            }
            if (signatures.isEmpty()) return NONE
            return SignatureHelpInfo(
                signatures,
                payload.optInt("active_signature", 0).coerceIn(0, signatures.lastIndex),
            )
        }
    }
}

private fun JSONObject.textOrNull(name: String): String? =
    if (isNull(name)) null else optString(name, null)?.takeIf { it.isNotEmpty() }

/**
 * The signature's label with its active parameter emphasised — Zed marks
 * the active parameter bold in the popover (signature_help.rs,
 * `highlight_style` with `FontWeight::EXTRA_BOLD`). Pure, for the tests: a
 * parameter whose offsets fall outside the label is ignored rather than
 * thrown.
 */
fun signatureLabel(signature: Signature, bold: SpanStyle): AnnotatedString {
    val active = signature.activeParameter?.let(signature.parameters::getOrNull)
    return buildAnnotatedString {
        append(signature.label)
        if (active != null) {
            val start = active.start.coerceIn(0, signature.label.length)
            val end = active.end.coerceIn(start, signature.label.length)
            if (end > start) addStyle(bold, start, end)
        }
    }
}

private data class SignatureQuestion(val row: Int, val col: Int, val generation: Int)

@Stable
class SignatureHelpState internal constructor(private val editor: EditorState) {
    private var question: SignatureQuestion? by mutableStateOf(null)
    private var generation = 0

    var info: SignatureHelpInfo? by mutableStateOf(null)
        private set

    /** Where the popover hangs — the caret when it was last asked. */
    var row: Int = 0
        private set
    var col: Int = 0
        private set

    val isShowing: Boolean get() = info != null

    /**
     * Zed's `editor::ShowSignatureHelp` — a toggle: showing, it hides;
     * hidden, it asks at the caret (signature_help.rs `show_signature_help`
     * with `ShowSignatureHelp` from the keymap).
     */
    fun toggleAtCaret(): Boolean {
        if (editor.sessionOrNull == null) return false
        if (isShowing) {
            clear()
            return true
        }
        ask()
        return true
    }

    /** A typed character: a trigger asks, a retrigger re-asks while showing. */
    internal fun onTyped(text: String) {
        val triggers = editor.lspTriggers
        when {
            triggers.opensSignatureHelp(text) -> ask()
            isShowing && triggers.retriggersSignatureHelp(text) -> ask()
        }
    }

    /**
     * The caret moved while the popover is up: another row is another
     * call, so the popover goes; the same row is the same call with a
     * possibly different active parameter, so it is asked again.
     */
    internal fun caretMoved() {
        if (!isShowing) return
        if (editor.cursorRow != row) {
            clear()
            return
        }
        ask()
    }

    private fun ask() {
        if (editor.sessionOrNull == null) return
        generation++
        question = SignatureQuestion(editor.cursorRow, editor.cursorCol, generation)
    }

    fun clear(): Boolean {
        val was = isShowing || question != null
        question = null
        info = null
        return was
    }

    @Composable
    internal fun Poller() {
        val pending = question
        LaunchedEffect(pending) {
            if (pending == null) return@LaunchedEffect
            val session = editor.sessionOrNull ?: return@LaunchedEffect
            // A retrigger while a caret is still moving collapses into one
            // request, at the caret's resting place.
            if (isShowing) delay(CARET_SETTLE_MILLIS)
            val answer = requestLsp(
                LspRequestKind.SignatureHelp,
                session.id,
                pending.row,
                pending.col,
            )
            question = null
            if (answer == null || answer.state != LspRequestState.Done) return@LaunchedEffect
            // Positions in the label are about the label, not the buffer,
            // so a buffer that moved since is no reason to drop the answer
            // — but a caret that has left the row is.
            if (answer.bufferId != session.id || editor.cursorRow != pending.row) {
                return@LaunchedEffect
            }
            val parsed = SignatureHelpInfo.parse(answer.payload)
            if (parsed.signatures.isEmpty()) {
                info = null
                return@LaunchedEffect
            }
            row = pending.row
            col = pending.col
            info = parsed
        }
    }
}

@Composable
internal fun rememberSignatureHelp(state: EditorState): SignatureHelpState {
    val help = remember(state) { SignatureHelpState(state) }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.cursorRow, state.cursorCol, state.revision) }
            .collectLatest { help.caretMoved() }
    }
    help.Poller()
    return help
}

/**
 * The popover: the active signature with its active parameter bold, the
 * parameter's documentation under it, and "1 of 3" when the server offered
 * overloads. Anchored like the hover card; a tap on it dismisses it.
 */
@Composable
internal fun SignatureHelpPopup(
    help: SignatureHelpState,
    anchorX: Float,
    anchorTop: Float,
    lineHeight: Float,
    areaWidth: Float,
    areaBottom: Float,
    onDismiss: () -> Unit,
) {
    val info = help.info ?: return
    val signature = info.active ?: return
    val theme = LocalZedTheme.current
    val density = LocalDensity.current
    val widthPx = with(density) { min(HELP_WIDTH.toPx(), areaWidth) }
    val placement = with(density) {
        placeMenuAtCaret(
            caretX = anchorX,
            caretTop = anchorTop,
            lineHeight = lineHeight,
            wantedWidth = widthPx,
            wantedHeight = HELP_MAX_HEIGHT.toPx(),
            minHeight = min(HELP_MAX_HEIGHT.toPx(), lineHeight * 2f),
            areaWidth = areaWidth,
            areaTop = 0f,
            areaBottom = areaBottom,
        )
    }
    val shape = RoundedCornerShape(8.dp)
    val bold = SpanStyle(fontWeight = FontWeight.ExtraBold, color = theme.color("text.accent", MaterialTheme.colorScheme.primary))
    val activeParameter = signature.activeParameter?.let(signature.parameters::getOrNull)
    Column(
        modifier = Modifier
            .offset { IntOffset(placement.x.roundToInt(), placement.y.roundToInt()) }
            .width(with(density) { widthPx.toDp() })
            .heightIn(max = with(density) { placement.height.toDp() })
            .clip(shape)
            .background(theme.color("elevated_surface.background"))
            .border(1.dp, theme.color("border.variant"), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(HELP_PADDING)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = signatureLabel(signature, bold),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
        val documentation = activeParameter?.documentation ?: signature.documentation
        if (!documentation.isNullOrEmpty()) {
            Text(
                text = markdownToText(documentation).trim(),
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        if (info.signatures.size > 1) {
            Text(
                text = "${info.activeSignature + 1} of ${info.signatures.size}",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
