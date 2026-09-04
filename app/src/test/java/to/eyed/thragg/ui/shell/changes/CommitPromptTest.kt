package to.eyed.thragg.ui.shell.changes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Ask the agent for a message" says, which is the whole of that button.
 */
class CommitPromptTest {

    @Test
    fun itNamesTheStagedFilesAndAsksForAMessageAndNothingElse() {
        val prompt = commitMessagePrompt(listOf("src/lib.rs", "tests/escrow.ts"))
        assertTrue("src/lib.rs, tests/escrow.ts" in prompt)
        assertTrue("imperative" in prompt)
        assertTrue("nothing else" in prompt)
    }

    @Test
    fun aLongStagingAreaIsSummarisedRatherThanPasted() {
        val prompt = commitMessagePrompt((1..12).map { "src/file$it.rs" })
        assertTrue("src/file8.rs" in prompt)
        // The agent has the repository and can read the rest itself; spending
        // the prompt on ninety paths is spending it on nothing.
        assertFalse("src/file9.rs" in prompt)
        assertTrue("and 4 more" in prompt)
    }

    @Test
    fun anEmptyStagingAreaStillProducesAWellFormedRequest() {
        val prompt = commitMessagePrompt(emptyList())
        assertTrue(prompt.startsWith("Write a commit message for the staged changes."))
    }
}
