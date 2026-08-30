package to.eyed.seeker.code.ui.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * How far a picture is zoomed and where it has been dragged.
 *
 * Zed's image viewer keeps `zoom_level` and `pan_offset` on the view and
 * moves them from five actions and three gestures (image_viewer.rs:210-357);
 * this is the same pair, held outside the pane so the workspace's commands —
 * the palette rows, the chords the root key pass routes here — reach the same
 * numbers the pinch does.
 *
 * A zoom of 1 is *actual size*, one image pixel per screen pixel, as it is in
 * Zed; the pane draws the bitmap at its own pixel size and scales it by
 * [zoom]. A picture opens fitted to the pane and never larger than 100%,
 * which is Zed's first-layout zoom (image_viewer.rs:424-432).
 *
 * Zed's constants, exactly: 0.1× to 20×, and a chord step of 1.1
 * (image_viewer.rs:55-57). The pinch is continuous and only clamps.
 */
class ImageZoom {
    var zoom by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set

    /**
     * The file this zoom belongs to. The pane calls [openFile] when the tab's
     * path changes, and the first layout after that fits the new picture.
     */
    var path: String? = null
        private set

    /** Whether the picture has had its opening fit yet. */
    var isFitted: Boolean = false
        private set

    /** A new picture: back to the start, and waiting for a first layout to fit. */
    fun openFile(path: String) {
        if (this.path == path) return
        this.path = path
        isFitted = false
        reset()
    }

    /** The pane's size and the picture's, as of the last layout — what a fit needs. */
    var container: Size = Size.Zero
        private set
    var image: Size = Size.Zero
        private set

    /**
     * The pane reporting its size and the picture's. The first report for a
     * file fits the picture — Zed's `initial_zoom_level` (image_viewer.rs:
     * 424-432, 462-464); later ones only remember the sizes for the next
     * `FitToView`, and leave the user's zoom alone.
     */
    fun layout(container: Size, image: Size) {
        this.container = container
        this.image = image
        if (isFitted) return
        if (!fitToView()) return
        isFitted = true
    }

    /** `image_viewer::ZoomIn`: one step in, about the centre (image_viewer.rs:210). */
    fun zoomIn() = clampTo(zoom * ZOOM_STEP)

    /** `image_viewer::ZoomOut`: one step out (image_viewer.rs:214). */
    fun zoomOut() = clampTo(zoom / ZOOM_STEP)

    /**
     * `image_viewer::ResetZoom` and `ZoomToActualSize`, which Zed implements
     * identically: 100%, and the pan put back (image_viewer.rs:218-249).
     */
    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }

    /**
     * `image_viewer::FitToView`: the largest zoom at which the whole picture
     * fits the pane, never past 100% — a small icon is not blown up to fill
     * the screen (`compute_fit_to_view_zoom`, image_viewer.rs:224-239). False
     * when either size is not known yet, so nothing moves.
     */
    fun fitToView(): Boolean {
        val fitted = fittedZoom(container, image) ?: return false
        zoom = fitted
        pan = Offset.Zero
        return true
    }

    /**
     * The pinch and the wheel: scale by [factor] about [centroid], then drag
     * by [dragged], keeping whatever is under the fingers under the fingers —
     * Zed's `set_zoom` with a centre (image_viewer.rs:264-287).
     *
     * [centroid] is measured from the *centre* of the pane, which is where
     * the picture is scaled about and where it sits at zero pan. A point of
     * the picture under the fingers is at `p·zoom + pan`; for it to stay put
     * after the zoom, `pan' = centroid·(1 − f) + pan·f`.
     */
    fun gesture(centroid: Offset, dragged: Offset, factor: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val applied = next / zoom
        pan = centroid * (1f - applied) + pan * applied + dragged
        zoom = next
    }

    private fun clampTo(next: Float) {
        zoom = next.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    companion object {
        const val MIN_ZOOM = 0.1f
        const val MAX_ZOOM = 20f
        const val ZOOM_STEP = 1.1f

        /**
         * Zed's fit: the smaller of the two axis ratios, capped at 1
         * (`compute_fit_to_view_zoom`, image_viewer.rs:232-239), then held to
         * the same range every other zoom is. Null for a size that is not a
         * size — a pane not laid out yet, a bitmap not decoded.
         */
        internal fun fittedZoom(container: Size, image: Size): Float? {
            if (container.width <= 0f || container.height <= 0f) return null
            if (image.width <= 0f || image.height <= 0f) return null
            val fit = minOf(container.width / image.width, container.height / image.height)
            return minOf(fit, 1f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        }
    }
}
