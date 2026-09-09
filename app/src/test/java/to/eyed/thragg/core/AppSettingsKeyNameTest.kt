package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-11, the half that was still broken: the notice never named the key, and
 * the agent went with the rest of the settings.
 *
 * On the device, `"theme": 12345` inserted after the opening brace and a
 * duplicated `"tab_size"` both produced the byte-identical sentence "it is
 * not valid JSON" — no key, no line — and after a relaunch the Agent tab read
 * "No agent yet" with an agent configured three lines below the bad one,
 * because `agent_servers` comes out of the same object the engine refused.
 */
class AppSettingsKeyNameTest {

    /** The engine's answer is its own defaults; the file is what names the key. */
    @Test
    fun aValueOfTheWrongTypeIsNamed() {
        val file = """{"theme": 12345, "tab_size": 4}"""
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false, fileText = file)
        assertEquals("`theme` is not a value that setting takes", loaded.problem)
    }

    @Test
    fun twoBadKeysAreBothNamedAndInAStableOrder() {
        val file = """{"tab_size": "four", "hard_tabs": "yes"}"""
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false, fileText = file)
        assertEquals(
            "`hard_tabs`, `tab_size` are not values those settings take",
            loaded.problem,
        )
    }

    /** A duplicated key is org.json's to name, and it does. */
    @Test
    fun aDuplicatedKeyIsNamedInTheNotice() {
        val file = """{"tab_size": 4, "soft_wrap": "none", "tab_size": 8}"""
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false, fileText = file)
        assertNotNull(loaded.problem)
        assertTrue(
            "the notice must name the key: ${loaded.problem}",
            loaded.problem!!.contains("tab_size"),
        )
    }

    /** A setting this app does not read is never blamed for the engine's refusal. */
    @Test
    fun anUnknownKeyIsNotBlamed() {
        val file = """{"vim_mode": 3, "tab_size": 4}"""
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false, fileText = file)
        assertEquals("it is not valid JSON", loaded.problem)
    }

    /** THE AGENT STAYS. The file reads here even when the engine will not take it. */
    @Test
    fun aFileTheEngineRefusedIsStillReadOnThisSide() {
        val file = """
            {
              "theme": 12345,
              "buffer_font_size": 18,
              "agent_servers": {"spettro": {"command": "spettro", "args": ["acp"]}}
            }
        """.trimIndent()
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false, fileText = file)

        assertTrue("the file was readable, so it must be what is in force", loaded.recovered)
        assertEquals(18f, loaded.settings.bufferFontSize, 0.001f)
        assertEquals(listOf("spettro"), loaded.settings.agents.map { it.name })
        assertNotNull(loaded.problem)
    }

    /** A file nobody can read is not "recovered", and the old sentence stands. */
    @Test
    fun aFileThatIsNotJsonAtAllIsNotRecovered() {
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false, fileText = "{{{")
        assertFalse(loaded.recovered)
        assertEquals(AppSettings(), loaded.settings)
        assertNotNull(loaded.problem)
    }

    /** A good file says nothing and recovers nothing. */
    @Test
    fun aGoodFileIsSilent() {
        val loaded = AppSettings.checkedFrom("""{"tab_size": 2}""", engineAccepted = true)
        assertNull(loaded.problem)
        assertFalse(loaded.recovered)
        assertEquals(2, loaded.settings.tabSize)
    }

    /**
     * JSONC: the settings file ships with a comment on its first line and the
     * user's own beside their keys, and the raw file is what this path reads.
     */
    @Test
    fun commentsInTheFileAreNotTheProblem() {
        val file = """
            // Thragg settings.
            {
              /* the look */
              "theme": 12345, // was a string
              "buffer_font_size": 15
            }
        """.trimIndent()
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false, fileText = file)
        assertEquals("`theme` is not a value that setting takes", loaded.problem)
        assertTrue(loaded.recovered)
        assertEquals(15f, loaded.settings.bufferFontSize, 0.001f)
    }

    /** A `//` inside a string is a URL, not a comment. */
    @Test
    fun aUrlInAValueSurvivesTheCommentStrip() {
        val text = """{"a": "https://example.com/x", "b": 1} // tail"""
        assertEquals("""{"a": "https://example.com/x", "b": 1} """, AppSettings.withoutComments(text))
    }
}
