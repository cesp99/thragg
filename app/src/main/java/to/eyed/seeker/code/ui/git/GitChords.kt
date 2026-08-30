package to.eyed.seeker.code.ui.git

import androidx.compose.ui.input.key.Key

/**
 * The ctrl-g leader chords — Zed's two-step git bindings, scoped to the
 * `"GitPanel"` context (assets/keymaps/default-linux.json:1060-1069) and to
 * this panel here, for the same reason: in the editor plain `ctrl-g` is
 * go-to-line (:622), and in a terminal it is BEL, so the leader can only ever
 * live where the git panel has the keyboard.
 *
 * The mechanics mirror Zed's resolver: `ctrl-g` arms a pending chord, the next
 * non-modifier keystroke completes it or aborts it, and it is consumed either
 * way — an unmatched second key does nothing rather than falling through to
 * whatever it means on its own. The one deliberate difference is the timeout:
 * Zed holds a pending key forever, but Zed also echoes it in its status bar,
 * and an indefinite mode behind one small chip is a trap for a grazed ctrl-g
 * on a touch screen — see [GIT_CHORD_TIMEOUT_MS].
 *
 * The state itself — when the leader was pressed — lives in the panel as
 * composition state, because the pending chip is drawn from it; what lives
 * here is everything decidable without a clock or a screen.
 */

/**
 * A command the panel can be asked to run from a keyboard or from the
 * palette — the second keys of the ctrl-g chords, plus the two bulk stages,
 * which are single chords (`ctrl-space` / `ctrl-shift-space`,
 * default-linux.json:1070-1071). One vocabulary for both routes, so the
 * chords and the palette cannot drift apart.
 */
enum class GitPanelCommand {
    Fetch, Push, Pull, ForcePush, PullRebase, Diff, StageAll, UnstageAll,

    /**
     * The stash family — Zed's `git::StashAll` / `StashTracked` /
     * `StashStaged` (a message is asked for first), `StashPop` and
     * `StashApply` of the newest entry (git_panel.rs:2897-2990). No chord;
     * the palette and the panel's **Stash** menu are the routes.
     */
    StashAll, StashTracked, StashStaged, StashPop, StashApply,
}

/**
 * A palette-run command on its way to the panel. The workspace cannot run
 * `git::Push` itself — the panel owns the session, the single-flight busy
 * flag and the strip that says what git answered — so the ask travels as a
 * value, the way [to.eyed.seeker.code.ui.git.GitPanel]'s `focusToken` does.
 * [project] is the project it was asked *for*: the request waits out the
 * panel's first scan, and the workspace can switch projects under it in that
 * window. [token] makes the same command asked twice two distinct values.
 */
data class GitPanelRequest(val command: GitPanelCommand, val project: Long, val token: Int)

/** What the panel's request effect does with a pending [GitPanelRequest]. */
internal enum class PanelRequestStep { Run, Drop, Wait }

/**
 * The request effect's decision, out where it can be tested. A request
 * stamped for another project is [PanelRequestStep.Drop] — answered so it
 * cannot linger, but never run against a repository it was not asked about.
 * One for this project waits for the first `git status` ([scanned]), because
 * a push before the branch is known would silently do nothing; then it runs.
 */
internal fun panelRequestStep(
    request: GitPanelRequest,
    project: Long,
    scanned: Boolean,
): PanelRequestStep = when {
    request.project != project -> PanelRequestStep.Drop
    !scanned -> PanelRequestStep.Wait
    else -> PanelRequestStep.Run
}

/**
 * How long an armed ctrl-g waits for its second key. Zed has no timeout at
 * all; a few seconds is long enough to think and short enough that arrow keys
 * grazed into chord mode come back before they are missed.
 */
const val GIT_CHORD_TIMEOUT_MS = 4_000L

/**
 * Whether a ctrl keystroke arms the leader: bare `ctrl-g` — no Shift, and no
 * Alt, because AltGr arrives as Ctrl+Alt on European layouts and typing a
 * character must not arm a chord — and never while the commit editor holds
 * the caret. An armed leader owns the next keystroke whole, so arming it over
 * the message box turned the next typed 'g' into a network fetch and ate
 * every other letter; Zed gets away with arming there because its resolver
 * *replays* unmatched keystrokes into the editor as text
 * (key_dispatch.rs:537-561), which Compose has no equivalent of.
 */
internal fun armsGitChord(
    key: GitChordKey,
    shift: Boolean,
    alt: Boolean,
    messageFocused: Boolean,
): Boolean = key == GitChordKey.G && !shift && !alt && !messageFocused

/**
 * The second keystroke, reduced to the vocabulary the chord table cares
 * about. [Modifier] is every modifier going down on its own — Shift on its
 * way to `ctrl-g shift-up` must not resolve the chord, or the shifted half of
 * the table could never be typed.
 */
internal enum class GitChordKey { G, D, Up, Down, Escape, Modifier, Other }

/** What one keystroke does to a pending ctrl-g leader. */
internal sealed interface GitChordStep {
    /** The chord completed: run [command]. The keystroke is consumed. */
    data class Match(val command: GitPanelCommand) : GitChordStep

    /** A bare modifier: the chord stays pending and the keystroke passes. */
    data object StillPending : GitChordStep

    /**
     * Anything unbound — Escape included: the chord is dropped and the
     * keystroke is consumed, which is how Zed treats a second key that
     * matches no sequence.
     */
    data object Abort : GitChordStep
}

/**
 * Zed's table, second keys only (default-linux.json:1062-1067). Ctrl on the
 * second keystroke is deliberately not consulted: the leader is typed with
 * Ctrl held and fingers do not lift in time, and Zed itself spells the fetch
 * chord `ctrl-g ctrl-g`. Shift is consulted, because it is what separates
 * Push from Force Push and Pull from Pull Rebase — and on the letters a
 * shifted key matches nothing rather than its unshifted meaning.
 */
internal fun gitChordStep(key: GitChordKey, shift: Boolean): GitChordStep = when {
    key == GitChordKey.Modifier -> GitChordStep.StillPending
    key == GitChordKey.G && !shift -> GitChordStep.Match(GitPanelCommand.Fetch)
    key == GitChordKey.Up && !shift -> GitChordStep.Match(GitPanelCommand.Push)
    key == GitChordKey.Up -> GitChordStep.Match(GitPanelCommand.ForcePush)
    key == GitChordKey.Down && !shift -> GitChordStep.Match(GitPanelCommand.Pull)
    key == GitChordKey.Down -> GitChordStep.Match(GitPanelCommand.PullRebase)
    key == GitChordKey.D && !shift -> GitChordStep.Match(GitPanelCommand.Diff)
    else -> GitChordStep.Abort
}

/** A Compose key event's key, in the chord table's vocabulary. */
internal fun gitChordKeyOf(key: Key): GitChordKey = when (key) {
    Key.G -> GitChordKey.G
    Key.D -> GitChordKey.D
    Key.DirectionUp -> GitChordKey.Up
    Key.DirectionDown -> GitChordKey.Down
    Key.Escape -> GitChordKey.Escape
    Key.ShiftLeft, Key.ShiftRight,
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.MetaLeft, Key.MetaRight,
    -> GitChordKey.Modifier
    else -> GitChordKey.Other
}
