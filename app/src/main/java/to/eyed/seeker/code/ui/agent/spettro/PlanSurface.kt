package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.AgentPlanEntry
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalReduceMotion
import to.eyed.seeker.code.ui.theme.LocalZedTheme

// ---------------------------------------------------------------------------
// What the strip says, as a pure function
// ---------------------------------------------------------------------------

/**
 * The one line the 32 dp strip has room for, plus the count.
 *
 * [headline] is the first `in_progress` task, falling back to the first
 * pending one. The fallback is the interesting half: between tasks — and for
 * the whole of a plan that has been published but not started — there is no
 * `in_progress` entry at all, and a strip that went blank in those gaps would
 * flicker several times a turn.
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
 * The glyph for a task's state.
 *
 * Text glyphs rather than vector icons on purpose: they inherit the row's
 * colour and baseline for free, and this strip is rebuilt wholesale on every
 * plan update, so three drawables per row would be three allocations per
 * update for a shape 14 dp across.
 */
internal fun statusGlyph(status: AgentPlanEntry.Status): String = when (status) {
    AgentPlanEntry.Status.Completed -> "✓"
    AgentPlanEntry.Status.InProgress -> "◉"
    AgentPlanEntry.Status.Pending -> "○"
}

// ---------------------------------------------------------------------------
// The strip
// ---------------------------------------------------------------------------

/**
 * The plan strip — 32 dp above the composer, and the only permanently visible
 * trace of the session's task graph.
 *
 * The plan matters more on a phone than on a desktop for a simple reason: the
 * transcript shows perhaps four rows at a time, so the answer to "what is it
 * doing, and how much is left" is otherwise several scrolls away.
 *
 * **Replace wholesale.** The agent republishes the entire list in dependency
 * order on every task mutation, so a task can move three positions between
 * updates without anything having "happened" to it. Animating that reordering
 * would draw a race between rows that are not racing; the only thing that
 * cross-fades is the status glyph, which is the only thing that changed.
 *
 * Workflow phases never appear here. They belong to the run card — the plan
 * channel is the session task graph, and merging the two would have a
 * six-phase workflow overwrite a two-task plan.
 */
@Composable
fun PlanStrip(
    plan: List<AgentPlanEntry>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = planSummary(plan) ?: return
    val theme = LocalZedTheme.current
    val headline = summary.headline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(StripHeight)
            .clickable(onClickLabel = "Plan, ${summary.counts}", onClick = onExpand)
            .padding(horizontal = 12.dp),
    ) {
        if (headline != null) {
            PlanGlyph(headline)
            Text(
                text = headline.content,
                style = MaterialTheme.typography.labelMedium,
                color = if (summary.isComplete) {
                    theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    theme.color("text", MaterialTheme.colorScheme.onSurface)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (headline.isBlocked) BlockedPill()
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = summary.counts,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
        )
        Text(
            text = "▲",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/** docs/UI.md's vertical budget gives the strip exactly this much. */
private val StripHeight = 32.dp

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
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val summary = planSummary(plan)
    SheetScaffold(state = state, onDismiss = onDismiss, title = "Plan") {
        if (summary == null) {
            Text(
                text = "No plan yet. Spettro publishes one when a request needs more " +
                    "than one step.",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            return@SheetScaffold
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                text = "In dependency order",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${summary.done} / ${summary.total}",
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
        }
        HorizontalDivider(color = theme.color("border.variant", Color.Transparent))
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
    val theme = LocalZedTheme.current
    val status = entry.statusOf
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                AgentPlanEntry.Status.InProgress ->
                    theme.color("text", MaterialTheme.colorScheme.onSurface)
                AgentPlanEntry.Status.Pending ->
                    theme.color("text", MaterialTheme.colorScheme.onSurface)
                AgentPlanEntry.Status.Completed ->
                    theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.weight(1f),
        )
        if (entry.isBlocked) BlockedPill()
    }
}

/**
 * The status glyph, and the only thing on a plan row that animates.
 *
 * Keyed on the task's text so that a *rewritten* task swaps its glyph without
 * a fade — the fade means "this task advanced", and it would be a lie on a row
 * that is a different task in the same position.
 */
@Composable
private fun PlanGlyph(entry: AgentPlanEntry) {
    val theme = LocalZedTheme.current
    val reduceMotion = LocalReduceMotion.current
    Box(modifier = Modifier.width(GlyphWidth), contentAlignment = Alignment.TopStart) {
        key(entry.content) {
            Crossfade(
                targetState = entry.statusOf,
                animationSpec = tween(durationMillis = if (reduceMotion) 0 else 180),
                label = "plan-status",
            ) { status ->
                Text(
                    text = statusGlyph(status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (status) {
                        AgentPlanEntry.Status.Completed ->
                            theme.color("created", theme.color("text.muted", Color.Gray))
                        AgentPlanEntry.Status.InProgress ->
                            theme.color("text.accent", MaterialTheme.colorScheme.primary)
                        AgentPlanEntry.Status.Pending ->
                            theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                    },
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
    val theme = LocalZedTheme.current
    Text(
        text = "BLOCKED",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color("element.background", Color.Transparent))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
