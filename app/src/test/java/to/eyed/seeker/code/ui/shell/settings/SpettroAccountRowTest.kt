package to.eyed.seeker.code.ui.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Settings row for the Spettro account, whose label is the state itself:
 * the email when signed in, the verb when not. The choosers are pure exactly
 * so this test exists — the row must never print a blank label, and a plan
 * string surviving a sign-out must never resurface as a description.
 */
class SpettroAccountRowTest {

    @Test
    fun `signed out is the verb, whatever the cache still holds`() {
        assertEquals("Sign in to Spettro", spettroAccountLabel(signedIn = false, email = null))
        // A stale email from before a sign-out is not this row's to show.
        assertEquals(
            "Sign in to Spettro",
            spettroAccountLabel(signedIn = false, email = "dev@example.com"),
        )
    }

    @Test
    fun `signed in is the email`() {
        assertEquals(
            "dev@example.com",
            spettroAccountLabel(signedIn = true, email = "dev@example.com"),
        )
    }

    @Test
    fun `signed in with no email yet still says so`() {
        // The backend omits the email while the profile is still syncing; the
        // row must not be blank for that window.
        assertEquals("Signed in", spettroAccountLabel(signedIn = true, email = null))
    }

    @Test
    fun `the plan is the description, and only while signed in`() {
        assertEquals("Pro", spettroAccountDescription(signedIn = true, plan = "Pro"))
        assertNull(spettroAccountDescription(signedIn = true, plan = null))
        // A leftover plan from before a sign-out is not a fact about this row.
        assertNull(spettroAccountDescription(signedIn = false, plan = "Pro"))
    }
}
