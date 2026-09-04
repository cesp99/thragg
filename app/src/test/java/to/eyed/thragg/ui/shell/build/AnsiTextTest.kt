package to.eyed.thragg.ui.shell.build

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escape sweep, pinned on host.
 *
 * These are the exact shapes rustc emits under
 * `--message-format=json-diagnostic-rendered-ansi`, which is what every build
 * command in `BuildTasks` asks for: a bold bright-red `error`, a bold
 * bright-blue location arrow, and a `38;5;n` indexed colour for the carets.
 * The parser is pure and theme-blind precisely so this test can exist without
 * a parsed `ZedTheme` or a Compose runtime (`AnsiText.kt`).
 */
class AnsiTextTest {

    private val ESC = '\u001b'

    /** A palette that answers with an identifiable colour per name. */
    private val palette: (String) -> Color = { name ->
        when (name) {
            "red" -> Color.Red
            "bright_red" -> Color.Magenta
            "blue" -> Color.Blue
            else -> Color.Gray
        }
    }

    private fun annotate(text: String) = ansiAnnotate(text, Color.Black, palette)

    @Test
    fun `an unstyled line is returned untouched and unspanned`() {
        val line = "   Compiling thragg-program v0.1.0"
        val out = annotate(line)
        assertEquals(line, out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `a foreground run becomes one span over exactly its own characters`() {
        val out = annotate("$ESC[31merror$ESC[0m: bad")
        assertEquals("error: bad", out.text)
        assertEquals(1, out.spanStyles.size)
        val span = out.spanStyles.single()
        assertEquals(0, span.start)
        assertEquals(5, span.end)
        assertEquals(Color.Red, span.item.color)
    }

    @Test
    fun `bold and bright compose, and the reset ends the run`() {
        // rustc's own prefix: `ESC[0m ESC[1m ESC[38;5;9m` — reset, bold, then
        // an indexed bright red, which is index 9 in the sixteen-name table.
        val out = annotate("$ESC[0m$ESC[1m$ESC[38;5;9merror[E0433]$ESC[0m: nope")
        assertEquals("error[E0433]: nope", out.text)
        val span = out.spanStyles.single()
        assertEquals(Color.Magenta, span.item.color)
        assertEquals(12, span.end)
    }

    @Test
    fun `truecolor is taken literally and background codes are dropped`() {
        val out = annotate("$ESC[48;5;236m$ESC[38;2;255;128;0mwarn$ESC[0m")
        assertEquals("warn", out.text)
        assertEquals(Color(0xFFFF8000), out.spanStyles.single().item.color)
    }

    @Test
    fun `every non-SGR escape is swallowed whole`() {
        // A line-erase, an OSC title terminated by BEL, and a charset select.
        val bel = '\u0007'
        val out = annotate("a${ESC}[Kb${ESC}]0;title${bel}c$ESC(Bd")
        assertEquals("abcd", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `a truncated escape at the end of a line loses only itself`() {
        assertEquals("keep", annotate("keep$ESC").text)
        assertEquals("keep", annotate("keep$ESC[38;5;").text)
    }

    @Test
    fun `stripAnsi is the same sweep with the colours thrown away`() {
        val rendered = "$ESC[0m$ESC[1m$ESC[38;5;9merror$ESC[0m: cannot find `MintNft`"
        assertEquals("error: cannot find `MintNft`", stripAnsi(rendered))
        // The common case is not copied at all.
        val plain = "no escapes here"
        assertTrue(plain === stripAnsi(plain))
    }
}
