package to.eyed.seeker.code.ui.shell.agent

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import org.json.JSONObject
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentConfigOption
import to.eyed.seeker.code.core.AgentSessionState
import to.eyed.seeker.code.core.SpettroToolbar
import to.eyed.seeker.code.core.ULTRA_LOCK_REASON
import to.eyed.seeker.code.core.UltraState
import to.eyed.seeker.code.ui.agent.spettro.SelectStyle
import to.eyed.seeker.code.ui.agent.spettro.UltraTap
import to.eyed.seeker.code.ui.agent.spettro.chipIcon
import to.eyed.seeker.code.ui.agent.spettro.chipOrder
import to.eyed.seeker.code.ui.agent.spettro.drillGroups
import to.eyed.seeker.code.ui.agent.spettro.permissionFooters
import to.eyed.seeker.code.ui.agent.spettro.selectStyle
import to.eyed.seeker.code.ui.agent.spettro.ultraStateText
import to.eyed.seeker.code.ui.agent.spettro.ultraTap
import to.eyed.seeker.code.ui.agent.spettro.visibleChoices
import to.eyed.seeker.code.ui.components.Choice as UiChoice
import to.eyed.seeker.code.ui.components.DrillPage
import to.eyed.seeker.code.ui.components.DrillRow
import to.eyed.seeker.code.ui.components.LevelSlider
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SegmentedSelect
import to.eyed.seeker.code.ui.components.SelectRow
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.SheetScaffold
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.RowChevron
import to.eyed.seeker.code.ui.theme.effectSpec
import to.eyed.seeker.code.ui.theme.spatialSpec

/**
 * A config value as `session/set_config_option` wants it: a JSON **value**,
 * not a JSON object.
 *
 * `JSONObject.quote` rather than string interpolation because a model id may
 * hold a quote or a backslash (`lmstudio/qwen"3`), and a hand-built `"$value"`
 * would send malformed JSON that the agent answers with a parse error nobody
 * can act on.
 */
internal fun configValueJson(value: String): String = JSONObject.quote(value)

/** The same, for the boolean options. */
internal fun configValueJson(value: Boolean): String = if (value) "true" else "false"

/**
 * One option's value, in the two shapes ACP gives it, so the optimistic
 * overlay can hold either without a second map.
 *
 * A value class per shape rather than a bare `String?`: `""` is a legal model
 * id in the same way `false` is a legal Ultra, and a nullable string cannot
 * tell "off" from "the agent has not said yet".
 */
@Immutable
internal sealed interface ConfigValue {
    data class Select(val value: String) : ConfigValue

    data class Flag(val on: Boolean) : ConfigValue
}

/** What the wire currently says this option is. */
internal fun AgentConfigOption.wireValue(): ConfigValue =
    if (isBool) ConfigValue.Flag(currentBool == true) else ConfigValue.Select(currentValue.orEmpty())

/**
 * A tap that has left the phone and not yet come back.
 *
 * [saw] is the wire value at the moment of the tap, and it is what retires the
 * overlay: the entry is dropped the instant the option moves off it, whether
 * the agent agreed ([want]), disagreed, or the option vanished entirely. That
 * is the only signal available — `session/set_config_option` answers with a
 * full `config_option_update` replacement rather than an ack for the one
 * option — and it fails safe, because the overlay dies with the sheet.
 */
@Immutable
private data class PendingPick(val want: ConfigValue, val saw: ConfigValue)

/**
 * Everything the agent lets you change, on ONE page.
 *
 * This is the sheet the owner's verdict was about. It used to be 288 lines of
 * the identical 56dp row — mode, model, permission, thinking and Ultra all
 * drawn as `icon · name · value · chevron` — each of which opened a SECOND
 * `ModalBottomSheet` on top of this one to show the same radio list. Four
 * things were wrong with that and all four are answered here:
 *
 *  - **A radio list hides an ordering.** Thinking is an intensity scale, so it
 *    is a [LevelSlider] whose fill desaturates toward "off": you can read how
 *    hard the agent will think before you read a label.
 *  - **Three flat choices are what a segmented row is for.** Mode and
 *    permission become [SegmentedSelect], which prints the ACTIVE choice's
 *    description underneath — the only way "Restricted" ever says what it
 *    restricts.
 *  - **Sheet-over-sheet is two scrims, two handles and an ambiguous back.**
 *    The model list drills INSIDE this sheet ([DrillPage]), so back unwinds
 *    one level because there is one level.
 *  - **The control used to wait on a round trip.** [PendingPick] moves it on
 *    the tap and lets the host confirm behind it.
 *
 * Everything rendered is what the agent advertised — never a hard-coded
 * plan/coding/ask, because the mode list comes out of the project's own
 * `spettro.agents.toml` and varies per repo (docs/SPETTRO.md, "Toolbar
 * chips"). `chipOrder` is the section rank and `chipIcon` the section glyph,
 * unchanged: they were already right and they are shared with the summary row.
 *
 * @param onLocked Ultra was tapped in a state where turning it on would be
 *   refused. Nothing goes on the wire; [ULTRA_LOCK_REASON] is shown instead —
 *   and it is also printed under the switch, so the sentence is on the screen
 *   before the tap as well as after it.
 */
@Composable
fun AgentConfigSheet(
    shell: ShellState,
    state: AgentSessionState,
    onPick: (option: AgentConfigOption, valueJson: String) -> Unit,
    onLocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val toolbar = state.toolbar
    val ordered = chipOrder(toolbar.options)

    // The drilled option's id, saveable because a sheet is not exempt from
    // process death: coming back to the root page when the user was three
    // taps into a thirty-model list is the same bug as losing the sheet.
    var drilled by rememberSaveable { mutableStateOf<String?>(null) }
    val drillOption = ordered.firstOrNull { it.id == drilled }

    val pending = remember { mutableStateMapOf<String, PendingPick>() }
    LaunchedEffect(toolbar) {
        val answered = pending.filter { (id, pick) ->
            toolbar.options.firstOrNull { it.id == id }?.wireValue() != pick.saw
        }
        answered.keys.forEach(pending::remove)
    }

    fun valueOf(option: AgentConfigOption): ConfigValue =
        pending[option.id]?.want ?: option.wireValue()

    fun send(option: AgentConfigOption, want: ConfigValue, json: String) {
        pending[option.id] = PendingPick(want = want, saw = option.wireValue())
        onPick(option, json)
    }

    // Resolved out here: `AnimatedContent`'s `transitionSpec` is not a
    // composable lambda, so neither [effectSpec] nor [spatialSpec] — and
    // therefore neither's reduce-motion branch — can be read inside it.
    val fade = effectSpec<Float>()
    val slide = spatialSpec<IntOffset>()

    SheetScaffold(
        state = shell,
        onDismiss = onDismiss,
        // The drill page draws its own title beside its back arrow, so the
        // scaffold's title belongs to the root page alone; two titles on one
        // sheet is the sheet-over-sheet look this page exists to remove.
        title = if (drillOption == null) "Agent settings" else null,
    ) {
        AnimatedContent(
            targetState = drillOption?.id,
            transitionSpec = {
                // Opening pushes left, closing pushes right: the direction is
                // the only thing that says which way through the stack you
                // went, and a third of the width is far enough to read as a
                // push without the page appearing to come from off-screen.
                val opening = targetState != null
                val enter = slideInHorizontally(slide) { w -> if (opening) w / 3 else -w / 3 } +
                    fadeIn(fade)
                val exit = slideOutHorizontally(slide) { w -> if (opening) -w / 3 else w / 3 } +
                    fadeOut(fade)
                enter togetherWith exit
            },
            label = "config-drill",
            modifier = Modifier.fillMaxSize(),
        ) { id ->
            val option = if (id == null) null else ordered.firstOrNull { it.id == id }
            if (option == null) {
                ConfigRootPage(
                    ordered = ordered,
                    toolbar = toolbar,
                    agentName = agentDisplayName(state.agent?.agentName, null),
                    valueOf = ::valueOf,
                    onSelect = { opt, value ->
                        send(opt, ConfigValue.Select(value), configValueJson(value))
                    },
                    onFlag = { opt, on ->
                        send(opt, ConfigValue.Flag(on), configValueJson(on))
                    },
                    onDrill = { drilled = it.id },
                    onLocked = onLocked,
                )
            } else {
                val current = (valueOf(option) as? ConfigValue.Select)?.value
                DrillPage(
                    title = option.name,
                    groups = drillGroups(option),
                    currentValue = current,
                    onSelect = { choice ->
                        send(option, ConfigValue.Select(choice.value), configValueJson(choice.value))
                        drilled = null
                    },
                    onBack = { drilled = null },
                    modifier = Modifier.padding(horizontal = MD.space4),
                    valueOf = { it.value },
                    // Name, wire value AND description, because a user looking
                    // for "the cheap one" is typing a word that is only in the
                    // description, and "sonnet" is only in the value.
                    searchText = { "${it.name} ${it.value} ${it.description.orEmpty()}" },
                    row = { choice, selected ->
                        DrillRow(
                            name = choice.name,
                            description = choice.description,
                            selected = selected,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The one page: every selector the agent advertised, in `chipOrder`.
 *
 * A `LazyColumn` rather than a scrolling `Column` because the model section is
 * cheap only while its list is somewhere else — and because a sheet whose body
 * is a list of sections is exactly what the contentPadding rhythm is for: the
 * 16dp gutter on both sides, 16dp between sections, and 24dp at the bottom so
 * the last row clears the sheet's own edge.
 */
@Composable
private fun ConfigRootPage(
    ordered: List<AgentConfigOption>,
    toolbar: SpettroToolbar,
    agentName: String,
    valueOf: (AgentConfigOption) -> ConfigValue,
    onSelect: (AgentConfigOption, String) -> Unit,
    onFlag: (AgentConfigOption, Boolean) -> Unit,
    onDrill: (AgentConfigOption) -> Unit,
    onLocked: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = MD.space4,
            end = MD.space4,
            bottom = MD.space6,
        ),
        verticalArrangement = Arrangement.spacedBy(MD.space4),
    ) {
        if (ordered.isEmpty()) {
            item(key = "empty") {
                Text(
                    // Not an error: `configOptions` arrives with the session
                    // and a starting agent has simply not sent it yet.
                    text = "$agentName has not advertised any settings yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MD.space4),
                )
            }
        }
        items(ordered, key = { it.id }) { option ->
            ConfigSection(
                option = option,
                value = valueOf(option),
                agentName = agentName,
                ultraState = toolbar.ultraState,
                ultraStored = toolbar.ultraOn,
                onSelect = { onSelect(option, it) },
                onFlag = { onFlag(option, it) },
                onDrill = { onDrill(option) },
                onLocked = onLocked,
            )
        }
    }
}

/**
 * One section: its heading, its control, and whatever the control cannot say.
 *
 * The dispatch is [selectStyle], eight lines in the data half, and it is the
 * load-bearing decision on this screen — every control below is stock M3 with
 * the app's metrics, so the only thing that makes the page read as designed is
 * choosing the right one for the shape of the data.
 */
@Composable
private fun ConfigSection(
    option: AgentConfigOption,
    value: ConfigValue,
    agentName: String,
    ultraState: UltraState,
    ultraStored: Boolean,
    onSelect: (String) -> Unit,
    onFlag: (Boolean) -> Unit,
    onDrill: () -> Unit,
    onLocked: () -> Unit,
) {
    val style = selectStyle(option)
    if (style == SelectStyle.Switch) {
        FlagSection(
            option = option,
            on = (value as? ConfigValue.Flag)?.on == true,
            ultraState = ultraState,
            onFlag = onFlag,
            onLocked = onLocked,
        )
        return
    }

    val choices = visibleChoices(option)
    val current = (value as? ConfigValue.Select)?.value
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(text = option.name, icon = chipIcon(option))
        if (choices.isEmpty()) {
            // Real, and not rare: a model list with nothing connected arrives
            // empty, and so does any option the agent published before it had
            // values for it. The SECTION still draws — an option that vanishes
            // when it has nothing to offer is one the user thinks the app lost.
            Text(
                text = stringResource(R.string.agent_config_no_choices, agentName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        when (style) {
            SelectStyle.Slider -> LevelSlider(
                choices = choices.map { it.toUiChoice() },
                selectedValue = current,
                onSelect = onSelect,
            )

            SelectStyle.Segmented -> SegmentedSelect(
                options = choices.map { it.toUiChoice() },
                selectedValue = current,
                onSelect = onSelect,
            )

            SelectStyle.Drill -> DrillEntry(
                label = choices.firstOrNull { it.value == current }?.name
                    ?: current.orEmpty().ifEmpty { option.currentLabel },
                description = choices.firstOrNull { it.value == current }?.description,
                optionName = option.name,
                onClick = onDrill,
            )

            SelectStyle.Rows -> for (choice in choices) {
                SelectRow(
                    label = choice.name,
                    description = choice.description,
                    selected = choice.value == current,
                    onSelect = { onSelect(choice.value) },
                )
            }

            // Handled above, before the choices are read.
            SelectStyle.Switch -> Unit
        }
        // The two sentences the permission list owes the user, and the reason
        // they are pinned to this section rather than to the sheet: both are
        // consequences of a permission level that are not local to it — one
        // says what raising unlocks, the other that lowering suspends a stored
        // Ultra rather than clearing it.
        for (line in permissionFooters(option, ultraStored)) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MD.space2),
            )
        }
    }
}

/**
 * The row that opens the drill page: what it is set to, and a chevron.
 *
 * The description sits OUTSIDE the card, at the same 2dp offset a label's
 * description takes everywhere else, because it describes the value rather
 * than being part of the control — putting it inside would make the card two
 * lines tall and turn a one-line answer into a paragraph you have to read past.
 */
@Composable
private fun DrillEntry(
    label: String,
    description: String?,
    optionName: String,
    onClick: () -> Unit,
) {
    SeekerCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MD.space3, vertical = MD.rowPadY)
                .clearAndSetSemantics {
                    contentDescription = "Change $optionName"
                    stateDescription = label
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                // From the END, so a model's variant survives the truncation
                // and `…sonnet-4-6` still says which one it is.
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            RowChevron()
        }
    }
    if (!description.isNullOrBlank()) {
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MD.space05, start = MD.space1),
        )
    }
}

/**
 * A boolean option — Ultra, and anything boolean the agent grows later.
 *
 * A stock M3 `Switch`, which the agent panel did not contain a single one of
 * before this. Ultra's FOUR states are not lost to it: the switch carries the
 * two the switch can carry (stored on / stored off), and the two that are
 * neither — SUSPENDED and LOCKED — are carried by the sentence under it and by
 * `stateDescription`, so neither colour nor position is the only signal.
 *
 * LOCKED IS NOT A HIDDEN CONTROL. The switch is drawn disabled and the row
 * stays tappable, because the tap is one of the two places the reason is told
 * (the other is the line under it). A control that disappears when you are not
 * allowed to use it is one nobody learns exists.
 */
@Composable
private fun FlagSection(
    option: AgentConfigOption,
    on: Boolean,
    ultraState: UltraState,
    onFlag: (Boolean) -> Unit,
    onLocked: () -> Unit,
) {
    val colors = LocalSeekerColors.current
    val ultra = option.id == "ultra"
    val locked = ultra && ultraState == UltraState.Locked
    val stateText = if (ultra) ultraStateText(ultraState) else if (on) "On" else "Off"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // Only the locked row is a click target of its own: everywhere
                // else the switch is the control, and a row that toggles as
                // well as a switch gives TalkBack two ways to say one thing.
                if (locked) Modifier.clickable(onClickLabel = "Why ${option.name} is unavailable") {
                    onLocked()
                } else {
                    Modifier
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(
                text = option.name,
                icon = chipIcon(option),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = on,
                onCheckedChange = { wanted ->
                    if (ultra) {
                        when (val tap = ultraTap(ultraState)) {
                            is UltraTap.Set -> onFlag(tap.value)
                            UltraTap.Locked -> onLocked()
                        }
                    } else {
                        onFlag(wanted)
                    }
                },
                enabled = !locked,
                colors = if (ultra && on) {
                    // Ultra armed is the one charged state in the app and it
                    // wears the theme's warning accent, the same amber the
                    // summary row's dot and the TUI's badge take.
                    SwitchDefaults.colors(
                        checkedTrackColor = colors.ultraAmber,
                        checkedThumbColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        checkedBorderColor = colors.ultraAmber,
                    )
                } else {
                    SwitchDefaults.colors()
                },
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = option.name
                    stateDescription = stateText
                },
            )
        }
        val note = when {
            locked -> ULTRA_LOCK_REASON
            ultra && ultraState == UltraState.Suspended -> ultraStateText(ultraState)
            else -> option.description?.takeIf { it.isNotBlank() }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                // The two states the switch cannot draw are the two that are
                // warned about; anything else is an ordinary description.
                color = if (locked || (ultra && ultraState == UltraState.Suspended)) {
                    colors.warnInk
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = MD.space05),
            )
        }
    }
}

private fun AgentConfigOption.Choice.toUiChoice(): UiChoice =
    UiChoice(value = value, name = name, description = description)

/** One row of the overflow sheet. */
data class OverflowItem(
    val label: String,
    val detail: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * The header's overflow — everything the app bar deliberately does not carry.
 *
 * The bar keeps the title and the subtitle and nothing else (docs/VISUAL.md,
 * "Agent — the screen at rest"). Sessions, the plan, the review pane and
 * Spettro's own settings are all one tap deeper, which is the right depth for
 * things you reach a few times an hour.
 */
@Composable
fun AgentOverflowSheet(shell: ShellState, items: List<OverflowItem>, onDismiss: () -> Unit) {
    SheetScaffold(state = shell, onDismiss = onDismiss, title = "Agent") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MD.space6),
        ) {
            for (item in items) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = item.enabled, onClickLabel = item.label) {
                            item.onClick()
                        }
                        .padding(horizontal = MD.space4, vertical = MD.space3),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (item.enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledInk)
                        },
                    )
                    item.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** M3's disabled content alpha, and the one place this file spells it. */
private const val DisabledInk = 0.38f
