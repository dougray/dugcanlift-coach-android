package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey
import kotlin.math.roundToInt

/** A day's food totals, whichever source produced them (device `ft` totals or the sum of itemized entries). */
data class FoodTotals(
    val calories: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val fiberG: Double
)

/** One 7-day bucket ending on [endKey] (inclusive), newest bucket last in `weeklyBuckets`'s result. */
data class WeekStats(
    val endKey: String,
    val sessions: Int,
    val sets: Int,
    val volume: Double,
    val kcalAvg: Int?,
    val proteinAvg: Int?,
    val proteinHitRate: Double?,
    val stepsAvg: Int?
)

/**
 * The numbers a coach reads: training volume, estimated one-rep max, daily fuel, and
 * weekly summaries, derived from the raw [TrainingDay]/[ExerciseSet]/[ClientFoodEntry] data.
 */
object Stats {
    /** Σ weightLb × reps over non-warmup sets; a null weight or null reps contributes zero. */
    fun dayVolume(day: TrainingDay): Double =
        day.sets.filter { !it.isWarmup }.sumOf { (it.weightLb ?: 0.0) * (it.reps ?: 0) }

    /**
     * Epley estimated one-rep max for a single set: `weightLb * (1 + reps / 30.0)`.
     * No repetition cap — a 20+ rep set still produces an estimate (Doug, 2026-09-13).
     * Null for warmups, or when weight or reps is missing or not greater than zero.
     */
    fun e1rm(set: ExerciseSet): Double? {
        val w = set.weightLb
        val r = set.reps
        if (set.isWarmup || w == null || r == null || w <= 0.0 || r <= 0) return null
        return w * (1 + r / 30.0)
    }

    /** A day's fuel: the `ft` totals when present, else the sum of the (already as-eaten) itemized entries, else null. */
    fun fuel(day: TrainingDay): FoodTotals? {
        val cal = day.foodCalories
        if (cal != null) {
            return FoodTotals(cal, day.foodProteinG ?: 0.0, day.foodFatG ?: 0.0, day.foodCarbsG ?: 0.0, day.foodFiberG ?: 0.0)
        }
        if (day.foodEntries.isNotEmpty()) {
            return FoodTotals(
                day.foodEntries.sumOf { it.calories },
                day.foodEntries.sumOf { it.proteinG },
                day.foodEntries.sumOf { it.fatG },
                day.foodEntries.sumOf { it.carbsG },
                day.foodEntries.sumOf { it.fiberG }
            )
        }
        return null
    }

    /**
     * [weeks] 7-day buckets aligned to [endKey], newest last. A "session" is a day with at
     * least one set. Averages (kcal, protein, steps) and the protein hit rate consider only
     * days that actually logged the thing being averaged — never dividing by seven, and never
     * zero when nothing was logged: they are null instead.
     */
    fun weeklyBuckets(client: Client, weeks: Int, endKey: String): List<WeekStats> =
        (0 until weeks).map { i ->
            val end = DayKey.adding(-7 * (weeks - 1 - i), endKey)
            val bucketDays = client.days.filter { DayKey.daysBetween(it.dayKey, end) in 0..6 }

            val sessions = bucketDays.count { it.sets.isNotEmpty() }
            val sets = bucketDays.sumOf { it.sets.size }
            val volume = bucketDays.sumOf { dayVolume(it) }

            val fuels = bucketDays.mapNotNull { fuel(it) }
            val kcalAvg = fuels.takeIf { it.isNotEmpty() }?.let { (it.sumOf { f -> f.calories } / it.size).roundToInt() }
            val proteinAvg = fuels.takeIf { it.isNotEmpty() }?.let { (it.sumOf { f -> f.proteinG } / it.size).roundToInt() }
            val goal = client.goal
            val proteinHitRate = if (fuels.isEmpty() || goal == null) null
                else fuels.count { it.proteinG >= 0.95 * goal.proteinG }.toDouble() / fuels.size

            val stepsLogged = bucketDays.mapNotNull { it.steps }
            val stepsAvg = stepsLogged.takeIf { it.isNotEmpty() }?.let { (it.sum().toDouble() / it.size).roundToInt() }

            WeekStats(end, sessions, sets, volume, kcalAvg, proteinAvg, proteinHitRate, stepsAvg)
        }

    /** Bodyweight over time: days with no bodyweight logged are skipped; result is in chronological (day key) order. */
    fun bodyweightSeries(client: Client): List<Pair<String, Double>> =
        client.days.mapNotNull { day -> day.bodyweightLb?.let { day.dayKey to it } }.sortedBy { it.first }

    /** Estimated one-rep max over time, one series per exercise name, in the order the client's days appear. */
    fun perLiftE1rm(client: Client): Map<String, List<Pair<String, Double>>> {
        val series = LinkedHashMap<String, MutableList<Pair<String, Double>>>()
        for (day in client.days) {
            for (set in day.sets) {
                val estimate = e1rm(set) ?: continue
                series.getOrPut(set.exerciseName) { mutableListOf() }.add(day.dayKey to estimate)
            }
        }
        return series
    }
}
