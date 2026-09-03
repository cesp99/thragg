package to.eyed.seeker.code.solana.chain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Base58Test {

    @Test
    fun `the system program is thirty-two zero bytes`() {
        val id = "11111111111111111111111111111111"
        assertArrayEquals(ByteArray(32), Base58.decode(id))
        assertEquals(id, Base58.encode(ByteArray(32)))
    }

    @Test
    fun `round trips a real program id`() {
        val id = "BPFLoaderUpgradeab1e11111111111111111111111"
        val bytes = Base58.decode(id)
        assertEquals(32, bytes.size)
        assertEquals(id, Base58.encode(bytes))
        assertTrue(Base58.isPubkey(id))
    }

    @Test
    fun `refuses the characters the alphabet leaves out`() {
        assertNull(Base58.decodeOrNull("0OIl"))
        assertNull(Base58.decodeOrNull("abcé"))
    }

    @Test
    fun `known vector`() {
        // "hello" in Base58 per the Bitcoin alphabet.
        assertEquals("Cn8eVZg", Base58.encode("hello".toByteArray()))
        assertArrayEquals("hello".toByteArray(), Base58.decode("Cn8eVZg"))
    }

    @Test
    fun `shortens with an ellipsis`() {
        assertEquals("7NJd…4kQz", Base58.short("7NJdQ2xkW8kM4kQz"))
        assertEquals("short", Base58.short("short"))
    }

    @Test
    fun `compact u16 matches the wire examples`() {
        assertArrayEquals(byteArrayOf(0), CompactU16.encode(0))
        assertArrayEquals(byteArrayOf(0x7F), CompactU16.encode(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), CompactU16.encode(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), CompactU16.encode(16383))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x01), CompactU16.encode(16384))
        for (v in listOf(0, 1, 127, 128, 300, 16383, 16384, 65535)) {
            val enc = CompactU16.encode(v)
            assertEquals(v to enc.size, CompactU16.decode(enc))
        }
    }

    @Test
    fun `compact u16 refuses a truncated or oversized prefix`() {
        assertThrows(IllegalArgumentException::class.java) { CompactU16.decode(byteArrayOf(0x80.toByte())) }
        assertThrows(IllegalArgumentException::class.java) { CompactU16.decode(byteArrayOf()) }
        // Three bytes can spell up to 2^21; anything past 0xFFFF is not a u16.
        assertThrows(IllegalArgumentException::class.java) {
            CompactU16.decode(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F))
        }
    }
}
