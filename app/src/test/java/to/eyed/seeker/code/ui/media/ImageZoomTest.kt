package to.eyed.seeker.code.ui.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zed's image viewer arithmetic (image_viewer.rs:55-57, 210-249, 424-432),
 * pinned: the step, the clamp, the fit that never enlarges, and the opening
 * fit that happens once per file and not again on a resize.
 */
class ImageZoomTest {

    @Test
    fun chordsStepByZedsFactorAndClamp() {
        val zoom = ImageZoom()
        zoom.zoomIn()
        assertEquals(1.1f, zoom.zoom, 0.0001f)
        zoom.zoomOut()
        assertEquals(1f, zoom.zoom, 0.0001f)
        repeat(100) { zoom.zoomIn() }
        assertEquals(ImageZoom.MAX_ZOOM, zoom.zoom, 0.0001f)
        repeat(200) { zoom.zoomOut() }
        assertEquals(ImageZoom.MIN_ZOOM, zoom.zoom, 0.0001f)
    }

    @Test
    fun resetIsActualSizeWithThePanPutBack() {
        val zoom = ImageZoom()
        zoom.gesture(centroid = Offset(10f, 10f), dragged = Offset(30f, -5f), factor = 2f)
        assertEquals(2f, zoom.zoom, 0.0001f)
        assertTrue(zoom.pan != Offset.Zero)
        zoom.reset()
        assertEquals(1f, zoom.zoom, 0.0001f)
        assertEquals(Offset.Zero, zoom.pan)
    }

    /** `compute_fit_to_view_zoom`: the smaller axis ratio, and never more than 1. */
    @Test
    fun fitIsTheSmallerAxisRatioCappedAtOne() {
        assertEquals(0.5f, ImageZoom.fittedZoom(Size(1000f, 1000f), Size(2000f, 500f))!!, 0.0001f)
        assertEquals(0.25f, ImageZoom.fittedZoom(Size(1000f, 500f), Size(1000f, 2000f))!!, 0.0001f)
        assertEquals(1f, ImageZoom.fittedZoom(Size(1000f, 1000f), Size(64f, 64f))!!, 0.0001f)
        assertEquals(null, ImageZoom.fittedZoom(Size.Zero, Size(64f, 64f)))
        assertEquals(null, ImageZoom.fittedZoom(Size(1000f, 1000f), Size(0f, 64f)))
    }

    @Test
    fun theOpeningFitHappensOncePerFile() {
        val zoom = ImageZoom()
        zoom.openFile("/a.png")
        // A pane not laid out yet fits nothing, and is not counted as fitted.
        zoom.layout(Size.Zero, Size(2000f, 2000f))
        assertFalse(zoom.isFitted)
        zoom.layout(Size(1000f, 1000f), Size(2000f, 2000f))
        assertTrue(zoom.isFitted)
        assertEquals(0.5f, zoom.zoom, 0.0001f)
        // The user zooms; a resize afterwards leaves it alone.
        zoom.zoomIn()
        zoom.layout(Size(500f, 500f), Size(2000f, 2000f))
        assertEquals(0.55f, zoom.zoom, 0.0001f)
        // FitToView asked for uses the newest sizes.
        assertTrue(zoom.fitToView())
        assertEquals(0.25f, zoom.zoom, 0.0001f)
        // A new file starts over; the same file again does not.
        zoom.openFile("/a.png")
        assertTrue(zoom.isFitted)
        zoom.openFile("/b.png")
        assertFalse(zoom.isFitted)
        assertEquals(1f, zoom.zoom, 0.0001f)
    }

    @Test
    fun pinchKeepsThePointUnderTheFingersStill() {
        val zoom = ImageZoom()
        // Zooming about a point 100px right of centre by 2× pushes the
        // picture 100px left, so that point stays put.
        zoom.gesture(centroid = Offset(100f, 0f), dragged = Offset.Zero, factor = 2f)
        assertEquals(Offset(-100f, 0f), zoom.pan)
        assertEquals(2f, zoom.zoom, 0.0001f)
    }
}
