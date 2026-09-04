package to.eyed.thragg.solana.chain

/**
 * What the cluster says about a program id, and the sentence Settings prints
 * for it.
 *
 * The Program row in Settings, the Deploy sheet and the close flow all start
 * with the same question — "what is at this address on this cluster?" — and
 * the loader answers it in two hops: the 36-byte Program account names the
 * programdata account, and the programdata account holds the slot, the
 * upgrade authority and the ELF. Two `getAccountInfo` calls, folded here into
 * one of four answers, so that no caller has to know the loader's layouts or
 * the four ways an address can be "not deployed":
 *
 *  - [OnChainProgram.NotFound]: nothing at the id. A fresh deploy goes here.
 *  - [OnChainProgram.NotAProgram]: an account, but not one the upgradeable
 *    loader owns — someone's wallet, a token account, a program under the
 *    old loader. Deploying over it is impossible and the row says whose it is.
 *  - [OnChainProgram.Closed]: the Program account is there but its
 *    programdata is gone. This is the one that bites: the loader refuses to
 *    ever deploy under that id again, so the row has to say so in words and
 *    the deploy has to stop before it spends a buffer's rent on it.
 *  - [OnChainProgram.Deployed]: the real thing, with the facts the close
 *    flow needs — who may upgrade it and how many lamports come back.
 *
 * [inspect] is the only function here that talks to the network, and it
 * blocks; the sentence and the permission check are pure so they are pinned
 * by host tests, the way every row label in this app is.
 */
sealed interface OnChainProgram {

    /** No account at that id. */
    data object NotFound : OnChainProgram

    /** An account that the upgradeable loader does not own, or owns but is not a program. [owner] is Base58. */
    data class NotAProgram(val owner: String) : OnChainProgram

    /**
     * A live program. [authority] is the upgrade authority in Base58, or
     * null when the program is immutable; [reclaimable] is the programdata
     * account's lamports, all of which a close returns; [dataLen] is the
     * ELF capacity the programdata was sized for (`max_data_len`).
     */
    data class Deployed(
        val programId: String,
        val programData: String,
        val slot: Long,
        val authority: String?,
        val reclaimable: Long,
        val dataLen: Long,
    ) : OnChainProgram

    /** The Program account exists but its programdata was closed: the id can never be deployed again. */
    data class Closed(val programId: String) : OnChainProgram
}

object ProgramStatus {

    /**
     * Two reads, one answer. Blocking; call it on Dispatchers.IO, and through
     * an [RpcPacer] when it runs next to a deploy. Throws [RpcException] when
     * the cluster could not be reached — the row prints "could not reach
     * devnet" for that, which is a different fact from any of the four.
     */
    fun inspect(rpc: Rpc, programId: String): OnChainProgram {
        val program = rpc.getAccountInfo(programId) ?: return OnChainProgram.NotFound
        if (program.owner != Loader.PROGRAM_ID) return OnChainProgram.NotAProgram(program.owner.base58)
        val state = Loader.parse(program.data) as? Loader.State.Program
            ?: return OnChainProgram.NotAProgram(program.owner.base58)
        val programDataId = state.programData.base58
        // The header only: the slot and the authority are its 45 bytes, and
        // the ELF after them is a megabyte this row has no use for. The
        // account's full size still arrives as `space`, which is what the
        // capacity below is read from — never the (sliced) data's length.
        val programData = rpc.getAccountInfo(programDataId, dataSlice = DataSlice(0, Loader.PROGRAMDATA_HEADER))
            ?: return OnChainProgram.Closed(programId)
        val data = Loader.parse(programData.data) as? Loader.State.ProgramData
            ?: return OnChainProgram.Closed(programId)
        return OnChainProgram.Deployed(
            programId = programId,
            programData = programDataId,
            slot = data.slot,
            authority = data.authority?.base58,
            reclaimable = programData.lamports,
            dataLen = (programData.space - Loader.PROGRAMDATA_HEADER).coerceAtLeast(0L),
        )
    }

    /**
     * The Settings row's sentence. [wallet] and [deployKey] are the two
     * addresses this phone can sign for, so the authority is named by its
     * role when it is one of them and by its short address otherwise.
     */
    fun describe(status: OnChainProgram, cluster: Cluster, wallet: String?, deployKey: String?): String {
        val where = cluster.display
        return when (status) {
            OnChainProgram.NotFound -> "not deployed on $where"
            is OnChainProgram.NotAProgram -> "not a program on $where · owned by ${Base58.short(status.owner)}"
            is OnChainProgram.Closed -> "closed on $where · id can never be reused"
            is OnChainProgram.Deployed -> {
                val authority = status.authority
                val by = when {
                    authority == null -> "immutable"
                    wallet != null && authority == wallet -> "upgradeable by Seed Vault"
                    deployKey != null && authority == deployKey -> "upgradeable by the deploy key"
                    else -> "upgradeable by ${Base58.short(authority)}"
                }
                "deployed on $where · $by"
            }
        }
    }

    /**
     * Whether this phone holds the authority a close needs: the program is
     * deployed and its upgrade authority is the connected wallet or the
     * local deploy key. An immutable program cannot be closed by anyone.
     */
    fun canClose(status: OnChainProgram, wallet: String?, deployKey: String?): Boolean {
        val deployed = status as? OnChainProgram.Deployed ?: return false
        val authority = deployed.authority ?: return false
        return (wallet != null && authority == wallet) || (deployKey != null && authority == deployKey)
    }
}
