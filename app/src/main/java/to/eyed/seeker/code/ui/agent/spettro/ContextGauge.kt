package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.AgentTurnUsage
import to.eyed.seeker.code.core.AgentUsage
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// The numbers, as pure functions
// ---------------------------------------------------------------------------
//
// Everything below the composables is here so the rules that decide what the
// gauge *says* can be tested without a device. They are the rules that are
// easy to get subtly wrong and impossible to notice: a window of 0 dividing
// into a NaN, a 0.4 % context reading "0%" and looking empty, a 99.6 %
// context reading "100%" and looking hopeless.

/** How alarming the current occupancy is. The three colours the ring takes. */
internal enum class ContextSeverity { CALM, WARM, FULL }

/**
 * The thresholds from docs/SPETTRO.md, "Context gauge": amber at 75 %, red at
 * 90 %. They match [AgentUsage.isWarm] and [AgentUsage.isNearlyFull]; this
 * enum exists because a colour is a third state and a pair of booleans is two.
 */
internal fun contextSeverity(fraction: Float): ContextSeverity = when {
    fraction >= 0.90f -> ContextSeverity.FULL
    fraction >= 0.75f -> ContextSeverity.WARM
    else -> ContextSeverity.CALM
}

/**
 * The percent, as text.
 *
 * Two rules, both of them about not lying at the ends of the range. A
 * non-zero fraction under one percent prints `<1%` rather than `0%`, because
 * "0%" reads as *nothing has happened yet* and the gauge would then be
 * indistinguishable from a fresh session while a turn is running. And a
 * fraction that has not actually reached the window never prints `100%`, for
 * the same reason in the other direction: 99.6 % is a session you can keep
 * using, and 100 % is one that is about to lose a reply.
 */
internal fun contextPercentLabel(fraction: Float): String {
    val clamped = fraction.coerceIn(0f, 1f)
    if (clamped <= 0f) return "0%"
    val percent = clamped * 100f
    if (percent < 1f) return "<1%"
    val rounded = percent.roundToInt().coerceIn(1, 100)
    return if (rounded == 100 && clamped < 1f) "99%" else "$rounded%"
}

/**
 * `1.2M`, `3.4k`, `412`.
 *
 * One decimal on both suffixes because the sheet prints two of these side by
 * side ("168.2k of 200.0k") and a bare `168k of 200k` cannot show the gauge
 * moving — occupancy climbs by a few hundred tokens per request, and the
 * whole point of `usage_update` arriving mid-turn is that the number moves
 * while you watch it.
 */
internal fun formatTokens(count: Long): String {
    val n = count.coerceAtLeast(0L)
    return when {
        n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000L -> {
            // 999_950 formats as "1000.0k". Four digits before the suffix is
            // wider than the label it replaced, so promote instead.
            val text = String.format(Locale.US, "%.1fk", n / 1_000.0)
            if (text == "1000.0k") "1.0M" else text
        }
        else -> n.toString()
    }
}

/**
 * `168.2k of 200.0k tokens`, or null when there is no honest denominator.
 *
 * A `size` of 0 is not a full window and not an empty one — it is an agent
 * that has not said. docs/SPETTRO.md is explicit that such an update is
 * ignored rather than divided by, so the whole line goes rather than printing
 * a percentage of nothing.
 */
internal fun contextTokensLine(usage: AgentUsage?): String? {
    if (usage == null || usage.size <= 0L) return null
    return "${formatTokens(usage.used)} of ${formatTokens(usage.size)} tokens"
}

/**
 * `Total processed: 412.8k tokens`, and only when it says something new.
 *
 * `used` is occupancy — the largest single request so far — and `tokensUsed`
 * is the monotonic spend. Before the first compaction they track each other
 * closely, and printing both would suggest two independent facts where there
 * is one. It earns its line when it exceeds occupancy, which is exactly when
 * the conversation has cost more than it currently holds.
 */
internal fun totalProcessedLine(usage: AgentUsage?): String? {
    val spend = usage?.tokensUsed ?: return null
    if (spend <= usage.used) return null
    return "Total processed: ${formatTokens(spend)} tokens"
}

/** `Cache hits: 63%`, from the turn that just ended, or null if it had none. */
internal fun cacheHitsLine(turnUsage: AgentTurnUsage?): String? {
    val rate = turnUsage?.cacheHitRate ?: return null
    return "Cache hits: ${contextPercentLabel(rate)}"
}

/**
 * The sentence over the action row, or null while there is nothing to do.
 *
 * Advice rather than alarm: compaction is two-stage and often free — spooling
 * large tool outputs to disk may release enough on its own, with no
 * summariser call — so the tone at 75 % is "soon", not "now".
 */
internal fun contextAdviceLine(fraction: Float): String? =
    when (contextSeverity(fraction)) {
        ContextSeverity.FULL -> "Full — compact now, or the next reply may be lost"
        ContextSeverity.WARM -> "Nearly full — compact soon"
        ContextSeverity.CALM -> null
    }

/** Whether the sheet shows `/compact` and the auto-compact toggle at all. */
internal fun showsCompactActions(fraction: Float): Boolean = fraction >= 0.75f

/**
 * Whether the warning belongs above the composer too, where it is seen
 * without opening anything.
 *
 * 0.85 rather than [AgentUsage.isNearlyFull]'s 0.90 on purpose: the sheet's
 * red is a state, the strip is an interruption, and the strip has to arrive
 * with enough room left to act on it. docs/SPETTRO.md asks for it at 85 %.
 */
internal fun showsComposerWarning(fraction: Float): Boolean = fraction >= 0.85f

// ---------------------------------------------------------------------------
// The ring
// ---------------------------------------------------------------------------

/**
 * The context ring — the one number in the app bar.
 *
 * It is in the app bar, and the mode chip and the plan are not, because it is
 * the only reading that changes *what you should do next*: at 90 % the right
 * move is to compact or start a new conversation, and no other indicator on
 * this screen can tell you that.
 *
 * It is a **gauge, never a counter**. `used` is context occupancy — the
 * largest single request so far — and it falls after a compaction. An
 * animation that only ever grew, or a label that accumulated, would both be
 * lying about the same number.
 *
 * `usage_update` arrives after every LLM request *inside* a turn rather than
 * once at the end (docs/SPETTRO.md W-08), so this moves while the agent works.
 * That is the reason for the sweep animation: without it the ring would jump
 * several times a turn and read as a glitch rather than as progress.
 *
 * Draws nothing at all when the agent has not reported a usable window. A
 * `size` of 0 divides into nonsense, and an empty ring in the app bar is a
 * claim that the context is empty.
 */
@Composable
fun ContextRing(
    usage: AgentUsage?,
    modifier: Modifier = Modifier,
    /** The tabular percent beside the ring. Off in the app bar, on in sheets. */
    label: Boolean = false,
    size: Dp = RingSize,
    onClick: (() -> Unit)? = null,
) {
    if (usage == null || usage.size <= 0L) return
    val theme = LocalZedTheme.current
    val fraction = usage.fraction
    val severity = contextSeverity(fraction)
    val accent = when (severity) {
        ContextSeverity.FULL -> theme.color("error", MaterialTheme.colorScheme.error)
        ContextSeverity.WARM -> theme.color("warning", MaterialTheme.colorScheme.tertiary)
        ContextSeverity.CALM -> theme.color("text.accent", MaterialTheme.colorScheme.primary)
    }
    val track = theme.color("border.variant", Color.Transparent)
    val percent = contextPercentLabel(fraction)

    val reduceMotion = LocalReduceMotion.current
    val swept by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 420),
        label = "context",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .then(if (onClick != null) Modifier.touchTarget() else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClickLabel = "Context window", onClick = onClick)
                } else {
                    Modifier
                },
            )
            // One description for the pair. Read as two nodes it announces the
            // percent twice, once as an unlabelled graphic.
            .clearAndSetSemantics {
                contentDescription = "Context window $percent full"
            },
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = RingStroke.toPx()
            val inset = stroke / 2f
            val box = Size(this.size.width - stroke, this.size.height - stroke)
            val corner = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = corner,
                size = box,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
            if (swept > 0f) {
                // A floor on the sweep: at 12 dp a half-percent arc is under
                // one pixel, so a session that has genuinely started would
                // draw as an untouched ring.
                val sweep = (360f * swept.coerceIn(0f, 1f)).coerceAtLeast(4f)
                drawArc(
                    // −90° puts zero at the top. Compose measures from three
                    // o'clock, and a gauge that filled from the right reads as
                    // a different quantity at a glance.
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = corner,
                    size = box,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
        }
        if (label) {
            Text(
                text = percent,
                style = MaterialTheme.typography.labelSmall.copy(
                    // Tabular figures: this number changes several times a
                    // turn and a proportional `1` makes the whole app bar
                    // shift left and right as it does.
                    fontFeatureSettings = "tnum",
                ),
                color = if (severity == ContextSeverity.CALM) {
                    theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    accent
                },
                maxLines = 1,
            )
        }
    }
}

/** docs/SPETTRO.md's 12 dp ring at stroke 2.5 — the app-bar size. */
private val RingSize = 12.dp
private val RingStroke = 2.5.dp

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

/**
 * The ring's sheet: the two numbers, the cache-hit rate, and — only when it
 * would help — the two things you can do about it.
 *
 * The action row is gated at 75 % rather than always shown because `/compact`
 * on a quarter-full context is a slow no-op that costs a summariser call, and
 * an always-present button invites exactly that.
 *
 * [autoCompact] is nullable and stays that way. `/compact auto on|off` is a
 * slash command with no readback — Spettro publishes no config option for it
 * — so before the user has touched it this sheet genuinely does not know the
 * setting, and a switch drawn in a made-up position is worse than one drawn
 * as unknown.
 */
@Composable
fun ContextSheet(
    state: ShellState,
    usage: AgentUsage?,
    turnUsage: AgentTurnUsage?,
    onCompact: () -> Unit,
    onToggleAutoCompact: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    autoCompact: Boolean? = null,
) {
    val theme = LocalZedTheme.current
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val fraction = usage?.takeIf { it.size > 0L }?.fraction ?: 0f

    // Optimistic, and only until the sheet closes: the command is fire and
    // forget, so this remembers what was asked for rather than claiming to
    // know what the agent did with it.
    var asked by remember(autoCompact) { mutableStateOf(autoCompact) }

    SheetScaffold(state = state, onDismiss = onDismiss, title = "Context window") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            if (usage == null || usage.size <= 0L) {
                Text(
                    text = "Spettro has not reported a context window yet. The gauge " +
                        "appears with the first reply.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Spacer(Modifier.height(16.dp))
                return@SheetScaffold
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContextRing(usage = usage, size = 44.dp)
                Text(
                    text = contextPercentLabel(fraction),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFeatureSettings = "tnum",
                    ),
                    color = text,
                )
            }

            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                contextTokensLine(usage)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = text,
                    )
                }
                totalProcessedLine(usage)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = muted,
                    )
                }
                cacheHitsLine(turnUsage)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        color = muted,
                    )
                }
            }

            // The one sentence that explains why the number can go *down*,
            // which otherwise reads as a bug the first time it happens.
            Spacer(Modifier.height(12.dp))
            Text(
                text = "This is how much of the window the conversation currently " +
                    "occupies. It falls when Spettro compacts.",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.fillMaxWidth(),
            )

            if (showsCompactActions(fraction)) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = theme.color("border.variant", Color.Transparent))
                Spacer(Modifier.height(12.dp))
                contextAdviceLine(fraction)?.let { advice ->
                    Text(
                        text = "⚠ $advice",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (contextSeverity(fraction)) {
                            ContextSeverity.FULL ->
                                theme.color("error", MaterialTheme.colorScheme.error)
                            else ->
                                theme.color("warning", MaterialTheme.colorScheme.tertiary)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GaugeButton(
                        label = "/compact",
                        primary = true,
                        onClick = {
                            onCompact()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    GaugeButton(
                        label = "auto-compact:  ${autoCompactWord(asked)}",
                        primary = false,
                        onClick = {
                            // Unknown flips to on: the only reason to reach
                            // for this control while the window is filling is
                            // to stop being asked again.
                            val next = asked != true
                            asked = next
                            onToggleAutoCompact(next)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Compacting often costs nothing: Spettro first moves large " +
                        "tool output to disk, and only summarises if that was not enough.",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** `on` / `off` / the honest third answer. */
private fun autoCompactWord(value: Boolean?): String = when (value) {
    true -> "on"
    false -> "off"
    null -> "?"
}

/**
 * The warning above the composer, from 85 %.
 *
 * Deliberately a row and not a dialog: the answer to a filling context is
 * usually "carry on and compact at the next natural break", and a modal would
 * make that decision for the user at the worst moment.
 */
@Composable
fun ContextWarningRow(
    usage: AgentUsage?,
    onOpenGauge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (usage == null || usage.size <= 0L) return
    val fraction = usage.fraction
    if (!showsComposerWarning(fraction)) return
    val theme = LocalZedTheme.current
    val severity = contextSeverity(fraction)
    val accent = if (severity == ContextSeverity.FULL) {
        theme.color("error", MaterialTheme.colorScheme.error)
    } else {
        theme.color("warning", MaterialTheme.colorScheme.tertiary)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Context window", onClick = onOpenGauge)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        ContextRing(usage = usage)
        Text(
            text = contextAdviceLine(fraction) ?: "Context nearly full",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = contextPercentLabel(fraction),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = accent,
            maxLines = 1,
        )
    }
}

/** The two buttons under the gauge. Local, because they are 40 dp and paired. */
@Composable
private fun GaugeButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val accent = theme.color("text.accent", MaterialTheme.colorScheme.primary)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) accent else Color.Transparent)
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = theme.color("border", Color.Transparent),
                        shape = RoundedCornerShape(8.dp),
                    )
                },
            )
            .clickable(onClickLabel = label, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = if (primary) {
                theme.color("editor.background", MaterialTheme.colorScheme.onPrimary)
            } else {
                theme.color("text", MaterialTheme.colorScheme.onSurface)
            },
            maxLines = 1,
        )
    }
}
