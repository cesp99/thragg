package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ctrl-g resolver's table and its edges. The table is Zed's
 * (default-linux.json:1062-1067); the edges are the ones a chord machine gets
 * wrong quietly — a Shift on its way to `shift-up` resolving the chord, or an
 * unmatched second key falling through to whatever it means on its own.
 */
class GitChordsTest {

    private fun match(command: GitPanelCommand) = GitChordStep.Match(command)

    @Test
    fun theSecondKeysResolveToZedsTable() {
        // ctrl-g ctrl-g → Fetch; ctrl-g up/down → Push/Pull; ctrl-g d → Diff.
        assertEquals(match(GitPanelCommand.Fetch), gitChordStep(GitChordKey.G, shift = false))
        assertEquals(match(GitPanelCommand.Push), gitChordStep(GitChordKey.Up, shift = false))
        assertEquals(match(GitPanelCommand.Pull), gitChordStep(GitChordKey.Down, shift = false))
        assertEquals(match(GitPanelCommand.Diff), gitChordStep(GitChordKey.D, shift = false))
    }

    @Test
    fun shiftIsWhatSeparatesTheForcefulHalves() {
        assertEquals(match(GitPanelCommand.ForcePush), gitChordStep(GitChordKey.Up, shift = true))
        assertEquals(
            match(GitPanelCommand.PullRebase),
            gitChordStep(GitChordKey.Down, shift = true),
        )
    }

    @Test
    fun shiftedLettersMatchNothingRatherThanTheirUnshiftedMeaning() {
        // Zed binds `ctrl-g d`, not `ctrl-g shift-d`; a shifted letter is a
        // different keystroke and must abort like any other unbound one.
        assertEquals(GitChordStep.Abort, gitChordStep(GitChordKey.G, shift = true))
        assertEquals(GitChordStep.Abort, gitChordStep(GitChordKey.D, shift = true))
    }

    @Test
    fun escapeAndUnboundKeysAbortAndAreConsumed() {
        // Abort *is* consumption: the second keystroke of a failed chord must
        // not fall through to Escape's dismiss or a letter's own meaning.
        assertEquals(GitChordStep.Abort, gitChordStep(GitChordKey.Escape, shift = false))
        assertEquals(GitChordStep.Abort, gitChordStep(GitChordKey.Other, shift = false))
        assertEquals(GitChordStep.Abort, gitChordStep(GitChordKey.Other, shift = true))
    }

    @Test
    fun theLeaderNeverArmsFromInsideTheCommitBox() {
        // With the caret in the message box the next keystroke is typing;
        // an armed leader there turned a typed 'g' into a network fetch.
        assertEquals(
            false,
            armsGitChord(GitChordKey.G, shift = false, alt = false, messageFocused = true),
        )
        assertEquals(
            true,
            armsGitChord(GitChordKey.G, shift = false, alt = false, messageFocused = false),
        )
    }

    @Test
    fun onlyBareCtrlGArmsTheLeader() {
        // Shift is a different keystroke, and AltGr arrives as Ctrl+Alt on
        // European layouts — typing a character must not arm a chord.
        assertEquals(
            false,
            armsGitChord(GitChordKey.G, shift = true, alt = false, messageFocused = false),
        )
        assertEquals(
            false,
            armsGitChord(GitChordKey.G, shift = false, alt = true, messageFocused = false),
        )
        assertEquals(
            false,
            armsGitChord(GitChordKey.D, shift = false, alt = false, messageFocused = false),
        )
    }

    @Test
    fun bareModifiersKeepTheChordPending() {
        // Shift going down on its way to `ctrl-g shift-up` is not the second
        // key — with or without the Shift bit, which the left Shift's own
        // down event already carries on some keyboards.
        assertEquals(GitChordStep.StillPending, gitChordStep(GitChordKey.Modifier, shift = false))
        assertEquals(GitChordStep.StillPending, gitChordStep(GitChordKey.Modifier, shift = true))
    }
}
