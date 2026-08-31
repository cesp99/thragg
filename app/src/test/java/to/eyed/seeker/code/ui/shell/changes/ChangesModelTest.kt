package to.eyed.seeker.code.ui.shell.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.AgentEditedFile
import to.eyed.seeker.code.core.AgentReview
import to.eyed.seeker.code.core.FileDiff
import to.eyed.seeker.code.core.GitChange
import to.eyed.seeker.code.core.GitFileStatus
import to.eyed.seeker.code.core.GitPanelState
import to.eyed.seeker.code.core.PatchHunk
import to.eyed.seeker.code.core.PatchLine

/**
 * The three blocks of the Changes route, as a value.
 *
 * Everything the screen decides is in [changesModel] and its neighbours,
 * precisely so the decisions are checkable without a device: which block a
 * file lands in, what its checkbox says, and what a discard is about to do —
 * the three answers a wrong composition would get wrong silently.
 */
class ChangesModelTest {

    private fun change(
        path: String,
        staged: GitFileStatus? = null,
        unstaged: GitFileStatus? = null,
        conflicted: Boolean = false,
        inHead: Boolean = true,
        original: String? = null,
    ) = GitChange(path, staged, unstaged, conflicted, inHead, original)

    private fun diff(path: String, added: Int, removed: Int): FileDiff {
        val lines = List(added) { PatchLine('+', "new", 0, it + 1) } +
            List(removed) { PatchLine('-', "old", it + 1, 0) }
        return FileDiff(
            path = path,
            original = null,
            isBinary = false,
            hunks = listOf(PatchHunk(oldStart = 1, newStart = 1, heading = "", lines = lines)),
        )
    }

    private fun review(vararg files: Pair<String, String>) = AgentReview(
        version = 1,
        files = files.map { (path, status) ->
            AgentEditedFile(
                path = path,
                status = status,
                created = false,
                deleted = false,
                diff = diff(path, added = 3, removed = 1),
            )
        },
    )

    private val status = GitPanelState(
        scanned = true,
        ran = true,
        hasRepo = true,
        entries = listOf(
            change("programs/escrow/src/state.rs", unstaged = GitFileStatus.Modified),
            change("tests/escrow.ts", staged = GitFileStatus.Modified),
            change("target/deploy/escrow.so", unstaged = GitFileStatus.Untracked, inHead = false),
            change("Anchor.toml", conflicted = true),
        ),
    )

    @Test
    fun aFileWaitingOnKeepOrRejectIsListedOnceAndOnlyInTheAgentBlock() {
        val model = changesModel(
            review("programs/escrow/src/state.rs" to "pending"),
            status,
            emptyMap(),
        )
        assertEquals(listOf("programs/escrow/src/state.rs"), model.agent.map { it.path })
        // The same file is a git change too — the agent wrote to the worktree
        // — and listing it twice would offer two different verbs for one
        // decision.
        assertTrue("programs/escrow/src/state.rs" !in model.git.map { it.change.path })
    }

    @Test
    fun aKeptFileIsAnOrdinaryChangeAgain() {
        val model = changesModel(
            review("programs/escrow/src/state.rs" to "kept"),
            status,
            emptyMap(),
        )
        assertEquals(emptyList<String>(), model.agent.map { it.path })
        assertTrue("programs/escrow/src/state.rs" in model.git.map { it.change.path })
    }

    @Test
    fun conflictsAreTheirOwnBlockAndNeverGetACheckbox() {
        val model = changesModel(AgentReview.NONE, status, emptyMap())
        assertEquals(listOf("Anchor.toml"), model.conflicts.map { it.path })
        assertTrue("Anchor.toml" !in model.git.map { it.change.path })
    }

    @Test
    fun stageAllTakesEverythingWithSomethingLeftToStage() {
        val model = changesModel(AgentReview.NONE, status, emptyMap())
        assertEquals(
            listOf(
                "programs/escrow/src/state.rs",
                "target/deploy/escrow.so",
                // A conflict's staging *is* its resolution, so it is in; the
                // wholly staged tests/escrow.ts has nothing left to add.
                "Anchor.toml",
            ),
            model.stageAll,
        )
    }

    @Test
    fun theCommitCountIsEveryStagedFileIncludingOnesTheAgentTouched() {
        val model = changesModel(
            review("tests/escrow.ts" to "pending"),
            status,
            emptyMap(),
        )
        // tests/escrow.ts is in the agent block, and still in the next commit.
        assertEquals(1, model.stagedCount)
        assertEquals(listOf("tests/escrow.ts"), model.stagedPaths)
    }

    @Test
    fun countsRideAlongWhereGitHasThemAndAreNullWhereItDoesNot() {
        val counts = diffCounts(listOf(diff("tests/escrow.ts", added = 4, removed = 0)))
        val model = changesModel(AgentReview.NONE, status, counts)
        val tests = model.git.first { it.change.path == "tests/escrow.ts" }
        assertEquals(4, tests.added)
        assertEquals(0, tests.removed)
        // An untracked file has no `git diff HEAD` entry at all, and the row
        // draws no numbers rather than "+0 −0".
        val untracked = model.git.first { it.change.path == "target/deploy/escrow.so" }
        assertEquals(null, untracked.added)
    }

    @Test
    fun theCheckboxTellsTheThreeStatesApart() {
        assertEquals(StageMark.Unstaged, stageMark(change("a", unstaged = GitFileStatus.Modified)))
        assertEquals(StageMark.Staged, stageMark(change("b", staged = GitFileStatus.Modified)))
        // Edited, staged, edited again: committing now commits the older text,
        // so this must not read as a tick.
        assertEquals(
            StageMark.Partial,
            stageMark(
                change("c", staged = GitFileStatus.Modified, unstaged = GitFileStatus.Modified)
            ),
        )
    }

    @Test
    fun theRowLettersAreGitsOwn() {
        assertEquals("M", statusLetter(change("a", unstaged = GitFileStatus.Modified)))
        assertEquals("?", statusLetter(change("b", unstaged = GitFileStatus.Untracked)))
        assertEquals("U", statusLetter(change("c", conflicted = true)))
        // The staged half answers for a file with nothing unstaged.
        assertEquals("A", statusLetter(change("d", staged = GitFileStatus.Added)))
    }

    @Test
    fun discardSaysWhichOfTheTwoThingsItIsAboutToDo() {
        assertTrue(
            "last commit" in discardWarning(change("a.rs", unstaged = GitFileStatus.Modified))
        )
        assertTrue(
            "trash" in discardWarning(
                change("b.rs", unstaged = GitFileStatus.Untracked, inHead = false)
            )
        )
        // A rename is both at once, and the confirmation names both names.
        val renamed = discardWarning(
            change("new.rs", staged = GitFileStatus.Renamed, original = "old.rs")
        )
        assertTrue("old.rs" in renamed && "new.rs" in renamed)
    }

    @Test
    fun theRemoteIsTheBranchesOwnFirstThenTheOnlyOneThenOrigin() {
        assertEquals("upstream", pickRemote("upstream", listOf("origin", "upstream")))
        assertEquals("fork", pickRemote(null, listOf("fork")))
        assertEquals("origin", pickRemote(null, listOf("fork", "origin")))
        // Nothing to push to is a real state on a project this app created,
        // and it is answered with a sentence rather than a picker.
        assertEquals(null, pickRemote(null, emptyList()))
        assertEquals(null, pickRemote(null, listOf("fork", "mirror")))
    }

    @Test
    fun anEmptyTreeIsAnEmptyModel() {
        val model = changesModel(AgentReview.NONE, GitPanelState(scanned = true, ran = true), emptyMap())
        assertTrue(model.isEmpty)
        assertEquals(0, model.stagedCount)
        assertEquals(emptyList<String>(), model.stageAll)
    }
}
