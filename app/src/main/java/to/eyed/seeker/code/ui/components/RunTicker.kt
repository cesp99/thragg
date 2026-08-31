package to.eyed.seeker.code.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.delay
import to.eyed.seeker.code.ui.theme.Durations
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.TabularNums

/**
 * `1m04s · 12.3k` beside a spinner: what a turn in flight looks like on one
 * 36dp line.
 *
 * ONE HERTZ, on purpose. The spinner carries the motion at 20fps and this
 * carries the number, and re-laying out a text run at 60fps to advance a
 * seconds counter is work with no viewer — the digit changes once a second
 * whatever the frame rate. The `delay` is anchored to the wall clock rather
 * than a frame callback for the same reason ([Durations.TICKER]).
 *
 * Both figures take [TabularNums]. Without it the elapsed label re-measures
 * every time a `1` becomes a `2` and the token count beside it steps left and
 * right once a second, which the eye reads as a rendering fault before it
 * reads the number.
 *
 * The whole thing is one semantics node saying the sentence a screen reader
 * should say — "running for 1m 04s, 12.3k tokens" — rather than three nodes
 * reciting a spinner, a separator and two numbers. It is also the reason the
 * separator may stay a `·`: it is typography, and nothing announces it.
 *
 * [tokens] null is a run the host has not costed yet, which is most of the
 * first second of every turn; the clock draws alone rather than showing a
 * zero that would tick to a real number and look like a correction.
 */
@Composable
fun RunTicker(
    startedAt: Long,
    tokens: Long?,
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(Durations.TICKER)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = elapsedLabel(now - startedAt)
    val label = if (tokens == null) elapsed else "$elapsed · ${formatTokens(tokens)}"
    val spoken = if (tokens == null) {
        "running for $elapsed"
    } else {
        "running for $elapsed, ${formatTokens(tokens)} tokens"
    }
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
    ) {
        SeekerSpinner(size = 12.dp, color = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularNums),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * `12s`, `1m 07s`, `1h 04m` — elapsed, never a clock time.
 *
 * Two units at most: the third is noise at the width this is drawn, and a run
 * measured in hours is not one anybody is watching the seconds of. The same
 * function as `OrchBits.kt`'s, moved here with the component that is now every
 * caller of it (the agent strip, the build strip, the live-run strip and the
 * workflow card all printed their own version of this).
 */
internal fun elapsedLabel(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m ${rest.toString().padStart(2, '0')}s"
        else -> "${rest}s"
    }
}

/**
 * `1.2M`, `3.4k`, `412`.
 *
 * One decimal on both suffixes because these are printed in pairs
 * ("168.2k of 200.0k") and a bare `168k of 200k` cannot show a gauge moving —
 * occupancy climbs by a few hundred tokens per request, and the whole point of
 * a usage update arriving mid-turn is that the number moves while you watch.
 * 999,950 would format as "1000.0k", which is wider than the label it
 * replaced, so it promotes to "1.0M" instead.
 */
internal fun formatTokens(n: Long): String {
    val count = n.coerceAtLeast(0L)
    return when {
        count >= 1_000_000L -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000L -> {
            val text = String.format(Locale.US, "%.1fk", count / 1_000.0)
            if (text == "1000.0k") "1.0M" else text
        }
        else -> count.toString()
    }
}
