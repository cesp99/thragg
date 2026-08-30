package to.eyed.seeker.code.ui.preview

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.editor.HighlightSpan
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalUiFontSize
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.UiFontFamily
import to.eyed.seeker.code.ui.theme.ZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.theme.revealBy

/** Zed's git panel width, which is what the search dock already uses. */
internal val PreviewDockWidth = 400.dp
private val DockMinWidth = 280.dp

/**
 * What the editor keeps beside the dock however far the grip is dragged.
 *
 * The work area's Row measures its fixed-width panels first and gives the
 * editor what is left, so a dock with no upper bound can leave the editor
 * exactly 0dp wide — it does not shrink, it disappears.
 */
private val MinEditorWidth = 200.dp

/** [DockWidth], clamped to what is actually on offer. See [MinEditorWidth]. */
internal fun clampDockWidth(width: Dp, available: Dp): Dp =
    width.coerceIn(DockMinWidth, (available - MinEditorWidth).coerceAtLeast(DockMinWidth))

/** The dock's grip, the same 6dp the terminal and search docks use. */
private val HandleWidth = 6.dp

/**
 * The header wears Zed's toolbar frame: `py(Base06)` = 6px and `px(Base08)` =
 * 8px around a 32px item row (workspace/src/toolbar.rs:123-124, 140, 150).
 */
private val HeaderRowHeight = 32.dp
private val HeaderVerticalPad = 6.dp
private val HeaderHorizontalPad = 8.dp

/**
 * The close control is Zed's IconButton at `ButtonSize::Default`: a 22px box
 * (button_like.rs:469), `rounded_sm`, ghost hover. Sub-40dp per the
 * 2026-08-17 density decision; Escape and Ctrl+Shift+M still close the
 * preview without touching it.
 */
private val ControlBox = 22.dp

/**
 * How long the buffer rests before the preview is rebuilt.
 *
 * Parsing a README and asking the engine for its fences' syntax is tens of
 * milliseconds — nothing on its own, and six of them in a row while somebody
 * types a word. The delay is inside the effect, so each keystroke cancels the
 * previous one before it starts and only the last one lands.
 *
 * This is the debounce, not the latency: [PREVIEW_VERSION_POLL_MS] is a key of the
 * same effect and typing moves the engine's version too, so the tick after the
 * last keystroke restarts the wait once more. What a typist actually sees is
 * up to this plus that.
 */
internal const val PREVIEW_REPARSE_DEBOUNCE_MS = 180L

/**
 * How often the engine's buffer version is re-read.
 *
 * `EditorState.revision` covers everything this app's editor does to the text
 * and is immediate, which is why it is the effect's first key. It does *not*
 * cover a reload from disk: the workspace's status loop calls
 * `BufferSession.reload` when a clean file changes underneath it, and nothing
 * about that is visible to composition. One long read every quarter second is
 * what keeps the preview honest about that case.
 */
internal const val PREVIEW_VERSION_POLL_MS = 250L

/**
 * How long the editor's scroll rests before the preview follows it.
 *
 * A fling reports a new top row on every frame, and each of them would be a
 * `scrollToItem` on the preview's list — sixty relayouts a second of a list
 * whose items are paragraphs. Waiting for the scroll to stop moving means one
 * relayout per gesture, and 90 ms is short enough that nobody waits for it.
 */
internal const val SYNC_SETTLE_MS = 90L

/**
 * The most text the preview will take.
 *
 * The editor opens a 50 MB file happily because it is line-windowed — *"the
 * pane never holds the whole buffer, only the lines on screen"* — and this
 * panel is the first thing in the app that pulls all of one into the Java
 * heap. Measured on a README-shaped document, a parsed block list costs 18-23×
 * the source: an [InlineSpan] per styled run, *retained* for as long as the
 * panel is open, and doubled while a reparse builds the next one beside it.
 * There is no `android:largeHeap` here, so a 5 MB `.md` is ~115 MB in one
 * burst — an OutOfMemoryError thrown inside a coroutine, which nothing on this
 * path catches, which is process death, which loses every unsaved tab.
 *
 * A megabyte is ~20 MB retained and ~40 MB across a reparse, which fits beside
 * tree-sitter's arenas and the terminal in a standard per-app heap. The engine
 * already refuses a file over 4 MB for project search (`project_search.rs`);
 * this is the same refusal set lower, because what is measured here is not the
 * file's size but what holding it parsed costs.
 */
internal const val MAX_PREVIEW_CHARS = 1024 * 1024

/** Rows per read while measuring the buffer against [MAX_PREVIEW_CHARS]. */
private const val READ_CHUNK_ROWS = 2000

/**
 * The buffer's text, or null when there is more of it than [limit].
 *
 * Read a chunk of rows at a time and measured as it goes, because the whole
 * point is to *not* materialise a file that is going to be refused: reading
 * the lot and then checking its length is the allocation that kills the
 * process, and it happens before any cap inside the parser could be consulted.
 *
 * [read] is the buffer's own `lines(firstRow, lastRow)` — rows `[first, last)`
 * joined by newlines, which is why the chunks are rejoined with one.
 */
internal fun cappedSource(
    lineCount: Int,
    limit: Int = MAX_PREVIEW_CHARS,
    read: (Int, Int) -> String,
): String? {
    val text = StringBuilder()
    var row = 0
    while (row < lineCount) {
        val end = minOf(lineCount, row + READ_CHUNK_ROWS)
        if (row > 0) text.append('\n')
        text.append(read(row, end))
        if (text.length > limit) return null
        row = end
    }
    return text.toString()
}

/**
 * A rendered view of the open Markdown file — Zed's `markdown_preview`, drawn
 * in Compose.
 *
 * There is deliberately no WebView behind this. A WebView is a second
 * rendering engine in the process, a second security surface for a document
 * the user may have cloned from anywhere, and it cannot be told to use the Zed
 * theme — every colour here comes out of [ZedTheme] and changes with it.
 *
 * The preview *follows* the editor: it re-renders when the buffer changes,
 * and — while scroll sync is on — it scrolls to whichever block owns the top
 * line of the editor's window, the way Zed's `sync_preview_to_source_index`
 * follows the editor's selection (markdown_preview_view.rs:628-647). The
 * other direction is a tap: a block in the preview puts the caret on the line
 * it was read from. Typing never moves the preview — only scrolling does — so
 * the two panes still do not fight over the reader's position.
 *
 * Sync is a toolbar toggle and a setting (`markdown_preview.scroll_sync`).
 * Zed's equivalent is a whole second item, `markdown::OpenFollowingPreview`;
 * with one preview panel a toggle is the same idea with less furniture.
 *
 * @param path the file's project-relative path — the header's title, and what
 *   a relative link is resolved against.
 * @param onOpenPath asked to open a project-relative path when a relative link
 *   is followed. Null leaves such links inert.
 * @param projectRoot the project's absolute root, so a `![](docs/x.png)` can
 *   be drawn. Null keeps every image as its alt text.
 * @param onJumpToSource asked for the 0-based line a tapped block came from.
 */
@Composable
fun MarkdownPreview(
    editor: EditorState,
    path: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPath: ((String) -> Unit)? = null,
    projectRoot: String? = null,
    onJumpToSource: ((Int) -> Unit)? = null,
) {
    val theme = LocalZedTheme.current
    val style = previewStyle()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    val isMarkdown = PreviewKind.of(path) == PreviewKind.Markdown
    val settings = LocalAppSettings.current

    // The setting seeds the toggle and every later change to it wins: someone
    // who turns sync off in settings.json while the panel is open means it.
    var followEditor by remember { mutableStateOf(settings.markdownPreview.scrollSync) }
    LaunchedEffect(settings.markdownPreview.scrollSync) {
        followEditor = settings.markdownPreview.scrollSync
    }
    /** An outside URL waiting for the reader to say yes. See [ExternalLinkDialog]. */
    var pendingLink by remember { mutableStateOf<String?>(null) }

    // Two things move the text and only one of them is visible to composition.
    // See PREVIEW_VERSION_POLL_MS.
    var engineVersion by remember(editor) { mutableLongStateOf(-1L) }
    LaunchedEffect(editor) {
        while (true) {
            engineVersion = editor.session.version
            delay(PREVIEW_VERSION_POLL_MS)
        }
    }

    var document by remember(editor) { mutableStateOf(PreviewDocument.EMPTY) }
    LaunchedEffect(editor, isMarkdown, editor.revision, engineVersion) {
        if (!isMarkdown) {
            document = PreviewDocument.EMPTY
            return@LaunchedEffect
        }
        delay(PREVIEW_REPARSE_DEBOUNCE_MS)
        // All of it off the main thread: reading past the drawn window is a
        // JNI call that takes the engine's buffer mutex, and highlighting a
        // fence is a tree-sitter parse behind that same mutex.
        document = withContext(Dispatchers.Default) {
            val source = cappedSource(editor.lineCount) { first, last ->
                editor.linesOf(first, last)
            }
            if (source == null) PreviewDocument.TOO_LARGE else PreviewDocument.of(source)
        }
    }

    // Editor to preview. `snapshotFlow` over the top *buffer* row, so this
    // fires on a scroll and on nothing else — a caret move or a keystroke
    // must not drag the reader's preview around. `collectLatest` with a
    // settle delay, so a fling is one scroll at the end rather than one per
    // frame; the scroll itself is instant, because animating to a target the
    // finger has already moved past is a preview that never catches up.
    LaunchedEffect(editor, followEditor) {
        if (!followEditor) return@LaunchedEffect
        snapshotFlow { editor.topVisibleRow() }
            .distinctUntilChanged()
            .collectLatest { row ->
                delay(SYNC_SETTLE_MS)
                // Read here, not as an effect key: a reparse lands on every
                // keystroke, and restarting the flow would re-emit the current
                // row and drag the preview about while somebody types.
                val parsed = document
                val block = blockAtSourceLine(parsed.lineRanges, row)
                if (block in parsed.blocks.indices) listState.scrollToItem(block)
            }
    }

    // The highlighter's cache is keyed by whole fence texts and lives as long
    // as the process does; a preview that has been closed has no claim on it.
    DisposableEffect(Unit) {
        onDispose { CodeFenceHighlighter.clear() }
    }

    /** What a tap on a link means. */
    fun follow(target: String) {
        when {
            target.startsWith("http://", ignoreCase = true) ||
                target.startsWith("https://", ignoreCase = true) ||
                target.startsWith("mailto:", ignoreCase = true) ->
                // Asked first. A README is a document from somewhere else, its
                // links are its author's, and leaving the app for one of them
                // is a decision the reader gets to make — with the address in
                // front of them, which is the only way a lookalike domain is
                // ever caught. Zed's own preview shows the URL on hover; a
                // finger has no hover, so it is a sheet.
                pendingLink = target
            // A `#anchor`: the heading it names, if the document has one.
            target.startsWith("#") ->
                anchorBlockIndex(document.blocks, target)?.let { block ->
                    scope.launch { listState.revealItem(block) }
                }
            else -> relativeLinkTarget(path, target)?.let { onOpenPath?.invoke(it) }
        }
    }

    fun scrollBy(pages: Float) {
        scope.launch {
            val viewport = listState.layoutInfo.viewportSize.height.toFloat()
            listState.revealBy(viewport * pages)
        }
    }

    WithMarkdownImages(remember(projectRoot, path) { MarkdownImages(projectRoot, path) }) {
        Box(modifier = modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(theme.color("editor.background"))
                        .focusRequester(focus)
                        .focusable()
                        // Focus is taken on a press rather than requested when the
                        // panel appears: opening the preview must not pull the
                        // keyboard out of the editor, since following the buffer while
                        // it is typed in is the whole point. The down is watched in the
                        // initial pass and left unconsumed, so the list still scrolls.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                focus.requestFocus()
                            }
                        }
                        // Zed's own markdown keymap: pageup/pagedown, up/down and
                        // ctrl-home/ctrl-end scroll the preview
                        // (assets/keymaps/default-linux.json:1343-1350).
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                event.key == Key.Escape && !event.isCtrlPressed -> {
                                    onDismiss()
                                    true
                                }
                                event.isCtrlPressed && event.key == Key.MoveHome -> {
                                    scope.launch { listState.revealItem(0) }
                                    true
                                }
                                event.isCtrlPressed && event.key == Key.MoveEnd -> {
                                    scope.launch {
                                        listState.revealItem(
                                            (document.blocks.size - 1).coerceAtLeast(0)
                                        )
                                    }
                                    true
                                }
                                event.isCtrlPressed -> false
                                event.key == Key.PageDown -> {
                                    scrollBy(1f)
                                    true
                                }
                                event.key == Key.PageUp -> {
                                    scrollBy(-1f)
                                    true
                                }
                                event.key == Key.DirectionDown -> {
                                    scrollBy(0.12f)
                                    true
                                }
                                event.key == Key.DirectionUp -> {
                                    scrollBy(-0.12f)
                                    true
                                }
                                else -> false
                            }
                        }
                ) {
                    PreviewHeader(
                        path = path,
                        onDismiss = onDismiss,
                        actions = {
                            if (isMarkdown && onJumpToSource != null) {
                                HeaderToggle(
                                    label = "⇅",
                                    description = if (followEditor) {
                                        "Stop following the editor"
                                    } else {
                                        "Follow the editor's scroll"
                                    },
                                    on = followEditor,
                                    onClick = { followEditor = !followEditor },
                                )
                            }
                        },
                    )
                    // The toolbar's underline is `border.variant`, not `border`
                    // (workspace/src/toolbar.rs:128-129).
                    HorizontalDivider(color = theme.color("border.variant"))
                    when {
                        !isMarkdown -> Notice(
                            "Markdown preview shows a .md file. " +
                                "Open one and it appears here.",
                            style,
                        )
                        // Refused rather than attempted: see MAX_PREVIEW_CHARS.
                        // The editor still has the file, which is the point of
                        // saying so instead of dying.
                        document.isTooLarge -> Notice(
                            "This file is too large to preview. " +
                                "It is still open in the editor.",
                            style,
                        )
                        else -> LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // Zed's scroll container is `p_4` — 16px on all four
                            // sides (markdown_preview_view.rs:1673) — on
                            // `editor.background` (markdown_preview_view.rs:1634).
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            // No key: the block list is rebuilt wholesale on every
                            // edit, and a content-derived key would make every item
                            // "new" and throw the scroll position to the top on each
                            // keystroke. Index keys keep the reader where they were.
                            itemsIndexed(document.blocks) { index, block ->
                                val jump = onJumpToSource
                                Box(
                                    modifier = if (jump == null) {
                                        Modifier
                                    } else {
                                        // No indication: a whole paragraph flashing
                                        // grey on every tap of a link inside it is
                                        // noise. A link consumes its own tap, so
                                        // this only fires on the block's own space.
                                        Modifier.clickable(
                                            interactionSource = null,
                                            indication = null,
                                            onClickLabel = "Go to this line in the editor",
                                        ) { jump(sourceLineOfBlock(document.lineRanges, index)) }
                                    }
                                ) {
                                    BlockView(block, document, style, ::follow)
                                }
                            }
                        }
                    }
                }
            }
            pendingLink?.let { target ->
                ExternalLinkDialog(
                    url = target,
                    onDismiss = { pendingLink = null },
                    onConfirm = {
                        pendingLink = null
                        // The browser may be absent on a stripped image; a preview
                        // must not take the app down over a tapped badge.
                        runCatching { uriHandler.openUri(target) }
                    },
                )
            }
        }
    }
}

/**
 * "Open this in the browser?", with the address in full.
 *
 * The address is shown rather than summarised on purpose: `[our docs](…)`
 * pointing somewhere else entirely is the oldest trick there is, and the only
 * defence is showing the reader where they are actually going before they go.
 */
@Composable
private fun ExternalLinkDialog(url: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val theme = LocalZedTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.color("elevated_surface.background", theme.color("surface.background")),
        title = {
            Text(
                text = "Leave the editor?",
                style = TextStyle(fontFamily = UiFontFamily, fontSize = 16.sp),
                color = theme.color("text"),
            )
        },
        text = {
            Text(
                text = url,
                style = TextStyle(fontFamily = BufferFontFamily, fontSize = 13.sp),
                color = theme.color("text.muted"),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Open", color = theme.color("text.accent"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = theme.color("text.muted"))
            }
        },
    )
}

/** The panel with something to say and nothing to draw. */
@Composable
private fun Notice(text: String, style: PreviewStyle) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style.body,
            color = style.theme.color("text.muted"),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The title row: which file is being previewed, and the way out.
 *
 * The ✕ is what a finger closes the preview with — on a phone the panel owns
 * the whole work area and there is no editor to press Ctrl+Shift+V into.
 */
@Composable
internal fun PreviewHeader(
    path: String,
    onDismiss: () -> Unit,
    /** A word about what is being shown — a table's row and column count. */
    detail: String? = null,
    /** Controls that sit before the ✕, in the order they are written. */
    actions: @Composable RowScope.() -> Unit = {},
) {
    val theme = LocalZedTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(theme.color("toolbar.background"))
            .padding(horizontal = HeaderHorizontalPad, vertical = HeaderVerticalPad)
            .height(HeaderRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Named, not just shown: on a phone the preview covers the editor
        // entirely, and "README.md" on its own does not say which of the two
        // views of that file you are looking at. `text_ui` 14px, as the
        // toolbar's breadcrumbs are (breadcrumbs/src/breadcrumbs.rs:53-55).
        Text(
            text = "Preview · ${path.substringAfterLast('/')}",
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 14.sp),
            color = theme.color("text"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // The title takes the slack, so the detail and the controls stay
            // together at the right whatever the file is called.
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(
                text = detail,
                style = TextStyle(fontFamily = UiFontFamily, fontSize = 12.sp),
                color = theme.color("text.muted"),
                maxLines = 1,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        actions()
        val closeInteraction = remember { MutableInteractionSource() }
        val closeHovered by closeInteraction.collectIsHoveredAsState()
        val closePressed by closeInteraction.collectIsPressedAsState()
        Box(
            modifier = Modifier
                .size(ControlBox)
                .clip(RoundedCornerShape(4.dp))
                // A ghost button: transparent at rest, `ghost_element.hover`
                // under the pointer, `ghost_element.active` while pressed,
                // swapped instantly — no ripple (button_like.rs:298-303,
                // 324-329).
                .background(
                    when {
                        closePressed -> theme.color("ghost_element.active", Color.Transparent)
                        closeHovered -> theme.color("ghost_element.hover", Color.Transparent)
                        else -> Color.Transparent
                    }
                )
                .clickable(
                    interactionSource = closeInteraction,
                    indication = null,
                    onClickLabel = "Close the preview",
                    onClick = onDismiss,
                )
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                style = TextStyle(fontFamily = UiFontFamily, fontSize = 14.sp),
                color = theme.color("icon"),
            )
        }
    }
}

/**
 * A header control that is either on or off — the preview's scroll-sync
 * toggle, wearing the same ghost frame the ✕ does so the two sit level.
 */
@Composable
private fun HeaderToggle(
    label: String,
    description: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ControlBox)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    on -> theme.color("ghost_element.selected", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                onClick = onClick,
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 14.sp),
            color = if (on) theme.color("text.accent") else theme.color("icon"),
        )
    }
}

/** The dock's left edge: the border, and the grip that drags it wider. */
@Composable
internal fun PreviewResizeHandle(onDrag: (Dp) -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        modifier = Modifier
            .width(HandleWidth)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, delta -> onDrag(delta.toDp()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(color = theme.color("border"))
    }
}

// ---- The document, parsed and highlighted off the main thread --------------

/**
 * A parsed document together with the syntax spans of its fenced code.
 *
 * The spans are computed here rather than in the renderer because getting them
 * means an engine parse per fence, and the renderer runs on the main thread.
 */
internal class PreviewDocument private constructor(
    val blocks: List<MarkdownBlock>,
    private val code: Map<Pair<String?, String>, List<List<HighlightSpan>>>,
    /** True when the file was refused for its size. See [MAX_PREVIEW_CHARS]. */
    val isTooLarge: Boolean = false,
    /** Index-aligned with [blocks] — see [ParsedMarkdown]. Empty for a string. */
    val lineRanges: List<IntRange> = emptyList(),
) {
    fun spansFor(block: MarkdownBlock.Code): List<List<HighlightSpan>>? =
        code[block.language to block.code]

    companion object {
        val EMPTY = PreviewDocument(emptyList(), emptyMap())
        val TOO_LARGE = PreviewDocument(emptyList(), emptyMap(), isTooLarge = true)

        /**
         * Parses [source] and highlights its fences. **Blocks the thread it is
         * called on** — every fence is a tree-sitter parse behind the engine's
         * buffer mutex — so it belongs on a worker.
         *
         * Suspending for the sake of the [yield]s: `parseMarkdown` has no
         * suspension point of its own, so without them cancelling the effect
         * cancelled nothing and a second parse could run beside the first.
         */
        suspend fun of(source: String): PreviewDocument {
            if (source.length > MAX_PREVIEW_CHARS) return TOO_LARGE
            val parsed = parseMarkdownDocument(source)
            val code = mutableMapOf<Pair<String?, String>, List<List<HighlightSpan>>>()
            for (block in codeBlocksIn(parsed.blocks)) {
                yield()
                // A ```mermaid fence is a drawing, not code: highlighting it
                // would be a tree-sitter parse of a language nothing has.
                if (block.language.equals(MERMAID_LANGUAGE, ignoreCase = true)) continue
                val key = block.language to block.code
                if (key in code) continue
                CodeFenceHighlighter.highlight(block.code, block.language)
                    ?.let { code[key] = it }
            }
            return PreviewDocument(parsed.blocks, code, lineRanges = parsed.lineRanges)
        }

        /** Every fence in the document, quotes and list items included. */
        private fun codeBlocksIn(blocks: List<MarkdownBlock>): List<MarkdownBlock.Code> =
            blocks.flatMap { block ->
                when (block) {
                    is MarkdownBlock.Code -> listOf(block)
                    is MarkdownBlock.Quote -> codeBlocksIn(block.blocks)
                    is MarkdownBlock.Bullets -> block.items.flatMap { codeBlocksIn(it.blocks) }
                    else -> emptyList()
                }
            }
    }
}

// ---- Styling ---------------------------------------------------------------

/**
 * Everything the blocks below draw with, resolved once per composition.
 *
 * The numbers are Zed's `MarkdownStyle::themed(MarkdownFont::Preview, …)`
 * (crates/markdown/src/markdown.rs:174-296): the body is the UI font at 1rem
 * with a 1.75 line height, code is the buffer font at the buffer font size,
 * inline code sits on `editor.foreground` at 8% and a link is `text.accent`
 * underlined at half strength.
 */
internal class PreviewStyle(
    val theme: ZedTheme,
    val body: TextStyle,
    val code: TextStyle,
    val rem: Float,
    val linkStyles: TextLinkStyles,
    val inlineCodeBackground: Color,
)

@Composable
private fun previewStyle(): PreviewStyle {
    val theme = LocalZedTheme.current
    val rem = LocalUiFontSize.current
    val settings = LocalAppSettings.current
    val accent = theme.color("text.accent")
    val uiFont = UiFontFamily
    val bufferFont = BufferFontFamily
    return remember(theme, rem, settings.bufferFontSize, uiFont, bufferFont) {
        PreviewStyle(
            theme = theme,
            body = TextStyle(
                fontFamily = uiFont,
                fontSize = rem.sp,
                lineHeight = (rem * 1.75f).sp,
                color = theme.color("text"),
            ),
            code = TextStyle(
                fontFamily = bufferFont,
                fontSize = settings.bufferFontSize.sp,
                lineHeight = (settings.bufferFontSize * 1.4f).sp,
                color = theme.color("editor.foreground"),
            ),
            rem = rem,
            linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = accent,
                    textDecoration = TextDecoration.Underline,
                ),
                hoveredStyle = SpanStyle(
                    color = accent,
                    background = accent.copy(alpha = 0.12f),
                    textDecoration = TextDecoration.Underline,
                ),
                pressedStyle = SpanStyle(
                    color = accent,
                    background = accent.copy(alpha = 0.24f),
                    textDecoration = TextDecoration.Underline,
                ),
            ),
            inlineCodeBackground = theme.color("editor.foreground").copy(alpha = 0.08f),
        )
    }
}

/**
 * A markdown *string*, rendered the way the file preview renders a document.
 *
 * The preview proper is buffer-bound — it follows an [EditorState] and polls
 * the engine's version — but the agent panel's messages are markdown that
 * never becomes a file, so this is the same renderer over a plain string.
 * Zed does exactly this: an agent's reply is a `Markdown` element with the
 * same style as everything else (agent_ui renders `Entity<Markdown>` per
 * chunk), which is why an agent's tables and fences look like the editor's.
 *
 * [source] may change on every frame of a streaming reply, and parsing it is
 * not free — each fence is a tree-sitter parse behind the engine's buffer
 * mutex — so the work is debounced and done off the main thread, and the last
 * good render stays on screen meanwhile. That is the same trade the preview
 * makes, for the same reason.
 */
@Composable
internal fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    onLink: (String) -> Unit = {},
) {
    val style = previewStyle()
    var document by remember { mutableStateOf(PreviewDocument.EMPTY) }
    var lastParsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(source) {
        // Throttled, **not** debounced, and the difference is the whole
        // feature. A plain `delay(DEBOUNCE)` at the top of an effect keyed on
        // the text never fires at all while the text keeps changing faster
        // than the delay — and a streaming reply changes every 120 ms against
        // a 180 ms debounce, so the panel would have shown an empty bubble
        // until the agent stopped talking. Waiting only for the *remainder*
        // since the last parse bounds how stale the view can be instead of
        // starving it.
        val wait = parseDelay(SystemClock.uptimeMillis() - lastParsed)
        if (wait > 0) delay(wait)
        document = withContext(Dispatchers.Default) { PreviewDocument.of(source) }
        lastParsed = SystemClock.uptimeMillis()
    }
    Column(modifier = modifier) {
        for (block in document.blocks) {
            BlockView(block, document, style, onLink)
        }
    }
}

/**
 * How long to wait before re-parsing, given how long ago the last parse was.
 *
 * Pure so the policy can be tested: the property that matters is that it is
 * bounded above by the interval — text arriving forever cannot postpone a
 * parse forever — and that a first parse, or one after a quiet spell, is
 * immediate.
 */
internal fun parseDelay(sinceLastParse: Long, interval: Long = PREVIEW_REPARSE_DEBOUNCE_MS): Long =
    when {
        // A clock that went backwards (or a first parse) is not a reason to
        // stall; parse now.
        sinceLastParse < 0 -> 0
        sinceLastParse >= interval -> 0
        else -> interval - sinceLastParse
    }

/**
 * Tailwind's scale, which is what Zed's `text_3xl`…`text_sm` resolve to
 * (crates/markdown/src/markdown.rs:3240-3247). Multiples of the body size, so
 * the whole document follows `ui_font_size` the way Zed's does.
 */
private fun headingScale(level: Int): Float = when (level) {
    1 -> 1.875f
    2 -> 1.5f
    3 -> 1.25f
    4 -> 1.125f
    5 -> 1f
    else -> 0.875f
}

// ---- Blocks ----------------------------------------------------------------

@Composable
private fun BlockView(
    block: MarkdownBlock,
    document: PreviewDocument,
    style: PreviewStyle,
    onLink: (String) -> Unit,
) {
    when (block) {
        is MarkdownBlock.Heading -> HeadingView(block, style, onLink)
        is MarkdownBlock.Paragraph -> ParagraphView(block.content, style, onLink)
        // Zed renders a ```mermaid fence as a diagram (`crates/mermaid_render`);
        // so does this, for the subset in [MermaidDiagram].
        is MarkdownBlock.Code ->
            if (block.language.equals(MERMAID_LANGUAGE, ignoreCase = true)) {
                MermaidView(block.code, style)
            } else {
                CodeView(block, document, style)
            }
        is MarkdownBlock.Math -> MathView(block, style)
        is MarkdownBlock.Quote -> QuoteView(block, document, style, onLink)
        is MarkdownBlock.Bullets -> BulletsView(block, document, style, onLink)
        MarkdownBlock.Rule -> HorizontalDivider(
            color = style.theme.color("border"),
            modifier = Modifier.padding(vertical = 12.dp),
        )
        is MarkdownBlock.Table -> TableView(block, style, onLink)
    }
}

/** The fence info word that means "draw this", not "colour this". */
internal const val MERMAID_LANGUAGE = "mermaid"

/**
 * A paragraph: one wrapping string, except where it holds a picture.
 *
 * See [inlineSegments] for why it is cut up at all. An image whose source
 * cannot be drawn never leaves the string, so ordinary prose is still exactly
 * one `Text` and wraps as one.
 */
@Composable
private fun ParagraphView(
    content: List<InlineSpan>,
    style: PreviewStyle,
    onLink: (String) -> Unit,
) {
    val images = LocalMarkdownImages.current
    val segments = remember(content, images) {
        inlineSegments(content) { span ->
            images?.projectRoot != null && isDrawableImageSource(span.imageSource)
        }
    }
    if (segments.size == 1 && segments.first() is InlineSegment.Prose) {
        Text(
            text = buildInline(content, style, onLink),
            style = style.body,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        for (segment in segments) {
            when (segment) {
                is InlineSegment.Prose -> Text(
                    text = buildInline(segment.spans, style, onLink),
                    style = style.body,
                )
                // A badge row stays a row and scrolls if there are more badges
                // than there is width; stacking them would turn a one-line
                // status strip into a column six items tall.
                is InlineSegment.Images -> Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (span in segment.spans) MarkdownImageView(span, style, onLink)
                }
            }
        }
    }
}

/**
 * A `$$…$$` block: the TeX, set in the buffer font and centred.
 *
 * Not typeset — see [InlineStyle.Math]. Centring and the frame are what say
 * "this is a formula" without claiming to have rendered one.
 */
@Composable
private fun MathView(block: MarkdownBlock.Math, style: PreviewStyle) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(style.inlineCodeBackground)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = block.source, style = style.code, softWrap = false)
    }
}

@Composable
private fun HeadingView(
    heading: MarkdownBlock.Heading,
    style: PreviewStyle,
    onLink: (String) -> Unit,
) {
    // Zed: everything but H1 gets `mt_6`, and H1–H3 get `pb_1` and a rule
    // under them (markdown.rs:3249-3262).
    val underlined = heading.level <= 3
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (heading.level == 1) 0.dp else 24.dp, bottom = 8.dp)
    ) {
        Text(
            text = buildInline(heading.content, style, onLink),
            style = style.body.copy(
                fontSize = (style.rem * headingScale(heading.level)).sp,
                lineHeight = 1.3.em,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(bottom = if (underlined) 4.dp else 0.dp),
        )
        if (underlined) HorizontalDivider(color = style.theme.color("border.variant"))
    }
}

@Composable
private fun CodeView(
    block: MarkdownBlock.Code,
    document: PreviewDocument,
    style: PreviewStyle,
) {
    val spans = document.spansFor(block)
    val text = remember(block, spans, style) { annotateCode(block.code, spans, style.theme) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(style.theme.color("editor.background"))
            .border(1.dp, style.theme.color("border.variant"), RoundedCornerShape(4.dp))
            // Zed sets `code_block_overflow_x_scroll`: code is not prose and
            // wrapping it silently changes what it says.
            .horizontalScroll(rememberScrollState())
            .padding(8.dp)
    ) {
        Text(text = text, style = style.code, softWrap = false)
    }
}

@Composable
private fun QuoteView(
    quote: MarkdownBlock.Quote,
    document: PreviewDocument,
    style: PreviewStyle,
    onLink: (String) -> Unit,
) {
    val fallback = style.theme.color("border")
    val accent = when (quote.kind) {
        "NOTE", "IMPORTANT" -> style.theme.color("info", fallback)
        "TIP" -> style.theme.color("success", fallback)
        "WARNING" -> style.theme.color("warning", fallback)
        "CAUTION" -> style.theme.color("error", fallback)
        else -> fallback
    }
    // The bar is painted behind the column rather than laid out beside it: a
    // sibling would need `fillMaxHeight` inside a wrap-content Row, and the
    // intrinsic measurement that makes that legal is not something every child
    // here supports — a fenced code block inside a quote is a horizontal
    // scroller, and asking it for an intrinsic height is asking for a crash.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .drawBehind { drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp)
    ) {
        if (quote.kind != null) {
            Text(
                text = quote.kind.lowercase().replaceFirstChar { it.uppercase() },
                style = style.body.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        for (child in quote.blocks) BlockView(child, document, style, onLink)
    }
}

@Composable
private fun BulletsView(
    list: MarkdownBlock.Bullets,
    document: PreviewDocument,
    style: PreviewStyle,
    onLink: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (list.tight) 0.dp else 6.dp),
    ) {
        for (item in list.items) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when (item.checked) {
                        true -> "☑"
                        false -> "☐"
                        null -> item.marker
                    },
                    style = style.body,
                    color = style.theme.color("text.muted"),
                    textAlign = if (list.ordered) TextAlign.End else TextAlign.Start,
                    // One line: a marker that wrapped would push its own
                    // item's first line down.
                    //
                    // **The width is a minimum, not a width**, and only an
                    // ordered list gets one. A fixed 30dp column was there so
                    // `100.` would line up, but it applied to `•` as well, and
                    // a bullet followed by nearly forty points of nothing was
                    // what the agent panel's lists looked like: an empty
                    // corridor down the left of every reply. Zed sizes the
                    // marker to itself and leaves one gap (markdown.rs:
                    // 2005-2017, `h_flex().items_start()` with `gap_1`), which
                    // is what an unordered list gets here now; the numbers
                    // keep a minimum so they still align with each other.
                    maxLines = 1,
                    modifier = if (list.ordered) Modifier.widthIn(min = 24.dp) else Modifier,
                )
                Column(modifier = Modifier.padding(start = 6.dp).weight(1f)) {
                    for (child in item.blocks) BlockView(child, document, style, onLink)
                }
            }
        }
    }
}

@Composable
private fun TableView(
    table: MarkdownBlock.Table,
    style: PreviewStyle,
    onLink: (String) -> Unit,
) {
    val border = style.theme.color("border.variant")
    val columns = table.columnCount()
    if (columns == 0) return
    // Equal-weight columns that wrap, rather than Zed's measured widths inside
    // a horizontal scroller: a three-column table in a 280dp dock is otherwise
    // a scrollbar with a table hiding behind it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(1.dp)
    ) {
        TableRowView(table.header, table.alignments, columns, bold = true, style, onLink)
        HorizontalDivider(color = border)
        table.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = border)
            TableRowView(row, table.alignments, columns, bold = false, style, onLink)
        }
    }
}

@Composable
private fun TableRowView(
    row: List<List<InlineSpan>>,
    alignments: List<ColumnAlignment>,
    columns: Int,
    bold: Boolean,
    style: PreviewStyle,
    onLink: (String) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        for (column in 0 until columns) {
            Text(
                text = buildInline(row.getOrNull(column).orEmpty(), style, onLink),
                style = if (bold) style.body.copy(fontWeight = FontWeight.SemiBold) else style.body,
                textAlign = when (alignments.getOrNull(column)) {
                    ColumnAlignment.Center -> TextAlign.Center
                    ColumnAlignment.End -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

// ---- Inline ---------------------------------------------------------------

/** One block's inline runs as a styled, clickable string. */
private fun buildInline(
    spans: List<InlineSpan>,
    style: PreviewStyle,
    onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val text = if (span.isImage) {
            // No fetch, and no pretending: an image is named, not drawn. A
            // network request from a preview of a file the user may have just
            // cloned is not something this app makes on their behalf.
            if (span.text.isBlank()) "[image]" else "[image: ${span.text}]"
        } else {
            span.text
        }
        if (text.isEmpty()) continue
        val appearance = SpanStyle(
            fontWeight = if (InlineStyle.Bold in span.styles) FontWeight.SemiBold else null,
            fontStyle = if (
                InlineStyle.Italic in span.styles || InlineStyle.Math in span.styles
            ) {
                FontStyle.Italic
            } else {
                null
            },
            textDecoration = if (InlineStyle.Strikethrough in span.styles) {
                TextDecoration.LineThrough
            } else {
                null
            },
            // The buffer font is a setting now, so code *and* math take it
            // from the resolved style rather than the shipped family.
            fontFamily = if (
                InlineStyle.Code in span.styles || InlineStyle.Math in span.styles
            ) {
                style.code.fontFamily
            } else {
                null
            },
            fontSize = if (
                InlineStyle.Code in span.styles || InlineStyle.Math in span.styles
            ) {
                style.code.fontSize
            } else {
                TextUnit.Unspecified
            },
            // Math is set apart by its face, not by a chip: `$a$` inside a
            // sentence reads as a symbol, and a code-span background around
            // every one of them makes a paragraph of physics unreadable.
            background = when {
                InlineStyle.Code in span.styles -> style.inlineCodeBackground
                else -> Color.Unspecified
            },

            color = if (span.isImage) style.theme.color("text.muted") else Color.Unspecified,
        )
        val destination = span.link
        if (destination == null) {
            withStyle(appearance) { append(text) }
        } else {
            withLink(
                LinkAnnotation.Clickable(
                    tag = destination,
                    styles = style.linkStyles,
                    linkInteractionListener = LinkInteractionListener { onLink(destination) },
                )
            ) {
                withStyle(appearance) { append(text) }
            }
        }
    }
}

/** A fenced block's text, coloured with the engine's spans where we have them. */
private fun annotateCode(
    code: String,
    spans: List<List<HighlightSpan>>?,
    theme: ZedTheme,
): AnnotatedString {
    if (spans == null) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)
        var offset = 0
        for ((row, line) in code.split('\n').withIndex()) {
            for (span in spans.getOrElse(row) { emptyList() }) {
                val start = offset + span.start.coerceIn(0, line.length)
                val end = offset + span.end.coerceIn(0, line.length)
                if (start >= end) continue
                theme.spanStyle(span.style)?.let { addStyle(it, start, end) }
            }
            offset += line.length + 1
        }
    }
}

