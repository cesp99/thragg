package to.eyed.thragg.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where reclaimed rent lands, and whether the app says so.
 *
 * QA r4 §12, on devnet: reclaiming buffer `Ciyd…TiYy` moved 933,607,480
 * lamports to the **wallet** and took 5,000 in fee from the **deploy key**,
 * which is the account that had paid the rent in the first place. Watching
 * the Deploy-key balance, Reclaim looked like a button that costs money and
 * returns nothing, and no copy anywhere named the destination.
 */
class ProgramCloseRecipientTest {

    private val wallet = "9qVMvZjPggbwe5BJNya33C9QzbkRrGCNtFtRrNvjjNC5"
    private val deployKey = "Dju5N5s7EgRCyQSZhkVWhsRYxcU94wdjriVjqV41JorA"
    private val buffer = "CiydNyaptcDBXt3mreXxGRaLWkTfnPWh8DtC9gGqTiYy"

    @Test
    fun `a buffer's rent goes back to the deploy key that fronted it, wallet or no wallet`() {
        assertEquals(deployKey, ProgramClose.bufferRecipient(deployKey))
        // A program close is a different question and keeps its own rule.
        assertEquals(wallet, ProgramClose.recipientFor(wallet, deployKey))
        assertEquals(deployKey, ProgramClose.recipientFor(null, deployKey))
    }

    @Test
    fun `the caption says where a reclaim will send the rent before it moves`() {
        val said = ProgramClose.reclaimDestination(deployKey)
        assertTrue(said, "deploy key Dju5…JorA" in said)
        assertTrue(said, "which paid it" in said)
        // Before the key has been read off disk there is still a destination.
        assertTrue(ProgramClose.reclaimDestination(null), "deploy key" in ProgramClose.reclaimDestination(null))
    }

    @Test
    fun `the reclaim sentence names the sum, the destination and why it is the destination`() {
        val said = ProgramClose.reclaimedDetail(buffer, 933_607_480L, deployKey)
        assertTrue(said, "Ciyd…TiYy" in said)
        assertTrue(said, "0.9336 SOL" in said)
        assertTrue(said, "deploy key Dju5…JorA" in said)
        assertTrue(said, "which paid it" in said)
    }
}
