package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.ui.editor.SoftWrapMode

/**
 * The editor keys the settings screen has rows for — `hard_tabs`,
 * `preferred_line_length`, `format_on_save`, `autosave` — read out of the
 * engine's resolved JSON, in the shapes Zed's default.json writes them.
 */
class AppSettingsEditorKeysTest {

    @Test
    fun readsTheEditorKeys() {
        val parsed = AppSettings.parse(
            """
            {
              "hard_tabs": true, "preferred_line_length": 120, "soft_wrap": "bounded",
              "format_on_save": "language_server",
              "autosave": {"after_delay": {"milliseconds": 500}}
            }
            """.trimIndent()
        )
        assertTrue(parsed.hardTabs)
        assertEquals(120, parsed.preferredLineLength)
        assertEquals(SoftWrapMode.Bounded, parsed.softWrap)
        assertEquals(FormatOnSave.LanguageServer, parsed.formatOnSave)
        assertEquals(Autosave.AfterDelay(500), parsed.autosave)
    }

    @Test
    fun autosaveTakesZedsFourShapes() {
        assertEquals(Autosave.Off, AppSettings.parse("""{"autosave": "off"}""").autosave)
        assertEquals(Autosave.OnFocusChange, AppSettings.parse("""{"autosave": "on_focus_change"}""").autosave)
        assertEquals(Autosave.OnWindowChange, AppSettings.parse("""{"autosave": "on_window_change"}""").autosave)
        assertEquals(
            Autosave.AfterDelay(1000),
            AppSettings.parse("""{"autosave": {"after_delay": {"milliseconds": 1000}}}""").autosave,
        )
        // A value that is none of them is off, never a crash.
        assertEquals(Autosave.Off, AppSettings.parse("""{"autosave": 7}""").autosave)
    }

    /** What the row writes has to be what the engine reads back. */
    @Test
    fun autosaveWritesTheShapeItReads() {
        for (value in listOf(Autosave.Off, Autosave.OnFocusChange, Autosave.OnWindowChange, Autosave.AfterDelay(1000))) {
            val parsed = AppSettings.parse("""{"autosave": ${value.toJson()}}""")
            assertEquals(value, parsed.autosave)
        }
    }

    @Test
    fun defaultsAreZeds() {
        val parsed = AppSettings.parse("{}")
        assertEquals(false, parsed.hardTabs)
        assertEquals(80, parsed.preferredLineLength)
        assertEquals(FormatOnSave.Off, parsed.formatOnSave)
        assertEquals(Autosave.Off, parsed.autosave)
    }
}
