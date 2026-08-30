package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.GitChange
import to.eyed.seeker.code.core.GitFileStatus

/**
 * The split button's two pure halves: the four-label state machine the button
 * is titled by, and the path filter "Commit Tracked" stages before it commits.
 * Both are transcriptions of Zed — `commit_button_title()`
 * (git_panel.rs:5642-5656) and the `!is_created()` filter inside
 * `commit_changes()` (git_panel.rs:3103-3107) — and a transcription is exactly
 * the kind of code that drifts one branch at a time.
 */
class CommitPickerTest {

    private fun change(
        path: String,
        staged: GitFileStatus? = null,
        unstaged: GitFileStatus? = null,
        conflicted: Boolean = false,
        inHead: Boolean = true,
        original: String? = null,
    ) = GitChange(path, staged, unstaged, conflicted, inHead, original)

    /** Zed's table, row for row — there are exactly four labels. */
    @Test
    fun theButtonHasExactlyFourTitles() {
        // Not amending: staging anything is a plain "Commit" of the index.
        assertEquals("Commit", commitButtonLabel(amendPending = false, hasStaged = true, hasTracked = true))
        assertEquals("Commit", commitButtonLabel(amendPending = false, hasStaged = true, hasTracked = false))
        // Nothing staged promises the stage-tracked-first flow — even over a
        // clean tree, where the label still reads "Commit Tracked" and the
        // enabled logic is what says no (git_panel.rs:5646-5650).
        assertEquals("Commit Tracked", commitButtonLabel(amendPending = false, hasStaged = false, hasTracked = true))
        assertEquals("Commit Tracked", commitButtonLabel(amendPending = false, hasStaged = false, hasTracked = false))

        // Amending with something staged amends the index.
        assertEquals("Amend", commitButtonLabel(amendPending = true, hasStaged = true, hasTracked = true))
        assertEquals("Amend", commitButtonLabel(amendPending = true, hasStaged = true, hasTracked = false))
        // Nothing staged but tracked changes exist: they will be staged first.
        assertEquals("Amend Tracked", commitButtonLabel(amendPending = true, hasStaged = false, hasTracked = true))
        // Nothing at all: an amend needs no changes, so it is plain "Amend".
        assertEquals("Amend", commitButtonLabel(amendPending = true, hasStaged = false, hasTracked = false))
    }

    /**
     * "Commit Tracked" must never sweep an untracked file into the commit —
     * that is the entire difference between it and `git commit -a` plus
     * `git add .`, and Zed's filter is `!status.is_created()`.
     */
    @Test
    fun commitTrackedStagesEveryChangeExceptTheCreatedOnes() {
        val paths = trackedCommitPaths(
            listOf(
                change("modified.rs", unstaged = GitFileStatus.Modified),
                change("staged-mod.rs", staged = GitFileStatus.Modified),
                change("deleted.rs", unstaged = GitFileStatus.Deleted),
                change("renamed.rs", staged = GitFileStatus.Renamed, inHead = false, original = "old.rs"),
                // The created ones, in every spelling the pair has.
                change("untracked.rs", unstaged = GitFileStatus.Untracked, inHead = false),
                change("staged-new.rs", staged = GitFileStatus.Added, inHead = false),
                change("newdir/", unstaged = GitFileStatus.Untracked, inHead = false),
            )
        )
        assertEquals(listOf("modified.rs", "staged-mod.rs", "deleted.rs", "renamed.rs"), paths)
    }

    /**
     * A conflict passes Zed's `!is_created()` filter — Unmerged is neither
     * untracked nor added — and the conflicts guard is what keeps it from
     * mattering, having turned the commit back before the list is built.
     */
    @Test
    fun aConflictPassesTheCommitFilterButIsNotATrackedChange() {
        val conflict = change("both.rs", conflicted = true)
        assertFalse(isCreatedChange(conflict))
        assertEquals(listOf("both.rs"), trackedCommitPaths(listOf(conflict)))
        // …while the *label*'s predicate buckets it as a conflict, not as
        // tracked (git_panel.rs:5129-5139): a tree holding only a conflict has
        // no tracked changes.
        assertFalse(hasTrackedChanges(listOf(conflict)))
    }

    @Test
    fun trackedMeansNeitherConflictedNorCreated() {
        assertTrue(hasTrackedChanges(listOf(change("a.rs", unstaged = GitFileStatus.Modified))))
        assertTrue(hasTrackedChanges(listOf(change("d.rs", staged = GitFileStatus.Deleted))))
        assertFalse(hasTrackedChanges(emptyList()))
        assertFalse(
            hasTrackedChanges(
                listOf(
                    change("new.rs", unstaged = GitFileStatus.Untracked, inHead = false),
                    change("added.rs", staged = GitFileStatus.Added, inHead = false),
                )
            )
        )
    }

    /**
     * Entering amend saves the draft it displaces; cancelling — or the amend
     * landing — hands it back. Per project, as the drafts themselves are: a
     * pending amend in one repository is no business of another's.
     */
    @Test
    fun aPendingAmendKeepsTheDraftItDisplaced() {
        val project = 41L
        assertFalse(AmendDrafts.pending(project))
        AmendDrafts.enter(project, "half a message")
        assertTrue(AmendDrafts.pending(project))
        assertEquals("half a message", AmendDrafts.original(project))

        // Another project has no amend pending.
        assertFalse(AmendDrafts.pending(42L))
        assertEquals("", AmendDrafts.original(42L))

        AmendDrafts.clear(project)
        assertFalse(AmendDrafts.pending(project))
        assertEquals("", AmendDrafts.original(project))

        // An empty editor is a legitimate original: pending all the same, and
        // cancelling restores the emptiness rather than keeping HEAD's text.
        AmendDrafts.enter(project, "")
        assertTrue(AmendDrafts.pending(project))
        assertEquals("", AmendDrafts.original(project))
        AmendDrafts.clear(project)
    }
}
