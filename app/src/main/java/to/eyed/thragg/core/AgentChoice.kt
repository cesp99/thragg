package to.eyed.thragg.core

import android.content.Context

/**
 * Which agent the user picked, kept across process death.
 *
 * The panel used to forget it. [AgentSessions.agent] was written by exactly two
 * things — the bundled agent registering itself on the way into the screen, and
 * a tap in the picker — and neither wrote it anywhere, so the choice lived only
 * in the object. On a phone that is *less* than one session: a device holding a
 * 1.4 GB toolchain kills this process routinely, and every relaunch put a user
 * who had deliberately chosen their own `agent_servers` entry back on Spettro
 * without saying so.
 *
 * **The name, and not the definition.** A configured agent exists only in
 * settings.json (see [AgentDefinition]), so there is nothing here to serialise
 * that would still be true after the user edits that file — a stored argv would
 * launch the *old* command for ever. The name is the `agent_servers` key, which
 * is the identity the user themselves types, and it is resolved against the
 * live settings at every launch by [resolve]. An entry that has been renamed or
 * deleted resolves to nothing, which is exactly right: the panel then falls
 * back to registering the bundled agent, as it does on a fresh install.
 *
 * **Where it is written.** The `thragg.agent` preferences file the agent screen
 * already keeps its one-time "how much should it ask?" record in, and not
 * settings.json. Two reasons, and they are the same two that put the record
 * there: settings.json is the file the user opens *in the editor*, so every key
 * in it is a key they may hand-edit and expect to mean something, and this is a
 * memory of a tap rather than a knob. Storing it there would also mean a new
 * key in the engine's typed `Settings` struct, since the resolved settings that
 * come back over JNI drop what the struct does not name.
 */
object AgentChoice {

    /**
     * Shared with the agent screen's permission-question record — one file for
     * the panel's small facts about this device, named in one place.
     */
    const val PREFS = "thragg.agent"

    private const val KEY_CHOSEN = "chosen_agent"

    /**
     * The `agent_servers` key last chosen, or null if none ever was.
     *
     * **Blocking**: the first read of a preferences file goes to disk. Call it
     * off the main thread.
     */
    fun remembered(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CHOSEN, null)
            ?.takeIf { it.isNotBlank() }

    /**
     * Record [name] as the choice, or forget it when null — which is what
     * [AgentSessions.reset] means by going back to the picker.
     *
     * `apply` rather than `commit`: nothing downstream reads this again before
     * the next process, and a fire-and-forget write is the difference between
     * this being callable from wherever the choice is made and it needing a
     * coroutine at every call site.
     */
    fun remember(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply { if (name.isNullOrBlank()) remove(KEY_CHOSEN) else putString(KEY_CHOSEN, name) }
            .apply()
    }

    /**
     * The remembered agent as it stands *today* — matched by name against the
     * live `agent_servers` list.
     *
     * Null for a name nobody recognises any more, and null for no name at all,
     * so a caller has one branch for "there is nothing to restore" and cannot
     * accidentally resurrect an entry the user deleted.
     */
    fun resolve(agents: List<AgentDefinition>, name: String?): AgentDefinition? {
        if (name.isNullOrBlank()) return null
        return agents.firstOrNull { it.name == name }
    }
}
