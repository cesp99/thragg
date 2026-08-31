package to.eyed.seeker.code.ui.agent.spettro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentConfigOption
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.SelectionMark

/**
 * The selector behind a chip: one modal bottom sheet, one radio list.
 *
 * A sheet rather than the dropdown the old composer used, for one measurable
 * reason: a connected Anthropic plus an OpenRouter key is thirty-odd models,
 * and a popup anchored to a 32 dp chip at the bottom of a 890 dp screen puts
 * that list under the user's own hand. A modal sheet is thumb-reachable, it
 * scrolls, and it has room for each choice's description — which the
 * Permission list genuinely needs, since "Restricted" tells nobody what it
 * restricts.
 *
 * Everything rendered comes off the wire. The group headers are the agent's
 * providers, in the agent's order, including the synthetic `Active` group it
 * prepends when the current model's provider has been disconnected — which is
 * why `currentValue` always resolves and why nothing here invents a fallback
 * row. Tapping picks, dismisses, and hands the raw value up; the response's
 * full option set then replaces state wholesale, because one change can move
 * another (connecting a provider repopulates the model list).
 *
 * @param option the select to render. A boolean does not open a sheet — Ultra
 *   and its kin are inline chip toggles — so a boolean passed here lands on
 *   the empty state rather than drawing a two-row radio group.
 * @param ultraStored the agent's *stored* Ultra flag, needed only by the
 *   Permission footer: lowering to Ask first suspends Ultra rather than
 *   clearing it, and a user who is not told that reads the suspension as the
 *   setting having been lost.
 * @param onPick the chosen `value`, exactly as it arrived. The caller encodes
 *   it for `session/set_config_option`; this file never touches JSON.
 */
@Composable
fun ConfigSheet(
    state: ShellState,
    option: AgentConfigOption,
    /**
     * The connected agent's own name, for the empty case. `configOptions` is
     * ordinary ACP, so this sheet is reached by any `agent_servers` entry and
     * the sentence must not name the one we happen to ship.
     */
    agentName: String,
    ultraStored: Boolean = false,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val sections = sheetSections(option)
    val footers = permissionFooters(option, ultraStored)
    val listState = rememberLazyListState()

    // Whether the description gets a row of its own, which shifts every row
    // under it — hence [currentRowIndex]'s `leading` rather than a constant.
    val subtitle = option.description?.takeIf { it.isNotBlank() }

    // Open on the current value rather than at the top. Thirty models deep,
    // "which one am I on" is the first question the sheet has to answer, and
    // scrolling to find out is the interaction this sheet exists to remove.
    // `scrollToItem`, not `animateScrollToItem`: the sheet is still coming up,
    // and a list that is also travelling reads as a glitch.
    LaunchedEffect(option.currentValue, sections.size) {
        val index = currentRowIndex(sections, option.currentValue, if (subtitle == null) 0 else 1)
        if (index > 0) listState.scrollToItem(index)
    }

    SheetScaffoldHost(
        state = state,
        onDismiss = onDismiss,
        title = option.name,
        footers = footers,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (subtitle != null) {
                item(key = "subtitle") {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (sections.isEmpty()) {
                item(key = "empty") {
                    Text(
                        // Real, and not rare: a model list with nothing
                        // connected arrives empty, and so does any option the
                        // agent published before it had values for it.
                        text = stringResource(R.string.agent_config_no_choices, agentName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // Keys are positional, not value-derived, and that is deliberate.
            // Two things push that way at once: `config_option_update` is a
            // full replacement so there is no continuity for a stable key to
            // preserve, and the values are wire data — a provider that
            // published the same model twice, or two groups with the same
            // name, is a duplicate key, and a duplicate key is a LazyColumn
            // that throws rather than a list that looks slightly wrong.
            sections.forEachIndexed { index, section ->
                if (section.header != null) {
                    stickyHeaderCompat(key = "h$index") { GroupHeader(section.header) }
                }
                itemsIndexed(
                    items = section.choices,
                    key = { row, _ -> "$index:$row" },
                ) { _, choice ->
                    ChoiceRow(
                        choice = choice,
                        selected = choice.value == option.currentValue,
                        onClick = {
                            onPick(choice.value)
                            onDismiss()
                        },
                    )
                }
            }
            item(key = "tail") { Spacer(Modifier.height(12.dp)) }
        }
    }
}

/**
 * The scaffold call, kept separate so the two footer sentences are pinned
 * under the list rather than scrolling away with it — they are the reason
 * someone opened the Permission sheet in the first place.
 */
@Composable
private fun SheetScaffoldHost(
    state: ShellState,
    onDismiss: () -> Unit,
    title: String,
    footers: List<String>,
    content: @Composable () -> Unit,
) {
    val theme = LocalZedTheme.current
    SheetScaffold(
        state = state,
        onDismiss = onDismiss,
        title = title,
        actions = if (footers.isEmpty()) {
            null
        } else {
            {
                Column {
                    for (line in footers) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
    ) {
        content()
    }
}

/**
 * `stickyHeader` behind one name.
 *
 * Foundation has moved this signature twice and it is still opt-in; wrapping
 * it once means a third move is a one-line fix rather than a fix per group.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.stickyHeaderCompat(key: Any, content: @Composable () -> Unit) {
    stickyHeader(key = key) { content() }
}

/** A provider band that stays put while its models scroll under it. */
@Composable
private fun GroupHeader(name: String) {
    val theme = LocalZedTheme.current
    Text(
        text = name.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * One 56 dp choice.
 *
 * `heightIn(min = …)` rather than `height(…)`: the Permission descriptions run
 * to two lines on a 400 dp column, and a fixed row would clip the half of
 * "prompt for sensitive ones" that says which ones.
 */
@Composable
private fun ChoiceRow(
    choice: AgentConfigOption.Choice,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .clickable(onClickLabel = "Select ${choice.name}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // The shared [SelectionMark], in a fixed 32dp slot so every name in
        // the sheet starts at the same x. This was `(•)` / `( )` typed as
        // text: two parentheses and a middle dot at bodyMedium, which on the
        // Seeker's 480dpi panel sat low against its own label and read as
        // punctuation rather than as the control it is. Radio, not checkbox —
        // picking a config choice replaces the answer, never adds to it.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.width(32.dp),
        ) {
            SelectionMark(
                selected = selected,
                multi = false,
                tint = if (selected) {
                    theme.color("text.accent", MaterialTheme.colorScheme.primary)
                } else {
                    theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = choice.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            )
            choice.description?.takeIf { it.isNotBlank() }?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// The pure half — everything below is tested in ConfigSheetsTest
// ---------------------------------------------------------------------------

/** A group header and the choices under it; `header == null` for a flat list. */
internal data class SheetSection(
    val header: String?,
    val choices: List<AgentConfigOption.Choice>,
)

/**
 * The list to draw, grouped or flat, with internal roles filtered.
 *
 * Grouped-ness is decided upstream (W-13: the list is grouped when its FIRST
 * element carries nested options), so this only has to pick a branch. Empty
 * groups are dropped rather than drawn as a header with nothing under it — a
 * provider that is connected but has published no models yet produces exactly
 * that.
 */
internal fun sheetSections(option: AgentConfigOption): List<SheetSection> {
    val sections = if (option.isGrouped) {
        option.groups.map { SheetSection(it.name, visibleChoices(option, it.options)) }
    } else {
        listOf(SheetSection(null, visibleChoices(option, option.flat)))
    }
    return sections.filter { it.choices.isNotEmpty() }
}

/**
 * Roles Spettro drives itself, which a person must never pick.
 *
 * `worker` and `subagent` are what a fan-out puts its children into; a
 * conversation switched into one by hand is a top-level agent pretending to
 * be somebody's child, and it answers accordingly. They are hidden here
 * rather than assumed absent because `spettro.agents.toml` is a file the user
 * edits, and this is the one place the phone knows the difference.
 *
 * The exclusion is *never* applied to the current value. Hiding the state you
 * are actually in is the failure mode of every "clean up the list" rule: the
 * sheet would then show no selection at all, which reads as a bug rather than
 * as a policy.
 */
private val INTERNAL_MODE_ROLES = setOf("worker", "subagent")

private fun visibleChoices(
    option: AgentConfigOption,
    choices: List<AgentConfigOption.Choice>,
): List<AgentConfigOption.Choice> =
    if (option.category != "mode") {
        choices
    } else {
        choices.filter { it.value !in INTERNAL_MODE_ROLES || it.value == option.currentValue }
    }

/**
 * Where the current value sits in the flattened item list, so the sheet can
 * open on it.
 *
 * Every group header is an item of its own and the subtitle — present only
 * when the agent described the option — is another, so the arithmetic is here
 * rather than inline: an off-by-one opens the sheet one model above the one
 * you are on, which looks exactly like the right answer.
 *
 * @param leading items before the first section; 1 with a subtitle, 0 without.
 */
internal fun currentRowIndex(
    sections: List<SheetSection>,
    currentValue: String?,
    leading: Int = 1,
): Int {
    if (currentValue == null) return 0
    var index = leading
    for (section in sections) {
        if (section.header != null) index++
        for (choice in section.choices) {
            if (choice.value == currentValue) return index
            index++
        }
    }
    return 0
}

/** Spettro's lowest permission level, and the one Ultra is gated behind. */
private const val ASK_FIRST = "ask-first"

/**
 * The two sentences the Permission sheet owes the user, and only that sheet.
 *
 * Both exist because the *consequence* of a permission level is not local to
 * the permission list. Raising it turns Ultra and workflows from refused into
 * available; lowering it does not clear a stored Ultra, it suspends it, and
 * the difference shows up later as a chip that reads ON while nothing fans
 * out. Neither fact is anywhere else on the screen.
 *
 * They are mutually exclusive by construction: the first fires only at
 * `ask-first`, the second only above it.
 */
internal fun permissionFooters(option: AgentConfigOption, ultraStored: Boolean): List<String> {
    if (option.id != "permission") return emptyList()
    val current = option.currentValue
    return when {
        current == ASK_FIRST -> listOf(RAISE_UNLOCKS)
        // Only worth saying when there is a level to lower *to*; the list is
        // the project's, not this build's.
        ultraStored && option.choices.any { it.value == ASK_FIRST } -> listOf(LOWER_SUSPENDS)
        else -> emptyList()
    }
}

internal const val RAISE_UNLOCKS = "Restricted or YOLO also lets Ultra and workflows run."

internal const val LOWER_SUSPENDS =
    "Ultra stays on but is suspended until you raise this again."

/** SPETTRO.md's 56 dp selector row. */
private val RowHeight = 56.dp
