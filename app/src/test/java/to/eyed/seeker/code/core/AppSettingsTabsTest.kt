package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin half of Zed's `tabs`, `preview_tabs`, `max_tabs` and the rest of
 * `project_panel`.
 *
 * The engine parses the same file with serde and clamps it; this parser has to
 * agree with it on every default, because the two run against the same
 * settings.json and a disagreement shows up as a setting that looks like it
 * did nothing.
 */
class AppSettingsTabsTest {

    @Test
    fun `an empty file is Zed's defaults`() {
        val settings = AppSettings.parse("{}")
        assertEquals(ClosePosition.Right, settings.tabs.closePosition)
        assertFalse(settings.tabs.fileIcons)
        assertFalse(settings.tabs.gitStatus)
        assertEquals(ShowDiagnostics.Off, settings.tabs.showDiagnostics)
        assertEquals(ActivateOnClose.History, settings.tabs.activateOnClose)
        assertNull(settings.maxTabs)
        assertTrue(settings.previewTabs.enabled)
        assertTrue(settings.previewTabs.fromProjectPanel)
        // Zed's odd one out: the finder opens permanently by default.
        assertFalse(settings.previewTabs.fromFileFinder)
        assertTrue(settings.previewTabs.fromCodeNavigation)
        assertEquals(ProjectPanelSort.DirectoriesFirst, settings.projectPanel.sort)
        assertFalse(settings.projectPanel.hideRoot)
        assertTrue(settings.projectPanel.autoFoldDirs)
        assertEquals(EntrySpacing.Comfortable, settings.projectPanel.entrySpacing)
        assertEquals(20f, settings.projectPanel.indentSize, 0f)
        // `all` in the panel, `off` in the tabs — Zed's two different defaults
        // for the same enum.
        assertEquals(ShowDiagnostics.All, settings.projectPanel.showDiagnostics)
    }

    @Test
    fun `every tab key is read`() {
        val settings = AppSettings.parse(
            """
            {
              "tabs": {
                "close_position": "left",
                "file_icons": true,
                "git_status": true,
                "show_diagnostics": "errors",
                "activate_on_close": "neighbour"
              }
            }
            """.trimIndent()
        )
        assertEquals(ClosePosition.Left, settings.tabs.closePosition)
        assertTrue(settings.tabs.fileIcons)
        assertTrue(settings.tabs.gitStatus)
        assertEquals(ShowDiagnostics.Errors, settings.tabs.showDiagnostics)
        assertEquals(ActivateOnClose.Neighbour, settings.tabs.activateOnClose)
    }

    @Test
    fun `a half-written tabs block keeps the defaults for what it omits`() {
        val settings = AppSettings.parse("""{ "tabs": { "file_icons": true } }""")
        assertTrue(settings.tabs.fileIcons)
        assertEquals(ClosePosition.Right, settings.tabs.closePosition)
        assertEquals(ActivateOnClose.History, settings.tabs.activateOnClose)
    }

    @Test
    fun `a value nobody recognises falls back rather than failing the parse`() {
        val settings = AppSettings.parse(
            """{ "tabs": { "close_position": "middle" }, "tab_size": 2 }"""
        )
        assertEquals(ClosePosition.Right, settings.tabs.closePosition)
        // And the rest of the file survived it.
        assertEquals(2, settings.tabSize)
    }

    @Test
    fun `max_tabs is read, and zero means unlimited`() {
        assertEquals(6, AppSettings.parse("""{ "max_tabs": 6 }""").maxTabs)
        // The engine refuses a zero for the same reason: it would close every
        // tab as it opened.
        assertNull(AppSettings.parse("""{ "max_tabs": 0 }""").maxTabs)
        assertNull(AppSettings.parse("""{ "max_tabs": null }""").maxTabs)
    }

    @Test
    fun `preview routes follow the settings`() {
        val off = PreviewTabSettings(enabled = false)
        assertFalse(off.previews(PreviewRoute.ProjectPanel))
        val on = PreviewTabSettings(fromFileFinder = true)
        assertTrue(on.previews(PreviewRoute.FileFinder))
        assertTrue(on.previews(PreviewRoute.ProjectPanel))
        // A permanent open is never a preview, whatever the settings say.
        assertFalse(on.previews(PreviewRoute.Permanent))
    }

    @Test
    fun `the project panel block is read`() {
        val settings = AppSettings.parse(
            """
            {
              "project_panel": {
                "sort_mode": "mixed",
                "hide_root": true,
                "auto_fold_dirs": false,
                "entry_spacing": "standard",
                "indent_size": 32,
                "show_diagnostics": "off"
              }
            }
            """.trimIndent()
        )
        assertEquals(ProjectPanelSort.Mixed, settings.projectPanel.sort)
        assertTrue(settings.projectPanel.hideRoot)
        assertFalse(settings.projectPanel.autoFoldDirs)
        assertEquals(EntrySpacing.Standard, settings.projectPanel.entrySpacing)
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
