package to.eyed.thragg.ui.shell.build

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.thragg.solana.build.ArtifactFreshness
import to.eyed.thragg.solana.build.BuildAction
import to.eyed.thragg.solana.build.FailureFacts

/**
 * The failure card's three sentences and the strip's trailing slot.
 *
 * Every one of these is a QA finding rather than a style preference: the card
 * was titled "The build failed" whatever had failed and its Retry always ran a
 * build (G-02), and the strip printed a path to an artifact that either did
 * not exist or was not what had just failed (P-09).
 */
class FailureCardTest {

    private fun facts(
        action: BuildAction,
        errors: Int = 0,
        warnings: Int = 0,
        detail: String? = null,
    ) = FailureFacts(action, errors, warnings, detail)

    @Test
    fun `the title names the verb that failed`() {
        assertEquals("The build failed", failureTitle(BuildAction.Build))
        assertEquals("The tests failed", failureTitle(BuildAction.Test))
        assertEquals("The deploy failed", failureTitle(BuildAction.Deploy))
        // A verdict restored with the shell knows no action; Build is what the
        // one-tap control runs, so it is the honest fallback.
        assertEquals("The build failed", failureTitle(null))
    }

    @Test
    fun `errors are quoted before anything else`() {
        assertEquals(
            "anchor build reported 2 errors and 3 warnings.",
            failureBody(facts(BuildAction.Build, errors = 2, warnings = 3), "anchor build"),
        )
        assertEquals(
            "anchor build reported 1 error.",
            failureBody(facts(BuildAction.Build, errors = 1), "anchor build"),
        )
    }

    @Test
    fun `with no errors the log's own last word beats the warning count`() {
        // The exact shape of G-02: a chain failure under eight harmless
        // warnings, reported as "reported 8 warnings".
        assertEquals(
            "anchor test stopped: Attempt to load a program that does not exist",
            failureBody(
                facts(
                    BuildAction.Test,
                    warnings = 8,
                    detail = "Attempt to load a program that does not exist",
                ),
                "anchor test",
            ),
        )
    }

    @Test
    fun `warnings are said only when there is nothing better`() {
        assertEquals(
            "anchor build reported 8 warnings.",
            failureBody(facts(BuildAction.Build, warnings = 8), "anchor build"),
        )
        assertEquals(
            "The last run stopped without finishing. The log has what it printed.",
            failureBody(null, ""),
        )
    }

    @Test
    fun `the strip prints a path only for an artifact that is there and is not the failure`() {
        val path = "target/deploy/escrow.so"
        assertEquals(path, stripPath(path, failed = false, freshness = ArtifactFreshness.Fresh(at = 1L)))
        assertEquals(path, stripPath(path, failed = false, freshness = ArtifactFreshness.Stale(at = 1L)))
        // Nothing built: the path names a file that does not exist.
        assertEquals("", stripPath(path, failed = false, freshness = ArtifactFreshness.Missing))
        // Beside "Failed" a path reads as "here is what it produced", and the
        // artifact beside it is the one from before the run.
        assertEquals("", stripPath(path, failed = true, freshness = ArtifactFreshness.Stale(at = 1L)))
        assertEquals("", stripPath(null, failed = false, freshness = ArtifactFreshness.Fresh(at = 1L)))
    }
}
