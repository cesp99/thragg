package to.eyed.seeker.code.ui.shell

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.components.SeekerSpinner
import to.eyed.seeker.code.ui.components.StatusDot
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.effectSpec
import to.eyed.seeker.code.ui.theme.glyphHeight

/**
 * The tab switcher: one floating capsule with the three destinations in it,
 * and a pill that slides between them.
 *
 * This is the only navigation chrome in the app. Four inherited models — the
 * pane tree, two docks, the tab strip and the palette-plus-keymap — die
 * together and are replaced by this capsule plus one back stack per
 * destination (docs/UI.md, "Navigation"). It is the SwiftUI shape of the
 * thing rather than the Material one: not a full-width bar with a hairline
 * over it, but a rounded object floating on the shell background with the
 * screen's own ground showing around it, built from the same two materials
 * as the empty state's disc — the house fill step and a hairline
 * (EmptyState.kt). The selection is a pill of the 16% primary wash
 * (docs/VISUAL.md, the `secondaryContainer` trap) that *travels*: it is one
 * object that moves to the tab you chose, never three that light up in turn.
 *
 * Two rules are enforced *here* rather than at the call site, because a rule a
 * caller can forget is not a rule:
 *
 *  1. **The bar hides whenever the IME is up.** Unconditional. In Code the
 *     44dp action row takes its place, docked on the keyboard; in Agent the
 *     composer does. Nowhere do the capsule, an action row and the keyboard
 *     coexist — the height is reclaimed precisely so the typing posture has
 *     ~454dp of buffer left (docs/UI.md, "Code with the soft keyboard up").
 *  2. **Setup takes the bar with it.** It is the one route that does; see
 *     [Route.hidesNavBar].
 *
 * Emitting nothing is how both are done: a composable that returns early takes
 * no space in the [Column] above it, so the destination gets the height back
 * rather than being laid out under a hidden bar.
 *
 * TAP AND DRAG ARE THE SAME MOTION. A tap sends the pill to the tapped tab on
 * a critically damped spring while the destination itself shows on the next
 * frame with no motion (SeekerShell.kt, `surfaceTransition`; a tab is tapped
 * tens of times a session). A drag anywhere on the bar takes the pill with
 * the finger, 1:1 and from wherever it was — grabbing it mid-flight stops
 * the flight, so a tap can be caught and reversed — with a tick as it
 * crosses into each slot. On release the pill is *thrown*: its landing slot
 * is the nearest one to where the finger's velocity would have carried it
 * ([settleSlot]), the spring continues at the finger's own speed so there is
 * no seam between the drag and the settle, and the screen slides the same
 * way ([ShellState.swipeTo]). Past either end the pill rubber-bands rather
 * than stopping dead ([rubberBand]). The drag is on the bar and not on the
 * content, because the content already owns horizontal gestures — the
 * editor scrolls long lines, the terminal selects, the transcript's chips
 * and code blocks scroll — and a pager under all of that would steal every
 * one of them.
 *
 * The one thing the pill does not do is answer a destination change that
 * came from elsewhere with a drag: a notification tap, a "Fix with agent",
 * the back gesture's "not Code → Code" all move the pill on the same tap
 * spring, driven off [ShellState.destination] so the capsule can never
 * disagree with the screen.
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
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    val slotPx = with(LocalDensity.current) { SlotWidth.toPx() }
    val last = Destination.entries.lastIndex

    // WHERE THE PILL IS, in slots: 0 is Code, 2 is Build, and 1.4 is a finger
    // most of the way from Agent to Build. One animatable for the rest state
    // and one nullable for "a finger has it": while the finger holds the pill
    // the drawn position is the drag and the animatable is stopped, so a
    // release can hand the spring the exact position and speed the finger let
    // go at.
    val pill = remember { Animatable(state.destination.ordinal.toFloat(), PillThreshold) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var anchor by remember { mutableFloatStateOf(0f) }
    var travel by remember { mutableFloatStateOf(0f) }
    // The slot the pill was last over during a drag, for the crossing tick.
    var over by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.destination) {
        val target = state.destination.ordinal.toFloat()
        // A release has already started this animation for its own target;
        // an unrelated change (a tap, a notification) starts it here.
        if (drag == null && pill.targetValue != target) {
            if (reduceMotion) pill.snapTo(target) else pill.animateTo(target, TapSpring)
        }
    }

    val shown = drag ?: pill.value
    val lit = shown.roundToInt().coerceIn(0, last)
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            // The shell's own ground, not `surface`: the capsule floats on
            // the same background the destination is drawn on, which is what
            // makes it float rather than sit on a bar.
            .background(scheme.background)
            .windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(vertical = BarPad)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    travel += delta
                    val position = rubberBand(anchor + travel / slotPx, last)
                    drag = position
                    val slot = position.roundToInt().coerceIn(0, last)
                    if (slot != over) {
                        over = slot
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                },
                onDragStarted = {
                    // Grabbed: wherever it is right now, mid-flight included.
                    pill.stop()
                    anchor = pill.value
                    travel = 0f
                    over = anchor.roundToInt().coerceIn(0, last)
                    drag = anchor
                },
                onDragStopped = { velocityPx ->
                    val position = drag ?: return@draggable
                    val velocity = velocityPx / slotPx
                    val target = settleSlot(position, velocity, last)
                    if (target != over) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    // Continuous: the animatable takes over at the drag's
                    // last position before the drag stops being drawn.
                    pill.snapTo(position)
                    drag = null
                    state.swipeTo(Destination.entries[target])
                    if (reduceMotion) {
                        pill.snapTo(target.toFloat())
                    } else {
                        pill.animateTo(target.toFloat(), ThrowSpring, initialVelocity = velocity)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val slotHeight = if (landscape) LandscapeSlotHeight else SlotHeight
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(scheme.surfaceContainer)
                .border(MD.hairline, scheme.outlineVariant, CircleShape)
                .padding(CapsulePad),
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((shown * slotPx).roundToInt(), 0) }
                    .size(SlotWidth, slotHeight)
                    .background(scheme.primary.copy(alpha = 0.16f), CircleShape),
            )
            Row(modifier = Modifier.selectableGroup()) {
                NavItem(
                    destination = Destination.Code,
                    label = "Code",
                    state = state,
                    landscape = landscape,
                    lit = lit == Destination.Code.ordinal,
                    icon = R.drawable.ic_file_code,
                )
                NavItem(
                    destination = Destination.Agent,
                    label = "Agent",
                    state = state,
                    landscape = landscape,
                    lit = lit == Destination.Agent.ordinal,
                    icon = R.drawable.ic_ui_agent,
                    badge = { if (state.agentAttention) AttentionDot() },
                )
                // One glyph per slot: while a run is going the spinner IS the
                // icon, and for ten seconds after a success the green tick IS
                // the icon — never a mark painted over the ▶. The overlay
                // versions drew a 26dp arc, and then an 11dp check, across a
                // 24dp triangle: two marks fighting for the same 24dp,
                // unreadable at a glance. The braille spinner is the app's
                // one "running" mark (SeekerSpinner.kt); the tick takes the
                // slot for its [BuildState.SUCCESS_TICK_MS] and hands the ▶
                // back. Only Failed stays a corner dot — it coexists with the
                // ▶ indefinitely, which is exactly what a badge is for.
                val buildBadge = currentBuildBadge(state)
                NavItem(
                    destination = Destination.Build,
                    label = "Build",
                    state = state,
                    landscape = landscape,
                    lit = lit == Destination.Build.ordinal,
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
}

/**
 * One slot: the glyph, the label under it in portrait, and the badge slot
 * over the glyph's top-right corner. The pill is not here — it is one object
 * drawn under the row — so what a slot owns is its ink, which follows the
 * pill: [lit] is "the pill is over me right now", true for the tab being
 * dragged toward before it is selected, and it cross-fades on [effectSpec]
 * so the ink arrives with the pill rather than snapping a beat after it.
 *
 * The whole [SlotWidth] column is the target — an icon-sized hit box in the
 * corner of a tab is the classic phone-navigation mistake — and it has the
 * `Tab` role and the selected state in semantics, which is the part of the
 * stock component worth keeping. The label carries the destination's name
 * for a screen reader in portrait, so the glyph is decoration there; in
 * landscape there is no label and the glyph has to say it instead. One node
 * either way, never two saying "Code" twice.
 */
@Composable
private fun NavItem(
    destination: Destination,
    label: String,
    state: ShellState,
    landscape: Boolean,
    lit: Boolean,
    icon: Int,
    running: () -> Boolean = { false },
    succeeded: () -> Boolean = { false },
    badge: @Composable BoxScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val ink by animateColorAsState(
        targetValue = if (lit) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = effectSpec(),
        label = "nav-ink",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(SlotWidth)
            .height(if (landscape) LandscapeSlotHeight else SlotHeight)
            .clip(CircleShape)
            .selectable(
                selected = state.destination == destination,
                interactionSource = interaction,
                // No ripple: the pill answering the finger is the feedback,
                // and a wash under a wash is two things saying one thing.
                indication = null,
                role = Role.Tab,
                onClick = { state.show(destination) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            Box(contentAlignment = Alignment.Center) {
                if (running()) {
                    // The slot's whole glyph, not a decoration on one — see
                    // the Build item's note at the call site.
                    Box(
                        modifier = Modifier.semantics {
                            contentDescription = "$label — running"
                        },
                    ) {
                        SeekerSpinner(size = IconSize.Nav, color = ink)
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
                        tint = ink,
                        size = IconSize.Nav,
                    )
                }
                badge()
            }
            if (!landscape) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    maxLines = 1,
                    modifier = Modifier.padding(top = LabelGap),
                )
            }
        }
    }
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
 * Where a pill let go at [position] slots, moving at [velocity] slots per
 * second, comes to rest: the slot nearest to where that speed would have
 * carried it, clamped to `0..last`.
 *
 * The projection is the exponential-decay one every scroll view uses
 * (`v · d / (1 − d)` per millisecond of velocity, [DECELERATION]), not the
 * release point — a flick that lets go early still lands on the next tab,
 * because the pill is thrown, not placed. The thrown distance is capped at
 * one slot, so no flick jumps from Code to Build over Agent; a *drag* that
 * genuinely crossed two slots still lands two slots away, because the finger
 * went there.
 */
internal fun settleSlot(position: Float, velocity: Float, last: Int): Int {
    val thrown = (velocity / 1000f * DECELERATION / (1f - DECELERATION)).coerceIn(-1f, 1f)
    return (position + thrown).roundToInt().coerceIn(0, last)
}

/**
 * The pill past either end of the capsule: it follows the finger less the
 * further out it is, and never more than about a third of a slot, so the end
 * reads as "responsive, but there is nothing more here" rather than as
 * frozen. Inside `0..last` the position is returned untouched.
 */
internal fun rubberBand(position: Float, last: Int): Float {
    val over = when {
        position < 0f -> position
        position > last -> position - last
        else -> return position
    }
    val give = over * RUBBER / (1f + RUBBER * abs(over))
    return if (over < 0f) give else last + give
}

/**
 * 88dp per tab: three of them make a 272dp capsule, which is a floating
 * object on a 400dp screen rather than a bar with its corners rounded, and
 * still a target more than half again the 48dp floor.
 */
private val SlotWidth = 88.dp

private val SlotPadY = 4.dp
private val LandscapeSlotPadY = 6.dp
private val LabelGap = 2.dp

/**
 * The pill's height in portrait: glyph, gap, label, and 4dp of pill above
 * and below the pair. `labelSmall` is 11sp fixed in [materialTypography], so
 * this is 24 + 2 + ~15 + 8 = 49dp; at a large font scale the label's own ink
 * decides, exactly as the status bar's height does (StatusBar.kt).
 */
private val SlotHeight: Dp
    @Composable get() = IconSize.Nav + LabelGap + glyphHeight(MaterialTheme.typography.labelSmall) +
        SlotPadY * 2

/** Landscape: icon only, 24dp with 6dp of pill above and below. */
private val LandscapeSlotHeight = IconSize.Nav + LandscapeSlotPadY * 2


/** The capsule's wall around the pill: the one 4dp the pill never covers. */
private val CapsulePad = MD.space1

/** Ground above and below the capsule, so it floats rather than docks. */
private val BarPad = MD.space2

private val BadgeDot = 7.dp

/**
 * How far past the glyph's top-right corner the badge sits. The slot is
 * clipped to its pill and the glyph is 4dp under the pill's top, so a dot
 * pushed 9dp out was cut to a sliver on the pill's rim — a strange mark
 * hanging over the icon rather than a dot on it. 2dp keeps the whole dot
 * inside the slot, on the empty corner of the ✦ and the ▶.
 */
private val BadgeOffset = 2.dp

/**
 * The tap spring: critically damped, so the pill arrives without a wobble —
 * a wobble on a thing tapped a hundred times a day is a delay a hundred
 * times a day. Stiffness 400 settles in about 350ms, Apple's "move" response.
 */
private val TapSpring = spring<Float>(dampingRatio = 1f, stiffness = 400f, visibilityThreshold = PillThreshold)

/**
 * The release spring: a little under-damped, because the gesture that
 * preceded it carried momentum and a throw that lands dead reads as caught.
 * The overshoot is a few pixels and only ever happens after a flick.
 */
private val ThrowSpring = spring<Float>(dampingRatio = 0.8f, stiffness = 400f, visibilityThreshold = PillThreshold)

/**
 * 0.002 of a slot — under a pixel — so the spring runs to the last pixel and
 * the settle is not a 3px snap at the end of it.
 */
private const val PillThreshold = 0.002f

/**
 * Between a scroll view's 0.998 and its "fast" 0.99: about a fifth of a
 * second of the release velocity. Measured on the phone — at 0.998 a slow,
 * steady drag from Build that stopped over Agent was carried on to Code,
 * because half a second of even a slow finger is most of a slot; at this
 * rate a slow drag lands where it stopped and a real flick (1000px/s and
 * up) still clears the half-slot it needs.
 */
private const val DECELERATION = 0.995f

/** How much the pill follows a finger past the end: about half, decaying. */
private const val RUBBER = 0.55f
