package to.eyed.seeker.code.ui.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.core.GitDiff
import to.eyed.seeker.code.core.GitHunk

/**
 * Zed's per-hunk git commands on an editor — `git::ToggleStaged`,
 * `git::StageAndNext`, `git::UnstageAndNext` and `git::Restore`
 * (editor/src/git.rs `stage_or_unstage_diff_hunks`, `restore_hunks_in_ranges`)
 * — for the three ways in: the chord, the palette, and the expanded block's
 * header buttons.
 *
 * Every command here runs git (or, for a restore, edits the buffer) off the
 * main thread, one at a time per editor: [EditorState.hunkActionBusy] is the
 * single-flight, and what greys the header buttons while a command is out.
 * What git said when it refused lands in [EditorState.hunkError], which the
 * pane shows over the text until the next command or a tap.
 */
internal object GitHunkActions {

    /**
     * Stage or unstage every hunk touching [rows]. [then] runs on the main
     * thread after a success — StageAndNext's "and next".
     */
    fun stage(state: EditorState, scope: CoroutineScope, rows: IntRange, stage: Boolean, then: () -> Unit = {}) {
        val session = state.sessionOrNull ?: return
        run(state, scope, { GitDiff.stageHunk(session.id, rows, stage) }) {
            state.hunkStagingToken++
            then()
        }
    }

    /**
     * Zed's `git::ToggleStaged`: the hunk under the caret is staged if the
     * index does not hold it, unstaged if it does. The staged bit is asked of
     * the engine fresh rather than read from the header's cache — the cache
     * exists only while a block is showing, and the chord works on a
     * collapsed hunk too.
     */
    fun toggleStagedAtCaret(state: EditorState, scope: CoroutineScope): Boolean {
        val session = state.sessionOrNull ?: return false
        val rows = caretRows(state)
        if (hunksIn(state, rows).isEmpty()) return false
        run(state, scope, {
            val states = GitDiff.hunkStates(session.id)
            states.error?.let { return@run it }
            val target = states.hunks.firstOrNull { it.touches(rows) } ?: return@run null
            GitDiff.stageHunk(session.id, rows, stage = target.staged != true)
        }) {
            state.hunkStagingToken++
        }
        return true
    }

    /**
     * Zed's `git::StageAndNext` / `UnstageAndNext`: the hunk under the caret,
     * then the caret moves to the next hunk (git.rs `stage_and_next`).
     */
    fun stageAndNext(state: EditorState, scope: CoroutineScope, stage: Boolean): Boolean {
        val rows = caretRows(state)
        if (hunksIn(state, rows).isEmpty()) return false
        this.stage(state, scope, rows, stage) { state.goToHunk(forward = true) }
        return true
    }

    /**
     * Zed's `git::Restore`: HEAD's rows go back over every hunk touching
     * [rows]. The engine edits the buffer; the editor resyncs its caches
     * afterwards the way it does after any edit from below.
     */
    fun restore(state: EditorState, scope: CoroutineScope, rows: IntRange) {
        val session = state.sessionOrNull ?: return
        run(state, scope, { GitDiff.restoreHunk(session.id, rows) }) {
            state.noteExternalEdit()
            state.hunkStagingToken++
        }
    }

    /** [restore] for the hunks under the caret — the chord and the palette. */
    fun restoreAtCaret(state: EditorState, scope: CoroutineScope): Boolean {
        val rows = caretRows(state)
        if (hunksIn(state, rows).isEmpty()) return false
        restore(state, scope, rows)
        return true
    }

    /** The rows the primary caret or its selection covers. */
    private fun caretRows(state: EditorState): IntRange {
        val caret = state.primaryCaret()
        return caret.startRow..caret.endRow
    }

    /** The gutter's hunks touching [rows], deletions on their boundary included. */
    internal fun hunksIn(state: EditorState, rows: IntRange): List<GitHunk> =
        state.gitHunks.filter { it.touches(rows) }

    private fun GitHunk.touches(rows: IntRange): Boolean =
        startRow <= rows.last + 1 && endRow.coerceAtLeast(startRow) >= rows.first &&
            // A deletion sits *between* rows: it belongs to the row below
            // its boundary, not to the one above.
            (endRow > startRow || startRow in rows)

    private fun run(
        state: EditorState,
        scope: CoroutineScope,
        action: suspend () -> String?,
        onSuccess: () -> Unit,
    ) {
        if (state.hunkActionBusy) return
        state.hunkActionBusy = true
        state.hunkError = null
        scope.launch {
            val failure = withContext(Dispatchers.IO) { action() }
            state.hunkActionBusy = false
            if (failure == null) onSuccess() else state.hunkError = failure
        }
    }
}
