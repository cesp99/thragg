package to.eyed.thragg.ui.components

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import to.eyed.thragg.ui.theme.MD

/**
 * The app's one seam: 1dp of `outlineVariant`, and never a colour chosen at
 * the call site.
 *
 * It replaces ninety-six raw `HorizontalDivider`s, most of which passed their
 * own `theme.color(...)` — which is how one screen ended up with three
 * different rules drawn down it, each a shade off the last. There is exactly
 * one line weight in the design and exactly one ink for it, because with
 * elevation pinned to zero the hairline is doing the work a shadow does
 * elsewhere: it is the edge, not a decoration on one (docs/VISUAL.md,
 * "Foundations", ELEVATION).
 *
 * [color] is a parameter and not a constant for the two honest exceptions —
 * the rule under a top bar that has to match a strip below it, and a Zed
 * island's inner rule, which belongs to the island. Reach for it rarely; the
 * default is the answer.
 */
@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    HorizontalDivider(modifier = modifier, thickness = MD.hairline, color = color)
}
