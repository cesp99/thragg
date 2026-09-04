package to.eyed.thragg.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.SeekerIconButton
import to.eyed.thragg.ui.theme.mutedIcon

/** How loud a [NoticeCard] is, and it is the only axis it has. */
enum class Severity {
    /** Something happened that you would want to know. No colour. */
    Info,

    /** Something is going wrong and there is still a way out. Amber. */
    Warn,

    /** Something failed. Red. */
    Error,
}

/**
 * The app's error channel: a card that stays, in the place the thing went
 * wrong.
 *
 * IT REPLACES `android.widget.Toast`, at every site. A toast is untappable,
 * uncopyable, and gone in four seconds — the wrong medium for a build error, a
 * failed tool call or a path that does not exist, which are all things a
 * developer needs to READ and usually to copy. spettro-chat-android reaches
 * for one five times; Seeker's answer is three tiers of this card: dismissible
 * in place for a transient failure, an in-transcript notice for a recoverable
 * wait, and a composer-replacing panel for a hard stop (docs/VISUAL.md, "What
 * we deliberately do not copy").
 *
 * THE SEVERITY IS A WASH AND A GLYPH, NOT A FILLED BOX. `Info` takes the plain
 * card ground; `Warn` and `Error` take their hue at 10% with the hue's own
 * hairline at 35% and a mark in the solved `warnInk`/`dangerInk`. A saturated
 * red panel with white text on it is a dialog pretending to be a card, and it
 * would be the loudest object on any screen it appeared on — which is wrong
 * even for a failure, because the thing that failed is what should be read
 * first.
 *
 * The inks come from `LocalSeekerColors`, solved against a card's actual
 * ground rather than the bare canvas: raw `warning` on a panel measures 1.64:1
 * on Ayu Light, which is a warning nobody can read on the theme most likely to
 * be used outdoors.
 *
 * [actions] is a `RowScope` so the ways out sit on one line under the body —
 * "Compact thread" and "New thread", "Retry" — and [onDismiss] adds the close
 * control, which is what makes the transient tier transient. A notice with
 * neither is a statement; that is a legitimate third shape.
 */
@Composable
fun NoticeCard(
    severity: Severity,
    title: String?,
    body: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalSeekerColors.current
    val hue: Color? = when (severity) {
        Severity.Info -> null
        Severity.Warn -> colors.warnMark
        Severity.Error -> colors.removedMark
    }
    val ink = when (severity) {
        Severity.Info -> scheme.onSurfaceVariant
        Severity.Warn -> colors.warnInk
        Severity.Error -> colors.dangerInk
    }
    val glyph = when (severity) {
        Severity.Info -> R.drawable.ic_file_info
        Severity.Warn, Severity.Error -> R.drawable.ic_ui_warning
    }
    SeekerCard(
        modifier = modifier.fillMaxWidth(),
        fill = hue?.copy(alpha = 0.10f) ?: scheme.surfaceContainer,
        border = hue?.copy(alpha = 0.35f) ?: scheme.outlineVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MD.space3),
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            SeekerIcon(
                icon = glyph,
                contentDescription = null,
                tint = ink,
                size = IconSize.Inline,
            )
            Column(modifier = Modifier.weight(1f)) {
                if (!title.isNullOrBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = ink,
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = if (title.isNullOrBlank()) 0.dp else MD.space05),
                )
                // Drawn whether or not the caller supplied any: an empty
                // actions row is 8dp of bottom breathing room under the body,
                // which the card wants anyway, and detecting an empty content
                // lambda would cost a SubcomposeLayout to save a gap.
                Row(
                    modifier = Modifier.padding(top = MD.space2),
                    horizontalArrangement = Arrangement.spacedBy(MD.space2),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            if (onDismiss != null) {
                SeekerIconButton(
                    icon = R.drawable.ic_ui_close,
                    description = "Dismiss",
                    onClick = onDismiss,
                    tint = mutedIcon,
                    size = IconSize.Marker,
                )
            }
        }
    }
}
