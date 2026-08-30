package to.eyed.seeker.code.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The avatar cache's prune policy — the pure half of keeping `git-avatars/`
 * from growing one file per author forever. Oldest go first (mtime as LRU),
 * orphaned `.part` files always go, and a cache within budget is left alone.
 */
class GitAvatarsTest {

    private fun file(name: String, bytes: Long, modified: Long) =
        AvatarCacheFile(name, bytes, modified)

    @Test
    fun aCacheWithinBudgetIsLeftAlone() {
        val plan = avatarPrunePlan(
            listOf(file("aa", 100, 1), file("bb", 100, 2)),
            budget = 1_000,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun theOldestGoFirstUntilTheRestFits() {
        val plan = avatarPrunePlan(
            listOf(
                file("newest", 100, 30),
                file("oldest", 100, 10),
                file("middle", 100, 20),
            ),
            budget = 150,
        )
        // Dropping the two oldest brings 300 down to 100 ≤ 150; the newest
        // survives.
        assertEquals(listOf("oldest", "middle"), plan)
    }

    /** A `.part` left behind is a download the process died under — a
     * finished one is renamed away — so it goes even under budget. */
    @Test
    fun orphanedPartFilesAlwaysGo() {
        val plan = avatarPrunePlan(
            listOf(file("aa", 100, 1), file("fetch123.part", 5, 2)),
            budget = 1_000,
        )
        assertEquals(listOf("fetch123.part"), plan)
    }

    @Test
    fun anEmptyDirectoryPlansNothing() {
        assertTrue(avatarPrunePlan(emptyList(), budget = 1).isEmpty())
    }
}
