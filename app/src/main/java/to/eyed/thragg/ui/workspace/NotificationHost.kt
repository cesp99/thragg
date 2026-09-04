package to.eyed.thragg.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.eyed.thragg.R
import to.eyed.thragg.ui.theme.Durations
import to.eyed.thragg.ui.theme.LocalReduceMotion
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.ZedRadius
import to.eyed.thragg.ui.theme.rem

/**
 * Zed's notification column is `w_112` — 448px — and never wider than the
 * work area it sits in (workspace.rs:6625-6634). A phone gets the width it
 * has, minus the margin.
 */
private val ToastWidth = 448.dp

/** Zed's `.right_3().bottom_3()` around the column (workspace.rs:6627-6628). */
private val StackMargin = 12.dp

/** `gap_2` between the toasts (workspace.rs:6634). */
private val StackGap = 8.dp

/** The close control: Zed's IconButton at `ButtonSize::Default` (button_like.rs:469). */
private val ControlBox = 22.dp
private val ControlIcon = 14.dp

/**
 * The workspace's toast stack — Zed's `Workspace::render_notifications`
 * (workspace.rs:6620-6642), which draws its notifications in a column over
 * the work area with the status bar below it.
 *
 * Zed puts the column bottom-right, where a desktop pointer already is. This
 * app splits it by layout, which is what the shape of the device asks for: on
 * a wide screen the toasts sit **top-right**, clear of the status bar and the
 * panel buttons a tablet user is aiming at; on a compact one they sit **at the
 * bottom**, above the status bar and the terminal — within a thumb's reach,
 * since dismissing one is a tap.
 *
 * Beyond [NotificationStack.MAX_VISIBLE] the rest collapse into a "+N more"
 * row that expands the stack, so a burst of failures is a number rather than a
 * wall.
 *
 * The host draws nothing at all when the stack is empty, and it never takes
 * focus by itself: a toast that stole the keyboard mid-keystroke would be
 * worse than the failure it is reporting. Tab or a tap lands on one, and then
 * Escape dismisses it.
 */
@Composable
fun NotificationHost(
    stack: NotificationStack,
    /** False on a phone or a folded foldable — see `WideLayoutMinWidth`. */
    isWide: Boolean,
    modifier: Modifier = Modifier,
) {
    // One timer for the whole stack, waking exactly when the soonest toast
    // runs out rather than polling: an idle workspace should not recompose
    // four times a second because something said "Saved" a minute ago.
    val nextExpiry = stack.nextExpiry()
    LaunchedEffect(nextExpiry) {
        val at = nextExpiry ?: return@LaunchedEffect
        val wait = at - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        stack.expire()
    }

    val visible = stack.visible
    val reduce = LocalReduceMotion.current

    // A LazyColumn rather than a Column, and not for laziness — four toasts
    // is the cap. It is for `animateItem`: a keyed item that leaves the list
    // fades out over [Durations.BAND_OUT] and the ones above it settle into
    // the gap on a spring, where a Column simply stops drawing it. A toast
    // that vanishes between two frames reads as a glitch on the very message
    // it was carrying; one that settles reads as read. The list is never
    // user-scrollable, and an empty one has no size, so it takes no touch
    // away from what is under it.
    LazyColumn(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(StackGap),
        userScrollEnabled = false,
        modifier = modifier
            .padding(StackMargin)
            .widthIn(max = ToastWidth),
    ) {
        // Newest nearest the edge the stack grows from: first in the column on
        // a wide screen, last on a compact one, so in both cases the newest
        // toast is the one under the eye. The overflow row sits at the far end
        // from it, where the older ones are.
        val ordered = if (isWide) visible else visible.asReversed()
        if (isWide && stack.hidden > 0) item(key = "overflow") { OverflowRow(stack) }
        items(ordered, key = { it.id }) { notification ->
            Toast(
                notification = notification,
                onDismiss = { stack.dismiss(notification.id) },
                modifier = Modifier.animateItem(
                    // The entrance is the toast's own (it rises from the edge
                    // it will leave by); this only carries the exit and the
                    // settle.
                    fadeInSpec = null,
                    placementSpec = if (reduce) {
                        snap()
                    } else {
                        spring(
                            stiffness = Spring.StiffnessMediumLow,
                            visibilityThreshold = IntOffset.VisibilityThreshold,
                        )
                    },
                    fadeOutSpec = if (reduce) snap() else tween(Durations.BAND_OUT),
                ),
            )
        }
        if (!isWide && stack.hidden > 0) item(key = "overflow") { OverflowRow(stack) }
        if (stack.isExpanded && stack.all.size > NotificationStack.MAX_VISIBLE) {
            item(key = "footer") { StackFooter(stack) }
        }
    }
}

/**
 * Whether a toast let go at [offset] px with [velocity] px/s is on its way
 * out, or should settle back.
 *
 * NOT A DISTANCE THRESHOLD. The finger's release point is where the gesture
 * *stopped being observed*, not where it was going: a quick flick lets go a
 * few pixels in with the whole intent in its speed. So the resting place is
 * projected first — Apple's scroll-deceleration form, `(v / 1000) · d / (1 −
 * d)` with `d = 0.998`, the same curve a scroll view coasts on — and the
 * decision is made on where the toast would come to rest, not where it was
 * dropped. Half the width is the line: past it the card is more gone than
 * here.
 *
 * A pure function so the rule is checkable on the host; the device test is
 * that a flick works.
 */
internal fun dismissesToast(offset: Float, velocity: Float, width: Int): Boolean {
    if (width <= 0) return false
    val projected = offset + projectedTravel(velocity)
    // A throw that crosses home is a throw HOME: a card dragged right and
    // flung back left is being put back, however hard, and must never go
    // out the far side. (Caught by the host test before it reached a thumb.)
    if (offset != 0f && sign(projected) != sign(offset)) return false
    return abs(projected) >= width / 2f
}

/**
 * How far a release at [velocity] px/s coasts before stopping, at UIScrollView's
 * normal deceleration rate. The exponential-decay form Apple ships, not the
 * textbook `v² / 2a`.
 */
internal fun projectedTravel(velocity: Float, decelerationRate: Float = 0.998f): Float =
    (velocity / 1000f) * decelerationRate / (1f - decelerationRate)

/**
 * One toast: Zed's notification card — an icon, the message, an optional
 * action button and a close button, on `elevated_surface.background` inside a
 * 1px border (notifications.rs:968-1150).
 */
@Composable
private fun Toast(
    notification: AppNotification,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = severityColour(notification.severity)
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    // The swipe. The card follows the finger 1:1 sideways, thins as it goes,
    // and on release either coasts off the edge it was thrown toward or
    // springs home — decided by [dismissesToast] on the projected resting
    // place, so a short fast flick dismisses and a long slow drag that stops
    // short does not. The drag itself never reads reduce-motion: a card that
    // stopped following the finger would be a bug, not an accommodation. The
    // settle does.
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val settle: AnimationSpec<Float> = if (reduce) snap() else SwipeSettle
    // Entered the way it will leave — up from the bottom edge on a phone,
    // where the stack sits — so the exit that `animateItem` draws later is
    // the same motion reversed. Reduce-motion shows it in place.
    val entered = remember { MutableTransitionState(reduce) }.apply { targetState = true }
    AnimatedVisibility(
        visibleState = entered,
        enter = if (reduce) {
            EnterTransition.None
        } else {
            fadeIn(tween(Durations.BAND_IN)) +
                slideInVertically(tween(Durations.BAND_IN)) { height -> height / 2 }
        },
        modifier = modifier,
    ) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width }
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer {
                // Thins as it travels, so a card half-way off the edge is
                // half-way gone rather than a full-strength card in the wrong
                // place; the fraction is of the width it has to cover.
                val gone = (abs(offset.value) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                alpha = 1f - gone * 0.5f
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    scope.launch { offset.snapTo(offset.value + delta) }
                },
                onDragStopped = { velocity ->
                    if (dismissesToast(offset.value, velocity, width)) {
                        // Off the side it was heading for, carrying the
                        // finger's own speed, and only then out of the stack.
                        val exit = if (offset.value + projectedTravel(velocity) >= 0f) width else -width
                        offset.animateTo(exit.toFloat(), settle, initialVelocity = velocity)
                        onDismiss()
                    } else {
                        offset.animateTo(0f, settle, initialVelocity = velocity)
                    }
                },
            )
            .clip(RoundedCornerShape(rem(ZedRadius.MD)))
            // A toast is drawn over EVERY screen, both halves of the app
            // included, so it takes M3's raised rung rather than the editor's
            // own `elevated_surface.background`: over a sheet that is already
            // `surfaceContainer` the raw key is the same hex on nine of the
            // eleven bundled themes, and the card had no edge but the severity
            // border. `High` and not `Highest`, because the buttons inside it
            // rest on the rung above.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                // The border carries the severity, as Zed's status colours do
                // — the card itself stays the surface it is drawn on, so a
                // stack of four does not become four coloured slabs.
                accent.copy(alpha = 0.5f),
                RoundedCornerShape(rem(ZedRadius.MD)),
            )
            // Focusable, not focused: Escape dismisses the toast the keyboard
            // is on. Nothing here grabs focus on its own.
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key != Key.Escape) return@onPreviewKeyEvent false
                onDismiss()
                true
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Image(
            painter = painterResource(severityIcon(notification.severity)),
            contentDescription = severityLabel(notification.severity),
            colorFilter = ColorFilter.tint(accent),
            modifier = Modifier.size(ControlIcon),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = notification.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                // Long enough for a compiler's sentence, short enough that
                // four of them still fit above a phone's status bar.
                maxLines = 6,
            )
            val action = notification.action
            if (action != null) {
                ToastButton(
                    label = action.label,
                    emphasised = true,
                    onClick = {
                        // The toast goes with the press: every action so far
                        // opens something, and leaving the card behind would
                        // cover what it just opened.
                        onDismiss()
                        action.run()
                    },
                )
            }
        }
        GhostIconButton(
            icon = R.drawable.ic_ui_close,
            label = "Dismiss this notification",
            onClick = onDismiss,
        )
    }
    }
}

/**
 * The swipe's settle: under-damped, because a card that was just thrown has
 * momentum and a little overshoot is what makes the release feel like the
 * same object the finger was holding. Apple's drawer numbers (damping 0.8),
 * on Compose's medium-low stiffness so it lands in about a third of a second.
 */
private val SwipeSettle: AnimationSpec<Float> = spring(
    dampingRatio = 0.8f,
    stiffness = Spring.StiffnessMediumLow,
)

/** "+3 more" — the cap's overflow, and the tap that lifts it. */
@Composable
private fun OverflowRow(stack: NotificationStack) {
    ToastButton(
        label = "+${stack.hidden} more",
        emphasised = false,
        onClick = { stack.expand() },
    )
}

/** Once expanded: fold the stack back, or clear the lot. */
@Composable
private fun StackFooter(stack: NotificationStack) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToastButton(label = "Show fewer", emphasised = false, onClick = { stack.collapse() })
        ToastButton(label = "Clear all", emphasised = false, onClick = { stack.clearAll() })
    }
}

/**
 * Zed's `Button` at `LabelSize::Small` inside a notification: a ghost box that
 * fills on hover, the label in `text.accent` when it is the card's own action
 * (notifications.rs:1120-1150).
 */
@Composable
private fun ToastButton(label: String, emphasised: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            // Zed's `element.*` ramp collapses to one resting fill plus the
            // state layer: `element.background` is a rung of the ladder, so it
            // is named as one — the rung above the toast, so the button reads
            // as raised on it — and hover and press are what the indication
            // draws over that.
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = label,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (emphasised) FontWeight.Medium else null,
            color = if (emphasised) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** The X: a ghost IconButton, the same one the status bar and the docks use. */
@Composable
internal fun GhostIconButton(
    icon: Int,
    label: String,
    tint: Color? = null,
    box: Dp = ControlBox,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(box)
            // Ghost means nothing at rest and the state layer on top; the clip
            // is what rounds that layer to the button's corners.
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            colorFilter = ColorFilter.tint(
                tint ?: MaterialTheme.colorScheme.onSurfaceVariant
            ),
            modifier = Modifier.size(ControlIcon),
        )
    }
}

/**
 * Zed's status colours — `info`, `warning`, `error` (theme/src/styles/status.rs)
 * — as the Material half spells them.
 *
 * Same three meanings, same three keys underneath: `primary` is derived from
 * `text.accent`, which is exactly what `info` fell back to here, and `error` is
 * the `error` key itself. Only `warning` changes shape, and it has to: it is
 * drawn as an icon and as a border, so it wants MARK_RATIO against the card it
 * lands on, and the raw key is 1.64:1 on Ayu Light. `warnMark` on
 * [LocalThraggColors] is that key already solved.
 */
@Composable
private fun severityColour(severity: NotificationSeverity): Color = when (severity) {
    NotificationSeverity.Info -> MaterialTheme.colorScheme.primary
    NotificationSeverity.Warning -> LocalThraggColors.current.warnMark
    NotificationSeverity.Error -> MaterialTheme.colorScheme.error
}

private fun severityIcon(severity: NotificationSeverity): Int = when (severity) {
    NotificationSeverity.Info -> R.drawable.ic_file_info
    NotificationSeverity.Warning, NotificationSeverity.Error -> R.drawable.ic_ui_warning
}

private fun severityLabel(severity: NotificationSeverity): String = when (severity) {
    NotificationSeverity.Info -> "Notice"
    NotificationSeverity.Warning -> "Warning"
    NotificationSeverity.Error -> "Error"
}
