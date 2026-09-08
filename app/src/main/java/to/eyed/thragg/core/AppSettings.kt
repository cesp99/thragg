package to.eyed.thragg.core

import org.json.JSONObject
import to.eyed.thragg.ui.editor.CurrentLineHighlight
import to.eyed.thragg.ui.editor.EditorCursorShape
import to.eyed.thragg.ui.editor.InlineDiagnosticsSettings
import to.eyed.thragg.ui.editor.RelativeLineNumbers
import to.eyed.thragg.ui.editor.ShowWhitespaces
import to.eyed.thragg.ui.editor.SoftWrapMode

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

/**
 * Which files the project tree marks as having diagnostics — Zed's
 * `ShowDiagnostics`, read for `project_panel.show_diagnostics` (default
 * `all`). Zed shares the enum with `tabs.show_diagnostics`; the tab strip
 * left this build in the 2026-09 demolition, so the panel is its one reader.
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

/**
 * What is left of Zed's `project_panel` block. `sort_mode`, `auto_fold_dirs`
 * and `entry_spacing` are not read any more (docs/UI.md, P8): the tree sorts
 * directories first, folds single-child chains and uses Zed's comfortable
 * pitch, and a row for a key that changes nothing is worse than no row.
 */
data class ProjectPanelSettings(
    val hideRoot: Boolean = false,
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
 * The app's resolved settings, mirroring `engine::Settings`.
 *
 * The engine owns the file — it is JSONC, hand-editable, and keeps its
 * comments through edits made here (see `core/crates/engine/src/config.rs`).
 * This is just the read model; every field is wired to something visible.
 * The keys for surfaces the phone build removed — vim, `base_keymap`, the
 * docks, `tab_bar` / `toolbar` / `status_bar`, `minimap`, `scrollbar`,
 * `preview_tabs`, `icon_theme`, the project panel's sort, fold and spacing —
 * are not modelled at all (docs/UI.md, P8): the engine still parses them off
 * a Zed settings file, and this side has nothing to hand them to.
 * `inlay_hints` went further on 2026-09-08: the engine no longer has the key
 * either, so it is ignored like any unknown one. The 2026-09 demolition added to that list: the tab strip's
 * `tabs` block and `max_tabs`, `close_on_file_delete`, the command palette's
 * `command_aliases`, `markdown_preview.scroll_sync`, `git.inline_blame.enabled`
 * and `restore_on_startup` — the last because session restore here is
 * unconditional (`ui/shell/SessionRestore.kt` never read it, so a row for it
 * would have been a lie).
 */
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
    /** The font keys: families, fallbacks, features, weight, line height, sizes. */
    val fonts: FontSettings = FontSettings(),
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
    /** How gitignored entries appear in the project tree. */
    val gitignoredFiles: GitignoredFiles = GitignoredFiles.Dimmed,
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
    /** Zed's `reduce_motion`, plus this platform's `auto` — see [ReduceMotion]. */
    val reduceMotion: ReduceMotion = ReduceMotion.Auto,
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
    /** Zed's `diagnostics.inline` — the error-lens messages. */
    val inlineDiagnostics: InlineDiagnosticsSettings = InlineDiagnosticsSettings(),
) {
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
        const val KEY_BUFFER_FONT_FAMILY = "buffer_font_family"
        const val KEY_BUFFER_FONT_FEATURES = "buffer_font_features"
        const val KEY_BUFFER_FONT_WEIGHT = "buffer_font_weight"
        const val KEY_BUFFER_LINE_HEIGHT = "buffer_line_height"
        const val KEY_UI_FONT_FAMILY = "ui_font_family"
        const val KEY_UI_FONT_SIZE = "ui_font_size"
        const val KEY_FONT_SIZE = "buffer_font_size"
        const val KEY_TAB_SIZE = "tab_size"
        const val KEY_HARD_TABS = "hard_tabs"
        const val KEY_PREFERRED_LINE_LENGTH = "preferred_line_length"
        const val KEY_FORMAT_ON_SAVE = "format_on_save"
        const val KEY_AUTOSAVE = "autosave"
        const val KEY_SOFT_WRAP = "soft_wrap"
        const val KEY_NOTIFY_AGENT = "agent.notify_when_agent_waiting"
        const val KEY_REDUCE_MOTION = "reduce_motion"

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
        const val KEY_INLINE_DIAGNOSTICS = "diagnostics.inline.enabled"

        /** How the tree treats gitignored entries — see [GitignoredFiles]. */
        const val KEY_GITIGNORED = "project_panel.gitignored_files"

        /** The rest of Zed's `project_panel` block. */
        const val KEY_PANEL_HIDE_ROOT = "project_panel.hide_root"
        const val KEY_PANEL_DIAGNOSTICS = "project_panel.show_diagnostics"

        fun parse(json: String): AppSettings = runCatching {
            val root = JSONObject(json)
            val panel = root.optJSONObject("project_panel")
            AppSettings(
                themeSelection = ThemeSelection.parse(root.opt("theme")),
                themeOverrides = root.optJSONObject("theme_overrides")
                    ?.takeIf { it.length() > 0 }?.toString().orEmpty(),
                fonts = FontSettings.parse(root),
                bufferFontSize = root.optDouble("buffer_font_size", 14.0).toFloat(),
                tabSize = root.optInt("tab_size", 4),
                hardTabs = root.optBoolean("hard_tabs", false),
                softWrap = SoftWrapMode.fromKey(
                    root.optString("soft_wrap", SoftWrapMode.EditorWidth.key)
                ),
                preferredLineLength = root.optInt("preferred_line_length", 80),
                formatOnSave = FormatOnSave.fromKey(root.optString("format_on_save", "off")),
                autosave = root.opt("autosave")?.let(Autosave::parse) ?: Autosave.OnFocusChange,
                gitignoredFiles = GitignoredFiles.fromKey(
                    panel?.optString("gitignored_files", "dimmed") ?: "dimmed"
                ),
                projectPanel = parseProjectPanel(panel),
                agents = parseAgents(root.optJSONObject("agent_servers")),
                terminal = parseTerminal(root.optJSONObject("terminal")),
                contextServers = parseContextServers(root.optJSONObject("context_servers")),
                notifyWhenAgentWaiting = NotifyWhenAgentWaiting.fromKey(
                    root.optJSONObject("agent")?.optString("notify_when_agent_waiting"),
                ),
                reduceMotion = ReduceMotion.fromKey(root.optString("reduce_motion", "auto")),
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
                inlineDiagnostics = InlineDiagnosticsSettings.parse(
                    root.optJSONObject("diagnostics")
                ),
            )
        }.getOrDefault(AppSettings())

        private fun parseProjectPanel(json: JSONObject?): ProjectPanelSettings {
            val fallback = ProjectPanelSettings()
            if (json == null) return fallback
            return ProjectPanelSettings(
                hideRoot = json.optBoolean("hide_root", fallback.hideRoot),
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
