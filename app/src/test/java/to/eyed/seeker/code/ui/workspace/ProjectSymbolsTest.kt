package to.eyed.seeker.code.ui.workspace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The project symbols picker's two pure steps: reading what the servers
 * answered and ranking it — Zed's second pass over `workspace/symbol`
 * (project_symbols.rs `update_matches`).
 */
class ProjectSymbolsTest {

    private fun symbol(name: String, container: String? = null) = WorkspaceSymbol(
        name = name, kind = "function", container = container,
        path = "src/main.rs", absolutePath = "/p/src/main.rs",
        row = 0, col = 0, endRow = 0, endCol = 0, server = "rust-analyzer",
    )

    @Test
    fun parsesTheEnginesListAndSkipsWhatCannotBeOpened() {
        val symbols = parseWorkspaceSymbols(
            JSONObject(
                """{"symbols":[
                    {"name":"main","kind":"function","container":null,"path":"src/main.rs","absolute_path":"/p/src/main.rs","row":2,"col_utf16":3,"end_row":2,"end_col_utf16":7,"server":"rust-analyzer"},
                    {"name":"","absolute_path":"/p/x.rs"},
                    {"name":"Orphan","absolute_path":""},
                    {"name":"new","kind":"method","container":"Config","path":"/usr/lib/x.rs","absolute_path":"/usr/lib/x.rs","row":9,"col_utf16":4}
                ]}"""
            )
        )
        assertEquals(2, symbols.size)
        assertEquals("main", symbols[0].name)
        assertNull(symbols[0].container)
        assertEquals(2, symbols[0].row)
        assertEquals(3, symbols[0].col)
        assertEquals("/p/src/main.rs", symbols[0].asDefinition().path)
        assertEquals("Config", symbols[1].container)
        // A missing end falls back to the start, so the reveal selects nothing rather than garbage.
        assertEquals(9, symbols[1].endRow)
        assertEquals(4, symbols[1].endCol)
        assertTrue(parseWorkspaceSymbols(null).isEmpty())
    }

    @Test
    fun anEmptyQueryKeepsTheServersOrder() {
        val answered = listOf(symbol("zeta"), symbol("alpha"))
        assertEquals(answered, rankSymbols(answered, ""))
        assertEquals(answered, rankSymbols(answered, "   "))
    }

    @Test
    fun theBestMatchComesFirstAndNonMatchesDropOut() {
        val ranked = rankSymbols(listOf(symbol("domain_name"), symbol("unrelated"), symbol("main")), "main")
        assertEquals(listOf("main", "domain_name"), ranked.map { it.name })
    }

    @Test
    fun anUppercaseQueryMatchesCaseSensitively() {
        val symbols = listOf(symbol("config"), symbol("Config"))
        assertEquals(listOf("Config"), rankSymbols(symbols, "Conf").map { it.name })
        assertEquals(setOf("config", "Config"), rankSymbols(symbols, "conf").map { it.name }.toSet())
    }
}
