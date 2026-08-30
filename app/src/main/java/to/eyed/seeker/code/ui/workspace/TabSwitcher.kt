package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.rem
import to.eyed.seeker.code.ui.theme.revealItem

/**
 * The tabs in most-recently-used order, newest first.
 *
 * [history] is the pane's activation history, oldest first — Zed's
 * `Pane::activation_history`, which its tab switcher reads in reverse
 * (tab_switcher/src/tab_switcher.rs). Tabs the history has never heard of —
 * opened before the workspace started tracking, or restored from a session —
 * come last, in strip order, rather than being dropped: the switcher lists
 * *every* tab, and an order that hid one would be worse than an imperfect one.
 *
 * Pure, and the reason it lives outside [OpenFilesState]: this is the part
 * worth testing (`TabSwitcherOrderTest`).
 */
fun mruOrder(paths: List<String>, history: List<String>): List<String> {
    val open = paths.toHashSet()
    val ordered = ArrayList<String>(paths.size)
    val seen = HashSet<String>(paths.size)
    for (index in history.indices.reversed()) {
        val path = history[index]
        if (path in open && seen.add(path)) ordered += path
    }
    for (path in paths) if (seen.add(path)) ordered += path
    return ordered
}

/**
 * Where the switcher's selection lands after [delta] steps, wrapping.
 *
 * Zed's switcher is a picker over a list and its Ctrl+Tab is `menu::SelectNext`
 * with wraparound; `ctrl-shift-tab` is `tab_switcher::Toggle { select_last }`,
 * which is the same ring walked the other way.
 */
fun mruStep(size: Int, index: Int, delta: Int): Int {
    if (size <= 0) return 0
    return ((index + delta) % size + size) % size
}

/**
 * The Ctrl+Tab overlay — Zed's `tab_switcher::Toggle`.
 *
 * Held open while Ctrl is down: each Ctrl+Tab moves the highlight one step
 * down the most-recently-used list, Ctrl+Shift+Tab moves it back, and letting
 * Ctrl go switches to whatever is highlighted. That "hold and release" is the
 * whole point — it is what makes Ctrl+Tab mean "the file I was just in"
 * however many times you press it, which positional cycling never can.
 *
 * The keyboard drives it from the workspace's own key pass — the only place a
 * Ctrl *release* can be seen, which is also why the overlay must not take the
 * focus (see the note on the `Popup` below). Everything here is the touch and
 * mouse half: the list scrolls, a row is one tap, and the scrim dismisses.
 */
@Composable
fun TabSwitcherOverlay(
    tabs: List<OpenFile>,
    selected: Int,
    showFileIcons: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (tabs.isEmpty()) return
    val theme = LocalZedTheme.current
    val listState = rememberLazyListState()

    // The highlighted row has to be on screen for the overlay to mean
    // anything: a long strip walked with Ctrl+Tab would otherwise scroll off
    // the bottom immediately.
    LaunchedEffect(selected) {
        if (selected in tabs.indices) listState.revealItem(selected)
    }

    // A `Popup` with `focusable = false`, **not** a `Dialog`, and that is the
    // load-bearing choice: a focusable window takes the keyboard, and the
    // keyboard is how this overlay is driven — the workspace's own key pass
    // has to keep seeing `Ctrl` `Tab` and, above all, the `Ctrl` *release*
    // that commits. A Dialog here left the overlay up forever, because the
    // release went to the dialog's window and the workspace never heard it.
    Popup(
        alignment = Alignment.Center,
        properties = PopupProperties(focusable = false),
        onDismissRequest = onDismiss,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                // The scrim is the touch route out: with no focus there is no
                // outside-click dismissal of its own.
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Surface(
                shape = RoundedCornerShape(rem(0.75f)),
                color = theme.color(
                    "elevated_surface.background",
                    MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 460.dp)
                    // Swallow taps that land on the card, so the scrim behind
                    // does not read them as "dismiss".
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(modifier = Modifier.padding(vertical = rem(0.5f))) {
                    Text(
                        text = "Open tabs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = rem(0.75f),
                            vertical = rem(0.25f),
                        ),
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(min = 260.dp)
                            .heightIn(max = MAX_SWITCHER_HEIGHT),
                    ) {
                        itemsIndexed(tabs, key = { _, file -> file.path }) { index, file ->
                            TabSwitcherRow(
                                file = file,
                                isSelected = index == selected,
                                showFileIcons = showFileIcons,
                                onClick = { onSelect(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Zed's modal scrim, which is `elevated_surface` over a dimmed workspace. */
private const val SCRIM_ALPHA = 0.32f

/** About ten rows at the default font, then it scrolls. */
private val MAX_SWITCHER_HEIGHT = 320.dp

@Composable
private fun TabSwitcherRow(
    file: OpenFile,
    isSelected: Boolean,
    showFileIcons: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        isSelected -> theme.color("element.selected")
        hovered -> theme.color("ghost_element.hover", Color.Transparent)
        else -> Color.Transparent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rem(0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = rem(0.5f), vertical = rem(0.0625f))
            .clip(RoundedCornerShape(rem(0.25f)))
            .background(background)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = rem(0.375f), vertical = rem(0.25f)),
    ) {
        if (showFileIcons) {
            androidx.compose.foundation.Image(
                painter = FileIcons.forFile(file.name),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    theme.color("icon.muted", MaterialTheme.colorScheme.onSurfaceVariant)
                ),
                modifier = Modifier.size(rem(0.875f)),
            )
        }
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = if (file.isPreview) FontStyle.Italic else FontStyle.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(modifier = Modifier.weight(1f))
        // The directory, dimmed, so two `mod.rs` are told apart — Zed's
        // switcher shows the same secondary label.
        val directory = file.path.substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            Text(
                text = directory,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
    }
}
