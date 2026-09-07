package to.eyed.thragg.ui.git

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.GitDiff
import to.eyed.thragg.core.GitHunk
import to.eyed.thragg.core.ResumedEffect
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.editor.HunkBlock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** How often the engine's hunk counter is re-read. */
private const val HUNK_POLL_MS = 250L

/**
 * What git has to say about the open file, as the editor draws it: the
 * gutter's hunks.
 *
 * Hunks are a cache in the engine — reading the counter is what schedules the
 * diff, and reading the hunks never runs git — so they are polled. Blame,
 * which ran git inside proot over the whole file, went with the blame column
 * and the inline blame (docs/UI.md, "What is removed").
 */
class GitAnnotations(
    val hunks: List<GitHunk>,
) {
    companion object {
        val NONE = GitAnnotations(emptyList())
    }
}

@Composable
fun rememberGitAnnotations(editor: EditorState): GitAnnotations {
    val session = editor.sessionOrNull
    var hunks by remember(session) { mutableStateOf(emptyList<GitHunk>()) }
    // Bumped every time the buffer becomes clean — which is every save, and is
    // the moment a hunk's staged bit can have changed.
    var savedToken by remember(session) { mutableStateOf(0) }
    var isDirty by remember(session) { mutableStateOf(false) }

    ResumedEffect(session) {
        if (session == null) return@ResumedEffect
        // The whole loop stays here on Default — both reads are JNI calls
        // that take the engine's locks, and neither belongs on the frame's
        // thread, cheap as they are — and the main thread is touched only
        // when something moved.
        withContext(Dispatchers.Default) {
            var seenHunks = -1L
            // From the state, not `false`: this block restarts on every
            // return to the foreground, and a re-baseline would miss the
            // buffer having been saved (or dirtied) while the app was away.
            var wasDirty = isDirty
            while (true) {
                val version = GitDiff.hunksVersion(session.id)
                val dirty = session.isDirty
                val rows = if (version != seenHunks) GitDiff.hunks(session.id) else null
                seenHunks = version
                if (rows != null || dirty != wasDirty) {
                    val dirtyChanged = dirty != wasDirty
                    wasDirty = dirty
                    withContext(Dispatchers.Main) {
                        rows?.let {
                            hunks = it
                            // The state's copy is what the hunk commands
                            // walk and what the expanded blocks hang off.
                            editor.setGitHunks(it)
                        }
                        if (dirtyChanged) {
                            isDirty = dirty
                            if (!dirty) savedToken++
                        }
                    }
                }
                delay(HUNK_POLL_MS)
            }
        }
    }

    // The blocks of the expanded hunks. Rebuilt when the hunks move or the
    // expanded set changes; the base lines are a cache read in the engine,
    // but a JNI call nonetheless, so they are read off the main thread.
    val allExpanded = editor.allHunksExpanded
    val expandedRows = editor.expandedHunkRows
    LaunchedEffect(session, hunks, allExpanded, expandedRows) {
        if (session == null || (!allExpanded && expandedRows.isEmpty())) {
            editor.setHunkBlocks(emptyList())
            return@LaunchedEffect
        }
        val blocks = withContext(Dispatchers.Default) {
            hunks.mapNotNull { hunk ->
                if (!allExpanded && hunk.startRow !in expandedRows) return@mapNotNull null
                // Null while the base is still on its way — and then the
                // hunks are on their way too, so this is a frame at most.
                val oldLines = GitDiff.hunkBaseLines(session.id, hunk) ?: return@mapNotNull null
                HunkBlock(hunk, oldLines)
            }
        }
        editor.setHunkBlocks(blocks)
    }

    // The staged bit per hunk — git, so only while a block is showing that
    // reads it, and again after each stage, unstage and save.
    val stagingToken = editor.hunkStagingToken
    val anyExpanded = allExpanded || expandedRows.isNotEmpty()
    LaunchedEffect(session, hunks, anyExpanded, stagingToken, savedToken) {
        if (session == null || !anyExpanded) return@LaunchedEffect
        val states = withContext(Dispatchers.IO) { GitDiff.hunkStates(session.id) }
        if (states.error == null) {
            editor.hunkStaged = states.hunks.associate { it.startRow to (it.staged ?: false) }
        }
    }

    return remember(hunks) { GitAnnotations(hunks) }
}
