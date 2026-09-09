package to.eyed.thragg.ui.shell

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.ProjectSession
import to.eyed.thragg.core.SessionItem
import to.eyed.thragg.core.SessionItemKind
import to.eyed.thragg.core.SessionSelection
import to.eyed.thragg.core.WorkspaceSession
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.shell.build.ShellModes
import to.eyed.thragg.ui.shell.code.CodeState
import to.eyed.thragg.ui.shell.code.PendingOpen
import to.eyed.thragg.ui.workspace.OpenFile

/**
 * Restore where you left off — P9, shrunk to what a 400 dp column has
 * (docs/UI.md, P9).
 *
 * The document is [WorkspaceSession]: the open files in most-recently-used
 * order with a caret and a scroll each, the destination that was showing and
 * whether Build was in Shell mode. The engine keeps it, one file per project,
 * and makes it honest about the disk on the way back — a file that has gone
 * is dropped, a caret past the end of a file is clamped, a corrupt document is
 * discarded (engine/src/session.rs). This side only *captures* and *applies*.
 *
 * Two hooks write it and one reads it:
 *
 *  - **leaving the app** (`MainActivity.onStop`) — the common case on Android,
 *    where the process holding a 1.4 GB toolchain is killed a minute after it
 *    stops being visible;
 *  - **switching project** ([to.eyed.thragg.ui.shell.projects.openProjectInShell])
 *    — the old project's place is written before its buffers are torn down,
 *    and the new project's is read after it opens.
 *
 * Applying is a queue, not a call: the files go into [CodeState.pendingOpens],
 * which Code drains the moment it is composed with the project, exactly as a
 * build error or a `path:line:col` from the terminal reaches it — so a restore
 * that lands on Agent still has its files open when the thumb reaches Code.
 * The destination is applied only on the launch-time restore and only while
 * nothing has moved the shell off Code yet: a notification tap or a thumb on
 * the bar in the second the open takes must win over a document from
 * yesterday.
 */
object SessionRestore {

    /** The write's own scope: a save is not the composition's business to await. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The document for the open project as the shell stands now, or null with
     * no project. Reads Compose state, so it runs on the **main thread**.
     */
    fun snapshot(state: ShellState, code: CodeState): WorkspaceSession? {
        val project = state.project ?: return null
        // `mruTabs()` is newest first; the document is oldest first so that
        // opening in order rebuilds the same recency, the active file last.
        val files = code.files.mruTabs().asReversed().mapNotNull { file -> file.toSessionItem() }
        return WorkspaceSession(
            root = project.rootPath,
            files = files,
            destination = state.destination.name.lowercase(),
            shellMode = ShellModes.isShell(project.rootPath),
        )
    }

    /** Snapshot now, on the caller's (main) thread; write it off it. */
    fun save(state: ShellState, code: CodeState) {
        val session = snapshot(state, code) ?: return
        io.launch { CoreBridge.sessionSave(session.root, session.toJson()) }
    }

    /**
     * Put [project]'s saved place back: its files into Code's open queue, its
     * Shell mode, and — with [applyDestination] — the destination it was on.
     * Suspending, off the main thread for the read; the queue and the state
     * are written back on the caller's.
     */
    suspend fun restore(
        state: ShellState,
        code: CodeState,
        project: ProjectSession,
        applyDestination: Boolean,
    ) {
        val root = project.rootPath
        val session = withContext(Dispatchers.IO) {
            WorkspaceSession.parse(runCatching { CoreBridge.sessionLoad(root) }.getOrNull())
        } ?: return
        // The project may have changed hands while the file was read.
        if (state.project?.rootPath != root) return
        for (open in plan(session)) code.pendingOpens.add(open)
        ShellModes.set(root, session.shellMode)
        if (applyDestination && state.destination == Destination.Code) {
            destinationOf(session.destination)?.let { if (it != Destination.Code) state.show(it) }
        }
    }

    /**
     * The opens a document asks for, in order — a value function so the
     * ordering rule (oldest first, the active file last, so it ends up active)
     * is tested without an editor.
     */
    fun plan(session: WorkspaceSession): List<PendingOpen> =
        session.files.map { item -> PendingOpen(item.path, restore = item) }

    /** `code` / `agent` / `build` as the shell spells them; null for anything else. */
    fun destinationOf(name: String): Destination? =
        Destination.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun OpenFile.toSessionItem(): SessionItem? {
        if (!isReopenable || path.isBlank() || path.startsWith("/")) return null
        return placeOf(this, path)
    }
}

/**
 * Where [file] is standing right now — caret, selection and scroll — as a
 * place that can be put back into a tab keyed [path].
 *
 * [path] is a parameter rather than `file.path` for the one caller that needs
 * a different one: a file renamed under an open tab is closed and reopened at
 * its new name, and the place has to travel with it (CodeScreen, G-07).
 */
internal fun placeOf(file: OpenFile, path: String = file.path): SessionItem? {
    val editor = file.editor
    return when {
        editor != null -> SessionItem(
            path = path,
            kind = SessionItemKind.Text,
            scroll = editor.scrollY,
            selections = editor.caretsInOrder().map { caret ->
                SessionSelection(caret.anchorRow, caret.anchorCol, caret.headRow, caret.headCol)
            },
        )
        file.media != null -> SessionItem(path = path, kind = SessionItemKind.Media)
        else -> null
    }
}

/**
 * Put a saved caret and scroll back into [file]'s editor — the same steps as
 * `NavEntry.restoreIn`, which is the jump list's version of this. The two
 * frames between the caret and the scroll are what lets the layout catch up
 * before the scroll is measured against it; without them the second call is
 * clamped against a viewport that has not been laid out yet.
 */
internal suspend fun SessionItem.restoreIn(file: OpenFile) {
    val editor = file.editor ?: return
    val primary = selections.firstOrNull()
    if (primary != null) {
        val lastRow = (editor.lineCount - 1).coerceAtLeast(0)
        val startRow = primary.anchorRow.coerceIn(0, lastRow)
        val endRow = primary.headRow.coerceIn(0, lastRow)
        val startCol = primary.anchorCol.coerceIn(0, editor.line(startRow).length)
        val endCol = primary.headCol.coerceIn(0, editor.line(endRow).length)
        val forward = startRow < endRow || (startRow == endRow && startCol <= endCol)
        editor.selectRange(
            if (forward) {
                EditorState.SelectionRange(startRow, startCol, endRow, endCol)
            } else {
                EditorState.SelectionRange(endRow, endCol, startRow, startCol)
            },
        )
    }
    withFrameNanos { }
    withFrameNanos { }
    editor.scrollToY(scroll)
    if (primary != null) editor.ensureCursorVisible()
}
