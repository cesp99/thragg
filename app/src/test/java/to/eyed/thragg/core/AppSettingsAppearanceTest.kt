package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin side of the appearance keys, against the JSON the engine hands
 * back — the same shapes `engine::appearance` parses, read here so the two
 * cannot drift apart silently.
 */
class AppSettingsAppearanceTest {

    @Test
    fun the_theme_object_resolves_a_name_per_appearance() {
        val settings = AppSettings.parse(
            """
            {
              "theme": { "mode": "system", "light": "Ayu Light", "dark": "Ayu Dark" }
            }
            """.trimIndent()
        )
        assertEquals(ThemeMode.System, settings.themeSelection.mode)
        assertEquals("Ayu Dark", settings.themeSelection.themeName(systemIsDark = true))
        assertEquals("Ayu Light", settings.themeSelection.themeName(systemIsDark = false))
    }

    @Test
    fun a_bare_name_is_that_theme_whatever_the_device_says() {
        val settings = AppSettings.parse("""{ "theme": "Gruvbox Dark Hard" }""")
        assertNull(settings.themeSelection.mode)
        assertNull(settings.themeSelection.isDark(systemIsDark = true))
        assertEquals("Gruvbox Dark Hard", settings.themeSelection.themeName(true))
        assertEquals("Gruvbox Dark Hard", settings.themeSelection.themeName(false))
    }

    @Test
    fun the_legacy_mode_words_still_mean_a_mode() {
        // Files written before `theme` could hold names are still on devices.
        val settings = AppSettings.parse("""{ "theme": "dark" }""")
        assertEquals(ThemeMode.Dark, settings.themeSelection.mode)
        assertEquals(ThemeSelection.DEFAULT_DARK, settings.themeSelection.dark)
    }

    @Test
    fun choosing_a_theme_leaves_the_opposing_slot_alone() {
        val selection = ThemeSelection(ThemeMode.System, "Ayu Light", "Ayu Dark")
        val after = selection.with("One Light", nameIsDark = false)
        assertEquals("One Light", after.light)
        assertEquals("Ayu Dark", after.dark)
        assertEquals(ThemeMode.System, after.mode)
        // And a bare name becomes an object with itself in both slots, so
        // nothing on screen changes as the mode arrives.
        val fromStatic = ThemeSelection(null, "One Dark", "One Dark").withMode(ThemeMode.Dark)
        assertEquals("One Dark", fromStatic.light)
        assertEquals("One Dark", fromStatic.dark)
    }

    @Test
    fun the_theme_value_round_trips_through_the_json_it_writes() {
        val selection = ThemeSelection(ThemeMode.Light, "One Light", "One Dark")
        val written = AppSettings.parse("""{ "theme": ${selection.toJson()} }""")
        assertEquals(selection, written.themeSelection)

        val bare = ThemeSelection(null, "Ayu Dark", "Ayu Dark")
        assertEquals(bare, AppSettings.parse("""{ "theme": ${bare.toJson()} }""").themeSelection)
    }

    @Test
    fun the_font_keys_are_read_and_clamped() {
        val settings = AppSettings.parse(
            """
            {
              "buffer_font_family": "JetBrains Mono",
              "buffer_font_fallbacks": ["Noto Sans Mono", "  "],
              "buffer_font_features": { "calt": false, "liga": false, "ss01": 2 },
              "buffer_font_weight": 5000,
              "buffer_line_height": { "custom": 1.45 },
              "ui_font_family": "Inter",
              "ui_font_size": 200
            }
            """.trimIndent()
        )
        val fonts = settings.fonts
        assertEquals("JetBrains Mono", fonts.bufferFamily)
        assertEquals(listOf("Noto Sans Mono"), fonts.bufferFallbacks)
        assertEquals(900f, fonts.bufferWeight, 0f)
        assertEquals(1.45f, fonts.bufferLineHeight.value, 0.0001f)
        assertEquals("Inter", fonts.uiFamily)
        assertEquals(FontSettings.MAX_UI_FONT_SIZE, fonts.uiSize, 0f)
        assertTrue(fonts.ligaturesOff)
        // Android takes one string; every tag the file named is in it.
        assertTrue(fonts.featureSettings.contains("\"calt\" 0"))
        assertTrue(fonts.featureSettings.contains("\"ss01\" 2"))
    }

    @Test
    fun a_file_that_says_nothing_gets_zeds_defaults() {
        val fonts = AppSettings.parse("{}").fonts
        assertNull(fonts.bufferFamily)
        assertNull(fonts.uiFamily)
        assertEquals(400f, fonts.bufferWeight, 0f)
        assertEquals(1.618f, fonts.bufferLineHeight.value, 0.0001f)
        assertEquals(FontSettings.DEFAULT_UI_FONT_SIZE, fonts.uiSize, 0f)
        assertEquals("", fonts.featureSettings)
        assertEquals(IconThemeSelection.DEFAULT, AppSettings.parse("{}").iconTheme.light)
    }

    @Test
    fun the_icon_theme_takes_both_shapes() {
        assertEquals(
            "Pastel",
            AppSettings.parse("""{ "icon_theme": "Pastel" }""").iconTheme.iconThemeName(true),
        )
        val dynamic = AppSettings.parse(
            """{ "icon_theme": { "mode": "system", "light": "Day", "dark": "Night" } }"""
        ).iconTheme
        assertEquals("Night", dynamic.iconThemeName(isDark = true))
        assertEquals("Day", dynamic.iconThemeName(isDark = false))
    }

    @Test
    fun theme_overrides_travel_as_the_text_they_arrived_as() {
        val settings = AppSettings.parse(
            """{ "theme_overrides": { "editor.background": "#101014" } }"""
        )
        assertTrue(settings.themeOverrides.contains("editor.background"))
        // An empty object is nothing to lay over anything, and says so.
        assertEquals("", AppSettings.parse("""{ "theme_overrides": {} }""").themeOverrides)
        assertEquals("", AppSettings.parse("{}").themeOverrides)
    }
}
