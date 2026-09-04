package to.eyed.thragg.ui.agent.spettro

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.core.Member
import to.eyed.thragg.core.OrchCounts
import to.eyed.thragg.core.OrchStatus
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.SeekerSpinner
import to.eyed.thragg.ui.components.ZedCodeBlock
import to.eyed.thragg.ui.theme.LocalReduceMotion
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.LocalZedTheme
import to.eyed.thragg.ui.theme.DisclosureMark
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.MonoSmall
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.animateSize
import to.eyed.thragg.ui.theme.readable

/**
 * The primitives every orchestration surface is drawn from — the run cards in
 * the transcript and the live peek above the chips (docs/SPETTRO.md, "Workflow
 * run card", "Ultra swarm card", "Live orchestration peek").
 *
 * They live in one file for a reason that is not tidiness: the peek is a
 * *readout* of the same run the card is a *record* of, and the two must agree
 * glyph for glyph. A user who learns that a cross means "this member failed"
 * in the card must not meet a second vocabulary forty dp lower down; a meter
 * that rounds one failure in fifty to zero pixels in one place and not the
 * other turns a struggling run into an argument about which surface is lying.
 *
 * Everything here that decides *what* to draw — how much meter to fill, which
 * rows survive a cap, how a name is shortened — is a plain function with no
 * Compose in it, tested in `OrchBitsTest`. Only the drawing needs a composer.
 *
 * **This file is in the MATERIAL half** (docs/VISUAL.md, "THE BOUNDARY,
 * EXACTLY"). Its inks come from `MaterialTheme.colorScheme` and
 * [LocalSeekerColors], not from `theme.color(...)`, because a run card is a
 * card in an app rather than a pane in an editor. The one exception is
 * [memberTint], which reads the theme's own `terminal.ansi.*` palette because
 * that is what a sub-agent tint *is* — and it is solved for contrast before it
 * is drawn, which the Zed half would not do.
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
internal val RunCardRadius = MD.radiusMd

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
 * Ultra's one identity colour, as a WASH.
 *
 * The spec names `#f59e0b`; the bridge resolves it to the theme's own
 * `warning`, which is the amber slot in every Zed theme and what the context
 * gauge already turns at 75%. This is the raw hue and belongs behind things —
 * a card fill, a border. Anything drawing amber as *text* wants
 * `LocalSeekerColors.current.warnInk`, which is the same hue solved to 4.5:1:
 * on Ayu Light the raw value measures 1.64:1 (docs/VISUAL.md, "THE HYBRID").
 */
@Composable
internal fun ultraAmber(): Color = LocalSeekerColors.current.ultraAmber

/**
 * The green a finished member and the done half of a meter share.
 *
 * Solved at TEXT_RATIO rather than MARK_RATIO even though most of its uses are
 * marks: one value is cheaper to reason about than two, and an ink used where
 * a mark would do is merely a little further from the raw hue. Raw `created`
 * measures 2.11:1 on Ayu Light, which is neither.
 */
@Composable
internal fun doneColor(): Color = LocalSeekerColors.current.addedInk

/** Failure red, for a glyph, a meter segment or a count. See [doneColor]. */
@Composable
internal fun failColor(): Color = LocalSeekerColors.current.dangerInk

/**
 * The eight tints a sub-agent spec can take, as keys into the theme's own
 * terminal palette.
 *
 * Keyed on the spec, never on the instance: twelve `review#N` members are
 * twelve of *one* thing and must read that way, while `review` and `code`
 * running side by side must not. Red is not in the list — a member tinted
 * with the failure colour would claim a failure it has not had.
 *
 * **There is no baked fallback table any more, and that was a bug rather than
 * a style choice.** These eight keys used to carry One Dark's hexes as their
 * fallbacks, so a user on Gruvbox got One Dark's cyan and magenta in the
 * middle of a Gruvbox card. A theme that ships no `terminal.ansi.*` block
 * falls back to [ZedTheme.playerColor] instead — Zed's own participant
 * palette, which every theme has because it is derived from the cursor.
 */
private val MEMBER_TINT_KEYS: List<String> = listOf(
    "terminal.ansi.cyan",
    "terminal.ansi.magenta",
    "terminal.ansi.blue",
    "terminal.ansi.green",
    "terminal.ansi.yellow",
    "terminal.ansi.bright_cyan",
    "terminal.ansi.bright_magenta",
    "terminal.ansi.bright_blue",
)

/**
 * Which tint a spec takes — FNV-1a rather than [String.hashCode] so the answer
 * is arithmetic this file owns and cannot be changed under it.
 *
 * It must be a *function of the name*: a run whose members arrive in a
 * different order on a resume would otherwise recolour itself, and the colour
 * is the only thing carrying "these two rows are the same kind of worker".
 */
internal fun memberTintIndex(specId: String, count: Int = MEMBER_TINT_KEYS.size): Int {
    if (count <= 0 || specId.isEmpty()) return 0
    var hash = -2128831035 // FNV-1a 32-bit offset basis
    for (ch in specId) {
        hash = hash xor ch.code
        hash *= 16777619
    }
    return ((hash % count) + count) % count
}

/**
 * A member's tint, drawn on a card and therefore solved against one.
 *
 * The hue is the theme's; the lightness is whatever clears 4.5:1 on
 * [SeekerColors.cardGround]. A terminal palette is authored for a terminal —
 * full-strength ink on the terminal's own background — and a run card is a
 * washed surface two steps up the ladder, so `terminal.ansi.yellow` on a light
 * theme arrives at about 1.9:1 if it is drawn raw. It is drawn as the member's
 * NAME at 12sp, which is exactly the case the solver exists for.
 */
@Composable
internal fun memberTint(specId: String): Color {
    val theme = LocalZedTheme.current
    val ground = LocalSeekerColors.current.cardGround
    val index = memberTintIndex(specId)
    val raw = theme.color(MEMBER_TINT_KEYS[index], theme.playerColor(index))
    return readable(raw, ground)
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
    val reduce = LocalReduceMotion.current
    val fill = meterFill(counts)
    val spec = if (reduce) snap() else tween<Float>(durationMillis = 240)
    val done by animateFloatAsState(fill.done, spec, label = "meter.done")
    val failed by animateFloatAsState(fill.failed, spec, label = "meter.failed")
    // The groove is a rung of the surface ladder rather than a wash of the
    // ink: a meter with nothing in it should read as an empty channel, and an
    // 8%-of-the-text track reads as a very faint fill.
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val doneTint = doneColor()
    val failTint = failColor()

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            // The em dash [OrchCounts.ratio] draws for a run with no members
            // yet is a mark, not a word, so the meter says the sentence.
            .semantics {
                contentDescription =
                    if (counts.total > 0) counts.ratio else "not started yet"
            },
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
// The cell strip
// ---------------------------------------------------------------------------

/** How many cells a 400 dp header can carry before they stop being countable. */
internal const val CellCap = 32

/**
 * One cell per unit of work, in the order the run declared it.
 *
 * `null` is a cell nobody has been launched for yet — a queued swarm item, a
 * member of a phase that has not started. Drawing it is the point: a plan that
 * announced twenty items and shows five cells is a plan that shrank.
 *
 * Pure, so `OrchBitsTest` can pin the padding and the cap without a phone.
 */
internal fun cellStates(
    members: List<Member>,
    total: Int,
    cap: Int = CellCap,
): List<OrchStatus?> {
    val known = members.map { it.status as OrchStatus? }
    val queued = (total - known.size).coerceAtLeast(0)
    return (known + List(queued) { null }).take(cap)
}

/**
 * The strip of cells that lives in a run card's HEADER.
 *
 * In the header rather than the body, and that placement is the whole
 * argument: a card the reader collapsed must not stop showing what broke.
 * Collapsing hides the *detail* of a run, never its shape.
 *
 * A RUNNING cell takes the accent and not the member's own mode tint. Half the
 * mode palette is a green, so a swarm of `code` agents drew running-green
 * beside done-green and the strip said nothing at all.
 */
@Composable
internal fun CellStrip(
    states: List<OrchStatus?>,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
) {
    if (states.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val queuedInk = scheme.outlineVariant
    val runningInk = scheme.primary
    val doneInk = colors.addedMark
    val failedInk = colors.removedMark
    val spoken = remember(states) { cellSummary(states) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = spoken },
    ) {
        val gap = 2.dp.toPx()
        val cells = states.size
        val cell = ((size.width - gap * (cells - 1)) / cells).coerceAtLeast(1f)
        val radius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
        states.forEachIndexed { index, status ->
            drawRoundRect(
                color = when (status) {
                    OrchStatus.Done -> doneInk
                    OrchStatus.Failed -> failedInk
                    OrchStatus.Running -> runningInk
                    null -> queuedInk
                },
                topLeft = Offset(index * (cell + gap), 0f),
                size = Size(cell, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** What a screen reader hears instead of thirty-two rectangles. */
internal fun cellSummary(states: List<OrchStatus?>): String {
    val done = states.count { it == OrchStatus.Done }
    val failed = states.count { it == OrchStatus.Failed }
    val running = states.count { it == OrchStatus.Running }
    val queued = states.count { it == null }
    return listOfNotNull(
        "$done done".takeIf { done > 0 },
        "$failed failed".takeIf { failed > 0 },
        "$running running".takeIf { running > 0 },
        "$queued queued".takeIf { queued > 0 },
    ).joinToString(", ").ifEmpty { "not started yet" }
}

// ---------------------------------------------------------------------------
// The spinner and the status glyph
// ---------------------------------------------------------------------------

/**
 * Spettro's braille spinner, now [SeekerSpinner].
 *
 * The implementation MOVED to `ui/components/SeekerSpinner.kt` in P3 — the
 * cadence and the reduce-motion branch were already right, and the only thing
 * wrong with them was that they lived in the agent package where the build
 * strip and the setup steps could not reach them. This name survives as the
 * one-line forward because the orchestration surfaces read as one vocabulary
 * and `SpettroSpinner` is what the peek and the cards call it.
 */
@Composable
internal fun SpettroSpinner(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
) {
    SeekerSpinner(modifier = modifier, size = size, color = color)
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
    val accent = runningTint ?: MaterialTheme.colorScheme.primary
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
            // The [Box] above already says "done" / "failed"; these are the
            // picture of it and must not be read out a second time.
            OrchStatus.Done -> SeekerIcon(
                icon = R.drawable.ic_ui_check,
                contentDescription = null,
                tint = doneColor(),
                size = size,
            )

            OrchStatus.Failed -> SeekerIcon(
                icon = R.drawable.ic_ui_close,
                contentDescription = null,
                tint = failColor(),
                size = size,
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val danger = failColor()
    val terms = countsTerms(counts)
    if (terms.isEmpty()) return
    // Tabular, because every one of these figures ticks during a fan-out and
    // `1 running` becoming `2 running` must not move `3 done` sideways.
    val style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TabularNums)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MD.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        terms.forEachIndexed { index, term ->
            if (index > 0) Text("·", style = style, color = muted)
            Text(
                text = term.text,
                style = style,
                color = if (term.failure) danger else muted,
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
 * The arithmetic MOVED to `ui/components/RunTicker.kt` with the readout that
 * is now every caller's front end; this forwards so the peek, the run cards
 * and `AgentScreen`'s own clock keep printing the same string, and so
 * `OrchBitsTest` keeps pinning it from where it was written.
 */
internal fun elapsedLabel(millis: Long): String =
    to.eyed.thragg.ui.components.elapsedLabel(millis)

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
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val muted = scheme.onSurfaceVariant
    val tint = memberTint(member.specId)
    val hasDetail = member.children.isNotEmpty() || member.resultText.isNotBlank()

    Column(modifier = modifier.fillMaxWidth().animateSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MemberRowHeight)
                .clickable(enabled = hasDetail, onClickLabel = member.instance) { onToggle() }
                .padding(horizontal = MD.space1),
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
                // The live detail is a path or a command — the buffer's face,
                // so the same string looks the same here as in the editor.
                style = MonoSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (trailingPill.isNotEmpty()) Pill(trailingPill, colors.dangerInk)
            if (member.cached) Pill("REPLAYED", scheme.primary)
            if (member.children.isNotEmpty()) {
                Text(
                    text = "(${member.children.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
            if (hasDetail) DisclosureMark(open = expanded, tint = muted)
        }

        // The failure reason is never behind the disclosure. It is the only
        // thing on a settled card the reader came back for, and a provider's
        // bare `429 after 3 attempts` is one line.
        if (member.status == OrchStatus.Failed && member.failureReason.isNotBlank()) {
            Text(
                text = member.failureReason.trim(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.dangerInk,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, bottom = MD.iconGap, end = MD.space1),
            )
        }

        if (expanded && hasDetail) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = MD.space1, bottom = MD.space2),
                verticalArrangement = Arrangement.spacedBy(MD.space05),
            ) {
                if (member.task.isNotBlank()) {
                    Text(
                        text = member.task.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(MD.space1))
                }
                for (child in member.children) {
                    Text(
                        text = "· " + child.title
                            .removePrefix("[${member.instance}]")
                            .trim()
                            .replace('\n', '⏎'),
                        style = MonoSmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (member.resultText.isNotBlank()) {
                    Spacer(Modifier.height(MD.space1))
                    if (member.resultIsJson) {
                        // A JSON result is code, so it goes in the island
                        // rather than being drawn as Material text in the
                        // system mono (docs/VISUAL.md, "THE SEAM").
                        ZedCodeBlock(
                            text = member.resultText.trim(),
                            language = "json",
                            maxLines = 12,
                        )
                    } else {
                        Text(
                            text = member.resultText.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
            .clip(RoundedCornerShape(MD.radiusXs))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = MD.space1, vertical = 1.dp),
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MemberRowHeight)
            .alpha(0.5f)
            .padding(horizontal = MD.space1),
    ) {
        SeekerIcon(
            icon = R.drawable.ic_ui_circle,
            contentDescription = null,
            tint = muted,
            size = IconSize.Marker,
        )
        Text(
            text = "queued",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
        )
        Text(
            text = item.replace('\n', '⏎'),
            style = MonoSmall,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A one-line disclosure: `log (7)  "journal replayed 0 entries"`.
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(onClickLabel = label) { onToggle() }
            .padding(horizontal = MD.space1),
    ) {
        DisclosureMark(open = open, tint = muted)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
        )
        if (peek.isNotEmpty()) {
            Text(
                text = peek.replace('\n', '⏎'),
                style = if (peekMono) MonoSmall else MaterialTheme.typography.labelSmall,
                color = muted.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The frame both run cards take: a rounded box with a tinted wash and a
 * tinted edge.
 *
 * **[wash] IS A WASH AND NOT AN INK, and the two are separate values on
 * purpose** (docs/VISUAL.md, "Agent — a workflow run card"). This takes the
 * raw hue — `primary` for a workflow, `ultraAmber` for a swarm, `agentAccent`
 * for a sub-agent — because darkening a fill to clear a text ratio only makes
 * the card muddy. Whatever the caller draws as TEXT inside it takes the
 * matching `*Ink` from [LocalSeekerColors] instead.
 *
 * A failed run flips the wash to the failure hue and adds no second badge. One
 * signal per fact: a red frame and a `FAILED` chip and a red status dot are
 * three ways of saying the same thing and two of them are noise.
 *
 * Elevation is zero, as everywhere: the card is a wash plus a hairline, and
 * `animateSize()` is what makes opening it a movement rather than a jump.
 */
@Composable
internal fun RunCardFrame(
    wash: Color,
    failed: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalSeekerColors.current
    val hue = if (failed) colors.removedMark else wash
    val shape = RoundedCornerShape(RunCardRadius)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .animateSize()
            .clip(shape)
            // 6% dark / 4.5% light: a light theme's canvas has less headroom
            // above it, so the same alpha reads as a stronger tint.
            .background(hue.copy(alpha = if (colors.isDark) 0.06f else 0.045f))
            .border(MD.hairline, hue.copy(alpha = 0.25f), shape)
            .padding(MD.space3),
    ) {
        content()
    }
}

/** A hairline between a card's header and its body. */
@Composable
internal fun CardRule(modifier: Modifier = Modifier) {
    HairlineDivider(modifier = modifier)
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
    val reduce = LocalReduceMotion.current
    val scheme = MaterialTheme.colorScheme
    val danger = failColor()
    val color = when {
        pending -> scheme.onSurfaceVariant
        status == OrchStatus.Failed -> danger
        status == OrchStatus.Running -> scheme.primary
        else -> scheme.onSurfaceVariant
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
    val border = MaterialTheme.colorScheme.outlineVariant
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
            strokeWidth = MD.hairline.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3f, 4f), 0f) else null,
        )
    }
}
