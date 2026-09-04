package to.eyed.thragg.ui.workspace

import to.eyed.thragg.core.SafeDelete
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** What an operation did, or why it did nothing. */
sealed interface FileOpResult {
    /** [path] is project-relative: what the panel should select afterwards. */
    data class Done(val path: String) : FileOpResult

    /** [reason] is shown to the user verbatim, so it has to read as a sentence. */
    data class Failed(val reason: String) : FileOpResult
}

/**
 * The project panel's file operations, against a project's real directory.
 *
 * These go straight to the filesystem and stop there. Nothing here updates the
 * tree: the engine's worktree is watching the same directory, so a create,
 * rename or delete arrives back as a snapshot version bump like any change
 * made from a terminal or another program. That is the only path — a panel
 * that patched its own rows would be showing something the engine disagrees
 * with the moment anything else touched the disk.
 *
 * Every call is blocking; the panel runs them on [kotlinx.coroutines.Dispatchers.IO].
 *
 * Paths in and out are project-relative and '/'-separated, the same vocabulary
 * [to.eyed.thragg.core.ProjectEntry.path] uses, and every one of them is
 * resolved against the project root before use — see [resolve] and
 * [SafeDelete.isInside], which together are why a row in the tree cannot name
 * a file outside the project.
 */
object ProjectFiles {

    /**
     * Longest single name we accept. ext4 and f2fs both stop at 255 *bytes*,
     * not characters, which is why this is measured after encoding.
     */
    private const val MAX_NAME_BYTES = 255

    /**
     * Why [name] can't be used, or null if it can.
     *
     * Rejecting rather than sanitizing, as [to.eyed.thragg.core.ProjectsRoot]
     * does for project names: a file called something other than what the user
     * typed is worse than being told no. Dots are fine here though — a project
     * full of `.gitignore` and `.github` would be unusable otherwise.
     */
    fun nameError(name: String, parent: File? = null): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Name cannot be empty"
            trimmed == "." || trimmed == ".." -> "Reserved name"
            trimmed.contains('/') || trimmed.contains('\\') -> "Name cannot contain slashes"
            trimmed.any { it.code < 0x20 } -> "Name contains control characters"
            trimmed.toByteArray().size > MAX_NAME_BYTES -> "Name is too long"
            parent != null && exists(File(parent, trimmed)) ->
                "Something called that is already here"
            else -> null
        }
    }

    /**
     * As [nameError], for the new-file and new-folder prompts, which also
     * accept a path: `src/ui/Panel.kt` creates the directories on the way.
     * Zed's own new-file editor does this and it saves three dialogs.
     */
    fun pathError(path: String, parent: File? = null): String? {
        val parts = path.trim().split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return "Name cannot be empty"
        var dir = parent
        for ((index, part) in parts.withIndex()) {
            val last = index == parts.lastIndex
            nameError(part, if (last) dir else null)?.let { return it }
            // An intermediate directory that already exists is not a clash,
            // it is the directory we will create the rest inside.
            if (!last && dir != null) dir = File(dir, part.trim()).takeIf { it.isDirectory }
        }
        return null
    }

    /**
     * The file [rel] names inside [root], or null if it doesn't name one.
     *
     * The same rule the engine applies to a project-relative path: no empty
     * components, no `.` and no `..`, so the string alone cannot walk out of
     * the project. A link *inside* the project is still reachable — that is
     * [SafeDelete.isInside]'s job, and it is checked where it matters.
     */
    fun resolve(root: File, rel: String): File? {
        if (rel.isEmpty()) return root
        if (rel.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(root, rel)
    }

    /** The directory holding [rel], or "" for a top-level entry. */
    fun parentOf(rel: String): String = rel.substringBeforeLast('/', "")

    fun join(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    /** Create an empty file, or a directory, under [parent]. */
    fun create(root: File, parent: String, name: String, isDir: Boolean): FileOpResult {
        val parentDir = resolve(root, parent) ?: return notInProject(parent)
        // The string cannot walk out with `..`, but a symlinked folder in the
        // tree can: resolve the destination itself before creating anything.
        if (!SafeDelete.resolvesInside(root, parentDir)) return notInProject(parent)
        val parts = name.trim().split('/').filter { it.isNotEmpty() }
        pathError(name, parentDir)?.let { return FileOpResult.Failed(it) }
        var dir = parentDir
        var rel = parent
        return try {
            for (part in parts.dropLast(1)) {
                dir = File(dir, part.trim())
                rel = join(rel, part.trim())
                if (!dir.isDirectory && !dir.mkdir()) {
                    return FileOpResult.Failed("Could not create ${dir.name}")
                }
            }
            val leaf = parts.last().trim()
            // Re-checked after walking any intermediate components: one of
            // them may itself be an existing link out of the project.
            if (!SafeDelete.resolvesInside(root, dir)) return notInProject(rel)
            val target = File(dir, leaf)
            val created = if (isDir) target.mkdir() else target.createNewFile()
            if (!created) FileOpResult.Failed("Could not create $leaf")
            else FileOpResult.Done(join(rel, leaf))
        } catch (e: IOException) {
            FileOpResult.Failed(e.message ?: "Could not create ${parts.last()}")
        }
    }

    /** Rename [rel] in place. [newName] is a single name, not a path. */
    fun rename(root: File, rel: String, newName: String): FileOpResult {
        if (rel.isEmpty()) {
            return FileOpResult.Failed("The project itself is renamed from the project picker")
        }
        val source = resolve(root, rel) ?: return notInProject(rel)
        if (!SafeDelete.isInside(root, source)) return notInProject(rel)
        val name = newName.trim()
        if (name == source.name) return FileOpResult.Done(rel)
        val parentDir = source.parentFile ?: return notInProject(rel)
        nameError(name, parentDir)?.let { return FileOpResult.Failed(it) }
        return if (source.renameTo(File(parentDir, name))) {
            FileOpResult.Done(join(parentOf(rel), name))
        } else {
            FileOpResult.Failed("Could not rename ${source.name}")
        }
    }

    /**
     * Delete [rel] and, if it is a directory, everything under it.
     *
     * Goes through [SafeDelete] rather than `File.deleteRecursively`, which
     * follows symbolic links — read that file's doc comment; this is the path
     * that once deleted a user's projects by way of a link pointing at them.
     */
    fun delete(root: File, rel: String): FileOpResult {
        if (rel.isEmpty()) {
            return FileOpResult.Failed("The project itself is deleted from the project picker")
        }
        val target = resolve(root, rel) ?: return notInProject(rel)
        // Asked here only so the refusal can say what is wrong; the check that
        // matters is the one inside `deleteWithin`, which never trusts a
        // caller to have made it.
        if (!SafeDelete.isInside(root, target)) return notInProject(rel)
        return if (SafeDelete.deleteWithin(root, target)) {
            FileOpResult.Done(rel)
        } else {
            FileOpResult.Failed("Could not delete ${target.name}")
        }
    }

    /** Copy [rel] next to itself, under a free name. */
    fun duplicate(root: File, rel: String): FileOpResult = copyInto(root, rel, parentOf(rel))

    /** Copy [rel] into the directory [destDir], under a free name if it clashes. */
    fun copyInto(root: File, rel: String, destDir: String): FileOpResult {
        val source = resolve(root, rel) ?: return notInProject(rel)
        val target = resolve(root, destDir) ?: return notInProject(destDir)
        transferError(root, source, target, rel, destDir)?.let { return FileOpResult.Failed(it) }
        val name = freeName(target, source.name, source.isDirectory)
        return try {
            copyTree(source.toPath(), File(target, name).toPath())
            FileOpResult.Done(join(destDir, name))
        } catch (e: IOException) {
            FileOpResult.Failed(e.message ?: "Could not copy ${source.name}")
        }
    }

    /**
     * Move [rel] into the directory [destDir].
     *
     * [overwrite] is the answer to a drop the panel has already asked about:
     * without it a clash takes a free name ("main copy.rs"), which is what a
     * paste should do; with it the existing entry is replaced, which is what
     * the user said when they confirmed the prompt.
     */
    fun moveInto(
        root: File,
        rel: String,
        destDir: String,
        overwrite: Boolean = false,
    ): FileOpResult {
        val source = resolve(root, rel) ?: return notInProject(rel)
        val target = resolve(root, destDir) ?: return notInProject(destDir)
        // Cutting and pasting into the directory it is already in is what a
        // user does when they change their mind, not a request for a copy.
        if (parentOf(rel) == destDir) return FileOpResult.Done(rel)
        transferError(root, source, target, rel, destDir)?.let { return FileOpResult.Failed(it) }
        val name = if (overwrite) source.name else freeName(target, source.name, source.isDirectory)
        return try {
            val destination = File(target, name)
            if (overwrite && exists(destination)) {
                // `REPLACE_EXISTING` refuses a non-empty directory, which is
                // the common case for a folder drop, so the old entry goes
                // first — through [SafeDelete], which will not follow a link
                // out of the project.
                if (!SafeDelete.deleteWithin(root, destination)) {
                    return FileOpResult.Failed("Could not replace $name")
                }
            }
            Files.move(source.toPath(), destination.toPath())
            FileOpResult.Done(join(destDir, name))
        } catch (e: IOException) {
            FileOpResult.Failed(e.message ?: "Could not move ${source.name}")
        }
    }

    /**
     * Whether putting something called [name] into [destDir] would replace
     * what is already there — what the drop prompt asks about.
     */
    fun wouldOverwrite(root: File, destDir: String, name: String): Boolean {
        val target = resolve(root, destDir) ?: return false
        return exists(File(target, name))
    }

    /**
     * A name nothing in [dir] is using: `main.rs`, `main copy.rs`,
     * `main copy 1.rs`… Zed's scheme, kept character for character so a
     * project that has been touched by both looks like one project.
     */
    fun freeName(dir: File, name: String, isDir: Boolean): String {
        if (!exists(File(dir, name))) return name
        // A leading dot is part of the name, not an extension: `.gitignore`
        // duplicates to `.gitignore copy`, never to ` copy.gitignore`.
        val dot = name.lastIndexOf('.')
        val stem = if (isDir || dot <= 0) name else name.substring(0, dot)
        val extension = if (isDir || dot <= 0) "" else name.substring(dot)
        var index = 0
        while (true) {
            val suffix = if (index == 0) " copy" else " copy $index"
            val candidate = "$stem$suffix$extension"
            if (!exists(File(dir, candidate))) return candidate
            index++
        }
    }

    /** Why [source] can't be put into [target], or null if it can. */
    private fun transferError(
        root: File,
        source: File,
        target: File,
        rel: String,
        destDir: String,
    ): String? = when {
        !SafeDelete.isInside(root, source) -> "${source.name} is not in this project"
        !target.isDirectory -> "${target.name} is not a folder"
        // `isDirectory` follows links, so a row the panel draws as a folder
        // can be a link out of the project — and writing into it writes
        // wherever it points.
        !SafeDelete.resolvesInside(root, target) ->
            "${target.name} leads outside this project"
        destDir == rel || destDir.startsWith("$rel/") ->
            "A folder cannot be pasted into itself"
        else -> null
    }

    /**
     * `Files.exists` on the link itself. A dangling symlink is invisible to
     * `File.exists()` but still occupies the name, so the plain check would
     * hand back a name that then fails to be created.
     */
    private fun exists(file: File): Boolean =
        Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * Copy a tree without following links out of it — the same reasoning as
     * [SafeDelete], from the other end: `walkFileTree` visits a link as an
     * entry rather than descending it, and `NOFOLLOW_LINKS` copies the link
     * rather than the megabytes it may point at.
     */
    private fun copyTree(source: Path, target: Path) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.copy(
                        file,
                        target.resolve(source.relativize(file)),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun notInProject(path: String): FileOpResult.Failed =
        FileOpResult.Failed(
            "${path.substringAfterLast('/').ifEmpty { "That path" }} is not in this project"
        )
}
