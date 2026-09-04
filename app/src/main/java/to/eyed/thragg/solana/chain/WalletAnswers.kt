package to.eyed.thragg.solana.chain

/**
 * What to make of a transaction a wallet hands back.
 *
 * The first assumption was that a wallet signs the bytes it is given and
 * nothing else, and the Seeker's own Seed Vault Wallet disproved it on the
 * first devnet transfer: it returned a transaction whose message no longer
 * matched — a wallet is free to refresh the blockhash and to put its own
 * compute-budget instructions in front, and this one does. Refusing that
 * was refusing every transaction the wallet would ever sign.
 *
 * So the check is for what MATTERS rather than for identity: the fee payer
 * is still ours, every instruction we asked for is still there with the same
 * program, the same accounts in the same roles and the same data, anything
 * the wallet added is a compute-budget instruction and nothing else, and the
 * wallet's signature verifies over the message it actually returned. What
 * comes back is the wallet's message, because that is the one its signature
 * covers, and every local signer signs that one afterwards
 * (ChainSigning.signAndSend).
 *
 * Pure, so the JVM can pin it with a wallet that adds, a wallet that swaps,
 * and a wallet that drops.
 */
internal object WalletAnswers {

    /** `ComputeBudget111111111111111111111111111111` — the only program a wallet may add. */
    val COMPUTE_BUDGET: Pubkey = Pubkey.of("ComputeBudget111111111111111111111111111111")

    /**
     * The [original]'s instruction set, as resolved account metas, for
     * comparing two messages whose key tables differ.
     */
    private data class Resolved(val programId: Pubkey, val accounts: List<AccountMeta>, val data: List<Byte>)

    private fun Message.resolve(ix: CompiledInstruction): Resolved {
        val total = accountKeys.size
        val signed = header.numRequiredSignatures
        val writableSigned = signed - header.numReadonlySigned
        val writableUnsigned = total - signed - header.numReadonlyUnsigned
        fun meta(index: Int): AccountMeta {
            val signer = index < signed
            val writable = if (signer) index < writableSigned else index - signed < writableUnsigned
            return AccountMeta(accountKeys[index], signer, writable)
        }
        return Resolved(accountKeys[ix.programIdIndex], ix.accountIndexes.map(::meta), ix.data.toList())
    }

    /**
     * The wallet's copy of [original], checked, or a [WalletException] that
     * names what the wallet changed. [ordinal] is one-based, for the message.
     */
    fun accept(original: Transaction, returned: ByteArray, wallet: Pubkey, ordinal: Int): Transaction {
        val tx = try {
            Transaction.deserialize(returned)
        } catch (e: Exception) {
            throw WalletException("Seed Vault returned transaction $ordinal in a shape this app cannot read")
        }
        val before = original.message
        val after = tx.message
        if (before.accountKeys.firstOrNull() != after.accountKeys.firstOrNull()) {
            throw WalletException("Seed Vault changed who pays for transaction $ordinal")
        }
        val wanted = before.instructions.map { before.resolve(it) }.toMutableList()
        for (ix in after.instructions) {
            val got = after.resolve(ix)
            val index = wanted.indexOf(got)
            when {
                index >= 0 -> wanted.removeAt(index)
                got.programId == COMPUTE_BUDGET -> Unit
                else -> throw WalletException(
                    "Seed Vault added an instruction for ${Base58.short(got.programId.base58)} to transaction $ordinal"
                )
            }
        }
        if (wanted.isNotEmpty()) {
            throw WalletException("Seed Vault dropped ${wanted.size} of transaction $ordinal's instructions")
        }
        val slot = after.indexOf(wallet)
        if (slot !in 0 until after.signerCount) {
            throw WalletException("Seed Vault returned transaction $ordinal without a slot for its own signature")
        }
        val signature = tx.signatures.getOrNull(slot)
        if (signature == null || signature.size != Transaction.SIGNATURE_SIZE || signature.all { it == 0.toByte() }) {
            throw WalletException("Seed Vault returned transaction $ordinal without its signature")
        }
        if (!Ed25519.verify(wallet, after.serialize(), signature)) {
            throw WalletException("Seed Vault's signature on transaction $ordinal does not verify")
        }
        return tx
    }
}
