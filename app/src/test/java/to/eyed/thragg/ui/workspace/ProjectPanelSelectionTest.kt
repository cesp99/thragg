package to.eyed.thragg.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Shift-click ranges in the project panel — Zed's `marked_entries`
 * (project_panel.rs:1754-1760).
 *
 * The rule is "everything between the anchor and the click, in list order,
 * inclusive", and the interesting cases are the ones where the anchor is
 * *behind* the click and where it has stopped existing — a directory
 * collapsed under it, which is an ordinary thing to do between two clicks.
 */
class ProjectPanelSelectionTest {

    private val rows = listOf("Cargo.toml", "README.md", "src", "src/main.rs", "src/lib.rs")

    @Test
    fun `a range runs forwards from the anchor`() {
        assertEquals(
            listOf("README.md", "src", "src/main.rs"),
            markedRange(rows, anchor = "README.md", target = "src/main.rs"),
        )
    }

    @Test
    fun `a range runs backwards from the anchor just as well`() {
        assertEquals(
            listOf("README.md", "src", "src/main.rs"),
            markedRange(rows, anchor = "src/main.rs", target = "README.md"),
        )
    }

    @Test
    fun `a range onto the anchor itself is one row`() {
        assertEquals(
            listOf("src"),
            markedRange(rows, anchor = "src", target = "src"),
        )
    }

    @Test
    fun `no anchor marks the clicked row alone`() {
        assertEquals(
            listOf("src"),
            markedRange(rows, anchor = null, target = "src"),
        )
    }

    @Test
    fun `an anchor that has scrolled out of the tree marks the clicked row`() {
        // Collapsing a directory takes its children out of the list; a
        // shift-click afterwards must still do something visible.
        assertEquals(
            listOf("src"),
            markedRange(rows, anchor = "src/gone.rs", target = "src"),
        )
    }

    @Test
    fun `a target that is not in the tree marks nothing`() {
        assertEquals(
            emptyList<String>(),
            markedRange(rows, anchor = "src", target = "not-here.rs"),
        )
    }

    @Test
    fun `the whole tree is a valid range`() {
        assertEquals(
            rows,
            markedRange(rows, anchor = rows.first(), target = rows.last()),
        )
    }
}
