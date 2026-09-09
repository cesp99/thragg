package to.eyed.thragg.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock has to be readable in every theme — G-15.
 *
 * The status bar is drawn by the system over *our* background, and nothing was
 * telling it which way round that background is: `enableEdgeToEdge()` ran once
 * in `MainActivity` with the default auto style, which follows the **system's**
 * dark mode, and no `WindowInsetsControllerCompat` existed anywhere in
 * `app/src/main`. On a phone in dark mode showing Ayu Light the icons stayed
 * white on white and simply vanished — measured over the clock strip at min
 * 252 / max 255, against 40 / 255 on a dark theme
 * (`/tmp/qa/s5-editor-agent-git/173-code-ayu-light.png`).
 *
 * The appearance is driven from the resolved theme's own background now, and
 * the rule is this function, so every bundled theme is checked here rather
 * than one of them being checked by eye.
 */
class SystemBarIconsTest {

    @Test
    fun `every bundled theme asks for icons it can be read against`() {
        for (theme in BundledThemes.all) {
            val background = theme.palette().scheme.background
            // A dark theme wants light icons; a light theme wants dark ones.
            assertEquals(
                "${theme.name} draws its system-bar icons the wrong way round",
                !theme.isDark,
                usesDarkSystemBarIcons(background),
            )
        }
    }

    /**
     * Nothing bundled is anywhere near the threshold, which is what makes a
     * single luminance test the right shape for this rule.
     */
    @Test
    fun `no bundled theme sits on the line`() {
        for (theme in BundledThemes.all) {
            val luminance = theme.palette().scheme.background.luminance()
            val clear = if (theme.isDark) luminance < 0.25f else luminance > 0.6f
            assertTrue("${theme.name} background luminance is $luminance", clear)
        }
    }

    @Test
    fun `white asks for dark icons and black for light ones`() {
        assertTrue(usesDarkSystemBarIcons(Color.White))
        assertFalse(usesDarkSystemBarIcons(Color.Black))
    }
}
