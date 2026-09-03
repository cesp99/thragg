package to.eyed.seeker.code.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Press feedback for a control that is a *shape*: it gives under the thumb.
 *
 * The ripple says "this heard you" with a wash; this says it with mass. A
 * card, a chip or a filled circle that shrinks to [PRESS_SCALE] the instant it
 * is pressed and springs back on release reads as a physical thing being
 * pushed, which is the difference between an interface that answers the
 * finger and one that answers the tap event a few frames later. The two are
 * complementary, not competing: the ripple is the state layer Material draws
 * on every pressable, and the scale is the extra half-inch of craft that
 * makes the raised ones feel raised.
 *
 * THE SIGNAL IS THE INTERACTION SOURCE, not a pointer listener of this
 * modifier's own. That is what makes it scroll-safe: `clickable` inside a
 * scrolling container delays its press interaction until the gesture has
 * proven not to be a scroll, so a list of cards does not twitch every time
 * the list is flicked. A raw `awaitFirstDown` here would fire on every scroll
 * start. Callers hand the same [MutableInteractionSource][androidx.compose.foundation.interaction.MutableInteractionSource]
 * to their `clickable`/`Surface`/`Button`, and this reads it.
 *
 * WHERE IT GOES, and where it does not. On the things that are drawn as
 * objects — [SeekerCard][to.eyed.seeker.code.ui.components.SeekerCard] with an
 * `onClick`, a [SeekerChip][to.eyed.seeker.code.ui.components.SeekerChip], the
 * composer's circles, the Build run control, a filled button. Not on rows in
 * a list and not on the navigation bar: a row is a region of a surface and a
 * region does not shrink, and the bar is touched a hundred times a day, which
 * is the frequency at which every animation becomes a delay.
 *
 * Critically damped and stiff, on purpose: a press must land on the frame it
 * happens and a release must settle without a wobble, because a wobble on a
 * button is a button that looks unsure. [PRESS_STIFFNESS] settles in roughly
 * 100ms, which is the band Material and iOS both use for a press.
 *
 * Reduce-motion snaps between the two sizes rather than removing the change:
 * the pressed size is a *state* the finger is holding, and a state that
 * cannot be seen is a control that cannot be trusted.
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    scale: Float = PRESS_SCALE,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val factor by animateFloatAsState(
        targetValue = if (pressed) scale else 1f,
        animationSpec = if (LocalReduceMotion.current) {
            snap()
        } else {
            spring(dampingRatio = 1f, stiffness = PRESS_STIFFNESS)
        },
        label = "press-scale",
    )
    // Read inside the layer block so a press invalidates the draw and nothing
    // above it: the control's size in layout never changes, only what is
    // rasterised, which is the whole reason this is a transform and not a
    // padding.
    return this.graphicsLayer {
        scaleX = factor
        scaleY = factor
    }
}

/**
 * 97%. Visible on a 40dp circle and on a 340dp card alike, and invisible as
 * a *size* on either: the eye reads it as the thing giving way, not as the
 * thing getting smaller. Below 0.95 a card starts to look like it is being
 * pulled into the screen.
 */
const val PRESS_SCALE = 0.97f

/**
 * Stiff enough to settle in about 100ms at critical damping. The same
 * stiffness [effectSpec] uses for a colour, and for the same reason: a press
 * has no momentum to carry and any overshoot reads as a flicker.
 */
private const val PRESS_STIFFNESS = 1600f
