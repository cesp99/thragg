package to.eyed.seeker.code.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a hover card says, and where a definition points.
 *
 * The markdown half matters more than it looks: a server's `contents` is the
 * only place in this editor where text from a *program* is rendered as
 * markup, and a signature mangled into prose is a card that lies about the
 * code.
 */
class HoverTest {

    // ---- markdown, read out loud ----

    @Test
    fun aFencedSignatureSurvivesVerbatim() {
        assertEquals(
            "fn main()\n\nThe entry point.",
            markdownToText("```rust\nfn main()\n```\n\nThe entry point."),
        )
    }

    @Test
    fun emphasisAndCodeSpansLoseTheirPunctuation() {
        assertEquals("bold and code here", markdownToText("**bold** and `code` here"))
    }

    @Test
    fun aLinkBecomesItsLabel() {
        assertEquals("see the docs", markdownToText("see [the docs](https://example.com)"))
    }

    @Test
    fun aHeadingLosesItsHashes() {
        assertEquals("Vec::push\n\nAppends an element.", markdownToText("# Vec::push\n\nAppends an element."))
    }

    @Test
    fun aListKeepsItsBullets() {
        val text = markdownToText("- first\n- second")
        assertTrue(text, text.startsWith("• first"))
        assertTrue(text, text.endsWith("• second"))
    }

    @Test
    fun anEmptyOrBlankHoverIsNothingAtAll() {
        // `""` is what the bridge sends when the server had nothing to say,
        // and the card must not appear at all for it.
        assertEquals("", markdownToText(""))
        assertEquals("", markdownToText("   \n\n  "))
    }

    @Test
    fun anIndentedBlockOfCodeIsNotReflowed() {
        assertEquals(
            "fn a()\nfn b()",
            markdownToText("```\nfn a()\nfn b()\n```"),
        )
    }

    // ---- the hover payload ----

    @Test
    fun aHoverCarriesItsRangeWhenTheServerGaveOne() {
        val info = HoverInfo.parse(
            org.json.JSONObject(
                """{"contents":"docs","range":{"row":0,"col_utf16":3,"end_row":0,"end_col_utf16":7}}"""
            )
        )
        assertEquals("docs", info.contents)
        assertEquals(LspRange(0, 3, 0, 7), info.range)
    }

    @Test
    fun aHoverWithNoRangeIsStillAHover() {
        val info = HoverInfo.parse(org.json.JSONObject("""{"contents":"docs","range":null}"""))
        assertNull(info.range)
        assertTrue(HoverInfo.parse(org.json.JSONObject("""{"contents":""}""")).isEmpty)
        assertTrue(HoverInfo.parse(null).isEmpty)
    }

    // ---- definitions ----

    @Test
    fun aDefinitionTargetIsAnAbsolutePathAndAPosition() {
        val targets = parseDefinitionTargets(
            org.json.JSONObject(
                """
                {"targets":[{"path":"/abs/host/path/src/lib.rs","row":12,"col_utf16":3,
                             "end_row":12,"end_col_utf16":9}]}
                """.trimIndent()
            )
        )
        assertEquals(
            listOf(DefinitionTarget("/abs/host/path/src/lib.rs", 12, 3, 12, 9)),
            targets,
        )
    }

    @Test
    fun noTargetsIsALegitimateAnswer() {
        // A keyword has no definition, and that is not an error.
        assertTrue(parseDefinitionTargets(org.json.JSONObject("""{"targets":[]}""")).isEmpty())
        assertTrue(parseDefinitionTargets(null).isEmpty())
    }

    @Test
    fun aTargetWithNoPathIsDropped() {
        val targets = parseDefinitionTargets(
            org.json.JSONObject("""{"targets":[{"path":null,"row":1},{"path":"/a.rs","row":2}]}""")
        )
        assertEquals(listOf("/a.rs"), targets.map { it.path })
        assertEquals(2, targets.single().row)
    }
}
