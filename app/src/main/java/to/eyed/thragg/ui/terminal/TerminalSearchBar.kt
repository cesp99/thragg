package to.eyed.thragg.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.termux.view.TerminalView
import kotlinx.coroutines.delay
import to.eyed.thragg.terminal.TerminalSessionHost
import to.eyed.thragg.ui.theme.BufferFontFamily
import to.eyed.thragg.ui.theme.LocalZedTheme

/** The buffer search bar's chrome, reused measure for measure (BufferSearchBar.kt). */
private val ToolbarVPad = 6.dp
private val ToolbarHPad = 8.dp
private val InputMinHeight = 32.dp
private val FieldRadius = 6.dp
private val ButtonBox = 20.dp
private val CountMinWidth = 40.dp

/**
 * How long the scan waits after the query, the toggle or the screen moves.
 * The scan is main-thread work (see [searchTerminal]), so a build printing a
 * line per millisecond must cost one scan per pause, not one per line.
 */
private const val SEARCH_DEBOUNCE_MS = 150L

/**
 * Find in the terminal's scrollback — Zed's buffer search bar deployed over a
 * terminal (terminal_view.rs `SearchableItem for TerminalView`), in the shape
 * of the editor's own bar: the query with the case toggle inside its right
 * edge, the prev/next arrows behind a hairline, the match count, close.
 * Enter and Shift+Enter walk the hits, Escape closes and hands the keyboard
 * back to the shell.
 *
 * Matches are found over the emulator's screen and transcript, and the
 * current one is scrolled to and painted through the view's highlight —
 * both live in [TerminalView], so the bar is handed the view rather than
 * the session.
 */
@Composable
fun TerminalSearchBar(
    host: TerminalSessionHost,
    view: TerminalView?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var caseSensitive by remember { mutableStateOf(false) }
    var matches by remember { mutableStateOf(emptyList<TerminalMatch>()) }
    var current by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // The highlight is the bar's: it goes when the bar goes, and when the
    // session under it changes.
    DisposableEffect(view, host) {
        onDispose { view?.clearHighlight() }
    }

    // Re-run on the query, the toggle, and every screen revision — the shell
    // keeps printing while the bar is up, and rows the matches were measured
    // against scroll into the transcript as it does.
    LaunchedEffect(query.text, caseSensitive, host, host.screenRevision, view) {
        val emulator = view?.mEmulator
        if (query.text.isEmpty() || emulator == null) {
            matches = emptyList()
            current = 0
            view?.clearHighlight()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        val found = searchTerminal(
            screen = emulator.screen,
            columns = emulator.mColumns,
            screenRows = emulator.mRows,
            query = query.text,
            caseSensitive = caseSensitive,
        )
        // A fresh query starts at the newest hit — the one nearest the prompt
        // is the one you were looking at — and a rescan keeps its place.
        val wasEmpty = matches.isEmpty()
        matches = found
        current = if (wasEmpty) (found.size - 1).coerceAtLeast(0) else current.coerceIn(0, (found.size - 1).coerceAtLeast(0))
        revealTerminalMatch(view, found.getOrNull(current))
    }

    fun step(delta: Int) {
        if (matches.isEmpty()) return
        current = ((current + delta) % matches.size + matches.size) % matches.size
        view?.let { revealTerminalMatch(it, matches[current]) }
    }

    val inputStyle = MaterialTheme.typography.bodyMedium.let { base ->
        base.copy(fontFamily = BufferFontFamily, lineHeight = base.fontSize * 1.3)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("toolbar.background"))
            .padding(horizontal = ToolbarHPad, vertical = ToolbarVPad)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.F3 -> {
                        step(if (event.isShiftPressed) -1 else 1)
                        true
                    }
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = InputMinHeight)
                .clip(RoundedCornerShape(FieldRadius))
                .border(1.dp, theme.color("border"), RoundedCornerShape(FieldRadius))
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                    .pointerHoverIcon(PointerIcon.Text),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = inputStyle.copy(
                        color = theme.color(
                            if (query.text.isNotEmpty() && matches.isEmpty()) "error" else "text"
                        ),
                    ),
                    cursorBrush = SolidColor(theme.cursor),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (query.text.isEmpty()) {
                    Text(
                        text = "Search…",
                        style = inputStyle,
                        color = theme.color("text.placeholder"),
                    )
                }
            }
            BarButton("Aa", "Match case", selected = caseSensitive) {
                caseSensitive = !caseSensitive
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(ButtonBox)
                    .background(theme.color("border.variant"))
            )
            Box(modifier = Modifier.width(8.dp))
            BarButton(
                "‹",
                "Previous match",
                enabled = matches.isNotEmpty(),
                textStyle = MaterialTheme.typography.bodyMedium,
            ) { step(-1) }
            BarButton(
                "›",
                "Next match",
                enabled = matches.isNotEmpty(),
                textStyle = MaterialTheme.typography.bodyMedium,
            ) { step(1) }
            Text(
                text = if (matches.isNotEmpty()) "${current + 1}/${matches.size}" else "0/0",
                style = MaterialTheme.typography.labelMedium,
                color = theme.color(if (matches.isNotEmpty()) "text" else "text.disabled"),
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .widthIn(min = CountMinWidth),
            )
        }

        BarButton("✕", "Close", textStyle = MaterialTheme.typography.bodyMedium) { onDismiss() }
    }
}

/** The buffer search bar's square subtle button (BufferSearchBar.kt), same colours. */
@Composable
private fun BarButton(
    glyph: String,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        !enabled -> theme.color("ghost_element.disabled")
        pressed -> theme.color("ghost_element.active")
        hovered -> theme.color("ghost_element.hover")
        else -> theme.color("ghost_element.background")
    }
    Box(
        modifier = Modifier
            .size(ButtonBox)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = textStyle,
            color = theme.color(
                when {
                    !enabled -> "text.disabled"
                    selected -> "text.accent"
                    else -> "text"
                }
            ),
        )
    }
}
