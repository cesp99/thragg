package to.eyed.seeker.code.ui.git

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.seeker.code.core.Commit
import to.eyed.seeker.code.core.CommitPage

/**
 * The graph pager's interleavings — the ones the pane cannot help producing:
 * a page fetch is a slow `git log` under proot, and the version poll's reload
 * can land while one is in flight. `runBlocking` is single-threaded, so each
 * `yield` is a deliberate handover and the interleaving is exact.
 */
class GraphPagingTest {

    private fun commit(sha: String) = Commit(
        sha = sha,
        parents = emptyList(),
        author = "Carlo",
        authorEmail = "carlo@example.com",
        authorTime = 0L,
        subject = "s",
        refs = emptyList(),
    )

    private fun page(vararg shas: String) = CommitPage(commits = shas.map { commit(it) })

    private fun shas(paging: GraphPaging) = paging.commits.map { it.sha }

    /** The bug the guard exists for: a reload emptied the list while a
     * mid-history page was in flight, and the stale page came back *first* —
     * appending it drew history with its newest commits below older ones. */
    @Test
    fun aResetStrandsTheInFlightPage(): Unit = runBlocking {
        val paging = GraphPaging()
        val gate = CompletableDeferred<CommitPage>()
        val stale = launch { paging.loadMore { gate.await() } }
        yield() // the stale fetch is now suspended mid-flight
        paging.reset()
        paging.loadMore { page("b1", "b2") } // the reload's fresh page zero
        gate.complete(page("a100", "a101")) // the stale page lands late
        stale.join()
        assertEquals(listOf("b1", "b2"), shas(paging))
        assertFalse(paging.loading)
        assertFalse(paging.exhausted)
    }

    /** The stale call's *error* must not poison the fresh list either — it
     * used to set `error` and `exhausted = true`, killing paging for a list
     * it was never about. */
    @Test
    fun aStaleErrorDoesNotKillTheFreshList(): Unit = runBlocking {
        val paging = GraphPaging()
        val gate = CompletableDeferred<CommitPage>()
        val stale = launch { paging.loadMore { gate.await() } }
        yield()
        paging.reset()
        paging.loadMore { page("b1") }
        gate.complete(CommitPage(error = "fatal: bad object"))
        stale.join()
        assertNull(paging.error)
        assertFalse(paging.exhausted)
        assertEquals(listOf("b1"), shas(paging))
    }

    /** One page at a time: a second call while one is in flight is a no-op
     * rather than a second `git log` racing the first to the append. */
    @Test
    fun onlyOnePageFliesAtATime(): Unit = runBlocking {
        val paging = GraphPaging()
        val gate = CompletableDeferred<CommitPage>()
        var fetches = 0
        val first = launch {
            paging.loadMore {
                fetches += 1
                gate.await()
            }
        }
        yield()
        paging.loadMore {
            fetches += 1
            page("x")
        }
        assertEquals("the overlapping call must not fetch", 1, fetches)
        gate.complete(page("a"))
        first.join()
        assertEquals(listOf("a"), shas(paging))
    }

    @Test
    fun pagesAppendInOrderAndDeduplicate(): Unit = runBlocking {
        val paging = GraphPaging()
        paging.loadMore { skip ->
            assertEquals(0, skip)
            page("a", "b")
        }
        paging.loadMore { skip ->
            // The next page is asked for from where the list ends.
            assertEquals(2, skip)
            // A commit made meanwhile shifted the window: "b" arrives again.
            page("b", "c")
        }
        assertEquals(listOf("a", "b", "c"), shas(paging))
        assertFalse(paging.exhausted)
    }

    /** An empty page — or one that is entirely commits already seen — is the
     * end of history, not a page to wait on forever. */
    @Test
    fun anEmptyOrFullySeenPageExhausts(): Unit = runBlocking {
        val paging = GraphPaging()
        paging.loadMore { page("a") }
        paging.loadMore { CommitPage() }
        assertTrue(paging.exhausted)

        val rewritten = GraphPaging()
        rewritten.loadMore { page("a") }
        rewritten.loadMore { page("a") }
        assertTrue(rewritten.exhausted)
        assertEquals(listOf("a"), shas(rewritten))
    }

    /** An error ends paging and says why; a reset clears both and loads anew. */
    @Test
    fun anErrorExhaustsAndAResetForgivesIt(): Unit = runBlocking {
        val paging = GraphPaging()
        paging.loadMore { CommitPage(error = "fatal: not a git repository") }
        assertEquals("fatal: not a git repository", paging.error)
        assertTrue(paging.exhausted)

        paging.reset()
        assertNull(paging.error)
        assertTrue("a fresh list reads as loading until its first page", paging.loading)
        paging.loadMore { page("a") }
        assertEquals(listOf("a"), shas(paging))
    }
}
