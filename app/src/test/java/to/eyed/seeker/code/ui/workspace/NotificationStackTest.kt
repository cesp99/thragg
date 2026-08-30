package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The toast stack's rules, driven on the host — Zed's
 * `Workspace::notifications` (workspace/src/notifications.rs) with the cap and
 * the expiry a phone needs on top.
 *
 * Every rule here is one somebody will hit within a minute of using the app:
 * a save that fails twice, four failures in a row, an info toast that should
 * go away and an error that must not.
 */
class NotificationStackTest {

    /** A clock the test moves by hand; nothing here waits on real time. */
    private class Clock(var now: Long = 1_000L)

    private fun stack(clock: Clock) = NotificationStack { clock.now }

    // ---- ordering ------------------------------------------------------------

    @Test
    fun theNewestNotificationIsFirst() {
        val stack = stack(Clock())
        stack.info("first")
        stack.warn("second")
        stack.error("third")
        assertEquals(listOf("third", "second", "first"), stack.all.map { it.message })
    }

    @Test
    fun aKeyedNotificationReplacesItsOwnAndTakesTheFrontAgain() {
        val stack = stack(Clock())
        stack.error("could not save main.rs", key = "save:main.rs")
        stack.warn("rustfmt: no such file", key = "format")
        stack.error("could not save main.rs", key = "save:main.rs")
        assertEquals(
            listOf("could not save main.rs", "rustfmt: no such file"),
            stack.all.map { it.message },
        )
    }

    @Test
    fun anUnkeyedNotificationStacksEvenWhenItRepeats() {
        val stack = stack(Clock())
        stack.error("git push failed")
        stack.error("git push failed")
        assertEquals(2, stack.all.size)
    }

    @Test
    fun dismissingAKeyTakesTheStateItDescribedWithIt() {
        val stack = stack(Clock())
        stack.error(".zed/settings.json is not in effect", key = "project-settings")
        stack.dismissKey("project-settings")
        assertTrue(stack.all.isEmpty())
    }

    // ---- the cap -------------------------------------------------------------

    @Test
    fun onlyFourAreVisibleAndTheRestAreCounted() {
        val stack = stack(Clock())
        repeat(7) { index -> stack.error("problem $index") }
        assertEquals(NotificationStack.MAX_VISIBLE, stack.visible.size)
        assertEquals(listOf("problem 6", "problem 5", "problem 4", "problem 3"), stack.visible.map { it.message })
        assertEquals(3, stack.hidden)
    }

    @Test
    fun expandingShowsThemAllAndHidesNothing() {
        val stack = stack(Clock())
        repeat(7) { index -> stack.error("problem $index") }
        stack.expand()
        assertEquals(7, stack.visible.size)
        assertEquals(0, stack.hidden)
    }

    /**
     * Dismissing back under the cap folds the stack up again on its own: a
     * "Show fewer" button that is the only way out of a list of two would be
     * a control with nothing to do.
     */
    @Test
    fun droppingBackUnderTheCapCollapsesTheStack() {
        val stack = stack(Clock())
        val ids = (0 until 6).map { index -> stack.error("problem $index") }
        stack.expand()
        assertTrue(stack.isExpanded)
        ids.take(2).forEach(stack::dismiss)
        assertFalse(stack.isExpanded)
        assertEquals(4, stack.all.size)
    }

    @Test
    fun theOldestFallOffOnceTheStackIsFull() {
        val stack = stack(Clock())
        repeat(NotificationStack.MAX_HELD + 3) { index -> stack.error("problem $index") }
        assertEquals(NotificationStack.MAX_HELD, stack.all.size)
        assertEquals("problem ${NotificationStack.MAX_HELD + 2}", stack.all.first().message)
        // The three oldest are the ones that went.
        assertEquals("problem 3", stack.all.last().message)
    }

    // ---- expiry --------------------------------------------------------------

    @Test
    fun anInfoNotificationGoesAwayOnItsOwnAndAnErrorDoesNot() {
        val clock = Clock()
        val stack = stack(clock)
        stack.info("Exported 12 files")
        stack.error("git fetch failed")
        clock.now += NotificationStack.INFO_MS - 1
        assertFalse(stack.expire())
        assertEquals(2, stack.all.size)
        clock.now += 1
        assertTrue(stack.expire())
        assertEquals(listOf("git fetch failed"), stack.all.map { it.message })
    }

    @Test
    fun aWarningStaysUntilItIsDismissed() {
        val clock = Clock()
        val stack = stack(clock)
        stack.warn("rustfmt: no such file")
        clock.now += 60 * 60 * 1000L
        assertFalse(stack.expire())
        assertEquals(1, stack.all.size)
    }

    /** A button nobody has had time to read is a button nobody will press. */
    @Test
    fun anInfoNotificationWithAButtonWaitsTwiceAsLong() {
        val clock = Clock()
        val stack = stack(clock)
        stack.info("Task finished", action = NotificationAction("Show output") {})
        clock.now += NotificationStack.INFO_MS
        assertFalse(stack.expire())
        clock.now += NotificationStack.INFO_WITH_ACTION_MS - NotificationStack.INFO_MS
        assertTrue(stack.expire())
        assertTrue(stack.all.isEmpty())
    }

    @Test
    fun theSoonestExpiryIsWhatTheHostSleepsUntil() {
        val clock = Clock(now = 5_000L)
        val stack = stack(clock)
        stack.error("stays")
        assertNull(stack.nextExpiry())
        stack.info("goes")
        assertEquals(5_000L + NotificationStack.INFO_MS, stack.nextExpiry())
    }

    @Test
    fun clearAllEmptiesTheStackAndFoldsIt() {
        val stack = stack(Clock())
        repeat(6) { index -> stack.error("problem $index") }
        stack.expand()
        stack.clearAll()
        assertTrue(stack.all.isEmpty())
        assertFalse(stack.isExpanded)
        assertEquals(0, stack.hidden)
    }
}
