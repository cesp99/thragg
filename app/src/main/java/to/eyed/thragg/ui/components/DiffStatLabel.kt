package to.eyed.thragg.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import to.eyed.thragg.ui.theme.LocalSeekerColors
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.TabularNums

/**
 * `+24 −6`, in the two colours a diff has, with figures that do not shimmy.
 *
 * Three separate defects die here. The stat was plain `text.muted` at 11sp,
 * which at that size on a 400dp column is the difference between scannable
 * and invisible — so it takes `addedInk`/`removedInk`, which the bridge has
 * already solved to 4.5:1 against a card's actual ground rather than against
 * the bare canvas (on Ayu Light the raw `created` measures 2.11:1). It was
 * drawn at whatever weight the row's style carried — so it is Medium here,
 * because two three-character runs have to hold their own beside a filename.
 * And it re-measured itself every time a number changed — so it carries
 * [TabularNums], which is why a file list stops jittering while a build walks
 * through it.
 *
 * ZEROES ARE OMITTED, not drawn. `+0` beside `−6` is a fact nobody needs and
 * a column of them makes a list look like a table with an empty column; a file
 * that only gained lines says `+24` and stops.
 *
 * The whole label is one semantics node reading "24 added, 6 removed", because
 * a screen reader spelling out "plus twenty-four minus six" is arithmetic, not
 * a description. The minus is U+2212, not a hyphen: it is the same width as
 * the plus, which is the entire reason the pair lines up in a column.
 */
@Composable
fun DiffStatLabel(
    added: Int,
    removed: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
) {
    if (added <= 0 && removed <= 0) return
    val colors = LocalSeekerColors.current
    val style = MaterialTheme.typography.labelSmall.copy(
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = TabularNums,
    )
    val spoken = listOfNotNull(
        "$added added".takeIf { added > 0 },
        "$removed removed".takeIf { removed > 0 },
    ).joinToString(", ")
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(MD.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (added > 0) {
            Text(text = "+$added", style = style, color = colors.addedInk, maxLines = 1)
        }
        if (removed > 0) {
            Text(text = "−$removed", style = style, color = colors.removedInk, maxLines = 1)
        }
    }
}
