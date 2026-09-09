package to.eyed.thragg.core

import android.content.Context
import java.io.File
import to.eyed.thragg.solana.chain.DeployedPrograms

/** A project directory as the picker lists it. */
data class ProjectSummary(
    val name: String,
    val path: String,
    /** Direct children, for a cheap sense of size. Not a recursive count. */
    val entryCount: Int,
    /**
     * When this project was last *touched*: the later of the folder's own
     * mtime and the last time the app opened it.
     *
     * The folder's mtime alone is not what a list headed RECENT means. A
     * directory's mtime moves when a direct child is created or removed, not
     * when a file three levels down is edited and not when the project is
     * opened — so the project you had open for an hour read "4 hours ago" and
     * sat at the bottom of the list (QA P-13).
     */
    val lastModified: Long,
)

/**
 * Where projects live on device.
 *
 * App-private storage (`filesDir/projects`) is the only project root, and
 * that is a deliberate constraint rather than a stopgap: the engine's
 * worktree is Zed's, which walks and watches a real filesystem path with
 * `std::fs` and inotify. A Storage Access Framework tree is a stream of
 * content URIs with no path behind it, so a project living out on shared
 * storage could not be scanned, watched or opened by the engine at all.
 * Content from elsewhere is therefore *copied in* (see [SafTransfer]) rather
 * than opened in place.
 */
object ProjectsRoot {
    /**
     * Where a file shared from another app goes when it is not for the open
     * project: a project like any other, created the first time it is
     * needed and deletable from the picker like the rest.
     */
    const val SCRATCH_NAME = "Scratch"
    private const val PREFS = "projects"
    private const val KEY_LAST_OPENED = "last_opened"

    /** `opened:<name>` → epoch millis; what makes the RECENT list recent. */
    private fun openedKey(name: String) = "opened:$name"

    /** Longest project name we accept, well under any filesystem limit. */
    private const val MAX_NAME_LENGTH = 96

    fun directory(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    fun projectDir(context: Context, name: String): File = File(directory(context), name)

    /** Every project, most recently touched first. */
    fun list(context: Context): List<ProjectSummary> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return directory(context)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { dir ->
                ProjectSummary(
                    name = dir.name,
                    path = dir.absolutePath,
                    entryCount = dir.list()?.size ?: 0,
                    lastModified = touchedAt(dir.lastModified(), prefs.getLong(openedKey(dir.name), 0L)),
                )
            }
            .sortedByDescending { it.lastModified }
    }

    /**
     * When a project was last touched, from the two facts we have about it.
     *
     * Pure, and the reason it is: "the project you just opened is at the top
     * and says *just now*" is the rule the list is read by, and it is
     * checkable without a device (ProjectsRootTest).
     */
    fun touchedAt(folderModified: Long, openedAt: Long): Long = maxOf(folderModified, openedAt)

    /**
     * Why [name] can't be a project name, or null if it can. Rejecting rather
     * than silently sanitizing: a project called something other than what
     * the user typed is worse than being told no.
     */
    fun nameError(context: Context, name: String): String? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> "Name cannot be empty"
            trimmed.length > MAX_NAME_LENGTH -> "Name is too long"
            trimmed == "." || trimmed == ".." -> "Reserved name"
            trimmed.startsWith(".") -> "Name cannot start with a dot"
            // A separator would escape the projects directory entirely.
            trimmed.contains('/') || trimmed.contains('\\') -> "Name cannot contain slashes"
            trimmed.any { it.code < 0x20 } -> "Name contains control characters"
            projectDir(context, trimmed).exists() -> "A project called that already exists"
            else -> null
        }
    }

    /** Create an empty project. Returns null if the name is unusable. */
    fun create(context: Context, name: String): File? {
        val trimmed = name.trim()
        if (nameError(context, trimmed) != null) return null
        val dir = projectDir(context, trimmed)
        return if (dir.mkdirs()) dir else null
    }

    /**
     * A free name based on [desired] — `project`, `project 2`, `project 3`…
     * Imports use this so bringing the same folder in twice doesn't fail.
     */
    fun uniqueName(context: Context, desired: String): String {
        val base = desired.trim()
            .replace('/', '-')
            .replace('\\', '-')
            .trimStart('.')
            .take(MAX_NAME_LENGTH)
            .ifEmpty { "project" }
        if (!projectDir(context, base).exists()) return base
        var suffix = 2
        while (projectDir(context, "$base $suffix").exists()) suffix++
        return "$base $suffix"
    }

    /** The Scratch project, created on first use. Blocking; call it off the main thread. */
    fun scratch(context: Context): File = projectDir(context, SCRATCH_NAME).apply { mkdirs() }

    /** Delete a project and everything in it. There is no undo. */
    fun delete(context: Context, name: String): Boolean {
        val dir = projectDir(context, name)
        // Guard against a name that somehow escapes the root.
        if (dir.parentFile != directory(context)) return false
        if (lastOpened(context) == name) setLastOpened(context, null)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(openedKey(name))
            .apply()
        // Its saved place goes with it, or a project created later under the
        // same name would open yesterday's files (engine/src/session.rs).
        runCatching { CoreBridge.sessionClear(dir.absolutePath) }
        // Symlink-safe: a project may contain links (a clone with a
        // node_modules symlink, say) and deleting one must not chase it out of
        // the project. See SafeDelete.
        return SafeDelete.deleteTree(dir)
    }

    fun lastOpened(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_OPENED, null)
            ?.takeIf { projectDir(context, it).isDirectory }

    /**
     * Note that [name] is the open project, and that it was opened *now*.
     *
     * The timestamp is the half that was missing: this stored a name only, so
     * nothing in the app ever recorded that a project had been opened and the
     * RECENT list had nothing to sort by but folder mtimes (QA P-13).
     */
    fun setLastOpened(context: Context, name: String?, at: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (name == null) {
                    remove(KEY_LAST_OPENED)
                } else {
                    putString(KEY_LAST_OPENED, name)
                    putLong(openedKey(name), at)
                }
            }
            .apply()
    }

    /**
     * The project to reopen at launch: the one last open, else the most
     * recently touched, else null.
     *
     * Null is a real answer and the shell draws it: a fresh install has no
     * project, and what it shows is an empty Code destination with the
     * Projects sheet over it (docs/UI.md, "First run"). Nothing is seeded to
     * avoid that — the sample project this used to write was a `welcome`
     * directory with a Cargo.toml, a README and five thousand generated lines
     * of Rust, which on a Solana-first IDE is a project nobody asked for
     * standing in front of the one they came to make.
     */
    fun lastProject(context: Context): String? {
        lastOpened(context)?.let { return projectDir(context, it).absolutePath }
        return list(context).firstOrNull()?.path
    }

    /**
     * As [lastProject], with somewhere to go when there is nothing: Scratch,
     * which is a real empty project rather than a fabricated tutorial.
     *
     * Only the inherited workspace calls this, and only because it has no way
     * to draw "no project open" — it opens one or it opens nothing. The new
     * shell calls [lastProject] and handles the null. **Blocking**, because
     * it may create the directory.
     */
    fun defaultProject(context: Context): String =
        lastProject(context) ?: scratch(context).absolutePath

    /**
     * Rename a project, keeping it inside the projects directory.
     *
     * Returns the new directory, or null when [to] is not a usable name or
     * the rename failed. The project's *contents* never mention its directory
     * name (a scaffold names the crate, not the folder), and the engine is
     * told about the move by being reopened on the new path.
     *
     * What is **not** in the directory has to move with it, and this is where
     * that happens rather than at the one call site: everything the app files
     * under a project is keyed by the project's absolute path.
     *
     *  - **The session document.** `files/sessions/` is named by a hash of the
     *    root path, so a rename left an orphan behind and the renamed project
     *    opened on "Nothing open yet" — renaming back brought the files
     *    straight back, which is what proved the key (QA G-17). It is read
     *    *before* the move, while the files it names are still there for the
     *    engine to validate against, and written under the new root, whose
     *    own `save_session` restamps the document's `root` field.
     *  - **The deployed-programs record**, whose `projectRoot` is how the
     *    Build tab finds the card for what is on chain.
     *
     * Both are best-effort: a rename that worked is not undone because a
     * bookkeeping file could not be rewritten.
     *
     * **Blocking** — it moves a directory and runs the session bridge.
     */
    fun rename(context: Context, from: String, to: String): File? {
        val trimmed = to.trim()
        if (trimmed == from) return projectDir(context, from).takeIf { it.isDirectory }
        if (nameError(context, trimmed) != null) return null
        val source = projectDir(context, from)
        if (!source.isDirectory || source.parentFile != directory(context)) return null
        val target = projectDir(context, trimmed)
        val document = runCatching { CoreBridge.sessionLoad(source.absolutePath) }.getOrNull()
        if (!source.renameTo(target)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val openedAt = prefs.getLong(openedKey(from), 0L)
        prefs.edit()
            .remove(openedKey(from))
            .apply { if (openedAt > 0L) putLong(openedKey(trimmed), openedAt) }
            .apply()
        if (lastOpened(context) == from) setLastOpened(context, trimmed, openedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        if (document != null) {
            runCatching { CoreBridge.sessionSave(target.absolutePath, document) }
            runCatching { CoreBridge.sessionClear(source.absolutePath) }
        }
        retargetRecords(context, source.absolutePath, target.absolutePath)
        return target
    }

    /**
     * Point every deployed-program record at the project's new path.
     *
     * Uses [DeployedPrograms]'s own reader and writer rather than touching
     * `deployed-programs.json`, so the file's shape stays that object's
     * business. Silent when there is nothing recorded, which is the common
     * case.
     */
    private fun retargetRecords(context: Context, from: String, to: String) {
        runCatching {
            DeployedPrograms.all(context)
                .filter { it.projectRoot == from }
                .forEach { DeployedPrograms.record(context, it.copy(projectRoot = to)) }
        }
    }
}
