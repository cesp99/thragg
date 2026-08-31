package to.eyed.seeker.code.ui.workspace

/*
 * The picker chrome, lifted out of the file finder it used to share a file
 * with.
 *
 * These four composables plus one PaddingValues were the top of
 * ui/workspace/FileFinder.kt. FileFinder itself went with the command
 * palette, the theme selector and the rest of the keyboard-first shell (see
 * docs/UI.md, "What is removed") — but the chrome outlived it, because
 * OutlinePicker is kept as Code's "Go to symbol" sheet and it is drawn out of
 * exactly these pieces. Splitting rather than inlining keeps the Zed
 * provenance comments attached to the code they describe, and keeps the file
 * that answers "what does a picker look like here" one grep away.
 *
 * Zed half: every colour below is drawn raw from LocalZedTheme, unsolved,
 * because that is the rule on this side of the seam (docs/VISUAL.md, "The
 * hybrid").
 */

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.rem

/**
 * Elevated surfaces are `rounded_lg` = 8px with a 1px `border.variant`
 * (crates/ui/src/traits/styled_ext.rs:6-12).
 */
private val ModalRadius = 8.dp

/**
 * The shell every picker in this app wears: the file finder, the command
 * palette and the theme selector are the same widget in Zed, so they are the
 * same widget here.
 *
 * Zed's picker is one widget with several contents: `DEFAULT_MODAL_WIDTH` is
 * `rems(34)` and `DEFAULT_MODAL_MAX_HEIGHT` `rems(24)` — 544 and 384 at the
 * default 16px rem, scaling with the UI font (crates/picker/src/picker.rs:45-46,
 * crates/picker/src/shape.rs:207-215). The container is a `ModalSurface`:
 * `elevated_surface.background`, `rounded_lg`, 1px `border.variant`
 * (crates/ui/src/styles/elevation.rs:58-77; styled_ext.rs:6-12).
 *
 * [modifier] lands on the surface itself, which is where a caller hangs the
 * key handling it needs to see before the text field does. Tapping outside
 * dismisses: the content fills the window so that nothing is ever "outside" it
 * as far as the dialog is concerned, and the dimmed area has to close us by
 * hand.
 */
@Composable
internal fun PickerModal(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalZedTheme.current
    Dialog(
        onDismissRequest = onDismiss,
        // A picker has to stay usable with the soft keyboard up — on a phone
        // it is the *only* way to type — and a dialog only learns where the IME
        // is once it stops fitting the system windows itself.
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                shape = RoundedCornerShape(ModalRadius),
                color = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
                border = BorderStroke(
                    1.dp,
                    theme.color("border.variant", MaterialTheme.colorScheme.outlineVariant),
                ),
                modifier = modifier
                    .widthIn(max = rem(34f))
                    .heightIn(max = rem(24f))
                    .fillMaxWidth()
                    // Swallow taps, or they would reach the dismiss handler
                    // above and close the picker from inside it. `clickable`
                    // would do it too, and would ripple the whole panel.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(content = content)
            }
        }
    }
}

/**
 * The picker's query row, exactly as Zed draws it: a bare single-line editor
 * in a 36px row (`h_9`) with 10px of horizontal padding (`px_2p5`) — no box,
 * no border, no background of its own (crates/picker/src/render.rs:106-122) —
 * separated from what follows by a 1px `Divider::horizontal()`, whose default
 * colour is `border.variant` (render.rs:123-126;
 * crates/ui/src/components/divider.rs:19-31, 46-54).
 */
@Composable
internal fun PickerQueryField(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 10.dp)
            .pointerHoverIcon(PointerIcon.Text),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.cursor),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        if (query.text.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.placeholder"),
                maxLines = 1,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.color("border.variant")),
    )
}

/**
 * One picker row: Zed's `ListItem` with `.inset(true)` and
 * `ListItemSpacing::Sparse`. Inset draws 4px of surface either side of the
 * row (`px(Base04)`, crates/ui/src/components/list/list_item.rs:307-310); the
 * row itself is `rounded_sm` with 6px inside (`px(Base06)`), 4px vertical
 * (`py_1`) and a 6px gap between slots (list_item.rs:363-368, 405-407, 429).
 * With the 14px label's φ line box that lands the row at ~31px, Zed's number.
 * Hover, press and selection are the ghost ramp — `ghost_element.hover` /
 * `.active` / `.selected` (list_item.rs:380-385) — swapped instantly, no
 * ripple, as everywhere in Zed.
 */
@Composable
internal fun PickerListItem(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val theme = LocalZedTheme.current
    // Resolved once per row lifetime, not per recomposition: a picker list
    // repaints every row on each keystroke, and `theme.color` is a map read.
    val selectedFill = remember(theme) { theme.color("ghost_element.selected") }
    val pressedFill = remember(theme) { theme.color("ghost_element.active") }
    val hoverFill = remember(theme) { theme.color("ghost_element.hover") }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    isSelected -> selectedFill
                    pressed && enabled -> pressedFill
                    hovered && enabled -> hoverFill
                    else -> Color.Transparent
                }
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        content = content,
    )
}

/**
 * A picker with nothing to show: Zed renders the message as a disabled inset
 * row inside 8px of vertical padding, `Color::Muted`
 * (crates/picker/src/render.rs:257-268). The paddings below are that row's,
 * flattened: 4+6 horizontal, 8+4 vertical.
 */
@Composable
internal fun PickerEmptyState(text: String) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = theme.color("text.muted"),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
    )
}

/**
 * The vertical padding Zed's picker list carries — `py_1` = 4px
 * (crates/picker/src/picker.rs:1503).
 */
internal val PickerListPadding = PaddingValues(vertical = 4.dp)
