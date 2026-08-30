package to.eyed.seeker.code.ui.theme

import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.Modifier

/**
 * A 48dp minimum *touch* target around a control that draws smaller.
 *
 * Zed's chrome is dense — an `IconButton` at `ButtonSize::Default` is
 * `rems_from_px(22)`, and a tab's ✕ smaller still — and the 2026-08-17
 * density decision kept it that way on purpose: this app is meant to look
 * like Zed, and every button's action is also a chord and a palette row. What
 * that decision was about is the size the button is *drawn*; it was never
 * about the size of the area a finger has to hit, and 22dp is well under the
 * 48dp that Android's accessibility guidance (and WCAG 2.5.8) asks for.
 *
 * So the glyph keeps Zed's size and the target grows around it. Material's
 * `minimumInteractiveComponentSize` is exactly this: it measures the content,
 * then reports at least 48dp and centres the content inside — the drawn
 * control does not change by a pixel.
 *
 * In a bar with a fixed height — the tab strip, the toolbar, the status bar —
 * the parent's own height wins, so the target widens to 48dp and the bar stays
 * the height Zed's metrics make it. In anything that sizes to its content —
 * a dialog, a panel row, the terminal's key row — it grows in both directions,
 * which is where the 48dp was most missing.
 *
 * Reach for this on every icon-only control. A control with a text label is
 * usually already wider than 48dp, and one that is not costs nothing to wrap.
 */
fun Modifier.touchTarget(): Modifier = this.minimumInteractiveComponentSize()
