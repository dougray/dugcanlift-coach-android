package com.dugcanlift.coach.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * One JSON file per client under `<root>/clients/<id>.json`, written
 * atomically (write `.tmp`, then move into place) so a crash mid-write never
 * leaves a half-written file behind, and so saving one client can never
 * corrupt or touch another's file.
 */
class ClientRepository(root: File) {
    private val dir = File(root, "clients").apply { mkdirs() }

    fun all(): List<Client> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { Client.fromJson(JSONObject(f.readText())) }.getOrNull() }

    fun get(id: String): Client? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { runCatching { Client.fromJson(JSONObject(it.readText())) }.getOrNull() }

    /**
     * Writes `.tmp` then moves it into place with [Files.move], which throws on
     * failure instead of returning a boolean nobody checks (the old
     * `File.renameTo` bug: a rename that fails across filesystems, against a
     * held-open destination, or on flaky storage silently orphaned the `.tmp`
     * and dropped the coach's newest import with no exception and no log).
     * Atomic where the filesystem supports it; falls back to a plain replace
     * rather than to the old silent path when it does not, and a genuine
     * failure propagates.
     */
    fun save(client: Client) {
        val tmp = File(dir, "${client.id}.json.tmp")
        val target = File(dir, "${client.id}.json")
        tmp.writeText(client.toJson().toString())
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    fun replaceAll(clients: List<Client>) {
        dir.listFiles()?.forEach { it.delete() }
        clients.forEach(::save)
    }
}
