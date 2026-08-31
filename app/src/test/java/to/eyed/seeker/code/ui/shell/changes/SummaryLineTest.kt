package to.eyed.seeker.code.ui.shell.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.seeker.code.core.GitChange
import to.eyed.seeker.code.core.GitFileStatus

/**
 * The two subtitles the changes package prints, pinned.
 *
 * Both are the second line of a `SeekerTopBar` — `3 files · +128 −47` over
 * Changes, `2 errors · 5 warnings` over Problems — and both are pure functions
 * for the reason every sentence in this package is one: the wording is the
 * product, the arithmetic behind it is easy to get subtly wrong, and neither
 * is worth a device round trip to check.
 *
 * The cases that earn their lines are the empty one (a bar with no subtitle at
 * all beats one that says "0 files"), the singular, and the file git has no
 * numbers for — an untracked one is a file and contributes nothing to the
 * totals, which is exactly what its row does.
 */
class SummaryLineTest {

    private fun change(path: String, unstaged: GitFileStatus? = GitFileStatus.Modified) =
        GitChange(
            path = path,
            staged = null,
            unstaged = unstaged,
            conflicted = false,
            inHead = true,
            original = null,
        )

    private fun gitRow(path: String) = GitChangeRow(
        change = change(path),
        mark = StageMark.Unstaged,
        added = null,
        removed = null,
    )

    private fun agentRow(path: String, added: Int, removed: Int) = AgentChangeRow(
        path = path,
        added = added,
        removed = removed,
        created = false,
        deleted = false,
    )

    private fun model(
        agent: List<AgentChangeRow> = emptyList(),
        git: List<GitChangeRow> = emptyList(),
        conflicts: List<GitChange> = emptyList(),
    ) = ChangesModel(
        agent = agent,
        git = git,
        conflicts = conflicts,
        stagedPaths = emptyList(),
        stageAll = emptyList(),
    )

    @Test
    fun `a clean tree has no subtitle rather than a zero`() {
        assertNull(changesSummary(model(), emptyMap()))
    }

    @Test
    fun `the counts are over every block and the noun agrees`() {
        val summary = changesSummary(
            model(
                agent = listOf(agentRow("programs/src/lib.rs", 24, 6)),
                git = listOf(gitRow("Anchor.toml"), gitRow("tests/escrow.ts")),
            ),
            mapOf(
                "Anchor.toml" to DiffCount(6, 41),
                "tests/escrow.ts" to DiffCount(2, 0),
            ),
        )
        assertEquals("3 files · +32 −47", summary)
    }

    @Test
    fun `one file is singular`() {
        val summary = changesSummary(
            model(git = listOf(gitRow("Anchor.toml"))),
            mapOf("Anchor.toml" to DiffCount(1, 0)),
        )
        assertEquals("1 file · +1 −0", summary)
    }

    @Test
    fun `a file git has no numbers for still counts as a file`() {
        // An untracked file is in `git status` and not in `git diff HEAD`, so
        // it has a row and no `+N −N` — and the totals say so by omitting the
        // pair entirely rather than printing `+0 −0`.
        assertEquals("1 file", changesSummary(model(git = listOf(gitRow("new.rs"))), emptyMap()))
    }

    @Test
    fun `problems says nothing about a project with no problems`() {
        assertNull(problemsSummary(errors = 0, warnings = 0, total = 0))
    }

    @Test
    fun `problems counts both severities and agrees in number`() {
        assertEquals("2 errors · 5 warnings", problemsSummary(2, 5, 7))
        assertEquals("1 error · 1 warning", problemsSummary(1, 1, 2))
    }

    @Test
    fun `hints alone are still problems`() {
        // Infos and hints are neither errors nor warnings, and a subtitle that
        // said "0 errors · 0 warnings" over a list of three rows would be a
        // header that contradicts the screen under it.
        assertEquals("3 problems", problemsSummary(errors = 0, warnings = 0, total = 3))
        assertEquals("1 problem", problemsSummary(errors = 0, warnings = 0, total = 1))
    }
}
