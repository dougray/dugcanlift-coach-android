package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey
import org.json.JSONArray
import org.json.JSONObject

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
            dayKey = json.getString("dayKey"),
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
     * Days since the most recent day that was actually **logged** — has sets,
     * food totals, or food entries — not merely imported (an empty day from
     * the shared link doesn't count, and `lastImportedAtEpochMs` is ignored
     * entirely). Null when the client has never logged anything.
     */
    fun daysSinceLastLoggedDay(today: String): Int? =
        days.filter { it.sets.isNotEmpty() || it.foodEntries.isNotEmpty() || it.foodCalories != null }
            .maxOfOrNull { it.dayKey }
            ?.let { DayKey.daysBetween(it, today) }

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
