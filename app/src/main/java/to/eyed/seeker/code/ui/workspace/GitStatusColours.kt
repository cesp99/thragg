package to.eyed.seeker.code.ui.workspace

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import to.eyed.seeker.code.ui.theme.ZedTheme
import to.eyed.seeker.code.ui.theme.readable

/**
 * Version-control state of a single project entry, the way the tree paints it.
 *
 * Deliberately small: the panel only needs to pick a colour, so the richer
 * distinctions git makes (staged vs unstaged, index vs worktree, rename
 * sources) collapse into the states Zed's project panel actually shows.
 *
 * [Ignored] exists so a status source *can* report it, but the panel keeps
 * driving ignored-ness from `ProjectEntry.isIgnored` — that is what the
 * `project_panel.gitignored_files` setting (show / dimmed / hide) is wired to,
 * and it is known before any git query has run.
 */
enum class GitFileStatus {
    /** Unchanged, or not known yet. Painted with the normal text colour. */
    None,
    Modified,
    /** Tracked and newly added (staged new file). */
    Added,
    /** Not tracked by git at all. */
    Untracked,
    Deleted,
    /** Tracked and moved. The theme ships a colour for it; git emits R/RM. */
    Renamed,
    Conflicted,
    Ignored,
}

/**
 * An immutable per-path status table.
 *
 * Paths are project-relative, '/'-separated — the same keys
 * [to.eyed.seeker.code.core.ProjectEntry.path] uses.
 *
 * Directories are looked up by their own path: if the engine publishes a
 * rolled-up summary for a directory, it appears here under that path and the
 * panel paints it; if it doesn't, the lookup returns [GitFileStatus.None] and
 * the directory stays plain. The UI never rolls anything up itself — guessing
 * a directory's status from its loaded children would be wrong for the
 * children that are still unscanned.
 */
@Immutable
class GitStatusSnapshot private constructor(
    /** The source's version this table was read at; see [GitStatusSource]. */
    val version: Long,
    private val byPath: Map<String, GitFileStatus>,
) {
    val isEmpty: Boolean get() = byPath.isEmpty()

    fun statusOf(path: String): GitFileStatus = byPath[path] ?: GitFileStatus.None

    companion object {
        val Empty = GitStatusSnapshot(0L, emptyMap())

        fun of(version: Long, byPath: Map<String, GitFileStatus>): GitStatusSnapshot =
            // Copy: the class is @Immutable, which Compose treats as a promise
            // it may skip recomposition on, and callers build these from
            // mutable maps.
            GitStatusSnapshot(version, HashMap(byPath))
    }
}

/**
 * Where per-path git status comes from.
 *
 * Same shape as the worktree itself: a cheap [version] counter the panel
 * polls, and a blocking [snapshot] read it only performs when that counter
 * moved. The panel folds this into the poll loop it already runs, so status
 * costs no extra coroutine and no per-frame work.
 *
 * Until the engine can answer (P4-3 bundles git; see agent-docs TASKS.md
 * P3-8), [Absent] is wired in and the tree looks exactly as it did before.
 */
interface GitStatusSource {
    /** Bumped whenever statuses change. Must be cheap — it is polled. */
    val version: Long

    /**
     * Read the current table. **Blocking** — the panel calls this from
     * [kotlinx.coroutines.Dispatchers.Default], never on the main thread.
     */
    fun snapshot(): GitStatusSnapshot

    /** No git status at all: every entry is [GitFileStatus.None], forever. */
    object Absent : GitStatusSource {
        override val version: Long get() = 0L
        override fun snapshot(): GitStatusSnapshot = GitStatusSnapshot.Empty
    }
}


/**
 * The theme's version-control colours, resolved once per theme.
 *
 * Every value comes from the loaded Zed theme (assets/themes/one.json); none
 * are hardcoded. Zed carries two related families of keys and one.json has
 * both, so each entry prefers the explicit `version_control.*` key and falls
 * back to the older status key of the same meaning:
 *
 * | state      | key                        | fallback  |
 * |------------|----------------------------|-----------|
 * | modified   | `version_control.modified` | `modified`|
 * | added      | `version_control.added`    | `created` |
 * | untracked  | `version_control.added`    | `created` |
 * | deleted    | `version_control.deleted`  | `deleted` |
 * | renamed    | `version_control.renamed`  | `renamed` |
 * | conflicted | `version_control.conflict` | `conflict`|
 * | ignored    | `ignored`                  | `text.disabled` |
 *
 * Notes on what one.json actually ships: it has no
 * `version_control.conflict`, so conflicts land on the `conflict` key (the
 * same amber Zed uses for conflict text elsewhere), and no
 * `version_control.untracked`, so untracked shares the added colour exactly as
 * Zed's own panel does. `ignored` and `text.disabled` are the same colour in
 * both One Dark and One Light, so the existing dimming is untouched.
 *
 * [Colour lookups are map reads, so they happen here — once, in a
 * `remember(theme)` — and never per row or per frame.]
 */
@Immutable
class GitStatusColours(
    val default: Color,
    val modified: Color,
    val added: Color,
    val untracked: Color,
    val deleted: Color,
    val renamed: Color,
    val conflicted: Color,
    val ignored: Color,
) {
    /**
     * Colour for one row. Pure `when` over an enum plus field reads: no
     * allocation, no map lookup, safe to call from the row composable.
     *
     * [isIgnored] comes from the worktree, which knows about .gitignore
     * without any git query; [dimIgnored] is the `project_panel
     * .gitignored_files` setting. A real change wins over ignored-ness, as in
     * Zed — `entry_git_aware_label_color` checks conflict, deleted, modified
     * and created *before* ignored (editor/src/items.rs:2205-2219), so an
     * ignored file that is also modified reads as modified.
     */
    fun colorFor(
        status: GitFileStatus,
        isIgnored: Boolean = false,
        dimIgnored: Boolean = true,
    ): Color = when (status) {
        GitFileStatus.Conflicted -> conflicted
        GitFileStatus.Deleted -> deleted
        GitFileStatus.Modified -> modified
        GitFileStatus.Added -> added
        GitFileStatus.Untracked -> untracked
        GitFileStatus.Renamed -> renamed
        GitFileStatus.None, GitFileStatus.Ignored ->
            if ((isIgnored || status == GitFileStatus.Ignored) && dimIgnored) ignored else default
    }

    /**
     * The same six meanings, made legible on [ground].
     *
     * The project panel is drawn on a Material sheet now, and this is the one
     * table in it that still reads the Zed theme — because these hues MEAN
     * something: the amber a modified file wears here is the amber the diff,
     * the git panel and the buffer's gutter wear, and a tree that answered
     * "changed" in an M3 role would disagree with all three. So the hue is
     * kept and only the lightness moves, by the smallest distance that clears
     * 4.5:1 — which is not optional on a sheet: measured across the bundled
     * themes, Ayu Light's `created` is **2.11:1** on the panel ground and One
     * Light's is 2.64:1 (docs/VISUAL.md, "The hybrid").
     *
     * [default] and [ignored] are left alone deliberately. `default` is
     * already a solved M3 role handed in by the caller, and `ignored` is
     * SUPPOSED to be hard to read — dimming is the whole of what it says, and
     * solving it to 4.5:1 would delete the meaning it carries.
     *
     * Not used by the editor's or the diff's tables ([from]): those are drawn
     * inside `ZedSurface`, where raw is the rule.
     */
    fun solvedOn(ground: Color): GitStatusColours = GitStatusColours(
        default = default,
        modified = readable(modified, ground),
        added = readable(added, ground),
        untracked = readable(untracked, ground),
        deleted = readable(deleted, ground),
        renamed = readable(renamed, ground),
        conflicted = readable(conflicted, ground),
        ignored = ignored,
    )

    companion object {
        /**
         * Resolve against [theme]. [default] is the panel's normal text
         * colour (Material `onSurface`), and the last-resort fallback for a
         * theme missing a key outright; [muted] plays the same role for
         * ignored entries, matching what the panel dimmed them to before git
         * status existed.
         */
        fun from(theme: ZedTheme, default: Color, muted: Color = default): GitStatusColours =
            resolve(theme, default, muted, statusFamilyFirst = false)

        /**
         * The project panel's resolution order. Its rows tint through
         * `Color::Modified` and friends, which map to the **status family**
         * (`modified`, `created`, `deleted`, `conflict` —
         * ui/src/styles/color.rs:94-99) rather than to `version_control.*`,
         * which belongs to the git panel and the branch icon. The two
         * families differ in the shipped themes — One Light's
         * `version_control.*` hexes are not light-adapted — so the order is
         * visible, not academic.
         */
        fun forProjectPanel(theme: ZedTheme, default: Color, muted: Color = default) =
            resolve(theme, default, muted, statusFamilyFirst = true)

        private fun resolve(
            theme: ZedTheme,
            default: Color,
            muted: Color,
            statusFamilyFirst: Boolean,
        ): GitStatusColours {
            fun key(vararg keys: String): Color {
                val order = if (statusFamilyFirst) keys.reversed() else keys.toList()
                for (name in order) lookup(theme, name)?.let { return it }
                return default
            }
            return GitStatusColours(
                default = default,
                modified = key("version_control.modified", "modified"),
                renamed = key("version_control.renamed", "renamed"),
                added = key("version_control.added", "created"),
                untracked = key("version_control.untracked", "version_control.added", "created"),
                deleted = key("version_control.deleted", "deleted"),
                conflicted = key("version_control.conflict", "conflict"),
                ignored = lookup(theme, "ignored")
                    ?: lookup(theme, "text.disabled")
                    ?: muted,
            )
        }

        /**
         * "Does this theme define [name]?", exactly. [ZedTheme.color] takes a
         * fallback instead of returning null and no colour value is
         * impossible, so probe twice with different sentinels: only a missing
         * key can hand back both of them.
         */
        private fun lookup(theme: ZedTheme, name: String): Color? {
            val first = theme.color(name, PROBE_A)
            if (first == PROBE_A && theme.color(name, PROBE_B) == PROBE_B) return null
            return first
        }

        private val PROBE_A = Color(0x01020304)
        private val PROBE_B = Color(0x04030201)
    }
}
