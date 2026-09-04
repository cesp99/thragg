package to.eyed.thragg.solana.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireTest {

    private fun key(fill: Int) = Pubkey(ByteArray(32) { fill.toByte() })
    private val systemProgram = Pubkey(ByteArray(32))
    private val blockhashBytes = ByteArray(32) { 3 }
    private val blockhash = Base58.encode(blockhashBytes)

    private fun le32(value: Long) = ByteArray(4) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }
    private fun le64(value: Long) = ByteArray(8) { i -> ((value ushr (8 * i)) and 0xFF).toByte() }

    @Test
    fun `pubkey compares by content and round trips base58`() {
        val a = key(7)
        val b = Pubkey(ByteArray(32) { 7 })
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, key(8))
        assertEquals(a, Pubkey.of(a.base58))
        assertEquals(a.base58, a.toString())
        assertNull(Pubkey.ofOrNull("not base58 0O"))
        assertNull(Pubkey.ofOrNull("abc"))
        assertEquals(systemProgram, Pubkey.of("11111111111111111111111111111111"))
    }

    @Test
    fun `refuses a key that is not thirty-two bytes`() {
        try {
            Pubkey(ByteArray(31))
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `a transfer message serializes byte for byte`() {
        val payer = key(1)
        val to = key(2)
        val lamports = 1_500_000L
        val transfer = Instruction(
            programId = systemProgram,
            accounts = listOf(
                AccountMeta(payer, isSigner = true, isWritable = true),
                AccountMeta(to, isSigner = false, isWritable = true),
            ),
            data = le32(2) + le64(lamports),
        )
        val message = Message.compile(payer, listOf(transfer), blockhash)

        assertEquals(MessageHeader(1, 0, 1), message.header)
        assertEquals(listOf(payer, to, systemProgram), message.accountKeys)
        assertEquals(1, message.signerCount)
        assertEquals(0, message.indexOf(payer))
        assertEquals(-1, message.indexOf(key(9)))

        // Built by hand from the wire rules: header, compact key count, keys,
        // blockhash, compact instruction count, then per instruction the
        // program index, compact account count + indexes, compact data length + data.
        val expected = byteArrayOf(1, 0, 1) +
            byteArrayOf(3) + payer.bytes + to.bytes + systemProgram.bytes +
            blockhashBytes +
            byteArrayOf(1) +
            byteArrayOf(2) + byteArrayOf(2, 0, 1) + byteArrayOf(12) + le32(2) + le64(lamports)
        assertArrayEquals(expected, message.serialize())
        assertEquals(3 + 1 + 96 + 32 + 1 + 1 + 3 + 1 + 12, message.serialize().size)
    }

    @Test
    fun `compile orders keys by signer then writable and merges duplicates`() {
        val payer = key(1)
        val readonly = key(2)
        val signerThenWritable = key(3)
        val writable = key(4)
        val readonlySigner = key(5)
        val program = key(6)
        val first = Instruction(
            program,
            listOf(
                AccountMeta(readonly, isSigner = false, isWritable = false),
                AccountMeta(signerThenWritable, isSigner = true, isWritable = false),
                AccountMeta(writable, isSigner = false, isWritable = true),
                AccountMeta(readonlySigner, isSigner = true, isWritable = false),
                // The fee payer mentioned as a plain reader still lands in slot 0 as a writable signer.
                AccountMeta(payer, isSigner = false, isWritable = false),
            ),
            byteArrayOf(9),
        )
        val second = Instruction(
            program,
            listOf(AccountMeta(signerThenWritable, isSigner = false, isWritable = true)),
            byteArrayOf(),
        )
        val message = Message.compile(payer, listOf(first, second), blockhash)

        assertEquals(
            listOf(payer, signerThenWritable, readonlySigner, writable, readonly, program),
            message.accountKeys,
        )
        assertEquals(MessageHeader(numRequiredSignatures = 3, numReadonlySigned = 1, numReadonlyUnsigned = 2), message.header)
        assertEquals(5, message.instructions[0].programIdIndex)
        assertEquals(listOf(4, 1, 3, 2, 0), message.instructions[0].accountIndexes)
        assertEquals(listOf(1), message.instructions[1].accountIndexes)
        assertTrue(message.isSigner(readonlySigner))
        assertFalse(message.isSigner(writable))
    }

    @Test
    fun `a program that is also an account keeps its stronger flags`() {
        val payer = key(1)
        val program = key(2)
        val ix = Instruction(program, listOf(AccountMeta(program, isSigner = false, isWritable = true)), byteArrayOf())
        val message = Message.compile(payer, listOf(ix), blockhash)
        assertEquals(listOf(payer, program), message.accountKeys)
        assertEquals(MessageHeader(1, 0, 0), message.header)
    }

    @Test
    fun `message round trips through serialize and deserialize`() {
        val payer = key(1)
        val ix = Instruction(
            key(6),
            listOf(AccountMeta(key(2), true, false), AccountMeta(key(3), false, true)),
            ByteArray(200) { it.toByte() },
        )
        val message = Message.compile(payer, listOf(ix, ix), blockhash)
        val bytes = message.serialize()
        val back = Message.deserialize(bytes)
        assertEquals(message.header, back.header)
        assertEquals(message.accountKeys, back.accountKeys)
        assertArrayEquals(message.recentBlockhash, back.recentBlockhash)
        assertEquals(message.instructions.size, back.instructions.size)
        message.instructions.zip(back.instructions).forEach { (a, b) ->
            assertEquals(a.programIdIndex, b.programIdIndex)
            assertEquals(a.accountIndexes, b.accountIndexes)
            assertArrayEquals(a.data, b.data)
        }
        assertArrayEquals(bytes, back.serialize())
    }

    @Test
    fun `deserialize refuses a versioned message and trailing bytes`() {
        val message = Message.compile(key(1), emptyList(), blockhash)
        val bytes = message.serialize()
        try {
            Message.deserialize(byteArrayOf(0x80.toByte()) + bytes.copyOfRange(1, bytes.size))
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            Message.deserialize(bytes + byteArrayOf(0))
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `transaction slots fill one signer at a time`() {
        val payer = key(1)
        val other = key(2)
        val ix = Instruction(key(6), listOf(AccountMeta(other, true, true)), byteArrayOf(1))
        val message = Message.compile(payer, listOf(ix), blockhash)
        val unsigned = Transaction.unsigned(message)

        assertEquals(2, unsigned.signatures.size)
        assertFalse(unsigned.isFullySigned)
        assertNull(unsigned.signature)
        assertTrue(unsigned.requiresSignatureFrom(other))
        assertFalse(unsigned.requiresSignatureFrom(key(6)))

        val sigA = ByteArray(64) { 0x11 }
        val sigB = ByteArray(64) { 0x22 }
        val half = unsigned.withSignature(other, sigB)
        assertFalse(half.isFullySigned)
        assertNull(half.signature)
        assertArrayEquals(ByteArray(64), half.signatures[0])
        assertArrayEquals(sigB, half.signatures[1])
        // The original was not touched.
        assertArrayEquals(ByteArray(64), unsigned.signatures[1])

        val full = half.withSignature(payer, sigA)
        assertTrue(full.isFullySigned)
        assertEquals(Base58.encode(sigA), full.signature)

        try {
            full.withSignature(key(6), sigA)
            fail("a program id is not a signer")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            full.withSignature(payer, ByteArray(63))
            fail("a signature is 64 bytes")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `transaction serializes as count, slots, message and round trips`() {
        val payer = key(1)
        val message = Message.compile(payer, listOf(Instruction(key(6), emptyList(), byteArrayOf(5, 6))), blockhash)
        val sig = ByteArray(64) { (it + 1).toByte() }
        val tx = Transaction.unsigned(message).withSignature(payer, sig)
        val bytes = tx.serialize()
        assertArrayEquals(byteArrayOf(1) + sig + message.serialize(), bytes)

        val back = Transaction.deserialize(bytes)
        assertArrayEquals(sig, back.signatures[0])
        assertEquals(tx.signature, back.signature)
        assertArrayEquals(bytes, back.serialize())

        val viaBase64 = Transaction.deserialize(bytes.toBase64().fromBase64())
        assertArrayEquals(bytes, viaBase64.serialize())
    }

    @Test
    fun `base64 helpers use the standard padded alphabet`() {
        assertEquals("AAEC/w==", byteArrayOf(0, 1, 2, 0xFF.toByte()).toBase64())
        assertArrayEquals(byteArrayOf(0, 1, 2, 0xFF.toByte()), "AAEC/w==".fromBase64())
        assertEquals("", ByteArray(0).toBase64())
    }

    @Test
    fun `compile refuses a blockhash that is not thirty-two bytes`() {
        try {
            Message.compile(key(1), emptyList(), "abc")
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
