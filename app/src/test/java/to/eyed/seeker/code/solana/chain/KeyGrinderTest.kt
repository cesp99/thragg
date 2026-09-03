package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The walk is right when the slow derivation agrees with it and the
 * signatures verify; the grinder cross-checks the first itself, so a test
 * that gets keys out of it at all has proved the walk, and verifying what
 * they sign proves the expanded-key form the signer takes.
 */
class KeyGrinderTest {

    @Test
    fun `keys off the walk sign verifiably, whatever the window`() {
        // Accept everything: the first few points of the walk, in under a second.
        val grinder = KeyGrinder(threads = 1, capacity = 8, accept = { true })
        grinder.start()
        try {
            val keys = List(3) { requireNotNull(grinder.take(10_000L)) { "no key within 10 s" } }
            val message = "hello, faucet".toByteArray()
            for (key in keys) {
                val signature = key.sign(message)
                assertTrue(Ed25519.verify(key.publicKey, message, signature))
                // Not a doctored message, not another key's signature.
                assertTrue(!Ed25519.verify(key.publicKey, "hello, faucet!".toByteArray(), signature))
            }
            assertEquals(3, keys.map { it.publicKey }.distinct().size)
            assertTrue(grinder.keysFound >= 3)
            assertTrue(grinder.candidatesTested >= 256)
        } finally {
            grinder.stop()
        }
    }

    @Test
    fun `finds a key the faucet would pay for`() {
        val grinder = KeyGrinder(threads = 2)
        grinder.start()
        try {
            val key = requireNotNull(grinder.take(90_000L)) { "no AAA key within 90 s" }
            assertTrue(key.publicKey.base58, key.publicKey.base58.startsWith("AAA"))
            assertTrue(key.leadingAs >= 3)
            assertTrue(PowFaucet.hasAaaPrefix(key.publicKey.bytes))
            val message = ByteArray(200) { it.toByte() }
            assertTrue(Ed25519.verify(key.publicKey, message, key.sign(message)))
        } finally {
            grinder.stop()
        }
    }

    @Test
    fun `scalar bytes are little-endian and refuse anything the clamp would rewrite`() {
        val clamped = BigInteger.ONE.shiftLeft(254).add(BigInteger.valueOf(8L * 1234))
        val bytes = requireNotNull(KeyGrinder.scalarBytes(clamped))
        assertEquals(32, bytes.size)
        assertEquals(0x40, bytes[31].toInt() and 0xFF)
        assertEquals((8 * 1234) and 0xFF, bytes[0].toInt() and 0xFF)
        // Bit 254 clear, bit 255 set, low bits set: three scalars a clamp would change.
        assertNull(KeyGrinder.scalarBytes(BigInteger.valueOf(8)))
        assertNull(KeyGrinder.scalarBytes(BigInteger.ONE.shiftLeft(255)))
        assertNull(KeyGrinder.scalarBytes(clamped.add(BigInteger.ONE)))
    }
}
