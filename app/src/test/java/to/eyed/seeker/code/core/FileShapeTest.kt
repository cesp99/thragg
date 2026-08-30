package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the line-ending and encoding items: what the engine's
 * names become on the status bar, and how the picker filters them.
 */
class FileShapeTest {

    @Test
    fun `line endings answer to the keys the engine speaks`() {
        assertEquals(LineEnding.Unix, LineEnding.fromKey("lf"))
        assertEquals(LineEnding.Windows, LineEnding.fromKey("crlf"))
        assertNull(LineEnding.fromKey("cr"))
        assertEquals("LF", LineEnding.Unix.label)
        assertEquals("CRLF", LineEnding.Windows.label)
    }

    @Test
    fun `encoding labels are the status bar's spellings`() {
        assertEquals("UTF-8", encodingLabel("UTF-8", false))
        assertEquals("UTF-8 BOM", encodingLabel("UTF-8", true))
        assertEquals("UTF-16 LE", encodingLabel("UTF-16LE", false))
        assertEquals("UTF-16 BE BOM", encodingLabel("UTF-16BE", true))
        assertEquals("Windows-1252", encodingLabel("windows-1252", false))
        assertEquals("Windows-874", encodingLabel("windows-874", false))
        assertEquals("Shift_JIS", encodingLabel("Shift_JIS", false))
        assertEquals("ISO-8859-15", encodingLabel("ISO-8859-15", false))
        assertEquals("x-mac-cyrillic", encodingLabel("x-mac-cyrillic", false))
    }

    @Test
    fun `an encoding parses from the engine's json`() {
        val encoding = BufferEncoding.fromJson("""{"name":"UTF-8","bom":true}""")
        assertEquals(BufferEncoding("UTF-8", true), encoding)
        assertEquals("UTF-8 BOM", encoding.label)
        // `bom` is optional on the way in.
        assertEquals(BufferEncoding("windows-1252", false), BufferEncoding.fromJson("""{"name":"windows-1252"}"""))
        assertEquals(listOf("Big5", "UTF-8"), parseEncodingNames("""["Big5","UTF-8"]"""))
    }

    private val names = listOf("Big5", "UTF-16BE", "UTF-16LE", "UTF-8", "windows-1252", "windows-1251")

    @Test
    fun `an empty query keeps every encoding in the engine's order`() {
        assertEquals(names, matchEncodings(names, "").map { it.name })
        assertEquals(names, matchEncodings(names, "   ").map { it.name })
    }

    @Test
    fun `the query is a case-insensitive subsequence of the label`() {
        assertEquals(listOf("UTF-16BE", "UTF-16LE", "UTF-8"), matchEncodings(names, "utf").map { it.name })
        assertEquals(listOf("windows-1252", "windows-1251"), matchEncodings(names, "win125").map { it.name })
        assertEquals(listOf("windows-1252"), matchEncodings(names, "1252").map { it.name })
        assertEquals(emptyList<String>(), matchEncodings(names, "utf9").map { it.name })
    }

    @Test
    fun `positions land on the label's characters for highlighting`() {
        val match = matchEncodings(names, "u8").single()
        assertEquals("UTF-8", match.name)
        assertEquals(listOf(0, 4), match.positions)
    }

    @Test
    fun `a space or hyphen in the query need not be in the label`() {
        // "UTF-16 LE" has the space; "utf 16le" typed by ear still finds it.
        assertEquals(listOf("UTF-16LE"), matchEncodings(names, "utf 16le").map { it.name })
        assertEquals(listOf("UTF-16LE"), matchEncodings(names, "utf16-le").map { it.name })
    }
}
