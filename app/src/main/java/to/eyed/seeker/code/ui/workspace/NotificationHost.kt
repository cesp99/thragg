package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.rem

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
    if (visible.isEmpty()) return

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(StackGap),
        modifier = modifier
            .padding(StackMargin)
            .widthIn(max = ToastWidth),
    ) {
        // Newest nearest the edge the stack grows from: first in the column on
        // a wide screen, last on a compact one, so in both cases the newest
        // toast is the one under the eye. The overflow row sits at the far end
        // from it, where the older ones are.
        val ordered = if (isWide) visible else visible.asReversed()
        if (isWide && stack.hidden > 0) OverflowRow(stack)
        for (notification in ordered) {
            Toast(
                notification = notification,
                onDismiss = { stack.dismiss(notification.id) },
            )
        }
        if (!isWide && stack.hidden > 0) OverflowRow(stack)
        if (stack.isExpanded && stack.all.size > NotificationStack.MAX_VISIBLE) {
            StackFooter(stack)
        }
    }
}

/**
 * One toast: Zed's notification card — an icon, the message, an optional
 * action button and a close button, on `elevated_surface.background` inside a
 * 1px border (notifications.rs:968-1150).
 */
@Composable
private fun Toast(notification: AppNotification, onDismiss: () -> Unit) {
    val accent = severityColour(notification.severity)
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
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
 * [LocalSeekerColors] is that key already solved.
 */
@Composable
private fun severityColour(severity: NotificationSeverity): Color = when (severity) {
    NotificationSeverity.Info -> MaterialTheme.colorScheme.primary
    NotificationSeverity.Warning -> LocalSeekerColors.current.warnMark
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
