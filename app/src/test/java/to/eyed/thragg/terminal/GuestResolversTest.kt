package to.eyed.thragg.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest's `resolv.conf` ordering rules, on the host.
 *
 * Each rule here was bought with a rehearsal failure: the device resolver
 * that refused the guest's DNS queries is why the publics are appended at
 * all, and glibc's MAXNS=3 window is why the device's own list is capped —
 * a fallback pushed past line three is a fallback glibc never reads.
 */
class GuestResolversTest {

    // --- the fallback is unconditional --------------------------------------

    /** The old behaviour, kept: no device resolvers means the publics alone. */
    @Test
    fun emptyDeviceListGetsThePublics() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), GuestResolvers.list(emptyList()))
    }

    /**
     * The rehearsal case: one device resolver that misbehaves. The publics
     * must be there BEHIND it — present, so a failed lookup has somewhere to
     * go, and behind, so LAN-only names still resolve through the device's.
     */
    @Test
    fun publicsFollowTheDeviceResolverRatherThanReplacingIt() {
        assertEquals(
            listOf("192.168.1.1", "1.1.1.1", "8.8.8.8"),
            GuestResolvers.list(listOf("192.168.1.1")),
        )
    }

    // --- glibc's window ------------------------------------------------------

    /**
     * glibc reads only the first three `nameserver` lines, so at least one
     * public entry must sit at index <= 2 whatever the device reports.
     */
    @Test
    fun aPublicResolverStaysInsideGlibcsFirstThreeLines() {
        val crowded = GuestResolvers.list(
            listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"),
        )
        assertEquals(
            listOf("10.0.0.1", "10.0.0.2", "1.1.1.1", "8.8.8.8"),
            crowded,
        )
        assertTrue(
            "a public resolver must be within glibc's MAXNS=3 window",
            crowded.take(3).any { it in GuestResolvers.PUBLIC },
        )
    }

    // --- no duplicate lines --------------------------------------------------

    /** A device already using a public resolver must not list it twice. */
    @Test
    fun aDeviceUsingAPublicResolverIsNotDoubled() {
        assertEquals(
            listOf("1.1.1.1", "8.8.8.8"),
            GuestResolvers.list(listOf("1.1.1.1")),
        )
    }

    /** Duplicate device entries collapse before the cap is applied. */
    @Test
    fun duplicateDeviceEntriesCollapse() {
        assertEquals(
            listOf("10.0.0.1", "10.0.0.2", "1.1.1.1", "8.8.8.8"),
            GuestResolvers.list(listOf("10.0.0.1", "10.0.0.1", "10.0.0.2")),
        )
    }
    /**
     * The file ends with `options use-vc` — DNS over TCP, for both resolvers.
     *
     * The A/B/A evidence is in [GuestResolvers.conf]'s note: Go's resolver
     * hit an intermittent, UDP-wide EPERM from engine-spawned processes on
     * the Seeker, TCP never did, and the flip reproduced in both directions.
     * This test pins the line so a cleanup that "simplifies" the writer back
     * to nameservers-only re-breaks sign-in loudly here instead of quietly
     * on a phone.
     */
    @Test
    fun theConfForcesTcpDns() {
        val conf = GuestResolvers.conf(listOf("192.168.50.1"))
        assertEquals(
            "nameserver 192.168.50.1\nnameserver 1.1.1.1\nnameserver 8.8.8.8\noptions use-vc\n",
            conf,
        )
    }

    // --- only TCP-capable resolvers earn a line ------------------------------

    /**
     * The on-device failure this rule was bought with: `use-vc` makes TCP the
     * only transport, the Seeker's router drops TCP 53 silently, and glibc's
     * blocking connect turned every lookup into a two-minute SYN_SENT stall —
     * `cargo install` spent five minutes resolving its first name. A resolver
     * that fails the TCP probe must not be written at all.
     */
    @Test
    fun aResolverThatCannotSpeakTcpIsLeftOut() {
        val conf = GuestResolvers.conf(listOf("192.168.50.1")) { candidates ->
            candidates.associateWith { it != "192.168.50.1" }
        }
        assertEquals(
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions use-vc\n",
            conf,
        )
    }

    /** A device resolver that passes the probe keeps its place at the front. */
    @Test
    fun aTcpCapableDeviceResolverStaysFirst() {
        assertEquals(
            listOf("192.168.1.1", "1.1.1.1", "8.8.8.8"),
            GuestResolvers.usable(
                listOf("192.168.1.1", "1.1.1.1", "8.8.8.8"),
                mapOf("192.168.1.1" to true, "1.1.1.1" to true, "8.8.8.8" to true),
            ),
        )
    }

    /**
     * Offline, every probe fails; the unfiltered list is written anyway,
     * because an empty resolv.conf turns "no network" into a different,
     * stranger failure than the resolver errors a user can read.
     */
    @Test
    fun allProbesFailingKeepsTheWholeList() {
        val candidates = listOf("192.168.1.1", "1.1.1.1", "8.8.8.8")
        assertEquals(
            candidates,
            GuestResolvers.usable(candidates, candidates.associateWith { false }),
        )
    }

}
