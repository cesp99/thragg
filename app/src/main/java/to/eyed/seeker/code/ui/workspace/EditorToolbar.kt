package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.preview.PreviewKind
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.glyphHeight
import to.eyed.seeker.code.ui.theme.rem
import to.eyed.seeker.code.ui.theme.touchTarget

/**
 * The toolbar's metrics as rem multiples — `ui_font_size` is the rem
 * (theme_settings/src/settings.rs:619) — held as bare numbers so the table is
 * checkable on the host (`ChromeMetricsTest`).
 */
internal object ToolbarMetrics {

    /**
     * Zed's toolbar frame: `py(Base06)` and `px(Base08)` around an `h_8` =
     * `rems(2)` item row (workspace/src/toolbar.rs:123-124, 140, 150), on
     * `toolbar.background` with a 1px `border.variant` underline
     * (toolbar.rs:128-130). Per the 2026-08-17 density decision the 40dp floor
     * is gone; every button's action stays a chord away.
     */
    const val ITEM_ROW_HEIGHT = 2f
    const val VERTICAL_PAD = 0.375f
    const val HORIZONTAL_PAD = 0.5f

    /** `gap(Base08)` between the crumbs and the button group (toolbar.rs:136). */
    const val GROUP_GAP = 0.5f

    /** `gap_1` between breadcrumb segments (editor/src/element.rs:6813). */
    const val CRUMB_GAP = 0.25f

    /**
     * Zed's IconButton at `ButtonSize::Default`: `rems_from_px(22)`
     * (button_like.rs:465-473) around an `IconSize::Small` glyph,
     * `rems_from_px(14)` (icon.rs:74) — the exact button the quick action bar's
     * eye is (quick_action_bar/preview.rs:66-68).
     */
    const val BUTTON_BOX = 1.375f
    const val ICON = 0.875f
}

/**
 * The item row, with the accessibility floor on top of Zed's `h_8`:
 * `max(rem(2), the crumbs' ink)`. Exactly 32dp at the default, and taller only
 * once the *system's* font scale would otherwise clip a breadcrumb. See
 * [glyphHeight].
 */
private val ToolbarItemRowHeight: Dp
    @Composable @ReadOnlyComposable get() = maxOf(
        rem(ToolbarMetrics.ITEM_ROW_HEIGHT),
        glyphHeight(MaterialTheme.typography.bodyMedium),
    )

private val ToolbarVerticalPad: Dp
    @Composable @ReadOnlyComposable get() = rem(ToolbarMetrics.VERTICAL_PAD)

private val ToolbarHorizontalPad: Dp
    @Composable @ReadOnlyComposable get() = rem(ToolbarMetrics.HORIZONTAL_PAD)

private val ButtonBox: Dp
    @Composable @ReadOnlyComposable get() = rem(ToolbarMetrics.BUTTON_BOX)

private val IconSize: Dp
    @Composable @ReadOnlyComposable get() = rem(ToolbarMetrics.ICON)

/**
 * The row under the tab strip: Zed's toolbar — breadcrumbs on the left
 * (crates/breadcrumbs/src/breadcrumbs.rs), the quick action bar on the right.
 *
 * The breadcrumb text is the file name, then the engine's symbol path at the
 * caret ("impl Foo" › "fn bar"), separated by Zed's own `›` glyph in
 * `text.placeholder` with the segments muted (editor/src/element.rs:6793,
 * 6809). The trail is a button into the outline picker, which is Zed's own
 * wiring for it — and the picker's touch route.
 *
 * Quick actions: the magnifier toggles the search bar — the touch twin of
 * Ctrl+F, which Zed's quick action bar carries in the same spot — and for a
 * previewable file, Zed's eye (quick_action_bar/preview.rs). Beside them the
 * selections menu, Zed's `editor-selections-dropdown` on its I-beam
 * (quick_action_bar.rs:243-320).
 *
 * Zed's `toolbar` block decides which of the three parts are drawn:
 * `breadcrumbs`, `quick_actions` and `selections_menu` (default.json:544-555).
 * With all three off there is nothing left, and the caller draws no toolbar
 * at all rather than an empty bar with a border.
 */
@Composable
internal fun EditorToolbar(
    fileName: String,
    symbolPath: List<String>,
    onToggleSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** A tap on the crumbs — Zed wraps them in a button into the outline. */
    onOpenOutline: (() -> Unit)? = null,
    kind: PreviewKind? = null,
    isPreviewOpen: Boolean = false,
    onTogglePreview: (() -> Unit)? = null,
    /** The project symbol picker — the touch twin of Ctrl+T. */
    onOpenProjectSymbols: (() -> Unit)? = null,
    /**
     * The selections menu's rows, built by the caller because they are the
     * *editor's* actions and this file has no editor. Null draws no button,
     * which is also what `toolbar.selections_menu: false` does.
     */
    selectionsMenu: (() -> List<ContextMenuItem>)? = null,
) {
    val theme = LocalZedTheme.current
    val parts = LocalAppSettings.current.toolbar
    val underline = theme.color("border.variant")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("toolbar.background", theme.color("editor.background")))
            // The underline is drawn inside the frame, as gpui draws borders
            // (toolbar.rs:128-129).
            .drawBehind {
                val line = 1.dp.toPx()
                drawRect(
                    color = underline,
                    topLeft = Offset(0f, size.height - line),
                    size = Size(size.width, line),
                )
            }
            .padding(horizontal = ToolbarHorizontalPad, vertical = ToolbarVerticalPad)
            .height(ToolbarItemRowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rem(ToolbarMetrics.GROUP_GAP)),
    ) {
        // Breadcrumbs scroll off to the left rather than squeezing the
        // buttons, as Zed's `overflow_x_scroll` container does
        // (breadcrumbs.rs:53-55). The whole trail is one button into the
        // outline — Zed wraps it in a `ButtonLike` with `rounded_sm` and a
        // ghost hover (element.rs:6838-6839).
        val crumbInteraction = remember { MutableInteractionSource() }
        val crumbHovered by crumbInteraction.collectIsHoveredAsState()
        // Switched off, the crumbs leave their weight behind: the buttons stay
        // at the right edge rather than sliding to the left of an empty bar.
        if (!parts.breadcrumbs) Box(modifier = Modifier.weight(1f))
        if (parts.breadcrumbs) Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(rem(ZedRadius.SM)))
                .background(
                    if (crumbHovered && onOpenOutline != null) {
                        theme.color("ghost_element.hover", Color.Transparent)
                    } else {
                        Color.Transparent
                    }
                )
                .then(
                    if (onOpenOutline != null) {
                        Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(
                                interactionSource = crumbInteraction,
                                indication = null,
                                onClickLabel = "Open the outline",
                                onClick = onOpenOutline,
                            )
                    } else {
                        Modifier
                    }
                )
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(rem(ToolbarMetrics.CRUMB_GAP)),
        ) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text"),
                maxLines = 1,
            )
            for (segment in symbolPath) {
                Text(
                    // Zed's separator is the literal glyph, a Label in
                    // `text.placeholder` (element.rs:6809).
                    text = "›",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder"),
                    maxLines = 1,
                )
                Text(
                    text = segment,
                    style = MaterialTheme.typography.bodyMedium,
                    // Segments are `Color::Muted` (element.rs:6793).
                    color = theme.color("text.muted"),
                    maxLines = 1,
                )
            }
        }
        if (selectionsMenu != null && parts.selectionsMenu) {
            var open by remember { mutableStateOf(false) }
            Box {
                QuickActionButton(
                    icon = R.drawable.ic_ui_cursor_i_beam,
                    // Zed's own tooltip (quick_action_bar.rs:265).
                    label = "Selection Controls",
                    isOn = open,
                    onClick = { open = true },
                )
                if (open) {
                    ContextMenu(
                        expanded = true,
                        onDismiss = { open = false },
                        items = selectionsMenu(),
                        offset = DpOffset.Zero,
                    )
                }
            }
        }
        if (onToggleSearch != null && parts.quickActions) {
            QuickActionButton(
                icon = R.drawable.ic_ui_magnifying_glass,
                label = "Find in file",
                isOn = false,
                onClick = onToggleSearch,
            )
        }
        if (onOpenProjectSymbols != null && parts.quickActions) {
            QuickActionButton(
                icon = R.drawable.ic_ui_hash,
                label = "Search project symbols",
                isOn = false,
                onClick = onOpenProjectSymbols,
            )
        }
        if (kind != null && onTogglePreview != null && parts.quickActions) {
            val label = when (kind) {
                PreviewKind.Markdown -> "Preview Markdown"
                PreviewKind.Svg -> "Preview SVG"
                PreviewKind.Table -> "Preview as a table"
            }
            QuickActionButton(
                icon = R.drawable.ic_ui_eye,
                label = label,
                isOn = isPreviewOpen,
                onClick = onTogglePreview,
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: Int,
    label: String,
    isOn: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ButtonBox)
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            // Zed's 22dp glyph, with a finger-sized area around it.
            .touchTarget()
            // `Subtle`, a ghost button: transparent at rest,
            // `ghost_element.hover` under the pointer and
            // `ghost_element.active` while pressed, swapped instantly
            // (button_like.rs:298-303, 324-329).
            .background(
                when {
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = label,
            // A toggled IconButton keeps its ghost background and swaps the
            // glyph to `Color::Selected` = `text.accent`
            // (icon_button.rs:246-248, color.rs:108).
            colorFilter = ColorFilter.tint(
                if (isOn) {
                    theme.color("text.accent", theme.color("icon"))
                } else {
                    theme.color("text", theme.color("icon"))
                }
            ),
            modifier = Modifier.size(IconSize),
        )
    }
}
