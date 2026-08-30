package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's JSON and this parser have to agree, and nothing at build time
 * makes them: the contract is a string. So these are the shapes
 * `engine::GitChanges` actually serializes, spelled out — including the ones
 * that are easy to get wrong, where a field is `null` rather than absent.
 */
class GitPanelStateTest {

    @Test
    fun readsAFullSnapshot() {
        val state = GitPanelState.parse(
            """
            {"scanned":true,"has_repo":true,
             "branch":{"name":"main","ahead":2,"behind":1,"unborn":false},
             "entries":[
               {"path":"src/main.rs","staged":"modified","unstaged":null,
                "conflicted":false,"in_head":true},
               {"path":"new.rs","staged":null,"unstaged":"untracked",
                "conflicted":false,"in_head":false}]}
            """
        )
        assertTrue(state.scanned)
        assertTrue(state.hasRepo)
        assertEquals("main", state.branch?.name)
        assertEquals(2, state.branch?.ahead)
        assertEquals(1, state.branch?.behind)

        assertEquals(listOf("src/main.rs"), state.staged.map { it.path })
        assertEquals(listOf("new.rs"), state.unstaged.map { it.path })
        assertTrue(state.conflicts.isEmpty())
        assertFalse(state.isClean)

        // The one field the discard confirmation reads, and the one that
        // decides whether a file can be restored or has to be trashed.
        assertTrue(state.staged.first().inHead)
        assertFalse(state.unstaged.first().inHead)
    }

    /**
     * `MM`: staged, then edited again. It belongs in both sections, and a UI
     * that showed it once would be hiding that what is staged is no longer
     * what is on disk.
     */
    @Test
    fun aFileCanBeInBothSections() {
        val state = GitPanelState.parse(
            """{"scanned":true,"has_repo":true,"branch":null,"entries":[
                 {"path":"a.rs","staged":"modified","unstaged":"modified",
                  "conflicted":false,"in_head":true}]}"""
        )
        assertEquals(listOf("a.rs"), state.staged.map { it.path })
        assertEquals(listOf("a.rs"), state.unstaged.map { it.path })
    }

    @Test
    fun aConflictIsInNeitherSection() {
        val state = GitPanelState.parse(
            """{"scanned":true,"has_repo":true,"branch":null,"entries":[
                 {"path":"a.rs","staged":null,"unstaged":null,
                  "conflicted":true,"in_head":true}]}"""
        )
        assertTrue(state.staged.isEmpty())
        assertTrue(state.unstaged.isEmpty())
        assertEquals(listOf("a.rs"), state.conflicts.map { it.path })
    }

    @Test
    fun aDetachedHeadHasNoBranchName() {
        val state = GitPanelState.parse(
            """{"scanned":true,"has_repo":true,
                "branch":{"name":null,"ahead":0,"behind":0,"unborn":false},
                "entries":[]}"""
        )
        assertNull(state.branch?.name)
        assertTrue(state.isClean)
    }

    /**
     * "Nothing has changed" and "we have not asked yet" look identical in the
     * entries and are not the same thing to show anyone.
     */
    @Test
    fun anUnscannedProjectIsNotAcleanOne() {
        val state = GitPanelState.parse("""{"scanned":false,"has_repo":true,"entries":[]}""")
        assertFalse(state.scanned)
        assertTrue(state.hasRepo)
        assertTrue(state.isClean)
    }

    @Test
    fun anEmptyObjectIsNotARepository() {
        val state = GitPanelState.parse("{}")
        assertFalse(state.scanned)
        assertFalse(state.hasRepo)
        assertNull(state.branch)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun aPathSplitsIntoNameAndDirectory() {
        val nested = GitChange("src/deep/main.rs", null, GitFileStatus.Modified, false, true)
        assertEquals("main.rs", nested.name)
        assertEquals("src/deep", nested.directory)
        assertFalse(nested.isDirectory)

        val root = GitChange("README", null, GitFileStatus.Modified, false, true)
        assertEquals("README", root.name)
        assertEquals("", root.directory)
    }

    /**
     * `--untracked-files=normal` collapses a whole new directory into one
     * record with a trailing slash — the state right after starting new work.
     * Split like a file it has no name at all, and the row draws blank.
     */
    @Test
    fun anUntrackedDirectoryIsOneRowWithAName() {
        val state = GitPanelState.parse(
            """{"scanned":true,"has_repo":true,"branch":null,"entries":[
                 {"path":"src/feature/","staged":null,"unstaged":"untracked",
                  "conflicted":false,"in_head":false,"original":null}]}"""
        )
        val folder = state.unstaged.single()
        assertTrue(folder.isDirectory)
        // The slash stays in the label: "feature" and "feature/" are not the
        // same promise when the next tap discards one of them.
        assertEquals("feature/", folder.name)
        assertEquals("src", folder.directory)

        val top = GitChange("newdir/", null, GitFileStatus.Untracked, false, false)
        assertEquals("newdir/", top.name)
        assertEquals("", top.directory)
    }

    /**
     * A rename carries the name the last commit knows it by. Discarding one
     * cannot be done without it — the old name is restored and the new one, a
     * path HEAD has never held, goes to the trash.
     */
    @Test
    fun aRenameCarriesTheNameTheCommitKnows() {
        val state = GitPanelState.parse(
            """{"scanned":true,"has_repo":true,"branch":null,"entries":[
                 {"path":"renamed.txt","staged":"renamed","unstaged":"modified",
                  "conflicted":false,"in_head":false,"original":"a.txt"}]}"""
        )
        val renamed = state.staged.single()
        assertEquals("a.txt", renamed.original)
        // The new name is *not* in the commit, whatever git's letters look like.
        assertFalse(renamed.inHead)

        // And an ordinary row has no original at all, absent or null.
        val plain = GitPanelState.parse(
            """{"scanned":true,"has_repo":true,"entries":[
                 {"path":"a.rs","staged":null,"unstaged":"modified",
                  "conflicted":false,"in_head":true}]}"""
        )
        assertNull(plain.unstaged.single().original)
    }

    /**
     * "git could not run" and "the tree is clean" arrive as the same empty
     * list. A device whose Debian had no git was told its tree was clean.
     */
    @Test
    fun aStatusThatNeverRanIsNotACleanTree() {
        val neverRan = GitPanelState.parse("""{"scanned":true,"ran":false,"has_repo":true,"entries":[]}""")
        assertTrue(neverRan.scanned)
        assertFalse(neverRan.ran)
        assertTrue(neverRan.isClean)

        val ranAndClean = GitPanelState.parse("""{"scanned":true,"ran":true,"has_repo":true,"entries":[]}""")
        assertTrue(ranAndClean.ran)
    }
}
