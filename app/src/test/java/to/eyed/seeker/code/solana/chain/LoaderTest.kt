package to.eyed.seeker.code.solana.chain

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loader's bytes, checked against the reference encodings rather than
 * against each other. A wrong tag or a swapped account here is a transaction
 * the cluster rejects with "invalid instruction data" or "incorrect
 * authority" and nothing more, so every builder is pinned byte-for-byte and
 * every meta list is pinned in order with its flags.
 *
 * Keys are compared by their Base58 spelling, not by `==`: [Pubkey] wraps a
 * `ByteArray`, and this test should not depend on how its equality is
 * defined.
 */
class LoaderTest {

    private fun key(fill: Int): Pubkey = Pubkey(ByteArray(32) { fill.toByte() })

    private val payer = key(1)
    private val buffer = key(2)
    private val program = key(3)
    private val programData = key(4)
    private val authority = key(5)
    private val other = key(6)

    /** `key:sw` — the pubkey's Base58, `s` when a signer, `w` when writable. */
    private fun metas(ix: Instruction): List<String> = ix.accounts.map {
        it.pubkey.base58 + ":" + (if (it.isSigner) "s" else "-") + (if (it.isWritable) "w" else "-")
    }

    private fun u32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun u64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    // ---- ids ---------------------------------------------------------------

    @Test
    fun `well-known ids decode to thirty-two bytes`() {
        assertEquals("BPFLoaderUpgradeab1e11111111111111111111111", Loader.PROGRAM_ID.base58)
        assertEquals("11111111111111111111111111111111", Loader.SYSTEM_PROGRAM.base58)
        assertEquals("SysvarRent111111111111111111111111111111111", Loader.SYSVAR_RENT.base58)
        assertEquals("SysvarC1ock11111111111111111111111111111111", Loader.SYSVAR_CLOCK.base58)
        assertArrayEquals(ByteArray(32), Loader.SYSTEM_PROGRAM.bytes)
    }

    // ---- system program ----------------------------------------------------

    @Test
    fun `createAccount is tag 0, lamports, space, owner`() {
        val ix = Loader.createAccount(payer, buffer, lamports = 1_234_567L, space = 4_096L, owner = Loader.PROGRAM_ID)
        assertEquals(Loader.SYSTEM_PROGRAM.base58, ix.programId.base58)
        assertArrayEquals(u32(0) + u64(1_234_567L) + u64(4_096L) + Loader.PROGRAM_ID.bytes, ix.data)
        assertEquals(listOf("${payer.base58}:sw", "${buffer.base58}:sw"), metas(ix))
    }

    @Test
    fun `transfer is tag 2 and lamports`() {
        val ix = Loader.transfer(payer, other, 500_000_000L)
        assertEquals(Loader.SYSTEM_PROGRAM.base58, ix.programId.base58)
        assertArrayEquals(u32(2) + u64(500_000_000L), ix.data)
        assertEquals(listOf("${payer.base58}:sw", "${other.base58}:-w"), metas(ix))
    }

    // ---- loader instructions -----------------------------------------------

    @Test
    fun `initializeBuffer is a bare tag 0 and the authority does not sign`() {
        val ix = Loader.initializeBuffer(buffer, authority)
        assertEquals(Loader.PROGRAM_ID.base58, ix.programId.base58)
        assertArrayEquals(u32(0), ix.data)
        assertEquals(listOf("${buffer.base58}:-w", "${authority.base58}:--"), metas(ix))
    }

    @Test
    fun `write is tag 1, offset, u64 length, bytes`() {
        val chunk = byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x02)
        val ix = Loader.write(buffer, authority, offset = 1011, chunk = chunk)
        assertEquals(Loader.PROGRAM_ID.base58, ix.programId.base58)
        assertArrayEquals(u32(1) + u32(1011) + u64(5L) + chunk, ix.data)
        assertEquals(listOf("${buffer.base58}:-w", "${authority.base58}:s-"), metas(ix))
    }

    @Test
    fun `write with an empty chunk is sixteen bytes`() {
        assertEquals(16, Loader.write(buffer, authority, 0, ByteArray(0)).data.size)
    }

    @Test
    fun `deployWithMaxDataLen is tag 2 plus u64, with the loader's eight accounts in order`() {
        val ix = Loader.deployWithMaxDataLen(payer, programData, program, buffer, authority, maxDataLen = 200_000L)
        assertEquals(Loader.PROGRAM_ID.base58, ix.programId.base58)
        assertArrayEquals(u32(2) + u64(200_000L), ix.data)
        assertEquals(
            listOf(
                "${payer.base58}:sw",
                "${programData.base58}:-w",
                "${program.base58}:sw",
                "${buffer.base58}:-w",
                "${Loader.SYSVAR_RENT.base58}:--",
                "${Loader.SYSVAR_CLOCK.base58}:--",
                "${Loader.SYSTEM_PROGRAM.base58}:--",
                "${authority.base58}:s-",
            ),
            metas(ix),
        )
    }

    @Test
    fun `a fresh deploy creates the program account first, then deploys into it`() {
        val ixs = Loader.deployProgram(
            payer, programData, program, buffer, authority,
            programRent = 1_141_440L, maxDataLen = 400_000L,
        )
        assertEquals(2, ixs.size)
        // The loader checks that the program account already exists, is its
        // own, and is 36 rent-exempt bytes; the system program has to make it
        // in the same transaction, before the deploy runs.
        val create = ixs[0]
        assertEquals(Loader.SYSTEM_PROGRAM.base58, create.programId.base58)
        assertArrayEquals(u32(0) + u64(1_141_440L) + u64(Loader.PROGRAM_SIZE.toLong()) + Loader.PROGRAM_ID.bytes, create.data)
        assertEquals(listOf("${payer.base58}:sw", "${program.base58}:sw"), metas(create))
        val deploy = ixs[1]
        assertEquals(Loader.PROGRAM_ID.base58, deploy.programId.base58)
        assertArrayEquals(u32(2) + u64(400_000L), deploy.data)
        assertEquals("${program.base58}:sw", metas(deploy)[2])
        // As the deployer sends it — the payer is the authority too — two
        // sign the same message: the payer as fee payer, the program because
        // it is being created.
        val asSent = Loader.deployProgram(payer, programData, program, buffer, authority = payer, programRent = 1L, maxDataLen = 2L)
        val message = Message.compile(payer, asSent, Base58.encode(ByteArray(32)))
        assertEquals(2, message.signerCount)
        assertTrue(message.isSigner(payer))
        assertTrue(message.isSigner(program))
    }

    @Test
    fun `max_data_len reserves exactly the ELF`() {
        assertEquals(1, Loader.MAX_DATA_LEN_FACTOR)
        assertEquals(200_000L, Loader.maxDataLen(200_000))
        assertEquals(0L, Loader.maxDataLen(0))
    }

    @Test
    fun `extendProgram is tag 6 plus u32, paid by a signer, with no authority`() {
        val ix = Loader.extendProgram(programData, program, payer, additionalBytes = 12_345)
        assertEquals(Loader.PROGRAM_ID.base58, ix.programId.base58)
        assertArrayEquals(u32(6) + u32(12_345), ix.data)
        assertEquals(
            listOf(
                "${programData.base58}:-w",
                "${program.base58}:-w",
                "${Loader.SYSTEM_PROGRAM.base58}:--",
                "${payer.base58}:sw",
            ),
            metas(ix),
        )
    }

    @Test
    fun `upgrade is a bare tag 3 with seven accounts`() {
        val ix = Loader.upgrade(programData, program, buffer, spill = payer, authority = authority)
        assertArrayEquals(u32(3), ix.data)
        assertEquals(
            listOf(
                "${programData.base58}:-w",
                "${program.base58}:-w",
                "${buffer.base58}:-w",
                "${payer.base58}:-w",
                "${Loader.SYSVAR_RENT.base58}:--",
                "${Loader.SYSVAR_CLOCK.base58}:--",
                "${authority.base58}:s-",
            ),
            metas(ix),
        )
    }

    @Test
    fun `setAuthority is tag 4 for buffers and programdata alike`() {
        val onBuffer = Loader.setBufferAuthority(buffer, authority, other)
        assertArrayEquals(u32(4), onBuffer.data)
        assertEquals(listOf("${buffer.base58}:-w", "${authority.base58}:s-", "${other.base58}:--"), metas(onBuffer))

        val onProgramData = Loader.setUpgradeAuthority(programData, authority, other)
        assertArrayEquals(u32(4), onProgramData.data)
        assertEquals(
            listOf("${programData.base58}:-w", "${authority.base58}:s-", "${other.base58}:--"),
            metas(onProgramData),
        )
    }

    @Test
    fun `closeProgram is tag 5 with the program as a fourth writable account`() {
        val ix = Loader.closeProgram(programData, recipient = payer, authority = authority, program = program)
        assertEquals(Loader.PROGRAM_ID.base58, ix.programId.base58)
        assertArrayEquals(u32(5), ix.data)
        assertEquals(
            listOf(
                "${programData.base58}:-w",
                "${payer.base58}:-w",
                "${authority.base58}:s-",
                "${program.base58}:-w",
            ),
            metas(ix),
        )
    }

    @Test
    fun `closeBuffer is tag 5 with three accounts`() {
        val ix = Loader.closeBuffer(buffer, recipient = payer, authority = authority)
        assertArrayEquals(u32(5), ix.data)
        assertEquals(listOf("${buffer.base58}:-w", "${payer.base58}:-w", "${authority.base58}:s-"), metas(ix))
    }

    // ---- account parsing ---------------------------------------------------

    @Test
    fun `parses a buffer with and without an authority`() {
        val withAuthority = u32(1) + byteArrayOf(1) + authority.bytes + ByteArray(10)
        val state = Loader.parse(withAuthority)
        assertTrue(state is Loader.State.Buffer)
        assertEquals(authority.base58, (state as Loader.State.Buffer).authority?.base58)

        val immutable = u32(1) + byteArrayOf(0) + ByteArray(32)
        val none = Loader.parse(immutable)
        assertTrue(none is Loader.State.Buffer)
        assertNull((none as Loader.State.Buffer).authority)
    }

    @Test
    fun `parses the thirty-six byte program account`() {
        val state = Loader.parse(u32(2) + programData.bytes)
        assertTrue(state is Loader.State.Program)
        assertEquals(programData.base58, (state as Loader.State.Program).programData.base58)
    }

    @Test
    fun `parses programdata with slot and authority, then the ELF`() {
        val elf = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
        val bytes = u32(3) + u64(287_654_321L) + byteArrayOf(1) + authority.bytes + elf
        assertEquals(Loader.PROGRAMDATA_HEADER + elf.size, bytes.size)
        val state = Loader.parse(bytes)
        assertTrue(state is Loader.State.ProgramData)
        state as Loader.State.ProgramData
        assertEquals(287_654_321L, state.slot)
        assertEquals(authority.base58, state.authority?.base58)

        val immutable = Loader.parse(u32(3) + u64(7L) + byteArrayOf(0) + ByteArray(32))
        assertTrue(immutable is Loader.State.ProgramData)
        assertNull((immutable as Loader.State.ProgramData).authority)
        assertEquals(7L, immutable.slot)
    }

    @Test
    fun `parses uninitialized, which is what a closed programdata becomes`() {
        assertEquals(Loader.State.Uninitialized, Loader.parse(u32(0)))
        assertEquals(Loader.State.Uninitialized, Loader.parse(u32(0) + ByteArray(45)))
    }

    @Test
    fun `rejects short, truncated and garbage data`() {
        assertNull(Loader.parse(ByteArray(0)))
        assertNull(Loader.parse(byteArrayOf(1, 0, 0)))
        // Right tag, header cut short.
        assertNull(Loader.parse(u32(1) + byteArrayOf(1) + ByteArray(31)))
        assertNull(Loader.parse(u32(2) + ByteArray(31)))
        assertNull(Loader.parse(u32(3) + u64(1L) + byteArrayOf(1) + ByteArray(20)))
        // A tag the loader does not have.
        assertNull(Loader.parse(u32(4) + ByteArray(64)))
        assertNull(Loader.parse(u32(-1) + ByteArray(64)))
        // An option byte that is neither present nor absent.
        assertNull(Loader.parse(u32(1) + byteArrayOf(2) + ByteArray(32)))
        assertNull(Loader.parse(u32(3) + u64(1L) + byteArrayOf(9) + ByteArray(32)))
        // An ELF handed in by mistake.
        assertNull(Loader.parse(byteArrayOf(0x7F, 0x45, 0x4C, 0x46) + ByteArray(60)))
    }

    // ---- rent --------------------------------------------------------------

    @Test
    fun `rent formula matches the cluster's known figures`() {
        assertEquals(890_880L, Loader.rentExempt(0))
        assertEquals(1_141_440L, Loader.rentExempt(Loader.PROGRAM_SIZE))
        assertEquals(890_880L + 6_960L, Loader.rentExempt(1))
    }

    // ---- chunking ----------------------------------------------------------

    @Test
    fun `write chunk size is the CLI's figure for one signer`() {
        val size = Loader.writeChunkSize()
        assertTrue("chunk $size", size in 1 until Loader.MAX_TRANSACTION_SIZE)
        // header 3 + keys 1+96 + blockhash 32 + one instruction 1+21 = 154
        // (program index, two account indices, and 16 bytes of data: tag,
        // offset, u64 length), plus 1+64 for the signature = 219; 1232 - 219 - 1.
        assertEquals(1012, size)
        assertEquals(size, Loader.writeChunkSize())
    }

    @Test
    fun `a full-size write transaction fits the packet and one more byte does not`() {
        val size = Loader.writeChunkSize()
        val blockhash = Base58.encode(ByteArray(32) { 0x2A })
        fun txBytes(chunk: Int): Int {
            val ix = Loader.write(buffer, authority, 0, ByteArray(chunk) { 0x7F })
            val message = Message.compile(authority, listOf(ix), blockhash)
            return Transaction.unsigned(message).serialize().size
        }
        assertTrue(txBytes(size) <= Loader.MAX_TRANSACTION_SIZE)
        assertEquals(Loader.MAX_TRANSACTION_SIZE, txBytes(size))
        assertTrue(txBytes(size + 1) > Loader.MAX_TRANSACTION_SIZE)
    }

    @Test
    fun `chunks are contiguous and add back up to the input`() {
        val elf = ByteArray(Loader.writeChunkSize() * 3 + 17) { (it % 251).toByte() }
        val chunks = Loader.chunks(elf)
        assertEquals(4, chunks.size)
        var expectedOffset = 0
        val joined = ByteArray(elf.size)
        for ((offset, bytes) in chunks) {
            assertEquals(expectedOffset, offset)
            assertTrue(bytes.isNotEmpty())
            assertTrue(bytes.size <= Loader.writeChunkSize())
            bytes.copyInto(joined, offset)
            expectedOffset += bytes.size
        }
        assertEquals(elf.size, expectedOffset)
        assertArrayEquals(elf, joined)
        assertEquals(17, chunks.last().second.size)
    }

    @Test
    fun `chunks of nothing is nothing, and one short chunk is one chunk`() {
        assertTrue(Loader.chunks(ByteArray(0)).isEmpty())
        val one = Loader.chunks(ByteArray(100))
        assertEquals(1, one.size)
        assertEquals(0, one[0].first)
        assertEquals(100, one[0].second.size)
    }

    // ---- estimate ----------------------------------------------------------

    @Test
    fun `fresh deploy estimate is buffer plus programdata plus program plus fees`() {
        val elf = 200_000
        val estimate = Loader.estimateDeploy(elf, upgrade = false)
        assertEquals(Loader.rentExempt(Loader.BUFFER_HEADER + elf), estimate.bufferRent)
        // Exactly the ELF: a bigger rebuild extends the account, it does not pre-pay for it.
        assertEquals(Loader.rentExempt(Loader.PROGRAMDATA_HEADER + elf), estimate.programDataRent)
        assertEquals(1_141_440L, estimate.programRent)
        val writes = (elf + Loader.writeChunkSize() - 1) / Loader.writeChunkSize()
        assertTrue(estimate.fees >= Loader.LAMPORTS_PER_SIGNATURE * writes)
        assertEquals(Loader.LAMPORTS_PER_SIGNATURE * (writes + 5), estimate.fees)
        assertEquals(
            estimate.bufferRent + estimate.programDataRent + estimate.programRent + estimate.fees,
            estimate.total,
        )
        assertEquals(estimate.programDataRent + estimate.programRent, estimate.permanent)
        // A 200 KB program: two accounts of 1.4 SOL each, one of them refunded.
        assertTrue(estimate.total in 2_500_000_000L..3_200_000_000L)
    }

    @Test
    fun `upgrade estimate pays for the buffer and fees only`() {
        val estimate = Loader.estimateDeploy(50_000, upgrade = true)
        assertEquals(Loader.rentExempt(Loader.BUFFER_HEADER + 50_000), estimate.bufferRent)
        assertEquals(0L, estimate.programDataRent)
        assertEquals(0L, estimate.programRent)
        assertEquals(0L, estimate.permanent)
        assertTrue(estimate.fees > 0L)
        assertEquals(estimate.bufferRent + estimate.fees, estimate.total)
    }

    @Test
    fun `an empty ELF still costs the accounts`() {
        val estimate = Loader.estimateDeploy(0, upgrade = false)
        assertEquals(Loader.rentExempt(Loader.BUFFER_HEADER), estimate.bufferRent)
        assertEquals(Loader.LAMPORTS_PER_SIGNATURE * 5, estimate.fees)
        assertEquals(1_141_440L, estimate.programRent)
        assertTrue(estimate.total > estimate.permanent)
    }

    // ---- formatting --------------------------------------------------------

    @Test
    fun `lamportsToSol shows four decimals and drops the trailing zeros`() {
        assertEquals("0 SOL", Loader.lamportsToSol(0L))
        assertEquals("1.4321 SOL", Loader.lamportsToSol(1_432_100_000L))
        assertEquals("1 SOL", Loader.lamportsToSol(1_000_000_000L))
        assertEquals("1.5 SOL", Loader.lamportsToSol(1_500_000_000L))
        assertEquals("2 SOL", Loader.lamportsToSol(2_000_000_000L))
        assertEquals("0.0011 SOL", Loader.lamportsToSol(1_141_440L))
        assertEquals("0.0009 SOL", Loader.lamportsToSol(890_880L))
        assertEquals("0.1 SOL", Loader.lamportsToSol(100_000_000L))
        assertEquals("1234.5679 SOL", Loader.lamportsToSol(1_234_567_890_123L))
    }

    @Test
    fun `lamportsToSol does not round a fee down to nothing`() {
        assertEquals("<0.0001 SOL", Loader.lamportsToSol(5_000L))
        assertEquals("<0.0001 SOL", Loader.lamportsToSol(49_999L))
        assertEquals("0.0001 SOL", Loader.lamportsToSol(50_000L))
        assertEquals("-0.5 SOL", Loader.lamportsToSol(-500_000_000L))
    }
}
