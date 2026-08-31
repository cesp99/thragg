package to.eyed.seeker.code.solana.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The launch environment's contract, and the refresh rule that carries fixes
 * to entries written before them.
 *
 * `agent_servers` entries are written once at registration and then trusted,
 * so an env fix that only reached fresh installs would never reach the one
 * device that matters — the one where the agent is already registered. The
 * merge below is what `ensureRegistered` uses to decide whether to rewrite.
 */
class SpettroInstallTest {

    // --- what the env must carry --------------------------------------------

    /**
     * The DNS lever: Spettro's login poll died in Go's pure resolver
     * (`write udp ...:53: operation not permitted`) while glibc resolved the
     * same name in the same guest, and `netdns=cgo` is what points a
     * cgo-built binary at glibc. Losing this key silently would bring that
     * dead login back.
     */
    @Test
    fun launchEnvironmentRoutesGoDnsThroughGlibc() {
        assertEquals("netdns=cgo", SpettroInstall.launchEnvironment()["GODEBUG"])
    }

    /** The home the keys land in, and the PATH the agent shells out with. */
    @Test
    fun launchEnvironmentPinsHomeAndPath() {
        val env = SpettroInstall.launchEnvironment()
        assertEquals("/root", env["HOME"])
        assertEquals(true, env["PATH"]?.isNotBlank())
    }

    // --- the refresh decision ------------------------------------------------

    /** An entry that already carries everything is left alone — no settings write. */
    @Test
    fun upToDateEnvironmentNeedsNoRefresh() {
        assertNull(
            SpettroInstall.refreshedEnvironment(
                existing = mapOf("A" to "1", "B" to "2"),
                wanted = mapOf("A" to "1"),
            )
        )
    }

    /** A missing key is what triggers the rewrite. */
    @Test
    fun aMissingKeyTriggersARefresh() {
        assertEquals(
            mapOf("A" to "1", "B" to "2"),
            SpettroInstall.refreshedEnvironment(
                existing = mapOf("A" to "1"),
                wanted = mapOf("B" to "2"),
            ),
        )
    }

    /** Keys the user added by hand survive the refresh. */
    @Test
    fun handWrittenKeysSurviveTheRefresh() {
        val refreshed = SpettroInstall.refreshedEnvironment(
            existing = mapOf("USER_KEY" to "kept", "PATH" to "stale"),
            wanted = mapOf("PATH" to "fresh", "GODEBUG" to "netdns=cgo"),
        )
        assertEquals(
            mapOf("USER_KEY" to "kept", "PATH" to "fresh", "GODEBUG" to "netdns=cgo"),
            refreshed,
        )
    }

    /** The wanted value wins over a stale one — the keys this app owns stay owned. */
    @Test
    fun ownedKeysAreBroughtUpToDate() {
        assertEquals(
            mapOf("GODEBUG" to "netdns=cgo"),
            SpettroInstall.refreshedEnvironment(
                existing = mapOf("GODEBUG" to "http2debug=1"),
                wanted = mapOf("GODEBUG" to "netdns=cgo"),
            ),
        )
    }

    /**
     * The real default: the map `ensureRegistered` compares against is
     * [SpettroInstall.launchEnvironment] itself, so an entry written by the
     * previous release — HOME and PATH, no GODEBUG — must come back marked
     * for a rewrite that adds the DNS key and keeps the rest.
     */
    @Test
    fun aPreGodebugEntryIsRefreshedInPlace() {
        val old = SpettroInstall.launchEnvironment() - "GODEBUG"
        val refreshed = SpettroInstall.refreshedEnvironment(existing = old)
        assertEquals(SpettroInstall.launchEnvironment(), refreshed)
    }
}
