package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of what is left of Zed's `project_panel` — `hide_root`,
 * `indent_size`, `show_diagnostics`. The `preview_tabs` block and the
 * panel's `sort_mode`, `auto_fold_dirs` and `entry_spacing` are gone from
 * the model with the surfaces they configured (docs/UI.md, P8), and the
 * `tabs` block and `max_tabs` went with the tab strip in the 2026-09
 * demolition — this file was AppSettingsTabsTest until then.
 *
 * The engine parses the same file with serde and clamps it; this parser has to
 * agree with it on every default, because the two run against the same
 * settings.json and a disagreement shows up as a setting that looks like it
 * did nothing.
 */
class AppSettingsProjectPanelTest {

    @Test
    fun `an empty file is Zed's defaults`() {
        val settings = AppSettings.parse("{}")
        assertFalse(settings.projectPanel.hideRoot)
        assertEquals(20f, settings.projectPanel.indentSize, 0f)
        // `all` is the panel's default; Zed's tab strip defaulted the same
        // enum to `off`, but the panel is its only reader here.
        assertEquals(ShowDiagnostics.All, settings.projectPanel.showDiagnostics)
    }

    @Test
    fun `a tabs block nobody reads any more does not fail the parse`() {
        val settings = AppSettings.parse(
            """{ "tabs": { "close_position": "middle" }, "max_tabs": 6, "tab_size": 2 }"""
        )
        // The rest of the file survived it.
        assertEquals(2, settings.tabSize)
    }

    @Test
    fun `the project panel block is read`() {
        val settings = AppSettings.parse(
            """
            {
              "project_panel": {
                "hide_root": true,
                "indent_size": 32,
                "show_diagnostics": "off"
              }
            }
            """.trimIndent()
        )
        assertTrue(settings.projectPanel.hideRoot)
        assertEquals(32f, settings.projectPanel.indentSize, 0f)
        assertEquals(ShowDiagnostics.Off, settings.projectPanel.showDiagnostics)
    }

    @Test
    fun `indent_size is clamped the way the engine clamps it`() {
        assertEquals(4f, AppSettings.parse("""{"project_panel":{"indent_size":0}}""")
            .projectPanel.indentSize, 0f)
        assertEquals(64f, AppSettings.parse("""{"project_panel":{"indent_size":900}}""")
            .projectPanel.indentSize, 0f)
    }

    @Test
    fun `show_diagnostics decides what is marked`() {
        assertFalse(ShowDiagnostics.Off.marks(errors = 3, warnings = 0))
        assertTrue(ShowDiagnostics.Errors.marks(errors = 1, warnings = 0))
        assertFalse(ShowDiagnostics.Errors.marks(errors = 0, warnings = 9))
        assertTrue(ShowDiagnostics.All.marks(errors = 0, warnings = 1))
        assertFalse(ShowDiagnostics.All.marks(errors = 0, warnings = 0))
    }
}
