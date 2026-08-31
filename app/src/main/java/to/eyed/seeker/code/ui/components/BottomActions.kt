package to.eyed.seeker.code.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import to.eyed.seeker.code.ui.theme.MD

/**
 * The pinned bottom bar, and the seam that stops it eating the row above it.
 *
 * Six surfaces end in a row of actions the list above must not push away —
 * Projects (New program / Clone), New program (Cancel / Create), Setup (Start
 * / Skip), Changes (the commit bar), Problems (Fix with agent) and every
 * [to.eyed.seeker.code.ui.shell.SheetScaffold] with a field or an action in
 * it. All six drew the bar as a bare `Column` or `Box` under a `weight(1f)`
 * body, and all six had the same defect on the device: the body is CLIPPED
 * where the bar begins, so the row that happens to straddle that line is
 * guillotined mid-glyph with nothing to say it was cut. Projects lost half of
 * Settings, New program half of LOCATION, Setup half of its last step. A list
 * that ends in a half-drawn row does not read as "there is more below", it
 * reads as a rendering fault.
 *
 * It is one defect with three halves, so it is fixed once, here, rather than
 * six times at six call sites:
 *
 *  1. **The bar has an edge.** A [HairlineDivider] across its top. With
 *     elevation pinned to zero in both halves there is no shadow to separate
 *     chrome from content, so the hairline is the edge — the same rule
 *     [to.eyed.seeker.code.ui.shell.ShellNavBar] and every top bar follow
 *     (docs/VISUAL.md, "Foundations", ELEVATION).
 *  2. **The content fades into it**, via [fadeUnderBottomActions] on the
 *     scrolling container above. A hairline alone still cuts the row; the fade
 *     is what turns the cut into "this continues".
 *  3. **The content clears it**, via [BottomActionsGap] of bottom padding, so
 *     the last row can be scrolled entirely out from under the fade.
 *
 * The insets are here too, for the same reason: the bar is the last thing
 * above the system's own, so it — not its caller — is what has to clear the
 * gesture handle and rise with the keyboard.
 */
@Composable
fun BottomActions(
    modifier: Modifier = Modifier,
    /** `Start` for a Cancel/Confirm row, `CenterHorizontally` for a stack. */
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Outside the insets, deliberately: the rule is the bar's top edge and
        // it belongs against the content, not floated off it by the IME.
        HairlineDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // UNION, NOT TWO CHAINED PADDINGS. `imePadding()` then
                // `navigationBarsPadding()` adds both, and with the keyboard
                // up the IME already covers the gesture area — the bar would
                // float a gesture handle's height above the keys it is
                // supposed to sit on. The union is the larger of the two,
                // which is what "clear whatever the system is putting under
                // this bar" actually means.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                // 16dp is the screen gutter, everywhere; 12dp above and below
                // is what makes a 48dp control a 72dp band under the thumb.
                .padding(horizontal = MD.space4, vertical = MD.space3),
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}

/**
 * The bottom pad on content that scrolls under a [BottomActions].
 *
 * 24dp, the house figure — "the bottom pad on scrolling content so the last
 * row clears the nav bar or sheet edge" (docs/VISUAL.md, "Foundations",
 * RHYTHM) — and deliberately the SAME value the fade is tall. That is what
 * makes the fade honest at rest: scrolled to the end, the gradient lands on
 * the padding rather than dimming a row the user has already reached.
 */
val BottomActionsGap: Dp = MD.space6

/**
 * Fade the bottom [height] of a scrolling container out to nothing.
 *
 * Applied to the container — the `LazyColumn`, or the `Column` carrying
 * `verticalScroll` — and never to a body that does not scroll: on a short
 * sheet whose content ends above the fold this would dim the bottom of a card
 * for no reason. Pair it with [BottomActionsGap] of trailing padding inside
 * the container and the two cases both come out right: mid-scroll the clipped
 * row dissolves, at the end there is nothing under the gradient to dissolve.
 *
 * ORDER MATTERS ON A `verticalScroll` COLUMN: this goes BEFORE it in the
 * chain. After it the node being masked is the scrolled CONTENT, whose height
 * is the whole page, so the gradient rides down with the text instead of
 * staying at the foot of the viewport. A `LazyColumn` has no such trap — it
 * scrolls inside itself, so the node is the viewport either way.
 *
 * An alpha mask (`DstIn` through an offscreen layer) rather than a scrim in
 * the surface colour, because a scrim has to be told which colour it is
 * standing in for and this is drawn over four different grounds — sheet
 * `surfaceContainer`, screen `surface`, and whatever a card under it is. A
 * mask cannot be given the wrong one. The cost is one offscreen buffer for the
 * container, which is worth it on lists of tens of rows and would not be on
 * the build log.
 */
fun Modifier.fadeUnderBottomActions(height: Dp = BottomActionsGap): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = height.toPx().coerceAtMost(size.height)
        if (fade <= 0f) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - fade,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - fade),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }
