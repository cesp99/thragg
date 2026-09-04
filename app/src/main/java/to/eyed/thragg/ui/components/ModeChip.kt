package to.eyed.thragg.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import to.eyed.thragg.ui.theme.LocalThraggColors
import to.eyed.thragg.ui.theme.MD

/**
 * The agent's current mode, in the colour that mode has in every Spettro
 * front-end.
 *
 * A label, not a control — tapping the mode happens in the config sheet, and a
 * chip that looks pressable but is not is worse than one that does not. The
 * tint comes from `ThraggColors.modeColor`, which is the TUI's table
 * (`styles.go`) and spettro-android's `SpettroColors.kt:69-96`, so "plan" is
 * the same purple on the phone as in the terminal. That agreement is the
 * reason these hues are the one thing in the app not derived from the user's
 * editor theme.
 *
 * FILL AT 14%, INK SOLVED. The wash is what makes the chip read as a chip
 * without a border; the text is `modeInk`, solved to 4.5:1 against a card's
 * ground, because the raw mode hue on a light theme is a pastel and pastels
 * are where a label quietly stops being readable.
 *
 * [colorName] is the manifest's colour name when the agent supplied one
 * ("green", "cyan"); null falls back to the mode id, and an unknown id falls
 * back to the theme's own accent. Callers keep their `category != "mode"`
 * guard — this decides what a name means, not whether it is a mode's.
 */
@Composable
fun ModeChip(
    name: String,
    modifier: Modifier = Modifier,
    colorName: String? = null,
) {
    val colors = LocalThraggColors.current
    val key = colorName ?: name
    Text(
        text = name,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = colors.modeInk(key),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(MD.pill))
            .background(colors.modeColor(key).copy(alpha = 0.14f))
            .padding(horizontal = MD.pillPadX, vertical = MD.pillPadY),
    )
}
