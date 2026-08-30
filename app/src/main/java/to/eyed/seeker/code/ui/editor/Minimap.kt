package to.eyed.seeker.code.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Zed's minimap (`minimap` in assets/settings/default.json:639-680): a
 * downscaled rendering of the file down the right edge, with a thumb marking
 * the viewport.
 *
 * Zed draws it as real text at a tiny font size. Here it is per-line coloured
 * rectangles built from the same highlight spans the editor already has —
 * "no glyphs, the shape of the code", which is what a phone-sized minimap can
 * actually show and what the caller reads it for. The spans come from the
 * engine on a background dispatcher, never in the draw pass.
 */

/** How tall one minimap row is, in density-independent pixels. */
internal const val MINIMAP_ROW_DP = 2f

/** One run of one row, in the colour its syntax style paints. */
internal data class MinimapRun(val startCol: Int, val endCol: Int, val style: Int)

/**
 * What the minimap draws: the rows starting at [firstRow], each as its runs.
 * A run with a style of -1 is plain text — the row's own extent, which is
 * what gives the map the shape of the code.
 */
internal data class MinimapContent(
    val firstRow: Int,
    val rows: List<List<MinimapRun>>,
) {
    companion object {
        val EMPTY = MinimapContent(0, emptyList())
    }
}

/**
 * Which rows the minimap shows, given where the editor is.
 *
 * A file that fits starts at its first row. A longer one scrolls
 * proportionally: at the top of the file the minimap is at the top, at the
 * bottom it is at the bottom, and in between the two move together — Zed's
 * own `minimap_scroll_top` arithmetic, which is what keeps the thumb inside
 * the map at every position.
 */
internal fun minimapFirstRow(
    topRow: Int,
    viewportRows: Int,
    minimapRows: Int,
    totalRows: Int,
): Int {
    if (totalRows <= minimapRows) return 0
    val scrollable = (totalRows - viewportRows).coerceAtLeast(1)
    val progress = (topRow.toFloat() / scrollable).coerceIn(0f, 1f)
    return ((totalRows - minimapRows) * progress).toInt().coerceAtLeast(0)
}

/**
 * The minimap's content for the rows it currently shows, refreshed off the
 * main thread whenever the viewport or the text moves.
 *
 * `collectLatest`, so a fling reads once at the end of it rather than once
 * per frame; the previous content stays on screen until the new one lands,
 * which reads as a map that lags a moment rather than one that blinks.
 */
@Composable
internal fun rememberMinimap(
    state: EditorState,
    enabled: Boolean,
    /** How tall one minimap row is, in pixels. */
    rowHeightPx: Float,
): State<MinimapContent> {
    val content = remember(state) { mutableStateOf(MinimapContent.EMPTY) }
    LaunchedEffect(state, enabled, rowHeightPx) {
        if (!enabled || rowHeightPx <= 0f) {
            content.value = MinimapContent.EMPTY
            return@LaunchedEffect
        }
        snapshotFlow {
            // The viewport's height is a plain field the draw pass writes, so
            // its generation is what tells this flow the pane was measured.
            val measured = state.measuredGeneration
            val rows = (state.viewportHeightPx / rowHeightPx).toInt()
            val first = minimapFirstRow(
                topRow = state.topVisibleRow(),
                viewportRows = state.viewportRows(),
                minimapRows = rows,
                totalRows = state.lineCount,
            )
            Triple(first, rows, measured + state.revision)
        }.collectLatest { (first, rows, _) ->
            val last = (first + rows).coerceAtMost(state.lineCount)
            if (rows <= 0 || last <= first) {
                content.value = MinimapContent.EMPTY
                return@collectLatest
            }
            content.value = withContext(Dispatchers.Default) {
                runCatching { readMinimap(state, first, last) }.getOrDefault(MinimapContent.EMPTY)
            }
        }
    }
    return content
}

/**
 * The rows `[firstRow, lastRow)` as minimap runs: one plain run per row from
 * its indent to its end, and one coloured run per highlight span over it.
 *
 * Reads the buffer directly rather than the pane's window cache — this runs
 * off the main thread, and the cache is the draw pass's.
 */
private fun readMinimap(state: EditorState, firstRow: Int, lastRow: Int): MinimapContent {
    val lines = state.buffer.lines(firstRow, lastRow).split('\n')
    val rows = List(lastRow - firstRow) { index ->
        val runs = mutableListOf<MinimapRun>()
        val line = lines.getOrNull(index).orEmpty()
        val indent = line.indexOfFirst { it != ' ' && it != '\t' }
        // A blank row draws nothing at all, which is what makes the gaps
        // between blocks legible on a two-pixel-tall row.
        if (indent >= 0) runs.add(MinimapRun(indent, line.length, -1))
        runs
    }
    val flat = state.buffer.highlights(firstRow, lastRow)
    if (flat != null) {
        var i = 0
        while (i + 3 < flat.size) {
            rows.getOrNull(flat[i] - firstRow)
                ?.add(MinimapRun(flat[i + 1], flat[i + 2], flat[i + 3]))
            i += 4
        }
    }
    return MinimapContent(firstRow, rows)
}
