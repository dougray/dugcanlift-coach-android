package com.dugcanlift.coach.data

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one read of the train library found. [unreadable] is kept apart from an
 * empty library for the reason [StoredCookLibrary] spells out: exporting the
 * first is fine, exporting the second is what makes the loss permanent.
 */
data class StoredTrainLibrary(
    val routines: List<Routine>,
    val sessions: List<ScheduledSession>,
    val unreadable: Throwable? = null
) {
    val isUnreadable: Boolean get() = unreadable != null
}

/**
 * The coach's routines and the sessions booked from them.
 *
 * Its own file rather than joining `cook-library.json`. The two could have been
 * one "library" store -- the backup format treats them as one -- but Cook
 * shipped first, in 1.1, and merging them now would mean migrating a file that
 * is already on coaches' phones. A second file costs a few lines of duplication
 * and no migration at all.
 */
class TrainRepository(root: File) {

    private val file = File(root.apply { mkdirs() }, FILE_NAME)

    fun load(): StoredTrainLibrary {
        if (!file.exists()) return StoredTrainLibrary(emptyList(), emptyList())
        return try {
            val root = JSONObject(file.readText())
            StoredTrainLibrary(
                routines = root.optJSONArray("routines").objects(::routineFromJson),
                sessions = root.optJSONArray("sessions").objects(::scheduledSessionFromJson).filterNotNull()
            )
        } catch (t: Throwable) {
            StoredTrainLibrary(emptyList(), emptyList(), unreadable = t)
        }
    }

    fun save(routines: List<Routine>, sessions: List<ScheduledSession>) {
        val root = JSONObject()
            .put("routines", JSONArray(routines.map { it.toJson() }))
            .put("sessions", JSONArray(sessions.map { it.toJson() }))
        writeTextAtomically(file, root.toString())
    }

    fun upsertRoutine(routine: Routine) {
        val current = requireReadable()
        save(current.routines.filterNot { it.id == routine.id } + routine, current.sessions)
    }

    /**
     * Removes a routine and every session booked from it.
     *
     * The sessions go for the same reason a deleted recipe takes its planned
     * meals: a session pointing at a routine that no longer exists renders as a
     * blank row in a client's week forever, and cannot be sent.
     */
    fun deleteRoutine(id: String) {
        val current = requireReadable()
        save(
            current.routines.filterNot { it.id == id },
            current.sessions.filterNot { it.routineId == id }
        )
    }

    fun upsertSession(session: ScheduledSession) {
        val current = requireReadable()
        save(current.routines, current.sessions.filterNot { it.id == session.id } + session)
    }

    fun deleteSession(id: String) {
        val current = requireReadable()
        save(current.routines, current.sessions.filterNot { it.id == id })
    }

    fun replaceAll(routines: List<Routine>, sessions: List<ScheduledSession>) = save(routines, sessions)

    private fun requireReadable(): StoredTrainLibrary {
        val current = load()
        if (current.isUnreadable) throw current.unreadable!!
        return current
    }

    companion object {
        private const val FILE_NAME = "train-library.json"
    }
}

private fun <T> JSONArray?.objects(decode: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> (opt(i) as? JSONObject)?.let(decode) }
}
