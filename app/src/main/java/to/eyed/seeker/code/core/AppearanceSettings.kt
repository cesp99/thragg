package to.eyed.seeker.code.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The appearance half of the settings model — themes, fonts, icon themes.
 *
 * The engine owns the file and has already parsed, defaulted and clamped
 * every value here (`core/crates/engine/src/appearance.rs`); this is the read
 * model over the JSON it hands back, in the same shapes, so a block pasted
 * out of a Zed settings file means the same thing on both sides.
 *
 * Writing goes the other way through [AppSettings.set], and always **whole
 * values**: `theme` is an object with three keys and setting one of them on
 * its own would leave the other two missing.
 */

/**
 * Zed's `theme` value: a bare theme name, or one name per appearance with a
 * mode that picks between them (`settings_content/src/theme.rs:337-350`).
 *
 * [mode] is null for the bare-name form, which is Zed's `Static`: that theme
 * in every appearance, with the *theme's* own appearance deciding whether the
 * app paints light or dark.
 */
data class ThemeSelection(
    val mode: ThemeMode?,
    val light: String,
    val dark: String,
) {
    /** Whether a bare name — Zed's `Static` — rather than the object form. */
    val isStatic: Boolean get() = mode == null

    /**
     * Whether this means dark right now, or null when only the theme file
     * knows (the bare-name form).
     */
    fun isDark(systemIsDark: Boolean): Boolean? = when (mode) {
        null -> null
        ThemeMode.System -> systemIsDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    /** The theme to paint with. */
    fun themeName(systemIsDark: Boolean): String =
        if (isDark(systemIsDark) == true) dark else light

    /**
     * This selection with [name] filling the slot for its own appearance and
     * the other slot left alone — Zed's
     * `theme_selector.rs:retain_original_opposing_theme`, which is what makes
     * "follow the system" keep working once both halves have been chosen.
     */
    fun with(name: String, nameIsDark: Boolean): ThemeSelection = ThemeSelection(
        mode = mode ?: ThemeMode.System,
        light = if (nameIsDark) light else name,
        dark = if (nameIsDark) name else dark,
    )

    /** This selection with a different mode, both names kept. */
    fun withMode(mode: ThemeMode): ThemeSelection = ThemeSelection(mode, light, dark)

    /** What goes into settings.json — the object form, or the bare name. */
    fun toJson(): String = if (mode == null) {
        JSONObject.quote(light)
    } else {
        JSONObject().apply {
            put("mode", mode.key)
            put("light", light)
            put("dark", dark)
        }.toString()
    }

    companion object {
        /** Zed's defaults (`settings_content/src/theme.rs:354-355`). */
        const val DEFAULT_LIGHT = "One Light"
        const val DEFAULT_DARK = "One Dark"

        val Default = ThemeSelection(ThemeMode.System, DEFAULT_LIGHT, DEFAULT_DARK)

        /**
         * The value under `"theme"`, in either shape.
         *
         * The three bare words `"system"`, `"light"` and `"dark"` are the
         * mode, not a theme name: that is what this app's `theme` key used to
         * be, and the engine reads them the same way — see the note on
         * `appearance::ThemeSelection`.
         */
        fun parse(value: Any?): ThemeSelection = when (value) {
            is String -> when (val mode = ThemeMode.entries.firstOrNull { it.key == value }) {
                null -> ThemeSelection(null, value, value)
                else -> ThemeSelection(mode, DEFAULT_LIGHT, DEFAULT_DARK)
            }
            is JSONObject -> ThemeSelection(
                mode = ThemeMode.fromKey(value.optString("mode", "system")),
                light = value.optString("light").ifEmpty { DEFAULT_LIGHT },
                dark = value.optString("dark").ifEmpty { DEFAULT_DARK },
            )
            else -> Default
        }
    }
}

/**
 * Zed's `icon_theme`, which takes `theme`'s two shapes
 * (`settings_content/src/theme.rs:379-394`).
 */
data class IconThemeSelection(
    val mode: ThemeMode?,
    val light: String,
    val dark: String,
) {
    /** The icon theme to draw with, given the appearance in effect. */
    fun iconThemeName(isDark: Boolean): String = when (mode) {
        null -> light
        ThemeMode.System -> if (isDark) dark else light
        ThemeMode.Light -> light
        ThemeMode.Dark -> dark
    }

    fun with(name: String): IconThemeSelection =
        if (mode == null) IconThemeSelection(null, name, name) else copy(light = name, dark = name)

    fun toJson(): String = if (mode == null) {
        JSONObject.quote(light)
    } else {
        JSONObject().apply {
            put("mode", mode.key)
            put("light", light)
            put("dark", dark)
        }.toString()
    }

    companion object {
        /** Zed's own icon theme, which is the set this app bundles. */
        const val DEFAULT = "Zed (Default)"

        val Default = IconThemeSelection(null, DEFAULT, DEFAULT)

        fun parse(value: Any?): IconThemeSelection = when (value) {
            is String -> IconThemeSelection(null, value, value)
            is JSONObject -> IconThemeSelection(
                mode = ThemeMode.fromKey(value.optString("mode", "system")),
                light = value.optString("light").ifEmpty { DEFAULT },
                dark = value.optString("dark").ifEmpty { DEFAULT },
            )
            else -> Default
        }
    }
}

/**
 * Zed's `buffer_line_height` (`settings_content/src/theme.rs:509-517`): two
 * words and one object, `{"custom": 1.4}`.
 */
sealed class BufferLineHeight {
    /** φ — Zed's default (`theme/src/buffer_line_height.rs:17`). */
    data object Comfortable : BufferLineHeight()

    /** Tighter, at 1.3. */
    data object Standard : BufferLineHeight()

    /** A multiple of the font's own height. */
    data class Custom(val multiple: Float) : BufferLineHeight()

    /** Zed's own numbers. */
    val value: Float
        get() = when (this) {
            Comfortable -> 1.618f
            Standard -> 1.3f
            is Custom -> multiple
        }

    /** The label the settings row prints. */
    val label: String
        get() = when (this) {
            Comfortable -> "Comfortable"
            Standard -> "Standard"
            is Custom -> "%.2f".format(multiple)
        }

    fun toJson(): String = when (this) {
        Comfortable -> "\"comfortable\""
        Standard -> "\"standard\""
        is Custom -> "{\"custom\":$multiple}"
    }

    companion object {
        fun parse(value: Any?): BufferLineHeight = when (value) {
            "standard" -> Standard
            is JSONObject -> value.optDouble("custom", 1.618).toFloat()
                .coerceIn(1f, 3f)
                .let(::Custom)
            else -> Comfortable
        }
    }
}

/**
 * The font keys of Zed's `ThemeSettingsContent`
 * (`settings_content/src/theme.rs:178-206`), as this app reads them.
 *
 * A null family means the face the app bundles — Lilex for the buffer, IBM
 * Plex Sans for the chrome, which are Zed's own `.ZedMono` and `.ZedSans`.
 * Anything else is a name looked up in [to.eyed.seeker.code.ui.theme.FontCatalog].
 */
data class FontSettings(
    val bufferFamily: String? = null,
    val bufferFallbacks: List<String> = emptyList(),
    /** OpenType feature tags, `calt`/`liga` among them. */
    val bufferFeatures: Map<String, Int> = emptyMap(),
    /** A CSS weight, 100..900. */
    val bufferWeight: Float = 400f,
    val bufferLineHeight: BufferLineHeight = BufferLineHeight.Comfortable,
    val uiFamily: String? = null,
    /** Zed's `ui_font_size`, which is also this app's rem. */
    val uiSize: Float = DEFAULT_UI_FONT_SIZE,
) {
    /**
     * Whether ligatures are switched off. Android exposes OpenType features
     * through one `fontFeatureSettings` string, so the two tags that spell
     * "ligatures" are the ones a row can honestly offer; either at zero means
     * off.
     */
    val ligaturesOff: Boolean
        get() = LIGATURE_TAGS.any { bufferFeatures[it] == 0 }

    /**
     * The value for Android's `fontFeatureSettings` — a CSS-ish list, e.g.
     * `"calt" 0, "liga" 0`. Empty when the file asks for nothing, which
     * leaves the font's own defaults in force.
     */
    val featureSettings: String
        get() = bufferFeatures.entries
            .joinToString(", ") { (tag, value) -> "\"$tag\" $value" }

    companion object {
        const val DEFAULT_UI_FONT_SIZE = 16f

        /** The two tags that spell "ligatures" between them. */
        val LIGATURE_TAGS = listOf("calt", "liga")

        fun parse(root: JSONObject): FontSettings = FontSettings(
            bufferFamily = root.optString("buffer_font_family").takeIf { it.isNotBlank() },
            bufferFallbacks = stringList(root.optJSONArray("buffer_font_fallbacks")),
            bufferFeatures = features(root.optJSONObject("buffer_font_features")),
            bufferWeight = root.optDouble("buffer_font_weight", 400.0).toFloat()
                .coerceIn(100f, 900f),
            bufferLineHeight = BufferLineHeight.parse(root.opt("buffer_line_height")),
            uiFamily = root.optString("ui_font_family").takeIf { it.isNotBlank() },
            uiSize = root.optDouble("ui_font_size", DEFAULT_UI_FONT_SIZE.toDouble())
                .toFloat()
                .coerceIn(MIN_UI_FONT_SIZE, MAX_UI_FONT_SIZE),
        )

        /**
         * The engine's clamp, restated: below 10 the tab bar is a strip no
         * finger can hit and above 32 a title bar is half the screen — both
         * ends leave the app unusable from the screen you would fix it on.
         */
        const val MIN_UI_FONT_SIZE = 10f
        const val MAX_UI_FONT_SIZE = 32f

        private fun stringList(array: JSONArray?): List<String> =
            if (array == null) emptyList() else List(array.length()) { array.optString(it) }
                .filter { it.isNotBlank() }

        private fun features(json: JSONObject?): Map<String, Int> {
            if (json == null) return emptyMap()
            return json.keys().asSequence().mapNotNull { tag ->
                when (val value = json.opt(tag)) {
                    is Boolean -> tag to if (value) 1 else 0
                    is Number -> tag to value.toInt()
                    else -> null
                }
            }.toMap()
        }
    }
}
