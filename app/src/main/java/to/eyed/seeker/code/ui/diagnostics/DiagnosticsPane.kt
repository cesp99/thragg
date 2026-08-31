package to.eyed.seeker.code.ui.diagnostics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.core.ProjectSession
import to.eyed.seeker.code.ui.editor.Diagnostic
import to.eyed.seeker.code.ui.editor.DiagnosticSeverity
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.editor.FileDiagnosticRows
import to.eyed.seeker.code.ui.editor.ProjectDiagnosticRows
import to.eyed.seeker.code.ui.editor.rememberProjectDiagnostics
import to.eyed.seeker.code.ui.theme.BufferFontFamily
import to.eyed.seeker.code.ui.theme.DisclosureMark
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.workspace.EntryIconMark

/**
 * The toolbar chrome, the header card and the gutter, all with the search
 * panel's numbers — the two lists have to read as siblings, and those numbers
 * are Zed's own (see ProjectSearchPanel.kt for the derivations).
 */
private val ToolbarVPad = 6.dp
private val ToolbarHPad = 8.dp
private val ButtonBox = 20.dp
private val HeaderPadding = 4.dp
private val HeaderCardMinHeight = 40.dp
private val LineNumberWidth = 30.dp
private val LineNumberDigitWidth = 7.5.dp
private val SeverityIconSize = 14.dp

private fun lineNumberWidth(line: Int): Dp {
    val digits = line.toString().length
    return if (digits <= 4) LineNumberWidth else LineNumberWidth + LineNumberDigitWidth * (digits - 4)
}

/** How far PageUp and PageDown move the selection. */
private const val PAGE_ROWS = 10

/**
 * Whether warnings were showing when the tab was last closed. Session-lived
 * on purpose, like the search panel's remembered query: Zed keeps
 * `include_warnings` per workspace, and this is that in the small.
 */
private object LastDiagnostics {
    var includeWarnings: Boolean = true
}

/**
 * Put the caret on a diagnostic, in the buffer it belongs to.
 *
 * The panel cannot do this itself — it never opens a file — so the workspace
 * opens the file and hands the buffer here, exactly as a search hit is
 * revealed. Suspending for `revealProjectSearchMatch`'s reason: a freshly
 * opened pane has no viewport until it has drawn once, so the scroll waits
 * two frames and asks again.
 */
suspend fun EditorState.revealDiagnosticTarget(target: Diagnostic) {
    goToDiagnosticTarget(target)
    withFrameNanos { }
    withFrameNanos { }
    ensureCursorVisible()
}

/**
 * Every problem in the project, grouped by file — Zed's project diagnostics
 * (crates/diagnostics), which there is an editor of excerpts inside a pane.
 *
 * A tab in the work area, exactly where Zed deploys it: `diagnostics::Deploy`
 * puts the diagnostics editor into the active pane as an item, beside the
 * files, and the git graph made the same translation here first. What this
 * costs is Zed's editable excerpts — the rows are a list, not buffers — and
 * its way in is the status bar's summary, which is the button Zed gives its
 * diagnostics editor too.
 *
 * The list is project-wide and outlives tabs: rows come from
 * [rememberProjectDiagnostics], which reads what servers have published for
 * the whole workspace, not what happens to be open.
 */
@Composable
fun DiagnosticsPane(
    project: ProjectSession,
    /** Bumped by the workspace to put the keyboard back in the pane. */
    focusToken: Int,
    onOpenDiagnostic: (path: String, diagnostic: Diagnostic) -> Unit,
    /**
     * Open every problem as an editable multibuffer — which is what Zed's
     * project diagnostics *are* (crates/diagnostics builds one excerpt per
     * group). Null leaves the pane the read-only list it has been.
     */
    onOpenMultibuffer: ((List<FileDiagnosticRows>) -> Unit)? = null,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The rows to draw, when the host has a source of its own — the Problems
     * route, whose list is the engine's *merged with the last build's*
     * (solana/build/BuildDiagnostics.kt). Null keeps the pane's own poll of
     * the engine, which is what the workspace tab has always used.
     *
     * The merge cannot happen inside this pane: the engine's diagnostics store
     * is read-only across JNI, so cargo's rows live beside it in Kotlin and
     * only a consumer can put the two together (docs/UI.md, P4).
     */
    rows: ProjectDiagnosticRows? = null,
    /**
     * Whether the pane draws its own toolbar — counts, the warnings toggle and
     * ✕.
     *
     * False for a host that carries those itself: the Problems route has a ←
     * of its own, its counts in its header and an all/errors/warnings filter
     * that is finer than this toggle. Two rows of chrome over a list on a
     * 400dp screen is one row too many, and the filter would then exist twice
     * with two different answers.
     */
    showToolbar: Boolean = true,
) {
    val theme = LocalZedTheme.current
    val polled = rememberProjectDiagnostics(project.id.takeIf { rows == null })
    val state = rows ?: polled
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    // With the toolbar gone the toggle has no switch, so nothing may be hidden
    // behind it: the host filtered [rows] before handing them over and this
    // pane draws what it was given.
    var paneIncludeWarnings by remember { mutableStateOf(LastDiagnostics.includeWarnings) }
    val includeWarnings = if (showToolbar) paneIncludeWarnings else true
    var selected by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    // Guarded: the pane used to be raised into a workspace that was already
    // laid out, and is now also composed *with* the Problems route on its
    // first frame — where the node may not be placed yet and `requestFocus`
    // throws rather than returning false. Nothing on this surface depends on
    // holding focus; the arrows are for a keyboard the phone does not have.
    LaunchedEffect(focusToken) { runCatching { focus.requestFocus() } }

    val rows = remember(state, collapsed, includeWarnings) {
        diagnosticsRows(state.files, collapsed, includeWarnings)
    }
    /** The files the multibuffer would cover — what is listed, warnings and all. */
    val listed = remember(state, includeWarnings) {
        state.files.mapNotNull { file ->
            val kept = if (includeWarnings) {
                file.rows
            } else {
                file.rows.filter { it.severity == DiagnosticSeverity.Error }
            }
            if (kept.isEmpty()) null else FileDiagnosticRows(file.path, kept)
        }
    }
    val errors = rows.filterIsInstance<DiagnosticsRow.FileRow>().sumOf { it.errors }
    val warnings = rows.filterIsInstance<DiagnosticsRow.FileRow>().sumOf { it.warnings }

    // Bumped only by the arrows — see the search panel for why a click must
    // not scroll the row it just hit.
    var scrollToSelected by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrollToSelected) {
        if (scrollToSelected > 0 && selected in rows.indices) {
            listState.revealItem(selected)
        }
    }

    fun move(delta: Int) {
        if (rows.isEmpty()) return
        selected = when {
            selected < 0 -> if (delta > 0) 0 else rows.lastIndex
            else -> (selected + delta).coerceIn(0, rows.lastIndex)
        }
        scrollToSelected++
    }

    fun toggle(path: String) {
        collapsed = if (path in collapsed) collapsed - path else collapsed + path
    }

    /** What Enter and a click both mean: a file folds, an issue opens. */
    fun activate(row: DiagnosticsRow) {
        when (row) {
            is DiagnosticsRow.FileRow -> toggle(row.path)
            is DiagnosticsRow.IssueRow -> onOpenDiagnostic(row.path, row.diagnostic)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.color("panel.background"))
            // The panel itself is the focus target the arrows talk to — it
            // has no text field to hold the caret for it.
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.isCtrlPressed) {
                    return@onPreviewKeyEvent false
                }
                // Zed's `editor::OpenExcerpts` chord (alt-enter): the whole
                // list as the multibuffer Zed's diagnostics editor already is.
                // The workspace's own Alt+Enter refuses outside a multibuffer,
                // so it reaches us.
                if (event.isAltPressed &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    val open = onOpenMultibuffer
                    if (open == null || listed.isEmpty()) return@onPreviewKeyEvent false
                    open(listed)
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionDown -> { move(1); true }
                    Key.DirectionUp -> { move(-1); true }
                    Key.PageDown -> { move(PAGE_ROWS); true }
                    Key.PageUp -> { move(-PAGE_ROWS); true }
                    Key.Enter, Key.NumPadEnter -> {
                        val row = rows.getOrNull(selected)
                        if (row == null) move(1) else activate(row)
                        true
                    }
                    Key.Escape -> { onDismiss(); true }
                    else -> false
                }
            },
    ) {
        // The toolbar: the summary Zed's diagnostics editor puts in its tab
        // and toolbar — counts on the left, the warnings toggle and close on
        // the right — on `toolbar.background` behind a 1px `border.variant`
        // underline, like every toolbar here.
        if (showToolbar) Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.color("toolbar.background"))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ToolbarHPad, vertical = ToolbarVPad),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (errors == 0 && warnings == 0) {
                    Text(
                        text = if (state.files.isEmpty()) {
                            "No problems"
                        } else {
                            "No errors"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.color("text.muted"),
                    )
                } else {
                    if (errors > 0) {
                        SeverityMark(
                            severity = DiagnosticSeverity.Error,
                            color = theme.color("error"),
                            label = "$errors errors",
                        )
                        Text(
                            text = errors.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (warnings > 0) {
                        SeverityMark(
                            severity = DiagnosticSeverity.Warning,
                            color = theme.color("warning"),
                            label = "$warnings warnings",
                        )
                        Text(
                            text = warnings.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f))
                // The touch twin of Alt+Enter: every problem in one editable
                // document, which is the surface Zed's diagnostics live in.
                if (onOpenMultibuffer != null && listed.isNotEmpty()) {
                    ToolbarToggle(
                        description = "Open the problems in an editable multibuffer",
                        selected = false,
                        onClick = { onOpenMultibuffer(listed) },
                    ) { color ->
                        Text(
                            // Stacked documents: many files, one editor.
                            text = "❐",
                            style = MaterialTheme.typography.labelMedium,
                            color = color,
                        )
                    }
                }
                // Zed's `ToggleWarnings`, as a toggle button wearing the
                // warning glyph: lit while warnings are listed.
                ToolbarToggle(
                    description = if (includeWarnings) "Hide warnings" else "Show warnings",
                    selected = includeWarnings,
                    onClick = {
                        paneIncludeWarnings = !includeWarnings
                        LastDiagnostics.includeWarnings = !includeWarnings
                        selected = -1
                    },
                ) { color ->
                    WarningGlyph(color = color)
                }
                ToolbarToggle(
                    description = "Close diagnostics",
                    selected = false,
                    onClick = onDismiss,
                ) { color ->
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(theme.color("border.variant"))
            )
        }
        // The rows live on `editor.background`, as Zed's diagnostics editor
        // does — it *is* an editor there.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(theme.color("editor.background")),
        ) {
            if (rows.isEmpty()) {
                Landing(
                    hasAny = state.files.isNotEmpty(),
                    color = theme.color("text.muted"),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        when (row) {
                            is DiagnosticsRow.FileRow -> FileHeaderRow(
                                row = row,
                                isSelected = index == selected,
                                onClick = {
                                    selected = index
                                    toggle(row.path)
                                },
                            )
                            is DiagnosticsRow.IssueRow -> IssueResultRow(
                                row = row,
                                isSelected = index == selected,
                                onClick = {
                                    selected = index
                                    onOpenDiagnostic(row.path, row.diagnostic)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Zed's "No problems in workspace" landing, and the errors-only variant. */
@Composable
private fun Landing(hasAny: Boolean, color: Color) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (hasAny) {
                "No errors in the workspace"
            } else {
                "No problems in the workspace"
            },
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * A file's header — the search panel's card, byte for byte in shape, with the
 * match count swapped for per-severity counts.
 */
@Composable
private fun FileHeaderRow(
    row: DiagnosticsRow.FileRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bufferStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = BufferFontFamily)
    Box(modifier = Modifier.fillMaxWidth().padding(HeaderPadding)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HeaderCardMinHeight)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (hovered) theme.color("element.hover")
                    else theme.color("editor.subheader.background")
                )
                .border(
                    1.dp,
                    theme.color(if (isSelected) "border.focused" else "border"),
                    RoundedCornerShape(4.dp),
                )
                .pointerHoverIcon(PointerIcon.Hand)
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Drawn, not typed. This caret is the one mark of this pane that
            // survives into the shell: the Problems route hosts the pane with
            // `showToolbar = false`, so the file rows are all of it that ships
            // on the phone, and `▸`/`▾` at labelSmall were the thin, small
            // marks the icon pass exists to remove.
            DisclosureMark(
                open = !row.isCollapsed,
                tint = theme.color("icon.muted", theme.color("text.muted")),
            )
            EntryIconMark(
                name = row.name,
                isDir = false,
                isExpanded = false,
                color = theme.color("icon.muted", theme.color("text.muted")),
            )
            Text(
                text = row.name,
                style = bufferStyle,
                color = theme.color("text"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.directory,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = BufferFontFamily),
                color = theme.color("text.muted"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.errors > 0) {
                SeverityMark(
                    severity = DiagnosticSeverity.Error,
                    color = theme.color("error"),
                    label = "${row.errors} errors",
                )
                Text(
                    text = row.errors.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                )
            }
            if (row.warnings > 0) {
                SeverityMark(
                    severity = DiagnosticSeverity.Warning,
                    color = theme.color("warning"),
                    label = "${row.warnings} warnings",
                )
                Text(
                    text = row.warnings.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.color("text.muted"),
                )
            }
        }
    }
}

/**
 * One diagnostic: its severity, its line where the gutter would put it, and
 * the message — which wraps, because "expected `&str`, found `String`" cut to
 * one line is a riddle. The severity colours the mark and the line number and
 * leaves the message in `editor.foreground`, the same division of labour the
 * status bar uses.
 */
@Composable
private fun IssueResultRow(
    row: DiagnosticsRow.IssueRow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val diagnostic = row.diagnostic
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        isSelected -> theme.color("ghost_element.selected")
        hovered -> theme.color("ghost_element.hover", Color.Transparent)
        else -> Color.Transparent
    }
    val severityColor = theme.color(diagnostic.severity.token)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .pointerHoverIcon(PointerIcon.Hand)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SeverityMark(
            severity = diagnostic.severity,
            color = severityColor,
            label = diagnostic.severity.name,
        )
        Text(
            text = (diagnostic.row + 1).toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = BufferFontFamily),
            color = severityColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(lineNumberWidth(diagnostic.row + 1)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = diagnostic.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = BufferFontFamily),
                color = theme.color("editor.foreground"),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            val origin = listOfNotNull(diagnostic.source, diagnostic.code)
            if (origin.isNotEmpty()) {
                Text(
                    text = origin.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = BufferFontFamily),
                    color = theme.color("text.muted"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A square toolbar button with the ghost ramp every button here wears —
 * `Subtle` at rest, `ghost_element.hover` / `.active` under the pointer,
 * glyph in `text.accent` while selected (see the search panel's BarButton for
 * the Zed derivation).
 */
@Composable
private fun ToolbarToggle(
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(ButtonBox)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .focusProperties { canFocus = false }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(
            if (selected) {
                theme.color("text.accent", MaterialTheme.colorScheme.onSurface)
            } else {
                theme.color("text", MaterialTheme.colorScheme.onSurface)
            }
        )
    }
}

/**
 * The severity glyphs, strokes on a canvas for the status bar's reason: three
 * shapes are cheaper to keep true than three more SVGs. Same geometry as the
 * bar's, at the panel's size.
 */
@Composable
private fun SeverityMark(severity: DiagnosticSeverity, color: Color, label: String) {
    Canvas(
        modifier = Modifier
            .size(SeverityIconSize)
            .semantics { contentDescription = label }
    ) {
        val stroke = size.minDimension * 0.11f
        when (severity) {
            DiagnosticSeverity.Error -> {
                drawCircle(
                    color = color,
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
                val inset = size.minDimension * 0.33f
                drawLine(
                    color = color,
                    start = Offset(inset, inset),
                    end = Offset(size.width - inset, size.height - inset),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width - inset, inset),
                    end = Offset(inset, size.height - inset),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            DiagnosticSeverity.Warning -> drawWarning(color, stroke)
            // Zed's Info/Hint are both a ringed "i"; the token already mutes
            // the hint's colour.
            DiagnosticSeverity.Info, DiagnosticSeverity.Hint -> {
                drawCircle(
                    color = color,
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
                drawCircle(
                    color = color,
                    radius = stroke * 0.55f,
                    center = Offset(size.width / 2f, size.height * 0.32f),
                )
                drawLine(
                    color = color,
                    start = Offset(size.width / 2f, size.height * 0.48f),
                    end = Offset(size.width / 2f, size.height * 0.70f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** The toolbar's warning triangle, sized to its button rather than a row. */
@Composable
private fun WarningGlyph(color: Color) {
    Canvas(modifier = Modifier.size(SeverityIconSize)) {
        drawWarning(color, size.minDimension * 0.11f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWarning(
    color: Color,
    stroke: Float,
) {
    val top = size.height * 0.14f
    val bottom = size.height * 0.84f
    val path = Path().apply {
        moveTo(size.width / 2f, top)
        lineTo(size.width - stroke, bottom)
        lineTo(stroke, bottom)
        close()
    }
    drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    drawLine(
        color = color,
        start = Offset(size.width / 2f, size.height * 0.42f),
        end = Offset(size.width / 2f, size.height * 0.62f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawCircle(
        color = color,
        radius = stroke * 0.55f,
        center = Offset(size.width / 2f, size.height * 0.73f),
    )
}
