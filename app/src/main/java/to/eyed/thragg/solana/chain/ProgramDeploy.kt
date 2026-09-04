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
 *     buffer's rent to discover.
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
 *     create it), reserving twice the ELF as `max_data_len` so the next
 *     build has room; an upgrade that has outgrown its programdata extends
 *     it first. A fresh deploy and an upgrade owned by the deploy key are
 *     signed locally. An upgrade whose authority is the wallet hands the
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

    /** Set the moment the create-buffer transaction is signed; cleared once the buffer has been drained. */
    @Volatile
    private var buffer: Keypair? = null

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
        fund(mode)
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
        buffer?.let { OpenBuffers.remove(app, it.publicKey.base58) }
        sweep()
        return "Program Id: ${programId.base58} · signature $signature · ${cluster.explorerAddress(programId.base58)}"
    }

    /** The line a failure or a cancellation appends once a buffer exists and has not been drained. */
    fun bufferNote(): String? {
        val open = buffer?.takeUnless { bufferDrained } ?: return null
        return "Buffer ${open.publicKey.base58} holds ${sol(bufferRent)} — Settings > Wallet can reclaim it"
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
                val grow = (elf.size - status.dataLen).coerceAtLeast(0L).toInt()
                if (grow > 0) {
                    onLine(
                        "Its programdata has room for ${status.dataLen} bytes and the new artifact is ${elf.size} — " +
                            "it will be extended by $grow bytes first"
                    )
                }
                Mode.Upgrade(status, authority, byWallet, grow)
            }
        }
    }

    // ---- 2. fund ------------------------------------------------------------------

    private suspend fun fund(mode: Mode) {
        val upgrade = mode is Mode.Upgrade
        bufferRent = rent(Loader.BUFFER_HEADER + elf.size)
        // Fresh: the programdata at its reserved size. Upgrade: only what an
        // extension adds — the rent the grown account needs, less what the
        // account already holds — and nothing when the ELF fits.
        val programDataRent = when (mode) {
            Mode.Fresh -> rent((Loader.PROGRAMDATA_HEADER + Loader.maxDataLen(elf.size)).toInt())
            is Mode.Upgrade ->
                if (mode.grow > 0) (rent(Loader.PROGRAMDATA_HEADER + elf.size) - mode.status.reclaimable).coerceAtLeast(0L) else 0L
        }
        programRent = if (upgrade) 0L else rent(Loader.PROGRAM_SIZE)
        val estimate = Loader.CostEstimate(
            bufferRent = bufferRent,
            programDataRent = programDataRent,
            programRent = programRent,
            fees = Loader.LAMPORTS_PER_SIGNATURE * (chunks.size + 5),
        )
        val required = estimate.total + estimate.total / 10
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
        } catch (e: Exception) {
            val held = runCatching { balanceOf(payer) }.getOrDefault(balance)
            throw ChainException(
                "Deploy key ${payer.base58} holds ${sol(held)} and needs ${sol(required)} on ${cluster.display}. " +
                    "${ChainSigning.readable(e)} Send ${sol((required - held).coerceAtLeast(0L))} to ${payer.base58} and deploy again.",
                e,
            )
        }
    }

    /** One Transfer from the wallet to the deploy key, signed by the wallet, sent by us. */
    private suspend fun walletTransfer(from: Pubkey, lamports: Long) {
        val available = balanceOf(from)
        val needed = lamports + Loader.LAMPORTS_PER_SIGNATURE
        if (available < needed) {
            throw ChainException(
                "Seed Vault ${short(from)} holds ${sol(available)} on ${cluster.display}, and funding the deploy key needs ${sol(needed)}"
            )
        }
        onLine("Asking Seed Vault to send ${sol(lamports)} to the deploy key ${short(payer)} · keep Thragg on screen while it answers")
        val signature = ChainSigning.signAndSend(
            app, cluster, rpc, pacer, from, listOf(Loader.transfer(from, payer, lamports)),
            local = emptyList(), wallet = from,
        )
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
        // may be, and the record is the only way back to it.
        buffer = keypair
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
        val bufferKey = checkNotNull(buffer).publicKey
        val total = chunks.size
        val confirmed = BooleanArray(total)
        val lastError = AtomicReference<String?>(null)
        var pending: List<Int> = chunks.indices.toList()
        onLine("Writing $total chunks of ${Loader.writeChunkSize()} bytes")
        for (round in 1..WRITE_ROUNDS) {
            coroutineContext.ensureActive()
            if (round > 1) onLine("Resending ${pending.size} chunks (round $round of $WRITE_ROUNDS)")
            val sent = ArrayList<Sent>(pending.size)
            for (batch in pending.chunked(WRITE_BATCH)) {
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
        val bufferKey = checkNotNull(buffer).publicKey
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
                bufferDrained = true
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
                    bufferDrained = true
                    onLine("Upgraded · ${cluster.explorerTx(signature)}")
                    signature to handOver(programData)
                } else {
                    val authority = mode.authority
                    onLine("Handing the buffer to Seed Vault ${short(authority)} so it can sign the upgrade")
                    sendLocal(listOf(Loader.setBufferAuthority(bufferKey, payer, authority)), signers = listOf(deployKey))
                    OpenBuffers.add(
                        app,
                        OpenBuffer(bufferKey.base58, cluster, authority.base58, System.currentTimeMillis(), programId.base58),
                    )
                    // The wallet is launched from the activity, and only
                    // once it is resumed: a deploy watched from the
                    // notification shade stalls here until the app is back.
                    onLine("Asking Seed Vault to sign the upgrade · keep Thragg on screen while it answers")
                    val signature = ChainSigning.signAndSend(
                        app, cluster, rpc, pacer, payer,
                        listOf(Loader.upgrade(programData, programId, bufferKey, authority, authority)),
                        local = listOf(deployKey), wallet = authority,
                    )
                    bufferDrained = true
                    onLine("Upgraded · ${cluster.explorerTx(signature)}")
                    signature to authority
                }
            }
        }
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
