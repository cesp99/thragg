package to.eyed.thragg.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.effectSpec

/**
 * Two to four flat choices, side by side, with the ACTIVE one's description
 * printed underneath.
 *
 * The description is the whole reason this is a component and not a call to
 * `SingleChoiceSegmentedButtonRow`. A segment has room for one or two words,
 * so a row of them tells you the names of the options and nothing about them —
 * which is how "Restricted" ended up telling nobody what it restricts. Putting
 * the active choice's own sentence under the row costs one line of height,
 * says the thing that matters, and crossfades as the selection moves so the
 * connection between the tap and the sentence is visible.
 *
 * IT CROSSFADES, IT DOES NOT SLIDE. The description is not travelling
 * anywhere: two sentences in the same place are a substitution, and a fade on
 * `effectSpec()` reads as one. A slide would imply the sentences are a
 * sequence you can move through, which is [LevelSlider]'s vocabulary, not
 * this one — and `effectSpec()` is also what makes the substitution instant
 * when reduce-motion is on, which a hand-written `tween` would not be.
 *
 * THE UPPER BOUND IS FOUR and it is a real constraint, not a style rule: at
 * 400dp minus the 16dp gutters, five segments give 70dp each, which cannot
 * hold "Ask once each" at any size a thumb can aim at. Anything longer is a
 * [DrillPage] or a column of [SelectRow]s — the config sheet's `selectStyle`
 * makes exactly that call.
 *
 * Stock `SegmentedButton` carries the shape (first and last ends rounded, the
 * middles square), the divider between segments, the check animation and the
 * correct role semantics. Its colours are overridden only where the design
 * differs: the active container is `primary` at 16% rather than
 * `secondaryContainer`, so a selected segment is a wash rather than a second
 * button-coloured object beside the real buttons.
 */
@Composable
fun SegmentedSelect(
    options: List<Choice>,
    selectedValue: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    showActiveDescription: Boolean = true,
) {
    if (options.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    // Resolved here rather than inside `transitionSpec`, which is not a
    // composable lambda — see the same note in LevelSlider.
    val fade = effectSpec<Float>()
    Column(modifier = modifier) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                val selected = option.value == selectedValue
                SegmentedButton(
                    selected = selected,
                    onClick = { onSelect(option.value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = scheme.primary.copy(alpha = 0.16f),
                        activeContentColor = scheme.onSurface,
                        activeBorderColor = scheme.outlineVariant,
                        inactiveContainerColor = scheme.surface,
                        inactiveContentColor = scheme.onSurfaceVariant,
                        inactiveBorderColor = scheme.outlineVariant,
                    ),
                    label = {
                        Text(
                            text = option.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        val description = options.firstOrNull { it.value == selectedValue }?.description
        if (showActiveDescription && !description.isNullOrBlank()) {
            AnimatedContent(
                targetState = description,
                transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
                label = "segment-description",
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space2, start = MD.space1),
                )
            }
        }
    }
}
