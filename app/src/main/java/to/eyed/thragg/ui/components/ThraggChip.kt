package to.eyed.thragg.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.theme.pressScale

/**
 * A small pill that does something: an attachment, a filter, a branch, a tab.
 *
 * Stock `AssistChip` is deliberately not used, and the reason is size. Its
 * container is 32dp with 8dp of internal padding and its label slot takes
 * `labelLarge` at 14sp — a strip of six of them does not fit across 400dp with
 * a filename in each. This draws the same idea at the app's own metrics: a
 * 28dp pill, `labelMedium`, and the 6dp [MD.iconGap] between a 14dp glyph and
 * its label. The 48dp touch target arrives without being asked for:
 * material3's clickable `Surface` applies `minimumInteractiveComponentSize()`
 * itself (Surface.kt:219), which grows the hit box and leaves the drawing
 * alone — the same trick `Modifier.touchTarget()` does for the icon buttons
 * that are not surfaces.
 *
 * [tint] null is the neutral chip — `surfaceContainerHigh` with a hairline —
 * and a tint gives the meaning-carrying form: the hue at 14% behind ink at
 * full strength, with the border taking the same hue at 40%. Wash plus ink,
 * never a saturated fill with white on it: a filled chip in the theme's accent
 * puts a second button-coloured object beside every real button.
 *
 * DISABLED IS DRAWN, not hidden. A chip that vanishes when it stops applying
 * takes its row's layout with it; at 38% it stays where it was and stops being
 * a target, which is Material's own disabled alpha and the same number the
 * rest of the app uses.
 *
 * A pill is a small object, and it gives under the thumb ([pressScale]) as
 * every other object in the Material half does. On a 28dp chip the 3% is a
 * single pixel of travel, and it is still the difference between a chip that
 * is a sticker and one that is a button.
 */
@Composable
fun ThraggChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes leading: Int? = null,
    @DrawableRes trailing: Int? = null,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.38f
    // A tinted chip is a wash plus full-strength ink; a neutral one is a rung
    // of the container ladder plus the hairline. [tint] is expected to be an
    // ink that is already legible — `LocalThraggColors`' *Ink roles are solved
    // against a card's ground, and a raw theme hue passed here is exactly the
    // 2.11:1 label the solver exists to prevent.
    val fill = if (tint != null) {
        tint.copy(alpha = 0.14f * alpha)
    } else {
        scheme.surfaceContainerHigh.copy(alpha = alpha)
    }
    val edge = if (tint != null) {
        tint.copy(alpha = 0.40f * alpha)
    } else {
        scheme.outlineVariant.copy(alpha = alpha)
    }
    val ink = (tint ?: scheme.onSurface).copy(alpha = alpha)
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.pressScale(interaction),
        interactionSource = interaction,
        shape = RoundedCornerShape(MD.pill),
        color = fill,
        border = BorderStroke(MD.hairline, edge),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .padding(horizontal = MD.space3, vertical = MD.tagPadY),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
        ) {
            if (leading != null) {
                ThraggIcon(
                    icon = leading,
                    contentDescription = null,
                    tint = if (tint == null) mutedIcon.copy(alpha = alpha) else ink,
                    size = IconSize.Marker,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) {
                ThraggIcon(
                    icon = trailing,
                    contentDescription = null,
                    tint = if (tint == null) mutedIcon.copy(alpha = alpha) else ink,
                    size = IconSize.Marker,
                )
            }
        }
    }
}
