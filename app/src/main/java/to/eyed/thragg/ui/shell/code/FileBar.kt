package to.eyed.thragg.ui.shell.code

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.ThraggIconButton
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.workspace.OpenFilesState

/**
 * The file bar: the answer to "where did my tabs go".
 *
 * A 44dp row of chips, one per open buffer, pinned directly above the nav bar
 * — which is to say in the bottom third of the screen, where the thumb of the
 * hand holding the phone already is. That position is the entire point. The
 * old shell put the tab strip at the top and the rarely-pressed ⋮ within easy
 * reach at the bottom, which is the reachability inversion the spec names as
 * defect 3 (docs/UI.md, "Why"). Switching files is the single most frequent
 * navigation in an editor; it goes at the bottom. docs/VISUAL.md's wireframe
 * sketches this row under the top bar, and it stays here anyway: that drawing
 * is an inventory of the chrome, and moving the most-pressed control in the
 * destination out of the thumb zone is not a colour change.
 *
 * It IS a Material row now, on the app side of the seam: the fill is
 * `surfaceContainer`, the seam over it is a [HairlineDivider], the active chip
 * is a `surfaceContainerHigh` pill and the tap has its ripple back. What it is
 * *not* is Zed's tab strip — the buffer below the hairline keeps every one of
 * its own colours, and this bar is the boundary.
 *
 * What went with the tab strip and is *not* here: preview tabs, pinning,
 * drag-to-reorder, `max_tabs`, the split menu, the close-others menu and the
 * Ctrl+Tab overlay. Two gestures replace all of it — tap a chip to switch,
 * long-press a chip to close — and the second one raises
 * [to.eyed.thragg.ui.common.UnsavedChangesDialog] through
 * [OpenFilesState.requestClose], which the host composes. Closing a dirty
 * buffer without that dialog is the one place in this app where work can
 * vanish unreported, so the close request deliberately goes through the model
 * rather than calling `close` directly.
 *
 * The tree button at the right end does **not** scroll away with the chips.
 * It is the way into the Files & Find sheet — which carries its own
 * names/in-files switch, so one door is enough — and a control that is
 * sometimes off the right edge of the screen is a control you cannot rely on.
 *
 * It hides itself whenever the IME is up, unconditionally and for the same
 * reason [to.eyed.thragg.ui.shell.ShellNavBar] does: with the keyboard
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
    /** The tree button — the Files sheet, on the tree. */
    onFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (WindowInsets.isImeVisible) return
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

    Column(modifier = modifier.fillMaxWidth()) {
        // The boundary itself, drawn: everything above this line is the Zed
        // half and everything below it is Material (docs/VISUAL.md, "Code
        // destination — chrome only", the double rule in the wireframe).
        HairlineDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FileBarHeight)
                .background(MaterialTheme.colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f, fill = true).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space1),
                contentPadding = PaddingValues(horizontal = MD.space2),
            ) {
                itemsIndexed(files.tabs, key = { _, file -> file.path }) { index, file ->
                    FileChip(
                        name = file.name,
                        dirty = file.isDirty,
                        active = index == activeIndex,
                        onClick = { onSelect(index) },
                        onLongClick = {
                            // A close is destructive and it has no visible
                            // affordance, so it gets the same confirmation by
                            // feel that a long-press-to-delete does everywhere
                            // else on the platform.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onRequestClose(index)
                        },
                    )
                }
            }
            // The fixed end. Drawn after the list and outside it, so no length
            // of file names can push either off the screen.
            Box(
                modifier = Modifier
                    .width(MD.hairline)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            // ONE button, not two. A "Search in files" magnifier stood beside
            // this and opened the very same sheet on its other tab — the
            // Files sheet carries the names/in-files switch at its foot, one
            // tap from either — so the pair was two doors into one room, and
            // the second door cost the file chips 44dp of the row.
            ThraggIconButton(
                icon = R.drawable.ic_ui_file_tree,
                description = "Files",
                onClick = onFiles,
                tint = mutedIcon,
                modifier = Modifier.width(FileBarHeight),
            )
        }
    }
}

/**
 * One buffer's chip: a pill at [MD.pill], filled a step up the ladder when it
 * is the buffer on screen and transparent when it is not.
 *
 * Selection is a fill here rather than the border change the rest of the
 * design uses, and that is the one place the rule bends on purpose: these are
 * chips in a scrolling row where only one is ever selected, so the reading is
 * "which of these" rather than "is this one on" — the same argument the nav
 * bar's indicator pill makes one row below it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileChip(
    name: String,
    dirty: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(ChipHeight)
            .clip(RoundedCornerShape(MD.pill))
            .background(
                if (active) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = "Close $name",
            )
            .padding(horizontal = MD.space3),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        if (dirty) {
            // The dot, not a close button: closing is the long press, and a
            // tiny ✕ next to a tiny label on a 400dp row is two targets inside
            // one thumb.
            ThraggIcon(
                icon = R.drawable.ic_ui_dot,
                contentDescription = "unsaved",
                tint = MaterialTheme.colorScheme.primary,
                size = DirtyDotSize,
                modifier = Modifier.padding(start = MD.space1),
            )
        }
    }
}

/** 44dp, the same as the action row — see docs/UI.md's budget. */
internal val FileBarHeight = 44.dp

/** The chip inside the 44dp bar, leaving 6dp of ground above and below it. */
private val ChipHeight = 32.dp

/**
 * The unsaved mark, smaller than [IconSize.Marker] on purpose: it sits inside
 * a chip beside a `labelLarge` filename, and at 14dp it would be the biggest
 * thing on the chip.
 */
private val DirtyDotSize = 8.dp
