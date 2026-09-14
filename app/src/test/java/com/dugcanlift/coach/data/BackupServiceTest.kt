package com.dugcanlift.coach.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round 6 [I-2]/[C-1]/[I-4]: Connect's two file-picker callbacks are Composable lambdas, which
 * this repo has no harness for -- so the coordination they used to do inline lives in
 * [BackupService], the same "extract it until it can be tested" pattern `RosterLoader` set. These
 * tests pin the two things that callback could not be asked about: that the work happens off the
 * caller's thread, and that an export refuses rather than silently writing an incomplete backup.
 */
class BackupServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun client(id: String) = Client(id, "Doug", "lb", "and", 0, null, emptyList())
    private fun libraryFile() = File(tmp.root, "preserved-library.json")
    private fun repo() = ClientRepository(tmp.root)
    private fun cook() = CookRepository(tmp.root)
    private fun train() = TrainRepository(tmp.root)
    private fun service(repo: ClientRepository = repo(), cook: CookRepository = cook(),
                        train: TrainRepository = train()) =
        BackupService(repo, libraryFile(), cook, train)

    @Test fun `a healthy roster exports and still reports Backup saved`() = runBlocking {
        val repo = repo()
        repo.save(client("a1"))
        val out = ByteArrayOutputStream()
        val outcome = service(repo).export { out }
        assertEquals("Backup saved.", outcome.message)
        assertFalse(outcome.isError)
        assertEquals(1, JSONObject(out.toString()).getJSONArray("clients").length())
    }

    @Test fun `an unreadable client file stops the export instead of quietly leaving that client out`() = runBlocking {
        val repo = repo()
        repo.save(client("a1"))
        tmp.root.resolve("clients/bad.json").writeText("{not json")
        var opened = false
        val outcome = service(repo).export { opened = true; ByteArrayOutputStream() }
        assertTrue(outcome.isError)
        assertTrue(outcome.message.startsWith("Couldn't create a backup:"))
        assertFalse("nothing may be written when the export is incomplete", opened)
    }

    @Test fun `an unreadable library cache stops the export instead of writing a backup with no recipes`() = runBlocking {
        val repo = repo()
        repo.save(client("a1"))
        libraryFile().writeText("{\"recipes\": [{\"id\": \"A\"")   // killed mid-write
        var opened = false
        val outcome = service(repo).export { opened = true; ByteArrayOutputStream() }
        assertTrue(outcome.isError)
        assertTrue(outcome.message.contains("recipes and routines"))
        assertFalse("nothing may be written when the library can't be read", opened)
    }

    @Test fun `export does its reading and writing off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        var worker: Thread? = null
        service().export { worker = Thread.currentThread(); ByteArrayOutputStream() }
        assertNotNull(worker)
        assertNotSame("the SAF stream must be opened off the main thread too", caller, worker)
    }

    @Test fun `restore does its reading and writing off the calling thread`() = runBlocking {
        val caller = Thread.currentThread()
        var worker: Thread? = null
        service().restore { worker = Thread.currentThread(); ByteArrayInputStream(BackupCodec.export(emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList()).toByteArray()) }
        assertNotNull(worker)
        assertNotSame(caller, worker)
    }

    @Test fun `a file that is not a backup leaves the roster alone and says so`() = runBlocking {
        val repo = repo()
        repo.save(client("a1"))
        val outcome = service(repo).restore { ByteArrayInputStream("not a backup".toByteArray()) }
        assertEquals("That doesn't look like a valid backup file.", outcome.message)
        assertTrue(outcome.isError)
        assertEquals(listOf("a1"), repo.all().map { it.id })
    }

    @Test fun `a restore reports the clients it restored`() = runBlocking {
        val repo = repo()
        repo.save(client("old"))
        val backup = BackupCodec.export(listOf(client("n1"), client("n2")), null, emptyList(), emptyList(), emptyList(), emptyList())
        val outcome = service(repo).restore { ByteArrayInputStream(backup.toByteArray()) }
        assertEquals("Restored 2 clients.", outcome.message)
        assertFalse(outcome.isError)
        assertEquals(setOf("n1", "n2"), repo.all().map { it.id }.toSet())
    }

    // [I-3]'s other half: a write that fails is not the file being invalid, and the coach must be
    // told their roster is still there rather than being talked out of retrying.
    @Test fun `a restore whose write fails does not blame the file and says the roster is intact`() = runBlocking {
        val repo = repo()
        repo.save(client("old"))
        // Occupy the staging path replaceAll must write through.
        tmp.root.resolve("clients/n1.json.new").mkdirs()
        val backup = BackupCodec.export(listOf(client("n1")), null, emptyList(), emptyList(), emptyList(), emptyList())
        val outcome = service(repo).restore { ByteArrayInputStream(backup.toByteArray()) }
        assertTrue(outcome.isError)
        assertTrue(outcome.message.startsWith("Couldn't restore that backup:"))
        assertTrue(outcome.message.contains("roster is unchanged"))
        assertEquals(listOf("old"), repo.all().map { it.id })
    }

    @Test fun `a restore over a corrupt library cache sets it aside and says so`() = runBlocking {
        libraryFile().writeText("{\"plans\": [{\"id\": \"A\"")
        // The backup must carry a key the codec still preserves, or
        // PreservedLibraryStore.update is handed nothing and never reaches the
        // corrupt cache it is supposed to quarantine.
        val backup = JSONObject(BackupCodec.export(emptyList(), null, emptyList(), emptyList(), emptyList(), emptyList())).put("plans", org.json.JSONArray()).toString()
        val outcome = service().restore { ByteArrayInputStream(backup.toByteArray()) }
        assertFalse(outcome.isError)
        assertTrue(outcome.message.contains("set aside"))
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("preserved-library.json.corrupt-") })
    }
}
