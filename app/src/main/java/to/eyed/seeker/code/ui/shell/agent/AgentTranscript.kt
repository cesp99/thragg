package to.eyed.seeker.code.ui.shell.agent

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import to.eyed.seeker.code.ui.agent.spettro.SwarmCard
import to.eyed.seeker.code.ui.agent.spettro.WorkflowCard
import to.eyed.seeker.code.ui.agent.spettro.WorkflowScriptRow
import to.eyed.seeker.code.ui.agent.spettro.activationGlow
import to.eyed.seeker.code.ui.agent.spettro.rememberActivationBrush
import to.eyed.seeker.code.ui.common.MarkdownText
import to.eyed.seeker.code.ui.components.DiffStatLabel
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SeekerSpinner
import to.eyed.seeker.code.ui.components.StatusDot
import to.eyed.seeker.code.ui.components.ZedCodeBlock
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.Durations
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall
import to.eyed.seeker.code.ui.theme.SeekerIcon
import to.eyed.seeker.code.ui.theme.animateSize
import to.eyed.seeker.code.ui.theme.effectSpec
import to.eyed.seeker.code.ui.theme.seekerSpring
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
 * The one icon a tool row can hold for each kind.
 *
 * This used to be a table of Unicode characters drawn in a `Text`. Two of them
 * are outside what a phone's UI face is obliged to carry, one was an emoji
 * that arrived in colour on a monochrome row, and all of them rendered at the
 * font's optical size rather than an icon's. The mapping is unchanged; only
 * the medium is.
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

/** One named string out of a call's arguments, or null when it is not there. */
private fun AgentEntry.ToolCall.arg(key: String): String? {
    val args = this.args ?: this.openArgs ?: return null
    return args.opt(key)?.let { value ->
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
}

/**
 * Whether this call is one sub-agent being launched.
 *
 * The kind alone cannot say it — ACP files a sub-agent launch under `Think`,
 * which is also where a plain reasoning step lands — so the tool's own name
 * decides, exactly as [toolVerb] and [toolIcon] already do for the same case.
 * A sub-agent gets a CARD rather than a row (docs/VISUAL.md, "Agent — a
 * tool-call row"), because it is a whole other agent's turn folded into one
 * line of this one.
 */
internal fun isSubAgent(call: AgentEntry.ToolCall): Boolean =
    call.kind == ToolKind.Think && call.toolName.startsWith("agent", ignoreCase = true)

/** Which sub-agent was launched, as the card's title. */
internal fun subAgentName(call: AgentEntry.ToolCall): String =
    call.arg("subagent_type") ?: call.arg("agent") ?: call.arg("who") ?: "agent"

/** What it was launched to do — two lines of it, never the raw arguments. */
internal fun subAgentTask(call: AgentEntry.ToolCall): String =
    call.arg("task") ?: call.arg("prompt") ?: call.arg("description") ?: ""

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
    fun str(key: String): String? = call.arg(key)
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

/**
 * The `+N`/`−N` a call's diffs add up to.
 *
 * A pair rather than the formatted string it used to be: the formatting, the
 * two inks, the tabular figures and the spoken description all live in
 * `DiffStatLabel` now, and a row that pre-rendered `"+24 −6"` could not hand
 * it either number.
 */
internal fun diffTotals(call: AgentEntry.ToolCall): Pair<Int, Int> {
    val diffs = call.diffs
    if (diffs.isEmpty()) return 0 to 0
    return diffs.sumOf { it.added } to diffs.sumOf { it.removed }
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

/**
 * A diff's lines as one string plus the spans that tint them.
 *
 * **The ground is painted, not just the ink**, and that is the change worth
 * writing down: the old version drew each line as coloured text on one flat
 * `editor.background` box, and a `+` line and a `-` line at 11sp differ only
 * by a hue that half the bundled themes place within two JND of each other.
 * A ten-percent wash behind the whole line is what makes a diff readable at a
 * glance rather than readable on inspection.
 *
 * Lines are padded to the widest one so every band is the same length. A
 * `SpanStyle` background covers exactly the glyphs it spans, so without the
 * padding the wash would end wherever the line's text does and a diff would
 * read as a ragged staircase rather than as a stack.
 *
 * The inks are the theme's own `created`/`deleted`, drawn RAW: this text lives
 * inside a [ZedCodeBlock], which is a Zed island, and Zed draws its diff
 * colours raw (docs/VISUAL.md, "THE SEAM"). The *outside* of the island — the
 * `+24 −6` on the row above — takes the solved `addedInk`/`removedInk`.
 */
internal fun diffSpans(
    lines: List<String>,
    added: Color,
    removed: Color,
): Pair<String, List<AnnotatedString.Range<SpanStyle>>> {
    if (lines.isEmpty()) return "" to emptyList()
    val width = lines.maxOf { it.length }
    val padded = lines.map { it.padEnd(width) }
    val ranges = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    var offset = 0
    for (line in padded) {
        val tint = when (line.firstOrNull()) {
            '+' -> added
            '-' -> removed
            else -> null
        }
        if (tint != null) {
            ranges += AnnotatedString.Range(
                SpanStyle(color = tint, background = tint.copy(alpha = 0.10f)),
                offset,
                offset + line.length,
            )
        }
        offset += line.length + 1
    }
    return padded.joinToString("\n") to ranges
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
 *
 * **16 dp is the gutter and it lives here, once.** Every row used to carry its
 * own `padding(horizontal = 12.dp)` or `16.dp`, which is why a tool row, a run
 * card and a user bubble sat at three different left edges down one column.
 * The list holds the gutter; a row holds nothing. The bottom pad is 24 dp so
 * the last row clears the composer rather than tucking under it.
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
        contentPadding = PaddingValues(
            start = MD.space4,
            top = MD.space4,
            end = MD.space4,
            bottom = MD.space6,
        ),
        verticalArrangement = Arrangement.spacedBy(MD.space2),
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

            is TranscriptRow.Agent -> MemberRow(
                member = row.member,
                expanded = expanded[row.id] ?: false,
                onToggle = { expanded[row.id] = !(expanded[row.id] ?: false) },
            )

            is TranscriptRow.Run -> when (val run = row.run) {
                is OrchRun.Workflow -> WorkflowCard(run = run, state = shell)
                is OrchRun.Swarm -> SwarmCard(run = run, state = shell)
            }

            is TranscriptRow.Script -> WorkflowScriptRow(script = row.script, state = shell)
        }
    }
}

/**
 * What the user said — the only row in the transcript with bubble framing.
 *
 * Right-aligned, at most 80 % of the column, on `secondaryContainer` — the one
 * M3 role that means "a quiet block of the user's own colour", and the role
 * `element.selected` maps to in the bridge, so this is the same fill it always
 * had with a name that says why.
 *
 * Its text runs through [activationGlow] so the phrase that armed
 * orchestration stays lit *after* sending: the composer lights `ultracode`
 * while it is being typed, and a bubble that dropped the glow would read as
 * the mode having been declined somewhere between the box and the wire.
 */
@Composable
private fun UserBubble(entry: AgentEntry.User, onRestore: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val brush = rememberActivationBrush(ActivationSurface.BUBBLE)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .clip(RoundedCornerShape(MD.radiusLg))
                .background(scheme.secondaryContainer)
                .then(
                    if (entry.checkpoint) {
                        Modifier.clickable(onClickLabel = "Restore checkpoint", onClick = onRestore)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = MD.space3, vertical = MD.space2),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = activationGlow(entry.markdown.trim(), brush),
                style = MaterialTheme.typography.bodyMedium,
                // A reverted turn is history the project no longer reflects,
                // so it is dimmed rather than recoloured: the same ink at 60%
                // stays legible and says "this is behind you".
                color = scheme.onSecondaryContainer.copy(alpha = if (entry.reverted) 0.6f else 1f),
            )
            if (entry.checkpoint) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MD.space1),
                    modifier = Modifier.padding(top = MD.space1),
                ) {
                    SeekerIcon(
                        icon = R.drawable.ic_ui_rotate_ccw,
                        contentDescription = null,
                        tint = scheme.onSecondaryContainer.copy(alpha = 0.7f),
                        size = IconSize.Marker,
                    )
                    Text(
                        text = "restore checkpoint",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSecondaryContainer.copy(alpha = 0.7f),
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
 * [Durations.BLOCK_FADE] instead of being typed out. A typewriter here produces
 * a dead screen followed by a jump (docs/SPETTRO.md, "Deliberately not
 * reproduced").
 */
@Composable
private fun AssistantRow(
    entry: AgentEntry.Assistant,
    expanded: MutableMap<String, Boolean>,
    key: String,
) {
    val scheme = MaterialTheme.colorScheme
    val thoughts = entry.thoughts.trim()
    val spoken = entry.spoken
    val thinkingOpen = expanded["think:$key"] ?: false
    Column(
        modifier = Modifier.fillMaxWidth().animateSize(),
        verticalArrangement = Arrangement.spacedBy(MD.iconGap),
    ) {
        if (thoughts.isNotEmpty()) {
            val turn by animateFloatAsState(
                targetValue = if (thinkingOpen) 90f else 0f,
                animationSpec = seekerSpring(),
                label = "reasoning-chevron",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(onClickLabel = "Reasoning") {
                        expanded["think:$key"] = !thinkingOpen
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            ) {
                SeekerIcon(
                    icon = R.drawable.ic_ui_chevron_right,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    size = IconSize.Marker,
                    modifier = Modifier.rotate(turn),
                )
                SeekerIcon(
                    icon = R.drawable.ic_ui_brain,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    size = IconSize.Marker,
                )
                Text(
                    // "Thinking…" while it is the last thing on screen and
                    // nothing has been said yet; "Reasoning" once the answer
                    // has landed, because by then it is a record.
                    text = if (spoken.isBlank()) "Thinking…" else "Reasoning",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (thinkingOpen) {
                Text(
                    // Reasoning is PROSE, so it takes the body scale and the
                    // UI face. It used to be drawn in the buffer family, which
                    // said "this is code" about a paragraph of English.
                    text = thoughts,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
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
                    enter = fadeIn(animationSpec = tween(durationMillis = Durations.BLOCK_FADE)),
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
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
    val scheme = MaterialTheme.colorScheme
    val (icon, words) = pillMark(text)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
            modifier = Modifier
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .padding(horizontal = MD.pillPadX, vertical = MD.space1),
        ) {
            // Decorative: the words beside it already say what happened, so a
            // description here would make a screen reader say it twice.
            if (icon != null) {
                SeekerIcon(icon, null, scheme.onSurfaceVariant, size = IconSize.Marker)
            }
            Text(
                text = words,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One line nobody can render, and the rest of the conversation intact. */
@Composable
private fun UnsupportedRow() {
    Text(
        text = "· a row this build does not know how to draw ·",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/** A plan the agent finished, filed where the turn that owned it is. */
@Composable
private fun CompletedPlanCard(entry: AgentEntry.CompletedPlan) {
    if (entry.entries.isEmpty()) return
    SeekerCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = MD.space3, vertical = MD.rowPadY),
            verticalArrangement = Arrangement.spacedBy(MD.space05),
        ) {
            Text(
                text = "plan · ${entry.entries.count { it.isDone }}/${entry.entries.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = MD.space1),
            )
            for (step in entry.entries) PlanRow(step)
        }
    }
}

/** The kind glyph on a tool row: 16dp, between [IconSize.Marker] and Inline. */
private val ToolGlyphSize = 16.dp

/**
 * One tool call: one line, and everything it produced behind a tap.
 *
 * **Collapsed, the row has no fill at all.** A long run of reads should be
 * visually quiet until one of them is opened; every row used to carry its own
 * ground, and a wall of them dominated the transcript it was supposed to
 * annotate. Opening one FADES the fill in — `surfaceContainer` at alpha 0
 * animating to `surfaceContainer`, so the same colour arrives rather than a
 * different composable swapping in and losing the row's layout mid-transition.
 *
 * Framing is spent on the two things worth stopping at, and Seeker's rule that
 * ten reads are ten unframed quiet rows survives intact: a FAILED call gets a
 * 1 dp `error` border, a WAITING one a 1 dp `primary` border and the line that
 * says what to do about it. A completed call gets a muted dot and nothing more
 * — deliberately not a green check, because a transcript of green checks reads
 * as a list of achievements (docs/VISUAL.md, "What we deliberately do not
 * copy").
 *
 * A SUB-AGENT launch is the third shape: it promotes to a tinted card, because
 * it is another agent's whole turn folded into one line of this one.
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
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val waiting = call.status == ToolCallStatus.WaitingForConfirmation
    val failed = call.status == ToolCallStatus.Failed
    val agent = isSubAgent(call)
    val (added, removed) = diffTotals(call)
    val raw = call.rawInputOpen ?: call.rawInput
    val hasBody = call.content.isNotEmpty() || raw != null
    var sheet by remember(call.key) { mutableStateOf<MonoSheetRequest?>(null) }

    val shape = RoundedCornerShape(if (agent) MD.radiusMd else MD.radiusSm)
    val ground by animateColorAsState(
        targetValue = when {
            // The sub-agent wash is identity rather than state: it is there
            // open or closed, at 7% dark / 5% light.
            agent -> colors.agentAccent.copy(alpha = if (colors.isDark) 0.07f else 0.05f)
            open -> scheme.surfaceContainer
            else -> scheme.surfaceContainer.copy(alpha = 0f)
        },
        // A fill is colour, so it takes the effects spring rather than the
        // spatial one: an alpha that overshoots reads as a flicker.
        animationSpec = effectSpec(),
        label = "tool-row-fill",
    )
    val edge = when {
        failed -> scheme.error
        waiting -> scheme.primary
        agent -> colors.agentAccent.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    val turn by animateFloatAsState(
        targetValue = if (open && !waiting) 90f else 0f,
        animationSpec = seekerSpring(),
        label = "tool-row-chevron",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateSize()
            .clip(shape)
            .background(ground)
            .then(
                if (edge == Color.Transparent) {
                    Modifier
                } else {
                    Modifier.border(MD.hairline, edge, shape)
                }
            ),
    ) {
        val header = Modifier
            .fillMaxWidth()
            .heightIn(min = ToolRowHeight)
            .then(
                // Clickable only when there is something behind the tap. A row
                // that lights up under a finger and then does nothing is worse
                // than one that does not respond at all.
                if (waiting) {
                    Modifier.clickable(onClickLabel = "Answer") { onOpenPermission(call) }
                } else if (hasBody) {
                    Modifier.clickable(onClickLabel = call.title) { onToggle() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = MD.toolPadX, vertical = MD.toolPadY)

        if (agent) {
            SubAgentHeader(
                call = call,
                modifier = header,
                showChevron = hasBody,
                chevronTurn = turn,
            )
        } else {
            Row(
                modifier = header,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MD.space2),
            ) {
                SeekerIcon(
                    icon = toolIcon(call),
                    // The verb beside it is the label; this is the picture of it.
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    size = ToolGlyphSize,
                )
                Text(
                    text = toolVerb(call),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    // A path or a command in the buffer's own face, so the
                    // file named here looks like the file two taps away.
                    text = toolDetail(call),
                    style = MonoSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f),
                )
                DiffStatLabel(added = added, removed = removed)
                ToolStatusMark(call.status)
                if (hasBody) {
                    SeekerIcon(
                        icon = R.drawable.ic_ui_chevron_right,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        size = IconSize.Marker,
                        modifier = Modifier.rotate(turn),
                    )
                }
            }
        }

        if (waiting) {
            Text(
                text = "Waiting for you — tap to answer",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                modifier = Modifier.padding(
                    start = MD.toolPadX,
                    end = MD.toolPadX,
                    bottom = MD.space2,
                ),
            )
        }

        if (open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Indented to the verb, not to the glyph: the body is what
                    // the row said, said at length.
                    .padding(start = MD.space6, end = MD.space2, bottom = MD.space2),
                verticalArrangement = Arrangement.spacedBy(MD.iconGap),
            ) {
                // Diffs first, then output. What an edit CHANGED is the answer;
                // what it printed while changing it is the working.
                for (content in call.content.filterIsInstance<ToolContent.Diff>()) {
                    InlineDiff(
                        file = content.file,
                        onOpenFull = { onOpenPath(content.file.path) },
                    )
                }
                for (content in call.content) {
                    when (content) {
                        is ToolContent.Markdown -> MarkdownText(content.markdown)
                        is ToolContent.Diff -> Unit // drawn above
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
                if (raw != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MD.space1),
                        modifier = Modifier
                            .touchTarget()
                            .clickable(onClickLabel = "Arguments") {
                                sheet = MonoSheetRequest("Arguments", raw)
                            }
                            .padding(vertical = MD.space1),
                    ) {
                        Text(
                            text = "arguments",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        SeekerIcon(
                            icon = R.drawable.ic_ui_chevron_right,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
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

/** A tool row is one line of text with room to be touched. */
private val ToolRowHeight = 44.dp

/**
 * A sub-agent launch, as a card header.
 *
 * The purple is the ONE fixed hue in the whole palette (`agentAccent`,
 * #AD7BF9): sub-agents are a vocabulary Spettro shares across the TUI, the
 * desktop client and this app, and deriving them from the user's editor theme
 * would break a cross-front-end agreement that a user relies on to recognise
 * one (docs/VISUAL.md, "THE HYBRID", BAND B).
 *
 * The name is drawn in `agentInk` — the same hue solved against a card's
 * ground — and the capsule behind "AGENT" in the raw hue at 18%, which is the
 * ink/wash split every card in this pass uses.
 */
@Composable
private fun SubAgentHeader(
    call: AgentEntry.ToolCall,
    modifier: Modifier,
    showChevron: Boolean,
    chevronTurn: Float,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val task = subAgentTask(call)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(MD.space05)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SeekerIcon(
                icon = R.drawable.ic_ui_agent,
                contentDescription = null,
                tint = colors.agentInk,
                size = ToolGlyphSize,
            )
            Text(
                text = subAgentName(call),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.agentInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "AGENT",
                // 9sp: a capsule, not a label. It is a category mark on a row
                // whose title is already 13sp, and at 11sp it competes with it.
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.agentInk,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.agentAccent.copy(alpha = 0.18f))
                    .padding(horizontal = MD.tagPadX, vertical = MD.tagPadY),
            )
            Spacer(Modifier.weight(1f))
            ToolStatusMark(call.status)
            if (showChevron) {
                SeekerIcon(
                    icon = R.drawable.ic_ui_chevron_right,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    size = IconSize.Marker,
                    modifier = Modifier.rotate(chevronTurn),
                )
            }
        }
        if (task.isNotBlank()) {
            Text(
                text = task.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A mono sheet somebody asked for, by title and body. */
private data class MonoSheetRequest(val title: String, val body: String)

/**
 * Where a call got to.
 *
 * A **small dot** for completed rather than a green check: the check is
 * reserved for orchestration members, and a transcript in which every read
 * carries one reads as a list of achievements (docs/SPETTRO.md). Seeker's
 * argument for the dot is better than spettro-android's reason for the check,
 * so the dot survives the redesign unchanged.
 *
 * 8dp rather than [IconSize.Marker]'s 14: this is punctuation at the end of a
 * row, and a 14dp filled circle beside 11sp text is a bullet that shouts.
 */
@Composable
private fun ToolStatusMark(status: ToolCallStatus) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    when (status) {
        ToolCallStatus.Pending, ToolCallStatus.InProgress ->
            SeekerSpinner(size = 12.dp, color = scheme.primary)

        ToolCallStatus.WaitingForConfirmation -> Text(
            text = "asks",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
        )

        ToolCallStatus.Completed -> StatusDot(
            color = scheme.onSurfaceVariant,
            contentDescription = "done",
        )

        ToolCallStatus.Failed -> SeekerIcon(
            icon = R.drawable.ic_ui_close,
            contentDescription = "failed",
            tint = colors.dangerInk,
            size = IconSize.Marker,
        )

        ToolCallStatus.Rejected, ToolCallStatus.Canceled -> Text(
            text = "—",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * A diff inside a card: removed lines then added ones, capped, never wrapped.
 *
 * The lines go in a [ZedCodeBlock] — the editor's ground, the editor's ink and
 * the user's buffer face, inside a Material `outlineVariant` edge — because
 * this used to draw in `FontFamily.Monospace` and the same file therefore
 * looked like two different files two taps apart (docs/VISUAL.md, "THE SEAM").
 * The per-line grounds come from [diffSpans].
 *
 * The header above the island is Material: `+24 −6` there is [DiffStatLabel]'s
 * SOLVED addedInk/removedInk, not the raw hues inside the block, because it
 * sits on the card rather than on the editor.
 */
@Composable
private fun InlineDiff(file: FileDiff, onOpenFull: () -> Unit) {
    val theme = LocalZedTheme.current
    val scheme = MaterialTheme.colorScheme
    val (lines, more) = remember(file) { diffPreview(file) }
    val addedInk = theme.color("created", theme.color("text"))
    val removedInk = theme.color("deleted", theme.color("text"))
    val (body, spans) = remember(lines, addedInk, removedInk) {
        diffSpans(lines, addedInk, removedInk)
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MD.space1),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = shortenPath(file.path),
                style = MonoSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            DiffStatLabel(added = file.added, removed = file.removed)
        }
        ZedCodeBlock(text = body, spans = spans, copyable = false)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (more > 0) {
                Text(
                    text = "… $more more lines",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Text(
                text = "View full diff",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier
                    .touchTarget()
                    .clickable(onClick = onOpenFull),
            )
        }
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
    val scheme = MaterialTheme.colorScheme
    // A sealed terminal never changes again, so it is never polled — the
    // engine may well have evicted the live one by now.
    val live = rememberAgentTerminal(terminalId, enabled = sealedState == null)
    val terminal = sealedState ?: live
    val text = remember(terminal.revision, terminal.output) { stripAnsi(terminal.output) }
    val tail = remember(text) { text.lines().takeLast(TERMINAL_TAIL_LINES).joinToString("\n") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MD.space1),
    ) {
        ZedCodeBlock(text = tail, copyable = false)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (terminal.running) SeekerSpinner(size = 12.dp, color = scheme.primary)
            Text(
                text = "Open full output",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier
                    .touchTarget()
                    .clickable { onOpenFull(text) },
            )
        }
    }
}

/**
 * How many lines of a command's output the card shows before the sheet.
 *
 * Fourteen rather than the forty this used to take, because forty was never
 * what it drew: the block carried a `heightIn(max = 240.dp)` that clipped it
 * at about thirteen lines of the buffer face and gave no sign it had. The
 * island has no arbitrary height cap, so the cap is spelled where it is true —
 * in the number of lines actually taken. "Open full output" still gets the
 * whole thing.
 */
private const val TERMINAL_TAIL_LINES = 14
