package to.eyed.seeker.code.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import to.eyed.seeker.code.ui.theme.Durations
import to.eyed.seeker.code.ui.theme.LocalReduceMotion

/**
 * "Spettro is working", drawn the way Spettro draws it everywhere else.
 *
 * This is the CLI's braille spinner as a ring of dots: eight discrete 50ms
 * steps, one revolution per 400ms, head at full opacity with the tail falling
 * away behind it. It is deliberately not a `CircularProgressIndicator` and
 * deliberately not material3 1.4.0's absent `LoadingIndicator` — this glyph is
 * what the user has already seen in the TUI and the desktop client, and a
 * Material sweep here would be a different product's spinner in the same
 * session.
 *
 * MOVED, not rewritten, out of `OrchBits.kt` where it was `SpettroSpinner`:
 * the cadence and the reduce-motion branch were already right, and the only
 * thing wrong with it was that it lived in the agent package where the build
 * strip and the setup steps could not reach it.
 *
 * Discrete steps are also the cheap answer to a fan-out. Twenty of these on
 * screen — a swarm with twenty members is not hypothetical — are twenty
 * invalidations every 50ms rather than twenty per frame.
 *
 * REDUCE MOTION STOPS THE ROTATION AND KEEPS THE RAMP. The spinner still
 * marks "running", because that is information; the turning is the
 * decoration, and decoration is what the setting removes. Zed's own status-bar
 * spinner does exactly this (docs/VISUAL.md, "Foundations", MOTION).
 */
@Composable
fun SeekerSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val reduce = LocalReduceMotion.current
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(reduce) {
        if (reduce) return@LaunchedEffect
        while (true) {
            delay(Durations.SPINNER_FRAME)
            step = (step + 1) % Durations.SPINNER_FRAMES
        }
    }
    Canvas(modifier.size(size)) {
        val dot = this.size.minDimension * 0.11f
        val ring = this.size.minDimension / 2f - dot
        for (index in 0 until Durations.SPINNER_FRAMES) {
            val behind = (step - index + Durations.SPINNER_FRAMES) % Durations.SPINNER_FRAMES
            val alpha = (1f - behind * 0.16f).coerceAtLeast(0.12f)
            val angle = (-90f + index * 45f) * (PI.toFloat() / 180f)
            drawCircle(
                color = color.copy(alpha = color.alpha * alpha),
                radius = dot,
                center = center + Offset(ring * cos(angle), ring * sin(angle)),
            )
        }
    }
}
