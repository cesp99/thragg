package to.eyed.seeker.code.solana.chain

import java.math.BigInteger

/**
 * Base58 with the Bitcoin alphabet, which is what every Solana address,
 * signature and blockhash is spelled in.
 *
 * Hand-written rather than pulled from a library for one reason: this is the
 * one encoding the whole chain layer rests on — a program id read out of a
 * keypair file, a `declare_id!` compared against it, an address printed in a
 * row — and it has to be checkable on the host JVM with no Android and no
 * transitive Kotlin-multiplatform jar in the way. Forty lines, `BigInteger`
 * for the arithmetic, and leading zero bytes carried as leading `1`s exactly
 * as the reference encoder does.
 */
object Base58 {

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val INDEXES = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }
    private val BASE = BigInteger.valueOf(58)

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros] == 0.toByte()) zeros++
        val out = StringBuilder()
        var n = BigInteger(1, bytes)
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(BASE)
            out.append(ALPHABET[r.toInt()])
            n = q
        }
        repeat(zeros) { out.append(ALPHABET[0]) }
        return out.reverse().toString()
    }

    /** Null for a string that is not Base58, rather than a throw a row has to catch. */
    fun decodeOrNull(text: String): ByteArray? {
        if (text.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < text.length && text[zeros] == ALPHABET[0]) zeros++
        var n = BigInteger.ZERO
        for (c in text) {
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            if (digit < 0) return null
            n = n.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val magnitude = n.toByteArray().let { raw ->
            // BigInteger adds a sign byte when the top bit is set; drop it.
            if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        }
        val body = if (n.signum() == 0) ByteArray(0) else magnitude
        return ByteArray(zeros) + body
    }

    fun decode(text: String): ByteArray =
        decodeOrNull(text) ?: throw IllegalArgumentException("not base58: $text")

    /** Whether [text] decodes to exactly 32 bytes — the shape of a public key. */
    fun isPubkey(text: String): Boolean = decodeOrNull(text)?.size == 32

    /** `7NJd…4kQz` — the four-and-four ellipsis every row in the app prints. */
    fun short(address: String, keep: Int = 4): String =
        if (address.length <= keep * 2 + 1) address else address.take(keep) + "…" + address.takeLast(keep)
}

/**
 * Solana's compact-u16: seven bits per byte, low bits first, `0x80` as the
 * continuation flag, three bytes at most. Used for every length prefix in a
 * serialized message.
 */
object CompactU16 {
    fun encode(value: Int): ByteArray {
        require(value in 0..0xFFFF) { "compact-u16 out of range: $value" }
        val out = ArrayList<Byte>(3)
        var rem = value
        while (true) {
            val byte = rem and 0x7F
            rem = rem ushr 7
            if (rem == 0) {
                out.add(byte.toByte())
                break
            }
            out.add((byte or 0x80).toByte())
        }
        return out.toByteArray()
    }

    /** The value and the number of bytes it took, read at [offset]. */
    fun decode(bytes: ByteArray, offset: Int = 0): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var i = offset
        while (true) {
            require(i < bytes.size) { "compact-u16 ended early" }
            val b = bytes[i].toInt() and 0xFF
            value = value or ((b and 0x7F) shl shift)
            i++
            if (b and 0x80 == 0) break
            shift += 7
            require(shift <= 14) { "compact-u16 too long" }
        }
        require(value <= 0xFFFF) { "compact-u16 out of range: $value" }
        return value to (i - offset)
    }
}
