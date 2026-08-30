package to.eyed.seeker.code.ui.editor

import org.json.JSONArray
import org.json.JSONObject

/**
 * One merge conflict as git left it in the file — the engine's
 * `ConflictRegion` (engine/src/git_conflict.rs), which is Zed's
 * (crates/project/src/git_store/conflict_set.rs:95-103) in rows.
 *
 * Only rows travel to this side: the offsets the resolution edits are the
 * engine's business, and it re-reads them from the live text when asked
 * rather than trusting a copy that may be a poll behind. Every range here is
 * half-open, 0-based buffer rows.
 */
data class ConflictRegion(
    /** The label after `<<<<<<<` — `HEAD` when git wrote none. */
    val oursBranchName: String,
    /** The label after `>>>>>>>` — `Origin` when git wrote none. */
    val theirsBranchName: String,
    /** Row of the `<<<<<<<` line. */
    val startRow: Int,
    /** One past the row of the `>>>>>>>` line. */
    val endRow: Int,
    val oursStartRow: Int,
    val oursEndRow: Int,
    /** The diff3 base, or -1..-1 when the conflict has none. */
    val baseStartRow: Int,
    val baseEndRow: Int,
    val theirsStartRow: Int,
    val theirsEndRow: Int,
) {
    val hasBase: Boolean get() = baseStartRow >= 0

    /** Whether [row] is inside this conflict, markers included. */
    operator fun contains(row: Int): Boolean = row >= startRow && row < endRow

    /**
     * Which of Zed's five row highlights [row] gets, or null outside the
     * region. Zed paints the whole region in the *theirs* colour first and
     * then the `<<<<<<<` line and ours over it in the *ours* colour
     * (conflict_view.rs:307-326), so the base and the `=======` line — which
     * belong to neither side — come out in theirs' colour, as here.
     */
    fun sideOf(row: Int): ConflictSide? = when {
        row < startRow || row >= endRow -> null
        row < oursEndRow -> ConflictSide.Ours
        else -> ConflictSide.Theirs
    }

    companion object {
        /** The engine's JSON, as [to.eyed.seeker.code.core.CoreBridge.bufferConflicts] writes it. */
        fun parseAll(json: String?): List<ConflictRegion> {
            if (json.isNullOrEmpty()) return emptyList()
            val array = try {
                JSONArray(json)
            } catch (_: org.json.JSONException) {
                return emptyList()
            }
            return List(array.length()) { index -> of(array.getJSONObject(index)) }
        }

        private fun of(json: JSONObject): ConflictRegion {
            val ours = json.getJSONObject("ours_rows")
            val theirs = json.getJSONObject("theirs_rows")
            val base = json.optJSONObject("base_rows")
            return ConflictRegion(
                oursBranchName = json.optString("ours_branch_name", "HEAD"),
                theirsBranchName = json.optString("theirs_branch_name", "Origin"),
                startRow = json.getInt("start_row"),
                endRow = json.getInt("end_row"),
                oursStartRow = ours.getInt("start"),
                oursEndRow = ours.getInt("end"),
                baseStartRow = base?.getInt("start") ?: -1,
                baseEndRow = base?.getInt("end") ?: -1,
                theirsStartRow = theirs.getInt("start"),
                theirsEndRow = theirs.getInt("end"),
            )
        }
    }
}

/** Which colour a row of a conflict is painted in — see [ConflictRegion.sideOf]. */
enum class ConflictSide { Ours, Theirs }

/**
 * The conflict [row] falls in, markers included, or null. A binary search:
 * it is asked once per drawn row, per frame, and the regions are disjoint
 * and in order.
 */
fun conflictAt(conflicts: List<ConflictRegion>, row: Int): ConflictRegion? {
    var low = 0
    var high = conflicts.size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val conflict = conflicts[mid]
        when {
            row < conflict.startRow -> high = mid - 1
            row >= conflict.endRow -> low = mid + 1
            else -> return conflict
        }
    }
    return null
}

/**
 * The conflict to go to from [row]: the first one starting after it going
 * forward, the last one starting before it going back, wrapping at either
 * end the way the diagnostic motions wrap. Null with no conflicts at all —
 * and the conflict the caret is *in* does not count as the next one, or
 * pressing "next" on a marker line would go nowhere.
 */
fun nextConflict(conflicts: List<ConflictRegion>, row: Int, forward: Boolean): ConflictRegion? {
    if (conflicts.isEmpty()) return null
    return if (forward) {
        conflicts.firstOrNull { it.startRow > row } ?: conflicts.first()
    } else {
        conflicts.lastOrNull { it.startRow < row } ?: conflicts.last()
    }
}
