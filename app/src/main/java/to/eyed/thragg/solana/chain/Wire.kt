package to.eyed.thragg.solana.chain

import java.util.Base64

/**
 * The Solana transaction on the wire, in its two shapes this app sends:
 * public keys, account metas, instructions, the compiled message, and the
 * signed envelope around it.
 *
 * Written by hand rather than taken from web3-solana or the MWA jars for the
 * same reason [Base58] was: there is no `solana` CLI on the phone, so every
 * buffer write, deploy, upgrade and close is a message this file has to lay
 * out byte-for-byte, and that layout has to be provable on the host JVM with
 * nothing but JUnit. The **legacy** format is small — a three-byte header, a
 * key table, a blockhash and a list of index-compiled instructions, each
 * length prefixed with [CompactU16]. **Transaction V1** (SIMD-0385, feature
 * `enable_tx_v1`, active on devnet, testnet and mainnet-beta as of
 * 2026-09-19) is the same message with a `0x81` version byte, a 4,096-byte
 * ceiling instead of 1,232, fixed-width counts instead of compact-u16, no
 * address lookup tables, and a [TxConfig] in the header where legacy needed
 * ComputeBudget instructions — a V1 transaction *ignores* those instructions,
 * so the compute-unit limit and the priority fee have to be in the mask or
 * they are not set. Both layouts are pinned in WireTest against the vectors
 * solana-message 5.0.0 produces.
 *
 * The only part with any judgement in it is [Message.compile], which decides
 * the order of the key table and is shared by both formats. The rules it
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
 * is the question the RPC client asks before it sends. The envelope differs
 * by format: legacy is a compact count, the slots, then the message; V1 is
 * the message then the slots, no count — so [Transaction.deserialize] reads
 * the first byte before anything else.
 *
 * Nothing here signs or hashes; that is [Keypair] in Keys.kt. Nothing here
 * knows a program's instruction layout; that is Loader.kt. Nothing here
 * chooses a format; that is TxFormatPolicy.kt.
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

/**
 * Which wire layout a [Message] serializes to, with the ceiling the cluster
 * enforces on the whole signed transaction in that layout: 1,232 bytes for
 * legacy (one IPv6 MTU minus headers, `PACKET_DATA_SIZE`), 4,096 for V1
 * (`solana_message::v1::MAX_TRANSACTION_SIZE`). Neither is checked here —
 * a message is refused by the RPC, not by the serializer — but every caller
 * that packs a transaction (Loader.writeChunkSize, PowFaucet.message) sizes
 * against the format's number rather than a constant of its own.
 */
enum class TxFormat(val maxTransactionSize: Int) {
    Legacy(1232),
    V1(4096),
}

/**
 * The V1 header's configuration: what legacy transactions say with
 * ComputeBudget instructions, said once in a bitmask (SIMD-0385).
 *
 * The mask is regenerated from the fields on every serialization, so a
 * message can never carry an unknown bit or half of the two-bit priority-fee
 * pair; those are refused on the way *in* ([Message.deserialize]), the way
 * solana-message 5.0.0 refuses them. The ranges are the runtime's: every
 * value is a `u32` except the fee, which is a `u64`, and a heap size is a
 * multiple of 1 KiB in `[32 KiB, 256 KiB]` or the transaction fails
 * sanitization.
 *
 * WHAT AN UNSET BIT MEANS IS NOT "THE DEFAULT". For a legacy transaction
 * with no ComputeBudget instruction the runtime assumes 200,000 compute
 * units per instruction (3,000 for a builtin) and 64 MiB of loadable
 * account data. For V1 an unset bit means **zero** — agave's
 * `runtime-transaction/src/transaction_meta.rs` `from_v1_config` is
 * `compute_unit_limit.unwrap_or(0)` and
 * `loaded_accounts_data_size_limit.unwrap_or(0)` (read 2026-09-19), and
 * SIMD-0385 says the same in words. A V1 transaction sent with [NONE] is
 * therefore refused for exceeding a budget of nothing, and every V1 message
 * this app sends carries at least those two values (TxFormatPolicy.config).
 * [NONE] exists for legacy messages, which must not carry a config at all.
 */
data class TxConfig(
    val priorityFeeLamports: Long? = null,
    val computeUnitLimit: Long? = null,
    val loadedAccountsDataSize: Long? = null,
    val heapSize: Long? = null,
) {

    init {
        priorityFeeLamports?.let { require(it >= 0L) { "priority fee out of range: $it" } }
        computeUnitLimit?.let { require(it in U32) { "compute unit limit out of range: $it" } }
        loadedAccountsDataSize?.let { require(it in U32) { "loaded accounts data size out of range: $it" } }
        heapSize?.let {
            require(it in MIN_HEAP_SIZE..MAX_HEAP_SIZE && it % HEAP_STEP == 0L) {
                "heap size must be a multiple of $HEAP_STEP in [$MIN_HEAP_SIZE, $MAX_HEAP_SIZE], not $it"
            }
        }
    }

    /** The `u32` TransactionConfigMask: bits 0-1 fee, 2 compute units, 3 account data, 4 heap. */
    val mask: Long
        get() = (if (priorityFeeLamports != null) BITS_PRIORITY_FEE else 0L) or
            (if (computeUnitLimit != null) BIT_COMPUTE_UNIT_LIMIT else 0L) or
            (if (loadedAccountsDataSize != null) BIT_LOADED_ACCOUNTS_DATA_SIZE else 0L) or
            (if (heapSize != null) BIT_HEAP_SIZE else 0L)

    /** How many bytes the config values take after the key table. */
    val size: Int
        get() = (if (priorityFeeLamports != null) 8 else 0) +
            (if (computeUnitLimit != null) 4 else 0) +
            (if (loadedAccountsDataSize != null) 4 else 0) +
            (if (heapSize != null) 4 else 0)

    val isNone: Boolean get() = this == NONE

    /** The values, in bit order, only those whose bits are set. */
    internal fun write(out: ByteWriter) {
        priorityFeeLamports?.let(out::u64)
        computeUnitLimit?.let(out::u32)
        loadedAccountsDataSize?.let(out::u32)
        heapSize?.let(out::u32)
    }

    companion object {
        val NONE = TxConfig()

        const val BITS_PRIORITY_FEE = 0b11L
        const val BIT_COMPUTE_UNIT_LIMIT = 0b100L
        const val BIT_LOADED_ACCOUNTS_DATA_SIZE = 0b1000L
        const val BIT_HEAP_SIZE = 0b10000L
        const val KNOWN_BITS = BITS_PRIORITY_FEE or BIT_COMPUTE_UNIT_LIMIT or BIT_LOADED_ACCOUNTS_DATA_SIZE or BIT_HEAP_SIZE

        const val HEAP_STEP = 1024L
        const val MIN_HEAP_SIZE = 32L * 1024L
        const val MAX_HEAP_SIZE = 256L * 1024L

        /** The runtime's `MAX_COMPUTE_UNIT_LIMIT`; a larger request is clamped to it, not refused. */
        const val MAX_COMPUTE_UNIT_LIMIT = 1_400_000L

        /** The runtime's `MAX_LOADED_ACCOUNTS_DATA_SIZE_BYTES`, and what a legacy transaction gets by default. */
        const val MAX_LOADED_ACCOUNTS_DATA_SIZE = 64L * 1024L * 1024L

        private val U32 = 0L..0xFFFF_FFFFL

        /**
         * A mask read off the wire, then its values in bit order. Refuses a
         * mask with a bit this code does not know (it could not be
         * re-serialized, and the message is signed) and one with a single
         * priority-fee bit, exactly as upstream's deserializer does.
         */
        internal fun read(mask: Long, reader: ByteReader): TxConfig {
            require(mask and KNOWN_BITS.inv() == 0L) { "unknown transaction config bits in mask $mask" }
            val feeBits = mask and BITS_PRIORITY_FEE
            require(feeBits == 0L || feeBits == BITS_PRIORITY_FEE) { "a priority fee is both bits 0 and 1, not one of them" }
            return TxConfig(
                priorityFeeLamports = if (feeBits != 0L) reader.u64() else null,
                computeUnitLimit = if (mask and BIT_COMPUTE_UNIT_LIMIT != 0L) reader.u32() else null,
                loadedAccountsDataSize = if (mask and BIT_LOADED_ACCOUNTS_DATA_SIZE != 0L) reader.u32() else null,
                heapSize = if (mask and BIT_HEAP_SIZE != 0L) reader.u32() else null,
            )
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
 * wire; the Base58 string the RPC hands out is decoded once in [compile]. In
 * V1 the same 32 bytes are the "lifetime specifier"; nothing else about them
 * changes.
 *
 * [format] decides the bytes [serialize] emits and the limits the message is
 * held to at construction: legacy keeps the 256-key rule it always had and
 * must carry [TxConfig.NONE]; V1 is held to the runtime's `validate` —
 * at most 64 addresses, 64 instructions and 12 signatures, at least one
 * writable signer, no duplicate addresses, no program at slot 0, and per
 * instruction at most 255 account indexes and 65,535 data bytes, every index
 * inside the table. Checked here rather than in [compile] alone so that a
 * message a wallet hands back is held to the same rules.
 */
class Message(
    val header: MessageHeader,
    val accountKeys: List<Pubkey>,
    val recentBlockhash: ByteArray,
    val instructions: List<CompiledInstruction>,
    val format: TxFormat = TxFormat.Legacy,
    val config: TxConfig = TxConfig.NONE,
) {

    init {
        require(recentBlockhash.size == BLOCKHASH_SIZE) { "a blockhash is $BLOCKHASH_SIZE bytes" }
        require(header.numRequiredSignatures <= accountKeys.size) { "more signers than keys" }
        when (format) {
            TxFormat.Legacy -> require(config.isNone) { "a legacy message carries no transaction config" }
            TxFormat.V1 -> validateV1()
        }
    }

    private fun validateV1() {
        require(accountKeys.size <= V1_MAX_ADDRESSES) { "a V1 message holds at most $V1_MAX_ADDRESSES addresses, not ${accountKeys.size}" }
        require(instructions.size <= V1_MAX_INSTRUCTIONS) { "a V1 message holds at most $V1_MAX_INSTRUCTIONS instructions, not ${instructions.size}" }
        require(header.numRequiredSignatures <= V1_MAX_SIGNATURES) { "a V1 transaction has at most $V1_MAX_SIGNATURES signatures, not ${header.numRequiredSignatures}" }
        require(header.numReadonlySigned < header.numRequiredSignatures) { "a V1 message needs a writable signer to pay" }
        require(accountKeys.size >= header.numRequiredSignatures + header.numReadonlyUnsigned) { "the V1 header counts more keys than the table holds" }
        require(accountKeys.toSet().size == accountKeys.size) { "a V1 message may not list an address twice" }
        for (ix in instructions) {
            require(ix.programIdIndex in 1 until accountKeys.size) { "program index ${ix.programIdIndex} is not in the table (or is the fee payer)" }
            require(ix.accountIndexes.size <= 0xFF) { "a V1 instruction names at most 255 accounts, not ${ix.accountIndexes.size}" }
            require(ix.data.size <= 0xFFFF) { "a V1 instruction carries at most 65535 data bytes, not ${ix.data.size}" }
            require(ix.accountIndexes.all { it in accountKeys.indices }) { "an instruction's account index is outside the table" }
        }
    }

    /** How many 64-byte signature slots a [Transaction] over this message has. */
    val signerCount: Int get() = header.numRequiredSignatures

    /** Index in the key table, or -1 when the key is not part of this message. */
    fun indexOf(pubkey: Pubkey): Int = accountKeys.indexOf(pubkey)

    /** Whether [pubkey] is one of the keys that has to sign this message. */
    fun isSigner(pubkey: Pubkey): Boolean = indexOf(pubkey).let { it in 0 until signerCount }

    /** The bytes that get signed, in [format]'s layout. */
    fun serialize(): ByteArray = when (format) {
        TxFormat.Legacy -> serializeLegacy()
        TxFormat.V1 -> serializeV1()
    }

    private fun serializeLegacy(): ByteArray {
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

    /**
     * SIMD-0385, as solana-message 5.0.0 writes it: version byte, the three
     * header bytes, the `u32` config mask, the lifetime specifier, `u8`
     * instruction count, `u8` address count, the addresses, the config
     * values in bit order, then EVERY instruction's four-byte header
     * (program index, account count, `u16` data length) before ANY
     * instruction's payload (indexes, then data). No compact-u16 anywhere.
     */
    private fun serializeV1(): ByteArray {
        val out = ByteWriter()
        out.u8(V1_PREFIX)
        out.u8(header.numRequiredSignatures)
        out.u8(header.numReadonlySigned)
        out.u8(header.numReadonlyUnsigned)
        out.u32(config.mask)
        out.bytes(recentBlockhash)
        out.u8(instructions.size)
        out.u8(accountKeys.size)
        for (key in accountKeys) out.bytes(key.bytes)
        config.write(out)
        for (ix in instructions) {
            out.u8(ix.programIdIndex)
            out.u8(ix.accountIndexes.size)
            out.u16(ix.data.size)
        }
        for (ix in instructions) {
            for (index in ix.accountIndexes) out.u8(index)
            out.bytes(ix.data)
        }
        return out.toByteArray()
    }

    companion object {
        const val BLOCKHASH_SIZE = 32

        /** `MESSAGE_VERSION_PREFIX | 1`: the first byte of every V1 message. */
        const val V1_PREFIX = 0x81

        /** `MESSAGE_VERSION_PREFIX`: v0, which this app neither sends nor reads. */
        const val V0_PREFIX = 0x80

        const val V1_MAX_ADDRESSES = 64
        const val V1_MAX_INSTRUCTIONS = 64
        const val V1_MAX_SIGNATURES = 12

        /**
         * Build the key table and index the instructions against it. The
         * table is ordered the same way for both formats; [format] and
         * [config] only decide what [serialize] emits and which limits the
         * result is held to.
         *
         * @param feePayer always slot 0, signer and writable, whether or not
         *   any instruction mentions it.
         * @param recentBlockhash the Base58 the RPC's getLatestBlockhash returned.
         */
        fun compile(
            feePayer: Pubkey,
            instructions: List<Instruction>,
            recentBlockhash: String,
            format: TxFormat = TxFormat.Legacy,
            config: TxConfig = TxConfig.NONE,
        ): Message {
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
            return Message(header, keys, blockhash, compiled, format, config)
        }

        /**
         * The inverse of [serialize], by the first byte: `0x81` is V1,
         * `0x80` is v0 (address lookup tables, which this app has no use for
         * and does not read), anything under `0x80` is a legacy header.
         * Nothing may follow the message.
         */
        fun deserialize(bytes: ByteArray): Message {
            val reader = ByteReader(bytes)
            val message = read(reader)
            require(reader.remaining == 0) { "${reader.remaining} trailing bytes after the message" }
            return message
        }

        /** One message off the front of [reader], leaving whatever follows — a V1 transaction's signatures. */
        internal fun read(reader: ByteReader): Message {
            val first = reader.u8()
            return when {
                first == V1_PREFIX -> readV1(reader)
                first and 0x80 != 0 -> throw IllegalArgumentException("versioned messages are not supported")
                else -> readLegacy(first, reader)
            }
        }

        private fun readLegacy(required: Int, reader: ByteReader): Message {
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
            return Message(header, keys, blockhash, instructions)
        }

        private fun readV1(reader: ByteReader): Message {
            val header = MessageHeader(reader.u8(), reader.u8(), reader.u8())
            val mask = reader.u32()
            val blockhash = reader.bytes(BLOCKHASH_SIZE)
            val ixCount = reader.u8()
            val keyCount = reader.u8()
            val keys = List(keyCount) { Pubkey(reader.bytes(Pubkey.SIZE)) }
            val config = TxConfig.read(mask, reader)
            val headers = List(ixCount) { Triple(reader.u8(), reader.u8(), reader.u16()) }
            val instructions = headers.map { (program, accountCount, dataLength) ->
                val accounts = List(accountCount) { reader.u8() }
                CompiledInstruction(program, accounts, reader.bytes(dataLength))
            }
            return Message(header, keys, blockhash, instructions, TxFormat.V1, config)
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

    /**
     * The bytes the RPC and the wallet take. Legacy: signature count, the
     * slots, then the message. V1: the message, then the slots — no count,
     * because the header already says how many, and nothing after them.
     */
    fun serialize(): ByteArray {
        val out = ByteWriter()
        when (message.format) {
            TxFormat.Legacy -> {
                out.compact(signatures.size)
                for (slot in signatures) out.bytes(slot)
                out.bytes(message.serialize())
            }
            TxFormat.V1 -> {
                out.bytes(message.serialize())
                for (slot in signatures) out.bytes(slot)
            }
        }
        return out.toByteArray()
    }

    /** Whether the serialized bytes are within the format's ceiling ([TxFormat.maxTransactionSize]). */
    val fits: Boolean get() = serialize().size <= message.format.maxTransactionSize

    companion object {
        const val SIGNATURE_SIZE = 64

        /** All slots zero. */
        fun unsigned(message: Message): Transaction =
            Transaction(message, Array(message.signerCount) { ByteArray(SIGNATURE_SIZE) })

        /**
         * The inverse of [serialize]; what a wallet hands back after signing.
         * Dispatches on the first byte: `0x81` is a V1 envelope (message
         * first, then exactly the header's number of slots), `0x80` is a v0
         * message this app does not read, and anything else is the legacy
         * compact signature count — which can never itself be `0x80` or
         * more, since that would be 128 signatures in a 1,232-byte packet.
         */
        fun deserialize(bytes: ByteArray): Transaction {
            require(bytes.isNotEmpty()) { "an empty transaction" }
            val first = bytes[0].toInt() and 0xFF
            val reader = ByteReader(bytes)
            return when {
                first == Message.V1_PREFIX -> {
                    val message = Message.read(reader)
                    val slots = Array(message.signerCount) { reader.bytes(SIGNATURE_SIZE) }
                    require(reader.remaining == 0) { "${reader.remaining} trailing bytes after the signatures" }
                    Transaction(message, slots)
                }
                first and 0x80 != 0 -> throw IllegalArgumentException("versioned messages are not supported")
                else -> {
                    val count = reader.compact()
                    val slots = Array(count) { reader.bytes(SIGNATURE_SIZE) }
                    val message = Message.deserialize(reader.rest())
                    Transaction(message, slots)
                }
            }
        }

        private fun ByteArray.isZero(): Boolean = all { it == 0.toByte() }
    }
}

/** Standard Base64 with padding, the encoding the JSON-RPC uses for account data and transactions. */
fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

/** The inverse of [toBase64]; throws on malformed input, which is what a bad RPC reply deserves. */
fun String.fromBase64(): ByteArray = Base64.getDecoder().decode(this)

/** Little-endian integers (u8, u16, u32, u64) and compact-u16 prefixes, appended in order. */
internal class ByteWriter {
    private val out = java.io.ByteArrayOutputStream()

    fun u8(value: Int) {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        out.write(value)
    }

    fun u16(value: Int) {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
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

    fun u16(): Int {
        val chunk = bytes(2)
        return (chunk[0].toInt() and 0xFF) or ((chunk[1].toInt() and 0xFF) shl 8)
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
