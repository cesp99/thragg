package to.eyed.seeker.code.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget

/**
 * The one shape every modal surface in the app takes.
 *
 * There are no docks and nothing is ever side by side (docs/UI.md,
 * "Navigation"): Files & Find, Projects & tools, Cluster, Wallet, Deploy
 * confirm, the agent picker, the @-mention picker, Commit, the ⋮ overflow, the
 * file long-press menu, the code-actions sheet and the unsaved-changes confirm
 * are all *this*. Hosting them through one scaffold is what makes three
 * behaviours true of all of them at once instead of true of whichever ones
 * their author remembered:
 *
 *  1. **The field is pinned at the bottom.** Any sheet with a text field puts
 *     it at the *bottom* of the sheet so the IME lands directly under it and
 *     the results scroll above — the opposite of the desktop habit of a search
 *     field at the top with the keyboard covering its own results. Pass
 *     [field]; do not put a `TextField` in [content].
 *  2. **It registers with the back handler.** [ShellState.dismissTopSheet] can
 *     close the topmost sheet without knowing what it is (step 2 of the
 *     ordered handler), because every sheet is on the stack this scaffold
 *     keeps.
 *  3. **It opens at [OPEN_FRACTION] of the height and drags to full.** Pass
 *     [openFraction] for the rare sheet that is a form rather than a menu. The
 *     drag lives on the handle rather than on Material's own detents: its only
 *     intermediate anchor is exactly half the window, and the sheets this app
 *     has — a file tree with a filter, a deploy summary — want the two thirds
 *     the spec asks for.
 *
 * Dragging the handle below [DISMISS_FRACTION] dismisses, as does a tap on the
 * scrim, a back press (Material's sheet window takes it before the shell's
 * handler is asked — see ShellBackHandler.kt) and a downward fling on the body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    state: ShellState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The sheet's own title, drawn under the handle. Null for a bare menu. */
    title: String? = null,
    /**
     * The bottom-pinned text field, if the sheet has one. It sits under
     * [content], above the IME, and it is the only thing in a sheet that may
     * hold the keyboard.
     */
    field: (@Composable () -> Unit)? = null,
    /** Actions pinned under the field — Commit, Deploy, "＋ New file". */
    actions: (@Composable () -> Unit)? = null,
    /**
     * How much of the window the sheet takes when it opens, as a fraction.
     *
     * [OPEN_FRACTION] is the house default and almost every sheet wants it.
     * The exception is a sheet that is not a menu over the screen but a *form*
     * standing in for it — the question sheet Spettro raises, where the agent
     * has stopped and the answer is the only thing on the phone worth doing.
     * Opening that at two thirds hides its own review page behind a drag the
     * user has no reason to guess at. The handle still resizes from wherever
     * this puts it, so this changes the opening pose and nothing else.
     */
    openFraction: Float = OPEN_FRACTION,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalZedTheme.current
    val sheetState = rememberModalBottomSheetState(
        // Material's partial detent is half the window and is not
        // configurable; this scaffold owns the height instead (see [fraction]),
        // so the sheet itself is always "expanded" to whatever height we asked
        // for and the handle does the rest.
        skipPartiallyExpanded = true,
    )
    // The dismiss the back handler will call. `rememberUpdatedState` because
    // the handle is registered once and the caller's lambda is rebuilt on
    // every recomposition; without it a sheet dismissed by back would run the
    // lambda from the frame it opened on.
    val dismiss by rememberUpdatedState(onDismiss)
    val handle = remember { SheetHandle { dismiss() } }
    DisposableEffect(handle) {
        state.sheetOpened(handle)
        onDispose { state.sheetClosed(handle) }
    }

    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    // Keyed on the requested pose so a caller that computes it (rather than
    // passing a constant) is not stuck with the first frame's value, and
    // clamped because a fraction at or below the dismiss threshold would open
    // a sheet that is already asking to be closed.
    var fraction by remember(openFraction) {
        mutableFloatStateOf(openFraction.coerceIn(DISMISS_FRACTION, 1f))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface),
        // Drawn below, so the drag can size the sheet rather than move it.
        dragHandle = null,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxHeight(fraction)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .touchTarget()
                    .semantics { contentDescription = "Drag to resize, drag down to close" }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            // Up is negative, and up grows the sheet.
                            val next = fraction - delta / windowHeight.value
                            if (next < DISMISS_FRACTION) onDismiss() else fraction = next.coerceAtMost(1f)
                        },
                    )
                    .padding(vertical = HandlePadding),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = HandleWidth, height = HandleHeight)
                        .clip(RoundedCornerShape(HandleHeight / 2))
                        .background(theme.color("border", MaterialTheme.colorScheme.outline)),
                )
            }
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(horizontal = SheetPadding, vertical = TitleGap),
                )
            }
            // The body takes what is left, so the field below it cannot be
            // pushed off the bottom by a long list.
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth(),
                content = content,
            )
            if (field != null || actions != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = SheetPadding, vertical = TitleGap),
                ) {
                    field?.invoke()
                    actions?.invoke()
                }
            } else {
                // Nothing pinned: the gesture inset still has to be cleared, or
                // the last row of a menu sits under the system's handle.
                Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().height(0.dp))
            }
        }
    }
}

/** "Sheets open at ~65% height" — docs/UI.md, "Navigation". */
private const val OPEN_FRACTION = 0.65f

/** Dragged below this, the gesture was a dismissal rather than a resize. */
private const val DISMISS_FRACTION = 0.45f

private val HandleWidth = 32.dp
private val HandleHeight = 4.dp
private val HandlePadding = 8.dp
private val SheetPadding = 16.dp
private val TitleGap = 8.dp
