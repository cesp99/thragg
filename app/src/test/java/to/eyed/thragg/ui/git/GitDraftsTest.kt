package to.eyed.thragg.ui.git

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The write-through and the seeding: a draft typed before process death must
 * come back keyed by the project's *path* — its id is an engine handle a new
 * process mints afresh — and a live map must never be stamped over by its own
 * older write. The backend here is the [DraftBackend] seam; on device it is
 * SharedPreferences wearing it.
 */
class GitDraftsTest {

    /** SharedPreferences in test clothes: a string map. */
    private class FakeBackend : DraftBackend {
        val stored = mutableMapOf<String, String>()

        override fun get(key: String): String? = stored[key]

        override fun put(key: String, value: String) {
            stored[key] = value
        }

        override fun remove(key: String) {
            stored.remove(key)
        }
    }

    private val backend = FakeBackend()

    @Before
    fun bind() {
        GitDraftStore.reset()
        GitDraftStore.attach(backend, project = 7L, rootPath = "/projects/app")
    }

    @After
    fun unbind() {
        // The maps are process-global; a test must not leak its drafts into
        // the next one's seeding.
        CommitDrafts.clear(7L)
        CommitDrafts.clear(8L)
        AmendDrafts.clear(7L)
        CommitToggles.seedSignoff(false)
        GitDraftStore.reset()
    }

    @Test
    fun aDraftWritesThroughKeyedByPathAndClearsThrough() {
        CommitDrafts.put(7L, "Fix the parser")
        assertEquals("Fix the parser", backend.stored["draft:/projects/app"])
        CommitDrafts.clear(7L)
        assertNull(backend.stored["draft:/projects/app"])
        // Emptying the box is the same promise as clearing.
        CommitDrafts.put(7L, "typed")
        CommitDrafts.put(7L, "")
        assertNull(backend.stored["draft:/projects/app"])
    }

    @Test
    fun aStoredDraftComesBackInAFreshProcessUnderANewId() {
        CommitDrafts.put(7L, "half a message")
        // Process death: the maps empty, the ids are minted anew — only the
        // path survives.
        CommitDrafts.clear(7L)
        backend.put("draft:/projects/app", "half a message")
        GitDraftStore.reset()
        GitDraftStore.attach(backend, project = 21L, rootPath = "/projects/app")
        assertEquals("half a message", CommitDrafts.of(21L))
        CommitDrafts.clear(21L)
    }

    @Test
    fun theLiveDraftOutranksItsOwnOlderWrite() {
        CommitDrafts.put(7L, "newer words")
        backend.put("draft:/projects/app", "older words")
        // A rebind — the panel reopening — must not stamp disk over memory.
        GitDraftStore.attach(backend, project = 7L, rootPath = "/projects/app")
        assertEquals("newer words", CommitDrafts.of(7L))
    }

    @Test
    fun aPendingAmendSurvivesWithItsDisplacedOriginalEmptyIncluded() {
        // An amend entered over an empty box displaces "" — presence is the
        // flag, and the empty original must restore as empty, not as absent.
        AmendDrafts.enter(7L, "")
        assertEquals("", backend.stored["amend:/projects/app"])
        AmendDrafts.clear(7L)
        backend.put("amend:/projects/app", "the displaced draft")
        GitDraftStore.reset()
        GitDraftStore.attach(backend, project = 33L, rootPath = "/projects/app")
        assertTrue(AmendDrafts.pending(33L))
        assertEquals("the displaced draft", AmendDrafts.original(33L))
        AmendDrafts.clear(33L)
        assertNull(backend.stored["amend:/projects/app"])
    }

    @Test
    fun signoffPersistsAndSkipHooksNever() {
        CommitToggles.signoff = true
        assertEquals("1", backend.stored["signoff"])
        CommitToggles.skipHooks = true
        assertFalse(backend.stored.keys.any { it.contains("skip") })
        CommitToggles.signoff = false
        assertNull(backend.stored["signoff"])
        CommitToggles.skipHooks = false
    }

    @Test
    fun signoffSeedsOncePerProcessFromTheStoredFlag() {
        backend.put("signoff", "1")
        GitDraftStore.reset()
        GitDraftStore.attach(backend, project = 7L, rootPath = "/projects/app")
        assertTrue(CommitToggles.signoff)
        // The user turns it off; a second bind — another panel open — must
        // not seed a stale flag back over the choice. The stray key stands
        // in for any write the process has since outrun.
        CommitToggles.signoff = false
        backend.put("signoff", "1")
        GitDraftStore.attach(backend, project = 7L, rootPath = "/projects/app")
        assertFalse(CommitToggles.signoff)
        backend.remove("signoff")
    }

    @Test
    fun anUnboundProjectStaysPureMemory() {
        // A project the panel has not bound — tests included — writes
        // nothing through: there is no path to key its draft by.
        CommitDrafts.put(8L, "unbound")
        assertEquals("unbound", CommitDrafts.of(8L))
        assertTrue(backend.stored.values.none { it == "unbound" })
    }
}
