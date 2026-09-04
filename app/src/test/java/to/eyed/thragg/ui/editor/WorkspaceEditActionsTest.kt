package to.eyed.thragg.ui.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEditActionsTest {

    // ---- the apply receipt ----

    @Test
    fun parsesAReceipt() {
        val receipt = EditReceipt.parse(
            """
            {"applied":true,"error":null,"files":[
              {"path":"/p/lib.rs","buffer_id":null,"edits":2},
              {"path":"/p/main.rs","buffer_id":7,"edits":1}
            ]}
            """.trimIndent()
        )
        assertTrue(receipt.applied)
        assertNull(receipt.error)
        assertEquals(2, receipt.files.size)
        // org.json's null trap: a JSON null buffer_id must be Kotlin null,
        // not zero — matching a tab against 0 would resync the wrong editor.
        assertNull(receipt.files[0].bufferId)
        assertEquals(7L, receipt.files[1].bufferId)
    }

    @Test
    fun anUnreadableReceiptIsARefusal() {
        assertFalse(EditReceipt.parse(null).applied)
        assertFalse(EditReceipt.parse("").applied)
        assertFalse(EditReceipt.parse("[").applied)
    }

    // ---- the edit summary ----

    @Test
    fun aSummaryKnowsEmptyFromRefused() {
        val edits = EditSummary.parse(JSONObject("""{"files":2,"edits":5,"resource_ops":false}"""))
        assertEquals(5, edits.edits)
        assertFalse(edits.isEmpty)
        assertNull(edits.error)

        // Zero edits with no error is a real answer: a well-formatted file.
        val clean = EditSummary.parse(JSONObject("""{"files":0,"edits":0,"resource_ops":false}"""))
        assertTrue(clean.isEmpty)

        val refused = EditSummary.parse(JSONObject("""{"error":"nothing to rename"}"""))
        assertEquals("nothing to rename", refused.error)
        assertFalse(refused.isEmpty)
    }

    // ---- references ----

    @Test
    fun parsesReferenceTargets() {
        val targets = parseReferenceTargets(
            JSONObject(
                """
                {"targets":[
                  {"path":"/p/main.rs","row":3,"col_utf16":4,"end_row":3,
                   "end_col_utf16":8,"line_text":"let name = 1;"},
                  {"path":"/p/lib.rs","row":0,"col_utf16":0}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(2, targets.size)
        assertEquals("let name = 1;", targets[0].lineText)
        assertNull(targets[1].lineText)
        // The jump reuses the definition plumbing whole.
        val definition = targets[0].asDefinition()
        assertEquals("/p/main.rs", definition.path)
        assertEquals(3, definition.row)
        assertEquals(4, definition.colUtf16)
    }

    // ---- code actions ----

    @Test
    fun theServersPreferredActionSortsFirstAndDisabledCarriesItsReason() {
        val actions = parseCodeActions(
            JSONObject(
                """
                {"actions":[
                  {"index":0,"title":"Run a command","kind":null,
                   "is_preferred":false,"disabled":"runs a server command"},
                  {"index":1,"title":"Fix it","kind":"quickfix",
                   "is_preferred":true,"disabled":null}
                ]}
                """.trimIndent()
            )
        )
        assertEquals(2, actions.size)
        assertEquals("Fix it", actions[0].title)
        assertEquals(1, actions[0].index)
        assertTrue(actions[0].isPreferred)
        assertNull(actions[0].disabled)
        assertEquals("runs a server command", actions[1].disabled)
    }

    @Test
    fun unreadablePayloadsAreEmptyLists() {
        assertTrue(parseReferenceTargets(null).isEmpty())
        assertTrue(parseCodeActions(null).isEmpty())
        assertTrue(parseReferenceTargets(JSONObject("{}")).isEmpty())
    }
}
