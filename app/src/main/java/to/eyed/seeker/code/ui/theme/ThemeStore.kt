package to.eyed.seeker.code.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import to.eyed.seeker.code.core.AppSettings
import to.eyed.seeker.code.core.FontSettings
import to.eyed.seeker.code.core.ThemeMode
import to.eyed.seeker.code.core.ThemeSelection

/**
 * The two pieces of appearance state that are **not** settings: the theme
 * being previewed, and how far the buffer font has been zoomed this session.
 *
 * Everything else moved into settings.json when the engine learned Zed's
 * `theme` object and the font keys — the two theme names and `ui_font_size`
 * used to live here in app preferences because `Settings::theme` was a bare
 * mode enum with nowhere to put them. What is left is the state that has no
 * business in a file:
 *
 *  - **The preview.** The theme selector applies the theme under the cursor
 *    to the real window and reverts on dismiss (`theme_selector.rs:227-256`);
 *    writing each step to disk would be eleven writes per walk of the list,
 *    and a crash mid-walk would leave you in a theme you never chose.
 *  - **The buffer font zoom.** Zed's `zed::IncreaseBufferFontSize` and its
 *    siblings carry `{ "persist": false }` in the default keymap
 *    (default-linux.json:30-33): the chords adjust a delta over the setting,
 *    they do not rewrite the setting. Ours keeps that — with one divergence,
 *    below.
 */
object ThemeStore {
    /** Zed's `ui_font_size` default, which is also this app's rem. */
    const val DEFAULT_UI_FONT_SIZE = FontSettings.DEFAULT_UI_FONT_SIZE

    /** Zed's step for its font-size chords (`zed.rs:1191-1200`). */
    const val FONT_SIZE_STEP = 1f

    /**
     * How far the buffer font can be zoomed away from the setting.
     *
     * Wide, because this is also what a pinch drives, and a pinch that stops
     * responding feels broken in a way a chord that stops does not. The
     * resolved size is clamped to the engine's own 6..48 either way.
     */
    private const val MAX_FONT_DELTA = 24f

    private const val PREFS = "theme"
    private const val KEY_BUFFER_FONT_DELTA = "buffer_font_delta"

    /** The pre-`ThemeSelection` keys, read once by [migrateLegacyPreferences] and then never again. */
    private const val KEY_DARK = "dark"
    private const val KEY_LIGHT = "light"
    private const val KEY_UI_FONT_SIZE = "ui_font_size"
    private const val KEY_MIGRATED = "migrated_to_settings"

    private val _preview = MutableStateFlow<String?>(null)

    /** The theme the selector's cursor is on, applied but not saved. */
    val preview: StateFlow<String?> = _preview.asStateFlow()

    private val _bufferFontDelta = MutableStateFlow(0f)

    /** Added to `buffer_font_size` before the editor and terminal draw. */
    val bufferFontDelta: StateFlow<Float> = _bufferFontDelta.asStateFlow()

    /** Read the stored zoom. **Blocking** — call it off the main thread. */
    fun load(context: Context) {
        _bufferFontDelta.value = prefs(context)
            .getFloat(KEY_BUFFER_FONT_DELTA, 0f)
            .coerceIn(-MAX_FONT_DELTA, MAX_FONT_DELTA)
    }

    /**
     * Show [name] without saving it, or clear the preview when null.
     *
     * This is the whole point of the selector: Zed applies the theme under the
     * cursor to the real window rather than to a swatch, because a theme is
     * judged on the code you were already reading. Nothing here touches disk,
     * so dismissing the picker leaves no trace.
     */
    fun preview(name: String?) {
        _preview.update { if (it == name) it else name }
    }

    /**
     * Zoom the buffer font — the `ctrl-=` / `ctrl--` chords and the pinch on
     * the editor surface.
     *
     * **This is remembered across launches, and Zed's is not.** Zed's chords
     * pass `persist: false` because a desktop window keeps its zoom for as
     * long as it is open, which is as long as you are working. Android ends
     * processes for its own reasons in the middle of that same session, and a
     * pinch that undid itself while the phone was in a pocket would read as a
     * bug. It stays out of settings.json all the same: the *setting* is what
     * the settings screen shows, and a pinch must not rewrite it.
     */
    fun adjustBufferFontSize(context: Context, delta: Float) =
        setBufferFontDelta(context, _bufferFontDelta.value + delta)

    /** Zed's `zed::ResetBufferFontSize`: back to what the setting says. */
    fun resetBufferFontSize(context: Context) = setBufferFontDelta(context, 0f)

    private fun setBufferFontDelta(context: Context, delta: Float) {
        val clamped = delta.coerceIn(-MAX_FONT_DELTA, MAX_FONT_DELTA)
        prefs(context).edit().putFloat(KEY_BUFFER_FONT_DELTA, clamped).apply()
        _bufferFontDelta.value = clamped
    }

    /**
     * The appearance settings this app used to keep in preferences, written
     * into settings.json the first time a build that understands them runs.
     *
     * Returns the settings after the migration, or null when there was
     * nothing to migrate. **Blocking** — it writes the settings file.
     *
     * Only ever run once, and only over what the file does not already say: a
     * user who has since hand-edited `theme` into the object form must not
     * have a year-old preference put back on top of it.
     */
    fun migrateLegacyPreferences(context: Context, settings: AppSettings): AppSettings? {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return null
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()

        var updated: AppSettings? = null
        val dark = prefs.getString(KEY_DARK, null)
        val light = prefs.getString(KEY_LIGHT, null)
        // The old `theme` key held only a mode, so a file still holding one is
        // exactly the file whose names are in preferences.
        if ((dark != null || light != null) && settings.themeSelection.light ==
            ThemeSelection.DEFAULT_LIGHT &&
            settings.themeSelection.dark == ThemeSelection.DEFAULT_DARK
        ) {
            val selection = settings.themeSelection.copy(
                mode = settings.themeSelection.mode ?: ThemeMode.System,
                light = light ?: settings.themeSelection.light,
                dark = dark ?: settings.themeSelection.dark,
            )
            updated = AppSettings.set(AppSettings.KEY_THEME, selection.toJson()) ?: updated
        }
        val uiFontSize = prefs.getFloat(KEY_UI_FONT_SIZE, 0f)
        if (uiFontSize > 0f && uiFontSize != DEFAULT_UI_FONT_SIZE) {
            updated = AppSettings.set(
                AppSettings.KEY_UI_FONT_SIZE,
                uiFontSize.coerceIn(FontSettings.MIN_UI_FONT_SIZE, FontSettings.MAX_UI_FONT_SIZE)
                    .toInt()
                    .toString(),
            ) ?: updated
        }
        prefs.edit().remove(KEY_DARK).remove(KEY_LIGHT).remove(KEY_UI_FONT_SIZE).apply()
        return updated
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Whether this mode means dark right now. */
fun ThemeMode.isDark(systemIsDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemIsDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
