package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lookup, not the drawing: which icon a name resolves to, following Zed's
 * own order. The tables themselves are generated from Zed's `icon_theme.rs`
 * by `tools/import-zed-icons.py`, so what is worth testing here is the rule
 * that reads them — "split on the last dot" would get four of these wrong.
 */
class FileIconsTest {

    @Test
    fun anOrdinaryExtensionResolves() {
        assertEquals("ic_file_rust", FileIcons.resourceFor("main.rs"))
        assertEquals("ic_file_python", FileIcons.resourceFor("app.py"))
        assertEquals("ic_file_toml", FileIcons.resourceFor("Cargo.toml"))
    }

    /** A name with no extension at all, matched whole. */
    @Test
    fun aStemWithNoExtensionResolves() {
        assertEquals("ic_file_docker", FileIcons.resourceFor("Dockerfile"))
    }

    /**
     * A dotfile is its *whole* name, not an empty stem with an extension —
     * `.gitignore` is git's, not "the gitignore language".
     */
    @Test
    fun aDotfileResolvesByItsWholeName() {
        assertEquals("ic_file_git", FileIcons.resourceFor(".gitignore"))
    }

    /**
     * The longest suffix wins before the shortest: this is why the lookup
     * walks dots from the left instead of taking `substringAfterLast('.')`.
     */
    @Test
    fun aCompoundSuffixBeatsItsTail() {
        assertEquals("ic_file_eslint", FileIcons.resourceFor("eslint.config.js"))
        // …and the tail still answers when nothing longer matches.
        assertEquals("ic_file_javascript", FileIcons.resourceFor("auth.module.js"))
    }

    @Test
    fun imagesAreNotSourceCode() {
        assertEquals("ic_file_image", FileIcons.resourceFor("photo.png"))
    }

    /**
     * Zed ships an archive icon and maps nothing to it — `.zip` falls back to
     * the plain sheet there too. Pinned because the obvious "fix" is to invent
     * a mapping Zed does not have, and then our tree stops matching its.
     */
    @Test
    fun anArchiveGetsWhatZedGivesIt() {
        assertEquals("ic_file_file", FileIcons.resourceFor("bundle.zip"))
    }

    /** Anything unrecognised gets the plain sheet, as it does in Zed. */
    @Test
    fun anUnknownExtensionFallsBackToTheFileSheet() {
        assertEquals("ic_file_file", FileIcons.resourceFor("notes.qqq"))
        assertEquals("ic_file_file", FileIcons.resourceFor("noextension"))
        assertEquals("ic_file_file", FileIcons.resourceFor(""))
    }

    /**
     * Every icon key the tables name has a drawable — except the ones Zed's
     * own data leaves undefined, which resolve to the file sheet rather than
     * to nothing. `backup` is the only one today: `.bak` names an icon key
     * that Zed's `FILE_ICONS` never defines.
     */
    @Test
    fun everyMappedIconHasADrawableOrFallsBackCleanly() {
        val undefined = ZED_ICON_BY_SUFFIX.values.toSet()
            .plus(ZED_ICON_BY_STEM.values)
            .filterNot { ZED_ICON_DRAWABLE.containsKey(it) }
            .sorted()
        assertEquals(listOf("backup"), undefined)
        assertEquals("ic_file_file", FileIcons.resourceFor("notes.bak"))
    }
}
