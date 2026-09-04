package to.eyed.thragg.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The fold/wrap display map, stressed rather than exampled.
 *
 * Composing folds with soft wrap is the arithmetic most likely to drift —
 * a block can now measure *shorter* than its estimate, which nothing before
 * folding could do — so this walks forty randomised shapes and asserts the
 * three properties every caller depends on: a visible row round-trips
 * through its display row, display rows walk buffer rows monotonically and
 * never land on a hidden one, and unfolding restores the wrapped count.
 */
class FoldDisplayInvariantTest {

    private fun mapOver(lines: List<String>, columns: Int): DisplayMap {
        val src = lines.toMutableList()
        return DisplayMap({ src.size }, { f, l -> src.subList(f, l) }).also { it.configure(columns, 4) }
    }

    @Test
    fun roundTripHoldsAcrossFoldCycles() {
        val rnd = Random(7)
        repeat(40) { trial ->
            val n = 12 + rnd.nextInt(40)
            val lines = (0 until n).map { r ->
                if (rnd.nextInt(3) == 0) "a longer line that will wrap around $r" else "short $r"
            }
            val columns = if (trial % 2 == 0) 0 else 12
            val map = mapOver(lines, columns)

            // A few non-overlapping folds.
            val folds = mutableListOf<IntRange>()
            var at = 0
            while (at < n - 3) {
                if (rnd.nextInt(2) == 0) {
                    val len = 1 + rnd.nextInt(3)
                    val end = minOf(at + len, n - 2)
                    if (end > at) folds.add(at..end)
                    at = end + 1
                } else {
                    at += 1 + rnd.nextInt(2)
                }
            }
            // The map hides rows; a fold's own chip row stays visible.
            val hiddenRanges = folds.map { (it.first + 1)..it.last }.filter { !it.isEmpty() }
            map.setFoldedRows(hiddenRanges)

            val hidden = folds.flatMap { (it.first + 1)..it.last }.toSet()
            val visible = (0 until n).filter { it !in hidden }

            // Before a row of it has been measured, the height is already the
            // fold's own answer: one display row per visible row, which is
            // exactly right with wrapping off and a floor with it on. The
            // frame after a fold reads this number, so it may never be the
            // unfolded row count.
            val estimated = map.displayRowCount
            assertEquals("trial $trial estimate", visible.size, estimated)

            map.measureWindow(0, n * 4)
            assertTrue(
                "trial $trial measured $estimated -> ${map.displayRowCount}",
                map.displayRowCount >= estimated,
            )
            if (columns == 0) {
                assertEquals("trial $trial unwrapped height", visible.size, map.displayRowCount)
            }

            // Every visible buffer row round-trips through its display row.
            for (row in visible) {
                val d = map.displayRowOf(row)
                assertEquals("trial $trial row $row round trip", row, map.bufferRowOf(d))
            }
            // bufferRowOf is monotonic non-decreasing over display rows.
            var last = -1
            for (d in 0 until map.displayRowCount) {
                val r = map.bufferRowOf(d)
                assertTrue("trial $trial monotonic at $d ($last -> $r)", r >= last)
                assertTrue("trial $trial visible at $d (row $r)", r !in hidden)
                last = r
            }
            // Unfolding everything restores the unfolded row count.
            map.setFoldedRows(emptyList())
            map.measureWindow(0, n * 4)
            val expected = lines.sumOf { map.segmentCountOf(it) }
            assertEquals("trial $trial unfolded count", expected, map.displayRowCount)
        }
    }
}
