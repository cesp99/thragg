package to.eyed.thragg.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader, against what a README actually contains.
 *
 * The cases here are the ones that go wrong quietly rather than loudly: a
 * `---` that is a rule in one place and a heading underline in another,
 * `snake_case` that must not turn italic, a nested list that must not flatten,
 * and a fence whose contents must survive being anything at all.
 */
class MarkdownDocumentTest {

    private fun text(spans: List<InlineSpan>): String = spans.joinToString("") { it.text }

    private fun paragraphs(source: String): List<MarkdownBlock.Paragraph> =
        parseMarkdown(source).filterIsInstance<MarkdownBlock.Paragraph>()

    @Test
    fun `headings come in both spellings`() {
        val blocks = parseMarkdown(
            """
            # Title
            ### Third
            Setext
            ======
            Second
            ------
            """.trimIndent()
        )
        val headings = blocks.filterIsInstance<MarkdownBlock.Heading>()
        assertEquals(listOf(1, 3, 1, 2), headings.map { it.level })
        assertEquals(listOf("Title", "Third", "Setext", "Second"), headings.map { text(it.content) })
    }

    @Test
    fun `a dashed line is a rule with no paragraph open and a heading with one`() {
        assertEquals(
            listOf(MarkdownBlock.Rule),
            parseMarkdown("---")
        )
        val blocks = parseMarkdown("Heading\n---\n")
        assertEquals(1, blocks.size)
        assertEquals(2, (blocks[0] as MarkdownBlock.Heading).level)
    }

    @Test
    fun `a paragraph's lines join and a hard break survives`() {
        val paragraph = paragraphs("one\ntwo").single()
        assertEquals("one two", text(paragraph.content))
        val broken = paragraphs("one  \ntwo").single()
        assertEquals("one\ntwo", text(broken.content))
    }

    @Test
    fun `emphasis, strong, strike and code spans`() {
        val spans = paragraphs("*a* **b** ~~c~~ `d`").single().content
        val styled = spans.filter { it.styles.isNotEmpty() }
        assertEquals(
            listOf(
                setOf(InlineStyle.Italic),
                setOf(InlineStyle.Bold),
                setOf(InlineStyle.Strikethrough),
                setOf(InlineStyle.Code),
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
                it.styles == setOf(InlineStyle.Bold, InlineStyle.Italic) && it.text == "and italic"
            }
        )
    }

    @Test
    fun `an underscore inside a word is not emphasis`() {
        val spans = paragraphs("call snake_case_name here").single().content
        assertEquals("call snake_case_name here", text(spans))
        assertTrue(spans.none { InlineStyle.Italic in it.styles })
    }

    @Test
    fun `a lone asterisk with spaces around it stays literal`() {
        val spans = paragraphs("2 * 3 * 4").single().content
        assertEquals("2 * 3 * 4", text(spans))
    }

    @Test
    fun `markup inside a code span is left alone`() {
        val spans = paragraphs("use `a_*b*_c` here").single().content
        val code = spans.single { InlineStyle.Code in it.styles }
        assertEquals("a_*b*_c", code.text)
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

    @Test
    fun `an image becomes a run that names itself and fetches nothing`() {
        val spans = paragraphs("![a badge](https://img.example/x.svg)").single().content
        val image = spans.single()
        assertTrue(image.isImage)
        assertEquals("a badge", image.text)
        assertEquals("https://img.example/x.svg", image.link)
    }

    @Test
    fun `html tags are dropped and a br becomes a line break`() {
        val spans = paragraphs("<p align=\"center\">one<br/>two</p>").single().content
        assertEquals("one\ntwo", text(spans))
    }

    @Test
    fun `a fenced block keeps its language and its contents verbatim`() {
        val block = parseMarkdown(
            """
            ```rust
            fn main() {
                // # not a heading
            }
            ```
            """.trimIndent()
        ).single() as MarkdownBlock.Code
        assertEquals("rust", block.language)
        assertEquals("fn main() {\n    // # not a heading\n}", block.code)
    }

    @Test
    fun `an unterminated fence still ends at the end of the file`() {
        val block = parseMarkdown("```\nstuff\n").single() as MarkdownBlock.Code
        assertEquals("stuff\n", block.code)
    }

    @Test
    fun `a link definition inside a fence is not a definition`() {
        val spans = paragraphs(
            """
            ```
            [ref]: https://example.com/inside
            ```

            [ref] alone
            """.trimIndent()
        ).single().content
        assertTrue(spans.none { it.link != null })
    }

    @Test
    fun `lists nest instead of flattening`() {
        val list = parseMarkdown(
            """
            - one
              - inner
            - two
            """.trimIndent()
        ).single() as MarkdownBlock.Bullets
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks.filterIsInstance<MarkdownBlock.Bullets>().single()
        assertEquals(1, nested.items.size)
        assertEquals(
            "inner",
            text((nested.items[0].blocks.single() as MarkdownBlock.Paragraph).content),
        )
    }

    @Test
    fun `an ordered list counts from its own first number`() {
        val list = parseMarkdown("3. c\n4. d").single() as MarkdownBlock.Bullets
        assertTrue(list.ordered)
        assertEquals(listOf("3.", "4."), list.items.map { it.marker })
    }

    @Test
    fun `task items carry their checkbox and lose its text`() {
        val list = parseMarkdown("- [x] done\n- [ ] todo").single() as MarkdownBlock.Bullets
        assertEquals(listOf(true, false), list.items.map { it.checked })
        assertEquals(
            "done",
            text((list.items[0].blocks.single() as MarkdownBlock.Paragraph).content),
        )
    }

    @Test
    fun `a blank line between items makes the list loose but keeps it one list`() {
        val list = parseMarkdown("- one\n\n- two").single() as MarkdownBlock.Bullets
        assertEquals(2, list.items.size)
        assertTrue(!list.tight)
    }

    @Test
    fun `a fence inside a list item stays inside it`() {
        val list = parseMarkdown(
            """
            - run this:

              ```sh
              ls -la
              ```
            """.trimIndent()
        ).single() as MarkdownBlock.Bullets
        val code = list.items.single().blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("sh", code.language)
        assertEquals("ls -la", code.code)
    }

    @Test
    fun `a block quote nests its blocks and picks up a GitHub alert`() {
        val quote = parseMarkdown("> [!WARNING]\n> mind the gap").single() as MarkdownBlock.Quote
        assertEquals("WARNING", quote.kind)
        assertEquals(
            "mind the gap",
            text((quote.blocks.single() as MarkdownBlock.Paragraph).content),
        )
    }

    @Test
    fun `a table reads its header, alignments and rows`() {
        val table = parseMarkdown(
            """
            | Shortcut | Action |
            |---|:---:|
            | `Ctrl` `G` | Go to line |
            """.trimIndent()
        ).single() as MarkdownBlock.Table
        assertEquals(listOf("Shortcut", "Action"), table.header.map(::text))
        assertEquals(listOf(ColumnAlignment.Start, ColumnAlignment.Center), table.alignments)
        assertEquals(1, table.rows.size)
        assertEquals("Go to line", text(table.rows[0][1]))
    }

    @Test
    fun `a table straight after a paragraph line still becomes a table`() {
        val blocks = parseMarkdown("intro\n\n| a | b |\n| - | - |\n| 1 | 2 |")
        assertTrue(blocks[1] is MarkdownBlock.Table)
    }

    @Test
    fun `an indented block is code, but an indented continuation line is not`() {
        val code = parseMarkdown("# Title\n\n    indented()").last() as MarkdownBlock.Code
        assertEquals("indented()", code.code)
        assertNull(code.language)
        val paragraph = paragraphs("a sentence\n    that wrapped").single()
        assertEquals("a sentence that wrapped", text(paragraph.content))
    }

    @Test
    fun `a relative link resolves against the file and cannot leave the project`() {
        assertEquals("docs/USERLAND.md", resolveRelativePath("README.md", "docs/USERLAND.md"))
        assertEquals("docs/BUILDING.md", resolveRelativePath("docs/SHORTCUTS.md", "BUILDING.md"))
        assertEquals("README.md", resolveRelativePath("docs/SHORTCUTS.md", "../README.md"))
        assertEquals("etc/passwd", resolveRelativePath("README.md", "../../../etc/passwd"))
        assertEquals("docs/A.md", resolveRelativePath("README.md", "docs/A.md#heading"))
    }

    /**
     * Half-typed markup is the *normal* state of a file being previewed while
     * it is written, so every one of these is a document the parser will see.
     * The only claim is that it comes back with something rather than
     * throwing — a crash here takes the editor down with it.
     */
    @Test
    fun `half-typed markup parses instead of throwing`() {
        val cases = listOf(
            "[", "]", "![", "![]", "[]()", "[a](", "[a][", "[a][b]", "[]: ",
            "`", "``", "```", "~~", "~~~", "*", "**", "***", "****", "_", "__",
            "<", ">", "<>", "</>", "<a", "|", "|-", "|---|", "| a |", "- ", "-",
            "1.", "1. ", "> ", ">", "#", "######", "#######", "    ",
            "\\", "\\\\", " ", "🙂 **bold 🙂**",
            "- a\n  - b\n    - c\n      - d\n        - e",
            "> > > deep\n> > > quote",
            "| a |\n|---|\n| `x|y` |",
            "```\n```\n```\n",
        )
        for (case in cases) {
            // A blank-only document is legitimately no blocks; everything else
            // has to produce something.
            val blocks = parseMarkdown(case)
            assertTrue("threw nothing but produced nothing for <$case>", case.isBlank() || blocks.isNotEmpty())
        }
    }

    @Test
    fun `an empty document is no blocks rather than one empty paragraph`() {
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown(""))
        assertEquals(emptyList<MarkdownBlock>(), parseMarkdown("\n\n   \n"))
    }

    // ---- Degenerate input ------------------------------------------------
    //
    // A previewed `.md` comes out of a repository the user has just cloned, so
    // every one of these is a document the parser can be handed. None of them
    // may take the process down, and none may take a wall-clock age: an
    // uncaught StackOverflowError or OutOfMemoryError on the parse thread is
    // process death, and process death loses every unsaved tab in every pane.

    /**
     * Parses on a 512 KB stack, which is the order of an Android worker
     * thread's — the host JVM's default is several times larger and hides
     * exactly the overflow this is looking for.
     */
    private fun parseOnSmallStack(source: String): List<MarkdownBlock> {
        var blocks: List<MarkdownBlock> = emptyList()
        var failure: Throwable? = null
        val thread = Thread(
            null,
            {
                try {
                    blocks = parseMarkdown(source)
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

    private fun quoteDepth(blocks: List<MarkdownBlock>): Int = blocks.maxOfOrNull { block ->
        when (block) {
            is MarkdownBlock.Quote -> 1 + quoteDepth(block.blocks)
            is MarkdownBlock.Bullets -> block.items.maxOfOrNull { quoteDepth(it.blocks) } ?: 0
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
     * half a minute on a phone, per keystroke, on the shared background pool.
     */
    @Test(timeout = 5_000)
    fun `a paragraph of unmatched emphasis openers does not go quadratic`() {
        val paragraph = paragraphs("*a ".repeat(80_000)).single()
        assertTrue(text(paragraph.content).startsWith("*a *a"))
    }

    @Test(timeout = 2_000)
    fun `a paragraph of glob patterns does not go quadratic`() {
        val globs = (1..20_000).joinToString(" ") { "*.ext$it" }
        val paragraph = paragraphs(globs).single()
        assertTrue(text(paragraph.content).startsWith("*.ext1 *.ext2"))
    }

    /**
     * Under the inline-length cap, so this is the scan window's bound rather
     * than the cap's: 8000 strikethrough openers, none of them closed, in a
     * paragraph small enough that the parser still reads it for markup.
     */
    @Test(timeout = 5_000)
    fun `unmatched openers inside a readable paragraph stay bounded`() {
        val source = "~~a ".repeat(8_000)
        assertTrue(paragraphs(source).single().content.isNotEmpty())
    }

    // ---- Links -----------------------------------------------------------

    @Test
    fun `an image wrapped in a link keeps the link`() {
        val badge = paragraphs("[![Build](https://img.shields.io/b.svg)](https://ci.example.com/job)")
            .single().content.single()
        assertTrue(badge.isImage)
        assertEquals("Build", badge.text)
        assertEquals("https://ci.example.com/job", badge.link)

        val logo = paragraphs("[![Logo](docs/logo.png)](https://example.com)")
            .single().content.single()
        assertEquals("https://example.com", logo.link)

        // On its own, the image's own src is still what it points at.
        val bare = paragraphs("![Logo](docs/logo.png)").single().content.single()
        assertEquals("docs/logo.png", bare.link)
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
    fun `a link that names a directory is not offered as a file to open`() {
        assertNull(relativeLinkTarget("README.md", "docs/"))
        assertNull(relativeLinkTarget("docs/guide/README.md", "../"))
        assertNull(relativeLinkTarget("README.md", "."))
        assertNull(relativeLinkTarget("README.md", ""))
        assertEquals("docs/USERLAND.md", relativeLinkTarget("README.md", "docs/USERLAND.md"))
        assertEquals("etc/passwd", relativeLinkTarget("README.md", "../../../etc/passwd"))
    }

    // ---- Blocks ----------------------------------------------------------

    @Test
    fun `a fence under a quote is not swallowed by its lazy continuation`() {
        val blocks = parseMarkdown("> a quote\n```rust\nfn main() {}\n```")
        val quote = blocks[0] as MarkdownBlock.Quote
        assertTrue(quote.blocks.none { it is MarkdownBlock.Code })
        assertEquals("fn main() {}", (blocks[1] as MarkdownBlock.Code).code)
    }

    @Test
    fun `a row wider than its header still draws every cell`() {
        val table = parseMarkdown(
            """
            | a | b | c |
            |---|---|---|
            | 1 | 2 | 3 | 4 | 5 |
            """.trimIndent()
        ).single() as MarkdownBlock.Table
        assertEquals(5, table.columnCount())
    }

    /** Every run of text in [blocks], structure ignored. */
    private fun blockText(blocks: List<MarkdownBlock>): String = blocks.joinToString(" ") { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> text(block.content)
            is MarkdownBlock.Heading -> text(block.content)
            is MarkdownBlock.Quote -> blockText(block.blocks)
            is MarkdownBlock.Bullets -> block.items.joinToString(" ") { blockText(it.blocks) }
            is MarkdownBlock.Code -> block.code
            is MarkdownBlock.Math -> block.source
            is MarkdownBlock.Table -> ""
            MarkdownBlock.Rule -> ""
        }
    }
}
