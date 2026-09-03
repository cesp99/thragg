package to.eyed.seeker.code.ui.shell

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SeekerSpinner
import to.eyed.seeker.code.ui.components.StatusDot
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.glyphHeight
import to.eyed.seeker.code.ui.theme.touchTarget

/**
 * The bottom navigation bar: three items, and nothing else is a destination.
 *
 * This is the only navigation chrome in the app. Four inherited models — the
 * pane tree, two docks, the tab strip and the palette-plus-keymap — die
 * together and are replaced by this bar plus one back stack per destination
 * (docs/UI.md, "Navigation"). Three items at ~133dp on a 400dp screen are
 * comfortably thumb-reachable across the full width, left hand or right.
 *
 * It is a real `NavigationBar` of real `NavigationBarItem`s now, and that is
 * the whole change (docs/VISUAL.md, P13). The hand-rolled `Row` this replaces
 * carried a comment refusing the ripple because "a 133dp splash on every
 * destination switch is motion the rest of this chrome does not have" — which
 * was the right complaint about the wrong control. M3's selection state is a
 * **pill shape** behind the glyph, not a splash across the column: it says
 * which destination you are on without animating a third of the screen, which
 * is exactly what that comment was asking for. What the stock component brings
 * with it is the part nobody hand-rolls twice: the `Tab` role and the selected
 * state in semantics, the gesture-inset handling, and the item spacing.
 *
 * Two rules are enforced *here* rather than at the call site, because a rule a
 * caller can forget is not a rule:
 *
 *  1. **The bar hides whenever the IME is up.** Unconditional. In Code the
 *     44dp action row takes its place, docked on the keyboard; in Agent the
 *     composer does. Nowhere do the nav bar, an action row and the keyboard
 *     coexist — the 56dp is reclaimed precisely so the typing posture has ~454dp
 *     of buffer left (docs/UI.md, "Code with the soft keyboard up").
 *  2. **Setup takes the bar with it.** It is the one route that does; see
 *     [Route.hidesNavBar].
 *
 * Emitting nothing is how both are done: a composable that returns early takes
 * no space in the [Column] above it, so the destination gets the height back
 * rather than being laid out under a hidden bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShellNavBar(state: ShellState, modifier: Modifier = Modifier) {
    if (WindowInsets.isImeVisible) return
    if (state.currentStack.hidesNavBar) return

    // The one orientation branch in the shell, and it is a *height* rule, not
    // a width rule: at 400dp of height the label under the icon is 12dp that
    // the editor needs more (docs/UI.md, "Orientation"). There is no isWide,
    // no window-size class and no second layout behind it.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Read once and used twice: the component is told to draw the inset, and
    // the height is told to make room for it. Taking them from the same value
    // is what keeps the two from disagreeing.
    val barInsets = NavigationBarDefaults.windowInsets
    val bottomInset = with(LocalDensity.current) { barInsets.getBottom(this).toDp() }
    Column(modifier = modifier) {
        // The seam over the bar. With elevation pinned to zero in both halves
        // there is no shadow to separate chrome from content, so the hairline
        // is the edge (docs/VISUAL.md, "Foundations", ELEVATION).
        HairlineDivider()
        NavigationBar(
            // `surface`, the same ink as the top bar, rather than the stock
            // `surfaceContainer`: the two bars frame one screen and a bottom
            // bar a step lighter than the top one reads as a floating panel.
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            windowInsets = barInsets,
            // MEASURED ON DEVICE, and the reason this is arithmetic rather than
            // a constant. `NavigationBar` applies its window insets *inside*
            // the height it is given — the inset is padding taken OUT of the
            // box, not added under it — so a flat `height(56.dp)` handed the
            // three items 56 minus the gesture handle's ~24dp to lay out in,
            // and they were drawn clipped: the top of every glyph sliced off,
            // the selection pill cut in half, and the handle sitting on the
            // labels. The inset is therefore added back explicitly, so the
            // spec's 56dp is 56dp of *bar* with the handle below it.
            modifier = Modifier.height(
                (if (landscape) LandscapeNavBarHeight else NavBarHeight) + bottomInset,
            ),
        ) {
            NavItem(
                destination = Destination.Code,
                label = "Code",
                state = state,
                landscape = landscape,
                icon = R.drawable.ic_file_code,
            )
            NavItem(
                destination = Destination.Agent,
                label = "Agent",
                state = state,
                landscape = landscape,
                icon = R.drawable.ic_ui_agent,
                badge = { if (state.agentAttention) AttentionDot() },
            )
            // One glyph per slot: while a run is going the spinner IS the
            // icon, and for ten seconds after a success the green tick IS the
            // icon — never a mark painted over the ▶. The overlay versions
            // drew a 26dp arc, and then an 11dp check, across a 24dp triangle:
            // two marks fighting for the same 24dp, unreadable at a glance.
            // The braille spinner is the app's one "running" mark
            // (SeekerSpinner.kt); the tick takes the slot for its
            // [BuildState.SUCCESS_TICK_MS] and hands the ▶ back. Only Failed
            // stays a corner dot — it coexists with the ▶ indefinitely, which
            // is exactly what a badge is for.
            val buildBadge = currentBuildBadge(state)
            NavItem(
                destination = Destination.Build,
                label = "Build",
                state = state,
                landscape = landscape,
                icon = R.drawable.ic_ui_play,
                running = { buildBadge == BuildBadge.Ring },
                succeeded = { buildBadge == BuildBadge.Tick },
                badge = {
                    if (buildBadge == BuildBadge.Failed) {
                        StatusDot(
                            color = MaterialTheme.colorScheme.error,
                            size = BadgeDot,
                            contentDescription = "The last run failed",
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = BadgeOffset, y = -BadgeOffset),
                        )
                    }
                },
            )
        }
    }
}

/**
 * One item: the glyph in its selection pill, the label under it, and the badge
 * slot over the glyph's top-right corner.
 *
 * The whole ~133dp column is the target, which `NavigationBarItem` does for
 * free — an icon-sized hit box in the corner of a third of the screen is the
 * classic phone-navigation mistake, and [touchTarget] is applied on top of it
 * so the floor holds at any font scale.
 *
 * The label carries the destination's name for a screen reader in portrait, so
 * the glyph is decoration there; in landscape there is no label and the glyph
 * has to say it instead. One node either way, never two saying "Code" twice.
 */
@Composable
private fun RowScope.NavItem(
    destination: Destination,
    label: String,
    state: ShellState,
    landscape: Boolean,
    icon: Int,
    running: () -> Boolean = { false },
    succeeded: () -> Boolean = { false },
    badge: @Composable BoxScope.() -> Unit = {},
) {
    val selected = state.destination == destination
    NavigationBarItem(
        selected = selected,
        onClick = { state.show(destination) },
        modifier = Modifier.touchTarget(),
        icon = {
            Box(contentAlignment = Alignment.Center) {
                if (running()) {
                    // The slot's whole glyph, not a decoration on one — see
                    // the Build item's note at the call site.
                    Box(
                        modifier = Modifier.semantics {
                            contentDescription = "$label — running"
                        },
                    ) {
                        SeekerSpinner(size = IconSize.Nav, color = LocalContentColor.current)
                    }
                } else if (succeeded()) {
                    SeekerIcon(
                        icon = R.drawable.ic_ui_check,
                        contentDescription = "$label — the last run succeeded",
                        // The solved added ink rather than `created` raw: this
                        // is a mark on the Material half, where inks clear
                        // 4.5:1 against the ground they sit on — Ayu Light
                        // draws `created` at 2.11:1 (docs/VISUAL.md, "THE
                        // HYBRID"). The green is the information here, so it
                        // overrides the slot's selection tint.
                        tint = LocalSeekerColors.current.addedMark,
                        size = IconSize.Nav,
                    )
                } else {
                    SeekerIcon(
                        icon = icon,
                        contentDescription = if (landscape) label else null,
                        // Null: `NavigationBarItem` tints its own icon slot through
                        // `LocalContentColor`, which is how the selected and
                        // unselected inks below reach it.
                        tint = LocalContentColor.current,
                        size = IconSize.Nav,
                    )
                }
                badge()
            }
        },
        label = if (landscape) null else { { Text(text = label, maxLines = 1) } },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            // 16% is the design's selection wash, the same one a selected
            // option card takes; `secondaryContainer` — M3's default here — is
            // a *rung of the fill ladder* in this scheme (it is Zed's
            // `element.selected`), so using it would make the pill read as
            // another raised surface rather than as a state.
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/** ✦'s badge: "the agent finished, or is blocked, while you were elsewhere". */
@Composable
private fun BoxScope.AttentionDot() {
    StatusDot(
        color = MaterialTheme.colorScheme.primary,
        size = BadgeDot,
        contentDescription = "The agent is waiting for you",
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = BadgeOffset, y = -BadgeOffset),
    )
}

/**
 * ▶'s state, resolved on a wall clock: the most important cross-destination
 * signal in the app (docs/UI.md, "Navigation" — BADGES). A 71-second build is
 * a build you walk away from, so the outcome has to reach the user wherever
 * they went — which is why the bar carries it and not only the Build screen.
 *
 * How each state is *drawn* is the Build item's call site: running and the
 * success tick swap the glyph itself, and only Failed is a corner dot. The
 * tick was a corner mark too — an 11dp green check lapped over the ▶ — and
 * that was the "two marks in one 24dp slot" overlay this file already removed
 * once for the running ring, made worse by being on screen for exactly ten
 * seconds: ink that appears over a glyph and then vanishes reads as a glitch,
 * not a report.
 *
 * The tick expires on that wall clock, so this needs a wake-up rather than a
 * recomposition it will not otherwise get. One timer, armed only while a tick
 * is actually on screen: an idle bar does not tick over.
 */
@Composable
private fun currentBuildBadge(state: ShellState): BuildBadge {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val build = state.build
    LaunchedEffect(build) {
        now = System.currentTimeMillis()
        val success = build as? BuildState.Succeeded ?: return@LaunchedEffect
        val left = BuildState.SUCCESS_TICK_MS - (now - success.at)
        if (left > 0) {
            delay(left)
            now = System.currentTimeMillis()
        }
    }
    return buildBadge(build, now)
}

/**
 * 56dp, with the accessibility floor on top of it: at a large font scale the
 * label's own ink decides, exactly as the status bar's height does
 * (StatusBar.kt, `StatusBarHeight`). Nothing in this bar truncates, and
 * nothing here is a rem — Zed has no bottom bar to be faithful to, and the
 * number the spec fixes is 56dp on a 400 x 890dp phone.
 *
 * The arithmetic gained [IndicatorPad] when the items became real ones: M3
 * draws the selection pill 4dp taller than the glyph on each side, and a
 * height that did not account for it would clip the pill rather than the
 * label. `labelSmall` is 11sp fixed in [materialTypography] now, so the
 * portrait sum is 24 + 8 + ~14 + 8 = 54dp and the 56 still wins — as it did
 * before, and as it stops doing somewhere north of a 2.0 system font scale,
 * which is exactly when it should.
 */
private val NavBarHeight: Dp
    @Composable get() = maxOf(
        BarHeight,
        NavIconSize + IndicatorPad * 2 + glyphHeight(MaterialTheme.typography.labelSmall) +
            BarPadding * 2,
    )

/** Landscape: icon only, three targets at ~296dp each (docs/UI.md, "Orientation"). */
private val LandscapeNavBarHeight: Dp
    @Composable get() = maxOf(LandscapeBarHeight, NavIconSize + IndicatorPad * 2 + BarPadding * 2)

private val BarHeight = 56.dp
private val LandscapeBarHeight = 44.dp
/**
 * 24dp, which is what a Material navigation bar draws and what every other
 * bottom bar on the phone draws beside it. It was 20dp, and 20dp is the reason
 * this bar's three items read as smaller than the system's own chrome on a
 * 480dpi screen — an icon is not text and does not get to be dense.
 *
 * See [IconSize] for where the rest of the app's icon sizes live; this one is
 * spelled out here because [NavBarHeight] is arithmetic that has to read.
 */
private val NavIconSize = IconSize.Nav

/** Half the difference between M3's 32dp selection pill and its 24dp glyph. */
private val IndicatorPad = 4.dp
private val BadgeDot = 7.dp
private val BadgeOffset = 9.dp
private val BarPadding = 4.dp
