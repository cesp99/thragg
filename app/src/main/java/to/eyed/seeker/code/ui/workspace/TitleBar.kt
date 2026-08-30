package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.GitBranch
import to.eyed.seeker.code.ui.theme.LocalUiFontSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.glyphHeight
import to.eyed.seeker.code.ui.theme.rem
import to.eyed.seeker.code.ui.theme.remsAt

/**
 * The title bar's metrics as rem multiples — `ui_font_size` is the rem
 * (theme_settings/src/settings.rs:619) — held as bare numbers so the table is
 * checkable on the host (`ChromeMetricsTest`).
 */
internal object TitleBarMetrics {

    /**
     * Zed's bar is `(1.75 * rem_size).max(px(34.))`
     * (ui/src/utils/constants.rs:16-19) — a rem *with a pixel floor*, which is
     * why it is 34 rather than 28 at the default: the floor wins until
     * `ui_font_size` passes 19.43. [barHeight] is that `max`, not one or the
     * other.
     */
    const val HEIGHT = 1.75f
    val HEIGHT_FLOOR = 34.dp

    /**
     * Everything that sits in the bar is a ButtonLike at `ButtonSize::Default`:
     * `rems_from_px(22)` tall (button_like.rs:465-473) with `px(Base04)` inside
     * (button_like.rs:800-801), `rounded_sm` corners (button_like.rs:527) and
     * `gap(Base04)` between its children (button_like.rs:797).
     */
    const val BUTTON_HEIGHT = 1.375f
    const val BUTTON_PAD = 0.25f
    const val BUTTON_GAP = 0.25f

    /** `gap_0p5` between the left group's buttons (title_bar.rs:292). */
    const val LEFT_GROUP_GAP = 0.125f

    /** `pl_2` — the bar's only outer padding (title_bar.rs:417). */
    const val LEADING_PAD = 0.5f

    /** `IconSize::XSmall` = `rems_from_px(12)` (icon.rs:73), the branch glyph. */
    const val XSMALL_ICON = 0.75f

    /** How wide the ☰ menu opens. Ours; a measure of the text in it. */
    const val MENU_MIN_WIDTH = 16.25f

    /** Zed's ListSeparator: 6px above and below the rule (list_separator.rs:9-12). */
    const val SEPARATOR_GAP = 0.375f

    /** An inset ListItem: 4px of surface around each row (list_item.rs:309). */
    const val MENU_INSET = 0.25f

    /** The row itself: `rounded_sm` with `px(Base06)` inside (list_item.rs:364). */
    const val MENU_ROW_PAD_X = 0.375f
    const val MENU_ROW_PAD_Y = 0.125f

    /** `ml_4` between a label and its keybinding (context_menu.rs:2089). */
    const val LABEL_TO_CHORD = 1f

    fun barHeight(uiFontSize: Float): Dp = maxOf(remsAt(uiFontSize, HEIGHT), HEIGHT_FLOOR)
}

/**
 * The bar and its buttons, each with the accessibility floor on top of Zed's
 * metric: `max(Zed's number, the label's ink)`. At every ordinary font scale
 * these are exactly [TitleBarMetrics.barHeight] and `rem(1.375f)`; they grow
 * only once the *system's* font scale has made the text taller than the box
 * Zed specifies. See [glyphHeight].
 */
private val TitleBarHeight: Dp
    @Composable @ReadOnlyComposable get() = maxOf(
        TitleBarMetrics.barHeight(LocalUiFontSize.current),
        glyphHeight(MaterialTheme.typography.bodyMedium),
    )

private val BarButtonHeight: Dp
    @Composable @ReadOnlyComposable get() = maxOf(
        rem(TitleBarMetrics.BUTTON_HEIGHT),
        glyphHeight(MaterialTheme.typography.bodyMedium),
    )

private val BarButtonPad: Dp
    @Composable @ReadOnlyComposable get() = rem(TitleBarMetrics.BUTTON_PAD)

private val BarButtonGap: Dp
    @Composable @ReadOnlyComposable get() = rem(TitleBarMetrics.BUTTON_GAP)

/** One entry in the menu: what it does, and the chord that also does it. */
data class MenuAction(
    val label: String,
    val shortcut: String?,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The workspace title bar, in the shape Zed uses: a menu button on the left,
 * then what you have open.
 *
 * Project- and file-level commands live here rather than in the status bar.
 * The status bar is for *state* — where the cursor is, what language, what
 * the panel is doing — and the top bar is for *actions*, which is both Zed's
 * split and the one that survives the soft keyboard covering the bottom of
 * the screen.
 */
@Composable
fun TitleBar(
    projectName: String?,
    filePath: String?,
    isDirty: Boolean,
    menuGroups: List<List<MenuAction>>,
    modifier: Modifier = Modifier,
    /** The branch the project is on, as Zed shows it beside the name. */
    branch: GitBranch? = null,
    /** Opens the branch picker, which is what Zed's branch control leads to. */
    onBranch: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The left group's rhythm is `gap_0p5` = 2px between the buttons,
        // whose own 4px `px` makes the visible gap (title_bar.rs:292).
        horizontalArrangement = Arrangement.spacedBy(rem(TitleBarMetrics.LEFT_GROUP_GAP)),
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(theme.color("title_bar.background", theme.color("tab_bar.background")))
            // Left only, as Zed's `pl_2`: the right end is a button group
            // that brings its own padding (title_bar/src/title_bar.rs:417).
            .padding(start = rem(TitleBarMetrics.LEADING_PAD)),
    ) {
        Box {
            val menuInteraction = remember { MutableInteractionSource() }
            val menuHovered by menuInteraction.collectIsHoveredAsState()
            val menuPressed by menuInteraction.collectIsPressedAsState()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(BarButtonHeight)
                    .clip(RoundedCornerShape(rem(ZedRadius.SM)))
                    // A ghost button's states, swapped instantly: `Subtle` is
                    // `ghost_element.hover` under the pointer and
                    // `ghost_element.active` while pressed
                    // (button_like.rs:298-303, 324-329).
                    .background(
                        when {
                            menuPressed -> theme.color("ghost_element.active", Color.Transparent)
                            menuHovered -> theme.color("ghost_element.hover", Color.Transparent)
                            else -> Color.Transparent
                        }
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = menuInteraction, indication = null) {
                        menuOpen = true
                    }
                    .padding(horizontal = BarButtonPad),
            ) {
                Text(
                    text = "☰",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                // Zed's `elevation_2`: an elevated surface, `rounded_lg` 8px,
                // and a 1px border in `border.variant` (styled_ext.rs:6-12) —
                // the same container every context menu wears
                // (context_menu.rs:2274).
                shape = RoundedCornerShape(rem(ZedRadius.LG)),
                border = BorderStroke(1.dp, theme.color("border.variant")),
                containerColor = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
            ) {
                // Scrollable, and it has to be: the menu has outgrown the
                // screen. Material's DropdownMenu clips to the window and does
                // not scroll on its own, so the last entries — settings, and
                // removing the userland — were simply unreachable on a
                // 674dp-tall window, and worse on a phone.
                // Bounded *and* scrollable, in that order: DropdownMenu
                // measures its content with an infinite maximum height, and a
                // scroller inside that throws. Capping it against the window
                // is what gives the scroller something finite to work with —
                // and without the cap the menu simply ran off the bottom of
                // the screen, taking settings and "remove userland" with it.
                val maxMenuHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
                // Entries are inset ListItems: 4px of surface around each row,
                // the row itself `rounded_sm` with 6px inside
                // (list_item.rs:309, 364, 405).
                Column(
                    modifier = Modifier
                        .widthIn(min = rem(TitleBarMetrics.MENU_MIN_WIDTH))
                        .heightIn(max = maxMenuHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = rem(TitleBarMetrics.MENU_INSET))
                ) {
                    menuGroups.forEachIndexed { index, group ->
                        if (index > 0) {
                            // Zed's ListSeparator: 1px of `border.variant`
                            // with 6px above and below (list_separator.rs:9-12).
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = rem(TitleBarMetrics.SEPARATOR_GAP))
                                    // The rule itself is `border_1` — px(1.),
                                    // and it stays one pixel.
                                    .height(1.dp)
                                    .background(theme.color("border.variant")),
                            )
                        }
                        for (action in group) {
                            MenuRow(action) { menuOpen = false }
                        }
                    }
                }
            }
        }

        if (projectName != null) {
            // In Zed this is a Button opening the recent-projects picker —
            // `LabelSize::Small` in `text`, ghost hover, `rounded_sm`
            // (title_bar.rs:841-853). We have no recent-projects picker yet,
            // so the ButtonLike dress stays and the popover does not: hover
            // per the grammar, but no hand cursor over a control that has
            // nothing to do when clicked.
            val nameInteraction = remember { MutableInteractionSource() }
            val nameHovered by nameInteraction.collectIsHoveredAsState()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(BarButtonHeight)
                    .clip(RoundedCornerShape(rem(ZedRadius.SM)))
                    .background(
                        if (nameHovered) {
                            theme.color("ghost_element.hover", Color.Transparent)
                        } else {
                            Color.Transparent
                        }
                    )
                    .hoverable(nameInteraction)
                    .padding(horizontal = BarButtonPad),
            ) {
                Text(
                    text = projectName,
                    // LabelSize::Small = 12px (title_bar.rs:842) — labelMedium
                    // on our scale — at the plain 400 weight Zed's labels keep.
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Zed's own order: the app menu, the project, then the branch
        // (title_bar/src/title_bar.rs). A repository with no commits yet has a
        // branch name and nothing on it, which is worth seeing too.
        if (branch != null) {
            val branchInteraction = remember { MutableInteractionSource() }
            val branchHovered by branchInteraction.collectIsHoveredAsState()
            val branchPressed by branchInteraction.collectIsPressedAsState()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BarButtonGap),
                modifier = Modifier
                    .height(BarButtonHeight)
                    .clip(RoundedCornerShape(rem(ZedRadius.SM)))
                    .background(
                        when {
                            branchPressed && onBranch != null ->
                                theme.color("ghost_element.active", Color.Transparent)
                            branchHovered ->
                                theme.color("ghost_element.hover", Color.Transparent)
                            else -> Color.Transparent
                        }
                    )
                    .then(
                        if (onBranch != null) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    interactionSource = branchInteraction,
                                    indication = null,
                                    // Zed's branch button opens the branch
                                    // picker (title_bar.rs:1050-1058); its
                                    // tooltip is "Branch & Stash", but ours
                                    // has no stash tab to promise.
                                    onClickLabel = "Switch Branch",
                                    onClick = onBranch,
                                )
                        } else {
                            Modifier.hoverable(branchInteraction)
                        }
                    )
                    .padding(horizontal = BarButtonPad),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_ui_git_branch),
                    contentDescription = null,
                    // `IconSize::XSmall` = 12px in `Color::Muted`, exactly the
                    // branch button's start icon (title_bar.rs:1043-1047).
                    colorFilter = ColorFilter.tint(
                        theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                    ),
                    modifier = Modifier.size(rem(TitleBarMetrics.XSMALL_ICON)),
                )
                Text(
                    // The branch label is `LabelSize::Small` in `Color::Muted`
                    // (title_bar.rs:1038-1042).
                    text = branch.name ?: "no branch",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                )
                // Ahead and behind, in git's own arrows — the same pair the
                // git panel's header shows.
                val drift = buildString {
                    if (branch.ahead > 0) append("↑${branch.ahead}")
                    if (branch.behind > 0) {
                        if (isNotEmpty()) append(' ')
                        append("↓${branch.behind}")
                    }
                }
                if (drift.isNotEmpty()) {
                    Text(
                        text = drift,
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color(
                            "text.muted",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
        if (filePath != null) {
            Text(
                text = if (isDirty) "$filePath •" else filePath,
                style = MaterialTheme.typography.labelMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = BarButtonPad)
                    .weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun MenuRow(action: MenuAction, onChosen: () -> Unit) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // `ml_4` between a label and its keybinding (context_menu.rs:2089).
        horizontalArrangement = Arrangement.spacedBy(rem(TitleBarMetrics.LABEL_TO_CHORD)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            // A ghost row: `ghost_element.hover` under the pointer,
            // `ghost_element.active` while pressed (list_item.rs:380-385).
            .background(
                when {
                    !action.enabled -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (action.enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        // Instant colour swap, no ripple, as everywhere in Zed.
                        .clickable(interactionSource = interaction, indication = null) {
                            onChosen()
                            action.onClick()
                        }
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = rem(TitleBarMetrics.MENU_ROW_PAD_X),
                vertical = rem(TitleBarMetrics.MENU_ROW_PAD_Y),
            ),
    ) {
        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (action.enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                theme.color("text.disabled", MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.weight(1f),
        )
        if (action.shortcut != null) {
            Text(
                text = action.shortcut,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
