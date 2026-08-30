package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.Toolchain

/**
 * The toolchain selector's list: which toolchains a query keeps, and in what
 * order. Pure, so it is checked here rather than by opening the picker on a
 * device — where the list depends on what is installed in the userland.
 */
class ToolchainSelectorTest {

    /** The engine's order: the project's own virtualenvs, then the guest's. */
    private val toolchains = listOf(
        Toolchain("Python (.venv)", "/p/.venv/bin/python3", "Python", ".venv"),
        Toolchain("Python (backend/.venv)", "/p/backend/.venv/bin/python3", "Python", "backend/.venv"),
        Toolchain("Python (poetry: virtualenvs/p-x)", "/root/.cache/p-x/bin/python", "Python", "poetry"),
        Toolchain("Python (system)", "/usr/bin/python3", "Python", "system"),
        Toolchain("Rust (stable-aarch64-unknown-linux-gnu)", "/root/.rustup/toolchains/stable/bin/cargo", "Rust", "rustup"),
    )

    private fun names(query: String) = matchToolchains(toolchains, query).map { it.name }

    @Test
    fun an_empty_query_keeps_the_engines_order() {
        assertEquals(toolchains.map { it.name }, names(""))
        assertEquals(toolchains.map { it.name }, names("   "))
    }

    @Test
    fun a_query_filters_by_name() {
        assertEquals(
            listOf("Rust (stable-aarch64-unknown-linux-gnu)"),
            names("rust"),
        )
    }

    /**
     * Two virtualenvs are told apart by their paths, not their names, so the
     * path is searched too — typing "backend" must find the one in it.
     */
    @Test
    fun a_query_filters_by_path() {
        assertEquals(listOf("Python (backend/.venv)"), names("backend"))
    }

    @Test
    fun matching_ignores_case_and_allows_gaps() {
        assertTrue("POETRY" in names("POETRY").joinToString().uppercase())
        // A subsequence, as every picker in this app matches: p-o-e-t.
        assertEquals(listOf("Python (poetry: virtualenvs/p-x)"), names("poet"))
    }

    @Test
    fun a_query_that_matches_nothing_keeps_nothing() {
        assertTrue(names("zzzz").isEmpty())
    }

    /**
     * The status bar follows the open file's language, as Zed's item does.
     */
    @Test
    fun the_status_bar_prints_the_open_files_language_toolchain() {
        val active = listOf(toolchains[0], toolchains[4])
        assertEquals("Rust (stable-aarch64-unknown-linux-gnu)", statusBarToolchain(active, "Rust"))
        assertEquals("Python (.venv)", statusBarToolchain(active, "Python"))
    }

    /**
     * A README is open, or no file at all: the item still shows something,
     * because it is the only way back to the picker without a keyboard.
     */
    @Test
    fun a_language_with_no_toolchain_falls_back_to_the_first() {
        val active = listOf(toolchains[0])
        assertEquals("Python (.venv)", statusBarToolchain(active, "Markdown"))
        assertEquals("Python (.venv)", statusBarToolchain(active, null))
    }

    @Test
    fun no_toolchain_at_all_prints_nothing() {
        assertNull(statusBarToolchain(emptyList(), "Python"))
    }
}
