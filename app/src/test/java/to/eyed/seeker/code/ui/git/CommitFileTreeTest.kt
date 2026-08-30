package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.CommitFile

/**
 * The sidebar's changed-files tree: which rows exist, in what order, at what
 * depth — the three things a tree gets subtly wrong, pinned as arithmetic.
 */
class CommitFileTreeTest {

    private fun file(path: String, status: Char = 'M') =
        CommitFile(status = status, path = path, original = null)

    @Test
    fun rootFilesAreRowsAtDepthZero() {
        val rows = commitFileTree(listOf(file("README.md"), file("build.rs")), emptySet())
        assertEquals(listOf("README.md", "build.rs"), rows.map { it.key })
        assertTrue(rows.all { it is CommitTreeFile && it.depth == 0 })
    }

    /** Directories first, then files, both in path order — Zed's walk. */
    @Test
    fun directoriesComeBeforeFilesAndBothSort() {
        val rows = commitFileTree(
            listOf(file("zzz.txt"), file("src/main.rs"), file("assets/logo.txt")),
            emptySet(),
        )
        assertEquals(
            listOf("assets", "assets/logo.txt", "src", "src/main.rs", "zzz.txt"),
            rows.map { it.key },
        )
        val dirs = rows.filterIsInstance<CommitTreeDir>()
        assertEquals(listOf("assets", "src"), dirs.map { it.label })
        // Children are one indent step in.
        assertEquals(1, (rows[1] as CommitTreeFile).depth)
    }

    /**
     * A Java-shaped path is one row, not four: single-child chains compact
     * into `a/b/c` (git_graph.rs:412-509).
     */
    @Test
    fun singleChildChainsCompact() {
        val rows = commitFileTree(
            listOf(file("app/src/main/java/Main.kt"), file("app/src/main/java/Other.kt")),
            emptySet(),
        )
        assertEquals(2 + 1, rows.size)
        val dir = rows[0] as CommitTreeDir
        assertEquals("app/src/main/java", dir.label)
        assertEquals("app/src/main/java", dir.key)
        assertEquals(0, dir.depth)
        // The files sit one step inside the compacted chain, not five.
        assertEquals(1, (rows[1] as CommitTreeFile).depth)
        assertEquals("Main.kt", (rows[1] as CommitTreeFile).name)
    }

    /** The chain stops compacting where the tree actually branches. */
    @Test
    fun aBranchInTheChainStopsTheCompaction() {
        val rows = commitFileTree(
            listOf(file("src/ui/a.kt"), file("src/core/b.kt")),
            emptySet(),
        )
        assertEquals(
            listOf("src", "src/core", "src/core/b.kt", "src/ui", "src/ui/a.kt"),
            rows.map { it.key },
        )
        assertEquals(0, (rows[0] as CommitTreeDir).depth)
        assertEquals(1, (rows[1] as CommitTreeDir).depth)
        assertEquals(2, (rows[2] as CommitTreeFile).depth)
    }

    /** A directory in a chain that also holds a file is a compaction stop. */
    @Test
    fun aFileInTheMiddleStopsTheCompaction() {
        val rows = commitFileTree(
            listOf(file("src/lib.rs"), file("src/git/mod.rs")),
            emptySet(),
        )
        assertEquals(
            listOf("src", "src/git", "src/git/mod.rs", "src/lib.rs"),
            rows.map { it.key },
        )
    }

    /** A collapsed directory keeps its row and loses its contents. */
    @Test
    fun collapsingHidesTheContentsNotTheRow() {
        val files = listOf(file("src/a.kt"), file("src/b.kt"), file("top.txt"))
        val open = commitFileTree(files, emptySet())
        assertEquals(4, open.size)
        val folded = commitFileTree(files, setOf("src"))
        assertEquals(listOf("src", "top.txt"), folded.map { it.key })
    }

    /** Folding a compacted chain folds by the chain's deepest key. */
    @Test
    fun aCompactedChainFoldsAsOne() {
        val files = listOf(file("a/b/c/d.txt"))
        val rows = commitFileTree(files, setOf("a/b/c"))
        assertEquals(listOf("a/b/c"), rows.map { it.key })
    }

    @Test
    fun nothingInNothingOut() {
        assertTrue(commitFileTree(emptyList(), emptySet()).isEmpty())
    }
}
