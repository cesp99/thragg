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
 * The exception is a **fresh** seed — Build's and Problems' "Fix with agent"
 * ([AgentFix]), which is a request of its own rather than more of a sentence
 * the user is writing. It replaces what is waiting instead of joining it: the
 * displaced draft (an unsent New program sentence, something half-typed) is
 * set aside on the thread and comes back into the composer once the fix is
 * sent ([drainInto]). Merging them sent "This is a new Anchor program called
 * seeker_vault." as the first paragraph of a compiler error.
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
        AgentFix.seed = { text -> offer(text, fresh = true) }
        AgentThreadSeed.hasReader = true
    }

    /**
     * Put [text] in the composer, after whatever is already waiting there —
     * or, when [fresh], *instead* of it: a held ordinary seed is set aside
     * (its [DraftSeed.aside]), and a held fresh one is superseded by the
     * newer request while whatever it had set aside is kept.
     */
    fun offer(text: String, mentions: List<AgentMention> = emptyList(), fresh: Boolean = false) {
        if (text.isBlank() && mentions.isEmpty()) return
        val held = pending
        pending = when {
            held == null -> DraftSeed(text, mentions, fresh = fresh)
            fresh -> DraftSeed(
                text = text,
                mentions = mentions,
                fresh = true,
                aside = if (held.fresh) held.aside else held.copy(aside = null),
            )
            else -> held.copy(
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
 *
 * [fresh] marks a request that starts a message of its own ("Fix with
 * agent"); [aside] is an ordinary seed it displaced while both were still in
 * the mailbox, which the composer parks rather than drops.
 */
data class DraftSeed(
    val text: String,
    val mentions: List<AgentMention> = emptyList(),
    val fresh: Boolean = false,
    val aside: DraftSeed? = null,
)

/** What the composer's field holds after a seed is drained into it. */
internal data class Drained(
    val text: String,
    val mentions: List<AgentMention>,
    /** The draft a fresh seed displaced, to come back after it is sent. */
    val parked: DraftSeed?,
)

/**
 * Drain [seed] into a field holding [field] and [fieldMentions].
 *
 * An ordinary seed appends, as it always has. A fresh one takes the field
 * whole, and what it displaced — its own [DraftSeed.aside] and the field —
 * is returned as [Drained.parked] for the composer to hold on the thread.
 * A field that still says exactly [supersedes] (the previous fresh seed,
 * never sent) is dropped rather than parked: an older build error coming
 * back after the newer one is sent is noise, not a draft.
 */
internal fun drainInto(
    field: String,
    fieldMentions: List<AgentMention>,
    seed: DraftSeed,
    supersedes: String? = null,
): Drained {
    val existing = field.trimEnd()
    if (!seed.fresh) {
        return Drained(
            text = if (existing.isEmpty()) seed.text else existing + "\n\n" + seed.text,
            mentions = (fieldMentions + seed.mentions).distinct(),
            parked = null,
        )
    }
    val stale = supersedes != null && existing.trim() == supersedes.trim()
    val displaced = listOfNotNull(
        seed.aside,
        DraftSeed(existing, fieldMentions).takeIf { !stale && (existing.isNotBlank() || fieldMentions.isNotEmpty()) },
    )
    val parked = displaced.takeIf { it.isNotEmpty() }?.let { parts ->
        DraftSeed(
            text = parts.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n"),
            mentions = parts.flatMap { it.mentions }.distinct(),
        )
    }
    return Drained(text = seed.text, mentions = seed.mentions, parked = parked)
}

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
