package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.GitBranch

/**
 * The face of the remote split button is Zed's `render_remote_button` match
 * over the branch's upstream (crates/git_ui/src/git_ui.rs:785-838), and every
 * row of that match is a case here.
 */
class RemoteButtonTest {

    private fun branch(
        name: String? = "main",
        ahead: Int = 0,
        behind: Int = 0,
        unborn: Boolean = false,
        upstream: String? = null,
        upstreamGone: Boolean = false,
    ) = GitBranch(
        name = name,
        ahead = ahead,
        behind = behind,
        unborn = unborn,
        upstream = upstream,
        upstreamGone = upstreamGone,
    )

    // --- no button at all ----------------------------------------------

    @Test
    fun noBranchRendersNoButton() {
        // No repository record at all, and a detached HEAD — both are Zed's
        // `when_some(branch, …)` falling through (git_panel.rs:5851).
        assertNull(remoteButtonSpec(null))
        assertNull(remoteButtonSpec(branch(name = null)))
    }

    @Test
    fun anUnbornBranchRendersNoButton() {
        // `git init`, nothing committed: there is no commit to publish, and
        // Zed's handlers early-return without a branch to speak for
        // (git_panel.rs:3837, 3908).
        assertNull(remoteButtonSpec(branch(unborn = true)))
    }

    // --- tracked upstream ----------------------------------------------

    @Test
    fun inSyncIsFetchUnderTheCirclingArrows() {
        val spec = remoteButtonSpec(branch(upstream = "origin/main"))!!
        assertEquals("Fetch", spec.label)
        assertEquals(RemoteButtonIcon.ArrowCircle, spec.icon)
        assertEquals(RemoteButtonAction.Fetch, spec.action)
        // A left icon means no counts, drifted or not (git_ui.rs:1078-1108).
        assertFalse(spec.showsCounts)
    }

    @Test
    fun aheadOnlyIsPushWearingItsCount() {
        val spec = remoteButtonSpec(branch(ahead = 3, upstream = "origin/main"))!!
        assertEquals("Push", spec.label)
        assertNull(spec.icon)
        assertEquals(RemoteButtonAction.Push, spec.action)
        assertTrue(spec.showsCounts)
        assertEquals(3, spec.ahead)
        assertEquals(0, spec.behind)
    }

    @Test
    fun behindAtAllIsPull() {
        val spec = remoteButtonSpec(branch(behind = 2, upstream = "origin/main"))!!
        assertEquals("Pull", spec.label)
        assertNull(spec.icon)
        assertEquals(RemoteButtonAction.Pull, spec.action)
        assertTrue(spec.showsCounts)
        assertEquals(2, spec.behind)
    }

    @Test
    fun aheadAndBehindIsStillPullWearingBothCounts() {
        // Zed's `(ahead, behind)` arm: behind wins the verb, and the button
        // carries both numbers (git_ui.rs:814-820, 1078-1108).
        val spec = remoteButtonSpec(branch(ahead = 4, behind = 1, upstream = "origin/main"))!!
        assertEquals("Pull", spec.label)
        assertEquals(RemoteButtonAction.Pull, spec.action)
        assertEquals(4, spec.ahead)
        assertEquals(1, spec.behind)
        assertTrue(spec.showsCounts)
    }

    // --- gone and absent upstreams -------------------------------------

    @Test
    fun aGoneUpstreamIsRepublish() {
        // The remote branch was deleted: the click re-creates it, so the
        // action is a push (git_ui.rs:822-829).
        val spec = remoteButtonSpec(
            branch(ahead = 2, upstream = "origin/main", upstreamGone = true),
        )!!
        assertEquals("Republish", spec.label)
        assertEquals(RemoteButtonIcon.ExpandUp, spec.icon)
        assertEquals(RemoteButtonAction.Push, spec.action)
        assertFalse(spec.showsCounts)
    }

    @Test
    fun noUpstreamIsPublish() {
        val spec = remoteButtonSpec(branch(ahead = 5))!!
        assertEquals("Publish", spec.label)
        assertEquals(RemoteButtonIcon.ExpandUp, spec.icon)
        assertEquals(RemoteButtonAction.Push, spec.action)
        // Publish never counts what it is ahead by — there is nothing on the
        // other side to be ahead *of* (git_ui.rs:831-836, 942-970).
        assertFalse(spec.showsCounts)
    }
}
