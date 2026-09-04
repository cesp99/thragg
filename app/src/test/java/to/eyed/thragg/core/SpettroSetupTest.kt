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
}
