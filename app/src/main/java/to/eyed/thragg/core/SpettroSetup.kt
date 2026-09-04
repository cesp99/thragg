package to.eyed.thragg.core

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accounts, providers and models — everything the agent needs before it can
 * answer anything, and nothing about a conversation.
 *
 * The problem this file exists for is specific. A phone user has never run
 * Spettro: there is no `~/.spettro`, no API key, no TUI to fall back to. The
 * agent starts fine, handshakes fine, creates a session fine — and then fails
 * at the first prompt with a provider error nobody can act on. So the moment
 * the agent is up, before a session is created, `_spettro/providers/list` is
 * asked and the answer decides whether the setup screen is shown
 * (docs/SPETTRO.md, "First run", step 2).
 *
 * Three rules run through all of it:
 *
 *  1. **The gate fails open.** [SetupGate.NEEDED] requires a *successful*
 *     answer that says nothing is connected. An error, a timeout, an older CLI
 *     answering -32601 — all [SetupGate.UNKNOWN], which never blocks anything.
 *     Not knowing must never cost someone access to the rest of the app.
 *  2. **API keys are write-only.** A key is posted to
 *     `_spettro/providers/connect`, which verifies it against the provider's
 *     own API and stores it in the CLI's encrypted `~/.spettro/keys.enc`.
 *     Nothing on this protocol ever hands one back, and nothing on this side
 *     ever keeps, caches or logs one. The parameter that carries it is the
 *     only place it exists in this process.
 *  3. **Decoding is lenient in one direction only.** A missing boolean is
 *     false, a missing display name falls back to the id, a missing login
 *     status is `idle` — but a *failed call* is never decoded into an empty
 *     list, because an empty list is a fact ("you have no providers") and a
 *     failure is not.
 *
 * Every call blocks in the bridge for up to 45 seconds and therefore runs on
 * `Dispatchers.IO`; the suspend functions here do that themselves, so a
 * composable may call them from its own scope.
 */
object SpettroSetup {

    private const val TAG = "seeker-spettro"

    // --- what the screens read -------------------------------------------------

    /** The last successful `_spettro/providers/list`, or null before the first. */
    var providers by mutableStateOf<ProvidersList?>(null)
        private set

    /** The last `_spettro/models/list`. Empty until asked for; not a gate. */
    var models by mutableStateOf<List<ModelEntry>>(emptyList())
        private set

    /**
     * The account, from `_spettro/account/status` and from every
     * `_spettro/account/update` the agent pushes afterwards.
     */
    var account by mutableStateOf<AccountStatus?>(null)
        private set

    /** Whether the setup screen should be in the way. See [SetupGate]. */
    var gate by mutableStateOf(SetupGate.UNKNOWN)
        private set

    /**
     * Dismissed for this run of the app — "Skip for now".
     *
     * Not persisted, deliberately: the next launch asks again, because a user
     * who skipped setup has an agent that cannot answer and that is worth one
     * more question. What it does buy is the banner above the composer instead
     * of a full-screen takeover for the rest of the session.
     */
    var skipped by mutableStateOf(false)
        private set

    /** The device-flow login, while one is in flight. */
    var login by mutableStateOf<LoginStatus?>(null)
        private set

    /**
     * True when the last login failure was the transport and not the account:
     * the extension call answered Offline — no agent process, or a process
     * that died and cannot be reached. The sheet keys its "start the agent"
     * recovery off this rather than off `projectId < 0` alone, because a
     * *died* agent leaves projectId non-negative while being exactly as
     * unreachable as one that never started.
     */
    var loginOffline by mutableStateOf(false)
        private set

    /**
     * The last thing that went wrong, in the words it arrived in.
     *
     * Provider errors are shown **verbatim**: "key rejected (401)" is the
     * provider's own text, written to be read by the person who typed the key,
     * and wrapping it in a sentence of ours only makes it longer and vaguer.
     */
    var lastError by mutableStateOf<String?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- the gate ---------------------------------------------------------------

    /**
     * Ask `_spettro/providers/list` and recompute [gate]. Returns the new
     * gate so a caller can branch on it without a recomposition.
     *
     * Called immediately after the handshake and again after anything that
     * could have connected something: a key accepted, a login completed, a
     * local endpoint added, a first prompt that failed with a provider error.
     */
    suspend fun refreshProviders(): SetupGate {
        val result = AgentSessions.callExtension("_spettro/providers/list")
        when (result) {
            is ExtResult.Ok -> {
                val list = ProvidersList.parse(result.result)
                providers = list
                gate = setupGate(list, account)
                Log.i(
                    TAG,
                    "providers/list: ${list.providers.count { it.connected }} keyed, " +
                        "${list.local.size} local, subscription=" +
                        "${list.subscription?.connected == true}/" +
                        "${list.subscription?.modelCount ?: 0} models -> $gate",
                )
            }
            else -> {
                // FAIL OPEN. We do not know, and not knowing is not "no".
                Log.i(TAG, "providers/list did not answer; the setup gate stays unknown")
                gate = SetupGate.UNKNOWN
            }
        }
        return gate
    }

    /**
     * Everything the agent screen needs the moment the handshake lands.
     *
     * [refreshProviders] first, because it is the gate and it is quick. Then,
     * if the subscription is signed in, [refreshAccount] — and this call is
     * not optional, it is what puts the plan's models into the agent at all.
     * Spettro keeps the subscription's model list **only in the agent
     * process's memory**: nothing under `~/.spettro` holds it, and in ACP
     * mode the only thing that fetches it from the backend and registers it
     * is `_spettro/account/status` (the TUI does the same at its own
     * startup). A freshly spawned agent therefore knows *no* subscription
     * models, `providers/list` reports the subscription with `modelCount: 0`,
     * and the session's model dropdown carries nothing but the active model —
     * on every launch, until something asks. This asks.
     *
     * The providers are read again afterwards so the subscription card's
     * model count is the truth and not the pre-fetch zero.
     */
    suspend fun refreshOnHandshake(): SetupGate {
        refreshProviders()
        if (providers?.subscription?.connected == true) {
            refreshAccount()
            refreshProviders()
        }
        return gate
    }

    /**
     * Why the subscription's models are missing from the pickers — or null
     * when they are there, or when there is no subscription to speak of.
     *
     * Two different sentences for two different facts: a `stale` status is a
     * backend the agent could not reach (the phone is offline, or the guest's
     * DNS is), and the plan is very probably fine; a fresh status with zero
     * models is the plan itself. Conflating them sends a user with no Wi-Fi to
     * the pricing page.
     */
    val subscriptionModelsNote: String?
        get() {
            val status = account ?: return null
            if (!status.signedIn || status.modelCount > 0) return null
            return if (status.stale) {
                "Spettro's models could not be loaded: the agent could not reach " +
                    "Spettro. Check the connection and retry."
            } else {
                "Your Spettro plan has no models available right now."
            }
        }

    /**
     * "Skip for now": stop blocking, and let the panel put the honest banner
     * above the composer instead. The gate itself is left alone, so a later
     * refresh can still flip it to SATISFIED on its own.
     */
    fun skip() {
        skipped = true
    }

    /** Put the gate back in the way — a first prompt that failed on a provider. */
    fun unskip() {
        skipped = false
    }

    /** Whether the setup screen should be showing right now. */
    val isBlocking: Boolean get() = gate == SetupGate.NEEDED && !skipped

    /** Whether the composer should carry the "no model connected" banner. */
    val needsBanner: Boolean get() = gate == SetupGate.NEEDED && skipped

    fun clearError() {
        lastError = null
    }

    // --- providers ---------------------------------------------------------------

    /**
     * Post an API key. **This is the only place a key exists on this side**,
     * and it exists only for the length of this call.
     *
     * Up to 30 seconds, because the key is verified against the provider's own
     * API *before* it is persisted — a bad key is never written to disk. The
     * caller must keep its field disabled rather than time out; the bridge's
     * own allowance is 45 s.
     *
     * `activate: true` is exactly what first run wants: it also sets the
     * active provider and model, so the very next prompt has something to go
     * to.
     */
    suspend fun connectProvider(providerId: String, apiKey: String): ConnectOutcome {
        lastError = null
        val params = JSONObject()
            .put("providerId", providerId)
            .put("apiKey", apiKey)
            .put("activate", true)
        return when (val result = AgentSessions.callExtension(
            "_spettro/providers/connect",
            params.toString(),
        )) {
            is ExtResult.Ok -> {
                val models = result.result.optInt("modelCount", 0)
                refreshProviders()
                // A key that verified just changed what the open session can
                // talk to; its model dropdown does not find out on its own.
                refreshModelWorldSoon()
                ConnectOutcome.Connected(models, result.result.optString("activeModel").ifBlank { null })
            }
            is ExtResult.Rpc -> {
                lastError = result.message
                ConnectOutcome.Rejected(result.message)
            }
            ExtResult.Unsupported -> {
                lastError = UPDATE_SPETTRO
                ConnectOutcome.Rejected(UPDATE_SPETTRO)
            }
            is ExtResult.Offline -> {
                lastError = result.message
                ConnectOutcome.Rejected(result.message)
            }
        }
    }

    /** Forget a provider's key. The gate is recomputed, since this can open it. */
    suspend fun disconnectProvider(providerId: String): Boolean {
        val params = JSONObject().put("providerId", providerId).toString()
        val ok = AgentSessions.callExtension("_spettro/providers/disconnect", params) is ExtResult.Ok
        if (ok) {
            refreshProviders()
            // The model world shrank, and shrinking is the case that matters
            // most: a dropdown offering a model whose key just left routes the
            // next turn into an auth error. Same nudge as connecting.
            refreshModelWorldSoon()
        }
        return ok
    }

    /**
     * Look at a local endpoint without saving anything — Ollama or LM Studio,
     * on the device or on the LAN.
     *
     * Zero models is an *error* rather than an empty success: an endpoint that
     * answered but has nothing loaded would otherwise be added and then fail
     * at the first prompt, which is the failure this whole file exists to
     * prevent.
     */
    suspend fun probeLocal(endpoint: String, apiKey: String?): LocalProbe {
        lastError = null
        val params = JSONObject().put("endpoint", endpoint)
        if (!apiKey.isNullOrBlank()) params.put("apiKey", apiKey)
        return when (val result = AgentSessions.callExtension(
            "_spettro/providers/local/probe",
            params.toString(),
        )) {
            is ExtResult.Ok -> {
                val names = result.result.optJSONArray("models").strings()
                if (names.isEmpty()) {
                    LocalProbe.Failed("That endpoint returned no models.")
                } else {
                    LocalProbe.Found(names)
                }
            }
            is ExtResult.Rpc -> LocalProbe.Failed(result.message)
            ExtResult.Unsupported -> LocalProbe.Failed(UPDATE_SPETTRO)
            is ExtResult.Offline -> LocalProbe.Failed(result.message)
        }
    }

    /** Probe, register and persist a local endpoint. Same params as [probeLocal]. */
    suspend fun addLocal(endpoint: String, apiKey: String?): LocalProbe {
        lastError = null
        val params = JSONObject().put("endpoint", endpoint)
        if (!apiKey.isNullOrBlank()) params.put("apiKey", apiKey)
        return when (val result = AgentSessions.callExtension(
            "_spettro/providers/local/add",
            params.toString(),
        )) {
            is ExtResult.Ok -> {
                refreshProviders()
                LocalProbe.Found(result.result.optJSONArray("models").strings())
            }
            is ExtResult.Rpc -> LocalProbe.Failed(result.message).also { lastError = result.message }
            ExtResult.Unsupported -> LocalProbe.Failed(UPDATE_SPETTRO).also { lastError = UPDATE_SPETTRO }
            is ExtResult.Offline -> LocalProbe.Failed(result.message).also { lastError = result.message }
        }
    }

    /** Remove one local endpoint. Keyed by endpoint, which is its identity. */
    suspend fun removeLocal(endpoint: String): Boolean {
        val params = JSONObject().put("endpoint", endpoint).toString()
        val ok = AgentSessions.callExtension("_spettro/providers/local/remove", params) is ExtResult.Ok
        if (ok) refreshProviders()
        return ok
    }

    // --- models -------------------------------------------------------------------

    /** The model list, for Settings → Spettro. Not on the first-run path. */
    suspend fun refreshModels() {
        val result = AgentSessions.callExtension("_spettro/models/list")
        // `models` normally, `result` when the method answered with a bare
        // array — ExtResult wraps a non-object result under that key rather
        // than dropping it.
        val json = result.objectOrNull ?: return
        val array = json.optJSONArray("models") ?: json.optJSONArray("result") ?: return
        models = List(array.length()) { ModelEntry.parse(array.optJSONObject(it)) }
    }

    /**
     * The model world changed — a login finished, a key was accepted, or the
     * agent pushed an account update with a different model count. Two lists
     * go stale at that moment and neither refreshes itself:
     *
     *  - the open session's config options (the model chip's dropdown), which
     *    the CLI never re-advertises on its own —
     *    [AgentSessions.refreshConfigOptions] explains the round-trip trick;
     *  - [models], which the favourites screen reads.
     *
     * Fire-and-forget on [scope] rather than awaited, because every caller is
     * on its way to telling the user something better ("Connected", "Signed
     * in") and a dropdown refresh must not hold that up. Both calls already
     * drop failures silently — a stale list is a nuisance, not an error.
     */
    private fun refreshModelWorldSoon() {
        scope.launch {
            // The session may still be being created when this fires from
            // the handshake path: the config-option refresh needs it to
            // exist, and bailing now would leave the dropdown stale until
            // the next event. A short wait, not a long one — a session that
            // never comes is the agent screen's problem, not this one's.
            repeat(SESSION_WAIT_TICKS) {
                if (AgentSessions.sessionId >= 0) return@repeat
                delay(SESSION_WAIT_TICK_MS)
            }
            AgentSessions.refreshConfigOptions()
            refreshModels()
        }
    }

    private const val SESSION_WAIT_TICKS = 20
    private const val SESSION_WAIT_TICK_MS = 500L

    /** Star or unstar a model. The list is re-read, since the flag is the agent's. */
    suspend fun favouriteModel(provider: String, name: String, favourite: Boolean) {
        val params = JSONObject()
            .put("provider", provider)
            .put("name", name)
            .put("favorite", favourite)
            .toString()
        if (AgentSessions.callExtension("_spettro/models/favorite", params) is ExtResult.Ok) {
            refreshModels()
        }
    }

    // --- account and the device flow ------------------------------------------------

    /**
     * Read the account. Blocks up to 15 s agent-side while it asks the backend.
     *
     * A successful read is also the moment the agent registers the plan's
     * models (see [refreshOnHandshake]), so when the count moved the open
     * session's dropdown and the favourites list are refreshed here, and the
     * gate is recomputed — a plan that turns out to expose nothing is a gate
     * that should close. Returns whether the model world moved, so a caller
     * that would otherwise refresh it again can skip that.
     */
    suspend fun refreshAccount(): Boolean {
        val result = AgentSessions.callExtension("_spettro/account/status")
        val json = result.objectOrNull ?: return false
        val next = AccountStatus.parse(json)
        val moved = modelWorldChanged(account, next)
        account = next
        providers?.let { gate = setupGate(it, next) }
        Log.i(
            TAG,
            "account/status: signedIn=${next.signedIn} plan=${next.plan} " +
                "models=${next.modelCount} stale=${next.stale} moved=$moved -> $gate",
        )
        if (moved) refreshModelWorldSoon()
        return moved
    }

    /** Sign out. Providers are unaffected; the gate is recomputed anyway. */
    suspend fun signOut(): Boolean {
        val ok = AgentSessions.callExtension("_spettro/account/logout") is ExtResult.Ok
        if (ok) {
            account = null
            refreshProviders()
            // Plan models leave the catalogue with the key; without this an
            // open session keeps offering them until its next natural
            // round trip, and picking one fails a turn later.
            refreshModelWorldSoon()
        }
        return ok
    }

    /**
     * How long a device-flow login may stay open. The agent's own poller has
     * the same ceiling; this one is the client's, so a sheet cannot outlive
     * the code it is showing.
     */
    private const val LOGIN_CEILING_MS = 10 * 60 * 1000L

    /** The local mirror read, as a dropped-notification safety net. */
    private const val LOGIN_POLL_MS = 2_000L

    /** Consecutive failed local polls before the flow gives up. */
    private const val LOGIN_POLL_FAILURES = 3

    /**
     * Superseded flows bow out. A second *Sign in* tap cancels the first
     * server-side; this makes the first tap's timer stop writing over the
     * second one's state on the way out.
     */
    @Volatile
    private var loginGeneration = 0

    private var loginJob: Job? = null

    /**
     * Begin the browser sign-in and drive it to an answer.
     *
     * The **agent** owns the poller: it asks the backend every two seconds and
     * pushes `_spettro/account/update` notifications as the state moves, which
     * the engine caches ([CoreBridge.acpAccountStatus]). The phone must never
     * poll the backend itself.
     *
     * What runs here is two cheap local reads on the same two-second beat: the
     * cached notification, and `_spettro/account/login/poll`, which is a read
     * of the agent's own memory rather than a network call. Belt and braces —
     * a single dropped notification would otherwise strand the sheet on a
     * spinner for ten minutes.
     *
     * [onBrowserUrl] is called once, with the URL to open, before the loop
     * starts.
     */
    fun startLogin(context: android.content.Context, onBrowserUrl: (String) -> Unit) {
        val app = context.applicationContext
        val mine = ++loginGeneration
        loginJob?.cancel()
        lastError = null
        loginOffline = false
        login = LoginStatus(loginId = null, status = "starting", browserUrl = null, error = null)
        loginJob = scope.launch {
            // Opening the browser *guarantees* this app goes to the
            // background for the length of the sign-in, and on the Seeker a
            // backgrounded app's guest lost its network mid-poll — the loop
            // below watched `context deadline exceeded` arrive while the
            // browser was already showing "CLI authenticated". The terminal's
            // foreground service is the same shield every long guest run
            // uses; per-generation tag, so a second Sign-in tap's hold is
            // never released by the first tap's teardown.
            val hold = "login#$mine"
            runCatching {
                to.eyed.thragg.terminal.TerminalSessions.of(app)
                    .holdForBackgroundWork(hold, true)
            }
            try {
                val started = AgentSessions.callExtension("_spettro/account/login/start")
                val json = when (started) {
                    is ExtResult.Ok -> started.result
                    ExtResult.Unsupported -> return@launch failLogin(mine, UPDATE_SPETTRO)
                    is ExtResult.Rpc -> return@launch failLogin(mine, started.message)
                    is ExtResult.Offline -> {
                        loginOffline = true
                        return@launch failLogin(mine, started.message)
                    }
                }
                if (loginGeneration != mine) return@launch
                val status = LoginStatus.parse(json)
                login = status
                val url = status.browserUrl
                if (url.isNullOrBlank()) {
                    // The agent often *does* say why — the backend's own words
                    // ride in `error` — and a generic line where a reason exists
                    // sent a real on-device failure off to be guessed at. Say
                    // what it said; fall back to the generic line only when it
                    // truly said nothing.
                    return@launch failLogin(
                        mine,
                        status.error ?: "Spettro did not give a sign-in link to open.",
                    )
                }
                withContext(Dispatchers.Main) { onBrowserUrl(url) }
                watchLogin(mine)
            } finally {
                runCatching {
                    to.eyed.thragg.terminal.TerminalSessions.of(app)
                        .holdForBackgroundWork(hold, false)
                }
            }
        }
    }

    private suspend fun watchLogin(mine: Int) {
        val deadline = System.currentTimeMillis() + LOGIN_CEILING_MS
        var failures = 0
        var seenAccountVersion = -1L
        while (loginGeneration == mine && System.currentTimeMillis() < deadline) {
            delay(LOGIN_POLL_MS)
            if (loginGeneration != mine) return

            // 1. What the agent pushed. This is the authoritative channel and
            //    it carries the whole refreshed account on completion — plan,
            //    credits, model count — not just a status word.
            val version = runCatching { CoreBridge.acpAccountVersion() }.getOrDefault(0L)
            if (version != 0L && version != seenAccountVersion) {
                seenAccountVersion = version
                val pushed = runCatching { CoreBridge.acpAccountStatus() }.getOrNull()
                val json = pushed?.takeIf { it.isNotBlank() && it != "null" }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (json != null) {
                    val status = AccountStatus.parse(json)
                    // Decided against the account *before* this push replaces
                    // it — afterwards old and new are the same object.
                    val modelsMoved = modelWorldChanged(account, status)
                    account = status
                    status.login?.let { login = it }
                    if (status.signedIn || status.login?.status == "complete") {
                        // finishLogin refreshes the model world itself; doing
                        // it here too would round-trip the agent twice.
                        return finishLogin(mine)
                    }
                    if (modelsMoved) refreshModelWorldSoon()
                }
            }

            // 2. The local mirror, in case a notification never arrived.
            when (val polled = AgentSessions.callExtension("_spettro/account/login/poll")) {
                is ExtResult.Ok -> {
                    failures = 0
                    val status = LoginStatus.parse(polled.result)
                    if (loginGeneration != mine) return
                    login = status
                    when (status.status) {
                        "complete" -> return finishLogin(mine)
                        "expired", "cancelled" -> return
                        "error" -> return failLogin(mine, status.error ?: "The sign-in failed.")
                    }
                }
                else -> {
                    failures++
                    if (failures >= LOGIN_POLL_FAILURES) {
                        return failLogin(
                            mine,
                            "Thragg lost contact with the agent while signing in.",
                        )
                    }
                }
            }
        }
        if (loginGeneration == mine && login?.status != "complete") {
            failLogin(mine, "The sign-in link expired. Start again when you are ready.")
        }
    }

    private suspend fun finishLogin(mine: Int) {
        if (loginGeneration != mine) return
        login = LoginStatus(login?.loginId, "complete", null, null)
        val moved = refreshAccount()
        refreshProviders()
        // The login just added the plan's models; the session that was open
        // through it is still advertising the pre-login list. The account
        // read refreshes it itself when it saw the count move; when the
        // pushed update had already carried the new count, it did not.
        if (!moved) refreshModelWorldSoon()
    }

    private fun failLogin(mine: Int, message: String) {
        if (loginGeneration != mine) return
        login = LoginStatus(login?.loginId, "error", null, message)
        lastError = message
    }

    /**
     * Cancel a login in flight. Also called implicitly by starting a second
     * one, which the backend treats the same way.
     */
    fun cancelLogin() {
        loginGeneration++
        loginJob?.cancel()
        loginJob = null
        login = null
        scope.launch { AgentSessions.callExtension("_spettro/account/login/cancel") }
    }

    /** What an old CLI gets told, everywhere, in the same words. */
    const val UPDATE_SPETTRO =
        "This build of Spettro is too old for that. Update it from Setup."
}

// ---------------------------------------------------------------------------
// The shapes on the wire
// ---------------------------------------------------------------------------

/**
 * Whether the app knows the agent has something to talk to.
 *
 * Three states rather than two, and the third is the important one:
 * [UNKNOWN] is what every failure decodes to, and it never blocks.
 */
enum class SetupGate {
    /** Not asked yet, or the ask failed. Never blocks anything. */
    UNKNOWN,

    /** Asked, answered, and nothing is connected. The setup screen is shown. */
    NEEDED,

    /** Asked, answered, something is connected. */
    SATISFIED,
}

/** One provider Spettro knows how to talk to. */
data class ProviderEntry(
    val id: String,
    val name: String,
    /** The environment variable it would read a key from, when it has one. */
    val envKey: String?,
    val connected: Boolean,
    /** The agent's own hint that this is a good first choice. */
    val suggested: Boolean,
    val modelCount: Int,
) {
    companion object {
        fun parse(json: JSONObject?): ProviderEntry {
            val id = json?.optString("id").orEmpty()
            return ProviderEntry(
                id = id,
                name = json?.optString("name")?.takeIf { it.isNotBlank() } ?: id,
                envKey = json?.optString("envKey")?.takeIf { it.isNotBlank() },
                connected = json?.optBoolean("connected", false) ?: false,
                suggested = json?.optBoolean("suggested", false) ?: false,
                modelCount = json?.optInt("modelCount", 0) ?: 0,
            )
        }
    }
}

/** An Ollama or LM Studio endpoint, on the device or on the LAN. */
data class LocalEndpoint(
    val endpoint: String,
    val name: String,
    val hasKey: Boolean,
    val modelCount: Int,
) {
    companion object {
        fun parse(json: JSONObject?): LocalEndpoint {
            val endpoint = json?.optString("endpoint").orEmpty()
            return LocalEndpoint(
                endpoint = endpoint,
                // A local endpoint that never got a name is named after
                // itself; a blank row in a list of one is indistinguishable
                // from a bug.
                name = json?.optString("name")?.takeIf { it.isNotBlank() } ?: endpoint,
                hasKey = json?.optBoolean("hasKey", false) ?: false,
                modelCount = json?.optInt("modelCount", 0) ?: 0,
            )
        }
    }
}

/**
 * `_spettro/providers/list`, decoded.
 *
 * [subscription] is the Spettro account itself, which is a provider on the
 * wire and a different thing in the UI: it is card 1 of the setup screen, and
 * it must never appear in the API-key grid, where it would ask for a key that
 * does not exist.
 */
data class ProvidersList(
    val providers: List<ProviderEntry>,
    val local: List<LocalEndpoint>,
    val subscription: ProviderEntry?,
) {
    /** The gate's own question, asked once so nobody re-derives it wrongly. */
    val hasSomethingToTalkTo: Boolean
        get() = providers.any { it.connected } ||
            local.isNotEmpty() ||
            subscription?.connected == true

    /**
     * The API-key grid, in the order it is drawn: the five featured providers
     * first and in *that* order, then everything else alphabetically.
     *
     * The order is a recommendation, not a ranking, and it is fixed rather
     * than computed so the grid does not reshuffle between launches. The
     * subscription entry is excluded — it is card 1.
     */
    val keyGrid: List<ProviderEntry>
        get() {
            val featured = FEATURED.withIndex().associate { (index, id) -> id to index }
            return providers
                .filter { it.id != subscription?.id }
                .sortedWith(
                    compareBy(
                        { featured[it.id] ?: FEATURED.size },
                        { it.name.lowercase() },
                    ),
                )
        }

    companion object {
        /** docs/SPETTRO.md, "Card 2 — Own API key": these five, in this order. */
        val FEATURED = listOf("anthropic", "openai", "mistral", "x-ai", "zai")

        fun parse(json: JSONObject): ProvidersList {
            val providers = json.optJSONArray("providers")
            val local = json.optJSONArray("local")
            return ProvidersList(
                providers = List(providers?.length() ?: 0) {
                    ProviderEntry.parse(providers?.optJSONObject(it))
                }.filter { it.id.isNotEmpty() },
                local = List(local?.length() ?: 0) {
                    LocalEndpoint.parse(local?.optJSONObject(it))
                }.filter { it.endpoint.isNotEmpty() },
                subscription = json.optJSONObject("subscription")?.let(ProviderEntry::parse),
            )
        }
    }
}

/** One model, as `_spettro/models/list` describes it. */
data class ModelEntry(
    val provider: String,
    val providerName: String,
    val name: String,
    val displayName: String,
    val vision: Boolean,
    val reasoning: Boolean,
    val toolCall: Boolean,
    /** Context window in tokens; 0 when the agent does not know. */
    val context: Long,
    val local: Boolean,
    val favorite: Boolean,
    val active: Boolean,
) {
    companion object {
        fun parse(json: JSONObject?): ModelEntry {
            val provider = json?.optString("provider").orEmpty()
            val name = json?.optString("name").orEmpty()
            return ModelEntry(
                provider = provider,
                providerName = json?.optString("providerName")?.takeIf { it.isNotBlank() }
                    ?: provider,
                name = name,
                displayName = json?.optString("displayName")?.takeIf { it.isNotBlank() } ?: name,
                vision = json?.optBoolean("vision", false) ?: false,
                reasoning = json?.optBoolean("reasoning", false) ?: false,
                toolCall = json?.optBoolean("toolCall", false) ?: false,
                context = json?.optLong("context", 0L) ?: 0L,
                local = json?.optBoolean("local", false) ?: false,
                favorite = json?.optBoolean("favorite", false) ?: false,
                active = json?.optBoolean("active", false) ?: false,
            )
        }
    }
}

/**
 * The Spettro account.
 *
 * [stale] is the agent saying "these numbers are the last ones I had" — the
 * backend was unreachable when it last looked. Muted rather than errored: a
 * credit figure from four minutes ago is still worth showing, and an error
 * where a number should be reads as an account problem it is not.
 */
data class AccountStatus(
    val signedIn: Boolean,
    val email: String?,
    val plan: String?,
    val planStatus: String?,
    val creditsUsed: Double?,
    val creditLimit: Double?,
    val remainingCredits: Double?,
    val modelCount: Int,
    val pricingUrl: String?,
    val login: LoginStatus?,
    val stale: Boolean,
) {
    companion object {
        fun parse(json: JSONObject): AccountStatus = AccountStatus(
            signedIn = json.optBoolean("signedIn", false),
            email = json.optString("email").takeIf { it.isNotBlank() },
            plan = json.optString("plan").takeIf { it.isNotBlank() },
            planStatus = json.optString("planStatus").takeIf { it.isNotBlank() },
            creditsUsed = json.optDoubleOrNull("creditsUsed"),
            creditLimit = json.optDoubleOrNull("creditLimit"),
            remainingCredits = json.optDoubleOrNull("remainingCredits"),
            modelCount = json.optInt("modelCount", 0),
            pricingUrl = json.optString("pricingUrl").takeIf { it.isNotBlank() },
            login = json.optJSONObject("login")?.let(LoginStatus::parse),
            stale = json.optBoolean("stale", false),
        )
    }
}

/**
 * Whether an agent-pushed account update means the model list changed under
 * an open session — the one decision behind refreshing it.
 *
 * `modelCount` is the whole test, deliberately. It is the only field of the
 * push that talks about models at all, and every event that changes the list
 * moves it: a plan activating, expiring, upgrading. Credits and plan *names*
 * move without the list moving, and refreshing on those would round-trip the
 * agent on every metering tick. A first-ever status ([previous] null) counts
 * as "was zero", so an update that arrives already carrying models refreshes
 * and one carrying none does not.
 *
 * Top-level and pure so the test does not have to stand up [SpettroSetup]'s
 * state to ask the question.
 */
fun modelWorldChanged(previous: AccountStatus?, next: AccountStatus): Boolean =
    next.modelCount != (previous?.modelCount ?: 0)

/**
 * The gate, from a successful provider list and whatever is known of the
 * account.
 *
 * [ProvidersList.hasSomethingToTalkTo] is the rule; this adds the one case
 * that list cannot see on its own. The subscription is reported *connected*
 * the moment a key is stored, and its model count is only ever non-zero after
 * an account read — so "connected, zero models" says nothing by itself. What
 * decides it is the account: a **fresh** status (not [AccountStatus.stale])
 * carrying zero models is the backend saying the plan has nothing to offer,
 * and a signed-in user with no model is exactly as stuck as a signed-out one.
 * A stale status — the backend unreachable — keeps the gate open, because
 * not knowing is not "no", and a keyed provider or a local endpoint beside
 * the subscription satisfies it regardless.
 *
 * Top-level and pure, like [modelWorldChanged], for the same reason.
 */
fun setupGate(providers: ProvidersList, account: AccountStatus?): SetupGate {
    if (!providers.hasSomethingToTalkTo) return SetupGate.NEEDED
    val subscription = providers.subscription
    val onlyTheSubscription = subscription != null && subscription.connected &&
        providers.providers.none { it.connected && it.id != subscription.id } &&
        providers.local.isEmpty()
    if (onlyTheSubscription && account != null && account.signedIn &&
        !account.stale && account.modelCount == 0
    ) {
        return SetupGate.NEEDED
    }
    return SetupGate.SATISFIED
}

/**
 * Where a browser sign-in has got to.
 *
 * [status] is the agent's own word — `idle`, `starting`, `pending`,
 * `complete`, `expired`, `cancelled`, `error` — kept as a string rather than
 * an enum so a status this build has never heard of arrives intact instead of
 * decoding to "unknown" and stranding the sheet.
 */
data class LoginStatus(
    val loginId: String?,
    val status: String,
    val browserUrl: String?,
    val error: String?,
) {
    val isPending: Boolean get() = status == "starting" || status == "pending"

    companion object {
        fun parse(json: JSONObject): LoginStatus = LoginStatus(
            loginId = json.optString("loginId").takeIf { it.isNotBlank() },
            status = json.optString("status").takeIf { it.isNotBlank() } ?: "idle",
            browserUrl = json.optString("browserUrl").takeIf { it.isNotBlank() },
            error = json.optString("error").takeIf { it.isNotBlank() },
        )
    }
}

/** What came back from [SpettroSetup.connectProvider]. */
sealed interface ConnectOutcome {
    /** The key was verified and written. [modelCount] is what it unlocked. */
    data class Connected(val modelCount: Int, val activeModel: String?) : ConnectOutcome

    /** [message] is the provider's own words, and is shown as they are. */
    data class Rejected(val message: String) : ConnectOutcome
}

/** What came back from a local-endpoint probe or add. */
sealed interface LocalProbe {
    data class Found(val models: List<String>) : LocalProbe
    data class Failed(val message: String) : LocalProbe
}

// --- small JSON manners ------------------------------------------------------

/**
 * A double that is genuinely absent rather than zero.
 *
 * `optDouble` answers `NaN` for a missing key, and a credit balance of NaN
 * rendered as "0.00" would be a number the app invented.
 */
private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.takeUnless { it.isNaN() }
}

/** A JSON array of strings, blanks dropped. Null-safe: absent is empty. */
private fun JSONArray?.strings(): List<String> =
    List(this?.length() ?: 0) { this?.optString(it).orEmpty() }.filter { it.isNotBlank() }
