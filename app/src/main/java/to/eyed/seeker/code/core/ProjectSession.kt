package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a project keeps its own settings, relative to its root — Zed's
 * `.zed/settings.json`, and the engine's `LOCAL_SETTINGS_PATH`.
 */
const val LOCAL_SETTINGS_PATH = ".zed/settings.json"

/** One entry in a project's worktree, as the engine reports it. */
data class ProjectEntry(
    /** Path relative to the project root, '/'-separated. Empty for the root. */
    val path: String,
    val name: String,
    val isDir: Boolean,
    /** Ignored by git. Zed only scans an ignored directory once it's expanded. */
    val isIgnored: Boolean,
    /** A dot-file, or inside a dot-directory. */
    val isHidden: Boolean,
    /** A directory whose children haven't been scanned yet — [ProjectSession.expand] first. */
    val isUnloaded: Boolean,
    /** Size in bytes; 0 for directories. */
    val size: Long,
)

/**
 * What git says about one path, reduced to what a project-panel row can show.
 *
 * A directory carries a rolled-up summary rather than an exact status — it can
 * hold a deletion and an addition at once — so on a directory [Modified] means
 * "something below changed" and [Added] means "everything below is new".
 */
enum class GitFileStatus {
    Modified,
    Added,
    Deleted,
    Renamed,
    Conflicted,
    Untracked,
    Ignored;

    internal companion object {
        /** The engine's snake_case names; anything unknown is ignored. */
        fun parse(name: String): GitFileStatus? = when (name) {
            "modified" -> Modified
            "added" -> Added
            "deleted" -> Deleted
            "renamed" -> Renamed
            "conflicted" -> Conflicted
            "untracked" -> Untracked
            "ignored" -> Ignored
            else -> null
        }
    }
}

/** One fuzzy file-finder hit. */
data class FileMatch(
    /** Path relative to *its folder's* root, '/'-separated. */
    val path: String,
    val name: String,
    /** UTF-16 offsets into [path] that matched, for highlighting. */
    val positions: List<Int>,
    /** Which folder of the project the hit is in — see [ProjectWorktree.id]. */
    val worktree: Long = 0L,
    /**
     * That folder's name, or empty when the project has only one folder.
     * Zed's file finder shows it in front of the path exactly this way.
     */
    val worktreeName: String = "",
    /**
     * The path to open the hit by — what [ProjectSession.absolutePathOf]
     * resolves. The same as [path] in the project's own folder.
     */
    val projectPath: String = "",
)

/**
 * One folder of a project — a Zed `Worktree`.
 *
 * A project is an ordered list of these, with the folder it was opened with
 * ([isPrimary]) first. `workspace::AddFolderToProject` appends and
 * `workspace::RemoveWorktreeFromProject` drops one.
 */
data class ProjectWorktree(
    val id: Long,
    /** The folder's name, which is what the panel's root header shows. */
    val name: String,
    /** Absolute path on disk. */
    val path: String,
    val scanComplete: Boolean,
    /** Why this folder could not be scanned, if it could not. */
    val error: String?,
    /** The folder the project was opened with. It cannot be removed. */
    val isPrimary: Boolean,
)

/**
 * Handle for one open project (a Zed worktree inside the engine).
 *
 * Scanning happens on the engine's own thread; nothing here blocks on it.
 * [version] is the staleness token: when it changes, cached children are
 * stale and worth re-reading. Callers drive that polling — see
 * [to.eyed.seeker.code.ui.workspace.ProjectPanel].
 */
class ProjectSession(absolutePath: String) {
    /**
     * Where the project lives on disk. The engine works in project-relative
     * paths, but a terminal has to start somewhere real.
     */
    val rootPath: String = absolutePath

    val id: Long = CoreBridge.openProject(absolutePath)

    val version: Long
        get() = CoreBridge.projectVersion(id)

    val scanComplete: Boolean
        get() = CoreBridge.projectScanComplete(id)

    val error: String?
        get() = CoreBridge.projectError(id)

    val rootName: String
        get() = CoreBridge.projectRootName(id).orEmpty()

    /**
     * Direct children of [dir] (project-relative, "" for the root), already
     * sorted directories-first by the engine.
     */
    fun children(dir: String): List<ProjectEntry> = parseEntries(CoreBridge.projectEntries(id, dir))

    /** [children], in a named folder of the project. */
    fun children(worktree: Long, dir: String): List<ProjectEntry> =
        parseEntries(CoreBridge.worktreeEntries(id, worktree, dir))

    private fun parseEntries(text: String): List<ProjectEntry> {
        val json = JSONArray(text)
        return List(json.length()) { index ->
            val entry = json.getJSONObject(index)
            ProjectEntry(
                path = entry.getString("path"),
                name = entry.getString("name"),
                isDir = entry.getBoolean("is_dir"),
                isIgnored = entry.getBoolean("is_ignored"),
                isHidden = entry.getBoolean("is_hidden"),
                isUnloaded = entry.getBoolean("is_unloaded"),
                size = entry.getLong("size"),
            )
        }
    }

    /**
     * Fuzzy-match [query] against the project's files, best first. An empty
     * query lists files. **Blocking** — call from
     * [kotlinx.coroutines.Dispatchers.Default].
     */
    fun findFiles(query: String, limit: Int = 50): List<FileMatch> {
        val json = JSONArray(CoreBridge.projectFindFiles(id, query, limit.toLong()))
        return List(json.length()) { index ->
            val entry = json.getJSONObject(index)
            val positions = entry.getJSONArray("positions")
            FileMatch(
                path = entry.getString("path"),
                name = entry.getString("name"),
                positions = List(positions.length()) { positions.getInt(it) },
                worktree = entry.optLong("worktree"),
                worktreeName = entry.optString("worktree_name"),
                projectPath = entry.optString("project_path"),
            )
        }
    }

    /**
     * Staleness token for [gitStatus], of the same shape as [version]: it
     * changes when the statuses change, and reading it is also what tells the
     * engine to go and refresh them. Nothing here waits on git — the engine
     * runs it on a thread of its own, debounced behind worktree changes — so
     * this is safe to poll from the UI loop.
     *
     * Stays 0 forever in builds with no Linux userland: there is no git to
     * ask, and that must look like a clean repository, not like a failure.
     */
    val gitStatusVersion: Long
        get() = CoreBridge.gitStatusVersion(id)

    /**
     * Git status by project-relative path, ready to colour rows with.
     *
     * Ancestor directories of a changed file are present too, with a rolled-up
     * status, so a row lookup is a single map hit whether it is a file or a
     * directory. Empty when the project is not in a repository, or when there
     * is no userland to run git in.
     */
    fun gitStatus(): Map<String, GitFileStatus> {
        val json = JSONObject(CoreBridge.gitStatus(id))
        val statuses = HashMap<String, GitFileStatus>(json.length())
        for (path in json.keys()) {
            GitFileStatus.parse(json.getString(path))?.let { statuses[path] = it }
        }
        return statuses
    }

    /** Ask the engine to scan a directory it deferred. Asynchronous. */
    fun expand(dir: String): Boolean = CoreBridge.expandDirectory(id, dir)

    /** [expand], in a named folder of the project. */
    fun expand(worktree: Long, dir: String): Boolean =
        CoreBridge.expandWorktreeDirectory(id, worktree, dir)

    /** Absolute path of a project-relative entry, or null if it isn't one. */
    fun absolutePathOf(path: String): String? = CoreBridge.projectEntryPath(id, path)

    /**
     * Move [paths] to the app's trash — Zed's `project_panel::Trash`.
     * **Blocking**; call it off the main thread. The entries it hands back go
     * to [restoreTrash] if the user takes the Undo.
     */
    fun trash(paths: List<String>): TrashResult {
        val json = CoreBridge.projectTrash(id, JSONArray(paths).toString())
            ?: return TrashResult.Failed("The engine could not reach the trash")
        return try {
            val root = JSONObject(json)
            val error = root.optString("error").takeIf { it.isNotEmpty() }
            if (error != null) return TrashResult.Failed(error)
            val array = root.optJSONArray("trashed") ?: JSONArray()
            TrashResult.Done(
                List(array.length()) { index ->
                    val entry = array.getJSONObject(index)
                    TrashedEntry(
                        path = entry.optString("path"),
                        id = entry.optString("id"),
                        name = entry.optString("name"),
                        originalParent = entry.optString("original_parent"),
                    )
                }
            )
        } catch (e: org.json.JSONException) {
            TrashResult.Failed(e.message ?: "The trash answered with nonsense")
        }
    }

    /**
     * Put trashed entries back. Null when it worked, the reason when it did
     * not. **Blocking**; call it off the main thread.
     */
    fun restoreTrash(entries: List<TrashedEntry>): String? {
        val array = JSONArray()
        for (entry in entries) {
            array.put(
                JSONObject().apply {
                    put("path", entry.path)
                    put("id", entry.id)
                    put("name", entry.name)
                    put("original_parent", entry.originalParent)
                }
            )
        }
        return CoreBridge.restoreTrash(array.toString())
    }

    /** [absolutePathOf], in a named folder of the project. */
    fun absolutePathOf(worktree: Long, path: String): String? =
        CoreBridge.worktreeEntryPath(id, worktree, path)

    /**
     * Every folder of the project, in order, the one it was opened with
     * first. Never empty for a live project.
     */
    fun worktrees(): List<ProjectWorktree> {
        val json = JSONArray(CoreBridge.projectWorktrees(id))
        return List(json.length()) { index ->
            val entry = json.getJSONObject(index)
            ProjectWorktree(
                id = entry.getLong("id"),
                name = entry.getString("name"),
                path = entry.getString("path"),
                scanComplete = entry.getBoolean("scan_complete"),
                error = if (entry.isNull("error")) null else entry.getString("error"),
                isPrimary = entry.getBoolean("is_primary"),
            )
        }
    }

    /**
     * Add a folder — Zed's `workspace::AddFolderToProject`. [path] must
     * already exist on disk; importing a SAF tree is the caller's job.
     *
     * Returns the folder's id, or throws nothing: failures come back as
     * [AddFolderResult.Failed] with the engine's own sentence.
     */
    fun addWorktree(path: String): AddFolderResult {
        val json = JSONObject(CoreBridge.projectAddWorktree(id, path))
        return if (json.has("error")) {
            AddFolderResult.Failed(json.getString("error"))
        } else {
            AddFolderResult.Added(json.getLong("id"))
        }
    }

    /**
     * Drop a folder — Zed's `workspace::RemoveWorktreeFromProject`. null when
     * it worked, the reason when it did not; the folder the project was
     * opened with cannot be removed.
     */
    fun removeWorktree(worktree: Long): String? = CoreBridge.projectRemoveWorktree(id, worktree)

    fun close(): Boolean = CoreBridge.closeProject(id)
}

/** One entry in the app's trash, and everything needed to put it back. */
data class TrashedEntry(
    /** Project-relative path it had — what the Undo message names. */
    val path: String,
    /** Full path it now occupies inside the trash. */
    val id: String,
    val name: String,
    val originalParent: String,
)

/** What [ProjectSession.trash] did, or why it did nothing. */
sealed interface TrashResult {
    data class Done(val entries: List<TrashedEntry>) : TrashResult

    /** [reason] is shown verbatim, so it has to read as a sentence. */
    data class Failed(val reason: String) : TrashResult
}

/** What [ProjectSession.addWorktree] did. */
sealed interface AddFolderResult {
    /** The folder that now covers the path — a new one, or one already open. */
    data class Added(val worktree: Long) : AddFolderResult

    data class Failed(val reason: String) : AddFolderResult
}
