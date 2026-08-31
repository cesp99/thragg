package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Putting the user's chosen agent back after the process has been killed.
 *
 * The part worth testing off a device is the *resolution*, because that is
 * where a remembered choice can go wrong quietly: the name is all that is
 * stored, `agent_servers` is a file people hand-edit between launches, and a
 * lookup that guessed — matched loosely, fell back to the first entry — would
 * silently launch a different program from the one that was picked.
 */
class AgentChoiceTest {

    private fun agent(name: String) =
        AgentDefinition(id = "custom:$name", name = name, argv = listOf("/usr/bin/$name"))

    private val agents = listOf(agent("Spettro"), agent("my-agent"))

    @Test
    fun theRememberedNameComesBackAsItsEntry() {
        assertEquals(agents[1], AgentChoice.resolve(agents, "my-agent"))
    }

    /**
     * An entry renamed or deleted in settings.json resolves to nothing rather
     * than to something near it — the panel then behaves exactly as it does on
     * a fresh install, which is the only honest answer.
     */
    @Test
    fun aNameThatIsNoLongerConfiguredResolvesToNothing() {
        assertNull(AgentChoice.resolve(agents, "deleted-agent"))
        assertNull(AgentChoice.resolve(emptyList(), "Spettro"))
    }

    /** Never chosen, and never a fallback to whatever happens to be first. */
    @Test
    fun noRememberedNameIsNotTheFirstAgent() {
        assertNull(AgentChoice.resolve(agents, null))
        assertNull(AgentChoice.resolve(agents, ""))
        assertNull(AgentChoice.resolve(agents, "   "))
    }

    /**
     * The key is matched exactly. `agent_servers` keys are case-sensitive in
     * settings.json, so two entries differing only in case are two agents and
     * matching loosely would launch the wrong one.
     */
    @Test
    fun theNameIsMatchedExactly() {
        assertNull(AgentChoice.resolve(agents, "spettro"))
        assertNull(AgentChoice.resolve(agents, " Spettro"))
    }
}
