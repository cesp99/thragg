package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a tab does under a pointer: the three things a tab can be asked
 * for, and the drag that takes it to another pane.
 *
 * Positions are in root coordinates — the same frame the pane group keeps
 * its pane bounds in — so the drop target is a containment test and not a
 * conversion.
 */
internal class TabGestures(
    val onSelect: () -> Unit,
    /** The long-press menu, on touch; a mouse right-clicks instead. */
    val onMenu: () -> Unit,
    val onDragStart: (Offset) -> Unit,
    val onDrag: (Offset) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit,
)

/** The marker `waitForUpOrCancellation` returns through when the gesture was taken away. */
private val Cancelled = Any()

/**
 * Tap, long-press and drag on one tab, arbitrated the way a touch screen
 * needs them to be.
 *
 * Zed drags a tab from the first pixel of movement (`on_drag` on the tab,
 * pane.rs:2950-2960), which a mouse can do here too. A finger cannot: the
 * strip scrolls sideways under it, so the first movement is a scroll and
 * has to stay one. So touch **long-presses first** — the haptic says the
 * tab is lifted — and then either drags it (it moves past the touch slop)
 * or, released where it was, opens the menu that a long press always
 * opened. A tap is still a tap.
 *
 * Every move of a lifted tab is consumed, which is what stops the strip
 * scrolling under the drag. A secondary or middle button never reaches
 * this: those are consumed in the initial pass by the click modifiers
 * beside it, and `awaitFirstDown` skips a consumed press.
 */
@Composable
internal fun Modifier.tabGestures(gestures: TabGestures): Modifier {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val haptics = LocalHapticFeedback.current
    // The pointer loop outlives recomposition; it reads the callbacks
    // through a holder rather than closing over the ones it started with.
    val latest by rememberUpdatedState(gestures)
    return this
        .onGloballyPositioned { coordinates = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val touch = down.type != PointerType.Mouse
                val toRoot = { change: PointerInputChange ->
                    coordinates?.localToRoot(change.position) ?: change.position
                }
                if (touch) {
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation() ?: Cancelled
                    }
                    when (outcome) {
                        // The pause ran out with the finger still down: lifted.
                        null -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // The strip took the pointer to scroll.
                        Cancelled -> return@awaitEachGesture
                        else -> {
                            (outcome as PointerInputChange).consume()
                            latest.onSelect()
                            return@awaitEachGesture
                        }
                    }
                }
                var dragging = false
                var travelled = Offset.Zero
                val slop = viewConfiguration.touchSlop
                val completed = drag(down.id) { change ->
                    travelled += change.positionChange()
                    if (!dragging && travelled.getDistance() > slop) {
                        dragging = true
                        latest.onDragStart(toRoot(change))
                    }
                    if (dragging || touch) change.consume()
                    if (dragging) latest.onDrag(toRoot(change))
                }
                when {
                    dragging -> if (completed) latest.onDragEnd() else latest.onDragCancel()
                    touch -> latest.onMenu()
                    completed -> latest.onSelect()
                }
            }
        }
}
