package to.eyed.thragg.ui.shell.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.thragg.solana.build.BuildAction

/**
 * The status strip's readout for the run deck, as tables: which blocked
 * verb it names, and that it says nothing while a run is going.
 */
class DeckReadoutTest {

    @Test
    fun `the readout names the first blocked verb in deck order`() {
        assertNull(deckReadout(listOf(BuildAction.Build to null, BuildAction.Test to null, BuildAction.Deploy to null)))
        assertEquals(
            "Deploy · needs a build",
            deckReadout(listOf(BuildAction.Build to null, BuildAction.Test to null, BuildAction.Deploy to "needs a build")),
        )
        assertEquals(
            "Build · no toolchain",
            deckReadout(listOf(BuildAction.Build to "no toolchain", BuildAction.Test to "no toolchain", BuildAction.Deploy to null)),
        )
        assertEquals(
            "Build · open a project",
            deckReadout(listOf(BuildAction.Build to "open a project", BuildAction.Test to "open a project", BuildAction.Deploy to "open a project")),
        )
    }

    @Test
    fun `a reason is drawn only at rest`() {
        assertEquals("needs a build", drawnReason("needs a build", running = false))
        assertEquals("no toolchain", drawnReason("no toolchain", running = false))
        // Running: the Stop and the spinner already say so; the key gates silently.
        assertNull(drawnReason("building…", running = true))
        assertNull(drawnReason(null, running = false))
    }
}
