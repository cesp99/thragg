package to.eyed.thragg.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.InlayHintSettings

/**
 * Inlay hints — the `: i32` after a binding and the `name:` before an
 * argument that a language server offers and Zed draws dimmed inline
 * (crates/editor/src/inlay_hint_cache.rs, drawn through the display map's
 * inlay layer).
 *
 * Two halves. The *request* half ([rememberInlayHints]) asks the engine for
 * the visible rows' hints, debounced the way Zed debounces
 * (`edit_debounce_ms` 700, `scroll_debounce_ms` 50 — assets/settings/
 * default.json:806-811), and hands the answer to [EditorState] once it is
 * sure the answer describes the text on screen. The *display* half
 * ([spliceInlays]) is pure: it turns one row's text and its hints into the
 * string to lay out, and the two mappings between buffer columns and
 * positions in that string, so that every pixel the pane measures — the
 * caret, a selection, a squiggle, a tap — lands on real text while the hint
 * merely takes up room. A hint is never in the buffer: it lives in the
 * layout alone, exactly as Zed's inlays live in the display map and not in
 * the buffer.
 */

/** Zed's `edit_debounce_ms` (assets/settings/default.json:808). */
private const val EDIT_DEBOUNCE_MILLIS = 700L

/** Zed's `scroll_debounce_ms` (assets/settings/default.json:811). */
private const val SCROLL_DEBOUNCE_MILLIS = 50L

/**
 * One hint, where it hangs and what it says. [col] is the UTF-16 column the
 * hint sits *before*: a type hint at the end of `let x` has the column of
 * the character after `x`, a parameter hint the column of the argument's
 * first character.
 */
data class InlayHint(
    val row: Int,
    val col: Int,
    val label: String,
    /** `type`, `parameter`, or null — Zed's `InlayHintKind`. */
    val kind: String?,
    val paddingLeft: Boolean,
    val paddingRight: Boolean,
) {
    /**
     * What is drawn: the label with the spaces the server asked for on each
     * side, which is how Zed pads too (`padding_left` / `padding_right`,
     * lsp_command.rs:3953-3962).
     */
    val text: String
        get() = buildString {
            if (paddingLeft) append(' ')
            append(label)
            if (paddingRight) append(' ')
        }
}

/** The engine's `{hints: [...]}` payload, as rows; garbage is no hints. */
fun parseInlayHints(payload: JSONObject?): List<InlayHint> {
    val array = payload?.optJSONArray("hints") ?: return emptyList()
    val hints = ArrayList<InlayHint>(array.length())
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val label = entry.optString("label", "")
        if (label.isEmpty()) continue
        hints.add(
            InlayHint(
                row = entry.optInt("row", 0),
                col = entry.optInt("col_utf16", 0),
                label = label,
                kind = if (entry.isNull("kind")) null else entry.optString("kind", null),
                paddingLeft = entry.optBoolean("padding_left", false),
                paddingRight = entry.optBoolean("padding_right", false),
            )
        )
    }
    return hints
}

/**
 * The hints of one buffer, grouped by row and sorted within a row by column
 * — parameter hints before type hints at the same column, since a parameter
 * hint anchors *before* its position and a type hint *after* it
 * (`anchor_before` / `anchor_after`, lsp_command.rs:3942-3948).
 */
fun groupInlayHints(hints: List<InlayHint>, settings: InlayHintSettings): Map<Int, List<InlayHint>> {
    if (hints.isEmpty()) return emptyMap()
    val byRow = HashMap<Int, MutableList<InlayHint>>()
    for (hint in hints) {
        if (!settings.shows(hint.kind)) continue
        byRow.getOrPut(hint.row) { ArrayList(2) }.add(hint)
    }
    for (list in byRow.values) {
        list.sortWith(compareBy<InlayHint> { it.col }.thenBy { if (it.kind == "parameter") 0 else 1 })
    }
    return byRow
}

/**
 * One display segment with its hints spliced in: the text to measure, and
 * the two directions of the column mapping.
 *
 * [text] is the segment's own characters with each hint's [InlayHint.text]
 * inserted before the character at the hint's column. [hintRanges] are the
 * hint runs inside it, in display offsets, for the muted colour.
 */
class SplicedSegment internal constructor(
    val text: String,
    val hintRanges: List<IntRange>,
    /** Segment-relative buffer column → display offset of each hint's start. */
    private val hintCols: IntArray,
    private val hintLengths: IntArray,
) {
    val hasHints: Boolean get() = hintCols.isNotEmpty()

    /**
     * The display offset of a segment-relative buffer column: the column
     * plus every hint that sits at or before it. A hint at exactly this
     * column comes *before* the character, so the column lands after it —
     * which puts the caret after `: i32` when it stands after `x`, where
     * Zed puts it (a type hint's anchor is after its position).
     */
    fun toDisplay(col: Int): Int {
        var shift = 0
        for (i in hintCols.indices) {
            if (hintCols[i] <= col) shift += hintLengths[i] else break
        }
        return col + shift
    }

    /**
     * The display offset of a buffer column counting only the hints
     * strictly *before* it — what the end of a span or a selection wants,
     * so a highlight that ends at `x` does not spill over the `: i32`
     * hanging off it.
     */
    fun toDisplayBefore(col: Int): Int {
        var shift = 0
        for (i in hintCols.indices) {
            if (hintCols[i] < col) shift += hintLengths[i] else break
        }
        return col + shift
    }

    /**
     * The segment-relative buffer column of a display offset. An offset
     * inside a hint belongs to the hint's own column — a tap on the hint
     * lands on the text it annotates, never inside it.
     */
    fun toBuffer(displayOffset: Int): Int {
        var shift = 0
        for (i in hintCols.indices) {
            val hintStart = hintCols[i] + shift
            if (displayOffset < hintStart) break
            if (displayOffset < hintStart + hintLengths[i]) return hintCols[i]
            shift += hintLengths[i]
        }
        return displayOffset - shift
    }

    companion object {
        /** A segment with nothing spliced: identity both ways. */
        internal fun plain(text: String) = SplicedSegment(text, emptyList(), IntArray(0), IntArray(0))
    }
}

/**
 * [spans] — one segment's highlight spans, segment-relative — moved to
 * where their text sits once the hints are spliced in: a span starting at
 * a hint's column starts after the hint, a span ending there ends before
 * it, so the hint itself is never painted in the colour of its neighbour.
 */
fun SplicedSegment.shiftSpans(spans: List<HighlightSpan>): List<HighlightSpan> {
    if (!hasHints || spans.isEmpty()) return spans
    val shifted = ArrayList<HighlightSpan>(spans.size)
    for (span in spans) {
        val start = toDisplay(span.start)
        val end = toDisplayBefore(span.end)
        if (end > start) shifted.add(HighlightSpan(start, end, span.style))
    }
    return shifted
}

/**
 * Splice [hints] into the segment `[start, end)` of [line]. Hints outside
 * the segment are left out; a hint at exactly [end] belongs to the segment
 * that ends there (the type hint after the last word of a wrapped row shows
 * on that row), except at a segment that is not the row's last, where it
 * would show twice.
 */
fun spliceInlays(
    line: String,
    start: Int,
    end: Int,
    hints: List<InlayHint>,
): SplicedSegment {
    val segmentEnd = end.coerceIn(start, line.length)
    val segment = if (start <= 0 && segmentEnd >= line.length) line else line.substring(start, segmentEnd)
    if (hints.isEmpty()) return SplicedSegment.plain(segment)
    val isLast = segmentEnd >= line.length
    val inSegment = hints.filter { hint ->
        val col = hint.col
        col >= start && (col < segmentEnd || (isLast && col == segmentEnd))
    }
    if (inSegment.isEmpty()) return SplicedSegment.plain(segment)
    val out = StringBuilder(segment.length + inSegment.sumOf { it.text.length })
    val ranges = ArrayList<IntRange>(inSegment.size)
    val cols = IntArray(inSegment.size)
    val lengths = IntArray(inSegment.size)
    var consumed = 0
    for ((index, hint) in inSegment.withIndex()) {
        val col = (hint.col - start).coerceIn(consumed, segment.length)
        out.append(segment, consumed, col)
        consumed = col
        val hintStart = out.length
        out.append(hint.text)
        ranges.add(hintStart until out.length)
        cols[index] = col
        lengths[index] = hint.text.length
    }
    out.append(segment, consumed, segment.length)
    return SplicedSegment(out.toString(), ranges, cols, lengths)
}

/**
 * The request half: ask for the visible rows' hints whenever the text,
 * the viewport or the settings move, and install what comes back.
 *
 * An edit clears the hints at once — their columns describe text that has
 * moved, and Zed's answer to that (anchors that ride the edit) is not
 * available to a pane that holds rows and columns — and asks again after
 * the edit debounce; a scroll keeps what it has and asks after the scroll
 * debounce, so the rows scrolled into view fill in. With hints switched
 * off nothing is asked and nothing is held.
 */
@Composable
internal fun rememberInlayHints(state: EditorState, settings: InlayHintSettings) {
    LaunchedEffect(state, settings) {
        if (!settings.enabled) {
            state.setInlayHints(emptyMap())
            return@LaunchedEffect
        }
        var lastRevision = state.revision
        snapshotFlow {
            InlayAsk(state.revision, state.visibleBufferRowRange())
        }.collectLatest { ask ->
            val session = state.sessionOrNull ?: return@collectLatest
            val edited = ask.revision != lastRevision
            lastRevision = ask.revision
            if (edited) state.setInlayHints(emptyMap())
            delay(if (edited) EDIT_DEBOUNCE_MILLIS else SCROLL_DEBOUNCE_MILLIS)
            val id = withContext(Dispatchers.Default) {
                CoreBridge.lspRequestInlayHints(
                    session.id,
                    ask.rows.first.toLong(),
                    ask.rows.last.toLong(),
                )
            }
            val answer = pollLspRequest(id) ?: return@collectLatest
            if (answer.state != LspRequestState.Done) return@collectLatest
            // Against the buffer as it is *now*: a hint for text that has
            // moved since is a hint drawn in the wrong place.
            if (answer.bufferId != session.id || answer.bufferVersion != state.bufferVersion) {
                return@collectLatest
            }
            state.setInlayHints(groupInlayHints(parseInlayHints(answer.payload), settings))
        }
    }
}

private data class InlayAsk(val revision: Int, val rows: IntRange)
