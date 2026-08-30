package to.eyed.seeker.code.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link detector, over the rows a compiler or a shell actually prints.
 * Indices are what the view hands over after mapping a touch to a column.
 */
class TerminalLinksTest {

    private fun pathAt(line: String, index: Int): TerminalLink.PathLike {
        val link = findTerminalLink(line, index)
        assertTrue("expected a path at $index in '$line', got $link", link is TerminalLink.PathLike)
        return link as TerminalLink.PathLike
    }

    private fun urlAt(line: String, index: Int): TerminalLink.Url {
        val link = findTerminalLink(line, index)
        assertTrue("expected a URL at $index in '$line', got $link", link is TerminalLink.Url)
        return link as TerminalLink.Url
    }

    @Test
    fun rustcLocationWithLineAndColumn() {
        val line = "  --> src/main.rs:12:5"
        val link = pathAt(line, line.indexOf("main"))
        assertEquals("src/main.rs", link.path)
        assertEquals(12, link.row)
        assertEquals(5, link.column)
        assertEquals(line.indexOf("src"), link.start)
        assertEquals(line.length, link.end)
    }

    @Test
    fun theWholeTokenIsTheLinkIncludingItsPosition() {
        val line = "src/main.rs:12:5"
        for (index in line.indices) {
            val link = pathAt(line, index)
            assertEquals("src/main.rs", link.path)
            assertEquals(12, link.row)
        }
    }

    @Test
    fun aBareRelativePathHasNoPosition() {
        val link = pathAt("cat README.md", 6)
        assertEquals("README.md", link.path)
        assertNull(link.row)
        assertNull(link.column)
    }

    @Test
    fun lineOnlyAndTrailingColon() {
        assertEquals(PathWithPosition("Cargo.toml", 3, null), parsePathWithPosition("Cargo.toml:3:"))
        assertEquals(PathWithPosition("Cargo.toml", 3, null), parsePathWithPosition("Cargo.toml:3"))
    }

    @Test
    fun msbuildParenthesisedPositions() {
        assertEquals(PathWithPosition("a.cs", 22, 5), parsePathWithPosition("a.cs(22,5)"))
        assertEquals(PathWithPosition("a.cs", 22, null), parsePathWithPosition("a.cs(22)"))
        assertEquals(PathWithPosition("a.cs", 22, 5), parsePathWithPosition("a.cs:(22:5)"))
        val line = "error in a.cs(22,5): missing ;"
        val link = pathAt(line, line.indexOf("a.cs"))
        assertEquals("a.cs", link.path)
        assertEquals(22, link.row)
        assertEquals(5, link.column)
    }

    @Test
    fun aColonSuffixThatIsNotNumbersStaysInTheName() {
        assertEquals(PathWithPosition("test:10:a", null, null), parsePathWithPosition("test:10:a"))
        assertEquals(PathWithPosition("foo/bar.py", 22, null), parsePathWithPosition("foo/bar.py:22"))
    }

    @Test
    fun pythonTracebackForm() {
        val line = "  File \"/root/app/run.py\", line 41, in <module>"
        val link = pathAt(line, line.indexOf("run.py"))
        assertEquals("/root/app/run.py", link.path)
        assertEquals(41, link.row)
        assertNull(link.column)
    }

    @Test
    fun spacesBetweenWordsAreNotLinks() {
        assertNull(findTerminalLink("ls -la  src", 7))
        assertNull(findTerminalLink("abc", 3))
        assertNull(findTerminalLink("abc", -1))
    }

    @Test
    fun quotesAndBracketsAreDelimitersNotPath() {
        val line = "warning: unused import: `std::io` in 'src/lib.rs'"
        val link = pathAt(line, line.indexOf("lib.rs"))
        assertEquals("src/lib.rs", link.path)
        val update = "Update(.claude/SKILL.md)"
        assertEquals(".claude/SKILL.md", pathAt(update, update.indexOf("SKILL")).path)
    }

    @Test
    fun urlsWinAndTrailingPunctuationIsTrimmed() {
        val line = "see https://example.com/docs. and (https://zed.dev/x) or http://a.b/c,"
        assertEquals("https://example.com/docs", urlAt(line, line.indexOf("example")).url)
        assertEquals("https://zed.dev/x", urlAt(line, line.indexOf("zed")).url)
        assertEquals("http://a.b/c", urlAt(line, line.indexOf("a.b")).url)
        // The trimmed dot is no longer part of the link.
        assertNull(findTerminalLink(line, line.indexOf("docs.") + 4))
    }

    @Test
    fun aBalancedParenthesisStaysInTheUrl() {
        val line = "https://en.wikipedia.org/wiki/Rust_(programming_language)"
        assertEquals(line, urlAt(line, 10).url)
    }

    @Test
    fun fileUrlsBecomePathsWithPositions() {
        val line = "open file:///root/My%20Docs/a.rs:7:3 now"
        val link = pathAt(line, line.indexOf("a.rs"))
        assertEquals("/root/My Docs/a.rs", link.path)
        assertEquals(7, link.row)
        assertEquals(3, link.column)
        // OSC 8 form with a host.
        assertEquals("/tmp/x.txt", pathAt("file://localhost/tmp/x.txt", 20).path)
    }

    @Test
    fun resolvesAgainstTheShellsDirectoryAndMapsTheGuestProjectsMount() {
        val link = TerminalLink.PathLike("../Cargo.toml", 2, null, 0, 0)
        val target = resolveTerminalPath(link, "/data/projects/welcome/src", "/data/projects")
        assertEquals("/data/projects/welcome/Cargo.toml", target.absolutePath)
        assertEquals(2, target.row)

        val guest = TerminalLink.PathLike("/projects/welcome/src/main.rs", 1, 1, 0, 0)
        assertEquals(
            "/data/projects/welcome/src/main.rs",
            resolveTerminalPath(guest, "/anything", "/data/projects").absolutePath,
        )
        val outside = TerminalLink.PathLike("/usr/include/stdio.h", null, null, 0, 0)
        assertEquals(
            "/usr/include/stdio.h",
            resolveTerminalPath(outside, "/data/projects/welcome", "/data/projects").absolutePath,
        )
    }

    @Test
    fun projectRelativeNamesOnlyInsideTheRoot() {
        assertEquals("src/main.rs", projectRelativePath("/p/welcome/src/main.rs", "/p/welcome"))
        assertNull(projectRelativePath("/p/welcome-2/src/main.rs", "/p/welcome"))
        assertNull(projectRelativePath("/p/welcome", "/p/welcome"))
        assertNull(projectRelativePath("/usr/include/stdio.h", "/p/welcome"))
    }
}
