package com.dugcanlift.coach.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** What TalkBack hears in place of a hand-drawn chart. */
class ChartSummaryTest {
    @Test fun `a series reads as where it started, where it ended, and its range`() {
        assertEquals(
            "Bodyweight, 3 points, from 182 lb on 2026-07-01 to 176.4 lb on 2026-09-20. High 183 lb, low 176.4 lb.",
            chartSummary("Bodyweight", listOf("2026-07-01" to 182.0, "2026-08-01" to 183.0, "2026-09-20" to 176.44), "lb")
        )
    }

    /** TalkBack hears the dates the axis shows, through the axis's own formatter. */
    @Test fun `the dates are spoken as the axis writes them, not as ISO keys`() {
        val points = listOf("2026-07-01" to 182.0, "2026-08-01" to 183.0, "2026-09-20" to 176.44)
        val us = ChartAxis.labeler(points.map { it.first }, Locale.US) { ChartAxisTest.EN_US_DAY }
        assertEquals(
            "Bodyweight, 3 points, from 182 lb on Jul 1 to 176.4 lb on Sep 20. High 183 lb, low 176.4 lb.",
            chartSummary("Bodyweight", points, "lb", us)
        )
        assertEquals("Bodyweight, one point: 182 lb on Jul 1.", chartSummary("Bodyweight", points.take(1), "lb", us))
        // A chart across a new year says the year, as its axis does.
        val across = listOf("2025-12-28" to 180.0, "2026-01-04" to 179.0)
        val withYear = ChartAxis.labeler(across.map { it.first }, Locale.US) {
            assertEquals(ChartAxis.DAY_YEAR_SKELETON, it); ChartAxisTest.EN_US_DAY_YEAR
        }
        assertEquals(
            "Bodyweight, 2 points, from 180 on Dec 28, 2025 to 179 on Jan 4, 2026. High 180, low 179.",
            chartSummary("Bodyweight", across, dateLabel = withYear)
        )
        // And in the reader's own locale.
        val de = ChartAxis.labeler(points.map { it.first }, Locale.GERMANY) { "d. MMM" }
        assertEquals(
            "Bodyweight, 3 points, from 182 on 1. Juli to 176.4 on 20. Sept.. High 183, low 176.4.",
            chartSummary("Bodyweight", points, dateLabel = de)
        )
    }

    @Test fun `one point and none say so`() {
        assertEquals("Calories by week, one point: 2100 on W36.", chartSummary("Calories by week", listOf("W36" to 2100.0)))
        assertEquals("Bodyweight, nothing logged yet.", chartSummary("Bodyweight", emptyList()))
    }
}
