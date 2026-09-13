package com.dugcanlift.coach.data

import com.dugcanlift.kit.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round 6 [I-7]: `import` is read-modify-write over the whole client file, and RosterScreen
 * dispatches one per user action -- a pasted link importing while a share-sheet link arrives gives
 * two coroutines the same `existing`, each merging its own days onto it, each writing the whole
 * file. `Files.move` makes each write atomic and does nothing about the lost update.
 *
 * The interleaving is forced rather than hoped for: the first read into the repository is slow, so
 * without mutual exclusion the second import completes entirely inside it and is then overwritten.
 * With the import serialised, the second import cannot begin until the first has saved, and reads
 * the first import's day.
 */
class ShareLinkImporterConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()

    private class SlowFirstReadRepository(root: File) : ClientRepository(root) {
        private val reads = AtomicInteger(0)
        // The delay lands AFTER the read, which is what makes the stale read stale: the first
        // importer holds a client it read before the second importer's save, exactly as a slow
        // device does between `repo.get` and `repo.save`.
        override fun get(id: String): Client? {
            val read = super.get(id)
            if (reads.getAndIncrement() == 0) Thread.sleep(400)
            return read
        }
    }

    private fun link(dayOffset: Int) = ShareLinkCodec.encodeFragment(
        SharePayload(
            ShareClient("a1b2c3d4", "Doug", platform = "and"), null, "2026-09-01", "2026-09-13", 1,
            listOf(ShareDay(dayOffset, null, null, 180.0, null, emptyList(), null, null))
        )
    )

    @Test fun `two imports racing for the same client cannot lose one another's days`() {
        val repo = SlowFirstReadRepository(tmp.root)
        val first = thread { ShareLinkImporter.import(link(1), repo) }
        Thread.sleep(100)   // guarantee `first` owns the slow read
        val second = thread { ShareLinkImporter.import(link(2), repo) }
        first.join(5_000)
        second.join(5_000)

        assertEquals(
            "neither import's day may be lost",
            listOf("2026-09-02", "2026-09-03"),
            repo.get("a1b2c3d4")!!.days.map { it.dayKey }.sorted()
        )
    }
}
