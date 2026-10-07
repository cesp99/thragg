package to.eyed.thragg.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoders and the gate rule behind the setup screen.
 *
 * The gate is the part worth testing hardest, because it is the one piece of
 * this app that can take the whole thing away from somebody: `NEEDED` blocks
 * the agent screen, and it must be reachable **only** from a successful answer
 * that genuinely says nothing is connected.
 */
class SpettroSetupTest {

    private fun providers(json: String) = ProvidersList.parse(JSONObject(json))

    @Test
    fun nothingConnectedIsTheOnlyShapeThatNeedsSetup() {
        val list = providers(
            """{"providers":[{"id":"anthropic","name":"Anthropic","connected":false}],
                "local":[]}"""
        )
        assertFalse(list.hasSomethingToTalkTo)
    }

    @Test
    fun oneConnectedProviderSatisfiesTheGate() {
        val list = providers(
            """{"providers":[{"id":"anthropic","name":"Anthropic","connected":true,
                             "modelCount":42,"envKey":"ANTHROPIC_API_KEY"}],"local":[]}"""
        )
        assertTrue(list.hasSomethingToTalkTo)
        assertEquals(42, list.providers.single().modelCount)
        assertEquals("ANTHROPIC_API_KEY", list.providers.single().envKey)
    }

    /** A local endpoint is a model too — the whole point of card 3. */
    @Test
    fun aLocalEndpointAloneSatisfiesTheGate() {
        val list = providers(
            """{"providers":[],"local":[{"endpoint":"http://127.0.0.1:11434/v1","modelCount":3}]}"""
        )
        assertTrue(list.hasSomethingToTalkTo)
        // A nameless endpoint is named after itself: a blank row in a list of
        // one is indistinguishable from a bug.
        assertEquals("http://127.0.0.1:11434/v1", list.local.single().name)
    }

    @Test
    fun theSubscriptionCountsAndIsKeptOutOfTheKeyGrid() {
        val list = providers(
            """{"providers":[{"id":"spettro","name":"Spettro","connected":true},
                            {"id":"openai","name":"OpenAI","connected":false}],
                "local":[],
                "subscription":{"id":"spettro","name":"Spettro","connected":true}}"""
        )
        assertTrue(list.hasSomethingToTalkTo)
        // Card 1 owns the subscription; a grid row asking for its API key
        // would ask for a key that does not exist.
        assertTrue(list.keyGrid.none { it.id == "spettro" })
    }

    /**
     * The grid order is a fixed recommendation, not a ranking computed from
     * whatever the agent sent — a grid that reshuffles between launches makes
     * muscle memory impossible.
     */
    @Test
    fun theKeyGridPutsTheFeaturedFiveFirstAndSortsTheRest() {
        val list = providers(
            """{"providers":[{"id":"zed","name":"Zed"},{"id":"openai","name":"OpenAI"},
                            {"id":"anthropic","name":"Anthropic"},{"id":"aws","name":"AWS"},
                            {"id":"zai","name":"Z.ai"},{"id":"x-ai","name":"xAI"},
                            {"id":"mistral","name":"Mistral"}],
                "local":[]}"""
        )
        assertEquals(
            listOf("anthropic", "openai", "mistral", "x-ai", "zai", "aws", "zed"),
            list.keyGrid.map { it.id },
        )
    }

    /** A provider with no id is not a provider; it is a half-written row. */
    @Test
    fun namelessProvidersAreDropped() {
        val list = providers("""{"providers":[{"name":"Mystery"},{"id":"openai"}],"local":[]}""")
        assertEquals(listOf("openai"), list.providers.map { it.id })
        // A provider with no display name falls back to its id rather than
        // rendering an empty chip.
        assertEquals("openai", list.providers.single().name)
    }

    @Test
    fun modelDefaultsFallBackRatherThanBlank() {
        val model = ModelEntry.parse(
            JSONObject("""{"provider":"anthropic","name":"claude-sonnet-4-5"}""")
        )
        assertEquals("anthropic", model.providerName)
        assertEquals("claude-sonnet-4-5", model.displayName)
        assertFalse(model.vision)
        assertFalse(model.favorite)
        assertEquals(0L, model.context)
    }

    /**
     * A missing credit figure is *absent*, not zero. `optDouble` answers NaN
     * for a missing key, and "0.00 credits left" is a number the app would
     * have invented at the worst possible moment.
     */
    @Test
    fun anAbsentCreditFigureStaysAbsent() {
        val account = AccountStatus.parse(JSONObject("""{"signedIn":true,"email":"a@b.c"}"""))
        assertTrue(account.signedIn)
        assertNull(account.remainingCredits)
        assertNull(account.plan)
        assertFalse(account.stale)
    }

    @Test
    fun theAccountCarriesItsLoginAndItsStaleFlag() {
        val account = AccountStatus.parse(
            JSONObject(
                """{"signedIn":false,"stale":true,"creditsUsed":1.5,"creditLimit":20.0,
                    "login":{"loginId":"l-1","status":"pending",
                             "browserUrl":"https://spettro.app/device/ABCD"}}"""
            )
        )
        assertTrue(account.stale)
        assertEquals(1.5, account.creditsUsed!!, 0.0001)
        assertEquals("pending", account.login?.status)
        assertTrue(account.login!!.isPending)
        assertEquals("https://spettro.app/device/ABCD", account.login?.browserUrl)
    }

    /**
     * A status word this build has never heard of arrives intact rather than
     * decoding to "unknown" — an enum here would strand the sheet on a spinner
     * the first time the CLI adds a state.
     */
    @Test
    fun anUnknownLoginStatusIsKeptAsItArrived() {
        val login = LoginStatus.parse(JSONObject("""{"status":"reauthorising"}"""))
        assertEquals("reauthorising", login.status)
        assertFalse(login.isPending)
        // And a login object with nothing in it is `idle`, never blank.
        assertEquals("idle", LoginStatus.parse(JSONObject("{}")).status)
    }

    // --- when does a pushed account update warrant a model refresh? --------

    private fun account(json: String) = AccountStatus.parse(JSONObject(json))

    /**
     * `modelCount` moving — either way — is the refresh trigger: a plan
     * activating grows the list, one expiring shrinks it, and both leave an
     * open session's model dropdown wrong until something round-trips.
     */
    @Test
    fun aMovedModelCountWarrantsARefresh() {
        val before = account("""{"signedIn":true,"modelCount":4}""")
        assertTrue(modelWorldChanged(before, account("""{"signedIn":true,"modelCount":12}""")))
        assertTrue(modelWorldChanged(before, account("""{"signedIn":true,"modelCount":0}""")))
    }

    /**
     * Credits and plan wording move on every metering tick; refreshing on
     * those would round-trip the agent constantly for a list that did not
     * change.
     */
    @Test
    fun creditAndPlanChurnAloneDoesNot() {
        val before = account("""{"signedIn":true,"modelCount":4,"creditsUsed":1.0,"plan":"pro"}""")
        val after = account("""{"signedIn":true,"modelCount":4,"creditsUsed":2.5,"plan":"pro plus"}""")
        assertFalse(modelWorldChanged(before, after))
    }

    /** No previous status reads as "had zero models", in both directions. */
    @Test
    fun theFirstEverStatusComparesAgainstZero() {
        assertTrue(modelWorldChanged(null, account("""{"signedIn":true,"modelCount":7}""")))
        assertFalse(modelWorldChanged(null, account("""{"signedIn":false}""")))
    }

    // --- the gate, with the account in the picture ---------------------------

    private val subscriptionOnly = providers(
        """{"providers":[{"id":"anthropic","name":"Anthropic","connected":false}],
            "local":null,
            "subscription":{"id":"spettro","name":"Spettro","connected":true,"modelCount":0}}"""
    )

    /**
     * What a freshly spawned agent says before anything asked for the
     * account: the subscription is connected and has zero models, because the
     * plan's models live in the agent's memory and nothing has fetched them
     * yet. That is not "no" — it is "not yet".
     */
    @Test
    fun aConnectedSubscriptionWithNoAccountReadYetIsSatisfied() {
        assertEquals(SetupGate.SATISFIED, setupGate(subscriptionOnly, null))
    }

    /** The backend could not be reached: the plan is probably fine. Stay open. */
    @Test
    fun aStaleAccountWithNoModelsKeepsTheGateOpen() {
        val offline = account("""{"signedIn":true,"plan":"max","modelCount":0,"stale":true}""")
        assertEquals(SetupGate.SATISFIED, setupGate(subscriptionOnly, offline))
    }

    /** The backend answered, and the answer was "nothing": that IS "no". */
    @Test
    fun aFreshAccountWithNoModelsClosesTheGate() {
        val empty = account("""{"signedIn":true,"plan":"free","modelCount":0}""")
        assertEquals(SetupGate.NEEDED, setupGate(subscriptionOnly, empty))
        val plenty = account("""{"signedIn":true,"plan":"max","modelCount":9}""")
        assertEquals(SetupGate.SATISFIED, setupGate(subscriptionOnly, plenty))
    }

    /** An empty plan beside a keyed provider or a local model is not stuck. */
    @Test
    fun anEmptyPlanBesideAnotherConnectionIsSatisfied() {
        val empty = account("""{"signedIn":true,"plan":"free","modelCount":0}""")
        val keyed = providers(
            """{"providers":[{"id":"anthropic","name":"Anthropic","connected":true}],
                "local":[],
                "subscription":{"id":"spettro","name":"Spettro","connected":true}}"""
        )
        assertEquals(SetupGate.SATISFIED, setupGate(keyed, empty))
        val local = providers(
            """{"providers":[],
                "local":[{"endpoint":"http://127.0.0.1:11434/v1","modelCount":1}],
                "subscription":{"id":"spettro","name":"Spettro","connected":true}}"""
        )
        assertEquals(SetupGate.SATISFIED, setupGate(local, empty))
    }

    /** Nothing connected at all is NEEDED whatever the account says. */
    @Test
    fun nothingConnectedIsNeededRegardlessOfTheAccount() {
        val none = providers("""{"providers":[],"local":[]}""")
        assertEquals(SetupGate.NEEDED, setupGate(none, null))
        assertEquals(
            SetupGate.NEEDED,
            setupGate(none, account("""{"signedIn":true,"modelCount":9}""")),
        )
    }

    // --- a configured local endpoint that is not answering --------------------

    private val deadLocal = """{"endpoint":"http://127.0.0.1:11434","name":"Ollama","modelCount":0}"""

    /**
     * The device's own failure: signed out, and the only "model" a stale
     * Ollama endpoint whose startup probe registered nothing. Listed is not
     * reachable; the first prompt would fail with "connection refused".
     */
    @Test
    fun aLocalEndpointWithNoModelsDoesNotSatisfyTheGate() {
        val list = providers("""{"providers":[],"local":[$deadLocal]}""")
        assertFalse(list.hasSomethingToTalkTo)
        assertEquals(SetupGate.NEEDED, setupGate(list, null))
        assertEquals(listOf("http://127.0.0.1:11434"), list.deadLocal.map { it.endpoint })
        assertTrue(list.usableLocal.isEmpty())

        // An absent count is the lenient default, zero: not something to talk to.
        val uncounted = providers("""{"providers":[],"local":[{"endpoint":"http://127.0.0.1:11434"}]}""")
        assertFalse(uncounted.hasSomethingToTalkTo)
        assertEquals(SetupGate.NEEDED, setupGate(uncounted, null))
    }

    @Test
    fun aDeadLocalBesideAKeyedProviderIsSatisfied() {
        val list = providers(
            """{"providers":[{"id":"anthropic","name":"Anthropic","connected":true}],
                "local":[$deadLocal]}"""
        )
        assertEquals(SetupGate.SATISFIED, setupGate(list, null))
    }

    @Test
    fun aDeadLocalBesideALiveSubscriptionIsSatisfied() {
        val list = providers(
            """{"providers":[],"local":[$deadLocal],
                "subscription":{"id":"spettro","name":"Spettro","connected":true}}"""
        )
        assertEquals(SetupGate.SATISFIED, setupGate(list, null))
        assertEquals(
            SetupGate.SATISFIED,
            setupGate(list, account("""{"signedIn":true,"plan":"max","modelCount":9}""")),
        )
    }

    /** A dead endpoint must not hide an empty plan the way a live one does. */
    @Test
    fun aDeadLocalDoesNotHideAnEmptyPlan() {
        val list = providers(
            """{"providers":[],"local":[$deadLocal],
                "subscription":{"id":"spettro","name":"Spettro","connected":true}}"""
        )
        val empty = account("""{"signedIn":true,"plan":"free","modelCount":0}""")
        assertEquals(SetupGate.NEEDED, setupGate(list, empty))
    }

    @Test
    fun oneLiveOneDeadLocalIsSatisfied() {
        val list = providers(
            """{"providers":[],"local":[$deadLocal,
                {"endpoint":"http://192.168.1.5:11434","modelCount":2}]}"""
        )
        assertEquals(SetupGate.SATISFIED, setupGate(list, null))
        assertEquals(listOf("http://127.0.0.1:11434"), list.deadLocal.map { it.endpoint })
        assertEquals(listOf("http://192.168.1.5:11434"), list.usableLocal.map { it.endpoint })
    }

    // --- the active model on a local endpoint that is not answering ----------

    private fun model(
        provider: String,
        name: String,
        local: Boolean = false,
        favorite: Boolean = false,
        toolCall: Boolean = false,
    ) = ModelEntry(
        provider = provider,
        providerName = provider,
        name = name,
        displayName = name,
        vision = false,
        reasoning = false,
        toolCall = toolCall,
        context = 0,
        local = local,
        favorite = favorite,
        active = false,
    )

    private val stale = "http://127.0.0.1:11434"

    /**
     * The device's failure after a sign-in: the plan's models are there and
     * the gate is satisfied, but the active model is still the stale Ollama
     * endpoint, which has no model registered. The subscription must win.
     */
    @Test
    fun aSignInMovesTheActiveModelOffADeadLocalOntoThePlan() {
        val models = listOf(
            model("anthropic", "claude-haiku", toolCall = true),
            model("spettro", "spettro-fast"),
            model("spettro", "spettro-pro", toolCall = true),
        )
        assertTrue(isOnUnansweringLocal(stale, models))
        val next = replacementForUnansweringLocal(stale, models, preferProvider = "spettro")
        assertEquals("spettro:spettro-pro", next?.configValue)
    }

    /** A keyed provider beside a stale local: the gate was satisfied all along. */
    @Test
    fun aKeyedProviderReplacesADeadLocalActiveModel() {
        val models = listOf(
            model("anthropic", "claude-opus"),
            model("anthropic", "claude-sonnet", toolCall = true),
        )
        assertEquals(
            "anthropic:claude-sonnet",
            replacementForUnansweringLocal("$stale/", models)?.configValue,
        )
        // A favourite outranks the tool-calling default.
        val starred = models + model("openai", "gpt", favorite = true)
        assertEquals("openai:gpt", replacementForUnansweringLocal(stale, starred)?.configValue)
    }

    @Test
    fun aLiveLocalActiveModelIsLeftAlone() {
        val models = listOf(
            model(stale, "qwen2.5-coder:7b", local = true),
            model("anthropic", "claude-sonnet", toolCall = true),
        )
        assertFalse(isOnUnansweringLocal(stale, models))
        assertFalse(isOnUnansweringLocal("$stale/", models))
        assertNull(replacementForUnansweringLocal(stale, models))
    }

    @Test
    fun aKeyedActiveModelIsLeftAlone() {
        val models = listOf(model("openai", "gpt"))
        // Not a URL: whether the key is good is the gate's business, not this.
        assertFalse(isOnUnansweringLocal("anthropic", models))
        assertNull(replacementForUnansweringLocal("anthropic", models))
        assertNull(replacementForUnansweringLocal(null, models))
    }

    /**
     * Another live local endpoint is never the replacement: Spettro splits the
     * model value at its first colon, so it would be refused.
     */
    @Test
    fun nothingButAnotherLocalLeavesTheActiveModelWhereItIs() {
        val models = listOf(model("http://192.168.1.5:11434", "llama3", local = true))
        assertTrue(isOnUnansweringLocal(stale, models))
        assertNull(replacementForUnansweringLocal(stale, models))
        assertNull(replacementForUnansweringLocal(stale, emptyList()))
    }

    @Test
    fun modelsListCarriesTheActiveSelection() {
        val list = ModelsList.parse(
            JSONObject(
                """{"models":[{"provider":"anthropic","name":"claude-sonnet"}],
                    "activeProvider":"$stale","activeModel":"qwen2.5-coder:7b"}"""
            )
        )!!
        assertEquals(stale, list.activeProvider)
        assertEquals("qwen2.5-coder:7b", list.activeModel)
        assertEquals("anthropic:claude-sonnet", list.models.single().configValue)
        assertNull(ModelsList.parse(JSONObject("""{"activeProvider":"x"}""")))
        assertNull(ModelsList.parse(JSONObject("""{"models":[]}"""))!!.activeProvider)
    }
}
