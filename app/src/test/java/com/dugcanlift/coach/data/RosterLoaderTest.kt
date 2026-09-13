package com.dugcanlift.coach.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the fix for the cold-launch import race: [RosterLoader.refresh] must serialise concurrent
 * loads so a load that was issued earlier -- but happens to finish later, because it was dispatched
 * to Dispatchers.IO and the thread pool scheduled it last -- can never overwrite a result from a
 * load that was issued after it. This is exactly the shape of the bug in RosterScreen: the initial
 * `LaunchedEffect(Unit) { clients = repo.all() }` and a tap-to-import's post-import `repo.all()`
 * both start at composition time with no ordering between them, and whichever read finishes last
 * wins -- silently dropping the just-imported client if the *older* read happens to finish *last*.
 */
class RosterLoaderTest {
    private fun client(id: String) = Client(id, id, "lb", null, 0, null, emptyList())

    @Test
    fun `an earlier-started, later-finishing load cannot clobber a newer result`() = runBlocking {
        val loader = RosterLoader()
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()

        // "stale" is issued first (like the initial LaunchedEffect(Unit) load) but is held open so
        // it finishes last.
        val stale = async {
            loader.refresh {
                staleStarted.complete(Unit)
                releaseStale.await()
                listOf(client("old-roster-only"))
            }
        }

        // Don't let "fresh" start until "stale" has actually claimed the earlier generation --
        // otherwise this test would just be asserting "the only load wins", not "the later one wins".
        staleStarted.await()

        // "fresh" is issued second (like the post-import repo.all()) and finishes immediately.
        val fresh = async {
            loader.refresh { listOf(client("old-roster-only"), client("just-imported")) }
        }
        val freshResult = fresh.await()

        // Now let the earlier, slower load finish. It must not be allowed to win.
        releaseStale.complete(Unit)
        stale.await()

        assertEquals(listOf("old-roster-only", "just-imported"), freshResult.map { it.id })
        assertEquals(listOf("old-roster-only", "just-imported"), loader.current.map { it.id })
    }

    @Test
    fun `a load issued after a completed one always wins`() = runBlocking {
        val loader = RosterLoader()
        loader.refresh { listOf(client("first")) }
        loader.refresh { listOf(client("first"), client("second")) }

        assertEquals(listOf("first", "second"), loader.current.map { it.id })
    }
}
