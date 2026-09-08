package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Zed's `command_aliases` block as this side reads it. The engine's own
 * tests cover the file; this covers the Kotlin model.
 *
 * This file used to be ChromeSettingsTest and also pinned `tab_bar`,
 * `toolbar` and `status_bar`; those blocks left the model with the chrome
 * they configured (docs/UI.md, "What is removed"), and a test for a key
 * nothing reads is a test that a default is a default.
 */
class CommandAliasesSettingsTest {

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
