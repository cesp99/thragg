package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.terminal.TerminalSessionHost

/**
 * The `terminal` section as the engine hands it over, and the environment a
 * session builds from it. The engine's `config.rs` has the mirror test
 * (`the_terminal_section_is_read_and_clamped`) over the same keys.
 */
class AppSettingsTerminalTest {

    @Test
    fun readsTheThreeKeysUnderZedsNames() {
        val parsed = AppSettings.parse(
            """{"terminal":{"working_directory":"current_file_directory",
                "env":{"EDITOR":"vi"},"max_scroll_history_lines":20000}}"""
        )
        assertEquals(TerminalWorkingDirectory.CurrentFileDirectory, parsed.terminal.workingDirectory)
        assertEquals(mapOf("EDITOR" to "vi"), parsed.terminal.env)
        assertEquals(20000, parsed.terminal.scrollbackLines)
    }

    @Test
    fun missingSectionIsZedsDefaults() {
        val parsed = AppSettings.parse("""{"theme":"dark"}""")
        assertEquals(TerminalSettings(), parsed.terminal)
        assertEquals(TerminalWorkingDirectory.CurrentProjectDirectory, parsed.terminal.workingDirectory)
        assertEquals(10_000, parsed.terminal.scrollbackLines)
        assertTrue(parsed.terminal.env.isEmpty())
    }

    /**
     * The engine allows Zed's 100 000; the vendored emulator would answer
     * anything above its own 50 000 with its 2 000 default, silently.
     */
    @Test
    fun scrollbackIsClampedToTheEmulatorsBounds() {
        val big = AppSettings.parse("""{"terminal":{"max_scroll_history_lines":100000}}""")
        assertEquals(AppSettings.MAX_SCROLLBACK_LINES, big.terminal.scrollbackLines)
        val small = AppSettings.parse("""{"terminal":{"max_scroll_history_lines":1}}""")
        assertEquals(AppSettings.MIN_SCROLLBACK_LINES, small.terminal.scrollbackLines)
    }

    @Test
    fun anUnknownDirectoryNameFallsBackToTheProject() {
        val parsed = AppSettings.parse("""{"terminal":{"working_directory":"somewhere"}}""")
        assertEquals(TerminalWorkingDirectory.CurrentProjectDirectory, parsed.terminal.workingDirectory)
    }

    /** The user's `PATH` replaces the app's rather than sitting behind it. */
    @Test
    fun userEnvironmentReplacesTheAppsEntryOfTheSameName() {
        val merged = TerminalSessionHost.mergeEnvironment(
            listOf("HOME=/data/home", "PATH=/app/bin", "TERM=xterm-256color"),
            listOf("PATH=/usr/bin", "EDITOR=vi"),
        )
        assertEquals(listOf("HOME=/data/home", "TERM=xterm-256color", "PATH=/usr/bin", "EDITOR=vi"), merged)
        assertEquals(listOf("A=1"), TerminalSessionHost.mergeEnvironment(listOf("A=1"), emptyList()))
    }
}
