package to.eyed.thragg

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No emoji, and no glyph standing in for an icon.
 *
 * This exists because the defect it catches shipped. The app drew its
 * controls as Unicode characters inside `Text` composables — a magnifier for
 * search, a hamburger for the file sheet, `✅` for a completed goal — and it
 * got as far as the owner's phone before anyone noticed. Two things are wrong
 * with it, and neither shows up in a build log:
 *
 *  - **A glyph is not an icon.** It renders at the *font's* optical size and
 *    stroke weight rather than an icon metric, so it comes out thin and small
 *    beside real chrome, and it cannot be sized or tinted like the icons
 *    around it. On a 480dpi panel the difference is obvious and unfixable
 *    from the call site.
 *  - **It is font-dependent.** A device whose UI face lacks the codepoint
 *    draws tofu. `⛨`, `⌁` and `⬢` are not in every Android font.
 *
 * And an emoji is worse again: it renders in colour, at the system's emoji
 * scale, and reads as a chat app rather than a developer tool.
 *
 * The rule is enforced here rather than in review because six agents writing
 * Compose in parallel will reintroduce it, and a rule nothing checks is a
 * preference. The two escape hatches below are deliberately narrow.
 */
class NoEmojiInUiTest {

    /**
     * Characters that may appear in a user-facing string.
     *
     * Only these three, and each earns its place:
     *  - `·` U+00B7, the separator in "3 errors · 2 warnings", which is
     *    typography rather than an icon;
     *  - `—` and `–`, dashes, likewise;
     *  - `…`, the ellipsis, which is a character and not a control.
     */
    private val allowedPunctuation = setOf('·', '—', '–', '…', '‘', '’', '“', '”')

    /**
     * Strings that carry a glyph because *the wire* carries it.
     *
     * Spettro streams its own status lines with a leading character — a
     * terminal has nothing else to mark a line with — and the matcher in
     * `AgentTranscript.kt` has to hold them verbatim to recognise what
     * arrives. Those never reach the screen: `pillMark()` strips the glyph and
     * draws an icon in its place. Matching the protocol is not decorating the
     * UI, so this is an exemption for the matcher and nothing else.
     */
    private val allowedFiles = setOf(
        "AgentTranscript.kt",
    )

    /**
     * The one string resource whose subject *is* a glyph.
     *
     * `show_whitespaces` documents what the editor draws for a space and a
     * tab. Naming those characters in prose is the string's whole job, and
     * replacing them with icons would make the sentence describe something
     * other than what the setting does.
     */
    private val allowedStringNames = setOf(
        "settings_zed_s_show_whitespaces_a_for",
    )

    /**
     * Glyph-as-icon sites that predate this test, by file.
     *
     * A ratchet, not an amnesty. The rule went in after the code did, and
     * converting thirty call sites in one change would bury the rule itself in
     * the diff — so the test asserts the count per file never *grows*, and each
     * line here is a debt with a name on it. Lower the number when you convert
     * one; delete the entry at zero. A file that is not listed may not have any
     * at all.
     *
     * The three biggest — TerminalPane, ProjectSearchPanel, BufferSearchBar —
     * are reachable UI (Build's Shell mode, and the find bars in Code), so they
     * are the ones worth doing first.
     *
     * The six entries at the bottom arrived in P4 rather than being written
     * then: they are in packages that used to be skipped wholesale as "about
     * to be deleted", and the parts of those packages that survived the
     * demolition are now scanned like everything else. Two of them are keyboard
     * chord labels ("Ctrl →") rather than icons, which is a fair argument for
     * an exemption and not one this test is going to make on their behalf —
     * the shell's menus print chords through a drawable now.
     */
    private val baseline: Map<String, Int> = mapOf(
        "solana/agents/SpettroInstall.kt" to 2,
        "core/Tasks.kt" to 1,
        "ui/shell/changes/ChangesScreen.kt" to 1,
        "ui/common/MarkdownText.kt" to 2,
        "ui/search/ProjectSearchPanel.kt" to 5,
        "ui/search/BufferSearchBar.kt" to 4,
        "ui/terminal/TerminalPane.kt" to 10,
        "ui/terminal/TerminalSearchBar.kt" to 1,
        "ui/workspace/ProjectPanel.kt" to 3,
        "ui/workspace/GoToLine.kt" to 2,
        "ui/workspace/ContextMenu.kt" to 1,
        "ui/agent/AgentReviewPane.kt" to 1,
        "ui/git/AskpassDialog.kt" to 1,
        "ui/git/DiffPane.kt" to 1,
        "ui/workspace/RenameSymbol.kt" to 2,
        "ui/workspace/LspLogsPane.kt" to 2,
    )

    /**
     * Packages still carrying the inherited desktop UI.
     *
     * Converting glyphs in a file that is about to be deleted is work with a
     * negative return, so the packages awaiting demolition are skipped whole.
     * The exclusion is a dated concession, not a permanent carve-out.
     *
     * P4 cut it from seven entries to one. ui/workspace, ui/agent, ui/git,
     * ui/preview, ui/media and ui/tasks have had their dead halves removed
     * (docs/UI.md, "What is removed"), so what is left in them is code that
     * ships and is now scanned like everything else — its remaining glyph
     * sites moved into `baseline` above, where they are debts with names
     * rather than a hole in the net. ui/editor is the last one out: the vim
     * package, the minimap and the inlay hints go in the editor pass, and
     * until they do a third of that directory is about to stop existing.
     */
    private val notYetDeleted = setOf(
        "ui/editor",
    )

    private fun pictographs(text: String): Set<Char> =
        text.filter { ch ->
            if (ch in allowedPunctuation || ch.code < 0x2000) return@filter false
            val b = Character.UnicodeBlock.of(ch)
            b == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS ||
                b == Character.UnicodeBlock.EMOTICONS ||
                b == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS ||
                b == Character.UnicodeBlock.SUPPLEMENTAL_SYMBOLS_AND_PICTOGRAPHS ||
                b == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS ||
                b == Character.UnicodeBlock.DINGBATS ||
                b == Character.UnicodeBlock.ARROWS ||
                b == Character.UnicodeBlock.GEOMETRIC_SHAPES ||
                b == Character.UnicodeBlock.MISCELLANEOUS_TECHNICAL ||
                b == Character.UnicodeBlock.BOX_DRAWING
        }.toSet()

    /** The module root, whatever directory Gradle chose to run us from. */
    private fun appDir(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "src/main/res/values/strings.xml").isFile) {
            dir = dir.parentFile ?: error("cannot find the app module from ${File("").absolutePath}")
        }
        return dir
    }

    @Test
    fun `no pictographs in string resources`() {
        val strings = File(appDir(), "src/main/res/values/strings.xml")
        val offences = strings.readLines().withIndex().mapNotNull { (i, line) ->
            if (line.trimStart().startsWith("<!--")) return@mapNotNull null
            if (allowedStringNames.any { line.contains("name=\"$it\"") }) return@mapNotNull null
            val found = pictographs(line)
            if (found.isEmpty()) null else "strings.xml:${i + 1}  ${found.joinToString("")}  ${line.trim().take(80)}"
        }
        assertTrue(
            "A user-facing string may not carry a pictograph — use a drawable and ui/theme/Icons.kt.\n" +
                offences.joinToString("\n"),
            offences.isEmpty(),
        )
    }

    @Test
    fun `no glyphs used as icons in shipped Compose`() {
        val src = File(appDir(), "src/main/java/to/eyed/thragg")
        val literal = Regex("\"([^\"\\\\\\n]{0,120})\"")
        val offences = mutableListOf<String>()

        src.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.relativeTo(src).path.replace(File.separatorChar, '/')
            if (notYetDeleted.any { rel.startsWith(it) }) return@forEach
            if (file.name in allowedFiles) return@forEach

            file.readLines().forEachIndexed { i, line ->
                val code = line.trimStart()
                // Comments explain the rule and often have to name the glyph.
                if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) return@forEachIndexed
                literal.findAll(line).forEach { m ->
                    val found = pictographs(m.groupValues[1])
                    if (found.isNotEmpty()) {
                        offences += "$rel:${i + 1}  ${found.joinToString("")}  ${m.groupValues[1].take(60)}"
                    }
                }
            }
        }

        val byFile = offences.groupingBy { it.substringBefore(':') }.eachCount()

        val grew = byFile.filter { (file, n) -> n > (baseline[file] ?: 0) }
        assertTrue(
            "A glyph in a Text is not an icon: it renders at the font's weight, cannot be tinted or\n" +
                "sized with the chrome around it, and shows tofu where the face lacks the codepoint.\n" +
                "Use a drawable through ui/theme/Icons.kt (ThraggIcon / ThraggIconButton).\n\n" +
                grew.entries.joinToString("\n") { (f, n) ->
                    "$f: $n now, ${baseline[f] ?: 0} allowed"
                } + "\n\n" + offences.filter { it.substringBefore(':') in grew }.joinToString("\n"),
            grew.isEmpty(),
        )

        // The other half of a ratchet: a debt paid off must be struck from the
        // list, or the list stops meaning anything and quietly permits a
        // regression in a file someone already cleaned.
        val stale = baseline.filter { (file, n) -> (byFile[file] ?: 0) < n }
        assertTrue(
            "These are cleaner than the baseline claims. Lower or remove the entry in `baseline`:\n" +
                stale.entries.joinToString("\n") { (f, n) ->
                    "$f: ${byFile[f] ?: 0} now, baseline says $n"
                },
            stale.isEmpty(),
        )
    }
}
