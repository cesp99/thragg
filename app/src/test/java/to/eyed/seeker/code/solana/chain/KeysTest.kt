package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KeysTest {

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    // RFC 8032 section 7.1, test 1.
    private val rfcSeed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hex()
    private val rfcPub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private val rfcSig = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

    @Test
    fun `the all-zero seed derives the well-known public key`() {
        val keypair = Keypair.fromSeed(ByteArray(32))
        assertEquals("3b6a27bcceb6a42d62a3a8d02a6f0d73653215771de243a63ac048a18b59da29", keypair.publicKey.bytes.hex())
        assertEquals("4zvwRjXUKGfvwnParsHAS3HuSVzV5cA4McphgmoCtajS", keypair.publicKey.base58)
    }

    @Test
    fun `matches the RFC 8032 test vector`() {
        val keypair = Keypair.fromSeed(rfcSeed)
        assertEquals(rfcPub, keypair.publicKey.bytes.hex())
        val signature = keypair.sign(ByteArray(0))
        assertEquals(64, signature.size)
        assertEquals(rfcSig, signature.hex())
        assertTrue(Ed25519.verify(keypair.publicKey, ByteArray(0), signature))
    }

    @Test
    fun `sign and verify over a real message`() {
        val keypair = Keypair.generate()
        val message = "hello, seeker".toByteArray()
        val signature = keypair.sign(message)
        assertTrue(Ed25519.verify(keypair.publicKey, message, signature))
        assertFalse(Ed25519.verify(keypair.publicKey, message + byteArrayOf(0), signature))
        assertFalse(Ed25519.verify(Keypair.generate().publicKey, message, signature))
        val tampered = signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(Ed25519.verify(keypair.publicKey, message, tampered))
        assertFalse(Ed25519.verify(keypair.publicKey, message, ByteArray(63)))
        assertFalse(Ed25519.verify(keypair.publicKey, message, ByteArray(64)))
    }

    @Test
    fun `verify is false for an off-curve public key rather than a throw`() {
        val pda = Pda.programDataAddress(Pubkey.of("metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"))
        assertFalse(Ed25519.verify(pda, ByteArray(0), ByteArray(64) { 1 }))
    }

    @Test
    fun `signing a transaction fills the right slot and verifies over the message bytes`() {
        val payer = Keypair.generate()
        val blockhash = Base58.encode(ByteArray(32) { 3 })
        val ix = Instruction(Pubkey(ByteArray(32)), listOf(AccountMeta(payer.publicKey, true, true)), byteArrayOf(2))
        val message = Message.compile(payer.publicKey, listOf(ix), blockhash)
        val tx = Transaction.unsigned(message).withSignature(payer.publicKey, payer.sign(message.serialize()))
        assertTrue(tx.isFullySigned)
        assertTrue(Ed25519.verify(payer.publicKey, tx.message.serialize(), tx.signatures[0]))
        assertEquals(Base58.encode(tx.signatures[0]), tx.signature)
    }

    @Test
    fun `generate makes distinct keys`() {
        assertNotEquals(Keypair.generate().publicKey, Keypair.generate().publicKey)
    }

    @Test
    fun `secret bytes are seed then public key`() {
        val keypair = Keypair.fromSeed(rfcSeed)
        val secret = keypair.secretBytes()
        assertEquals(64, secret.size)
        assertArrayEquals(rfcSeed, secret.copyOfRange(0, 32))
        assertArrayEquals(rfcPub.hex(), secret.copyOfRange(32, 64))
        assertEquals(keypair.publicKey, Keypair.fromSecretBytes(secret).publicKey)
    }

    @Test
    fun `fromSecretBytes refuses a public half that does not derive from the seed`() {
        val secret = Keypair.fromSeed(rfcSeed).secretBytes()
        secret[40] = (secret[40].toInt() xor 1).toByte()
        try {
            Keypair.fromSecretBytes(secret)
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            Keypair.fromSecretBytes(ByteArray(32))
            fail("expected a throw")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `json round trips in solana's format`() {
        val keypair = Keypair.fromSeed(rfcSeed)
        val json = keypair.toJson()
        assertTrue(json.startsWith("[") && json.endsWith("]"))
        assertEquals(64, json.removeSurrounding("[", "]").split(",").size)
        assertFalse(json.contains(" "))
        assertTrue(json.startsWith("[157,97,177,157,239,253,90,96,"))

        val back = Keypair.fromJson(json)!!
        assertEquals(keypair.publicKey, back.publicKey)
        assertArrayEquals(keypair.secretBytes(), back.secretBytes())

        // Whitespace and newlines, as an editor might leave them.
        val spaced = json.replace(",", ", ").replace("[", "[\n  ").replace("]", "\n]\n")
        assertEquals(keypair.publicKey, Keypair.fromJson(spaced)!!.publicKey)
    }

    @Test
    fun `malformed json reads as null`() {
        assertNull(Keypair.fromJson(""))
        assertNull(Keypair.fromJson("not json"))
        assertNull(Keypair.fromJson("[]"))
        assertNull(Keypair.fromJson("{\"a\":1}"))
        assertNull(Keypair.fromJson(List(63) { 1 }.joinToString(",", "[", "]")))
        assertNull(Keypair.fromJson(List(64) { 256 }.joinToString(",", "[", "]")))
        assertNull(Keypair.fromJson(List(64) { -1 }.joinToString(",", "[", "]")))
        assertNull(Keypair.fromJson(List(64) { "x" }.joinToString(",", "[", "]")))
        // 64 well-formed ints whose public half is wrong.
        assertNull(Keypair.fromJson(List(64) { 0 }.joinToString(",", "[", "]")))
    }

    @Test
    fun `read and write a keypair file`() {
        val dir = Files.createTempDirectory("keys").toFile()
        try {
            val file = File(dir, "target/deploy/my_program-keypair.json")
            assertNull(Keypair.read(file))
            val keypair = Keypair.generate()
            Keypair.write(file, keypair)
            assertTrue(file.isFile)
            assertEquals(keypair.toJson(), file.readText())
            assertEquals(keypair.publicKey, Keypair.read(file)!!.publicKey)
            assertTrue(file.canRead())
            assertTrue(file.canWrite())

            file.writeText("garbage")
            assertNull(Keypair.read(file))
            assertNull(Keypair.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
