package to.eyed.thragg.ui.agent.spettro

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.thragg.R
import to.eyed.thragg.core.Member
import to.eyed.thragg.core.OrchRun
import to.eyed.thragg.core.OrchStatus
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.StatusDot
import to.eyed.thragg.ui.theme.Durations
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalReduceMotion
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.accentIcon
import to.eyed.thragg.ui.theme.mutedIcon

/**
 * The live orchestration peek (docs/SPETTRO.md, "Live orchestration peek").
 *
 * It answers one question, continuously, while a fan-out is in flight: **who
 * is still working, and on what**. The run card in the transcript is the
 * RECORD — it keeps every member, every failure and every count — and it is
 * also four hundred dp up the scroll by the time twenty agents are running.
 * This is the readout: pinned above the chips, one thumb-reach from the
 * bottom, and it lists only what is still moving.
 *
 * The interesting part is what happens when a run *ends*. A section that
 * vanishes mid-glance is worse than one that lingers, so:
 *
 *  - the last live snapshot is **held** for [Durations.RUN_HOLD], unchanged,
 *    which is how long its FINAL counts stay readable;
 *  - it then collapses to a one-line settled summary;
 *  - and it is **released** at [PeekReleaseMs].
 *
 * A settled run keeps the slot the eye found it in — [PeekSlot.slot] is
 * assigned once per run key and never recomputed — so nothing below it jumps
 * while the reader is still looking at it.
 *
 * A run that merely *disappeared* from the transcript is never reported as a
 * success: [peekStatus] and [peekNote] downgrade it to `failed` when it had
 * failures and to a bare `ended` otherwise.
 */

// ---------------------------------------------------------------------------
// The settle choreography — pure
// ---------------------------------------------------------------------------

/**
 * How long the full live snapshot survives the run that produced it.
 *
 * [Durations.RUN_HOLD] rather than a number of its own: this strip is the only
 * place a run's final counts are shown, and "how long a finished thing stays
 * readable" is one decision the whole app makes once (docs/VISUAL.md,
 * "Foundations", MOTION). It was 1600 ms, which was the copy-confirmation's
 * duration wearing a run's clothes — long enough to notice a change, too short
 * to read four figures.
 */
internal const val PeekHoldMs = Durations.RUN_HOLD

/** And how long the one-line summary survives after that. */
internal const val PeekReleaseMs = 7000L

/** Running members listed per workflow phase, and per swarm. */
internal const val PeekPhaseCap = 3
internal const val PeekSwarmCap = 5

/** Instance names are clamped hard here: this column is one line per row. */
internal const val PeekInstanceMax = 12

/** One run's slot in the peek, with the moment it stopped moving. */
internal data class PeekSlot(
    val key: String,
    /** Assigned once, never recomputed: the position the eye found it in. */
    val slot: Int,
    val run: OrchRun,
    /** 0 while it is still moving; else the wall clock it settled at. */
    val settledAt: Long,
    /** It left the transcript rather than reporting an ending. */
    val vanished: Boolean,
) {
    /** Still drawn in full. */
    fun isHeld(now: Long): Boolean = settledAt == 0L || now - settledAt < PeekHoldMs

    fun isReleased(now: Long): Boolean = settledAt != 0L && now - settledAt >= PeekReleaseMs
}

/**
 * Fold the previous slots and the current runs into the next slots.
 *
 * Pure, and driven by a clock the caller passes in, because this is the whole
 * choreography and it is worth testing without a composition or a delay.
 *
 * Three rules it encodes:
 *
 *  1. **A run is only ever admitted while it is moving.** A transcript that
 *     scrolled a finished run into view must not pop the peek open for
 *     something that ended ten minutes ago.
 *  2. **Slots are assigned once.** New keys take the next free number; an
 *     existing key keeps the number it had, settled or not.
 *  3. **Settling is sticky.** The first poll that sees a run stop stamps the
 *     time; later polls never re-stamp it, or a run that kept being re-sent
 *     would never release.
 */
internal fun peekSlots(
    previous: List<PeekSlot>,
    runs: List<OrchRun>,
    now: Long,
): List<PeekSlot> {
    val byKey = previous.associateBy { it.key }
    var next = (previous.maxOfOrNull { it.slot } ?: -1) + 1
    val seen = mutableSetOf<String>()
    val out = mutableListOf<PeekSlot>()

    for (run in runs) {
        val key = run.tool.key
        val prior = byKey[key]
        if (prior == null && !run.status.isMoving) continue
        seen += key
        val settledAt = when {
            run.status.isMoving -> 0L
            prior != null && prior.settledAt != 0L -> prior.settledAt
            else -> now
        }
        out += PeekSlot(
            key = key,
            slot = prior?.slot ?: next++,
            run = run,
            settledAt = settledAt,
            vanished = false,
        )
    }

    // A run that stopped being sent has ended in some way nobody reported.
    for (slot in previous) {
        if (slot.key in seen) continue
        out += slot.copy(settledAt = if (slot.settledAt != 0L) slot.settledAt else now, vanished = true)
    }

    return out.filterNot { it.isReleased(now) }.sortedBy { it.slot }
}

/**
 * The status a settled slot shows.
 *
 * A run that vanished mid-flight is reported by what it had already produced —
 * failures make it a failure — and never by the spinner it was last seen
 * wearing, which would leave a dead run pulsing for seven seconds.
 */
internal fun peekStatus(run: OrchRun, vanished: Boolean): OrchStatus = when {
    !vanished -> run.status
    run.counts.failed > 0 -> OrchStatus.Failed
    else -> OrchStatus.Done
}

/**
 * The settled line's note: the run's own summary, else its counts, else a word
 * that claims nothing.
 *
 * `ended` rather than `finished` for a run that vanished without counts —
 * "finished" is a claim about an outcome, and the one thing known about this
 * run is that it stopped being mentioned.
 */
internal fun peekNote(run: OrchRun, vanished: Boolean): String {
    val own = (run as? OrchRun.Workflow)?.summary.orEmpty()
    if (own.isNotBlank()) return own
    val parts = buildList {
        if (run.counts.done > 0) add("${run.counts.done} done")
        if (run.counts.failed > 0) add("${run.counts.failed} failed")
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")
    return if (vanished) "ended" else "finished"
}

/** The members a peek row lists: only what is still moving, capped. */
internal fun peekMembers(members: List<Member>, cap: Int): List<Member> =
    members.filter { it.status == OrchStatus.Running }.take(cap)

/** How many running members the cap hid. */
internal fun peekHidden(members: List<Member>, cap: Int): Int =
    (members.count { it.status == OrchStatus.Running } - cap).coerceAtLeast(0)

/** The collapsed line's name for a run. */
internal fun peekName(run: OrchRun): String = when (run) {
    is OrchRun.Workflow -> run.name.ifBlank { "workflow" }
    is OrchRun.Swarm ->
        "ultra swarm" + run.subagentType.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
}

// ---------------------------------------------------------------------------
// The surface
// ---------------------------------------------------------------------------

/** How often the choreography is re-evaluated. Cheap: the fold is a few maps. */
private const val PeekTickMs = 250L

/**
 * The live-run strip: one line, or a half-sheet's worth of live detail.
 *
 * Draws nothing at all when no run is live and none is still settling, so the
 * caller can place it unconditionally and let it take zero height — which is
 * also what the IME rule needs (docs/SPETTRO.md, "Screen shell": the plan and
 * the peek collapse to zero when the keyboard is up).
 *
 * IT ARRIVES AND LEAVES AT DIFFERENT SPEEDS, and that asymmetry is the whole
 * point of the redesign (docs/VISUAL.md, "Foundations", MOTION):
 * [Durations.BAND_IN] in, [Durations.BAND_OUT] out. A run that has just
 * finished is the thing the reader is most likely to be looking at, so a strip
 * that blinked away at the speed it came in would read as a glitch rather than
 * as a completion. The [Durations.RUN_HOLD] before that is the same argument
 * one level down: the full snapshot survives the run by four seconds so its
 * FINAL counts can be read, because this strip is the only place they appear.
 *
 * The last non-empty snapshot is kept in [shown] so the exit transition has
 * something to draw. Without it the strip would compose against an empty list
 * for the whole 240 ms of its own departure, which is to say it would vanish
 * instantly and animate nothing.
 */
@Composable
fun LiveRunPeek(
    runs: List<OrchRun>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val reduce = LocalReduceMotion.current

    val latest by rememberUpdatedState(runs)
    var slots by remember { mutableStateOf(emptyList<PeekSlot>()) }
    var shown by remember { mutableStateOf(emptyList<PeekSlot>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One loop rather than "recompute on new traffic" plus "and also on a
    // timer": the hold and the release happen when *no* traffic is arriving,
    // which is exactly the case a traffic-driven update never covers.
    LaunchedEffect(Unit) {
        while (true) {
            val stamp = System.currentTimeMillis()
            now = stamp
            slots = peekSlots(slots, latest, stamp)
            if (slots.isNotEmpty()) shown = slots
            delay(if (slots.isEmpty() && latest.isEmpty()) PeekTickMs * 2 else PeekTickMs)
        }
    }

    AnimatedVisibility(
        visible = slots.isNotEmpty(),
        enter = fadeIn(tween(if (reduce) 0 else Durations.BAND_IN)) +
            expandVertically(tween(if (reduce) 0 else Durations.BAND_IN)),
        exit = fadeOut(tween(if (reduce) 0 else Durations.BAND_OUT)) +
            shrinkVertically(tween(if (reduce) 0 else Durations.BAND_OUT)),
        modifier = modifier,
    ) {
        if (shown.isEmpty()) return@AnimatedVisibility
        val liveCount = shown.count { it.settledAt == 0L }
        Column(modifier = Modifier.fillMaxWidth().background(scheme.surfaceContainer)) {
            HairlineDivider()
            if (!expanded) {
                PeekCollapsed(shown, onToggle)
            } else {
                PeekExpandedHeader(liveCount, onToggle)
                HairlineDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = PeekMaxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = MD.space4, vertical = MD.space2),
                    verticalArrangement = Arrangement.spacedBy(MD.iconGap),
                ) {
                    for (slot in shown) {
                        if (slot.isHeld(now)) PeekRun(slot) else PeekSettledLine(slot)
                    }
                }
            }
        }
    }
}

/** Half a screen: past this the strip is a sheet pretending not to be one. */
private val PeekMaxHeight = 420.dp

/**
 * The one-line form: the run that is still moving, else the newest slot.
 *
 * Never a silently different one from the first line of the expanded list —
 * a collapsed strip naming a run the expanded strip puts third is a strip the
 * reader stops trusting.
 */
@Composable
private fun PeekCollapsed(slots: List<PeekSlot>, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val lead = slots.firstOrNull { it.settledAt == 0L } ?: slots.last()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .clickable(onClickLabel = "Live runs") { onToggle() }
            .padding(horizontal = MD.space4),
    ) {
        StatusGlyph(peekStatus(lead.run, lead.vanished), runningTint = colors.accentMark)
        Text(
            text = peekName(lead.run),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (slots.size > 1) {
            Text(
                text = "+${slots.size - 1}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (lead.settledAt == 0L) {
            Text(
                text = lead.run.counts.ratio,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = scheme.onSurfaceVariant,
            )
            ProgressMeter(lead.run.counts, modifier = Modifier.width(PeekMeterWidth))
        } else {
            Text(
                text = peekNote(lead.run, lead.vanished),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SeekerIcon(
            icon = R.drawable.ic_ui_chevron_up,
            contentDescription = null,
            tint = mutedIcon,
            size = IconSize.Marker,
        )
    }
}

/** Wide enough for eight cells and narrow enough to leave the name its room. */
private val PeekMeterWidth = 72.dp

/** The expanded strip's own header — the toggle back, and the live count. */
@Composable
private fun PeekExpandedHeader(liveCount: Int, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .clickable(onClickLabel = "Live runs") { onToggle() }
            .padding(horizontal = MD.space4),
    ) {
        StatusDot(color = colors.accentMark, pulsing = liveCount > 0)
        Text(
            text = "Live",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (liveCount > 0) {
            Text(
                text = "$liveCount",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = scheme.onSurfaceVariant,
            )
        }
        SeekerIcon(
            icon = R.drawable.ic_ui_chevron_down,
            contentDescription = null,
            tint = mutedIcon,
            size = IconSize.Marker,
        )
    }
}

/** One live run, in full: the head line, its meter, and what is moving in it. */
@Composable
private fun PeekRun(slot: PeekSlot) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val run = slot.run

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatusGlyph(
                peekStatus(run, slot.vanished),
                size = 10.dp,
                runningTint = colors.accentMark,
            )
            Text(
                text = peekName(run),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = run.counts.ratio,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = scheme.onSurfaceVariant,
            )
        }
        (run as? OrchRun.Workflow)?.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it.trim(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ProgressMeter(run.counts, modifier = Modifier.padding(vertical = 3.dp))
        CountsLabel(run.counts)

        when (run) {
            is OrchRun.Workflow -> for (phase in run.phases) {
                PeekPhase(phase.title, phase.detail, phase.counts, phase.members, phase.isPending)
            }

            is OrchRun.Swarm -> PeekMembers(run.members, PeekSwarmCap)
        }
    }
}

/**
 * A phase inside the strip.
 *
 * A pending phase still draws its empty track. Without it, a phase that tints
 * in place pushes everything below it down at the exact moment the reader is
 * scanning the column for who is stuck.
 */
@Composable
private fun PeekPhase(
    title: String,
    detail: String,
    counts: to.eyed.thragg.core.OrchCounts,
    members: List<Member>,
    pending: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MD.space2, top = MD.space1)
            .alpha(if (pending) 0.62f else 1f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SeekerIcon(
                icon = if (pending) R.drawable.ic_ui_circle else R.drawable.ic_ui_dot,
                contentDescription = if (pending) "queued" else null,
                tint = if (pending) mutedIcon else accentIcon,
                size = PhaseDotSize,
            )
            Text(
                text = title.ifBlank { "unphased" },
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = if (pending) "pending" else counts.ratio,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = scheme.onSurfaceVariant,
            )
        }
        ProgressMeter(counts, modifier = Modifier.padding(vertical = MD.space05))
        PeekMembers(members, PeekPhaseCap)
    }
}

/** Running members only, dots pulsing rather than spinning. */
@Composable
private fun PeekMembers(members: List<Member>, cap: Int) {
    val scheme = MaterialTheme.colorScheme
    for (member in peekMembers(members, cap)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 22.dp)
                .padding(start = MD.rowPadY),
        ) {
            val tint = memberTint(member.specId)
            // Eight braille spinners in one column is noise — the eye reads
            // the *motion* rather than the rows. One opacity cycle each, all
            // at the same period so they beat together rather than shimmering.
            // That argument now lives in `StatusDot`, which is the same dot.
            StatusDot(color = tint, pulsing = true)
            Text(
                text = truncateInstance(member.instance, PeekInstanceMax),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
            Text(
                text = member.liveDetail.replace('\n', '⏎'),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
    val hidden = peekHidden(members, cap)
    if (hidden > 0) {
        Text(
            text = "… $hidden more running",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 26.dp, top = MD.space05),
        )
    }
}

/** The one-line settled summary a run collapses to before it is released. */
@Composable
private fun PeekSettledLine(slot: PeekSlot) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
    ) {
        StatusGlyph(peekStatus(slot.run, slot.vanished), size = 10.dp)
        Text(
            text = peekName(slot.run),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = peekNote(slot.run, slot.vanished),
            style = MaterialTheme.typography.labelSmall,
            color = if (slot.run.counts.failed > 0) colors.removedInk else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The phase bullet: 9dp, so it reads as punctuation beside `labelMedium`. */
private val PhaseDotSize = 9.dp
