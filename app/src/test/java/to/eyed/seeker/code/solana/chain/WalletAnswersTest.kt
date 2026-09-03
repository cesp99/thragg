package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wallet may refresh the blockhash and add compute-budget instructions —
 * the Seeker's does — and must not touch anything else.
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
