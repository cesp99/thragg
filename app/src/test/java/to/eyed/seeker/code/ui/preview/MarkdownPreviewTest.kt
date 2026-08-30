package to.eyed.seeker.code.ui.preview

import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the panel makes before it draws anything: how much of a
 * buffer it is willing to hold, and how wide it is willing to get.
 *
 * Both are load-bearing rather than cosmetic. The editor is line-windowed and
 * opens a file of any size; this panel is the one place that pulls a whole
 * buffer into the Java heap, and without a cap a large `.md` is an
 * OutOfMemoryError on a background thread — process death, and every unsaved
 * tab with it.
 */
class MarkdownPreviewTest {

    @Test
    fun `a buffer past the cap is refused before it has all been read`() {
        val row = "x".repeat(1_000)
        var rowsRead = 0
        val source = cappedSource(lineCount = 5_000) { first, last ->
            rowsRead += last - first
            List(last - first) { row }.joinToString("\n")
        }
        assertNull(source)
        // The point is to refuse *without* materialising it: reading the lot
        // and then measuring it is the allocation that kills the process.
        assertTrue("read all $rowsRead rows before deciding", rowsRead < 5_000)
    }

    @Test
    fun `a buffer under the cap comes back whole, chunk joins included`() {
        val source = cappedSource(lineCount = 3) { first, last ->
            (first until last).joinToString("\n") { "line $it" }
        }
        assertEquals("line 0\nline 1\nline 2", source)
        assertEquals("", cappedSource(lineCount = 0) { _, _ -> "" })
    }

    @Test
    fun `a document past the cap becomes the refusal rather than a block list`() {
        runBlocking {
            val document = PreviewDocument.of("a".repeat(MAX_PREVIEW_CHARS + 1))
            assertTrue(document.isTooLarge)
            assertTrue(document.blocks.isEmpty())
        }
    }

    @Test
    fun `a document under the cap still parses`() {
        runBlocking {
            val document = PreviewDocument.of("# Title\n\nsome prose")
            assertFalse(document.isTooLarge)
            assertEquals(2, document.blocks.size)
        }
    }

    @Test
    fun `the dock cannot be dragged wide enough to squeeze the editor out`() {
        assertEquals(800.dp, clampDockWidth(900.dp, 1000.dp))
        assertEquals(800.dp, clampDockWidth(4000.dp, 1000.dp))
        assertEquals(400.dp, clampDockWidth(400.dp, 1000.dp))
        assertEquals(280.dp, clampDockWidth(100.dp, 1000.dp))
        // Too narrow to satisfy both minimums: the dock's own floor wins, and
        // the caller has already decided this is not a docked layout.
        assertEquals(280.dp, clampDockWidth(400.dp, 300.dp))
    }
}
