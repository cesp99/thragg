package to.eyed.seeker.code.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.json.JSONObject
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.pollVersion


/**
 * A nullable string field, read the way Android's `org.json` requires.
 *
 * `optString(name, null)` returns the **string `"null"`** for a JSON null on
 * Android — Harmony's implementation coerces before it defaults — so a
 * `?.takeIf { it.isNotEmpty() }` sails straight past it and the UI prints the
 * word. The trap is documented in `app/build.gradle.kts` and it has cost this
 * project twice; ask `isNull` first, as the engine's own contract expects.
 */
private fun JSONObject.stringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name, null)?.takeIf { it.isNotEmpty() }


/**
 * What a language server has said about the code, on the UI side of the
 * frozen LSP bridge.
 *
 * Everything here is a *cache read*: no call in this file can block, fail or
 * be told "no", and the absence of language intelligence is expressed as
 * empty data rather than as an error. That is the bridge's contract, and it
 * is what lets the whole feature be a poll.
 *
 * The parsing is deliberately free of Compose and of the JNI object so it
 * can be tested on the host — a severity mapped wrong is an underline in the
 * wrong colour on every file in the language, and that is not something to
 * find on a device.
 */

/** How long between reads of a buffer's diagnostic counter. */
private const val BUFFER_POLL_MILLIS = 250L

/**
 * How long between reads of a project's LSP counter. Slower than the buffer
 * poll because it carries no per-keystroke urgency — but it must never stop:
 * polling `lspVersion` is *also* what starts a server for a file that was
 * open before its project, or before the userland grew the binary.
 */
private const val PROJECT_POLL_MILLIS = 500L

/**
 * LSP's four severities, in Zed's order of seriousness.
 *
 * [token] is the theme key each one paints in — Zed's `StatusColors`, picked
 * by exactly this match in `diagnostic_style`
 * (crates/editor/src/display_map.rs:2505-2513) and again for the scrollbar
 * markers (crates/editor/src/element.rs:6179-6184).
 */
enum class DiagnosticSeverity(val token: String) {
    Error("error"),
    Warning("warning"),
    Info("info"),
    Hint("hint"),
    ;

    companion object {
        /**
         * The bridge never sends null and never sends anything else — but a
         * severity is the one field whose fallback has a *meaning*: the
         * engine already reports a diagnostic the server left unrated as
         * `warning`, so an unknown spelling landing on Warning keeps the two
         * sides saying the same thing rather than inventing a fifth state.
         */
        fun from(name: String?): DiagnosticSeverity = when (name) {
            "error" -> Error
            "info" -> Info
            "hint" -> Hint
            else -> Warning
        }
    }
}

/**
 * One diagnostic, in the coordinates the renderer works in: 0-based rows and
 * UTF-16 columns, like `bufferHighlights` and everything else on this bridge.
 */
data class Diagnostic(
    val row: Int,
    val colUtf16: Int,
    val endRow: Int,
    val endColUtf16: Int,
    val severity: DiagnosticSeverity,
    val message: String,
    val source: String? = null,
    val code: String? = null,
) {
    /** True for a range with no width at all — a caret-sized diagnostic. */
    val isEmpty: Boolean get() = row == endRow && colUtf16 == endColUtf16

    /** The first line of the message, which is all a one-line surface can show. */
    val firstLine: String get() = message.substringBefore('\n')

    /**
     * The message with the server and code that produced it, the way Zed
     * writes it in the diagnostics list — "mismatched types (rustc E0308)".
     */
    val label: String
        get() {
            val parts = listOfNotNull(source, code)
            return if (parts.isEmpty()) firstLine else "$firstLine (${parts.joinToString(" ")})"
        }
}

/**
 * Every diagnostic the servers have published for one buffer, exactly as
 * `bufferDiagnostics` handed it over.
 *
 * [rows] is sorted by (row, col, endRow, endCol) — the bridge guarantees it,
 * and every walk here depends on it.
 */
class BufferDiagnostics(
    /** The bridge's own counter. 0 means nothing has ever been published. */
    val version: Long,
    /**
     * The engine buffer version the rows describe, or null when the server
     * dated them against text we no longer hold. Nullable rather than
     * sentinelled because 0 is a real buffer version.
     */
    val bufferVersion: Long?,
    val rows: List<Diagnostic>,
) {
    /**
     * The tallest diagnostic in the list, in rows.
     *
     * This is what makes [forEachIn] one walk rather than a scan. The list is
     * sorted by *start*, so a diagnostic that starts above the viewport can
     * still reach into it — and without a bound on how far, finding the first
     * one to paint means walking from the top of the file. Almost every
     * diagnostic is one row tall, so this is almost always 0 and the binary
     * search below lands exactly on the first visible row.
     */
    val maxRowSpan: Int = rows.maxOfOrNull { it.endRow - it.row } ?: 0

    val isEmpty: Boolean get() = rows.isEmpty()

    /**
     * Index of the first row that could touch [firstRow] — a lower bound on
     * `row >= firstRow - maxRowSpan`, by binary search.
     */
    fun firstIndexFor(firstRow: Int): Int {
        val floor = firstRow - maxRowSpan
        var low = 0
        var high = rows.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (rows[mid].row < floor) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Every diagnostic overlapping buffer rows [firstRow, lastRow], in order,
     * in one walk and with no list allocated — this runs inside the editor's
     * draw pass, once per frame.
     */
    inline fun forEachIn(firstRow: Int, lastRow: Int, action: (Diagnostic) -> Unit) {
        var i = firstIndexFor(firstRow)
        while (i < rows.size) {
            val diagnostic = rows[i]
            // Sorted by start row: past the bottom of the window, so is
            // everything after it.
            if (diagnostic.row > lastRow) return
            if (diagnostic.endRow >= firstRow) action(diagnostic)
            i++
        }
    }

    /**
     * The diagnostic to describe when the caret sits at (row, col): the ones
     * containing it, narrowed to the most severe and then to the shortest.
     *
     * Zed's `DiagnosticIndicator::update` picks exactly this way — the
     * diagnostics in `cursor..cursor`, empty ranges dropped, `min_by_key` on
     * (severity, length) (crates/diagnostics/src/items.rs:213-223). The empty
     * ones go because a zero-width range under the caret would win every
     * length comparison and describe nothing.
     */
    fun at(row: Int, col: Int): Diagnostic? {
        var best: Diagnostic? = null
        forEachIn(row, row) { diagnostic ->
            if (!diagnostic.isEmpty && diagnostic.contains(row, col) &&
                (best == null || diagnostic.isTighterThan(best!!))
            ) {
                best = diagnostic
            }
        }
        return best
    }

    /**
     * The next diagnostic after (row, col), wrapping to the first — Zed's
     * `go_to_diagnostic_in_direction`, which chains `after` with `before` so
     * the last one leads back round to the first
     * (crates/editor/src/diagnostics.rs:220-244).
     */
    fun next(row: Int, col: Int): Diagnostic? {
        if (rows.isEmpty()) return null
        return rows.firstOrNull { isAfter(it.row, it.colUtf16, row, col) } ?: rows.first()
    }

    /** The previous diagnostic before (row, col), wrapping to the last. */
    fun previous(row: Int, col: Int): Diagnostic? {
        if (rows.isEmpty()) return null
        return rows.lastOrNull { isAfter(row, col, it.row, it.colUtf16) } ?: rows.last()
    }

    /** The most severe diagnostic starting or continuing on [row], if any. */
    fun onRow(row: Int): Diagnostic? {
        var best: Diagnostic? = null
        forEachIn(row, row) { diagnostic ->
            if (best == null || diagnostic.severity < best!!.severity) best = diagnostic
        }
        return best
    }

    companion object {
        val EMPTY = BufferDiagnostics(version = 0L, bufferVersion = null, rows = emptyList())

        /**
         * Parse what `bufferDiagnostics` returned. Never throws: a payload
         * this cannot read is no diagnostics, which is a state the UI already
         * has to draw, rather than a crash in a draw pass.
         */
        fun parse(json: String?): BufferDiagnostics {
            if (json.isNullOrEmpty()) return EMPTY
            return try {
                val root = JSONObject(json)
                val version = root.optLong("version", 0L)
                if (version == 0L) return EMPTY
                val bufferVersion =
                    if (root.isNull("buffer_version")) null else root.optLong("buffer_version")
                BufferDiagnostics(version, bufferVersion, parseRows(root.optJSONArray("rows")))
            } catch (_: org.json.JSONException) {
                EMPTY
            }
        }
    }
}

/**
 * The rows of one file, in the shape both `bufferDiagnostics` and
 * `lspDiagnosticRows` publish them. Skips what it cannot read rather than
 * throwing — the callers' contract.
 */
private fun parseRows(array: org.json.JSONArray?): List<Diagnostic> {
    val rows = ArrayList<Diagnostic>(array?.length() ?: 0)
    for (i in 0 until (array?.length() ?: 0)) {
        val entry = array!!.optJSONObject(i) ?: continue
        val startRow = entry.optInt("row", 0)
        val startCol = entry.optInt("col_utf16", 0)
        rows.add(
            Diagnostic(
                row = startRow,
                colUtf16 = startCol,
                endRow = entry.optInt("end_row", startRow),
                endColUtf16 = entry.optInt("end_col_utf16", startCol),
                severity = DiagnosticSeverity.from(entry.stringOrNull("severity")),
                message = entry.optString("message", ""),
                source = entry.stringOrNull("source"),
                code = entry.stringOrNull("code"),
            )
        )
    }
    return rows
}

/** (row, col) is inside this diagnostic, both ends included. */
fun Diagnostic.contains(row: Int, col: Int): Boolean =
    !isAfter(this.row, colUtf16, row, col) && !isAfter(row, col, endRow, endColUtf16)

/**
 * Zed's `min_by_key((severity, length))`, as far as rows and columns can say
 * it: severity first, then the range covering fewer rows.
 *
 * Zed compares a real length because it holds offsets; we hold (row, column)
 * pairs, and the difference between two columns on *different* rows is not a
 * length — it can even be negative, which would rank the longer range tighter.
 * So columns decide only when both ranges live on one row, and two ranges of
 * equal row-span are left in document order, which is the order they arrived.
 */
internal fun Diagnostic.isTighterThan(other: Diagnostic): Boolean {
    if (severity != other.severity) return severity < other.severity
    val rowsHere = endRow - row
    val rowsThere = other.endRow - other.row
    if (rowsHere != rowsThere) return rowsHere < rowsThere
    if (rowsHere != 0) return false
    return (endColUtf16 - colUtf16) < (other.endColUtf16 - other.colUtf16)
}

/** Document order over two positions. */
private fun isAfter(row: Int, col: Int, thanRow: Int, thanCol: Int): Boolean =
    row > thanRow || (row == thanRow && col > thanCol)

/** One file's share of a project's diagnostics, as `lspDiagnostics` reports it. */
data class FileDiagnostics(
    /**
     * Project-relative and `/`-separated, the same spelling as
     * `projectEntries` — except a file outside the project, which keeps its
     * absolute path.
     */
    val path: String,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
    val hints: Int,
)

/**
 * A whole project's diagnostics, which is what the status bar counts. Zed's
 * `DiagnosticSummary`, and like Zed's it survives closing the file: only an
 * empty publish from the server clears a path from it.
 */
data class DiagnosticSummary(
    val version: Long,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
    val hints: Int,
    val files: List<FileDiagnostics>,
) {
    /**
     * Zed's clean case is `(0, 0)` on errors and warnings alone — a project
     * with nothing but hints still shows the check (items.rs:36-40).
     */
    val isClean: Boolean get() = errors == 0 && warnings == 0

    /** Zed's `diagnostics_label`, verbatim in shape (items.rs:90-108). */
    val label: String
        get() {
            if (isClean) return "Project diagnostics: no problems"
            val parts = ArrayList<String>(2)
            if (errors > 0) parts.add("$errors error${if (errors == 1) "" else "s"}")
            if (warnings > 0) parts.add("$warnings warning${if (warnings == 1) "" else "s"}")
            return "Project diagnostics: ${parts.joinToString(", ")}"
        }

    companion object {
        val EMPTY = DiagnosticSummary(0L, 0, 0, 0, 0, emptyList())

        fun parse(json: String?): DiagnosticSummary {
            if (json.isNullOrEmpty()) return EMPTY
            return try {
                val root = JSONObject(json)
                val array = root.optJSONArray("files")
                val files = ArrayList<FileDiagnostics>(array?.length() ?: 0)
                for (i in 0 until (array?.length() ?: 0)) {
                    val entry = array!!.optJSONObject(i) ?: continue
                    files.add(
                        FileDiagnostics(
                            path = entry.optString("path", ""),
                            errors = entry.optInt("errors", 0),
                            warnings = entry.optInt("warnings", 0),
                            infos = entry.optInt("infos", 0),
                            hints = entry.optInt("hints", 0),
                        )
                    )
                }
                DiagnosticSummary(
                    version = root.optLong("version", 0L),
                    errors = root.optInt("errors", 0),
                    warnings = root.optInt("warnings", 0),
                    infos = root.optInt("infos", 0),
                    hints = root.optInt("hints", 0),
                    files = files,
                )
            } catch (_: org.json.JSONException) {
                EMPTY
            }
        }
    }
}

/** One file's diagnostics, messages included, as `lspDiagnosticRows` lists it. */
data class FileDiagnosticRows(
    /** Spelled exactly as [FileDiagnostics.path] is. */
    val path: String,
    /** Sorted by position, like a buffer's rows. */
    val rows: List<Diagnostic>,
)

/**
 * Every diagnostic a project's servers have published, messages and all —
 * what the diagnostics panel lists, where [DiagnosticSummary] only counts.
 * Read only while the panel is showing: the payload is every message in the
 * project, which is why the status bar keeps to the counts.
 */
data class ProjectDiagnosticRows(
    val version: Long,
    /** Files with at least one diagnostic, sorted by path. */
    val files: List<FileDiagnosticRows>,
) {
    companion object {
        val EMPTY = ProjectDiagnosticRows(0L, emptyList())

        /** Parse what `lspDiagnosticRows` returned. Never throws. */
        fun parse(json: String?): ProjectDiagnosticRows {
            if (json.isNullOrEmpty()) return EMPTY
            return try {
                val root = JSONObject(json)
                val array = root.optJSONArray("files")
                val files = ArrayList<FileDiagnosticRows>(array?.length() ?: 0)
                for (i in 0 until (array?.length() ?: 0)) {
                    val entry = array!!.optJSONObject(i) ?: continue
                    files.add(
                        FileDiagnosticRows(
                            path = entry.optString("path", ""),
                            rows = parseRows(entry.optJSONArray("rows")),
                        )
                    )
                }
                ProjectDiagnosticRows(version = root.optLong("version", 0L), files = files)
            } catch (_: org.json.JSONException) {
                EMPTY
            }
        }
    }
}

/**
 * Poll a project's full diagnostic rows, for as long as the caller is
 * composed — which must be only while the diagnostics panel is showing: this
 * read serializes every message in the project on each move of the counter.
 * The counter is [CoreBridge.lspVersion], the same one [rememberLspState]
 * polls, so the panel and the status bar agree on when something changed.
 */
@Composable
fun rememberProjectDiagnostics(projectId: Long?): ProjectDiagnosticRows {
    var state by remember(projectId) { mutableStateOf(ProjectDiagnosticRows.EMPTY) }
    ResumedEffect(projectId) {
        if (projectId == null || projectId < 0) return@ResumedEffect
        pollVersion(
            intervalMs = PROJECT_POLL_MILLIS,
            version = { CoreBridge.lspVersion(projectId) },
            read = { ProjectDiagnosticRows.parse(CoreBridge.lspDiagnosticRows(projectId)) },
            apply = { state = it },
        )
    }
    return state
}

/** What the engine says one server is doing. */
enum class LspServerState { Starting, Running, Unavailable }

/** One language server, as `lspServers` lists it. */
data class LspServer(
    val name: String,
    val state: LspServerState,
    /** The server's own last stderr line when it could not start; else null. */
    val error: String?,
    /** Grammar names, sorted, of the buffers currently registered with it. */
    val languages: List<String>,
    /**
     * What the server says it is doing right now — rust-analyzer's
     * "indexing (45%)" — or null while it is quiet. One line; the engine
     * already picked which token to show.
     */
    val progress: String? = null,
    /** The user stopped it — Zed's `editor::StopLanguageServer`; a restart is the way back. */
    val stopped: Boolean = false,
) {
    /**
     * What to tell the user about a server that is not running, in words —
     * and no words at all for one that is.
     *
     * Zed's LspButton says this with a coloured dot and a popover listing the
     * servers (language_tools/src/lsp_button.rs:1367-1379). The dot is the
     * right size for a desktop with a mouse to hover; on a phone the useful
     * half of that popover is one sentence, and a server that could not start
     * is *always* the cue to install it, so the sentence is the affordance.
     */
    val note: String?
        get() = when (state) {
            LspServerState.Unavailable -> {
                val reason = error?.trim()?.substringBefore('\n')?.takeIf { it.isNotEmpty() }
                when {
                    stopped -> "$name is stopped"
                    reason == null || reason.contains("not found", ignoreCase = true) ->
                        "$name is not installed"
                    else -> "$name could not start: $reason"
                }
            }
            else -> null
        }

    /**
     * Whether the note's action — install this server — is the right answer
     * to it.
     *
     * False for a server the engine refused on the process budget (P5-4,
     * `CAP_REACHED` in lsp.rs): that one is installed and running elsewhere,
     * and the fix is closing a project or a tab, not apt. Offering "install"
     * there would be a button that downloads nothing and changes nothing.
     */
    val installable: Boolean
        get() = state == LspServerState.Unavailable && !stopped &&
            error?.contains("too many language servers", ignoreCase = true) != true
}

fun parseLspServers(json: String?): List<LspServer> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val array = org.json.JSONArray(json)
        val servers = ArrayList<LspServer>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val languages = entry.optJSONArray("languages")
            servers.add(
                LspServer(
                    name = entry.optString("name", ""),
                    state = when (entry.optString("state")) {
                        "running" -> LspServerState.Running
                        "starting" -> LspServerState.Starting
                        else -> LspServerState.Unavailable
                    },
                    error = entry.stringOrNull("error"),
                    languages = (0 until (languages?.length() ?: 0)).mapNotNull {
                        languages?.optString(it)?.takeIf(String::isNotEmpty)
                    },
                    progress = entry.stringOrNull("progress"),
                    stopped = entry.optBoolean("stopped", false),
                )
            )
        }
        servers
    } catch (_: org.json.JSONException) {
        emptyList()
    }
}

/** Everything a project's servers have said, read together under one counter. */
data class LspState(
    val version: Long,
    val summary: DiagnosticSummary,
    val servers: List<LspServer>,
) {
    companion object {
        val EMPTY = LspState(0L, DiagnosticSummary.EMPTY, emptyList())
    }
}

/**
 * Poll a project's LSP state: one counter, and a read only when it moves.
 *
 * The "seen" version lives beside the loop rather than in the effect's keys.
 * That is not a style choice — a `produceState` keyed on a counter that
 * starts at zero and is corrected a frame later runs its body twice, and the
 * hours that cost are written down in `agent-docs/CONVENTIONS.md`,
 * under the traps ("a guest command is a process, not a getter").
 *
 * The loop must keep running even while the counter sits at 0: the bridge
 * documents polling `lspVersion` as what *starts* servers for files that
 * were open before their project, or before `apt install` put the binary
 * there. A project view that stops polling is a project whose servers may
 * never start.
 */
@Composable
fun rememberLspState(projectId: Long?): LspState {
    var state by remember(projectId) { mutableStateOf(LspState.EMPTY) }
    ResumedEffect(projectId) {
        if (projectId == null || projectId < 0) return@ResumedEffect
        pollVersion(
            intervalMs = PROJECT_POLL_MILLIS,
            version = { CoreBridge.lspVersion(projectId) },
            read = { version ->
                LspState(
                    version = version,
                    summary = DiagnosticSummary.parse(CoreBridge.lspDiagnostics(projectId)),
                    servers = parseLspServers(CoreBridge.lspServers(projectId)),
                )
            },
            apply = { state = it },
        )
    }
    return state
}

/**
 * Poll one buffer's diagnostics into [state], for as long as the pane lives.
 *
 * `bufferDiagnosticsVersion` is a hash lookup that does *not* move when the
 * user types — the bridge is explicit that a UI must not be woken by its own
 * typing — so this reads the (potentially large) row payload only when a
 * server has actually published something. The loop lives on the default
 * dispatcher and touches the main thread only for the
 * [EditorState.showDiagnostics] write — [pollVersion]'s contract.
 *
 * Staleness is *not* read from the payload's `stale` flag on every frame,
 * because that flag would then need a read per keystroke to stay true.
 * [EditorState] derives it instead, from the `buffer_version` this read
 * carried against the version the buffer is on now — the same comparison the
 * engine makes, at no cost.
 */
suspend fun pollBufferDiagnostics(state: EditorState) {
    val bufferId = state.sessionOrNull?.id ?: return
    // The counter is what the loop records, not the payload's own `version`
    // field: they agree, but only the counter is still the truth if a
    // payload ever arrives unreadable.
    pollVersion(
        intervalMs = BUFFER_POLL_MILLIS,
        version = { CoreBridge.bufferDiagnosticsVersion(bufferId) },
        read = { BufferDiagnostics.parse(CoreBridge.bufferDiagnostics(bufferId)) },
        apply = { state.showDiagnostics(it) },
    )
}
