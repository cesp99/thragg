@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package to.eyed.thragg.ui.shell.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import to.eyed.thragg.core.AgentMention
import to.eyed.thragg.core.AppSettings
import to.eyed.thragg.core.BufferSession
import to.eyed.thragg.core.CoreBridge
import to.eyed.thragg.core.FormatterSpec
import to.eyed.thragg.core.LOCAL_SETTINGS_PATH
import to.eyed.thragg.core.LanguageSettings
import to.eyed.thragg.core.ProjectSession
import to.eyed.thragg.core.ResumedEffect
import to.eyed.thragg.core.SessionItem
import to.eyed.thragg.core.ShareOut
import to.eyed.thragg.R
import to.eyed.thragg.ui.common.BinaryPlaceholder
import to.eyed.thragg.ui.common.UnsavedChangesDialog
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.editor.DiagnosticSeverity
import to.eyed.thragg.ui.editor.EditReceipt
import to.eyed.thragg.ui.editor.EditSummary
import to.eyed.thragg.ui.editor.EditorOverlays
import to.eyed.thragg.ui.editor.EditorPane
import to.eyed.thragg.ui.editor.EditorState
import to.eyed.thragg.ui.editor.LspRequestState
import to.eyed.thragg.ui.editor.SoftWrapMode
import to.eyed.thragg.ui.editor.applyPendingEdit
import to.eyed.thragg.ui.editor.pollLspRequest
import to.eyed.thragg.ui.editor.requestFormatting
import to.eyed.thragg.ui.editor.revealDefinitionTarget
import to.eyed.thragg.solana.build.BuildRunner
import to.eyed.thragg.ui.media.MediaKind
import to.eyed.thragg.ui.search.BufferSearchBar
import to.eyed.thragg.ui.search.SearchDeploy
import to.eyed.thragg.ui.shell.BackSeam
import to.eyed.thragg.ui.shell.BuildState
import to.eyed.thragg.ui.shell.Destination
import to.eyed.thragg.ui.shell.Route
import to.eyed.thragg.ui.shell.ShellState
import to.eyed.thragg.ui.shell.placeOf
import to.eyed.thragg.ui.shell.restoreIn
import to.eyed.thragg.ui.shell.agent.AgentSeams
import to.eyed.thragg.ui.shell.agent.agentFixPrompt
import to.eyed.thragg.ui.shell.build.CodeJump
import to.eyed.thragg.ui.shell.projects.ProjectsSheet
import to.eyed.thragg.ui.components.EmptyState
import to.eyed.thragg.ui.components.HairlineDivider
import to.eyed.thragg.ui.components.ThraggTopBar
import to.eyed.thragg.ui.shell.changes.countFileProblems
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.pressScale
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.ThraggIconButton
import to.eyed.thragg.ui.theme.TabularNums
import to.eyed.thragg.ui.theme.ZedSurface
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.theme.touchTarget
import to.eyed.thragg.ui.workspace.AutosaveTracker
import to.eyed.thragg.ui.workspace.autosaveDelayMs
import to.eyed.thragg.ui.workspace.savesOnLeavingApp
import to.eyed.thragg.ui.workspace.savesOnLeavingFile
import to.eyed.thragg.ui.workspace.GoToLine
import to.eyed.thragg.ui.workspace.isUnderRemoved
import to.eyed.thragg.ui.workspace.movedTabPath
import to.eyed.thragg.ui.workspace.NotificationAction
import to.eyed.thragg.ui.workspace.Notifications
import to.eyed.thragg.ui.workspace.OpenFile
import to.eyed.thragg.ui.workspace.OpenFilesState
import to.eyed.thragg.ui.workspace.OutlinePicker

/**
 * Code — the editor, full-bleed, and the destination a developer lives in.
 *
 * The structure is the wireframe and nothing else (docs/UI.md, "Code — the
 * editor"): a 44dp header carrying identity and the rare exits, the buffer
 * taking every pixel that is left, and a 44dp file bar pinned directly above
 * the nav bar, in the thumb zone, carrying the frequent exit — which is
 * switching files. There is no tab strip, no breadcrumb toolbar, no status
 * bar, no minimap panel and no dock, and the reachability inversion those
 * produced (everything you press often at the top, everything you press rarely
 * at the bottom) is the defect this layout exists to fix.
 *
 * What this file *owns*, and what it must therefore have carried across from
 * WorkspaceScreen.kt by hand rather than re-derived (docs/UI.md, "What is
 * removed", the WorkspaceScreen entry): `openFileInto`, `saveNow` with
 * `format_on_save` and the whitespace rules in front of it, the status poll
 * that reloads a clean buffer whose file moved underneath it, `resyncBuffers`
 * after a workspace edit, and the autosave. Those are five bugs' worth of
 * behaviour that took a long time to get right, and the spec is explicit that
 * re-deriving them would be re-finding them.
 *
 * The one thing it does *not* own is the editor surface. EditorPane and its
 * dozen collaborators — the virtualized canvas, the IME path, selection
 * handles, tree-sitter spans, LSP diagnostics, hover, completions, folds — are
 * inherited whole and called, not reimplemented. Long-press in particular is
 * left exactly as it is: it selects the word and raises the hover card with
 * its "Go to definition / type definition / implementation / declaration"
 * rows (EditorPane.kt, Hover.kt), and that *is* the design's answer to LSP
 * navigation by touch. A long-press sheet would fight range selection for the
 * same gesture and lose.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodeScreen(
    state: ShellState,
    settings: AppSettings,
    settingsPath: String?,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val code = remember { CodeState.current }
    val files = code.files
    val project = state.project

    // A project switch takes every buffer with it: the paths are relative to a
    // root that no longer exists, and an editor over a released engine buffer
    // draws nothing. Unconditional rather than through `requestCloseAll`,
    // which asks — by the time a project is being closed the answer has
    // already been given somewhere else.
    LaunchedEffect(project?.rootPath) {
        val root = project?.rootPath
        if (code.projectRoot == root) return@LaunchedEffect
        for (index in files.tabs.indices.reversed()) files.close(index)
        files.clearClosedHistory()
        code.autosave.retain(emptyList())
        code.places.clear()
        code.projectRoot = root
    }

    val active = files.active
    val activeEditor = active?.editor

    // ---- Opening -----------------------------------------------------------

    /**
     * [openFile]'s body, awaitable — carried across from
     * WorkspaceScreen.kt:700 minus the pane tree it opened *into*. There is
     * one pane now, so "already open elsewhere" cannot happen and the clone
     * path goes with it.
     */
    suspend fun openFileInto(open: ProjectSession?, path: String): OpenFile? {
        val existing = files.indexOfPath(path)
        if (existing >= 0) {
            files.select(existing)
            return files.tabs[existing]
        }
        // An **absolute** path is a file that is not in the worktree at all —
        // settings.json is the one the app itself asks for (Settings →
        // "Edit settings.json"), and it lives in the app's files directory,
        // not in the project. `absolutePathOf` can only answer for paths
        // *inside* the root, so it answered null and the row looked dead
        // (QA 0.0.22, G-12). Such a tab is keyed by its absolute path, which
        // is also what keeps it out of the session document
        // (SessionRestore.toSessionItem refuses a leading `/`).
        val absolutePath = if (path.startsWith('/')) {
            path.takeIf { File(it).isFile } ?: return null
        } else {
            open?.absolutePathOf(path) ?: return null
        }
        // A picture never reaches the engine: opening one as text would put a
        // megabyte of mojibake in a CRDT and set tree-sitter on it. The
        // routing is OpenFiles.kt's `MediaKind`, kept; what draws it is
        // ui/common/BinaryPlaceholder.kt rather than the media player.
        val media = MediaKind.of(path.substringAfterLast('/'))
        if (media != null) {
            val opened = OpenFile(path, editor = null, media = media, absolutePath = absolutePath)
            files.open(opened)
            return opened
        }
        val session = withContext(Dispatchers.IO) { BufferSession.openFile(absolutePath) }
            ?: return null
        val opened = OpenFile(path, EditorState(session), absolutePath = absolutePath)
        files.open(opened)
        opened.refreshLanguageSettings()
        return opened
    }

    fun openFile(path: String, onOpened: (suspend (OpenFile) -> Unit)? = null) {
        val open = project
        // A relative path has no meaning without a project to resolve it
        // against; an absolute one (settings.json) opens either way.
        if (open == null && !path.startsWith('/')) return
        // The answer that needs no I/O is given in *this* frame, because
        // callers go on to act on the active file.
        val existing = files.indexOfPath(path)
        if (existing >= 0) {
            files.select(existing)
            val file = files.tabs[existing]
            if (onOpened != null) scope.launch { onOpened(file) }
            return
        }
        scope.launch {
            val opened = openFileInto(open, path)
            if (opened == null) {
                // Said rather than swallowed: a file that simply never appears
                // is the failure mode this reports.
                Notifications.error("$path could not be opened", key = "open:$path")
            } else {
                onOpened?.invoke(opened)
            }
        }
    }

    // ---- Saving ------------------------------------------------------------

    /**
     * The save that is waiting for an answer about a file that changed on
     * disk, or null. One at a time: the dialog names one file, and a queue of
     * them is not a decision anybody can make (the same rule as
     * [UnsavedChangesDialog]).
     */
    var diskConflict by remember { mutableStateOf<DiskConflict?>(null) }

    fun resyncBuffers(bufferIds: List<Long>) {
        for (file in files.tabs) {
            val id = file.session?.id
            if (id != null && id in bufferIds) file.editor?.noteExternalEdit()
            file.refreshStatus()
        }
    }

    /**
     * A workspace edit — a rename, a quick fix, a formatting — landed
     * engine-side, and the receipt names every file it touched. The engine
     * changed those buffers *underneath* their editors, so each open one is
     * resynced, and every dirty dot re-read: an applied edit makes clean
     * buffers dirty.
     */
    fun resyncAfterWorkspaceEdit(receipt: EditReceipt) {
        if (receipt.files.isEmpty()) return
        resyncBuffers(receipt.files.mapNotNull { it.bufferId })
    }

    /**
     * `format_on_save`, before the write — carried across from
     * WorkspaceScreen.kt:1704 whole, because every branch of it was paid for:
     * the `code_actions_on_format` first, then the formatter, each landing in
     * the buffer through the engine so the editor resyncs and the undo history
     * keeps them as steps of their own. A formatter that fails says so and the
     * save goes ahead regardless — a file that could not be formatted is still
     * a file worth keeping.
     */
    suspend fun formatBeforeSave(file: OpenFile) {
        val editor = file.editor ?: return
        val languageSettings = file.languageSettings
        if (!languageSettings.formatsOnSave) return
        val id = editor.session.id
        if (languageSettings.codeActionsOnFormat.isNotEmpty()) {
            val actions = pollLspRequest(
                withContext(Dispatchers.Default) { CoreBridge.lspRequestCodeActionsOnFormat(id) }
            )
            if (actions != null && actions.state == LspRequestState.Done) {
                val summary = EditSummary.parse(actions.payload)
                if (summary.error == null && !summary.isEmpty) {
                    resyncAfterWorkspaceEdit(applyPendingEdit(actions.id))
                }
            }
        }
        when (val formatter = languageSettings.saveFormatter) {
            is FormatterSpec.External -> {
                val outcome = withContext(Dispatchers.IO) {
                    JSONObject(CoreBridge.formatBufferExternally(id))
                }
                if (outcome.optBoolean("changed", false)) {
                    editor.noteExternalEdit()
                    file.refreshStatus()
                }
                val error = outcome.optString("error", "").takeIf { it.isNotEmpty() }
                // A warning, not an error: the save goes ahead regardless, so
                // the file *is* on disk — it just was not formatted.
                if (error != null) {
                    Notifications.warn(
                        "${formatter.command}: $error",
                        key = "format:${formatter.command}",
                    )
                }
            }
            is FormatterSpec.None -> Unit
            else -> {
                val answer = requestFormatting(id) ?: return
                if (answer.state != LspRequestState.Done) return
                val summary = EditSummary.parse(answer.payload)
                if (summary.error != null || summary.isEmpty) return
                resyncAfterWorkspaceEdit(applyPendingEdit(answer.id))
            }
        }
    }

    /**
     * `remove_trailing_whitespace_on_save` and `ensure_final_newline_on_save`,
     * applied after the formatter and before the write, so a formatter that
     * reintroduced a trailing space does not win.
     */
    suspend fun cleanBeforeSave(file: OpenFile) {
        val editor = file.editor ?: return
        if (!file.languageSettings.cleansOnSave) return
        val changed = withContext(Dispatchers.IO) { CoreBridge.cleanBufferOnSave(editor.session.id) }
        if (changed) {
            editor.noteExternalEdit()
            file.refreshStatus()
        }
    }

    /**
     * Write one buffer to disk and return once it is there.
     *
     * Awaitable on purpose, and this is the single most load-bearing property
     * in the file: ▶ Build saves *every* dirty buffer and waits for all of
     * them before it spawns anything, because a build of stale files is worse
     * than no build (docs/UI.md, "Why", defect 2).
     *
     * [format] is off for the leave-a-file autosave, as Zed turns it off for
     * its delayed autosave (pane.rs:2545-2548): a formatter running under the
     * user's fingers is not help.
     *
     * [conflicts] is what happens when the file changed **on disk** while the
     * buffer was dirty — the agent rewrote it, a build regenerated it, a
     * `git checkout` moved it. Until 0.0.22 nothing asked: `hasDiskChange` was
     * written by [OpenFile.refreshStatus] and read by exactly one place, the
     * poll's clean-reload branch, so every save silently overwrote the newer
     * file (QA 0.0.22, G-04). A save the user asked for [DiskChange.Ask]s; an
     * autosave nobody is watching [DiskChange.Hold]s, because the answer to
     * "which of these two versions do you want" cannot be guessed on the
     * user's behalf while they are looking at another screen.
     */
    suspend fun saveNow(
        file: OpenFile,
        format: Boolean = true,
        conflicts: DiskChange = DiskChange.Ask,
    ) {
        val open = file.session ?: return
        if (file.isReadOnly) return
        // The poll is up to a quarter of a second behind and this is the
        // question the whole branch turns on, so ask the engine now.
        file.refreshStatus()
        if (file.hasDiskChange) {
            when (conflicts) {
                DiskChange.Hold -> {
                    Notifications.warn(
                        "${file.name} changed on disk, so it was not autosaved. " +
                            "Open it and choose which version to keep.",
                        key = diskChangeKey(file.path),
                    )
                    return
                }
                DiskChange.Ask -> {
                    val asked = CompletableDeferred<DiskChangeAnswer>()
                    diskConflict = DiskConflict(file, asked)
                    val answer = try {
                        asked.await()
                    } finally {
                        diskConflict = null
                    }
                    when (answer) {
                        DiskChangeAnswer.Cancel -> return
                        DiskChangeAnswer.Reload -> {
                            val reloaded = withContext(Dispatchers.IO) { open.reload() }
                            // In the same turn as the reload, exactly as the
                            // poll does: between the two the editor's carets
                            // name a file that is gone.
                            if (reloaded) file.editor?.noteExternalEdit()
                            file.refreshStatus()
                            Notifications.dismissKey(diskChangeKey(file.path))
                            return
                        }
                        DiskChangeAnswer.Overwrite -> Unit
                    }
                }
            }
        }
        Notifications.dismissKey(diskChangeKey(file.path))
        if (format) {
            formatBeforeSave(file)
            cleanBeforeSave(file)
        }
        // The engine's answer was thrown away for a long time, and a write
        // that failed — a read-only mount, a full disk, a file deleted under
        // the tab — looked exactly like one that worked, right down to the
        // dirty dot clearing.
        val written = withContext(Dispatchers.IO) { open.save() }
        if (!written && file.absolutePath != null) {
            Notifications.error(
                "${file.name} could not be saved — the file may be read-only or gone.",
                key = "save:${file.path}",
            )
        }
        code.autosave.saved(file.path)
        file.refreshStatus()
        // Saving settings.json *is* the reload: the engine reads the file
        // fresh on every settings() call, so re-parsing here applies the edit
        // everywhere at the only moment the file can change from inside the
        // app — Zed's file watcher, without the watcher.
        if (file.absolutePath != null && file.absolutePath == settingsPath) {
            onSettingsChanged(withContext(Dispatchers.IO) { AppSettings.load() })
        }
        // The project's own file, likewise: the watcher would get there a tick
        // later, and the parse error — if there is one — belongs on screen
        // now, next to the text that caused it.
        if (file.path == LOCAL_SETTINGS_PATH) {
            val open2 = project ?: return
            val error = withContext(Dispatchers.IO) {
                CoreBridge.reloadProjectSettings(open2.id)
                CoreBridge.projectSettingsError(open2.id)
            }
            reportLocalSettings(error)
            for (tab in files.tabs) tab.refreshLanguageSettings()
        }
    }

    /** [saveNow], fire and forget — what a button wants. */
    fun save(file: OpenFile, format: Boolean = true, conflicts: DiskChange = DiskChange.Ask) {
        scope.launch { saveNow(file, format, conflicts) }
    }

    /**
     * Save every dirty buffer and *wait*, then run [after].
     *
     * This is the atom ▶ Build is. Sequential rather than parallel because
     * `format_on_save` funnels through the language server either way and two
     * concurrent workspace edits over the same server is how a formatting
     * lands in the wrong file.
     */
    suspend fun saveAllDirty(format: Boolean = true, conflicts: DiskChange = DiskChange.Ask) {
        files.refreshStatuses()
        for (file in files.tabs) {
            if (file.isDirty && !file.isReadOnly) saveNow(file, format, conflicts)
        }
    }

    // ---- The tree moving files under the tabs -------------------------------

    /**
     * The project panel deleted or trashed [removed] (a file, or a folder with
     * files under it): every tab it took with it is closed.
     *
     * Unconditionally, and this is the one place in the app where a dirty tab
     * closes without the [UnsavedChangesDialog]. That dialog's "Save" writes
     * the buffer back to its path — which here would *resurrect the file the
     * user just deleted* — and its "Cancel" would leave the tab open over a
     * buffer bound to a path that no longer exists, which is the bug being
     * fixed: the next autosave recreated the deleted file (QA 0.0.22, G-07).
     * So the tab goes, and a buffer with unsaved edits says so out loud rather
     * than going quietly.
     */
    fun closeTabsUnder(removed: String) {
        for (file in files.tabs) {
            if (!isUnderRemoved(file.path, removed)) continue
            val index = files.indexOfPath(file.path)
            if (index < 0) continue
            val hadEdits = file.isDirty
            files.close(index)
            code.places.remove(file.path)
            Notifications.dismissKey(diskChangeKey(file.path))
            if (hadEdits) {
                Notifications.warn(
                    "${file.name} was deleted with unsaved edits in it, and its tab is closed.",
                    key = "removed:${file.path}",
                )
            }
        }
        code.autosave.retain(files.tabs.map { it.path })
    }

    /**
     * The project panel renamed or moved [from] to [to]: every tab under it
     * follows.
     *
     * A tab cannot simply be re-keyed — [OpenFile.path] is a `val` and, more
     * to the point, the engine buffer behind it is bound to the *old*
     * absolute path, so a save would write the old file back. It is closed and
     * reopened at the new name instead, with its caret and scroll carried
     * across ([placeOf]) and the tab that was active still active afterwards.
     * A buffer with unsaved edits loses them to the move and is told so: the
     * text on disk is what moved, and there is no engine call that would carry
     * a dirty buffer to a new path.
     */
    fun rekeyTabsAfterMove(from: String, to: String) {
        val moving = files.tabs.mapNotNull { file ->
            movedTabPath(file.path, from, to)?.let { file to it }
        }
        if (moving.isEmpty()) return
        val activeBefore = files.active?.path
        scope.launch {
            for ((file, destination) in moving) {
                val index = files.indexOfPath(file.path)
                if (index < 0) continue
                val place = placeOf(file, destination)
                val hadEdits = file.isDirty
                val name = file.name
                files.close(index)
                code.places.remove(file.path)
                Notifications.dismissKey(diskChangeKey(file.path))
                // Before the open, for the reason the restore drain does the
                // same: the open suspends and the tab it adds recomposes.
                if (place != null) code.places[destination] = place
                val reopened = openFileInto(project, destination)
                if (reopened == null) {
                    code.places.remove(destination)
                    Notifications.error(
                        "$destination could not be opened after the move.",
                        key = "open:$destination",
                    )
                    continue
                }
                if (hadEdits) {
                    Notifications.warn(
                        "$name moved while it had unsaved edits; the tab now shows the " +
                            "file on disk at $destination.",
                        key = "moved:$destination",
                    )
                }
            }
            code.autosave.retain(files.tabs.map { it.path })
            // Reopening activates each tab in turn, so the file the user was
            // in has to be put back — under its new name if it was the one
            // that moved.
            val activeAfter = activeBefore?.let { movedTabPath(it, from, to) ?: it }
            val index = activeAfter?.let { files.indexOfPath(it) } ?: -1
            if (index >= 0) files.select(index)
        }
    }

    // ---- The seams the shell reads -----------------------------------------

    // Everything that navigates to a line of source goes through here: a build
    // error row, a diagnostic in Problems, a `path:line:col` in the terminal,
    // a file another app shared in.
    //
    // It *queues* rather than opening, and it is registered once for the life
    // of the process rather than for the life of the composition. Both matter
    // and both were got wrong first: a build error row is tapped while Build
    // is on screen, which is exactly when Code is not composed — a lambda
    // cleared on the way out would be null at the only moment it is used, and
    // one that was not cleared would launch its coroutine in a scope that had
    // already been cancelled. The queue lives on [CodeState], which outlives
    // both, and Code drains it the moment it is shown.
    LaunchedEffect(state) {
        state.openPath = { incoming -> code.pendingOpens.add(PendingOpen(incoming)) }
        // The same seam with a position on it (ui/shell/build/ShellMode.kt).
        // A build error row and a `path:line:col` the terminal printed both
        // know the line; without this they land at the top of a 400-line file,
        // which is a jump that has not done what it promised.
        CodeJump.openAt = { path, row, column ->
            code.pendingOpens.add(PendingOpen(path, row, column))
        }
        // Pressing ▶ on the *Build* screen has to save the buffers too, and
        // that press happens while Code is not composed — so this closes over
        // nothing but [CodeState], which is process-wide, and reads no
        // composition value at all (P4's handoff; BuildRunner.kt:118).
        //
        // Unformatted on purpose: Code's own runBuild() already did the
        // formatted save before it reached the runner, and a formatter run
        // over every dirty buffer from the Build screen is a workspace edit
        // the user cannot see happening.
        BuildRunner.saveAll = {
            var written = 0
            files.refreshStatuses()
            for (file in files.tabs) {
                if (!file.isDirty || file.isReadOnly) continue
                val session = file.session ?: continue
                // Code is not composed when ▶ is pressed on Build, so there is
                // nobody to ask: a buffer whose file moved on disk is left
                // alone and said out loud, rather than overwritten by a save
                // the user did not know they were asking for (G-04).
                if (file.hasDiskChange) {
                    Notifications.warn(
                        "${file.name} changed on disk and was not saved before the build. " +
                            "Open it and choose which version to keep.",
                        key = diskChangeKey(file.path),
                    )
                    continue
                }
                if (withContext(Dispatchers.IO) { session.save() }) written++
                code.autosave.saved(file.path)
                file.refreshStatus()
            }
            written
        }
    }
    // THE GLOBAL SETTINGS FILE'S PARSE, SAID OUT LOUD.
    //
    // The project's own `.zed/settings.json` has had `reportLocalSettings`
    // since it was written; the user's file had nothing, and a single
    // duplicated key in it reverted every setting and disconnected the agent
    // in complete silence (QA 0.0.22, G-11). Same shape as the local one: one
    // keyed notice, taken back the moment the file parses again.
    LaunchedEffect(settings, settingsPath) {
        val loaded = withContext(Dispatchers.IO) { AppSettings.loadChecked() }
        val problem = loaded.problem
        val path = settingsPath
        if (problem == null) {
            Notifications.dismissKey(GLOBAL_SETTINGS_NOTIFICATION)
        } else {
            // Two sentences, and which one is true depends on how far the
            // file got. `recovered` means this side read it even though the
            // engine would not — the theme, the agent and the terminal are
            // the user's, the editor's own settings are not — and saying
            // "every setting is the built-in default" there would be the
            // notice contradicting the app in front of it (QA G-11).
            val consequence = if (loaded.recovered) {
                // `recovered` means this side parsed the whole file, so the
                // settings the user can see — theme, font size, tab width,
                // the agent, the terminal — really are theirs. Saying they
                // are defaults was the notice contradicting the screen
                // behind it, which is what QA found on the device.
                "Thragg read it anyway, so your settings are in effect; the editor engine " +
                    "refused it, so fix the file to be sure they all apply."
            } else {
                "Every setting is the built-in default until it is fixed."
            }
            Notifications.error(
                "settings.json is not in effect: $problem. $consequence",
                // Open, and *arrive*: this toast is raised at launch and is on
                // screen whatever tab the user is on, so opening the file
                // without switching to Code is a button that looks dead from
                // the Agent tab (QA r2 §6c). Same rule as CodeJump.to's.
                action = path?.let {
                    NotificationAction("Open") {
                        state.openPath?.invoke(it)
                        state.show(Destination.Code)
                    }
                },
                key = GLOBAL_SETTINGS_NOTIFICATION,
            )
        }
    }

    // The view, for one imperative read of the IME's visibility below —
    // `WindowInsets.isImeVisible` is a composition read, and the restore is
    // a coroutine.
    val hostView = LocalView.current
    // ONE long-lived drainer, keyed on the project alone.
    //
    // It used to be keyed on `pendingOpens.size` as well, and each open was
    // *launched* rather than awaited. Both halves were wrong and the session
    // restore paid for it (QA 0.0.22, G-16): the launched opens finished in
    // whatever order the engine answered in, so the file that ended up active
    // was a race — a two-file restore came back with `Cargo.toml` showing
    // instead of the file the user was in — and a key that the body's own
    // `removeAt(0)` changes cancels the body mid-open the moment an open
    // takes longer than a frame. Awaiting each open in turn is what makes the
    // document's order (oldest first, the file that was showing last) the
    // order the tabs are opened in, and therefore honoured.
    LaunchedEffect(project) {
        val open = project
        var restored = false
        while (true) {
            val pending = code.pendingOpens.firstOrNull()
            if (pending == null) {
                // A session restored under a keyboard that is already up —
                // the process killed and brought back while the user was
                // typing — has a keyboard with no owner: Compose restores no
                // focus, so the IME is connected to nothing and the editor's
                // action row docks on it with nobody to act for. Hand the
                // keyboard to the file the user was in. Only when it *is* up:
                // a restore must never raise the keyboard.
                if (restored) {
                    if (imeIsShowing(hostView)) files.active?.editor?.requestFocus()
                    restored = false
                }
                // Park until something is queued, rather than spinning or
                // being torn down and rebuilt on every arrival.
                snapshotFlow { code.pendingOpens.size }.first { it > 0 }
                continue
            }
            // A relative path needs a project to resolve it; with none open
            // the queue waits, and this effect is restarted by the key when
            // one arrives. An absolute path (settings.json) needs nothing.
            if (open == null && !pending.path.startsWith('/')) return@LaunchedEffect
            code.pendingOpens.removeAt(0)
            val path = if (open != null) relativeTo(open, pending.path) else pending.path
            // A restored place carries its own caret and scroll
            // (ui/shell/SessionRestore.kt) and is put back whole — but only
            // once the tab is the one on screen, because a scroll is clamped
            // against a viewport and a tab nobody is looking at has not been
            // laid out. See [CodeState.places].
            //
            // Written *before* the open, not after: opening suspends, and the
            // recomposition that the new tab causes can run the effect that
            // reads this map before the open has returned.
            val saved = pending.restore
            if (saved != null) {
                code.places[path] = saved
                restored = true
            }
            val file = openFileInto(open, path)
            if (file == null) {
                code.places.remove(path)
                // Said rather than swallowed: a file that simply never appears
                // is the failure mode this reports.
                Notifications.error("$path could not be opened", key = "open:$path")
                continue
            }
            if (saved == null && pending.row > 0) {
                // 1-based from the compiler and from the terminal, 0-based in
                // the buffer; 0 means "no position was known", and then the
                // caret is left exactly where this file was last read.
                file.editor?.revealDefinition(
                    pending.row - 1,
                    (pending.column - 1).coerceAtLeast(0),
                )
            }
        }
    }

    // A saved place waiting for its tab to be the one on screen.
    //
    // `EditorState.scrollToY` clamps against `maxScrollY`, which is the
    // content height minus the *viewport* height — and a tab that has never
    // been composed has no viewport, so the clamp is to zero and the restored
    // scroll is thrown away. That is why a relaunch used to open every file at
    // the top however carefully the position had been written down
    // (QA 0.0.22, G-16). Applied here, when the tab becomes active and has a
    // layout, and removed only once it has landed so a cancellation between
    // the frames does not lose it.
    LaunchedEffect(active) {
        val file = active ?: return@LaunchedEffect
        val place = code.places[file.path] ?: return@LaunchedEffect
        place.restoreIn(file)
        code.places.remove(file.path)
    }

    var searchDeploy by remember { mutableStateOf<SearchDeploy?>(null) }
    var searchFocused by remember { mutableStateOf(false) }

    /**
     * Step 4 of the ordered back handler: close the find bar *and clear the
     * match highlights*. The second half is not optional — leaving it out is
     * how a closed find bar leaves a buffer painted yellow
     * (WorkspaceScreen.kt:3634).
     */
    DisposableEffect(state, activeEditor) {
        state.findBarSeam = BackSeam(
            isActive = { searchDeploy != null },
            consume = {
                activeEditor?.clearSearchMatches()
                searchDeploy = null
            },
        )
        // Step 1: whatever the pane has raised over itself. The handle is a
        // holder the pane fills while it is composed, so this seam survives
        // the buffer being swapped underneath it.
        state.overlaySeam = BackSeam(
            isActive = { code.overlays.isShowing },
            consume = { code.overlays.dismissTopmost() },
        )
        // Step 6: one entry off the jump stack — OpenFiles.kt's NavHistory,
        // kept. Following a go-to-definition and pressing back is the gesture
        // the whole step exists for.
        state.jumpSeam = BackSeam(
            isActive = { files.canGoBack },
            consume = {
                val entry = files.goBack() ?: return@BackSeam
                val index = files.indexOfPath(entry.path)
                if (index >= 0) {
                    // goBack already made the file active, outside the
                    // history's ears.
                    scope.launch { entry.restoreIn(files.tabs[index]) }
                } else if (project == null) {
                    files.navigationFailed(entry, wasBack = true)
                } else {
                    openFile(entry.path) { file -> entry.restoreIn(file) }
                }
            },
        )
        onDispose {
            state.findBarSeam = null
            state.overlaySeam = null
            state.jumpSeam = null
        }
    }

    // ---- The status poll ---------------------------------------------------

    // One loop for every buffer's status. A buffer whose file changed
    // underneath it while *clean* is reloaded without asking: there are no
    // local edits to lose, and silently showing stale text would be the worse
    // behaviour. Restarting on every return to the foreground is exactly right
    // — the background is where files change underneath buffers, and on this
    // device the background is also where a 71-second build rewrites them.
    //
    // Every loop in here iterates `files.tabs`, which is a **snapshot** and
    // not the live list — see [OpenFilesState.tabs]. Both loops suspend
    // inside their body, and a tab opened or closed in that window used to
    // kill the process on a cold start (QA 0.0.22, B-02).
    ResumedEffect(project, settings.autosave) {
        var settingsVersionSeen = -1L
        // Paths already complained about, so a warning that is true 4 times a
        // second is raised once. Cleared when the file agrees with the disk
        // again.
        val diskChangeTold = HashSet<String>()
        val autosave = settings.autosave
        val autosaveDelay = autosave.autosaveDelayMs()
        while (true) {
            files.refreshStatuses()
            for (file in files.tabs) {
                // A file the engine moved under us (a workspace edit, a git
                // command, an agent writing through the open buffer): the
                // handle's version is re-read first, because an engine-side
                // write bumps the engine's version and not the handle's, and
                // an editor comparing the handle against itself would never
                // notice. One bridge call, next to the five refreshStatus
                // already makes.
                file.session?.refreshVersion()
                file.editor?.resyncIfBufferMoved()
                if (file.hasDiskChange && !file.isDirty) {
                    val reloaded = withContext(Dispatchers.IO) { file.session?.reload() ?: false }
                    // Resync *now*, in the same turn as the reload — not on
                    // the next tick. Between the two, the editor's carets,
                    // anchor and line count named a file that was gone, and a
                    // keystroke or an IME commit in that quarter second was
                    // an edit against text the buffer no longer had.
                    //
                    // `noteExternalReload`, not `noteExternalEdit`: this
                    // reload is the one NOBODY ASKED FOR, and the engine's
                    // history keeps it as an undoable transaction. Undoing it
                    // puts the file as it was before the other writer back in
                    // a buffer that then autosaves over the newer file — the
                    // agent's rewrite, `seahorse build`'s regeneration or a
                    // `git checkout` silently lost. The floor stops undo
                    // there; the user-chosen Reload in [saveNow] deliberately
                    // does not set one, because there the text below it is
                    // the user's own unsaved work.
                    if (reloaded) file.editor?.let { editor ->
                        // Said where the tap was, because an undo that does
                        // nothing and says nothing reads as a broken button.
                        editor.onUndoStoppedAtReload = {
                            Notifications.warn(
                                "${file.name} was reloaded from disk, so there is " +
                                    "nothing before that left to undo.",
                                key = "undofloor:${file.path}",
                            )
                        }
                        editor.noteExternalReload()
                    }
                    file.refreshStatus()
                }
                // A *dirty* buffer whose file moved is the one case the app
                // cannot decide for itself, and until 0.0.22 it was the case
                // nobody was told about: `hasDiskChange` was written here and
                // read nowhere, so the next save — a tab switch, ▶, leaving
                // the app — quietly overwrote the newer file (QA 0.0.22,
                // G-04). Said once, and taken back when it stops being true.
                if (file.hasDiskChange && file.isDirty) {
                    if (diskChangeTold.add(file.path)) {
                        Notifications.warn(
                            "${file.name} changed on disk while you have unsaved edits. " +
                                "Saving will ask which version to keep.",
                            key = diskChangeKey(file.path),
                        )
                    }
                } else if (diskChangeTold.remove(file.path)) {
                    Notifications.dismissKey(diskChangeKey(file.path))
                }
            }
            // A tab that was complained about and then closed takes its
            // complaint with it: the file may be perfectly fine now, and a
            // toast about a buffer that no longer exists cannot be acted on.
            if (diskChangeTold.isNotEmpty()) {
                val stillOpen = files.tabs.mapTo(HashSet()) { it.path }
                val gone = diskChangeTold.filterNot { it in stillOpen }
                for (path in gone) {
                    diskChangeTold.remove(path)
                    Notifications.dismissKey(diskChangeKey(path))
                }
            }
            // `"autosave": {"after_delay": …}` — Zed's debounce, driven from
            // this poll because this editor has no edit event to hang it on:
            // the buffer's version counter is what moves, and the tick that
            // re-read it is already here. [AutosaveTracker] was written for
            // exactly this and had no caller at all until 0.0.22 (G-05).
            if (autosaveDelay != null) {
                val now = SystemClock.uptimeMillis()
                val paths = ArrayList<String>()
                val dirty = ArrayList<String>()
                for (file in files.tabs) {
                    paths.add(file.path)
                    val version = file.session?.version ?: continue
                    code.autosave.observe(file.path, version, now)
                    if (file.isDirty && !file.isReadOnly) dirty.add(file.path)
                }
                code.autosave.retain(paths)
                for (path in code.autosave.due(dirty, autosaveDelay, now)) {
                    val file = files.tabs.firstOrNull { it.path == path } ?: continue
                    // Unformatted and unattended: a formatter running under
                    // the user's fingers is not help, and a file that changed
                    // on disk is held rather than overwritten behind them.
                    saveNow(file, format = false, conflicts = DiskChange.Hold)
                }
            }
            val open = project
            if (open != null) {
                val version = withContext(Dispatchers.Default) {
                    CoreBridge.projectSettingsVersion(open.id)
                }
                if (version != settingsVersionSeen) {
                    settingsVersionSeen = version
                    val error = withContext(Dispatchers.IO) {
                        CoreBridge.projectSettingsError(open.id)
                    }
                    reportLocalSettings(error)
                    for (file in files.tabs) file.refreshLanguageSettings()
                }
            }
            delay(STATUS_POLL_MS)
        }
    }

    // ---- Autosave, on leaving a file ---------------------------------------

    // "Autosave on leaving a file" — Settings' own switch, which until 0.0.22
    // wrote `"autosave": "off"` into a file nothing read: the only reference
    // to `settings.autosave` in the whole app was the row that wrote it, so
    // turning it off changed nothing and turning it on changed nothing
    // (QA 0.0.22, G-05). The three doors out of a buffer are gated on it here,
    // by the rules in ui/workspace/AutosaveTracker.kt, and every one of them
    // is *unattended* — a write nobody is watching must never overwrite a file
    // that moved on disk (G-04).
    //
    // Leaving a file is the active buffer changing; the one that was active is
    // written.
    val autosave = settings.autosave
    var previouslyActive by remember { mutableStateOf<OpenFile?>(null) }
    LaunchedEffect(active, autosave) {
        val departed = previouslyActive
        previouslyActive = active
        if (!autosave.savesOnLeavingFile()) return@LaunchedEffect
        if (departed != null && departed !== active && departed in files.tabs) {
            departed.refreshStatus()
            if (departed.isDirty && !departed.isReadOnly) {
                save(departed, format = false, conflicts = DiskChange.Hold)
            }
        }
    }

    // …and leaving the *app*, which on Android is the common case: the
    // process holding a 1.4 GB toolchain is killed aggressively, and back at
    // Code's root is one gesture (step 8). Every dirty buffer goes out here.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (autosave.savesOnLeavingApp()) {
            for (file in files.tabs) {
                if (file.isDirty && !file.isReadOnly) {
                    save(file, format = false, conflicts = DiskChange.Hold)
                }
            }
        }
    }

    // …and leaving Code for Agent or Build, which is the case that is easy to
    // miss and the one that matters most on this device: the agent reads the
    // files from *disk* through ACP, and the build compiles what is on disk.
    // A conversation about a buffer that was never written is a conversation
    // about the wrong file. Launched in [CodeState]'s own scope, not the
    // composition's — the composition is the thing that is going away.
    DisposableEffect(autosave) {
        onDispose {
            if (autosave.savesOnLeavingApp()) {
                code.scope.launch { saveAllDirty(format = false, conflicts = DiskChange.Hold) }
            }
        }
    }

    // "Re-tapping the current destination scrolls it to top" (docs/UI.md,
    // "Navigation"). The baseline is captured on the first composition so that
    // arriving at Code never scrolls the buffer you were reading.
    val retapSeen = remember { intArrayOf(state.retapCount) }
    LaunchedEffect(state.retapCount) {
        if (state.retapCount != retapSeen[0]) {
            retapSeen[0] = state.retapCount
            activeEditor?.scrollToY(0f)
        }
    }

    // ---- Build -------------------------------------------------------------

    val buildRunning = state.build is BuildState.Running

    /**
     * ▶, from the action row's fixed head or from anywhere else in Code.
     *
     * Save-all-then-build is *one* action and the order inside it is not
     * negotiable (docs/UI.md, P4). With no toolchain the press pushes Setup
     * rather than failing, which is what the spec asks for and is also the
     * only honest thing to do in a build that has none.
     */
    fun runBuild() {
        if (project == null) return
        if (!state.toolchainReady) {
            state.push(Route.Setup)
            return
        }
        // `CodeBuildSeam.run` is installed by `ShellBootstrap` before anything
        // is drawn, so there is no frame on which it is null and no branch here
        // that says so; what made this button dead was the runner having no
        // layout until Build had been composed once, and that is fixed where a
        // project is opened (QA G-01, B-10).
        val runner = CodeBuildSeam.run ?: return
        scope.launch {
            saveAllDirty()
            runner(project)
        }
    }

    // ---- Sheets, dialogs and the routes they push ---------------------------

    var sheet by remember { mutableStateOf<CodeSheet?>(null) }
    /** Zed's `outline` and `go_to_line` pickers, raised from the ⋮ sheet. */
    var picker by remember { mutableStateOf<CodePicker?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Bare: `background` *is* `editor.background` through the bridge
            // (MaterialBridge.kt, BAND A), so the Zed read with an M3 fallback
            // that used to be here was one that could never fire.
            .background(MaterialTheme.colorScheme.background)
            // THE KEYBOARD'S SPACE, GIVEN BACK ONCE, HERE.
            //
            // This column used to fill the window and nothing inside it moved
            // for the IME, so with the keyboard up the pane was laid out over
            // the full height and the keyboard was simply *drawn on top of*
            // its bottom third. Two device findings came out of that one
            // omission (QA 0.0.22, G-08): you typed into a line you could not
            // see, because tapping a line in the lower half put the caret
            // under the keys; and the end of a file became unreachable,
            // because `maxScrollY` is content minus *viewport* and the
            // viewport it measured was the one the keyboard was covering — the
            // reachable range got smaller as the keyboard came up.
            //
            // One inset for the whole destination rather than one per band:
            // the find bar carried its own `imePadding` and was the only thing
            // in Code that stood above the keys.
            .imePadding(),
    ) {
        CodeTopBar(
            projectName = project?.let { File(it.rootPath).name },
            file = active,
            // ONE COUNT, from [countFileProblems] — the same merge and the
            // same dedupe the Problems route lists, scoped to this file. The
            // badge used to count rust-analyzer's rows alone, so a build's
            // errors in the open file were not in it and the number it showed
            // could not agree with the screen it opens (QA G-19).
            errorCount = activeEditor?.let {
                countFileProblems(active?.absolutePath, it.diagnostics.rows).errors
            } ?: 0,
            onFind = {
                val editor = activeEditor ?: return@CodeTopBar
                val open = searchDeploy
                val seed = if (open != null && searchFocused) null else editor.searchSeed()
                searchDeploy = SearchDeploy(token = (open?.token ?: 0) + 1, seed = seed)
            },
            onProblems = { state.push(Route.Problems) },
            onOverflow = { sheet = CodeSheet.Overflow },
        )
        HairlineDivider()

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // The open buffer is asked about *first*, before "no project":
            // settings.json is opened by its absolute path from Settings, it
            // belongs to no worktree, and a shell with no project open still
            // has to be able to draw it (G-12).
            when {
                // THE SEAM. Everything inside this wrapper is the Zed half:
                // the gutter, the indent guides, the completions popup, the
                // LSP action list and the selection handles keep Zed's
                // colours, Zed's rem metrics, Zed's no-ripple rule and Zed's
                // LTR pin, because they have to agree with tree-sitter's
                // output in the same buffer (docs/VISUAL.md, "THE BOUNDARY,
                // EXACTLY"). Nothing inside it was touched by this pass.
                active != null && activeEditor != null -> ZedSurface {
                    EditorPane(
                        state = activeEditor,
                        modifier = Modifier.fillMaxSize(),
                        fileName = active.name,
                        languageSettings = active.languageSettings.wrappedForAPhone(),
                        onOpenDefinition = { target ->
                            // The server answers in absolute paths and the project
                            // opens by its own relative spelling; a target outside
                            // the root — the standard library, a registry crate —
                            // is dropped rather than opened as a path that does
                            // not resolve (WorkspaceScreen.kt:2104).
                            val open = project ?: return@EditorPane
                            val relative = relativeTo(open, target.path)
                            if (relative != target.path) {
                                openFile(relative) { opened ->
                                    opened.editor?.revealDefinitionTarget(target)
                                }
                            }
                        },
                        onWorkspaceEditApplied = { receipt -> resyncAfterWorkspaceEdit(receipt) },
                        onSaveBuffer = { save(active) },
                        onBuild = { runBuild() },
                        buildRunning = buildRunning,
                        onFixWithAgent = { diagnostic ->
                            fixWithAgent(state, active.path, diagnostic)
                        },
                        // Two strips docked on one keyboard would be 88dp of the
                        // 454 the typing posture has; the find bar wins while it
                        // is deployed, because it is the thing being typed into.
                        showActionRow = searchDeploy == null,
                        overlays = code.overlays,
                    )
                }
                // A 1.4 MB `.so` never reaches the text rope: MediaKind routed
                // it away in `openFileInto`, and this is what it routed it to.
                active != null -> BinaryPlaceholder(
                    absolutePath = active.absolutePath.orEmpty(),
                    kind = active.media,
                    modifier = Modifier.fillMaxSize(),
                )
                project == null -> CodeEmpty(
                    headline = "No project is open",
                    body = "Open one, clone a repository or start a new program.",
                    action = "Projects & tools",
                    onAction = { sheet = CodeSheet.Projects },
                )
                else -> CodeEmpty(
                    headline = "Nothing open yet",
                    body = "Pick a file from the tree, or search the project by name.",
                    action = "Browse files",
                    onAction = { sheet = CodeSheet.Files(FilesMode.Names) },
                )
            }
        }

        // Find and replace, re-hosted at the *bottom* of the screen rather
        // than at the top of the editor, and lifted onto the keyboard by
        // `imePadding` — the desktop habit of a search field at the top with
        // the keyboard covering its own results is the thing being fixed.
        val deploy = searchDeploy
        if (deploy != null && activeEditor != null) {
            // Inside the wrapper with the buffer it searches: the find bar
            // draws its match count against the editor's own ground and its
            // hits in `search.match_background`, which is the same ink the
            // spans behind it are painted in (ui/search/ is the Zed half).
            ZedSurface {
                // No `imePadding` here any more: the column above has it, and
                // two of them would lift the bar by two keyboards.
                Box(modifier = Modifier.fillMaxWidth()) {
                    BufferSearchBar(
                        editor = activeEditor,
                        deploy = deploy,
                        onDismiss = {
                            activeEditor.clearSearchMatches()
                            searchDeploy = null
                        },
                        onFocusChanged = { focused -> searchFocused = focused },
                    )
                }
            }
        }

        // The status line, between the buffer and the file bar: where the
        // caret is, what the language is, and how many problems the server has
        // published about this file. It reads `cursorRow`/`cursorCol` inside
        // its own composable rather than here, so a caret moving recomposes
        // 28dp of text instead of the destination.
        if (activeEditor != null) {
            EditorStatusLine(editor = activeEditor, file = active)
        }

        FileBar(
            files = files,
            onSelect = { index -> files.select(index) },
            onRequestClose = { index -> files.requestClose(index) },
            onFiles = { sheet = CodeSheet.Files(FilesMode.Names) },
        )
    }

    // The other half of G-08: the pane just got shorter by a keyboard, so the
    // caret may be below the fold. In its own composable because reading the
    // IME inset is a composition read and this destination must not recompose
    // on every frame of the keyboard's animation — the same reason
    // [EditorStatusLine] reads the caret inside itself.
    if (activeEditor != null) ImeCaretReveal(activeEditor)

    // Every route into closing a file goes through this dialog, and a host
    // that forgets to compose it fails *silently* — the request parks in
    // `OpenFilesState.closeConfirmation` and the file simply never closes.
    // Composed here, once, above every route that can ask: the file bar's
    // long-press, the Files sheet's ✕, and a project switch.
    UnsavedChangesDialog(files)

    // …and the same rule for the save that found a newer file on disk: one
    // host, next to the composable that owns the screen, so no route can
    // forget it (G-04).
    diskConflict?.let { pending ->
        DiskChangeDialog(
            file = pending.file,
            onAnswer = { answer -> pending.answer.complete(answer) },
        )
    }

    when (val open = sheet) {
        CodeSheet.Projects -> ProjectsSheet(state = state, onDismiss = { sheet = null })
        is CodeSheet.Files -> FilesSheet(
            shell = state,
            project = project,
            files = files,
            initialMode = open.mode,
            onOpenFile = { path ->
                sheet = null
                openFile(path)
            },
            onOpenMatch = { path, line ->
                sheet = null
                openFile(path) { file ->
                    file.editor?.revealDefinition((line - 1).coerceAtLeast(0), 0)
                }
            },
            onOpenChanges = {
                sheet = null
                state.push(Route.Changes)
            },
            // Straight into the other sheet rather than through `null`: the
            // slot holds one at a time, so assigning it *is* the swap, and
            // dismissing first would drop a frame of bare editor between them.
            onOpenProjects = { sheet = CodeSheet.Projects },
            // The tree's long-press menu reaching the open tabs (G-07).
            onEntryRemoved = { path -> closeTabsUnder(path) },
            onEntryMoved = { from, to -> rekeyTabsAfterMove(from, to) },
            onDismiss = { sheet = null },
        )
        CodeSheet.Overflow -> CodeOverflowSheet(
            shell = state,
            file = active,
            onSave = { active?.let { save(it) } },
            onGoToSymbol = { picker = CodePicker.Symbol },
            onGoToLine = { picker = CodePicker.Line },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    // The two pickers the ⋮ sheet raises. Dialogs rather than sheets because
    // both preview into the buffer behind them as you browse and hand it back
    // untouched on Escape — a sheet at 65% would cover the very lines they are
    // previewing.
    when (picker) {
        CodePicker.Symbol -> activeEditor?.let { editor ->
            OutlinePicker(editor = editor, onDismiss = { picker = null })
        }
        CodePicker.Line -> activeEditor?.let { editor ->
            GoToLine(editor = editor, onDismiss = { picker = null })
        }
        null -> Unit
    }
    // A picker over a buffer that has gone — the file was closed from the
    // Files sheet while it was up — has nothing to browse and no way to be
    // dismissed, so it is dropped. In an effect rather than inline: this is a
    // write to state the composition above has already read.
    LaunchedEffect(activeEditor) {
        if (activeEditor == null) picker = null
    }
}

/**
 * The 56dp top bar: identity on the left, the rare exits on the right.
 *
 * Everything here is either something you read (which file, where it lives,
 * whether it is dirty, how many errors) or something you press once an hour.
 * Everything you press once a minute is in the file bar at the bottom, which
 * is the whole reachability argument (docs/UI.md, "Why", defect 3).
 *
 * It is the shared [ThraggTopBar] now, which is what makes it 56dp with the
 * app's other bars rather than 44dp on its own, gives it the window insets and
 * the bar semantics, and puts the file's directory on a second line instead of
 * squeezing project and file onto one 400dp row. **The file is the title**:
 * this destination is one buffer at a time and the buffer is what the screen
 * is about; the project's name goes in the subtitle beside the directory,
 * where it identifies without competing.
 *
 * The project chip that used to open Projects & tools went with the old row —
 * a `ThraggTopBar` has one leading slot and it belongs to back. The sheet is
 * still two taps away and from the same thumb: the file bar's tree button
 * opens Files & Find, which carries Projects in its action row. With no
 * project open the empty state carries it as a button, which is the case that
 * mattered — a fresh install must never draw a screen with nothing to press.
 */
@Composable
private fun CodeTopBar(
    projectName: String?,
    file: OpenFile?,
    errorCount: Int,
    onFind: () -> Unit,
    onProblems: () -> Unit,
    onOverflow: () -> Unit,
) {
    val directory = file?.path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
    ThraggTopBar(
        title = file?.name ?: projectName ?: "No project",
        subtitle = when {
            file == null -> projectName?.let { "no file open" }
            else -> listOfNotNull(directory, projectName).joinToString(" · ").ifEmpty { null }
        },
        actions = {
            // The unsaved mark sits with the actions rather than beside the
            // title: a dot inside a `titleLarge` line reads as punctuation,
            // and this one is a *state*. It fades when the awaited save lands.
            UnsavedDot(
                dirty = file?.isDirty == true,
                size = DirtyDot,
                modifier = Modifier.padding(end = MD.space1),
            )
            ThraggIconButton(
                icon = R.drawable.ic_ui_magnifying_glass,
                // It searches *this buffer* — the file bar's magnifier is the
                // one that searches the project. The two were both labelled
                // "Search in files", which is a thing a screen reader user
                // could only discover by pressing the wrong one.
                description = "Find in this file",
                onClick = onFind,
                tint = mutedIcon,
            )
            // The count is the point, so this one keeps its number and gains
            // the glyph beside it rather than becoming an icon-only button.
            if (errorCount > 0) {
                ProblemsAction(errorCount, onProblems)
            }
            ThraggIconButton(
                icon = R.drawable.ic_ui_more_vertical,
                description = "More",
                onClick = onOverflow,
                tint = mutedIcon,
            )
        },
    )
}

/** `✕ 3` — the error count, with the mark that says what is being counted. */
@Composable
private fun ProblemsAction(errorCount: Int, onClick: () -> Unit) {
    // The solved ink, not `error` raw: this is a label on the Material half and
    // it has to clear 4.5:1 on the bar it sits in (docs/VISUAL.md, "THE
    // HYBRID" — inks in the Material half are solved).
    val tint = LocalThraggColors.current.removedInk
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(MD.radiusXs))
            .clickable(onClickLabel = "Problems", onClick = onClick)
            .padding(horizontal = MD.iconGap, vertical = MD.space2)
            .semantics { contentDescription = "$errorCount problems" },
    ) {
        ThraggIcon(
            icon = R.drawable.ic_ui_close,
            contentDescription = null,
            tint = tint,
            size = IconSize.Marker,
        )
        Text(
            text = "$errorCount",
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TabularNums),
            color = tint,
            maxLines = 1,
            modifier = Modifier.padding(start = MD.space05),
        )
    }
}

/**
 * `Ln 104, Col 31 · rust · 2 problems` — 28dp of what the caret is standing
 * on, under the buffer and over the file bar.
 *
 * On the Material side of the seam, because it is chrome about the file rather
 * than a rendering of it (docs/VISUAL.md's Code wireframe puts it below the
 * double rule). Three decisions worth their lines:
 *
 *  - **It reads the caret here.** `cursorRow`/`cursorCol` are snapshot state
 *    on [EditorState], so the read has to happen inside the smallest
 *    composable that needs it or every keystroke would recompose the whole
 *    destination — including the pane that is drawing the keystroke.
 *  - **Tabular figures.** The two numbers change under a moving finger and
 *    proportional digits make the row jitter as a `1` becomes a `0`
 *    (Type.kt, [TabularNums]).
 *  - **The language is read off the engine, once, off the main thread.**
 *    `BufferSession.language` is a JNI call, and this row is recomposed by
 *    every caret move.
 *
 * There is deliberately no "UTF-8": the app does not know a buffer's encoding
 * — the engine reads bytes as UTF-8 and says nothing about what they were —
 * and a status line that states an unchecked fact is worse than one that
 * leaves it out.
 *
 * It hides with the keyboard, under the same rule as [FileBar] and
 * [to.eyed.thragg.ui.shell.ShellNavBar] and for the same arithmetic: the
 * typing posture's ~454dp of buffer is what is left after the 56dp bar and the
 * 44dp file bar give their space back (docs/UI.md, "Code with the soft
 * keyboard up"), and a row that stayed would quietly spend 28dp of it. This is
 * the only band this destination gained, so it is the only one that could.
 *
 * The half of it that changes while you type does NOT go with it: the caret's
 * position is drawn on the keyboard's own dock instead, 18dp above the action
 * row, where it is read without spending the buffer's height twice (G-08 —
 * with the IME up this row was the only thing printing Ln/Col, so with the
 * IME up nothing did). The language and the problem count stay here; neither
 * moves under a keystroke.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorStatusLine(editor: EditorState, file: OpenFile) {
    if (WindowInsets.isImeVisible) return
    var language by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        language = withContext(Dispatchers.IO) { runCatching { file.session?.language }.getOrNull() }
    }
    // The same count the ✕ badge and the Problems route take, for this file:
    // the server's rows and the last build's, each problem once.
    val problems = countFileProblems(file.absolutePath, editor.diagnostics.rows).total
    val position = "Ln ${editor.cursorRow + 1}, Col ${editor.cursorCol + 1}"
    val text = listOfNotNull(
        position,
        language,
        when (problems) {
            0 -> null
            1 -> "1 problem"
            else -> "$problems problems"
        },
    ).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(StatusLineHeight)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = MD.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = TabularNums),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What a save does when the file changed **on disk** under a dirty buffer.
 *
 * Two callers' worth of policy and no more: a save the user asked for can ask
 * them, and an autosave — leaving a file, leaving Code, leaving the app, the
 * `after_delay` debounce — cannot, because the question is "which of these two
 * versions is the one you want" and nobody is looking at the screen.
 */
internal enum class DiskChange {
    /** Raise [DiskChangeDialog] and do what it says. */
    Ask,

    /** Do not write. Say so, leave the buffer dirty, and let the user decide. */
    Hold,
}

/** The three answers to [DiskChangeDialog]. */
internal enum class DiskChangeAnswer { Overwrite, Reload, Cancel }

/** A save parked on that question. */
internal class DiskConflict(
    val file: OpenFile,
    val answer: CompletableDeferred<DiskChangeAnswer>,
)

/** The toast key the disk-change complaint is keyed on, so it replaces. */
private fun diskChangeKey(path: String): String = "disk:$path"

/**
 * "main.rs changed on disk" — the question an overwrite has to ask.
 *
 * The shape is [UnsavedChangesDialog]'s, and for the same reason: it is the
 * only other place in this app where a single tap can destroy work that is not
 * on screen. Here there are *two* versions and only the user knows which one
 * matters — the agent's rewrite of the file, or the edits in the buffer — so
 * the safe answer (Cancel) is the one nearest the thumb and neither
 * destructive answer is the default.
 */
@Composable
private fun DiskChangeDialog(file: OpenFile, onAnswer: (DiskChangeAnswer) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(DiskChangeAnswer.Cancel) },
        // Zero, like every other dialog in both halves: `surfaceTint` is
        // transparent, so a tonal elevation would tint nothing.
        tonalElevation = 0.dp,
        title = { Text("${file.name} changed on disk") },
        text = {
            Text(
                text = "This file was rewritten outside the editor — by the agent, a build " +
                    "or a git command — while your buffer has unsaved edits. Overwrite " +
                    "replaces the file with your buffer; Reload throws your edits away and " +
                    "takes the file.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(DiskChangeAnswer.Overwrite) }) { Text("Overwrite") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(MD.space1)) {
                TextButton(onClick = { onAnswer(DiskChangeAnswer.Cancel) }) { Text("Cancel") }
                TextButton(onClick = { onAnswer(DiskChangeAnswer.Reload) }) { Text("Reload") }
            }
        },
    )
}

/**
 * Put the caret back on screen when the keyboard's inset changes.
 *
 * Draws nothing. It exists to hold the composition read of
 * `WindowInsets.isImeVisible` in a composable of its own, so that the
 * keyboard's animation recomposes 0 pixels instead of the whole destination —
 * and to wait two frames before asking, because the pane's viewport is
 * measured by the layout pass that the inset change causes, and
 * `ensureCursorVisible` against a viewport that has not been re-measured
 * scrolls to the wrong place (the same two frames `SessionItem.restoreIn`
 * waits for, and for the same reason).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImeCaretReveal(editor: EditorState) {
    val visible = WindowInsets.isImeVisible
    LaunchedEffect(editor, visible) {
        withFrameNanos { }
        withFrameNanos { }
        editor.ensureCursorVisible()
    }
}

/**
 * Empty Code: the shared [EmptyState], with one button.
 *
 * Never a blank screen with nothing to press (docs/UI.md, "First run", step 4)
 * — the empty state of the start destination is the first thing a fresh
 * install shows after Setup, and a blank rectangle there reads as a crash.
 * The way out is a real filled `Button` rather than a tinted line of text: it
 * is the only action on the screen, and the one place in Code where a button
 * that looks like a button is worth 44dp.
 */
@Composable
private fun CodeEmpty(headline: String, body: String, action: String, onAction: () -> Unit) {
    // The one button on the screen gives under the thumb like every other
    // filled object in the Material half; a stock Button only ripples.
    val interaction = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            headline = headline,
            body = body,
            action = {
                Button(
                    onClick = onAction,
                    interactionSource = interaction,
                    modifier = Modifier.pressScale(interaction),
                ) { Text(action) }
            },
        )
    }
}

/** Which modal sheet Code has up, if any. */
internal sealed interface CodeSheet {
    data class Files(val mode: FilesMode) : CodeSheet

    data object Overflow : CodeSheet

    /** P8's Projects & tools, raised by the header's project chip. */
    data object Projects : CodeSheet
}

/** The two pickers the ⋮ sheet raises over the buffer. */
private enum class CodePicker { Symbol, Line }

/**
 * Everything the Code destination keeps across a rotation, held **outside the
 * composition** for the same reason [ShellState] is: a configuration change
 * tears the composition down, and what would be lost here is not a cached
 * value but every open buffer, every caret and every scroll position.
 *
 * A class with one process-wide instance rather than an `object` so a test can
 * build a fresh one. `rememberSaveable` is not the answer — it survives
 * process death, which is P9's job, and it does *not* survive being removed
 * from the composition, which is exactly what switching to Agent does.
 */
class CodeState {
    /**
     * The open buffers. `panes.active.files` in the old shell; a single
     * [OpenFilesState] owned by the destination now, because a 400dp column
     * cannot hold two editors and there is no pane tree left to ask.
     */
    val files = OpenFilesState()

    /** Whose files these are, so a project switch can throw them away exactly once. */
    var projectRoot: String? = null

    /** The leave-a-file autosave's bookkeeping — AutosaveTracker.kt, kept. */
    val autosave = AutosaveTracker()

    /** The pane's popups, for back's step 1. See [EditorOverlays]. */
    val overlays = EditorOverlays()

    /**
     * Files another destination asked Code to open, oldest first.
     *
     * A queue rather than a call because the ask arrives while Code is *not*
     * on screen — that is what "open this build error" means — and because
     * there may be no project open yet on a cold start through a share. It
     * drains when both are true.
     */
    val pendingOpens = mutableStateListOf<PendingOpen>()

    /**
     * Saved places — caret, selection and scroll — waiting for their tab to
     * be the one on screen, keyed by the tab's path.
     *
     * A scroll can only be restored against a laid-out viewport, so a
     * document that restores four files can put back exactly one of them at
     * the moment it opens: the one that ends up active. The other three wait
     * here until the user selects them, which is the first moment their
     * editor has a height to clamp against.
     */
    val places = mutableMapOf<String, SessionItem>()

    /**
     * The scope the saves that outlive the composition run in.
     *
     * Leaving Code writes every dirty buffer, and "leaving" is precisely the
     * moment `rememberCoroutineScope` is cancelled. `Main.immediate` so a save
     * started on the way out is already running before the frame ends.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        val current: CodeState = CodeState()
    }
}

/**
 * The seam ▶ Build runs through, filled in by solana/build/BuildRunner.kt
 * (P4).
 *
 * A holder rather than a call, because P2 and P4 land in parallel and this
 * file must not import a package that does not exist yet — and because the
 * ordering ▶ promises (save every dirty buffer, *wait*, then run) belongs on
 * the editor's side of the seam, where the buffers are. What P4 fills in is
 * only the second half.
 *
 * Null is a real state and it is handled rather than crashed on: a build with
 * no runner says so once, in a toast, and does nothing.
 */
/**
 * A file another destination asked Code to open, and where in it to land.
 *
 * [row] and [column] are 1-based, as the compiler, the terminal and every LSP
 * client on earth spell a position, and `0` means "not known" — which is what
 * a plain [ShellState.openPath] hands over, and is answered by leaving the
 * caret where the file was last left rather than by jumping to line 1.
 */
data class PendingOpen(
    val path: String,
    val row: Int = 0,
    val column: Int = 0,
    /**
     * A saved place to put back whole — caret and scroll — instead of a line
     * to jump to. Set only by the session restore (ui/shell/SessionRestore.kt).
     */
    val restore: SessionItem? = null,
)

object CodeBuildSeam {
    /** Run a build of [project]. Set once, at startup, by P4. */
    var run: ((project: ProjectSession) -> Unit)? = null
}

/**
 * `[ Fix ▸ ]` — hand a diagnostic to the agent.
 *
 * The signature is EditorPane's and does not change; what changed is the
 * second half of the handoff. The error, its file, its line and the compiler's
 * own words go into the Agent composer through [AgentSeams], and the file goes
 * with them as a mention so the agent *reads* it rather than guessing at it
 * from a path in a sentence.
 *
 * **Seeded, not sent.** A diagnostic is a fact, not yet a request: the user
 * finishes the sentence ("…without changing the account layout") and presses
 * Send. The switch happens first so the composer is on screen when the text
 * lands in it.
 */
internal fun fixWithAgent(state: ShellState, path: String, diagnostic: Diagnostic) {
    state.show(Destination.Agent)
    AgentSeams.offer(agentFixPrompt(path, diagnostic), listOf(AgentMention.File(path)))
}

/**
 * How often an open buffer is re-checked against the disk.
 *
 * The engine's own status is a version compare, so the loop is cheap; what it
 * catches is a file moved, deleted or rewritten under a buffer by a git
 * command, a build script or the agent — carried across from
 * WorkspaceScreen.kt:249 with its interval intact.
 */
private const val STATUS_POLL_MS = 250L

/**
 * Whether the soft keyboard is on screen right now, read off the window
 * rather than the composition — for the one caller that asks from a
 * coroutine, after a restore, and must not subscribe the screen to the
 * insets to find out.
 */
private fun imeIsShowing(view: View): Boolean =
    ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/** The toast key the project-settings complaint is keyed on, so it replaces. */
private const val LOCAL_SETTINGS_NOTIFICATION = "project-settings"

/** …and the user file's, which had no complaint at all until 0.0.22 (G-11). */
private const val GLOBAL_SETTINGS_NOTIFICATION = "settings-json"

/**
 * Whether the project's own settings file parsed, said once.
 *
 * Keyed, because it is asked twice — by the save that wrote the file and by
 * the poller that noticed it move — and because the answer changing to "it
 * parses now" has to take the toast away rather than leave a stale complaint
 * on screen.
 */
private fun reportLocalSettings(error: String?) {
    if (error == null) {
        Notifications.dismissKey(LOCAL_SETTINGS_NOTIFICATION)
    } else {
        Notifications.error(
            "$LOCAL_SETTINGS_PATH is not in effect: $error",
            key = LOCAL_SETTINGS_NOTIFICATION,
        )
    }
}

/**
 * [path] as this project spells it, or unchanged when it is not inside the
 * project at all.
 *
 * Every opener in this file takes a *project-relative* path — that is the only
 * name the engine's buffers have — while the compiler, the language server and
 * the terminal all answer in absolute ones. Returning the input unchanged for
 * an outside path is what lets the callers drop it: a definition in the
 * standard library or in a registry crate has no relative name, and opening it
 * at a path that does not resolve is worse than not opening it.
 */
internal fun relativeTo(project: ProjectSession, path: String): String {
    if (!path.startsWith('/')) return path
    val root = project.rootPath
    if (path == root) return path
    val prefix = "$root/"
    return if (path.startsWith(prefix)) path.removePrefix(prefix) else path
}

/**
 * The buffer's settings with wrapping forced on.
 *
 * Not a preference on this device: the column is 400dp wide and a line that
 * runs off the right edge is a line that has to be scrolled horizontally to be
 * read, one line at a time. A file whose settings already wrap keeps the mode
 * it asked for — `bounded` at 80 columns is still narrower than the screen.
 *
 * This is the *whole* truth about `soft_wrap` in this app, which is why
 * Settings no longer offers a switch for it: the switch wrote the key, this
 * function overrode it, and the row was a placebo in both directions
 * (QA 0.0.22, G-06). Internal rather than private so the rule has a test
 * (`CodeHostTest`) instead of a comment.
 */
internal fun LanguageSettings.wrappedForAPhone(): LanguageSettings =
    if (softWrap.wraps) this else copy(softWrap = SoftWrapMode.EditorWidth)

/**
 * Hand [file] to whatever else is on the phone — the share sheet.
 *
 * The one caller of [ShareOut] on this side of the app, and the only way a
 * file leaves a sandboxed IDE at all: there is no file manager on this device
 * that can reach the app's private projects directory. A tab with no file
 * behind it (a picture that failed to stage, a buffer never written) is
 * skipped rather than shared as a path that does not exist.
 */
internal fun shareFile(context: android.content.Context, file: OpenFile) {
    val absolute = file.absolutePath ?: return
    val onDisk = File(absolute)
    if (!ShareOut.canShare(onDisk)) return
    ShareOut.share(context, onDisk)
}

/** 28dp — the status line under the buffer (docs/VISUAL.md's Code wireframe). */
private val StatusLineHeight = 28.dp

/** The unsaved mark in the bar, at the size the file bar's chips draw it. */
private val DirtyDot = 8.dp
