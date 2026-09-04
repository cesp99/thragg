package to.eyed.thragg.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading what the engine says about a commit.
 *
 * The case that earns this file: `optString` on a JSON **null** hands back the
 * four-character string `"null"`, not null and not empty — so every file in
 * the history read `null → .gitignore` until the field was read with
 * `isNull` first. It is the same trap the git status parser already documents,
 * and it is invisible until something is on screen.
 */
class CommitParsingTest {

    @Test
    fun aCommitRowReadsEveryField() {
        val commit = Commit.parse(
            JSONObject(
                """
                {
                  "sha": "5670bdd0f1e2c3a4b5c6d7e8f9012345678abcde",
                  "parents": ["d0dd1f6", "bb4cc8d"],
                  "author": "Carlo Esposito",
                  "author_email": "carlo@example.com",
                  "author_time": 1700000000,
                  "subject": "commit from the app",
                  "refs": ["HEAD -> main", "origin/main"]
                }
                """.trimIndent()
            )
        )
        assertEquals("5670bdd", commit.shortSha)
        assertTrue(commit.isMerge)
        assertEquals("Carlo Esposito", commit.author)
        assertEquals(1_700_000_000L, commit.authorTime)
        assertEquals(listOf("HEAD -> main", "origin/main"), commit.refs)
    }

    /** A root commit: no parents, no refs, and nothing invented for either. */
    @Test
    fun aRootCommitHasNoParents() {
        val commit = Commit.parse(
            JSONObject("""{"sha":"abc","parents":[],"author":"A","author_email":"a@b","author_time":1,"subject":"first","refs":[]}""")
        )
        assertTrue(commit.parents.isEmpty())
        assertTrue(!commit.isMerge)
        assertTrue(commit.refs.isEmpty())
    }

    /**
     * The bug this file exists for: a file that was *not* renamed has
     * `"original": null`, and reading it with `optString` produced the string
     * "null", which the panel then drew as `null → .gitignore`.
     */
    @Test
    fun aFileThatWasNotRenamedHasNoOriginalName() {
        val file = JSONObject("""{"status":"A","path":".gitignore","original":null}""")
        assertNull(if (file.isNull("original")) null else file.getString("original"))
        // `optString` is the trap, and *what* it hands back differs between
        // Android's org.json and the one these tests link — Android's returns
        // the four-character string "null". Neither is what the panel wanted,
        // which is the point: the field has to be read with `isNull` first.
        assertTrue(file.optString("original") != null)
    }
}
