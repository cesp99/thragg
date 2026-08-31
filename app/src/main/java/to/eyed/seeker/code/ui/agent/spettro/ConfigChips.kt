package to.eyed.seeker.code.ui.agent.spettro

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentConfigOption
import to.eyed.seeker.code.core.SpettroToolbar
import to.eyed.seeker.code.core.ULTRA_LOCK_REASON
import to.eyed.seeker.code.core.UltraState
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.SeekerIcon

/**
 * The five selectors Spettro advertises, as a 32 dp chip row above the
 * composer — Mode, Model, Permission, Thinking, and the Ultra toggle.
 *
 * Three rules shape this file and none of them is cosmetic.
 *
 * **The UI is never the source of truth.** Every one of these has a slash
 * command (`/mode`, `/models`, `/ultra`) that changes the same agent-side
 * value, and `config_option_update` is a *full replacement* pushed after any
 * handled command. So a chip renders [SpettroToolbar] as it stands on this
 * poll and holds no state of its own; there is nothing here to fall out of
 * sync because there is nothing here to sync.
 *
 * **It is data-driven.** The agent's mode list comes out of the project's own
 * `spettro.agents.toml` and differs per repo, so nothing below hard-codes
 * plan/coding/ask, or assumes five options, or hides a sixth the agent grows
 * later. The only id-specific behaviour is [CHIP_PRIORITY] (which chip is
 * reachable without scrolling) and Ultra's gating, which is a protocol rule
 * rather than a preference.
 *
 * **Ultra's lock is honest.** Turning Ultra on under `ask-first` is *refused*
 * by the agent, so a chip that sent it anyway would flip and then flip back
 * with no explanation. [ultraTap] decides before anything goes on the wire:
 * a locked tap raises [ULTRA_LOCK_REASON] and offers the Permission sheet
 * instead. The chip is still drawn, and still tappable — a control that
 * vanishes when you are not allowed to use it is one nobody learns exists.
 *
 * Callbacks only; K9 wires them to `AgentSessions`. Nothing here touches the
 * bridge, so nothing here can block the main thread.
 *
 * @param busy the agent cannot take a config change right now. SPETTRO.md is
 *   exact that this *disables* rather than hides ("Chips are disabled (not
 *   hidden) while `phase == starting`") — the row's width must not change
 *   underneath a thumb that is already moving toward it.
 * @param onSelect open the selector sheet for a select option ([ConfigSheet]).
 * @param onToggleUltra the new stored value for a boolean option. Ultra's OFF
 *   direction is never locked, so this fires for it whatever the permission
 *   level.
 * @param onLockedTap Ultra was tapped while turning it on would be refused.
 *   Show [ULTRA_LOCK_REASON] verbatim with a *Change…* action that opens the
 *   Permission sheet.
 */
@Composable
fun ConfigChips(
    toolbar: SpettroToolbar,
    busy: Boolean,
    onSelect: (AgentConfigOption) -> Unit,
    onToggleUltra: (AgentConfigOption, Boolean) -> Unit,
    onLockedTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ordered = chipOrder(toolbar.options)
    if (ordered.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ChipRowHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (option in ordered) {
            when {
                // Ultra is the gated boolean: an inline toggle, no sheet, and
                // four states rather than two. `isBool` as well as the id,
                // because [SpettroToolbar.ultraState] reads `currentBool` — an
                // `ultra` that arrived as a select would be drawn permanently
                // off, and is better served by the ordinary sheet.
                option.id == ULTRA_ID && option.isBool -> UltraChip(
                    option = option,
                    state = toolbar.ultraState,
                    busy = busy,
                    onToggle = { onToggleUltra(option, it) },
                    onLockedTap = onLockedTap,
                )
                // Any other boolean the agent grows later: the same inline
                // toggle without the gating, because a two-value sheet is a
                // worse switch.
                option.isBool -> PlainChip(
                    option = option,
                    busy = busy,
                    onClick = { onToggleUltra(option, option.currentBool != true) },
                )
                else -> PlainChip(
                    option = option,
                    busy = busy,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/**
 * A rejected `session/set_config_option`, in the agent's own words.
 *
 * Deliberately its own composable rather than a slot inside [ConfigChips]:
 * the chip row has a fixed 36 dp in the vertical budget and a notice growing
 * inside it would push the composer down mid-tap. K9 places this above the
 * row and feeds it `AgentSessions.lastRefusal` / the engine's `notice`.
 *
 * The text is **verbatim**. `unknown mode: x` and `invalid model: x` were
 * written by the CLI to be read by the person who typed them; rewording them
 * into "Could not change setting" throws away the only part that helps. The
 * refusal itself is already dropped rather than retried (K2), so this row is
 * the whole of the user's feedback.
 */
@Composable
fun ConfigNotice(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalZedTheme.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SeekerIcon(
            icon = R.drawable.ic_ui_warning,
            contentDescription = null,
            tint = theme.color("warning", MaterialTheme.colorScheme.error),
            size = IconSize.Marker,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 2,
        )
    }
}

// ---------------------------------------------------------------------------
// The chips
// ---------------------------------------------------------------------------

/** Mode / Model / Thinking / Permission, and any select or boolean beyond them. */
@Composable
private fun PlainChip(
    option: AgentConfigOption,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val tint = modeTintArgb(option)?.let { Color(it) }
    val text = theme.color("text", MaterialTheme.colorScheme.onSurface)
    val label = chipLabel(option)
    ChipBox(
        fill = theme.color("element.background", MaterialTheme.colorScheme.surfaceVariant),
        // The mode chip carries the mode's own colour on its border and glyph
        // rather than its fill: at 32 dp a filled chip is the loudest thing on
        // the screen, and Ultra-armed has to stay the loudest thing.
        border = tint?.copy(alpha = 0.55f)
            ?: theme.color("border.variant", MaterialTheme.colorScheme.outlineVariant),
        busy = busy,
        onClick = onClick,
        onClickLabel = if (option.isBool) "Toggle ${option.name}" else "Change ${option.name}",
        description = "${option.name}, ${option.currentLabel}",
    ) {
        SeekerIcon(
            icon = chipIcon(option),
            contentDescription = null,
            tint = tint ?: theme.color("icon.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            size = IconSize.Marker,
        )
        // A select prints its *value* — the option's own name is already said
        // by the icon, and "Model: Claude Sonnet 4.5" does not fit beside four
        // siblings. A boolean prints its name and then its state, because
        // "Off" alone names nothing.
        Text(
            text = if (option.isBool) option.name else label,
            style = MaterialTheme.typography.labelMedium,
            color = if (tint != null) text else text.copy(alpha = 0.9f),
            maxLines = 1,
        )
        if (option.isBool) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                maxLines = 1,
            )
        }
    }
}

/**
 * Ultra, in the four states of the wireframe.
 *
 * SUSPENDED is derived here and nowhere on the wire: the agent publishes
 * `cfg.Ultra`, the *stored* flag, not `UltraActive() = Ultra && permission !=
 * ask-first`. Rendering the wire value alone would show a confident ON over a
 * swarm that is not fanning out.
 */
@Composable
private fun UltraChip(
    option: AgentConfigOption,
    state: UltraState,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onLockedTap: () -> Unit,
) {
    val theme = LocalZedTheme.current
    // "Amber" is the theme's warning accent, so Ultra sits inside the user's
    // palette rather than beside it; the literal is only the floor for a theme
    // that never wrote the key.
    val amber = theme.color("warning", UltraAmberFallback)
    val neutral = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val armed = state == UltraState.On
    ChipBox(
        fill = when (state) {
            UltraState.On -> amber
            UltraState.Off, UltraState.Suspended -> amber.copy(alpha = 0.08f)
            UltraState.Locked -> Color.Transparent
        },
        border = when (state) {
            UltraState.On -> amber
            UltraState.Off, UltraState.Suspended -> amber.copy(alpha = 0.40f)
            UltraState.Locked -> neutral.copy(alpha = 0.40f)
        },
        busy = busy,
        // A locked chip stays at .55 rather than at a disabled alpha: it is
        // still tappable, and the tap is the only place the reason is told.
        dim = if (state == UltraState.Locked) 0.55f else 1f,
        onClick = { when (val tap = ultraTap(state)) {
            is UltraTap.Set -> onToggle(tap.value)
            UltraTap.Locked -> onLockedTap()
        } },
        onClickLabel = when (state) {
            UltraState.On, UltraState.Suspended -> "Turn ${option.name} off"
            UltraState.Off -> "Turn ${option.name} on"
            UltraState.Locked -> "Why ${option.name} is unavailable"
        },
        description = option.name,
        stateText = ultraStateText(state),
    ) {
        // #1a1205 is the one hard-coded colour SPETTRO.md permits: the label on
        // the armed amber chip, which has to stay legible on a fill that is the
        // theme's warning colour rather than the surface.
        val label = when (state) {
            UltraState.On -> UltraArmedLabel
            UltraState.Locked -> neutral
            else -> amber
        }
        SeekerIcon(
            icon = R.drawable.ic_ui_zap,
            contentDescription = null,
            tint = label,
            size = IconSize.Marker,
        )
        Text(
            text = option.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
            color = label,
            maxLines = 1,
        )
        // The suffix glyph carries the two states that are not simply on or
        // off. It is a redundancy, not the signal: `stateDescription` says the
        // same thing to TalkBack, so neither colour nor glyph is load-bearing
        // alone.
        val suffix = when (state) {
            UltraState.Suspended -> R.drawable.ic_ui_pause
            UltraState.Locked -> R.drawable.ic_ui_lock
            else -> null
        }
        if (suffix != null) {
            SeekerIcon(
                icon = suffix,
                contentDescription = null,
                tint = label,
                size = IconSize.Marker,
            )
        }
    }
}

/**
 * The shared chip shell: 32 dp, pill, one row of children.
 *
 * `clearAndSetSemantics` because a chip is one control, not a glyph plus a
 * word plus a pause sign — TalkBack reads "Model, Claude Sonnet 4.5" rather
 * than spelling the icon.
 */
@Composable
private fun ChipBox(
    fill: Color,
    border: Color,
    busy: Boolean,
    onClick: () -> Unit,
    onClickLabel: String,
    description: String,
    stateText: String? = null,
    dim: Float = 1f,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .height(ChipHeight)
            .defaultMinSize(minWidth = 44.dp)
            .alpha(if (busy) DisabledAlpha else dim)
            .clip(RoundedCornerShape(ChipHeight / 2))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(ChipHeight / 2))
            .clickable(enabled = !busy, onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 10.dp)
            .clearAndSetSemantics {
                contentDescription = description
                if (stateText != null) stateDescription = stateText
            },
    ) { content() }
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
 * The chip's icon, from `category` first and `id` only as the fallback.
 *
 * `permission` and `ultra` deliberately carry no category on the wire, so
 * those two are the only ids matched here; everything else is keyed on the
 * classification the agent itself published, which is what lets a renamed
 * option keep the right icon.
 *
 * These were Unicode characters — `⌁`, `⬢`, `✻`, `⛨` — and every one of them
 * is outside the range a phone's UI face has to carry. A chip whose glyph is
 * tofu is a chip whose meaning is gone, so this returns a drawable now; the
 * mapping from category to *idea* is unchanged.
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
 * Spettro's mode identity colours — plan, coding, ask.
 *
 * Hard-coded on purpose, and the one place in this file that is. They are the
 * same three colours the CLI and the desktop client paint, so a phone that
 * derived them from the user's editor theme would break a shared vocabulary:
 * "you are in the purple mode" has to mean the same thing in both windows.
 * An id this table does not know gets no tint rather than a guessed one.
 */
internal fun modeTintArgb(option: AgentConfigOption): Long? =
    if (option.category != "mode") {
        null
    } else {
        when (option.currentValue) {
            "plan" -> 0xFFBD93F9
            "coding" -> 0xFF34D399
            "ask" -> 0xFF60A5FA
            else -> null
        }
    }

/**
 * What the chip prints, cut to fit.
 *
 * The model chip keeps its **end** (`…claude-sonnet-4-5`) because that is
 * where the variant lives; a head-truncated model reads `anthropic:claude-s…`
 * and every model in the list looks identical. Everything else keeps its
 * head, where a mode or permission name puts its distinguishing word.
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

/** What a tap on the Ultra chip means, decided before anything is sent. */
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
 * `ask-first`. A SUSPENDED chip is stored-on under ask-first, so its tap
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

/** What TalkBack says about the Ultra chip; also the four states in words. */
internal fun ultraStateText(state: UltraState): String = when (state) {
    UltraState.Off -> "Off"
    UltraState.On -> "On"
    UltraState.Suspended -> "On but suspended — the permission level is Ask first"
    UltraState.Locked -> "Unavailable — raise the permission level first"
}

private val ChipHeight = 32.dp

/** 32 dp of chip inside the wireframe's 36 dp band. */
private val ChipRowHeight = 36.dp

private const val DisabledAlpha = 0.4f

/** SPETTRO.md's one permitted literal: the label on the armed amber chip. */
private val UltraArmedLabel = Color(0xFF1A1205)

/** Only reached by a theme whose JSON never wrote `warning`. */
private val UltraAmberFallback = Color(0xFFF5A524)
