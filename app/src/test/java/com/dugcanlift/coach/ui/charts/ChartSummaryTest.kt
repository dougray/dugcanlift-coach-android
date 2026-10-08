package com.dugcanlift.coach.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Test

/** What TalkBack hears in place of a hand-drawn chart. */
class ChartSummaryTest {
    @Test fun `a series reads as where it started, where it ended, and its range`() {
        assertEquals(
            "Bodyweight, 3 points, from 182 lb on 2026-07-01 to 176.4 lb on 2026-09-20. High 183 lb, low 176.4 lb.",
            chartSummary("Bodyweight", listOf("2026-07-01" to 182.0, "2026-08-01" to 183.0, "2026-09-20" to 176.44), "lb")
        )
    }

    @Test fun `one point and none say so`() {
        assertEquals("Calories by week, one point: 2100 on W36.", chartSummary("Calories by week", listOf("W36" to 2100.0)))
        assertEquals("Bodyweight, nothing logged yet.", chartSummary("Bodyweight", emptyList()))
    }
}
