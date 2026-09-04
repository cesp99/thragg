package to.eyed.thragg.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The guest `/etc/hosts` pinning rule, on the host.
 *
 * Bought with a live failure: cargo's c-ares resolver ignores `options
 * use-vc`, hit the guest's intermittent UDP EPERM, and took a toolchain
 * install from compiling to `Could not resolve host: static.crates.io` —
 * while glibc's curl resolved fine in the same guest in the same minute.
 * `/etc/hosts` is the one channel every resolver honours first, so these
 * rules are what keep cargo reachable when its own DNS is not.
 */
class GuestHostsTest {

    private val debianDefault =
        "127.0.0.1\tlocalhost\n::1\t\tlocalhost ip6-localhost ip6-loopback\n"

    @Test
    fun theBlockIsAppendedAfterTheImageDefaults() {
        val merged = GuestHosts.merged(
            debianDefault,
            mapOf("static.crates.io" to "151.101.130.137"),
        )
        assertEquals(
            debianDefault.trimEnd('\n') + "\n" +
                GuestHosts.BEGIN + "\n" +
                "151.101.130.137\tstatic.crates.io\n" +
                GuestHosts.END + "\n",
            merged,
        )
    }

    /** Re-deriving per session must replace the block, never stack a second. */
    @Test
    fun aSecondMergeReplacesTheFirstBlock() {
        val once = GuestHosts.merged(debianDefault, mapOf("crates.io" to "1.2.3.4"))
        val twice = GuestHosts.merged(once, mapOf("crates.io" to "5.6.7.8"))
        assertEquals(1, twice.lines().count { it == GuestHosts.BEGIN })
        assertFalse(twice.contains("1.2.3.4"))
        assert(twice.contains("5.6.7.8\tcrates.io"))
    }

    /**
     * Nothing resolved — the phone is offline — removes the block: a stale
     * pin must lose to the guest's own resolver, not outrank it.
     */
    @Test
    fun nothingResolvedRemovesTheBlock() {
        val once = GuestHosts.merged(debianDefault, mapOf("crates.io" to "1.2.3.4"))
        assertEquals(debianDefault, GuestHosts.merged(once, emptyMap()))
    }

    /** A line somebody wrote by hand survives every rewrite. */
    @Test
    fun handWrittenLinesAreKept() {
        val hand = debianDefault + "10.0.0.7\tmy-nas\n"
        val merged = GuestHosts.merged(hand, mapOf("crates.io" to "1.2.3.4"))
        assert(merged.contains("10.0.0.7\tmy-nas"))
        val again = GuestHosts.merged(merged, emptyMap())
        assertEquals(hand, again)
    }

    /**
     * A guest set up before the rename to Thragg carries the block under the
     * old fences. /etc/hosts takes the first match, so that block must be
     * replaced, not left above the new one with its stale addresses.
     */
    @Test
    fun aBlockUnderTheOldFencesIsReplaced() {
        val legacy = debianDefault +
            "# seeker-pinned begin — rewritten per session; edit outside this block\n" +
            "1.2.3.4\tcrates.io\n" +
            "# seeker-pinned end\n"
        val merged = GuestHosts.merged(legacy, mapOf("crates.io" to "5.6.7.8"))
        assertFalse(merged.contains("seeker-pinned"))
        assertFalse(merged.contains("1.2.3.4"))
        assertEquals(1, merged.lines().count { it == GuestHosts.BEGIN })
        assert(merged.contains("5.6.7.8\tcrates.io"))
        assertEquals(debianDefault, GuestHosts.merged(legacy, emptyMap()))
    }
}
