package to.eyed.seeker.code.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge, checked against every theme the app can paint with.
 *
 * This is the load-bearing test in the redesign: every screen reads the scheme
 * this file derives, so a role that is wrong here is wrong on all of them at
 * once, on a theme the author of the screen may never have opened. Eleven
 * themes times both appearances is cheap on the host and it is the only way to
 * know that the Ayu Light column — the one that breaks things — still holds.
 */
class MaterialBridgeTest {

    /**
     * Cross-ground checks compare an ink solved on one role against a
     * *neighbouring* one, where the last bisection step can leave the ratio a
     * hair under the bar. A hundredth of a ratio point is far below the
     * threshold of anything visible and far above float noise.
     */
    private val slack = 0.01f

    private fun assertReadable(label: String, ink: Color, on: Color, minRatio: Float = TEXT_RATIO) {
        val ratio = contrastRatio(ink, on)
        assertTrue("$label is ${"%.2f".format(ratio)}:1, needs $minRatio", ratio >= minRatio - slack)
    }

    /**
     * A rung is its own Zed key, or that key pushed a hair further from the
     * canvas.
     *
     * The distance is measured in channels rather than in luminance because
     * sRGB is compressed at both ends: the same 3% blend that moves Ayu Dark's
     * near-black rung by 0.005 of luminance moves Ayu Light's near-white one by
     * 0.071. In channels both are what they look like — five units and ten,
     * against the 255 the theme was authored in.
     */
    private fun assertNudged(theme: ZedTheme, role: String, key: Color, rung: Color) {
        val moved = rung.luminance() - key.luminance()
        val away = if (theme.isDark) moved >= 0f else moved <= 0f
        assertTrue("${theme.name} $role moved toward the canvas", away)
        val channels = maxOf(
            abs(rung.red - key.red),
            abs(rung.green - key.green),
            abs(rung.blue - key.blue),
        )
        assertTrue(
            "${theme.name} $role moved ${(channels * 255).toInt()} units: a colour, not a step",
            channels < 0.06f,
        )
    }

    private fun ColorScheme.rungs(): List<Color> = listOf(
        surfaceContainerLowest,
        surfaceContainerLow,
        surfaceContainer,
        surfaceContainerHigh,
        surfaceContainerHighest,
    )

    // ---------------------------------------------------------------- band A

    @Test
    fun `the surface ladder is monotone away from the canvas in both appearances`() {
        for (theme in BundledThemes.all) {
            val rungs = theme.palette().scheme.rungs()
            val luminances = rungs.map { it.luminance() }
            for (i in 1 until luminances.size) {
                val step = luminances[i] - luminances[i - 1]
                val away = if (theme.isDark) step > 0f else step < 0f
                assertTrue(
                    "${theme.name} rung $i runs the wrong way: $luminances",
                    away,
                )
            }
        }
    }

    @Test
    fun `the five rungs are pairwise distinct after the de-dupe`() {
        for (theme in BundledThemes.all) {
            val rungs = theme.palette().scheme.rungs()
            assertEquals("${theme.name} has collided rungs: $rungs", 5, rungs.toSet().size)
            // Distinct as *colours* is not enough — two rungs a user cannot
            // tell apart are the bug. In nine of the eleven bundled themes
            // element.background and elevated_surface.background are the same
            // hex, which is why the de-dupe exists at all.
            //
            // The bar is luminance rather than a hex difference, and it is low
            // on purpose: sRGB is compressed near black, so the smallest real
            // step in any bundled theme is Ayu Dark's nudged rung 2 at 0.0045 —
            // five units per channel, the same size as the steps Zed's own
            // ladder uses on that theme.
            for (i in 1 until rungs.size) {
                assertTrue(
                    "${theme.name} rungs ${i - 1} and $i are indistinguishable",
                    abs(rungs[i].luminance() - rungs[i - 1].luminance()) >= 0.003f,
                )
            }
        }
    }

    @Test
    fun `band A is zeds own keys, unmodified`() {
        for (theme in BundledThemes.all) {
            val scheme = theme.palette().scheme
            assertEquals(theme.name, theme.color("editor.background"), scheme.background)
            assertEquals(theme.name, theme.color("panel.background"), scheme.surface)
            assertEquals(theme.name, theme.color("editor.background"), scheme.surfaceContainerLowest)
            assertEquals(theme.name, theme.color("border"), scheme.outline)
            assertEquals(theme.name, theme.color("border.variant"), scheme.outlineVariant)
            assertEquals(theme.name, theme.color("error"), scheme.error)
            assertEquals(theme.name, theme.color("text"), scheme.inverseSurface)
            assertEquals(theme.name, theme.color("editor.background"), scheme.inverseOnSurface)
            assertEquals(theme.name, theme.color("element.selected"), scheme.secondaryContainer)
            // Every rung above the canvas is its key, or its key nudged: the
            // de-dupe fires on `surfaceContainer` in all eleven bundled themes
            // (nine share a hex with the rung below, One Dark differs by one
            // unit of red), and the nudge then PROPAGATES — on One Dark it
            // pushes rung 2 to within 0.004 of `element.hover`, which moves rung
            // 3 as well, and on Ayu Dark `element.hover` and `background` are
            // 0.0052 apart to begin with. A moved rung must still be its own
            // key, moved AWAY from the canvas and by a hair: a de-dupe that
            // moved a rung toward the canvas, or moved it a visible distance,
            // would be inventing a colour rather than separating two.
            val element = theme.color("element.background")
            assertNudged(theme, "surfaceVariant", element, scheme.surfaceVariant)
            assertNudged(theme, "surfaceContainerLow", element, scheme.surfaceContainerLow)
            assertNudged(
                theme, "surfaceContainer",
                theme.color("elevated_surface.background"), scheme.surfaceContainer,
            )
            assertNudged(
                theme, "surfaceContainerHigh",
                theme.color("element.hover"), scheme.surfaceContainerHigh,
            )
            assertNudged(
                theme, "surfaceContainerHighest",
                theme.color("background"), scheme.surfaceContainerHighest,
            )
            // bright/dim are the two canvases, sorted, so one expression is
            // right in both appearances.
            val canvases = listOf(theme.color("editor.background"), theme.color("background"))
            assertEquals(theme.name, canvases.maxBy { it.luminance() }, scheme.surfaceBright)
            assertEquals(theme.name, canvases.minBy { it.luminance() }, scheme.surfaceDim)
        }
    }

    /**
     * `secondaryContainer` is a SIXTH RUNG OF THE FILL LADDER, and that is why
     * no stock M3 default may be left holding it.
     *
     * This is the trap that shipped twice. Material's own components use the
     * role as a *state*: `NavigationBarItem`'s selection pill,
     * `SliderTokens.InactiveTrackColor`, `ProgressIndicatorTokens.TrackColor`,
     * `FilterChipTokens.FlatSelectedContainerColor`,
     * `OutlinedSegmentedButtonTokens.SelectedContainerColor`. That is correct
     * in a stock M3 palette, where `secondaryContainer` is a pale tint of the
     * seed sitting a long way from every surface. It is wrong here, because
     * the bridge maps it to Zed's `element.selected` — a real fill from the
     * same family as the ladder, and, as this asserts, one that lands a step
     * BEYOND `surfaceContainerHighest` in every bundled theme, in both
     * appearances. Anything painted in it therefore draws as the most raised
     * panel on the screen rather than as a state on the panel it is part of,
     * and, at 1.31–1.57:1 against `surface`, it is not even a mark: it is a
     * surface, and it is louder than every surface the app actually uses.
     *
     * The two halves are the two ways the mistake shows up, so both are
     * pinned. `ShellNavBar`'s pill and `SettingsScreen`'s slider track both
     * read this role by default and both were wrong on the device before they
     * were overridden; the ratchet that stops the third one is
     * [StockDefaultsTest].
     */
    @Test
    fun `secondaryContainer is a sixth fill rung, not a state, on every theme`() {
        for (theme in BundledThemes.all) {
            val s = theme.palette().scheme
            val role = s.secondaryContainer.luminance()
            val top = s.surfaceContainerHighest.luminance()
            // "Away from the canvas" is up on a dark theme and down on a light
            // one, which is the same convention the ladder itself is checked
            // against above.
            val beyond = if (theme.isDark) role > top else role < top
            assertTrue(
                "${theme.name} secondaryContainer ${"%.4f".format(role)} is inside the " +
                    "ladder (top rung ${"%.4f".format(top)}) — if that is now true the trap " +
                    "below has changed shape and the call sites that work around it need " +
                    "re-reading, not deleting",
                beyond,
            )
            val ratio = contrastRatio(s.secondaryContainer, s.surface)
            assertTrue(
                "${theme.name} secondaryContainer is ${"%.2f".format(ratio)}:1 on surface, " +
                    "which would make it a legible MARK — it is meant to be a fill",
                ratio < MARK_RATIO,
            )
        }
    }

    @Test
    fun `surface tint is transparent, so tonal elevation washes nothing`() {
        // The single biggest existing bug in the bridge: left as `primary`,
        // Material's tonal elevation paints the accent over every raised
        // Surface, Card, DropdownMenu and ModalBottomSheet in the app.
        for (theme in BundledThemes.all) {
            assertEquals(theme.name, Color.Transparent, theme.palette().scheme.surfaceTint)
        }
    }

    @Test
    fun `the scrim is material's, because zed has none`() {
        // Within a 255th: Compose packs an sRGB colour to 8 bits per channel,
        // so 0.32 comes back as 82/255.
        assertEquals(0.60f, BundledThemes.named("One Dark").palette().scheme.scrim.alpha, 0.005f)
        assertEquals(0.32f, BundledThemes.named("One Light").palette().scheme.scrim.alpha, 0.005f)
        assertEquals(Color.Black.red, BundledThemes.named("One Dark").palette().scheme.scrim.red, 0f)
    }

    // ---------------------------------------------------------------- band B

    @Test
    fun `every material ink clears its pairing role on every bundled theme`() {
        for (theme in BundledThemes.all) {
            val s = theme.palette().scheme
            val where = theme.name
            assertReadable("$where onPrimary/primary", s.onPrimary, s.primary)
            assertReadable("$where onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer)
            assertReadable("$where onSecondary/secondary", s.onSecondary, s.secondary)
            assertReadable("$where onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer)
            assertReadable("$where onTertiary/tertiary", s.onTertiary, s.tertiary)
            assertReadable("$where onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer)
            assertReadable("$where onError/error", s.onError, s.error)
            assertReadable("$where onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer)
            assertReadable("$where onBackground/background", s.onBackground, s.background)
            assertReadable("$where onSurface/surface", s.onSurface, s.surface)
            assertReadable("$where onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
            assertReadable("$where onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant)
            assertReadable("$where inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface)
        }
    }

    @Test
    fun `onSurface is legible on every rung of the ladder, which is where a sheet puts it`() {
        for (theme in BundledThemes.all) {
            val s = theme.palette().scheme
            for ((index, rung) in s.rungs().withIndex()) {
                assertReadable("${theme.name} onSurface/rung$index", s.onSurface, rung)
            }
            assertReadable("${theme.name} onSurface/surfaceBright", s.onSurface, s.surfaceBright)
            assertReadable("${theme.name} onSurface/surfaceDim", s.onSurface, s.surfaceDim)
        }
    }

    @Test
    fun `the accent is legible as text on the three grounds it is drawn on`() {
        // primary is solved on surfaceContainer — a card — and has to survive
        // being printed on the two canvases as well. It is NOT asserted on the
        // top of the ladder: a link on a hover fill is not a thing this app
        // draws, and solving for it would darken every accent needlessly.
        for (theme in BundledThemes.all) {
            val s = theme.palette().scheme
            assertReadable("${theme.name} primary/surfaceContainer", s.primary, s.surfaceContainer)
            assertReadable("${theme.name} primary/surface", s.primary, s.surface)
            assertReadable("${theme.name} primary/background", s.primary, s.background)
            assertReadable("${theme.name} secondary/surface", s.secondary, s.surface)
            assertReadable("${theme.name} tertiary/surface", s.tertiary, s.surface)
        }
    }

    @Test
    fun `the two ayu light bugs the bridge was written to fix are fixed`() {
        val theme = BundledThemes.named("Ayu Light")
        val s = theme.palette().scheme
        // onPrimary was `editor.background` unconditionally (Theme.kt:139) —
        // a 2.84:1 label on every filled button on this theme.
        val rawLabel = contrastRatio(theme.color("editor.background"), theme.color("text.accent"))
        assertEquals(2.84f, rawLabel, 0.02f)
        // The fix is in `primary`, not in the choice of ink: pickInk keeps the
        // theme's own canvas as the label whenever it clears, and once the
        // accent has been solved to 4.5:1 on the container the canvas clears on
        // it at 5.6:1. Same two colours, one of them moved.
        assertNotEquals(theme.color("text.accent"), s.primary)
        assertTrue(contrastRatio(s.onPrimary, s.primary) >= TEXT_RATIO)
        // onSurfaceVariant was `text.muted` raw — 2.79:1, times 455 call sites.
        assertEquals(2.79f, contrastRatio(theme.color("text.muted"), s.surface), 0.02f)
        assertTrue(contrastRatio(s.onSurfaceVariant, s.surface) >= TEXT_RATIO)
    }

    @Test
    fun `the seed falls back down zeds own chain`() {
        val cursor = Color(0xFF74ADE8)
        val focused = Color(0xFF2E7D32)
        val base = mapOf(
            "editor.background" to Color(0xFF1B1B1B),
            "panel.background" to Color(0xFF232323),
            "element.background" to Color(0xFF2B2B2B),
            "elevated_surface.background" to Color(0xFF333333),
            "element.hover" to Color(0xFF3B3B3B),
            "background" to Color(0xFF434343),
            "text" to Color(0xFFEEEEEE),
            "text.muted" to Color(0xFF9A9A9A),
            "border.focused" to focused,
        )
        // No `text.accent`, but a players entry: the cursor is the seed, and
        // One Dark's cursor is the same #74ade8 its text.accent is.
        val withPlayers = theme(base, cursor = cursor, players = listOf(cursor))
        assertEquals(cursor, withPlayers.palette().scheme.primary)
        // No players either: `border.focused` is next.
        val withBorder = theme(base, cursor = cursor, players = emptyList())
        assertEquals(
            readable(focused, on = withBorder.palette().scheme.surfaceContainer),
            withBorder.palette().scheme.primary,
        )
    }

    // ---------------------------------------------------------------- band C

    @Test
    fun `every seeker ink and mark clears on the card ground`() {
        for (theme in BundledThemes.all) {
            val c = theme.palette().seeker
            val container = theme.palette().scheme.surfaceContainer
            val inks = mapOf(
                "accentInk" to c.accentInk,
                "addedInk" to c.addedInk,
                "removedInk" to c.removedInk,
                "warnInk" to c.warnInk,
                "agentInk" to c.agentInk,
                "dangerInk" to c.dangerInk,
            )
            for ((name, ink) in inks) {
                assertReadable("${theme.name} $name", ink, c.cardGround)
                // The card is the hardest ground; the bare canvas is easier.
                assertReadable("${theme.name} $name on the canvas", ink, container)
            }
            val marks = mapOf(
                "accentMark" to c.accentMark,
                "addedMark" to c.addedMark,
                "removedMark" to c.removedMark,
                "warnMark" to c.warnMark,
            )
            for ((name, mark) in marks) {
                assertReadable("${theme.name} $name", mark, c.cardGround, MARK_RATIO)
            }
        }
    }

    @Test
    fun `the agent purple is the one fixed hue, and it survives being solved`() {
        for (theme in BundledThemes.all) {
            val palette = theme.palette()
            assertEquals(theme.name, AgentAccent, palette.seeker.agentAccent)
            // Solved for legibility, but still recognisably the same purple in
            // every theme: blue over red over green, as #AD7BF9 is. That is the
            // point of fixing it — the TUI and the desktop client draw it too.
            val tertiary = palette.scheme.tertiary
            assertTrue(
                "${theme.name} tertiary is no longer purple: $tertiary",
                tertiary.blue > tertiary.red && tertiary.red > tertiary.green,
            )
        }
    }

    @Test
    fun `the mode table answers manifest names and mode ids`() {
        val colors = BundledThemes.named("One Dark").palette().seeker
        assertEquals(Color(0xFF34D399), colors.modeColor("green"))
        assertEquals(Color(0xFF60A5FA), colors.modeColor("Cyan"))
        assertEquals(Color(0xFFBD93F9), colors.modeColor("plan"))
        assertEquals(Color(0xFF34D399), colors.modeColor("coding"))
        assertEquals(Color(0xFF60A5FA), colors.modeColor("ask"))
        // An unknown name is the accent rather than a guessed hue; null is the
        // same, because the caller's `category != "mode"` guard hands us null.
        assertEquals(colors.accentInk, colors.modeColor("teal"))
        assertEquals(colors.accentInk, colors.modeColor(null))
    }

    @Test
    fun `every mode reads as text on every theme`() {
        val modes = listOf(
            "blue", "green", "cyan", "yellow", "magenta", "purple", "red",
            "plan", "coding", "ask", null,
        )
        for (theme in BundledThemes.all) {
            val c = theme.palette().seeker
            for (mode in modes) {
                assertReadable("${theme.name} mode $mode", c.modeInk(mode), c.cardGround)
            }
        }
    }

    @Test
    fun `the hairline is zeds own, and the card ground is not the bare surface`() {
        for (theme in BundledThemes.all) {
            val palette = theme.palette()
            assertEquals(theme.name, theme.color("border.variant"), palette.seeker.hairline)
            assertNotEquals(theme.name, palette.scheme.surfaceContainer, palette.seeker.cardGround)
            assertEquals(theme.name, theme.isDark, palette.seeker.isDark)
        }
    }

    /** A synthetic theme, for the derivations no bundled file exercises. */
    private fun theme(
        colors: Map<String, Color>,
        cursor: Color,
        players: List<Color>,
    ) = ZedTheme(
        name = "Synthetic",
        family = "Test",
        isDark = true,
        colors = colors,
        syntax = emptyMap(),
        cursor = cursor,
        selection = cursor.copy(alpha = 0.24f),
        players = players,
    )
}
