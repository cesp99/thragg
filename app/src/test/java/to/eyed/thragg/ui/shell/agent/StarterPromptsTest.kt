package to.eyed.thragg.ui.shell.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The empty thread's three chips: named for the project when there is one. */
class StarterPromptsTest {

    @Test
    fun `the first prompt names the project`() {
        assertEquals("Explain what escrow does", starterPrompts("escrow").first())
    }

    @Test
    fun `no project falls back to the generic noun`() {
        assertEquals("Explain what this program does", starterPrompts(null).first())
        assertEquals("Explain what this program does", starterPrompts("  ").first())
    }

    @Test
    fun `there are three, and each is a sentence a chip can hold`() {
        val prompts = starterPrompts("escrow")
        assertEquals(3, prompts.size)
        assertTrue(prompts.all { it.length in 10..40 })
        assertEquals(prompts.size, prompts.distinct().size)
    }
}
