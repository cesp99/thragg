package to.eyed.thragg.solana.chain

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What this phone remembers about the chain: its deploy key, the programs it
 * has deployed, and any buffer it left behind mid-deploy.
 *
 * Three small files under `<filesDir>/chain/`, org.json, one `@Volatile`
 * cache each — the `SolanaToolchain.record` pattern (solana/toolchain/
 * ToolchainState.kt), because these are records of things that *happened*
 * rather than settings the user would ever type, and settings.json drops keys
 * the engine does not know. None of it is sensitive except the deploy key,
 * which is why that one is a separate file with owner-only permissions and
 * the other two are plain JSON a person could read.
 *
 * Why each record exists:
 *
 *  - **DeployKey.** The wallet is Seed Vault, reached through Mobile Wallet
 *    Adapter, and MWA prompts the user for *every* signing round. A deploy is
 *    a few hundred buffer writes. So the writes are signed by a local keypair
 *    that the faucet funds on devnet and the wallet funds once on mainnet,
 *    and the wallet is asked for nothing, or for one transfer. That keypair
 *    is generated on first use and kept for the life of the install, because
 *    it also ends up as the upgrade authority whenever no wallet is connected
 *    — lose it and those programs are immutable.
 *  - **DeployedPrograms.** Settings' "Deployed from this phone" card. The
 *    chain does not index programs by deployer, so this is the only list the
 *    app can show without asking the user for ids.
 *  - **OpenBuffers.** A buffer account holds the whole ELF's rent until the
 *    deploy that drains it lands. A process death or a network drop between
 *    the buffer being created and the deploy confirming would otherwise
 *    strand that SOL with nothing on the phone knowing its address. Recorded
 *    the moment the create-buffer transaction is *sent*, removed when the
 *    deploy completes, and offered back as "Reclaim" in Settings otherwise.
 *
 * The JSON shape is [DeployedPrograms.parse]/[DeployedPrograms.render] and
 * [OpenBuffers.parse]/[OpenBuffers.render]: pure functions over text, so the
 * round trip is a host test that needs no Context. An entry whose cluster id
 * is not one of ours is dropped on read rather than failing the file.
 *
 * All IO is blocking; call these on Dispatchers.IO.
 */

/** Where the chain layer keeps its files. */
private fun chainDir(context: Context): File =
    File(context.applicationContext.filesDir, "chain")

/**
 * The local keypair that signs buffer writes and, when no wallet is around,
 * holds the upgrade authority. See the file comment for why it exists.
 */
object DeployKey {

    private const val FILE_NAME = "deploy-key.json"

    @Volatile
    private var cached: Keypair? = null

    fun file(context: Context): File = File(chainDir(context), FILE_NAME)

    /** Whether one has been generated on this install. Blocking. */
    fun exists(context: Context): Boolean = cached != null || file(context).isFile

    /**
     * The deploy key, generated and written the first time anything asks.
     * Blocking.
     *
     * Owner-only permissions are applied on every read as well as on write:
     * a file that was there before this build learned to restrict it is
     * fixed the next time it is touched, and the calls are idempotent.
     */
    fun get(context: Context): Keypair {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val file = file(context)
            val existing = Keypair.read(file)
            val keypair = if (existing != null) {
                existing
            } else {
                val fresh = Keypair.generate()
                Keypair.write(file, fresh)
                fresh
            }
            restrict(file)
            cached = keypair
            return keypair
        }
    }

    /** `chmod 600`, as far as java.io.File can say it. */
    private fun restrict(file: File) {
        if (!file.isFile) return
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }
}

/** One program this phone deployed, as the Settings card lists it. */
data class DeployedProgram(
    /** The module name at deploy time: `my_program`. */
    val name: String,
    val programId: String,
    val cluster: Cluster,
    /** The upgrade authority after the deploy — wallet, deploy key, or null when unknown. */
    val authority: String?,
    /** Epoch millis. */
    val deployedAt: Long,
    /** The deploy or upgrade transaction's signature, for the explorer link. */
    val signature: String?,
    /** The project it came from, so the row can say so; null when unknown. */
    val projectRoot: String?,
)

/** A buffer account this phone created and has not yet drained. */
data class OpenBuffer(
    val address: String,
    val cluster: Cluster,
    /** Always the deploy key today; recorded so a future signer change is honest. */
    val authority: String,
    /** Epoch millis. */
    val createdAt: Long,
    /** The program the buffer was meant for, when known. */
    val programId: String?,
)

/** The `deployed-programs.json` record. */
object DeployedPrograms {

    private const val FILE_NAME = "deployed-programs.json"

    @Volatile
    private var cached: List<DeployedProgram>? = null

    /** Bumped by every [record] and [remove]. */
    var version by mutableIntStateOf(0)
        private set

    /** Every program recorded, oldest first. Blocking on the first call. */
    fun all(context: Context): List<DeployedProgram> {
        cached?.let { return it }
        val loaded = read(File(chainDir(context), FILE_NAME))
        cached = loaded
        return loaded
    }

    /**
     * Add [p], replacing any earlier record of the same id on the same
     * cluster — an upgrade is the same program, with a newer signature.
     */
    fun record(context: Context, p: DeployedProgram) {
        val kept = all(context).filterNot { it.programId == p.programId && it.cluster == p.cluster }
        write(context, kept + p)
    }

    /** Forget [programId] on [cluster] — after a close, or by hand. */
    fun remove(context: Context, programId: String, cluster: Cluster) {
        write(context, all(context).filterNot { it.programId == programId && it.cluster == cluster })
    }

    private fun write(context: Context, entries: List<DeployedProgram>) {
        val file = File(chainDir(context), FILE_NAME)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(render(entries))
        }
        cached = entries
        version++
    }

    private fun read(file: File): List<DeployedProgram> {
        if (!file.isFile) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    // --- the shape, pure ------------------------------------------------------

    /** `{"programs":[…]}` -> the list; entries with an unknown cluster are dropped. */
    fun parse(text: String): List<DeployedProgram> {
        if (text.isBlank()) return emptyList()
        val array = JSONObject(text).optJSONArray("programs") ?: return emptyList()
        val out = ArrayList<DeployedProgram>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val cluster = Cluster.fromId(o.optString("cluster", "")) ?: continue
            val programId = o.optString("programId", "")
            if (programId.isEmpty()) continue
            out.add(
                DeployedProgram(
                    name = o.optString("name", ""),
                    programId = programId,
                    cluster = cluster,
                    authority = o.optStringOrNull("authority"),
                    deployedAt = o.optLong("deployedAt", 0L),
                    signature = o.optStringOrNull("signature"),
                    projectRoot = o.optStringOrNull("projectRoot"),
                ),
            )
        }
        return out
    }

    /** The list -> `{"programs":[…]}`. */
    fun render(list: List<DeployedProgram>): String {
        val array = JSONArray()
        for (p in list) {
            array.put(
                JSONObject()
                    .put("name", p.name)
                    .put("programId", p.programId)
                    .put("cluster", p.cluster.id)
                    .putOrSkip("authority", p.authority)
                    .put("deployedAt", p.deployedAt)
                    .putOrSkip("signature", p.signature)
                    .putOrSkip("projectRoot", p.projectRoot),
            )
        }
        return JSONObject().put("programs", array).toString()
    }
}

/** The `open-buffers.json` record. */
object OpenBuffers {

    private const val FILE_NAME = "open-buffers.json"

    @Volatile
    private var cached: List<OpenBuffer>? = null

    /** Bumped by every [add] and [remove]. */
    var version by mutableIntStateOf(0)
        private set

    /** Every buffer still open, oldest first. Blocking on the first call. */
    fun all(context: Context): List<OpenBuffer> {
        cached?.let { return it }
        val loaded = read(File(chainDir(context), FILE_NAME))
        cached = loaded
        return loaded
    }

    /** Note [b] the moment its create transaction is sent; one row per address. */
    fun add(context: Context, b: OpenBuffer) {
        val kept = all(context).filterNot { it.address == b.address }
        write(context, kept + b)
    }

    /** Forget [address] — the deploy drained it, or Reclaim closed it. */
    fun remove(context: Context, address: String) {
        write(context, all(context).filterNot { it.address == address })
    }

    private fun write(context: Context, entries: List<OpenBuffer>) {
        val file = File(chainDir(context), FILE_NAME)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(render(entries))
        }
        cached = entries
        version++
    }

    private fun read(file: File): List<OpenBuffer> {
        if (!file.isFile) return emptyList()
        return runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    // --- the shape, pure ------------------------------------------------------

    /** `{"buffers":[…]}` -> the list; entries with an unknown cluster are dropped. */
    fun parse(text: String): List<OpenBuffer> {
        if (text.isBlank()) return emptyList()
        val array = JSONObject(text).optJSONArray("buffers") ?: return emptyList()
        val out = ArrayList<OpenBuffer>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val cluster = Cluster.fromId(o.optString("cluster", "")) ?: continue
            val address = o.optString("address", "")
            if (address.isEmpty()) continue
            out.add(
                OpenBuffer(
                    address = address,
                    cluster = cluster,
                    authority = o.optString("authority", ""),
                    createdAt = o.optLong("createdAt", 0L),
                    programId = o.optStringOrNull("programId"),
                ),
            )
        }
        return out
    }

    /** The list -> `{"buffers":[…]}`. */
    fun render(list: List<OpenBuffer>): String {
        val array = JSONArray()
        for (b in list) {
            array.put(
                JSONObject()
                    .put("address", b.address)
                    .put("cluster", b.cluster.id)
                    .put("authority", b.authority)
                    .put("createdAt", b.createdAt)
                    .putOrSkip("programId", b.programId),
            )
        }
        return JSONObject().put("buffers", array).toString()
    }
}

/** A string field that may be absent or JSON `null`, as a Kotlin null. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

/** Put [value], or leave the key out entirely when it is null. */
private fun JSONObject.putOrSkip(key: String, value: String?): JSONObject =
    if (value == null) this else put(key, value)
