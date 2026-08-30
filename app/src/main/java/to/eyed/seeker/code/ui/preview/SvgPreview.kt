package to.eyed.seeker.code.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.ui.editor.EditorState
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.UiFontFamily

/** How far a pinch may take the drawing, either way. */
private const val MIN_ZOOM = 0.2f
private const val MAX_ZOOM = 24f

/** Breathing room around the drawing, so a full-bleed icon is not edge to edge. */
private val Inset = 24.dp

/**
 * The picture an SVG describes, beside the text that describes it.
 *
 * Zed keeps SVG out of its image viewer by name (`image_store.rs:261`) and
 * opens it in the editor with a preview behind the toolbar's eye, which is the
 * right way round: the person who opens `icon.svg` in an IDE is usually the
 * person editing it. So this is a *preview*, exactly like the Markdown one —
 * same dock, same header, same rule that the source stays where it was.
 *
 * It follows the buffer rather than the file: the drawing updates as the `d`
 * attribute is typed, which is what makes it worth having open. [SvgDocument]
 * does the reading; everything Android-shaped happens here.
 *
 * Zoom is a pinch or a scroll, pan is a drag, and a double tap puts both back.
 * A 16px icon is unreadable at 1:1 on a 420dpi screen, so the drawing is fitted
 * to the pane first and the zoom is on top of that.
 */
@Composable
fun SvgPreview(
    editor: EditorState,
    path: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    val focus = remember { FocusRequester() }

    val isSvg = PreviewKind.of(path) == PreviewKind.Svg

    // Two things move the text and only one of them is visible to composition:
    // the same reload-from-disk case the Markdown preview polls for.
    var engineVersion by remember(editor) { mutableLongStateOf(-1L) }
    LaunchedEffect(editor) {
        while (true) {
            engineVersion = editor.session.version
            delay(PREVIEW_VERSION_POLL_MS)
        }
    }

    var drawing by remember(editor) { mutableStateOf(SvgDrawing.LOADING) }
    LaunchedEffect(editor, isSvg, editor.revision, engineVersion) {
        if (!isSvg) {
            drawing = SvgDrawing.EMPTY
            return@LaunchedEffect
        }
        delay(PREVIEW_REPARSE_DEBOUNCE_MS)
        // All of it off the main thread: reading past the drawn window is a
        // JNI call that takes the engine's buffer mutex, and turning a few
        // thousand `d` attributes into paths is not free either.
        drawing = withContext(Dispatchers.Default) {
            val source = cappedSource(editor.lineCount) { first, last -> editor.linesOf(first, last) }
            when {
                source == null -> SvgDrawing.TOO_LARGE
                else -> SvgDrawing.of(source)
            }
        }
    }

    var zoom by remember(editor) { mutableFloatStateOf(1f) }
    var pan by remember(editor) { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(theme.color("editor.background"))
                    .focusRequester(focus)
                    .focusable()
                    // Focus is taken on a press, never requested when the panel
                    // appears: opening the preview must not pull the keyboard
                    // out of the editor it is following.
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
                            // The keyboard's zoom, for a device with no touch
                            // screen and for anyone who cannot pinch.
                            event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.Plus) -> {
                                zoom = (zoom * 1.25f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                true
                            }
                            event.isCtrlPressed && event.key == Key.Minus -> {
                                zoom = (zoom / 1.25f).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                true
                            }
                            event.isCtrlPressed && event.key == Key.Zero -> {
                                zoom = 1f
                                pan = Offset.Zero
                                true
                            }
                            else -> false
                        }
                    }
            ) {
                PreviewHeader(path = path, onDismiss = onDismiss)
                HorizontalDivider(color = theme.color("border.variant"))
                when {
                    !isSvg -> PreviewNotice(
                        "The SVG preview draws a .svg file. Open one and it appears here."
                    )
                    drawing.isTooLarge -> PreviewNotice(
                        "This file is too large to preview. It is still open in the editor."
                    )
                    // The debounce plus the first read is a couple of hundred
                    // milliseconds; asserting the file is broken for that long
                    // every time it is opened is a lie the user reads first.
                    drawing.isLoading -> PreviewNotice("Reading…")
                    drawing.document == null -> PreviewNotice(
                        "This is not an SVG that can be drawn — the editor still has the source. " +
                            "A file that declares a DOCTYPE is refused on purpose."
                    )
                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        SvgCanvas(
                            drawing = drawing,
                            zoom = zoom,
                            pan = pan,
                            onGesture = { centroid, panned, zoomed ->
                                val next = (zoom * zoomed).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                // Keep whatever is under the fingers under the
                                // fingers: the point they are on has to stay
                                // put while everything around it grows.
                                val factor = next / zoom
                                pan = (pan + centroid) * factor - centroid + panned
                                zoom = next
                            },
                            onReset = {
                                zoom = 1f
                                pan = Offset.Zero
                            },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        Footer(drawing)
                    }
                }
            }
        }
    }
}

@Composable
private fun SvgCanvas(
    drawing: SvgDrawing,
    zoom: Float,
    pan: Offset,
    onGesture: (Offset, Offset, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    // What `currentColor` means here. Zed's own icon set is drawn entirely in
    // it, and resolving it to black would make every one of them invisible in
    // a dark theme.
    val current = theme.color("text")
    val document = drawing.document ?: return
    Canvas(
        modifier = modifier
            .padding(Inset)
            .pointerInput(Unit) {
                // The centroid matters: zooming about the pane's middle slides
                // the drawing out from under the fingers doing the zooming.
                detectTransformGestures { centroid, panned, zoomed, _ ->
                    onGesture(centroid, panned, zoomed)
                }
            }
            // Second gesture detector, after the transform one: a double tap is
            // the universal "put it back", and it must not eat the pinch.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onReset() })
            }
            // A mouse has no pinch. Zed zooms its image viewer on the wheel and
            // so does every other viewer; without this the only zoom a paired
            // mouse had was the keyboard's.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll) return@awaitEachGesture
                    val change = event.changes.firstOrNull() ?: return@awaitEachGesture
                    val wheel = change.scrollDelta.y
                    if (wheel == 0f) return@awaitEachGesture
                    // Wheel down is positive; zooming *out* is what that means.
                    onGesture(change.position, Offset.Zero, if (wheel < 0f) 1.1f else 1 / 1.1f)
                    change.consume()
                }
            }
    ) {
        // The drawing's own backing. An SVG says nothing about what is behind
        // it, and Zed's icons — like most icon sets — are drawn in black: on
        // the editor's own background, in a dark theme, that is a black
        // rectangle. The chequer is what every image editor uses to say
        // "transparent", and it makes both black and white ink readable.
        drawChequer(theme.color("border"), theme.color("editor.background"))
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val (scale, offsetX, offsetY) = document.fit(size.width, size.height)
        withTransform({
            translate(pan.x, pan.y)
            scale(zoom, zoom, pivot = center)
            translate(offsetX, offsetY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawSvgShapes(drawing.shapes, current)
        }
    }
}

/**
 * [drawing], fitted to this draw scope and centred in it — no zoom, no pan.
 *
 * The Markdown preview draws an inline `.svg` with this: it is the same
 * geometry the pane uses, minus the gestures a picture inside a paragraph has
 * no room for. [current] is what `currentColor` resolves to.
 */
internal fun DrawScope.drawSvgFitted(drawing: SvgDrawing, current: Color) {
    val document = drawing.document ?: return
    if (size.width <= 0f || size.height <= 0f) return
    val (scale, offsetX, offsetY) = document.fit(size.width, size.height)
    withTransform({
        translate(offsetX, offsetY)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawSvgShapes(drawing.shapes, current)
    }
}

/** Every shape in the drawing, in document order: fill first, then stroke. */
private fun DrawScope.drawSvgShapes(shapes: List<SvgDrawing.Drawable>, current: Color) {
    for (shape in shapes) {
        shape.fill?.let { paint ->
            drawPath(shape.path, color = paint.resolve(current, shape.fillAlpha))
        }
        shape.stroke?.let { paint ->
            drawPath(
                path = shape.path,
                color = paint.resolve(current, shape.strokeAlpha),
                style = Stroke(
                    width = shape.strokeWidth,
                    cap = if (shape.capRound) StrokeCap.Round else StrokeCap.Butt,
                    join = if (shape.joinRound) StrokeJoin.Round else StrokeJoin.Miter,
                ),
            )
        }
    }
}

/** The drawing's own size, and what it contains that we did not draw. */
@Composable
private fun Footer(drawing: SvgDrawing) {
    val theme = LocalZedTheme.current
    val document = drawing.document ?: return
    val size = "${trim(document.viewportWidth)} × ${trim(document.viewportHeight)}"
    val missing = document.unsupported
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (missing.isEmpty()) {
                size
            } else {
                // Said out loud rather than drawn wrong: an SVG with a gradient
                // comes out missing its fills, and the source is right there.
                "$size · not drawn: ${missing.joinToString(", ")}"
            },
            style = TextStyle(fontFamily = UiFontFamily, fontSize = 12.sp),
            color = if (missing.isEmpty()) theme.color("text.muted") else theme.color("text"),
        )
    }
}

private fun trim(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

/** ARGB and an inherited `currentColor`, resolved against the theme. */
private fun SvgPaint.resolve(current: Color, alpha: Float): Color {
    val base = when (this) {
        is SvgPaint.Current -> current
        is SvgPaint.Solid -> Color(argb)
    }
    return if (alpha >= 1f) base else base.copy(alpha = base.alpha * alpha.coerceIn(0f, 1f))
}

/**
 * A parsed document together with the [Path]s it draws as.
 *
 * The paths are built here rather than in the canvas because
 * `PathParser.createPathFromPathData` is a parse per shape, and the canvas runs
 * on the main thread on every frame.
 */
internal class SvgDrawing private constructor(
    val document: SvgDocument?,
    val shapes: List<Drawable>,
    /** True when the file was refused for its size. See [MAX_PREVIEW_CHARS]. */
    val isTooLarge: Boolean = false,
    /** True until the first parse has landed. */
    val isLoading: Boolean = false,
) {
    class Drawable(
        val path: Path,
        val fill: SvgPaint?,
        val fillAlpha: Float,
        val stroke: SvgPaint?,
        val strokeAlpha: Float,
        val strokeWidth: Float,
        val capRound: Boolean,
        val joinRound: Boolean,
    )

    companion object {
        /** Before the first parse lands — *not* the same as "cannot draw it". */
        val LOADING = SvgDrawing(null, emptyList(), isLoading = true)
        val EMPTY = SvgDrawing(null, emptyList())
        val TOO_LARGE = SvgDrawing(null, emptyList(), isTooLarge = true)

        /** **Blocks the thread it is called on** — it belongs on a worker. */
        fun of(source: String): SvgDrawing {
            val document = SvgDocument.parse(source) ?: return EMPTY
            val shapes = document.shapes.mapNotNull { shape ->
                // A `d` the parser chokes on loses that one shape, not the
                // drawing: half an icon still tells you what you typed wrong.
                val path = runCatching {
                    PathParser.createPathFromPathData(shape.pathData)
                }.getOrNull() ?: return@mapNotNull null
                if (!shape.transform.isIdentity) {
                    val matrix = android.graphics.Matrix()
                    matrix.setValues(
                        floatArrayOf(
                            shape.transform.a, shape.transform.c, shape.transform.e,
                            shape.transform.b, shape.transform.d, shape.transform.f,
                            0f, 0f, 1f,
                        )
                    )
                    path.transform(matrix)
                }
                val composed = path.asComposePath()
                if (shape.evenOdd) composed.fillType = PathFillType.EvenOdd
                Drawable(
                    path = composed,
                    fill = shape.fill,
                    fillAlpha = shape.fillAlpha,
                    stroke = shape.stroke,
                    strokeAlpha = shape.strokeAlpha,
                    // The transform is baked into the *geometry* above, so a
                    // stroke drawn at its declared width would come out
                    // thinner or thicker than the shape it outlines. The
                    // factor is the square root of the area scale, which is
                    // what a renderer that transformed the pen would give.
                    strokeWidth = shape.strokeWidth * shape.transform.scaleFactor,
                    capRound = shape.strokeCapRound,
                    joinRound = shape.strokeJoinRound,
                )
            }
            return SvgDrawing(document, shapes)
        }
    }
}

@Composable
private fun PreviewNotice(text: String) {
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

/**
 * The chequerboard behind a drawing — the universal "there is nothing here".
 *
 * Two colours a hair apart rather than the usual grey-on-white: this sits in a
 * themed editor, and a bright chequer beside a dark buffer would be the
 * loudest thing on screen.
 */
private fun DrawScope.drawChequer(mark: Color, ground: Color) {
    drawRect(color = ground)
    val cell = 12.dp.toPx()
    val faint = mark.copy(alpha = 0.16f)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            drawRect(
                color = faint,
                topLeft = Offset(x, y),
                size = Size(minOf(cell, size.width - x), minOf(cell, size.height - y)),
            )
            x += cell * 2
        }
        y += cell
        row++
    }
}
