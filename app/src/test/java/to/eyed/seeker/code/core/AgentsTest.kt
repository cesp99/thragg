package to.eyed.seeker.code.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent model — data only, no vendors, nothing installable. */
class AgentsTest {

    /**
     * The two shapes a guest that cannot find a program says so in, and the
     * shape it must not be confused with: an agent's own error still gets the
     * plain-error rendering, not the missing-program sentence.
     */
    @Test
    fun aMissingProgramIsRecognisedByEitherSpelling() {
        assertTrue(Agents.looksLikeMissingProgram("sh: 1: claude-code-acp: not found"))
        assertTrue(Agents.looksLikeMissingProgram("execvp: No such file or directory"))
        assertTrue(Agents.looksLikeMissingProgram("bash: node: command not found"))
        assertFalse(Agents.looksLikeMissingProgram("Error: not authenticated"))
        assertFalse(Agents.looksLikeMissingProgram(null))
    }

    /**
     * The spec is parsed by serde on the Rust side, so it must be real JSON —
     * built with JSONObject, never by interpolation, because a configured
     * agent's fields are whatever someone typed into settings.json.
     */
    @Test
    fun theSpecCarriesTheWholeDefinition() {
        val agent = AgentDefinition(
            id = "custom:Mine",
            name = "Mine",
            argv = listOf("python3", "/root/agent.py", "--acp"),
            env = mapOf("API_KEY" to "secret"),
        )
        val json = JSONObject(agent.toSpecJson())
        assertEquals("Mine", json.getString("name"))
        assertEquals("python3", json.getJSONArray("argv").getString(0))
        assertEquals("--acp", json.getJSONArray("argv").getString(2))
        assertEquals("secret", json.getJSONObject("env").getString("API_KEY"))
    }
}
