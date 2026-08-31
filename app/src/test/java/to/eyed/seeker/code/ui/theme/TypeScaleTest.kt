package to.eyed.seeker.code.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The token foundation, pinned.
 *
 * Type, shape and spacing are the three things in a design system that nothing
 * else can check. A colour that is wrong fails [ContrastTest]; a layout that is
 * wrong is visible on a phone. A type role that quietly kept Material's Roboto
 * metrics looks *fine* — it is only wrong beside the role above it, three
 * screens away, and by then nobody remembers which one moved.
 *
 * So the numbers are written twice on purpose: once in Type.kt/Shape.kt as the
 * definition and once here as the specification (docs/VISUAL.md,
 * "Foundations"). The duplication is the test. None of this needs a device —
 * `TextStyle`, `Dp` and `Shapes` are arithmetic.
 */
class TypeScaleTest {

    private val type: Typography = materialTypography()

    /** A role's size, line height and tracking in sp, as three plain floats. */
    private fun TextStyle.metrics(): Triple<Float, Float, Float> =
        Triple(fontSize.value, lineHeight.value, letterSpacing.value)

    private fun assertRole(
        name: String,
        style: TextStyle,
        size: Float,
        lineHeight: Float,
        weight: FontWeight,
        tracking: Float,
    ) {
        assertEquals("$name size", size, style.fontSize.value, 0f)
        assertEquals("$name lineHeight", lineHeight, style.lineHeight.value, 0f)
        assertEquals("$name tracking", tracking, style.letterSpacing.value, 0f)
        assertEquals("$name weight", weight, style.fontWeight)
    }

    /**
     * All fifteen roles, at the numbers the spec fixes.
     *
     * The six display/headline roles are the reason this test exists. Left
     * unset they keep Material's own 32-57sp Roboto metrics, and they are
     * reachable rather than theoretical: `AlertDialog` titles are
     * `headlineSmall` and `LargeTopAppBar`'s is `headlineMedium`, so they were
     * the largest type in the app and nobody had chosen them.
     */
    @Test
    fun `material scale is fixed sp at the specified metrics`() {
        assertRole("displayLarge", type.displayLarge, 48f, 54f, FontWeight.SemiBold, -0.4f)
        assertRole("displayMedium", type.displayMedium, 40f, 46f, FontWeight.SemiBold, -0.4f)
        assertRole("displaySmall", type.displaySmall, 34f, 40f, FontWeight.SemiBold, -0.3f)
        assertRole("headlineLarge", type.headlineLarge, 32f, 38f, FontWeight.SemiBold, -0.3f)
        assertRole("headlineMedium", type.headlineMedium, 28f, 34f, FontWeight.SemiBold, -0.3f)
        assertRole("headlineSmall", type.headlineSmall, 24f, 30f, FontWeight.SemiBold, -0.2f)
        assertRole("titleLarge", type.titleLarge, 20f, 26f, FontWeight.SemiBold, -0.1f)
        assertRole("titleMedium", type.titleMedium, 16f, 22f, FontWeight.SemiBold, 0f)
        assertRole("titleSmall", type.titleSmall, 14f, 20f, FontWeight.Medium, 0f)
        assertRole("bodyLarge", type.bodyLarge, 15f, 22f, FontWeight.Normal, 0.1f)
        assertRole("bodyMedium", type.bodyMedium, 14f, 20f, FontWeight.Normal, 0.1f)
        assertRole("bodySmall", type.bodySmall, 12f, 16f, FontWeight.Normal, 0.1f)
        assertRole("labelLarge", type.labelLarge, 13f, 18f, FontWeight.Medium, 0.1f)
        assertRole("labelMedium", type.labelMedium, 12f, 16f, FontWeight.Medium, 0.2f)
        assertRole("labelSmall", type.labelSmall, 11f, 15f, FontWeight.Medium, 0.2f)
    }

    /**
     * No role leaks a Material default.
     *
     * A blunter statement of the same thing: a `Typography()` with a role left
     * out is not distinguishable at the call site from one where the role was
     * chosen, so assert that every one of the fifteen differs from stock. If a
     * role is ever dropped from [materialTypography] this fails even if the
     * table above was updated to match the leak.
     */
    @Test
    fun `no material role is left at its stock metrics`() {
        val stock = Typography()
        val roles = listOf(
            "displayLarge" to (type.displayLarge to stock.displayLarge),
            "displayMedium" to (type.displayMedium to stock.displayMedium),
            "displaySmall" to (type.displaySmall to stock.displaySmall),
            "headlineLarge" to (type.headlineLarge to stock.headlineLarge),
            "headlineMedium" to (type.headlineMedium to stock.headlineMedium),
            "headlineSmall" to (type.headlineSmall to stock.headlineSmall),
            "titleLarge" to (type.titleLarge to stock.titleLarge),
            "titleMedium" to (type.titleMedium to stock.titleMedium),
            "titleSmall" to (type.titleSmall to stock.titleSmall),
            "bodyLarge" to (type.bodyLarge to stock.bodyLarge),
            "bodyMedium" to (type.bodyMedium to stock.bodyMedium),
            "bodySmall" to (type.bodySmall to stock.bodySmall),
            "labelLarge" to (type.labelLarge to stock.labelLarge),
            "labelMedium" to (type.labelMedium to stock.labelMedium),
            "labelSmall" to (type.labelSmall to stock.labelSmall),
        )
        val leaked = roles.filter { (_, pair) -> pair.first.metrics() == pair.second.metrics() }
        assertTrue(
            "these roles still carry Material's own metrics: ${leaked.map { it.first }}",
            leaked.isEmpty(),
        )
    }

    /** Every role draws in the face it was handed, not in a default. */
    @Test
    fun `material scale uses the supplied family`() {
        val scale = materialTypography(BundledFonts.buffer)
        listOf(
            scale.displayLarge, scale.displayMedium, scale.displaySmall,
            scale.headlineLarge, scale.headlineMedium, scale.headlineSmall,
            scale.titleLarge, scale.titleMedium, scale.titleSmall,
            scale.bodyLarge, scale.bodyMedium, scale.bodySmall,
            scale.labelLarge, scale.labelMedium, scale.labelSmall,
        ).forEach { assertEquals(BundledFonts.buffer, it.fontFamily) }
        // And the default is the chrome face Zed itself draws in.
        assertEquals(BundledFonts.ui, materialTypography().bodyMedium.fontFamily)
    }

    /**
     * The scale is monotone: nothing below a role is larger than it.
     *
     * Cheap, and it catches the transposition the table above cannot — two
     * roles whose numbers are both "right" and swapped.
     */
    @Test
    fun `material scale descends`() {
        val ladder: List<TextUnit> = listOf(
            type.displayLarge.fontSize, type.displayMedium.fontSize, type.displaySmall.fontSize,
            type.headlineLarge.fontSize, type.headlineMedium.fontSize, type.headlineSmall.fontSize,
            type.titleLarge.fontSize, type.titleMedium.fontSize, type.titleSmall.fontSize,
            type.bodyLarge.fontSize, type.bodyMedium.fontSize, type.bodySmall.fontSize,
            type.labelLarge.fontSize, type.labelMedium.fontSize, type.labelSmall.fontSize,
        )
        // displaySmall (34) sits below headlineLarge (32) in Material's own
        // naming too, so the ladder is checked within each family rather than
        // across all fifteen: display, then headline, then title, then body,
        // then label, each strictly descending.
        listOf(0..2, 3..5, 6..8, 9..11, 12..14).forEach { family ->
            family.zipWithNext().forEach { (a, b) ->
                assertTrue(
                    "role $b (${ladder[b].value}sp) is not smaller than role $a " +
                        "(${ladder[a].value}sp)",
                    ladder[b].value < ladder[a].value,
                )
            }
        }
    }

    /**
     * Leading tightens as the type grows, and stays inside the readable band.
     *
     * This is the rule the fifteen line heights encode and the one a future
     * edit is most likely to break by "rounding a role up": leading is a
     * function of measure, not of size. At 11sp a label needs 1.36em to stop
     * two lines of it merging; at 48sp a display line needs 1.13em, and giving
     * it the label's ratio would open a 17dp trench between two words of a
     * heading. IBM Plex Sans occupies 1.3em of the em box (Rem.kt,
     * `GLYPH_EXTENT`), so the display roles are deliberately set tighter than
     * their own glyph extent — correct for one or two lines of it, and the
     * reason nothing but display and headline is allowed below that mark.
     *
     * The ceiling is 1.6em for everything: past that a line stops being a line
     * and becomes a paragraph. Zed's half of the app sits right on it, at φ.
     */
    @Test
    fun `leading tightens as the type grows`() {
        val roles = listOf(
            "displayLarge" to type.displayLarge, "displayMedium" to type.displayMedium,
            "displaySmall" to type.displaySmall, "headlineLarge" to type.headlineLarge,
            "headlineMedium" to type.headlineMedium, "headlineSmall" to type.headlineSmall,
            "titleLarge" to type.titleLarge, "titleMedium" to type.titleMedium,
            "titleSmall" to type.titleSmall, "bodyLarge" to type.bodyLarge,
            "bodyMedium" to type.bodyMedium, "bodySmall" to type.bodySmall,
            "labelLarge" to type.labelLarge, "labelMedium" to type.labelMedium,
            "labelSmall" to type.labelSmall,
        )
        roles.forEach { (name, style) ->
            val ratio = style.lineHeight.value / style.fontSize.value
            assertTrue("$name leading ${"%.3f".format(ratio)}em is under 1.12em", ratio >= 1.12f)
            assertTrue("$name leading ${"%.3f".format(ratio)}em is over 1.6em", ratio <= 1.6f)
        }
        // Only display and headline may sit below the face's own 1.3em extent.
        roles.drop(6).forEach { (name, style) ->
            val ratio = style.lineHeight.value / style.fontSize.value
            assertTrue(
                "$name is body-sized and its leading ${"%.3f".format(ratio)}em is under " +
                    "IBM Plex Sans's ${GLYPH_EXTENT}em glyph extent",
                ratio >= GLYPH_EXTENT,
            )
        }
        // And the trend itself: the biggest role is tighter than the smallest.
        val display = type.displayLarge.lineHeight.value / type.displayLarge.fontSize.value
        val label = type.labelSmall.lineHeight.value / type.labelSmall.fontSize.value
        assertTrue("leading must tighten as the type grows", display < label)
    }

    /**
     * The Zed scale is untouched: ratios of the live `ui_font_size`, φ leading,
     * zero tracking.
     *
     * P2 rewrote the root around [materialTypography] and it would be very easy
     * to "tidy" this one into fixed sp at the same time. It must stay a
     * function of the setting — `window.rem_size = ui_font_size` is why Zed's
     * chrome grows when a user bumps it, and a port that hardcoded 16 would
     * make the setting do nothing.
     */
    @Test
    fun `zed scale still scales with ui font size`() {
        val phi = 1.618034f
        listOf(16f, 20f).forEach { size ->
            val scale = zedTypography(size)
            assertEquals("bodyLarge at $size", size, scale.bodyLarge.fontSize.value, 0.001f)
            assertEquals("bodyMedium at $size", size * 0.875f, scale.bodyMedium.fontSize.value, 0.001f)
            assertEquals("bodySmall at $size", size * 0.75f, scale.bodySmall.fontSize.value, 0.001f)
            assertEquals("labelSmall at $size", size * 0.625f, scale.labelSmall.fontSize.value, 0.001f)
            assertEquals(
                "bodyMedium leading at $size",
                size * 0.875f * phi,
                scale.bodyMedium.lineHeight.value,
                0.001f,
            )
            assertEquals("tracking at $size", 0f, scale.bodyMedium.letterSpacing.value, 0f)
        }
    }

    /**
     * The two scales are two scales.
     *
     * At the default `ui_font_size` of 16 the Zed body is 14sp and so is the
     * Material `titleSmall`, which is a coincidence worth not mistaking for
     * agreement: the leading differs (φ against a fixed 20) and so does the
     * weight. If these two ever produce identical `bodyMedium`s the seam has
     * collapsed and one of the halves has stopped being itself.
     */
    @Test
    fun `the two scales disagree`() {
        val zed = zedTypography(ThemeStore.DEFAULT_UI_FONT_SIZE)
        assertNotNull(zed.bodyMedium.fontSize)
        assertTrue(
            "the Zed and Material body roles have converged",
            zed.bodyMedium.metrics() != type.bodyMedium.metrics(),
        )
    }

    /** The 4dp grid is a 4dp grid. */
    @Test
    fun `spacing steps land on the grid`() {
        assertEquals(4.dp, MD.space1)
        assertEquals(8.dp, MD.space2)
        assertEquals(12.dp, MD.space3)
        assertEquals(16.dp, MD.space4)
        assertEquals(24.dp, MD.space6)
        assertEquals(32.dp, MD.space8)
        listOf(MD.space1, MD.space2, MD.space3, MD.space4, MD.space6, MD.space8).forEach {
            assertEquals("${it.value}dp is off the 4dp grid", 0f, it.value % 4f, 0f)
        }
    }

    /**
     * The radii, and the fact that [SeekerShapes] is made of them.
     *
     * The scale is by ROLE — 8dp code blocks and tool rows, 12dp cards, 16dp
     * bubbles and a sheet's inner cards, 20dp pills, 24dp a sheet's own top
     * corners — so the assertion that matters is that Material's five slots are
     * filled from the same five numbers rather than from a second table.
     */
    @Test
    fun `shape scale matches the radii it is built from`() {
        assertEquals(4.dp, MD.radiusXs)
        assertEquals(8.dp, MD.radiusSm)
        assertEquals(12.dp, MD.radiusMd)
        assertEquals(16.dp, MD.radiusLg)
        assertEquals(24.dp, MD.radiusXl)
        assertEquals(20.dp, MD.pill)
        assertEquals(1.dp, MD.hairline)

        assertEquals(RoundedCornerShape(MD.radiusXs), SeekerShapes.extraSmall)
        assertEquals(RoundedCornerShape(MD.radiusSm), SeekerShapes.small)
        assertEquals(RoundedCornerShape(MD.radiusMd), SeekerShapes.medium)
        assertEquals(RoundedCornerShape(MD.radiusLg), SeekerShapes.large)
        assertEquals(RoundedCornerShape(MD.radiusXl), SeekerShapes.extraLarge)
    }

    /**
     * The heights that recur, and the touch floor among them.
     *
     * [MD.rowMin] is 48dp because that is Android's accessibility floor and
     * WCAG 2.5.8's; it is the height of a row that is *drawn*, which is a
     * different measurement from `Modifier.touchTarget()`'s hit box and happens
     * to be the same number.
     */
    @Test
    fun `recurring heights`() {
        assertEquals(48.dp, MD.rowMin)
        assertEquals(56.dp, MD.barHeight)
        assertEquals(36.dp, MD.stripHeight)
        assertTrue("a tappable row may not be under 48dp", MD.rowMin >= 48.dp)
    }

    /**
     * The six off-grid values are the six that were argued for, and no more.
     *
     * Each is the inside of one small block rather than a gap between blocks,
     * which is why the 4dp rhythm does not apply to them. Pinning them here is
     * the difference between named exceptions and the drift back to sixty
     * unexamined literals that this object replaced.
     */
    @Test
    fun `the named off-grid values`() {
        assertEquals(6.dp, MD.iconGap)
        assertEquals(10.dp, MD.rowPadY)
        assertEquals(9.dp, MD.composerPadY)
        assertEquals(3.dp, MD.pillPadY)
        assertEquals(10.dp, MD.pillPadX)
        assertEquals(2.dp, MD.tagPadY)
        assertEquals(7.dp, MD.tagPadX)
        assertEquals(5.dp, MD.toolPadY)
        assertEquals(8.dp, MD.toolPadX)

        val offGrid: List<Dp> = listOf(MD.iconGap, MD.rowPadY, MD.composerPadY, MD.pillPadY)
        offGrid.forEach {
            assertTrue("${it.value}dp is on the grid and does not need naming", it.value % 4f != 0f)
        }
    }

    /**
     * The named durations, and the one asymmetry among them that carries an
     * argument: a band leaves more slowly than it arrives.
     *
     * A finished run is the thing most likely to be being read at the moment
     * its strip collapses, so it settles rather than blinking away — and it is
     * held four seconds first, because its final counts are shown nowhere else.
     */
    @Test
    fun `motion durations`() {
        assertEquals(180, Durations.BAND_IN)
        assertEquals(240, Durations.BAND_OUT)
        assertTrue("a band must leave more slowly than it arrives", Durations.BAND_OUT > Durations.BAND_IN)
        assertEquals(4000L, Durations.RUN_HOLD)
        assertEquals(120, Durations.BLOCK_FADE)
        assertEquals(1600L, Durations.COPY_CONFIRM)
        assertEquals(1000L, Durations.TICKER)
        assertEquals(8, Durations.SPINNER_FRAMES)
        assertEquals(50L, Durations.SPINNER_FRAME)
        assertEquals(
            "the spinner's cycle is 400ms",
            400L,
            Durations.SPINNER_FRAME * Durations.SPINNER_FRAMES,
        )
        // Every colour and alpha tween sits in the 150-250ms band, and nothing
        // outside it: under 150 a colour change is a flash, over 250 the eye
        // has moved on before it lands.
        assertTrue(Durations.TINT in 150..250)
    }

    /** [TabularNums] is the OpenType tag, not a description of it. */
    @Test
    fun `tabular figures tag`() {
        assertEquals("tnum", TabularNums)
    }

    /** Nothing in the Material scale is expressed in anything but sp. */
    @Test
    fun `material scale is in sp`() {
        assertEquals(15.sp, type.bodyLarge.fontSize)
        assertEquals(14.sp, type.bodyMedium.fontSize)
    }
}
