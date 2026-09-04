package to.eyed.thragg.ui.agent.spettro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import to.eyed.thragg.R
import to.eyed.thragg.core.Member
import to.eyed.thragg.core.OrchRun
import to.eyed.thragg.core.OrchStatus
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.seekerSpring

/**
 * An Ultra swarm (docs/SPETTRO.md, "Ultra swarm card").
 *
 * Ultra fans one instruction out over a list of items — one sub-agent per
 * item, each in its own git worktree — and the card is flat where the workflow
 * card is nested: no phases, one column of members and the items nobody has
 * been launched for yet.
 *
 * Three things make it different from a list of tool calls, and all three are
 * about a phone:
 *
 *  1. **The denominator is `items.size`, never the members launched.** Ultra
 *     ramps its launches (five at once, then one every 700 ms), so counting
 *     launches would make a 7/20 meter run *backwards* to 7/12 as it went.
 *  2. **Ghost cells.** The un-launched items are drawn, at half opacity, in
 *     the geometry their real row will take. A 20-item swarm spends its first
 *     fifteen seconds mostly un-launched, and without them it reads as a
 *     five-item swarm that keeps growing.
 *  3. **Order is running → failed → done.** What is still moving is the only
 *     part that can be acted on; once nothing moves, failures lead, because
 *     they are what the reader came back for. The fold already emits that
 *     order — this card never re-sorts.
 *
 * The container carries one amber identity and nothing else: every member is
 * already tinted by its spec, and a second colour per row makes the card
 * confetti. The amber is used twice and as two different values — the raw hue
 * washes the card, and `warnInk` writes the words, because the raw one
 * measures 1.64:1 on Ayu Light (docs/VISUAL.md, "INK AND WASH ARE SEPARATE
 * VALUES").
 *
 * See [WorkflowCard] for the [state] parameter, the LazyColumn `key` contract
 * and the header rule — all three apply here unchanged.
 */
@Composable
fun SwarmCard(
    run: OrchRun.Swarm,
    state: ShellState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val muted = scheme.onSurfaceVariant
    val amberWash = colors.ultraAmber
    val amberInk = colors.warnInk
    val live = run.status.isMoving

    // Collapsed by default, expanded when it failed — see [WorkflowCard].
    var open by rememberSaveable(run.tool.key) {
        mutableStateOf(run.status == OrchStatus.Failed)
    }
    var showAll by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    var showDone by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    var showQueued by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    var worktreeSheet by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    val openMembers = remember(run.tool.key) { mutableStateMapOf<String, Boolean>() }

    val split = splitMembers(run.members, live = live, cap = SwarmRowCap, showAll = showAll)
    val ghosts = if (showQueued) run.pending else run.pending.take(GhostCap)
    val cells = remember(run.members, run.counts.total) {
        cellStates(run.members, run.counts.total)
    }

    RunCardFrame(
        wash = amberWash,
        failed = run.status == OrchStatus.Failed,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MD.iconGap)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(onClickLabel = "Ultra swarm") { open = !open },
            ) {
                SeekerIcon(
                    icon = R.drawable.ic_ui_zap,
                    contentDescription = null,
                    tint = amberInk,
                    size = IconSize.Marker,
                )
                Text(
                    text = "Ultra swarm" + run.subagentType.takeIf { it.isNotBlank() }
                        ?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                if (run.isolation.contains("worktree", ignoreCase = true)) {
                    WorktreePill(amberInk) { worktreeSheet = true }
                }
                Chevron(open)
            }

            // The meter, the ratio and the counts are HEADER, not body: a
            // collapsed swarm must still say how much of it broke.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProgressMeter(
                    run.counts,
                    modifier = Modifier.width(if (open) 96.dp else 72.dp),
                )
                Text(
                    text = run.counts.ratio,
                    style = MaterialTheme.typography.labelMedium
                        .copy(fontFeatureSettings = TabularNums),
                    color = muted,
                )
                CountsLabel(run.counts, modifier = Modifier.weight(1f))
                if (run.pending.isNotEmpty()) {
                    Text(
                        text = "${run.pending.size} queued",
                        style = MaterialTheme.typography.labelMedium
                            .copy(fontFeatureSettings = TabularNums),
                        color = muted,
                    )
                }
            }

            CellStrip(cells)

            if (!open) return@RunCardFrame

            if (run.description.isNotBlank()) {
                Text(
                    text = run.description.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            CardRule(Modifier.padding(vertical = MD.space05))

            for (member in split.rows) {
                MemberRow(
                    member = member,
                    expanded = openMembers[member.tool.key] == true,
                    onToggle = { openMembers[member.tool.key] = openMembers[member.tool.key] != true },
                    trailingPill = mergeOutcome(member),
                )
            }
            if (split.hiddenCount > 0) {
                OverflowRow(overflowLabel(split.hidden)) { showAll = true }
            } else if (showAll && run.members.size > SwarmRowCap) {
                OverflowRow("show fewer") { showAll = false }
            }

            for (item in ghosts) {
                GhostRow(item)
            }
            val moreQueued = run.pending.size - ghosts.size
            if (moreQueued > 0) {
                // A phone cannot spend 400 dp on work that has not started.
                OverflowRow("+$moreQueued more queued") { showQueued = true }
            } else if (showQueued && run.pending.size > GhostCap) {
                OverflowRow("show fewer") { showQueued = false }
            }

            if (split.folded.isNotEmpty()) {
                Disclosure(
                    label = "${split.folded.size} done",
                    open = showDone,
                    onToggle = { showDone = !showDone },
                )
                if (showDone) {
                    for (member in split.folded) {
                        MemberRow(
                            member = member,
                            expanded = openMembers[member.tool.key] == true,
                            onToggle = {
                                openMembers[member.tool.key] = openMembers[member.tool.key] != true
                            },
                            trailingPill = mergeOutcome(member),
                        )
                    }
                }
            }
        }
    }

    if (worktreeSheet) {
        WorktreeSheet(state) { worktreeSheet = false }
    }
}

/** See [WorkflowCard]'s own chevron: one drawable, rotated on [seekerSpring]. */
@Composable
private fun Chevron(open: Boolean) {
    val angle by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = seekerSpring(),
        label = "swarm-card-chevron",
    )
    SeekerIcon(
        icon = R.drawable.ic_ui_chevron_down,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        size = IconSize.Marker,
        modifier = Modifier.rotate(angle),
    )
}

/** The `worktree` pill: an explanation one tap away, never a tooltip. */
@Composable
private fun WorktreePill(amber: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(MD.radiusXs))
            .background(amber.copy(alpha = 0.12f))
            .border(MD.hairline, amber.copy(alpha = 0.4f), RoundedCornerShape(MD.radiusXs))
            .clickable(onClickLabel = "What worktree isolation means") { onClick() }
            .padding(horizontal = MD.iconGap, vertical = MD.space05),
    ) {
        SeekerIcon(
            icon = R.drawable.ic_ui_git_fork,
            contentDescription = null,
            tint = amber,
            size = WorktreeMarkSize,
        )
        Text(
            text = "worktree",
            style = MaterialTheme.typography.labelSmall,
            color = amber,
            maxLines = 1,
        )
    }
}

/** 11dp: this pill is 16dp tall, so [IconSize.Marker] would burst it. */
private val WorktreeMarkSize = 11.dp

/**
 * The one-shot worktree sheet.
 *
 * Verbatim from the spec, because every sentence in it is a promise about the
 * user's repository: branches are created, merged and deleted without being
 * asked, and the one case where work is *kept* — a conflict — is the case a
 * user needs to have been told about before they meet it.
 */
@Composable
private fun WorktreeSheet(state: ShellState, onDismiss: () -> Unit) {
    SheetScaffold(state = state, onDismiss = onDismiss, title = "Worktree isolation") {
        Text(
            text = "Each member works in its own git worktree on its own branch, under " +
                ".spettro/worktrees/. Every branch is merged back into the main checkout " +
                "and deleted when the swarm finishes; a branch that conflicts is kept for " +
                "you to resolve.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space2),
        )
    }
}

/**
 * `conflict` / `preserved` — a member whose branch was not merged away.
 *
 * These two outcomes are the swarm's only unrecovered work, and they are
 * otherwise invisible: the member reads as finished, the card reads as
 * finished, and a branch sits in `.spettro/worktrees/` that nobody will ever
 * look for. Anything else the agent reports about the merge is normal
 * housekeeping and gets no pill.
 */
internal fun mergeOutcome(member: Member): String {
    if (member.status == OrchStatus.Running) return ""
    val json = runCatching { JSONObject(member.rawResult) }.getOrNull() ?: return ""
    val value = listOf("merge", "merge_status", "worktree", "branch_status")
        .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotEmpty() } }
        ?: return ""
    return when (value.lowercase()) {
        "conflict", "conflicted" -> "conflict"
        "preserved", "kept" -> "preserved"
        else -> ""
    }
}
