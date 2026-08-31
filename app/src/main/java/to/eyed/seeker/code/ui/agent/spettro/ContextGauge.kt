package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentTurnUsage
import to.eyed.seeker.code.core.AgentUsage
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.NoticeCard
import to.eyed.seeker.code.ui.components.Severity
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.TabularNums
import to.eyed.seeker.code.ui.theme.effectSpec
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
//
// THE ARITHMETIC IS UNCHANGED BY THE REDESIGN. What moved is the drawing: the
// percentage left the 22 dp app-bar ring — the smallest, least-read pixel on
// the screen, carrying the one value that decides whether the next message
// works — and became a tabular figure on the 36 dp status strip, costing no
// extra height at all. See docs/VISUAL.md, "Agent — the context gauge".

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
 * `8.6k left` — the same fact from the other end, and the more actionable one.
 *
 * "191.4k of 200.0k" needs a subtraction before it means anything; "8.6k left"
 * is the number that decides whether the next message fits. Both are printed,
 * the headroom quieter, because the first is the reading and the second is
 * the consequence.
 *
 * Clamped at zero: an agent that reports occupancy above its own window (it
 * happens, on the request that overflowed) must not print negative headroom.
 */
internal fun contextLeftLine(usage: AgentUsage?): String? {
    if (usage == null || usage.size <= 0L) return null
    return "${formatTokens((usage.size - usage.used).coerceAtLeast(0L))} left"
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
 * `Prompt 148.0k · Output 43.0k · Cache 12.0k` — where the last turn's tokens
 * went, or null before a turn has ended.
 *
 * The TURN's figures and not the window's, and the distinction is the whole
 * reason it is a separate line: occupancy answers "how much room is left",
 * this answers "what did that answer cost", and a cache-miss regression shows
 * up here first while the gauge above it looks unchanged. Zero parts are
 * dropped rather than printed as `Cache 0` — an agent that reports no cache
 * has not reported a cache of nothing.
 */
internal fun turnBreakdownLine(turnUsage: AgentTurnUsage?): String? {
    val turn = turnUsage ?: return null
    val cached = turn.cachedReadTokens + turn.cachedWriteTokens
    val parts = buildList {
        if (turn.inputTokens > 0) add("Prompt ${formatTokens(turn.inputTokens)}")
        if (turn.outputTokens > 0) add("Output ${formatTokens(turn.outputTokens)}")
        if (cached > 0) add("Cache ${formatTokens(cached)}")
    }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
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
 * Whether the notice should take the composer's place rather than sit above
 * it.
 *
 * TWO CONDITIONS, AND BOTH ARE NECESSARY. A full window alone is not enough:
 * a 96 %-full session usually still accepts a short prompt, and removing the
 * composer from under a user who could have typed is the worst possible way
 * to be wrong. But once the host has actually *refused* a turn, the composer
 * is a control that does nothing — and leaving it there means the user types
 * a paragraph before finding out. Taking it away at exactly that point is
 * severity expressed by placement rather than by a dialog, which is the right
 * vocabulary for a tool (docs/VISUAL.md, "Agent — the context gauge").
 *
 * [refused] is the turn's own stop reason, not a guess from the numbers.
 */
internal fun contextBlocksComposer(fraction: Float, refused: Boolean): Boolean =
    refused && contextSeverity(fraction) == ContextSeverity.FULL

/** Whether the gauge is loud enough to earn a card of its own. */
internal fun showsContextNotice(fraction: Float): Boolean =
    contextSeverity(fraction) == ContextSeverity.FULL

// ---------------------------------------------------------------------------
// In the status strip
// ---------------------------------------------------------------------------

/**
 * `191.4k tok  96%` — the context, on the 36 dp status strip.
 *
 * THIS REPLACES THE APP-BAR RING as the at-rest readout, and the inversion is
 * the argument. The ring was 12 dp of arc in the busiest row on the screen,
 * carrying the one value that decides whether the next message works; a
 * reader had to know it was a gauge, know which way it filled, and then still
 * open something to get a number. Here it is a plain tabular figure on the
 * line it belongs with, at zero extra height, and as it climbs it changes
 * colour **in place** — calm `onSurfaceVariant`, warm `warnInk` from 75 %,
 * full `removedInk` from 90 %. Severity by ink, not by a band that appears.
 *
 * Tapping opens the sheet, where the ring still lives at a size worth
 * drawing.
 *
 * Draws nothing at all when the agent has not reported a usable window: a
 * `size` of 0 divides into nonsense, and a "0%" on the strip is a claim that
 * the context is empty.
 *
 * `TabularNums` throughout: this figure changes several times a turn, and a
 * proportional `1` makes the whole strip shift left and right as it does.
 */
@Composable
fun UsageReadout(
    usage: AgentUsage?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (usage == null || usage.size <= 0L) return
    val colors = LocalSeekerColors.current
    val fraction = usage.fraction
    val percent = contextPercentLabel(fraction)
    val ink = when (contextSeverity(fraction)) {
        ContextSeverity.FULL -> colors.removedInk
        ContextSeverity.WARM -> colors.warnInk
        ContextSeverity.CALM -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
        modifier = modifier
            .clip(RoundedCornerShape(MD.radiusXs))
            .clickable(onClickLabel = "Context window", onClick = onClick)
            .padding(horizontal = MD.space1, vertical = MD.space05)
            // One node for the pair. Read as two it announces a token count
            // and then a bare percentage of nothing named. The action is
            // re-declared inside the clear: clearing removes the click label
            // too, and a readout that opens a sheet has to say so.
            .clearAndSetSemantics {
                contentDescription =
                    "Context window $percent full, ${formatTokens(usage.used)} tokens"
                onClick(label = "Context window") {
                    onClick()
                    true
                }
            },
    ) {
        Text(
            text = "${formatTokens(usage.used)} tok",
            style = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = TabularNums,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = percent,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = TabularNums,
            ),
            color = ink,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------
// The ring
// ---------------------------------------------------------------------------

/**
 * The context ring, now drawn at a size worth drawing: the sheet's headline.
 *
 * It is a **gauge, never a counter**. `used` is context occupancy — the
 * largest single request so far — and it falls after a compaction. An
 * animation that only ever grew, or a label that accumulated, would both be
 * lying about the same number.
 *
 * `usage_update` arrives after every LLM request *inside* a turn rather than
 * once at the end (docs/SPETTRO.md W-08), so this moves while the agent works.
 * That is the reason for the sweep animation, and why it is [effectSpec] — a
 * colour-and-alpha-class tween that reduce-motion switches off — rather than
 * a hand-written `tween`.
 *
 * The percent sits INSIDE the ring rather than beside it. At 96 dp the middle
 * is empty and the figure is what the ring is about, so putting it anywhere
 * else makes the reader's eye do a hop for no reason.
 *
 * Draws nothing at all when the agent has not reported a usable window.
 */
@Composable
fun ContextRing(
    usage: AgentUsage?,
    modifier: Modifier = Modifier,
    /** The tabular percent, centred in the ring. */
    label: Boolean = true,
    size: Dp = RingSize,
) {
    if (usage == null || usage.size <= 0L) return
    val colors = LocalSeekerColors.current
    val scheme = MaterialTheme.colorScheme
    val fraction = usage.fraction
    val severity = contextSeverity(fraction)
    val accent = when (severity) {
        ContextSeverity.FULL -> colors.removedMark
        ContextSeverity.WARM -> colors.warnMark
        ContextSeverity.CALM -> scheme.primary
    }
    val track = scheme.outlineVariant
    val percent = contextPercentLabel(fraction)

    val swept by animateFloatAsState(
        targetValue = fraction,
        animationSpec = effectSpec(),
        label = "context",
    )

    Box(
        contentAlignment = Alignment.Center,
        // One description for the pair. Read as two nodes it announces the
        // percent twice, once as an unlabelled graphic.
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Context $percent full"
        },
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = (size / RingStrokeRatio).toPx()
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
                // A floor on the sweep: a half-percent arc is under one pixel,
                // so a session that has genuinely started would draw as an
                // untouched ring.
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
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = if (severity == ContextSeverity.CALM) scheme.onSurface else accent,
                maxLines = 1,
            )
        }
    }
}

/** The sheet's ring. docs/VISUAL.md's wireframe draws it at 96 dp. */
private val RingSize = 96.dp

/** Stroke is a twelfth of the diameter, so the ring reads the same at any size. */
private const val RingStrokeRatio = 12f

// ---------------------------------------------------------------------------
// The third tier: the notice
// ---------------------------------------------------------------------------

/**
 * The context notice — tier three, and the only tier that takes space.
 *
 * IT REPLACES `ContextWarningRow`, which was a permanently-pinned band from
 * 85 % onwards. Two things were wrong with that. It spent height on a state
 * the user could do nothing useful about yet, on the screen with the least
 * height to spare; and when the state finally *did* become actionable it said
 * the same thing in the same voice, so there was no signal left to spend.
 *
 * This appears only at 90 % — the point at which the agent may actually
 * refuse the next message — and it carries both ways out rather than a link
 * to a sheet that carries them. When the host has already refused a turn, the
 * caller draws it **in the composer's place** ([contextBlocksComposer]):
 * there is nothing useful to type, so the thing to do is the only thing on
 * screen.
 *
 * Not a dialog and not a toast. A dialog would make the decision for the user
 * at the worst moment, and a toast is gone in four seconds
 * (docs/VISUAL.md, "What we deliberately do not copy").
 */
@Composable
fun ContextNotice(
    usage: AgentUsage?,
    onCompact: () -> Unit,
    onNewThread: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (usage == null || usage.size <= 0L) return
    val fraction = usage.fraction
    if (!showsContextNotice(fraction)) return
    NoticeCard(
        severity = Severity.Error,
        title = "Nearly full",
        body = "New messages may be refused. Compact the thread to summarise it and " +
            "free space, or start a new one.",
        modifier = modifier,
        actions = {
            Button(
                onClick = onCompact,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(text = "Compact thread", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onNewThread,
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text(text = "New thread", style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

/**
 * The ring's sheet: the two numbers, what the last turn spent, and — only
 * when it would help — the two things you can do about it.
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
    /**
     * The connected agent's own name. A window that has not been reported yet
     * is a sentence about whoever is on the other end — `usage` rides
     * `session/update`, and a generic agent sends it too.
     */
    agentName: String,
    onCompact: () -> Unit,
    onToggleAutoCompact: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    autoCompact: Boolean? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fraction = usage?.takeIf { it.size > 0L }?.fraction ?: 0f

    // Optimistic, and only until the sheet closes: the command is fire and
    // forget, so this remembers what was asked for rather than claiming to
    // know what the agent did with it.
    var asked by remember(autoCompact) { mutableStateOf(autoCompact) }

    SheetScaffold(state = state, onDismiss = onDismiss, title = "Context") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MD.space4),
        ) {
            if (usage == null || usage.size <= 0L) {
                Text(
                    text = stringResource(R.string.agent_context_unreported, agentName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MD.space4),
                )
                return@SheetScaffold
            }

            Spacer(Modifier.height(MD.space2))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space4),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ContextRing(usage = usage)
                Column(verticalArrangement = Arrangement.spacedBy(MD.space05)) {
                    contextTokensLine(usage)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFeatureSettings = TabularNums,
                            ),
                            color = scheme.onSurface,
                        )
                    }
                    contextLeftLine(usage)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFeatureSettings = TabularNums,
                            ),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(MD.space4))
            Column(verticalArrangement = Arrangement.spacedBy(MD.space1)) {
                turnBreakdownLine(turnUsage)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = TabularNums,
                        ),
                        color = scheme.onSurfaceVariant,
                    )
                }
                totalProcessedLine(usage)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = TabularNums,
                        ),
                        color = scheme.onSurfaceVariant,
                    )
                }
                cacheHitsLine(turnUsage)?.let { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFeatureSettings = TabularNums,
                        ),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            // The one sentence that explains why the number can go *down*,
            // which otherwise reads as a bug the first time it happens.
            Spacer(Modifier.height(MD.space3))
            Text(
                text = "This is how much of the window the conversation currently " +
                    "occupies. It falls when Spettro compacts.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            if (showsCompactActions(fraction)) {
                Spacer(Modifier.height(MD.space4))
                HairlineDivider()
                Spacer(Modifier.height(MD.space3))
                contextAdviceLine(fraction)?.let { advice ->
                    NoticeCard(
                        severity = if (contextSeverity(fraction) == ContextSeverity.FULL) {
                            Severity.Error
                        } else {
                            Severity.Warn
                        },
                        title = null,
                        body = advice,
                    )
                    Spacer(Modifier.height(MD.space3))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MD.space2),
                ) {
                    Button(
                        onClick = {
                            onCompact()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "Compact thread")
                    }
                    OutlinedButton(
                        onClick = {
                            // Unknown flips to on: the only reason to reach
                            // for this control while the window is filling is
                            // to stop being asked again.
                            val next = asked != true
                            asked = next
                            onToggleAutoCompact(next)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = "Auto ${autoCompactWord(asked)}", maxLines = 1)
                    }
                }
                Spacer(Modifier.height(MD.space2))
                Text(
                    text = "Compacting often costs nothing: Spettro first moves large " +
                        "tool output to disk, and only summarises if that was not enough.",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(MD.space6))
        }
    }
}

/** `on` / `off` / the honest third answer. */
private fun autoCompactWord(value: Boolean?): String = when (value) {
    true -> "on"
    false -> "off"
    null -> "?"
}
