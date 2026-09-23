package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONObject

/**
 * What one read of the sent plans found.
 *
 * [unreadable] is not collapsed into "nothing sent", for the reason [StoredRoadPicks.unreadable]
 * is not: "this coach has sent nothing" and "what they sent is on disk but unreadable" have
 * opposite correct responses, and every write here is a read-modify-write that would otherwise
 * save the empty one over the real one.
 */
data class StoredSentPlans(
    val rows: List<SentPlan>,
    val unreadable: Throwable? = null
) {
    val isUnreadable: Boolean get() = unreadable != null

    /** This client's sends, newest first. */
    fun forClient(clientId: String?): List<SentPlan> = SentPlans.forClient(rows, clientId)
}

/**
 * The record of what each client was actually sent: one row per send, the plan payload as encoded.
 *
 * Its own small file rather than a section of the train library, for the reasons
 * [RoadPickRepository] is its own: a client's sends are swept when that client is removed
 * ([ClientRemoval]), and the train library's unreadable-means-refuse-the-export rule is about a
 * coach's routines, which nothing can rebuild. A missing send record costs one empty card.
 *
 * Coach web keeps the same rows under `coach.sentPlans` in local storage, and the backup carries
 * them as `sentPlans` (BACKUP-FORMAT.md "The Coach backup's sent plans"), so one file moves
 * between all three Coach builds.
 */
class SentPlanRepository(root: File) {

    private val file = File(root.apply { mkdirs() }, FILE_NAME)

    fun load(): StoredSentPlans {
        if (!file.exists()) return StoredSentPlans(emptyList())
        return try {
            StoredSentPlans(SentPlans.decode(JSONObject(file.readText()).optJSONArray(KEY)))
        } catch (t: Throwable) {
            // Not "nothing sent". See StoredSentPlans.unreadable.
            StoredSentPlans(emptyList(), unreadable = t)
        }
    }

    /**
     * Files one send, replacing this client's newest row when the plan is identical
     * ([SentPlans.record]) and pruning to the cap.
     */
    fun record(entry: SentPlan) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        write(SentPlans.record(current.rows, entry))
    }

    /** Everything sent to this client, gone. Silent when they were sent nothing. */
    fun removeClient(clientId: String) {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        if (current.rows.none { it.clientId == clientId }) return
        write(current.rows.filterNot { it.clientId == clientId })
    }

    /**
     * A backup's rows folded in by id, never deleting a newer send ([SentPlans.mergeBackup]).
     * Returns how many rows were added.
     */
    fun merge(incoming: List<SentPlan>): Int {
        if (incoming.isEmpty()) return 0
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        val merged = SentPlans.mergeBackup(current.rows, incoming)
        if (merged.added > 0) write(merged.rows)
        return merged.added
    }

    private fun write(rows: List<SentPlan>) {
        val root = JSONObject()
        SentPlans.encode(rows)?.let { root.put(KEY, it) }
        writeTextAtomically(file, root.toString())
    }

    companion object {
        private const val FILE_NAME = "sent-plans.json"
        private const val KEY = "sentPlans"
    }
}
