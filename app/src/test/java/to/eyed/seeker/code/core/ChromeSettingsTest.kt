package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zed's `tab_bar`, `toolbar`, `status_bar` and `command_aliases` blocks as
 * this side reads them. The engine's own tests cover the file; these cover
 * the Kotlin model the UI switches on.
 */
class ChromeSettingsTest {

    @Test
    fun everyPartOfTheChromeIsShownUntilItIsSwitchedOff() {
        val defaults = AppSettings()
        assertTrue(defaults.tabBar.show)
        assertTrue(defaults.tabBar.showNavHistoryButtons)
        assertTrue(defaults.tabBar.showTabBarButtons)
        assertTrue(defaults.toolbar.breadcrumbs)
        assertTrue(defaults.toolbar.quickActions)
        assertTrue(defaults.toolbar.selectionsMenu)
        assertTrue(defaults.statusBar.activeLanguageButton)
        assertTrue(defaults.statusBar.cursorPositionButton)
        // Parsing an empty file has to agree with the constructor's defaults,
        // or the settings screen shows one thing and the workspace draws
        // another.
        assertEquals(defaults.tabBar, AppSettings.parse("{}").tabBar)
        assertEquals(defaults.toolbar, AppSettings.parse("{}").toolbar)
        assertEquals(defaults.statusBar, AppSettings.parse("{}").statusBar)
    }

    @Test
    fun oneKeyMovesAndItsNeighboursKeepZedsDefault() {
        val settings = AppSettings.parse(
            """
            {
              "tab_bar": { "show_tab_bar_buttons": false },
              "toolbar": { "breadcrumbs": false },
              "status_bar": { "cursor_position_button": false }
            }
            """.trimIndent()
        )
        assertTrue(settings.tabBar.show)
        assertTrue(settings.tabBar.showNavHistoryButtons)
        assertFalse(settings.tabBar.showTabBarButtons)
        assertFalse(settings.toolbar.breadcrumbs)
        assertTrue(settings.toolbar.quickActions)
        assertTrue(settings.statusBar.activeLanguageButton)
        assertFalse(settings.statusBar.cursorPositionButton)
    }

    @Test
    fun aToolbarWithNothingLeftIsNotDrawnAtAll() {
        assertTrue(ToolbarSettings().isVisible)
        assertTrue(ToolbarSettings(breadcrumbs = false, quickActions = false).isVisible)
        assertFalse(
            ToolbarSettings(
                breadcrumbs = false,
                quickActions = false,
                selectionsMenu = false,
            ).isVisible
        )
    }

    @Test
    fun commandAliasesArriveSortedAndDropWhatIsNotAString() {
        val settings = AppSettings.parse(
            """{"command_aliases":{"term":"terminal_panel::Toggle","W":"workspace::Save","bad":""}}"""
        )
        assertEquals(
            listOf("W" to "workspace::Save", "term" to "terminal_panel::Toggle"),
            settings.commandAliases.toList(),
        )
        assertEquals(emptyMap<String, String>(), AppSettings.parse("{}").commandAliases)
    }
}
