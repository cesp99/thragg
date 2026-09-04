package to.eyed.thragg.ui.agent.spettro

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentPlanEntry
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.SectionHeader
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.Durations
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalReduceMotion
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.mutedIcon

// ---------------------------------------------------------------------------
// What the strip says, as a pure function
// ---------------------------------------------------------------------------

/**
 * The one line the status strip has room for, plus the count.
 *
 * [headline] is the first `in_progress` task, falling back to the first
 * pending one. The fallback is the interesting half: between tasks — and for
 * the whole of a plan that has been published but not started — there is no
 * `in_progress` entry at all, and a strip that went blank in those gaps would
 * flicker several times a turn.
 *
 * The headline survives the move into [PlanProgress] even though the 36 dp
 * strip no longer prints it: it is what the unfold opens *on*, and it is the
 * sentence a screen reader is given for the collapsed control.
 */
internal data class PlanSummary(
    val headline: AgentPlanEntry?,
    val done: Int,
    val total: Int,
) {
    /** `3/7`, tabular, and the only number in the strip. */
    val counts: String get() = "$done/$total"

    /** Everything published is finished — worth saying, and worth not hiding. */
    val isComplete: Boolean get() = total > 0 && done == total
}

/**
 * Summarise a plan for the strip. Null for an empty plan, which is a real
 * state and not an absence: Spettro publishes `entries: []` deliberately when
 * the last task is deleted, and the strip's answer to that is to disappear.
 */
internal fun planSummary(plan: List<AgentPlanEntry>): PlanSummary? {
    if (plan.isEmpty()) return null
    val running = plan.firstOrNull { it.statusOf == AgentPlanEntry.Status.InProgress }
    val pending = plan.firstOrNull { it.statusOf == AgentPlanEntry.Status.Pending }
    val done = plan.count { it.statusOf == AgentPlanEntry.Status.Completed }
    return PlanSummary(
        // A finished plan keeps its last completed task on the strip rather
        // than showing an empty line above the composer.
        headline = running ?: pending ?: plan.lastOrNull(),
        done = done,
        total = plan.size,
    )
}

/**
 * The mark for a task's state, and the word behind it.
 *
 * This used to be a table of Unicode characters, argued for on the grounds
 * that a glyph inherits the row's colour and baseline for free and costs no
 * allocation on a strip that is rebuilt on every plan update. Both halves of
 * that were true and neither was the point: a `Text` glyph draws at the
 * *font's* optical size, so these three marks came out thinner and smaller
 * than every drawable beside them, and `◉` is not a codepoint a phone's UI
 * face is obliged to have. The cost was never real either — `painterResource`
 * caches the inflated drawable per resource id, so three ids is three cached
 * painters for the life of the process, not three allocations per update.
 *
 * The second half of the pair is what a screen reader says. The mark is the
 * only thing on the row that carries the state, so unlike most icons in this
 * app it is *not* decoration and does get a description.
 */
internal fun statusIcon(status: AgentPlanEntry.Status): Pair<Int, String> = when (status) {
    AgentPlanEntry.Status.Completed -> R.drawable.ic_ui_check to "done"
    AgentPlanEntry.Status.InProgress -> R.drawable.ic_ui_circle_dot to "in progress"
    AgentPlanEntry.Status.Pending -> R.drawable.ic_ui_circle to "to do"
}

// ---------------------------------------------------------------------------
// In the status strip
// ---------------------------------------------------------------------------

/**
 * `2/4 ▬▬▭▭` — the plan, folded into the 36 dp status strip.
 *
 * THIS REPLACES THE 32 dp `PlanStrip` BAND, and the replacement is the point
 * (docs/VISUAL.md, "Agent — the screen at rest"). The old strip spent a whole
 * pinned row of an 890 dp column printing one task's sentence, which is the
 * *least* durable thing the plan channel carries — the agent republishes the
 * entire list in dependency order on every mutation, so that sentence changes
 * several times a turn while the ratio barely moves. The ratio and the cells
 * are what the eye actually reads at a glance, they cost no extra height at
 * all beside the ticker and the usage readout, and the sentence is one tap
 * away in [PlanUnfold] rather than gone.
 *
 * Draws nothing for an empty plan, so the strip closes up around it.
 *
 * NO `touchTarget()` HERE, and it is deliberate: the spec pins the strip at
 * 36 dp, and `minimumInteractiveComponentSize()` reserves 48 dp of *layout*,
 * which would make the strip taller than the thing it is a strip of. This is
 * not an icon-only control — it is a labelled target ~36 dp tall and ~80 dp
 * wide, well clear of WCAG 2.5.8's 24 dp floor, which is the case that rule
 * carves out.
 */
@Composable
fun PlanProgress(
    plan: List<AgentPlanEntry>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = planSummary(plan) ?: return
    val colors = LocalThraggColors.current
    val spoken = buildString {
        append("Plan, ")
        append(summary.done)
        append(" of ")
        append(summary.total)
        append(" done")
        summary.headline?.let {
            append(", ")
            append(it.content)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
        modifier = modifier
            .clip(RoundedCornerShape(MD.radiusXs))
            .clickable(
                onClickLabel = if (expanded) "Hide the plan" else "Show the plan",
                onClick = onToggle,
            )
            .padding(horizontal = MD.space1, vertical = MD.space05)
            // One node for the three: read as three it announces a checkbox,
            // a fraction and an unlabelled graphic, none of which is a plan.
            // The action is re-declared inside the clear, because clearing is
            // what it says and a control that lost its own click is not an
            // improvement on one that announced itself badly.
            .clearAndSetSemantics {
                contentDescription = spoken
                onClick(label = if (expanded) "Hide the plan" else "Show the plan") {
                    onToggle()
                    true
                }
            },
    ) {
        ThraggIcon(
            icon = if (summary.isComplete) {
                R.drawable.ic_ui_checkbox_checked
            } else {
                R.drawable.ic_ui_checkbox
            },
            contentDescription = null,
            tint = if (summary.isComplete) colors.addedMark else mutedIcon,
            size = IconSize.Marker,
        )
        Text(
            text = summary.counts,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = TabularNums,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        PlanCells(done = summary.done, total = summary.total)
    }
}

/**
 * `▬▬▭▭` — the plan as cells rather than as a bar.
 *
 * Cells and not a continuous meter because a plan is countable: four tasks
 * with two done is two full cells, and a 50 %-wide bar would say the same
 * thing about a plan of two and a plan of forty. Capped at [PlanCellMax], at
 * which point the cells become proportional and the ratio beside them stays
 * exact — the number is the truth here and the cells are the glance.
 */
@Composable
private fun PlanCells(done: Int, total: Int, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val cells = total.coerceAtMost(PlanCellMax)
    val filled = if (total <= PlanCellMax) {
        done.coerceIn(0, cells)
    } else {
        // Round *down*: a cell that lit before its task finished would be the
        // one lie a progress readout must not tell.
        ((done.toFloat() / total) * cells).toInt().coerceIn(0, cells)
    }
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(PlanCellGap),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        repeat(cells) { index ->
            Box(
                modifier = Modifier
                    .size(width = PlanCellWidth, height = PlanCellHeight)
                    .clip(RoundedCornerShape(PlanCellHeight / 2))
                    .background(
                        if (index < filled) scheme.primary else scheme.outlineVariant,
                    ),
            )
        }
    }
}

/** Eight is where a row of cells stops being countable at a glance. */
private const val PlanCellMax = 8
private val PlanCellWidth = 6.dp
private val PlanCellHeight = 3.dp
private val PlanCellGap = 2.dp

/**
 * The plan, opened *inside* the status strip.
 *
 * The strip animates its own size around this ([Modifier.animateSize] on the
 * strip), so the tasks push the transcript down rather than covering it, and
 * the reader keeps the run ticker and the usage readout in view while they
 * read what is left to do. That is the difference between this and the sheet:
 * the sheet is for studying a thirty-task plan, this is for answering "what
 * is it on" without losing the conversation.
 *
 * Capped and scrollable, because an autonomous run publishes plans long
 * enough to swallow the screen.
 */
@Composable
fun PlanUnfold(plan: List<AgentPlanEntry>, modifier: Modifier = Modifier) {
    if (plan.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = PlanUnfoldMax)
            .verticalScroll(rememberScrollState())
            .padding(bottom = MD.space2),
    ) {
        // Keyed by position AND content, as the sheet is: content alone
        // repeats ("run the tests" twice in one plan is ordinary) and two
        // siblings under the same key is not a state Compose recovers from.
        plan.forEachIndexed { index, entry ->
            key(index, entry.content) { PlanRow(entry) }
        }
    }
}

/** Six 34 dp rows and a hint of a seventh — enough to read, not enough to hide. */
private val PlanUnfoldMax = 220.dp

// ---------------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------------

/**
 * The whole plan, half a screen of it.
 *
 * `LazyColumn` rather than a scrolling `Column` because a long autonomous run
 * publishes plans of thirty-odd tasks and the sheet is opened *while* they are
 * being rewritten — composing every row of every revision is measurable on
 * this device. Keyed by position and content together: content alone repeats
 * ("run the tests" twice in one plan is ordinary), and position alone would
 * make every row after an insertion look like a changed row.
 */
@Composable
fun PlanSheet(
    state: ShellState,
    plan: List<AgentPlanEntry>,
    /**
     * The connected agent's own name. `session/update`'s `plan` is ordinary
     * ACP, so the empty sentence must not name the agent we bundle.
     */
    agentName: String,
    onDismiss: () -> Unit,
) {
    val summary = planSummary(plan)
    SheetScaffold(state = state, onDismiss = onDismiss, title = "Plan") {
        if (summary == null) {
            Text(
                text = stringResource(R.string.agent_plan_empty, agentName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(MD.space4),
            )
            return@SheetScaffold
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MD.space4, vertical = MD.space1),
        ) {
            SectionHeader(text = "In dependency order", modifier = Modifier.weight(1f))
            Text(
                text = "${summary.done} / ${summary.total}",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = TabularNums,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HairlineDivider()
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(
                count = plan.size,
                key = { index -> "$index/${plan[index].content}" },
            ) { index ->
                PlanRow(plan[index])
            }
        }
    }
}

/**
 * One task.
 *
 * The in-progress row is the one the eye should land on, so it is the only
 * one at full text colour and weight; completed rows are deliberately quieter
 * than pending ones, because what is left to do is more useful than what is
 * done.
 */
@Composable
fun PlanRow(entry: AgentPlanEntry, modifier: Modifier = Modifier) {
    val status = entry.statusOf
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space4, vertical = MD.space2),
    ) {
        PlanGlyph(entry)
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (status == AgentPlanEntry.Status.InProgress) {
                FontWeight.Medium
            } else {
                FontWeight.Normal
            },
            color = when (status) {
                AgentPlanEntry.Status.InProgress -> MaterialTheme.colorScheme.onSurface
                AgentPlanEntry.Status.Pending -> MaterialTheme.colorScheme.onSurface
                AgentPlanEntry.Status.Completed -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (entry.isBlocked) BlockedPill()
    }
}

/**
 * The status mark, and the only thing on a plan row that animates.
 *
 * Keyed on the task's text so that a *rewritten* task swaps its mark without
 * a fade — the fade means "this task advanced", and it would be a lie on a row
 * that is a different task in the same position.
 */
@Composable
private fun PlanGlyph(entry: AgentPlanEntry) {
    val colors = LocalThraggColors.current
    val reduceMotion = LocalReduceMotion.current
    Box(modifier = Modifier.width(GlyphWidth), contentAlignment = Alignment.TopStart) {
        key(entry.content) {
            Crossfade(
                targetState = entry.statusOf,
                animationSpec = tween(
                    durationMillis = if (reduceMotion) 0 else Durations.BAND_IN,
                ),
                label = "plan-status",
            ) { status ->
                val (icon, said) = statusIcon(status)
                ThraggIcon(
                    icon = icon,
                    contentDescription = said,
                    // A completed task's tick is `created` solved to 3:1 on a
                    // card, not the raw key: raw `created` measures 2.11:1 on
                    // Ayu Light, which is a tick nobody sees. The old
                    // `Color.Gray` third fallback is gone with it — a mark
                    // that fell back to a hue no theme contains was the one
                    // colour on this row that could not be themed.
                    tint = when (status) {
                        AgentPlanEntry.Status.Completed -> colors.addedMark
                        AgentPlanEntry.Status.InProgress -> MaterialTheme.colorScheme.primary
                        AgentPlanEntry.Status.Pending -> mutedIcon
                    },
                    size = IconSize.Marker,
                )
            }
        }
    }
}

private val GlyphWidth = 16.dp

/**
 * `BLOCKED`, at the row's trailing edge.
 *
 * This is the whole reason W-12 lifts the literal `" (blocked)"` suffix out
 * of the task's text in Rust: the CLI can only express the state by writing
 * it into the sentence, and a plan row reading "Run the test suite (blocked)"
 * puts a status word where the task's own words should be.
 */
@Composable
private fun BlockedPill() {
    Text(
        text = "BLOCKED",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .height(BlockedPillHeight)
            .clip(RoundedCornerShape(MD.radiusXs))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = MD.tagPadX, vertical = MD.tagPadY),
    )
}

/** Tall enough to hold 11sp with 2dp either side and not a pixel more. */
private val BlockedPillHeight = 20.dp
