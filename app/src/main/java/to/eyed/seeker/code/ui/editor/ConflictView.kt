package to.eyed.seeker.code.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.pollVersion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedTheme
import kotlin.math.roundToInt

/** How often the buffer's version is re-read for conflicts. The hunks' rate. */
private const val CONFLICT_POLL_MILLIS = 250L

/**
 * A branch label longer than this is cut, on a button that has two beside
 * it on a phone-width row. Zed's are full width on a desktop.
 */
private const val MAX_LABEL_CHARS = 14

/**
 * Keep [EditorState.conflicts] current: whenever the buffer's version moves,
 * re-read the regions off the main thread and publish them.
 *
 * Polled rather than derived from [EditorState.revision]: the buffer moves
 * from under the editor too — a reload, a workspace edit, an agent's write —
 * and the engine's version is the one counter every door bumps. One JNI
 * read of a long per tick while the version is still; a linear scan of the
 * text when it is not, on Default, never on the frame's thread.
 */
suspend fun pollBufferConflicts(state: EditorState) {
    val bufferId = state.sessionOrNull?.id ?: return
    pollVersion(
        intervalMs = CONFLICT_POLL_MILLIS,
        version = { CoreBridge.bufferVersion(bufferId) },
        read = { ConflictRegion.parseAll(CoreBridge.bufferConflicts(bufferId)) },
        apply = { state.showConflicts(it) },
    )
}

/**
 * The two colours a conflict's rows are painted in — Zed's
 * `version_control.conflict_marker.ours` and `.theirs`
 * (conflict_view.rs:299-300), which One Dark writes and most themes do not.
 * A theme without them gets the added colour for ours and the deleted one
 * for theirs, at the alpha One Dark gives its own (`#…1a`, a tenth): the
 * same wash the gutter's strip already speaks in, so the page reads as one
 * palette rather than two.
 */
class ConflictColours(val ours: Color, val theirs: Color) {
    companion object {
        private const val WASH_ALPHA = 0.1f

        fun from(theme: ZedTheme): ConflictColours = ConflictColours(
            ours = theme.color(
                "version_control.conflict_marker.ours",
                fallback = theme.color("version_control.added").copy(alpha = WASH_ALPHA),
            ),
            theirs = theme.color(
                "version_control.conflict_marker.theirs",
                fallback = theme.color("version_control.deleted").copy(alpha = WASH_ALPHA),
            ),
        )
    }
}

/**
 * Zed's conflict header, one per conflict on screen: `Use <ours>`,
 * `Use <theirs>`, `Use Both` (conflict_view.rs:334-380).
 *
 * Zed inserts it as a block *above* the `<<<<<<<` line. This editor's
 * display map has no block rows, so the buttons sit *on* that line instead,
 * against its right edge — the marker line is git's, not the user's, and
 * its text past the branch name is nothing anyone needs to read. They are
 * ordinary composables floated over the canvas, so a tap lands on a button
 * and never also places the caret under it.
 *
 * Composition reads the *rows* on screen through a [derivedStateOf], so
 * scrolling by a pixel does not recompose this; each header's own position
 * is read in its offset lambda, which is layout, not composition.
 */
@Composable
internal fun ConflictHeaders(
    state: EditorState,
    onResolve: (ConflictRegion, keepOurs: Boolean, keepTheirs: Boolean) -> Unit,
) {
    val conflicts = state.conflicts
    if (conflicts.isEmpty()) return
    val theme = LocalZedTheme.current
    // Which conflicts have their marker row on screen. The upper bound is
    // generous by a row on purpose — a header for a row a pixel off the
    // bottom costs nothing, and one missing from a row a pixel on it shows.
    val onScreen by remember(state, conflicts) {
        derivedStateOf {
            val first = state.firstDisplayRow()
            val last = state.lastDisplayRow(first)
            val map = state.displayMap
            val firstRow = map.bufferRowOf(first)
            val lastRow = map.bufferRowOf((last - 1).coerceAtLeast(first))
            conflicts.filter { it.startRow in firstRow..lastRow && !map.isRowHidden(it.startRow) }
        }
    }
    if (onScreen.isEmpty()) return
    val density = LocalDensity.current
    val rowHeight = with(density) { state.lineHeightPx.toDp() }
    // Clear of the scrollbar track, which is the canvas's own right edge.
    val trackWidth = with(density) { state.charWidthPx.coerceIn(10f, 24f).toDp() }
    Box(modifier = Modifier.fillMaxSize()) {
        for (conflict in onScreen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val top = state.displayRowOf(conflict.startRow, 0) * state.lineHeightPx -
                            state.scrollY
                        IntOffset(0, top.roundToInt())
                    }
                    .padding(end = trackWidth + 2.dp)
                    .height(rowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ConflictButton(
                    label = "Use ${conflict.oursBranchName.cut()}",
                    theme = theme,
                    onClick = { onResolve(conflict, true, false) },
                )
                ConflictButton(
                    label = "Use ${conflict.theirsBranchName.cut()}",
                    theme = theme,
                    onClick = { onResolve(conflict, false, true) },
                )
                ConflictButton(
                    label = "Use Both",
                    theme = theme,
                    onClick = { onResolve(conflict, true, true) },
                )
            }
        }
    }
}

private fun String.cut(): String =
    if (length <= MAX_LABEL_CHARS) this else take(MAX_LABEL_CHARS - 1) + "…"

/**
 * One of the header's buttons. Zed's are `Button::new(..).label_size(Small)`
 * on the editor background (conflict_view.rs:330-336); ours carry a border
 * as well, because they sit on a tinted row rather than a block of their
 * own and need an edge to read as buttons on it.
 */
@Composable
private fun ConflictButton(label: String, theme: ZedTheme, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = theme.color("text"),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color("editor.background"))
            .border(1.dp, theme.color("border"), RoundedCornerShape(4.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
