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
private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null
private fun JSONObject.optDoubleOrNull(name: String): Double? = if (has(name) && !isNull(name)) getDouble(name) else null
/** A finite number at [name], or null -- absent, JSON null, a string, NaN. A value nobody can read is unrecorded. */
internal fun JSONObject.optFiniteOrNull(name: String): Double? =
    (opt(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }
// optStringOrNull and optLongOrNull live in JsonExtensions.kt -- shared with BackupCodec.kt.

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

/**
 * One itemised food, **as eaten**: every number here is already multiplied by [servings] -- see
 * [ShareLinkImporter]. [saturatedFatG], [sugarG] and [sodiumMg] follow the same rule as the macros
 * (the wire's `fe` is per serving; the store is not) and are null when the food recorded none,
 * never zero. Absent in any file written before they existed, which reads as null.
 */
data class ClientFoodEntry(
    val foodName: String,
    val servings: Double,
    val calories: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val fiberG: Double,
    val meal: Int,
    val saturatedFatG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null
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
        // Only when known: a key absent is exactly what an older file says, and an explicit null
        // would add three keys to every food of every backup for nothing.
        saturatedFatG?.let { put("saturatedFatG", it) }
        sugarG?.let { put("sugarG", it) }
        sodiumMg?.let { put("sodiumMg", it) }
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
            meal = json.getInt("meal"),
            saturatedFatG = json.optFiniteOrNull("saturatedFatG"),
            sugarG = json.optFiniteOrNull("sugarG"),
            sodiumMg = json.optFiniteOrNull("sodiumMg")
        )
    }
}

/**
 * A day's saturated fat, sugar and sodium -- SHARE-FORMAT's `fx`, stored with named fields.
 *
 * Each total covers only the foods that recorded it (as eaten, multiplied by servings); [foods] is
 * every food logged that day and the `with*` counts say how many of them each total covers. A
 * partial total is a floor, not a day, which is why the counts are kept at all. A total with no
 * food behind it is null, never zero. Tracked, never targeted: there is no goal for any of them.
 */
data class DayNutrientTotals(
    val saturatedFatG: Double?,
    val sugarG: Double?,
    val sodiumMg: Double?,
    val foods: Int,
    val withSaturatedFat: Int,
    val withSugar: Int,
    val withSodium: Int
) {
    /** True when none of the three totals is known -- such a value is stored as no totals at all. */
    val isEmpty: Boolean get() = saturatedFatG == null && sugarG == null && sodiumMg == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("saturatedFatG", saturatedFatG ?: JSONObject.NULL)
        put("sugarG", sugarG ?: JSONObject.NULL)
        put("sodiumMg", sodiumMg ?: JSONObject.NULL)
        put("foods", foods)
        put("withSaturatedFat", withSaturatedFat)
        put("withSugar", withSugar)
        put("withSodium", withSodium)
    }

    companion object {
        /** Null for an absent, null or all-unknown object: nothing recorded is no totals. */
        fun fromJson(json: JSONObject?): DayNutrientTotals? {
            if (json == null) return null
            return DayNutrientTotals(
                saturatedFatG = json.optFiniteOrNull("saturatedFatG"),
                sugarG = json.optFiniteOrNull("sugarG"),
                sodiumMg = json.optFiniteOrNull("sodiumMg"),
                foods = json.optInt("foods", 0).coerceAtLeast(0),
                withSaturatedFat = json.optInt("withSaturatedFat", 0).coerceAtLeast(0),
                withSugar = json.optInt("withSugar", 0).coerceAtLeast(0),
                withSodium = json.optInt("withSodium", 0).coerceAtLeast(0)
            ).takeIf { !it.isEmpty }
        }
    }
}

/**
 * One run, walk or hike from a day's `o` (SHARE-FORMAT "Outdoor"). [type] is the wire's: 0 run,
 * 1 walk, 2 hike -- an unknown type never gets this far, see [ShareLinkImporter]. Distances are
 * metres, always, whatever the client's display unit; 0 is "nothing measured", as the wire says.
 */
data class OutdoorActivity(
    val type: Int,
    val durationSec: Long,
    val distanceMeters: Long,
    val climbMeters: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("durationSec", durationSec)
        put("distanceMeters", distanceMeters)
        put("climbMeters", climbMeters)
    }

    companion object {
        fun fromJson(json: JSONObject): OutdoorActivity = OutdoorActivity(
            type = json.getInt("type"),
            durationSec = json.getLong("durationSec"),
            distanceMeters = json.getLong("distanceMeters"),
            climbMeters = json.getLong("climbMeters")
        )
    }
}

/**
 * One type's all-time bests from `ob`. A null best is "nothing to show" -- no distance ever
 * measured, or nothing long enough to set a pace -- and renders as a dash, never as zero.
 */
data class OutdoorBest(
    val type: Int,
    val count: Int,
    val farthestMeters: Long?,
    val longestSec: Long?,
    val fastestSecPerKm: Long?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("count", count)
        put("farthestMeters", farthestMeters ?: JSONObject.NULL)
        put("longestSec", longestSec ?: JSONObject.NULL)
        put("fastestSecPerKm", fastestSecPerKm ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): OutdoorBest = OutdoorBest(
            type = json.getInt("type"),
            count = json.getInt("count"),
            farthestMeters = json.optLongOrNull("farthestMeters"),
            longestSec = json.optLongOrNull("longestSec"),
            fastestSecPerKm = json.optLongOrNull("fastestSecPerKm")
        )
    }
}

/**
 * `lr`: the client's newest route. The four numbers are the whole activity; [polyline] is not --
 * the client's app has already cut the first and last 200 m off it. Kept as the wire's encoded
 * string rather than as points: it is what the sender produced, it is a fraction of the size, and
 * decoding 150 points when the card draws is nothing.
 */
data class LastRoute(
    val type: Int,
    val startedAtEpochSec: Long,
    val durationSec: Long,
    val distanceMeters: Long,
    val climbMeters: Long,
    val polyline: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("startedAtEpochSec", startedAtEpochSec)
        put("durationSec", durationSec)
        put("distanceMeters", distanceMeters)
        put("climbMeters", climbMeters)
        put("polyline", polyline)
    }

    companion object {
        fun fromJson(json: JSONObject): LastRoute = LastRoute(
            type = json.getInt("type"),
            startedAtEpochSec = json.getLong("startedAtEpochSec"),
            durationSec = json.getLong("durationSec"),
            distanceMeters = json.getLong("distanceMeters"),
            climbMeters = json.getLong("climbMeters"),
            polyline = json.getString("polyline")
        )
    }
}

/** Decodes every object in an optional array; absent (a file written before the field) is empty. */
internal fun <T> JSONObject.optObjectList(name: String, decode: (JSONObject) -> T): List<T> =
    optJSONArray(name)?.let { arr -> (0 until arr.length()).map { decode(arr.getJSONObject(it)) } }.orEmpty()

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
    val foodEntries: List<ClientFoodEntry>,
    /** A day's `o`, in start order. A day holding only this is still a day. */
    val outdoor: List<OutdoorActivity> = emptyList(),
    /** A day's `fx`: saturated fat, sugar and sodium with their coverage. Null when none was recorded. */
    val nutrientTotals: DayNutrientTotals? = null
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
        put("outdoor", JSONArray(outdoor.map { it.toJson() }))
        nutrientTotals?.let { put("nutrientTotals", it.toJson()) }
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
            foodEntries = json.optJSONArray("foodEntries")?.let { arr -> (0 until arr.length()).map { ClientFoodEntry.fromJson(arr.getJSONObject(it)) } }.orEmpty(),
            outdoor = json.optObjectList("outdoor", OutdoorActivity::fromJson),
            nutrientTotals = DayNutrientTotals.fromJson(json.optJSONObject("nutrientTotals"))
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
    val days: List<TrainingDay>,
    /** `ob` from the newest send, or null when that send carried none. */
    val outdoorBests: List<OutdoorBest>? = null,
    /** `lr` from the newest send, or null when that send carried none -- route sharing is off. */
    val lastRoute: LastRoute? = null,
    /**
     * `z` of the newest send absorbed, Unix seconds. Null for a client stored before this field,
     * which any send counts as newer than. Decides whether [outdoorBests] and [lastRoute] are
     * replaced -- see [ShareLinkImporter].
     */
    val exportedAtEpochSec: Long? = null
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
        put("outdoorBests", outdoorBests?.let { b -> JSONArray(b.map { it.toJson() }) } ?: JSONObject.NULL)
        put("lastRoute", lastRoute?.toJson() ?: JSONObject.NULL)
        put("exportedAtEpochSec", exportedAtEpochSec ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): Client = Client(
            id = json.getString("id"),
            name = json.getString("name"),
            displayUnit = json.getString("displayUnit"),
            platform = json.optStringOrNull("platform"),
            lastImportedAtEpochMs = json.getLong("lastImportedAtEpochMs"),
            goal = if (json.has("goal") && !json.isNull("goal")) Goal.fromJson(json.getJSONObject("goal")) else null,
            days = json.optJSONArray("days")?.let { arr -> (0 until arr.length()).map { TrainingDay.fromJson(arr.getJSONObject(it)) } }.orEmpty(),
            outdoorBests = outdoorBestsFromJson(json),
            lastRoute = lastRouteFromJson(json),
            exportedAtEpochSec = json.optLongOrNull("exportedAtEpochSec")
        )

        /** Shared with [BackupCodec], which spells the client envelope itself. */
        internal fun outdoorBestsFromJson(json: JSONObject): List<OutdoorBest>? =
            if (json.has("outdoorBests") && !json.isNull("outdoorBests")) json.optObjectList("outdoorBests", OutdoorBest::fromJson) else null

        internal fun lastRouteFromJson(json: JSONObject): LastRoute? =
            if (json.has("lastRoute") && !json.isNull("lastRoute")) LastRoute.fromJson(json.getJSONObject("lastRoute")) else null
    }
}
