package to.eyed.seeker.code.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.json.JSONObject

/**
 * A parsed Zed theme. The source of truth is Zed's theme JSON (the family
 * files under assets/themes/, vendored from the Zed repository — see
 * docs/THIRD_PARTY.md); no hardcoded palettes remain in the app.
 *
 * A *family* file holds several themes — One is two, Gruvbox is six — so a
 * theme's identity is its full name ("Gruvbox Dark Hard"), not its file.
 * [ZedThemes] is the index that maps one to the other.
 */
class ZedTheme(
    val name: String,
    /** The family the theme is shipped in: "One", "Ayu", "Gruvbox". */
    val family: String,
    val isDark: Boolean,
    private val colors: Map<String, Color>,
    private val syntax: Map<String, SyntaxStyle>,
    val cursor: Color,
    val selection: Color,
    /**
     * The theme's `players` cursor colours in order — Zed's
     * `PlayerColors::color_for_participant`, which the blame gutter uses to
     * tell one commit's rows from the next (editor/src/element.rs:7019).
     * At least one entry: the accent stands in for a theme with none.
     */
    val players: List<Color> = listOf(cursor),
) {
    data class SyntaxStyle(val color: Color?, val italic: Boolean, val bold: Boolean)

    /**
     * Zed's `color_for_participant(index)`: player 0 is the local user, so
     * participants wrap over the *other* entries (theme/src/styles/players.rs
     * `color_for_participant`, `(participant_index % (len - 1)) + 1`).
     */
    fun playerColor(index: Int): Color {
        if (players.size <= 1) return players.firstOrNull() ?: cursor
        val others = players.size - 1
        return players[(Math.floorMod(index, others)) + 1]
    }

    /** A theme's identity, as the picker lists it before anything is loaded. */
    data class Meta(val name: String, val family: String, val isDark: Boolean)

    /**
     * This theme with `theme_overrides` laid over it — Zed's
     * `experimental.theme_overrides` (settings_content/src/theme.rs:247-248).
     *
     * The overrides are a *partial* style object: the same keys a theme file
     * writes, and only the ones the user wants different. Merging happens key
     * by key over the parsed table rather than over the JSON, so an override
     * of `editor.background` cannot cost the theme the forty keys it did not
     * mention — which is what re-parsing a merged document would do to any
     * theme whose file leaves them out.
     *
     * An empty or unreadable override object returns this theme unchanged: a
     * settings file is hand-edited, and the answer to a typo in it is the
     * theme you had.
     */
    fun withOverrides(overrides: String): ZedTheme {
        if (overrides.isBlank()) return this
        val json = runCatching { JSONObject(overrides) }.getOrNull() ?: return this
        if (json.length() == 0) return this
        val (overrideColors, overrideSyntax) = readStyle(json)
        if (overrideColors.isEmpty() && overrideSyntax.isEmpty()) return this
        val mergedSyntax = syntax.toMutableMap()
        for ((key, style) in overrideSyntax) {
            val base = mergedSyntax[key]
            mergedSyntax[key] = if (base == null) style else SyntaxStyle(
                color = style.color ?: base.color,
                italic = style.italic,
                bold = style.bold,
            )
        }
        return ZedTheme(
            name = name,
            family = family,
            isDark = isDark,
            colors = colors + overrideColors,
            syntax = mergedSyntax,
            cursor = overrideColors[CURSOR_OVERRIDE] ?: cursor,
            selection = overrideColors[UI_SELECTION] ?: selection,
            players = players,
        )
    }

    /**
     * Style-table lookup by Zed style key, e.g. `"editor.background"`.
     *
     * A miss falls back to [DERIVED], then to magenta. The derivations are not
     * invention: a dozen keys Zed's own themes never write — the indent
     * guides, the minimap, `pane_group.border` — are filled by its Rust
     * deserializer from `ThemeColors::dark()`, and a theme JSON that omits
     * them is normal rather than broken. Without this table the first indent
     * guide we draw is magenta.
     */
    fun color(key: String, fallback: Color = Color.Magenta): Color =
        colors[key] ?: DERIVED[key]?.let { colors[it] } ?: fallback

    /** Compose span styles indexed by engine style id. */
    private val spanStyles: List<SpanStyle?> = STYLE_NAMES.map { name ->
        syntax[name]?.let { style ->
            SpanStyle(
                color = style.color ?: Color.Unspecified,
                fontWeight = if (style.bold) FontWeight.Bold else null,
                fontStyle = if (style.italic) FontStyle.Italic else null,
            )
        }
    }

    fun spanStyle(styleId: Int): SpanStyle? = spanStyles.getOrNull(styleId)

    companion object {
        /**
         * Keys Zed's themes leave out, and the key each one borrows from.
         *
         * Zed fills these in Rust from `ThemeColors::dark()`
         * (crates/theme/src/default_colors.rs) rather than in the JSON, so
         * even its own One Dark omits every one of them. The borrowings below
         * follow those defaults' *relationships* — an indent guide is the
         * quiet border, an active one the ordinary border — rather than
         * hardcoding One Dark's hexes, so a user's own theme stays coherent.
         */
        private val DERIVED = mapOf(
            // one.json writes the key with a literal `null`; Zed's Rust side
            // fills it from ThemeColors, where it is the focused-border blue.
            "panel.focused_border" to "border.focused",
            "editor.indent_guide" to "border.variant",
            "editor.indent_guide_active" to "border",
            // Zed's One themes write both; a theme that leaves them out gets
            // the same pair the indent guides fall back to, which is what
            // Zed's `ThemeColors` derives them from as well.
            "editor.wrap_guide" to "border.variant",
            "editor.active_wrap_guide" to "border",
            "panel.indent_guide" to "border.variant",
            "panel.indent_guide_hover" to "border",
            "panel.indent_guide_active" to "border.selected",
            "pane_group.border" to "border",
            "scrollbar.thumb.active_background" to "scrollbar.thumb.hover_background",
            "minimap.thumb.background" to "scrollbar.thumb.background",
            "minimap.thumb.hover_background" to "scrollbar.thumb.hover_background",
            "minimap.thumb.active_background" to "scrollbar.thumb.hover_background",
            "minimap.thumb.border" to "scrollbar.thumb.border",
            "drop_target.border" to "border.selected",
            "editor.document_highlight.bracket_background"
                to "editor.document_highlight.read_background",
            "editor.debugger_active_line.background" to "editor.highlighted_line.background",
            "debugger.accent" to "text.accent",
            // Zed's own themes write both, and every bundled family does — but
            // the five double-fallback idioms that read them
            // (ShellNavBar.kt:259, GitGraphPane.kt:1000/1095, OrchBits.kt:134)
            // exist because a *user's* theme need not, and each one spells the
            // borrowing out again at the call site. One entry each, here, is
            // the same answer written once: a success is a created line, and an
            // informational mark is the accent.
            "success" to "created",
            "info" to "text.accent",
            "terminal.ansi.background" to "terminal.background",
            "panel.overlay_background" to "elevated_surface.background",
            "panel.overlay_hover" to "element.hover",
        )

        /**
         * The one omitted key no other key can stand in for.
         *
         * `element.selection_background` tints selected text inside chrome
         * inputs — the palette's query field, the rename box. Zed's default is
         * the same wash the editor selects with, which lives in `players[0]`
         * rather than in the flat style table, so it is seeded at parse time
         * instead of being borrowed by [DERIVED].
         */
        private const val UI_SELECTION = "element.selection_background"

        /**
         * The one caret colour a `theme_overrides` object can name.
         *
         * A theme's caret lives in `players[0].cursor`, which is an array
         * rather than a style key, and an override object addressing an array
         * element is not a shape Zed has. `editor.foreground` is not it
         * either. So the override reads the flat key Zed's own themes leave
         * for it, and a user who wants a different caret writes that one.
         */
        private const val CURSOR_OVERRIDE = "editor.cursor"

        /**
         * What is wrong with [json] as a theme family file, or null when
         * there is nothing wrong with it.
         *
         * Zed's schema is a family — a `name`, an `author`, and a `themes`
         * array whose entries each have a `name`, an `appearance` and a
         * `style` object (theme/src/schema.rs `ThemeFamilyContent`). A file
         * that fails this is *reported*, in the picker, rather than skipped:
         * a theme that silently does not appear is indistinguishable from one
         * the app never noticed, and the difference is the whole reason
         * someone would look.
         */
        fun problemWith(json: String): String? {
            val family = runCatching { JSONObject(json) }.getOrNull()
                ?: return "not valid JSON"
            if (family.optString("name").isEmpty()) return "no \"name\""
            val themes = family.optJSONArray("themes")
                ?: return "no \"themes\" array"
            if (themes.length() == 0) return "\"themes\" is empty"
            for (index in 0 until themes.length()) {
                val theme = themes.optJSONObject(index)
                    ?: return "themes[$index] is not an object"
                if (theme.optString("name").isEmpty()) return "themes[$index] has no \"name\""
                val style = theme.optJSONObject("style")
                    ?: return "themes[$index] has no \"style\" object"
                if (style.length() == 0) return "themes[$index] has an empty \"style\""
            }
            return null
        }

        /**
         * Mirrors `STYLE_NAMES` in `core/crates/engine/src/highlight.rs` —
         * the engine's highlight style ids index this list. Keep in sync.
         */
        private val STYLE_NAMES = listOf(
            "attribute", "boolean", "comment", "comment.doc", "constant",
            "constructor", "embedded", "emphasis", "emphasis.strong", "enum",
            "function", "keyword", "label", "link_text", "link_uri", "number",
            "operator", "preproc", "property", "punctuation",
            "punctuation.bracket", "punctuation.delimiter",
            "punctuation.list_marker", "punctuation.special", "string",
            "string.escape", "string.regex", "string.special",
            "string.special.symbol", "tag", "text.literal", "title", "type",
            "variable", "variable.special",
        )

        /**
         * The themes a family file contains, without parsing their palettes.
         *
         * The picker lists every installed theme, and listing them must not
         * cost eleven palette parses — so this reads only the identity of
         * each, and [parse] is paid for the one theme actually shown.
         */
        internal fun index(json: String): List<Meta> {
            val family = JSONObject(json)
            val familyName = family.optString("name")
            val themes = family.optJSONArray("themes") ?: return emptyList()
            return (0 until themes.length()).mapNotNull { i ->
                val theme = themes.optJSONObject(i) ?: return@mapNotNull null
                val name = theme.optString("name").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                Meta(
                    name = name,
                    family = familyName,
                    isDark = theme.optString("appearance") != "light",
                )
            }
        }

        /** The theme called [name] from a family file, or null if it isn't in it. */
        internal fun parse(json: String, name: String): ZedTheme? {
            val family = JSONObject(json)
            val themes = family.optJSONArray("themes") ?: return null
            for (i in 0 until themes.length()) {
                val theme = themes.getJSONObject(i)
                if (theme.optString("name") == name) {
                    return parseTheme(theme, family.optString("name"))
                }
            }
            return null
        }

        /**
         * A style object read into the two tables a theme is: flat colours
         * by key, and the syntax entries. Shared by a whole theme file and by
         * a `theme_overrides` fragment, which is the same shape with holes.
         */
        private fun readStyle(
            style: JSONObject,
        ): Pair<MutableMap<String, Color>, MutableMap<String, SyntaxStyle>> {
            val colors = mutableMapOf<String, Color>()
            for (key in style.keys()) {
                val value = style.opt(key)
                if (value is String && value.startsWith("#")) {
                    parseColor(value)?.let { colors[key] = it }
                }
            }
            val syntax = mutableMapOf<String, SyntaxStyle>()
            val syntaxJson = style.optJSONObject("syntax") ?: JSONObject()
            for (key in syntaxJson.keys()) {
                val entry = syntaxJson.optJSONObject(key) ?: continue
                syntax[key] = SyntaxStyle(
                    color = entry.optString("color").takeIf { it.startsWith("#") }
                        ?.let(::parseColor),
                    italic = entry.optString("font_style") == "italic",
                    bold = entry.optInt("font_weight", 400) >= 600,
                )
            }
            return colors to syntax
        }

        private fun parseTheme(theme: JSONObject, family: String): ZedTheme {
            val style = theme.getJSONObject("style")
            val (colors, syntax) = readStyle(style)

            val playersJson = style.optJSONArray("players")
            val player0 = playersJson?.optJSONObject(0)
            val accent = colors["text.accent"] ?: Color.White
            val selection = player0?.optString("selection")?.let(::parseColor)
                ?: accent.copy(alpha = 0.24f)
            colors.getOrPut(UI_SELECTION) { selection }
            val cursor = player0?.optString("cursor")?.let(::parseColor) ?: accent
            val players = if (playersJson == null) {
                emptyList()
            } else {
                List(playersJson.length()) { index ->
                    playersJson.optJSONObject(index)?.optString("cursor")?.let(::parseColor)
                }.filterNotNull()
            }
            return ZedTheme(
                name = theme.getString("name"),
                family = family,
                isDark = theme.optString("appearance") != "light",
                colors = colors,
                syntax = syntax,
                cursor = cursor,
                selection = selection,
                players = players.ifEmpty { listOf(cursor) },
            )
        }

        /** `#rrggbb` or `#rrggbbaa` → [Color]. */
        private fun parseColor(hex: String): Color? {
            val digits = hex.removePrefix("#")
            return when (digits.length) {
                6 -> digits.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
                8 -> digits.toLongOrNull(16)?.let {
                    // Zed is #rrggbbaa; Compose wants aarrggbb.
                    Color(((it and 0xFF) shl 24) or (it shr 8))
                }
                else -> null
            }
        }
    }
}

val LocalZedTheme = staticCompositionLocalOf<ZedTheme> {
    error("ZedTheme not provided — wrap content in SeekerCodeByEyedTheme")
}
