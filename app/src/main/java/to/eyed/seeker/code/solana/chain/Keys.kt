package to.eyed.seeker.code.solana.chain

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.math.GroupElement
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ed25519 for the chain layer: keypairs in Solana's file format, signing,
 * verifying, and program-derived addresses.
 *
 * There is no `solana-keygen` on the phone, so the program keypair that
 * `target/deploy/<name>-keypair.json` holds, the deploy key that signs a few
 * hundred buffer writes, and the throwaway buffer account are all made and
 * read here. The curve arithmetic is `net.i2p.crypto:eddsa` — pure Java,
 * CC0, and small enough to audit — and this file is the only one that
 * imports it, so the rest of the chain package sees [Keypair], [Pubkey] and
 * two objects, never a `GroupElement`.
 *
 * Solana's keypair file is a JSON array of 64 integers: the 32-byte seed
 * followed by the 32-byte public key. The public key is redundant and
 * [Keypair.fromSecretBytes] treats it as a checksum — a file whose second
 * half does not derive from its first is refused rather than trusted.
 *
 * [Ed25519.isOnCurve] exists for one caller, [Pda.findProgramAddress]: a
 * program-derived address is by definition a 32-byte string that is *not* a
 * valid curve point, and the runtime finds it by hashing seeds with a bump
 * byte counted down from 255 until decoding fails. The eddsa decoder does
 * exactly the check the runtime does — recover x from y and reject when
 * neither root works — and signals it with an `IllegalArgumentException`
 * from the `GroupElement(curve, bytes)` constructor; that exception is the
 * whole answer.
 */
class Keypair private constructor(val publicKey: Pubkey, private val seed: ByteArray) {

    /** Built once: the spec does a scalar multiply, and a deploy signs hundreds of times. */
    private val privateKey: EdDSAPrivateKey by lazy(LazyThreadSafetyMode.NONE) {
        EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, Ed25519.spec))
    }

    /** A 64-byte detached signature over [message], deterministic per RFC 8032. */
    fun sign(message: ByteArray): ByteArray {
        val engine = Ed25519.engine()
        engine.initSign(privateKey)
        engine.update(message)
        return engine.sign()
    }

    /** The 64 bytes Solana's file holds: seed then public key. A copy; callers may zero it. */
    fun secretBytes(): ByteArray = seed + publicKey.bytes

    /** `[12,34,...]` — 64 integers, no whitespace, the way `solana-keygen` writes it. */
    fun toJson(): String = secretBytes().joinToString(",", prefix = "[", postfix = "]") { (it.toInt() and 0xFF).toString() }

    override fun toString(): String = "Keypair(${publicKey.base58})"

    companion object {
        const val SEED_SIZE = 32
        const val SECRET_SIZE = 64

        /** A fresh keypair from the platform's [SecureRandom]. */
        fun generate(): Keypair = fromSeed(ByteArray(SEED_SIZE).also { SecureRandom().nextBytes(it) })

        /** Derive the public key from a 32-byte seed. */
        fun fromSeed(seed: ByteArray): Keypair {
            require(seed.size == SEED_SIZE) { "a seed is $SEED_SIZE bytes, not ${seed.size}" }
            val spec = EdDSAPrivateKeySpec(seed, Ed25519.spec)
            // getA() is the public point; geta() next to it is the clamped scalar. Spell it out.
            return Keypair(Pubkey(spec.getA().toByteArray()), seed.copyOf())
        }

        /** The 64-byte form; throws when the public half does not derive from the seed. */
        fun fromSecretBytes(bytes: ByteArray): Keypair {
            require(bytes.size == SECRET_SIZE) { "a secret key is $SECRET_SIZE bytes, not ${bytes.size}" }
            val keypair = fromSeed(bytes.copyOfRange(0, SEED_SIZE))
            val claimed = bytes.copyOfRange(SEED_SIZE, SECRET_SIZE)
            require(claimed.contentEquals(keypair.publicKey.bytes)) { "public key does not match the seed" }
            return keypair
        }

        /**
         * Parse Solana's JSON array. Null for anything that is not exactly 64
         * integers in 0..255 whose public half matches the seed — a keypair
         * file is a thing the user can edit, and a bad one should read as
         * "missing", not crash the Build tab.
         */
        fun fromJson(text: String): Keypair? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
            val body = trimmed.substring(1, trimmed.length - 1).trim()
            if (body.isEmpty()) return null
            val values = body.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (values.size != SECRET_SIZE || values.any { it !in 0..255 }) return null
            val bytes = ByteArray(SECRET_SIZE) { values[it].toByte() }
            return try {
                fromSecretBytes(bytes)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /** Null when the file is missing, unreadable or malformed. */
        fun read(file: File): Keypair? {
            if (!file.isFile) return null
            val text = try {
                file.readText()
            } catch (e: IOException) {
                return null
            }
            return fromJson(text)
        }

        /** Write in Solana's format, creating parents, readable and writable by the owner only. */
        fun write(file: File, keypair: Keypair) {
            file.parentFile?.mkdirs()
            file.writeText(keypair.toJson())
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }
}

/** Verification and the on-curve test, over the same eddsa curve spec [Keypair] signs with. */
object Ed25519 {

    internal val spec: EdDSANamedCurveSpec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)

    /** A fresh engine per call: `java.security.Signature` instances are not thread-safe. */
    internal fun engine(): EdDSAEngine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))

    /** False for a wrong-length signature, an off-curve key, or a signature that does not verify. */
    fun verify(publicKey: Pubkey, message: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != Transaction.SIGNATURE_SIZE) return false
        return try {
            val engine = engine()
            engine.initVerify(EdDSAPublicKey(EdDSAPublicKeySpec(publicKey.bytes, spec)))
            engine.update(message)
            engine.verify(signature)
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
    }

    /**
     * Whether [bytes] decodes as a point on the curve — true for every real
     * public key, false for a program-derived address. Anything but 32 bytes
     * is not on the curve either.
     */
    fun isOnCurve(bytes: ByteArray): Boolean {
        if (bytes.size != Pubkey.SIZE) return false
        return try {
            GroupElement(spec.curve, bytes)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}

/** Program-derived addresses, the runtime's way. */
object Pda {

    private const val MAX_SEEDS = 16
    private const val MAX_SEED_LENGTH = 32
    private val MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)

    /** The upgradeable BPF loader, spelled here so Keys.kt stands on its own. */
    private val UPGRADEABLE_LOADER: Pubkey by lazy { Pubkey.of("BPFLoaderUpgradeab1e11111111111111111111111") }

    /**
     * The address and the bump that produced it. Counts the bump down from
     * 255 and hashes `seeds + bump + programId + "ProgramDerivedAddress"`
     * until the digest is off the curve, exactly as `find_program_address`
     * does, so the result matches what the program computes on chain.
     */
    fun findProgramAddress(seeds: List<ByteArray>, programId: Pubkey): Pair<Pubkey, Int> {
        require(seeds.size <= MAX_SEEDS) { "at most $MAX_SEEDS seeds, not ${seeds.size}" }
        require(seeds.all { it.size <= MAX_SEED_LENGTH }) { "a seed is at most $MAX_SEED_LENGTH bytes" }
        for (bump in 255 downTo 0) {
            val digest = MessageDigest.getInstance("SHA-256")
            for (seed in seeds) digest.update(seed)
            digest.update(bump.toByte())
            digest.update(programId.bytes)
            digest.update(MARKER)
            val candidate = digest.digest()
            if (!Ed25519.isOnCurve(candidate)) return Pubkey(candidate) to bump
        }
        throw IllegalStateException("no viable bump for $programId")
    }

    /** Where the upgradeable loader keeps a program's ELF and upgrade authority. */
    fun programDataAddress(programId: Pubkey): Pubkey =
        findProgramAddress(listOf(programId.bytes), UPGRADEABLE_LOADER).first
}
