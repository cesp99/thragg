package to.eyed.seeker.code.solana.chain

import java.util.Base64

/**
 * The legacy Solana transaction on the wire: public keys, account metas,
 * instructions, the compiled message, and the signed envelope around it.
 *
 * Written by hand rather than taken from web3-solana or the MWA jars for the
 * same reason [Base58] was: there is no `solana` CLI on the phone, so every
 * buffer write, deploy, upgrade and close is a message this file has to lay
 * out byte-for-byte, and that layout has to be provable on the host JVM with
 * nothing but JUnit. The format is small — a three-byte header, a key table,
 * a blockhash and a list of index-compiled instructions, each length prefixed
 * with [CompactU16] — and the only part with any judgement in it is
 * [Message.compile], which decides the order of the key table. The rules it
 * follows are the runtime's: fee payer first, then the other writable signers,
 * read-only signers, writable non-signers, read-only non-signers; a key that
 * appears more than once is merged with the strongest flags it was given; a
 * program id is a read-only non-signer. Within a group keys keep the order
 * they were first mentioned in, which is what makes a compiled message
 * reproducible in a test without a sort to reason about.
 *
 * [Transaction] holds one 64-byte slot per required signer, zero until
 * signed. Slots are filled one signer at a time — the Seed Vault wallet fills
 * its own, the deploy key fills its own locally — so [Transaction.withSignature]
 * returns a new envelope rather than mutating, and [Transaction.isFullySigned]
 * is the question the RPC client asks before it sends.
 *
 * Nothing here signs or hashes; that is [Keypair] in Keys.kt. Nothing here
 * knows a program's instruction layout; that is Loader.kt.
 */

/**
 * A 32-byte Ed25519 public key or program-derived address.
 *
 * A plain class, not a `value class`: a value class over a `ByteArray` would
 * compare by array identity, and the whole of [Message.compile] — merging a
 * key that appears twice, finding the fee payer's slot — hinges on two keys
 * decoded from two places being equal. Content equality is the point.
 */
class Pubkey(val bytes: ByteArray) {

    init {
        require(bytes.size == SIZE) { "a public key is $SIZE bytes, not ${bytes.size}" }
    }

    /** Lazily spelled once; a key is printed far more often than it is made. */
    val base58: String by lazy(LazyThreadSafetyMode.NONE) { Base58.encode(bytes) }

    override fun toString(): String = base58

    override fun equals(other: Any?): Boolean = other is Pubkey && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        const val SIZE = 32

        /** Throws on anything that is not the Base58 of exactly 32 bytes. */
        fun of(base58: String): Pubkey =
            ofOrNull(base58) ?: throw IllegalArgumentException("not a public key: $base58")

        /** Null for a string a row typed or a file held that is not a key. */
        fun ofOrNull(base58: String): Pubkey? {
            val decoded = Base58.decodeOrNull(base58) ?: return null
            return if (decoded.size == SIZE) Pubkey(decoded) else null
        }
    }
}

/** One account an instruction touches, with the two flags the runtime cares about. */
data class AccountMeta(val pubkey: Pubkey, val isSigner: Boolean, val isWritable: Boolean)

/** An instruction before compilation: the program, its accounts, its raw data. */
data class Instruction(val programId: Pubkey, val accounts: List<AccountMeta>, val data: ByteArray)

/** The three counts at the front of every message. */
data class MessageHeader(
    val numRequiredSignatures: Int,
    val numReadonlySigned: Int,
    val numReadonlyUnsigned: Int,
)

/** An instruction after compilation: every account is an index into [Message.accountKeys]. */
data class CompiledInstruction(val programIdIndex: Int, val accountIndexes: List<Int>, val data: ByteArray)

/**
 * The part of a transaction that gets signed.
 *
 * [recentBlockhash] is kept as 32 raw bytes because that is what it is on the
 * wire; the Base58 string the RPC hands out is decoded once in [compile].
 */
class Message(
    val header: MessageHeader,
    val accountKeys: List<Pubkey>,
    val recentBlockhash: ByteArray,
    val instructions: List<CompiledInstruction>,
) {

    init {
        require(recentBlockhash.size == BLOCKHASH_SIZE) { "a blockhash is $BLOCKHASH_SIZE bytes" }
        require(header.numRequiredSignatures <= accountKeys.size) { "more signers than keys" }
    }

    /** How many 64-byte signature slots a [Transaction] over this message has. */
    val signerCount: Int get() = header.numRequiredSignatures

    /** Index in the key table, or -1 when the key is not part of this message. */
    fun indexOf(pubkey: Pubkey): Int = accountKeys.indexOf(pubkey)

    /** Whether [pubkey] is one of the keys that has to sign this message. */
    fun isSigner(pubkey: Pubkey): Boolean = indexOf(pubkey).let { it in 0 until signerCount }

    fun serialize(): ByteArray {
        val out = ByteWriter()
        out.u8(header.numRequiredSignatures)
        out.u8(header.numReadonlySigned)
        out.u8(header.numReadonlyUnsigned)
        out.compact(accountKeys.size)
        for (key in accountKeys) out.bytes(key.bytes)
        out.bytes(recentBlockhash)
        out.compact(instructions.size)
        for (ix in instructions) {
            out.u8(ix.programIdIndex)
            out.compact(ix.accountIndexes.size)
            for (index in ix.accountIndexes) out.u8(index)
            out.compact(ix.data.size)
            out.bytes(ix.data)
        }
        return out.toByteArray()
    }

    companion object {
        const val BLOCKHASH_SIZE = 32

        /**
         * Build the key table and index the instructions against it.
         *
         * @param feePayer always slot 0, signer and writable, whether or not
         *   any instruction mentions it.
         * @param recentBlockhash the Base58 the RPC's getLatestBlockhash returned.
         */
        fun compile(feePayer: Pubkey, instructions: List<Instruction>, recentBlockhash: String): Message {
            val blockhash = Base58.decodeOrNull(recentBlockhash)
                ?: throw IllegalArgumentException("not a blockhash: $recentBlockhash")
            require(blockhash.size == BLOCKHASH_SIZE) { "not a blockhash: $recentBlockhash" }

            // Merge every mention of a key into one meta with the strongest flags,
            // keeping first-mention order so the result is reproducible.
            val flags = LinkedHashMap<Pubkey, Flags>()
            fun note(key: Pubkey, signer: Boolean, writable: Boolean) {
                val prior = flags[key]
                flags[key] = Flags(
                    signer = signer || (prior?.signer ?: false),
                    writable = writable || (prior?.writable ?: false),
                )
            }
            note(feePayer, signer = true, writable = true)
            for (ix in instructions) {
                for (meta in ix.accounts) note(meta.pubkey, meta.isSigner, meta.isWritable)
                note(ix.programId, signer = false, writable = false)
            }

            val others = flags.entries.filter { it.key != feePayer }
            val signerWritable = others.filter { it.value.signer && it.value.writable }.map { it.key }
            val signerReadonly = others.filter { it.value.signer && !it.value.writable }.map { it.key }
            val plainWritable = others.filter { !it.value.signer && it.value.writable }.map { it.key }
            val plainReadonly = others.filter { !it.value.signer && !it.value.writable }.map { it.key }

            val keys = ArrayList<Pubkey>(flags.size)
            keys.add(feePayer)
            keys.addAll(signerWritable)
            keys.addAll(signerReadonly)
            keys.addAll(plainWritable)
            keys.addAll(plainReadonly)
            require(keys.size <= 256) { "a message holds at most 256 keys, not ${keys.size}" }

            val header = MessageHeader(
                numRequiredSignatures = 1 + signerWritable.size + signerReadonly.size,
                numReadonlySigned = signerReadonly.size,
                numReadonlyUnsigned = plainReadonly.size,
            )
            val compiled = instructions.map { ix ->
                CompiledInstruction(
                    programIdIndex = keys.indexOf(ix.programId),
                    accountIndexes = ix.accounts.map { keys.indexOf(it.pubkey) },
                    data = ix.data,
                )
            }
            return Message(header, keys, blockhash, compiled)
        }

        /** The inverse of [serialize]. Legacy messages only; a versioned prefix throws. */
        fun deserialize(bytes: ByteArray): Message {
            val reader = ByteReader(bytes)
            val required = reader.u8()
            require(required and 0x80 == 0) { "versioned messages are not supported" }
            val header = MessageHeader(required, reader.u8(), reader.u8())
            val keyCount = reader.compact()
            val keys = List(keyCount) { Pubkey(reader.bytes(Pubkey.SIZE)) }
            val blockhash = reader.bytes(BLOCKHASH_SIZE)
            val ixCount = reader.compact()
            val instructions = List(ixCount) {
                val program = reader.u8()
                val accountCount = reader.compact()
                val accounts = List(accountCount) { reader.u8() }
                val dataLength = reader.compact()
                CompiledInstruction(program, accounts, reader.bytes(dataLength))
            }
            require(reader.remaining == 0) { "${reader.remaining} trailing bytes after the message" }
            return Message(header, keys, blockhash, instructions)
        }
    }

    private data class Flags(val signer: Boolean, val writable: Boolean)
}

/**
 * A message plus its signature slots. Immutable: signing returns a new one.
 *
 * @param signatures exactly [Message.signerCount] entries of 64 bytes; an
 *   all-zero entry is an unsigned slot.
 */
class Transaction(val message: Message, val signatures: Array<ByteArray>) {

    init {
        require(signatures.size == message.signerCount) {
            "${signatures.size} signature slots for ${message.signerCount} signers"
        }
        require(signatures.all { it.size == SIGNATURE_SIZE }) { "a signature is $SIGNATURE_SIZE bytes" }
    }

    /** Every slot holds a real signature. */
    val isFullySigned: Boolean get() = signatures.all { !it.isZero() }

    /** The transaction id: Base58 of the fee payer's signature, once there is one. */
    val signature: String? get() = signatures.firstOrNull()?.takeUnless { it.isZero() }?.let(Base58::encode)

    /** Whether [signer] has a slot in this transaction at all. */
    fun requiresSignatureFrom(signer: Pubkey): Boolean = message.isSigner(signer)

    /** A copy with [signer]'s slot filled. Throws if [signer] is not one of the required signers. */
    fun withSignature(signer: Pubkey, signature: ByteArray): Transaction {
        require(signature.size == SIGNATURE_SIZE) { "a signature is $SIGNATURE_SIZE bytes, not ${signature.size}" }
        val index = message.indexOf(signer)
        require(index in 0 until message.signerCount) { "$signer is not a signer of this transaction" }
        val slots = Array(signatures.size) { i -> if (i == index) signature.copyOf() else signatures[i] }
        return Transaction(message, slots)
    }

    /** Signature count, the slots, then the message — the bytes the RPC and the wallet take. */
    fun serialize(): ByteArray {
        val out = ByteWriter()
        out.compact(signatures.size)
        for (slot in signatures) out.bytes(slot)
        out.bytes(message.serialize())
        return out.toByteArray()
    }

    companion object {
        const val SIGNATURE_SIZE = 64

        /** All slots zero. */
        fun unsigned(message: Message): Transaction =
            Transaction(message, Array(message.signerCount) { ByteArray(SIGNATURE_SIZE) })

        /** The inverse of [serialize]; what a wallet hands back after signing. */
        fun deserialize(bytes: ByteArray): Transaction {
            val reader = ByteReader(bytes)
            val count = reader.compact()
            val slots = Array(count) { reader.bytes(SIGNATURE_SIZE) }
            val message = Message.deserialize(reader.rest())
            return Transaction(message, slots)
        }

        private fun ByteArray.isZero(): Boolean = all { it == 0.toByte() }
    }
}

/** Standard Base64 with padding, the encoding the JSON-RPC uses for account data and transactions. */
fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

/** The inverse of [toBase64]; throws on malformed input, which is what a bad RPC reply deserves. */
fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

/** Little-endian integers and compact-u16 prefixes, appended in order. */
internal class ByteWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun u8(value: Int) {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        out.write(value)
    }

    fun u32(value: Long) {
        require(value in 0..0xFFFF_FFFFL) { "u32 out of range: $value" }
        for (shift in 0 until 32 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    fun u64(value: Long) {
        for (shift in 0 until 64 step 8) out.write(((value ushr shift) and 0xFF).toInt())
    }

    fun compact(value: Int) = out.write(CompactU16.encode(value))

    fun bytes(value: ByteArray) = out.write(value)

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** The reading side of [ByteWriter]; bounds errors surface as [IllegalArgumentException]. */
internal class ByteReader(private val bytes: ByteArray, private var offset: Int = 0) {

    val remaining: Int get() = bytes.size - offset

    fun u8(): Int {
        require(remaining >= 1) { "message ended early" }
        return bytes[offset++].toInt() and 0xFF
    }

    fun u32(): Long {
        val chunk = bytes(4)
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (chunk[i].toLong() and 0xFF)
        return value
    }

    fun u64(): Long {
        val chunk = bytes(8)
        var value = 0L
        for (i in 7 downTo 0) value = (value shl 8) or (chunk[i].toLong() and 0xFF)
        return value
    }

    fun compact(): Int {
        require(remaining >= 1) { "message ended early" }
        val (value, length) = CompactU16.decode(bytes, offset)
        offset += length
        return value
    }

    fun bytes(length: Int): ByteArray {
        require(length >= 0 && remaining >= length) { "message ended early: wanted $length, had $remaining" }
        val chunk = bytes.copyOfRange(offset, offset + length)
        offset += length
        return chunk
    }

    fun rest(): ByteArray = bytes(remaining)
}
