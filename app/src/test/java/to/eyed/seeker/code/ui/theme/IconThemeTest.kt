package to.eyed.seeker.code.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The icon-theme lookup: Zed's rule
 * (`file_icons/src/file_icons.rs`), and what a *user* icon theme does to it.
 *
 * The bundled tables are generated from Zed's `icon_theme.rs`, so what is
 * worth pinning here is not which icon Rust gets — that is `FileIconsTest` —
 * but the arbitration: whole name before suffix, longest suffix before
 * shortest, and a user theme answering only for the keys it names while
 * everything else falls through to the set in the APK.
 */
class IconThemeTest {

    /** A theme that renames two types and ships art for one of them. */
    private val custom = IconTheme(
        name = "Pastel",
        isBundled = false,
        fileStems = mapOf("Makefile" to "build"),
        fileSuffixes = mapOf("rs" to "ferris", "module.js" to "module"),
        icons = mapOf("ferris" to "/data/icon_themes/pastel/ferris.png"),
        collapsedDirectory = "/data/icon_themes/pastel/folder.png",
        expandedDirectory = "/data/icon_themes/pastel/folder-open.png",
        defaultFile = IconTheme.DEFAULT_FILE,
    )

    @Test
    fun the_bundled_theme_follows_zeds_lookup() {
        val bundled = IconThemes.bundled
        // The whole name first: a dotfile has no stem before its dot, and
        // `eslint.config.js` is eslint's rather than JavaScript's.
        assertEquals("vcs", bundled.iconKey(".gitignore"))
        assertEquals("eslint", bundled.iconKey("eslint.config.js"))
        assertEquals("docker", bundled.iconKey("Dockerfile"))
        // Then progressively shorter suffixes.
        assertEquals("javascript", bundled.iconKey("auth.module.js"))
        assertEquals("rust", bundled.iconKey("main.rs"))
        // A name no table answers for has no key at all — the caller decides
        // what a file with no icon looks like.
        assertNull(bundled.iconKey("notes.qqq"))
        assertEquals(IconTheme.DEFAULT_FILE, bundled.iconFor("notes.qqq"))
    }

    @Test
    fun a_user_theme_answers_for_what_it_names() {
        assertEquals("ferris", custom.iconKey("main.rs"))
        assertEquals("build", custom.iconKey("Makefile"))
        assertEquals(
            "/data/icon_themes/pastel/ferris.png",
            custom.iconFor("main.rs", fallback = IconThemes.bundled),
        )
    }

    @Test
    fun what_a_user_theme_does_not_name_falls_through_to_the_bundled_set() {
        // Python is in neither of the custom theme's tables, so both the key
        // and the art come from the set in the APK — which is what makes
        // overriding six file types cost only six.
        assertNull(custom.iconKey("app.py"))
        assertEquals("ic_file_python", custom.iconFor("app.py", fallback = IconThemes.bundled))
    }

    @Test
    fun a_key_a_user_theme_renames_but_ships_no_art_for_borrows_it() {
        // `module.js` maps to a key the theme has no image for, and the
        // bundled set has never heard of `module` either: the fallback's own
        // default sheet is the answer, not a blank row.
        assertEquals("module", custom.iconKey("auth.module.js"))
        assertEquals(
            IconTheme.DEFAULT_FILE,
            custom.iconFor("auth.module.js", fallback = IconThemes.bundled),
        )
    }

    @Test
    fun directories_open_and_close() {
        assertEquals("ic_file_folder", IconThemes.bundled.directoryIcon(isExpanded = false))
        assertEquals("ic_file_folder_open", IconThemes.bundled.directoryIcon(isExpanded = true))
        assertEquals("/data/icon_themes/pastel/folder.png", custom.directoryIcon(false))
    }
}
