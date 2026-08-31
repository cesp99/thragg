package to.eyed.seeker.code.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extracted reader, against what an *agent* actually writes.
 *
 * Ported from `test/ui/preview/MarkdownDocumentTest.kt` — the inline half of
 * it, plus the block kinds `ui/common/MarkdownText.kt` still reads (fences,
 * lists, quotes, rules, headings) and none of the ones it dropped with the
 * preview (tables, display math, source ranges, relative link targets).
 *
 * The cases are the ones that go wrong *quietly* rather than loudly: a `---`
 * that is a rule in one place and a heading underline in another, a
 * `snake_case` identifier that must not turn italic, a nested list that must
 * not flatten, and a fence whose contents must survive being anything at all.
 * The degenerate ones at the bottom exist because this reader's input is a
 * model's output: nobody checked it, and it must not be able to kill the
 * process.
 */
class MarkdownTextTest {

    private fun text(spans: List<MarkdownSpan>): String = spans.joinToString("") { it.text }

    private fun paragraphs(source: String): List<MarkdownNode.Paragraph> =
        parseMarkdownText(source).filterIsInstance<MarkdownNode.Paragraph>()

    private fun blockText(blocks: List<MarkdownNode>): String = blocks.joinToString(" ") { block ->
        when (block) {
            is MarkdownNode.Heading -> text(block.content)
            is MarkdownNode.Paragraph -> text(block.content)
            is MarkdownNode.Code -> block.code
            is MarkdownNode.Quote -> blockText(block.blocks)
            is MarkdownNode.Bullets -> block.items.joinToString(" ") { blockText(it.blocks) }
            MarkdownNode.Rule -> "---"
        }
    }

    // ---- Inline ----------------------------------------------------------

    @Test
    fun `emphasis, strong, strike and code spans`() {
        val spans = paragraphs("*a* **b** ~~c~~ `d`").single().content
        val styled = spans.filter { it.styles.isNotEmpty() }
        assertEquals(
            listOf(
                setOf(MarkdownStyle.Italic),
                setOf(MarkdownStyle.Bold),
                setOf(MarkdownStyle.Strikethrough),
                setOf(MarkdownStyle.Code),
            ),
            styled.map { it.styles },
        )
        assertEquals(listOf("a", "b", "c", "d"), styled.map { it.text })
    }

    @Test
    fun `nested emphasis keeps both styles`() {
        val spans = paragraphs("**bold *and italic***").single().content
        assertTrue(
            spans.any {
                it.styles == setOf(MarkdownStyle.Bold, MarkdownStyle.Italic) &&
                    it.text == "and italic"
            }
        )
    }

    @Test
    fun `an underscore inside a word is not emphasis`() {
        val spans = paragraphs("call snake_case_name here").single().content
        assertEquals("call snake_case_name here", text(spans))
        assertTrue(spans.none { MarkdownStyle.Italic in it.styles })
    }

    @Test
    fun `a lone asterisk with spaces around it stays literal`() {
        assertEquals("2 * 3 * 4", text(paragraphs("2 * 3 * 4").single().content))
    }

    @Test
    fun `markup inside a code span is left alone`() {
        val spans = paragraphs("use `a_*b*_c` here").single().content
        val code = spans.single { MarkdownStyle.Code in it.styles }
        assertEquals("a_*b*_c", code.text)
    }

    /**
     * The reason the `$` scan survived the extraction: without it `$a_i * b_j$`
     * is read as emphasis and a stray underscore, and a shell snippet's `$5`
     * must not become a formula either.
     */
    @Test
    fun `math is a run of its own and a price is not`() {
        val math = paragraphs("the ratio ${'$'}a_i * b_j${'$'} holds").single().content
            .single { MarkdownStyle.Math in it.styles }
        assertEquals("a_i * b_j", math.text)
        val money = paragraphs("${'$'}5 and ${'$'}6 each").single().content
        assertEquals("${'$'}5 and ${'$'}6 each", text(money))
        assertTrue(money.none { MarkdownStyle.Math in it.styles })
    }

    @Test
    fun `links inline, by reference and bare`() {
        val source = """
            See [docs](https://example.com/a), [other][ref] and https://example.com/b.

            [ref]: https://example.com/c
        """.trimIndent()
        val spans = paragraphs(source).single().content
        val links = spans.filter { it.link != null }
        assertEquals(
            listOf("https://example.com/a", "https://example.com/c", "https://example.com/b"),
            links.map { it.link },
        )
        // The full stop belongs to the sentence, not to the bare URL.
        assertEquals("https://example.com/b", links.last().text)
    }

    /**
     * The agent panel passes `onOpenPath` as its link handler, so a link to a
     * file in the project has to arrive as a link at all.
     */
    @Test
    fun `a link to a project file keeps its destination`() {
        val span = paragraphs("edited [state.rs](src/state.rs) for you").single().content
            .single { it.link != null }
        assertEquals("src/state.rs", span.link)
        assertEquals("state.rs", span.text)
    }

    @Test
    fun `a link with an empty half shows text rather than syntax`() {
        val empty = paragraphs("[text]()").single().content
        assertEquals("text", text(empty))
        assertTrue(empty.none { it.link != null })

        val unlabelled = paragraphs("[](https://x.com)").single().content.single()
        assertEquals("https://x.com", unlabelled.text)
        assertEquals("https://x.com", unlabelled.link)
    }

    @Test
    fun `an image becomes a run that names itself and fetches nothing`() {
        val image = paragraphs("![a badge](https://img.example/x.svg)").single().content.single()
        assertTrue(image.isImage)
        assertEquals("a badge", image.text)
        assertEquals("https://img.example/x.svg", image.link)
    }

    @Test
    fun `an image wrapped in a link keeps the link`() {
        val badge =
            paragraphs("[![Build](https://img.shields.io/b.svg)](https://ci.example.com/job)")
                .single().content.single()
        assertTrue(badge.isImage)
        assertEquals("Build", badge.text)
        assertEquals("https://ci.example.com/job", badge.link)
    }

    @Test
    fun `html tags are dropped and a br becomes a line break`() {
        val spans = paragraphs("<p align=\"center\">one<br/>two</p>").single().content
        assertEquals("one\ntwo", text(spans))
    }

    // ---- Blocks the renderer still draws ---------------------------------

    @Test
    fun `headings come in both spellings`() {
        val blocks = parseMarkdownText(
            """
            # Title
            ### Third
            Setext
            ======
            Second
            ------
            """.trimIndent()
        )
        val headings = blocks.filterIsInstance<MarkdownNode.Heading>()
        assertEquals(listOf(1, 3, 1, 2), headings.map { it.level })
        assertEquals(listOf("Title", "Third", "Setext", "Second"), headings.map { text(it.content) })
    }

    @Test
    fun `a dashed line is a rule with no paragraph open and a heading with one`() {
        assertEquals(listOf(MarkdownNode.Rule), parseMarkdownText("---"))
        val blocks = parseMarkdownText("Heading\n---\n")
        assertEquals(1, blocks.size)
        assertEquals(2, (blocks[0] as MarkdownNode.Heading).level)
    }

    @Test
    fun `a paragraph's lines join and a hard break survives`() {
        assertEquals("one two", text(paragraphs("one\ntwo").single().content))
        assertEquals("one\ntwo", text(paragraphs("one  \ntwo").single().content))
    }

    @Test
    fun `a fenced block keeps its language and its contents verbatim`() {
        val block = parseMarkdownText(
            """
            ```rust
            fn main() {
                // *not* emphasis, and `not` a code span
            }
            ```
            """.trimIndent()
        ).single() as MarkdownNode.Code
        assertEquals("rust", block.language)
        assertEquals(
            "fn main() {\n    // *not* emphasis, and `not` a code span\n}",
            block.code,
        )
    }

    @Test
    fun `an unterminated fence still ends at the end of the message`() {
        val block = parseMarkdownText("```\nstill streaming").single() as MarkdownNode.Code
        assertEquals("still streaming", block.code)
    }

    @Test
    fun `a link definition inside a fence is not a definition`() {
        val blocks = parseMarkdownText(
            """
            ```
            [ref]: https://example.com/in-a-fence
            ```

            [ref] is not a link.
            """.trimIndent()
        )
        val paragraph = blocks.filterIsInstance<MarkdownNode.Paragraph>().single()
        assertTrue(paragraph.content.none { it.link != null })
    }

    @Test
    fun `lists nest instead of flattening`() {
        val list = parseMarkdownText(
            """
            - one
              - inner
            - two
            """.trimIndent()
        ).single() as MarkdownNode.Bullets
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks.filterIsInstance<MarkdownNode.Bullets>().single()
        assertEquals(1, nested.items.size)
        assertEquals("inner", blockText(nested.items[0].blocks).trim())
    }

    @Test
    fun `an ordered list counts from its own first number`() {
        val list = parseMarkdownText("3. c\n4. d").single() as MarkdownNode.Bullets
        assertTrue(list.ordered)
        assertEquals(listOf("3.", "4."), list.items.map { it.marker })
    }

    @Test
    fun `task items carry their checkbox and lose its text`() {
        val list = parseMarkdownText("- [x] done\n- [ ] todo").single() as MarkdownNode.Bullets
        assertEquals(listOf(true, false), list.items.map { it.checked })
        assertEquals(listOf("done", "todo"), list.items.map { blockText(it.blocks).trim() })
    }

    @Test
    fun `a fence inside a list item stays inside it`() {
        val list = parseMarkdownText(
            """
            - run this:

              ```sh
              cargo build-sbf
              ```
            """.trimIndent()
        ).single() as MarkdownNode.Bullets
        val fence = list.items.single().blocks.filterIsInstance<MarkdownNode.Code>().single()
        assertEquals("cargo build-sbf", fence.code)
    }

    @Test
    fun `a block quote nests its blocks and picks up a GitHub alert`() {
        val quote = parseMarkdownText("> [!WARNING]\n> keys are not synced")
            .single() as MarkdownNode.Quote
        assertEquals("WARNING", quote.kind)
        assertEquals("keys are not synced", blockText(quote.blocks).trim())
    }

    @Test
    fun `a fence under a quote is not swallowed by its lazy continuation`() {
        val blocks = parseMarkdownText("> a quote\n```rust\nfn main() {}\n```")
        val quote = blocks[0] as MarkdownNode.Quote
        assertTrue(quote.blocks.none { it is MarkdownNode.Code })
        assertEquals("fn main() {}", (blocks[1] as MarkdownNode.Code).code)
    }

    @Test
    fun `an indented block is code, but an indented continuation line is not`() {
        val code = parseMarkdownText("    fn main() {}").single() as MarkdownNode.Code
        assertEquals("fn main() {}", code.code)
        val paragraph = paragraphs("a sentence\n    wrapped by four spaces").single()
        assertEquals("a sentence wrapped by four spaces", text(paragraph.content))
    }

    /**
     * A table is no longer laid out — that machinery stayed in `ui/preview` —
     * but its text must still survive, one row per paragraph line, rather than
     * disappearing or throwing.
     */
    @Test
    fun `a table is read as prose rather than dropped`() {
        val blocks = parseMarkdownText("| a | b |\n|---|---|\n| 1 | 2 |")
        assertTrue(blockText(blocks).contains("1"))
        assertTrue(blockText(blocks).contains("b"))
    }

    // ---- Degenerate input ------------------------------------------------

    @Test
    fun `half-typed markup parses instead of throwing`() {
        val cases = listOf(
            "[", "]", "![", "![]", "[]()", "[a](", "[a][", "[a][b]", "[]: ",
            "`", "``", "```", "~~", "~~~", "*", "**", "***", "****", "_", "__",
            "<", ">", "<>", "</>", "<a", "|", "|-", "|---|", "| a |", "- ", "-",
            "1.", "1. ", "> ", ">", "#", "######", "#######", "    ",
            "\\", "\\\\", " ", "🙂 **bold 🙂**",
            "- a\n  - b\n    - c\n      - d\n        - e",
            "> > > deep\n> > > quote",
            "```\n```\n```\n",
        )
        for (case in cases) {
            // A blank-only message is legitimately no blocks; everything else
            // has to produce something. A reply is parsed on every frame while
            // it streams, so every one of these is a *prefix* the reader is
            // handed for real.
            val blocks = parseMarkdownText(case)
            assertTrue(
                "threw nothing but produced nothing for <$case>",
                case.isBlank() || blocks.isNotEmpty(),
            )
        }
    }

    @Test
    fun `an empty message is no blocks rather than one empty paragraph`() {
        assertEquals(emptyList<MarkdownNode>(), parseMarkdownText(""))
        assertEquals(emptyList<MarkdownNode>(), parseMarkdownText("\n\n   \n"))
    }

    /**
     * Parses on a 512 KB stack, which is the order of an Android worker
     * thread's — the host JVM's default is several times larger and hides
     * exactly the overflow this is looking for.
     */
    private fun parseOnSmallStack(source: String): List<MarkdownNode> {
        var blocks: List<MarkdownNode> = emptyList()
        var failure: Throwable? = null
        val thread = Thread(
            null,
            {
                try {
                    blocks = parseMarkdownText(source)
                } catch (error: Throwable) {
                    failure = error
                }
            },
            "markdown-parse",
            512L * 1024,
        )
        thread.start()
        thread.join()
        failure?.let { throw AssertionError("parse died on a 512 KB stack", it) }
        return blocks
    }

    private fun quoteDepth(blocks: List<MarkdownNode>): Int = blocks.maxOfOrNull { block ->
        when (block) {
            is MarkdownNode.Quote -> 1 + quoteDepth(block.blocks)
            is MarkdownNode.Bullets -> block.items.maxOfOrNull { quoteDepth(it.blocks) } ?: 0
            else -> 0
        }
    } ?: 0

    @Test
    fun `nesting past the depth limit is drawn rather than descended into`() {
        val quotes = parseOnSmallStack(">".repeat(1500) + " deep")
        assertTrue(quotes.isNotEmpty())
        val depth = quoteDepth(quotes)
        assertTrue("quote nesting reached $depth", depth <= 32)
        // The text is still all there, it has simply stopped being structured.
        assertTrue(blockText(quotes).contains("deep"))
    }

    @Test
    fun `degenerate nesting of every kind survives a worker thread's stack`() {
        val cases = listOf(
            ">".repeat(5000) + " deep",
            "> ".repeat(5000) + "deep",
            "- ".repeat(2000) + "deep",
            "*".repeat(3000) + "a",
            "~~".repeat(2000) + "a",
            "[".repeat(2000) + "a",
            "![".repeat(2000) + "a",
            "<".repeat(2000) + "a",
            "`".repeat(2000) + "a",
        )
        for (case in cases) {
            assertTrue(
                "produced nothing for a ${case.length}-character degenerate document",
                parseOnSmallStack(case).isNotEmpty(),
            )
        }
    }

    /**
     * The reviewer's measurements, as timeouts. Before the scan window and the
     * inline-length cap these took 23 s and 3.7 s on a desktop JVM — which is
     * half a minute on a phone, per streamed chunk, on the shared background
     * pool.
     */
    @Test(timeout = 5_000)
    fun `a paragraph of unmatched emphasis openers does not go quadratic`() {
        val paragraph = paragraphs("*a ".repeat(80_000)).single()
        assertTrue(text(paragraph.content).startsWith("*a *a"))
    }

    @Test(timeout = 2_000)
    fun `a paragraph of glob patterns does not go quadratic`() {
        val globs = (1..20_000).joinToString(" ") { "*.ext$it" }
        assertTrue(text(paragraphs(globs).single().content).startsWith("*.ext1 *.ext2"))
    }

    @Test(timeout = 5_000)
    fun `unmatched openers inside a readable paragraph stay bounded`() {
        assertTrue(paragraphs("~~a ".repeat(8_000)).single().content.isNotEmpty())
    }

    // ---- The streaming throttle ------------------------------------------
    //
    // Carried over from test/ui/preview/MarkdownThrottleTest.kt: the panel's
    // one real-time property. A debounce here starves — a reply that changes
    // every 120 ms against a 180 ms wait never renders until the agent stops
    // talking — so the wait is only ever the remainder.

    @Test
    fun `a first parse and one after a quiet spell are immediate`() {
        assertEquals(0L, markdownParseDelay(sinceLastParse = 10_000, interval = 180))
        assertEquals(0L, markdownParseDelay(sinceLastParse = 180, interval = 180))
        assertEquals(0L, markdownParseDelay(sinceLastParse = 500, interval = 180))
    }

    @Test
    fun `a parse too soon waits only for the remainder`() {
        assertEquals(60L, markdownParseDelay(sinceLastParse = 120, interval = 180))
        assertEquals(180L, markdownParseDelay(sinceLastParse = 0, interval = 180))
    }

    @Test
    fun `a backwards clock parses now`() {
        assertEquals(0L, markdownParseDelay(sinceLastParse = -5, interval = 180))
    }

    /**
     * The defect itself, as a property: text arriving faster than the interval
     * must still parse, at a bounded staleness, forever.
     */
    @Test
    fun `text that never stops arriving still parses at a bounded interval`() {
        val interval = 180L
        val arrivalEvery = 120L
        var now = 0L
        var lastParsed = -interval
        var parses = 0
        var worstStaleness = 0L
        repeat(20) {
            now += arrivalEvery
            val wait = markdownParseDelay(now - lastParsed, interval)
            val parseAt = now + wait
            worstStaleness = maxOf(worstStaleness, parseAt - now)
            lastParsed = parseAt
            parses++
        }
        assertEquals(20, parses)
        assertTrue("staleness reached $worstStaleness", worstStaleness <= interval)
    }
}
