package to.eyed.thragg.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The workspace as a document — the app's half of `engine/src/session.rs`.
 *
 * Zed persists a workspace in sqlite (workspace/src/persistence.rs): the pane
 * group as a tree of axes and panes, each pane's items with their kind and
 * active flag, and per-item editor state — `scroll_anchor` and `selections` —
 * written by editor/src/persistence.rs. This is the same shape as plain data,
 * and the engine is the one that writes it, reads it back and decides what
 * survives contact with the disk.
 *
 * Nothing here is Compose state and nothing here touches the engine: it is
 * the value that travels between the two, which is what makes both ends
 * testable. [to.eyed.thragg.ui.workspace.captureSession] builds one
 * from the live workspace and
 * [to.eyed.thragg.ui.workspace.applySession] puts one back.
 *
 * The JSON is the engine's, key for key. `org.json` rather than a
 * serialization plugin, which is this project's convention for everything
 * that crosses the bridge.
 */
data class WorkspaceSession(
    /** The project root this describes; the engine refuses a mismatch. */
    val root: String,
    val panes: SessionPane,
    val docks: SessionDocks = SessionDocks(),
    /**
     * Terminal tabs. The processes died with the app — a session is a
     * process tree — so this is only what it takes to start fresh shells
     * where the last ones stood.
     */
    val terminals: List<SessionTerminal> = emptyList(),
    /** Whether the active pane filled the work area — Zed's `Pane::zoomed`. */
    val zoomed: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("version", VERSION)
        put("root", root)
        put("panes", panes.toJson())
        put("docks", docks.toJson())
        put("terminals", JSONArray().apply { terminals.forEach { put(it.toJson()) } })
        put("zoomed", zoomed)
    }.toString()

    companion object {
        /** Must match `engine::SESSION_VERSION`; the engine discards anything else. */
        const val VERSION = 1

        /**
         * Read a document the engine handed back. Null for anything that
         * does not parse — the engine has already validated what it returns,
         * so this is the belt to its braces rather than the only check.
         */
        fun parse(json: String?): WorkspaceSession? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val root = JSONObject(json)
                if (root.optInt("version", -1) != VERSION) return null
                WorkspaceSession(
                    root = root.optString("root"),
                    panes = SessionPane.parse(root.optJSONObject("panes")) ?: return null,
                    docks = SessionDocks.parse(root.optJSONObject("docks")),
                    terminals = root.optJSONArray("terminals").mapObjects(SessionTerminal::parse),
                    zoomed = root.optBoolean("zoomed", false),
                )
            }.getOrNull()
        }
    }
}

/** One caret: an anchor and a head, both 0-based row plus UTF-16 column. */
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
 * What a persisted tab is — Zed's `item_kind`. The tabs that survive a
 * relaunch are the ones opened by a *path*; a diff, the commit graph, the
 * diagnostics and the agent review are opened by a view and are not written
 * down at all (see `OpenFile.isReopenable`).
 */
enum class SessionItemKind(val key: String) {
    Text("text"),
    Media("media");

    companion object {
        fun fromKey(key: String?): SessionItemKind =
            entries.firstOrNull { it.key == key } ?: Text
    }
}

/** One tab of one pane. */
data class SessionItem(
    /** Project-relative, `/`-separated — the key the tab strip uses. */
    val path: String,
    val kind: SessionItemKind = SessionItemKind.Text,
    val pinned: Boolean = false,
    /**
     * A *preview* tab — Zed's `preview_tabs`, at most one per pane. Zed
     * serializes this too, so a project browsed but not committed to comes
     * back the way it was left.
     */
    val preview: Boolean = false,
    /** [to.eyed.thragg.ui.editor.EditorState.scrollY] in pixels. */
    val scroll: Float = 0f,
    /** Every caret, in document order. */
    val selections: List<SessionSelection> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("kind", kind.key)
        put("pinned", pinned)
        put("preview", preview)
        put("scroll", scroll.toDouble())
        put("selections", JSONArray().apply { selections.forEach { put(it.toJson()) } })
    }

    companion object {
        fun parse(json: JSONObject): SessionItem? {
            val path = json.optString("path").takeIf { it.isNotBlank() } ?: return null
            return SessionItem(
                path = path,
                kind = SessionItemKind.fromKey(json.optString("kind")),
                pinned = json.optBoolean("pinned", false),
                preview = json.optBoolean("preview", false),
                scroll = json.optDouble("scroll", 0.0).toFloat(),
                selections = json.optJSONArray("selections").mapObjects(SessionSelection::parse),
            )
        }
    }
}

/** One entry of a pane's jump list — [to.eyed.thragg.ui.workspace.NavEntry]. */
data class SessionNavEntry(
    val path: String,
    val row: Int = 0,
    val col: Int = 0,
    val scroll: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("row", row)
        put("col", col)
        put("scroll", scroll.toDouble())
    }

    companion object {
        fun parse(json: JSONObject): SessionNavEntry? {
            val path = json.optString("path").takeIf { it.isNotBlank() } ?: return null
            return SessionNavEntry(
                path = path,
                row = json.optInt("row", 0).coerceAtLeast(0),
                col = json.optInt("col", 0).coerceAtLeast(0),
                scroll = json.optDouble("scroll", 0.0).toFloat(),
            )
        }
    }
}

/** A pane's GoBack/GoForward stacks, oldest first. */
data class SessionNavHistory(
    val back: List<SessionNavEntry> = emptyList(),
    val forward: List<SessionNavEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = back.isEmpty() && forward.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("back", JSONArray().apply { back.forEach { put(it.toJson()) } })
        put("forward", JSONArray().apply { forward.forEach { put(it.toJson()) } })
    }

    companion object {
        fun parse(json: JSONObject?): SessionNavHistory {
            if (json == null) return SessionNavHistory()
            return SessionNavHistory(
                back = json.optJSONArray("back").mapObjects(SessionNavEntry::parse),
                forward = json.optJSONArray("forward").mapObjects(SessionNavEntry::parse),
            )
        }
    }
}

/** Which way an axis lays its members out — gpui's `Axis`. */
enum class SessionAxis(val key: String) {
    Horizontal("horizontal"),
    Vertical("vertical");

    companion object {
        fun fromKey(key: String?): SessionAxis =
            entries.firstOrNull { it.key == key } ?: Horizontal
    }
}

/**
 * One node of the pane tree — Zed's `SerializedPaneGroup`: a pane, or an
 * axis of members with their flexes.
 */
sealed interface SessionPane {
    fun toJson(): JSONObject

    data class Leaf(
        val items: List<SessionItem> = emptyList(),
        /** Index into [items], or -1 for a pane with nothing in it. */
        val activeIndex: Int = -1,
        /** Whether this is the pane the workspace's commands act on. */
        val active: Boolean = false,
        val history: SessionNavHistory = SessionNavHistory(),
    ) : SessionPane {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("kind", "leaf")
            put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            put("active_index", activeIndex)
            put("active", active)
            if (!history.isEmpty) put("history", history.toJson())
        }
    }

    data class Split(
        val axis: SessionAxis,
        val children: List<SessionPane>,
        /** One per child, summing to the child count — Zed's invariant. */
        val flexes: List<Float>,
    ) : SessionPane {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("kind", "split")
            put("axis", axis.key)
            put("children", JSONArray().apply { children.forEach { put(it.toJson()) } })
            put("flexes", JSONArray().apply { flexes.forEach { put(it.toDouble()) } })
        }
    }

    companion object {
        fun parse(json: JSONObject?): SessionPane? {
            if (json == null) return null
            return when (json.optString("kind")) {
                "split" -> {
                    val children = json.optJSONArray("children")
                    val parsed = buildList {
                        for (index in 0 until (children?.length() ?: 0)) {
                            parse(children!!.optJSONObject(index))?.let(::add)
                        }
                    }
                    if (parsed.size < 2) return parsed.firstOrNull()
                    val flexes = json.optJSONArray("flexes")
                    Split(
                        axis = SessionAxis.fromKey(json.optString("axis")),
                        children = parsed,
                        flexes = List(flexes?.length() ?: 0) {
                            flexes!!.optDouble(it, 1.0).toFloat()
                        },
                    )
                }
                else -> Leaf(
                    items = json.optJSONArray("items").mapObjects(SessionItem::parse),
                    activeIndex = json.optInt("active_index", -1),
                    active = json.optBoolean("active", false),
                    history = SessionNavHistory.parse(json.optJSONObject("history")),
                )
            }
        }
    }
}

/** One dock's occupant: the panel's settings key, and the dock's width in dp. */
data class SessionDock(val panel: String = "", val width: Float = 0f) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("panel", panel)
        put("width", width.toDouble())
    }

    companion object {
        fun parse(json: JSONObject?): SessionDock {
            if (json == null) return SessionDock()
            return SessionDock(
                panel = json.optString("panel"),
                width = json.optDouble("width", 0.0).toFloat(),
            )
        }
    }
}

/** The terminal dock, which is a dock of its own here rather than a panel. */
data class SessionTerminalDock(val open: Boolean = false, val height: Float = 0f) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("open", open)
        put("height", height.toDouble())
    }

    companion object {
        fun parse(json: JSONObject?): SessionTerminalDock {
            if (json == null) return SessionTerminalDock()
            return SessionTerminalDock(
                open = json.optBoolean("open", false),
                height = json.optDouble("height", 0.0).toFloat(),
            )
        }
    }
}

/** One terminal tab: what it was called and where it was standing. */
data class SessionTerminal(val title: String, val cwd: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("cwd", cwd)
    }

    companion object {
        fun parse(json: JSONObject): SessionTerminal? {
            val cwd = json.optString("cwd").takeIf { it.isNotBlank() } ?: return null
            return SessionTerminal(title = json.optString("title"), cwd = cwd)
        }
    }
}

/** Everything the docks remember. */
data class SessionDocks(
    val left: SessionDock = SessionDock(),
    val right: SessionDock = SessionDock(),
    /** Which side was opened most recently — `DockLayout.lastOpened`. */
    val lastOpenedRight: Boolean = false,
    val terminal: SessionTerminalDock = SessionTerminalDock(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("left", left.toJson())
        put("right", right.toJson())
        put("last_opened_right", lastOpenedRight)
        put("terminal", terminal.toJson())
    }

    companion object {
        fun parse(json: JSONObject?): SessionDocks {
            if (json == null) return SessionDocks()
            return SessionDocks(
                left = SessionDock.parse(json.optJSONObject("left")),
                right = SessionDock.parse(json.optJSONObject("right")),
                lastOpenedRight = json.optBoolean("last_opened_right", false),
                terminal = SessionTerminalDock.parse(json.optJSONObject("terminal")),
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
