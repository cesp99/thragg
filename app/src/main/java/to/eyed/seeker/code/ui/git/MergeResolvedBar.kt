package to.eyed.seeker.code.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import to.eyed.seeker.code.core.GitFileStatus
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.pollVersion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.rem

/** How often git's status counter is re-read for the conflicted set. */
private const val STATUS_POLL_MILLIS = 500L

/**
 * The project-relative paths git currently calls conflicted, kept current
 * the way the project panel keeps its colours: the status counter is
 * polled off the main thread, and the table re-read only when it moves.
 * Empty with no project, no repository, or no userland to run git in.
 */
@Composable
fun rememberConflictedPaths(project: ProjectSession?): Set<String> {
    var paths by remember(project) { mutableStateOf(emptySet<String>()) }
    ResumedEffect(project) {
        if (project == null) return@ResumedEffect
        pollVersion(
            intervalMs = STATUS_POLL_MILLIS,
            version = { project.gitStatusVersion },
            read = {
                project.gitStatus()
                    .filterValues { it == GitFileStatus.Conflicted }
                    .keys
            },
            apply = { paths = it },
        )
    }
    return paths
}

/**
 * The strip over a file git still calls conflicted once its last marker is
 * gone: the offer to stage it, which is how git is told a conflict is
 * resolved.
 *
 * Zed has no such strip — its panel's checkbox on a conflicted entry stages
 * it, and that is the whole ceremony — but this panel keeps that checkbox
 * off a conflict (staging one *with* markers in it would be recording the
 * markers), so the moment the markers are gone needs a way to say so from
 * where the user is, which is the editor. Staging saves first: git reads
 * the file on disk, and a resolution that only exists in the buffer would
 * stage the conflict it replaced.
 */
@Composable
fun MergeResolvedBar(
    fileName: String,
    /** What the last attempt to stage said, or null. */
    error: String?,
    onStage: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rem(1f)),
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = rem(0.875f), vertical = rem(0.5f)),
    ) {
        Text(
            text = error ?: "No conflicts left in $fileName",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) theme.color("error") else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        BarAction("Mark resolved (stage)", onStage)
        BarAction("Dismiss", onDismiss)
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = rem(0.25f), vertical = rem(0.125f)),
    )
}
