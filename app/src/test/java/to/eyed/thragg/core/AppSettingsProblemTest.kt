package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-11 — one bad key must not revert every setting *in silence*.
 *
 * On the device: a duplicated key in `files/settings.json`, a force-stop and a
 * relaunch put the whole app back on the defaults — Agent showing "No agent
 * yet" with a configured agent, Format-on-save reading OFF — with nothing
 * anywhere saying why, and deleting the line put it all back. Two layers can
 * fall back and neither used to speak: the engine (`Engine::settings` logs a
 * warning and returns `Settings::default()`) and this file's own `runCatching`.
 *
 * [AppSettings.checkedFrom] is that pair, made a value: the settings the app
 * will run on, and the phrase that says why they are not the file's.
 */
class AppSettingsProblemTest {

    @Test
    fun aFileTheEngineRefusedIsReported() {
        // The engine hands back its defaults, which parse perfectly — so the
        // JSON in front of us is no evidence at all, and this is the only
        // question that catches it.
        val loaded = AppSettings.checkedFrom("{}", engineAccepted = false)
        assertEquals("it is not valid JSON", loaded.problem)
        assertEquals(AppSettings(), loaded.settings)
    }

    @Test
    fun aDuplicatedKeyIsReportedRatherThanSwallowed() {
        val loaded = AppSettings.checkedFrom(
            """{"buffer_font_size": 14, "buffer_font_size": 18}""",
            engineAccepted = true,
        )
        assertNotNull("a duplicate key must not pass unremarked", loaded.problem)
        assertEquals(AppSettings(), loaded.settings)
    }

    @Test
    fun textThatIsNotJsonAtAllIsReported() {
        val loaded = AppSettings.checkedFrom("this is not json", engineAccepted = true)
        assertNotNull(loaded.problem)
        assertTrue(loaded.problem!!.isNotBlank())
    }

    @Test
    fun aGoodFileHasNothingToSay() {
        val loaded = AppSettings.checkedFrom(
            """{"buffer_font_size": 18, "hard_tabs": true}""",
            engineAccepted = true,
        )
        assertNull(loaded.problem)
        assertEquals(18f, loaded.settings.bufferFontSize, 0.001f)
        assertTrue(loaded.settings.hardTabs)
    }

    /**
     * No engine and no file yet is not a failure: the defaults *are* the
     * truth on a fresh install, and a complaint there would be the first
     * thing a new user saw.
     */
    @Test
    fun anEmptyAnswerIsNotAComplaint() {
        assertNull(AppSettings.checkedFrom("", engineAccepted = true).problem)
        assertNull(AppSettings.checkedFrom("   ", engineAccepted = true).problem)
    }

    /** The plain reader keeps its old contract: never throws, defaults on junk. */
    @Test
    fun parseStillNeverThrows() {
        assertEquals(AppSettings(), AppSettings.parse("not json"))
        assertEquals(AppSettings(), AppSettings.parse(""))
    }
}
