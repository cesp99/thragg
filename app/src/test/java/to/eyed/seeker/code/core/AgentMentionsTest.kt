package to.eyed.seeker.code.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mention model, on the wire and in the composer. The engine's
 * `acp_thread::tests::mentions_parse_from_paths_and_from_tagged_objects`
 * reads what [AgentMention.toJson] writes.
 */
class AgentMentionsTest {

    /** Every kind serializes tagged, with the engine's snake_case keys. */
    @Test
    fun mentionsSerializeInTheEnginesTaggedShape() {
        val json = JSONArray(
            AgentMention.toJson(
                listOf(
                    AgentMention.File("src/main.rs"),
                    AgentMention.Directory("src"),
                    AgentMention.Symbol("src/main.rs", "main", 0, 3),
                    AgentMention.Selection("src/main.rs", 1, 2, "x"),
                    AgentMention.Thread(4, "Earlier"),
                    AgentMention.Fetch("https://e.com", "page"),
                    AgentMention.Rules("AGENTS.md"),
                    AgentMention.Diagnostics,
                )
            )
        )
        assertEquals(8, json.length())
        assertEquals("file", json.getJSONObject(0).getString("kind"))
        assertEquals("directory", json.getJSONObject(1).getString("kind"))
        val symbol = json.getJSONObject(2)
        assertEquals("symbol", symbol.getString("kind"))
        assertEquals(0, symbol.getInt("start_row"))
        assertEquals(3, symbol.getInt("end_row"))
        assertEquals("x", json.getJSONObject(3).getString("text"))
        assertEquals(4L, json.getJSONObject(4).getLong("session"))
        assertEquals("https://e.com", json.getJSONObject(5).getString("url"))
        assertEquals("rules", json.getJSONObject(6).getString("kind"))
        assertEquals("diagnostics", json.getJSONObject(7).getString("kind"))
    }

    /**
     * A file, a directory and a rules file live in the text as `@token`;
     * everything else is a chip only, so deleting text never drops it.
     */
    @Test
    fun onlyPathMentionsHaveATextToken() {
        assertEquals("src/main.rs", AgentMention.File("src/main.rs").textToken)
        assertEquals("src/", AgentMention.Directory("src").textToken)
        assertEquals("AGENTS.md", AgentMention.Rules("AGENTS.md").textToken)
        assertNull(AgentMention.Selection("a", 0, 0, "t").textToken)
        assertNull(AgentMention.Fetch("https://e.com", "t").textToken)
        assertNull(AgentMention.Thread(1, "t").textToken)
        assertNull(AgentMention.Diagnostics.textToken)
        assertEquals("main.rs L2–3", AgentMention.Selection("src/main.rs", 1, 2, "t").label)
        assertEquals("e.com/x", AgentMention.Fetch("https://e.com/x", "t").label)
    }

    /** The Fetch row is offered for an https URL and nothing else. */
    @Test
    fun onlyAnHttpsUrlLooksLikeOne() {
        assertTrue(FetchMention.looksLikeUrl("https://example.com/page"))
        assertFalse(FetchMention.looksLikeUrl("https://"))
        assertFalse(FetchMention.looksLikeUrl("http://example.com"))
        assertFalse(FetchMention.looksLikeUrl("src/main.rs"))
        assertFalse(FetchMention.looksLikeUrl("https://a b"))
    }

    /** HTML becomes readable text: scripts gone, blocks as lines, entities decoded. */
    @Test
    fun htmlReducesToText() {
        val html = """
            <html><head><title>T</title><style>p{}</style><script>x()</script></head>
            <body><h1>Hello &amp; welcome</h1><p>One<br>two</p>
            <!-- hidden --><ul><li>a</li><li>&#x41;&#66;</li></ul>&nbsp;end&hellip;</body></html>
        """.trimIndent()
        val text = FetchMention.htmlToText(html)
        assertEquals("T\n\nHello & welcome\n\nOne\ntwo\n\na\n\nAB\n\nend…", text)
        assertFalse(text.contains("x()"))
    }
}
