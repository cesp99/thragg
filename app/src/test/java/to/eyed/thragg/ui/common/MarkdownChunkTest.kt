package to.eyed.thragg.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming chunker: what part of a growing reply will never be read
 * differently.
 *
 * The property that matters is not "it settles a lot" — it is that whatever it
 * settles is settled CORRECTLY, and that the answer never moves backwards
 * while text is appended. A cut in the wrong place is not a slow render but a
 * wrong one, so every case below is a way a naive "split on a blank line"
 * would have been wrong, plus the invariant that lets the caller keep a cache
 * at all.
 */
class MarkdownChunkTest {

    /** Long enough to clear [MARKDOWN_CHUNK_MIN] without saying anything. */
    private fun pad(): String = (1..40).joinToString("\n\n") { "Paragraph number $it. " + "x".repeat(40) }

    /** The head and the tail parse to the same thing as the whole document. */
    private fun assertSplitIsFaithful(source: String) {
        val cut = settledPrefix(source)
        val whole = parseMarkdownText(source)
        val links = collectMarkdownLinks(source)
        val split = parseMarkdownChunk(source.substring(0, cut), links) +
            parseMarkdownChunk(source.substring(cut), links)
        assertEquals(whole, split)
    }

    @Test
    fun `short documents are not chunked at all`() {
        assertEquals(0, settledPrefix("a\n\nb\n\nc\n"))
    }

    @Test
    fun `a paragraph boundary settles and the split is faithful`() {
        val source = pad() + "\n\nthe growing tail"
        assertTrue(settledPrefix(source) > 0)
        assertSplitIsFaithful(source)
    }

    @Test
    fun `a cut is never made inside an open fence`() {
        // The fence swallows the blank lines after it, so nothing beyond the
        // opener may settle — otherwise the head's "paragraphs" become code
        // the moment the closing fence arrives.
        val head = pad()
        val source = head + "\n\n```rust\nfn a() {}\n\nfn b() {}\n\nstill streaming"
        assertTrue(settledPrefix(source) <= head.length + 2)
        assertSplitIsFaithful(source)
    }

    @Test
    fun `a closed fence lets the text after it settle again`() {
        val source = pad() + "\n\n```rust\nfn a() {}\n```\n\nafter the fence\n\ntail"
        assertSplitIsFaithful(source)
        assertTrue(settledPrefix(source) > pad().length)
    }

    @Test
    fun `a loose list is never cut between its items`() {
        // `- a\n\n- b` is ONE list. Cutting between them would draw two tight
        // lists, and for an ordered list it would restart the numbering.
        val source = pad() + "\n\n1. first\n\n2. second\n\n3. third\n"
        val cut = settledPrefix(source)
        assertTrue("cut landed inside the list", cut <= pad().length + 2)
        assertSplitIsFaithful(source)
    }

    @Test
    fun `an indented continuation is never a cut point`() {
        val source = pad() + "\n\n- item\n\n    still the item\n\n"
        assertTrue(settledPrefix(source) <= pad().length + 2)
        assertSplitIsFaithful(source)
    }

    @Test
    fun `a quote is never a cut point`() {
        val source = pad() + "\n\n> quoted\n\n> still quoted\n"
        assertTrue(settledPrefix(source) <= pad().length + 2)
        assertSplitIsFaithful(source)
    }

    @Test
    fun `the final incomplete line never settles`() {
        // "-" is not a list marker; "- x" is. Refusing to cut at a line with
        // no newline after it is what makes the answer monotone.
        val source = pad() + "\n\n-"
        val before = settledPrefix(source)
        val after = settledPrefix(source + " item\n")
        assertTrue("the cut moved backwards", after >= before)
    }

    @Test
    fun `the cut never moves backwards as text is appended`() {
        var source = pad()
        var previous = settledPrefix(source)
        for (piece in listOf("\n\nmore prose\n", "```\ncode\n```\n", "\n- a\n- b\n", "\n\ntail\n")) {
            source += piece
            val cut = settledPrefix(source)
            assertTrue("cut went from $previous to $cut", cut >= previous)
            previous = cut
        }
    }

    @Test
    fun `a settled cut always lands at the start of a line`() {
        val source = pad() + "\n\ntail\n"
        val cut = settledPrefix(source)
        assertTrue(cut == 0 || source[cut - 1] == '\n')
    }
}
