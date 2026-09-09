package to.eyed.thragg.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * proot's `--link2symlink` debris, undone.
 *
 * Every guest process runs under `--link2symlink` (terminal/DebianUserland.kt,
 * and the engine's own proot line in `guest.rs`), and the rewrite turns a
 * `link(old, new)` into: `old` renamed to a hidden `.l2s.<name>.NNNN` file,
 * and `new` a **symlink whose text is the absolute HOST path** of that hidden
 * file. Host-side the chain still resolves, so nothing looks wrong — until the
 * directory moves.
 *
 * git is the caller that matters. `finalize_object_file` writes a loose object
 * to `objects/XX/tmp_obj_??????` and hard-links it into place, so *every*
 * object a commit writes ends up as an absolute symlink. The exported
 * `qa_git.zip` from the 0.0.23 regression pass is the evidence: seven
 * `.git/objects/??/.l2s.tmp_obj_*.0001` files and not one real object name
 * (QA 0.0.23, G-17/G-24 findings). Two consequences, both seen:
 *
 *  - **Renaming the project destroys the repository.** The symlinks still name
 *    `…/projects/<old name>/.git/objects/…`, which no longer exists.
 *  - **A copy of the project is not a repository.** Anything that walks the
 *    tree either skips symlinks (the zip export) or copies dangling ones.
 *
 * The prevention lives in [to.eyed.thragg.terminal.DebianUserland] — the guest
 * gitconfig is seeded with `core.createObject = rename`, so git stops
 * hard-linking at all. This is the repair for what is already on disk, and it
 * is what makes moving or copying a project safe whatever wrote the tree.
 *
 * **Blocking**: it walks the tree. Call it from
 * [kotlinx.coroutines.Dispatchers.IO].
 */
object GuestLinks {

    /** proot's own marker for the file it moved a hard link's bytes into. */
    const val DEBRIS_PREFIX = ".l2s."

    /**
     * What a pass over a tree came to.
     *
     * [unresolved] holds project-relative paths of links that could not be
     * repaired because the bytes they name are gone — a repository that has
     * *already* been moved. They are what an export has to say out loud
     * rather than ship.
     */
    data class Repair(val repaired: Int, val unresolved: List<String>) {
        val isClean: Boolean get() = unresolved.isEmpty()
    }

    /**
     * Replace every `--link2symlink` symlink under [root] with the real file.
     *
     * Conservative on purpose — a link is only touched when all three are
     * true: its text is absolute, it names a path *inside* [root], and that
     * path's file name carries proot's [DEBRIS_PREFIX]. A symlink the user or
     * a package manager made is left exactly as it is.
     *
     * Idempotent: a tree with no debris costs one `lstat` per entry and
     * changes nothing.
     */
    fun repair(root: File): Repair {
        if (!root.isDirectory) return Repair(0, emptyList())
        val prefix = root.absolutePath.trimEnd('/') + "/"
        var repaired = 0
        val unresolved = mutableListOf<String>()
        // Debris a previous link in this pass has already claimed: proot gives
        // one hidden file to a group of hard links, so the second name has to
        // be filled with a copy of where the first one's bytes went.
        val claimed = HashMap<String, File>()

        walk(root) { file ->
            val text = linkTarget(file) ?: return@walk
            if (!text.startsWith("/") || !text.startsWith(prefix)) return@walk
            val debris = File(text)
            if (!debris.name.startsWith(DEBRIS_PREFIX)) return@walk
            val done = runCatching {
                when {
                    // The ordinary case: the hidden file is still there, so
                    // the bytes move back under the name git wrote.
                    debris.isFile -> {
                        file.delete()
                        Files.move(debris.toPath(), file.toPath())
                        claimed[text] = file
                        true
                    }
                    // A real hard link with two names: the first took the
                    // bytes, this one gets a copy of them.
                    claimed[text] != null -> {
                        file.delete()
                        Files.copy(
                            claimed.getValue(text).toPath(),
                            file.toPath(),
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                        true
                    }

                    else -> false
                }
            }.getOrDefault(false)
            if (done) repaired++ else unresolved += file.absolutePath.removePrefix(prefix)
        }
        return Repair(repaired, unresolved)
    }

    /**
     * Whether anything under [dir] is still a `--link2symlink` symlink that
     * cannot be followed — asked of `.git` after a [repair], because a
     * repository missing one object is not a repository.
     */
    fun hasDanglingDebris(dir: File): Boolean {
        if (!dir.isDirectory) return false
        var found = false
        walk(dir) { file ->
            if (found) return@walk
            val text = linkTarget(file) ?: return@walk
            if (text.startsWith("/") && File(text).name.startsWith(DEBRIS_PREFIX) && !File(text).exists()) {
                found = true
            }
        }
        return found
    }

    /** The text of [file] as a symbolic link, or null when it is not one. */
    private fun linkTarget(file: File): String? {
        val path = file.toPath()
        if (!Files.isSymbolicLink(path)) return null
        return runCatching { Files.readSymbolicLink(path).toString() }.getOrNull()
    }

    /**
     * Every entry under [root], links included, without ever *following* one.
     *
     * `File.isDirectory` follows a symbolic link, so a link to a directory
     * would take the walk out of the tree — the same trap
     * [SafeDelete] exists to avoid, and the reason this does not use
     * `walkTopDown`.
     */
    private fun walk(root: File, visit: (File) -> Unit) {
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (child in dir.listFiles().orEmpty()) {
                if (Files.isSymbolicLink(child.toPath())) {
                    visit(child)
                } else if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    visit(child)
                }
            }
        }
    }
}
