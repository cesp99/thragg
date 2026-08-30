package to.eyed.seeker.code.ui.git

import android.content.Context
import android.content.SharedPreferences

/**
 * Commit state that must outlive more than the composition.
 *
 * The three objects below started as in-memory maps because the panel is a
 * composable that gets *removed* — Escape, a compact screen opening a file.
 * But on Android the analogous event is not panel close: it is the OS killing
 * the backgrounded process, which is routine — switching to a browser to copy
 * an error message mid-commit-message is enough. Zed serializes exactly this
 * state (`SerializedCommitMessage` with `amend_pending` and `signoff_enabled`,
 * git_panel.rs:489-504, 1466-1558), so ours writes through to
 * [SharedPreferences]: the maps stay the API and the source of truth for the
 * running process, and every write also lands on disk.
 *
 * Keyed on disk by the project's *root path*, never its id: the id is an
 * engine handle minted per [to.eyed.seeker.code.core.CoreBridge.openProject]
 * call, worthless across the very process death this file exists for. The id
 * → path binding is [GitDraftStore.bind]'s job, done when the panel composes.
 *
 * Main thread only, like the composition that owns it; the disk half is an
 * `apply()`, which never blocks the caller.
 */

/**
 * The storage seam: what [GitDraftStore] needs from a place drafts survive
 * in. An interface so the wiring — seeding, write-through, the id→path
 * binding — is testable on the JVM, where [SharedPreferences] is a stub.
 */
internal interface DraftBackend {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** [SharedPreferences] wearing the seam. */
private class PrefsBackend(private val prefs: SharedPreferences) : DraftBackend {
    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * The disk half of [CommitDrafts], [AmendDrafts] and [CommitToggles]: which
 * backend to write through to, and which prefs key each project id stands
 * for. Nothing here is read on a hot path — the objects' maps answer reads —
 * so a write is one string put and a bind is at most three gets.
 */
internal object GitDraftStore {
    private const val PREFS = "git_commit_drafts"
    private const val SIGNOFF = "signoff"

    private var backend: DraftBackend? = null

    /** The prefs key each bound project id writes through to. */
    private val roots = mutableMapOf<Long, String>()

    /** Signoff is seeded once per process; after that memory is fresher. */
    private var signoffSeeded = false

    /**
     * Wire a project up: remember its id→path binding and seed the in-memory
     * maps from disk — memory wins where it already has an answer, because a
     * live process is always fresher than its own last write. Called from the
     * panel's composition, before anything reads a draft.
     */
    fun bind(context: Context, project: Long, rootPath: String) {
        val store = backend
            ?: PrefsBackend(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            )
        attach(store, project, rootPath)
    }

    /** [bind]'s wiring with the backend handed in — the half a JVM test can run. */
    internal fun attach(store: DraftBackend, project: Long, rootPath: String) {
        backend = store
        roots[project] = rootPath
        CommitDrafts.seed(project, store.get("draft:$rootPath")?.takeIf { it.isNotEmpty() })
        AmendDrafts.seed(project, store.get("amend:$rootPath"))
        if (!signoffSeeded) {
            signoffSeeded = true
            CommitToggles.seedSignoff(store.get(SIGNOFF) != null)
        }
    }

    /** Write one project-keyed value through; null removes. No-op unbound. */
    internal fun write(kind: String, project: Long, value: String?) {
        val store = backend ?: return
        val root = roots[project] ?: return
        val key = "$kind:$root"
        if (value == null) store.remove(key) else store.put(key, value)
    }

    /** Signoff is one global flag, present-when-on — Zed's `signoff_enabled`. */
    internal fun writeSignoff(on: Boolean) {
        val store = backend ?: return
        if (on) store.put(SIGNOFF, "1") else store.remove(SIGNOFF)
    }

    /** Test-only: forget the wiring, the way a fresh process has. */
    internal fun reset() {
        backend = null
        roots.clear()
        signoffSeeded = false
    }
}

/**
 * Commit messages typed but not yet committed, one per project.
 *
 * The panel's own composition cannot be where a half-written commit message
 * lives — that is the one thing here nobody will retype — and neither can
 * process memory alone, which is why every write goes through
 * [GitDraftStore]. Kept beside the panel rather than hoisted into the
 * workspace because nothing else reads it, and touched only from the main
 * thread, like the composition that owns it.
 */
internal object CommitDrafts {
    private val drafts = mutableMapOf<Long, String>()

    fun of(project: Long): String = drafts[project] ?: ""

    fun put(project: Long, message: String) {
        if (message.isEmpty()) drafts.remove(project) else drafts[project] = message
        GitDraftStore.write("draft", project, message.takeIf { it.isNotEmpty() })
    }

    /** After a commit that landed: that message has done its job. */
    fun clear(project: Long) {
        drafts.remove(project)
        GitDraftStore.write("draft", project, null)
    }

    /** A draft read back from disk; the live map wins when it has one. */
    internal fun seed(project: Long, message: String?) {
        if (message != null && project !in drafts) drafts[project] = message
    }
}

/**
 * A pending amend, one per project, outliving the composition and the process
 * as [CommitDrafts] does — Zed keeps `amend_pending` and the pre-amend
 * `original_message` per work directory and restores both on load
 * (`SerializedCommitMessage`, git_panel.rs:496-504, 1541-1558). Presence in
 * the map *is* the pending flag; the value is the draft the amend displaced,
 * put back when the amend is cancelled or lands. Main thread only, like the
 * composition that reads it.
 */
internal object AmendDrafts {
    private val originals = mutableMapOf<Long, String>()

    fun pending(project: Long): Boolean = project in originals

    fun original(project: Long): String = originals[project] ?: ""

    fun enter(project: Long, original: String) {
        originals[project] = original
        GitDraftStore.write("amend", project, original)
    }

    fun clear(project: Long) {
        originals.remove(project)
        GitDraftStore.write("amend", project, null)
    }

    /**
     * A pending amend read back from disk — the stored value may be the empty
     * string, because an amend entered over an empty box displaces "". The
     * live map wins when it already holds one.
     */
    internal fun seed(project: Long, original: String?) {
        if (original != null && project !in originals) originals[project] = original
    }
}

/**
 * The split button's other two toggles. Signoff keeps its setting the way a
 * draft keeps its words — Zed serializes `signoff_enabled` and never resets it
 * after a commit (git_panel.rs:489-494, 1466-1495) — so it writes through and
 * is seeded back on the first bind. Skip Hooks is deliberately weaker: never
 * persisted, and *spent* — reset to false — by every commit that lands
 * (git_panel.rs:3131, 8059-8064), because `--no-verify` is a decision about
 * one commit, not a policy.
 */
internal object CommitToggles {
    private var signoffValue = false

    var signoff: Boolean
        get() = signoffValue
        set(value) {
            signoffValue = value
            GitDraftStore.writeSignoff(value)
        }

    var skipHooks: Boolean = false

    /** The stored flag, installed without writing it straight back. */
    internal fun seedSignoff(value: Boolean) {
        signoffValue = value
    }
}
