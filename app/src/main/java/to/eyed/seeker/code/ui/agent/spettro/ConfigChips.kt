package to.eyed.seeker.code.ui.agent.spettro

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentConfigOption
import to.eyed.seeker.code.core.SpettroToolbar
import to.eyed.seeker.code.core.UltraState
import to.eyed.seeker.code.ui.components.StatusDot
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalSeekerColors
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.SeekerIcon

/**
 * The one line above the composer that says what the agent is set to, and the
 * five selectors behind it.
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
 * The summary line: `coding · Sonnet 4.6 · Ask · high`, and a tap opens the
 * config sheet.
 *
 * This is what bought back the 36dp horizontally-scrolling chip band. The same
 * five selectors are readable in ~20dp of a column that is 890dp tall and
 * spends most of it on a keyboard, and the row is a *statement of state* — you
 * read it, you do not aim at it — with one target instead of five.
 *
 * ULTRA IS NOT LOST WITH THE CHIPS. Its four-state gating is real protocol
 * behaviour, so a stored-on or locked Ultra puts an amber dot on the end of
 * this line and the full sentence in the sheet. The dot is a redundancy rather
 * than the signal: `stateDescription` says the same thing in words.
 *
 * @param onOpen open the config sheet. The whole row is the target — a 14dp
 *   glyph is not something to aim at, and the row already spans the column.
 */
@Composable
fun ConfigSummaryRow(
    toolbar: SpettroToolbar,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = configSummary(toolbar)
    if (summary.isEmpty()) return
    val colors = LocalSeekerColors.current
    val ultra = toolbar.ultraState
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Agent settings", onClick = onOpen)
            .padding(horizontal = MD.space4, vertical = MD.space05)
            .heightIn(min = SummaryRowHeight)
            .clearAndSetSemantics {
                contentDescription = "Agent settings"
                stateDescription = if (ultra == UltraState.Off) {
                    summary
                } else {
                    "$summary. Ultra: ${ultraStateText(ultra)}"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
    ) {
        // Lucide's `sliders-horizontal`, which is the Tune glyph under the
        // name this project already gave it.
        SeekerIcon(
            icon = R.drawable.ic_ui_filter,
            contentDescription = null,
            tint = colors.accentMark,
            size = IconSize.Marker,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.labelLarge,
            color = colors.accentInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (ultra != UltraState.Off) {
            StatusDot(
                // Suspended and locked are stated in words by the sheet; here
                // they are the same amber at half strength, because the point
                // of the dot on this line is only "Ultra is involved".
                color = if (ultra == UltraState.On) {
                    colors.ultraAmber
                } else {
                    colors.ultraAmber.copy(alpha = 0.5f)
                },
                size = UltraDotSize,
            )
        }
        // The spec's UnfoldMore is not in the vendored Lucide set (adding one
        // means re-vendoring the pinned snapshot), and this is the closer of
        // the two glyphs that are: the tap raises a sheet, which is exactly
        // what `arrow-up-from-line` already means everywhere else in the app.
        SeekerIcon(
            icon = R.drawable.ic_ui_expand_up,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            size = SummaryCaretSize,
        )
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
 * Reading order for the summary LINE, which is not the sheet's order.
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
        .filter { it.isNotBlank() && it != "—" }
        .joinToString(" · ")
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

/** What TalkBack says about an Ultra control; also the four states in words. */
internal fun ultraStateText(state: UltraState): String = when (state) {
    UltraState.Off -> "Off"
    UltraState.On -> "On"
    UltraState.Suspended -> "On but suspended — the permission level is Ask first"
    UltraState.Locked -> "Unavailable — raise the permission level first"
}

/** docs/VISUAL.md's 24dp summary band, less its 2dp of padding. */
private val SummaryRowHeight = 20.dp

/** Between [IconSize.Marker] and nothing: a caret, not a control. */
private val SummaryCaretSize = 12.dp

/** Small enough to be punctuation on a 20dp line, big enough to see. */
private val UltraDotSize = 6.dp
