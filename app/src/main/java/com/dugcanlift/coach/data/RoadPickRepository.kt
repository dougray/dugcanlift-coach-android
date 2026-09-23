package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one read of the road picks found.
 *
 * [unreadable] is not collapsed into "no picks", for the reason
 * [StoredCookLibrary.unreadable] is not: "this coach has ticked nothing" and
 * "their ticks are on disk but unreadable" have opposite correct responses,
 * and every write here is a read-modify-write that would otherwise save the
 * empty one over the real one.
 */
data class StoredRoadPicks(
    val byClient: Map<String, List<String>>,
    val unreadable: Throwable? = null
) {
    val isUnreadable: Boolean get() = unreadable != null

    fun forClient(clientId: String?): List<String> =
        if (clientId == null) emptyList() else RoadPicks.normalise(byClient[clientId])
}

/**
 * The Road Food items a coach has marked for each client: one list of item
 * ids per client, in the order they were ticked.
 *
 * Its own small file rather than a section of `cook-library.json`, for two
 * reasons. A client's picks are swept when that client is removed
 * ([ClientRemoval]) and the cook library is not touched by that sweep beyond
 * its meals; and the cook library's unreadable-means-refuse-the-export rule
 * is about a coach's recipes, which nothing can rebuild. Picks are a handful
 * of ticks a coach can redo.
 *
 * Coach web keeps the same shape under `coach.roadPicks` in local storage --
 * an object keyed by client id -- and the backup carries it as `roadPicks`
 * (BACKUP-FORMAT.md "The Coach backup's road picks").
 */
class RoadPickRepository(root: File) {

    private val file = File(root.apply { mkdirs() }, FILE_NAME)

    fun load(): StoredRoadPicks {
        if (!file.exists()) return StoredRoadPicks(emptyMap())
        return try {
            StoredRoadPicks(decode(JSONObject(file.readText())))
        } catch (t: Throwable) {
            // Not "no picks". See StoredRoadPicks.unreadable.
            StoredRoadPicks(emptyMap(), unreadable = t)
        }
    }

    /**
     * One client's picks, dropping the key entirely when there are none: an
     * empty list is how "no picks" is written here and on the wire.
     */
    fun setForClient(clientId: String, ids: List<String?>) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        val list = RoadPicks.normalise(ids)
        val next = current.byClient.toMutableMap()
        if (list.isEmpty()) next.remove(clientId) else next[clientId] = list
        write(next)
    }

    /** Everything this client had, gone. Silent when they had none. */
    fun removeClient(clientId: String) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        if (clientId !in current.byClient) return
        write(current.byClient - clientId)
    }

    /**
     * Restores a backup's picks, **per client, not per id**.
     *
     * There are no rows with ids of their own to match on, so a client this
     * device already has picks for keeps them -- an older backup must never
     * delete newer work -- and a client it has none for takes the file's list.
     * A file with no `roadPicks` at all changes nothing. This follows
     * BACKUP-FORMAT.md rather than this app's replace-the-library habit,
     * because the format is what the three Coach builds share. Returns how
     * many picks were added.
     */
    fun merge(fromFile: Map<String, List<String>>): Int {
        if (fromFile.isEmpty()) return 0
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        val next = current.byClient.toMutableMap()
        var added = 0
        fromFile.forEach { (clientId, ids) ->
            if (current.forClient(clientId).isNotEmpty()) return@forEach
            val list = RoadPicks.normalise(ids)
            if (list.isEmpty()) return@forEach
            next[clientId] = list
            added += list.size
        }
        if (added > 0) write(next)
        return added
    }

    private fun write(byClient: Map<String, List<String>>) {
        val root = JSONObject()
        byClient.forEach { (clientId, ids) -> root.put(clientId, JSONArray(ids)) }
        writeTextAtomically(file, root.toString())
    }

    companion object {
        private const val FILE_NAME = "road-picks.json"

        /** The backup's `roadPicks` object, read leniently: junk is no picks, never a failed restore. */
        fun decode(root: JSONObject?): Map<String, List<String>> {
            if (root == null) return emptyMap()
            val out = LinkedHashMap<String, List<String>>()
            root.keys().forEach { clientId ->
                val ids = RoadPicks.fromWire(root.optJSONArray(clientId))
                if (ids.isNotEmpty()) out[clientId] = ids
            }
            return out
        }

        /** The same object for a backup, or null when there is nothing to write. */
        fun encode(byClient: Map<String, List<String>>): JSONObject? {
            val root = JSONObject()
            byClient.forEach { (clientId, ids) ->
                val list = RoadPicks.normalise(ids)
                if (list.isNotEmpty()) root.put(clientId, JSONArray(list))
            }
            return root.takeIf { it.length() > 0 }
        }
    }
}
