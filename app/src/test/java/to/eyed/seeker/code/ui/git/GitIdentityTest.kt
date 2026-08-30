package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising the one wall every fresh userland hits.
 *
 * git says this three different ways depending on its version and on how much
 * of an identity it found, and all three mean "tell me who you are". Matching
 * only the first phrasing would leave the other two showing a bare error with
 * no way out of it — which is exactly what the panel did.
 */
class GitIdentityTest {

    @Test
    fun everyWayGitAsksWhoYouAreIsRecognised() {
        assertTrue(
            needsIdentity(
                "Committer identity unknown\n*** Please tell me who you are.\n" +
                    "Run\n  git config --global user.email \"you@example.com\""
            )
        )
        assertTrue(needsIdentity("unable to auto-detect email address (got 'root@localhost.(none)')"))
        assertTrue(needsIdentity("fatal: empty ident name (for <root@localhost>) not allowed"))
        assertTrue(needsIdentity("fatal: no name was given and auto-detection is disabled"))
    }

    /** Every other failure keeps the plain error strip. */
    @Test
    fun otherFailuresAreNotAnIdentityProblem() {
        assertFalse(needsIdentity("nothing to commit, working tree clean"))
        assertFalse(needsIdentity("error: pathspec 'x' did not match any file(s) known to git"))
        assertFalse(needsIdentity(""))
    }
}
