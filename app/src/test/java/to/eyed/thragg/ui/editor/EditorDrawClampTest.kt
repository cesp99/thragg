package to.eyed.thragg.ui.editor

import androidx.compose.ui.input.pointer.PointerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two rules the draw pass and the pointer pass may not lose again.
 *
 * The first killed the app twice on the device: a bracket-pair highlight is
 * computed against one revision of a row and painted against the next, so
 * after a "delete word back" its column can sit past the end of the line it
 * came from — and `TextLayoutResult.getHorizontalPosition` throws on an
 * offset outside the text it measured, taking the whole frame with it.
 *
 * The second is why a long press never showed a hover card.
 */
class EditorDrawClampTest {

    // ---- B-01: a column that outlived its line ----

    /** The offsets the crash logged: column 13 against a layout of 8. */
    @Test
    fun aColumnPastTheEndOfTheSegmentLandsOnItsEnd() {
        assertEquals(8, layoutOffsetOf(col = 13, startCol = 0, layoutLength = 8))
        assertEquals(25, layoutOffsetOf(col = 46, startCol = 0, layoutLength = 25))
    }

    /** A wrapped row measures a segment, so the offset is relative to it. */
    @Test
    fun aColumnInsideTheSegmentIsMeasuredFromTheSegmentsStart() {
        assertEquals(4, layoutOffsetOf(col = 24, startCol = 20, layoutLength = 10))
        assertEquals(10, layoutOffsetOf(col = 40, startCol = 20, layoutLength = 10))
    }

    /** A column in front of the segment — a highlight above the window — is its start. */
    @Test
    fun aColumnBeforeTheSegmentLandsOnItsStart() {
        assertEquals(0, layoutOffsetOf(col = 3, startCol = 20, layoutLength = 10))
        assertEquals(0, layoutOffsetOf(col = -1, startCol = 0, layoutLength = 10))
    }

    /** An empty row measures an empty layout, and zero is the only offset in it. */
    @Test
    fun anEmptyLayoutOnlyEverAnswersZero() {
        assertEquals(0, layoutOffsetOf(col = 7, startCol = 0, layoutLength = 0))
        assertEquals(0, layoutOffsetOf(col = 7, startCol = 0, layoutLength = -1))
    }

    // ---- G-10: whose exit takes the card away ----

    @Test
    fun onlyAMouseLeavingClearsTheHoverCard() {
        assertTrue(exitClearsHover(PointerType.Mouse))
        assertFalse(
            "a finger's exit is the end of the gesture that asked",
            exitClearsHover(PointerType.Touch),
        )
        assertFalse(exitClearsHover(PointerType.Stylus))
        assertFalse(exitClearsHover(PointerType.Eraser))
    }
}
