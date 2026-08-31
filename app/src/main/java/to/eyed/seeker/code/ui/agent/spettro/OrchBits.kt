package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.seeker.code.core.Member
import to.eyed.seeker.code.core.OrchCounts
import to.eyed.seeker.code.core.OrchStatus
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The primitives every orchestration surface is drawn from — the run cards in
 * the transcript and the live peek above the chips (docs/SPETTRO.md, "Workflow
 * run card", "Ultra swarm card", "Live orchestration peek").
 *
 * They live in one file for a reason that is not tidiness: the peek is a
 * *readout* of the same run the card is a *record* of, and the two must agree
 * glyph for glyph. A user who learns that ✗ means "this member failed" in the
 * card must not meet a second vocabulary forty dp lower down; a meter that
 * rounds one failure in fifty to zero pixels in one place and not the other
 * turns a struggling run into an argument about which surface is lying.
 *
 * Everything here that decides *what* to draw — how much meter to fill, which
 * rows survive a cap, how a name is shortened — is a plain function with no
 * Compose in it, tested in `OrchBitsTest`. Only the drawing needs a composer.
 *
 * Motion policy (docs/SPETTRO.md, "Screen shell"): opacity and transform only.
 * The single exception in this file is the meter's fill width, which moves
 * inside a box of fixed size. Everything animated reads [LocalReduceMotion]
 * and stops dead when it is set — these surfaces repaint several times a
 * second during a fan-out, which is exactly the case the setting exists for.
 */

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

/** Run cards and the peek share one corner so they read as one family. */
internal val RunCardRadius = 12.dp

/** Member rows are a touch target: they open. */
internal val MemberRowHeight = 44.dp

/** The phase spine: rail plus dot, and the indent every member row sits at. */
internal val SpineWidth = 14.dp

/** Rows drawn while a run is live, per phase (a swarm gets [SwarmRowCap]). */
internal const val PhaseRowCap = 6

/** Rows drawn while a swarm is live — it is flat, so it can afford more. */
internal const val SwarmRowCap = 8

/** Ghost cells drawn before the `+N more queued` summary. */
internal const val GhostCap = 3

// ---------------------------------------------------------------------------
// Colour
// ---------------------------------------------------------------------------

/**
 * Ultra's one identity colour.
 *
 * The spec names `#f59e0b`, and this is the closest a themed app can honestly
 * get: the theme's own `warning` is the amber slot in every Zed theme, and it
 * is what the context gauge already turns at 75%. Amber doing double duty
 * (Ultra identity, context severity) is deliberate — see the swarm card in
 * docs/SPETTRO.md. The literal is a *fallback* for a theme that ships no
 * warning colour, not a hard-coded paint.
 */
@Composable
internal fun ultraAmber(): Color =
    LocalZedTheme.current.color("warning", Color(0xFFF59E0B))

/** The green a finished member and the done half of a meter share. */
@Composable
internal fun doneColor(): Color {
    val theme = LocalZedTheme.current
    return theme.color("success", theme.color("created", Color(0xFF4CAF50)))
}

@Composable
internal fun failColor(): Color =
    LocalZedTheme.current.color("error", MaterialTheme.colorScheme.error)

/**
 * The eight tints a sub-agent spec can take.
 *
 * Keyed on the spec, never on the instance: twelve `review#N` members are
 * twelve of *one* thing and must read that way, while `review` and `code`
 * running side by side must not. Red is not in the list — a member tinted
 * with the failure colour would claim a failure it has not had.
 */
private val MEMBER_TINTS: List<Pair<String, Color>> = listOf(
    "terminal.ansi.cyan" to Color(0xFF56B6C2),
    "terminal.ansi.magenta" to Color(0xFFC678DD),
    "terminal.ansi.blue" to Color(0xFF61AFEF),
    "terminal.ansi.green" to Color(0xFF98C379),
    "terminal.ansi.yellow" to Color(0xFFE5C07B),
    "terminal.ansi.bright_cyan" to Color(0xFF7DD3D8),
    "terminal.ansi.bright_magenta" to Color(0xFFD7A0EA),
    "terminal.ansi.bright_blue" to Color(0xFF8AB4F8),
)

/**
 * Which tint a spec takes — FNV-1a rather than [String.hashCode] so the answer
 * is arithmetic this file owns and cannot be changed under it.
 *
 * It must be a *function of the name*: a run whose members arrive in a
 * different order on a resume would otherwise recolour itself, and the colour
 * is the only thing carrying "these two rows are the same kind of worker".
 */
internal fun memberTintIndex(specId: String, count: Int = MEMBER_TINTS.size): Int {
    if (count <= 0 || specId.isEmpty()) return 0
    var hash = -2128831035 // FNV-1a 32-bit offset basis
    for (ch in specId) {
        hash = hash xor ch.code
        hash *= 16777619
    }
    return ((hash % count) + count) % count
}

@Composable
internal fun memberTint(specId: String): Color {
    val (key, fallback) = MEMBER_TINTS[memberTintIndex(specId)]
    return LocalZedTheme.current.color(key, fallback)
}

/** A tint laid over a surface at [amount], for borders and card fills. */
internal fun mix(base: Color, tint: Color, amount: Float): Color = lerp(base, tint, amount)

// ---------------------------------------------------------------------------
// The meter
// ---------------------------------------------------------------------------

/** How much of the meter each segment takes, 0..1. */
internal data class MeterFill(val done: Float, val failed: Float) {
    val track: Float get() = (1f - done - failed).coerceAtLeast(0f)
}

/**
 * A failure is never allowed to round to nothing.
 *
 * One failure in fifty is 2% of a 370 dp bar — about seven pixels once the
 * corner radius has eaten both ends — and a run that lost a member would draw
 * as a run that lost nothing. Six percent is the smallest slice that survives
 * the rounding at this width.
 */
internal const val MinFailedFraction = 0.06f

/**
 * The meter's two filled segments.
 *
 * `done` is clamped rather than scaled so the two never sum past the box: the
 * failure keeps the width it was promised and the success gives way, because
 * an over-reported success is the one error that matters here.
 */
internal fun meterFill(counts: OrchCounts): MeterFill {
    if (counts.total <= 0) return MeterFill(0f, 0f)
    val total = counts.total.toFloat()
    val failed = if (counts.failed > 0) {
        (counts.failed / total).coerceIn(MinFailedFraction, 1f)
    } else {
        0f
    }
    val done = if (counts.done > 0) {
        (counts.done / total).coerceIn(0f, 1f - failed)
    } else {
        0f
    }
    return MeterFill(done, failed)
}

/**
 * The 4 dp segmented meter: done, then failed, then track.
 *
 * The fill width is the one animated dimension in these surfaces, and it is
 * safe because it moves *inside* a box whose size never changes — nothing
 * below it can be pushed while it is being read.
 */
@Composable
internal fun ProgressMeter(
    counts: OrchCounts,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
) {
    val theme = LocalZedTheme.current
    val reduce = LocalReduceMotion.current
    val fill = meterFill(counts)
    val spec = if (reduce) snap() else tween<Float>(durationMillis = 240)
    val done by animateFloatAsState(fill.done, spec, label = "meter.done")
    val failed by animateFloatAsState(fill.failed, spec, label = "meter.failed")
    val track = theme.color("element.background", Color.Transparent)
    val doneTint = doneColor()
    val failTint = failColor()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = counts.ratio },
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        val gap = if (done > 0f && failed > 0f) 1.5.dp.toPx() else 0f
        val usable = (size.width - gap).coerceAtLeast(0f)
        drawRoundRect(track, size = size, cornerRadius = radius)
        var x = 0f
        for ((fraction, color) in listOf(done to doneTint, failed to failTint)) {
            if (fraction <= 0f) continue
            val width = usable * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(width, size.height),
                cornerRadius = radius,
            )
            x += width + gap
        }
    }
}

// ---------------------------------------------------------------------------
// The spinner and the status glyph
// ---------------------------------------------------------------------------

private const val SpinnerDots = 8
private const val SpinnerStepMs = 50L

/**
 * Spettro's own braille spinner, ported to a ring of dots.
 *
 * Eight discrete 50 ms steps — one revolution per 400 ms, exactly the CLI's
 * ⣾⣽⣻⢿⡿⣟⣯⣷ cadence — with the head at full opacity and the tail falling away
 * behind it. It is deliberately not a `CircularProgressIndicator`: this glyph
 * is what "Spettro is working" looks like everywhere else the user has seen
 * Spettro, and a Material sweep would be a different product's spinner.
 *
 * Discrete steps rather than a continuous rotation is also the cheap answer to
 * a fan-out: twenty of these on screen at once are twenty invalidations every
 * 50 ms, not twenty per frame.
 */
@Composable
internal fun SpettroSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    val reduce = LocalReduceMotion.current
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(reduce) {
        // Reduced motion keeps the ramp and stops the rotation, which is Zed's
        // own answer for the status-bar spinner: still legible as "working",
        // no movement.
        if (reduce) return@LaunchedEffect
        while (true) {
            delay(SpinnerStepMs)
            step = (step + 1) % SpinnerDots
        }
    }
    Canvas(modifier.size(size)) {
        val dot = this.size.minDimension * 0.11f
        val ring = this.size.minDimension / 2f - dot
        for (index in 0 until SpinnerDots) {
            val behind = (step - index + SpinnerDots) % SpinnerDots
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

/**
 * Running / done / failed, said twice: in a glyph and in a content
 * description.
 *
 * The check and the cross carry the state on their own shape, so a failure is
 * legible with the colour taken away — which is not a hypothetical on a phone
 * held at arm's length in sunlight.
 */
@Composable
internal fun StatusGlyph(
    status: OrchStatus,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    runningTint: Color? = null,
) {
    val theme = LocalZedTheme.current
    val accent = runningTint ?: theme.color("text.accent", MaterialTheme.colorScheme.primary)
    val description = when (status) {
        OrchStatus.Running -> "running"
        OrchStatus.Done -> "done"
        OrchStatus.Failed -> "failed"
    }
    Box(
        modifier = modifier
            .size(size + 2.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            OrchStatus.Running -> SpettroSpinner(accent, size = size)
            OrchStatus.Done -> Text(
                text = "✓",
                style = MaterialTheme.typography.labelSmall,
                color = doneColor(),
                modifier = Modifier.clearAndSetSemantics { },
            )

            OrchStatus.Failed -> Text(
                text = "✗",
                style = MaterialTheme.typography.labelSmall,
                color = failColor(),
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Counts, names and time
// ---------------------------------------------------------------------------

/** One term of the counts line, and whether it is the one that is coloured. */
internal data class CountTerm(val text: String, val failure: Boolean)

/**
 * `2 running · 4 done · 1 failed · 3 replayed`, zero terms dropped.
 *
 * `cached` is **always** spelled *replayed*: it means the resume journal
 * replayed a member rather than re-running it, not that a prompt cache was
 * hit, and the two are different claims about how much work actually happened.
 *
 * Zeroes are dropped rather than greyed because on a 400 dp column the line is
 * read as a shape, and `0 failed` in a row of counts is read as a number
 * before it is read as a zero.
 */
internal fun countsTerms(counts: OrchCounts): List<CountTerm> = buildList {
    if (counts.running > 0) add(CountTerm("${counts.running} running", failure = false))
    if (counts.done > 0) add(CountTerm("${counts.done} done", failure = false))
    if (counts.failed > 0) add(CountTerm("${counts.failed} failed", failure = true))
    if (counts.cached > 0) add(CountTerm("${counts.cached} replayed", failure = false))
}

@Composable
internal fun CountsLabel(counts: OrchCounts, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val terms = countsTerms(counts)
    if (terms.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        terms.forEachIndexed { index, term ->
            if (index > 0) {
                Text("·", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Text(
                text = term.text,
                style = MaterialTheme.typography.labelSmall,
                color = if (term.failure) failColor() else muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * Shorten an instance name **keeping its `#N`**.
 *
 * The suffix is the only part that distinguishes one member of a fan-out from
 * another: twelve rows reading `general-purp…` are one row twelve times, and
 * the number is what the reader is scanning for when a single member is stuck.
 */
internal fun truncateInstance(instance: String, max: Int = 16): String {
    if (max <= 0) return ""
    if (instance.length <= max) return instance
    val hash = instance.lastIndexOf('#')
    val suffix = if (hash > 0) instance.substring(hash) else ""
    // No room for head + ellipsis + suffix: the plain truncation at least
    // keeps the front, which is where the spec name lives.
    if (suffix.isEmpty() || suffix.length + 2 > max) return instance.take(max - 1) + "…"
    return instance.take(max - suffix.length - 1) + "…" + suffix
}

/**
 * `12s`, `1m 07s`, `1h 04m` — elapsed, never a clock time.
 *
 * Two units at most: the third is noise at the width this is drawn, and a run
 * measured in hours is not one anybody is watching the seconds of.
 */
internal fun elapsedLabel(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m ${rest.toString().padStart(2, '0')}s"
        else -> "${rest}s"
    }
}

// ---------------------------------------------------------------------------
// Which rows survive
// ---------------------------------------------------------------------------

/**
 * The rows a phase or a swarm actually draws.
 *
 * [rows] are drawn in the order given — which is the order the fold produced,
 * running before failed before done, stable inside each group. The cap picks
 * *survivors*, it never re-sorts them: a live list that reorders under the
 * thumb is unreadable, and the fold has already put the actionable rows first.
 *
 * [folded] is the settled form's rule (docs/SPETTRO.md): a finished run drops
 * successful DETAIL, never STRUCTURE. Every failure keeps its row and its
 * reason; the successes collapse behind one `4 done` disclosure, because after
 * the fact nobody reads twelve identical green lines.
 */
internal data class MemberSplit(
    val rows: List<Member>,
    val folded: List<Member>,
    val hidden: List<Member>,
) {
    val hiddenCount: Int get() = hidden.size
}

internal fun splitMembers(
    members: List<Member>,
    live: Boolean,
    cap: Int,
    showAll: Boolean = false,
): MemberSplit {
    if (showAll) return MemberSplit(members, emptyList(), emptyList())
    if (!live) {
        val kept = members.filter { it.status != OrchStatus.Done }
        val shown = kept.take(cap)
        return MemberSplit(
            rows = shown,
            folded = members.filter { it.status == OrchStatus.Done },
            hidden = kept.drop(cap),
        )
    }
    return MemberSplit(
        rows = members.take(cap),
        folded = emptyList(),
        hidden = members.drop(cap),
    )
}

/**
 * `… 5 more running` when everything hidden is still going, `… 5 more`
 * otherwise.
 *
 * The distinction is the whole value of the line: "five more are working" is
 * an answer, "five more exist" is a scrollbar.
 */
internal fun overflowLabel(hidden: List<Member>): String {
    if (hidden.isEmpty()) return ""
    val allRunning = hidden.all { it.status == OrchStatus.Running }
    return if (allRunning) "… ${hidden.size} more running" else "… ${hidden.size} more"
}

// ---------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------

/**
 * One sub-agent: glyph, tinted instance, what it is doing, and — when it
 * failed — why.
 *
 * One line, never wrapped. During a fan-out the detail changes every time the
 * member makes a tool call, and a detail allowed to wrap makes the whole
 * column shiver several times a second.
 */
@Composable
internal fun MemberRow(
    member: Member,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    instanceMax: Int = 16,
    /** `conflict` / `preserved` — unmerged swarm work. Empty draws nothing. */
    trailingPill: String = "",
) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val tint = memberTint(member.specId)
    val hasDetail = member.children.isNotEmpty() || member.resultText.isNotBlank()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MemberRowHeight)
                .clickable(enabled = hasDetail, onClickLabel = member.instance) { onToggle() }
                .padding(horizontal = 4.dp),
        ) {
            StatusGlyph(member.status, runningTint = tint)
            Text(
                text = truncateInstance(member.instance, instanceMax),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
            Text(
                text = member.liveDetail.replace('\n', '⏎'),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailingPill.isNotEmpty()) Pill(trailingPill, failColor())
            if (member.cached) {
                Pill("REPLAYED", theme.color("text.accent", MaterialTheme.colorScheme.primary))
            }
            if (member.children.isNotEmpty()) {
                Text(
                    text = "(${member.children.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
            if (hasDetail) {
                Text(
                    text = if (expanded) "⌄" else "›",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }

        // The failure reason is never behind the disclosure. It is the only
        // thing on a settled card the reader came back for, and a provider's
        // bare `429 after 3 attempts` is one line.
        if (member.status == OrchStatus.Failed && member.failureReason.isNotBlank()) {
            Text(
                text = member.failureReason.trim(),
                style = MaterialTheme.typography.labelSmall,
                color = mix(muted, failColor(), 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, bottom = 6.dp, end = 4.dp),
            )
        }

        if (expanded && hasDetail) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (member.task.isNotBlank()) {
                    Text(
                        text = member.task.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = text,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                for (child in member.children) {
                    Text(
                        text = "· " + child.title
                            .removePrefix("[${member.instance}]")
                            .trim()
                            .replace('\n', '⏎'),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (member.resultText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = member.resultText.trim(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = if (member.resultIsJson) FontFamily.Monospace else null,
                        color = muted,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A trailing pill on a member row.
 *
 * Two of them exist. `REPLAYED` says the resume journal handed this member
 * back rather than re-running it — without it a resumed run reads as a run
 * that re-did everything in four seconds. `conflict` / `preserved` say a
 * swarm member's branch was not merged away, which is the only unrecovered
 * work a swarm can leave behind.
 */
@Composable
private fun Pill(label: String, accent: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = accent,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/**
 * A queued swarm item — the ghost cell.
 *
 * Same geometry as a real member row, at half opacity, so when Ultra's ramp
 * finally launches this item the row fills in where it stood and nothing below
 * it moves. Nobody else draws these, and without them a 20-item swarm spends
 * its first fifteen seconds looking like a 5-item one.
 */
@Composable
internal fun GhostRow(item: String, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MemberRowHeight)
            .alpha(0.5f)
            .padding(horizontal = 4.dp),
    ) {
        Text("○", style = MaterialTheme.typography.labelSmall, color = muted)
        Text(
            text = "queued",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
        )
        Text(
            text = item.replace('\n', '⏎'),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A one-line disclosure: `⌄ log (7)  "journal replayed 0 entries"`.
 *
 * Always a disclosure on the phone, never the desktop's "inline when there are
 * three or fewer" rule — a card whose height depends on how much it happens to
 * contain cannot be scanned, and the peek line is what tells you whether
 * opening it is worth the tap.
 */
@Composable
internal fun Disclosure(
    label: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    peek: String = "",
    peekMono: Boolean = false,
) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClickLabel = label) { onToggle() }
            .padding(horizontal = 4.dp),
    ) {
        Text(
            text = if (open) "⌄" else "›",
            style = MaterialTheme.typography.labelSmall,
            color = muted,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
        )
        if (peek.isNotEmpty()) {
            Text(
                text = peek.replace('\n', '⏎'),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = if (peekMono) FontFamily.Monospace else null,
                color = muted.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The frame both run cards take: a rounded box with a tinted border.
 *
 * [accent] is the run's identity — amber for Ultra, the neutral border for a
 * workflow — and a failed run flips it to the error mix and adds no second
 * badge. One signal per fact: a red frame and a `FAILED` chip and a red status
 * dot are three ways of saying the same thing and two of them are noise.
 */
@Composable
internal fun RunCardFrame(
    accent: Color,
    failed: Boolean,
    modifier: Modifier = Modifier,
    fillAmount: Float = 0.06f,
    borderAmount: Float = 0.22f,
    content: @Composable () -> Unit,
) {
    val theme = LocalZedTheme.current
    val surface = theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface)
    val edge = if (failed) failColor() else accent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RunCardRadius))
            .background(mix(surface, edge, fillAmount))
            .border(
                width = 1.dp,
                color = mix(surface, edge, if (failed) 0.45f else borderAmount),
                shape = RoundedCornerShape(RunCardRadius),
            )
            .padding(10.dp),
    ) {
        content()
    }
}

/** A hairline between a card's header and its body. */
@Composable
internal fun CardRule(modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.color("border.variant", theme.color("border", Color.Transparent))),
    )
}

/**
 * The phase spine: 14 dp of rail and a dot, drawn once per phase row group.
 *
 * A pending phase draws a *dashed* rail and a dashed dot rather than nothing,
 * because the whole point of a declared plan is that you can see what has not
 * happened yet.
 */
@Composable
internal fun PhaseDot(
    status: OrchStatus,
    pending: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val reduce = LocalReduceMotion.current
    val accent = theme.color("text.accent", MaterialTheme.colorScheme.primary)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val color = when {
        pending -> muted
        status == OrchStatus.Failed -> failColor()
        status == OrchStatus.Running -> accent
        else -> muted
    }
    // The halo pulses opacity only — a halo that pulsed its radius would move
    // the rail beside it, and the rail is the alignment the eye reads phases
    // against.
    val transition = rememberInfiniteTransition(label = "phase.pulse")
    val wave by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse),
        label = "phase.halo",
    )
    val pulse = if (status == OrchStatus.Running && !pending && !reduce) wave else 1f
    Canvas(modifier.size(SpineWidth)) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = 3.dp.toPx()
        if (status == OrchStatus.Running && !pending) {
            drawCircle(color.copy(alpha = 0.25f * pulse), radius = radius + 3.dp.toPx(), center = centre)
        }
        if (pending) {
            drawCircle(color.copy(alpha = 0.55f), radius = radius, center = centre, style = DashedDot)
        } else {
            drawCircle(color, radius = radius, center = centre)
        }
    }
}

private val DashedDot = Stroke(
    width = 3f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
)

/** The vertical rail a phase's member rows hang off. */
@Composable
internal fun SpineRail(
    dashed: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val border = theme.color("border.variant", theme.color("border", Color.Transparent))
    Canvas(
        modifier = modifier
            .width(SpineWidth)
            .fillMaxHeight(),
    ) {
        val x = size.width / 2f
        drawLine(
            color = border,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3f, 4f), 0f) else null,
        )
    }
}
