package com.dugcanlift.coach.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * TRAIN for Coach -- the routines a coach writes and the sessions they book
 * against a client's calendar.
 *
 * The Train half of the library stopped being opaque cargo when this arrived,
 * exactly as `recipes`/`meals` did for Cook. The same rules apply and for the
 * same reasons: both backup spellings are accepted, and every key this app has
 * no model for is preserved rather than dropped. See `Cook.kt`'s header.
 *
 * ## Kilograms, and the 2.2x that is waiting for anyone who forgets
 *
 * This is the one thing here that is not like Cook. The two writers of this
 * library disagree about units:
 *
 * ```
 * Coach iOS:  "targetWeightKg": 100     kilograms
 * Coach web:  "weightLb": 225           pounds
 * ```
 *
 * They also spell distance differently (`targetDistanceMeters` against
 * `distanceM`). Reading both into one field without converting does not throw,
 * does not warn, and turns a 100 kg prescription into 100 lb on a client's
 * phone -- Coach iOS's own importer carries a comment saying precisely that.
 *
 * Kilograms are canonical here, matching Coach iOS's storage. A pound value
 * from a web file is converted on the way in and converted back on the way
 * out, so a web coach's file round-trips through this app at the weight they
 * wrote.
 */

/** 1 kg in pounds. The same constant the kit uses for the wire format. */
internal const val LB_PER_KG = 2.2046226218

data class PrescribedSet(
    /** Kilograms, always. See this file's header for why that matters. */
    val targetWeightKg: Double? = null,
    val targetReps: Int? = null,
    val targetRpe: Double? = null,
    val targetDurationSec: Int? = null,
    val targetDistanceMeters: Double? = null,
    val unknownKeys: JSONObject? = null,
    /**
     * A set for one side only, done on that side once (PLAN-FORMAT "Sides" rule
     * 2) -- the asymmetric case: an extra set on the left, rehab side only. Null
     * is both, which is what every set written before this means.
     */
    val side: SetSide? = null
) {
    val targetWeightLb: Double? get() = targetWeightKg?.let { it * LB_PER_KG }
}

data class RoutineExercise(
    val name: String,
    val equipment: String = "",
    val note: String? = null,
    val sets: List<PrescribedSet> = emptyList(),
    val unknownKeys: JSONObject? = null,
    /**
     * Every prescribed set is done on both sides (PLAN-FORMAT "Sides" rule 1):
     * "3 x 8, each side" stays three [sets], and the client's LIFT expects six.
     */
    val eachSide: Boolean = false
) {
    val displayName: String get() = if (equipment.isBlank()) name else "$name ($equipment)"
}

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val exercises: List<RoutineExercise> = emptyList(),
    val unknownKeys: JSONObject? = null
) {
    val setCount: Int get() = exercises.sumOf { it.sets.size }
}

/**
 * A routine booked onto one client's day.
 *
 * Unlike a planned meal, `clientId` is not nullable: Coach iOS's own
 * `BackupSession` has it non-optional, because a session that belongs to
 * nobody is not a session -- it is a routine.
 */
data class ScheduledSession(
    val id: String = UUID.randomUUID().toString(),
    val clientId: String,
    val dayKey: String,
    val routineId: String,
    val unknownKeys: JSONObject? = null
)

/* ---------------- JSON ---------------- */

private val SET_KEYS = setOf(
    "targetWeightKg", "weightLb", "targetReps", "reps", "targetRPE", "rpe",
    "targetDurationSec", "durationSec", "targetDistanceMeters", "distanceM", "side"
)
private val EXERCISE_KEYS = setOf("name", "equipment", "note", "sets", "eachSide")
private val ROUTINE_KEYS = setOf("id", "name", "exercises")
private val SESSION_KEYS = setOf("id", "clientID", "clientId", "dayKey", "date", "routineID", "routineId", "workoutId")

private fun JSONObject.extras(known: Set<String>): JSONObject? {
    var extra: JSONObject? = null
    for (key in keys()) {
        if (key in known) continue
        val target = extra ?: JSONObject().also { extra = it }
        target.put(key, get(key))
    }
    return extra
}

private fun JSONObject.mergeInto(target: JSONObject) {
    for (key in keys()) target.put(key, get(key))
}

private fun JSONObject.doubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null

private fun JSONObject.intOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

/**
 * Accepts both writers. A `weightLb` is converted to kilograms here and
 * nowhere else -- doing it at the edge means nothing downstream has to
 * remember which file a routine came from.
 */
fun prescribedSetFromJson(o: JSONObject): PrescribedSet = PrescribedSet(
    targetWeightKg = o.doubleOrNull("targetWeightKg")
        ?: o.doubleOrNull("weightLb")?.let { it / LB_PER_KG },
    targetReps = o.intOrNull("targetReps") ?: o.intOrNull("reps"),
    targetRpe = o.doubleOrNull("targetRPE") ?: o.doubleOrNull("rpe"),
    targetDurationSec = o.intOrNull("targetDurationSec") ?: o.intOrNull("durationSec"),
    targetDistanceMeters = o.doubleOrNull("targetDistanceMeters") ?: o.doubleOrNull("distanceM"),
    unknownKeys = o.extras(SET_KEYS),
    // BACKUP-FORMAT's spelling, the one Coach web and Coach iOS write. Absent,
    // "both", or a string this build does not know all read as both rather
    // than failing the import -- and are not written back.
    side = SetSide.fromWire(o.optStringOrNull("side"))
)

/**
 * Coach iOS's spelling, which is what this app writes: kilograms, long names.
 * `side` is `"left"` or `"right"`, omitted when both -- so a set with no side
 * writes exactly the object it always did.
 */
fun PrescribedSet.toJson(): JSONObject {
    val o = JSONObject()
    unknownKeys?.mergeInto(o)
    targetWeightKg?.let { o.put("targetWeightKg", it) }
    targetReps?.let { o.put("targetReps", it) }
    targetRpe?.let { o.put("targetRPE", it) }
    targetDurationSec?.let { o.put("targetDurationSec", it) }
    targetDistanceMeters?.let { o.put("targetDistanceMeters", it) }
    side?.let { o.put("side", it.wire) }
    return o
}

private fun <T> JSONArray?.mapObjects(decode: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> (opt(i) as? JSONObject)?.let(decode) }
}

fun routineExerciseFromJson(o: JSONObject): RoutineExercise = RoutineExercise(
    name = o.optStringOrNull("name").orEmpty(),
    equipment = o.optStringOrNull("equipment").orEmpty(),
    note = o.optStringOrNull("note"),
    sets = o.optJSONArray("sets").mapObjects(::prescribedSetFromJson),
    unknownKeys = o.extras(EXERCISE_KEYS),
    // Only `true` is each side; `false`, a string, or nothing is not.
    eachSide = o.opt("eachSide") == true
)

/** `eachSide: true` only when it is, never `false`: an exercise without it writes what it always did. */
fun RoutineExercise.toJson(): JSONObject {
    val o = JSONObject()
    unknownKeys?.mergeInto(o)
    o.put("name", name)
    o.put("equipment", equipment)
    note?.let { o.put("note", it) }
    if (eachSide) o.put("eachSide", true)
    o.put("sets", JSONArray(sets.map { it.toJson() }))
    return o
}

fun routineFromJson(o: JSONObject): Routine = Routine(
    id = o.optStringOrNull("id") ?: UUID.randomUUID().toString(),
    name = o.optStringOrNull("name").orEmpty(),
    exercises = o.optJSONArray("exercises").mapObjects(::routineExerciseFromJson),
    unknownKeys = o.extras(ROUTINE_KEYS)
)

fun Routine.toJson(): JSONObject {
    val o = JSONObject()
    unknownKeys?.mergeInto(o)
    o.put("id", id)
    o.put("name", name)
    o.put("exercises", JSONArray(exercises.map { it.toJson() }))
    return o
}

/** Null when the file names no client: Coach iOS requires one, so a row
 *  without it is not a session this app can show anyone. */
fun scheduledSessionFromJson(o: JSONObject): ScheduledSession? {
    val client = o.optStringOrNull("clientID") ?: o.optStringOrNull("clientId") ?: return null
    return ScheduledSession(
        id = o.optStringOrNull("id") ?: UUID.randomUUID().toString(),
        clientId = client,
        dayKey = o.optStringOrNull("dayKey") ?: o.optStringOrNull("date").orEmpty(),
        // iOS routineID, web workoutId.
        routineId = o.optStringOrNull("routineID") ?: o.optStringOrNull("routineId")
            ?: o.optStringOrNull("workoutId").orEmpty(),
        unknownKeys = o.extras(SESSION_KEYS)
    )
}

fun ScheduledSession.toJson(): JSONObject {
    val o = JSONObject()
    unknownKeys?.mergeInto(o)
    o.put("id", id)
    o.put("clientID", clientId)
    o.put("dayKey", dayKey)
    o.put("routineID", routineId)
    return o
}

/** Sessions for one client, oldest day first. */
fun List<ScheduledSession>.forClient(clientId: String): List<ScheduledSession> =
    filter { it.clientId == clientId }.sortedBy { it.dayKey }
