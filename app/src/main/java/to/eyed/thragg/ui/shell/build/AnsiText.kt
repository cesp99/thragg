package to.eyed.thragg.ui.shell.build

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/*
 * SGR escapes to Compose spans, and the same escapes to nothing.
 *
 * The build log is the one surface on the Build destination that is
 * legitimately a Zed island — compiler output, in the user's buffer face, in
 * the user's terminal colours (docs/VISUAL.md, "Every other screen" -> Build).
 * Which means the escapes have to be read, because cargo is asked for them by
 * name: every build command in `BuildTasks` passes
 * `--message-format=json-diagnostic-rendered-ansi`, so `BuildIssue.rendered`
 * arrives with rustc's own colouring in it — the `error[E0433]` in red, the
 * arrow to the location in blue, the carets under the span. Before this the
 * log threw all of that away and drew everything in one ink, and the raw
 * escapes leaked into the clipboard and into the agent prompt as noise.
 */

/** The escape byte itself, and the only control character this file names. */
private const val ESC = '\u001b'

/** BEL, one of the two things that ends an OSC string. */
private const val BEL = '\u0007'

/** The sixteen names Zed writes as `terminal.ansi.*`, in SGR order. */
private val ANSI_NAMES = listOf(
    "black", "red", "green", "yellow", "blue", "magenta", "cyan", "white",
    "bright_black", "bright_red", "bright_green", "bright_yellow",
    "bright_blue", "bright_magenta", "bright_cyan", "bright_white",
)

/**
 * [text] with its escapes turned into spans over [base].
 *
 * PURE, AND DELIBERATELY THEME-BLIND. The palette arrives as a lambda rather
 * than as a `ZedTheme`, for two reasons that are really one: it makes the
 * parser host-testable without a parsed theme (`AnsiTextTest`), and it keeps
 * the file honest about the seam — this decides *which colour name* a byte
 * means, and the caller decides what that name looks like, which on the Zed
 * half of the app is a raw `terminal.ansi.*` read. That is the same split
 * `TerminalPane.kt:1035` already uses for the emulator's own palette, and it
 * is why this cannot bake One Dark's hexes the way `OrchBits.kt:150` did.
 *
 * WHAT IS UNDERSTOOD: `ESC [ ... m` — reset, bold, dim, italic, underline and
 * their "off" codes, the eight normal and eight bright foregrounds, `38;5;n`
 * (the 256-colour cube and grey ramp) and `38;2;r;g;b`. WHAT IS DROPPED ON
 * PURPOSE: every background code. A build log is drawn on the editor's ground
 * inside a bordered island, and a run of cells repainted in the compiler's
 * idea of a background would punch a hole through it. Every other escape —
 * `ESC [ K`, an OSC title, a charset selector — is consumed and discarded,
 * because rule 2 of the log is that nothing is ever *dropped*, and a control
 * sequence rendered as mojibake is worse than a line that is gone.
 *
 * [palette] is asked for one of [ANSI_NAMES]; the 216-colour cube and the
 * 24-step grey ramp are computed here instead, because they are absolute sRGB
 * in the standard and no theme has a key for them.
 */
internal fun ansiAnnotate(
    text: String,
    base: Color,
    palette: (String) -> Color,
): AnnotatedString {
    if (!text.contains(ESC)) return AnnotatedString(text)
    val out = AnnotatedString.Builder(text.length)
    var sgr = Sgr()
    var runStart = 0
    var i = 0

    fun closeRun() {
        val end = out.length
        if (end != runStart) sgr.span(base)?.let { out.addStyle(it, runStart, end) }
        runStart = end
    }

    while (i < text.length) {
        val ch = text[i]
        if (ch != ESC) {
            out.append(ch)
            i++
            continue
        }
        when (text.getOrNull(i + 1)) {
            // CSI: parameter bytes, then one final byte in 0x40..0x7E.
            '[' -> {
                var j = i + 2
                while (j < text.length && text[j].code !in 0x40..0x7E) j++
                if (j < text.length && text[j] == 'm') {
                    closeRun()
                    sgr = sgr.applying(text.substring(i + 2, j), palette)
                }
                i = if (j < text.length) j + 1 else text.length
            }
            // OSC: a string terminated by BEL or by ESC backslash.
            ']' -> {
                var j = i + 2
                while (j < text.length && text[j] != BEL &&
                    !(text[j] == ESC && text.getOrNull(j + 1) == '\\')
                ) {
                    j++
                }
                i = when {
                    j >= text.length -> text.length
                    text[j] == BEL -> j + 1
                    else -> j + 2
                }
            }
            // A stray ESC at the end of a truncated line: there is nothing
            // after it to consume, so it simply does not reach the screen.
            null -> i = text.length
            // Every other escape: zero or more intermediate bytes in
            // 0x20..0x2F, then one final byte. `ESC ( B` (select ASCII) is
            // THREE bytes, not two, which is exactly the off-by-one that
            // leaves a stray `B` in the middle of a build log.
            else -> {
                var j = i + 1
                while (j < text.length && text[j].code in 0x20..0x2F) j++
                i = if (j < text.length) j + 1 else text.length
            }
        }
    }
    closeRun()
    return out.toAnnotatedString()
}

/**
 * [text] with every escape removed and nothing else changed.
 *
 * What goes on the clipboard and into "Ask the agent about this". The agent is
 * reading the same words the screen is, and a prompt full of raw SGR bytes is
 * wasted tokens and one confused model.
 */
internal fun stripAnsi(text: String): String {
    if (!text.contains(ESC)) return text
    // Reuse the parser rather than writing a second, subtly different scanner:
    // "which bytes are control" is exactly the question that has to have one
    // answer in this file, and the spans cost nothing when thrown away.
    return ansiAnnotate(text, Color.Unspecified) { Color.Unspecified }.text
}

/** The graphic-rendition state a run of characters is drawn with. */
private data class Sgr(
    val fg: Color? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    /** Null when the run is plain, so the common case adds no span at all. */
    fun span(base: Color): SpanStyle? {
        if (fg == null && !bold && !dim && !italic && !underline) return null
        val ink = fg ?: base
        return SpanStyle(
            // Dim is alpha rather than a darker colour: the ink is whatever
            // the theme said, and pulling it toward a background this file
            // cannot see is how a "faint" note becomes invisible on a light
            // theme.
            color = if (dim) ink.copy(alpha = 0.65f) else ink,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }

    fun applying(params: String, palette: (String) -> Color): Sgr {
        // `ESC[m` is `ESC[0m`, and an empty parameter is a zero.
        val codes = params.split(';').map { it.trim().toIntOrNull() ?: 0 }
        var state = this
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> state = Sgr()
                1 -> state = state.copy(bold = true)
                2 -> state = state.copy(dim = true)
                3 -> state = state.copy(italic = true)
                4 -> state = state.copy(underline = true)
                21, 22 -> state = state.copy(bold = false, dim = false)
                23 -> state = state.copy(italic = false)
                24 -> state = state.copy(underline = false)
                39 -> state = state.copy(fg = null)
                in 30..37 -> state = state.copy(fg = palette(ANSI_NAMES[code - 30]))
                in 90..97 -> state = state.copy(fg = palette(ANSI_NAMES[code - 90 + 8]))
                38 -> when (codes.getOrNull(i + 1)) {
                    5 -> {
                        state = state.copy(fg = indexed(codes.getOrNull(i + 2) ?: 0, palette))
                        i += 2
                    }

                    2 -> {
                        state = state.copy(
                            fg = Color(
                                red = (codes.getOrNull(i + 2) ?: 0).coerceIn(0, 255),
                                green = (codes.getOrNull(i + 3) ?: 0).coerceIn(0, 255),
                                blue = (codes.getOrNull(i + 4) ?: 0).coerceIn(0, 255),
                            ),
                        )
                        i += 4
                    }

                    else -> Unit
                }
                // Backgrounds (40-49, 100-107) and everything else: consumed
                // and dropped, which is the point of the sweep.
                else -> Unit
            }
            i++
        }
        return state
    }
}

/** `38;5;n`: sixteen names, a 6x6x6 cube, then a 24-step grey ramp. */
private fun indexed(n: Int, palette: (String) -> Color): Color = when (n) {
    in 0..15 -> palette(ANSI_NAMES[n])
    in 16..231 -> {
        val c = n - 16
        Color(CUBE[c / 36], CUBE[(c / 6) % 6], CUBE[c % 6])
    }

    in 232..255 -> {
        val level = 8 + (n - 232) * 10
        Color(level, level, level)
    }

    else -> palette(ANSI_NAMES[7])
}

/** xterm's cube steps: 0, then 95 and 40 apart. Not evenly spaced, on purpose. */
private val CUBE = intArrayOf(0, 95, 135, 175, 215, 255)
