package to.eyed.thragg.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import java.util.Locale
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.SeekerIcon
import to.eyed.thragg.ui.theme.mutedIcon

/**
 * The label over a group of rows: 12sp, tracked out to 0.8sp, in
 * `onSurfaceVariant`.
 *
 * There were three private copies of this before it moved here
 * (SettingsScreen.kt:276, ChangesScreen.kt:466, and the config sheet's own),
 * at three sizes and two colours, which is what a component library exists to
 * stop. The extra letter-spacing is the whole trick at this size: caps at 12sp
 * set solid read as a block rather than as words, and 0.8sp is where the
 * counters open up without the phrase falling apart.
 *
 * IT UPPERCASES ITS OWN TEXT so a caller cannot supply the odd one out, and
 * the row is marked as a `heading()` so TalkBack's heading navigation can jump
 * between sections — which on a screen that is a stack of six groups is the
 * difference between a scroll and a swipe. The visible text is upper case and
 * the semantics carry the string as it was written, so nothing is spelled out
 * letter by letter.
 *
 * [icon] draws at [IconSize.Marker] with the 6dp [MD.iconGap], the one gap
 * that exists because 4dp crowds a 14dp glyph against 12sp text and 8dp
 * unhooks it. It is decoration — the words beside it are the label — so it
 * carries no description of its own.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    Row(
        modifier = modifier.padding(bottom = MD.space2).semantics(mergeDescendants = true) {
            heading()
            // The drawn string is upper case; this is the one as written, so a
            // screen reader is never handed a word it might spell out.
            contentDescription = text
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.iconGap),
    ) {
        if (icon != null) {
            SeekerIcon(
                icon = icon,
                contentDescription = null,
                tint = mutedIcon,
                size = IconSize.Marker,
            )
        }
        Text(
            text = text.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
