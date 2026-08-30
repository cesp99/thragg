package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.seeker.code.core.GitChange
import to.eyed.seeker.code.core.GitFileStatus

/**
 * What the bulk stages send — Zed's `git add --all` and `git reset`
 * (git_panel.rs:5741-5743), spelled as paths. The case worth pinning is the
 * `MM` file: staged *and* modified again, it must be in both answers.
 */
class GitStageAllPathsTest {

    private fun change(
        path: String,
        staged: GitFileStatus? = null,
        unstaged: GitFileStatus? = null,
        conflicted: Boolean = false,
    ) = GitChange(path, staged, unstaged, conflicted, inHead = true, original = null)

    private val entries = listOf(
        change("staged.rs", staged = GitFileStatus.Modified),
        change("both.rs", staged = GitFileStatus.Modified, unstaged = GitFileStatus.Modified),
        change("changed.rs", unstaged = GitFileStatus.Modified),
        change("new.rs", unstaged = GitFileStatus.Untracked),
        change("conflict.rs", conflicted = true),
    )

    @Test
    fun stageAllTakesEverythingWithAnythingLeftToStage() {
        // The wholly staged file has nothing to add; the conflict's staging
        // *is* its resolution, so it is in.
        assertEquals(
            listOf("both.rs", "changed.rs", "new.rs", "conflict.rs"),
            stageAllPaths(entries),
        )
    }

    @Test
    fun unstageAllTakesEveryStagedHalf() {
        assertEquals(listOf("staged.rs", "both.rs"), unstageAllPaths(entries))
    }

    @Test
    fun emptyEntriesAnswerEmptyRatherThanRunningGitOnNothing() {
        assertEquals(emptyList<String>(), stageAllPaths(emptyList()))
        assertEquals(emptyList<String>(), unstageAllPaths(emptyList()))
    }
}
