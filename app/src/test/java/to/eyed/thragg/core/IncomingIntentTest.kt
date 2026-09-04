package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The intent → import mapping, and where a staged file lands. The mapping is
 * the second line of defence behind the manifest's filters — an explicit
 * intent skips those — so it is tested as the thing that decides.
 */
class IncomingIntentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val view = "android.intent.action.VIEW"
    private val edit = "android.intent.action.EDIT"
    private val send = "android.intent.action.SEND"
    private val sendMultiple = "android.intent.action.SEND_MULTIPLE"

    @Test
    fun aPlainLaunchImportsNothing() {
        assertNull(importRequestFor(IntentFacts("android.intent.action.MAIN", null, null)))
        assertNull(importRequestFor(IntentFacts(null, null, null)))
    }

    @Test
    fun viewAndEditCarryTheirDataUri() {
        val viewed = importRequestFor(IntentFacts(view, "text/plain", "content://docs/1"))
        assertEquals(ImportRequest.Files(listOf("content://docs/1"), edit = false), viewed)
        val edited = importRequestFor(IntentFacts(edit, "application/json", "file:///sdcard/a.json"))
        assertEquals(ImportRequest.Files(listOf("file:///sdcard/a.json"), edit = true), edited)
    }

    @Test
    fun viewWithAnUnreadableSchemeIsNotAnImport() {
        assertNull(importRequestFor(IntentFacts(view, "text/html", "https://example.com/x.txt")))
        assertNull(importRequestFor(IntentFacts(view, "text/plain", null)))
    }

    @Test
    fun sendPrefersTheStreamOverTheText() {
        val facts = IntentFacts(send, "text/plain", null, streams = listOf("content://s/1"), text = "hello")
        assertEquals(ImportRequest.Files(listOf("content://s/1")), importRequestFor(facts))
    }

    @Test
    fun sendWithTextOnlyBecomesSharedText() {
        val facts = IntentFacts(send, "text/plain", null, text = "fn main() {}")
        assertEquals(ImportRequest.Text("fn main() {}"), importRequestFor(facts))
        assertEquals("Shared text.txt", ImportRequest.Text.FILE_NAME)
    }

    @Test
    fun sendWithNeitherIsNothing() {
        assertNull(importRequestFor(IntentFacts(send, "text/plain", null, text = "   ")))
        assertNull(importRequestFor(IntentFacts(send, "*/*", null)))
    }

    @Test
    fun sendMultipleKeepsEveryReadableStreamInOrder() {
        val facts = IntentFacts(
            sendMultiple,
            "*/*",
            null,
            streams = listOf("content://s/1", "https://not-a-file", "file:///tmp/b"),
        )
        assertEquals(
            ImportRequest.Files(listOf("content://s/1", "file:///tmp/b")),
            importRequestFor(facts),
        )
        assertNull(importRequestFor(IntentFacts(sendMultiple, "*/*", null, text = "ignored")))
    }

    @Test
    fun schemeMatchingIgnoresCase() {
        val facts = IntentFacts(view, "text/plain", "CONTENT://docs/2")
        assertEquals(ImportRequest.Files(listOf("CONTENT://docs/2")), importRequestFor(facts))
    }

    @Test
    fun displayNamesCannotWalkOutOfTheProject() {
        assertEquals(".._settings.json", safeFileName("../settings.json", "x"))
        assertEquals("a_b", safeFileName("a\\b", "x"))
        assertEquals("fallback.txt", safeFileName("..", "fallback.txt"))
        assertEquals("fallback.txt", safeFileName("   ", "fallback.txt"))
        assertEquals("notes.md", safeFileName("  notes.md ", "x"))
    }

    // ---- Where a staged file lands ----------------------------------------

    private fun staged(name: String, text: String): StagedFile {
        val file = temp.newFile()
        file.writeText(text)
        return StagedFile(name, file)
    }

    @Test
    fun placeWritesTheFileAndCreatesTheFoldersOnTheWay() {
        val root = temp.newFolder("project")
        val landed = IncomingFiles.place(root, "docs/notes/shared.md", staged("shared.md", "# hi"))
        assertEquals("docs/notes/shared.md", landed.getOrThrow())
        assertEquals("# hi", File(root, "docs/notes/shared.md").readText())
    }

    @Test
    fun placeSuffixesRatherThanOverwriting() {
        val root = temp.newFolder("project")
        File(root, "main.rs").writeText("original")
        val landed = IncomingFiles.place(root, "main.rs", staged("main.rs", "shared"))
        assertEquals("main copy.rs", landed.getOrThrow())
        assertEquals("original", File(root, "main.rs").readText())
        assertEquals("shared", File(root, "main copy.rs").readText())
        val again = IncomingFiles.place(root, "main.rs", staged("main.rs", "third"))
        assertEquals("main copy 1.rs", again.getOrThrow())
    }

    @Test
    fun placeRefusesAPathThatLeavesTheProject() {
        val root = temp.newFolder("project")
        assertTrue(IncomingFiles.place(root, "../escape.txt", staged("escape.txt", "x")).isFailure)
        assertTrue(IncomingFiles.place(root, "", staged("x", "x")).isFailure)
        assertTrue(IncomingFiles.place(root, "/", staged("x", "x")).isFailure)
    }
}
