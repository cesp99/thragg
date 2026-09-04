package to.eyed.thragg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Terminal output as a card should show it.
 *
 * Agents run `cargo test`, jest and eslint, and those colour their output
 * whenever they think a terminal is watching. Without this the card fills
 * with escape-code noise around every word.
 */
class StripAnsiTest {

    private val esc = "\u001B"
    private val bel = "\u0007"

    /** The overwhelmingly common case must cost nothing. */
    @Test
    fun textWithNoEscapesIsHandedBackUntouched() {
        val plain = "test result: ok. 260 passed\n"
        assertSame(plain, stripAnsi(plain))
    }

    @Test
    fun colourSequencesGoAndTheWordsStay() {
        val coloured = "${esc}[1m${esc}[31merror[E0308]${esc}[0m: mismatched types${esc}[0m"
        assertEquals("error[E0308]: mismatched types", stripAnsi(coloured))
    }

    /** Cursor movement and erase-line are CSI too, with different finals. */
    @Test
    fun cursorAndEraseSequencesGo() {
        assertEquals("done", stripAnsi("${esc}[2K${esc}[1Gdone"))
    }

    /** OSC ends at BEL, or at ESC-backslash. */
    @Test
    fun windowTitlesAndHyperlinksGo() {
        assertEquals("build", stripAnsi("${esc}]0;npm run build${bel}build"))
        assertEquals("link", stripAnsi("${esc}]8;;https://example.com${esc}\\link"))
    }

    /**
     * A progress bar rewrites its own line with a carriage return. Keeping
     * the CR makes the whole line disappear in a Compose Text, so the line is
     * restarted and the last state written is what shows.
     */
    @Test
    fun aRewrittenProgressLineKeepsItsLastState() {
        assertEquals("kept\n 100%", stripAnsi("kept\n 10%\r 55%\r 100%"))
    }

    /** A sequence cut off by a chunk boundary must not leak its bytes. */
    @Test
    fun anUnterminatedSequenceIsDroppedRatherThanPrinted() {
        assertEquals("ok", stripAnsi("ok${esc}[38;5;"))
    }

    @Test
    fun realNewlinesSurvive() {
        assertEquals("a\nb\n", stripAnsi("${esc}[32ma\nb\n${esc}[0m"))
    }
}
