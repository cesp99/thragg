package to.eyed.thragg.ui.shell.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import to.eyed.thragg.core.AgentMention
import to.eyed.thragg.solana.build.AgentFix
import to.eyed.thragg.ui.editor.Diagnostic
import to.eyed.thragg.ui.shell.projects.AgentThreadSeed

/**
 * The one door every other destination hands work to the agent through.
 *
 * Three surfaces outside this package end in "…and then say it to the agent":
 * the editor's `[ Fix ▸ ]` on a diagnostic (CodeScreen.kt), Build's
 * `[ Fix with agent ]` on a failed run ([AgentFix]), and New program's "open a
 * thread and describe it to the agent afterwards" ([AgentThreadSeed]). All
 * three fire while the Agent destination is **not composed** — that is what
 * "switch to Agent" means — so this is a process-wide mailbox rather than a
 * call into a composable. The composer drains it on its next composition,
 * which is the frame after the destination switch.
 *
 * It is Compose state so the drain is a recomposition rather than a poll, and
 * it *appends* rather than replacing: pressing Fix on three diagnostics before
 * looking at the screen must produce one prompt about three errors, not two
 * lost ones.
 *
 * Seeded, never sent. Every one of the three is half a sentence the user
 * finishes — docs/UI.md's New program note says so in as many words, and it is
 * just as true of a compiler error, which is a fact and not yet a request.
 */
object AgentSeams {

    /**
     * What is waiting for the composer, or null.
     *
     * Public and observable because the composer is the only reader and the
     * only writer of the *drain*; nothing else may consume it, since a seed
     * consumed by anything but a text field is a sentence that vanished.
     */
    var pending: DraftSeed? by mutableStateOf(null)
        private set

    /**
     * Register the seams the other destinations check for.
     *
     * Called once from `MainActivity`, **not** from the Agent screen's own
     * composition, and the difference is a real bug: both callers ask whether
     * anyone is listening *before* they navigate here, so a registration that
     * waited for the first visit to Agent would make the first "Fix with
     * agent" of a fresh install fall back to the clipboard and the first "open
     * a thread" toast "no coding agent is set up yet" — with the destination
     * sitting there, fully able to do both.
     */
    fun install() {
        AgentFix.seed = { text -> offer(text) }
        AgentThreadSeed.hasReader = true
    }

    /** Put [text] in the composer, after whatever is already waiting there. */
    fun offer(text: String, mentions: List<AgentMention> = emptyList()) {
        if (text.isBlank() && mentions.isEmpty()) return
        val held = pending
        pending = if (held == null) {
            DraftSeed(text, mentions)
        } else {
            DraftSeed(
                text = listOf(held.text.trimEnd(), text).filter { it.isNotEmpty() }.joinToString("\n\n"),
                // Distinct because two errors in the same file are two seeds
                // naming one path, and the agent must not be told to read it
                // twice.
                mentions = (held.mentions + mentions).distinct(),
            )
        }
    }

    /** Take what is waiting. The composer calls this and nothing else does. */
    fun take(): DraftSeed? {
        val held = pending ?: return null
        pending = null
        return held
    }

    /** Drop it — a project switch, whose seed is about files that are gone. */
    fun clear() {
        pending = null
    }
}

/**
 * Text bound for the composer, with whatever context it needs read alongside.
 *
 * The mentions matter: `@programs/escrow/src/state.rs` in the message body is
 * only text, and the agent reads a file because a `resource_link` block was
 * sent beside the prompt (AgentMentions.kt). A seed that pasted the path and
 * attached nothing would produce an agent guessing at a file it was never
 * handed.
 */
data class DraftSeed(val text: String, val mentions: List<AgentMention> = emptyList())

/**
 * What `[ Fix ▸ ]` says to the agent about one diagnostic.
 *
 * The compiler's own words, unparaphrased, plus where they came from. The
 * message is quoted whole rather than by [Diagnostic.firstLine] because
 * rustc's second and third lines are the half that says what to do — "expected
 * `&str`, found `String`" is on line two of a great many E0308s.
 *
 * `source` and `code` travel as the trailing parenthesis Zed's diagnostics
 * list already uses ("rustc E0308"), which is the string the user can search
 * for and the one the agent recognises.
 */
internal fun agentFixPrompt(path: String, diagnostic: Diagnostic): String {
    val tag = listOfNotNull(diagnostic.source, diagnostic.code).joinToString(" ")
    return buildString {
        append("Fix this ")
        append(diagnostic.severity.token)
        append(" in ")
        append(path)
        append(':')
        // 1-based, as the compiler, the terminal and every LSP client on earth
        // spell a position — the engine's rows are 0-based.
        append(diagnostic.row + 1)
        append(':')
        append(diagnostic.colUtf16 + 1)
        if (tag.isNotEmpty()) {
            append(" (")
            append(tag)
            append(')')
        }
        append("\n\n")
        append(diagnostic.message.trim())
    }
}
