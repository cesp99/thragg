package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The footer row's and empty state's little rules, checked against Zed's:
 * "View Branch Diff" hides only on the main branch (git_panel.rs:7049-7059),
 * the Uncommit meta words the flag by whether anything is unstaged
 * (git_panel.rs:6218-6231), and the pushed-commit confirmation names every
 * remote in one comma-joined sentence (git_panel.rs:3216-3228).
 */
class GitFooterTest {

    @Test
    fun branchDiffHidesOnlyOnTheMainBranch() {
        assertFalse(showsViewBranchDiff("main"))
        assertFalse(showsViewBranchDiff("master"))
        assertTrue(showsViewBranchDiff("feature/footer"))
        // Zed matches the exact lowercase names, nothing looser.
        assertTrue(showsViewBranchDiff("Main"))
        assertTrue(showsViewBranchDiff("main-2"))
        // A detached HEAD is on no branch, which is not the main one.
        assertTrue(showsViewBranchDiff(null))
    }

    @Test
    fun uncommitMetaWordsTheSoftFlagOnlyWithUnstagedChanges() {
        assertEquals("git reset HEAD^ --soft", uncommitMeta(hasUnstaged = true))
        assertEquals("git reset HEAD^", uncommitMeta(hasUnstaged = false))
    }

    @Test
    fun uncommitRunsOnlyAgainstTheCommitItRead() {
        // The engine's reset is a blind `HEAD^`; the pin is what keeps a
        // commit landing mid-flow — or while the pushed dialog sat open —
        // from being the one that gets reset.
        assertNull(uncommitPinRefusal(expected = "abc123", fresh = "abc123"))
        assertNotNull(uncommitPinRefusal(expected = "abc123", fresh = "def456"))
        // HEAD lost altogether — an unborn branch after an outside reset —
        // is no commit to uncommit, not a reset of whatever comes next.
        assertNotNull(uncommitPinRefusal(expected = "abc123", fresh = null))
    }

    @Test
    fun theEmptyStateTellsNoRepoApartFromNoGit() {
        // A project outside any repository never runs git at all — the engine
        // answers "no repository" from the host filesystem — so `ran` is
        // false there by design, and it must read as Zed's Initialize
        // Repository state, not as a missing git binary.
        val noRepo = to.eyed.seeker.code.core.GitPanelState(
            scanned = true, ran = false, hasRepo = false,
        )
        assertEquals(GitPanelEmptyState.NoRepo, gitPanelEmptyState(noRepo))
        // A repository git could not be run *in* is the genuine no-git case.
        val noGit = to.eyed.seeker.code.core.GitPanelState(
            scanned = true, ran = false, hasRepo = true,
        )
        assertEquals(GitPanelEmptyState.NoGit, gitPanelEmptyState(noGit))
        // The first scan still out claims nothing yet.
        assertEquals(
            GitPanelEmptyState.Scanning,
            gitPanelEmptyState(to.eyed.seeker.code.core.GitPanelState()),
        )
        val clean = to.eyed.seeker.code.core.GitPanelState(
            scanned = true, ran = true, hasRepo = true,
        )
        assertEquals(GitPanelEmptyState.Clean, gitPanelEmptyState(clean))
    }

    @Test
    fun anUnbornBranchWearsItsBareName() {
        // Zed shows just "main" after `git init` (git_panel.rs:8640-8654 with
        // repository.rs:2076-2094 — an unborn branch is still a named one);
        // the empty state's body already says nothing has been committed.
        val unborn = to.eyed.seeker.code.core.GitPanelState(
            scanned = true, ran = true, hasRepo = true,
            branch = to.eyed.seeker.code.core.GitBranch(name = "main", unborn = true),
        )
        assertEquals("main", branchLabel(unborn, head = null))
    }

    @Test
    fun theBranchLabelFallsBackAsZedDoes() {
        val detached = to.eyed.seeker.code.core.GitPanelState(
            scanned = true, ran = true, hasRepo = true,
            branch = to.eyed.seeker.code.core.GitBranch(name = null),
        )
        // A detached HEAD wears the first 8 characters of its sha.
        assertEquals("abc1234d", branchLabel(detached, head = "abc1234def"))
        // No branch and no commit at all is Zed's "(no branch)".
        assertEquals("(no branch)", branchLabel(detached, head = null))
        val noRepo = to.eyed.seeker.code.core.GitPanelState(scanned = true)
        assertEquals("No repository", branchLabel(noRepo, head = null))
    }

    @Test
    fun pushedDetailNamesEveryRemote() {
        assertEquals(
            "This commit was already pushed to origin/main.",
            uncommitPushedDetail(listOf("origin/main")),
        )
        assertEquals(
            "This commit was already pushed to origin/main, fork/main.",
            uncommitPushedDetail(listOf("origin/main", "fork/main")),
        )
    }
}
