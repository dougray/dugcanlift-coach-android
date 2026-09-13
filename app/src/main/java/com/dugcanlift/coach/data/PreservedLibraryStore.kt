package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The coach's cached copy of the opaque library (recipes/meals/routines/sessions) from the most
 * recent restore that carried one, kept on disk so a later Save Backup can carry it back out
 * untouched even across app runs -- otherwise the round trip [BackupCodec] promises would only
 * hold within a single restore-then-export call, not across app runs.
 *
 * This is the only piece of state spanning two separate user actions -- Restore now, Save later
 * -- which is exactly why it needs direct tests rather than only Compose-level ones.
 */
object PreservedLibraryStore {
    // Mirrors BackupCodec's private LIBRARY_KEYS (same four names) -- kept as a separate literal
    // here rather than widening that file's visibility just for this.
    private val LIBRARY_KEYS = listOf("recipes", "meals", "routines", "sessions")

    fun load(file: File): JSONObject? =
        file.takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    /**
     * Applies one restore's result to the cache. [RestoreResult.preservedLibrary] is `null` when
     * the restored file carried none of the four library keys (a v1 file, or a v2 file with no
     * library yet) -- coach-ios's BackupCodec.swift documents the rule this must follow: an older
     * backup must never delete newer work sitting on the device, so a restore with no library
     * leaves the cache exactly as it was.
     *
     * A non-null library is *merged* into the cache, per array, keyed by `id` -- the same rule
     * coach-ios's `BackupCodec.restore` (Sources/Shared/BackupCodec.swift, ~lines 244-308) applies
     * to its four typed arrays: `let existingRecipes = Set(...map(\.id)); for row in backup.recipes
     * ?? [] where !existingRecipes.contains(row.id) { insert row }` -- repeated identically for
     * meals, routines and sessions. Read literally: the device's existing rows are never touched or
     * replaced, and an incoming row is added only when its id is *not* already present on the
     * device. On a colliding id, the device's copy wins and the incoming row is dropped. A key
     * missing from one side entirely is left as whatever the other side has (Swift's `?? []` makes
     * an absent incoming array behave as empty, which is a no-op against the existing set; an
     * absent *cache* array means every incoming row is "new" and all get kept). This was replace-
     * outright before -- "Android never authors library content" answered who writes new content,
     * not what order backups get restored in, and a coach restoring an older/thinner backup after a
     * richer one silently lost every id the thinner file didn't mention.
     *
     * An entry with no `id` field at all cannot be matched against anything, on either side --
     * treating that as "no collision" (always keep, on both sides) is the only choice that can't
     * silently drop data because identity couldn't be determined; the alternative (treat as always-
     * colliding, i.e. drop it) is exactly the failure mode this fix exists to close.
     */
    fun update(file: File, library: JSONObject?) {
        if (library == null) return
        val cache = load(file)
        val merged = if (cache == null) library else merge(cache, library)
        file.writeText(merged.toString())
    }

    private fun merge(cache: JSONObject, incoming: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in LIBRARY_KEYS) {
            val cacheArr = cache.optJSONArray(key)
            val incomingArr = incoming.optJSONArray(key)
            when {
                cacheArr == null && incomingArr == null -> Unit
                cacheArr == null -> result.put(key, incomingArr)
                incomingArr == null -> result.put(key, cacheArr)
                else -> result.put(key, mergeById(cacheArr, incomingArr))
            }
        }
        return result
    }

    /**
     * The device's rows (`cacheArr`) are kept exactly as-is, in order. An incoming row is appended
     * only if its `id` is absent from the *original* cache id set -- mirroring Swift's frozen
     * `existingRecipes` Set, which is computed once before its loop and never grows as rows are
     * inserted, so two same-id rows within `incoming` itself are not deduplicated against each
     * other, only against the device's prior state. A row with no `id` field is never matched
     * against anything and is always kept.
     */
    private fun mergeById(cacheArr: JSONArray, incomingArr: JSONArray): JSONArray {
        val merged = JSONArray()
        val cacheIds = mutableSetOf<String>()
        for (i in 0 until cacheArr.length()) {
            val entry = cacheArr.get(i)
            merged.put(entry)
            (entry as? JSONObject)?.optStringOrNull("id")?.let { cacheIds.add(it) }
        }
        for (i in 0 until incomingArr.length()) {
            val entry = incomingArr.get(i)
            val id = (entry as? JSONObject)?.optStringOrNull("id")
            if (id == null || id !in cacheIds) merged.put(entry)
        }
        return merged
    }
}
