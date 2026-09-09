package to.eyed.thragg.solana.chain

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

    /**
     * The smallest extension the upgradeable loader will accept, and the
     * runtime's own `MAX_PERMITTED_DATA_INCREASE`: 10 kB, one page's worth.
     *
     * THIS IS THE FLOOR THAT KILLED THE EDIT-THEN-REDEPLOY LOOP. Until now
     * the deployer asked `ExtendProgram` for exactly the shortfall, and the
     * loader refuses anything under this unless the extension takes the
     * account to [MAX_PERMITTED_DATA_LENGTH]. Measured on the Seeker
     * 2026-09-09: `r4_anchor` grew 177,920 to 183,616 bytes, the deploy
     * uploaded 180 chunks over 7m17s, and only then did the chain say
     * "ExtendProgram requires a minimum of 10240 additional bytes or to
     * extend to maximum size, but only 5696 were requested" — 0.93452748 SOL
     * parked in a buffer for a run that could never have landed. **Every
     * upgrade whose artifact grew by under 10 kB was impossible**, which is
     * most of them: an added instruction is a few hundred bytes.
     * [extendBytes] is the one place the number is chosen, so the estimate
     * and the run cannot disagree about it again.
     */
    const val MAX_PERMITTED_DATA_INCREASE = 10 * 1024

    /** The ceiling a programdata account may reach: the runtime's `MAX_PERMITTED_DATA_LENGTH`. */
    const val MAX_PERMITTED_DATA_LENGTH = 10 * 1024 * 1024

    /**
     * The `additional_bytes` an upgrade of [elfBytes] must ask for when the
     * programdata has room for [dataLen], or zero when the ELF still fits.
     *
     * Never between one and [MAX_PERMITTED_DATA_INCREASE]: a shortfall under
     * the loader's floor is rounded **up** to the floor, which costs a
     * little more rent (10 kB at devnet's ~5,083 lamports a byte is 0.052
     * SOL, and it comes back when the program is closed) and is the
     * difference between an upgrade that lands and one that fails after
     * nine minutes of upload. Clamped at the top so an account within a page
     * of [MAX_PERMITTED_DATA_LENGTH] asks to extend to exactly the maximum,
     * which is the loader's other accepted answer.
     *
     * Both [estimateDeploy] and `ProgramDeploy.inspect` call this, so the
     * sheet prices the same number the run sends.
     */
    fun extendBytes(elfBytes: Int, dataLen: Long): Int {
        require(elfBytes >= 0) { "elf size must not be negative: $elfBytes" }
        if (elfBytes <= dataLen) return 0
        val room = MAX_PERMITTED_DATA_LENGTH - (PROGRAMDATA_HEADER + dataLen)
        if (room <= 0L) return 0
        val wanted = elfBytes - dataLen
        return maxOf(wanted, MAX_PERMITTED_DATA_INCREASE.toLong()).coerceAtMost(room).toInt()
    }

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
     *
     * [additionalBytes] must come from [extendBytes]: the loader refuses
     * anything under [MAX_PERMITTED_DATA_INCREASE] that does not reach the
     * ceiling, and it refuses it *after* the whole upload.
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
     * What the cluster already has at the id, for an upgrade's estimate:
     * [dataLen] is the programdata's ELF capacity (`max_data_len`) and
     * [reclaimable] its lamports. Both come from [OnChainProgram.Deployed],
     * and without them an upgrade cannot be priced — see [estimateDeploy].
     */
    data class Existing(val dataLen: Long, val reclaimable: Long)

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
     * The formula behind the Deploy sheet's "~1.49 SOL" row, and the same
     * arithmetic the deployer funds by (`ProgramDeploy.fund` calls this, so
     * the two cannot drift).
     *
     * Fresh deploy: a buffer sized for the ELF, a programdata account sized
     * for [maxDataLen] and the 36-byte program account. Upgrade: the buffer,
     * plus — when [existing] is known and the new ELF has outgrown it — what
     * `ExtendProgram` costs, which is the rent of the grown account less the
     * lamports it already holds. That last term is G-21: [MAX_DATA_LEN_FACTOR]
     * is 1, so the previous `max_data_len` is the previous ELF's length and
     * **every upgrade whose artifact grew by a byte pays it**; the sheet used
     * to print zero for it and the deploy then asked for up to a third more
     * than the row said. With [existing] null the upgrade branch still says
     * zero — the caller could not reach the cluster, and a guess would be
     * worse than the `~`.
     *
     * Fees count one signature per Write, plus the buffer-create,
     * deploy-or-upgrade, set-authority and extend transactions with their
     * second signers: `writes + 5` signatures, which rounds an upgrade up by
     * one and keeps the two paths one line.
     *
     * [rent] is [rentExempt]'s formula by default — the sheet's `~` — and
     * the cluster's own `getMinimumBalanceForRentExemption` when the caller
     * can ask (`DeploySheet.gather`, the deployer's `fund`), quoted for the
     * sizes [rentSizes] names so one paced pass covers them. The two differ:
     * on devnet 2026-09-08 the cluster wanted 1.0207 SOL for a 200 kB
     * buffer where the formula said 1.3985, so a sheet that compared the
     * formula with the key's balance called a key with 0.8 SOL to spare
     * "short by 0.02" — and the deployer, a minute later, disagreed in the
     * same log. Same figures in, same figures out — and one pricing per
     * estimate: a caller that could not quote every size falls back to the
     * formula for all of them rather than mixing the two.
     */
    fun estimateDeploy(
        elfBytes: Int,
        upgrade: Boolean,
        rent: (Int) -> Long = ::rentExempt,
        existing: Existing? = null,
    ): CostEstimate {
        require(elfBytes >= 0) { "elf size must not be negative: $elfBytes" }
        val chunk = writeChunkSize()
        val writes = (elfBytes + chunk - 1) / chunk
        val extend = extendSize(elfBytes, upgrade, existing)
        return CostEstimate(
            bufferRent = rent(BUFFER_HEADER + elfBytes),
            programDataRent = when {
                !upgrade -> rent((PROGRAMDATA_HEADER + maxDataLen(elfBytes)).toInt())
                extend != null -> (rent(extend) - (existing?.reclaimable ?: 0L)).coerceAtLeast(0L)
                else -> 0L
            },
            programRent = if (upgrade) 0L else rent(PROGRAM_SIZE),
            fees = LAMPORTS_PER_SIGNATURE * (writes + 5),
        )
    }

    /**
     * The deployer's own threshold: an estimate plus a tenth.
     *
     * `ProgramDeploy.fund` will not start with less than this in the deploy
     * key, and the Deploy sheet's "short by about" line has to use the same
     * number or the sheet says one thing and the log says another 10 % bigger
     * (s4 read the two as a contradiction). One function, both callers.
     */
    fun withMargin(lamports: Long): Long = lamports + lamports / 10

    /**
     * What is left to pay when a buffer from an earlier attempt is being
     * reused: its rent is on chain already, and so is a signature for every
     * chunk that landed. Never negative.
     */
    fun outstanding(estimate: CostEstimate, bufferAlreadyPaid: Boolean, writesAlreadyLanded: Int): Long {
        val paid = (if (bufferAlreadyPaid) estimate.bufferRent else 0L) +
            LAMPORTS_PER_SIGNATURE * writesAlreadyLanded.coerceAtLeast(0)
        return (estimate.total - paid).coerceAtLeast(0L)
    }

    /**
     * For each of [chunks], whether a buffer account's whole [data] already
     * holds exactly those bytes at [BUFFER_HEADER] + its offset.
     *
     * This is what makes a resumed deploy safe: the record in `OpenBuffers`
     * says which account an earlier attempt left, and this says whether the
     * account is *this* artifact — byte for byte, not by size or by hope.
     * An account too short for a chunk answers false for it rather than
     * throwing, so a buffer built for a different build is simply not
     * adopted.
     */
    fun writtenChunks(data: ByteArray, chunks: List<Pair<Int, ByteArray>>): BooleanArray =
        BooleanArray(chunks.size) { index ->
            val (offset, bytes) = chunks[index]
            val at = BUFFER_HEADER + offset
            when {
                at < 0 || at + bytes.size > data.size -> false
                else -> {
                    var same = true
                    for (i in bytes.indices) {
                        if (data[at + i] != bytes[i]) {
                            same = false
                            break
                        }
                    }
                    same
                }
            }
        }

    /**
     * Every account size [estimateDeploy] will price for these arguments, so
     * a caller can quote them all from the cluster in one paced pass before
     * calling it (the `rent` lambda there is not suspending, and six
     * unretried reads scattered through a composition were their own defect).
     * Distinct, and in no particular order.
     */
    fun rentSizes(elfBytes: Int, upgrade: Boolean, existing: Existing? = null): List<Int> {
        require(elfBytes >= 0) { "elf size must not be negative: $elfBytes" }
        val sizes = ArrayList<Int>(3)
        sizes.add(BUFFER_HEADER + elfBytes)
        if (upgrade) {
            extendSize(elfBytes, upgrade, existing)?.let { sizes.add(it) }
        } else {
            sizes.add((PROGRAMDATA_HEADER + maxDataLen(elfBytes)).toInt())
            sizes.add(PROGRAM_SIZE)
        }
        return sizes.distinct()
    }

    /**
     * The programdata size an `ExtendProgram` would have to pay rent for, or
     * null when this is not an upgrade, the account is unknown, or the ELF
     * still fits. One place, so [estimateDeploy] and [rentSizes] cannot
     * disagree about which sizes are priced.
     *
     * It is the size the *clamped* extension reaches ([extendBytes]), not
     * `header + elfBytes`: the loader will not grow an account by less than
     * [MAX_PERMITTED_DATA_INCREASE], so pricing the shortfall alone quoted a
     * figure for a transaction that could not be accepted (QA G-21).
     */
    private fun extendSize(elfBytes: Int, upgrade: Boolean, existing: Existing?): Int? {
        if (!upgrade || existing == null) return null
        val by = extendBytes(elfBytes, existing.dataLen)
        if (by == 0) return null
        return (PROGRAMDATA_HEADER + existing.dataLen + by).toInt()
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
