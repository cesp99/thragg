package to.eyed.thragg.ui.search

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.ProjectSearchFile
import to.eyed.thragg.core.ProjectSearchMatch

/**
 * The two pure pieces of the results panel: what the list is made of, and
 * where the wash over a hit lands. Both are easy to get subtly wrong and
 * neither needs a device to prove.
 */
class ProjectSearchRowsTest {

    private fun match(
        line: Int = 1,
        text: String = "let foo = 1;",
        startUtf16: Int = 4,
        endUtf16: Int = 7,
        clippedStart: Boolean = false,
        clippedEnd: Boolean = false,
    ) = ProjectSearchMatch(
        line = line,
        column = startUtf16,
        start = startUtf16,
        end = endUtf16,
        startUtf16 = startUtf16,
        endUtf16 = endUtf16,
        text = text,
        clippedStart = clippedStart,
        clippedEnd = clippedEnd,
    )

    private fun file(path: String, matches: Int) = ProjectSearchFile(
        path = path,
        matches = List(matches) { match(line = it + 1) },
        matchCount = matches,
    )

    @Test
    fun everyFileBringsItsMatchesWithIt() {
        val rows = projectSearchRows(listOf(file("src/main.rs", 2), file("README.md", 1)), emptySet())

        assertEquals(5, rows.size)
        assertEquals("src/main.rs", (rows[0] as ProjectSearchRow.FileRow).path)
        assertEquals("main.rs", (rows[0] as ProjectSearchRow.FileRow).name)
        assertEquals("src", (rows[0] as ProjectSearchRow.FileRow).directory)
        assertTrue(rows[1] is ProjectSearchRow.MatchRow)
        assertTrue(rows[2] is ProjectSearchRow.MatchRow)
        assertEquals("README.md", (rows[3] as ProjectSearchRow.FileRow).path)
        // A file at the root has a name and nothing before it.
        assertEquals("", (rows[3] as ProjectSearchRow.FileRow).directory)
    }

    @Test
    fun aCollapsedFileKeepsItsHeaderAndLosesItsMatches() {
        val files = listOf(file("src/main.rs", 3), file("src/lib.rs", 1))
        val rows = projectSearchRows(files, setOf("src/main.rs"))

        assertEquals(3, rows.size)
        assertTrue((rows[0] as ProjectSearchRow.FileRow).isCollapsed)
        // Still says how many it is hiding.
        assertEquals(3, (rows[0] as ProjectSearchRow.FileRow).matchCount)
        assertEquals("src/lib.rs", rows[1].path)
        assertTrue(rows[2] is ProjectSearchRow.MatchRow)
    }

    @Test
    fun rowKeysAreUniqueSoAGrowingListNeverRemeasures() {
        val rows = projectSearchRows(listOf(file("a.rs", 3), file("b.rs", 3)), emptySet())
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun theWashLandsOnTheMatchAndNowhereElse() {
        val line = matchLine(match(), Color.Red)
        val spans = line.spanStyles

        assertEquals(1, spans.size)
        assertEquals(4, spans[0].start)
        assertEquals(7, spans[0].end)
        assertEquals("foo", line.text.substring(spans[0].start, spans[0].end))
    }

    @Test
    fun aWindowedLineShiftsTheWashPastItsEllipsis() {
        val line = matchLine(match(clippedStart = true, clippedEnd = true), Color.Red)
        val spans = line.spanStyles

        assertEquals("…let foo = 1;…", line.text)
        assertEquals("foo", line.text.substring(spans[0].start, spans[0].end))
    }

    @Test
    fun offsetsPastTheEndOfTheLineAreClampedRatherThanThrowing() {
        val line = matchLine(match(text = "short", startUtf16 = 3, endUtf16 = 99), Color.Red)
        assertEquals("rt", line.text.substring(line.spanStyles[0].start, line.spanStyles[0].end))

        // A zero-width hit (a regex like `^`) simply has nothing to wash.
        val empty = matchLine(match(startUtf16 = 2, endUtf16 = 2), Color.Red)
        assertTrue(empty.spanStyles.isEmpty())
    }

    @Test
    fun globsAreSplitOnCommasAndTrimmed() {
        assertEquals(listOf("src/**/*.rs", "*.toml"), globsOf(" src/**/*.rs , *.toml "))
        // A field holding only separators asks for nothing, not for everything.
        assertEquals(emptyList<String>(), globsOf(" , "))
        assertEquals(emptyList<String>(), globsOf(""))
    }
}
