package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.eyed.seeker.code.R
import to.eyed.seeker.code.core.AgentDefinition
import to.eyed.seeker.code.core.ContextServerDefinition
import to.eyed.seeker.code.core.NotifyWhenAgentWaiting
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.Autosave
import to.eyed.seeker.code.core.BaseKeymap
import to.eyed.seeker.code.core.DockSide
import to.eyed.seeker.code.core.FormatOnSave
import to.eyed.seeker.code.core.ActivateOnClose
import to.eyed.seeker.code.core.ClosePosition
import to.eyed.seeker.code.core.EntrySpacing
import to.eyed.seeker.code.core.GitignoredFiles
import to.eyed.seeker.code.core.BufferLineHeight
import to.eyed.seeker.code.core.FontSettings
import to.eyed.seeker.code.core.ProjectPanelSort
import to.eyed.seeker.code.core.ReduceMotion
import to.eyed.seeker.code.core.ShowDiagnostics
import to.eyed.seeker.code.core.RestoreOnStartup
import to.eyed.seeker.code.core.ThemeMode
import to.eyed.seeker.code.core.VimClipboard
import to.eyed.seeker.code.ui.agent.isAgentPanelSupported
import to.eyed.seeker.code.ui.editor.CurrentLineHighlight
import to.eyed.seeker.code.ui.editor.EditorCursorShape
import to.eyed.seeker.code.ui.editor.ShowMinimap
import to.eyed.seeker.code.ui.editor.ShowWhitespaces
import to.eyed.seeker.code.ui.editor.SoftWrapMode
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.BundledFonts
import to.eyed.seeker.code.ui.theme.FontCatalog
import to.eyed.seeker.code.ui.theme.IconThemes
import to.eyed.seeker.code.ui.theme.LocalZedTheme

private val FONT_SIZES = listOf(10f, 12f, 14f, 16f, 18f, 22f, 28f)

/**
 * The interface sizes offered, inside the engine's own 10..32 clamp. 16 is
 * Zed's default and therefore the size every chrome number in this app is
 * written against (assets/settings/default.json:71).
 */
private val UI_FONT_SIZES = listOf(12f, 14f, 16f, 18f, 20f, 24f)
private val TAB_SIZES = listOf(2, 4, 8)

/**
 * The CSS weights a row offers. Zed's `buffer_font_weight` takes any hundred
 * from 100 to 900; these four are the ones a monospace face actually ships,
 * and anything else is a number in settings.json.
 */
private val FONT_WEIGHTS: List<Pair<Float, String>> = listOf(
    300f to "Light",
    400f to "Regular",
    500f to "Medium",
    700f to "Bold",
)

/** Zed's two words, and the two customs worth a tap. */
private val LINE_HEIGHTS: List<BufferLineHeight> = listOf(
    BufferLineHeight.Standard,
    BufferLineHeight.Custom(1.45f),
    BufferLineHeight.Comfortable,
    BufferLineHeight.Custom(1.9f),
)

/** Zed's default is 80; 100 and 120 are the other two anyone sets. */
private val LINE_LENGTHS = listOf(80, 100, 120)

/**
 * The autosave choices as the row offers them. The delay is one value —
 * Zed's own example is 500 ms and its Settings UI offers a number field;
 * a second is the one a phone keyboard will not make you type.
 */
private val AUTOSAVE_CHOICES: List<Pair<Autosave, String>> = listOf(
    Autosave.Off to "Off",
    Autosave.OnFocusChange to "On tab change",
    Autosave.OnWindowChange to "In background",
    Autosave.AfterDelay(Autosave.DEFAULT_DELAY_MS) to "After 1 s idle",
)

/**
 * The row an autosave value selects: any `after_delay` is the delay row,
 * whatever its milliseconds say — settings.json may hold 500, the row
 * still means "after a delay".
 */
private fun Autosave.asChoice(): Autosave =
    if (this is Autosave.AfterDelay) Autosave.AfterDelay(Autosave.DEFAULT_DELAY_MS) else this

/**
 * Whether a row survives the filter box: Zed's settings window filters its
 * rows by title and description (settings_ui/src/settings_ui.rs, the
 * search field), and so does this one — a row is shown when any of its
 * words matches, or when the box is empty.
 */
internal fun matchesSettingsFilter(filter: String, vararg words: String?): Boolean {
    val needle = filter.trim()
    if (needle.isEmpty()) return true
    return words.any { it != null && it.contains(needle, ignoreCase = true) }
}

/** Zed's `rounded_md`, the corner every input box in this app wears. */
private val FieldRadius = 6.dp

/**
 * What the Add Agent form is holding — Zed's `CustomAgentForm`
 * (settings_ui/src/pages/external_agents_page.rs:329-347), minus the env
 * rows: environment variables stay a settings.json affair here, and an edit
 * carries an entry's existing env through untouched.
 */
private data class AgentForm(
    /** Set when editing — used to remove the old entry on rename, as Zed does. */
    val originalName: String? = null,
    val name: String = "",
    val command: String = "",
    val args: String = "",
    val error: String? = null,
)

/**
 * What the Add Context Server form is holding — Zed's MCP server modal
 * (agent_ui/src/agent_configuration/configure_context_server_modal.rs), as
 * three fields: a name, a command *or* an `https://` URL, and arguments.
 * Environment variables and headers stay a settings.json affair, and an
 * edit carries an entry's existing ones through untouched.
 */
private data class ContextServerForm(
    val originalName: String? = null,
    val name: String = "",
    /** A program to run over stdio, or an `http(s)://` URL to connect to. */
    val command: String = "",
    val args: String = "",
    val error: String? = null,
)

/**
 * Settings, in sections.
 *
 * Zed's settings window is pages of sections (settings_ui/src/page_data.rs —
 * General, Appearance, Editor, …); a phone dialog has no room for a page
 * rail, so the pages become sections of one scroll, each under a header.
 * Every control writes one key into the JSONC settings file through the
 * engine, which preserves the file's comments — this screen and a hand-edited
 * file are two views of the same thing, never competing sources of truth.
 */
/**
 * The three answers `show_diagnostics` takes, in both places it appears — the
 * tabs and the tree — so the two rows cannot drift apart.
 */
private val DIAGNOSTIC_CHOICES = listOf(
    ShowDiagnostics.Off to "Never",
    ShowDiagnostics.Errors to "Errors",
    ShowDiagnostics.All to "Errors and warnings",
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    settingsPath: String?,
    isFileValid: Boolean,
    /** Set when the last write was refused; see [AppSettings.set]. */
    refusal: String? = null,
    onSet: (keyPath: String, valueJson: String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Open the theme selector — the only place a theme *name* is chosen,
     * because it previews each one on the file you were reading and a row of
     * eleven names cannot.
     */
    onOpenThemeSelector: () -> Unit = {},
    /**
     * Open settings.json itself in the editor — the route to every key this
     * screen has no row for. Null where there is nowhere to open a tab, and
     * the path is then shown as plain text.
     */
    onEditFile: (() -> Unit)? = null,
    /** Open the built-in defaults as a read-only tab — `zed::OpenDefaultSettings`. */
    onOpenDefaultSettings: (() -> Unit)? = null,
    /** Open (creating) the project's `.zed/settings.json` — `zed::OpenProjectSettings`. */
    onOpenProjectSettings: (() -> Unit)? = null,
    /**
     * What the last keymap load could not use, sentence by sentence — the
     * settings screen's copy of the strip the workspace shows, so the
     * reason a binding is not working is where someone goes to look for it.
     */
    keymapErrors: List<String> = emptyList(),
    /** Open keymap.json in the editor — the touch route to rebinding a key. */
    onEditKeymap: (() -> Unit)? = null,
    /**
     * Save an agent from the Add Agent form: remove [AgentForm.originalName]
     * when renaming, then write the entry. Null hides the whole External
     * Agents section (the `play` edition, which has no agent panel at all).
     */
    onSaveAgent: ((originalName: String?, name: String, command: String, args: List<String>) -> Unit)? = null,
    /** Remove one configured agent — Zed's trash button. */
    onRemoveAgent: ((name: String) -> Unit)? = null,
    /**
     * Save a context server from its form — absent with [onSaveAgent], for
     * the same reason: no userland, no agent to hand a server to.
     */
    onSaveContextServer: ((originalName: String?, server: ContextServerDefinition) -> Unit)? = null,
    /** Remove one `context_servers` entry by name. */
    onRemoveContextServer: ((name: String) -> Unit)? = null,
    /** Open the About dialog — the version, and the specs a bug report needs. */
    onAbout: (() -> Unit)? = null,
    /** Show the welcome screen again — Zed's `zed::OpenOnboarding`. */
    onOpenOnboarding: (() -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    // Every appearance key is in settings.json now, so this screen reads them
    // where it reads everything else. What it still has to go out for is the
    // *list* of fonts, which is two directories and the platform's own.
    val uiFontSize = settings.fonts.uiSize
    val fontFamilies by produceState(emptyList<FontCatalog.Family>()) {
        value = withContext(Dispatchers.IO) { FontCatalog.installed(context) }
    }
    val iconThemeScan by IconThemes.scan.collectAsState()
    var agentForm by remember { mutableStateOf<AgentForm?>(null) }
    var contextServerForm by remember { mutableStateOf<ContextServerForm?>(null) }
    // Zed's settings window has a search box over its rows; with fourteen
    // rows in one scroll this one earns its own.
    var filter by remember { mutableStateOf("") }
    fun shown(vararg words: String?): Boolean = matchesSettingsFilter(filter, *words)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface),
            modifier = Modifier.widthIn(min = 320.dp, max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.settings_settings),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )

                if (!isFileValid) {
                    Text(
                        text = stringResource(R.string.settings_settings_json_could_not_be_parsed),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }

                // A value the engine refused never reached the file, and the
                // rest of the settings are untouched — but silence here is
                // what let a broken command look like a working one.
                if (refusal != null) {
                    Text(
                        text = refusal,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.color("error", MaterialTheme.colorScheme.error),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }

                FilterField(value = filter, onValue = { filter = it })

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp)
                ) {
                    // Zed's Appearance page: the theme, the icon theme and
                    // the two fonts (page_data.rs:507, 874).
                    val showTheme = shown("Theme", "appearance", "light", "dark", "theme")
                    val showIconTheme = shown("Icon theme", "appearance", "icon_theme", "icons")
                    val showUiSize = shown("Interface size", "appearance", "ui_font_size")
                    val showUiFont = shown("Interface font", "appearance", "ui_font_family")
                    val showBufferFont =
                        shown("Editor font", "appearance", "buffer_font_family", "monospace")
                    val showWeight = shown("Editor font weight", "appearance", "buffer_font_weight")
                    val showLigatures =
                        shown("Ligatures", "appearance", "buffer_font_features", "calt", "liga")
                    val showLineHeight =
                        shown("Line height", "appearance", "buffer_line_height", "leading")
                    if (showTheme || showIconTheme || showUiSize || showUiFont ||
                        showBufferFont || showWeight || showLigatures || showLineHeight
                    ) {
                        SectionHeader(
                            stringResource(R.string.settings_appearance),
                            subtitle = stringResource(R.string.settings_themes_fonts_and_icon_themes_you),
                        )
                    }
                    // The mode, not the names: which theme fills each slot is
                    // the theme selector's job, because it previews as it goes
                    // and a list of names in a settings row cannot.
                    if (showTheme) ChoiceRow(
                        label = stringResource(R.string.settings_appearance),
                        detail = "Zed's theme.mode — \"${settings.themeSelection.light}\" " +
                            "light, \"${settings.themeSelection.dark}\" dark",
                        options = ThemeMode.entries.map { it to it.label() },
                        selected = settings.themeSelection.mode ?: ThemeMode.System,
                        onSelect = {
                            onSet(
                                AppSettings.KEY_THEME,
                                settings.themeSelection.withMode(it).toJson(),
                            )
                        },
                    )
                    if (showTheme) LinkRow(
                        label = stringResource(R.string.settings_theme),
                        detail = stringResource(R.string.settings_pick_a_theme_previewing_each_as),
                        onClick = onOpenThemeSelector,
                    )
                    if (showIconTheme) ChoiceRow(
                        label = stringResource(R.string.settings_icon_theme),
                        detail = stringResource(R.string.settings_zed_s_icon_theme_which_icons),
                        options = iconThemeScan.themes.map { it.name to it.name },
                        selected = settings.iconTheme.iconThemeName(theme.isDark),
                        onSelect = {
                            onSet(AppSettings.KEY_ICON_THEME, settings.iconTheme.with(it).toJson())
                        },
                    )
                    // Zed's rem *is* `ui_font_size`
                    // (theme_settings/src/settings.rs:619), so this row
                    // resizes the whole chrome — rows, bars, gaps, icons —
                    // rather than only its text.
                    if (showUiSize) ChoiceRow(
                        label = stringResource(R.string.settings_interface_size),
                        detail = stringResource(R.string.settings_zed_s_ui_font_size_the),
                        options = UI_FONT_SIZES.map { it to it.toInt().toString() },
                        selected = UI_FONT_SIZES.minByOrNull {
                            kotlin.math.abs(it - uiFontSize)
                        } ?: FontSettings.DEFAULT_UI_FONT_SIZE,
                        onSelect = { onSet(AppSettings.KEY_UI_FONT_SIZE, it.toInt().toString()) },
                    )
                    if (showUiFont) FontRow(
                        label = stringResource(R.string.settings_interface_font),
                        detail = stringResource(R.string.settings_zed_s_ui_font_family),
                        families = fontFamilies,
                        selected = settings.fonts.uiFamily,
                        bundledName = BundledFonts.UI,
                        preview = "Project panel · Settings · Terminal",
                        previewSize = uiFontSize,
                        onSelect = {
                            onSet(
                                AppSettings.KEY_UI_FONT_FAMILY,
                                if (it == null) "null" else JSONObject.quote(it),
                            )
                        },
                    )
                    if (showBufferFont) FontRow(
                        label = stringResource(R.string.settings_editor_font),
                        detail = stringResource(R.string.settings_zed_s_buffer_font_family_the),
                        families = fontFamilies,
                        selected = settings.fonts.bufferFamily,
                        bundledName = BundledFonts.BUFFER,
                        preview = "fn main() { 0O1lI ==> != }",
                        previewSize = settings.bufferFontSize,
                        isMonospacePreview = true,
                        onSelect = {
                            onSet(
                                AppSettings.KEY_BUFFER_FONT_FAMILY,
                                if (it == null) "null" else JSONObject.quote(it),
                            )
                        },
                    )
                    if (showWeight) ChoiceRow(
                        label = stringResource(R.string.settings_editor_font_weight),
                        detail = stringResource(R.string.settings_zed_s_buffer_font_weight_in),
                        options = FONT_WEIGHTS,
                        selected = FONT_WEIGHTS.minByOrNull {
                            kotlin.math.abs(it.first - settings.fonts.bufferWeight)
                        }?.first ?: 400f,
                        onSelect = {
                            onSet(AppSettings.KEY_BUFFER_FONT_WEIGHT, it.toInt().toString())
                        },
                    )
                    // Android exposes OpenType features as one
                    // `fontFeatureSettings` string, so the honest row is the
                    // pair of tags that spell "ligatures" between them. Finer
                    // control is a `buffer_font_features` object in the file.
                    if (showLigatures) ChoiceRow(
                        label = stringResource(R.string.settings_ligatures),
                        detail = stringResource(R.string.settings_zed_s_buffer_font_features_calt),
                        options = listOf(true to "On", false to "Off"),
                        selected = !settings.fonts.ligaturesOff,
                        onSelect = { on ->
                            onSet(
                                AppSettings.KEY_BUFFER_FONT_FEATURES,
                                if (on) "{}" else "{\"calt\":false,\"liga\":false}",
                            )
                        },
                    )
                    if (showLineHeight) ChoiceRow(
                        label = stringResource(R.string.settings_line_height),
                        detail = stringResource(R.string.settings_zed_s_buffer_line_height),
                        options = LINE_HEIGHTS.map { it to it.label },
                        selected = LINE_HEIGHTS.minByOrNull {
                            kotlin.math.abs(it.value - settings.fonts.bufferLineHeight.value)
                        } ?: BufferLineHeight.Comfortable,
                        onSelect = { onSet(AppSettings.KEY_BUFFER_LINE_HEIGHT, it.toJson()) },
                    )

                    val showFont = shown("Editor font size", "editor", "buffer_font_size")
                    val showTab = shown("Tab width", "editor", "tab_size", "indent")
                    val showHardTabs = shown("Indent with", "editor", "hard_tabs", "tabs", "spaces", "indent")
                    val showWrap = shown("Wrap long lines", "editor", "soft_wrap")
                    val showLineLength = shown("Preferred line length", "editor", "preferred_line_length", "wrap guide", "column")
                    val showFormat = shown("Format on save", "editor", "format_on_save", "formatter")
                    val showAutosave = shown("Autosave", "editor", "autosave", "save")
                    val showInlay = shown("Inlay hints", "editor", "inlay_hints", "type hints", "parameter hints", "lsp")
                    val showVim = shown("Vim mode", "editor", "vim", "vim_mode", "modal", "clipboard", "default_mode")
                    val showWhitespace = shown("Show whitespace", "editor", "show_whitespaces", "spaces", "tabs", "invisible")
                    val showTrailing = shown("Trim trailing whitespace on save", "editor", "remove_trailing_whitespace_on_save", "save")
                    val showFinalNewline = shown("Final newline on save", "editor", "ensure_final_newline_on_save", "save")
                    val showLineNumbers = shown("Line numbers", "editor", "gutter", "line_numbers", "relative_line_numbers", "gutter")
                    val showLineWash = shown("Highlight the current line", "editor", "current_line_highlight")
                    val showCursor = shown("Cursor shape", "editor", "cursor_shape", "cursor_blink", "caret")
                    val showMinimap = shown("Minimap", "editor", "minimap", "map")
                    val showInline = shown("Inline diagnostics", "editor", "diagnostics", "error lens", "inline")
                    if (showFont || showTab || showHardTabs || showWrap || showLineLength ||
                        showFormat || showAutosave || showInlay || showVim ||
                        showWhitespace || showTrailing || showFinalNewline || showLineNumbers ||
                        showLineWash || showCursor || showMinimap || showInline
                    ) {
                        SectionDivider()
                        SectionHeader(
                            stringResource(R.string.settings_editor),
                            subtitle = stringResource(R.string.settings_per_language_overrides_languages_and_language),
                        )
                    }
                    if (showFont) ChoiceRow(
                        label = stringResource(R.string.settings_editor_font_size),
                        detail = null,
                        options = FONT_SIZES.map { it to it.toInt().toString() },
                        selected = FONT_SIZES.minByOrNull {
                            kotlin.math.abs(it - settings.bufferFontSize)
                        } ?: 14f,
                        onSelect = { onSet(AppSettings.KEY_FONT_SIZE, it.toInt().toString()) },
                    )
                    if (showTab) ChoiceRow(
                        label = stringResource(R.string.settings_tab_width),
                        detail = stringResource(R.string.settings_spaces_inserted_by_the_tab_key),
                        options = TAB_SIZES.map { it to it.toString() },
                        selected = settings.tabSize,
                        onSelect = { onSet(AppSettings.KEY_TAB_SIZE, it.toString()) },
                    )
                    if (showHardTabs) ChoiceRow(
                        label = stringResource(R.string.settings_indent_with),
                        detail = stringResource(R.string.settings_zed_s_hard_tabs_a_file),
                        options = listOf(false to "Spaces", true to "Tabs"),
                        selected = settings.hardTabs,
                        onSelect = { onSet(AppSettings.KEY_HARD_TABS, it.toString()) },
                    )
                    if (showWrap) ChoiceRow(
                        label = stringResource(R.string.settings_wrap_long_lines),
                        detail = stringResource(R.string.settings_zed_s_soft_wrap_bounded_wraps),
                        options = listOf(
                            SoftWrapMode.None to "Off",
                            SoftWrapMode.EditorWidth to "At the editor's width",
                            SoftWrapMode.Bounded to "Bounded",
                        ),
                        selected = settings.softWrap,
                        onSelect = { onSet(AppSettings.KEY_SOFT_WRAP, "\"${it.key}\"") },
                    )
                    if (showLineLength) ChoiceRow(
                        label = stringResource(R.string.settings_preferred_line_length),
                        detail = stringResource(R.string.settings_where_bounded_wrapping_breaks_drawn_as),
                        options = LINE_LENGTHS.map { it to it.toString() },
                        selected = LINE_LENGTHS.minByOrNull {
                            kotlin.math.abs(it - settings.preferredLineLength)
                        } ?: 80,
                        onSelect = { onSet(AppSettings.KEY_PREFERRED_LINE_LENGTH, it.toString()) },
                    )
                    if (showFormat) ChoiceRow(
                        label = stringResource(R.string.settings_format_on_save),
                        detail = stringResource(R.string.settings_through_the_language_server_or_the),
                        options = listOf(
                            FormatOnSave.Off to "Off",
                            FormatOnSave.On to "On",
                            FormatOnSave.LanguageServer to "Language server only",
                        ),
                        selected = settings.formatOnSave,
                        onSelect = { onSet(AppSettings.KEY_FORMAT_ON_SAVE, "\"${it.key}\"") },
                    )
                    if (showAutosave) ChoiceRow(
                        label = stringResource(R.string.settings_autosave),
                        detail = stringResource(R.string.settings_delayed_saves_skip_the_formatter_as),
                        options = AUTOSAVE_CHOICES,
                        selected = settings.autosave.asChoice(),
                        onSelect = { onSet(AppSettings.KEY_AUTOSAVE, it.toJson()) },
                    )
                    // Zed's display block: `show_whitespaces`, the gutter's two
                    // switches, `current_line_highlight`, the caret's shape
                    // and blink, the minimap and the inline diagnostics. Each
                    // is the key of the same name in settings.json; the
                    // per-editor toggles for the last four are palette rows.
                    if (showWhitespace) ChoiceRow(
                        label = stringResource(R.string.settings_show_whitespace),
                        detail = stringResource(R.string.settings_zed_s_show_whitespaces_a_for),
                        options = listOf(
                            ShowWhitespaces.Off to "Never",
                            ShowWhitespaces.Selection to "In the selection",
                            ShowWhitespaces.Boundary to "At boundaries",
                            ShowWhitespaces.Trailing to "Trailing only",
                            ShowWhitespaces.All to "Always",
                        ),
                        selected = settings.showWhitespaces,
                        onSelect = { onSet(AppSettings.KEY_SHOW_WHITESPACES, "\"${it.key}\"") },
                    )
                    if (showTrailing) ChoiceRow(
                        label = stringResource(R.string.settings_trim_trailing_whitespace_on_save),
                        detail = stringResource(R.string.settings_zed_s_remove_trailing_whitespace_on),
                        options = listOf(true to "On", false to "Off"),
                        selected = settings.removeTrailingWhitespaceOnSave,
                        onSelect = { onSet(AppSettings.KEY_REMOVE_TRAILING_WHITESPACE, it.toString()) },
                    )
                    if (showFinalNewline) ChoiceRow(
                        label = stringResource(R.string.settings_final_newline_on_save),
                        detail = stringResource(R.string.settings_zed_s_ensure_final_newline_on),
                        options = listOf(true to "On", false to "Off"),
                        selected = settings.ensureFinalNewlineOnSave,
                        onSelect = { onSet(AppSettings.KEY_ENSURE_FINAL_NEWLINE, it.toString()) },
                    )
                    if (showLineNumbers) ChoiceRow(
                        label = stringResource(R.string.settings_line_numbers),
                        detail = stringResource(R.string.settings_zed_s_gutter_line_numbers_and),
                        options = listOf(
                            "absolute" to "Absolute",
                            "relative" to "Relative to the cursor",
                            "off" to "Hidden",
                        ),
                        selected = when {
                            !settings.lineNumbers -> "off"
                            settings.relativeLineNumbers.isRelative -> "relative"
                            else -> "absolute"
                        },
                        onSelect = { choice ->
                            onSet(AppSettings.KEY_LINE_NUMBERS, (choice != "off").toString())
                            onSet(
                                AppSettings.KEY_RELATIVE_LINE_NUMBERS,
                                if (choice == "relative") "\"enabled\"" else "\"disabled\"",
                            )
                        },
                    )
                    if (showLineWash) ChoiceRow(
                        label = stringResource(R.string.settings_highlight_the_current_line),
                        detail = stringResource(R.string.settings_zed_s_current_line_highlight),
                        options = listOf(
                            CurrentLineHighlight.All to "Gutter and text",
                            CurrentLineHighlight.Line to "Text only",
                            CurrentLineHighlight.Gutter to "Gutter only",
                            CurrentLineHighlight.None to "Off",
                        ),
                        selected = settings.currentLineHighlight,
                        onSelect = { onSet(AppSettings.KEY_CURRENT_LINE_HIGHLIGHT, "\"${it.key}\"") },
                    )
                    if (showCursor) ChoiceRow(
                        label = stringResource(R.string.settings_cursor_shape),
                        detail = stringResource(R.string.settings_zed_s_cursor_shape_vim_mode),
                        options = listOf(
                            EditorCursorShape.Bar to "Bar",
                            EditorCursorShape.Block to "Block",
                            EditorCursorShape.Underline to "Underline",
                            EditorCursorShape.Hollow to "Hollow",
                        ),
                        selected = settings.cursorShape,
                        onSelect = { onSet(AppSettings.KEY_CURSOR_SHAPE, "\"${it.key}\"") },
                    )
                    if (showCursor) ChoiceRow(
                        label = stringResource(R.string.settings_blink_the_cursor),
                        detail = stringResource(R.string.settings_zed_s_cursor_blink),
                        options = listOf(true to "On", false to "Off"),
                        selected = settings.cursorBlink,
                        onSelect = { onSet(AppSettings.KEY_CURSOR_BLINK, it.toString()) },
                    )
                    if (showMinimap) ChoiceRow(
                        label = stringResource(R.string.settings_minimap),
                        detail = stringResource(R.string.settings_zed_s_minimap_the_file_as),
                        options = listOf(
                            ShowMinimap.Never to "Never",
                            ShowMinimap.Auto to "With the scrollbar",
                            ShowMinimap.Always to "Always",
                        ),
                        selected = settings.minimap.show,
                        onSelect = { onSet(AppSettings.KEY_MINIMAP_SHOW, "\"${it.key}\"") },
                    )
                    if (showInline) ChoiceRow(
                        label = stringResource(R.string.settings_inline_diagnostics),
                        detail = stringResource(R.string.settings_zed_s_diagnostics_inline_the_message),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.inlineDiagnostics.enabled,
                        onSelect = { onSet(AppSettings.KEY_INLINE_DIAGNOSTICS, it.toString()) },
                    )
                    // Zed's `inlay_hints` block, its four keys as four rows
                    // (assets/settings/default.json:793-821). The kinds stay
                    // listed while hints are off, as Zed's settings page
                    // lists them, so what will appear can be chosen first.
                    if (showInlay) ChoiceRow(
                        label = stringResource(R.string.settings_inlay_hints),
                        detail = stringResource(R.string.settings_types_and_parameter_names_from_the),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.inlayHints.enabled,
                        onSelect = { onSet(AppSettings.KEY_INLAY_HINTS, it.toString()) },
                    )
                    if (showInlay) ChoiceRow(
                        label = stringResource(R.string.settings_type_hints),
                        detail = stringResource(R.string.settings_i32_after_a_binding),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.inlayHints.showTypeHints,
                        onSelect = { onSet(AppSettings.KEY_INLAY_TYPE_HINTS, it.toString()) },
                    )
                    if (showInlay) ChoiceRow(
                        label = stringResource(R.string.settings_parameter_hints),
                        detail = stringResource(R.string.settings_name_before_an_argument),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.inlayHints.showParameterHints,
                        onSelect = { onSet(AppSettings.KEY_INLAY_PARAMETER_HINTS, it.toString()) },
                    )
                    if (showInlay) ChoiceRow(
                        label = stringResource(R.string.settings_other_hints),
                        detail = stringResource(R.string.settings_chaining_lifetimes_hints_of_no_particular),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.inlayHints.showOtherHints,
                        onSelect = { onSet(AppSettings.KEY_INLAY_OTHER_HINTS, it.toString()) },
                    )
                    // Zed's welcome-screen checkbox and its `vim_mode` key
                    // (docs/src/vim.md "Enabling and disabling vim mode"),
                    // with the two `vim` keys the editor reads beneath it.
                    if (showVim) ChoiceRow(
                        label = stringResource(R.string.settings_vim_mode),
                        detail = stringResource(R.string.settings_zed_s_vim_mode_modal_editing),
                        options = listOf(true to "On", false to "Off"),
                        selected = settings.vimMode,
                        onSelect = { onSet(AppSettings.KEY_VIM_MODE, it.toString()) },
                    )
                    if (showVim && settings.vimMode) {
                        ChoiceRow(
                            label = stringResource(R.string.settings_vim_starts_in),
                            detail = stringResource(R.string.settings_zed_s_vim_default_mode),
                            options = listOf(
                                "normal" to "Normal",
                                "insert" to "Insert",
                                "visual" to "Visual",
                                "replace" to "Replace",
                            ),
                            selected = settings.vim.defaultMode,
                            onSelect = { onSet(AppSettings.KEY_VIM_DEFAULT_MODE, "\"$it\"") },
                        )
                        ChoiceRow(
                            label = stringResource(R.string.settings_vim_and_the_clipboard),
                            detail = stringResource(R.string.settings_zed_s_vim_use_system_clipboard),
                            options = VimClipboard.entries.map { it to it.label },
                            selected = settings.vim.useSystemClipboard,
                            onSelect = { onSet(AppSettings.KEY_VIM_CLIPBOARD, "\"${it.key}\"") },
                        )
                    }

                    // Zed's General page carries `base_keymap` as "Keymap"
                    // (settings_ui/src/page_data.rs); the keymap file is
                    // one link away, as it is from Zed's keymap editor.
                    if (shown("Base keymap", "keyboard", "keymap", "shortcuts", "base_keymap")) {
                        SectionDivider()
                        SectionHeader(
                            stringResource(R.string.settings_keyboard),
                            subtitle = stringResource(R.string.settings_whose_shortcuts_to_start_from_none),
                        )
                        ChoiceRow(
                            label = stringResource(R.string.settings_base_keymap),
                            detail = stringResource(R.string.settings_zed_s_base_keymap),
                            options = BaseKeymap.entries.map { it to it.label },
                            selected = settings.baseKeymap,
                            onSelect = { onSet(AppSettings.KEY_BASE_KEYMAP, "\"${it.key}\"") },
                        )
                        // What the keymap could not use, where someone looking
                        // for why a key does nothing will look.
                        for (error in keymapErrors) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.color("error", MaterialTheme.colorScheme.error),
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                            )
                        }
                        if (onEditKeymap != null) {
                            Text(
                                text = stringResource(R.string.settings_edit_keymap_json_rebind_any_key),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(onClick = onEditKeymap)
                                    .padding(horizontal = 20.dp, vertical = 6.dp),
                            )
                        }
                    }

                    if (shown("Markdown preview", "scroll sync", "markdown_preview")) {
                        SectionDivider()
                        SectionHeader(stringResource(R.string.settings_preview))
                        ChoiceRow(
                            label = stringResource(R.string.settings_follow_the_editor),
                            detail = stringResource(R.string.settings_the_markdown_preview_scrolls_with_the),
                            options = listOf(true to "On", false to "Off"),
                            selected = settings.markdownPreview.scrollSync,
                            onSelect = {
                                onSet(AppSettings.KEY_MARKDOWN_SCROLL_SYNC, it.toString())
                            },
                        )
                    }

                    // Zed's `restore_on_startup` and `close_on_file_delete`,
                    // which its settings UI groups under Workspace
                    // (settings_ui/src/page_data.rs).
                    val showRestore = shown(
                        "Restore on startup", "workspace", "restore_on_startup",
                        "session", "reopen", "tabs",
                    )
                    val showCloseDeleted = shown(
                        "Deleted files", "workspace", "close_on_file_delete", "delete",
                    )
                    if (showRestore || showCloseDeleted) {
                        SectionDivider()
                        SectionHeader(
                            stringResource(R.string.settings_workspace),
                            subtitle = stringResource(R.string.settings_terminals_cannot_survive_the_app_closing),
                        )
                    }
                    if (showRestore) ChoiceRow(
                        label = stringResource(R.string.settings_restore_on_startup),
                        detail = stringResource(R.string.settings_zed_s_restore_on_startup),
                        options = RestoreOnStartup.entries.map { it to it.label },
                        selected = settings.restoreOnStartup,
                        onSelect = { onSet(AppSettings.KEY_RESTORE_ON_STARTUP, "\"${it.key}\"") },
                    )
                    if (showCloseDeleted) ChoiceRow(
                        label = stringResource(R.string.settings_deleted_files),
                        detail = stringResource(R.string.settings_a_tab_whose_file_is_deleted),
                        options = listOf(true to "Close the tab", false to "Keep it open"),
                        selected = settings.closeOnFileDelete,
                        onSelect = { onSet(AppSettings.KEY_CLOSE_ON_FILE_DELETE, it.toString()) },
                    )

                    if (shown("Inline blame", "git")) {
                        SectionDivider()
                        SectionHeader(stringResource(R.string.settings_git))
                        ChoiceRow(
                            label = stringResource(R.string.settings_inline_blame),
                            detail = stringResource(R.string.settings_who_last_touched_the_line_the),
                            options = listOf(true to "Show", false to "Hide"),
                            selected = settings.inlineBlame,
                            onSelect = { onSet(AppSettings.KEY_INLINE_BLAME, it.toString()) },
                        )
                    }

                    // A panel this edition cannot show gets no row: the agent
                    // panel needs the Linux userland, and offering to place
                    // something that will never appear is the sort of dead
                    // setting the "absent, not failing" rule exists to prevent.
                    val panels = WorkspacePanel.entries.filter {
                        (it != WorkspacePanel.Agent || isAgentPanelSupported) &&
                            shown(it.title, "panels", "dock", it.settingsKey)
                    }
                    val showGitignored = shown("Gitignored files", "panels", "project tree", "gitignored_files")
                    if (panels.isNotEmpty() || showGitignored) {
                        SectionDivider()
                        SectionHeader(
                            stringResource(R.string.settings_panels),
                            subtitle = stringResource(R.string.settings_which_side_each_panel_docks_on),
                        )
                    }
                    for (panel in panels) {
                        ChoiceRow(
                            label = panel.title,
                            detail = null,
                            options = DockSide.entries.map { it to it.label },
                            selected = settings.panel(panel.settingsKey).dock,
                            onSelect = {
                                onSet(AppSettings.keyForDock(panel.settingsKey), "\"${it.key}\"")
                            },
                        )
                    }
                    if (showGitignored) ChoiceRow(
                        label = stringResource(R.string.settings_gitignored_files),
                        detail = stringResource(R.string.settings_in_the_project_tree),
                        options = listOf(
                            GitignoredFiles.Show to "Show",
                            GitignoredFiles.Dimmed to "Grey out",
                            GitignoredFiles.Hide to "Hide",
                        ),
                        selected = settings.gitignoredFiles,
                        onSelect = { onSet(AppSettings.KEY_GITIGNORED, "\"${it.key}\"") },
                    )

                    SectionDivider()
                    SectionHeader(
                        stringResource(R.string.settings_project_panel),
                        subtitle = stringResource(R.string.settings_zed_s_project_panel_keys_indent),
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_order),
                        detail = stringResource(R.string.settings_zed_s_sort_mode),
                        options = listOf(
                            ProjectPanelSort.DirectoriesFirst to "Folders first",
                            ProjectPanelSort.Mixed to "Mixed",
                            ProjectPanelSort.FilesFirst to "Files first",
                        ),
                        selected = settings.projectPanel.sort,
                        onSelect = { onSet(AppSettings.KEY_PANEL_SORT, "\"${it.key}\"") },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_fold_single_child_folders),
                        detail = "Draw a run of them as one \u201ca/b/c\u201d row",
                        options = listOf(true to "Fold", false to "One row each"),
                        selected = settings.projectPanel.autoFoldDirs,
                        onSelect = { onSet(AppSettings.KEY_PANEL_AUTO_FOLD, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_row_spacing),
                        detail = stringResource(R.string.settings_zed_s_entry_spacing),
                        options = listOf(
                            EntrySpacing.Comfortable to "Comfortable",
                            EntrySpacing.Standard to "Tighter",
                        ),
                        selected = settings.projectPanel.entrySpacing,
                        onSelect = { onSet(AppSettings.KEY_PANEL_SPACING, "\"${it.key}\"") },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_mark_problems_in_the_tree),
                        detail = stringResource(R.string.settings_the_file_and_every_folder_above),
                        options = DIAGNOSTIC_CHOICES,
                        selected = settings.projectPanel.showDiagnostics,
                        onSelect = { onSet(AppSettings.KEY_PANEL_DIAGNOSTICS, "\"${it.key}\"") },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_project_name_row),
                        detail = stringResource(R.string.settings_zed_s_hide_root),
                        options = listOf(false to "Show", true to "Hide"),
                        selected = settings.projectPanel.hideRoot,
                        onSelect = { onSet(AppSettings.KEY_PANEL_HIDE_ROOT, it.toString()) },
                    )

                    SectionDivider()
                    SectionHeader(
                        stringResource(R.string.settings_tabs),
                        subtitle = stringResource(R.string.settings_zed_s_tabs_block_max_tabs),
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_preview_tabs),
                        detail = stringResource(R.string.settings_a_single_click_reuses_one_italic),
                        options = listOf(true to "On", false to "Off"),
                        selected = settings.previewTabs.enabled,
                        onSelect = { onSet(AppSettings.KEY_PREVIEW_TABS, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_close_button),
                        detail = stringResource(R.string.settings_which_end_of_the_tab_it),
                        options = listOf(
                            ClosePosition.Right to "Right",
                            ClosePosition.Left to "Left",
                        ),
                        selected = settings.tabs.closePosition,
                        onSelect = {
                            onSet(AppSettings.KEY_TAB_CLOSE_POSITION, "\"${it.key}\"")
                        },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_file_icons),
                        detail = stringResource(R.string.settings_in_the_tab_beside_the_name),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.tabs.fileIcons,
                        onSelect = { onSet(AppSettings.KEY_TAB_FILE_ICONS, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_git_status_in_tabs),
                        detail = stringResource(R.string.settings_tint_the_title_by_what_git),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.tabs.gitStatus,
                        onSelect = { onSet(AppSettings.KEY_TAB_GIT_STATUS, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_mark_problems_in_tabs),
                        detail = stringResource(R.string.settings_a_dot_beside_the_title),
                        options = DIAGNOSTIC_CHOICES,
                        selected = settings.tabs.showDiagnostics,
                        onSelect = { onSet(AppSettings.KEY_TAB_DIAGNOSTICS, "\"${it.key}\"") },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_after_closing_a_tab),
                        detail = stringResource(R.string.settings_which_tab_takes_over),
                        options = listOf(
                            ActivateOnClose.History to "The one before",
                            ActivateOnClose.Neighbour to "The one to the right",
                            ActivateOnClose.LeftNeighbour to "The one to the left",
                        ),
                        selected = settings.tabs.activateOnClose,
                        onSelect = {
                            onSet(AppSettings.KEY_TAB_ACTIVATE_ON_CLOSE, "\"${it.key}\"")
                        },
                    )

                    SectionDivider()
                    SectionHeader(
                        stringResource(R.string.settings_chrome),
                        subtitle = stringResource(R.string.settings_zed_s_tab_bar_toolbar_and),
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_tab_bar),
                        detail = stringResource(R.string.settings_the_strip_of_open_tabs_ctrl),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.tabBar.show,
                        onSelect = { onSet(AppSettings.KEY_TAB_BAR_SHOW, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_back_and_forward_buttons),
                        detail = stringResource(R.string.settings_zed_s_show_nav_history_buttons),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.tabBar.showNavHistoryButtons,
                        onSelect = { onSet(AppSettings.KEY_TAB_BAR_NAV_BUTTONS, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_tab_bar_buttons),
                        detail = stringResource(R.string.settings_the_switcher_new_file_split_and),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.tabBar.showTabBarButtons,
                        onSelect = { onSet(AppSettings.KEY_TAB_BAR_BUTTONS, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_breadcrumbs),
                        detail = stringResource(R.string.settings_the_file_name_and_the_symbol),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.toolbar.breadcrumbs,
                        onSelect = { onSet(AppSettings.KEY_TOOLBAR_BREADCRUMBS, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_quick_actions),
                        detail = stringResource(R.string.settings_find_in_file_project_symbols_and),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.toolbar.quickActions,
                        onSelect = { onSet(AppSettings.KEY_TOOLBAR_QUICK_ACTIONS, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_selections_menu),
                        detail = stringResource(R.string.settings_zed_s_selection_controls_select_all),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.toolbar.selectionsMenu,
                        onSelect = {
                            onSet(AppSettings.KEY_TOOLBAR_SELECTIONS_MENU, it.toString())
                        },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_language_button),
                        detail = stringResource(R.string.settings_the_language_name_in_the_status),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.statusBar.activeLanguageButton,
                        onSelect = { onSet(AppSettings.KEY_STATUS_BAR_LANGUAGE, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_cursor_position),
                        detail = stringResource(R.string.settings_the_line_column_readout_which_opens),
                        options = listOf(true to "Show", false to "Hide"),
                        selected = settings.statusBar.cursorPositionButton,
                        onSelect = { onSet(AppSettings.KEY_STATUS_BAR_CURSOR, it.toString()) },
                    )
                    ChoiceRow(
                        label = stringResource(R.string.settings_reduce_motion),
                        detail = stringResource(R.string.settings_scroll_animations_the_toast_slide_and),
                        options = ReduceMotion.entries.map { it to it.label },
                        selected = settings.reduceMotion,
                        onSelect = { onSet(AppSettings.KEY_REDUCE_MOTION, "\"${it.key}\"") },
                    )

                    if (onAbout != null || onOpenOnboarding != null) {
                        val showAbout = shown("About", "version", "system specs", "bug report")
                        val showWelcome = shown("Welcome", "onboarding", "first run")
                        if (showAbout || showWelcome) {
                            SectionDivider()
                            SectionHeader(stringResource(R.string.settings_about))
                        }
                        if (showWelcome && onOpenOnboarding != null) {
                            LinkRow(
                                label = stringResource(R.string.settings_show_the_welcome_screen),
                                detail = stringResource(R.string.settings_the_three_things_to_try_and),
                                onClick = onOpenOnboarding,
                            )
                        }
                        if (showAbout && onAbout != null) {
                            LinkRow(
                                label = stringResource(R.string.settings_about_seeker_code),
                                detail = stringResource(R.string.settings_version_engine_the_zed_commit_and),
                                onClick = onAbout,
                            )
                        }
                    }

                    // Zed's External Agents page, as a section: the same
                    // title, the same subtitle, the same list-or-empty-state,
                    // and an Add Agent form with Zed's own fields
                    // (external_agents_page.rs:51-58, 111-125, 497-544).
                    // Absent, not greyed, where there is no userland to run
                    // an agent in.
                    if (onSaveAgent != null && shown("External Agents", "agent", "agent_servers", "acp")) {
                        SectionDivider()
                        SectionHeader(
                            stringResource(R.string.settings_external_agents),
                            subtitle = stringResource(R.string.settings_agents_connected_through_the_agent_client),
                        )
                        val form = agentForm
                        if (form == null) {
                            AgentList(
                                agents = settings.agents,
                                onEdit = { agent ->
                                    agentForm = AgentForm(
                                        originalName = agent.name,
                                        name = agent.name,
                                        command = agent.argv.firstOrNull().orEmpty(),
                                        args = agent.argv.drop(1).joinToString(" "),
                                    )
                                },
                                onRemove = onRemoveAgent,
                                onAdd = { agentForm = AgentForm() },
                            )
                        } else {
                            AgentFormFields(
                                form = form,
                                onForm = { agentForm = it },
                                onCancel = { agentForm = null },
                                onSave = {
                                    val saved = validateAgentForm(form, settings.agents)
                                    if (saved == null) {
                                        onSaveAgent(
                                            form.originalName,
                                            form.name.trim(),
                                            form.command.trim(),
                                            // Zed splits arguments on
                                            // whitespace too
                                            // (external_agents_page.rs:825-829).
                                            form.args.split(Regex("\\s+"))
                                                .filter { it.isNotEmpty() },
                                        )
                                        agentForm = null
                                    } else {
                                        agentForm = form.copy(error = saved)
                                    }
                                },
                            )
                        }
                    }

                    // Zed's `agent.notify_when_agent_waiting`, as the one
                    // question a single-screen device asks of it.
                    if (onSaveAgent != null && shown("Notify when the agent is waiting", "agent", "notification")) {
                        SectionDivider()
                        SectionHeader(stringResource(R.string.settings_agent))
                        ChoiceRow(
                            label = stringResource(R.string.settings_notify_when_the_agent_is_waiting),
                            detail = stringResource(R.string.settings_a_notification_when_a_turn_ends),
                            options = listOf(
                                NotifyWhenAgentWaiting.PrimaryScreen to "On",
                                NotifyWhenAgentWaiting.Never to "Off",
                            ),
                            selected = if (settings.notifyWhenAgentWaiting.isOn) {
                                NotifyWhenAgentWaiting.PrimaryScreen
                            } else {
                                NotifyWhenAgentWaiting.Never
                            },
                            onSelect = { onSet(AppSettings.KEY_NOTIFY_AGENT, "\"${it.key}\"") },
                        )
                    }

                    // Zed's MCP servers page, as a section beside the agents:
                    // the `context_servers` entries, handed to the agent when
                    // a thread starts, with the same list-or-empty-state and
                    // a form in the agent form's dress.
                    if (onSaveContextServer != null && shown("Context Servers", "mcp", "context_servers", "agent")) {
                        SectionDivider()
                        SectionHeader(
                            stringResource(R.string.settings_context_servers),
                            subtitle = stringResource(R.string.settings_mcp_servers_the_agent_is_given),
                        )
                        val form = contextServerForm
                        if (form == null) {
                            ContextServerList(
                                servers = settings.contextServers,
                                onEdit = { server ->
                                    contextServerForm = ContextServerForm(
                                        originalName = server.name,
                                        name = server.name,
                                        command = server.url ?: server.command.orEmpty(),
                                        args = server.args.joinToString(" "),
                                    )
                                },
                                onRemove = onRemoveContextServer,
                                onAdd = { contextServerForm = ContextServerForm() },
                            )
                        } else {
                            ContextServerFormFields(
                                form = form,
                                onForm = { contextServerForm = it },
                                onCancel = { contextServerForm = null },
                                onSave = {
                                    val problem = validateContextServerForm(form, settings.contextServers)
                                    if (problem == null) {
                                        val existing = settings.contextServers
                                            .firstOrNull { it.name == form.originalName }
                                        onSaveContextServer(
                                            form.originalName,
                                            contextServerFromForm(form, existing),
                                        )
                                        contextServerForm = null
                                    } else {
                                        contextServerForm = form.copy(error = problem)
                                    }
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (settingsPath != null) {
                    if (onEditFile != null) {
                        Text(
                            text = stringResource(R.string.settings_open_settings_file_every_key_languages),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(onClick = onEditFile)
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                        if (onOpenProjectSettings != null) {
                            Text(
                                text = stringResource(R.string.settings_open_project_settings_zed_settings_json),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(onClick = onOpenProjectSettings)
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                        if (onOpenDefaultSettings != null) {
                            Text(
                                text = stringResource(R.string.settings_open_default_settings_every_key_documented),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(onClick = onOpenDefaultSettings)
                                    .padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                    } else {
                        Text(
                            text = "Edit directly: $settingsPath",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.settings_close),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Why the form cannot be saved, or null when it can — Zed's own messages and
 * rules (external_agents_page.rs:816-822, 744-759): name and command are
 * required, and a *new* agent may not take an existing name, while editing in
 * place may keep its own.
 */
private fun validateAgentForm(form: AgentForm, agents: List<AgentDefinition>): String? {
    val name = form.name.trim()
    if (name.isEmpty()) return "Agent name is required."
    if (form.command.isBlank()) return "Command is required."
    val collides = agents.any { it.name == name } && name != form.originalName
    if (collides) return "An agent named \"$name\" already exists."
    return null
}

/**
 * Why the context-server form cannot be saved, or null when it can: a name
 * and a command or URL are required, and a new entry may not take an
 * existing name — the agent form's rules, which are Zed's.
 */
private fun validateContextServerForm(
    form: ContextServerForm,
    servers: List<ContextServerDefinition>,
): String? {
    val name = form.name.trim()
    if (name.isEmpty()) return "Server name is required."
    if (form.command.isBlank()) return "A command, or an https:// URL, is required."
    val collides = servers.any { it.name == name } && name != form.originalName
    if (collides) return "A context server named \"$name\" already exists."
    return null
}

/**
 * The form as an entry: a command that is a URL is an HTTP server, anything
 * else a program — Zed's two shapes, told apart the way Zed's own untagged
 * setting tells them apart. [existing] lends its env or headers, which the
 * form does not carry.
 */
private fun contextServerFromForm(
    form: ContextServerForm,
    existing: ContextServerDefinition?,
): ContextServerDefinition {
    val command = form.command.trim()
    val isUrl = command.startsWith("http://") || command.startsWith("https://")
    return if (isUrl) {
        ContextServerDefinition(
            name = form.name.trim(),
            url = command,
            headers = existing?.headers.orEmpty(),
            enabled = existing?.enabled ?: true,
        )
    } else {
        ContextServerDefinition(
            name = form.name.trim(),
            command = command,
            // Split on whitespace, as Zed's agent form does.
            args = form.args.split(Regex("\\s+")).filter { it.isNotEmpty() },
            env = existing?.env.orEmpty(),
            enabled = existing?.enabled ?: true,
        )
    }
}

/** The configured context servers, or an empty state, and the Add button. */
@Composable
private fun ContextServerList(
    servers: List<ContextServerDefinition>,
    onEdit: (ContextServerDefinition) -> Unit,
    onRemove: ((String) -> Unit)?,
    onAdd: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (servers.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_no_context_servers_yet_add_one),
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        theme.color("border").copy(alpha = 0.6f),
                        RoundedCornerShape(FieldRadius),
                    )
                    .padding(12.dp),
            )
        }
        for (server in servers) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name + if (!server.enabled) " (disabled)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = server.summary,
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = BufferFontFamily),
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LinkText("Edit") { onEdit(server) }
                if (onRemove != null) {
                    LinkText("Remove") { onRemove(server.name) }
                }
            }
        }
        LinkText("+ Add Context Server", onClick = onAdd)
    }
}

/** The context-server form: name, command or URL, arguments. */
@Composable
private fun ContextServerFormFields(
    form: ContextServerForm,
    onForm: (ContextServerForm) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            label = stringResource(R.string.settings_server_name),
            detail = stringResource(R.string.settings_a_unique_name_it_is_the),
            value = form.name,
            placeholder = "filesystem",
            onValue = { onForm(form.copy(name = it, error = null)) },
        )
        FormField(
            label = stringResource(R.string.settings_command_or_url),
            detail = stringResource(R.string.settings_a_program_the_agent_runs_in),
            value = form.command,
            placeholder = "npx",
            onValue = { onForm(form.copy(command = it, error = null)) },
        )
        FormField(
            label = stringResource(R.string.settings_arguments),
            detail = stringResource(R.string.settings_space_separated_ignored_for_a_url),
            value = form.args,
            placeholder = "-y @modelcontextprotocol/server-filesystem .",
            onValue = { onForm(form.copy(args = it, error = null)) },
        )
        if (form.error != null) {
            Text(
                text = form.error,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("error", MaterialTheme.colorScheme.error),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            LinkText("Cancel", onClick = onCancel)
            LinkText("Save", onClick = onSave)
        }
    }
}

/** The configured agents, or Zed's empty state, and the Add Agent button. */
@Composable
private fun AgentList(
    agents: List<AgentDefinition>,
    onEdit: (AgentDefinition) -> Unit,
    onRemove: ((String) -> Unit)?,
    onAdd: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (agents.isEmpty()) {
            // Zed's dashed empty-state box, in sentence form
            // (external_agents_page.rs:111-125).
            Text(
                text = stringResource(R.string.settings_no_external_agents_added_yet_add),
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        theme.color("border").copy(alpha = 0.6f),
                        RoundedCornerShape(FieldRadius),
                    )
                    .padding(12.dp),
            )
        }
        for (agent in agents) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = agent.argv.joinToString(" "),
                        style = MaterialTheme.typography.labelSmall
                            .copy(fontFamily = BufferFontFamily),
                        color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Zed's rows carry a configure gear and a trash can
                // (external_agents_page.rs:179-209); words are honest at this
                // size.
                LinkText("Edit") { onEdit(agent) }
                if (onRemove != null) {
                    LinkText("Remove") { onRemove(agent.name) }
                }
            }
        }
        LinkText("+ Add Agent", onClick = onAdd)
    }
}

/** Zed's Add Custom Agent fields: name, command, arguments (:497-544). */
@Composable
private fun AgentFormFields(
    form: AgentForm,
    onForm: (AgentForm) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            label = stringResource(R.string.settings_agent_name),
            detail = stringResource(R.string.settings_a_unique_name_it_is_the_2),
            value = form.name,
            placeholder = "my-agent",
            onValue = { onForm(form.copy(name = it, error = null)) },
        )
        FormField(
            label = stringResource(R.string.settings_command),
            detail = stringResource(R.string.settings_a_program_on_the_userland_s),
            value = form.command,
            placeholder = "/usr/local/bin/agent",
            onValue = { onForm(form.copy(command = it, error = null)) },
        )
        FormField(
            label = stringResource(R.string.settings_arguments),
            detail = stringResource(R.string.settings_space_separated_environment_variables_are_set),
            value = form.args,
            placeholder = "--acp",
            onValue = { onForm(form.copy(args = it, error = null)) },
        )
        if (form.error != null) {
            Text(
                text = form.error,
                style = MaterialTheme.typography.bodySmall,
                color = theme.color("error", MaterialTheme.colorScheme.error),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
        ) {
            LinkText("Cancel", onClick = onCancel)
            LinkText("Save", onClick = onSave)
        }
    }
}

@Composable
private fun FormField(
    label: String,
    detail: String?,
    value: String,
    placeholder: String,
    onValue: (String) -> Unit,
) {
    val theme = LocalZedTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Zed's input box: 32px min height, 6px corners, 1px border, the
        // editor's background (external_agents_page.rs:558-576).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(FieldRadius))
                .background(theme.color("editor.background"))
                .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.color("text.placeholder", theme.color("text.muted")),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
                cursorBrush = SolidColor(theme.color("editor.foreground")),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The filter box over the rows — Zed's settings search field, in this
 * dialog's input-box dress ([FormField]'s box, without the label).
 */
@Composable
private fun FilterField(value: String, onValue: (String) -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(FieldRadius))
            .background(theme.color("editor.background"))
            .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_filter_settings),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.color("text.placeholder", theme.color("text.muted")),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = theme.color("text")),
            cursorBrush = SolidColor(theme.color("editor.foreground")),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A tappable word with a hand cursor — the dialog's button idiom. */
@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

/**
 * A section's header — Zed's section headers over its settings pages
 * (page_data.rs, `concat_sections!`), which is what gives every row a place
 * instead of one flat list where everything has the same priority.
 */
@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = LocalZedTheme.current.color("border").copy(alpha = 0.6f),
    )
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "System"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}

/** One setting as a row of segmented choices — touch-sized and mouse-friendly. */
/**
 * A font row: the name in force, a line of text drawn **in that font**, and
 * the installed families to choose from.
 *
 * The preview is the row. A list of family names tells you nothing about
 * whether a face has a slashed zero or ligatures you can live with, and those
 * are the only two questions anyone asks of a monospace font — so the sample
 * line spells both, and it is drawn by resolving the selected family exactly
 * as the editor will.
 */
@Composable
private fun FontRow(
    label: String,
    detail: String,
    families: List<FontCatalog.Family>,
    /** The name in settings.json, or null for the bundled face. */
    selected: String?,
    /** What "the bundled face" is called in the list. */
    bundledName: String,
    preview: String,
    previewSize: Float,
    isMonospacePreview: Boolean = false,
    onSelect: (String?) -> Unit,
) {
    val theme = LocalZedTheme.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val fallback = if (isMonospacePreview) BundledFonts.buffer else BundledFonts.ui
    val family by produceState(fallback, selected, families) {
        value = withContext(Dispatchers.IO) { FontCatalog.family(context, selected, fallback) }
    }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = preview,
            fontFamily = family,
            fontSize = previewSize.sp,
            color = theme.color("text"),
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .background(theme.color("editor.background"), RoundedCornerShape(FieldRadius))
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        Text(
            text = (selected ?: "$bundledName (bundled)") +
                if (expanded) " — tap a name to use it" else " — tap to change",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (expanded) {
            // Bounded, and scrolled: a device can have a hundred fonts, and
            // this row is inside a dialog that is already a scroll.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                FontChoice(
                    text = "$bundledName (bundled)",
                    isSelected = selected == null,
                    onClick = { onSelect(null); expanded = false },
                )
                for (installed in families) {
                    if (installed.name == bundledName) continue
                    FontChoice(
                        text = "${installed.name}  ·  ${installed.origin.name.lowercase()}",
                        isSelected = installed.name.equals(selected, ignoreCase = true),
                        onClick = { onSelect(installed.name); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun FontChoice(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) theme.color("text.accent") else theme.color("text"),
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** A row whose whole point is opening something else. */
@Composable
private fun LinkRow(label: String, detail: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalZedTheme.current.color("text.accent"),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    detail: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            for ((value, text) in options) {
                Choice(text = text, isSelected = value == selected, onClick = { onSelect(value) })
            }
        }
    }
}

@Composable
private fun Choice(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val theme = LocalZedTheme.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (isSelected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .background(
                if (isSelected) theme.color("element.selected") else theme.color("element.background"),
                RoundedCornerShape(6.dp),
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
