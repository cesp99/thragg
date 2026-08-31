package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import to.eyed.seeker.code.core.OrchRun
import to.eyed.seeker.code.core.OrchStatus
import to.eyed.seeker.code.core.WorkflowPhase
import to.eyed.seeker.code.core.WorkflowScript
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme

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
 * Two rules from the spec do most of the work here:
 *
 *  - **A declared phase with no members is never hidden.** Knowing what is
 *    still coming is half the value of a run that announced a plan, and the
 *    pending rows are what keep the card from re-laying-out under the thumb
 *    every time a phase starts.
 *  - **A finished run drops successful DETAIL, never STRUCTURE.** The spine,
 *    the meters and every `3/3 done` survive; each failed member keeps its row
 *    and gains its reason; the successes fold behind one `4 done` disclosure.
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
    val theme = LocalZedTheme.current
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val border = theme.color("border", Color.Transparent)
    val live = run.status.isMoving

    // Seeded open while the run is moving, closed once it is over — the same
    // "seeded rather than closed" rule the tool-call cards already use. A run
    // in flight is the one thing on the screen worth its height; a scrollback
    // of three finished runs, each 400 dp tall, is a wall. The user's own
    // toggle wins from then on, which is what the flag remembers.
    var open by rememberSaveable(run.tool.key) { mutableStateOf(live) }
    var showLogs by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    var showScript by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    var showRaw by rememberSaveable(run.tool.key) { mutableStateOf(false) }
    // Not saveable: which member rows were opened is cheap to redo and there
    // is no Bundle-safe container for it that does not cost more than it
    // saves.
    val openMembers = remember(run.tool.key) { mutableStateMapOf<String, Boolean>() }

    RunCardFrame(
        accent = border,
        failed = run.status == OrchStatus.Failed,
        modifier = modifier,
        fillAmount = 0.02f,
        borderAmount = 1f,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ---- header line 1: mark, name, badge, chevron ----------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(onClickLabel = run.name) { open = !open },
            ) {
                Text("▣", style = MaterialTheme.typography.labelMedium, color = muted)
                Text(
                    text = run.name.ifBlank { "workflow" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(2.dp))
                if (open) {
                    RunBadge(run.status)
                    Spacer(Modifier.weight(1f))
                } else {
                    // Collapsed, the summary *is* the card: `12 agents · 1
                    // failed`, in the run's own words.
                    Text(
                        text = run.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (run.counts.failed > 0) failColor() else muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    // ⌄ open, ⌃ tucked away — the state, not the gesture, which is
                    // the vocabulary every other card in this app already uses.
                    text = if (open) "⌄" else "⌃",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }

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

            // ---- header line 2: meter, ratio, elapsed ----------------------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProgressMeter(run.counts, modifier = Modifier.weight(1f))
                Text(
                    text = run.counts.ratio,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
                val elapsed = runElapsed(run.tool.key, live)
                if (elapsed.isNotEmpty()) {
                    Text(
                        text = elapsed,
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
            // The counts string lives only here: it does not fit beside a
            // meter at 400 dp, and the ratio above already answers "how far".
            CountsLabel(run.counts)

            CardRule(Modifier.padding(vertical = 2.dp))

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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = SpineWidth, bottom = 4.dp),
                    ) {
                        for (line in run.logs) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = muted,
                            )
                        }
                    }
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
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    var sheet by rememberSaveable(script.tool.key) { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MemberRowHeight)
                .clickable(onClickLabel = "Workflow script") { sheet = true }
                .padding(horizontal = 8.dp),
        ) {
            Text("▣", style = MaterialTheme.typography.labelMedium, color = muted)
            Text(
                text = "Workflow" + script.savedAs.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            StatusGlyph(script.status)
            Text("›", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        if (script.error.isNotBlank()) {
            Text(
                text = script.error.lineSequence().first(),
                style = MaterialTheme.typography.labelSmall,
                color = mix(muted, failColor(), 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 26.dp, end = 8.dp, bottom = 4.dp),
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

/** `● RUNNING` / `✓ DONE` / `✗ FAILED`, in the run's own colour. */
@Composable
private fun RunBadge(status: OrchStatus) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val label = when (status) {
        OrchStatus.Running -> "RUNNING"
        OrchStatus.Done -> "DONE"
        OrchStatus.Failed -> "FAILED"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatusGlyph(status, size = 10.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = when (status) {
                OrchStatus.Failed -> failColor()
                OrchStatus.Running -> theme.color("text.accent", MaterialTheme.colorScheme.primary)
                OrchStatus.Done -> muted
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
    val theme = LocalZedTheme.current
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
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
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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
                color = text,
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
                    Spacer(Modifier.height(12.dp))
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clickable(onClickLabel = label) { onClick() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun ScriptBody(script: WorkflowScript) {
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SpineWidth, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (script.returned.isNotBlank()) {
            Text(
                text = script.returned,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (script.source.isNotBlank()) {
            Text(
                text = script.source,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = muted,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (script.error.isNotBlank()) {
            Text(
                text = script.error,
                style = MaterialTheme.typography.labelSmall,
                color = mix(muted, failColor(), 0.75f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The mono sheet: the CLI's own tree, unwrapped.
 *
 * Horizontal scroll and `softWrap = false` are the whole point. An 80-column
 * ASCII tree wrapped into a 400 dp column is not a tree any more — the
 * indentation that carries the structure is exactly what wrapping destroys —
 * so this scrolls sideways like the terminal does.
 */
@Composable
internal fun MonoSheet(
    state: ShellState,
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    SheetScaffold(state = state, onDismiss = onDismiss, title = title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = theme.color("editor.foreground", MaterialTheme.colorScheme.onSurface),
                softWrap = false,
            )
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
