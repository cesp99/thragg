package to.eyed.thragg.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a font file's name as a family, a weight and a slant — the whole
 * basis of `buffer_font_family` on a platform that will not tell you what is
 * installed (see [FontNames]).
 */
class FontNamesTest {

    @Test
    fun a_style_suffix_becomes_a_weight_and_a_slant() {
        assertEquals(
            FontNames.Face("JetBrains Mono", 600, italic = true),
            FontNames.faceOf("JetBrainsMono-SemiBoldItalic.ttf"),
        )
        assertEquals(
            FontNames.Face("Noto Sans Mono", 400, italic = false),
            FontNames.faceOf("NotoSansMono-Regular.ttf"),
        )
        assertEquals(
            FontNames.Face("Fira Code", 300, italic = false),
            FontNames.faceOf("FiraCode-Light.otf"),
        )
        // `ExtraBold` is not `Bold` with `Extra` in front of it.
        assertEquals(800, FontNames.faceOf("Inter-ExtraBold.ttf").weight)
    }

    @Test
    fun a_name_with_no_style_is_a_family_at_400_upright() {
        assertEquals(
            FontNames.Face("Lilex", 400, italic = false),
            FontNames.faceOf("Lilex.ttf"),
        )
    }

    @Test
    fun a_suffix_that_is_not_a_style_stays_part_of_the_family() {
        // Otherwise a condensed face and a normal one collapse into one name
        // and the picker offers a family that draws two different fonts.
        assertEquals(
            "Noto Sans Mono-Condensed",
            FontNames.faceOf("NotoSansMono-Condensed.ttf").family,
        )
    }

    @Test
    fun camel_case_becomes_the_name_a_person_would_type() {
        assertEquals("JetBrains Mono", FontNames.displayName("JetBrainsMono"))
        assertEquals("Noto Sans Mono", FontNames.displayName("NotoSansMono"))
        // Runs of capitals are acronyms, not words.
        assertEquals("Noto Sans CJK", FontNames.displayName("NotoSansCJK"))
        // A name that already reads as a name is left alone.
        assertEquals("IBM Plex Sans", FontNames.displayName("IBM Plex Sans"))
    }

    @Test
    fun only_font_files_are_offered() {
        assertTrue(FontNames.isFontFile("Lilex.ttf"))
        assertTrue(FontNames.isFontFile("Lilex.OTF"))
        assertFalse(FontNames.isFontFile("Lilex.zip"))
        assertFalse(FontNames.isFontFile("LICENSE"))
    }
}
