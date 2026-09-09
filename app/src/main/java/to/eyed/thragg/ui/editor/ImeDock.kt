package to.eyed.thragg.ui.editor

/**
 * How much of a pane the soft keyboard covers, in pixels, given the
 * keyboard's inset from the window's bottom, the window's height, and the
 * pane's own bottom edge in window coordinates.
 *
 * `imePadding` cannot answer this: it would pad by the whole keyboard, and
 * part of that keyboard may already be below the pane — whatever sits between
 * the pane's bottom and the window's. What is left after subtracting that is
 * the overlap; it is zero when the keyboard is hidden or ends above nothing of
 * the pane, and the whole inset when the pane reaches the window's bottom.
 *
 * Pure, so the docking of the action row and the popups (EditorPane.kt) has a
 * host test. A [paneBottomPx] below zero means "not placed yet" and is taken
 * as the window's bottom — the pane is about to be laid out reaching it, and
 * a row lifted by the whole keyboard is the right first frame.
 */
internal fun imeOverlap(imeBottomPx: Float, windowHeightPx: Float, paneBottomPx: Float): Float {
    val bottom = if (paneBottomPx < 0f) windowHeightPx else paneBottomPx
    return (imeBottomPx - (windowHeightPx - bottom)).coerceAtLeast(0f)
}
