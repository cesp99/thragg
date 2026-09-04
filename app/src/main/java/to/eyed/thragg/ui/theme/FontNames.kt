package to.eyed.thragg.ui.theme

/**
 * A font file's name read as a family, a weight and a slant.
 *
 * Android has no API that answers "what families are installed": the
 * `Typeface` factory takes a name and silently hands back the default for one
 * it does not know, and `SystemFonts.getAvailableFonts()` (API 29) answers
 * with *files* and their style metrics but no family name at all. So the
 * family has to come from the file name, which is the convention every font
 * ships under — `JetBrainsMono-SemiBoldItalic.ttf` is JetBrains Mono at 600,
 * slanted — and which is also what a user typing a name into settings.json
 * will have read off the file they dropped in.
 *
 * Kept free of Android types on purpose: this is the part with rules in it,
 * and rules are worth a host test.
 */
object FontNames {

    /** What a file name says: the family, the CSS weight and the slant. */
    data class Face(val family: String, val weight: Int, val italic: Boolean)

    /** The extensions Android's font loader can open. */
    val EXTENSIONS = setOf("ttf", "otf", "ttc", "otc")

    /**
     * The CSS weights, by the word a file name spells them with, longest
     * first so `ExtraBold` is not read as `Bold` with `Extra` left over.
     */
    private val WEIGHTS: List<Pair<String, Int>> = listOf(
        "extralight" to 200,
        "ultralight" to 200,
        "semibold" to 600,
        "demibold" to 600,
        "extrabold" to 800,
        "ultrabold" to 800,
        "thin" to 100,
        "light" to 300,
        "normal" to 400,
        "regular" to 400,
        "book" to 400,
        "medium" to 500,
        "bold" to 700,
        "black" to 900,
        "heavy" to 900,
    ).sortedByDescending { it.first.length }

    /** Whether [fileName] is a font this app can load. */
    fun isFontFile(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /**
     * The face [fileName] describes.
     *
     * The style words live after the last `-` or `_`, which is the convention
     * Google Fonts, Nerd Fonts and the system's own `/system/fonts` all
     * follow. A name with no separator is a family on its own at 400 upright:
     * `Lilex.ttf` is Lilex, not "Lil" in some weight called "ex".
     */
    fun faceOf(fileName: String): Face {
        val stem = fileName.substringBeforeLast('.')
        val separator = stem.indexOfLast { it == '-' || it == '_' }
        val (familyPart, stylePart) = if (separator > 0) {
            stem.substring(0, separator) to stem.substring(separator + 1)
        } else {
            stem to ""
        }
        var rest = stylePart.lowercase()
        val italic = rest.contains("italic") || rest.contains("oblique")
        rest = rest.replace("italic", "").replace("oblique", "")
        var weight = 400
        for ((word, value) in WEIGHTS) {
            if (rest.contains(word)) {
                weight = value
                rest = rest.replace(word, "")
                break
            }
        }
        // Anything still left after the style words were taken out was never
        // a style: `NotoSansMono-Condensed` is its own family, and reading it
        // as Noto Sans Mono would collapse two faces into one name. Keep the
        // whole stem in that case.
        val family = if (rest.isNotBlank()) stem else familyPart
        return Face(displayName(family), weight, italic)
    }

    /**
     * `JetBrainsMono` → `JetBrains Mono`: the name a person would type.
     *
     * Camel case is split between a lower-case letter and an upper-case one,
     * and only once the word so far is at least [MIN_WORD] long. That second
     * rule is what separates `Noto Sans Mono` from `Jet Brains Mono`: font
     * families glue real words together, and a real word in one is not three
     * letters. It is a heuristic and it is meant to be — Android hands over a
     * file name and nothing else (see the note on this object), so the choice
     * is between a heuristic and `JetBrainsMono`.
     *
     * A run of capitals is an acronym rather than words, so `NotoSansCJK`
     * keeps its `CJK`, and a family that already has spaces is left exactly
     * as written.
     */
    fun displayName(raw: String): String {
        val spaced = StringBuilder(raw.length + 4)
        var wordLength = 0
        for ((index, character) in raw.withIndex()) {
            val previous = raw.getOrNull(index - 1)
            val boundary = index > 0 &&
                character.isUpperCase() &&
                previous != null &&
                (previous.isLowerCase() || previous.isDigit())
            if (boundary && wordLength >= MIN_WORD) {
                spaced.append(' ')
                wordLength = 0
            }
            spaced.append(character)
            wordLength = if (character.isLetterOrDigit()) wordLength + 1 else 0
        }
        return spaced.toString().replace('_', ' ').trim()
    }

    /** The shortest run of letters this will treat as a word of its own. */
    private const val MIN_WORD = 4
}
