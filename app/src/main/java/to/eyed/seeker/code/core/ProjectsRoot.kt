package to.eyed.seeker.code.core

import android.content.Context
import java.io.File

/** A project directory as the picker lists it. */
data class ProjectSummary(
    val name: String,
    val path: String,
    /** Direct children, for a cheap sense of size. Not a recursive count. */
    val entryCount: Int,
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
    private const val SAMPLE_NAME = "welcome"

    /**
     * Where a file shared from another app goes when it is not for the open
     * project: a project like any other, created the first time it is
     * needed and deletable from the picker like the rest.
     */
    const val SCRATCH_NAME = "Scratch"
    private const val PREFS = "projects"
    private const val KEY_LAST_OPENED = "last_opened"

    /** Longest project name we accept, well under any filesystem limit. */
    private const val MAX_NAME_LENGTH = 96

    fun directory(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    fun projectDir(context: Context, name: String): File = File(directory(context), name)

    /** Every project, most recently touched first. */
    fun list(context: Context): List<ProjectSummary> =
        directory(context)
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { dir ->
                ProjectSummary(
                    name = dir.name,
                    path = dir.absolutePath,
                    entryCount = dir.list()?.size ?: 0,
                    lastModified = dir.lastModified(),
                )
            }
            .sortedByDescending { it.lastModified }

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
        // Symlink-safe: a project may contain links (a clone with a
        // node_modules symlink, say) and deleting one must not chase it out of
        // the project. See SafeDelete.
        return SafeDelete.deleteTree(dir)
    }

    fun lastOpened(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_OPENED, null)
            ?.takeIf { projectDir(context, it).isDirectory }

    fun setLastOpened(context: Context, name: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply { if (name == null) remove(KEY_LAST_OPENED) else putString(KEY_LAST_OPENED, name) }
            .apply()
    }

    /**
     * The project to open at startup: the one last open, else any existing
     * one, else the sample — seeded only when there are no projects at all,
     * so deleting it keeps it deleted.
     */
    fun defaultProject(context: Context): String {
        lastOpened(context)?.let { return projectDir(context, it).absolutePath }
        list(context).firstOrNull()?.let { return it.path }
        val sample = projectDir(context, SAMPLE_NAME)
        writeSampleProject(sample)
        return sample.absolutePath
    }

    private fun writeSampleProject(project: File) {
        File(project, "src").mkdirs()
        File(project, "Cargo.toml").writeText(
            """
            [package]
            name = "welcome"
            version = "0.1.0"
            edition = "2024"
            """.trimIndent() + "\n"
        )
        File(project, ".gitignore").writeText("target\n")
        File(project, "README.md").writeText(
            """
            # Welcome to Seeker IDE

            An IDE for Android, built on Zed's Rust engine.

            This project lives in the app's private storage. The tree on the
            left is a real Zed worktree scanned inside the engine: gitignore
            aware, incremental, and lazy — directories are read only when you
            open them.

            Use the project name in the status bar to switch projects, create
            one, or import a folder from your device.
            """.trimIndent() + "\n"
        )
        File(project, "src/main.rs").writeText(sampleSource())
    }

    /** Welcome text plus generated lines — a scroll workout for the renderer. */
    private fun sampleSource(): String = buildString {
        append(
            """
            // Welcome to Seeker IDE.
            //
            // This buffer lives inside the Rust engine (core/crates/engine):
            // Zed's rope/CRDT text stack, reached over JNI. The editor draws
            // only the visible line window, and the colors come from Zed's
            // tree-sitter highlight queries running inside the engine.
            //
            // The lines that follow are generated so you can put the
            // virtualized renderer through its paces. Fling away.

            const GREETING: &str = "Hello from the Rust core!";

            """.trimIndent() + "\n"
        )
        for (i in 1..5_000) {
            append("fn generated_$i() -> i32 { $i * ${i % 7} }  // scroll test, line ${i + 12}\n")
        }
    }
}
