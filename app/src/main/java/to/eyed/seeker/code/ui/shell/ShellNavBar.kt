package to.eyed.seeker.code.ui.shell

import android.content.res.Configuration
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
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

    val theme = LocalZedTheme.current
    // The one orientation branch in the shell, and it is a *height* rule, not
    // a width rule: at 400dp of height the label under the icon is 12dp that
    // the editor needs more (docs/UI.md, "Orientation"). There is no isWide,
    // no window-size class and no second layout behind it.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background", MaterialTheme.colorScheme.surface))
            // The gesture inset, below the bar's own height: the 56dp is the
            // touch target, and the handle's ~24dp is padding under it rather
            // than a slice out of it.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
            .height(if (landscape) LandscapeNavBarHeight else NavBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavItem(
            destination = Destination.Code,
            label = "Code",
            state = state,
            landscape = landscape,
            icon = { tint -> NavIcon(R.drawable.ic_file_code, tint) },
        )
        NavItem(
            destination = Destination.Agent,
            label = "Agent",
            state = state,
            landscape = landscape,
            icon = { tint -> NavIcon(R.drawable.ic_ui_agent, tint) },
            badge = { if (state.agentAttention) AttentionDot() },
        )
        NavItem(
            destination = Destination.Build,
            label = "Build",
            state = state,
            landscape = landscape,
            icon = { tint -> PlayGlyph(tint) },
            badge = { BuildBadgeSlot(state) },
        )
    }
}

/**
 * One item: icon, label, badge slot, and the whole 133dp column as the target.
 *
 * The target is the column rather than the glyph — this is the control the
 * thumb aims at most often in the app, and an icon-sized hit box in the corner
 * of a third of the screen is the classic phone-navigation mistake.
 */
@Composable
private fun RowScope.NavItem(
    destination: Destination,
    label: String,
    state: ShellState,
    landscape: Boolean,
    icon: @Composable (Color) -> Unit,
    badge: @Composable BoxScope.() -> Unit = {},
) {
    val theme = LocalZedTheme.current
    val selected = state.destination == destination
    val tint = if (selected) {
        theme.color("text.accent", MaterialTheme.colorScheme.primary)
    } else {
        theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .touchTarget()
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // No ripple: a 133dp splash on every destination switch is
                // motion the rest of this chrome does not have.
                indication = null,
            ) { state.show(destination) },
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon(tint)
            badge()
        }
        if (!landscape) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NavIcon(resource: Int, tint: Color) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(NavIconSize),
    )
}

/**
 * The ▶ of the Build item, drawn rather than imported: the icon set carried
 * over from Zed has no play glyph (it had no build button), and a triangle is
 * three lines of Path against a new asset and a licence note.
 */
@Composable
private fun PlayGlyph(tint: Color) {
    Canvas(modifier = Modifier.size(NavIconSize)) {
        val inset = size.minDimension * 0.18f
        val path = Path().apply {
            moveTo(inset, inset * 0.7f)
            lineTo(size.width - inset, size.height / 2f)
            lineTo(inset, size.height - inset * 0.7f)
            close()
        }
        drawPath(path, color = tint)
    }
}

/** ✦'s badge: "the agent finished, or is blocked, while you were elsewhere". */
@Composable
private fun BoxScope.AttentionDot() {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .offset(x = BadgeOffset, y = -BadgeOffset)
            .align(Alignment.TopEnd)
            .size(BadgeDot)
            .background(
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                shape = androidx.compose.foundation.shape.CircleShape,
            )
            .semantics { contentDescription = "The agent is waiting for you" },
    )
}

/**
 * ▶'s badge, and the most important cross-destination signal in the app: a
 * ring while a run is going, a red dot when the last one failed, a green tick
 * for ten seconds after a success (docs/UI.md, "Navigation" — BADGES).
 *
 * A 71-second build is a build you walk away from, so the outcome has to reach
 * the user wherever they went — which is why this is painted on the bar and
 * not only on the Build screen.
 */
@Composable
private fun BoxScope.BuildBadgeSlot(state: ShellState) {
    val theme = LocalZedTheme.current
    // The tick expires on a wall clock, so the badge needs a wake-up rather
    // than a recomposition it will not otherwise get. One timer, armed only
    // while a tick is actually on screen: an idle bar does not tick over.
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
    when (buildBadge(build, now)) {
        BuildBadge.None -> Unit
        BuildBadge.Ring -> RunningRing()
        BuildBadge.Failed -> Box(
            modifier = Modifier
                .offset(x = BadgeOffset, y = -BadgeOffset)
                .size(BadgeDot)
                .background(
                    color = theme.color("error", MaterialTheme.colorScheme.error),
                    shape = CircleShape,
                )
                .semantics { contentDescription = "The last run failed" },
        )
        BuildBadge.Tick -> Image(
            painter = painterResource(R.drawable.ic_ui_check),
            contentDescription = "The last run succeeded",
            colorFilter = ColorFilter.tint(
                theme.color("success", theme.color("created", Color(0xFF98C379)))
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = BadgeOffset, y = -BadgeOffset)
                .size(BadgeTick),
        )
    }
}

/**
 * The ring, drawn as a rotating arc around the glyph. It honours
 * `LocalReduceMotion` by standing still rather than by disappearing: the fact
 * that a build is running is information, and the spin is only how it is said
 * (Motion.kt — "a screen where half the motion stopped is worse than one where
 * none did", and vestibular disorders are why the setting exists).
 */
@Composable
private fun RunningRing() {
    val theme = LocalZedTheme.current
    val still = LocalReduceMotion.current
    val spin by rememberInfiniteTransition(label = "build").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "build-turn",
    )
    val colour = theme.color("text.accent", MaterialTheme.colorScheme.primary)
    Canvas(
        modifier = Modifier
            .size(RingSize)
            .rotate(if (still) 0f else spin)
            .semantics { contentDescription = "Running" },
    ) {
        drawArc(
            color = colour,
            startAngle = 0f,
            sweepAngle = if (still) 360f else 280f,
            useCenter = false,
            style = Stroke(width = RingStroke.toPx()),
        )
    }
}

/**
 * 56dp, with the accessibility floor on top of it: at a large font scale the
 * label's own ink decides, exactly as the status bar's height does
 * (StatusBar.kt, `StatusBarHeight`). Nothing in this bar truncates, and
 * nothing here is a rem — Zed has no bottom bar to be faithful to, and the
 * number the spec fixes is 56dp on a 400 x 890dp phone.
 */
private val NavBarHeight: Dp
    @Composable get() = maxOf(
        BarHeight,
        NavIconSize + glyphHeight(MaterialTheme.typography.labelSmall) + BarPadding * 2,
    )

/** Landscape: icon only, three targets at ~296dp each (docs/UI.md, "Orientation"). */
private val LandscapeNavBarHeight: Dp
    @Composable get() = maxOf(LandscapeBarHeight, NavIconSize + BarPadding * 2)

private val BarHeight = 56.dp
private val LandscapeBarHeight = 44.dp
private val NavIconSize = 20.dp
private val BadgeDot = 7.dp
private val BadgeTick = 11.dp
private val BadgeOffset = 9.dp
private val RingSize = 26.dp
private val RingStroke = 2.dp
private val BarPadding = 4.dp
