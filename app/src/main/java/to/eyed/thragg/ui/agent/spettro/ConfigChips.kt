package to.eyed.thragg.ui.agent.spettro

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.core.AgentConfigOption
import to.eyed.thragg.core.SpettroToolbar
import to.eyed.thragg.core.UltraState
import to.eyed.thragg.ui.components.StatusDot
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon

/**
 * The chip in the composer that says what the agent is set to, and the five
 * selectors behind it.
 *
 * Three rules shape this file and none of them is cosmetic.
 *
 * **The UI is never the source of truth.** Every one of these has a slash
 * command (`/mode`, `/models`, `/ultra`) that changes the same agent-side
 * value, and `config_option_update` is a *full replacement* pushed after any
 * handled command. So this renders [SpettroToolbar] as it stands on this poll
 * and holds no state of its own; there is nothing here to fall out of sync
 * because there is nothing here to sync. (The config sheet's optimistic
 * overlay is the one exception, and it lives and dies with that sheet.)
 *
 * **It is data-driven.** The agent's mode list comes out of the project's own
 * `spettro.agents.toml` and differs per repo, so nothing below hard-codes
 * plan/coding/ask, or assumes five options, or hides a sixth the agent grows
 * later. The only id-specific behaviour is [CHIP_PRIORITY] (reading order) and
 * Ultra's gating, which is a protocol rule rather than a preference.
 *
 * **Ultra's lock is honest.** Turning Ultra on under `ask-first` is *refused*
 * by the agent, so a control that sent it anyway would flip and then flip back
 * with no explanation. [ultraTap] decides before anything goes on the wire.
 */

/**
 * The composer's config chip: `Sonnet 4.6  high`, and a tap opens the sheet.
 *
 * THIS REPLACED A ONE-LINE SUMMARY that read `⚙ Coding · Sonnet 4.6 · Ask
 * first · Off` across the full width above the input row, and the owner was
 * right about it: four readouts strung on one rule is a status bar, and a
 * status bar is the shape you give facts nobody can act on. Every one of those
 * four *is* actionable — they all open the same sheet — so the line was
 * spending the composer's widest slot saying four things in order to offer one
 * affordance.
 *
 * So it is a chip in the control row instead, which is where
 * spettro-chat-android's `InputBar` puts the same idea: it is **the row's only
 * flexible element**, so it truncates under pressure and the buttons around it
 * never compress. What it prints is the MODEL — the one selector whose value
 * changes what the next answer costs and how good it is — and the thinking
 * level beside it in a muted ink when thinking is on, because effort is a
 * qualifier on the model rather than a fact of its own.
 *
 * MODE AND PERMISSION ARE STILL THERE, as controls rather than as words. They
 * live one tap away in the config sheet, which is the surface the owner likes
 * and which this does not touch; the sheet is also the only place either of
 * them can be *changed*, which is what a user reading them wants next. The
 * mode's identity colour still marks the transcript's own rows, so "which hat
 * is it wearing" has not gone quiet.
 *
 * ULTRA IS NOT LOST WITH THE WORDS. Its four-state gating is real protocol
 * behaviour — turning it on under `ask-first` is refused — so the chip carries
 * an amber dot when Ultra is *engaged*, and the full sentence is in the sheet.
 * Engaged means [UltraState.On], or [UltraState.Suspended] at half strength:
 * on, but not applying this turn, which is the one case worth a mark the user
 * did not ask for.
 *
 * [UltraState.Locked] draws NOTHING, and that is the correction to the first
 * cut of this chip. Locked is "unavailable — raise the permission level
 * first", which is the resting state of every default `ask-first` session that
 * has never been near Ultra — so keying the dot off `!= Off` lit it more or
 * less permanently and it stopped meaning anything. A dot that is always on is
 * decoration.
 *
 * The dot is a redundancy rather than the signal: [configChipState] says the
 * same thing in words to a screen reader — including the locked case, which
 * has no dot — and it says the mode and the permission too, so nothing the old
 * line announced is announced any less.
 *
 * There is no caret. The row-wide summary needed one to say it was a control
 * and not a caption; a raised pill with a settings mark on it, sitting between
 * two buttons, does not — and the width a stacked chevron costs is width the
 * model name wants.
 *
 * @param onOpen open the config sheet. The whole chip is the target, which at
 *   [ConfigChipHeight] by its own width clears WCAG 2.5.8's 24dp floor for a
 *   labelled control without claiming a 48dp row the control row has not got.
 */
@Composable
fun ConfigChip(
    toolbar: SpettroToolbar,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing on the wire yet is no chip at all rather than an empty pill: the
    // selectors arrive a poll after the session does, and a pill that says
    // nothing for that second is worse than one that arrives with its name.
    val label = configChipLabel(toolbar) ?: return
    val effort = configChipEffort(toolbar)
    val state = configChipState(toolbar)
    val colors = LocalSeekerColors.current
    val scheme = MaterialTheme.colorScheme
    val ultra = toolbar.ultraState
    Row(
        modifier = modifier
            .heightIn(min = ConfigChipHeight)
            .clip(CircleShape)
            // A step above the composer's own container, which is itself a
            // step above the band: the chip has to read as a thing sitting on
            // the surface, the way the two discs beside it do.
            .background(scheme.surfaceContainerHighest)
            .clickable(onClickLabel = "Agent settings", onClick = onOpen)
            .padding(horizontal = MD.space3, vertical = MD.pillPadY)
            .clearAndSetSemantics {
                contentDescription = "Agent settings"
                stateDescription = state
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
    ) {
        // Lucide's `sliders-horizontal`, which is the Tune glyph under the
        // name this project already gave it. It is what tells the eye the pill
        // is a way in rather than a badge.
        SeekerIcon(
            icon = R.drawable.ic_ui_filter,
            contentDescription = null,
            tint = colors.accentMark,
            size = IconSize.Marker,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            maxLines = 1,
            // Both cuts are needed and they cut different ends. [chipLabel]
            // takes the model's TAIL because that is where the variant lives;
            // this is the last resort when even that does not fit the row, and
            // it keeps the head, which is the wrong end — so it should only
            // ever fire on a phone held in a very small window.
            overflow = TextOverflow.Ellipsis,
            // `fill = false` so a short model name does not push the effort
            // label and the dot to the far edge of the row.
            modifier = Modifier.weight(1f, fill = false),
        )
        if (effort != null) {
            Text(
                text = effort,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (ultraEngaged(ultra)) {
            StatusDot(
                // Suspended is the half-strength one: Ultra is on, but this
                // turn runs without it. Full amber is the plain "it is on".
                color = if (ultra == UltraState.On) {
                    colors.ultraAmber
                } else {
                    colors.ultraAmber.copy(alpha = 0.5f)
                },
                size = UltraDotSize,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Mode colour
// ---------------------------------------------------------------------------

/**
 * A mode's identity colour, or null for anything that is not a mode.
 *
 * The table itself is `SeekerColors.modeColor` — the same one the TUI and the
 * desktop client paint from, keyed on the manifest colour NAMES that
 * `spettro.agents.toml` actually emits as well as on the mode ids. This
 * function is only the guard that used to be the first line of `modeTintArgb`:
 * `category != "mode"` means no tint, because tinting a permission level with
 * a mode's palette would say something untrue about it.
 */
@Composable
internal fun modeTint(option: AgentConfigOption): Color? =
    if (option.category != "mode") {
        null
    } else {
        LocalSeekerColors.current.modeColor(option.currentValue)
    }

// ---------------------------------------------------------------------------
// The pure half — everything below is tested in ConfigChipsTest
// ---------------------------------------------------------------------------

/** Spettro's boolean option id, and the only one with a gating rule. */
internal const val ULTRA_ID = "ultra"

/**
 * Reading order on a 400 dp screen, by option id.
 *
 * Ultra first because it is the charged one — a fan-out costs real money and
 * must be visible without scrolling. Anything the agent sends that is not on
 * this list keeps its wire position after the ones that are, so a sixth
 * option appears rather than disappearing.
 */
private val CHIP_PRIORITY = listOf(ULTRA_ID, "mode", "model", "thinking", "permission")

internal fun chipOrder(options: List<AgentConfigOption>): List<AgentConfigOption> {
    val known = CHIP_PRIORITY.mapNotNull { id -> options.firstOrNull { it.id == id } }
    val rest = options.filter { it.id !in CHIP_PRIORITY }
    return known + rest
}

/**
 * Reading order for the SPOKEN state, which is not the sheet's order.
 *
 * The sheet ranks Ultra first because it is the charged control and it must be
 * reachable without scrolling. A sentence has no scrolling and no Ultra — the
 * dot carries that — so it runs in the order the answer is usually wanted:
 * what the agent is being, what it is thinking with, what it may do, and how
 * hard it thinks. Anything the agent grows later follows in wire order, so a
 * sixth selector appears rather than disappearing.
 */
private val SUMMARY_ORDER = listOf("mode", "model", "permission", "thinking")

/**
 * `coding · Sonnet 4.6 · Ask · high`, or empty when the agent has said nothing.
 *
 * Booleans are left out on purpose: "Ultra Off" in the middle of the line is
 * three of its forty characters spent on the one state that is already the
 * default, and Ultra's other three states are carried by the dot.
 *
 * The separator is U+00B7, the same one every other "a · b" in the app uses.
 */
internal fun configSummary(toolbar: SpettroToolbar): String {
    val ranked = SUMMARY_ORDER.mapNotNull { id -> toolbar.options.firstOrNull { it.id == id } }
    val rest = toolbar.options.filter { it.id !in SUMMARY_ORDER }
    return (ranked + rest)
        .filter { !it.isBool }
        .map { chipLabel(it) }
        // [saysSomething], not a blank check: an unset model's label is the
        // literal `:` and a summary reading "coding · : · yolo" is worse
        // than one that skips the hole.
        .filter { saysSomething(it) }
        .joinToString(" · ")
}

/**
 * What [ConfigChip] prints, or null when the agent has advertised nothing.
 *
 * The model, because the model is the selector whose value the user is most
 * often about to change and the only one whose *name* they need in front of
 * them: mode and permission are two or three known words each and are read off
 * the sheet in a second, while "which of thirty models is this" is not
 * recoverable from memory.
 *
 * The fallback is the agent's first non-boolean selector rather than nothing.
 * A generic ACP agent may publish no `model` at all (the conformance agent
 * without `--spettro` publishes exactly one option, and it is not always this
 * one), and a chip that vanishes takes the only route to the config sheet in
 * the composer with it.
 */
internal fun configChipLabel(toolbar: SpettroToolbar): String? {
    val option = toolbar.model ?: toolbar.options.firstOrNull { !it.isBool } ?: return null
    val label = chipLabel(option)
    if (saysSomething(label)) return label
    // The agent's "no model chosen yet" arrives as the literal `:` —
    // provider and name formatted around their separator with neither
    // present — and a chip printing a lone colon looks broken, not unset.
    // The model chip keeps a plain-words label instead of vanishing, because
    // the chip is also the route to the sheet that fixes the situation.
    return if (option === toolbar.model) "No model" else null
}

/**
 * Whether a wire label carries any actual name — a letter or a digit —
 * rather than only the punctuation of an empty format string (`:`, `—`, or
 * blank).
 */
private fun saysSomething(label: String) = label.any(Char::isLetterOrDigit)

/**
 * The thinking level beside the model, or null when it is not thinking.
 *
 * `off` earns no ink. It is the default and it is the state the absence of a
 * word already describes, and the chip's whole budget is the width the model
 * name is not using. Matched on the VALUE rather than the label because the
 * label is the agent's prose and a project could localise it; matched on
 * `thought_level` as well as on the id, because this file does not hard-code
 * the agent's option names (see the header).
 */
internal fun configChipEffort(toolbar: SpettroToolbar): String? {
    val thinking = toolbar.thinking
        ?: toolbar.options.firstOrNull { it.category == THOUGHT_LEVEL }
        ?: return null
    val value = thinking.currentValue?.lowercase() ?: return null
    if (value in THINKING_OFF) return null
    return chipLabel(thinking).takeIf { it.isNotBlank() && it != "—" }
}

/** ACP's category for a thinking scale; `SpettroToolbar.thinking` is the id. */
private const val THOUGHT_LEVEL = "thought_level"

/** The values that mean "not thinking", in every spelling seen on the wire. */
private val THINKING_OFF = setOf("off", "none", "false", "0")

/**
 * What a screen reader is told the chip is set to.
 *
 * THE CHIP MUST ANNOUNCE EVERYTHING THE OLD SUMMARY LINE DID. Sighted users
 * gave up two readouts for a shorter row; a TalkBack user gains nothing from a
 * shorter row and would only have lost the mode and the permission level. So
 * the spoken state is still the whole sentence — [configSummary] in reading
 * order — with Ultra's four-state gating appended in words whenever the amber
 * dot is showing, since a dot says nothing at all out loud.
 */
internal fun configChipState(toolbar: SpettroToolbar): String {
    val summary = configSummary(toolbar).ifEmpty { "not reported yet" }
    val ultra = toolbar.ultraState
    return if (ultra == UltraState.Off) summary else "$summary. Ultra: ${ultraStateText(ultra)}"
}

/**
 * The chip's icon, from `category` first and `id` only as the fallback.
 *
 * `permission` and `ultra` deliberately carry no category on the wire, so
 * those two are the only ids matched here; everything else is keyed on the
 * classification the agent itself published, which is what lets a renamed
 * option keep the right icon.
 *
 * These were Unicode characters and every one of them is outside the range a
 * phone's UI face has to carry. A control whose glyph is tofu is a control
 * whose meaning is gone, so this returns a drawable now; the mapping from
 * category to *idea* is unchanged.
 */
@DrawableRes
internal fun chipIcon(option: AgentConfigOption): Int = when (option.category) {
    "mode" -> R.drawable.ic_ui_compass
    "model" -> R.drawable.ic_ui_hexagon
    "thought_level" -> R.drawable.ic_ui_sparkles
    else -> when (option.id) {
        ULTRA_ID -> R.drawable.ic_ui_zap
        "permission" -> R.drawable.ic_ui_shield
        else -> R.drawable.ic_ui_diamond
    }
}

/**
 * What a control prints, cut to fit.
 *
 * The model keeps its **end** (`…claude-sonnet-4-5`) because that is where the
 * variant lives; a head-truncated model reads `anthropic:claude-s…` and every
 * model in the list looks identical. Everything else keeps its head, where a
 * mode or permission name puts its distinguishing word.
 */
internal fun chipLabel(option: AgentConfigOption, max: Int = ChipLabelMax): String {
    val label = option.currentLabel
    if (label.length <= max) return label
    return if (option.category == "model") {
        "…" + label.takeLast(max - 1)
    } else {
        label.take(max - 1) + "…"
    }
}

/** Roughly what fits beside four siblings before the row starts scrolling. */
internal const val ChipLabelMax = 18

/** What a tap on an Ultra control means, decided before anything is sent. */
internal sealed interface UltraTap {
    /** Send this stored value. */
    data class Set(val value: Boolean) : UltraTap

    /** Send nothing: the agent would refuse it. Explain instead. */
    data object Locked : UltraTap
}

/**
 * The gate, in one place.
 *
 * Only one of the four states refuses, and it is the *on* direction under
 * `ask-first`. A SUSPENDED control is stored-on under ask-first, so its tap
 * turns Ultra off — which is never locked — and that is the tap the user
 * wants there anyway: the alternative reading, "un-suspend it", is not a
 * thing this control can do without changing the permission level, and doing
 * that silently would be worse than doing nothing.
 */
internal fun ultraTap(state: UltraState): UltraTap = when (state) {
    UltraState.Locked -> UltraTap.Locked
    UltraState.Off -> UltraTap.Set(true)
    UltraState.On, UltraState.Suspended -> UltraTap.Set(false)
}

/**
 * Whether Ultra is doing something the chip should mark.
 *
 * On, or on-and-suspended. NOT [UltraState.Locked]: that is the default
 * `ask-first` session's resting state rather than anything the user set, and a
 * dot that is lit by default is not a signal. See [ConfigChip]'s note.
 */
internal fun ultraEngaged(state: UltraState): Boolean =
    state == UltraState.On || state == UltraState.Suspended

/** What TalkBack says about an Ultra control; also the four states in words. */
internal fun ultraStateText(state: UltraState): String = when (state) {
    UltraState.Off -> "Off"
    UltraState.On -> "On"
    UltraState.Suspended -> "On but suspended — the permission level is Ask first"
    UltraState.Locked -> "Unavailable — raise the permission level first"
}

/**
 * The chip's drawn height, inside the control row's 48dp.
 *
 * Not 48: the row's height is already set by the touch targets of `＋` and
 * send, and a 48dp pill between two 40dp discs reads as a fourth button. 32dp
 * is a chip, and 32dp by however wide the model name is clears WCAG 2.5.8's
 * 24dp floor for a control that carries its own label — the same argument the
 * composer's draft chips make.
 */
internal val ConfigChipHeight = 32.dp

/** Small enough to be punctuation on a 32dp chip, big enough to see. */
private val UltraDotSize = 6.dp
