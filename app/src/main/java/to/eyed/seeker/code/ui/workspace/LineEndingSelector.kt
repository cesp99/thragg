package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.LineEnding
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/**
 * Zed's line-ending selector: a two-row picker, `LF` and `CRLF`, with a check
 * on the one in use (`line_ending_selector.rs:104-108, 179-197`). It has no
 * query field — Zed builds it with `Picker::nonsearchable_uniform_list`
 * (`line_ending_selector.rs:70`) — so the placeholder Zed gives it, "Select a
 * line ending…", is written as a heading instead, where a phone can read
 * what the two rows are for.
 *
 * Confirming hands the choice to [onSelect]. What that does is the
 * workspace's business: the engine keeps the text as it is and marks the
 * buffer dirty, and the next save writes the file in the ending chosen.
 */
@Composable
fun LineEndingSelector(
    current: LineEnding?,
    onSelect: (LineEnding) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val choices = remember { LineEnding.entries }
    // Start on the ending in use, so Enter straight away is a no-op.
    var selected by remember { mutableIntStateOf(choices.indexOf(current).coerceAtLeast(0)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun move(delta: Int) {
        val size = choices.size
        selected = ((selected + delta) % size + size) % size
    }

    fun confirm() {
        onSelect(choices[selected])
        onDismiss()
    }

    PickerModal(
        onDismiss = onDismiss,
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionDown -> { move(1); true }
                    event.key == Key.DirectionUp -> { move(-1); true }
                    event.isCtrlPressed && event.key == Key.N -> { move(1); true }
                    event.isCtrlPressed && event.key == Key.P -> { move(-1); true }
                    event.key == Key.Tab -> {
                        move(if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        confirm()
                        true
                    }
                    event.key == Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            },
    ) {
        // Zed's placeholder (line_ending_selector.rs:119-121).
        PickerHeading("Select a line ending…")
        LazyColumn(
            contentPadding = PickerListPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(choices, key = { _, it -> it.key }) { index, ending ->
                PickerListItem(
                    isSelected = index == selected,
                    onClick = {
                        selected = index
                        confirm()
                    },
                ) {
                    Text(
                        text = ending.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = theme.color("text"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (ending == current) {
                        // Zed's end slot: `IconName::Check` in `Color::Muted`
                        // (line_ending_selector.rs:194).
                        PickerCheckMark()
                    }
                }
            }
        }
    }
}

/**
 * A picker's title where it has no query row: the placeholder text Zed would
 * show in the field, in the field's 36px row with its 10px inset and the 1px
 * `border.variant` rule beneath (`picker/src/render.rs:106-126`), so the
 * modal keeps the shape of every other picker.
 */
@Composable
internal fun PickerHeading(text: String) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = theme.color("text.placeholder"),
            maxLines = 1,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(theme.color("border.variant")),
    )
}

/** Zed's `IconName::Check` at `Color::Muted`, in a row's end slot. */
@Composable
internal fun PickerCheckMark() {
    val theme = LocalZedTheme.current
    Text(
        text = "✓",
        style = MaterialTheme.typography.bodyMedium,
        color = theme.color("text.muted"),
        maxLines = 1,
    )
}
