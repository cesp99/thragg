package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which files the toolbar's eye appears for.
 *
 * One button for both previews, as in Zed — so this is the whole of the
 * decision, and getting it wrong shows an eye that opens a panel saying "not
 * this file", which is worse than no eye at all.
 */
class PreviewKindTest {

    @Test
    fun markdownIsPreviewed() {
        assertEquals(PreviewKind.Markdown, PreviewKind.of("README.md"))
        assertEquals(PreviewKind.Markdown, PreviewKind.of("docs/NOTES.MARKDOWN"))
        assertEquals(PreviewKind.Markdown, PreviewKind.of("a/b/c.mkd"))
    }

    @Test
    fun svgIsPreviewed() {
        assertEquals(PreviewKind.Svg, PreviewKind.of("assets/logo.svg"))
        assertEquals(PreviewKind.Svg, PreviewKind.of("LOGO.SVG"))
    }

    /** Zed's tabular_data_preview registers four suffixes; so does this. */
    @Test
    fun delimitedDataIsPreviewed() {
        assertEquals(PreviewKind.Table, PreviewKind.of("data/rows.csv"))
        assertEquals(PreviewKind.Table, PreviewKind.of("ROWS.TSV"))
        assertEquals(PreviewKind.Table, PreviewKind.of("a/b.psv"))
        assertEquals(PreviewKind.Table, PreviewKind.of("a/b.ssv"))
    }

    @Test
    fun everythingElseHasNoPreview() {
        assertNull(PreviewKind.of("src/main.rs"))
        assertNull(PreviewKind.of("photo.png"))
        assertNull(PreviewKind.of("Makefile"))
        assertNull(PreviewKind.of("notes.txt"))
        assertNull(PreviewKind.of(""))
    }

    /**
     * The suffix is the *file's*, not the path's. A directory called `docs.md`
     * would otherwise put an eye on every file inside it.
     */
    @Test
    fun onlyTheFileNameIsRead() {
        assertNull(PreviewKind.of("docs.md/notes.txt"))
        assertEquals(PreviewKind.Markdown, PreviewKind.of("docs.svg/notes.md"))
    }
}
