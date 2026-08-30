package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which `src` the preview will draw, and from where.
 *
 * The two things that must not happen are a network request the reader did not
 * ask for, and a read outside the project they opened — a README is a document
 * from somebody else, and both of those would be the document deciding.
 */
class MarkdownImageTest {

    @Test
    fun `a relative source resolves against the document's directory`() {
        assertEquals(
            "/p/docs/img/a.png",
            resolveImagePath("/p", "docs/GUIDE.md", "img/a.png"),
        )
        assertEquals(
            "/p/assets/a.png",
            resolveImagePath("/p", "docs/GUIDE.md", "../assets/a.png"),
        )
        assertEquals("/p/a.png", resolveImagePath("/p", "README.md", "./a.png"))
    }

    /** A walk out of the project is clamped, not followed. */
    @Test
    fun `a source cannot climb out of the project`() {
        assertEquals(
            "/p/etc/passwd",
            resolveImagePath("/p", "README.md", "../../../etc/passwd"),
        )
    }

    @Test
    fun `anything with a scheme is refused`() {
        assertNull(resolveImagePath("/p", "README.md", "https://img.example/a.png"))
        assertNull(resolveImagePath("/p", "README.md", "http://img.example/a.png"))
        assertNull(resolveImagePath("/p", "README.md", "data:image/png;base64,AAAA"))
        assertNull(resolveImagePath("/p", "README.md", "file:///etc/passwd"))
        assertNull(resolveImagePath("/p", "README.md", "//img.example/a.png"))
    }

    @Test
    fun `nothing resolves without a project or a source`() {
        assertNull(resolveImagePath(null, "README.md", "a.png"))
        assertNull(resolveImagePath("", "README.md", "a.png"))
        assertNull(resolveImagePath("/p", "README.md", null))
        assertNull(resolveImagePath("/p", "README.md", "   "))
        // A directory is not a picture.
        assertNull(resolveImagePath("/p", "README.md", "assets/"))
    }

    @Test
    fun `only suffixes something here can decode are drawn`() {
        assertTrue(isDrawableImageSource("a.png"))
        assertTrue(isDrawableImageSource("docs/SHOT.JPG"))
        assertTrue(isDrawableImageSource("logo.svg"))
        assertTrue(isDrawableImageSource("a.webp?v=2"))
        assertFalse(isDrawableImageSource("a.psd"))
        assertFalse(isDrawableImageSource("notes.md"))
        assertFalse(isDrawableImageSource(null))
        assertFalse(isDrawableImageSource(""))
    }
}
