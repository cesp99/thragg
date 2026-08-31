package to.eyed.seeker.code.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `secondaryContainer` trap, made into a build failure.
 *
 * Material 3 spends one role, `secondaryContainer`, on *selection state*: the
 * navigation bar's pill, a slider's unspent track, a progress bar's track, a
 * selected filter chip, a selected segment, a tonal button's fill. That is
 * right in a stock M3 palette, where the role is a pale tint of the seed
 * sitting a long way from every surface in the scheme.
 *
 * It is wrong here. The bridge maps it to Zed's `element.selected`
 * (MaterialBridge.kt, band A), which is a real fill from the same family as
 * the surface ladder and lands a step BEYOND `surfaceContainerHighest` on
 * every bundled theme in both appearances — which [MaterialBridgeTest]
 * measures, under "secondaryContainer is a sixth fill rung". So a component
 * that keeps the default paints the loudest surface on the screen where the
 * design wanted a state, and no theme makes it look intentional.
 *
 * IT HAS ALREADY HAPPENED TWICE, which is why this is a test and not a
 * paragraph. `ShellNavBar` shipped its selection pill in the role and it read
 * as a second raised panel behind the glyph; `SettingsScreen`'s `SliderRow`
 * shipped the *track* in it — and, because `defaultSliderColors` cross-wires
 * the tick colours to the opposite half's track, also shipped `primary` dots
 * along the unselected half. Both were found by looking at the phone. The
 * third one will not be.
 *
 * THE CHECK IS DELIBERATELY COARSE: a file that imports one of these
 * components must also NAME the colour parameter that overrides the default.
 * It cannot tell which call site the override belongs to, and it is not made
 * cleverer — a parser that tracked arguments per call would be a Kotlin
 * front-end, and the failure mode it would buy (a file with two sliders, one
 * of them overridden) is one a reviewer sees. What it does catch is the whole
 * of what actually happens: a stock component dropped in with `colors =`
 * left off entirely.
 *
 * Removing a component's entry is not the way to pass. If a call site really
 * wants the role — a genuinely raised tonal surface — say so at the call site
 * by writing the role's name out, which satisfies the check honestly.
 */
class StockDefaultsTest {

    /**
     * Stock component to the parameter that proves its default was replaced.
     *
     * The key is matched as a whole import line, so `SliderDefaults` does not
     * satisfy `Slider` and `NavigationBarItemDefaults` does not satisfy
     * `NavigationBarItem`. The value is the M3 parameter whose token is
     * `SecondaryContainer` or `OnSecondaryContainer` in material3 1.4.0's
     * token files — the one the call site has to write to take the role back.
     */
    private val stockDefaults: Map<String, String> = mapOf(
        // SliderTokens.InactiveTrackColor, and the tick cross-wiring behind it.
        "Slider" to "inactiveTrackColor",
        // NavigationBarTokens.ItemActiveIndicatorColor.
        "NavigationBarItem" to "indicatorColor",
        // NavigationRailColorTokens.ItemActiveIndicator.
        "NavigationRailItem" to "indicatorColor",
        // NavigationDrawerTokens.ActiveIndicatorColor.
        "NavigationDrawerItem" to "selectedContainerColor",
        // FilterChipTokens.FlatSelectedContainerColor.
        "FilterChip" to "selectedContainerColor",
        // InputChipTokens.SelectedContainerColor.
        "InputChip" to "selectedContainerColor",
        // OutlinedSegmentedButtonTokens.SelectedContainerColor.
        "SegmentedButton" to "activeContainerColor",
        // ProgressIndicatorTokens.TrackColor, on both shapes.
        "LinearProgressIndicator" to "trackColor",
        "CircularProgressIndicator" to "trackColor",
        // FilledTonalButtonTokens / FilledTonalIconButtonTokens ContainerColor.
        "FilledTonalButton" to "containerColor",
        "FilledTonalIconButton" to "containerColor",
    )

    @Test
    fun `every stock component that defaults to secondaryContainer overrides it`() {
        val offenders = mutableListOf<String>()
        for (file in sourceFiles()) {
            val text = file.readText()
            for ((component, override) in stockDefaults) {
                if ("import androidx.compose.material3.$component\n" !in text) continue
                if (override in text) continue
                offenders += "  ${relative(file)} uses $component and never writes $override"
            }
        }
        assertTrue(
            "These files drop in a stock M3 component and leave a colour role that this\n" +
                "app's bridge maps to Zed's element.selected — a fill a step beyond the top\n" +
                "of the surface ladder, so the state draws as the most raised panel on the\n" +
                "screen. Name the parameter and give it the design's own value: a selection\n" +
                "is primary at 16%, an unspent track is surfaceContainerHighest.\n" +
                "See docs/VISUAL.md, \"Foundations\", THE secondaryContainer TRAP.\n\n" +
                offenders.sorted().joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun relative(file: File): String =
        file.relativeTo(sourceRoot()).path.replace(File.separatorChar, '/')

    private fun sourceFiles(): List<File> =
        sourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** The app module's Kotlin, found from whatever directory Gradle forks in. */
    private fun sourceRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "src/main/res/values/strings.xml").isFile) {
            dir = dir.parentFile ?: error("cannot find the app module from ${File("").absolutePath}")
        }
        return File(dir, "src/main/java/to/eyed/seeker/code")
    }
}
