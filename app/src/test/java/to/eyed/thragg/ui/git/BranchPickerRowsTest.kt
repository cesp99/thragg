package to.eyed.thragg.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.thragg.core.GitBranchEntry

/**
 * The picker's list against Zed's rules: the collapse of tracked remotes, the
 * head-then-recency order, locals stably ahead of remotes, the create entry
 * the query grows, and the section headers over the groups
 * (branch_picker.rs:944-977, 1436-1506, 1941-1973).
 */
class BranchPickerRowsTest {

    private fun branch(
        name: String,
        isRemote: Boolean = false,
        isHead: Boolean = false,
        date: Long = 0,
        upstream: String? = null,
        sha: String = "abc123",
    ) = GitBranchEntry(
        name = name,
        isRemote = isRemote,
        isHead = isHead,
        sha = sha,
        subject = "subject of $name",
        committerDate = date,
        author = "Carlo",
        hasParent = true,
        upstream = upstream,
    )

    private fun names(rows: List<BranchPickerRow>): List<String> = rows.map {
        when (it) {
            is BranchPickerRow.Branch -> it.entry.name
            is BranchPickerRow.Create -> "create:${it.name}"
        }
    }

    @Test
    fun headFirstThenMostRecentCommitFirst() {
        val rows = branchPickerRows(
            listOf(
                branch("old", date = 10),
                branch("main", isHead = true, date = 5),
                branch("fresh", date = 90),
            ),
            query = "",
        )
        assertEquals(listOf("main", "fresh", "old"), names(rows))
    }

    @Test
    fun trackedRemotesAreCollapsedAndLocalsLeadRemotes() {
        val rows = branchPickerRows(
            listOf(
                // `origin/main` is main's upstream, so it must not be listed
                // twice (branch_picker.rs:944-961).
                branch("origin/main", isRemote = true, date = 80),
                branch("origin/feature", isRemote = true, date = 99),
                branch("main", isHead = true, date = 80, upstream = "origin/main"),
                branch("slow", date = 1),
            ),
            query = "",
        )
        // Locals first — stably, keeping the recency order — then the one
        // remote nothing tracks (sort_branch_entries, branch_picker.rs:915-939).
        assertEquals(listOf("main", "slow", "origin/feature"), names(rows))
    }

    @Test
    fun queryFiltersFuzzilyAndAppendsTheCreateEntry() {
        val rows = branchPickerRows(
            listOf(
                branch("main", isHead = true),
                branch("feature/login", date = 5),
                branch("fix/typo", date = 9),
            ),
            query = "feat",
        )
        // Only the match survives, and the query becomes a create entry after
        // it (branch_picker.rs:1484-1506).
        assertEquals(listOf("feature/login", "create:feat"), names(rows))
        // The matched characters are the ones a highlight would paint.
        val match = rows.first() as BranchPickerRow.Branch
        assertEquals(listOf(0, 1, 2, 3), match.positions)
    }

    @Test
    fun anExactNameMatchOffersNoCreateEntry() {
        val rows = branchPickerRows(
            listOf(branch("main", isHead = true), branch("dev")),
            query = "dev",
        )
        assertEquals(listOf("dev"), names(rows))
    }

    @Test
    fun exactMatchIsCheckedAgainstCollapsedRowsToo() {
        // `origin/main` is collapsed out of the list, but typing its name
        // exactly must not offer to create a branch called origin/main —
        // Zed checks `all_branches` (branch_picker.rs:1486-1488).
        val rows = branchPickerRows(
            listOf(
                branch("main", isHead = true, upstream = "origin/main"),
                branch("origin/main", isRemote = true),
            ),
            query = "origin/main",
        )
        assertTrue(rows.none { it is BranchPickerRow.Create })
    }

    @Test
    fun createEntryNormalizesSpacesToDashes() {
        val rows = branchPickerRows(listOf(branch("main", isHead = true)), query = "my branch ")
        assertEquals(listOf("create:my-branch"), names(rows))
        assertEquals("my-branch", normalizeBranchName(" my branch "))
    }

    @Test
    fun allWhitespaceQueryOffersNothingToCreate() {
        val rows = branchPickerRows(listOf(branch("main", isHead = true)), query = "   ")
        assertTrue(rows.none { it is BranchPickerRow.Create })
    }

    @Test
    fun smartCaseMatchesLikeZeds() {
        val branches = listOf(branch("Feature", isHead = true), branch("feature-two"))
        // Lowercase query: case-insensitive, both match.
        assertEquals(
            listOf("Feature", "feature-two", "create:feat"),
            names(branchPickerRows(branches, query = "feat")),
        )
        // An uppercase letter makes it exact about case.
        assertEquals(
            listOf("Feature", "create:Feat"),
            names(branchPickerRows(branches, query = "Feat")),
        )
    }

    @Test
    fun filtersNarrowToOneSide() {
        val branches = listOf(
            branch("main", isHead = true),
            branch("origin/feature", isRemote = true),
        )
        assertEquals(
            listOf("main"),
            names(branchPickerRows(branches, query = "", filter = BranchFilter.Local)),
        )
        assertEquals(
            listOf("origin/feature"),
            names(branchPickerRows(branches, query = "", filter = BranchFilter.Remote)),
        )
    }

    @Test
    fun filterCyclesAllLocalRemote() {
        assertEquals(BranchFilter.Local, BranchFilter.All.next())
        assertEquals(BranchFilter.Remote, BranchFilter.Local.next())
        assertEquals(BranchFilter.All, BranchFilter.Remote.next())
    }

    @Test
    fun sectionHeadersMarkTheTwoGroups() {
        val rows = branchPickerRows(
            listOf(
                branch("main", isHead = true),
                branch("dev", date = 3),
                branch("origin/feature", isRemote = true),
            ),
            query = "",
        )
        // "Local Branches" over the first row, no divider; "Remote Branches"
        // over the group change, with one (branch_picker.rs:1941-1957).
        assertEquals("Local Branches" to false, branchSectionHeader(rows, 0, BranchFilter.All))
        assertNull(branchSectionHeader(rows, 1, BranchFilter.All))
        assertEquals("Remote Branches" to true, branchSectionHeader(rows, 2, BranchFilter.All))
        // A narrowed filter draws no per-group headers at all.
        assertNull(branchSectionHeader(rows, 0, BranchFilter.Local))
    }

    @Test
    fun narrowedFiltersGetOneListHeader() {
        val branches = listOf(branch("main", isHead = true))
        val locals = branchPickerRows(branches, query = "", filter = BranchFilter.Local)
        assertEquals("Local Branches", branchListHeader(locals, BranchFilter.Local))
        assertNull(branchListHeader(locals, BranchFilter.All))
        // Only a create entry left: the header is skipped
        // (branch_picker.rs:1354-1375).
        val createOnly = branchPickerRows(branches, query = "zzz", filter = BranchFilter.Local)
        assertNull(branchListHeader(createOnly, BranchFilter.Local))
    }

    @Test
    fun rowKeysAreDistinctAcrossSides() {
        // A local branch and a gone-upstream remote row can share a name;
        // their LazyColumn keys must not collide.
        val rows = branchPickerRows(
            listOf(branch("main", isHead = true), branch("main", isRemote = true)),
            query = "",
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
