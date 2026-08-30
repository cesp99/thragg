package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * What to search for. One shape drives both searches, so a search bar's
 * toggles can be handed to either without translation; [includeIgnored],
 * [includeGlobs] and [excludeGlobs] are simply ignored when searching a
 * buffer.
 */
data class SearchQuery(
    val query: String = "",
    /** Treat [query] as a regular expression rather than literal text. */
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    /**
     * Keep only hits with a non-word character (or nothing) either side, where
     * a word character is alphanumeric or '_'. The rule is the same for a
     * literal and for a regex — a regex is filtered on where its match landed,
     * not rewritten — so it never quietly means something else.
     */
    val wholeWord: Boolean = false,
    /** Project search: also search files git ignores. */
    val includeIgnored: Boolean = false,
    /** Project search: only these paths. Empty means every file. */
    val includeGlobs: List<String> = emptyList(),
    /** Project search: never these paths. Applied after [includeGlobs]. */
    val excludeGlobs: List<String> = emptyList(),
) {
    internal fun toJson(): String = JSONObject()
        .put("query", query)
        .put("regex", regex)
        .put("case_sensitive", caseSensitive)
        .put("whole_word", wholeWord)
        .put("include_ignored", includeIgnored)
        .put("include_globs", JSONArray(includeGlobs))
        .put("exclude_globs", JSONArray(excludeGlobs))
        .toString()

    /**
     * Why this query won't compile, or null if it will. Only a regex can
     * fail; show it next to the field rather than showing no results.
     */
    fun error(): String? = CoreBridge.searchQueryError(toJson())
}

/** One hit in a buffer, in the coordinates the editor works in. */
data class BufferMatch(
    /** Byte offsets into the buffer, on character boundaries. */
    val start: Long,
    val end: Long,
    /** 0-based row and *byte* column — what [CoreBridge.pointToOffset] takes. */
    val row: Int,
    val column: Int,
)

/** Everything [searchBuffer] found. */
data class BufferSearch(
    val matches: List<BufferMatch>,
    /**
     * Matches in the buffer in all. Larger than `matches.size` when the limit
     * bit, which is what lets the bar say "3 of 12 000" honestly.
     */
    val total: Int,
) {
    val truncated: Boolean get() = total > matches.size

    companion object {
        val Empty = BufferSearch(emptyList(), 0)
    }
}

/**
 * Every match of [query] in a buffer, ascending. Returns [BufferSearch.Empty]
 * for an unknown buffer or a query that doesn't compile — ask
 * [SearchQuery.error] to tell the two apart.
 *
 * Cheap enough to call on every keystroke of the query: the engine scans the
 * whole buffer in a few milliseconds even at 100k lines, which is why there is
 * no incremental variant of this and no result to keep between calls.
 */
fun searchBuffer(bufferId: Long, query: SearchQuery, limit: Int = 10_000): BufferSearch {
    val flat = CoreBridge.bufferSearch(bufferId, query.toJson(), limit.toLong())
    if (flat == null || flat.isEmpty()) return BufferSearch.Empty
    // Element 0 is the total; the rest are groups of four.
    val matches = ArrayList<BufferMatch>((flat.size - 1) / 4)
    var index = 1
    while (index + 3 < flat.size) {
        matches.add(
            BufferMatch(
                start = flat[index],
                end = flat[index + 1],
                row = flat[index + 2].toInt(),
                column = flat[index + 3].toInt(),
            )
        )
        index += 4
    }
    return BufferSearch(matches, flat[0].toInt())
}

/** What a replacement in a buffer did. */
data class ReplaceOutcome(
    /** The buffer's version afterwards — unchanged when [replaced] is 0. */
    val version: Long,
    /** Hits rewritten. */
    val replaced: Int,
    /**
     * Byte offset just past the last replacement in the *new* text: where a
     * search bar stepping on should resume, so the hit it lands on is the one
     * after the text it just rewrote.
     */
    val resumeAt: Long,
)

private fun parseReplaceOutcome(flat: LongArray?): ReplaceOutcome? {
    if (flat == null || flat.size < 3) return null
    return ReplaceOutcome(version = flat[0], replaced = flat[1].toInt(), resumeAt = flat[2])
}

/**
 * Replace the first hit of [query] at or after byte offset [from], wrapping to
 * the first in the buffer — Zed's `search::ReplaceNext`. Null for an unknown
 * buffer or a query that doesn't compile. As cheap as [searchBuffer] plus one
 * edit; the caller resyncs its editor with `noteExternalEdit` afterwards.
 */
fun replaceNextInBuffer(
    bufferId: Long,
    query: SearchQuery,
    replacement: String,
    from: Long,
): ReplaceOutcome? =
    parseReplaceOutcome(CoreBridge.bufferReplaceNext(bufferId, query.toJson(), replacement, from))

/**
 * Replace every hit of [query] in the buffer, as one undo step — Zed's
 * `search::ReplaceAll`. Null for an unknown buffer or a query that doesn't
 * compile.
 */
fun replaceAllInBuffer(bufferId: Long, query: SearchQuery, replacement: String): ReplaceOutcome? =
    parseReplaceOutcome(CoreBridge.bufferReplaceAll(bufferId, query.toJson(), replacement))

/** What a project-wide replacement did. */
data class ProjectReplaceReceipt(
    /** Files with at least one replacement. */
    val files: Int,
    /** Hits rewritten, across every file. */
    val replacements: Int,
    /**
     * The open buffers among them — edited through the buffer path, so their
     * editors have to resync and their tabs are dirty now.
     */
    val bufferIds: List<Long>,
    /** Files that could not be rewritten, as "path: why". */
    val errors: List<String>,
) {
    /** "Replaced 12 matches in 3 files" — the panel's summary line. */
    val summary: String
        get() = "Replaced ${plural(replacements, "match", "matches")} in ${plural(files, "file", "files")}"

    internal companion object {
        fun parse(json: String): ProjectReplaceReceipt {
            val root = JSONObject(json)
            val buffers = root.optJSONArray("buffers") ?: JSONArray()
            val errors = root.optJSONArray("errors") ?: JSONArray()
            return ProjectReplaceReceipt(
                files = root.optInt("files"),
                replacements = root.optInt("replacements"),
                bufferIds = List(buffers.length()) { buffers.getLong(it) },
                errors = List(errors.length()) { errors.getString(it) },
            )
        }

        private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
    }
}

/**
 * Replace every hit of [query] in every file the project's last — finished —
 * search found, Zed's `search::ReplaceAll` over the results. Null when the
 * engine refused: no finished search, an unknown project, a query that does
 * not compile. **Blocking** — call it off the main thread.
 */
fun replaceAllInProject(
    project: ProjectSession,
    query: SearchQuery,
    replacement: String,
): ProjectReplaceReceipt? =
    CoreBridge.projectReplaceAll(project.id, query.toJson(), replacement)?.let(ProjectReplaceReceipt::parse)

/** How far a project search has got. */
enum class ProjectSearchState {
    /**
     * The project's first scan hasn't finished, so there is nothing to search
     * yet. The search is alive and starts itself when the scan lands — show a
     * spinner, not an error, and never "no results".
     */
    Scanning,
    Running,

    /** Finished, over the whole project. */
    Done,

    /** Cancelled, superseded by a newer search, or an id the engine forgot. */
    Cancelled;

    /** Still to come — the caller should keep polling. */
    val isLive: Boolean get() = this == Scanning || this == Running

    internal companion object {
        fun parse(name: String): ProjectSearchState = when (name) {
            "scanning" -> Scanning
            "running" -> Running
            "done" -> Done
            else -> Cancelled
        }
    }
}

/**
 * One hit in a project search, shaped for a results panel: it carries the line
 * it lives on, so the panel draws a result without opening the file.
 */
data class ProjectSearchMatch(
    /**
     * 1-based, for display. To put a cursor on it, open the file and ask for
     * `CoreBridge.pointToOffset(bufferId, line - 1, column)`.
     */
    val line: Int,
    /**
     * Byte column of the match in the *whole* line — what
     * [CoreBridge.pointToOffset] wants. Equal to [start] unless [text] was
     * windowed.
     */
    val column: Int,
    /** Byte range of the match within [text]. Kotlin wants [startUtf16]. */
    val start: Int,
    val end: Int,
    /** The same range as UTF-16 offsets: how to index [text] to highlight it. */
    val startUtf16: Int,
    val endUtf16: Int,
    /** The line, windowed around the match if it was very long. */
    val text: String,
    /** [text] starts mid-line, so draw an ellipsis. */
    val clippedStart: Boolean,
    /**
     * Something was cut off the end — [text] stops before the line does, or
     * the match itself ran on past this line. Either way, draw an ellipsis.
     */
    val clippedEnd: Boolean,
)

/** Every hit in one file. */
data class ProjectSearchFile(
    /**
     * Relative to *its folder's* root, '/'-separated — the same spelling
     * [ProjectEntry] uses.
     */
    val path: String,
    val matches: List<ProjectSearchMatch>,
    /** Matches in the file in all; larger than `matches.size` when capped. */
    val matchCount: Int,
    /** Which folder of the project the file is in. */
    val worktree: Long = 0L,
    /** That folder's name, or empty when the project has only one folder. */
    val worktreeName: String = "",
    /**
     * Absolute path. Two folders can hold the same relative path, so this —
     * not [path] — is what identifies a result row.
     */
    val absPath: String = "",
    /**
     * The path to open the file by — what [ProjectSession.absolutePathOf]
     * resolves. The same as [path] in the project's own folder.
     */
    val projectPath: String = "",
)

/**
 * A snapshot of a project search: the counters, and the files added since the
 * caller last read.
 */
data class ProjectSearchResults(
    val state: ProjectSearchState,
    val version: Long,
    /** Set only for a failure that stopped the search, not a skipped file. */
    val error: String?,
    /**
     * Files walked. Includes the ones the engine skipped without reading —
     * over 4 MiB, binary, not UTF-8, unreadable — see [CoreBridge].
     */
    val filesSearched: Int,
    /** Files the worktree offered, for a progress bar. */
    val totalFiles: Int,
    /** Files with a match in all — not just in [newFiles]. */
    val fileCount: Int,
    /**
     * Matches in all, counted the way [ProjectSearchFile.matchCount] is:
     * matches dropped by the engine's per-file cap are in here too, so this is
     * the honest "N results" figure rather than the number drawn.
     */
    val matchCount: Int,
    /** One of the engine's caps bit, so this is not the whole truth. */
    val truncated: Boolean,
    /** Files found since the offset the caller asked from. */
    val newFiles: List<ProjectSearchFile>,
)

/**
 * A running search over a project's worktree.
 *
 * This never blocks: the engine does the reading on a thread of its own and
 * publishes [version], which moves whenever there is something new. Poll it
 * the way [ProjectSession.version] is polled, and call [poll] when it changes
 * — results only ever grow, so each call hands back what is new and the caller
 * appends.
 *
 * Starting a search cancels whatever was running for the same project, so a
 * search bar can simply start a new one on every change of the query.
 *
 * A project that is still scanning is searched all the same: the session
 * reports [ProjectSearchState.Scanning] and starts itself when the scan lands,
 * so a search over a freshly opened repo is a wait, never a wrong answer.
 *
 * [poll] is not free — it parses everything found since the last call, which
 * can be megabytes — so call it off the main thread and only when [version]
 * has moved. [cancel] frees the results; a panel that closes without calling
 * it leaves them alive until the project does.
 */
class ProjectSearchSession(project: ProjectSession, query: SearchQuery) {
    /** -1 if the project is unknown or the query didn't compile. */
    val id: Long = CoreBridge.projectSearchStart(project.id, query.toJson())

    /** How many files the caller has already taken. */
    private var taken = 0

    /**
     * Staleness token, of the same shape as [ProjectSession.version]: it moves
     * when there is something new to [poll]. Non-zero for as long as the
     * engine remembers the search, so 0 means forgotten, never "not yet".
     */
    val version: Long
        get() = if (id < 0) 0 else CoreBridge.projectSearchVersion(id)

    /**
     * The counters, plus every file found since the last call. Advances the
     * read cursor, so calling twice in a row gives no files the second time.
     */
    fun poll(): ProjectSearchResults {
        if (id < 0) {
            return ProjectSearchResults(
                state = ProjectSearchState.Cancelled,
                version = 0,
                error = null,
                filesSearched = 0,
                totalFiles = 0,
                fileCount = 0,
                matchCount = 0,
                truncated = false,
                newFiles = emptyList(),
            )
        }
        val json = JSONObject(CoreBridge.projectSearchResults(id, taken.toLong()))
        val files = json.optJSONArray("files") ?: JSONArray()
        taken = json.optInt("from_file", taken) + files.length()
        return ProjectSearchResults(
            state = ProjectSearchState.parse(json.optString("state")),
            version = json.optLong("version"),
            error = if (json.isNull("error")) null else json.optString("error"),
            filesSearched = json.optInt("files_searched"),
            totalFiles = json.optInt("total_files"),
            fileCount = json.optInt("file_count"),
            matchCount = json.optInt("match_count"),
            truncated = json.optBoolean("truncated"),
            newFiles = List(files.length()) { index -> parseFile(files.getJSONObject(index)) },
        )
    }

    /** Stop the search. Safe to call more than once. */
    fun cancel(): Boolean = id >= 0 && CoreBridge.projectSearchCancel(id)

    private fun parseFile(file: JSONObject): ProjectSearchFile {
        val matches = file.getJSONArray("matches")
        return ProjectSearchFile(
            path = file.getString("path"),
            matchCount = file.getInt("match_count"),
            worktree = file.optLong("worktree"),
            worktreeName = file.optString("worktree_name"),
            absPath = file.optString("abs_path"),
            projectPath = file.optString("project_path"),
            matches = List(matches.length()) { index ->
                val match = matches.getJSONObject(index)
                ProjectSearchMatch(
                    line = match.getInt("line"),
                    column = match.getInt("column"),
                    start = match.getInt("start"),
                    end = match.getInt("end"),
                    startUtf16 = match.getInt("start_utf16"),
                    endUtf16 = match.getInt("end_utf16"),
                    text = match.getString("text"),
                    clippedStart = match.getBoolean("clipped_start"),
                    clippedEnd = match.getBoolean("clipped_end"),
                )
            },
        )
    }
}
