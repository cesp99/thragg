package to.eyed.thragg.solana.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claim, pinned to a transaction the faucet program accepted on devnet:
 * `4kP4c2PXewDTU6ptHcFF4anH82hRWKPri4Zxi8AZoephUft776epZB2ookyNMe9hczZja99eW15Y3k3V2EELMYsc`
 * in slot 492,621,215 — one claim by the key `AAApx4…CgZZ` for the payer
 * `BPM9Xt…UKwJ`. Its account list, its eight data bytes and the balances it
 * moved are what every constant here is checked against, so the layout
 * cannot drift from what the program expects without a test saying so.
 */
class PowFaucetTest {

    private val payer = Pubkey.of("BPM9XtpwnBHxCmZ1ujghe98Uf7ZuPaPiJAfCa82GUKwJ")
    private val key = Pubkey.of("AAApx4vMGVY9mqFVXxjbVHyrNKphFdSZeqqsvXWvCgZZ")
    private val receipt = Pubkey.of("2YQ69n1FVD5hvLqaRMLHyNZrG4ES7enkYowVYeuVVPzr")
    private val spec3 = Pubkey.of("7QR2Vrhedq3PKUkT25hUaoXCwXr82i6BRds5ZXhfcyzw")
    private val source3 = Pubkey.of("6yvwhesLJeE8fNWviosRoUtBP3VFUXE7SEhSP9fFRJ3Z")
    private val blockhash = "4zvwRjXUKGfvwnParsHAS3HuSVzV5cA4McphgmoCtajS"

    @Test
    fun `the discriminator is Anchor's global airdrop, as the wire showed it`() {
        assertEquals("71ad24ee26981675", PowFaucet.DISCRIMINATOR.joinToString("") { "%02x".format(it) })
        assertEquals("L1oa6RAB8MJ", Base58.encode(PowFaucet.DISCRIMINATOR))
    }

    @Test
    fun `spec and source PDAs are the accounts the program read and paid from`() {
        val spec = PowFaucet.spec(3)
        assertEquals(spec3, spec.address)
        assertEquals(source3, spec.source)
        assertEquals(listOf(3, 4), PowFaucet.specs.map { it.difficulty })
        // Two difficulties, two different windows to pay from.
        assertFalse(PowFaucet.spec(4).address == spec3)
    }

    @Test
    fun `the receipt PDA is the account the program created for that key`() {
        assertEquals(receipt, PowFaucet.receipt(key, 3))
        assertFalse(PowFaucet.receipt(key, 4) == receipt)
    }

    @Test
    fun `one claim is the six accounts in the program's order with the payer paid`() {
        val claim = PowFaucet.claim(payer, key, PowFaucet.spec(3))
        assertEquals(PowFaucet.PROGRAM_ID, claim.programId)
        assertArrayEquals(PowFaucet.DISCRIMINATOR, claim.data)
        assertEquals(
            listOf(
                AccountMeta(payer, isSigner = true, isWritable = true),
                AccountMeta(key, isSigner = true, isWritable = false),
                AccountMeta(receipt, isSigner = false, isWritable = true),
                AccountMeta(spec3, isSigner = false, isWritable = false),
                AccountMeta(source3, isSigner = false, isWritable = true),
                AccountMeta(Loader.SYSTEM_PROGRAM, isSigner = false, isWritable = false),
            ),
            claim.accounts,
        )
    }

    @Test
    fun `a single claim compiles to the header the accepted transaction had`() {
        // numRequiredSignatures 2, numReadonlySigned 1, numReadonlyUnsigned 3.
        val message = Message.compile(payer, listOf(PowFaucet.claim(payer, key, PowFaucet.spec(3))), blockhash)
        assertEquals(MessageHeader(2, 1, 3), message.header)
        assertEquals(payer, message.accountKeys[0])
        assertEquals(key, message.accountKeys[1])
        assertEquals(
            setOf(payer, key, receipt, spec3, source3, Loader.SYSTEM_PROGRAM, PowFaucet.PROGRAM_ID),
            message.accountKeys.toSet(),
        )
    }

    @Test
    fun `compute unit limit is tag 2 and a little-endian u32`() {
        val ix = PowFaucet.setComputeUnitLimit(320_000L)
        assertEquals(PowFaucet.COMPUTE_BUDGET, ix.programId)
        assertTrue(ix.accounts.isEmpty())
        assertArrayEquals(byteArrayOf(2, 0x00, 0xE2.toByte(), 0x04, 0x00), ix.data)
    }

    @Test
    fun `six three-A keys fit one packet and claim once each`() {
        val keys = List(PowFaucet.CLAIMS_PER_TX) { aaaKey(it) }
        val (message, claims) = PowFaucet.message(payer, keys, blockhash)
        assertEquals(6, claims)
        assertEquals(7, message.signerCount)
        val size = Transaction.unsigned(message).serialize().size
        assertTrue("$size bytes", size <= PowFaucet.PACKET_LIMIT)
        // A seventh would not fit; six is the batch for a reason.
        assertTrue(Transaction.unsigned(Message.compile(payer, listOf(PowFaucet.setComputeUnitLimit(1)) + PowFaucet.claims(payer, keys + aaaKey(9)), blockhash)).serialize().size > PowFaucet.PACKET_LIMIT)
    }

    @Test
    fun `a four-A key claims both specs alone and only the first when the batch is full`() {
        val lucky = aaaaKey()
        assertEquals(4, PowFaucet.leadingAs(lucky))
        assertEquals(2, PowFaucet.claims(payer, listOf(lucky)).size)
        val (_, alone) = PowFaucet.message(payer, listOf(lucky), blockhash)
        assertEquals(2, alone)
        val (full, claims) = PowFaucet.message(payer, List(5) { aaaKey(it) } + lucky, blockhash)
        assertEquals(6, claims)
        assertTrue(Transaction.unsigned(full).serialize().size <= PowFaucet.PACKET_LIMIT)
    }

    @Test
    fun `the AAA window agrees with Base58 on real keys and on random bytes`() {
        assertTrue(PowFaucet.hasAaaPrefix(key.bytes))
        assertTrue(PowFaucet.hasAaaPrefix(aaaaKey().bytes))
        assertFalse(PowFaucet.hasAaaPrefix(payer.bytes))
        assertFalse(PowFaucet.hasAaaPrefix(ByteArray(31)))
        val random = java.util.Random(7)
        repeat(2_000) {
            val bytes = ByteArray(32).also(random::nextBytes)
            assertEquals(Base58.encode(bytes).startsWith("AAA"), PowFaucet.hasAaaPrefix(bytes))
        }
        // The edges: the first value spelled AAA…, and the first spelled AAB….
        val lo = ByteArray(32).also { Base58.decode("AAA" + "1".repeat(41)).copyInto(it) }
        val hi = ByteArray(32).also { Base58.decode("AAB" + "1".repeat(41)).copyInto(it) }
        assertTrue(PowFaucet.hasAaaPrefix(lo))
        assertFalse(PowFaucet.hasAaaPrefix(hi))
        assertEquals(3, PowFaucet.leadingAs(key))
        assertEquals(0, PowFaucet.leadingAs(payer))
    }

    @Test
    fun `progress reads as one sentence, rate only once there is one`() {
        val early = PowFaucet.Progress(balance = 1_100_000_000L, target = 3_400_000_000L, mined = 0L, elapsedMs = 1_000L, inFlight = 0, keysFound = 0L)
        assertEquals("Mined 0 SOL · holds 1.1 SOL of 3.4 SOL", early.describe())
        val later = PowFaucet.Progress(balance = 1_560_000_000L, target = 3_400_000_000L, mined = 480_000_000L, elapsedMs = 12_000L, inFlight = 4, keysFound = 30L)
        assertEquals(2_400_000_000L, later.lamportsPerMinute)
        assertEquals("Mined 0.48 SOL · 2.4 SOL/min · holds 1.56 SOL of 3.4 SOL · 4 in flight", later.describe())
    }

    @Test
    fun `a claim nets the payout less the receipt and its share of the fee`() {
        // From the accepted transaction: 20,000,000 in, 810,624 to the receipt, 10,000 of fee for two signers.
        assertEquals(19_179_376L, PowFaucet.CLAIM_LAMPORTS - PowFaucet.RECEIPT_RENT - 2 * Loader.LAMPORTS_PER_SIGNATURE)
        assertTrue(PowFaucet.BOOTSTRAP_LAMPORTS > PowFaucet.RECEIPT_RENT + 7 * Loader.LAMPORTS_PER_SIGNATURE)
    }

    /** A 32-byte value inside the AAA window; not on the curve, which compilation does not care about. */
    private fun aaaKey(salt: Int): Pubkey {
        val bytes = ByteArray(32).also { Base58.decode("AAA" + "1".repeat(41)).copyInto(it) }
        // The window's low edge ends in five zero bytes; the salt lives there.
        bytes[31] = (1 + salt).toByte()
        return Pubkey(bytes).also { check(PowFaucet.leadingAs(it) == 3) { it.base58 } }
    }

    private fun aaaaKey(): Pubkey {
        val bytes = ByteArray(32).also { Base58.decode("AAAA" + "1".repeat(40)).copyInto(it) }
        bytes[31] = 0x33
        return Pubkey(bytes)
    }
}
