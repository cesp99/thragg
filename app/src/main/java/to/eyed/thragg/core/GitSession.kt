package to.eyed.thragg.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The git side of a project: what has changed, what is staged, and the four
 * things the panel can do about it.
 *
 * Same shape as [ProjectSearchSession] and the worktree itself — a cheap
 * counter to poll, and a blocking read taken only when the counter moves. The
 * counter here is [ProjectSession.gitStatusVersion], the very one the project
 * panel already polls for its colours: one `git status` inside the userland
 * feeds both, and a panel open beside the tree costs nothing extra.
 *
 * Everything below is empty and quiet in a build with no Linux userland. That
 * is not this class's decision to explain — the panel is simply not offered
 * there (`isGitPanelSupported`), because an editor should not show a git panel
 * it can never fill.
 */
class GitSession(private val project: ProjectSession) {
    /**
     * Staleness token, of the same shape as [ProjectSession.version]: it moves
     * whenever anything git says about the project changes, staging included.
     * Reading it is also what schedules a refresh, so it must keep being read.
     */
    val version: Long
        get() = project.gitStatusVersion

    /**
     * Everything the panel draws. Reads a cache the engine filled on a thread
     * of its own — it never waits for git — but it does parse JSON, so read it
     * when [version] has moved rather than every frame.
     */
    fun state(): GitPanelState = GitPanelState.parse(CoreBridge.gitChanges(project.id))

    /**
     * Stage those paths, deletions included. Null when it worked, and the
     * reason — usually git's own sentence — when it did not.
     *
     * **Blocking** — call it from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun stage(paths: List<String>): String? =
        CoreBridge.gitStage(project.id, JSONArray(paths).toString())

    /**
     * Send this branch's commits to [remote] — Zed's push, its Publish and
     * Republish when [setUpstream] (the branch has no upstream, or its
     * upstream is gone), its Force Push when [force]: `--force-with-lease`,
     * never plain `--force`, and the lease is the only safety Zed puts in
     * front of it. The remote comes from the caller: resolve it with
     * [branchRemote] and fall back to [remotes] and a picker, which is Zed's
     * own order. **Blocking** — it uses the network.
     */
    fun push(
        branch: String,
        remote: String,
        setUpstream: Boolean,
        force: Boolean = false,
    ): RemoteOpResult =
        RemoteOpResult.parse(CoreBridge.gitPush(project.id, branch, remote, setUpstream, force))

    /**
     * Fetch from one [remote], or — null — from every one of them, Zed's
     * plain Fetch (`git fetch --all`). **Blocking** — network.
     */
    fun fetch(remote: String? = null): RemoteOpResult =
        RemoteOpResult.parse(CoreBridge.gitFetch(project.id, remote.orEmpty()))

    /**
     * Pull [branch] from [remote], rebasing when [rebase] — Zed's Pull and
     * Pull (Rebase). The branch name joins the argv only when the branch has
     * no upstream, exactly as Zed passes it. **Blocking** — network.
     */
    fun pull(branch: String, remote: String, rebase: Boolean = false): RemoteOpResult =
        RemoteOpResult.parse(CoreBridge.gitPull(project.id, branch, remote, rebase))

    /**
     * Every remote, with the fetch URL github.com detection reads — the
     * Fetch From and Push To pickers' listing. **Blocking** — it runs git.
     */
    fun remotes(): GitRemoteList = GitRemoteList.parse(CoreBridge.gitRemotes(project.id))

    /**
     * The remote [branch] is configured to talk to in that direction, or null
     * when none is — the cue to fall back to [remotes] and a picker (one
     * remote picks itself; several ask), which is Zed's `get_remote` flow.
     * **Blocking** — it runs git.
     */
    fun branchRemote(branch: String, forPush: Boolean): String? =
        CoreBridge.gitBranchRemote(project.id, branch, forPush)

    /**
     * What changed, line by line — the whole patch, or one file's.
     *
     * **Blocking** — call it from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun patch(path: String? = null, staged: Boolean = false): PatchResult {
        val root = JSONObject(CoreBridge.gitPatch(project.id, path.orEmpty(), staged))
        if (!root.isNull("error")) return PatchResult(error = root.getString("error"))
        val files = root.optJSONArray("files") ?: JSONArray()
        return PatchResult(
            files = List(files.length()) { index -> FileDiff.parse(files.getJSONObject(index)) },
        )
    }

    /**
     * A page of history, newest first. Empty for a repository with no commits.
     * [allRefs] is the graph's walk — every branch, remote and tag in
     * `--date-order`, Zed's `LogSource::All`; the default is the plain HEAD
     * walk the panel's History tab shows.
     *
     * **Blocking** — call it from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun log(limit: Int = 100, skip: Int = 0, allRefs: Boolean = false): CommitPage {
        val root = JSONObject(CoreBridge.gitLog(project.id, limit.toLong(), skip.toLong(), allRefs))
        if (!root.isNull("error")) return CommitPage(error = root.getString("error"))
        val array = root.optJSONArray("commits") ?: JSONArray()
        return CommitPage(
            commits = List(array.length()) { index -> Commit.parse(array.getJSONObject(index)) },
        )
    }

    /**
     * The branch's changes since it left [base] — Zed's Branch Diff, a
     * `git diff <base>...` merge-base diff with worktree contents included,
     * which is what the clean tree's "View Branch Diff" opens. **Blocking** —
     * call it from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun branchPatch(base: String): PatchResult {
        val root = JSONObject(CoreBridge.gitBranchPatch(project.id, base))
        if (!root.isNull("error")) return PatchResult(error = root.getString("error"))
        val files = root.optJSONArray("files") ?: JSONArray()
        return PatchResult(
            files = List(files.length()) { index -> FileDiff.parse(files.getJSONObject(index)) },
        )
    }

    /**
     * What one commit changed against its first parent, as the patch the diff
     * view draws. A [path] narrows it to one file — the sidebar's per-file
     * "View Changes". **Blocking** — call it from
     * [kotlinx.coroutines.Dispatchers.IO].
     */
    fun commitPatch(sha: String, path: String? = null): PatchResult {
        val root = JSONObject(CoreBridge.gitCommitPatch(project.id, sha, path.orEmpty()))
        if (!root.isNull("error")) return PatchResult(error = root.getString("error"))
        val files = root.optJSONArray("files") ?: JSONArray()
        return PatchResult(
            files = List(files.length()) { index -> FileDiff.parse(files.getJSONObject(index)) },
        )
    }

    /** One commit in full. Null when git could not read it. **Blocking**. */
    fun commitDetails(sha: String): CommitDetails? {
        val root = JSONObject(CoreBridge.gitCommitDetails(project.id, sha))
        if (!root.isNull("error")) return null
        val files = root.optJSONArray("files") ?: JSONArray()
        return CommitDetails(
            commit = Commit.parse(root),
            message = root.optString("message"),
            files = List(files.length()) { index ->
                val file = files.getJSONObject(index)
                CommitFile(
                    status = file.optString("status").firstOrNull() ?: '?',
                    path = file.optString("path"),
                    // `optString` on a JSON null hands back the *string*
                    // "null", which is how every renamed-from field in the
                    // history read `null → .gitignore`.
                    original = if (file.isNull("original")) null else file.getString("original"),
                )
            },
        )
    }

    /**
     * Who commits will be recorded as, or nulls when git has none.
     *
     * **Blocking** — call it from [kotlinx.coroutines.Dispatchers.IO].
     */
    fun identity(): GitIdentity? {
        val root = JSONObject(CoreBridge.gitIdentity(project.id))
        val name = root.optString("name")
        val email = root.optString("email")
        if (!root.has("name")) return null
        return GitIdentity(name, email)
    }

    /** Record that identity in the guest. Null when it worked. **Blocking**. */
    fun setIdentity(name: String, email: String): String? =
        CoreBridge.gitSetIdentity(project.id, name, email)

    /** Take those paths back out of the index. **Blocking**. */
    fun unstage(paths: List<String>): String? =
        CoreBridge.gitUnstage(project.id, JSONArray(paths).toString())

    /**
     * **Destructive.** Throw away every uncommitted change to those paths.
     *
     * A path the last commit has goes back to what the commit holds. A path it
     * does not — untracked, newly staged, or the new name of a rename — has
     * nowhere to go back to, so it is moved to the app's trash instead of being
     * deleted; [GitChange.inHead] is which of the two will happen, and the
     * confirmation should say so. A rename is both at once: [GitChange.original]
     * comes back from the commit and the new name goes to the trash.
     *
     * A row the engine cannot explain — a conflict above all, where discarding
     * would keep one side of a merge and say nothing — comes back as a refusal
     * with the reason in it, and nothing is touched.
     *
     * **Ask the user first, naming the files.** Nothing below here asks.
     * **Blocking**.
     */
    fun discard(paths: List<String>): String? =
        CoreBridge.gitDiscard(project.id, JSONArray(paths).toString())

    /**
     * Commit what is staged. An empty message is refused rather than making an
     * empty commit, and nothing is staged implicitly: committing with an empty
     * index comes back with git's own "nothing added to commit".
     *
     * The three flags are the commit split-button's menu: [amend] folds the
     * commit into HEAD, [signoff] appends the trailer, [noVerify] is Skip
     * Hooks — `git commit --no-verify`, the literal Zed's menu shows as that
     * entry's aside.
     *
     * The other refusal worth knowing is "unable to auto-detect email address",
     * which means the userland's git has no identity yet. It is fixed in the
     * terminal, once, with `git config --global user.email`; the panel shows
     * git's words rather than paraphrasing them. **Blocking**.
     */
    fun commit(
        message: String,
        amend: Boolean = false,
        signoff: Boolean = false,
        noVerify: Boolean = false,
    ): String? = CoreBridge.gitCommit(project.id, message, amend, signoff, noVerify)

    /**
     * Undo the last commit, keeping everything it held staged — exactly
     * `git reset --soft HEAD^`, Zed's Uncommit. Nothing below here asks:
     * check [headPushedRemotes] and read the old message back for the commit
     * box *before* calling this, while HEAD still names the commit.
     * Null when it worked. **Blocking**.
     */
    fun uncommit(): String? = CoreBridge.gitUncommit(project.id)

    /**
     * Every `remote/branch` that already holds HEAD — evidence for the
     * uncommit confirmation that the commit was pushed. Empty both when
     * nothing was pushed and when the check could not run; Zed proceeds
     * silently in both cases, and so should a caller. **Blocking**.
     */
    fun headPushedRemotes(): List<String> =
        parsePushedRemotes(CoreBridge.gitHeadPushedRemotes(project.id))

    /**
     * Make the project a repository — the panel's "Initialize Repository".
     * The guest's own `init.defaultBranch` wins when it is set;
     * [fallbackBranch] — Zed's setting defaults it to `main` — names the
     * branch when it is not. Null when it worked. **Blocking**.
     */
    fun initRepository(fallbackBranch: String = "main"): String? =
        CoreBridge.gitInit(project.id, fallbackBranch)

    /**
     * Every branch, local and remote-tracking, with the tip commit each
     * picker row shows. **Blocking** — it runs git; read it when the picker
     * opens, not on a poll loop.
     */
    fun branches(): GitBranchList = GitBranchList.parse(CoreBridge.gitBranches(project.id))

    /**
     * Check out a branch by the name [branches] listed. A remote name
     * (`origin/feature`) grows a local tracking branch named after it first,
     * as in Zed. Null when it worked; git's refusal — a dirty worktree above
     * all — when it did not, and nothing is stashed or forced. **Blocking**.
     */
    fun checkoutBranch(name: String): String? = CoreBridge.gitChangeBranch(project.id, name)

    /**
     * Create a branch and switch to it. No [base] branches off HEAD — the
     * picker's plain Create; "Create New From" passes the default branch.
     * Null when it worked. **Blocking**.
     */
    fun createBranch(name: String, base: String? = null): String? =
        CoreBridge.gitCreateBranch(project.id, name, base.orEmpty())

    /**
     * Delete a branch. A branch that is not fully merged is git's own
     * refusal, which the picker answers by offering [force]; deleting a
     * remote-tracking row passes [isRemote] so the `-r` rides along. Null
     * when it worked. **Blocking**.
     */
    fun deleteBranch(name: String, isRemote: Boolean = false, force: Boolean = false): String? =
        CoreBridge.gitDeleteBranch(project.id, name, isRemote, force)

    /**
     * The repository's default branch — what "Create New From:" names — or
     * null when nothing in Zed's chain matches. **Blocking**.
     */
    fun defaultBranch(): String? = CoreBridge.gitDefaultBranch(project.id)

    /**
     * The hunks of one changed file with their staged bit — the project
     * diff's per-hunk buttons read it. **Blocking** — it runs git.
     */
    fun hunkStates(path: String): HunkStates = HunkStates.parse(CoreBridge.gitPathHunkStates(project.id, path))

    /**
     * Stage or unstage every hunk of [path] touching [rows] (rows of the
     * file as it is now, 0-based) — the project diff's Stage/Unstage on a
     * hunk header. Null when it worked. **Blocking**.
     */
    fun stageHunk(path: String, rows: IntRange, stage: Boolean): String? =
        CoreBridge.gitPathHunkStage(project.id, path, rows.first.toLong(), (rows.last + 1).toLong(), stage)

    /**
     * Put the commit's rows back over every hunk of [path] touching [rows]
     * — the project diff's Restore. Edits the open buffer when there is one,
     * else writes the file. Null when it worked. **Blocking**.
     */
    fun restoreHunk(path: String, rows: IntRange): String? =
        CoreBridge.gitPathHunkRestore(project.id, path, rows.first.toLong(), (rows.last + 1).toLong())

    /** `git stash list`, newest first. **Blocking** — it runs git. */
    fun stashList(): StashList = StashList.parse(CoreBridge.gitStashList(project.id))

    /**
     * `git stash push` of the given [kind] — Zed's Stash All / Tracked /
     * Staged — with an optional [message]. Null when it worked. **Blocking**.
     */
    fun stashPush(kind: StashKind, message: String = ""): String? =
        CoreBridge.gitStashPush(project.id, kind.ordinal, message)

    /** `git stash pop`; null [index] pops the newest. **Blocking**. */
    fun stashPop(index: Int? = null): String? = CoreBridge.gitStashPop(project.id, (index ?: -1).toLong())

    /** `git stash apply`; null [index] applies the newest. **Blocking**. */
    fun stashApply(index: Int? = null): String? =
        CoreBridge.gitStashApply(project.id, (index ?: -1).toLong())

    /** `git stash drop`; null [index] drops the newest. **Blocking**. */
    fun stashDrop(index: Int? = null): String? = CoreBridge.gitStashDrop(project.id, (index ?: -1).toLong())

    internal companion object {
        /** The bridge's `{"remotes":[…]}`; an error object is an empty list. */
        fun parsePushedRemotes(json: String): List<String> {
            val remotes = JSONObject(json).optJSONArray("remotes") ?: JSONArray()
            return List(remotes.length()) { remotes.getString(it) }
        }
    }
}

/** Which branch the repository is on, and how far it has drifted. */
data class GitBranch(
    /** Null on a detached HEAD, which is on no branch. */
    val name: String?,
    /** Commits this branch has that its upstream does not, and the reverse. */
    val ahead: Int = 0,
    val behind: Int = 0,
    /** The branch exists but has no commits yet — a repository just created. */
    val unborn: Boolean = false,
    /**
     * The upstream it tracks, or null for a branch nobody has pushed — which
     * is the difference between a push and Zed's "Publish".
     */
    val upstream: String? = null,
    /**
     * The upstream is configured but its ref is gone — deleted on the remote.
     * Zed's `UpstreamTracking::Gone`: the remote button reads "Republish",
     * and pushing re-creates the branch with `--set-upstream`.
     */
    val upstreamGone: Boolean = false,
) {
    val hasUpstream: Boolean get() = upstream != null

    internal companion object {
        /**
         * From the bridge's JSON: the `branch` object [CoreBridge.gitChanges]
         * nests, and the one [CoreBridge.gitBranchInfo] hands back whole.
         */
        fun parse(json: JSONObject): GitBranch = GitBranch(
            name = if (json.isNull("name")) null else json.optString("name"),
            ahead = json.optInt("ahead"),
            behind = json.optInt("behind"),
            unborn = json.optBoolean("unborn"),
            upstream = if (json.isNull("upstream")) null else json.getString("upstream"),
            upstreamGone = json.optBoolean("upstream_gone"),
        )
    }
}

/**
 * One remote, as `git remote -v` lists it: the name every remote argv takes,
 * and the fetch URL — which is what tells a github.com remote from any other.
 */
data class GitRemote(val name: String, val url: String) {
    /**
     * The remote is on github.com — what gates the open-on-web actions.
     *
     * Host equality over the parsed URL, as Zed's provider does
     * (github.rs:192-198): a bare substring test called
     * `https://notgithub.com/…` and a `github.com` buried in somebody
     * else's path GitHub, and disagreed with `githubRepoSlug`, which gates
     * the graph sidebar off the same remote.
     */
    val isGithub: Boolean
        get() = GitRemoteUrl.parse(url)?.host == "github.com"
}

/** Every remote, or why the listing failed. */
data class GitRemoteList(
    val remotes: List<GitRemote> = emptyList(),
    val error: String? = null,
) {
    internal companion object {
        fun parse(json: String): GitRemoteList {
            val root = JSONObject(json)
            val remotes = root.optJSONArray("remotes") ?: JSONArray()
            return GitRemoteList(
                remotes = List(remotes.length()) { index ->
                    val remote = remotes.getJSONObject(index)
                    GitRemote(name = remote.optString("name"), url = remote.optString("url"))
                },
                error = if (root.isNull("error")) null else root.getString("error"),
            )
        }
    }
}

/**
 * What a remote command (fetch, pull, push) said. The two streams are kept
 * apart because Zed's toast rules read them separately — a pull's file count
 * is parsed off stdout while the fetch progress lands on stderr, and a push's
 * "Everything up-to-date" is a stderr sentence. [error] is null on success;
 * on failure it is the one-line reason, with the streams still here for the
 * log view.
 */
data class RemoteOpResult(
    /** The remote the command ran against; null for fetch-all. */
    val remote: String? = null,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
) {
    val ok: Boolean get() = error == null

    internal companion object {
        fun parse(json: String): RemoteOpResult {
            val root = JSONObject(json)
            return RemoteOpResult(
                remote = if (root.isNull("remote")) null else root.getString("remote"),
                stdout = root.optString("stdout"),
                stderr = root.optString("stderr"),
                error = if (root.isNull("error")) null else root.getString("error"),
            )
        }
    }
}

/**
 * One branch, as a picker row draws it: the name, and the tip commit's
 * subject, author and date for the meta line. The engine's `BranchEntry`,
 * which is Zed's `Branch` plus its `CommitSummary`, flattened.
 */
data class GitBranchEntry(
    /**
     * `main` — or `origin/main` for a remote-tracking branch: the refname
     * with its namespace stripped, and the spelling
     * [GitSession.checkoutBranch] takes back.
     */
    val name: String,
    val isRemote: Boolean,
    /** The branch HEAD is on: first in the sort, check icon, no delete. */
    val isHead: Boolean,
    /** The tip commit's hash — empty on an unborn branch, which has none. */
    val sha: String,
    val subject: String,
    /** Seconds since the Unix epoch; 0 when there is no commit. */
    val committerDate: Long,
    val author: String,
    /**
     * The tip commit has a parent — false for a root commit, which is what
     * hides the Uncommit button.
     */
    val hasParent: Boolean,
    /**
     * The upstream a local branch tracks, `origin/main`-style — the key the
     * picker collapses remote rows by: a remote branch some local branch
     * already tracks is not shown again.
     */
    val upstream: String? = null,
    /** Drift against that upstream; both 0 when in sync or untracked. */
    val ahead: Int = 0,
    val behind: Int = 0,
    /** The upstream is configured but its ref is gone — deleted remotely. */
    val upstreamGone: Boolean = false,
) {
    /** The row has a commit to describe; false only on an unborn branch. */
    val hasCommit: Boolean get() = sha.isNotEmpty()

    /** `origin` of `origin/feature`; null for a local branch. */
    val remote: String? get() = if (isRemote) name.substringBefore('/') else null

    internal companion object {
        fun parse(json: JSONObject): GitBranchEntry = GitBranchEntry(
            name = json.optString("name"),
            isRemote = json.optBoolean("is_remote"),
            isHead = json.optBoolean("is_head"),
            sha = json.optString("sha"),
            subject = json.optString("subject"),
            committerDate = json.optLong("committer_date"),
            author = json.optString("author"),
            hasParent = json.optBoolean("has_parent"),
            upstream = if (json.isNull("upstream")) null else json.getString("upstream"),
            ahead = json.optInt("ahead"),
            behind = json.optInt("behind"),
            upstreamGone = json.optBoolean("upstream_gone"),
        )
    }
}

/**
 * Every branch, or as many as git could list: a partial listing keeps its
 * rows and carries the complaint beside them, which the picker shows as a
 * warning banner rather than an empty list — Zed's own rule.
 */
data class GitBranchList(
    val branches: List<GitBranchEntry> = emptyList(),
    val error: String? = null,
) {
    internal companion object {
        fun parse(json: String): GitBranchList {
            val root = JSONObject(json)
            val branches = root.optJSONArray("branches") ?: JSONArray()
            return GitBranchList(
                branches = List(branches.length()) { index ->
                    GitBranchEntry.parse(branches.getJSONObject(index))
                },
                error = if (root.isNull("error")) null else root.getString("error"),
            )
        }
    }
}

/**
 * One changed file.
 *
 * [staged] and [unstaged] are the two halves of git's status pair, and a file
 * can have both: editing a file, staging it, then editing it again puts it in
 * both sections of the panel with different contents in each — which is exactly
 * what git means and what a single rolled-up status cannot say.
 */
data class GitChange(
    /**
     * Project-relative, '/'-separated — [ProjectEntry.path]'s spelling.
     *
     * Ends in '/' when git collapsed a whole new directory into one record,
     * which is what it does with every file in a directory it has never seen.
     * That row is a folder, and [isDirectory] is how the panel knows.
     */
    val path: String,
    val staged: GitFileStatus?,
    val unstaged: GitFileStatus?,
    /** A merge conflict: in neither section, and nothing to stage until it is resolved. */
    val conflicted: Boolean,
    /**
     * The last commit has a version of **this path**. False for an untracked or
     * newly staged file, and false for the destination of a rename, whose old
     * name is the one the commit holds — discarding those *trashes* rather than
     * restores, which is the difference the confirmation has to state.
     */
    val inHead: Boolean,
    /**
     * What the last commit calls this file, when it has been renamed or copied.
     * Discarding a rename cannot be done without it: the old name is restored
     * and the new one goes to the trash.
     */
    val original: String? = null,
) {
    /** One record for a whole new directory — `?? newdir/`. */
    val isDirectory: Boolean get() = path.endsWith('/')

    /**
     * The row's label. Keeps the trailing slash for a directory: "src" and
     * "src/" are two different promises when the next tap discards one of them.
     */
    val name: String get() = path.trimEnd('/').substringAfterLast('/') + if (isDirectory) "/" else ""

    val directory: String get() = path.trimEnd('/').substringBeforeLast('/', "")
}

/** A snapshot of everything the git panel draws. */
data class GitPanelState(
    /**
     * A status run has completed. Until it has, "no changes" is not yet true —
     * it is unknown, and the panel says so rather than claiming a clean tree.
     */
    val scanned: Boolean = false,
    /**
     * git actually ran. False and [scanned] both true means it could not be
     * run at all — no Linux userland, or no git inside it — which is *not*
     * the same as a clean tree, though both arrive as an empty list.
     */
    val ran: Boolean = false,
    /** The project is inside a git repository at all. */
    val hasRepo: Boolean = false,
    val branch: GitBranch? = null,
    val entries: List<GitChange> = emptyList(),
) {
    val staged: List<GitChange> get() = entries.filter { it.staged != null }
    val unstaged: List<GitChange> get() = entries.filter { it.unstaged != null }
    val conflicts: List<GitChange> get() = entries.filter { it.conflicted }

    val isClean: Boolean get() = entries.isEmpty()

    internal companion object {
        fun parse(json: String): GitPanelState {
            val root = JSONObject(json)
            val entries = root.optJSONArray("entries") ?: JSONArray()
            val branch = root.optJSONObject("branch")
            return GitPanelState(
                scanned = root.optBoolean("scanned"),
                ran = root.optBoolean("ran"),
                hasRepo = root.optBoolean("has_repo"),
                branch = branch?.let { GitBranch.parse(it) },
                entries = List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    GitChange(
                        path = entry.getString("path"),
                        staged = status(entry, "staged"),
                        unstaged = status(entry, "unstaged"),
                        conflicted = entry.optBoolean("conflicted"),
                        inHead = entry.optBoolean("in_head"),
                        original = if (entry.isNull("original")) null else entry.getString("original"),
                    )
                },
            )
        }

        private fun status(entry: JSONObject, key: String): GitFileStatus? =
            if (entry.isNull(key)) null else GitFileStatus.parse(entry.getString(key))
    }
}

/**
 * The name and email commits are recorded under.
 *
 * Empty strings mean git has none — not that it has an empty one. A fresh
 * Debian guesses `root@localhost.(none)` from its hostname, then refuses to
 * commit with it, which is the one wall every new userland hits.
 */
data class GitIdentity(val name: String, val email: String) {
    val isComplete: Boolean get() = name.isNotBlank() && email.isNotBlank()
}

/** One file's diff, and what a diff view draws. */
data class FileDiff(
    val path: String,
    /** Where a rename came from. */
    val original: String?,
    /** git said the content is binary; there are no hunks to show. */
    val isBinary: Boolean,
    /** The diff creates this file — what tells an empty new file from a
     * mode-only change, which is otherwise the same hunkless shape. */
    val created: Boolean = false,
    /** The diff removes this file — the same tell, for an empty deletion. */
    val deleted: Boolean = false,
    val hunks: List<PatchHunk>,
) {
    /** How many lines the patch adds and removes, for a summary line. */
    val added: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.kind == '+' } }
    val removed: Int get() = hunks.sumOf { hunk -> hunk.lines.count { it.kind == '-' } }

    internal companion object {
        fun parse(json: JSONObject): FileDiff {
            val hunks = json.optJSONArray("hunks") ?: JSONArray()
            return FileDiff(
                path = json.optString("path"),
                original = if (json.isNull("original")) null else json.getString("original"),
                isBinary = json.optBoolean("is_binary"),
                created = json.optBoolean("created"),
                deleted = json.optBoolean("deleted"),
                hunks = List(hunks.length()) { index ->
                    val hunk = hunks.getJSONObject(index)
                    val lines = hunk.optJSONArray("lines") ?: JSONArray()
                    PatchHunk(
                        oldStart = hunk.optInt("old_start"),
                        newStart = hunk.optInt("new_start"),
                        heading = hunk.optString("heading"),
                        lines = List(lines.length()) { at ->
                            val line = lines.getJSONObject(at)
                            PatchLine(
                                kind = line.optString("kind").firstOrNull() ?: ' ',
                                text = line.optString("text"),
                                oldLine = line.optInt("old_line"),
                                newLine = line.optInt("new_line"),
                            )
                        },
                    )
                },
            )
        }
    }
}

/** One `@@` block of a patch. */
data class PatchHunk(
    val oldStart: Int,
    val newStart: Int,
    /** The enclosing function, when git found one. */
    val heading: String,
    val lines: List<PatchLine>,
) {
    /** How many lines the block covers on each side, as git's header writes. */
    val oldCount: Int get() = lines.count { it.kind != '+' }
    val newCount: Int get() = lines.count { it.kind != '-' }
}

/** One line of a hunk: `' '` unchanged, `'+'` added, `'-'` removed. */
data class PatchLine(
    val kind: Char,
    val text: String,
    /** Its number on the old side, or 0 for an added line. */
    val oldLine: Int,
    /** Its number on the new side, or 0 for a removed line. */
    val newLine: Int,
)

/** A patch, or why there is none. */
data class PatchResult(
    val files: List<FileDiff> = emptyList(),
    val error: String? = null,
)

/** One commit, as the History tab draws it. */
data class Commit(
    val sha: String,
    /** More than one means a merge. */
    val parents: List<String>,
    val author: String,
    val authorEmail: String,
    /** Seconds since the Unix epoch. */
    val authorTime: Long,
    val subject: String,
    /** `HEAD -> main`, `origin/main`, `tag: v1` — git's own `%D`, split. */
    val refs: List<String>,
) {
    val shortSha: String get() = sha.take(7)
    val isMerge: Boolean get() = parents.size > 1

    internal companion object {
        fun parse(json: JSONObject): Commit {
            val parents = json.optJSONArray("parents") ?: JSONArray()
            val refs = json.optJSONArray("refs") ?: JSONArray()
            return Commit(
                sha = json.optString("sha"),
                parents = List(parents.length()) { parents.getString(it) },
                author = json.optString("author"),
                authorEmail = json.optString("author_email"),
                authorTime = json.optLong("author_time"),
                subject = json.optString("subject"),
                refs = List(refs.length()) { refs.getString(it) },
            )
        }
    }
}

/** A page of history, or why there is none. */
data class CommitPage(
    val commits: List<Commit> = emptyList(),
    val error: String? = null,
)

/** A path a commit touched. */
data class CommitFile(val status: Char, val path: String, val original: String?)

/** One commit in full: the row's fields, the whole message, and its files. */
data class CommitDetails(
    val commit: Commit,
    val message: String,
    val files: List<CommitFile>,
)

/** What happened to a run of rows, as the gutter paints it. */
enum class GitHunkKind { Added, Modified, Deleted }

/**
 * One difference between the buffer and the last commit.
 *
 * Rows are *buffer* rows and follow unsaved edits: the engine holds the file's
 * text at HEAD and re-diffs it against the live buffer, so a hunk stays under
 * the line it belongs to while you type.
 */
data class GitHunk(
    val kind: GitHunkKind,
    /** First row of the hunk, 0-based. */
    val startRow: Int,
    /**
     * One past its last row — and *equal* to [startRow] for a deletion, which
     * occupies no rows. A gutter draws a deletion as a mark on the boundary
     * above [startRow], not as a filled row.
     */
    val endRow: Int,
    /** How many rows the commit had here. 0 for an addition. */
    val oldRows: Int,
    /**
     * The row those sat on in the commit, 0-based — where an expanded hunk
     * reads its deleted lines from ([GitDiff.hunkBaseLines]).
     */
    val oldStart: Int = 0,
    /**
     * Whether the index already holds the buffer's text for these rows —
     * known only from [GitDiff.hunkStates], which asks git; the polled
     * [GitDiff.hunks] leaves it null. Drives the expanded header's
     * Stage/Unstage label, as Zed's `has_secondary_hunk` does
     * (editor/src/git.rs:3091).
     */
    val staged: Boolean? = null,
) {
    /** Buffer rows the hunk occupies; empty for a deletion. */
    val rows: IntRange get() = startRow until endRow

    /**
     * Whether the hunk sits at [row] — its rows, or for a deletion the row
     * just below its boundary, which is where the gutter's pill is drawn.
     */
    fun covers(row: Int): Boolean =
        if (endRow > startRow) row in startRow until endRow else row == startRow
}

/** [GitDiff.hunkStates]' answer: the hunks with their staged bit, or why not. */
data class HunkStates(val hunks: List<GitHunk> = emptyList(), val error: String? = null) {
    companion object {
        fun parse(json: String): HunkStates {
            val root = JSONObject(json)
            if (!root.isNull("error")) return HunkStates(error = root.getString("error"))
            val entries = root.optJSONArray("hunks") ?: JSONArray()
            return HunkStates(
                hunks = List(entries.length()) { index ->
                    val entry = entries.getJSONObject(index)
                    GitHunk(
                        kind = GitHunkKind.entries.getOrElse(entry.optInt("kind")) { GitHunkKind.Modified },
                        startRow = entry.getInt("start_row"),
                        endRow = entry.getInt("end_row"),
                        oldRows = entry.getInt("old_rows"),
                        oldStart = entry.optInt("old_start"),
                        staged = entry.optBoolean("staged"),
                    )
                }
            )
        }
    }
}

/** One `stash@{N}` — Zed's `StashEntry` (crates/git/src/stash.rs). */
data class StashEntry(
    /** `N`: 0 is the newest. */
    val index: Int,
    val sha: String,
    /** The message, minus git's `WIP on branch:` prefix. */
    val message: String,
    /** The branch the stash was taken on, when git's prefix named one. */
    val branch: String?,
    /** Seconds since the Unix epoch. */
    val timestamp: Long,
) {
    companion object {
        fun parse(json: JSONObject): StashEntry = StashEntry(
            index = json.getInt("index"),
            sha = json.getString("sha"),
            message = json.optString("message"),
            branch = json.optString("branch").takeIf { !json.isNull("branch") && it.isNotEmpty() },
            timestamp = json.optLong("timestamp"),
        )
    }
}

/** The stash listing, or why there is none. */
data class StashList(val entries: List<StashEntry> = emptyList(), val error: String? = null) {
    companion object {
        fun parse(json: String): StashList {
            val root = JSONObject(json)
            if (!root.isNull("error")) return StashList(error = root.getString("error"))
            val entries = root.optJSONArray("entries") ?: JSONArray()
            return StashList(List(entries.length()) { StashEntry.parse(entries.getJSONObject(it)) })
        }
    }
}

/**
 * What a stash push takes — Zed's `StashKind` (git_panel.rs:205-227), in the
 * order the engine numbers it.
 */
enum class StashKind(val label: String) {
    /** Everything, untracked files included: `--include-untracked`. */
    All("Stash All"),
    /** Tracked changes only, the plain `git stash push`. */
    Tracked("Stash Tracked"),
    /** The index only: `--staged`. */
    Staged("Stash Staged"),
}

/** One run of rows and the commit that last touched it. */
data class BlameLine(
    /** Full commit hash; all zeroes for lines that are not committed yet. */
    val sha: String,
    /** First row of the run, 0-based, in the file **on disk**. */
    val startRow: Int,
    val rowCount: Int,
    val author: String,
    /** Seconds since the Unix epoch, or 0 for an uncommitted line. */
    val authorTime: Long,
    /** The commit's subject line. */
    val summary: String,
) {
    /** What git itself abbreviates a hash to. */
    val shortSha: String get() = sha.take(7)

    val isCommitted: Boolean get() = sha.any { it != '0' }
}

/** Blame for a whole file, or why there is none. */
data class FileBlame(
    val lines: List<BlameLine> = emptyList(),
    /** git's own message: not a repository, no such path in HEAD, no userland. */
    val error: String? = null,
) {
    /** The run covering [row], or null past the end of what git blamed. */
    fun at(row: Int): BlameLine? =
        lines.lastOrNull { row >= it.startRow && row < it.startRow + it.rowCount }
}

/**
 * The git view of one open buffer: the gutter's hunks, and blame.
 *
 * Keyed by buffer rather than by project because that is what it is about — the
 * file you are looking at — and because the engine already knows which project
 * a file is in.
 */
object GitDiff {
    /**
     * Staleness token for [hunks]; 0 while there is nothing to show. Poll it
     * like [GitSession.version]: reading it is what schedules the diff, and it
     * never waits for one.
     */
    fun hunksVersion(bufferId: Long): Long = CoreBridge.gitHunksVersion(bufferId)

    /**
     * The hunks, ascending by row. Reads a cache — it takes the engine's buffer
     * locks briefly and never runs git, so it is safe on the main thread,
     * though there is no reason to call it unless [hunksVersion] has moved.
     */
    fun hunks(bufferId: Long): List<GitHunk> {
        val flat = CoreBridge.gitHunks(bufferId)
        val hunks = ArrayList<GitHunk>(flat.size / 5)
        var index = 0
        while (index + 4 < flat.size) {
            hunks.add(
                GitHunk(
                    kind = KINDS.getOrElse(flat[index]) { GitHunkKind.Modified },
                    startRow = flat[index + 1],
                    endRow = flat[index + 2],
                    oldRows = flat[index + 3],
                    oldStart = flat[index + 4],
                )
            )
            index += 5
        }
        return hunks
    }

    /**
     * The rows the commit had where [hunk] now is — what its expanded block
     * draws struck through — or null while the base text is still on its
     * way. A cache read, safe wherever [hunks] is.
     */
    fun hunkBaseLines(bufferId: Long, hunk: GitHunk): List<String>? {
        if (hunk.oldRows == 0) return emptyList()
        val json = CoreBridge.gitHunkBaseLines(bufferId, hunk.oldStart.toLong(), hunk.oldRows.toLong())
            ?: return null
        val array = JSONArray(json)
        return List(array.length()) { array.getString(it) }
    }

    /**
     * The hunks with their staged bit. **Blocking** — it runs `git show` —
     * so from [kotlinx.coroutines.Dispatchers.IO], when hunks are expanded
     * and when the status counter moved, never on the poll loop.
     */
    fun hunkStates(bufferId: Long): HunkStates = HunkStates.parse(CoreBridge.gitHunkStates(bufferId))

    /**
     * Stage or unstage every hunk touching [rows] — Zed's `git::ToggleStaged`
     * for one hunk. Null when it worked. **Blocking**.
     */
    fun stageHunk(bufferId: Long, rows: IntRange, stage: Boolean): String? =
        CoreBridge.gitHunkStage(bufferId, rows.first.toLong(), (rows.last + 1).toLong(), stage)

    /**
     * Put the commit's rows back over every hunk touching [rows] — Zed's
     * `git::Restore`. An edit of the buffer: the editor resyncs afterwards.
     * Null when it worked. **Blocking** only if the base is not cached.
     */
    fun restoreHunk(bufferId: Long, rows: IntRange): String? =
        CoreBridge.gitHunkRestore(bufferId, rows.first.toLong(), (rows.last + 1).toLong())

    /**
     * Who last touched each run of rows. **Blocking and uncached** — it runs
     * git every time — so call it when the user asks for blame, from
     * [kotlinx.coroutines.Dispatchers.IO], and never on a poll loop.
     */
    fun blame(bufferId: Long): FileBlame {
        val root = JSONObject(CoreBridge.gitBlame(bufferId))
        if (!root.isNull("error")) return FileBlame(error = root.getString("error"))
        val entries = root.optJSONArray("entries") ?: JSONArray()
        return FileBlame(
            lines = List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                BlameLine(
                    sha = entry.getString("sha"),
                    startRow = entry.getInt("start_row"),
                    rowCount = entry.getInt("row_count"),
                    author = entry.optString("author"),
                    authorTime = entry.optLong("author_time"),
                    summary = entry.optString("summary"),
                )
            }
        )
    }

    /** Index order matches the engine's, which is what the ints mean. */
    private val KINDS = GitHunkKind.entries.toTypedArray()
}
