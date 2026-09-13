package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.ExerciseSet
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
 * Splits a [com.dugcanlift.coach.data.Stats.perLiftE1rm] key ("name|equipment") into a display
 * label -- "Back Squat (Barbell)" -- or the bare name when equipment is blank, matching an
 * equipment-less exercise's empty-string equipment on the wire. Lets two same-named lifts on
 * different equipment (a barbell row and a cable row) read as the distinct lifts they are.
 */
fun liftDisplayName(key: String): String {
    val separator = key.indexOf('|')
    if (separator < 0) return key
    val name = key.substring(0, separator)
    val equipment = key.substring(separator + 1)
    return if (equipment.isBlank()) name else "$name ($equipment)"
}

/** mm:ss once a minute or more has passed, else "Ns" -- e.g. 45.0 -> "45s", 90.0 -> "1:30". */
fun formatDuration(seconds: Double): String {
    val total = seconds.roundToInt()
    val minutes = total / 60
    val secs = total % 60
    return if (minutes > 0) String.format(Locale.US, "%d:%02d", minutes, secs) else "${secs}s"
}

/**
 * Renders one set the way a coach reads it -- "225 × 5 @ RPE 8" -- weight in [unit], with
 * missing pieces simply omitted rather than shown as zero: a bodyweight set skips the weight
 * (`"8 reps"`, not `"0 × 8"`), a set with no RPE skips the "@ RPE" suffix, and a timed set
 * with neither weight nor reps shows its duration instead.
 */
fun formatSetLine(set: ExerciseSet, unit: String): String {
    val reps = set.reps
    val weight = set.weightLb
    val descriptor = when {
        reps != null && weight != null -> "${formatWeight(weight, unit)} × $reps"
        reps != null -> "$reps reps"
        set.durationSec != null -> formatDuration(set.durationSec)
        weight != null -> formatWeight(weight, unit)
        else -> "—"
    }
    return set.rpe?.let { "$descriptor @ RPE ${trimmedNumber(it)}" } ?: descriptor
}
