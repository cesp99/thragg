package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Open with…" must never offer this app to itself: on a device where
 * nothing else views text, a chooser whose only candidate is us launched
 * us, and the file came back through the import dialog.
 */
class ShareOutHandlersTest {

    private val own = "to.eyed.thragg"
    private val us = ViewHandler(own, "to.eyed.thragg.MainActivity")
    private val editor = ViewHandler("org.example.editor", "org.example.editor.Main")
    private val viewer = ViewHandler("org.example.viewer", "org.example.viewer.View")

    @Test
    fun ourOwnActivitiesAreStruckFromTheList() {
        val (ours, others) = splitOwnHandlers(listOf(editor, us, viewer), own)
        assertEquals(listOf(us), ours)
        assertEquals(listOf(editor, viewer), others)
    }

    @Test
    fun aListWithOnlyUsLeavesNobodyToOffer() {
        val (ours, others) = splitOwnHandlers(listOf(us), own)
        assertEquals(listOf(us), ours)
        assertTrue(others.isEmpty())
    }

    @Test
    fun everyActivityOfOurPackageCountsAsOurs() {
        val alias = ViewHandler(own, "to.eyed.thragg.SomeAlias")
        val (ours, others) = splitOwnHandlers(listOf(alias, us, editor), own)
        assertEquals(listOf(alias, us), ours)
        assertEquals(listOf(editor), others)
    }

    @Test
    fun noHandlersAtAllIsNobodyToOffer() {
        val (ours, others) = splitOwnHandlers(emptyList(), own)
        assertTrue(ours.isEmpty())
        assertTrue(others.isEmpty())
    }
}
