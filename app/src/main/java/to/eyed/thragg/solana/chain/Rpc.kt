package to.eyed.thragg.solana.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The eight JSON-RPC calls a deploy, an upgrade, a close and a Settings row
 * need, spoken to a public Solana endpoint over `HttpURLConnection`.
 *
 * There is no `solana` CLI on the phone (Agave has no arm64 build), so every
 * question we ask the cluster goes through here. The shape follows from two
 * facts about the public endpoints:
 *
 *  - They answer in three registers that all look like "the call failed":
 *    an HTTP status (429 when we write too fast), a JSON-RPC error envelope
 *    (`-32005` "node is behind", a simulation failure with `data.logs`), and
 *    a null `value`. [RpcException] carries the HTTP status and the RPC code
 *    separately so the pacer can tell "slow down" from "you sent nonsense",
 *    and the message quotes the first program log lines because that is the
 *    only place a failed deploy says *why*.
 *  - They are rate limited per IP (100 requests / 10 s, 40 per method, 40 in
 *    flight), and a deploy is a couple of hundred writes. [RpcPacer] is the
 *    one gate every write goes through: a sliding window well under the
 *    public limit, an in-flight cap, and a short exponential retry on the
 *    three answers that mean "try again" rather than "you are wrong".
 *
 * Every parser is a pure companion function over the response text so the
 * envelope handling is checked on the host with the real `org.json`, not on
 * a device against a live cluster. The network methods block (call them on
 * `Dispatchers.IO`); only [RpcPacer.run] suspends, and it moves the blocking
 * call onto IO itself.
 */

/**
 * One failed call. [httpStatus] is set when the transport refused us (429 is
 * the interesting one); [code] is the JSON-RPC error code when the node
 * answered with an error envelope. Both null means a local complaint, such as
 * an expired blockhash or a response that was not JSON at all.
 */
class RpcException(
    message: String,
    val code: Int? = null,
    val httpStatus: Int? = null,
    /** The endpoint's own `Retry-After`, in seconds, when a 429 carried one. */
    val retryAfterSeconds: Int? = null,
) : Exception(message) {

    /**
     * Answers the pacer's question: is this worth a second try after a
     * breath? 429 is the rate limit; 502, 503 and 504 are the load balancer
     * in front of the public endpoint saying a node was not there for that
     * one request, which the next one usually does not repeat.
     */
    val isTransient: Boolean
        get() = httpStatus == 429 || httpStatus in TRANSIENT_GATEWAY || code == NODE_BEHIND

    /**
     * The blockhash ran out before the transaction landed — either [Rpc.confirm]
     * saw the chain pass `lastValidBlockHeight`, or preflight refused a hash the
     * node no longer holds. Either way nothing landed and the same instructions
     * can be signed again over a fresh hash ([ChainSigning.signAndSend]).
     */
    val isBlockhashExpiry: Boolean
        get() = message == BLOCKHASH_EXPIRED || message?.contains("Blockhash not found", ignoreCase = true) == true

    companion object {
        /** "Node is behind by N slots" / "Transaction simulation failed: blockhash not found" family. */
        const val NODE_BEHIND = -32005

        /** What [Rpc.confirm] says when the chain moved past the hash without the transaction. */
        const val BLOCKHASH_EXPIRED = "blockhash expired"

        /** Bad gateway, unavailable, gateway timeout: the endpoint's front door, not the request. */
        private val TRANSIENT_GATEWAY = setOf(502, 503, 504)
    }
}

/** `getAccountInfo` with base64 encoding, decoded. [space] is the on-chain data length. */
data class AccountInfo(
    val lamports: Long,
    val owner: Pubkey,
    val data: ByteArray,
    val executable: Boolean,
    val space: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is AccountInfo &&
            lamports == other.lamports &&
            owner.base58 == other.owner.base58 &&
            data.contentEquals(other.data) &&
            executable == other.executable &&
            space == other.space

    override fun hashCode(): Int = owner.base58.hashCode() * 31 + data.contentHashCode()
}

/** The `dataSlice` option of `getAccountInfo`: [length] bytes of `data` starting at [offset]. */
data class DataSlice(val offset: Int, val length: Int)

/** `getLatestBlockhash`: the hash to sign over and the last block height it is good for. */
data class Blockhash(val blockhash: String, val lastValidBlockHeight: Long)

/**
 * One row of `getSignatureStatuses`. [err] is the error object rendered as
 * text (null when the transaction succeeded or is still in flight);
 * [confirmationStatus] is `processed`, `confirmed` or `finalized`.
 */
data class SignatureStatus(
    val slot: Long,
    val confirmations: Long?,
    val err: String?,
    val confirmationStatus: String?,
) {
    /** Landed without error at `confirmed` or better. */
    val confirmed: Boolean
        get() = err == null && (confirmationStatus == "confirmed" || confirmationStatus == "finalized")
}

class Rpc(val cluster: Cluster, val url: String = cluster.rpcUrl) {

    /**
     * `getAccountInfo`, or null when there is no account. [dataSlice] asks
     * for [DataSlice.length] bytes from [DataSlice.offset] instead of the
     * whole `data` — a programdata account is the ELF plus a header, and a
     * caller after the header alone should not pull a megabyte over the
     * phone's radio to read 45 bytes of it. `space` is the full size either
     * way; only `data` is cut.
     */
    fun getAccountInfo(pubkey: String, commitment: String = "confirmed", dataSlice: DataSlice? = null): AccountInfo? {
        val options = JSONObject().put("encoding", "base64").put("commitment", commitment)
        if (dataSlice != null) {
            options.put("dataSlice", JSONObject().put("offset", dataSlice.offset).put("length", dataSlice.length))
        }
        return parseAccountInfo(call("getAccountInfo", JSONArray().put(pubkey).put(options)))
    }

    fun getBalance(pubkey: String): Long {
        val options = JSONObject().put("commitment", "confirmed")
        return parseResultLong(call("getBalance", JSONArray().put(pubkey).put(options)))
    }

    fun getLatestBlockhash(): Blockhash {
        val options = JSONObject().put("commitment", "confirmed")
        return parseBlockhash(call("getLatestBlockhash", JSONArray().put(options)))
    }

    fun getBlockHeight(): Long {
        val options = JSONObject().put("commitment", "confirmed")
        return parseResultLong(call("getBlockHeight", JSONArray().put(options)))
    }

    fun getMinimumBalanceForRentExemption(bytes: Int): Long =
        parseResultLong(call("getMinimumBalanceForRentExemption", JSONArray().put(bytes)))

    /** Devnet and testnet only; the faucet answers 429 freely, so callers go through [RpcPacer]. */
    fun requestAirdrop(pubkey: String, lamports: Long): String =
        // The faucet is the one endpoint that hangs rather than answers when
        // it is dry (measured on devnet: an "Internal error" first, then a
        // request that sat for minutes). Twenty seconds is long enough for a
        // faucet that works and short enough to move on from one that does not.
        parseResultString(call("requestAirdrop", JSONArray().put(pubkey).put(lamports), readTimeoutMs = AIRDROP_TIMEOUT_MS))

    /**
     * Statuses in the same order as [signatures], null where the cluster has
     * never seen one. The node caps a request at 256 signatures; longer lists
     * are split here so callers can hand over a whole write batch.
     */
    fun getSignatureStatuses(signatures: List<String>): List<SignatureStatus?> {
        if (signatures.isEmpty()) return emptyList()
        val options = JSONObject().put("searchTransactionHistory", true)
        return signatures.chunked(MAX_STATUS_BATCH).flatMap { batch ->
            val list = JSONArray()
            batch.forEach { list.put(it) }
            parseStatuses(call("getSignatureStatuses", JSONArray().put(list).put(options)))
        }
    }

    /** Sends a fully signed transaction and returns its signature (base58 of slot 0). */
    fun sendTransaction(tx: Transaction, skipPreflight: Boolean = false): String {
        if (!tx.isFullySigned) throw RpcException("Transaction is missing a signature")
        val options = JSONObject()
            .put("encoding", "base64")
            .put("skipPreflight", skipPreflight)
            .put("preflightCommitment", "confirmed")
            .put("maxRetries", SEND_MAX_RETRIES)
        return parseResultString(call("sendTransaction", JSONArray().put(tx.serialize().toBase64()).put(options)))
    }

    /**
     * Polls until [signature] is confirmed or finalized. Throws [RpcException]
     * with the program's error when the transaction landed and failed, with
     * "blockhash expired" once the chain has moved past [lastValidBlockHeight]
     * without seeing it (the caller re-signs and resends), and with a timeout
     * message as a last resort. The block height is checked every other poll
     * so a confirm costs about one request per second, not two.
     *
     * The height is read after the status, so the two can straddle the last
     * valid block: the status said "not yet", the transaction landed in that
     * very block, and the height then read past it. "Expired" there would
     * send the caller off to sign the same instructions again — a second
     * Transfer, a second Write that happens to be idempotent only by luck.
     * So the height is not the last word: one more status read is, and the
     * transaction it finds is returned (or its error thrown) as usual.
     */
    fun confirm(signature: String, lastValidBlockHeight: Long, timeoutMs: Long = 90_000): SignatureStatus {
        val deadline = System.currentTimeMillis() + timeoutMs
        var polls = 0
        while (true) {
            settled(signature)?.let { return it }
            if (polls % 2 == 1 && getBlockHeight() > lastValidBlockHeight) {
                settled(signature)?.let { return it }
                throw RpcException(RpcException.BLOCKHASH_EXPIRED)
            }
            if (System.currentTimeMillis() >= deadline) {
                throw RpcException("Timed out after ${timeoutMs / 1000} s waiting for ${Base58.short(signature)} on ${cluster.display}")
            }
            polls++
            Thread.sleep(CONFIRM_POLL_MS)
        }
    }

    /** One status read: the status when [signature] is confirmed, null while it is not, its error thrown. */
    private fun settled(signature: String): SignatureStatus? {
        val status = getSignatureStatuses(listOf(signature)).firstOrNull() ?: return null
        status.err?.let { throw RpcException("Transaction ${Base58.short(signature)} failed: $it") }
        return status.takeIf { it.confirmed }
    }

    /**
     * Send, then wait. Pass the [lastValidBlockHeight] from the [Blockhash] the
     * transaction was signed over; without it the wait is bounded by the
     * blockhash's nominal 150-block life measured from now, which is looser
     * but never wrong by more than the time the caller sat on the signed bytes.
     */
    fun sendAndConfirm(tx: Transaction, lastValidBlockHeight: Long? = null, skipPreflight: Boolean = false): String {
        val bound = lastValidBlockHeight ?: (getBlockHeight() + BLOCKHASH_LIFETIME)
        val signature = sendTransaction(tx, skipPreflight)
        confirm(signature, bound)
        return signature
    }

    /**
     * One POST. Non-2xx reads the error stream (the node puts a JSON-RPC
     * envelope there for 429 too, which is worth quoting); 2xx is handed back
     * as text for the parsers, after the envelope check so an error never
     * reaches a parser looking for `result`.
     */
    fun call(method: String, params: JSONArray = JSONArray(), readTimeoutMs: Int = READ_TIMEOUT_MS): String {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
            .toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = readTimeoutMs
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = try {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                } catch (_: IOException) {
                    ""
                }
                val detail = errorOf(text)?.message ?: text.take(200).trim()
                val reason = if (status == 429) "rate limited" else "HTTP $status"
                val suffix = if (detail.isEmpty()) "" else " · $detail"
                val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
                throw RpcException(
                    "${cluster.display} refused $method: $reason$suffix",
                    httpStatus = status,
                    retryAfterSeconds = retryAfter,
                )
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            errorOf(text)?.let { throw it }
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_STATUS_BATCH = 256
        private const val READ_TIMEOUT_MS = 60_000
        private const val AIRDROP_TIMEOUT_MS = 20_000
        private const val SEND_MAX_RETRIES = 5
        private const val CONFIRM_POLL_MS = 1_500L
        private const val BLOCKHASH_LIFETIME = 150L
        private const val LOG_LINES_QUOTED = 3

        // ---- pure parsers -------------------------------------------------

        /** `getAccountInfo`: null for a null `value` (no account at that address). */
        fun parseAccountInfo(json: String): AccountInfo? {
            val result = resultOf(json) as? JSONObject ?: throw malformed("getAccountInfo")
            val value = result.optJSONObject("value") ?: return null
            val dataField = value.opt("data")
            val encoded = when (dataField) {
                is JSONArray -> dataField.optString(0, "")
                is String -> dataField
                else -> ""
            }
            val data = if (encoded.isEmpty()) ByteArray(0) else encoded.fromBase64()
            val owner = Pubkey.ofOrNull(value.optString("owner")) ?: throw malformed("getAccountInfo owner")
            return AccountInfo(
                lamports = value.optLong("lamports"),
                owner = owner,
                data = data,
                executable = value.optBoolean("executable", false),
                space = if (value.has("space")) value.optLong("space") else data.size.toLong(),
            )
        }

        fun parseBlockhash(json: String): Blockhash {
            val result = resultOf(json) as? JSONObject ?: throw malformed("getLatestBlockhash")
            val value = result.optJSONObject("value") ?: throw malformed("getLatestBlockhash value")
            val hash = value.optString("blockhash")
            if (hash.isEmpty()) throw malformed("getLatestBlockhash blockhash")
            return Blockhash(hash, value.optLong("lastValidBlockHeight"))
        }

        fun parseStatuses(json: String): List<SignatureStatus?> {
            val result = resultOf(json) as? JSONObject ?: throw malformed("getSignatureStatuses")
            val value = result.optJSONArray("value") ?: throw malformed("getSignatureStatuses value")
            return List(value.length()) { index ->
                val row = value.optJSONObject(index) ?: return@List null
                val err = row.opt("err")
                SignatureStatus(
                    slot = row.optLong("slot"),
                    confirmations = if (row.isNull("confirmations")) null else row.optLong("confirmations"),
                    err = if (err == null || err == JSONObject.NULL) null else err.toString(),
                    confirmationStatus = if (row.isNull("confirmationStatus")) null else row.optString("confirmationStatus"),
                )
            }
        }

        /** A bare number result, or `{context, value}` where value is the number (getBalance). */
        fun parseResultLong(json: String): Long {
            return when (val result = resultOf(json)) {
                is Number -> result.toLong()
                is JSONObject -> {
                    val value = result.opt("value")
                    (value as? Number)?.toLong() ?: throw malformed("numeric value")
                }
                else -> throw malformed("number")
            }
        }

        fun parseResultString(json: String): String {
            val result = resultOf(json)
            return (result as? String)?.takeIf { it.isNotEmpty() } ?: throw malformed("string")
        }

        /**
         * The JSON-RPC error envelope as an exception, or null when the text has
         * no `error` member (including when it is not JSON at all: that is a
         * different complaint, raised by [resultOf]). Program logs, when the
         * node attached them, are the actual reason a deploy step failed, so
         * the first few are folded into the message.
         */
        fun errorOf(json: String): RpcException? {
            val root = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            val error = root.optJSONObject("error") ?: return null
            val code = if (error.has("code") && !error.isNull("code")) error.optInt("code") else null
            val message = error.optString("message").ifEmpty { "RPC error" }
            val logs = error.optJSONObject("data")?.optJSONArray("logs")
            val quoted = if (logs != null && logs.length() > 0) {
                val lines = (0 until minOf(logs.length(), LOG_LINES_QUOTED)).map { logs.optString(it) }
                val more = if (logs.length() > LOG_LINES_QUOTED) " …" else ""
                " · " + lines.joinToString(" · ") + more
            } else {
                ""
            }
            return RpcException(message + quoted, code = code)
        }

        /** The `result` member, or the envelope's error thrown, or a complaint that this is not JSON-RPC. */
        private fun resultOf(json: String): Any {
            errorOf(json)?.let { throw it }
            val root = try {
                JSONObject(json)
            } catch (_: JSONException) {
                throw RpcException("The node answered something that is not JSON-RPC: ${json.take(120).trim()}")
            }
            if (!root.has("result") || root.isNull("result")) throw RpcException("The node answered without a result")
            return root.get("result")
        }

        private fun malformed(what: String) = RpcException("Unexpected shape in the node's $what answer")
    }
}

/**
 * The gate every request to a public endpoint goes through during a deploy.
 *
 * A sliding ten-second window (default 35, under the 40-per-method public
 * limit) plus an in-flight cap, both fair in arrival order; and a retry of
 * up to five more attempts, 1 s / 2 s / 5 s / 10 s / 15 s apart — or exactly
 * as long as a 429's `Retry-After` asks — on the answers that mean "not
 * now": HTTP 429 (and the gateway's 502/503/504), RPC `-32005`, and a
 * dropped socket. Anything
 * else (a simulation failure, a bad signature) is thrown straight through,
 * because retrying it would only burn the window.
 *
 * [run] suspends; the block itself is blocking and is run on `Dispatchers.IO`
 * interruptibly, so cancelling the deploy interrupts a socket read instead of
 * waiting out a 60 s read timeout.
 */
class RpcPacer(
    private val maxPerTenSeconds: Int = 8,
    maxInFlight: Int = 2,
    private val backoffMs: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L),
) {
    private val inFlight = Semaphore(maxInFlight)
    private val window = Mutex()
    private val stamps = ArrayDeque<Long>()

    /**
     * Wall-clock time before which nobody sends. Set from a 429 so that the
     * OTHER coroutines waiting behind the semaphore do not each walk into the
     * same closed door and spend their own retries on it.
     */
    private var holdUntil = 0L

    /**
     * Run [block] in its turn. [retry] off means one attempt: the caller has
     * its own loop, or the call is one whose slowness is the answer (the
     * faucet), and five more tries would only stretch a failure over minutes.
     */
    suspend fun <T> run(retry: Boolean = true, block: () -> T): T = inFlight.withPermit { attempt(retry, block) }

    private suspend fun <T> attempt(retry: Boolean, block: () -> T): T {
        var failures = 0
        while (true) {
            awaitSlot()
            val pause: Long
            try {
                return runInterruptible(Dispatchers.IO) { block() }
            } catch (error: RpcException) {
                if (!retry || !error.isTransient || failures >= backoffMs.size) throw error
                // The endpoint said how long. Believe it, and hold the whole
                // pacer for it, not just this caller.
                pause = error.retryAfterSeconds?.let { it * 1_000L + RETRY_AFTER_GRACE_MS } ?: backoffMs[failures]
                if (error.httpStatus == 429) hold(pause)
            } catch (error: IOException) {
                if (!retry || failures >= backoffMs.size) throw error
                pause = backoffMs[failures]
            }
            delay(pause)
            failures++
        }
    }

    private suspend fun hold(ms: Long) {
        window.withLock {
            val until = System.currentTimeMillis() + ms
            if (until > holdUntil) holdUntil = until
        }
    }

    /** Waits until fewer than [maxPerTenSeconds] requests started in the last ten seconds, then claims a slot. */
    private suspend fun awaitSlot() {
        while (true) {
            val wait = window.withLock {
                val now = System.currentTimeMillis()
                while (stamps.isNotEmpty() && now - stamps.first() >= WINDOW_MS) stamps.removeFirst()
                when {
                    now < holdUntil -> holdUntil - now
                    stamps.size < maxPerTenSeconds -> {
                        stamps.addLast(now)
                        0L
                    }
                    else -> WINDOW_MS - (now - stamps.first())
                }
            }
            if (wait <= 0L) return
            delay(wait.coerceAtLeast(10L))
        }
    }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val RETRY_AFTER_GRACE_MS = 500L
    }
}
