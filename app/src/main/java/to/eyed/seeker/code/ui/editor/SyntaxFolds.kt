package to.eyed.seeker.code.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import to.eyed.seeker.code.core.CoreBridge

/**
 * Where the fold ranges come from, in order of authority.
 *
 * The server's `textDocument/foldingRange` first, when the buffer's server
 * offers it; the syntax tree next — the engine's `bufferFoldRanges`, from
 * the grammar's own indent captures; and the indentation walk
 * ([IndentFolds]) last, for a language with no grammar or a grammar with
 * no `indents.scm`, which is what Zed itself falls back to when nothing
 * better is known (`crease_for_buffer_row`, display_map.rs:2317-2430).
 *
 * The first two are lists the pane holds; the walk is computed on demand.
 * [chooseFolds] is the whole of the arbitration, and pure, so the tests
 * can pin it: an empty list from a source is "that source knows nothing",
 * never "there is nothing to fold".
 */

/** The engine's `[{start_row, end_row}]` — `bufferFoldRanges`'s shape. */
internal fun parseFoldRanges(json: String?): List<FoldRange> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        foldRangesOf(JSONArray(json))
    } catch (_: JSONException) {
        emptyList()
    }
}

/** The server's answer, `{ranges: [{start_row, end_row}]}`. */
internal fun parseFoldingRangesPayload(payload: JSONObject?): List<FoldRange> {
    val array = payload?.optJSONArray("ranges") ?: return emptyList()
    return foldRangesOf(array)
}

private fun foldRangesOf(array: JSONArray): List<FoldRange> {
    val ranges = ArrayList<FoldRange>(array.length())
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val start = entry.optInt("start_row", -1)
        val end = entry.optInt("end_row", -1)
        if (start < 0 || end <= start) continue
        ranges.add(FoldRange(start, end))
    }
    ranges.sortWith(compareBy({ it.startRow }, { it.endRow }))
    return ranges
}

/**
 * The list that answers: the server's when it has one, else the tree's,
 * else null — "walk the indentation". Ranges are clipped to the buffer as
 * it stands, because both sources describe the text as it was a reparse
 * or a round trip ago and a fold past the last row is a crash later.
 */
internal fun chooseFolds(
    server: List<FoldRange>?,
    syntax: List<FoldRange>?,
    rowCount: Int,
): List<FoldRange>? {
    val chosen = server?.takeIf { it.isNotEmpty() } ?: syntax?.takeIf { it.isNotEmpty() } ?: return null
    val last = rowCount - 1
    return chosen.mapNotNull { range ->
        if (range.startRow >= last) return@mapNotNull null
        val end = range.endRow.coerceAtMost(last)
        if (end > range.startRow) FoldRange(range.startRow, end) else null
    }
}

/** The fold whose chip sits on [row], or null. [folds] is sorted by start row. */
internal fun foldStartingAt(folds: List<FoldRange>, row: Int): FoldRange? {
    var low = 0
    var high = folds.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (folds[mid].startRow < row) low = mid + 1 else high = mid
    }
    return folds.getOrNull(low)?.takeIf { it.startRow == row }
}

/**
 * Zed's `editor::Fold` at a caret (fold.rs:195-204): walk up from the
 * caret's row and take the first fold that reaches back down to it — the
 * innermost block containing the caret.
 */
internal fun foldContaining(folds: List<FoldRange>, row: Int): FoldRange? {
    var best: FoldRange? = null
    for (fold in folds) {
        if (fold.startRow > row) break
        if (fold.endRow >= row && (best == null || fold.startRow >= best.startRow)) best = fold
    }
    return best
}

/**
 * Zed's `editor::Fold` over a selection (fold.rs:167-193): every fold
 * starting inside the rows, outermost first, skipping the ones an earlier
 * fold already swallowed.
 */
internal fun foldsWithin(folds: List<FoldRange>, rows: IntRange): List<FoldRange> {
    val picked = ArrayList<FoldRange>()
    var next = rows.first
    for (fold in folds) {
        if (fold.startRow < next) continue
        if (fold.startRow > rows.last) break
        picked.add(fold)
        next = fold.endRow + 1
    }
    return picked
}

/**
 * Keep the pane's known folds current: the tree's on every reparse the
 * highlight poll notices, and the server's — asked on the same cadence,
 * kept only when it describes the buffer as it stands. `collectLatest`,
 * so a burst of reparses reads once, at the end.
 */
@Composable
internal fun rememberSyntaxFolds(state: EditorState) {
    LaunchedEffect(state) {
        snapshotFlow { state.highlightVersion }.collectLatest {
            val session = state.sessionOrNull ?: return@collectLatest
            val syntax = withContext(Dispatchers.Default) {
                parseFoldRanges(CoreBridge.bufferFoldRanges(session.id))
            }
            // The server's last answer is about the text before this
            // reparse; the tree's is fresh. Replace, then ask again.
            state.setKnownFolds(syntax = syntax, server = null)
            if (!state.lspTriggers.foldingRanges) return@collectLatest
            val id = withContext(Dispatchers.Default) {
                CoreBridge.lspRequestFoldingRanges(session.id)
            }
            val answer = pollLspRequest(id) ?: return@collectLatest
            if (answer.state != LspRequestState.Done) return@collectLatest
            if (answer.bufferId != session.id || answer.bufferVersion != state.bufferVersion) {
                return@collectLatest
            }
            state.setKnownFolds(syntax = syntax, server = parseFoldingRangesPayload(answer.payload))
        }
    }
}
