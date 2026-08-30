package to.eyed.seeker.code.ui.git

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.BlameLine
import to.eyed.seeker.code.core.GitHunk
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.ui.theme.LocalZedTheme

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

/** Zed's `GIT_BLAME_MAX_AUTHOR_CHARS_DISPLAYED` (blame_ui.rs). */
const val BLAME_MAX_AUTHOR_CHARS = 20

/**
 * The blame column's author: Zed truncates to 20 characters with an
 * ellipsis (`truncate_and_trailoff(author, GIT_BLAME_MAX_AUTHOR_CHARS_DISPLAYED)`,
 * blame_ui.rs:170), and a line nobody has committed reads as such.
 */
fun blameAuthor(entry: BlameLine): String {
    if (!entry.isCommitted) return "Uncommitted"
    val name = entry.author.ifBlank { "<no name>" }
    return if (name.length > BLAME_MAX_AUTHOR_CHARS) name.take(BLAME_MAX_AUTHOR_CHARS - 1) + "…" else name
}

/**
 * Zed's `u32::from(Oid)` — the sha's first four bytes as a number — which
 * indexes the theme's player colours so one commit's rows share a colour and
 * neighbouring commits mostly do not (element.rs:7019).
 */
fun shaIndex(sha: String): Int {
    val hex = sha.take(8).padEnd(8, '0')
    return hex.toLongOrNull(16)?.toInt() ?: sha.hashCode()
}

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

/**
 * Where the blame popover reads the commit's whole message and the
 * repository's remote from: the project, held by the workspace, which the
 * editor pane does not otherwise know about.
 */
class BlameHost(val project: ProjectSession) {
    private val session = GitSession(project)

    /** The commit's full message, or null when git could not say. **Blocking**. */
    fun message(sha: String): String? = session.commitDetails(sha)?.message

    /**
     * The commit's page on github.com when the remote is there — Zed's
     * "Open permalink to line" is per host provider; this app parses only
     * GitHub, as the graph does. Origin first, as Zed falls back to.
     * **Blocking**.
     */
    fun commitUrl(sha: String): String? {
        val remotes = session.remotes().remotes
        val remote = (remotes.firstOrNull { it.name == "origin" } ?: remotes.firstOrNull()) ?: return null
        return githubCommitUrl(remote.url, sha)
    }
}

/**
 * The blame entry's popover — Zed's `render_blame_entry_popover`
 * (blame_ui.rs:302-420): the author and relative date, the short sha, the
 * whole message once it has been read, and "View on GitHub" when the
 * remote parses. Anchored under the row that was tapped; a tap on it
 * dismisses it, as does the next tap in the column.
 */
@Composable
fun BlamePopover(
    line: BlameLine,
    host: BlameHost?,
    anchorY: Dp,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    var message by remember(line.sha) { mutableStateOf<String?>(null) }
    var url by remember(line.sha) { mutableStateOf<String?>(null) }
    LaunchedEffect(line.sha, host) {
        if (host == null || !line.isCommitted) return@LaunchedEffect
        val (text, link) = withContext(Dispatchers.IO) {
            host.message(line.sha) to host.commitUrl(line.sha)
        }
        message = text
        url = link
    }
    val now = remember { System.currentTimeMillis() / 1000L }
    Column(
        modifier = Modifier
            .offset(x = 8.dp, y = anchorY)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.color("elevated_surface.background", theme.color("background")))
            .border(1.dp, theme.color("border.variant"), RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "Close", onClick = onDismiss)
            .padding(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (line.isCommitted) line.author.ifBlank { "<no name>" } else "Uncommitted",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (line.isCommitted) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = relativeTime(line.authorTime, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                    maxLines = 1,
                )
            }
        }
        if (line.isCommitted) {
            Text(
                text = line.shortSha,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted"),
                modifier = Modifier.padding(top = 2.dp),
            )
            // The whole message — capped and scrolling, as Zed caps its
            // popover at 12 line-heights — falling back to the subject the
            // blame line itself carried while the details are on their way.
            Text(
                text = (message ?: line.summary).ifBlank { "(no message)" },
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text"),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            )
            url?.let { link ->
                Text(
                    // Zed says "View on {provider}" (blame_ui.rs:389-405).
                    text = "View on GitHub",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.accent"),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClickLabel = "View on GitHub") {
                            // The browser's job, not ours.
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
                            onDismiss()
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}
