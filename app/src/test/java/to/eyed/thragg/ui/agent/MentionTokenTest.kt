package to.eyed.thragg.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Which `@path` mentions a send actually carries.
 *
 * This was a real leak, not a nicety: the composer used to keep a mention if
 * the message merely *contained* `"@" + path`, so a path that is a prefix of
 * another survived being deleted. Completing `.env` by mistake, backspacing
 * it and completing `.env.example` sent both — and the engine embeds a
 * mentioned file's contents in the prompt, so the file the user had taken
 * back reached the agent anyway. Every real project has such pairs
 * (`Dockerfile`/`Dockerfile.dev`, `index.js`/`index.js.map`).
 */
class MentionTokenTest {

    @Test
    fun aPrefixOfAnotherMentionIsNotCountedAsPresent() {
        val present = mentionTokensIn("look at @.env.example please")
        assertEquals(setOf(".env.example"), present)
        assertFalse(".env" in present)
    }

    @Test
    fun everyStandingMentionIsCounted() {
        assertEquals(
            setOf("src/main.rs", "Cargo.toml"),
            mentionTokensIn("@src/main.rs and @Cargo.toml — compare them"),
        )
    }

    @Test
    fun aMentionAtTheStartAndOneAfterANewlineBothCount() {
        assertEquals(
            setOf("a.txt", "b.txt"),
            mentionTokensIn("@a.txt\nand @b.txt"),
        )
    }

    /** An email address is not a mention: no whitespace boundary before its @. */
    @Test
    fun anEmailAddressIsNotAMention() {
        assertEquals(emptySet<String>(), mentionTokensIn("mail me@example.com about it"))
    }

    @Test
    fun aBareAtIsNotAMention() {
        assertEquals(emptySet<String>(), mentionTokensIn("what does @ do"))
    }
}
