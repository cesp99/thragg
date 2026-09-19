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

    /**
     * The same arithmetic under Transaction V1, where three chunks share a
     * signature: a buffer holding every chunk leaves the finish's five, a
     * buffer holding two chunks has paid for no whole transaction yet, and
     * one holding four has paid for one.
     */
    @Test
    fun `an adopted V1 buffer is charged per transaction, rounded against the user`() {
        val elf = 180_000
        val estimate = Loader.estimateDeploy(elf, upgrade = true, format = TxFormat.V1)
        val chunks = (elf + Loader.writeChunkSize(TxFormat.V1) - 1) / Loader.writeChunkSize(TxFormat.V1)
        val writes = Loader.writeTransactions(chunks, TxFormat.V1)
        assertEquals(Loader.LAMPORTS_PER_SIGNATURE * (writes + 5), estimate.fees)
        assertTrue(writes in 40..60)

        fun adopted(done: Int) = AdoptableBuffer(
            Pubkey(ByteArray(32) { 1 }), Pubkey(ByteArray(32) { 2 }), 1_000L,
            BooleanArray(chunks) { it < done }, TxFormat.V1,
        )
        assertEquals(0, adopted(2).writesPaid)
        assertEquals(1, adopted(4).writesPaid)
        // 149 chunks are 50 transactions; a whole buffer paid for all fifty, not the 49 the floor would say.
        assertEquals(149, chunks)
        assertEquals(50, writes)
        assertEquals(49, adopted(chunks - 1).writesPaid)
        assertEquals(writes, adopted(chunks).writesPaid)
        assertEquals(
            5 * Loader.LAMPORTS_PER_SIGNATURE,
            Loader.outstanding(estimate, bufferAlreadyPaid = true, writesAlreadyLanded = adopted(chunks).writesPaid),
        )
        // Two landed chunks are priced as nothing paid: the remaining ones re-group, so this errs upward.
        assertEquals(
            estimate.total - estimate.bufferRent,
            Loader.outstanding(estimate, bufferAlreadyPaid = true, writesAlreadyLanded = adopted(2).writesPaid),
        )
        // A legacy adoption is one chunk, one transaction, as before.
        assertEquals(7, AdoptableBuffer(Pubkey(ByteArray(32) { 1 }), Pubkey(ByteArray(32) { 2 }), 1L, BooleanArray(9) { it < 7 }).writesPaid)
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
