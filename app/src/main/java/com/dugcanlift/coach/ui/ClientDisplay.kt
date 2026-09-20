package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.ExerciseSet
import com.dugcanlift.coach.data.LiftProgression
import com.dugcanlift.coach.data.SideBalance
import java.util.Locale
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Pure display-formatting helpers for [com.dugcanlift.coach.ui.ClientScreen] -- kept free of
 * Compose so they can be unit-tested directly. Everything here follows two rules: an absent value
 * renders as an em dash, never a zero (a week with no logged food is not a week that ate zero
 * calories); and weights are always stored in pounds, converting to [String]-facing kilograms
 * for display only, never touching the stored data.
 */

/** 1 kg == this many pounds -- the exact factor the brief specifies, applied for display only. */
private const val LB_PER_KG = 2.2046226218

/** Converts a stored pound weight to [unit] ("kg" or "lb") for display. Never mutates stored data. */
fun displayWeightValue(lb: Double, unit: String): Double = if (unit == "kg") lb / LB_PER_KG else lb

/**
 * Formats a stored pound weight for display in [unit], trimming a trailing ".0" but keeping one
 * decimal place otherwise. A null weight (nothing logged) renders as an em dash -- never "0",
 * which would misrepresent an unlogged set as a zero-weight one.
 */
fun formatWeight(lb: Double?, unit: String): String {
    if (lb == null) return "—"
    return trimmedNumber(displayWeightValue(lb, unit))
}

/** [formatWeight] with the unit suffixed, e.g. "225 lb" / "102.1 kg". An em dash is left bare. */
fun formatWeightWithUnit(lb: Double?, unit: String): String {
    val value = formatWeight(lb, unit)
    return if (value == "—") value else "$value $unit"
}

/** Renders a whole-number quantity, or an em dash when absent -- never "0" for a value never logged. */
fun formatOrDash(value: Int?): String = value?.toString() ?: "—"

/** Renders a 0..1 rate as a rounded percentage, or an em dash when there was nothing to compute a rate from. */
fun formatPercentOrDash(value: Double?): String = value?.let { "${(it * 100).roundToInt()}%" } ?: "—"

/** Trims a floating value to at most one decimal place, dropping a trailing ".0". */
private fun trimmedNumber(value: Double): String {
    val rounded = round(value * 10.0) / 10.0
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) asLong.toString() else String.format(Locale.US, "%.1f", rounded)
}

/**
 * Splits a [com.dugcanlift.coach.data.Stats.liftKey] ("name|equipment", optionally with a third
 * "|side" field) into a display label -- "Back Squat (Barbell)" -- or the bare name when equipment
 * is blank, matching an equipment-less exercise's empty-string equipment on the wire. Lets two
 * same-named lifts on different equipment (a barbell row and a cable row) read as the distinct
 * lifts they are.
 *
 * The side is deliberately *not* in the label: a per-limb lift is one heading with two lines under
 * it, not two headings a coach has to read as a pair.
 */
fun liftDisplayName(key: String): String {
    val separator = key.indexOf('|')
    if (separator < 0) return key
    val name = key.substring(0, separator)
    val rest = key.substring(separator + 1)
    val equipment = rest.substringBefore('|')
    return if (equipment.isBlank()) name else "$name ($equipment)"
}

/**
 * The one line a per-limb lift's chart carries under it.
 *
 * With three sessions a side it is SHARE-FORMAT's imbalance figure and its trend -- "Left 10%
 * stronger · gap closing". Below that there is no figure, and saying so is the honest answer: the
 * line counts what each side has instead, so a coach can see how far off a figure is rather than
 * wondering why there isn't one.
 *
 * **Tracked and shown, never targeted.** No threshold, no colour, no advice, here or at the call
 * site -- the same discipline saturated fat, sugar and sodium are held to.
 */
fun imbalanceLine(progression: LiftProgression): String? {
    if (!progression.hasSides) return null
    progression.imbalance?.let { return it.description }
    val (left, right) = SideBalance.sessionCounts(progression.sessions)
    return "Left and right tracked · L $left · R $right " +
        "(${SideBalance.MIN_SESSIONS} sessions each before a gap is shown)"
}

/** mm:ss once a minute or more has passed, else "Ns" -- e.g. 45.0 -> "45s", 90.0 -> "1:30". */
fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    val minutes = total / 60
    val secs = total % 60
    return if (minutes > 0) String.format(Locale.US, "%d:%02d", minutes, secs) else "${secs}s"
}

/** A distance in metres, e.g. 500.0 -> "500m", 42.5 -> "42.5m". Never null-checked here -- callers omit the call entirely for a null distance. */
fun formatDistance(meters: Double): String = "${trimmedNumber(meters)}m"

/**
 * Renders one set the way a coach reads it -- "225 × 5 @ RPE 8" -- weight in [unit], with
 * missing pieces simply omitted rather than shown as zero: a bodyweight set skips the weight
 * (`"8 reps"`, not `"0 × 8"`), a set with no RPE skips the "@ RPE" suffix, and a timed set
 * with neither weight nor reps shows its duration instead.
 *
 * [ExerciseSet.distanceMeters] (a sled push, a row logged for metres, a carry) is never dropped:
 * it's appended, comma-separated, to whatever else the set logged -- `"225 × 5, 40m"`,
 * `"8 reps, 40m"`, or a bare duration paired with it (`"1:30, 500m"`) -- and when distance is the
 * *only* thing logged (no reps, weight, or duration) it becomes the whole descriptor (`"500m"`)
 * rather than falling through to the em dash, which used to render a real distance-only set
 * identically to a set nobody logged at all.
 */
fun formatSetLine(set: ExerciseSet, unit: String): String {
    val reps = set.reps
    val weight = set.weightLb
    val duration = set.durationSec
    val distance = set.distanceMeters

    val base: String? = when {
        reps != null && weight != null -> "${formatWeight(weight, unit)} × $reps"
        reps != null -> "$reps reps"
        duration != null -> formatDuration(duration)
        weight != null -> formatWeight(weight, unit)
        else -> null
    }

    val descriptor = when {
        base != null && distance != null -> "$base, ${formatDistance(distance)}"
        base != null -> base
        distance != null -> formatDistance(distance)
        else -> "—"
    }
    val withRpe = set.rpe?.let { "$descriptor @ RPE ${trimmedNumber(it)}" } ?: descriptor
    // "185 x 5 L", trailing, as LIFT for Android writes it. A both-sided set says nothing: printing
    // "both" on every bench press set would be noise on every screen, and absent already means both
    // everywhere else this value travels.
    return set.side?.let { "$withRpe ${it.short}" } ?: withRpe
}
