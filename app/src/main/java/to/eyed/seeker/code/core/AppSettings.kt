package to.eyed.seeker.code.core

import org.json.JSONObject
import to.eyed.seeker.code.ui.editor.CurrentLineHighlight
import to.eyed.seeker.code.ui.editor.EditorCursorShape
import to.eyed.seeker.code.ui.editor.InlineDiagnosticsSettings
import to.eyed.seeker.code.ui.editor.MinimapSettings
import to.eyed.seeker.code.ui.editor.RelativeLineNumbers
import to.eyed.seeker.code.ui.editor.ScrollbarSettings
import to.eyed.seeker.code.ui.editor.ShowWhitespaces
import to.eyed.seeker.code.ui.editor.SoftWrapMode

/** How the project tree treats gitignored entries. */
enum class GitignoredFiles(val key: String) {
    /** Listed like any other file. */
    Show("show"),
    /** Listed, but greyed out — what Zed does. */
    Dimmed("dimmed"),
    /** Left out of the tree. */
    Hide("hide");

    companion object {
        fun fromKey(key: String): GitignoredFiles =
            entries.firstOrNull { it.key == key } ?: Dimmed
    }
}

/** Where the close affordance sits on a tab — Zed's `tabs.close_position`. */
enum class ClosePosition(val key: String) {
    Left("left"),
    Right("right");

    companion object {
        fun fromKey(key: String?): ClosePosition =
            entries.firstOrNull { it.key == key } ?: Right
    }
}

/**
 * Which files a surface marks as having diagnostics — Zed's
 * `ShowDiagnostics`, shared by `tabs.show_diagnostics` (default `off`) and
 * `project_panel.show_diagnostics` (default `all`).
 */
enum class ShowDiagnostics(val key: String) {
    Off("off"),
    Errors("errors"),
    All("all");

    /** Whether a file with [errors] and [warnings] is marked at all. */
    fun marks(errors: Int, warnings: Int): Boolean = when (this) {
        Off -> false
        Errors -> errors > 0
        All -> errors > 0 || warnings > 0
    }

    companion object {
        fun fromKey(key: String?, fallback: ShowDiagnostics): ShowDiagnostics =
            entries.firstOrNull { it.key == key } ?: fallback
    }
}

/** Which tab takes over when the active one closes — Zed's `activate_on_close`. */
enum class ActivateOnClose(val key: String) {
    /** The tab that was open before this one. Zed's default. */
    History("history"),
    /** The neighbour on the right, if there is one. */
    Neighbour("neighbour"),
    /** The neighbour on the left, if there is one. */
    LeftNeighbour("left_neighbour");

    companion object {
        fun fromKey(key: String?): ActivateOnClose =
            entries.firstOrNull { it.key == key } ?: History
    }
}

/** How siblings are ordered in the project panel — Zed's `sort_mode`. */
enum class ProjectPanelSort(val key: String) {
    DirectoriesFirst("directories_first"),
    Mixed("mixed"),
    FilesFirst("files_first");

    companion object {
        fun fromKey(key: String?): ProjectPanelSort =
            entries.firstOrNull { it.key == key } ?: DirectoriesFirst
    }
}

/** Row height in the project panel — Zed's `entry_spacing`. */
enum class EntrySpacing(val key: String) {
    Comfortable("comfortable"),
    Standard("standard");

    companion object {
        fun fromKey(key: String?): EntrySpacing =
            entries.firstOrNull { it.key == key } ?: Comfortable
    }
}

/** The editor's tab strip — Zed's `tabs` block, with Zed's own defaults. */
data class TabSettings(
    val closePosition: ClosePosition = ClosePosition.Right,
    val fileIcons: Boolean = false,
    val gitStatus: Boolean = false,
    val showDiagnostics: ShowDiagnostics = ShowDiagnostics.Off,
    val activateOnClose: ActivateOnClose = ActivateOnClose.History,
)

/**
 * Provisional tabs — Zed's `preview_tabs`.
 *
 * A tab opened with a single click is italic and is *reused* by the next
 * provisional open; editing it, or double-clicking, promotes it.
 */
data class PreviewTabSettings(
    val enabled: Boolean = true,
    val fromProjectPanel: Boolean = true,
    val fromFileFinder: Boolean = false,
    val fromCodeNavigation: Boolean = true,
) {
    /** Whether an open through [route] should land in the preview tab. */
    fun previews(route: PreviewRoute): Boolean = enabled && when (route) {
        PreviewRoute.ProjectPanel -> fromProjectPanel
        PreviewRoute.FileFinder -> fromFileFinder
        PreviewRoute.CodeNavigation -> fromCodeNavigation
        PreviewRoute.Permanent -> false
    }
}

/** Which of Zed's `preview_tabs` keys an open is governed by. */
enum class PreviewRoute {
    ProjectPanel,
    FileFinder,
    CodeNavigation,

    /** Not a preview route at all: this open makes a permanent tab. */
    Permanent,
}

/**
 * Zed's `toolbar` block (assets/settings/default.json:544-555): which parts
 * of the row under the tabs are drawn.
 *
 * Three of Zed's five keys — the two left out, `agent_review` and
 * `code_actions`, name toolbar items there is no version of here.
 */
data class ToolbarSettings(
    /** The file name and the symbol path at the caret. */
    val breadcrumbs: Boolean = true,
    /** The icon buttons: find in file, project symbols, preview. */
    val quickActions: Boolean = true,
    /** The selections readout and its menu of multi-caret actions. */
    val selectionsMenu: Boolean = true,
) {
    /** Whether the toolbar has anything left to draw. */
    val isVisible: Boolean get() = breadcrumbs || quickActions || selectionsMenu
}

/**
 * Zed's `tab_bar` block (assets/settings/default.json:1386-1397): whether the
 * strip is drawn, and which of its two fixed button groups are.
 */
data class TabBarSettings(
    val show: Boolean = true,
    /** The `←` / `→` group at the leading edge (tab_bar.rs:103-112). */
    val showNavHistoryButtons: Boolean = true,
    /** The `⇥ + ⊞ ⤢` group at the trailing edge (tab_bar.rs:141-150). */
    val showTabBarButtons: Boolean = true,
)

/**
 * Zed's `status_bar` block (assets/settings/default.json:1904-1913): the two
 * readouts that can be switched off. The dock buttons cannot — on a phone
 * they are the only route to a panel.
 */
data class StatusBarSettings(
    val activeLanguageButton: Boolean = true,
    val cursorPositionButton: Boolean = true,
)

/**
 * Zed's `reduce_motion`, plus the answer this platform needs.
 *
 * Zed has `on` and `off`. Android asks the same question system-wide —
 * Accessibility ▸ Remove animations writes 0 to
 * `Settings.Global.ANIMATOR_DURATION_SCALE` — so [Auto] is the default here
 * and defers to it; Zed's two words still force the answer either way.
 */
enum class ReduceMotion(val key: String, val label: String) {
    On("on", "Always"),
    Off("off", "Never"),
    Auto("auto", "Follow the system");

    /**
     * Whether motion should be reduced, given what the system was told.
     *
     * [systemAnimationsOff] is `Settings.Global.ANIMATOR_DURATION_SCALE == 0`
     * — the read is the caller's, so this stays a pure function a host test
     * can pin.
     */
    fun applies(systemAnimationsOff: Boolean): Boolean = when (this) {
        On -> true
        Off -> false
        Auto -> systemAnimationsOff
    }

    companion object {
        fun fromKey(key: String?): ReduceMotion =
            entries.firstOrNull { it.key == key } ?: Auto
    }
}

/** The project panel's own settings — Zed's `project_panel` block. */
data class ProjectPanelSettings(
    val sort: ProjectPanelSort = ProjectPanelSort.DirectoriesFirst,
    val hideRoot: Boolean = false,
    val autoFoldDirs: Boolean = true,
    val entrySpacing: EntrySpacing = EntrySpacing.Comfortable,
    /** Indent per nesting level, in dp. Zed's default is 20. */
    val indentSize: Float = 20f,
    val showDiagnostics: ShowDiagnostics = ShowDiagnostics.All,
)

/** How the editor picks light or dark. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark");

    companion object {
        fun fromKey(key: String): ThemeMode =
            entries.firstOrNull { it.key == key } ?: System
    }
}

/**
 * Zed's `base_keymap`: whose shortcuts to start from. Every value but
 * [None] lays one of Zed's own overlay keymaps over the defaults; [None] is
 * Zed's "no defaults at all", leaving keymap.json as the whole keymap. The
 * keys are Zed's spellings, capitals included, so a line from a Zed settings
 * file works here.
 */
enum class BaseKeymap(val key: String, val label: String) {
    VSCode("VSCode", "VS Code"),
    JetBrains("JetBrains", "JetBrains"),
    SublimeText("SublimeText", "Sublime Text"),
    Atom("Atom", "Atom"),
    Emacs("Emacs", "Emacs"),
    None("None", "None");

    companion object {
        fun fromKey(key: String?): BaseKeymap = entries.firstOrNull { it.key == key } ?: VSCode
    }
}

/**
 * The app's resolved settings, mirroring `engine::Settings`.
 *
 * The engine owns the file — it is JSONC, hand-editable, and keeps its
 * comments through edits made here (see `core/crates/engine/src/config.rs`).
 * This is just the read model; every field is wired to something visible.
 */
/**
 * Which side of the workspace a panel lives on — Zed's `dock` — plus
 * [Hidden], this app's third answer: the panel is switched off, its
 * status-bar button gone and its commands refusing. Zed separates that into a
 * per-panel `"button"` key; here it is one row with three answers, by the
 * owner's design.
 */
enum class DockSide(val key: String, val label: String) {
    Left("left", "Left"),
    Right("right", "Right"),
    Hidden("hidden", "Hidden");

    companion object {
        fun fromKey(key: String?): DockSide = entries.firstOrNull { it.key == key } ?: Left

        /**
         * The docks that exist on screen. **Iterate this, never [entries]**,
         * when walking the workspace's docks: [Hidden] is a per-panel state,
         * not a third dock — an `entries` loop treated it as one and drew the
         * right dock's panel twice, because every `left-else-right` branch
         * reads Hidden as Right.
         */
        val docks: List<DockSide> = listOf(Left, Right)
    }
}

/** Where a panel docks, and how wide it opens the first time. */
data class PanelPlacement(val dock: DockSide, val defaultWidth: Float)

/**
 * Zed's `autosave` (settings_content/src/workspace.rs:609-618): three plain
 * words and one object, `{"after_delay": {"milliseconds": N}}`.
 */
sealed class Autosave {
    data object Off : Autosave()

    /** Save a tab when the active tab changes. */
    data object OnFocusChange : Autosave()

    /** Save every dirty tab when the app leaves the foreground. */
    data object OnWindowChange : Autosave()

    /** Save a tab once it has sat unedited for this long. */
    data class AfterDelay(val milliseconds: Long) : Autosave()

    /** The JSON the settings file takes for this value. */
    fun toJson(): String = when (this) {
        Off -> "\"off\""
        OnFocusChange -> "\"on_focus_change\""
        OnWindowChange -> "\"on_window_change\""
        is AfterDelay -> "{\"after_delay\":{\"milliseconds\":$milliseconds}}"
    }

    companion object {
        /** Zed's own example delay (default.json: "milliseconds": 500) rounded to a second. */
        const val DEFAULT_DELAY_MS = 1000L

        fun parse(value: Any?): Autosave = when (value) {
            "on_focus_change" -> OnFocusChange
            "on_window_change" -> OnWindowChange
            is JSONObject -> value.optJSONObject("after_delay")
                ?.optLong("milliseconds", DEFAULT_DELAY_MS)
                ?.let { AfterDelay(it.coerceIn(100L, 600_000L)) }
                ?: Off
            else -> Off
        }
    }
}

/**
 * Zed's `restore_on_startup` (assets/settings/default.json:156-164): how much
 * of the last session comes back at launch.
 *
 * Zed's three values say how many *windows* return — every workspace of the
 * last session, only the most recent one, or none. This app has one window
 * and one project open at a time, so they say how much of that window
 * returns, which is the same question with a single window. The names are
 * Zed's so a line copied from a Zed settings file reads as it did there.
 */
enum class RestoreOnStartup(val key: String, val label: String, val description: String) {
    /** The project and everything in it: panes, tabs, carets, docks, terminals. */
    LastSession("last_session", "Last session", "Panes, tabs, carets, docks and terminals"),

    /** The project alone, with a fresh workspace. */
    LastWorkspace("last_workspace", "Last project", "The project alone, with no tabs"),

    /** Nothing: the app starts on the project picker. */
    None("none", "Nothing", "Start on the project picker");

    /** Whether the last project is reopened at all. */
    val reopensProject: Boolean get() = this != None

    /** Whether the saved tabs, panes, docks and terminals are put back. */
    val restoresWorkspace: Boolean get() = this == LastSession

    companion object {
        fun fromKey(key: String?): RestoreOnStartup =
            entries.firstOrNull { it.key == key } ?: LastSession
    }
}

/** Where a new shell starts — Zed's `terminal.working_directory`, the engine's `TerminalWorkingDirectory`. */
enum class TerminalWorkingDirectory(val key: String) {
    CurrentProjectDirectory("current_project_directory"),
    CurrentFileDirectory("current_file_directory"),
    /** One project per workspace here, so this is the project directory. */
    FirstProjectDirectory("first_project_directory"),
    AlwaysHome("always_home");

    companion object {
        fun fromKey(key: String?): TerminalWorkingDirectory =
            entries.firstOrNull { it.key == key } ?: CurrentProjectDirectory
    }
}

/**
 * The `terminal` section, mirroring the engine's `TerminalSettings`. Read at
 * session spawn and nowhere else: a running shell keeps the directory,
 * environment and scrollback it was born with, as in Zed ("existing terminals
 * will not pick up this change until they are recreated").
 */
data class TerminalSettings(
    val workingDirectory: TerminalWorkingDirectory = TerminalWorkingDirectory.CurrentProjectDirectory,
    /** Appended to the shell's environment, after the app's own, later wins. */
    val env: Map<String, String> = emptyMap(),
    /**
     * Rows of scrollback. The engine clamps to Zed's 100 000; the vendored
     * emulator's own ceiling is 50 000 (`TERMINAL_TRANSCRIPT_ROWS_MAX`), above
     * which it would silently substitute its 2 000 default — so the host
     * clamps again before handing the number over.
     */
    val scrollbackLines: Int = 10_000,
)

/**
 * One MCP context server — Zed's `context_servers` entry, in the two shapes
 * an ACP agent can take: a program it runs over stdio ([command] set), or an
 * HTTP endpoint it connects to ([url] set). Forwarded by the engine as
 * `mcpServers` when a thread starts; nothing here runs on this side.
 */
data class ContextServerDefinition(
    val name: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Zed's `enabled`; a server switched off stays in the file, unsent. */
    val enabled: Boolean = true,
) {
    val isHttp: Boolean get() = url != null

    /** What the settings row prints under the name. */
    val summary: String
        get() = url ?: (listOf(command.orEmpty()) + args).joinToString(" ")

    /** The engine's JSON for this entry, which is Zed's shape verbatim. */
    fun toSpecJson(): String = JSONObject().apply {
        if (url != null) {
            put("url", url)
            put("headers", JSONObject(headers))
        } else {
            put("command", command.orEmpty())
            put("args", org.json.JSONArray(args))
            put("env", JSONObject(env))
        }
        if (!enabled) put("enabled", false)
    }.toString()
}

/**
 * Zed's `agent.notify_when_agent_waiting`. Its two "on" values choose which
 * screens get the pop-up on a desktop; a phone has one screen, so both mean
 * [isOn] here and `never` means what it says. All three names are kept so a
 * file copied from Zed reads as it did there.
 */
enum class NotifyWhenAgentWaiting(val key: String) {
    PrimaryScreen("primary_screen"),
    AllScreens("all_screens"),
    Never("never");

    val isOn: Boolean get() = this != Never

    companion object {
        fun fromKey(key: String?): NotifyWhenAgentWaiting =
            entries.firstOrNull { it.key == key } ?: PrimaryScreen
    }
}

/**
 * Zed's `inlay_hints` (assets/settings/default.json:793-821): off as a
 * whole by default, every kind shown once it is on. [showOtherHints] covers
 * hints with no LSP kind — rust-analyzer's chaining and lifetime hints.
 */
data class InlayHintSettings(
    val enabled: Boolean = false,
    val showTypeHints: Boolean = true,
    val showParameterHints: Boolean = true,
    val showOtherHints: Boolean = true,
) {
    /** Whether a hint of [kind] (`type`, `parameter`, or null) is shown. */
    fun shows(kind: String?): Boolean = enabled && when (kind) {
        "type" -> showTypeHints
        "parameter" -> showParameterHints
        else -> showOtherHints
    }

    companion object {
        fun parse(json: JSONObject?): InlayHintSettings {
            if (json == null) return InlayHintSettings()
            return InlayHintSettings(
                enabled = json.optBoolean("enabled", false),
                showTypeHints = json.optBoolean("show_type_hints", true),
                showParameterHints = json.optBoolean("show_parameter_hints", true),
                showOtherHints = json.optBoolean("show_other_hints", true),
            )
        }
    }
}

/**
 * How Vim's unnamed register and the system clipboard relate — Zed's
 * `vim.use_system_clipboard` (docs/src/vim.md), with Zed's default.
 */
enum class VimClipboard(val key: String, val label: String) {
    /** Every yank and delete lands on the clipboard, and a paste reads it. */
    Always("always", "Always"),
    /** Only the `"+` and `"*` registers touch the clipboard. */
    Never("never", "Never"),
    /** Yanks go to the clipboard; deletes stay in Vim's registers. */
    OnYank("on_yank", "On yank");

    companion object {
        fun fromKey(key: String?): VimClipboard = entries.firstOrNull { it.key == key } ?: Always
    }
}

/**
 * Zed's `markdown_preview` object, as the engine resolves it.
 *
 * Zed makes a preview that tracks its editor a *separate item*
 * (`markdown::OpenFollowingPreview`); this app has one preview panel, so
 * following is a setting and a toolbar toggle. See the engine's
 * `MarkdownPreviewSettings`.
 */
data class MarkdownPreviewSettings(
    /** Whether the preview follows the editor's scroll, and taps jump back. */
    val scrollSync: Boolean = true,
)

/** Zed's `vim` object: the two keys the editor reads. */
data class VimSettings(
    /** Zed's `vim.default_mode`, as the engine spells it (`"normal"`, `"visual_line"`…). */
    val defaultMode: String = "normal",
    val useSystemClipboard: VimClipboard = VimClipboard.Always,
)

data class AppSettings(
    /** Which theme, in Zed's two shapes — see [ThemeSelection]. */
    val themeSelection: ThemeSelection = ThemeSelection.Default,
    /**
     * Zed's `theme_overrides`: a partial style object laid over whichever
     * theme is in effect, kept as the raw JSON text it arrived as. Nothing
     * but the theme layer reads it, and holding it as text is what lets this
     * data class compare by value (`JSONObject` compares by identity).
     */
    val themeOverrides: String = "",
    /** Which icon theme the tree and the tabs draw from — Zed's `icon_theme`. */
    val iconTheme: IconThemeSelection = IconThemeSelection.Default,
    /** The font keys: families, fallbacks, features, weight, line height, sizes. */
    val fonts: FontSettings = FontSettings(),
    /** Whose shortcuts to start from — Zed's `base_keymap`. */
    val baseKeymap: BaseKeymap = BaseKeymap.VSCode,
    /** Editor text size in sp. */
    val bufferFontSize: Float = 14f,
    /** Spaces inserted by the Tab key. */
    val tabSize: Int = 4,
    /** Indent with tab characters rather than spaces — Zed's `hard_tabs`. */
    val hardTabs: Boolean = false,
    /**
     * What a line longer than the editor does.
     *
     * **This default is not Zed's, deliberately** (docs/UI.md, "Settings").
     * Zed's is `none`, which scrolls a long line off the right edge and is
     * the right answer on a 1400px-wide window with a mouse. On a 400dp
     * portrait column there is no horizontal scrollbar worth having and no
     * pointer to fling it with: a `use` line or a `#[account(...)]`
     * attribute simply leaves the screen. The mode and its Fenwick tree
     * already exist (DisplayMap.kt:485-535), so this is a default, not a
     * feature.
     */
    val softWrap: SoftWrapMode = SoftWrapMode.EditorWidth,
    /** The column `bounded` wraps at, and the active wrap guide's column. */
    val preferredLineLength: Int = 80,
    /** Format the file when it is saved — Zed's `format_on_save`. */
    val formatOnSave: FormatOnSave = FormatOnSave.Off,
    /**
     * Save without being asked — Zed's `autosave`.
     *
     * **Also not Zed's default** (docs/UI.md, "Settings"). Zed's is `off`,
     * and on a desktop the cost of that is a ⌘S. Here the cost is a build:
     * `cargo build-sbf` reads the file on disk, takes 71 seconds over it,
     * and reports on a version of the program that is not the one on screen
     * — a 71-second lie, and one that is very hard to see as a stale-file
     * problem. `on_focus_change` is Zed's own "when you leave a file", which
     * is the last moment before that can happen.
     */
    val autosave: Autosave = Autosave.OnFocusChange,
    /** How much of the last session comes back at launch — Zed's `restore_on_startup`. */
    val restoreOnStartup: RestoreOnStartup = RestoreOnStartup.LastSession,
    /**
     * Zed's `close_on_file_delete`: a tab whose file is deleted on disk
     * closes itself. Off by default, as in Zed, and never applied to a tab
     * with unsaved edits — Zed checks `!item.is_dirty` too.
     */
    val closeOnFileDelete: Boolean = false,
    /** Zed's `git.inline_blame.enabled`, whose default is on. */
    val inlineBlame: Boolean = true,
    /** Zed's `inlay_hints`, off by default as in Zed. */
    val inlayHints: InlayHintSettings = InlayHintSettings(),
    /** Zed's `vim_mode`: modal editing in every buffer. Off by default, as in Zed. */
    val vimMode: Boolean = false,
    /** Zed's `vim` object, read only while [vimMode] is on. */
    val vim: VimSettings = VimSettings(),
    /**
     * Where each panel docks and how wide it opens — Zed's `dock` and
     * `default_width`, per panel.
     */
    val panels: Map<String, PanelPlacement> = DEFAULT_PANELS,
    /** How gitignored entries appear in the project tree. */
    val gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
    /** Zed's `tabs`: what a tab shows and how the strip behaves. */
    val tabs: TabSettings = TabSettings(),
    /** Zed's `preview_tabs`: which routes open a provisional tab. */
    val previewTabs: PreviewTabSettings = PreviewTabSettings(),
    /**
     * Zed's `max_tabs`: opening one past this closes the tab gone longest
     * without being looked at. Null is unlimited, which is Zed's default.
     */
    val maxTabs: Int? = null,
    /** The rest of Zed's `project_panel` block. */
    val projectPanel: ProjectPanelSettings = ProjectPanelSettings(),
    /**
     * ACP agents — Zed's `agent_servers`, in name order, and the *only*
     * source of agents there is: the panel names none of its own. The command
     * runs inside the Linux userland, so anything on Debian's PATH that
     * speaks the protocol counts.
     */
    val agents: List<AgentDefinition> = emptyList(),
    /** Zed's `terminal` section: where a shell starts, its environment, its scrollback. */
    val terminal: TerminalSettings = TerminalSettings(),
    /** MCP context servers — Zed's `context_servers`, in name order. */
    val contextServers: List<ContextServerDefinition> = emptyList(),
    /** Zed's `agent.notify_when_agent_waiting`. */
    val notifyWhenAgentWaiting: NotifyWhenAgentWaiting = NotifyWhenAgentWaiting.PrimaryScreen,
    /** Zed's `markdown_preview` — see [MarkdownPreviewSettings]. */
    val markdownPreview: MarkdownPreviewSettings = MarkdownPreviewSettings(),
    /** Zed's `toolbar`: which parts of the row under the tabs are drawn. */
    val toolbar: ToolbarSettings = ToolbarSettings(),
    /** Zed's `tab_bar`: the strip itself and its two button groups. */
    val tabBar: TabBarSettings = TabBarSettings(),
    /** Zed's `status_bar`: the language and cursor-position readouts. */
    val statusBar: StatusBarSettings = StatusBarSettings(),
    /** Zed's `reduce_motion`, plus this platform's `auto` — see [ReduceMotion]. */
    val reduceMotion: ReduceMotion = ReduceMotion.Auto,
    /**
     * Zed's `command_aliases`: a string typed into the command palette and
     * the action name it stands for. Sorted by key, for the reason
     * [parseAgents] gives — a list that reshuffles cannot be learned.
     */
    val commandAliases: Map<String, String> = emptyMap(),
    /**
     * The top-level `show_whitespaces`, `remove_trailing_whitespace_on_save`
     * and `ensure_final_newline_on_save` — the settings screen's rows. The
     * editor reads the *resolved* per-buffer values off
     * [LanguageSettings], because a language or a project may override all
     * three; these are what the screen edits.
     */
    val showWhitespaces: ShowWhitespaces = ShowWhitespaces.Selection,
    val removeTrailingWhitespaceOnSave: Boolean = true,
    val ensureFinalNewlineOnSave: Boolean = true,
    /** Zed's `relative_line_numbers`: the gutter counts from the caret. */
    val relativeLineNumbers: RelativeLineNumbers = RelativeLineNumbers.Disabled,
    /** Zed's `gutter.line_numbers`: the numbers at all. */
    val lineNumbers: Boolean = true,
    /** Zed's `current_line_highlight`. */
    val currentLineHighlight: CurrentLineHighlight = CurrentLineHighlight.All,
    /** Zed's `cursor_shape` and `cursor_blink`. */
    val cursorShape: EditorCursorShape = EditorCursorShape.Bar,
    val cursorBlink: Boolean = true,
    /** Zed's `scrollbar` block — the marks down the track. */
    val scrollbar: ScrollbarSettings = ScrollbarSettings(),
    /** Zed's `minimap` block. */
    val minimap: MinimapSettings = MinimapSettings(),
    /** Zed's `diagnostics.inline` — the error-lens messages. */
    val inlineDiagnostics: InlineDiagnosticsSettings = InlineDiagnosticsSettings(),
) {
    /**
     * Where the panel keyed [settingsKey] sits, falling back to the shipped
     * default. Keyed by string rather than by the UI's enum: settings are the
     * lower layer and cannot see it.
     */
    fun panel(settingsKey: String): PanelPlacement =
        panels[settingsKey] ?: DEFAULT_PANELS.getValue(settingsKey)

    /**
     * How the app picks light or dark, for the callers that only want the
     * mode. A bare theme name has none — the theme's own appearance decides —
     * and answers [ThemeMode.System], which is what "let something else say"
     * means everywhere this is read.
     */
    val theme: ThemeMode get() = themeSelection.mode ?: ThemeMode.System

    companion object {
        /** Keys as the engine names them, for [CoreBridge.setSetting]. */
        const val KEY_THEME = "theme"
        const val KEY_ICON_THEME = "icon_theme"
        const val KEY_BUFFER_FONT_FAMILY = "buffer_font_family"
        const val KEY_BUFFER_FONT_FEATURES = "buffer_font_features"
        const val KEY_BUFFER_FONT_WEIGHT = "buffer_font_weight"
        const val KEY_BUFFER_LINE_HEIGHT = "buffer_line_height"
        const val KEY_UI_FONT_FAMILY = "ui_font_family"
        const val KEY_UI_FONT_SIZE = "ui_font_size"
        const val KEY_BASE_KEYMAP = "base_keymap"
        const val KEY_FONT_SIZE = "buffer_font_size"
        const val KEY_TAB_SIZE = "tab_size"
        const val KEY_HARD_TABS = "hard_tabs"
        const val KEY_PREFERRED_LINE_LENGTH = "preferred_line_length"
        const val KEY_FORMAT_ON_SAVE = "format_on_save"
        const val KEY_AUTOSAVE = "autosave"
        const val KEY_RESTORE_ON_STARTUP = "restore_on_startup"
        const val KEY_CLOSE_ON_FILE_DELETE = "close_on_file_delete"
        const val KEY_SOFT_WRAP = "soft_wrap"
        const val KEY_INLINE_BLAME = "git.inline_blame.enabled"
        const val KEY_NOTIFY_AGENT = "agent.notify_when_agent_waiting"
        const val KEY_INLAY_HINTS = "inlay_hints.enabled"
        const val KEY_INLAY_TYPE_HINTS = "inlay_hints.show_type_hints"
        const val KEY_INLAY_PARAMETER_HINTS = "inlay_hints.show_parameter_hints"
        const val KEY_INLAY_OTHER_HINTS = "inlay_hints.show_other_hints"
        const val KEY_VIM_MODE = "vim_mode"
        const val KEY_VIM_DEFAULT_MODE = "vim.default_mode"
        const val KEY_VIM_CLIPBOARD = "vim.use_system_clipboard"
        const val KEY_MARKDOWN_SCROLL_SYNC = "markdown_preview.scroll_sync"
        const val KEY_REDUCE_MOTION = "reduce_motion"

        /** Zed's `toolbar` block, key by key. */
        const val KEY_TOOLBAR_BREADCRUMBS = "toolbar.breadcrumbs"
        const val KEY_TOOLBAR_QUICK_ACTIONS = "toolbar.quick_actions"
        const val KEY_TOOLBAR_SELECTIONS_MENU = "toolbar.selections_menu"

        /** Zed's `tab_bar` block. */
        const val KEY_TAB_BAR_SHOW = "tab_bar.show"
        const val KEY_TAB_BAR_NAV_BUTTONS = "tab_bar.show_nav_history_buttons"
        const val KEY_TAB_BAR_BUTTONS = "tab_bar.show_tab_bar_buttons"

        /** Zed's `status_bar` block. */
        const val KEY_STATUS_BAR_LANGUAGE = "status_bar.active_language_button"
        const val KEY_STATUS_BAR_CURSOR = "status_bar.cursor_position_button"

        /** The editor's display block, key by key. */
        const val KEY_RELATIVE_LINE_NUMBERS = "relative_line_numbers"
        const val KEY_LINE_NUMBERS = "gutter.line_numbers"
        const val KEY_CURRENT_LINE_HIGHLIGHT = "current_line_highlight"
        const val KEY_CURSOR_SHAPE = "cursor_shape"
        const val KEY_CURSOR_BLINK = "cursor_blink"
        const val KEY_SHOW_WHITESPACES = "show_whitespaces"
        const val KEY_SHOW_WRAP_GUIDES = "show_wrap_guides"
        const val KEY_REMOVE_TRAILING_WHITESPACE = "remove_trailing_whitespace_on_save"
        const val KEY_ENSURE_FINAL_NEWLINE = "ensure_final_newline_on_save"
        const val KEY_MINIMAP_SHOW = "minimap.show"
        const val KEY_SCROLLBAR_SHOW = "scrollbar.show"
        const val KEY_INLINE_DIAGNOSTICS = "diagnostics.inline.enabled"

        /** `project_panel` → `project_panel.dock`. */
        fun keyForDock(panel: String): String = "$panel.dock"

        /**
         * What each panel does when settings.json says nothing. The project
         * tree on the left is *this app's* default rather than Zed's current
         * one — Zed moved its tree to the right — because every file manager
         * on this platform puts it left and it is one line to change.
         */
        val DEFAULT_PANELS: Map<String, PanelPlacement> = mapOf(
            "project_panel" to PanelPlacement(DockSide.Left, 240f),
            // Zed's own `outline_panel` defaults (default.json:955-957).
            "outline_panel" to PanelPlacement(DockSide.Right, 300f),
            "git_panel" to PanelPlacement(DockSide.Right, 360f),
            "project_search" to PanelPlacement(DockSide.Right, 360f),
            "preview" to PanelPlacement(DockSide.Right, 400f),
            "agent_panel" to PanelPlacement(DockSide.Right, 400f),
        )
        const val KEY_GITIGNORED = "project_panel.gitignored_files"

        /** Zed's `tabs` block, key by key. */
        const val KEY_TAB_CLOSE_POSITION = "tabs.close_position"
        const val KEY_TAB_FILE_ICONS = "tabs.file_icons"
        const val KEY_TAB_GIT_STATUS = "tabs.git_status"
        const val KEY_TAB_DIAGNOSTICS = "tabs.show_diagnostics"
        const val KEY_TAB_ACTIVATE_ON_CLOSE = "tabs.activate_on_close"
        const val KEY_PREVIEW_TABS = "preview_tabs.enabled"

        /** The rest of Zed's `project_panel` block. */
        const val KEY_PANEL_SORT = "project_panel.sort_mode"
        const val KEY_PANEL_HIDE_ROOT = "project_panel.hide_root"
        const val KEY_PANEL_AUTO_FOLD = "project_panel.auto_fold_dirs"
        const val KEY_PANEL_SPACING = "project_panel.entry_spacing"
        const val KEY_PANEL_DIAGNOSTICS = "project_panel.show_diagnostics"

        fun parse(json: String): AppSettings = runCatching {
            val root = JSONObject(json)
            val panel = root.optJSONObject("project_panel")
            AppSettings(
                themeSelection = ThemeSelection.parse(root.opt("theme")),
                themeOverrides = root.optJSONObject("theme_overrides")
                    ?.takeIf { it.length() > 0 }?.toString().orEmpty(),
                iconTheme = IconThemeSelection.parse(root.opt("icon_theme")),
                fonts = FontSettings.parse(root),
                baseKeymap = BaseKeymap.fromKey(root.optString("base_keymap", "VSCode")),
                bufferFontSize = root.optDouble("buffer_font_size", 14.0).toFloat(),
                tabSize = root.optInt("tab_size", 4),
                hardTabs = root.optBoolean("hard_tabs", false),
                softWrap = SoftWrapMode.fromKey(
                    root.optString("soft_wrap", SoftWrapMode.EditorWidth.key)
                ),
                preferredLineLength = root.optInt("preferred_line_length", 80),
                formatOnSave = FormatOnSave.fromKey(root.optString("format_on_save", "off")),
                autosave = root.opt("autosave")?.let(Autosave::parse) ?: Autosave.OnFocusChange,
                restoreOnStartup = RestoreOnStartup.fromKey(
                    root.optString("restore_on_startup", "last_session")
                ),
                closeOnFileDelete = root.optBoolean("close_on_file_delete", false),
                inlineBlame = root.optJSONObject("git")
                    ?.optJSONObject("inline_blame")
                    ?.optBoolean("enabled", true) ?: true,
                inlayHints = InlayHintSettings.parse(root.optJSONObject("inlay_hints")),
                vimMode = root.optBoolean("vim_mode", false),
                vim = root.optJSONObject("vim").let { vim ->
                    VimSettings(
                        defaultMode = vim?.optString("default_mode", "normal") ?: "normal",
                        useSystemClipboard = VimClipboard.fromKey(
                            vim?.optString("use_system_clipboard", "always")
                        ),
                    )
                },
                panels = DEFAULT_PANELS.mapValues { (key, fallback) ->
                    val panel = root.optJSONObject(key) ?: return@mapValues fallback
                    PanelPlacement(
                        dock = DockSide.fromKey(panel.optString("dock", fallback.dock.key)),
                        defaultWidth = panel.optDouble(
                            "default_width",
                            fallback.defaultWidth.toDouble(),
                        ).toFloat().coerceIn(120f, 900f),
                    )
                },
                gitignoredFiles = GitignoredFiles.fromKey(
                    panel?.optString("gitignored_files", "dimmed") ?: "dimmed"
                ),
                tabs = parseTabs(root.optJSONObject("tabs")),
                previewTabs = parsePreviewTabs(root.optJSONObject("preview_tabs")),
                // `optInt` cannot tell 0 from absent, and both mean "no cap"
                // here — the engine refuses a zero for the same reason.
                maxTabs = root.optInt("max_tabs", 0).takeIf { it > 0 },
                projectPanel = parseProjectPanel(panel),
                agents = parseAgents(root.optJSONObject("agent_servers")),
                terminal = parseTerminal(root.optJSONObject("terminal")),
                contextServers = parseContextServers(root.optJSONObject("context_servers")),
                notifyWhenAgentWaiting = NotifyWhenAgentWaiting.fromKey(
                    root.optJSONObject("agent")?.optString("notify_when_agent_waiting"),
                ),
                markdownPreview = MarkdownPreviewSettings(
                    scrollSync = root.optJSONObject("markdown_preview")
                        ?.optBoolean("scroll_sync", true) ?: true,
                ),
                toolbar = root.optJSONObject("toolbar").let { toolbar ->
                    val fallback = ToolbarSettings()
                    ToolbarSettings(
                        breadcrumbs = toolbar?.optBoolean("breadcrumbs", fallback.breadcrumbs)
                            ?: fallback.breadcrumbs,
                        quickActions = toolbar?.optBoolean("quick_actions", fallback.quickActions)
                            ?: fallback.quickActions,
                        selectionsMenu = toolbar
                            ?.optBoolean("selections_menu", fallback.selectionsMenu)
                            ?: fallback.selectionsMenu,
                    )
                },
                tabBar = root.optJSONObject("tab_bar").let { bar ->
                    val fallback = TabBarSettings()
                    TabBarSettings(
                        show = bar?.optBoolean("show", fallback.show) ?: fallback.show,
                        showNavHistoryButtons = bar
                            ?.optBoolean("show_nav_history_buttons", fallback.showNavHistoryButtons)
                            ?: fallback.showNavHistoryButtons,
                        showTabBarButtons = bar
                            ?.optBoolean("show_tab_bar_buttons", fallback.showTabBarButtons)
                            ?: fallback.showTabBarButtons,
                    )
                },
                statusBar = root.optJSONObject("status_bar").let { bar ->
                    val fallback = StatusBarSettings()
                    StatusBarSettings(
                        activeLanguageButton = bar
                            ?.optBoolean("active_language_button", fallback.activeLanguageButton)
                            ?: fallback.activeLanguageButton,
                        cursorPositionButton = bar
                            ?.optBoolean("cursor_position_button", fallback.cursorPositionButton)
                            ?: fallback.cursorPositionButton,
                    )
                },
                reduceMotion = ReduceMotion.fromKey(root.optString("reduce_motion", "auto")),
                commandAliases = parseCommandAliases(root.optJSONObject("command_aliases")),
                showWhitespaces = ShowWhitespaces.fromKey(
                    root.optString("show_whitespaces", "selection")
                ),
                removeTrailingWhitespaceOnSave = root.optBoolean(
                    "remove_trailing_whitespace_on_save",
                    true,
                ),
                ensureFinalNewlineOnSave = root.optBoolean("ensure_final_newline_on_save", true),
                relativeLineNumbers = RelativeLineNumbers.fromKey(
                    root.optString("relative_line_numbers", "disabled")
                ),
                lineNumbers = root.optJSONObject("gutter")
                    ?.optBoolean("line_numbers", true) ?: true,
                currentLineHighlight = CurrentLineHighlight.fromKey(
                    root.optString("current_line_highlight", "all")
                ),
                cursorShape = EditorCursorShape.fromKey(root.optString("cursor_shape", "bar")),
                cursorBlink = root.optBoolean("cursor_blink", true),
                scrollbar = ScrollbarSettings.parse(root.optJSONObject("scrollbar")),
                minimap = MinimapSettings.parse(root.optJSONObject("minimap")),
                inlineDiagnostics = InlineDiagnosticsSettings.parse(
                    root.optJSONObject("diagnostics")
                ),
            )
        }.getOrDefault(AppSettings())

        /**
         * `command_aliases` as the palette reads it: a typed string, and the
         * action name it stands for. An entry whose value is not a string is
         * dropped rather than sinking the block — settings.json is a file
         * people edit by hand, and the engine drops it too.
         */
        private fun parseCommandAliases(json: JSONObject?): Map<String, String> {
            if (json == null) return emptyMap()
            return json.keys().asSequence().mapNotNull { alias ->
                val target = json.optString(alias).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                alias to target
            }.sortedBy { it.first }.toMap()
        }

        /** Zed's `tabs` block; anything missing falls back to Zed's default. */
        private fun parseTabs(json: JSONObject?): TabSettings {
            val fallback = TabSettings()
            if (json == null) return fallback
            return TabSettings(
                closePosition = ClosePosition.fromKey(json.optString("close_position", null)),
                fileIcons = json.optBoolean("file_icons", fallback.fileIcons),
                gitStatus = json.optBoolean("git_status", fallback.gitStatus),
                showDiagnostics = ShowDiagnostics.fromKey(
                    json.optString("show_diagnostics", null),
                    fallback.showDiagnostics,
                ),
                activateOnClose = ActivateOnClose.fromKey(
                    json.optString("activate_on_close", null)
                ),
            )
        }

        private fun parsePreviewTabs(json: JSONObject?): PreviewTabSettings {
            val fallback = PreviewTabSettings()
            if (json == null) return fallback
            return PreviewTabSettings(
                enabled = json.optBoolean("enabled", fallback.enabled),
                fromProjectPanel = json.optBoolean(
                    "enable_preview_from_project_panel",
                    fallback.fromProjectPanel,
                ),
                fromFileFinder = json.optBoolean(
                    "enable_preview_from_file_finder",
                    fallback.fromFileFinder,
                ),
                fromCodeNavigation = json.optBoolean(
                    "enable_preview_from_code_navigation",
                    fallback.fromCodeNavigation,
                ),
            )
        }

        private fun parseProjectPanel(json: JSONObject?): ProjectPanelSettings {
            val fallback = ProjectPanelSettings()
            if (json == null) return fallback
            return ProjectPanelSettings(
                sort = ProjectPanelSort.fromKey(json.optString("sort_mode", null)),
                hideRoot = json.optBoolean("hide_root", fallback.hideRoot),
                autoFoldDirs = json.optBoolean("auto_fold_dirs", fallback.autoFoldDirs),
                entrySpacing = EntrySpacing.fromKey(json.optString("entry_spacing", null)),
                // Clamped like the engine's: a hand-edited 0 would stack every
                // level on top of the last, and a 200 would leave no room for
                // the name.
                indentSize = json.optDouble("indent_size", fallback.indentSize.toDouble())
                    .toFloat()
                    .coerceIn(4f, 64f),
                showDiagnostics = ShowDiagnostics.fromKey(
                    json.optString("show_diagnostics", null),
                    fallback.showDiagnostics,
                ),
            )
        }

        /**
         * The `terminal` section as the engine resolved it. The engine has
         * already clamped the scrollback to Zed's ceiling; the emulator's
         * lower one is applied here so a number the engine accepts cannot
         * turn into the emulator's silent default.
         */
        private fun parseTerminal(json: JSONObject?): TerminalSettings {
            if (json == null) return TerminalSettings()
            val env = json.optJSONObject("env")
            return TerminalSettings(
                workingDirectory = TerminalWorkingDirectory.fromKey(
                    json.optString("working_directory", null)
                ),
                env = env?.keys()?.asSequence()?.associateWith { key -> env.optString(key) }.orEmpty(),
                scrollbackLines = json.optInt("max_scroll_history_lines", 10_000)
                    .coerceIn(MIN_SCROLLBACK_LINES, MAX_SCROLLBACK_LINES),
            )
        }

        /** The vendored emulator's `TERMINAL_TRANSCRIPT_ROWS_MIN` / `_MAX`. */
        const val MIN_SCROLLBACK_LINES = 100
        const val MAX_SCROLLBACK_LINES = 50_000

        /**
         * `context_servers` as the settings screen's list: a `command` entry
         * is a stdio server, a `url` entry an HTTP one, and an entry with
         * neither is dropped — the engine drops it too. Name-sorted, for the
         * reason [parseAgents] gives.
         */
        private fun parseContextServers(json: JSONObject?): List<ContextServerDefinition> {
            if (json == null) return emptyList()
            return json.keys().asSequence().mapNotNull { name ->
                val entry = json.optJSONObject(name) ?: return@mapNotNull null
                val enabled = entry.optBoolean("enabled", true)
                val url = entry.optString("url").takeIf { it.isNotBlank() }
                val command = entry.optString("command").takeIf { it.isNotBlank() }
                when {
                    command != null -> {
                        val args = entry.optJSONArray("args")
                        val env = entry.optJSONObject("env")
                        ContextServerDefinition(
                            name = name,
                            command = command,
                            args = List(args?.length() ?: 0) { args!!.optString(it) },
                            env = env?.keys()?.asSequence()
                                ?.associateWith { key -> env.optString(key) }.orEmpty(),
                            enabled = enabled,
                        )
                    }
                    url != null -> {
                        val headers = entry.optJSONObject("headers")
                        ContextServerDefinition(
                            name = name,
                            url = url,
                            headers = headers?.keys()?.asSequence()
                                ?.associateWith { key -> headers.optString(key) }.orEmpty(),
                            enabled = enabled,
                        )
                    }
                    else -> null
                }
            }.sortedBy { it.name }.toList()
        }

        /**
         * `agent_servers` as the panel's own list.
         *
         * An agent with no command is dropped rather than offered: it would
         * be a row that can only fail, and a half-written settings entry is
         * an ordinary state of a file people edit by hand.
         *
         * Sorted by name here, explicitly: the engine sends the map sorted
         * (its `BTreeMap`), but `JSONObject` promises nothing about key
         * order, and a picker that reshuffles between launches would make
         * muscle memory impossible.
         */
        private fun parseAgents(json: JSONObject?): List<AgentDefinition> {
            if (json == null) return emptyList()
            return json.keys().asSequence().mapNotNull { name ->
                val entry = json.optJSONObject(name) ?: return@mapNotNull null
                val command = entry.optString("command").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val args = entry.optJSONArray("args")
                val env = entry.optJSONObject("env")
                AgentDefinition(
                    id = "custom:$name",
                    name = name,
                    argv = listOf(command) + List(args?.length() ?: 0) {
                        args!!.optString(it)
                    },
                    env = env?.keys()?.asSequence()?.associateWith { key ->
                        env.optString(key)
                    }.orEmpty(),
                )
            }.sortedBy { it.name }.toList()
        }

        /** Read the current settings. **Blocking** — call it off the main thread. */
        fun load(): AppSettings = parse(CoreBridge.settings())

        /**
         * Write one setting and return the new resolved settings, or null if
         * the write failed. **Blocking** — call it off the main thread.
         */
        fun set(keyPath: String, valueJson: String): AppSettings? =
            CoreBridge.setSetting(keyPath, valueJson)?.let(::parse)

        /**
         * Add or replace one `agent_servers` entry — the Add Agent form,
         * saved. The name travels whole (never through [set]'s dot-split key
         * path, where "my.agent" would nest). **Blocking** — call it off the
         * main thread.
         */
        fun saveAgent(
            name: String,
            command: String,
            args: List<String>,
            env: Map<String, String> = emptyMap(),
        ): AppSettings? {
            val spec = JSONObject().apply {
                put("command", command)
                put("args", org.json.JSONArray(args))
                put("env", JSONObject(env))
            }
            return CoreBridge.setAgentServer(name, spec.toString())?.let(::parse)
        }

        /** Remove one `agent_servers` entry. **Blocking** — off the main thread. */
        fun removeAgent(name: String): AppSettings? =
            CoreBridge.removeAgentServer(name)?.let(::parse)

        /**
         * Add or replace one `context_servers` entry — the settings screen's
         * context-server form. **Blocking** — off the main thread.
         */
        fun saveContextServer(server: ContextServerDefinition): AppSettings? =
            CoreBridge.setContextServer(server.name, server.toSpecJson())?.let(::parse)

        /** Remove one `context_servers` entry. **Blocking** — off the main thread. */
        fun removeContextServer(name: String): AppSettings? =
            CoreBridge.removeContextServer(name)?.let(::parse)
    }
}
