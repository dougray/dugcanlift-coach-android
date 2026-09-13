package com.dugcanlift.coach.data
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClientRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun client(id: String, vararg days: TrainingDay) = Client(id, "Doug", "lb", "and", 0, null, days.toList())
    private fun day(key: String, sets: List<ExerciseSet> = emptyList()) = TrainingDay(key, null, null, null, null, null, null, null, null, null, sets, emptyList())

    @Test fun `saves and reloads a client with days and sets`() {
        val repo = ClientRepository(tmp.root)
        repo.save(client("a1", day("2026-09-10", listOf(ExerciseSet("Back Squat", "Barbell", 225.0, 5, 8.0, null, null, false)))))
        val back = ClientRepository(tmp.root).get("a1")!!
        assertEquals(1, back.days.size); assertEquals(225.0, back.days[0].sets[0].weightLb!!, 0.0)
    }
    @Test fun `each client is its own file so saving one never touches another`() {
        val repo = ClientRepository(tmp.root); repo.save(client("a1")); repo.save(client("b2"))
        assertEquals(setOf("a1.json", "b2.json"), tmp.root.resolve("clients").list()!!.toSet())
    }
    @Test fun `days since last LOGGED day ignores empty days and import time`() {
        val c = client("a1", day("2026-09-01", listOf(ExerciseSet("Row", null, null, 10, null, null, null, false))), day("2026-09-12"))
        assertEquals(12, c.daysSinceLastLoggedDay(today = "2026-09-13"))
    }
    @Test fun `a client who never logged is null not zero`() = assertNull(client("a1").daysSinceLastLoggedDay("2026-09-13"))
    @Test fun `a corrupt file is skipped rather than crashing the roster`() {
        val repo = ClientRepository(tmp.root); repo.save(client("a1"))
        tmp.root.resolve("clients/bad.json").writeText("{not json")
        assertEquals(listOf("a1"), repo.all().map { it.id })
    }
    @Test fun `a save whose move cannot succeed throws rather than silently reporting success`() {
        val repo = ClientRepository(tmp.root)
        // Occupy the destination path with a directory: on every POSIX and NTFS filesystem,
        // moving a regular file onto a directory path fails (EISDIR / access denied) --
        // no permission bits needed, so this is deterministic across CI and local runs alike,
        // unlike the old File.renameTo(...) call whose failure this reproduces, which returned
        // false and was ignored instead of throwing.
        tmp.root.resolve("clients/a1.json").mkdirs()
        try {
            repo.save(client("a1"))
            fail("expected save() to throw when the destination cannot be replaced")
        } catch (e: Exception) {
            // expected -- a genuine failure propagates instead of being swallowed
        }
    }
}
