package to.eyed.thragg.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Assertions about *the shipped launcher icon*, not about a fixture.
 *
 * The launcher icon is the one drawable nothing in a build ever looks at. It
 * is not referenced from Kotlin, so no compile error can involve it; it is
 * inflated by the launcher process, so no instrumentation test in this app
 * sees it; and its ten `.webp` fallbacks are separate files that can drift
 * from the vectors without a single warning. The previous icon — the Android
 * Studio template, Google's robot on Google's brand green — survived to a
 * public release precisely because nothing here was watching.
 *
 * These are the invariants that make the mark work rather than the ones that
 * make it compile. Each is a property a redraw could plausibly break:
 *
 *  * ink inside the 66-unit safe circle, because a launcher mask is a shape
 *    this app does not choose and OEMs ship several;
 *  * ink big enough and every limb heavy enough to survive a 48dp render;
 *  * the monochrome layer being its own drawing — the same mark, inset for a
 *    bare system plate — and not `<monochrome>` pointed at the foreground,
 *    which is the usual way themed icons end up broken.
 *
 * The files are read off the source tree rather than the classpath: Gradle
 * runs a unit test with the module directory as its working directory, and
 * these are resources, which a JVM unit test has no `R` for. The same
 * approach as `ToolchainManifestTest`.
 */
class LauncherIconTest {

    /** The adaptive canvas, the region a launcher shows, and the safe circle. */
    private val canvas = 108.0
    private val centre = canvas / 2
    private val viewport = 72.0
    private val safeRadius = 66.0 / 2

    // ---------------------------------------------------------------- layout

    @Test
    fun `the adaptive icon declares three separate layers`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val root = parse(res("src/main/res/mipmap-anydpi/$name.xml")).documentElement
            val layers = listOf("background", "foreground", "monochrome").associateWith { tag ->
                val nodes = root.getElementsByTagName(tag)
                assertEquals("$name.xml declares one <$tag>", 1, nodes.length)
                (nodes.item(0) as Element).getAttributeNS(ANDROID, "drawable")
            }
            assertEquals("@drawable/ic_launcher_background", layers.getValue("background"))
            assertEquals("@drawable/ic_launcher_foreground", layers.getValue("foreground"))
            // The whole point: a themed icon needs its own drawing. Reusing the
            // foreground is what the template did.
            assertNotEquals(
                "$name.xml reuses the foreground as its monochrome layer",
                layers.getValue("foreground"),
                layers.getValue("monochrome"),
            )
            assertEquals("@drawable/ic_launcher_monochrome", layers.getValue("monochrome"))
        }
    }

    @Test
    fun `the background covers the whole canvas, not just the viewport`() {
        // A launcher parallaxes the background under the foreground. Anything
        // short of full bleed shows the canvas edge when it does.
        val box = union(paths("ic_launcher_background").map { it.box })
        assertEquals(0.0, box.minX, TOLERANCE)
        assertEquals(0.0, box.minY, TOLERANCE)
        assertEquals(canvas, box.maxX, TOLERANCE)
        assertEquals(canvas, box.maxY, TOLERANCE)
    }

    // ------------------------------------------------------------- safe zone

    @Test
    fun `both drawn layers keep their ink inside the safe circle`() {
        for (layer in DRAWN) {
            val reach = paths(layer).maxOf { path -> path.reachFrom(centre, centre) }
            assertTrue(
                "$layer reaches $reach from the centre, past the $safeRadius safe radius",
                reach <= safeRadius,
            )
            // And is not so far inside it that the mark has gone timid: below
            // about 25 the glyph is small enough that the tile reads as empty.
            assertTrue("$layer only reaches $reach; the mark has shrunk", reach > 25.0)
        }
    }

    @Test
    fun `both drawn layers are centred on the canvas`() {
        for (layer in DRAWN) {
            val box = union(paths(layer).map { it.box })
            assertEquals("$layer is off-centre horizontally", centre, box.midX, 0.05)
            assertEquals("$layer is off-centre vertically", centre, box.midY, 0.05)
        }
    }

    // ---------------------------------------------------- legible at 48dp

    @Test
    fun `the mark fills enough of the viewport to read at 48dp`() {
        for (layer in DRAWN) {
            val box = union(paths(layer).map { it.box })
            // 55% of the visible 72 units. Less than this and the glyph floats
            // in a tile at launcher size; the safe circle caps it at ~92%.
            val floor = viewport * 0.55
            assertTrue("$layer is only ${box.width} wide", box.width >= floor)
            assertTrue("$layer is only ${box.height} tall", box.height >= floor)
        }
    }

    @Test
    fun `every limb is heavy enough to survive a 48dp render`() {
        // One canvas unit is 48/72 dp once the launcher has cropped to the
        // viewport, so a 6-unit limb is 4dp — about the thinnest that still
        // reads as a deliberate shape rather than a hairline at mdpi.
        //
        // The mark is one filled outline, so there is no stroke width to read;
        // the limbs are measured off the outline's own points. The bar is the
        // ink above the hooks, a hook is the ink beside the stem's top, and the
        // stem is everything below the hooks' round ends.
        for (layer in DRAWN) {
            val points = paths(layer).single().points
            val top = points.minOf { it.second }
            val bar = points.filter { it.second < 48 }.maxOf { it.second } - top
            val hook = points.filter { it.first > centre && it.second in 48.0..60.0 }
                .let { hook -> hook.maxOf { it.first } - hook.minOf { it.first } }
            val stem = points.filter { it.second > 60 }
                .let { stem -> stem.maxOf { it.first } - stem.minOf { it.first } }
            for ((name, width) in listOf("bar" to bar, "hook" to hook, "stem" to stem)) {
                assertTrue("$layer's $name is only $width units", width >= 6.0)
            }
            // And the moustache leads: the bar outweighs the stem it sits on.
            assertTrue("$layer's bar ($bar) is not heavier than its stem ($stem)", bar > stem)
        }
    }

    // -------------------------------------------------------- the mono layer

    @Test
    fun `the monochrome layer is a single opaque colour`() {
        // The system re-tints this layer, so only the alpha survives — but a
        // second colour in the source means someone drew it expecting the
        // tones to read, and in themed mode they will not.
        val colours = paths("ic_launcher_monochrome").flatMap { it.colours }.toSet()
        assertEquals("the monochrome layer uses $colours", 1, colours.size)
    }

    @Test
    fun `the monochrome layer is the colour mark inset for a bare plate`() {
        // A themed launcher drops the tile and sits the glyph on a system
        // plate, where a mark tuned to fill a coloured tile looks oversized.
        // So the monochrome drawing is the same outline scaled about the
        // centre — and only that, so the two cannot drift into two marks.
        val colour = paths("ic_launcher_foreground").single()
        val mono = paths("ic_launcher_monochrome").single()
        assertEquals("the two layers are not the same outline", colour.points.size, mono.points.size)
        val factors = colour.points.zip(mono.points).flatMap { (c, m) ->
            listOfNotNull(
                ratio(m.first - centre, c.first - centre),
                ratio(m.second - centre, c.second - centre),
            )
        }
        val scale = factors.average()
        for (factor in factors) {
            assertEquals("the monochrome layer is not a uniform inset", scale, factor, 0.01)
        }
        assertTrue("the monochrome layer ($scale) is not inset", scale < 1.0)
        assertTrue("the monochrome layer ($scale) is inset too far to read", scale >= 0.9)
    }

    /** `a / b`, or null where the point sits on the centre line and says nothing. */
    private fun ratio(a: Double, b: Double): Double? = if (abs(b) < 0.5) null else a / b

    // ------------------------------------------------------------- hygiene

    @Test
    fun `no trace of the Android Studio template survives`() {
        val sources = listOf(
            "src/main/res/drawable/ic_launcher_background.xml",
            "src/main/res/drawable/ic_launcher_foreground.xml",
            "src/main/res/drawable/ic_launcher_monochrome.xml",
            "src/main/res/mipmap-anydpi/ic_launcher.xml",
            "src/main/res/mipmap-anydpi/ic_launcher_round.xml",
        )
        for (path in sources) {
            val text = res(path).readText()
            // Google's Android brand green. The comments name it, because
            // they explain why it is gone; what must never happen again is a
            // resource *attribute* holding it.
            for (line in text.lineSequence().filter { it.contains("android:") }) {
                assertTrue(
                    "$path still paints Android brand green: ${line.trim()}",
                    !line.contains("3DDC84", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `every density bucket has both bitmap fallbacks`() {
        // Regenerated from the vectors by tools/render-launcher-icon.py, which
        // has a --check mode; all this can prove from the JVM is that a
        // density did not lose its file.
        for (density in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            for (name in listOf("ic_launcher", "ic_launcher_round")) {
                val file = res("src/main/res/mipmap-$density/$name.webp")
                assertTrue("${file.path} is empty", file.length() > 0)
                assertEquals(
                    "${file.path} is not a WebP",
                    "RIFF",
                    String(file.readBytes().copyOfRange(0, 4), Charsets.US_ASCII),
                )
            }
        }
    }

    // ------------------------------------------------------------ machinery

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val TOLERANCE = 1e-6
        val DRAWN = listOf("ic_launcher_foreground", "ic_launcher_monochrome")

        /**
         * Only the commands these drawables use, and only absolute: a relative
         * `l` read as an `L` would place ink somewhere it is not, and quietly.
         */
        const val SUPPORTED = "MLAZz"
        val NUMBER = Regex("""-?\d*\.?\d+""")
    }

    private data class Box(
        val minX: Double,
        val minY: Double,
        val maxX: Double,
        val maxY: Double,
    ) {
        val midX get() = (minX + maxX) / 2
        val midY get() = (minY + maxY) / 2
        val width get() = maxX - minX
        val height get() = maxY - minY
    }

    /** One `<path>`, reduced to the on-path points and the paint that widens them. */
    private inner class Ink(node: Element) {
        private val data: String = node.getAttributeNS(ANDROID, "pathData")
        private val fill = opaque(node.getAttributeNS(ANDROID, "fillColor"))
        private val stroke = opaque(node.getAttributeNS(ANDROID, "strokeColor"))

        /**
         * A stroke widens the ink by half its width in every direction, which
         * is exact at a round cap and an upper bound at a join.
         */
        val halfStroke: Double =
            if (stroke == null) 0.0
            else node.getAttributeNS(ANDROID, "strokeWidth").toDouble() / 2

        val colours: List<String> = listOfNotNull(fill, stroke)

        val points: List<Pair<Double, Double>> = parsePoints()

        val box: Box = Box(
            points.minOf { it.first } - halfStroke,
            points.minOf { it.second } - halfStroke,
            points.maxOf { it.first } + halfStroke,
            points.maxOf { it.second } + halfStroke,
        )

        fun reachFrom(x: Double, y: Double): Double =
            points.maxOf { hypot(it.first - x, it.second - y) } + halfStroke

        /**
         * `M`, `L`, `A` and `Z`, absolute, which is all these files contain.
         *
         * Bounding a shape by its on-path points is exact here and not in
         * general: the only curves are the cursor cell's corner arcs, and an
         * axis-aligned quarter turn stays inside the box its two endpoints
         * make. That condition is asserted rather than assumed, so a redraw
         * that introduces a curve which bulges past its endpoints fails here
         * loudly instead of quietly under-reporting its own size.
         */
        private fun parsePoints(): List<Pair<Double, Double>> {
            data.firstOrNull { it.isLetter() && it !in SUPPORTED }?.let {
                fail("pathData uses '$it', which this test cannot bound: $data")
            }
            val out = mutableListOf<Pair<Double, Double>>()
            var x = 0.0
            var y = 0.0
            for (match in Regex("([$SUPPORTED])([^$SUPPORTED]*)").findAll(data)) {
                val op = match.groupValues[1][0]
                val n = NUMBER.findAll(match.groupValues[2]).map { it.value.toDouble() }.toList()
                when (op) {
                    'M', 'L' -> {
                        var i = 0
                        while (i + 1 < n.size) {
                            x = n[i]; y = n[i + 1]; out += x to y; i += 2
                        }
                    }
                    'A' -> {
                        var i = 0
                        while (i + 6 < n.size) {
                            val rx = n[i]
                            val ry = n[i + 1]
                            val nx = n[i + 5]
                            val ny = n[i + 6]
                            assertEquals("arc is not an axis-aligned quarter turn: $data",
                                rx, abs(nx - x), 1e-6)
                            assertEquals("arc is not an axis-aligned quarter turn: $data",
                                ry, abs(ny - y), 1e-6)
                            x = nx; y = ny; out += x to y; i += 7
                        }
                    }
                }
            }
            assertTrue("pathData produced no points: $data", out.isNotEmpty())
            return out
        }

        /** `#00000000` is the template's idiom for "no paint on a path I stroke". */
        private fun opaque(value: String?): String? =
            value?.takeIf { it.length == 9 && it.substring(1, 3) != "00" }
                ?: value?.takeIf { it.length == 7 }
    }

    private fun paths(drawable: String): List<Ink> {
        val nodes = parse(res("src/main/res/drawable/$drawable.xml"))
            .getElementsByTagName("path")
        return (0 until nodes.length).map { Ink(nodes.item(it) as Element) }
    }

    private fun union(boxes: List<Box>): Box = boxes.reduce { a, b ->
        Box(min(a.minX, b.minX), min(a.minY, b.minY), max(a.maxX, b.maxX), max(a.maxY, b.maxY))
    }

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun res(relative: String): File =
        listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("no $relative (cwd ${File(".").absolutePath})")
}
