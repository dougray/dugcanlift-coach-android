package com.dugcanlift.coach.data

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/**
 * What one read of the clients directory found. [unreadableFiles] names the files that are there
 * but could not be decoded -- they are NOT absent clients, and a caller about to write a backup
 * (which is sourced from [clients]) has to refuse rather than write a file that silently leaves
 * them out. See [ClientRepository.load].
 */
data class StoredRoster(val clients: List<Client>, val unreadableFiles: List<String>)

/**
 * One JSON file per client under `<root>/clients/<file name for id>.json`, written
 * atomically (write `.tmp`, then move into place) so a crash mid-write never
 * leaves a half-written file behind, and so saving one client can never
 * corrupt or touch another's file.
 *
 * `open` only so tests can interpose on [get]/[save] to reproduce a concurrent import; nothing in
 * the app subclasses it.
 */
open class ClientRepository(root: File) {
    private val dir = File(root, "clients").apply { mkdirs() }

    /**
     * Where a file that cannot be decoded is set aside instead of being deleted. Outside
     * `clients/`, so it never reappears in [all] -- and never destroyed, because "Coach could not
     * read this" is not the same as "this client does not exist", and a coach may still be able to
     * recover the bytes. See [replaceAll].
     */
    private val quarantineDir = File(root, "unreadable")

    /**
     * Every client currently readable. A file that cannot be decoded is skipped so one damaged
     * file can never crash the roster -- use [load] when you need to know that happened, which
     * anything that WRITES the roster out (export, replace) does.
     */
    fun all(): List<Client> = load().clients

    /** [all], plus the names of the files that could not be decoded. */
    fun load(): StoredRoster {
        val clients = mutableListOf<Client>()
        val unreadable = mutableListOf<String>()
        for (f in clientFiles()) {
            val client = readClient(f)
            if (client != null) clients += client else unreadable += f.name
        }
        return StoredRoster(clients, unreadable)
    }

    open fun get(id: String): Client? = File(dir, fileNameFor(id)).takeIf { it.exists() }?.let { readClient(it) }

    /**
     * Writes `.tmp` then moves it into place with [moveIntoPlace], which throws on
     * failure instead of returning a boolean nobody checks (the old
     * `File.renameTo` bug: a rename that fails across filesystems, against a
     * held-open destination, or on flaky storage silently orphaned the `.tmp`
     * and dropped the coach's newest import with no exception and no log).
     */
    open fun save(client: Client) {
        writeTextAtomically(File(dir, fileNameFor(client.id)), client.toJson().toString())
    }

    /** Removes the client's file. True when it is gone afterwards, including when there was none. */
    fun delete(id: String): Boolean {
        val file = File(dir, fileNameFor(id))
        return file.delete() || !file.exists()
    }

    /**
     * Replaces the whole roster, without ever destroying the old one before the new one is on disk.
     *
     * This used to be `delete every file, then save in a loop`, with nothing transactional about
     * it: a failure on client four of ten (a full disk, a value that will not serialise) left three
     * clients where ten used to be, while Connect reported that the *file* was invalid -- so the
     * coach did not retry, and the roster that had already been deleted was simply gone.
     *
     * Now: every client is serialised and staged to a `.new` file first, so anything that can fail
     * (serialisation, space, permissions) fails while the previous roster is still completely
     * intact and untouched. Only once every staged file exists is each moved into place, replacing
     * its old file directly -- there is no moment where the roster is missing from disk. Files
     * belonging to clients the new roster does not include are removed last, and one that cannot be
     * decoded is set aside in [quarantineDir] rather than deleted: this delete is precisely the
     * step that turns "a corrupt file is invisible" into "a corrupt file is destroyed".
     */
    fun replaceAll(clients: List<Client>) {
        val staged = mutableListOf<Pair<File, File>>()
        try {
            for (client in clients) {
                val target = File(dir, fileNameFor(client.id))
                val staging = File(dir, "${target.name}.new")
                staging.writeText(client.toJson().toString())
                staged += staging to target
            }
        } catch (e: Exception) {
            staged.forEach { (staging, _) -> staging.delete() }
            throw e
        }

        staged.forEach { (staging, target) -> moveIntoPlace(staging, target) }

        val keep = staged.map { it.second.name }.toSet()
        clientFiles().filter { it.name !in keep }.forEach { old ->
            if (readClient(old) == null) quarantine(old) else old.delete()
        }
    }

    private fun clientFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().sortedBy { it.name }

    private fun readClient(file: File): Client? =
        runCatching { Client.fromJson(JSONObject(file.readText())) }.getOrNull()

    private fun quarantine(file: File) {
        quarantineDir.mkdirs()
        var target = File(quarantineDir, file.name)
        var n = 1
        while (target.exists()) target = File(quarantineDir, "${file.name}.$n").also { n++ }
        moveIntoPlace(file, target)
    }

    companion object {
        /**
         * Ids that are safe to use as a file name directly. Anything already stored by an earlier
         * build has one of these (they are what every real encoder emits), so this mapping leaves
         * every existing `clients/<id>.json` exactly where it is and readable.
         */
        private val PLAIN_FILENAME_ID = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * `c.i` arrives from an untrusted link and SHARE-FORMAT.md puts no charset constraint on
         * it, yet it went straight into `File(dir, "$id.json")`. An id containing `/` wrote outside
         * `clients/` entirely (`../../shared_prefs/x` escapes the app's repository directory) or
         * threw `FileNotFoundException` mid-import. Rather than reject the client -- which would
         * silently drop a real lifter's data because a third-party encoder chose an unusual id --
         * an unsafe id is hashed to a name that is unambiguously one path segment. The id itself is
         * unchanged in the stored JSON, which is what [all] and [get] compare against, so nothing
         * about the client's identity depends on this mapping. `~` cannot appear in
         * [PLAIN_FILENAME_ID], so a hashed name can never collide with a plain one.
         */
        internal fun fileNameFor(id: String): String =
            if (PLAIN_FILENAME_ID.matches(id)) "$id.json" else "~${sha256Hex(id)}.json"

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
