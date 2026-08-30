package to.eyed.seeker.code.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the field accepts, and where a number actually lands.
 *
 * Both halves are pure, and both are where this command goes wrong: a parser
 * that accepts `0` sends the caret to row -1, and one that refuses a number
 * past the end of the file makes the command useless for "take me to the
 * bottom".
 */
class GoToLineTest {

    @Test
    fun `a bare number is a line`() {
        assertEquals(GoToLineTarget(42, null), parseGoToLine("42"))
        assertEquals(GoToLineTarget(42, null), parseGoToLine("  42  "))
    }

    @Test
    fun `line and column, with either separator`() {
        assertEquals(GoToLineTarget(42, 8), parseGoToLine("42:8"))
        assertEquals(GoToLineTarget(42, 8), parseGoToLine("42,8"))
    }

    @Test
    fun `a trailing separator is the line on its own, not an error`() {
        assertEquals(GoToLineTarget(42, null), parseGoToLine("42:"))
    }

    @Test
    fun `anything that is not a position is refused`() {
        assertNull(parseGoToLine(""))
        assertNull(parseGoToLine("   "))
        assertNull(parseGoToLine("abc"))
        assertNull(parseGoToLine("0"))
        assertNull(parseGoToLine("-3"))
        assertNull(parseGoToLine("4:0"))
        assertNull(parseGoToLine("4:x"))
        assertNull(parseGoToLine("1:2:3"))
        // Wider than an Int, so `toIntOrNull` refuses it rather than wrapping.
        assertNull(parseGoToLine("99999999999"))
    }

    @Test
    fun `a position is one-based going in and zero-based coming out`() {
        assertEquals(0 to 0, goToLinePosition(GoToLineTarget(1, 1), lineCount = 10) { 80 })
        assertEquals(41 to 7, goToLinePosition(GoToLineTarget(42, 8), lineCount = 100) { 80 })
    }

    @Test
    fun `a line past the end lands on the last line rather than being refused`() {
        assertEquals(9 to 0, goToLinePosition(GoToLineTarget(9999, null), lineCount = 10) { 0 })
    }

    @Test
    fun `a column past the end of its line lands at the end of it`() {
        assertEquals(2 to 5, goToLinePosition(GoToLineTarget(3, 99), lineCount = 10) { 5 })
    }

    @Test
    fun `an empty buffer still gives a position inside itself`() {
        assertEquals(0 to 0, goToLinePosition(GoToLineTarget(5, 5), lineCount = 0) { 0 })
    }
}
