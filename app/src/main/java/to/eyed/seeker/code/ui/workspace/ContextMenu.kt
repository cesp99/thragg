package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.rem

/**
 * The menu's metrics as rem multiples — `ui_font_size` is the rem
 * (theme_settings/src/settings.rs:619) — held as bare numbers so the table is
 * checkable on the host (`ChromeMetricsTest`). The radii and the 1px border
 * are `px(…)` in gpui and stay `.dp`.
 */
internal object MenuMetrics {

    /** How wide a context menu opens before its labels push it wider. Ours. */
    const val MIN_WIDTH = 13.75f

    /** An inset ListItem: 4px of surface around each row (list_item.rs:309). */
    const val INSET = 0.25f

    /** The row itself: `rounded_sm` with `px(Base06)` inside (list_item.rs:364). */
    const val ROW_PAD_X = 0.375f
    const val ROW_PAD_Y = 0.125f

    /** `ml_4` between a label and its keybinding (context_menu.rs:2089). */
    const val LABEL_TO_CHORD = 1f

    /** The leading check slot a toggleable entry reserves, checked or not. */
    const val CHECK_SLOT = 0.875f
}

/**
 * What a disabled label keeps of its enabled colour.
 *
 * Material's own figure, and the reason it is a figure rather than a key: on
 * this side of the seam a disabled row is `onSurface` faded, so it stays the
 * same hue as the row above it and reads as the same row switched off. Zed's
 * `text.disabled` is a separate ink and in nine of the eleven bundled themes
 * it is within a hair of `text.muted`, which the chord column already uses —
 * so a menu drawn with it had disabled rows and shortcut labels the same
 * colour, and nothing said which was which.
 */
internal const val DISABLED_ALPHA = 0.38f

/** One row of a context menu: what it does, and the chord that also does it. */
internal data class ContextMenuItem(
    val label: String,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    /**
     * A toggleable entry — Zed's `toggleable(IconPosition::Start, checked)`
     * (context_menu.rs): non-null grows a leading check slot, filled with the
     * mark when true and left empty when false, so the labels of checked and
     * unchecked entries stay aligned.
     */
    val checked: Boolean? = null,
    /**
     * Zed's documentation aside — the muted explainer an entry can carry (the
     * commit menu's Skip Hooks shows the literal `git commit --no-verify`,
     * git_panel.rs:5599-5608). Zed floats it in a panel beside the menu; on a
     * phone there is no beside, so it is a second line under the label.
     */
    val aside: String? = null,
    /**
     * Zed's `.separator()` — a 1px rule above this entry, dividing the menu
     * into its groups (the remote menu draws one between Pull (Rebase) and
     * Push, git_ui.rs:1050). On the entry rather than in the list so [items]
     * stays a plain list of rows.
     */
    val separatorAbove: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The menu a right-click or a long-press opens, in the shape the title bar's
 * menu already uses: label on the left, its shortcut on the right.
 *
 * [offset] moves it to where the pointer was, so a right-click drops the menu
 * under the cursor rather than at the corner of whatever was clicked.
 */
@Composable
internal fun ContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    items: List<ContextMenuItem>,
    offset: DpOffset = DpOffset.Zero,
    minWidth: Dp = rem(MenuMetrics.MIN_WIDTH),
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        // Zed's `elevation_2`: an elevated surface, `rounded_lg` 8px, and a
        // 1px border in `border.variant` (styled_ext.rs:6-12) — which is
        // `surfaceContainer` and `outlineVariant`, the same two colours by
        // their Material names. This menu hangs under the Code, Build and
        // Changes overflow buttons, so it is chrome and takes the M3 roles;
        // the bridge maps `surfaceContainer` FROM `elevated_surface.background`
        // and nudges it when a theme collides two ladder rungs, so it is the
        // same ink with the menu-over-a-sheet case fixed.
        shape = RoundedCornerShape(rem(ZedRadius.LG)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // Entries are inset ListItems: 4px of surface around each row, the
        // row itself `rounded_sm` with 6px inside (list_item.rs:309, 364, 405).
        Column(
            modifier = Modifier
                .widthIn(min = minWidth)
                .padding(horizontal = rem(MenuMetrics.INSET)),
        ) {
            for (item in items) {
                if (item.separatorAbove) {
                    // Zed's separator: a 1px `border.variant` rule with 4px of
                    // surface above and below (context_menu.rs's Divider row).
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = rem(MenuMetrics.INSET)),
                    )
                }
                ContextMenuRow(item, onChosen = onDismiss)
            }
        }
    }
}

@Composable
private fun ContextMenuRow(item: ContextMenuItem, onChosen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rem(MenuMetrics.LABEL_TO_CHORD)),
        modifier = Modifier
            .fillMaxWidth()
            // No hand-drawn `ghost_element.hover` fill any more. Under M3 that
            // key has no colour answer at all — hover is a STATE LAYER, and the
            // indication below draws it (docs/VISUAL.md, "Foundations": the 51
            // `ghost_element.hover` sites delete their background rather than
            // remap it). The clip stays, because it is what keeps that layer
            // and the ripple inside the row's corners.
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            .then(
                if (item.enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        // NO `indication = null` any more. This row is drawn on
                        // BOTH sides of the hybrid — the terminal's long-press
                        // menu and the Material half's ⋮ menus — and each side
                        // already provides the right indication: `ZedSurface`
                        // installs `NoIndication`, so Zed's instant colour swap
                        // survives there, while the Material half gets its
                        // ripple. Hard-coding null gave a phone menu no press
                        // feedback at all outside the editor.
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                        ) {
                            onChosen()
                            item.onClick()
                        }
                } else {
                    Modifier
                }
            )
            // MEASURED ON DEVICE: the rows were 22dp apart. `ROW_PAD_Y` is
            // 0.125rem — 2dp — because this menu is a faithful port of Zed's
            // DESKTOP context menu, where the target is a mouse cursor. On a
            // phone the target is a fingertip, so the row takes the app's
            // [MD.rowMin] floor; the rem paddings still set the rhythm inside
            // it, and a row whose label wraps still grows past the floor.
            .heightIn(min = MD.rowMin)
            .padding(
                horizontal = rem(MenuMetrics.ROW_PAD_X),
                vertical = rem(MenuMetrics.ROW_PAD_Y),
            ),
    ) {
        if (item.checked != null) {
            // The check wears `Color::Accent`, as every selected mark in Zed
            // (context_menu.rs renders toggleable entries with a Check icon in
            // the accent colour); the slot stays open when unchecked.
            Text(
                text = if (item.checked) "✓" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(min = rem(MenuMetrics.CHECK_SLOT)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyMedium,
                // Disabled is M3's 38% of the enabled ink, not Zed's
                // `text.disabled` key: `onSurfaceVariant` is a live secondary
                // colour here — the chord on the right wears it — so a
                // disabled row painted with it would read as merely quiet.
                color = if (item.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                },
            )
            if (item.aside != null) {
                Text(
                    text = item.aside,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (item.shortcut != null) {
            Text(
                text = item.shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Right-click, reported with the position it happened at.
 *
 * Watched in the *initial* pass and consumed only for the secondary button, so
 * taps, drags and left-clicks reach whatever is underneath unchanged — which
 * matters where the thing underneath is an Android view, as in the terminal.
 */
@Composable
internal fun Modifier.onSecondaryClick(onClick: (DpOffset) -> Unit): Modifier =
    onButtonPress(secondary = true, onClick = onClick)

/** Middle-click. Closing a tab with the wheel button is a habit worth keeping. */
@Composable
internal fun Modifier.onMiddleClick(onClick: () -> Unit): Modifier =
    onButtonPress(secondary = false) { onClick() }

/**
 * Run [onPress] on every pointer press, before anything else sees it, and
 * consume nothing.
 *
 * What it exists for: a surface that uses `detectDragGesturesAfterLongPress`
 * alongside `clickable` gets *both* on a long press that never moves — the
 * drag detector reports its end and the tap detector, which has no timeout,
 * still reports a click. The fix is a flag the long press sets and the click
 * checks, and this is where the flag is *cleared*: at the start of the next
 * gesture, which is the only moment that is unambiguously before both.
 */
@Composable
internal fun Modifier.onAnyPress(onPress: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onPress)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) latest()
            }
        }
    }
}

@Composable
private fun Modifier.onButtonPress(secondary: Boolean, onClick: (DpOffset) -> Unit): Modifier {
    // The pointer loop outlives recomposition, so it reads the callback
    // through a holder rather than closing over the one it started with.
    val latest by rememberUpdatedState(onClick)
    return pointerInput(secondary) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val buttons = event.buttons
                val wanted =
                    if (secondary) buttons.isSecondaryPressed else buttons.isTertiaryPressed
                if (!wanted) continue
                val position = event.changes.first().position
                event.changes.forEach { it.consume() }
                latest(DpOffset(position.x.toDp(), position.y.toDp()))
            }
        }
    }
}
