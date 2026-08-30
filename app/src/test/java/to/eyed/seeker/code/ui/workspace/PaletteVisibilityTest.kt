package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette's one rule about what is listed and what is greyed.
 *
 * Hidden is for what this *build* cannot do — cloning with no Linux userland,
 * an agent thread in an edition with no agent panel. Unavailable is for what
 * cannot happen *right now*, and it is listed and greyed, never hidden,
 * because a command that vanishes when you cannot use it is one you never
 * learn exists.
 */
class PaletteVisibilityTest {

    /** Nothing open, nothing running, but every capability present. */
    private fun idle() = CommandContext(
        hasProject = false,
        hasActiveFile = false,
        hasActiveBuffer = false,
        tabCount = 0,
        terminalCount = 0,
        canClone = true,
        canInstallLanguageServer = true,
        canUseAgent = true,
    )

    /** The same build with no userland: the capabilities are gone. */
    private fun withoutUserland() = idle().copy(
        canClone = false,
        canInstallLanguageServer = false,
        canUseAgent = false,
    )

    @Test
    fun everyCommandThisBuildCanPerformIsListedEvenWhenItCannotRun() {
        val entries = paletteEntries(idle())
        val listed = entries.map { it.command }.toSet()
        val missing = WorkspaceCommand.entries.filter { it !in listed }
        // With every capability present, nothing may be hidden — whatever the
        // moment looks like.
        assertEquals(emptyList<WorkspaceCommand>(), missing)
        // …and the ones that need something are greyed rather than gone.
        assertFalse(entries.first { it.command == WorkspaceCommand.Save }.isEnabled)
        assertFalse(entries.first { it.command == WorkspaceCommand.CloseTab }.isEnabled)
    }

    @Test
    fun aBuildWithoutAUserlandHidesOnlyWhatItCannotEverDo() {
        val listed = paletteEntries(withoutUserland()).map { it.command }.toSet()
        val hidden = WorkspaceCommand.entries.filter { it !in listed }
        assertTrue(hidden.isNotEmpty())
        // Everything hidden is hidden because a *capability* is absent, never
        // because of the state of the workspace.
        for (command in hidden) {
            assertTrue(
                "${command.id} is hidden for a reason that is not a build capability",
                command.isOffered(idle()),
            )
        }
    }

    @Test
    fun removingAFolderIsGreyedRatherThanHiddenWithOneFolder() {
        // It used to hide itself, which meant you had to add a folder before
        // the palette would admit that removing one was possible.
        val entries = paletteEntries(idle())
        val row = entries.first { it.command == WorkspaceCommand.RemoveFolderFromProject }
        assertFalse(row.isEnabled)
        assertTrue(
            paletteEntries(idle().copy(hasExtraFolders = true))
                .first { it.command == WorkspaceCommand.RemoveFolderFromProject }
                .isEnabled
        )
    }

    @Test
    fun everyCommandHasAHumanisedNameAndNoTwoShareOne() {
        val entries = paletteEntries(idle())
        assertTrue(entries.all { it.name.isNotBlank() })
        val duplicates = entries.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), duplicates)
    }
}
