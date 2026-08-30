package to.eyed.seeker.code.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.UiFontFamily
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.theme.revealBy

/**
 * How wide one character of the buffer font is taken to be when a column is
 * sized. A monospace advance is about 0.6em; the extra is the cell padding's
 * share, and the whole thing only has to be close — the columns are read, not
 * measured.
 */
private const val CHAR_ADVANCE = 0.62f

/**
 * A `.csv` or `.tsv` drawn as a table — Zed's `tabular_data_preview`, in the
 * same dock the Markdown and SVG previews use and behind the same 👁 toggle.
 *
 * It follows the buffer, not the file: editing a cell in the text pane
 * redraws the table, which is the reason to have both open at once. The parse
 * is debounced and done off the main thread, exactly as the Markdown
 * preview's is and for the same reasons (see [PREVIEW_REPARSE_DEBOUNCE_MS]).
 *
 * Column widths come from the content, as Zed's do, and the table scrolls in
 * both directions rather than wrapping: a cell that wrapped would give every
 * row a different height and make the columns impossible to read across.
 *
 * @param onJumpToSource asked for the 0-based source row of a tapped table
 *   row, so a cell can take the caret to the line it came from.
 */
@Composable
fun TablePreview(
    editor: EditorState,
    path: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onJumpToSource: ((Int) -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val settings = LocalAppSettings.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    val columnState = rememberScrollState()

    val isTable = PreviewKind.of(path) == PreviewKind.Table
    val delimiter = remember(path) { TableDocument.delimiterFor(path) }

    // The same two sources of truth the Markdown preview watches: composition
    // sees our own edits, the poll catches a reload from disk.
    var engineVersion by remember(editor) { mutableLongStateOf(-1L) }
    LaunchedEffect(editor) {
        while (true) {
            engineVersion = editor.session.version
            delay(PREVIEW_VERSION_POLL_MS)
        }
    }

    var document by remember(editor) { mutableStateOf(TableDocument.EMPTY) }
    var tooLarge by remember(editor) { mutableStateOf(false) }
    LaunchedEffect(editor, isTable, delimiter, editor.revision, engineVersion) {
        if (!isTable) {
            document = TableDocument.EMPTY
            return@LaunchedEffect
        }
        delay(PREVIEW_REPARSE_DEBOUNCE_MS)
        val parsed = withContext(Dispatchers.Default) {
            val source = cappedSource(editor.lineCount) { first, last -> editor.linesOf(first, last) }
            source?.let { TableDocument.parse(it, delimiter) }
        }
        tooLarge = parsed == null
        document = parsed ?: TableDocument.EMPTY
    }

    // The buffer face is a composition local now (it follows the user's
    // `buffer_font_family`), so it is read here and keyed, not inside the
    // remember's lambda.
    val bufferFont = BufferFontFamily
    val cellStyle = remember(settings.bufferFontSize, bufferFont) {
        TextStyle(fontFamily = bufferFont, fontSize = settings.bufferFontSize.sp)
    }
    val columnWidths = remember(document, settings.bufferFontSize) {
        (0 until document.columnCount).map { column ->
            (TableDocument.columnWidth(document, column) * settings.bufferFontSize * CHAR_ADVANCE)
                .dp + CellPadding * 2
        }
    }
    val total = columnWidths.fold(0.dp) { sum, width -> sum + width }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("editor.background"))
            .focusRequester(focus)
            .focusable()
            // Focus on a press, as the other previews take it: opening the
            // panel must not pull the keyboard out of the editor.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    focus.requestFocus()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Escape && !event.isCtrlPressed -> {
                        onDismiss()
                        true
                    }
                    event.isCtrlPressed && event.key == Key.MoveHome -> {
                        scope.launch { rowState.revealItem(0) }
                        true
                    }
                    event.isCtrlPressed && event.key == Key.MoveEnd -> {
                        scope.launch {
                            rowState.revealItem((document.rows.size - 1).coerceAtLeast(0))
                        }
                        true
                    }
                    event.isCtrlPressed -> false
                    event.key == Key.PageDown || event.key == Key.PageUp -> {
                        val pages = if (event.key == Key.PageDown) 1f else -1f
                        scope.launch {
                            rowState.revealBy(
                                rowState.layoutInfo.viewportSize.height.toFloat() * pages
                            )
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        PreviewHeader(
            path = path,
            onDismiss = onDismiss,
            // The count is the first thing anyone wants from a file they
            // cannot read straight through.
            detail = when {
                !isTable || tooLarge -> null
                document.isEmpty -> "empty"
                else -> buildString {
                    append(document.rows.size)
                    append(if (document.rows.size == 1) " row × " else " rows × ")
                    append(document.columnCount)
                    append(if (document.columnCount == 1) " column" else " columns")
                    if (document.isTruncated) append(" (first ${TableDocument.MAX_ROWS})")
                }
            },
        )
        HorizontalDivider(color = theme.color("border.variant"))
        when {
            !isTable -> TableNotice("A table preview shows a .csv or .tsv file.")
            tooLarge -> TableNotice(
                "This file is too large to preview. It is still open in the editor."
            )
            document.isEmpty -> TableNotice("There are no rows in this file yet.")
            else -> {
                val border = theme.color("border.variant")
                Column(modifier = Modifier.fillMaxSize().horizontalScroll(columnState)) {
                    Row(
                        modifier = Modifier
                            .width(total)
                            .background(theme.color("toolbar.background"))
                    ) {
                        for (column in 0 until document.columnCount) {
                            TableCell(
                                text = document.header.getOrNull(column).orEmpty(),
                                width = columnWidths[column],
                                style = cellStyle.copy(fontWeight = FontWeight.SemiBold),
                                colour = theme.color("text"),
                            )
                        }
                    }
                    HorizontalDivider(color = border)
                    LazyColumn(state = rowState, modifier = Modifier.width(total).weight(1f)) {
                        itemsIndexed(document.rows) { index, row ->
                            val line = document.rowLines.getOrNull(index)
                            Row(
                                modifier = Modifier
                                    .width(total)
                                    .then(
                                        if (line != null && onJumpToSource != null) {
                                            Modifier.clickable(
                                                onClickLabel = "Go to line $line",
                                            ) { onJumpToSource(line - 1) }
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                for (column in 0 until document.columnCount) {
                                    TableCell(
                                        text = row.getOrNull(column).orEmpty(),
                                        width = columnWidths[column],
                                        style = cellStyle,
                                        colour = theme.color("editor.foreground"),
                                    )
                                }
                            }
                            HorizontalDivider(color = border)
                        }
                    }
                }
            }
        }
    }
}

/** Left and right padding inside a cell. */
private val CellPadding = 8.dp

/** One cell: a fixed column width, one line, clipped rather than wrapped. */
@Composable
private fun TableCell(text: String, width: Dp, style: TextStyle, colour: Color) {
    Text(
        // A cell with an embedded newline is still one row of the table, so it
        // is shown as one line here; the editor keeps the original.
        text = text.replace('\n', ' ').replace('\r', ' '),
        style = style,
        color = colour,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = CellPadding, vertical = 6.dp),
    )
}

@Composable
private fun TableNotice(text: String) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 13.sp),
            color = theme.color("text.muted"),
        )
    }
}
