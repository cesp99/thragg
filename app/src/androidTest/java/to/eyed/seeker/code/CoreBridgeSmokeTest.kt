package to.eyed.seeker.code

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import to.eyed.seeker.code.core.CoreBridge

/**
 * Smoke test for the JNI v2 surface: loads libseekercore.so on-device and
 * round-trips edits, line-window reads and undo/redo through the Rust
 * engine.
 */
@RunWith(AndroidJUnit4::class)
class CoreBridgeSmokeTest {
    @Test
    fun engineVersionIsExposed() {
        assertTrue(CoreBridge.engineVersion().isNotEmpty())
    }

    @Test
    fun editLinesUndoRedoRoundTrip() {
        val id = CoreBridge.createBuffer("one\ntwo\nthree")
        try {
            assertEquals(3, CoreBridge.bufferLineCount(id))
            assertEquals("two\nthree", CoreBridge.bufferLines(id, 1, 3))

            // "two" -> "2" (bytes 4..7), a mid-buffer incremental edit.
            val v1 = CoreBridge.applyEdit(id, 4, 7, "2")
            assertTrue(v1 > 0)
            assertEquals("one\n2\nthree", CoreBridge.bufferText(id))
            assertEquals(v1, CoreBridge.bufferVersion(id))

            val v2 = CoreBridge.undoBuffer(id)
            assertTrue(v2 > v1)
            assertEquals("one\ntwo\nthree", CoreBridge.bufferText(id))

            val v3 = CoreBridge.redoBuffer(id)
            assertTrue(v3 > v2)
            assertEquals("one\n2\nthree", CoreBridge.bufferText(id))
        } finally {
            assertTrue(CoreBridge.closeBuffer(id))
        }
    }

    @Test
    fun invalidArgumentsReportErrors() {
        val id = CoreBridge.createBuffer("héllo")
        try {
            // Mid-code-point offset is rejected, buffer untouched.
            assertEquals(-1, CoreBridge.applyEdit(id, 2, 3, "x"))
            assertEquals("héllo", CoreBridge.bufferText(id))
            // Nothing to undo.
            assertEquals(-1, CoreBridge.undoBuffer(id))
        } finally {
            assertTrue(CoreBridge.closeBuffer(id))
        }
        // Operations on a closed buffer fail cleanly.
        assertEquals(-1, CoreBridge.bufferLineCount(id))
        assertNull(CoreBridge.bufferText(id))
        assertFalse(CoreBridge.closeBuffer(id))
    }
}
