package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A `dayKey` that `ISO_LOCAL_DATE` rejects -- `"2026-9-3"` (unpadded), `""`, anything a
 * hand-edited, truncated or third-party-encoded file can carry -- parses perfectly well as JSON
 * and then throws out of [Client.daysSinceLastLoggedDay] and [Stats.weeklyBuckets], both of which
 * run inside composition. The roster is the only route to Connect, so that crash loop has no exit
 * but clearing app data. Rejecting the day where it enters the app is the first of two defences;
 * the second is that neither of those two functions throws any more even if one slips past.
 */
internal fun requireDayKey(key: String): String {
    if (DayKey.parse(key) == null) throw JSONException("dayKey \"$key\" is not a yyyy-MM-dd date")
    return key
}

/** Reads [name] from [this], or null if absent or JSON null. */
private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null
private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null
private fun JSONObject.optDoubleOrNull(name: String): Double? = if (has(name) && !isNull(name)) getDouble(name) else null
// optStringOrNull lives in JsonExtensions.kt -- shared with BackupCodec.kt.

data class Goal(
    val calories: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val fiberG: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("calories", calories)
        put("proteinG", proteinG)
        put("fatG", fatG)
        put("carbsG", carbsG)
        put("fiberG", fiberG)
    }

    companion object {
        fun fromJson(json: JSONObject): Goal = Goal(
            calories = json.getInt("calories"),
            proteinG = json.getInt("proteinG"),
            fatG = json.getInt("fatG"),
            carbsG = json.getInt("carbsG"),
            fiberG = json.getInt("fiberG")
        )
    }
}

data class ExerciseSet(
    val exerciseName: String,
    val equipment: String?,
    val weightLb: Double?,
    val reps: Int?,
    val rpe: Double?,
    val durationSec: Double?,
    val distanceMeters: Double?,
    val isWarmup: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("exerciseName", exerciseName)
        put("equipment", equipment ?: JSONObject.NULL)
        put("weightLb", weightLb ?: JSONObject.NULL)
        put("reps", reps ?: JSONObject.NULL)
        put("rpe", rpe ?: JSONObject.NULL)
        put("durationSec", durationSec ?: JSONObject.NULL)
        put("distanceMeters", distanceMeters ?: JSONObject.NULL)
        put("isWarmup", isWarmup)
    }

    companion object {
        fun fromJson(json: JSONObject): ExerciseSet = ExerciseSet(
            exerciseName = json.getString("exerciseName"),
            equipment = json.optStringOrNull("equipment"),
            weightLb = json.optDoubleOrNull("weightLb"),
            reps = json.optIntOrNull("reps"),
            rpe = json.optDoubleOrNull("rpe"),
            durationSec = json.optDoubleOrNull("durationSec"),
            distanceMeters = json.optDoubleOrNull("distanceMeters"),
            isWarmup = json.getBoolean("isWarmup")
        )
    }
}

data class ClientFoodEntry(
    val foodName: String,
    val servings: Double,
    val calories: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val fiberG: Double,
    val meal: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("foodName", foodName)
        put("servings", servings)
        put("calories", calories)
        put("proteinG", proteinG)
        put("fatG", fatG)
        put("carbsG", carbsG)
        put("fiberG", fiberG)
        put("meal", meal)
    }

    companion object {
        fun fromJson(json: JSONObject): ClientFoodEntry = ClientFoodEntry(
            foodName = json.getString("foodName"),
            servings = json.getDouble("servings"),
            calories = json.getDouble("calories"),
            proteinG = json.getDouble("proteinG"),
            fatG = json.getDouble("fatG"),
            carbsG = json.getDouble("carbsG"),
            fiberG = json.getDouble("fiberG"),
            meal = json.getInt("meal")
        )
    }
}

data class TrainingDay(
    val dayKey: String,
    val sessionName: String?,
    val focus: String?,
    val bodyweightLb: Double?,
    val steps: Long?,
    val foodCalories: Double?,
    val foodProteinG: Double?,
    val foodFatG: Double?,
    val foodCarbsG: Double?,
    val foodFiberG: Double?,
    val sets: List<ExerciseSet>,
    val foodEntries: List<ClientFoodEntry>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("dayKey", dayKey)
        put("sessionName", sessionName ?: JSONObject.NULL)
        put("focus", focus ?: JSONObject.NULL)
        put("bodyweightLb", bodyweightLb ?: JSONObject.NULL)
        put("steps", steps ?: JSONObject.NULL)
        put("foodCalories", foodCalories ?: JSONObject.NULL)
        put("foodProteinG", foodProteinG ?: JSONObject.NULL)
        put("foodFatG", foodFatG ?: JSONObject.NULL)
        put("foodCarbsG", foodCarbsG ?: JSONObject.NULL)
        put("foodFiberG", foodFiberG ?: JSONObject.NULL)
        put("sets", JSONArray(sets.map { it.toJson() }))
        put("foodEntries", JSONArray(foodEntries.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): TrainingDay = TrainingDay(
            dayKey = requireDayKey(json.getString("dayKey")),
            sessionName = json.optStringOrNull("sessionName"),
            focus = json.optStringOrNull("focus"),
            bodyweightLb = json.optDoubleOrNull("bodyweightLb"),
            steps = json.optLongOrNull("steps"),
            foodCalories = json.optDoubleOrNull("foodCalories"),
            foodProteinG = json.optDoubleOrNull("foodProteinG"),
            foodFatG = json.optDoubleOrNull("foodFatG"),
            foodCarbsG = json.optDoubleOrNull("foodCarbsG"),
            foodFiberG = json.optDoubleOrNull("foodFiberG"),
            sets = json.optJSONArray("sets")?.let { arr -> (0 until arr.length()).map { ExerciseSet.fromJson(arr.getJSONObject(it)) } }.orEmpty(),
            foodEntries = json.optJSONArray("foodEntries")?.let { arr -> (0 until arr.length()).map { ClientFoodEntry.fromJson(arr.getJSONObject(it)) } }.orEmpty()
        )
    }
}

data class Client(
    val id: String,
    val name: String,
    val displayUnit: String,
    val platform: String?,
    val lastImportedAtEpochMs: Long,
    val goal: Goal?,
    val days: List<TrainingDay>
) {
    /**
     * Days since the client's most recent stored day. **Any** stored day counts —
     * a bodyweight-only or steps-only day is the client reporting in, and the
     * silence banner exists to find people who have stopped talking to their
     * coach, not people who trained somewhere else. `lastImportedAtEpochMs` is
     * ignored entirely. Null when the client has no days at all.
     *
     * This matches Coach iOS's `Client.daysSinceLastLoggedDay`
     * (`Sources/Shared/Models.swift`), which takes the max over every stored day
     * with no filter. Android previously required sets, food totals or food
     * entries, so a client who only ever weighed in read "Nothing logged yet"
     * here and "Logged today" on the phone, and sat permanently in the silence
     * banner while sending data daily. Doug's call, 2026-09-13: match iOS.
     */
    fun daysSinceLastLoggedDay(today: String): Int? {
        // Non-throwing throughout: this is called from inside RosterScreen's composition, where an
        // exception is an unrecoverable crash loop (see [requireDayKey]). An unparseable key is not
        // a date the app can reason about, so it cannot be "the most recent logged day" -- the
        // newest key that IS parseable is. Note "2026-9-3" sorts AFTER "2026-09-06" as a string,
        // so the old maxOfOrNull over raw strings picked exactly the key it could not parse.
        if (DayKey.parse(today) == null) return null
        val latest = days
            .mapNotNull { DayKey.parse(it.dayKey) }
            .maxOrNull() ?: return null
        return DayKey.daysBetween(latest.toString(), today)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("displayUnit", displayUnit)
        put("platform", platform ?: JSONObject.NULL)
        put("lastImportedAtEpochMs", lastImportedAtEpochMs)
        put("goal", goal?.toJson() ?: JSONObject.NULL)
        put("days", JSONArray(days.map { it.toJson() }))
    }

    companion object {
        fun fromJson(json: JSONObject): Client = Client(
            id = json.getString("id"),
            name = json.getString("name"),
            displayUnit = json.getString("displayUnit"),
            platform = json.optStringOrNull("platform"),
            lastImportedAtEpochMs = json.getLong("lastImportedAtEpochMs"),
            goal = if (json.has("goal") && !json.isNull("goal")) Goal.fromJson(json.getJSONObject("goal")) else null,
            days = json.optJSONArray("days")?.let { arr -> (0 until arr.length()).map { TrainingDay.fromJson(arr.getJSONObject(it)) } }.orEmpty()
        )
    }
}
