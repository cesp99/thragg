package to.eyed.seeker.code.solana.chain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The devnet proof-of-work faucet, mined from the phone.
 *
 * Ellipsis Labs keeps a program on devnet,
 * `PoWSNH2hEZogtCg1Zgm51FnkmJperzYDgPK4fvs8taL`, that pays 0.02 SOL to
 * whoever presents a transaction co-signed by a keypair whose Base58 public
 * key starts with `AAA`. Three A's is the difficulty-3 spec, four the
 * difficulty-4 one, and a key with four claims both. `requestAirdrop` on
 * the public endpoint is rationed by IP and hangs when it is dry; this is
 * rationed by nothing but CPU, so on devnet it is what the Airdrop button
 * and a deploy's funding step use. [KeyGrinder] finds the keys; this object
 * lays the claim out exactly as the program expects and [mine] runs the loop
 * that turns keys into a balance. The design is devnet-larper's
 * (github.com/cesp99/devnet-larper) cut down to what a phone on the public
 * RPC can do: no direct-to-leader sending, six claims to a transaction, a
 * dozen in flight, and the pacer between us and the endpoint.
 *
 * The claim, checked against a transaction the program accepted (devnet
 * `4kP4c2PX…MYsc`): six accounts in this order — payer (signer, writable),
 * the ground key (signer), the receipt PDA (writable), the spec PDA, the
 * spec's source PDA (writable), the system program — and eight bytes of
 * data, Anchor's `global:airdrop` discriminator. The PDAs are
 * `["spec", difficulty u8, amount u64 LE]`, `["source", spec]` and
 * `["receipt", key, difficulty u8]`, all off the program. In the same
 * instruction the payer fronts the receipt's rent (810,624 lamports, as
 * measured) and is paid the 0.02 SOL, so a claim nets about 0.019 SOL — and
 * a payer that holds nothing cannot make the first one, because the fee is
 * taken before anything runs. [BOOTSTRAP_LAMPORTS] is that floor; [fund]
 * asks the ordinary faucet, then the wallet, for a first few thousandths
 * when the key is under it, and mines from there.
 */
object PowFaucet {

    val PROGRAM_ID: Pubkey = Pubkey.of("PoWSNH2hEZogtCg1Zgm51FnkmJperzYDgPK4fvs8taL")

    /** `ComputeBudget111111111111111111111111111111`. */
    val COMPUTE_BUDGET: Pubkey = Pubkey.of("ComputeBudget111111111111111111111111111111")

    /** What one claim pays, and the amount in the spec PDA's seeds. */
    const val CLAIM_LAMPORTS = 20_000_000L

    /** What the payer fronts for each receipt account; measured, not derived. */
    const val RECEIPT_RENT = 810_624L

    /** The difficulties with a 0.02 SOL spec on devnet: `AAA` and `AAAA`. */
    val DIFFICULTIES: List<Int> = listOf(3, 4)

    /** Six is what fits: each key costs a signature, two account keys and an instruction. */
    const val CLAIMS_PER_TX = 6

    /** A claim used 44,907 units in the wild; six need about 270k, this leaves room. */
    const val COMPUTE_UNITS = 320_000L

    /** The wire limit on a transaction; a message over it is not sent, it is refused. */
    const val PACKET_LIMIT = 1232

    /** Fee and one receipt's rent, rounded up: what the first claim needs in the payer. */
    const val BOOTSTRAP_LAMPORTS = 2_000_000L

    /** What the wallet is asked for to start a dry key: enough for many first claims, small enough to give. */
    const val BOOTSTRAP_ASK = 50_000_000L

    /** What the ordinary faucet is asked for to start a dry key; the usual whole SOL, if it gives at all. */
    const val BOOTSTRAP_AIRDROP = 1_000_000_000L

    /** Anchor's `sha256("global:airdrop")[..8]`. */
    val DISCRIMINATOR: ByteArray =
        MessageDigest.getInstance("SHA-256").digest("global:airdrop".toByteArray(Charsets.US_ASCII)).copyOf(8)

    /** One difficulty's spec PDA and the source PDA it pays from. */
    class Spec(val difficulty: Int, val address: Pubkey, val source: Pubkey)

    /** The two specs, derived once. */
    val specs: List<Spec> by lazy { DIFFICULTIES.map(::spec) }

    fun spec(difficulty: Int): Spec {
        val amount = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(CLAIM_LAMPORTS).array()
        val (address, _) = Pda.findProgramAddress(
            listOf("spec".toByteArray(Charsets.US_ASCII), byteArrayOf(difficulty.toByte()), amount),
            PROGRAM_ID,
        )
        val (source, _) = Pda.findProgramAddress(listOf("source".toByteArray(Charsets.US_ASCII), address.bytes), PROGRAM_ID)
        return Spec(difficulty, address, source)
    }

    /** The receipt the program creates for [key] at [difficulty]; its existence is what makes a key single-use. */
    fun receipt(key: Pubkey, difficulty: Int): Pubkey =
        Pda.findProgramAddress(
            listOf("receipt".toByteArray(Charsets.US_ASCII), key.bytes, byteArrayOf(difficulty.toByte())),
            PROGRAM_ID,
        ).first

    /** One `airdrop` instruction: [payer] is paid, [key] proves the work. */
    fun claim(payer: Pubkey, key: Pubkey, spec: Spec): Instruction = Instruction(
        programId = PROGRAM_ID,
        accounts = listOf(
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(key, isSigner = true, isWritable = false),
            AccountMeta(receipt(key, spec.difficulty), isSigner = false, isWritable = true),
            AccountMeta(spec.address, isSigner = false, isWritable = false),
            AccountMeta(spec.source, isSigner = false, isWritable = true),
            AccountMeta(Loader.SYSTEM_PROGRAM, isSigner = false, isWritable = false),
        ),
        data = DISCRIMINATOR,
    )

    /** `ComputeBudgetInstruction::SetComputeUnitLimit`: tag 2, u32 LE. */
    fun setComputeUnitLimit(units: Long): Instruction = Instruction(
        programId = COMPUTE_BUDGET,
        accounts = emptyList(),
        data = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(2).putInt(units.toInt()).array(),
    )

    /** How many A's [key]'s Base58 starts with. */
    fun leadingAs(key: Pubkey): Int = key.base58.takeWhile { it == 'A' }.length

    /** Every claim [keys] can make up to [maxDifficulty], in key order. */
    fun claims(payer: Pubkey, keys: List<Pubkey>, maxDifficulty: Int = DIFFICULTIES.max()): List<Instruction> =
        keys.flatMap { key ->
            val prefix = leadingAs(key)
            specs.filter { it.difficulty <= prefix && it.difficulty <= maxDifficulty }.map { claim(payer, key, it) }
        }

    /**
     * The message for one batch: the compute budget, then a claim per key
     * per spec. A four-A key's second claim costs two more account keys and
     * an instruction, which pushes six keys over the packet; when it does,
     * the batch is compiled again with three-A claims only. Returns the
     * message and how many claims it holds.
     */
    fun message(payer: Pubkey, keys: List<Pubkey>, blockhash: String): Pair<Message, Int> {
        for (maxDifficulty in DIFFICULTIES.sortedDescending()) {
            val claims = claims(payer, keys, maxDifficulty)
            val message = Message.compile(payer, listOf(setComputeUnitLimit(COMPUTE_UNITS)) + claims, blockhash)
            if (Transaction.unsigned(message).serialize().size <= PACKET_LIMIT) return message to claims.size
        }
        throw ChainException("${keys.size} claims do not fit in one transaction")
    }

    // ---- the AAA window ----------------------------------------------------

    /**
     * The 32-byte values that Base58-encode with `AAA` are one contiguous
     * window: from `AAA` followed by forty-one `1`s (the digit for zero) up
     * to, not including, `AAB` and the same. Both ends decode to 32 bytes
     * with a leading 0x88, so the test is a byte compare that usually
     * decides on the first byte, and the grinder can run it on every
     * candidate without spelling any of them.
     */
    private val WINDOW_LO: ByteArray = bound("AAA")
    private val WINDOW_HI: ByteArray = bound("AAB")

    fun hasAaaPrefix(encoded: ByteArray): Boolean =
        encoded.size == Pubkey.SIZE && compareUnsigned(encoded, WINDOW_LO) >= 0 && compareUnsigned(encoded, WINDOW_HI) < 0

    private fun bound(prefix: String): ByteArray {
        val decoded = Base58.decode(prefix + "1".repeat(BASE58_PUBKEY_LENGTH - prefix.length))
        require(decoded.size <= Pubkey.SIZE) { "$prefix… decodes to ${decoded.size} bytes" }
        return ByteArray(Pubkey.SIZE).also { decoded.copyInto(it, Pubkey.SIZE - decoded.size) }
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until Pubkey.SIZE) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    /** A 32-byte key at its longest: the window is defined over 44-character spellings. */
    private const val BASE58_PUBKEY_LENGTH = 44

    // ---- mining -------------------------------------------------------------

    /** A snapshot for a progress row, every couple of seconds while [mine] runs. */
    class Progress(
        /** What the payer is believed to hold: the last balance read plus what confirmed since. */
        val balance: Long,
        val target: Long,
        /** Gross payouts confirmed this session. */
        val mined: Long,
        val elapsedMs: Long,
        val inFlight: Int,
        val keysFound: Long,
    ) {
        val lamportsPerMinute: Long
            get() = if (elapsedMs <= 0L) 0L else mined * 60_000L / elapsedMs

        /** `Mined 0.46 SOL · 3.2 SOL/min · holds 1.1 of 3.4 SOL · 4 in flight`. */
        fun describe(): String {
            // One decimal: a rate is a rough number, and four places of it jitter.
            val rate = if (elapsedMs < 5_000L) "" else " · %.1f SOL/min".format(java.util.Locale.ROOT, lamportsPerMinute / 1e9)
            val flight = if (inFlight == 0) "" else " · $inFlight in flight"
            return "Mined ${Loader.lamportsToSol(mined)}$rate · holds ${Loader.lamportsToSol(balance)} of ${Loader.lamportsToSol(target)}$flight"
        }
    }

    /** [mine] found the payer under [BOOTSTRAP_LAMPORTS]: it cannot pay for its first claim. */
    class NeedsBootstrap(val balance: Long) : ChainException(
        "The deploy key holds ${Loader.lamportsToSol(balance)}, under the ${Loader.lamportsToSol(BOOTSTRAP_LAMPORTS)} a first claim needs up front"
    )

    /**
     * Mine until [payer] holds [target] lamports, and return what it holds.
     * Throws [NeedsBootstrap] when it cannot start, [ChainException] when
     * the endpoint refuses claims repeatedly or nothing lands for minutes,
     * and lets cancellation through with whatever landed already in the key.
     */
    suspend fun mine(
        rpc: Rpc,
        pacer: RpcPacer,
        payer: Keypair,
        target: Long,
        onProgress: (Progress) -> Unit = {},
    ): Long {
        val balance = pacer.run { rpc.getBalance(payer.publicKey.base58) }
        if (balance >= target) return balance
        if (balance < BOOTSTRAP_LAMPORTS) throw NeedsBootstrap(balance)
        val grinder = KeyGrinder()
        grinder.start()
        try {
            return Miner(rpc, pacer, payer, target, grinder, onProgress).run(balance)
        } finally {
            grinder.stop()
        }
    }

    /**
     * [mine], with the bootstrap handled: a payer under the floor is offered
     * the ordinary faucet once, then [walletTransfer] for [BOOTSTRAP_ASK]
     * when there is one, and told what to do by hand when neither gives.
     * [onLine] narrates those steps; [onProgress] is the miner's.
     */
    suspend fun fund(
        rpc: Rpc,
        pacer: RpcPacer,
        payer: Keypair,
        target: Long,
        walletTransfer: (suspend (lamports: Long) -> Unit)?,
        onLine: (String) -> Unit,
        onProgress: (Progress) -> Unit = {},
    ): Long {
        try {
            return mine(rpc, pacer, payer, target, onProgress)
        } catch (e: NeedsBootstrap) {
            bootstrap(rpc, pacer, payer, e.balance, walletTransfer, onLine)
        }
        return mine(rpc, pacer, payer, target, onProgress)
    }

    private suspend fun bootstrap(
        rpc: Rpc,
        pacer: RpcPacer,
        payer: Keypair,
        balance: Long,
        walletTransfer: (suspend (lamports: Long) -> Unit)?,
        onLine: (String) -> Unit,
    ) {
        val address = payer.publicKey.base58
        val floor = Loader.lamportsToSol(BOOTSTRAP_LAMPORTS)
        onLine(
            "Deploy key ${Base58.short(address)} holds ${Loader.lamportsToSol(balance)} — a first claim needs about $floor " +
                "up front for its fee and receipt; asking the devnet faucet once"
        )
        val faucetNote: String
        try {
            // One attempt, short fuse: this faucet hangs rather than refuses
            // when it is dry, and a minute of waiting on it is a minute of
            // not mining.
            val signature = pacer.run(retry = false) { rpc.requestAirdrop(address, BOOTSTRAP_AIRDROP) }
            pacer.run(retry = false) {
                rpc.confirm(signature, rpc.getBlockHeight() + BLOCKHASH_LIFETIME, timeoutMs = BOOTSTRAP_CONFIRM_MS)
            }
            onLine("The faucet gave ${Loader.lamportsToSol(BOOTSTRAP_AIRDROP)}; mining from here")
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            faucetNote = ChainSigning.readable(e)
            onLine("Faucet: $faucetNote")
        }
        var walletNote = ""
        if (walletTransfer != null) {
            onLine("Asking Seed Vault for ${Loader.lamportsToSol(BOOTSTRAP_ASK)} to start the miner")
            try {
                walletTransfer(BOOTSTRAP_ASK)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = ChainSigning.readable(e)
                onLine("The wallet transfer failed: $why")
                walletNote = " Seed Vault: $why."
            }
        }
        throw ChainException(
            "The deploy key $address holds ${Loader.lamportsToSol(balance)} on devnet, and mining needs about $floor " +
                "up front for the first claim's fee and receipt. The faucet said: $faucetNote.$walletNote " +
                "Send a little devnet SOL to $address once — faucet.solana.com, or any devnet wallet — and after that it mines its own."
        )
    }

    /**
     * The loop. One coroutine on IO owns every piece of state; the grinder's
     * threads only ever touch the queue. Each tick: settle what the endpoint
     * says about the transactions in flight, re-read the balance when it is
     * stale or when the estimate says the target is met, send while there is
     * room in flight and the estimate is still short, report, sleep.
     *
     * Accounting is an estimate between balance reads — the last read plus
     * the net of every claim confirmed since — because a read is a request
     * and requests are the scarce thing here. The estimate decides when to
     * stop *sending*; only a real read with nothing in flight decides when
     * to stop *mining*, so a dropped transaction is made up for and an
     * overshoot is at most one batch.
     */
    private class Miner(
        private val rpc: Rpc,
        private val pacer: RpcPacer,
        private val payer: Keypair,
        private val target: Long,
        private val grinder: KeyGrinder,
        private val onProgress: (Progress) -> Unit,
    ) {
        private class Sent(val signature: String, val keys: List<GroundKey>, val claims: Int, val lastValidBlockHeight: Long) {
            /** What landing it adds to the payer: payouts less receipts less the fee for every signer. */
            val net: Long
                get() = claims * (CLAIM_LAMPORTS - RECEIPT_RENT) - Loader.LAMPORTS_PER_SIGNATURE * (1 + keys.size)
        }

        private val inFlight = ArrayList<Sent>()

        /** Keys with a claim to make: re-signs first, then whatever the grinder found. */
        private val ready = ArrayDeque<GroundKey>()

        private var blockhash: Blockhash? = null
        private var blockhashAt = 0L
        private var balance = 0L
        private var balanceAt = 0L

        /** Net of claims confirmed since [balanceAt]. */
        private var credited = 0L
        private var mined = 0L
        private var polledAt = 0L
        private var reportedAt = 0L
        private var sentAt = 0L
        private var landedAt = 0L
        private var refusals = 0
        private var lastRefusal: String? = null
        private val startedAt = System.currentTimeMillis()

        suspend fun run(initial: Long): Long {
            balance = initial
            balanceAt = startedAt
            landedAt = startedAt
            while (true) {
                coroutineContext.ensureActive()
                val now = System.currentTimeMillis()
                if (inFlight.isNotEmpty() && now - polledAt >= POLL_MS) settle()
                if (inFlight.isEmpty() && balance + credited >= target) {
                    readBalance()
                    if (balance >= target) return balance
                } else if (now - balanceAt >= BALANCE_EVERY_MS) {
                    readBalance()
                }
                if (now - landedAt >= STALL_MS) {
                    val why = lastRefusal?.let { " The last refusal: $it." }.orEmpty()
                    throw ChainException("Nothing landed for ${STALL_MS / 60_000} minutes on ${rpc.cluster.display}.$why")
                }
                send()
                if (now - reportedAt >= PROGRESS_MS) {
                    reportedAt = now
                    onProgress(progress())
                }
                delay(TICK_MS)
            }
        }

        private fun progress() = Progress(
            balance = balance + credited,
            target = target,
            mined = mined,
            elapsedMs = System.currentTimeMillis() - startedAt,
            inFlight = inFlight.size,
            keysFound = grinder.keysFound,
        )

        private suspend fun readBalance() {
            balance = pacer.run { rpc.getBalance(payer.publicKey.base58) }
            balanceAt = System.currentTimeMillis()
            credited = 0L
        }

        private suspend fun blockhash(): Blockhash {
            val now = System.currentTimeMillis()
            val held = blockhash
            if (held != null && now - blockhashAt < BLOCKHASH_REUSE_MS) return held
            return pacer.run { rpc.getLatestBlockhash() }.also {
                blockhash = it
                blockhashAt = now
            }
        }

        /** Ask once about everything in flight and sort it into landed, failed, expired, or still out. */
        private suspend fun settle() {
            polledAt = System.currentTimeMillis()
            val statuses = pacer.run { rpc.getSignatureStatuses(inFlight.map { it.signature }) }
            var height: Long? = null
            val still = ArrayList<Sent>(inFlight.size)
            for ((index, sent) in inFlight.withIndex()) {
                val status = statuses.getOrNull(index)
                when {
                    status == null -> {
                        // Never seen: still travelling, or gone with its blockhash.
                        val h = height ?: pacer.run { rpc.getBlockHeight() }.also { height = it }
                        if (h > sent.lastValidBlockHeight) ready.addAll(0, sent.keys) else still += sent
                    }
                    status.err != null -> refused(status.err)
                    status.confirmed -> {
                        credited += sent.net
                        mined += sent.claims * CLAIM_LAMPORTS
                        landedAt = System.currentTimeMillis()
                        refusals = 0
                    }
                    else -> still += sent
                }
            }
            inFlight.clear()
            inFlight.addAll(still)
        }

        /**
         * A refusal — preflight or on chain — drops its keys: the grinder
         * makes more faster than a retry would find out why. Several in a
         * row with nothing landing between them is the endpoint or the
         * program saying no to all of it, and the message says what it said.
         */
        private fun refused(why: String) {
            refusals++
            lastRefusal = why
            if (refusals >= MAX_REFUSALS) {
                throw ChainException("The faucet refused $refusals claims in a row on ${rpc.cluster.display}: $why")
            }
        }

        private suspend fun send() {
            while (inFlight.size < MAX_IN_FLIGHT && balance + credited + inFlight.sumOf { it.net } < target) {
                gather()
                if (ready.isEmpty()) return
                // A short batch is sent only once a full one has been a while coming.
                if (ready.size < CLAIMS_PER_TX && System.currentTimeMillis() - sentAt < PARTIAL_AFTER_MS) return
                val keys = List(minOf(CLAIMS_PER_TX, ready.size)) { ready.removeFirst() }
                val hash = blockhash()
                val (message, claims) = message(payer.publicKey, keys.map { it.publicKey }, hash.blockhash)
                val bytes = message.serialize()
                var tx = Transaction.unsigned(message).withSignature(payer.publicKey, payer.sign(bytes))
                for (key in keys) tx = tx.withSignature(key.publicKey, key.sign(bytes))
                val signature = try {
                    pacer.run { rpc.sendTransaction(tx) }
                } catch (e: RpcException) {
                    if (e.isBlockhashExpiry) {
                        blockhash = null
                        ready.addAll(0, keys)
                        continue
                    }
                    refused(e.message ?: "refused")
                    continue
                }
                inFlight += Sent(signature, keys, claims, hash.lastValidBlockHeight)
                sentAt = System.currentTimeMillis()
            }
        }

        /** Top [ready] up to a batch from the grinder, waiting a moment for it. */
        private fun gather() {
            val deadline = System.currentTimeMillis() + GATHER_MS
            while (ready.size < CLAIMS_PER_TX) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0L) return
                ready.addLast(grinder.take(wait) ?: return)
            }
        }
    }

    private const val TICK_MS = 250L
    private const val GATHER_MS = 300L
    private const val PARTIAL_AFTER_MS = 3_000L
    private const val POLL_MS = 4_000L
    private const val PROGRESS_MS = 2_000L
    private const val BALANCE_EVERY_MS = 30_000L
    private const val BLOCKHASH_REUSE_MS = 30_000L
    private const val BLOCKHASH_LIFETIME = 150L
    private const val BOOTSTRAP_CONFIRM_MS = 30_000L
    private const val STALL_MS = 3 * 60_000L
    private const val MAX_IN_FLIGHT = 12
    private const val MAX_REFUSALS = 4
}
