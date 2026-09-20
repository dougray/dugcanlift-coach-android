package com.dugcanlift.coach.data

import kotlin.math.roundToInt

/**
 * One session of one lift, reduced to a best estimated 1RM per side. A null is "that side was not
 * trained that day" -- never a zero, which would drag a mean down as though the limb had been
 * loaded and failed.
 */
data class SideSession(val dayKey: String, val leftE1rm: Double?, val rightE1rm: Double?)

/**
 * Whether the gap has grown or shrunk across the window.
 *
 * [UNKNOWN] is LIFT web's `trend: null` -- fewer than [SideBalance.MIN_FOR_TREND] sessions on a
 * side, so there is no earlier figure honest enough to compare against. It is not "steady".
 */
enum class ImbalanceTrend { WIDENING, CLOSING, STEADY, UNKNOWN }

/**
 * The imbalance between two sides of one lift: `(strong - weak) / strong` on estimated 1RM, as a
 * fraction of 1.
 *
 * [stronger] is null when the two are exactly equal, which is the only time there is no stronger
 * side to name. [was] is the same figure over each side's *first* three sessions, which is what
 * [trend] compares against; it is null whenever the trend is [ImbalanceTrend.UNKNOWN].
 */
data class SideImbalance(
    val fraction: Double,
    val stronger: SetSide?,
    val trend: ImbalanceTrend,
    val was: Double?,
    val leftSessions: Int,
    val rightSessions: Int
) {
    /** Whole percent, the only precision this number deserves. */
    val percent: Int get() = (fraction * 100).roundToInt()

    /**
     * The one line the lift's chart shows.
     *
     * Tracked and shown, never targeted -- the discipline saturated fat, sugar and sodium follow.
     * It states the gap and which way it is going and stops there: no threshold, no colour, no
     * warning, no advice. A 10% difference is ordinary in most people, and what one client's means
     * is a question for the trainer reading it.
     */
    val description: String
        get() {
            val head = when {
                stronger == null || percent == 0 -> "Even"
                else -> "${stronger.label} $percent% stronger"
            }
            val tail = when (trend) {
                ImbalanceTrend.WIDENING -> "gap widening"
                ImbalanceTrend.CLOSING -> "gap closing"
                ImbalanceTrend.STEADY -> "holding steady"
                ImbalanceTrend.UNKNOWN -> null
            }
            return listOfNotNull(head, tail).joinToString(" · ")
        }
}

/**
 * The per-side maths behind the estimated-1RM charts, kept pure and free of Compose so it can be
 * tested directly -- a rule that decides a number a person reads about their own body cannot live
 * in a composable's state, where nothing can reach it.
 *
 * **This is a port of LIFT web's `lift/sides.js`, function for function**, by way of LIFT for
 * Android's own `SideBalance.kt`, and it is a port rather than a second opinion on purpose: four
 * apps printing different percentages from the same log is worse than any one of them printing a
 * slightly better number. If the rule changes, it changes in `sides.js` first and is ported again.
 *
 * Everything reads estimated 1RM, because that is the one number that compares 60x8 on the left
 * against 65x6 on the right. Warmups need no side of their own: [Stats.e1rm] already returns null
 * for one, so a warmup's side changes no figure here.
 */
object SideBalance {

    /**
     * How many sessions each side needs before an imbalance figure is shown.
     *
     * Three, from SHARE-FORMAT's "The imbalance figure". A figure drawn from one session each would
     * move ten points on a day someone went in tired, and be read as a finding.
     */
    const val MIN_SESSIONS = 3

    /**
     * And how many before a *trend* is. With exactly three, the first three and the last three are
     * the same sessions, so "steady" would be arithmetic rather than an observation.
     */
    const val MIN_FOR_TREND = 4

    /**
     * Half a percentage point. Below it the gap has not done anything -- this is an estimate built
     * out of an estimate, and `sides.js` draws the line in the same place.
     */
    private const val TREND_EPSILON = 0.005

    /**
     * The imbalance across a window of sessions, or null when there is not enough to say -- fewer
     * than [MIN_SESSIONS] recorded on either side.
     *
     * Each side's figure is the **mean of its last [MIN_SESSIONS] sessions**, not its best and not
     * its latest: `sides.js`'s rule, for the reason the threshold exists at all. One heavy day is
     * not a change in strength, and one tired day is not either.
     *
     * Note "its last three *sessions*", counted on that side's own recorded ones -- a day that
     * trained only the left is not a right-side zero, it is a day the right has nothing to say
     * about.
     */
    fun imbalance(sessions: List<SideSession>): SideImbalance? {
        val left = recorded(sessions.map { it.leftE1rm })
        val right = recorded(sessions.map { it.rightE1rm })
        if (left.size < MIN_SESSIONS || right.size < MIN_SESSIONS) return null

        val nowLeft = mean(left.takeLast(MIN_SESSIONS))
        val nowRight = mean(right.takeLast(MIN_SESSIONS))
        val fraction = gap(nowLeft, nowRight) ?: return null

        val was = earlierGap(left, right)
        return SideImbalance(
            fraction = fraction,
            stronger = when {
                nowLeft == nowRight -> null
                nowLeft > nowRight -> SetSide.LEFT
                else -> SetSide.RIGHT
            },
            trend = trendFrom(fraction, was),
            was = was,
            leftSessions = left.size,
            rightSessions = right.size
        )
    }

    /**
     * Widening or closing, from the gap over each side's first [MIN_SESSIONS] sessions against the
     * gap over its last [MIN_SESSIONS].
     *
     * The comparison is of the *size* of the gap, not of which side is ahead: a client whose weaker
     * side overtakes has closed one gap and opened another, and calling that "widening" at the
     * crossover would be wrong.
     */
    fun trend(sessions: List<SideSession>): ImbalanceTrend {
        val left = recorded(sessions.map { it.leftE1rm })
        val right = recorded(sessions.map { it.rightE1rm })
        if (left.size < MIN_SESSIONS || right.size < MIN_SESSIONS) return ImbalanceTrend.UNKNOWN
        val now = gap(mean(left.takeLast(MIN_SESSIONS)), mean(right.takeLast(MIN_SESSIONS)))
            ?: return ImbalanceTrend.UNKNOWN
        return trendFrom(now, earlierGap(left, right))
    }

    /** How many sessions each side actually recorded, which is what the "not enough yet" line says. */
    fun sessionCounts(sessions: List<SideSession>): Pair<Int, Int> =
        recorded(sessions.map { it.leftE1rm }).size to recorded(sessions.map { it.rightE1rm }).size

    /* ---------- the pieces, in `sides.js` order ---------- */

    /** A side's usable figures. A null, a NaN or a zero is "that side wasn't trained", never a zero 1RM. */
    private fun recorded(values: List<Double?>): List<Double> =
        values.mapNotNull { v -> v?.takeIf { it.isFinite() && it > 0.0 } }

    private fun mean(values: List<Double>): Double = values.sum() / values.size

    /** `(strong - weak) / strong`, or null when there is no strong side to divide by. */
    private fun gap(left: Double, right: Double): Double? {
        val strong = maxOf(left, right)
        if (!strong.isFinite() || strong <= 0.0) return null
        val weak = minOf(left, right)
        return (strong - weak) / strong
    }

    /** The same figure over each side's first [MIN_SESSIONS], or null until both have [MIN_FOR_TREND]. */
    private fun earlierGap(left: List<Double>, right: List<Double>): Double? {
        if (left.size < MIN_FOR_TREND || right.size < MIN_FOR_TREND) return null
        return gap(mean(left.take(MIN_SESSIONS)), mean(right.take(MIN_SESSIONS)))
    }

    private fun trendFrom(now: Double, was: Double?): ImbalanceTrend {
        if (was == null) return ImbalanceTrend.UNKNOWN
        val moved = now - was
        return when {
            moved > TREND_EPSILON -> ImbalanceTrend.WIDENING
            moved < -TREND_EPSILON -> ImbalanceTrend.CLOSING
            else -> ImbalanceTrend.STEADY
        }
    }
}
