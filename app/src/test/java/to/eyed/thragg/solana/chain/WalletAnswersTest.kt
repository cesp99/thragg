package to.eyed.thragg.solana.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wallet may refresh the blockhash and add compute-budget instructions —
 * the Seeker's does — and must not touch anything else. Under Transaction
 * V1 the same latitude is the header's config; the format itself may not
 * change.
 */
class WalletAnswersTest {

    private val wallet = Keypair.generate()
    private val deployKey = Keypair.generate()
    private val blockhash = Base58.encode(ByteArray(32) { 7 })
    private val transfer = Loader.transfer(wallet.publicKey, deployKey.publicKey, 1_000_000L)

    private fun original() = Transaction.unsigned(Message.compile(wallet.publicKey, listOf(transfer), blockhash))

    private fun signedBy(keypair: Keypair, message: Message): Transaction =
        Transaction.unsigned(message).withSignature(keypair.publicKey, keypair.sign(message.serialize()))

    @Test
    fun `an untouched message is accepted`() {
        val tx = signedBy(wallet, original().message)
        val accepted = WalletAnswers.accept(original(), tx.serialize(), wallet.publicKey, 1)
        assertTrue(accepted.isFullySigned)
    }

    @Test
    fun `a fresh blockhash and a compute budget instruction in front are accepted`() {
        val budget = Instruction(WalletAnswers.COMPUTE_BUDGET, emptyList(), byteArrayOf(2, 0x40, 0x0d, 3, 0))
        val altered = Message.compile(wallet.publicKey, listOf(budget, transfer), Base58.encode(ByteArray(32) { 9 }))
        val tx = signedBy(wallet, altered)
        val accepted = WalletAnswers.accept(original(), tx.serialize(), wallet.publicKey, 1)
        assertEquals(2, accepted.message.instructions.size)
        assertTrue(accepted.isFullySigned)
    }

    @Test
    fun `a dropped instruction is refused`() {
        val budget = Instruction(WalletAnswers.COMPUTE_BUDGET, emptyList(), byteArrayOf(2, 0, 0, 0, 0))
        val altered = Message.compile(wallet.publicKey, listOf(budget), blockhash)
        val tx = signedBy(wallet, altered)
        val error = assertThrows(WalletException::class.java) {
            WalletAnswers.accept(original(), tx.serialize(), wallet.publicKey, 1)
        }
        assertTrue(error.message!!.contains("dropped"))
    }

    @Test
    fun `an added transfer is refused`() {
        val extra = Loader.transfer(wallet.publicKey, Keypair.generate().publicKey, 5L)
        val altered = Message.compile(wallet.publicKey, listOf(transfer, extra), blockhash)
        val tx = signedBy(wallet, altered)
        val error = assertThrows(WalletException::class.java) {
            WalletAnswers.accept(original(), tx.serialize(), wallet.publicKey, 1)
        }
        assertTrue(error.message!!.contains("added"))
    }

    @Test
    fun `a changed fee payer is refused`() {
        val altered = Message.compile(deployKey.publicKey, listOf(transfer), blockhash)
        val tx = signedBy(wallet, altered)
        assertThrows(WalletException::class.java) {
            WalletAnswers.accept(original(), tx.serialize(), wallet.publicKey, 1)
        }
    }

    // ---- Transaction V1 ----------------------------------------------------

    private val v1Config = TxFormatPolicy.config(TxFormat.V1, listOf(transfer))

    private fun originalV1() =
        Transaction.unsigned(Message.compile(wallet.publicKey, listOf(transfer), blockhash, TxFormat.V1, v1Config))

    @Test
    fun `an untouched V1 message is accepted and comes back as V1`() {
        val tx = signedBy(wallet, originalV1().message)
        val accepted = WalletAnswers.accept(originalV1(), tx.serialize(), wallet.publicKey, 1)
        assertEquals(TxFormat.V1, accepted.message.format)
        assertEquals(v1Config, accepted.message.config)
        assertTrue(accepted.isFullySigned)
    }

    /** What a wallet's compute-budget instructions become in V1: header fields, which are its to set. */
    @Test
    fun `a V1 answer with a fresh lifetime and a changed config is accepted`() {
        val theirs = TxConfig(priorityFeeLamports = 5_000L, computeUnitLimit = 50_000L, loadedAccountsDataSize = 1L shl 20)
        val altered = Message.compile(wallet.publicKey, listOf(transfer), Base58.encode(ByteArray(32) { 9 }), TxFormat.V1, theirs)
        val tx = signedBy(wallet, altered)
        val accepted = WalletAnswers.accept(originalV1(), tx.serialize(), wallet.publicKey, 1)
        assertEquals(theirs, accepted.message.config)
        assertArrayEquals(altered.recentBlockhash, accepted.message.recentBlockhash)
        assertTrue(accepted.isFullySigned)
        // And a compute-budget instruction in the body — ignored by the runtime, tolerated here as before.
        val budget = Instruction(WalletAnswers.COMPUTE_BUDGET, emptyList(), byteArrayOf(2, 0x40, 0x0d, 3, 0))
        val withIx = signedBy(wallet, Message.compile(wallet.publicKey, listOf(budget, transfer), blockhash, TxFormat.V1, theirs))
        assertEquals(2, WalletAnswers.accept(originalV1(), withIx.serialize(), wallet.publicKey, 1).message.instructions.size)
    }

    @Test
    fun `a V1 answer that drops, adds or repays is refused like a legacy one`() {
        val dropped = signedBy(wallet, Message.compile(wallet.publicKey, emptyList(), blockhash, TxFormat.V1, v1Config))
        assertTrue(assertThrows(WalletException::class.java) {
            WalletAnswers.accept(originalV1(), dropped.serialize(), wallet.publicKey, 1)
        }.message!!.contains("dropped"))
        val extra = Loader.transfer(wallet.publicKey, Keypair.generate().publicKey, 5L)
        val added = signedBy(wallet, Message.compile(wallet.publicKey, listOf(transfer, extra), blockhash, TxFormat.V1, v1Config))
        assertTrue(assertThrows(WalletException::class.java) {
            WalletAnswers.accept(originalV1(), added.serialize(), wallet.publicKey, 1)
        }.message!!.contains("added"))
        val repaid = signedBy(wallet, Message.compile(deployKey.publicKey, listOf(transfer), blockhash, TxFormat.V1, v1Config))
        assertThrows(WalletException::class.java) {
            WalletAnswers.accept(originalV1(), repaid.serialize(), wallet.publicKey, 1)
        }
        // The wallet's signature has to be over the V1 bytes it returned, not over the legacy spelling.
        val legacyBytes = Message.compile(wallet.publicKey, listOf(transfer), blockhash).serialize()
        val crossSigned = originalV1().withSignature(wallet.publicKey, wallet.sign(legacyBytes))
        assertTrue(assertThrows(WalletException::class.java) {
            WalletAnswers.accept(originalV1(), crossSigned.serialize(), wallet.publicKey, 1)
        }.message!!.contains("does not verify"))
    }

    @Test
    fun `an answer in the other format is a shape this app cannot read`() {
        val legacyAnswer = signedBy(wallet, original().message)
        val error = assertThrows(WalletException::class.java) {
            WalletAnswers.accept(originalV1(), legacyAnswer.serialize(), wallet.publicKey, 2)
        }
        assertEquals("Seed Vault returned transaction 2 in a shape this app cannot read", error.message)
        val v1Answer = signedBy(wallet, originalV1().message)
        val other = assertThrows(WalletException::class.java) {
            WalletAnswers.accept(original(), v1Answer.serialize(), wallet.publicKey, 3)
        }
        assertEquals("Seed Vault returned transaction 3 in a shape this app cannot read", other.message)
        // A v0 envelope (the unsupported prefix) is the same refusal.
        val bytes = v1Answer.serialize().copyOf().also { it[0] = 0x80.toByte() }
        assertEquals(
            "Seed Vault returned transaction 4 in a shape this app cannot read",
            assertThrows(WalletException::class.java) { WalletAnswers.accept(originalV1(), bytes, wallet.publicKey, 4) }.message,
        )
    }

    @Test
    fun `a missing or wrong signature is refused`() {
        val unsigned = original()
        assertThrows(WalletException::class.java) {
            WalletAnswers.accept(original(), unsigned.serialize(), wallet.publicKey, 1)
        }
        // Sixty-four bytes in the wallet's slot that the wallet never produced.
        val wrong = original().withSignature(wallet.publicKey, ByteArray(Transaction.SIGNATURE_SIZE) { 1 })
        assertThrows(WalletException::class.java) {
            WalletAnswers.accept(original(), wrong.serialize(), wallet.publicKey, 1)
        }
    }
}
