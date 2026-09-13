package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONObject

/**
 * One JSON file per client under `<root>/clients/<id>.json`, written
 * atomically (write `.tmp`, then rename) so a crash mid-write never leaves a
 * half-written file behind, and so saving one client can never corrupt or
 * touch another's file.
 */
class ClientRepository(root: File) {
    private val dir = File(root, "clients").apply { mkdirs() }

    fun all(): List<Client> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { Client.fromJson(JSONObject(f.readText())) }.getOrNull() }

    fun get(id: String): Client? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { runCatching { Client.fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun save(client: Client) {
        val tmp = File(dir, "${client.id}.json.tmp")
        tmp.writeText(client.toJson().toString())
        tmp.renameTo(File(dir, "${client.id}.json"))
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    fun replaceAll(clients: List<Client>) {
        dir.listFiles()?.forEach { it.delete() }
        clients.forEach(::save)
    }
}
