package to.eyed.seeker.code.ui.workspace

import android.content.Context

/**
 * The command table behind the palette.
 *
 * The commands themselves are [WorkspaceCommand] — one enum case per thing the
 * workspace can be asked to do, carrying its Zed action name and when it
 * applies. This file is the *view* of that table: the human name, the chord
 * from [shortcutLabels], whether it can run right now, and the fuzzy matching
 * and recency ordering the palette lists them by.
 *
 * There is deliberately no second registry to keep in step. A command that
 * exists is a command the palette offers, because the palette reads
 * `WorkspaceCommand.entries` — adding one is a case in that enum and a branch
 * in `WorkspaceScreen.runCommand`, and nothing here changes.
 */

/**
 * What the workspace can do at this moment, which is what availability is
 * judged against.
 *
 * Every field answers a `return false` that already exists in
 * `WorkspaceScreen.runCommand`: the palette greys exactly what that function
 * would have refused, so a row you can click is a row that does something.
 */
data class CommandContext(
    val hasProject: Boolean,
    /** A tab is open — any tab, a picture included. */
    val hasActiveFile: Boolean,
    /**
     * The open tab is *text*. Closing, pinning and revealing work on a
     * picture; saving and searching in it do not.
     */
    val hasActiveBuffer: Boolean,
    /**
     * The open tab is a picture: Zed's `ImageViewer` context, where the zoom
     * actions live and nowhere else.
     */
    val hasActiveImage: Boolean = false,
    val tabCount: Int,
    val terminalCount: Int,
    /** Whether this build has a Linux userland to run git in. */
    val canClone: Boolean,
    /**
     * Whether this build has a Linux userland to install a language server
     * into. The same answer as [canClone] today and a different question: apt
     * and git are separately absent-able, and the day one is true without the
     * other they must not have shared a field.
     */
    val canInstallLanguageServer: Boolean = false,
    /**
     * Whether this build can run an ACP agent at all — it needs the userland,
     * because that is where Node and the agent live. Its own field for the
     * same reason [canInstallLanguageServer] is: these are three separately
     * absent-able features that happen to share an answer today.
     */
    val canUseAgent: Boolean = false,
    /** Whether the open file is one the preview can draw — Markdown or SVG. */
    val canPreview: Boolean = false,
    /** Whether the open file has merge-conflict markers in it right now. */
    val hasConflicts: Boolean = false,
    /**
     * Whether the open tab is a multibuffer — Zed's `editor::OpenExcerpts`
     * only means anything there.
     */
    val isMultibuffer: Boolean = false,
    /** Whether the navigation history has anywhere to go back to. */
    val canGoBack: Boolean = false,
    /**
     * Settings keys of panels whose dock is `"hidden"`: their toggle commands
     * grey out rather than silently doing nothing, because a hidden panel is
     * switched off and its chord must say so, not shrug.
     */
    val hiddenPanels: Set<String> = emptySet(),
    /** …or forward to, which only a GoBack can set up. */
    val canGoForward: Boolean = false,
    /** How many panes the work area is split into; the pane commands need two. */
    val paneCount: Int = 1,
    /** Whether a pane fills the work area on its own — `workspace::ToggleZoom` undoes it. */
    val isZoomed: Boolean = false,
    /**
     * The find bar is up over the editor, so the commands that act on it —
     * replace next/all, select every match — have something to act on.
     */
    val searchBarOpen: Boolean = false,
    /**
     * Whether the open tab is a file on disk — text or a picture, but not a
     * diff, the graph or the diagnostics, which have no file another app
     * could be handed. What "Open with…" and "Share…" need.
     */
    val activeFileOnDisk: Boolean = false,
    /** Whether a task has run this session — what `task::Rerun` needs. */
    val hasRunTask: Boolean = false,
    /**
     * Whether the toast stack has anything in it, so
     * `workspace::ClearAllNotifications` greys out rather than being a row
     * that clears nothing.
     */
    val hasNotifications: Boolean = false,
    /**
     * The project holds a folder beyond the one it was opened with, so
     * `workspace::RemoveWorktreeFromProject` has something to remove.
     */
    val hasExtraFolders: Boolean = false,
)

/** A command as the palette shows it. */
data class PaletteEntry(
    val command: WorkspaceCommand,
    /** Zed's humanised action name: "terminal panel: toggle". */
    val name: String,
    /** The chord that also runs it, or null when it has none. */
    val shortcut: String?,
    /** False greys the row: the command exists but cannot run right now. */
    val isEnabled: Boolean,
    /**
     * The `command_aliases` key that also reaches this command, if the user
     * wrote one. Printed beside the row for the reason the chord is: an alias
     * nobody can see is an alias nobody uses.
     */
    val alias: String? = null,
)

/** A matched entry, with the characters of [PaletteEntry.name] that matched. */
data class CommandMatch(
    val entry: PaletteEntry,
    val positions: List<Int>,
    val score: Int,
)

/**
 * Every command this build offers, judged against [context].
 *
 * [focus] is where the keyboard is, and it decides which chord each row
 * prints. It is not cosmetic: the same command answers to different chords in
 * the two focuses — Save is Ctrl+S in the workspace and Ctrl+Shift+S while a
 * shell has the keyboard, because a terminal owns every plain Ctrl+letter. A
 * palette opened from the terminal that printed the workspace chord would be
 * teaching the user to press Ctrl+S at a shell, which is XOFF: output stops,
 * and it looks exactly like a hang.
 */
fun paletteEntries(
    context: CommandContext,
    focus: Focus = Focus.Workspace,
    aliases: Map<String, String> = emptyMap(),
): List<PaletteEntry> {
    // One pass over the aliases rather than a scan per command: the map is
    // keyed the wrong way round for this question (typed string → action),
    // and there are more commands than aliases.
    val byAction = aliases.entries.associate { (alias, action) -> action to alias }
    return WorkspaceCommand.entries
        .filter { it.isOffered(context) }
        .map { command ->
            PaletteEntry(
                command = command,
                name = humanizeActionName(command.id),
                shortcut = shortcutLabel(command, focus),
                isEnabled = command.isAvailable(context),
                alias = byAction[command.id],
            )
        }
}

/**
 * Zed's `command_aliases` (command_palette.rs:471-474): a string the user
 * typed, replaced wholesale by the action it stands for.
 *
 * The *whole* query has to match a key — Zed looks the query up in the map
 * and does not touch it otherwise — which is what makes a one-letter alias
 * like `"W": "workspace::Save"` usable without stealing every query that
 * starts with a W. Case-sensitive, as Zed's `HashMap<String, _>` lookup is:
 * `W` and `w` are two aliases if you write two.
 */
fun resolveCommandAlias(query: String, aliases: Map<String, String>): String =
    aliases[query.trim()] ?: query

/**
 * Zed's `normalize_action_query` (command_palette.rs:48-67): make a query
 * that was typed as an *action name* match a humanised one.
 *
 * `humanizeActionName` turns `terminal_panel::Toggle` into "terminal panel:
 * toggle", so a query has to lose its underscores and its second colon to
 * stand a chance of matching. Runs of whitespace collapse for the same
 * reason. Nothing else is touched — the case is left alone, because the
 * matcher decides about case for itself.
 */
internal fun normalizeActionQuery(input: String): String = buildString {
    var last: Char? = null
    for (character in input.trim()) {
        val normalized = if (character == '_') ' ' else character
        if (last == ':' && normalized == ':') continue
        if (last != null && last.isWhitespace() && normalized.isWhitespace()) continue
        last = normalized
        append(normalized)
    }
}

/**
 * Zed's own action-name humanising: `terminal_panel::Toggle` becomes
 * "terminal panel: toggle".
 *
 * Zed's version also keeps acronyms together (`ToggleAI` → "toggle AI"); ours
 * has none to keep, and the day it does is the day to copy that half too.
 */
internal fun humanizeActionName(id: String): String = buildString {
    for (character in id) {
        when {
            character == ':' -> if (endsWith(':')) append(' ') else append(':')
            character == '_' -> append(' ')
            character.isUpperCase() -> {
                if (isNotEmpty() && !endsWith(' ')) append(' ')
                append(character.lowercaseChar())
            }
            else -> append(character)
        }
    }
}

/**
 * Match [query] against [entries], best first.
 *
 * [recent] is the ids of recently run commands, newest first: they lead an
 * empty palette and break ties in a full one, which is what makes the second
 * use of a command faster than the first. Unavailable commands are ranked with
 * the rest rather than sunk — Zed greys them where they are, and a command
 * that moves when you cannot run it is harder to learn.
 */
fun matchCommands(
    entries: List<PaletteEntry>,
    query: String,
    recent: List<String> = emptyList(),
): List<CommandMatch> {
    fun rank(entry: PaletteEntry): Int =
        recent.indexOf(entry.command.id).takeIf { it >= 0 } ?: recent.size

    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        return entries
            .sortedWith(compareBy({ rank(it) }, { it.name }))
            .map { CommandMatch(it, emptyList(), 0) }
    }

    // Smart case — a query with an uppercase letter is case-sensitive — is the
    // file finder's convention, and it is right there because paths carry
    // uppercase. Command names do not: `humanizeActionName` lowercases every
    // one, so a candidate can never contain an uppercase letter, and applying
    // the rule here matches *nothing* the moment a soft keyboard capitalises
    // the first letter. It applies only if there is something to match.
    val smartCase = trimmed.any { it.isUpperCase() } &&
        entries.any { entry -> entry.name.any(Char::isUpperCase) }
    return entries
        .mapNotNull { entry ->
            fuzzyMatch(entry.name, trimmed, smartCase)?.let { found ->
                CommandMatch(entry, found.positions, found.score)
            }
        }
        .sortedWith(compareByDescending<CommandMatch> { it.score }
            .thenBy { rank(it.entry) }
            .thenBy { it.entry.name })
}

/** What a hit is worth, and which characters to highlight. */
internal class Hit(val score: Int, val positions: List<Int>)

private const val UNREACHABLE = Int.MIN_VALUE / 2

/** A character that matched at all. */
private const val MATCH_SCORE = 1

/** …at the start of a word, which is what people type first. */
private const val WORD_START_SCORE = 6

/** …immediately after the previous one: "term" beats t…e…r…m. */
private const val RUN_SCORE = 4

/** The most a single gap can cost, so one long skip doesn't sink a good hit. */
private const val MAX_GAP_PENALTY = 3

private const val WORD_SEPARATORS = " :_-./"

/**
 * Fuzzy subsequence match, scored so that word starts and unbroken runs win.
 *
 * This is the palette's own matcher rather than the engine's. The engine's
 * `fuzzy` crate is reachable only through `find_files`, which matches a
 * project's paths and nothing else; matching fourteen short strings in Kotlin
 * is not worth a JNI call, and a `match_strings` entry point is the right way
 * to share the real thing later (noted for the bridge). The branch picker
 * ranks its branch names through this same function, so every in-app list
 * that is not a worktree matches the same way.
 *
 * The search is exhaustive rather than greedy — every way of laying the query
 * over the name, best kept — because greedy matching highlights the wrong
 * characters as soon as a letter repeats, and "toggle" against "terminal
 * panel: toggle" is exactly that case. It costs O(query × name²) over a
 * table this size, which is nothing.
 */
internal fun fuzzyMatch(name: String, query: String, smartCase: Boolean): Hit? {
    if (query.length > name.length) return null
    val rows = query.length
    val columns = name.length
    val score = Array(rows) { IntArray(columns) { UNREACHABLE } }
    val cameFrom = Array(rows) { IntArray(columns) { -1 } }

    for (i in 0 until rows) {
        // The i-th query character cannot land before column i.
        for (j in i until columns) {
            if (!same(query[i], name[j], smartCase)) continue
            val gain = MATCH_SCORE + if (isWordStart(name, j)) WORD_START_SCORE else 0
            if (i == 0) {
                score[0][j] = gain
                continue
            }
            var best = UNREACHABLE
            var bestFrom = -1
            for (k in i - 1 until j) {
                val previous = score[i - 1][k]
                if (previous == UNREACHABLE) continue
                val gap = j - k - 1
                val candidate = previous + gain +
                    if (gap == 0) RUN_SCORE else -minOf(gap, MAX_GAP_PENALTY)
                if (candidate > best) {
                    best = candidate
                    bestFrom = k
                }
            }
            if (bestFrom < 0) continue
            score[i][j] = best
            cameFrom[i][j] = bestFrom
        }
    }

    var end = -1
    var best = UNREACHABLE
    // Ascending with a strict comparison, so equal scores keep the earliest
    // match — the one the reader's eye is already on.
    for (j in rows - 1 until columns) {
        if (score[rows - 1][j] > best) {
            best = score[rows - 1][j]
            end = j
        }
    }
    if (end < 0) return null

    val positions = IntArray(rows)
    var column = end
    for (i in rows - 1 downTo 0) {
        positions[i] = column
        column = cameFrom[i][column]
    }
    return Hit(best, positions.toList())
}

private fun same(query: Char, candidate: Char, smartCase: Boolean): Boolean =
    if (smartCase) query == candidate else query.lowercaseChar() == candidate.lowercaseChar()

private fun isWordStart(name: String, index: Int): Boolean =
    index == 0 || name[index - 1] in WORD_SEPARATORS

/**
 * The commands most recently run, newest first.
 *
 * Zed keeps these in its database and puts them at the top of the palette,
 * which is most of what makes the palette fast to use a second time. Ours is a
 * short list of action ids in app-private preferences: ids rather than enum
 * names because ids are the stable identity, and a list rather than a
 * `StringSet` because the order *is* the data.
 */
object CommandRecency {
    private const val PREFS = "command_palette"
    private const val KEY_RECENT = "recent"

    /** Long enough to cover what one person actually uses in a session. */
    private const val LIMIT = 20

    @Volatile
    private var cached: List<String> = emptyList()

    /** What the last [load] found, for painting the first frame without I/O. */
    fun known(): List<String> = cached

    /** Read the list from disk. Blocking; call it off the main thread. */
    fun load(context: Context): List<String> {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, null)
            ?: return cached
        cached = stored.split(' ').filter { it.isNotEmpty() }
        return cached
    }

    /** Move [command] to the front. Returns the new list. */
    fun record(context: Context, command: WorkspaceCommand): List<String> {
        val updated = (listOf(command.id) + cached.filter { it != command.id }).take(LIMIT)
        cached = updated
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECENT, updated.joinToString(" "))
            .apply()
        return updated
    }
}
