package to.eyed.thragg.core

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Delete a directory tree without ever following a symbolic link out of it.
 *
 * This exists because `File.deleteRecursively()` does follow them: it decides
 * what to descend into with `File.isDirectory()`, which resolves links. A
 * Linux rootfs is *full* of symlinks — Debian's `/bin` is one — and a user can
 * trivially make one that points at their projects:
 *
 * ```
 * ln -s /data/data/…/files/projects /root/p
 * ```
 *
 * Removing the userland would then delete their work. The links inside a
 * distribution are harmless because they point back into it, but "harmless in
 * the cases we thought of" is not a property worth betting a user's source
 * code on.
 *
 * `Files.walkFileTree` does not follow links unless asked, so a link is
 * visited as an entry and unlinked, never descended.
 */
object SafeDelete {

    /**
     * Delete [root] and everything beneath it. Returns false if anything
     * survived — callers that care (an installer clearing a half-finished
     * rootfs) should treat that as failure rather than press on.
     */
    fun deleteTree(root: File): Boolean {
        if (!root.exists() && !isSymlink(root)) return true
        return try {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    /** Reached for an unreadable directory; delete what we can. */
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            !root.exists()
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Delete [target], but only once it is established that [target] really
     * lives inside [root]. Returns false without touching anything otherwise.
     *
     * This is what the project panel deletes through. [deleteTree] already
     * refuses to *descend* a link, which keeps a delete from spreading; this
     * adds the other half, that the thing being deleted was reached from
     * inside the project in the first place. A tree whose middle is a link —
     * `project/vendor -> /` — would otherwise let a row the user sees in their
     * project stand for a file nowhere near it.
     */
    fun deleteWithin(root: File, target: File): Boolean =
        isInside(root, target) && deleteTree(target)

    /**
     * Is [target] a path inside [root], judged by the directory holding it?
     *
     * The parent chain is canonicalized, so a symlinked directory partway down
     * cannot smuggle the target out of [root]. The target itself deliberately
     * is not: a link is an entry of the project in its own right, and deleting
     * or renaming one must act on the link, never on what it points at.
     *
     * Both sides are canonicalized, not just the parent: on Android `filesDir`
     * is /data/user/0/<pkg>, itself a link to /data/data/<pkg>, so comparing
     * one spelling against the other would say "outside" about every project
     * we have. The engine resolves the worktree root once for the same reason.
     *
     * Also the guard the panel's rename, move and copy use — same property,
     * same answer, one definition.
     */
    fun isInside(root: File, target: File): Boolean {
        val parent = target.parentFile ?: return false
        val rootPath = canonicalOrNull(root) ?: return false
        val parentPath = canonicalOrNull(parent) ?: return false
        return parentPath == rootPath || parentPath.startsWith(rootPath + File.separator)
    }

    /**
     * Whether [dir] *itself* resolves to somewhere inside [root].
     *
     * [isInside] canonicalizes the parent and deliberately not the target, so
     * that deleting or renaming a symlink acts on the link rather than on what
     * it points at. A *destination* is the opposite question: writing into a
     * link means writing wherever it points, so here the directory itself is
     * resolved. A project holding `vendor -> /storage/emulated/0/Download` —
     * one line in the terminal dock, and unremarkable in a Debian-shaped tree
     * — otherwise lets New File and Paste land outside the project, where the
     * panel then refuses to delete what it just created.
     */
    fun resolvesInside(root: File, dir: File): Boolean {
        val rootPath = canonicalOrNull(root) ?: return false
        val dirPath = canonicalOrNull(dir) ?: return false
        return dirPath == rootPath || dirPath.startsWith(rootPath + File.separator)
    }

    private fun canonicalOrNull(file: File): String? = try {
        file.canonicalPath
    } catch (e: IOException) {
        null
    }

    private fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())
}
