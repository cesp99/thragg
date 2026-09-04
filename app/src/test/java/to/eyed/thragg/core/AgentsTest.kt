package to.eyed.thragg.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

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

    /**
     * The one substitution the bundled entry depends on. `agent_servers` is a
     * file people edit by hand, so the row is static and the project it points
     * at is resolved at launch — an unresolved token would reach the agent as
     * a relative path and `session/new` answers -32602 for one.
     */
    @Test
    fun theProjectRootTokenIsResolvedAtLaunch() {
        val agent = AgentDefinition(
            id = "custom:Spettro",
            name = "Spettro",
            argv = listOf(
                "/opt/seeker/agents/spettro/spettro",
                "-acp",
                "--cwd",
                Agents.PROJECT_ROOT_TOKEN,
            ),
            env = mapOf("HOME" to "/root", "SCRATCH" to "${Agents.PROJECT_ROOT_TOKEN}/.tmp"),
        )
        val launched = agent.forProjectRoot("/root/projects/escrow")
        assertEquals("/root/projects/escrow", launched.argv[3])
        assertEquals("/root/projects/escrow/.tmp", launched.env["SCRATCH"])
        // Everything else is untouched, the identity included.
        assertEquals("-acp", launched.argv[1])
        assertEquals("/root", launched.env["HOME"])
        assertEquals("Spettro", launched.name)
    }

    /** A hand-written entry with no token comes back as the same object. */
    @Test
    fun anEntryWithoutTheTokenIsUnchanged() {
        val agent = AgentDefinition(
            id = "custom:Mine",
            name = "Mine",
            argv = listOf("python3", "/root/agent.py"),
        )
        assertSame(agent, agent.forProjectRoot("/root/projects/escrow"))
    }

    /**
     * The failure with no symptom: an agent whose `$HOME` is not writable
     * starts, handshakes, answers — and silently forgets the API key that was
     * typed into it. The check has to name the directory, because "setup
     * failed" is not something anybody can act on.
     */
    @Test
    fun anUnusableHomeIsNamedRatherThanGuessedAt() {
        val root = createTempDirectory().toFile()
        val missing = File(root, "root")
        assertNotNull(Agents.homeProblem(missing))
        assertTrue(Agents.homeProblem(missing)!!.contains(Agents.GUEST_HOME))

        val file = File(root, "notadir").apply { writeText("") }
        assertNotNull(Agents.homeProblem(file))

        val home = File(root, "home").apply { mkdirs() }
        assertNull(Agents.homeProblem(home))
    }
}
