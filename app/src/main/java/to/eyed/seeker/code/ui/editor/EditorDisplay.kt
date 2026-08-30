package to.eyed.seeker.code.ui.editor

import org.json.JSONObject
import to.eyed.seeker.code.ui.editor.vim.VimCursorShape

/**
 * The display settings Zed keeps outside the language layer — how the gutter
 * counts, how the caret looks, what the scrollbar and the minimap show, and
 * whether a diagnostic writes itself at the end of its line.
 *
 * They are parsed here rather than in `AppSettings` for the reason
 * `LanguageSettings` is a file of its own: the pane is the only reader, and
 * everything in here is a Zed key with a Zed default beside it.
 */

/**
 * Zed's `show_whitespaces` (assets/settings/default.json:520-530): which
 * space and tab characters get a visible glyph.
 */
enum class ShowWhitespaces(val key: String) {
    Off("off"),
    All("all"),
    /** Zed's default: only inside a selection. */
    Selection("selection"),
    /**
     * Tabs, whitespace touching either edge of the line, and whitespace next
     * to more whitespace — Zed's own three conditions
     * (default.json:526-529).
     */
    Boundary("boundary"),
    /** Only the run of whitespace after the last visible character. */
    Trailing("trailing");

    companion object {
        fun fromKey(key: String?): ShowWhitespaces =
            entries.firstOrNull { it.key == key } ?: Selection
    }
}

/** Zed's `relative_line_numbers` (settings_content/src/editor.rs:304-308). */
enum class RelativeLineNumbers(val key: String) {
    Disabled("disabled"),
    Enabled("enabled"),
    /** Relative, counting wrapped display rows rather than buffer rows. */
    Wrapped("wrapped");

    val isRelative: Boolean get() = this != Disabled

    companion object {
        fun fromKey(key: String?): RelativeLineNumbers =
            entries.firstOrNull { it.key == key } ?: Disabled
    }
}

/** Zed's `current_line_highlight` (default.json:308-316). */
enum class CurrentLineHighlight(val key: String) {
    None("none"),
    Gutter("gutter"),
    Line("line"),
    /** Zed's default: gutter and text both. */
    All("all");

    val washesGutter: Boolean get() = this == Gutter || this == All
    val washesText: Boolean get() = this == Line || this == All

    companion object {
        fun fromKey(key: String?): CurrentLineHighlight =
            entries.firstOrNull { it.key == key } ?: All
    }
}

/**
 * Zed's `cursor_shape` (default.json:259-270). The vim layer has shapes of
 * its own ([VimCursorShape]); this is what the caret looks like when vim is
 * not driving it, and [toVim] is how the two meet.
 */
enum class EditorCursorShape(val key: String) {
    Bar("bar"),
    Block("block"),
    Underline("underline"),
    /** A box drawn around the following character. */
    Hollow("hollow");

    /**
     * The pane draws carets through vim's shapes, which have the same four
     * cases; `hollow` is drawn as a block outline by the pane itself.
     */
    fun toVim(): VimCursorShape = when (this) {
        Bar -> VimCursorShape.Bar
        Block, Hollow -> VimCursorShape.Block
        Underline -> VimCursorShape.Underline
    }

    companion object {
        fun fromKey(key: String?): EditorCursorShape =
            entries.firstOrNull { it.key == key } ?: Bar
    }
}

/** Zed's `scrollbar.show` (default.json:600-613). */
enum class ShowScrollbar(val key: String) {
    /** Zed's default, and this pane's behaviour since it had a scrollbar. */
    Auto("auto"),
    System("system"),
    Always("always"),
    Never("never");

    companion object {
        fun fromKey(key: String?): ShowScrollbar = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * Zed's `scrollbar.diagnostics`, which is a severity floor rather than a
 * flag: `"warning"` marks errors and warnings and nothing quieter. `false`
 * and `true` are read as `none` and `all`, as Zed reads them.
 */
enum class ScrollbarDiagnostics(val key: String) {
    None("none"),
    Error("error"),
    Warning("warning"),
    Information("information"),
    All("all");

    /** Whether a diagnostic of [severity] earns a mark under this floor. */
    fun marks(severity: DiagnosticSeverity): Boolean = when (this) {
        None -> false
        Error -> severity == DiagnosticSeverity.Error
        Warning -> severity <= DiagnosticSeverity.Warning
        Information -> severity <= DiagnosticSeverity.Info
        All -> true
    }

    companion object {
        fun fromKey(value: Any?, fallback: ScrollbarDiagnostics = All): ScrollbarDiagnostics =
            when (value) {
                is Boolean -> if (value) All else None
                is String -> entries.firstOrNull { it.key == value } ?: fallback
                else -> fallback
            }
    }
}

/** Zed's `scrollbar` block, narrowed to the marks this pane can draw. */
data class ScrollbarSettings(
    val show: ShowScrollbar = ShowScrollbar.Auto,
    val cursors: Boolean = true,
    val gitDiff: Boolean = true,
    val searchResults: Boolean = true,
    val selectedSymbol: Boolean = true,
    val diagnostics: ScrollbarDiagnostics = ScrollbarDiagnostics.All,
) {
    /** Whether the track is drawn at all when there is something to scroll. */
    val isShown: Boolean get() = show != ShowScrollbar.Never

    companion object {
        fun parse(json: JSONObject?): ScrollbarSettings {
            val fallback = ScrollbarSettings()
            if (json == null) return fallback
            return ScrollbarSettings(
                show = ShowScrollbar.fromKey(json.optString("show", null)),
                cursors = json.optBoolean("cursors", fallback.cursors),
                gitDiff = json.optBoolean("git_diff", fallback.gitDiff),
                searchResults = json.optBoolean("search_results", fallback.searchResults),
                selectedSymbol = json.optBoolean("selected_symbol", fallback.selectedSymbol),
                diagnostics = ScrollbarDiagnostics.fromKey(json.opt("diagnostics")),
            )
        }
    }
}

/** Zed's `minimap.show` (default.json:640-649); the default is `never`. */
enum class ShowMinimap(val key: String) {
    /** With the scrollbar — which on this pane means "when there is scroll". */
    Auto("auto"),
    Always("always"),
    Never("never");

    companion object {
        fun fromKey(key: String?): ShowMinimap = entries.firstOrNull { it.key == key } ?: Never
    }
}

/**
 * Zed's `minimap` block. `display_in` and `thumb_border` are not here: this
 * pane has one editor per pane and draws the thumb as a wash, so neither key
 * has anything to change.
 */
data class MinimapSettings(
    val show: ShowMinimap = ShowMinimap.Never,
    /** `hover` means "while it is being dragged" on a touch screen. */
    val thumbAlways: Boolean = true,
    /** How wide the map may get, in buffer-font columns. */
    val maxWidthColumns: Int = 80,
) {
    companion object {
        fun parse(json: JSONObject?): MinimapSettings {
            val fallback = MinimapSettings()
            if (json == null) return fallback
            return MinimapSettings(
                show = ShowMinimap.fromKey(json.optString("show", null)),
                thumbAlways = json.optString("thumb", "always") != "hover",
                maxWidthColumns = json.optInt("max_width_columns", fallback.maxWidthColumns)
                    .coerceIn(8, 200),
            )
        }
    }
}

/**
 * Zed's `diagnostics.inline` (default.json:1656-1672) — the error-lens
 * message drawn at the end of the row it belongs to.
 */
data class InlineDiagnosticsSettings(
    val enabled: Boolean = false,
    /** The quietest severity worth drawing; null in Zed means "all". */
    val maxSeverity: ScrollbarDiagnostics = ScrollbarDiagnostics.All,
    /** Em widths between the end of the line and the message. */
    val padding: Int = 4,
    /** The column the messages line up at, when the lines are shorter. */
    val minColumn: Int = 0,
) {
    companion object {
        fun parse(json: JSONObject?): InlineDiagnosticsSettings {
            val inline = json?.optJSONObject("inline") ?: return InlineDiagnosticsSettings()
            val fallback = InlineDiagnosticsSettings()
            return InlineDiagnosticsSettings(
                enabled = inline.optBoolean("enabled", fallback.enabled),
                maxSeverity = ScrollbarDiagnostics.fromKey(inline.opt("max_severity")),
                padding = inline.optInt("padding", fallback.padding).coerceIn(0, 32),
                minColumn = inline.optInt("min_column", fallback.minColumn).coerceIn(0, 400),
            )
        }
    }
}

/**
 * Which columns of [line] get a whitespace glyph under [mode], as a set of
 * UTF-16 column indices.
 *
 * [selection] is the columns the selection covers on this row, for
 * `"selection"`; an empty range means nothing on this row is selected. Pure,
 * so the four modes can be pinned by a test rather than by looking at a
 * screenshot.
 */
fun whitespaceColumns(
    line: String,
    mode: ShowWhitespaces,
    selection: IntRange = IntRange.EMPTY,
): Set<Int> {
    if (mode == ShowWhitespaces.Off || line.isEmpty()) return emptySet()
    fun isSpace(index: Int) = index in line.indices && (line[index] == ' ' || line[index] == '\t')
    val marked = mutableSetOf<Int>()
    val trailingFrom = line.indexOfLast { it != ' ' && it != '\t' } + 1
    for (index in line.indices) {
        if (!isSpace(index)) continue
        val show = when (mode) {
            ShowWhitespaces.Off -> false
            ShowWhitespaces.All -> true
            ShowWhitespaces.Selection -> index in selection
            ShowWhitespaces.Trailing -> index >= trailingFrom
            // Zed's three conditions, in its own order: a tab always, an
            // edge always, and whitespace with whitespace beside it.
            ShowWhitespaces.Boundary ->
                line[index] == '\t' ||
                    index == 0 ||
                    index == line.length - 1 ||
                    isSpace(index - 1) ||
                    isSpace(index + 1)
        }
        if (show) marked.add(index)
    }
    return marked
}

/**
 * The number the gutter draws for [row] — Zed's relative line numbers
 * (`relative_line_numbers`): the distance from [cursorRow], except on the
 * cursor's own row, which keeps its absolute number so the file position is
 * never lost. 1-based, as the gutter is.
 */
fun gutterLineNumber(row: Int, cursorRow: Int, relative: Boolean): Int =
    if (!relative || row == cursorRow) row + 1 else kotlin.math.abs(row - cursorRow)
