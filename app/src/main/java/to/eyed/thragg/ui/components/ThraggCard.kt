package to.eyed.thragg.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.pressScale

/**
 * The one raised block in the Material half: a fill step and a hairline, and
 * nothing else.
 *
 * ELEVATION IS ZERO EVERYWHERE, in both halves, and this component is where
 * that decision is enforced rather than restated. `shadowElevation` and
 * `tonalElevation` are pinned to zero here, and the scheme's `surfaceTint` is
 * `Color.Transparent` (MaterialBridge.kt), so a Material `Surface` cannot wash
 * the accent over itself the way tonal elevation does by default. Depth is
 * carried by exactly two devices — a step along the `surfaceContainer` ladder
 * and one 1dp `outlineVariant` hairline — because a shadow under a card is
 * what makes a Compose screen read as a floating desktop panel beside a
 * tree-sitter buffer that has no shadows at all (docs/VISUAL.md,
 * "Foundations", ELEVATION).
 *
 * [filled] `false` is the transparent case rather than a second component: a
 * tool row is quiet until it is opened, and a card that fades its fill in is
 * one animation on one colour instead of a swap between two composables that
 * would lose the row's layout mid-transition.
 *
 * [borderWidth] exists for the one state change the design allows a border to
 * carry: selection is a BORDER change, not a fill change — 1dp
 * `outlineVariant` becomes 1.5dp `primary` at 70% — because a selected fill on
 * a ladder whose rungs are already fills is a rung, not a state. [SelectableCard]
 * animates exactly that pair; anything hand-rolling a selected card
 * should pass the same two values rather than inventing a third.
 *
 * [onClick] null draws a card that is not a control, which is the common case;
 * pass one and the card gets Material's press feedback and a button role. The
 * ripple is back in the Material half on purpose — a row that does not respond
 * to a press is the loudest "this is not a real Android app" tell there is,
 * and `ZedSurface` is where the editor's no-ripple rule now lives instead.
 * On top of the ripple the clickable form *gives*: [pressScale] on the same
 * interaction source, so the card shrinks under the thumb the frame it is
 * pressed and springs back on release. A card is drawn as an object, and an
 * object that is pushed should move.
 */
@Composable
fun ThraggCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(MD.radiusMd),
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    filled: Boolean = true,
    borderWidth: Dp = MD.hairline,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val container = if (filled) fill else Color.Transparent
    // No stroke at all rather than a zero-width one: a BorderStroke of 0.dp
    // still costs a draw pass and, at some densities, still puts a line down.
    // "No border" has to mean no draw call.
    val stroke = if (borderWidth > 0.dp) BorderStroke(borderWidth, border) else null
    if (onClick == null) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = container,
            border = stroke,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = { Column(content = content) },
        )
    } else {
        val interaction = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            modifier = modifier.pressScale(interaction),
            interactionSource = interaction,
            shape = shape,
            color = container,
            border = stroke,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = { Column(content = content) },
        )
    }
}
