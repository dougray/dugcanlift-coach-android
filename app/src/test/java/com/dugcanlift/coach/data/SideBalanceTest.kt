package com.dugcanlift.coach.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The imbalance maths, ported from LIFT web's `lift/sides.js` by way of LIFT for Android's
 * `SideBalanceTest.kt` -- the same cases, against Coach's own [SideSession].
 *
 * The rule: the mean of each side's last three sessions, three sessions a side before there is a
 * figure and four before there is a trend, half a percentage point of movement before the gap has
 * done anything. These pin the web's answers rather than this file's own opinion, because four apps
 * printing different percentages from one log is worse than any of them printing a slightly better
 * number.
 */
class SideBalanceTest {

    /** One session, same reps both sides, so the weights are the whole story: Epley scales both alike. */
    private fun session(day: String, leftLb: Double?, rightLb: Double?, reps: Int = 8) = SideSession(
        dayKey = day,
        leftE1rm = leftLb?.let { it * (1 + reps / 30.0) },
        rightE1rm = rightLb?.let { it * (1 + reps / 30.0) }
    )

    /* ---------- not enough data ---------- */

    @Test fun `two sessions a side is not enough to name an imbalance`() {
        val sessions = listOf(session("2026-09-01", 60.0, 55.0), session("2026-09-08", 60.0, 55.0))
        assertNull(SideBalance.imbalance(sessions))
        assertEquals(ImbalanceTrend.UNKNOWN, SideBalance.trend(sessions))
    }

    @Test fun `three sessions on one side and two on the other is still not enough`() {
        val sessions = listOf(
            session("2026-09-01", 60.0, 55.0),
            session("2026-09-08", 60.0, 55.0),
            session("2026-09-15", 60.0, null)
        )
        assertNull(SideBalance.imbalance(sessions))
        assertEquals(3 to 2, SideBalance.sessionCounts(sessions))
    }

    // A side that was in the log but recorded nothing usable -- a zero-weight bodyweight set, a
    // NaN out of a malformed file -- is not a zero one-rep max. It is a session that side has
    // nothing to say about, and counting it would drag a mean toward zero.
    @Test fun `a zero or non-finite figure is not a session`() {
        val sessions = listOf(
            SideSession("2026-09-01", 100.0, 0.0),
            SideSession("2026-09-08", 100.0, Double.NaN),
            SideSession("2026-09-15", 100.0, 90.0)
        )
        assertNull(SideBalance.imbalance(sessions))
        assertEquals(3 to 1, SideBalance.sessionCounts(sessions))
    }

    @Test fun `no per-side sessions at all is no figure, not a zero gap`() {
        assertNull(SideBalance.imbalance(emptyList()))
        assertEquals(ImbalanceTrend.UNKNOWN, SideBalance.trend(emptyList()))
        assertEquals(0 to 0, SideBalance.sessionCounts(emptyList()))
    }

    /* ---------- perfectly balanced ---------- */

    @Test fun `two sides that match exactly are zero percent apart and name no stronger side`() {
        val sessions = listOf(
            session("2026-09-01", 60.0, 60.0),
            session("2026-09-08", 62.5, 62.5),
            session("2026-09-15", 65.0, 65.0)
        )
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(0.0, imbalance.fraction, 1e-9)
        assertNull(imbalance.stronger)
        // Exactly three sessions: the first three and the last three are the same sessions, so there
        // is no trend to state and the line says so by saying nothing.
        assertEquals(ImbalanceTrend.UNKNOWN, imbalance.trend)
        assertNull(imbalance.was)
    }

    @Test fun `a trend needs a fourth session on each side`() {
        val three = listOf(
            session("2026-09-01", 100.0, 90.0),
            session("2026-09-04", 100.0, 90.0),
            session("2026-09-08", 100.0, 90.0)
        )
        assertEquals(ImbalanceTrend.UNKNOWN, SideBalance.trend(three))
        val four = three + session("2026-09-11", 100.0, 90.0)
        assertEquals(ImbalanceTrend.STEADY, SideBalance.trend(four))
        assertEquals(4, SideBalance.MIN_FOR_TREND)
        assertEquals(3, SideBalance.MIN_SESSIONS)
        // The words Coach web's imbalanceLines prints after "gap "; UNKNOWN has none.
        assertEquals(listOf("widening", "closing", "steady", null), ImbalanceTrend.entries.map { it.wire })
    }

    /* ---------- a real gap ---------- */

    @Test fun `the gap is strong minus weak over strong, on estimated 1RM`() {
        val sessions = listOf(
            session("2026-09-01", 100.0, 90.0),
            session("2026-09-08", 100.0, 90.0),
            session("2026-09-15", 100.0, 90.0)
        )
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(0.10, imbalance.fraction, 1e-9)
        assertEquals(SetSide.LEFT, imbalance.stronger)
        assertEquals(3, imbalance.leftSessions)
        assertEquals(3, imbalance.rightSessions)
    }

    @Test fun `reps count, not just the weight on the bar`() {
        // The right lifts less for more reps: 90 x 10 estimates above 100 x 5, so the right is the
        // stronger side -- left 116.67, right 120.
        val one = SideSession("a", leftE1rm = 100.0 * (1 + 5 / 30.0), rightE1rm = 90.0 * (1 + 10 / 30.0))
        val imbalance = SideBalance.imbalance(listOf(one, one.copy(dayKey = "b"), one.copy(dayKey = "c")))!!
        assertEquals(SetSide.RIGHT, imbalance.stronger)
        assertEquals(0.0278, imbalance.fraction, 1e-4)
    }

    @Test fun `each side is the mean of its last three sessions, not its best in the window`() {
        // The left holds 100 all four sessions; the right was there and then fell away. Taking each
        // side's BEST in the window instead would compare the left's 126.67 against the right's best
        // 120.33 and print 5%, a number describing a fortnight ago. This test catches that.
        val sessions = listOf(
            session("2026-09-01", 100.0, 95.0),
            session("2026-09-04", 100.0, 95.0),
            session("2026-09-08", 100.0, 60.0),
            session("2026-09-11", 100.0, 60.0)
        )
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(0.2833, imbalance.fraction, 1e-4)
        assertEquals(SetSide.LEFT, imbalance.stronger)
        assertEquals(ImbalanceTrend.WIDENING, imbalance.trend)
    }

    @Test fun `a single tired session moves the figure, but only by its third`() {
        val sessions = listOf(
            session("2026-09-01", 100.0, 90.0),
            session("2026-09-08", 100.0, 90.0),
            session("2026-09-15", 70.0, 63.0)
        )
        // Both sides fell by the same proportion, so the GAP is unchanged at 10%.
        assertEquals(0.10, SideBalance.imbalance(sessions)!!.fraction, 1e-9)
    }

    /* ---------- widening and closing ---------- */

    @Test fun `a weak side catching up reads as closing`() {
        val sessions = listOf(
            session("2026-09-01", 100.0, 80.0),
            session("2026-09-04", 100.0, 80.0),
            session("2026-09-08", 100.0, 95.0),
            session("2026-09-11", 100.0, 95.0)
        )
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(ImbalanceTrend.CLOSING, imbalance.trend)
        // Last three on the right: 80, 95, 95 -> mean 90 against the left's 100. The first three,
        // 80, 80, 95, were 15% behind.
        assertEquals(0.15, imbalance.was!!, 1e-9)
    }

    @Test fun `half a percentage point of movement is not a direction`() {
        val sessions = listOf(
            session("2026-09-01", 100.0, 90.0),
            session("2026-09-04", 100.0, 90.0),
            session("2026-09-08", 100.0, 90.0),
            session("2026-09-11", 100.0, 91.0)
        )
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(ImbalanceTrend.STEADY, imbalance.trend)
        assertTrue("the gap did move, just not enough to name", imbalance.fraction < imbalance.was!!)
    }

    @Test fun `a strong side pulling away reads as widening`() {
        val sessions = listOf(
            session("2026-09-01", 100.0, 95.0),
            session("2026-09-04", 100.0, 95.0),
            session("2026-09-08", 120.0, 95.0),
            session("2026-09-11", 120.0, 95.0)
        )
        assertEquals(ImbalanceTrend.WIDENING, SideBalance.imbalance(sessions)!!.trend)
    }

    @Test fun `a side with three sessions has a figure but no direction`() {
        // Two days that trained only the left. They are not right-side zeros -- they are days the
        // right has nothing to say about, so the right counts three sessions: enough for the figure
        // and one short of a trend.
        val sessions = listOf(
            session("2026-09-01", 100.0, null),
            session("2026-09-04", 100.0, null),
            session("2026-09-08", 100.0, 90.0),
            session("2026-09-11", 100.0, 90.0),
            session("2026-09-14", 100.0, 90.0)
        )
        assertEquals(ImbalanceTrend.UNKNOWN, SideBalance.trend(sessions))
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(5, imbalance.leftSessions)
        assertEquals(3, imbalance.rightSessions)
    }

    // The gap's *size*, not which side is ahead: a client whose weaker side overtakes has closed one
    // gap and opened another, and calling that "widening" at the crossover would be wrong.
    @Test fun `a side that overtakes the other has closed the gap, not widened it`() {
        // The right starts 30% behind and ends 5% ahead: a 4.8% gap where there was an 18.3% one.
        val sessions = listOf(
            session("2026-09-01", 100.0, 70.0),
            session("2026-09-04", 100.0, 70.0),
            session("2026-09-08", 100.0, 105.0),
            session("2026-09-11", 100.0, 105.0),
            session("2026-09-14", 100.0, 105.0)
        )
        val imbalance = SideBalance.imbalance(sessions)!!
        assertEquals(SetSide.RIGHT, imbalance.stronger)
        assertEquals(ImbalanceTrend.CLOSING, imbalance.trend)
        assertTrue(imbalance.fraction < imbalance.was!!)
    }
}
