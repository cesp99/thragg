package to.eyed.thragg.ui.shell.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import to.eyed.thragg.solana.build.ArtifactFreshness
import to.eyed.thragg.solana.build.BuildAction
import to.eyed.thragg.solana.build.ProjectFramework
import to.eyed.thragg.solana.build.ProjectLayout

/**
 * The Deploy/Test gate as a table: the one function the overflow and every
 * Test/Deploy control render from, so they cannot disagree.
 */
class VerbReasonTest {

    private val anchor = ProjectLayout(root = "/p", framework = ProjectFramework.Anchor, programs = emptyList())
    private val nothing = ProjectLayout(root = "/p", framework = ProjectFramework.Unknown, programs = emptyList())

    private fun reason(
        action: BuildAction,
        toolchainReady: Boolean = true,
        layout: ProjectLayout? = anchor,
        freshness: ArtifactFreshness = ArtifactFreshness.Fresh(at = 1L),
        running: Boolean = false,
    ) = verbReason(action, toolchainReady, layout, freshness, running)

    @Test
    fun `no toolchain blocks Test but not Deploy`() {
        assertEquals("no toolchain", reason(BuildAction.Test, toolchainReady = false))
        assertEquals("no toolchain", reason(BuildAction.Build, toolchainReady = false))
        // Deploy signs and sends from Kotlin: a phone with no guest can still
        // ship the artifact it has.
        assertNull(reason(BuildAction.Deploy, toolchainReady = false))
    }

    @Test
    fun `no project, or nothing buildable in it, asks for one`() {
        assertEquals("open a project", reason(BuildAction.Test, layout = null))
        assertEquals("open a project", reason(BuildAction.Deploy, layout = null))
        assertEquals("open a project", reason(BuildAction.Deploy, layout = nothing))
    }

    @Test
    fun `Deploy needs an artifact, and a stale one is still allowed`() {
        assertEquals("needs a build", reason(BuildAction.Deploy, freshness = ArtifactFreshness.Missing))
        assertNull(reason(BuildAction.Deploy, freshness = ArtifactFreshness.Stale(at = 1L)))
        assertNull(reason(BuildAction.Deploy, freshness = ArtifactFreshness.Fresh(at = 1L)))
        // Test does not need the artifact: `anchor test` builds its own.
        assertNull(reason(BuildAction.Test, freshness = ArtifactFreshness.Missing))
    }

    @Test
    fun `a running build blocks every verb, and an idle ready project blocks none`() {
        assertEquals("building…", reason(BuildAction.Test, running = true))
        assertEquals("building…", reason(BuildAction.Deploy, running = true))
        assertNull(reason(BuildAction.Test))
        assertNull(reason(BuildAction.Deploy))
        assertNull(reason(BuildAction.Build))
    }
}
