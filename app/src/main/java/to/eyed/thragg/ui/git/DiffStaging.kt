package to.eyed.thragg.ui.git

import to.eyed.thragg.core.GitHunk
import to.eyed.thragg.core.PatchHunk

/**
 * The arithmetic behind the project diff's Stage / Unstage / Restore buttons
 * — Zed's `project_diff.rs` staging, which asks the buffer diff for the hunk
 * under each header. This app's diff tab draws git's own patch, whose `@@`
 * blocks carry context lines and merge changes fewer than six rows apart,
 * while the engine stages by *buffer rows* against its own context-free
 * hunks. These two functions translate one into the other, and are tested
 * on the host because the deletion case — a change with no rows on the new
 * side at all — is where a translation goes quietly wrong.
 */

/**
 * The rows of the file as it is now that a patch hunk changed, 0-based and
 * inclusive: from the first changed line's new-side row to the last's. A run
 * of removed lines has no rows of its own and counts as the boundary it sits
 * on — the row of the next kept line, which is where the gutter's deletion
 * pill is drawn and where the engine's hunk for it lies. Null for a hunk of
 * nothing but context.
 */
internal fun changedRows(hunk: PatchHunk): IntRange? {
    var first = Int.MAX_VALUE
    var last = Int.MIN_VALUE
    // The new-side row the next line will occupy — what a deletion is
    // anchored to. Starts at the hunk's own header; every kept or added line
    // advances it.
    var nextNewRow = (hunk.newStart - 1).coerceAtLeast(0)
    for (line in hunk.lines) {
        when (line.kind) {
            '+' -> {
                val row = line.newLine - 1
                first = minOf(first, row)
                last = maxOf(last, row)
                nextNewRow = row + 1
            }
            '-' -> {
                first = minOf(first, nextNewRow)
                last = maxOf(last, nextNewRow)
            }
            else -> nextNewRow = line.newLine
        }
    }
    return if (first == Int.MAX_VALUE) null else first..last
}

/**
 * Whether one of the engine's hunks lies on [rows] — the engine's own
 * inclusive `touches` (git_hunks.rs), with a deletion matched on its
 * boundary row.
 */
internal fun GitHunk.touchesRows(rows: IntRange): Boolean =
    startRow <= rows.last + 1 && maxOf(endRow, startRow) >= rows.first

/**
 * The staged bit for a patch hunk: true when every engine hunk on its rows is
 * in the index, which is when the header should read Unstage — Zed's
 * `has_secondary_hunk` read across the block. Null when the engine has no
 * hunk there, which means the answer is not known yet.
 */
internal fun hunkStagedState(states: List<GitHunk>, rows: IntRange): Boolean? {
    val touching = states.filter { it.touchesRows(rows) }
    if (touching.isEmpty()) return null
    return touching.all { it.staged == true }
}

/** The file's bit: every hunk staged and at least one to stage. */
internal fun fileStagedState(states: List<GitHunk>): Boolean? =
    if (states.isEmpty()) null else states.all { it.staged == true }
