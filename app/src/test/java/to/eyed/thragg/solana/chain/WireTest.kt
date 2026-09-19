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

    // ---- Transaction V1 (SIMD-0385) ---------------------------------------------

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** The upstream vectors' keys: Ed25519 seeds of all-0x01 (payer), all-0x02 (authority), all-0x03 (buffer). */
    private val vecPayer = Keypair.fromSeed(ByteArray(32) { 1 })
    private val vecAuthority = Keypair.fromSeed(ByteArray(32) { 2 })
    private val vecBuffer = Keypair.fromSeed(ByteArray(32) { 3 }).publicKey
    private val vecBlockhash = Base58.encode(ByteArray(32) { 7 })
    private val loader = Pubkey.of("BPFLoaderUpgradeab1e11111111111111111111111")

    /** solana-message 5.0.0: one loader Write, priority fee 1,000,000 and 200,000 compute units in the header. */
    private val vectorAMessage = "8102010107000000" +
        "0707070707070707070707070707070707070707070707070707070707070707" + "0104" +
        "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c" +
        "8139770ea87d175f56a35466c34c7ecccb8d8a91b4ee37a25df60f5b8fc9b394" +
        "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1" +
        "02a8f6914e88a1b0e210153ef763ae2b00c2b93d16c124d2c0537a1004800000" +
        "40420f0000000000" + "400d0300" + "03021a00" + "0201" +
        "01000000" + "05000000" + "0a00000000000000" + "aaaaaaaaaaaaaaaaaaaa"
    private val vectorASignatures =
        "9352bb870941041a0c184a35c25196b7eb1a1809b863b6a98e78246fcc4836c6c5074b5530f093607be33d82ce8c7d322731ff2660d421022692b6fd6991bb02" +
            "2a95c418a99eb0f426362914403149e225cfea8e777c390035e3961639c58dd4a46e739b89aea7ecfda2c601aed4ed788265419185fec0c46dbbcf6dbbe4d302"

    /** solana-transaction 5.0.0: no config, one System transfer of 42 lamports from the payer to the buffer key. */
    private val vectorBMessage = "8101000100000000" +
        "0707070707070707070707070707070707070707070707070707070707070707" + "0103" +
        "8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c" +
        "ed4928c628d1c2c6eae90338905995612959273a5c63f93636c14614ac8737d1" +
        "0000000000000000000000000000000000000000000000000000000000000000" +
        "02020c00" + "0001" + "02000000" + "2a00000000000000"
    private val vectorBSignature =
        "0a380981608960549dbe7144cd1d0c72b242f4060a578afcc69e9f8d0d6e5dcdabf29d83102377cd25a71a8ef5fa5fe38aba6f468252f0ad195f488c5cbe8e01"

    private fun vectorAInstruction() = Instruction(
        loader,
        listOf(
            AccountMeta(vecBuffer, isSigner = false, isWritable = true),
            AccountMeta(vecAuthority.publicKey, isSigner = true, isWritable = false),
        ),
        le32(1) + le32(5) + le64(10) + ByteArray(10) { 0xAA.toByte() },
    )

    @Test
    fun `the vector keys derive from their seeds`() {
        assertEquals("AKnL4NNf3DGWZJS6cPknBuEGnVsV4A4m5tgebLHaRSZ9", vecPayer.publicKey.base58)
        assertEquals("9hSR6S7WPtxmTojgo6GG3k4yDPecgJY292j7xrsUGWBu", vecAuthority.publicKey.base58)
        assertEquals("GyGKxMyg1p9SsHfm15MkNUu1u9TN2JtTspcdmrtGUdse", vecBuffer.base58)
    }

    @Test
    fun `vector A - a V1 write with a priority fee and a compute limit matches upstream byte for byte`() {
        val config = TxConfig(priorityFeeLamports = 1_000_000L, computeUnitLimit = 200_000L)
        val message = Message.compile(vecPayer.publicKey, listOf(vectorAInstruction()), vecBlockhash, TxFormat.V1, config)
        assertEquals(TxFormat.V1, message.format)
        assertEquals(listOf(vecPayer.publicKey, vecAuthority.publicKey, vecBuffer, loader), message.accountKeys)
        assertEquals(MessageHeader(2, 1, 1), message.header)
        assertEquals(0b111L, config.mask)
        assertEquals(vectorAMessage, message.serialize().hex())

        // Both signers sign exactly the message bytes; the envelope is message then signatures, nothing else.
        val bytes = message.serialize()
        val tx = Transaction.unsigned(message)
            .withSignature(vecPayer.publicKey, vecPayer.sign(bytes))
            .withSignature(vecAuthority.publicKey, vecAuthority.sign(bytes))
        assertTrue(tx.isFullySigned)
        assertEquals(vectorAMessage + vectorASignatures, tx.serialize().hex())
        assertEquals(342, tx.serialize().size)
        assertTrue(tx.fits)
    }

    @Test
    fun `vector B - a V1 transfer with no config matches upstream byte for byte`() {
        val transfer = Instruction(
            systemProgram,
            listOf(
                AccountMeta(vecPayer.publicKey, isSigner = true, isWritable = true),
                AccountMeta(vecBuffer, isSigner = false, isWritable = true),
            ),
            le32(2) + le64(42),
        )
        val message = Message.compile(vecPayer.publicKey, listOf(transfer), vecBlockhash, TxFormat.V1)
        assertEquals(listOf(vecPayer.publicKey, vecBuffer, systemProgram), message.accountKeys)
        assertEquals(vectorBMessage, message.serialize().hex())
        val tx = Transaction.unsigned(message).withSignature(vecPayer.publicKey, vecPayer.sign(message.serialize()))
        assertEquals(vectorBMessage + vectorBSignature, tx.serialize().hex())
    }

    @Test
    fun `the upstream vectors deserialize back to what compiled them`() {
        val a = Transaction.deserialize(hex(vectorAMessage + vectorASignatures))
        assertEquals(TxFormat.V1, a.message.format)
        assertEquals(TxConfig(priorityFeeLamports = 1_000_000L, computeUnitLimit = 200_000L), a.message.config)
        assertEquals(MessageHeader(2, 1, 1), a.message.header)
        assertEquals(listOf(vecPayer.publicKey, vecAuthority.publicKey, vecBuffer, loader), a.message.accountKeys)
        assertEquals(1, a.message.instructions.size)
        assertEquals(3, a.message.instructions[0].programIdIndex)
        assertEquals(listOf(2, 1), a.message.instructions[0].accountIndexes)
        assertArrayEquals(vectorAInstruction().data, a.message.instructions[0].data)
        assertEquals(2, a.signatures.size)
        assertTrue(a.isFullySigned)
        assertTrue(Ed25519.verify(vecPayer.publicKey, a.message.serialize(), a.signatures[0]))
        assertTrue(Ed25519.verify(vecAuthority.publicKey, a.message.serialize(), a.signatures[1]))
        assertEquals(vectorAMessage + vectorASignatures, a.serialize().hex())

        val b = Message.deserialize(hex(vectorBMessage))
        assertEquals(TxConfig.NONE, b.config)
        assertEquals(TxFormat.V1, b.format)
        assertEquals(vectorBMessage, b.serialize().hex())
        assertEquals(vectorBMessage + vectorBSignature, Transaction.deserialize(hex(vectorBMessage + vectorBSignature)).serialize().hex())
    }

    @Test
    fun `a V1 message lays out headers before payloads and config values in bit order`() {
        val payer = key(1)
        val first = Instruction(key(6), listOf(AccountMeta(key(2), true, false)), byteArrayOf(9, 8, 7))
        val second = Instruction(key(7), listOf(AccountMeta(key(3), false, true), AccountMeta(key(2), false, false)), ByteArray(300) { 1 })
        val config = TxConfig(
            priorityFeeLamports = 0x0102030405060708L,
            computeUnitLimit = 0xFFFF_FFFFL,
            loadedAccountsDataSize = 65_536L,
            heapSize = 64L * 1024L,
        )
        val message = Message.compile(payer, listOf(first, second), blockhash, TxFormat.V1, config)
        assertEquals(0b11111L, config.mask)
        assertEquals(20, config.size)
        // keys: payer, signer-readonly key(2), writable key(3), then programs 6 and 7
        assertEquals(listOf(payer, key(2), key(3), key(6), key(7)), message.accountKeys)
        assertEquals(MessageHeader(2, 1, 2), message.header)
        val expected = byteArrayOf(0x81.toByte(), 2, 1, 2) +
            le32(0b11111) +
            blockhashBytes +
            byteArrayOf(2, 5) +
            payer.bytes + key(2).bytes + key(3).bytes + key(6).bytes + key(7).bytes +
            le64(0x0102030405060708L) + le32(0xFFFF_FFFFL) + le32(65_536) + le32(65_536) +
            // instruction headers: program, account count, u16 data length
            byteArrayOf(3, 1, 3, 0) + byteArrayOf(4, 2, 0x2C, 0x01) +
            // payloads: indexes then data
            byteArrayOf(1) + byteArrayOf(9, 8, 7) +
            byteArrayOf(2, 1) + ByteArray(300) { 1 }
        assertArrayEquals(expected, message.serialize())

        val back = Message.deserialize(message.serialize())
        assertEquals(config, back.config)
        assertEquals(message.accountKeys, back.accountKeys)
        assertEquals(message.header, back.header)
        assertEquals(2, back.instructions.size)
        assertArrayEquals(second.data, back.instructions[1].data)
        assertArrayEquals(message.serialize(), back.serialize())
    }

    @Test
    fun `a V1 transaction round trips through serialize and deserialize with several slots`() {
        val payer = key(1)
        val other = key(2)
        val ix = Instruction(key(6), listOf(AccountMeta(other, true, true)), byteArrayOf(1))
        val message = Message.compile(payer, listOf(ix), blockhash, TxFormat.V1, TxConfig(computeUnitLimit = 6_000L))
        val sigA = ByteArray(64) { 0x11 }
        val sigB = ByteArray(64) { 0x22 }
        val tx = Transaction.unsigned(message).withSignature(other, sigB).withSignature(payer, sigA)
        val bytes = tx.serialize()
        assertArrayEquals(message.serialize() + sigA + sigB, bytes)
        val back = Transaction.deserialize(bytes)
        assertEquals(TxFormat.V1, back.message.format)
        assertArrayEquals(sigA, back.signatures[0])
        assertArrayEquals(sigB, back.signatures[1])
        assertEquals(tx.signature, back.signature)
        assertArrayEquals(bytes, back.serialize())
        // A V1 envelope with a byte after the signatures is not one.
        try {
            Transaction.deserialize(bytes + byteArrayOf(0))
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `legacy bytes are unchanged by the V1 code and the two formats do not mix`() {
        val payer = key(1)
        val ix = Instruction(key(6), listOf(AccountMeta(key(2), false, true)), byteArrayOf(5, 6))
        val legacy = Message.compile(payer, listOf(ix), blockhash)
        assertEquals(TxFormat.Legacy, legacy.format)
        assertEquals(TxConfig.NONE, legacy.config)
        val v1 = Message.compile(payer, listOf(ix), blockhash, TxFormat.V1)
        // Same table, same header; only the bytes differ, and the first byte says which.
        assertEquals(legacy.accountKeys, v1.accountKeys)
        assertEquals(legacy.header, v1.header)
        assertEquals(1, legacy.serialize()[0].toInt())
        assertEquals(0x81, v1.serialize()[0].toInt() and 0xFF)
        // The legacy layout is what it always was: header, compact count, keys, hash, compact ix list.
        val expected = byteArrayOf(1, 0, 1) + byteArrayOf(3) + payer.bytes + key(2).bytes + key(6).bytes +
            blockhashBytes + byteArrayOf(1) + byteArrayOf(2) + byteArrayOf(1, 1) + byteArrayOf(2, 5, 6)
        assertArrayEquals(expected, legacy.serialize())
        assertEquals(TxFormat.Legacy, Transaction.deserialize(Transaction.unsigned(legacy).serialize()).message.format)
        // A legacy message may not carry a config.
        try {
            Message.compile(payer, listOf(ix), blockhash, TxFormat.Legacy, TxConfig(computeUnitLimit = 1L))
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `v0 is still refused, as a message and as a transaction`() {
        val message = Message.compile(key(1), emptyList(), blockhash, TxFormat.V1)
        val bytes = message.serialize()
        for (candidate in listOf(
            byteArrayOf(0x80.toByte()) + bytes.copyOfRange(1, bytes.size),
            byteArrayOf(0x8F.toByte()) + bytes.copyOfRange(1, bytes.size),
        )) {
            try {
                Message.deserialize(candidate)
                fail("expected a throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("versioned messages are not supported", e.message)
            }
            try {
                Transaction.deserialize(candidate)
                fail("expected a throw")
            } catch (e: IllegalArgumentException) {
                assertEquals("versioned messages are not supported", e.message)
            }
        }
    }

    @Test
    fun `the config mask refuses unknown bits and a lone priority-fee bit, and checks its ranges`() {
        val message = Message.compile(key(1), emptyList(), blockhash, TxFormat.V1, TxConfig(computeUnitLimit = 1L))
        val bytes = message.serialize()
        fun withMask(mask: Long): ByteArray = bytes.copyOf().also { le32(mask).copyInto(it, 4) }
        // Bit 5 is nobody's; bit 0 alone is half a fee.
        for (mask in listOf(0b100100L, 0x80000000L, 0b101L, 0b110L)) {
            try {
                Message.deserialize(withMask(mask))
                fail("mask $mask should be refused")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(TxConfig(computeUnitLimit = 1L), Message.deserialize(withMask(0b100L)).config)

        assertEquals(0L, TxConfig.NONE.mask)
        assertEquals(0, TxConfig.NONE.size)
        assertEquals(0b11L, TxConfig(priorityFeeLamports = 1L).mask)
        assertEquals(8, TxConfig(priorityFeeLamports = 1L).size)
        assertEquals(0b1000L, TxConfig(loadedAccountsDataSize = 1L).mask)
        assertEquals(0b10000L, TxConfig(heapSize = 32L * 1024L).mask)
        TxConfig(heapSize = 256L * 1024L)
        for (bad in listOf(
            { TxConfig(computeUnitLimit = 0x1_0000_0000L) },
            { TxConfig(computeUnitLimit = -1L) },
            { TxConfig(loadedAccountsDataSize = 0x1_0000_0000L) },
            { TxConfig(priorityFeeLamports = -1L) },
            { TxConfig(heapSize = 32L * 1024L - 1024L) },
            { TxConfig(heapSize = 256L * 1024L + 1024L) },
            { TxConfig(heapSize = 32L * 1024L + 1L) },
        )) {
            try {
                bad()
                fail("expected a throw")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `V1 limits are enforced where legacy's are not`() {
        val payer = key(1)
        fun ixWithKeys(count: Int) = Instruction(key(200), List(count) { AccountMeta(key(10 + it), false, false) }, byteArrayOf())
        // 64 addresses: payer + 62 accounts + the program is the most; one more is refused.
        Message.compile(payer, listOf(ixWithKeys(62)), blockhash, TxFormat.V1)
        Message.compile(payer, listOf(ixWithKeys(63)), blockhash)
        try {
            Message.compile(payer, listOf(ixWithKeys(63)), blockhash, TxFormat.V1)
            fail("65 addresses")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // 64 instructions.
        val tiny = Instruction(key(6), emptyList(), byteArrayOf())
        Message.compile(payer, List(64) { tiny }, blockhash, TxFormat.V1)
        try {
            Message.compile(payer, List(65) { tiny }, blockhash, TxFormat.V1)
            fail("65 instructions")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // 12 signatures: payer plus eleven.
        fun signers(count: Int) = Instruction(key(6), List(count) { AccountMeta(key(10 + it), true, false) }, byteArrayOf())
        assertEquals(12, Message.compile(payer, listOf(signers(11)), blockhash, TxFormat.V1).signerCount)
        try {
            Message.compile(payer, listOf(signers(12)), blockhash, TxFormat.V1)
            fail("13 signatures")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // 65,535 data bytes per instruction; legacy had the same limit through compact-u16.
        Message.compile(payer, listOf(Instruction(key(6), emptyList(), ByteArray(65_535))), blockhash, TxFormat.V1)
        try {
            Message.compile(payer, listOf(Instruction(key(6), emptyList(), ByteArray(65_536))), blockhash, TxFormat.V1)
            fail("65536 data bytes")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // A program at slot 0 — the fee payer — is refused by the runtime's validate, and here.
        try {
            Message.compile(payer, listOf(Instruction(payer, emptyList(), byteArrayOf())), blockhash, TxFormat.V1)
            fail("program is the fee payer")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        // The ceilings the packer sizes against.
        assertEquals(1232, TxFormat.Legacy.maxTransactionSize)
        assertEquals(4096, TxFormat.V1.maxTransactionSize)
    }
}
