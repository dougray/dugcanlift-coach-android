package com.dugcanlift.coach.data

import com.dugcanlift.kit.DayKey
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** A day's food totals, whichever source produced them (device `ft` totals or the sum of itemized entries). */
data class FoodTotals(
    val calories: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val fiberG: Double
)

/** Saturated fat, sugar and sodium -- tracked and shown, never targeted. */
enum class Nutrient(val label: String, val unit: String) {
    SATURATED_FAT("Saturated fat", "g"),
    SUGAR("Sugar", "g"),
    SODIUM("Sodium", "mg");

    /** This nutrient's total on a day, or null when no food that day recorded it. */
    fun total(t: DayNutrientTotals): Double? = when (this) {
        SATURATED_FAT -> t.saturatedFatG
        SUGAR -> t.sugarG
        SODIUM -> t.sodiumMg
    }

    /** How many of the day's [DayNutrientTotals.foods] this nutrient's total covers. */
    fun covered(t: DayNutrientTotals): Int = when (this) {
        SATURATED_FAT -> t.withSaturatedFat
        SUGAR -> t.withSugar
        SODIUM -> t.withSodium
    }
}

/** [perDay] averaged over [days] days that recorded the nutrient, [partialDays] of them from only some foods. */
data class NutrientAverage(val nutrient: Nutrient, val perDay: Double, val days: Int, val partialDays: Int)

/**
 * One lift's estimated-1RM progression: the series to chart, and the gap between its sides when it
 * was logged per limb.
 *
 * [key] is `"name|equipment"` -- the lift a coach reads as one heading. The *series* underneath are
 * still kept apart by side ([Stats.liftKey]); this type is the grouping for the screen, not for the
 * numbers.
 *
 * Every series is one point per day -- the day's best working set -- including [both]. That is the
 * same figure [imbalance] averages, so the lines and the number under them cannot disagree, and a
 * two-sided lift does not read on a different scale from a per-limb one.
 */
data class LiftProgression(
    val key: String,
    val both: List<Pair<String, Double>>,
    val sessions: List<SideSession>,
    val imbalance: SideImbalance?
) {
    val leftPoints: List<Pair<String, Double>> get() = sessions.mapNotNull { s -> s.leftE1rm?.let { s.dayKey to it } }
    val rightPoints: List<Pair<String, Double>> get() = sessions.mapNotNull { s -> s.rightE1rm?.let { s.dayKey to it } }

    /** True once any set of this lift named a limb, which is what turns the per-side parts on. */
    val hasSides: Boolean get() = leftPoints.isNotEmpty() || rightPoints.isNotEmpty()

    /** Nothing to draw at all -- neither side, nor an unmarked set. */
    val isEmpty: Boolean get() = both.isEmpty() && !hasSides
}

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
     *
     * Groups every stored day into its bucket in a single pass over [Client.days] rather than
     * re-filtering the whole list once per bucket -- with [historySpanWeeks] now able to ask for
     * a hundred-plus buckets covering a client's whole history, the old approach was quadratic in
     * the number of days. A day whose key [daysBetweenOrNull] can't place (unparseable, or simply
     * older than the oldest bucket this call was asked for) lands in no bucket, same as before.
     */
    fun weeklyBuckets(client: Client, weeks: Int, endKey: String): List<WeekStats> {
        if (weeks <= 0) return emptyList()

        // bucketsFromNewest[0] is the 7 days ending on endKey itself; bucketsFromNewest[k] is the
        // 7-day block k weeks further back. Filled in one pass, then read back out oldest-first below.
        val bucketsFromNewest = Array(weeks) { mutableListOf<TrainingDay>() }
        for (day in client.days) {
            val distance = daysBetweenOrNull(day.dayKey, endKey) ?: continue
            if (distance < 0) continue
            val bucketFromNewest = distance / 7
            if (bucketFromNewest >= weeks) continue
            bucketsFromNewest[bucketFromNewest].add(day)
        }

        val goal = client.goal
        return (0 until weeks).map { i ->
            val bucketFromNewest = weeks - 1 - i
            val end = DayKey.adding(-7 * bucketFromNewest, endKey)
            val bucketDays = bucketsFromNewest[bucketFromNewest]

            val sessions = bucketDays.count { it.sets.isNotEmpty() }
            val sets = bucketDays.sumOf { it.sets.size }
            val volume = bucketDays.sumOf { dayVolume(it) }

            val fuels = bucketDays.mapNotNull { fuel(it) }
            val kcalAvg = fuels.takeIf { it.isNotEmpty() }?.let { (it.sumOf { f -> f.calories } / it.size).roundToInt() }
            val proteinAvg = fuels.takeIf { it.isNotEmpty() }?.let { (it.sumOf { f -> f.proteinG } / it.size).roundToInt() }
            val proteinHitRate = if (fuels.isEmpty() || goal == null) null
                else fuels.count { it.proteinG >= 0.95 * goal.proteinG }.toDouble() / fuels.size

            val stepsLogged = bucketDays.mapNotNull { it.steps }
            val stepsAvg = stepsLogged.takeIf { it.isNotEmpty() }?.let { (it.sum().toDouble() / it.size).roundToInt() }

            WeekStats(end, sessions, sets, volume, kcalAvg, proteinAvg, proteinHitRate, stepsAvg)
        }
    }

    /** Ten years: comfortably beyond any real client's tenure, but far short of the tens of thousands
     * of weekly buckets a single absurd-but-syntactically-valid day key (e.g. a restored file holding
     * "0001-01-01") would otherwise demand of [historySpanWeeks] and, through it, [weeklyBuckets]. */
    const val MAX_HISTORY_SPAN_WEEKS = 520

    /**
     * Weeks needed for [weeklyBuckets] to cover a client's entire history: from their oldest stored
     * day through [today], rounded up to whole 7-day buckets so the oldest day always lands inside
     * the earliest bucket. Zero for a client with no days at all (or none with a key
     * [DayKey.parse] accepts) -- there is nothing to chart, and callers should treat that as the
     * existing empty state rather than requesting a single meaningless bucket. Capped at
     * [MAX_HISTORY_SPAN_WEEKS]; see its doc for why.
     */
    fun historySpanWeeks(client: Client, today: String): Int {
        val oldest = client.days.mapNotNull { DayKey.parse(it.dayKey) }.minOrNull() ?: return 0
        val end = DayKey.parse(today) ?: return 0
        val days = ChronoUnit.DAYS.between(oldest, end).toInt().coerceAtLeast(0)
        return (days / 7 + 1).coerceAtMost(MAX_HISTORY_SPAN_WEEKS)
    }

    /**
     * Average of [WeekStats.proteinHitRate] across [weeks], excluding weeks that logged nothing to
     * compute a rate from (`null`) -- never averaging in a zero for a week that simply wasn't
     * logged, the same null-exclusion rule [weeklyBuckets] itself applies to each week individually.
     * Null when every week has nothing to average, or [weeks] itself is empty.
     */
    fun avgProteinHitRate(weeks: List<WeekStats>): Double? {
        val rates = weeks.mapNotNull { it.proteinHitRate }
        return if (rates.isEmpty()) null else rates.average()
    }

    /** Bodyweight over time: days with no bodyweight logged are skipped; result is in chronological (day key) order. */
    fun bodyweightSeries(client: Client): List<Pair<String, Double>> =
        client.days.mapNotNull { day -> day.bodyweightLb?.let { day.dayKey to it } }.sortedBy { it.first }

    /**
     * Estimated one-rep max over time, one series per lift identity, **one point per day**: the
     * best working set that lift recorded that day, oldest first.
     *
     * Per day rather than per set so that a point on a chart and a "session" in the imbalance rule
     * are the same thing everywhere -- SHARE-FORMAT's figure is the mean of each side's last three
     * *sessions*, and a chart that plotted sets would have put three points where that rule counts
     * one. It applies to two-sided lifts too, which did once plot every set: the same lift must not
     * change shape depending on whether its client happens to log limbs.
     *
     * Keyed `"name|equipment|side"` -- the kit's share-format construction (trimmed name, `|`,
     * trimmed equipment) with the side joined on -- because the wire format treats equipment as
     * part of a lift's identity, and per-limb logging joins the side to it for exactly the same
     * reason: a barbell row and a cable row are both "Row" but are not the same lift, and a
     * left-arm row and a right-arm row are no more the same lift than those two are. A null
     * equipment (an equipment-less exercise) is treated as an empty string, matching what the
     * wire's own `ex.equipment.trim()` produces for one; a null side (both, the only thing a set
     * written before per-limb logging can be) is likewise an empty string.
     *
     * Merging the sides here is not a lesser chart, it is a *wrong* one: the two limbs interleave
     * set for set and the line zig-zags between them, which is precisely the bug `"name|equipment"`
     * was introduced to fix for a cable pulldown against a machine one. See SHARE-FORMAT,
     * "Tolerating the bits is not enough; Coach must group on side".
     */
    fun perLiftE1rm(client: Client): Map<String, List<Pair<String, Double>>> {
        val best = LinkedHashMap<String, MutableMap<String, Double>>()
        for (day in client.days) {
            for (set in day.sets) {
                val estimate = e1rm(set) ?: continue
                val byDay = best.getOrPut(liftKey(set.exerciseName, set.equipment, set.side)) { mutableMapOf() }
                byDay[day.dayKey] = maxOf(byDay[day.dayKey] ?: estimate, estimate)
            }
        }
        return best.mapValues { (_, byDay) -> byDay.toSortedMap().map { (dayKey, e) -> dayKey to e } }
    }

    /**
     * One [SideSession] per day this client trained [name] on [equipment] with a named limb, oldest
     * first, holding that day's best estimated 1RM for each side. A side the day did not record is
     * null, not zero: "that limb has nothing to say about this day".
     *
     * Read straight off [perLiftE1rm], so the figures the imbalance averages are literally the
     * points the chart draws -- the number under a chart and the chart itself cannot disagree.
     */
    fun sideSessions(client: Client, name: String, equipment: String?): List<SideSession> {
        val series = perLiftE1rm(client)
        val left = series[liftKey(name, equipment, SetSide.LEFT)].orEmpty().toMap()
        val right = series[liftKey(name, equipment, SetSide.RIGHT)].orEmpty().toMap()
        return (left.keys + right.keys).sorted().map { SideSession(it, left[it], right[it]) }
    }

    /**
     * Every lift this client has an estimate for, one entry per `"name|equipment"`, each carrying
     * the series to chart and -- when the lift was logged per limb -- the gap between the sides.
     *
     * A lift with sides draws its sides as separate lines and never averages them together. A lift
     * with both (sets logged before the client turned the toggle on, and sided ones after) keeps
     * all three: those earlier sets are real and leaving them off the chart would be a quieter lie
     * than showing them.
     */
    fun perLiftProgressions(client: Client): List<LiftProgression> {
        val series = perLiftE1rm(client)
        // One entry per lift, in the order its first charted set appears.
        val lifts = LinkedHashMap<String, Pair<String, String?>>()
        for (day in client.days) {
            for (set in day.sets) {
                if (e1rm(set) == null) continue
                lifts.getOrPut(matchKey(set.exerciseName, set.equipment)) { set.exerciseName to set.equipment }
            }
        }
        return lifts.map { (key, named) ->
            val (name, equipment) = named
            val sided = series.containsKey(liftKey(name, equipment, SetSide.LEFT)) ||
                series.containsKey(liftKey(name, equipment, SetSide.RIGHT))
            val sessions = if (sided) sideSessions(client, name, equipment) else emptyList()
            LiftProgression(
                key = key,
                both = series[liftKey(name, equipment, null)].orEmpty(),
                sessions = sessions,
                imbalance = SideBalance.imbalance(sessions)
            )
        }
    }

    /**
     * The mean daily total of each of saturated fat, sugar and sodium over the [windowDays] days
     * ending on [endKey] (inclusive), counting **only days that recorded that nutrient** -- a day
     * with no sodium is not a zero-sodium day, and dividing by the window would say it was. A
     * nutrient no day in the window recorded is left out of the result, not averaged to zero.
     *
     * A day whose total covers only some of its foods still counts, as the floor it is;
     * [NutrientAverage.partialDays] says how many did, so the screen can say so.
     */
    fun nutrientAverages(client: Client, windowDays: Int, endKey: String): List<NutrientAverage> {
        if (windowDays <= 0) return emptyList()
        val inWindow = client.days.mapNotNull { day ->
            val totals = day.nutrientTotals ?: return@mapNotNull null
            val distance = daysBetweenOrNull(day.dayKey, endKey) ?: return@mapNotNull null
            totals.takeIf { distance in 0 until windowDays }
        }
        return Nutrient.entries.mapNotNull { nutrient ->
            val recorded = inWindow.filter { nutrient.total(it) != null }
            if (recorded.isEmpty()) return@mapNotNull null
            NutrientAverage(
                nutrient = nutrient,
                perDay = recorded.sumOf { nutrient.total(it)!! } / recorded.size,
                days = recorded.size,
                partialDays = recorded.count { nutrient.covered(it) < it.foods }
            )
        }
    }

    /** `"name|equipment|side"`; the side is an empty string for both, which is what an unmarked set is. */
    fun liftKey(name: String, equipment: String?, side: SetSide?): String =
        "${matchKey(name, equipment)}|${side?.wire ?: ""}"

    /** The first two thirds of a [liftKey]: one lift, whichever limbs it was logged with. */
    fun matchKey(name: String, equipment: String?): String = "${name.trim()}|${(equipment ?: "").trim()}"

    /**
     * [DayKey.daysBetween] throws on a key `ISO_LOCAL_DATE` rejects, and [weeklyBuckets] calls it
     * for every stored day from inside ClientScreen's composition. A day the app cannot place on a
     * calendar belongs in no bucket -- it is skipped, never fatal. See `requireDayKey`.
     */
    private fun daysBetweenOrNull(from: String, to: String): Int? {
        val a = DayKey.parse(from) ?: return null
        val b = DayKey.parse(to) ?: return null
        return DayKey.daysBetween(a.toString(), b.toString())
    }
}
