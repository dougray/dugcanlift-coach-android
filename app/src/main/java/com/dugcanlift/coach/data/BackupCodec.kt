package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Seconds between the Unix epoch (1970-01-01) and Foundation's reference date (2001-01-01),
 * which is what `Date`'s wire encoding (and this file's `lastImportedAt`) actually counts from.
 * Add this on read, subtract it on write. Getting the sign backwards silently shifts every
 * client's import date by 31 years, and the resulting number still looks plausible, so both
 * directions are pinned by [BackupCodecTest].
 */
private const val FOUNDATION_EPOCH_OFFSET_SECONDS = 978_307_200L

/** Names of the four library arrays this file may carry that Coach Android has no model for. */
private val LIBRARY_KEYS = listOf("recipes", "meals", "routines", "sessions")

private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

/**
 * [clients] decoded from the file, in Coach Android's own model. [preservedLibrary] holds
 * whichever of `recipes`/`meals`/`routines`/`sessions` were present in the file, as the exact
 * JSON values that were parsed -- never decoded into models -- so [BackupCodec.export] can write
 * them straight back out untouched. `null` means the file had none of the four keys (a v1 file,
 * or a v2 file with no library yet), which must round-trip to "no library keys", not to "delete
 * the library."
 */
data class RestoreResult(val clients: List<Client>, val preservedLibrary: JSONObject?)

/**
 * Reads and writes Coach iOS's `BackupCodec` v2 file
 * (`coach-ios/Sources/Shared/BackupCodec.swift`) -- the same file iOS's own Connect screen saves
 * and restores, so a coach can move between phone and Android without losing anything.
 *
 * `id`, `name`, `displayUnit`, `platform`, `goal` and `days` (and everything nested under a day --
 * sets and food entries) already share field names with [Client]'s own JSON shape, so this codec
 * only has custom handling for the top-level client envelope: `lastImportedAt`'s 2001-epoch
 * conversion, and the four opaque library arrays. See [LIBRARY_KEYS] and
 * [FOUNDATION_EPOCH_OFFSET_SECONDS].
 *
 * Clients restore by **replace** -- callers pass [RestoreResult.clients] to
 * [ClientRepository.replaceAll], exactly as iOS's own restore replaces its roster. The library
 * arrays are opaque cargo: Coach Android does not have Cook, Train or Sessions yet, so they are
 * carried as-is rather than parsed and rebuilt, which is what keeps a phone -> Android -> phone
 * round trip from losing a coach's recipes.
 */
object BackupCodec {
    fun restore(json: String): RestoreResult {
        val root = JSONObject(json)
        val clients = root.optJSONArray("clients")?.let { arr ->
            (0 until arr.length()).map { clientFromJson(arr.getJSONObject(it)) }
        }.orEmpty()

        var preserved: JSONObject? = null
        for (key in LIBRARY_KEYS) {
            if (root.has(key) && !root.isNull(key)) {
                val library = preserved ?: JSONObject().also { preserved = it }
                library.put(key, root.get(key))
            }
        }
        return RestoreResult(clients, preserved)
    }

    fun export(clients: List<Client>, preservedLibrary: JSONObject?): String {
        val root = JSONObject()
        root.put("v", 2)
        root.put("clients", JSONArray(clients.map { clientToJson(it) }))
        if (preservedLibrary != null) {
            for (key in LIBRARY_KEYS) {
                if (preservedLibrary.has(key)) root.put(key, preservedLibrary.get(key))
            }
        }
        return root.toString()
    }

    private fun clientFromJson(json: JSONObject): Client {
        val lastImportedAtEpochMs = if (json.has("lastImportedAt") && !json.isNull("lastImportedAt")) {
            val foundationSeconds = json.getDouble("lastImportedAt")
            ((foundationSeconds + FOUNDATION_EPOCH_OFFSET_SECONDS) * 1000.0).toLong()
        } else {
            System.currentTimeMillis()
        }
        return Client(
            id = json.getString("id"),
            name = json.getString("name"),
            displayUnit = json.getString("displayUnit"),
            platform = json.optStringOrNull("platform"),
            lastImportedAtEpochMs = lastImportedAtEpochMs,
            goal = if (json.has("goal") && !json.isNull("goal")) Goal.fromJson(json.getJSONObject("goal")) else null,
            days = json.optJSONArray("days")?.let { arr -> (0 until arr.length()).map { TrainingDay.fromJson(arr.getJSONObject(it)) } }.orEmpty()
        )
    }

    private fun clientToJson(client: Client): JSONObject = JSONObject().apply {
        put("id", client.id)
        put("name", client.name)
        put("displayUnit", client.displayUnit)
        put("platform", client.platform ?: JSONObject.NULL)
        put("lastImportedAt", client.lastImportedAtEpochMs / 1000.0 - FOUNDATION_EPOCH_OFFSET_SECONDS)
        put("goal", client.goal?.toJson() ?: JSONObject.NULL)
        put("days", JSONArray(client.days.map { it.toJson() }))
    }
}
