package to.eyed.seeker.code.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading of an SVG, on the host.
 *
 * The parser holds no Android types precisely so this can exist: what breaks an
 * SVG viewer is never the curve maths — `PathParser` does that — it is the
 * document around it, and every case below was wrong in a draft of this file.
 */
class SvgDocumentTest {

    private fun svg(body: String, attributes: String = """viewBox="0 0 24 24""""): SvgDocument {
        val text = """<svg xmlns="http://www.w3.org/2000/svg" $attributes>$body</svg>"""
        return SvgDocument.parse(text) ?: throw AssertionError("did not parse: $text")
    }

    // ---- the document -----------------------------------------------------

    /** `viewBox` is the drawing's own coordinate system; `width` is a request. */
    @Test
    fun theViewBoxWinsOverTheDeclaredSize() {
        val document = svg("", attributes = """width="512" height="512" viewBox="0 0 16 16"""")
        assertEquals(16f, document.viewportWidth, 0f)
        assertEquals(16f, document.viewportHeight, 0f)
    }

    /** With no `viewBox`, the declared size is all there is — units and all. */
    @Test
    fun aSizeWithUnitsIsStillASize() {
        val document = svg("", attributes = """width="48px" height="32.5pt"""")
        assertEquals(48f, document.viewportWidth, 0f)
        assertEquals(32.5f, document.viewportHeight, 0f)
    }

    /**
     * A percentage sizes the SVG against its container, which a preview does
     * not have; with nothing else to go on there is no drawing to fit.
     */
    @Test
    fun aDocumentWithNoUsableSizeIsRefused() {
        assertNull(SvgDocument.parse("""<svg width="100%" height="100%"></svg>"""))
    }

    @Test
    fun somethingThatIsNotAnSvgIsRefused() {
        assertNull(SvgDocument.parse("<html><body>hello</body></html>"))
        assertNull(SvgDocument.parse("not xml at all"))
        assertNull(SvgDocument.parse(""))
    }

    /**
     * The file may have been cloned an hour ago from anywhere. A DOCTYPE is
     * how an XML parser is talked into reading `/etc/passwd` or into expanding
     * a kilobyte into a gigabyte, and an SVG has no legitimate use for one.
     */
    @Test
    fun aDoctypeIsRefusedRatherThanExpanded() {
        val billionLaughs = """
            <?xml version="1.0"?>
            <!DOCTYPE svg [
              <!ENTITY a "aaaaaaaaaa">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
              <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
            ]>
            <svg viewBox="0 0 24 24"><path d="M0,0 L1,1" fill="&c;"/></svg>
        """.trimIndent()
        assertNull(SvgDocument.parse(billionLaughs))
    }

    @Test
    fun aFileLargerThanTheCapIsRefusedWithoutParsingIt() {
        val huge = "<svg viewBox=\"0 0 1 1\">" + " ".repeat(SvgDocument.MAX_CHARS) + "</svg>"
        assertNull(SvgDocument.parse(huge))
    }

    // ---- shapes -----------------------------------------------------------

    /** Every primitive becomes the `d` it is equivalent to, so one path draws all. */
    @Test
    fun everyPrimitiveBecomesPathData() {
        val document = svg(
            """
            <rect x="1" y="2" width="4" height="6"/>
            <circle cx="8" cy="8" r="3"/>
            <ellipse cx="8" cy="8" rx="3" ry="2"/>
            <line x1="0" y1="0" x2="10" y2="10"/>
            <polyline points="0,0 5,5 10,0"/>
            <polygon points="0,0 5,5 10,0"/>
            <path d="M1,1 L2,2"/>
            """.trimIndent()
        )
        assertEquals(7, document.shapes.size)
        assertTrue(document.shapes.all { it.pathData.startsWith("M") })
        // A polygon closes and a polyline does not — the whole difference.
        assertTrue(document.shapes[5].pathData.endsWith("Z"))
        assertTrue(!document.shapes[4].pathData.endsWith("Z"))
    }

    /** A zero-sized primitive is not a shape; drawing it would be a stray dot. */
    @Test
    fun aShapeWithNoSizeIsNotDrawn() {
        assertEquals(0, svg("""<rect x="1" y="1" width="0" height="8"/>""").shapes.size)
        assertEquals(0, svg("""<circle cx="8" cy="8" r="0"/>""").shapes.size)
    }

    /** `rx` alone rounds both axes, which is what SVG says and icons rely on. */
    @Test
    fun aRoundedRectangleIsFourArcs() {
        val shape = svg("""<rect width="10" height="10" rx="2"/>""").shapes.single()
        assertEquals(4, shape.pathData.count { it == 'a' })
    }

    // ---- inherited paint --------------------------------------------------

    /**
     * The shape of nearly every icon set, Zed's included: paint on the root or
     * a group, bare paths inside. Reading each shape's own attributes and
     * stopping there draws all of them as black silhouettes.
     */
    @Test
    fun paintIsInheritedDownGroups() {
        val document = svg(
            """<g fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
                 <path d="M1,1 L2,2"/>
               </g>"""
        )
        val shape = document.shapes.single()
        assertNull(shape.fill)
        assertEquals(SvgPaint.Current, shape.stroke)
        assertEquals(1.5f, shape.strokeWidth, 0f)
        assertTrue(shape.strokeCapRound)
    }

    /** A shape's own attribute beats what it inherits. */
    @Test
    fun aShapeOverridesWhatItInherits() {
        val shape = svg("""<g fill="#ff0000"><path d="M0,0" fill="#00ff00"/></g>""").shapes.single()
        assertEquals(SvgPaint.Solid(0xFF00FF00L), shape.fill)
    }

    /** With nothing said at all, SVG fills black — and only a `<line>` does not. */
    @Test
    fun theDefaultFillIsBlackExceptOnALine() {
        assertEquals(SvgPaint.Solid(0xFF000000L), svg("""<path d="M0,0"/>""").shapes.single().fill)
        assertNull(svg("""<line x1="0" y1="0" x2="1" y2="1"/>""").shapes.single().fill)
    }

    /** Nested opacity multiplies; two halves are a quarter, as a compositor does. */
    @Test
    fun groupOpacityMultipliesThroughTheTree() {
        val shape = svg("""<g opacity="0.5"><g opacity="0.5"><path d="M0,0"/></g></g>""")
            .shapes.single()
        assertEquals(0.25f, shape.fillAlpha, 1e-6f)
    }

    @Test
    fun theEvenOddRuleSurvives() {
        assertTrue(svg("""<path d="M0,0" fill-rule="evenodd"/>""").shapes.single().evenOdd)
        assertTrue(!svg("""<path d="M0,0"/>""").shapes.single().evenOdd)
    }

    // ---- colours ----------------------------------------------------------

    @Test
    fun coloursAreReadInEveryFormAnIconUses() {
        assertEquals(0xFFAABBCCL, colour("#abc"))
        assertEquals(0xFFAABBCCL, colour("#AABBCC"))
        // SVG writes #RRGGBBAA; every graphics API here wants ARGB.
        assertEquals(0x80112233L, colour("#11223380"))
        assertEquals(0xFF010203L, colour("rgb(1, 2, 3)"))
        assertEquals(0xFFFF0000L, colour("rgb(100%, 0%, 0%)"))
        assertEquals(0xFF808080L, colour("grey"))
        assertNull(colour("#gg0011"))
        assertNull(colour("chartreuse"))
    }

    // ---- transforms -------------------------------------------------------

    /**
     * The reason transforms are matrices here and not three separate fields:
     * these two orders put the same point in different places, and a struct of
     * translate/scale/rotate quietly draws them the same.
     */
    @Test
    fun transformsDoNotCommute() {
        val first = SvgTransform.parse("rotate(90) translate(10,0)").map(0f, 0f)
        val second = SvgTransform.parse("translate(10,0) rotate(90)").map(0f, 0f)
        assertEquals(0f, first.first, 1e-4f)
        assertEquals(10f, first.second, 1e-4f)
        assertEquals(10f, second.first, 1e-4f)
        assertEquals(0f, second.second, 1e-4f)
    }

    /** `rotate(a cx cy)` turns about a point rather than about the origin. */
    @Test
    fun aRotationCanBeAboutAPoint() {
        val (x, y) = SvgTransform.parse("rotate(180 8 8)").map(8f, 0f)
        assertEquals(8f, x, 1e-4f)
        assertEquals(16f, y, 1e-4f)
    }

    /** A group's transform reaches its children, composed with their own. */
    @Test
    fun aGroupTransformReachesItsChildren() {
        val shape = svg("""<g transform="translate(4,4)"><path d="M0,0" transform="scale(2)"/></g>""")
            .shapes.single()
        val (x, y) = shape.transform.map(1f, 1f)
        assertEquals(6f, x, 1e-4f)
        assertEquals(6f, y, 1e-4f)
    }

    @Test
    fun aMatrixTransformIsTakenAsGiven() {
        val (x, y) = SvgTransform.parse("matrix(2,0,0,2,1,1)").map(3f, 4f)
        assertEquals(7f, x, 1e-4f)
        assertEquals(9f, y, 1e-4f)
    }

    // ---- what it will not draw --------------------------------------------

    /**
     * Named, never guessed at. A gradient drawn as a flat colour is a lie the
     * user cannot see; a line saying "not drawn: gradients" is one they can act
     * on, and the source is one tap away.
     */
    @Test
    fun aGradientIsReportedRatherThanApproximated() {
        val document = svg(
            """<defs><linearGradient id="g"><stop offset="0"/></linearGradient></defs>
               <rect width="10" height="10" fill="url(#g)"/>"""
        )
        assertEquals(setOf("gradients"), document.unsupported)
        assertNull(document.shapes.single().fill)
    }

    @Test
    fun textAndEmbeddedImagesAreReported() {
        val document = svg("""<text x="0" y="0">hi</text><image href="a.png"/>""")
        assertEquals(setOf("text", "embedded images"), document.unsupported)
        assertEquals(0, document.shapes.size)
    }

    /**
     * `<defs>` holds shapes that are *definitions*: something has to `use`
     * them for them to appear. Walking into it would draw the lot at 0,0 —
     * which for an icon sheet means every icon stacked in one corner.
     */
    @Test
    fun definitionsAreNotDrawnWhereTheyStand() {
        val document = svg("""<defs><path d="M0,0 L9,9"/></defs><path d="M1,1"/>""")
        assertEquals(1, document.shapes.size)
    }

    // ---- the edges a device found, and a desktop JVM never would ---------

    /**
     * `viewBox="min-x min-y w h"` moves the origin. Google's Material Symbols
     * are all `0 -960 960 960`: ignoring the offset drew every one of them a
     * whole canvas above the pane, which is to say not at all.
     */
    @Test
    fun theViewBoxOriginMovesTheDrawing() {
        val shape = svg(
            """<rect x="-8" y="-8" width="16" height="16"/>""",
            attributes = """viewBox="-8 -8 16 16"""",
        ).shapes.single()
        val (x, y) = shape.transform.map(-8f, -8f)
        assertEquals(0f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    /**
     * `style="fill:red"` says what `fill="red"` says, and tool-generated files
     * use it constantly — six of Zed's own icons do. Reading only attributes
     * drew all of them as black silhouettes and said nothing about why.
     */
    @Test
    fun theStyleAttributeIsRead() {
        val shape = svg(
            """<path d="M0,0" style="fill:#ff0000;stroke:#00ff00;stroke-width:4"/>"""
        ).shapes.single()
        assertEquals(SvgPaint.Solid(0xFFFF0000L), shape.fill)
        assertEquals(SvgPaint.Solid(0xFF00FF00L), shape.stroke)
        assertEquals(4f, shape.strokeWidth, 0f)
    }

    /** A hidden layer is hidden in every other renderer. */
    @Test
    fun hiddenElementsAreNotDrawn() {
        assertEquals(0, svg("""<g display="none"><path d="M0,0"/></g>""").shapes.size)
        assertEquals(0, svg("""<rect width="4" height="4" visibility="hidden"/>""").shapes.size)
    }

    /** With both given, `rx` and `ry` are different radii — not the larger one twice. */
    @Test
    fun aRectangleKeepsBothItsRadii() {
        val shape = svg("""<rect width="20" height="20" rx="2" ry="8"/>""").shapes.single()
        assertTrue(shape.pathData.startsWith("M2.0,"))
    }

    /**
     * The transform is baked into the geometry, so the pen has to be scaled to
     * match or a scaled shape gets a hairline outline.
     */
    @Test
    fun aScaledShapeGetsAScaledPen() {
        val shape = svg("""<g transform="scale(4)"><path d="M0,0" stroke="#000"/></g>""")
            .shapes.single()
        assertEquals(4f, shape.transform.scaleFactor, 1e-4f)
        // A rotation scales nothing.
        assertEquals(1f, SvgTransform.rotate(30f).scaleFactor, 1e-4f)
    }

    /**
     * What cannot be drawn is *named*. A shape sized in percentages resolves
     * against a viewport this has none of, so it comes out empty — and the
     * pane has to be able to say so instead of showing a blank.
     */
    @Test
    fun whatCannotBeSizedIsReportedRatherThanDroppedSilently() {
        val document = svg("""<rect width="100%" height="100%" fill="#f00"/>""")
        assertEquals(setOf("percentage sizes"), document.unsupported)
    }

    @Test
    fun aNestedSvgIsReported() {
        val document = svg("""<svg x="50" y="50" viewBox="0 0 10 10"><path d="M0,0"/></svg>""")
        assertTrue("nested SVG" in document.unsupported)
    }

    /** The fit is the drawing centred in what it is given, ratio kept. */
    @Test
    fun theDrawingIsCentredInThePaneItIsGiven() {
        val document = svg("", attributes = """viewBox="0 0 10 10"""")
        val (scale, x, y) = document.fit(width = 200f, height = 100f)
        assertEquals(10f, scale, 0f)
        assertEquals(50f, x, 0f)
        assertEquals(0f, y, 0f)
    }
}
