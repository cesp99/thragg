package to.eyed.seeker.code.solana.chain

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The BPF upgradeable loader, spelled out in bytes: its instruction encodings,
 * its account layouts, and the arithmetic that turns an ELF size into a SOL
 * figure for the Deploy sheet.
 *
 * This file exists because there is no `solana` CLI on the phone. Agave ships
 * no arm64 build, so `solana program deploy` — the thing that knows how to
 * carve a `.so` into buffer writes and finish with `DeployWithMaxDataLen` — is
 * not available, and every byte it would have produced has to be produced
 * here instead. The shape follows from that:
 *
 *  - **Pure.** Nothing in here does IO or touches Android. A builder takes
 *    public keys and returns an [Instruction]; a parser takes an account's raw
 *    bytes and returns a [State]. That is what lets the encodings be checked
 *    byte-for-byte on the host JVM, which matters because a wrong byte here is
 *    a transaction the cluster rejects with an error that names nothing.
 *  - **Bincode by hand.** The loader's wire format is bincode: a `u32` LE
 *    enum tag, then the fields in order, with `u64` LE lengths on byte
 *    vectors. There are six instructions and four account states; a
 *    serialization library would be more code than the encodings themselves.
 *  - **Account metas in the loader's order.** The program checks accounts by
 *    position, not by key, so each builder lists them exactly as
 *    `bpf_loader_upgradeable::instruction` does, signer and writable flags
 *    included. [Message.compile] then sorts the keys; the metas are the source
 *    of truth for what it sorts.
 *  - **Chunk size from the serializer, not a constant.** A legacy transaction
 *    is at most 1232 bytes. The CLI derives its write chunk by serializing a
 *    Write with an empty chunk and subtracting; so does [writeChunkSize], so
 *    that if the message encoder ever changes the chunk follows it.
 *
 * Reference layouts (verified 2026-09-02 against the loader's `state.rs` and
 * `instruction.rs`):
 *
 *  - Buffer: `[0..4]` tag 1, `[4]` option, `[5..37]` authority; data at 37.
 *  - Program: `[0..4]` tag 2, `[4..36]` programdata address; exactly 36 bytes.
 *  - ProgramData: `[0..4]` tag 3, `[4..12]` slot u64 LE, `[12]` option,
 *    `[13..45]` upgrade authority; ELF at 45.
 */
object Loader {

    /** `BPFLoaderUpgradeab1e11111111111111111111111`. */
    val PROGRAM_ID: Pubkey = Pubkey.of("BPFLoaderUpgradeab1e11111111111111111111111")

    /** `11111111111111111111111111111111` — thirty-two zero bytes. */
    val SYSTEM_PROGRAM: Pubkey = Pubkey.of("11111111111111111111111111111111")

    val SYSVAR_RENT: Pubkey = Pubkey.of("SysvarRent111111111111111111111111111111111")
    val SYSVAR_CLOCK: Pubkey = Pubkey.of("SysvarC1ock11111111111111111111111111111111")

    /** Bytes before the ELF in a Buffer account: tag, option, authority. */
    const val BUFFER_HEADER = 37

    /** The whole Program account: tag plus the programdata address. */
    const val PROGRAM_SIZE = 36

    /** Bytes before the ELF in a ProgramData account: tag, slot, option, authority. */
    const val PROGRAMDATA_HEADER = 45

    /** The legacy transaction ceiling: one IPv6 MTU minus headers. */
    const val MAX_TRANSACTION_SIZE = 1232

    /** What the cluster charges per signature, in lamports. */
    const val LAMPORTS_PER_SIGNATURE = 5_000L

    /**
     * How much room a fresh deploy reserves, as a multiple of the ELF.
     *
     * One, not two. The CLI once reserved twice the program and paid twice
     * the rent for it, permanently; today its default is the program's own
     * length, because a programdata account grows through [extendProgram]
     * when the next build is bigger — and this deployer sends that before an
     * upgrade whenever it has to. Rent on a 200 kB program is 1.4 SOL, so on
     * mainnet the difference is real money spent on room nobody may use.
     */
    const val MAX_DATA_LEN_FACTOR = 1

    /** The `max_data_len` a fresh deploy of [elfBytes] asks for. */
    fun maxDataLen(elfBytes: Int): Long = MAX_DATA_LEN_FACTOR.toLong() * elfBytes

    // Loader instruction tags, in `UpgradeableLoaderInstruction` order.
    private const val TAG_INITIALIZE_BUFFER = 0
    private const val TAG_WRITE = 1
    private const val TAG_DEPLOY_WITH_MAX_DATA_LEN = 2
    private const val TAG_UPGRADE = 3
    private const val TAG_SET_AUTHORITY = 4
    private const val TAG_CLOSE = 5
    private const val TAG_EXTEND_PROGRAM = 6

    // System program instruction tags, in `SystemInstruction` order.
    private const val SYSTEM_CREATE_ACCOUNT = 0
    private const val SYSTEM_TRANSFER = 2

    // Account state tags, in `UpgradeableLoaderState` order.
    private const val STATE_UNINITIALIZED = 0
    private const val STATE_BUFFER = 1
    private const val STATE_PROGRAM = 2
    private const val STATE_PROGRAMDATA = 3

    /**
     * The rent-exempt minimum for an account of [bytes] data: the runtime's
     * `(128 + bytes) * 6960` with default rent parameters. For the estimate
     * row only — a live deploy asks the RPC, because a cluster may change it.
     */
    fun rentExempt(bytes: Int): Long = (128L + bytes) * 6960L

    // ---- System program ------------------------------------------------

    /** `SystemInstruction::CreateAccount`: both [from] and [newAccount] sign. */
    fun createAccount(from: Pubkey, newAccount: Pubkey, lamports: Long, space: Long, owner: Pubkey): Instruction {
        val data = ByteBuffer.allocate(4 + 8 + 8 + 32).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SYSTEM_CREATE_ACCOUNT)
            .putLong(lamports)
            .putLong(space)
            .put(owner.bytes)
            .array()
        return Instruction(
            programId = SYSTEM_PROGRAM,
            accounts = listOf(
                AccountMeta(from, isSigner = true, isWritable = true),
                AccountMeta(newAccount, isSigner = true, isWritable = true),
            ),
            data = data,
        )
    }

    /** `SystemInstruction::Transfer`. */
    fun transfer(from: Pubkey, to: Pubkey, lamports: Long): Instruction {
        val data = ByteBuffer.allocate(4 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SYSTEM_TRANSFER)
            .putLong(lamports)
            .array()
        return Instruction(
            programId = SYSTEM_PROGRAM,
            accounts = listOf(
                AccountMeta(from, isSigner = true, isWritable = true),
                AccountMeta(to, isSigner = false, isWritable = true),
            ),
            data = data,
        )
    }

    // ---- Loader ----------------------------------------------------------

    /**
     * `InitializeBuffer`. The authority is *not* a signer here: the account
     * was just created by the same transaction's CreateAccount, and whoever
     * paid for it names the authority.
     */
    fun initializeBuffer(buffer: Pubkey, authority: Pubkey): Instruction = Instruction(
        programId = PROGRAM_ID,
        accounts = listOf(
            AccountMeta(buffer, isSigner = false, isWritable = true),
            AccountMeta(authority, isSigner = false, isWritable = false),
        ),
        data = tag(TAG_INITIALIZE_BUFFER),
    )

    /**
     * `Write { offset, bytes }`. The `bytes` vector is bincode's `u64`
     * length prefix followed by the bytes; the offset is where in the
     * buffer's ELF region — after [BUFFER_HEADER] — the chunk lands.
     */
    fun write(buffer: Pubkey, authority: Pubkey, offset: Int, chunk: ByteArray): Instruction {
        require(offset >= 0) { "write offset must not be negative: $offset" }
        val data = ByteBuffer.allocate(4 + 4 + 8 + chunk.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TAG_WRITE)
            .putInt(offset)
            .putLong(chunk.size.toLong())
            .put(chunk)
            .array()
        return Instruction(
            programId = PROGRAM_ID,
            accounts = listOf(
                AccountMeta(buffer, isSigner = false, isWritable = true),
                AccountMeta(authority, isSigner = true, isWritable = false),
            ),
            data = data,
        )
    }

    /**
     * `DeployWithMaxDataLen { max_data_len }`. The program account must sign
     * (it is being created), the payer funds the programdata account, and the
     * buffer's authority — which must also be the signer at the end — becomes
     * the upgrade authority.
     */
    fun deployWithMaxDataLen(
        payer: Pubkey,
        programData: Pubkey,
        program: Pubkey,
        buffer: Pubkey,
        authority: Pubkey,
        maxDataLen: Long,
    ): Instruction {
        val data = ByteBuffer.allocate(4 + 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TAG_DEPLOY_WITH_MAX_DATA_LEN)
            .putLong(maxDataLen)
            .array()
        return Instruction(
            programId = PROGRAM_ID,
            accounts = listOf(
                AccountMeta(payer, isSigner = true, isWritable = true),
                AccountMeta(programData, isSigner = false, isWritable = true),
                AccountMeta(program, isSigner = true, isWritable = true),
                AccountMeta(buffer, isSigner = false, isWritable = true),
                AccountMeta(SYSVAR_RENT, isSigner = false, isWritable = false),
                AccountMeta(SYSVAR_CLOCK, isSigner = false, isWritable = false),
                AccountMeta(SYSTEM_PROGRAM, isSigner = false, isWritable = false),
                AccountMeta(authority, isSigner = true, isWritable = false),
            ),
            data = data,
        )
    }

    /**
     * The whole fresh-deploy transaction, in the loader's order. The loader
     * does NOT create the program account: `DeployWithMaxDataLen` expects it
     * to exist already, loader-owned, [PROGRAM_SIZE] bytes and rent-exempt,
     * and answers "Program account too small" otherwise. So the CLI's
     * `deploy_with_max_program_len` is two instructions — a system
     * CreateAccount by the payer for the program, then the deploy — and this
     * is that pair. Both are signed by the payer and the program keypair.
     */
    fun deployProgram(
        payer: Pubkey,
        programData: Pubkey,
        program: Pubkey,
        buffer: Pubkey,
        authority: Pubkey,
        programRent: Long,
        maxDataLen: Long,
    ): List<Instruction> = listOf(
        createAccount(payer, program, programRent, PROGRAM_SIZE.toLong(), PROGRAM_ID),
        deployWithMaxDataLen(payer, programData, program, buffer, authority, maxDataLen),
    )

    /**
     * `ExtendProgram { additional_bytes }`: grow a programdata account so a
     * larger ELF can be upgraded into it. No authority signs — anyone may
     * pay to extend — but the [payer] does, since the loader moves the extra
     * rent from it through the system program. `additional_bytes` is a u32.
     */
    fun extendProgram(programData: Pubkey, program: Pubkey, payer: Pubkey, additionalBytes: Int): Instruction {
        require(additionalBytes > 0) { "extend by at least one byte: $additionalBytes" }
        val data = ByteBuffer.allocate(4 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(TAG_EXTEND_PROGRAM)
            .putInt(additionalBytes)
            .array()
        return Instruction(
            programId = PROGRAM_ID,
            accounts = listOf(
                AccountMeta(programData, isSigner = false, isWritable = true),
                AccountMeta(program, isSigner = false, isWritable = true),
                AccountMeta(SYSTEM_PROGRAM, isSigner = false, isWritable = false),
                AccountMeta(payer, isSigner = true, isWritable = true),
            ),
            data = data,
        )
    }

    /** `Upgrade`. The buffer's lamports drain into [spill]. */
    fun upgrade(programData: Pubkey, program: Pubkey, buffer: Pubkey, spill: Pubkey, authority: Pubkey): Instruction =
        Instruction(
            programId = PROGRAM_ID,
            accounts = listOf(
                AccountMeta(programData, isSigner = false, isWritable = true),
                AccountMeta(program, isSigner = false, isWritable = true),
                AccountMeta(buffer, isSigner = false, isWritable = true),
                AccountMeta(spill, isSigner = false, isWritable = true),
                AccountMeta(SYSVAR_RENT, isSigner = false, isWritable = false),
                AccountMeta(SYSVAR_CLOCK, isSigner = false, isWritable = false),
                AccountMeta(authority, isSigner = true, isWritable = false),
            ),
            data = tag(TAG_UPGRADE),
        )

    /** `SetAuthority` on a buffer. */
    fun setBufferAuthority(buffer: Pubkey, current: Pubkey, new: Pubkey): Instruction =
        setAuthority(buffer, current, new)

    /** `SetAuthority` on a programdata account — the upgrade authority. */
    fun setUpgradeAuthority(programData: Pubkey, current: Pubkey, new: Pubkey): Instruction =
        setAuthority(programData, current, new)

    private fun setAuthority(account: Pubkey, current: Pubkey, new: Pubkey): Instruction = Instruction(
        programId = PROGRAM_ID,
        accounts = listOf(
            AccountMeta(account, isSigner = false, isWritable = true),
            AccountMeta(current, isSigner = true, isWritable = false),
            AccountMeta(new, isSigner = false, isWritable = false),
        ),
        data = tag(TAG_SET_AUTHORITY),
    )

    /**
     * `Close` on a programdata account. The fourth account — the program —
     * is what tells the loader this is a program close and not a buffer
     * close; it is marked writable because the loader flips it.
     */
    fun closeProgram(programData: Pubkey, recipient: Pubkey, authority: Pubkey, program: Pubkey): Instruction =
        Instruction(
            programId = PROGRAM_ID,
            accounts = listOf(
                AccountMeta(programData, isSigner = false, isWritable = true),
                AccountMeta(recipient, isSigner = false, isWritable = true),
                AccountMeta(authority, isSigner = true, isWritable = false),
                AccountMeta(program, isSigner = false, isWritable = true),
            ),
            data = tag(TAG_CLOSE),
        )

    /** `Close` on a buffer: three accounts, no program. */
    fun closeBuffer(buffer: Pubkey, recipient: Pubkey, authority: Pubkey): Instruction = Instruction(
        programId = PROGRAM_ID,
        accounts = listOf(
            AccountMeta(buffer, isSigner = false, isWritable = true),
            AccountMeta(recipient, isSigner = false, isWritable = true),
            AccountMeta(authority, isSigner = true, isWritable = false),
        ),
        data = tag(TAG_CLOSE),
    )

    private fun tag(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    // ---- Account state -----------------------------------------------------

    /**
     * What a loader-owned account's bytes say it is. Null authorities are
     * real: a buffer with no authority is immutable, and a programdata with
     * no authority is a program nobody can upgrade or close.
     */
    sealed interface State {
        /** Tag 0, or a programdata account after a successful close. */
        data object Uninitialized : State

        data class Buffer(val authority: Pubkey?) : State

        data class Program(val programData: Pubkey) : State

        data class ProgramData(val slot: Long, val authority: Pubkey?) : State
    }

    /**
     * Parses [data] as one of the four loader layouts, or returns null when it
     * is none of them: too short for its tag, an option byte that is neither
     * 0 nor 1, or a tag the loader does not have. Every read is bounds-checked
     * up front so that a truncated account never becomes an exception in a
     * Settings row.
     */
    fun parse(data: ByteArray): State? {
        if (data.size < 4) return null
        return when (u32(data, 0)) {
            STATE_UNINITIALIZED -> State.Uninitialized
            STATE_BUFFER -> {
                if (data.size < BUFFER_HEADER) return null
                val authority = optionPubkey(data, 4) ?: return null
                State.Buffer(authority.value)
            }
            STATE_PROGRAM -> {
                if (data.size < PROGRAM_SIZE) return null
                State.Program(Pubkey(data.copyOfRange(4, 36)))
            }
            STATE_PROGRAMDATA -> {
                if (data.size < PROGRAMDATA_HEADER) return null
                val slot = ByteBuffer.wrap(data, 4, 8).order(ByteOrder.LITTLE_ENDIAN).long
                val authority = optionPubkey(data, 12) ?: return null
                State.ProgramData(slot, authority.value)
            }
            else -> null
        }
    }

    /** A present-or-absent pubkey, as distinct from "the bytes were garbage". */
    private class Option(val value: Pubkey?)

    /** bincode `Option<Pubkey>` at [at]: one flag byte then 32 bytes. Null on a flag that is neither 0 nor 1. */
    private fun optionPubkey(data: ByteArray, at: Int): Option? = when (data[at].toInt()) {
        0 -> Option(null)
        1 -> Option(Pubkey(data.copyOfRange(at + 1, at + 33)))
        else -> null
    }

    private fun u32(data: ByteArray, at: Int): Int =
        ByteBuffer.wrap(data, at, 4).order(ByteOrder.LITTLE_ENDIAN).int

    // ---- Sizing ------------------------------------------------------------

    @Volatile
    private var cachedChunkSize: Int = 0

    /**
     * How many ELF bytes fit in one Write transaction with a single signer.
     *
     * Computed the way the CLI computes it: serialize a Write with an empty
     * chunk against a dummy blockhash and subtract from the 1232-byte ceiling,
     * then subtract one more byte because the instruction's compact-u16 data
     * length grows from one byte to two as soon as the data passes 127 bytes
     * — which a real chunk always does. Cached after the first call; the
     * probe keys are random because [Message.compile] merges duplicate keys
     * and the buffer and its authority must stay two accounts.
     */
    fun writeChunkSize(): Int {
        val cached = cachedChunkSize
        if (cached > 0) return cached
        val authority = Keypair.generate().publicKey
        val buffer = Keypair.generate().publicKey
        val probe = Message.compile(
            authority,
            listOf(write(buffer, authority, 0, ByteArray(0))),
            Base58.encode(ByteArray(32)),
        )
        val empty = Transaction.unsigned(probe).serialize().size
        val size = MAX_TRANSACTION_SIZE - empty - 1
        check(size in 1 until MAX_TRANSACTION_SIZE) { "write chunk size out of range: $size" }
        cachedChunkSize = size
        return size
    }

    /** [elf] cut into `(offset, bytes)` pieces of [writeChunkSize], the last one shorter. */
    fun chunks(elf: ByteArray): List<Pair<Int, ByteArray>> {
        if (elf.isEmpty()) return emptyList()
        val size = writeChunkSize()
        val out = ArrayList<Pair<Int, ByteArray>>((elf.size + size - 1) / size)
        var offset = 0
        while (offset < elf.size) {
            val end = minOf(offset + size, elf.size)
            out.add(offset to elf.copyOfRange(offset, end))
            offset = end
        }
        return out
    }

    /**
     * The lamports a deploy needs up front, and how many of them stay.
     * [bufferRent] comes back when the deploy or upgrade drains the buffer;
     * [programDataRent] and [programRent] are the [permanent] part.
     */
    data class CostEstimate(
        val bufferRent: Long,
        val programDataRent: Long,
        val programRent: Long,
        val fees: Long,
    ) {
        val total: Long get() = bufferRent + programDataRent + programRent + fees
        val permanent: Long get() = programDataRent + programRent
    }

    /**
     * The formula behind the Deploy sheet's "~1.49 SOL" row.
     *
     * Fresh deploy: a buffer sized for the ELF, a programdata account sized
     * for [maxDataLen] — twice the ELF, so the next build has room — and the
     * 36-byte program account. Upgrade: the buffer only; the programdata is
     * already paid for, and the deployer tops it up with `ExtendProgram` only
     * when the new ELF outgrows it, which the sheet's estimate cannot know
     * without the account. Fees count one signature per Write,
     * plus the buffer-create, deploy-or-upgrade and set-authority transactions
     * with their second signers: `writes + 5` signatures, which rounds an
     * upgrade up by one and keeps the two paths one line.
     */
    fun estimateDeploy(elfBytes: Int, upgrade: Boolean): CostEstimate {
        require(elfBytes >= 0) { "elf size must not be negative: $elfBytes" }
        val chunk = writeChunkSize()
        val writes = (elfBytes + chunk - 1) / chunk
        return CostEstimate(
            bufferRent = rentExempt(BUFFER_HEADER + elfBytes),
            programDataRent = if (upgrade) 0L else rentExempt((PROGRAMDATA_HEADER + maxDataLen(elfBytes)).toInt()),
            programRent = if (upgrade) 0L else rentExempt(PROGRAM_SIZE),
            fees = LAMPORTS_PER_SIGNATURE * (writes + 5),
        )
    }

    /**
     * `1.4321 SOL`: four decimals, trailing zeros dropped, `0 SOL` for zero.
     * A positive amount too small to show at four decimals prints as
     * `<0.0001 SOL` rather than lying with a zero — a 5000-lamport fee is
     * not nothing.
     */
    fun lamportsToSol(lamports: Long): String {
        if (lamports == 0L) return "0 SOL"
        val sign = if (lamports < 0) "-" else ""
        // valueOf(unscaled, 9): the lamport count with the point moved nine
        // places, exactly, before rounding to the four the row shows.
        val sol = BigDecimal.valueOf(Math.abs(lamports), 9).setScale(4, RoundingMode.HALF_UP)
        if (sol.signum() == 0) return "$sign<0.0001 SOL"
        return sign + sol.stripTrailingZeros().toPlainString() + " SOL"
    }
}
