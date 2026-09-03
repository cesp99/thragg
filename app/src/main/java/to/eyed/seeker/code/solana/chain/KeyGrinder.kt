package to.eyed.seeker.code.solana.chain

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.math.FieldElement
import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Finds Ed25519 keypairs whose public key the devnet proof-of-work faucet
 * pays for — Base58 starting with `AAA` — a few hundred thousand candidates
 * a second per core, on the phone.
 *
 * The obvious way is to draw a seed, derive its key and look, and the eddsa
 * library derives a key with a full scalar multiplication: a few hundred
 * microseconds, so a few thousand tries a second per core, and a hit is one
 * try in about 57,000 (a 32-byte value Base58-encodes with `AAA` when it
 * falls in one window of width 58^41 out of 2^256). That is a claim every
 * fifteen seconds per core, which is slower than the faucet it replaces.
 *
 * The way used here is devnet-larper's (github.com/cesp99/devnet-larper): a
 * public key is `a·B` for the secret scalar `a`, so once one key is known
 * every next key is one point addition away, `(a+8)·B = a·B + 8B`. Eight
 * rather than one because RFC 8032 clamps the scalar — clears its low three
 * bits, pins bit 254 — and a walk in steps of eight keeps every scalar it
 * visits in that clamped form, so the same bytes the walk counted are what
 * the signer clamps to, and the key it derives is the point the walk found.
 * A point addition is a dozen field multiplications; the one expensive step
 * left, dividing out the projective `Z` to read the `y` coordinate that
 * Base58 sees, is done for [BATCH] points with a single inversion plus three
 * multiplications each (Montgomery's trick). The field and point arithmetic
 * is the library's own, so nothing about the curve is re-derived here, and
 * every hit is cross-checked by deriving the key again the slow way before
 * it is handed out — a walk that drifted would produce keys whose signatures
 * do not verify, and the cross-check is what turns that into a dropped
 * candidate rather than a refused transaction.
 *
 * The keys have no seed. [GroundKey] carries the scalar and a nonce prefix
 * directly, the form the library's `EdDSAPrivateKeySpec(spec, h)` takes, and
 * signs with the same engine [Keypair] uses. Keys.kt and this file are the
 * two places the eddsa library is imported.
 *
 * Threads are plain daemon threads at the lowest priority: the grinder runs
 * while a Settings sheet or a deploy waits on it, and the screen should
 * still scroll. They block when [capacity] keys are waiting, so a miner
 * that has stopped sending stops the CPU too. [stop] interrupts them.
 */
class GroundKey internal constructor(
    val publicKey: Pubkey,
    private val privateKey: EdDSAPrivateKey,
) {
    /** How many A's the Base58 spelling starts with: three for most hits, four for a lucky one. */
    val leadingAs: Int = publicKey.base58.takeWhile { it == 'A' }.length

    /** A 64-byte detached signature over [message], the same engine and digest as [Keypair.sign]. */
    fun sign(message: ByteArray): ByteArray {
        val engine = Ed25519.engine()
        engine.initSign(privateKey)
        engine.update(message)
        return engine.sign()
    }

    override fun toString(): String = "GroundKey(${publicKey.base58})"
}

class KeyGrinder(
    private val threads: Int = defaultThreads(),
    capacity: Int = DEFAULT_CAPACITY,
    /** The test on the 32-byte encoded key; the faucet's window by default, anything a test likes otherwise. */
    private val accept: (ByteArray) -> Boolean = PowFaucet::hasAaaPrefix,
) {
    private val queue = ArrayBlockingQueue<GroundKey>(capacity)
    private val workers = ArrayList<Thread>(threads)
    private val tested = AtomicLong()
    private val found = AtomicLong()

    @Volatile
    private var running = false

    /** Candidates examined so far, across threads. */
    val candidatesTested: Long get() = tested.get()

    /** Keys that passed [accept] and the cross-check. */
    val keysFound: Long get() = found.get()

    fun start() {
        check(workers.isEmpty()) { "the grinder is already running" }
        running = true
        repeat(threads) { index ->
            val thread = Thread({ grind() }, "key-grinder-$index").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
            workers += thread
            thread.start()
        }
    }

    /** The next key, or null when none arrived within [timeoutMs]. */
    fun take(timeoutMs: Long): GroundKey? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun stop() {
        running = false
        workers.forEach { it.interrupt() }
        workers.clear()
        queue.clear()
    }

    private fun grind() {
        val base = Ed25519.spec.b
        val step = eightTimes(base).toCached()
        val random = SecureRandom()
        val xs = arrayOfNulls<FieldElement>(BATCH)
        val ys = arrayOfNulls<FieldElement>(BATCH)
        val zs = arrayOfNulls<FieldElement>(BATCH)
        val prefix = arrayOfNulls<FieldElement>(BATCH)
        while (running) {
            // A random clamped scalar to start from, and its point the slow way.
            val start = ByteArray(Pubkey.SIZE).also(random::nextBytes)
            start[0] = (start[0].toInt() and 248).toByte()
            start[31] = ((start[31].toInt() and 63) or 64).toByte()
            var scalar = BigInteger(1, start.reversedArray())
            var point = base.scalarMultiply(start).toP3()
            while (running && scalar < WALK_LIMIT) {
                // BATCH consecutive points, kept projective.
                var next = point
                for (i in 0 until BATCH) {
                    xs[i] = next.x
                    ys[i] = next.y
                    zs[i] = next.z
                    next = next.add(step).toP3()
                }
                tested.addAndGet(BATCH.toLong())
                // Montgomery's trick: prefix products, one inversion, then
                // peel each Z⁻¹ off walking backwards.
                var acc = zs[0]!!
                prefix[0] = acc
                for (i in 1 until BATCH) {
                    acc = acc.multiply(zs[i])
                    prefix[i] = acc
                }
                var inverse = acc.invert()
                for (i in BATCH - 1 downTo 0) {
                    val zInverse = if (i == 0) inverse else inverse.multiply(prefix[i - 1])
                    if (i > 0) inverse = inverse.multiply(zs[i])
                    val encoded = ys[i]!!.multiply(zInverse).toByteArray()
                    // The sign of x lives in the top bit of the last byte, which
                    // is the least significant place for Base58; the window is
                    // decided by the first bytes, so x is only computed for the
                    // one candidate in tens of thousands that gets this far.
                    if (accept(encoded)) {
                        if (xs[i]!!.multiply(zInverse).isNegative) {
                            encoded[31] = (encoded[31].toInt() or 0x80).toByte()
                        }
                        if (accept(encoded)) hit(scalar + BigInteger.valueOf(8L * i), encoded)
                    }
                }
                point = next
                scalar += STRIDE
            }
        }
    }

    /**
     * Turn a scalar the walk found into a signer, the slow way, and keep it
     * only if the slow way agrees with the walk.
     */
    private fun hit(scalar: BigInteger, encoded: ByteArray) {
        val littleEndian = scalarBytes(scalar) ?: return
        // The second half of an expanded key is the nonce prefix RFC 8032
        // derives from the seed; there is no seed, so it is a hash of the
        // scalar, which is as secret as the scalar and as deterministic.
        val h = ByteArray(2 * Pubkey.SIZE)
        littleEndian.copyInto(h, 0)
        MessageDigest.getInstance("SHA-512").digest(littleEndian).copyInto(h, Pubkey.SIZE, Pubkey.SIZE, 2 * Pubkey.SIZE)
        // The spec clamps h in place (a no-op for a walked scalar) and derives A.
        val spec = EdDSAPrivateKeySpec(Ed25519.spec, h)
        if (!spec.getA().toByteArray().contentEquals(encoded)) return
        val key = GroundKey(Pubkey(encoded), EdDSAPrivateKey(spec))
        found.incrementAndGet()
        try {
            queue.put(key)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** Points per inversion. Larger is not faster past a few hundred; the arrays are the cost. */
        private const val BATCH = 256
        private const val DEFAULT_CAPACITY = 64
        private val STRIDE: BigInteger = BigInteger.valueOf(8L * BATCH)

        /** Below 2^255 by one batch: the last scalar of a batch still has bit 255 clear. */
        private val WALK_LIMIT: BigInteger = BigInteger.ONE.shiftLeft(255) - STRIDE

        /** All but one core, and never none; the one left over keeps the UI thread on its feet. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1)

        /** `8·B` from three doublings, in P3. */
        private fun eightTimes(point: GroupElement): GroupElement =
            point.dbl().toP3().dbl().toP3().dbl().toP3()

        /**
         * The scalar as 32 little-endian bytes in clamped form, or null when
         * the walk carried it past 2^255 — bit 255 set, or bit 254 clear —
         * which the signer's clamp would silently rewrite into another key.
         */
        internal fun scalarBytes(scalar: BigInteger): ByteArray? {
            val bigEndian = scalar.toByteArray()
            val significant = if (bigEndian.size > Pubkey.SIZE && bigEndian[0] == 0.toByte()) {
                bigEndian.copyOfRange(1, bigEndian.size)
            } else {
                bigEndian
            }
            if (significant.size > Pubkey.SIZE) return null
            val out = ByteArray(Pubkey.SIZE)
            for (i in significant.indices) out[i] = significant[significant.size - 1 - i]
            val top = out[31].toInt() and 0xC0
            if (top != 0x40 || (out[0].toInt() and 7) != 0) return null
            return out
        }
    }
}
