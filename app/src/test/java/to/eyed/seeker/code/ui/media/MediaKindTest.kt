package to.eyed.seeker.code.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which files stop being text.
 *
 * The decision is small but it is the one that keeps a megabyte of PNG out of
 * a CRDT, and the boundary is easy to get wrong in both directions — so the
 * edges are pinned rather than the middle.
 */
class MediaKindTest {

    @Test
    fun picturesSoundAndVideoAreMedia() {
        assertEquals(MediaKind.Image, MediaKind.of("photo.PNG"))
        assertEquals(MediaKind.Image, MediaKind.of("icon.webp"))
        assertEquals(MediaKind.Audio, MediaKind.of("theme.flac"))
        assertEquals(MediaKind.Video, MediaKind.of("demo.mp4"))
    }

    /**
     * An SVG is text first: Zed opens it in the editor and puts the drawing
     * behind the toolbar's eye (`image_store.rs:261` excludes it from the
     * image viewer by name). Opening it as a picture would take the source
     * away from the one person who wants it — whoever is editing the icon.
     */
    @Test
    fun anSvgStaysText() {
        assertNull(MediaKind.of("logo.svg"))
    }

    /**
     * Zed's image list also has `psd`, `jxl`, `qoi` and friends, which
     * `BitmapFactory` cannot decode. Text is a worse answer than a picture but
     * a better one than an empty pane, so those stay in the editor.
     */
    @Test
    fun aFormatAndroidCannotDecodeStaysText() {
        assertNull(MediaKind.of("layers.psd"))
        assertNull(MediaKind.of("scan.tiff"))
    }

    @Test
    fun sourceCodeIsNotMedia() {
        assertNull(MediaKind.of("main.rs"))
        assertNull(MediaKind.of("Makefile"))
        assertNull(MediaKind.of(""))
        assertNull(MediaKind.of(".gitignore"))
    }

    /** `.PNG`, `.Png` and `.png` are one format, whatever the disk says. */
    @Test
    fun theSuffixIsReadWithoutRegardToCase() {
        assertEquals(MediaKind.Image, MediaKind.of("A.JpEg"))
    }
}
