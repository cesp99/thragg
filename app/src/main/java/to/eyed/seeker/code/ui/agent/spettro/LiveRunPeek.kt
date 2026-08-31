package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.seeker.code.core.Member
import to.eyed.seeker.code.core.OrchRun
import to.eyed.seeker.code.core.OrchStatus
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme

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
 *  - the last live snapshot is **held** for 1600 ms, unchanged;
 *  - it then collapses to a one-line settled summary;
 *  - and it is **released** at 7000 ms.
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

/** How long the full live snapshot survives the run that produced it. */
internal const val PeekHoldMs = 1600L

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
 * The peek: a 56 dp line, or a half-sheet's worth of live detail.
 *
 * Draws nothing at all when no run is live and none is still settling, so the
 * caller can place it unconditionally and let it take zero height — which is
 * also what the IME rule needs (docs/SPETTRO.md, "Screen shell": the plan
 * strip and the peek collapse to zero when the keyboard is up).
 */
@Composable
fun LiveRunPeek(
    runs: List<OrchRun>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val accent = theme.color("text.accent", MaterialTheme.colorScheme.primary)

    val latest by rememberUpdatedState(runs)
    var slots by remember { mutableStateOf(emptyList<PeekSlot>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // One loop rather than "recompute on new traffic" plus "and also on a
    // timer": the hold and the release happen when *no* traffic is arriving,
    // which is exactly the case a traffic-driven update never covers.
    LaunchedEffect(Unit) {
        while (true) {
            val stamp = System.currentTimeMillis()
            now = stamp
            slots = peekSlots(slots, latest, stamp)
            delay(if (slots.isEmpty() && latest.isEmpty()) PeekTickMs * 2 else PeekTickMs)
        }
    }

    if (slots.isEmpty()) return
    val liveCount = slots.count { it.settledAt == 0L }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface)),
    ) {
        CardRule()
        if (!expanded) {
            // Collapsed: the run that is still moving, else the newest slot —
            // never a silently different one from the first line of the
            // expanded sheet.
            val lead = slots.firstOrNull { it.settledAt == 0L } ?: slots.last()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(onClickLabel = "Live runs") { onToggle() }
                    .padding(horizontal = 12.dp),
            ) {
                StatusGlyph(peekStatus(lead.run, lead.vanished), runningTint = accent)
                Text(
                    text = "Live ·",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
                Text(
                    text = peekName(lead.run),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (slots.size > 1) {
                    Text(
                        text = "+${slots.size - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (lead.settledAt == 0L) {
                    Text(
                        text = lead.run.counts.ratio,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                    ProgressMeter(lead.run.counts, modifier = Modifier.width(72.dp))
                } else {
                    Text(
                        text = peekNote(lead.run, lead.vanished),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("▲", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            return@Column
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = "Live runs") { onToggle() }
                .padding(horizontal = 12.dp),
        ) {
            PulseDot(accent, live = liveCount > 0)
            Text(
                text = "Live",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = text,
            )
            Spacer(Modifier.weight(1f))
            if (liveCount > 0) {
                Text(
                    text = "$liveCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
            Text("▼", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        CardRule()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (slot in slots) {
                if (slot.isHeld(now)) PeekRun(slot) else PeekSettledLine(slot)
            }
        }
    }
}

/** One live run, in full: the head line, its meter, and what is moving in it. */
@Composable
private fun PeekRun(slot: PeekSlot) {
    val theme = LocalZedTheme.current
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val accent = theme.color("text.accent", MaterialTheme.colorScheme.primary)
    val run = slot.run

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatusGlyph(peekStatus(run, slot.vanished), size = 10.dp, runningTint = accent)
            Text(
                text = peekName(run),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = run.counts.ratio,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        (run as? OrchRun.Workflow)?.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it.trim(),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
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
 * A phase inside the peek.
 *
 * A pending phase still draws its empty track. Without it, a phase that tints
 * in place pushes everything below it down at the exact moment the reader is
 * scanning the column for who is stuck.
 */
@Composable
private fun PeekPhase(
    title: String,
    detail: String,
    counts: to.eyed.seeker.code.core.OrchCounts,
    members: List<Member>,
    pending: Boolean,
) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp)
            .alpha(if (pending) 0.62f else 1f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (pending) "○" else "●",
                style = MaterialTheme.typography.labelSmall,
                color = if (pending) muted else theme.color("text.accent", MaterialTheme.colorScheme.primary),
            )
            Text(
                text = title.ifBlank { "unphased" },
                style = MaterialTheme.typography.labelMedium,
                color = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = if (pending) "pending" else counts.ratio,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
        }
        ProgressMeter(counts, modifier = Modifier.padding(vertical = 2.dp))
        PeekMembers(members, PeekPhaseCap)
    }
}

/** Running members only, dots pulsing rather than spinning. */
@Composable
private fun PeekMembers(members: List<Member>, cap: Int) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    for (member in peekMembers(members, cap)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 22.dp)
                .padding(start = 10.dp),
        ) {
            val tint = memberTint(member.specId)
            PulseDot(tint, live = true)
            Text(
                text = truncateInstance(member.instance, PeekInstanceMax),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
            )
            Text(
                text = member.liveDetail.replace('\n', '⏎'),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
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
            color = muted,
            modifier = Modifier.padding(start = 26.dp, top = 2.dp),
        )
    }
}

/** The one-line settled summary a run collapses to before it is released. */
@Composable
private fun PeekSettledLine(slot: PeekSlot) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp),
    ) {
        StatusGlyph(peekStatus(slot.run, slot.vanished), size = 10.dp)
        Text(
            text = peekName(slot.run),
            style = MaterialTheme.typography.labelMedium,
            color = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = peekNote(slot.run, slot.vanished),
            style = MaterialTheme.typography.labelSmall,
            color = if (slot.run.counts.failed > 0) failColor() else muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A member's dot, pulsing.
 *
 * Eight braille spinners in one column is noise — the eye reads the *motion*
 * rather than the rows. One opacity cycle each, all at the same period so they
 * beat together rather than shimmering.
 */
@Composable
private fun PulseDot(color: Color, live: Boolean, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val reduce = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "peek.pulse")
    val wave by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "peek.dot",
    )
    val alpha = if (live && !reduce) wave else 1f
    Canvas(Modifier.size(size)) {
        drawCircle(color = color.copy(alpha = color.alpha * alpha), radius = this.size.minDimension / 4f)
    }
}
