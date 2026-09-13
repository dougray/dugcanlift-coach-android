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

    // --- Round 6 [I-3]: replaceAll must never destroy the roster it is replacing before the new
    // one is safely on disk. A day carrying a non-finite number cannot be serialised (org.json
    // refuses NaN on the JVM and on Android alike), which is a faithful stand-in for the full-disk
    // IOException the review's scenario hits on client four of ten.
    private fun unserialisableClient(id: String) = Client(id, "Doug", "lb", "and", 0, null,
        listOf(TrainingDay("2026-09-10", null, null, null, null, Double.NaN, null, null, null, null, emptyList(), emptyList())))

    @Test fun `a replaceAll that fails partway leaves the previous roster intact`() {
        val repo = ClientRepository(tmp.root)
        repo.save(client("a1")); repo.save(client("b2")); repo.save(client("c3"))
        try {
            repo.replaceAll(listOf(client("n1"), client("n2"), unserialisableClient("n3"), client("n4")))
            fail("expected replaceAll to fail rather than half-replace the roster")
        } catch (e: Exception) {
            // expected -- the caller is told the restore failed, and the old roster is still there
        }
        assertEquals(setOf("a1", "b2", "c3"), repo.all().map { it.id }.toSet())
    }

    // --- Round 6 [I-4]: a file all() cannot read is not an absent client. replaceAll is the step
    // that turns "invisible" into "permanently destroyed", so it must keep what it cannot read.
    @Test fun `replaceAll preserves a client file it cannot read instead of deleting it`() {
        val repo = ClientRepository(tmp.root)
        repo.save(client("a1"))
        tmp.root.resolve("clients/bad.json").writeText("{not json")
        repo.replaceAll(listOf(client("z9")))
        assertEquals(listOf("z9"), repo.all().map { it.id })
        assertTrue("an unreadable file must be kept, never deleted", tmp.root.resolve("unreadable/bad.json").exists())
    }

    // --- Round 6 [I-8]: c.i comes off an untrusted link with no charset constraint in
    // SHARE-FORMAT.md, and goes straight into File(dir, "$id.json").
    @Test fun `an id with a path separator stays inside the clients directory and still reads back`() {
        val root = tmp.newFolder("store")
        val repo = ClientRepository(root)
        repo.save(client("../../escape"))
        repo.save(client("a/b"))
        assertEquals(2, root.resolve("clients").listFiles()!!.size)
        assertEquals("../../escape", repo.get("../../escape")!!.id)
        assertEquals("a/b", repo.get("a/b")!!.id)
        assertEquals(setOf("../../escape", "a/b"), repo.all().map { it.id }.toSet())
        assertFalse("nothing may be written outside clients/", tmp.root.resolve("escape.json").exists())
    }

    @Test fun `an ordinary id keeps its plain filename so already-stored clients stay readable`() {
        val repo = ClientRepository(tmp.root)
        repo.save(client("a1b2c3d4"))
        assertTrue(tmp.root.resolve("clients/a1b2c3d4.json").exists())
        assertEquals("a1b2c3d4", repo.get("a1b2c3d4")!!.id)
    }

    // --- Round 6 [I-4]: the roster must keep skipping a file it cannot read, but anything that
    // WRITES the roster out has to know that happened -- export is sourced from all().
    @Test fun `load reports the files it could not read while all still skips them`() {
        val repo = ClientRepository(tmp.root)
        repo.save(client("a1"))
        tmp.root.resolve("clients/bad.json").writeText("{not json")
        val stored = repo.load()
        assertEquals(listOf("a1"), stored.clients.map { it.id })
        assertEquals(listOf("bad.json"), stored.unreadableFiles)
        assertEquals(listOf("a1"), repo.all().map { it.id })
    }
}
