package to.eyed.seeker.code.ui.agent

/*
 * The one piece of ui/agent/AgentPanel.kt that outlived it.
 *
 * AgentPanel.kt was 3658 lines of the pre-shell agent surface and is gone
 * (docs/VISUAL.md, P4). Its composer's mention bookkeeping is not: the shell's
 * composer, ui/shell/agent/AgentComposer.kt, calls [mentionTokensIn] on every
 * send to decide which `@path` attachments are still standing in the message.
 * That is protocol behaviour with a test behind it (MentionTokenTest), not
 * chrome, so it moves rather than dying with the file. The regex is verbatim.
 */

/** Every complete `@path` token in a message — what actually gets sent. */
private val MentionTokens = Regex("(?:^|\\s)@([^\\s@]+)")

/**
 * The `@path` tokens actually standing in [message].
 *
 * Whole tokens, which is the whole point: a substring test matched a path
 * that is a *prefix* of another, so a mention the user had deleted and
 * replaced went out anyway and the engine embedded its contents.
 */
internal fun mentionTokensIn(message: String): Set<String> =
    MentionTokens.findAll(message).map { it.groupValues[1] }.toSet()
