package to.eyed.seeker.code.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.theme.IconSize
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.SeekerIcon

/**
 * Nothing here — said in a way that names the way out.
 *
 * THE MARK IS STATIC, and that is a decision rather than an omission. This is
 * the slot spettro-chat-android fills with `LiquidMorph`: a 7000ms oscillating
 * blob with three blurred layers that reallocates a `Shape` every frame,
 * reads no reduce-motion signal at all, and would sit two taps from a
 * tree-sitter buffer. The slot is worth having; the mascot is not. Seeker's
 * own 40dp mark, drawn once, does the same job of making an empty screen look
 * intended rather than broken.
 *
 * THE COPY REGISTER IS THE EXISTING ONE, and it is right: "No thread open.
 * Start one to talk to Spettro." — a headline that states the fact and a body
 * that names the action. Not "Where should we begin?". Unlike a chat app the
 * composer here is not always available, so an empty state that only sets a
 * mood leaves the user with nowhere to press.
 *
 * [action] is a slot rather than a label plus a lambda because the button
 * differs by screen — a filled `Button` on Setup, a text button on Problems,
 * nothing at all on a search with no matches, where the way out is to type
 * something else.
 */
@Composable
fun EmptyState(
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = R.drawable.ic_launcher_monochrome,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space8, vertical = MD.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MD.space3),
    ) {
        if (icon != null) {
            SeekerIcon(
                icon = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                size = IconSize.Hero,
            )
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Column(modifier = Modifier.padding(top = MD.space2)) { action() }
        }
    }
}
