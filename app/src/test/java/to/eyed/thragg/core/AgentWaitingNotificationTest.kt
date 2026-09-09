package to.eyed.thragg.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a parked permission is allowed to buzz the phone — B-08.
 *
 * `AgentSessions.watch` exists precisely for the case the Agent destination
 * cannot cover: a permission raised, the user leaving the tab or locking the
 * screen, and a turn that will not restart on its own. It was **never
 * started** — `watch` had no caller anywhere in `app/src`, so `watchLoop`, the
 * high-importance `agent_waiting` raise, the nav-bar attention dot and the
 * `onProblem` hook were all dead code and a blocked run notified nobody,
 * indefinitely. It is started from `MainActivity.onCreate` now, beside the
 * seams that are installed there for the same reason.
 *
 * The loop's own condition is this function, so the rule can be pinned here
 * rather than only by pocketing a phone for five minutes.
 */
class AgentWaitingNotificationTest {

    @Test
    fun `a parked request with the app in the background raises it`() {
        assertTrue(
            AgentSessions.notifiesWaiting(
                panelVisible = false,
                needed = 1,
                appInForeground = false,
            )
        )
    }

    @Test
    fun `nothing waiting says nothing`() {
        assertFalse(
            AgentSessions.notifiesWaiting(
                panelVisible = false,
                needed = 0,
                appInForeground = false,
            )
        )
    }

    /**
     * The panel is the notification: with it on screen the sheet is already
     * up, and a system notification for a question the user is looking at is
     * noise.
     */
    @Test
    fun `the panel on screen is the answer`() {
        assertFalse(
            AgentSessions.notifiesWaiting(
                panelVisible = true,
                needed = 2,
                appInForeground = false,
            )
        )
    }

    /**
     * In the foreground on another destination, the nav bar's attention dot
     * says it — pulling the user out of the app they are already in would be
     * the wrong shape.
     */
    @Test
    fun `the app in front is told by the dot, not by a notification`() {
        assertFalse(
            AgentSessions.notifiesWaiting(
                panelVisible = false,
                needed = 1,
                appInForeground = true,
            )
        )
    }
}
