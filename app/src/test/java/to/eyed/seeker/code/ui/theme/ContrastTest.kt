package to.eyed.seeker.code.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every theme the APK ships, parsed from the same JSON the app reads.
 *
 * Host-side rather than instrumented because [ZedTheme] is a JSON parser and
 * [palette] is arithmetic — neither needs a device, and a contrast regression
 * that only a connected phone can catch is a contrast regression that ships.
 * `org.json` is a real test dependency for exactly this (libs.versions.toml,
 * `json = "20250107"`); the android.jar the unit tests link against holds stubs
 * that throw.
 */
internal object BundledThemes {

    /** The module root, whatever directory Gradle chose to run us from. */
    private fun appDir(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "src/main/assets/themes").isDirectory) {
            dir = dir.parentFile ?: error("cannot find the app module from ${File("").absolutePath}")
        }
        return dir
    }

    /** All eleven, in file order: Ayu (3), Gruvbox (6), One (2). */
    val all: List<ZedTheme> by lazy {
        File(appDir(), "src/main/assets/themes")
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                val json = file.readText()
                ZedTheme.index(json).map { meta ->
                    ZedTheme.parse(json, meta.name)
                        ?: error("${meta.name} is indexed but does not parse")
                }
            }
    }

    fun named(name: String): ZedTheme =
        all.firstOrNull { it.name == name } ?: error("no bundled theme called $name")
}

/**
 * The solver, and the measured damage it exists to undo.
 *
 * The numbers pinned below were measured over the eleven bundled themes before
 * any of this existed, and they are the whole argument for it: `text.muted` at
 * **2.79:1** is 455 illegible call sites on Ayu Light, and `text.accent` at
 * **2.84:1** is the label on every filled button on it. If a theme file is
 * revendored and one of those raw numbers moves, this test says so rather than
 * letting the fix quietly stop being needed — or quietly stop being enough.
 */
class ContrastTest {

    private val ayuLight get() = BundledThemes.named("Ayu Light")
    private val oneLight get() = BundledThemes.named("One Light")

    // ---------------------------------------------------------------- solver

    @Test
    fun `contrast ratio is wcag`() {
        assertEquals(21f, contrastRatio(Color.White, Color.Black), 0.01f)
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.White, Color.White), 0.001f)
        // The pair every mid-tone fill is judged against: luminance 0.179 is
        // where black and white are equally readable, at 4.58:1 each.
        val pivot = Color(0xFF757575)
        assertTrue(contrastRatio(Color.Black, pivot) > 4.5f)
        assertTrue(contrastRatio(Color.White, pivot) > 4.5f)
    }

    @Test
    fun `a colour that already clears is returned untouched`() {
        val ink = Color(0xFF202020)
        assertEquals(ink, readable(ink, on = Color.White))
        assertEquals(ink, readable(ink, on = Color.White, MARK_RATIO))
    }

    @Test
    fun `solving keeps the hue and moves the smallest distance it can`() {
        // spettro-android's own example: "coding" green on a light canvas is
        // 1.8:1 — not quiet, unreadable.
        val green = Color(0xFF34D399)
        val canvas = Color(0xFFFAFAFA)
        assertTrue(contrastRatio(green, canvas) < 2f)
        val solved = readable(green, on = canvas)
        assertTrue(
            "solved to ${contrastRatio(solved, canvas)}",
            contrastRatio(solved, canvas) >= TEXT_RATIO,
        )
        // The smallest distance: 12 halvings land within a hair of the bar
        // rather than overshooting to black.
        assertTrue(contrastRatio(solved, canvas) < TEXT_RATIO + 0.1f)
        // Still the green one: green stays the dominant channel.
        assertTrue(solved.green > solved.red && solved.green > solved.blue)
        // A mark only has to be told apart, so it moves less than an ink does.
        val mark = readable(green, on = canvas, MARK_RATIO)
        assertTrue(contrastRatio(mark, canvas) >= MARK_RATIO)
        assertTrue(mark.luminance() > solved.luminance())
    }

    @Test
    fun `a mid-luminance ground is solved from the other end`() {
        // The Gruvbox Light case: primaryContainer lands at luminance 0.42, so
        // `lum > 0.5` implies white, and nothing white-ward reaches 4.5:1 on it.
        // Before the second-extreme pass this shipped onPrimaryContainer at
        // 2.03:1 on three of the eleven themes.
        val ground = Color(0xFF9A9A9A)
        assertTrue(ground.luminance() > 0.179f && ground.luminance() < 0.5f)
        assertTrue(contrastRatio(Color.White, ground) < TEXT_RATIO)
        val solved = readable(Color(0xFF0B6678), on = ground)
        assertTrue(
            "solved to ${contrastRatio(solved, ground)}",
            contrastRatio(solved, ground) >= TEXT_RATIO,
        )
    }

    @Test
    fun `pickInk takes the first ink that clears, then the second, then an extreme`() {
        // Ayu's accent as a filled button: at luminance 0.31 it is a *light*
        // fill, so white reaches only 2.9:1 on it and black is the answer —
        // which is the whole reason a filled label cannot be a constant.
        val fill = Color(0xFF3B9EE5)
        assertEquals(Color.Black, pickInk(on = fill, prefer = Color.Black, alt = Color.White))
        assertEquals(Color.Black, pickInk(on = fill, prefer = Color.White, alt = Color.Black))
        val deep = Color(0xFF1B3A6B)
        assertEquals(Color.White, pickInk(on = deep, prefer = Color.White, alt = Color.Black))
        // Neither candidate clears: the answer is a real black or white rather
        // than a compromise, because a label carries no colour meaning.
        val ink = pickInk(on = fill, prefer = Color(0xFF9AA0A6), alt = Color(0xFF7F8C99))
        assertEquals(Color.Black, ink)
        assertTrue(contrastRatio(ink, fill) >= TEXT_RATIO)
    }

    @Test
    fun `the last resort always clears, on the worst fill there is`() {
        // Luminance 0.179 is the worst possible fill; both extremes reach
        // 4.58:1 there, so pickInk can never fail.
        for (step in 0..255) {
            val fill = Color(red = step / 255f, green = step / 255f, blue = step / 255f)
            val ink = pickInk(on = fill, prefer = fill, alt = fill)
            assertTrue(
                "grey $step gave ${contrastRatio(ink, fill)}",
                contrastRatio(ink, fill) >= TEXT_RATIO,
            )
        }
    }

    // -------------------------------------------------------------- measured

    @Test
    fun `the raw zed inks this solver exists for are as bad as reported`() {
        val ayu = ayuLight
        val panel = ayu.color("panel.background")
        assertEquals(2.79f, contrastRatio(ayu.color("text.muted"), panel), 0.02f)
        assertEquals(2.84f, contrastRatio(ayu.color("text.accent"), ayu.color("editor.background")), 0.02f)
        assertEquals(2.11f, contrastRatio(ayu.color("created"), panel), 0.02f)
        assertEquals(1.64f, contrastRatio(ayu.color("warning"), panel), 0.02f)
        val one = oneLight
        val onePanel = one.color("panel.background")
        assertEquals(3.84f, contrastRatio(one.color("text.accent"), one.color("editor.background")), 0.02f)
        assertEquals(2.64f, contrastRatio(one.color("created"), onePanel), 0.02f)
    }

    @Test
    fun `every zed ink the material half draws is solvable on every bundled theme`() {
        val inks = listOf("text", "text.muted", "text.accent", "created", "deleted", "warning")
        for (theme in BundledThemes.all) {
            val panel = theme.color("panel.background")
            for (key in inks) {
                val solved = readable(theme.color(key), on = panel)
                assertTrue(
                    "${theme.name} $key solved to ${contrastRatio(solved, panel)}",
                    contrastRatio(solved, panel) >= TEXT_RATIO,
                )
                val mark = readable(theme.color(key), on = panel, MARK_RATIO)
                assertTrue(
                    "${theme.name} $key as a mark is ${contrastRatio(mark, panel)}",
                    contrastRatio(mark, panel) >= MARK_RATIO,
                )
            }
        }
    }

    @Test
    fun `all eleven bundled themes parse`() {
        val names = BundledThemes.all.map { it.name }
        assertEquals(11, names.size)
        assertEquals(names.toSet().size, names.size)
        assertTrue(names.containsAll(listOf("One Dark", "One Light", "Ayu Light", "Gruvbox Dark Hard")))
        assertEquals(6, BundledThemes.all.count { it.isDark })
        assertNotEquals(
            BundledThemes.named("One Dark").color("text"),
            BundledThemes.named("One Light").color("text"),
        )
    }
}
