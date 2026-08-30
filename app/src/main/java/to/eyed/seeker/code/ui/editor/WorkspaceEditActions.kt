package to.eyed.seeker.code.ui.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.seeker.code.core.CoreBridge

/**
 * The UI side of the engine's workspace-edit machinery: references, code
 * actions, rename and formatting — the requests whose answers are *work*, not
 * words.
 *
 * The shape is always the same two steps, and it is the bridge's contract:
 * ask (the engine holds the resulting edit), then [applyPendingEdit] (the
 * engine lands it and says which buffers it changed underneath the UI). This
 * file is the parsing and the two steps; the states and popups that drive
 * them live beside the hover's in LspActions.kt.
 */

/** `optString` coerces JSON null to "null" on Android; ask `isNull` first. */
private fun JSONObject.textOrNull(name: String): String? =
    if (isNull(name)) null else optString(name, null)?.takeIf { it.isNotEmpty() }

/**
 * What a settled rename, formatting or code-action-apply says it is holding:
 * the size of the edit, or the sentence explaining why there is none.
 */
data class EditSummary(
    val files: Int,
    val edits: Int,
    /** The edit also creates, renames or deletes files — it will be refused. */
    val resourceOps: Boolean,
    /** The server had nothing to offer, in words. Null when an edit waits. */
    val error: String?,
) {
    val isEmpty: Boolean get() = error == null && edits == 0

    companion object {
        fun parse(payload: JSONObject?): EditSummary = EditSummary(
            files = payload?.optInt("files", 0) ?: 0,
            edits = payload?.optInt("edits", 0) ?: 0,
            resourceOps = payload?.optBoolean("resource_ops", false) ?: false,
            error = payload?.textOrNull("error"),
        )
    }
}

/** One file [applyPendingEdit] touched. */
data class AppliedFile(
    /** Absolute and canonical — match it against open tabs' absolute paths. */
    val path: String,
    /** The open buffer it landed in, or null for a file edited on disk. */
    val bufferId: Long?,
    val edits: Int,
)

/** What applying a pending edit did. */
data class EditReceipt(
    val applied: Boolean,
    val error: String?,
    val files: List<AppliedFile>,
) {
    companion object {
        val REFUSED = EditReceipt(applied = false, error = "nothing to apply", files = emptyList())

        fun parse(json: String?): EditReceipt {
            if (json.isNullOrEmpty()) return REFUSED
            return try {
                val root = JSONObject(json)
                val array = root.optJSONArray("files")
                val files = ArrayList<AppliedFile>(array?.length() ?: 0)
                for (i in 0 until (array?.length() ?: 0)) {
                    val entry = array!!.optJSONObject(i) ?: continue
                    files.add(
                        AppliedFile(
                            path = entry.optString("path", ""),
                            bufferId =
                                if (entry.isNull("buffer_id")) null else entry.optLong("buffer_id"),
                            edits = entry.optInt("edits", 0),
                        )
                    )
                }
                EditReceipt(
                    applied = root.optBoolean("applied", false),
                    error = root.textOrNull("error"),
                    files = files,
                )
            } catch (_: org.json.JSONException) {
                REFUSED
            }
        }
    }
}

/** One place a symbol is used, as `lspRequestReferences` lists them. */
data class ReferenceTarget(
    val path: String,
    val row: Int,
    val colUtf16: Int,
    val endRow: Int,
    val endColUtf16: Int,
    /** The trimmed text of the line, when the engine could read it. */
    val lineText: String?,
) {
    /** The jump itself is a definition jump; reuse its plumbing whole. */
    fun asDefinition(): DefinitionTarget =
        DefinitionTarget(path, row, colUtf16, endRow, endColUtf16)
}

fun parseReferenceTargets(payload: JSONObject?): List<ReferenceTarget> {
    val array = payload?.optJSONArray("targets") ?: return emptyList()
    val targets = ArrayList<ReferenceTarget>(array.length())
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val path = entry.optString("path", "")
        if (path.isEmpty()) continue
        val row = entry.optInt("row", 0)
        val col = entry.optInt("col_utf16", 0)
        targets.add(
            ReferenceTarget(
                path = path,
                row = row,
                colUtf16 = col,
                endRow = entry.optInt("end_row", row),
                endColUtf16 = entry.optInt("end_col_utf16", col),
                lineText = entry.textOrNull("line_text"),
            )
        )
    }
    return targets
}

/** One row of the code-action menu, as `lspRequestCodeActions` lists them. */
data class CodeActionItem(
    /** What `lspRequestCodeActionApply` takes. */
    val index: Int,
    val title: String,
    /** LSP's kind string ("quickfix", "refactor.extract"), or null. */
    val kind: String?,
    /** The server's best answer; the menu puts it first. */
    val isPreferred: Boolean,
    /** Why it cannot run, when it cannot. Null for an action that can. */
    val disabled: String?,
)

fun parseCodeActions(payload: JSONObject?): List<CodeActionItem> {
    val array = payload?.optJSONArray("actions") ?: return emptyList()
    val actions = ArrayList<CodeActionItem>(array.length())
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        actions.add(
            CodeActionItem(
                index = entry.optInt("index", i),
                title = entry.optString("title", ""),
                kind = entry.textOrNull("kind"),
                isPreferred = entry.optBoolean("is_preferred", false),
                disabled = entry.textOrNull("disabled"),
            )
        )
    }
    // The server's preferred fix first, exactly as Zed sorts its menu.
    return actions.sortedByDescending { it.isPreferred }
}

/**
 * Rename the symbol at a caret. Settles with an [EditSummary] payload; the
 * edit waits at the engine for [applyPendingEdit]. Threading and cancel
 * semantics are [pollLspRequest]'s.
 */
suspend fun requestRename(bufferId: Long, row: Int, colUtf16: Int, newName: String): LspAnswer? {
    val id = withContext(Dispatchers.Default) {
        CoreBridge.lspRequestRename(bufferId, row.toLong(), colUtf16.toLong(), newName)
    }
    return pollLspRequest(id)
}

/** Format the whole document. Contract as [requestRename]. */
suspend fun requestFormatting(bufferId: Long): LspAnswer? {
    val id = withContext(Dispatchers.Default) { CoreBridge.lspRequestFormatting(bufferId) }
    return pollLspRequest(id)
}

/**
 * Pick action [index] out of a settled code-action list and ready its edit.
 * The list request must still be alive — it is what holds the actions.
 */
suspend fun requestCodeActionApply(listRequestId: Long, index: Int): LspAnswer? {
    val id = withContext(Dispatchers.Default) {
        CoreBridge.lspRequestCodeActionApply(listRequestId, index.toLong())
    }
    return pollLspRequest(id)
}

/**
 * Land the edit a settled request is holding. Blocking at the bridge, so it
 * runs on IO; the receipt names every touched file, and the caller must call
 * [EditorState.noteExternalEdit] on each open editor it names — the engine
 * has already changed those buffers underneath them.
 */
suspend fun applyPendingEdit(requestId: Long): EditReceipt = withContext(Dispatchers.IO) {
    EditReceipt.parse(CoreBridge.lspApplyPendingEdit(requestId))
}
