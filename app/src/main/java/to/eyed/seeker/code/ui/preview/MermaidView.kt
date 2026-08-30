package to.eyed.seeker.code.ui.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A ```mermaid fence, drawn.
 *
 * [MermaidDiagram] reads it and [MermaidLayout] places it; everything
 * Compose-shaped is here. A diagram outside the supported subset is not drawn
 * wrong — it becomes a card naming the type with its source underneath, which
 * is the same answer the SVG preview gives a gradient.
 */
@Composable
internal fun MermaidView(source: String, style: PreviewStyle, modifier: Modifier = Modifier) {
    val diagram = remember(source) { MermaidDiagram.parse(source) }
    if (diagram == null || diagram.kind == MermaidKind.Unknown || diagram.isEmpty) {
        MermaidCard(source, diagram?.declared, style, modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(style) {
        style.body.copy(fontSize = style.body.fontSize * 0.85f, color = Color.Unspecified)
    }
    val density = LocalDensity.current
    val layout = remember(diagram, measurer, labelStyle, density) {
        val lineHeight = measurer.measure("Ag", labelStyle).size.height.toFloat()
        MermaidLayout.of(
            diagram,
            MermaidMetrics(
                lineHeight = lineHeight,
                padX = with(density) { 10.dp.toPx() },
                padY = with(density) { 6.dp.toPx() },
                gapX = with(density) { 20.dp.toPx() },
                gapY = with(density) { 36.dp.toPx() },
                minNodeWidth = with(density) { 44.dp.toPx() },
            ),
        ) { text -> measurer.measure(text, labelStyle).size.width.toFloat() }
    }
    if (layout.nodes.isEmpty()) {
        MermaidCard(source, diagram.declared, style, modifier)
        return
    }
    val labels = remember(layout, measurer, labelStyle) {
        val texts = layout.nodes.map { it.node.label } + layout.edges.mapNotNull { it.label }
        texts.distinct().associateWith { measurer.measure(it, labelStyle) }
    }

    val theme = style.theme
    val ink = theme.color("text")
    val line = theme.color("border")
    val fill = theme.color("element.background", theme.color("editor.background"))
    val accent = theme.color("text.accent")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color("editor.background"))
            .border(1.dp, theme.color("border.variant"), RoundedCornerShape(4.dp))
            .padding(12.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val available = with(density) { maxWidth.toPx() }
            // Fitted, never blown up: a two-box diagram at 3× would be a
            // cartoon. Below 1 it shrinks so the whole graph stays on screen
            // rather than needing a scroll in a 400dp dock.
            val scale = if (layout.width <= 0f) 1f else minOf(1f, available / layout.width)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { (layout.height * scale).toDp() })
            ) {
                withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                    for (lifeline in layout.lifelines) {
                        drawLine(
                            color = line,
                            start = Offset(lifeline.x, layout.nodes.first().height),
                            end = Offset(lifeline.x, lifeline.y),
                            strokeWidth = 1f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }
                    for (edge in layout.edges) drawEdge(edge, line, ink, labels, accent)
                    for (node in layout.nodes) drawNode(node, fill, line, ink, labels)
                }
            }
        }
        if (diagram.unsupported.isNotEmpty()) {
            Text(
                text = "not drawn: ${diagram.unsupported.joinToString(", ")}",
                style = style.body.copy(fontSize = style.body.fontSize * 0.8f),
                color = theme.color("text.muted"),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** One node's outline, then its label centred in it. */
private fun DrawScope.drawNode(
    node: PlacedNode,
    fill: Color,
    line: Color,
    ink: Color,
    labels: Map<String, TextLayoutResult>,
) {
    val topLeft = Offset(node.x, node.y)
    val size = Size(node.width, node.height)
    val outline = Stroke(width = 1.5f)
    when (node.node.shape) {
        MermaidShape.Circle, MermaidShape.Stadium, MermaidShape.Rounded -> {
            val radius = if (node.node.shape == MermaidShape.Rounded) {
                node.height / 4f
            } else {
                node.height / 2f
            }
            drawRoundRect(fill, topLeft, size, androidx.compose.ui.geometry.CornerRadius(radius))
            drawRoundRect(
                line,
                topLeft,
                size,
                androidx.compose.ui.geometry.CornerRadius(radius),
                style = outline,
            )
        }
        MermaidShape.Diamond, MermaidShape.Hexagon, MermaidShape.Flag -> {
            val path = polygonFor(node)
            drawPath(path, fill)
            drawPath(path, line, style = outline)
        }
        else -> {
            drawRect(fill, topLeft, size)
            drawRect(line, topLeft, size, style = outline)
            if (node.node.shape == MermaidShape.Subroutine) {
                val inset = 5f
                drawLine(
                    line,
                    Offset(node.x + inset, node.y),
                    Offset(node.x + inset, node.y + node.height),
                    strokeWidth = 1.5f,
                )
                drawLine(
                    line,
                    Offset(node.x + node.width - inset, node.y),
                    Offset(node.x + node.width - inset, node.y + node.height),
                    strokeWidth = 1.5f,
                )
            }
        }
    }
    val label = labels[node.node.label] ?: return
    drawText(
        textLayoutResult = label,
        color = ink,
        topLeft = Offset(
            node.centreX - label.size.width / 2f,
            node.centreY - label.size.height / 2f,
        ),
    )
}

/** A diamond, a hexagon or Mermaid's asymmetric flag, as a path. */
private fun polygonFor(node: PlacedNode): Path {
    val path = Path()
    val left = node.x
    val right = node.x + node.width
    val top = node.y
    val bottom = node.y + node.height
    when (node.node.shape) {
        MermaidShape.Diamond -> {
            path.moveTo(node.centreX, top)
            path.lineTo(right, node.centreY)
            path.lineTo(node.centreX, bottom)
            path.lineTo(left, node.centreY)
        }
        MermaidShape.Hexagon -> {
            val notch = node.height / 2f
            path.moveTo(left + notch, top)
            path.lineTo(right - notch, top)
            path.lineTo(right, node.centreY)
            path.lineTo(right - notch, bottom)
            path.lineTo(left + notch, bottom)
            path.lineTo(left, node.centreY)
        }
        else -> {
            val notch = node.height / 3f
            path.moveTo(left, top)
            path.lineTo(right - notch, top)
            path.lineTo(right, node.centreY)
            path.lineTo(right - notch, bottom)
            path.lineTo(left, bottom)
        }
    }
    path.close()
    return path
}

/** How far back the two strokes of an arrow head reach. */
private const val ARROW_HEAD = 10f
private const val ARROW_SPREAD = 0.45f

private fun DrawScope.drawEdge(
    edge: PlacedEdge,
    line: Color,
    ink: Color,
    labels: Map<String, TextLayoutResult>,
    accent: Color,
) {
    val start = Offset(edge.start.x, edge.start.y)
    val end = Offset(edge.end.x, edge.end.y)
    val width = if (edge.edge.thick) 3f else 1.5f
    drawLine(
        color = line,
        start = start,
        end = end,
        strokeWidth = width,
        pathEffect = if (edge.edge.dashed) {
            PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        } else {
            null
        },
    )
    if (edge.edge.arrow) {
        val length = hypot(end.x - start.x, end.y - start.y)
        if (length > 0f) {
            val angle = kotlin.math.atan2(end.y - start.y, end.x - start.x)
            for (spread in listOf(-ARROW_SPREAD, ARROW_SPREAD)) {
                drawLine(
                    color = line,
                    start = end,
                    end = Offset(
                        end.x - ARROW_HEAD * cos(angle + spread),
                        end.y - ARROW_HEAD * sin(angle + spread),
                    ),
                    strokeWidth = width,
                )
            }
        }
    }
    val at = edge.labelAt ?: return
    val label = labels[edge.label] ?: return
    // The label sits *on* the line, so it is drawn on its own patch of the
    // background rather than over the stroke.
    val topLeft = Offset(at.x - label.size.width / 2f, at.y - label.size.height / 2f)
    drawRect(
        color = accent.copy(alpha = 0.06f),
        topLeft = Offset(topLeft.x - 3f, topLeft.y - 1f),
        size = Size(label.size.width + 6f, label.size.height + 2f),
    )
    drawText(textLayoutResult = label, color = ink, topLeft = topLeft)
}

/**
 * A fence this reader cannot draw: named, with its source kept.
 *
 * "Named" matters — a `gantt` block that silently became a grey box would be
 * indistinguishable from a broken renderer, and the whole point of admitting
 * the subset is that the reader can see which side of it they are on.
 */
@Composable
private fun MermaidCard(
    source: String,
    declared: String?,
    style: PreviewStyle,
    modifier: Modifier = Modifier,
) {
    val theme = style.theme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(theme.color("editor.background"))
            .border(1.dp, theme.color("border.variant"), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Text(
            text = if (declared.isNullOrBlank()) {
                "Mermaid diagram — not drawn"
            } else {
                "Mermaid $declared — not drawn"
            },
            style = style.body.copy(fontSize = style.body.fontSize * 0.85f),
            color = theme.color("text.muted"),
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text = source,
                style = style.code,
                softWrap = false,
                maxLines = MERMAID_CARD_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How much of an undrawn diagram's source the card shows. */
private const val MERMAID_CARD_LINES = 20
