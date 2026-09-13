package com.dugcanlift.coach.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises concurrent roster loads so a load that was issued earlier can never overwrite a
 * result from one issued later, no matter which finishes first.
 *
 * Moving the roster load off the main thread (`Dispatchers.IO`) removed an ordering guarantee the
 * old synchronous code had for free: on a cold launch through an App Link or the share sheet,
 * `RosterScreen`'s first composition starts two unordered writers of `clients` -- the initial
 * `LaunchedEffect(Unit)` load and the tap-to-import's own post-import reload. Both reads race on
 * the IO dispatcher's thread pool with no relationship between "started first" and "finishes
 * first"; if the initial load happens to finish *after* the post-import one, it silently
 * overwrites the just-imported client with the pre-import roster, while the snackbar still says
 * the import succeeded.
 *
 * The fix is not to go back to main-thread I/O -- it's to make the *result* ordering match the
 * *issue* ordering. Each call to [refresh] claims a strictly increasing generation number before
 * suspending to do the actual load; when the load completes, its result is only applied if no
 * higher generation has already been applied. A load issued first can finish last and simply be
 * discarded -- it can never clobber a newer one. This needs no true one-at-a-time queuing of the
 * I/O itself (loads can still run concurrently), only serialised bookkeeping around which result
 * is allowed to win, which is what the two `withLock` sections below do.
 */
class RosterLoader {
    private val lock = Mutex()
    private var nextGeneration = 0L
    private var appliedGeneration = 0L

    /** The most recently *applied* load result -- see [refresh]. Starts empty, before any load. */
    var current: List<Client> = emptyList()
        private set

    /**
     * Runs [load], then applies its result only if no load issued after this call has already been
     * applied. Always returns the roster this call caused to be current -- which is [current] at
     * the moment this call's bookkeeping runs, so a caller that lost the race still observes
     * whichever newer result won, rather than its own stale read.
     */
    suspend fun refresh(load: suspend () -> List<Client>): List<Client> {
        val generation = lock.withLock { ++nextGeneration }
        val result = load()
        lock.withLock {
            if (generation > appliedGeneration) {
                appliedGeneration = generation
                current = result
            }
            return current
        }
    }
}
