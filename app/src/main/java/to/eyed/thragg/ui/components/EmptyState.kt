package to.eyed.thragg.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon

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
 * THE MARK SITS ON A DISC. It was drawn bare at 45% on the canvas, and on the
 * device that read as a watermark — the one element on an otherwise empty
 * 890dp column, and the faintest thing on it. A [DiscSize] circle of
 * `surfaceContainer` with the house hairline gives it a place to be: the same
 * two depth devices every card uses, a fill step and a 1dp edge, so the empty
 * state is built from the vocabulary of the screens that are not empty. The
 * ink is `onSurfaceVariant` at full strength; the disc carries the quiet now,
 * so the mark no longer has to.
 *
 * NOTHING HERE ANIMATES IN. A staggered rise was tried and taken out on the
 * device: a destination is switched to tens of times a session, and a screen
 * whose contents arrive a piece at a time after every tap is a screen that
 * makes the user wait for information they asked for. Every tab shows what
 * it has on the frame it is shown (docs/UI.md, "Navigation").
 *
 * THE COPY REGISTER IS THE EXISTING ONE, and it is right: "No thread open.
 * Start one to talk to Spettro." — a headline that states the fact and a body
 * that names the action. Not "Where should we begin?". Unlike a chat app the
 * composer here is not always available, so an empty state that only sets a
 * mood leaves the user with nowhere to press.
 *
 * [action] is a slot rather than a label plus a lambda because the button
 * differs by screen — a filled `Button` on Setup, a text button on Problems,
 * a row of starter chips on the empty Agent thread, nothing at all on a
 * search with no matches, where the way out is to type something else.
 */
@Composable
fun EmptyState(
    headline: String,
    body: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = R.drawable.ic_launcher_monochrome,
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MD.space8, vertical = MD.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MD.space3),
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(DiscSize)
                    .background(scheme.surfaceContainer, CircleShape)
                    .border(MD.hairline, scheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                SeekerIcon(
                    icon = icon,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    size = IconSize.Hero,
                )
            }
        }
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Column(modifier = Modifier.padding(top = MD.space2)) { action() }
        }
    }
}

/** 72dp: the mark's disc, [IconSize.Hero] with 16dp of ground on every side. */
private val DiscSize = 72.dp
