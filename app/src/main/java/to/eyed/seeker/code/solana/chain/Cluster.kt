package to.eyed.seeker.code.solana.chain

/**
 * The three Solana clusters the app will talk to, and every spelling each one
 * has.
 *
 * One enum rather than a string because a cluster is spelled four different
 * ways depending on who is asking, and the bugs live in the seams between
 * them: Anchor.toml wants `mainnet` and rejects `mainnet-beta`; the public
 * RPC host and the explorer's `?cluster=` query want `mainnet-beta`, except
 * that the explorer wants *nothing at all* for mainnet; Mobile Wallet Adapter
 * wants `solana:mainnet`. A row that prints one of these has to print the
 * right one, and a setting that persists one has to persist something that
 * survives a rename. [id] is the persisted form and it never changes; the
 * others are derived here, in one place, and tested.
 *
 * Deliberately no localnet and no custom RPC. There is no validator on this
 * phone (Agave has no arm64 build, docs/SOLANA.md), so a project whose
 * Anchor.toml says `localnet` is shown that fact and offered the three real
 * networks instead — see [fromAnchor] returning null for it.
 *
 * This file must stay free of Android and MWA imports: the mapping to MWA's
 * `Blockchain` lives in `SeedVaultWallet.kt` so that every function here is a
 * host-testable pure value.
 */
enum class Cluster(
    /** The persisted spelling: prefs keys, JSON records. Never renamed. */
    val id: String,
    /** What every row prints. */
    val display: String,
    /** The `[provider] cluster` value Anchor accepts (lowercase, no `-beta`). */
    val anchorName: String,
    /** The public JSON-RPC endpoint. */
    val rpcUrl: String,
    /** Whether `requestAirdrop` works here — devnet and testnet only. */
    val hasFaucet: Boolean,
) {
    Devnet("devnet", "devnet", "devnet", "https://api.devnet.solana.com", true),
    Testnet("testnet", "testnet", "testnet", "https://api.testnet.solana.com", true),
    MainnetBeta("mainnet-beta", "mainnet-beta", "mainnet", "https://api.mainnet-beta.solana.com", false);

    /** Real SOL: the one cluster where a deploy costs money and a close is final. */
    val isMainnet: Boolean get() = this == MainnetBeta

    /**
     * Whether the proof-of-work faucet program is deployed here — devnet
     * only. Where it is, it is what funds the deploy key (PowFaucet.kt): it
     * pays for CPU, not for waiting, where `requestAirdrop` is rationed by
     * IP and hangs when dry. [hasFaucet] still says whether `requestAirdrop`
     * exists at all, which is what a dry key's first few thousandths and
     * testnet rely on.
     */
    val hasPowFaucet: Boolean get() = this == Devnet

    /**
     * The explorer's query suffix. Mainnet is the explorer's default and takes
     * no parameter; the other two are named by their [id].
     */
    private val explorerQuery: String
        get() = if (isMainnet) "" else "?cluster=$id"

    /** `https://explorer.solana.com/address/<id>?cluster=devnet` — no query on mainnet. */
    fun explorerAddress(address: String): String = "$EXPLORER/address/$address$explorerQuery"

    /** `https://explorer.solana.com/tx/<sig>?cluster=devnet` — no query on mainnet. */
    fun explorerTx(signature: String): String = "$EXPLORER/tx/$signature$explorerQuery"

    companion object {
        private const val EXPLORER = "https://explorer.solana.com"

        /** Where a project starts: free SOL, and nothing that can be lost. */
        val DEFAULT: Cluster = Devnet

        /**
         * The cluster whose [id] is [id], or null. Case-insensitive and
         * whitespace-tolerant because the only thing that ever wrote one is
         * us, and a stored value that does not match is a reason to fall back,
         * not to crash.
         */
        fun fromId(id: String?): Cluster? {
            val wanted = id?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == wanted }
        }

        /**
         * The cluster an Anchor.toml `[provider] cluster` value names, or null.
         *
         * Case-insensitive, because Anchor itself is (`Devnet` and `DEVNET`
         * both work). `mainnet-beta` is accepted too even though Anchor
         * rejects it: a hand-edited file that says so clearly *means* mainnet,
         * and the next [ClusterStore.set] rewrites it to the spelling Anchor
         * takes. `localnet`, and anything else, is null — the caller shows the
         * raw value and offers the three real networks.
         */
        fun fromAnchor(name: String?): Cluster? {
            val wanted = name?.trim()?.lowercase() ?: return null
            if (wanted.isEmpty()) return null
            return entries.firstOrNull { it.anchorName == wanted || it.id == wanted }
        }
    }
}
