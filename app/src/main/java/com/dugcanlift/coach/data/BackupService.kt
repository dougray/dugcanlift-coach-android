package com.dugcanlift.coach.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the Connect screen should show after a backup action: the message, and whether it is an error. */
data class BackupOutcome(val message: String, val isError: Boolean)

/**
 * Save Backup and Restore from Backup, off the main thread and out of the Composable.
 *
 * Both of Connect's file-picker callbacks used to run the entire operation inline on the main
 * thread: read and parse every client file, serialise the whole roster, and write it through the
 * content resolver -- or, on restore, read the whole document, decode it, and rewrite every client
 * file. A 12-client roster with months of itemized food is a multi-megabyte serialise plus a SAF
 * write on the UI thread; on a cloud-backed provider (Drive, a network mount) that is an ANR, and
 * the system kills the app mid-restore. Two earlier rounds fixed exactly this in RosterScreen and
 * ClientScreen; the backup path landed afterwards and reintroduced it in the one place that does
 * the most I/O.
 *
 * The streams are opened by the caller's lambdas, which run *inside* this class's
 * `withContext(io)` -- so the content-resolver call is off the main thread too, not just the
 * parsing. This is also the repo's usual answer to "logic that would otherwise only be reachable
 * from a @Composable" (see `RosterLoader`): the coordination lives here where it can be tested,
 * and the Composable does nothing but launch it and render the result.
 */
class BackupService(
    private val repo: ClientRepository,
    private val libraryFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Refuses rather than writing a backup that is missing something. Two cases, both of which used
     * to write a cheerful "Backup saved." over a file that had quietly lost data:
     *
     * - a client file that cannot be decoded -- `all()` drops it, so it would be absent from this
     *   export, and the next restore's `replaceAll` would then destroy it for good;
     * - an unreadable preserved-library cache -- the coach's recipes and routines, which this app
     *   cannot regenerate and which would simply not appear in the written file.
     */
    suspend fun export(openOutput: () -> OutputStream?): BackupOutcome = withContext(io) {
        try {
            val roster = repo.load()
            if (roster.unreadableFiles.isNotEmpty()) {
                return@withContext BackupOutcome(
                    "Couldn't create a backup: ${roster.unreadableFiles.size} client file(s) on this " +
                        "device can't be read, and a backup without them would lose them. Nothing was written.",
                    true
                )
            }
            val json = BackupCodec.export(roster.clients, PreservedLibraryStore.load(libraryFile))
            val stream = openOutput() ?: throw IllegalStateException("Couldn't open that location.")
            stream.use { it.write(json.toByteArray()) }
            BackupOutcome("Backup saved.", false)
        } catch (e: PreservedLibraryUnreadableException) {
            BackupOutcome(
                "Couldn't create a backup: this device's saved recipes and routines can't be read, " +
                    "and a backup without them would lose them. Nothing was written.",
                true
            )
        } catch (e: Exception) {
            BackupOutcome("Couldn't create a backup: ${e.message}", true)
        }
    }

    /**
     * Reads, decodes, replaces, then updates the library cache -- with each failure reported as
     * what it actually was. "That doesn't look like a valid backup file." used to cover all three
     * stages, including a write that failed on a perfectly good file and a library-cache update
     * that ran *after* the clients had already restored successfully: the coach was told the file
     * was bad, so they did not retry, and (before [ClientRepository.replaceAll] became
     * transactional) the roster it had already deleted was gone.
     */
    suspend fun restore(openInput: () -> InputStream?): BackupOutcome = withContext(io) {
        val decoded = try {
            val json = openInput()?.bufferedReader()?.readText()
                ?: throw IllegalStateException("Couldn't access that file.")
            BackupCodec.restore(json)
        } catch (e: Exception) {
            return@withContext BackupOutcome("That doesn't look like a valid backup file.", true)
        }

        try {
            repo.replaceAll(decoded.clients)
        } catch (e: Exception) {
            // replaceAll is transactional: the previous roster is still exactly as it was.
            return@withContext BackupOutcome(
                "Couldn't restore that backup: ${e.message} Your existing roster is unchanged.",
                true
            )
        }

        try {
            val update = PreservedLibraryStore.update(libraryFile, decoded.preservedLibrary)
            if (update.quarantinedCorruptCache) {
                BackupOutcome(
                    "Restored ${decoded.clients.size} clients. This device's previously saved recipes " +
                        "and routines couldn't be read and were set aside, not deleted.",
                    false
                )
            } else {
                BackupOutcome("Restored ${decoded.clients.size} clients.", false)
            }
        } catch (e: Exception) {
            BackupOutcome(
                "Restored ${decoded.clients.size} clients, but couldn't save this backup's recipes " +
                    "and routines: ${e.message}",
                true
            )
        }
    }
}
