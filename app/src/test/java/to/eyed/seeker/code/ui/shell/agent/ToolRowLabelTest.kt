package to.eyed.seeker.code.ui.shell.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import to.eyed.seeker.code.core.AgentEntry
import to.eyed.seeker.code.core.ToolCallStatus
import to.eyed.seeker.code.core.ToolKind

/**
 * What the two halves of a collapsed tool row say, and that they do not say it
 * twice.
 *
 * The row is `toolVerb` in `labelLarge` then `toolDetail` in `MonoSmall`, a
 * space apart. `toolDetail` is normally built from the call's *arguments*, but
 * a call that sends none falls back to the title — and an ACP title is
 * conventionally `"<verb> <target>"`, so the row drew **"Edit  Edit
 * edit-lib.rs"**. Photographed on the device against
 * tools/conformance-agent.py, whose edit call sends exactly that title with no
 * `path` argument.
 *
 * These pin the narrow fix rather than the general idea: only a leading token
 * that IS the verb is dropped, so nothing here licenses a future version that
 * starts rewriting titles it merely dislikes.
 */
class ToolRowLabelTest {

    private fun call(
        title: String,
        kind: ToolKind = ToolKind.Edit,
        rawInput: String? = null,
    ) = AgentEntry.ToolCall(
        id = "call-1",
        title = title,
        kind = kind,
        status = ToolCallStatus.Completed,
        options = emptyList(),
        content = emptyList(),
        locations = emptyList(),
        rawInput = rawInput,
    )

    @Test
    fun `the verb the row already prints is not printed again by the detail`() {
        // The observed row: verb "Edit", detail was "Edit edit-lib.rs".
        val row = call("Edit edit-lib.rs")
        assertEquals("Edit", toolVerb(row))
        assertEquals("edit-lib.rs", toolDetail(row))
    }

    @Test
    fun `casing is not what decides it`() {
        assertEquals("notes.md", toolDetail(call("edit notes.md")))
        assertEquals("notes.md", toolDetail(call("EDIT notes.md")))
    }

    @Test
    fun `a word that merely starts with the verb is left alone`() {
        // "Move" must not eat the front of "Moved", or the detail loses a word
        // and the row says something the agent did not.
        val row = call("Moved 3 files", kind = ToolKind.Move)
        assertEquals("Move", toolVerb(row))
        assertEquals("Moved 3 files", toolDetail(row))
    }

    @Test
    fun `a title that is only the verb keeps it, because an empty detail says less`() {
        assertEquals("Edit", toolDetail(call("Edit")))
    }

    @Test
    fun `a title that does not open on the verb is untouched`() {
        assertEquals("src/lib.rs", toolDetail(call("src/lib.rs")))
    }

    @Test
    fun `real arguments still win over the title`() {
        // The fix is confined to the fallback: a call WITH a usable path
        // argument never consults the title at all.
        val row = call("Edit edit-lib.rs", rawInput = """{"path":"a/b/notes.md"}""")
        assertEquals("a/b/notes.md", toolDetail(row))
    }
}
