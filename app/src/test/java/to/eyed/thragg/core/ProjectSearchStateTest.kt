package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's state names and this enum have to agree, because everything
 * unrecognised parses as [ProjectSearchState.Cancelled] — a panel that
 * mis-reads "scanning" would tell the user their search died while it was
 * simply waiting for the project scan.
 */
class ProjectSearchStateTest {

    @Test
    fun parsesEveryStateTheEngineCanSend() {
        assertEquals(ProjectSearchState.Scanning, ProjectSearchState.parse("scanning"))
        assertEquals(ProjectSearchState.Running, ProjectSearchState.parse("running"))
        assertEquals(ProjectSearchState.Done, ProjectSearchState.parse("done"))
        assertEquals(ProjectSearchState.Cancelled, ProjectSearchState.parse("cancelled"))
    }

    @Test
    fun anythingUnknownIsTreatedAsGone() {
        assertEquals(ProjectSearchState.Cancelled, ProjectSearchState.parse(""))
        assertEquals(ProjectSearchState.Cancelled, ProjectSearchState.parse("wat"))
    }

    @Test
    fun onlyScanningAndRunningAreStillComing() {
        assertTrue(ProjectSearchState.Scanning.isLive)
        assertTrue(ProjectSearchState.Running.isLive)
        assertFalse(ProjectSearchState.Done.isLive)
        assertFalse(ProjectSearchState.Cancelled.isLive)
    }
}
