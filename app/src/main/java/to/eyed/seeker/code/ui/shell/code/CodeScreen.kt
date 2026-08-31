package to.eyed.seeker.code.ui.shell.code

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.BufferSession
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.FormatterSpec
import to.eyed.seeker.code.core.LanguageSettings
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.ShareOut
import to.eyed.seeker.code.ui.common.BinaryPlaceholder
import to.eyed.seeker.code.ui.common.UnsavedChangesDialog
import to.eyed.seeker.code.ui.editor.Diagnostic
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.editor.EditReceipt
import to.eyed.seeker.code.ui.editor.EditSummary
import to.eyed.seeker.code.ui.editor.EditorOverlays
import to.eyed.seeker.code.ui.editor.EditorPane
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.editor.LspRequestState
import to.eyed.seeker.code.ui.editor.SoftWrapMode
import to.eyed.seeker.code.ui.editor.applyPendingEdit
import to.eyed.seeker.code.ui.editor.pollLspRequest
import to.eyed.seeker.code.ui.editor.requestFormatting
import to.eyed.seeker.code.ui.editor.revealDefinitionTarget
import to.eyed.seeker.code.solana.build.BuildRunner
import to.eyed.seeker.code.ui.media.MediaKind
import to.eyed.seeker.code.ui.search.BufferSearchBar
import to.eyed.seeker.code.ui.search.SearchDeploy
import to.eyed.seeker.code.ui.shell.BackSeam
import to.eyed.seeker.code.ui.shell.BuildState
import to.eyed.seeker.code.ui.shell.Destination
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.shell.build.CodeJump
import to.eyed.seeker.code.ui.shell.projects.ProjectsSheet
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.workspace.AutosaveTracker
import to.eyed.seeker.code.ui.workspace.GoToLine
import to.eyed.seeker.code.ui.workspace.Notifications
import to.eyed.seeker.code.ui.workspace.OpenFile
import to.eyed.seeker.code.ui.workspace.OpenFilesState
import to.eyed.seeker.code.ui.workspace.OutlinePicker

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
    val theme = LocalZedTheme.current
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
    suspend fun openFileInto(open: ProjectSession, path: String): OpenFile? {
        val existing = files.indexOfPath(path)
        if (existing >= 0) {
            files.select(existing)
            return files.tabs[existing]
        }
        val absolutePath = open.absolutePathOf(path) ?: return null
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
        val open = project ?: return
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
     */
    suspend fun saveNow(file: OpenFile, format: Boolean = true) {
        val open = file.session ?: return
        if (file.isReadOnly) return
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
    fun save(file: OpenFile, format: Boolean = true) {
        scope.launch { saveNow(file, format) }
    }

    /**
     * Save every dirty buffer and *wait*, then run [after].
     *
     * This is the atom ▶ Build is. Sequential rather than parallel because
     * `format_on_save` funnels through the language server either way and two
     * concurrent workspace edits over the same server is how a formatting
     * lands in the wrong file.
     */
    suspend fun saveAllDirty(format: Boolean = true) {
        files.refreshStatuses()
        for (file in files.tabs.toList()) {
            if (file.isDirty && !file.isReadOnly) saveNow(file, format)
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
            for (file in files.tabs.toList()) {
                if (!file.isDirty || file.isReadOnly) continue
                val session = file.session ?: continue
                if (withContext(Dispatchers.IO) { session.save() }) written++
                code.autosave.saved(file.path)
                file.refreshStatus()
            }
            written
        }
    }
    LaunchedEffect(code.pendingOpens.size, project) {
        val open = project ?: return@LaunchedEffect
        while (code.pendingOpens.isNotEmpty()) {
            val pending = code.pendingOpens.removeAt(0)
            openFile(relativeTo(open, pending.path)) { file ->
                // 1-based from the compiler and from the terminal, 0-based in
                // the buffer; 0 means "no position was known", and then the
                // caret is left exactly where this file was last read.
                if (pending.row > 0) {
                    file.editor?.revealDefinition(
                        pending.row - 1,
                        (pending.column - 1).coerceAtLeast(0),
                    )
                }
            }
        }
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
    ResumedEffect(project) {
        var settingsVersionSeen = -1L
        while (true) {
            files.refreshStatuses()
            for (file in files.tabs) {
                // A file the engine moved under us (a workspace edit, a git
                // command) — one field compared, no bridge call.
                file.editor?.resyncIfBufferMoved()
                if (file.hasDiskChange && !file.isDirty) {
                    withContext(Dispatchers.IO) { file.session?.reload() }
                    file.refreshStatus()
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

    // "Autosave on leaving a file", on and not a setting anybody has to find
    // (docs/UI.md, P8, and step 8 of the back handler, which leaves the app
    // with no confirm *because* of this). Leaving a file is the active buffer
    // changing; the one that was active is written.
    var previouslyActive by remember { mutableStateOf<OpenFile?>(null) }
    LaunchedEffect(active) {
        val departed = previouslyActive
        previouslyActive = active
        if (departed != null && departed !== active && departed in files.tabs) {
            departed.refreshStatus()
            if (departed.isDirty && !departed.isReadOnly) save(departed, format = false)
        }
    }

    // …and leaving the *app*, which on Android is the common case: the
    // process holding a 1.4 GB toolchain is killed aggressively, and back at
    // Code's root is one gesture (step 8). Every dirty buffer goes out here.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        for (file in files.tabs) {
            if (file.isDirty && !file.isReadOnly) save(file, format = false)
        }
    }

    // …and leaving Code for Agent or Build, which is the case that is easy to
    // miss and the one that matters most on this device: the agent reads the
    // files from *disk* through ACP, and the build compiles what is on disk.
    // A conversation about a buffer that was never written is a conversation
    // about the wrong file. Launched in [CodeState]'s own scope, not the
    // composition's — the composition is the thing that is going away.
    DisposableEffect(Unit) {
        onDispose {
            code.scope.launch { saveAllDirty(format = false) }
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
        val runner = CodeBuildSeam.run
        if (runner == null) {
            Notifications.info("The build runner is not installed in this build.", key = "build")
            return
        }
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
            .background(theme.color("editor.background", MaterialTheme.colorScheme.background)),
    ) {
        CodeHeader(
            projectName = project?.let { File(it.rootPath).name },
            file = active,
            errorCount = activeEditor?.diagnostics?.rows?.count {
                it.severity == DiagnosticSeverity.Error
            } ?: 0,
            // P8's Projects & tools sheet, behind the chip where the spec
            // puts it (docs/UI.md, "Projects & tools"). It is not decoration:
            // with no project open this is the *only* way to make one, and
            // until it was wired the start destination of a fresh install was
            // a screen with nothing on it to press.
            onOpenProjects = { sheet = CodeSheet.Projects },
            onFind = {
                val editor = activeEditor ?: return@CodeHeader
                val open = searchDeploy
                val seed = if (open != null && searchFocused) null else editor.searchSeed()
                searchDeploy = SearchDeploy(token = (open?.token ?: 0) + 1, seed = seed)
            },
            onProblems = { state.push(Route.Problems) },
            onOverflow = { sheet = CodeSheet.Overflow },
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                project == null -> CodeEmpty(
                    line = "No project is open.",
                    action = "Projects & tools",
                    onAction = { sheet = CodeSheet.Projects },
                )
                active == null -> CodeEmpty(
                    line = "Nothing open yet.",
                    action = "Browse files",
                    onAction = { sheet = CodeSheet.Files(FilesMode.Names) },
                )
                activeEditor != null -> EditorPane(
                    state = activeEditor,
                    modifier = Modifier.fillMaxSize(),
                    fileName = active.name,
                    languageSettings = active.languageSettings.wrappedForAPhone(),
                    showInlineBlame = active.languageSettings.inlineBlame,
                    onOpenDefinition = { target ->
                        // The server answers in absolute paths and the project
                        // opens by its own relative spelling; a target outside
                        // the root — the standard library, a registry crate —
                        // is dropped rather than opened as a path that does
                        // not resolve (WorkspaceScreen.kt:2104).
                        // `project` is non-null in this branch — the `when`
                        // above answered the no-project case first.
                        val relative = relativeTo(project, target.path)
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
                // A 1.4 MB `.so` never reaches the text rope: MediaKind routed
                // it away in `openFileInto`, and this is what it routed it to.
                else -> BinaryPlaceholder(
                    absolutePath = active.absolutePath.orEmpty(),
                    kind = active.media,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Find and replace, re-hosted at the *bottom* of the screen rather
        // than at the top of the editor, and lifted onto the keyboard by
        // `imePadding` — the desktop habit of a search field at the top with
        // the keyboard covering its own results is the thing being fixed.
        val deploy = searchDeploy
        if (deploy != null && activeEditor != null) {
            Box(modifier = Modifier.fillMaxWidth().imePadding()) {
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

        FileBar(
            files = files,
            onSelect = { index -> files.select(index) },
            onRequestClose = { index -> files.requestClose(index) },
            onFind = { sheet = CodeSheet.Files(FilesMode.InFiles) },
            onFiles = { sheet = CodeSheet.Files(FilesMode.Names) },
        )
    }

    // Every route into closing a file goes through this dialog, and a host
    // that forgets to compose it fails *silently* — the request parks in
    // `OpenFilesState.closeConfirmation` and the file simply never closes.
    // Composed here, once, above every route that can ask: the file bar's
    // long-press, the Files sheet's ✕, and a project switch.
    UnsavedChangesDialog(files)

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
 * The 44dp header: identity on the left, the rare exits on the right.
 *
 * Everything here is either something you read (which project, which file,
 * whether it is dirty, how many errors) or something you press once an hour.
 * Everything you press once a minute is in the file bar at the bottom, which
 * is the whole reachability argument (docs/UI.md, "Why", defect 3).
 */
@Composable
private fun CodeHeader(
    projectName: String?,
    file: OpenFile?,
    errorCount: Int,
    /** Opens Projects & tools — P8's sheet, and null until it lands. */
    onOpenProjects: (() -> Unit)?,
    onFind: () -> Unit,
    onProblems: () -> Unit,
    onOverflow: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HeaderHeight)
            .background(theme.color("status_bar.background", MaterialTheme.colorScheme.surface))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            // The ▾ is drawn only when there is a sheet behind it. A chevron
            // on a control that opens nothing is a promise the header cannot
            // keep; without it the chip is what the rest of the header is,
            // which is identity.
            text = (projectName ?: "No project") + if (onOpenProjects != null) " ▾" else "",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier
                .let { if (onOpenProjects != null) it.clickable(onClick = onOpenProjects) else it }
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
        Text(
            text = buildString {
                append(file?.name ?: "")
                if (file?.isDirty == true) append(" ●")
            },
            style = MaterialTheme.typography.labelMedium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        HeaderAction("⌕", onFind)
        if (errorCount > 0) {
            HeaderAction(
                label = "✕$errorCount",
                onClick = onProblems,
                tint = theme.color("error", MaterialTheme.colorScheme.error),
            )
        }
        HeaderAction("⋮", onOverflow)
    }
}

@Composable
private fun HeaderAction(
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val theme = LocalZedTheme.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = tint ?: theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        maxLines = 1,
        modifier = Modifier
            .touchTarget()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

/**
 * Empty Code: one line of body text and one button.
 *
 * Never a blank screen with nothing to press (docs/UI.md, "First run", step 4)
 * — the empty state of the start destination is the first thing a fresh
 * install shows after Setup, and a blank rectangle there reads as a crash.
 */
@Composable
private fun CodeEmpty(line: String, action: String?, onAction: () -> Unit) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
        )
        if (action != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = theme.color("text.accent", MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .padding(top = 12.dp)
                    .touchTarget()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
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
data class PendingOpen(val path: String, val row: Int = 0, val column: Int = 0)

object CodeBuildSeam {
    /** Run a build of [project]. Set once, at startup, by P4. */
    var run: ((project: ProjectSession) -> Unit)? = null
}

/**
 * `[ Fix ▸ ]` — hand a diagnostic to the agent.
 *
 * **This is the function P3 fills in.** Everything on this side of it is done:
 * the gutter's ✕ is a touch target, the card is drawn, the button is wired,
 * and the destination switch below is the half of the handoff that needs no
 * agent. What is missing is the other half — seeding the composer with the
 * error, the file and the line — and it needs AgentScreen's composer state,
 * which is P3's and does not exist yet.
 *
 * It is deliberately *not* a no-op in the meantime: taking the user to Agent
 * with the error copied is a worse handoff than a pre-seeded thread and a
 * better one than a button that does nothing.
 */
internal fun fixWithAgent(state: ShellState, path: String, diagnostic: Diagnostic) {
    state.show(Destination.Agent)
    Notifications.info(
        "Fix requested: ${path.substringAfterLast('/')}:${diagnostic.row + 1} — " +
            diagnostic.message.lineSequence().first().trim(),
        key = "fix-with-agent",
    )
}

/**
 * Soft wrap, on.
 *
 * Zed's default is `none` and it is right for a 1200dp window; here the
 * viewport is 400dp wide and a line of Rust is not, so horizontal scrolling
 * would be the primary way of reading code. The mode and its Fenwick tree
 * already exist (DisplayMap.kt) with their own test suite, so this is a
 * default, not a feature.
 *
 * Applied here rather than only in settings.json because it must be true of a
 * fresh install with no settings file at all, and because the value the engine
 * resolves per buffer stacks the language's own entry on top of the user's.
 */
private fun LanguageSettings.wrappedForAPhone(): LanguageSettings =
    if (softWrap == SoftWrapMode.None) copy(softWrap = SoftWrapMode.EditorWidth) else this

/**
 * A path the shell handed in, as a path the project can open.
 *
 * Callers outside Code speak in absolute paths — a shared file lands at
 * `File(root, relative).absolutePath`, a build error names a real file on
 * disk — and [ProjectSession.absolutePathOf] takes the project-relative
 * spelling. Anything already relative, or outside the root, is passed through
 * untouched so the failure is "could not be opened" rather than a silently
 * mangled path.
 */
private fun relativeTo(project: ProjectSession, path: String): String {
    if (!path.startsWith('/')) return path
    val root = project.rootPath.trimEnd('/')
    if (!path.startsWith("$root/")) return path
    return path.removePrefix("$root/")
}

/** Whether the project's own settings file parsed, said once. */
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

/** Share the open file out — the ⋮ sheet's row, and ShareOut's one caller here. */
internal fun shareFile(context: android.content.Context, file: OpenFile) {
    val path = file.absolutePath ?: return
    ShareOut.share(context, File(path))
}

/** How often every open buffer's status is re-read. Zed polls; so do we. */
private const val STATUS_POLL_MS = 250L

private const val LOCAL_SETTINGS_PATH = ".zed/settings.json"
private const val LOCAL_SETTINGS_NOTIFICATION = "project-settings"

/** The header, the file bar and the action row are all the same height. */
internal val HeaderHeight = 44.dp
