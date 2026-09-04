package to.eyed.thragg.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing remote URLs and building the two URLs the sidebar hangs off them.
 *
 * These are the strings git actually writes into `.git/config`, and each
 * spelling has broken somebody's "open on web" button somewhere: the scp
 * colon, the `.git` suffix, the user in the URL, the host that merely
 * *contains* github.com.
 */
class GitHostingTest {

    // What Zed's `parse_remote_url` accepts (github.rs:192-212).
    @Test
    fun theThreeGitSpellingsAllParse() {
        assertEquals("cesp99/seeker", githubRepoSlug("https://github.com/cesp99/seeker.git"))
        assertEquals("cesp99/seeker", githubRepoSlug("git@github.com:cesp99/seeker.git"))
        assertEquals("cesp99/seeker", githubRepoSlug("ssh://git@github.com/cesp99/seeker.git"))
    }

    @Test
    fun theSuffixAndSlashShrugOff() {
        assertEquals("a/b", githubRepoSlug("https://github.com/a/b"))
        assertEquals("a/b", githubRepoSlug("https://github.com/a/b/"))
        assertEquals("a/b.wiki", githubRepoSlug("https://github.com/a/b.wiki.git"))
        assertEquals("a/b", githubRepoSlug("https://user@github.com/a/b.git"))
    }

    /** `notgithub.com` and `github.com.evil.example` are both somebody else. */
    @Test
    fun otherHostsAreNotGithub() {
        assertNull(githubRepoSlug("https://gitlab.com/a/b.git"))
        assertNull(githubRepoSlug("git@gitlab.com:a/b.git"))
        assertNull(githubRepoSlug("https://notgithub.com/a/b"))
        assertNull(githubRepoSlug("https://github.com.evil.example/a/b"))
        assertNull(githubRepoSlug("https://github.example.com/a/b"))
    }

    /**
     * An '@' buried in the *path* must not move the host: RFC 3986 userinfo
     * cannot contain '/', and Zed's `Url::parse` reads `git.evil.example`
     * here (remote.rs:19-31) — while a real user, even with a port beside
     * it, still parses.
     */
    @Test
    fun anAtSignInThePathDoesNotMoveTheHost() {
        assertNull(githubRepoSlug("https://git.evil.example/mirror/x@github.com/fakeowner/fakerepo"))
        assertNull(githubRepoSlug("git@evil.example:github.com/a"))
        assertEquals("a/b", githubRepoSlug("ssh://git@github.com:22/a/b.git"))
    }

    /** Owner and repo are exactly two segments — no more, no fewer. */
    @Test
    fun theSlugIsExactlyTwoSegments()  {
        assertNull(githubRepoSlug("https://github.com/onlyowner"))
        assertNull(githubRepoSlug("https://github.com/a/b/c"))
        assertNull(githubRepoSlug("https://github.com/"))
        assertNull(githubRepoSlug(""))
    }

    /** `{base}/{owner}/{repo}/commit/{sha}` (github.rs:214-225). */
    @Test
    fun theCommitUrlIsThePermalink() {
        assertEquals(
            "https://github.com/cesp99/seeker/commit/abc123def",
            githubCommitUrl("git@github.com:cesp99/seeker.git", "abc123def"),
        )
        assertNull(githubCommitUrl("https://gitlab.com/a/b.git", "abc"))
    }

    /** The email CDN endpoint, encoded (github.rs:75-82). */
    @Test
    fun theAvatarUrlAsksTheEmailCdn() {
        assertEquals(
            "https://avatars.githubusercontent.com/u/e?email=carlo%40example.com&s=128",
            githubAvatarUrl("carlo@example.com"),
        )
        // git sometimes leaves the angle brackets on.
        assertEquals(
            "https://avatars.githubusercontent.com/u/e?email=carlo%40example.com&s=128",
            githubAvatarUrl("<carlo@example.com>"),
        )
        // The noreply spelling resolves through the same endpoint.
        assertEquals(
            "https://avatars.githubusercontent.com/u/e?email=123%2Bcesp99%40users.noreply.github.com&s=128",
            githubAvatarUrl("123+cesp99@users.noreply.github.com"),
        )
    }

    /** Bots skip the CDN (github.rs:84-91), and nobody has no avatar. */
    @Test
    fun botsAndBlanksHaveNoAvatarUrl() {
        assertNull(githubAvatarUrl("dependabot[bot]@users.noreply.github.com"))
        assertNull(githubAvatarUrl(""))
        assertNull(githubAvatarUrl("   "))
    }

    /** One author, one cache file — however their mail is dressed. */
    @Test
    fun theCacheKeyNormalizesTheIdentity() {
        val plain = avatarCacheKey("carlo@example.com")
        assertEquals(plain, avatarCacheKey("Carlo@Example.COM"))
        assertEquals(plain, avatarCacheKey("  <carlo@example.com>  "))
        assertNotEquals(plain, avatarCacheKey("other@example.com"))
        // A filename, not an email: hex only, fixed length, nothing to escape.
        assertEquals(40, plain.length)
        assert(plain.all { it in "0123456789abcdef" })
    }
}
