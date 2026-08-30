package to.eyed.seeker.code.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * One excerpt of a multibuffer: a stretch of one file, and where it sits in
 * the composed document.
 *
 * Rows are 0-based throughout. [headerRow] is the composed row carrying the
 * `// path:12-18` header the engine wrote; [firstRow]..[lastRow] are the rows
 * of file text beneath it, and [fileStartRow] is the file row [firstRow]
 * shows.
 */
data class Excerpt(
    /** Project-relative path, `/`-separated — what the header shows. */
    val path: String,
    val absPath: String,
    /**
     * The engine buffer this excerpt reads from — the same id a tab on the
     * same file holds, which is how a closing tab knows not to release a
     * buffer a multibuffer is still showing.
     */
    val bufferId: Long,
    val headerRow: Int,
    val firstRow: Int,
    val lastRow: Int,
    val fileStartRow: Int,
    val fileEndRow: Int,
    /** This file has unsaved edits. */
    val dirty: Boolean,
) {
    /** The file row a composed row shows; [fileStartRow] for the header. */
    fun fileRowOf(row: Int): Int =
        if (row <= firstRow) fileStartRow else fileStartRow + (row - firstRow)

    /** Whether [row] is this excerpt's header or one of its text rows. */
    fun contains(row: Int): Boolean = row in headerRow..lastRow

    /** `path:12-18`, the label of the sticky header. */
    val label: String
        get() = if (fileStartRow == fileEndRow) {
            "$path:${fileStartRow + 1}"
        } else {
            "$path:${fileStartRow + 1}-${fileEndRow + 1}"
        }
}

/** Everything the UI draws a multibuffer tab from. */
data class MultiBufferInfo(
    val id: Long,
    val title: String,
    /** "search", "references" or "diagnostics". */
    val kind: String,
    /** The engine buffer holding the composition — what the editor renders. */
    val bufferId: Long,
    /** The composition's content version; it moves on every edit or rebuild. */
    val version: Long,
    /**
     * How many times the composition has been rewritten wholesale. A caller
     * whose copy is behind must re-measure the whole document; one that is
     * current only saw its own edits, whose reach the editor already knows.
     */
    val rebuilds: Long,
    /** How many of the files in it have unsaved edits. */
    val dirtyFiles: Int,
    val excerpts: List<Excerpt>,
) {
    /** The excerpt a composed row belongs to, or null between two of them. */
    fun excerptAt(row: Int): Excerpt? = excerpts.lastOrNull { it.headerRow <= row }
        ?.takeIf { it.contains(row) }

    /**
     * The excerpt whose header should stick to the top of the pane for a view
     * scrolled to [row] — the last one that has started, even when [row] has
     * run past its text into the gap before the next header.
     */
    fun stickyAt(row: Int): Excerpt? = excerpts.lastOrNull { it.headerRow <= row }

    companion object {
        fun parse(json: String?): MultiBufferInfo? {
            val root = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
            val excerpts = root.optJSONArray("excerpts") ?: JSONArray()
            return MultiBufferInfo(
                id = root.optLong("id"),
                title = root.optString("title"),
                kind = root.optString("kind"),
                bufferId = root.optLong("buffer"),
                version = root.optLong("version"),
                rebuilds = root.optLong("rebuilds"),
                dirtyFiles = root.optInt("dirty_files"),
                excerpts = (0 until excerpts.length()).mapNotNull { index ->
                    val item = excerpts.optJSONObject(index) ?: return@mapNotNull null
                    Excerpt(
                        path = item.optString("path"),
                        absPath = item.optString("abs_path"),
                        bufferId = item.optLong("buffer"),
                        headerRow = item.optInt("header_row"),
                        firstRow = item.optInt("first_row"),
                        lastRow = item.optInt("last_row"),
                        fileStartRow = item.optInt("file_start_row"),
                        fileEndRow = item.optInt("file_end_row"),
                        dirty = item.optBoolean("dirty"),
                    )
                },
            )
        }
    }
}

/** Where one composed row came from. */
data class MultiBufferLocation(val path: String, val absPath: String, val row: Int)

/** What a save-all did — Zed's SaveAll over the files of one multibuffer. */
data class SaveAllReport(val saved: List<String>, val failed: List<String>) {
    val isEmpty: Boolean get() = saved.isEmpty() && failed.isEmpty()
}

/**
 * Where an excerpt should go, before the engine adds context and merges.
 * Rows are 0-based; [endRow] defaults to [row].
 */
data class ExcerptRequest(
    val path: String,
    val absPath: String? = null,
    val row: Int,
    val endRow: Int = row,
)

/**
 * Handle for one open multibuffer — Zed's `MultiBuffer`, which is what its
 * project search, find-all-references and project diagnostics actually open
 * (search/src/project_search.rs, editor/src/lsp_ext.rs, diagnostics/src).
 *
 * The engine composes the excerpts into a single ordinary buffer, so the
 * editor pane renders [bufferId] with no idea it is a multibuffer; edits it
 * makes are routed back to the file they belong to inside the engine, which
 * is what keeps undo, `didChange` and the dirty flag per file. This class only
 * holds the parts the editor cannot infer: the headers, the row map and the
 * save-all.
 */
class MultiBufferSession private constructor(val id: Long, info: MultiBufferInfo) {
    /**
     * Snapshot state, because the sticky header is drawn from it: a
     * recomposition of the excerpts — a file edited in its own tab, a save —
     * has to reach the bar without anything else changing.
     */
    var info: MultiBufferInfo by mutableStateOf(info)
        private set

    /** The composed buffer the editor renders. */
    val bufferId: Long get() = info.bufferId

    val title: String get() = info.title

    val isDirty: Boolean get() = info.dirtyFiles > 0

    /**
     * Recompose if a file behind this moved — someone edited it in its own
     * tab, or it was reloaded — and re-read the headers when anything changed.
     * Returns true if the composition moved, so the pane knows to re-measure.
     *
     * **Blocking**: it can rebuild the whole composition. Call it off the main
     * thread.
     */
    fun sync(): Boolean {
        val version = CoreBridge.multibufferSync(id)
        if (version < 0 || version == info.version) return false
        // The version also moves for an edit *we* made, which shifted the
        // header rows below it — so the headers are re-read either way. Only a
        // wholesale recomposition needs the editor to re-measure, and that is
        // what the rebuild counter separates out.
        val before = info.rebuilds
        info = MultiBufferInfo.parse(CoreBridge.multibufferInfo(id)) ?: return false
        return info.rebuilds != before
    }

    /** Re-read the headers without recomposing — after a save, say. */
    fun refresh() {
        info = MultiBufferInfo.parse(CoreBridge.multibufferInfo(id)) ?: info
    }

    /**
     * Which file and row a composed row shows. Answered from the headers we
     * already hold, so a tap costs no JNI call; the bridge's
     * [CoreBridge.multibufferLocate] is the same answer for callers without an
     * info in hand.
     */
    fun locate(row: Int): MultiBufferLocation? {
        val excerpt = info.excerptAt(row) ?: return null
        return MultiBufferLocation(excerpt.path, excerpt.absPath, excerpt.fileRowOf(row))
    }

    /**
     * Write every dirty file — Zed's SaveAll, which is what Ctrl+S means over
     * a multibuffer. **Blocking**: call it off the main thread.
     */
    fun saveAll(): SaveAllReport {
        val json = CoreBridge.multibufferSaveAll(id) ?: return SaveAllReport(emptyList(), emptyList())
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return SaveAllReport(emptyList(), emptyList())
        refresh()
        return SaveAllReport(root.strings("saved"), root.strings("failed"))
    }

    /**
     * Close it, releasing the files it opened on demand. [keepBufferIds] are
     * the buffers the caller still has tabs on — the engine has no way to know
     * them, and closing one out from under a tab would leave it drawing a
     * buffer that no longer exists.
     */
    fun close(keepBufferIds: LongArray = LongArray(0)): Boolean =
        CoreBridge.multibufferClose(id, keepBufferIds)

    companion object {
        /**
         * Open a multibuffer over [excerpts]. [root] is the project root that
         * relative paths are resolved against. Returns null when not one of
         * the files could be read.
         *
         * **Blocking** (it opens every file it excerpts) — call it off the
         * main thread.
         */
        fun open(
            title: String,
            kind: String,
            root: String,
            excerpts: List<ExcerptRequest>,
        ): MultiBufferSession? {
            if (excerpts.isEmpty()) return null
            val array = JSONArray()
            for (excerpt in excerpts) {
                array.put(
                    JSONObject()
                        .put("path", excerpt.path)
                        .put("abs", excerpt.absPath ?: "")
                        .put("row", excerpt.row)
                        .put("endRow", excerpt.endRow),
                )
            }
            val id = CoreBridge.multibufferCreate(title, kind, root, array.toString())
            if (id < 0) return null
            val info = MultiBufferInfo.parse(CoreBridge.multibufferInfo(id))
            if (info == null) {
                CoreBridge.multibufferClose(id, LongArray(0))
                return null
            }
            return MultiBufferSession(id, info)
        }
    }
}

private fun JSONObject.strings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.optString(it) }
}
