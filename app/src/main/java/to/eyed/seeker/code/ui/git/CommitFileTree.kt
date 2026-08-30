package to.eyed.seeker.code.ui.git

import to.eyed.seeker.code.core.CommitFile

/**
 * One row of the sidebar's changed-files tree: a directory to fold, or a file
 * to open. [depth] is how many indent steps in it sits.
 */
sealed interface CommitTreeRow {
    val depth: Int

    /** Stable list key; for a directory, also what the fold state is kept by. */
    val key: String
}

/** A directory row — possibly a compacted `a/b/c` chain. */
data class CommitTreeDir(
    /** What the row says: the chain of single-child directories, joined. */
    val label: String,
    override val depth: Int,
    /** The full repo path of the deepest directory in the chain. */
    override val key: String,
) : CommitTreeRow

/** A file row. */
data class CommitTreeFile(
    val file: CommitFile,
    /** The file's own name; the directory is the row above it. */
    val name: String,
    override val depth: Int,
) : CommitTreeRow {
    override val key: String get() = file.path
}

/**
 * Lay a commit's files out as Zed's directory tree (git_graph.rs:412-509):
 * files sorted by repo path, directories built from the paths, and **chains of
 * single-child directories compacted** into one `a/b/c` row so a Java-shaped
 * tree does not cost eight rows of nothing. Directories default to open;
 * [collapsed] holds the keys of the ones folded shut, whose contents are
 * simply not emitted.
 *
 * Pure, and tested as arithmetic: which rows exist, in what order, at what
 * depth — the three things a tree gets subtly wrong.
 */
fun commitFileTree(files: List<CommitFile>, collapsed: Set<String>): List<CommitTreeRow> {
    val root = TreeNode()
    for (file in files.sortedBy { it.path }) {
        val parts = file.path.split('/')
        var node = root
        for (part in parts.dropLast(1)) {
            if (part.isEmpty()) continue
            node = node.dirs.getOrPut(part) { TreeNode() }
        }
        node.files.add(file)
    }
    val rows = ArrayList<CommitTreeRow>()
    emit(root, prefix = "", depth = 0, collapsed = collapsed, rows = rows)
    return rows
}

private class TreeNode {
    /** Sorted, so sibling directories come out in path order. */
    val dirs = sortedMapOf<String, TreeNode>()
    val files = ArrayList<CommitFile>()
}

private fun emit(
    node: TreeNode,
    prefix: String,
    depth: Int,
    collapsed: Set<String>,
    rows: MutableList<CommitTreeRow>,
) {
    // Zed lists a directory's subdirectories before its files, as every tree
    // does (git_graph.rs:470-486).
    for ((name, child) in node.dirs) {
        // Compact `a/b/c` while each link has exactly one child and nothing
        // else — one row for the chain, keyed and folded as its deepest link.
        var label = name
        var path = if (prefix.isEmpty()) name else "$prefix/$name"
        var tail = child
        while (tail.files.isEmpty() && tail.dirs.size == 1) {
            val (next, grandchild) = tail.dirs.entries.first()
            label = "$label/$next"
            path = "$path/$next"
            tail = grandchild
        }
        rows.add(CommitTreeDir(label = label, depth = depth, key = path))
        if (path !in collapsed) {
            emit(tail, prefix = path, depth = depth + 1, collapsed = collapsed, rows = rows)
        }
    }
    for (file in node.files) {
        rows.add(
            CommitTreeFile(
                file = file,
                name = file.path.substringAfterLast('/'),
                depth = depth,
            )
        )
    }
}
