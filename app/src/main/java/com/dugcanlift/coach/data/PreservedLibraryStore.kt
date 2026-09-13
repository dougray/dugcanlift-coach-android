package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Thrown when the cache file exists but cannot be parsed -- see [PreservedLibraryStore.load]. */
class PreservedLibraryUnreadableException(file: File, cause: Throwable) :
    Exception("The preserved library cache (${file.name}) exists but could not be read.", cause)

/**
 * What [PreservedLibraryStore.read] found on disk. [Unreadable] is deliberately NOT collapsed into
 * [Absent]: "this device has no library" and "this device's library is on disk but unreadable" have
 * opposite correct responses -- the first exports a backup with no library keys, the second must
 * refuse to export at all, because writing that backup is what makes the loss permanent.
 */
sealed class LibraryCache {
    object Absent : LibraryCache()
    data class Present(val library: JSONObject) : LibraryCache()
    data class Unreadable(val cause: Throwable) : LibraryCache()
}

/** Whether [PreservedLibraryStore.update] had to set a corrupt cache aside before writing. */
data class LibraryUpdateResult(val quarantinedCorruptCache: Boolean)

/**
 * The coach's cached copy of the opaque library (recipes/meals/routines/sessions, plus any
 * top-level key a newer Coach iOS file carries that Coach Android has no model for) from the most
 * recent restore that carried one, kept on disk so a later Save Backup can carry it back out
 * untouched even across app runs -- otherwise the round trip [BackupCodec] promises would only
 * hold within a single restore-then-export call, not across app runs.
 *
 * This is the only piece of state spanning two separate user actions -- Restore now, Save later
 * -- which is exactly why it needs direct tests rather than only Compose-level ones.
 *
 * Two properties are load-bearing and were added in round 6:
 *
 * 1. **The write is staged** ([writeTextAtomically]), the same way [ClientRepository.save] writes a
 *    client. This file holds the data the app deliberately does not understand and cannot
 *    regenerate; a bare `writeText` truncated by a device kill silently turns a coach's 40 recipes
 *    and 12 routines into a parse error.
 * 2. **A parse error is surfaced, not swallowed.** [load] throws
 *    [PreservedLibraryUnreadableException] rather than returning `null`, so an export refuses
 *    instead of writing `{"v":2,"clients":[...]}` with no library and reporting "Backup saved."
 */
object PreservedLibraryStore {

    /** Never throws: the three states, as found. */
    fun read(file: File): LibraryCache {
        if (!file.exists()) return LibraryCache.Absent
        return runCatching { JSONObject(file.readText()) }
            .fold({ LibraryCache.Present(it) }, { LibraryCache.Unreadable(it) })
    }

    /**
     * The cached library, or `null` when there is none. Throws
     * [PreservedLibraryUnreadableException] when the file is there but unreadable -- callers that
     * are about to write a backup must fail loudly rather than quietly omit the library.
     */
    fun load(file: File): JSONObject? = when (val cache = read(file)) {
        is LibraryCache.Absent -> null
        is LibraryCache.Present -> cache.library
        is LibraryCache.Unreadable -> throw PreservedLibraryUnreadableException(file, cache.cause)
    }

    /**
     * Applies one restore's result to the cache. [RestoreResult.preservedLibrary] is `null` when
     * the restored file carried nothing beyond the keys this app models (a v1 file, or a v2 file
     * with no library yet) -- coach-ios's BackupCodec.swift documents the rule this must follow: an
     * older backup must never delete newer work sitting on the device, so a restore with no library
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
     *
     * An unreadable cache is *set aside* (renamed `<name>.corrupt-<millis>`), never overwritten and
     * never merged blind: the bytes stay on the device for recovery, the new library is written
     * cleanly, and the caller is told through [LibraryUpdateResult] so a restore can say so instead
     * of silently self-healing.
     */
    fun update(file: File, library: JSONObject?): LibraryUpdateResult {
        if (library == null) return LibraryUpdateResult(quarantinedCorruptCache = false)
        var quarantined = false
        val cache = when (val found = read(file)) {
            is LibraryCache.Absent -> null
            is LibraryCache.Present -> found.library
            is LibraryCache.Unreadable -> {
                moveIntoPlace(file, File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
                quarantined = true
                null
            }
        }
        val merged = if (cache == null) library else merge(cache, library)
        writeTextAtomically(file, merged.toString())
        return LibraryUpdateResult(quarantinedCorruptCache = quarantined)
    }

    /**
     * Merges over the *union* of both sides' keys, not a fixed list of four names. Scoping this to
     * `recipes`/`meals`/`routines`/`sessions` was the same defect as dropping the library outright,
     * one level down: a `programs` array from a newer Coach iOS file survived [BackupCodec.restore]
     * and was then erased here, on the way to the disk that was supposed to be preserving it.
     *
     * Two arrays merge by id (above). Anything else -- a scalar, an object, a type that changed
     * between versions -- cannot be merged item-wise, so the incoming file's value wins when it has
     * one and the cache's is kept when it does not. Nothing is dropped because its shape was
     * unfamiliar.
     */
    private fun merge(cache: JSONObject, incoming: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = LinkedHashSet<String>()
        cache.keys().forEach { keys.add(it) }
        incoming.keys().forEach { keys.add(it) }
        for (key in keys) {
            val cached = cache.takeIf { it.has(key) && !it.isNull(key) }?.get(key)
            val fresh = incoming.takeIf { it.has(key) && !it.isNull(key) }?.get(key)
            when {
                cached == null && fresh == null -> Unit
                cached == null -> result.put(key, fresh)
                fresh == null -> result.put(key, cached)
                cached is JSONArray && fresh is JSONArray -> result.put(key, mergeById(cached, fresh))
                else -> result.put(key, fresh)
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
