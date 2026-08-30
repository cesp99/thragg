package to.eyed.seeker.code.ui.preview

/**
 * Enough of Mermaid to draw the two diagrams a README actually contains.
 *
 * Zed renders a ```mermaid fence with `crates/mermaid_render`, which shells
 * out to a bundled JavaScript runtime and lets mermaid.js lay the diagram out.
 * There is no such runtime here — the alternatives on Android are a WebView
 * (a second rendering engine in the process, pointed at a file the user may
 * have cloned an hour ago) or a network round trip to mermaid.ink (a request
 * this app does not make on anyone's behalf). So the common subset is parsed
 * here and drawn in Compose, and everything outside it is *named* rather than
 * drawn wrong — the same bargain [SvgDocument] makes for gradients.
 *
 * What is read:
 *
 *  - `flowchart` / `graph`, direction `TD`, `TB`, `BT`, `LR`, `RL`;
 *    node shapes `id`, `id[box]`, `id(round)`, `id([stadium])`, `id[[sub]]`,
 *    `id[(cylinder)]`, `id((circle))`, `id{diamond}`, `id{{hexagon}}`,
 *    `id>flag]`; links `-->`, `---`, `-.->`,` -.-`, `==>`, `===`, `--o`,
 *    `--x` and their labelled forms `A-->|text|B` and `A -- text --> B`;
 *    chains (`A --> B --> C`) and semicolon-separated statements.
 *  - `sequenceDiagram`: `participant`/`actor`, with or without `as`, and the
 *    messages `->`, `-->`, `->>`, `-->>`, `-x`, `--x`, `-)`, `--)`.
 *
 * What is not: subgraphs, class/style directives, clicks, notes, loops and
 * alt blocks, and every diagram type other than the two above. Those go into
 * [unsupported] and the renderer says so under the drawing. A diagram type it
 * cannot lay out at all comes back as [MermaidKind.Unknown], which draws the
 * fence as a named card with its source instead.
 *
 * Pure Kotlin, no Compose types: the parse and the layout are both checked on
 * the host. [MermaidView] draws the result.
 */

/** Which of Mermaid's diagrams this is. */
enum class MermaidKind { Flowchart, Sequence, Unknown }

/** `flowchart TD` and friends. `TB` is Mermaid's synonym for `TD`. */
enum class MermaidDirection { Down, Up, Left, Right }

/** The node shapes the flowchart reader knows, and what each is drawn as. */
enum class MermaidShape { Box, Rounded, Stadium, Subroutine, Cylinder, Circle, Diamond, Hexagon, Flag }

/** One flowchart node, or one sequence-diagram participant. */
data class MermaidNode(
    val id: String,
    val label: String,
    val shape: MermaidShape = MermaidShape.Box,
)

/** One link between two nodes, or one message between two participants. */
data class MermaidEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    /** A `-.->` or a `-->>` reply: drawn as a dashed line. */
    val dashed: Boolean = false,
    /** A `==>`: drawn heavier. */
    val thick: Boolean = false,
    /** False for `---`, which is a line with no head. */
    val arrow: Boolean = true,
)

/** A parsed diagram: what it is, what is in it, and what was skipped. */
class MermaidDiagram(
    val kind: MermaidKind,
    val direction: MermaidDirection,
    val nodes: List<MermaidNode>,
    val edges: List<MermaidEdge>,
    /** Keywords met and not drawn, in the order they were first seen. */
    val unsupported: List<String>,
    /** The word the source opened with — what an [MermaidKind.Unknown] card is named after. */
    val declared: String,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    companion object {
        /** A fence longer than this is not a diagram anyone reads. */
        const val MAX_LINES = 500

        /** Bounds on a drawing, so a generated fence cannot cost a frame. */
        const val MAX_NODES = 200
        const val MAX_EDGES = 400

        /** Keywords that begin a construct this reader skips rather than draws. */
        private val SKIPPED = listOf(
            "subgraph", "end", "classDef", "class", "style", "linkStyle", "click",
            "direction", "note", "loop", "alt", "else", "opt", "par", "and", "rect",
            "critical", "break", "activate", "deactivate", "autonumber", "box",
        )

        /**
         * Read a ```mermaid fence's body, or null when there is nothing in it.
         *
         * Never throws and never runs long: the input is a code fence out of a
         * repository, so every loop here is bounded by a constant rather than
         * by the document.
         */
        fun parse(source: String): MermaidDiagram? {
            val lines = source.replace("\r\n", "\n").replace('\r', '\n')
                .split('\n')
                .take(MAX_LINES)
                .map { it.substringBefore("%%").trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val head = lines.first()
            val declared = head.substringBefore(' ').trim().removeSuffix(":")
            return when {
                declared.equals("flowchart", true) || declared.equals("graph", true) ->
                    parseFlowchart(head, lines.drop(1), declared)
                declared.equals("sequenceDiagram", true) ->
                    parseSequence(lines.drop(1), declared)
                else -> MermaidDiagram(
                    kind = MermaidKind.Unknown,
                    direction = MermaidDirection.Down,
                    nodes = emptyList(),
                    edges = emptyList(),
                    unsupported = listOf(declared),
                    declared = declared,
                )
            }
        }

        // ---- Flowcharts ---------------------------------------------------

        private fun parseFlowchart(
            head: String,
            body: List<String>,
            declared: String,
        ): MermaidDiagram {
            // `graph TD; A-->B;` is one line and three statements: the
            // declaration is only what precedes the first semicolon, and
            // whatever follows it is body like any other line.
            val declaration = head.substringBefore(';')
            val direction = when (declaration.substringAfter(' ', "").trim().uppercase()) {
                "BT" -> MermaidDirection.Up
                "LR" -> MermaidDirection.Right
                "RL" -> MermaidDirection.Left
                else -> MermaidDirection.Down
            }
            val trailing = head.substringAfter(';', "").trim()
            val nodes = LinkedHashMap<String, MermaidNode>()
            val edges = mutableListOf<MermaidEdge>()
            val skipped = LinkedHashSet<String>()
            for (line in (if (trailing.isEmpty()) body else listOf(trailing) + body)) {
                // Mermaid lets `;` end a statement, and a generated diagram
                // routinely puts several on one line.
                for (statement in line.split(';')) {
                    val text = statement.trim()
                    if (text.isEmpty()) continue
                    val keyword = text.substringBefore(' ').lowercase().trimEnd(':')
                    if (SKIPPED.any { it.lowercase() == keyword }) {
                        skipped.add(keyword)
                        continue
                    }
                    readFlowStatement(text, nodes, edges, skipped)
                }
            }
            return MermaidDiagram(
                kind = MermaidKind.Flowchart,
                direction = direction,
                nodes = nodes.values.toList(),
                edges = edges,
                unsupported = skipped.toList(),
                declared = declared,
            )
        }

        /**
         * One flowchart statement: a chain of node references separated by
         * links, as in `A[Start] --> B{Ok?} -- yes --> C`.
         *
         * Read left to right rather than split on a link pattern, because a
         * node's own label may contain anything at all — `A[a --> b]` is one
         * node, and splitting on `-->` makes it two halves of nothing.
         */
        private fun readFlowStatement(
            statement: String,
            nodes: LinkedHashMap<String, MermaidNode>,
            edges: MutableList<MermaidEdge>,
            skipped: MutableSet<String>,
        ) {
            var index = 0
            var previous: String? = null
            var pending: MermaidEdge? = null
            while (index < statement.length) {
                while (index < statement.length && statement[index].isWhitespace()) index++
                if (index >= statement.length) break
                val reference = readNodeReference(statement, index) ?: break
                index = reference.end
                val existing = nodes[reference.node.id]
                // A later mention with a label wins over a bare one: `A --> B`
                // then `B[Done]` is how half of all flowcharts are written.
                if (existing == null || (existing.label == existing.id && reference.labelled)) {
                    if (nodes.size < MAX_NODES || existing != null) {
                        nodes[reference.node.id] = reference.node
                    }
                }
                pending?.let {
                    if (edges.size < MAX_EDGES) {
                        edges.add(it.copy(from = previous ?: it.from, to = reference.node.id))
                    }
                }
                pending = null
                previous = reference.node.id
                while (index < statement.length && statement[index].isWhitespace()) index++
                val link = LINK.matchAt(statement, index)
                if (link == null) {
                    // Something in the middle of a statement that is neither a
                    // node nor a link: say so once rather than guess.
                    if (index < statement.length) skipped.add("link")
                    break
                }
                index = link.range.last + 1
                pending = edgeOf(link)
            }
        }

        /**
         * Mermaid's link operators, with both places a label can live: in the
         * middle (`-- text -->`) or after (`-->|text|`).
         *
         * The middle forms come first so `-- yes -->` is not read as a bare
         * `--` followed by the node `yes`.
         */
        private val LINK = Regex(
            "(?:" +
                "--\\s*([^|>\\n]*?)\\s*--+>" + "|" +
                "-\\.\\s*([^|>\\n]*?)\\s*\\.-+>" + "|" +
                "==\\s*([^|>\\n]*?)\\s*==+>" + "|" +
                "--\\s*([^|>\\n]+?)\\s*--+" + "|" +
                "(-\\.+->|-\\.+-|={2,}>|--[oxOX]|==[oxOX]|={2,}|-{2,}>|-{2,})" +
                ")" +
                "\\s*(?:\\|\\s*([^|]*?)\\s*\\|)?"
        )

        /** The edge one [LINK] match describes, label and line style included. */
        private fun edgeOf(match: MatchResult): MermaidEdge {
            val middle = (1..4).firstNotNullOfOrNull { match.groupValues[it].ifBlank { null } }
            val trailing = match.groupValues[6].ifBlank { null }
            // The operator without its trailing `|label|`, which is the only
            // part that says how the line is drawn.
            val stem = match.groupValues[5].ifEmpty { match.value.substringBefore('|') }.trimEnd()
            return MermaidEdge(
                from = "",
                to = "",
                label = trailing ?: middle,
                dashed = stem.startsWith("-."),
                thick = stem.startsWith("=="),
                // `---` and `-.-` are lines without a head; `-->`, `--o` and
                // `--x` all point somewhere.
                arrow = '>' in stem || stem.last().lowercaseChar() in setOf('o', 'x'),
            )
        }

        /** A node reference and where it ended. */
        private class NodeReference(val node: MermaidNode, val end: Int, val labelled: Boolean)

        /**
         * The bracket pairs Mermaid's node shapes use, longest opener first —
         * `[[` has to be tried before `[`, or every subroutine is a box whose
         * label starts with `[`.
         */
        private val SHAPES = listOf(
            Triple("[[", "]]", MermaidShape.Subroutine),
            Triple("[(", ")]", MermaidShape.Cylinder),
            Triple("([", "])", MermaidShape.Stadium),
            Triple("((", "))", MermaidShape.Circle),
            Triple("{{", "}}", MermaidShape.Hexagon),
            Triple("[", "]", MermaidShape.Box),
            Triple("(", ")", MermaidShape.Rounded),
            Triple("{", "}", MermaidShape.Diamond),
            Triple(">", "]", MermaidShape.Flag),
        )

        private fun readNodeReference(text: String, start: Int): NodeReference? {
            var index = start
            while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_')) {
                index++
            }
            if (index == start) return null
            val id = text.substring(start, index)
            for ((open, close, shape) in SHAPES) {
                if (!text.startsWith(open, index)) continue
                val bodyStart = index + open.length
                val bodyEnd = text.indexOf(close, bodyStart)
                if (bodyEnd < 0) continue
                val label = text.substring(bodyStart, bodyEnd).trim().trim('"')
                return NodeReference(
                    MermaidNode(id, label.ifEmpty { id }, shape),
                    bodyEnd + close.length,
                    labelled = true,
                )
            }
            return NodeReference(MermaidNode(id, id, MermaidShape.Box), index, labelled = false)
        }

        // ---- Sequence diagrams --------------------------------------------

        /** `A->>B: text`, and every other arrow Mermaid gives a message. */
        private val MESSAGE = Regex(
            "^([A-Za-z0-9_][A-Za-z0-9_ ]*?)\\s*" +
                "(-{1,2}>>|-{1,2}>|--?\\)|-{1,2}[xX])\\s*" +
                "([A-Za-z0-9_][A-Za-z0-9_ ]*?)\\s*(?::\\s*(.*))?$"
        )

        private fun parseSequence(body: List<String>, declared: String): MermaidDiagram {
            val nodes = LinkedHashMap<String, MermaidNode>()
            val edges = mutableListOf<MermaidEdge>()
            val skipped = LinkedHashSet<String>()
            fun participant(id: String, label: String? = null) {
                val trimmed = id.trim()
                if (trimmed.isEmpty()) return
                val existing = nodes[trimmed]
                if (existing == null && nodes.size >= MAX_NODES) return
                if (existing == null || (label != null && existing.label == existing.id)) {
                    nodes[trimmed] = MermaidNode(trimmed, label?.trim() ?: trimmed)
                }
            }
            for (line in body) {
                val keyword = line.substringBefore(' ').lowercase().trimEnd(':')
                if (keyword == "participant" || keyword == "actor") {
                    val rest = line.substringAfter(' ').trim()
                    // `participant A as Alice`, and `as` is case-insensitive.
                    val split = Regex("\\s+as\\s+", RegexOption.IGNORE_CASE).split(rest, limit = 2)
                    participant(split[0], split.getOrNull(1))
                    continue
                }
                val message = MESSAGE.find(line)
                if (message == null) {
                    if (SKIPPED.any { it.lowercase() == keyword }) skipped.add(keyword)
                    continue
                }
                val (from, arrow, to) = message.destructured.toList()
                participant(from)
                participant(to)
                if (edges.size < MAX_EDGES) {
                    edges.add(
                        MermaidEdge(
                            from = from.trim(),
                            to = to.trim(),
                            label = message.groupValues[4].trim().ifEmpty { null },
                            // Mermaid's reply arrows are the `--` ones.
                            dashed = arrow.startsWith("--"),
                            arrow = true,
                        )
                    )
                }
            }
            return MermaidDiagram(
                kind = MermaidKind.Sequence,
                direction = MermaidDirection.Down,
                nodes = nodes.values.toList(),
                edges = edges,
                unsupported = skipped.toList(),
                declared = declared,
            )
        }
    }
}
