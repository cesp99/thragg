package to.eyed.thragg.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import to.eyed.thragg.R
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.ThraggIconButton
import to.eyed.thragg.ui.theme.effectSpec
import to.eyed.thragg.ui.theme.mutedIcon

/**
 * One filter field, in every list long enough to need one.
 *
 * It is the SAME pill as the composer — 20dp radius, `surfaceContainerHigh`,
 * a hairline that becomes `primary` at 50% while focused — and that sameness
 * is the point of it being here rather than drawn per screen. A user who has
 * typed into the composer knows what this is before reading the placeholder.
 *
 * A `BasicTextField` rather than M3's `TextField` or `OutlinedTextField`, for
 * the reason the composer already documents: those bring a label, a container
 * height and an indicator line that cannot be made into a pill, and fighting
 * them costs more code than drawing the decoration. What the stock field would
 * have given for free — the placeholder, the cursor brush, the IME action — is
 * eight lines here.
 *
 * THE CLEAR BUTTON APPEARS ONLY WITH TEXT IN THE FIELD, at [IconSize.Marker]
 * inside a 48dp target, and it is the one control in this component so it
 * carries the description. `imeAction = Search` rather than `Done` because the
 * keyboard's own key should say what the field does, and search-as-you-type
 * means the key just dismisses.
 *
 * [focusRequester] lets a sheet open with the keyboard already up — the model
 * drill and the command palette both want that, and a picker that needs a tap
 * before it can be typed into is a picker that is faster to scroll.
 */
@Composable
fun ThraggSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge by animateColorAsState(
        targetValue = if (focused) scheme.primary.copy(alpha = 0.5f) else scheme.outlineVariant,
        animationSpec = effectSpec(),
        label = "search-border",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(MD.pill))
            .background(scheme.surfaceContainerHigh)
            .border(MD.hairline, edge, RoundedCornerShape(MD.pill))
            .padding(start = MD.space3, end = MD.space1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        ThraggIcon(
            icon = R.drawable.ic_ui_magnifying_glass,
            contentDescription = null,
            tint = mutedIcon,
            size = IconSize.Inline,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (focusRequester == null) {
                            Modifier
                        } else {
                            Modifier.focusRequester(focusRequester)
                        },
                    ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(scheme.primary),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (value.isNotEmpty()) {
            ThraggIconButton(
                icon = R.drawable.ic_ui_close,
                description = "Clear search",
                onClick = { onValueChange("") },
                tint = mutedIcon,
                size = IconSize.Marker,
            )
        }
    }
}
