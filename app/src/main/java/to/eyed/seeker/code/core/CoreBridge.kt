package to.eyed.seeker.code.core

/**
 * Kotlin side of the JNI boundary to the Rust engine (`core/crates/jni-bridge`).
 *
 * Naming contract: each `external` function here maps to a
 * `Java_to_eyed_seeker_code_core_CoreBridge_<name>` symbol in the Rust
 * crate. Keep the two files in sync — this is the only place the two worlds
 * meet.
 *
 * Calls across this boundary must stay coarse-grained: never loop over
 * per-character calls from Kotlin.
 *
 * Error convention: functions returning [Long] return -1 for unknown
 * buffers / invalid arguments (and, for undo/redo, when there is nothing to
 * undo/redo). Functions returning [String]? return null for unknown buffers.
 */
object CoreBridge {
    init {
        System.loadLibrary("seekercore")
    }

    /**
     * Hands the engine the app's private files directory and brings it up.
     * Call this before anything else touches the bridge.
     *
     * Android runs apps without `$HOME`, which the vendored Zed crates assume
     * exists — a worktree scan panics without one. The engine points `HOME`
     * (and the trash) at this directory.
     *
     * Cheap on the calling thread: paths, logging and the engine's own state
     * are set up synchronously, and the expensive part — the gpui runtime —
     * is only *kicked off*, onto a thread of its own. Nothing here or later
     * waits for that boot: work reaching the runtime before it is up queues
     * inside the engine, and the UI gates on the version counters it already
     * polls ([projectVersion] and friends), which stay 0 until there is
     * something to show.
     *
     * [verboseLogging] raises the engine's log level from Info to Debug, which
     * is where its git and scan diagnostics live. Pass `BuildConfig.DEBUG`: the
     * Rust library cannot tell a debug APK from a release one on its own,
     * because Gradle builds it `--release` for every Android build type.
     */
    external fun initialize(filesDir: String, verboseLogging: Boolean)

    external fun engineVersion(): String

    /** Returns the id of the newly created buffer. */
    external fun createBuffer(initialText: String): Long

    external fun closeBuffer(bufferId: Long): Boolean

    /**
     * Replaces the byte range [start, end) with [text]. Offsets are UTF-8
     * byte offsets and must lie on character boundaries. Returns the new
     * buffer version, or -1 on invalid buffer id or range.
     */
    external fun applyEdit(bufferId: Long, start: Long, end: Long, text: String): Long

    /**
     * Undoes the most recent edit transaction. Returns the new buffer
     * version, or -1 if there was nothing to undo.
     */
    external fun undoBuffer(bufferId: Long): Long

    /**
     * Redoes the most recently undone transaction. Returns the new buffer
     * version, or -1 if there was nothing to redo.
     */
    external fun redoBuffer(bufferId: Long): Long

    /**
     * Monotonic content version, bumped by every edit/undo/redo. Cheap
     * staleness check for cached reads.
     */
    external fun bufferVersion(bufferId: Long): Long

    external fun bufferLineCount(bufferId: Long): Long

    /**
     * Text of rows [firstLine, lastLine) — end-exclusive, clipped to the
     * buffer — joined with '\n' and without a trailing newline. This is the
     * read path the editor should use: fetch only the visible window.
     */
    external fun bufferLines(bufferId: Long, firstLine: Long, lastLine: Long): String?

    /**
     * Assigns a tree-sitter language (grammar name, e.g. "rust") to the
     * buffer and parses it. Returns false for unknown buffers/languages.
     */
    external fun bufferSetLanguage(bufferId: Long, language: String): Boolean

    /**
     * Every language the engine can parse, as a JSON array of objects with
     * `grammar` and `name`, sorted by display name — what the language
     * selector lists (Zed's `language_selector::Toggle`). Read through
     * [to.eyed.seeker.code.core.Languages], which caches it.
     */
    external fun availableLanguages(): String

    /**
     * Highlight spans for rows [firstLine, lastLine), flattened as groups
     * of four ints: row, UTF-16 start column, UTF-16 end column, style id
     * (index into the engine's style-name list, mirrored by
     * [to.eyed.seeker.code.ui.editor.SyntaxPalette]). Empty when the
     * buffer has no language; null for unknown buffers.
     */
    external fun bufferHighlights(bufferId: Long, firstLine: Long, lastLine: Long): IntArray?

    /**
     * The symbol path containing the caret — Zed's breadcrumbs after the
     * file name — as a JSON array of strings, outermost first ("impl Foo",
     * "fn bar"). Empty array when the buffer has no language or the caret
     * sits outside every symbol; null for unknown buffers. The column is
     * UTF-16, like every caret the UI holds. Reads the last parsed tree, so
     * the answer can be one highlight-worker round-trip stale.
     */
    external fun bufferOutlinePath(bufferId: Long, row: Long, colUtf16: Long): String?

    /**
     * Every outline item in the buffer, in source order — the rows of Zed's
     * outline picker — as a JSON array of
     * `{label, depth, row, col_utf16, end_row}`, where row/col are the
     * *item's* start (the caret target Zed confirms onto, outline.rs:417-425)
     * and end_row closes its extent. Empty array when the buffer has no
     * language; null for unknown buffers. Same staleness contract as
     * [bufferOutlinePath].
     */
    external fun bufferOutline(bufferId: Long): String?

    /**
     * Every foldable block in the buffer, from the syntax tree, as a JSON
     * array of `{start_row, end_row}` — the chip sits on `start_row`, rows
     * `start_row + 1..=end_row` hide; sorted, one per start row. Reads the
     * last parsed tree like [bufferOutline], so re-read it when
     * [bufferHighlightVersion] moves. Empty for a buffer with no language or
     * a grammar without an indents query — keep the indent walk for those;
     * null for unknown buffers.
     */
    external fun bufferFoldRanges(bufferId: Long): String?

    /**
     * The smallest syntax node that strictly contains the given range, as
     * `{start_row, start_col_utf16, end_row, end_col_utf16}` — what
     * `editor::SelectLargerSyntaxNode` grows a selection to. Null when the
     * range already covers the file, the buffer has no language, or the tree
     * has not been parsed yet; the caller then leaves the selection alone.
     * Same staleness contract as [bufferFoldRanges].
     */
    external fun bufferSyntaxNodeRange(
        bufferId: Long,
        startRow: Long,
        startColUtf16: Long,
        endRow: Long,
        endColUtf16: Long,
    ): String?

    /**
     * The innermost bracket pair around the given range, as
     * `{"open": {…}, "close": {…}}` of two row/column ranges — what the pane
     * highlights around the caret and what `editor::MoveToEnclosingBracket`
     * jumps between. From the grammar's `brackets.scm` where there is one and
     * from a delimiter count where there is not, so a plain-text buffer still
     * matches its braces. Null when nothing encloses the range.
     */
    external fun bufferEnclosingBrackets(
        bufferId: Long,
        startRow: Long,
        startColUtf16: Long,
        endRow: Long,
        endColUtf16: Long,
    ): String?

    /**
     * Apply the buffer's `remove_trailing_whitespace_on_save` and
     * `ensure_final_newline_on_save` — the save path's whitespace rules, run
     * against the buffer's resolved settings so a language or a project may
     * turn either off. True when the buffer changed, in which case the editor
     * must resync. **Blocking** — call it off the main thread.
     */
    external fun cleanBufferOnSave(bufferId: Long): Boolean

    /**
     * Byte offset of (row, byte column), clipped to the buffer. -1 for an
     * unknown buffer or negative arguments.
     */
    external fun pointToOffset(bufferId: Long, row: Long, column: Long): Long

    /**
     * (row, byte column) of a byte offset, clipped to the buffer, packed as
     * `(row shl 32) or column`. -1 for an unknown buffer or negative offset.
     */
    external fun offsetToPoint(bufferId: Long, offset: Long): Long

    /**
     * Whole buffer contents. Placeholder-era convenience; real rendering
     * must use [bufferLines].
     */
    external fun bufferText(bufferId: Long): String?

    // -----------------------------------------------------------------------
    // Projects. Opening and expanding are asynchronous: they hand work to the
    // engine's gpui runtime and return at once. Watch [projectVersion] to know
    // when there is something new to read; every other call reads a mirrored
    // snapshot and never waits on the runtime.
    // -----------------------------------------------------------------------

    /** Starts scanning [path] as a project. Returns its id (always > 0). */
    external fun openProject(path: String): Long

    external fun closeProject(projectId: Long): Boolean

    /**
     * Monotonic version of the mirrored worktree snapshot; 0 while there is
     * nothing to show. Poll it to know when to re-read entries.
     */
    external fun projectVersion(projectId: Long): Long

    /** Whether the initial scan has finished. Entries are readable before it. */
    external fun projectScanComplete(projectId: Long): Boolean

    /** Why the project failed to open, or null if it did not fail. */
    external fun projectError(projectId: Long): String?

    /** Display name of the project root; null for an unknown project. */
    external fun projectRootName(projectId: Long): String?

    /**
     * Direct children of a project-relative directory ("" for the root), as a
     * JSON array of objects with `path`, `name`, `is_dir`, `is_ignored`,
     * `is_hidden`, `is_unloaded` and `size`. One call per expanded directory —
     * never one per entry. Unknown projects and unscanned directories give
     * `[]`, never null.
     */
    external fun projectEntries(projectId: Long, dir: String): String

    /**
     * Scans a directory the worktree deferred (gitignored, hidden, or past
     * Zed's `file_scan_depth`). Asynchronous: results show up as a version
     * bump. False if the project or path is unknown.
     */
    external fun expandDirectory(projectId: Long, dir: String): Boolean

    /**
     * Absolute path of a project-relative entry; null if the project is
     * unknown or the path tries to escape the root.
     */
    external fun projectEntryPath(projectId: Long, path: String): String?

    /**
     * Moves entries to the app's trash — the project panel's Delete key, which
     * is Zed's `project_panel::Trash`.
     *
     * Android has no system trash, so this is the app-private one the engine
     * already uses for git discard: entries are *moved*, so it is as cheap as
     * a rename, and nothing is lost until the app's data is cleared.
     *
     * One call for the whole selection. Returns
     * `{"trashed":[{"path","id","name","original_parent"}, …]}`, or
     * `{"error":"…"}` with a sentence to show. Hand the `trashed` array back to
     * [restoreTrash] for Undo. **Blocking** — call it off the main thread.
     */
    external fun projectTrash(projectId: Long, pathsJson: String): String?

    /**
     * Puts trashed entries back where they came from — the panel's Undo, and
     * Zed's `project_panel::Undo`. Takes the `trashed` array [projectTrash]
     * returned. Null when it worked, the reason when it did not; a name that
     * has been taken again refuses the whole restore rather than overwriting.
     * **Blocking**.
     */
    external fun restoreTrash(entriesJson: String): String?

    // -----------------------------------------------------------------------
    // Multi-root projects. A project holds an ordered list of folders — Zed's
    // `Vec<Worktree>` — with the one it was opened with first. The calls above
    // are that first folder; the ones here name a folder explicitly, which is
    // what the panel does once there is more than one.
    // -----------------------------------------------------------------------

    /**
     * Every folder of the project, in order, as a JSON array of objects with
     * `id`, `name`, `path`, `scan_complete`, `error` and `is_primary`. `[]`
     * for an unknown project, never null.
     */
    external fun projectWorktrees(projectId: Long): String

    /**
     * Adds a folder to an open project — Zed's
     * `workspace::AddFolderToProject`. [path] must already exist on disk: the
     * engine works in real paths, so a SAF tree is imported first (see
     * `SafTransfer`).
     *
     * Returns JSON — `{"id": <folder id>}`, or `{"error": "…"}`. A path
     * already covered by one of the project's folders is not added twice; the
     * folder that covers it comes back instead, which is what Zed's
     * `find_or_create_worktree` does.
     */
    external fun projectAddWorktree(projectId: Long, path: String): String

    /**
     * Drops a folder from the project — Zed's
     * `workspace::RemoveWorktreeFromProject`. null when it worked, the reason
     * when it did not; the folder the project was opened with cannot be
     * removed (close the project instead).
     */
    external fun projectRemoveWorktree(projectId: Long, worktreeId: Long): String?

    /** [projectEntries] for a named folder of the project. */
    external fun worktreeEntries(projectId: Long, worktreeId: Long, dir: String): String

    /** [expandDirectory] for a named folder of the project. */
    external fun expandWorktreeDirectory(projectId: Long, worktreeId: Long, dir: String): Boolean

    /** [projectEntryPath] for a named folder of the project. */
    external fun worktreeEntryPath(projectId: Long, worktreeId: Long, path: String): String?

    // -----------------------------------------------------------------------
    // Git status. The engine has no git of its own: it runs the one inside the
    // Debian userland, through proot. We know where that lives and the engine
    // must not guess, so [setUserland] is what turns the feature on — and the
    // `play` flavour simply never calls it, leaving every query below
    // answering "nothing to show".
    // -----------------------------------------------------------------------

    /**
     * Tells the engine where proot and the Debian rootfs are. Call once the
     * userland reports [to.eyed.seeker.code.terminal.UserlandState.Ready];
     * never in the `play` flavour, which has no userland to point at.
     *
     * The engine binds [projectsDir] into the guest at its *own* path, so host
     * and guest agree on every path and nothing needs translating.
     */
    external fun setUserland(
        proot: String,
        rootfs: String,
        tmpDir: String,
        projectsDir: String,
    )

    /** Forgets the userland — after the rootfs is deleted. Status goes empty. */
    external fun clearUserland()

    /**
     * Generation counter for a project's git status; 0 while there is nothing
     * to show. Poll it exactly like [projectVersion]. Polling is also what
     * schedules a refresh, so this must be called for status to stay current —
     * it never waits on git.
     */
    external fun gitStatusVersion(projectId: Long): Long

    /**
     * The branch record the project is on, from the cached status run, as
     * JSON — `{name, ahead, behind, unborn, upstream, upstream_gone}`, the
     * same object
     * [gitChanges] nests — with no JSON of the changed files and no git run:
     * the title bar's drift arrows and the history views' reload keys ride
     * the same half-second poll the name does. Null when nothing is known:
     * no repository, or no completed run yet. A detached HEAD is a present
     * object whose `name` is null — on no branch, which is not the same
     * answer as no repository. Versioned by [gitStatusVersion], like every
     * other read of that cache.
     */
    external fun gitBranchInfo(projectId: Long): String?

    /**
     * The commit HEAD points at, from the same cache — the staleness key for
     * the commit graph: history needs reloading when this moves, not on every
     * status change. Null when it is not known, which a caller must read as
     * "assume it moved", never as "nothing changed". Versioned by
     * [gitStatusVersion].
     */
    external fun gitHead(projectId: Long): String?

    /**
     * The status map as a JSON object of project-relative path to status
     * (`modified`, `added`, `deleted`, `renamed`, `conflicted`, `untracked`,
     * `ignored`). Ancestor directories of a changed file are included with a
     * rolled-up status, so the panel needs one lookup per row. Reads a cache:
     * never blocks, never null, `{}` when there is nothing to show.
     */
    external fun gitStatus(projectId: Long): String

    /**
     * Everything the git panel draws, as JSON — see [GitPanelState] for the
     * shape. It is the *same* `git status` run [gitStatus] reads, unreduced:
     * one process serves both, and [gitStatusVersion] is the counter for both.
     *
     * Never blocks, never null. `scanned` is false until a run has completed,
     * which is how "nothing changed" is told from "not asked yet".
     */
    external fun gitChanges(projectId: Long): String

    /**
     * Stages every path in [pathsJson] (a JSON array of project-relative
     * paths), deletions included. Returns null when it worked, and git's own
     * message when it did not.
     *
     * **Blocking** — it waits for a process inside the Linux userland, which is
     * tens of milliseconds at best. Call it off the main thread.
     */
    external fun gitStage(projectId: Long, pathsJson: String): String?

    /** Takes every path back out of the index. **Blocking**; see [gitStage]. */
    external fun gitUnstage(projectId: Long, pathsJson: String): String?

    /**
     * **Destructive.** Throws away every uncommitted change to those paths.
     *
     * A path the last commit has is restored to what the commit holds — index
     * and worktree both. A path it does not have (untracked, staged-new, or the
     * new name of a rename) cannot be restored from anywhere, so it is moved to
     * the app's trash rather than deleted: a mistake here must not be a loss.
     *
     * A row the engine cannot explain is *refused*, with the reason as the
     * return value and nothing touched — a conflict above all, where the
     * obvious git command keeps one side of the merge and reports success.
     *
     * **Confirm with the user first, naming the files.** Nothing below this
     * call asks anything. **Blocking**.
     */
    external fun gitDiscard(projectId: Long, pathsJson: String): String?

    /**
     * Commits what is staged. An empty or whitespace-only message is refused
     * rather than becoming an empty commit, and nothing is staged implicitly —
     * a commit with an empty index comes back with git's own "nothing added to
     * commit". The three flags are the split-button menu's Amend, Signoff and
     * Skip Hooks, appended to the argv in Zed's own order. **Blocking**.
     */
    external fun gitCommit(
        projectId: Long,
        message: String,
        amend: Boolean,
        signoff: Boolean,
        noVerify: Boolean,
    ): String?

    /**
     * Undoes the last commit, keeping its changes staged — exactly `git reset
     * --soft HEAD^`, Zed's Uncommit. Nothing below this call asks anything:
     * read [gitHeadPushedRemotes] and the old message *before* resetting,
     * while HEAD still names the commit. Null when it worked. **Blocking**.
     */
    external fun gitUncommit(projectId: Long): String?

    /**
     * Every `remote/branch` that already holds HEAD, as JSON
     * `{"remotes":[…]}` — the evidence the uncommit confirmation shows that
     * the commit was pushed. Empty both for "nothing pushed" and for a check
     * git could not run — Zed proceeds silently there, and a failed *check*
     * must not block the reset. **Blocking**.
     */
    external fun gitHeadPushedRemotes(projectId: Long): String

    /**
     * Makes the project a repository — the panel's "Initialize Repository"
     * empty state. Zed's two commands: the guest's `init.defaultBranch` names
     * the branch when it is set, [fallbackBranch] when it is not, then
     * `git init -b <branch>`. Null when it worked. **Blocking**.
     */
    external fun gitInit(projectId: Long, fallbackBranch: String): String?

    /**
     * Every local and remote-tracking branch with its tip commit, as JSON —
     * see [GitBranchList] for the shape. A partial listing keeps what parsed
     * and carries git's complaint beside it, which is the picker's warning
     * banner. **Blocking** — it runs git; the picker reads it once on open,
     * not on a poll loop.
     */
    external fun gitBranches(projectId: Long): String

    /**
     * Checks out a branch by the name [gitBranches] listed. A remote name
     * (`origin/feature`) grows a local tracking branch named after it first —
     * minus the remote prefix — exactly as Zed does. Null when it worked;
     * git's refusal, a dirty worktree above all, when it did not. **Blocking**.
     */
    external fun gitChangeBranch(projectId: Long, name: String): String?

    /**
     * Creates a branch and switches to it — `git switch -c <name> [<base>]`.
     * An empty [base] branches off HEAD, which is what the picker's plain
     * Create does; "Create New From" passes the default branch. Null when it
     * worked. **Blocking**.
     */
    external fun gitCreateBranch(projectId: Long, name: String, base: String): String?

    /**
     * Deletes a branch — `git branch -d|-D|-dr|-Dr <name>`, Zed's flag table.
     * An unmerged branch is git's own "not fully merged", the picker's cue to
     * offer force-delete. Null when it worked. **Blocking**.
     */
    external fun gitDeleteBranch(
        projectId: Long,
        name: String,
        isRemote: Boolean,
        force: Boolean,
    ): String?

    /**
     * The repository's default branch, by Zed's chain: `upstream/HEAD`, then
     * `origin/HEAD`, then `init.defaultBranch` if that local branch exists,
     * then local `main`, then `master`. Null when nothing matches — the
     * picker simply drops its "Create New From" entry. **Blocking**.
     */
    external fun gitDefaultBranch(projectId: Long): String?

    /**
     * Every remote with its fetch URL, as JSON — `{"remotes":[{name, url}]}`
     * in `git remote -v`'s order, or `{"error":…}`. The Fetch From / Push To
     * pickers' listing, and where github.com detection reads the URL.
     * **Blocking** — it runs git.
     */
    external fun gitRemotes(projectId: Long): String

    /**
     * The remote [branch] is configured to push to ([isPush]) or pull from —
     * Zed's `get_remote` asks `<branch>@{push}` or `branch.<branch>.remote`
     * respectively — or null when none is configured (or git could not be
     * asked): the cue to fall back to [gitRemotes] and a picker. **Blocking**.
     */
    external fun gitBranchRemote(projectId: Long, branch: String, isPush: Boolean): String?

    /**
     * Fetch from [remote], or from all remotes when it is empty — Zed's
     * `git fetch --all`. Returns the remote-command JSON [RemoteOpResult]
     * parses: git's stdout and stderr kept apart for the toast rules, plus
     * `error` when it failed. Never null. **Blocking** — network.
     */
    external fun gitFetch(projectId: Long, remote: String): String

    /**
     * Pull [branch] from [remote], with `--rebase` when [rebase] — Zed's Pull
     * and Pull (Rebase). The branch name joins the argv only when the branch
     * has no upstream. Returns the remote-command JSON. **Blocking** —
     * network.
     */
    external fun gitPull(projectId: Long, branch: String, remote: String, rebase: Boolean): String

    /**
     * Push [branch] to [remote] — with `--set-upstream` for Zed's Publish and
     * Republish when [setUpstream], or `--force-with-lease` (never plain
     * `--force`) when [force]; force wins when both are set, as in Zed.
     * Returns the remote-command JSON. **Blocking** — network.
     */
    external fun gitPush(
        projectId: Long,
        branch: String,
        remote: String,
        setUpstream: Boolean,
        force: Boolean,
    ): String

    /**
     * What a git that may ask for a credential runs with, for the app's own
     * `git clone`: JSON `{"env":["GIT_ASKPASS=…",…],"args":["-c",…]}` —
     * the askpass environment the engine's fetch, pull and push already
     * carry, and the credential-cache option when the userland's git has
     * the helper. Both lists are empty without a userland. Cheap.
     */
    external fun gitAskpassSetup(): String

    /**
     * The oldest credential prompt a running git or ssh is waiting on, as
     * JSON `{id, prompt, kind, subject, masked, suggestion}` — [GitAskpass]
     * parses it — or null when none is pending. Polled only while a clone,
     * fetch, pull or push is in flight: a lock and a `stat`, no git run.
     */
    external fun gitPendingPrompt(): String?

    /**
     * Answer prompt [id]. A username is kept for its host for the app
     * session either way; a password or passphrase only when [remember].
     * False when the prompt is gone — answered already, or its git with it.
     */
    external fun gitAnswerPrompt(id: Long, answer: String, remember: Boolean): Boolean

    /** Refuse prompt [id]: git or ssh gives up with its own message. */
    external fun gitCancelPrompt(id: Long): Boolean

    /** Drop every username and secret remembered for the session. */
    external fun gitForgetCredentials()

    /**
     * The working tree's diff as a patch, as JSON. An empty [path] means every
     * changed file. **Blocking** — it runs git.
     */
    external fun gitPatch(projectId: Long, path: String, staged: Boolean): String

    /**
     * The branch's changes since it left [base] — the merge-base diff behind
     * "View Branch Diff", in [gitPatch]'s JSON shape. **Blocking** — it runs
     * git.
     */
    external fun gitBranchPatch(projectId: Long, base: String): String

    /**
     * A page of commit history, newest first, as JSON — `{"commits":[…]}` or
     * `{"error":…}`. [allRefs] walks every branch, remote and tag in
     * `--date-order` — the graph's view; false is the plain HEAD walk the
     * History tab shows. **Blocking** — it runs git.
     */
    external fun gitLog(projectId: Long, limit: Long, skip: Long, allRefs: Boolean): String

    /**
     * What one commit changed against its first parent, in [gitPatch]'s JSON
     * shape. An empty [path] is the whole commit; a path narrows it to one
     * file. **Blocking** — it runs git.
     */
    external fun gitCommitPatch(projectId: Long, sha: String, path: String): String

    /**
     * One commit in full: its fields, its whole message and the paths it
     * touched. **Blocking** — it runs git.
     */
    external fun gitCommitDetails(projectId: Long, sha: String): String

    /**
     * Who commits are recorded as, as JSON `{"name":…,"email":…}`. Both empty
     * when git has none — a fresh Debian guesses `root@localhost.(none)` from
     * the hostname, refuses to use it, and every commit fails until somebody
     * says who they are. **Blocking** — it runs git.
     */
    external fun gitIdentity(projectId: Long): String

    /**
     * Set that identity globally inside the guest. Null when it worked, and
     * the reason when it did not. **Blocking**.
     */
    external fun gitSetIdentity(projectId: Long, name: String, email: String): String?

    /**
     * Generation counter for a buffer's diff hunks; 0 while there is nothing to
     * show. Poll it exactly like [gitStatusVersion] — polling is what schedules
     * the diff, and it never waits for one.
     */
    external fun gitHunksVersion(bufferId: Long): Long

    /**
     * The buffer's difference from the last commit, flattened as groups of four
     * ints: kind (0 added, 1 modified, 2 deleted), first row, end row
     * (exclusive), and how many rows the commit had there. [GitHunk] wraps it.
     *
     * Rows are *buffer* rows and follow unsaved edits: only the base text comes
     * from git, and the diff against it is computed in the engine whenever the
     * buffer moves. A deletion occupies no rows — first and end row are equal,
     * and mark the boundary the rows were removed from.
     *
     * Reads a cache: takes the engine's buffer locks briefly, never runs git
     * and never blocks on one that is running; never null. Empty for a buffer
     * with no file, one outside a repository, and one that matches the commit.
     */
    external fun gitHunks(bufferId: Long): IntArray

    /**
     * The merge-conflict regions in a buffer, as JSON — see
     * [to.eyed.seeker.code.ui.editor.ConflictRegion], which parses it. Zed's
     * `ConflictSet::parse` over the live text: the `<<<<<<<`, `|||||||`,
     * `=======` and `>>>>>>>` lines git left, read from the buffer rather than
     * asked of git. Offsets are bytes, rows are 0-based, every range is
     * `{start, end}` half-open.
     *
     * Reads the whole buffer under its lock and scans it once — linear, and
     * only worth asking again when [bufferVersion] has moved, so ask it off
     * the main thread from a poll of that. `[]` for a buffer with no markers;
     * null for an unknown buffer.
     */
    external fun bufferConflicts(bufferId: Long): String?

    /**
     * Resolve the conflict whose `<<<<<<<` line is [startRow], keeping ours,
     * theirs or both — Zed's "Use HEAD" / "Use <branch>" / "Use Both". One
     * edit, one undo step. Returns the version it produced, or -1 when the row
     * no longer opens a conflict — the buffer moved under the UI — in which
     * case nothing changed.
     */
    external fun resolveConflict(
        bufferId: Long,
        startRow: Long,
        keepOurs: Boolean,
        keepTheirs: Boolean,
    ): Long

    /**
     * Who last touched each run of rows, as JSON — see [BlameLine].
     *
     * The rows are the rows of the file **on disk**. git blames what it can
     * read, and a buffer with unsaved edits has drifted from that.
     *
     * **Blocking and uncached**: it runs git every time. Ask when the user asks
     * for blame, off the main thread — never on a poll loop.
     */
    external fun gitBlame(bufferId: Long): String

    /**
     * The rows HEAD had where a hunk now is — the deleted lines an expanded
     * hunk draws — as a JSON array of strings, or null while the base text
     * is still on its way. A cache read: never runs git.
     */
    external fun gitHunkBaseLines(bufferId: Long, oldStart: Long, oldRows: Long): String?

    /**
     * The gutter's hunks with their staged bit, as JSON — see [HunkStates].
     * **Blocking**: one `git show` of the index. Ask when hunks are expanded
     * and when the status counter moved, off the main thread — never per
     * frame.
     */
    external fun gitHunkStates(bufferId: Long): String

    /**
     * Stage ([stage] true) or unstage every hunk touching buffer rows
     * `[startRow, endRow)` — Zed's `git::ToggleStaged` for one hunk, done as
     * `git apply --cached` of a one-hunk patch. Null when it worked, git's
     * own sentence when not. **Blocking**.
     */
    external fun gitHunkStage(bufferId: Long, startRow: Long, endRow: Long, stage: Boolean): String?

    /**
     * Put HEAD's rows back over every hunk touching buffer rows
     * `[startRow, endRow)` — Zed's `git::Restore`: an undoable edit of the
     * buffer, after which the editor must resync
     * ([to.eyed.seeker.code.ui.editor.EditorState.noteExternalEdit]).
     * Null when it worked. Runs git only if the base is not cached yet.
     */
    external fun gitHunkRestore(bufferId: Long, startRow: Long, endRow: Long): String?

    /** [gitHunkStates] for a project-relative path — the project diff's route. **Blocking**. */
    external fun gitPathHunkStates(projectId: Long, path: String): String

    /**
     * [gitHunkStage] by path; rows are rows of the file as it is now — the
     * `+` side of the patch — 0-based. **Blocking**.
     */
    external fun gitPathHunkStage(
        projectId: Long,
        path: String,
        startRow: Long,
        endRow: Long,
        stage: Boolean,
    ): String?

    /**
     * [gitHunkRestore] by path: an edit of the open buffer when there is one,
     * else the file on disk. **Blocking**.
     */
    external fun gitPathHunkRestore(projectId: Long, path: String, startRow: Long, endRow: Long): String?

    /**
     * `git stash list` as JSON — see [StashEntry]. Newest first. **Blocking**.
     */
    external fun gitStashList(projectId: Long): String

    /**
     * `git stash push`: [kind] is [StashKind]'s ordinal; an empty message
     * means none. Null when it worked. **Blocking**.
     */
    external fun gitStashPush(projectId: Long, kind: Int, message: String): String?

    /** `git stash pop [stash@{N}]`; a negative [index] pops the latest. **Blocking**. */
    external fun gitStashPop(projectId: Long, index: Long): String?

    /** `git stash apply [stash@{N}]`. **Blocking**. */
    external fun gitStashApply(projectId: Long, index: Long): String?

    /** `git stash drop [stash@{N}]`. **Blocking**. */
    external fun gitStashDrop(projectId: Long, index: Long): String?

    // -----------------------------------------------------------------------
    // Settings. The file is JSONC and hand-editable; writes are surgical, so
    // comments survive. All of these touch the filesystem — call them off the
    // main thread.
    // -----------------------------------------------------------------------

    /** Resolved settings as JSON. Falls back to defaults if the file is broken. */
    external fun settings(): String

    /** The settings file's raw JSONC, created with documented defaults on first use. */
    external fun settingsText(): String

    /** Whether the file parses. False means [settings] is showing defaults. */
    external fun settingsAreValid(): Boolean

    /**
     * Sets one setting. [keyPath] is dot-separated
     * (`project_panel.show_ignored`), [valueJson] is JSON (`true`, `18`,
     * `"dark"`). Returns the resolved settings as JSON, or null on failure.
     */
    external fun setSetting(keyPath: String, valueJson: String): String?

    /**
     * Replaces the whole settings file. Returns the resolved settings as JSON,
     * or null if the text doesn't parse — the file is then left untouched.
     */
    external fun setSettingsText(text: String): String?

    /**
     * Adds or replaces one `agent_servers` entry — the settings screen's Add
     * Agent form, saved. [name] goes into the file verbatim (never through
     * [setSetting]'s dot-split path, where "my.agent" would nest), and
     * [specJson] is `{"command": …, "args": […], "env": {…}}`. Returns the
     * resolved settings as JSON, or null on failure.
     */
    external fun setAgentServer(name: String, specJson: String): String?

    /**
     * Removes one `agent_servers` entry by name. Removing a name that is not
     * there succeeds — the entry is gone either way. Returns the resolved
     * settings as JSON, or null on failure.
     */
    external fun removeAgentServer(name: String): String?

    // -----------------------------------------------------------------------
    // Keymap. Zed's keymap.json, next to settings.json. The engine parses
    // and layers it; the app decides what the names mean. Both touch the
    // filesystem — call them off the main thread.
    // -----------------------------------------------------------------------

    /**
     * The keymap file's raw JSONC, created with a commented starter on first
     * use — so opening it as a tab always finds a file.
     */
    external fun keymapText(): String

    /**
     * The resolved keymap as JSON: `{"bindings": [{context, keystrokes,
     * action, args, source}…], "errors": [sentence…]}`. [defaultKeymapJson]
     * is the app's own table in keymap-file form (`DefaultKeymap.json()`),
     * whose action names are what the engine treats as existing; the base
     * keymap `settings.json` names and then the user's file are layered on
     * top, later bindings outranking earlier ones at the same context depth.
     * Never null.
     */
    external fun loadKeymap(defaultKeymapJson: String): String

    /**
     * The built-in default settings as documented JSONC text — what Zed's
     * `zed::OpenDefaultSettings` shows read-only. Never touches the disk.
     */
    external fun defaultSettingsText(): String

    /**
     * The settings in force for one buffer, every layer resolved — the user
     * file, the project's `.zed/settings.json`, and the `languages` entry
     * for the buffer's language in each — as JSON; see
     * [LanguageSettings.parse] for the keys. Never null. **Blocking** (reads
     * settings.json) — call it off the main thread.
     */
    external fun bufferLanguageSettings(bufferId: Long): String

    /**
     * Monotonic counter for a project's `.zed/settings.json`: bumped when the
     * project opens and whenever the file changes on disk. Poll it, like
     * [projectVersion], to know when to re-read [bufferLanguageSettings].
     */
    external fun projectSettingsVersion(projectId: Long): Long

    /**
     * Why the project's `.zed/settings.json` is not in effect — its parse
     * error — or null when it is, or there is none.
     */
    external fun projectSettingsError(projectId: Long): String?

    /**
     * Re-reads `.zed/settings.json` now — after this editor saved it — so
     * the save and its effect arrive together. **Blocking** — off the main
     * thread.
     */
    external fun reloadProjectSettings(projectId: Long): Boolean

    /**
     * Runs the buffer's `code_actions_on_format` kinds through its server
     * and holds their edits; same polling contract as [lspRequestFormatting],
     * and [lspApplyPendingEdit] lands them in order. Settles `done` with
     * zero edits when nothing is configured.
     */
    external fun lspRequestCodeActionsOnFormat(bufferId: Long): Long

    /**
     * Runs the buffer's external `formatter` in the userland with the buffer
     * on stdin and replaces the buffer with its stdout. JSON
     * `{changed, error}`; `error` is a sentence, null on success.
     * **Blocking** for as long as the program runs — off the main thread.
     */
    external fun formatBufferExternally(bufferId: Long): String

    /**
     * Adds or replaces one `context_servers` entry — Zed's MCP context-server
     * shape, forwarded to the agent as `mcpServers` when a thread starts.
     * [specJson] is `{"command": …, "args": […], "env": {…}}` for a server
     * the agent runs over stdio, or `{"url": …, "headers": {…}}` for an HTTP
     * one. Returns the resolved settings as JSON, or null when the spec is
     * neither shape or the write failed.
     */
    external fun setContextServer(name: String, specJson: String): String?

    /** Removes one `context_servers` entry by name; a missing name succeeds. */
    external fun removeContextServer(name: String): String?

    /**
     * Fuzzy-matches [query] against the project's files, best first, as a JSON
     * array of objects with `path`, `name`, `positions` (UTF-16 offsets into
     * `path`, for highlighting) and `score`. An empty query lists files rather
     * than matching nothing. Never null. **Blocking** — call it off the main
     * thread.
     */
    external fun projectFindFiles(projectId: Long, query: String, limit: Long): String

    /**
     * Reads a file into a new buffer, choosing the language from its name.
     * Opening the same file twice returns the *same* buffer — a file must
     * never fork into two edit histories. Returns the buffer id, or -1 if the
     * file could not be read. **Blocking** — call it off the main thread.
     */
    external fun openFile(path: String): Long

    /**
     * Bumped when a background reparse lands. The content version doesn't
     * move then, so watch this to know highlight spans are stale.
     */
    external fun bufferHighlightVersion(bufferId: Long): Long

    /**
     * The grammar the buffer is highlighted with ("rust", "markdown"), or
     * null if it has no language.
     */
    external fun bufferLanguage(bufferId: Long): String?

    /**
     * A language's whole editing config as JSON, straight from the grammar's
     * own `config.toml`:
     *
     *     {"name": "Rust", "line_comments": ["// ", "/// "],
     *      "block_comment": {"start": "/*", "end": "*/", "prefix": "* ",
     *                        "tab_size": 1},
     *      "autoclose_before": ";:.,=}])>", "hard_tabs": false,
     *      "tab_size": null, "increase_indent_pattern": null,
     *      "brackets": [{"start": "{", "end": "}", "close": true,
     *                    "surround": true, "newline": true, "not_in": []}]}
     *
     * Null for a grammar we do not carry. One call per language for the life
     * of the process — [to.eyed.seeker.code.ui.editor.EditorLanguage] caches
     * what comes back, and nothing may call this on the typing path.
     */
    external fun languageConfig(language: String): String?

    /**
     * For each byte offset, a bitmask of the bracket pairs live there: bit *i*
     * for pair *i* of [languageConfig]'s `brackets`.
     *
     * This is the half of the language config that is not data. A pair
     * carrying `not_in = ["string", "comment"]` is live or not depending on
     * where the caret sits in the *syntax tree*, and the tree is the engine's:
     * the highlight spans this side caches are style ids over the visible
     * window, they trail the text by a parse, and the character that decides
     * the answer is the one not typed yet.
     *
     * Every bit is set for an unknown buffer, for a buffer with no language,
     * and for a language whose pairs carry no `not_in` at all — which is every
     * plain bracket, so the UI never needs to ask about `(` or `{`.
     *
     * It reparses the buffer when the tree is stale, so it takes every caret's
     * offset in one call and must only be called when a pair character is
     * actually typed — never per keystroke.
     */
    external fun bufferBracketScopes(bufferId: Long, offsets: LongArray): LongArray

    /** Absolute path of the file behind a buffer; null for scratch buffers. */
    external fun bufferPath(bufferId: Long): String?

    /** Whether the buffer has edits not yet written to disk. */
    external fun bufferIsDirty(bufferId: Long): Boolean

    /**
     * Whether the file changed on disk since the buffer last synced with it.
     * Set by the worktree's file watcher; cleared by save or reload. The
     * engine only ever *flags* this — resolving it is the UI's call.
     */
    external fun bufferHasDiskChange(bufferId: Long): Boolean

    /** Whether the file behind the buffer has been deleted from disk. */
    external fun bufferFileDeleted(bufferId: Long): Boolean

    /**
     * Writes the buffer to its file. Returns the version now on disk, or -1
     * if the buffer has no file or the write failed. **Blocking** — call it
     * off the main thread.
     */
    external fun saveBuffer(bufferId: Long): Long

    /**
     * Re-reads the file into the buffer, discarding local edits. Applied as a
     * single undoable edit, so a mistaken reload is recoverable. Returns the
     * new version, or -1. **Blocking** — call it off the main thread.
     */
    external fun reloadBuffer(bufferId: Long): Long

    // -----------------------------------------------------------------------
    // The shape of the file behind a buffer: its line ending and encoding.
    // The buffer is always UTF-8 with `\n`; these say how it is written back.
    // Line endings travel as "lf" / "crlf", encodings as their WHATWG names
    // ("UTF-8", "UTF-16LE", "windows-1252"); [BufferSession] wraps both.

    /**
     * "lf" or "crlf" — the line ending the file uses and the next save
     * writes. Null for a buffer with no file.
     */
    external fun bufferLineEnding(bufferId: Long): String?

    /**
     * Choose the line ending the next save writes ("lf" or "crlf"). A change
     * marks the buffer dirty; the text is untouched. False for a buffer with
     * no file.
     */
    external fun setBufferLineEnding(bufferId: Long, lineEnding: String): Boolean

    /**
     * The encoding the file is read and written in, as JSON:
     *
     *     {"name": "UTF-8", "bom": true}
     *
     * Null for a buffer with no file.
     */
    external fun bufferEncoding(bufferId: Long): String?

    /**
     * Every encoding the engine can read and write, as a JSON array of names
     * in Zed's order — what the encoding picker lists.
     */
    external fun availableEncodings(): String

    /**
     * Choose the encoding the next save writes, keeping the text as it is.
     * A change marks the buffer dirty. [bom] only means anything for UTF-8
     * and UTF-16. False for a buffer with no file or an unknown name.
     */
    external fun setBufferEncoding(bufferId: Long, encoding: String, bom: Boolean): Boolean

    /**
     * Re-read the file decoded as [encoding] — "reopen with encoding". Local
     * edits are discarded, undoably, as with [reloadBuffer]; the buffer then
     * saves in that encoding. Returns the new version, or -1. **Blocking** —
     * call it off the main thread.
     */
    external fun reopenBufferWithEncoding(bufferId: Long, encoding: String): Long

    // -----------------------------------------------------------------------
    // Search. Both searches take the same options object, as JSON, so one
    // search bar can drive either without reshaping its state:
    //
    //     {"query": "needle", "regex": false, "case_sensitive": false,
    //      "whole_word": false, "include_ignored": false,
    //      "include_globs": [], "exclude_globs": []}
    //
    // Every field may be omitted; the last three are project-search only.
    // [SearchQuery] builds it — nothing should be spelling this out by hand.
    //
    // `whole_word` means the same thing for every kind of query: a hit counts
    // only when neither neighbouring character is a word character
    // (alphanumeric or '_'). A regex is filtered on where its match landed,
    // never rewritten, so `foo|bar` and `\w+` obey the toggle like everything
    // else does.
    //
    // Buffer search answers on the calling thread: it is one pass over a rope,
    // which is what lets the search bar re-run it on every keystroke. Project
    // search cannot answer at all — it reads thousands of files — so it runs
    // on an engine thread and publishes a counter to poll, exactly like
    // [gitStatusVersion].
    //
    // Project search silently skips four kinds of file: ones it cannot read,
    // ones over 4 MiB, ones holding a NUL byte anywhere, and ones that are not
    // valid UTF-8. They still count towards `files_searched`, so a UI that
    // says "searched 400 of 400" is telling the truth about the walk — it just
    // cannot promise a hit inside a 5 MB log or a Latin-1 file.
    // -----------------------------------------------------------------------

    /**
     * Why [queryJson] will not compile, or null if it will. Ask this to
     * explain a half-typed regex instead of silently showing no results.
     *
     * It compiles the query to find out, which a pathological pattern can drag
     * out to tens of milliseconds — so ask it when a search has *failed*, not
     * on every keystroke beside the search itself, which compiles the query
     * again. **Off the main thread** if the query is regex.
     */
    external fun searchQueryError(queryJson: String): String?

    /**
     * Every match in a buffer, flattened: element 0 is how many matches the
     * buffer holds in all, and the rest are groups of four — byte start, byte
     * end, row, byte column. When the total exceeds the groups present,
     * [limit] bit and the UI can still say "3 of 12 000".
     *
     * Null for an unknown buffer or a query that doesn't compile; ask
     * [searchQueryError] which. Wrapped by [BufferSearch].
     *
     * One pass over the whole buffer, so it costs what the buffer is big —
     * a couple of milliseconds at 100k lines. Fine on the keystroke path for
     * an ordinary file; **off the main thread** for a generated one.
     */
    external fun bufferSearch(bufferId: Long, queryJson: String, limit: Long): LongArray?

    /**
     * Starts searching a project. Returns a search id to poll with, or -1 if
     * the project is unknown or the query doesn't compile. Returns at once —
     * it only compiles the query and starts a thread.
     *
     * A project still being scanned is neither of those failures: the search
     * starts, reports [ProjectSearchState.Scanning] until the scan lands, and
     * then searches the whole tree. Results are never reported over a partly
     * scanned project, so `done` always means done over all of it.
     *
     * Starting a search cancels whatever was already running for that project,
     * so there is only ever one live id per project.
     */
    external fun projectSearchStart(projectId: Long, queryJson: String): Long

    /**
     * Generation counter for a search. Non-zero from the moment
     * [projectSearchStart] returns, so 0 means one thing only: an id the
     * engine has forgotten. Poll it exactly like [projectVersion].
     */
    external fun projectSearchVersion(searchId: Long): Long

    /**
     * Everything a search has found from [fromFile] onwards, as JSON — see
     * [ProjectSearchResults] for the shape. Results only grow, so a caller
     * holding `n` files passes `n` and gets what it is missing. Never null.
     *
     * Costs what it hands back: the engine publishes every 100 ms, so one poll
     * can carry megabytes of JSON to serialize here and parse on the Kotlin
     * side. **Call it off the main thread** — poll [projectSearchVersion],
     * which is a single load, and only read when it moves.
     */
    external fun projectSearchResults(searchId: Long, fromFile: Long): String

    /**
     * Stops a search and forgets it. False if the id is already gone. Also how
     * its results are freed: the engine holds the last search per project
     * until this is called or the project closes, which for a big result set
     * is megabytes — so a panel that closes must cancel.
     */
    external fun projectSearchCancel(searchId: Long): Boolean

    /**
     * Replaces the first hit at or after byte offset [from] — wrapping to the
     * first in the buffer — with [replacement], in one call. Three longs:
     * the buffer's new version, how many hits were rewritten (0 or 1), and
     * the byte offset just past the rewritten text, where the bar should
     * look for the next hit. Null for an unknown buffer or a query that
     * does not compile. Wrapped by [replaceNextInBuffer].
     *
     * The replacement is expanded as Zed expands it: verbatim for a literal
     * query; for a regex, `$1`, `$name` and `${name}` are capture groups and
     * `\n`, `\t`, `\\` are a newline, a tab and a backslash. Case, word
     * and regex apply exactly as they do to the search.
     *
     * One scan of the buffer and one edit, on the calling thread — the same
     * cost as [bufferSearch], and the same advice about where to run it.
     */
    external fun bufferReplaceNext(
        bufferId: Long,
        queryJson: String,
        replacement: String,
        from: Long,
    ): LongArray?

    /**
     * Replaces every hit in the buffer, as one edit and one undo step however
     * many there were. The same three longs and the same null as
     * [bufferReplaceNext]; the middle one is the count. Wrapped by
     * [replaceAllInBuffer].
     */
    external fun bufferReplaceAll(bufferId: Long, queryJson: String, replacement: String): LongArray?

    /**
     * Replaces every hit in every file the project's last search found, as
     * JSON — see [ProjectReplaceReceipt]. Open files are edited through the
     * buffer path (their editors must resync; one undo takes each back);
     * files not open are rewritten on disk, atomically, with no undo.
     *
     * Null when the project is unknown, the query does not compile, or there
     * is no *finished* search to replace over. **Blocking** — reads and
     * writes every file in the list, so call it off the main thread.
     */
    external fun projectReplaceAll(projectId: Long, queryJson: String, replacement: String): String?

    // -----------------------------------------------------------------------
    // Language servers. The engine has no LSP client of its own — it drives
    // Zed's, over the same proot the git calls go through — so a server is
    // whatever `apt` put in the Debian rootfs. Every call below therefore
    // degrades the way the git ones do: no userland, no server installed, or a
    // language nobody packages one for all report "nothing to show" rather than
    // an error, and the `play` flavour never has one at all.
    //
    // Two shapes, both already on this boundary:
    //
    //  * **Diagnostics are pushed and polled.** The server publishes when it
    //    likes; the engine caches and bumps a counter. Poll [lspVersion] per
    //    project and [bufferDiagnosticsVersion] per open tab, exactly as the
    //    panel polls [projectVersion], and read the JSON only when one moves.
    //    Polling [lspVersion] is also what starts servers for files that were
    //    already open when the userland appeared, so a project view must poll
    //    it.
    //  * **Requests are started and polled.** [lspRequestCompletion] and its
    //    two siblings return an id at once and never block. Poll
    //    [lspRequestVersion] — 1 in flight, 2 settled, 0 forgotten — then read
    //    [lspRequestResult]. Starting a request cancels the previous one *of
    //    the same kind*, which is what a completion popup re-asking on every
    //    keystroke wants; [lspRequestCancel] frees the slot when it closes.
    //
    // Positions are UTF-16 columns in both directions, like every other
    // position here ([bufferHighlights], [bufferOutlinePath]).
    // -----------------------------------------------------------------------

    /**
     * Generation counter for everything a project's language servers have
     * said: diagnostics for any of its files, and the servers' own state. 0
     * until something has. Poll it exactly like [projectVersion].
     *
     * Polling is also what *starts* servers, and opening the folder is
     * reason enough: the scanned tree's languages start their servers with
     * no tab open at all, so a workspace-wide analysis runs from the moment
     * the project is up. The same poll covers files that were open before
     * the userland arrived — `apt install clangd` in the terminal, with the
     * editor running. It never waits for a server.
     */
    external fun lspVersion(projectId: Long): Long

    /**
     * What each of the project's servers is doing, as a JSON array of
     * `{name, state, error, languages, progress}`. `state` is `starting`,
     * `running` or `unavailable`; `error` carries the server's own last line
     * of stderr when it could not be started — usually "command not found",
     * which is the user's cue to install it; `progress` is the server's own
     * one-line `$/progress` report ("indexing (45%)") or null while it is
     * quiet. Versioned by [lspVersion]. Never blocks, never null, `[]` when
     * nothing is running.
     */
    external fun lspServers(projectId: Long): String

    /**
     * Diagnostic totals for a project, as JSON: `{version, errors, warnings,
     * infos, hints, files: [{path, errors, warnings, infos, hints}]}`. Paths
     * are project-relative and `/`-separated — the spelling [projectEntries]
     * and [gitChanges] use — except for a file outside the project, which keeps
     * its absolute path. Versioned by [lspVersion]. Never blocks, never null.
     *
     * Diagnostics are **project-wide**, as Zed's are: closing a tab does not
     * retract what a server said about that file, because a workspace-wide
     * analysis (rust-analyzer's `cargo check`) is still right about it. Only an
     * empty publish from the server, or [closeProject], clears them.
     */
    external fun lspDiagnostics(projectId: Long): String

    /**
     * Every diagnostic in the project, messages included, as JSON:
     * `{version, files: [{path, rows: [{row, col_utf16, end_row, end_col_utf16,
     * severity, message, source, code}]}]}`. Paths are spelled as
     * [lspDiagnostics] spells them; rows as [bufferDiagnostics] spells them,
     * sorted by position. Versioned by [lspVersion], like the counts.
     *
     * This is the diagnostics *panel's* read, and it serializes every message
     * in the project — poll [lspVersion] and call it only when the counter
     * moves and the panel is showing. The status bar keeps to
     * [lspDiagnostics]. Never blocks, never null.
     */
    external fun lspDiagnosticRows(projectId: Long): String

    /**
     * Generation counter for one buffer's diagnostics; 0 until a server has
     * published for its file. Poll this per open tab: it is a hash lookup,
     * where [bufferDiagnostics] clones and serializes every row.
     *
     * It does **not** move when the buffer is edited — a UI must not be woken
     * by its own typing. `stale` in [bufferDiagnostics] is what says the rows
     * have drifted.
     */
    external fun bufferDiagnosticsVersion(bufferId: Long): Long

    /**
     * Everything a server has said about this buffer's file, as JSON:
     * `{version, buffer_version, stale, rows: [{row, col_utf16, end_row,
     * end_col_utf16, severity, message, source, code}]}`.
     *
     * `severity` is `error`, `warning`, `info` or `hint`, never absent — a
     * diagnostic the server left unrated counts as a warning. `source` and
     * `code` may be null. Rows are sorted by position, so painting a visible
     * window is one walk and "go to next diagnostic" is a scan.
     *
     * `buffer_version` is the buffer version the rows describe, or null when
     * the server dated them against text we no longer hold; `stale` is true
     * when the buffer has moved since. Dim the underlines rather than moving
     * them: only the server knows where they belong now.
     *
     * Reads a cache — never blocks, never null, empty for a buffer with no
     * file, no server, or nothing wrong with it.
     */
    external fun bufferDiagnostics(bufferId: Long): String

    /**
     * Asks for completions at a caret. Returns a request id to poll with; never
     * blocks and never fails — a buffer with no server behind it gets an id
     * that reports `unavailable` straight away, so the UI has one code path
     * whether or not language intelligence is installed.
     *
     * Cancels whatever completion request was already in flight, at the server
     * too, so a popup may call this on every keystroke.
     */
    external fun lspRequestCompletion(bufferId: Long, row: Long, colUtf16: Long): Long

    /** Hover documentation at a caret. Same contract as [lspRequestCompletion]. */
    external fun lspRequestHover(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * Where the symbol under the caret is defined. Same contract as
     * [lspRequestCompletion].
     */
    external fun lspRequestDefinition(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * Everywhere the symbol under the caret is used, declaration included.
     * Same contract as [lspRequestCompletion]; the payload is definition's
     * targets, each with `line_text` — the trimmed text of its line — when
     * the file could be read.
     */
    external fun lspRequestReferences(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * The code actions at a caret — quick fixes for the diagnostics under it,
     * refactorings otherwise. Same polling contract as [lspRequestCompletion];
     * the payload is `{actions: [{index, title, kind, is_preferred,
     * disabled}]}`, `disabled` being null for an action that can run and a
     * sentence when it cannot. The actions themselves stay in the engine,
     * keyed by `index`, for [lspRequestCodeActionApply] — so the request must
     * stay uncancelled until the pick is made.
     */
    external fun lspRequestCodeActions(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * Picks one action out of a settled [lspRequestCodeActions] answer and
     * readies its edit, resolving through the server when it was sent lazy.
     * Returns a new request id on the same polling contract: it settles
     * `done` with `{files, edits, resource_ops}` — or `{error}` when the
     * server offered no edit — and the edit itself waits for
     * [lspApplyPendingEdit]. A stale list id or bad index settles
     * `unavailable`.
     */
    external fun lspRequestCodeActionApply(requestId: Long, index: Long): Long

    /**
     * Renames the symbol under the caret to [newName], everywhere the server
     * knows about. Same polling contract as [lspRequestCompletion]; settles
     * `done` with `{files, edits, resource_ops}` — or `{error}` when there is
     * nothing here to rename — and **changes nothing** until
     * [lspApplyPendingEdit].
     */
    external fun lspRequestRename(bufferId: Long, row: Long, colUtf16: Long, newName: String): Long

    /**
     * Formats the whole document with the workspace's tab size. Same polling
     * contract as [lspRequestCompletion]; settles `done` with `{files, edits,
     * resource_ops}` — zero edits is a well-formatted file — and applies
     * nothing until [lspApplyPendingEdit].
     */
    external fun lspRequestFormatting(bufferId: Long): Long

    /**
     * Where the *type* of the symbol under the caret is defined — Zed's
     * `editor::GoToTypeDefinition`. [lspRequestDefinition]'s contract and
     * payload shape.
     */
    external fun lspRequestTypeDefinition(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * The implementations of the symbol under the caret — Zed's
     * `editor::GoToImplementation`. [lspRequestDefinition]'s contract and shape.
     */
    external fun lspRequestImplementation(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * The declaration of the symbol under the caret — Zed's
     * `editor::GoToDeclaration`. [lspRequestDefinition]'s contract and shape.
     */
    external fun lspRequestDeclaration(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * Inlay hints for rows `firstRow..=lastRow` — the visible range. Same
     * polling contract as [lspRequestCompletion]; the payload is `{hints:
     * [{row, col_utf16, label, kind, padding_left, padding_right}]}`, `kind`
     * being `type`, `parameter` or null. `buffer_version` echoes the version
     * asked at: drop an answer whose version is not the buffer's current
     * one, its columns describe text that moved. Supersedes the previous
     * hint request, so a scroll may ask freely.
     */
    external fun lspRequestInlayHints(bufferId: Long, firstRow: Long, lastRow: Long): Long

    /**
     * The signature of the call the caret sits in — Zed's
     * `editor::ShowSignatureHelp`. Same polling contract as
     * [lspRequestCompletion]; the payload is `{signatures: [{label,
     * documentation, parameters: [{start, end, documentation}],
     * active_parameter}], active_signature}`, `start`/`end` being UTF-16
     * offsets into the label. An empty list is "not in a call".
     */
    external fun lspRequestSignatureHelp(bufferId: Long, row: Long, colUtf16: Long): Long

    /**
     * The server's folding ranges for the whole buffer, as `{ranges:
     * [{start_row, end_row}]}` in [bufferFoldRanges]'s shape. Settles
     * `unavailable` at once for a server that does not fold — use the syntax
     * tree then. Same polling contract as [lspRequestCompletion].
     */
    external fun lspRequestFoldingRanges(bufferId: Long): Long

    /**
     * Resolve one item of a settled [lspRequestCompletion] answer by its
     * `index` — the documentation and `additionalTextEdits` a server leaves
     * out of the list. Settles `done` with `{documentation, detail,
     * additional_edits}`; when `additional_edits` is more than zero the
     * edits (an import to add) wait for [lspApplyPendingEdit], to be landed
     * *after* the completion itself is inserted. The list request must still
     * be alive.
     */
    external fun lspRequestCompletionResolve(requestId: Long, index: Long): Long

    /**
     * `workspace/symbol` across every running server of a project — Zed's
     * `project_symbols::Toggle`. Same polling contract as
     * [lspRequestCompletion] (the answer's `buffer_id` is 0); the payload is
     * `{symbols: [{name, kind, container, path, absolute_path, row,
     * col_utf16, end_row, end_col_utf16, server}]}`, `path` project-relative
     * where it can be. `unavailable` when no server is running. Supersedes
     * the previous query.
     */
    external fun lspRequestWorkspaceSymbols(projectId: Long, query: String): Long

    /**
     * The characters a buffer's server opens its menus on, from its declared
     * capabilities: `{completion: [...], signature_help: [...],
     * signature_help_retrigger: [...], folding_ranges, inlay_hints}`. Every
     * list is empty for a buffer with no running server — keep the defaults
     * then. Reads a cache; never blocks.
     */
    external fun lspBufferTriggers(bufferId: Long): String

    /**
     * Stop a project's server by name and start it again — Zed's
     * `editor::RestartLanguageServer`. False when there is no such server.
     * Never blocks; [lspVersion] and [lspServers] show it coming back.
     */
    external fun lspRestartServer(projectId: Long, name: String): Boolean

    /**
     * Stop a project's server by name and keep it stopped — Zed's
     * `editor::StopLanguageServer`. It then lists as `unavailable` with the
     * error "stopped" until [lspRestartServer] or the project closes; typing
     * in its files does not wake it. False when there is no such server.
     */
    external fun lspStopServer(projectId: Long, name: String): Boolean

    /**
     * A server's log — the last two thousand lines of its stderr, its
     * `window/logMessage`s, the RPC trace and the engine's own lifecycle
     * notes — as `{version, lines}`. `version` moves per line; poll it and
     * read only when it does. Empty for a server that never started.
     */
    external fun lspServerLogs(projectId: Long, name: String): String

    /**
     * The log's counter alone — [lspServerLogs]'s `version` without the
     * lines. Poll this; read the lines when it moves. Zero for a server
     * that never started. Never blocks.
     */
    external fun lspServerLogsVersion(projectId: Long, name: String): Long

    /**
     * Lands the edit a settled rename, formatting or code-action-apply
     * request is holding, and says what was touched: `{applied, error,
     * files: [{path, buffer_id, edits}]}`.
     *
     * Open buffers take the edits through the normal edit path — `didChange`,
     * undo history and version bumps included — and files nobody has open are
     * rewritten atomically on disk. `path` is absolute and canonical: match
     * it against open tabs, and **call `noteExternalEdit` on the editor of
     * every file whose `buffer_id` is not null** — the engine changed those
     * buffers underneath the UI. The edit is taken: a second call reports
     * "nothing to apply". **Blocking**, like [saveBuffer]; call it off the
     * main thread.
     */
    external fun lspApplyPendingEdit(requestId: Long): String

    /**
     * Generation counter for a request: 1 while in flight, 2 once settled, 0
     * for an id the engine has forgotten (superseded, cancelled, or its buffer
     * closed). Poll it exactly like [projectSearchVersion].
     */
    external fun lspRequestVersion(requestId: Long): Long

    /**
     * A request's answer, as JSON: `{id, kind, state, version, buffer_id, row,
     * col_utf16, buffer_version, payload}`.
     *
     * `kind` is `completion`, `hover`, `definition`, `references`,
     * `code_action`, `code_action_apply`, `rename` or `formatting`. `state`
     * is `pending`,
     * `done`, `timeout`, `unavailable` or `cancelled` — `done` with an empty
     * payload is a real answer ("no completions here"); the other three are
     * not, and must not be cached as one. `row`, `col_utf16` and
     * `buffer_version` echo where and when it was asked, so a late answer can
     * be dropped by a caller whose caret has moved.
     *
     * `payload` is null until it settles, and then depends on `kind`:
     *
     *  * `completion` — `{is_incomplete, items: [{label, detail, kind,
     *    insert_text, is_snippet, filter_text, sort_text, documentation,
     *    deprecated, preselect, edit}]}`. `insert_text`, `filter_text` and
     *    `sort_text` are never null (they fall back to the label);
     *    `is_snippet` means `insert_text` carries `${1:placeholder}` syntax;
     *    `edit` is `{row, col_utf16, end_row, end_col_utf16}` — the range to
     *    replace — or null, meaning the UI picks the word around the caret.
     *  * `hover` — `{contents, range}`. `contents` is markdown, `""` when the
     *    server had nothing to say; `range` has the same shape as `edit`, or is
     *    null.
     *  * `definition` — `{targets: [{path, row, col_utf16, end_row,
     *    end_col_utf16}]}`. `path` is absolute and openable with [openFile];
     *    targets in URIs that are not files are dropped rather than handed over
     *    as paths that do not exist.
     *  * `references` — definition's shape plus `line_text` per target.
     *  * `code_action` — see [lspRequestCodeActions].
     *  * `code_action_apply`, `rename`, `formatting` — `{files, edits,
     *    resource_ops}` when an edit is waiting for [lspApplyPendingEdit], or
     *    `{error}` when the server had nothing to offer.
     *
     * Never null: a forgotten id reports itself `cancelled` with a null
     * payload — and every other field of *that* answer is a placeholder,
     * `kind` included, since the caller is the one that knows what it asked
     * for. It serializes the whole answer, which for a completion list is
     * tens of kilobytes — read it when [lspRequestVersion] moves, not on a
     * timer.
     */
    external fun lspRequestResult(requestId: Long): String

    /**
     * Stops a request and forgets it — how a closed completion popup frees its
     * slot, and how the server is told to stop working on an answer nobody will
     * read. False if the id was already gone.
     */
    external fun lspRequestCancel(requestId: Long): Boolean

    // -----------------------------------------------------------------------
    // ACP agents. The engine runs an agent inside the Debian userland — one
    // process at a time, budgeted against the same 32 the terminal, git, apt
    // and the language servers share — and keeps a state machine per session.
    // It speaks the Agent Client Protocol; we do not implement the protocol on
    // either side of this boundary.
    //
    // Two shapes, both already here:
    //
    //  * **The conversation is pushed and polled.** The agent streams whenever
    //    it likes and the engine folds each update into the session, bumping a
    //    revision. Poll [acpSessionVersion] — a single load — and only when it
    //    moves read [acpSessionState] for the chrome and [acpEntriesSince] for
    //    the rows that changed. Exactly the [lspVersion] contract.
    //  * **Everything the user does returns at once.** [acpPrompt],
    //    [acpCancel] and [acpRespondPermission] hand work to the engine's
    //    connection thread and come straight back; what happened shows up
    //    behind the counter.
    //
    // The only position anything here carries is inside a tool call's diff,
    // and those are 1-based rows in the shape [gitPatch] already speaks — so
    // an agent's edit renders with the same view a commit does.
    //
    // Absent, not failing, without a userland: the `play` flavour never has
    // one, and a `full` build before Debian is installed gets a session that
    // reports itself unavailable with that sentence.
    // -----------------------------------------------------------------------

    /**
     * Starts (or joins) the agent described by [specJson] and opens a session
     * on [projectId]. Returns a session id to poll.
     *
     * [specJson] is `{"name":…,"argv":[program,…],"env":{…}}` —
     * [AgentDefinition.toSpecJson] builds it. The argv is the **guest** command
     * line, so the program must be on the userland's PATH, which is what
     * `npm install -g` puts it there for.
     *
     * A spec different from the running agent's replaces that agent; the same
     * spec joins it, so a second session costs no second process.
     *
     * Returns -1 only when the request was malformed — bad JSON, no command,
     * an unknown project — which is a bug on this side with nothing to show a
     * user. Everything a user can act on comes back as a real session whose
     * state is `unavailable` and whose `error` is the sentence to show.
     *
     * **Blocking** — it spawns a process. Call it off the main thread.
     */
    external fun acpStartSession(projectId: Long, specJson: String): Long

    /**
     * Reopens one of the agent's *own* past conversations as a new thread.
     *
     * [sessionId] is a `sessionId` from [acpSessionList]. What reopening means
     * is the agent's to decide: one that supports `session/load` replays the
     * whole conversation back as updates, so the transcript fills in by
     * itself; one that only supports `session/resume` continues it with no
     * history at all. `agent.capabilities` in [acpSessionState] says which,
     * and `canOpenHistory` says whether either is possible.
     *
     * **Blocking** — it may spawn a process. Call it off the main thread.
     */
    external fun acpResumeSession(projectId: Long, specJson: String, sessionId: String): Long

    /**
     * The agent's own past conversations — `session/list`.
     *
     * `{"version", "loading", "error", "sessions": [{sessionId, cwd, title,
     * updatedAt, …}]}`, the session objects in the protocol's own camelCase.
     * Poll it with `refresh = false` and pass `refresh = true` only when the
     * user asked — opening the threads view, or after a delete — because a
     * refresh is a round trip to the agent. Empty for an agent whose
     * `capabilities.list` is false; the view is gated on that.
     */
    external fun acpSessionList(refresh: Boolean): String

    /**
     * Version counter for [acpSessionList] — the cached list's own `version`
     * field, without serializing the list to learn it. Poll this single load
     * and make the full read only when it moves; it covers `loading` flipping
     * as well as the answer landing. 0 means no agent is running, and a
     * replaced agent never repeats a value already seen.
     */
    external fun acpSessionListVersion(): Long

    /** Forgets one of the agent's past conversations — `session/delete`. */
    external fun acpDeleteSession(sessionId: String): Boolean

    /**
     * Signs out of whatever [acpAuthenticate] signed into — `logout`.
     *
     * Does not end the open sessions: what signing out means for a
     * conversation in flight is the agent's call, and the next thing it
     * refuses arrives through the ordinary `needsAuth` path.
     */
    external fun acpLogout(): Boolean

    /**
     * Generation counter for a session; it moves whenever anything about the
     * conversation does. 0 means one thing only: an id the engine has
     * forgotten. Poll it exactly like [projectSearchVersion].
     */
    external fun acpSessionVersion(sessionId: Long): Long

    /**
     * Everything about a session except its rows, as JSON:
     *
     *     {"version":12, "project":1, "phase":"ready", "error":null,
     *      "needs_auth":false, "title":"Fixing the parser",
     *      "stop_reason":"end_turn", "entry_count":9,
     *      "plan":[{"content":…,"priority":…,"status":…}],
     *      "usage":{"used":1200,"size":200000},
     *      "modes":{"currentModeId":"default","availableModes":[…]},
     *      "commands":[…],
     *      "agent":{"name":"Claude Code","agent_name":…,"agent_version":…,
     *               "auth_methods":[…]}}
     *
     * `phase` is `starting`, `ready`, `running` or `unavailable`. `error`
     * carries the sentence to show — an agent's own last line of stderr when
     * it would not start, which is usually why. `needs_auth` means
     * [acpAuthenticate] with one of `agent.auth_methods` is the way forward.
     * `plan`, `modes` and `commands` are ACP's own shapes, camelCase and all.
     *
     * `"null"` for a forgotten id. Reads a cache; never blocks.
     */
    external fun acpSessionState(sessionId: Long): String

    /**
     * The conversation rows whose revision is newer than [since], as JSON:
     *
     *     {"revision":12, "total":9, "entries":[{"index":8, "rev":12, …}]}
     *
     * Each entry carries the `index` it sits at, so a caller merges in place
     * rather than re-reading the transcript; pass back the `revision` you were
     * last given. When `total` is smaller than what you hold, a refusal has
     * removed rows and the whole thing should be re-read from 0.
     *
     * `kind` says what a row is, and the rest depends on it:
     *
     *  * `user` — `{markdown}`.
     *  * `assistant` — `{chunks:[{thought,markdown}]}`. A `thought` chunk is
     *    the agent's reasoning and is worth folding away by default.
     *  * `tool_call` — `{id, title, tool_kind, status, options, content,
     *    locations}`. `tool_kind` is `read`/`edit`/`execute`/… — an icon, not
     *    the row's kind. `status` is `pending`, `waiting_for_confirmation`,
     *    `in_progress`, `completed`, `failed`, `rejected` or `canceled`.
     *    `options` is non-empty only while waiting, and each option is
     *    `{optionId,name,kind}` with `kind` in `allow_once`/`allow_always`/
     *    `reject_once`/`reject_always` — answer with [acpRespondPermission].
     *    `content` is a list of `{"type":"markdown","markdown":…}` and
     *    `{"type":"diff","diff":{path,original,is_binary,hunks}}`, the diff
     *    being exactly [FileDiff], so [gitPatch]'s renderer draws it.
     *    `locations` is `[{path,line}]`, project-relative where it can be.
     *
     * Never null except for a forgotten id, which gives `"null"`.
     */
    external fun acpEntriesSince(sessionId: Long, since: Long): String

    /**
     * Sends a prompt. Returns at once; the turn arrives behind the counter.
     *
     * A prompt sent while the agent is still starting, or still answering, is
     * queued **and shown immediately** — the running turn is cancelled and
     * this one follows it, which is Zed's follow-up behaviour. False for a
     * forgotten id or a session that is over.
     *
     * [mentionsJson] is a JSON array of what the user @-mentioned — the
     * tagged objects [AgentMention.toJson] writes (`{"kind": "file", "path":
     * …}`, `{"kind": "selection", …}`, `{"kind": "thread", "session": …}`,
     * …); a bare path string is still taken as a file. The engine sends each
     * as a resource block beside the text (embedded when the agent takes
     * embedded context, a link or plain text otherwise) and reads what a
     * symbol, thread or diagnostics mention points at itself. `[]` when there
     * are none; a malformed list means none rather than a lost message.
     *
     * [imagesJson] is a JSON array of `{"mime_type", "data"}` with the data
     * base64-encoded — pictures the user attached, already decoded and shrunk
     * on this side, because the engine has no image codec and is not getting
     * one. `[]` when there are none, and the same forgiving parse: rubbish
     * costs the attachment, never the message. The engine drops them anyway
     * for an agent whose `promptCapabilities.image` is false.
     */
    external fun acpPrompt(
        sessionId: Long,
        text: String,
        mentionsJson: String,
        imagesJson: String,
    ): Boolean

    /**
     * Changes one of the agent's session configuration options — model,
     * effort, whatever it advertised under `configOptions` in
     * [acpSessionState]. [valueJson] is `true`/`false` for a boolean option
     * or a JSON string (`"\"opus\""`) for a select's value id. The change
     * lands when the agent confirms it — watch the counter. False for a
     * forgotten id or a value that is neither shape.
     */
    external fun acpSetConfigOption(
        sessionId: Long,
        configId: String,
        valueJson: String,
    ): Boolean

    /**
     * Stops the running turn and any prompt queued behind it. Tool calls still
     * in flight report `canceled`, and every open permission request is
     * answered `cancelled`, as the protocol requires. False for a forgotten id.
     */
    external fun acpCancel(sessionId: Long): Boolean

    /**
     * Answers a permission request. [optionId] must be one of the ids that
     * tool call's `options` offered — anything else is refused rather than
     * guessed at, and the request stays open. False when nothing was waiting.
     *
     * [answerMetaJson] (`""` for none) becomes the response's `_meta`, and is
     * how a *question* that arrived through the permission channel is
     * answered: the option id says which choice was taken and
     * `{"spettro.app/questionAnswer":{"kind":"option","optionId":"opt-1"}}`
     * says what that choice was — the only part a free-text answer can carry.
     * Declining the whole walked form is the `cancelled` outcome plus
     * `{"spettro.app/questionAnswer":{"kind":"declined"}}`. Malformed JSON is
     * dropped and the choice still travels.
     */
    external fun acpRespondPermission(
        sessionId: Long,
        toolCallId: String,
        optionId: String,
        answerMetaJson: String,
    ): Boolean

    /**
     * The permission answer without any `_meta` — every caller that is
     * answering an ordinary Allow / Deny.
     *
     * Not `external`, and not a default argument: JNI binds by name, and a
     * second *native* method of the same name would force both to be
     * registered under their mangled signatures. An ordinary overload in
     * front of the one native declaration costs nothing and keeps the call
     * sites that have no metadata to send honest about it.
     */
    fun acpRespondPermission(sessionId: Long, toolCallId: String, optionId: String): Boolean =
        acpRespondPermission(sessionId, toolCallId, optionId, "")

    /**
     * Switches the session's mode — Claude Code's `default` / `acceptEdits` /
     * `plan`, say. The change lands when the agent confirms it, so watch the
     * counter rather than assuming. False when the session has no modes.
     */
    external fun acpSetMode(sessionId: Long, modeId: String): Boolean

    /**
     * Runs one of the agent's advertised auth methods (`agent.auth_methods` in
     * [acpSessionState]), then retries the sessions that were waiting on it.
     * False when there is no agent to authenticate with.
     */
    external fun acpAuthenticate(sessionId: Long, methodId: String): Boolean

    /**
     * Closes a session and forgets it. Closing the last one stops the agent —
     * SIGQUIT first, as proot needs, so no tracee is orphaned against
     * Android's process cap. False if the id was already gone.
     */
    external fun acpCloseSession(sessionId: Long): Boolean

    /**
     * Files the agent has written, from [since] onwards:
     * `{"total":n,"paths":[absolute,…]}`.
     *
     * The engine already flags any open buffer among them the way it flags any
     * other external change; this says *which*, so the UI can reload them with
     * [reloadBuffer] — undoably, and with highlighting and the language server
     * kept in step. Pass back the `total` you were last given.
     */
    external fun acpWrittenFiles(since: Long): String

    /**
     * One agent terminal — a command the agent asked *us* to run through
     * `terminal/create`, named by a `terminal` content block on a tool call.
     *
     * Poll it with the `revision` you were last given. An unchanged terminal
     * answers `{"revision": n}` and nothing else, so watching a build log is
     * as cheap as watching an idle one; when it has moved you get
     * `{"revision", "label", "output", "truncated", "exitStatus", "running"}`.
     * `{"revision": 0}` means the engine no longer has it: the agent released
     * it, or its session closed. The transcript keeps the tool call either
     * way — it is the live process that is gone.
     */
    external fun acpTerminalOutput(terminalId: String, since: Long): String

    /**
     * Answer one of the agent's questions — the `elicitations` in
     * [acpSessionState], which is how ACP carries every ask that is not a
     * permission: a token, a choice between branches, "open this URL and sign
     * in".
     *
     * [actionJson] is `{"action":"accept","content":{…}}`,
     * `{"action":"decline"}` or `{"action":"cancel"}`. The content's JSON
     * types are the protocol's own, so a field drawn as a switch goes back as
     * a boolean and a number field as a number — a string there would be a
     * lie the agent cannot detect.
     *
     * A URL question stays listed after an accept: the agent is watching for
     * the sign-in and takes the card away itself when it sees it. False for a
     * question that is already gone.
     */
    external fun acpRespondElicitation(elicitationId: String, actionJson: String): Boolean

    // ---- Spettro's extension ------------------------------------------
    //
    // Everything below is `_spettro/*`: the superset the one agent this app
    // ships speaks. All of it gates on `agent.spettroExtensions` in
    // [acpSessionState] being present — absent means a generic ACP agent, and
    // none of these will do anything useful.

    /**
     * Version counter for [acpPendingQuestions] — the [acpSessionVersion]
     * contract: poll this single load, read the list only when it moves.
     *
     * A question that names a session is already folded into
     * [acpSessionState] as `questions`, so the panel's ordinary poll finds it
     * without a second loop; this counter is for one raised before any
     * session exists. 0 means no agent is running, and a replaced agent never
     * repeats a value already seen.
     */
    external fun acpQuestionsVersion(): Long

    /**
     * Every open Spettro question — `_spettro/question/ask`, which carries a
     * whole ask-user form in one request rather than walking the user through
     * it one permission prompt at a time — as
     * `[{"id","session","payload"}]`.
     *
     * `payload` is the agent's own object, forwarded verbatim: `version`,
     * `sessionId`, `question`, `context`, `options[]`, `allowCustomInput`,
     * `questions[]`. The engine parses none of it, so a field the agent adds
     * next release arrives without an engine change.
     */
    external fun acpPendingQuestions(): String

    /**
     * Answers one. [answerJson] is the JSON-RPC *result* the agent receives,
     * built here — `{"answers":[…]}` for a filled form,
     * `{"kind":"declined"}` for a refusal — because the shape belongs to the
     * extension. False for a question that is already gone, and for malformed
     * JSON, in which case nothing is sent at all: an agent answered with
     * rubbish is worse off than one still waiting.
     */
    external fun acpRespondQuestion(questionId: String, answerJson: String): Boolean

    /**
     * The last `_spettro/account/update` the agent pushed, verbatim, or
     * `"null"`.
     *
     * This is the **only** way a device-flow login progresses: the agent owns
     * the two-second poller against the backend and pushes what it learns.
     * The phone polls [acpAccountVersion] and reads this; it must never poll
     * the backend itself.
     */
    external fun acpAccountStatus(): String

    /** Version counter for [acpAccountStatus]. 0 means no agent is running. */
    external fun acpAccountVersion(): Long

    /**
     * Calls one of the agent's `_spettro/…` methods — the single seam all
     * nineteen of them go through. [paramsJson] is an object (`{}` when the
     * method takes none) and travels exactly as given.
     *
     * **Blocking, up to 45 seconds** — `_spettro/providers/connect` verifies
     * the key against the provider's own API before answering. Call it on
     * `Dispatchers.IO`.
     *
     * The answer is an envelope, because there is no exception channel
     * across JNI: `{"ok":true,"result":…}`, or
     * `{"ok":false,"code":…,"message":…,"data":…}`. Two codes deserve their
     * own words: `-32601` is an older CLI, so say "update Spettro" rather
     * than "that failed", and `0` means the call never reached the wire.
     *
     * Workflow calls always pass `sessionId` and never `cwd`: an unknown
     * session id is an error you can see, whereas an omitted scope silently
     * falls back to the agent's process directory and lists the wrong repo.
     */
    external fun acpCallExtension(projectId: Long, method: String, paramsJson: String): String

    /**
     * Sends a message *into* the turn already running — **steering**.
     *
     * Not a new turn and not a cancel: the agent queues the text into the
     * turn it is in the middle of, says so with a `→ steering queued` line,
     * and keeps working. [acpPrompt] queues a whole new turn behind this one
     * instead, and [acpPromptImmediately] stops the work to make room.
     *
     * False unless a turn is actually running **and** the agent advertised
     * `spettroExtensions`: a second concurrent prompt to a generic ACP agent
     * is two turns at once, which the protocol says nothing about. Same
     * [mentionsJson] and [imagesJson] as [acpPrompt].
     */
    external fun acpSteer(
        sessionId: Long,
        text: String,
        mentionsJson: String,
        imagesJson: String,
    ): Boolean

    /**
     * Puts away the `notice` in [acpSessionState] — the one line saying why
     * the last mode or config change the user asked for did not happen.
     */
    external fun acpClearNotice(sessionId: Long): Boolean

    /**
     * Version counter for [acpPendingElicitations] — poll this single load
     * and read the list only when it moves, the [acpSessionVersion] contract.
     * It moves whenever any of the agent's questions changes, session-scoped
     * ones included. 0 means no agent is running, and a replaced agent never
     * repeats a value already seen.
     */
    external fun acpElicitationsVersion(): Long

    /**
     * The agent's questions that belong to no session — ACP's *request*
     * scope, in the same shape as `elicitations` in [acpSessionState].
     * Read it when [acpElicitationsVersion] moves.
     *
     * No session argument because there may be no session: an agent can ask
     * for a token while authenticating, before any conversation exists. One
     * of these left unanswered blocks the agent, so the panel has to show it
     * wherever it is — including over the agent picker.
     */
    external fun acpPendingElicitations(): String

    /**
     * Interrupts the running turn and sends [text] as soon as it stops.
     *
     * The deliberate version of a follow-up. [acpPrompt] **queues** one
     * instead, which is what typing while the agent works should do: killing
     * the turn to make room throws away the work it had done.
     */
    external fun acpPromptImmediately(
        sessionId: Long,
        text: String,
        mentionsJson: String,
        imagesJson: String,
    ): Boolean

    /**
     * Drops one queued prompt, by the `id` its row carries in the `queue` of
     * [acpSessionState].
     */
    external fun acpRemoveQueuedPrompt(sessionId: Long, queuedId: Long): Boolean

    // ---- checkpoints and the review ----------------------------------------
    //
    // The engine keeps what every file held before the agent's first touch
    // of it in a turn — through `fs/write_text_file`, or reported in a
    // completed tool call's diff. That is the checkpoint: nothing else the
    // agent may have done (a shell command it ran) is seen or restorable.

    /**
     * Restores the checkpoint on the user message at [entryIndex]: every
     * file edited from that turn on goes back to what it held before,
     * through the engine's write path so an open buffer reloads as it does
     * for any external edit; the rows after the message come back from
     * [acpEntriesSince] with `reverted: true`. False when the message has no
     * checkpoint (`checkpoint` on its row is what says it has one).
     */
    external fun acpRestoreCheckpoint(sessionId: Long, entryIndex: Long): Boolean

    /**
     * The review tab: every file the agent edited in the thread, diffed from
     * its earliest pre-edit text to what it holds now — `{"version",
     * "files": [{"path", "status", "created", "deleted", "diff"}]}`, with
     * `diff` in [gitPatch]'s shape, `status` `"pending"` or `"kept"`, and
     * `path` project-relative. `"null"` for a forgotten session. It reads the
     * files, so read it when [acpSessionVersion] moves, not per frame.
     */
    external fun acpEditedFiles(sessionId: Long): String

    /**
     * Keeps the agent's edits to the files in [pathsJson] — a JSON array of
     * the paths [acpEditedFiles] reports; `[]` means every file. They leave
     * the review; their checkpoints stay. False when nothing changed.
     */
    external fun acpKeepEdits(sessionId: Long, pathsJson: String): Boolean

    /**
     * Puts the files in [pathsJson] (as [acpKeepEdits] takes them) back to
     * what they held before the agent's first touch. False when there was
     * nothing to reject.
     */
    external fun acpRejectEdits(sessionId: Long, pathsJson: String): Boolean

    /**
     * Answers the first waiting permission prompt with the option of [kind]
     * — `allow_once`, `allow_always`, `reject_once` or `reject_always`; the
     * `agent::AllowOnce` family of chords. False when nothing is waiting or
     * that prompt offers no option of the kind.
     */
    external fun acpAnswerWaiting(sessionId: Long, kind: String): Boolean

    // --- Tasks and runnables ----------------------------------------------

    /**
     * Every task that resolves for the project and the editor context — the
     * project's `.zed/tasks.json`, the user's `tasks.json` and the language's
     * built-ins — as a JSON array of `{id, label, full_label, command, args,
     * command_label, cwd, env, use_new_terminal, allow_concurrent_runs,
     * reveal, hide, save, show_command, show_summary, source, tags}`, in the order
     * a fresh picker lists them. [contextJson] is `{buffer_id?, row?,
     * column?, selected_text?, runnable?: {tags, captures, run_text}}`; with
     * a `runnable` the answer is the tasks bound to its tags only.
     *
     * **Blocking** — reads two small files. Call it off the main thread.
     */
    external fun tasksList(projectId: Long, contextJson: String): String

    /**
     * Resolve one template of the UI's own — the picker's oneshot, given as a
     * `tasks.json` entry — against the same context [tasksList] uses. One
     * task object, or null when it names a variable the context lacks.
     * **Blocking**, as [tasksList] is.
     */
    external fun taskResolve(projectId: Long, contextJson: String, templateJson: String): String?

    /**
     * Every toolchain the project could use, as a JSON array of
     * `{name, path, language, source}` — Zed's `toolchain::Select` list:
     * the virtualenvs under the project, poetry's environment, rustup's
     * toolchains, and whatever `python3`/`cargo` are on the guest's PATH.
     *
     * **Blocking** — stats the project and runs a few short programs inside
     * the Debian userland. Call it off the main thread.
     */
    external fun toolchains(projectId: Long): String

    /**
     * The toolchains in force for the project, one per language, in the same
     * shape as [toolchains]. Reads a small file; never touches the userland.
     */
    external fun activeToolchains(projectId: Long): String

    /**
     * Choose a toolchain for [language] in the project, or clear it when
     * [toolchainJson] is null. Restarts the project's language servers, which
     * is what makes the interpreter take effect. **Blocking** — writes a
     * small file.
     */
    external fun setToolchain(projectId: Long, language: String, toolchainJson: String?): Boolean

    /**
     * The rows the grammar's `runnables.scm` marks — the play buttons — as a
     * JSON array of `{row, col_utf16, tags, captures, run_text, end_row}` in
     * row order. Empty for a language without runnables; null for an unknown
     * buffer. Same staleness contract as [bufferOutline]: the last parsed
     * tree, versioned by [bufferHighlightVersion].
     */
    external fun bufferRunnables(bufferId: Long): String?

    // ---- multibuffers --------------------------------------------------
    //
    // Zed's signature surface (crates/multi_buffer): excerpts of several files
    // in one editable document. The engine composes them into a *mirror*
    // buffer whose id comes back in [multibufferInfo]; render that with the
    // ordinary editor and the ordinary [applyEdit]/[undoBuffer] calls on it
    // are routed to the underlying files, so undo, `didChange` and the dirty
    // flag all happen per file.

    /**
     * Opens a multibuffer over [excerptsJson]: a JSON array of
     * `{"path", "abs", "row", "endRow"}` with 0-based rows, `abs` defaulting
     * to `root/path` and `endRow` to `row`. The engine adds two rows of
     * context around each and merges the excerpts that then touch.
     *
     * Returns its id, or -1 when not one of the files could be read.
     * **Blocking** (it opens every file it excerpts) — call it off the main
     * thread.
     */
    external fun multibufferCreate(
        title: String,
        kind: String,
        root: String,
        excerptsJson: String,
    ): Long

    /**
     * The mirror buffer to render, the headers to draw over it and how many of
     * its files are dirty, as JSON — see [MultiBufferInfo]. Null once the
     * engine has forgotten the id.
     */
    external fun multibufferInfo(multibufferId: Long): String?

    /**
     * Which file, and which row of it, a display row of the mirror shows:
     * `{"path", "absPath", "row", "header"}`. Null for a row outside every
     * excerpt.
     */
    external fun multibufferLocate(multibufferId: Long, row: Long): String?

    /**
     * Recomposes the mirror if a file behind it moved — because its own tab
     * was edited, or it was reloaded from disk. Returns the mirror's content
     * version, so the pane can poll this and only redraw when it changes; -1
     * for an unknown id.
     */
    external fun multibufferSync(multibufferId: Long): Long

    /**
     * Writes every dirty file in the multibuffer — Zed's SaveAll, which is
     * what Ctrl+S does over one. Returns
     * `{"saved": [path], "failed": ["path: reason"]}`, or null for an unknown
     * id. **Blocking** — call it off the main thread.
     */
    external fun multibufferSaveAll(multibufferId: Long): String?

    /**
     * Closes a multibuffer and releases the files it opened on demand.
     *
     * [keepBufferIds] names the buffers the caller still has tabs on, which
     * the engine cannot know; those, and any file left with unsaved edits, are
     * kept open.
     */
    external fun multibufferClose(multibufferId: Long, keepBufferIds: LongArray): Boolean

    // -----------------------------------------------------------------------
    // Workspace sessions — one JSON document per project (the pane tree, the
    // tabs with their carets and scroll, the docks, the terminal tabs) and
    // the recent-projects list. The engine owns the format and every rule
    // about putting one back; see engine/src/session.rs and
    // [WorkspaceSession]. All of these touch the filesystem: **blocking**,
    // call them off the main thread.
    // -----------------------------------------------------------------------

    /**
     * Write [documentJson] as [root]'s session. False when the JSON is not a
     * session document — the engine parses before it writes — or when the
     * write failed.
     */
    external fun sessionSave(root: String, documentJson: String): Boolean

    /**
     * [root]'s saved session, validated against the disk as it is now: a
     * file that has gone is dropped, a caret past the end of a file is
     * clamped, an emptied pane collapses. Null when there is none, or when
     * the file was corrupt, in which case it has been discarded.
     */
    external fun sessionLoad(root: String): String?

    /** Forget a project's session — what deleting the project does. */
    external fun sessionClear(root: String)

    /**
     * Note that [root] has just been opened, and return the recent list as
     * it now stands: a JSON array of `{path, name, last_opened}`, newest
     * first.
     */
    external fun noteProjectOpened(root: String): String

    /** Every project opened before, newest first, minus those since deleted. */
    external fun recentProjects(): String

    /**
     * Take [root] off the recent list — Zed's "Remove from Recent Projects".
     * The project stays on disk; its saved session goes with it. Returns the
     * new list.
     */
    external fun removeRecentProject(root: String): String
}
