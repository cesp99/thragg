package to.eyed.seeker.code.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.theme.LocalReduceMotion

/**
 * A state, said in eight dp.
 *
 * Seeker's deliberate answer to spettro-android's green check on every
 * completed tool call, and the argument is worth keeping written down: a
 * transcript of green ticks reads as a list of achievements, so a finished
 * call gets a muted dot and nothing more. Colour is spent where it carries
 * information — a diff stat, a failure, a run that is waiting on the user —
 * and a dot is what "this happened" looks like when it did not need saying.
 *
 * [pulsing] is for the one case where the dot is the *only* thing reporting a
 * live state and there is no room for a spinner beside it: the nav bar's
 * attention badge. It breathes on the alpha rather than on the radius, because
 * a mark that changes size makes the row around it look like it is reflowing.
 * Reduce-motion stops the breath and leaves the dot at full strength — still
 * legible as "attention", not moving.
 *
 * [contentDescription] is null by default because the usual dot sits beside a
 * label that already says what it means, and a screen reader announcing "dot"
 * after "Read lib.rs" is noise. Pass one when the dot is alone — the badge,
 * the dirty marker on a project row — and it becomes the only thing the node
 * says.
 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    pulsing: Boolean = false,
    contentDescription: String? = null,
) {
    val reduce = LocalReduceMotion.current
    val alpha = if (pulsing && !reduce) {
        val transition = rememberInfiniteTransition(label = "status-dot")
        val value by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                // 900ms each way: slow enough to read as breathing rather than
                // as blinking, which is what a shorter cycle looks like at
                // this size.
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-dot-alpha",
        )
        value
    } else {
        1f
    }
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
    }
    Box(
        modifier = modifier
            .then(semantics)
            .size(size)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}
