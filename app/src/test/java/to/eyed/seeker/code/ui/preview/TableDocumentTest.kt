package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSV reader, against the parts of RFC 4180 that people forget.
 *
 * A delimited file is the one format where "split on commas" is wrong in a way
 * that looks right until the day someone exports a row with an address in it —
 * and then every column after that one is off by one for the rest of the file.
 */
class TableDocumentTest {

    private fun csv(text: String) = TableDocument.parse(text, ',')

    @Test
    fun `the first row is the header and the rest are rows`() {
        val table = csv("name,age\nada,36\nalan,41\n")
        assertEquals(listOf("name", "age"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("alan", "41"), table.rows[1])
        assertEquals(2, table.columnCount)
    }

    @Test
    fun `a quoted field keeps its delimiter`() {
        val table = csv("a,b\n\"one, two\",three\n")
        assertEquals(listOf("one, two", "three"), table.rows.single())
    }

    @Test
    fun `a doubled quote inside a quoted field is one quote`() {
        val table = csv("a\n\"she said \"\"hi\"\"\"\n")
        assertEquals(listOf("she said \"hi\""), table.rows.single())
    }

    @Test
    fun `a newline inside quotes stays inside the field`() {
        val table = csv("a,b\n\"line one\nline two\",x\nlast,y\n")
        assertEquals(2, table.rows.size)
        assertEquals("line one\nline two", table.rows[0][0])
        assertEquals(listOf("last", "y"), table.rows[1])
    }

    /** The row that spans two lines still reports the line it *started* on. */
    @Test
    fun `rows report the source line they start on`() {
        val table = csv("a,b\n\"one\ntwo\",x\nthree,y\n")
        assertEquals(listOf(2, 4), table.rowLines)
    }

    @Test
    fun `windows line endings are one break`() {
        val table = csv("a,b\r\n1,2\r\n3,4\r\n")
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("3", "4"), table.rows[1])
    }

    /** A trailing newline is not a phantom empty row — Zed drops blank rows. */
    @Test
    fun `a trailing newline is not a row`() {
        assertEquals(1, csv("a,b\n1,2\n").rows.size)
        assertEquals(1, csv("a,b\n1,2").rows.size)
        assertEquals(1, csv("a,b\n1,2\n\n\n").rows.size)
    }

    /** The column count is the widest row's, not the header's. */
    @Test
    fun `a row wider than the header keeps its cells`() {
        val table = csv("a,b\n1,2,3,4\n")
        assertEquals(4, table.columnCount)
        assertEquals(listOf("1", "2", "3", "4"), table.rows.single())
    }

    @Test
    fun `an empty file is an empty table`() {
        assertTrue(csv("").isEmpty)
        assertTrue(csv("   \n\n").isEmpty)
        assertFalse(csv("a\n1\n").isEmpty)
    }

    @Test
    fun `tabs separate a tsv`() {
        val table = TableDocument.parse("a\tb\n1\t2\n", '\t')
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf("1", "2"), table.rows.single())
        assertEquals('\t', TableDocument.delimiterFor("data/rows.tsv"))
        assertEquals(',', TableDocument.delimiterFor("data/rows.csv"))
        assertEquals('|', TableDocument.delimiterFor("rows.PSV"))
        // Anything else defaults to a comma rather than refusing to open.
        assertEquals(',', TableDocument.delimiterFor("rows.txt"))
    }

    @Test
    fun `an empty cell is a cell`() {
        val table = csv("a,b,c\n1,,3\n")
        assertEquals(listOf("1", "", "3"), table.rows.single())
    }

    @Test
    fun `column widths come from the content and stay in range`() {
        val table = csv("id,description\n1,${"x".repeat(200)}\n")
        assertEquals(TableDocument.MIN_COLUMN_CHARS, TableDocument.columnWidth(table, 0))
        assertEquals(TableDocument.MAX_COLUMN_CHARS, TableDocument.columnWidth(table, 1))
    }

    /** A cell with a newline is measured by its longest line, not its length. */
    @Test
    fun `a multi-line cell is measured by its longest line`() {
        val table = csv("h\n\"ab\ncdefgh\"\n")
        assertEquals(TableDocument.MIN_COLUMN_CHARS, TableDocument.columnWidth(table, 0))
    }

    /** A file longer than the cap is shown as far as the cap and says so. */
    @Test
    fun `a very long file is truncated rather than held whole`() {
        val text = buildString {
            append("a\n")
            repeat(TableDocument.MAX_ROWS + 50) { append("row$it\n") }
        }
        val table = csv(text)
        assertTrue(table.isTruncated)
        assertEquals(TableDocument.MAX_ROWS - 1, table.rows.size)
    }
}
