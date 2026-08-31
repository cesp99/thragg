package to.eyed.seeker.code.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hybrid, enforced by grep.
 *
 * Seeker looks like two things on purpose. Where it is an editor it looks like
 * Zed, because the buffer, the terminal and the diff have to agree with
 * tree-sitter's colours; where it is an app it looks like Android, because it
 * is one. Both halves come out of one `remember(theme)` — `ZedTheme.palette()`
 * produces the M3 `ColorScheme` and the `SeekerColors` together — so they
 * cannot disagree about a hue. What they *can* do, and what no reviewer will
 * catch twice, is drift about which half a given screen is in.
 *
 * So the boundary is a test rather than a paragraph (docs/VISUAL.md, P13 —
 * "a one-grep contract is the only way a boundary this size survives its
 * second year"):
 *
 *  - a file under [ZED_HALF] must not read `MaterialTheme.colorScheme`. Its
 *    inks are Zed's own keys, drawn **raw**, because Zed draws them raw and
 *    the point of that half is to look like Zed;
 *  - a file under [MATERIAL_HALF] must not read `LocalZedTheme`. Its inks come
 *    from the solved scheme and from `LocalSeekerColors`, because a Material
 *    surface has to clear 4.5:1 and Ayu Light's `text.muted` is 2.79:1 on the
 *    panel it would be drawn on.
 *
 * [ZedCodeBlock][to.eyed.seeker.code.ui.components.ZedCodeBlock] is the single
 * named exception, and it is the seam itself: a code snippet inside a Material
 * sheet is a Zed island — editor ground, buffer face, raw spans — with a
 * Material `outlineVariant` border, because the island's *edge* belongs to the
 * sheet and that is what stops it reading as a hole.
 *
 * THE BASELINES ARE AN UPPER BOUND, not an equality, and that is the one way
 * this differs from `NoEmojiInUiTest`'s ratchet. That test fails when a file
 * gets *cleaner* than its entry, which is right for a finished rule: it keeps
 * the list honest. This one is landing in the middle of a conversion that
 * several chunks are doing in parallel, and a file that loses its last Zed read
 * must not fail the build of the chunk that cleaned it. So: a count may fall
 * to zero freely, and may never rise. Delete an entry when it reaches zero.
 *
 * Every remaining entry is a real debt with a name on it, and they divide into
 * exactly two kinds:
 *
 *  - **The Zed half's `theme.color("key", MaterialTheme.colorScheme.role)`
 *    idiom** — a Zed read with an M3 fallback that never fires, because every
 *    bundled theme defines the key. Harmless today and wrong in principle;
 *    they go when the editor pass lands, which is also when the drawing
 *    surface is allowed to be touched at all.
 *  - **The Material half's remaining Zed reads** — the transcript's code
 *    blocks, the build log's ANSI, the review pane's diff. Every one of them
 *    is a *snippet*, which means every one of them is a `ZedCodeBlock` waiting
 *    to happen; the chunks that own those files are converting them.
 */
class SeamTest {

    /**
     * Packages that are painted by Zed and answer to `ZedSurface`.
     *
     * 499 call sites when this was written. `ui/shell/changes/DiffScreen.kt`
     * and the editor host inside `ui/shell/code/CodeScreen.kt` are Zed-painted
     * too, but they are *hosts* — they wrap their body in `ZedSurface` and draw
     * their own chrome in Material — so they are checked as the Material files
     * they are.
     */
    private val ZED_HALF = listOf(
        "ui/editor",
        "ui/terminal",
        "ui/git",
        "ui/preview",
        "ui/search",
        "ui/diagnostics",
        "ui/media",
        "ui/tasks",
    )

    /** Packages that are the app: real Material 3, solved inks, ripple on. */
    private val MATERIAL_HALF = listOf(
        "ui/shell",
        "ui/agent",
        "ui/common",
        "ui/components",
    )

    /**
     * The seam, and the only file allowed to be both.
     *
     * A path suffix rather than a bare file name, so a second `ZedCodeBlock.kt`
     * somewhere else in the tree does not inherit the exemption.
     */
    private val SEAM = "ui/components/ZedCodeBlock.kt"

    /** Zed-half files still carrying an M3 fallback. May shrink, never grow. */
    private val zedReadingMaterial: Map<String, Int> = mapOf(
        "ui/editor/Completions.kt" to 5,
        "ui/editor/EditorPane.kt" to 9,
        "ui/editor/Hover.kt" to 2,
        "ui/editor/LspActions.kt" to 4,
        "ui/editor/SignatureHelp.kt" to 4,
        "ui/git/MergeResolvedBar.kt" to 2,
        "ui/terminal/TerminalPane.kt" to 12,
    )

    /** Material-half files still reading the Zed theme. May shrink, never grow. */
    private val materialReadingZed: Map<String, Int> = mapOf(
        "ui/agent/AgentReviewPane.kt" to 7,
        "ui/agent/ContextPicker.kt" to 2,
        "ui/agent/spettro/OrchBits.kt" to 2,
        "ui/shell/agent/AgentTranscript.kt" to 2,
        "ui/shell/build/BuildLogView.kt" to 5,
    )

    @Test
    fun `the Zed half does not read the Material scheme`() {
        val found = count(ZED_HALF, "MaterialTheme.colorScheme")
        assertNoneGrew(
            found = found,
            allowed = zedReadingMaterial,
            rule = "A file under ui/editor, ui/terminal, ui/git, ui/preview, ui/search,\n" +
                "ui/diagnostics, ui/media or ui/tasks is painted by Zed and must not read\n" +
                "MaterialTheme.colorScheme. Use theme.color(\"key\") — raw, with no fallback:\n" +
                "the Zed half's job is to agree with tree-sitter, and Zed draws its inks raw.",
        )
    }

    @Test
    fun `the Material half does not read the Zed theme`() {
        val found = count(MATERIAL_HALF, "LocalZedTheme").filterKeys { it != SEAM }
        assertNoneGrew(
            found = found,
            allowed = materialReadingZed,
            rule = "A file under ui/shell, ui/agent, ui/common or ui/components is the app half\n" +
                "and must not read LocalZedTheme. Use MaterialTheme.colorScheme and\n" +
                "LocalSeekerColors, whose inks are solved for contrast — Ayu Light's raw\n" +
                "text.muted is 2.79:1 and its created is 2.11:1.\n" +
                "A code snippet inside a sheet is the one exception, and it is a component:\n" +
                "ui/components/ZedCodeBlock.kt.",
        )
    }

    /**
     * The seam is where it is claimed to be.
     *
     * Cheap, and it is what makes the exemption above a *named* one rather than
     * a hole: if `ZedCodeBlock` is ever deleted, renamed or reduced to a
     * Material box, this fails and the exemption has to be re-argued instead of
     * quietly covering whatever moves into that path.
     */
    @Test
    fun `the seam component exists and reads both halves`() {
        val file = File(sourceRoot(), SEAM)
        assertTrue("$SEAM is the named seam and it is not there.", file.isFile)
        val text = file.readText()
        assertTrue(
            "$SEAM must read LocalZedTheme — the island's ground, ink and spans are Zed's.",
            "LocalZedTheme" in text,
        )
        assertTrue(
            "$SEAM must read the Material outline — the island's EDGE belongs to the sheet,\n" +
                "and that border is what stops a snippet reading as a hole in the card.",
            "outlineVariant" in text,
        )
    }

    // ---- the mechanics ------------------------------------------------------

    /** `<module>/src/main/java/to/eyed/seeker/code`, found from wherever Gradle ran us. */
    private fun sourceRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "src/main/res/values/strings.xml").isFile) {
            dir = dir.parentFile ?: error("cannot find the app module from ${File("").absolutePath}")
        }
        return File(dir, "src/main/java/to/eyed/seeker/code")
    }

    /**
     * How many times [needle] appears in code — comments skipped, exactly as
     * `NoEmojiInUiTest` skips them, because the KDoc on both sides of this
     * boundary has to be able to *name* the thing it is explaining. This very
     * file would otherwise fail three of its own rules.
     */
    private fun count(roots: List<String>, needle: String): Map<String, Int> {
        val src = sourceRoot()
        val out = HashMap<String, Int>()
        for (root in roots) {
            val dir = File(src, root)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val hits = file.readLines().sumOf { line ->
                    val code = line.trimStart()
                    if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
                        0
                    } else {
                        countOccurrences(line, needle)
                    }
                }
                if (hits > 0) {
                    out[file.relativeTo(src).path.replace(File.separatorChar, '/')] = hits
                }
            }
        }
        return out
    }

    private fun countOccurrences(line: String, needle: String): Int {
        var from = line.indexOf(needle)
        var n = 0
        while (from >= 0) {
            n++
            from = line.indexOf(needle, from + needle.length)
        }
        return n
    }

    private fun assertNoneGrew(found: Map<String, Int>, allowed: Map<String, Int>, rule: String) {
        val grew = found.filter { (file, n) -> n > (allowed[file] ?: 0) }
        assertTrue(
            rule + "\n\n" + grew.entries.sortedBy { it.key }.joinToString("\n") { (file, n) ->
                "  $file: $n now, ${allowed[file] ?: 0} allowed"
            },
            grew.isEmpty(),
        )
    }
}
