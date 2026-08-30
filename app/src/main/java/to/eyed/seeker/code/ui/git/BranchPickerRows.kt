package to.eyed.seeker.code.ui.git

import to.eyed.seeker.code.core.GitBranchEntry
import to.eyed.seeker.code.ui.workspace.fuzzyMatch

/**
 * The branch picker's list, computed away from the composable so ordering,
 * collapsing and the create entry are all checkable on the host. This file is
 * Zed's `process_branches` / `update_matches` pair (branch_picker.rs:944-977,
 * 1399-1510), minus the create-remote-from-URL flow this app does not have.
 */

/** One row of the picker: a branch, or the query offered as a new branch. */
sealed interface BranchPickerRow {
    /** Stable identity for the LazyColumn. */
    val key: String

    data class Branch(
        val entry: GitBranchEntry,
        /** Which characters of the name the query matched, for highlighting. */
        val positions: List<Int> = emptyList(),
    ) : BranchPickerRow {
        // A local `main` and a deleted-upstream `main` cannot collide — remote
        // names carry their remote — but the prefix keeps the promise anyway.
        override val key: String get() = (if (entry.isRemote) "remote:" else "local:") + entry.name
    }

    /** Zed's `Entry::NewBranch` — the query, normalized, as a branch to make. */
    data class Create(val name: String) : BranchPickerRow {
        override val key: String get() = "create"
    }
}

/**
 * Which branches the list shows — Zed's `BranchFilter` with its labels and its
 * cycle order (branch_picker.rs:595-620).
 */
enum class BranchFilter(val label: String) {
    All("All Branches"),
    Local("Local Branches"),
    Remote("Remote Branches");

    /** The `CycleBranchFilter` order: All → Local → Remote → All. */
    fun next(): BranchFilter = when (this) {
        All -> Local
        Local -> Remote
        Remote -> All
    }
}

/**
 * Git branch names can't contain whitespace, so spaces become dashes — after a
 * trim, because a name can't start or end with a dash either. Zed's
 * `normalize_branch_name` (branch_picker.rs:899-904), comment included.
 */
fun normalizeBranchName(query: String): String = query.trim().replace(' ', '-')

/**
 * Everything the picker lists for [query] under [filter], in Zed's order:
 *
 * 1. Remote branches some local branch tracks are hidden — checking one out
 *    would detach HEAD for no reason when the local branch is right there
 *    (`process_branches`, branch_picker.rs:944-963).
 * 2. Base order is current branch first, then most recent commit first
 *    (branch_picker.rs:966-976). A non-empty query replaces that with fuzzy
 *    score order, smart-cased (branch_picker.rs:1436-1460).
 * 3. Local branches stably ahead of remote ones — `sort_branch_entries` runs
 *    after both paths, and in checkout mode its key reduces to `is_remote`
 *    (branch_picker.rs:915-939) — which is what makes the two section headers
 *    contiguous.
 * 4. A query no branch is exactly named after is appended as a create entry,
 *    normalized (branch_picker.rs:1484-1506).
 */
fun branchPickerRows(
    branches: List<GitBranchEntry>,
    query: String,
    filter: BranchFilter = BranchFilter.All,
): List<BranchPickerRow> {
    // The upstreams local branches track, in the remote rows' own spelling
    // ("origin/main") — the collapse key.
    val tracked = branches.mapNotNull { it.upstream }.toHashSet()
    val visible = branches
        .filterNot { it.isRemote && it.name in tracked }
        .sortedWith(compareBy({ !it.isHead }, { -it.committerDate }))
        .filter {
            when (filter) {
                BranchFilter.All -> true
                BranchFilter.Local -> !it.isRemote
                BranchFilter.Remote -> it.isRemote
            }
        }

    val matched: List<BranchPickerRow.Branch> = if (query.isEmpty()) {
        visible.map { BranchPickerRow.Branch(it) }
    } else {
        // Smart case, as Zed's `Case::Smart`: an uppercase letter anywhere in
        // the query makes the match case-sensitive.
        val smartCase = query.any { it.isUpperCase() }
        visible
            .mapNotNull { entry ->
                fuzzyMatch(entry.name, query, smartCase)?.let { hit ->
                    Triple(entry, hit.positions, hit.score)
                }
            }
            .sortedByDescending { it.third }
            .map { BranchPickerRow.Branch(it.first, it.second) }
    }

    val rows: MutableList<BranchPickerRow> =
        matched.sortedBy { it.entry.isRemote }.toMutableList()

    // The create entry: the raw query against every branch name, collapsed
    // rows included, exactly as Zed checks it (branch_picker.rs:1486-1488) —
    // the normalization is spent on the name, not on the comparison. A query
    // that normalizes to nothing (all spaces) has no branch in it to offer.
    if (query.isNotEmpty() && branches.none { it.name == query }) {
        val name = normalizeBranchName(query)
        if (name.isNotEmpty()) rows += BranchPickerRow.Create(name)
    }
    return rows
}

/**
 * The section header above row [index], or null — Zed emits one over the first
 * row of each contiguous local/remote group, but only with every branch on
 * show: a filtered list gets one title for the whole list instead
 * ([branchListHeader]). The Boolean is whether the header also draws a top
 * divider, which "Remote Branches" does when anything sits above it
 * (branch_picker.rs:1941-1957).
 */
fun branchSectionHeader(
    rows: List<BranchPickerRow>,
    index: Int,
    filter: BranchFilter,
): Pair<String, Boolean>? {
    if (filter != BranchFilter.All) return null
    val row = rows.getOrNull(index) as? BranchPickerRow.Branch ?: return null
    val previous = rows.getOrNull(index - 1) as? BranchPickerRow.Branch
    val startsSection = index == 0 || previous == null ||
        previous.entry.isRemote != row.entry.isRemote
    if (!startsSection) return null
    return if (row.entry.isRemote) {
        "Remote Branches" to (index != 0)
    } else {
        "Local Branches" to false
    }
}

/**
 * The single header a Local or Remote filter puts over the whole list —
 * skipped when the first match is not a branch, i.e. only the create entry
 * survived the query (`render_header`, branch_picker.rs:1354-1375).
 */
fun branchListHeader(rows: List<BranchPickerRow>, filter: BranchFilter): String? {
    if (filter == BranchFilter.All) return null
    if (rows.firstOrNull() !is BranchPickerRow.Branch) return null
    return filter.label
}
