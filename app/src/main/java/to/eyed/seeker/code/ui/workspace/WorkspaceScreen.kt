package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.FontSettings
import to.eyed.seeker.code.core.BufferEncoding
import to.eyed.seeker.code.core.PreviewRoute
import to.eyed.seeker.code.core.TabSettings
import to.eyed.seeker.code.core.BufferSession
import to.eyed.seeker.code.core.CoreBridge
import to.eyed.seeker.code.core.ExcerptRequest
import to.eyed.seeker.code.core.ImportRequest
import to.eyed.seeker.code.core.IncomingFiles
import to.eyed.seeker.code.core.AddFolderResult
import to.eyed.seeker.code.core.LanguageChoice
import to.eyed.seeker.code.core.Languages
import to.eyed.seeker.code.core.LanguageServerInstaller
import to.eyed.seeker.code.core.LineEnding
import to.eyed.seeker.code.core.MultiBufferSession
import to.eyed.seeker.code.core.ShareOut
import to.eyed.seeker.code.core.StagedFile
import to.eyed.seeker.code.ui.agent.AgentPanel
import to.eyed.seeker.code.core.AgentMention
import to.eyed.seeker.code.core.AgentSessions
import to.eyed.seeker.code.ui.agent.AgentReviewPane
import to.eyed.seeker.code.ui.agent.AgentWorkspaceAccess
import to.eyed.seeker.code.ui.agent.OpenBufferRef
import to.eyed.seeker.code.ui.agent.isAgentPanelSupported
import to.eyed.seeker.code.core.DockSide
import to.eyed.seeker.code.core.ProjectEntry
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.core.ProjectSummary
import to.eyed.seeker.code.core.ProjectWorktree
import to.eyed.seeker.code.core.ProjectsRoot
import to.eyed.seeker.code.core.RecentProject
import to.eyed.seeker.code.core.WorkspaceSession
import to.eyed.seeker.code.core.ResumedEffect
import to.eyed.seeker.code.core.SafTransfer
import java.io.File
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.SideEffect
import to.eyed.seeker.code.core.TerminalWorkingDirectory
import to.eyed.seeker.code.core.Toolchain
import to.eyed.seeker.code.core.Toolchains
import to.eyed.seeker.code.terminal.GitClone
import to.eyed.seeker.code.terminal.ShellEnvironment
import to.eyed.seeker.code.terminal.TerminalSessions
import to.eyed.seeker.code.ui.terminal.TerminalPathTarget
import to.eyed.seeker.code.ui.terminal.projectRelativePath
import to.eyed.seeker.code.terminal.Userland
import to.eyed.seeker.code.terminal.UserlandInstaller
import to.eyed.seeker.code.terminal.UserlandState
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ThemeStore
import to.eyed.seeker.code.core.ProjectReplaceReceipt
import to.eyed.seeker.code.core.ProjectSearchFile
import to.eyed.seeker.code.core.ProjectSearchMatch
import to.eyed.seeker.code.ui.search.BufferSearchBar
import to.eyed.seeker.code.ui.search.SearchBarAction
import to.eyed.seeker.code.ui.search.SearchDeploy
import to.eyed.seeker.code.ui.search.ProjectSearchPanel
import to.eyed.seeker.code.ui.search.revealProjectSearchMatch
import to.eyed.seeker.code.ui.diagnostics.DiagnosticsPane
import to.eyed.seeker.code.ui.diagnostics.revealDiagnosticTarget
import to.eyed.seeker.code.ui.editor.Diagnostic
import to.eyed.seeker.code.ui.editor.EditReceipt
import to.eyed.seeker.code.ui.editor.EditorPane
import to.eyed.seeker.code.ui.editor.FileDiagnosticRows
import to.eyed.seeker.code.ui.editor.ReferenceTarget
import to.eyed.seeker.code.ui.editor.MultiBufferPane
import to.eyed.seeker.code.ui.editor.DefinitionTarget
import to.eyed.seeker.code.ui.editor.EditorAction
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.editor.EditSummary
import to.eyed.seeker.code.ui.editor.LspRequestState
import to.eyed.seeker.code.ui.editor.applyPendingEdit
import to.eyed.seeker.code.ui.editor.pollLspRequest
import to.eyed.seeker.code.ui.editor.requestFormatting
import to.eyed.seeker.code.core.Autosave
import to.eyed.seeker.code.core.FormatterSpec
import to.eyed.seeker.code.core.LanguageSettings
import to.eyed.seeker.code.core.LOCAL_SETTINGS_PATH
import to.eyed.seeker.code.ui.editor.revealDefinitionTarget
import to.eyed.seeker.code.ui.editor.SoftWrapMode
import org.json.JSONObject
import to.eyed.seeker.code.ui.editor.LspServer
import to.eyed.seeker.code.ui.editor.LspServerState
import to.eyed.seeker.code.ui.editor.parseLspServers
import to.eyed.seeker.code.ui.editor.rememberLspState
import to.eyed.seeker.code.ui.media.ImageZoom
import to.eyed.seeker.code.ui.media.MediaKind
import to.eyed.seeker.code.ui.git.AskpassDialog
import to.eyed.seeker.code.ui.git.BranchPicker
import to.eyed.seeker.code.ui.git.DiffPane
import to.eyed.seeker.code.ui.git.DiffTarget
import to.eyed.seeker.code.ui.git.GitGraphPane
import to.eyed.seeker.code.ui.git.GitPanel
import to.eyed.seeker.code.ui.git.GitPanelCommand
import to.eyed.seeker.code.ui.git.GitPanelDockWidth
import to.eyed.seeker.code.ui.git.GitPanelRequest
import to.eyed.seeker.code.ui.git.StashPicker
import to.eyed.seeker.code.ui.git.BlameHost
import to.eyed.seeker.code.ui.git.MergeResolvedBar
import to.eyed.seeker.code.ui.git.rememberConflictedPaths
import to.eyed.seeker.code.core.GitSession
import to.eyed.seeker.code.ui.git.rememberGitBranch
import to.eyed.seeker.code.ui.media.MediaPane
import to.eyed.seeker.code.ui.preview.MarkdownPreview
import to.eyed.seeker.code.ui.preview.PreviewDockWidth
import to.eyed.seeker.code.ui.preview.PreviewKind
import to.eyed.seeker.code.ui.preview.SvgPreview
import to.eyed.seeker.code.ui.preview.TablePreview
import to.eyed.seeker.code.core.Runnable
import to.eyed.seeker.code.ui.tasks.TaskPicker
import to.eyed.seeker.code.ui.tasks.TaskPickerRequest
import to.eyed.seeker.code.ui.tasks.TaskRuns
import to.eyed.seeker.code.ui.tasks.editorTaskContext
import to.eyed.seeker.code.ui.tasks.loadTasks
import to.eyed.seeker.code.ui.tasks.runTask
import to.eyed.seeker.code.core.TaskSave
import to.eyed.seeker.code.core.TaskSpec
import to.eyed.seeker.code.ui.terminal.TerminalDock

/**
 * Where the project panel stops being a drawer and becomes Zed's sidebar.
 *
 * 840dp is Material's "expanded" breakpoint, and it was the wrong rule: the
 * Fold's inner display is 674dp at its density, so the device this app is
 * built for was getting the phone layout while unfolded. What actually
 * matters is whether the editor is still usable beside a 240dp panel, and at
 * 600dp it has 360dp — a phone's whole width — so that is the line.
 */
private val WideLayoutMinWidth = 600.dp

/**
 * Notification keys — Zed's `NotificationId`s (notifications.rs:42-63). A
 * message that is *about a state* rather than about an event carries one, so
 * saying it twice replaces the first toast and the state going away takes the
 * toast with it.
 */
private const val LOCAL_SETTINGS_NOTIFICATION = "project-settings"
private const val KEYMAP_NOTIFICATION = "keymap"
private const val SETTINGS_NOTIFICATION = "settings"
private const val LSP_CRASH_NOTIFICATION = "lsp-crash"

/** The worktree scan's activity key — one per project, so switching swaps it. */
private const val SCAN_ACTIVITY = "worktree-scan"
// Zed's own default (assets/settings/default.json:816).
/**
 * The narrowest a dock can be dragged. Narrower than this and the panel's own
 * rows stop making sense — a file name in 120dp is an ellipsis.
 */
private val DockMinWidth = 200.dp

/** What ProjectSearchPanel asks for as a dock — kept in step with it. */
private val ProjectSearchDockWidth = 360.dp

/**
 * Under this the editor is not worth showing beside two docks; the search
 * panel takes the whole work area instead. A phone's own width, which is the
 * least anyone edits code in.
 */
private val MinEditorWidth = 360.dp

/** Terminal dock: initial height, and how small or large a drag may make it. */
private val TerminalDockHeight = 260.dp
private val TerminalDockMinHeight = 96.dp

/** The file opened on a fresh install, relative to the sample project root. */
private const val STARTUP_FILE = "src/main.rs"

/**
 * How often to re-read each open buffer's dirty / on-disk state from the
 * engine. Those are plain JNI getters rather than observable state, so the UI
 * pulls them; a few calls per second per tab is far cheaper than making every
 * keystroke push through the bridge.
 */
private const val STATUS_POLL_MS = 250L

/**
 * How often the workspace re-reads the project's folder list. Slower than the
 * tab statuses: folders change when somebody adds or removes one, which is a
 * deliberate act, and the panel has its own faster loop for the tree itself.
 */
private const val FOLDERS_POLL_MS = 500L

/**
 * How long the workspace waits after the last change before writing the
 * session down. A caret moves per keystroke, and a keystroke is not worth a
 * file write; a second after the typing stops is.
 */
private const val SESSION_SAVE_DEBOUNCE_MS = 1_000L

/**
 * The tab key the built-in default settings open under. Not a path anything
 * can write to: the tab is read-only, and the key only has to be one no
 * project file can collide with.
 */
private const val DEFAULT_SETTINGS_TAB = "zed://default-settings.json"

/**
 * What a freshly created `.zed/settings.json` holds — Zed's
 * `initial_project_settings_content` (settings/src/settings.rs), which is a
 * comment and an empty object.
 */
private const val INITIAL_PROJECT_SETTINGS = """// Project-specific settings for this project only.
//
// The editor, language, lsp and git keys go here — tab_size, hard_tabs,
// soft_wrap, preferred_line_length, format_on_save, formatter,
// code_actions_on_format, enable_language_server, "languages": { … },
// "lsp": { … }, "git": { … }. Everything else stays in the user settings.
{
}
"""

/** The project's own tasks file, as the engine reads it (tasks.rs). */
private const val LOCAL_TASKS_PATH = ".zed/tasks.json"

/**
 * What a freshly created `tasks.json` holds — Zed's `initial_tasks_content`
 * (assets/settings/initial_tasks.json), minus the keys this port has no
 * home for: `reveal_target` (no centre pane to put a terminal in), `shell`
 * (the userland's login shell runs every task) and `hooks` (no git
 * worktrees). The same text seeds both the user's file and a project's.
 */
private const val INITIAL_TASKS = """// Tasks: shell commands the terminal runs for you. See docs/TASKS.md.
//
// Example:
[
  {
    "label": "Example task",
    "command": "for i in {1..5}; do echo \"Hello ${'$'}i/5\"; sleep 1; done",
    //"args": [],
    // Env overrides for the command, will be appended to the terminal's environment from the settings.
    "env": { "foo": "bar" },
    // Current working directory to spawn the command into, defaults to current project root.
    //"cwd": "/path/to/working/directory",
    // Whether to use a new terminal tab or reuse the existing one to spawn the process, defaults to `false`.
    "use_new_terminal": false,
    // Whether to allow multiple instances of the same task to be run, or rather wait for the existing ones to finish, defaults to `false`.
    "allow_concurrent_runs": false,
    // What to do with the terminal pane and tab, after the command was started:
    // * `always` — always show the task's pane, and focus the corresponding tab in it (default)
    // * `no_focus` — always show the task's pane, add the task's tab in it, but don't focus it
    // * `never` — do not alter focus, but still add/reuse the task's tab in its pane
    "reveal": "always",
    // What to do with the terminal pane and tab, after the command had finished:
    // * `never` — Do nothing when the command finishes (default)
    // * `always` — always hide the terminal tab, hide the pane also if it was the last tab in it
    // * `on_success` — hide the terminal tab on task success only, otherwise behaves similar to `always`
    "hide": "never",
    // Whether to show the task line in the output of the spawned task, defaults to `true`.
    "show_summary": true,
    // Whether to show the command line in the output of the spawned task, defaults to `true`.
    "show_command": true,
    // Which edited buffers to save before running the task:
    // * `all` — save all edited buffers
    // * `current` — save currently active buffer only
    // * `none` — don't save any buffers
    "save": "none"
    // Represents the tags for inline runnable indicators, or spawning multiple tasks at once.
    // "tags": []
  }
]
"""

/**
 * How long the caret rests before the breadcrumbs ask the engine for the
 * symbol path. Arrow-key travel and typing move the caret in bursts; a
 * per-move JNI query would be noise, and the answer only matters once the
 * eye has somewhere to settle.
 */
private const val BREADCRUMB_SETTLE_MS = 80L

/**
 * The engine's outline path — a JSON array of strings, outermost first.
 * Parsed defensively: a null (unknown buffer) or garbage answer is an empty
 * trail, never a crash. `getString`, not `optString`: Android's `org.json`
 * renders a JSON null as the string "null" through `optString`.
 */
private fun parseOutlinePath(json: String?): List<String> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val array = org.json.JSONArray(json)
        buildList {
            for (i in 0 until array.length()) {
                val label = array.getString(i)
                if (label.isNotBlank()) add(label)
            }
        }
    } catch (_: org.json.JSONException) {
        emptyList()
    }
}

/**
 * The line between two docks.
 *
 * Material's dividers default to `outlineVariant`, which our theme maps to
 * Zed's `border.variant` — and Zed reserves that for the quieter lines inside
 * a panel (a toolbar's underline, a list separator). The edges *between* docks
 * are `border` (crates/workspace/src/dock.rs:1203), and at One Dark's values
 * the two differ enough to read as a different app.
 */
/**
 * The active pane's tabs — what every command that says "the active tab"
 * means. A property rather than a captured value so that activating another
 * pane redirects every reader at once.
 */
private val PaneGroupState.files: OpenFilesState get() = active.files

@Composable
private fun DockDivider(vertical: Boolean = false) {
    val color = LocalZedTheme.current.color("border")
    if (vertical) VerticalDivider(color = color) else HorizontalDivider(color = color)
}

/**
 * Which of the workspace's overlays are on screen — every dialog, picker and
 * palette drawn over the work area, one flag each.
 *
 * A remembered holder rather than ten delegated locals of [WorkspaceScreen]:
 * the flags are written from callbacks handed all over the workspace — menus,
 * chords, the status bar — and read only where each overlay is rendered.
 * Kept on one stable object, a callback that opens an overlay captures
 * `overlays` alone, and flipping a flag invalidates the scopes that read it,
 * never the children that merely hold the callbacks writing it.
 */
private class WorkspaceOverlays {
    // Removing the Linux userland throws away ~100 MB and everything
    // installed into it, so it confirms first — same rule as deleting a
    // project.
    var removeUserlandOpen by mutableStateOf(false)
    // `projects` is re-listed whenever the picker opens or a transfer
    // finishes, rather than watched — projects change only when the user
    // changes them.
    var pickerOpen by mutableStateOf(false)
    /** The tab bar's `+`, Ctrl+N, and the palette's `workspace: new file`. */
    var newFileOpen by mutableStateOf(false)
    /**
     * Whether the language-server prompt is on screen. The install itself
     * lives in [LanguageServerInstaller], so closing this does not stop apt.
     */
    var serverPromptOpen by mutableStateOf(false)
    var finderOpen by mutableStateOf(false)
    var paletteOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var themeSelectorOpen by mutableStateOf(false)
    /** Zed's `icon_theme_selector::Toggle`. */
    var iconThemeSelectorOpen by mutableStateOf(false)
    /** The `LF` / `CRLF` picker, from the status bar or the palette. */
    var lineEndingSelectorOpen by mutableStateOf(false)
    /** The encoding picker — reopen with, or save with. */
    var encodingSelectorOpen by mutableStateOf(false)
    /** The language picker — Zed's `language_selector::Toggle`, `ctrl-k m`. */
    var languageSelectorOpen by mutableStateOf(false)
    /** The toolchain picker — Zed's `toolchain::Select`. */
    var toolchainSelectorOpen by mutableStateOf(false)
    /** "Add Folder to Project" — Zed's `workspace::AddFolderToProject`. */
    var addFolderOpen by mutableStateOf(false)
    /** "Remove Folder from Project", listing the folders that can go. */
    var removeFolderOpen by mutableStateOf(false)
    /** Ctrl+G. A surface rather than a command: it answers for itself. */
    var goToLineOpen by mutableStateOf(false)
    var outlineOpen by mutableStateOf(false)
    /** The project symbol picker — Zed's `project_symbols::Toggle`. */
    var projectSymbolsOpen by mutableStateOf(false)
    /** The rename-symbol dialog, over the active editor. */
    var renameOpen by mutableStateOf(false)
    /** The branch picker — Zed's `git::Switch`, from the panel's branch button. */
    var branchPickerOpen by mutableStateOf(false)
    /** The stash picker — Zed's `git::ViewStash`, from the panel's Stash menu. */
    var stashPickerOpen by mutableStateOf(false)
    /** The task picker — Zed's `task::Spawn` — and what it was opened for. */
    var taskPicker by mutableStateOf<TaskPickerRequest?>(null)
    /** The recent-projects picker — Zed's `projects::OpenRecent`. */
    var recentOpen by mutableStateOf(false)
    /** The welcome screen — Zed's `zed::OpenOnboarding`. */
    var onboardingOpen by mutableStateOf(false)
    /** About, and the system specs it copies — Zed's `zed::About`. */
    var aboutOpen by mutableStateOf(false)
}

/**
 * Root of the IDE UI, in the spirit of Zed's workspace: a project panel, a tab
 * strip, an editor area and a status bar. Wide screens (tablets, unfolded
 * foldables) get a fixed sidebar; compact screens (phones, folded) get a
 * slimmer layout with the panel in a drawer.
 */
@Composable
fun WorkspaceScreen(
    settings: AppSettings,
    settingsPath: String?,
    onSettingsChanged: (AppSettings) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * A file or text another app handed the activity, to be imported —
     * see [IncomingFiles]. Cleared through [onIncomingHandled] once the
     * bytes are staged, so a rotation does not import it twice.
     */
    incoming: ImportRequest? = null,
    onIncomingHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    // Resolving the project root can write to disk (it seeds the sample on a
    // fresh install), so it happens off the main thread and the UI starts with
    // no project — which is also the state P3-4's project picker will use.
    var project by remember { mutableStateOf<ProjectSession?>(null) }
    val panes = remember { PaneGroupState() }
    /** Whether the window is wide enough for the docks to sit beside the editor — see the layout below. */
    var isWide by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Conflicts the user chose to live with, so the bar doesn't nag.
    val dismissedConflicts = remember { mutableStateOf(setOf<String>()) }
    // What the last "Mark resolved (stage)" said, for the banner that asked.
    var resolvedStageError by remember { mutableStateOf<String?>(null) }

    // Workspace shortcuts are matched in a *preview* pass at the root, so they
    // work wherever focus sits — including while the editor holds it. Editor
    // chords are never matched here, so they still reach EditorPane; terminal
    // chords are arbitrated by focus (see Keybindings.kt).
    val rootFocus = remember { FocusRequester() }

    // The terminal dock. Sessions survive the dock being hidden — a build
    // keeps running while you read code — but not a project switch, since a
    // shell sitting in a directory nobody has open is a trap.
    val terminals = remember(context) { TerminalSessions.of(context) }
    // What the next shell is born with. Written on every composition that
    // sees new settings, read only at spawn: a running shell keeps its own.
    SideEffect { terminals.spawn = settings.terminal }
    var terminalFocused by remember { mutableStateOf(false) }
    var dockHeight by remember { mutableStateOf(TerminalDockHeight) }
    val overlays = remember { WorkspaceOverlays() }
    /** Whether the project panel holds the keyboard — see [WorkspaceCommand.NewFile]. */
    var projectPanelFocused by remember { mutableStateOf(false) }
    /** Which grammar the language-server prompt is about (null = show the list). */
    var serverPromptGrammar by remember { mutableStateOf<String?>(null) }
    /** Why the last new-file create failed, if it did. */
    var newFileError by remember { mutableStateOf<String?>(null) }
    /** The picker opens straight into the clone form for Ctrl+Shift+G. */
    var pickerStartsInClone by remember { mutableStateOf(false) }
    /** Ctrl+Shift+E asked the panel to show the active file and take the keyboard. */
    var revealInPanel by remember { mutableStateOf(false) }
    /**
     * The find bar's current deployment, or null while it is closed. Every
     * Ctrl+F, Ctrl+H, toolbar tap or palette command while it is up makes a
     * fresh one — re-seeding, re-focusing or acting — as Zed's `Deploy` does.
     */
    var searchDeploy by remember { mutableStateOf<SearchDeploy?>(null) }
    /** The bar reports whether one of its fields holds the keyboard. */
    var searchBarFocused by remember { mutableStateOf(false) }
    /**
     * The Ctrl+Tab switcher: the tabs it is walking, most-recently-used first,
     * frozen for as long as the overlay is up. They are the *active pane's* —
     * Zed's switcher is a pane's, and the chord acts where the caret is.
     *
     * Frozen on purpose — Zed's switcher takes the activation history once,
     * when it opens (tab_switcher/src/tab_switcher.rs), so pressing Ctrl+Tab
     * three times walks three *different* tabs. Recomputing it per press would
     * make the list reshuffle under the highlight and the third press land
     * back where the first did.
     */
    var switcherTabs by remember { mutableStateOf<List<OpenFile>>(emptyList()) }
    var switcherIndex by remember { mutableIntStateOf(0) }
    /** An include glob the project panel asked project search to start from. */
    var projectSearchInclude by remember { mutableStateOf<String?>(null) }
    /**
     * What the right-hand dock is showing, if anything.
     *
     * One at a time, which is a dock's rule in Zed as well: several panels may
     * live in one, exactly one is active. It is also what stops three of them
     * sharing a 600dp screen and leaving the editor a character wide.
     *
     * What the preview previews is decided by whichever file is active, so
     * switching tabs switches it with them rather than leaving a rendered
     * README beside a Rust file.
     */
    val docks = remember { DockLayout() }
    /** Ctrl+Shift+F. The token is bumped to pull focus back to its query. */
    var projectSearchFocus by remember { mutableIntStateOf(0) }
    /** Ctrl+Shift+G, the same way: press it again to put focus back on the list. */
    var gitPanelFocus by remember { mutableIntStateOf(0) }
    var agentPanelFocus by remember { mutableIntStateOf(0) }
    /** Ctrl+Shift+B, the same way: press it again to put focus back on the filter. */
    var outlinePanelFocus by remember { mutableIntStateOf(0) }
    /**
     * Whether the agent composer holds the keyboard — Zed's `AgentPanel`
     * keymap context. It is what makes `Ctrl+N` start a thread there and a
     * file everywhere else, and what scopes the permission chords.
     */
    var agentComposerFocused by remember { mutableStateOf(false) }
    /** The diagnostics panel's keyboard, pulled back the same way. */
    var diagnosticsFocus by remember { mutableIntStateOf(0) }
    /**
     * Whether the git panel holds the keyboard. It is what lets Ctrl+G be
     * the panel's chord leader there and go-to-line everywhere else — the
     * root key pass below stands aside on it, the way Zed scopes its git
     * chords to the `GitPanel` context (default-linux.json:1060).
     */
    var gitPanelFocused by remember { mutableStateOf(false) }
    /**
     * The open picture's zoom and pan, held here so the palette's rows and
     * the root key pass reach the same numbers the pane's pinch does. One
     * per workspace: only the active tab is drawn, and the pane resets it
     * when the file changes.
     */
    val imageZoom = remember { ImageZoom() }
    /** A palette-run git command on its way to the panel — see [GitPanelRequest]. */
    var gitPanelRequest by remember { mutableStateOf<GitPanelRequest?>(null) }
    /**
     * Whether the dock is drawn over the whole work area rather than beside
     * the editor. Decided during layout, where the width is known, and read by
     * whatever opens a file *from* a dock — which has to hand the area back
     * when it is true, and must not when the editor is right there next to it.
     */
    var dockTookWorkArea by remember { mutableStateOf<DockSide?>(null) }
    /**
     * Which docks the last layout actually drew.
     *
     * Open and drawn are not the same thing — a screen that cannot hold both
     * docks draws one and leaves the other waiting — and the buttons have to
     * know the difference, or the one for a waiting panel closes it invisibly.
     */
    var drawnDocks by remember { mutableStateOf(emptySet<DockSide>()) }
    var settingsValid by remember { mutableStateOf(true) }
    // The session zoom over `buffer_font_size` — the editor's pinch and Zed's
    // `persist: false` chords. The terminal reads it too, so it lives here
    // rather than inside the editor pane.
    val bufferFontDelta by ThemeStore.bufferFontDelta.collectAsState()
    /** What the engine refused, if it refused the last write. */
    var settingsRefusal by remember { mutableStateOf<String?>(null) }
    /** The `after_delay` autosave's debounce; see [AutosaveTracker]. */
    val autosaveTracker = remember { AutosaveTracker() }

    /**
     * The chord state every key pass shares — the root's and the terminal's
     * — so a two-stroke binding is one chord wherever its strokes land.
     */
    val chords = remember { ChordDispatcher() }
    // The contexts the last stroke was resolved against, so the which-key
    // overlay lists the completions the *next* stroke will actually be
    // matched against rather than the ones the editor happens to have.
    var chordContexts by remember { mutableStateOf(emptyList<KeymapContext>()) }
    /**
     * The key event the pre-IME pass last took up, so the ordinary pass can
     * tell a chord it already refused from a fresh key — the same event
     * object travels through both passes when nothing consumed it. A plain
     * field: it lives for one event and nothing draws it.
     */
    val keySeenBeforeIme = remember { PreImeKey() }
    /** The user's keymap.json, next to settings.json; null where there is no settings file. */
    val tasksPath = remember(settingsPath) {
        settingsPath?.let { File(File(it).parentFile, "tasks.json").absolutePath }
    }
    val keymapPath = remember(settingsPath) {
        settingsPath?.let { File(File(it).parentFile, "keymap.json").absolutePath }
    }

    /**
     * Read the keymap through the engine — defaults, base keymap, the
     * user's file — and put it in force. Runs at start, when `base_keymap`
     * changes and when keymap.json is saved; Zed's file watcher, without
     * the watcher. Errors re-raise the strip even if it was dismissed: they
     * are new, or the save that was meant to fix them did not.
     */
    suspend fun reloadKeymap() {
        val (keymap, errors) = withContext(Dispatchers.IO) {
            Keymap.parse(CoreBridge.loadKeymap(DefaultKeymap.text()))
        }
        KeymapStore.install(keymap, errors)
    }
    LaunchedEffect(settings.baseKeymap) { reloadKeymap() }
    var projects by remember { mutableStateOf(emptyList<ProjectSummary>()) }
    var transferMessage by remember { mutableStateOf<String?>(null) }
    var transferError by remember { mutableStateOf<String?>(null) }
    /**
     * Projects the user has opened before, newest first — the engine's
     * recent list, which is what `projects::OpenRecent` fuzzy-matches over.
     */
    var recents by remember { mutableStateOf(emptyList<RecentProject>()) }
    /**
     * The root whose session has finished being restored, or null.
     *
     * The gate on writing: until the restore has run, what is on screen is
     * an empty workspace, and the debounced saver would happily write that
     * over the document being restored. It is also cleared the moment a
     * project starts closing, so the last frames of the outgoing project
     * cannot land under the incoming one's name.
     */
    var sessionReady by remember { mutableStateOf<String?>(null) }

    fun refreshProjects() {
        scope.launch {
            projects = withContext(Dispatchers.IO) { ProjectsRoot.list(context) }
        }
    }

    /**
     * A second tab on [tab]'s content — Zed's `clone_on_split`
     * (editor/src/items.rs `Item::clone_on_split`): the same buffer under a
     * new editor with its own caret, started where the original's is. A
     * picture clones as another view of the file. The keyed views — a
     * diff, the graph, the diagnostics, the review — cannot: two tabs on
     * one key would collide in every lookup, and Zed's `can_split` says no
     * for them too.
     */
    fun cloneTab(tab: OpenFile): OpenFile? {
        if (!tab.isReopenable) return null
        val source = tab.editor
        if (source == null) {
            return OpenFile(tab.path, editor = null, media = tab.media, absolutePath = tab.absolutePath)
        }
        val editor = EditorState(source.session, readOnly = source.isReadOnly)
        val opened = OpenFile(tab.path, editor, absolutePath = tab.absolutePath)
        scope.launch {
            NavEntry(tab.path, source.cursorRow, source.cursorCol, source.scrollY).restoreIn(opened)
        }
        return opened
    }

    /**
     * [openFile]'s body, awaitable. A session restore opens a pane's tabs in
     * the order they were saved, and an order needs each open to finish
     * before the next begins; everything else goes through [openFile], which
     * is this in a `launch`.
     *
     * Null when the file could not be opened at all.
     */
    suspend fun openFileInto(
        project: ProjectSession,
        path: String,
        into: Pane,
        preview: Boolean = false,
    ): OpenFile? {
        val files = into.files
        val existing = files.indexOfPath(path)
        if (existing >= 0) {
            files.select(existing)
            // Asking for a file permanently that is open as a preview keeps
            // it: Zed's `preview_item_id` is cleared by any permanent open.
            if (!preview) files.promote(path)
            return files.tabs[existing]
        }
        // Open in *another* pane already: Zed opens it again in this one,
        // a second view of the same buffer (the engine keys buffers by path,
        // file.rs:132-137, so the session is shared rather than reopened).
        val elsewhere = panes.tabForPath(path)
        if (elsewhere != null) {
            val opened = cloneTab(elsewhere) ?: return null
            files.open(opened, preview)
            opened.refreshLanguageSettings()
            return opened
        }
        val absolutePath = project.absolutePathOf(path) ?: return null
        // A picture never reaches the engine: opening one as text would put a
        // megabyte of mojibake in a CRDT and set tree-sitter on it.
        val media = MediaKind.of(path.substringAfterLast('/'))
        if (media != null) {
            val opened = OpenFile(path, editor = null, media = media, absolutePath = absolutePath)
            files.open(opened, preview)
            return opened
        }
        val session = withContext(Dispatchers.IO) { BufferSession.openFile(absolutePath) }
            ?: return null
        val opened = OpenFile(path, EditorState(session), absolutePath = absolutePath)
        files.open(opened, preview)
        opened.refreshLanguageSettings()
        return opened
    }

    fun openFile(
        project: ProjectSession,
        path: String,
        /**
         * Which of Zed's `preview_tabs` keys governs this open. The settings
         * decide whether it is *actually* a preview tab; the caller only says
         * where the request came from.
         */
        route: PreviewRoute = PreviewRoute.Permanent,
        /** Runs instead when the file could not be opened at all. */
        onFailed: (() -> Unit)? = null,
        /**
         * The pane to open in. The active one, as Zed opens into the active
         * pane (workspace.rs `open_path` → `active_pane`); a rename reopens
         * each tab in the pane it was in.
         */
        into: Pane = panes.active,
        /** Runs once the tab exists — how a search hit puts the caret on itself. */
        onOpened: (suspend (OpenFile) -> Unit)? = null,
    ) {
        // The two answers that need no I/O are given here rather than in a
        // coroutine: a tab that is already on the strip must be selected in
        // *this* frame, since callers go on to act on the active tab.
        val files = into.files
        val preview = settings.previewTabs.previews(route)
        val existing = files.indexOfPath(path)
        if (existing >= 0) {
            files.select(existing)
            // Asking for a file permanently that is open as a preview keeps
            // it: Zed's `preview_item_id` is cleared by any permanent open.
            if (!preview) files.promote(path)
            val tab = files.tabs[existing]
            if (onOpened != null) scope.launch { onOpened(tab) }
            return
        }
        val elsewhere = panes.tabForPath(path)
        if (elsewhere != null) {
            val opened = cloneTab(elsewhere) ?: return
            files.open(opened)
            scope.launch {
                opened.refreshLanguageSettings()
                onOpened?.invoke(opened)
            }
            return
        }
        // A file the engine will not give us — deleted under a history
        // entry, unreadable, a path outside the worktree. Said rather than
        // swallowed: a tab that simply never appears is the failure mode
        // this reports.
        fun failed() {
            Notifications.error("$path could not be opened", key = "open:$path")
            onFailed?.invoke()
        }
        scope.launch {
            val opened = openFileInto(project, path, into, preview)
            if (opened == null) failed() else onOpened?.invoke(opened)
        }
    }

    /**
     * Whether the workspace may split right now: the compact layout caps
     * the tree at two panes — a phone has the width for two columns of
     * code, not three — and says so rather than making a sliver.
     */
    fun canSplit(): Boolean {
        if (isWide || panes.panes.size < 2) return true
        Notifications.warn(
            "Two panes is the most a screen this narrow can hold; " +
                "unfold or widen it to split again.",
            key = "panes:split-cap",
        )
        return false
    }

    /**
     * Zed's `pane::Split*` in its default `ClonePane` mode
     * (`split_and_clone`, workspace.rs:6048-6080): a new pane beside the
     * active one holding a second view of its active item. An item that
     * cannot be cloned — a diff, the graph, the diagnostics, all keyed
     * views — is moved instead when the pane has more, and with nothing
     * else in the pane the split is an empty pane on the far side, so the
     * item ends up on the side asked for (`Pane::split`, pane.rs:2579-2590).
     * An empty pane splits empty.
     */
    fun splitActivePane(direction: SplitDirection): Boolean {
        if (!canSplit()) return false
        val pane = panes.active
        val item = pane.files.active
        val clone = item?.let { cloneTab(it) }
        when {
            item == null -> panes.activate(panes.split(pane, direction))
            clone != null -> {
                val fresh = panes.split(pane, direction)
                fresh.files.open(clone)
                panes.activate(fresh)
                scope.launch { clone.refreshLanguageSettings() }
            }
            pane.files.tabs.size > 1 -> panes.moveItem(item, pane, pane, direction)
            else -> panes.split(pane, direction.opposite)
        }
        panes.files.active?.editor?.requestFocus()
        return true
    }

    /**
     * The tab menu's "Split right/down" and a drop on an edge: *this* tab
     * into a new pane — Zed's `SplitMode::MovePane` (pane.rs:4365-4370),
     * which with a lone tab is the far-side empty pane described above.
     */
    fun splitWithTab(pane: Pane, index: Int, direction: SplitDirection) {
        if (!canSplit()) return
        val file = pane.files.tabs.getOrNull(index) ?: return
        if (pane.files.tabs.size > 1) {
            panes.moveItem(file, pane, pane, direction)
        } else {
            panes.split(pane, direction.opposite)
            panes.activate(pane)
        }
    }

    /** Every open buffer asks the engine for its settings again. */
    suspend fun refreshAllLanguageSettings() {
        for (tab in panes.allTabs) tab.refreshLanguageSettings()
    }

    // A settings write — from the screen, from the file's own tab — may
    // have changed any tab's tab width or wrap; every tab re-resolves.
    LaunchedEffect(settings) { refreshAllLanguageSettings() }
    // The agent's background watcher — the one that notifies while the panel
    // is not on screen — runs for the life of the process, gated by Zed's
    // `agent.notify_when_agent_waiting`, kept current from the settings.
    LaunchedEffect(Unit) { if (isAgentPanelSupported) AgentSessions.watch(context) }
    LaunchedEffect(settings.notifyWhenAgentWaiting) {
        AgentSessions.notifyMode = settings.notifyWhenAgentWaiting
    }

    /**
     * Open a multibuffer tab — Zed's `MultiBuffer`, which is the surface its
     * project search, find-all-references and project diagnostics all deploy
     * into.
     *
     * [key] is the tab's identity, so asking twice for the same search reaches
     * the tab that is already there. [focus] names a file row to land the caret
     * on, which is how a tapped search hit arrives at itself inside the
     * composition. [refresh] recomposes it from the excerpts given now — a
     * multibuffer is a snapshot of what its producer had found, so the button
     * that opened it is also the button that brings it up to date.
     */
    fun openMultibuffer(
        key: String,
        title: String,
        kind: String,
        excerpts: List<ExcerptRequest>,
        focus: Pair<String, Int>? = null,
        refresh: Boolean = false,
    ) {
        val open = project ?: return
        if (excerpts.isEmpty()) return
        if (refresh) {
            // Nothing is lost: the edits are in the *files*, which the engine
            // keeps open while they are dirty, not in the composition.
            panes.files.indexOfPath(key).takeIf { it >= 0 }?.let(panes.files::close)
        }

        /** Put the caret on [row] of [path] inside the composition. */
        suspend fun reveal(file: OpenFile) {
            val (path, row) = focus ?: return
            val session = file.multibuffer ?: return
            val editor = file.editor ?: return
            val excerpt = session.info.excerpts.firstOrNull {
                it.path == path && row in it.fileStartRow..it.fileEndRow
            } ?: return
            val target = (excerpt.firstRow + (row - excerpt.fileStartRow))
                .coerceIn(0, (editor.lineCount - 1).coerceAtLeast(0))
            editor.selectRange(EditorState.SelectionRange(target, 0, target, 0))
            // Two frames for the same reason `revealProjectSearchMatch` waits:
            // a pane composed this frame has no measured viewport to scroll in.
            withFrameNanos { }
            withFrameNanos { }
            editor.ensureCursorVisible()
        }

        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
            val tab = panes.files.tabs[existing]
            scope.launch { reveal(tab) }
        } else {
            scope.launch {
                val session = withContext(Dispatchers.IO) {
                    MultiBufferSession.open(title, kind, open.rootPath, excerpts)
                } ?: return@launch
                val opened = OpenFile(
                    path = key,
                    editor = EditorState(BufferSession.adopt(session.bufferId)),
                    multibuffer = session,
                )
                panes.files.open(opened)
                reveal(opened)
            }
        }
        // A compact screen gave the panel the whole work area; opening a tab
        // has to hand it — and the keyboard — back, exactly as [openMatch]
        // does. Spelled out rather than through [handOverWorkArea], which is
        // declared further down than this is used.
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * Project-search results as a multibuffer — which in Zed *is* the project
     * search: `project_search::Deploy` opens a results editor over a
     * multibuffer of every hit, editable in place
     * (search/src/project_search.rs).
     *
     * One tab per query, so re-running the same search reuses it. The excerpts
     * are a snapshot of what the search had found when this was asked, exactly
     * as Zed's results editor is a snapshot of the last search.
     */
    fun openSearchMultibuffer(
        query: String,
        results: List<ProjectSearchFile>,
        focus: Pair<String, Int>?,
    ) {
        // A hit reveals itself in whatever is already open; the chip and
        // Alt+Enter — which pass no hit — recompose from the results as they
        // stand, so a search that has since finished is not shown half-done.
        val excerpts = results.flatMap { file ->
            file.matches.map { match ->
                ExcerptRequest(path = file.path, row = (match.line - 1).coerceAtLeast(0))
            }
        }
        openMultibuffer(
            key = "multibuffer:search:$query",
            title = "Search: $query",
            kind = "search",
            excerpts = excerpts,
            focus = focus,
            refresh = focus == null,
        )
    }

    /**
     * The project's problems as an editable multibuffer — which is what Zed's
     * project diagnostics are: `diagnostics::Deploy` opens an editor over a
     * multibuffer with one excerpt per diagnostic group (crates/diagnostics).
     *
     * The list stays, because on a phone a grouped, collapsible list is the
     * better way to read fifty problems; this is the surface for fixing them.
     */
    fun openDiagnosticsMultibuffer(listed: List<FileDiagnosticRows>) {
        val excerpts = listed.flatMap { file ->
            file.rows.map { row ->
                ExcerptRequest(
                    path = file.path,
                    row = row.row.coerceAtLeast(0),
                    endRow = row.endRow.coerceAtLeast(row.row),
                )
            }
        }
        openMultibuffer(
            key = "multibuffer:diagnostics",
            title = "Project diagnostics",
            kind = "diagnostics",
            excerpts = excerpts,
            // Problems come and go as the server rechecks; the button is also
            // how the multibuffer catches up with them.
            refresh = true,
        )
    }

    /**
     * Every answer to `FindAllReferences` in one editable document — which is
     * what Zed's `FindAllReferences` opens outright (editor/src/editor.rs
     * `find_all_references` builds a multibuffer of the ranges).
     *
     * The list popup is still what the chord raises first, because on a phone
     * a popup beats a whole tab for a two-hit answer; this is its "all of
     * them" row and its Alt+Enter.
     */
    fun openReferencesMultibuffer(targets: List<ReferenceTarget>) {
        val open = project ?: return
        val root = open.rootPath
        val excerpts = targets.mapNotNull { target ->
            // A target outside the project — a header out of /usr/include —
            // has no project-relative name; the engine takes the absolute one
            // and the header shows that instead.
            val relative = target.path.removePrefix("$root/")
            ExcerptRequest(
                path = relative,
                absPath = if (target.path.startsWith("/")) target.path else null,
                row = target.row.coerceAtLeast(0),
                endRow = target.endRow.coerceAtLeast(target.row),
            )
        }
        val name = panes.files.active?.editor?.wordUnderCaret()
        openMultibuffer(
            key = "multibuffer:references:${targets.firstOrNull()?.path}:" +
                "${targets.firstOrNull()?.row}",
            title = if (name.isNullOrEmpty()) "References" else "References to $name",
            kind = "references",
            excerpts = excerpts,
        )
    }

    /**
     * Zed's `editor::OpenExcerpts` (alt-enter): leave the multibuffer for the
     * file the caret's excerpt came from, with the caret on the same row.
     * False when the open tab is not a multibuffer, so the chord falls through
     * to the editor beneath.
     */
    fun openExcerptAtCaret(): Boolean {
        val open = project ?: return false
        val tab = panes.files.active ?: return false
        val session = tab.multibuffer ?: return false
        val at = session.locate(tab.editor?.cursorRow ?: 0) ?: return false
        openFile(open, at.path) { file ->
            val editor = file.editor ?: return@openFile
            val row = at.row.coerceIn(0, (editor.lineCount - 1).coerceAtLeast(0))
            editor.selectRange(EditorState.SelectionRange(row, 0, row, 0))
            withFrameNanos { }
            withFrameNanos { }
            editor.ensureCursorVisible()
        }
        return true
    }

    /**
     * A path the panel deleted. The engine keys buffers by path, so a tab left
     * open on it would keep a live buffer whose next save recreates the file
     * the user just deleted.
     */
    fun closeTabsUnder(path: String) {
        for (pane in panes.panes) {
            val files = pane.files
            for (index in files.tabs.indices.reversed()) {
                val tab = files.tabs[index]
                // A diff of a file that has just been deleted is a diff of
                // nothing; its key names the same path.
                val diffed = tab.diff?.path
                val matches = { candidate: String -> candidate == path || candidate.startsWith("$path/") }
                if (matches(tab.path) || (diffed != null && matches(diffed))) files.close(index)
            }
        }
    }

    /**
     * A path the panel renamed or moved: the tab has to be reopened at the new
     * path, or saving writes back to the old one and the user ends up with
     * both panes.files.
     *
     * **Unsaved edits travel with the file.** Closing the tab is what makes
     * the engine let the buffer go, and `close` is the unconditional kind — it
     * drops whatever was unsaved. Asking here would be too late and asking
     * before the rename would be asking about a file that still had its old
     * name, so the edits are written to the *new* path first. Saving instead
     * would write them back to the old name and recreate the file the user
     * just renamed away.
     */
    /** [retitleTabs] for one pane: its tabs reopen in it, not in the active one. */
    fun retitleTabsIn(pane: Pane, open: ProjectSession, from: String, to: String) {
        val files = pane.files
        // A diff tab is about the old name; it is closed rather than moved,
        // since reopening it is one tap and a stale patch is a lie.
        for (index in files.tabs.indices.reversed()) {
            val diffed = files.tabs[index].diff?.path ?: continue
            if (diffed == from || diffed.startsWith("$from/")) files.close(index)
        }
        val moved = files.tabs.filter {
            it.editor != null && (it.path == from || it.path.startsWith("$from/"))
        }
        if (moved.isEmpty()) return
        val wasActive = files.active?.path
        val movedPaths = moved.map { it.path }
        scope.launch {
            for (tab in moved) {
                // Written once, by whichever pane's tab gets here first;
                // a shared buffer is the same text either way.
                if (!tab.isDirty) continue
                val destination = open.absolutePathOf(to + tab.path.removePrefix(from))
                val id = tab.session?.id ?: continue
                val text = withContext(Dispatchers.IO) { CoreBridge.bufferText(id) }
                if (destination != null && text != null) {
                    withContext(Dispatchers.IO) { File(destination).writeText(text) }
                }
            }
            for (path in movedPaths) files.indexOfPath(path).takeIf { it >= 0 }?.let(files::close)
            // The pane may have gone with its last tab; then the reopen
            // lands in the active pane, as any open does.
            val target = panes.paneById(pane.id) ?: panes.active
            for (path in movedPaths) openFile(open, to + path.removePrefix(from), into = target)
            if (wasActive != null && wasActive !in movedPaths) {
                files.indexOfPath(wasActive).takeIf { it >= 0 }?.let(files::select)
            }
        }
    }

    fun retitleTabs(from: String, to: String) {
        val open = project ?: return
        for (pane in panes.panes) retitleTabsIn(pane, open, from, to)
    }

    /**
     * Switch the workspace to another project: close every tab and the old
     * worktree first, so the engine isn't left scanning a project nobody is
     * looking at.
     */
    /**
     * The workspace as it is now, written under [root] — Zed's
     * `Workspace::serialize_workspace`.
     *
     * The document is built on the calling thread, which must be the main
     * one (it reads Compose state); only the write goes to the IO pool.
     */
    suspend fun writeSession(root: String) {
        val document = captureSession(
            root = root,
            panes = panes,
            docks = docks,
            terminals = terminals.sessions,
            terminalOpen = terminals.isOpen,
            terminalHeight = dockHeight,
        )
        val json = document.toJson()
        withContext(Dispatchers.IO) { CoreBridge.sessionSave(root, json) }
    }

    /**
     * Put [open]'s saved workspace back — Zed's `load_workspace`. Returns
     * whether anything came back, so a caller can fall back to its own idea
     * of what to open.
     *
     * `restore_on_startup: "last_workspace"` reopens the project without its
     * contents and `"none"` never gets here at all; only `"last_session"`
     * restores the panes, tabs, docks and terminals.
     */
    suspend fun restoreSession(open: ProjectSession): Boolean {
        if (!settings.restoreOnStartup.restoresWorkspace) return false
        val json = withContext(Dispatchers.IO) { CoreBridge.sessionLoad(open.rootPath) }
        val document = WorkspaceSession.parse(json) ?: return false
        applySession(
            session = document,
            panes = panes,
            docks = docks,
            settings = settings,
            terminals = terminals,
            onTerminalHeight = { dockHeight = it },
            openInto = { path, pane, preview -> openFileInto(open, path, pane, preview) },
        )
        return panes.allTabs.isNotEmpty()
    }

    fun openProject(path: String, startupFile: String? = null, startupFiles: List<String> = emptyList()) {
        scope.launch {
            // What is on screen belongs to the project being left, and this
            // is the last moment it can be written down.
            project?.rootPath?.takeIf { it == sessionReady }?.let { writeSession(it) }
            sessionReady = null
            // Every pane's tabs go, and so do the panes: the layout was
            // this project's.
            panes.reset()
            terminals.closeAll()
            // The tasks that ran were this project's; rerunning one in the
            // next would run it in the wrong directory. Each pane's closed
            // and jump history goes with `panes.reset()` above.
            TaskRuns.clear()
            dismissedConflicts.value = emptySet()
            // A git command still waiting on the old project's first scan is
            // about that project; it must not survive into this one. The
            // panel's own stamp check is the second lock on the same door.
            gitPanelRequest = null
            project?.close()
            val opened = ProjectSession(path)
            project = opened
            withContext(Dispatchers.IO) {
                ProjectsRoot.setLastOpened(context, File(path).name)
                recents = RecentProject.parseList(CoreBridge.noteProjectOpened(path))
            }
            restoreSession(opened)
            // Only after the restore: until it has run, what is on screen is
            // an empty workspace, and saving that would erase what is being
            // restored.
            sessionReady = path
            if (startupFile != null) openFile(opened, startupFile)
            for (file in startupFiles) openFile(opened, file)
            refreshProjects()
        }
    }

    /**
     * Zed's `workspace::CloseWindow` (default-linux.json:25).
     *
     * One window is all this platform gives, so closing it is closing what
     * is *in* it: the session is written down first, then every tab, the
     * panes, the shells and the worktree go, and the picker is what is left
     * — which is also where `restore_on_startup: "none"` starts.
     */
    fun closeWorkspace() {
        scope.launch {
            project?.rootPath?.takeIf { it == sessionReady }?.let { writeSession(it) }
            sessionReady = null
            panes.reset()
            terminals.closeAll()
            TaskRuns.clear()
            dismissedConflicts.value = emptySet()
            gitPanelRequest = null
            project?.close()
            project = null
            refreshProjects()
            transferError = null
            pickerStartsInClone = false
            overlays.pickerOpen = true
        }
    }

    // ---- Files arriving from other apps ------------------------------------
    //
    // A share or open-with is an *import*: the engine works on real paths
    // (ProjectsRoot), so the bytes are staged into cache first and then
    // placed in a project — the open one, after asking where, or Scratch.

    /** Staged files waiting for the "Add to project?" answer. */
    var pendingImport by remember { mutableStateOf<List<StagedFile>?>(null) }

    /**
     * Put the staged files into [root] — a file at [destination], or every
     * file into the folder [destination] names — and open what landed.
     * [openIn] is how the tabs are opened: through [openFile] on the current
     * project, or through [openProject] when the project is Scratch.
     */
    fun placeImport(
        root: File,
        destination: String,
        files: List<StagedFile>,
        openIn: (List<String>) -> Unit,
    ) {
        scope.launch {
            val single = files.singleOrNull()
            val landed = withContext(Dispatchers.IO) {
                files.map { staged ->
                    val path = if (single != null) {
                        destination
                    } else {
                        ProjectFiles.join(destination.trim().trim('/'), staged.name)
                    }
                    IncomingFiles.place(root, path, staged)
                }
            }
            val failed = landed.firstOrNull { it.isFailure }
            if (failed != null) {
                // A toast rather than a dialog: some of the files may have
                // landed, and a modal over the ones that did would be a
                // demand for acknowledgement of a result the user can see.
                Notifications.error(
                    failed.exceptionOrNull()?.message ?: "The file could not be added",
                    key = "import",
                )
            }
            val paths = landed.mapNotNull { it.getOrNull() }
            if (paths.isNotEmpty()) openIn(paths)
            rootFocus.requestFocus()
        }
    }

    /** The Scratch route: a project of its own, opened with the files in it. */
    fun importToScratch(files: List<StagedFile>) {
        scope.launch {
            val root = withContext(Dispatchers.IO) { ProjectsRoot.scratch(context) }
            placeImport(root, "", files) { paths ->
                if (project?.rootPath == root.absolutePath) {
                    project?.let { open -> paths.forEach { openFile(open, it) } }
                } else {
                    openProject(root.absolutePath, startupFiles = paths)
                }
            }
        }
    }

    LaunchedEffect(incoming) {
        val request = incoming ?: return@LaunchedEffect
        // Staged before anything else: the read grant on a content URI is
        // the sender's to revoke, and asking a question first would spend it.
        val staged = withContext(Dispatchers.IO) { IncomingFiles.stage(context, request) }
        val files = staged.getOrNull()
        if (files == null) {
            Notifications.error(
                staged.exceptionOrNull()?.message ?: "The shared file could not be read",
                key = "import",
            )
        } else {
            // The startup effect is still resolving the project on a cold
            // start through a share; wait for it rather than racing it.
            // Nothing to wait for means nothing is open, and Scratch is
            // where the file goes.
            val open = withTimeoutOrNull(5_000L) { snapshotFlow { project }.filterNotNull().first() }
            if (open == null) importToScratch(files) else pendingImport = files
        }
        // Last, not first: this nulls the key this effect is launched on,
        // and cancelling the effect above the dialog would lose the share.
        onIncomingHandled()
    }

    // The engine runs Debian's git through proot for status, and cannot guess
    // where either lives. Keyed on the installer's state, not on `Unit`: the
    // userland can be installed and removed while the app runs, and told once
    // at startup the engine kept pointing at whatever was true then. Install
    // Debian and git status stayed silently empty until the next launch;
    // remove it and the engine went on running a git that was no longer there.
    //
    // This runs more than once per process — the installer's state is
    // re-observed as the workspace recomposes, twice in one process just from
    // launching and opening the terminal — so the engine's `set_userland` is
    // idempotent on purpose (guest.rs). Do not "optimise" that away: an
    // earlier version restarted the askpass server on every repeat, and the
    // old server's Drop took the new one's helper script with it, leaving
    // GIT_ASKPASS pointing at nothing and credential prompts unable to reach
    // the dialog.
    LaunchedEffect(UserlandInstaller.state) {
        withContext(Dispatchers.IO) { syncUserlandWithEngine(context) }
    }

    // The panel moves the caret of the buffer it was opened on; if that tab
    // closes underneath it there is nothing left for it to move.
    LaunchedEffect(panes.files.active) {
        if (panes.files.active?.editor == null) {
            overlays.goToLineOpen = false
            overlays.outlineOpen = false
        }
        // A picture has no focusable of its own, and the editor that had the
        // keyboard has just left the composition — Compose hands focus
        // nowhere, so the zoom chords would reach no one. A sound or video
        // tab asks for the keys itself (MediaPane.kt).
        if (panes.files.active?.media == MediaKind.Image) rootFocus.requestFocus()
    }

    LaunchedEffect(Unit) {
        refreshProjects()
        withContext(Dispatchers.IO) {
            recents = RecentProject.parseList(CoreBridge.recentProjects())
        }
        // Zed's `restore_on_startup: "none"`: nothing comes back, so there
        // is no project to open and the picker is what the app starts on.
        if (!settings.restoreOnStartup.reopensProject) {
            overlays.pickerOpen = true
            return@LaunchedEffect
        }
        val root = withContext(Dispatchers.IO) { ProjectsRoot.defaultProject(context) }
        val opened = ProjectSession(root)
        project = opened
        withContext(Dispatchers.IO) {
            ProjectsRoot.setLastOpened(context, File(root).name)
            recents = RecentProject.parseList(CoreBridge.noteProjectOpened(root))
        }
        refreshProjects()
        val restored = restoreSession(opened)
        sessionReady = root
        // Only meaningful for the seeded sample, and only when nothing came
        // back: a restored workspace has already said which files were open.
        if (!restored && File(root, STARTUP_FILE).isFile) openFile(opened, STARTUP_FILE)
    }
    DisposableEffect(Unit) {
        onDispose { project?.close() }
    }

    // The open project's folders — Zed's worktrees. Polled off the project's
    // own version counter, which bumps when one is added or removed, so the
    // palette and the dialogs never read a list the panel has moved on from.
    var folders by remember(project) { mutableStateOf(emptyList<ProjectWorktree>()) }
    ResumedEffect(project) {
        val open = project ?: return@ResumedEffect
        var seen = -1L
        while (true) {
            val version = withContext(Dispatchers.Default) { open.version }
            if (version != seen) {
                seen = version
                val read = withContext(Dispatchers.Default) { open.worktrees() }
                if (read != folders) folders = read
            }
            delay(FOLDERS_POLL_MS)
        }
    }

    // The project's toolchains in force, one per language — what Zed's
    // `ActiveToolchain` status item reads. Loaded once per project and again
    // after a pick; nothing else can change them, so there is nothing to poll.
    var activeToolchains by remember(project) { mutableStateOf(emptyList<Toolchain>()) }
    LaunchedEffect(project) {
        val open = project ?: return@LaunchedEffect
        activeToolchains = withContext(Dispatchers.IO) { Toolchains.active(open.id) }
    }

    /**
     * Add [path] — already a real directory in app storage — to the open
     * project, and let the panel notice. Zed's `AddFolderToProject`.
     */
    fun addFolder(path: String) {
        val open = project ?: return
        scope.launch {
            when (val result = withContext(Dispatchers.IO) { open.addWorktree(path) }) {
                is AddFolderResult.Failed -> transferError = result.reason
                is AddFolderResult.Added -> {
                    overlays.addFolderOpen = false
                    folders = withContext(Dispatchers.Default) { open.worktrees() }
                }
            }
        }
    }

    // ---- Workspace persistence ---------------------------------------------
    //
    // Zed serializes a workspace whenever something structural happens to it
    // — an item added, removed or activated, a pane split or closed, a dock
    // toggled or resized — and coalesces the noisy ones. Here the whole
    // document *is* the trigger: `captureSession` reads exactly the snapshot
    // state those events change, so watching it as a flow catches every one
    // of them, and the caret (which moves per keystroke) is what the
    // debounce is for.
    LaunchedEffect(project, sessionReady) {
        val open = project ?: return@LaunchedEffect
        if (sessionReady != open.rootPath) return@LaunchedEffect
        snapshotFlow {
            captureSession(
                root = open.rootPath,
                panes = panes,
                docks = docks,
                terminals = terminals.sessions,
                terminalOpen = terminals.isOpen,
                terminalHeight = dockHeight,
            )
        }.collectLatest { document ->
            // `collectLatest` cancels this on the next change, which is the
            // debounce: a burst of typing writes once, when it stops.
            delay(SESSION_SAVE_DEBOUNCE_MS)
            val json = document.toJson()
            withContext(Dispatchers.IO) { CoreBridge.sessionSave(open.rootPath, json) }
        }
    }

    // The debounce is a second long and the app may not have one: Android
    // stops an activity and can kill the process straight afterwards, which
    // is where Zed serializes on window close.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val open = project ?: return@LifecycleEventEffect
        if (sessionReady == open.rootPath) scope.launch { writeSession(open.rootPath) }
    }

    // SAF pickers. Import copies a folder in; export copies a project out.
    // Neither can open in place — see ProjectsRoot for why.
    // Set while the folder picker is being used for `AddFolderToProject`
    // rather than for opening a project: the copy is the same either way, only
    // what happens to it afterwards differs.
    var importAsFolder by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val asFolder = importAsFolder
        importAsFolder = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferError = null
            transferMessage = "Importing…"
            val result = withContext(Dispatchers.IO) {
                SafTransfer.importAsProject(context, uri) { progress ->
                    transferMessage = "Importing ${progress.files} files… ${progress.currentName}"
                }
            }
            transferMessage = null
            when (result) {
                is SafTransfer.Result.Imported -> {
                    refreshProjects()
                    if (asFolder) {
                        addFolder(result.project.absolutePath)
                    } else {
                        openProject(result.project.absolutePath)
                        overlays.pickerOpen = false
                    }
                }
                is SafTransfer.Result.Failed -> {
                    transferError = result.message
                    Notifications.error(result.message, key = "import")
                }
                else -> Unit
            }
        }
    }

    var exportTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferError = null
            transferMessage = "Exporting…"
            val result = withContext(Dispatchers.IO) {
                SafTransfer.exportProject(context, File(target.path), uri) { progress ->
                    transferMessage = "Exporting ${progress.files} files… ${progress.currentName}"
                }
            }
            transferMessage = null
            // Also as a toast: an export is minutes over a big project, and
            // the picker it reports into is a screen people close and walk
            // away from. The picker keeps its own copy for whoever stayed.
            when (result) {
                is SafTransfer.Result.Exported -> {
                    transferError = "Exported ${result.files} files to the chosen folder"
                    Notifications.info(
                        "Exported ${result.files} files from ${target.name} to the chosen folder",
                        key = "export",
                    )
                }
                is SafTransfer.Result.Failed -> {
                    transferError = result.message
                    Notifications.error(result.message, key = "export")
                }
                else -> Unit
            }
        }
    }

    // Zed's `tabs.activate_on_close` and `max_tabs` live on the tab model,
    // which outlives any one settings value — so they are pushed rather than
    // read, on every settings change and on the first composition. Onto
    // *every* pane: each one keeps its own strip, and Zed's cap is per pane.
    SideEffect {
        for (pane in panes.panes) {
            pane.files.activateOnClose = settings.tabs.activateOnClose
            pane.files.maxTabs = settings.maxTabs
        }
    }

    // One loop for every tab's status. A buffer whose file changed underneath
    // it while *clean* is reloaded without asking: there are no local edits to
    /**
     * Whether the project's own settings file parsed, said once.
     *
     * Keyed, because it is asked twice — by the save that wrote the file and
     * by the poller that noticed it move — and because the answer changing to
     * "it parses now" has to take the toast away rather than leave a stale
     * complaint on screen.
     */
    fun reportLocalSettings(error: String?) {
        if (error == null) {
            Notifications.dismissKey(LOCAL_SETTINGS_NOTIFICATION)
        } else {
            Notifications.error(
                "$LOCAL_SETTINGS_PATH is not in effect: $error",
                key = LOCAL_SETTINGS_NOTIFICATION,
            )
        }
    }

    // lose, and silently showing stale text would be the worse behaviour.
    // Restarting on every return to the foreground is exactly right here:
    // the background is where files change underneath tabs.
    // Keyed on `close_on_file_delete` as well, so flipping it in settings.json
    // takes effect on the next tick rather than on the next launch.
    ResumedEffect(panes, settings.closeOnFileDelete) {
        var settingsVersionSeen = -1L
        while (true) {
            for (pane in panes.panes) pane.files.refreshStatuses()
            // A preview tab stops being provisional the moment it is edited —
            // Zed clears `preview_item_id` on the buffer's first change. Done
            // here rather than in the editor so no per-keystroke path has to
            // know about tabs at all; the cost is that it lands within one
            // poll of the keystroke, which is invisible. Per pane, because
            // each pane has a preview slot of its own.
            for (pane in panes.panes) {
                for (tab in pane.files.tabs) {
                    if (tab.isPreview && tab.isDirty) pane.files.promote(tab.path)
                }
            }
            for (tab in panes.allTabs) {
                // A multibuffer's composed buffer has no file of its own; what
                // it watches is the files behind it moving — someone editing
                // one in its own tab, or a reload — which the engine answers
                // by recomposing. The version check is a single load, so this
                // is as cheap as the reload probe beside it.
                val multibuffer = tab.multibuffer
                if (multibuffer != null) {
                    val moved = withContext(Dispatchers.IO) { multibuffer.sync() }
                    if (moved) tab.editor?.refreshLineCount()
                    tab.refreshStatus()
                    continue
                }
                // A file split into two panes: the pane that did not type
                // learns the buffer moved here (one field compared, no
                // bridge call) and re-measures.
                tab.editor?.resyncIfBufferMoved()
                if (tab.hasDiskChange && !tab.isDirty) {
                    withContext(Dispatchers.IO) { tab.session?.reload() }
                    tab.refreshStatus()
                    dismissedConflicts.value -= tab.path
                }
            }
            // Zed's `close_on_file_delete` (workspace/src/item.rs:894-896):
            // a tab whose file has gone closes itself — but never one with
            // unsaved edits, which is the same guard Zed keeps, or the
            // setting would silently throw work away.
            if (settings.closeOnFileDelete) {
                for (pane in panes.panes) {
                    val files = pane.files
                    for (index in files.tabs.indices.reversed()) {
                        val tab = files.tabs[index]
                        if (tab.isDeleted && !tab.isDirty) files.close(index)
                    }
                }
            }
            // The project's `.zed/settings.json` moved on disk — the watcher
            // re-read it and bumped this. One long per tick; the re-resolve
            // happens only when it moved.
            val open = project
            if (open != null) {
                val version = withContext(Dispatchers.Default) { CoreBridge.projectSettingsVersion(open.id) }
                if (version != settingsVersionSeen) {
                    settingsVersionSeen = version
                    val error = withContext(Dispatchers.IO) { CoreBridge.projectSettingsError(open.id) }
                    reportLocalSettings(error)
                    refreshAllLanguageSettings()
                }
            }
            delay(STATUS_POLL_MS)
        }
    }

    fun resyncBuffers(bufferIds: List<Long>) {
        for (tab in panes.allTabs) {
            val id = tab.session?.id
            if (id != null && id in bufferIds) {
                tab.editor?.noteExternalEdit()
            }
            tab.refreshStatus()
        }
    }

    /**
     * A workspace edit — a rename, a quick fix, a formatting — landed
     * engine-side, and the receipt names every file it touched. The engine
     * changed those buffers *underneath* their editors, so each open one is
     * resynced ([EditorState.noteExternalEdit]), and every tab's dirty dot
     * re-read: an applied edit makes clean buffers dirty.
     */
    fun resyncAfterWorkspaceEdit(receipt: EditReceipt) {
        if (receipt.files.isEmpty()) return
        resyncBuffers(receipt.files.mapNotNull { it.bufferId })
    }

    /**
     * `format_on_save`, before the write — Zed's `Item::save` with
     * `SaveOptions { format: true }` (workspace/src/pane.rs:2539-2557): the
     * `code_actions_on_format` first, then the formatter, each landing in the
     * buffer through the engine so the editor resyncs and the undo history
     * keeps them as steps of their own. A formatter that has nothing to say
     * says nothing; one that fails says so in the notice bar, and the save
     * goes ahead regardless — a file that could not be formatted is still a
     * file worth keeping.
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
            // The language server: `auto`, `language_server`, or a code
            // action kind, which the engine folds into the request above.
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
     * `remove_trailing_whitespace_on_save` and `ensure_final_newline_on_save`
     * — both default true, as in Zed — applied after the formatter and before
     * the write, so a formatter that reintroduced a trailing space does not
     * win. The engine does the work and lands it as one undoable edit
     * (`Engine::clean_buffer_on_save`); the editor resyncs when it changed.
     *
     * Skipped for the delayed autosave along with the formatter, as Zed skips
     * its whole format step there (`SaveOptions { format: false }`,
     * workspace/src/pane.rs:2545-2548): rewriting the line under the user's
     * fingers every second of idleness is not help.
     */
    suspend fun cleanBeforeSave(file: OpenFile) {
        val editor = file.editor ?: return
        if (!file.languageSettings.cleansOnSave) return
        val id = editor.session.id
        val changed = withContext(Dispatchers.IO) { CoreBridge.cleanBufferOnSave(id) }
        if (changed) {
            editor.noteExternalEdit()
            file.refreshStatus()
        }
    }

    /**
     * Write a tab to disk, formatting first when its settings say so, and
     * return once it is there — for a caller with something to do *after*
     * the file is on disk, which the task runner is: a test that runs
     * before its edit is written tests the wrong code. [format] is off for
     * the delayed autosave, as Zed turns it off there (pane.rs:2545-2548):
     * a formatter running under the user's fingers every second of idleness
     * is not help.
     */
    suspend fun saveNow(file: OpenFile, format: Boolean = true) {
        // Ctrl+S over a multibuffer writes every file in it — Zed's SaveAll,
        // which is what its multibuffer save does (workspace.rs
        // `save_all_internal`). The composed buffer has no file to save to.
        val multibuffer = file.multibuffer
        if (multibuffer != null) {
            withContext(Dispatchers.IO) { multibuffer.saveAll() }
            // A file that could not be written stays dirty, and its dot stays
            // lit in the sticky header — the same thing a failed single-file
            // save does below.
            file.refreshStatus()
            return
        }
        val open = file.session ?: return
        if (file.isReadOnly) return
        run {
            if (format) {
                formatBeforeSave(file)
                cleanBeforeSave(file)
            }
            // The engine's answer was thrown away for a long time, and a
            // write that failed — a read-only mount, a full disk, a file
            // deleted under the tab — looked exactly like one that worked,
            // right down to the dirty dot clearing. It says so now, and the
            // dot stays where refreshStatus puts it.
            val written = withContext(Dispatchers.IO) { open.save() }
            // Only for a tab that has a file: the engine answers -1 for a
            // buffer with nothing to write to, and the bundled-text tabs are
            // exactly that. They are read-only and never get here, and this
            // is the guard that keeps it true if one ever stops being.
            if (!written && file.absolutePath != null) {
                Notifications.error(
                    "${file.name} could not be saved — the file may be read-only or gone.",
                    key = "save:${file.path}",
                )
            }
            autosaveTracker.saved(file.path)
            file.refreshStatus()
            dismissedConflicts.value -= file.path
            // Saving the settings tab *is* the reload: the engine reads the
            // file fresh on every settings() call, so re-parsing here applies
            // the edit everywhere at the only moment the file can change from
            // inside the app — Zed's file watcher, without the watcher.
            if (file.path == settingsPath) {
                settingsValid = withContext(Dispatchers.IO) { CoreBridge.settingsAreValid() }
                onSettingsChanged(withContext(Dispatchers.IO) { AppSettings.load() })
            }
            // The project's own file, likewise: the watcher would get there a
            // tick later, and the parse error — if there is one — belongs on
            // screen now, next to the text that caused it.
            if (file.path == LOCAL_SETTINGS_PATH) {
                val open = project ?: return@run
                val error = withContext(Dispatchers.IO) {
                    CoreBridge.reloadProjectSettings(open.id)
                    CoreBridge.projectSettingsError(open.id)
                }
                reportLocalSettings(error)
                refreshAllLanguageSettings()
            }
            // And the keymap's save is its reload, for the same reason.
            if (file.path == keymapPath) reloadKeymap()
        }
    }

    /** [saveNow], fire and forget — what a chord or a button wants. */
    fun save(file: OpenFile, format: Boolean = true) {
        scope.launch { saveNow(file, format) }
    }

    // `"autosave": {"after_delay": …}`: the debounce is read off the
    // buffers' version counters by a poll of its own — see AutosaveTracker.
    // Restarted on every settings change, so a delay edited in the file is
    // the delay in force.
    ResumedEffect(panes, settings.autosave) {
        val autosave = settings.autosave as? Autosave.AfterDelay ?: return@ResumedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val tabs = panes.allTabs
            autosaveTracker.retain(tabs.map { it.path })
            for (tab in tabs) {
                val session = tab.session ?: continue
                autosaveTracker.observe(tab.path, session.version, now)
            }
            val dirty = tabs.filter { it.isDirty && !it.isReadOnly }.map { it.path }
            for (path in autosaveTracker.due(dirty, autosave.milliseconds, now)) {
                val tab = tabs.firstOrNull { it.path == path } ?: continue
                autosaveTracker.saved(path)
                save(tab, format = false)
            }
            delay(STATUS_POLL_MS)
        }
    }

    // `"autosave": "on_focus_change"` — Zed saves the item the focus left
    // (workspace.rs:7170-7185 saves on window deactivation; the pane saves
    // on item deactivation the same way). Here focus leaving a buffer is the
    // active tab changing: the tab that was active is written.
    var previouslyActive by remember { mutableStateOf<OpenFile?>(null) }
    LaunchedEffect(panes.files.active) {
        val departed = previouslyActive
        previouslyActive = panes.files.active
        if (settings.autosave == Autosave.OnFocusChange && departed != null &&
            departed !== panes.files.active && departed.isDirty && departed in panes.allTabs
        ) {
            save(departed)
        }
    }

    // `"autosave": "on_window_change"` — and `on_focus_change`, which Zed
    // also honours when the window deactivates (workspace.rs:7174-7181):
    // every dirty tab is written when the app leaves the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (settings.autosave == Autosave.OnWindowChange || settings.autosave == Autosave.OnFocusChange) {
            for (tab in panes.allTabs) if (tab.isDirty && !tab.isReadOnly) save(tab)
        }
    }

    /**
     * The engine replaced the buffer's text without going through the
     * editor: the editor's line window must be re-keyed and its IME
     * connection restarted. The second matters as much as the first — the
     * soft keyboard's shadow copy of the caret line predates the reload,
     * and its next sync would have written the *old* line back over the
     * new text as an edit of its own, which is where an undo of "reopen
     * with encoding" once took two presses.
     */
    fun noteBufferReplaced(file: OpenFile) {
        file.editor?.noteExternalEdit()
        file.refreshStatus()
        dismissedConflicts.value -= file.path
    }

    fun reload(file: OpenFile) {
        val open = file.session ?: return
        scope.launch {
            withContext(Dispatchers.IO) { open.reload() }
            noteBufferReplaced(file)
        }
    }

    /**
     * The line-ending picker's confirm: the engine records the choice and
     * marks the buffer dirty, and the next save writes it. Zed's picker saves
     * on the spot (line_ending_selector.rs:131-142); here the save stays the
     * user's, so the dirty dot says what happened and Ctrl+S is one press
     * away — a phone with the keyboard down has no undo for a write.
     */
    fun setLineEnding(file: OpenFile, lineEnding: LineEnding) {
        val open = file.session ?: return
        open.setLineEnding(lineEnding)
        file.refreshStatus()
    }

    /**
     * Parse the buffer as [language] — Zed's `language_selector` confirm,
     * which overrides the language for *this buffer* and writes nothing.
     * Re-reading the engine's answer rather than trusting ours keeps the bar
     * honest when a grammar is refused.
     */
    fun setLanguage(file: OpenFile, language: LanguageChoice) {
        val open = file.session ?: return
        scope.launch {
            withContext(Dispatchers.IO) { open.setLanguage(language.grammar) }
            file.language = open.language
            file.editor?.refreshHighlightVersion()
        }
    }

    /** Re-read the file decoded as [encoding] — Zed's `reload_with_encoding`. */
    fun reopenWithEncoding(file: OpenFile, encoding: String) {
        val open = file.session ?: return
        scope.launch {
            withContext(Dispatchers.IO) { open.reopenWithEncoding(encoding) }
            noteBufferReplaced(file)
        }
    }

    /** Write the file in [encoding], now: the choice is recorded, then saved. */
    fun saveWithEncoding(file: OpenFile, encoding: BufferEncoding) {
        val open = file.session ?: return
        open.setEncoding(encoding)
        save(file)
    }

    /**
     * The resolved-conflict banner's "Mark resolved (stage)": save, then
     * `git add` the file — git reads the disk, so the save is not optional —
     * and keep git's sentence for the banner when it refuses. Success needs
     * no message: the next status poll drops the file from the conflicted
     * set and the banner with it.
     */
    fun stageResolved(file: OpenFile) {
        val open = project ?: return
        val session = file.session ?: return
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                if (session.save()) GitSession(open).stage(listOf(file.path)) else "Could not save ${file.name}"
            }
            file.refreshStatus()
            resolvedStageError = error
        }
    }

    // A wide screen opens with its sidebar up, which is what it did before
    // docks existed and what Zed does. A compact one does not: there, a dock
    // *is* the work area, and starting on the tree would hide the editor
    // behind it. Seeded once, so closing it stays closed.
    // The agent reports a failed launch or a failed turn here — see
    // [AgentSessions.onProblem], which exists so `core` need not know the
    // toast stack is where its failures go.
    DisposableEffect(Unit) {
        AgentSessions.onProblem = { message -> Notifications.error(message, key = "agent") }
        onDispose { AgentSessions.onProblem = null }
    }

    // The worktree scan, in the status bar while it runs — Zed's activity
    // indicator reports its worktree scans the same way
    // (activity_indicator.rs:485-495). A big repository is seconds of it, and
    // until now the tree simply filled itself in silence.
    LaunchedEffect(project) {
        val open = project ?: return@LaunchedEffect
        Activities.begin(
            key = SCAN_ACTIVITY,
            message = "Scanning ${open.rootName}…",
            target = ActivityTarget.ProjectPanel,
        )
        try {
            while (!withContext(Dispatchers.Default) { open.scanComplete }) delay(STATUS_POLL_MS)
        } finally {
            // Also on cancellation: switching projects mid-scan must not
            // leave the old project's spinner turning for ever.
            Activities.end(SCAN_ACTIVITY)
        }
    }

    var docksSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(isWide, project) {
        if (!docksSeeded && isWide && project != null) {
            docks.open(WorkspacePanel.Project, settings)
            docksSeeded = true
        }
    }



    /** Whether the open tab is text the preview can draw. */
    fun canPreviewActiveFile(): Boolean {
        val open = panes.files.active ?: return false
        return open.editor != null && PreviewKind.of(open.path) != null
    }

    /**
     * Show or hide a panel, and put the keyboard somewhere the key table can
     * see it.
     *
     * The focus half is not incidental: panels do not all take focus when they
     * appear — the preview deliberately does not, since it follows a file being
     * typed in — and a compact screen can take a terminal off the screen while
     * `terminalFocused` still says the shell has the keys. Returns whether the
     * panel is now open.
     */
    /** The commit graph, as a tab of its own — Zed opens it as a pane item. */
    fun openGraph() {
        if (project == null) return
        val key = "git-graph:"
        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
        } else {
            panes.files.open(OpenFile(path = key, editor = null, graph = true))
        }
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * A language server's log, as a tab for the graph's reason: Zed's
     * `dev::OpenLanguageServerLogs` opens its log view as a pane item
     * (language_tools/src/lsp_log_view.rs), one per server here so two
     * servers' chatter never interleaves.
     */
    fun openLspLogsTab(serverName: String) {
        if (project == null) return
        val key = "lsp-logs:$serverName"
        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
        } else {
            panes.files.open(OpenFile(path = key, editor = null, lspLogs = serverName))
        }
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * The servers a `RestartLanguageServer` / `StopLanguageServer` means:
     * Zed's act on the active buffer's language (editor.rs
     * `restart_language_server` asks the project for the buffer's servers);
     * with no buffer open, every server of the project. Read at command
     * time rather than watched: the palette runs these once in a while.
     */
    suspend fun serversForActiveFile(): List<LspServer> {
        val open = project ?: return emptyList()
        val servers = withContext(Dispatchers.IO) { parseLspServers(CoreBridge.lspServers(open.id)) }
        val language = panes.files.active?.language ?: return servers
        return servers.filter { language in it.languages }.ifEmpty { servers }
    }

    /**
     * Open a go-to target — a definition, a project symbol — in its file
     * with the caret on it. A target outside the project (a header out of
     * /usr/include) has no project-relative name, and this opener knows only
     * those: dropped rather than opened at a path the panel could never show.
     */
    fun openDefinitionTarget(target: DefinitionTarget) {
        val open = project ?: return
        val root = open.rootPath
        val relative = if (root != null && target.path.startsWith("$root/")) {
            target.path.removePrefix("$root/")
        } else {
            return
        }
        openFile(open, relative, PreviewRoute.CodeNavigation) { file ->
            file.editor?.revealDefinitionTarget(target)
        }
    }

    /**
     * Project diagnostics, as a tab for the graph's reason: Zed opens it as a
     * pane item (diagnostics/src/diagnostics.rs deploys it into the active
     * pane), so a second visit selects the tab that is already there.
     */
    fun openDiagnosticsTab() {
        if (project == null) return
        val key = "diagnostics:"
        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
        } else {
            panes.files.open(OpenFile(path = key, editor = null, diagnostics = true))
        }
        diagnosticsFocus++
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * The agent review, as a tab for the diagnostics' reason: Zed opens its
     * agent diff as a pane item (`agent::OpenAgentDiff`, agent_diff.rs), and
     * a second visit selects the tab already there. It follows the active
     * thread, so one tab is all there ever is.
     */
    fun openAgentReviewTab() {
        if (project == null) return
        val key = "agent-review:"
        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
        } else {
            panes.files.open(OpenFile(path = key, editor = null, agentReview = true))
        }
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * The editor's selection as a mention, for the agent's `@` picker and
     * `agent::AddSelectionToThread`; null when nothing is selected. Read on
     * demand, never as state — the caret moves on every arrow key.
     */
    fun editorSelectionMention(): AgentMention.Selection? {
        val active = panes.files.active ?: return null
        val editor = active.editor ?: return null
        val range = editor.selectionRange() ?: return null
        val text = editor.selectionText()
        if (text.isEmpty()) return null
        return AgentMention.Selection(
            path = active.path,
            startRow = range.startRow,
            // An end at column 0 is the line break, not the next line.
            endRow = if (range.endCol == 0 && range.endRow > range.startRow) range.endRow - 1 else range.endRow,
            text = text,
        )
    }

    /** The open text buffers, for the picker's Symbols section. */
    fun openBufferRefs(): List<OpenBufferRef> = panes.allTabs.mapNotNull { tab ->
        tab.session?.let { OpenBufferRef(it.id, tab.path) }
    }

    // Lambdas, read when the picker opens: see [AgentWorkspaceAccess].
    val agentWorkspace = remember {
        AgentWorkspaceAccess(
            openBuffers = { openBufferRefs() },
            selection = { editorSelectionMention() },
        )
    }

    /** Whether [panel] is not just open but on screen. See [drawnDocks]. */
    fun panelIsDrawn(panel: WorkspacePanel): Boolean =
        docks.isOpen(panel, settings) && panel.sideIn(settings) in drawnDocks

    fun togglePanel(panel: WorkspacePanel): Boolean {
        // A panel that is open but *not drawn* — the loser when two docks will
        // not both fit — is raised rather than toggled. Its button says open
        // and the screen says otherwise; the press has to resolve that in
        // favour of showing it, not of closing something invisible.
        if (docks.isOpen(panel, settings) && !panelIsDrawn(panel)) {
            docks.raise(panel, settings)
            terminalFocused = false
            rootFocus.requestFocus()
            return true
        }
        val opened = docks.toggle(panel, settings)
        terminalFocused = false
        rootFocus.requestFocus()
        return opened
    }

    /** Zed's `workspace::ToggleLeftDock` / `ToggleRightDock`. */
    fun toggleDock(side: DockSide): Boolean {
        val showing = docks.active(side)
        if (showing != null) {
            docks.closeDock(side)
            rootFocus.requestFocus()
            return true
        }
        // Nothing in it: open the panel that lives on this side, preferring
        // the tree, which is what a person means by "show the sidebar".
        val panel = WorkspacePanel.entries.firstOrNull { it.sideIn(settings) == side }
            ?: return false
        // A panel with nothing to show would open onto its empty state and
        // take the screen with it.
        // A tree with no project is an empty panel over the work area with no
        // button to dismiss it.
        val usable = when (panel) {
            WorkspacePanel.Preview -> canPreviewActiveFile()
            else -> project != null
        }
        if (!usable) return false
        return togglePanel(panel)
    }


    /**
     * Zed's pane::GoBack / GoForward: [OpenFilesState.goBack] pops the entry
     * and activates its tab when it is still open; a closed file is reopened
     * through the same [openFile] the tabs' reopen uses. Either way the entry
     * restores its caret and scroll once the editor exists.
     */
    fun navigateHistory(back: Boolean): Boolean {
        val entry = (if (back) panes.files.goBack() else panes.files.goForward()) ?: return false
        val index = panes.files.indexOfPath(entry.path)
        if (index >= 0) {
            // goBack already made the tab active, outside the history's ears.
            val tab = panes.files.tabs[index]
            scope.launch { entry.restoreIn(tab) }
        } else {
            val open = project
            if (open == null) {
                panes.files.navigationFailed(entry, wasBack = back)
                return false
            }
            // A file that will not open is not a move: put the entry back
            // rather than lighting the opposite arrow for travel that never
            // happened, and disarm the landing bracket.
            openFile(
                open,
                entry.path,
                onFailed = { panes.files.navigationFailed(entry, wasBack = back) },
            ) { tab -> entry.restoreIn(tab) }
        }
        return true
    }

    /**
     * A git command run from the palette reaches the panel as a request: the
     * panel owns the session, the single-flight busy flag and the strip that
     * says what git answered, so the command must run *there* — Zed routes
     * its workspace-registered git actions to the panel the same way
     * (git_ui.rs:193-241). Opening the dock first is what makes the spinner,
     * and whatever git says back, visible.
     */
    fun requestGitPanelCommand(command: GitPanelCommand): Boolean {
        val open = project ?: return false
        docks.open(WorkspacePanel.Git, settings)
        // On a compact screen the panel takes the work area from a focused
        // terminal, and nothing else would tell the key table it is gone.
        terminalFocused = false
        // Stamped with the project it was asked for: the panel drops a
        // request whose stamp no longer matches, so a push asked on one
        // project can never land on another the workspace switched to while
        // the first scan was still out.
        gitPanelRequest = GitPanelRequest(command, open.id, (gitPanelRequest?.token ?: 0) + 1)
        return true
    }

    /**
     * Open the find bar over the active editor, or re-deploy it — Zed's
     * `buffer_search::Deploy` / `DeployReplace`. Seeded from the editor's
     * selection or the word under its caret, unless the bar itself has the
     * keyboard: then it is Zed's `search::FocusSearch`, which keeps the
     * query and only selects it. False with no text buffer to search.
     */
    fun deploySearch(replace: Boolean): Boolean {
        val editor = panes.files.active?.editor ?: return false
        val open = searchDeploy
        val seed = if (open != null && searchBarFocused) null else editor.searchSeed()
        searchDeploy = SearchDeploy(token = (open?.token ?: 0) + 1, seed = seed, replace = replace)
        return true
    }

    /** A palette command for the open find bar. False while it is closed. */
    fun requestSearchBarAction(action: SearchBarAction): Boolean {
        val open = searchDeploy ?: return false
        searchDeploy = SearchDeploy(token = open.token + 1, action = action)
        return true
    }

    /**
     * Close the find bar and hand the keyboard to the editor it searched —
     * Zed's `buffer_search::Dismiss` focuses the searchable item on its way
     * out (buffer_search.rs:822-844), and `SelectAllMatches` closes the bar
     * the same way after placing its carets, which would otherwise sit in
     * an editor nothing can type into. The workspace root takes the keys
     * only while there is no editor to give them to.
     */
    fun dismissSearch() {
        searchDeploy = null
        searchBarFocused = false
        val editor = panes.files.active?.editor
        if (editor != null) editor.requestFocus() else rootFocus.requestFocus()
    }

    /**
     * Where a new shell starts — `terminal.working_directory`, resolved as
     * Zed's `TerminalPanel::get_working_directory` does
     * (terminal_panel.rs): the open file's directory when asked and there is
     * one, home when asked, the project root otherwise. Null without a
     * project, which is when the terminal commands refuse.
     */
    fun terminalStartDirectory(): String? {
        val root = project?.rootPath ?: return null
        return when (settings.terminal.workingDirectory) {
            TerminalWorkingDirectory.CurrentFileDirectory ->
                panes.files.active?.absolutePath?.let { File(it).parent } ?: root
            TerminalWorkingDirectory.AlwaysHome -> ShellEnvironment.homeDir(context).absolutePath
            TerminalWorkingDirectory.CurrentProjectDirectory,
            TerminalWorkingDirectory.FirstProjectDirectory -> root
        }
    }

    /**
     * Open one of the app's own files — settings.json, keymap.json, the
     * generated default keymap — as a tab, keyed by its absolute path: it
     * belongs to no project, and the engine opens it like any other file.
     * [materialise] runs first, off the main thread, so the file exists.
     * A tab already open for it is selected rather than opened twice.
     */
    fun openAppFile(path: String, materialise: () -> Unit) {
        val existing = panes.files.indexOfPath(path)
        if (existing >= 0) {
            panes.files.select(existing)
            return
        }
        scope.launch {
            val session = withContext(Dispatchers.IO) {
                materialise()
                BufferSession.openFile(path)
            } ?: return@launch
            panes.files.open(OpenFile(path, EditorState(session), absolutePath = path))
        }
    }

    /**
     * Open a project-relative file as a tab, writing [initial] first when
     * it does not exist — Zed's `open_local_file` (zed.rs), which is how
     * both `.zed/settings.json` and `.zed/tasks.json` come to be. A tab
     * already open for it is selected instead.
     */
    fun openProjectFileCreating(open: ProjectSession, relative: String, initial: String) {
        val existing = panes.files.indexOfPath(relative)
        if (existing >= 0) {
            panes.files.select(existing)
            return
        }
        scope.launch {
            val created = withContext(Dispatchers.IO) {
                val file = File(open.rootPath, relative)
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    runCatching { file.writeText(initial) }.isSuccess
                } else {
                    true
                }
            }
            if (!created) {
                Notifications.error("$relative could not be created")
                return@launch
            }
            openFile(open, relative)
        }
    }

    /**
     * Run [task] in the terminal dock, after writing whatever its `save`
     * asks for — Zed's `save_for_task` (workspace/src/tasks.rs:174-198):
     * `all` saves every edited tab, `current` the active one, `none`
     * nothing. Awaited, so the command sees the text on screen.
     */
    fun spawnTask(root: String, task: TaskSpec) {
        scope.launch {
            when (task.save) {
                // `all` is Zed's whole workspace, so every pane's tabs, not
                // just the active one's; a file split into two panes is one
                // buffer and is saved once.
                TaskSave.All ->
                    panes.allTabs.filter { it.isDirty }.distinct().forEach { saveNow(it) }
                TaskSave.Current -> panes.files.active?.takeIf { it.isDirty }?.let { saveNow(it) }
                TaskSave.None -> Unit
            }
            runTask(context, terminals, root, task)
        }
    }

    /**
     * A play button, or `editor::SpawnNearestTask`: the tasks bound to the
     * row's tags, run at once when there is exactly one — Zed's
     * `spawn_nearest_task` takes the first resolution (runnables.rs:371) —
     * and offered in the picker when there are several, already narrowed to
     * them. None at all opens the picker on the plain list, so a tap never
     * silently does nothing.
     */
    fun spawnRunnable(runnable: Runnable) {
        val opened = project ?: return
        val editor = panes.files.active?.editor ?: return
        val taskContext = editorTaskContext(editor).copy(
            row = runnable.row,
            column = runnable.col,
            runnable = runnable,
        )
        scope.launch {
            val tasks = loadTasks(opened.id, taskContext)
            when (tasks.size) {
                1 -> spawnTask(opened.rootPath, tasks[0])
                0 -> overlays.taskPicker = TaskPickerRequest(taskContext.copy(runnable = null))
                else -> overlays.taskPicker = TaskPickerRequest(taskContext, preloaded = tasks)
            }
        }
    }

    /**
     * Run a pane activation and, when it moved, hand the keyboard to the
     * editor that is now active — Zed focuses the pane's handle
     * (workspace.rs:5464), which its editor picks up.
     */
    fun activatePane(move: () -> Boolean): Boolean {
        if (!move()) return false
        panes.files.active?.editor?.requestFocus()
        return true
    }

    /**
     * Write `ui_font_size`, which is the app's rem: every bar, row, gap and
     * icon is a multiple of it, so this resizes the chrome and not only its
     * text (`theme_settings/src/settings.rs:619`).
     *
     * Whole numbers only. The key takes a float and the chords step by one,
     * so the rounding never loses anything — and an integer is what a hand
     * edited file, and the settings row beside it, will hold.
     */
    /**
     * The editor's text size with the session zoom in it — Zed's
     * `buffer_font_size` plus the delta its `persist: false` chords move.
     * The terminal draws at this size too, as it draws in the same face.
     */
    val bufferFontSizeSp = (settings.bufferFontSize + bufferFontDelta).coerceIn(6f, 48f)

    fun setUiFontSize(size: Float) {
        val clamped = size.coerceIn(FontSettings.MIN_UI_FONT_SIZE, FontSettings.MAX_UI_FONT_SIZE)
        scope.launch(Dispatchers.IO) {
            AppSettings.set(AppSettings.KEY_UI_FONT_SIZE, clamped.toInt().toString())
                ?.let { updated -> withContext(Dispatchers.Main) { onSettingsChanged(updated) } }
        }
    }

    fun adjustUiFontSize(delta: Float) = setUiFontSize(settings.fonts.uiSize + delta)

    fun runCommand(command: WorkspaceCommand): Boolean {
        val active = panes.files.active
        when (command) {
            // A picture has no buffer, so there is nothing to save and the
            // chord is refused rather than silently doing nothing.
            WorkspaceCommand.Save -> {
                if (active?.session == null || active.isReadOnly) return false
                save(active)
            }
            WorkspaceCommand.CloseTab -> {
                if (panes.files.activeIndex < 0) return false
                // `requestClose`, never `close`: the unconditional one drops
                // the buffer and every edit since the last save, and this
                // command is the most-used route to it.
                panes.files.requestClose(panes.files.activeIndex)
            }
            WorkspaceCommand.CloseOtherTabs -> {
                if (panes.files.activeIndex < 0) return false
                panes.files.requestCloseOthers(panes.files.activeIndex)
            }
            WorkspaceCommand.CloseTabsToTheRight -> {
                if (panes.files.activeIndex < 0) return false
                panes.files.requestCloseToTheRight(panes.files.activeIndex)
            }
            WorkspaceCommand.CloseTabsToTheLeft -> {
                if (panes.files.activeIndex < 0) return false
                panes.files.requestCloseToTheLeft(panes.files.activeIndex)
            }
            WorkspaceCommand.CloseCleanTabs -> panes.files.requestCloseClean()
            // The switcher's own command — Zed's `tab_switcher::Toggle`. Opened
            // this way (the palette, the ☰ menu, the strip's ⇥ button) it stays
            // up until something is chosen, because there is no Ctrl being held
            // to release. The active pane's tabs, as the chord's are.
            WorkspaceCommand.OpenTabSwitcher -> {
                if (panes.files.tabs.size < 2) return false
                switcherTabs = panes.files.mruTabs()
                switcherIndex = 1
            }
            WorkspaceCommand.CloseAllTabs -> panes.files.requestCloseAll()
            WorkspaceCommand.TogglePinTab -> {
                if (panes.files.activeIndex < 0) return false
                panes.files.togglePin(panes.files.activeIndex)
            }
            WorkspaceCommand.ReopenClosedTab -> {
                val opened = project ?: return false
                val path = panes.files.takeReopenPath() ?: return false
                openFile(opened, path)
            }
            WorkspaceCommand.RevealInProjectPanel -> {
                if (panes.files.active == null) return false
                docks.open(WorkspacePanel.Project, settings)
                revealInPanel = true
            }
            // Zed binds ctrl-shift-enter in the ProjectPanel context only
            // (default-linux.json:1002); while the panel has the keys its
            // own handler acts on the selected row, and this stands aside
            // exactly as NewFile does.
            WorkspaceCommand.OpenWithSystem -> {
                // The panel's row while the panel has the keys; the git
                // panel's `ctrl-shift-enter` is amend, as in Zed's GitPanel
                // context, so the chord stands aside there too.
                if (projectPanelFocused || gitPanelFocused) return false
                val path = active?.absolutePath ?: return false
                ShareOut.openWith(context, File(path))
            }
            WorkspaceCommand.Share -> {
                val path = active?.absolutePath ?: return false
                ShareOut.share(context, File(path))
            }
            WorkspaceCommand.NextTab -> panes.files.selectRelative(1)
            WorkspaceCommand.PreviousTab -> panes.files.selectRelative(-1)
            WorkspaceCommand.OpenInTerminal -> {
                val open = project ?: return false
                terminals.newSession(
                    panes.files.active?.path
                        ?.let { open.absolutePathOf(it.substringBeforeLast('/', "")) }
                        ?: open.rootPath
                )
            }
            WorkspaceCommand.SplitRight -> if (!splitActivePane(SplitDirection.Right)) return false
            WorkspaceCommand.SplitLeft -> if (!splitActivePane(SplitDirection.Left)) return false
            WorkspaceCommand.SplitUp -> if (!splitActivePane(SplitDirection.Up)) return false
            WorkspaceCommand.SplitDown -> if (!splitActivePane(SplitDirection.Down)) return false
            WorkspaceCommand.ActivatePaneLeft -> if (!activatePane { panes.activateInDirection(SplitDirection.Left) }) return false
            WorkspaceCommand.ActivatePaneRight -> if (!activatePane { panes.activateInDirection(SplitDirection.Right) }) return false
            WorkspaceCommand.ActivatePaneUp -> if (!activatePane { panes.activateInDirection(SplitDirection.Up) }) return false
            WorkspaceCommand.ActivatePaneDown -> if (!activatePane { panes.activateInDirection(SplitDirection.Down) }) return false
            WorkspaceCommand.ActivateNextPane -> if (!activatePane { panes.activateNext() }) return false
            WorkspaceCommand.ActivatePreviousPane -> if (!activatePane { panes.activatePrevious() }) return false
            WorkspaceCommand.SwapPaneLeft -> if (!panes.swapInDirection(SplitDirection.Left)) return false
            WorkspaceCommand.SwapPaneRight -> if (!panes.swapInDirection(SplitDirection.Right)) return false
            WorkspaceCommand.SwapPaneUp -> if (!panes.swapInDirection(SplitDirection.Up)) return false
            WorkspaceCommand.SwapPaneDown -> if (!panes.swapInDirection(SplitDirection.Down)) return false
            WorkspaceCommand.JoinIntoNext -> if (!activatePane { panes.joinIntoNext() }) return false
            WorkspaceCommand.JoinAll -> if (!panes.joinAll()) return false
            WorkspaceCommand.ToggleZoom -> if (!panes.toggleZoom()) return false
            WorkspaceCommand.GoBack -> if (!navigateHistory(back = true)) return false
            WorkspaceCommand.GoForward -> if (!navigateHistory(back = false)) return false
            WorkspaceCommand.NewFile -> {
                if (project == null) return false
                // Zed binds ctrl-n to both `workspace::NewFile` and
                // `project_panel::NewFile` and lets the panel's more specific
                // context win while it has focus (default-linux.json:654,
                // 965). Refusing here does the same: the preview pass falls
                // through and the panel's own handler sees the chord.
                if (projectPanelFocused) return false
                overlays.newFileOpen = true
            }
            WorkspaceCommand.ToggleProjectPanel -> togglePanel(WorkspacePanel.Project)
            WorkspaceCommand.ToggleLeftDock -> if (!toggleDock(DockSide.Left)) return false
            WorkspaceCommand.ToggleRightDock -> if (!toggleDock(DockSide.Right)) return false
            WorkspaceCommand.OpenProjects -> {
                refreshProjects()
                transferError = null
                pickerStartsInClone = false
                overlays.pickerOpen = true
            }
            WorkspaceCommand.OpenRecent -> {
                scope.launch {
                    recents = withContext(Dispatchers.IO) {
                        RecentProject.parseList(CoreBridge.recentProjects())
                    }
                    overlays.recentOpen = true
                }
            }
            WorkspaceCommand.CloseWindow -> {
                if (project == null) return false
                closeWorkspace()
            }
            WorkspaceCommand.InstallLanguageServer -> {
                if (!LanguageServerInstaller.isSupported) return false
                // The open file names the language; with nothing open the
                // prompt shows the list rather than guessing one.
                serverPromptGrammar = panes.files.active?.language
                overlays.serverPromptOpen = true
            }
            WorkspaceCommand.CloneRepository -> {
                if (!GitClone.isSupported) return false
                refreshProjects()
                transferError = null
                pickerStartsInClone = true
                overlays.pickerOpen = true
            }
            WorkspaceCommand.FindFile -> {
                if (project == null) return false
                overlays.finderOpen = true
            }
            WorkspaceCommand.SelectTheme -> overlays.themeSelectorOpen = true
            WorkspaceCommand.SelectIconTheme -> overlays.iconThemeSelectorOpen = true
            // Both pickers are about the active *text* file: a picture has no
            // line ending to choose and no encoding to reopen in.
            WorkspaceCommand.SelectLineEnding -> {
                if (active?.session == null) return false
                overlays.lineEndingSelectorOpen = true
            }
            WorkspaceCommand.SelectEncoding -> {
                if (active?.session == null) return false
                overlays.encodingSelectorOpen = true
            }
            WorkspaceCommand.SelectLanguage -> {
                if (active?.session == null) return false
                overlays.languageSelectorOpen = true
            }
            WorkspaceCommand.SelectToolchain -> {
                if (project == null) return false
                overlays.toolchainSelectorOpen = true
            }
            // Zed's multi-root worktrees: the project keeps an ordered list of
            // folders and these two grow and shrink it.
            WorkspaceCommand.AddFolderToProject -> {
                if (project == null) return false
                refreshProjects()
                overlays.addFolderOpen = true
            }
            WorkspaceCommand.RemoveFolderFromProject -> {
                if (folders.count { !it.isPrimary } == 0) return false
                overlays.removeFolderOpen = true
            }
            WorkspaceCommand.TogglePreview -> {
                if (!canPreviewActiveFile()) return false
                togglePanel(WorkspacePanel.Preview)
            }
            // Zed's rem is `ui_font_size`, so these three resize the whole
            // chrome — rows, bars, gaps and icons — rather than only its
            // text. They *write the setting*, unlike Zed's own
            // (`persist: false`), because the key is now in settings.json and
            // a chord that moved the chrome without moving the row that shows
            // its size would be two sources of truth for one number.
            WorkspaceCommand.IncreaseUiFontSize -> adjustUiFontSize(ThemeStore.FONT_SIZE_STEP)
            WorkspaceCommand.DecreaseUiFontSize -> adjustUiFontSize(-ThemeStore.FONT_SIZE_STEP)
            WorkspaceCommand.ResetUiFontSize -> setUiFontSize(ThemeStore.DEFAULT_UI_FONT_SIZE)

            // Zed's buffer-font chords, which carry `{ "persist": false }`
            // (default-linux.json:30-33): they move a delta over
            // `buffer_font_size`, not the setting. Pinching the editor drives
            // the same delta.
            WorkspaceCommand.IncreaseBufferFontSize ->
                ThemeStore.adjustBufferFontSize(context, ThemeStore.FONT_SIZE_STEP)

            WorkspaceCommand.DecreaseBufferFontSize ->
                ThemeStore.adjustBufferFontSize(context, -ThemeStore.FONT_SIZE_STEP)

            WorkspaceCommand.ResetBufferFontSize -> ThemeStore.resetBufferFontSize(context)

            // Zed's image viewer actions (image_viewer.rs:210-249), refused
            // rather than shrugged at when the open tab is not a picture.
            WorkspaceCommand.ImageZoomIn -> {
                if (active?.media != MediaKind.Image) return false
                imageZoom.zoomIn()
            }
            WorkspaceCommand.ImageZoomOut -> {
                if (active?.media != MediaKind.Image) return false
                imageZoom.zoomOut()
            }
            WorkspaceCommand.ImageResetZoom, WorkspaceCommand.ImageZoomToActualSize -> {
                if (active?.media != MediaKind.Image) return false
                imageZoom.reset()
            }
            WorkspaceCommand.ImageFitToView -> {
                if (active?.media != MediaKind.Image) return false
                imageZoom.fitToView()
            }

            WorkspaceCommand.ToggleSoftWrap -> {
                val next = if (settings.softWrap.wraps) SoftWrapMode.None else SoftWrapMode.EditorWidth
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AppSettings.set(AppSettings.KEY_SOFT_WRAP, "\"" + next.key + "\"")
                    }
                    if (updated != null) onSettingsChanged(updated)
                }
            }
            WorkspaceCommand.ToggleVimMode -> {
                val next = !settings.vimMode
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AppSettings.set(AppSettings.KEY_VIM_MODE, next.toString())
                    }
                    if (updated != null) onSettingsChanged(updated)
                }
            }
            WorkspaceCommand.OpenGitGraph -> {
                if (project == null) return false
                openGraph()
            }
            WorkspaceCommand.SwitchBranch -> {
                if (project == null) return false
                overlays.branchPickerOpen = true
            }
            WorkspaceCommand.GitViewStash -> {
                if (project == null) return false
                overlays.stashPickerOpen = true
            }
            WorkspaceCommand.GitStashAll ->
                if (!requestGitPanelCommand(GitPanelCommand.StashAll)) return false
            WorkspaceCommand.GitStashTracked ->
                if (!requestGitPanelCommand(GitPanelCommand.StashTracked)) return false
            WorkspaceCommand.GitStashStaged ->
                if (!requestGitPanelCommand(GitPanelCommand.StashStaged)) return false
            WorkspaceCommand.GitStashPop ->
                if (!requestGitPanelCommand(GitPanelCommand.StashPop)) return false
            WorkspaceCommand.GitStashApply ->
                if (!requestGitPanelCommand(GitPanelCommand.StashApply)) return false
            // Git in the editor: the palette's rows for the editor's own
            // `editor::`/`git::` hunk actions, forwarded to the active
            // editor's handler under the same id — the keymap's route, so
            // the two cannot drift.
            WorkspaceCommand.GoToHunk,
            WorkspaceCommand.GoToPreviousHunk,
            WorkspaceCommand.ExpandAllDiffHunks,
            WorkspaceCommand.ToggleSelectedDiffHunks,
            WorkspaceCommand.GitToggleStaged,
            WorkspaceCommand.GitStageAndNext,
            WorkspaceCommand.GitUnstageAndNext,
            WorkspaceCommand.GitRestore,
            WorkspaceCommand.GitBlame,
            // The editor's own text, line and display commands, forwarded the
            // same way and for the same reason.
            WorkspaceCommand.SelectLine,
            WorkspaceCommand.SelectLargerSyntaxNode,
            WorkspaceCommand.SelectSmallerSyntaxNode,
            WorkspaceCommand.MoveToEnclosingBracket,
            WorkspaceCommand.SortLinesCaseSensitive,
            WorkspaceCommand.SortLinesCaseInsensitive,
            WorkspaceCommand.ReverseLines,
            WorkspaceCommand.ShuffleLines,
            WorkspaceCommand.UniqueLinesCaseSensitive,
            WorkspaceCommand.UniqueLinesCaseInsensitive,
            WorkspaceCommand.Transpose,
            WorkspaceCommand.Rewrap,
            WorkspaceCommand.ConvertToUpperCase,
            WorkspaceCommand.ConvertToLowerCase,
            WorkspaceCommand.ConvertToTitleCase,
            WorkspaceCommand.ConvertToSnakeCase,
            WorkspaceCommand.ConvertToKebabCase,
            WorkspaceCommand.ConvertToUpperCamelCase,
            WorkspaceCommand.ConvertToLowerCamelCase,
            WorkspaceCommand.ConvertToOppositeCase,
            WorkspaceCommand.ToggleLineNumbers,
            WorkspaceCommand.ToggleRelativeLineNumbers,
            WorkspaceCommand.ToggleMinimap,
            WorkspaceCommand.ToggleInlineDiagnostics,
            -> return panes.files.active?.editor?.runAction(command.id) ?: false
            WorkspaceCommand.ToggleAgentPanel -> {
                if (!isAgentPanelSupported) return false
                if (togglePanel(WorkspacePanel.Agent)) agentPanelFocus++
            }
            WorkspaceCommand.NewAgentThread -> {
                if (!isAgentPanelSupported || project == null) return false
                // Zed's `NewThread` on a panel with no agent chosen yet is
                // the panel's own empty state; here that is opening it.
                if (!panelIsDrawn(WorkspacePanel.Agent)) togglePanel(WorkspacePanel.Agent)
                if (AgentSessions.active != null) AgentSessions.newThreadHere()
                agentPanelFocus++
            }
            WorkspaceCommand.AgentAllowOnce -> {
                if (AgentSessions.sessionId < 0) return false
                AgentSessions.answerWaiting("allow_once")
            }
            WorkspaceCommand.AgentAllowAlways -> {
                if (AgentSessions.sessionId < 0) return false
                AgentSessions.answerWaiting("allow_always")
            }
            WorkspaceCommand.AgentRejectOnce -> {
                if (AgentSessions.sessionId < 0) return false
                AgentSessions.answerWaiting("reject_once")
            }
            WorkspaceCommand.OpenAgentDiff -> {
                if (!isAgentPanelSupported || project == null) return false
                openAgentReviewTab()
            }
            WorkspaceCommand.AgentKeepAll -> {
                if (AgentSessions.sessionId < 0) return false
                AgentSessions.keepEdits(emptyList())
            }
            WorkspaceCommand.AgentRejectAll -> {
                if (AgentSessions.sessionId < 0) return false
                AgentSessions.rejectEdits(emptyList())
            }
            WorkspaceCommand.AddSelectionToThread -> {
                if (!isAgentPanelSupported || project == null) return false
                // Zed adds the selection and focuses the panel, opening it
                // first when it is hidden (agent_panel.rs:644-700). With
                // nothing selected it does nothing — a chord with no
                // selection behind it is a slip, not a request.
                val selection = editorSelectionMention() ?: return false
                if (!panelIsDrawn(WorkspacePanel.Agent)) togglePanel(WorkspacePanel.Agent)
                AgentSessions.addSelectionToThread(selection)
                agentPanelFocus++
            }

            WorkspaceCommand.ToggleGitPanel -> {
                if (project == null) return false
                if (togglePanel(WorkspacePanel.Git)) gitPanelFocus++
            }
            WorkspaceCommand.ToggleOutlinePanel -> {
                // The panel reads the active buffer's symbols, so with no
                // buffer there is nothing for it to be about.
                if (panes.files.active?.editor == null) return false
                // Pressed again with the panel already up, the chord puts the
                // keyboard back in its filter — Zed's `ToggleFocus`, which is
                // what the action is called.
                if (togglePanel(WorkspacePanel.Outline)) outlinePanelFocus++
            }
            WorkspaceCommand.ClearAllNotifications -> {
                if (Notifications.all.isEmpty()) return false
                Notifications.clearAll()
            }
            WorkspaceCommand.OpenDiagnostics -> {
                if (project == null) return false
                openDiagnosticsTab()
            }
            WorkspaceCommand.ProjectSymbols -> {
                if (project == null) return false
                overlays.projectSymbolsOpen = true
            }
            WorkspaceCommand.ToggleInlayHints -> {
                val next = !settings.inlayHints.enabled
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AppSettings.set(AppSettings.KEY_INLAY_HINTS, next.toString())
                    }
                    if (updated != null) onSettingsChanged(updated)
                }
            }
            WorkspaceCommand.RestartLanguageServer -> {
                val open = project ?: return false
                scope.launch {
                    val servers = serversForActiveFile()
                    withContext(Dispatchers.IO) {
                        for (server in servers) CoreBridge.lspRestartServer(open.id, server.name)
                    }
                }
            }
            WorkspaceCommand.StopLanguageServer -> {
                val open = project ?: return false
                scope.launch {
                    val servers = serversForActiveFile()
                    withContext(Dispatchers.IO) {
                        for (server in servers) CoreBridge.lspStopServer(open.id, server.name)
                    }
                }
            }
            WorkspaceCommand.OpenLanguageServerLogs -> {
                if (project == null) return false
                scope.launch {
                    // One tab, for the active file's server; the status bar's
                    // menu is where a particular one is picked.
                    val server = serversForActiveFile().firstOrNull()
                    if (server != null) {
                        openLspLogsTab(server.name)
                    } else {
                        Notifications.warn("No language server is running for this project")
                    }
                }
            }
            // The git family runs in the panel — see requestGitPanelCommand.
            WorkspaceCommand.GitFetch ->
                if (!requestGitPanelCommand(GitPanelCommand.Fetch)) return false
            WorkspaceCommand.GitPull ->
                if (!requestGitPanelCommand(GitPanelCommand.Pull)) return false
            WorkspaceCommand.GitPullRebase ->
                if (!requestGitPanelCommand(GitPanelCommand.PullRebase)) return false
            WorkspaceCommand.GitPush ->
                if (!requestGitPanelCommand(GitPanelCommand.Push)) return false
            WorkspaceCommand.GitForcePush ->
                if (!requestGitPanelCommand(GitPanelCommand.ForcePush)) return false
            WorkspaceCommand.GitStageAll ->
                if (!requestGitPanelCommand(GitPanelCommand.StageAll)) return false
            WorkspaceCommand.GitUnstageAll ->
                if (!requestGitPanelCommand(GitPanelCommand.UnstageAll)) return false
            WorkspaceCommand.GitDiff ->
                if (!requestGitPanelCommand(GitPanelCommand.Diff)) return false
            // The conflict family acts on the open editor's own list, which
            // is a poll behind the text; the engine re-reads the text before
            // it edits, and a refusal comes back as false.
            WorkspaceCommand.GoToNextConflict ->
                if (active?.editor?.goToConflict(forward = true) != true) return false
            WorkspaceCommand.GoToPreviousConflict ->
                if (active?.editor?.goToConflict(forward = false) != true) return false
            WorkspaceCommand.ConflictUseOurs ->
                if (active?.editor?.resolveConflictAtCaret(keepOurs = true, keepTheirs = false) != true) {
                    return false
                }
            WorkspaceCommand.ConflictUseTheirs ->
                if (active?.editor?.resolveConflictAtCaret(keepOurs = false, keepTheirs = true) != true) {
                    return false
                }
            WorkspaceCommand.ConflictUseBoth ->
                if (active?.editor?.resolveConflictAtCaret(keepOurs = true, keepTheirs = true) != true) {
                    return false
                }
            WorkspaceCommand.FindInFile -> {
                // The shell's search while it has the keyboard — Zed deploys
                // the same action over a terminal (default-linux.json:1281)
                // — and the palette's, when the terminal is what is open and
                // no buffer is.
                if (terminalFocused || (panes.files.active?.editor == null && terminals.isOpen)) {
                    if (terminals.activeIndex < 0) return false
                    terminals.searchOpen = true
                    return true
                }
                // From inside the bar this is Zed's `search::FocusSearch`
                // (default-linux.json:413): focus and select the query, no
                // re-seeding. From the editor it is `Deploy`, seeded.
                if (!deploySearch(replace = false)) return false
            }
            WorkspaceCommand.FindAndReplaceInFile -> {
                // Zed's `ctrl-h` is `DeployReplace` in the editor and
                // `ToggleReplace` in the bar (default-linux.json:142, 414).
                if (searchDeploy != null && searchBarFocused) {
                    requestSearchBarAction(SearchBarAction.ToggleReplace)
                } else if (!deploySearch(replace = true)) {
                    return false
                }
            }
            WorkspaceCommand.ToggleReplace ->
                if (!requestSearchBarAction(SearchBarAction.ToggleReplace)) return false
            WorkspaceCommand.ReplaceNext ->
                if (!requestSearchBarAction(SearchBarAction.ReplaceNext)) return false
            WorkspaceCommand.ReplaceAll ->
                if (!requestSearchBarAction(SearchBarAction.ReplaceAll)) return false
            WorkspaceCommand.SelectAllMatches ->
                if (!requestSearchBarAction(SearchBarAction.SelectAllMatches)) return false
            WorkspaceCommand.OpenOnboarding -> overlays.onboardingOpen = true
            WorkspaceCommand.About -> overlays.aboutOpen = true
            WorkspaceCommand.OpenSettings -> {
                scope.launch {
                    settingsValid = withContext(Dispatchers.IO) { CoreBridge.settingsAreValid() }
                    overlays.settingsOpen = true
                }
            }
            WorkspaceCommand.OpenSettingsFile -> {
                if (project == null) return false
                val path = settingsPath ?: return false
                // The engine writes the documented default file on its first
                // read, so a fresh install has something to open.
                openAppFile(path) { CoreBridge.settingsText() }
            }
            WorkspaceCommand.OpenKeymap -> {
                if (project == null) return false
                val path = keymapPath ?: return false
                openAppFile(path) { CoreBridge.keymapText() }
            }
            WorkspaceCommand.OpenDefaultKeymap -> {
                if (project == null) return false
                // Generated from the table the keyboard runs on, into the
                // cache — it is a document to read and copy from, not a
                // file the app reads back; Zed's is read-only for the same
                // reason.
                val path = File(context.cacheDir, "default-keymap.json").absolutePath
                openAppFile(path) { File(path).writeText(DefaultKeymap.text()) }
            }
            WorkspaceCommand.OpenDefaultSettings -> {
                if (project == null) return false
                val existing = panes.files.indexOfPath(DEFAULT_SETTINGS_TAB)
                if (existing >= 0) {
                    panes.files.select(existing)
                } else {
                    scope.launch {
                        // A scratch buffer holding the engine's own default
                        // text, highlighted as JSONC and refusing edits —
                        // Zed's `open_bundled_file` (zed.rs:306-316).
                        val session = withContext(Dispatchers.IO) {
                            BufferSession(CoreBridge.defaultSettingsText()).also { it.setLanguage("jsonc") }
                        }
                        val opened = OpenFile(
                            DEFAULT_SETTINGS_TAB,
                            EditorState(session, readOnly = true),
                            absolutePath = null,
                        )
                        panes.files.open(opened)
                        opened.refreshLanguageSettings()
                    }
                }
            }
            WorkspaceCommand.OpenProjectSettings -> {
                val open = project ?: return false
                // Created on the way, as Zed's `OpenProjectSettings` does
                // (zed.rs `open_local_file` with the initial contents
                // `initial_project_settings_content`): an empty object with
                // a comment saying what goes in.
                openProjectFileCreating(open, LOCAL_SETTINGS_PATH, INITIAL_PROJECT_SETTINGS)
            }
            WorkspaceCommand.OpenTasks -> {
                if (project == null) return false
                val path = tasksPath ?: return false
                // Created with Zed's commented example on first open, as
                // `OpenTasks` does (zed.rs:274-283).
                openAppFile(path) {
                    val file = File(path)
                    if (!file.exists()) runCatching { file.writeText(INITIAL_TASKS) }
                }
            }
            WorkspaceCommand.OpenProjectTasks -> {
                val open = project ?: return false
                openProjectFileCreating(open, LOCAL_TASKS_PATH, INITIAL_TASKS)
            }
            WorkspaceCommand.ToggleTerminal -> {
                val start = terminalStartDirectory() ?: return false
                if (terminals.isOpen) {
                    terminals.hide()
                    // Give the keyboard back to the workspace, or the next
                    // keystroke would go nowhere.
                    terminalFocused = false
                    rootFocus.requestFocus()
                } else {
                    terminals.open(start)
                }
            }
            WorkspaceCommand.NewTerminal -> {
                val start = terminalStartDirectory() ?: return false
                terminals.newSession(start)
            }
            WorkspaceCommand.CloseTerminal -> {
                if (terminals.activeIndex < 0) return false
                terminals.closeSession(terminals.activeIndex)
                if (!terminals.isOpen) {
                    terminalFocused = false
                    rootFocus.requestFocus()
                }
            }
            WorkspaceCommand.NextTerminal -> terminals.selectRelative(1)
            WorkspaceCommand.PreviousTerminal -> terminals.selectRelative(-1)
            WorkspaceCommand.SpawnTask -> {
                if (project == null) return false
                overlays.taskPicker = TaskPickerRequest(editorTaskContext(active?.editor))
            }
            WorkspaceCommand.RerunTask -> {
                val root = project?.rootPath ?: return false
                // Zed's `task::Rerun` reuses the task as it resolved last
                // time, with `reevaluate_context` off by default — the same
                // command in the same directory, not a fresh resolution
                // against wherever the caret is now.
                val last = TaskRuns.history.last ?: return false
                spawnTask(root, last)
            }
            WorkspaceCommand.SpawnNearestTask -> {
                val editor = active?.editor ?: return false
                val runnable = editor.nearestRunnable() ?: return false
                spawnRunnable(runnable)
            }
            // False rather than true when the tab is not a multibuffer: the
            // chord then falls through to the editor, where Alt+Enter is a
            // newline, instead of being swallowed for nothing.
            WorkspaceCommand.OpenExcerpt -> return openExcerptAtCaret()
        }
        return true
    }

    /** A project-search hit: open its file and put the caret on the match. */
    fun openMatch(path: String, match: ProjectSearchMatch) {
        val open = project ?: return
        openFile(open, path) { file -> file.editor?.revealProjectSearchMatch(match) }
        if (dockTookWorkArea != null) {
            // A compact screen gave the panel the whole work area, so opening a
            // file has to hand it back — and hand the keyboard back with it, or
            // the keymap dies the way it did when Stop-all closed the dock.
            dockTookWorkArea?.let(docks::closeDock)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * The git panel's "Resolve" on a conflicted file: open it and put the
     * caret on its first conflict, the way a diagnostics row opens on its
     * problem. A file git calls conflicted but that has no markers left —
     * resolved by hand and not yet staged — simply opens, and the banner
     * over it offers the staging.
     */
    fun openConflict(path: String) {
        val open = project ?: return
        openFile(open, path) { file -> file.editor?.goToFirstConflict() }
        if (dockTookWorkArea != null) {
            dockTookWorkArea?.let(docks::closeDock)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * A diagnostics row: open its file and put the caret on the problem,
     * exactly as a search hit is opened. A diagnostic in a file outside the
     * project keeps an absolute path the opener cannot resolve; [openFile]
     * fails it quietly, which matches what go-to-definition does with the
     * same paths.
     */
    fun openDiagnostic(path: String, diagnostic: Diagnostic) {
        val open = project ?: return
        openFile(open, path) { file -> file.editor?.revealDiagnosticTarget(diagnostic) }
        if (dockTookWorkArea != null) {
            dockTookWorkArea?.let(docks::closeDock)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * A path the terminal printed and the user followed. Inside the project
     * it opens at the line and column the text carried, as a search hit
     * does; outside it, the editor has nothing to offer — the engine works
     * on project paths — so the answer is a sentence rather than silence.
     * Zed's `open_path_like_target` opens anything on disk; that is the
     * difference a single-project workspace makes.
     */
    fun openTerminalPath(target: TerminalPathTarget) {
        val open = project ?: return
        val relative = projectRelativePath(target.absolutePath, open.rootPath)
        val file = File(target.absolutePath)
        val complaint = when {
            relative == null -> "${target.absolutePath} is outside the project"
            !file.exists() -> "$relative does not exist"
            file.isDirectory -> "$relative is a directory"
            else -> null
        }
        if (complaint != null) {
            Toast.makeText(context, complaint, Toast.LENGTH_SHORT).show()
            return
        }
        relative ?: return
        openFile(open, relative) { opened ->
            val row = target.row ?: return@openFile
            // Printed positions are 1-based; a column on its own is not a
            // position, so a missing one lands on the line's first character.
            val col = (target.column ?: 1) - 1
            opened.editor?.revealDefinitionTarget(
                DefinitionTarget(relative, row - 1, col.coerceAtLeast(0), row - 1, col.coerceAtLeast(0))
            )
        }
        // On a compact screen the terminal covers the work area, and a file
        // opened from it has to be seen — the same hand-back a search hit does.
        if (!isWide && terminals.isOpen) {
            terminals.hide()
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * A workspace edit — a rename, a quick fix, a formatting — landed
     * engine-side, and the receipt names every file it touched. The engine
     * changed those buffers *underneath* their editors, so each open one is
     * resynced ([EditorState.noteExternalEdit]), and every tab's dirty dot
     * re-read: an applied edit makes clean buffers dirty.
     */

    /**
     * Project search's replace-all edited open buffers through the engine,
     * the same way an applied workspace edit does, and rewrote closed files
     * on disk — which the engine's watcher reports as a disk change on any
     * buffer that has one, so every tab's status is re-read.
     */
    fun resyncAfterProjectReplace(receipt: ProjectReplaceReceipt) {
        resyncBuffers(receipt.bufferIds)
    }

    /**
     * A path a dock asked for — a changed file in the git panel, a relative
     * link in the preview.
     *
     * On a compact screen the dock *is* the work area: the tab strip and the
     * editor are not composed at all, so opening a file behind it looks like
     * nothing happening. Hand the screen back, exactly as a search hit does.
     */
    /**
     * A dock that *has* the work area gives it back — which is not always the
     * dock opened most recently, since a panel can be left holding the screen
     * after the newer one is dismissed. A no-op on a screen wide enough to
     * show the editor beside the panel, where nothing was ever covered.
     */
    fun handOverWorkArea() {
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    fun openFromDock(path: String, route: PreviewRoute = PreviewRoute.Permanent) {
        val open = project ?: return
        openFile(open, path, route)
        handOverWorkArea()
    }

    // A tap in the tree is a dock opening a file, and on a compact screen the
    // dock *is* the work area — so it hands the area back, exactly as a search
    // hit does. Without this the file opened behind the tree and nothing
    // looked like it had happened.
    val onOpenEntry: (ProjectEntry, Boolean) -> Unit = { entry, preview ->
        openFromDock(
            entry.path,
            if (preview) PreviewRoute.ProjectPanel else PreviewRoute.Permanent,
        )
    }

    /**
     * Show a diff — one file's, or the whole project's.
     *
     * A tab rather than a dock: a diff is a *document*, it is read left to
     * right and scrolled, and it belongs beside the file it is about. Keyed by
     * a path of its own so a diff and its file can be open at once.
     */
    fun openDiff(path: String?) {
        val open = project ?: return
        val target = DiffTarget(path)
        val key = "git-diff:${path ?: ""}"
        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
        } else {
            panes.files.open(OpenFile(path = key, editor = null, diff = target))
        }
        // The panel that asked may have been holding the whole screen.
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * Show the branch against its merge base with [base] — Zed's Branch Diff
     * tab ("Changes since {base}", branch_diff.rs:43), the git panel's "View
     * Branch Diff". Keyed by the base so a later deploy against the same base
     * reuses the tab.
     */
    fun openBranchDiff(base: String) {
        if (project == null) return
        val target = DiffTarget(path = null, mergeBase = base)
        val key = "git-branch-diff:$base"
        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
        } else {
            panes.files.open(OpenFile(path = key, editor = null, diff = target))
        }
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    /**
     * Show what one commit changed — Zed's CommitView, in [DiffPane]'s
     * clothes. [path] narrows it to one file (the graph sidebar's per-file
     * "View Changes"); the two are different tabs, as Zed's filtered view is.
     */
    fun openCommitDiff(sha: String, subject: String, path: String? = null) {
        if (project == null) return
        val target = DiffTarget(path = path, commit = sha, subject = subject)
        val key = "git-commit:$sha:${path ?: ""}"
        val existing = panes.files.indexOfPath(key)
        if (existing >= 0) {
            panes.files.select(existing)
        } else {
            panes.files.open(OpenFile(path = key, editor = null, diff = target))
        }
        dockTookWorkArea?.let { side ->
            docks.closeDock(side)
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    fun openProjectSearch(): Boolean {
        if (project == null) return false
        docks.open(WorkspacePanel.Search, settings)
        // The panel takes a compact screen away from a focused terminal, and
        // nothing else would tell the key table that the terminal is gone.
        terminalFocused = false
        projectSearchFocus++
        return true
    }

    /**
     * The tab bar's ⊞ menu — Zed's split popover (pane.rs:4353-4380) with
     * the join and zoom commands after a rule, since a finger has no other
     * way to those. The pane whose bar it is has just been activated by the
     * press, so the commands act on it.
     */
    fun paneSplitMenu(): List<ContextMenuItem> {
        val several = panes.panes.size > 1
        return listOf(
            ContextMenuItem("Split right", shortcutLabel(WorkspaceCommand.SplitRight)) {
                runCommand(WorkspaceCommand.SplitRight)
            },
            ContextMenuItem("Split left", shortcutLabel(WorkspaceCommand.SplitLeft)) {
                runCommand(WorkspaceCommand.SplitLeft)
            },
            ContextMenuItem("Split up", shortcutLabel(WorkspaceCommand.SplitUp)) {
                runCommand(WorkspaceCommand.SplitUp)
            },
            ContextMenuItem("Split down", shortcutLabel(WorkspaceCommand.SplitDown)) {
                runCommand(WorkspaceCommand.SplitDown)
            },
            ContextMenuItem("Join into next", enabled = several, separatorAbove = true) {
                runCommand(WorkspaceCommand.JoinIntoNext)
            },
            ContextMenuItem("Join all panes", enabled = several) {
                runCommand(WorkspaceCommand.JoinAll)
            },
            ContextMenuItem(
                if (panes.isZoomed) "Zoom out" else "Zoom in",
                shortcutLabel(WorkspaceCommand.ToggleZoom),
                enabled = panes.isZoomed || panes.files.active != null,
                separatorAbove = true,
            ) { runCommand(WorkspaceCommand.ToggleZoom) },
        )
    }

    // What the keymap could not use — Zed's "Errors in user keymap file"
    // notification, with its way in (settings/src/keymap_file.rs:195-199).
    // Keyed, so a reload that fixes nothing replaces the toast rather than
    // stacking a second copy, and a reload that fixes everything takes it
    // away. Below `runCommand` because its button runs one.
    LaunchedEffect(KeymapStore.errors, project == null) {
        val errors = KeymapStore.errors
        if (errors.isEmpty()) {
            Notifications.dismissKey(KEYMAP_NOTIFICATION)
            return@LaunchedEffect
        }
        Notifications.error(
            message = buildString {
                append("keymap.json: ")
                append(errors.first())
                if (errors.size > 1) append(" (+${errors.size - 1} more)")
            },
            action = if (project != null) {
                NotificationAction("Open keymap.json") {
                    runCommand(WorkspaceCommand.OpenKeymap)
                }
            } else {
                null
            },
            key = KEYMAP_NOTIFICATION,
        )
    }

    // settings.json that does not parse: the engine falls back to defaults
    // and the settings screen greys out, which on its own looks like the app
    // ignoring the file. Zed raises a notification for the same case.
    LaunchedEffect(settingsValid, project == null) {
        if (settingsValid) {
            Notifications.dismissKey(SETTINGS_NOTIFICATION)
            return@LaunchedEffect
        }
        Notifications.error(
            message = "settings.json does not parse — the defaults are in force until it does.",
            action = if (project != null) {
                NotificationAction("Open settings.json") {
                    runCommand(WorkspaceCommand.OpenSettingsFile)
                }
            } else {
                null
            },
            key = SETTINGS_NOTIFICATION,
        )
    }

    /**
     * The dragged tab was let go. A drop on an edge is a split, and a
     * compact screen that is already at two panes refuses it the way it
     * refuses the chord — with the sentence, and the tab back where it was.
     */
    fun dropDraggedTab() {
        val target = panes.dropTarget
        if (target?.direction != null && !canSplit()) {
            panes.cancelDrag()
            return
        }
        panes.finishDrag()
        panes.files.active?.editor?.requestFocus()
    }

    /**
     * Run what a keystroke resolved to. Returns whether it was taken: a
     * refusal — a command that cannot run now, a surface with nothing to
     * show — leaves the key to whatever is below, exactly as the old
     * per-command `return false`s did.
     *
     * Four kinds of name: the workspace's own surfaces ([WorkspaceAction]),
     * the palette's [WorkspaceCommand]s, the terminal dock's `terminal::`
     * actions and the editor's `editor::` ones — each answered by the thing
     * that has it.
     */
    fun runAction(binding: KeyBinding): Boolean {
        val action = binding.action ?: return false
        when (action) {
            // The palette is not a WorkspaceCommand: it would have to be
            // dispatched by the same `runCommand` it opens, and a command
            // that opens the list of commands is a knot for no gain.
            WorkspaceAction.CommandPalette -> {
                overlays.paletteOpen = true
                return true
            }
            WorkspaceAction.ProjectSearch -> return openProjectSearch()
            WorkspaceAction.GoToLine -> {
                // Ctrl+G is the git panel's chord leader while the panel
                // has the keyboard — Zed scopes its git chords to the
                // `GitPanel` context for exactly this collision
                // (default-linux.json:1060 vs :622) — so go-to-line
                // stands aside and the panel's own handler takes the key.
                if (gitPanelFocused) return false
                if (panes.files.active?.editor == null) return false
                overlays.goToLineOpen = true
                return true
            }
            WorkspaceAction.Outline -> {
                if (panes.files.active?.editor == null) return false
                overlays.outlineOpen = true
                return true
            }
            WorkspaceAction.ActivateItem -> {
                val index = binding.intArg ?: return false
                // Ctrl+1 and Ctrl+2 switch the *panel's* tabs while the git
                // panel has the keyboard (Zed's git_panel::ActivateChangesTab
                // / ActivateHistoryTab, default-linux.json:1010-1011); the
                // other digits still pick editor tabs from there.
                if (gitPanelFocused && index < 2) return false
                if (index !in panes.files.tabs.indices) return false
                panes.files.select(index)
                return true
            }
            WorkspaceAction.ActivateLastItem -> {
                if (panes.files.tabs.isEmpty()) return false
                panes.files.select(panes.files.tabs.lastIndex)
                return true
            }
            WorkspaceAction.ActivatePane -> {
                val index = binding.intArg ?: return false
                return activatePane { panes.activateAt(index) }
            }
        }
        WorkspaceCommand.byId(action)?.let { return runCommand(it) }
        if (action.startsWith("terminal::")) return terminals.dockAction?.invoke(action) ?: false
        if (action.startsWith("editor::")) return panes.files.active?.editor?.runAction(action) ?: false
        return false
    }

    /**
     * The one key pass: a key down, resolved against the keymap in force
     * for whatever [focus] has the keyboard, with the chord state shared
     * across surfaces. Returns whether the event is spoken for.
     */
    fun dispatchKey(event: AndroidKeyEvent, focus: Focus): Boolean {
        if (event.action != AndroidKeyEvent.ACTION_DOWN) return false
        // A modifier's own down-event names no keystroke and leaves a pending
        // chord waiting, so Ctrl pressed on the way to Ctrl+0 cannot end it.
        val stroke = Keystroke.of(event) ?: return false
        val editorFocused = focus == Focus.Workspace && panes.files.active?.editor?.isFocused == true
        // The composer is a text box in a dock, never the editor: while it
        // has the keys the `AgentPanel` section is in force over the
        // workspace's, which is what makes Ctrl+N a thread there.
        // A picture as the open tab puts the `ImageViewer` section in force
        // over the workspace's — its zoom chords share keys with the UI font
        // size and the first tab (default-linux.json:1566-1574) — unless the
        // git panel or the composer has the keyboard, whose own keys come
        // first.
        val imageFocused = focus == Focus.Workspace && !gitPanelFocused &&
            !agentComposerFocused && panes.files.active?.media == MediaKind.Image
        val contexts = KeymapContext.chainFor(
            focus,
            editorFocused,
            agentPanelFocused = focus == Focus.Workspace && agentComposerFocused,
            imageFocused = imageFocused,
        )
        chordContexts = contexts
        val press = chords.press(stroke, KeymapStore.keymap, contexts)
        val binding = press.binding ?: return press.consumed
        return runAction(binding)
    }

    /**
     * A key down at the workspace root, whichever pass delivered it: the
     * completion menu first, then the keymap. Returns whether it was taken.
     */
    fun handleWorkspaceKey(event: KeyEvent): Boolean {
        val focus = if (terminalFocused) Focus.Terminal else Focus.Workspace
        // The completion menu's keys come first and win, exactly as
        // Zed's `Editor && showing_completions` context outranks the
        // editor's own bindings for the same keys.
        val editor = panes.files.active?.editor?.takeIf { focus == Focus.Workspace && it.isFocused }
        if (editor?.keyInterceptor?.invoke(event) == true) return true
        return dispatchKey(event.nativeKeyEvent, focus)
    }

    // Zed waits one second for a chord's next stroke and then replays what
    // it had (gpui/src/window.rs:5418); a phone keyboard is slower, so 1.5.
    // Whatever the pending strokes already completed on their own runs then.
    LaunchedEffect(chords.pending) {
        if (chords.pending.isEmpty()) return@LaunchedEffect
        delay(CHORD_TIMEOUT_MS)
        chords.timeout()?.let { runAction(it) }
    }

    /** A shell in a directory — the panel's `project_panel::OpenInTerminal`. */
    fun openTerminalIn(absoluteDir: String) {
        terminals.newSession(absoluteDir)
        terminalFocused = true
    }

    /**
     * Project search seeded with an include glob — Zed's
     * `project_panel::NewSearchInDirectory`. The glob is handed to the panel
     * beside the focus token it already watches, so opening the panel and
     * seeding it are one act and cannot arrive out of order.
     */
    fun searchInDirectory(glob: String) {
        if (project == null) return
        projectSearchInclude = glob
        openProjectSearch()
    }

    // One counter for everything this project's servers have said. Polling it
    // is also what *starts* servers — for the languages the scanned tree
    // holds, tabs or no tabs, and for a file that was open before its project
    // or before `apt install` put the binary in the userland — so it runs
    // whenever a project is open, not only when the status bar has something
    // to show.
    //
    // Read up here rather than beside the status bar because three surfaces
    // want it now: the status bar's summary, `tabs.show_diagnostics` on the
    // strip, and `project_panel.show_diagnostics` on the tree.
    val lsp = rememberLspState(project?.id)
    // `tabs.git_status`: the strip tints its titles from the same table the
    // tree does, and reads nothing at all while the setting is off.
    val tabGitStatus = rememberGitStatuses(project, settings.tabs.gitStatus)
    val panelDiagnostics = remember(lsp.version, settings.projectPanel.showDiagnostics) {
        DiagnosticMarks.of(lsp.summary.files, settings.projectPanel.showDiagnostics)
    }
    val tabDiagnostics = remember(lsp.version, settings.tabs.showDiagnostics) {
        DiagnosticMarks.of(lsp.summary.files, settings.tabs.showDiagnostics)
    }

    LaunchedEffect(Unit) { rootFocus.requestFocus() }

    // The dock can close without anyone here asking it to: the foreground
    // service's "Stop all" ends every session from the notification shade, and
    // the composable that held focus — a TerminalView — simply disappears.
    // Compose does not hand that focus anywhere, so the whole keymap goes dead:
    // measured on the Fold, neither Ctrl+` nor Ctrl+P did anything afterwards,
    // and only clicking the status bar's terminal button brought the app back.
    // Whenever the dock is not open, focus belongs to the workspace.
    LaunchedEffect(terminals.isOpen) {
        if (!terminals.isOpen) {
            terminalFocused = false
            rootFocus.requestFocus()
        }
    }

    // ---- The back gesture ---------------------------------------------------
    //
    // One handler, one stack: back closes the topmost transient surface and
    // nothing else, and reaches the system — backgrounding the app — only
    // when there is nothing left to close. Without this a swipe from the
    // edge with the palette up put the whole IDE behind the launcher.
    //
    // Surfaces drawn in a window of their own — the pickers, the settings
    // screen, every `Dialog` — are not in this list: a dialog window has its
    // own back dispatcher and dismisses itself before this handler is asked.
    // What is here is everything drawn *inline* over the work area, in the
    // order it stacks: modals, then the find bar, then a focused terminal,
    // then a dock — and only on the compact layout, where the dock *is* the
    // work area. Side by side with the editor a dock is furniture, not a
    // surface, and back leaves it alone, as it does on a desktop.
    val compactTerminal = !isWide && terminals.isOpen && dockTookWorkArea == null
    val inlineModalOpen = overlays.paletteOpen || overlays.goToLineOpen ||
        overlays.outlineOpen || overlays.projectSymbolsOpen || overlays.renameOpen ||
        overlays.themeSelectorOpen || overlays.iconThemeSelectorOpen ||
        overlays.branchPickerOpen || overlays.stashPickerOpen ||
        overlays.serverPromptOpen
    val backCloses = inlineModalOpen || searchDeploy != null ||
        (terminals.isOpen && terminalFocused) || dockTookWorkArea != null || compactTerminal
    BackHandler(enabled = backCloses) {
        when {
            overlays.paletteOpen -> overlays.paletteOpen = false
            overlays.goToLineOpen -> overlays.goToLineOpen = false
            overlays.outlineOpen -> overlays.outlineOpen = false
            overlays.projectSymbolsOpen -> overlays.projectSymbolsOpen = false
            overlays.renameOpen -> overlays.renameOpen = false
            overlays.themeSelectorOpen -> overlays.themeSelectorOpen = false
            overlays.iconThemeSelectorOpen -> overlays.iconThemeSelectorOpen = false
            overlays.branchPickerOpen -> overlays.branchPickerOpen = false
            overlays.stashPickerOpen -> overlays.stashPickerOpen = false
            overlays.serverPromptOpen -> overlays.serverPromptOpen = false
            searchDeploy != null -> {
                panes.files.active?.editor?.clearSearchMatches()
                dismissSearch()
            }
            // The dock hides; the sessions in it keep running, exactly as
            // Ctrl+` leaves them. Escape inside the shell is untouched: the
            // terminal view does not map back to it (TerminalPane.kt), so
            // the gesture never reaches the pty.
            (terminals.isOpen && terminalFocused) || compactTerminal -> runCommand(WorkspaceCommand.ToggleTerminal)
            dockTookWorkArea != null -> dockTookWorkArea?.let(docks::closeDock)
        }
        // Compose hands focus nowhere when a surface leaves; the keymap
        // would go with it.
        terminalFocused = false
        rootFocus.requestFocus()
    }

    // A tap on the agent's notification: bring the panel forward, which is
    // what the tap promised. The request is a counter, so a second tap on a
    // later notification opens it again.
    LaunchedEffect(AgentSessions.openPanelRequest) {
        if (AgentSessions.openPanelRequest == 0 || project == null) return@LaunchedEffect
        if (!panelIsDrawn(WorkspacePanel.Agent)) togglePanel(WorkspacePanel.Agent)
        agentPanelFocus++
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            // The modifier chords are taken here, before the soft keyboard
            // sees the key at all — see [Keystroke.beforeIme]: Gboard
            // otherwise answers Ctrl+Backspace (and, with a selection or
            // extra carets on screen, Ctrl+Z) through the editor's
            // InputConnection with an idea of its own, and the keymap never
            // hears the chord. What no binding claims falls through to the
            // keyboard and then to the ordinary pass below, which skips an
            // event it already refused rather than resolve it twice.
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreInterceptKeyBeforeSoftKeyboard false
                val stroke = Keystroke.of(event.nativeKeyEvent)
                if (stroke == null || !stroke.beforeIme) return@onPreInterceptKeyBeforeSoftKeyboard false
                keySeenBeforeIme.event = event.nativeKeyEvent
                handleWorkspaceKey(event)
            }
            .onPreviewKeyEvent { event ->
                // The Ctrl+Tab switcher — Zed's `tab_switcher::Toggle`. It is
                // matched before the keymap because it is the one binding that
                // cares about a key going *up*: the overlay is held open while
                // Ctrl is down and commits on its release, which is what makes
                // repeated Ctrl+Tab walk the most-recently-used list instead of
                // the strip. The chord is in the keymap all the same, so it is
                // printed and rebindable; what it resolves to there is the
                // untimed half of the same thing.
                // Escape backs out of the switcher without switching — the
                // overlay is not focusable (see TabSwitcherOverlay), so this
                // pass is where its keys land.
                val switcherFocus = if (terminalFocused) Focus.Terminal else Focus.Workspace
                if (switcherTabs.isNotEmpty() &&
                    event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape
                ) {
                    switcherTabs = emptyList()
                    return@onPreviewKeyEvent true
                }
                if (switcherTabs.isNotEmpty() && isCtrlRelease(event)) {
                    val chosen = switcherTabs.getOrNull(switcherIndex)
                    switcherTabs = emptyList()
                    chosen?.let { panes.files.indexOfPath(it.path) }
                        ?.takeIf { it >= 0 }
                        ?.let(panes.files::select)
                    return@onPreviewKeyEvent true
                }
                if (isTabSwitcher(event, switcherFocus)) {
                    if (panes.files.tabs.size < 2) return@onPreviewKeyEvent false
                    if (switcherTabs.isEmpty()) {
                        switcherTabs = panes.files.mruTabs()
                        // The *second* entry: one press means "the file I was
                        // just in", which is the whole point of the gesture.
                        switcherIndex = if (event.nativeKeyEvent.isShiftPressed) {
                            switcherTabs.lastIndex
                        } else {
                            1
                        }
                    } else {
                        switcherIndex = mruStep(
                            switcherTabs.size,
                            switcherIndex,
                            if (event.nativeKeyEvent.isShiftPressed) -1 else 1,
                        )
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent === keySeenBeforeIme.event) {
                    keySeenBeforeIme.event = null
                    return@onPreviewKeyEvent false
                }
                handleWorkspaceKey(event)
            }
    ) {
        isWide = maxWidth >= WideLayoutMinWidth
        val windowWidth = maxWidth
        // The status bar spans the whole window, below the panel as well as
        // the editor — it reports on the workspace, not on the editor pane.
        val active = panes.files.active
        val menuGroups = listOf(
            listOf(
                MenuAction(stringResource(R.string.menu_new_project), null) {
                    refreshProjects(); transferError = null; overlays.pickerOpen = true
                },
                MenuAction(stringResource(R.string.menu_open_project), shortcutLabel(WorkspaceCommand.OpenProjects)) {
                    runCommand(WorkspaceCommand.OpenProjects)
                },
                MenuAction(stringResource(R.string.menu_import_folder), null) { importLauncher.launch(null) },
            ),
            listOf(
                MenuAction(stringResource(R.string.menu_search_all_files), shortcutLabel(WorkspaceAction.ProjectSearch), enabled = project != null) {
                    openProjectSearch()
                },
                MenuAction(stringResource(R.string.menu_find_file), shortcutLabel(WorkspaceCommand.FindFile), enabled = project != null) {
                    runCommand(WorkspaceCommand.FindFile)
                },
                MenuAction(
                    if (settings.softWrap.wraps) "Stop wrapping lines" else "Wrap long lines",
                    shortcutLabel(WorkspaceCommand.ToggleSoftWrap),
                ) {
                    runCommand(WorkspaceCommand.ToggleSoftWrap)
                },
                MenuAction(
                    if (settings.vimMode) "Turn off vim mode" else "Turn on vim mode",
                    shortcutLabel(WorkspaceCommand.ToggleVimMode),
                ) {
                    runCommand(WorkspaceCommand.ToggleVimMode)
                },
                MenuAction(stringResource(R.string.menu_git_graph), null, enabled = project != null) {
                    runCommand(WorkspaceCommand.OpenGitGraph)
                },
                MenuAction(
                    stringResource(R.string.menu_git_panel),
                    shortcutLabel(WorkspaceCommand.ToggleGitPanel),
                    enabled = project != null,
                ) {
                    runCommand(WorkspaceCommand.ToggleGitPanel)
                },
                MenuAction(
                    stringResource(R.string.menu_add_selection_to_agent_thread),
                    shortcutLabel(WorkspaceCommand.AddSelectionToThread),
                    enabled = isAgentPanelSupported && active?.editor?.hasSelection == true,
                ) {
                    runCommand(WorkspaceCommand.AddSelectionToThread)
                },
                MenuAction(
                    stringResource(R.string.menu_review_agent_changes),
                    shortcutLabel(WorkspaceCommand.OpenAgentDiff),
                    enabled = isAgentPanelSupported && project != null,
                ) {
                    runCommand(WorkspaceCommand.OpenAgentDiff)
                },
                MenuAction(
                    stringResource(R.string.menu_go_to_line),
                    shortcutLabel(WorkspaceAction.GoToLine),
                    enabled = active?.editor != null,
                ) {
                    overlays.goToLineOpen = true
                },
                MenuAction(
                    stringResource(R.string.menu_outline),
                    shortcutLabel(WorkspaceAction.Outline),
                    enabled = active?.editor != null,
                ) {
                    overlays.outlineOpen = true
                },
                MenuAction(
                    stringResource(R.string.menu_project_symbols),
                    shortcutLabel(WorkspaceCommand.ProjectSymbols),
                    enabled = project != null,
                ) {
                    runCommand(WorkspaceCommand.ProjectSymbols)
                },
                MenuAction(
                    stringResource(R.string.menu_toggle_preview),
                    shortcutLabel(WorkspaceCommand.TogglePreview),
                    enabled = canPreviewActiveFile(),
                ) {
                    runCommand(WorkspaceCommand.TogglePreview)
                },
                MenuAction(stringResource(R.string.menu_save), shortcutLabel(WorkspaceCommand.Save), enabled = active?.session != null) {
                    active?.let { save(it) }
                },
                MenuAction(stringResource(R.string.menu_save_all), null, enabled = panes.allTabs.any { it.isDirty }) {
                    for (tab in panes.allTabs) if (tab.isDirty) save(tab)
                },
                MenuAction(stringResource(R.string.menu_close_tab), shortcutLabel(WorkspaceCommand.CloseTab), enabled = active != null) {
                    runCommand(WorkspaceCommand.CloseTab)
                },
            ),
            listOf(
                // The palette's own route for anyone without a keyboard —
                // which on a phone is everyone, and it is the only way to
                // reach the commands this table cannot give a chord to.
                MenuAction(stringResource(R.string.menu_command_palette), shortcutLabel(WorkspaceAction.CommandPalette)) {
                    overlays.paletteOpen = true
                },
                MenuAction(stringResource(R.string.menu_reveal_in_project_panel), shortcutLabel(WorkspaceCommand.RevealInProjectPanel), enabled = active != null) {
                    runCommand(WorkspaceCommand.RevealInProjectPanel)
                },
                // The touch routes to the two project commands the picker
                // itself has no room for.
                MenuAction(stringResource(R.string.menu_open_recent_project), shortcutLabel(WorkspaceCommand.OpenRecent)) {
                    runCommand(WorkspaceCommand.OpenRecent)
                },
                MenuAction(
                    stringResource(R.string.menu_close_project),
                    shortcutLabel(WorkspaceCommand.CloseWindow),
                    enabled = project != null,
                ) {
                    runCommand(WorkspaceCommand.CloseWindow)
                },
                MenuAction(stringResource(R.string.menu_toggle_left_dock), shortcutLabel(WorkspaceCommand.ToggleLeftDock)) {
                    runCommand(WorkspaceCommand.ToggleLeftDock)
                },
                MenuAction(stringResource(R.string.menu_toggle_right_dock), shortcutLabel(WorkspaceCommand.ToggleRightDock)) {
                    runCommand(WorkspaceCommand.ToggleRightDock)
                },
                MenuAction(stringResource(R.string.menu_toggle_project_panel), shortcutLabel(WorkspaceCommand.ToggleProjectPanel)) {
                    runCommand(WorkspaceCommand.ToggleProjectPanel)
                },
                MenuAction(stringResource(R.string.menu_toggle_terminal), shortcutLabel(WorkspaceCommand.ToggleTerminal), enabled = project != null) {
                    runCommand(WorkspaceCommand.ToggleTerminal)
                },
                MenuAction(stringResource(R.string.menu_new_terminal), shortcutLabel(WorkspaceCommand.NewTerminal), enabled = project != null) {
                    runCommand(WorkspaceCommand.NewTerminal)
                },
                MenuAction(stringResource(R.string.menu_run_task), shortcutLabel(WorkspaceCommand.SpawnTask), enabled = project != null) {
                    runCommand(WorkspaceCommand.SpawnTask)
                },
                MenuAction(
                    stringResource(R.string.menu_rerun_last_task),
                    shortcutLabel(WorkspaceCommand.RerunTask),
                    enabled = project != null && TaskRuns.hasHistory,
                ) {
                    runCommand(WorkspaceCommand.RerunTask)
                },
                MenuAction(stringResource(R.string.menu_edit_tasks_json), null, enabled = project != null) {
                    runCommand(WorkspaceCommand.OpenTasks)
                },
                MenuAction(stringResource(R.string.menu_edit_project_tasks_zed_tasks_json), null, enabled = project != null) {
                    runCommand(WorkspaceCommand.OpenProjectTasks)
                },

                MenuAction(stringResource(R.string.menu_theme), shortcutLabel(WorkspaceCommand.SelectTheme)) {
                    runCommand(WorkspaceCommand.SelectTheme)
                },
                MenuAction(stringResource(R.string.menu_icon_theme), shortcutLabel(WorkspaceCommand.SelectIconTheme)) {
                    runCommand(WorkspaceCommand.SelectIconTheme)
                },
                MenuAction(stringResource(R.string.menu_welcome), shortcutLabel(WorkspaceCommand.OpenOnboarding)) {
                    runCommand(WorkspaceCommand.OpenOnboarding)
                },
                MenuAction(stringResource(R.string.menu_about), shortcutLabel(WorkspaceCommand.About)) {
                    runCommand(WorkspaceCommand.About)
                },
                MenuAction(stringResource(R.string.menu_settings), shortcutLabel(WorkspaceCommand.OpenSettings)) {
                    runCommand(WorkspaceCommand.OpenSettings)
                },
                // The screen above covers what has a row; the file covers
                // everything (agent_servers among it), and it lives where no
                // other editor on the device can reach it.
                MenuAction(stringResource(R.string.menu_edit_settings_json), null, enabled = project != null) {
                    runCommand(WorkspaceCommand.OpenSettingsFile)
                },
                MenuAction(stringResource(R.string.menu_edit_project_settings_zed_settings_json), null, enabled = project != null) {
                    runCommand(WorkspaceCommand.OpenProjectSettings)
                },
                MenuAction(stringResource(R.string.menu_open_default_settings), null, enabled = project != null) {
                    runCommand(WorkspaceCommand.OpenDefaultSettings)
                },
                // The keymap's touch route — Zed's menu carries "Open Keymap"
                // beside its settings for the same reason.
                MenuAction(
                    stringResource(R.string.menu_edit_keymap_json),
                    shortcutLabel(WorkspaceCommand.OpenKeymap),
                    enabled = project != null,
                ) {
                    runCommand(WorkspaceCommand.OpenKeymap)
                },
            ) + userlandActions(context) { overlays.removeUserlandOpen = true },
        )

        Column(modifier = Modifier.fillMaxSize()) {
            TitleBar(
                projectName = project?.rootName,
                // A tab with no buffer — a diff, the graph, a picture — has a
                // key rather than a path, and a name of its own; that is what
                // belongs in the title bar.
                filePath = active?.let { if (it.editor == null) it.name else it.path },
                isDirty = active?.isDirty == true,
                menuGroups = menuGroups,
                branch = rememberGitBranch(project),
                // Zed's title-bar branch button opens the branch picker, not
                // the panel (title_bar.rs:1050-1058).
                onBranch = if (project != null) {
                    { runCommand(WorkspaceCommand.SwitchBranch) }
                } else {
                    null
                },
            )
            DockDivider()
            // Compact screens have no room to split: the dock takes the whole
            // work area, and the docks decide between themselves how much of
            // it each gets — see planDocks, which is where that argument lives
            // and where its cases are tested.
            //
            // A dock whose subject has gone shows nothing rather than holding
            // the screen: the preview needs a buffer to follow (a picture *is*
            // the preview), and search and git need a project.
            docks.reconcile(settings)
            fun panelHasSubject(panel: WorkspacePanel): Boolean = when (panel) {
                WorkspacePanel.Preview -> canPreviewActiveFile()
                // An outline of a picture, a diff or the graph is an outline
                // of nothing: the panel needs a text buffer to read symbols
                // out of, exactly as the preview needs one to render.
                WorkspacePanel.Outline -> active?.editor != null
                WorkspacePanel.Search, WorkspacePanel.Git -> project != null
                // An agent runs in the userland and works on a project, so a
                // build without one never shows this panel at all.
                WorkspacePanel.Agent -> project != null && isAgentPanelSupported
                WorkspacePanel.Project -> true
            }
            val plan = planDocks(
                window = windowWidth,
                leftWanted = docks.left?.takeIf(::panelHasSubject)
                    ?.let { docks.leftWidth ?: it.widthIn(settings) },
                rightWanted = docks.right?.takeIf(::panelHasSubject)
                    ?.let { docks.rightWidth ?: it.widthIn(settings) },
                lastOpened = docks.lastOpened,
                minEditor = MinEditorWidth,
                minDock = DockMinWidth,
                canSplit = isWide,
            )
            dockTookWorkArea = plan.fullScreen
            drawnDocks = DockSide.docks.filter { plan.draws(it) }.toSet()
            val terminalIsFullScreen = !isWide && terminals.isOpen && plan.fullScreen == null
            Box(modifier = Modifier.weight(1f)) {
                val fullScreen = plan.fullScreen
                if (fullScreen != null) {
                    DockPanel(
                        panel = docks.active(fullScreen)!!,
                        project = project,
                        file = active,
                        settings = settings,
                        searchFocus = projectSearchFocus,
                        searchInclude = projectSearchInclude,
                        onSearchSeedApplied = { projectSearchInclude = null },
                        gitFocus = gitPanelFocus,
                        gitRequest = gitPanelRequest,
                        onGitRequestHandled = { gitPanelRequest = null },
                        onGitFocusChanged = { gitPanelFocused = it },
                        agentFocus = agentPanelFocus,
                        outlineFocus = outlinePanelFocus,
                        onOutlineJump = ::handOverWorkArea,
                        revealRequest = revealInPanel,
                        onRevealHandled = { revealInPanel = false },
                        onOpenEntry = onOpenEntry,
                        onOpenMatch = ::openMatch,
                        onProjectReplaced = ::resyncAfterProjectReplace,
                        onOpenSearchMultibuffer = ::openSearchMultibuffer,
                        multibufferIsDefault = isWide,
                        onOpenPath = ::openFromDock,
                        onOpenSettings = { runCommand(WorkspaceCommand.OpenSettings) },
                        onOpenAgentReview = ::openAgentReviewTab,
                        agentWorkspace = agentWorkspace,
                        onAgentFocusChanged = { agentComposerFocused = it },
                        onOpenDiff = ::openDiff,
                        onResolveConflict = ::openConflict,
                        onOpenBranchDiff = ::openBranchDiff,
                        onOpenCommit = { sha, subject -> openCommitDiff(sha, subject) },
                        onOpenGraph = ::openGraph,
                        onSwitchBranch = { runCommand(WorkspaceCommand.SwitchBranch) },
                        onViewStash = { runCommand(WorkspaceCommand.GitViewStash) },
                        onEntryRemoved = ::closeTabsUnder,
                        onEntryMoved = ::retitleTabs,
                        onAddFolder = { runCommand(WorkspaceCommand.AddFolderToProject) },
                        onPanelFocusChanged = { projectPanelFocused = it },
                        diagnostics = panelDiagnostics,
                        onOpenTerminal = ::openTerminalIn,
                        onSearchInDirectory = ::searchInDirectory,
                        openedPath = panes.files.active?.path,
                        onDismiss = {
                            docks.closeDock(fullScreen)
                            rootFocus.requestFocus()
                        },
                    )
                } else if (terminalIsFullScreen) {
                    TerminalDock(
                        state = terminals,
                        cwd = project?.rootPath,
                        fontSizeSp = bufferFontSizeSp,
                        onKey = { event -> dispatchKey(event, Focus.Terminal) },
                        onHide = { runCommand(WorkspaceCommand.ToggleTerminal) },
                        onSpawnTask = { runCommand(WorkspaceCommand.SpawnTask) },
                        onFocusChanged = { focused -> terminalFocused = focused },
                        onOpenPath = ::openTerminalPath,
                    )
                } else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        for (side in DockSide.docks) {
                            val panel = docks.active(side)?.takeIf { plan.draws(side) }
                            // The editor sits between the two, so it is drawn
                            // when the loop reaches the right-hand dock.
                            if (side == DockSide.Right) {
                                EditorArea(
                                    panes = panes,
                                    onActivatePane = panes::activate,
                                    onSplitTab = ::splitWithTab,
                                    splitMenu = ::paneSplitMenu,
                                    onToggleZoom = { runCommand(WorkspaceCommand.ToggleZoom) },
                                    onDragStart = { pane, file, position -> panes.startDrag(file, pane, position) },
                                    onDrag = panes::updateDrag,
                                    onDragEnd = ::dropDraggedTab,
                                    onDragCancel = panes::cancelDrag,
                                    dismissed = dismissedConflicts,
                                    onSave = ::save,
                                    onReload = ::reload,
                                    onStageResolved = ::stageResolved,
                                    stageError = resolvedStageError,
                                    onReopen = { runCommand(WorkspaceCommand.ReopenClosedTab) },
                                    onNavigateBack = { runCommand(WorkspaceCommand.GoBack) },
                                    onNavigateForward = { runCommand(WorkspaceCommand.GoForward) },
                                    onNewFile = if (project != null) {
                                        { runCommand(WorkspaceCommand.NewFile) }
                                    } else {
                                        null
                                    },
                                    searchDeploy = searchDeploy,
                                    onSearchDismissed = ::dismissSearch,
                                    onSearchFocusChanged = { searchBarFocused = it },
                                    onToggleSearch = {
                                        if (searchDeploy != null) dismissSearch() else deploySearch(replace = false)
                                    },
                                    onOpenOutline = { overlays.outlineOpen = true },
                                    onOpenProjectSymbols = { runCommand(WorkspaceCommand.ProjectSymbols) },
                                    isPreviewOpen = docks.isOpen(WorkspacePanel.Preview, settings),
                                    onTogglePreview = { runCommand(WorkspaceCommand.TogglePreview) },
                                    diffProject = project,
                                    diagnosticsFocus = diagnosticsFocus,
                                    onOpenDiagnostic = ::openDiagnostic,
                                    onOpenDiagnosticsMultibuffer = ::openDiagnosticsMultibuffer,
                                    onOpenExcerpt = { path, row ->
                                        val open = project
                                        if (open != null) {
                                            openFile(open, path) { file ->
                                                val editor = file.editor ?: return@openFile
                                                val at = row.coerceIn(
                                                    0,
                                                    (editor.lineCount - 1).coerceAtLeast(0),
                                                )
                                                editor.selectRange(
                                                    EditorState.SelectionRange(at, 0, at, 0)
                                                )
                                                withFrameNanos { }
                                                withFrameNanos { }
                                                editor.ensureCursorVisible()
                                            }
                                        }
                                    },
                                    onOpenPath = { path -> project?.let { openFile(it, path) } },
                                    onOpenCommit = ::openCommitDiff,
                                    imageZoom = imageZoom,
                                    onOpenDefinition = ::openDefinitionTarget,
                                    onWorkspaceEditApplied = ::resyncAfterWorkspaceEdit,
                                    onRenameSymbol = { overlays.renameOpen = true },
                                    onOpenWith = { file ->
                                        file.absolutePath?.let { ShareOut.openWith(context, File(it)) }
                                    },
                                    onShare = { file ->
                                        file.absolutePath?.let { ShareOut.share(context, File(it)) }
                                    },
                                    onRunnableTapped = if (project != null) ::spawnRunnable else null,
                                    // Vim's `:w`, `:q`, `:wq`, `:e` and
                                    // `ctrl-o`, each the same route the
                                    // command or the tab strip already takes.
                                    onVimSave = {
                                        val file = panes.files.active
                                        if (file?.session == null || file.isReadOnly) {
                                            false
                                        } else {
                                            save(file)
                                            true
                                        }
                                    },
                                    onCloseTab = { force ->
                                        val index = panes.files.activeIndex
                                        if (index < 0) {
                                            false
                                        } else {
                                            if (force) panes.files.close(index) else panes.files.requestClose(index)
                                            true
                                        }
                                    },
                                    onSaveAndClose = {
                                        val file = panes.files.active
                                        val open = file?.session
                                        if (file == null || open == null) {
                                            false
                                        } else {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { open.save() }
                                                file.refreshStatus()
                                                panes.files.indexOfPath(file.path).takeIf { it >= 0 }?.let(panes.files::requestClose)
                                            }
                                            true
                                        }
                                    },
                                    onVimOpenPath = { path ->
                                        val open = project
                                        if (open == null) {
                                            false
                                        } else {
                                            openFile(open, path.trimStart('/'))
                                            true
                                        }
                                    },
                                    onNavigate = { back -> navigateHistory(back) },
                                    tabSettings = settings.tabs,
                                    gitStatusOf = { path -> tabGitStatus.statusOf(path) },
                                    hasDiagnostics = { path ->
                                        tabDiagnostics.severityOf(path) != null
                                    },
                                    onOpenSwitcher = {
                                        runCommand(WorkspaceCommand.OpenTabSwitcher)
                                    },
                                    onOpenReferences = ::openReferencesMultibuffer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (panel != null) {
                                Dock(
                                    side = side,
                                    width = plan.widthOf(side),
                                    // The drag moves the width the dock *asked
                                    // for*, not the one it was given: when two
                                    // docks are sharing a tight screen the plan
                                    // shrinks both, and resizing from the
                                    // shrunk figure would fight the planner and
                                    // barely move.
                                    onResize = { delta ->
                                        val current = docks.width(side)
                                            ?: panel.widthIn(settings)
                                        docks.setWidth(
                                            side,
                                            (current + delta).coerceIn(
                                                DockMinWidth,
                                                (windowWidth - MinEditorWidth)
                                                    .coerceAtLeast(DockMinWidth),
                                            ),
                                        )
                                    },
                                ) {
                                    DockPanel(
                                        panel = panel,
                                        project = project,
                                        file = active,
                                        settings = settings,
                                        searchFocus = projectSearchFocus,
                                        searchInclude = projectSearchInclude,
                                        onSearchSeedApplied = {
                                            projectSearchInclude = null
                                        },
                                        gitFocus = gitPanelFocus,
                                        gitRequest = gitPanelRequest,
                                        onGitRequestHandled = { gitPanelRequest = null },
                                        onGitFocusChanged = { gitPanelFocused = it },
                                        agentFocus = agentPanelFocus,
                                        outlineFocus = outlinePanelFocus,
                                        onOutlineJump = ::handOverWorkArea,
                                        revealRequest = revealInPanel,
                                        onRevealHandled = { revealInPanel = false },
                                        onOpenEntry = onOpenEntry,
                                        onOpenMatch = ::openMatch,
                        onProjectReplaced = ::resyncAfterProjectReplace,
                        onOpenSearchMultibuffer = ::openSearchMultibuffer,
                        multibufferIsDefault = isWide,
                                        onOpenPath = ::openFromDock,
                                        onOpenSettings = {
                                            runCommand(WorkspaceCommand.OpenSettings)
                                        },
                                        onOpenAgentReview = ::openAgentReviewTab,
                                        agentWorkspace = agentWorkspace,
                                        onAgentFocusChanged = { agentComposerFocused = it },
                                        onOpenDiff = ::openDiff,
                                        onResolveConflict = ::openConflict,
                                        onOpenBranchDiff = ::openBranchDiff,
                                        onOpenCommit = { sha, subject ->
                                            openCommitDiff(sha, subject)
                                        },
                                        onOpenGraph = ::openGraph,
                                        onSwitchBranch = {
                                            runCommand(WorkspaceCommand.SwitchBranch)
                                        },
                                        onViewStash = {
                                            runCommand(WorkspaceCommand.GitViewStash)
                                        },
                                        onEntryRemoved = ::closeTabsUnder,
                                        onEntryMoved = ::retitleTabs,
                                        onPanelFocusChanged = {
                                            projectPanelFocused = it
                                        },
                                        diagnostics = panelDiagnostics,
                                        onOpenTerminal = ::openTerminalIn,
                                        onSearchInDirectory = ::searchInDirectory,
                                        onAddFolder = {
                                            runCommand(WorkspaceCommand.AddFolderToProject)
                                        },
                                        openedPath = panes.files.active?.path,
                                        onDismiss = {
                                            docks.closeDock(side)
                                            rootFocus.requestFocus()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // Over the editor, at the top, where Zed's own sits — and only
                // while the editor is the thing on screen: it moves the caret
                // as you type, which is pointless behind a full-screen panel.
                val goToLineEditor = active?.editor
                if (overlays.goToLineOpen && goToLineEditor != null &&
                    plan.fullScreen == null && !terminalIsFullScreen
                ) {
                    GoToLine(
                        editor = goToLineEditor,
                        onDismiss = {
                            overlays.goToLineOpen = false
                            rootFocus.requestFocus()
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                // Gated like go-to-line, and placed where it is: a rename is
                // about the caret's symbol, which a full-screen panel hides.
                val renameEditor = active?.editor
                if (overlays.renameOpen && renameEditor != null &&
                    plan.fullScreen == null && !terminalIsFullScreen
                ) {
                    RenameSymbol(
                        editor = renameEditor,
                        onApplied = ::resyncAfterWorkspaceEdit,
                        onDismiss = {
                            overlays.renameOpen = false
                            rootFocus.requestFocus()
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                // Gated like go-to-line: previewing moves the caret, which is
                // pointless — and invisible — behind a full-screen panel.
                val outlineEditor = active?.editor
                if (overlays.outlineOpen && outlineEditor != null &&
                    plan.fullScreen == null && !terminalIsFullScreen
                ) {
                    OutlinePicker(
                        editor = outlineEditor,
                        onDismiss = {
                            overlays.outlineOpen = false
                            rootFocus.requestFocus()
                        },
                    )
                }
                val taskRequest = overlays.taskPicker
                val taskProject = project
                if (taskRequest != null && taskProject != null) {
                    TaskPicker(
                        projectId = taskProject.id,
                        request = taskRequest,
                        onRun = { task ->
                            overlays.taskPicker = null
                            spawnTask(taskProject.rootPath, task)
                        },
                        onDismiss = {
                            overlays.taskPicker = null
                            rootFocus.requestFocus()
                        },
                    )
                }
                // The toast stack, over everything in the work area and under
                // nothing: a failure has to be readable with a panel, a
                // terminal or a modal on screen. Above the status bar on a
                // compact screen and out of the way at the top-right on a
                // wide one — see [NotificationHost].
                NotificationHost(
                    stack = Notifications,
                    isWide = isWide,
                    modifier = Modifier.align(
                        if (isWide) Alignment.TopEnd else Alignment.BottomCenter
                    ),
                )
                // The project's symbols, from its servers — needs a project,
                // not an editor; a pick opens the file the symbol lives in.
                val symbolsProject = project
                if (overlays.projectSymbolsOpen && symbolsProject != null &&
                    plan.fullScreen == null && !terminalIsFullScreen
                ) {
                    ProjectSymbolsPicker(
                        projectId = symbolsProject.id,
                        onOpen = ::openDefinitionTarget,
                        onDismiss = {
                            overlays.projectSymbolsOpen = false
                            rootFocus.requestFocus()
                        },
                    )
                }
            }
            if (terminals.isOpen && !terminalIsFullScreen && plan.fullScreen == null) {
                // Drag handle. Wide screens are where a paired mouse lives, so
                // it gets a resize cursor as well as a touch target.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .pointerHoverIcon(PointerIcon.Crosshair)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, delta ->
                                dockHeight = (dockHeight - delta.toDp())
                                    .coerceAtLeast(TerminalDockMinHeight)
                            }
                        }
                ) {
                    DockDivider()
                }
                TerminalDock(
                    state = terminals,
                    cwd = project?.rootPath,
                    fontSizeSp = bufferFontSizeSp,
                    onKey = { event -> dispatchKey(event, Focus.Terminal) },
                    onHide = { runCommand(WorkspaceCommand.ToggleTerminal) },
                    onSpawnTask = { runCommand(WorkspaceCommand.SpawnTask) },
                    onFocusChanged = { focused -> terminalFocused = focused },
                    onOpenPath = ::openTerminalPath,
                    modifier = Modifier.height(dockHeight),
                )
            }
            DockDivider()
            // Each panel's button sits with its dock, so moving a panel in
            // settings moves the way you reach it.
            val panelButtons = WorkspacePanel.entries
                .filter { it != WorkspacePanel.Preview || canPreviewActiveFile() }
                .filter { it != WorkspacePanel.Outline || active?.editor != null }
                .filter { it != WorkspacePanel.Project || project != null }
                .filter { it !in setOf(WorkspacePanel.Search, WorkspacePanel.Git) || project != null }
                .filter { it != WorkspacePanel.Agent || (project != null && isAgentPanelSupported) }
                .map { panel ->
                    PanelButton(
                        panel = panel,
                        isOpen = panelIsDrawn(panel),
                        onClick = {
                            when (panel) {
                                WorkspacePanel.Search -> if (docks.isOpen(panel, settings)) {
                                    togglePanel(panel)
                                } else {
                                    openProjectSearch()
                                }
                                WorkspacePanel.Git -> runCommand(WorkspaceCommand.ToggleGitPanel)
                                WorkspacePanel.Preview ->
                                    runCommand(WorkspaceCommand.TogglePreview)
                                WorkspacePanel.Outline ->
                                    runCommand(WorkspaceCommand.ToggleOutlinePanel)
                                WorkspacePanel.Project ->
                                    runCommand(WorkspaceCommand.ToggleProjectPanel)
                                WorkspacePanel.Agent ->
                                    runCommand(WorkspaceCommand.ToggleAgentPanel)
                            }
                        },
                    )
                }
            // `lsp` is read once, above the layout: three surfaces want it —
            // the status bar's summary, `tabs.show_diagnostics` on the strip
            // and `project_panel.show_diagnostics` on the tree.
            //
            // A server that *was* running and is now unavailable did not fail
            // to install — it died. Zed reports that as a notification with a
            // way into the log (activity_indicator.rs:594-626); the status
            // bar's note cannot, because it says the same thing for a server
            // that was never there.
            val serverStates = remember(project?.id) { mutableMapOf<String, LspServerState>() }
            LaunchedEffect(lsp.servers, project?.id) {
                for (server in lsp.servers) {
                    val was = serverStates.put(server.name, server.state)
                    if (was != LspServerState.Running) continue
                    if (server.state != LspServerState.Unavailable || server.stopped) continue
                    Notifications.error(
                        message = "${server.name} stopped: ${server.error ?: "the server exited"}",
                        action = NotificationAction("Show log") { openLspLogsTab(server.name) },
                        key = "$LSP_CRASH_NOTIFICATION:${server.name}",
                    )
                }
            }
            // Zed's which_key: what a half-pressed chord could still become.
            // Above the status bar, which is where the pending strokes are
            // already printed, so the two halves of the same moment are
            // together.
            WhichKeyOverlay(pending = chords.pending, contexts = chordContexts)
            val vim = active?.editor?.vim
            val activeLanguage = active?.language?.let { Languages.displayName(it) }
            StatusBar(
                // Vim's cursor when the layer is on: in a visual mode it is
                // the character under the selection's end, not the caret
                // past it, and that is the column the bar should print.
                cursorRow = vim?.cursor()?.row ?: active?.editor?.cursorRow ?: 0,
                cursorCol = vim?.cursor()?.col ?: active?.editor?.cursorCol ?: 0,
                language = activeLanguage,
                onSelectLanguage = { runCommand(WorkspaceCommand.SelectLanguage) },
                toolchain = statusBarToolchain(activeToolchains, activeLanguage),
                onSelectToolchain = { runCommand(WorkspaceCommand.SelectToolchain) },
                hasFile = active?.editor != null,
                // Zed's cursor-position item opens the go-to-line picker
                // (cursor_position.rs:216-222); ours is the same button, and
                // the only way to reach `ctrl-g` with no keyboard attached.
                onSelectCursorPosition = { overlays.goToLineOpen = true },
                lineEnding = active?.lineEnding?.label,
                onSelectLineEnding = { runCommand(WorkspaceCommand.SelectLineEnding) },
                encoding = active?.encoding?.label,
                onSelectEncoding = { runCommand(WorkspaceCommand.SelectEncoding) },
                pendingKeys = chords.pending
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" ") { it.label },
                vimMode = vim?.mode?.label,
                vimPending = vim?.pendingKeys.orEmpty(),
                onVimEscape = vim?.let { { it.handleKey("escape") } },
                leftPanels = panelButtons.filter { it.panel.sideIn(settings) == DockSide.Left },
                rightPanels = panelButtons.filter { it.panel.sideIn(settings) == DockSide.Right },
                isTerminalOpen = terminals.isOpen,
                onToggleTerminal = if (project != null) {
                    { runCommand(WorkspaceCommand.ToggleTerminal) }
                } else {
                    null
                },
                // Zed's diagnostic summary and its LSP button, both of which
                // it registers as *left* items (zed.rs:640-641). Null with no
                // project: there is nothing to summarise, and Zed hides the
                // indicator in the same case.
                diagnostics = if (project != null) lsp.summary else null,
                servers = lsp.servers,
                // Zed's activity indicator, another left item: the worktree
                // scan, a project search, a git command, a running task, and
                // the language servers' own progress folded in.
                activities = Activities.all,
                onActivity = { target ->
                    when (target) {
                        ActivityTarget.ProjectPanel ->
                            runCommand(WorkspaceCommand.ToggleProjectPanel)
                        ActivityTarget.ProjectSearch -> openProjectSearch()
                        ActivityTarget.GitPanel -> runCommand(WorkspaceCommand.ToggleGitPanel)
                        ActivityTarget.Terminal -> runCommand(WorkspaceCommand.ToggleTerminal)
                        ActivityTarget.LanguageServerLogs ->
                            runCommand(WorkspaceCommand.OpenLanguageServerLogs)
                        null -> Unit
                    }
                },
                cursorDiagnostic = active?.editor?.diagnosticAtCursor(),
                onGoToDiagnostic = active?.editor?.let { it::goToNextDiagnostic },
                onOpenDiagnostics = if (project != null) {
                    { runCommand(WorkspaceCommand.OpenDiagnostics) }
                } else {
                    null
                },
                onInstallServer = { server: LspServer ->
                    // The grammar the server is actually registered against
                    // beats the table's first one: clangd opened from a .cpp
                    // file should offer C++.
                    serverPromptGrammar =
                        server.languages.firstOrNull() ?: grammarForServer(server.name)
                    overlays.serverPromptOpen = true
                }.takeIf { LanguageServerInstaller.isSupported },
                // Zed's LspButton menu, per server (lsp_button.rs).
                onRestartServer = project?.let { open ->
                    { server: LspServer ->
                        scope.launch(Dispatchers.IO) { CoreBridge.lspRestartServer(open.id, server.name) }
                    }
                },
                onStopServer = project?.let { open ->
                    { server: LspServer ->
                        scope.launch(Dispatchers.IO) { CoreBridge.lspStopServer(open.id, server.name) }
                    }
                },
                onShowServerLogs = project?.let { { server: LspServer -> openLspLogsTab(server.name) } },
            )
        }
    }

    if (overlays.settingsOpen) {
        SettingsScreen(
            settings = settings,
            settingsPath = settingsPath,
            isFileValid = settingsValid,
            refusal = settingsRefusal,
            // The settings screen owns the mode; the *names* are the theme
            // selector's, because it previews each one on the file behind it.
            onOpenThemeSelector = {
                overlays.settingsOpen = false
                overlays.themeSelectorOpen = true
            },
            onSet = { keyPath, valueJson ->
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AppSettings.set(keyPath, valueJson)
                    }
                    if (updated != null) {
                        onSettingsChanged(updated)
                        settingsValid = true
                        settingsRefusal = null
                    } else {
                        // The engine refuses a write that would leave the file
                        // unparseable, and settings.json is untouched. Saying
                        // nothing here is what made a broken command — the
                        // soft-wrap toggle, which sent a malformed value —
                        // look like one that simply did nothing.
                        settingsRefusal = "$keyPath could not be set to $valueJson. " +
                            "settings.json is unchanged."
                    }
                }
            },
            onDismiss = {
                overlays.settingsOpen = false
                settingsRefusal = null
            },
            onEditFile = if (project != null && settingsPath != null) {
                {
                    overlays.settingsOpen = false
                    settingsRefusal = null
                    runCommand(WorkspaceCommand.OpenSettingsFile)
                }
            } else {
                null
            },
            onOpenDefaultSettings = if (project != null) {
                {
                    overlays.settingsOpen = false
                    settingsRefusal = null
                    runCommand(WorkspaceCommand.OpenDefaultSettings)
                }
            } else {
                null
            },
            onOpenProjectSettings = if (project != null) {
                {
                    overlays.settingsOpen = false
                    settingsRefusal = null
                    runCommand(WorkspaceCommand.OpenProjectSettings)
                }
            } else {
                null
            },
            keymapErrors = KeymapStore.errors,
            onEditKeymap = if (project != null && keymapPath != null) {
                {
                    overlays.settingsOpen = false
                    settingsRefusal = null
                    runCommand(WorkspaceCommand.OpenKeymap)
                }
            } else {
                null
            },
            // The External Agents section — absent entirely in the `play`
            // edition, which has no userland to run an agent in.
            onSaveAgent = if (isAgentPanelSupported) {
                { originalName, name, command, args ->
                    scope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            // A rename removes the old entry first, as Zed's
                            // form does (external_agents_page.rs:762-769); an
                            // edit keeps the entry's env, which the form does
                            // not carry.
                            if (originalName != null && originalName != name) {
                                AppSettings.removeAgent(originalName)
                            }
                            val env = settings.agents
                                .firstOrNull { it.name == originalName }?.env.orEmpty()
                            AppSettings.saveAgent(name, command, args, env)
                        }
                        if (updated != null) {
                            onSettingsChanged(updated)
                            settingsRefusal = null
                        } else {
                            settingsRefusal =
                                "The agent \"$name\" could not be written to settings.json."
                        }
                    }
                }
            } else {
                null
            },
            onRemoveAgent = { name ->
                scope.launch {
                    val updated = withContext(Dispatchers.IO) { AppSettings.removeAgent(name) }
                    if (updated != null) {
                        onSettingsChanged(updated)
                        settingsRefusal = null
                    } else {
                        settingsRefusal =
                            "The agent \"$name\" could not be removed from settings.json."
                    }
                }
            },
            onSaveContextServer = if (isAgentPanelSupported) {
                { originalName, server ->
                    scope.launch {
                        val updated = withContext(Dispatchers.IO) {
                            // A rename removes the old entry first, as the
                            // agent form does.
                            if (originalName != null && originalName != server.name) {
                                AppSettings.removeContextServer(originalName)
                            }
                            AppSettings.saveContextServer(server)
                        }
                        if (updated != null) {
                            onSettingsChanged(updated)
                            settingsRefusal = null
                        } else {
                            settingsRefusal =
                                "The context server \"${server.name}\" could not be written to settings.json."
                        }
                    }
                }
            } else {
                null
            },
            onRemoveContextServer = { name ->
                scope.launch {
                    val updated = withContext(Dispatchers.IO) { AppSettings.removeContextServer(name) }
                    if (updated != null) {
                        onSettingsChanged(updated)
                        settingsRefusal = null
                    } else {
                        settingsRefusal =
                            "The context server \"$name\" could not be removed from settings.json."
                    }
                }
            },
            onAbout = {
                overlays.settingsOpen = false
                settingsRefusal = null
                overlays.aboutOpen = true
            },
            onOpenOnboarding = {
                overlays.settingsOpen = false
                settingsRefusal = null
                overlays.onboardingOpen = true
            },
        )
    }

    // The welcome screen: once on a fresh install, and whenever asked for
    // afterwards. One boolean out of app preferences, asked once — nothing
    // else writes it — and off the main thread, because the first touch of a
    // SharedPreferences file reads the disk.
    LaunchedEffect(Unit) {
        val seen = withContext(Dispatchers.IO) { OnboardingState.hasBeenSeen(context) }
        if (!seen) overlays.onboardingOpen = true
    }
    if (overlays.onboardingOpen) {
        OnboardingScreen(
            settings = settings,
            onSet = { keyPath, valueJson ->
                scope.launch {
                    withContext(Dispatchers.IO) { AppSettings.set(keyPath, valueJson) }
                        ?.let(onSettingsChanged)
                }
            },
            onOpenThemeSelector = {
                overlays.onboardingOpen = false
                overlays.themeSelectorOpen = true
            },
            onOpenProjects = {
                overlays.onboardingOpen = false
                runCommand(WorkspaceCommand.OpenProjects)
            },
            onOpenPalette = {
                overlays.onboardingOpen = false
                overlays.paletteOpen = true
            },
            // No userland, no shell worth advertising: the row would be a
            // promise this edition cannot keep.
            onOpenTerminal = if (Userland.backend.isSupported && project != null) {
                {
                    overlays.onboardingOpen = false
                    runCommand(WorkspaceCommand.ToggleTerminal)
                }
            } else {
                null
            },
            onDismiss = { dontShowAgain ->
                if (dontShowAgain) OnboardingState.markSeen(context)
                overlays.onboardingOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.aboutOpen) {
        AboutDialog(onDismiss = {
            overlays.aboutOpen = false
            rootFocus.requestFocus()
        })
    }

    val openedProject = project
    if (overlays.finderOpen && openedProject != null) {
        FileFinder(
            project = openedProject,
            onOpen = { match ->
                overlays.finderOpen = false
                // `projectPath` carries the folder's name for a hit outside
                // the project's own folder; it is `path` for everything else.
                openFile(
                    openedProject,
                    match.projectPath.ifEmpty { match.path },
                    PreviewRoute.FileFinder,
                )
            },
            onDismiss = { overlays.finderOpen = false },
        )
    }

    if (overlays.branchPickerOpen && openedProject != null) {
        BranchPicker(
            project = openedProject,
            onDismiss = {
                overlays.branchPickerOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.stashPickerOpen && openedProject != null) {
        StashPicker(
            project = openedProject,
            onDismiss = {
                overlays.stashPickerOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.themeSelectorOpen) {
        ThemeSelector(
            selection = settings.themeSelection,
            // The whole `theme` value, never one of its keys: written a key
            // at a time it would spend a moment as `{"mode": …}` with no
            // names, which does not parse — and the engine answers a file
            // that does not parse with the defaults.
            onSelectionChange = { selection ->
                onSettingsChanged(settings.copy(themeSelection = selection))
                scope.launch(Dispatchers.IO) {
                    AppSettings.set(AppSettings.KEY_THEME, selection.toJson())
                        ?.let { updated -> withContext(Dispatchers.Main) { onSettingsChanged(updated) } }
                }
            },
            onDismiss = {
                overlays.themeSelectorOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.iconThemeSelectorOpen) {
        IconThemeSelector(
            selection = settings.iconTheme,
            isDark = settings.themeSelection.isDark(isSystemInDarkTheme())
                ?: LocalZedTheme.current.isDark,
            onSelectionChange = { selection ->
                onSettingsChanged(settings.copy(iconTheme = selection))
                scope.launch(Dispatchers.IO) {
                    AppSettings.set(AppSettings.KEY_ICON_THEME, selection.toJson())
                        ?.let { updated -> withContext(Dispatchers.Main) { onSettingsChanged(updated) } }
                }
            },
            onDismiss = {
                overlays.iconThemeSelectorOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    val shapeTarget = panes.files.active?.takeIf { it.session != null }
    if (overlays.lineEndingSelectorOpen && shapeTarget != null) {
        LineEndingSelector(
            current = shapeTarget.lineEnding,
            onSelect = { lineEnding -> setLineEnding(shapeTarget, lineEnding) },
            onDismiss = {
                overlays.lineEndingSelectorOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    val addFolderTarget = project
    if (overlays.addFolderOpen && addFolderTarget != null) {
        AddFolderDialog(
            // Projects already on the device that are not folders of this one:
            // those can be added where they are, since they are in app storage
            // already. Anything else has to be copied in first.
            candidates = projects.filter { candidate ->
                folders.none { it.path == candidate.path } &&
                    candidate.path != addFolderTarget.rootPath
            },
            onAddExisting = { candidate -> addFolder(candidate.path) },
            onImport = {
                importAsFolder = true
                importLauncher.launch(null)
            },
            onDismiss = {
                overlays.addFolderOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.removeFolderOpen) {
        RemoveFolderDialog(
            folders = folders.filter { !it.isPrimary },
            onRemove = { folder ->
                overlays.removeFolderOpen = false
                val open = project ?: return@RemoveFolderDialog
                scope.launch {
                    val failure = withContext(Dispatchers.IO) { open.removeWorktree(folder.id) }
                    if (failure != null) {
                        transferError = failure
                    } else {
                        folders = withContext(Dispatchers.Default) { open.worktrees() }
                    }
                }
            },
            onDismiss = {
                overlays.removeFolderOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.languageSelectorOpen && shapeTarget != null) {
        LanguageSelector(
            current = shapeTarget.language,
            onSelect = { language -> setLanguage(shapeTarget, language) },
            onDismiss = {
                overlays.languageSelectorOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    val toolchainProject = project
    if (overlays.toolchainSelectorOpen && toolchainProject != null) {
        val open = toolchainProject
        ToolchainSelector(
            projectId = open.id,
            onSelected = { toolchain ->
                scope.launch {
                    // A pick replaces the choice for its own language and
                    // leaves the others alone — a Rust toolchain does not
                    // disturb a virtualenv. "None" names no language, so it
                    // clears every one of them, which is the only reading of
                    // a row that says the project has no toolchain.
                    val cleared = withContext(Dispatchers.IO) {
                        if (toolchain != null) {
                            Toolchains.select(open.id, toolchain.language, toolchain)
                        } else {
                            Toolchains.active(open.id)
                                .forEach { Toolchains.select(open.id, it.language, null) }
                        }
                        Toolchains.active(open.id)
                    }
                    activeToolchains = cleared
                    Notifications.info(
                        message = toolchain?.let { "Toolchain: ${it.name}" }
                            ?: "Toolchain cleared",
                    )
                }
            },
            onDismiss = {
                overlays.toolchainSelectorOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.encodingSelectorOpen && shapeTarget != null) {
        EncodingSelector(
            current = shapeTarget.encoding,
            isDirty = shapeTarget.isDirty,
            onReopen = { encoding -> reopenWithEncoding(shapeTarget, encoding) },
            onSaveWith = { encoding -> saveWithEncoding(shapeTarget, encoding) },
            onDismiss = {
                overlays.encodingSelectorOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    // The Ctrl+Tab overlay. Outside the layout box on purpose: it is a
    // window-level modal like the palette, and the pane it names may be under
    // a full-screen dock. Its tabs are the active pane's, as the chord's are.
    if (switcherTabs.isNotEmpty()) {
        TabSwitcherOverlay(
            tabs = switcherTabs,
            selected = switcherIndex,
            showFileIcons = settings.tabs.fileIcons,
            onSelect = { index ->
                val chosen = switcherTabs.getOrNull(index)
                switcherTabs = emptyList()
                chosen?.let { panes.files.indexOfPath(it.path) }
                    ?.takeIf { it >= 0 }
                    ?.let(panes.files::select)
                rootFocus.requestFocus()
            },
            onDismiss = {
                switcherTabs = emptyList()
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.paletteOpen) {
        CommandPalette(
            workspace = CommandContext(
                hasProject = project != null,
                hasActiveFile = panes.files.active != null,
                hasActiveBuffer = panes.files.active?.editor != null,
                hasActiveImage = panes.files.active?.media == MediaKind.Image,
                tabCount = panes.files.tabs.size,
                terminalCount = terminals.sessions.size,
                canClone = GitClone.isSupported,
                canInstallLanguageServer = LanguageServerInstaller.isSupported,
                canUseAgent = isAgentPanelSupported,
                canPreview = canPreviewActiveFile(),
                hasConflicts = panes.files.active?.editor?.conflicts?.isNotEmpty() == true,
                canGoBack = panes.files.canGoBack,
                canGoForward = panes.files.canGoForward,
                searchBarOpen = searchDeploy != null,
                activeFileOnDisk = panes.files.active?.absolutePath != null,
                hasRunTask = TaskRuns.hasHistory,
                hasNotifications = Notifications.all.isNotEmpty(),
                hasExtraFolders = folders.any { !it.isPrimary },
                paneCount = panes.panes.size,
                isZoomed = panes.isZoomed,
                isMultibuffer = panes.files.active?.multibuffer != null,
                hiddenPanels = WorkspacePanel.entries
                    .filter { it.sideIn(settings) == DockSide.Hidden }
                    .map { it.settingsKey }
                    .toSet(),
            ),
            onRun = { runCommand(it) },
            onDismiss = {
                overlays.paletteOpen = false
                // Compose hands focus nowhere when an overlay leaves, and the
                // whole keymap goes with it — the same failure the terminal's
                // Stop-all once caused.
                rootFocus.requestFocus()
            },
            keyboardFocus = if (terminalFocused) Focus.Terminal else Focus.Workspace,
        )
    }

    if (overlays.removeUserlandOpen) {
        val name = Userland.backend.displayName
        AlertDialog(
            onDismissRequest = { overlays.removeUserlandOpen = false },
            title = { Text("Remove the $name userland?") },
            text = {
                Text(
                    "Everything installed with apt is deleted and the terminal " +
                        "goes back to Android's own shell. Your projects are not " +
                        "part of the userland and are left alone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    overlays.removeUserlandOpen = false
                    scope.launch {
                        // Sessions are running inside it; they have to go first.
                        terminals.closeAll()
                        withContext(Dispatchers.IO) {
                            Userland.backend.remove(context)
                            // Or the engine keeps pointing git at a rootfs
                            // that is no longer there.
                            CoreBridge.clearUserland()
                        }
                        // And tell the installer, which is what the terminal's
                        // banner and the ☰ entry both read. Without this the
                        // dock goes on believing there is a userland — it only
                        // asks the disk when the pane is first composed — so
                        // the offer to install never came back and the shell
                        // sat at a prompt inside a rootfs that no longer
                        // existed.
                        UserlandInstaller.refresh(context)
                    }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { overlays.removeUserlandOpen = false }) { Text("Cancel") }
            },
        )
    }

    // A credential question from any git command that can ask one — the
    // panel's fetch, pull and push, the picker's clone. At the root so it
    // sits above whichever of them is on screen; it shows itself only while
    // one is running and has asked.
    AskpassDialog()

    if (overlays.pickerOpen) {
        ProjectPicker(
            startInClone = pickerStartsInClone,
            onCloned = { path ->
                overlays.pickerOpen = false
                pickerStartsInClone = false
                refreshProjects()
                openProject(path)
            },
            projects = projects,
            currentPath = project?.let { session -> projects.firstOrNull { it.name == session.rootName }?.path },
            busyMessage = transferMessage,
            errorMessage = transferError,
            onOpen = { summary ->
                overlays.pickerOpen = false
                openProject(summary.path)
            },
            onCreate = { name ->
                scope.launch {
                    val created = withContext(Dispatchers.IO) { ProjectsRoot.create(context, name) }
                    if (created == null) {
                        transferError = "Could not create that project"
                    } else {
                        overlays.pickerOpen = false
                        openProject(created.absolutePath)
                    }
                }
            },
            onImport = { importLauncher.launch(null) },
            onExport = { summary ->
                exportTarget = summary
                exportLauncher.launch(null)
            },
            onDelete = { summary ->
                scope.launch {
                    val wasCurrent = project?.rootName == summary.name
                    if (wasCurrent) sessionReady = null
                    withContext(Dispatchers.IO) {
                        ProjectsRoot.delete(context, summary.name)
                        // Its saved workspace and its place in the recent
                        // list go with it: both name a directory that is no
                        // longer there.
                        recents = RecentProject.parseList(
                            CoreBridge.removeRecentProject(summary.path)
                        )
                    }
                    refreshProjects()
                    if (wasCurrent) {
                        val next = withContext(Dispatchers.IO) { ProjectsRoot.defaultProject(context) }
                        openProject(next)
                    }
                }
            },
            // With no project open there is nothing behind the picker to go
            // back to — `restore_on_startup: "none"` and CloseWindow both
            // land here — so it stays until one is chosen.
            onDismiss = { if (project != null) overlays.pickerOpen = false },
            nameError = { name -> ProjectsRoot.nameError(context, name) },
        )
    }

    if (overlays.recentOpen) {
        RecentProjectsPicker(
            projects = recents,
            currentPath = project?.rootPath,
            onOpen = { recent ->
                overlays.recentOpen = false
                openProject(recent.path)
            },
            onRemove = { recent ->
                scope.launch {
                    recents = withContext(Dispatchers.IO) {
                        RecentProject.parseList(CoreBridge.removeRecentProject(recent.path))
                    }
                }
            },
            onDismiss = {
                overlays.recentOpen = false
                rootFocus.requestFocus()
            },
        )
    }

    if (overlays.serverPromptOpen) {
        LanguageServerPrompt(
            grammar = serverPromptGrammar,
            onDismiss = {
                overlays.serverPromptOpen = false
                // Compose hands focus nowhere when an overlay leaves, and the
                // whole keymap goes with it.
                rootFocus.requestFocus()
            },
        )
    }

    val newFileProject = project
    if (overlays.newFileOpen && newFileProject != null) {
        EntryNameDialog(
            title = "NEW FILE",
            confirmLabel = "Create",
            initial = "",
            selectionEnd = 0,
            placeholder = "Name, or a path like src/main.rs",
            // A trailing slash is how one says "directory", and this dialog
            // only makes files — refuse it rather than quietly making a file
            // with a slash-shaped name that then blocks the directory.
            errorFor = { name ->
                if (name.trimEnd().endsWith('/')) {
                    "This makes a file — drop the trailing slash"
                } else {
                    ProjectFiles.pathError(name, File(newFileProject.rootPath))
                }
            },
            onConfirm = { name ->
                overlays.newFileOpen = false
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        ProjectFiles.create(File(newFileProject.rootPath), "", name, isDir = false)
                    }
                    when (result) {
                        is FileOpResult.Done -> openFile(newFileProject, result.path)
                        // The validation runs before the create and the disk
                        // can move underneath it; a create that fails has to
                        // say so rather than closing on nothing.
                        is FileOpResult.Failed -> newFileError = result.reason
                    }
                    rootFocus.requestFocus()
                }
            },
            onDismiss = {
                overlays.newFileOpen = false
                rootFocus.requestFocus()
            },
        )
    }
    newFileError?.let { message ->
        PanelErrorDialog(message = message) {
            newFileError = null
            rootFocus.requestFocus()
        }
    }

    // "Add <name> to <project>?" — the staged share waiting for a home.
    val importProject = project
    val staged = pendingImport
    if (staged != null && importProject != null) {
        val root = File(importProject.rootPath)
        ImportFileDialog(
            files = staged,
            projectName = importProject.rootName,
            // A clash is not an error — the file gets ` copy` on landing —
            // so the check is the path's shape alone, not what is there.
            errorFor = { path ->
                if (path.trimEnd().endsWith('/')) {
                    "This names a file — drop the trailing slash"
                } else {
                    ProjectFiles.pathError(path)
                }
            },
            onConfirm = { destination ->
                pendingImport = null
                placeImport(root, destination, staged) { paths ->
                    paths.forEach { openFile(importProject, it) }
                }
            },
            onScratch = {
                pendingImport = null
                importToScratch(staged)
            },
            onDismiss = {
                pendingImport = null
                // The bytes were staged on our side; a cancel drops them.
                scope.launch(Dispatchers.IO) { staged.forEach { it.temp.delete() } }
                rootFocus.requestFocus()
            },
        )
    }
}

/**
 * The work area: the pane tree, each leaf drawn by [PaneContent]. The
 * callbacks are the workspace's, shared by every pane; which pane a tab
 * bar's button acts on is settled by the press that activated it first.
 */
@Composable
private fun EditorArea(
    panes: PaneGroupState,
    onActivatePane: (Pane) -> Unit,
    /** The tab menu's "Split right/down": this tab of this pane into a new one. */
    onSplitTab: (Pane, Int, SplitDirection) -> Unit,
    /** The tab bar's ⊞ menu. */
    splitMenu: () -> List<ContextMenuItem>,
    onToggleZoom: () -> Unit,
    /** A tab lifted out of a pane's strip, and where it goes from there. */
    onDragStart: (Pane, OpenFile, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    dismissed: androidx.compose.runtime.MutableState<Set<String>>,
    onSave: (OpenFile) -> Unit,
    onReload: (OpenFile) -> Unit,
    /** The resolved-conflict banner's stage, and what the last one said. */
    onStageResolved: (OpenFile) -> Unit,
    stageError: String?,
    onReopen: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    /** Null with no project; the `+` group then stays hidden. */
    onNewFile: (() -> Unit)?,
    /** The find bar's deployment, or null while it is closed. */
    searchDeploy: SearchDeploy?,
    onSearchDismissed: () -> Unit,
    /** The bar reporting whether one of its fields holds the keyboard. */
    onSearchFocusChanged: (Boolean) -> Unit,
    /** The toolbar magnifier — the touch twin of Ctrl+F. */
    onToggleSearch: () -> Unit,
    /** A tap on the breadcrumbs — Zed's own button into the outline. */
    onOpenOutline: () -> Unit,
    /** The toolbar's symbol button — the touch twin of Ctrl+T. */
    onOpenProjectSymbols: () -> Unit,
    isPreviewOpen: Boolean,
    onTogglePreview: () -> Unit,
    /** For a diff tab, which needs the project rather than a buffer. */
    diffProject: ProjectSession?,
    /** Bumped when the diagnostics tab opens, to hand it the keyboard. */
    diagnosticsFocus: Int,
    /** A diagnostics row opening its file with the caret on the problem. */
    onOpenDiagnostic: (String, Diagnostic) -> Unit,
    onOpenPath: (String) -> Unit,
    /** The graph asking for a commit's diff tab, whole or one file of it. */
    onOpenCommit: (sha: String, subject: String, path: String?) -> Unit,
    /** The open picture's zoom, shared with the workspace's commands. */
    imageZoom: ImageZoom,
    /**
     * A definition in another file. This pane has one buffer and no way to
     * make a second, so the workspace opens the file and then puts the caret
     * on the target — the same shape as a project-search hit.
     */
    onOpenDefinition: (DefinitionTarget) -> Unit,
    /** A workspace edit landed; the workspace resyncs every editor it names. */
    onWorkspaceEditApplied: (EditReceipt) -> Unit,
    /** Raise the rename dialog over the active editor. */
    onRenameSymbol: () -> Unit,
    /** The tab menu's "Open with…" and "Share…" — see [ShareOut]. */
    onOpenWith: (OpenFile) -> Unit,
    onShare: (OpenFile) -> Unit,
    /** The gutter's play button; null with no project to run anything in. */
    onRunnableTapped: ((Runnable) -> Unit)?,
    /** Vim's `:w`, `:q` / `:q!`, `:wq`, `:e path` and `ctrl-o` / `ctrl-i`. */
    onVimSave: () -> Boolean,
    onCloseTab: (force: Boolean) -> Boolean,
    onSaveAndClose: () -> Boolean,
    onVimOpenPath: (String) -> Boolean,
    onNavigate: (back: Boolean) -> Unit,
    /** Zed's `tabs` block, for each pane's strip. */
    tabSettings: TabSettings,
    /** A tab's git status, when `tabs.git_status` asks for it. */
    gitStatusOf: (String) -> GitFileStatus,
    /** Whether `tabs.show_diagnostics` marks a tab's file. */
    hasDiagnostics: (String) -> Boolean,
    /** The tab switcher, for a finger — the strip's ⇥ button. */
    onOpenSwitcher: (() -> Unit)?,
    /** A multibuffer's sticky header, tapped: open that file at that row. */
    onOpenExcerpt: (path: String, row: Int) -> Unit,
    /** The diagnostics tab asking for its problems as a multibuffer. */
    onOpenDiagnosticsMultibuffer: (List<FileDiagnosticRows>) -> Unit,
    /** Every reference at once, as a multibuffer — Zed's own surface. */
    onOpenReferences: (List<ReferenceTarget>) -> Unit,
    modifier: Modifier = Modifier,
) {
    PaneGroupView(panes = panes, onActivate = onActivatePane, modifier = modifier) { pane ->
        PaneContent(
            pane = pane,
            isActivePane = panes.active === pane,
            isZoomed = panes.isZoomed,
            onSplitTab = onSplitTab,
            splitMenu = splitMenu,
            onToggleZoom = onToggleZoom,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
            dismissed = dismissed,
            onSave = onSave,
            onReload = onReload,
            onReopen = onReopen,
            onNavigateBack = onNavigateBack,
            onNavigateForward = onNavigateForward,
            onNewFile = onNewFile,
            searchDeploy = searchDeploy,
            onSearchDismissed = onSearchDismissed,
            onSearchFocusChanged = onSearchFocusChanged,
            onToggleSearch = onToggleSearch,
            onOpenOutline = onOpenOutline,
            isPreviewOpen = isPreviewOpen,
            onTogglePreview = onTogglePreview,
            diffProject = diffProject,
            diagnosticsFocus = diagnosticsFocus,
            onOpenDiagnostic = onOpenDiagnostic,
            onOpenPath = onOpenPath,
            onOpenCommit = onOpenCommit,
            onOpenDefinition = onOpenDefinition,
            onWorkspaceEditApplied = onWorkspaceEditApplied,
            onRenameSymbol = onRenameSymbol,
            onOpenWith = onOpenWith,
            onShare = onShare,
            onStageResolved = onStageResolved,
            stageError = stageError,
            onOpenProjectSymbols = onOpenProjectSymbols,
            imageZoom = imageZoom,
            onRunnableTapped = onRunnableTapped,
            onVimSave = onVimSave,
            onCloseTab = onCloseTab,
            onSaveAndClose = onSaveAndClose,
            onVimOpenPath = onVimOpenPath,
            onNavigate = onNavigate,
            tabSettings = tabSettings,
            gitStatusOf = gitStatusOf,
            hasDiagnostics = hasDiagnostics,
            onOpenSwitcher = onOpenSwitcher,
            onOpenExcerpt = onOpenExcerpt,
            onOpenDiagnosticsMultibuffer = onOpenDiagnosticsMultibuffer,
            onOpenReferences = onOpenReferences,
        )
    }
}

/**
 * One pane's column: its tab bar, then the toolbar, find bar and notices
 * that belong to its active tab, then the tab itself — an editor, a diff,
 * the graph, a picture. What Zed's `Pane::render` stacks (pane.rs:4600-4660).
 */
@Composable
private fun PaneContent(
    pane: Pane,
    isActivePane: Boolean,
    isZoomed: Boolean,
    onSplitTab: (Pane, Int, SplitDirection) -> Unit,
    splitMenu: () -> List<ContextMenuItem>,
    onToggleZoom: () -> Unit,
    onDragStart: (Pane, OpenFile, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    dismissed: androidx.compose.runtime.MutableState<Set<String>>,
    onSave: (OpenFile) -> Unit,
    onReload: (OpenFile) -> Unit,
    onReopen: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
    /** Null with no project; the `+` group then stays hidden. */
    onNewFile: (() -> Unit)?,
    /** The find bar's deployment, or null while it is closed. */
    searchDeploy: SearchDeploy?,
    onSearchDismissed: () -> Unit,
    /** The bar reporting whether one of its fields holds the keyboard. */
    onSearchFocusChanged: (Boolean) -> Unit,
    /** The toolbar magnifier — the touch twin of Ctrl+F. */
    onToggleSearch: () -> Unit,
    /** A tap on the breadcrumbs — Zed's own button into the outline. */
    onOpenOutline: () -> Unit,
    isPreviewOpen: Boolean,
    onTogglePreview: () -> Unit,
    /** For a diff tab, which needs the project rather than a buffer. */
    diffProject: ProjectSession?,
    /** Bumped when the diagnostics tab opens, to hand it the keyboard. */
    diagnosticsFocus: Int,
    /** A diagnostics row opening its file with the caret on the problem. */
    onOpenDiagnostic: (String, Diagnostic) -> Unit,
    /** A multibuffer's sticky header, tapped: open that file at that row. */
    onOpenExcerpt: (path: String, row: Int) -> Unit,
    /** The diagnostics tab asking for its problems as a multibuffer. */
    onOpenDiagnosticsMultibuffer: (List<FileDiagnosticRows>) -> Unit,
    onOpenPath: (String) -> Unit,
    /** The graph asking for a commit's diff tab, whole or one file of it. */
    onOpenCommit: (sha: String, subject: String, path: String?) -> Unit,
    /**
     * A definition in another file. This pane has one buffer and no way to
     * make a second, so the workspace opens the file and then puts the caret
     * on the target — the same shape as a project-search hit.
     */
    onOpenDefinition: (DefinitionTarget) -> Unit,
    /** A workspace edit landed; the workspace resyncs every editor it names. */
    onWorkspaceEditApplied: (EditReceipt) -> Unit,
    /** Raise the rename dialog over the active editor. */
    onRenameSymbol: () -> Unit,
    /** The tab menu's "Open with…" and "Share…" — see [ShareOut]. */
    onOpenWith: (OpenFile) -> Unit,
    onShare: (OpenFile) -> Unit,
    /** The resolved-conflict banner's stage, and what the last one said. */
    onStageResolved: (OpenFile) -> Unit,
    stageError: String?,
    /** The toolbar's symbol button — the touch twin of Ctrl+T. */
    onOpenProjectSymbols: () -> Unit,
    /** The open picture's zoom, shared with the workspace's commands. */
    imageZoom: ImageZoom,
    /** The gutter's play button; null with no project to run anything in. */
    onRunnableTapped: ((Runnable) -> Unit)?,
    /** Vim's `:w`, `:q` / `:q!`, `:wq`, `:e path` and `ctrl-o` / `ctrl-i`. */
    onVimSave: () -> Boolean,
    onCloseTab: (force: Boolean) -> Boolean,
    onSaveAndClose: () -> Boolean,
    onVimOpenPath: (String) -> Boolean,
    onNavigate: (back: Boolean) -> Unit,
    /** Zed's `tabs` block, for the strip. */
    tabSettings: TabSettings,
    /** A tab's git status, when `tabs.git_status` asks for it. */
    gitStatusOf: (String) -> GitFileStatus,
    /** Whether `tabs.show_diagnostics` marks a tab's file. */
    hasDiagnostics: (String) -> Boolean,
    /** The tab switcher, for a finger — the strip's ⇥ button. */
    onOpenSwitcher: (() -> Unit)?,
    /** Every reference at once, as a multibuffer — Zed's own surface. */
    onOpenReferences: (List<ReferenceTarget>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val files = pane.files
    val active = files.active
    // What the blame column's popover reads a commit's message and the
    // repository's remote through — the project, which the pane itself does
    // not hold.
    val blameHost = remember(diffProject) { diffProject?.let { BlameHost(it) } }
    // The pane fills the cell [PaneGroupView] gave it; the group took the
    // caller's modifier.
    Column(modifier = Modifier.fillMaxSize()) {
        if (files.tabs.isNotEmpty()) {
            EditorTabs(
                files,
                onSave = onSave,
                onReopen = onReopen,
                onNavigateBack = onNavigateBack,
                onNavigateForward = onNavigateForward,
                onNewFile = onNewFile,
                onOpenWith = onOpenWith,
                onShare = onShare,
                splitMenu = splitMenu,
                onToggleZoom = onToggleZoom,
                isZoomed = isZoomed,
                onSplitTab = { index, direction -> onSplitTab(pane, index, direction) },
                onDragStart = { file, position -> onDragStart(pane, file, position) },
                onDrag = onDrag,
                onDragEnd = onDragEnd,
                onDragCancel = onDragCancel,
                tabs = tabSettings,
                gitStatusOf = gitStatusOf,
                hasDiagnostics = hasDiagnostics,
                onOpenSwitcher = onOpenSwitcher,
            )
            // `tab_bar.show: false` takes the rule with the strip; EditorTabs
            // still runs, because it hosts the unsaved-changes dialog that
            // every route into closing a tab goes through.
            if (LocalAppSettings.current.tabBar.show) DockDivider()
        }
        // Zed's toolbar: breadcrumbs on the left — the file name, then the
        // engine's symbol path at the caret — and the quick action bar on the
        // right. Shown for every text buffer, as Zed shows it; a picture is
        // not previewable — it *is* the preview — so a media tab never gets
        // one.
        val activeEditor = active?.editor
        if (active != null && activeEditor != null && LocalAppSettings.current.toolbar.isVisible) {
            val previewKind = PreviewKind.of(active.path)
            BreadcrumbToolbar(
                editor = activeEditor,
                fileName = active.name,
                onToggleSearch = onToggleSearch,
                onOpenOutline = onOpenOutline,
                onOpenProjectSymbols = onOpenProjectSymbols,
                kind = previewKind,
                isPreviewOpen = isPreviewOpen,
                onTogglePreview = if (previewKind != null) onTogglePreview else null,
            )
            DockDivider()
        }
        // Find-in-file is a text question; a picture has nothing to search.
        if (isActivePane && searchDeploy != null && activeEditor != null) {
            BufferSearchBar(
                editor = activeEditor,
                deploy = searchDeploy,
                onDismiss = {
                    activeEditor.clearSearchMatches()
                    onSearchDismissed()
                },
                onFocusChanged = onSearchFocusChanged,
            )
            DockDivider()
        }

        // Only a dirty buffer (or a vanished file) needs the user's decision;
        // the clean case is reloaded by the status loop without a prompt.
        if (active != null &&
            (active.hasDiskChange || active.isDeleted) &&
            active.path !in dismissed.value
        ) {
            FileConflictBar(
                file = active,
                onReload = { onReload(active) },
                onSave = { onSave(active) },
                onDismiss = { dismissed.value = dismissed.value + active.path },
            )
            HorizontalDivider()
        }
        // A file git still calls conflicted whose last marker is gone: offer
        // the staging that tells git so. Only once the editor has actually
        // read the file for markers — before that, "none" means nothing.
        val conflictedPaths = rememberConflictedPaths(diffProject)
        var resolvedDismissed by remember { mutableStateOf(setOf<String>()) }
        if (active != null && activeEditor != null && active.path in conflictedPaths) {
            val hasMarkers = activeEditor.conflicts.isNotEmpty()
            // A dismissal lasts until the file has conflicts again.
            LaunchedEffect(active.path, hasMarkers) {
                if (hasMarkers) resolvedDismissed -= active.path
            }
            if (activeEditor.conflictsRead && !hasMarkers && active.path !in resolvedDismissed) {
                MergeResolvedBar(
                    fileName = active.name,
                    error = stageError,
                    onStage = { onStageResolved(active) },
                    onDismiss = { resolvedDismissed += active.path },
                )
                HorizontalDivider()
            }
        }

        if (active == null) {
            // An empty pane is empty. Zed's placeholder for a workspace with a
            // project open renders no hint text at all — and no fill of its
            // own, so what shows is the workspace body's `background`
            // (workspace/src/pane.rs:4550-4566, workspace.rs:9111); the
            // welcome hints belong to the no-project page, which for us is
            // the project picker.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(LocalZedTheme.current.color("background")),
            )
        } else if (activeEditor != null && active.multibuffer != null) {
            // The same pane, over the engine's composition — plus the sticky
            // header Zed draws above each excerpt. Ctrl+S here is SaveAll, and
            // `onSave` already knows that.
            MultiBufferPane(
                state = activeEditor,
                multibuffer = active.multibuffer,
                onOpenExcerpt = onOpenExcerpt,
                onSaveAll = { onSave(active) },
                modifier = Modifier.weight(1f),
                languageSettings = active.languageSettings,
                showInlineBlame = active.languageSettings.inlineBlame,
                onOpenDefinition = onOpenDefinition,
                onWorkspaceEditApplied = onWorkspaceEditApplied,
                onRenameSymbol = onRenameSymbol,
                onOpenReferences = onOpenReferences,
            )
        } else if (activeEditor != null) {
            EditorPane(
                state = activeEditor,
                modifier = Modifier.weight(1f),
                fileName = active.name,
                languageSettings = active.languageSettings,
                showInlineBlame = active.languageSettings.inlineBlame,
                onOpenDefinition = onOpenDefinition,
                onWorkspaceEditApplied = onWorkspaceEditApplied,
                onRenameSymbol = onRenameSymbol,
                blameHost = blameHost,
                onRunnableTapped = onRunnableTapped,
                onSaveFile = onVimSave,
                onCloseTab = onCloseTab,
                onSaveAndClose = onSaveAndClose,
                onOpenPath = onVimOpenPath,
                onNavigate = onNavigate,
                onOpenReferences = onOpenReferences,
            )
        } else if (active.graph && diffProject != null) {
            GitGraphPane(
                project = diffProject,
                onOpenCommit = onOpenCommit,
                modifier = Modifier.weight(1f),
            )
        } else if (active.agentReview) {
            AgentReviewPane(
                onOpenFile = onOpenPath,
                onDismiss = {
                    files.indexOfPath(active.path).takeIf { it >= 0 }?.let(files::close)
                },
                modifier = Modifier.weight(1f),
            )
        } else if (active.lspLogs != null && diffProject != null) {
            LspLogsPane(
                projectId = diffProject.id,
                serverName = active.lspLogs,
                modifier = Modifier.weight(1f),
            )
        } else if (active.diagnostics && diffProject != null) {
            DiagnosticsPane(
                project = diffProject,
                focusToken = diagnosticsFocus,
                onOpenDiagnostic = onOpenDiagnostic,
                onOpenMultibuffer = onOpenDiagnosticsMultibuffer,
                // The toolbar's close closes the tab, exactly as the strip's
                // own button would.
                onDismiss = {
                    files.indexOfPath(active.path).takeIf { it >= 0 }?.let(files::close)
                },
                modifier = Modifier.weight(1f),
            )
        } else if (active.diff != null && diffProject != null) {
            DiffPane(
                project = diffProject,
                target = active.diff,
                onOpenFile = onOpenPath,
                modifier = Modifier.weight(1f),
            )
        } else {
            MediaPane(
                absolutePath = active.absolutePath.orEmpty(),
                kind = active.media ?: MediaKind.Image,
                zoom = imageZoom,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The toolbar and the symbol-path lookup behind its breadcrumbs, in a scope
 * of their own.
 *
 * The caret is watched through [snapshotFlow] rather than passed as effect
 * keys: keys are read during composition, and a read of snapshot state there
 * subscribes the *enclosing* scope — keyed from [EditorArea], every arrow key
 * recomposed the tab strip, toolbar, search bar and pane around it. Here the
 * reads happen inside the effect, and a caret move invalidates nothing until
 * the settled path itself changes — and then only this toolbar.
 */
@Composable
private fun BreadcrumbToolbar(
    editor: EditorState,
    fileName: String,
    onToggleSearch: () -> Unit,
    onOpenOutline: () -> Unit,
    onOpenProjectSymbols: () -> Unit,
    kind: PreviewKind?,
    isPreviewOpen: Boolean,
    onTogglePreview: (() -> Unit)?,
) {
    var symbolPath by remember(editor) { mutableStateOf<List<String>>(emptyList()) }
    // Re-asked when the caret settles or a reparse lands — all observable
    // state, and the JNI read runs off the main thread. `collectLatest`, so a
    // caret still moving restarts the settle delay instead of queueing a
    // query per step.
    LaunchedEffect(editor) {
        snapshotFlow {
            Triple(editor.cursorRow, editor.cursorCol, editor.highlightVersion)
        }.collectLatest { (row, col, _) ->
            delay(BREADCRUMB_SETTLE_MS)
            val id = editor.session.id
            symbolPath = withContext(Dispatchers.Default) {
                parseOutlinePath(CoreBridge.bufferOutlinePath(id, row.toLong(), col.toLong()))
            }
        }
    }
    EditorToolbar(
        fileName = fileName,
        symbolPath = symbolPath,
        onToggleSearch = onToggleSearch,
        onOpenOutline = onOpenOutline,
        kind = kind,
        isPreviewOpen = isPreviewOpen,
        onTogglePreview = onTogglePreview,
        onOpenProjectSymbols = onOpenProjectSymbols,
        selectionsMenu = { selectionsMenuItems(editor, onOpenOutline) },
    )
}

/**
 * Zed's selection-controls menu, row for row
 * (zed/src/zed/quick_action_bar.rs:271-320), less the four actions this editor
 * has not got — the two syntax-node selections, "Add to Agent Thread" and
 * "Go to Line/Column", which is the status bar's own button here.
 *
 * Every row is an `editor::` action, so each prints the chord the keymap gives
 * it and each is exactly what the keyboard would have run.
 */
private fun selectionsMenuItems(
    editor: EditorState,
    onOpenOutline: () -> Unit,
): List<ContextMenuItem> {
    fun row(
        label: String,
        action: String,
        separatorAbove: Boolean = false,
    ) = ContextMenuItem(
        label = label,
        shortcut = shortcutLabel(action),
        separatorAbove = separatorAbove,
        onClick = { editor.runAction(action) },
    )
    return listOf(
        row("Select All", EditorAction.SelectAll),
        row("Select Next Occurrence", EditorAction.SelectNext),
        row("Add Cursor Above", EditorAction.AddSelectionAbove),
        row("Add Cursor Below", EditorAction.AddSelectionBelow),
        ContextMenuItem(
            label = "Go to Symbol",
            shortcut = shortcutLabel(WorkspaceAction.Outline),
            separatorAbove = true,
            onClick = onOpenOutline,
        ),
        row("Next Problem", EditorAction.GoToDiagnostic, separatorAbove = true),
        row("Previous Problem", EditorAction.GoToPreviousDiagnostic),
        row("Next Hunk", EditorAction.GoToHunk, separatorAbove = true),
        row("Previous Hunk", EditorAction.GoToPreviousHunk),
        row("Move Line Up", EditorAction.MoveLineUp, separatorAbove = true),
        row("Move Line Down", EditorAction.MoveLineDown),
        row("Duplicate Selection", EditorAction.DuplicateLineDown),
    )
}

/**
 * The userland entry, or nothing at all in a build that has no userland —
 * an editor should not advertise a feature it cannot perform.
 *
 * The state comes from [UserlandInstaller] first, because that one is Compose
 * state: asking the backend directly reads the disk and tells the truth, but
 * tells it *once*, so the menu built before an install finished kept saying
 * there was nothing to remove until something unrelated happened to
 * recompose it. Measured on the emulator: install Debian, open the menu, and
 * the entry is missing.
 */
/**
 * A dock: the panel in it, and the edge that resizes it.
 *
 * The handle is on the side facing the editor — the *inner* edge — because
 * that is the edge that moves, and it is the same 6dp grip the terminal dock
 * has had all along. A drag on the left dock's edge widens it; the same drag
 * on the right dock's edge narrows it, which is why the sign follows the side.
 */
@Composable
private fun Dock(
    side: DockSide,
    width: Dp,
    onResize: (Dp) -> Unit,
    content: @Composable () -> Unit,
) {
    Row(modifier = Modifier.width(width).fillMaxHeight()) {
        if (side == DockSide.Right) DockHandle(side, onResize)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
        if (side == DockSide.Left) DockHandle(side, onResize)
    }
}

/** The grip, and the border it sits on. */
@Composable
private fun DockHandle(side: DockSide, onResize: (Dp) -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(side) {
                detectHorizontalDragGestures { _, delta ->
                    val moved = with(density) { delta.toDp() }
                    onResize(if (side == DockSide.Left) moved else -moved)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        DockDivider(vertical = true)
    }
}

/** Whichever panel the dock is showing. */
@Composable
private fun DockPanel(
    panel: WorkspacePanel,
    project: ProjectSession?,
    file: OpenFile?,
    settings: AppSettings,
    searchFocus: Int,
    /** An include glob project search should start from, or null. */
    searchInclude: String?,
    onSearchSeedApplied: () -> Unit,
    gitFocus: Int,
    /** A palette-run git command for the git panel, and its receipt. */
    gitRequest: GitPanelRequest?,
    onGitRequestHandled: () -> Unit,
    /** The git panel reporting whether it holds the keyboard. */
    onGitFocusChanged: (Boolean) -> Unit,
    agentFocus: Int,
    /** Bumped by Ctrl+Shift+B, to put the caret back in the outline's filter. */
    outlineFocus: Int,
    /** The outline jumped somewhere; a full-screen dock hands the area back. */
    onOutlineJump: () -> Unit,
    revealRequest: Boolean,
    openedPath: String?,
    onRevealHandled: () -> Unit,
    onOpenEntry: (ProjectEntry, Boolean) -> Unit,
    onOpenMatch: (String, ProjectSearchMatch) -> Unit,
    /** Project search replaced across files; the open editors resync. */
    onProjectReplaced: (ProjectReplaceReceipt) -> Unit,
    /** Search results opening as an editable multibuffer. */
    onOpenSearchMultibuffer: (String, List<ProjectSearchFile>, Pair<String, Int>?) -> Unit,
    /** Whether a search hit opens the multibuffer rather than its file. */
    multibufferIsDefault: Boolean,
    onOpenPath: (String) -> Unit,
    onOpenDiff: (String?) -> Unit,
    /** The git panel's conflict rows opening their file on its first conflict. */
    onResolveConflict: (String) -> Unit,
    /** The git panel's "View Branch Diff" opening the branch-vs-base tab. */
    onOpenBranchDiff: (String) -> Unit,
    /** The git panel's footer opening one commit as a diff tab. */
    onOpenCommit: (String, String) -> Unit,
    onOpenGraph: () -> Unit,
    /** The git panel's branch button opening the branch picker. */
    onSwitchBranch: () -> Unit,
    /** The git panel's Stash menu opening the stash picker. */
    onViewStash: () -> Unit,
    onEntryRemoved: (String) -> Unit,
    onEntryMoved: (String, String) -> Unit,
    /** The project panel's `+` — Zed's `workspace::AddFolderToProject`. */
    onAddFolder: () -> Unit,
    /** The project panel reporting whether it holds the keyboard. */
    onPanelFocusChanged: (Boolean) -> Unit,
    /** Which entries the panel's `show_diagnostics` marks. */
    diagnostics: DiagnosticMarks,
    /** A shell in a directory the panel picked — `project_panel::OpenInTerminal`. */
    onOpenTerminal: (String) -> Unit,
    /** Project search over one folder — `project_panel::NewSearchInDirectory`. */
    onSearchInDirectory: (String) -> Unit,
    /** Open the settings screen — the agent panel's empty state points here. */
    onOpenSettings: () -> Unit,
    /** The agent panel's review badge opening the review tab. */
    onOpenAgentReview: () -> Unit,
    /** The open buffers and the editor's selection, for the agent's `@` picker. */
    agentWorkspace: AgentWorkspaceAccess,
    /** The agent composer reporting whether it holds the keyboard. */
    onAgentFocusChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    when (panel) {
        WorkspacePanel.Project -> ProjectPanel(
            project = project,
            onOpenFile = onOpenEntry,
            openedPath = openedPath,
            gitignoredFiles = settings.gitignoredFiles,
            panel = settings.projectPanel,
            diagnostics = diagnostics,
            onOpenTerminal = onOpenTerminal,
            onSearchInDirectory = onSearchInDirectory,
            revealRequest = revealRequest,
            onRevealHandled = onRevealHandled,
            onFocusChanged = onPanelFocusChanged,
            onEntryRemoved = onEntryRemoved,
            onEntryMoved = onEntryMoved,
            onAddFolder = onAddFolder,
        )
        WorkspacePanel.Search -> ProjectSearchPanel(
            project = project ?: return,
            focusToken = searchFocus,
            onOpenMatch = onOpenMatch,
            onReplaced = onProjectReplaced,
            onOpenMultibuffer = onOpenSearchMultibuffer,
            multibufferIsDefault = multibufferIsDefault,
            onDismiss = onDismiss,
            seedInclude = searchInclude,
            onSeedApplied = onSearchSeedApplied,
        )
        WorkspacePanel.Preview -> PreviewPanel(
            editor = file?.editor ?: return,
            path = file.path,
            projectRoot = project?.rootPath,
            onDismiss = onDismiss,
            onOpenPath = onOpenPath,
        )
        WorkspacePanel.Outline -> OutlinePanel(
            editor = file?.editor,
            focusToken = outlineFocus,
            onNavigated = onOutlineJump,
            onDismiss = onDismiss,
        )
        WorkspacePanel.Git -> GitPanel(
            project = project ?: return,
            focusToken = gitFocus,
            onOpenFile = onOpenPath,
            onOpenDiff = onOpenDiff,
            onResolveConflict = onResolveConflict,
            onOpenBranchDiff = onOpenBranchDiff,
            onOpenCommit = onOpenCommit,
            onOpenGraph = onOpenGraph,
            onSwitchBranch = onSwitchBranch,
            onViewStash = onViewStash,
            onDismiss = onDismiss,
            request = gitRequest,
            onRequestHandled = onGitRequestHandled,
            onFocusChanged = onGitFocusChanged,
        )
        WorkspacePanel.Agent -> AgentPanel(
            project = project ?: return,
            focusToken = agentFocus,
            onOpenPath = onOpenPath,
            onOpenSettings = onOpenSettings,
            onOpenReview = onOpenAgentReview,
            workspace = agentWorkspace,
            onFocusChanged = onAgentFocusChanged,
        )
    }
}

/**
 * Whichever preview the open file has — Zed shows one button and one panel,
 * and which of the two it is follows the file rather than a second command.
 *
 * A file with no preview keeps the panel and gets its empty state, rather than
 * having the panel vanish under it: switching to a `.rs` for one lookup and
 * back should not cost the reader their preview.
 */
@Composable
private fun PreviewPanel(
    editor: EditorState,
    path: String,
    projectRoot: String?,
    onDismiss: () -> Unit,
    onOpenPath: (String) -> Unit,
) {
    // Both directions of the preview's scroll sync end here: a tap in the
    // preview lands the caret on the source line, which is Zed's
    // `change_selection_to_source_index` (markdown_preview_view.rs:691-709).
    // Focus stays in the preview — Zed only moves it when the *editor* asked —
    // so a reader tapping their way down a document is not typing into it.
    val jump: (Int) -> Unit = { line -> editor.revealDefinition(line, 0) }
    when (PreviewKind.of(path)) {
        PreviewKind.Svg -> SvgPreview(
            editor = editor,
            path = path,
            onDismiss = onDismiss,
        )
        PreviewKind.Table -> TablePreview(
            editor = editor,
            path = path,
            onDismiss = onDismiss,
            onJumpToSource = jump,
        )
        else -> MarkdownPreview(
            editor = editor,
            path = path,
            onDismiss = onDismiss,
            onOpenPath = onOpenPath,
            projectRoot = projectRoot,
            onJumpToSource = jump,
        )
    }
}

@Composable
private fun userlandActions(
    context: android.content.Context,
    onRemove: () -> Unit,
): List<MenuAction> {
    val installed = UserlandInstaller.state ?: Userland.backend.state(context)
    return if (installed is UserlandState.Ready) {
        listOf(
            MenuAction("Remove ${Userland.backend.displayName} userland…", null) { onRemove() }
        )
    } else {
        emptyList()
    }
}

/**
 * Point the engine at the Linux userland, so it can run git for project-panel
 * status. Blocking; call it off the main thread.
 *
 * Nothing to do in a build without a userland, or before Debian is installed —
 * git status then reads as "clean", which is the right way for a feature that
 * cannot run to look.
 */
internal fun syncUserlandWithEngine(context: Context) {
    if (Userland.backend.state(context) !is UserlandState.Ready) {
        CoreBridge.clearUserland()
        return
    }
    CoreBridge.setUserland(
        File(context.applicationInfo.nativeLibraryDir, "libproot_exec.so").absolutePath,
        File(context.filesDir, "debian").absolutePath,
        context.cacheDir.absolutePath,
        File(context.filesDir, "projects").absolutePath,
    )
}

/**
 * The key event the workspace's pre-IME pass last took up — see the root's
 * `onPreInterceptKeyBeforeSoftKeyboard`. A plain holder rather than
 * snapshot state: it lives for one event and nothing draws it.
 */
private class PreImeKey {
    var event: AndroidKeyEvent? = null
}
