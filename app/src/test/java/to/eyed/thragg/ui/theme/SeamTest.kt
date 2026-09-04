package to.eyed.thragg.ui.theme

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
 * [ZedCodeBlock][to.eyed.thragg.ui.components.ZedCodeBlock] is the single
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
 *
 * A count is not the same thing as a *named exception*, and there are three of
 * those: [SEAM], and the two the project tree keeps — see
 * [the project tree's only Zed read is its git status] and
 * [the Zed theme is not smuggled into the Material half through a parameter]. Each
 * has a test that pins what the exception is FOR, so it cannot quietly widen
 * into cover for the next unconverted surface. That is the difference between
 * a debt that is allowed to sit and a decision that was made.
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

    /**
     * Packages that are the app: real Material 3, solved inks, ripple on.
     *
     * `ui/workspace` is in this list and it was the uncomfortable one. The
     * demolition pass deleted 31 of its files and left the rest behind
     * *without moving them*, so the package straddled nothing: every survivor
     * with a live caller is drawn INSIDE a Material surface — `ProjectPanel`
     * inside the Files sheet, `ContextMenu` under the Code and Build overflow
     * buttons, `NotificationHost` over the whole app, `AboutDialog`,
     * `GoToLine`, `OutlinePicker` and `PickerModal` from the shell. It was in
     * neither list when this test was first written, which meant forty Zed
     * reads on the Material side of the seam were invisible to the ratchet;
     * the file tree drawing its own darker `panel.background` and a full-bleed
     * `element.selected` band inside a `surfaceContainer` sheet is what that
     * blind spot looked like on a phone, and it shipped.
     *
     * All forty are gone. What is left in the map below for this package is
     * two reads in one file, and they are an exception rather than a debt: see
     * [the project tree's only Zed read is its git status].
     */
    private val MATERIAL_HALF = listOf(
        "ui/shell",
        "ui/agent",
        "ui/common",
        "ui/components",
        "ui/workspace",
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
        // The import and the one `LocalZedTheme.current` that feeds
        // [GitStatusColours.forProjectPanel]. NOT debt — the tree's git inks
        // are the theme's on purpose — and a count alone would not say so,
        // which is why the test below pins what those two reads may be for.
        // The other ten ui/workspace entries that stood here are gone: the
        // panel, both menus, the picker chrome, the toasts, the two inline
        // panels, the LSP prompt and log and the About dialog now take the M3
        // roles. A ported file leaves this map; it does not sit at zero.
        "ui/workspace/ProjectPanel.kt" to 2,
    )

    /**
     * Material-half files that take a [ZedTheme] as a PARAMETER.
     *
     * The second hiding place, and the reason the first audit of `ui/workspace`
     * undercounted. `LocalZedTheme` is a composition local and greps cleanly;
     * a helper that takes `theme: ZedTheme` and reads eight keys off it does
     * not, and its caller shows up as one read rather than eight. That is
     * exactly the shape of `GitStatusColours`, and it is exactly the shape the
     * next unconverted surface will take if it is extracted before it is
     * converted — a `fun panelColours(theme: ZedTheme, …)` is one refactor
     * away from being invisible again.
     *
     * NOW THE WHOLE MATERIAL HALF, not just the package the blind spot was
     * found in. Scoping it to `ui/workspace` was right while four chunks were
     * converting in parallel and a widened check could have failed a build for
     * a helper its owner had not baselined yet; that is over, the rest of the
     * half was audited, and it turned out to hold exactly one other match.
     * Leaving the check narrow would have meant the next `ui/agent` helper
     * could hide the same way the tree's did.
     */
    private val zedThemeParameters: Map<String, Int> = mapOf(
        // `from`, `forProjectPanel`, `resolve`, `lookup`. The version-control
        // hues MEAN something and stay Zed's; only their lightness is solved,
        // by `solvedOn`, for the Material ground they are now drawn on.
        "ui/workspace/GitStatusColours.kt" to 4,
        // NOT COLOURS. Both are `ZedTheme.Meta` — a theme's name and whether
        // it is dark — which is what a list OF themes is necessarily made of;
        // the rows draw their three swatches from an already-resolved
        // `ThemeEntry`, not from a theme handed in. The grep cannot tell
        // `: ZedTheme.Meta` from `: ZedTheme` and it is not made cleverer on
        // purpose: a regex that excluded `.Meta` would also excuse a
        // `: ZedTheme.Meta` that had quietly grown a colour accessor, and an
        // entry with a written reason is worth more than one the pattern
        // silently skips.
        "ui/shell/settings/ThemeList.kt" to 2,
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
     * The Zed theme does not get into `ui/workspace` through a parameter.
     *
     * The companion to the rule above, and the one that would have caught this
     * package the first time. `LocalZedTheme` is a composition local, so a
     * grep for it finds every *composable* that reads the theme — but a plain
     * function that takes `theme: ZedTheme` and pulls eight keys off it is
     * invisible to that grep, and its caller reads as one hit rather than
     * eight. `GitStatusColours` is exactly that shape and is exactly why this
     * exists: it is legitimate, so it is baselined; the next one will not be,
     * and it now has to argue for itself before it lands.
     */
    @Test
    fun `the Zed theme is not smuggled into the Material half through a parameter`() {
        val found = count(MATERIAL_HALF, ": ZedTheme")
        assertNoneGrew(
            found = found,
            allowed = zedThemeParameters,
            rule = "A Material-half helper that takes `theme: ZedTheme` reads the Zed theme\n" +
                "without reading LocalZedTheme, so the seam rule above cannot see it. That is\n" +
                "how forty raw Zed colours were drawn inside a Material sheet for a release.\n" +
                "Take the colours themselves — MaterialTheme.colorScheme roles, or\n" +
                "LocalSeekerColors for what M3 has no role for — not the theme they came\n" +
                "from. GitStatusColours is the one exception and it is baselined:\n" +
                "version-control HUES carry meaning across the editor, the diff and the tree,\n" +
                "so they stay Zed's and only their lightness is solved.",
        )
    }

    /**
     * The project tree's two Zed reads are the git ones, and nothing else.
     *
     * `ProjectPanel.kt` is 2,800 lines and its baseline is 2, which under the
     * upper-bound rule would let a *different* pair of reads move in
     * unnoticed — a new `theme.color("panel.background")` would keep the count
     * at two and pass. That is precisely the failure this package already had
     * once, so the exception is pinned by what it is FOR rather than by how
     * many reads it costs: the panel may hold the theme to hand it to
     * [GitStatusColours], and may not paint with it.
     *
     * `solvedOn` is asserted too, because it is the whole argument. Keeping a
     * Zed hue is right — the tree's amber has to be the diff's amber — and
     * drawing it raw on a Material sheet is not: Ayu Light's `created` is
     * 2.11:1 there. Drop the solve and the exception stops being defensible,
     * so dropping it should fail here rather than in a screenshot.
     */
    @Test
    fun `the project tree's only Zed read is its git status`() {
        val name = "ui/workspace/ProjectPanel.kt"
        val file = File(sourceRoot(), name)
        assertTrue("$name is the file tree and it is not there.", file.isFile)
        val code = file.readLines().filterNot {
            val trimmed = it.trimStart()
            trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
        }
        val painting = code.filter { "theme.color(" in it }
        assertTrue(
            "$name is the app half: it is drawn inside the Files sheet, which is a\n" +
                "surfaceContainer. It may hold LocalZedTheme for ONE thing — handing it to\n" +
                "GitStatusColours.forProjectPanel, whose hues mean the same as the diff's —\n" +
                "and must paint everything else from MaterialTheme.colorScheme and\n" +
                "LocalSeekerColors. These lines paint with it:\n" +
                painting.joinToString("\n") { "  " + it.trim() },
            painting.isEmpty(),
        )
        assertTrue(
            "$name must pass its theme to GitStatusColours.forProjectPanel — that call is\n" +
                "the only reason it is allowed to read LocalZedTheme at all.",
            code.any { "GitStatusColours" in it } &&
                code.any { "forProjectPanel(theme" in it },
        )
        assertTrue(
            "$name must solve the git inks for the ground it draws them on:\n" +
                "`.solvedOn(...)`. The hue is Zed's because it carries meaning; the\n" +
                "LIGHTNESS is not, because Ayu Light's created is 2.11:1 on a sheet.",
            code.any { ".solvedOn(" in it },
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

    /** `<module>/src/main/java/to/eyed/thragg`, found from wherever Gradle ran us. */
    private fun sourceRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "src/main/res/values/strings.xml").isFile) {
            dir = dir.parentFile ?: error("cannot find the app module from ${File("").absolutePath}")
        }
        return File(dir, "src/main/java/to/eyed/thragg")
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
