package to.eyed.thragg.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where you were in a project — the app's half of `engine/src/session.rs`.
 *
 * Zed persists a workspace in sqlite (workspace/src/persistence.rs): a pane
 * tree, each pane's items, and per-item editor state. This shell is a 400 dp
 * column with one editor and three destinations, so the document is the
 * phone's (docs/UI.md, P9): the open files in most-recently-used order with a
 * caret and a scroll each, which destination was showing, and whether Build
 * was in Shell mode. The engine writes it, reads it back and decides what
 * survives contact with the disk — a file that has gone is dropped, a caret
 * past the end of a file is clamped — because Android kills a backgrounded
 * process holding a 1.4 GB toolchain aggressively, and losing your place every
 * time would be the worst bug in the product.
 *
 * Nothing here is Compose state and nothing here touches the engine: it is
 * the value that travels between the two, which is what makes both ends
 * testable. `ui/shell/SessionRestore.kt` builds one from the live shell and
 * puts one back.
 *
 * The JSON is the engine's, key for key. `org.json` rather than a
 * serialization plugin, which is this project's convention for everything
 * that crosses the bridge.
 */
data class WorkspaceSession(
    /** The project root this describes; the engine refuses a mismatch. */
    val root: String,
    /**
     * The open files, **least recently used first** — the last one is the
     * file that was showing. Opening them in this order rebuilds the file
     * bar's recency order.
     */
    val files: List<SessionItem> = emptyList(),
    /** `code`, `agent` or `build` — [to.eyed.thragg.ui.shell.Destination], lower-cased. */
    val destination: String = "code",
    /** Whether Build was showing its terminal rather than the build controls. */
    val shellMode: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", VERSION)
        put("root", root)
        put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
        put("destination", destination)
        put("shell_mode", shellMode)
    }.toString()

    companion object {
        /**
         * Must match `engine::SESSION_VERSION`; the engine discards anything
         * else. Bumped from 1 — the desktop shell's pane tree — to 2 for the
         * phone's document, in the same commit as the engine's constant
         * (docs/ARCHITECTURE.md requires paired changes).
         */
        const val VERSION = 2

        /**
         * The engine's validated document, or null for anything that is not
         * one. Never throws: a corrupt session is a session lost, not a crash
         * at launch.
         */
        fun parse(json: String?): WorkspaceSession? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(json)
                if (root.optInt("version", -1) != VERSION) return null
                WorkspaceSession(
                    root = root.optString("root"),
                    files = root.optJSONArray("files").mapObjects(SessionItem::parse),
                    destination = root.optString("destination").ifBlank { "code" },
                    shellMode = root.optBoolean("shell_mode", false),
                )
            }.getOrNull()
        }
    }
}

/** One caret: anchor and head, rows 0-based, columns in UTF-16 code units. */
data class SessionSelection(
    val anchorRow: Int,
    val anchorCol: Int,
    val headRow: Int,
    val headCol: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("anchor_row", anchorRow)
        put("anchor_col", anchorCol)
        put("head_row", headRow)
        put("head_col", headCol)
    }

    companion object {
        fun parse(json: JSONObject): SessionSelection = SessionSelection(
            anchorRow = json.optInt("anchor_row", 0).coerceAtLeast(0),
            anchorCol = json.optInt("anchor_col", 0).coerceAtLeast(0),
            headRow = json.optInt("head_row", 0).coerceAtLeast(0),
            headCol = json.optInt("head_col", 0).coerceAtLeast(0),
        )
    }
}

/**
 * What a persisted file is — Zed's `item_kind`. The files that survive a
 * relaunch are the ones opened by a *path*; a diff, the problems list and the
 * agent review are opened by a view and are not written down at all (see
 * `OpenFile.isReopenable`).
 */
enum class SessionItemKind(val key: String) {
    Text("text"),
    Media("media");

    companion object {
        fun fromKey(key: String?): SessionItemKind =
            entries.firstOrNull { it.key == key } ?: Text
    }
}

/** One open file. */
data class SessionItem(
    /** Project-relative, `/`-separated — the key the file bar uses. */
    val path: String,
    val kind: SessionItemKind = SessionItemKind.Text,
    /** [to.eyed.thragg.ui.editor.EditorState.scrollY] in pixels. */
    val scroll: Float = 0f,
    /** Every caret, in document order. */
    val selections: List<SessionSelection> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("kind", kind.key)
        put("scroll", scroll.toDouble())
        put("selections", JSONArray().apply { selections.forEach { put(it.toJson()) } })
    }

    companion object {
        fun parse(json: JSONObject): SessionItem? {
            val path = json.optString("path").takeIf { it.isNotBlank() } ?: return null
            return SessionItem(
                path = path,
                kind = SessionItemKind.fromKey(json.optString("kind")),
                scroll = json.optDouble("scroll", 0.0).toFloat(),
                selections = json.optJSONArray("selections").mapObjects(SessionSelection::parse),
            )
        }
    }
}

/**
 * One project the user has opened before — the engine's `RecentProject`, and
 * a row of the Open Recent picker. Ordered by [lastOpened], newest first, as
 * Zed's recent-projects list is.
 */
data class RecentProject(val path: String, val name: String, val lastOpened: Long) {
    companion object {
        /** The engine's JSON array. Never throws; garbage is an empty list. */
        fun parseList(json: String?): List<RecentProject> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                JSONArray(json).mapObjects { entry ->
                    val path = entry.optString("path").takeIf { it.isNotBlank() }
                        ?: return@mapObjects null
                    RecentProject(
                        path = path,
                        name = entry.optString("name").ifBlank { path.substringAfterLast('/') },
                        lastOpened = entry.optLong("last_opened", 0L),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** Every object of a JSON array that [parse] accepts, in order. */
private inline fun <T> JSONArray?.mapObjects(parse: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val entry = optJSONObject(index) ?: continue
            parse(entry)?.let(::add)
        }
    }
}
