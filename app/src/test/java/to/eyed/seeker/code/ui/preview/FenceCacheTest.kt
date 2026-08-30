package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.ui.editor.HighlightSpan

/**
 * What the fence highlighter remembers, tested without an engine.
 *
 * The highlighting itself needs the JNI bridge and cannot run here; the memory
 * behaviour can, and it is the part that goes wrong quietly — a cache keyed by
 * whole fence texts that nothing ever empties, and a refusal that costs an
 * engine buffer to discover and was never written down.
 */
class FenceCacheTest {

    private val spans = listOf(listOf(HighlightSpan(0, 4, 1)))

    @Test
    fun `a fence we cannot colour is remembered as such`() {
        val cache = FenceCache(limit = 8)
        assertNull(cache.answerFor("zigzag", "fn main() {}"))
        cache.remember("zigzag", "fn main() {}", emptyList())
        // Not null: "we have been here and there is nothing to draw". Null
        // would send the caller back to the engine on every single reparse.
        assertEquals(emptyList<List<HighlightSpan>>(), cache.answerFor("zigzag", "fn main() {}"))
    }

    @Test
    fun `an answer is remembered per grammar and per text`() {
        val cache = FenceCache(limit = 8)
        cache.remember("rust", "fn main() {}", spans)
        assertNotNull(cache.answerFor("rust", "fn main() {}"))
        assertNull(cache.answerFor("rust", "fn other() {}"))
        assertNull(cache.answerFor("c", "fn main() {}"))
    }

    @Test
    fun `it never grows past its limit`() {
        val cache = FenceCache(limit = 4)
        repeat(50) { cache.remember("rust", "fence $it", spans) }
        assertEquals(4, cache.size)
        assertNotNull(cache.answerFor("rust", "fence 49"))
        assertNull(cache.answerFor("rust", "fence 0"))
    }

    @Test
    fun `clearing it lets go of everything`() {
        val cache = FenceCache(limit = 8)
        cache.remember("rust", "fn main() {}", spans)
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.answerFor("rust", "fn main() {}"))
        assertTrue(cache.answerFor("rust", "fn main() {}") == null)
    }
}
