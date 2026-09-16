package com.dugcanlift.coach.ui

import com.dugcanlift.coach.data.OutdoorActivity
import com.dugcanlift.coach.data.OutdoorBest
import com.dugcanlift.coach.data.TrainingDay
import com.dugcanlift.kit.DayKey
import com.dugcanlift.kit.OutdoorShare
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Display strings for a client's runs, walks and hikes -- Coach web's `route.js` formatting, which
 * is the strings LIFT itself shows. Kept free of Compose, like [formatWeight] and friends, so every
 * one is pinned by a plain unit test.
 *
 * Distances are stored in metres and converted here only: miles for a client who reads pounds,
 * kilometres for one who reads kilograms. A null best renders as a dash, never as zero.
 */

private const val METERS_PER_MILE = 1609.344
private val OUTDOOR_TYPE_LABELS = listOf("Run", "Walk", "Hike")

/** "Run", "Walk" or "Hike" for the wire's 0, 1, 2; null for a type this app does not know. */
fun outdoorTypeLabel(type: Int): String? = OUTDOOR_TYPE_LABELS.getOrNull(type)

/** "km" for a client whose unit is kg, "mi" for everyone else -- the same split as their weights. */
fun distanceUnitFor(displayUnit: String): String = if (displayUnit == "kg") "km" else "mi"

private fun unitMeters(distanceUnit: String): Double = if (distanceUnit == "km") 1000.0 else METERS_PER_MILE

/** "28:40", or "1:02:10" past an hour. */
fun formatActivityDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/** "3.11 mi" / "5.01 km". */
fun formatActivityDistance(meters: Long, distanceUnit: String): String =
    String.format(Locale.US, "%.2f %s", meters / unitMeters(distanceUnit), distanceUnit)

/** "8:03 /mi" from seconds per kilometre, rounded to the whole second in [distanceUnit]. */
fun formatPace(secondsPerKm: Double, distanceUnit: String): String {
    val per = (secondsPerKm / 1000 * unitMeters(distanceUnit)).roundToLong()
    return String.format(Locale.US, "%d:%02d /%s", per / 60, per % 60, distanceUnit)
}

/**
 * An activity's own pace, or null under 1 km -- the shortest distance LIFT lets set a pace, since
 * anything shorter is mostly GPS noise -- or with no time to divide by.
 */
fun activityPace(distanceMeters: Long, durationSec: Long, distanceUnit: String): String? {
    if (distanceMeters < OutdoorShare.MINIMUM_PACE_DISTANCE_METERS || durationSec <= 0) return null
    return formatPace(durationSec.toDouble() / distanceMeters * 1000, distanceUnit)
}

/** "Run · 2 activities" / "Walk · 1 activity". */
fun bestHeading(best: OutdoorBest): String =
    "${outdoorTypeLabel(best.type) ?: "Activity"} · ${best.count} ${if (best.count == 1) "activity" else "activities"}"

/** Farthest, Longest and Fastest pace, each a dash when the client has nothing to show for it. */
fun bestStats(best: OutdoorBest, distanceUnit: String): List<Pair<String, String>> = listOf(
    "Farthest" to (best.farthestMeters?.let { formatActivityDistance(it, distanceUnit) } ?: "—"),
    "Longest" to (best.longestSec?.let { formatActivityDuration(it) } ?: "—"),
    "Fastest pace" to (best.fastestSecPerKm?.let { formatPace(it.toDouble(), distanceUnit) } ?: "—")
)

/** The Last route card's date, e.g. "Wed, Sep 16", in the phone's own zone. */
fun formatRouteDate(startedAtEpochSec: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(Instant.ofEpochSecond(startedAtEpochSec).atZone(zone))

/** A day key as "Sep 13"; the key itself if it does not parse, never a throw inside composition. */
fun formatShortDay(dayKey: String, locale: Locale = Locale.getDefault()): String =
    DayKey.parse(dayKey)?.let { DateTimeFormatter.ofPattern("MMM d", locale).format(it) } ?: dayKey

/** One activity as a line in a day's log or the Recent card: "3.11 mi · 28:40". */
fun activitySummary(activity: OutdoorActivity, distanceUnit: String): String =
    "${formatActivityDistance(activity.distanceMeters, distanceUnit)} · ${formatActivityDuration(activity.durationSec)}"

/** Days holding an outdoor activity, newest first, at most [limit] of them -- Coach web's Recent. */
fun recentOutdoorDays(days: List<TrainingDay>, limit: Int = 10): List<TrainingDay> =
    days.filter { it.outdoor.isNotEmpty() }.sortedByDescending { it.dayKey }.take(limit)
