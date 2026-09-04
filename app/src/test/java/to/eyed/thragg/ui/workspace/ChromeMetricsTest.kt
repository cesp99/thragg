package to.eyed.thragg.ui.workspace

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.ui.theme.GLYPH_EXTENT
import to.eyed.thragg.ui.theme.ThemeStore
import to.eyed.thragg.ui.theme.remsAt

/**
 * The chrome's metrics, pinned.
 *
 * Zed sets `window.rem_size = ui_font_size` (theme_settings/src/settings.rs:619),
 * so every chrome dimension in it is a multiple of the UI font size and bumping
 * that setting grows the rows and the gaps along with the text. Z-18 moved our
 * panel and menus onto the same footing, and the whole risk of that change is
 * arithmetic: a mistyped multiple moves a dimension at the *default* font
 * size, where nothing was supposed to move at all.
 *
 * So the first part of this file is that acceptance criterion, spelled out one
 * number at a time: **at `ui_font_size` = 16 every metric is exactly the dp
 * literal it replaced.** The rest checks that they then scale — and that the
 * dimensions Zed writes as `px(…)` do *not*, because growing those would make
 * us diverge from Zed at precisely the setting this work exists to honour.
 *
 * SHRUNK IN P4, not weakened. The tab strip, title bar, status bar and editor
 * toolbar this used to cover went with ui/workspace/ (docs/UI.md, "What is
 * removed"), and their metric objects went with them; TabMetrics,
 * TitleBarMetrics, StatusBarMetrics, ToolbarMetrics, TabPixels and
 * StatusBarPixels no longer exist to assert about. What survives is the two
 * surfaces that survived — the project panel behind the Files sheet, and the
 * context menu — plus the whole accessibility half, which was always the part
 * about Rem.kt rather than about any one bar. Rem.kt is kept and never
 * surfaced (docs/UI.md, "What is kept but not surfaced"); this is the test
 * that says so.
 */
class ChromeMetricsTest {

    /** The rem at the shipped default, which is Zed's default too. */
    private val default = ThemeStore.DEFAULT_UI_FONT_SIZE

    private fun at(uiFontSize: Float, multiple: Float): Dp = remsAt(uiFontSize, multiple)

    // --- Nothing moved at ui_font_size = 16 ---

    @Test
    fun defaultUiFontSizeIsSixteen() {
        assertEquals(16f, default, 0f)
        assertEquals(16.dp, at(default, 1f))
    }

    @Test
    fun projectPanelIsUnchangedAtTheDefault() {
        assertEquals(4.dp, at(default, PanelMetrics.ROW_GAP))
        assertEquals(6.dp, at(default, PanelMetrics.ROW_PADDING))
        assertEquals(24.dp, at(default, PanelMetrics.ROW_CONTENT))
        // The pitch: h_6 content box plus the wrapper's two 1px borders.
        assertEquals(26.dp, PanelMetrics.rowHeight(default))
        assertEquals(6.dp, at(default, PanelMetrics.STICKY_SHADOW))
        assertEquals(6.dp, at(default, PanelMetrics.STATUS_SLOT_END_PADDING))
        assertEquals(6.dp, at(default, PanelMetrics.DIRECTORY_DOT))
        assertEquals(12.dp, at(default, PanelMetrics.LIST_BOTTOM_PADDING))
        assertEquals(12.dp, at(default, PanelMetrics.MESSAGE_PADDING))
    }

    @Test
    fun contextMenuIsUnchangedAtTheDefault() {
        assertEquals(220.dp, at(default, MenuMetrics.MIN_WIDTH))
        assertEquals(4.dp, at(default, MenuMetrics.INSET))
        assertEquals(6.dp, at(default, MenuMetrics.ROW_PAD_X))
        assertEquals(2.dp, at(default, MenuMetrics.ROW_PAD_Y))
        assertEquals(16.dp, at(default, MenuMetrics.LABEL_TO_CHORD))
    }

    // --- …and they move together once the setting does ---

    @Test
    fun everyRemMetricScalesWithTheUiFontSize() {
        for (multiple in everyRemMultiple) {
            assertEquals(
                "rem($multiple) should double from 16 to 32",
                at(32f, multiple).value,
                at(16f, multiple).value * 2f,
                0.001f,
            )
            assertEquals(
                "rem($multiple) should be three quarters at 12",
                at(12f, multiple).value,
                at(16f, multiple).value * 0.75f,
                0.001f,
            )
        }
    }

    @Test
    fun theRowPitchOnlyGrowsByItsContentBox() {
        // 1px borders are `px(1.)` in Zed, so the pitch is `rems(1.5) + 2px`
        // and not `rems(1.625)` — those agree at 16 and nowhere else.
        assertEquals(26.dp, PanelMetrics.rowHeight(16f))
        assertEquals(32.dp, PanelMetrics.rowHeight(20f))
        assertEquals(32.5.dp, at(20f, 1.625f)) // what the wrong spelling gives
    }

    // --- The px constants stay px ---

    @Test
    fun pixelDimensionsDoNotScale() {
        // Every one of these is written `px(…)` in Zed, so a bigger UI font
        // must leave it exactly where it is: `indent_size` is a settings number
        // handed to `px()` (project_panel.rs:6140), the guide offset and end
        // padding are `px(15.)`/`px(4.)` (indent_guides.rs:33,
        // project_panel.rs:7215) and the borders are `px(1.)`/`px(2.)`.
        assertEquals(20.dp, PanelPixels.IndentPerLevel)
        assertEquals(1.dp, PanelPixels.GuideWidth)
        assertEquals(15.dp, PanelPixels.GuideOffset)
        assertEquals(4.dp, PanelPixels.GuideEndInset)
        assertEquals(2.dp, PanelPixels.ActiveRowRail)
        assertEquals(2.dp, PanelPixels.RowBorders)
    }

    // --- The accessibility floor, and the promise that it costs nothing ---

    /**
     * The ink of one line of chrome text, the way `glyphHeight` computes it.
     *
     * [textRatio] is the type scale's, from `ui/theme/Type.kt`: `TextSize::
     * Default` is `rems(0.875)` and `TextSize::Small` `rems(0.75)`
     * (ui/src/styles/typography.rs:138-141). [fontScale] is the *system's*
     * font size setting, which multiplies sp and leaves dp alone — the whole
     * reason a dp box can end up too small for the sp text in it.
     */
    private fun ink(uiFontSize: Float, textRatio: Float, fontScale: Float): Dp =
        (uiFontSize * textRatio * fontScale * GLYPH_EXTENT).dp

    private val bodyText = 0.875f
    private val labelText = 0.75f

    @Test
    fun theGlyphFloorChangesNothingAtTheDefaultFontScale() {
        // Every box the floor guards, against the ink it guards it from. If any
        // of these ever flipped, the "nothing moved at 16" invariant would be
        // broken by the accessibility fix rather than by the rem conversion.
        val body = ink(default, bodyText, fontScale = 1f)
        val label = ink(default, labelText, fontScale = 1f)
        assertEquals(18.2.dp.value, body.value, 0.01f)
        assertEquals(15.6.dp.value, label.value, 0.01f)

        assertTrue("panel row", body + PanelPixels.RowBorders < PanelMetrics.rowHeight(default))
        assertTrue("menu row", label < at(default, MenuMetrics.MIN_WIDTH))
    }

    @Test
    fun theGlyphFloorTakesOverBeforeTheTextIsCut() {
        // The panel row is the tightest box in the chrome, so it is the first
        // to grow: its 24dp content box holds 14sp of ink up to a system font
        // scale of about 1.32, and takes over from there.
        val row = PanelMetrics.rowHeight(default)
        assertTrue(ink(default, bodyText, fontScale = 1.3f) + PanelPixels.RowBorders < row)
        assertTrue(ink(default, bodyText, fontScale = 1.4f) + PanelPixels.RowBorders > row)

        // And at Android's largest setting the row is half as tall again,
        // rather than showing half a file name.
        assertEquals(
            36.4.dp.value,
            ink(default, bodyText, fontScale = 2f).value,
            0.01f,
        )
    }

    @Test
    fun theFloorIsAboutTheSystemScaleNotTheUiFontSetting() {
        // Raising `ui_font_size` moves box *and* text together, so the floor
        // stays out of the way: the same comparison holds at 20 and at 24.
        for (uiFontSize in listOf(12f, 16f, 20f, 24f)) {
            assertTrue(
                "ui_font_size $uiFontSize",
                ink(uiFontSize, bodyText, fontScale = 1f) + PanelPixels.RowBorders <
                    PanelMetrics.rowHeight(uiFontSize),
            )
        }
    }

    private val everyRemMultiple = listOf(
        PanelMetrics.ROW_GAP,
        PanelMetrics.ROW_PADDING,
        PanelMetrics.ROW_CONTENT,
        PanelMetrics.STICKY_SHADOW,
        PanelMetrics.STATUS_SLOT_END_PADDING,
        PanelMetrics.DIRECTORY_DOT,
        PanelMetrics.LIST_BOTTOM_PADDING,
        PanelMetrics.MESSAGE_PADDING,
        MenuMetrics.MIN_WIDTH,
        MenuMetrics.INSET,
        MenuMetrics.ROW_PAD_X,
        MenuMetrics.ROW_PAD_Y,
        MenuMetrics.LABEL_TO_CHORD,
    )
}
