package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.rem

/**
 * The which-key hint: what a half-pressed chord could still become.
 *
 * Zed's `which_key` — Emacs' `which-key` before it — answers the moment
 * between the two strokes of `ctrl-k ctrl-0`. The keymap already holds that
 * moment ([ChordDispatcher.pending], with [CHORD_TIMEOUT_MS] to end it) and
 * the status bar already prints the strokes typed so far; what was missing was
 * the half that teaches, which is the *list*: press Ctrl+K and you should be
 * able to see, without letting go, that Ctrl+0 resets the font and Ctrl+S
 * opens the keymap.
 *
 * It is deliberately not a modal. Nothing here takes focus, nothing here can
 * be tapped: the chord is finished on the keyboard or abandoned by the pause,
 * and a panel you could dismiss would be a third way out of a state that has
 * two. Every row it lists is in the command palette as well, which is the
 * touch route to the same commands.
 */
@Composable
fun WhichKeyOverlay(
    /** The strokes typed so far — empty hides the whole thing. */
    pending: List<Keystroke>,
    /** The contexts the next stroke will be resolved against. */
    contexts: Collection<KeymapContext>,
    modifier: Modifier = Modifier,
    keymap: Keymap = KeymapStore.keymap,
) {
    if (pending.isEmpty()) return
    val rows = remember(keymap, pending, contexts) { keymap.completions(pending, contexts) }
    if (rows.isEmpty()) return
    val theme = LocalZedTheme.current
    val typed = pending.joinToString(", ") { it.label }

    Column(
        modifier = modifier
            .padding(horizontal = rem(0.5f), vertical = rem(0.25f))
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(rem(ZedRadius.LG)))
            .background(theme.color("elevated_surface.background", MaterialTheme.colorScheme.surface))
            .border(
                width = 1.dp,
                color = theme.color("border.variant", theme.color("border")),
                shape = RoundedCornerShape(rem(ZedRadius.LG)),
            )
            .padding(vertical = rem(0.375f))
            // One announcement for the whole panel rather than one per row:
            // a screen reader should say what is pending and how many ways
            // there are out of it, not read a table aloud mid-chord.
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = whichKeyAnnouncement(typed, rows.size)
            },
    ) {
        Text(
            text = "$typed …",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(horizontal = rem(0.75f), vertical = rem(0.125f)),
        )
        Column(
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            for (row in rows) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = rem(0.75f), vertical = rem(0.125f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rem(0.5f)),
                ) {
                    Text(
                        text = row.keys,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = theme.color("text.accent"),
                        maxLines = 1,
                    )
                    Text(
                        text = humanizeActionName(row.action),
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * What a screen reader says while a chord is pending — pulled out so the
 * sentence is checkable on the host.
 */
internal fun whichKeyAnnouncement(typed: String, count: Int): String =
    if (count == 1) {
        "$typed pressed. One way to finish it."
    } else {
        "$typed pressed. $count ways to finish it."
    }
