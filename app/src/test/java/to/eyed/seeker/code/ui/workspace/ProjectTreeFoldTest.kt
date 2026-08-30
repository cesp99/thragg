package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.seeker.code.core.ProjectEntry

/**
 * `auto_fold_dirs`: a run of single-child directories is drawn as one `a/b/c`
 * row — Zed's rule (project_panel.rs:4418-4432), which skips a directory whose
 * only child is another directory and labels the chain's deepest member with
 * the whole path.
 *
 * The rule has three edges worth pinning, and all three are what makes a tree
 * *wrong* when they are missed: a directory with a file beside its subfolder is
 * not foldable, an unscanned directory must not be folded into (nobody has
 * looked at its children), and a chain must terminate.
 */
class ProjectTreeFoldTest {

    private fun dir(path: String, unloaded: Boolean = false, ignored: Boolean = false) =
        ProjectEntry(
            path = path,
            name = path.substringAfterLast('/'),
            isDir = true,
            isIgnored = ignored,
            isHidden = false,
            isUnloaded = unloaded,
            size = 0L,
        )

    private fun file(path: String) = ProjectEntry(
        path = path,
        name = path.substringAfterLast('/'),
        isDir = false,
        isIgnored = false,
        isHidden = false,
        isUnloaded = false,
        size = 1L,
    )

    /** A directory reader over a fixed tree; anything unlisted is empty. */
    private fun tree(vararg entries: Pair<String, List<ProjectEntry>>): (String) -> List<ProjectEntry> {
        val map = entries.toMap()
        return { path -> map[path].orEmpty() }
    }

    @Test
    fun `a chain of only-child directories folds into one row`() {
        val folded = foldDirectoryChain(
            dir("src"),
            tree(
                "src" to listOf(dir("src/main")),
                "src/main" to listOf(dir("src/main/java")),
                "src/main/java" to listOf(file("src/main/java/App.kt")),
            ),
        )
        assertEquals("src/main/java", folded.entry.path)
        assertEquals("src/main/java", folded.label)
    }

    @Test
    fun `a directory with two children does not fold`() {
        val folded = foldDirectoryChain(
            dir("src"),
            tree("src" to listOf(dir("src/main"), file("src/lib.rs"))),
        )
        assertEquals("src", folded.entry.path)
        assertEquals("src", folded.label)
    }

    @Test
    fun `a directory whose only child is a file does not fold`() {
        // The row would then claim to be a directory the user cannot expand
        // into anything but that one file.
        val folded = foldDirectoryChain(
            dir("src"),
            tree("src" to listOf(file("src/main.rs"))),
        )
        assertEquals("src", folded.entry.path)
    }

    @Test
    fun `folding stops at a directory the worktree has not scanned`() {
        // Its children are unknown; folding past it would claim something
        // about entries nobody has looked at.
        val folded = foldDirectoryChain(
            dir("target"),
            tree("target" to listOf(dir("target/debug", unloaded = true))),
        )
        assertEquals("target", folded.entry.path)
    }

    @Test
    fun `folding stops at a gitignored directory`() {
        val folded = foldDirectoryChain(
            dir("build"),
            tree("build" to listOf(dir("build/intermediates", ignored = true))),
        )
        assertEquals("build", folded.entry.path)
    }

    @Test
    fun `a file is never folded`() {
        val folded = foldDirectoryChain(file("README.md"), tree())
        assertEquals("README.md", folded.entry.path)
        assertEquals("README.md", folded.label)
    }

    @Test
    fun `an unscanned directory is left alone`() {
        val entry = dir("node_modules", unloaded = true)
        assertEquals(entry, foldDirectoryChain(entry, tree()).entry)
    }

    @Test
    fun `a cycle terminates rather than folding forever`() {
        // A symlink the worktree followed can make a directory its own only
        // child. The walk is capped, so this returns rather than hanging.
        val looping = dir("loop")
        val folded = foldDirectoryChain(looping) { listOf(looping) }
        assertEquals("loop", folded.entry.path)
        // 64 steps of "loop" appended to the first: the cap, not an infinity.
        assertEquals(65, folded.label.split('/').size)
    }
}
