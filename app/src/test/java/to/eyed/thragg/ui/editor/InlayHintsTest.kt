package to.eyed.thragg.ui.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.InlayHintSettings

/**
 * The display half of inlay hints: a hint takes up room in the row's text
 * and nowhere else. Every column the pane measures goes through
 * [SplicedSegment], so a mapping off by one is a caret drawn inside `: i32`
 * — and a tap that lands there is a caret *in* text that does not exist.
 */
class InlayHintsTest {

    private fun type(row: Int, col: Int, label: String) =
        InlayHint(row, col, label, "type", paddingLeft = false, paddingRight = false)

    private fun parameter(row: Int, col: Int, label: String) =
        InlayHint(row, col, label, "parameter", paddingLeft = false, paddingRight = true)

    private val on = InlayHintSettings(enabled = true)

    // ---- the text ----

    @Test
    fun aHintIsInsertedBeforeTheCharacterAtItsColumn() {
        // `let x = 1` with `: i32` after `x` (column 5).
        val spliced = spliceInlays("let x = 1", 0, 9, listOf(type(0, 5, ": i32")))
        assertEquals("let x: i32 = 1", spliced.text)
        assertEquals(listOf(5 until 10), spliced.hintRanges)
        assertTrue(spliced.hasHints)
    }

    @Test
    fun paddingBecomesSpacesAroundTheLabel() {
        // Zed pads with a space per side the server asked for
        // (lsp_command.rs:3953-3962).
        val both = InlayHint(0, 5, "name:", "parameter", paddingLeft = true, paddingRight = true)
        assertEquals(" name: ", both.text)
        val right = both.copy(paddingLeft = false)
        assertEquals("f(a, name: b)", spliceInlays("f(a, b)", 0, 7, listOf(right)).text)
    }

    @Test
    fun withoutHintsTheSegmentIsTheTextItselfAndTheMappingIsIdentity() {
        val spliced = spliceInlays("hello world", 6, 11, emptyList())
        assertEquals("world", spliced.text)
        assertFalse(spliced.hasHints)
        assertEquals(3, spliced.toDisplay(3))
        assertEquals(3, spliced.toBuffer(3))
    }

    // ---- the two mappings ----

    @Test
    fun aColumnAfterTheHintIsShiftedByItsLength() {
        val spliced = spliceInlays("let x = 1", 0, 9, listOf(type(0, 5, ": i32")))
        assertEquals(4, spliced.toDisplay(4))
        // The caret standing after `x` lands after `: i32`, where Zed puts
        // it — a type hint anchors after its position.
        assertEquals(10, spliced.toDisplay(5))
        assertEquals(11, spliced.toDisplay(6))
        // A span *ending* at `x`'s column must stop before the hint.
        assertEquals(5, spliced.toDisplayBefore(5))
        assertEquals(11, spliced.toDisplayBefore(6))
    }

    @Test
    fun aTapInsideAHintLandsOnTheTextItAnnotates() {
        val spliced = spliceInlays("let x = 1", 0, 9, listOf(type(0, 5, ": i32")))
        assertEquals(4, spliced.toBuffer(4))
        for (offset in 5 until 10) assertEquals("offset $offset", 5, spliced.toBuffer(offset))
        assertEquals(5, spliced.toBuffer(10))
        assertEquals(6, spliced.toBuffer(11))
    }

    @Test
    fun theMappingsRoundTripAcrossSeveralHints() {
        val hints = listOf(parameter(0, 2, "a:"), parameter(0, 5, "b:"))
        val spliced = spliceInlays("f(1, 2)", 0, 7, hints)
        assertEquals("f(a: 1, b: 2)", spliced.text)
        for (col in 0..7) {
            assertEquals("column $col", col, spliced.toBuffer(spliced.toDisplay(col)))
        }
    }

    @Test
    fun highlightSpansMoveWithTheirTextAndNeverPaintTheHint() {
        val spliced = spliceInlays("let x = 1", 0, 9, listOf(type(0, 5, ": i32")))
        val shifted = spliced.shiftSpans(
            listOf(HighlightSpan(0, 3, 1), HighlightSpan(4, 5, 2), HighlightSpan(8, 9, 3)),
        )
        assertEquals(listOf(HighlightSpan(0, 3, 1), HighlightSpan(4, 5, 2), HighlightSpan(13, 14, 3)), shifted)
    }

    // ---- segments of a wrapped row ----

    @Test
    fun aHintAtTheEndOfARowShowsOnTheLastSegmentOnly() {
        val hint = type(0, 5, ": i32")
        // The row `let x` wrapped in two: `let ` and `x`.
        val first = spliceInlays("let x", 0, 4, listOf(hint))
        val second = spliceInlays("let x", 4, 5, listOf(hint))
        assertFalse(first.hasHints)
        assertEquals("x: i32", second.text)
    }

    @Test
    fun aHintAtASegmentBoundaryBelongsToTheSegmentThatStartsThere() {
        val hint = parameter(0, 4, "b:")
        val first = spliceInlays("f(a,b)", 0, 4, listOf(hint))
        val second = spliceInlays("f(a,b)", 4, 6, listOf(hint))
        assertFalse(first.hasHints)
        assertEquals("b: b)", second.text)
    }

    // ---- grouping and settings ----

    @Test
    fun hintsAreGroupedByRowAndOrderedByColumnWithParametersFirst() {
        val grouped = groupInlayHints(
            listOf(type(1, 9, ": T"), type(0, 5, ": i32"), parameter(0, 5, "x:")),
            on,
        )
        assertEquals(setOf(0, 1), grouped.keys)
        assertEquals(listOf("x:", ": i32"), grouped.getValue(0).map { it.label })
    }

    @Test
    fun aKindThatIsSwitchedOffIsNotShown() {
        val hints = listOf(type(0, 5, ": i32"), parameter(0, 7, "x:"), InlayHint(0, 9, "chain", null, false, false))
        assertEquals(
            listOf("x:"),
            groupInlayHints(hints, on.copy(showTypeHints = false, showOtherHints = false)).getValue(0).map { it.label },
        )
        assertTrue(groupInlayHints(hints, InlayHintSettings(enabled = false)).isEmpty())
    }

    // ---- the payload ----

    @Test
    fun parsesTheEnginesPayloadAndDropsAnEmptyLabel() {
        val hints = parseInlayHints(
            JSONObject(
                """{"hints":[
                    {"row":3,"col_utf16":7,"label":": String","kind":"type","padding_left":false,"padding_right":false},
                    {"row":4,"col_utf16":2,"label":"","kind":"parameter"},
                    {"row":5,"col_utf16":1,"label":"x","kind":null,"padding_left":true}
                ]}"""
            )
        )
        assertEquals(2, hints.size)
        assertEquals(InlayHint(3, 7, ": String", "type", false, false), hints[0])
        assertNull(hints[1].kind)
        assertTrue(hints[1].paddingLeft)
        assertTrue(parseInlayHints(null).isEmpty())
    }
}
