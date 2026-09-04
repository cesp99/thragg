package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of a multibuffer tab: reading the engine's headers, and the
 * row arithmetic the sticky bar and the excerpt jump both rest on. Both are
 * easy to get off by one and neither needs a device.
 */
class MultiBufferInfoTest {

    /**
     * Two excerpts: `src/a.rs` rows 3..7 under a header at composed row 0, and
     * `src/b.rs` rows 18..22 under a header at composed row 6.
     */
    private val json = """
        {
          "id": 1,
          "title": "Search: needle",
          "kind": "search",
          "buffer": 9,
          "version": 4,
          "rebuilds": 2,
          "dirty_files": 1,
          "excerpts": [
            {
              "path": "src/a.rs", "abs_path": "/p/src/a.rs", "buffer": 3,
              "header_row": 0, "first_row": 1, "last_row": 5,
              "file_start_row": 3, "file_end_row": 7, "dirty": false
            },
            {
              "path": "src/b.rs", "abs_path": "/p/src/b.rs", "buffer": 4,
              "header_row": 6, "first_row": 7, "last_row": 11,
              "file_start_row": 18, "file_end_row": 22, "dirty": true
            }
          ]
        }
    """.trimIndent()

    private val info = requireNotNull(MultiBufferInfo.parse(json))

    @Test
    fun `reads the composition and its headers`() {
        assertEquals(1L, info.id)
        assertEquals("Search: needle", info.title)
        assertEquals("search", info.kind)
        assertEquals(9L, info.bufferId)
        assertEquals(4L, info.version)
        assertEquals(2L, info.rebuilds)
        assertEquals(1, info.dirtyFiles)
        assertEquals(listOf("src/a.rs", "src/b.rs"), info.excerpts.map { it.path })
        assertEquals(listOf(3L, 4L), info.excerpts.map { it.bufferId })
        assertTrue(info.excerpts[1].dirty)
    }

    /** Nothing here throws on rubbish; a tab that cannot parse simply has none. */
    @Test
    fun `bad json is no info at all`() {
        assertNull(MultiBufferInfo.parse(null))
        assertNull(MultiBufferInfo.parse("not json"))
        assertEquals(0, requireNotNull(MultiBufferInfo.parse("{}")).excerpts.size)
    }

    /** A composed row maps to the file row under it, header rows included. */
    @Test
    fun `composed rows map back to file rows`() {
        val a = info.excerpts[0]
        assertEquals(3, a.fileRowOf(0)) // the header answers with the first row
        assertEquals(3, a.fileRowOf(1))
        assertEquals(7, a.fileRowOf(5))
        val b = info.excerpts[1]
        assertEquals(18, b.fileRowOf(6))
        assertEquals(20, b.fileRowOf(9))
        assertEquals(22, b.fileRowOf(11))
    }

    @Test
    fun `an excerpt owns its header and its text rows`() {
        assertEquals("src/a.rs", info.excerptAt(0)?.path)
        assertEquals("src/a.rs", info.excerptAt(5)?.path)
        assertEquals("src/b.rs", info.excerptAt(6)?.path)
        assertEquals("src/b.rs", info.excerptAt(11)?.path)
        // Past the last excerpt there is nothing to open.
        assertNull(info.excerptAt(12))
        assertNull(info.excerptAt(-1))
    }

    /**
     * The sticky bar names the excerpt that has *started*, even when the top
     * of the viewport has run past its text into the gap before the next
     * header — otherwise the bar would blink off between excerpts.
     */
    @Test
    fun `the sticky header keeps the last excerpt that started`() {
        assertEquals("src/a.rs", info.stickyAt(0)?.path)
        assertEquals("src/a.rs", info.stickyAt(5)?.path)
        assertEquals("src/b.rs", info.stickyAt(6)?.path)
        assertEquals("src/b.rs", info.stickyAt(99)?.path)
        assertNull(info.stickyAt(-1))
    }

    /** What the bar prints, and what a one-row excerpt prints instead. */
    @Test
    fun `the label is the path and the line range`() {
        assertEquals("src/a.rs:4-8", info.excerpts[0].label)
        assertEquals(
            "src/a.rs:4",
            info.excerpts[0].copy(fileStartRow = 3, fileEndRow = 3).label,
        )
    }
}
