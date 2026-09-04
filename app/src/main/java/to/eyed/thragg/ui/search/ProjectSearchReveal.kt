package to.eyed.thragg.ui.search

import androidx.compose.runtime.withFrameNanos
import to.eyed.thragg.core.ProjectSearchMatch
import to.eyed.thragg.ui.editor.EditorState

/**
 * Put the caret on a project-search hit, in the buffer that hit lives in.
 *
 * The panel cannot do this itself — it never opens a file — so the workspace
 * opens the file and hands the buffer here. The engine gives the hit as a
 * 1-based line and a *byte* column, and the renderer works in 0-based rows and
 * UTF-16 columns, so the conversion happens against the buffer's own line
 * rather than against the (possibly windowed) text the result row drew.
 *
 * Suspending because of the scroll: a pane learns its height in the draw pass,
 * so a file this call has just opened has no viewport yet and the selection's
 * own `ensureCursorVisible` finds nothing to scroll within. Waiting two frames
 * — one for the composition that adds the pane, one for the draw that measures
 * it — and asking again is what puts line 900 on screen instead of line 1.
 *
 * Call it from a composition's scope ([androidx.compose.runtime.rememberCoroutineScope]),
 * which is where the frame clock the wait needs lives.
 */
suspend fun EditorState.revealProjectSearchMatch(match: ProjectSearchMatch) {
    val row = (match.line - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    val text = line(row)
    val start = utf16Col(text, match.column)
    val end = utf16Col(text, match.column + (match.end - match.start)).coerceAtLeast(start)
    selectRange(EditorState.SelectionRange(row, start, row, end))
    withFrameNanos { }
    withFrameNanos { }
    ensureCursorVisible()
}
