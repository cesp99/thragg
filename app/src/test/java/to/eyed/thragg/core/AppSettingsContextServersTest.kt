package to.eyed.thragg.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `context_servers` and `agent.notify_when_agent_waiting` in settings.json —
 * Zed's own keys, read by the settings screen. The engine's `config.rs` has
 * the mirror-image tests (`context_servers_are_read_in_zeds_shape`,
 * `the_notify_setting_takes_zeds_three_names`) over the same JSON.
 */
class AppSettingsContextServersTest {

    private fun settings(body: String): AppSettings =
        AppSettings.parse("""{"theme":"system",$body}""")

    /** A `command` entry is stdio, a `url` entry HTTP; both name-sorted. */
    @Test
    fun readsBothShapesInNameOrder() {
        val parsed = settings(
            """
            "context_servers": {
              "fs": {
                "source": "custom",
                "command": "npx",
                "args": ["-y", "server-filesystem", "."],
                "env": {"ROOT": "/proj"}
              },
              "docs": {"url": "https://example.com/mcp", "headers": {"Authorization": "Bearer x"}},
              "off": {"command": "quiet", "enabled": false}
            }
            """.trimIndent()
        )
        assertEquals(listOf("docs", "fs", "off"), parsed.contextServers.map { it.name })
        val fs = parsed.contextServers[1]
        assertFalse(fs.isHttp)
        assertEquals("npx", fs.command)
        assertEquals(listOf("-y", "server-filesystem", "."), fs.args)
        assertEquals(mapOf("ROOT" to "/proj"), fs.env)
        assertTrue(fs.enabled)
        val docs = parsed.contextServers[0]
        assertTrue(docs.isHttp)
        assertEquals("https://example.com/mcp", docs.url)
        assertEquals(mapOf("Authorization" to "Bearer x"), docs.headers)
        assertFalse(parsed.contextServers[2].enabled)
    }

    /** An entry with neither key is dropped, as the engine drops it. */
    @Test
    fun anEntryWithNeitherCommandNorUrlIsDropped() {
        val parsed = settings(
            """"context_servers": {"neither": {"name": "x"}, "works": {"command": "fine"}}"""
        )
        assertEquals(listOf("works"), parsed.contextServers.map { it.name })
        assertTrue(settings(""""context_servers": 7""").contextServers.isEmpty())
    }

    /** What the form writes back is Zed's shape verbatim. */
    @Test
    fun theSpecIsZedsShape() {
        val stdio = JSONObject(
            ContextServerDefinition(
                name = "fs",
                command = "npx",
                args = listOf("-y", "srv"),
                env = mapOf("A" to "1"),
            ).toSpecJson()
        )
        assertEquals("npx", stdio.getString("command"))
        assertEquals("srv", stdio.getJSONArray("args").getString(1))
        assertEquals("1", stdio.getJSONObject("env").getString("A"))
        assertFalse(stdio.has("url"))
        assertFalse(stdio.has("enabled"))

        val http = JSONObject(
            ContextServerDefinition(name = "docs", url = "https://e.com/mcp", enabled = false).toSpecJson()
        )
        assertEquals("https://e.com/mcp", http.getString("url"))
        assertFalse(http.has("command"))
        assertFalse(http.getBoolean("enabled"))
        assertEquals("https://e.com/mcp", ContextServerDefinition(name = "d", url = "https://e.com/mcp").summary)
        assertEquals("npx -y srv", ContextServerDefinition(name = "f", command = "npx", args = listOf("-y", "srv")).summary)
    }

    /** Zed's three names; a phone reads the two "on" ones the same way. */
    @Test
    fun theNotifySettingTakesZedsThreeNames() {
        assertEquals(NotifyWhenAgentWaiting.PrimaryScreen, settings(""""agent": {}""").notifyWhenAgentWaiting)
        assertEquals(
            NotifyWhenAgentWaiting.Never,
            settings(""""agent": {"notify_when_agent_waiting": "never"}""").notifyWhenAgentWaiting,
        )
        assertTrue(
            settings(""""agent": {"notify_when_agent_waiting": "all_screens"}""").notifyWhenAgentWaiting.isOn
        )
        assertFalse(NotifyWhenAgentWaiting.Never.isOn)
        assertEquals(NotifyWhenAgentWaiting.PrimaryScreen, NotifyWhenAgentWaiting.fromKey("sometimes"))
        assertNull(AppSettings.parse("{}").contextServers.firstOrNull())
    }
}
