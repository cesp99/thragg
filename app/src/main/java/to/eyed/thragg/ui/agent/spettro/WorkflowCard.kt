package to.eyed.thragg.ui.agent.spettro

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.thragg.R
import to.eyed.thragg.core.OrchRun
import to.eyed.thragg.core.OrchStatus
import to.eyed.thragg.core.WorkflowPhase
import to.eyed.thragg.core.WorkflowScript
import to.eyed.thragg.ui.components.ZedCodeBlock
import to.eyed.thragg.ui.shell.SheetScaffold
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.RowChevron
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.seekerSpring

/**
 * A workflow run, live.
 *
 * This is the surface Spettro is bundled *for* (docs/SPETTRO.md, "Workflow run
 * card"). A message carrying `ultracode` arms the workflow tool; the run that
 * follows declares its phases before it has done any work, and this card draws
 * that declaration from t=0 — every phase as a row, pending ones included —
 * and fills them in as members land. A generic ACP client sees the same
 * traffic as an anonymous `workflow` tool call with a wall of `agent` calls
 * after it, and that is precisely the failure this card exists to avoid.
 *
 * Three rules from the spec do most of the work here:
 *
 *  - **A declared phase with no members is never hidden.** Knowing what is
 *    still coming is half the value of a run that announced a plan, and the
 *    pending rows are what keep the card from re-laying-out under the thumb
 *    every time a phase starts.
 *  - **A finished run drops successful DETAIL, never STRUCTURE.** The spine,
 *    the meters and every `3/3 done` survive; each failed member keeps its row
 *    and gains its reason; the successes fold behind one `4 done` disclosure.
 *  - **The meter, the counts and the cell strip live in the HEADER**
 *    (docs/VISUAL.md, "Agent — a workflow run card"), above the chevron's
 *    guard, so collapsing the card hides the run's detail and never its shape.
 *    A failure folded behind a chevron is a failure the card hid.
 *
 * [state] is here for one reason: the raw-tree sheet. Every modal surface in
 * this app goes through [SheetScaffold] so the shell's ordered back handler
 * can close the topmost one without knowing what it is (docs/UI.md,
 * "Navigation"), and that registration needs the shell's state object.
 *
 * **Caller contract:** give the row a stable `key` in the transcript's
 * `LazyColumn` (`TranscriptRow.id`). The card's open/closed flag is
 * `rememberSaveable`, which a LazyColumn restores per item key — without a key
 * a card scrolled off screen and back forgets that it was open, which during a
 * long run is every few seconds.
 */
@Composable
fun WorkflowCard(
    run: OrchRun.Workflow,
    state: ShellState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val live = run.status.isMoving

    // COLLAPSED is the phone's default, with one exception. A desktop has a
    // column beside the transcript; here the transcript IS the screen, and
    // three finished runs at 400 dp each are a wall. A FAILED run opens
    // expanded, because the reason a member died is the thing the reader came
    // back for and it must not need a tap.
    var open by rememberSaveable(run.tool.key) {
        mutableStateOf(run.status == OrchStatus.Failed)
    }
    var showLogs by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    var showScript by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    var showRaw by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    // Not saveable: which member rows were opened is cheap to redo and there
    // is no Bundle-safe container for it that does not cost more than it
    // saves.
    val openMembers = remember(run.tool.key) { mutableStateMapOf<String, Boolean>() }

    val cells = remember(run.phases, run.counts.total) {
        cellStates(run.phases.flatMap { it.members }, run.counts.total)
    }

    RunCardFrame(
        // The WASH, not an ink: the accent's raw hue behind the card, with
        // every piece of text inside it taking a solved value instead
        // (docs/VISUAL.md, "INK AND WASH ARE SEPARATE VALUES").
        wash = scheme.primary,
        failed = run.status == OrchStatus.Failed,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(MD.iconGap)) {
            // ---- header line 1: mark, name, badge, chevron ----------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(onClickLabel = run.name) { open = !open },
            ) {
                // The run's own mark. `git_graph` because what distinguishes a
                // workflow from the swarm card beside it is its *structure* —
                // phases with members hanging off them — and the swarm already
                // wears its own distinguishing property (the Ultra bolt). A
                // goal mark would not have told the two apart.
                SeekerIcon(
                    icon = R.drawable.ic_ui_git_graph,
                    contentDescription = null,
                    tint = muted,
                    size = IconSize.Marker,
                )
                Text(
                    text = run.name.ifBlank { "workflow" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(MD.space05))
                RunBadge(run.status)
                Spacer(Modifier.weight(1f))
                Chevron(open)
            }

            // ---- header line 2: meter, ratio, counts, elapsed --------------
            // Above the `!open` guard on purpose: see the class comment.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProgressMeter(
                    run.counts,
                    // Wider when the card is open, because there is room for
                    // it; the ratio beside it never moves either way.
                    modifier = Modifier.width(if (open) 96.dp else 72.dp),
                )
                Text(
                    text = run.counts.ratio,
                    style = MaterialTheme.typography.labelMedium
                        .copy(fontFeatureSettings = TabularNums),
                    color = muted,
                )
                CountsLabel(run.counts, modifier = Modifier.weight(1f))
                val elapsed = runElapsed(run.tool.key, live)
                if (elapsed.isNotEmpty()) {
                    Text(
                        text = elapsed,
                        style = MaterialTheme.typography.labelMedium
                            .copy(fontFeatureSettings = TabularNums),
                        color = muted,
                    )
                }
            }

            // ---- header line 3: the cell strip -----------------------------
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

            for (phase in run.phases) {
                PhaseBlock(
                    phase = phase,
                    live = live,
                    isOpen = { key -> openMembers[key] == true },
                    onToggle = { key -> openMembers[key] = openMembers[key] != true },
                )
            }

            if (run.logs.isNotEmpty()) {
                Disclosure(
                    label = "log (${run.logs.size})",
                    open = showLogs,
                    onToggle = { showLogs = !showLogs },
                    peek = if (showLogs) "" else run.logs.last(),
                    peekMono = true,
                )
                if (showLogs) {
                    // The island, not `FontFamily.Monospace` over Material ink:
                    // a run's log is the CLI's own output and reads as the
                    // buffer's face on the editor's ground or as nothing.
                    ZedCodeBlock(
                        text = run.logs.joinToString("\n"),
                        modifier = Modifier.padding(start = SpineWidth, bottom = MD.space1),
                    )
                }
            }

            run.script?.let { script ->
                Disclosure(
                    label = "script",
                    open = showScript,
                    onToggle = { showScript = !showScript },
                    peek = if (showScript) "" else script.savedAs,
                )
                if (showScript) ScriptBody(script)
            }

            if (run.rendered.isNotBlank()) {
                Disclosure(
                    label = "raw tree",
                    open = false,
                    onToggle = { showRaw = true },
                )
            }
        }
    }

    if (showRaw) {
        MonoSheet(
            state = state,
            title = "Raw tree",
            body = run.rendered,
            onDismiss = { showRaw = false },
        )
    }
}

/**
 * A workflow SCRIPT call that started no run.
 *
 * Deliberately quiet — a row, not a card. It produced nothing, and full card
 * framing would make a non-event look like the run it failed to become. It is
 * still drawn, because it is the only evidence a workflow was attempted at
 * all: drop it and the transcript says nothing happened.
 */
@Composable
fun WorkflowScriptRow(
    script: WorkflowScript,
    state: ShellState,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val muted = scheme.onSurfaceVariant
    var sheet by rememberSaveable(script.tool.key) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MemberRowHeight)
                .clickable(onClickLabel = "Workflow script") { sheet = true }
                .padding(horizontal = MD.space2),
        ) {
            SeekerIcon(
                icon = R.drawable.ic_ui_git_graph,
                contentDescription = null,
                tint = muted,
                size = IconSize.Marker,
            )
            Text(
                text = "Workflow" + script.savedAs.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            StatusGlyph(script.status)
            RowChevron(tint = muted)
        }
        if (script.error.isNotBlank()) {
            Text(
                text = script.error.lineSequence().first(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.dangerInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 26.dp, end = MD.space2, bottom = MD.space1),
            )
        }
    }

    if (sheet) {
        MonoSheet(
            state = state,
            title = "Workflow script",
            // The returned value first: it is what the script was written to
            // produce, and the source is only how it got there.
            body = listOf(script.returned, script.source)
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            onDismiss = { sheet = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

/**
 * The card's disclosure: down when it is closed, up when it is open.
 *
 * One chevron rotated rather than two drawables swapped, so it joins the
 * single [seekerSpring] every other expand in the app rides — and so
 * reduce-motion snaps it in one place rather than in twenty
 * (docs/VISUAL.md, "Foundations", MOTION).
 */
@Composable
private fun Chevron(open: Boolean) {
    val angle by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = seekerSpring(),
        label = "run-card-chevron",
    )
    SeekerIcon(
        icon = R.drawable.ic_ui_chevron_down,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        size = IconSize.Marker,
        modifier = Modifier.rotate(angle),
    )
}

/** `RUNNING` / `DONE` / `FAILED`, in the run's own colour. */
@Composable
private fun RunBadge(status: OrchStatus) {
    val scheme = MaterialTheme.colorScheme
    val danger = failColor()
    val label = when (status) {
        OrchStatus.Running -> "RUNNING"
        OrchStatus.Done -> "DONE"
        OrchStatus.Failed -> "FAILED"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space1),
    ) {
        StatusGlyph(status, size = 10.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when (status) {
                OrchStatus.Failed -> danger
                OrchStatus.Running -> scheme.primary
                OrchStatus.Done -> scheme.onSurfaceVariant
            },
        )
    }
}

/**
 * One phase: the spine, the header, its own meter, and its members.
 *
 * The header is drawn for a pending phase exactly as it is for a running one,
 * with `PENDING` where the meter would be and the whole row at 62% — the row
 * must not change *shape* when work arrives in it, or every phase transition
 * shoves the rest of the card down the screen while it is being read.
 */
@Composable
private fun PhaseBlock(
    phase: WorkflowPhase,
    live: Boolean,
    isOpen: (String) -> Boolean,
    onToggle: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val pending = phase.isPending
    var showAll by remember(phase.title) { mutableStateOf(false) }
    var showDone by remember(phase.title) { mutableStateOf(false) }

    val split = splitMembers(
        members = phase.members,
        live = live && phase.status.isMoving,
        cap = PhaseRowCap,
        showAll = showAll,
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space1),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .alpha(if (pending) 0.62f else 1f),
        ) {
            PhaseDot(phase.status, pending)
            Text(
                text = phase.title.ifBlank { "unphased" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (phase.detail.isNotBlank()) {
                Text(
                    text = phase.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (pending) {
                Text(
                    text = "PENDING",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            SpineRail(dashed = pending)
            Column(modifier = Modifier.weight(1f)) {
                if (pending) {
                    // The empty track still occupies the rail, so a phase
                    // tinting in place does not push the card around.
                    Spacer(Modifier.height(MD.space3))
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MD.space2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = MD.space05),
                    ) {
                        ProgressMeter(phase.counts, modifier = Modifier.width(72.dp))
                        CountsLabel(phase.counts, modifier = Modifier.weight(1f))
                    }
                }
                for (member in split.rows) {
                    MemberRow(
                        member = member,
                        expanded = isOpen(member.tool.key),
                        onToggle = { onToggle(member.tool.key) },
                    )
                }
                if (split.hiddenCount > 0) {
                    OverflowRow(overflowLabel(split.hidden)) { showAll = true }
                } else if (showAll && phase.members.size > PhaseRowCap) {
                    OverflowRow("show fewer") { showAll = false }
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
                                expanded = isOpen(member.tool.key),
                                onToggle = { onToggle(member.tool.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `… 5 more` / `show fewer`, at the member rows' own indent. */
@Composable
internal fun OverflowRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = MD.space1, vertical = MD.space2),
    )
}

/**
 * A script's returned value, its source and its error.
 *
 * The first two are code and go in the island; the error is a sentence and
 * stays Material text in the failure ink.
 */
@Composable
private fun ScriptBody(script: WorkflowScript) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SpineWidth, bottom = MD.space1),
        verticalArrangement = Arrangement.spacedBy(MD.space1),
    ) {
        if (script.returned.isNotBlank()) {
            ZedCodeBlock(text = script.returned, language = "returned", maxLines = 12)
        }
        if (script.source.isNotBlank()) {
            ZedCodeBlock(text = script.source, language = "source", maxLines = 12)
        }
        if (script.error.isNotBlank()) {
            Text(
                text = script.error,
                style = MaterialTheme.typography.labelMedium,
                color = LocalSeekerColors.current.dangerInk,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The mono sheet: the CLI's own tree, unwrapped.
 *
 * Horizontal scroll and no soft wrap are the whole point. An 80-column ASCII
 * tree wrapped into a 400 dp column is not a tree any more — the indentation
 * that carries the structure is exactly what wrapping destroys — so this
 * scrolls sideways like the terminal does. [ZedCodeBlock] owns both, plus the
 * thing this used to get wrong: it drew in `FontFamily.Monospace`, the
 * *system* mono, so the CLI's tree came out in a different face from the
 * editor two taps away (docs/VISUAL.md, "THE SEAM").
 */
@Composable
internal fun MonoSheet(
    state: ShellState,
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    SheetScaffold(state = state, onDismiss = onDismiss, title = title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MD.space4, vertical = MD.space2),
        ) {
            ZedCodeBlock(text = body)
        }
    }
}

/**
 * How long this card has watched the run.
 *
 * Deliberately *not* claimed as the run's age: ACP tool calls carry no
 * timestamps, so a run that was already going when the transcript was opened
 * has no start this app can honestly quote. Measuring from first composition
 * and showing nothing at all for a run that arrived settled is the version
 * that never lies; the alternative was a `0s` under a run that had been going
 * for a minute.
 */
@Composable
private fun runElapsed(key: String, live: Boolean): String {
    if (!live) return ""
    val started = rememberSaveable(key) { System.currentTimeMillis() }
    var now by remember(key) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(key, live) {
        while (live) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return elapsedLabel(now - started)
}
