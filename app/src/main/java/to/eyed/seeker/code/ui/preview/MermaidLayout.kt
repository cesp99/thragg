package to.eyed.seeker.code.ui.preview

import kotlin.math.abs
import kotlin.math.max

/**
 * Where a [MermaidDiagram]'s boxes and arrows go.
 *
 * Mermaid lays a flowchart out with dagre — a proper layered graph drawing
 * with crossing minimisation and spline routing. This is the first two thirds
 * of that and none of the third: nodes are ranked by longest path, drawn in
 * declaration order inside their rank, and joined by straight lines clipped to
 * the boxes they leave and enter. The result reads correctly for the diagrams
 * a README holds — a handful of nodes and a couple of branches — and does not
 * pretend to be dagre for anything larger.
 *
 * Pure arithmetic: it takes the text metrics it needs as a function, so the
 * whole of it is checked on the host with a fake measurer, and [MermaidView]
 * supplies the real one.
 */

/** A point in the diagram's own coordinates, before any fit-to-width scale. */
data class MermaidPoint(val x: Float, val y: Float)

/** One node, placed. */
class PlacedNode(
    val node: MermaidNode,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val centreX: Float get() = x + width / 2f
    val centreY: Float get() = y + height / 2f
}

/** One link, placed: where it leaves, where it arrives, and where its text sits. */
class PlacedEdge(
    val edge: MermaidEdge,
    val start: MermaidPoint,
    val end: MermaidPoint,
    val label: String?,
    val labelAt: MermaidPoint?,
)

/** The gaps and paddings a drawing is built from, in pixels. */
class MermaidMetrics(
    val lineHeight: Float,
    /** Left and right padding inside a node box. */
    val padX: Float,
    /** Top and bottom padding inside a node box. */
    val padY: Float,
    /** Between two nodes of the same rank. */
    val gapX: Float,
    /** Between two ranks. */
    val gapY: Float,
    val minNodeWidth: Float,
)

class MermaidLayout(
    val width: Float,
    val height: Float,
    val nodes: List<PlacedNode>,
    val edges: List<PlacedEdge>,
    /** Lifeline bottoms for a sequence diagram; empty for a flowchart. */
    val lifelines: List<MermaidPoint>,
) {
    companion object {
        val EMPTY = MermaidLayout(0f, 0f, emptyList(), emptyList(), emptyList())

        /**
         * Lay [diagram] out. [measure] is the width of a string in the label
         * font; the height of every label is one [MermaidMetrics.lineHeight].
         */
        fun of(
            diagram: MermaidDiagram,
            metrics: MermaidMetrics,
            measure: (String) -> Float,
        ): MermaidLayout = when (diagram.kind) {
            MermaidKind.Flowchart -> flowchart(diagram, metrics, measure)
            MermaidKind.Sequence -> sequence(diagram, metrics, measure)
            MermaidKind.Unknown -> EMPTY
        }

        /**
         * The rank of every node: the length of the longest path to it.
         *
         * Relaxed rather than sorted topologically, and bounded by the node
         * count, because a Mermaid flowchart may perfectly well contain a
         * cycle — `A --> B --> A` is a legal diagram and a topological sort
         * has no answer for it. Once the passes run out whatever ranks have
         * been reached are used, which draws a cycle as a staircase rather
         * than not at all.
         */
        internal fun rankNodes(nodes: List<MermaidNode>, edges: List<MermaidEdge>): IntArray {
            val index = nodes.withIndex().associate { (at, node) -> node.id to at }
            val ranks = IntArray(nodes.size)
            if (nodes.isEmpty()) return ranks
            repeat(nodes.size) {
                var changed = false
                for (edge in edges) {
                    val from = index[edge.from] ?: continue
                    val to = index[edge.to] ?: continue
                    if (from == to) continue
                    if (ranks[to] <= ranks[from]) {
                        ranks[to] = ranks[from] + 1
                        changed = true
                    }
                }
                if (!changed) return ranks
            }
            return ranks
        }

        private fun flowchart(
            diagram: MermaidDiagram,
            metrics: MermaidMetrics,
            measure: (String) -> Float,
        ): MermaidLayout {
            if (diagram.nodes.isEmpty()) return EMPTY
            val ranks = rankNodes(diagram.nodes, diagram.edges)
            val boxHeight = metrics.lineHeight + metrics.padY * 2f
            val widths = diagram.nodes.map { node ->
                max(measure(node.label) + metrics.padX * 2f, metrics.minNodeWidth)
            }
            val horizontal =
                diagram.direction == MermaidDirection.Right ||
                    diagram.direction == MermaidDirection.Left
            // Along the rank axis every rank is one band; across it the nodes
            // of a rank are laid out one after another and the band is then
            // centred, which is what keeps a two-child branch symmetrical
            // under its parent.
            val byRank = LinkedHashMap<Int, MutableList<Int>>()
            for (at in diagram.nodes.indices) {
                byRank.getOrPut(ranks[at]) { mutableListOf() }.add(at)
            }
            val rankCount = (ranks.maxOrNull() ?: 0) + 1
            // The band's extent across the rank axis, per rank.
            val across = FloatArray(rankCount)
            for ((rank, members) in byRank) {
                across[rank] = members.sumOf { at ->
                    (if (horizontal) boxHeight else widths[at]).toDouble()
                }.toFloat() + metrics.gapX * (members.size - 1)
            }
            val widest = across.maxOrNull() ?: 0f
            val bandDepth = if (horizontal) {
                (0 until rankCount).map { rank ->
                    byRank[rank].orEmpty().maxOfOrNull { widths[it] } ?: metrics.minNodeWidth
                }
            } else {
                List(rankCount) { boxHeight }
            }
            // Where each rank's band starts along the rank axis.
            val bandStart = FloatArray(rankCount)
            var depth = 0f
            for (rank in 0 until rankCount) {
                bandStart[rank] = depth
                depth += bandDepth[rank] + metrics.gapY
            }
            val totalDepth = (depth - metrics.gapY).coerceAtLeast(0f)

            val placed = arrayOfNulls<PlacedNode>(diagram.nodes.size)
            for ((rank, members) in byRank) {
                var offset = (widest - across[rank]) / 2f
                for (at in members) {
                    val node = diagram.nodes[at]
                    val alongDepth = bandStart[rank]
                    placed[at] = if (horizontal) {
                        PlacedNode(node, alongDepth, offset, widths[at], boxHeight)
                    } else {
                        PlacedNode(node, offset, alongDepth, widths[at], boxHeight)
                    }
                    offset += (if (horizontal) boxHeight else widths[at]) + metrics.gapX
                }
            }
            var boxes = placed.filterNotNull()
            // `BT` and `RL` are the same layout read backwards.
            val flipY = diagram.direction == MermaidDirection.Up
            val flipX = diagram.direction == MermaidDirection.Left
            if (flipY || flipX) {
                val spanX = if (horizontal) totalDepth else widest
                val spanY = if (horizontal) widest else totalDepth
                boxes = boxes.map {
                    PlacedNode(
                        it.node,
                        if (flipX) spanX - it.x - it.width else it.x,
                        if (flipY) spanY - it.y - it.height else it.y,
                        it.width,
                        it.height,
                    )
                }
            }
            val byId = boxes.associateBy { it.node.id }
            val links = diagram.edges.mapNotNull { edge ->
                val from = byId[edge.from] ?: return@mapNotNull null
                val to = byId[edge.to] ?: return@mapNotNull null
                if (from === to) return@mapNotNull null
                val start = clipToBox(from, to.centreX, to.centreY)
                val end = clipToBox(to, from.centreX, from.centreY)
                PlacedEdge(
                    edge = edge,
                    start = start,
                    end = end,
                    label = edge.label,
                    labelAt = edge.label?.let {
                        MermaidPoint((start.x + end.x) / 2f, (start.y + end.y) / 2f)
                    },
                )
            }
            return MermaidLayout(
                width = if (horizontal) totalDepth else widest,
                height = if (horizontal) widest else totalDepth,
                nodes = boxes,
                edges = links,
                lifelines = emptyList(),
            )
        }

        /**
         * Where the segment from [box]'s centre towards ([toX], [toY]) leaves
         * the box.
         *
         * The arrow has to stop at the border rather than at the centre, or
         * every head is drawn underneath the box it points at.
         */
        internal fun clipToBox(box: PlacedNode, toX: Float, toY: Float): MermaidPoint {
            val dx = toX - box.centreX
            val dy = toY - box.centreY
            if (dx == 0f && dy == 0f) return MermaidPoint(box.centreX, box.centreY)
            val halfWidth = box.width / 2f
            val halfHeight = box.height / 2f
            // The smaller of the two axis scales is the side actually crossed.
            val scaleX = if (dx == 0f) Float.MAX_VALUE else halfWidth / abs(dx)
            val scaleY = if (dy == 0f) Float.MAX_VALUE else halfHeight / abs(dy)
            val scale = minOf(scaleX, scaleY)
            return MermaidPoint(box.centreX + dx * scale, box.centreY + dy * scale)
        }

        /** How far a self-message's loop reaches to the right of its lifeline. */
        internal const val SELF_MESSAGE_WIDTH = 48f

        private fun sequence(
            diagram: MermaidDiagram,
            metrics: MermaidMetrics,
            measure: (String) -> Float,
        ): MermaidLayout {
            if (diagram.nodes.isEmpty()) return EMPTY
            val boxHeight = metrics.lineHeight + metrics.padY * 2f
            val widths = diagram.nodes.map { node ->
                max(measure(node.label) + metrics.padX * 2f, metrics.minNodeWidth)
            }
            // The columns have to be far enough apart for the widest message
            // to sit between them, or every label overprints its neighbours.
            val widestLabel = diagram.edges.maxOfOrNull { measure(it.label.orEmpty()) } ?: 0f
            val gap = max(metrics.gapX, widestLabel + metrics.padX * 2f)
            val boxes = mutableListOf<PlacedNode>()
            var x = 0f
            for ((at, node) in diagram.nodes.withIndex()) {
                boxes.add(PlacedNode(node, x, 0f, widths[at], boxHeight))
                x += widths[at] + gap
            }
            val width = (x - gap).coerceAtLeast(0f) + SELF_MESSAGE_WIDTH
            val rowHeight = metrics.lineHeight * 2f
            val first = boxHeight + metrics.gapY
            val byId = boxes.associateBy { it.node.id }
            val links = mutableListOf<PlacedEdge>()
            var row = 0
            for (edge in diagram.edges) {
                val from = byId[edge.from] ?: continue
                val to = byId[edge.to] ?: continue
                val y = first + row * rowHeight
                row++
                if (from === to) {
                    // Mermaid draws a self-message as a loop out and back; one
                    // level of detail down, it is an arrow to the right and a
                    // label above it.
                    links.add(
                        PlacedEdge(
                            edge = edge,
                            start = MermaidPoint(from.centreX, y),
                            end = MermaidPoint(from.centreX + SELF_MESSAGE_WIDTH, y),
                            label = edge.label,
                            labelAt = edge.label?.let {
                                MermaidPoint(from.centreX + SELF_MESSAGE_WIDTH / 2f, y)
                            },
                        )
                    )
                    continue
                }
                links.add(
                    PlacedEdge(
                        edge = edge,
                        start = MermaidPoint(from.centreX, y),
                        end = MermaidPoint(to.centreX, y),
                        label = edge.label,
                        labelAt = edge.label?.let {
                            MermaidPoint((from.centreX + to.centreX) / 2f, y)
                        },
                    )
                )
            }
            val height = first + max(row, 1) * rowHeight
            return MermaidLayout(
                width = width,
                height = height,
                nodes = boxes,
                edges = links,
                lifelines = boxes.map { MermaidPoint(it.centreX, height) },
            )
        }
    }
}
