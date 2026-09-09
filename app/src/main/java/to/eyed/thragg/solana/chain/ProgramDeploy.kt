package to.eyed.thragg.solana.chain

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import to.eyed.thragg.solana.build.Deployer
import to.eyed.thragg.solana.build.ProgramTarget
import to.eyed.thragg.solana.build.ProjectLayout
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * `solana program deploy`, done by hand: the [Deployer] behind the Deploy
 * button, signing with the Seeker's Seed Vault where a signature has to be
 * the user's and with the local deploy key everywhere else.
 *
 * There is no `solana` CLI on the phone (Agave has no arm64 build), so the
 * seven steps the CLI performs are performed here, in Kotlin, over the
 * primitives in Loader.kt, Wire.kt and Rpc.kt. The order is the loader's and
 * the choices inside it are about prompts and money:
 *
 *  1. **Inspect** the program id. Fresh, upgrade, or one of the three ways
 *     an id is unusable — closed ids are the one that would otherwise cost a
 *     buffer's rent to discover. Then, for an upgrade the *wallet* has to
 *     sign, **ask Seed Vault to authorize before anything is spent**, and
 *     **adopt** the buffer an earlier attempt left, when its bytes are this
 *     artifact's: the two failure modes that cost the most are a nine-minute
 *     upload thrown away because the wallet said no at the end, and a retry
 *     that pays for the same upload twice.
 *  2. **Fund the deploy key.** Every buffer write is a signature, and Mobile
 *     Wallet Adapter prompts for every signing round, so a few hundred writes
 *     cannot be the wallet's. They are the deploy key's, and the deploy key
 *     is topped up once: by mining the proof-of-work faucet on devnet
 *     (PowFaucet.kt), from the faucet on testnet, from the wallet by one
 *     Transfer on mainnet. Rent figures come from the RPC, not
 *     the formula, because a cluster can change them.
 *  3. **Create the buffer**, and record it in OpenBuffers *before* the
 *     transaction is sent: a network drop or a killed process after this
 *     point strands the ELF's rent, and the record is what lets Settings
 *     offer it back.
 *  4. **Write the chunks**, forty per blockhash, paced under the public
 *     endpoint's limits, then confirmed in bulk; whatever did not land before
 *     its blockhash expired is re-signed and resent. A Write is idempotent,
 *     which is what makes that safe.
 *  5. **Deploy or upgrade.** A fresh deploy creates the 36-byte program
 *     account and deploys into it in one transaction (the loader does not
 *     create it), reserving the ELF's own length as `max_data_len`
 *     ([Loader.MAX_DATA_LEN_FACTOR] is 1, as the CLI's default is today); an
 *     upgrade that has outgrown its programdata extends it first, and pays
 *     the extension's rent, which the estimate now names. A fresh deploy and
 *     an upgrade owned by the deploy key are signed locally. An upgrade whose authority is the wallet hands the
 *     buffer to the wallet first, then asks it to sign the Upgrade — the
 *     wallet first, the deploy key after, our RPC sends.
 *  6. **Hand the upgrade authority to Seed Vault** when it is connected, so
 *     the program ends up owned by the user's wallet and not by a key that
 *     lives in this app's files. Not fatal when it fails: the program is
 *     deployed, and the log says who holds it.
 *  7. **Record and tidy**: DeployedPrograms, the buffer record removed, and
 *     on mainnet the deploy key's change swept back to the wallet.
 *
 * Every failure becomes a `Result.failure` whose message says what to do,
 * and any failure after step three also names the buffer and what it holds.
 * Cancellation is left alone — BuildRunner owns the job — except that the
 * buffer note is written to the log on the way out. All of it runs on
 * Dispatchers.IO; the only excursion to Main is the wallet prompt.
 */
object SeedVaultDeployer : Deployer {

    override val label: String = "Seed Vault"

    override suspend fun deploy(
        context: Context,
        project: ProjectLayout,
        program: ProgramTarget,
        onLine: (String) -> Unit,
    ): Result<String> {
        val app = context.applicationContext
        var session: DeploySession? = null
        return try {
            withContext(Dispatchers.IO) {
                val opened = DeploySession.open(app, project, program, onLine)
                session = opened
                Result.success(opened.run())
            }
        } catch (e: CancellationException) {
            session?.bufferNote()?.let { onLine("Cancelled · $it") }
            throw e
        } catch (e: Exception) {
            val base = ChainSigning.readable(e)
            val note = session?.bufferNote()
            Result.failure(ChainException(if (note == null) base else "$base · $note", e))
        }
    }
}

/**
 * One deploy, start to finish. Everything the steps share is a `val` fixed
 * by [open], so the wallet and the mode can be reasoned about as facts
 * rather than re-read mid-flight.
 */
private class DeploySession private constructor(
    private val app: Context,
    private val project: ProjectLayout,
    private val program: ProgramTarget,
    private val onLine: (String) -> Unit,
    private val cluster: Cluster,
    private val deployKey: Keypair,
    private val wallet: Pubkey?,
    private val programKeypair: Keypair,
    private val elf: ByteArray,
) {
    private val rpc = Rpc(cluster)
    private val pacer = RpcPacer()
    private val payer: Pubkey get() = deployKey.publicKey
    private val programId: Pubkey get() = programKeypair.publicKey
    private val chunks: List<Pair<Int, ByteArray>> by lazy { Loader.chunks(elf) }

    /**
     * The buffer this deploy is uploading into: set the moment the
     * create-buffer transaction is signed, or the moment an earlier attempt's
     * buffer is adopted ([adoptBuffer]). A [Pubkey] and not the [Keypair],
     * because the buffer's key signs exactly one transaction — its own
     * CreateAccount — and every later instruction is signed by the buffer's
     * *authority*. That is what makes a resume possible at all: the key that
     * was generated in memory nine minutes ago is not needed to finish the
     * job it started.
     */
    @Volatile
    private var bufferKey: Pubkey? = null

    /** Who may write to [bufferKey] and hand it on: the deploy key, or the wallet after step 5. */
    private var bufferAuthority: Pubkey? = null

    /** Chunks already on the adopted buffer, byte for byte; null when the buffer is this run's. */
    private var alreadyWritten: BooleanArray? = null

    @Volatile
    private var bufferDrained = false
    private var bufferRent = 0L

    /** The program account's rent, read from the RPC in [fund]; spent by the fresh deploy's CreateAccount. */
    private var programRent = 0L

    private sealed interface Mode {
        data object Fresh : Mode

        /** [grow] is how many bytes the programdata is short of the new ELF; zero when it fits. */
        data class Upgrade(
            val status: OnChainProgram.Deployed,
            val authority: Pubkey,
            val byWallet: Boolean,
            val grow: Int,
        ) : Mode
    }

    private class Sent(val index: Int, val signature: String, val lastValidBlockHeight: Long)

    suspend fun run(): String {
        onLine("Deploying ${program.moduleName} · ${elf.size} bytes · to ${cluster.display} as ${programId.base58}")
        onLine(
            if (wallet != null) {
                "Signer: Seed Vault ${short(wallet)} · buffer writes by the deploy key ${short(payer)}"
            } else {
                "Signer: deploy key ${short(payer)} (no wallet connected)"
            }
        )
        val mode = inspect()
        authorizeUpfront(mode)
        val resumed = adoptBuffer(mode)
        fund(mode, resumed)
        probeExtend(mode)
        createBuffer()
        writeChunks()
        val (signature, authority) = finalise(mode)
        DeployedPrograms.record(
            app,
            DeployedProgram(
                name = program.moduleName,
                programId = programId.base58,
                cluster = cluster,
                authority = authority.base58,
                deployedAt = System.currentTimeMillis(),
                signature = signature,
                projectRoot = project.root,
            ),
        )
        bufferKey?.let { OpenBuffers.remove(app, it.base58) }
        sweep()
        return "Program Id: ${programId.base58} · signature $signature · ${cluster.explorerAddress(programId.base58)}"
    }

    /**
     * The line a failure or a cancellation appends once a buffer exists and
     * has not been drained. It says the upload is not lost: the next deploy
     * of the same artifact adopts this buffer ([adoptBuffer]) instead of
     * paying its rent and its nine minutes again.
     */
    fun bufferNote(): String? {
        val open = bufferKey?.takeUnless { bufferDrained } ?: return null
        return "Buffer ${open.base58} holds ${sol(bufferRent)} — deploying this artifact again reuses it " +
            "rather than uploading afresh; Settings > Wallet can reclaim it instead"
    }

    // ---- 1. inspect ---------------------------------------------------------------

    private suspend fun inspect(): Mode {
        val id = programId.base58
        val keypairPath = "target/deploy/${program.moduleName}-keypair.json"
        return when (val status = pacer.run { ProgramStatus.inspect(rpc, id) }) {
            OnChainProgram.NotFound -> {
                onLine("Nothing at ${short(programId)} on ${cluster.display} yet · fresh deploy")
                Mode.Fresh
            }
            is OnChainProgram.Closed -> throw ChainException(
                "Program $id was closed on ${cluster.display}; that id can never be reused. " +
                    "Delete $keypairPath and rebuild to get a new id."
            )
            is OnChainProgram.NotAProgram -> throw ChainException(
                "$id on ${cluster.display} is not a program — the account is owned by ${status.owner}. " +
                    "Delete $keypairPath and rebuild to get a new id."
            )
            is OnChainProgram.Deployed -> {
                val current = status.authority ?: throw ChainException(
                    "${short(programId)} on ${cluster.display} is immutable — it has no upgrade authority, so it cannot be upgraded"
                )
                val authority: Pubkey = when {
                    current == payer.base58 -> payer
                    wallet != null && current == wallet.base58 -> wallet
                    else -> throw ChainException(
                        "${short(programId)} on ${cluster.display} can only be upgraded by ${Base58.short(current)}, " +
                            "which is neither Seed Vault nor this phone's deploy key. Connect that wallet, or deploy under a new id."
                    )
                }
                val byWallet = authority != payer
                onLine(
                    "${short(programId)} is deployed on ${cluster.display} (slot ${status.slot}) · upgrading · " +
                        "authority is ${if (byWallet) "Seed Vault" else "the deploy key"}"
                )
                // Loader.extendBytes, never the shortfall itself: the loader
                // refuses any extension under MAX_PERMITTED_DATA_INCREASE, and
                // it refuses it after the whole upload (QA G-21).
                val grow = Loader.extendBytes(elf.size, status.dataLen)
                if (grow > 0) {
                    val short = elf.size - status.dataLen
                    onLine(
                        "Its programdata has room for ${status.dataLen} bytes and the new artifact is ${elf.size} — " +
                            "it will be extended by $grow bytes first" +
                            if (grow > short) {
                                " (the loader's smallest extension is ${Loader.MAX_PERMITTED_DATA_INCREASE} bytes, " +
                                    "not the $short it is short by)"
                            } else {
                                ""
                            }
                    )
                }
                Mode.Upgrade(status, authority, byWallet, grow)
            }
        }
    }

    // ---- 1b. authorize, before anything is spent ------------------------------------

    /**
     * A wallet-authority upgrade asks Seed Vault to authorize **now**, before
     * the buffer and its nine minutes of writes.
     *
     * Measured twice on the Seeker 2026-09-08: both upgrades wrote all 186
     * chunks (8m38s and 9m20s) and only then said "Asking Seed Vault to sign
     * the upgrade" — one failed because the dApp Store was updating the
     * wallet app, the other because the prompt was not answered in time, and
     * each left a 0.9532 SOL buffer behind (QA G-20). The authorization is
     * knowable in seconds and it is the one precondition the upload cannot
     * supply, so it is checked first. With a live auth token this is silent
     * (MWA re-authorizes without a prompt); without one it raises the connect
     * sheet here, where nothing has been spent yet.
     *
     * A deploy or an upgrade the deploy key signs needs no wallet, and asks
     * for none.
     */
    private suspend fun authorizeUpfront(mode: Mode) {
        val upgrade = mode as? Mode.Upgrade ?: return
        if (!upgrade.byWallet) return
        val expected = upgrade.authority
        onLine(
            "Checking Seed Vault ${short(expected)} still authorizes Thragg on ${cluster.display} " +
                "before uploading · keep Thragg on screen if it asks"
        )
        val answered = withContext(Dispatchers.Main) { SeedVaultWallet.connect(app, cluster) }
        val address = answered.getOrElse { why ->
            throw ChainException(
                "${ChainSigning.readable(why)} — only Seed Vault ${short(expected)} can sign this upgrade, " +
                    "so nothing was uploaded and nothing was spent. Reconnect it in Settings, under Wallet, and deploy again.",
                why,
            )
        }
        if (address != expected.base58) {
            throw ChainException(
                "Seed Vault is connected as ${Base58.short(address)}, but ${short(programId)} on ${cluster.display} " +
                    "can only be upgraded by ${short(expected)} — nothing was uploaded. " +
                    "Switch the wallet's account, or deploy under a new id."
            )
        }
        onLine("Seed Vault ${short(expected)} authorizes Thragg on ${cluster.display}")
    }

    // ---- 1c. adopt an earlier attempt's buffer ---------------------------------------

    /**
     * The buffer an earlier attempt left for this program, when its bytes are
     * this artifact's — the scan is [BufferAdoption.scan], shared with the
     * Deploy sheet; this is the part that takes it over and says so.
     */
    private suspend fun adoptBuffer(mode: Mode): AdoptableBuffer? {
        val walletAuthority = (mode as? Mode.Upgrade)?.takeIf { it.byWallet }?.authority
        val resumable = BufferAdoption.scan(
            app = app,
            rpc = rpc,
            pacer = pacer,
            cluster = cluster,
            programId = programId.base58,
            elfSize = elf.size,
            chunks = chunks,
            payer = payer,
            walletAuthority = walletAuthority,
            forget = true,
        ) ?: return null
        bufferKey = resumable.key
        bufferAuthority = resumable.authority
        alreadyWritten = resumable.written
        bufferRent = resumable.lamports
        onLine(
            when {
                resumable.whole ->
                    "Buffer ${short(resumable.key)} from an earlier attempt already holds this artifact — " +
                        "reusing it, ${sol(resumable.lamports)} of rent and ${chunks.size} writes saved"
                resumable.done > 0 ->
                    "Buffer ${short(resumable.key)} from an earlier attempt holds ${resumable.done} of ${chunks.size} " +
                        "chunks of this artifact — reusing it and writing the rest"
                else ->
                    "Buffer ${short(resumable.key)} from an earlier attempt is the right size and still this phone's to " +
                        "write — reusing it, ${sol(resumable.lamports)} of rent saved"
            }
        )
        return resumable
    }

    // ---- 2. fund ------------------------------------------------------------------

    private suspend fun fund(mode: Mode, resumed: AdoptableBuffer?) {
        val upgrade = mode is Mode.Upgrade
        // The account the cluster already has, so an upgrade's ExtendProgram
        // is priced here exactly as the Deploy sheet prices it: one
        // [Loader.estimateDeploy] for both, quoted over the cluster's own
        // rent for the sizes it names (QA G-21 — this used to be inline
        // arithmetic here and a zero on the sheet).
        val existing = (mode as? Mode.Upgrade)?.let { Loader.Existing(it.status.dataLen, it.status.reclaimable) }
        val quotes = HashMap<Int, Long>()
        for (size in Loader.rentSizes(elf.size, upgrade, existing)) quotes[size] = rent(size)
        val estimate = Loader.estimateDeploy(elf.size, upgrade, { quotes.getValue(it) }, existing)
        bufferRent = resumed?.lamports ?: estimate.bufferRent
        programRent = estimate.programRent
        // What an earlier attempt already paid for is not asked for twice:
        // the buffer's rent is on chain, and so are the writes that landed.
        val outstanding = Loader.outstanding(
            estimate,
            bufferAlreadyPaid = resumed != null,
            writesAlreadyLanded = resumed?.done ?: 0,
        )
        val required = Loader.withMargin(outstanding)
        var balance = balanceOf(payer)
        if (balance >= required) {
            onLine("Deploy key ${short(payer)} holds ${sol(balance)} · needs about ${sol(required)}")
            return
        }
        val address = payer.base58
        if (cluster.hasPowFaucet) {
            mineOnDevnet(required, balance)
            return
        }
        if (cluster.hasFaucet) {
            val lastError = AtomicReference<String?>(null)
            for (attempt in 1..AIRDROP_TRIES) {
                val shortfall = required - balance
                val amount = airdropAmount(shortfall)
                onLine(
                    "Deploy key ${short(payer)} holds ${sol(balance)}, needs ${sol(required)} — requesting " +
                        "${sol(amount)} from the ${cluster.display} faucet" +
                        if (attempt > 1) " (try $attempt of $AIRDROP_TRIES)" else ""
                )
                try {
                    // One attempt each and a short wait: this loop is the
                    // retry, and a faucet that is dry hangs rather than says so.
                    val signature = pacer.run(retry = false) { rpc.requestAirdrop(address, amount) }
                    pacer.run(retry = false) {
                        rpc.confirm(signature, rpc.getBlockHeight() + BLOCKHASH_LIFETIME, timeoutMs = AIRDROP_CONFIRM_MS)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val why = ChainSigning.readable(e)
                    lastError.set(why)
                    onLine("Faucet: $why")
                    delay(AIRDROP_RETRY_MS)
                }
                balance = balanceOf(payer)
                if (balance >= required) {
                    onLine("Deploy key now holds ${sol(balance)}")
                    return
                }
            }
            var walletNote = ""
            if (wallet != null) {
                onLine("The faucet did not cover it — asking Seed Vault instead")
                // A wallet that declines, or holds too little, is a line in
                // the log and a clause below; the failure that ends the
                // deploy is still the one that names the address and the
                // shortfall, because sending SOL there by hand is the way
                // out either way.
                try {
                    walletTransfer(wallet, required - balance)
                    balance = balanceOf(payer)
                    if (balance >= required) return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val why = ChainSigning.readable(e)
                    onLine("The wallet transfer failed: $why")
                    walletNote = " The wallet transfer failed: $why."
                }
            }
            val faucetNote = lastError.get()?.let { " The faucet said: $it." }.orEmpty()
            throw ChainException(
                "Deploy key $address holds ${sol(balance)} and needs ${sol(required)} on ${cluster.display}.$faucetNote$walletNote " +
                    "Send ${sol(required - balance)} to $address and deploy again."
            )
        } else {
            val from = wallet ?: throw ChainException(
                "Connect Seed Vault in Settings > Wallet — on ${cluster.display} the deploy key " +
                    "${short(payer)} is funded from the wallet, and it holds ${sol(balance)} of the ${sol(required)} needed"
            )
            onLine("Deploy key ${short(payer)} holds ${sol(balance)}, needs ${sol(required)} — asking Seed Vault for the difference")
            walletTransfer(from, required - balance)
            balance = balanceOf(payer)
            if (balance < required) {
                throw ChainException(
                    "Deploy key $address holds ${sol(balance)} after the transfer and needs ${sol(required)}; deploy again to top it up"
                )
            }
        }
    }

    /**
     * Devnet: the deploy key mines what it is short from the proof-of-work
     * faucet (PowFaucet.kt), a line to the log every [MINE_LINE_MS]. A key
     * too dry for its first claim is offered the ordinary faucet, then the
     * wallet for a little, inside [PowFaucet.fund]; the failure that ends
     * the deploy names the address and the shortfall, as it always did,
     * because sending SOL there by hand is the way out either way.
     */
    private suspend fun mineOnDevnet(required: Long, balance: Long) {
        onLine(
            "Deploy key ${short(payer)} holds ${sol(balance)}, needs ${sol(required)} — " +
                "mining the difference from the ${cluster.display} proof-of-work faucet"
        )
        val from = wallet
        suspend fun fromWallet(lamports: Long) {
            walletTransfer(from ?: return, lamports)
        }
        var lastLine = 0L
        try {
            val now = PowFaucet.fund(
                rpc, pacer, deployKey, required,
                walletTransfer = if (from == null) null else ::fromWallet,
                onLine = onLine,
                onProgress = { progress ->
                    val at = System.currentTimeMillis()
                    if (at - lastLine >= MINE_LINE_MS) {
                        lastLine = at
                        onLine(progress.describe())
                    }
                },
            )
            onLine("Deploy key now holds ${sol(now)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: PowFaucet.FaucetEmpty) {
            // The faucet's source PDA is empty, so mining can only lose the
            // deploy key money (PowFaucet.FaucetEmpty). The wallet is the
            // remedy on devnet too, and it is one prompt: ask for the whole
            // gap rather than the miner's bootstrap.
            askWallet(from, required, balance, e.message ?: PowFaucet.EMPTY, e)
        } catch (e: Exception) {
            // Anything else the miner ended on — the endpoint refusing claims,
            // nothing landing for minutes — leaves the key wherever it got to,
            // and the wallet is still the way to close the gap. Devnet used to
            // be the one cluster whose funding step had no wallet fallback at
            // all (QA B-07); it now has the same one testnet does.
            askWallet(from, required, balance, "Mining did not finish: ${ChainSigning.readable(e)}", e)
        }
    }

    /**
     * Close what is left of the gap from the wallet, or fail saying what the
     * miner said and what to send by hand. Returns quietly when the key is
     * already covered — a mining session that ended badly may still have
     * landed everything that was needed.
     */
    private suspend fun askWallet(from: Pubkey?, required: Long, fallback: Long, why: String, cause: Exception) {
        // A balance read that fails falls back to the last one known rather
        // than to zero: asking the wallet for the whole requirement when the
        // key may already hold most of it is real money on the wrong cluster.
        val held = runCatching { balanceOf(payer) }.getOrDefault(fallback)
        val gap = (required - held).coerceAtLeast(0L)
        if (gap == 0L) {
            onLine("$why · the deploy key already holds ${sol(held)}")
            return
        }
        var walletNote = ""
        if (from != null) {
            onLine("$why Asking Seed Vault for the difference instead")
            try {
                walletTransfer(from, gap)
                val now = balanceOf(payer)
                onLine("Deploy key now holds ${sol(now)}")
                if (now >= required) return
                walletNote = " Seed Vault sent less than the gap."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val note = ChainSigning.readable(e)
                onLine("The wallet transfer failed: $note")
                walletNote = " Seed Vault: $note."
            }
        }
        throw ChainException(
            "Deploy key ${payer.base58} holds ${sol(held)} and needs ${sol(required)} on ${cluster.display}. " +
                "$why$walletNote " +
                (
                    if (from == null) {
                        "Connect Seed Vault in Settings, under Wallet, or send "
                    } else {
                        "Send "
                    }
                    ) +
                "${sol(gap)} to ${payer.base58} and deploy again.",
            cause,
        )
    }

    /**
     * One Transfer from the wallet to the deploy key, signed by the wallet,
     * sent by us — [WalletTopUp.transfer], which is the app's only one. The
     * balance check, the fee reserve and the "keep Thragg on screen" line
     * live there now, so the Wallet sheet's Top up, the faucet's bootstrap
     * and this cannot drift apart.
     */
    private suspend fun walletTransfer(from: Pubkey, lamports: Long) {
        val signature = WalletTopUp.transfer(app, cluster, rpc, pacer, from, payer, lamports, onLine)
        onLine("Seed Vault sent ${sol(lamports)} · ${Base58.short(signature)}")
    }

    /** Whole SOL, at least one, at most [MAX_AIRDROP] — what the faucet is willing to hand out at once. */
    private fun airdropAmount(shortfall: Long): Long {
        val whole = ((shortfall + LAMPORTS_PER_SOL - 1) / LAMPORTS_PER_SOL) * LAMPORTS_PER_SOL
        return whole.coerceIn(LAMPORTS_PER_SOL, MAX_AIRDROP)
    }

    // ---- 3. buffer ----------------------------------------------------------------

    private suspend fun createBuffer() {
        coroutineContext.ensureActive()
        // An adopted buffer is already on chain, already paid for, and its
        // record is already in OpenBuffers (adoptBuffer).
        if (bufferKey != null) return
        val keypair = Keypair.generate()
        val space = (Loader.BUFFER_HEADER + elf.size).toLong()
        val blockhash = pacer.run { rpc.getLatestBlockhash() }
        val message = Message.compile(
            payer,
            listOf(
                Loader.createAccount(payer, keypair.publicKey, bufferRent, space, Loader.PROGRAM_ID),
                Loader.initializeBuffer(keypair.publicKey, payer),
            ),
            blockhash.blockhash,
        )
        val bytes = message.serialize()
        val tx = Transaction.unsigned(message)
            .withSignature(payer, deployKey.sign(bytes))
            .withSignature(keypair.publicKey, keypair.sign(bytes))
        // Recorded before the send: from here on the rent is on chain, or
        // may be, and the record is the only way back to it — for Reclaim,
        // and for the next attempt, which continues this upload rather than
        // starting a second one (adoptBuffer).
        bufferKey = keypair.publicKey
        bufferAuthority = payer
        OpenBuffers.add(
            app,
            OpenBuffer(
                address = keypair.publicKey.base58,
                cluster = cluster,
                authority = payer.base58,
                createdAt = System.currentTimeMillis(),
                programId = programId.base58,
            ),
        )
        val signature = pacer.run { rpc.sendTransaction(tx) }
        pacer.run { rpc.confirm(signature, blockhash.lastValidBlockHeight) }
        onLine("Buffer ${short(keypair.publicKey)} created · ${sol(bufferRent)} held until the deploy lands")
    }

    // ---- 4. writes ----------------------------------------------------------------

    private suspend fun writeChunks() {
        val bufferKey = checkNotNull(this.bufferKey)
        val total = chunks.size
        val confirmed = alreadyWritten?.copyOf() ?: BooleanArray(total)
        val lastError = AtomicReference<String?>(null)
        var pending: List<Int> = chunks.indices.filter { !confirmed[it] }
        if (pending.isEmpty()) {
            onLine("All $total chunks are on the buffer already — nothing to upload")
            return
        }
        check(bufferAuthority == payer) {
            "The buffer's authority is ${bufferAuthority?.base58} — the deploy key cannot write to it"
        }
        onLine(
            if (pending.size == total) {
                "Writing $total chunks of ${Loader.writeChunkSize()} bytes"
            } else {
                "Writing the ${pending.size} chunks of $total this buffer is missing"
            }
        )
        for (round in 1..WRITE_ROUNDS) {
            coroutineContext.ensureActive()
            if (round > 1) onLine("Resending ${pending.size} chunks (round $round of $WRITE_ROUNDS)")
            val sent = ArrayList<Sent>(pending.size)
            val batches = pending.chunked(WRITE_BATCH)
            for (batch in batches) {
                coroutineContext.ensureActive()
                val blockhash = pacer.run { rpc.getLatestBlockhash() }
                val results = coroutineScope {
                    batch.map { index ->
                        async {
                            val (offset, bytes) = chunks[index]
                            val message = Message.compile(
                                payer,
                                listOf(Loader.write(bufferKey, payer, offset, bytes)),
                                blockhash.blockhash,
                            )
                            val tx = Transaction.unsigned(message).withSignature(payer, deployKey.sign(message.serialize()))
                            try {
                                Sent(index, pacer.run { rpc.sendTransaction(tx) }, blockhash.lastValidBlockHeight)
                            } catch (e: RpcException) {
                                lastError.set(e.message)
                                null
                            } catch (e: IOException) {
                                lastError.set(ChainSigning.readable(e))
                                null
                            }
                        }
                    }.awaitAll()
                }
                sent.addAll(results.filterNotNull())
                // The endpoint's rate limit makes signing and sending a batch
                // of forty about fifty seconds, so a 186-chunk artifact spent
                // four and a half minutes here with nothing to show for it —
                // the "Writing n/total" lines below only start once the first
                // statuses come back (QA P-17). One line per batch is one a
                // minute, which is what the log island wants.
                if (batches.size > 1) onLine("Signed and sent ${sent.size}/${pending.size} chunks")
            }
            if (sent.isEmpty()) {
                throw ChainException("Could not send any of the ${pending.size} chunks" + lastError.get()?.let { " · $it" }.orEmpty())
            }
            awaitWrites(sent, confirmed, total)
            pending = pending.filter { !confirmed[it] }
            if (pending.isEmpty()) {
                onLine("Wrote $total/$total chunks")
                return
            }
        }
        throw ChainException(
            "${pending.size} of $total chunks did not land after $WRITE_ROUNDS rounds" + lastError.get()?.let { " · $it" }.orEmpty()
        )
    }

    /**
     * Poll statuses for [sent] until each is confirmed or past its
     * blockhash; the latter are left unconfirmed for the next round. A Write
     * that LANDED AND FAILED is neither: the loader refused it — the buffer's
     * authority changed, the offset is past its end, the account is gone —
     * and the same bytes over a fresh blockhash would be refused the same
     * way five rounds running, so it ends the deploy here with the loader's
     * reason.
     */
    private suspend fun awaitWrites(sent: List<Sent>, confirmed: BooleanArray, total: Int) {
        var remaining = sent
        var reported = confirmed.count { it }
        var polls = 0
        val deadline = System.currentTimeMillis() + WRITE_WAIT_MS
        while (remaining.isNotEmpty()) {
            coroutineContext.ensureActive()
            delay(STATUS_POLL_MS)
            val statuses = remaining.chunked(STATUS_BATCH).flatMap { group ->
                pacer.run { rpc.getSignatureStatuses(group.map { it.signature }) }
            }
            val still = ArrayList<Sent>(remaining.size)
            remaining.forEachIndexed { i, s ->
                val status = statuses.getOrNull(i)
                when {
                    status == null -> still.add(s)
                    status.err != null -> throw ChainException("chunk ${s.index + 1} failed: ${status.err}")
                    status.confirmed -> confirmed[s.index] = true
                    else -> still.add(s)
                }
            }
            remaining = still
            val done = confirmed.count { it }
            if (done - reported >= PROGRESS_EVERY || (remaining.isEmpty() && done != reported)) {
                onLine("Writing $done/$total chunks")
                reported = done
            }
            if (remaining.isEmpty()) return
            polls++
            if (polls % 2 == 0) {
                val height = pacer.run { rpc.getBlockHeight() }
                remaining = remaining.filter { it.lastValidBlockHeight >= height }
            }
            if (System.currentTimeMillis() >= deadline) return
        }
    }

    // ---- 5. and 6. finalise --------------------------------------------------------

    /** The deploy or upgrade signature, and who holds the upgrade authority afterwards. */
    private suspend fun finalise(mode: Mode): Pair<String, Pubkey> {
        coroutineContext.ensureActive()
        val bufferKey = checkNotNull(this.bufferKey)
        return when (mode) {
            Mode.Fresh -> {
                val programData = Pda.programDataAddress(programId)
                val maxDataLen = Loader.maxDataLen(elf.size)
                onLine("Deploying · programdata ${short(programData)} with room for $maxDataLen bytes")
                // CreateAccount for the program, then the deploy, one
                // transaction: the loader expects the program account to be
                // there already (Loader.deployProgram).
                val signature = sendLocal(
                    Loader.deployProgram(payer, programData, programId, bufferKey, payer, programRent, maxDataLen),
                    signers = listOf(deployKey, programKeypair),
                )
                drained()
                onLine("Deployed · ${cluster.explorerTx(signature)}")
                signature to handOver(programData)
            }
            is Mode.Upgrade -> {
                val programData = Pubkey.of(mode.status.programData)
                if (mode.grow > 0) extend(programData, mode.grow)
                if (!mode.byWallet) {
                    onLine("Upgrading")
                    val signature = sendLocal(
                        listOf(Loader.upgrade(programData, programId, bufferKey, payer, payer)),
                        signers = listOf(deployKey),
                    )
                    drained()
                    onLine("Upgraded · ${cluster.explorerTx(signature)}")
                    signature to handOver(programData)
                } else {
                    val authority = mode.authority
                    // Already handed over by the attempt this run resumed:
                    // doing it twice would be refused, and the buffer is
                    // exactly where it needs to be.
                    if (bufferAuthority != authority) {
                        onLine("Handing the buffer to Seed Vault ${short(authority)} so it can sign the upgrade")
                        sendLocal(listOf(Loader.setBufferAuthority(bufferKey, payer, authority)), signers = listOf(deployKey))
                        bufferAuthority = authority
                        OpenBuffers.add(
                            app,
                            OpenBuffer(bufferKey.base58, cluster, authority.base58, System.currentTimeMillis(), programId.base58),
                        )
                    }
                    val signature = signUpgrade(programData, bufferKey, authority)
                    drained()
                    onLine("Upgraded · ${cluster.explorerTx(signature)}")
                    signature to authority
                }
            }
        }
    }

    /**
     * The buffer is empty: its rent has moved to the spill account, which is
     * always the deploy key ([signUpgrade] explains why). Said out loud
     * because "Reclaim took 5,000 lamports and gave nothing back" was a real
     * reading of the Wallet sheet when the money went somewhere unnamed.
     */
    private fun drained() {
        bufferDrained = true
        if (bufferRent > 0L) onLine("Buffer drained · ${sol(bufferRent)} of rent back to the deploy key ${short(payer)}")
    }

    /**
     * The wallet's signature on the Upgrade, asked for once and then once
     * more after re-authorizing.
     *
     * The wallet is launched from the activity, and only once it is resumed:
     * a deploy watched from the notification shade stalls here until the app
     * is back. And the authorization can go missing between [authorizeUpfront]
     * and this moment — on 2026-09-08 the dApp Store updated
     * `com.solanamobile.wallet` mid-run and took Thragg's with it — so a
     * refusal here re-requests it inline and asks again rather than ending a
     * deploy that has everything else it needs. The buffer is untouched
     * either way: a second failure leaves it whole and the next run adopts it.
     */
    private suspend fun signUpgrade(programData: Pubkey, bufferKey: Pubkey, authority: Pubkey): String {
        // The spill is the DEPLOY KEY, not the authority: the buffer's rent
        // was fronted by the deploy key (createBuffer, or an earlier attempt's
        // createBuffer that adoptBuffer resumed), so paying it back to the
        // wallet moves nearly a SOL off the key that is about to need it and
        // reads, on the Deploy-key balance row, as an upgrade that cost 0.95
        // SOL (QA r4 §5). Whoever ends up holding the authority, the account
        // that paid gets it back; the Wallet sheet's "Return SOL to wallet"
        // is how it goes on to the wallet, deliberately.
        val instructions = listOf(Loader.upgrade(programData, programId, bufferKey, payer, authority))
        onLine("Asking Seed Vault to sign the upgrade · keep Thragg on screen while it answers")
        return try {
            ChainSigning.signAndSend(
                app, cluster, rpc, pacer, payer, instructions,
                local = listOf(deployKey), wallet = authority,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLine("Seed Vault did not sign: ${ChainSigning.readable(e)} · asking it to authorize Thragg again")
            val again = withContext(Dispatchers.Main) { SeedVaultWallet.connect(app, cluster) }
            val address = again.getOrElse { why ->
                throw ChainException(
                    "${ChainSigning.readable(e)} Re-authorizing failed too: ${ChainSigning.readable(why)}",
                    e,
                )
            }
            if (address != authority.base58) {
                throw ChainException(
                    "Seed Vault came back as ${Base58.short(address)}, which does not hold this program's " +
                        "upgrade authority (${short(authority)})",
                    e,
                )
            }
            onLine("Seed Vault authorized Thragg again · asking for the signature once more")
            ChainSigning.signAndSend(
                app, cluster, rpc, pacer, payer, instructions,
                local = listOf(deployKey), wallet = authority,
            )
        }
    }

    /**
     * Ask the cluster whether it will grow the programdata, BEFORE the
     * buffer's rent and the chunks are spent on the assumption that it will.
     *
     * The same reasoning as [authorizeUpfront], for the other precondition
     * an upload cannot supply. Measured on the Seeker 2026-09-09: an upgrade
     * that grew `r4_anchor` by 5,696 bytes wrote 180 chunks over 7m17s and
     * then died on "ExtendProgram requires a minimum of 10240 additional
     * bytes", leaving 0.93452748 SOL in a buffer. [Loader.extendBytes] is
     * why that particular refusal cannot happen again; this is why the next
     * one — an account at the 10 MB ceiling, a programdata whose owner
     * changed under us, a deploy key that cannot pay the extra rent after
     * all — costs seconds and nothing instead of seven minutes and a buffer.
     *
     * A simulation is not a promise: it is unsigned, run against the current
     * slot, and the extension is still sent for real in [finalise]. A probe
     * the node will not answer at all is not a refusal and does not stop the
     * deploy; only a simulation that came back with an error does.
     */
    private suspend fun probeExtend(mode: Mode) {
        val upgrade = mode as? Mode.Upgrade ?: return
        if (upgrade.grow <= 0) return
        val programData = Pubkey.of(upgrade.status.programData)
        onLine(
            "Checking ${cluster.display} will grow programdata ${short(programData)} by ${upgrade.grow} bytes " +
                "before uploading"
        )
        val refusal = try {
            val blockhash = pacer.run { rpc.getLatestBlockhash() }
            val message = Message.compile(
                payer,
                listOf(Loader.extendProgram(programData, programId, payer, upgrade.grow)),
                blockhash.blockhash,
            )
            pacer.run { rpc.simulate(Transaction.unsigned(message)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLine("The extension could not be checked (${ChainSigning.readable(e)}) — going ahead")
            return
        }
        if (refusal != null) {
            throw ChainException(
                "${cluster.display} will not grow programdata ${short(programData)} by ${upgrade.grow} bytes: " +
                    "$refusal — nothing was uploaded and nothing was spent."
            )
        }
        onLine("It will · nothing spent yet")
    }

    /**
     * Grow the programdata by [by] bytes so the new ELF fits. Nobody's
     * authority is needed — the loader lets anyone pay to extend — so the
     * deploy key signs and pays, whoever holds the upgrade authority.
     */
    private suspend fun extend(programData: Pubkey, by: Int) {
        onLine("Extending programdata ${short(programData)} by $by bytes")
        val signature = sendLocal(listOf(Loader.extendProgram(programData, programId, payer, by)), signers = listOf(deployKey))
        onLine("Extended · ${Base58.short(signature)}")
    }

    /**
     * Move the upgrade authority from the deploy key to the wallet when one
     * is connected. The program is already deployed by the time this runs, so
     * a refusal here is a log line, not a failed deploy.
     */
    private suspend fun handOver(programData: Pubkey): Pubkey {
        val to = wallet ?: run {
            onLine("Upgrade authority is the deploy key ${short(payer)} — connect Seed Vault to hand it over on the next deploy")
            return payer
        }
        return try {
            sendLocal(listOf(Loader.setUpgradeAuthority(programData, payer, to)), signers = listOf(deployKey))
            onLine("Upgrade authority is now Seed Vault ${short(to)}")
            to
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLine("Could not hand the upgrade authority to Seed Vault (${ChainSigning.readable(e)}) — it stays with the deploy key ${short(payer)}")
            payer
        }
    }

    // ---- 7. tidy ------------------------------------------------------------------

    /** Mainnet only: the deploy key's change goes back to the wallet, less one fee. */
    private suspend fun sweep() {
        val to = wallet ?: return
        if (!cluster.isMainnet) return
        try {
            val balance = balanceOf(payer)
            val amount = balance - Loader.LAMPORTS_PER_SIGNATURE
            if (amount <= 0) return
            sendLocal(listOf(Loader.transfer(payer, to, amount)), signers = listOf(deployKey))
            onLine("Returned ${sol(amount)} to Seed Vault ${short(to)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLine("Could not return the deploy key's change to Seed Vault (${ChainSigning.readable(e)}) — Settings > Wallet can do it later")
        }
    }

    // ---- shared -------------------------------------------------------------------

    /** Compile with the deploy key paying, sign with [signers], send and confirm. */
    private suspend fun sendLocal(instructions: List<Instruction>, signers: List<Keypair>): String =
        ChainSigning.signAndSend(app, cluster, rpc, pacer, payer, instructions, local = signers, wallet = null)

    private suspend fun rent(bytes: Int): Long = pacer.run { rpc.getMinimumBalanceForRentExemption(bytes) }

    private suspend fun balanceOf(key: Pubkey): Long = pacer.run { rpc.getBalance(key.base58) }

    private fun sol(lamports: Long): String = Loader.lamportsToSol(lamports)

    private fun short(key: Pubkey): String = Base58.short(key.base58)

    companion object {
        private const val LAMPORTS_PER_SOL = 1_000_000_000L
        private const val MAX_AIRDROP = 2 * LAMPORTS_PER_SOL
        private const val AIRDROP_TRIES = 3
        private const val AIRDROP_RETRY_MS = 2_000L
        private const val BLOCKHASH_LIFETIME = 150L
        private const val AIRDROP_CONFIRM_MS = 30_000L
        private const val MINE_LINE_MS = 10_000L
        private const val WRITE_BATCH = 40
        private const val WRITE_ROUNDS = 12
        private const val WRITE_WAIT_MS = 120_000L
        private const val STATUS_BATCH = 100
        private const val STATUS_POLL_MS = 1_500L
        private const val PROGRESS_EVERY = 10

        /**
         * Read everything a deploy needs off disk and out of prefs, on the
         * calling (IO) thread, and fail early on the two things a person fixes
         * with a build: no artifact, and a `declare_id!` that is not the
         * keypair's address. The second is checked here and not only on the
         * Deploy sheet, so that no caller of the Deployer seam can spend rent
         * on a program that rejects every instruction.
         */
        fun open(app: Context, project: ProjectLayout, program: ProgramTarget, onLine: (String) -> Unit): DeploySession {
            val artifact = File(project.root, program.artifactPath)
            if (!artifact.isFile) throw ChainException("Build first — no ${program.artifactPath}")
            val elf = artifact.readBytes()
            if (elf.isEmpty()) throw ChainException("${program.artifactPath} is empty — build again")
            val cluster = ClusterStore.of(app, project.root)
            if (ProgramIds.resolve(project.root, program, cluster).disagree) throw ChainException(ProgramIds.DISAGREE)
            val deployKey = DeployKey.get(app)
            val wallet = SeedVaultWallet.address?.let { Pubkey.ofOrNull(it) }
            val programKeypair = ProgramIds.ensureKeypair(project.root, program)
            return DeploySession(app, project, program, onLine, cluster, deployKey, wallet, programKeypair, elf)
        }
    }
}

/**
 * A buffer an earlier attempt left, that a run can finish instead of
 * uploading afresh — and that an estimate must therefore not charge for.
 */
internal class AdoptableBuffer(
    val key: Pubkey,
    val authority: Pubkey,
    val lamports: Long,
    /** Per chunk: whether the buffer already holds exactly those bytes. */
    val written: BooleanArray,
) {
    val done: Int get() = written.count { it }
    val chunks: Int get() = written.size
    val whole: Boolean get() = written.all { it }
}

/**
 * Whether an open buffer on this cluster is *this* artifact's, asked by both
 * the deployer and the Deploy sheet.
 *
 * IT IS ONE FUNCTION BECAUSE THE TWO DISAGREED. On the Seeker 2026-09-09 the
 * sheet quoted "~1.8111 SOL, of which 0.9047 comes back" for an upgrade the
 * run then landed for **0.00113 SOL**, because the deployer knew a whole
 * buffer was sitting there and the sheet did not (QA r4 §7). A user reading
 * that number decides whether to deploy at all, or tops up a key that needed
 * nothing. So the scan lives here, the deployer takes the buffer over
 * (`DeploySession.adoptBuffer`) and the sheet only prices it.
 *
 * The account is compared, not trusted: same owner, same size, same bytes,
 * and an authority this phone can still act through.
 */
internal object BufferAdoption {

    /**
     * Whether any buffer is on record for [programId] on [cluster] — the
     * cheap local question, so a sheet does not read a 200 kB artifact off
     * disk to discover there is nothing to adopt. No network.
     */
    fun anyRecorded(context: Context, cluster: Cluster, programId: String): Boolean =
        runCatching { OpenBuffers.all(context.applicationContext) }.getOrDefault(emptyList())
            .any { it.cluster == cluster && it.programId == programId }

    /**
     * The newest record for [programId] on [cluster] whose account is this
     * artifact's buffer, or null.
     *
     * [walletAuthority] is the wallet that holds the upgrade authority, when
     * one does: a buffer already handed over to it is adoptable only when it
     * is whole, because from that point this phone cannot write to it.
     * [forget] removes records whose account a successful read did not find
     * — the deployer's housekeeping, which a sheet does not do.
     */
    suspend fun scan(
        app: Context,
        rpc: Rpc,
        pacer: RpcPacer,
        cluster: Cluster,
        programId: String,
        elfSize: Int,
        chunks: List<Pair<Int, ByteArray>>,
        payer: Pubkey,
        walletAuthority: Pubkey?,
        forget: Boolean,
    ): AdoptableBuffer? {
        val records = runCatching { OpenBuffers.all(app) }.getOrDefault(emptyList())
            .filter { it.cluster == cluster && it.programId == programId }
        if (records.isEmpty()) return null
        val space = (Loader.BUFFER_HEADER + elfSize).toLong()
        for (record in records.asReversed()) {
            coroutineContext.ensureActive()
            val key = Pubkey.ofOrNull(record.address) ?: continue
            val read = runCatching { pacer.run { rpc.getAccountInfo(record.address) } }
            val info = read.getOrNull()
            if (info == null) {
                // A read that succeeded and found nothing is a record for an
                // account that is gone; a read that failed says nothing.
                if (forget && read.isSuccess) runCatching { OpenBuffers.remove(app, record.address) }
                continue
            }
            if (info.owner != Loader.PROGRAM_ID || info.space != space) continue
            val authority = (Loader.parse(info.data) as? Loader.State.Buffer)?.authority ?: continue
            val ours = authority == payer
            val theirs = walletAuthority != null && authority == walletAuthority
            if (!ours && !theirs) continue
            val written = Loader.writtenChunks(info.data, chunks)
            if (!ours && !written.all { it }) continue
            return AdoptableBuffer(key, authority, info.lamports, written)
        }
        return null
    }

    /**
     * The Deploy sheet's line under the cost when a buffer is being reused:
     * why the number is so much smaller than the one an upgrade usually
     * shows. Pure, tested.
     */
    fun detail(address: String, lamports: Long, done: Int, chunks: Int): String {
        val where = "buffer ${Base58.short(address)}"
        val rent = "${Loader.lamportsToSol(lamports)} of rent is already on chain"
        return when {
            done >= chunks && chunks > 0 -> "reusing $where — $rent and all $chunks chunks are uploaded"
            done > 0 -> "reusing $where — $rent and $done of $chunks chunks are uploaded"
            else -> "reusing $where — $rent"
        }
    }
}
