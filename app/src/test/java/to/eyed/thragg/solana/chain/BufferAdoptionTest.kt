package to.eyed.thragg.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences the sheet prints about money that has already moved.
 *
 * The scan itself needs a cluster and a Context and is proved on the device;
 * what is pinned here is the arithmetic and the copy that decide what a
 * person reads before pressing Deploy.
 */
class BufferAdoptionTest {

    private val buffer = "CiydNyaptcDBXt3mreXxGRaLWkTfnPWh8DtC9gGqTiYy"

    /**
     * QA r4 §7: the Deploy sheet quoted "~1.8111 SOL, of which 0.9047 comes
     * back" for an upgrade the run then landed for 0.00113 SOL, because the
     * deployer adopted a whole buffer the sheet had not looked for. The
     * arithmetic that closes that gap is [Loader.outstanding] over the same
     * scan, so a whole buffer leaves only the fees the finish still costs.
     */
    @Test
    fun `an adopted buffer is not charged for twice`() {
        val estimate = Loader.estimateDeploy(180_000, upgrade = true)
        val writes = (estimate.fees / Loader.LAMPORTS_PER_SIGNATURE - 5).toInt()
        assertTrue(writes > 100)

        val fresh = Loader.outstanding(estimate, bufferAlreadyPaid = false, writesAlreadyLanded = 0)
        assertEquals(estimate.total, fresh)

        val whole = Loader.outstanding(estimate, bufferAlreadyPaid = true, writesAlreadyLanded = writes)
        // Only the handful of non-Write signatures the finish still pays for.
        assertEquals(5 * Loader.LAMPORTS_PER_SIGNATURE, whole)
        assertTrue(whole < fresh / 1_000)

        val half = Loader.outstanding(estimate, bufferAlreadyPaid = true, writesAlreadyLanded = writes / 2)
        assertTrue(half > whole)
        assertTrue(half < fresh)
    }

    @Test
    fun `the line says which buffer, how much of it is rent, and how far the upload got`() {
        val whole = BufferAdoption.detail(buffer, 933_607_480L, done = 180, chunks = 180)
        assertTrue(whole, "Ciyd…TiYy" in whole)
        assertTrue(whole, "0.9336 SOL" in whole)
        assertTrue(whole, "all 180 chunks are uploaded" in whole)

        val part = BufferAdoption.detail(buffer, 933_607_480L, done = 42, chunks = 180)
        assertTrue(part, "42 of 180 chunks" in part)

        val empty = BufferAdoption.detail(buffer, 933_607_480L, done = 0, chunks = 180)
        assertTrue(empty, "chunks" !in empty)
        assertTrue(empty, "rent is already on chain" in empty)
    }
}
