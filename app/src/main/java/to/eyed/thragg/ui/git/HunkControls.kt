package to.eyed.thragg.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.thragg.core.GitHunk
import to.eyed.thragg.ui.theme.LocalZedTheme
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.GitSession
import to.eyed.thragg.core.ProjectSession

/**
 * The small things the editor's git surfaces are built from: the expanded
 * hunk's header buttons and their hit-testing, the banner git's refusals
 * land in, and the blame column's text rules and popover.
 *
 * The pane draws the hunk header itself on its canvas — it is a row of the
 * display map, scrolled with the text — so the buttons are not composables:
 * they are rectangles recorded as they are painted ([HunkHeaderHits]) and
 * looked up when a press lands. The popover and the banner *are*
 * composables, laid over the canvas, because they hold text that wraps and
 * a link that opens a browser.
 */

/** What one of an expanded hunk's header buttons does — Zed's hunk controls (editor/src/git.rs:3077-3175). */
enum class HunkHeaderAction { Stage, Unstage, Restore, Close }

/** One painted header button and the hunk it belongs to. */
data class HunkHeaderHit(val rect: Rect, val hunk: GitHunk, val action: HunkHeaderAction)

/**
 * The header buttons as the last draw pass laid them out, so the pixels and
 * the pointer can never disagree: cleared at the top of each frame, filled
 * as the headers are painted, read by the press handler.
 */
class HunkHeaderHits {
    private val hits = ArrayList<HunkHeaderHit>()

    fun clear() = hits.clear()

    fun add(rect: Rect, hunk: GitHunk, action: HunkHeaderAction) {
        hits.add(HunkHeaderHit(rect, hunk, action))
    }

    /** The button under [position], if a press there means one. */
    fun hitAt(position: Offset): HunkHeaderHit? = hits.firstOrNull { it.rect.contains(position) }
}

/**
 * The rows a hunk command is asked about: the hunk's own rows, or for a
 * deletion — which has none — the boundary row it is drawn against, which
 * the engine's inclusive `touches` matches to the deletion.
 */
fun IntRange.orBoundary(hunk: GitHunk): IntRange =
    if (isEmpty()) hunk.startRow..hunk.startRow else this

/**
 * What git said when a hunk command was refused — Zed's error toast — over
 * the top of the text until the next command or a tap.
 */
@Composable
fun HunkErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = theme.color("error"),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(theme.color("elevated_surface.background", theme.color("background")))
            .border(1.dp, theme.color("error.border", theme.color("border")), RoundedCornerShape(6.dp))
            .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
