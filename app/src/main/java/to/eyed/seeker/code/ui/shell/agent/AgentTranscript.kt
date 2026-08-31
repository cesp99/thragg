package to.eyed.seeker.code.ui.shell.agent

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.FileDiff
import to.eyed.seeker.code.core.OrchRun
import to.eyed.seeker.code.core.ToolCallStatus
import to.eyed.seeker.code.core.ToolContent
import to.eyed.seeker.code.core.ToolKind
import to.eyed.seeker.code.core.TranscriptRow
import to.eyed.seeker.code.core.rememberAgentTerminal
import to.eyed.seeker.code.core.stripAnsi
import to.eyed.seeker.code.ui.agent.spettro.ActivationSurface
import to.eyed.seeker.code.ui.agent.spettro.MemberRow
import to.eyed.seeker.code.ui.agent.spettro.MonoSheet
import to.eyed.seeker.code.ui.agent.spettro.PlanRow
import to.eyed.seeker.code.ui.agent.spettro.SpettroSpinner
import to.eyed.seeker.code.ui.agent.spettro.SwarmCard
import to.eyed.seeker.code.ui.agent.spettro.WorkflowCard
import to.eyed.seeker.code.ui.agent.spettro.WorkflowScriptRow
import to.eyed.seeker.code.ui.agent.spettro.activationGlow
import to.eyed.seeker.code.ui.agent.spettro.rememberActivationBrush
import to.eyed.seeker.code.ui.common.MarkdownText
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.touchTarget

// ---------------------------------------------------------------------------
// The pure half — everything a row prints, decided without a Composable in
// sight so it can be pinned by a host test rather than by looking at a phone.
// ---------------------------------------------------------------------------

/**
 * One piece of an assistant turn: either a centred system pill or prose.
 *
 * Spettro writes its steering, goal, loop and compaction notices *into the
 * assistant message stream* — there is no channel for them — and on a 400 dp
 * column an unstyled `→ steering queued` reads as the agent talking to itself.
 * They are matched by PREFIX rather than equality because every one of them
 * carries a tail the CLI composes ("↻ goal iteration 3 of 10").
 */
internal data class SpokenBlock(val pill: String?, val text: String)

/**
 * The prefixes that are system pills rather than the agent's words
 * (docs/SPETTRO.md, "Conversation rows").
 *
 * Deliberately a short, closed list: anything that is not on it is prose, and
 * a false positive costs the reader a sentence of the answer while a false
 * negative costs them nothing but a plainer line.
 */
private val PILL_PREFIXES = listOf(
    "→ steering queued",
    "✔ steering delivered",
    "↻ goal iteration",
    "✅ goal complete",
    "⏸ loop waiting",
    "🗜 compacted",
    "🗜 compacting",
)

/**
 * The icon each pill is drawn with, and the glyph it replaces.
 *
 * Spettro writes these lines with a leading character of its own — "✔ steering
 * delivered", "🗜 compacting" — because a terminal has nothing else to mark a
 * line with. This app does. The character is stripped and a real icon drawn in
 * its place, so a status marker is not a font-dependent glyph rendering at
 * whatever weight the UI face happens to give it, and does not fall back to
 * tofu on a device whose font lacks the codepoint. Two of them (U+2705 and
 * U+1F5DC) are emoji, which render in colour and read as decoration in a
 * product that is not a chat app.
 *
 * [PILL_PREFIXES] still has to carry the characters verbatim: that is what
 * arrives on the wire, and the matcher matches the wire.
 */
private val PILL_MARKS: List<Pair<String, Int>> = listOf(
    "→ steering queued" to R.drawable.ic_ui_arrow_right,
    "✔ steering delivered" to R.drawable.ic_ui_check,
    "↻ goal iteration" to R.drawable.ic_ui_rotate_ccw,
    "✅ goal complete" to R.drawable.ic_ui_check,
    "⏸ loop waiting" to R.drawable.ic_ui_pause,
    "🗜 compacted" to R.drawable.ic_ui_compact,
    "🗜 compacting" to R.drawable.ic_ui_compact,
)

/**
 * The pill's icon and its words with the agent's leading glyph removed.
 *
 * Falls back to no icon and the line untouched: a pill we recognised by
 * prefix but have no mark for should still say what it says.
 */
internal fun pillMark(pill: String): Pair<Int?, String> {
    val hit = PILL_MARKS.firstOrNull { pill.startsWith(it.first, ignoreCase = true) }
        ?: return null to pill
    val glyph = hit.first.substringBefore(' ')
    return hit.second to pill.removePrefix(glyph).trim()
}

/** The pill [line] is, or null when it is the agent speaking. */
internal fun systemPill(line: String): String? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val prefix = PILL_PREFIXES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        ?: return null
    // The whole line, not the prefix: "↻ goal iteration 3 of 10" without its
    // numbers is a pill that says nothing.
    return if (prefix.length == trimmed.length) prefix else trimmed
}

/**
 * Split an answer into pills and prose, in order.
 *
 * Consecutive prose lines stay together so markdown still sees whole blocks —
 * splitting every line would break fenced code and lists — while each pill is
 * its own row, which is what lets it be centred.
 */
internal fun splitSpoken(markdown: String): List<SpokenBlock> {
    if (markdown.isEmpty()) return emptyList()
    if (PILL_PREFIXES.none { markdown.contains(it, ignoreCase = true) }) {
        return listOf(SpokenBlock(null, markdown))
    }
    val blocks = mutableListOf<SpokenBlock>()
    val prose = StringBuilder()
    fun flush() {
        val text = prose.toString().trim('\n')
        if (text.isNotBlank()) blocks += SpokenBlock(null, text)
        prose.setLength(0)
    }
    for (line in markdown.lines()) {
        val pill = systemPill(line)
        if (pill == null) {
            prose.append(line).append('\n')
        } else {
            flush()
            blocks += SpokenBlock(pill, line.trim())
        }
    }
    flush()
    return blocks
}

/** A stop reason worth a line, and whether it is an error. */
internal data class StopNotice(val text: String, val isError: Boolean)

/**
 * What the end of a turn is worth saying (docs/SPETTRO.md, "Conversation
 * rows").
 *
 * `end_turn` is silent, because a turn that ended normally has already said
 * everything it had to say; every other reason describes something the user
 * did not ask for and would otherwise read as the agent falling quiet.
 */
internal fun stopReasonNotice(stopReason: String?): StopNotice? = when (stopReason) {
    "refusal" -> StopNotice("The agent declined to continue.", isError = true)
    "max_tokens", "max_turn_requests" ->
        StopNotice("The turn hit a limit before finishing.", isError = false)
    "cancelled" -> StopNotice("Turn cancelled.", isError = false)
    else -> null
}

/**
 * The verb a tool call's row leads with (docs/SPETTRO.md, "Tool-call cards").
 *
 * By ACP *kind* first, because the kind is the field the protocol guarantees;
 * the tool's own name only decides the two cases the kind cannot: `ls` is a
 * listing rather than a search, and a `think` raised by a sub-agent is that
 * agent working rather than the model planning.
 */
internal fun toolVerb(call: AgentEntry.ToolCall): String = when (call.kind) {
    ToolKind.Execute -> "Terminal"
    ToolKind.Read -> "Read"
    ToolKind.Edit -> "Edit"
    ToolKind.Delete -> "Delete"
    ToolKind.Move -> "Move"
    ToolKind.Search -> if (call.toolName.equals("ls", ignoreCase = true)) "List" else "Search"
    ToolKind.Fetch -> "Fetch"
    ToolKind.Think -> if (call.toolName.startsWith("agent", ignoreCase = true)) "Agent" else "Plan"
    ToolKind.SwitchMode -> "Mode"
    ToolKind.Other -> call.toolName.replaceFirstChar { it.uppercase() }.ifEmpty { "Tool" }
}

/**
 * The one icon a 44 dp row can hold for each kind.
 *
 * This used to be a table of Unicode characters drawn in a `Text` — `⌗`, `◇`,
 * `✎`. Two of them (`⌗` U+2317, `⌦`-family marks) are outside what a phone's
 * UI face is obliged to carry, one of them was an emoji that arrived in
 * colour on a monochrome row, and all of them rendered at the font's optical
 * size rather than an icon's. The mapping is unchanged; only the medium is.
 */
@DrawableRes
internal fun toolIcon(call: AgentEntry.ToolCall): Int = when (call.kind) {
    ToolKind.Execute -> R.drawable.ic_ui_terminal
    ToolKind.Read -> R.drawable.ic_ui_diamond
    ToolKind.Edit -> R.drawable.ic_ui_pencil
    ToolKind.Delete -> R.drawable.ic_ui_trash
    ToolKind.Move -> R.drawable.ic_ui_arrow_right
    ToolKind.Search -> R.drawable.ic_ui_magnifying_glass
    ToolKind.Fetch -> R.drawable.ic_ui_download
    ToolKind.Think ->
        if (call.toolName.startsWith("agent", ignoreCase = true)) R.drawable.ic_ui_brain
        else R.drawable.ic_ui_circle_dashed
    ToolKind.SwitchMode -> R.drawable.ic_ui_swap
    ToolKind.Other -> R.drawable.ic_ui_circle
}

/**
 * Newlines collapsed, because a row is one line and a wrap makes it shiver.
 *
 * Trimmed *first*: a trailing newline is not content, and collapsing it would
 * leave every command in the transcript ending in a glyph that says the output
 * continues when it does not.
 */
internal fun oneLine(text: String): String =
    text.trim().replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎')

/**
 * A path down to its last [keep] components, with a leading ellipsis.
 *
 * Middle-ellipsising a path in a `Text` keeps the middle and eats the ends,
 * which on `programs/escrow/src/state.rs` loses the filename — the one part
 * that identifies it.
 */
internal fun shortenPath(path: String, keep: Int = 3): String {
    val parts = path.trim().split('/').filter { it.isNotEmpty() }
    if (parts.size <= keep) return path.trim()
    return "…/" + parts.takeLast(keep).joinToString("/")
}

/**
 * What the row says the call is *about* — derived from its arguments, never
 * shown as raw JSON and never read out of the title.
 *
 * Spettro truncates the title's inline JSON at 120 characters, so the title is
 * routinely invalid JSON; the arguments are the only trustworthy source
 * (docs/SPETTRO.md, § state model 3). The latest arguments are preferred over
 * the opening ones because for a long-running call the newest are the live
 * ones, and [AgentEntry.ToolCall.openArgs] falls back to them anyway.
 */
internal fun toolDetail(call: AgentEntry.ToolCall): String {
    val args = call.args ?: call.openArgs ?: return oneLine(call.title)
    fun str(key: String): String? = args.opt(key)?.let { value ->
        when (value) {
            is String -> value.takeIf { it.isNotBlank() }
            is org.json.JSONArray -> (0 until value.length())
                .mapNotNull { value.opt(it) as? String }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
            is org.json.JSONObject -> null
            else -> value.toString()
        }
    }
    val name = call.toolName.lowercase()
    if (name.startsWith("bash") || name.startsWith("shell") || name.startsWith("exec") ||
        call.kind == ToolKind.Execute
    ) {
        str("command")?.let { return oneLine(it) }
    }
    if (name.startsWith("agent")) {
        val who = str("subagent_type") ?: str("agent") ?: str("who")
        val task = str("task") ?: str("prompt") ?: str("description")
        if (who != null && task != null) return oneLine("$who: $task")
        if (who != null) return oneLine(who)
    }
    val path = str("path") ?: str("file") ?: str("file_path") ?: str("filename")
    val pattern = str("pattern") ?: str("regex") ?: str("query")
    if (path != null) {
        val short = shortenPath(path)
        return oneLine(if (pattern != null) "\"$pattern\" in $short" else short)
    }
    for (key in listOf("content", "task", "description", "pattern", "query", "regex", "url", "name")) {
        str(key)?.let { return oneLine(it) }
    }
    // Nothing named: the arguments themselves, compactly, rather than the
    // pretty-printed JSON blob the raw block already holds.
    val pairs = args.keys().asSequence()
        .filter { !it.startsWith("_") }
        .take(3)
        .mapNotNull { key -> str(key)?.let { "$key: $it" } }
        .toList()
    return if (pairs.isEmpty()) oneLine(call.title) else oneLine(pairs.joinToString("  "))
}

/** `+24 −6` for a call that carries diffs, or an empty string. */
internal fun diffStat(call: AgentEntry.ToolCall): String {
    val diffs = call.diffs
    if (diffs.isEmpty()) return ""
    val added = diffs.sumOf { it.added }
    val removed = diffs.sumOf { it.removed }
    if (added == 0 && removed == 0) return ""
    return "+$added −$removed"
}

/** How many changed lines a card unrolls before it summarises (docs/SPETTRO.md). */
internal const val INLINE_DIFF_LINES = 40

/** The changed lines to draw, removed first, and how many were left out. */
internal fun diffPreview(file: FileDiff, cap: Int = INLINE_DIFF_LINES): Pair<List<String>, Int> {
    val changed = file.hunks.flatMap { hunk -> hunk.lines.filter { it.kind != ' ' } }
    val ordered = changed.filter { it.kind == '-' } + changed.filter { it.kind == '+' }
    val shown = ordered.take(cap).map { "${it.kind}${it.text}" }
    return shown to (ordered.size - shown.size).coerceAtLeast(0)
}

// ---------------------------------------------------------------------------
// The rows
// ---------------------------------------------------------------------------

/**
 * The conversation, folded.
 *
 * The flat entry list is run through [to.eyed.seeker.code.core.foldOrchestration]
 * by the caller, so a workflow's two hundred sibling tool calls arrive here as
 * one run card. Keyed by [TranscriptRow.id], which is `turn:id` — tool-call ids
 * repeat in every turn (W-17) and keying on the id alone made a second turn's
 * `call-1` recycle the first turn's card.
 */
@Composable
internal fun AgentTranscript(
    shell: ShellState,
    rows: List<TranscriptRow>,
    listState: LazyListState,
    /** Which cards are open. Held by the caller: a LazyColumn drops item state. */
    expanded: MutableMap<String, Boolean>,
    onOpenPath: (String) -> Unit,
    onOpenPermission: (AgentEntry.ToolCall) -> Unit,
    onRestoreCheckpoint: (Int) -> Unit,
    /** The tail of the transcript: the ticker, an error, the stop notice. */
    tail: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        transcriptRows(rows, expanded, shell, onOpenPath, onOpenPermission, onRestoreCheckpoint)
        item(key = "tail") { tail() }
    }
}

/**
 * The `when (row)` itself, lifted out of the lambda so the body of the list is
 * readable and so the row types are exhaustive in one place.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.transcriptRows(
    rows: List<TranscriptRow>,
    expanded: MutableMap<String, Boolean>,
    shell: ShellState,
    onOpenPath: (String) -> Unit,
    onOpenPermission: (AgentEntry.ToolCall) -> Unit,
    onRestoreCheckpoint: (Int) -> Unit,
) {
    items(rows.size, key = { rows[it].id }) { index ->
        when (val row = rows[index]) {
            is TranscriptRow.Item -> when (val entry = row.entry) {
                is AgentEntry.User -> UserBubble(entry) { onRestoreCheckpoint(index) }
                is AgentEntry.Assistant -> AssistantRow(entry, expanded, row.id)
                is AgentEntry.ToolCall -> ToolCallRow(
                    shell = shell,
                    call = entry,
                    open = expanded[entry.key] ?: false,
                    onToggle = { expanded[entry.key] = !(expanded[entry.key] ?: false) },
                    onOpenPath = onOpenPath,
                    onOpenPermission = onOpenPermission,
                )
                is AgentEntry.CompletedPlan -> CompletedPlanCard(entry)
                AgentEntry.Unsupported -> UnsupportedRow()
            }

            is TranscriptRow.Agent -> Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                MemberRow(
                    member = row.member,
                    expanded = expanded[row.id] ?: false,
                    onToggle = { expanded[row.id] = !(expanded[row.id] ?: false) },
                )
            }

            is TranscriptRow.Run -> when (val run = row.run) {
                is OrchRun.Workflow -> WorkflowCard(
                    run = run,
                    state = shell,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                is OrchRun.Swarm -> SwarmCard(
                    run = run,
                    state = shell,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            is TranscriptRow.Script -> WorkflowScriptRow(
                script = row.script,
                state = shell,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * What the user said — the only row in the transcript with bubble framing.
 *
 * Right-aligned, at most 80 % of the column, accent-filled. Its text runs
 * through [activationGlow] so the phrase that armed orchestration stays lit
 * *after* sending: the composer lights `ultracode` while it is being typed,
 * and a bubble that dropped the glow would read as the mode having been
 * declined somewhere between the box and the wire.
 */
@Composable
private fun UserBubble(entry: AgentEntry.User, onRestore: () -> Unit) {
    val theme = LocalZedTheme.current
    val brush = rememberActivationBrush(ActivationSurface.BUBBLE)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    theme.color("element.selected", MaterialTheme.colorScheme.surfaceVariant)
                )
                .then(
                    if (entry.checkpoint) {
                        Modifier.clickable(onClickLabel = "Restore checkpoint", onClick = onRestore)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = activationGlow(entry.markdown.trim(), brush),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color(
                    if (entry.reverted) "text.muted" else "text",
                    MaterialTheme.colorScheme.onSurface,
                ),
            )
            if (entry.checkpoint) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    SeekerIcon(
                        icon = R.drawable.ic_ui_rotate_ccw,
                        contentDescription = null,
                        tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        size = IconSize.Marker,
                    )
                    Text(
                        text = "restore checkpoint",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

/**
 * The agent's turn: its reasoning, folded away, then what it actually said.
 *
 * The answer arrives *whole* rather than token by token — Spettro's internal
 * stream has draft-reset semantics ACP cannot express — so it is faded in over
 * ~120 ms instead of being typed out. A typewriter here produces a dead screen
 * followed by a jump (docs/SPETTRO.md, "Deliberately not reproduced").
 */
@Composable
private fun AssistantRow(
    entry: AgentEntry.Assistant,
    expanded: MutableMap<String, Boolean>,
    key: String,
) {
    val theme = LocalZedTheme.current
    val thoughts = entry.thoughts.trim()
    val spoken = entry.spoken
    val thinkingOpen = expanded["think:$key"] ?: false
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (thoughts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clickable(onClickLabel = "Reasoning") {
                        expanded["think:$key"] = !thinkingOpen
                    }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SeekerIcon(
                    icon = if (thinkingOpen) {
                        R.drawable.ic_ui_chevron_down
                    } else {
                        R.drawable.ic_ui_chevron_right
                    },
                    contentDescription = null,
                    tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    size = IconSize.Marker,
                )
                SeekerIcon(
                    icon = R.drawable.ic_ui_brain,
                    contentDescription = null,
                    tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    size = IconSize.Marker,
                )
                Text(
                    // "Thinking…" while it is the last thing on screen and
                    // nothing has been said yet; "Reasoning" once the answer
                    // has landed, because by then it is a record.
                    text = if (spoken.isBlank()) "Thinking…" else "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
            if (thinkingOpen) {
                Text(
                    text = thoughts,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
        for ((index, block) in splitSpoken(spoken).withIndex()) {
            val pill = block.pill
            if (pill != null) {
                SystemPill(pill)
            } else {
                // Faded in rather than typed out. `visibleState` seeded
                // false and flipped on composition is the only spelling that
                // actually animates the *first* frame — a bare
                // `visible = true` arrives already shown.
                val appearing = remember(key, index) {
                    MutableTransitionState(false).apply { targetState = true }
                }
                AnimatedVisibility(
                    visibleState = appearing,
                    enter = fadeIn(animationSpec = tween(durationMillis = 120)),
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        MarkdownText(block.text)
                    }
                }
            }
        }
    }
}

/** A centred system pill — steering, a goal iteration, a compaction. */
@Composable
private fun SystemPill(text: String) {
    val theme = LocalZedTheme.current
    val (icon, words) = pillMark(text)
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(theme.color("element.background", Color.Transparent))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            // Decorative: the words beside it already say what happened, so a
            // description here would make a screen reader say it twice.
            if (icon != null) SeekerIcon(icon, null, muted, size = IconSize.Marker)
            Text(
                text = words,
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One line nobody can render, and the rest of the conversation intact. */
@Composable
private fun UnsupportedRow() {
    val theme = LocalZedTheme.current
    Text(
        text = "· a row this build does not know how to draw ·",
        style = MaterialTheme.typography.labelSmall,
        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        textAlign = TextAlign.Center,
    )
}

/** A plan the agent finished, filed where the turn that owned it is. */
@Composable
private fun CompletedPlanCard(entry: AgentEntry.CompletedPlan) {
    val theme = LocalZedTheme.current
    if (entry.entries.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.color("element.background", Color.Transparent))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "plan · ${entry.entries.count { it.isDone }}/${entry.entries.size}",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        for (step in entry.entries) PlanRow(step)
    }
}

/**
 * One tool call: 44 dp, one line, and everything it produced behind a tap.
 *
 * The row is deliberately *not* a card. Ten reads and one pending edit drawn
 * as eleven identical boxes is the shape this replaced; framing is spent on
 * the two things worth stopping at — a call that is waiting for the user, and
 * a failure.
 */
@Composable
private fun ToolCallRow(
    shell: ShellState,
    call: AgentEntry.ToolCall,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenPermission: (AgentEntry.ToolCall) -> Unit,
) {
    val theme = LocalZedTheme.current
    val waiting = call.status == ToolCallStatus.WaitingForConfirmation
    val failed = call.status == ToolCallStatus.Failed
    val stat = diffStat(call)
    var sheet by remember(call.key) { mutableStateOf<MonoSheetRequest?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .then(
                if (waiting || failed) {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = 1.dp,
                            color = if (failed) {
                                theme.color("error", MaterialTheme.colorScheme.error)
                            } else {
                                theme.color("text.accent", MaterialTheme.colorScheme.primary)
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 8.dp)
                } else {
                    Modifier
                }
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clickable(onClickLabel = call.title) {
                    if (waiting) onOpenPermission(call) else onToggle()
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeekerIcon(
                icon = toolIcon(call),
                // The verb beside it is the label; this is the picture of it.
                contentDescription = null,
                tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                size = IconSize.Marker,
            )
            Text(
                text = toolVerb(call),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
            )
            Text(
                text = toolDetail(call),
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            if (stat.isNotEmpty()) {
                Text(
                    text = stat,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1,
                )
            }
            ToolStatusMark(call.status)
            SeekerIcon(
                icon = if (!waiting && open) {
                    R.drawable.ic_ui_chevron_up
                } else {
                    R.drawable.ic_ui_chevron_right
                },
                contentDescription = null,
                tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                size = IconSize.Marker,
            )
        }

        if (waiting) {
            Text(
                text = "Waiting for you — tap to answer",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (open) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (content in call.content) {
                    when (content) {
                        is ToolContent.Markdown -> MarkdownText(content.markdown)
                        is ToolContent.Diff -> InlineDiff(
                            file = content.file,
                            onOpenFull = { onOpenPath(content.file.path) },
                        )
                        is ToolContent.Terminal -> TerminalBlock(
                            terminalId = content.terminalId,
                            sealedState = content.sealed,
                            onOpenFull = { body ->
                                sheet = MonoSheetRequest(call.title, body)
                            },
                        )
                    }
                }
                // The opening arguments, not the latest ones: for a workflow
                // run the finish update replaces them with a summary, and what
                // the call *asked for* is the half worth reading (W-09).
                val raw = call.rawInputOpen ?: call.rawInput
                if (raw != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .touchTarget()
                            .clickable(onClickLabel = "Arguments") {
                                sheet = MonoSheetRequest("Arguments", raw)
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = "arguments",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color(
                                "text.muted",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        SeekerIcon(
                            icon = R.drawable.ic_ui_chevron_right,
                            contentDescription = null,
                            tint = theme.color(
                                "text.muted",
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            size = IconSize.Marker,
                        )
                    }
                }
            }
        }
    }

    sheet?.let { request ->
        MonoSheet(
            state = shell,
            title = request.title,
            body = request.body,
            onDismiss = { sheet = null },
        )
    }
}

/** A mono sheet somebody asked for, by title and body. */
private data class MonoSheetRequest(val title: String, val body: String)

/**
 * Where a call got to.
 *
 * A **small dot** for completed rather than a green check: the check is
 * reserved for orchestration members, and a transcript in which every read
 * carries one reads as a list of achievements (docs/SPETTRO.md).
 *
 * 8dp rather than [IconSize.Marker]'s 14: this is punctuation at the end of a
 * row, and a 14dp filled circle beside `labelSmall` is a bullet that shouts.
 */
@Composable
private fun ToolStatusMark(status: ToolCallStatus) {
    val theme = LocalZedTheme.current
    when (status) {
        ToolCallStatus.Pending, ToolCallStatus.InProgress ->
            SpettroSpinner(color = theme.color("text.accent", MaterialTheme.colorScheme.primary))

        ToolCallStatus.WaitingForConfirmation -> Text(
            text = "asks",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
        )

        ToolCallStatus.Completed -> SeekerIcon(
            icon = R.drawable.ic_ui_dot,
            contentDescription = "done",
            tint = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            size = CompletedDotSize,
        )

        ToolCallStatus.Failed -> SeekerIcon(
            icon = R.drawable.ic_ui_close,
            contentDescription = "failed",
            tint = theme.color("error", MaterialTheme.colorScheme.error),
            size = IconSize.Marker,
        )

        ToolCallStatus.Rejected, ToolCallStatus.Canceled -> Text(
            text = "—",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/**
 * A diff inside a card: removed lines then added ones, capped, never wrapped.
 *
 * Horizontal scroll rather than wrapping, because wrapped code is unreadable
 * code — and the cap is what stops a generated file burying the conversation
 * it belongs to.
 */
@Composable
private fun InlineDiff(file: FileDiff, onOpenFull: () -> Unit) {
    val theme = LocalZedTheme.current
    val (lines, more) = remember(file) { diffPreview(file) }
    val across = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.color("editor.background", Color.Transparent))
            .padding(6.dp),
    ) {
        Text(
            text = shortenPath(file.path),
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
        )
        Column(modifier = Modifier.fillMaxWidth().horizontalScroll(across)) {
            for (line in lines) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
                    maxLines = 1,
                    color = when (line.firstOrNull()) {
                        '+' -> theme.color("created", theme.color("text"))
                        '-' -> theme.color("deleted", theme.color("text"))
                        else -> theme.color("text")
                    },
                )
            }
        }
        if (more > 0) {
            Text(
                text = "… $more more lines",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Text(
            text = "View full diff",
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .touchTarget()
                .clickable(onClick = onOpenFull)
                .padding(top = 2.dp),
        )
    }
}

/**
 * A command the agent ran, tailed.
 *
 * The tail rather than the head: the end is where the error is, and the
 * engine's own cap is about memory rather than about reading.
 */
@Composable
private fun TerminalBlock(
    terminalId: String,
    sealedState: to.eyed.seeker.code.core.AgentTerminalState?,
    onOpenFull: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
    // A sealed terminal never changes again, so it is never polled — the
    // engine may well have evicted the live one by now.
    val live = rememberAgentTerminal(terminalId, enabled = sealedState == null)
    val terminal = sealedState ?: live
    val text = remember(terminal.revision, terminal.output) { stripAnsi(terminal.output) }
    val tail = remember(text) { text.lines().takeLast(TERMINAL_TAIL_LINES) }
    val across = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.color("terminal.background", theme.color("editor.background")))
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .horizontalScroll(across),
        ) {
            for (line in tail) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
                    color = theme.color("terminal.foreground", theme.color("text")),
                    maxLines = 1,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (terminal.running) {
                SpettroSpinner(color = theme.color("text.accent", MaterialTheme.colorScheme.primary))
            }
            Text(
                text = "Open full output",
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .touchTarget()
                    .clickable { onOpenFull(text) }
                    .padding(top = 2.dp),
            )
        }
    }
}

/** How many lines of a command's output the card shows before the sheet. */
private const val TERMINAL_TAIL_LINES = 40

/** See [ToolStatusMark]: the completed dot is punctuation, not a mark. */
private val CompletedDotSize = 8.dp
