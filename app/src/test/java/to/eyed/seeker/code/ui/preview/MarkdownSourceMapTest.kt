package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source-line mapping the preview's scroll sync is built on, and the
 * anchors a `#link` follows.
 *
 * Both are the sort of thing that is wrong by one and stays wrong for months:
 * a preview that lands on the block *after* the one you are editing looks like
 * a rendering delay rather than an off-by-one, and a `#anchor` that scrolls
 * nowhere looks like the link is broken rather than the slug.
 */
class MarkdownSourceMapTest {

    /** A dollar sign, written so no Kotlin string template can read it. */
    private val D = '\u0024'.toString()

    private fun ranges(source: String) = parseMarkdownDocument(source).lineRanges

    @Test
    fun `every top-level block knows the lines it came from`() {
        val source = """
            # Title

            First paragraph.

            ```rust
            fn main() {}
            ```

            - one
            - two
        """.trimIndent()
        val parsed = parseMarkdownDocument(source)
        assertEquals(4, parsed.blocks.size)
        assertEquals(listOf(0, 2, 4, 8), parsed.lineRanges.map { it.first })
        // The heading is one line; the fence is its opener, body and closer.
        assertEquals(0..0, parsed.lineRanges[0])
        assertEquals(4..6, parsed.lineRanges[2])
    }

    /**
     * A blank line belongs to the block above it, so scrolling through the
     * gap between two paragraphs does not make the preview jump ahead.
     */
    @Test
    fun `a line between blocks belongs to the block above`() {
        val lineRanges = ranges("alpha\n\nbeta\n")
        assertEquals(0, blockAtSourceLine(lineRanges, 0))
        assertEquals(0, blockAtSourceLine(lineRanges, 1))
        assertEquals(1, blockAtSourceLine(lineRanges, 2))
        // Past the end is still the last block, not a crash.
        assertEquals(1, blockAtSourceLine(lineRanges, 99))
    }

    @Test
    fun `a document with nothing in it maps every line to zero`() {
        assertEquals(0, blockAtSourceLine(emptyList(), 0))
        assertEquals(0, blockAtSourceLine(emptyList(), 40))
        assertEquals(0, sourceLineOfBlock(emptyList(), 3))
    }

    /** A list is one block, however many lines and items it has. */
    @Test
    fun `a multi-line block claims all of its lines`() {
        val source = """
            intro

            - one
              still one
            - two

            outro
        """.trimIndent()
        val lineRanges = ranges(source)
        assertEquals(3, lineRanges.size)
        for (line in 2..4) assertEquals(1, blockAtSourceLine(lineRanges, line))
        assertEquals(2, blockAtSourceLine(lineRanges, 6))
        assertEquals(6, sourceLineOfBlock(lineRanges, 2))
    }

    /** The ranges only ever move forward, which is what the binary search needs. */
    @Test
    fun `ranges are in source order`() {
        val source = buildString {
            repeat(40) { index ->
                append("## Heading $index\n\ntext $index\n\n")
            }
        }
        val lineRanges = ranges(source)
        assertTrue(lineRanges.zipWithNext().all { (a, b) -> a.first < b.first })
    }

    @Test
    fun `github's heading slugs`() {
        assertEquals("getting-started", headingSlug("Getting Started"))
        assertEquals("whats-new-in-020", headingSlug("What's new in 0.2.0?"))
        assertEquals("c-and-c", headingSlug("C++ and C#"))
        assertEquals("a_b-c", headingSlug("a_b c"))
    }

    @Test
    fun `an anchor finds its heading`() {
        val blocks = parseMarkdown(
            """
            # Seeker IDE

            text

            ## Getting Started

            more
            """.trimIndent()
        )
        assertEquals(2, anchorBlockIndex(blocks, "#getting-started"))
        assertEquals(0, anchorBlockIndex(blocks, "#seeker-ide"))
        assertNull(anchorBlockIndex(blocks, "#nothing-here"))
        assertNull(anchorBlockIndex(blocks, "#"))
    }

    // ---- Math ------------------------------------------------------------

    @Test
    fun `a dollar block is math, not a paragraph`() {
        val blocks = parseMarkdown(
            """
            before

            ${'$'}${'$'}
            E = mc^2
            ${'$'}${'$'}

            after
            """.trimIndent()
        )
        val math = blocks.filterIsInstance<MarkdownBlock.Math>().single()
        assertEquals("E = mc^2", math.source)
        assertEquals(3, blocks.size)
    }

    @Test
    fun `a one-line dollar block closes itself`() {
        val fence = D + D
        val math = parseMarkdown(fence + "x^2 + y^2" + fence).single() as MarkdownBlock.Math
        assertEquals("x^2 + y^2", math.source)
    }

    /** An unclosed `$$` must not swallow the rest of the file. */
    @Test
    fun `an unclosed dollar block stays prose`() {
        val blocks = parseMarkdown(D + D + "\nnot really math\n\nanother paragraph\n")
        assertTrue(blocks.none { it is MarkdownBlock.Math })
        assertEquals(2, blocks.size)
    }

    @Test
    fun `inline math keeps its source and its underscores`() {
        val spans = parseInline("the sum " + D + "a_i * b_j" + D + " is small", emptyMap())
        val math = spans.single { InlineStyle.Math in it.styles }
        assertEquals("a_i * b_j", math.text)
        // No emphasis was read out of the formula on the way past.
        assertTrue(spans.none { InlineStyle.Italic in it.styles })
    }

    @Test
    fun `money is not math`() {
        val spans = parseInline("it costs " + D + "5 and " + D + "10 more", emptyMap())
        assertTrue(spans.none { InlineStyle.Math in it.styles })
        assertEquals("it costs " + D + "5 and " + D + "10 more", spans.joinToString("") { it.text })
    }

    // ---- Images ----------------------------------------------------------

    @Test
    fun `an image keeps its own source under an enclosing link`() {
        val spans = parseInline("[![build](badges/ci.svg)](https://ci.example)", emptyMap())
        val image = spans.single { it.isImage }
        assertEquals("build", image.text)
        assertEquals("badges/ci.svg", image.imageSource)
        // The tap still means the job, not the badge.
        assertEquals("https://ci.example", image.link)
    }

    @Test
    fun `a bare image carries its source and its link`() {
        val image = parseInline("![shot](docs/shot.png)", emptyMap()).single()
        assertEquals("docs/shot.png", image.imageSource)
        assertEquals("docs/shot.png", image.link)
    }

    @Test
    fun `a badge row is one image segment`() {
        val spans = parseInline(
            "![a](a.png) ![b](b.png)",
            emptyMap(),
        )
        val segments = inlineSegments(spans) { true }
        assertEquals(1, segments.size)
        assertEquals(2, (segments.single() as InlineSegment.Images).spans.size)
    }

    @Test
    fun `prose around an image is split from it`() {
        val spans = parseInline("see ![a](a.png) here", emptyMap())
        val segments = inlineSegments(spans) { true }
        assertEquals(3, segments.size)
        assertTrue(segments[0] is InlineSegment.Prose)
        assertTrue(segments[1] is InlineSegment.Images)
        assertTrue(segments[2] is InlineSegment.Prose)
    }

    /** An image nothing can draw stays in the prose, as it always did. */
    @Test
    fun `an undrawable image is left in the text`() {
        val spans = parseInline("see ![a](https://x/a.png) here", emptyMap())
        val segments = inlineSegments(spans) { false }
        assertEquals(1, segments.size)
        assertTrue(segments.single() is InlineSegment.Prose)
    }
}
