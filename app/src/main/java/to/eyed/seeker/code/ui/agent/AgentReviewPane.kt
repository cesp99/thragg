package to.eyed.seeker.code.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.seeker.code.core.AgentEditedFile
import to.eyed.seeker.code.core.AgentReview
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.pollVersion
import to.eyed.seeker.code.ui.git.DiffLineRow
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/** How often the tab looks for news; the same beat as the panel. */
private const val POLL_MS = 250L

/**
 * Every edit the agent made in the active thread, in one tab — Zed's agent
 * diff (agent_ui/src/agent_diff.rs), the multibuffer its "Review Changes"
 * button and `agent::OpenAgentDiff` open, with Keep and Reject on each
 * file and Keep All / Reject All over the lot (agent_diff.rs:832-856,
 * 1157-1171).
 *
 * Per file rather than per hunk, because that is the decision a phone can
 * take: each file is its earliest pre-edit text diffed against what it
 * holds now, drawn by the rows the git diff tab draws. **Reject** puts the
 * file back as it was; **Keep** takes it out of the review and leaves the
 * checkpoint on the message that made it, so a kept edit is still one
 * "Restore checkpoint" away.
 *
 * A tab in the work area, where Zed opens it as a pane item, so it can sit
 * beside the file it is about. It follows the *active* thread: the review
 * is a view of the conversation the panel is showing.
 */
@Composable
fun AgentReviewPane(
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val sessionId = AgentSessions.sessionId.takeIf { it >= 0 }
    var review by remember(sessionId) { mutableStateOf(AgentReview.NONE) }
    ResumedEffect(sessionId) {
        if (sessionId == null) return@ResumedEffect
        pollVersion(
            intervalMs = POLL_MS,
            version = { CoreBridge.acpSessionVersion(sessionId) },
            read = { AgentReview.parse(CoreBridge.acpEditedFiles(sessionId)) },
            apply = { review = it },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background", theme.color("background"))),
    ) {
        ReviewToolbar(
            pending = review.pending.size,
            onKeepAll = { AgentSessions.keepEdits(emptyList()) },
            onRejectAll = { AgentSessions.rejectEdits(emptyList()) },
            onDismiss = onDismiss,
        )
        HorizontalDivider(color = theme.color("border.variant", theme.color("border")))
        when {
            sessionId == null -> Notice("No agent thread is open.")
            review.files.isEmpty() -> Notice(
                "No changes to review. Edits the agent makes through the panel appear here.",
            )
            else -> ReviewBody(review, onOpenFile)
        }
    }
}

/** The title, the count, and Zed's two bulk buttons. */
@Composable
private fun ReviewToolbar(
    pending: Int,
    onKeepAll: () -> Unit,
    onRejectAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("toolbar.background", theme.color("editor.background")))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (pending == 0) "Review changes" else "Review changes — $pending pending",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (pending > 0) {
            // Zed's order: Reject All, then Keep All (agent_diff.rs:1157-1171).
            ReviewAction("Reject all", destructive = true, onClick = onRejectAll)
            ReviewAction("Keep all", onClick = onKeepAll)
        }
        ReviewAction("✕", onClick = onDismiss)
    }
}

@Composable
private fun ReviewBody(review: AgentReview, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val bufferFont = BufferFontFamily
    val code = remember(settings.bufferFontSize, bufferFont) {
        TextStyle(
            fontFamily = bufferFont,
            fontSize = settings.bufferFontSize.sp,
            lineHeight = (settings.bufferFontSize * 1.618034f).sp,
        )
    }
    // One horizontal scroll shared by every row, sized to the longest line —
    // DiffBody's arrangement, and its reason (DiffPane.kt).
    val across = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val contentWidth = remember(review, code) {
        val longest = review.files.asSequence()
            .flatMap { it.diff.hunks.asSequence() }
            .flatMap { it.lines.asSequence() }
            .maxOfOrNull { it.text.length + 1 } ?: 0
        (longest * measurer.measure("M", code).size.width).coerceAtLeast(1)
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        for ((fileIndex, file) in review.files.withIndex()) {
            item(key = "file:${file.path}") {
                FileRow(file, onOpenFile)
            }
            when {
                file.diff.isBinary -> item(key = "binary:$fileIndex") {
                    Notice("Binary file — nothing to show line by line.")
                }
                file.diff.hunks.isEmpty() -> item(key = "empty:$fileIndex") {
                    Notice(
                        when {
                            file.deleted -> "The file was deleted."
                            file.created -> "An empty file was created."
                            else -> "No difference from what it held before."
                        },
                    )
                }
                else -> for ((hunkIndex, hunk) in file.diff.hunks.withIndex()) {
                    item(key = "hunk:$fileIndex:$hunkIndex") {
                        Text(
                            text = "@@ -${hunk.oldStart},${hunk.oldCount} " +
                                "+${hunk.newStart},${hunk.newCount} @@ ${hunk.heading}".trimEnd(),
                            style = code.copy(fontSize = settings.bufferFontSize.sp * 0.85f),
                            color = theme.color("text.muted"),
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(theme.color("element.background", theme.color("border.variant")))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    itemsIndexed(
                        items = hunk.lines,
                        key = { at, _ -> "line:$fileIndex:$hunkIndex:$at" },
                    ) { _, line ->
                        DiffLineRow(line, code, across, contentWidth)
                    }
                }
            }
        }
    }
}

/** One file's header: the path, its counts, and Zed's two per-file buttons. */
@Composable
private fun FileRow(file: AgentEditedFile, onOpenFile: (String) -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (hovered) theme.color("element.hover", Color.Transparent) else Color.Transparent,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = file.path,
                ) { onOpenFile(file.path) },
        ) {
            Text(
                text = file.path,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = when {
                        file.deleted -> "deleted"
                        file.created -> "created"
                        else -> "edited"
                    } + if (!file.isPending) " · kept" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted"),
                )
                Text(
                    text = "+${file.diff.added}",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("created", theme.color("text.muted")),
                )
                Text(
                    text = "−${file.diff.removed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("deleted", theme.color("text.muted")),
                )
            }
        }
        if (file.isPending) {
            // Zed's per-row order: Reject, then Keep (agent_diff.rs:832-856).
            ReviewAction("Reject", destructive = true) {
                AgentSessions.rejectEdits(listOf(file.path))
            }
            ReviewAction("Keep") { AgentSessions.keepEdits(listOf(file.path)) }
        }
    }
}

/** A word-button: the review's controls are words, at this size. */
@Composable
private fun ReviewAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = when {
            destructive && hovered -> theme.color("error", MaterialTheme.colorScheme.error)
            hovered -> theme.color("text")
            else -> theme.color("text.muted")
        },
        maxLines = 1,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun Notice(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = LocalZedTheme.current.color("text.muted"),
        )
    }
}
