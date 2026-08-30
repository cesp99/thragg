package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Mermaid subset: what it reads, and — just as important — what it admits
 * it did not.
 *
 * The failure this guards against is a diagram that comes out *nearly* right:
 * an edge label read as a node, a `subgraph` silently flattened, a `--o` whose
 * `o` became a node called "o". Each of those draws something plausible and
 * wrong, which is worse than the card that says "not drawn".
 */
class MermaidDiagramTest {

    private fun flow(source: String) = MermaidDiagram.parse(source)!!

    @Test
    fun `a flowchart's nodes, labels and shapes`() {
        val diagram = flow(
            """
            flowchart TD
                A[Start] --> B(Round)
                B --> C{Choice}
                C --> D((Circle))
            """.trimIndent()
        )
        assertEquals(MermaidKind.Flowchart, diagram.kind)
        assertEquals(MermaidDirection.Down, diagram.direction)
        assertEquals(listOf("A", "B", "C", "D"), diagram.nodes.map { it.id })
        assertEquals(listOf("Start", "Round", "Choice", "Circle"), diagram.nodes.map { it.label })
        assertEquals(
            listOf(
                MermaidShape.Box,
                MermaidShape.Rounded,
                MermaidShape.Diamond,
                MermaidShape.Circle,
            ),
            diagram.nodes.map { it.shape },
        )
        assertEquals(3, diagram.edges.size)
        assertEquals("A" to "B", diagram.edges[0].from to diagram.edges[0].to)
    }

    @Test
    fun `a chain is a chain of edges`() {
        val diagram = flow("graph LR\n  A --> B --> C\n")
        assertEquals(MermaidDirection.Right, diagram.direction)
        assertEquals(listOf("A" to "B", "B" to "C"), diagram.edges.map { it.from to it.to })
    }

    @Test
    fun `edge labels in both spellings`() {
        val diagram = flow(
            """
            flowchart TD
                A -->|yes| B
                A -- no --> C
            """.trimIndent()
        )
        assertEquals(listOf("yes", "no"), diagram.edges.map { it.label })
        assertEquals(listOf("A" to "B", "A" to "C"), diagram.edges.map { it.from to it.to })
    }

    @Test
    fun `line styles`() {
        val diagram = flow(
            """
            flowchart TD
                A --> B
                A --- C
                A -.-> D
                A ==> E
                A -.- F
            """.trimIndent()
        )
        assertEquals(
            listOf(true, false, true, true, false),
            diagram.edges.map { it.arrow },
        )
        assertEquals(
            listOf(false, false, true, false, true),
            diagram.edges.map { it.dashed },
        )
        assertTrue(diagram.edges.single { it.to == "E" }.thick)
    }

    /** `--o` and `--x` end in a head, not in a node called `o`. */
    @Test
    fun `circle and cross ends are edges, not nodes`() {
        val diagram = flow("flowchart LR\n  A --o B\n  C --x D\n")
        assertEquals(listOf("A", "B", "C", "D"), diagram.nodes.map { it.id })
        assertEquals(2, diagram.edges.size)
        assertTrue(diagram.edges.all { it.arrow })
    }

    /** A label given later wins over the bare mention that came first. */
    @Test
    fun `a node labelled after it is used keeps the label`() {
        val diagram = flow("flowchart TD\n  A --> B\n  B[Done]\n")
        assertEquals("Done", diagram.nodes.single { it.id == "B" }.label)
    }

    @Test
    fun `semicolons separate statements and comments are dropped`() {
        val diagram = flow("graph TD; A-->B; B-->C; %% a note\n")
        assertEquals(listOf("A", "B", "C"), diagram.nodes.map { it.id })
        assertEquals(2, diagram.edges.size)
    }

    @Test
    fun `a subgraph is named rather than drawn`() {
        val diagram = flow(
            """
            flowchart TD
                subgraph one
                A --> B
                end
            """.trimIndent()
        )
        assertTrue("subgraph" in diagram.unsupported)
        assertEquals(listOf("A", "B"), diagram.nodes.map { it.id })
    }

    @Test
    fun `an unknown diagram type is named, not guessed at`() {
        val diagram = MermaidDiagram.parse("gantt\n  title A\n")!!
        assertEquals(MermaidKind.Unknown, diagram.kind)
        assertEquals("gantt", diagram.declared)
        assertTrue(diagram.isEmpty)
        assertNull(MermaidDiagram.parse("   \n\n"))
    }

    // ---- Sequence diagrams ------------------------------------------------

    @Test
    fun `a sequence diagram's participants and messages`() {
        val diagram = MermaidDiagram.parse(
            """
            sequenceDiagram
                participant A as Alice
                participant B as Bob
                A->>B: Hello
                B-->>A: Hi back
            """.trimIndent()
        )!!
        assertEquals(MermaidKind.Sequence, diagram.kind)
        assertEquals(listOf("A", "B"), diagram.nodes.map { it.id })
        assertEquals(listOf("Alice", "Bob"), diagram.nodes.map { it.label })
        assertEquals(listOf("Hello", "Hi back"), diagram.edges.map { it.label })
        // A `-->>` reply is the dashed one.
        assertEquals(listOf(false, true), diagram.edges.map { it.dashed })
    }

    /** A participant nobody declared is still a participant. */
    @Test
    fun `an undeclared participant appears in message order`() {
        val diagram = MermaidDiagram.parse("sequenceDiagram\n  Alice->>Bob: hi\n")!!
        assertEquals(listOf("Alice", "Bob"), diagram.nodes.map { it.id })
        assertEquals("hi", diagram.edges.single().label)
    }

    @Test
    fun `notes and loops are named rather than drawn`() {
        val diagram = MermaidDiagram.parse(
            """
            sequenceDiagram
                loop every minute
                A->>B: poll
                end
                Note right of B: busy
            """.trimIndent()
        )!!
        assertTrue("loop" in diagram.unsupported)
        assertTrue("note" in diagram.unsupported)
        assertEquals(1, diagram.edges.size)
    }

    // ---- Layout ------------------------------------------------------------

    private val metrics = MermaidMetrics(
        lineHeight = 10f,
        padX = 4f,
        padY = 4f,
        gapX = 10f,
        gapY = 20f,
        minNodeWidth = 20f,
    )

    /** Eight pixels a character, so the arithmetic below is checkable by hand. */
    private val measure: (String) -> Float = { it.length * 8f }

    @Test
    fun `ranks are the longest path to a node`() {
        val nodes = listOf("A", "B", "C", "D").map { MermaidNode(it, it) }
        val edges = listOf(
            MermaidEdge("A", "B"),
            MermaidEdge("B", "C"),
            MermaidEdge("A", "C"),
            MermaidEdge("C", "D"),
        )
        assertEquals(listOf(0, 1, 2, 3), MermaidLayout.rankNodes(nodes, edges).toList())
    }

    /** A cycle has no topological order; it must still terminate. */
    @Test
    fun `a cycle does not hang the ranker`() {
        val nodes = listOf("A", "B").map { MermaidNode(it, it) }
        val ranks = MermaidLayout.rankNodes(nodes, listOf(MermaidEdge("A", "B"), MermaidEdge("B", "A")))
        assertEquals(2, ranks.size)
    }

    @Test
    fun `a flowchart is laid out in bands, one per rank`() {
        val diagram = flow("flowchart TD\n  A --> B\n  A --> C\n")
        val layout = MermaidLayout.of(diagram, metrics, measure)
        assertEquals(3, layout.nodes.size)
        val a = layout.nodes.single { it.node.id == "A" }
        val b = layout.nodes.single { it.node.id == "B" }
        val c = layout.nodes.single { it.node.id == "C" }
        // Rank 0 above rank 1, and the two children side by side on one row.
        assertTrue(a.y < b.y)
        assertEquals(b.y, c.y, 0.001f)
        assertTrue(b.x < c.x)
        // The parent sits centred over its two children.
        assertEquals((b.centreX + c.centreX) / 2f, a.centreX, 0.001f)
        assertEquals(2, layout.edges.size)
    }

    @Test
    fun `left to right swaps the axes`() {
        val diagram = flow("flowchart LR\n  A --> B\n")
        val layout = MermaidLayout.of(diagram, metrics, measure)
        val a = layout.nodes.first { it.node.id == "A" }
        val b = layout.nodes.first { it.node.id == "B" }
        assertTrue(a.x < b.x)
        assertEquals(a.y, b.y, 0.001f)
    }

    /** An arrow stops at the border of the box it points at, not its centre. */
    @Test
    fun `an edge is clipped to the boxes it joins`() {
        val diagram = flow("flowchart TD\n  A --> B\n")
        val layout = MermaidLayout.of(diagram, metrics, measure)
        val a = layout.nodes.first { it.node.id == "A" }
        val b = layout.nodes.first { it.node.id == "B" }
        val edge = layout.edges.single()
        assertEquals(a.y + a.height, edge.start.y, 0.001f)
        assertEquals(b.y, edge.end.y, 0.001f)
    }

    @Test
    fun `a sequence diagram gets one lifeline per participant`() {
        val diagram = MermaidDiagram.parse("sequenceDiagram\n  A->>B: one\n  B->>A: two\n")!!
        val layout = MermaidLayout.of(diagram, metrics, measure)
        assertEquals(2, layout.lifelines.size)
        assertEquals(2, layout.edges.size)
        // Messages stack down the diagram in the order they were written.
        assertTrue(layout.edges[0].start.y < layout.edges[1].start.y)
        assertNotNull(layout.nodes.firstOrNull { it.node.id == "A" })
    }
}
