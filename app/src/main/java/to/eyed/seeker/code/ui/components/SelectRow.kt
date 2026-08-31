package to.eyed.seeker.code.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.SelectionMark
import to.eyed.seeker.code.ui.theme.effectSpec
import to.eyed.seeker.code.ui.theme.spatialSpec

/**
 * One selectable value: what goes back on the wire, what the user reads, and
 * why they would pick it.
 *
 * The same three fields ACP's `session/set_config_option` carries
 * (`AgentConfigOption.Choice`), spelled again here so the components that draw
 * a choice do not drag the agent protocol into a package that also draws the
 * framework picker on New program and the staged/unstaged filter on Changes.
 * Those are choices too and they have no wire value at all — [value] is
 * simply the identity the caller gets back.
 *
 * [description] is what a segmented control cannot show and is the reason
 * [SegmentedSelect] prints the ACTIVE one underneath: three words in a segment
 * tell nobody what "Restricted" restricts.
 */
@Immutable
data class Choice(
    val value: String,
    val name: String,
    val description: String? = null,
)

/**
 * A choice as a row: the mark, the label, the reason, and whatever the caller
 * needs on the right.
 *
 * The row is the whole target — 48dp minimum, the full width, one
 * `selectable`/`toggleable` node — rather than a mark you have to hit. That is
 * both the Material rule and the practical one on a phone: the mark is 18dp
 * and the thumb is not.
 *
 * SEMANTICS COME FROM THE MODIFIER, not from the drawing. `selectable(role =
 * RadioButton)` and `toggleable(role = Checkbox)` are what make TalkBack say
 * "selected, radio button" and offer the right action; [SelectionMark] is
 * decoration underneath it, which is why it takes no description of its own.
 * The `multi` split is a real difference in meaning — whether a second tap
 * adds to the answer or replaces it — so it changes both the mark and the
 * announced role, and nothing else.
 *
 * The label-to-description gap is [MD.space05] (2dp), the one gap in the
 * rhythm that is not a gap between two things: a label and its own
 * description are one block, and 4dp already reads as two.
 */
@Composable
fun SelectRow(
    label: String,
    description: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    multi: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val mark by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = effectSpec(),
        label = "select-mark",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .then(
                if (multi) {
                    Modifier.toggleable(
                        value = selected,
                        role = Role.Checkbox,
                        onValueChange = { onSelect() },
                    )
                } else {
                    Modifier.selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = onSelect,
                    )
                },
            )
            .padding(horizontal = MD.space3, vertical = MD.rowPadY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
    ) {
        SelectionMark(selected = selected, multi = multi, tint = mark)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/**
 * The same choice as a card: for options that carry more than a sentence, and
 * for grids where a row would waste the width.
 *
 * SELECTION IS A BORDER CHANGE, NOT A FILL CHANGE, and this is where that
 * decision is drawn. The fill ladder is already spoken for — a rung means
 * "raised", not "chosen" — so a selected card keeps its ground and its edge
 * goes from a 1dp `outlineVariant` hairline to 1.5dp of `primary` at 70%. The
 * width animates on [spatialSpec] and the colour on [effectSpec], which is the
 * same split every other state change in the app uses: geometry has mass,
 * colour does not.
 *
 * Half a dp of extra border is deliberately what carries it. A selected state
 * that repaints the whole card is louder than the content inside it, and on a
 * page where three of five sections have a selection, that is a page of
 * highlights with nothing left to highlight.
 */
@Composable
fun SelectableCard(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(MD.radiusSm),
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val edge by animateColorAsState(
        targetValue = if (selected) {
            scheme.primary.copy(alpha = 0.7f)
        } else {
            scheme.outlineVariant
        },
        animationSpec = effectSpec(),
        label = "card-border-colour",
    )
    val width by animateDpAsState(
        targetValue = if (selected) 1.5.dp else MD.hairline,
        animationSpec = spatialSpec(),
        label = "card-border-width",
    )
    Surface(
        selected = selected,
        onClick = onSelect,
        enabled = enabled,
        modifier = modifier,
        shape = shape,
        color = fill,
        border = BorderStroke(width, edge),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = { Column(content = content) },
    )
}
