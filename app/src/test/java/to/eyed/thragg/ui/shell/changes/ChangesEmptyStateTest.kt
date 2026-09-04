package to.eyed.thragg.ui.shell.changes

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.thragg.core.GitPanelState

/**
 * The empty list's one sentence, and the order that picks it — Conquest's
 * `theEmptyStateTellsNoRepoApartFromNoGit`, carried across with the function.
 */
class ChangesEmptyStateTest {

    @Test
    fun theEmptyStateTellsNoRepoApartFromNoGit() {
        // A project outside any repository never runs git at all — the engine
        // answers "no repository" from the host filesystem — so `ran` is
        // false there by design, and it must read as the Initialize
        // Repository state, not as a git that could not be run.
        val noRepo = GitPanelState(scanned = true, ran = false, hasRepo = false)
        assertEquals(ChangesEmptyState.NoRepo, changesEmptyState(noRepo))
        // A repository git could not be run *in* is the genuine no-git case.
        val noGit = GitPanelState(scanned = true, ran = false, hasRepo = true)
        assertEquals(ChangesEmptyState.NoGit, changesEmptyState(noGit))
        // The first scan still out claims nothing yet.
        assertEquals(ChangesEmptyState.Scanning, changesEmptyState(GitPanelState()))
        val clean = GitPanelState(scanned = true, ran = true, hasRepo = true)
        assertEquals(ChangesEmptyState.Clean, changesEmptyState(clean))
    }
}
