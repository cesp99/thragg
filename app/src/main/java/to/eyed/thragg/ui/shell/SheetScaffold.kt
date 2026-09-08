package to.eyed.thragg.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import to.eyed.thragg.ui.components.BottomActions
import to.eyed.thragg.ui.theme.LocalReduceMotion
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.throwSpec
import to.eyed.thragg.ui.theme.touchTarget

/**
 * The one shape every modal surface in the app takes.
 *
 * There are no docks and nothing is ever side by side (docs/UI.md,
 * "Navigation"): Files & Find, Projects & tools, Cluster, Wallet, Deploy
 * confirm, the agent picker, the @-mention picker, Commit, the ⋮ overflow, the
 * file long-press menu, the code-actions sheet and the unsaved-changes confirm
 * are all *this*. Hosting them through one scaffold is what makes three
 * behaviours true of all of them at once instead of true of whichever ones
 * their author remembered:
 *
 *  1. **The field is pinned at the bottom.** Any sheet with a text field puts
 *     it at the *bottom* of the sheet so the IME lands directly under it and
 *     the results scroll above — the opposite of the desktop habit of a search
 *     field at the top with the keyboard covering its own results. Pass
 *     [field]; do not put a `TextField` in [content].
 *  2. **It registers with the back handler.** [ShellState.dismissTopSheet] can
 *     close the topmost sheet without knowing what it is (step 2 of the
 *     ordered handler), because every sheet is on the stack this scaffold
 *     keeps.
 *  3. **It opens at [OPEN_FRACTION] of the height and drags to full.** Pass
 *     [openFraction] for the rare sheet that is a form rather than a menu. The
 *     drag lives on the handle rather than on Material's own detents: its only
 *     intermediate anchor is exactly half the window, and the sheets this app
 *     has — a file tree with a filter, a deploy summary — want the two thirds
 *     the spec asks for.
 *
 * Dragging the handle below [DISMISS_FRACTION] dismisses, as does a tap on the
 * scrim, a back press (Material's sheet window takes it before the shell's
 * handler is asked — see ShellBackHandler.kt) and a downward fling on the body.
 *
 * THE HANDLE TRACKS 1:1 AND SETTLES. While the finger holds it the height is
 * the finger's; on release the sheet is thrown to the nearer of its two poses
 * — [OPEN_FRACTION] or the full window — carrying the finger's velocity
 * ([settlePose], [throwSpec]), and crossing the dismiss line ticks once per
 * crossing so the hand knows where the door is before the sheet goes. Every
 * dismissal, whichever door it came through, runs the sheet's exit first and
 * the caller's [onDismiss] after: callers still null their state and never
 * learn there was an animation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    state: ShellState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The sheet's own title, drawn under the handle. Null for a bare menu. */
    title: String? = null,
    /**
     * The bottom-pinned text field, if the sheet has one. It sits under
     * [content], above the IME, and it is the only thing in a sheet that may
     * hold the keyboard.
     */
    field: (@Composable () -> Unit)? = null,
    /** Actions pinned under the field — Commit, Deploy, "＋ New file". */
    actions: (@Composable () -> Unit)? = null,
    /**
     * How much of the window the sheet takes when it opens, as a fraction.
     *
     * [OPEN_FRACTION] is the house default and almost every sheet wants it.
     * The exception is a sheet that is not a menu over the screen but a *form*
     * standing in for it — the question sheet Spettro raises, where the agent
     * has stopped and the answer is the only thing on the phone worth doing.
     * Opening that at two thirds hides its own review page behind a drag the
     * user has no reason to guess at. The handle still resizes from wherever
     * this puts it, so this changes the opening pose and nothing else.
     */
    openFraction: Float = OPEN_FRACTION,
    /**
     * The sheet's ground.
     *
     * `surfaceContainer` is the default and is right for a sheet whose body is
     * a list or a form. Pass `background` when the body is CARDS — a card at
     * `surfaceContainer` on a `surfaceContainer` sheet is an outline with
     * nothing inside it.
     */
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(
        // Material's partial detent is half the window and is not
        // configurable; this scaffold owns the height instead (see [fraction]),
        // so the sheet itself is always "expanded" to whatever height we asked
        // for and the handle does the rest.
        skipPartiallyExpanded = true,
    )
    // The dismiss the back handler will call. `rememberUpdatedState` because
    // the handle is registered once and the caller's lambda is rebuilt on
    // every recomposition; without it a sheet dismissed by back would run the
    // lambda from the frame it opened on.
    val dismiss by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    // ONE WAY OUT. The scrim, back, the handle's drag and the shell's
    // dismissTopSheet all come through here, and the sheet leaves the way it
    // came — Material's own hide animation — before the caller is told. The
    // latch is what makes that safe: M3 calls `onDismissRequest` after its
    // own animation, a second call from the shell can land mid-hide, and the
    // caller's lambda nulls state that must be nulled exactly once. The
    // `runCatching` is for a hide cancelled by the sheet leaving composition
    // first, which is not a reason to skip telling the caller.
    var dismissed by remember { mutableStateOf(false) }
    fun hide() {
        if (dismissed) return
        dismissed = true
        scope.launch {
            runCatching { sheetState.hide() }
            dismiss()
        }
    }
    val handle = remember { SheetHandle { hide() } }
    DisposableEffect(handle) {
        state.sheetOpened(handle)
        onDispose { state.sheetClosed(handle) }
    }

    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    // Keyed on the requested pose so a caller that computes it (rather than
    // passing a constant) is not stuck with the first frame's value, and
    // clamped because a fraction at or below the dismiss threshold would open
    // a sheet that is already asking to be closed.
    // An Animatable, not a state: the drag `snapTo`s it and the release
    // `animateTo`s it, and there is one number either way.
    val fraction = remember(openFraction) {
        Animatable(openFraction.coerceIn(DISMISS_FRACTION, 1f))
    }
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()
    val reduceMotion = LocalReduceMotion.current
    val throwSpring = throwSpec()
    val haptics = LocalHapticFeedback.current
    // Below the dismiss line right now, for the crossing tick: flips on the
    // way down, re-arms on the way back up, so a finger hovering on the line
    // is told once per crossing and not once per frame.
    var crossed by remember { mutableStateOf(false) }
    // Where the finger last put the sheet, written synchronously: the
    // `snapTo` behind it is launched on the composition's dispatcher, which
    // may be a frame behind the gesture, and the release must not settle
    // from a frame-old height. NaN when no finger has it.
    val held = remember { floatArrayOf(Float.NaN) }

    ModalBottomSheet(
        onDismissRequest = ::hide,
        sheetState = sheetState,
        // A sheet whose body is a bare list takes `surfaceContainer`; the two
        // sheets whose bodies are CARDS (permission, question) pass
        // `background` instead, so their cards have something to read against
        // (docs/VISUAL.md, "Foundations" — spettro-android splits the same
        // way at ChatConfigSheet.kt:117 vs ChatComposer.kt:369).
        containerColor = containerColor,
        // 24dp, the sheet's own corner in the shape scale, and flat: depth in
        // this app is a fill step and a hairline, never a shadow or a tonal
        // overlay (`surfaceTint` is transparent, so the overlay would be a
        // no-op that still costs a draw).
        shape = RoundedCornerShape(topStart = MD.radiusXl, topEnd = MD.radiusXl),
        tonalElevation = 0.dp,
        // Drawn below, so the drag can size the sheet rather than move it.
        dragHandle = null,
        modifier = modifier,
    ) {
        // MEASURED ON DEVICE. This was `fillMaxHeight(fraction)`, which made
        // EVERY sheet exactly 65% of the window whatever was in it — so the
        // permission sheet, whose whole content is a title and one line, drew
        // ~470dp of empty grey between that line and its Allow/Reject pair at
        // the floor. 65% is a CAP, not a height: a sheet wraps its content and
        // stops growing there, which is what a Material bottom sheet does and
        // what makes a short one read as a dialog rather than a broken page.
        Column(modifier = Modifier.heightIn(max = windowHeight * fraction.value)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .touchTarget()
                    .semantics { contentDescription = "Drag to resize, drag down to close" }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            // Up is negative, and up grows the sheet. The
                            // sheet follows below the line too — the tick
                            // says where it is, and the release decides.
                            // Pixels over pixels: the delta arrives in px,
                            // and dividing it by the dp height moved the
                            // sheet three fingers per finger on a 480 dpi
                            // phone, which is not tracking.
                            val from = held[0].takeUnless { it.isNaN() } ?: fraction.value
                            val next = (from - delta / windowHeightPx).coerceIn(0f, 1f)
                            val below = next < DISMISS_FRACTION
                            if (below != crossed) {
                                crossed = below
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            held[0] = next
                            scope.launch { fraction.snapTo(next) }
                        },
                        onDragStopped = { velocityPx ->
                            // Fractions of the window per second, up positive.
                            val velocity = -velocityPx / windowHeightPx
                            val at = held[0].takeUnless { it.isNaN() } ?: fraction.value
                            held[0] = Float.NaN
                            // Continuous: the animatable takes over at the
                            // drag's last position, whether or not its own
                            // snap has landed yet.
                            fraction.snapTo(at)
                            if (at < DISMISS_FRACTION) {
                                hide()
                            } else {
                                val pose = settlePose(at, velocity)
                                if (reduceMotion) {
                                    fraction.snapTo(pose)
                                } else {
                                    fraction.animateTo(pose, throwSpring, initialVelocity = velocity)
                                }
                            }
                        },
                    )
                    .padding(vertical = HandlePadding),
            ) {
                Box(
                    modifier = Modifier
                        .size(width = HandleWidth, height = HandleHeight)
                        .clip(RoundedCornerShape(HandleHeight / 2))
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            if (title != null) {
                // A real title, not the 12sp muted line this used to draw. A
                // sheet's header is the one place on it that says what it is,
                // and at labelMedium in `text.muted` it read as a caption over
                // the content rather than as the content's name.
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = SheetPadding, vertical = TitleGap),
                )
            }
            // Weighted so a long body yields to the pinned row below rather
            // than pushing it off the bottom — but `fill = false`, so a SHORT
            // body takes only its own height and lets the column wrap. With
            // `fill = true` the body stretched to the cap and the pinned row
            // was pushed to the floor of a mostly empty sheet.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                content = content,
            )
            if (field != null || actions != null) {
                // [BottomActions] and not a bare Column: the pinned row is a
                // BAR, and a bar with no edge above it looks like it has eaten
                // the row it is standing on. A sheet's body is clipped exactly
                // where this begins — Projects lost half of its Settings row
                // that way — so the hairline, the insets and the 16 × 12
                // padding all come from the one component that four surfaces
                // share, rather than from four copies of this Column.
                BottomActions {
                    field?.invoke()
                    actions?.invoke()
                }
            } else {
                // Nothing pinned: the gesture inset still has to be cleared, or
                // the last row of a menu sits under the system's handle.
                Box(modifier = Modifier.fillMaxWidth().navigationBarsPadding().height(0.dp))
            }
        }
    }
}

/**
 * Where a sheet let go at [fraction] of the window, moving at [velocity]
 * windows per second (up positive), comes to rest: the nearer of its two
 * poses — [OPEN_FRACTION] or the full window — to where that speed would
 * have carried it.
 *
 * The projection is the nav pill's ([settleSlot], `ShellNavBar.kt`): the
 * same exponential decay, so a flick on a sheet handle and a flick on the
 * pill carry the same fifth of a second of the finger. A slow release below
 * the midpoint between the poses goes to 65%, above it to 100%; a real flick
 * clears the midpoint from either side. A sheet that opened at 1.0 is not
 * special-cased — a small drag on it projects nowhere near 0.65 and it
 * settles home. Never below [DISMISS_FRACTION]: below the line is a
 * dismissal, decided before this is asked.
 */
internal fun settlePose(fraction: Float, velocity: Float): Float {
    val thrown = velocity / 1000f * SETTLE_DECELERATION / (1f - SETTLE_DECELERATION)
    val projected = fraction + thrown
    return if (abs(projected - OPEN_FRACTION) <= abs(projected - 1f)) OPEN_FRACTION else 1f
}

/** "Sheets open at ~65% height" — docs/UI.md, "Navigation". */
internal const val OPEN_FRACTION = 0.65f

/** Dragged below this, the gesture was a dismissal rather than a resize. */
internal const val DISMISS_FRACTION = 0.45f

/** `ShellNavBar`'s `DECELERATION`, in the same units of "how much of a flick carries". */
private const val SETTLE_DECELERATION = 0.995f

private val HandleWidth = 32.dp
private val HandleHeight = 4.dp
private val HandlePadding = 8.dp
private val SheetPadding = MD.space4
private val TitleGap = MD.space2
