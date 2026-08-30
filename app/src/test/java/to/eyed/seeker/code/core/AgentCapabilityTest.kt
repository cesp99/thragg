package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the panel is allowed to offer, from what the agent said at
 * `initialize`.
 *
 * Every one of these gates a method that is an error to call unasked, so
 * reading them wrong shows up as a button that does nothing — the worst kind
 * of bug to find on a phone.
 */
class AgentCapabilityTest {

    private fun state(agent: String) = AgentSessionState.parse(
        """{"version":1,"phase":"ready","agent":$agent}""",
    )

    /** An agent that says nothing can do nothing extra. */
    @Test
    fun anAgentThatSaysNothingIsOfferedNothing() {
        val caps = state("""{"name":"x"}""").agent!!.capabilities
        assertFalse(caps.list)
        assertFalse(caps.canOpenHistory)
        assertFalse(caps.hasHistory)
        assertFalse(caps.logout)
    }

    /**
     * Listing without a way to reopen is a list of rows that do nothing, so
     * the history view needs both halves.
     */
    @Test
    fun historyNeedsBothListingAndReopening() {
        val listOnly = state("""{"capabilities":{"list":true}}""").agent!!.capabilities
        assertTrue(listOnly.list)
        assertFalse("listing alone is not a history view", listOnly.hasHistory)

        val loadOnly = state("""{"capabilities":{"load_session":true}}""").agent!!.capabilities
        assertTrue(loadOnly.canOpenHistory)
        assertFalse("reopening alone has nothing to reopen", loadOnly.hasHistory)

        val both = state(
            """{"capabilities":{"list":true,"resume":true}}""",
        ).agent!!.capabilities
        assertTrue(both.hasHistory)
        assertFalse("resume is not load", both.loadSession)
    }

    /**
     * The schema makes the plain `agent` method an *untagged* variant, so a
     * method with no `type` is not an unknown kind — it is the ordinary one.
     */
    @Test
    fun anAuthMethodWithNoTypeIsTheOrdinaryKind() {
        val method = state(
            """{"auth_methods":[{"methodId":"oauth","name":"Sign in"}]}""",
        ).agent!!.authMethods.single()
        assertEquals("agent", method.type)
        assertFalse(method.isTerminal)
    }

    /**
     * A terminal method carries the extra arguments and environment the
     * agent's own command is to be run with. Losing either would open a
     * terminal that runs the agent normally instead of its login flow.
     */
    @Test
    fun aTerminalAuthMethodKeepsItsArgumentsAndEnvironment() {
        val method = state(
            """{"auth_methods":[{
                 "type":"terminal","id":"login","name":"Sign in",
                 "args":["--login","--verbose"],"env":{"NO_COLOR":"1"}}]}""",
        ).agent!!.authMethods.single()
        assertTrue(method.isTerminal)
        assertEquals("login", method.id)
        assertEquals(listOf("--login", "--verbose"), method.args)
        assertEquals(mapOf("NO_COLOR" to "1"), method.env)
    }

    /** A past conversation with no title is named after its directory. */
    @Test
    fun aPastSessionWithoutATitleIsNamedAfterItsDirectory() {
        val list = AgentSessionList.parse(
            """{"version":3,"sessions":[
                 {"sessionId":"a","cwd":"/home/me/projects/thing"},
                 {"sessionId":"b","cwd":"/x","title":"Refactor the parser"}]}""",
        )
        assertEquals(3L, list.version)
        assertEquals("thing", list.sessions[0].label)
        assertEquals("Refactor the parser", list.sessions[1].label)
    }

    /** A row with no id could never be reopened, so it is not a row. */
    @Test
    fun aPastSessionWithoutAnIdIsDropped() {
        val list = AgentSessionList.parse("""{"version":1,"sessions":[{"cwd":"/x"}]}""")
        assertTrue(list.sessions.isEmpty())
    }
}
