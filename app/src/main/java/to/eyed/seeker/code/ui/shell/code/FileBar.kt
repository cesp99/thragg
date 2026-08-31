package to.eyed.seeker.code.ui.shell.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.workspace.OpenFilesState

/**
 * The file bar: the answer to "where did my tabs go".
 *
 * A 44dp row of chips, one per open buffer, pinned directly above the nav bar
 * — which is to say in the bottom third of the screen, where the thumb of the
 * hand holding the phone already is. That position is the entire point. The
 * old shell put the tab strip at the top and the rarely-pressed ⋮ within easy
 * reach at the bottom, which is the reachability inversion the spec names as
 * defect 3 (docs/UI.md, "Why"). Switching files is the single most frequent
 * navigation in an editor; it goes at the bottom.
 *
 * What went with the tab strip and is *not* here: preview tabs, pinning,
 * drag-to-reorder, `max_tabs`, the split menu, the close-others menu and the
 * Ctrl+Tab overlay. Two gestures replace all of it — tap a chip to switch,
 * long-press a chip to close — and the second one raises
 * [to.eyed.seeker.code.ui.common.UnsavedChangesDialog] through
 * [OpenFilesState.requestClose], which the host composes. Closing a dirty
 * buffer without that dialog is the one place in this app where work can
 * vanish unreported, so the close request deliberately goes through the model
 * rather than calling `close` directly.
 *
 * The ⌕ and ☰ at the right end do **not** scroll away with the chips. They are
 * the two ways into the Files & Find sheet — ⌕ opens it searching *in files*,
 * ☰ opens it on the tree — and a control that is sometimes off the right edge
 * of the screen is a control you cannot rely on.
 *
 * It hides itself whenever the IME is up, unconditionally and for the same
 * reason [to.eyed.seeker.code.ui.shell.ShellNavBar] does: with the keyboard
 * open the 44dp belongs to the editor's action row, and the vertical budget
 * in docs/UI.md only balances because both this and the 56dp bar are gone.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FileBar(
    files: OpenFilesState,
    onSelect: (index: Int) -> Unit,
    /** Long-press: close, asking first when the buffer is dirty. */
    onRequestClose: (index: Int) -> Unit,
    /** ⌕ — the Files sheet, in its "in files" mode. */
    onFind: () -> Unit,
    /** ☰ — the Files sheet, on the tree. */
    onFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (WindowInsets.isImeVisible) return
    val theme = LocalZedTheme.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val activeIndex = files.activeIndex

    // The chips stay in the order the files were opened in, and the active one
    // is scrolled to instead. Ordering them by most-recently-used — which is
    // what the model can hand back, `mruTabs()` — would move the chip you just
    // tapped to the left edge and shift every other one under your finger,
    // which makes the second tap of a two-file loop land on the wrong file.
    // Stable positions, moving viewport.
    LaunchedEffect(activeIndex, files.tabs.size) {
        if (activeIndex in files.tabs.indices) {
            runCatching { listState.animateScrollToItem(activeIndex) }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(FileBarHeight)
            .background(theme.color("tab_bar.background", MaterialTheme.colorScheme.surface)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f, fill = true).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        ) {
            itemsIndexed(files.tabs, key = { _, file -> file.path }) { index, file ->
                val isActive = index == activeIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isActive) {
                                theme.color("tab.active_background", MaterialTheme.colorScheme.surfaceVariant)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .combinedClickable(
                            onClick = { onSelect(index) },
                            onLongClick = {
                                // A close is destructive and it has no visible
                                // affordance, so it gets the same confirmation
                                // by feel that a long-press-to-delete does
                                // everywhere else on the platform.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onRequestClose(index)
                            },
                            onLongClickLabel = "Close ${file.name}",
                        )
                        .padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                        color = if (isActive) {
                            theme.color("text", MaterialTheme.colorScheme.onSurface)
                        } else {
                            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    if (file.isDirty) {
                        // The dot, not a ✕: closing is the long press, and a
                        // tiny ✕ next to a tiny label on a 400dp row is two
                        // targets inside one thumb.
                        Text(
                            text = " ●",
                            style = MaterialTheme.typography.labelMedium,
                            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                            modifier = Modifier.semantics { contentDescription = "unsaved" },
                        )
                    }
                }
            }
        }
        // The fixed end. Drawn after the list and outside it, so no length of
        // file names can push either off the screen.
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(theme.color("border", MaterialTheme.colorScheme.outline)),
        )
        FileBarAction("⌕", "Search in files", onFind)
        FileBarAction("☰", "Files", onFiles)
    }
}

@Composable
private fun FileBarAction(label: String, description: String, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(FileBarHeight)
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
        )
    }
}

/** 44dp, the same as the header and the action row — see docs/UI.md's budget. */
internal val FileBarHeight = 44.dp
