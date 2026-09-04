package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.ui.editor.SoftWrapMode

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
    }

    /**
     * The two defaults this app takes away from Zed (docs/UI.md, "Settings").
     *
     * Both are here rather than in the list above because both are a
     * *divergence* and the test is the record of it: `soft_wrap` because a
     * 400dp portrait column has no usable horizontal scroll, and `autosave`
     * because `cargo build-sbf` reads the file on disk and spends 71 seconds
     * telling you about a version of the program that is not on screen.
     */
    @Test
    fun theTwoDefaultsThisAppFlips() {
        val parsed = AppSettings.parse("{}")
        assertEquals(SoftWrapMode.EditorWidth, parsed.softWrap)
        assertEquals(Autosave.OnFocusChange, parsed.autosave)
        // The data class's own defaults have to agree with the parse of an
        // empty document, or a preview and a device disagree about wrapping.
        assertEquals(AppSettings().softWrap, parsed.softWrap)
        assertEquals(AppSettings().autosave, parsed.autosave)
    }

    /** A file that says so still gets Zed's answers — the flip is a default, not a policy. */
    @Test
    fun anExplicitValueStillWins() {
        assertEquals(
            SoftWrapMode.None,
            AppSettings.parse("""{"soft_wrap": "none"}""").softWrap,
        )
        assertEquals(Autosave.Off, AppSettings.parse("""{"autosave": "off"}""").autosave)
    }
}
