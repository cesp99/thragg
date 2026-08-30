package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * ACP agents, as data — and only as data.
 *
 * The panel is agent-agnostic, which is the protocol's whole point: ACP is a
 * standard, so *every* agent comes from `agent_servers` in settings.json —
 * Zed's own key, managed by the settings screen's External Agents section the
 * way Zed's settings window manages the same list
 * (settings_ui/src/pages/external_agents_page.rs). No agent is named in code,
 * and nothing here installs anything or tells the user what to install: the
 * command runs inside the Linux userland, and putting it there is the user's
 * own business, in the terminal they already have (DECISIONS.md, 2026-08-18).
 */
data class AgentDefinition(
    /** Stable id, used as the settings key and in the session list. */
    val id: String,
    /** What to call it on screen — the `agent_servers` key. */
    val name: String,
    /** The guest argv, program included. */
    val argv: List<String>,
    /** Extra environment, on top of the guest's own. */
    val env: Map<String, String> = emptyMap(),
) {
    /**
     * The engine's `AgentSpec`, as JSON.
     *
     * Assembled with `JSONObject` rather than by hand. A custom agent's name
     * and arguments are whatever the user typed into settings.json, so either
     * may contain a quote or a backslash — and hand-built JSON is exactly
     * where that stops being valid JSON and the engine rejects the spec.
     */
    fun toSpecJson(): String = JSONObject().apply {
        put("name", name)
        put("argv", JSONArray(argv))
        put("env", JSONObject(env.toMap()))
    }.toString()
}

object Agents {
    /**
     * Whether an agent that would not start looks like a missing program.
     *
     * The engine reports the agent's own last line of stderr, and a guest
     * that cannot find the program says so in one of two ways depending on
     * whether the shell or the loader got there first. Used only to pick the
     * sentence the panel shows.
     */
    fun looksLikeMissingProgram(error: String?): Boolean {
        val text = error?.lowercase() ?: return false
        return "command not found" in text ||
            "not found" in text ||
            "no such file or directory" in text
    }
}
