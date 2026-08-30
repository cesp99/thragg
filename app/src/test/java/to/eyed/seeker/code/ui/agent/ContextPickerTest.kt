package to.eyed.seeker.code.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure halves of the `@` picker. */
class ContextPickerTest {

    /**
     * Directories come from the root's own entries and from the ancestors
     * of the files that matched, shortest first, without duplicates.
     */
    @Test
    fun directoryCandidatesCombineRootsAndAncestors() {
        val dirs = directoryCandidates(
            rootDirs = listOf("src", "docs"),
            matchedFiles = listOf("src/ui/agent/Panel.kt", "src/core/Bridge.kt"),
            query = "",
        )
        assertEquals(listOf("src", "docs", "src/ui", "src/core", "src/ui/agent"), dirs)
    }

    @Test
    fun directoryCandidatesFilterByQueryAndCap() {
        assertEquals(
            listOf("src/ui", "src/ui/agent"),
            directoryCandidates(listOf("src"), listOf("src/ui/agent/Panel.kt"), "ui/"),
        )
        assertEquals(
            listOf("a"),
            directoryCandidates(listOf("a", "b", "c"), emptyList(), "", limit = 1),
        )
    }

    /** A bare `@` opens on files; a URL jumps to Fetch. */
    @Test
    fun aUrlQueryOpensOnFetch() {
        assertEquals(MentionSection.Files, defaultSection("main"))
        assertEquals(MentionSection.Fetch, defaultSection("https://example.com"))
    }
}
