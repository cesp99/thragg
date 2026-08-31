package to.eyed.seeker.code.ui.agent.spettro

import to.eyed.seeker.code.core.AgentConfigOption
import to.eyed.seeker.code.ui.components.Group

/**
 * The config surface's data half: what a selector's list looks like, and which
 * control it should be drawn as.
 *
 * This file used to be a composable too — `ConfigSheet`, a second
 * `ModalBottomSheet` opened on top of the agent's config sheet to show one
 * radio list. That is gone (docs/VISUAL.md, "Agent — the model selector"):
 * sheet-over-sheet on a 400dp phone is two scrims, two drag handles and a back
 * gesture that means one thing to the user and two to the app, so the list
 * drills INSIDE the sheet through `DrillPage` instead. Its two genuinely good
 * behaviours — provider group headers, and opening scrolled to the current
 * value — moved into that component, which is why the arithmetic that used to
 * live here (`currentRowIndex`) is not here any more either.
 *
 * What survives is everything that is a rule rather than a drawing, and every
 * one of them is pinned by `ConfigSheetsTest`.
 */

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
        option.groups.map { SheetSection(it.name, offerable(option, it.options)) }
    } else {
        listOf(SheetSection(null, offerable(option, option.flat)))
    }
    return sections.filter { it.choices.isNotEmpty() }
}

/**
 * Every choice a person may actually pick, flattened.
 *
 * The flat view is what the slider, the segmented row and the row list all
 * want — none of them can express a group — and it goes through
 * [sheetSections] rather than `option.choices` so the internal-role filter is
 * applied once, in one place, whatever shape the wire sent.
 */
internal fun visibleChoices(option: AgentConfigOption): List<AgentConfigOption.Choice> =
    sheetSections(option).flatMap { it.choices }

/** [sheetSections] as `DrillPage` wants it: the same list, its own type. */
internal fun drillGroups(option: AgentConfigOption): List<Group<AgentConfigOption.Choice>> =
    sheetSections(option).map { Group(it.header, it.choices) }

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

private fun offerable(
    option: AgentConfigOption,
    choices: List<AgentConfigOption.Choice>,
): List<AgentConfigOption.Choice> =
    if (option.category != "mode") {
        choices
    } else {
        choices.filter { it.value !in INTERNAL_MODE_ROLES || it.value == option.currentValue }
    }

/** The five ways a config option can be drawn. */
internal enum class SelectStyle {
    /** An ordered intensity scale: thinking. */
    Slider,

    /** Two to four flat choices side by side: mode, permission. */
    Segmented,

    /** Five or six flat choices, each with room for its reason. */
    Rows,

    /** Grouped, or long enough to need a filter: model. */
    Drill,

    /** A boolean: Ultra, and whatever else the agent grows. */
    Switch,
}

/**
 * Which control an option gets, from the SHAPE OF ITS DATA and nothing else.
 *
 * Eight lines, and the whole of what makes the config sheet read as designed
 * rather than as a settings dump — every control it dispatches to is stock M3
 * with the app's metrics, so the only real decision is this one. It is keyed
 * on shape rather than on id so an option the agent grows next month lands
 * somewhere sensible without a code change; only [isThinking] names anything,
 * and it names a *category* the agent itself publishes.
 *
 * The order of the branches is the argument:
 *  - **thinking is a slider** because it is an ordered intensity and a radio
 *    list hides the ordering. Three levels is the minimum for a scale to be
 *    worth reading as one; a grouped thought level is not a scale at all.
 *  - **grouped or long is a drill**, because thirty models with provider
 *    headers is a page, not a section.
 *  - **two to four is segmented**: at 400dp minus the 16dp gutters, five
 *    segments give 70dp each, which cannot hold "Ask once each" at any size a
 *    thumb can aim at.
 *  - **anything else is rows**, which is also the only style with room for
 *    every choice's description at once.
 */
internal fun selectStyle(option: AgentConfigOption): SelectStyle {
    if (option.isBool) return SelectStyle.Switch
    val count = visibleChoices(option).size
    val isThinking = option.category == THOUGHT_LEVEL || option.id == THINKING_ID
    return when {
        isThinking && !option.isGrouped && count >= 3 -> SelectStyle.Slider
        option.isGrouped || count > MAX_ROWS -> SelectStyle.Drill
        count in 2..MAX_SEGMENTS -> SelectStyle.Segmented
        else -> SelectStyle.Rows
    }
}

/** ACP's category for a thinking scale; the id is the fallback. */
private const val THOUGHT_LEVEL = "thought_level"

private const val THINKING_ID = "thinking"

/** Beyond this many rows a section is longer than the sheet, so it drills. */
private const val MAX_ROWS = 6

/** See [selectStyle]: five segments do not fit on a 400dp column. */
private const val MAX_SEGMENTS = 4

/** Spettro's lowest permission level, and the one Ultra is gated behind. */
private const val ASK_FIRST = "ask-first"

/**
 * The two sentences the Permission section owes the user, and only that one.
 *
 * Both exist because the *consequence* of a permission level is not local to
 * the permission list. Raising it turns Ultra and workflows from refused into
 * available; lowering it does not clear a stored Ultra, it suspends it, and
 * the difference shows up later as a switch that reads ON while nothing fans
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
