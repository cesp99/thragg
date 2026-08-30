package to.eyed.seeker.code.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `agent_servers` in settings.json — the entry that makes the panel's promise
 * of *any* ACP agent true rather than "the two we happen to name".
 *
 * The shape is Zed's own setting of the same name, and the engine's
 * `config.rs` has the mirror-image test (`a_custom_agent_is_read_from_settings`)
 * over the same JSON — the two suites together are what keep the two sides of
 * the bridge speaking the same dialect.
 */
class AppSettingsAgentsTest {

    private fun settings(agentServers: String): AppSettings = AppSettings.parse(
        """{"theme":"system","agent_servers":$agentServers}"""
    )

    /**
     * Name-sorted, deliberately on both sides: the engine's map is a BTreeMap
     * and this parser sorts too, because `JSONObject` promises nothing about
     * key order and a picker that reshuffles between launches is unusable.
     */
    @Test
    fun readsConfiguredAgentsInNameOrder() {
        val parsed = settings(
            """
            {
              "My agent": {
                "command": "python3",
                "args": ["/root/agent.py", "--acp"],
                "env": {"TOKEN": "x"}
              },
              "Bare": {"command": "some-agent"}
            }
            """.trimIndent()
        )

        assertEquals(listOf("Bare", "My agent"), parsed.agents.map { it.name })
        val mine = parsed.agents[1]
        assertEquals("custom:My agent", mine.id)
        assertEquals(listOf("python3", "/root/agent.py", "--acp"), mine.argv)
        assertEquals(mapOf("TOKEN" to "x"), mine.env)

        val bare = parsed.agents[0]
        assertEquals(listOf("some-agent"), bare.argv)
        assertTrue(bare.env.isEmpty())
    }

    /**
     * A half-written entry is an ordinary state of a file people edit by
     * hand. An agent with no command would be a row that can only fail, so it
     * is dropped — and it must not take its valid neighbours with it.
     */
    @Test
    fun aBrokenEntryIsDroppedWithoutSinkingTheRest() {
        val parsed = settings(
            """
            {
              "No command": {"args": ["x"]},
              "Blank command": {"command": "   "},
              "Not an object": "claude",
              "Works": {"command": "fine-agent"}
            }
            """.trimIndent()
        )
        assertEquals(listOf("Works"), parsed.agents.map { it.name })
    }

    @Test
    fun absentOrRubbishMeansNoAgentsAndNoCrash() {
        assertTrue(AppSettings.parse("""{"theme":"dark"}""").agents.isEmpty())
        assertTrue(AppSettings.parse("not json").agents.isEmpty())
        assertTrue(settings("17").agents.isEmpty())
    }

    // --- the picker's list ----------------------------------------------------

    /**
     * The picker *is* the configured list — nothing else. ACP is a standard
     * and the panel is agent-agnostic by the owner's ruling: no agent is
     * named in code, so an empty `agent_servers` means an empty picker (which
     * the panel renders as Zed's empty state), never a vendor list.
     */
    @Test
    fun thePickerOffersExactlyWhatIsConfigured() {
        assertTrue(settings("{}").agents.isEmpty())
        val parsed = settings("""{"Mine": {"command": "mine"}}""")
        assertEquals(listOf("Mine"), parsed.agents.map { it.name })
    }

    // --- the spec that crosses the bridge --------------------------------------

    /**
     * The spec is parsed by serde on the Rust side, so it has to be real JSON
     * — including when the name or an argument carries a quote or a
     * backslash, which for a command line someone typed into settings.json is
     * not an exotic case. Hand-interpolated JSON is exactly where this broke.
     */
    @Test
    fun theSpecSurvivesQuotesAndBackslashes() {
        val awkward = AgentDefinition(
            id = "custom:odd",
            name = """He said "hi" \once\""",
            argv = listOf("""path\with\slashes""", """--msg="quoted""""),
            env = mapOf("A\"B" to "C\\D"),
        )
        val parsed = JSONObject(awkward.toSpecJson())
        assertEquals("""He said "hi" \once\""", parsed.getString("name"))
        assertEquals("""path\with\slashes""", parsed.getJSONArray("argv").getString(0))
        assertEquals("""--msg="quoted"""", parsed.getJSONArray("argv").getString(1))
        assertEquals("C\\D", parsed.getJSONObject("env").getString("A\"B"))
    }

    /** And the env actually reaches the spec — it is how keys get to agents. */
    @Test
    fun theEnvRidesTheSpec() {
        val parsed = settings(
            """{"Keyed": {"command": "agent", "env": {"API_KEY": "secret"}}}"""
        )
        val spec = JSONObject(parsed.agents.single().toSpecJson())
        assertEquals("secret", spec.getJSONObject("env").getString("API_KEY"))
    }
}
