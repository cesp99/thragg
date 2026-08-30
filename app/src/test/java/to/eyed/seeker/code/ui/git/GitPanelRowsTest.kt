package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.GitChange
import to.eyed.seeker.code.core.GitFileStatus
import to.eyed.seeker.code.core.GitPanelState

/**
 * The flattening the panel's list is drawn from. It is worth its own test for
 * one reason above the rest: a file can legitimately appear twice, and two rows
 * with the same `LazyColumn` key is a crash rather than a cosmetic fault.
 */
class GitPanelRowsTest {

    private fun change(
        path: String,
        staged: GitFileStatus? = null,
        unstaged: GitFileStatus? = null,
        conflicted: Boolean = false,
        inHead: Boolean = true,
        original: String? = null,
    ) = GitChange(path, staged, unstaged, conflicted, inHead, original)

    private fun state(vararg changes: GitChange) =
        GitPanelState(scanned = true, hasRepo = true, entries = changes.toList())

    @Test
    fun sectionsComeInZedsOrderAndEmptyOnesAreAbsent() {
        val rows = gitPanelRows(
            state(
                change("staged.rs", staged = GitFileStatus.Modified),
                change("changed.rs", unstaged = GitFileStatus.Modified),
                change("conflict.rs", conflicted = true),
            )
        )
        assertEquals(
            listOf(
                "section:Conflicts",
                "Conflicts:conflict.rs",
                "section:Staged",
                "Staged:staged.rs",
                "section:Changes",
                "Changes:changed.rs",
            ),
            rows.map { it.key },
        )

        // Nothing conflicted: no conflicts header.
        val quiet = gitPanelRows(state(change("a.rs", unstaged = GitFileStatus.Modified)))
        assertEquals(listOf("section:Changes", "Changes:a.rs"), quiet.map { it.key })
    }

    @Test
    fun aFileInTwoSectionsGetsTwoDistinctKeys() {
        val rows = gitPanelRows(
            state(
                change("a.rs", staged = GitFileStatus.Modified, unstaged = GitFileStatus.Modified)
            )
        )
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue("Staged:a.rs" in keys)
        assertTrue("Changes:a.rs" in keys)
    }

    @Test
    fun aSectionCarriesEveryPathInItForItsStageAllAction() {
        val rows = gitPanelRows(
            state(
                change("a.rs", unstaged = GitFileStatus.Modified),
                change("b.rs", unstaged = GitFileStatus.Untracked),
                change("c.rs", staged = GitFileStatus.Added),
            )
        )
        val changes = rows.filterIsInstance<GitPanelRow.SectionRow>()
            .single { it.section == GitSection.Changes }
        assertEquals(listOf("a.rs", "b.rs"), changes.paths)

        val staged = rows.filterIsInstance<GitPanelRow.SectionRow>()
            .single { it.section == GitSection.Staged }
        assertEquals(listOf("c.rs"), staged.paths)
    }

    @Test
    fun aCleanProjectHasNoRows() {
        assertTrue(gitPanelRows(state()).isEmpty())
        assertTrue(gitPanelRows(GitPanelState()).isEmpty())
    }

    /**
     * Discard is offered from the ⋯ menu, from a right-click, from a long-press
     * and from `Delete`; a guard on one of them is a guard on none. All four go
     * through this, because the confirmation a conflict must never reach is the
     * one that promises "back to what the last commit holds" — and `git restore`
     * on an unmerged path keeps "ours", stages it, exits 0 and leaves the merge
     * half-done with nothing on screen to say so.
     */
    @Test
    fun aConflictIsRefusedBeforeTheConfirmationRatherThanDiscarded() {
        val conflict = change("f.txt", conflicted = true, inHead = true)
        val refusal = discardRefusal(conflict)
        assertTrue(refusal != null && "merge conflict" in refusal)

        // Everything else still gets the dialog.
        assertNull(discardRefusal(change("a.rs", unstaged = GitFileStatus.Modified)))
        assertNull(discardRefusal(change("new.rs", unstaged = GitFileStatus.Untracked, inHead = false)))
        assertNull(
            discardRefusal(
                change("renamed.txt", staged = GitFileStatus.Renamed, inHead = false, original = "a.txt")
            )
        )
    }

    /** What the item promises has to be what happens to *that* row. */
    @Test
    fun theDiscardItemIsNamedForWhatItDoesToThisRow() {
        assertEquals("Discard changes…", discardLabel(change("a.rs", unstaged = GitFileStatus.Modified)))
        assertEquals(
            "Move to the trash…",
            discardLabel(change("new.rs", unstaged = GitFileStatus.Untracked, inHead = false)),
        )
        assertEquals(
            "Move the folder to the trash…",
            discardLabel(change("src/", unstaged = GitFileStatus.Untracked, inHead = false)),
        )
        // A rename is a restore *and* a trash, and "discard changes" is neither.
        assertEquals(
            "Undo the rename…",
            discardLabel(
                change("renamed.txt", staged = GitFileStatus.Renamed, inHead = false, original = "a.txt")
            ),
        )
    }

    /**
     * The panel is removed from the composition by Escape, and on a compact
     * screen by opening a file. A commit message that lives only in that
     * composition is a message the user types twice.
     */
    @Test
    fun aTypedCommitMessageSurvivesThePanelClosing() {
        val project = 7L
        assertEquals("", CommitDrafts.of(project))
        CommitDrafts.put(project, "Fix the parser\n\nIt was reading the source record")
        assertEquals("Fix the parser\n\nIt was reading the source record", CommitDrafts.of(project))

        // Another project's draft is its own.
        assertEquals("", CommitDrafts.of(8L))

        // Cleared on a commit that landed, and by emptying the box.
        CommitDrafts.clear(project)
        assertEquals("", CommitDrafts.of(project))
        CommitDrafts.put(project, "typed")
        CommitDrafts.put(project, "")
        assertEquals("", CommitDrafts.of(project))
    }
}
