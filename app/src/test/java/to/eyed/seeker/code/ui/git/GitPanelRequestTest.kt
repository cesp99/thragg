package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the panel does with a palette request that outlived its moment. The
 * request waits out the panel's first `git status`, and in that window the
 * user can dismiss the panel or switch projects — the one thing that must
 * never come of it is a push running against a repository it was not asked
 * about.
 */
class GitPanelRequestTest {

    private fun request(project: Long) =
        GitPanelRequest(GitPanelCommand.Push, project = project, token = 1)

    @Test
    fun aRequestForThisProjectWaitsForTheScanThenRuns() {
        assertEquals(
            PanelRequestStep.Wait,
            panelRequestStep(request(project = 7), project = 7, scanned = false),
        )
        assertEquals(
            PanelRequestStep.Run,
            panelRequestStep(request(project = 7), project = 7, scanned = true),
        )
    }

    @Test
    fun aRequestStampedForAnotherProjectIsDroppedNeverRun() {
        // Dropped even with the scan landed: scanned=true is project B's
        // scan, and the push was asked about project A.
        assertEquals(
            PanelRequestStep.Drop,
            panelRequestStep(request(project = 7), project = 8, scanned = true),
        )
        // And dropped rather than waited on — a mismatched request can never
        // become right by waiting.
        assertEquals(
            PanelRequestStep.Drop,
            panelRequestStep(request(project = 7), project = 8, scanned = false),
        )
    }
}
