package to.eyed.thragg.solana.chain

import com.solana.mobilewalletadapter.clientlib.transaction.TransactionVersion

/**
 * Which [TxFormat] a transaction is compiled in, and the one config every
 * V1 message needs.
 *
 * Two answers, for two kinds of signer:
 *
 *  * **[local]** — every transaction only this phone's keys sign: buffer
 *    writes and the buffer's CreateAccount, a deploy or upgrade by the deploy
 *    key, SetAuthority, Close, the faucet's claims, the Wallet sheet's
 *    "Return SOL". These are V1, because that is where the size goes: a V1
 *    write transaction carries three loader Writes where a legacy one
 *    carried one ([Loader.writesPerTransaction]), and a faucet transaction
 *    eleven ground keys' claims where a legacy one carried six keys'. `enable_tx_v1` is active
 *    on devnet, testnet and mainnet-beta (verified 2026-09-19).
 *  * **[wallet]** — a transaction Seed Vault signs: V1 only when the wallet's
 *    MWA `get_capabilities` answer lists transaction version 1
 *    (`supported_transaction_versions`), legacy otherwise. Whether the
 *    shipping Seed Vault Wallet advertises or signs V1 is UNKNOWN as of
 *    2026-09-19 — seed-vault-sdk PR #780 is open — which is why it is asked
 *    rather than assumed, on connect and cached per cluster and account
 *    (SeedVaultWallet), and why a
 *    wallet that could not be asked is treated as legacy.
 *
 * **Demotion.** The public RPC endpoints sit behind load balancers and a
 * node that predates SIMD-0385 answers a V1 payload with a decoder error
 * ([RpcException.isFormatNotUnderstood]). The first such answer to a V1
 * send or simulate demotes the whole process to legacy — [local] and
 * [wallet] both — for the rest of its life, once, loudly (the caller's log
 * line), and the caller recompiles the same instructions as legacy. A
 * deploy mid-upload re-cuts what it has not yet written to the legacy chunk
 * size ([Loader.recut]); the offsets already on the buffer stay valid.
 * Nothing promotes back: a process that saw one old node will see it again.
 *
 * **Config.** A V1 header with no compute-unit limit runs on ZERO units and
 * one with no loaded-accounts-data-size loads ZERO bytes — agave's
 * `from_v1_config` is `unwrap_or(0)` for both (read 2026-09-19), which is
 * not what a legacy transaction gets by default — so [config] reproduces
 * the legacy defaults explicitly: 3,000 units per builtin instruction
 * (`MAX_BUILTIN_ALLOCATION_COMPUTE_UNIT_LIMIT`, SIMD-0170), 200,000 per
 * other program (`DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT`), capped at the
 * runtime's 1.4 M, and 64 MiB of account data. Neither raises the fee: the
 * fee is signatures times 5,000 plus the priority fee, which stays unset.
 *
 * Pure apart from one volatile flag, so the JVM pins every branch.
 */
object TxFormatPolicy {

    /** The runtime's `MAX_BUILTIN_ALLOCATION_COMPUTE_UNIT_LIMIT`: what a builtin instruction is given by default. */
    const val BUILTIN_COMPUTE_UNITS = 3_000L

    /** The runtime's `DEFAULT_INSTRUCTION_COMPUTE_UNIT_LIMIT`: what any other instruction is given by default. */
    const val DEFAULT_COMPUTE_UNITS = 200_000L

    /** The builtins this app calls; anything else is a BPF program and budgets like one. */
    private val BUILTINS: Set<Pubkey> by lazy { setOf(Loader.SYSTEM_PROGRAM, Loader.PROGRAM_ID, WalletAnswers.COMPUTE_BUDGET) }

    @Volatile
    private var demoted = false

    /** True once an RPC has refused a V1 payload for its shape; every format is legacy from then on. */
    val isDemoted: Boolean get() = demoted

    /** The format for a transaction only local keypairs sign. */
    fun local(): TxFormat = if (demoted) TxFormat.Legacy else TxFormat.V1

    /**
     * The format for a transaction the wallet signs, from the
     * `supported_transaction_versions` its capabilities reported — an
     * `Object[]` of the strings and integers the spec allows, or null when
     * the wallet was never asked or did not answer. Version 1 in the list
     * is the only thing that makes it V1.
     */
    fun wallet(supportedTransactionVersions: Array<Any>?): TxFormat = when {
        demoted -> TxFormat.Legacy
        supportedTransactionVersions == null -> TxFormat.Legacy
        TransactionVersion.supportsVersion(supportedTransactionVersions, 1) -> TxFormat.V1
        else -> TxFormat.Legacy
    }

    /**
     * The config a message in [format] carrying [instructions] needs:
     * [TxConfig.NONE] for legacy, and for V1 the legacy defaults spelled
     * out — [computeUnitLimit] when the caller measured its own, else the
     * runtime's per-instruction allowance — plus the full 64 MiB of account
     * data. The heap and the priority fee stay unset.
     */
    fun config(format: TxFormat, instructions: List<Instruction>, computeUnitLimit: Long? = null): TxConfig = when (format) {
        TxFormat.Legacy -> TxConfig.NONE
        TxFormat.V1 -> TxConfig(
            computeUnitLimit = (computeUnitLimit ?: defaultComputeUnits(instructions)).coerceIn(0L, TxConfig.MAX_COMPUTE_UNIT_LIMIT),
            loadedAccountsDataSize = TxConfig.MAX_LOADED_ACCOUNTS_DATA_SIZE,
        )
    }

    /** What the runtime would have given a legacy message of [instructions] with no ComputeBudget instruction. */
    fun defaultComputeUnits(instructions: List<Instruction>): Long =
        instructions.sumOf { if (it.programId in BUILTINS) BUILTIN_COMPUTE_UNITS else DEFAULT_COMPUTE_UNITS }
            .coerceIn(0L, TxConfig.MAX_COMPUTE_UNIT_LIMIT)

    /**
     * Whether [e], from a send or simulate of a message in [format], is the
     * node not understanding V1 — and if so, the demotion, done. Returns
     * true when the caller should recompile as legacy and try again; the
     * first caller to get here also gets the line for its log. A legacy
     * refusal, or any other error, is false and the caller's to handle.
     */
    fun refusal(e: RpcException, format: TxFormat, onLine: (String) -> Unit): Boolean {
        if (format != TxFormat.V1 || !e.isFormatNotUnderstood) return false
        demote(e.message ?: "refused", onLine)
        return true
    }

    /** Demote, once: the line is printed only by the call that flips the flag. */
    fun demote(why: String, onLine: (String) -> Unit) {
        synchronized(this) {
            if (demoted) return
            demoted = true
        }
        onLine(
            "This endpoint does not accept Transaction V1 ($why) — " +
                "sending legacy transactions from here on, which are smaller and take more of them"
        )
    }

    /** Tests only: back to V1, as a fresh process is. */
    internal fun reset() {
        demoted = false
    }
}
