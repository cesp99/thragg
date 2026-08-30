package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.LanguageChoice

/**
 * The language selector's list: which languages a query keeps, in what order,
 * and where it matched. Pure, so it is checked here rather than by opening the
 * picker on a device.
 */
class LanguageSelectorTest {

    /** The engine's order: by display name, as `available_languages` sorts. */
    private val languages = listOf(
        LanguageChoice("c", "C"),
        LanguageChoice("cpp", "C++"),
        LanguageChoice("go", "Go"),
        LanguageChoice("javascript", "JavaScript"),
        LanguageChoice("json", "JSON"),
        LanguageChoice("markdown", "Markdown"),
        LanguageChoice("python", "Python"),
        LanguageChoice("rust", "Rust"),
        LanguageChoice("tsx", "TSX"),
    )

    private fun names(query: String) =
        matchLanguages(languages, query).map { it.language.name }

    @Test
    fun `an empty query lists every language in the engine's order`() {
        assertEquals(languages.map { it.name }, names(""))
        // Whitespace is not a query either — the field starts with none.
        assertEquals(languages.map { it.name }, names("   "))
        assertTrue(matchLanguages(languages, "").all { it.positions.isEmpty() })
    }

    @Test
    fun `matching is case-insensitive and by subsequence`() {
        assertEquals(listOf("Rust"), names("rust"))
        assertEquals(listOf("Rust"), names("RuSt"))
        // Non-contiguous, which is the point of a fuzzy picker.
        assertEquals(listOf("JavaScript"), names("jvs"))
        assertEquals(emptyList<String>(), names("zzz"))
    }

    @Test
    fun `a prefix match sorts above one that starts later`() {
        // The languages holding a "c": the two where it is the first
        // character come first, the shorter name of the two ahead.
        assertEquals(listOf("C", "C++", "JavaScript"), names("c"))
        // "s" starts none of them, so the earliest position wins — TSX and
        // JSON both match at 1, and the shorter name breaks the tie — then
        // Rust at 2 and JavaScript at 4.
        assertEquals(listOf("TSX", "JSON", "Rust", "JavaScript"), names("s"))
    }

    @Test
    fun `positions point at the matched characters for highlighting`() {
        val match = matchLanguages(languages, "js").first()
        assertEquals("JSON", match.language.name)
        assertEquals(listOf(0, 1), match.positions)
        val scattered = matchLanguages(languages, "jvs").single()
        assertEquals("JavaScript", scattered.language.name)
        assertEquals(
            "jvs",
            scattered.positions
                .map { scattered.language.name[it].lowercaseChar() }
                .joinToString(""),
        )
    }
}
