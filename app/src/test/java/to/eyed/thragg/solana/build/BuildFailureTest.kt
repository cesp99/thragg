package to.eyed.thragg.solana.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line a failed run puts on its card when the compiler said nothing.
 *
 * The case this exists for is real and was reported four times: `anchor test`
 * fails on the chain with "Attempt to load a program that does not exist", the
 * parser finds no diagnostics in that, and the card said "anchor test reported
 * 8 warnings" — a true sentence about a false cause (QA G-02).
 */
class BuildFailureTest {

    @Test
    fun `the marked line wins over the last line`() {
        val detail = BuildRunner.failureDetail(
            listOf(
                "yarn run v1.22.22",
                "Error: Attempt to load a program that does not exist",
                "    at Connection.sendEncodedTransaction",
                "Done in 3.41s.",
            )
        )
        assertEquals("Error: Attempt to load a program that does not exist", detail)
    }

    @Test
    fun `with nothing marked the last thing said is the answer`() {
        assertEquals(
            "Done in 3.41s.",
            BuildRunner.failureDetail(listOf("yarn run v1.22.22", "Done in 3.41s.")),
        )
    }

    @Test
    fun `blank lines go, and rustc escapes are stripped rather than dropped`() {
        // The card is Material prose; a paste of SGR bytes is not one. But
        // the line carrying them is usually the line the card wants, so the
        // escapes come off and the sentence stays.
        assertEquals(
            "cargo build failed",
            BuildRunner.failureDetail(
                listOf("  ", "\u001B[1;31merror[E0609]\u001B[0m", "cargo build failed", "")
            ),
        )
        assertEquals(
            "error[E0425]: cannot find value `zzz` in this scope",
            BuildRunner.failureDetail(
                listOf(
                    "   Compiling r4-native v0.1.0",
                    "\u001B[0m\u001B[1m\u001B[38;5;9merror[E0425]\u001B[0m\u001B[1m: " +
                        "cannot find value `zzz` in this scope\u001B[0m",
                )
            ),
        )
    }

    @Test
    fun `nothing at all is null rather than an empty sentence`() {
        assertNull(BuildRunner.failureDetail(emptyList()))
        assertNull(BuildRunner.failureDetail(listOf("", "   ")))
    }

    @Test
    fun `a very long line is cut and says it was cut`() {
        val long = "error: " + "x".repeat(400)
        val detail = BuildRunner.failureDetail(listOf(long))!!
        assertTrue(detail.length <= 161)
        assertTrue(detail.endsWith("\u2026"))
    }
}
