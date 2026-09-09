package to.eyed.thragg.ui.editor

import androidx.compose.ui.unit.dp

/** The fixed key row that rides the keyboard (EditorPane's action row). */
internal val ACTION_ROW_HEIGHT = 44.dp

/** The caret readout above it: a readout, not a target, so it is thin. */
internal val CARET_READOUT_HEIGHT = 18.dp

/**
 * Everything the keyboard's dock puts between the buffer and the keys.
 *
 * The one number for "the first pixel of the pane a popup, or the caret, may
 * not use": the reveal arithmetic, the popup placement and — since the toast
 * band learned to ride the keyboard and promptly landed on the keys — the
 * notification host all read it, so a band added to the dock cannot be added
 * to one of them and forgotten in the others.
 */
internal val IME_DOCK_HEIGHT = CARET_READOUT_HEIGHT + ACTION_ROW_HEIGHT

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
