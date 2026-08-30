package to.eyed.seeker.code.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's branch JSON and this parser have to agree, and nothing at
 * build time makes them: the contract is a string. These are the shapes
 * `engine::BranchList` actually serializes — including the null-not-absent
 * fields, and the unborn branch whose commit fields are empty rather than
 * missing.
 */
class GitBranchesTest {

    @Test
    fun readsAFullListing() {
        val list = GitBranchList.parse(
            """
            {"branches":[
               {"name":"main","is_remote":false,"is_head":true,
                "sha":"abc123","subject":"Fix the thing","committer_date":1700000000,
                "author":"Carlo Esposito","has_parent":true,
                "upstream":"origin/main","ahead":2,"behind":1,"upstream_gone":false},
               {"name":"origin/main","is_remote":true,"is_head":false,
                "sha":"abc123","subject":"Fix the thing","committer_date":1700000000,
                "author":"Carlo Esposito","has_parent":true,
                "upstream":null,"ahead":0,"behind":0,"upstream_gone":false}],
             "error":null}
            """
        )
        assertNull(list.error)
        assertEquals(2, list.branches.size)

        val head = list.branches[0]
        assertTrue(head.isHead)
        assertFalse(head.isRemote)
        assertNull(head.remote)
        assertEquals("origin/main", head.upstream)
        assertEquals(2, head.ahead)
        assertEquals(1, head.behind)
        assertEquals(1_700_000_000L, head.committerDate)
        assertTrue(head.hasParent)
        assertTrue(head.hasCommit)

        // The remote row's name keeps its remote prefix — the collapse rule
        // compares it against locals' upstreams, and checking it out hands
        // this very spelling back.
        val remote = list.branches[1]
        assertTrue(remote.isRemote)
        assertEquals("origin/main", remote.name)
        assertEquals("origin", remote.remote)
        assertNull(remote.upstream)
    }

    /**
     * A repository just initialized: one synthesized branch with no commit.
     * The row exists — the picker still names the branch — but has nothing
     * to describe, which is what "No commits found" is for.
     */
    @Test
    fun anUnbornBranchHasNoCommit() {
        val list = GitBranchList.parse(
            """{"branches":[
                 {"name":"main","is_remote":false,"is_head":true,
                  "sha":"","subject":"","committer_date":0,"author":"",
                  "has_parent":false,"upstream":null,"ahead":0,"behind":0,
                  "upstream_gone":false}],"error":null}"""
        )
        val branch = list.branches.single()
        assertTrue(branch.isHead)
        assertFalse(branch.hasCommit)
        assertFalse(branch.hasParent)
    }

    /** A gone upstream — deleted on the remote — is a fact, not a drift. */
    @Test
    fun aGoneUpstreamIsKeptApartFromDrift() {
        val list = GitBranchList.parse(
            """{"branches":[
                 {"name":"old","is_remote":false,"is_head":false,
                  "sha":"def","subject":"s","committer_date":1,"author":"a",
                  "has_parent":true,"upstream":"origin/old","ahead":0,"behind":0,
                  "upstream_gone":true}],"error":null}"""
        )
        assertTrue(list.branches.single().upstreamGone)
        assertEquals(0, list.branches.single().ahead)
    }

    /**
     * A partial listing keeps its rows and carries the complaint — the
     * picker's banner — rather than choosing between them.
     */
    @Test
    fun aPartialListingKeepsBothTheRowsAndTheError() {
        val list = GitBranchList.parse(
            """{"branches":[
                 {"name":"main","is_remote":false,"is_head":true,
                  "sha":"abc","subject":"s","committer_date":1,"author":"a",
                  "has_parent":false,"upstream":null,"ahead":0,"behind":0,
                  "upstream_gone":false}],
                "error":"fatal: bad ref"}"""
        )
        assertEquals(1, list.branches.size)
        assertEquals("fatal: bad ref", list.error)

        // And the no-repository shape: an error alone.
        val refused = GitBranchList.parse("""{"error":"Not a git repository"}""")
        assertTrue(refused.branches.isEmpty())
        assertEquals("Not a git repository", refused.error)
    }

    @Test
    fun pushedRemotesParseAndAnErrorIsAnEmptyList() {
        assertEquals(
            listOf("origin/main", "fork/main"),
            GitSession.parsePushedRemotes("""{"remotes":["origin/main","fork/main"]}"""),
        )
        assertTrue(GitSession.parsePushedRemotes("""{"remotes":[]}""").isEmpty())
        // Zed proceeds silently when the check cannot run, and so does this.
        assertTrue(GitSession.parsePushedRemotes("""{"error":"no userland"}""").isEmpty())
    }
}
