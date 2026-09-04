package to.eyed.thragg.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The numbers under a media tab, pinned at their edges: the unit boundaries
 * Zed's `format_file_size` tests pin (util/src/size.rs), the hour that
 * appears only once there is one, and a seek that never leaves the file.
 */
class MediaInfoTest {

    @Test
    fun fileSizeIsZedsBinaryFormat() {
        assertEquals("0B", MediaInfo.fileSize(0))
        assertEquals("1023B", MediaInfo.fileSize(1023))
        assertEquals("1.0KiB", MediaInfo.fileSize(1024))
        assertEquals("1.5KiB", MediaInfo.fileSize(1536))
        assertEquals("1.0MiB", MediaInfo.fileSize(1024 * 1024))
        assertEquals("2.5MiB", MediaInfo.fileSize(2_621_440))
    }

    @Test
    fun fileSizeHasZedsDecimalFormatToo() {
        assertEquals("999B", MediaInfo.fileSize(999, decimal = true))
        assertEquals("1.0KB", MediaInfo.fileSize(1000, decimal = true))
        assertEquals("1.5MB", MediaInfo.fileSize(1_500_000, decimal = true))
    }

    @Test
    fun clockShowsHoursOnlyWhenThereAreSome() {
        assertEquals("0:00", MediaInfo.clock(0))
        assertEquals("0:07", MediaInfo.clock(7_999))
        assertEquals("3:45", MediaInfo.clock(225_000))
        assertEquals("59:59", MediaInfo.clock(3_599_999))
        assertEquals("1:00:00", MediaInfo.clock(3_600_000))
        assertEquals("1:02:03", MediaInfo.clock(3_723_000))
    }

    /** ExoPlayer's `C.TIME_UNSET` is a large negative; a clock never reads minus. */
    @Test
    fun unknownTimeReadsAsZero() {
        assertEquals("0:00", MediaInfo.clock(Long.MIN_VALUE + 1))
        assertEquals("0:00", MediaInfo.clock(-1))
    }

    @Test
    fun summaryJoinsWithZedsBulletAndSkipsUnknowns() {
        assertEquals(
            "1920x1080 • 1.2MiB • PNG",
            MediaInfo.summary(MediaInfo.dimensions(1920, 1080), "1.2MiB", "PNG"),
        )
        assertEquals("0:12 • 3.4MiB", MediaInfo.summary(null, "0:12", "3.4MiB"))
        assertEquals("", MediaInfo.summary(null, null))
    }

    @Test
    fun imageFormatComesFromTheSuffix() {
        assertEquals("PNG", MediaInfo.imageFormat("shot.PNG"))
        assertEquals("JPEG", MediaInfo.imageFormat("photo.jpeg"))
        assertEquals("JPEG", MediaInfo.imageFormat("photo.jpg"))
        assertEquals("WebP", MediaInfo.imageFormat("icon.webp"))
        assertNull(MediaInfo.imageFormat("Makefile"))
    }

    @Test
    fun seekStaysInsideTheFile() {
        assertEquals(5_000, MediaInfo.seekTarget(0, 5_000, 60_000))
        assertEquals(0, MediaInfo.seekTarget(2_000, -5_000, 60_000))
        assertEquals(60_000, MediaInfo.seekTarget(58_000, 5_000, 60_000))
        // Length not known yet: forward is allowed, backward still stops at 0.
        assertEquals(12_000, MediaInfo.seekTarget(7_000, 5_000, 0))
        assertEquals(0, MediaInfo.seekTarget(1_000, -5_000, -1))
    }

    @Test
    fun progressIsAFractionOrZeroUntilTheLengthIsKnown() {
        assertEquals(0.5f, MediaInfo.progress(30_000, 60_000), 0.0001f)
        assertEquals(0f, MediaInfo.progress(30_000, 0), 0.0001f)
        assertEquals(1f, MediaInfo.progress(70_000, 60_000), 0.0001f)
    }
}
