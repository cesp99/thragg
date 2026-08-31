package to.eyed.seeker.code.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every outlined button in the app draws the shared edge.
 *
 * THE DEFECT THIS PINS was invisible in code review and obvious on the
 * device: material3 1.4.0 borders an `OutlinedButton` with `outlineVariant`,
 * which `MaterialBridge` derives from Zed's `border.variant` — the divider
 * ink, chosen precisely so it would not be noticed. Every filled/outlined
 * pair in the app therefore rendered as filled + TEXT, which in Material's
 * own grammar says "do this, or back out" rather than "here are two things
 * you can do". Eight pairs said the wrong sentence and nobody could point at
 * a line of wrong code, because the wrong colour was a default.
 *
 * A default that is wrong for the whole app cannot be fixed by remembering.
 * `outlinedButtonEdge()` is the fix and this is what keeps it applied: the
 * next `OutlinedButton` someone adds inherits the invisible border again
 * unless they pass `border`, and that omission looks exactly like correct
 * code. So it fails here instead of on a screen six weeks later.
 *
 * It checks for `border =` rather than for `outlinedButtonEdge()` on purpose.
 * A site with a genuine reason to draw a different edge — a destructive
 * action's error-coloured outline, say — is a decision, and the rule is only
 * that the decision has to be MADE. Inheriting the divider ink is what is
 * forbidden.
 */
class OutlinedButtonEdgeTest {

    @Test
    fun `every outlined button spells its border`() {
        val bare = mutableListOf<String>()
        sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { i, line ->
                    // The call, not a mention: an import, a KDoc reference or
                    // a comment naming the component is not a call site, and
                    // this file's own prose has to survive its own rule.
                    if (!line.trimStart().startsWith("OutlinedButton(")) return@forEachIndexed
                    // The argument list runs to the closing paren of the call,
                    // which for every site in this app is the `) {` that opens
                    // the content lambda or the `)` on its own line.
                    val args = lines.drop(i + 1).takeWhile { !it.trimStart().startsWith(")") }
                    if (args.none { "border =" in it }) {
                        bare += "${file.name}:${i + 1}"
                    }
                }
            }
        assertEquals(
            "These outlined buttons inherit Material's default border, which under this\n" +
                "scheme is the divider ink and is invisible on a Material sheet — the pair\n" +
                "then reads as filled + text. Pass `border = outlinedButtonEdge(enabled)`.\n" +
                "Found: $bare",
            emptyList<String>(),
            bare,
        )
    }

    /** `<module>/src/main/java/to/eyed/seeker/code`, found from wherever Gradle ran us. */
    private fun sourceRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "src/main/res/values/strings.xml").isFile) {
            dir = dir.parentFile ?: error("cannot find the app module from ${File("").absolutePath}")
        }
        return File(dir, "src/main/java/to/eyed/seeker/code")
    }
}
